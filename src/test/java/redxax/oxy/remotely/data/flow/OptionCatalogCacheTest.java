package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionCatalogCacheTest {
    @TempDir
    Path tempDirectory;

    @Test
    void legacyRevisionTracksContentAndInvalidation() {
        OptionCatalogCache cache = cache();
        String serverId = "catalog-item-selector-server";
        String sourceId = "server:minecraft:material";
        cache.invalidate(serverId, sourceId);

        assertEquals(0L, cache.legacyRevision(serverId, sourceId));
        assertTrue(cache.put(serverId, sourceId, "first", 1L, List.of("STONE"), List.of()));
        long first = cache.legacyRevision(serverId, sourceId);
        assertTrue(first > 0L);
        assertFalse(cache.put(serverId, sourceId, "first", 2L, List.of("STONE"), List.of()));
        assertEquals(first, cache.legacyRevision(serverId, sourceId));
        cache.invalidate(serverId, sourceId);
        assertEquals(0L, cache.legacyRevision(serverId, sourceId));
        assertTrue(cache.put(serverId, sourceId, "second", 3L, List.of("DIAMOND"), List.of()));
        assertTrue(cache.legacyRevision(serverId, sourceId) > first);
    }

    @Test
    void staleAndLegacyResponsesCannotOverwriteNewerSequencedCatalogs() {
        OptionCatalogCache cache = cache();
        String serverId = "catalog-sequence-server";
        String sourceId = "server:fixture:resources";
        cache.invalidate(serverId, sourceId);

        assertTrue(cache.put(serverId, sourceId, "new", 20L, List.of("new"), List.of()));
        assertFalse(cache.put(serverId, sourceId, "old", 19L, List.of("old"), List.of()));
        assertFalse(cache.put(serverId, sourceId, "legacy", 0L, List.of("legacy"), List.of()));
        assertEquals(List.of("new"), cache.getValues(serverId, sourceId));

        assertFalse(cache.put(serverId, sourceId, "same", 21L, List.of("new"), List.of()));
        assertFalse(cache.put(serverId, sourceId, "late", 20L, List.of("late"), List.of()));
        assertEquals(List.of("new"), cache.getValues(serverId, sourceId));
    }

    @Test
    void contextualCatalogsRemainIsolatedForTheSameSource() {
        OptionCatalogCache cache = cache();
        String serverId = "catalog-context-server";
        String sourceId = "server:fixture:contextual";
        String survival = "{\"mode\":\"survival\"}";
        String creative = "{\"mode\":\"creative\"}";
        cache.invalidate(serverId, sourceId);

        assertTrue(cache.put(serverId, sourceId, survival, "survival-1", 1L, List.of("stone"), List.of()));
        assertTrue(cache.put(serverId, sourceId, creative, "creative-1", 2L, List.of("barrier"), List.of()));

        assertEquals(List.of("stone"), cache.getValues(serverId, sourceId, survival));
        assertEquals(List.of("barrier"), cache.getValues(serverId, sourceId, creative));
    }

    @Test
    void richItemsCanBeReadAcrossCachedContextsWithoutMergingTheirValues() {
        OptionCatalogCache cache = cache();
        String serverId = "catalog-preview-server";
        String sourceId = "server:custom_content:asset";
        OptionCatalogItem item = new OptionCatalogItem();
        item.setValue("ruby_sword");
        item.setLabel("Ruby Sword");
        item.setMetadata(Map.of("provider", "nexo", "material", "PAPER"));
        OptionCatalogItem block = new OptionCatalogItem();
        block.setValue("ruby_ore");
        block.setLabel("Ruby Ore");
        block.setMetadata(Map.of("provider", "nexo", "material", "STONE"));

        cache.put(serverId, sourceId, "{\"content_type\":\"item\"}", "items", 1L, List.of("ruby_sword"), List.of(item));
        cache.put(serverId, sourceId, "{\"content_type\":\"block\"}", "blocks", 2L, List.of("ruby_ore"), List.of(block));

        assertEquals(List.of("ruby_sword"), cache.getValues(serverId, sourceId, "{\"content_type\":\"item\"}"));
        assertEquals(List.of("ruby_ore"), cache.getValues(serverId, sourceId, "{\"content_type\":\"block\"}"));
        assertEquals(List.of("Ruby Ore", "Ruby Sword"), cache.getItemsAcrossContexts(serverId, sourceId).stream()
            .map(OptionCatalogItem::getLabel).toList());
    }

    @Test
    void catalogFailureStatesRemainDistinct() {
        OptionCatalogCache cache = cache();
        String serverId = "catalog-diagnostic-server";
        String sourceId = "server:fixture:restricted";
        cache.invalidate(serverId, sourceId);

        assertTrue(cache.put(serverId, sourceId, "", "restricted-1", 1L, List.of(), List.of(), "restricted", "Permission required"));

        assertEquals("restricted", cache.getStatus(serverId, sourceId, ""));
        assertEquals("Permission required", cache.getDiagnostic(serverId, sourceId, ""));
        cache.markStale(serverId, sourceId);
        assertTrue(cache.markRequestInFlight(serverId, sourceId));
        OptionCatalogCache.LegacyCatalogSnapshot snapshot = cache.snapshot(serverId, sourceId, "");
        assertEquals("stale", snapshot.status());
        assertEquals("Cached catalog is awaiting refresh", snapshot.diagnostic());
        assertTrue(snapshot.requestInFlight());
    }

    @Test
    void richCatalogsSurviveOfflineCacheRestoration() {
        Path path = tempDirectory.resolve("catalogs.json");
        OptionCatalogItem item = new OptionCatalogItem();
        item.setValue("request:dragon_hunt");
        item.setLabel("Dragon Hunt");
        item.setDescription("Defeat The Ender Dragon");
        item.setIcon("minecraft:dragon_head");
        item.setGroup("Campaign");
        item.setMetadata(Map.of("owner", "request", "available", true));
        OptionCatalogCache cache = new OptionCatalogCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        cache.put("server-a", "server:request:quests", "{\"world\":\"world\"}", "quests-42", 42L,
            List.of("request:dragon_hunt"), List.of(item), "available", "");
        cache.persistenceCompletion().join();
        OptionCatalogCache restored = new OptionCatalogCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(path));

        assertEquals(List.of("request:dragon_hunt"), restored.getValues("server-a", "server:request:quests", "{\"world\":\"world\"}"));
        assertEquals("Dragon Hunt", restored.getItems("server-a", "server:request:quests", "{\"world\":\"world\"}").getFirst().getLabel());
        assertEquals("Campaign", restored.getItems("server-a", "server:request:quests", "{\"world\":\"world\"}").getFirst().getGroup());
        assertTrue(restored.isStale("server-a", "server:request:quests", "{\"world\":\"world\"}"));
        assertEquals("stale", restored.getStatus("server-a", "server:request:quests", "{\"world\":\"world\"}"));
        assertEquals("Cached catalog is awaiting refresh", restored.getDiagnostic("server-a", "server:request:quests", "{\"world\":\"world\"}"));
    }

    @Test
    void exactAuthoritativeRefreshDoesNotRewriteTheDurableCache() {
        OptionCatalogCache cache = new OptionCatalogCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("exact-catalogs.json")), true);
        String serverId = "catalog-exact-server";
        String sourceId = "server:fixture:exact";

        assertTrue(cache.put(serverId, sourceId, "exact", 8L, List.of("value"), List.of()));
        cache.persistenceCompletion().join();
        OptionCatalogCache.PersistenceMetrics persisted = cache.persistenceMetrics();
        long lookupRevision = cache.lookup(serverId, sourceId).revision();
        cache.markStale(serverId, sourceId);

        assertTrue(cache.put(serverId, sourceId, "exact", 8L, List.of("value"), List.of()));

        OptionCatalogCache.PersistenceMetrics refreshed = cache.persistenceMetrics();
        assertEquals(persisted.writes(), refreshed.writes());
        assertEquals(persisted.requestedRevision(), refreshed.requestedRevision());
        assertEquals(persisted.persistedRevision(), refreshed.persistedRevision());
        assertEquals(persisted.unchangedRefreshes() + 1L, refreshed.unchangedRefreshes());
        assertEquals(lookupRevision, cache.lookup(serverId, sourceId).revision());
    }

    @Test
    void reconnectRefreshCanReplaceAHighSequenceFromThePreviousSession() {
        OptionCatalogCache cache = cache();
        String serverId = "catalog-reconnect-server";
        String sourceId = "server:fixture:resources";
        cache.put(serverId, sourceId, "old", 900L, List.of("old"), List.of());
        cache.markServerStale(serverId);

        assertTrue(cache.markRequestInFlight(serverId, sourceId));
        assertTrue(cache.put(serverId, sourceId, "fresh", 1L, List.of("fresh"), List.of()));
        assertEquals(List.of("fresh"), cache.getValues(serverId, sourceId));
        assertFalse(cache.isStale(serverId, sourceId, ""));
    }

    @Test
    void refreshKeepsTheLastCatalogUsableUntilItsReplacementArrives() {
        OptionCatalogCache cache = cache();
        String serverId = "catalog-refresh-server";
        String sourceId = "server:fixture:resources";
        cache.put(serverId, sourceId, "old", 8L, List.of("cached"), List.of());

        cache.markStale(serverId, sourceId);

        assertTrue(cache.hasCatalog(serverId, sourceId));
        assertEquals(List.of("cached"), cache.getValues(serverId, sourceId));
        assertTrue(cache.isStale(serverId, sourceId, ""));
        assertTrue(cache.markRequestInFlight(serverId, sourceId));
        assertTrue(cache.put(serverId, sourceId, "new", 1L, List.of("fresh"), List.of()));
        assertEquals(List.of("fresh"), cache.getValues(serverId, sourceId));
    }

    private OptionCatalogCache cache() {
        return new OptionCatalogCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalogs.json")));
    }
}
