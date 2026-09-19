package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.flow.data.FlowGraph;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
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
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientCoreReceiveTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("98989898-9898-4989-8989-989898989898"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final UUID MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MESSAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID TRACE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final ContractRef<CapabilityId> RESOURCES = ContractRef.of(OWNER, CapabilityId.of("resources"));
    private static final ContractRef<CapabilityId> RESOURCE_REVISIONS =
        ContractRef.of(OWNER, CapabilityId.of("resource_revisions"));
    @TempDir
    static Path temporaryDirectory;

    @Test
    void receivesCoreDocumentOnlyAfterAuthentication() throws Exception {
        ReSyncFlowClient client = newClient();
        try {
            GraphDocument graph = graph("flow", "event-flow", 4, Set.of());
            ResourceDocument<Map<String, Object>> document = liveDocument(graph, ResourceActivationState.ACTIVE, MUTATION);
            ProtocolEnvelope<Map<String, Object>> event = documentEnvelope(ProtocolEnvelope.Kind.EVENT,
                ProtocolEnvelope.Status.OK, ResourceOperationKind.LOAD, document, null, Set.of(RESOURCES));

            invokeReceive(client, event);
            assertTrue(client.coreGraphResourceCache().state(document.resource()).isEmpty());

            authenticate(client);
            invokeReceive(client, event);

            GraphResourceState state = client.coreGraphResourceCache().state(document.resource()).orElseThrow();
            assertEquals(graph.checksum(), state.graphDocument().checksum());
            assertEquals(4L, state.revision());
            assertEquals(ResourceActivationState.ACTIVE, state.activationState());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void rejectsUnmarkedCoreGraphDocumentBeforeLegacyDeserialization() throws Exception {
        Probe probe = new Probe();
        ReSyncFlowClient client = newClient(probe);
        try {
            authenticate(client);
            FlowGraph legacyGraph = new FlowGraph();
            legacyGraph.setId("unmarked-flow");
            legacyGraph.setResourceType("flow");
            legacyGraph.setResourceRevision(4L);
            Map<String, Object> payload = new Gson().fromJson(new Gson().toJson(legacyGraph), Map.class);
            ServerResourceLocator resource = resource("flow", "unmarked-flow");
            ResourceDocument<Map<String, Object>> document = ResourceDocument.live(resource, 4L, MUTATION,
                ResourcePayloadCodecs.json().canonicalize(payload), ResourceActivationState.ACTIVE, "server");

            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.LOAD, document, null, Set.of(RESOURCES)));

            assertTrue(client.coreGraphResourceCache().state(resource).isEmpty());
            assertNull(probe.manager.getGraph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "unmarked-flow"));
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void receivesOnlyBoundTypeScopedCorePagesAndRetainsTombstones() throws Exception {
        ReSyncFlowClient client = newClient();
        List<ReSyncFlowClient.CoreGraphResourceTransition> transitions = new CopyOnWriteArrayList<>();
        List<ReSyncFlowClient.CoreGraphListSnapshot> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch transitionDelivery = new CountDownLatch(3);
        CountDownLatch snapshotDelivery = new CountDownLatch(3);
        client.addCoreGraphResourceListener(transition -> {
            transitions.add(transition);
            transitionDelivery.countDown();
        });
        client.addCoreGraphListListener(snapshot -> {
            snapshots.add(snapshot);
            snapshotDelivery.countDown();
        });
        try {
            authenticate(client);
            GraphDocument flow = graph("flow", "page-flow", 7, Set.of());
            FunctionSourceDocument function = function("function", "page-function", 8);
            ResourceDocument<Map<String, Object>> flowDocument = liveDocument(flow, ResourceActivationState.ACTIVE, MUTATION);
            ResourceDocument<Map<String, Object>> functionDocument = liveDocument(function, ResourceActivationState.ACTIVE, MUTATION);
            ServerResourceLocator commandResource = resource("command", "page-command");
            ResourceDocument<Map<String, Object>> tombstone = ResourceDocument.tombstone(commandResource, 9, MUTATION,
                new ContentHash("d".repeat(64)), "server");

            bindCorePageRequest(client, ReSyncResourceType.FLOW);
            invokeReceive(client, pageEnvelope(new ResourcePage<>(List.of(functionDocument), null, true)));
            assertTrue(client.pendingCoreGraphRequest(REQUEST).isEmpty());
            assertTrue(client.coreGraphResourceCache().state(function.graph().resource()).isEmpty());
            assertTrue(snapshots.isEmpty());

            bindCorePageRequest(client, ReSyncResourceType.FLOW);
            ProtocolEnvelope<Map<String, Object>> flowPage = pageEnvelope(new ResourcePage<>(List.of(flowDocument), null, true));
            invokeReceive(client, flowPage);
            invokeReceive(client, flowPage);
            bindCorePageRequest(client, ReSyncResourceType.FUNCTION);
            invokeReceive(client, pageEnvelope(new ResourcePage<>(List.of(functionDocument), null, true)));
            bindCorePageRequest(client, ReSyncResourceType.COMMAND);
            invokeReceive(client, pageEnvelope(new ResourcePage<>(List.of(tombstone), null, true)));
            assertTrue(transitionDelivery.await(2L, TimeUnit.SECONDS));
            assertTrue(snapshotDelivery.await(2L, TimeUnit.SECONDS));

            assertEquals(flow.checksum(), client.coreGraphResourceCache().state(flow.resource()).orElseThrow()
                .graphDocument().checksum());
            assertEquals(function.checksum(), client.coreGraphResourceCache().state(function.graph().resource()).orElseThrow()
                .functionSourceDocument().checksum());
            assertTrue(client.coreGraphResourceCache().state(commandResource).orElseThrow().tombstone());
            ReSyncFlowClient.CoreResourcePageCursor cursor = client.coreResourcePageCursor(CORRELATION).orElseThrow();
            assertEquals(ResourceOperationKind.LIST, cursor.operation());
            assertEquals(ReSyncResourceType.COMMAND, cursor.type());
            assertNull(cursor.nextCursor());
            assertTrue(cursor.complete());
            assertEquals(Set.of(commandResource), cursor.resources());
            assertEquals(Set.of(commandResource), cursor.tombstones());
            assertEquals(3, transitions.size());
            assertEquals(Set.of(flow.resource(), function.graph().resource(), commandResource), transitions.stream()
                .map(ReSyncFlowClient.CoreGraphResourceTransition::resource)
                .collect(Collectors.toSet()));
            assertEquals(3, snapshots.size());
            assertEquals(List.of(ReSyncResourceType.FLOW, ReSyncResourceType.FUNCTION, ReSyncResourceType.COMMAND),
                snapshots.stream().map(ReSyncFlowClient.CoreGraphListSnapshot::type).toList());
            assertTrue(snapshots.getLast().liveResources().isEmpty());
            assertEquals(Set.of(commandResource), snapshots.getLast().tombstones());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void keepsReadOnlySidecarAndDraftWhenCoreDocumentRequiresUnknownCapability() throws Exception {
        ReSyncFlowClient client = newClient();
        try {
            authenticate(client);
            ContractRef<CapabilityId> futureCapability = ContractRef.of(new OwnerId("future.extension"),
                CapabilityId.of("graph-edit"));
            GraphDocument draftGraph = graph("flow", "read-only-flow", 3, Set.of(futureCapability));
            client.coreGraphResourceCache().putDraft(new GraphDraft(draftGraph.resource(), 3, draftGraph));
            GraphDocument serverGraph = graph("flow", "read-only-flow", 4, Set.of(futureCapability));
            ResourceDocument<Map<String, Object>> document = liveDocument(serverGraph, ResourceActivationState.ACTIVE,
                MUTATION);

            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.LOAD, document, null, Set.of(RESOURCES)));

            assertTrue(client.coreGraphResourceCache().state(document.resource()).isEmpty());
            assertTrue(client.coreGraphResourceCache().readOnly(document.resource()).isPresent());
            assertTrue(client.coreGraphResourceCache().draft(document.resource()).isPresent());
            assertEquals(4L, client.coreGraphResourceCache().readOnly(document.resource()).orElseThrow().revision());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void notifiesCoreTransitionsForLiveReadOnlyAndTombstoneDocuments() throws Exception {
        ReSyncFlowClient client = newClient();
        List<ReSyncFlowClient.CoreGraphResourceTransition> transitions = new CopyOnWriteArrayList<>();
        CountDownLatch delivery = new CountDownLatch(3);
        client.addCoreGraphResourceListener(transition -> {
            transitions.add(transition);
            delivery.countDown();
        });
        try {
            authenticate(client);
            GraphDocument liveGraph = graph("flow", "callback-flow", 1, Set.of());
            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.LOAD, liveDocument(liveGraph, ResourceActivationState.ACTIVE, MUTATION), null,
                Set.of(RESOURCES)));

            ContractRef<CapabilityId> futureCapability = ContractRef.of(new OwnerId("future.extension"),
                CapabilityId.of("graph-edit"));
            GraphDocument readOnlyGraph = graph("flow", "callback-flow", 2, Set.of(futureCapability));
            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.LOAD, liveDocument(readOnlyGraph, ResourceActivationState.ACTIVE, MUTATION), null,
                Set.of(RESOURCES)));

            ServerResourceLocator tombstoneResource = resource("command", "callback-command");
            ResourceDocument<Map<String, Object>> tombstone = ResourceDocument.tombstone(tombstoneResource, 3,
                MUTATION, new ContentHash("d".repeat(64)), "server");
            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.DELETE, tombstone, null, Set.of(RESOURCES)));
            assertTrue(delivery.await(2L, TimeUnit.SECONDS));

            assertEquals(3, transitions.size());
            assertEquals(ReSyncResourceType.FLOW, transitions.get(0).type());
            assertTrue(transitions.get(0).authoritative());
            assertFalse(transitions.get(0).readOnly());
            assertFalse(transitions.get(0).tombstoned());
            assertTrue(transitions.get(1).readOnly());
            assertFalse(transitions.get(1).authoritative());
            assertEquals(liveGraph.resource(), transitions.get(0).resource());
            assertEquals(readOnlyGraph.resource(), transitions.get(1).resource());
            assertTrue(transitions.get(2).tombstoned());
            assertTrue(transitions.get(2).authoritative());
            assertTrue(transitions.get(2).state().tombstone());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void notifiesOnlyValidatedBoundAckAndConflictApplications() throws Exception {
        ReSyncFlowClient client = newClient();
        List<ReSyncFlowClient.CoreGraphResourceTransition> transitions = new CopyOnWriteArrayList<>();
        client.addCoreGraphResourceListener(transitions::add);
        try {
            authenticate(client);
            GraphDocument acknowledged = graph("flow", "ack-flow", 1, Set.of());
            ResourceDocument<Map<String, Object>> acknowledgedDocument = liveDocument(acknowledged,
                ResourceActivationState.ACTIVE, MUTATION);
            bindCoreRequest(client, ResourceOperationKind.LOAD, acknowledged.resource(), null, 0L);
            ProtocolEnvelope<Map<String, Object>> acknowledgedEnvelope = documentEnvelope(ProtocolEnvelope.Kind.ACK,
                ProtocolEnvelope.Status.OK, ResourceOperationKind.LOAD, acknowledgedDocument, REQUEST,
                Set.of(RESOURCES));
            invokeReceive(client, acknowledgedEnvelope);
            invokeReceive(client, acknowledgedEnvelope);

            GraphDocument current = graph("flow", "conflict-flow", 2, Set.of());
            ResourceDocument<Map<String, Object>> currentDocument = liveDocument(current,
                ResourceActivationState.ACTIVE, MUTATION);
            bindCoreRequest(client, ResourceOperationKind.SAVE, current.resource(), MUTATION, 1L);
            invokeReceive(client, conflictEnvelope(currentDocument));

            assertEquals(2, transitions.size());
            assertEquals(acknowledged.resource(), transitions.get(0).resource());
            assertEquals(current.resource(), transitions.get(1).resource());
            assertEquals(1L, transitions.get(0).state().revision());
            assertEquals(2L, transitions.get(1).state().revision());
            assertEquals(ResourceActivationState.ACTIVE, transitions.get(1).state().activationState());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void acceptedCoreOpenWaitsForCurrentSessionWithoutRetainingRequestIds() throws Exception {
        CoreOpenProbe probe = new CoreOpenProbe();
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = probe.manager.activateLiveReSyncSession(new ReSyncLiveServerSession(
            SERVER.canonicalText(), "Server", transport));
        try {
            assertTrue(client != null);
            authenticate(client, transport);
            GraphDocument graph = graph("flow", "deferred-open", 1, Set.of());
            ResourceDocument<Map<String, Object>> document = liveDocument(graph, ResourceActivationState.ACTIVE, MUTATION);
            ProtocolEnvelope<Map<String, Object>> envelope = documentEnvelope(ProtocolEnvelope.Kind.ACK,
                ProtocolEnvelope.Status.OK, ResourceOperationKind.LOAD, document, REQUEST, Set.of(RESOURCES));
            bindCoreRequest(client, ResourceOperationKind.LOAD, graph.resource(), null, 0L);
            rememberOpenIntent(client, ReSyncResourceType.FLOW, graph.resource().id());
            pendingCoreOpenRequests(client).add(REQUEST);

            invokeReceive(client, envelope);

            assertTrue(probe.manager.sessionLookups.get() >= 1);
            assertTrue(client.pendingCoreGraphRequest(REQUEST).isEmpty());
            assertTrue(pendingCoreOpenRequests(client).isEmpty());
            assertEquals(1, acceptedCoreOpenIntents(client).size());
            assertEquals(1, pendingOpenIntents(client).size());
            assertEquals(0, probe.manager.opened.get());
            probe.manager.allowSession();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (probe.manager.openedLatch.getCount() > 0L && System.nanoTime() < deadline) {
                drainConnectionCallbacks(client);
                ScreenManager.getInstance().processTasks();
                client.coreGraphSessionProjected(graph.resource());
                Thread.onSpinWait();
            }
            assertEquals(0L, probe.manager.openedLatch.getCount());
            client.coreGraphSessionProjected(graph.resource());
            client.coreGraphSessionProjected(graph.resource());

            assertEquals(1, probe.manager.opened.get());
            assertTrue(probe.manager.sessionLookups.get() >= 2);
            assertEquals(graph.resource(), probe.manager.openedSession.resource());
            assertTrue(acceptedCoreOpenIntents(client).isEmpty());
            assertTrue(pendingOpenIntents(client).isEmpty());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void listenerFailureDoesNotCorruptCoreReceiveAndSubscriptionCanClose() throws Exception {
        ReSyncFlowClient client = newClient();
        AtomicInteger calls = new AtomicInteger();
        ReSyncFlowClient.CoreGraphResourceSubscription subscription = client.subscribeCoreGraphResource(transition -> {
            calls.incrementAndGet();
            throw new IllegalStateException("listener failure");
        });
        try {
            authenticate(client);
            GraphDocument first = graph("flow", "listener-flow", 1, Set.of());
            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.LOAD, liveDocument(first, ResourceActivationState.ACTIVE, MUTATION), null,
                Set.of(RESOURCES)));
            assertTrue(client.coreGraphResourceCache().state(first.resource()).isPresent());
            drainConnectionCallbacks(client);
            assertEquals(1, calls.get());

            subscription.close();
            GraphDocument second = graph("flow", "listener-flow", 2, Set.of());
            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.LOAD, liveDocument(second, ResourceActivationState.ACTIVE, MUTATION), null,
                Set.of(RESOURCES)));
            drainConnectionCallbacks(client);
            assertEquals(1, calls.get());
            assertEquals(2L, client.coreGraphResourceCache().state(second.resource()).orElseThrow().revision());
        } finally {
            subscription.close();
            client.shutdown();
        }
    }

    @Test
    void ignoresCoreConflictWithoutCurrentWhenNoMatchingRequestExists() throws Exception {
        ReSyncFlowClient client = newClient();
        try {
            authenticate(client);
            GraphDocument graph = graph("flow", "conflict-flow", 5, Set.of());
            ResourceDocument<Map<String, Object>> document = liveDocument(graph, ResourceActivationState.ACTIVE, MUTATION);
            invokeReceive(client, documentEnvelope(ProtocolEnvelope.Kind.EVENT, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.LOAD, document, null, Set.of(RESOURCES)));
            GraphResourceState before = client.coreGraphResourceCache().state(document.resource()).orElseThrow();

            ProtocolEnvelope<Map<String, Object>> conflict = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.CONFLICT,
                new CatalogVersion(1, 0), MESSAGE, REQUEST, CORRELATION, TRACE, SERVER, document.resource(),
                0L, 1L, null, ContractRef.of(OWNER, OperationId.of("resource.save")),
                Set.of(RESOURCES, RESOURCE_REVISIONS), ContractRef.of(OWNER, ResourceTypeId.of("resource.document")),
                null, null, false, null, null, null, null, null, 1L,
                ProtocolEnvelope.Status.CONFLICT, List.of(), Map.of(), new ProtocolBody.ConflictResponse(document.resource(), null));

            invokeReceive(client, conflict);

            GraphResourceState after = client.coreGraphResourceCache().state(document.resource()).orElseThrow();
            assertEquals(before.revision(), after.revision());
            assertEquals(before.mutationId(), after.mutationId());
            assertEquals(before.graphDocument().checksum(), after.graphDocument().checksum());
        } finally {
            client.shutdown();
        }
    }

    private static ReSyncFlowClient newClient() throws Exception {
        return newClient(null);
    }

    private static ReSyncFlowClient newClient(RemotelyClient owner) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        TestClient client = new TestClient(transport, owner);
        if (owner != null && owner.getFlowManager() != null) {
            FlowManagerTestConnection.installCurrent(owner.getFlowManager(), SERVER.canonicalText(), client, transport);
        }
        return client;
    }

    private static void authenticate(ReSyncFlowClient client) throws Exception {
        if (client instanceof TestClient testClient) {
            testClient.establish();
            assertTypedAuthority(client);
            return;
        }
        throw new IllegalArgumentException("Core receive fixture transport is required");
    }

    private static void authenticate(ReSyncFlowClient client, ScriptedReSyncTransport transport) throws Exception {
        ReSyncFlowClientTestHarness.establish(client, transport, publication());
        assertTypedAuthority(client);
    }

    private static void assertTypedAuthority(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("authenticated");
        field.setAccessible(true);
        assertTrue(((AtomicBoolean) field.get(client)).get());
        Method authorityReady = ReSyncFlowClient.class.getDeclaredMethod("typedMutationAuthorityReady");
        authorityReady.setAccessible(true);
        assertTrue((Boolean) authorityReady.invoke(client));
        assertEquals(BINDING, client.activeCatalogAuthoringPublication().orElseThrow().binding());
    }

    private static CatalogCachePublication publication() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), List.of(
                authoringSection(CatalogAuthoringPublication.Section.TYPES),
                authoringSection(CatalogAuthoringPublication.Section.EDITORS),
                authoringSection(CatalogAuthoringPublication.Section.PREVIEWS),
                authoringSection(CatalogAuthoringPublication.Section.CAPABILITIES)), Set.of());
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, BINDING, 1L, List.of(), authoring,
            Map.of());
    }

    private static CatalogAuthoringPublication.SectionProjection authoringSection(
        CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE,
            List.of());
    }

    private static void invokeReceive(ReSyncFlowClient client, ProtocolEnvelope<Map<String, Object>> envelope)
        throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("handleProtocolEnvelope", byte[].class);
        method.setAccessible(true);
        method.invoke(client, (Object) new ProtocolEnvelopeCodec<Map<String, Object>>(ResourcePayloadCodecs.json())
            .encodeBytes(envelope));
    }

    private static void bindCoreRequest(ReSyncFlowClient client, ResourceOperationKind operation,
                                        ServerResourceLocator resource, UUID mutationId, long expectedRevision)
        throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("coreRequest", UUID.class, ReSyncResourceType.class,
            ResourceOperationKind.class, ServerResourceLocator.class, long.class, UUID.class, String.class,
            String.class, UUID.class, UUID.class, ContentHash.class);
        method.setAccessible(true);
        method.invoke(client, REQUEST, ReSyncResourceType.byTypeId(resource.resourceType().value()), operation,
            resource, expectedRevision, mutationId, null, null, CORRELATION, TRACE, null);
    }

    private static void bindCorePageRequest(ReSyncFlowClient client, ReSyncResourceType type) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("coreRequest", UUID.class, ReSyncResourceType.class,
            ResourceOperationKind.class, ServerResourceLocator.class, long.class, UUID.class, String.class,
            String.class, UUID.class, UUID.class, ContentHash.class);
        method.setAccessible(true);
        method.invoke(client, REQUEST, type, ResourceOperationKind.LIST, null, 0L, null, null, null,
            CORRELATION, TRACE, null);
        Class<?> keyType = Class.forName(ReSyncFlowClient.class.getName() + "$CoreListKey");
        var constructor = keyType.getDeclaredConstructor(ReSyncResourceType.class, String.class);
        constructor.setAccessible(true);
        Object key = constructor.newInstance(type, null);
        Field field = ReSyncFlowClient.class.getDeclaredField("activeCoreListCorrelations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, UUID> correlations = (Map<Object, UUID>) field.get(client);
        correlations.put(key, CORRELATION);
    }

    private static void drainConnectionCallbacks(ReSyncFlowClient client) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("submitConnectionCallbackDrain");
        method.setAccessible(true);
        CompletableFuture<?> completion = (CompletableFuture<?>) method.invoke(client);
        completion.get(2L, TimeUnit.SECONDS);
    }

    private static void rememberOpenIntent(ReSyncFlowClient client, ReSyncResourceType type, String id) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("rememberOpenIntent", ReSyncResourceType.class,
            String.class);
        method.setAccessible(true);
        method.invoke(client, type, id);
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> pendingCoreOpenRequests(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingCoreOpenRequests");
        field.setAccessible(true);
        return (Set<UUID>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> acceptedCoreOpenIntents(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("acceptedCoreOpenIntents");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> pendingOpenIntents(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingOpenIntents");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(client);
    }

    private static ProtocolEnvelope<Map<String, Object>> conflictEnvelope(
        ResourceDocument<Map<String, Object>> current) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.CONFLICT, new CatalogVersion(1, 0), MESSAGE, REQUEST,
            CORRELATION, TRACE, SERVER, current.resource(), current.revision(), 1L, current.mutationId(),
            ContractRef.of(OWNER, OperationId.of("resource.save")), Set.of(RESOURCES, RESOURCE_REVISIONS),
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, current.payloadHash(),
            current.deleted(), null, null, null, null, null, 1L, ProtocolEnvelope.Status.CONFLICT, List.of(), Map.of(),
            new ProtocolBody.ConflictResponse(current.resource(), current));
    }

    private static ProtocolEnvelope<Map<String, Object>> documentEnvelope(ProtocolEnvelope.Kind kind,
                                                                           ProtocolEnvelope.Status status,
                                                                           ResourceOperationKind operation,
                                                                           ResourceDocument<Map<String, Object>> document,
                                                                           UUID requestId,
                                                                           Set<ContractRef<CapabilityId>> capabilities) {
        return new ProtocolEnvelope<>(kind, new CatalogVersion(1, 0), MESSAGE, requestId, CORRELATION, TRACE, SERVER,
            document.resource(), document.revision(), 1L, document.mutationId(), ContractRef.of(OWNER,
            OperationId.of("resource." + operation.name().toLowerCase(Locale.ROOT))), capabilities,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(),
            document.deleted(), null, null, null, null, null, 1L, status, List.of(), Map.of(),
            new ProtocolBody.ResourceDocumentResponse(operation, document));
    }

    private static ProtocolEnvelope<Map<String, Object>> pageEnvelope(ResourcePage<Map<String, Object>> page) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, new CatalogVersion(1, 0), MESSAGE, REQUEST,
            CORRELATION, TRACE, SERVER, null, 0L, 1L, null, ContractRef.of(OWNER, OperationId.of("resource.list")),
            Set.of(RESOURCES), ContractRef.of(OWNER, ResourceTypeId.of("resource.page")), null, null, false,
            null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourcePageResponse(ResourceOperationKind.LIST, page));
    }

    private static ResourceDocument<Map<String, Object>> liveDocument(GraphDocument graph,
                                                                        ResourceActivationState activationState,
                                                                        UUID mutationId) {
        Map<String, Object> payload = assetPayload(GraphDocumentCodec.INSTANCE.encode(graph),
            graph.resource().resourceType().value(), CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND,
            activationState, graph.revision(), mutationId);
        return ResourceDocument.live(graph.resource(), graph.revision(), mutationId,
            ResourcePayloadCodecs.json().canonicalize(payload), activationState, "server");
    }

    private static ResourceDocument<Map<String, Object>> liveDocument(FunctionSourceDocument function,
                                                                        ResourceActivationState activationState,
                                                                        UUID mutationId) {
        GraphDocument graph = function.graph();
        Map<String, Object> payload = assetPayload(FunctionSourceDocumentCodec.INSTANCE.encode(function),
            graph.resource().resourceType().value(), CoreGraphResourceProjection.FUNCTION_SOURCE_KIND,
            activationState, graph.revision(), mutationId);
        return ResourceDocument.live(graph.resource(), graph.revision(), mutationId,
            ResourcePayloadCodecs.json().canonicalize(payload), activationState, "server");
    }

    private static Map<String, Object> assetPayload(JsonValue.JsonObject core, String resourceType, String kind,
                                                    ResourceActivationState activationState, long revision,
                                                    UUID mutationId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, resourceType);
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION,
            CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, revision);
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, mutationId.toString());
        fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, activationState.wireName());
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, kind);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION,
            CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        core.fields().forEach((key, value) -> fields.put(key, value.toJava()));
        ContentHash hash = new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields));
        fields.put(CoreGraphResourceProjection.ASSET_HASH, hash.canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return new LinkedHashMap<>(result);
    }

    private static GraphDocument graph(String type, String id, long revision,
                                       Set<ContractRef<CapabilityId>> requiredCapabilities) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING,
            requiredCapabilities, List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static FunctionSourceDocument function(String type, String id, long revision) {
        GraphDocument graph = graph(type, id, revision, Set.of());
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(graph.resource()),
            new FunctionRevision(revision), List.of(), List.of(), Map.of());
        return new FunctionSourceDocument(signature, graph, OpaqueData.empty());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static final class TestClient extends ReSyncFlowClient {
        private final ScriptedReSyncTransport transport;

        private TestClient(ScriptedReSyncTransport transport, RemotelyClient owner) {
            super(SERVER.canonicalText(), transport, owner, new ReSyncCatalogPublicationCache(DesktopReSyncStorage.fromKey(temporaryDirectory.resolve(UUID.randomUUID().toString() + ".json"))));
            this.transport = transport;
        }

        private void establish() throws Exception {
            ReSyncFlowClientTestHarness.establish(this, transport, publication());
        }
    }

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;

        private Probe() {
            super(null);
            manager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class CoreOpenProbe extends RemotelyClient {
        private final CoreOpenManager manager;

        private CoreOpenProbe() {
            super(null);
            manager = new CoreOpenManager(this);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class CoreOpenManager extends FlowManager {
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicInteger sessionLookups = new AtomicInteger();
        private final AtomicBoolean sessionReady = new AtomicBoolean();
        private final CountDownLatch openedLatch = new CountDownLatch(1);
        private CoreGraphEditorSession openedSession;

        private CoreOpenManager(RemotelyClient client) {
            super(client, null);
        }

        @Override
        public Optional<CoreGraphEditorSession> coreGraphEditorSession(String serverId, ReSyncResourceType type,
                                                                       String id) {
            sessionLookups.incrementAndGet();
            if (!sessionReady.get()) {
                return Optional.empty();
            }
            return super.coreGraphEditorSession(serverId, type, id);
        }

        private void allowSession() {
            sessionReady.set(true);
        }

        @Override
        public boolean openCoreGraphSession(ServerResourceLocator resource, CoreGraphEditorSession candidate,
                                            String title, String branchPin) {
            openedSession = candidate;
            opened.incrementAndGet();
            openedLatch.countDown();
            return true;
        }
    }

}
