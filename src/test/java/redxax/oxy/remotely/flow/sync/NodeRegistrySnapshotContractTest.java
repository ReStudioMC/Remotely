package redxax.oxy.remotely.flow.sync;

import restudio.resync.flow.contract.FlowTypeMetadata;
import restudio.resync.protocol.ReSyncProtocolContract;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistrySnapshotContractTest {
    @Test
    void matchesTheServerRegistryContractEnvelope() {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setServerIdentity("server-a");
        snapshot.setCompatibleUntil(1234L);
        snapshot.setCapabilities(List.of("nodes", "resources"));
        snapshot.setRegistryDiagnostics(Map.of("rejectedDefinitions", 0));

        assertEquals(2, snapshot.getContractVersion());
        assertEquals(2, snapshot.getMinimumClientContractVersion());
        assertEquals("server-a", snapshot.getServerIdentity());
        assertEquals(1234L, snapshot.getCompatibleUntil());
        assertEquals(List.of("nodes", "resources"), snapshot.getCapabilities());
        assertEquals(0, snapshot.getRegistryDiagnostics().get("rejectedDefinitions"));
    }

    @Test
    void rejectsDeltasFromAnotherRegistryBaseline() {
        NodeRegistrySnapshot delta = new NodeRegistrySnapshot();
        delta.setFullSync(false);
        delta.setBaseRegistryChecksum("registry-a");

        assertTrue(delta.canApplyTo("registry-a"));
        assertEquals(false, delta.canApplyTo("registry-b"));
    }

    @Test
    void matchesTheServerRegistryRequestBaselineContract() {
        NodeRegistryRequest request = new NodeRegistryRequest();
        request.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        request.setRegistryChecksum("registry-a");
        request.setPluginChecksums(Map.of("request", "plugin-a"));
        request.setCompatibilityCapability(NodeRegistryRequest.LEGACY_COMPATIBILITY_CAPABILITY);

        NodeRegistryRequest restored = new Gson().fromJson(new Gson().toJson(request), NodeRegistryRequest.class);

        assertEquals(2, restored.getContractVersion());
        assertEquals("registry-a", restored.getRegistryChecksum());
        assertEquals("plugin-a", restored.getPluginChecksums().get("request"));
        assertEquals(NodeRegistryRequest.LEGACY_COMPATIBILITY_CAPABILITY, restored.getCompatibilityCapability());
        assertTrue(restored.requestsLegacyCompatibility());
    }

    @Test
    void acceptsTemporalClockDomainsFromTheServerSnapshot() {
        NodeRegistrySnapshot snapshot = new Gson().fromJson("""
            {"plugins":[{"pluginId":"builtin","nodes":[{"clockDomain":"wall_time"}]}]}
            """, NodeRegistrySnapshot.class);

        assertEquals("wall_time", snapshot.getPlugins().getFirst().getNodes().getFirst().getClockDomain());
    }

    @Test
    void acceptsServerScopedExtensionValidatorDiagnostics() {
        NodeRegistrySnapshot snapshot = new Gson().fromJson("""
            {
              "serverIdentity":"server-a",
              "capabilities":["extensions","diagnostics","extension_validators"],
              "registryDiagnostics":{
                "extensionContributionInventory":[{"pluginId":"request","validators":["request:quest_references"]}],
                "validatorInventory":[{"id":"request:quest_references","owner":"request"}]
              }
            }
            """, NodeRegistrySnapshot.class);

        assertTrue(snapshot.getCapabilities().contains("extension_validators"));
        assertEquals("request:quest_references", inventoryValue(snapshot, "validatorInventory", "id"));
        assertEquals("request", inventoryValue(snapshot, "extensionContributionInventory", "pluginId"));
    }

    @Test
    void readsTheSharedCrossRepositoryRegistryFixture() throws Exception {
        NodeRegistrySnapshot snapshot = new Gson().fromJson(Files.readString(Path.of("contracts", "node-registry-v2.fixture.json")), NodeRegistrySnapshot.class);

        assertEquals(2, snapshot.getContractVersion());
        assertEquals("server-a", snapshot.getServerIdentity());
        assertEquals("request", snapshot.getPlugins().getFirst().getPluginId());
        assertEquals("request:quest", snapshot.getTypeMetadata().getFirst().getId());
        assertEquals("request:quest", snapshot.getResourceMetadata().getFirst().getTypeId());
        assertTrue(snapshot.canApplyTo("any-baseline"));
        assertEquals("available", snapshot.getResourceMetadata().getFirst().getOperationAvailability().get("save"));
        assertEquals("Quest IDs", snapshot.getOptionSourceMetadata().getFirst().getDisplayName());
        assertEquals("request:quest", snapshot.getOptionSourceMetadata().getFirst().getValueType());
        assertEquals(List.of("player"), snapshot.getOptionSourceMetadata().getFirst().getContextKeys());
        assertEquals("request:quest_references", inventoryValue(snapshot, "validatorInventory", "id"));
    }

    @Test
    void catalogValueTypeResolvesFromAuthoritativeTypeMetadata() {
        FlowTypeMetadata type = new FlowTypeMetadata();
        type.setId("request:quest");
        type.setCatalogSource("request:quest_ids");
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setTypeMetadata(List.of(type));
        snapshot.setOptionSourceMetadata(List.of(new FlowOptionSourceMetadata(
            "request:quest_ids", "request", "SEARCHABLE_LIST", true)));

        FlowOptionSourceMetadata source = snapshot.getOptionSourceMetadata().getFirst();

        assertEquals("Quest IDs", source.getDisplayName());
        assertEquals("request:quest", source.getValueType());
    }

    private Object inventoryValue(NodeRegistrySnapshot snapshot, String inventory, String key) {
        List<?> values = (List<?>) snapshot.getRegistryDiagnostics().get(inventory);
        return ((Map<?, ?>) values.getFirst()).get(key);
    }
}
