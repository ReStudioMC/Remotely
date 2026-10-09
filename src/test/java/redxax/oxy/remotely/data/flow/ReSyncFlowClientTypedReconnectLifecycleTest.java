package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import restudio.rebase.platform.jvm.JvmTaskScheduler;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.Async;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.ui.core.Screen;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationReceiptPacket;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import redxax.oxy.remotely.flow.sync.NodeRegistryRequest;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientTypedReconnectLifecycleTest {
    private TaskScheduler previousScheduler;
    private JvmTaskScheduler scheduler;
    private Async.Snapshot previousAsync;
    private ExecutorService asyncPool;

    @BeforeEach
    void installScheduler() {
        previousScheduler = TaskSchedulers.current();
        scheduler = new JvmTaskScheduler();
        TaskSchedulers.configure(scheduler);
        previousAsync = Async.snapshot();
        asyncPool = Executors.newVirtualThreadPerTaskExecutor();
        Async.installExecutor(asyncPool::execute, ignored -> Thread.currentThread().interrupt());
    }

    @AfterEach
    void restoreScheduler() {
        TaskSchedulers.configure(previousScheduler);
        scheduler.close();
        Async.restore(previousAsync);
        asyncPool.shutdownNow();
    }

    @Test
    void nonCanonicalServerDoesNotUseLegacyCompatibilityBeforeHandshake(@TempDir Path tempDirectory) {
        ReSyncFlowClient client = new ReSyncFlowClient("live:proxy:test", new NoopTransport(), null,
            isolatedCache(tempDirectory, "non-canonical"));
        try {
            assertEquals(ReSyncFlowClient.CatalogAuthority.UNAVAILABLE, client.catalogAuthority());
            assertFalse(client.catalogAuthorityAllowsGraphInteraction());
            assertFalse(client.catalogAuthorityAllowsWorkspacePublication());
            assertFalse(client.catalogAuthorityAllowsDurableSave());
            assertFalse(client.catalogAuthorityAllowsTypedMutation());
            assertTrue(client.catalogAuthorityDiagnostic().orElseThrow()
                .contains(ReSyncCatalogPublicationProjection.NON_CANONICAL_SERVER_ID));
            assertTrue(client.typedCatalogAuthorityAdvertised());
            assertTrue(client.catalogPublicationIdentityDiagnostic().isPresent());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void hydratedTypedCacheRemainsReadOnlyUntilTheServerConfirmsItsKey(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.fromString("99999999-9999-4999-8999-999999999999"));
        ReSyncCatalogPublicationCache cache = isolatedCache(tempDirectory, "pre-handshake");
        CatalogCachePublication publication = publication(server);
        seedCatalogPublication(cache, publication);
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), new NoopTransport(), null, cache);
        try {
            assertHydratedPublicationEventually(client, publication);
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertFalse(client.catalogAuthorityAllowsGraphInteraction());
            assertFalse(client.catalogAuthorityAllowsWorkspacePublication());
            assertFalse(client.catalogAuthorityAllowsDurableSave());
            assertFalse(client.catalogAuthorityAllowsTypedMutation());
            assertTrue(client.catalogPublicationProjection().active().isPresent());
            assertTrue(ReSyncTypedInteractionProjection.from(client).isEmpty());
            assertFalse(ReSyncTypedCatalogConsumer.editable(client, "resync.reconnect", "stable-node"));
            assertTrue(client.acknowledgeCatalogPublicationKey(publication.key()));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
            assertTrue(client.catalogAuthorityAllowsGraphInteraction());
            assertTrue(client.catalogAuthorityAllowsWorkspacePublication());
            assertTrue(client.catalogAuthorityAllowsDurableSave());
            assertTrue(client.catalogAuthorityAllowsTypedMutation());
            assertTrue(ReSyncTypedInteractionProjection.from(client).isPresent());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void staleTypedPublicationPacketRetainsThePreviousValidProjection(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("33333333-3333-4333-8333-333333333333"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "stale"));
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCacheKey key = new CatalogCacheKey(server, 31, new ContentHash("c".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        OwnerId owner = new OwnerId("resync.reconnect");
        ContractRef<NodeId> node = ContractRef.of(owner, new NodeId("stable-node"));
        CatalogCachePublication full = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 4,
            List.of(CatalogCachePublication.Entry.present(node, 4, CatalogCacheState.UNAVAILABLE, Set.of(), true,
                CatalogCacheOpaque.of("{\"id\":\"stable-node\"}".getBytes(StandardCharsets.UTF_8)))));
        CatalogCachePublication stale = new CatalogCachePublication(CatalogCachePublication.Kind.DELTA, key, 5,
            List.of(CatalogCachePublication.Entry.present(node, 4, CatalogCacheState.UNAVAILABLE, Set.of(), true,
                CatalogCacheOpaque.of("{\"id\":\"stale\"}".getBytes(StandardCharsets.UTF_8)))));
        try {
            client.connect().join();
            transport.receive(handshakeResponse(catalogKeyCapability(key)));
            transport.receiveCatalogPublication(codec.encodeBytes(full));
            assertPublicationEventually(client, full);
            transport.receiveCatalogPublication(codec.encodeBytes(stale));
            assertPublicationEventually(client, full);
        } finally {
            client.shutdown();
        }
    }

    private static void assertPublicationEventually(ReSyncFlowClient client, CatalogCachePublication expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var active = client.catalogPublicationProjection().active();
            if (active.isPresent()) {
                assertEquals(expected, active.orElseThrow().publication());
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(client.catalogPublicationProjection().active().isPresent(),
            () -> client.catalogAuthorityDiagnostic().orElse("Catalog publication was not retained before the timeout"));
    }

    private static void assertHydratedPublicationEventually(ReSyncFlowClient client,
                                                             CatalogCachePublication expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var active = client.catalogPublicationProjection().active();
            if (active.isPresent() && expected.equals(active.orElseThrow().publication())
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(expected, client.catalogPublicationProjection().active().orElseThrow().publication());
        assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
    }

    private static void seedCatalogPublication(ReSyncCatalogPublicationCache cache,
                                               CatalogCachePublication publication) {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(publication.key().serverId().canonicalText(), transport, null, cache);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void malformedTypedPublicationAfterConfirmationReturnsToReadOnlyReconciliation(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("66666666-6666-4666-8666-666666666666"));
        CapturingTransport transport = new CapturingTransport();
        CountDownLatch conversions = new CountDownLatch(2);
        ReSyncFlowClient client = new BlockingCatalogClient(server.canonicalText(), transport,
            isolatedCache(tempDirectory, "malformed-after-confirmation"), conversions, new CountDownLatch(0));
        CatalogCachePublication publication = publication(server);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.receiveCatalogPublication(codec.encodeBytes(publication));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());

            transport.receiveCatalogPublication(codec.encodeBytes(publication));
            assertTrue(client.typedInteractionProjection().isPresent());
            assertEquals(1L, conversions.getCount());

            transport.receiveCatalogPublication("[]".getBytes(StandardCharsets.UTF_8));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertTrue(client.typedInteractionProjection().isEmpty());
            assertFalse(ReSyncTypedCatalogConsumer.editable(client, "resync.reconnect", "stable-node"));

            CatalogCachePublication changed = publication(publication.key(), publication.revision(),
                "changed-node", CatalogCacheState.UNAVAILABLE);
            transport.receiveCatalogPublication(codec.encodeBytes(changed));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertTrue(client.typedInteractionProjection().isEmpty());

            transport.receiveCatalogPublication(codec.encodeBytes(publication));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
            assertEquals(publication, client.catalogPublicationProjection().active().orElseThrow().publication());
            assertTrue(client.typedInteractionProjection().isPresent());
            assertEquals(1L, conversions.getCount());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void canonicalPublicationEstablishesAuthorityOnTheAuthenticatedTransport(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.fromString("77777777-7777-4777-8777-777777777777"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "receipt"));
        CatalogCachePublication publication = publication(server);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertTrue(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length == 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST));
            assertFalse(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST));
            transport.receiveCatalogPublication(codec.encodeBytes(publication));

            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
            assertEquals(publication.key(), client.catalogPublicationProjection().acknowledgedKey().orElseThrow());
            assertEquals(publication, client.catalogPublicationProjection().active().orElseThrow().publication());
            List<CatalogPublicationReceiptPacket.Packet> receipts = transport.sentFrames.stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && (frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED
                        || frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED))
                .map(frame -> CatalogPublicationReceiptPacket.decode(frame.payload()))
                .toList();
            assertEquals(List.of(CatalogPublicationReceiptPacket.Kind.CLIENT_RECEIVED,
                CatalogPublicationReceiptPacket.Kind.CACHE_APPLIED), receipts.stream()
                .map(CatalogPublicationReceiptPacket.Packet::kind).toList());
            assertTrue(receipts.stream().allMatch(receipt -> receipt.key().equals(publication.key())
                && receipt.revision() == publication.revision()));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void catalogPublicationQueuedAfterDisconnectCannotRestoreAuthority(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.fromString("12121212-1212-4212-8212-121212121212"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "disconnected-publication"));
        CatalogCachePublication publication = publication(server);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.receiveCatalogPublication(codec.encodeBytes(publication));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());

            transport.close();
            transport.receiveCatalogPublication(codec.encodeBytes(publication));

            assertEquals(ReSyncFlowClient.CatalogAuthority.UNAVAILABLE, client.catalogAuthority());
            assertTrue(client.catalogPublicationProjection().active().isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void publicationPreparedOnARetiredGenerationCannotCommitOrEmitTerminalReceipt(@TempDir Path tempDirectory)
        throws Exception {
        ServerId server = new ServerId(UUID.fromString("13131313-1313-4313-8313-131313131313"));
        CapturingTransport transport = new CapturingTransport();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingCatalogClient client = new BlockingCatalogClient(server.canonicalText(), transport,
            isolatedCache(tempDirectory, "retired-worker"), entered, release);
        CatalogCachePublication publication = publication(server);
        byte[] canonical = new CatalogCachePublicationCodec().encodeBytes(publication);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.dispatchCatalogPublication(canonical);
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            transport.close();
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (client.pendingCatalogPublicationWork() != 0 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }

            assertTrue(client.catalogPublicationProjection().active().isEmpty());
            assertFalse(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID && frame.payload().length > 0
                && (frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CLIENT_RECEIVED
                    || frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_APPLIED
                    || frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_CACHE_REJECTED)));
        } finally {
            release.countDown();
            client.shutdown();
        }
    }

    @Test
    void malformedTypedPublicationKeyNeverDowngradesToTheLegacyRegistry(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.fromString("88888888-8888-4888-8888-888888888888"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "malformed-key"));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        capabilities.addProperty("catalogPublicationKey", "not-a-catalog-key");
        addContract(capabilities);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities.toString()));

            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertTrue(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length == 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST));
            assertFalse(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void futureAcknowledgedKeyForcesReadOnlyReconciliationUntilMatchingPublication(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.fromString("44444444-4444-4444-8444-444444444444"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "future-key"));
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCacheKey currentKey = new CatalogCacheKey(server, 41, new ContentHash("d".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        CatalogCacheKey futureKey = new CatalogCacheKey(server, 42, new ContentHash("e".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        OwnerId owner = new OwnerId("resync.reconnect");
        ContractRef<NodeId> node = ContractRef.of(owner, new NodeId("editable-node"));
        CatalogCachePublication current = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, currentKey, 1,
            List.of(CatalogCachePublication.Entry.present(node, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of("{\"id\":\"editable-node\"}".getBytes(StandardCharsets.UTF_8)))));
        CatalogCachePublication future = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, futureKey, 2,
            current.entries());
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(currentKey)));
            transport.receiveCatalogPublication(codec.encodeBytes(current));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
            assertTrue(ReSyncTypedCatalogConsumer.editable(client, owner.value(), node.id().value()));
            ReSyncTypedInteractionProjection currentInteraction = ReSyncTypedInteractionProjection.from(client).orElseThrow();
            assertSame(currentInteraction, ReSyncTypedInteractionProjection.from(client).orElseThrow());

            assertFalse(client.acknowledgeCatalogPublicationKey(futureKey));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertEquals("CATALOG_PUBLICATION_KEY_MISMATCH_RECONCILIATION",
                client.catalogAuthorityDiagnostic().orElseThrow());
            assertFalse(ReSyncTypedCatalogConsumer.editable(client, owner.value(), node.id().value()));

            transport.receiveCatalogPublication(codec.encodeBytes(future));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
            assertTrue(ReSyncTypedCatalogConsumer.editable(client, owner.value(), node.id().value()));
            ReSyncTypedInteractionProjection futureInteraction = ReSyncTypedInteractionProjection.from(client).orElseThrow();
            assertNotSame(currentInteraction, futureInteraction);
            assertEquals(futureKey, futureInteraction.key());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void malformedFunctionBoundaryPreventsTypedAuthority(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.fromString("45454545-4545-4545-8545-454545454545"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "malformed-boundary"));
        CatalogCacheKey key = new CatalogCacheKey(server, 43, new ContentHash("2".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        ContractRef<NodeId> identity = ContractRef.of(new OwnerId("extension"), new NodeId("function.inputs"));
        Map<String, Object> descriptor = Map.of("kind", "node", "id", "function.inputs",
            "displayName", "Function Inputs", "description", "Function Inputs", "domain", "flow",
            "family", "function", "pins", List.of(Map.of("kind", "pin", "id", "flow", "direction", "output",
                "type", Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", "execution"),
                    "arguments", List.of()), "displayName", "Flow", "description", "Flow", "resourceRole", "")),
            "metadata", Map.of("functionBoundary", Map.of("role", "inputs", "flowPin", "flow",
                "parameterPins", List.of(Map.of("id", "value", "name", "Value", "typeRef", "invalid<")))),
            "inspector", Map.of("intent", "none", "sections", List.of()));
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1,
            List.of(CatalogCachePublication.Entry.present(identity, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(CanonicalJson.canonicalize(descriptor).getBytes(StandardCharsets.UTF_8)))));
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(key)));
            transport.receiveCatalogPublication(codec.encodeBytes(publication));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertEquals("CATALOG_INTERACTION.BOUNDARY_PARAMETER_TYPE_INVALID:extension/function.inputs:value",
                client.catalogAuthorityDiagnostic().orElseThrow());
            assertTrue(client.typedInteractionProjection().isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void newerCatalogGenerationAcceptsARevisionLowerThanThePreviousGeneration(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.fromString("55555555-5555-4555-8555-555555555555"));
        CapturingTransport transport = new CapturingTransport();
        ReSyncCatalogPublicationCache cache = isolatedCache(tempDirectory, "generation-restart");
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCacheKey previousKey = new CatalogCacheKey(server, 9, new ContentHash("a".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        CatalogCacheKey replacementKey = new CatalogCacheKey(server, 11, new ContentHash("b".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        CatalogCachePublication previous = publication(previousKey, 101, "previous-generation", CatalogCacheState.UNAVAILABLE);
        CatalogCachePublication replacement = publication(replacementKey, 11, "replacement-generation", CatalogCacheState.UNAVAILABLE);
        seedCatalogPublication(cache, previous);
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null, cache);
        try {
            client.connect().join();
            assertHydratedPublicationEventually(client, previous);
            transport.receive(handshakeResponse(capabilities(replacementKey)));

            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertEquals(previous, client.catalogPublicationProjection().active().orElseThrow().publication());

            transport.receiveCatalogPublication(codec.encodeBytes(replacement));

            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
            assertEquals(replacement, client.catalogPublicationProjection().active().orElseThrow().publication());
            assertEquals(replacementKey, client.catalogPublicationProjection().acknowledgedKey().orElseThrow());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void previouslyTypedServerNeverDowngradesWhenCapabilitiesDisappear(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.randomUUID());
        String serverId = server.canonicalText();
        CapturingTransport transport = new CapturingTransport(server);
        ReSyncCatalogPublicationCache cache = isolatedCache(tempDirectory, "missing");
        CatalogCachePublication publication = publication(server);
        seedCatalogPublication(cache, publication);
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, transport, null, cache);
        try {
            client.connect().join();
            assertHydratedPublicationEventually(client, publication);
            transport.receive(handshakeResponse(null));

            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertEquals(publication.key(), ReSyncTypedCatalogConsumer.authorityKey(client).orElseThrow());
            assertTrue(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length == 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST));
            assertFalse(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void modernContractWithoutAnActivePublicationKeyRemainsReadOnly(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "modern-missing-key"));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        addContract(capabilities);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities.toString()));

            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, client.catalogAuthority());
            assertTrue(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length == 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST));
            assertFalse(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void fabricatedNegotiatedCapabilityCannotDowngradeToLegacyRegistry(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "fabricated-negotiation"));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        capabilities.addProperty("catalogPublicationKey", "invalid-typed-publication-key");
        addContract(capabilities);
        capabilities.getAsJsonObject("flowContract").getAsJsonArray("negotiated").add("fabricated");
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities.toString()));

            assertEquals(ReSyncFlowClient.CatalogAuthority.UNAVAILABLE, client.catalogAuthority());
            assertFalse(client.isConnectedState());
            assertEquals(ReSyncFlowClient.HandshakeStage.FAILED, client.handshakeObservation().stage());
            assertEquals("protocol_error", client.handshakeObservation().failureDiagnostic());
            assertFalse(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void emptySupportedCapabilityListCannotPassModernHandshake(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "empty-supported-negotiation"));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        addContract(capabilities);
        capabilities.getAsJsonObject("flowContract").add("supported", new JsonArray());
        capabilities.getAsJsonObject("flowContract").add("negotiated", new JsonArray());
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities.toString()));

            assertEquals(ReSyncFlowClient.CatalogAuthority.UNAVAILABLE, client.catalogAuthority());
            assertFalse(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void neverTypedServerWithoutACapabilityEnvelopeUsesExplicitLegacyCompatibility(@TempDir Path tempDirectory) {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport(server);
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "legacy-server"));
        try {
            client.connect().join();
            transport.receive(handshakeResponse(null));

            assertEquals(ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY, client.catalogAuthority());
            ReSyncDecodedFrame requestFrame = transport.sentFrames.stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST)
                .findFirst().orElseThrow();
            NodeRegistryRequest request = new Gson().fromJson(
                new String(requestFrame.payload(), 1, requestFrame.payload().length - 1, StandardCharsets.UTF_8),
                NodeRegistryRequest.class);
            assertEquals(NodeRegistryRequest.LEGACY_COMPATIBILITY_CAPABILITY, request.getCompatibilityCapability());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void legacyCompatibilityRemainsReadOnlyWithoutTypedMutationAuthority(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport(server);
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "legacy-read-authority"));
        try {
            client.connect().join();
            transport.receive(handshakeResponse(null));

            assertTrue(client.legacyReadCompatibilityAllowed());
            assertFalse(typedMutationAuthorityReady(client));
            client.requestResource(ReSyncResourceType.GUI, "legacy-gui", false);
            client.requestResourceList(ReSyncResourceType.GUI);

            assertTrue(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length > 1
                    && frame.payload()[0] == ReSyncResourceType.GUI.requestByte()));
            assertTrue(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length == 1
                    && frame.payload()[0] == ReSyncResourceType.GUI.listRequestByte()));

            DesignerSaveNotifications.start(server.canonicalText(), ReSyncResourceType.GUI, "legacy-gui", "Legacy GUI");
            int sentBeforeSave = transport.sentFrames.size();
            SyncedResourceCache<GuiDefinition> saves = new SyncedResourceCache<>(GuiDefinition::getId, GuiDefinition::getTitle);
            client.sendResourceSave(ReSyncResourceType.GUI,
                saves.putInDraft(server.canonicalText(), new GuiDefinition("legacy-gui", "Legacy GUI", 1)));
            assertEquals(sentBeforeSave, transport.sentFrames.size());
            assertFalse(client.catalogAuthorityAllowsGraphInteraction());
            assertFalse(client.catalogAuthorityAllowsWorkspacePublication());
            assertFalse(client.catalogAuthorityAllowsDurableSave());
            assertFalse(client.catalogAuthorityAllowsTypedMutation());
            int sentBeforeActivation = transport.sentFrames.size();
            client.sendResourceActivation(ReSyncResourceType.GUI, "legacy-gui", false, "legacy-activation");
            assertEquals(sentBeforeActivation, transport.sentFrames.size());
        } finally {
            DesignerSaveNotifications.failAnyForServer(server.canonicalText(), "test cleanup");
            client.shutdown();
        }
    }

    @Test
    void preHandshakeResourceListRefreshWaitsForNegotiatedCapabilities(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport(server);
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "pre-handshake-list"));
        try {
            client.connect().join();
            client.requestResourceList(ReSyncResourceType.GUI);

            Field pendingTypedResourceRequests = ReSyncFlowClient.class.getDeclaredField("pendingTypedResourceRequests");
            pendingTypedResourceRequests.setAccessible(true);
            assertTrue(((Map<?, ?>) pendingTypedResourceRequests.get(client)).isEmpty());

            Field pendingSends = ReSyncFlowClient.class.getDeclaredField("pendingSends");
            pendingSends.setAccessible(true);
            assertFalse(((Set<?>) pendingSends.get(client)).isEmpty());
            assertEquals(0L, transport.sentFrames.stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE).count());

            transport.receive(handshakeResponse(null));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline && transport.sentFrames.stream().noneMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length == 1
                    && frame.payload()[0] == ReSyncResourceType.GUI.listRequestByte())) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(transport.sentFrames.stream().anyMatch(frame ->
                frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                    && frame.payload().length == 1
                    && frame.payload()[0] == ReSyncResourceType.GUI.listRequestByte()));
            assertTrue(((Map<?, ?>) pendingTypedResourceRequests.get(client)).isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void coreAndManagedReadsUseTheNegotiatedGenericContractVersion(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport(server);
        Probe probe = new Probe();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, probe,
            isolatedCache(tempDirectory, "generic-read-contract"));
        CatalogVersion negotiated = new CatalogVersion(1, 2);
        try {
            FlowManagerTestConnection.installCurrent(probe.manager, server.canonicalText(), client, transport);
            client.connect().join();
            transport.receive(handshakeResponse(genericReadCapabilities(server, negotiated)));

            client.requestFlow("core-load", false);
            client.requestFlowList();
            client.requestResource(ReSyncResourceType.GUI, "managed-load", false);
            client.requestResourceList(ReSyncResourceType.GUI);

            List<ProtocolEnvelope<Map<String, Object>>> requests = awaitGenericReadRequests(transport);
            assertNegotiatedRead(requests, negotiated, ResourceOperationKind.LOAD, "flow", "core-load");
            assertNegotiatedRead(requests, negotiated, ResourceOperationKind.LIST, "flow", null);
            assertNegotiatedRead(requests, negotiated, ResourceOperationKind.LOAD, "gui", "managed-load");
            assertNegotiatedRead(requests, negotiated, ResourceOperationKind.LIST, "gui", null);
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void managedRequestsWithoutANegotiatedGenericContractSettleWithoutSending(@TempDir Path tempDirectory)
        throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport(server);
        Probe probe = new Probe();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, probe,
            isolatedCache(tempDirectory, "generic-contract-unavailable"));
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            client.connect().join();
            transport.receive(handshakeResponse(genericCapabilitiesWithoutAContractVersion(server)));
            int sentBeforeRequests = transport.sentFrames.size();

            client.requestResource(ReSyncResourceType.GUI, "managed-load", false);
            client.requestResourceList(ReSyncResourceType.GUI);

            ticket = DesignerSaveNotifications.startExact(server.canonicalText(), ReSyncResourceType.GUI,
                "managed-save", "Managed Save");
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Boolean> saved = new AtomicReference<>();
            ticket.whenFinished((result, ignored) -> {
                saved.set(result);
                finished.countDown();
            });
            SyncedResourceCache<GuiDefinition> saves = new SyncedResourceCache<>(GuiDefinition::getId,
                GuiDefinition::getTitle);
            client.sendResourceSave(ReSyncResourceType.GUI,
                saves.putInDraft(server.canonicalText(), new GuiDefinition("managed-save", "Managed Save", 1)), ticket);

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, saved.get());
            assertFalse(DesignerSaveNotifications.isPending(ticket));
            assertEquals(sentBeforeRequests, transport.sentFrames.size());
        } finally {
            if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
                DesignerSaveNotifications.failExact(ticket, "test cleanup");
            }
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void handshakeHydratesAuthorityEpochBeforeConnectionReady(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "authority-epoch"));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        addContract(capabilities);
        capabilities.addProperty("authorityEpoch", 7L);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities.toString()));

            assertEquals(7L, client.resourceRevisionReconciler().authorityEpoch(server.canonicalText()));
            assertTrue(booleanField(client, "authorityEpochAdvertised"));
            assertTrue(booleanField(client, "authorityEpochEstablished"));
            assertEquals(7L, longField(client, "handshakeAuthorityEpoch"));
            assertTrue(typedMutationAuthorityReady(client));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void replacementClientReconcilesItsFreshRevisionAuthorityWithWorldGen(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        addContract(capabilities);
        capabilities.addProperty("authorityEpoch", 7L);
        CapturingTransport firstTransport = new CapturingTransport();
        ReSyncFlowClient first = new ReSyncFlowClient(server.canonicalText(), firstTransport, null,
            isolatedCache(tempDirectory, "authority-epoch-first"));
        CapturingTransport replacementTransport = new CapturingTransport();
        ReSyncFlowClient replacement = new ReSyncFlowClient(server.canonicalText(), replacementTransport, null,
            isolatedCache(tempDirectory, "authority-epoch-replacement"));
        try {
            first.connect().join();
            firstTransport.receive(handshakeResponse(capabilities.toString()));
            assertTrue(typedMutationAuthorityReady(first));
            first.shutdown();

            replacement.connect().join();
            replacementTransport.receive(handshakeResponse(capabilities.toString()));

            assertEquals(7L, replacement.resourceRevisionReconciler().authorityEpoch(server.canonicalText()));
            assertTrue(typedMutationAuthorityReady(replacement));
        } finally {
            first.shutdown();
            replacement.shutdown();
        }
    }

    @Test
    void zeroAuthorityEpochKeepsTypedMutationsBlocked(@TempDir Path tempDirectory) throws Exception {
        ServerId server = new ServerId(UUID.randomUUID());
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(server.canonicalText(), transport, null,
            isolatedCache(tempDirectory, "authority-epoch-zero"));
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("serverId", server.canonicalText());
        addContract(capabilities);
        capabilities.addProperty("authorityEpoch", 0L);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities.toString()));

            assertEquals(0L, client.resourceRevisionReconciler().authorityEpoch(server.canonicalText()));
            assertTrue(booleanField(client, "authorityEpochAdvertised"));
            assertFalse(booleanField(client, "authorityEpochEstablished"));
            assertFalse(typedMutationAuthorityReady(client));
        } finally {
            client.shutdown();
        }
    }

    private static ReSyncCatalogPublicationCache isolatedCache(Path tempDirectory, String name) {
        return new ReSyncCatalogPublicationCache(DesktopReSyncStorage.fromKey(tempDirectory.resolve(name).resolve("catalog-publication-cache.json")));
    }

    private static CatalogCachePublication publication(ServerId server) {
        CatalogCacheKey key = new CatalogCacheKey(server, 31, new ContentHash("c".repeat(64)),
            new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());
        return publication(key, 4, "stable-node", CatalogCacheState.UNAVAILABLE);
    }

    private static CatalogCachePublication publication(CatalogCacheKey key, long revision, String nodeId,
                                                       CatalogCacheState state) {
        OwnerId owner = new OwnerId("resync.reconnect");
        ContractRef<NodeId> node = ContractRef.of(owner, new NodeId(nodeId));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, revision,
            List.of(CatalogCachePublication.Entry.present(node, revision, state, Set.of(), true,
                CatalogCacheOpaque.of(("{\"id\":\"" + nodeId + "\"}").getBytes(StandardCharsets.UTF_8)))));
    }

    private static byte[] handshakeResponse(String capabilities) {
        byte[] capabilityBytes = capabilities == null ? new byte[0] : capabilities.getBytes(StandardCharsets.UTF_8);
        int optionalBytes = capabilities == null ? 0 : Integer.BYTES * 2 + Integer.BYTES + capabilityBytes.length;
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 4 + optionalBytes);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        if (capabilities != null) {
            payload.putInt(0);
            payload.putInt(0);
            payload.putInt(capabilityBytes.length);
            payload.put(capabilityBytes);
        }
        return payload.array();
    }

    private static String capabilities(CatalogCacheKey key) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", key.serverId().canonicalText());
        root.addProperty("catalogPublicationKey", key.canonicalText());
        addContract(root);
        return root.toString();
    }

    private static String catalogKeyCapability(CatalogCacheKey key) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", key.serverId().canonicalText());
        root.addProperty("catalogPublicationKey", key.canonicalText());
        return root.toString();
    }

    private static String genericReadCapabilities(ServerId server, CatalogVersion version) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", server.canonicalText());
        root.addProperty("authorityEpoch", 1L);
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject contract = new JsonObject();
        JsonObject contractVersion = new JsonObject();
        contractVersion.addProperty("generation", version.generation());
        contractVersion.addProperty("minor", version.minor());
        contract.add("version", contractVersion);
        protocol.add("genericResourceContract", contract);
        JsonObject operations = new JsonObject();
        JsonArray reads = new JsonArray();
        reads.add("list");
        reads.add("load");
        operations.add("read", reads);
        operations.add("mutate", new JsonArray());
        protocol.add("resourceOperations", operations);
        root.add("protocolEnvelope", protocol);
        addContract(root);
        return root.toString();
    }

    private static String genericCapabilitiesWithoutAContractVersion(ServerId server) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", server.canonicalText());
        root.addProperty("authorityEpoch", 1L);
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject operations = new JsonObject();
        JsonArray reads = new JsonArray();
        reads.add("list");
        reads.add("load");
        operations.add("read", reads);
        JsonArray mutations = new JsonArray();
        mutations.add("create");
        mutations.add("save");
        operations.add("mutate", mutations);
        protocol.add("resourceOperations", operations);
        JsonObject authority = new JsonObject();
        authority.addProperty("supported", true);
        authority.addProperty("durable", true);
        protocol.add("mutationAuthority", authority);
        root.add("protocolEnvelope", protocol);
        addContract(root);
        return root.toString();
    }

    private static List<ProtocolEnvelope<Map<String, Object>>> awaitGenericReadRequests(CapturingTransport transport) {
        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        List<ProtocolEnvelope<Map<String, Object>>> requests = List.of();
        while (System.nanoTime() < deadline) {
            requests = transport.sentFrames.stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE)
                .map(frame -> codec.decodeBytes(frame.payload()))
                .filter(envelope -> envelope.body() instanceof ProtocolBody.ResourceRequest)
                .toList();
            if (requests.size() >= 4) {
                return requests;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        return requests;
    }

    private static void assertNegotiatedRead(List<ProtocolEnvelope<Map<String, Object>>> requests,
                                             CatalogVersion version, ResourceOperationKind kind,
                                             String type, String id) {
        assertTrue(requests.stream().anyMatch(envelope -> {
            ProtocolBody.ResourceRequest body = (ProtocolBody.ResourceRequest) envelope.body();
            if (body.operation().kind() != kind || !version.equals(envelope.contractVersion())) {
                return false;
            }
            if (kind == ResourceOperationKind.LOAD) {
                return envelope.resource() != null && type.equals(envelope.resource().resourceType().value())
                    && id.equals(envelope.resource().id());
            }
            return body.operation() instanceof ResourceListRequest list
                && type.equals(list.type().id().value());
        }));
    }

    private static void addContract(JsonObject root) {
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
    }

    private static boolean booleanField(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(client);
    }

    private static long longField(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(client);
    }

    private static boolean typedMutationAuthorityReady(ReSyncFlowClient client) throws Exception {
        var method = ReSyncFlowClient.class.getDeclaredMethod("typedMutationAuthorityReady");
        method.setAccessible(true);
        return (boolean) method.invoke(client);
    }

    private static final class NoopTransport implements ReSyncFrameTransport {
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

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;

        private Probe() {
            super(RemotelyComposition.browser(new TestHost()).scheduler(TaskSchedulers.current()).build());
            manager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            try {
                manager.shutdown();
            } finally {
                getComposition().serverSettingsRegistryStorageSnapshot().restore();
                TestHost host = (TestHost) getHost();
                ApplicationHostRegistry.install(host.previousHost);
                RemotelyClient.INSTANCE = host.previousClient;
            }
        }
    }

    private static final class TestHost implements ApplicationHost {
        private final ApplicationHost previousHost = ApplicationHostRegistry.current();
        private final RemotelyClient previousClient = RemotelyClient.INSTANCE;

        @Override public void setScreen(Screen screen) { }
        @Override public Screen getCurrentScreen() { return null; }
        @Override public void ensureTextRenderer() { }
        @Override public MinecraftGameAssets getGameAssets() { return null; }
        @Override public Object getFontIdentifier(String namespace, String path) { return null; }
        @Override public void openParentScreen(Screen currentScreen, Object parent) { }
        @Override public void setClipboard(String text) { }
        @Override public boolean shouldCloseRootScreen() { return false; }
        @Override public String getGameVersion() { return ""; }
        @Override public String getGameUserName() { return ""; }
        @Override public String getGameUUID() { return ""; }
    }

    private static final class CapturingTransport implements ReSyncFrameTransport {
        private final List<ReSyncDecodedFrame> sentFrames = new CopyOnWriteArrayList<>();
        private final ServerId peerServerId;
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;
        private volatile boolean open = true;

        private CapturingTransport() {
            this(null);
        }

        private CapturingTransport(ServerId peerServerId) {
            this.peerServerId = peerServerId;
        }

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
            sentFrames.add(new ReSyncFrameCodec().decode(frame, null));
        }

        @Override
        public void close() {
            open = false;
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean reusableAfterDisconnect() {
            return false;
        }

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.ofNullable(peerServerId);
        }

        private void receive(byte[] frame) {
            int sentBefore = sentFrames.size();
            frameHandler.accept(new ReSyncFrameCodec().encode(
                ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
                frame,
                (short) 0,
                1));
            awaitSettled(sentBefore);
        }

        private void receiveCatalogPublication(byte[] publication) {
            int sentBefore = sentFrames.size();
            ByteBuffer payload = ByteBuffer.allocate(1 + publication.length);
            payload.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
            payload.put(publication);
            frameHandler.accept(new ReSyncFrameCodec().encode(
                ReSyncProtocolContract.MESSAGE_DATA,
                payload.array(),
                ReSyncProtocolContract.CHANNEL_FLOW_ID,
                2));
            awaitSettled(sentBefore);
        }

        private void dispatchCatalogPublication(byte[] publication) {
            ByteBuffer payload = ByteBuffer.allocate(1 + publication.length);
            payload.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
            payload.put(publication);
            frameHandler.accept(new ReSyncFrameCodec().encode(
                ReSyncProtocolContract.MESSAGE_DATA,
                payload.array(),
                ReSyncProtocolContract.CHANNEL_FLOW_ID,
                2));
        }

        private void awaitSettled(int sentBefore) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            long stableSince = System.nanoTime();
            int observed = sentFrames.size();
            boolean active = observed != sentBefore;
            while (System.nanoTime() < deadline) {
                int current = sentFrames.size();
                if (current != observed) {
                    observed = current;
                    stableSince = System.nanoTime();
                    active = true;
                } else if (active && System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(20)) {
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
    }

    private static final class BlockingCatalogClient extends ReSyncFlowClient {
        private final DiagnosticSink previousSink;

        private BlockingCatalogClient(String serverId, ReSyncFrameTransport transport,
                                      ReSyncCatalogPublicationCache cache, CountDownLatch entered,
                                      CountDownLatch release) {
            super(serverId, transport, null, cache);
            previousSink = ReSyncLifecycleDiagnostics.install(new DiagnosticSink() {
                @Override
                public Status status() {
                    return Status.ready(Mode.VERBOSE);
                }

                @Override
                public Offer offer(DiagnosticEvent event) {
                    if ("typed_catalog_conversion_started".equals(event.stage())) {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Catalog preparation was not released");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }
                    return Offer.ACCEPTED;
                }

                @Override public Status pause() { return status(); }
                @Override public Status resume() { return status(); }
                @Override public Flush flush() { return Flush.EMPTY; }
                @Override public void close() { }
            });
        }

        @Override
        public void shutdown() {
            try {
                super.shutdown();
            } finally {
                ReSyncLifecycleDiagnostics.install(previousSink);
            }
        }
    }
}
