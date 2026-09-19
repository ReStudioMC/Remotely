package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.flow.registry.NodeDefinition;

final class EditorPassthroughPins {
    static final String OUTPUT_PREFIX = "__passthrough:";
    static final String OUTPUT_MARKER = "editor.passthrough";

    private EditorPassthroughPins() {
    }

    static boolean isOutputPin(String pinName) {
        return pinName != null && pinName.startsWith(OUTPUT_PREFIX);
    }

    static String outputPin(String inputPin) {
        return OUTPUT_PREFIX + inputPin;
    }

    static String inputPin(String outputPin) {
        if (!isOutputPin(outputPin)) {
            return outputPin;
        }
        return outputPin.substring(OUTPUT_PREFIX.length());
    }

    static boolean isOutputDefinition(NodeDefinition.PinDefinition pin) {
        return pin != null
            && pin.getDirection() == NodeDefinition.PinDirection.OUTPUT
            && OUTPUT_MARKER.equals(pin.getDescription());
    }

    static String pinId(NodeDefinition.PinDefinition pin) {
        if (pin == null || pin.getId() == null) {
            return "";
        }
        String value = pin.getId().value();
        if (isOutputDefinition(pin)) {
            return outputPin(value);
        }
        return value;
    }

    static NodeDefinition.PinDefinition outputDefinition(NodeDefinition.PinDefinition input) {
        String displayName = input.getDisplayName() != null && !input.getDisplayName().isBlank()
            ? input.getDisplayName() : pinId(input);
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(
            input.getId(),
            displayName,
            NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.OUTPUT,
            input.getDataType()
        ).description(OUTPUT_MARKER);
        if (input.getTypeRef() != null) {
            builder.typeRef(input.getTypeRef());
        }
        return builder.build();
    }
}
