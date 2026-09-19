package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.identity.PinId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeWidgetPassthroughPinTest {
    @Test
    void passthroughOutputKeepsEditorNameWithoutUsingItAsPinId() {
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder(
            "condition",
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT,
            FlowDataType.BOOLEAN
        ).displayName("Condition").build();

        NodeDefinition.PinDefinition output = EditorPassthroughPins.outputDefinition(input);

        assertEquals("condition", output.getId().value());
        assertEquals("Condition", output.getDisplayName());
        assertEquals("__passthrough:condition", EditorPassthroughPins.pinId(output));
        assertEquals("__passthrough:condition", EditorPassthroughPins.outputPin("condition"));
        assertTrue(EditorPassthroughPins.isOutputPin(EditorPassthroughPins.pinId(output)));
        assertTrue(EditorPassthroughPins.isOutputDefinition(output));
        assertNotEquals(EditorPassthroughPins.pinId(output), output.getId().value());
        assertThrows(IllegalArgumentException.class, () -> PinId.of("__passthrough:condition"));
    }
}
