package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.ui.FlowNodeWidget.FunctionBoundaryCatalog;
import redxax.oxy.remotely.test.TestDrawContext;
import redxax.oxy.remotely.util.BrowserSafeState.BooleanValue;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorWireHitSafetyTest {
    private static final String SOURCE = "source";
    private static final String TARGET = "target";

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void nonCoreCatalogWithWireCompletesThroughRenderFrames() throws Exception {
        WireEditor editor = new WireEditor(false, TARGET);
        try {
            editor.prepare(true);
            assertNull(editor.field("catalogRefresh"));
            assertFalse((boolean) editor.field("nodeCatalogRefreshQueued"));
            assertNotNull(editor.renderIndex());
            double[] hit = editor.hitPoint();
            assertSame(editor.connection(), editor.findConnection(hit[0], hit[1]));
        } finally {
            editor.removed();
        }
    }

    @Test
    void rightClickMissInOccupiedWireCellIsSafeWithoutAnActiveDrag() throws Exception {
        for (boolean stable : List.of(false, true)) {
            WireEditor editor = new WireEditor(stable, TARGET);
            try {
                editor.prepare(true);
                double[] hit = editor.hitPoint();
                GraphEditorScreen.WorldBounds miss = editor.missBounds(hit);
                assertSame(editor.connection(), editor.findConnection(hit[0], hit[1]));
                assertEquals(0, editor.visibleGroupCount(miss));

                assertTrue(assertDoesNotThrow(() -> editor.rightClick(miss.minX(), miss.minY())));
                assertEquals(1, editor.menuRequests);
                assertSame(editor.connection(), editor.graph.getConnections().getFirst());
                assertDoesNotThrow(() -> editor.renderWireRegion(miss));

                assertTrue(assertDoesNotThrow(() -> editor.rightClick(hit[0], hit[1])));
                assertEquals(1, editor.menuRequests);
            } finally {
                editor.removed();
            }
        }
    }

    @Test
    void eitherDragIdentityCanBeAbsentWithoutLosingTheOtherPriority() throws Exception {
        WireEditor editor = new WireEditor(true, TARGET);
        try {
            editor.prepare(true);
            double[] hit = editor.hitPoint();
            GraphEditorScreen.WorldBounds miss = editor.missBounds(hit);

            editor.setDrag(SOURCE, null);
            assertEquals(1, editor.visibleGroupCount(miss));
            assertDoesNotThrow(() -> editor.renderWireRegion(miss));
            assertTrue(assertDoesNotThrow(() -> editor.rightClick(miss.minX(), miss.minY())));

            editor.setDrag(null, SOURCE);
            assertEquals(1, editor.visibleGroupCount(miss));
            assertDoesNotThrow(() -> editor.renderWireRegion(miss));
            assertTrue(assertDoesNotThrow(() -> editor.rightClick(miss.minX(), miss.minY())));

            editor.setDrag("unrelated", null);
            assertEquals(0, editor.visibleGroupCount(miss));
            assertTrue(assertDoesNotThrow(() -> editor.rightClick(miss.minX(), miss.minY())));
            assertEquals(3, editor.menuRequests);

            editor.setDrag(null, null);
            assertSame(editor.connection(), editor.findConnection(hit[0], hit[1]));
        } finally {
            editor.removed();
        }
    }

    @Test
    void pendingIndexKeepsBoundedWireHitAndRenderFallbackSafe() throws Exception {
        WireEditor editor = new WireEditor(false, TARGET);
        try {
            editor.prepare(false);
            double[] hit = editor.hitPoint();
            GraphEditorScreen.WorldBounds miss = editor.missBounds(hit);
            assertNull(editor.renderIndex());
            assertSame(editor.connection(), editor.findConnection(hit[0], hit[1]));
            assertNull(editor.findConnection(miss.minX(), miss.minY()));
            assertTrue(assertDoesNotThrow(() -> editor.rightClick(miss.minX(), miss.minY())));
            assertEquals(1, editor.menuRequests);
            assertDoesNotThrow(() -> editor.renderWireRegion(miss));
            assertDoesNotThrow(() -> editor.renderWireRegion(new GraphEditorScreen.WorldBounds(0, 0, 512, 512)));
            assertNull(editor.renderIndex());
        } finally {
            editor.removed();
        }
    }

    @Test
    void missingTargetGeometryStaysUnhittableWithOrWithoutAnIndex() throws Exception {
        for (boolean ready : List.of(false, true)) {
            WireEditor editor = new WireEditor(true, null);
            try {
                editor.prepare(ready);
                assertNull(editor.connection().getTargetNodeId());
                assertNull(editor.findConnection(300, 400));
                assertTrue(assertDoesNotThrow(() -> editor.rightClick(300, 400)));
                assertEquals(1, editor.menuRequests);
                assertDoesNotThrow(() -> editor.renderWireRegion(new GraphEditorScreen.WorldBounds(0, 0, 512, 512)));
                assertSame(editor.connection(), editor.graph.getConnections().getFirst());
            } finally {
                editor.removed();
            }
        }
    }

    private static final class WireEditor extends FlowGraphDesignerScreen {
        private final NodeDefinition definition = new NodeDefinition.Builder("wire-hit", "Wire Hit", NodeDefinition.NodeCategory.FLOW)
            .owner("test")
            .input("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .build();
        private int menuRequests;

        private WireEditor(boolean stable, String target) {
            super(wireGraph(stable, target), "wire-hit-test", new Screen());
            width = 900;
            height = 600;
            zoomLevel = targetZoomLevel = 1F;
            panX = panY = targetPanX = targetPanY = 0F;
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            return new FlowNodeWidget((int) node.getX(), (int) node.getY(), node, graph, nodeId, null, null, null,
                FunctionBoundaryCatalog.unavailable(), definition, true);
        }

        @Override
        protected void showAllNodesMenu(int screenX, int screenY) {
            menuRequests++;
        }

        private void prepare(boolean indexReady) throws Exception {
            if (!indexReady) {
                BooleanValue workerQueued = (BooleanValue) field("graphRenderWorkerQueued");
                long workerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
                while (workerQueued.get() && System.nanoTime() < workerDeadline) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
                }
                assertFalse(workerQueued.get());
                workerQueued.set(true);
            }
            refreshNodeRegistry();
            TestDrawContext context = new TestDrawContext();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (System.nanoTime() < deadline) {
                renderHandler(context, 0, 0, 0F);
                if (widgetCache.size() == 2 && field("catalogRefresh") == null
                    && !(boolean) field("nodeCatalogRefreshQueued") && (!indexReady || renderIndex() != null)) {
                    break;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertEquals(2, widgetCache.size());
            assertNull(field("catalogRefresh"));
            assertFalse((boolean) field("nodeCatalogRefreshQueued"));
            if (indexReady) {
                assertNotNull(renderIndex());
            } else {
                assertNull(renderIndex());
            }
            zoomLevel = targetZoomLevel = 1F;
            panX = panY = targetPanX = targetPanY = 0F;
        }

        private FlowConnection connection() {
            return graph.getConnections().getFirst();
        }

        private Object renderIndex() throws Exception {
            return invoke("visibleGraphRenderIndex", new Class<?>[0]);
        }

        private FlowConnection findConnection(double x, double y) throws Exception {
            return (FlowConnection) invoke("findConnectionAt", new Class<?>[] {double.class, double.class}, x, y);
        }

        private boolean rightClick(double x, double y) {
            zoomLevel = targetZoomLevel = 1F;
            panX = panY = targetPanX = targetPanY = 0F;
            return mouseClicked(new ReMouseEvent(this, this, 0L, ReModifierState.none(), ReMouseEvent.Action.PRESSED,
                x, y, 0, 0, ReMouseButton.RIGHT, 1, 1));
        }

        @SuppressWarnings("unchecked")
        private double[] hitPoint() throws Exception {
            double[] source = widgetCache.get(SOURCE).getPinBounds("flow", false);
            double[] target = widgetCache.get(TARGET).getPinBounds("flow", true);
            assertNotNull(source);
            assertNotNull(target);
            List<GraphEditorScreen.WireSegment> segments = (List<GraphEditorScreen.WireSegment>) invoke("graphWireSegments",
                new Class<?>[] {double.class, double.class, double.class, double.class},
                source[0] + source[2] / 2, source[1] + source[3] / 2, target[0] + target[2] / 2, target[1] + target[3] / 2);
            GraphEditorScreen.WireSegment segment = segments.stream()
                .max(Comparator.comparingDouble(value -> Math.abs(value.x2() - value.x1()) + Math.abs(value.y2() - value.y1())))
                .orElseThrow();
            return new double[] {(segment.x1() + segment.x2()) / 2, (segment.y1() + segment.y2()) / 2};
        }

        private GraphEditorScreen.WorldBounds missBounds(double[] hit) {
            GraphEditorScreen.WorldBounds miss = new GraphEditorScreen.WorldBounds(hit[0], 400, hit[0] + 1, 401);
            Set<Long> wireCells = graphRenderCells(new GraphEditorScreen.WorldBounds(hit[0], hit[1], hit[0], hit[1]), 512, 4);
            Set<Long> missCells = graphRenderCells(miss, 512, 4);
            assertTrue(wireCells.stream().anyMatch(missCells::contains));
            assertFalse(widgetCache.values().stream().anyMatch(widget -> widget.isMouseOver(hit[0], 400)));
            return miss;
        }

        private int visibleGroupCount(GraphEditorScreen.WorldBounds bounds) throws Exception {
            Object index = renderIndex();
            assertNotNull(index);
            return ((List<?>) invoke("visibleWireGroups", new Class<?>[] {index.getClass(), GraphEditorScreen.WorldBounds.class,
                Collection.class}, index, bounds, List.of())).size();
        }

        private void renderWireRegion(GraphEditorScreen.WorldBounds bounds) throws Exception {
            invoke("renderWires", new Class<?>[] {IDrawContext.class, GraphEditorScreen.WorldBounds.class, Collection.class},
                new TestDrawContext(), bounds, List.copyOf(widgetCache.values()));
        }

        private void setDrag(String wireSource, String draggedNode) throws Exception {
            Object state = field("dragState");
            setField(state, "isDragging", wireSource != null);
            setField(state, "sourceNodeId", wireSource);
            setField(state, "sourcePin", wireSource != null ? "flow" : null);
            draggedWidget = draggedNode != null ? widgetCache.get(draggedNode) : null;
        }

        private Object field(String name) throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(this);
        }

        private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(this, arguments);
        }

        private static void setField(Object owner, String name, Object value) throws Exception {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        }

        private static FlowGraph wireGraph(boolean stable, String target) {
            FlowGraph graph = new FlowGraph();
            graph.setId("wire-hit");
            graph.setResourceType("flow");
            graph.getNodes().put(SOURCE, new FlowNode("test:wire-hit", 40, 80, Map.of()));
            graph.getNodes().put(TARGET, new FlowNode("test:wire-hit", 420, 80, Map.of()));
            graph.getConnections().add(stable ? FlowConnection.stable(SOURCE, "flow", target, "flow")
                : FlowConnection.legacy(SOURCE, "flow", target, "flow"));
            return graph;
        }
    }
}
