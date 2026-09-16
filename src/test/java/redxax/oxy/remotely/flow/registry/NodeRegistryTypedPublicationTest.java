package redxax.oxy.remotely.flow.registry;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryTypedPublicationTest {
    @Test
    void typedNodeLookupRequiresThePublishedOwner() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition definition = new NodeDefinition.Builder("extension.run", "Run", NodeDefinition.NodeCategory.FLOW)
            .owner("extension")
            .build();
        registry.registerServerDefinition("server-a", definition);

        assertSame(definition, registry.getDefinition("server-a", new NodeRegistry.NodeReference("extension", "extension.run")));
        assertNull(registry.getDefinition("server-a", new NodeRegistry.NodeReference("other", "extension.run")));
    }

    @Test
    void canonicalStringLookupUsesTheDefinitionOwner() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition definition = new NodeDefinition.Builder("shared.run", "Run", NodeDefinition.NodeCategory.FLOW)
            .owner("extension")
            .build();
        registry.registerServerDefinition("server-a", definition);

        assertSame(definition, registry.getDefinition("server-a", "extension:shared.run"));
    }

    @Test
    void rawLocalLookupRejectsMultipleOwners() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition first = new NodeDefinition.Builder("shared.run", "Run A", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-a")
            .build();
        NodeDefinition second = new NodeDefinition.Builder("shared.run", "Run B", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-b")
            .build();
        registry.registerServerDefinition("server-a", first);
        registry.registerServerDefinition("server-a", second);

        assertNull(registry.getDefinition("server-a", "shared.run"));
        assertSame(first, registry.getDefinition("server-a", "extension-a:shared.run"));
        assertSame(second, registry.getDefinition("server-a", "extension-b:shared.run"));
    }

    @Test
    void publicDefinitionCollectionsUseStableCanonicalOrder() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition ownerZ = new NodeDefinition.Builder("same", "Same", NodeDefinition.NodeCategory.FLOW)
            .owner("owner-z")
            .build();
        NodeDefinition ownerA = new NodeDefinition.Builder("same", "Same", NodeDefinition.NodeCategory.FLOW)
            .owner("owner-a")
            .build();
        NodeDefinition nameA = new NodeDefinition.Builder("name-b", "Name B", NodeDefinition.NodeCategory.FLOW)
            .owner("owner-a")
            .build();
        NodeDefinition nameB = new NodeDefinition.Builder("name-a", "Name A", NodeDefinition.NodeCategory.FLOW)
            .owner("owner-a")
            .build();

        registry.register(ownerZ);
        registry.register(nameA);
        registry.register(ownerA);
        registry.register(nameB);
        assertEquals(List.of("name-a", "name-b", "same"), new ArrayList<>(registry.getLocalDefinitions().keySet()));

        registry.registerServerDefinition("server-a", ownerZ);
        registry.registerServerDefinition("server-a", nameA);
        registry.registerServerDefinition("server-a", ownerA);
        registry.registerServerDefinition("server-a", nameB);
        assertEquals(List.of("owner-a:name-a", "owner-a:name-b", "owner-a:same", "owner-z:same"),
            new ArrayList<>(registry.getAllDefinitions("server-a").keySet()));
    }

    @Test
    void materializedSnapshotsOrderDefinitionsWithoutMutatingActivePayloads() {
        NodeDefinition later = new NodeDefinition.Builder("later", "Later", NodeDefinition.NodeCategory.FLOW)
            .owner("owner")
            .build();
        NodeDefinition earlier = new NodeDefinition.Builder("earlier", "Earlier", NodeDefinition.NodeCategory.FLOW)
            .owner("owner")
            .build();
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("plugin");
        payload.setNodes(List.of(later, earlier));
        NodeRegistrySnapshot source = compatibleSnapshot(true);
        source.setServerIdentity("server-a");
        source.setNodeIds(List.of("owner:later", "owner:earlier"));
        source.setPlugins(List.of(payload));

        NodeRegistry registry = new NodeRegistry();
        assertTrue(registry.applySnapshot("server-a", source));
        NodeRegistrySnapshot materialized = registry.materializeSnapshot("server-a", source);

        assertNotNull(materialized);
        assertEquals(List.of("owner:earlier", "owner:later"), materialized.getNodeIds());
        assertEquals(List.of(earlier, later), materialized.getPlugins().getFirst().getNodes());
        assertEquals(List.of(later, earlier), payload.getNodes());
        assertEquals(List.of(earlier, later), registry.captureServerState("server-a").definitions().values().stream().toList());
    }

    @Test
    void typedNodeLookupRejectsTombstonedDefinitionsKeptForRecovery() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition definition = new NodeDefinition.Builder("extension.run", "Run", NodeDefinition.NodeCategory.FLOW)
            .owner("extension")
            .build();
        registry.registerServerDefinition("server-a", definition);
        registry.unregisterServerDefinition("server-a", definition.getId());
        NodePluginPayload tombstone = new NodePluginPayload();
        tombstone.setPluginId("extension");
        tombstone.setNodes(List.of(definition));
        registry.restoreUnresolvedPlugins("server-a", List.of(tombstone));

        NodeRegistry.NodeReference reference = new NodeRegistry.NodeReference("extension", "extension.run");

        assertSame(definition, registry.getDefinition("server-a", definition.getId()));
        assertNull(registry.getActiveDefinition("server-a", reference));
        assertNull(registry.getDefinition("server-a", reference));
    }

    @Test
    void typedSnapshotAuthorityRejectsOlderGenerationsAndKeepsOpaqueData() {
        NodeRegistry registry = new NodeRegistry();
        NodeRegistrySnapshot current = compatibleSnapshot(true);
        NodeRegistry.SnapshotAuthority authority = new NodeRegistry.SnapshotAuthority("server-a", 4L, "typed-4");

        assertTrue(registry.applySnapshot("server-a", current, authority));
        registry.preserveSnapshotData("server-a", Map.of("unknownContribution", Map.of("value", "preserved")));

        NodeRegistrySnapshot stale = compatibleSnapshot(true);
        assertFalse(registry.applySnapshot("server-a", stale,
            new NodeRegistry.SnapshotAuthority("server-a", 3L, "typed-3")));
        assertEquals(authority, registry.getSnapshotAuthority("server-a"));
        assertEquals("preserved", ((Map<?, ?>) registry.getSnapshotData("server-a").get("unknownContribution")).get("value"));
    }

    @Test
    void stagingKeepsRegistryChecksumSeparateFromCatalogAuthority() {
        NodeRegistry registry = new NodeRegistry();
        NodeRegistrySnapshot snapshot = compatibleSnapshot(true);
        snapshot.setRegistryChecksum("registry-9");
        snapshot.setCatalogGeneration(9L);
        snapshot.setCatalogChecksum("catalog-9");
        snapshot.setCatalogProjectionIdentity("projection-9");

        NodeRegistry.ServerState candidate = registry.stageSnapshot("server-a", snapshot,
            new NodeRegistry.SnapshotAuthority("server-a", 9L, "catalog-9"));

        assertNotNull(candidate);
        assertTrue(registry.getRegistrySessionMetadata("server-a") == null);
        assertEquals("registry-9", candidate.session().checksum());
        assertEquals(new NodeRegistry.SnapshotAuthority("server-a", 9L, "catalog-9"), candidate.authority());
    }

    @Test
    void sameCatalogAuthorityCannotChangeItsProjectionIdentity() {
        NodeRegistry registry = new NodeRegistry();
        NodeRegistrySnapshot current = compatibleSnapshot(true);
        current.setCatalogGeneration(4L);
        current.setCatalogChecksum("catalog-4");
        current.setCatalogProjectionIdentity("projection-4");
        NodeRegistry.SnapshotAuthority authority = new NodeRegistry.SnapshotAuthority("server-a", 4L, "catalog-4");
        assertTrue(registry.applySnapshot("server-a", current, authority));

        NodeRegistrySnapshot conflict = compatibleSnapshot(true);
        conflict.setCatalogGeneration(4L);
        conflict.setCatalogChecksum("catalog-4");
        conflict.setCatalogProjectionIdentity("projection-conflict");

        assertFalse(registry.applySnapshot("server-a", conflict, authority));
        assertEquals("projection-4", registry.getSnapshotProjectionIdentity("server-a"));
    }

    @Test
    void invalidSnapshotEntryIsRejectedBeforeRegistryMutation() {
        NodeRegistry registry = new NodeRegistry();
        NodeRegistrySnapshot snapshot = compatibleSnapshot(true);
        snapshot.setPlugins(List.of(new NodePluginPayload()));

        assertNull(registry.stageSnapshot("server-a", snapshot, null));
        assertNull(registry.getRegistrySessionMetadata("server-a"));
        assertTrue(registry.getAllDefinitions("server-a").isEmpty());
    }

    @Test
    void canonicalAuthorityFiltersLegacyCompatibilityDefinitions() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition allowed = new NodeDefinition.Builder("allowed.node", "Allowed", NodeDefinition.NodeCategory.FLOW)
            .owner("extension").build();
        NodeDefinition legacyOnly = new NodeDefinition.Builder("legacy.node", "Legacy", NodeDefinition.NodeCategory.FLOW)
            .owner("extension").build();
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("extension");
        payload.setNodes(List.of(allowed, legacyOnly));

        NodeRegistrySnapshot snapshot = canonicalSnapshot(List.of(Map.of(
            "ownerId", "extension",
            "id", "allowed.node",
            "metadata", Map.of("sourceNodeId", "allowed.node"))));
        snapshot.setNodeIds(List.of("allowed.node", "legacy.node"));
        snapshot.setPlugins(List.of(payload));

        assertTrue(registry.applySnapshot("server-a", snapshot));
        assertSame(allowed, registry.getDefinition("server-a", "extension:allowed.node"));
        assertNull(registry.getDefinition("server-a", "extension:legacy.node"));
        assertEquals(snapshot.getCanonicalCatalogContent(), registry.captureServerState("server-a").catalogAuthority().content());
    }

    @Test
    void canonicalAuthorityMissingContentCannotActivateLegacyPayloads() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition legacy = new NodeDefinition.Builder("legacy.node", "Legacy", NodeDefinition.NodeCategory.FLOW)
            .owner("extension").build();
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("extension");
        payload.setNodes(List.of(legacy));
        NodeRegistrySnapshot snapshot = compatibleSnapshot(true);
        List<String> capabilities = new ArrayList<>(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
        capabilities.addAll(List.of("catalog_authority", "catalog_canonical"));
        snapshot.setCapabilities(capabilities);
        snapshot.setNodeIds(List.of(legacy.getId()));
        snapshot.setPlugins(List.of(payload));

        assertFalse(registry.applySnapshot("server-a", snapshot));
        assertNull(registry.getRegistrySessionMetadata("server-a"));
        assertTrue(registry.getAllDefinitions("server-a").isEmpty());
    }

    private NodeRegistrySnapshot canonicalSnapshot(List<Map<String, Object>> definitions) {
        CatalogSnapshot base = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        Map<String, Object> document = new LinkedHashMap<>((Map<String, Object>) CanonicalJson.parse(base.canonicalContent()));
        document.put("definitions", new ArrayList<>(definitions));
        document.remove("contentChecksum");
        document.put("contentChecksum", "");
        String withoutChecksum = CanonicalJson.canonicalize(document);
        document.put("contentChecksum", CatalogCanonicalizer.checksumForCanonicalContent(withoutChecksum).canonicalText());
        String content = CanonicalJson.canonicalize(document);

        NodeRegistrySnapshot snapshot = compatibleSnapshot(true);
        snapshot.setServerIdentity("server-a");
        List<String> capabilities = new ArrayList<>(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
        capabilities.addAll(List.of("catalog_authority", "catalog_canonical"));
        snapshot.setCapabilities(capabilities);
        snapshot.setCatalogGeneration(base.generation());
        snapshot.setCatalogChecksum(CatalogCanonicalizer.checksumForCanonicalContent(content).canonicalText());
        snapshot.setCatalogProjectionIdentity(base.bindingManifestHash().canonicalText());
        snapshot.setCatalogMetadata(Map.of("bindingManifestHash", base.bindingManifestHash().canonicalText()));
        snapshot.setOpaqueData(Map.of("catalogMetadata", Map.of(
            NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY, content,
            "bindingManifestHash", base.bindingManifestHash().canonicalText())));
        return snapshot;
    }

    @Test
    void stagedRegistryProofRequiresTheCanonicalOwner() {
        NodeRegistry registry = new NodeRegistry();
        NodeDefinition definition = new NodeDefinition.Builder("shared.run", "Run", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-a").build();
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId("extension-a");
        payload.setNodes(List.of(definition));
        NodeRegistrySnapshot snapshot = compatibleSnapshot(true);
        snapshot.setNodeIds(List.of("shared.run"));
        snapshot.setPlugins(List.of(payload));

        NodeRegistry.ServerState candidate = registry.stageSnapshot("server-a", snapshot, null);

        assertTrue(registry.containsDefinition(candidate, new NodeRegistry.NodeReference("extension-a", "shared.run")));
        assertFalse(registry.containsDefinition(candidate, new NodeRegistry.NodeReference("extension-b", "shared.run")));
    }

    private NodeRegistrySnapshot compatibleSnapshot(boolean fullSync) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setCapabilities(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
        snapshot.setFullSync(fullSync);
        return snapshot;
    }
}
