package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreGraphAuthoringRepeatableTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ContractRef<NodeId> NODE = ContractRef.of(OWNER, NodeId.of("repeatable"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        ServerId.parseCanonicalText("11111111-1111-4111-8111-111111111111"),
        ContractRef.of(OWNER, ResourceTypeId.of("flow")), "sample");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @Test
    void creationSeedsMinimumWithStableDistinctElementsAndExactTypedDefaults() {
        CoreGraphAuthoringAdapter adapter = new CoreGraphAuthoringAdapter();
        GraphNode node = adapter.createNode(NodeInstanceId.interactive(), projection(), context());
        RepeatableBinding group = node.repeatables().getFirst();

        assertEquals("items", group.groupId().canonicalText());
        assertEquals(2, group.elements().size());
        assertTrue(group.ordered());
        assertFalse(node.values().containsKey(PinId.of("value")));
        assertNotEquals(group.elements().getFirst().elementId(), group.elements().getLast().elementId());
        group.elements().forEach(element -> {
            assertEquals("Hello", element.values().get(PinId.of("value")).value().value());
            assertEquals("kept", element.values().get(PinId.of("value")).value().unknown().get("future"));
        });
        GraphDocument document = document(node);
        String encoded = GraphDocumentCodec.INSTANCE.encodeText(document);
        assertEquals(encoded, GraphDocumentCodec.INSTANCE.encodeText(
            GraphDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(encoded))));
        GraphNode second = adapter.createNode(NodeInstanceId.interactive(), projection(), context());
        assertNotEquals(group.elements().getFirst().elementId(), second.repeatables().getFirst().elements().getFirst().elementId());
    }

    @Test
    void existingNodeConversionDoesNotRegenerateRepeatableIdentitiesOrReplaceValues() {
        CoreGraphAuthoringAdapter adapter = new CoreGraphAuthoringAdapter();
        GraphNode node = adapter.createNode(NodeInstanceId.interactive(), projection(), context());
        FlowGraph projection = new FlowGraph("sample", Map.of(node.instanceId().canonicalText(), projection()), List.of(), List.of());
        projection.setResourceType("flow");
        CoreGraphAuthoringAdapter.Result result = adapter.convert(projection,
            JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(document(node))).getAsJsonObject(), context());

        assertTrue(result.lossless(), result.reason());
        assertEquals(JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(document(node))).getAsJsonObject()
                .getAsJsonArray("nodes").get(0).getAsJsonObject().get("repeatables"),
            JsonParser.parseString(GraphDocumentCodec.INSTANCE.encodeText(result.graphDocument())).getAsJsonObject()
                .getAsJsonArray("nodes").get(0).getAsJsonObject().get("repeatables"));
    }

    @Test
    void creationHonorsTheCumulativeElementBudget() {
        CoreGraphAuthoringAdapter adapter = new CoreGraphAuthoringAdapter();
        GraphNode boundary = adapter.createNode(NodeInstanceId.interactive(), projection(), context(1024));
        assertEquals(1024, boundary.repeatables().getFirst().elements().size());
        GraphNode combinedBoundary = adapter.createNode(NodeInstanceId.interactive(), projection(), context(600, 424));
        assertEquals(1024, combinedBoundary.repeatables().stream().mapToInt(group -> group.elements().size()).sum());
        assertThrows(IllegalArgumentException.class,
            () -> adapter.createNode(NodeInstanceId.interactive(), projection(), context(1025)));
        assertThrows(IllegalArgumentException.class,
            () -> adapter.createNode(NodeInstanceId.interactive(), projection(), context(600, 425)));
    }

    private static FlowNode projection() {
        return new FlowNode(NODE.owner().canonicalText() + ":" + NODE.id().canonicalText(), 0, 0, Map.of());
    }

    private static GraphDocument document(GraphNode node) {
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 0, BINDING, Set.of(), List.of(node), List.of(),
            List.of(), OpaqueData.empty());
    }

    private static CoreGraphAuthoringAdapter.Context context() {
        return context(2);
    }

    private static CoreGraphAuthoringAdapter.Context context(int... minima) {
        Map<String, Object> type = Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", "string"),
            "arguments", List.of());
        List<Map<String, Object>> pins = new ArrayList<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (int index = 0; index < minima.length; index++) {
            String pinId = index == 0 ? "value" : "value_" + index;
            String groupId = index == 0 ? "items" : "items_" + index;
            pins.add(Map.of("id", pinId, "direction", "input", "type", type,
                "repeatable", Map.of("enabled", true, "groupId", groupId, "minimum", minima[index],
                    "maximum", minima[index] + 2, "ordered", true),
                "default", Map.of("state", "value", "type", type, "value", "Hello", "future", "kept")));
            groups.add(Map.of("id", groupId, "minimum", minima[index], "maximum", minima[index] + 2, "ordered", true,
                "members", List.of(Map.of("pinId", pinId, "direction", "input", "type", type))));
        }
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(NODE, 1, CatalogCacheState.ACTIVE,
            Set.of(), false, new CatalogCacheOpaque(CanonicalJson.canonicalBytes(Map.of("id", "repeatable", "schemaVersion", 2,
                "pins", pins, "repeatables", groups))));
        return CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW, RESOURCE, BINDING,
            new CatalogVersion(1, 0), List.of(entry), 0);
    }
}
