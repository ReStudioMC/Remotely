package redxax.oxy.remotely.data.flow;

import restudio.rescreen.platform.Async;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ServerId;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;


public final class ReSyncCatalogPublicationCache {
    public static final int SCHEMA_VERSION = 1;
    private static final int DEFAULT_MAX_SNAPSHOTS = 1;
    private static final int MAX_PENDING_STORES = 4;
    private static final int MAX_SERVERS = 8;
    private static final int LEGACY_MAX_SERVERS = 64;
    private static final int LEGACY_MAX_SNAPSHOTS = 4;
    private static final int MAX_CACHE_BYTES = CanonicalLimits.catalog().inputBytes();
    private static final String STORE_REJECTED = "CATALOG_PUBLICATION.CACHE_STORE_REJECTED";
    private static final String STORE_QUEUE_FULL = "CATALOG_PUBLICATION.CACHE_STORE_QUEUE_FULL";
    private static final Map<String, WeakReference<StoreState>> PATH_STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<StoreState, Async<Map<String, Map<String, CachedPublication>>>> SHARED_LOADS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final BrowserWork.Executor CACHE_IO = BrowserWork.executor();
    private static final BrowserWork.Executor CACHE_CONTINUATIONS = BrowserWork.executor();

    private final ReSyncStorage storage;
    private final String storageIdentity;
    private final StoreState pathState;
    private final Object pathLock;
    private final int maxSnapshots;
    private final CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
    private final CatalogAuthoringPublicationCodec authoringCodec = new CatalogAuthoringPublicationCodec();
    private final ArrayDeque<PendingStore> pendingStores = new ArrayDeque<>();
    private volatile Map<String, Map<String, CachedPublication>> snapshots = Map.of();
    private volatile boolean loaded;
    private boolean storeDrainScheduled;
    private final BrowserSafeState.LongValue exactReplayStores = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue validatedStores = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue durableWrites = new BrowserSafeState.LongValue();

    public ReSyncCatalogPublicationCache() {
        this(ReSyncStorage.legacy("remotely.catalog-publication"));
    }

    public ReSyncCatalogPublicationCache(ReSyncStorage storage) {
        this(storage, DEFAULT_MAX_SNAPSHOTS);
    }

    public ReSyncCatalogPublicationCache(ReSyncStorage storage, int maxSnapshots) {
        this(storage, maxSnapshots, true);
    }

    private ReSyncCatalogPublicationCache(ReSyncStorage storage, int maxSnapshots, boolean loadImmediately) {
        this.storage = storage != null ? storage : ReSyncStorage.memory("remotely.catalog-publication");
        this.storageIdentity = storageIdentity(this.storage);
        if (maxSnapshots < 1) {
            throw new IllegalArgumentException("Maximum catalog publication snapshots must be positive");
        }
        this.maxSnapshots = maxSnapshots;
        this.pathState = pathState(storageIdentity, maxSnapshots);
        this.pathLock = pathState.lock();
        if (loadImmediately) {
            load();
        }
    }

    static ReSyncCatalogPublicationCache deferred() {
        return new ReSyncCatalogPublicationCache(ReSyncStorage.legacy("remotely.catalog-publication"),
            DEFAULT_MAX_SNAPSHOTS, false);
    }

    static ReSyncCatalogPublicationCache deferred(ReSyncStorage storage, int maxSnapshots) {
        return new ReSyncCatalogPublicationCache(storage, maxSnapshots, false);
    }

    private static String storageIdentity(ReSyncStorage storage) {
        return Integer.toHexString(System.identityHashCode(storage));
    }

    private static StoreState pathState(String identity, int maxSnapshots) {
        synchronized (PATH_STATES) {
            WeakReference<StoreState> reference = PATH_STATES.get(identity);
            StoreState state = reference == null ? null : reference.get();
            if (state == null) {
                state = new StoreState(identity, new Object(), maxSnapshots);
                PATH_STATES.put(state.identity(), new WeakReference<>(state));
            } else if (state.maxSnapshots() != maxSnapshots) {
                throw new IllegalArgumentException("Catalog cache path already uses a different retention policy");
            }
            return state;
        }
    }

    synchronized boolean loaded() {
        return loaded;
    }

    public synchronized Optional<CachedPublication> latest(ServerId serverId) {
        Objects.requireNonNull(serverId, "Server ID is required");
        Map<String, Map<String, CachedPublication>> shared = completedSharedSnapshots();
        if (shared != null) {
            installSnapshots(shared);
        } else if (!loaded) {
            synchronized (pathLock) {
                installSnapshots(currentSnapshots());
            }
        }
        return latestLoaded(serverId);
    }

    Async<Optional<CachedPublication>> latestAsync(ServerId serverId) {
        Objects.requireNonNull(serverId, "Server ID is required");
        synchronized (this) {
            if (loaded) {
                return Async.supplyAsync(() -> {
                    synchronized (this) {
                        Map<String, Map<String, CachedPublication>> shared = completedSharedSnapshots();
                        if (shared != null) {
                            installSnapshots(shared);
                        }
                        return latestLoaded(serverId);
                    }
                });
            }
        }
        return sharedLoad().thenApplyAsync(values -> {
            synchronized (this) {
                if (!loaded) {
                    Map<String, Map<String, CachedPublication>> shared = completedSharedSnapshots();
                    installSnapshots(shared == null ? values : shared);
                }
                return latestLoaded(serverId);
            }
        });
    }

