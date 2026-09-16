package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreRepeatableUiProjection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeWidgetPinLayoutTest {
    private static final int INPUT_COUNT = 500;
    private static final int OUTPUT_COUNT = 500;
    private static final int PIN_COUNT = INPUT_COUNT + OUTPUT_COUNT;

    @BeforeAll
    static void initializeTheme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void thousandPinLayoutIsLinearAndRetainsExactBoundsAndHitTesting() {
        NodeDefinition.Builder builder = new NodeDefinition.Builder("pin-layout", "Pin Layout", NodeDefinition.NodeCategory.FLOW)
            .owner("builtin");
        List<String> branches = new ArrayList<>();
        for (int index = 0; index < INPUT_COUNT; index++) {
            builder.input("input-" + index, NodeDefinition.PinType.DATA, FlowDataType.ENTITY);
        }
        for (int index = 0; index < OUTPUT_COUNT; index++) {
            String id = "output-" + index;
            builder.output(id, NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
            branches.add(id);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("__flow_branches", branches);
        NodeWidget widget = new NodeWidget(40, 70, new FlowNode("builtin:pin-layout", 40, 70, values),
            new FlowGraph(), "node", null, null, null, builder.build(), false);

        long layoutOperations = widget.getPinLayoutOperationCount();
        assertTrue(layoutOperations <= PIN_COUNT * 4L, () -> "Expected linear layout work, observed " + layoutOperations);

        double[] firstInput = widget.getPinBounds("input-0", true);
        double[] middleInput = widget.getPinBounds("input-250", true);
        double[] lastInput = widget.getPinBounds("input-499", true);
        double[] firstOutput = widget.getPinBounds("output-0", false);
        double[] lastOutput = widget.getPinBounds("output-499", false);
        assertNotNull(firstInput);
        assertNotNull(middleInput);
        assertNotNull(lastInput);
        assertNotNull(firstOutput);
        assertNotNull(lastOutput);
        assertEquals(46.0, firstInput[0]);
        assertEquals(firstInput[1] + 250 * 24.0, middleInput[1]);
        assertEquals(firstInput[1] + 499 * 24.0, lastInput[1]);
        assertEquals(firstOutput[1] + 499 * 24.0, lastOutput[1]);
        assertEquals(widget.getX() + widget.getWidth() - 16.0, firstOutput[0]);

        assertEquals("input-0", widget.getInputPinAtPosition(centerX(firstInput), centerY(firstInput)));
        assertEquals("input-250", widget.getInputPinAtPosition(centerX(middleInput), centerY(middleInput)));
        assertEquals("input-499", widget.getPinAtPosition(centerX(lastInput), centerY(lastInput)));
        assertEquals("output-0", widget.getOutputPinAtPosition(centerX(firstOutput), centerY(firstOutput)));
        assertEquals("output-499", widget.getPinAtPosition(centerX(lastOutput), centerY(lastOutput)));

        for (int index = 0; index < INPUT_COUNT; index++) {
            assertNotNull(widget.getPinBounds("input-" + index, true));
        }
        for (int index = 0; index < OUTPUT_COUNT; index++) {
            assertNotNull(widget.getPinBounds("output-" + index, false));
        }
        assertEquals(layoutOperations, widget.getPinLayoutOperationCount());
    }

    @Test
    void coreWidgetShowsEveryExecutionOutputWithoutLegacyBranchState() {
        NodeDefinition definition = new NodeDefinition.Builder("core-outputs", "Core Outputs",
            NodeDefinition.NodeCategory.FLOW).owner("test")
            .output("success", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("failure", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .output("timeout", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .build();
        FlowNode node = new FlowNode("test:core-outputs", 10, 20, new LinkedHashMap<>());
        NodeWidget widget = new NodeWidget(10, 20, node, new FlowGraph(), "node", null, null, null,
            definition, false, false, mutation -> true);

        assertEquals(List.of("success", "failure", "timeout"), widget.getVisibleOutputPins());
        assertNotNull(widget.getPinBounds("success", false));
        assertNotNull(widget.getPinBounds("failure", false));
        assertNotNull(widget.getPinBounds("timeout", false));
        assertFalse(node.getInputValues().containsKey("__flow_branches"));
    }

    @Test
    void repeatableOptionContextKeepsPendingValuesInsideTheSelectedElement() throws Exception {
        PinId mode = PinId.of("mode");
        PinId choice = PinId.of("choice");
        PinId ordinary = PinId.of("ordinary");
        PinId foreign = PinId.of("foreign");
        RepeatableElementId first = RepeatableElementId.deterministic("context-first");
        RepeatableElementId second = RepeatableElementId.deterministic("context-second");
        RepeatableGroupId cases = RepeatableGroupId.of("cases");
        RepeatableGroupId other = RepeatableGroupId.of("other");
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        NodeDefinition definition = new NodeDefinition.Builder("repeatable-context", "Repeatable Context",
            NodeDefinition.NodeCategory.FLOW).owner("test")
            .input(new NodeDefinition.PinBuilder(mode, "Mode", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .repeatable("cases", 0, 4, "Case", true).build())
            .input(new NodeDefinition.PinBuilder(choice, "Choice", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .repeatable("cases", 0, 4, "Case", true).build())
            .input(new NodeDefinition.PinBuilder(foreign, "Foreign", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .repeatable("other", 0, 4, "Other", true).build())
            .input(new NodeDefinition.PinBuilder(ordinary, "Ordinary", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).build())
            .build();
        NodeInstanceId nodeId = NodeInstanceId.deterministic("repeatable-context-node");
        GraphNode coreNode = new GraphNode(nodeId, ContractRef.of(OwnerId.of("test"), NodeId.of("repeatable-context")),
            1, null, Map.of(), Map.of(), List.of(), List.of(
                new RepeatableBinding(cases, true, List.of(
                    new RepeatableElement(first, Map.of(mode,
                        new PinValue(mode, TypedValue.value(string, "committed-first")))),
                    new RepeatableElement(second, Map.of()))),
                new RepeatableBinding(other, true, List.of(new RepeatableElement(first, Map.of(foreign,
                    new PinValue(foreign, TypedValue.value(string, "committed-foreign"))))))),
            InspectorState.empty(), 10, 20, OpaqueData.empty());
        FlowNode node = new FlowNode("test:repeatable-context", 10, 20,
            new LinkedHashMap<>(Map.of(ordinary.canonicalText(), "ordinary")));
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("repeatable-context"),
            ContractRef.of(OwnerId.of("test"), ResourceTypeId.of("flow")), "repeatable-context");
        NodeWidget widget = new NodeWidget(10, 20, node, new FlowGraph(), nodeId.canonicalText(), null, null, null,
            definition, false, false, mutation -> true, resource, Map.of());
        CoreRepeatableUiProjection.Projection projection = CoreRepeatableUiProjection.project(coreNode, definition);
        assertTrue(widget.configureCoreRepeatables(projection, mutation -> true));

        Field previewsField = NodeWidget.class.getDeclaredField("inputValuePreviews");
        previewsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, NodeWidget.NodeValueMutation> previews =
            (Map<String, NodeWidget.NodeValueMutation>) previewsField.get(widget);
        previews.put("case", new NodeWidget.NodeValueMutation(nodeId, mode, first, "pending-first", false));
        previews.put("foreign", new NodeWidget.NodeValueMutation(nodeId, foreign, first, "pending-foreign", false));
        previews.put("ordinary", new NodeWidget.NodeValueMutation(nodeId, ordinary, "pending-ordinary", false));

        Map<String, Object> firstContext = optionCatalogContext(widget, widget.coreViewPin(choice, first));
        Map<String, Object> secondContext = optionCatalogContext(widget, widget.coreViewPin(choice, second));
        Map<String, Object> ordinaryContext = optionCatalogContext(widget, ordinary.canonicalText());

        assertEquals(choice.canonicalText(), firstContext.get("$pin"));
        assertEquals("pending-first", firstContext.get(mode.canonicalText()));
        assertFalse(firstContext.containsKey(foreign.canonicalText()));
        assertFalse(secondContext.containsKey(mode.canonicalText()));
        assertFalse(secondContext.containsKey(foreign.canonicalText()));
        assertEquals("pending-ordinary", secondContext.get(ordinary.canonicalText()));
        assertFalse(ordinaryContext.containsKey(mode.canonicalText()));
        assertFalse(ordinaryContext.containsKey(foreign.canonicalText()));
        assertFalse(ordinaryContext.containsKey(ordinary.canonicalText()));
        assertEquals(ordinary.canonicalText(), ordinaryContext.get("$pin"));
        assertFalse(widget.configureCoreRepeatables(CoreRepeatableUiProjection.project(coreNode, definition),
            mutation -> true));
    }

    @Test
    void coreRepeatableLayoutUsesStableElementEndpointsWithoutLegacyCounts() {
        PinId inputPin = PinId.of("choice");
        PinId filterPin = PinId.of("filter");
        PinId outputPin = PinId.of("matched");
        RepeatableElementId first = RepeatableElementId.deterministic("layout-first");
        RepeatableElementId second = RepeatableElementId.deterministic("layout-second");
        RepeatableGroupId groupId = RepeatableGroupId.of("cases");
        RepeatableGroupId filterGroupId = RepeatableGroupId.of("filters");
        NodeDefinition definition = new NodeDefinition.Builder("repeatable-layout", "Repeatable Layout",
            NodeDefinition.NodeCategory.FLOW).owner("test")
            .input(new NodeDefinition.PinBuilder(inputPin, "Choice", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .repeatable("cases", 1, 4, "Case", true).build())
            .input(new NodeDefinition.PinBuilder(filterPin, "Filter", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .repeatable("filters", 1, 4, "Filter", true).build())
            .output(new NodeDefinition.PinBuilder(outputPin, "Matched", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
                .repeatable("cases", 1, 4, "Case", true).build())
            .build();
        NodeInstanceId nodeId = NodeInstanceId.deterministic("repeatable-layout-node");
        GraphNode coreNode = new GraphNode(nodeId, ContractRef.of(OwnerId.of("test"), NodeId.of("repeatable-layout")),
            1, null, Map.of(), Map.of(), List.of(), List.of(new RepeatableBinding(groupId, true, List.of(
                new RepeatableElement(first, Map.of()), new RepeatableElement(second, Map.of()))),
                new RepeatableBinding(filterGroupId, true, List.of(new RepeatableElement(first, Map.of())))),
            InspectorState.empty(), 10, 20, OpaqueData.empty());
        CoreRepeatableUiProjection.Projection projection = CoreRepeatableUiProjection.project(coreNode, definition);
        FlowNode node = new FlowNode("test:repeatable-layout", 10, 20, new LinkedHashMap<>());
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("repeatable-layout"),
            ContractRef.of(OwnerId.of("test"), ResourceTypeId.of("flow")), "repeatable-layout");
        NodeWidget widget = new NodeWidget(10, 20, node, new FlowGraph(), nodeId.canonicalText(), null, null, null,
            definition, false, false, mutation -> true, resource, Map.of());
        widget.configureCoreRepeatables(projection, mutation -> true);

        String firstInput = widget.coreViewPin(inputPin, first);
        String secondInput = widget.coreViewPin(inputPin, second);
        String filterInput = widget.coreViewPin(filterPin, first);
        String firstOutput = widget.coreViewPin(outputPin, first);
        String secondOutput = widget.coreViewPin(outputPin, second);

        assertNotNull(widget.getPinBounds(firstInput, true));
        assertNotNull(widget.getPinBounds(secondInput, true));
        assertNotNull(widget.getPinBounds(filterInput, true));
        assertNotNull(widget.getPinBounds(firstOutput, false));
        assertNotNull(widget.getPinBounds(secondOutput, false));
        assertEquals(new CoreRepeatableUiProjection.PinEndpoint(inputPin, second),
            widget.corePinEndpoint(secondInput));
        assertNotEquals(firstInput, filterInput);
        assertEquals(new CoreRepeatableUiProjection.PinEndpoint(filterPin, first), widget.corePinEndpoint(filterInput));
        assertEquals(List.of(firstInput, secondInput, filterInput), widget.getVisibleInputPins());
        assertEquals(List.of(firstOutput, secondOutput), widget.getVisibleOutputPins());
        assertFalse(node.getInputValues().containsKey("__repeatable_count:cases"));
        assertFalse(node.getInputValues().containsKey("__flow_branches"));
    }

    @Test
    void repeatablePinRemovalRebuildsRowsWithoutChangingRemainingIdentity() {
        NodeDefinition definition = new NodeDefinition.Builder("repeatable-layout", "Repeatable Layout", NodeDefinition.NodeCategory.FLOW)
            .owner("builtin")
            .input(new NodeDefinition.PinBuilder("item", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT,
                FlowDataType.ENTITY).repeatable("items", 1, 3, "Item").build())
            .build();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("__repeatable_count:items", 3);
        NodeWidget widget = new NodeWidget(10, 20, new FlowNode("builtin:repeatable-layout", 10, 20, values),
            new FlowGraph(), "node", null, null, null, definition, false);

        double[] first = widget.getPinBounds("item", true);
        double[] thirdBefore = widget.getPinBounds("item_3", true);
        assertNotNull(first);
        assertNotNull(thirdBefore);
        assertEquals(first[1] + 48.0, thirdBefore[1]);

        assertTrue(widget.removeOptionalInputPin("item_2"));

        double[] thirdAfter = widget.getPinBounds("item_3", true);
        assertNull(widget.getPinBounds("item_2", true));
        assertNotNull(thirdAfter);
        assertEquals(first[1] + 24.0, thirdAfter[1]);
        assertEquals(List.of("item", "item_3"), widget.getVisibleInputPins());
        assertEquals("item_3", widget.getInputPinAtPosition(centerX(thirdAfter), centerY(thirdAfter)));
    }

    @Test
    void conditionalPinBoundsFollowPresentationWithoutDeletingHiddenConnections() {
        NodeDefinition.PinDefinition mode = new NodeDefinition.PinBuilder("mode", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).widget(NodeDefinition.WidgetType.TEXT).build();
        NodeDefinition.PinDefinition conditional = new NodeDefinition.PinBuilder("conditional",
            NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.STRING)
            .visibleWhen("mode", "on").build();
        NodeDefinition definition = new NodeDefinition.Builder("conditional-layout", "Conditional Layout",
            NodeDefinition.NodeCategory.UTILITY).owner("test").input(mode).output(conditional).build();
        FlowNode node = new FlowNode("test:conditional-layout", 10, 20,
            new LinkedHashMap<>(Map.of("mode", "off")));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of("node", node)));
        graph.setConnections(new ArrayList<>(List.of(new FlowConnection("node", "conditional", "target", "value"))));
        NodeWidget widget = new NodeWidget(10, 20, node, graph, "node", null, null, null, definition, false);

        assertNull(widget.getPinBounds("conditional", false));
        assertEquals(1, graph.getConnections().size());

        node.getInputValues().put("mode", "on");
        widget.refreshInputWidgets();
        assertNotNull(widget.getPinBounds("conditional", false));
        assertEquals(1, graph.getConnections().size());

        node.getInputValues().put("mode", "off");
        widget.refreshInputWidgets();
        assertNull(widget.getPinBounds("conditional", false));
        assertEquals(1, graph.getConnections().size());
    }

    private static int centerX(double[] bounds) {
        return (int) (bounds[0] + bounds[2] / 2);
    }

    private static Map<String, Object> optionCatalogContext(NodeWidget widget, String pinId) throws Exception {
        Field inputsField = NodeWidget.class.getDeclaredField("inputs");
        inputsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<NodeDefinition.PinDefinition> inputs =
            (List<NodeDefinition.PinDefinition>) inputsField.get(widget);
        NodeDefinition.PinDefinition input = inputs.stream()
            .filter(candidate -> candidate.getId().canonicalText().equals(pinId)).findFirst().orElseThrow();
        Method contextMethod = NodeWidget.class.getDeclaredMethod("optionCatalogContext",
            NodeDefinition.PinDefinition.class);
        contextMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) contextMethod.invoke(widget, input);
        return context;
    }

    private static int centerY(double[] bounds) {
        return (int) (bounds[1] + bounds[3] / 2);
    }
}
