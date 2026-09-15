package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.network.protocol.NetworkOperationStage;
import redxax.oxy.remotely.network.protocol.NetworkOperationState;
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import redxax.oxy.remotely.network.protocol.NetworkProtocolCodec;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkProtocolCodecTest {
    private final NetworkProtocolCodec codec = new NetworkProtocolCodec();

    @Test
    void roundTripsMixedCreateSourcesWithoutAnOwnerIdentity() {
        String requestId = UUID.randomUUID().toString();
        String poolId = UUID.randomUUID().toString();
        String draftId = UUID.randomUUID().toString();
        String activationId = UUID.randomUUID().toString();
        NetworkCommand command = new NetworkCommand.Create(1, requestId, "network-1", "Network", 25565, List.of(
                new NetworkCommand.Member(new NetworkMemberSource.ExistingServer("proxy-1"), "proxy", NetworkMemberRole.PROXY, 25565, 0, false),
                new NetworkCommand.Member(new NetworkMemberSource.Draft(poolId, draftId, "", activationId, 4,
                        new NetworkMemberSource.DraftMetadata("Lobby", "minecraft:java", "paper", Map.of("VERSION", "1.21.4"), Map.of()),
                        new NetworkMemberSource.Compute(1024, 100), new NetworkMemberSource.Compute(4096, 200),
                        new NetworkMemberSource.Storage(8192, 1024)), "lobby", NetworkMemberRole.LOBBY, 25566, 100, true),
                new NetworkCommand.Member(new NetworkMemberSource.External("external-1", "Games", "10.0.0.40"),
                        "games", NetworkMemberRole.GAMEPLAY, 25567, 100, false)), Map.of("fallbackRoutes", List.of("lobby", "games")));

        assertEquals(command, codec.decodeCommand(codec.encode(command)));
    }

    @Test
    void roundTripsStatusWithCanonicalLifecycleCheckpoint() {
        NetworkLifecycleStep step = new NetworkLifecycleStep("start-0-node", "server-1", "node-1", "lobby",
                NetworkLifecycleAction.START, NetworkLifecycleStepStatus.RUNNING, 11, 0, "Starting lobby");
        NetworkLifecycleJob lifecycle = new NetworkLifecycleJob(1, "lifecycle-1", "network-1", 7,
                NetworkLifecycleOperation.START, NetworkLifecycleStatus.RUNNING, "Test", 10, 11, 1,
                "Starting network", List.of(step));
        NetworkOperationStatus status = new NetworkOperationStatus(1, UUID.randomUUID().toString(), "network-1", NetworkCommand.Type.LIFECYCLE,
                NetworkOperationState.RUNNING, NetworkOperationStage.LIFECYCLE, "operation-1", 7, "Starting network", 10, 11,
                null, lifecycle, List.of(new NetworkOperationStatus.MemberStatus("node-1", "server-1", "", "RUNNING", "Starting lobby")));

        assertEquals(status, codec.decodeStatus(codec.encode(status)));
    }

    @Test
    void rejectsACommandThatCouldInventDraftIdentityOrRevision() {
        NetworkMemberSource.DraftMetadata metadata = new NetworkMemberSource.DraftMetadata("Lobby", "minecraft:java", "paper",
                Map.of("VERSION", "1.21.4"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> new NetworkMemberSource.Draft(UUID.randomUUID().toString(), "",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, metadata,
                new NetworkMemberSource.Compute(1024, 100), new NetworkMemberSource.Compute(4096, 200),
                new NetworkMemberSource.Storage(8192, 1024)));
        assertThrows(IllegalArgumentException.class, () -> new NetworkCommand.Save(1, UUID.randomUUID().toString(),
                "network-1", 0, Map.of("name", "Network")));
    }
}
