package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.protocol.OptionInvalidation;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreOptionCatalogCacheTest {
    @Test
    void exactOwnersAndServersKeepMatchingLocalOptionIdsIsolated() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId firstServer = ServerId.deterministic("core-option-first");
        ServerId secondServer = ServerId.deterministic("core-option-second");
        ContractRef<CapabilityId> firstQuery = ContractRef.of(OwnerId.of("first.owner"), CapabilityId.of("options"));
        ContractRef<CapabilityId> secondQuery = ContractRef.of(OwnerId.of("second.owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey firstKey = key(firstServer, firstQuery);
        OptionCatalogCache.CoreKey secondKey = key(secondServer, secondQuery);
        OptionCatalogCache.CompletedCoreCatalog first = catalog(firstKey, "first");
        OptionCatalogCache.CompletedCoreCatalog second = catalog(secondKey, "second");

        store(cache, firstKey, first);
        store(cache, secondKey, second);

        assertNotEquals(firstKey, secondKey);
        assertSame(first, cache.coreSnapshot(firstKey).catalog());
        assertSame(second, cache.coreSnapshot(secondKey).catalog());
    }

    @Test
    void sourcesSharingOneQueryCapabilityKeepIndependentPages() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-shared-query");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey firstKey = key(server, query, "first");
        OptionCatalogCache.CoreKey secondKey = key(server, query, "second");
        OptionCatalogCache.CompletedCoreCatalog first = catalog(firstKey, "first-value");
        OptionCatalogCache.CompletedCoreCatalog second = catalog(secondKey, "second-value");

        store(cache, firstKey, first);
        store(cache, secondKey, second);

        assertNotEquals(firstKey, secondKey);
        assertSame(first, cache.coreSnapshot(firstKey).catalog());
        assertSame(second, cache.coreSnapshot(secondKey).catalog());
    }

    @Test
    void failedRefreshKeepsTheExactPreviousCorePageAsStaleData() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-stale");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey key = key(server, query);
        OptionCatalogCache.CompletedCoreCatalog catalog = catalog(key, "retained");
        store(cache, key, catalog);

        assertTrue(cache.begin(key, true));
        cache.fail(key, "Refresh Failed");

        OptionCatalogCache.CoreCatalogSnapshot snapshot = cache.coreSnapshot(key);
        assertSame(catalog, snapshot.catalog());
        assertEquals("unavailable", snapshot.status());
        assertEquals("Refresh Failed", snapshot.diagnostic());
    }

    @Test
    void duplicateTypedValuesCannotEnterTheCoreCache() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-duplicate");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey key = key(server, query);
        OptionCatalogCache.CompletedCoreCatalog source = catalog(key, "duplicate");
        OptionItem item = source.items().getFirst();
        OptionCatalogCache.CompletedCoreCatalog duplicate = new OptionCatalogCache.CompletedCoreCatalog(
            key.sourceReference(), query, source.revision(), source.invalidationKey(), List.of(item, item), List.of());

        assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(key, false));
        assertThrows(IllegalArgumentException.class, () -> cache.put(key, duplicate));
    }

    @Test
    void invalidationUsesExactSourceAndTreatsRevisionAsAnOpaquePair() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-invalidation");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey first = key(server, query, "first");
        OptionCatalogCache.CoreKey second = key(server, query, "second");
        OptionCatalogCache.CompletedCoreCatalog firstPage = catalog(first, "first-value");
        OptionCatalogCache.CompletedCoreCatalog secondPage = catalog(second, "second-value");
        store(cache, first, firstPage);
        store(cache, second, secondPage);
        OptionInvalidation lowerRevision = new OptionInvalidation(first.sourceReference(), query, server, null, 1L,
            "replacement", Set.of());

        Set<OptionCatalogCache.CoreKey> invalidated = invalidate(cache, first, lowerRevision);

        assertEquals(Set.of(first), invalidated);
        assertEquals("stale", cache.coreSnapshot(first).status());
        assertEquals("available", cache.coreSnapshot(second).status());
    }

    @Test
    void equalCompletedPairIsIdempotentAndDependencyFieldsTargetContextOrDependencies() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-dependencies");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey context = key(server, query, "options", Set.of("project"), Set.of(), "first");
        OptionCatalogCache.CoreKey dependency = key(server, query, "options", Set.of(), Set.of("project"), "second");
        OptionCatalogCache.CoreKey unrelated = key(server, query, "options", Set.of("world"), Set.of(), "third");
        OptionCatalogCache.CompletedCoreCatalog contextPage = catalog(context, "context-value");
        store(cache, context, contextPage);
        store(cache, dependency, catalog(dependency, "dependency-value"));
        store(cache, unrelated, catalog(unrelated, "unrelated-value"));
        assertTrue(cache.begin(context, true));
        OptionInvalidation equal = new OptionInvalidation(context.sourceReference(), query, server, null,
            contextPage.revision(), contextPage.invalidationKey(), Set.of("project"));

        assertTrue(invalidate(cache, context, equal).isEmpty());
        assertTrue(cache.coreSnapshot(context).loading());

        OptionInvalidation changed = new OptionInvalidation(context.sourceReference(), query, server, null, 3L,
            "changed", Set.of("project"));
        Set<OptionCatalogCache.CoreKey> invalidated = invalidate(cache, context, changed);
        assertEquals(Set.of(context, dependency), invalidated);
        assertFalse(cache.coreSnapshot(context).loading());
        assertEquals("stale", cache.coreSnapshot(context).status());
        assertEquals("stale", cache.coreSnapshot(dependency).status());
        assertEquals("available", cache.coreSnapshot(unrelated).status());
    }

    @Test
    void nonNullInvalidationResourceIsExactWhileNullIsBroad() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-resource-scope");
        OwnerId owner = OwnerId.of("owner");
        ContractRef<CapabilityId> query = ContractRef.of(owner, CapabilityId.of("options"));
        ContractRef<ResourceTypeId> type = ContractRef.of(owner, ResourceTypeId.of("project"));
        ServerResourceLocator firstResource = new ServerResourceLocator(server, type, "first");
        ServerResourceLocator secondResource = new ServerResourceLocator(server, type, "second");
        OptionCatalogCache.CoreKey first = key(server, query, "options", firstResource, "first");
        OptionCatalogCache.CoreKey second = key(server, query, "options", secondResource, "second");
        store(cache, first, catalog(first, "first-value"));
        store(cache, second, catalog(second, "second-value"));

        OptionInvalidation exact = new OptionInvalidation(first.sourceReference(), query, server, firstResource, 3L,
            "exact", Set.of());
        assertEquals(Set.of(first), invalidate(cache, first, exact));
        assertEquals("available", cache.coreSnapshot(second).status());

        OptionInvalidation broad = new OptionInvalidation(first.sourceReference(), query, server, null, 4L,
            "broad", Set.of());
        assertEquals(Set.of(first, second), invalidate(cache, first, broad));
        assertEquals("stale", cache.coreSnapshot(second).status());
    }

    @Test
    void accessOrderBoundsKeysAndRetainedItemWeight() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none(), 3, 5);
        ServerId server = ServerId.deterministic("core-option-bounds");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey first = key(server, query, "first");
        OptionCatalogCache.CoreKey second = key(server, query, "second");
        OptionCatalogCache.CoreKey third = key(server, query, "third");
        OptionCatalogCache.CoreKey fourth = key(server, query, "fourth");
        store(cache, first, catalog(first, "a", "b"));
        store(cache, second, catalog(second, "c", "d"));
        assertEquals("available", cache.coreSnapshot(first).status());
        store(cache, third, catalog(third, "e", "f"));

        assertEquals("available", cache.coreSnapshot(first).status());
        assertEquals("missing", cache.coreSnapshot(second).status());
        assertEquals(2, cache.coreMetrics().keys());
        assertEquals(4, cache.coreMetrics().items());

        assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(fourth, false));
        assertEquals(OptionCatalogCache.CoreAdmission.COALESCED, cache.admit(fourth, false));
        assertEquals(3, cache.coreMetrics().keys());
        assertEquals(1, cache.coreMetrics().inFlight());
    }

    @Test
    void unresolvedEntriesAreNeverEvictedAndOversizedSettlementFailsExplicitly() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none(), 2, 2);
        ServerId server = ServerId.deterministic("core-option-unresolved");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey first = key(server, query, "first");
        OptionCatalogCache.CoreKey second = key(server, query, "second");
        OptionCatalogCache.CoreKey blocked = key(server, query, "blocked");
        assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(first, false));
        assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(second, false));
        assertEquals(OptionCatalogCache.CoreAdmission.BUSY, cache.admit(blocked, false));

        assertEquals(OptionCatalogCache.CoreSettlement.REJECTED,
            cache.put(first, catalog(first, "one", "two", "three")));
        assertEquals("unavailable", cache.coreSnapshot(first).status());
        assertFalse(cache.coreSnapshot(first).loading());
        assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(blocked, false));
        assertTrue(cache.coreSnapshot(second).loading());
    }

    @Test
    void fullClientItemLimitRejectsTheFirstExcessItemAndSettlesTheReservation() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-full-item-limit");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey key = key(server, query, "oversized");
        OptionItem item = catalog(key, "value").items().getFirst();
        OptionCatalogCache.CompletedCoreCatalog oversized = new OptionCatalogCache.CompletedCoreCatalog(
            key.sourceReference(), key.query(), 1L, "oversized", Collections.nCopies(
                OptionCatalogCache.MAX_CORE_ITEMS + 1, item), List.of());
        assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(key, false));

        assertEquals(OptionCatalogCache.CoreSettlement.REJECTED, cache.put(key, oversized));
        assertEquals("unavailable", cache.coreSnapshot(key).status());
        assertFalse(cache.coreSnapshot(key).loading());
        assertEquals(0, cache.coreMetrics().items());
    }

    @Test
    void publicationInstallPurgesOldPublicationAndEpochAfterDisconnectFallback() {
        OptionCatalogCache cache = new OptionCatalogCache(OptionCatalogCache.Storage.none());
        ServerId server = ServerId.deterministic("core-option-publication");
        ContractRef<CapabilityId> query = ContractRef.of(OwnerId.of("owner"), CapabilityId.of("options"));
        OptionCatalogCache.CoreKey oldPublication = key(server, query, "old");
        OptionCatalogCache.CompletedCoreCatalog retained = catalog(oldPublication, "retained");
        store(cache, oldPublication, retained);

        cache.markCoreServerStale(server);
        assertSame(retained, cache.coreSnapshot(oldPublication).catalog());
        assertEquals("stale", cache.coreSnapshot(oldPublication).status());

        CatalogBinding replacement = new CatalogBinding(2L, "4".repeat(64), "5".repeat(64));
        OptionCatalogCache.CoreKey oldEpoch = new OptionCatalogCache.CoreKey(server,
            ContractRef.of(query.owner(), InspectorFieldId.of("old-epoch")), query, null, "{}", "{}", null,
            Set.of(), Set.of(), replacement, new CatalogVersion(1, 3), new ContentHash("6".repeat(64)), 7L, 7L);
        store(cache, oldEpoch, catalog(oldEpoch, "old-epoch"));
        cache.retainCorePublication(server, replacement, new CatalogVersion(1, 3),
            new ContentHash("6".repeat(64)), 7L, 8L);
        assertEquals("missing", cache.coreSnapshot(oldPublication).status());
        assertEquals("missing", cache.coreSnapshot(oldEpoch).status());
        assertEquals(0, cache.coreMetrics().keys());
    }

    private static OptionCatalogCache.CoreKey key(ServerId server, ContractRef<CapabilityId> query) {
        return key(server, query, "options");
    }

    private static OptionCatalogCache.CoreKey key(ServerId server, ContractRef<CapabilityId> query, String sourceId) {
        return key(server, query, sourceId, Set.of(), Set.of(), "");
    }

    private static OptionCatalogCache.CoreKey key(ServerId server, ContractRef<CapabilityId> query, String sourceId,
                                                   Set<String> contextFields, Set<String> dependencyFields,
                                                   String search) {
        return key(server, query, sourceId, null, contextFields, dependencyFields, search);
    }

    private static OptionCatalogCache.CoreKey key(ServerId server, ContractRef<CapabilityId> query, String sourceId,
                                                   ServerResourceLocator resource, String search) {
        return key(server, query, sourceId, resource, Set.of(), Set.of(), search);
    }

    private static OptionCatalogCache.CoreKey key(ServerId server, ContractRef<CapabilityId> query, String sourceId,
                                                   ServerResourceLocator resource, Set<String> contextFields,
                                                   Set<String> dependencyFields, String search) {
        String hash = "1".repeat(64);
        ContractRef<InspectorFieldId> source = ContractRef.of(query.owner(), InspectorFieldId.of(sourceId));
        return new OptionCatalogCache.CoreKey(server, source, query, resource, "{}", "{}", search,
            contextFields, dependencyFields, new CatalogBinding(1L, hash, "2".repeat(64)),
            new CatalogVersion(1, 3), new ContentHash("3".repeat(64)), 4L, 5L);
    }

    private static OptionCatalogCache.CompletedCoreCatalog catalog(OptionCatalogCache.CoreKey key, String... ids) {
        ContractRef<ResourceTypeId> type = ContractRef.of(key.query().owner(), ResourceTypeId.of("resource"));
        TypeExpr.ResourceType optionType = new TypeExpr.ResourceType(
            new TypeReference(type.owner().canonicalText(), type.id().value()));
        List<OptionItem> items = Arrays.stream(ids).map(id -> {
            ServerResourceLocator locator = new ServerResourceLocator(key.serverId(), type, id);
            return new OptionItem(TypedValue.locator(optionType, locator), id, id + " description", true, null);
        }).toList();
        return new OptionCatalogCache.CompletedCoreCatalog(key.sourceReference(), key.query(), 9L,
            "resource-options:fixture", items, List.of());
    }

    private static void store(OptionCatalogCache cache, OptionCatalogCache.CoreKey key,
                              OptionCatalogCache.CompletedCoreCatalog catalog) {
        assertEquals(OptionCatalogCache.CoreAdmission.STARTED, cache.admit(key, false));
        assertEquals(OptionCatalogCache.CoreSettlement.STORED, cache.put(key, catalog));
    }

    private static Set<OptionCatalogCache.CoreKey> invalidate(OptionCatalogCache cache,
                                                               OptionCatalogCache.CoreKey key,
                                                               OptionInvalidation invalidation) {
        return cache.invalidate(invalidation, key.catalogBinding(), key.publicationContractVersion(),
            key.publicationChecksum(), key.publicationRevision(), key.authorityEpoch());
    }
}
