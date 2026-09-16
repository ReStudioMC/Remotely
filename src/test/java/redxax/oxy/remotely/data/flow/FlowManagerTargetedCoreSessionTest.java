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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerTargetedCoreSessionTest {
    private static final ServerId SERVER = ServerId.deterministic("targeted-core-session");
    private static final CatalogBinding BINDING = new CatalogBinding(54L, "5".repeat(64), "4".repeat(64));
    private static final CatalogBinding OLD_BINDING = new CatalogBinding(53L, "5".repeat(64), "3".repeat(64));
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("targeted-core-node");

    @Test
    void loadedBaselineCreatesOnlyTheRequestedSessionAndPreservesItsDirtyDraft() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.loaded(ReSyncResourceType.COMMAND, "requested", BINDING);
            fixture.loaded(ReSyncResourceType.FLOW, "unopened", BINDING);
            assertTrue(fixture.session(ReSyncResourceType.COMMAND, "requested").isEmpty());
            assertTrue(fixture.session(ReSyncResourceType.FLOW, "unopened").isEmpty());

            assertTrue(fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(),
                ReSyncResourceType.COMMAND, "requested"));
            fixture.drain();

            CoreGraphEditorSession session = fixture.session(ReSyncResourceType.COMMAND, "requested").orElseThrow();
            assertEquals(1L, session.baselineRevision());
            assertEquals(BINDING, session.graphDocument().catalogBinding());
            assertTrue(fixture.manager.isCurrentCoreGraphEditorSession(SERVER.canonicalText(),
                ReSyncResourceType.COMMAND, "requested", session));
            assertTrue(fixture.session(ReSyncResourceType.FLOW, "unopened").isEmpty());
            assertEquals(0, fixture.client.loads.get());

            session.setNodePosition(NODE, 12.0, 24.0);
            assertTrue(session.isDirty());
            fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.COMMAND, "requested");
            fixture.drain();
            assertSame(session, fixture.session(ReSyncResourceType.COMMAND, "requested").orElseThrow());
            assertTrue(session.isDirty());
            assertEquals(12.0, session.graphDocument().nodes().getFirst().x());
        }
    }

    @Test
    void targetedRetryCreatesTheSessionAfterTheLoadArrives() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.FLOW, "later"));
            fixture.drain();
            assertEquals(1, fixture.client.loads.get());
            assertTrue(fixture.session(ReSyncResourceType.FLOW, "later").isEmpty());

            fixture.loaded(ReSyncResourceType.FLOW, "later", BINDING);
            assertTrue(fixture.session(ReSyncResourceType.FLOW, "later").isEmpty());
            fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.FLOW, "later");
            fixture.drain();

            assertEquals(1L, fixture.session(ReSyncResourceType.FLOW, "later").orElseThrow().revision());
            assertEquals(1, fixture.client.loads.get());
        }
    }

    @Test
    void incompatibleOrAbsentBaselinesNeverRegisterSessions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.loaded(ReSyncResourceType.COMMAND, "mismatch", OLD_BINDING);
            fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.COMMAND, "mismatch");
            fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.FLOW, "missing");
            fixture.drain();

            assertTrue(fixture.session(ReSyncResourceType.COMMAND, "mismatch").isEmpty());
            assertTrue(fixture.session(ReSyncResourceType.FLOW, "missing").isEmpty());
            assertTrue(fixture.registry().isEmpty());
        }
    }

    @Test
    void queuedPreparationRejectsAnAdvancedAuthorityEpoch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.loaded(ReSyncResourceType.COMMAND, "stale", BINDING);
            CountDownLatch release = fixture.blockWorker();
            try {
                fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.COMMAND, "stale");
                fixture.client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 2L);
            } finally {
                release.countDown();
            }
            fixture.drain();

            assertTrue(fixture.registry().isEmpty());
        }
    }

    @Test
    void queuedPreparationRejectsAReplacedConnectionOwner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.loaded(ReSyncResourceType.COMMAND, "stale", BINDING);
            CountDownLatch release = fixture.blockWorker();
            TestClient replacement = new TestClient(new ScriptedReSyncTransport());
            try {
                fixture.manager.requestCoreGraphHydration(SERVER.canonicalText(), ReSyncResourceType.COMMAND, "stale");
                FlowManagerTestConnection.install(fixture.manager, SERVER.canonicalText(), replacement, replacement.transport);
            } finally {
                release.countDown();
            }
            fixture.drain();

            assertTrue(fixture.registry().isEmpty());
            replacement.shutdown();
        }
    }

    private static CatalogAuthoringPublication publication() {
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            section(CatalogAuthoringPublication.Section.TYPES), section(CatalogAuthoringPublication.Section.EDITORS),
            section(CatalogAuthoringPublication.Section.PREVIEWS), section(CatalogAuthoringPublication.Section.CAPABILITIES));
        return new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0), CatalogProjectionVersion.current(),
            sections, Set.of());
    }

    private static CatalogAuthoringPublication.SectionProjection section(CatalogAuthoringPublication.Section section) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE, List.of());
    }

    private static final class Fixture implements AutoCloseable {
        private final FlowManager manager = new FlowManager(null, null);
        private final TestClient client = new TestClient(new ScriptedReSyncTransport());
        private final ThreadPoolExecutor worker;

        private Fixture() throws Exception {
            FlowManagerTestConnection.install(manager, SERVER.canonicalText(), client, client.transport);
            Method attach = FlowManager.class.getDeclaredMethod("attachCoreGraphListener", ReSyncFlowClient.class);
            attach.setAccessible(true);
            assertTrue((Boolean) attach.invoke(manager, client));
            Field field = FlowManager.class.getDeclaredField("projectMetadataHydrations");
            field.setAccessible(true);
            worker = (ThreadPoolExecutor) field.get(manager);
            drain();
        }

        private void loaded(ReSyncResourceType type, String id, CatalogBinding binding) throws Exception {
            ServerResourceLocator resource = new ServerResourceLocator(SERVER,
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(type.typeId())), id);
            GraphNode node = new GraphNode(NODE, ContractRef.of(OwnerId.of("test"), NodeId.of("node")), 1, null,
                Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 0.0, 0.0, OpaqueData.empty());
            GraphDocument graph = new GraphDocument(resource, 1L, binding, List.of(node), List.of());
            GraphResourceState state = GraphResourceState.live(resource, 1L, UUID.randomUUID(),
                ContentHash.of("a".repeat(64)), ContentHash.of("b".repeat(64)), graph, ResourceActivationState.ACTIVE);
            Method apply = FlowManager.class.getDeclaredMethod("applyHydratedCoreGraphState", ReSyncFlowClient.class,
                ReSyncResourceType.class, GraphResourceState.class);
            apply.setAccessible(true);
            apply.invoke(manager, client, type, state);
        }

        private Optional<CoreGraphEditorSession> session(ReSyncResourceType type, String id) {
            return manager.peekCoreGraphEditorSession(SERVER.canonicalText(), type, id);
        }

        private Map<?, ?> registry() throws Exception {
            Field field = FlowManager.class.getDeclaredField("coreGraphEditorSessions");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(manager);
        }

        private void drain() throws Exception {
            worker.submit(() -> {}).get(2L, TimeUnit.SECONDS);
        }

        private CountDownLatch blockWorker() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            worker.execute(() -> {
                entered.countDown();
                try {
                    assertTrue(release.await(2L, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            assertTrue(entered.await(2L, TimeUnit.SECONDS));
            return release;
        }

        @Override
        public void close() {
            manager.shutdown();
            client.shutdown();
        }
    }

    private static final class TestClient extends ReSyncFlowClient {
        private final ScriptedReSyncTransport transport;
        private final AtomicInteger loads = new AtomicInteger();

        private TestClient(ScriptedReSyncTransport transport) {
            super(SERVER.canonicalText(), transport, null);
            this.transport = transport;
            CatalogAuthoringPublication authoring = publication();
            CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
            CatalogCachePublication catalog = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
                BINDING, 1L, List.of(), authoring, Map.of());
            CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
            ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER,
                "targeted-core-session", catalogPublicationProjection(), catalogAuthoringProjection(), null);
            handler.setAuthoringRequired(true);
            CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending("targeted-core-session",
                "targeted-core-owner", catalog).dispatched().clientReceived(key, 1L).receipt().orElseThrow();
            ReSyncCatalogPublicationReceiptHandler.Application applied = handler.apply(receipt, catalog,
                codec.encodeBytes(catalog));
            assertTrue(applied.applied(), applied.diagnostic());
        }

        @Override
        public CatalogAuthority catalogAuthority() {
            return CatalogAuthority.TYPED_PUBLICATION;
        }

        @Override
        public void requestResource(ReSyncResourceType type, String id, boolean openWhenReceived) {
            assertFalse(openWhenReceived);
            loads.incrementAndGet();
        }
    }
}
