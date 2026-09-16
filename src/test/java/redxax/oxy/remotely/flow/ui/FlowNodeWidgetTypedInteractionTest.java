package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.render.TextRenderer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowNodeWidgetTypedInteractionTest {
    @BeforeAll
    static void initializeTheme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void typedProjectionDrivesDynamicParametersAndCompleteBoundaryEditing() {
        FlowGraph graph = new FlowGraph();
        graph.getFunctionInputs().add(new FlowGraph.FunctionParameter("value", FlowDataType.STRING));
        FlowNode start = new FlowNode("builtin:function.start", 0, 0, Map.of());
        FlowNodeWidget.FunctionBoundaryCatalog catalog = catalog(true);
        FlowNodeWidget widget = new FlowNodeWidget(0, 0, start, graph, "start", null, null, null, catalog,
            definition("function.start", true), false, false);

        assertEquals(FlowTypeRef.parse("extension:custom_value"), widget.getPinTypeRef("value", false));
        assertEquals(FlowTypeRef.simple("execution"), widget.getPinTypeRef("next", false));
        assertFalse(widget.isBoundaryReadOnly());
        assertFalse(widget.isEditorReadOnly());
    }

    @Test
    void typedDescriptorExecutionPinIsRenderedWithCatalogIdentity() {
        FlowNode start = new FlowNode("builtin:function.start", 0, 0, Map.of());
        FlowNodeWidget widget = new FlowNodeWidget(0, 0, start, new FlowGraph(), "start", null, null, null,
            catalog(true), definition("function.start", true), false, false);
        FlowNode end = new FlowNode("builtin:function.end", 0, 0, Map.of());
        FlowNodeWidget endWidget = new FlowNodeWidget(0, 0, end, new FlowGraph(), "end", null, null, null,
            catalog(true), definition("function.end", false), false, false);

        assertTrue(widget.getVisibleOutputPins().contains("next"));
        assertFalse(widget.getVisibleOutputPins().contains("flow"));
        assertTrue(endWidget.getVisibleInputPins().contains("previous"));
        assertFalse(endWidget.getVisibleInputPins().contains("flow"));
    }

    @Test
    void customCallSelectionRefreshesStableSignaturePins() {
        String serverId = "custom-call-refresh";
        NodeRegistry registry = NodeRegistry.getInstance();
        if (registry == null) {
            registry = new NodeRegistry();
        }
        registry.registerServerDefinition(serverId, new NodeDefinition.Builder(
                "custom_function:reward", "Reward", NodeDefinition.NodeCategory.FUNCTION)
            .input("amount", NodeDefinition.PinType.DATA, FlowDataType.NUMBER)
            .output("granted", NodeDefinition.PinType.DATA, FlowDataType.BOOLEAN)
            .build());
        try {
            FlowNode call = new FlowNode("call_function", 0, 0, new HashMap<>(Map.of("function", "reward")));
            NodeDefinition callDefinition = new NodeDefinition.Builder(
                    "call_function", "Call Function", NodeDefinition.NodeCategory.FUNCTION)
                .input("function", NodeDefinition.PinType.DATA, FlowDataType.FUNCTION)
                .input("arguments", NodeDefinition.PinType.DATA, FlowDataType.ANY)
                .output("result", NodeDefinition.PinType.DATA, FlowDataType.RESULT)
                .build();
            FlowNodeWidget widget = new FlowNodeWidget(0, 0, call, new FlowGraph(), "call", serverId, null, null,
                FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), callDefinition, false, false);

            assertTrue(widget.getVisibleInputPins().contains("amount"));
            assertFalse(widget.getVisibleInputPins().contains("arguments"));
            assertTrue(widget.getVisibleOutputPins().contains("granted"));
            assertTrue(widget.getVisibleOutputPins().contains("result"));
        } finally {
            registry.clearServer(serverId);
        }
    }

    @Test
    void incompleteTypedProjectionKeepsBoundaryReadOnly() {
        FlowGraph graph = new FlowGraph();
        FlowNode start = new FlowNode("builtin:function.start", 0, 0, Map.of());
        FlowNodeWidget widget = new FlowNodeWidget(0, 0, start, graph, "start", null, null, null, catalog(false),
            definition("function.start", true), false, false);

        assertTrue(widget.isBoundaryReadOnly());
        assertTrue(widget.isEditorReadOnly());
    }

    @Test
    void typedBoundaryDoesNotInferUndeclaredGraphParameters() {
        FlowGraph graph = new FlowGraph();
        graph.getFunctionInputs().add(new FlowGraph.FunctionParameter("stable-value", "Value", FlowDataType.STRING));
        FlowNode start = new FlowNode("builtin:function.start", 0, 0, Map.of());
        FlowNodeWidget.FunctionBoundaryCatalog catalog = FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("function.start")), "next", List.of()),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("function.end")), "previous", List.of()));

        assertNull(FlowNodeWidget.resolveFunctionParameterType(start, graph, "Value", false, catalog));
        assertEquals(FlowTypeRef.simple("string"),
            FlowNodeWidget.resolveFunctionParameterType(start, graph, "stable-value", false, catalog));
    }

    @Test
    void removingStableBoundaryParameterRemovesPrefixedConnections() {
        FlowGraph graph = new FlowGraph();
        graph.getFunctionInputs().add(new FlowGraph.FunctionParameter("stable-value", "Value", FlowDataType.STRING));
        graph.getConnections().add(FlowConnection.stable("start", "function-output-stable-value", "target", "value"));
        graph.getConnections().add(FlowConnection.stable("other", "function-output-stable-value", "target", "value"));
        FlowNode start = new FlowNode("builtin:function.start", 0, 0, Map.of());
        FlowNodeWidget widget = new FlowNodeWidget(0, 0, start, graph, "start", null, null, null, catalog(true),
            definition("function.start", true), false, false);

        widget.removeFunctionParameter("stable-value");

        assertTrue(graph.getFunctionInputs().isEmpty());
        assertEquals(1, graph.getConnections().size());
        assertEquals("other", graph.getConnections().getFirst().getSourceNodeId());
    }

    @Test
    void removingStableOutputParameterRemovesPrefixedInputConnection() {
        FlowGraph graph = new FlowGraph();
        graph.getFunctionOutputs().add(new FlowGraph.FunctionParameter("stable-output", "Output", FlowDataType.STRING));
        graph.getConnections().add(FlowConnection.stable("source", "value", "end", "function-input-stable-output"));
        FlowNode end = new FlowNode("builtin:function.end", 0, 0, Map.of());
        FlowNodeWidget widget = new FlowNodeWidget(0, 0, end, graph, "end", null, null, null, catalog(true),
            definition("function.end", false), false, false);

        widget.removeFunctionParameter("stable-output");

        assertTrue(graph.getFunctionOutputs().isEmpty());
        assertTrue(graph.getConnections().isEmpty());
    }

    private static FlowNodeWidget.FunctionBoundaryCatalog catalog(boolean complete) {
        FlowNodeWidget.FunctionBoundaryIntent input = new FlowNodeWidget.FunctionBoundaryIntent(
            FlowNodeWidget.FunctionBoundaryRole.INPUTS,
            ContractRef.of(new OwnerId("builtin"), new NodeId("function.start")), "next",
            List.of(new FlowNodeWidget.FunctionParameterPin("value", "value", FlowTypeRef.parse("extension:custom_value"))));
        FlowNodeWidget.FunctionBoundaryIntent output = complete ? new FlowNodeWidget.FunctionBoundaryIntent(
            FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
            ContractRef.of(new OwnerId("builtin"), new NodeId("function.end")), "previous", List.of()) : null;
        return FlowNodeWidget.FunctionBoundaryCatalog.of(input, output);
    }

    private static NodeDefinition definition(String id, boolean output) {
        NodeDefinition.Builder builder = new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.FLOW)
            .owner("builtin");
        if (output) {
            builder.output("flow", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
        }
        return builder.build();
    }
}
