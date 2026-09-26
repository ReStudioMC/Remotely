package redxax.oxy.remotely.flow.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class CustomContentGraphAdapterRootTest {
    @Test
    void armorContentKeepsItsOriginalRootWhenAnotherArmorNodeIsAdded() {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("armorContent", "armor", "Armor Content");
        FlowNode root = CustomContentGraphAdapter.findStartNode(graph);
        FlowNode added = new FlowNode(CustomContentGraphAdapter.ARMOR_NODE, 400, 200,
            Map.of("content_id", "new_armor"));
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        nodes.put("added", added);
        nodes.putAll(graph.getNodes());
        graph.setNodes(nodes);

        assertSame(root, CustomContentGraphAdapter.findStartNode(graph));
        graph.getContentProperties().put("content_id", "armorContent");
        assertSame(root, CustomContentGraphAdapter.findStartNode(graph));
    }
}
