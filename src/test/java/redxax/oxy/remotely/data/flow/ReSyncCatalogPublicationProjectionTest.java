package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCatalogPublicationProjectionTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final OwnerId OWNER = new OwnerId("resync.publication");
    private static final ContractRef<NodeId> ENTRY = ContractRef.of(OWNER, new NodeId("future-node"));
    private static final ContractRef<NodeId> EXTRA_ENTRY = ContractRef.of(OWNER, new NodeId("extra-node"));
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final ContentHash CHECKSUM = new ContentHash("a".repeat(64));
    private static final ContentHash BINDING_HASH = new ContentHash("f".repeat(64));
    private static final byte[] OPAQUE = "{\"future\":{\"value\":7},\"id\":\"future-node\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void decodesWithTheSharedCodecAndRetainsCanonicalUnknownOpaqueAndTombstoneData() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublication.Entry opaque = new CatalogCachePublication.Entry(ENTRY, 4, false,
            CatalogCacheState.UNAVAILABLE, Set.of(), true, CatalogCacheOpaque.of(OPAQUE),
            Map.of("futureEntryField", Map.of("keep", true)));
        CatalogCachePublication.Entry tombstone = CatalogCachePublication.Entry.tombstone(
            ContractRef.of(OWNER, new NodeId("removed")), 5);
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 5,
            List.of(opaque, tombstone), Map.of("futurePublicationField", Map.of("keep", true)));
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        byte[] bytes = codec.encodeBytes(publication);

        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);

        assertTrue(projection.apply(bytes, codec));
        ReSyncCatalogPublicationProjection.Snapshot active = projection.active().orElseThrow();
        assertEquals(publication.unknown(), active.publication().unknown());
        assertEquals(opaque, active.entry(ENTRY).orElseThrow());
        assertTrue(active.entry(ContractRef.of(OWNER, new NodeId("removed"))).orElseThrow().tombstone());
        assertArrayEquals(bytes, active.canonicalBytes());
    }

    @Test
    void decodedPublicationUsesItsValidatedBytesAndRawPairsStillRequireExactMatch() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublication publication = full(key, 1, "first");
        CatalogCachePublication other = full(key, 1, "other");
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        byte[] bytes = codec.encodeBytes(publication);
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);

        assertTrue(projection.prepare(codec.decodeValidatedPublication(bytes)).isPresent());
        assertTrue(projection.prepare(other, bytes).isEmpty());
        assertTrue(projection.active().isEmpty());
    }

    @Test
    void rejectsAKeyMismatchWithoutChangingTheAcknowledgedProjection() {
        CatalogCacheKey acceptedKey = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCacheKey mismatchedKey = new CatalogCacheKey(SERVER, 7, new ContentHash("b".repeat(64)), BINDING_HASH, VERSION);
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(projection.acknowledgeActiveKey(acceptedKey));
        CatalogCachePublication first = full(acceptedKey, 1, "first");
        byte[] firstBytes = new CatalogCachePublicationCodec().encodeBytes(first);
        assertTrue(projection.apply(first, firstBytes));
        ReSyncCatalogPublicationProjection.Snapshot previous = projection.active().orElseThrow();

        CatalogCachePublication mismatched = full(mismatchedKey, 2, "mismatched");
        byte[] mismatchedBytes = new CatalogCachePublicationCodec().encodeBytes(mismatched);

        assertFalse(projection.apply(mismatched, mismatchedBytes));
        assertEquals(previous.publication(), projection.active().orElseThrow().publication());
        assertArrayEquals(previous.canonicalBytes(), projection.active().orElseThrow().canonicalBytes());
        assertEquals(acceptedKey, projection.acknowledgedKey().orElseThrow());
    }

    @Test
    void rejectsASecondServerWithoutChangingTheBoundProjection() {
        CatalogCacheKey acceptedKey = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublication first = full(acceptedKey, 1, "first");
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(projection.apply(first, codec.encodeBytes(first)));
        ReSyncCatalogPublicationProjection.Snapshot previous = projection.active().orElseThrow();

        CatalogCacheKey wrongServerKey = new CatalogCacheKey(OTHER_SERVER, 8, new ContentHash("b".repeat(64)), BINDING_HASH, VERSION);
        CatalogCachePublication wrongServer = full(wrongServerKey, 2, "wrong-server");

        assertFalse(projection.acknowledgeActiveKey(wrongServerKey));
        assertFalse(projection.apply(wrongServer, codec.encodeBytes(wrongServer)));
        assertEquals(previous.publication(), projection.active().orElseThrow().publication());
        assertArrayEquals(previous.canonicalBytes(), projection.active().orElseThrow().canonicalBytes());
        assertEquals(acceptedKey, projection.acknowledgedKey().orElseThrow());
    }

    @Test
    void rejectsNonCanonicalConfiguredIdentityWithStableDiagnostic() {
        ReSyncCatalogPublicationProjection projection =
            ReSyncCatalogPublicationProjection.forConfiguredServerId("live:proxy:test");
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublication publication = full(key, 1, "unbound");

        assertEquals(ReSyncCatalogPublicationProjection.NON_CANONICAL_SERVER_ID,
            projection.identityDiagnostic().orElseThrow());
        assertFalse(projection.apply(publication, new CatalogCachePublicationCodec().encodeBytes(publication)));
        assertTrue(projection.active().isEmpty());
        assertTrue(projection.acknowledgedKey().isEmpty());
    }

    @Test
    void replacesTheProjectionAtomicallyOnlyAfterAValidNewRevision() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublication first = full(key, 1, "first");
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        byte[] firstBytes = codec.encodeBytes(first);
        assertTrue(projection.apply(first, firstBytes));
        ReSyncCatalogPublicationProjection.Snapshot previous = projection.active().orElseThrow();

        assertTrue(projection.apply(first, firstBytes));
        CatalogCachePublication stale = full(key, 1, "stale");
        assertFalse(projection.apply(stale, codec.encodeBytes(stale)));
        assertEquals(previous.publication(), projection.active().orElseThrow().publication());

        CatalogCachePublication replacement = full(key, 2, "replacement");
        byte[] replacementBytes = codec.encodeBytes(replacement);
        assertTrue(projection.apply(replacement, replacementBytes));
        ReSyncCatalogPublicationProjection.Snapshot active = projection.active().orElseThrow();
        assertEquals(replacement, active.publication());
        assertArrayEquals(replacementBytes, active.canonicalBytes());
    }

    @Test
    void acceptsANewerFullGenerationForTheSameServerAndProjection() {
        CatalogCacheKey firstKey = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCacheKey nextKey = new CatalogCacheKey(SERVER, 8, new ContentHash("b".repeat(64)), BINDING_HASH, VERSION);
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCachePublication first = full(firstKey, 7, "first");
        CatalogCachePublication next = full(nextKey, 8, "next");

        assertTrue(projection.apply(first, codec.encodeBytes(first)));
        assertTrue(projection.apply(next, codec.encodeBytes(next)));
        assertEquals(nextKey, projection.acknowledgedKey().orElseThrow());
        assertEquals(next, projection.active().orElseThrow().publication());
    }

    @Test
    void rejectsSameRevisionHydrationWhenItsEntriesDifferFromTheActiveProjection() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublication publication = full(key, 1, "active");
        CatalogCachePublication hydration = full(key, 1, "different");
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        byte[] bytes = codec.encodeBytes(publication);
        assertTrue(projection.apply(publication, bytes));
        ReSyncCatalogPublicationProjection.Snapshot previous = projection.active().orElseThrow();

        ReSyncCatalogPublicationCache.CachedPublication cached = new ReSyncCatalogPublicationCache.CachedPublication(
            SERVER, key, publication, hydration, bytes);

        assertFalse(projection.hydrate(cached));
        assertEquals(previous, projection.active().orElseThrow());
    }

    @Test
    void rejectsHydratedDeltaWhenItsProjectionDropsAnUnchangedEntry() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCachePublication.Entry activeEntry = full(key, 1, "active").entries().getFirst();
        CatalogCachePublication.Entry retainedEntry = CatalogCachePublication.Entry.present(EXTRA_ENTRY, 1,
            CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of("{\"id\":\"extra-node\"}".getBytes(StandardCharsets.UTF_8)));
        CatalogCachePublication activePublication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1,
            List.of(activeEntry, retainedEntry));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(projection.apply(activePublication, codec.encodeBytes(activePublication)));

        CatalogCachePublication.Entry deltaEntry = full(key, 2, "updated").entries().getFirst();
        CatalogCachePublication staleEntry = new CatalogCachePublication(CatalogCachePublication.Kind.DELTA, key, 2,
            List.of(deltaEntry));
        CatalogCachePublication hydration = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 2,
            staleEntry.entries());
        ReSyncCatalogPublicationCache.CachedPublication cached = new ReSyncCatalogPublicationCache.CachedPublication(
            SERVER, key, staleEntry, hydration, codec.encodeBytes(staleEntry));

        assertFalse(projection.hydrate(cached));
        assertEquals(activePublication, projection.active().orElseThrow().publication());
    }

    @Test
    void livePublicationWinsOverPreparedCacheHydration() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCachePublication cachedPublication = full(key, 1, "cached");
        ReSyncCatalogPublicationCache.CachedPublication cached = new ReSyncCatalogPublicationCache.CachedPublication(
            SERVER, key, cachedPublication, cachedPublication, codec.encodeBytes(cachedPublication));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        ReSyncCatalogPublicationProjection.PreparedHydration prepared = projection.prepareHydration(cached)
            .orElseThrow();
        CatalogCachePublication live = full(key, 2, "live");

        assertTrue(projection.apply(live, codec.encodeBytes(live)));

        assertFalse(projection.commitHydration(prepared));
        assertEquals(live, projection.active().orElseThrow().publication());
    }

    @Test
    void restoreRejectsEntryMapsThatCannotBeDerivedFromTheCanonicalPublication() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublication publication = full(key, 1, "active");
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        byte[] bytes = codec.encodeBytes(publication);
        assertTrue(projection.apply(publication, bytes));
        ReSyncCatalogPublicationProjection.Snapshot previous = projection.active().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> projection.restore(
            new ReSyncCatalogPublicationProjection.Snapshot(publication, Map.of(), bytes)));
        assertEquals(previous, projection.active().orElseThrow());
    }

    @Test
    void restoreRejectsDeltaEntriesThatClaimTheCurrentRevisionWithoutCanonicalEvidence() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 7, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogCachePublication first = full(key, 1, "first");
        CatalogCachePublication delta = new CatalogCachePublication(CatalogCachePublication.Kind.DELTA, key, 2,
            List.of(full(key, 2, "second").entries().getFirst()));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(projection.apply(first, codec.encodeBytes(first)));
        assertTrue(projection.apply(delta, codec.encodeBytes(delta)));

        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> forgedEntries = Map.of(
            ENTRY, delta.entries().getFirst(), EXTRA_ENTRY, CatalogCachePublication.Entry.tombstone(EXTRA_ENTRY, 2));
        assertThrows(IllegalArgumentException.class, () -> projection.restore(
            new ReSyncCatalogPublicationProjection.Snapshot(delta, forgedEntries, codec.encodeBytes(delta))));
    }

    private static CatalogCachePublication full(CatalogCacheKey key, long revision, String value) {
        byte[] data = ("{\"id\":\"future-node\",\"value\":\"" + value + "\"}").getBytes(StandardCharsets.UTF_8);
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(ENTRY, revision,
            CatalogCacheState.UNAVAILABLE, Set.of(), true, CatalogCacheOpaque.of(data));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, revision, List.of(entry));
    }
}
