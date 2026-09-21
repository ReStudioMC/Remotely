package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCatalogPublicationReceiptHandlerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("66666666-6666-4666-8666-666666666666"));
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final ContentHash BINDING_HASH = new ContentHash("f".repeat(64));
    private static final OwnerId OWNER = new OwnerId("resync.receipt");
    private static final ContractRef<NodeId> NODE = ContractRef.of(OWNER, new NodeId("opaque-node"));
    private static final String SESSION = "remotely-receipt-session";
    private static final String SESSION_OWNER = "remotely-receipt-owner";

    @Test
    void appliesOnlyAfterDispatchAndClientReceiptAndRetainsOpaqueData() {
        CatalogCachePublication publication = publication(new CatalogCacheKey(SERVER, 3,
            new ContentHash("a".repeat(64)), BINDING_HASH, VERSION), 7, "opaque");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION, projection);
        byte[] bytes = new CatalogCachePublicationCodec().encodeBytes(publication);

        CatalogPublicationReceipt dispatched = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, publication).dispatched();
        ReSyncCatalogPublicationReceiptHandler.Application dispatchOnly = handler.apply(dispatched, publication, bytes);
        assertTrue(dispatchOnly.readOnly());
        assertTrue(projection.active().isEmpty());

        CatalogPublicationReceipt receipt = dispatched.clientReceived(publication.key(), publication.revision())
            .receipt().orElseThrow();
        ReSyncCatalogPublicationReceiptHandler.Application applied = handler.apply(receipt, publication, bytes);
        assertTrue(applied.applied());
        ReSyncCatalogPublicationProjection.Snapshot active = projection.active().orElseThrow();
        assertEquals(publication.unknown(), active.publication().unknown());
        assertEquals(publication.entries().getFirst(), active.entry(NODE).orElseThrow());
        assertArrayEquals(bytes, active.canonicalBytes());
    }

    @Test
    void rejectsMismatchedAndStaleReceiptsWithoutChangingTheActiveProjection() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 4, new ContentHash("b".repeat(64)), BINDING_HASH, VERSION);
        CatalogCachePublication first = publication(key, 8, "first");
        CatalogCachePublication second = publication(key, 9, "second");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION, projection);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogPublicationReceipt firstReceipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, first).dispatched()
            .clientReceived(first.key(), first.revision()).receipt().orElseThrow();
        assertTrue(handler.apply(firstReceipt, first, codec.encodeBytes(first)).applied());
        ReSyncCatalogPublicationProjection.Snapshot previous = projection.active().orElseThrow();

        CatalogCacheKey wrongKey = new CatalogCacheKey(OTHER_SERVER, 4, new ContentHash("b".repeat(64)), BINDING_HASH, VERSION);
        CatalogPublicationReceipt wrongReceipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, second).dispatched()
            .clientReceived(second.key(), second.revision()).receipt().orElseThrow();
        CatalogCachePublication wrongPublication = publication(wrongKey, 10, "wrong-server");
        assertTrue(handler.apply(wrongReceipt, wrongPublication, codec.encodeBytes(wrongPublication)).readOnly());

        CatalogPublicationReceipt staleReceipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, first).dispatched()
            .clientReceived(first.key(), first.revision()).receipt().orElseThrow();
        assertTrue(handler.apply(staleReceipt, first, codec.encodeBytes(first)).applied());
        assertEquals(previous.publication(), projection.active().orElseThrow().publication());
        assertArrayEquals(previous.canonicalBytes(), projection.active().orElseThrow().canonicalBytes());
    }

    @Test
    void exactActivePublicationIsIdempotentlyAppliedWhileSameRevisionConflictIsRejected() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 6, new ContentHash("d".repeat(64)), BINDING_HASH, VERSION);
        CatalogCachePublication activePublication = publication(key, 12, "active");
        CatalogCachePublication conflictingPublication = publication(key, 12, "conflict");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION, projection);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, activePublication).dispatched()
            .clientReceived(activePublication.key(), activePublication.revision()).receipt().orElseThrow();
        byte[] activeBytes = codec.encodeBytes(activePublication);
        assertTrue(handler.apply(receipt, activePublication, activeBytes).applied());

        assertTrue(handler.apply(receipt, activePublication, activeBytes).applied());
        assertTrue(handler.apply(receipt, conflictingPublication, codec.encodeBytes(conflictingPublication)).readOnly());
        assertEquals(activePublication, projection.active().orElseThrow().publication());
        assertArrayEquals(activeBytes, projection.active().orElseThrow().canonicalBytes());
    }

    @Test
    void rejectsNonCanonicalReceiptBytesWithoutMutatingOpaqueProjection() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 5, new ContentHash("c".repeat(64)), BINDING_HASH, VERSION);
        CatalogCachePublication publication = publication(key, 11, "canonical");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION, projection);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, publication).dispatched()
            .clientReceived(publication.key(), publication.revision()).receipt().orElseThrow();
        byte[] canonical = new CatalogCachePublicationCodec().encodeBytes(publication);
        byte[] nonCanonical = (" {\"revision\":" + publication.revision() + ",\"kind\":\"full\"}").getBytes(StandardCharsets.UTF_8);
        assertTrue(handler.apply(receipt, publication, nonCanonical).readOnly());
        assertTrue(projection.active().isEmpty());
        assertFalse(Arrays.equals(canonical, nonCanonical));
    }

    @Test
    void cacheWriteFailureDoesNotRejectTheLiveProjection(@TempDir Path tempDir) throws Exception {
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(parentFile.resolve("cache.json")));
        CatalogCachePublication publication = publication(new CatalogCacheKey(SERVER, 7,
            new ContentHash("e".repeat(64)), BINDING_HASH, VERSION), 13, "live");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER, cache);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION,
            projection, null, cache);
        byte[] bytes = new CatalogCachePublicationCodec().encodeBytes(publication);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, publication).dispatched()
            .clientReceived(publication.key(), publication.revision()).receipt().orElseThrow();

        assertTrue(handler.apply(receipt, publication, bytes).applied());
        assertEquals(publication, projection.active().orElseThrow().publication());
        assertEquals(ReSyncCatalogPublicationReceiptHandler.PersistenceStatus.REJECTED,
            handler.cachePersistenceCompletion().join().status());
        assertTrue(handler.cachePersistence().diagnostic().startsWith("CATALOG_PUBLICATION.CACHE_STORE_"));
    }

    @Test
    void nodeOnlyPublicationPersistsWhenTheClientSupportsAuthoring(@TempDir Path tempDir) {
        Path path = tempDir.resolve("cache.json");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(
            DesktopReSyncStorage.fromKey(path));
        CatalogCachePublication publication = publication(new CatalogCacheKey(SERVER, 17,
            new ContentHash("7".repeat(64)), BINDING_HASH, VERSION), 23, "node-only");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER, cache);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION,
            projection, new ReSyncCatalogAuthoringProjection(SERVER), cache, List.of("catalog-authoring"));
        byte[] bytes = new CatalogCachePublicationCodec().encodeBytes(publication);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, publication)
            .dispatched().clientReceived(publication.key(), publication.revision()).receipt().orElseThrow();

        assertTrue(handler.apply(receipt, publication, bytes).applied());
        assertEquals(ReSyncCatalogPublicationReceiptHandler.PersistenceStatus.STORED,
            handler.cachePersistenceCompletion().join().status());
        assertTrue(cache.latest(SERVER).orElseThrow().authoringCapabilities().isEmpty());
    }

    @Test
    void rejectedFinalPublisherRestoresTheExactPreparedProjection() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 8, new ContentHash("8".repeat(64)), BINDING_HASH, VERSION);
        CatalogCachePublication first = publication(key, 14, "first");
        CatalogCachePublication replacement = publication(key, 15, "replacement");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER, SESSION,
            projection);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogPublicationReceipt firstReceipt = CatalogPublicationReceipt.pending(SESSION, SESSION_OWNER, first)
            .dispatched().clientReceived(first.key(), first.revision()).receipt().orElseThrow();
        assertTrue(handler.apply(firstReceipt, first, codec.encodeBytes(first)).applied());
        ReSyncCatalogPublicationProjection.Snapshot previous = projection.active().orElseThrow();
        CatalogPublicationReceipt replacementReceipt = CatalogPublicationReceipt
            .pending(SESSION, SESSION_OWNER, replacement).dispatched()
            .clientReceived(replacement.key(), replacement.revision()).receipt().orElseThrow();
        ReSyncCatalogPublicationReceiptHandler.PreparedApplication prepared = handler.prepare(replacementReceipt,
            replacement, codec.encodeBytes(replacement));

        assertTrue(prepared.ready());
        assertTrue(handler.commit(prepared, snapshot -> false).readOnly());
        assertSame(previous, projection.active().orElseThrow());
        assertEquals(key, projection.acknowledgedKey().orElseThrow());
    }

    private static CatalogCachePublication publication(CatalogCacheKey key, long revision, String value) {
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(NODE, revision,
            CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of(("{\"id\":\"opaque-node\",\"value\":\"" + value + "\"}")
                .getBytes(StandardCharsets.UTF_8)), Map.of("futureEntry", Map.of("value", value)));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, revision,
            List.of(entry), Map.of("futurePublication", Map.of("value", value)));
    }
}
