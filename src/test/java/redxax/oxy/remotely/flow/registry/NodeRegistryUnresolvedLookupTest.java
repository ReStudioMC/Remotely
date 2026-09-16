package redxax.oxy.remotely.flow.registry;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class NodeRegistryUnresolvedLookupTest {
    @Test
    void rawUnresolvedLookupRejectsAmbiguousOwnersRegardlessOfPayloadOrder() {
        NodeDefinition first = definition("shared.node", "extension-a");
        NodeDefinition second = definition("shared.node", "extension-b");

        NodeRegistry forward = registry(payload("extension-a", first), payload("extension-b", second));
        NodeRegistry reverse = registry(payload("extension-b", second), payload("extension-a", first));

        assertNull(forward.getDefinition("server-a", "shared.node"));
        assertNull(reverse.getDefinition("server-a", "shared.node"));
        assertSame(first, forward.getDefinition("server-a", "extension-a:shared.node"));
        assertSame(second, reverse.getDefinition("server-a", "extension-b:shared.node"));
    }

    @Test
    void rawUnresolvedLookupUsesTheOnlyCanonicalOwner() {
        NodeDefinition definition = definition("unique.node", "extension-a");
        NodeRegistry registry = registry(payload("extension-a", definition));

        assertSame(definition, registry.getDefinition("server-a", "unique.node"));
        assertSame(definition, registry.getUnresolvedDefinition("server-a", "unique.node"));
    }

    @Test
    void unresolvedCanonicalAliasesResolveByOwnerBeforeRawFallback() {
        NodeDefinition definition = new NodeDefinition.Builder("legacy.node", "Legacy", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-a")
            .canonicalId("current.node")
            .build();
        NodeRegistry registry = registry(payload("extension-a", definition));

        assertSame(definition, registry.getDefinition("server-a", "extension-a:current.node"));
        assertSame(definition, registry.getDefinition("server-a", "current.node"));
        assertNull(registry.getDefinition("server-a", "extension-b:current.node"));
    }

    @Test
    void legacyNamespacedUnresolvedIdsRetainTheirQualifiedIdentity() {
        NodeDefinition definition = new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.DATA).build();
        NodeRegistry registry = registry(payload("request", definition));

        assertSame(definition, registry.getDefinition("server-a", "request:quest_info"));
        assertNull(registry.getDefinition("server-a", "quest_info"));
        assertNull(registry.getDefinition("server-a", "other:quest_info"));
    }

    private NodeRegistry registry(NodePluginPayload... payloads) {
        NodeRegistry registry = new NodeRegistry();
        registry.restoreUnresolvedPlugins("server-a", List.of(payloads));
        return registry;
    }

    private NodePluginPayload payload(String pluginId, NodeDefinition definition) {
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId(pluginId);
        payload.setNodes(List.of(definition));
        return payload;
    }

    private NodeDefinition definition(String id, String owner) {
        return new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.FLOW).owner(owner).build();
    }
}
