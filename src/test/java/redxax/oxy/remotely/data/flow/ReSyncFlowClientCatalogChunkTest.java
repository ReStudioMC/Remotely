package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationChunkPacket;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientCatalogChunkTest {
    @Test
    void catalogAdmissionCannotInterleaveWorkspaceConnectAndStartupCommit(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("8ddddddd-dddd-4ddd-8ddd-dddddddddddd"), 64);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        client.pauseStartup = true;
        CountDownLatch heartbeatEntered = new CountDownLatch(1);
        CountDownLatch heartbeatRelease = new CountDownLatch(1);
        ScheduledThreadPoolExecutor heartbeat = (ScheduledThreadPoolExecutor)
            field(ReSyncFlowClient.class, "heartbeatScheduler").get(client);
        try {
            heartbeat.execute(() -> {
                heartbeatEntered.countDown();
                await(heartbeatRelease);
            });
            assertTrue(heartbeatEntered.await(2, TimeUnit.SECONDS));
            authenticate(client, transport, publication.key());
            assertTrue(client.workspaceConnected.await(2, TimeUnit.SECONDS));
            assertTrue(client.requestCatalogPublication(true));
            heartbeatRelease.countDown();
            heartbeat.submit(() -> {}).get(2, TimeUnit.SECONDS);
            heartbeat.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(0, transport.catalogRequests().size());
            Object acquisition = field(ReSyncFlowClient.class, "catalogAcquisition").get(client);
            assertFalse(field(acquisition.getClass(), "sendQueued").getBoolean(acquisition));
            client.workspaceRelease.countDown();
            awaitRequests(transport, 1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!client.isConnectedState() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(client.isConnectedState(), () -> client.handshakeObservation().toString());
            assertEquals(1, client.workspaceConnects.get());
        } finally {
            heartbeatRelease.countDown();
            client.release();
            client.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void retiredChunkProgressCannotCancelTheTimeoutRetry(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("8ccccccc-cccc-4ccc-8ccc-cccccccccccc"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        boolean reserved = false;
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            transport.receiveFlow(chunks(publication).getFirst());
            client.catalogPublicationWorkerIdentity().join();
            Object admission = field(ReSyncFlowClient.class, "catalogChunkAdmission").get(client);
            AtomicLong epoch = (AtomicLong) field(ReSyncFlowClient.class, "catalogPublicationWorkEpoch").get(client);
            long retiredEpoch = epoch.get();
            reserved = client.reserveCatalogPublicationWork(1);
            assertTrue(reserved);
            client.advance(31);
            client.checkCatalogAcquisitionDeadline();
            Method progress = ReSyncFlowClient.class.getDeclaredMethod("observeCatalogProgress",
                admission.getClass(), String.class, long.class);
            progress.setAccessible(true);
            progress.invoke(client, admission, "receiving", retiredEpoch);
            Object acquisition = field(ReSyncFlowClient.class, "catalogAcquisition").get(client);
            assertTrue(field(acquisition.getClass(), "pending").getBoolean(acquisition));
            assertEquals(1, transport.catalogRequests().size());
            client.releaseCatalogPublicationWork(1);
            reserved = false;
            awaitRequests(transport, 2);
        } finally {
            if (reserved) client.releaseCatalogPublicationWork(1);
            client.release();
            client.shutdown();
        }
    }

    @Test
    void retainedOutboundBacklogDoesNotConsumeCatalogAttempts(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), 64);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        client.firstRelease.countDown();
        Field draining = field(ReSyncFlowClient.class, "drainingPendingSends");
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            chunks(publication).forEach(transport::receiveFlow);
            assertPublicationEventually(client, publication);
            draining.setBoolean(client, true);
            Class<?> pendingSend = Class.forName(ReSyncFlowClient.class.getName() + "$PendingSend");
            Object retained = Proxy.newProxyInstance(pendingSend.getClassLoader(), new Class<?>[]{pendingSend},
                (proxy, method, arguments) -> true);
            Method enqueue = ReSyncFlowClient.class.getDeclaredMethod("enqueuePendingSend", String.class,
                long.class, pendingSend);
            enqueue.setAccessible(true);
            assertTrue((boolean) enqueue.invoke(client, null, 128L, retained));
            assertTrue(client.requestCatalogPublication(true));
            invoke(client, "drainCatalogAcquisition");
            Object acquisition = field(ReSyncFlowClient.class, "catalogAcquisition").get(client);
            for (int index = 0; index < 10; index++) {
                assertTrue(client.requestCatalogPublication());
                invoke(client, "drainCatalogAcquisition");
            }
            assertEquals(0, field(acquisition.getClass(), "attempts").getInt(acquisition));
            assertTrue(field(acquisition.getClass(), "sendQueued").getBoolean(acquisition));
            assertEquals(1, transport.catalogRequests().size());
            draining.setBoolean(client, false);
            invoke(client, "flushPendingSends");
            awaitRequests(transport, 2);
            assertEquals(1, field(acquisition.getClass(), "attempts").getInt(acquisition));
        } finally {
            draining.setBoolean(client, false);
            client.release();
            client.shutdown();
        }
    }

    @Test
    void lateAndMalformedChunksCannotReviveAnExhaustedAcquisition(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("8bbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        client.firstRelease.countDown();
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            for (int attempt = 2; attempt <= 3; attempt++) {
                client.advance(31);
                client.checkCatalogAcquisitionDeadline();
                awaitRequests(transport, attempt);
            }
            client.advance(31);
            client.checkCatalogAcquisitionDeadline();
            transport.receiveFlow(chunks(publication).getLast());
            client.catalogPublicationWorkerIdentity().join();
            transport.receiveFlow(new byte[]{ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CHUNK, 0});
            client.catalogPublicationWorkerIdentity().join();
            assertFalse(client.requestCatalogPublication());
            assertEquals(3, transport.catalogRequests().size());
            assertTrue(client.catalogAuthorityDiagnostic().orElseThrow().contains("RETRY_EXHAUSTED"));
            CatalogCachePublication next = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
                new CatalogCacheKey(publication.serverId(), 32, publication.key().snapshotChecksum(),
                    publication.key().bindingManifestHash(), CatalogProjectionVersion.current()), 2, publication.entries());
            chunks(next).forEach(transport::receiveFlow);
            assertPublicationEventually(client, next);
            assertEquals(3, transport.catalogRequests().size());
        } finally {
            client.release();
            client.shutdown();
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void invoke(ReSyncFlowClient client, String name) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(client);
    }

    @Test
    void recoveryDemandDoesNotInvalidateThePublicationAlreadyBeingDecoded(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("81111111-1111-4111-8111-111111111111"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            chunks(publication).forEach(transport::receiveFlow);
            assertTrue(client.firstDecoded.await(2, TimeUnit.SECONDS));
            transport.catalogRequestHandler = ignored -> chunks(publication).forEach(transport::receiveFlow);
            for (int index = 0; index < 10; index++) assertTrue(client.requestCatalogPublication());
            assertEquals(1, transport.catalogRequests().size());
            client.firstRelease.countDown();
            assertPublicationEventually(client, publication);
            assertEquals(1, client.decodes.get());
            assertEquals(1, transport.catalogRequests().size());
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void explicitRefreshWaitsForTheCurrentPublicationAndIsDispatchedOnce(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("82222222-2222-4222-8222-222222222222"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            chunks(publication).forEach(transport::receiveFlow);
            assertTrue(client.firstDecoded.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 5; index++) assertTrue(client.requestCatalogPublication(true));
            assertEquals(1, transport.catalogRequests().size());
            client.firstRelease.countDown();
            assertPublicationEventually(client, publication);
            awaitRequests(transport, 2);
            assertEquals(1, transport.catalogRequests().getLast().length);
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void lostResponsesRetryThreeTimesAndEnsureCannotRestartAnExhaustedAcquisition(@TempDir Path directory) {
        CatalogCachePublication publication = publication(server("83333333-3333-4333-8333-333333333333"), 64);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            for (int attempt = 2; attempt <= 3; attempt++) {
                client.advance(31);
                client.checkCatalogAcquisitionDeadline();
                awaitRequests(transport, attempt);
            }
            client.advance(31);
            client.checkCatalogAcquisitionDeadline();
            for (int index = 0; index < 10; index++) assertFalse(client.requestCatalogPublication());
            assertEquals(3, transport.catalogRequests().size());
            assertTrue(client.catalogAuthorityDiagnostic().orElseThrow().contains("RETRY_EXHAUSTED"));
            assertTrue(client.requestCatalogPublication(true));
            awaitRequests(transport, 4);
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void blockedDecoderHasAFiniteDeadlineWithoutAnotherOverlappingRequest(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("89999999-9999-4999-8999-999999999999"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            chunks(publication).forEach(transport::receiveFlow);
            assertTrue(client.firstDecoded.await(2, TimeUnit.SECONDS));
            client.advance(61);
            client.checkCatalogAcquisitionDeadline();
            client.checkCatalogAcquisitionDeadline();
            assertTrue(client.catalogAuthorityDiagnostic().orElseThrow().contains("WORKER_STALLED"));
            assertFalse(client.requestCatalogPublication());
            assertEquals(1, transport.catalogRequests().size());
            client.firstRelease.countDown();
            client.catalogPublicationWorkerIdentity().join();
            assertTrue(client.catalogPublicationProjection().active().isEmpty());
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void validatedChunkProgressExtendsTheIdleDeadlineButEnsureDoesNot(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("84444444-4444-4444-8444-444444444444"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            client.advance(25);
            transport.receiveFlow(chunks(publication).getFirst());
            client.catalogPublicationWorkerIdentity().join();
            client.advance(10);
            assertTrue(client.requestCatalogPublication());
            client.checkCatalogAcquisitionDeadline();
            assertEquals(1, transport.catalogRequests().size());
            client.advance(21);
            client.checkCatalogAcquisitionDeadline();
            awaitRequests(transport, 2);
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void fullDemandUpgradesAnOutstandingKeyedRequestAfterItsResponse(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("85555555-5555-4555-8555-555555555555"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        client.firstRelease.countDown();
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            chunks(publication).forEach(transport::receiveFlow);
            assertPublicationEventually(client, publication);
            assertTrue(client.requestCatalogPublication());
            awaitRequests(transport, 2);
            assertTrue(transport.catalogRequests().getLast().length > 1);
            assertTrue(client.ensureCatalogPublication(true));
            assertEquals(2, transport.catalogRequests().size());
            chunks(publication).forEach(transport::receiveFlow);
            awaitRequests(transport, 3);
            assertEquals(1, transport.catalogRequests().getLast().length);
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void newerPublicationStillRejectsTheOlderBlockedCandidate(@TempDir Path directory) throws Exception {
        CatalogCachePublication first = publication(server("86666666-6666-4666-8666-666666666666"), 300_000);
        CatalogCachePublication next = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            new CatalogCacheKey(first.serverId(), 32, first.key().snapshotChecksum(), first.key().bindingManifestHash(),
                CatalogProjectionVersion.current()), 2, first.entries());
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(first, transport, directory);
        client.blockSecond = true;
        try {
            authenticate(client, transport, first.key());
            awaitRequests(transport, 1);
            chunks(first).forEach(transport::receiveFlow);
            assertTrue(client.firstDecoded.await(2, TimeUnit.SECONDS));
            chunks(next).forEach(transport::receiveFlow);
            client.firstRelease.countDown();
            assertTrue(client.secondDecoded.await(2, TimeUnit.SECONDS));
            assertTrue(client.catalogPublicationProjection().active().isEmpty());
            client.secondRelease.countDown();
            assertPublicationEventually(client, next);
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void changedAuthorityRejectsAFormerEpochPreparation(@TempDir Path directory) throws Exception {
        CatalogCachePublication publication = publication(server("87777777-7777-4777-8777-777777777777"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            chunks(publication).forEach(transport::receiveFlow);
            assertTrue(client.firstDecoded.await(2, TimeUnit.SECONDS));
            Field epoch = ReSyncFlowClient.class.getDeclaredField("handshakeAuthorityEpoch");
            epoch.setAccessible(true);
            epoch.setLong(client, 2L);
            client.firstRelease.countDown();
            client.catalogPublicationWorkerIdentity().join();
            assertTrue(client.catalogPublicationProjection().active().isEmpty());
            assertTrue(client.ensureCatalogPublication(true));
            awaitRequests(transport, 2);
            chunks(publication).forEach(transport::receiveFlow);
            assertPublicationEventually(client, publication);
        } finally {
            client.release();
            client.shutdown();
        }
    }

    @Test
    void decoderFailureUsesTheSameFiniteRetryBudget(@TempDir Path directory) {
        CatalogCachePublication publication = publication(server("88888888-8888-4888-8888-888888888888"), 300_000);
        CapturingTransport transport = new CapturingTransport();
        ControlledClient client = new ControlledClient(publication, transport, directory);
        client.failDecode = true;
        try {
            authenticate(client, transport, publication.key());
            awaitRequests(transport, 1);
            transport.catalogRequestHandler = ignored -> chunks(publication).forEach(transport::receiveFlow);
            chunks(publication).forEach(transport::receiveFlow);
            awaitRequests(transport, 3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!client.catalogAuthorityDiagnostic().orElse("").contains("RETRY_EXHAUSTED")
                && System.nanoTime() < deadline) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            assertTrue(client.catalogAuthorityDiagnostic().orElseThrow().contains("RETRY_EXHAUSTED"));
            assertFalse(client.requestCatalogPublication());
            assertEquals(3, transport.catalogRequests().size());
        } finally {
            client.release();
            client.shutdown();
        }
    }

    private static void awaitRequests(CapturingTransport transport, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (transport.catalogRequests().size() < count && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(count, transport.catalogRequests().size());
    }

    private static final class ControlledClient extends ReSyncFlowClient {
        private final AtomicLong now = new AtomicLong(1_000L);
        private final AtomicInteger decodes = new AtomicInteger();
        private final CountDownLatch firstDecoded = new CountDownLatch(1);
        private final CountDownLatch firstRelease = new CountDownLatch(1);
        private final CountDownLatch secondDecoded = new CountDownLatch(1);
        private final CountDownLatch secondRelease = new CountDownLatch(1);
        private final CountDownLatch workspaceConnected = new CountDownLatch(1);
        private final CountDownLatch workspaceRelease = new CountDownLatch(1);
        private final AtomicInteger workspaceConnects = new AtomicInteger();
        private volatile boolean blockSecond;
        private volatile boolean failDecode;
        private volatile boolean pauseStartup;

        private ControlledClient(CatalogCachePublication publication, CapturingTransport transport, Path directory) {
            super(publication.serverId().canonicalText(), transport, null,
                new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(directory.resolve("acquisition.json"))));
        }

        @Override
        boolean connectWorkspaces(int generation) {
            boolean connected = super.connectWorkspaces(generation);
            workspaceConnects.incrementAndGet();
            if (connected && pauseStartup) {
                workspaceConnected.countDown();
                await(workspaceRelease);
            }
            return connected;
        }

        @Override
        long catalogAcquisitionNanos() {
            return now.get();
        }

        @Override
        CatalogCachePublication decodeCatalogPublication(byte[] bytes) {
            if (failDecode) throw new IllegalArgumentException("Rejected fixture");
            CatalogCachePublication decoded = super.decodeCatalogPublication(bytes);
            int count = decodes.incrementAndGet();
            try {
                if (count == 1) {
                    firstDecoded.countDown();
                    assertTrue(firstRelease.await(2, TimeUnit.SECONDS));
                } else if (count == 2 && blockSecond) {
                    secondDecoded.countDown();
                    assertTrue(secondRelease.await(2, TimeUnit.SECONDS));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return decoded;
        }

        private void advance(long seconds) {
            now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }

        private void release() {
            workspaceRelease.countDown();
            firstRelease.countDown();
            secondRelease.countDown();
        }
    }

    @Test
    void orderedChunksApplyThroughTheExistingPublicationPath(@TempDir Path temporaryDirectory) {
        ServerId server = server("11111111-1111-4111-8111-111111111111");
        CatalogCachePublication publication = publication(server, 300_000);
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("ordered.json"))));
        try {
            authenticate(client, transport, publication.key());
            for (byte[] chunk : chunks(publication)) {
                transport.receiveFlow(chunk);
            }
            assertPublicationEventually(client, publication);
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void duplicateChunkCanBeRetriedWithoutChangingTheTransfer(@TempDir Path temporaryDirectory) {
        ServerId server = server("22222222-2222-4222-8222-222222222222");
        CatalogCachePublication publication = publication(server, 300_000);
        List<byte[]> chunks = chunks(publication);
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("duplicate.json"))));
        try {
            authenticate(client, transport, publication.key());
            transport.receiveFlow(chunks.getFirst());
            transport.receiveFlow(chunks.getFirst());
            transport.receiveFlow(chunks.get(1));
            assertPublicationEventually(client, publication);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void conflictingChunkIsRejectedBeforeProjection(@TempDir Path temporaryDirectory) {
        ServerId server = server("33333333-3333-4333-8333-333333333333");
        CatalogCachePublication publication = publication(server, 300_000);
        List<byte[]> chunks = chunks(publication);
        CatalogPublicationChunkPacket.Chunk first = CatalogPublicationChunkPacket.decode(chunks.getFirst());
        byte[] conflictingPayload = first.payload();
        conflictingPayload[0] ^= 1;
        byte[] conflicting = CatalogPublicationChunkPacket.encode(new CatalogPublicationChunkPacket.Chunk(
            first.totalLength(), first.chunkIndex(), first.chunkCount(), first.digest(), conflictingPayload));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("conflict.json"))));
        try {
            authenticate(client, transport, publication.key());
            transport.receiveFlow(chunks.getFirst());
            transport.receiveFlow(conflicting);
            assertTrue(client.catalogPublicationProjection().active().isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void disconnectClearsPartialChunksAndPreventsOldGenerationCompletion(@TempDir Path temporaryDirectory) {
        ServerId server = server("44444444-4444-4444-8444-444444444444");
        CatalogCachePublication publication = publication(server, 300_000);
        List<byte[]> chunks = chunks(publication);
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("generation.json"))));
        try {
            authenticate(client, transport, publication.key());
            transport.receiveFlow(chunks.getFirst());
            transport.close();
            transport.reopen();
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.receiveFlow(chunks.get(1));
            assertTrue(client.catalogPublicationProjection().active().isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void singleFramePublicationRemainsCompatible(@TempDir Path temporaryDirectory) {
        ServerId server = server("55555555-5555-4555-8555-555555555555");
        CatalogCachePublication publication = publication(server, 64);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("single.json"))));
        try {
            authenticate(client, transport, publication.key());
            ByteBuffer payload = ByteBuffer.allocate(1 + codec.encodeBytes(publication).length);
            payload.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
            payload.put(codec.encodeBytes(publication));
            transport.receiveFlow(payload.array());
            assertPublicationEventually(client, publication);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void catalogWorkUsesADedicatedBoundedWorker(@TempDir Path temporaryDirectory) throws Exception {
        ServerId server = server("66666666-6666-4666-8666-666666666666");
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), new CapturingTransport(), null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("worker.json"))));
        int publicationBytes = CatalogPublicationChunkPacket.MAX_PUBLICATION_BYTES;
        try {
            assertTrue(client.catalogPublicationWorkerIdentity().join() != null);
            assertTrue(client.reserveCatalogPublicationWork(publicationBytes));
            assertTrue(client.reserveCatalogPublicationWork(publicationBytes));
            assertFalse(client.reserveCatalogPublicationWork(1));
            assertEquals(2L * publicationBytes, client.pendingCatalogPublicationBytes());
            client.releaseCatalogPublicationWork(publicationBytes);
            client.releaseCatalogPublicationWork(publicationBytes);
            assertEquals(0L, client.pendingCatalogPublicationBytes());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void shutdownReleasesPartialChunkReservations(@TempDir Path temporaryDirectory) {
        ServerId server = server("77777777-7777-4777-8777-777777777777");
        CatalogCachePublication publication = publication(server, 300_000);
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("shutdown.json"))));
        try {
            authenticate(client, transport, publication.key());
            transport.receiveFlow(chunks(publication).getFirst());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (client.pendingCatalogPublicationBytes() == 0L && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(client.pendingCatalogPublicationBytes() > 0L);
        } finally {
            client.shutdown();
        }
        assertEquals(0L, client.pendingCatalogPublicationBytes());
    }

    private static List<byte[]> chunks(CatalogCachePublication publication) {
        return CatalogPublicationChunkPacket.splitEncoded(new CatalogCachePublicationCodec().encodeBytes(publication));
    }

    private static void authenticate(ReSyncFlowClient client, CapturingTransport transport, CatalogCacheKey key) {
        client.connect().join();
        transport.receive(handshakeResponse(capabilities(key)));
    }

    private static void assertPublicationEventually(ReSyncFlowClient client, CatalogCachePublication expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var active = client.catalogPublicationProjection().active();
            if (active.isPresent()
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
                assertEquals(expected, active.orElseThrow().publication());
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        var active = client.catalogPublicationProjection().active();
        assertTrue(active.isPresent(), "Catalog publication was not applied before the timeout");
        assertEquals(expected, active.orElseThrow().publication());
        assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
    }

    private static CatalogCachePublication publication(ServerId server, int valueLength) {
        CatalogCacheKey key = new CatalogCacheKey(server, 31, new ContentHash("a".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        OwnerId owner = new OwnerId("resync.chunk.test");
        ContractRef<NodeId> node = ContractRef.of(owner, new NodeId("chunked"));
        String value = "{\"value\":\"" + "x".repeat(valueLength) + "\"}";
        CatalogCacheOpaque opaque = CatalogCacheOpaque.of(value.getBytes(StandardCharsets.UTF_8));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1,
            List.of(CatalogCachePublication.Entry.present(node, 1, CatalogCacheState.UNAVAILABLE, Set.of(), true, opaque)));
    }

    private static ServerId server(String value) {
        return new ServerId(UUID.fromString(value));
    }

    private static byte[] handshakeResponse(String capabilities) {
        byte[] capabilityBytes = capabilities.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 4 + Integer.BYTES * 3 + capabilityBytes.length);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(capabilityBytes.length);
        payload.put(capabilityBytes);
        return payload.array();
    }

    private static String capabilities(CatalogCacheKey key) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", key.serverId().canonicalText());
        root.addProperty("catalogPublicationKey", key.canonicalText());
        JsonObject contract = new JsonObject();
        contract.addProperty("version", ReSyncProtocolContract.FLOW_CONTRACT.version());
        contract.addProperty("minimumClientVersion", 0);
        JsonArray supported = new JsonArray();
        ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities().forEach(supported::add);
        contract.add("supported", supported);
        JsonArray negotiated = new JsonArray();
        for (String capability : ReSyncProtocolContract.FLOW_CONTRACT.negotiate(
            ReSyncProtocolContract.FLOW_CONTRACT.clientCapabilities())) {
            negotiated.add(capability);
        }
        contract.add("negotiated", negotiated);
        root.add("flowContract", contract);
        return root.toString();
    }

    private static final class CapturingTransport implements ReSyncFrameTransport {
        private final List<ReSyncDecodedFrame> sentFrames = new CopyOnWriteArrayList<>();
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;
        private boolean open = true;
        private volatile Consumer<byte[]> catalogRequestHandler;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            frameHandler = handler;
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
            ReSyncDecodedFrame decoded = codec.decode(frame, null);
            sentFrames.add(decoded);
            Consumer<byte[]> handler = catalogRequestHandler;
            if (handler != null && isCatalogRequest(decoded)) handler.accept(decoded.payload());
        }

        private List<byte[]> catalogRequests() {
            return sentFrames.stream().filter(CapturingTransport::isCatalogRequest)
                .map(ReSyncDecodedFrame::payload).toList();
        }

        private static boolean isCatalogRequest(ReSyncDecodedFrame frame) {
            return frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID && frame.payload().length > 0
                && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST;
        }

        @Override
        public void close() {
            open = false;
            if (closeHandler != null) {
                closeHandler.run();
            }
            awaitSettled();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        private void reopen() {
            open = true;
        }

        private void receive(byte[] payload) {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE, payload,
                (short) 0, 1));
            awaitSettled();
        }

        private void receiveFlow(byte[] payload) {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_DATA, payload,
                ReSyncProtocolContract.CHANNEL_FLOW_ID, 2));
            awaitSettled();
        }

        private void awaitSettled() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            long stableSince = System.nanoTime();
            int observed = sentFrames.size();
            while (System.nanoTime() < deadline) {
                int current = sentFrames.size();
                if (current != observed) {
                    observed = current;
                    stableSince = System.nanoTime();
                } else if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(20)) {
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
    }
}
