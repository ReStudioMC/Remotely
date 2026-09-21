package redxax.oxy.remotely.data.flow;

import restudio.rescreen.platform.Async;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.protocol.OptionInvalidation;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import restudio.rescreen.platform.Clock;

public class OptionCatalogCache {
    private static final int CACHE_SCHEMA_VERSION = 2;
    private static final long REQUEST_TIMEOUT_MILLIS = 10_000L;
    private static final long CORE_REQUEST_TIMEOUT_MILLIS = 20_000L;
    static final int MAX_CORE_KEYS = 256;
    static final int MAX_CORE_ITEMS = 100_000;
    private static final String KEY_SEPARATOR = "\0";
    private static volatile OptionCatalogCache INSTANCE = new OptionCatalogCache();
    private final Gson gson = new GsonBuilder().create();
    private final Storage storage;
    private final Work work;
    private final Clock clock;
    private final boolean asyncPersistence;
    private final int maxCoreKeys;
    private final int maxCoreItems;
    private final Map<String, Catalog> catalogs = BrowserSafeState.map();
    private final Map<String, Long> inFlightRequests = BrowserSafeState.map();
    private final Set<String> staleCatalogs = BrowserSafeState.set();
    private final Map<String, CatalogLookup> catalogLookups = BrowserSafeState.map();
    private final Object coreLock = new Object();
    private final LinkedHashMap<CoreKey, CoreEntry> coreEntries = new LinkedHashMap<>(16, 0.75f, true);
    private int coreItems;
    private final BrowserSafeState.LongValue catalogLookupRevision = new BrowserSafeState.LongValue(1L);
    private final Object catalogLookupLock = new Object();
    private final Object persistenceLock = new Object();
    private final BrowserSafeState.BooleanValue persistenceScheduled = new BrowserSafeState.BooleanValue();
    private final BrowserSafeState.LongValue persistenceRevision = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue persistedRevision = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue persistenceWrites = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue unchangedRefreshes = new BrowserSafeState.LongValue();
    private volatile Async<Void> persistenceCompletion = Async.completed(null);

    private OptionCatalogCache() {
        this(new ReSyncBackedStorage(ReSyncStorage.legacy("remotely.option-catalogs")),
            DesktopWork.INSTANCE, true, Clock.system());
    }

    public static synchronized OptionCatalogCache install(ReSyncStorage storage, Clock clock) {
        OptionCatalogCache previous = INSTANCE;
        INSTANCE = new OptionCatalogCache(new ReSyncBackedStorage(storage != null ? storage : ReSyncStorage.memory()),
            DesktopWork.INSTANCE, true, clock);
        return previous;
    }

    public static synchronized void restore(OptionCatalogCache previous) {
        INSTANCE = previous != null ? previous : new OptionCatalogCache();
    }

    OptionCatalogCache(ReSyncStorage storage) {
        this(new ReSyncBackedStorage(storage != null ? storage : ReSyncStorage.memory()), Work.direct(), false,
            Clock.system());
    }

    OptionCatalogCache(ReSyncStorage storage, boolean asyncPersistence) {
        this(new ReSyncBackedStorage(storage != null ? storage : ReSyncStorage.memory()),
            asyncPersistence ? DesktopWork.INSTANCE : Work.direct(), asyncPersistence, Clock.system());
    }

    OptionCatalogCache(Storage storage) {
        this(storage, Work.direct(), false, Clock.system());
    }

    OptionCatalogCache(Storage storage, Clock clock) {
        this(storage, Work.direct(), false, clock);
    }

    OptionCatalogCache(Storage storage, int maxCoreKeys, int maxCoreItems) {
        this(storage, Work.direct(), false, Clock.system(), maxCoreKeys, maxCoreItems);
    }

    OptionCatalogCache(Storage storage, Work work, boolean asyncPersistence) {
        this(storage, work, asyncPersistence, Clock.system());
    }

    private OptionCatalogCache(Storage storage, Work work, boolean asyncPersistence, Clock clock) {
        this(storage, work, asyncPersistence, clock, MAX_CORE_KEYS, MAX_CORE_ITEMS);
    }

    private OptionCatalogCache(Storage storage, Work work, boolean asyncPersistence, Clock clock,
                               int maxCoreKeys, int maxCoreItems) {
        this.storage = storage != null ? storage : Storage.none();
        this.work = work != null ? work : Work.direct();
        this.asyncPersistence = asyncPersistence;
        this.clock = clock != null ? clock : Clock.system();
        if (maxCoreKeys < 1 || maxCoreKeys > MAX_CORE_KEYS || maxCoreItems < 1 || maxCoreItems > MAX_CORE_ITEMS) {
            throw new IllegalArgumentException("Core option cache bounds are invalid");
        }
        this.maxCoreKeys = maxCoreKeys;
        this.maxCoreItems = maxCoreItems;
        if (asyncPersistence) {
            try {
                this.work.execute(this::load);
            } catch (RuntimeException exception) {
                ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(OptionCatalogCache.class)
                    .operation("Load Option Catalog").error("Could not schedule option catalog cache load", exception);
            }
        } else {
            load();
        }
    }

    public static OptionCatalogCache getInstance() {
        return INSTANCE;
    }

    public CoreAdmission admit(CoreKey key, boolean forceRefresh) {
        Objects.requireNonNull(key, "Core option catalog key is required");
        synchronized (coreLock) {
            long now = clock.millis();
            CoreEntry entry = coreEntries.get(key);
            if (entry != null) {
                if (entry.inFlight && now - entry.startedAtMillis < CORE_REQUEST_TIMEOUT_MILLIS) {
                    return CoreAdmission.COALESCED;
                }
                if (entry.inFlight) {
                    entry.inFlight = false;
                    entry.failure = null;
                    entry.stale = entry.catalog != null;
                }
                if (!forceRefresh && entry.catalog != null && !entry.stale) {
                    return CoreAdmission.FRESH;
                }
                entry.inFlight = true;
                entry.startedAtMillis = now;
                entry.failure = null;
                entry.stale = entry.catalog != null;
                return CoreAdmission.STARTED;
            }
            if (!makeCoreKeyRoom()) {
                return CoreAdmission.BUSY;
            }
            coreEntries.put(key, CoreEntry.started(now));
            return CoreAdmission.STARTED;
        }
    }

    public boolean begin(CoreKey key, boolean forceRefresh) {
        return admit(key, forceRefresh) == CoreAdmission.STARTED;
    }

