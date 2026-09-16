package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowSerializer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedGraphCacheTest {
    @Test
    void identicalIdsRemainIsolatedByGraphType() {
        TypedGraphCache cache = new TypedGraphCache();
        FlowGraph flow = graph("shared", ReSyncResourceType.FLOW);
        FlowGraph function = graph("shared", ReSyncResourceType.FUNCTION);
        FlowGraph command = graph("shared", ReSyncResourceType.COMMAND);

        cache.cache("server", flow);
        cache.cache("server", function);
        cache.cache("server", command);

        assertGraphEquals(flow, cache.get("server", ReSyncResourceType.FLOW, "shared"));
        assertGraphEquals(function, cache.get("server", ReSyncResourceType.FUNCTION, "shared"));
        assertGraphEquals(command, cache.get("server", ReSyncResourceType.COMMAND, "shared"));
        assertNull(cache.get("server", "shared"));
    }

    @Test
    void authoritativeListPrunesOnlyItsOwnType() {
        TypedGraphCache cache = new TypedGraphCache();
        cache.cache("server", graph("shared", ReSyncResourceType.FLOW));
        cache.cache("server", graph("shared", ReSyncResourceType.FUNCTION));

        cache.applyServerList("server", ReSyncResourceType.FLOW, List.of());

        assertNull(cache.get("server", ReSyncResourceType.FLOW, "shared"));
        assertNotNull(cache.get("server", ReSyncResourceType.FUNCTION, "shared"));
    }

    @Test
    void serverIdsIncludesEveryTypedGraphStoreInDeterministicOrder() {
        TypedGraphCache cache = new TypedGraphCache();
        cache.putInDraft("z:server", graph("draft", ReSyncResourceType.FLOW));
        cache.cache("a:server", graph("cached", ReSyncResourceType.FUNCTION));

        assertEquals(List.of("a:server", "z:server"), new ArrayList<>(cache.serverIds()));
    }

    @Test
    void clearAllClearsEveryTypedGraphStore() {
        TypedGraphCache cache = new TypedGraphCache();
        cache.cache("server", graph("flow", ReSyncResourceType.FLOW));
        cache.putInDraft("server", graph("function", ReSyncResourceType.FUNCTION));

        cache.clearAll();

        assertTrue(cache.serverIds().isEmpty());
        assertTrue(cache.getForServer("server").isEmpty());
    }

    @Test
    void ownedPointLookupPreservesTypedIdentityWithoutMutableAliases() {
        TypedGraphCache cache = new TypedGraphCache();
        cache.cache("server", graph("shared/path", ReSyncResourceType.FLOW));
        cache.cache("server", graph("shared/path", ReSyncResourceType.FUNCTION));

        FlowGraph selected = cache.getOwned("server", ReSyncResourceType.FLOW, "shared/path");
        selected.setId("changed");
        selected.setResourceType(ReSyncResourceType.COMMAND.typeId());

        assertEquals("shared/path", cache.getOwned("server", ReSyncResourceType.FLOW, "shared/path").getId());
        assertEquals(ReSyncResourceType.FLOW.typeId(), cache.getOwned("server", ReSyncResourceType.FLOW, "shared/path").getResourceType());
        assertEquals(ReSyncResourceType.FUNCTION.typeId(), cache.getOwned("server", ReSyncResourceType.FUNCTION, "shared/path").getResourceType());
    }

    private FlowGraph graph(String id, ReSyncResourceType type) {
        FlowGraph graph = new FlowGraph();
        graph.setId(id);
        graph.setResourceType(type.typeId());
        graph.setFunction(type == ReSyncResourceType.FUNCTION);
        return graph;
    }

    private void assertGraphEquals(FlowGraph expected, FlowGraph actual) {
        assertNotNull(actual);
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getResourceType(), actual.getResourceType());
        assertEquals(FlowSerializer.serialize(expected), FlowSerializer.serialize(actual));
    }
}
