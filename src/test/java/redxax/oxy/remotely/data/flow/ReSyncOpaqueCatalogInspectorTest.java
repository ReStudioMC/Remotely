package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncOpaqueCatalogInspectorTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("resync.inspector");
    private static final CatalogCacheKey KEY = new CatalogCacheKey(SERVER, 3, new ContentHash("a".repeat(64)),
        new ContentHash("f".repeat(64)), CatalogProjectionVersion.current());

    @Test
    void exposesOpaqueUnavailableAndTombstonedEntriesAsReadOnlyWithoutDataLoss() {
        CatalogCachePublication.Entry supported = present("supported", CatalogCacheState.ACTIVE, false,
            "{\"value\":1}", Map.of());
        CatalogCachePublication.Entry opaque = present("opaque", CatalogCacheState.UNAVAILABLE, true,
            "{\"future\":{\"keep\":true}}", Map.of("futureField", Map.of("keep", true)));
        CatalogCachePublication.Entry unavailable = present("unavailable", CatalogCacheState.UNAVAILABLE, false,
            "{\"value\":2}", Map.of());
        CatalogCachePublication.Entry tombstone = CatalogCachePublication.Entry.tombstone(key("removed"), 4,
            Map.of("tombstoneField", "keep"));

        List<ReSyncOpaqueCatalogInspector.Inspection> inspections = ReSyncOpaqueCatalogInspector.inspect(
            List.of(supported, opaque, unavailable, tombstone), entry -> entry.equals(supported));

        assertEquals(3, inspections.size());
        ReSyncOpaqueCatalogInspector.Inspection opaqueInspection = inspection(inspections, "opaque");
        assertEquals(ReSyncOpaqueCatalogInspector.Status.OPAQUE, opaqueInspection.status());
        assertTrue(opaqueInspection.readOnly());
        assertEquals("{\"future\":{\"keep\":true}}", opaqueInspection.canonicalData().orElseThrow());
        assertArrayEquals(opaque.data().canonicalBytes(), opaqueInspection.canonicalDataBytes());
        assertEquals(opaque.unknown(), opaqueInspection.unknown());

        ReSyncOpaqueCatalogInspector.Inspection unavailableInspection = inspection(inspections, "unavailable");
        assertEquals(ReSyncOpaqueCatalogInspector.Status.UNAVAILABLE, unavailableInspection.status());
        assertTrue(unavailableInspection.reason().contains("capabilities"));
        assertEquals(unavailable.data().canonicalText(), unavailableInspection.canonicalData().orElseThrow());

        ReSyncOpaqueCatalogInspector.Inspection tombstoneInspection = inspection(inspections, "removed");
        assertEquals(ReSyncOpaqueCatalogInspector.Status.TOMBSTONED, tombstoneInspection.status());
        assertTrue(tombstoneInspection.readOnly());
        assertTrue(tombstoneInspection.canonicalData().isEmpty());
        assertEquals(tombstone.unknown(), tombstoneInspection.unknown());
    }

    @Test
    void marksAValidEntryReadOnlyWhenNoSupportedDescriptorMappingExists() {
        CatalogCachePublication.Entry entry = present("unsupported", CatalogCacheState.ACTIVE, false,
            "{\"descriptor\":true}", Map.of("unknown", "preserve"));

        List<ReSyncOpaqueCatalogInspector.Inspection> inspections = ReSyncOpaqueCatalogInspector.inspect(
            List.of(entry), ignored -> false);

        ReSyncOpaqueCatalogInspector.Inspection inspection = inspections.getFirst();
        assertEquals(ReSyncOpaqueCatalogInspector.Status.UNSUPPORTED, inspection.status());
        assertTrue(inspection.readOnly());
        assertTrue(inspection.reason().contains("descriptor-to-widget"));
        assertEquals(entry.data().canonicalText(), inspection.canonicalData().orElseThrow());
        assertEquals(entry.unknown(), inspection.unknown());
    }

    @Test
    void leavesSupportedEntriesOutOfTheReadOnlySurface() {
        CatalogCachePublication.Entry entry = present("supported", CatalogCacheState.ACTIVE, false,
            "{\"descriptor\":true}", Map.of());

        assertTrue(ReSyncOpaqueCatalogInspector.inspect(List.of(entry), ignored -> true).isEmpty());
        assertFalse(ReSyncOpaqueCatalogInspector.inspect(List.of(entry), ignored -> false).isEmpty());
    }

    private static CatalogCachePublication.Entry present(String id, CatalogCacheState state, boolean opaque,
                                                         String data, Map<String, Object> unknown) {
        return CatalogCachePublication.Entry.present(key(id), 4, state, Set.of(), opaque,
            CatalogCacheOpaque.of(data.getBytes(StandardCharsets.UTF_8)), unknown);
    }

    private static ContractRef<NodeId> key(String id) {
        return ContractRef.of(OWNER, new NodeId(id));
    }

    private static ReSyncOpaqueCatalogInspector.Inspection inspection(
        List<ReSyncOpaqueCatalogInspector.Inspection> inspections, String id) {
        return inspections.stream()
            .filter(value -> value.definitionKey().id().value().equals(id))
            .findFirst()
            .orElseThrow();
    }
}
