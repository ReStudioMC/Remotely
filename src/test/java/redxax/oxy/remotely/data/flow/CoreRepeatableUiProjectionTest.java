package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRepeatableUiProjectionTest {
    @Test
    void projectsOrderedElementsAsStablePinAndElementTuples() {
        PinId valuePin = PinId.of("choice");
        PinId flowPin = PinId.of("matched-flow");
        RepeatableGroupId groupId = RepeatableGroupId.of("cases");
        RepeatableElementId first = RepeatableElementId.deterministic("repeatable-first");
        RepeatableElementId second = RepeatableElementId.deterministic("repeatable-second");
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        RepeatableBinding binding = new RepeatableBinding(groupId, true, List.of(
            new RepeatableElement(first, Map.of(valuePin,
                new PinValue(valuePin, TypedValue.value(string, "first")))),
            new RepeatableElement(second, Map.of(valuePin,
                new PinValue(valuePin, TypedValue.value(string, "second"))))));
        GraphNode node = new GraphNode(NodeInstanceId.deterministic("repeatable-node"),
            ContractRef.of(OwnerId.of("test"), NodeId.of("repeatable")), 1, null, Map.of(), Map.of(), List.of(),
            List.of(binding), InspectorState.empty(), 0, 0, OpaqueData.empty());
        NodeDefinition definition = new NodeDefinition.Builder("repeatable", "Repeatable",
            NodeDefinition.NodeCategory.FLOW).owner("test")
            .input(new NodeDefinition.PinBuilder(valuePin, "Choice", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).typedType(string)
                .repeatable("cases", 1, 4, "Case", true).build())
            .output(new NodeDefinition.PinBuilder(flowPin, "Matched", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
                .repeatable("cases", 1, 4, "Case", true).build())
            .build();

        CoreRepeatableUiProjection.Projection projection = CoreRepeatableUiProjection.project(node, definition);
        CoreRepeatableUiProjection.Group group = projection.group(groupId).orElseThrow();

        assertTrue(projection.available());
        assertEquals(List.of(valuePin, flowPin), group.members().stream()
            .map(NodeDefinition.PinDefinition::getId).toList());
        assertEquals(List.of(first, second), group.elements().stream()
            .map(CoreRepeatableUiProjection.Element::elementId).toList());
        assertEquals("second", group.element(second).orElseThrow().value(valuePin).orElseThrow().value().value());
        assertEquals(new CoreRepeatableUiProjection.PinEndpoint(flowPin, first),
            new CoreRepeatableUiProjection.PinEndpoint(flowPin, first));
    }
}
