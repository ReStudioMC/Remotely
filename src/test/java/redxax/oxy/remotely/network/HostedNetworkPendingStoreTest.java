package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyGroup;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.network.protocol.NetworkOperationState;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

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

    private static String status(NetworkCommand.Create command) {
        return """
                {"schemaVersion":1,"requestId":"%s","networkId":"%s","command":"CREATE",
                 "state":"SUCCEEDED","stage":"COMPLETE","operationId":"operation-1",
                 "networkRevision":"1","message":"Created","createdAt":"1","updatedAt":"2","members":[]}
                """.formatted(command.requestId(), command.networkId());
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
