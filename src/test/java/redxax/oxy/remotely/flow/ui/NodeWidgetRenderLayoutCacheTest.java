package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeWidgetRenderLayoutCacheTest {
    @BeforeAll
    static void initializeTheme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void unchangedFramesReuseAbsoluteChildLayoutAndGeometryChangesPublishOnce() {
        NodeDefinition definition = new NodeDefinition.Builder("layout-cache", "Layout Cache", NodeDefinition.NodeCategory.FLOW)
            .owner("builtin")
            .input("enabled", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .input("label", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .output("result", NodeDefinition.PinType.DATA, FlowDataType.STRING)
            .build();
        FlowNode node = new FlowNode("builtin:layout-cache", 30, 50, new LinkedHashMap<>());
        NodeWidget widget = new NodeWidget(30, 50, node, new FlowGraph(), "node", null, () -> {
        }, null, definition, false);
        widget.setAnimateLayout(false);

        widget.prepareRenderLayout();
        long firstLayoutOperations = widget.getChildLayoutOperationCount();
        double[] inputBefore = widget.getPinBounds("enabled", true);
        double[] outputBefore = widget.getPinBounds("result", false);
        assertTrue(firstLayoutOperations > 0);
        assertNotNull(inputBefore);
        assertNotNull(outputBefore);

        for (int frame = 0; frame < 1_000; frame++) {
            widget.prepareRenderLayout();
        }
        assertEquals(firstLayoutOperations, widget.getChildLayoutOperationCount());
        assertArrayEquals(inputBefore, widget.getPinBounds("enabled", true), 0.0);
        assertArrayEquals(outputBefore, widget.getPinBounds("result", false), 0.0);

        widget.setPosition(90, 110);
        widget.prepareRenderLayout();
        long movedLayoutOperations = widget.getChildLayoutOperationCount();
        double[] inputMoved = widget.getPinBounds("enabled", true);
        double[] outputMoved = widget.getPinBounds("result", false);
        assertTrue(movedLayoutOperations > firstLayoutOperations);
        assertEquals(inputBefore[0] + 60, inputMoved[0]);
        assertEquals(inputBefore[1] + 60, inputMoved[1]);
        assertEquals(outputBefore[0] + 60, outputMoved[0]);
        assertEquals(outputBefore[1] + 60, outputMoved[1]);

        int widthBefore = widget.getWidth();
        widget.setWidth(widthBefore + 48);
        widget.prepareRenderLayout();
        long resizedLayoutOperations = widget.getChildLayoutOperationCount();
        double[] inputResized = widget.getPinBounds("enabled", true);
        double[] outputResized = widget.getPinBounds("result", false);
        assertTrue(resizedLayoutOperations > movedLayoutOperations);
        assertArrayEquals(inputMoved, inputResized, 0.0);
        assertEquals(outputMoved[0] + 48, outputResized[0]);
        assertEquals(outputMoved[1], outputResized[1]);

        int inputColor = NodeWidget.getPinColor(widget.getPinType("enabled", true));
        int outputColor = NodeWidget.getPinColor(widget.getPinType("result", false));
        assertTrue(widget.assignLiteralInput("label", "updated"));
        widget.prepareRenderLayout();
        long dataLayoutOperations = widget.getChildLayoutOperationCount();
        double[] outputAfterDataRefresh = widget.getPinBounds("result", false);
        assertTrue(dataLayoutOperations > resizedLayoutOperations);
        assertNotNull(outputAfterDataRefresh);
        assertEquals(widthBefore, widget.getWidth());
        assertArrayEquals(inputResized, widget.getPinBounds("enabled", true), 0.0);
        assertEquals(widget.getX() + widget.getWidth() - 16.0, outputAfterDataRefresh[0]);
        assertEquals(outputResized[1], outputAfterDataRefresh[1]);
        assertEquals(inputColor, NodeWidget.getPinColor(widget.getPinType("enabled", true)));
        assertEquals(outputColor, NodeWidget.getPinColor(widget.getPinType("result", false)));

        widget.prepareRenderLayout();
        assertEquals(dataLayoutOperations, widget.getChildLayoutOperationCount());
    }
}
