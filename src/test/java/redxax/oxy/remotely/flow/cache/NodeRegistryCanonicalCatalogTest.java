package redxax.oxy.remotely.flow.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.protocol.ReSyncProtocolContract;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryCanonicalCatalogTest {
    @TempDir
    Path tempDirectory;

    @Test
    void cacheRetainsUnknownDescriptorsAndPinsFromTheCanonicalCatalog() {
        NodeRegistrySnapshot snapshot = canonicalSnapshot();
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("registry.json")));

        assertTrue(snapshot.hasValidCanonicalCatalog());
        assertTrue(cache.applySnapshot("server-a", snapshot));

        NodeRegistrySnapshot restored = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("registry.json"))).getSnapshot("server-a");
        Map<?, ?> descriptor = (Map<?, ?>) ((List<?>) restored.getCanonicalCatalogDocument().get("definitions")).getFirst();
        Map<?, ?> pin = (Map<?, ?>) ((List<?>) ((Map<?, ?>) descriptor.get("descriptor")).get("pins")).getFirst();

        assertEquals("future.node", descriptor.get("descriptorId"));
        assertEquals("future.pin", pin.get("id"));
        assertEquals(Map.of("kind", "future-type"), pin.get("futurePinType"));
        assertEquals(snapshot.getCanonicalCatalogContent(), restored.getCanonicalCatalogContent());
    }

    @Test
    void tamperedCanonicalCatalogIsRejectedBeforeCacheMutation() {
        NodeRegistrySnapshot snapshot = canonicalSnapshot();
        NodeRegistryCache cache = new NodeRegistryCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("tampered.json")));
        String tampered = snapshot.getCanonicalCatalogContent().replace("future.pin", "tampered.pin");
        snapshot.setOpaqueData(Map.of("catalogMetadata", Map.of(
            NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY, tampered,
            "bindingManifestHash", snapshot.getCatalogProjectionIdentity())));

        assertFalse(snapshot.hasValidCanonicalCatalog());
        assertFalse(cache.applySnapshot("server-a", snapshot));
        assertFalse(cache.getDiagnostic("server-a").present());
        assertTrue(cache.getDiagnostic("server-a").invalidationReason().contains("canonical catalog"));
    }

    @Test
    void tombstonesRejectTamperedCanonicalPluginPayloads() {
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("future-extension");
        payload.setNodes(List.of(new NodeDefinition.Builder("future.node", "Future Node", NodeDefinition.NodeCategory.FLOW).build()));
        payload.setOpaqueData(Map.of(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY, "{\"contentChecksum\":\"tampered\"}"));

        assertFalse(NodeRegistryTombstoneCache.validPayloads(List.of(payload)));
    }

    private NodeRegistrySnapshot canonicalSnapshot() {
        CatalogSnapshot base = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        Map<String, Object> document = new LinkedHashMap<>((Map<String, Object>) CanonicalJson.parse(base.canonicalContent()));
        List<Object> definitions = new ArrayList<>((List<?>) document.get("definitions"));
        definitions.add(Map.of(
            "owner", "extension",
            "descriptorId", "future.node",
            "descriptor", Map.of(
                "id", "future.node",
                "pins", List.of(Map.of(
                    "id", "future.pin",
                    "futurePinType", Map.of("kind", "future-type"))))));
        document.put("definitions", definitions);
        document.remove("contentChecksum");
        document.put("contentChecksum", "");
        String withoutChecksum = CanonicalJson.canonicalize(document);
        document.put("contentChecksum", CatalogCanonicalizer.checksumForCanonicalContent(withoutChecksum).canonicalText());
        String content = CanonicalJson.canonicalize(document);

        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setServerIdentity("server-a");
        List<String> capabilities = new ArrayList<>(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
        capabilities.addAll(List.of("catalog_authority", "catalog_canonical"));
        snapshot.setCapabilities(capabilities);
        snapshot.setFullSync(true);
        snapshot.setCatalogGeneration(base.generation());
        snapshot.setCatalogChecksum(CatalogCanonicalizer.checksumForCanonicalContent(content).canonicalText());
        snapshot.setCatalogProjectionIdentity(base.bindingManifestHash().canonicalText());
        snapshot.setCatalogMetadata(Map.of("bindingManifestHash", base.bindingManifestHash().canonicalText()));
        snapshot.setOpaqueData(Map.of("catalogMetadata", Map.of(
            NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY, content,
            "bindingManifestHash", base.bindingManifestHash().canonicalText())));
        return snapshot;
    }
}
