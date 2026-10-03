package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.screens.resources.ResourceContainerCapabilities;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.http.HttpHeaders;
import restudio.rescreen.platform.http.HttpRequest;
import restudio.rescreen.platform.http.HttpResponse;
import restudio.rescreen.platform.http.HttpTransport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceBackupCompletionTest {
    private final RecordingTransport transport = new RecordingTransport();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final BrowserLaunchSession.Metadata session = new BrowserLaunchSession.Metadata("grant", "ticket", "audience",
            Set.of("server.read"), "node", "expiry", "user", "", "", "", "", "");
    private final BrowserRemotelyServerApi api = new BrowserRemotelyServerApi(transport, () -> scheduler.now, scheduler,
            (uri, headers, listener) -> Async.failed(new UnsupportedOperationException()), session, null,
            "https://example.test/api", "https://example.test/api/remotely-web/capabilities", () -> session);
    private int installations;

    @AfterEach
    void close() {
        api.close();
    }

    @Test
    void unavailableBackupObservationDisablesBackupAndCreatesNoArchive() {
        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.identifier = "server-1";
        ServerUiCapabilityProvider capabilities = new ServerUiCapabilityProvider() {
            @Override
            public Availability availability(ServerModels.ClientServerView selected, String action) {
                return "backups.create".equals(action) ? Availability.supported()
                        : Availability.missing("Backup Listing Is Unavailable");
            }
        };
        HostedResourceContext context = new HostedResourceContext(null, api, "server-1", null, null, null,
                null, capabilities, server);
        HostedResourceContainerProvider provider = new HostedResourceContainerProvider(null, server, null, context);

        assertFalse(provider.capability(ResourceContainerCapabilities.BACKUP).available());
        assertEquals("Backup Listing Is Unavailable", provider.capability(ResourceContainerCapabilities.BACKUP).detail());
        Async<Void> result = context.createResourceBackup("Pre-Update Backup");
        assertTrue(result.isDone());
        assertEquals("Backup Listing Is Unavailable", result.failure().getMessage());
        assertEquals(0, transport.requests.size());
    }

    @Test
    void allocatedBackupDoesNotStartUpdatesUntilItsArchiveSucceeds() {
        Async<Void> result = update(() -> true);
        transport.reply(0, 200, backup(null, false));

        assertFalse(result.isDone());
        assertEquals(0, installations);
        scheduler.next();
        transport.reply(1, 200, "[" + backup(null, false) + "]");
        assertFalse(result.isDone());
        assertEquals(0, installations);
        scheduler.next();
        transport.reply(2, 200, "[" + backup("2026-09-30T10:00:00Z", true) + "]");

        assertTrue(result.isDone());
        assertNull(result.failure());
        assertEquals(1, installations);
        assertEquals(0, scheduler.pending());
    }

    @Test
    void failedArchiveBlocksEveryUpdate() {
        Async<Void> result = update(() -> true);
        transport.reply(0, 200, backup(null, false));
        scheduler.next();
        transport.reply(1, 200, "[" + backup("2026-09-30T10:00:00Z", false) + "]");

        assertTrue(result.isDone());
        assertEquals("Backup Failed. Updates Were Not Started", result.failure().getMessage());
        assertEquals(0, installations);
        assertEquals(0, scheduler.pending());
    }

    @Test
    void ambiguousCreationObservesTheSameRequestWithoutCreatingAnotherBackup() {
        Async<Void> result = update(() -> true);
        String requestId = BrowserJson.object(new String(transport.requests.getFirst().bodyPublisher().orElseThrow().bytes(),
                StandardCharsets.UTF_8)).get("request_id").getAsString();
        transport.reply(0, 500, "{}");
        scheduler.next();

        assertTrue(transport.requests.get(1).uri().getPath().endsWith("/backups/requests/" + requestId));
        transport.reply(1, 200, "{\"requestId\":\"" + requestId + "\",\"state\":\"succeeded\",\"backupId\":\"backup-1\","
                + "\"replaySafe\":true,\"backup\":" + backup(null, false) + "}");
        assertFalse(result.isDone());
        scheduler.next();
        transport.reply(2, 200, "[" + backup("2026-09-30T10:00:00Z", true) + "]");

        assertNull(result.failure());
        assertEquals(1, installations);
        assertEquals(1, transport.requests.stream().filter(request -> request.method().equals("POST")).count());
    }

    @Test
    void observationLossRecoversWithoutStartingUpdatesEarly() {
        Async<Void> result = update(() -> true);
        transport.reply(0, 200, backup(null, false));
        scheduler.next();
        transport.reply(1, 503, "{}");
        assertFalse(result.isDone());
        assertEquals(0, installations);
        scheduler.next();
        transport.reply(2, 200, "[" + backup("2026-09-30T10:00:00Z", true) + "]");

        assertNull(result.failure());
        assertEquals(1, installations);
    }

    @Test
    void cancellationStopsObservationAndRejectsTheLateCreationResult() {
        Async<Void> result = update(() -> true);
        result.cancel();
        transport.reply(0, 200, backup("2026-09-30T10:00:00Z", true));

        assertTrue(result.isCancelled());
        assertEquals(0, installations);
        assertEquals(0, scheduler.pending());
        assertEquals(1, transport.requests.size());
    }

    @Test
    void sessionRetirementCannotReleaseTheUpdateGate() {
        HostedResourceContext context = new HostedResourceContext(null, null, "server-1", null, null, null);
        Object operation = context.captureOperation();
        Async<Void> result = update(() -> context.isOperationCurrent(operation, null));
        transport.reply(0, 200, backup(null, false));
        context.invalidate();
        scheduler.next();

        assertTrue(result.isDone());
        assertEquals(0, installations);
        assertEquals(1, transport.requests.size());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void deadlineFailsAStalledCreationAndLeavesUpdatesUntouched() {
        Async<Void> result = update(() -> true);
        scheduler.next();

        assertTrue(result.isDone());
        assertTrue(result.failure().getMessage().contains("Updates Were Not Started"));
        assertEquals(0, installations);
        assertEquals(0, scheduler.pending());
    }

    private Async<Void> update(BooleanSupplier current) {
        return HostedResourceContext.awaitResourceBackup(api, "server-1", "Pre-Update Backup", scheduler, current)
                .thenRun(() -> installations++);
    }

    private static String backup(String completedAt, boolean success) {
        return "{\"uuid\":\"backup-1\",\"name\":\"Pre-Update Backup\",\"completedAt\":"
                + (completedAt == null ? "null" : "\"" + completedAt + "\"") + ",\"isSuccessful\":" + success + "}";
    }

    private static final class ManualScheduler implements TaskScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();
        private long now = 1000;

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public ScheduledTask schedule(Runnable task, Duration delay) {
            ManualTask scheduled = new ManualTask(task, now + delay.toMillis());
            tasks.add(scheduled);
            return scheduled;
        }

        @Override
        public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            throw new UnsupportedOperationException();
        }

        private void next() {
            ManualTask task = tasks.stream().filter(value -> !value.cancelled)
                    .min(Comparator.comparingLong(value -> value.at)).orElseThrow();
            task.cancelled = true;
            now = task.at;
            task.action.run();
        }

        private long pending() {
            return tasks.stream().filter(task -> !task.cancelled).count();
        }

        private static final class ManualTask implements ScheduledTask {
            private final Runnable action;
            private final long at;
            private boolean cancelled;

            private ManualTask(Runnable action, long at) {
                this.action = action;
                this.at = at;
            }

            @Override
            public boolean cancel() {
                boolean active = !cancelled;
                cancelled = true;
                return active;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        }
    }

    private static final class RecordingTransport implements HttpTransport {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<Async<HttpResponse<byte[]>>> responses = new ArrayList<>();

        @Override
        public <T> Async<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            Async<HttpResponse<byte[]>> response = Async.pending();
            requests.add(request);
            responses.add(response);
            return response.thenApply(value -> {
                try {
                    return new HttpResponse<>(value.statusCode(), value.headers(), handler.apply(value.body()));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }

        @Override
        public Async<HttpResponse<Void>> sendStreaming(HttpRequest request, HttpResponse.ChunkConsumer consumer) {
            return Async.failed(new UnsupportedOperationException());
        }

        private void reply(int index, int status, String body) {
            responses.get(index).complete(new HttpResponse<>(status, HttpHeaders.empty(), body.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
