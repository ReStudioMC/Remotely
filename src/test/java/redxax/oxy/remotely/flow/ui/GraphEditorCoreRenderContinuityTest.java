package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.test.TestDrawContext;
import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.contract.EditorDiagnostic;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCoreRenderContinuityTest {
    private static final ServerId SERVER = ServerId.deterministic("render-continuity");
    private static final OwnerId OWNER = OwnerId.of("test");
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("flow")), "render");
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("render-node");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "1".repeat(64), "2".repeat(64));

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void ordinaryTicksRetryTheSameNodeUntilConstructionSucceeds() throws Exception {
        RetryEditor editor = new RetryEditor(new CoreGraphEditorSession(document()));

        awaitProjectionBuild(editor);
        editor.tick();
        assertEquals(1, editor.attempts.get());
        assertEquals(0, editor.widgetCount());

        editor.tick();
        assertEquals(2, editor.attempts.get());
        assertEquals(0, editor.widgetCount());

        editor.tick();
        assertEquals(3, editor.attempts.get());
        assertEquals(1, editor.widgetCount());
        assertNotNull(editor.widget(NODE.canonicalText()));
        editor.removed();
    }

    @Test
    void graphFrameTicksOnceAndDrawingDoesNotAdvanceQueuedCoreWork() throws Exception {
        LifecycleEditor editor = new LifecycleEditor(new CoreGraphEditorSession(document()));
        try {
            assertEquals(0, editor.tickCount());
            assertEquals(0, editor.widgetCount());

            awaitProjectionBuild(editor);
            editor.render(new TestDrawContext(), 480, 270, 0F);

            assertEquals(0, editor.tickCount());
            assertEquals(0, editor.widgetCount());

            editor.renderEditor();

            assertEquals(1, editor.tickCount());
            assertEquals(1, editor.widgetCount());
            FlowNodeWidget published = editor.widget(NODE.canonicalText());
            assertNotNull(published);
            assertEquals(1, editor.attempts.get());

            editor.render(new TestDrawContext(), 480, 270, 0F);
            assertEquals(1, editor.tickCount());
            assertSame(published, editor.widget(NODE.canonicalText()));

            editor.renderEditor();

            assertEquals(2, editor.tickCount());
            assertEquals(1, editor.widgetCount());
            assertSame(published, editor.widget(NODE.canonicalText()));
            assertEquals(1, editor.attempts.get());
        } finally {
            editor.removed();
        }
    }

    @Test
    void explicitPublicationEventRestartsAtomicPreparationAndSettlesThroughTicks() throws Exception {
        LifecycleEditor editor = new LifecycleEditor(new CoreGraphEditorSession(document(13)));

        awaitProjectionBuild(editor);
        editor.tick();
        int interruptedAttempts = editor.attempts.get();
        assertTrue(interruptedAttempts > 0);
        assertTrue(interruptedAttempts <= 12);
        assertEquals(0, editor.widgetCount());

        editor.refreshNodeRegistry();
        int settlementTicks = 0;
        while (editor.widgetCount() == 0 && settlementTicks++ < 13) {
            editor.tick();
        }

        assertEquals(interruptedAttempts + 13, editor.attempts.get());
        assertEquals(13, editor.widgetCount());
        awaitPreparedRenderBuild(editor);
        editor.renderEditor();
        assertTrue(editor.coreEditorReadiness().widgetTopologyAvailable());

        editor.invalidateRenderIndex();

        assertFalse(editor.coreEditorReadiness().widgetTopologyAvailable());
        editor.scheduleRenderIndex();
        awaitPreparedRenderBuild(editor);
        assertDoesNotThrow(editor::renderEditor);
        assertTrue(editor.coreEditorReadiness().widgetTopologyAvailable());
        editor.removed();
    }

    @Test
    void largeCoreGraphKeepsThePreviousTopologyVisibleUntilTheBoundedPublicationCompletes() throws Exception {
        LifecycleEditor editor = new LifecycleEditor(new CoreGraphEditorSession(document()));

        awaitProjectionBuild(editor);
        editor.tick();
        assertEquals(1, editor.widgetCount());
        FlowNodeWidget previous = editor.widget(NODE.canonicalText());
        assertNotNull(previous);
        awaitRenderIndex(editor);
        assertTrue(editor.retirementPayloadsEmpty());

        editor.replaceSession(new CoreGraphEditorSession(document(4_097)));
        awaitProjectionBuild(editor);
        editor.tick();

        assertEquals(4_097, editor.graphNodeCount());
        assertEquals(1, editor.widgetCount());
        assertSame(previous, editor.widget(NODE.canonicalText()));
        assertDoesNotThrow(editor::renderEditor);

        int boundedTicks = 1;
        while (!editor.hasPendingTopology() && boundedTicks++ < 4_097) {
            editor.tick();
            assertEquals(1, editor.widgetCount());
            assertSame(previous, editor.widget(NODE.canonicalText()));
        }

        assertTrue(boundedTicks >= 342);
        assertTrue(boundedTicks <= 4_097);
        assertTrue(editor.hasPendingTopology());
        assertEquals(1, editor.widgetCount());
        assertSame(previous, editor.widget(NODE.canonicalText()));
        awaitPreparedRenderBuild(editor);
        assertEquals(1, editor.widgetCount());
        assertSame(previous, editor.widget(NODE.canonicalText()));

        List<FlowNodeWidget> displayed = editor.visibleAllNodes();
        List<FlowNodeWidget> selectable = editor.selectableAllNodes();

        assertEquals(displayed, selectable);
        assertEquals(4_097, displayed.size());
        assertEquals(4_097, editor.widgetCount());
        assertFalse(previous == editor.widget(NODE.canonicalText()));
        assertTrue(editor.wasRetired(previous));
        assertTrue(editor.retirementPayloadsEmpty());
        editor.awaitRetirementDrain();
        assertFalse(editor.wasRetired(previous));
        assertEquals(1, editor.cleanupCount(previous));
        assertTrue(editor.coreEditorReadiness().widgetTopologyAvailable());
        editor.removed();
    }

    @Test
    void sameCountReplacementCannotSaveThroughThePreviousTopologyOwner() throws Exception {
        LifecycleEditor editor = new LifecycleEditor(new CoreGraphEditorSession(document(2)));
        AtomicInteger saves = new AtomicInteger();
        editor.setCoreGraphSaveHandler((session, ticket) -> {
            saves.incrementAndGet();
            return true;
        });

        awaitProjectionBuild(editor);
        editor.tick();
        awaitPreparedRenderBuild(editor);
        editor.renderEditor();
        assertTrue(editor.isCoreEditorReady());

        CoreGraphEditorSession replacement = new CoreGraphEditorSession(replacementDocument());
        editor.replaceSession(replacement);
        awaitProjectionBuild(editor);
        editor.tick();

        assertTrue(editor.hasPendingTopology());
        assertFalse(editor.coreEditorReadiness().widgetTopologyAvailable());
        assertFalse(editor.isCoreEditorReady());
        editor.saveEditor();

        GraphNode retained = replacement.graphDocument().nodes().stream()
            .filter(node -> node.instanceId().equals(NODE)).findFirst().orElseThrow();
        assertEquals(900D, retained.x());
        assertEquals(700D, retained.y());
        assertEquals(0, saves.get());

        awaitPreparedRenderBuild(editor);
        assertFalse(editor.isCoreEditorReady());
        editor.renderEditor();

        assertFalse(editor.hasPendingTopology());
        assertTrue(editor.isCoreEditorReady());
        GraphNode adopted = replacement.graphDocument().nodes().stream()
            .filter(node -> node.instanceId().equals(NODE)).findFirst().orElseThrow();
        assertEquals(900D, adopted.x());
        assertEquals(700D, adopted.y());
        assertEquals(0, saves.get());
        editor.removed();
    }

    @Test
    void dirtyContinuityGeometryCannotResurrectDeletedOrReplacedWidgetsAcrossRenderedFrames() throws Exception {
        LifecycleEditor editor = new LifecycleEditor(new CoreGraphEditorSession(document(2)));
        try {
            awaitProjectionBuild(editor);
            editor.tick();
            awaitPreparedRenderBuild(editor);
            editor.renderEditor();
            List<FlowNodeWidget> original = editor.visibleAllNodes();
            assertEquals(2, original.size());
            List<String> identities = List.copyOf(editor.widgetCache.keySet());

            for (int remaining : List.of(1, 0)) {
                List<FlowNodeWidget> previous = List.copyOf(editor.widgetCache.values());
                editor.replaceSession(new CoreGraphEditorSession(document(remaining)));
                awaitProjectionBuild(editor);
                editor.tick();
                assertTrue(editor.hasPendingTopology());
                editor.dirtyGeometry(identities);
                previous.forEach(editor::raiseOldWidget);
                editor.renderEditor();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
                while (editor.hasPendingTopology() && System.nanoTime() < deadline) {
                    editor.renderEditor();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
                }
                assertFalse(editor.hasPendingTopology());
                assertEquals(remaining, editor.graphNodeCount());
                assertEquals(remaining, editor.widgetCount());
                for (int frame = 0; frame < 8; frame++) {
                    editor.dirtyGeometry(identities);
                    previous.forEach(editor::raiseOldWidget);
                    editor.renderEditor();
                    editor.assertExactRenderedWidgets(remaining);
                    assertTrue(editor.visibleAllNodes().stream().noneMatch(previous::contains));
                }
            }
        } finally {
            editor.removed();
        }
    }

    @Test
    void supersededPendingTopologyRetiresOnlyItsAbandonedWidgetsBeforeTheReplacementAdopts() throws Exception {
        LifecycleEditor editor = new LifecycleEditor(new CoreGraphEditorSession(document()));
        try {
            awaitProjectionBuild(editor);
            editor.tick();
            awaitPreparedRenderBuild(editor);
            editor.renderEditor();
            List<FlowNodeWidget> published = editor.activeWidgets();
            assertEquals(1, published.size());

            editor.replaceSession(new CoreGraphEditorSession(document(13)));
            awaitProjectionBuild(editor);
            List<FlowNodeWidget> abandoned = editor.awaitPendingWidgets();
            assertEquals(13, abandoned.size());
            assertTrue(abandoned.stream().noneMatch(published::contains));
            assertTrue(published.stream().noneMatch(widget -> editor.wasRetiredUnchecked(widget)));

            editor.replaceSession(new CoreGraphEditorSession(document(2)));
            awaitProjectionBuild(editor);
            List<FlowNodeWidget> replacement = editor.awaitDifferentPendingWidgets(abandoned);

            assertEquals(2, replacement.size());
            assertTrue(abandoned.stream().allMatch(editor::wasRetiredUnchecked));
            assertTrue(published.stream().noneMatch(editor::wasRetiredUnchecked));
            assertTrue(replacement.stream().noneMatch(editor::wasRetiredUnchecked));

            editor.awaitPendingAdoption();

            assertTrue(published.stream().allMatch(editor::wasRetiredUnchecked));
            assertTrue(editor.activeWidgets().stream().noneMatch(editor::wasRetiredUnchecked));
            assertTrue(editor.retirementPayloadsEmpty());
            editor.awaitRetirementDrain();
            assertTrue(published.stream().noneMatch(editor::wasRetiredUnchecked));
            assertTrue(abandoned.stream().noneMatch(editor::wasRetiredUnchecked));
            published.forEach(widget -> assertEquals(1, editor.cleanupCount(widget)));
            abandoned.forEach(widget -> assertEquals(1, editor.cleanupCount(widget)));
            editor.activeWidgets().forEach(widget -> assertEquals(0, editor.cleanupCount(widget)));
        } finally {
            editor.removed();
        }
    }

    @Test
    void screenCloseCleansPublishedPendingAndQueuedWidgetsExactlyOnce() throws Exception {
        LifecycleEditor editor = new LifecycleEditor(new CoreGraphEditorSession(document()));
        awaitProjectionBuild(editor);
        editor.tick();
        awaitPreparedRenderBuild(editor);
        editor.renderEditor();
        List<FlowNodeWidget> published = editor.activeWidgets();

        editor.replaceSession(new CoreGraphEditorSession(document(13)));
        awaitProjectionBuild(editor);
        List<FlowNodeWidget> abandoned = editor.awaitPendingWidgets();
        editor.replaceSession(new CoreGraphEditorSession(document(2)));
        awaitProjectionBuild(editor);
        List<FlowNodeWidget> pending = editor.awaitDifferentPendingWidgets(abandoned);

        editor.removed();
        editor.removed();

        published.forEach(widget -> assertEquals(1, editor.cleanupCount(widget)));
        abandoned.forEach(widget -> assertEquals(1, editor.cleanupCount(widget)));
        pending.forEach(widget -> assertEquals(1, editor.cleanupCount(widget)));
        assertEquals(0, editor.retiredWidgetCount());
        assertTrue(editor.coreTopologiesCleared());
    }

    @Test
    void priorityFallbacksRemainVisibleBeyondTheViewportCap() {
        List<Integer> viewport = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            viewport.add(index);
        }

        List<Integer> merged = GraphEditorScreen.mergeGraphRenderFallbacks(viewport, List.of(63, 64, 65));

        assertEquals(66, merged.size());
        assertEquals(viewport, merged.subList(0, 64));
        assertEquals(List.of(64, 65), merged.subList(64, 66));
    }

    @Test
    void renderPublicationRequiresTheExactProjectionGenerationAndChecksum() {
        Object graph = new Object();

        assertTrue(GraphEditorScreen.graphRenderBuildMatches(graph, 4L, 6L, 8L, "topology", graph, 4L, 6L,
            8L, "topology"));
        assertFalse(GraphEditorScreen.graphRenderBuildMatches(graph, 4L, 6L, 8L, "topology", graph, 4L, 6L,
            9L, "topology"));
        assertFalse(GraphEditorScreen.graphRenderBuildMatches(graph, 4L, 6L, 8L, "topology", graph, 4L, 6L,
            8L, "other"));
    }

    @Test
    void repeatedConstructionFailurePublishesAnExactDiagnosticPlaceholder() throws Exception {
        AlwaysFailEditor editor = new AlwaysFailEditor(new CoreGraphEditorSession(document()));

        awaitProjectionBuild(editor);
        editor.tick();
        editor.tick();
        editor.tick();

        FlowNodeWidget placeholder = editor.widget(NODE.canonicalText());
        assertNotNull(placeholder);
        assertFalse(placeholder.hasLoadedDefinition());
        assertEquals("WIDGET_CONSTRUCTION_FAILED", editor.diagnostics(NODE.canonicalText()).getFirst().code());
        editor.removed();
    }

    @Test
    void projectionGenerationWrapsWithoutPublishingGenerationZero() {
        assertEquals(1L, GraphEditorScreen.nextProjectionGeneration(Long.MAX_VALUE));
        assertEquals(12L, GraphEditorScreen.nextProjectionGeneration(11L));
    }

    @Test
    void projectedNodeFailureTerminatesWithAVisibleDiagnosticAcrossTicksAndReopen() throws Exception {
        BothFailEditor editor = new BothFailEditor(new CoreGraphEditorSession(document()));

        awaitProjectionBuild(editor);
        editor.tick();
        editor.tick();
        editor.tick();

        assertNotNull(editor.terminalFailure());
        assertEquals(NODE.canonicalText(), editor.terminalFailureNodeId());
        assertTrue(editor.hasTerminalCoreWidgetPublicationFailure());
        assertNull(editor.catalogRefresh());
        assertFalse(editor.nodeRefreshQueued());
        assertDoesNotThrow(editor::renderFailure);
        editor.removed();

        BothFailEditor reopened = new BothFailEditor(new CoreGraphEditorSession(document()));
        awaitProjectionBuild(reopened);
        reopened.tick();
        reopened.tick();
        reopened.tick();

        assertNotNull(reopened.terminalFailure());
        assertNull(reopened.catalogRefresh());
        assertDoesNotThrow(reopened::renderFailure);
        reopened.removed();
    }

    private static void awaitProjectionBuild(GraphEditorScreen editor) throws Exception {
        Field field = GraphEditorScreen.class.getDeclaredField("coreProjectionBuildResults");
        field.setAccessible(true);
        Queue<?> result = (Queue<?>) field.get(editor);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (result.peek() == null && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertNotNull(result.peek());
    }

    private static void awaitRenderIndex(LifecycleEditor editor) throws Exception {
        Field field = GraphEditorScreen.class.getDeclaredField("graphRenderIndex");
        field.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (field.get(editor) == null && System.nanoTime() < deadline) {
            editor.renderEditor();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertNotNull(field.get(editor));
    }

    private static void awaitPreparedRenderBuild(LifecycleEditor editor) throws Exception {
        Field field = GraphEditorScreen.class.getDeclaredField("graphRenderBuildResult");
        field.setAccessible(true);
        BrowserSafeState.ReferenceValue<?> result = (BrowserSafeState.ReferenceValue<?>) field.get(editor);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (result.get() == null && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertNotNull(result.get());
    }

    private static GraphDocument document() {
        return document(1);
    }

    private static GraphDocument document(int nodeCount) {
        List<GraphNode> nodes = new ArrayList<>();
        for (int index = 0; index < nodeCount; index++) {
            NodeInstanceId identity = index == 0 ? NODE : NodeInstanceId.deterministic("render-node-" + index);
            nodes.add(new GraphNode(identity, ContractRef.of(OWNER, NodeId.of("visible")), 1, null, Map.of(), Map.of(),
                List.of(), List.of(), InspectorState.empty(), 25D + index * 20D, 40D, OpaqueData.empty()));
        }
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 1L, BINDING, Set.of(), nodes, List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static GraphDocument replacementDocument() {
        NodeInstanceId replacement = NodeInstanceId.deterministic("replacement-render-node");
        List<GraphNode> nodes = List.of(
            new GraphNode(NODE, ContractRef.of(OWNER, NodeId.of("visible")), 1, null, Map.of(), Map.of(), List.of(),
                List.of(), InspectorState.empty(), 900D, 700D, OpaqueData.empty()),
            new GraphNode(replacement, ContractRef.of(OWNER, NodeId.of("visible")), 1, null, Map.of(), Map.of(),
                List.of(), List.of(), InspectorState.empty(), 1_100D, 700D, OpaqueData.empty())
        );
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 2L, BINDING, Set.of(), nodes, List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static final class RetryEditor extends GraphEditorScreen {
        private final AtomicInteger attempts = new AtomicInteger();

        private RetryEditor(CoreGraphEditorSession session) {
            super(emptyGraph(), SERVER.canonicalText(), new Screen());
            setCoreGraphEditorSession(session);
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("planned widget failure");
            }
            return super.createNodeWidget(nodeId, node);
        }

        private int widgetCount() {
            return widgetCache.size();
        }

        private FlowNodeWidget widget(String id) {
            return widgetCache.get(id);
        }

        private static FlowGraph emptyGraph() {
            FlowGraph graph = new FlowGraph();
            graph.setId(RESOURCE.id());
            graph.setResourceType(ReSyncResourceType.FLOW.typeId());
            return graph;
        }
    }

    private static final class LifecycleEditor extends GraphEditorScreen {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger ticks = new AtomicInteger();
        private final Map<FlowNodeWidget, Integer> cleanupCounts = new IdentityHashMap<>();

        private LifecycleEditor(CoreGraphEditorSession session) {
            super(RetryEditor.emptyGraph(), SERVER.canonicalText(), new Screen());
            width = 960;
            height = 540;
            setCoreGraphEditorSession(session);
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            attempts.incrementAndGet();
            return super.createNodeWidget(nodeId, node);
        }

        @Override
        protected void cleanupCoreWidget(FlowNodeWidget widget) {
            cleanupCounts.merge(widget, 1, Integer::sum);
            super.cleanupCoreWidget(widget);
        }

        @Override
        public void tick() {
            ticks.incrementAndGet();
            super.tick();
        }

        private int widgetCount() {
            return widgetCache.size();
        }

        private int graphNodeCount() {
            return graph.getNodes().size();
        }

        private int tickCount() {
            return ticks.get();
        }

        private FlowNodeWidget widget(String id) {
            return widgetCache.get(id);
        }

        private void replaceSession(CoreGraphEditorSession session) {
            setCoreGraphEditorSession(session);
        }

        private void saveEditor() {
            saveGraph();
        }

        private boolean hasPendingTopology() throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField("pendingCoreWidgetTopology");
            field.setAccessible(true);
            return field.get(this) != null;
        }

        private List<FlowNodeWidget> activeWidgets() {
            return List.copyOf(widgetCache.values());
        }

        private List<FlowNodeWidget> awaitPendingWidgets() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            List<FlowNodeWidget> pending = List.of();
            while (pending.isEmpty() && System.nanoTime() < deadline) {
                tick();
                pending = pendingWidgets();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertFalse(pending.isEmpty());
            return pending;
        }

        private List<FlowNodeWidget> awaitDifferentPendingWidgets(List<FlowNodeWidget> previous) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            List<FlowNodeWidget> pending = pendingWidgets();
            while ((pending.isEmpty() || pending.equals(previous)) && System.nanoTime() < deadline) {
                tick();
                pending = pendingWidgets();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertFalse(pending.isEmpty());
            assertFalse(pending.equals(previous));
            return pending;
        }

        @SuppressWarnings("unchecked")
        private List<FlowNodeWidget> pendingWidgets() throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField("pendingCoreWidgetTopology");
            field.setAccessible(true);
            Object topology = field.get(this);
            if (topology == null) {
                return List.of();
            }
            Method widgets = topology.getClass().getDeclaredMethod("widgets");
            widgets.setAccessible(true);
            return List.copyOf((List<FlowNodeWidget>) widgets.invoke(topology));
        }

        private void awaitPendingAdoption() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (hasPendingTopology() && System.nanoTime() < deadline) {
                renderEditor();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertFalse(hasPendingTopology());
        }

        private void awaitRetirementDrain() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (retiredWidgetCount() > 0 && System.nanoTime() < deadline) {
                tick();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(0, retiredWidgetCount());
        }

        @SuppressWarnings("unchecked")
        private List<FlowNodeWidget> visibleAllNodes() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("visibleGraphNodes", WorldBounds.class);
            method.setAccessible(true);
            return (List<FlowNodeWidget>) method.invoke(this,
                new WorldBounds(-1_000, -1_000, 100_000, 1_000));
        }

        @SuppressWarnings("unchecked")
        private List<FlowNodeWidget> selectableAllNodes() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("graphNodeCandidates", WorldBounds.class,
                boolean.class, Collection.class);
            method.setAccessible(true);
            return (List<FlowNodeWidget>) method.invoke(this,
                new WorldBounds(-1_000, -1_000, 100_000, 1_000), false, List.of());
        }

        private void invalidateRenderIndex() {
            invalidateGraphRenderTopology();
        }

        @SuppressWarnings("unchecked")
        private void dirtyGeometry(Collection<String> identities) throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField("graphRenderGeometryDirtyNodeIds");
            field.setAccessible(true);
            ((Set<String>) field.get(this)).addAll(identities);
        }

        private void raiseOldWidget(FlowNodeWidget widget) {
            try {
                Method method = GraphEditorScreen.class.getDeclaredMethod("bringToFront", FlowNodeWidget.class);
                method.setAccessible(true);
                method.invoke(this, widget);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        private void assertExactRenderedWidgets(int expected) throws Exception {
            List<FlowNodeWidget> visible = visibleAllNodes();
            assertEquals(expected, visible.size());
            assertEquals(Set.copyOf(widgetCache.values()), Set.copyOf(visible));
            assertEquals(Set.copyOf(visible), Set.copyOf(selectableAllNodes()));
            Field indexField = GraphEditorScreen.class.getDeclaredField("graphRenderIndex");
            indexField.setAccessible(true);
            Object index = indexField.get(this);
            assertNotNull(index);
            for (String name : List.of("nodeBounds", "nodeOrder")) {
                Field field = index.getClass().getDeclaredField(name);
                field.setAccessible(true);
                assertEquals(Set.copyOf(widgetCache.values()), ((Map<?, ?>) field.get(index)).keySet());
            }
            Field cellsField = index.getClass().getDeclaredField("nodeCells");
            cellsField.setAccessible(true);
            Map<?, ?> cells = (Map<?, ?>) cellsField.get(index);
            for (Object value : cells.values()) {
                assertTrue(widgetCache.values().containsAll((Collection<?>) value));
            }
        }

        private void scheduleRenderIndex() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("scheduleGraphRenderIndexBuild");
            method.setAccessible(true);
            method.invoke(this);
        }

        @SuppressWarnings("unchecked")
        private boolean wasRetired(FlowNodeWidget widget) throws ReflectiveOperationException {
            Field field = GraphEditorScreen.class.getDeclaredField("retiredCoreWidgets");
            field.setAccessible(true);
            return ((Set<FlowNodeWidget>) field.get(this)).contains(widget);
        }

        private boolean wasRetiredUnchecked(FlowNodeWidget widget) {
            try {
                return wasRetired(widget);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        private int cleanupCount(FlowNodeWidget widget) {
            return cleanupCounts.getOrDefault(widget, 0);
        }

        @SuppressWarnings("unchecked")
        private int retiredWidgetCount() throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField("retiredCoreWidgets");
            field.setAccessible(true);
            return ((Set<FlowNodeWidget>) field.get(this)).size();
        }

        @SuppressWarnings("unchecked")
        private boolean retirementPayloadsEmpty() throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField("coreWidgetTopology");
            field.setAccessible(true);
            Object topology = field.get(this);
            if (topology == null) {
                return false;
            }
            Method adopted = topology.getClass().getDeclaredMethod("retireAfterAdoption");
            Method abandoned = topology.getClass().getDeclaredMethod("retireIfAbandoned");
            adopted.setAccessible(true);
            abandoned.setAccessible(true);
            return ((List<FlowNodeWidget>) adopted.invoke(topology)).isEmpty()
                && ((List<FlowNodeWidget>) abandoned.invoke(topology)).isEmpty();
        }

        private boolean coreTopologiesCleared() throws Exception {
            return field("coreWidgetTopology") == null && field("pendingCoreWidgetTopology") == null
                && field("graphRenderContinuityTopology") == null;
        }

        private Object field(String name) throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(this);
        }

        private void renderEditor() {
            renderHandler(new TestDrawContext(), width / 2, height / 2, 0F);
        }
    }

    private static final class AlwaysFailEditor extends GraphEditorScreen {
        private AlwaysFailEditor(CoreGraphEditorSession session) {
            super(RetryEditor.emptyGraph(), SERVER.canonicalText(), new Screen());
            setCoreGraphEditorSession(session);
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            throw new IllegalStateException("planned permanent widget failure");
        }

        private FlowNodeWidget widget(String id) throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField("widgetCache");
            field.setAccessible(true);
            return (FlowNodeWidget) ((Map<?, ?>) field.get(this)).get(id);
        }

        private List<EditorDiagnostic> diagnostics(String id) {
            return editorDiagnosticsForNode(id);
        }
    }

    private static final class BothFailEditor extends GraphEditorScreen {
        private BothFailEditor(CoreGraphEditorSession session) {
            super(RetryEditor.emptyGraph(), SERVER.canonicalText(), new Screen());
            width = 640;
            height = 360;
            setCoreGraphEditorSession(session);
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            throw new IllegalStateException("planned widget failure");
        }

        @Override
        protected FlowNodeWidget createDiagnosticNodeWidget(String nodeId, FlowNode node, RuntimeException failure) {
            throw new IllegalStateException("planned diagnostic failure");
        }

        private Object terminalFailure() throws Exception {
            return field("coreWidgetPublicationFailure");
        }

        private String terminalFailureNodeId() throws Exception {
            Object failure = terminalFailure();
            Method method = failure.getClass().getDeclaredMethod("nodeId");
            method.setAccessible(true);
            return (String) method.invoke(failure);
        }

        private Object catalogRefresh() throws Exception {
            return field("catalogRefresh");
        }

        private boolean nodeRefreshQueued() throws Exception {
            return (boolean) field("nodeCatalogRefreshQueued");
        }

        private Object field(String name) throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(this);
        }

        private void renderFailure() {
            renderCoreWidgetPublicationFailure(new TestDrawContext());
        }
    }
}
