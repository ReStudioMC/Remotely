package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GraphEditorCoreRenderSafetyTest {
    @Test
    void renderCellsRejectNonFiniteAndUnboundedCoordinates() {
        assertNotNull(GraphEditorScreen.graphRenderCells(new GraphEditorScreen.WorldBounds(0, 0, 10, 10), 512, 4));
        assertNull(GraphEditorScreen.graphRenderCells(new GraphEditorScreen.WorldBounds(Double.NaN, 0, 10, 10), 512, 4));
        assertNull(GraphEditorScreen.graphRenderCells(new GraphEditorScreen.WorldBounds(-2_000_000_000D, 0, 10, 10), 512, 4));
        assertNull(GraphEditorScreen.graphRenderCells(new GraphEditorScreen.WorldBounds(0, 0, 1_000_000D, 10), 512, 4));
    }
}
