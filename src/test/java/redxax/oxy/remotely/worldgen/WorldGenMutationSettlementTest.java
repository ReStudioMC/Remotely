package redxax.oxy.remotely.worldgen;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncLiveServerSession;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenMutationSettlementTest {
    @Test
    void metadataReconciliationWaitsForAuthoritativeProjectMetadata() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/worldgen/WorldGenManager.java")).replace("\r\n", "\n");
        int authority = source.indexOf("if (!hasCurrentProjectMetadataAuthority(reconciliation, manager))");
        int exactState = source.indexOf("if (metadataStateMatches(manager, reconciliation))", authority);
        int ticket = source.indexOf("DesignerSaveNotifications.startExact", authority);

        assertTrue(authority >= 0);
        assertTrue(source.indexOf("client.requestProjectMetadata(reconciliation.serverId)", authority) > authority);
        assertTrue(exactState > authority);
        assertTrue(ticket > exactState);
        assertTrue(source.contains("manager.getAuthoritativeProjectResource(reconciliation.serverId"));
        assertTrue(source.contains("reconciliation.requiredMetadataAuthorityGeneration = "
            + "nextProjectMetadataAuthorityGeneration(manager,"));
    }

    @Test
    void metadataReconciliationRebindsLeasesWhenConnectionTokenChanges() throws Exception {
        WorldGenManager manager = WorldGenManager.getInstance();
        String serverId = "worldgen-metadata-rebind-" + UUID.randomUUID();
        RecordingTransport firstTransport = new RecordingTransport();
        RecordingTransport secondTransport = new RecordingTransport();
        FlowManager flowManager = new FlowManager(null, null);
        try {
            ReSyncFlowClient firstClient = flowManager.activateLiveReSyncSession(
                new ReSyncLiveServerSession(serverId, "WorldGen", firstTransport));
            publishCurrentSession(firstClient);
            FlowManager.ServerConnectionToken firstToken = flowManager.captureServerConnectionToken(serverId);
            assertTrue(firstToken.generation() > 0L);
            assertTrue(firstClient.resourceRevisionReconciler().observeAuthorityEpoch(serverId, 1L).accepted());
            assertTrue(manager.acceptAuthorityEpoch(serverId, 1L).accepted());

            WorldGenProject project = manager.createProjectTemplate("Hybrid", "Continental", "metadata-rebind");
            manager.handleProjectData(serverId, project, 1L, 1L);
            manager.handleProjectList(serverId, List.of(project.getId()), 1L, 1L);
            assertTrue(manager.snapshotProject(serverId, project.getId()) != null);
            assertTrue(manager.snapshotProjectList(serverId) != null);

            flowManager.cacheProjectMetadata(serverId, new ReSyncProjectMetadata(serverId));
            long oldMetadataGeneration = flowManager.projectMetadataAuthorityGeneration(serverId);
            assertTrue(oldMetadataGeneration > 0L);
            String canonicalHash = manager.snapshotProject(serverId, project.getId()).canonicalHash();
            Object reconciliation = newReconciliation(serverId, project.getId(), firstToken, canonicalHash);
            Class<?> reconciliationType = reconciliation.getClass();
            Field requiredRevision = reconciliationType.getDeclaredField("requiredRevision");
            Field requiredGeneration = reconciliationType.getDeclaredField("requiredMetadataAuthorityGeneration");
            requiredRevision.setAccessible(true);
            requiredGeneration.setAccessible(true);
            requiredRevision.setLong(reconciliation, 1L);
            requiredGeneration.setLong(reconciliation, oldMetadataGeneration);

            CountDownLatch disconnected = new CountDownLatch(1);
            firstTransport.disconnectObserver = disconnected::countDown;
            firstTransport.disconnect();
            assertTrue(disconnected.await(2L, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2L), () -> {
                while (flowManager.isCurrentServerConnection(firstToken)) {
                    Thread.onSpinWait();
                }
            });

            flowManager.activateLiveReSyncSession(new ReSyncLiveServerSession(serverId, "WorldGen", secondTransport));
            ReSyncFlowClient secondClient = awaitCurrentClient(flowManager, serverId, firstClient);
            publishCurrentSession(secondClient);
            FlowManager.ServerConnectionToken secondToken = flowManager.captureServerConnectionToken(serverId);
            assertTrue(secondToken.generation() > firstToken.generation());
            assertNotEquals(firstToken, secondToken);
            assertEquals(1L, manager.authorityEpoch(serverId));

            Method rebind = WorldGenManager.class.getDeclaredMethod("rebindMetadataAuthority", reconciliationType,
                FlowManager.class);
            rebind.setAccessible(true);
            assertTrue((Boolean) rebind.invoke(manager, reconciliation, flowManager));

            Field connection = reconciliationType.getDeclaredField("connection");
            connection.setAccessible(true);
            assertEquals(secondToken, connection.get(reconciliation));
            assertEquals(0L, requiredRevision.getLong(reconciliation));
            assertEquals(oldMetadataGeneration + 1L, requiredGeneration.getLong(reconciliation));
            assertTrue(manager.snapshotProject(serverId, project.getId()) == null);
            assertTrue(manager.snapshotProjectList(serverId) == null);
        } finally {
            manager.clearCache(serverId);
            flowManager.shutdown();
        }
    }

    @Test
    void unavailableAuthoritySettlesSaveDeleteAndPreview() {
        WorldGenManager manager = WorldGenManager.getInstance();
        String serverId = "worldgen-unavailable-" + UUID.randomUUID();
        FlowManager flowManager = new FlowManager(null, null);
        try {
            flowManager.activateLiveReSyncSession(new ReSyncLiveServerSession(serverId, "WorldGen", new OpenTransport()));
            WorldGenProject project = manager.createProjectTemplate("Hybrid", "Continental", "unavailable-" + UUID.randomUUID());

            assertFalse(manager.canMutateWorldGen(serverId));
            manager.saveWorldGen(serverId, project, false);
            assertTrue(manager.pendingWorldGenMutations(serverId).isEmpty());
            assertNull(manager.getCachedProject(serverId, project.getId()));

            assertNull(manager.deleteProject(serverId, project.getId()));
            assertTrue(manager.pendingWorldGenMutations(serverId).isEmpty());

            String previewId = "preview-" + UUID.randomUUID();
            manager.requestPreview(serverId, previewId, project, "NORMAL", 1L, "");
            assertEquals("stopped", manager.getPreviewState(serverId, previewId));
            manager.clearCache(serverId);
            assertEquals("stopped", manager.getPreviewState(serverId, previewId));
        } finally {
            manager.clearCache(serverId);
            flowManager.shutdown();
        }
    }

    @Test
    void shutdownRejectsAndSettlesWorldGenMutations() {
        WorldGenManager manager = WorldGenManager.getInstance();
        String serverId = "worldgen-shutdown-" + UUID.randomUUID();
        FlowManager flowManager = new FlowManager(null, null);
        try {
            ReSyncFlowClient client = flowManager.activateLiveReSyncSession(
                new ReSyncLiveServerSession(serverId, "WorldGen", new OpenTransport()));
            WorldGenProject project = manager.createProjectTemplate("Hybrid", "Continental", "shutdown-" + UUID.randomUUID());
            client.shutdown();

            assertThrows(IllegalStateException.class,
                () -> client.sendWorldGenPreviewApply(null, project, "preview-direct", "NORMAL", 1L, ""));

            assertFalse(manager.canMutateWorldGen(serverId));
            manager.saveWorldGen(serverId, project, false);
            assertTrue(manager.pendingWorldGenMutations(serverId).isEmpty());
            assertNull(manager.getCachedProject(serverId, project.getId()));

            assertNull(manager.deleteProject(serverId, project.getId()));
            assertTrue(manager.pendingWorldGenMutations(serverId).isEmpty());

            String previewId = "preview-" + UUID.randomUUID();
            manager.requestPreview(serverId, previewId, project, "NORMAL", 1L, "");
            assertEquals("stopped", manager.getPreviewState(serverId, previewId));
            manager.shutdown(serverId);
            assertEquals("stopped", manager.getPreviewState(serverId, previewId));
        } finally {
            manager.clearCache(serverId);
            flowManager.shutdown();
        }
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

    private static Object newReconciliation(String serverId, String projectId,
                                             FlowManager.ServerConnectionToken connection, String canonicalHash)
        throws Exception {
        Class<?> type = Class.forName(
            "redxax.oxy.remotely.worldgen.WorldGenManager$PendingWorldGenMetadataReconciliation");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, String.class, boolean.class,
            WorldGenManager.WorldGenMetadataIntent.class, long.class, String.class, FlowManager.ServerConnectionToken.class,
            long.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance("metadata-rebind-operation", serverId, projectId, true,
            new WorldGenManager.WorldGenMetadataIntent(projectId, "WorldGen", -1), 1L, canonicalHash, connection, 1L,
            null);
    }

    private static ReSyncFlowClient awaitCurrentClient(FlowManager flowManager, String serverId,
                                                       ReSyncFlowClient previous) {
        AtomicBoolean done = new AtomicBoolean();
        ReSyncFlowClient[] current = new ReSyncFlowClient[1];
        assertTimeoutPreemptively(Duration.ofSeconds(2L), () -> {
            while (!done.get()) {
                ReSyncFlowClient candidate = flowManager.existingFlowClient(serverId);
                if (candidate != null && candidate != previous) {
                    current[0] = candidate;
                    done.set(true);
                } else {
                    Thread.onSpinWait();
                }
            }
        });
        return current[0];
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

    private static final class RecordingTransport implements ReSyncFrameTransport {
        private final AtomicInteger sentFrames = new AtomicInteger();
        private volatile Runnable closeHandler = () -> {
        };
        private volatile Runnable disconnectObserver = () -> {
        };
        private volatile boolean open = true;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler == null ? () -> {
            } : handler;
        }

        @Override
        public void send(byte[] frame) {
            sentFrames.incrementAndGet();
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        private void disconnect() {
            open = false;
            closeHandler.run();
            disconnectObserver.run();
        }
    }
}
