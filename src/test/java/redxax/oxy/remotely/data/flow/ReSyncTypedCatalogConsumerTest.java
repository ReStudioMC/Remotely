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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncTypedCatalogConsumerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("resync.typed");
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final CatalogCacheKey KEY = new CatalogCacheKey(SERVER, 4, new ContentHash("a".repeat(64)),
        new ContentHash("f".repeat(64)), VERSION);

    @Test
    void listsTypedEntriesAndPreservesUnavailableOpaqueAndTombstoneRecords() {
        ContractRef<NodeId> activeKey = key("active");
        ContractRef<NodeId> opaqueKey = key("opaque");
        ContractRef<NodeId> readOnlyKey = key("read-only");
        ContractRef<NodeId> removedKey = key("removed");
        CatalogCachePublication.Entry active = CatalogCachePublication.Entry.present(activeKey, 1,
            CatalogCacheState.ACTIVE, Set.of(), false, data("active"));
        CatalogCachePublication.Entry opaque = CatalogCachePublication.Entry.present(opaqueKey, 1,
            CatalogCacheState.UNAVAILABLE, Set.of(), true, data("opaque"));
        CatalogCachePublication.Entry readOnly = CatalogCachePublication.Entry.present(readOnlyKey, 1,
            CatalogCacheState.READ_ONLY, Set.of(), false, data("read-only"));
        CatalogCachePublication.Entry removed = CatalogCachePublication.Entry.tombstone(removedKey, 1);
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, KEY, 1,
            List.of(active, opaque, readOnly, removed));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);

        assertTrue(projection.apply(publication, new restudio.resync.flow.cache.CatalogCachePublicationCodec().encodeBytes(publication)));
        assertEquals(List.of(active, opaque, readOnly, removed), ReSyncTypedCatalogConsumer.entries(projection));
        assertEquals(opaque, ReSyncTypedCatalogConsumer.find(projection, OWNER.value(), "opaque").orElseThrow());
        assertTrue(ReSyncTypedCatalogConsumer.renderable(active));
        assertTrue(ReSyncTypedCatalogConsumer.editable(active));
        assertFalse(ReSyncTypedCatalogConsumer.renderable(opaque));
        assertFalse(ReSyncTypedCatalogConsumer.editable(opaque));
        assertTrue(ReSyncTypedCatalogConsumer.renderable(readOnly));
        assertFalse(ReSyncTypedCatalogConsumer.editable(readOnly));
        assertTrue(ReSyncTypedCatalogConsumer.unavailable(opaque));
        assertTrue(ReSyncTypedCatalogConsumer.unavailable(removed));
    }

    @Test
    void missingProjectionFailsClosedWithoutLegacyLookup() {
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);

        assertTrue(ReSyncTypedCatalogConsumer.active(projection).isEmpty());
        assertTrue(ReSyncTypedCatalogConsumer.entries(projection).isEmpty());
        assertTrue(ReSyncTypedCatalogConsumer.find(projection, OWNER.value(), "legacy-only").isEmpty());
        assertFalse(ReSyncTypedCatalogConsumer.renderable(null));
        assertFalse(ReSyncTypedCatalogConsumer.editable(null));
    }

    @Test
    void malformedIdentityFailsClosed() {
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);

        assertTrue(ReSyncTypedCatalogConsumer.find(projection, "", "node").isEmpty());
        assertTrue(ReSyncTypedCatalogConsumer.find(projection, "owner", "").isEmpty());
        assertTrue(ReSyncTypedCatalogConsumer.find(projection, "owner:bad", "node").isEmpty());
    }

    @Test
    void descriptorProjectionRetainsOwnerQualifiedIdentityAndReadOnlyState() {
        ContractRef<NodeId> activeKey = key("active");
        ContractRef<NodeId> readOnlyKey = key("read-only");
        CatalogCachePublication.Entry active = CatalogCachePublication.Entry.present(activeKey, 1,
            CatalogCacheState.ACTIVE, Set.of(), false, data("active"));
        CatalogCachePublication.Entry readOnly = CatalogCachePublication.Entry.present(readOnlyKey, 1,
            CatalogCacheState.READ_ONLY, Set.of(), false, data("read-only"));
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, KEY, 1,
            List.of(active, readOnly));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);

        assertTrue(projection.apply(publication, new restudio.resync.flow.cache.CatalogCachePublicationCodec().encodeBytes(publication)));
        assertEquals(activeKey, ReSyncTypedCatalogConsumer.descriptor(projection, OWNER.value(), "active",
            ReSyncGenericDescriptorProjection.ClientCapabilities.none()).orElseThrow().definitionKey());
        assertTrue(ReSyncTypedCatalogConsumer.descriptor(projection, "other.owner", "active",
            ReSyncGenericDescriptorProjection.ClientCapabilities.none()).isEmpty());
        assertEquals(ReSyncGenericDescriptorProjection.Status.READ_ONLY,
            ReSyncTypedCatalogConsumer.descriptor(projection, OWNER.value(), "read-only",
                ReSyncGenericDescriptorProjection.ClientCapabilities.none()).orElseThrow().status());
    }

    private static ContractRef<NodeId> key(String id) {
        return ContractRef.of(OWNER, new NodeId(id));
    }

    private static CatalogCacheOpaque data(String id) {
        return CatalogCacheOpaque.of(("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
