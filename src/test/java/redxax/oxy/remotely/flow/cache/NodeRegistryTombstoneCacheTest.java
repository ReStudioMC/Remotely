package redxax.oxy.remotely.flow.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryTombstoneCacheTest {
    @TempDir
    Path tempDirectory;

    @Test
    void unresolvedExtensionSchemasSurviveOfflineRestorationAndRemainServerScoped() {
        Path path = tempDirectory.resolve("tombstones.json");
        NodeDefinition definition = new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.DATA).build();
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("request");
        payload.setChecksum("request-a");
        payload.setNodes(List.of(definition));
        NodeRegistryTombstoneCache cache = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        cache.replace("server-a", List.of(payload));
        NodeRegistryTombstoneCache restored = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        assertEquals("request", restored.get("server-a").getFirst().getPluginId());
        assertEquals("request:quest_info", restored.get("server-a").getFirst().getNodes().getFirst().getId());
        assertTrue(restored.get("server-b").isEmpty());

        restored.replace("server-a", List.of());
        NodeRegistryTombstoneCache cleared = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        assertTrue(cleared.get("server-a").isEmpty());
    }

    @Test
    void staleTombstonesDoNotMatchAReplacementRegistry() {
        Path path = tempDirectory.resolve("stale-tombstones.json");
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("request");
        payload.setNodes(List.of(new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.DATA).build()));
        NodeRegistryTombstoneCache cache = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.replace("server-a", "registry-a", new NodeRegistry.SnapshotAuthority("server-a", 4L, "catalog-a"),
            "projection-a", List.of(payload)));

        NodeRegistrySnapshot replacement = new NodeRegistrySnapshot();
        replacement.setRegistryChecksum("registry-b");
        replacement.setCatalogGeneration(5L);
        replacement.setCatalogChecksum("catalog-b");
        replacement.setCatalogProjectionIdentity("projection-b");

        assertFalse(cache.snapshot("server-a").matches(replacement));
        assertFalse(cache.replace("server-a", "registry-c",
            new NodeRegistry.SnapshotAuthority("server-a", 3L, "catalog-c"), "projection-c", List.of(payload)));
        assertEquals(4L, cache.snapshot("server-a").catalogGeneration());
    }

    @Test
    void failedPersistenceDoesNotAdvanceTombstones() throws Exception {
        Path blockedPath = tempDirectory.resolve("blocked-tombstones");
        Files.createDirectory(blockedPath);
        NodeRegistryTombstoneCache cache = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(blockedPath));
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("request");
        payload.setNodes(List.of(new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.DATA).build()));

        assertFalse(cache.replace("server-a", List.of(payload)));
        assertTrue(cache.get("server-a").isEmpty());
    }

    @Test
    void failedClearDoesNotAdvanceTombstones() throws Exception {
        Path path = tempDirectory.resolve("clear-tombstones.json");
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("request");
        payload.setNodes(List.of(new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.DATA).build()));
        NodeRegistryTombstoneCache cache = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.replace("server-a", List.of(payload)));

        Files.delete(path);
        Files.createDirectory(path);

        assertFalse(cache.clear("server-a"));
        assertTrue(cache.isQuarantined("server-a"));
        assertTrue(cache.get("server-a").isEmpty());
    }

    @Test
    void failedInvalidationQuarantinesThePersistedAuthorityAcrossRestart() throws Exception {
        Path path = tempDirectory.resolve("failed-invalidation-tombstones.json");
        NodeRegistryTombstoneCache cache = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.replace("server-a", "registry-a", new NodeRegistry.SnapshotAuthority("server-a", 4L, "catalog-a"),
            "projection-a", List.of(payload())));
        Files.createDirectory(path.resolveSibling("failed-invalidation-tombstones.json.tmp"));

        assertFalse(cache.invalidateServer("server-a", "publication failed"));
        assertTrue(cache.isQuarantined("server-a"));
        assertTrue(cache.get("server-a").isEmpty());

        NodeRegistryTombstoneCache restarted = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(restarted.isQuarantined("server-a"));
        assertTrue(restarted.get("server-a").isEmpty());
    }

    @Test
    void restoreReturnsThePreviousAuthorityBoundTombstones() {
        Path path = tempDirectory.resolve("restore-tombstones.json");
        NodePluginPayload payload = payload();
        NodeRegistryTombstoneCache cache = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.replace("server-a", "registry-a", new NodeRegistry.SnapshotAuthority("server-a", 4L, "catalog-a"),
            "projection-a", List.of(payload)));
        NodeRegistryTombstoneCache.TombstoneSnapshot previous = cache.snapshot("server-a");
        assertTrue(cache.replace("server-a", "registry-b", new NodeRegistry.SnapshotAuthority("server-a", 5L, "catalog-b"),
            "projection-b", List.of(payload)));

        assertTrue(cache.restore("server-a", previous));
        assertEquals(4L, cache.snapshot("server-a").catalogGeneration());
        assertEquals("catalog-a", new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).snapshot("server-a").catalogChecksum());
    }

    @Test
    void explicitInvalidationRemovesTombstoneAuthorityAcrossRestart() {
        Path path = tempDirectory.resolve("invalidated-tombstones.json");
        NodeRegistryTombstoneCache cache = new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(cache.replace("server-a", "registry-a", new NodeRegistry.SnapshotAuthority("server-a", 4L, "catalog-a"),
            "projection-a", List.of(payload())));

        assertTrue(cache.invalidateServer("server-a", "publication failed"));
        assertTrue(cache.get("server-a").isEmpty());
        assertTrue(new NodeRegistryTombstoneCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).get("server-a").isEmpty());
    }

    private NodePluginPayload payload() {
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("request");
        payload.setNodes(List.of(new NodeDefinition.Builder("request:quest_info", "Quest Info", NodeDefinition.NodeCategory.DATA).build()));
        return payload;
    }
}
