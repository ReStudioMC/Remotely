package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerCoreQueryPerformanceTest {
    private static final ServerId SERVER = ServerId.deterministic("core-query-performance");
    private static final CatalogBinding BINDING = new CatalogBinding(54L, "5".repeat(64), "4".repeat(64));
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("core-query-node");
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("command")), "requested");

    @Test
    void unchangedFrameQueriesNeverEncodeTheCatalogOrBaselineAndPreserveDirtyDrafts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CatalogAuthoringPublication publication = fixture.client.activeAuthoringPublication().orElseThrow();
            Reads<?> catalog = countReads(publication, "sections");
            Reads<?> baseline = countReads(fixture.graph, "nodes");
            CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
            fixture.graph.checksum();
            assertTrue(catalog.reads > 0);
            assertTrue(baseline.reads > 0);
            catalog.reads = 0;
            baseline.reads = 0;
            assertTrue(fixture.current());
            Object state = fixture.state();

            fixture.queryFrames(false);

            assertEquals(0, catalog.reads);
            assertEquals(0, baseline.reads);
            assertSame(state, fixture.state());
            fixture.session.setNodePosition(NODE, 12.0, 24.0);
            baseline.reads = 0;

            fixture.queryFrames(true);

            assertEquals(0, catalog.reads);
            assertEquals(0, baseline.reads);
            assertSame(state, fixture.state());
            assertSame(fixture.graph, fixture.session.baselineGraphDocument());
            assertEquals(12.0, fixture.session.graphDocument().nodes().getFirst().x());
        }
    }

    @Test
    void bindingOptionQueriesUseThePublishedCacheWithoutReadingGraphPayloads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Reads<?> baseline = countReads(fixture.graph, "nodes");
            fixture.graph.checksum();
            assertTrue(baseline.reads > 0);
            baseline.reads = 0;

            assertEquals(List.of(RESOURCE.id()), fixture.manager.getCachedGraphIdsForServer(
                SERVER.canonicalText(), ReSyncResourceType.COMMAND));

            assertEquals(0, baseline.reads);
        }
    }

    @Test
    void changedPublicationAndMidQueryRolloverFailClosedWithoutReplacingTheDraft() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.session.setNodePosition(NODE, 12.0, 24.0);
            ReSyncCatalogAuthoringProjection.Snapshot original = fixture.client.catalogAuthoringProjection().active().orElseThrow();
            publish(fixture.client, publication(Map.of("changed", true)), 2L);
            ReSyncCatalogAuthoringProjection.Snapshot replacement = fixture.client.catalogAuthoringProjection().active().orElseThrow();

            assertFalse(fixture.current());
            assertTrue(fixture.dirty());
            assertSame(fixture.session, fixture.storedSession());

            fixture.client.catalogAuthoringProjection().restorePrepared(original);
            assertTrue(fixture.current());
            fixture.client.publicationReads = 0;
            fixture.client.rollover = () -> fixture.client.catalogAuthoringProjection().restorePrepared(replacement);
            fixture.client.rolloverRead = 3;

            assertFalse(fixture.current());
            assertSame(fixture.session, fixture.storedSession());
            assertEquals(12.0, fixture.session.graphDocument().nodes().getFirst().x());
        }
    }

    @Test
    void connectionReplacementRejectsTheOldTokenAndRevalidatesTheSameDraft() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.session.setNodePosition(NODE, 12.0, 24.0);
            CoreGraphEditorSession.HistoryState history = fixture.session.historyState();
            FlowManager.ServerConnectionToken old = fixture.manager.captureServerConnectionToken(SERVER.canonicalText());
            TestClient replacement = new TestClient();
            try {
                publish(replacement, publication(Map.of()), 1L);
                FlowManagerTestConnection.install(fixture.manager, SERVER.canonicalText(), replacement, replacement.transport);
                assertFalse(fixture.manager.isCurrentServerConnection(old));
                assertTrue(fixture.manager.peekCoreGraphEditorSession(SERVER.canonicalText(),
                    ReSyncResourceType.COMMAND, RESOURCE.id()).isEmpty());

                attach(fixture.manager, replacement);
                replacement.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 2L);
                GraphDocument decoded = graph(0.0);
                load(fixture.manager, replacement, decoded);

                assertSame(decoded, projectedPayload(fixture.manager, ReSyncResourceType.COMMAND, RESOURCE.id()));
                assertTrue(fixture.current());
                assertTrue(fixture.dirty());
                assertSame(fixture.session, fixture.storedSession());
                assertSame(fixture.graph, fixture.session.baselineGraphDocument());
                assertEquals(history, fixture.session.historyState());
                assertEquals(12.0, fixture.session.graphDocument().nodes().getFirst().x());
                Reads<?> previousReads = countReads(fixture.graph, "nodes");
                Reads<?> decodedReads = countReads(decoded, "nodes");
                fixture.graph.checksum();
                decoded.checksum();
                assertTrue(previousReads.reads > 0);
                assertTrue(decodedReads.reads > 0);
                previousReads.reads = 0;
                decodedReads.reads = 0;

                fixture.queryFrames(true);

                assertEquals(0, previousReads.reads);
                assertEquals(0, decodedReads.reads);
                assertEquals(history, fixture.session.historyState());
            } finally {
                replacement.shutdown();
            }
        }
    }

    @Test
    void differentSameRevisionBaselineStillRequiresExactContent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CoreGraphUiProjection projection = new CoreGraphUiProjection();
            GraphDocument changed = graph(999.0);
            projection.apply(ReSyncResourceType.COMMAND, state(changed), false, 1L);
            CoreGraphUiProjection.Baseline baseline = projection.baseline(SERVER.canonicalText(),
                ReSyncResourceType.COMMAND, RESOURCE.id()).orElseThrow();
            Method matches = FlowManager.class.getDeclaredMethod("matchesCoreSessionBaseline",
                CoreGraphEditorSession.class, CoreGraphUiProjection.Baseline.class);
            matches.setAccessible(true);

            assertFalse((Boolean) matches.invoke(null, fixture.session, baseline));
            assertSame(fixture.graph, fixture.session.baselineGraphDocument());
        }
    }

    @Test
    void freshEqualFunctionBaselineIsVerifiedOnceWithoutResettingDirtyHistory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FunctionSourceDocument original = function();
            String id = original.graph().resource().id();
            load(fixture.manager, fixture.client, ReSyncResourceType.FUNCTION, functionState(original));
            CoreGraphEditorSession session = fixture.manager.coreGraphEditorSession(SERVER.canonicalText(),
                ReSyncResourceType.FUNCTION, id).orElseThrow();
            session.setNodePosition(NODE, 12.0, 24.0);
            CoreGraphEditorSession.HistoryState history = session.historyState();
            TestClient replacement = new TestClient();
            try {
                publish(replacement, publication(Map.of()), 1L);
                FlowManagerTestConnection.install(fixture.manager, SERVER.canonicalText(), replacement, replacement.transport);
                attach(fixture.manager, replacement);
                replacement.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 2L);
                FunctionSourceDocument decoded = function();
                load(fixture.manager, replacement, ReSyncResourceType.FUNCTION, functionState(decoded));
                assertSame(decoded, projectedPayload(fixture.manager, ReSyncResourceType.FUNCTION, id));
                assertTrue(fixture.manager.isCurrentCoreGraphEditorSession(SERVER.canonicalText(),
                    ReSyncResourceType.FUNCTION, id, session));
                assertSame(original, session.baselineFunctionSourceDocument());
                Reads<?> previousReads = countReads(original.graph(), "nodes");
                Reads<?> decodedReads = countReads(decoded.graph(), "nodes");
                original.checksum();
                decoded.checksum();
                assertTrue(previousReads.reads > 0);
                assertTrue(decodedReads.reads > 0);
                previousReads.reads = 0;
                decodedReads.reads = 0;

                for (int frame = 0; frame < 200; frame++) {
                    assertTrue(fixture.manager.isCurrentCoreGraphEditorSession(SERVER.canonicalText(),
                        ReSyncResourceType.FUNCTION, id, session));
                    assertTrue(fixture.manager.isCoreGraphEditorSessionDirty(SERVER.canonicalText(),
                        ReSyncResourceType.FUNCTION, id, session));
                    assertSame(session, fixture.manager.peekCoreGraphEditorSession(SERVER.canonicalText(),
                        ReSyncResourceType.FUNCTION, id).orElseThrow());
                }

                assertEquals(0, previousReads.reads);
                assertEquals(0, decodedReads.reads);
                assertEquals(history, session.historyState());
                assertEquals(12.0, session.functionSourceDocument().graph().nodes().getFirst().x());
            } finally {
                replacement.shutdown();
            }
        }
    }

    @Test
    void replacingTheSessionBaselineInvalidatesItsPreviousProof() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.current());
            fixture.session.markSaved(graph(999.0));

            assertTrue(fixture.manager.peekCoreGraphEditorSession(SERVER.canonicalText(),
                ReSyncResourceType.COMMAND, RESOURCE.id()).isEmpty());
            assertTrue(fixture.current());
            assertSame(fixture.graph, fixture.session.baselineGraphDocument());
        }
    }

    private static CatalogAuthoringPublication publication(Map<String, ?> unknown) {
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            section(CatalogAuthoringPublication.Section.TYPES), section(CatalogAuthoringPublication.Section.EDITORS),
            section(CatalogAuthoringPublication.Section.PREVIEWS), section(CatalogAuthoringPublication.Section.CAPABILITIES));
        return new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0), CatalogProjectionVersion.current(),
            sections, Set.of(), unknown);
    }

    private static CatalogAuthoringPublication.SectionProjection section(CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE, List.of());
    }

    private static void publish(TestClient client, CatalogAuthoringPublication authoring, long revision) {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            BINDING, revision, List.of(), authoring, Map.of());
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER,
            "core-query-performance", client.catalogPublicationProjection(), client.catalogAuthoringProjection(), null);
        handler.setAuthoringRequired(true);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending("core-query-performance",
            "core-query-owner", publication).dispatched().clientReceived(key, revision).receipt().orElseThrow();
        ReSyncCatalogPublicationReceiptHandler.Application applied = handler.apply(receipt, publication,
            codec.encodeBytes(publication));
        assertTrue(applied.applied(), applied.diagnostic());
    }

    private static GraphDocument graph(double x) {
        GraphNode node = new GraphNode(NODE, ContractRef.of(OwnerId.of("test"), NodeId.of("node")), 1, null,
            Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), x, 0.0, OpaqueData.empty());
        return new GraphDocument(RESOURCE, 1L, BINDING, List.of(node), List.of());
    }

    private static GraphResourceState state(GraphDocument graph) {
        return GraphResourceState.live(RESOURCE, 1L, UUID.randomUUID(), ContentHash.of("a".repeat(64)),
            ContentHash.of("b".repeat(64)), graph, ResourceActivationState.ACTIVE);
    }

    private static FunctionSourceDocument function() {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("function")), "requested-function");
        GraphDocument graph = new GraphDocument(resource, 1L, BINDING, graph(0.0).nodes(), List.of());
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(1L),
            List.of(), List.of());
        return new FunctionSourceDocument(signature, graph);
    }

    private static GraphResourceState functionState(FunctionSourceDocument source) {
        return new GraphResourceState(source.graph().resource(), 1L, UUID.randomUUID(), ContentHash.of("a".repeat(64)),
            ContentHash.of("b".repeat(64)), source, ResourceActivationState.ACTIVE);
    }

    private static void attach(FlowManager manager, TestClient client) throws Exception {
        Method method = FlowManager.class.getDeclaredMethod("attachCoreGraphListener", ReSyncFlowClient.class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(manager, client));
    }

    private static void load(FlowManager manager, TestClient client, GraphDocument graph) throws Exception {
        load(manager, client, ReSyncResourceType.COMMAND, state(graph));
    }

    private static void load(FlowManager manager, TestClient client, ReSyncResourceType type,
                             GraphResourceState state) throws Exception {
        Method method = FlowManager.class.getDeclaredMethod("applyHydratedCoreGraphState", ReSyncFlowClient.class,
            ReSyncResourceType.class, GraphResourceState.class);
        method.setAccessible(true);
        method.invoke(manager, client, type, state);
    }

    private static Object projectedPayload(FlowManager manager, ReSyncResourceType type, String id) throws Exception {
        Field field = FlowManager.class.getDeclaredField("coreGraphUiProjection");
        field.setAccessible(true);
        CoreGraphUiProjection projection = (CoreGraphUiProjection) field.get(manager);
        return projection.baseline(SERVER.canonicalText(), type, id).orElseThrow().payload();
    }

    @SuppressWarnings("unchecked")
    private static Reads<?> countReads(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Reads<?> reads = new Reads<>((List<Object>) field.get(owner));
        field.set(owner, reads);
        return reads;
    }

    private static final class Reads<E> extends AbstractList<E> implements RandomAccess {
        private final List<E> values;
        private int reads;

        private Reads(List<E> values) {
            this.values = values;
        }

        @Override
        public E get(int index) {
            reads++;
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final FlowManager manager = new FlowManager(null, null);
        private final TestClient client = new TestClient();
        private final GraphDocument graph = graph(0.0);
        private final CoreGraphEditorSession session;

        private Fixture() throws Exception {
            FlowManagerTestConnection.install(manager, SERVER.canonicalText(), client, client.transport);
            publish(client, publication(Map.of()), 1L);
            attach(manager, client);
            load(manager, client, graph);
            assertTrue(manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.COMMAND, RESOURCE.id()));
            Field field = FlowManager.class.getDeclaredField("projectMetadataHydrations");
            field.setAccessible(true);
            ((ThreadPoolExecutor) field.get(manager)).submit(() -> {}).get(3L, TimeUnit.SECONDS);
            session = manager.peekCoreGraphEditorSession(SERVER.canonicalText(),
                ReSyncResourceType.COMMAND, RESOURCE.id()).orElseThrow();
        }

        private boolean current() {
            return manager.isCurrentCoreGraphEditorSession(SERVER.canonicalText(), ReSyncResourceType.COMMAND, RESOURCE.id(), session);
        }

        private boolean dirty() {
            return manager.isCoreGraphEditorSessionDirty(SERVER.canonicalText(), ReSyncResourceType.COMMAND, RESOURCE.id(), session);
        }

        private void queryFrames(boolean dirty) {
            for (int frame = 0; frame < 200; frame++) {
                assertTrue(current());
                assertEquals(dirty, dirty());
                assertSame(session, manager.peekCoreGraphEditorSession(SERVER.canonicalText(),
                    ReSyncResourceType.COMMAND, RESOURCE.id()).orElseThrow());
            }
        }

        private Object state() throws Exception {
            Field field = FlowManager.class.getDeclaredField("coreGraphEditorSessions");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(manager)).values().iterator().next();
        }

        private CoreGraphEditorSession storedSession() throws Exception {
            Object state = state();
            Method method = state.getClass().getDeclaredMethod("session");
            method.setAccessible(true);
            return (CoreGraphEditorSession) method.invoke(state);
        }

        @Override
        public void close() {
            manager.shutdown();
            client.shutdown();
        }
    }

    private static final class TestClient extends ReSyncFlowClient {
        private final ScriptedReSyncTransport transport;
        private int publicationReads;
        private int rolloverRead;
        private Runnable rollover;

        private TestClient() {
            this(new ScriptedReSyncTransport());
        }

        private TestClient(ScriptedReSyncTransport transport) {
            super(SERVER.canonicalText(), transport, null);
            this.transport = transport;
        }

        @Override
        public CatalogAuthority catalogAuthority() {
            return CatalogAuthority.TYPED_PUBLICATION;
        }

        @Override
        public Optional<CatalogAuthoringPublication> activeAuthoringPublication() {
            if (++publicationReads == rolloverRead && rollover != null) {
                rollover.run();
            }
            return super.activeAuthoringPublication();
        }
    }
}