    public CoreSettlement put(CoreKey key, CompletedCoreCatalog catalog) {
        Objects.requireNonNull(key, "Core option catalog key is required");
        Objects.requireNonNull(catalog, "Completed Core option catalog is required");
        if (!key.sourceReference().equals(catalog.sourceRef()) || !key.query().equals(catalog.query())) {
            throw new IllegalArgumentException("A matching completed Core option catalog is required");
        }
        if (catalog.items().size() > maxCoreItems) {
            synchronized (coreLock) {
                CoreEntry entry = coreEntries.get(key);
                if (entry != null && entry.inFlight) {
                    entry.inFlight = false;
                    entry.startedAtMillis = 0L;
                    entry.failure = "Option Catalog Exceeds Client Capacity";
                    entry.stale = entry.catalog != null;
                }
                return CoreSettlement.REJECTED;
            }
        }
        Set<String> identities = new HashSet<>();
        for (OptionItem item : catalog.items()) {
            if (!identities.add(item.value().canonicalJson())) {
                throw new IllegalArgumentException("Core option catalog contains duplicate typed values");
            }
        }
        synchronized (coreLock) {
            CoreEntry entry = coreEntries.get(key);
            if (entry == null || !entry.inFlight) {
                return CoreSettlement.REJECTED;
            }
            if (!makeCoreItemRoom(key, catalog.items().size(), entry.itemCount())) {
                entry.inFlight = false;
                entry.startedAtMillis = 0L;
                entry.failure = "Option Catalog Exceeds Client Capacity";
                entry.stale = entry.catalog != null;
                return CoreSettlement.REJECTED;
            }
            CompletedCoreCatalog previous = entry.catalog;
            boolean changed = previous == null || !previous.equals(catalog) || entry.stale;
            coreItems += catalog.items().size() - entry.itemCount();
            entry.catalog = catalog;
            entry.inFlight = false;
            entry.startedAtMillis = 0L;
            entry.stale = false;
            entry.failure = null;
            return changed ? CoreSettlement.STORED : CoreSettlement.UNCHANGED;
        }
    }

    public Set<CoreKey> invalidate(OptionInvalidation invalidation, CatalogBinding catalogBinding,
                                   CatalogVersion publicationContractVersion, ContentHash publicationChecksum,
                                   long publicationRevision, long authorityEpoch) {
        Objects.requireNonNull(invalidation, "Core option invalidation is required");
        synchronized (coreLock) {
            Set<CoreKey> invalidated = new HashSet<>();
            for (Map.Entry<CoreKey, CoreEntry> candidate : coreEntries.entrySet()) {
                CoreKey key = candidate.getKey();
                CoreEntry entry = candidate.getValue();
                if (!matches(key, invalidation, catalogBinding, publicationContractVersion, publicationChecksum,
                    publicationRevision, authorityEpoch)) {
                    continue;
                }
                if (entry.catalog != null && entry.catalog.revision() == invalidation.revision()
                    && entry.catalog.invalidationKey().equals(invalidation.invalidationKey())) {
                    continue;
                }
                entry.stale = entry.catalog != null;
                entry.inFlight = false;
                entry.startedAtMillis = 0L;
                entry.failure = null;
                invalidated.add(key);
            }
            return Set.copyOf(invalidated);
        }
    }

    static boolean matches(CoreKey key, OptionInvalidation invalidation, CatalogBinding catalogBinding,
                           CatalogVersion publicationContractVersion, ContentHash publicationChecksum,
                           long publicationRevision, long authorityEpoch) {
        if (key == null || invalidation == null || catalogBinding == null || publicationContractVersion == null
            || publicationChecksum == null || !key.serverId().equals(invalidation.serverId())
            || !key.sourceReference().equals(invalidation.sourceRef()) || !key.query().equals(invalidation.query())
            || !key.catalogBinding().equals(catalogBinding)
            || !key.publicationContractVersion().equals(publicationContractVersion)
            || !key.publicationChecksum().equals(publicationChecksum)
            || key.publicationRevision() != publicationRevision || key.authorityEpoch() != authorityEpoch
            || invalidation.resource() != null && !invalidation.resource().equals(key.resource())) {
            return false;
        }
        if (invalidation.dependencyKeys().isEmpty()) {
            return true;
        }
        return key.contextFields().stream().anyMatch(invalidation.dependencyKeys()::contains)
            || key.dependencyFields().stream().anyMatch(invalidation.dependencyKeys()::contains);
    }

    public void fail(CoreKey key, String diagnostic) {
        if (key == null) {
            return;
        }
        synchronized (coreLock) {
            CoreEntry entry = coreEntries.get(key);
            if (entry == null) {
                return;
            }
            entry.inFlight = false;
            entry.startedAtMillis = 0L;
            entry.failure = diagnostic == null || diagnostic.isBlank() ? "Option Catalog Unavailable" : diagnostic;
            entry.stale = entry.catalog != null;
        }
    }

    public void retry(CoreKey key) {
        if (key == null) {
            return;
        }
        synchronized (coreLock) {
            CoreEntry entry = coreEntries.get(key);
            if (entry == null) {
                return;
            }
            entry.inFlight = false;
            entry.startedAtMillis = 0L;
            entry.failure = null;
            entry.stale = entry.catalog != null;
        }
    }

    public boolean continueRequest(CoreKey key) {
        if (key == null) {
            return false;
        }
        synchronized (coreLock) {
            CoreEntry entry = coreEntries.get(key);
            return entry != null && entry.inFlight;
        }
    }

    public CoreCatalogSnapshot coreSnapshot(CoreKey key) {
        if (key == null) {
            return CoreCatalogSnapshot.MISSING;
        }
        synchronized (coreLock) {
            CoreEntry entry = coreEntries.get(key);
            if (entry == null) {
                return CoreCatalogSnapshot.MISSING;
            }
            if (entry.catalog == null) {
                String status = entry.failure != null ? "unavailable" : entry.inFlight ? "loading" : "missing";
                String diagnostic = entry.failure != null ? entry.failure : entry.inFlight ? "Option catalog is loading"
                    : "Option catalog has not been loaded";
                return new CoreCatalogSnapshot(null, entry.inFlight, status, diagnostic);
            }
            String status = entry.failure != null ? "unavailable" : entry.stale ? "stale" : "available";
            String diagnostic = entry.failure != null ? entry.failure
                : entry.stale ? "Cached option catalog is awaiting refresh" : "";
            return new CoreCatalogSnapshot(entry.catalog, entry.inFlight, status, diagnostic);
        }
    }

