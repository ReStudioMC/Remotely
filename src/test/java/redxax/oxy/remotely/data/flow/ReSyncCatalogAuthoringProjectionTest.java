package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCatalogAuthoringProjectionTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final CatalogBinding BINDING = new CatalogBinding(7,
        new ContentHash("a".repeat(64)), new ContentHash("f".repeat(64)));

    @Test
    void appliesExactCanonicalAuthoringAndExposesTheSemanticChecksum() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, VERSION);
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        byte[] bytes = new CatalogAuthoringPublicationCodec().encodeBytes(publication);
        ReSyncCatalogAuthoringProjection projection = new ReSyncCatalogAuthoringProjection(SERVER);

        assertTrue(projection.apply(key, 4, publication, bytes));
        assertEquals(CatalogCachePublicationCodec.authoringPublicationChecksum(publication),
            projection.activeChecksum().orElseThrow());
        assertEquals(publication, projection.activePublication().orElseThrow());
        assertArrayEquals(bytes, projection.active().orElseThrow().canonicalBytes());
        assertTrue(projection.apply(key, 4, publication, bytes));
    }

    @Test
    void rejectsConflictingRevisionAndNonCanonicalBytesWithoutChangingActiveState() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, VERSION);
        CatalogAuthoringPublication first = publication(BINDING, CatalogCacheState.ACTIVE);
        CatalogAuthoringPublication second = publication(BINDING, CatalogCacheState.UNAVAILABLE);
        CatalogAuthoringPublicationCodec codec = new CatalogAuthoringPublicationCodec();
        byte[] firstBytes = codec.encodeBytes(first);
        ReSyncCatalogAuthoringProjection projection = new ReSyncCatalogAuthoringProjection(SERVER);

        assertTrue(projection.apply(key, 4, first, firstBytes));
        ReSyncCatalogAuthoringProjection.Snapshot active = projection.active().orElseThrow();
        assertFalse(projection.apply(key, 4, second, codec.encodeBytes(second)));
        byte[] nonCanonical = new byte[firstBytes.length + 1];
        System.arraycopy(firstBytes, 0, nonCanonical, 0, firstBytes.length);
        nonCanonical[nonCanonical.length - 1] = 0;
        assertFalse(projection.apply(key, 5, first, nonCanonical));
        assertSame(active, projection.active().orElseThrow());
    }

    private static CatalogAuthoringPublication publication(CatalogBinding binding, CatalogCacheState state) {
        boolean acknowledged = state != CatalogCacheState.UNAVAILABLE;
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, acknowledged, state, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                true, acknowledged, state, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                true, acknowledged, state, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                true, acknowledged, state, List.of()));
        return new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0), VERSION, sections, Set.of());
    }
}
