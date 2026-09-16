package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphNode;
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
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphAuthoringAdapterLocatorTest {
    private static final ServerId SERVER = ServerId.deterministic("core-authoring-locator");
    private static final OwnerId NODE_OWNER = OwnerId.of("restudio.resync");
    private static final OwnerId GRAPH_OWNER = OwnerId.of("restudio.test");
    private static final String NODE_ID = "resource_consumer";
    private static final String RESOURCE_OWNER = "test";
    private static final String RESOURCE_TYPE = "variable_definition";
    private static final String RESOURCE_ID = "score";
    private static final PinId PIN = PinId.of("resource");
    private static final NodeInstanceId INSTANCE = NodeInstanceId.deterministic("core-authoring-locator-node");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));

    @Test
    void newResourcePinIsEncodedAsCanonicalLocatorState() {
        CoreGraphAuthoringAdapter.Result result = new CoreGraphAuthoringAdapter().convert(
            graph(locator(SERVER, RESOURCE_OWNER, RESOURCE_TYPE)), context());

        assertTrue(result.lossless(), result.reason());
        GraphNode node = result.graphDocument().nodes().getFirst();
        TypedValue value = node.values().get(PIN).value();
        assertEquals(TypedValue.State.LOCATOR, value.state());
        assertEquals(SERVER, value.locator().serverId());
        assertEquals(RESOURCE_OWNER, value.locator().type().owner().canonicalText());
        assertEquals(RESOURCE_TYPE, value.locator().type().id().canonicalText());
        assertEquals(RESOURCE_ID, value.locator().id());
    }

    @Test
    void newResourcePinRejectsRawTextAndMismatchedTypes() {
        CoreGraphAuthoringAdapter adapter = new CoreGraphAuthoringAdapter();

        assertFalse(adapter.convert(graph(RESOURCE_ID), context()).lossless());
        assertFalse(adapter.convert(graph(locator(SERVER, "other", RESOURCE_TYPE)), context()).lossless());
        assertFalse(adapter.convert(graph(locator(ServerId.deterministic("foreign-server"), RESOURCE_OWNER,
            RESOURCE_TYPE)), context()).lossless());
    }

    private static FlowGraph graph(Object value) {
        FlowNode node = new FlowNode(NODE_OWNER.canonicalText() + ":" + NODE_ID, 0, 0,
            Map.of(PIN.canonicalText(), value));
        FlowGraph graph = new FlowGraph("locator", Map.of(INSTANCE.canonicalText(), node), List.of(), List.of());
        graph.setResourceType("flow");
        return graph;
    }

    private static CoreGraphAuthoringAdapter.Context context() {
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(
            ContractRef.of(NODE_OWNER, NodeId.of(NODE_ID)), 1, CatalogCacheState.ACTIVE, Set.of(), false,
            new CatalogCacheOpaque(CanonicalJson.canonicalBytes(Map.of(
                "id", NODE_ID,
                "schemaVersion", 2,
                "pins", List.of(Map.of("id", PIN.canonicalText(), "type", Map.of(
                    "kind", "resource",
                    "resourceType", Map.of("ownerId", RESOURCE_OWNER, "localId", RESOURCE_TYPE))))))));
        return CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW, graphResource(), BINDING,
            new CatalogVersion(1, 0), List.of(entry), 0);
    }

    private static ServerResourceLocator graphResource() {
        return new ServerResourceLocator(SERVER, ContractRef.of(GRAPH_OWNER, ResourceTypeId.of("flow")), "locator");
    }

    private static Map<String, Object> locator(ServerId server, String owner, String type) {
        return Map.of("serverId", server.canonicalText(), "type", Map.of("ownerId", owner, "localId", type),
            "id", RESOURCE_ID);
    }
}
