package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorFramePerformanceTest {
    @Test
    void selectedNodeDragCoalescesPointerUpdatesIntoOnePositionBatchPerFrame() {
        Map<String, int[]> startPositions = new LinkedHashMap<>();
        Map<String, CountingNodeWidget> widgets = new LinkedHashMap<>();
        for (int index = 0; index < 7; index++) {
            String nodeId = "node-" + index;
            startPositions.put(nodeId, new int[]{index * 10, index * 20});
            widgets.put(nodeId, new CountingNodeWidget(index * 10, index * 20, nodeId));
        }
        List<Set<String>> geometryBatches = new ArrayList<>();
        GraphEditorScreen.SelectedNodeMoveAccumulator move = new GraphEditorScreen.SelectedNodeMoveAccumulator();

        for (int update = 0; update < 64; update++) {
            move.queue(update, -update);
        }

        assertTrue(widgets.values().stream().allMatch(widget -> widget.positionChanges == 0));
        assertEquals(7, move.drain(startPositions, widgets,
            nodeIds -> geometryBatches.add(new LinkedHashSet<>(nodeIds))));
        assertTrue(widgets.values().stream().allMatch(widget -> widget.positionChanges == 1));
        assertEquals(1, geometryBatches.size());
        assertEquals(startPositions.keySet(), geometryBatches.getFirst());
        for (int index = 0; index < 7; index++) {
            CountingNodeWidget widget = widgets.get("node-" + index);
            assertEquals(index * 10 + 63, widget.getX());
            assertEquals(index * 20 - 63, widget.getY());
        }

        assertEquals(0, move.drain(startPositions, widgets,
            nodeIds -> geometryBatches.add(new LinkedHashSet<>(nodeIds))));
        assertEquals(1, geometryBatches.size());

        move.queue(80, -90);
        assertEquals(7, move.drain(startPositions, widgets,
            nodeIds -> geometryBatches.add(new LinkedHashSet<>(nodeIds))));
        assertTrue(widgets.values().stream().allMatch(widget -> widget.positionChanges == 2));
        assertEquals(2, geometryBatches.size());
    }

    @Test
    void selectedNodeDragDrainsBeforeFrameGeometryAndReleasePersistence() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        int pointerHandler = source.indexOf("public boolean mouseDragged(ReMouseEvent event)");
        int pointerQueue = source.indexOf("selectedNodeMove.queue(moveX, moveY);", pointerHandler);
        int pointerHandlerEnd = source.indexOf("private int wireColor", pointerHandler);
        int renderFrame = source.indexOf("beginGraphRenderFrame();");
        int renderFlush = source.indexOf("flushGraphRenderGeometry();", renderFrame);
        int releaseHandler = source.indexOf("public boolean mouseReleased(ReMouseEvent event)");
        int releaseDrain = source.indexOf("drainSelectedNodeMove();", releaseHandler);
        int releaseSnapshot = source.indexOf("captureSnapshot();", releaseHandler);
        int releaseSync = source.indexOf("syncNodePositions(selectedDragStartPositions.keySet());", releaseHandler);

        assertTrue(pointerHandler < pointerQueue && pointerQueue < pointerHandlerEnd);
        assertFalse(source.substring(pointerHandler, pointerHandlerEnd).contains("for (String nodeId : selectedNodeIds)"));
        assertTrue(renderFrame < renderFlush);
        assertTrue(releaseHandler < releaseDrain && releaseDrain < releaseSnapshot && releaseSnapshot < releaseSync);
    }

    @Test
    void geometryBatchFlushesAtMostOncePerFrameAndCarriesWorkForward() {
        long frame = 7L;
        long flushedFrame = -1L;
        int flushCount = 0;

        if (GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, 3)) {
            flushedFrame = frame;
            flushCount++;
        }
        if (GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, 2)) {
            flushedFrame = frame;
            flushCount++;
        }
        frame++;
        if (GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, 2)) {
            flushedFrame = frame;
            flushCount++;
        }

        assertEquals(2, flushCount);
        assertEquals(8L, flushedFrame);
        assertFalse(GraphEditorScreen.graphRenderGeometryFlushDue(frame + 1L, flushedFrame, 0));
    }

    @Test
    void stationaryGeometryDoesNotFlushAcrossFrames() {
        long flushedFrame = -1L;
        int flushCount = 0;

        for (long frame = 1L; frame <= 120L; frame++) {
            if (GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, 0)) {
                flushedFrame = frame;
                flushCount++;
            }
        }

        assertEquals(0, flushCount);
        assertEquals(-1L, flushedFrame);
    }

    @Test
    void widgetAnimationQueuesOneFollowingFrameFlushThenSettles() {
        long frame = 1L;
        long flushedFrame = -1L;
        int dirtyCount = 1;
        int flushCount = 0;

        if (GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, dirtyCount)) {
            flushedFrame = frame;
            dirtyCount = 0;
            flushCount++;
        }
        assertTrue(GraphEditorScreen.graphRenderWidgetBoundsChanged(40, 60, 120, 48, 40, 60, 132, 52));
        dirtyCount = 1;
        assertFalse(GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, dirtyCount));

        frame++;
        if (GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, dirtyCount)) {
            flushedFrame = frame;
            dirtyCount = 0;
            flushCount++;
        }
        assertFalse(GraphEditorScreen.graphRenderWidgetBoundsChanged(40, 60, 132, 52, 40, 60, 132, 52));
        for (int staticFrame = 0; staticFrame < 120; staticFrame++) {
            frame++;
            assertFalse(GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, dirtyCount));
        }

        assertEquals(2, flushCount);
        assertEquals(2L, flushedFrame);
    }

    @Test
    void widgetBoundsInvalidateForPositionOrEitherDimensionOnly() {
        assertTrue(GraphEditorScreen.graphRenderWidgetBoundsChanged(10, 20, 100, 40, 11, 20, 100, 40));
        assertTrue(GraphEditorScreen.graphRenderWidgetBoundsChanged(10, 20, 100, 40, 10, 21, 100, 40));
        assertTrue(GraphEditorScreen.graphRenderWidgetBoundsChanged(10, 20, 100, 40, 10, 20, 101, 40));
        assertTrue(GraphEditorScreen.graphRenderWidgetBoundsChanged(10, 20, 100, 40, 10, 20, 100, 41));
        assertFalse(GraphEditorScreen.graphRenderWidgetBoundsChanged(10, 20, 100, 40, 10, 20, 100, 40));
    }

    @Test
    void fractionalRollbackInvalidatesAgainstTheExactAssignedCoordinates() {
        double baselineX = 40.8;
        double baselineY = 60.2;
        int restoredX = (int) baselineX;
        int restoredY = (int) baselineY;

        assertFalse(GraphEditorScreen.roundedGraphRenderPositionChanged(41, 60, baselineX, baselineY));
        assertTrue(GraphEditorScreen.graphRenderWidgetBoundsChanged(41, 60, 100, 40,
            restoredX, restoredY, 100, 40));
        assertEquals(40, restoredX);
        assertEquals(60, restoredY);
    }

    @Test
    void changedWidgetRefreshesItsBoundsConnectedGroupsAndOverflowExactlyOnceOnTheNextFrame() {
        Set<String> dirtyNodeIds = new LinkedHashSet<>();
        assertFalse(GraphEditorScreen.queueGraphRenderGeometryIfBoundsChanged(dirtyNodeIds, "node",
            10, 20, 100, 40, 10, 20, 100, 40));
        assertTrue(dirtyNodeIds.isEmpty());

        long frame = 10L;
        long flushedFrame = frame;
        assertTrue(GraphEditorScreen.queueGraphRenderGeometryIfBoundsChanged(dirtyNodeIds, "node",
            10, 20, 100, 40, 11, 20, 108, 40));
        assertEquals(Set.of("node"), dirtyNodeIds);
        assertFalse(GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, dirtyNodeIds.size()));

        Object firstGroup = new Object();
        Object secondGroup = new Object();
        Map<String, List<Object>> groupsByNode = Map.of("node", List.of(firstGroup, firstGroup, secondGroup));
        AtomicInteger nodeRefreshes = new AtomicInteger();
        Set<Object> refreshedGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        AtomicInteger overflowRefreshes = new AtomicInteger();
        frame++;
        assertTrue(GraphEditorScreen.graphRenderGeometryFlushDue(frame, flushedFrame, dirtyNodeIds.size()));
        Set<String> batch = new LinkedHashSet<>(dirtyNodeIds);
        dirtyNodeIds.clear();
        int connectedGroupCount = GraphEditorScreen.refreshGraphRenderGeometryBatch(batch,
            ignored -> nodeRefreshes.incrementAndGet(), groupsByNode::get, refreshedGroups::add,
            overflowRefreshes::incrementAndGet);
        flushedFrame = frame;

        assertEquals(1, nodeRefreshes.get());
        assertEquals(2, connectedGroupCount);
        assertEquals(2, refreshedGroups.size());
        assertTrue(refreshedGroups.contains(firstGroup));
        assertTrue(refreshedGroups.contains(secondGroup));
        assertEquals(1, overflowRefreshes.get());
        assertFalse(GraphEditorScreen.graphRenderGeometryFlushDue(frame + 1L, flushedFrame, dirtyNodeIds.size()));
    }

    @Test
    void renderedBoundsChangesFeedNodeAndConnectedWireGeometryOnTheNextFrame() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        int renderLoop = source.indexOf("for (FlowNodeWidget flowNodeWidget : visibleNodes)");
        int flush = source.lastIndexOf("flushGraphRenderGeometry();", renderLoop);
        int render = source.indexOf("flowNodeWidget.render(worldContext", renderLoop);
        int queue = source.indexOf("graphRenderGeometryDirtyNodeIds.add(nodeId);", render);
        int refreshStart = source.indexOf("protected final void refreshGraphRenderGeometry");
        int refreshEnd = source.indexOf("private FlowNodeWidget graphRenderWidget", refreshStart);
        String refresh = source.substring(refreshStart, refreshEnd);

        assertTrue(flush >= 0 && flush < render);
        assertTrue(render < queue);
        assertFalse(source.contains("queueActiveGraphRenderGeometry"));
        assertFalse(source.contains("queueGraphRenderGeometry(workspaceNodePositions.keySet())"));
        assertTrue(refresh.contains("index.groupsByNode.getOrDefault(nodeId, List.of())"));
        assertTrue(refresh.contains("refreshWireGroupGeometry(index, group)"));
        assertTrue(refresh.contains("rebuildOverflowWireIndex(index)"));
    }

    @Test
    void remoteInterpolationInvalidatesOnlyWhenRoundedWidgetCoordinatesChange() {
        assertFalse(GraphEditorScreen.roundedGraphRenderPositionChanged(40, 60, 40.49, 59.51));
        assertTrue(GraphEditorScreen.roundedGraphRenderPositionChanged(40, 60, 40.5, 60.0));
        assertTrue(GraphEditorScreen.roundedGraphRenderPositionChanged(40, 60, 40.0, 59.49));
    }

    @Test
    void catalogDrainGuaranteesProgressThenHonorsTimeAndCountBounds() {
        assertTrue(GraphEditorScreen.catalogWidgetDrainCanContinue(20_000_000L, 0));
        assertFalse(GraphEditorScreen.catalogWidgetDrainCanContinue(2_000_000L, 1));
        assertTrue(GraphEditorScreen.catalogWidgetDrainCanContinue(0L, 11));
        assertFalse(GraphEditorScreen.catalogWidgetDrainCanContinue(0L, 12));
    }

    @Test
    void slowFrameDiagnosticUsesFrameBudgetAndOneSecondResourceRateLimit() {
        long now = 2_000_000_000L;

        assertFalse(GraphEditorScreen.graphFrameSlowTraceDue(16_666_999L, -1L, now));
        assertTrue(GraphEditorScreen.graphFrameSlowTraceDue(16_667_000L, -1L, now));
        assertFalse(GraphEditorScreen.graphFrameSlowTraceDue(25_000_000L, now - 999_999_999L, now));
        assertTrue(GraphEditorScreen.graphFrameSlowTraceDue(25_000_000L, now - 1_000_000_000L, now));
    }

    private static final class CountingNodeWidget extends NodeWidget {
        private int positionChanges;

        private CountingNodeWidget(int x, int y, String nodeId) {
            super(x, y, new FlowNode("test", x, y, Map.of()), new FlowGraph(), nodeId,
                null, null, null, null, false, true);
        }

        @Override
        public void setPosition(int x, int y) {
            super.setPosition(x, y);
            positionChanges++;
        }
    }
}
