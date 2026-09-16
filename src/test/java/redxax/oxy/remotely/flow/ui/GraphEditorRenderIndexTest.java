package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorRenderIndexTest {
    @Test
    void spatialCellsCoverNegativeAndPositiveViewportBounds() {
        GraphEditorScreen.WorldBounds bounds = new GraphEditorScreen.WorldBounds(-1.0, -1.0, 513.0, 513.0);

        Set<Long> cells = GraphEditorScreen.graphRenderCells(bounds, 512, 16);

        assertEquals(9, cells.size());
    }

    @Test
    void oversizedSpatialSpanFailsClosedBeforeAllocation() {
        GraphEditorScreen.WorldBounds bounds = new GraphEditorScreen.WorldBounds(-1_000_000_000.0, 0.0, 1_000_000_000.0, 3.0);

        assertNull(GraphEditorScreen.graphRenderCells(bounds, 512, 4_096));
        Set<Long> coarse = GraphEditorScreen.graphRenderCells(bounds, 1_048_576, 4_096);
        assertNotNull(coarse);
        assertTrue(coarse.size() <= 4_096);
    }

    @Test
    void coordinateBoundsRejectNonFiniteAndExtremeWireRoutes() {
        assertTrue(GraphEditorScreen.boundedWireCoordinates(new GraphEditorScreen.WorldBounds(-100.0, -100.0, 100.0, 100.0)));
        assertFalse(GraphEditorScreen.boundedWireCoordinates(new GraphEditorScreen.WorldBounds(-2_000_000_000.0, 0.0, 0.0, 1.0)));
        assertFalse(GraphEditorScreen.boundedWireCoordinates(new GraphEditorScreen.WorldBounds(Double.NaN, 0.0, 1.0, 1.0)));
        assertTrue(new GraphEditorScreen.WorldBounds(-10.0, -10.0, 10.0, 10.0)
            .intersects(new GraphEditorScreen.WorldBounds(9.0, 9.0, 20.0, 20.0)));
        assertNull(GraphEditorScreen.graphRenderCells(new GraphEditorScreen.WorldBounds(Double.NEGATIVE_INFINITY, 0.0,
            Double.POSITIVE_INFINITY, 1.0), 512, 4_096));
    }

    @Test
    void overflowHierarchyFindsRoutesBeyondFormerQueryCap() {
        List<GraphEditorScreen.WorldBounds> routes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            routes.add(new GraphEditorScreen.WorldBounds(-4_000_000_000.0 - i, -100.0, -3_999_999_999.0 - i, 100.0));
        }
        routes.add(new GraphEditorScreen.WorldBounds(2_000_000_000.0, -10.0, 2_000_000_100.0, 10.0));

        Set<Integer> matches = GraphEditorScreen.graphRenderIntersectingBounds(routes,
            new GraphEditorScreen.WorldBounds(2_000_000_050.0, -1.0, 2_000_000_060.0, 1.0));

        assertEquals(Set.of(300), matches);
    }

    @Test
    void catalogConstructionBlocksInitialViewportFinalization() {
        assertFalse(GraphEditorScreen.initialViewportCatalogReady(true, false));
        assertFalse(GraphEditorScreen.initialViewportCatalogReady(false, true));
        assertTrue(GraphEditorScreen.initialViewportCatalogReady(false, false));
    }

    @Test
    void mutationVersionAndNodeOrderTrackTopologyChanges() {
        assertEquals(8L, GraphEditorScreen.nextGraphRenderMutationVersion(7L));
        Object back = new Object();
        Object front = new Object();
        Map<Object, Integer> order = new IdentityHashMap<>();

        GraphEditorScreen.assignGraphRenderOrder(List.of(back, front), order);
        assertEquals(1, order.get(front));
        GraphEditorScreen.assignGraphRenderOrder(List.of(front, back), order);
        assertEquals(0, order.get(front));
    }

    @Test
    void largeLogicalSelectionDoesNotExpandIndexOrFallbackVisibility() {
        List<Integer> nodes = new ArrayList<>(10_000);
        Set<Integer> selected = new HashSet<>();
        for (int index = 0; index < 10_000; index++) {
            nodes.add(index);
            if (index > 0) {
                selected.add(index);
            }
        }
        AtomicInteger inspected = new AtomicInteger();

        List<Integer> fallback = GraphEditorScreen.graphRenderFallbackValues(nodes, node -> {
            inspected.incrementAndGet();
            return node == 0;
        }, List.of(), 4_096, 64);
        List<Integer> indexed = GraphEditorScreen.mergeGraphRenderFallbacks(List.of(0), List.of());

        assertEquals(List.of(0), fallback);
        assertEquals(4_096, inspected.get());
        assertEquals(List.of(0), indexed);
        assertEquals(9_999, selected.size());
        assertTrue(selected.contains(9_999));
    }

    @Test
    void activeDragPriorityRemainsSeparateFromLogicalSelection() {
        List<Integer> visible = GraphEditorScreen.mergeGraphRenderFallbacks(List.of(0), List.of(9_999));

        assertEquals(List.of(0, 9_999), visible);
    }

    @Test
    void highFanoutRenderingQueriesOnlyVisibleArmsAndPreservesExactRoutes() {
        List<GraphEditorScreen.WireSegment> trunks = List.of(
            new GraphEditorScreen.WireSegment(0, 100, 100, 100),
            new GraphEditorScreen.WireSegment(100, 100, 100, 100_000)
        );
        List<GraphEditorScreen.WireSegment> arms = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            double targetX = index == 15 ? 180 : 1_000;
            arms.add(new GraphEditorScreen.WireSegment(100, index * 10.0, targetX, index * 10.0));
        }
        CountingList<GraphEditorScreen.WireSegment> countedArms = new CountingList<>(arms);
        GraphEditorScreen.WorldBounds viewport = new GraphEditorScreen.WorldBounds(50, 90, 200, 210);

        List<GraphEditorScreen.WireSegment> visible = GraphEditorScreen.graphRenderVisibleFanoutSegments(trunks,
            countedArms, viewport);

        assertEquals(15, visible.size());
        assertTrue(countedArms.getCalls() <= 32);
        assertTrue(visible.stream().allMatch(segment -> segment.x1() >= viewport.minX()
            && segment.x1() <= viewport.maxX() && segment.x2() >= viewport.minX()
            && segment.x2() <= viewport.maxX() && segment.y1() >= viewport.minY()
            && segment.y1() <= viewport.maxY() && segment.y2() >= viewport.minY()
            && segment.y2() <= viewport.maxY()));
        assertEquals(new GraphEditorScreen.WireSegment(100, 100, 100, 100_000), trunks.get(1));
        assertEquals(new GraphEditorScreen.WireSegment(100, 150, 180, 150), arms.get(15));
        assertTrue(GraphEditorScreen.isNearWireSegment(170, 150, arms.get(15), 0.5));
        assertFalse(GraphEditorScreen.isNearWireSegment(170, 155, arms.get(15), 0.5));
    }

    @Test
    void productionFanoutHitQueryIsBoundedAndPreservesExactArmAndSharedTrunkHits() {
        List<GraphEditorScreen.WireSegment> trunks = List.of(
            new GraphEditorScreen.WireSegment(0, 100, 100, 100),
            new GraphEditorScreen.WireSegment(100, 100, 100, 100_000)
        );
        List<GraphEditorScreen.WireSegment> arms = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            arms.add(new GraphEditorScreen.WireSegment(100, index * 10.0, 220, index * 10.0));
        }
        CountingList<GraphEditorScreen.WireSegment> countedArms = new CountingList<>(arms);
        GraphEditorScreen.FanoutArmHitIndex armIndex = GraphEditorScreen.graphRenderFanoutArmIndex(countedArms);
        countedArms.resetGetCalls();

        int arm = GraphEditorScreen.graphRenderFanoutHitIndex(trunks, armIndex, 180, 12_340, 0.5);
        int trunk = GraphEditorScreen.graphRenderFanoutHitIndex(trunks, armIndex, 100, 50_005, 0.5);

        assertEquals(1_234, arm);
        assertEquals(-2, trunk);
        assertTrue(countedArms.getCalls() <= 64);
        assertEquals(arms.get(1_234), countedArms.get(arm));
    }

    @Test
    void productionFanoutHitQueryRemainsBoundedForTenThousandOverlappingArms() {
        List<GraphEditorScreen.WireSegment> arms = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            arms.add(new GraphEditorScreen.WireSegment(100, 500, 220, 500));
        }
        CountingList<GraphEditorScreen.WireSegment> countedArms = new CountingList<>(arms);
        GraphEditorScreen.FanoutArmHitIndex armIndex = GraphEditorScreen.graphRenderFanoutArmIndex(countedArms);
        countedArms.resetGetCalls();

        int hit = GraphEditorScreen.graphRenderFanoutHitIndex(List.of(), armIndex, 180, 500, 0.5);

        assertEquals(0, hit);
        assertTrue(countedArms.getCalls() <= 64);
    }

    @Test
    void productionPointerAndSelectionCandidatesInspectOnlySpatialMatchesInTopmostOrder() {
        Object back = new Object();
        Object front = new Object();
        Map<Long, List<Object>> cells = new HashMap<>();
        CountingIdentityMap<Object, GraphEditorScreen.WorldBounds> bounds = new CountingIdentityMap<>();
        Map<Object, Integer> order = new IdentityHashMap<>();
        addNode(cells, bounds, order, back, new GraphEditorScreen.WorldBounds(10, 10, 110, 110), 0);
        addNode(cells, bounds, order, front, new GraphEditorScreen.WorldBounds(20, 20, 120, 120), 1);
        for (int index = 0; index < 10_000; index++) {
            Object far = new Object();
            double x = 10_000 + index * 1_024.0;
            addNode(cells, bounds, order, far, new GraphEditorScreen.WorldBounds(x, 10, x + 100, 110), index + 2);
        }

        List<Object> point = GraphEditorScreen.graphRenderNodeCandidates(cells, bounds, order,
            new GraphEditorScreen.WorldBounds(50, 50, 50, 50), true, List.of());
        List<Object> selection = GraphEditorScreen.graphRenderNodeCandidates(cells, bounds, order,
            new GraphEditorScreen.WorldBounds(0, 0, 200, 200), false, List.of());

        assertEquals(List.of(front, back), point);
        assertEquals(List.of(back, front), selection);
        assertTrue(bounds.getCalls() <= 4);
    }

    @Test
    void completedIndexRequiresExactGraphIdentityAndMutationVersion() {
        Object graph = new Object();

        assertTrue(GraphEditorScreen.graphRenderBuildMatches(graph, 4L, 6L, graph, 4L, 6L));
        assertFalse(GraphEditorScreen.graphRenderBuildMatches(graph, 4L, 6L, new Object(), 4L, 6L));
        assertFalse(GraphEditorScreen.graphRenderBuildMatches(graph, 4L, 6L, graph, 3L, 6L));
        assertFalse(GraphEditorScreen.graphRenderBuildMatches(graph, 4L, 7L, graph, 4L, 7L));
    }

    @Test
    void awarenessSelectionSnapshotParsesOnlyNewVersions() {
        JsonArray firstSelection = new JsonArray();
        firstSelection.add("first");
        GraphEditorScreen.WorkspaceSelectionSnapshot first = GraphEditorScreen.workspaceSelectionSnapshot(null, 10L, 0xFF112233,
            firstSelection);
        JsonArray ignoredSelection = new JsonArray();
        ignoredSelection.add("ignored");

        GraphEditorScreen.WorkspaceSelectionSnapshot cached = GraphEditorScreen.workspaceSelectionSnapshot(first, 10L, 0xFF445566,
            ignoredSelection);
        GraphEditorScreen.WorkspaceSelectionSnapshot updated = GraphEditorScreen.workspaceSelectionSnapshot(first, 11L, 0xFF445566,
            ignoredSelection);

        assertSame(first, cached);
        assertEquals(Set.of("first"), cached.nodeIds());
        assertEquals(Set.of("ignored"), updated.nodeIds());
    }

    private static final class CountingList<T> extends AbstractList<T> {
        private final List<T> values;
        private int getCalls;

        private CountingList(List<T> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public T get(int index) {
            getCalls++;
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }

        private int getCalls() {
            return getCalls;
        }

        private void resetGetCalls() {
            getCalls = 0;
        }
    }

    private static void addNode(Map<Long, List<Object>> cells,
                                Map<Object, GraphEditorScreen.WorldBounds> bounds,
                                Map<Object, Integer> order, Object node,
                                GraphEditorScreen.WorldBounds nodeBounds, int nodeOrder) {
        bounds.put(node, nodeBounds);
        order.put(node, nodeOrder);
        for (long cell : GraphEditorScreen.graphRenderCells(nodeBounds, 512, 4_096)) {
            cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(node);
        }
    }

    private static final class CountingIdentityMap<K, V> extends IdentityHashMap<K, V> {
        private int getCalls;

        @Override
        public V get(Object key) {
            getCalls++;
            return super.get(key);
        }

        private int getCalls() {
            return getCalls;
        }
    }
}
