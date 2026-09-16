package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphEditorOrganizeGraphTest {
    @Test
    void nullValuedAndMissingNodesSortAfterValidNodesWithoutDereference() {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        nodes.put("lower", new FlowNode("action", 10, 40, Map.of()));
        nodes.put("null-entry", null);
        nodes.put("upper", new FlowNode("action", 30, 20, Map.of()));
        List<String> nodeIds = new ArrayList<>(List.of("missing-entry", "lower", "null-entry", "upper"));

        assertDoesNotThrow(() -> GraphEditorScreen.sortOrganizeNodes(nodeIds, nodes, ignored -> 50));

        assertEquals(List.of("upper", "lower", "missing-entry", "null-entry"), nodeIds);
    }
}
