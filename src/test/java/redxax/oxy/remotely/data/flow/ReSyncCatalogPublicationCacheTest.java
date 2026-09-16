package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalLimits;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCatalogPublicationCacheTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final OwnerId OWNER = new OwnerId("resync.publication");
    private static final ContractRef<NodeId> ENTRY = ContractRef.of(OWNER, new NodeId("future-node"));
    private static final ContractRef<NodeId> REMOVED = ContractRef.of(OWNER, new NodeId("removed-node"));
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final ContentHash CHECKSUM = new ContentHash("a".repeat(64));
    private static final ContentHash BINDING_HASH = new ContentHash("f".repeat(64));
    private static final CatalogCachePublicationCodec CODEC = new CatalogCachePublicationCodec();

    @Test
    void restartHydratesTheLastValidFullAndDeltaProjection(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCacheKey key = key(SERVER);
        ReSyncCatalogPublicationProjection first = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));
        CatalogCachePublication full = full(key, 1, "first");
        byte[] fullBytes = CODEC.encodeBytes(full);
        CatalogCachePublication delta = new CatalogCachePublication(CatalogCachePublication.Kind.DELTA, key, 2,
            List.of(present(ENTRY, 2, "second"), CatalogCachePublication.Entry.tombstone(REMOVED, 2)),
            Map.of("futurePublicationField", Map.of("keep", true)));
        byte[] deltaBytes = CODEC.encodeBytes(delta);

        assertTrue(first.apply(full, fullBytes));
        assertTrue(first.apply(delta, deltaBytes));

        ReSyncCatalogPublicationProjection restarted = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));

        assertTrue(restarted.hydrateFromCache());
        ReSyncCatalogPublicationProjection.Snapshot active = restarted.active().orElseThrow();
        assertEquals(delta, active.publication());
        assertArrayEquals(deltaBytes, active.canonicalBytes());
        assertEquals(present(ENTRY, 2, "second"), active.entry(ENTRY).orElseThrow());
        assertTrue(active.entry(REMOVED).orElseThrow().tombstone());
        assertEquals(delta.unknown(), active.publication().unknown());
    }

    @Test
    void isolatesCachedPublicationsByCanonicalServerId(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncCatalogPublicationProjection first = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));
        CatalogCachePublication publication = full(key(SERVER), 1, "server-one");
        assertTrue(first.apply(publication, CODEC.encodeBytes(publication)));

        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        ReSyncCatalogPublicationProjection other = new ReSyncCatalogPublicationProjection(OTHER_SERVER, cache);

        assertTrue(cache.latest(SERVER).isPresent());
        assertTrue(cache.latest(OTHER_SERVER).isEmpty());
        assertFalse(other.hydrateFromCache());
        assertTrue(other.active().isEmpty());
        assertTrue(other.acknowledgedKey().isEmpty());
    }

    @Test
    void independentCacheInstancesPreserveEachServersPublication(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncCatalogPublicationCache first = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        ReSyncCatalogPublicationCache second = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        CatalogCachePublication firstPublication = full(key(SERVER), 1, "server-one");
        CatalogCachePublication secondPublication = full(key(OTHER_SERVER), 1, "server-two");

        assertTrue(first.store(SERVER, firstPublication, CODEC.encodeBytes(firstPublication), firstPublication));
        assertTrue(second.store(OTHER_SERVER, secondPublication, CODEC.encodeBytes(secondPublication), secondPublication));

        ReSyncCatalogPublicationCache restarted = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertEquals(firstPublication, restarted.latest(SERVER).orElseThrow().publication());
        assertEquals(secondPublication, restarted.latest(OTHER_SERVER).orElseThrow().publication());
    }

    @Test
    void loadedSnapshotCanBeReadWithoutASecondDiskDecode(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCachePublication publication = full(key(SERVER), 53, "loaded");
        ReSyncCatalogPublicationCache writer = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(writer.store(SERVER, publication, CODEC.encodeBytes(publication), publication));
        ReSyncCatalogPublicationCache loaded = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        Files.delete(path);

        assertEquals(publication, loaded.latestLoaded(SERVER).orElseThrow().publication());
        assertEquals(publication, loaded.latest(SERVER).orElseThrow().publication());
    }

    @Test
    void deferredInstancesShareOneLoadedSnapshot(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCachePublication publication = full(key(SERVER), 53, "shared");
        Map<String, Object> snapshot = Map.of(
            "publication", CODEC.encode(publication),
            "projection", "publication"
        );
        Map<String, Object> root = Map.of(
            "schemaVersion", ReSyncCatalogPublicationCache.SCHEMA_VERSION,
            "servers", Map.of(SERVER.canonicalText(), Map.of(publication.key().canonicalText(), snapshot))
        );
        Files.write(path, JsonValue.fromJava(root).canonicalBytes(CanonicalLimits.catalog()));
        ReSyncStorage storage = DesktopReSyncStorage.fromKey(path);
        ReSyncCatalogPublicationCache first = ReSyncCatalogPublicationCache.deferred(storage, 4);
        ReSyncCatalogPublicationCache second = ReSyncCatalogPublicationCache.deferred(storage, 4);

        assertEquals(publication, first.latestAsync(SERVER).join().orElseThrow().publication());
        Files.delete(path);

        assertEquals(publication, second.latestAsync(SERVER).join().orElseThrow().publication());
    }

    @Test
    void completedLoadsContinueAwayFromTheCallingThread(@TempDir Path tempDir) {
        ReSyncCatalogPublicationCache cache = ReSyncCatalogPublicationCache.deferred(
            DesktopReSyncStorage.fromKey(tempDir.resolve("catalog-publication-cache.json")), 4);
        var completedLoad = cache.latestAsync(SERVER);
        completedLoad.join();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        CompletableFuture<Void> completed = new CompletableFuture<>();

        cache.continueAsync(completedLoad, () -> true, (value, failure) -> {
            callbackThread.set(Thread.currentThread().getName());
            completed.complete(null);
        });
        completed.join();

        assertEquals("ReSync-Catalog-Cache-Continuation", callbackThread.get());
    }

    @Test
    void onePathUsesOneRetentionPolicy(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncStorage storage = DesktopReSyncStorage.fromKey(path);
        ReSyncCatalogPublicationCache first = ReSyncCatalogPublicationCache.deferred(storage, 1);

        assertThrows(IllegalArgumentException.class, () -> ReSyncCatalogPublicationCache.deferred(storage, 4));
        assertSame(storage, first.storage());
    }

    @Test
    void compactFullSnapshotRoundTripsWithoutDuplicatingItsProjection(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCachePublication publication = full(key(SERVER), 53, "compact");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        assertTrue(cache.store(SERVER, publication, CODEC.encodeBytes(publication), publication));

        String encoded = Files.readString(path);
        assertTrue(encoded.contains("\"projection\":\"publication\""));
        assertFalse(encoded.contains("\"projection\":{"));
        ReSyncCatalogPublicationCache.CachedPublication restored = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path))
            .latest(SERVER).orElseThrow();
        assertEquals(publication, restored.publication());
        assertEquals(publication, restored.hydrationProjection());
    }

    @Test
    void readsLegacyV1FullSnapshotWithExplicitProjection(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCachePublication publication = full(key(SERVER), 53, "legacy-v1");
        Map<String, Object> snapshot = Map.of(
            "publication", CODEC.encode(publication),
            "projection", CODEC.encode(publication)
        );
        Map<String, Object> root = Map.of(
            "schemaVersion", ReSyncCatalogPublicationCache.SCHEMA_VERSION,
            "servers", Map.of(SERVER.canonicalText(), Map.of(publication.key().canonicalText(), snapshot))
        );
        Files.write(path, JsonValue.fromJava(root).canonicalBytes(CanonicalLimits.catalog()));

        ReSyncCatalogPublicationCache.CachedPublication restored = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path))
            .latest(SERVER).orElseThrow();
        assertEquals(publication, restored.publication());
        assertEquals(publication, restored.hydrationProjection());
    }

    @Test
    void currentPublicationStoresBesideSupportedLegacyCache(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCacheKey legacyKey = new CatalogCacheKey(SERVER, 1, CHECKSUM);
        CatalogCachePublication legacy = full(legacyKey, 1, "legacy");
        ReSyncCatalogPublicationCache legacyCache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertTrue(legacyCache.store(SERVER, legacy, CODEC.encodeBytes(legacy), legacy));

        CatalogCachePublication current = full(key(SERVER), 2, "current");
        ReSyncCatalogPublicationCache restarted = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        assertTrue(restarted.store(SERVER, current, CODEC.encodeBytes(current), current));
        assertEquals(current, new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).latest(SERVER).orElseThrow().publication());
    }

    @Test
    void exactCacheReplayIsIdempotentButSameRevisionConflictIsRejected(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        CatalogCacheKey key = key(SERVER);
        CatalogCachePublication publication = full(key, 1, "accepted");
        CatalogCachePublication conflict = full(key, 1, "conflict");

        assertTrue(cache.store(SERVER, publication, CODEC.encodeBytes(publication), publication));
        assertTrue(cache.store(SERVER, publication, CODEC.encodeBytes(publication), publication));
        assertFalse(cache.store(SERVER, conflict, CODEC.encodeBytes(conflict), conflict));
        assertEquals(publication, cache.latest(SERVER).orElseThrow().publication());
    }

    @Test
    void exactPreparedReplaySkipsValidationAndDurableRewrite(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        CatalogCachePublication publication = full(key(SERVER), 7, "prepared");
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(projection.apply(publication, CODEC.encodeBytes(publication)));
        ReSyncCatalogPublicationProjection.Snapshot snapshot = projection.active().orElseThrow();

        assertTrue(cache.storeAsync(SERVER, snapshot).join().stored());
        ReSyncCatalogPublicationCache.CacheMetrics stored = cache.metrics();
        assertTrue(cache.storeAsync(SERVER, snapshot).join().stored());

        ReSyncCatalogPublicationCache.CacheMetrics replayed = cache.metrics();
        assertEquals(stored.validatedStores(), replayed.validatedStores());
        assertEquals(stored.durableWrites(), replayed.durableWrites());
        assertEquals(stored.exactReplayStores() + 1L, replayed.exactReplayStores());
    }

    @Test
    void asynchronousStoresPreserveRevisionOrderAndRejectStalePersistence(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        CatalogCacheKey key = key(SERVER);
        CatalogCachePublication first = full(key, 1, "first");
        CatalogCachePublication second = full(key, 2, "second");

        var firstStore = cache.storeAsync(SERVER, first, CODEC.encodeBytes(first), first);
        var secondStore = cache.storeAsync(SERVER, second, CODEC.encodeBytes(second), second);
        ReSyncCatalogPublicationCache.Persistence firstResult = firstStore.join();
        ReSyncCatalogPublicationCache.Persistence secondResult = secondStore.join();
        ReSyncCatalogPublicationCache.Persistence staleResult = cache.storeAsync(SERVER, first,
            CODEC.encodeBytes(first), first).join();

        assertTrue(firstResult.stored());
        assertTrue(secondResult.stored());
        assertFalse(staleResult.stored());
        assertEquals(second, new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).latest(SERVER).orElseThrow().publication());
    }

    @Test
    void persistsCatalogsBeyondTheStandardTokenLimit(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCacheKey key = key(SERVER);
        CatalogCacheOpaque data = CatalogCacheOpaque.of("{\"value\":0}".getBytes(StandardCharsets.UTF_8));
        List<CatalogCachePublication.Entry> entries = new ArrayList<>(50_000);
        for (int index = 0; index < 50_000; index++) {
            entries.add(CatalogCachePublication.Entry.present(
                ContractRef.of(OWNER, new NodeId("node-" + index)), 1, CatalogCacheState.ACTIVE, Set.of(), false, data));
        }
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1,
            entries);
        byte[] canonical = CODEC.encodeBytes(publication);
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        assertTrue(cache.store(SERVER, publication, canonical, publication));
        assertEquals(publication, new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)).latest(SERVER).orElseThrow().publication());
    }

    @Test
    void bytePressureEvictsTheOldestSnapshotAndPersistsTheNewest(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        CatalogCachePublication newest = null;
        for (long generation : List.of(53L, 54L, 55L, 61L)) {
            CatalogCacheKey key = new CatalogCacheKey(SERVER, generation,
                new ContentHash(Long.toHexString(generation).repeat(64).substring(0, 64)), BINDING_HASH, VERSION);
            newest = largeFull(key, generation, (char) ('a' + generation % 20));
            assertTrue(cache.store(SERVER, newest, CODEC.encodeBytes(newest), newest));
        }

        ReSyncCatalogPublicationCache restarted = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertEquals(newest, restarted.latest(SERVER).orElseThrow().publication());
        List<Long> retained = restarted.snapshotState().snapshots().get(SERVER.canonicalText()).values().stream()
            .map(value -> value.key().catalogGeneration()).sorted().toList();
        assertEquals(List.of(61L), retained);
        assertTrue(Files.size(path) <= CanonicalLimits.catalog().inputBytes());
    }

    @Test
    void legacyRetentionCompactsAroundTheCurrentServerAndKeepsItsLatestSnapshot(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        Map<String, Object> servers = new java.util.LinkedHashMap<>();
        ServerId currentServer = null;
        CatalogCachePublication current = null;
        for (int index = 1; index <= 12; index++) {
            ServerId server = new ServerId(UUID.fromString("00000000-0000-4000-8000-%012d".formatted(index)));
            CatalogCacheKey key = new CatalogCacheKey(server, index, CHECKSUM, BINDING_HASH, VERSION);
            CatalogCachePublication publication = full(key, 1, "server-" + index);
            servers.put(server.canonicalText(), Map.of(key.canonicalText(), Map.of(
                "publication", CODEC.encode(publication), "projection", "publication")));
            if (index == 3) {
                currentServer = server;
                current = publication;
            }
        }
        Files.write(path, JsonValue.fromJava(Map.of("schemaVersion", ReSyncCatalogPublicationCache.SCHEMA_VERSION,
            "servers", servers)).canonicalBytes(CanonicalLimits.catalog()));

        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertEquals(current, cache.latest(currentServer).orElseThrow().publication());
        CatalogCacheKey nextKey = new CatalogCacheKey(currentServer, 13, CHECKSUM, BINDING_HASH, VERSION);
        CatalogCachePublication next = full(nextKey, 2, "current");

        assertTrue(cache.store(currentServer, next, CODEC.encodeBytes(next), next));

        ReSyncCatalogPublicationCache restarted = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        assertEquals(next, restarted.latest(currentServer).orElseThrow().publication());
        assertTrue(restarted.snapshotState().snapshots().size() <= 8);
        assertEquals(1, restarted.snapshotState().snapshots().get(currentServer.canonicalText()).size());
    }

    @Test
    void deltaProjectionConvergesWithAnEquivalentFullProjection(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCacheKey key = key(SERVER);
        ReSyncCatalogPublicationProjection deltaProjection = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));
        CatalogCachePublication full = full(key, 1, "first");
        CatalogCachePublication delta = new CatalogCachePublication(CatalogCachePublication.Kind.DELTA, key, 2,
            List.of(present(ENTRY, 2, "second"), CatalogCachePublication.Entry.tombstone(REMOVED, 2)));
        assertTrue(deltaProjection.apply(full, CODEC.encodeBytes(full)));
        assertTrue(deltaProjection.apply(delta, CODEC.encodeBytes(delta)));

        CatalogCachePublication convergedFull = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 2,
            List.of(present(ENTRY, 2, "second"), CatalogCachePublication.Entry.tombstone(REMOVED, 2)));
        ReSyncCatalogPublicationProjection fullProjection = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(fullProjection.apply(convergedFull, CODEC.encodeBytes(convergedFull)));

        assertEquals(fullProjection.active().orElseThrow().entries(), deltaProjection.active().orElseThrow().entries());
    }

    @Test
    void corruptedCacheFallsBackWithoutChangingTheCorruptFile(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        byte[] corrupt = "{not-canonical".getBytes(StandardCharsets.UTF_8);
        Files.write(path, corrupt);

        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER, cache);

        assertTrue(cache.latest(SERVER).isEmpty());
        assertFalse(projection.hydrateFromCache());
        assertTrue(projection.active().isEmpty());
        assertArrayEquals(corrupt, Files.readAllBytes(path));
    }

    @Test
    void staleDeltaRetainsTheLastDurableProjection(@TempDir Path tempDir) {
        Path path = tempDir.resolve("catalog-publication-cache.json");
        CatalogCacheKey key = key(SERVER);
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));
        CatalogCachePublication full = full(key, 3, "accepted");
        assertTrue(projection.apply(full, CODEC.encodeBytes(full)));
        CatalogCachePublication stale = new CatalogCachePublication(CatalogCachePublication.Kind.DELTA, key, 4,
            List.of(present(ENTRY, 3, "stale")));

        assertFalse(projection.apply(stale, CODEC.encodeBytes(stale)));

        ReSyncCatalogPublicationProjection restarted = new ReSyncCatalogPublicationProjection(SERVER,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path)));
        assertTrue(restarted.hydrateFromCache());
        assertEquals(full, restarted.active().orElseThrow().publication());
    }

    private static CatalogCacheKey key(ServerId serverId) {
        return new CatalogCacheKey(serverId, 7, CHECKSUM, BINDING_HASH, VERSION);
    }

    private static CatalogCachePublication full(CatalogCacheKey key, long revision, String value) {
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, revision,
            List.of(present(ENTRY, revision, value)));
    }

    private static CatalogCachePublication largeFull(CatalogCacheKey key, long revision, char value) {
        List<CatalogCachePublication.Entry> entries = new ArrayList<>();
        byte[] data = ("{\"value\":\"" + String.valueOf(value).repeat(940_000) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < 9; index++) {
            ContractRef<NodeId> entry = ContractRef.of(OWNER, new NodeId("large-" + revision + "-" + index));
            entries.add(CatalogCachePublication.Entry.present(entry, revision, CatalogCacheState.UNAVAILABLE,
                Set.of(), true, CatalogCacheOpaque.of(data)));
        }
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, revision, entries);
    }

    private static CatalogCachePublication.Entry present(ContractRef<NodeId> key, long revision, String value) {
        byte[] data = ("{\"id\":\"" + key.id().value() + "\",\"value\":\"" + value + "\"}")
            .getBytes(StandardCharsets.UTF_8);
        return CatalogCachePublication.Entry.present(key, revision, CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of(data));
    }
}
