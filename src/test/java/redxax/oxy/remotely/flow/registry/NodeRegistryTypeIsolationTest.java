package redxax.oxy.remotely.flow.registry;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import restudio.resync.flow.contract.FlowTypeMetadata;
import restudio.resync.protocol.ReSyncProtocolContract;
import redxax.oxy.remotely.flow.sync.FlowConversionRule;
import redxax.oxy.remotely.flow.sync.FlowResourceMetadata;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryTypeIsolationTest {
    @Test
    void serverTypesRemainIsolatedAndUnknownTypesStayExplicit() {
        NodeRegistry registry = new NodeRegistry();
        FlowTypeMetadata typeA = new FlowTypeMetadata("ext:token", "Token A", 0xFF112233, "string", true, true, false);
        typeA.setOwner("extension-a");
        FlowTypeMetadata typeB = new FlowTypeMetadata("ext:token", "Token B", 0xFF445566, "number", false, false, true);
        typeB.setOwner("extension-b");
        registry.applySnapshot("server-a", snapshot(typeA));
        registry.applySnapshot("server-b", snapshot(typeB));

        FlowDataType serverA = registry.resolveType("server-a", "ext:token");
        FlowDataType serverB = registry.resolveType("server-b", "ext:token");
        FlowDataType unresolved = FlowDataType.fromString("ext:token");

        assertNotSame(serverA, serverB);
        assertEquals(FlowDataType.STRING, serverA.getParent());
        assertEquals(FlowDataType.NUMBER, serverB.getParent());
        assertEquals("extension-a", serverA.getOwner());
        assertEquals("extension-b", serverB.getOwner());
        assertTrue(serverA.isResolved());
        assertTrue(serverB.isResolved());
        assertFalse(unresolved.isResolved());

        registry.clearServer("server-a");

        assertFalse(registry.resolveType("server-a", "ext:token").isResolved());
        assertTrue(registry.resolveType("server-b", "ext:token").isResolved());
    }

    @Test
    void resourceCapabilitiesRemainScopedToTheirServer() {
        NodeRegistry registry = new NodeRegistry();
        FlowResourceMetadata available = resource("trade_profile", true, List.of("discover", "get", "save"));
        FlowResourceMetadata unavailable = resource("trade_profile", false, List.of());
        NodeRegistrySnapshot first = new NodeRegistrySnapshot();
        compatible(first);
        first.setFullSync(true);
        first.setResourceMetadata(List.of(available));
        NodeRegistrySnapshot second = new NodeRegistrySnapshot();
        compatible(second);
        second.setFullSync(true);
        second.setResourceMetadata(List.of(unavailable));

        registry.applySnapshot("server-a", first);
        registry.applySnapshot("server-b", second);

        assertTrue(registry.getResourceMetadata("server-a", "trade_profile").isAvailable());
        assertFalse(registry.getResourceMetadata("server-b", "trade_profile").isAvailable());
        assertEquals(List.of("discover", "get", "save"), registry.getResourceMetadata("server-a", "trade_profile").getOperations());
    }

    @Test
    void extensionValidatorDiagnosticsRemainScopedToTheirServer() {
        NodeRegistry registry = new NodeRegistry();
        NodeRegistrySnapshot first = new NodeRegistrySnapshot();
        compatible(first);
        first.setFullSync(true);
        first.setRegistryDiagnostics(Map.of("validatorInventory", List.of(Map.of("id", "request:quests", "owner", "request"))));
        NodeRegistrySnapshot second = new NodeRegistrySnapshot();
        compatible(second);
        second.setFullSync(true);
        second.setRegistryDiagnostics(Map.of("validatorInventory", List.of(Map.of("id", "economy:accounts", "owner", "economy"))));

        registry.applySnapshot("server-a", first);
        registry.applySnapshot("server-b", second);

        assertTrue(registry.getRegistrySessionMetadata("server-a").diagnostics().toString().contains("request:quests"));
        assertFalse(registry.getRegistrySessionMetadata("server-a").diagnostics().toString().contains("economy:accounts"));
        assertTrue(registry.getRegistrySessionMetadata("server-b").diagnostics().toString().contains("economy:accounts"));
        registry.clearServer("server-a");
        assertNull(registry.getRegistrySessionMetadata("server-a"));
        assertNotNull(registry.getRegistrySessionMetadata("server-b"));
    }

    @Test
    void staleRegistryDeltaCannotReplaceTheActiveServerContract() {
        NodeRegistry registry = new NodeRegistry();
        FlowTypeMetadata original = new FlowTypeMetadata("ext:token", "Original", 0xFF112233, "string", true, true, false);
        FlowTypeMetadata replacement = new FlowTypeMetadata("ext:token", "Replacement", 0xFF445566, "number", false, false, true);
        NodeRegistrySnapshot baseline = snapshot(original);
        baseline.setRegistryChecksum("registry-a");
        assertTrue(registry.applySnapshot("server-a", baseline));

        NodeRegistrySnapshot stale = snapshot(replacement);
        stale.setFullSync(false);
        stale.setBaseRegistryChecksum("registry-stale");
        stale.setRegistryChecksum("registry-b");
        assertFalse(registry.applySnapshot("server-a", stale));

        assertEquals("registry-a", registry.getRegistrySessionMetadata("server-a").checksum());
        assertEquals(FlowDataType.STRING, registry.resolveType("server-a", "ext:token").getParent());

        stale.setBaseRegistryChecksum("registry-a");
        assertTrue(registry.applySnapshot("server-a", stale));

        assertEquals("registry-b", registry.getRegistrySessionMetadata("server-a").checksum());
        assertEquals(FlowDataType.NUMBER, registry.resolveType("server-a", "ext:token").getParent());
    }

    @Test
    void removedExtensionDefinitionsRemainRecoverableUntilThePluginReturns() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition definition = new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.DATA).build();
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("request");
        payload.setChecksum("request-a");
        payload.setNodes(List.of(definition));
        NodeRegistrySnapshot baseline = new NodeRegistrySnapshot();
        compatible(baseline);
        baseline.setFullSync(true);
        baseline.setRegistryChecksum("registry-a");
        baseline.setNodeIds(List.of("request:quest_info"));
        baseline.setPlugins(List.of(payload));
        registry.applySnapshot("server-a", baseline);

        NodeRegistrySnapshot removed = new NodeRegistrySnapshot();
        compatible(removed);
        removed.setFullSync(false);
        removed.setBaseRegistryChecksum("registry-a");
        removed.setRegistryChecksum("registry-b");
        removed.setNodeIds(List.of());
        removed.setRemovedPlugins(List.of("request"));
        registry.applySnapshot("server-a", removed);

        assertTrue(registry.getAllDefinitions("server-a").isEmpty());
        assertEquals(List.of("request"), registry.getUnresolvedPluginIds("server-a"));
        assertEquals("Quest Info", registry.getDefinition("server-a", "request:quest_info").getDisplayName());
        NodeRegistrySnapshot cacheSnapshot = registry.materializeSnapshot("server-a", removed);
        assertTrue(cacheSnapshot.isFullSync());
        assertTrue(cacheSnapshot.getNodeIds().isEmpty());
        assertTrue(cacheSnapshot.getPlugins().isEmpty());
        assertTrue(cacheSnapshot.getRemovedPlugins().isEmpty());
        assertEquals("registry-b", cacheSnapshot.getRegistryChecksum());

        NodeRegistrySnapshot restored = new NodeRegistrySnapshot();
        compatible(restored);
        restored.setFullSync(false);
        restored.setBaseRegistryChecksum("registry-b");
        restored.setRegistryChecksum("registry-c");
        restored.setNodeIds(List.of("request:quest_info"));
        restored.setPlugins(List.of(payload));
        registry.applySnapshot("server-a", restored);

        assertTrue(registry.getUnresolvedPluginIds("server-a").isEmpty());
        assertNotNull(registry.getAllDefinitions("server-a").get("builtin:request:quest_info"));
    }

    @Test
    void conversionLookupReturnsTheLowestCostAvailableRule() {
        NodeRegistry registry = new NodeRegistry();
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        compatible(snapshot);
        snapshot.setFullSync(true);
        snapshot.setConversionRules(List.of(
            new FlowConversionRule("number", "string", "slow", false, true, 5, "available"),
            new FlowConversionRule("number", "string", "fast", false, true, 2, "available"),
            new FlowConversionRule("number", "string", "offline", true, false, 1, "unavailable")
        ));
        registry.applySnapshot("server-a", snapshot);

        FlowConversionRule rule = registry.findConversionRule("server-a", FlowDataType.NUMBER, FlowDataType.STRING);

        assertNotNull(rule);
        assertEquals("fast", rule.getImplementationId());
        assertEquals(2, rule.getCost());
        assertTrue(rule.isLossy());
    }

    @Test
    void genericTypeVariablesAcceptCandidateEditorConnections() {
        NodeRegistry registry = new NodeRegistry();

        assertTrue(registry.canAssignTypes("server-a", FlowTypeRef.parse("list<player>"), FlowTypeRef.parse("list<type:t>")));
        assertTrue(registry.canAssignTypes("server-a", FlowTypeRef.parse("type:t"), FlowTypeRef.parse("world")));
        assertFalse(registry.canAssignTypes("server-a", FlowTypeRef.parse("list<player>"), FlowTypeRef.parse("list<world>")));
    }

    private NodeRegistrySnapshot snapshot(FlowTypeMetadata metadata) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        compatible(snapshot);
        snapshot.setFullSync(true);
        snapshot.setTypeMetadata(List.of(metadata));
        return snapshot;
    }

    private void compatible(NodeRegistrySnapshot snapshot) {
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setCapabilities(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
    }

    @Test
    void rejectsVersionTwoSnapshotMissingRequiredFlowCapabilityBeforeActivation() {
        NodeRegistry registry = new NodeRegistry();
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setCapabilities(List.of("nodes"));

        assertNull(registry.stageSnapshot("server-a", snapshot, null));
    }

    private FlowResourceMetadata resource(String type, boolean available, List<String> operations) {
        FlowResourceMetadata metadata = new FlowResourceMetadata();
        metadata.setTypeId(type);
        metadata.setAvailable(available);
        metadata.setOperations(operations);
        return metadata;
    }
}