    public void clearCoreRequests(ServerId serverId, String diagnostic) {
        if (serverId == null) {
            return;
        }
        synchronized (coreLock) {
            for (Map.Entry<CoreKey, CoreEntry> candidate : coreEntries.entrySet()) {
                if (serverId.equals(candidate.getKey().serverId()) && candidate.getValue().inFlight) {
                    CoreEntry entry = candidate.getValue();
                    entry.inFlight = false;
                    entry.startedAtMillis = 0L;
                    entry.failure = diagnostic == null || diagnostic.isBlank()
                        ? "Option Catalog Unavailable" : diagnostic;
                    entry.stale = entry.catalog != null;
                }
            }
        }
    }

    public void markCoreServerStale(ServerId serverId) {
        if (serverId == null) {
            return;
        }
        synchronized (coreLock) {
            for (Map.Entry<CoreKey, CoreEntry> candidate : coreEntries.entrySet()) {
                if (!serverId.equals(candidate.getKey().serverId())) {
                    continue;
                }
                CoreEntry entry = candidate.getValue();
                entry.stale = entry.catalog != null;
                if (entry.inFlight) {
                    entry.inFlight = false;
                    entry.startedAtMillis = 0L;
                    entry.failure = "The ReSync connection is unavailable";
                }
            }
        }
    }

