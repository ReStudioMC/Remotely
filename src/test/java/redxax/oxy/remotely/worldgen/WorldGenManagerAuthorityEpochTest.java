package redxax.oxy.remotely.worldgen;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncLiveServerSession;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenStage;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenManagerAuthorityEpochTest {
    @Test
    void capturedSaveAdmissionCannotCrossAuthorityReset() throws Exception {
        WorldGenManager manager = WorldGenManager.getInstance();
        String serverId = "worldgen-save-admission-" + UUID.randomUUID();
        FlowManager flowManager = new FlowManager(null, null);
        try {
            ReSyncFlowClient client = flowManager.activateLiveReSyncSession(
                new ReSyncLiveServerSession(serverId, "WorldGen", new OpenTransport()));
            publishCurrentSession(client);
            assertTrue(client.resourceRevisionReconciler().observeAuthorityEpoch(serverId, 7L).accepted());
            assertTrue(manager.acceptAuthorityEpoch(serverId, 7L).accepted());
            WorldGenProject project = manager.createProjectTemplate("Hybrid", "Continental", "admission");
            manager.handleProjectData(serverId, project, 1L, 7L);
            WorldGenManager.ProjectSaveAdmission admission = manager.captureProjectSaveAdmission(serverId, project.getId());
            assertTrue(admission != null);

            flowManager.closeServerConnection(serverId);

            assertFalse(manager.saveFrozenWorkspaceProject(admission, new FlowGraph(), WorldGenStage.TERRAIN, false));
            assertTrue(manager.pendingWorldGenMutations(serverId).isEmpty());
        } finally {
            manager.clearCache(serverId);
            flowManager.shutdown();
        }
    }

    @Test
    void uncoordinatedAuthorityEpochCannotAdvanceTheStore() {
        WorldGenManager manager = WorldGenManager.getInstance();
        String serverId = "worldgen-uncoordinated-" + UUID.randomUUID();
        manager.clearCache(serverId);

        WorldGenManager.EpochDecision rejected = manager.acceptAuthorityEpoch(serverId, 7L);

        assertFalse(rejected.accepted());
        assertEquals(0L, manager.authorityEpoch(serverId));
        manager.clearCache(serverId);
    }

    @Test
    void currentReSyncSessionCanReestablishClearedAuthorityEpoch() throws Exception {
        WorldGenManager manager = WorldGenManager.getInstance();
        String serverId = "worldgen-reconnect-" + UUID.randomUUID();
        FlowManager flowManager = new FlowManager(null, null);
        try {
            ReSyncFlowClient client = flowManager.activateLiveReSyncSession(
                new ReSyncLiveServerSession(serverId, "WorldGen", new OpenTransport()));
            assertTrue(client != null);
            publishCurrentSession(client);
            assertTrue(client.resourceRevisionReconciler().observeAuthorityEpoch(serverId, 7L).accepted());
            assertTrue(manager.acceptAuthorityEpoch(serverId, 7L).accepted());
            manager.clearCache(serverId);

            WorldGenManager.EpochDecision repaired = manager.acceptAuthorityEpoch(serverId, 7L);

            assertTrue(repaired.accepted());
            assertEquals(7L, manager.authorityEpoch(serverId));
        } finally {
            manager.clearCache(serverId);
            flowManager.shutdown();
        }
    }

    @Test
    void rejectedAuthorityCommitRestoresThePreviousEpoch() {
        WorldGenManager manager = WorldGenManager.getInstance();
        String serverId = "worldgen-epoch-" + UUID.randomUUID();
        manager.clearCache(serverId);
        AtomicInteger rollbacks = new AtomicInteger();
        AtomicLong externalEpoch = new AtomicLong();
        WorldGenManager.EpochDecision rejected = manager.acceptAuthorityEpoch(serverId, 7L,
            new WorldGenManager.AuthorityEpochTransition() {
                @Override
                public boolean prepare(long nextEpoch) {
                    return true;
                }

                @Override
                public boolean commit(long nextEpoch) {
                    externalEpoch.set(nextEpoch);
                    return false;
                }

                @Override
                public void rollback(long previousEpoch, long nextEpoch) {
                    rollbacks.incrementAndGet();
                    externalEpoch.set(previousEpoch);
                }
            });
        assertFalse(rejected.accepted());
        assertEquals(0L, manager.authorityEpoch(serverId));
        assertEquals(1, rollbacks.get());
        assertEquals(0L, externalEpoch.get());

        WorldGenManager.EpochDecision accepted = manager.acceptAuthorityEpoch(serverId, 7L,
            new WorldGenManager.AuthorityEpochTransition() {
                @Override
                public boolean prepare(long nextEpoch) {
                    return true;
                }

                @Override
                public boolean commit(long nextEpoch) {
                    return true;
                }

                @Override
                public void rollback(long previousEpoch, long nextEpoch) {
                }
            });
        assertTrue(accepted.accepted());
        assertEquals(7L, manager.authorityEpoch(serverId));
        manager.clearCache(serverId);
    }

    private static void establishEpoch(WorldGenManager manager, String serverId) {
        WorldGenManager.EpochDecision accepted = manager.acceptAuthorityEpoch(serverId, 7L,
            new WorldGenManager.AuthorityEpochTransition() {
                @Override
                public boolean prepare(long nextEpoch) {
                    return true;
                }

                @Override
                public boolean commit(long nextEpoch) {
                    return true;
                }

                @Override
                public void rollback(long previousEpoch, long nextEpoch) {
                }
            });
        assertTrue(accepted.accepted());
    }

    private static void publishCurrentSession(ReSyncFlowClient client) throws Exception {
        int generation = client.handshakeObservation().activeGeneration();
        assertTrue(generation > 0);
        Field field = ReSyncFlowClient.class.getDeclaredField("authenticated");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(client)).set(true);
        Field startup = ReSyncFlowClient.class.getDeclaredField("completedStartupGeneration");
        startup.setAccessible(true);
        startup.setInt(client, generation);
        assertTrue(client.isConnectedState());
    }

    private static final class OpenTransport implements ReSyncFrameTransport {
        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
