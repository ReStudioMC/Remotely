package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyGroup;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.network.protocol.NetworkOperationState;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostedNetworkPendingStoreTest {
    @Test
    void resumesFrozenRequestAfterStoreReopensAndClearsOnlyMatchingTerminalIdentity() {
        MemoryConfig config = new MemoryConfig();
        HostedNetworkPendingStore first = new HostedNetworkPendingStore(config);
        NetworkCommand.Create command = command();
        HostedNetworkPendingStore.Pending admitted = first.admit("true:account-a", command);
        assertThrows(IllegalStateException.class, () -> first.admit("true:account-a", command()));
        assertNull(first.current("true:account-b"));

        HostedNetworkPendingStore reopened = new HostedNetworkPendingStore(config);
        HostedNetworkPendingStore.Pending pending = reopened.current("true:account-a");
        assertNotNull(pending);
        assertEquals(admitted.body(), pending.body());
        assertEquals(command.requestId(), pending.command().requestId());
        assertEquals(command.networkId(), pending.command().networkId());
        NetworkMemberSource.Draft draft = (NetworkMemberSource.Draft) pending.command().members().getFirst().source();
        assertEquals(Long.MAX_VALUE, draft.expectedRevision());

        String[] submitted = {""};
        HostedNetworkClient client = new HostedNetworkClient((method, path, body) -> {
            submitted[0] = body;
            return Async.completed(status(command));
        });
        client.execute(pending.command(), pending.body(), TaskScheduler.direct(), () -> true).join();
        assertEquals(admitted.body(), submitted[0]);

        reopened.markTerminal("true:account-a", command.requestId(), command.networkId(), NetworkOperationState.NEEDS_REVIEW);
        assertEquals(NetworkOperationState.NEEDS_REVIEW,
                new HostedNetworkPendingStore(config).current("true:account-a").terminal());
        assertThrows(IllegalStateException.class, () -> reopened.acknowledgeTerminal("true:account-a",
                UUID.randomUUID().toString(), command.networkId()));
        assertNotNull(reopened.current("true:account-a"));
        reopened.acknowledgeTerminal("true:account-a", command.requestId(), command.networkId());
        assertNull(new HostedNetworkPendingStore(config).current("true:account-a"));
    }

    @Test
    void discardsUnfinishedRequestAndKeepsNewRequestWhenOldOutcomeArrives() {
        MemoryConfig config = new MemoryConfig();
        HostedNetworkPendingStore store = new HostedNetworkPendingStore(config);
        NetworkCommand.Create original = command();
        store.admit("true:account-a", original);

        assertThrows(IllegalStateException.class, () -> store.discard("true:account-a", "other-request", original.networkId()));
        assertThrows(IllegalStateException.class, () -> store.discard("true:account-b", original.requestId(), original.networkId()));
        assertEquals(original.requestId(), store.current("true:account-a").command().requestId());
        store.discard("true:account-a", original.requestId(), original.networkId());
        HostedNetworkPendingStore reopened = new HostedNetworkPendingStore(config);
        assertNull(reopened.current("true:account-a"));

        NetworkCommand.Create next = command();
        reopened.admit("true:account-a", next);
        reopened.clearTerminal("true:account-a", original.requestId(), original.networkId());
        reopened.markTerminal("true:account-a", original.requestId(), original.networkId(), NetworkOperationState.FAILED);
        HostedNetworkPendingStore.Pending saved = new HostedNetworkPendingStore(config).current("true:account-a");
        assertEquals(next.requestId(), saved.command().requestId());
        assertEquals(next.networkId(), saved.command().networkId());
        assertNull(saved.terminal());
    }

    @Test
    void replaysFrozenAttachmentAndPreservesReplacementWhenOldOutcomeArrives(@TempDir Path directory) {
        RemotelyConfigStore config = new RemotelyConfigManager(directory);
        HostedNetworkPendingStore store = new HostedNetworkPendingStore(config);
        NetworkCommand.Member member = new NetworkCommand.Member(new NetworkMemberSource.Draft(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "", UUID.randomUUID().toString(), Long.MAX_VALUE,
                new NetworkMemberSource.DraftMetadata("Backend", "minecraft:java", "paper", Map.of(), Map.of()),
                new NetworkMemberSource.Compute(4096, 100), new NetworkMemberSource.Compute(4096, 100),
                new NetworkMemberSource.Storage(8192, 0)), "backend", NetworkMemberRole.GAMEPLAY, 25566, 0, false);
        NetworkCommand.Attach command = new NetworkCommand.Attach(1, UUID.randomUUID().toString(), "network", Long.MAX_VALUE, member);
        HostedNetworkPendingStore.PendingAttach admitted = store.admitAttachment("true:account-a", command);
        NetworkCommand.Attach changed = new NetworkCommand.Attach(1, command.requestId(), command.networkId(), 1, member);
        assertThrows(IllegalStateException.class, () -> store.admitAttachment("true:account-a", changed));
        assertNull(store.attachment("true:account-b", command.networkId()));
        assertNull(store.attachment("true:account-a", "other-network"));

        HostedNetworkPendingStore reopened = new HostedNetworkPendingStore(config);
        HostedNetworkPendingStore.PendingAttach pending = reopened.attachment("true:account-a", command.networkId());
        assertEquals(command, pending.command());
        assertEquals(admitted.body(), pending.body());
        String[] submitted = {""};
        HostedNetworkClient client = new HostedNetworkClient((method, path, body) -> {
            submitted[0] = body;
            return Async.completed(status(command));
        });
        assertThrows(IllegalArgumentException.class, () -> client.execute(changed, pending.body(), TaskScheduler.direct(), () -> true));
        assertEquals("", submitted[0]);
        client.execute(pending.command(), pending.body(), TaskScheduler.direct(), () -> true).join();
        assertEquals(admitted.body(), submitted[0]);
        reopened.markAttachmentTerminal("true:account-a", command.requestId(), command.networkId(), NetworkOperationState.NEEDS_REVIEW);
        assertEquals(NetworkOperationState.NEEDS_REVIEW,
                new HostedNetworkPendingStore(config).attachment("true:account-a", command.networkId()).terminal());
        reopened.acknowledgeAttachment("true:account-a", command.requestId(), command.networkId());

        NetworkCommand.Attach next = new NetworkCommand.Attach(1, UUID.randomUUID().toString(), command.networkId(), 1, member);
        reopened.admitAttachment("true:account-a", next);
        reopened.clearAttachment("true:account-a", command.requestId(), command.networkId());
        reopened.markAttachmentTerminal("true:account-a", command.requestId(), command.networkId(), NetworkOperationState.FAILED);
        HostedNetworkPendingStore.PendingAttach saved = new HostedNetworkPendingStore(config).attachment("true:account-a", command.networkId());
        assertEquals(next, saved.command());
        assertNull(saved.terminal());
    }

    private static NetworkCommand.Create command() {
        String request = UUID.randomUUID().toString();
        return new NetworkCommand.Create(1, request, "network-" + request, "Network", 25565,
                List.of(new NetworkCommand.Member(new NetworkMemberSource.Draft(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        "", UUID.randomUUID().toString(), Long.MAX_VALUE,
                        new NetworkMemberSource.DraftMetadata("Proxy", "minecraft:java", "velocity", Map.of(), Map.of()),
                        new NetworkMemberSource.Compute(4096, 100), new NetworkMemberSource.Compute(4096, 100),
                        new NetworkMemberSource.Storage(8192, 0)), "proxy", NetworkMemberRole.PROXY, 25565, 0, false)),
                Map.of("firewallVerified", true));
    }

    private static String status(NetworkCommand command) {
        return """
                {"schemaVersion":1,"requestId":"%s","networkId":"%s","command":"%s",
                 "state":"SUCCEEDED","stage":"COMPLETE","operationId":"operation-1",
                 "networkRevision":"1","message":"Created","createdAt":"1","updatedAt":"2","members":[]}
                """.formatted(command.requestId(), command.networkId(), command.type().name());
    }

    private static final class MemoryConfig implements RemotelyConfigStore {
        private final Map<String, String> values = new HashMap<>();

        @Override public String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
        @Override public void set(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public void save() { }
        @Override public void apply() { }
        @Override public String readPendingNetwork(String key) { return values.get(key); }
        @Override public void writePendingNetwork(String key, String body) { values.put(key, body); }
        @Override public void removePendingNetwork(String key) { values.remove(key); }
        @Override public List<RemotelyGroup> getInstanceGroups(String context) { return List.of(); }
        @Override public void setInstanceGroups(String context, List<RemotelyGroup> groups) { }
    }
}
