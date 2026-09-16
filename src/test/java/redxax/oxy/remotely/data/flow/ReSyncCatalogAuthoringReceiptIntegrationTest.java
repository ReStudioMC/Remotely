package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCatalogAuthoringReceiptIntegrationTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("77777777-7777-4777-8777-777777777777"));
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final CatalogBinding BINDING = new CatalogBinding(4,
        new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));
    private static final OwnerId OWNER = new OwnerId("resync.authoring.receipt");
    private static final ContractRef<NodeId> NODE = ContractRef.of(OWNER, new NodeId("node"));
    private static final String SESSION = "authoring-receipt-session";
    private static final String SESSION_OWNER = "owner-authoring-receipt-session";
    private static final String FIRST_SESSION_OWNER = "owner-session-one";
    private static final String SECOND_SESSION_OWNER = "owner-session-two";

    @Test
    void commitsNodeAndSessionAuthoringTogetherButPersistsOnlyNodePublication(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        ReSyncCatalogPublicationProjection nodeProjection = new ReSyncCatalogPublicationProjection(SERVER, cache);
        ReSyncCatalogAuthoringProjection authoringProjection = new ReSyncCatalogAuthoringProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION,
            nodeProjection, authoringProjection, cache);
        handler.setAuthoringRequired(true);
        CatalogCachePublication publication = publication(CatalogCachePublication.Kind.FULL, 3, "first",
            authoring(BINDING, CatalogCacheState.ACTIVE));
        CatalogCachePublicationCodec publicationCodec = new CatalogCachePublicationCodec();
        byte[] bytes = publicationCodec.encodeBytes(publication);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, publication).dispatched()
            .clientReceived(publication.key(), publication.revision()).receipt().orElseThrow();

        assertTrue(handler.apply(receipt, publication, bytes).applied());
        assertEquals(publication.withAuthoringPublication(null), nodeProjection.active().orElseThrow().publication());
        assertEquals(publication.authoringPublication(), authoringProjection.activePublication().orElseThrow());
        assertEquals(publication.authoringPublicationChecksum(), authoringProjection.activeChecksum().orElseThrow());
        assertEquals(ReSyncCatalogPublicationReceiptHandler.PersistenceStatus.STORED,
            handler.cachePersistenceCompletion().join().status());
        assertNull(cache.latest(SERVER).orElseThrow().publication().authoringPublication());
        assertFalse(Files.readString(path).contains("\"authoring\""));
        assertTrue(handler.apply(receipt, publication, bytes).applied());
        assertEquals(ReSyncCatalogPublicationReceiptHandler.PersistenceStatus.STORED,
            handler.cachePersistenceCompletion().join().status());
    }

    @Test
    void acceptsSameNodeRevisionWithDifferentSessionAuthoringAndKeepsCacheNodeOnly(@TempDir Path tempDir) {
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("catalog-publication-cache.json")));
        ReSyncCatalogPublicationProjection nodeProjection = new ReSyncCatalogPublicationProjection(SERVER, cache);
        ReSyncCatalogAuthoringProjection firstAuthoring = new ReSyncCatalogAuthoringProjection(SERVER);
        ReSyncCatalogAuthoringProjection secondAuthoring = new ReSyncCatalogAuthoringProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler firstHandler = new ReSyncCatalogPublicationReceiptHandler(SERVER,
            "session-one", nodeProjection, firstAuthoring, cache);
        ReSyncCatalogPublicationReceiptHandler secondHandler = new ReSyncCatalogPublicationReceiptHandler(SERVER,
            "session-two", nodeProjection, secondAuthoring, cache);
        firstHandler.setAuthoringRequired(true);
        secondHandler.setAuthoringRequired(true);
        CatalogCachePublication first = publication(CatalogCachePublication.Kind.FULL, 3, "same",
            authoring(BINDING, CatalogCacheState.ACTIVE, "cap-one"));
        CatalogCachePublication second = publication(CatalogCachePublication.Kind.FULL, 3, "same",
            authoring(BINDING, CatalogCacheState.ACTIVE, "cap-two"));
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        byte[] firstBytes = codec.encodeBytes(first);
        byte[] secondBytes = codec.encodeBytes(second);
        assertTrue(first.authoringPublication().advertisedEditCapabilities()
            .stream().noneMatch(capability -> second.authoringPublication().canEdit(capability)));
        CatalogPublicationReceipt firstReceipt = CatalogPublicationReceipt.pending("session-one", FIRST_SESSION_OWNER, first).dispatched()
            .clientReceived(first.key(), first.revision()).receipt().orElseThrow();
        CatalogPublicationReceipt secondReceipt = CatalogPublicationReceipt.pending("session-two", SECOND_SESSION_OWNER, second).dispatched()
            .clientReceived(second.key(), second.revision()).receipt().orElseThrow();

        assertTrue(firstHandler.apply(firstReceipt, first, firstBytes).applied());
        assertTrue(secondHandler.apply(secondReceipt, second, secondBytes).applied());
        assertEquals(ReSyncCatalogPublicationReceiptHandler.PersistenceStatus.STORED,
            secondHandler.cachePersistenceCompletion().join().status());
        assertEquals(first.withAuthoringPublication(null), nodeProjection.active().orElseThrow().publication());
        assertEquals(first.authoringPublication(), firstAuthoring.activePublication().orElseThrow());
        assertEquals(second.authoringPublication(), secondAuthoring.activePublication().orElseThrow());
        ReSyncCatalogPublicationCache.CachedPublication cached = cache.latest(SERVER).orElseThrow();
        assertNull(cached.publication().authoringPublication());
        assertArrayEquals(codec.encodeBytes(first.withAuthoringPublication(null)), cached.canonicalBytes());

        ReSyncCatalogPublicationProjection restarted = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("catalog-publication-cache.json"))));
        assertTrue(restarted.hydrateFromCache());
        assertEquals(first.withAuthoringPublication(null), restarted.active().orElseThrow().publication());
        assertNull(restarted.active().orElseThrow().publication().authoringPublication());
        assertTrue(new ReSyncCatalogAuthoringProjection(SERVER).active().isEmpty());
    }

    @Test
    void reconnectHydratesLegacyCombinedCacheAsNodeOnlyThenCommitsLiveAuthoringAtTheSameRevision(
        @TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCachePublication publication = publication(CatalogCachePublication.Kind.FULL, 3, "reconnect",
            authoring(BINDING, CatalogCacheState.ACTIVE, "cap-reconnect"));
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        byte[] bytes = codec.encodeBytes(publication);
        Map<String, Object> legacySnapshot = Map.of(
            "publication", codec.encode(publication),
            "projection", "publication",
            "authoring", new CatalogAuthoringPublicationCodec().encode(publication.authoringPublication())
        );
        Map<String, Object> legacyCache = Map.of(
            "schemaVersion", ReSyncCatalogPublicationCache.SCHEMA_VERSION,
            "servers", Map.of(SERVER.canonicalText(), Map.of(publication.key().canonicalText(), legacySnapshot))
        );
        Files.write(path, JsonValue.fromJava(legacyCache).canonicalBytes(CanonicalLimits.catalog()));

        ReSyncCatalogPublicationProjection nodeProjection = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));
        assertTrue(nodeProjection.hydrateFromCache());
        assertNull(nodeProjection.active().orElseThrow().publication().authoringPublication());
        ReSyncCatalogAuthoringProjection authoringProjection = new ReSyncCatalogAuthoringProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER,
            SESSION, nodeProjection, authoringProjection, new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));
        handler.setAuthoringRequired(true);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, publication).dispatched()
            .clientReceived(publication.key(), publication.revision()).receipt().orElseThrow();

        assertTrue(handler.apply(receipt, publication, bytes).applied());
        assertEquals(publication.authoringPublication(), authoringProjection.activePublication().orElseThrow());
        assertEquals(publication.withAuthoringPublication(null), nodeProjection.active().orElseThrow().publication());
        assertEquals(ReSyncCatalogPublicationReceiptHandler.PersistenceStatus.STORED,
            handler.cachePersistenceCompletion().join().status());
    }

    @Test
    void rejectsNegotiatedDeltaWithoutAnEmbeddedAuthoringProjection(@TempDir Path tempDir) {
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("catalog-publication-cache.json")));
        ReSyncCatalogPublicationProjection nodeProjection = new ReSyncCatalogPublicationProjection(SERVER, cache);
        ReSyncCatalogAuthoringProjection authoringProjection = new ReSyncCatalogAuthoringProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION,
            nodeProjection, authoringProjection, cache);
        handler.setAuthoringRequired(true);
        CatalogCachePublication publication = publication(CatalogCachePublication.Kind.DELTA, 3, "delta", null);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, publication).dispatched()
            .clientReceived(publication.key(), publication.revision()).receipt().orElseThrow();

        assertTrue(handler.apply(receipt, publication, codec.encodeBytes(publication)).readOnly());
        assertTrue(nodeProjection.active().isEmpty());
        assertTrue(authoringProjection.active().isEmpty());
        assertTrue(cache.latest(SERVER).isEmpty());
    }

    private static CatalogCachePublication publication(CatalogCachePublication.Kind kind, long revision, String value,
                                                       CatalogAuthoringPublication authoring) {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, VERSION);
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(NODE, revision,
            CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of(("{\"id\":\"node\",\"value\":\"" + value + "\"}")
                .getBytes(StandardCharsets.UTF_8)));
        return new CatalogCachePublication(kind, key, BINDING, revision,
            List.of(entry), authoring, Map.of());
    }

    private static CatalogAuthoringPublication authoring(CatalogBinding binding, CatalogCacheState state) {
        return authoring(binding, state, null);
    }

    private static CatalogAuthoringPublication authoring(CatalogBinding binding, CatalogCacheState state,
                                                         String capabilityId) {
        if (capabilityId != null) {
            ContractRef<CapabilityId> capability = ContractRef.of(OWNER, new CapabilityId(capabilityId));
            CatalogAuthoringPublication.Entry capabilityEntry = new CatalogAuthoringPublication.Entry(
                CatalogAuthoringPublication.Section.CAPABILITIES, capability.canonicalText(), state, Set.of(),
                Set.of(CatalogAuthoringPublication.Section.CAPABILITIES), false,
                CatalogCacheOpaque.of(("{\"id\":\"" + capabilityId + "\"}")
                    .getBytes(StandardCharsets.UTF_8)));
            CatalogAuthoringPublication.Entry editorEntry = new CatalogAuthoringPublication.Entry(
                CatalogAuthoringPublication.Section.EDITORS, capability.canonicalText(), state, Set.of(capability),
                Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES), false,
                CatalogCacheOpaque.of(("{\"id\":\"editor-" + capabilityId + "\"}")
                    .getBytes(StandardCharsets.UTF_8)));
            List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
                new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                    true, true, state, List.of()),
                new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                    true, true, state, List.of(editorEntry)),
                new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                    true, true, state, List.of()),
                new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                    true, true, state, List.of(capabilityEntry)));
            return new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0), VERSION, sections, null);
        }
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, true, state, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                true, true, state, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                true, true, state, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                true, true, state, List.of()));
        return new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0), VERSION, sections, Set.of());
    }
}