    public void retainCorePublication(ServerId serverId, CatalogBinding catalogBinding,
                                      CatalogVersion publicationContractVersion, ContentHash publicationChecksum,
                                      long publicationRevision, long authorityEpoch) {
        if (serverId == null || catalogBinding == null || publicationContractVersion == null
            || publicationChecksum == null) {
            return;
        }
        synchronized (coreLock) {
            Iterator<Map.Entry<CoreKey, CoreEntry>> iterator = coreEntries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<CoreKey, CoreEntry> candidate = iterator.next();
                CoreKey key = candidate.getKey();
                if (!serverId.equals(key.serverId()) || key.catalogBinding().equals(catalogBinding)
                    && key.publicationContractVersion().equals(publicationContractVersion)
                    && key.publicationChecksum().equals(publicationChecksum)
                    && key.publicationRevision() == publicationRevision && key.authorityEpoch() == authorityEpoch) {
                    continue;
                }
                coreItems -= candidate.getValue().itemCount();
                iterator.remove();
            }
        }
    }

    public void purgeCoreServer(ServerId serverId) {
        if (serverId == null) {
            return;
        }
        synchronized (coreLock) {
            Iterator<Map.Entry<CoreKey, CoreEntry>> iterator = coreEntries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<CoreKey, CoreEntry> candidate = iterator.next();
                if (!serverId.equals(candidate.getKey().serverId())) {
                    continue;
                }
                coreItems -= candidate.getValue().itemCount();
                iterator.remove();
            }
        }
    }

    CoreMetrics coreMetrics() {
        synchronized (coreLock) {
            int inFlight = (int) coreEntries.values().stream().filter(entry -> entry.inFlight).count();
            return new CoreMetrics(coreEntries.size(), coreItems, inFlight);
        }
    }

    private boolean makeCoreKeyRoom() {
        if (coreEntries.size() < maxCoreKeys) {
            return true;
        }
        Iterator<Map.Entry<CoreKey, CoreEntry>> iterator = coreEntries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CoreKey, CoreEntry> candidate = iterator.next();
            if (candidate.getValue().inFlight) {
                continue;
            }
            coreItems -= candidate.getValue().itemCount();
            iterator.remove();
            return true;
        }
        return false;
    }

    private boolean makeCoreItemRoom(CoreKey protectedKey, int offeredItems, int replacedItems) {
        long projected = (long) coreItems - replacedItems + offeredItems;
        if (projected <= maxCoreItems) {
            return true;
        }
        long evictable = coreEntries.entrySet().stream()
            .filter(candidate -> !candidate.getKey().equals(protectedKey) && !candidate.getValue().inFlight)
            .mapToLong(candidate -> candidate.getValue().itemCount())
            .sum();
        if (projected - evictable > maxCoreItems) {
            return false;
        }
        Iterator<Map.Entry<CoreKey, CoreEntry>> iterator = coreEntries.entrySet().iterator();
        while (iterator.hasNext() && projected > maxCoreItems) {
            Map.Entry<CoreKey, CoreEntry> candidate = iterator.next();
            if (candidate.getKey().equals(protectedKey) || candidate.getValue().inFlight) {
                continue;
            }
            int removed = candidate.getValue().itemCount();
            iterator.remove();
            coreItems -= removed;
            projected -= removed;
        }
        return projected <= maxCoreItems;
    }

    @Deprecated
    public boolean put(String serverId, String sourceId, String revision, List<String> values) {
        return put(serverId, sourceId, revision, 0L, values, List.of());
    }

    @Deprecated
    public boolean put(String serverId, String sourceId, String revision, List<String> values, List<OptionCatalogItem> items) {
        return put(serverId, sourceId, revision, 0L, values, items);
    }

    @Deprecated
    public boolean put(String serverId, String sourceId, String revision, long sequence, List<String> values, List<OptionCatalogItem> items) {
        return put(serverId, sourceId, "", revision, sequence, values, items);
    }

    @Deprecated
    public boolean put(String serverId, String sourceId, String contextKey, String revision, long sequence, List<String> values, List<OptionCatalogItem> items) {
        return put(serverId, sourceId, contextKey, revision, sequence, values, items, "available", "");
    }

    @Deprecated
    public boolean put(String serverId, String sourceId, String contextKey, String revision, long sequence, List<String> values, List<OptionCatalogItem> items,
        String status, String diagnostic) {
        List<OptionCatalogItem> safeItems = items != null ? items.stream().filter(item -> item != null).toList() : List.of();
        if (safeItems.stream().anyMatch(OptionCatalogItem::isResource)) {
            throw new IllegalArgumentException("A resource catalog requires a typed catalog key");
        }
        List<String> safeValues = values != null ? values.stream().filter(value -> value != null && !value.isBlank()).toList() : List.of();
        if (safeValues.isEmpty() && !safeItems.isEmpty()) {
            safeValues = safeItems.stream().map(OptionCatalogItem::getValue).filter(value -> value != null && !value.isBlank()).toList();
        }
        Catalog next = new Catalog(revision, Math.max(0L, sequence), safeValues, safeItems, status, diagnostic, false);
        return put(legacyKey(serverId, sourceId, contextKey), next, () -> rebuildAcrossContexts(serverId, sourceId), serverId,
            sourceId, contextKey);
    }

    public boolean put(CatalogKey key, String revision, long sequence, List<OptionCatalogItem> items) {
        return put(key, revision, sequence, items, "available", "");
    }

    public boolean put(CatalogKey key, String revision, long sequence, List<OptionCatalogItem> items, String status, String diagnostic) {
        if (key == null) {
            throw new IllegalArgumentException("A typed catalog key is required");
        }
        List<OptionCatalogItem> safeItems = items != null ? items.stream().filter(item -> item != null).toList() : List.of();
        for (OptionCatalogItem item : safeItems) {
            ServerResourceLocator resource = item.getResource();
            if (resource == null || !key.matches(resource)) {
                throw new IllegalArgumentException("Every resource option must match the catalog server and type");
            }
        }
        Catalog next = new Catalog(revision, Math.max(0L, sequence), List.of(), safeItems, status, diagnostic, true);
        return put(key.storageKey(), next, () -> rebuildAcrossContexts(key), key.serverId().canonicalText(), key.sourceId(),
            key.contextKey());
    }

    private boolean put(String key, Catalog offered, Runnable rebuild, String serverId, String sourceId, String contextKey) {
        BrowserSafeState.BooleanValue changed = new BrowserSafeState.BooleanValue();
        BrowserSafeState.BooleanValue accepted = new BrowserSafeState.BooleanValue();
        BrowserSafeState.BooleanValue projectionChanged = new BrowserSafeState.BooleanValue();
        BrowserSafeState.BooleanValue persistenceChanged = new BrowserSafeState.BooleanValue();
        synchronized (catalogLookupLock) {
            boolean stale = staleCatalogs.contains(key);
            catalogs.compute(key, (ignored, previous) -> {
                Catalog next = offered.retain(previous);
                if (!stale && previous != null && previous.isNewerThan(next)) {
                    return previous;
                }
                accepted.set(true);
                changed.set(stale || previous == null || !previous.sameContent(next));
                projectionChanged.set(previous == null || !previous.sameContent(next));
                persistenceChanged.set(previous == null || !previous.equals(next));
                return next;
            });
            if (projectionChanged.get()) {
                publishLookup(key, catalogs.get(key));
                rebuild.run();
            }
            inFlightRequests.remove(key);
            if (accepted.get()) staleCatalogs.remove(key);
        }
        if (accepted.get()) {
            if (persistenceChanged.get()) {
                scheduleSave();
            } else {
                unchangedRefreshes.incrementAndGet();
                ReSyncFlowClient.traceLifecycle(serverId, "option_catalog_persistence_skipped", "sourceId", sourceId,
                    "contextKey", contextKey, "revision", offered.revision(), "sequence", offered.sequence());
            }
        }
        return changed.get();
    }

    public List<String> getValues(String serverId, String sourceId) {
        return getValues(serverId, sourceId, "");
    }

    public List<String> getValues(String serverId, String sourceId, String contextKey) {
        Catalog catalog = catalogs.get(legacyKey(serverId, sourceId, contextKey));
        return catalog != null ? catalog.values() : List.of();
    }

    public LegacyCatalogSnapshot snapshot(String serverId, String sourceId, String contextKey) {
        String key = legacyKey(serverId, sourceId, contextKey);
        synchronized (catalogLookupLock) {
            Catalog catalog = catalogs.get(key);
            CatalogLookup lookup = catalogLookups.getOrDefault(key, CatalogLookup.EMPTY);
            if (catalog == null) {
                return LegacyCatalogSnapshot.missing(isRequestInFlight(key));
            }
            boolean stale = staleCatalogs.contains(key);
            return new LegacyCatalogSnapshot(true, lookup.revision(), catalog.values(), catalog.items(), stale,
                isRequestInFlight(key), stale ? "stale" : catalog.status(),
                stale ? "Cached catalog is awaiting refresh" : catalog.diagnostic());
        }
    }

    public boolean hasValues(String serverId, String sourceId) {
        Catalog catalog = catalogs.get(legacyKey(serverId, sourceId, ""));
        return catalog != null && !catalog.values().isEmpty();
    }

    public boolean hasCatalog(String serverId, String sourceId) {
        return hasCatalog(serverId, sourceId, "");
    }

    public boolean hasCatalog(String serverId, String sourceId, String contextKey) {
        return catalogs.containsKey(legacyKey(serverId, sourceId, contextKey));
    }

    public boolean hasCatalog(CatalogKey key) {
        return key != null && catalogs.containsKey(key.storageKey());
    }

    public void invalidate(String serverId, String sourceId) {
        String prefix = legacyPrefix(serverId, sourceId);
        boolean changed;
        synchronized (catalogLookupLock) {
            changed = catalogs.keySet().removeIf(key -> key.startsWith(prefix));
            catalogLookups.keySet().removeIf(key -> key.startsWith(prefix));
            catalogLookups.remove(crossContextKey(serverId, sourceId));
            inFlightRequests.keySet().removeIf(key -> key.startsWith(prefix));
            staleCatalogs.removeIf(key -> key.startsWith(prefix));
        }
        if (changed) {
            scheduleSave();
        }
    }

    public void invalidate(String serverId, String sourceId, String contextKey) {
        String key = legacyKey(serverId, sourceId, contextKey);
        boolean changed;
        synchronized (catalogLookupLock) {
            changed = catalogs.remove(key) != null;
            catalogLookups.remove(key);
            rebuildAcrossContexts(serverId, sourceId);
            inFlightRequests.remove(key);
            staleCatalogs.remove(key);
        }
        if (changed) {
            scheduleSave();
        }
    }

    public void invalidate(CatalogKey catalogKey) {
        if (catalogKey == null) {
            return;
        }
        String key = catalogKey.storageKey();
        boolean changed;
        synchronized (catalogLookupLock) {
            changed = catalogs.remove(key) != null;
            catalogLookups.remove(key);
            rebuildAcrossContexts(catalogKey);
            inFlightRequests.remove(key);
            staleCatalogs.remove(key);
        }
        if (changed) {
            scheduleSave();
        }
    }

    public void invalidateAll(String serverId, List<String> sourceIds) {
        if (sourceIds == null) {
            return;
        }
        for (String sourceId : sourceIds) {
            invalidate(serverId, sourceId);
        }
    }

    public void markStale(String serverId, String sourceId) {
        String prefix = legacyPrefix(serverId, sourceId);
        synchronized (catalogLookupLock) {
            catalogs.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(staleCatalogs::add);
            inFlightRequests.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    public void markStale(String serverId, String sourceId, String contextKey) {
        String key = legacyKey(serverId, sourceId, contextKey);
        synchronized (catalogLookupLock) {
            if (catalogs.containsKey(key)) staleCatalogs.add(key);
            inFlightRequests.remove(key);
        }
    }

    public void markStale(CatalogKey catalogKey) {
        if (catalogKey == null) {
            return;
        }
        String key = catalogKey.storageKey();
        synchronized (catalogLookupLock) {
            if (catalogs.containsKey(key)) staleCatalogs.add(key);
            inFlightRequests.remove(key);
        }
    }

    public void markAllStale(String serverId, List<String> sourceIds) {
        if (sourceIds == null) {
            return;
        }
        for (String sourceId : sourceIds) {
            markStale(serverId, sourceId);
        }
    }

    public boolean markRequestInFlight(String serverId, String sourceId) {
        return markRequestInFlight(serverId, sourceId, "");
    }

    public boolean markRequestInFlight(String serverId, String sourceId, String contextKey) {
        if (sourceId == null || sourceId.isBlank()) return false;
        String key = legacyKey(serverId, sourceId, contextKey);
        synchronized (catalogLookupLock) {
            if (catalogs.containsKey(key) && !staleCatalogs.contains(key)) return false;
            return markRequestInFlight(key);
        }
    }

    public boolean markRequestInFlight(CatalogKey catalogKey) {
        if (catalogKey == null) return false;
        String key = catalogKey.storageKey();
        synchronized (catalogLookupLock) {
            if (catalogs.containsKey(key) && !staleCatalogs.contains(key)) return false;
            return markRequestInFlight(key);
        }
    }

    private boolean markRequestInFlight(String key) {
        synchronized (catalogLookupLock) {
            long now = System.currentTimeMillis();
            BrowserSafeState.BooleanValue started = new BrowserSafeState.BooleanValue();
            inFlightRequests.compute(key, (ignored, requestedAt) -> {
                if (requestedAt == null || now - requestedAt >= REQUEST_TIMEOUT_MILLIS) {
                    started.set(true);
                    return now;
                }
                return requestedAt;
            });
            return started.get();
        }
    }

    public boolean isRequestInFlight(String serverId, String sourceId) {
        return isRequestInFlight(serverId, sourceId, "");
    }

    public boolean isRequestInFlight(String serverId, String sourceId, String contextKey) {
        return isRequestInFlight(legacyKey(serverId, sourceId, contextKey));
    }

    public boolean isRequestInFlight(CatalogKey catalogKey) {
        return catalogKey != null && isRequestInFlight(catalogKey.storageKey());
    }

    private boolean isRequestInFlight(String key) {
        synchronized (catalogLookupLock) {
            Long requestedAt = inFlightRequests.get(key);
            if (requestedAt == null) return false;
            if (System.currentTimeMillis() - requestedAt < REQUEST_TIMEOUT_MILLIS) return true;
            inFlightRequests.remove(key, requestedAt);
            return false;
        }
    }

    public void clearRequestInFlight(String serverId, String sourceId) {
        clearRequestInFlight(serverId, sourceId, "");
    }

    public void clearRequestInFlight(String serverId, String sourceId, String contextKey) {
        synchronized (catalogLookupLock) {
            inFlightRequests.remove(legacyKey(serverId, sourceId, contextKey));
        }
    }

    public void clearRequestInFlight(CatalogKey catalogKey) {
        if (catalogKey != null) {
            synchronized (catalogLookupLock) {
                inFlightRequests.remove(catalogKey.storageKey());
            }
        }
    }

    public void clearRequestsInFlight(String serverId) {
        String prefix = "legacy" + KEY_SEPARATOR + safe(serverId) + KEY_SEPARATOR;
        String typedPrefix = "resource" + KEY_SEPARATOR + safe(serverId) + KEY_SEPARATOR;
        synchronized (catalogLookupLock) {
            inFlightRequests.keySet().removeIf(key -> key.startsWith(prefix));
            inFlightRequests.keySet().removeIf(key -> key.startsWith(typedPrefix));
        }
    }

    public void markServerStale(String serverId) {
        String prefix = "legacy" + KEY_SEPARATOR + safe(serverId) + KEY_SEPARATOR;
        String typedPrefix = "resource" + KEY_SEPARATOR + safe(serverId) + KEY_SEPARATOR;
        synchronized (catalogLookupLock) {
            catalogs.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(staleCatalogs::add);
            catalogs.keySet().stream().filter(key -> key.startsWith(typedPrefix)).forEach(staleCatalogs::add);
        }
    }

    public boolean isStale(String serverId, String sourceId, String contextKey) {
        synchronized (catalogLookupLock) {
            return staleCatalogs.contains(legacyKey(serverId, sourceId, contextKey));
        }
    }

    public boolean isStale(CatalogKey key) {
        synchronized (catalogLookupLock) {
            return key != null && staleCatalogs.contains(key.storageKey());
        }
    }

    public List<OptionCatalogItem> getItems(String serverId, String sourceId) {
        return getItems(serverId, sourceId, "");
    }

    public List<OptionCatalogItem> getItems(String serverId, String sourceId, String contextKey) {
        Catalog catalog = catalogs.get(legacyKey(serverId, sourceId, contextKey));
        return catalog != null ? catalog.items() : List.of();
    }

    public List<OptionCatalogItem> getItems(CatalogKey key) {
        Catalog catalog = key != null ? catalogs.get(key.storageKey()) : null;
        return catalog != null ? catalog.items() : List.of();
    }

    public CatalogLookup lookup(String serverId, String sourceId) {
        return lookup(serverId, sourceId, "");
    }

    public CatalogLookup lookup(String serverId, String sourceId, String contextKey) {
        return catalogLookups.getOrDefault(legacyKey(serverId, sourceId, contextKey), CatalogLookup.EMPTY);
    }

    public CatalogLookup lookup(CatalogKey key) {
        return key != null ? catalogLookups.getOrDefault(key.storageKey(), CatalogLookup.EMPTY) : CatalogLookup.EMPTY;
    }

    public CatalogLookup lookupAcrossContexts(String serverId, String sourceId) {
        return catalogLookups.getOrDefault(crossContextKey(serverId, sourceId), CatalogLookup.EMPTY);
    }

    public CatalogLookup lookupAcrossContexts(CatalogKey key) {
        return key != null ? catalogLookups.getOrDefault(crossContextKey(key.contextPrefix()), CatalogLookup.EMPTY)
            : CatalogLookup.EMPTY;
    }

    public List<OptionCatalogItem> getItemsAcrossContexts(String serverId, String sourceId) {
        return lookupAcrossContexts(serverId, sourceId).items().values().stream().distinct().toList();
    }

    public List<OptionCatalogItem> getItemsAcrossContexts(CatalogKey key) {
        return lookupAcrossContexts(key).resources().values().stream().distinct().toList();
    }

    public String getStatus(String serverId, String sourceId, String contextKey) {
        Catalog catalog = catalogs.get(legacyKey(serverId, sourceId, contextKey));
        if (catalog == null) {
            return "missing";
        }
        return isStale(serverId, sourceId, contextKey) ? "stale" : catalog.status();
    }

    public String getDiagnostic(String serverId, String sourceId, String contextKey) {
        Catalog catalog = catalogs.get(legacyKey(serverId, sourceId, contextKey));
        if (catalog == null) {
            return "Catalog has not been loaded";
        }
        return isStale(serverId, sourceId, contextKey) ? "Cached catalog is awaiting refresh" : catalog.diagnostic();
    }

    public String getStatus(CatalogKey key) {
        if (key == null) {
            return "missing";
        }
        Catalog catalog = catalogs.get(key.storageKey());
        return catalog == null ? "missing" : isStale(key) ? "stale" : catalog.status();
    }

    public String getDiagnostic(CatalogKey key) {
        if (key == null) {
            return "Catalog has not been loaded";
        }
        Catalog catalog = catalogs.get(key.storageKey());
        return catalog == null ? "Catalog has not been loaded"
            : isStale(key) ? "Cached catalog is awaiting refresh" : catalog.diagnostic();
    }

    private String legacyKey(String serverId, String sourceId, String contextKey) {
        return legacyPrefix(serverId, sourceId) + safe(contextKey);
    }

    private String legacyPrefix(String serverId, String sourceId) {
        return "legacy" + KEY_SEPARATOR + safe(serverId) + KEY_SEPARATOR + "builtin/legacy-string" + KEY_SEPARATOR
            + safe(sourceId) + KEY_SEPARATOR;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private String migrateLegacyKey(String storedKey) {
        if (storedKey == null || storedKey.startsWith("legacy" + KEY_SEPARATOR)
            || storedKey.startsWith("resource" + KEY_SEPARATOR)) {
            return storedKey;
        }
        int serverEnd = storedKey.indexOf(KEY_SEPARATOR);
        int sourceEnd = serverEnd >= 0 ? storedKey.indexOf(KEY_SEPARATOR, serverEnd + 1) : -1;
        if (serverEnd < 0 || sourceEnd < 0) {
            return "";
        }
        return legacyKey(storedKey.substring(0, serverEnd), storedKey.substring(serverEnd + 1, sourceEnd),
            storedKey.substring(sourceEnd + 1));
    }

    private boolean validStoredKey(String storedKey) {
        if (storedKey == null || storedKey.isBlank()) {
            return false;
        }
        int separators = 0;
        for (int index = 0; index < storedKey.length(); index++) {
            if (storedKey.charAt(index) == KEY_SEPARATOR.charAt(0)) {
                separators++;
            }
        }
        return separators == 4 && (storedKey.startsWith("legacy" + KEY_SEPARATOR)
            || storedKey.startsWith("resource" + KEY_SEPARATOR));
    }

    private void load() {
        long started = System.nanoTime();
        try {
            String persisted = storage.read();
            if (persisted == null || persisted.isBlank()) {
                return;
            }
            PersistedState state = gson.fromJson(persisted, PersistedState.class);
            if (state == null || state.schemaVersion < 1 || state.schemaVersion > CACHE_SCHEMA_VERSION || state.catalogs == null) {
                return;
            }
            Set<String> restored = new HashSet<>();
            synchronized (catalogLookupLock) {
                for (Map.Entry<String, Catalog> entry : state.catalogs.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        Catalog catalog = entry.getValue();
                        Catalog candidate = new Catalog(catalog.revision(), catalog.sequence(), catalog.values(), catalog.items(), catalog.status(),
                            catalog.diagnostic(), catalog.resources());
                        String restoredKey = state.schemaVersion == 1 ? migrateLegacyKey(entry.getKey()) : entry.getKey();
                        if (validStoredKey(restoredKey) && catalogs.putIfAbsent(restoredKey, candidate) == null) {
                            restored.add(restoredKey);
                        }
                    }
                }
                rebuildAllLookups();
                staleCatalogs.addAll(restored);
            }
            ReSyncFlowClient.traceLifecycle("", "option_catalog_cache_loaded", "catalogs", restored.size(),
                "elapsedMs", ((System.nanoTime() - started) / 1_000_000L));
        } catch (Exception exception) {
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(OptionCatalogCache.class).operation("Load Option Catalog").error("Could not load option catalog cache", exception);
        }
    }

    Async<Void> persistenceCompletion() {
        return persistenceCompletion;
    }

    PersistenceMetrics persistenceMetrics() {
        return new PersistenceMetrics(persistenceWrites.get(), unchangedRefreshes.get(), persistenceRevision.get(),
            persistedRevision.get());
    }

    private void scheduleSave() {
        if (!asyncPersistence) {
            long targetRevision = persistenceRevision.incrementAndGet();
            Map<String, Catalog> snapshot;
            synchronized (catalogLookupLock) {
                snapshot = new HashMap<>(catalogs);
            }
            if (save(snapshot)) {
                persistedRevision.set(targetRevision);
                persistenceWrites.incrementAndGet();
                persistenceCompletion = Async.completed(null);
            } else {
                Async<Void> failed = Async.pending();
                failed.fail(new IllegalStateException("Could not persist option catalog cache"));
                persistenceCompletion = failed;
            }
            return;
        }
        synchronized (persistenceLock) {
            persistenceRevision.incrementAndGet();
            if (persistenceCompletion.isDone()) {
                persistenceCompletion = Async.pending();
            }
        }
        schedulePersistenceDrain();
    }

    private void schedulePersistenceDrain() {
        if (!persistenceScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            work.execute(this::drainPersistence);
        } catch (RuntimeException exception) {
            persistenceScheduled.set(false);
            completePersistenceFailure(exception);
        }
    }

    private void drainPersistence() {
        boolean stored = true;
        try {
            while (stored) {
                long targetRevision = persistenceRevision.get();
                Map<String, Catalog> snapshot;
                synchronized (catalogLookupLock) {
                    snapshot = new HashMap<>(catalogs);
                }
                stored = save(snapshot);
                if (!stored) {
                    completePersistenceFailure(new IllegalStateException("Could not persist option catalog cache"));
                    return;
                }
                persistedRevision.accumulateAndGet(targetRevision, Math::max);
                persistenceWrites.incrementAndGet();
                ReSyncFlowClient.traceLifecycle("", "option_catalog_persistence_stored", "revision", targetRevision,
                    "catalogs", snapshot.size());
                if (persistenceRevision.get() == targetRevision) {
                    synchronized (persistenceLock) {
                        if (persistenceRevision.get() == targetRevision) {
                            persistenceCompletion.complete(null);
                            return;
                        }
                    }
                }
            }
        } finally {
            persistenceScheduled.set(false);
            if (stored && persistedRevision.get() < persistenceRevision.get()) {
                schedulePersistenceDrain();
            }
        }
    }

    private void completePersistenceFailure(RuntimeException exception) {
        synchronized (persistenceLock) {
            persistenceCompletion.fail(exception);
        }
    }

    private boolean save(Map<String, Catalog> snapshot) {
        try {
            storage.write(gson.toJson(new PersistedState(snapshot)));
            return true;
        } catch (Exception exception) {
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(OptionCatalogCache.class).operation("Save Option Catalog").error("Could not save option catalog cache", exception);
            return false;
        }
    }

    private void publishLookup(String key, Catalog catalog) {
        if (catalog == null) {
            catalogLookups.remove(key);
            return;
        }
        LinkedHashMap<String, OptionCatalogItem> items = new LinkedHashMap<>();
        LinkedHashMap<ServerResourceLocator, OptionCatalogItem> resources = new LinkedHashMap<>();
        for (OptionCatalogItem item : catalog.items()) {
            indexItem(items, resources, item);
        }
        catalogLookups.put(key, new CatalogLookup(catalogLookupRevision.incrementAndGet(), items, resources));
    }

    private void rebuildAcrossContexts(String serverId, String sourceId) {
        rebuildAcrossContexts(legacyPrefix(serverId, sourceId));
    }

    private void rebuildAcrossContexts(CatalogKey key) {
        rebuildAcrossContexts(key.contextPrefix());
    }

    private void rebuildAcrossContexts(String prefix) {
        LinkedHashMap<String, OptionCatalogItem> items = new LinkedHashMap<>();
        LinkedHashMap<ServerResourceLocator, OptionCatalogItem> resources = new LinkedHashMap<>();
        catalogs.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .sorted(Map.Entry.comparingByKey())
            .flatMap(entry -> entry.getValue().items().stream())
            .forEach(item -> indexItem(items, resources, item));
        catalogLookups.put(crossContextKey(prefix), new CatalogLookup(catalogLookupRevision.incrementAndGet(), items, resources));
    }

    private void indexItem(Map<String, OptionCatalogItem> items, Map<ServerResourceLocator, OptionCatalogItem> resources,
                           OptionCatalogItem item) {
        if (item == null) {
            return;
        }
        ServerResourceLocator resource = item.getResource();
        if (resource != null) {
            resources.putIfAbsent(resource, item);
            return;
        }
        if (item.getValue() == null || item.getValue().isBlank()) {
            return;
        }
        items.putIfAbsent(item.getValue(), item);
        Object provider = item.getMetadata().get("provider");
        if (provider != null && !provider.toString().isBlank()) {
            items.putIfAbsent("provider:" + provider.toString().toLowerCase(Locale.ROOT) + ":" + item.getValue(), item);
        }
    }

    private void rebuildAllLookups() {
        catalogLookups.clear();
        for (Map.Entry<String, Catalog> entry : catalogs.entrySet()) {
            publishLookup(entry.getKey(), entry.getValue());
        }
        Set<String> prefixes = new HashSet<>();
        for (String key : catalogs.keySet()) {
            int context = key.lastIndexOf(KEY_SEPARATOR);
            if (context >= 0) {
                prefixes.add(key.substring(0, context + KEY_SEPARATOR.length()));
            }
        }
        prefixes.forEach(this::rebuildAcrossContexts);
    }

    private String crossContextKey(String serverId, String sourceId) {
        return crossContextKey(legacyPrefix(serverId, sourceId));
    }

    private String crossContextKey(String prefix) {
        return "*" + prefix;
    }

    public record CatalogLookup(long revision, Map<String, OptionCatalogItem> items,
                                Map<ServerResourceLocator, OptionCatalogItem> resources) {
        private static final CatalogLookup EMPTY = new CatalogLookup(0L, Map.of(), Map.of());

        public CatalogLookup {
            items = items == null || items.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(items));
            resources = resources == null || resources.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        }

        public OptionCatalogItem get(String value) {
            return value == null ? null : items.get(value);
        }

        public OptionCatalogItem get(ServerResourceLocator resource) {
            return resource == null ? null : resources.get(resource);
        }
    }

    public record LegacyCatalogSnapshot(boolean present, long revision, List<String> values,
                                        List<OptionCatalogItem> items, boolean stale, boolean requestInFlight,
                                        String status, String diagnostic) {
        public LegacyCatalogSnapshot {
            values = values == null ? List.of() : List.copyOf(values);
            items = items == null ? List.of() : List.copyOf(items);
            status = status == null || status.isBlank() ? "missing" : status;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        private static LegacyCatalogSnapshot missing(boolean requestInFlight) {
            return new LegacyCatalogSnapshot(false, 0L, List.of(), List.of(), false, requestInFlight,
                "missing", "Catalog has not been loaded");
        }
    }

    record PersistenceMetrics(long writes, long unchangedRefreshes, long requestedRevision, long persistedRevision) {
    }

    public record CoreKey(ServerId serverId, ContractRef<InspectorFieldId> sourceReference,
                          ContractRef<CapabilityId> query, ServerResourceLocator resource, String contextKey,
                          String dependenciesKey, String search, Set<String> contextFields,
                          Set<String> dependencyFields, CatalogBinding catalogBinding,
                          CatalogVersion publicationContractVersion, ContentHash publicationChecksum,
                          long publicationRevision, long authorityEpoch) {
        public CoreKey {
            Objects.requireNonNull(serverId, "Core option server is required");
            Objects.requireNonNull(sourceReference, "Core option source reference is required");
            Objects.requireNonNull(query, "Core option query is required");
            Objects.requireNonNull(catalogBinding, "Core option catalog binding is required");
            Objects.requireNonNull(publicationContractVersion, "Core option publication contract is required");
            Objects.requireNonNull(publicationChecksum, "Core option publication checksum is required");
            if (resource != null && !serverId.equals(resource.serverId())) {
                throw new IllegalArgumentException("Core option resource belongs to another server");
            }
            contextKey = contextKey == null ? "" : contextKey;
            dependenciesKey = dependenciesKey == null ? "" : dependenciesKey;
            contextFields = immutableFields(contextFields, "Core option context fields are required");
            dependencyFields = immutableFields(dependencyFields, "Core option dependency fields are required");
            if (publicationRevision < 0L || authorityEpoch < 1L) {
                throw new IllegalArgumentException("Core option publication and authority must be current");
            }
        }

        private static Set<String> immutableFields(Set<String> fields, String message) {
            Objects.requireNonNull(fields, message);
            TreeSet<String> sorted = new TreeSet<>();
            for (String field : fields) {
                if (field == null || field.isBlank()) {
                    throw new IllegalArgumentException("Core option field names must be non-blank");
                }
                sorted.add(field);
            }
            return Collections.unmodifiableSet(sorted);
        }
    }

    public record CompletedCoreCatalog(ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query,
                                       long revision, String invalidationKey, List<OptionItem> items,
                                       List<Diagnostic> diagnostics) {
        public CompletedCoreCatalog {
            Objects.requireNonNull(sourceRef, "Completed Core option source is required");
            Objects.requireNonNull(query, "Completed Core option query is required");
            if (revision < 0L) {
                throw new IllegalArgumentException("Completed Core option revision cannot be negative");
            }
            if (invalidationKey == null || invalidationKey.isBlank()) {
                throw new IllegalArgumentException("Completed Core option invalidation key is required");
            }
            items = items == null ? List.of() : List.copyOf(items);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    public enum CoreAdmission {
        STARTED,
        FRESH,
        COALESCED,
        BUSY
    }

    public enum CoreSettlement {
        STORED,
        UNCHANGED,
        REJECTED
    }

    record CoreMetrics(int keys, int items, int inFlight) {
    }

    public record CoreCatalogSnapshot(CompletedCoreCatalog catalog, boolean loading, String status,
                                      String diagnostic) {
        private static final CoreCatalogSnapshot MISSING = new CoreCatalogSnapshot(null, false, "missing",
            "Option catalog has not been loaded");

        public CoreCatalogSnapshot {
            status = status == null || status.isBlank() ? "missing" : status;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    public record CatalogKey(ServerId serverId, ContractRef<ResourceTypeId> resourceType, String sourceId, String contextKey) {
        public CatalogKey {
            if (serverId == null || resourceType == null) {
                throw new IllegalArgumentException("Catalog server and resource type are required");
            }
            sourceId = clean(sourceId, "Catalog source is required");
            contextKey = contextKey != null ? contextKey : "";
            if (contextKey.indexOf(KEY_SEPARATOR) >= 0) {
                throw new IllegalArgumentException("Catalog context contains an invalid separator");
            }
        }

        public CatalogKey withContext(String contextKey) {
            return new CatalogKey(serverId, resourceType, sourceId, contextKey);
        }

        public boolean matches(ServerResourceLocator resource) {
            return resource != null && serverId.equals(resource.serverId()) && resourceType.equals(resource.type());
        }

        private String storageKey() {
            return contextPrefix() + contextKey;
        }

        private String contextPrefix() {
            return "resource" + KEY_SEPARATOR + serverId.canonicalText() + KEY_SEPARATOR + resourceType.canonicalText()
                + KEY_SEPARATOR + sourceId + KEY_SEPARATOR;
        }

        private static String clean(String value, String message) {
            if (value == null || value.isBlank() || value.indexOf(KEY_SEPARATOR) >= 0) {
                throw new IllegalArgumentException(message);
            }
            return value;
        }
    }

    private static final class CoreEntry {
        private CompletedCoreCatalog catalog;
        private boolean inFlight;
        private long startedAtMillis;
        private boolean stale;
        private String failure;

        private static CoreEntry started(long startedAtMillis) {
            CoreEntry entry = new CoreEntry();
            entry.inFlight = true;
            entry.startedAtMillis = startedAtMillis;
            return entry;
        }

        private int itemCount() {
            return catalog == null ? 0 : catalog.items().size();
        }
    }

    private record Catalog(String revision, long sequence, List<String> values, List<OptionCatalogItem> items,
                           String status, String diagnostic, boolean resources) {
        private Catalog {
            revision = revision != null ? revision : "";
            sequence = Math.max(0L, sequence);
            values = values != null ? List.copyOf(values) : List.of();
            items = items != null ? items.stream().filter(item -> item != null).map(OptionCatalogItem::copy).toList() : List.of();
            status = status != null && !status.isBlank() ? status : "available";
            diagnostic = diagnostic != null ? diagnostic : "";
        }

        private Catalog retain(Catalog previous) {
            if (previous == null || previous.resources != resources) {
                return this;
            }
            if (!"available".equals(status)) {
                return new Catalog(revision, sequence, previous.values, previous.items, status, diagnostic, resources);
            }
            LinkedHashMap<Object, OptionCatalogItem> retained = new LinkedHashMap<>();
            for (OptionCatalogItem item : items) {
                retained.put(identity(item), item);
            }
            Set<String> presentValues = new HashSet<>(values);
            for (OptionCatalogItem item : previous.items) {
                Object identity = identity(item);
                if (retained.containsKey(identity)) {
                    continue;
                }
                if (!resources && item.getValue() != null && presentValues.contains(item.getValue())) {
                    retained.put(identity, item);
                } else {
                    retained.put(identity, item.unavailable("No Longer Available"));
                }
            }
            return new Catalog(revision, sequence, values, List.copyOf(retained.values()), status, diagnostic, resources);
        }

        private static Object identity(OptionCatalogItem item) {
            ServerResourceLocator resource = item.getResource();
            return resource != null ? resource : safe(item.getValue());
        }

        private boolean sameContent(Catalog other) {
            return other != null && resources == other.resources && values.equals(other.values) && items.equals(other.items)
                && status.equals(other.status) && diagnostic.equals(other.diagnostic);
        }

        private boolean isNewerThan(Catalog other) {
            return sequence > 0L && (other.sequence <= 0L || sequence >= other.sequence);
        }
    }

    public interface Storage {
        String read() throws Exception;

        void write(String content) throws Exception;

        static Storage none() {
            return new Storage() {
                @Override
                public String read() {
                    return null;
                }

                @Override
                public void write(String content) {
                }
            };
        }
    }

    @FunctionalInterface
    public interface Work {
        void execute(Runnable task);

        static Work direct() {
            return Runnable::run;
        }
    }

    private static final class ReSyncBackedStorage implements Storage {
        private final ReSyncStorage storage;

        private ReSyncBackedStorage(ReSyncStorage storage) {
            this.storage = storage != null ? storage : ReSyncStorage.memory();
        }

        @Override
        public String read() {
            return storage.read("option-catalog-cache");
        }

        @Override
        public void write(String content) {
            storage.write("option-catalog-cache", content);
        }
    }

    private static final class DesktopWork implements Work {
        private static final DesktopWork INSTANCE = new DesktopWork();
        private final BrowserWork.Executor executor = BrowserWork.executor();

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }
    }

    private static class PersistedState {
        private int schemaVersion = CACHE_SCHEMA_VERSION;
        private Map<String, Catalog> catalogs = new HashMap<>();

        private PersistedState() {
        }

        private PersistedState(Map<String, Catalog> catalogs) {
            this.catalogs = catalogs != null ? catalogs : new HashMap<>();
        }
    }
}
