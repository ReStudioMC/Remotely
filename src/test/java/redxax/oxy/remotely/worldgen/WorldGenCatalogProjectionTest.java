package redxax.oxy.remotely.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationCache;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientTestHarness;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.worldgen.data.WorldGenGraph;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenCatalogProjectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesBaseAndWorldGenScopedServerIdsWithoutCreatingAnotherAuthority() {
        assertEquals("server-a", WorldGenCatalogProjection.normalizeBaseServerId("server-a"));
        assertEquals("server-a", WorldGenCatalogProjection.normalizeBaseServerId("server-a:worldgen"));
        assertEquals("server-a:worldgen", WorldGenCatalogProjection.scopedServerId("server-a"));
        assertEquals("server-a:worldgen", WorldGenCatalogProjection.scopedServerId("server-a:worldgen"));
        assertEquals("local", WorldGenManager.connectionServerId(null));
        assertEquals("local", WorldGenManager.connectionServerId(""));
        assertEquals("local:worldgen", WorldGenCatalogProjection.scopedServerId(""));
        assertEquals("server-a", WorldGenManager.connectionServerId("server-a:worldgen"));
        assertEquals("server-a:worldgen", WorldGenManager.registryServerId("server-a:worldgen"));
    }

    @Test
    void typedPublicationWinsOverStaleLegacyDefinitionsAndRequiresOwnerQualifiedIdentity() throws Exception {
        ServerId server = ServerId.random();
        ServerId otherServer = ServerId.random();
        NodeDefinition staleLegacy = legacyDefinition();
        ReSyncFlowClient client = acknowledgedClient(server);
        try {
            WorldGenCatalogProjection.Snapshot snapshot = WorldGenCatalogProjection.from(server.canonicalText(), client);
            WorldGenCatalogProjection.Snapshot scoped = WorldGenCatalogProjection.from(server.canonicalText() + ":worldgen", client);
            WorldGenCatalogProjection.Snapshot mismatched = WorldGenCatalogProjection.from(otherServer.canonicalText(), client);

            assertEquals(WorldGenCatalogProjection.Authority.TYPED_PUBLICATION, snapshot.authority());
            assertEquals(WorldGenCatalogProjection.Authority.TYPED_PUBLICATION, scoped.authority());
            assertEquals(WorldGenCatalogProjection.Authority.UNAVAILABLE, mismatched.authority());
            assertEquals("Typed Simplex", snapshot.definition("worldgen:simplex").orElseThrow().getDisplayName());
            assertEquals("worldgen", snapshot.definition("worldgen:simplex").orElseThrow().getOwner());
            assertFalse(snapshot.definition("worldgen:simplex").orElseThrow().getDisplayName().equals(staleLegacy.getDisplayName()));
            assertTrue(snapshot.definition("simplex").isEmpty());
            assertTrue(snapshot.definition("legacy:simplex").isEmpty());
            assertTrue(mismatched.definitions().isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void typedPublicationRejectsUnqualifiedIdentityEvenWhenTheLocalIdIsUnique() throws Exception {
        ServerId server = ServerId.random();
        ReSyncFlowClient client = acknowledgedClient(server);
        try {
            WorldGenCatalogProjection.Snapshot snapshot = WorldGenCatalogProjection.from(server.canonicalText(), client);
            assertThrows(IllegalArgumentException.class, () -> new WorldGenManager().liveFlowNodeType(snapshot, "simplex"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void typedOwnerQualifiedNodeTypesRoundTripAndAmbiguousLocalIdsAreRejected() throws Exception {
        ServerId graphServer = ServerId.random();
        ReSyncFlowClient client = acknowledgedClient(graphServer, List.of(
            ContractRef.of(new OwnerId("worldgen"), new NodeId("simplex")),
            ContractRef.of(new OwnerId("extension.worldgen"), new NodeId("simplex"))));
        try {
            WorldGenCatalogProjection.Snapshot snapshot = WorldGenCatalogProjection.from(graphServer.canonicalText(), client);
            assertEquals(WorldGenCatalogProjection.Authority.TYPED_PUBLICATION, snapshot.authority());
            assertTrue(snapshot.definition("worldgen:simplex").isPresent());
            assertTrue(snapshot.definition("extension.worldgen:simplex").isEmpty());
            FlowGraph source = new FlowGraph();
            source.getNodes().put("base", new FlowNode("worldgen:simplex", 0, 0, Map.of()));
            source.getNodes().put("extension", new FlowNode("extension.worldgen:simplex", 120, 0, Map.of()));

            assertThrows(IllegalArgumentException.class,
                () -> WorldGenManager.getInstance().toWorldGenGraph(snapshot, source));
            source.getNodes().remove("extension");
            WorldGenGraph wire = WorldGenManager.getInstance().toWorldGenGraph(snapshot, source);
            assertEquals("worldgen:simplex", wire.getNodes().get("base").getType());

            FlowGraph wrongOwner = new FlowGraph();
            wrongOwner.getNodes().put("wrong", new FlowNode("legacy:simplex", 0, 0, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> WorldGenManager.getInstance().toWorldGenGraph(snapshot, wrongOwner));

            FlowGraph unqualified = new FlowGraph();
            unqualified.getNodes().put("unqualified", new FlowNode("simplex", 0, 0, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> WorldGenManager.getInstance().toWorldGenGraph(unqualified));

            FlowGraph restored = WorldGenManager.getInstance().toFlowGraph(wire);
            assertEquals("worldgen:simplex", restored.getNodes().get("base").getType());

            FlowGraph ambiguous = new FlowGraph();
            ambiguous.getNodes().put("ambiguous", new FlowNode("simplex", 0, 0, Map.of()));
            assertThrows(IllegalArgumentException.class,
                () -> WorldGenManager.getInstance().liveFlowNodeType(snapshot, ambiguous.getNodes().get("ambiguous").getType()));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void worldGenAuthorityIgnoresAliasesAndMetadataClaims() {
        assertTrue(WorldGenCatalogProjection.isWorldGenReference("worldgen:simplex"));
        assertFalse(WorldGenCatalogProjection.isWorldGenReference("worldgen.extension:simplex"));
        assertFalse(WorldGenCatalogProjection.isWorldGenReference("extension.worldgen:simplex"));
        assertFalse(WorldGenCatalogProjection.isWorldGenDefinition(new NodeDefinition.Builder(
            "simplex", "Alias", NodeDefinition.NodeCategory.WORLD_GEN).owner("worldgen.extension").build()));
        assertFalse(WorldGenCatalogProjection.isWorldGenDefinition(new NodeDefinition.Builder(
            "simplex", "Category Claim", NodeDefinition.NodeCategory.WORLD_GEN).owner("extension").build()));
    }

    @Test
    void reconciliationAndUnavailableNeverExposeDefinitions() {
        NodeDefinition stale = legacyDefinition();

        WorldGenCatalogProjection.Snapshot reconciliation = WorldGenCatalogProjection.reconciliation("server-a", "refresh");
        WorldGenCatalogProjection.Snapshot unavailable = WorldGenCatalogProjection.unavailable("server-a", "offline");

        assertTrue(reconciliation.readOnly());
        assertTrue(unavailable.readOnly());
        assertTrue(reconciliation.definitions().isEmpty());
        assertTrue(unavailable.definitions().isEmpty());
        assertFalse(reconciliation.definitions().containsValue(stale));
        assertFalse(unavailable.definitions().containsValue(stale));

        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("stale", new FlowNode("worldgen:simplex", 0, 0, Map.of()));
        WorldGenManager manager = new WorldGenManager();
        assertThrows(IllegalStateException.class, () -> manager.toWorldGenGraph(reconciliation, graph));
        assertThrows(IllegalStateException.class, () -> manager.toWorldGenGraph(unavailable, graph));
    }

    @Test
    void legacyDefinitionsAreVisibleOnlyWhenCompatibilityIsExplicit() {
        NodeDefinition legacy = legacyDefinition();
        WorldGenCatalogProjection.Snapshot snapshot = WorldGenCatalogProjection.legacy("server-a",
            Map.of("worldgen:simplex", legacy), "LEGACY_COMPATIBILITY");

        assertTrue(snapshot.legacyCompatibility());
        assertTrue(snapshot.readOnly());
        assertEquals(legacy, snapshot.definition("worldgen:simplex").orElseThrow());
        assertTrue(snapshot.definition("simplex").isEmpty());

        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("legacy", new FlowNode("simplex", 0, 0, Map.of()));
        assertEquals("simplex", new WorldGenManager().toWorldGenGraph(snapshot, graph).getNodes().get("legacy").getType());
    }

    private static NodeDefinition legacyDefinition() {
        return new NodeDefinition.Builder("simplex", "Legacy Simplex", NodeDefinition.NodeCategory.WORLD_GEN)
            .owner("worldgen")
            .input("seed", NodeDefinition.PinType.DATA, FlowDataType.SEED)
            .output("out", NodeDefinition.PinType.DATA, FlowDataType.FLOAT)
            .build();
    }

    private static String descriptor() {
        return descriptor("simplex", "Typed Simplex");
    }

    private static String descriptor(String id, String displayName) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "seed");
        input.put("direction", "input");
        input.put("type", "seed");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "out");
        output.put("direction", "output");
        output.put("type", "float");
        return CanonicalJson.canonicalize(Map.of(
            "id", id,
            "displayName", displayName,
            "category", "world_gen",
            "pins", List.of(input, output)));
    }

    private ReSyncFlowClient acknowledgedClient(ServerId server) throws Exception {
        return acknowledgedClient(server, List.of(ContractRef.of(new OwnerId("worldgen"), new NodeId("simplex"))));
    }

    private ReSyncFlowClient acknowledgedClient(ServerId server, List<ContractRef<NodeId>> identities) throws Exception {
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("catalog-" + UUID.randomUUID()) + ".json"));
        return ReSyncFlowClientTestHarness.connect(server, cache, publication(server, identities)).client();
    }

    private static CatalogCachePublication publication(ServerId server, List<ContractRef<NodeId>> identities) {
        CatalogBinding binding = new CatalogBinding(4, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));
        CatalogCacheKey key = new CatalogCacheKey(server, binding, CatalogProjectionVersion.current());
        List<CatalogCachePublication.Entry> entries = identities.stream().map(identity -> CatalogCachePublication.Entry.present(
            identity, 4, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(descriptor(identity.id().value(), "Typed Simplex")
                .getBytes(StandardCharsets.UTF_8)))).toList();
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 4, entries);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCachePublication decoded = codec.decodeBytes(codec.encodeBytes(publication));
        assertEquals(publication, decoded, publication + " != " + decoded);
        return publication;
    }
}
