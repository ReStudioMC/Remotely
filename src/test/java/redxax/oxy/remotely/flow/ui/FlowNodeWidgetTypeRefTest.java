package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowNodeWidgetTypeRefTest {
    @Test
    void functionBoundaryPinsPreserveGenericTypeReferences() {
        FlowGraph graph = new FlowGraph();
        FlowGraph.FunctionParameter input = new FlowGraph.FunctionParameter("permissions", FlowDataType.LIST);
        input.setTypeRef(FlowTypeRef.parse("list<permission>"));
        FlowGraph.FunctionParameter output = new FlowGraph.FunctionParameter("lookup", FlowDataType.MAP);
        output.setTypeRef(FlowTypeRef.parse("map<string,resource_reference<gui>>"));
        graph.getFunctionInputs().add(input);
        graph.getFunctionOutputs().add(output);

        FlowNode start = new FlowNode("catalog.function.entry", 0, 0, Map.of());
        FlowNode end = new FlowNode("catalog.function.exit", 0, 0, Map.of());
        FlowNodeWidget.FunctionBoundaryCatalog catalog = FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS, "catalog.function.entry", "next"),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS, "catalog.function.exit", "previous")
        );

        assertEquals(FlowTypeRef.parse("list<permission>"), FlowNodeWidget.resolveFunctionParameterType(start, graph, "permissions", false, catalog));
        assertEquals(FlowTypeRef.parse("map<string,resource_reference<gui>>"), FlowNodeWidget.resolveFunctionParameterType(end, graph, "lookup", true, catalog));
        assertNull(FlowNodeWidget.resolveFunctionParameterType(start, graph, "permissions", true, catalog));
        assertNull(FlowNodeWidget.resolveFunctionParameterType(end, graph, "flow", true, catalog));
        assertNull(FlowNodeWidget.resolveFunctionParameterType(start, graph, "permissions", false));
    }
}