    <T> void continueAsync(Async<T> source, BooleanSupplier admission,
                           BiConsumer<? super T, ? super Throwable> continuation) {
        Objects.requireNonNull(source, "Cache continuation source is required");
        Objects.requireNonNull(admission, "Cache continuation admission is required");
        Objects.requireNonNull(continuation, "Cache continuation is required");
        try {
            source.whenComplete((value, failure) -> {
                if (!admission.getAsBoolean()) {
                    return;
                }
                try {
                    CACHE_CONTINUATIONS.execute(() -> {
                        if (admission.getAsBoolean()) {
                            continuation.accept(value, failure);
                        }
                    });
                } catch (RuntimeException ignored) {
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    synchronized Optional<CachedPublication> latestLoaded(ServerId serverId) {
        Objects.requireNonNull(serverId, "Server ID is required");
        Map<String, CachedPublication> serverSnapshots = snapshots.get(serverId.canonicalText());
        if (serverSnapshots == null || serverSnapshots.isEmpty()) {
            return Optional.empty();
        }
        return serverSnapshots.values().stream().max(Comparator
            .comparingLong((CachedPublication value) -> value.key().catalogGeneration())
            .thenComparingLong(value -> value.publication().revision())
            .thenComparing(value -> value.key().canonicalText()));
    }

    public boolean store(ServerId serverId, CatalogCachePublication publication, byte[] canonicalBytes,
                         CatalogCachePublication hydrationProjection) {
        Objects.requireNonNull(serverId, "Server ID is required");
        return storePrepared(serverId, publication, canonicalBytes, hydrationProjection);
    }

    private boolean storePrepared(ServerId serverId, CatalogCachePublication publication, byte[] canonicalBytes,
                                   CatalogCachePublication hydrationProjection) {
        return storePrepared(serverId, publication, canonicalBytes, hydrationProjection, null, List.of());
    }

    private boolean storePrepared(ServerId serverId, CatalogCachePublication publication, byte[] canonicalBytes,
                                  CatalogCachePublication hydrationProjection,
                                  CatalogAuthoringPublication authoringPublication,
                                  List<String> authoringCapabilities) {
        validatedStores.incrementAndGet();
        CachedPublication candidate = validate(serverId, publication, canonicalBytes, hydrationProjection,
            authoringPublication, authoringCapabilities);
        if (candidate == null) {
            return false;
        }
        return store(candidate);
    }

    private boolean store(CachedPublication candidate) {
        synchronized (pathLock) {
            Map<String, Map<String, CachedPublication>> current = currentSnapshots();
            String serverKey = candidate.serverId().canonicalText();
            String cacheKey = candidate.key().canonicalText();
            Map<String, Map<String, CachedPublication>> next = copySnapshots(current);
            Map<String, CachedPublication> serverSnapshots = new LinkedHashMap<>(next.getOrDefault(serverKey, Map.of()));
            for (CachedPublication existing : serverSnapshots.values()) {
                if (!sameAuthority(existing.key(), candidate.key())) {
                    continue;
                }
                if (existing.key().catalogGeneration() > candidate.key().catalogGeneration()
                    || existing.key().catalogGeneration() == candidate.key().catalogGeneration()
                        && !existing.key().equals(candidate.key())) {
                    return false;
                }
                if (existing.key().catalogGeneration() < candidate.key().catalogGeneration()
                    && candidate.publication().kind() != CatalogCachePublication.Kind.FULL) {
                    return false;
                }
            }
            CachedPublication previous = serverSnapshots.get(cacheKey);
            if (previous != null) {
                if (candidate.publication().revision() < previous.publication().revision()) {
                    return false;
                }
                if (candidate.publication().revision() == previous.publication().revision()) {
                    if (sameCachedPublication(previous, candidate)) {
                        snapshots = current;
                        return true;
                    }
                    if (!sameNodePublication(previous, candidate)
                        || previous.authoringPublication() != null && candidate.authoringPublication() == null
                        || previous.authoringPublication() != null && candidate.authoringPublication() != null
                            && Objects.equals(previous.authoringCapabilities(), candidate.authoringCapabilities())) {
                        return false;
                    }
                }
                if (candidate.publication().kind() == CatalogCachePublication.Kind.DELTA
                    && (!deltaAdvances(previous, candidate.publication())
                        || !appliesDelta(previous, candidate))) {
                    return false;
                }
            } else if (candidate.publication().kind() == CatalogCachePublication.Kind.DELTA) {
                return false;
            }
            serverSnapshots.put(cacheKey, candidate);
            while (serverSnapshots.size() > maxSnapshots) {
                String oldest = serverSnapshots.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(cacheKey))
                    .min(Comparator.comparingLong((Map.Entry<String, CachedPublication> entry) -> entry.getValue().publication().revision())
                        .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
                serverSnapshots.remove(oldest);
            }
            next.put(serverKey, serverSnapshots);
            trimServers(next, serverKey);
            PreparedReplacement replacement = fitSnapshots(next, serverKey, cacheKey);
            if (replacement == null) {
                return false;
            }
            return replaceSnapshots(replacement.snapshots(), replacement.encoded());
        }
    }

    public synchronized Async<Persistence> storeAsync(ServerId serverId,
                                                                   CatalogCachePublication publication,
                                                                   byte[] canonicalBytes,
                                                                   CatalogCachePublication hydrationProjection) {
        Objects.requireNonNull(serverId, "Server ID is required");
        Objects.requireNonNull(publication, "Catalog publication is required");
        Objects.requireNonNull(canonicalBytes, "Catalog publication bytes are required");
        Objects.requireNonNull(hydrationProjection, "Catalog hydration projection is required");
        if (pendingStores.size() >= MAX_PENDING_STORES) {
            return Async.completed(Persistence.rejected(publication.key(), publication.revision(),
                STORE_QUEUE_FULL));
        }
        byte[] stableCanonicalBytes = canonicalBytes.clone();
        return enqueueStore(publication.key(), publication.revision(), () -> storePrepared(serverId, publication,
            stableCanonicalBytes, hydrationProjection));
    }

    synchronized Async<Persistence> storeAsync(ServerId serverId,
                                                            ReSyncCatalogPublicationProjection.Snapshot snapshot) {
        return storeAsync(serverId, snapshot, null, List.of());
    }

    synchronized Async<Persistence> storeAsync(ServerId serverId,
                                                ReSyncCatalogPublicationProjection.Prepared prepared,
                                                ReSyncCatalogAuthoringProjection.Snapshot authoring,
                                                List<String> authoringCapabilities) {
        Objects.requireNonNull(prepared, "Prepared catalog projection is required");
        ReSyncCatalogPublicationProjection.Snapshot snapshot = prepared.candidate();
        CatalogCachePublication publication = snapshot.publication();
        return enqueueStore(publication.key(), publication.revision(), () -> storeProjectionSnapshot(serverId,
            snapshot, authoring, authoringCapabilities, true));
    }

    synchronized Async<Persistence> storeAsync(ServerId serverId,
                                                ReSyncCatalogPublicationProjection.Snapshot snapshot,
                                                ReSyncCatalogAuthoringProjection.Snapshot authoring,
                                                List<String> authoringCapabilities) {
        Objects.requireNonNull(serverId, "Server ID is required");
        Objects.requireNonNull(snapshot, "Catalog publication snapshot is required");
        CatalogCachePublication publication = snapshot.publication();
        return enqueueStore(publication.key(), publication.revision(), () -> storeProjectionSnapshot(serverId,
            snapshot, authoring, authoringCapabilities, false));
    }

    private boolean storeProjectionSnapshot(ServerId serverId, ReSyncCatalogPublicationProjection.Snapshot snapshot,
                                            ReSyncCatalogAuthoringProjection.Snapshot authoring,
                                            List<String> authoringCapabilities, boolean prepared) {
        CatalogCachePublication publication = snapshot.publication();
        synchronized (pathLock) {
            Map<String, CachedPublication> serverSnapshots = currentSnapshots().get(serverId.canonicalText());
            CachedPublication existing = serverSnapshots == null ? null
                : serverSnapshots.get(publication.key().canonicalText());
            if (existing != null && existing.publication().revision() == publication.revision()) {
                boolean exact = existing.matches(snapshot, authoring, authoringCapabilities);
                if (exact) {
                    exactReplayStores.incrementAndGet();
                    ReSyncFlowClient.traceLifecycle(serverId.canonicalText(), "catalog_cache_persistence_skipped",
                        "catalogKey", publication.key().canonicalText(), "revision", publication.revision());
                    return true;
                }
            }
            byte[] canonicalBytes = snapshot.canonicalBytes();
            long started = System.nanoTime();
            ReSyncFlowClient.traceLifecycle(serverId.canonicalText(), "catalog_cache_persistence_started",
                "catalogKey", publication.key().canonicalText(), "revision", publication.revision(),
                "bytes", canonicalBytes.length);
            CatalogAuthoringPublication authoringPublication = authoring == null ? null : authoring.publication();
            boolean stored;
            if (prepared) {
                validatedStores.incrementAndGet();
                CachedPublication candidate = validatePrepared(serverId, snapshot, canonicalBytes,
                    authoringPublication, authoringCapabilities);
                stored = candidate != null && store(candidate);
            } else {
                stored = storePrepared(serverId, publication, canonicalBytes, snapshot.hydrationPublication(),
                    authoringPublication, authoringCapabilities);
            }
            ReSyncFlowClient.traceLifecycle(serverId.canonicalText(), stored
                    ? "catalog_cache_persistence_stored" : "catalog_cache_persistence_rejected",
                "catalogKey", publication.key().canonicalText(), "revision", publication.revision(),
                "bytes", canonicalBytes.length, "elapsedMs", ((System.nanoTime() - started) / 1_000_000L));
            return stored;
        }
    }

    private synchronized Async<Persistence> enqueueStore(CatalogCacheKey key, long revision,
                                                                     BooleanSupplier transaction) {
        Async<Persistence> completion = Async.pending();
        if (pendingStores.size() >= MAX_PENDING_STORES) {
            completion.complete(Persistence.rejected(key, revision, STORE_QUEUE_FULL));
            return completion;
        }
        PendingStore pending = new PendingStore(key, revision, transaction, completion);
        pendingStores.addLast(pending);
        if (!storeDrainScheduled) {
            storeDrainScheduled = true;
            try {
                CACHE_IO.execute(this::drainStores);
            } catch (RuntimeException exception) {
                storeDrainScheduled = false;
                pendingStores.removeLastOccurrence(pending);
                completion.complete(Persistence.rejected(key, revision, STORE_QUEUE_FULL));
            }
        }
        return completion;
    }

    private void drainStores() {
        while (true) {
            PendingStore pending;
            synchronized (this) {
                pending = pendingStores.pollFirst();
                if (pending == null) {
                    storeDrainScheduled = false;
                    return;
                }
            }
            boolean stored;
            try {
                stored = pending.transaction().getAsBoolean();
            } catch (RuntimeException exception) {
                stored = false;
            }
            pending.completion().complete(stored
                ? Persistence.stored(pending.key(), pending.revision())
                : Persistence.rejected(pending.key(), pending.revision(), STORE_REJECTED));
        }
    }

    public ReSyncStorage storage() {
        return storage;
    }

    CacheMetrics metrics() {
        return new CacheMetrics(exactReplayStores.get(), validatedStores.get(), durableWrites.get());
    }

    public synchronized State snapshotState() {
        synchronized (pathLock) {
            installSnapshots(currentSnapshots());
        }
        return new State(snapshots);
    }

    public synchronized boolean restore(State state) {
        Objects.requireNonNull(state, "Catalog publication cache state is required");
        Map<String, Map<String, CachedPublication>> requested;
        try {
            requested = validateSnapshots(state.snapshots());
        } catch (RuntimeException exception) {
            return false;
        }
        synchronized (pathLock) {
            Map<String, Map<String, CachedPublication>> current = currentSnapshots();
            Map<String, Map<String, CachedPublication>> restored = mergeRestore(current, snapshots, requested);
            try {
                restored = validateSnapshots(restored);
            } catch (RuntimeException exception) {
                return false;
            }
            if (sameSnapshots(current, restored)) {
                installSnapshots(restored);
                publishShared(restored);
                return true;
            }
            return replaceSnapshots(restored);
        }
    }

    private CachedPublication validate(ServerId serverId, CatalogCachePublication publication, byte[] canonicalBytes,
                                       CatalogCachePublication hydrationProjection,
                                       CatalogAuthoringPublication authoringPublication,
                                       List<String> authoringCapabilities) {
        if (publication == null || canonicalBytes == null || canonicalBytes.length == 0 || hydrationProjection == null) {
            return null;
        }
        try {
            if (!serverId.canonicalText().equals(ServerId.parseCanonicalText(serverId.canonicalText()).canonicalText())
                || !serverId.equals(publication.serverId())
                || !serverId.equals(hydrationProjection.serverId())
                || !publication.projectionVersion().equals(hydrationProjection.projectionVersion())
                || !CatalogProjectionVersion.isSupported(publication.projectionVersion())
                || !publication.key().equals(hydrationProjection.key())
                || publication.revision() != hydrationProjection.revision()
                || hydrationProjection.kind() != CatalogCachePublication.Kind.FULL) {
                return null;
            }
            byte[] encodedPublication = codec.encodeBytes(publication);
            if (!Arrays.equals(canonicalBytes, encodedPublication)) {
                return null;
            }
            CatalogCachePublication nodePublication = nodePublication(publication);
            CatalogCachePublication nodeHydrationProjection = nodePublication(hydrationProjection);
            List<String> capabilities = canonicalCapabilities(authoringCapabilities);
            if (authoringPublication != null && (!nodePublication.key().hasCatalogBinding()
                || !nodePublication.key().catalogBinding().equals(authoringPublication.binding())
                || !nodePublication.projectionVersion().equals(authoringPublication.projectionVersion())
                || !canonicalAuthoring(authoringPublication))) {
                return null;
            }
            byte[] nodeCanonicalBytes = nodePublication == publication ? encodedPublication : codec.encodeBytes(nodePublication);
            if (!sameProjectionEntries(nodePublication, nodeHydrationProjection)) {
                return null;
            }
            if (nodePublication.kind() != CatalogCachePublication.Kind.FULL) {
                byte[] hydrationBytes = codec.encodeBytes(nodeHydrationProjection);
                CatalogCachePublication decodedHydration = codec.decodeBytes(hydrationBytes);
                if (!decodedHydration.equals(nodeHydrationProjection)
                    || !Arrays.equals(hydrationBytes, codec.encodeBytes(decodedHydration))) {
                    return null;
                }
            }
            return new CachedPublication(serverId, nodePublication.key(), nodePublication, nodeHydrationProjection,
                nodeCanonicalBytes, authoringPublication, capabilities);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CachedPublication validatePrepared(ServerId serverId, ReSyncCatalogPublicationProjection.Snapshot snapshot,
                                               byte[] canonicalBytes, CatalogAuthoringPublication authoringPublication,
                                               List<String> authoringCapabilities) {
        try {
            CatalogCachePublication publication = snapshot.publication();
            CatalogCachePublication hydrationProjection = snapshot.hydrationPublication();
            if (serverId == null || !serverId.equals(publication.serverId())
                || !serverId.equals(hydrationProjection.serverId())
                || !CatalogProjectionVersion.isSupported(publication.projectionVersion())
                || !publication.key().equals(hydrationProjection.key())
                || publication.revision() != hydrationProjection.revision()
                || hydrationProjection.kind() != CatalogCachePublication.Kind.FULL
                || !sameProjectionEntries(publication, hydrationProjection)
                || authoringPublication != null && (!publication.key().hasCatalogBinding()
                    || !publication.key().catalogBinding().equals(authoringPublication.binding())
                    || !publication.projectionVersion().equals(authoringPublication.projectionVersion())
                    || !canonicalAuthoring(authoringPublication))) {
                return null;
            }
            return new CachedPublication(serverId, publication.key(), publication, hydrationProjection,
                canonicalBytes, authoringPublication, canonicalCapabilities(authoringCapabilities));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean canonicalPublication(CatalogCachePublication publication, byte[] canonicalBytes) {
        try {
            return Arrays.equals(canonicalBytes, codec.encodeBytes(publication));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean canonicalAuthoring(CatalogAuthoringPublication publication) {
        try {
            byte[] bytes = authoringCodec.encodeBytes(publication);
            CatalogAuthoringPublication decoded = authoringCodec.decodeBytes(bytes);
            return publication.equals(decoded) && Arrays.equals(bytes, authoringCodec.encodeBytes(decoded));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static List<String> canonicalCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return List.of();
        }
        List<String> validated = new ArrayList<>(capabilities.size());
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank() || !capability.equals(capability.strip())) {
                throw new IllegalArgumentException("Authoring capability is invalid");
            }
            validated.add(capability);
        }
        return validated.stream().distinct().sorted().toList();
    }

    private CatalogCachePublication nodePublication(CatalogCachePublication publication) {
        return publication.authoringPublication() == null ? publication : publication.withAuthoringPublication(null);
    }

    private boolean sameProjectionEntries(CatalogCachePublication publication, CatalogCachePublication hydrationProjection) {
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> projected = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : hydrationProjection.entries()) {
            if (projected.put(entry.definitionKey(), entry) != null) {
                return false;
            }
        }
        if (!Objects.equals(publication.authoringPublication(), hydrationProjection.authoringPublication())
            || !publication.unknown().equals(hydrationProjection.unknown())) {
            return false;
        }
        if (publication.kind() == CatalogCachePublication.Kind.FULL) {
            return publication.entries().equals(hydrationProjection.entries());
        }
        Set<ContractRef<NodeId>> publicationKeys = publication.entries().stream()
            .map(CatalogCachePublication.Entry::definitionKey).collect(Collectors.toSet());
        for (CatalogCachePublication.Entry entry : publication.entries()) {
            if (!entry.equals(projected.get(entry.definitionKey()))) {
                return false;
            }
        }
        return projected.entrySet().stream()
            .filter(entry -> !publicationKeys.contains(entry.getKey()))
            .allMatch(entry -> entry.getValue().revision() < publication.revision());
    }

    private void load() {
        synchronized (pathLock) {
            Map<String, Map<String, CachedPublication>> values = readDisk();
            installSnapshots(values == null ? Map.of() : values);
            publishShared(snapshots);
        }
    }

    private Async<Map<String, Map<String, CachedPublication>>> sharedLoad() {
        Async<Map<String, Map<String, CachedPublication>>> shared;
        synchronized (SHARED_LOADS) {
            shared = SHARED_LOADS.get(pathState);
            if (shared == null) {
                shared = Async.pending();
                Async<Map<String, Map<String, CachedPublication>>> result = shared;
                SHARED_LOADS.put(pathState, result);
                try {
                    CACHE_IO.execute(() -> {
                        Map<String, Map<String, CachedPublication>> values;
                        synchronized (pathLock) {
                            values = readDisk();
                        }
                        result.complete(values == null ? Map.of() : values);
                    });
                } catch (RuntimeException exception) {
                    result.fail(exception);
                }
            }
        }
        Async<Map<String, Map<String, CachedPublication>>> current = shared;
        shared.whenComplete((value, failure) -> {
            if (failure != null) {
                synchronized (SHARED_LOADS) {
                    SHARED_LOADS.remove(pathState, current);
                }
            }
        });
        return shared;
    }

    private Map<String, Map<String, CachedPublication>> currentSnapshots() {
        Map<String, Map<String, CachedPublication>> shared = completedSharedSnapshots();
        if (shared != null) {
            return shared;
        }
        if (loaded) {
            return snapshots;
        }
        Map<String, Map<String, CachedPublication>> values = readDisk();
        Map<String, Map<String, CachedPublication>> resolved = values == null ? Map.of() : values;
        publishShared(resolved);
        return resolved;
    }

    private Map<String, Map<String, CachedPublication>> completedSharedSnapshots() {
        Async<Map<String, Map<String, CachedPublication>>> shared;
        synchronized (SHARED_LOADS) {
            shared = SHARED_LOADS.get(pathState);
        }
        return shared != null && shared.isDone() && !BrowserWork.failed(shared) && !shared.isCancelled()
            ? shared.join() : null;
    }

    private void installSnapshots(Map<String, Map<String, CachedPublication>> values) {
        snapshots = values == null ? Map.of() : immutableSnapshots(values);
        loaded = true;
    }

    private void publishShared(Map<String, Map<String, CachedPublication>> values) {
        Map<String, Map<String, CachedPublication>> immutable = immutableSnapshots(values == null ? Map.of() : values);
        synchronized (SHARED_LOADS) {
            SHARED_LOADS.put(pathState, Async.completed(immutable));
        }
    }

    private Map<String, Map<String, CachedPublication>> readDisk() {
        try {
            String persisted = storage.read("payload");
            if (persisted == null || persisted.isBlank()) {
                return Map.of();
            }
            byte[] encoded = persisted.getBytes(StandardCharsets.ISO_8859_1);
            return encoded.length <= MAX_CACHE_BYTES ? decode(encoded) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, Map<String, CachedPublication>> decode(byte[] encoded) {
        JsonValue.JsonObject root = object(CanonicalCodec.decode(encoded, CanonicalLimits.catalog()),
            "Catalog publication cache");
        requireFields(root, Set.of("schemaVersion", "servers"));
        if (integer(root.value("schemaVersion"), "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported catalog publication cache schema");
        }
        JsonValue.JsonObject serverValues = object(root.value("servers"), "Catalog publication cache servers");
        if (serverValues.fields().size() > LEGACY_MAX_SERVERS) {
            throw new IllegalArgumentException("Catalog publication cache server retention is invalid");
        }
        Map<String, Map<String, CachedPublication>> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> serverEntry : serverValues.fields().entrySet()) {
            ServerId serverId = ServerId.parseCanonicalText(serverEntry.getKey());
            if (!serverId.canonicalText().equals(serverEntry.getKey())) {
                throw new IllegalArgumentException("Catalog publication cache server key is not canonical");
            }
            JsonValue.JsonObject cacheValues = object(serverEntry.getValue(), "Catalog publication cache snapshots");
            Map<String, CachedPublication> serverSnapshots = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> cacheEntry : cacheValues.fields().entrySet()) {
                CatalogCacheKey key = CatalogCacheKey.parseCanonicalText(cacheEntry.getKey());
                if (!key.canonicalText().equals(cacheEntry.getKey()) || !serverId.equals(key.serverId())) {
                    throw new IllegalArgumentException("Catalog publication cache key is not bound to its server");
                }
                JsonValue.JsonObject value = object(cacheEntry.getValue(), "Catalog publication cache snapshot");
                requireFields(value, Set.of("publication", "projection"), "authoring");
                JsonValue publicationValue = value.value("publication");
                JsonValue projectionValue = value.value("projection");
                CatalogCachePublication publication = codec.decode(publicationValue);
                CatalogCachePublication hydrationProjection;
                if (projectionValue instanceof JsonValue.JsonString projectionReference) {
                    if (!"publication".equals(projectionReference.value())
                        || publication.kind() != CatalogCachePublication.Kind.FULL) {
                        throw new IllegalArgumentException("Catalog publication cache projection reference is invalid");
                    }
                    hydrationProjection = publication;
                } else {
                    hydrationProjection = codec.decode(projectionValue);
                }
                if (!key.equals(publication.key()) || !key.equals(hydrationProjection.key())) {
                    throw new IllegalArgumentException("Catalog publication cache snapshot key mismatch");
                }
                CatalogAuthoringPublication authoringPublication = null;
                List<String> authoringCapabilities = List.of();
                JsonValue authoringValue = value.value("authoring");
                if (authoringValue instanceof JsonValue.JsonObject authoringObject
                    && authoringObject.fields().containsKey("publication")) {
                    requireFields(authoringObject, Set.of("publication", "capabilities"));
                    authoringPublication = authoringCodec.decode(authoringObject.value("publication"));
                    authoringCapabilities = stringList(authoringObject.value("capabilities"),
                        "Catalog authoring capabilities");
                }
                CachedPublication checked = validateDecoded(serverId, publication, publicationValue,
                    hydrationProjection, authoringPublication, authoringCapabilities);
                if (checked == null) {
                    throw new IllegalArgumentException("Catalog publication cache snapshot is invalid");
                }
                serverSnapshots.put(cacheEntry.getKey(), checked);
            }
            rejectConflictingGenerations(serverSnapshots);
            if (serverSnapshots.size() > Math.max(maxSnapshots, LEGACY_MAX_SNAPSHOTS)) {
                throw new IllegalArgumentException("Catalog publication cache retention is invalid");
            }
            loaded.put(serverEntry.getKey(), Map.copyOf(trimSnapshots(serverSnapshots, maxSnapshots)));
        }
        return immutableSnapshots(loaded);
    }

    private static Map<String, CachedPublication> trimSnapshots(Map<String, CachedPublication> values, int limit) {
        Map<String, CachedPublication> retained = new LinkedHashMap<>(values);
        while (retained.size() > limit) {
            String oldest = retained.entrySet().stream().min(Comparator
                .comparingLong((Map.Entry<String, CachedPublication> entry) ->
                    entry.getValue().key().catalogGeneration())
                .thenComparingLong(entry -> entry.getValue().publication().revision())
                .thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).orElseThrow();
            retained.remove(oldest);
        }
        return retained;
    }

    private static void trimServers(Map<String, Map<String, CachedPublication>> values, String protectedServer) {
        while (values.size() > MAX_SERVERS) {
            String oldest = values.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(protectedServer))
                .min(Comparator.comparingLong((Map.Entry<String, Map<String, CachedPublication>> entry) ->
                        latestGeneration(entry.getValue()))
                    .thenComparingLong(entry -> latestRevision(entry.getValue()))
                    .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElseThrow();
            values.remove(oldest);
        }
    }

    private static long latestGeneration(Map<String, CachedPublication> values) {
        return values.values().stream().mapToLong(value -> value.key().catalogGeneration()).max().orElse(-1L);
    }

    private static long latestRevision(Map<String, CachedPublication> values) {
        return values.values().stream().mapToLong(value -> value.publication().revision()).max().orElse(-1L);
    }

    private CachedPublication validateDecoded(ServerId serverId, CatalogCachePublication publication,
                                              JsonValue publicationValue,
                                              CatalogCachePublication hydrationProjection,
                                              CatalogAuthoringPublication authoringPublication,
                                              List<String> authoringCapabilities) {
        try {
            if (publication == null || publicationValue == null || hydrationProjection == null
                || !serverId.equals(publication.serverId()) || !serverId.equals(hydrationProjection.serverId())
                || !publication.projectionVersion().equals(hydrationProjection.projectionVersion())
                || !CatalogProjectionVersion.isSupported(publication.projectionVersion())
                || !publication.key().equals(hydrationProjection.key())
                || publication.revision() != hydrationProjection.revision()
                || hydrationProjection.kind() != CatalogCachePublication.Kind.FULL) {
                return null;
            }
            CatalogCachePublication nodePublication = nodePublication(publication);
            CatalogCachePublication nodeHydration = nodePublication(hydrationProjection);
            if (!sameProjectionEntries(nodePublication, nodeHydration)
                || authoringPublication != null && (!nodePublication.key().hasCatalogBinding()
                    || !nodePublication.key().catalogBinding().equals(authoringPublication.binding())
                    || !nodePublication.projectionVersion().equals(authoringPublication.projectionVersion())
                    || !canonicalAuthoring(authoringPublication))) {
                return null;
            }
            byte[] canonicalBytes = codec.encodeBytes(nodePublication);
            return new CachedPublication(serverId, nodePublication.key(), nodePublication, nodeHydration,
                canonicalBytes, authoringPublication, canonicalCapabilities(authoringCapabilities));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void rejectConflictingGenerations(Map<String, CachedPublication> values) {
        List<CatalogCacheKey> keys = new ArrayList<>();
        values.values().forEach(value -> keys.add(value.key()));
        for (int first = 0; first < keys.size(); first++) {
            for (int second = first + 1; second < keys.size(); second++) {
                CatalogCacheKey left = keys.get(first);
                CatalogCacheKey right = keys.get(second);
                if (sameAuthorityGeneration(left, right) && !left.equals(right)) {
                    throw new IllegalArgumentException("Catalog publication cache has conflicting catalog keys");
                }
            }
        }
    }

    private static boolean sameAuthorityGeneration(CatalogCacheKey left, CatalogCacheKey right) {
        return left.serverId().equals(right.serverId())
            && left.projectionVersion().equals(right.projectionVersion())
            && left.catalogGeneration() == right.catalogGeneration();
    }

    private static boolean sameAuthority(CatalogCacheKey left, CatalogCacheKey right) {
        return left.serverId().equals(right.serverId())
            && left.projectionVersion().equals(right.projectionVersion());
    }

    private static boolean deltaAdvances(CachedPublication previous, CatalogCachePublication candidate) {
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> existing = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : previous.hydrationProjection().entries()) {
            existing.put(entry.definitionKey(), entry);
        }
        for (CatalogCachePublication.Entry entry : candidate.entries()) {
            CatalogCachePublication.Entry prior = existing.get(entry.definitionKey());
            if (prior != null && entry.revision() <= prior.revision()) {
                return false;
            }
        }
        return true;
    }

    private static boolean appliesDelta(CachedPublication previous, CachedPublication candidate) {
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> expected = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : previous.hydrationProjection().entries()) {
            expected.put(entry.definitionKey(), entry);
        }
        for (CatalogCachePublication.Entry entry : candidate.publication().entries()) {
            expected.put(entry.definitionKey(), entry);
        }
        return expected.equals(entries(candidate.hydrationProjection()));
    }

    private static Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries(
        CatalogCachePublication publication) {
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : publication.entries()) {
            entries.put(entry.definitionKey(), entry);
        }
        return entries;
    }

    private byte[] encode(Map<String, Map<String, CachedPublication>> values) {
        Map<String, Object> servers = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(serverEntry -> {
            Map<String, Object> serverSnapshots = new LinkedHashMap<>();
            serverEntry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(cacheEntry -> {
                CachedPublication value = cacheEntry.getValue();
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("publication", codec.encode(value.publication()));
                if (value.publication().kind() == CatalogCachePublication.Kind.FULL
                    && value.publication().equals(value.hydrationProjection())) {
                    snapshot.put("projection", "publication");
                } else {
                    snapshot.put("projection", codec.encode(value.hydrationProjection()));
                }
                if (value.authoringPublication() != null) {
                    snapshot.put("authoring", Map.of(
                        "publication", authoringCodec.encode(value.authoringPublication()),
                        "capabilities", value.authoringCapabilities()
                    ));
                }
                serverSnapshots.put(cacheEntry.getKey(), snapshot);
            });
            servers.put(serverEntry.getKey(), serverSnapshots);
        });
        byte[] encoded = JsonValue.fromJava(Map.of(
            "schemaVersion", SCHEMA_VERSION,
            "servers", servers
        )).canonicalBytes(CanonicalLimits.catalog());
        if (encoded.length > MAX_CACHE_BYTES) {
            throw new IllegalArgumentException("Catalog publication cache exceeds its retention limit");
        }
        return encoded;
    }

    private PreparedReplacement fitSnapshots(Map<String, Map<String, CachedPublication>> values,
                                             String protectedServer, String protectedKey) {
        Map<String, Map<String, CachedPublication>> fitted = copySnapshots(values);
        ArrayDeque<SnapshotIdentity> evictions = new ArrayDeque<>(evictionOrder(fitted, protectedServer,
            protectedKey));
        while (true) {
            boolean clearlyOversized = estimatedBytes(fitted) > MAX_CACHE_BYTES + 65_536L;
            if (!clearlyOversized || evictions.isEmpty()) {
                try {
                    Map<String, Map<String, CachedPublication>> immutable = immutableSnapshots(fitted);
                    return new PreparedReplacement(immutable, encode(immutable));
                } catch (RuntimeException exception) {
                    if (evictions.isEmpty()) {
                        return null;
                    }
                }
            }
            SnapshotIdentity eviction = evictions.removeFirst();
            Map<String, CachedPublication> serverSnapshots = fitted.get(eviction.server());
            if (serverSnapshots == null || serverSnapshots.remove(eviction.key()) == null) {
                continue;
            }
            if (serverSnapshots.isEmpty()) {
                fitted.remove(eviction.server());
            }
        }
    }

    private List<SnapshotIdentity> evictionOrder(Map<String, Map<String, CachedPublication>> values,
                                                  String protectedServer, String protectedKey) {
        Map<String, String> newestByServer = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, CachedPublication>> server : values.entrySet()) {
            server.getValue().entrySet().stream().max(Comparator
                .comparingLong((Map.Entry<String, CachedPublication> entry) ->
                    entry.getValue().key().catalogGeneration())
                .thenComparingLong(entry -> entry.getValue().publication().revision())
                .thenComparing(Map.Entry::getKey)).ifPresent(entry -> newestByServer.put(server.getKey(),
                    entry.getKey()));
        }
        Comparator<SnapshotIdentity> oldestFirst = Comparator
            .comparingLong((SnapshotIdentity value) -> value.publication().key().catalogGeneration())
            .thenComparingLong(value -> value.publication().publication().revision())
            .thenComparing(SnapshotIdentity::server)
            .thenComparing(SnapshotIdentity::key);
        List<SnapshotIdentity> older = new ArrayList<>();
        List<SnapshotIdentity> newest = new ArrayList<>();
        for (Map.Entry<String, Map<String, CachedPublication>> server : values.entrySet()) {
            for (Map.Entry<String, CachedPublication> snapshot : server.getValue().entrySet()) {
                if (server.getKey().equals(protectedServer) && snapshot.getKey().equals(protectedKey)) {
                    continue;
                }
                SnapshotIdentity identity = new SnapshotIdentity(server.getKey(), snapshot.getKey(),
                    snapshot.getValue());
                if (snapshot.getKey().equals(newestByServer.get(server.getKey()))) {
                    newest.add(identity);
                } else {
                    older.add(identity);
                }
            }
        }
        older.sort(oldestFirst);
        newest.sort(oldestFirst);
        older.addAll(newest);
        return older;
    }

    private long estimatedBytes(Map<String, Map<String, CachedPublication>> values) {
        long bytes = 64L;
        try {
            for (Map.Entry<String, Map<String, CachedPublication>> server : values.entrySet()) {
                bytes += server.getKey().getBytes(StandardCharsets.UTF_8).length + 16L;
                for (Map.Entry<String, CachedPublication> snapshot : server.getValue().entrySet()) {
                    CachedPublication publication = snapshot.getValue();
                    bytes += snapshot.getKey().getBytes(StandardCharsets.UTF_8).length
                        + publication.canonicalByteLength() + 96L;
                    if (publication.publication().kind() != CatalogCachePublication.Kind.FULL
                        || !publication.publication().equals(publication.hydrationProjection())) {
                        bytes += codec.encodeBytes(publication.hydrationProjection()).length;
                    }
                    if (publication.authoringPublication() != null) {
                        bytes += authoringCodec.encodeBytes(publication.authoringPublication()).length;
                        bytes += publication.authoringCapabilities().stream()
                            .mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length + 8L).sum();
                    }
                    if (bytes > Integer.MAX_VALUE) {
                        return bytes;
                    }
                }
            }
            return bytes;
        } catch (RuntimeException exception) {
            return Long.MAX_VALUE;
        }
    }

    private boolean replaceSnapshots(Map<String, Map<String, CachedPublication>> values) {
        synchronized (pathLock) {
            byte[] encoded;
            try {
                encoded = encode(values);
            } catch (RuntimeException exception) {
                return false;
            }
            return replaceSnapshots(values, encoded);
        }
    }

    private boolean replaceSnapshots(Map<String, Map<String, CachedPublication>> values, byte[] encoded) {
        synchronized (pathLock) {
            if (!replace(encoded)) {
                return false;
            }
            durableWrites.incrementAndGet();
            installSnapshots(values);
            publishShared(snapshots);
            return true;
        }
    }

    private boolean replace(byte[] encoded) {
        try {
            if (encoded != null && encoded.length > MAX_CACHE_BYTES) {
                return false;
            }
            String payload = encoded == null ? "" : new String(encoded, StandardCharsets.ISO_8859_1);
            storage.write("payload", payload);
            return payload.equals(storage.read("payload"));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Map<String, Map<String, CachedPublication>> copySnapshots(
        Map<String, Map<String, CachedPublication>> source) {
        Map<String, Map<String, CachedPublication>> copy = new LinkedHashMap<>();
        source.forEach((server, values) -> copy.put(server, new LinkedHashMap<>(values)));
        return copy;
    }

    private Map<String, Map<String, CachedPublication>> validateSnapshots(
        Map<String, Map<String, CachedPublication>> values) {
        Objects.requireNonNull(values, "Catalog publication cache snapshots are required");
        Map<String, Map<String, CachedPublication>> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, CachedPublication>> serverEntry : values.entrySet()) {
            String serverKey = Objects.requireNonNull(serverEntry.getKey(), "Catalog cache server is required");
            ServerId serverId = ServerId.parseCanonicalText(serverKey);
            if (!serverId.canonicalText().equals(serverKey)) {
                throw new IllegalArgumentException("Catalog cache server key is not canonical");
            }
            Map<String, CachedPublication> serverValues = Objects.requireNonNull(serverEntry.getValue(),
                "Catalog cache server snapshots are required");
            if (serverValues.size() > maxSnapshots) {
                throw new IllegalArgumentException("Catalog publication cache retention is invalid");
            }
            Map<String, CachedPublication> checkedServer = new LinkedHashMap<>();
            for (Map.Entry<String, CachedPublication> cacheEntry : serverValues.entrySet()) {
                String cacheKey = Objects.requireNonNull(cacheEntry.getKey(), "Catalog cache key is required");
                CatalogCacheKey key = CatalogCacheKey.parseCanonicalText(cacheKey);
                if (!key.canonicalText().equals(cacheKey) || !serverId.equals(key.serverId())) {
                    throw new IllegalArgumentException("Catalog publication cache key is not bound to its server");
                }
                CachedPublication value = Objects.requireNonNull(cacheEntry.getValue(),
                    "Catalog publication cache snapshot is required");
                if (!serverId.equals(value.serverId()) || !key.equals(value.key())) {
                    throw new IllegalArgumentException("Catalog publication cache snapshot identity mismatch");
                }
                CachedPublication checkedValue = validate(serverId, value.publication(), value.canonicalBytes(),
                    value.hydrationProjection(), value.authoringPublication(), value.authoringCapabilities());
                if (checkedValue == null) {
                    throw new IllegalArgumentException("Catalog publication cache snapshot is invalid");
                }
                checkedServer.put(cacheKey, checkedValue);
            }
            rejectConflictingGenerations(checkedServer);
            checked.put(serverKey, Map.copyOf(checkedServer));
        }
        return immutableSnapshots(checked);
    }

    private Map<String, Map<String, CachedPublication>> mergeRestore(
        Map<String, Map<String, CachedPublication>> current,
        Map<String, Map<String, CachedPublication>> knownCurrent,
        Map<String, Map<String, CachedPublication>> requested) {
        Map<String, Map<String, CachedPublication>> restored = copySnapshots(current);
        for (Map.Entry<String, Map<String, CachedPublication>> serverEntry : knownCurrent.entrySet()) {
            String serverKey = serverEntry.getKey();
            Map<String, CachedPublication> knownServer = serverEntry.getValue();
            Map<String, CachedPublication> currentServer = current.getOrDefault(serverKey, Map.of());
            Map<String, CachedPublication> requestedServer = requested.getOrDefault(serverKey, Map.of());
            Map<String, CachedPublication> restoredServer = new LinkedHashMap<>(restored.getOrDefault(serverKey, Map.of()));
            for (String cacheKey : unionKeys(knownServer, requestedServer)) {
                CachedPublication known = knownServer.get(cacheKey);
                CachedPublication disk = currentServer.get(cacheKey);
                if (known != null && sameCachedPublication(disk, known)) {
                    CachedPublication replacement = requestedServer.get(cacheKey);
                    if (replacement == null) {
                        restoredServer.remove(cacheKey);
                    } else {
                        restoredServer.put(cacheKey, replacement);
                    }
                } else if (known == null && disk == null && current.containsKey(serverKey)) {
                    CachedPublication replacement = requestedServer.get(cacheKey);
                    if (replacement != null) {
                        restoredServer.put(cacheKey, replacement);
                    }
                }
            }
            if (restoredServer.isEmpty()) {
                restored.remove(serverKey);
            } else {
                restored.put(serverKey, Map.copyOf(restoredServer));
            }
        }
        for (Map.Entry<String, Map<String, CachedPublication>> serverEntry : requested.entrySet()) {
            if (knownCurrent.containsKey(serverEntry.getKey())) {
                continue;
            }
            Map<String, CachedPublication> currentServer = current.get(serverEntry.getKey());
            if (currentServer != null) {
                continue;
            }
            restored.put(serverEntry.getKey(), serverEntry.getValue());
        }
        return restored;
    }

    private static Set<String> unionKeys(Map<String, CachedPublication> first, Map<String, CachedPublication> second) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(first.keySet());
        keys.addAll(second.keySet());
        return keys;
    }

    private static boolean sameSnapshots(Map<String, Map<String, CachedPublication>> first,
                                         Map<String, Map<String, CachedPublication>> second) {
        if (!first.keySet().equals(second.keySet())) {
            return false;
        }
        for (String server : first.keySet()) {
            Map<String, CachedPublication> firstValues = first.get(server);
            Map<String, CachedPublication> secondValues = second.get(server);
            if (!firstValues.keySet().equals(secondValues.keySet())) {
                return false;
            }
            for (String key : firstValues.keySet()) {
                if (!sameCachedPublication(firstValues.get(key), secondValues.get(key))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameCachedPublication(CachedPublication first, CachedPublication second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.serverId().equals(second.serverId())
            && first.key().equals(second.key())
            && first.publication().equals(second.publication())
            && first.hydrationProjection().equals(second.hydrationProjection())
            && Arrays.equals(first.canonicalBytes(), second.canonicalBytes())
            && Objects.equals(first.authoringPublication(), second.authoringPublication())
            && first.authoringCapabilities().equals(second.authoringCapabilities());
    }

    private static boolean sameNodePublication(CachedPublication first, CachedPublication second) {
        return first.serverId().equals(second.serverId())
            && first.key().equals(second.key())
            && first.publication().equals(second.publication())
            && first.hydrationProjection().equals(second.hydrationProjection())
            && Arrays.equals(first.canonicalBytes(), second.canonicalBytes());
    }

    private static Map<String, Map<String, CachedPublication>> immutableSnapshots(Map<String, Map<String, CachedPublication>> values) {
        Map<String, Map<String, CachedPublication>> copy = new LinkedHashMap<>();
        values.forEach((server, snapshots) -> copy.put(server, Map.copyOf(snapshots)));
        return Map.copyOf(copy);
    }

    private static JsonValue.JsonObject object(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static void requireFields(JsonValue.JsonObject object, Set<String> required, String... optional) {
        Set<String> optionalFields = Set.of(optional);
        if (!object.fields().keySet().containsAll(required)
            || object.fields().keySet().stream().anyMatch(field -> !required.contains(field) && !optionalFields.contains(field))) {
            throw new IllegalArgumentException("Catalog publication cache fields are invalid");
        }
    }

    private static long integer(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonNumber number) || number.value().scale() != 0) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is out of range", exception);
        }
    }

    private static List<String> stringList(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        List<String> values = new ArrayList<>(array.values().size());
        for (JsonValue member : array.values()) {
            if (!(member instanceof JsonValue.JsonString text) || text.value().isBlank()
                || !text.value().equals(text.value().strip())) {
                throw new IllegalArgumentException(name + " contains an invalid value");
            }
            values.add(text.value());
        }
        List<String> canonical = canonicalCapabilities(values);
        if (!canonical.equals(values)) {
            throw new IllegalArgumentException(name + " must be canonical");
        }
        return canonical;
    }

    public record CachedPublication(ServerId serverId, CatalogCacheKey key, CatalogCachePublication publication,
                                    CatalogCachePublication hydrationProjection, byte[] canonicalBytes,
                                    CatalogAuthoringPublication authoringPublication,
                                    List<String> authoringCapabilities) {
        public CachedPublication(ServerId serverId, CatalogCacheKey key, CatalogCachePublication publication,
                                 CatalogCachePublication hydrationProjection, byte[] canonicalBytes) {
            this(serverId, key, publication, hydrationProjection, canonicalBytes, null, List.of());
        }

        public CachedPublication {
            serverId = Objects.requireNonNull(serverId, "Server ID is required");
            key = Objects.requireNonNull(key, "Catalog cache key is required");
            publication = Objects.requireNonNull(publication, "Catalog publication is required");
            hydrationProjection = Objects.requireNonNull(hydrationProjection, "Catalog hydration projection is required");
            if (publication.authoringPublication() != null || hydrationProjection.authoringPublication() != null) {
                throw new IllegalArgumentException("Catalog publication cache accepts node publication only");
            }
            canonicalBytes = Objects.requireNonNull(canonicalBytes, "Catalog publication bytes are required").clone();
            authoringCapabilities = canonicalCapabilities(authoringCapabilities);
            if (authoringPublication == null && !authoringCapabilities.isEmpty()) {
                throw new IllegalArgumentException("Catalog authoring capabilities require a publication");
            }
            if (authoringPublication != null && (!key.hasCatalogBinding()
                || !key.catalogBinding().equals(authoringPublication.binding())
                || !key.projectionVersion().equals(authoringPublication.projectionVersion()))) {
                throw new IllegalArgumentException("Catalog authoring publication does not match its cache key");
            }
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }

        boolean matches(ReSyncCatalogPublicationProjection.Snapshot snapshot,
                        ReSyncCatalogAuthoringProjection.Snapshot authoring,
                        List<String> capabilities) {
            if (snapshot == null || !key.equals(snapshot.publication().key())
                || publication.revision() != snapshot.publication().revision()
                || !publication.equals(snapshot.publication())
                || !hydrationProjection.equals(snapshot.hydrationPublication())
                || !snapshot.canonicalBytesEqual(canonicalBytes)) {
                return false;
            }
            return authoring == null
                ? authoringPublication == null
                : authoring.key().equals(key) && authoring.revision() == publication.revision()
                    && authoring.publication().equals(authoringPublication)
                    && authoringCapabilities.equals(canonicalCapabilities(capabilities));
        }

        int canonicalByteLength() {
            return canonicalBytes.length;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof CachedPublication other)) {
                return false;
            }
            return serverId.equals(other.serverId)
                && key.equals(other.key)
                && publication.equals(other.publication)
                && hydrationProjection.equals(other.hydrationProjection)
                && Arrays.equals(canonicalBytes, other.canonicalBytes)
                && Objects.equals(authoringPublication, other.authoringPublication)
                && authoringCapabilities.equals(other.authoringCapabilities);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(serverId, key, publication, hydrationProjection, authoringPublication,
                authoringCapabilities);
            return 31 * result + Arrays.hashCode(canonicalBytes);
        }
    }

    public record State(Map<String, Map<String, CachedPublication>> snapshots) {
        public State {
            Objects.requireNonNull(snapshots, "Catalog publication cache snapshots are required");
            Map<String, Map<String, CachedPublication>> copy = new LinkedHashMap<>();
            snapshots.forEach((server, values) -> copy.put(Objects.requireNonNull(server, "Catalog cache server is required"),
                Map.copyOf(Objects.requireNonNull(values, "Catalog cache server snapshots are required"))));
            snapshots = Map.copyOf(copy);
        }

        @Override
        public Map<String, Map<String, CachedPublication>> snapshots() {
            return snapshots;
        }
    }

    public record Persistence(CatalogCacheKey key, long revision, Status status, String diagnostic) {
        public Persistence {
            key = Objects.requireNonNull(key, "Catalog cache key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Catalog publication revision cannot be negative");
            }
            status = Objects.requireNonNull(status, "Catalog cache persistence status is required");
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (status == Status.STORED && !diagnostic.isEmpty()) {
                throw new IllegalArgumentException("Stored catalog cache persistence cannot carry a diagnostic");
            }
            if (status == Status.REJECTED && diagnostic.isBlank()) {
                throw new IllegalArgumentException("Rejected catalog cache persistence must carry a diagnostic");
            }
        }

        public static Persistence stored(CatalogCacheKey key, long revision) {
            return new Persistence(key, revision, Status.STORED, "");
        }

        public static Persistence rejected(CatalogCacheKey key, long revision, String diagnostic) {
            return new Persistence(key, revision, Status.REJECTED, diagnostic);
        }

        public boolean stored() {
            return status == Status.STORED;
        }

        public enum Status {
            STORED,
            REJECTED
        }
    }

    record CacheMetrics(long exactReplayStores, long validatedStores, long durableWrites) {
    }

    private record SnapshotIdentity(String server, String key, CachedPublication publication) {
    }

    private record PreparedReplacement(Map<String, Map<String, CachedPublication>> snapshots, byte[] encoded) {
    }

    private record PendingStore(CatalogCacheKey key, long revision, BooleanSupplier transaction,
                                Async<Persistence> completion) {
        private PendingStore {
            key = Objects.requireNonNull(key, "Catalog cache key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Catalog publication revision cannot be negative");
            }
            transaction = Objects.requireNonNull(transaction, "Catalog cache persistence transaction is required");
            completion = Objects.requireNonNull(completion, "Catalog cache persistence completion is required");
        }
    }

    private record StoreState(String identity, Object lock, int maxSnapshots) {
        private StoreState {
            identity = Objects.requireNonNull(identity, "Catalog cache identity is required");
            lock = Objects.requireNonNull(lock, "Catalog cache path lock is required");
            if (maxSnapshots < 1) {
                throw new IllegalArgumentException("Maximum catalog publication snapshots must be positive");
            }
        }
    }
}
