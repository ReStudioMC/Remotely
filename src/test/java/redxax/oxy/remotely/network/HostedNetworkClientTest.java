package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.util.JsonTreeParser;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedNetworkClientTest {
    @Test
    void sendsExactDraftLongsAndAcceptsMatchingStatus() {
        String requestId = UUID.randomUUID().toString();
        String poolId = UUID.randomUUID().toString();
        String draftId = UUID.randomUUID().toString();
        String activationId = UUID.randomUUID().toString();
        NetworkCommand command = new NetworkCommand.Create(1, requestId, "network-1", "Network", 25565, List.of(
                new NetworkCommand.Member(new NetworkMemberSource.Draft(poolId, draftId, "", activationId, Long.MAX_VALUE,
                        new NetworkMemberSource.DraftMetadata("Lobby", "minecraft:java", "paper", Map.of(), Map.of()),
                        new NetworkMemberSource.Compute(Long.MAX_VALUE, 100), new NetworkMemberSource.Compute(4096, 200),
                        new NetworkMemberSource.Storage(8192, 0)), "lobby", NetworkMemberRole.LOBBY, 25565, 1, false)), Map.of());
        String[] submitted = {""};
        HostedNetworkClient client = new HostedNetworkClient((method, path, body) -> {
            assertEquals("POST", method);
            assertEquals("/hosted-networks/commands", path);
            submitted[0] = body;
            return Async.completed(status(requestId, "network-1", "CREATE", "9223372036854775807"));
        });

        NetworkOperationStatus result = client.submit(command).join();
        assertEquals(Long.MAX_VALUE, result.networkRevision());
        var wire = JsonTreeParser.parse(submitted[0]).getAsJsonObject();
        assertEquals("0", wire.get("expectedRevision").getAsString());
        var source = wire.getAsJsonArray("members").get(0).getAsJsonObject().getAsJsonObject("source");
        assertEquals(Long.toString(Long.MAX_VALUE), source.get("expectedRevision").getAsString());
        assertEquals(Long.toString(Long.MAX_VALUE), source.getAsJsonObject("installer").get("ramMiB").getAsString());
    }

    @Test
    void decodesExactLifecycleTimesAndRejectsMismatchedOrNoncanonicalStatus() {
        UUID requestId = UUID.randomUUID();
        String body = """
                {"schemaVersion":1,"requestId":"%s","networkId":"network-1","command":"LIFECYCLE",
                 "state":"RUNNING","stage":"LIFECYCLE","operationId":"operation-1",
                 "networkRevision":"9223372036854775807","message":"Starting","createdAt":"2","updatedAt":"3",
                 "lifecycleJob":{"schemaVersion":1,"jobId":"job-1","networkId":"network-1",
                 "networkRevision":"9223372036854775807","operation":"START","status":"RUNNING",
                 "initiator":"Test","createdAt":"2","updatedAt":"3","attempt":1,"message":"Starting",
                 "steps":[{"stepId":"step-1","instanceId":"server-1","nodeId":"node-1","routeName":"lobby",
                 "action":"START","status":"RUNNING","startedAt":"9223372036854775807","completedAt":"0","message":"Starting"}]},
                 "members":[]}
                """.formatted(requestId);
        HostedNetworkClient client = new HostedNetworkClient((method, path, ignored) -> Async.completed(body));
        NetworkOperationStatus result = client.operation("network-1", requestId, NetworkCommand.Type.LIFECYCLE).join();
        assertEquals(Long.MAX_VALUE, result.lifecycleJob().steps().getFirst().startedAt());

        HostedNetworkClient wrong = new HostedNetworkClient((method, path, ignored) -> Async.completed(
                body.replace("\"command\":\"LIFECYCLE\"", "\"command\":\"SAVE\"")));
        assertInstanceOf(IllegalArgumentException.class, wrong.operation("network-1", requestId,
                NetworkCommand.Type.LIFECYCLE).failure());

        HostedNetworkClient imprecise = new HostedNetworkClient((method, path, ignored) -> Async.completed(
                body.replace("\"networkRevision\":\"9223372036854775807\"",
                        "\"networkRevision\":9223372036854775807")));
        assertInstanceOf(IllegalArgumentException.class, imprecise.operation("network-1", requestId).failure());
    }

    @Test
    void executeSubmitsOnceAndPollsTheSameOperationUntilSuccess() {
        NetworkCommand.Create command = command();
        QueueScheduler scheduler = new QueueScheduler();
        List<String> requests = new ArrayList<>();
        HostedNetworkClient client = new HostedNetworkClient((method, path, body) -> {
            requests.add(method + " " + path);
            String state = switch (requests.size()) {
                case 1 -> "ADMITTED";
                case 2 -> "RUNNING";
                default -> "SUCCEEDED";
            };
            return Async.completed(status(command.requestId(), command.networkId(), "CREATE", "1", state, ""));
        });

        Async<NetworkOperationStatus> result = client.execute(command, scheduler, () -> true);
        assertFalse(result.isDone());
        assertEquals(1, scheduler.pending());
        scheduler.runNext();
        assertFalse(result.isDone());
        scheduler.runNext();

        assertEquals("SUCCEEDED", result.join().state().name());
        assertEquals(List.of("POST /hosted-networks/commands",
                "GET /hosted-networks/network-1/operations/" + command.requestId(),
                "GET /hosted-networks/network-1/operations/" + command.requestId()), requests);
        assertEquals(0, scheduler.pending());
    }

    @Test
    void executeRetainsTerminalFailureStatusWithoutResubmitting() {
        NetworkCommand.Create command = command();
        QueueScheduler scheduler = new QueueScheduler();
        List<String> requests = new ArrayList<>();
        HostedNetworkClient client = new HostedNetworkClient((method, path, body) -> {
            requests.add(method);
            return Async.completed(status(command.requestId(), command.networkId(), "CREATE", "1",
                    requests.size() == 1 ? "ADMITTED" : "NEEDS_REVIEW", "Check The Network Job"));
        });

        Async<NetworkOperationStatus> result = client.execute(command, scheduler, () -> true);
        scheduler.runNext();

        HostedNetworkClient.OperationFailure failure = assertInstanceOf(HostedNetworkClient.OperationFailure.class, result.failure());
        assertEquals("NEEDS_REVIEW", failure.status().state().name());
        assertEquals(command.networkId(), failure.status().networkId());
        assertEquals("Check The Network Job", failure.getMessage());
        assertEquals(List.of("POST", "GET"), requests);
        assertEquals(0, scheduler.pending());
    }

    @Test
    void accountChangeStopsQueuedPollingWithoutAnotherRequest() {
        NetworkCommand.Create command = command();
        QueueScheduler scheduler = new QueueScheduler();
        AtomicBoolean current = new AtomicBoolean(true);
        List<String> requests = new ArrayList<>();
        HostedNetworkClient client = new HostedNetworkClient((method, path, body) -> {
            requests.add(method);
            return Async.completed(status(command.requestId(), command.networkId(), "CREATE", "1"));
        });

        Async<NetworkOperationStatus> result = client.execute(command, scheduler, current::get);
        current.set(false);
        scheduler.runNext();

        assertInstanceOf(IllegalStateException.class, result.failure());
        assertTrue(result.failure().getMessage().contains("Account Changed"));
        assertEquals(List.of("POST"), requests);
        assertEquals(0, scheduler.pending());
    }

    @Test
    void ambiguousSubmissionFailureDoesNotRetryOrPoll() {
        NetworkCommand.Create command = command();
        QueueScheduler scheduler = new QueueScheduler();
        List<String> requests = new ArrayList<>();
        HostedNetworkClient client = new HostedNetworkClient((method, path, body) -> {
            requests.add(method + " " + path);
            return Async.failed(new IllegalStateException("Connection Lost After Submit"));
        });

        Async<NetworkOperationStatus> result = client.execute(command, scheduler, () -> true);

        assertEquals("Connection Lost After Submit", result.failure().getMessage());
        assertEquals(List.of("POST /hosted-networks/commands"), requests);
        assertEquals(0, scheduler.pending());
    }

    private static NetworkCommand.Create command() {
        return new NetworkCommand.Create(1, UUID.randomUUID().toString(), "network-1", "Network", 25565,
                List.of(new NetworkCommand.Member(new NetworkMemberSource.ExistingServer("server-1"), "proxy",
                        NetworkMemberRole.PROXY, 25565, 0, false)), Map.of());
    }

    private static String status(String requestId, String networkId, String command, String revision) {
        return status(requestId, networkId, command, revision, "ADMITTED", "");
    }

    private static String status(String requestId, String networkId, String command, String revision, String state, String message) {
        return """
                {"schemaVersion":1,"requestId":"%s","networkId":"%s","command":"%s",
                 "state":"%s","stage":"VALIDATING","operationId":"operation-1",
                 "networkRevision":"%s","message":"%s","createdAt":"1","updatedAt":"1","members":[]}
                """.formatted(requestId, networkId, command, state, revision, message);
    }

    private static final class QueueScheduler implements TaskScheduler {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public ScheduledTask schedule(Runnable task, Duration delay) {
            tasks.add(task);
            return new ScheduledTask() {
                private boolean cancelled;

                @Override
                public boolean cancel() {
                    cancelled = true;
                    return tasks.remove(task);
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        @Override
        public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            throw new UnsupportedOperationException();
        }

        int pending() {
            return tasks.size();
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }
}
