package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyClient;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.GraphResourceCache;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCoreGraphRequestTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("78787878-7878-4787-8787-787878787878"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final ProtocolEnvelopeCodec<Map<String, Object>> CODEC =
        new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());

    @Test
    void coreRequestPayloadTypeMatchesTheExpectedResponseContract() throws Exception {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        try {
            authenticate(client, transport);
            cacheCapabilities(probe.manager, genericCapabilities());
            Method supports = ReSyncFlowClient.class.getDeclaredMethod("supportsGenericCoreResource",
                ReSyncResourceType.class, ResourceOperationKind.class, boolean.class);
            supports.setAccessible(true);
            assertTrue((Boolean) supports.invoke(client, ReSyncResourceType.FLOW, ResourceOperationKind.LOAD, false),
                client.catalogAuthorityDebugState());

            client.requestFlow("payload-contract-flow", false);
            ProtocolEnvelope<Map<String, Object>> load = transport.requestEnvelope(ResourceOperationKind.LOAD,
                "payload-contract-flow", null);
            assertEquals(ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), load.payloadType());

            client.requestFlowList();
            ProtocolEnvelope<Map<String, Object>> list = transport.requestEnvelope(ResourceOperationKind.LIST, null, null);
            assertEquals(ResourceOperationKind.LIST, ((ProtocolBody.ResourceRequest) list.body()).operation().kind());
            assertEquals(ContractRef.of(OWNER, ResourceTypeId.of("resource.page")), list.payloadType());

            Method queryMethod = ReSyncFlowClient.class.getDeclaredMethod("sendCoreGraphList", ReSyncResourceType.class,
                String.class, String.class, UUID.class, UUID.class, ResourceOperationKind.class);
            queryMethod.setAccessible(true);
            assertTrue((boolean) queryMethod.invoke(client, ReSyncResourceType.FLOW, null, "payload-contract",
                UUID.randomUUID(), UUID.randomUUID(), ResourceOperationKind.QUERY));
            ProtocolEnvelope<Map<String, Object>> query = transport.requestEnvelope(ResourceOperationKind.QUERY, null, null);
            assertEquals(ResourceOperationKind.QUERY, ((ProtocolBody.ResourceRequest) query.body()).operation().kind());
            assertEquals(ContractRef.of(OWNER, ResourceTypeId.of("resource.page")), query.payloadType());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void graphLoadAndListUseTypedCoreRequestsAndEmptyPagesAreTerminal() {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        List<ReSyncFlowClient.CoreGraphListSnapshot> snapshots = new CopyOnWriteArrayList<>();
        client.addCoreGraphListListener(snapshots::add);
        try {
            authenticate(client, transport);
            cacheCapabilities(probe.manager, genericCapabilities());

            client.requestFlow("load-flow", false);
            ProtocolEnvelope<Map<String, Object>> load = transport.requestEnvelope(ResourceOperationKind.LOAD,
                "load-flow", null);
            assertEquals(ProtocolEnvelope.Kind.REQUEST, load.kind());
            assertEquals(ResourceOperationKind.LOAD, ((ProtocolBody.ResourceRequest) load.body()).operation().kind());
            ReSyncFlowClient.CoreGraphRequestBinding loadBinding = client.pendingCoreGraphRequest(load.requestId()).orElseThrow();
            assertEquals(load.requestId(), loadBinding.requestId());
            assertEquals(load.correlationId(), loadBinding.correlationId());
            assertEquals(load.traceId(), loadBinding.traceId());
            assertEquals(ResourceOperationKind.LOAD, loadBinding.operation());
            assertEquals("load-flow", loadBinding.resource().id());
            assertEquals(0L, loadBinding.expectedRevision());

            client.requestFlowList();
            ProtocolEnvelope<Map<String, Object>> list = transport.requestEnvelope(ResourceOperationKind.LIST, null, null);
            ResourceListRequest request = (ResourceListRequest) ((ProtocolBody.ResourceRequest) list.body()).operation();
            assertEquals(ResourceOperationKind.LIST, request.kind());
            assertNull(request.cursor());
            assertNull(request.search());
            ReSyncFlowClient.CoreGraphRequestBinding listBinding = client.pendingCoreGraphRequest(list.requestId()).orElseThrow();
            assertEquals(ResourceOperationKind.LIST, listBinding.operation());
            assertNull(listBinding.resource());
            assertNull(listBinding.mutationId());
            assertNull(listBinding.cursor());

            ResourcePage<Map<String, Object>> page = new ResourcePage<>(List.of(), null, true);
            ProtocolEnvelope<Map<String, Object>> response = pageResponse(list, page);
            transport.receiveEnvelope(CODEC.encodeBytes(response));
            await(() -> client.coreResourcePageCursor(list.correlationId()).isPresent()
                && client.pendingCoreGraphRequest(list.requestId()).isEmpty() && snapshots.size() == 1);

            ReSyncFlowClient.CoreResourcePageCursor cursor = client.coreResourcePageCursor(list.correlationId()).orElseThrow();
            assertEquals(ReSyncResourceType.FLOW, cursor.type());
            assertTrue(cursor.empty());
            assertTrue(cursor.complete());
            assertFalse(cursor.continuationExpected());
            assertTrue(client.pendingCoreGraphRequest(list.requestId()).isEmpty());
            assertEquals(1, snapshots.size());
            assertEquals(ReSyncResourceType.FLOW, snapshots.getFirst().type());
            assertTrue(snapshots.getFirst().resources().isEmpty());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void pageContinuationReusesOnlyTheBoundCursorAndTrace() {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        List<ReSyncFlowClient.CoreGraphListSnapshot> snapshots = new CopyOnWriteArrayList<>();
        client.addCoreGraphListListener(snapshots::add);
        try {
            authenticate(client, transport);
            cacheCapabilities(probe.manager, genericCapabilities());
            client.requestFlowList();
            ProtocolEnvelope<Map<String, Object>> first = transport.requestEnvelope(ResourceOperationKind.LIST, null, null);
            GraphDocument graph = graph("flow", "page-flow", 1L);
            ResourceDocument<Map<String, Object>> document = liveDocument(graph, UUID.randomUUID());
            ResourcePage<Map<String, Object>> page = new ResourcePage<>(List.of(document), "cursor-2", false);
            ProtocolEnvelope<Map<String, Object>> response = pageResponse(first, page);
            CoreGraphResourceProjection.PageProjection projected = CoreGraphResourceProjection.projectPage(response,
                Set.of(), BINDING);
            assertTrue(projected.accepted(), projected.rejectionReason());
            transport.receiveEnvelope(CODEC.encodeBytes(response));
            await(() -> client.coreResourcePageCursor(first.correlationId()).isPresent()
                || client.pendingCoreGraphRequest(first.requestId()).isEmpty());
            assertTrue(client.coreResourcePageCursor(first.correlationId()).isPresent(),
                "The Core page request was removed before its cursor was applied");
            assertEquals("cursor-2", client.coreResourcePageCursor(first.correlationId()).orElseThrow().nextCursor());
            await(() -> transport.protocolEnvelopeCount() >= 2);

            ReSyncFlowClient.CoreResourcePageCursor cursor = client.coreResourcePageCursor(first.correlationId()).orElseThrow();
            assertEquals("cursor-2", cursor.nextCursor());
            ProtocolEnvelope<Map<String, Object>> continuation = transport.requestEnvelope(ResourceOperationKind.LIST,
                null, "cursor-2");
            ResourceListRequest request = (ResourceListRequest) ((ProtocolBody.ResourceRequest) continuation.body()).operation();
            assertEquals("cursor-2", request.cursor());
            assertEquals(first.correlationId(), continuation.correlationId());
            assertEquals(first.traceId(), continuation.traceId());
            assertTrue(client.pendingCoreGraphRequest(continuation.requestId()).orElseThrow().cursor().equals("cursor-2"));
            assertFalse(client.requestCoreResourcePage(ReSyncResourceType.FLOW, first.correlationId()));

            GraphDocument secondGraph = graph("flow", "page-flow-2", 1L);
            transport.receiveEnvelope(CODEC.encodeBytes(pageResponse(continuation,
                new ResourcePage<>(List.of(liveDocument(secondGraph, UUID.randomUUID())), null, true))));
            await(() -> client.coreResourcePageCursor(first.correlationId())
                .map(ReSyncFlowClient.CoreResourcePageCursor::complete).orElse(false) && snapshots.size() == 1);
            ReSyncFlowClient.CoreResourcePageCursor completed = client.coreResourcePageCursor(first.correlationId()).orElseThrow();
            assertTrue(completed.complete());
            assertEquals(Set.of(graph.resource(), secondGraph.resource()), completed.resources());
            assertEquals(1, snapshots.size());
            assertEquals(completed.resources(), snapshots.getFirst().liveResources());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void graphCreateBindsMutationAndCompletesMatchingAck() {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        List<ReSyncFlowClient.CoreGraphMutationResult> results = new CopyOnWriteArrayList<>();
        client.addCoreGraphMutationListener(results::add);
        try {
            authenticate(client, transport);
            cacheCapabilities(probe.manager, genericCapabilities());
            GraphDocument graph = graph("flow", "create-flow", 1L);

            client.sendResourceSave(ReSyncResourceType.FLOW, graph);
            ProtocolEnvelope<Map<String, Object>> request = transport.requestEnvelope(ResourceOperationKind.CREATE,
                "create-flow", null);
            ResourceCreateRequest<?> operation = (ResourceCreateRequest<?>) ((ProtocolBody.ResourceRequest) request.body()).operation();
            assertEquals(ResourceOperationKind.CREATE, operation.kind());
            assertEquals(request.requestId(), client.pendingCoreGraphRequest(request.requestId()).orElseThrow().requestId());
            assertEquals(request.correlationId(), client.pendingCoreGraphRequest(request.requestId()).orElseThrow().correlationId());
            assertEquals(request.traceId(), client.pendingCoreGraphRequest(request.requestId()).orElseThrow().traceId());
            assertEquals(operation.mutationId(), request.mutationId());
            assertEquals(operation.mutationId(), client.pendingCoreGraphRequest(request.requestId()).orElseThrow().mutationId());
            assertEquals(0L, request.revision());
            assertTrue(operation.payload() instanceof Map<?, ?>);
            @SuppressWarnings("unchecked")
            Map<String, Object> operationPayload = (Map<String, Object>) operation.payload();
            assertEquals(String.valueOf(CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION),
                String.valueOf(operationPayload.get(CoreGraphResourceProjection.ASSET_FORMAT_VERSION)));

            GraphDocument stored = graph("flow", "create-flow", 1L);
            ResourceDocument<Map<String, Object>> document = liveDocument(stored, operation.mutationId());
            ProtocolEnvelope<Map<String, Object>> ack = documentResponse(request, ResourceOperationKind.CREATE, document,
                ProtocolEnvelope.Kind.ACK, ProtocolEnvelope.Status.OK);
            transport.receiveEnvelope(CODEC.encodeBytes(ack));
            await(() -> client.pendingCoreGraphRequest(request.requestId()).isEmpty() && results.size() == 1);

            assertTrue(client.pendingCoreGraphRequest(request.requestId()).isEmpty());
            assertEquals(1L, client.coreGraphResourceCache().state(document.resource()).orElseThrow().revision());
            assertEquals(1, results.size());
            ReSyncFlowClient.CoreGraphMutationResult result = results.getFirst();
            assertEquals(request.requestId(), result.requestId());
            assertEquals(request.correlationId(), result.correlationId());
            assertEquals(request.traceId(), result.traceId());
            assertEquals(operation.mutationId(), result.mutationId());
            assertEquals(document.resource(), result.resource());
            assertEquals(0L, result.expectedRevision());
            assertEquals(request.payloadHash(), result.requestPayloadHash());
            assertEquals(document.revision(), result.revision());
            assertEquals(document.mutationId(), result.authoritativeMutationId());
            assertEquals(document.payloadHash(), result.payloadHash());
            assertTrue(result.accepted());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void nullCurrentConflictCompletesAndDoesNotUseLegacyGraphPackets() {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        try {
            authenticate(client, transport);
            cacheCapabilities(probe.manager, genericCapabilities());
            GraphDocument graph = graph("flow", "conflict-flow", 1L);
            client.sendResourceSave(ReSyncResourceType.FLOW, graph);
            ProtocolEnvelope<Map<String, Object>> request = transport.requestEnvelope(ResourceOperationKind.CREATE,
                "conflict-flow", null);
            ServerResourceLocator resource = request.resource();
            ProtocolEnvelope<Map<String, Object>> conflict = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.CONFLICT,
                request.contractVersion(), UUID.randomUUID(), request.requestId(), request.correlationId(), request.traceId(), SERVER,
                resource, 0L, request.authorityEpoch(), null, request.operation(), request.capabilities(), request.payloadType(), null, null, false,
                null, null, null, null, null, request.sequence(), ProtocolEnvelope.Status.CONFLICT, List.of(), Map.of(),
                new ProtocolBody.ConflictResponse(resource, null));
            transport.receiveEnvelope(CODEC.encodeBytes(conflict));
            await(() -> client.pendingCoreGraphRequest(request.requestId()).isEmpty());

            assertTrue(client.pendingCoreGraphRequest(request.requestId()).isEmpty());
            assertTrue(client.coreGraphResourceCache().state(resource).isEmpty());
            assertTrue(transport.protocolEnvelopeCount() >= 1);
            assertTrue(transport.frames().stream().noneMatch(GenericCoreGraphRequestTest::legacyGraphPacket));
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void graphMutationStopsWhenDurableCoreCapabilityIsAbsent() {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache());
        try {
            client.sendResourceSave(ReSyncResourceType.FLOW, graph("flow", "read-only-flow", 1L));
            client.sendResourceDelete(ReSyncResourceType.FLOW, "read-only-flow");
            client.sendResourceActivation(ReSyncResourceType.FLOW, "read-only-flow", false, "legacy-request");
            assertEquals(0, transport.frames().size());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void coreMutationWaitsForAnAuthoritativeHandshake() throws Exception {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        try {
            client.connect().join();
            cacheCapabilities(probe.manager, genericCapabilities());
            assertFalse(client.sendResourceSave(ReSyncResourceType.FLOW, graph("flow", "queued-flow", 1L)));
            Field field = ReSyncFlowClient.class.getDeclaredField("pendingCoreGraphRequests");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, ?> pending = (Map<UUID, ?>) field.get(client);
            assertTrue(pending.isEmpty());

            transport.receiveHandshake(genericCapabilities().toString());
            awaitConnected(client);
            establishTypedCatalogAuthority(client);
            assertTrue(client.sendResourceSave(ReSyncResourceType.FLOW, graph("flow", "queued-flow", 1L)));
            ProtocolEnvelope<Map<String, Object>> request = transport.requestEnvelope(ResourceOperationKind.CREATE,
                "queued-flow", null);
            assertEquals(ProtocolEnvelope.Kind.REQUEST, request.kind());
            assertNotNull(request.mutationId());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void coreOpenIntentIsBoundToOneValidatedLoadResponse() throws Exception {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        try {
            authenticate(client, transport);
            cacheCapabilities(probe.manager, genericCapabilities());
            client.requestFlow("open-flow", true);
            ProtocolEnvelope<Map<String, Object>> request = transport.requestEnvelope(ResourceOperationKind.LOAD,
                "open-flow", null);
            Field intentsField = ReSyncFlowClient.class.getDeclaredField("pendingCoreOpenRequests");
            intentsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<UUID> intents = (Set<UUID>) intentsField.get(client);
            assertEquals(Set.of(request.requestId()), intents);

            ResourceDocument<Map<String, Object>> document = liveDocument(graph("flow", "open-flow", 1L), UUID.randomUUID());
            ProtocolEnvelope<Map<String, Object>> response = documentResponse(request, ResourceOperationKind.LOAD,
                document, ProtocolEnvelope.Kind.ACK, ProtocolEnvelope.Status.OK);
            transport.receiveEnvelope(CODEC.encodeBytes(response));
            await(intents::isEmpty);
            assertTrue(intents.isEmpty());
            transport.receiveEnvelope(CODEC.encodeBytes(response));
            assertTrue(intents.isEmpty());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void matchedCoreReadsRepublishExactDuplicatesWithoutRepublishingUnsolicitedEvents() throws Exception {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        CountDownLatch rediscovered = new CountDownLatch(2);
        CountDownLatch unsolicited = new CountDownLatch(1);
        AtomicInteger notifications = new AtomicInteger();
        List<ReSyncFlowClient.CoreGraphResourceTransition> transitions = new CopyOnWriteArrayList<>();
        client.addCoreGraphResourceListener(transition -> {
            transitions.add(transition);
            if (notifications.incrementAndGet() <= 2) {
                rediscovered.countDown();
            } else {
                unsolicited.countDown();
            }
        });
        try {
            authenticate(client, transport);
            cacheCapabilities(probe.manager, genericCapabilities());
            ResourceDocument<Map<String, Object>> document = liveDocument(
                graph("flow", "rediscovered-flow", 1L), UUID.randomUUID());

            client.requestFlow("rediscovered-flow", false);
            ProtocolEnvelope<Map<String, Object>> firstRequest = transport.requestEnvelope(ResourceOperationKind.LOAD,
                "rediscovered-flow", null);
            transport.receiveEnvelope(CODEC.encodeBytes(documentResponse(firstRequest, ResourceOperationKind.LOAD,
                document, ProtocolEnvelope.Kind.ACK, ProtocolEnvelope.Status.OK)));
            await(() -> notifications.get() == 1 && client.pendingCoreGraphRequest(firstRequest.requestId()).isEmpty());

            client.requestFlow("rediscovered-flow", false);
            ProtocolEnvelope<Map<String, Object>> secondRequest = transport.requestEnvelope(ResourceOperationKind.LOAD,
                "rediscovered-flow", null);
            transport.receiveEnvelope(CODEC.encodeBytes(documentResponse(secondRequest, ResourceOperationKind.LOAD,
                document, ProtocolEnvelope.Kind.ACK, ProtocolEnvelope.Status.OK)));

            assertTrue(rediscovered.await(2L, TimeUnit.SECONDS));
            assertEquals(List.of(GraphResourceCache.Status.NEW, GraphResourceCache.Status.DUPLICATE),
                transitions.stream().map(ReSyncFlowClient.CoreGraphResourceTransition::status).toList());

            transport.receiveEnvelope(CODEC.encodeBytes(documentEvent(secondRequest, document,
                ResourceOperationKind.LOAD)));
            assertFalse(unsolicited.await(200L, TimeUnit.MILLISECONDS));
            assertEquals(2, notifications.get());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void legacyGraphRepliesAreRejectedBeforeLegacyDeserialization() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache());
        try {
            authenticate(client, transport);
            Method receive = ReSyncFlowClient.class.getDeclaredMethod("handleDataMessage", short.class, byte[].class,
                int.class);
            receive.setAccessible(true);
            for (ReSyncResourceType type : List.of(ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION,
                ReSyncResourceType.COMMAND)) {
                for (byte packet : List.of(type.dataResponseByte(), type.listResponseByte(), type.saveAckByte())) {
                    assertTrue(ReSyncResourceType.isLegacyGraphResponse(packet));
                    assertDoesNotThrow(() -> receive.invoke(client, ReSyncProtocolContract.CHANNEL_FLOW_ID,
                        new byte[] {packet, (byte) 0xff, (byte) 0xfe}, client.activeTransportGeneration()));
                }
            }
            assertNull(ReSyncResourceType.byDataResponse(ReSyncResourceType.FLOW.dataResponseByte()));
            assertEquals(ReSyncResourceType.GUI,
                ReSyncResourceType.byDataResponse(ReSyncResourceType.GUI.dataResponseByte()));
        } finally {
            client.shutdown();
        }
    }

    private static void authenticate(ReSyncFlowClient client, CapturingTransport transport) {
        client.connect().join();
        transport.receiveHandshake(genericCapabilities().toString());
        awaitConnected(client);
        establishTypedCatalogAuthority(client);
        awaitStartupSettled(client, transport);
    }

    private static void awaitConnected(ReSyncFlowClient client) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (client.connectionState() != ReSyncFlowClient.ConnectionState.CONNECTED
            && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
    }

    private static void establishTypedCatalogAuthority(ReSyncFlowClient client) {
        try {
            Field lifecycleField = ReSyncFlowClient.class.getDeclaredField("catalogLifecycleLock");
            lifecycleField.setAccessible(true);
            synchronized (lifecycleField.get(client)) {
                CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
                List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
                    new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                        true, true, CatalogCacheState.ACTIVE, List.of()),
                    new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                        true, true, CatalogCacheState.ACTIVE, List.of()),
                    new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                        true, true, CatalogCacheState.ACTIVE, List.of()),
                    new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                        true, true, CatalogCacheState.ACTIVE, List.of()));
                CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING,
                    new CatalogVersion(1, 0), CatalogProjectionVersion.current(), sections, Set.of());
                CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
                    key, BINDING, 1L, List.of(), authoring, Map.of());
                Field publicationField = ReSyncFlowClient.class.getDeclaredField("catalogPublicationProjection");
                publicationField.setAccessible(true);
                ReSyncCatalogPublicationProjection publicationProjection =
                    (ReSyncCatalogPublicationProjection) publicationField.get(client);
                publicationProjection.acknowledgeActiveKey(key);
                assertTrue(publicationProjection.apply(publication,
                    new CatalogCachePublicationCodec().encodeBytes(publication)));
                Field authoringField = ReSyncFlowClient.class.getDeclaredField("catalogAuthoringProjection");
                authoringField.setAccessible(true);
                ReSyncCatalogAuthoringProjection authoringProjection =
                    (ReSyncCatalogAuthoringProjection) authoringField.get(client);
                assertTrue(authoringProjection.apply(key, 1L, authoring,
                    new CatalogAuthoringPublicationCodec().encodeBytes(authoring)));
                Field authority = ReSyncFlowClient.class.getDeclaredField("catalogAuthority");
                authority.setAccessible(true);
                authority.set(client, ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION);
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void awaitStartupSettled(ReSyncFlowClient client, CapturingTransport transport) {
        try {
            Field field = ReSyncFlowClient.class.getDeclaredField("completedStartupGeneration");
            field.setAccessible(true);
            await(() -> {
                try {
                    return field.getInt(client) == client.activeTransportGeneration();
                } catch (IllegalAccessException exception) {
                    throw new AssertionError(exception);
                }
            });
            int previous = -1;
            long stableSince = System.nanoTime();
            long deadline = stableSince + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                int current = transport.protocolEnvelopeCount();
                if (current != previous) {
                    previous = current;
                    stableSince = System.nanoTime();
                } else if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(50L)) {
                    return;
                }
                Thread.sleep(1L);
            }
            throw new AssertionError("Core startup requests did not settle");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void cacheCapabilities(FlowManager manager, JsonObject capabilities) {
        try {
            Field field = FlowManager.class.getDeclaredField("serverCapabilities");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, JsonObject> values = (Map<String, JsonObject>) field.get(manager);
            values.put(SERVER.canonicalText(), capabilities);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        assertTrue(condition.getAsBoolean());
    }

    private static GraphDocument graph(String type, String id, long revision) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static ResourceDocument<Map<String, Object>> liveDocument(GraphDocument graph, UUID mutationId) {
        Map<String, Object> payload = assetPayload(GraphDocumentCodec.INSTANCE.encode(graph),
            graph.resource().resourceType().value(), CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND,
            ResourceActivationState.ACTIVE, graph.revision(), mutationId);
        return ResourceDocument.live(graph.resource(), graph.revision(), mutationId,
            ResourcePayloadCodecs.json().canonicalize(payload), ResourceActivationState.ACTIVE, "server");
    }

    private static ProtocolEnvelope<Map<String, Object>> documentResponse(ProtocolEnvelope<Map<String, Object>> request,
                                                                           ResourceOperationKind operation,
                                                                           ResourceDocument<Map<String, Object>> document,
                                                                           ProtocolEnvelope.Kind kind,
                                                                           ProtocolEnvelope.Status status) {
        return new ProtocolEnvelope<>(kind, request.contractVersion(), UUID.randomUUID(), request.requestId(),
            request.correlationId(), request.traceId(), SERVER, document.resource(), document.revision(), request.authorityEpoch(), document.mutationId(),
            ContractRef.of(OWNER, OperationId.of("resource." + operation.name().toLowerCase())), request.capabilities(),
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(), document.deleted(),
            null, null, null, null, null, request.sequence(), status, List.of(), Map.of(),
            new ProtocolBody.ResourceDocumentResponse(operation, document));
    }

    private static ProtocolEnvelope<Map<String, Object>> documentEvent(ProtocolEnvelope<Map<String, Object>> request,
                                                                        ResourceDocument<Map<String, Object>> document,
                                                                        ResourceOperationKind operation) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.EVENT, request.contractVersion(), UUID.randomUUID(), null,
            UUID.randomUUID(), UUID.randomUUID(), SERVER, document.resource(), document.revision(), request.authorityEpoch(), document.mutationId(),
            ContractRef.of(OWNER, OperationId.of("resource." + operation.name().toLowerCase())), request.capabilities(),
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(), document.deleted(),
            null, null, null, null, null, 0L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourceDocumentResponse(operation, document));
    }

    private static ProtocolEnvelope<Map<String, Object>> pageResponse(ProtocolEnvelope<Map<String, Object>> request,
                                                                       ResourcePage<Map<String, Object>> page) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, request.contractVersion(), UUID.randomUUID(),
            request.requestId(), request.correlationId(), request.traceId(), SERVER, null, 0L, request.authorityEpoch(), null,
            ContractRef.of(OWNER, OperationId.of("resource.list")), request.capabilities(),
            ContractRef.of(OWNER, ResourceTypeId.of("resource.page")), null, null, false, null, null, null, null,
            null, request.sequence(), ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourcePageResponse(ResourceOperationKind.LIST, page));
    }

    private static Map<String, Object> assetPayload(JsonValue.JsonObject core, String type, String kind,
                                                    ResourceActivationState activationState, long revision,
                                                    UUID mutationId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, type);
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION, CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, revision);
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, mutationId.toString());
        fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, activationState.wireName());
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, kind);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION, CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        core.fields().forEach((key, value) -> fields.put(key, value.toJava()));
        fields.put(CoreGraphResourceProjection.ASSET_HASH,
            new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields)).canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return new LinkedHashMap<>(result);
    }

    private static JsonObject genericCapabilities() {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", SERVER.canonicalText());
        root.addProperty("authorityEpoch", 1L);
        root.addProperty("catalogPublicationKey",
            new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current()).canonicalText());
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject authority = new JsonObject();
        authority.addProperty("supported", true);
        authority.addProperty("durable", true);
        protocol.add("mutationAuthority", authority);
        JsonObject operations = new JsonObject();
        operations.add("read", array("list", "query", "load"));
        operations.add("mutate", array("create", "save", "delete", "activate"));
        protocol.add("resourceOperations", operations);
        JsonObject version = new JsonObject();
        version.addProperty("generation", 1);
        version.addProperty("minor", 1);
        protocol.add("resourceContractVersion", version);
        protocol.add("resourceCapabilities", array("resource_activation"));
        root.add("protocolEnvelope", protocol);
        return root;
    }

    private static JsonArray array(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static boolean legacyGraphPacket(ReSyncDecodedFrame frame) {
        if (frame.messageType() != ReSyncProtocolContract.MESSAGE_DATA
            || frame.channel() != ReSyncProtocolContract.CHANNEL_FLOW_ID || frame.payload().length == 0) {
            return false;
        }
        byte packet = frame.payload()[0];
        return packet == ReSyncProtocolContract.FLOW_PACKET_REQUEST
            || packet == ReSyncProtocolContract.FLOW_PACKET_SAVE
            || packet == ReSyncProtocolContract.FLOW_PACKET_DELETE
            || packet == ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST;
    }

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;

        private Probe() {
            super(null);
            manager = new FlowManager(this, null) {
                @Override
                public ServerConnectionToken captureServerConnectionToken(String serverId, ReSyncFlowClient source) {
                    return new ServerConnectionToken(serverId, redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(source, 1L));
                }

                @Override
                public boolean isCurrentServerConnection(ServerConnectionToken token) {
                    return token != null && token.source() != null && token.source().isConnectedState();
                }

                @Override
                public boolean runIfCurrentServerConnection(ServerConnectionToken token, Runnable action) {
                    if (!isCurrentServerConnection(token) || action == null) {
                        return false;
                    }
                    action.run();
                    return true;
                }
            };
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class CapturingTransport implements ReSyncFrameTransport {
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private final List<ReSyncDecodedFrame> frames = new ArrayList<>();
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            frameHandler = handler;
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public synchronized void send(byte[] frame) {
            frames.add(codec.decode(frame, null));
        }

        @Override
        public void close() {
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        private void receiveHandshake(String capabilities) {
            byte[] capabilityBytes = capabilities.getBytes(StandardCharsets.UTF_8);
            ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 7 + capabilityBytes.length);
            payload.put((byte) 1);
            payload.putInt(0);
            payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
            payload.putInt(0);
            payload.putInt(0);
            payload.putInt(0);
            payload.putInt(0);
            payload.putInt(capabilityBytes.length);
            payload.put(capabilityBytes);
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE, payload.array(), (short) 0, 1));
        }

        private void receiveEnvelope(byte[] payload) {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, payload,
                ReSyncProtocolContract.CHANNEL_CONTROL_ID, 2));
        }

        private ProtocolEnvelope<Map<String, Object>> requestEnvelope(ResourceOperationKind operation, String id,
                                                                       String cursor) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                synchronized (this) {
                    for (int index = frames.size() - 1; index >= 0; index--) {
                        ReSyncDecodedFrame frame = frames.get(index);
                        if (frame.messageType() != ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                            continue;
                        }
                        ProtocolEnvelope<Map<String, Object>> envelope = CODEC.decodeBytes(frame.payload());
                        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)
                            || request.operation().kind() != operation) {
                            continue;
                        }
                        if (id != null && (envelope.resource() == null || !id.equals(envelope.resource().id()))) {
                            continue;
                        }
                        if (cursor != null && (!(request.operation() instanceof ResourceListRequest list)
                            || !cursor.equals(list.cursor()))) {
                            continue;
                        }
                        return envelope;
                    }
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            throw new IllegalStateException("No matching Core graph envelope was sent");
        }

        private synchronized int protocolEnvelopeCount() {
            return (int) frames.stream().filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE).count();
        }

        private synchronized List<ReSyncDecodedFrame> frames() {
            return List.copyOf(frames);
        }
    }
}
