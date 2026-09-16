package redxax.oxy.remotely.flow.cache;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectionDiagnosticsRemainScopedToTheirServer() {
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("registry.json")));
        NodeRegistrySnapshot incompatible = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version() + 1, "invalid");

        cache.applySnapshot("server-a", incompatible);

        assertTrue(cache.getDiagnostic("server-a").invalidationReason().contains("Rejected incompatible"));
        assertEquals("", cache.getDiagnostic("server-b").invalidationReason());
        assertFalse(cache.getDiagnostic("server-a").present());
    }

    @Test
    void acceptedSnapshotPersistsAtomicallyAndClearsPriorRejection() {
        Path path = tempDir.resolve("registry.json");
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        cache.applySnapshot("server-a", snapshot("another-server", ReSyncProtocolContract.FLOW_CONTRACT.version(), "wrong"));
        assertFalse(cache.getDiagnostic("server-a").invalidationReason().isBlank());

        NodeRegistrySnapshot accepted = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a");
        accepted.setNodeIds(List.of("flow.start"));
        accepted.setRegistryDiagnostics(Map.of("parity", true));
        cache.applySnapshot("server-a", accepted);

        assertEquals("", cache.getDiagnostic("server-a").invalidationReason());
        assertTrue(Files.exists(path));
        assertFalse(Files.exists(path.resolveSibling("registry.json.tmp")));

        NodeRegistrySnapshot restored = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).getSnapshot("server-a");
        assertNotNull(restored);
        assertEquals("registry-a", restored.getRegistryChecksum());
        assertEquals(List.of("flow.start"), restored.getNodeIds());
        assertEquals(true, restored.getRegistryDiagnostics().get("parity"));
    }

    @Test
    void typedPublicationMetadataAndOpaqueFieldsSurviveRestart() {
        Path path = tempDir.resolve("typed-registry.json");
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        NodeRegistrySnapshot snapshot = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a");
        snapshot.setCatalogGeneration(7L);
        snapshot.setCatalogChecksum("catalog-a");
        snapshot.setCatalogProjectionIdentity("projection-a");
        snapshot.setDropContributions(List.of(Map.of("owner", "extension", "nodeId", "extension.run")));
        snapshot.setFunctionBoundaries(List.of(Map.of("role", "inputs", "owner", "extension", "nodeId", "extension.entry")));
        snapshot.setCatalogMetadata(Map.of("bindingManifestHash", "projection-a"));
        snapshot.setOpaqueData(Map.of("unknownField", Map.of("value", "preserved")));

        assertTrue(cache.applySnapshot("server-a", snapshot));
        NodeRegistrySnapshot restored = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).getSnapshot("server-a");

        assertEquals(7L, restored.getCatalogGeneration());
        assertEquals("catalog-a", restored.getCatalogChecksum());
        assertEquals("projection-a", restored.getCatalogProjectionIdentity());
        assertEquals("extension", restored.getDropContributions().getFirst().get("owner"));
        assertEquals("inputs", restored.getFunctionBoundaries().getFirst().get("role"));
        assertEquals("projection-a", restored.getCatalogMetadata().get("bindingManifestHash"));
        assertEquals("preserved", ((Map<?, ?>) restored.getOpaqueData().get("unknownField")).get("value"));

        NodeRegistrySnapshot stale = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-stale");
        stale.setCatalogGeneration(6L);
        stale.setCatalogChecksum("catalog-stale");
        stale.setCatalogProjectionIdentity("projection-stale");
        assertFalse(cache.applySnapshot("server-a", stale));
        assertEquals(7L, cache.getSnapshot("server-a").getCatalogGeneration());
    }

    @Test
    void failedPersistenceDoesNotAdvanceTheInMemoryCache() throws Exception {
        Path blockedPath = tempDir.resolve("blocked-cache");
        Files.createDirectory(blockedPath);
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(blockedPath));

        assertFalse(cache.applySnapshot("server-a", snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a")));
        assertFalse(cache.getDiagnostic("server-a").present());
    }

    @Test
    void failedClearDoesNotAdvanceTheInMemoryCache() throws Exception {
        Path path = tempDir.resolve("clear-cache.json");
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.applySnapshot("server-a", snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a")));

        Files.delete(path);
        Files.createDirectory(path);

        assertFalse(cache.clearServer("server-a"));
        assertTrue(cache.isQuarantined("server-a"));
        assertFalse(cache.getDiagnostic("server-a").present());
    }

    @Test
    void failedInvalidationQuarantinesThePersistedAuthorityAcrossRestart() throws Exception {
        Path path = tempDir.resolve("failed-invalidation-cache.json");
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.applySnapshot("server-a", snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a")));
        Files.createDirectory(path.resolveSibling("failed-invalidation-cache.json.tmp"));

        assertFalse(cache.invalidateServer("server-a", "publication failed"));
        assertTrue(cache.isQuarantined("server-a"));
        assertFalse(cache.getDiagnostic("server-a").present());

        NodeRegistryCache restarted = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(restarted.isQuarantined("server-a"));
        assertFalse(restarted.getDiagnostic("server-a").present());
    }

    @Test
    void checkpointRestoresThePreviousTypedSnapshotAfterANewerPublication() {
        Path path = tempDir.resolve("checkpoint-cache.json");
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        NodeRegistrySnapshot previous = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a");
        previous.setCatalogGeneration(4L);
        previous.setCatalogChecksum("catalog-a");
        previous.setCatalogProjectionIdentity("projection-a");
        previous.setOpaqueData(Map.of("unknown", Map.of("keep", true)));
        assertTrue(cache.applySnapshot("server-a", previous));
        NodeRegistryCache.CacheCheckpoint checkpoint = cache.capture("server-a");

        NodeRegistrySnapshot newer = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-b");
        newer.setCatalogGeneration(5L);
        newer.setCatalogChecksum("catalog-b");
        newer.setCatalogProjectionIdentity("projection-b");
        assertTrue(cache.applySnapshot("server-a", newer));
        assertTrue(cache.restore("server-a", checkpoint));

        assertEquals("registry-a", cache.getSnapshot("server-a").getRegistryChecksum());
        assertEquals(4L, cache.getSnapshot("server-a").getCatalogGeneration());
        assertEquals(true, ((Map<?, ?>) cache.getSnapshot("server-a").getOpaqueData().get("unknown")).get("keep"));
    }

    @Test
    void explicitInvalidationRemovesCachedAuthorityAndPersistsItsReason() {
        Path path = tempDir.resolve("invalidated-cache.json");
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.applySnapshot("server-a", snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a")));

        assertTrue(cache.invalidateServer("server-a", "publication failed"));
        assertFalse(cache.getDiagnostic("server-a").present());
        assertEquals("publication failed", new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).getDiagnostic("server-a").invalidationReason());
    }

    private NodeRegistrySnapshot snapshot(String serverId, int contractVersion, String checksum) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(contractVersion);
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setServerIdentity(serverId);
        snapshot.setFullSync(true);
        snapshot.setRegistryChecksum(checksum);
        snapshot.setGeneratedAt(1L);
        snapshot.setCapabilities(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
        return snapshot;
    }

    @Test
    void rejectsVersionTwoSnapshotMissingRequiredFlowCapability() {
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("missing-capability.json")));
        NodeRegistrySnapshot snapshot = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a");
        snapshot.setCapabilities(List.of("nodes"));

        assertFalse(cache.applySnapshot("server-a", snapshot));
        assertTrue(cache.getDiagnostic("server-a").invalidationReason().contains("incompatible"));
        assertFalse(cache.getDiagnostic("server-a").present());
    }

    @Test
    void rejectsHydratedCacheMissingRequiredFlowCapability() throws Exception {
        Path path = tempDir.resolve("hydrated-missing-capability.json");
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.applySnapshot("server-a", snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a")));

        var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var server = root.getAsJsonObject("servers").getAsJsonObject("server-a");
        server.add("capabilities", new JsonArray());
        server.getAsJsonArray("capabilities").add("nodes");
        Files.writeString(path, new Gson().toJson(root));

        NodeRegistryCache hydrated = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        assertFalse(hydrated.getDiagnostic("server-a").present());
        assertTrue(hydrated.getDiagnostic("server-a").invalidationReason().contains("invalid"));
    }

    @Test
    void rejectsCheckpointRestoreMissingRequiredFlowCapability() {
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("restore-missing-capability.json")));
        NodeRegistrySnapshot snapshot = snapshot("server-a", ReSyncProtocolContract.FLOW_CONTRACT.version(), "registry-a");
        snapshot.setCapabilities(List.of("nodes"));

        assertFalse(cache.restore("server-a", new NodeRegistryCache.CacheCheckpoint(snapshot, "")));
        assertFalse(cache.getDiagnostic("server-a").present());
    }
}
