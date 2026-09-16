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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeWidgetCapabilityAuthorityTest {
    @BeforeAll
    static void initializeTheme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void unsupportedDescriptorCapabilityIsReadOnlyAndDoesNotSeedState() {
        FlowNode node = new FlowNode("extension:dynamic", 0, 0, new LinkedHashMap<>());
        FlowNodeWidget widget = widget(node, descriptor(), true);
        widget.refreshInputWidgets();

        assertTrue(widget.isEditorReadOnly());
        assertEquals("Required descriptor capability is unavailable.", widget.getDefinitionReadOnlyReason());
        assertTrue(node.getInputValues().isEmpty());
        assertFalse(node.getInputValues().containsKey("__flow_branches"));
        assertEquals(List.of("first", "second", "third"), widget.getVisibleOutputPins());
    }

    @Test
    void legacySpecializedStateIsPreservedWithoutBecomingRuntimeAuthority() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("__function_signature", Map.of("inputs", Map.of("old", "string")));
        state.put("custom", "kept");
        FlowNode node = new FlowNode("extension:dynamic", 0, 0, state);
        Map<String, Object> before = Map.copyOf(node.getInputValues());
        FlowNodeWidget widget = widget(node, descriptor(), false);

        assertTrue(widget.isEditorReadOnly());
        assertEquals("Function interaction capability is unavailable.", widget.getDefinitionReadOnlyReason());
        assertEquals(before, node.getInputValues());
        assertFalse(node.getInputValues().containsKey("__flow_branches"));
    }

    private FlowNodeWidget widget(FlowNode node, NodeDefinition definition, boolean readOnly) {
        return new FlowNodeWidget(0, 0, node, new FlowGraph(), "node", null, null, null,
            FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), definition, readOnly, false);
    }

    private NodeDefinition descriptor() {
        return new NodeDefinition.Builder("dynamic", "Dynamic", NodeDefinition.NodeCategory.FLOW)
            .owner("extension")
            .input(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).defaultValue("created").build())
            .output("first", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("second", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("third", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .build();
    }
}
