package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ServerId;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ReSyncCatalogPublicationProjection {
    public static final String NON_CANONICAL_SERVER_ID = "CATALOG_PUBLICATION_SERVER_ID_NON_CANONICAL";
    private static final CatalogProjectionVersion PROJECTION_VERSION = CatalogProjectionVersion.current();
    private static final CatalogCachePublicationCodec PUBLICATION_CODEC = new CatalogCachePublicationCodec();

    private final ServerId expectedServerId;
    private final String identityDiagnostic;
    private final ReSyncCatalogPublicationCache durableCache;
    private final BrowserSafeState.ReferenceValue<CatalogCacheKey> acknowledgedKey = new BrowserSafeState.ReferenceValue<>();
    private final BrowserSafeState.ReferenceValue<Snapshot> active = new BrowserSafeState.ReferenceValue<>();

    public ReSyncCatalogPublicationProjection(ServerId expectedServerId) {
        this(Objects.requireNonNull(expectedServerId, "Expected server ID is required"), null, null);
    }

    public ReSyncCatalogPublicationProjection(ServerId expectedServerId, ReSyncCatalogPublicationCache durableCache) {
        this(Objects.requireNonNull(expectedServerId, "Expected server ID is required"), null, durableCache);
    }

    private ReSyncCatalogPublicationProjection(ServerId expectedServerId, String identityDiagnostic,
                                               ReSyncCatalogPublicationCache durableCache) {
        this.expectedServerId = expectedServerId;
        this.identityDiagnostic = identityDiagnostic;
        this.durableCache = durableCache;
    }

    public static ReSyncCatalogPublicationProjection forConfiguredServerId(String configuredServerId) {
        return forConfiguredServerId(configuredServerId, null);
    }

    public static ReSyncCatalogPublicationProjection forConfiguredServerId(String configuredServerId,
                                                                            ReSyncCatalogPublicationCache durableCache) {
        try {
            if (configuredServerId == null || configuredServerId.isBlank()) {
                throw new IllegalArgumentException("Configured server ID is blank");
            }
            ServerId expectedServerId = ServerId.parseCanonicalText(configuredServerId);
            if (!expectedServerId.canonicalText().equals(configuredServerId)) {
                throw new IllegalArgumentException("Configured server ID is not canonical");
            }
            return new ReSyncCatalogPublicationProjection(expectedServerId, null, durableCache);
        } catch (RuntimeException exception) {
            return new ReSyncCatalogPublicationProjection(null, NON_CANONICAL_SERVER_ID, durableCache);
        }
    }

    public synchronized boolean acknowledgeActiveKey(CatalogCacheKey key) {
        Objects.requireNonNull(key, "Catalog cache key is required");
        if (!acceptsServer(key) || !PROJECTION_VERSION.equals(key.projectionVersion())) {
            return false;
        }
        Snapshot current = active.get();
        if (current != null && (!sameAuthority(current.publication().key(), key)
            || key.catalogGeneration() < current.publication().catalogGeneration())) {
            return false;
        }
        CatalogCacheKey previous = acknowledgedKey.get();
        if (previous == null) {
            return acknowledgedKey.compareAndSet(null, key);
        }
        if (previous.equals(key)) {
            return true;
        }
        return sameAuthority(previous, key)
            && key.catalogGeneration() > previous.catalogGeneration()
            && acknowledgedKey.compareAndSet(previous, key);
    }

    public synchronized boolean apply(CatalogCachePublication publication, byte[] canonicalBytes) {
        Optional<Prepared> prepared = prepare(publication, canonicalBytes);
        if (prepared.isEmpty()) {
            return false;
        }
        Prepared candidate = prepared.orElseThrow();
        ReSyncCatalogPublicationCache.State previousCache = durableCache == null ? null : durableCache.snapshotState();
        if (durableCache != null && !durableCache.store(expectedServerId, candidate.candidate().publication(),
            candidate.candidate().canonicalBytes(), candidate.candidate().hydrationPublication())) {
            return false;
        }
        if (commit(candidate)) {
            return true;
        }
        if (durableCache != null && previousCache != null) {
            durableCache.restore(previousCache);
        }
        return false;
    }

    public synchronized Optional<Prepared> prepare(CatalogCachePublication publication, byte[] canonicalBytes) {
        Objects.requireNonNull(publication, "Catalog publication is required");
        Objects.requireNonNull(canonicalBytes, "Catalog publication bytes are required");
        if (canonicalBytes.length == 0 || !acceptsServer(publication.key())
            || !PROJECTION_VERSION.equals(publication.projectionVersion())
            || !canonicalPublication(publication, canonicalBytes)) {
            return Optional.empty();
        }
        CatalogCachePublication nodePublication = nodePublication(publication);
        byte[] nodeCanonicalBytes = PUBLICATION_CODEC.encodeBytes(nodePublication);
        CatalogCacheKey expected = acknowledgedKey.get();
        if (expected != null && !expected.equals(nodePublication.key())) {
            if (nodePublication.kind() != CatalogCachePublication.Kind.FULL
                || !sameAuthority(expected, nodePublication.key())
                || nodePublication.catalogGeneration() <= expected.catalogGeneration()) {
                return Optional.empty();
            }
        }
        Snapshot previous = active.get();
        if (previous != null && nodePublication.key().equals(previous.publication().key())) {
            if (nodePublication.revision() < previous.publication().revision()) {
                return Optional.empty();
            }
            if (nodePublication.revision() == previous.publication().revision()) {
                return nodePublication.equals(previous.publication())
                    && Arrays.equals(nodeCanonicalBytes, previous.canonicalBytes())
                    ? Optional.of(new Prepared(previous, previous)) : Optional.empty();
            }
        }
        if (previous != null && !nodePublication.key().equals(previous.publication().key())
            && (!sameAuthority(previous.publication().key(), nodePublication.key())
                || nodePublication.catalogGeneration() <= previous.publication().catalogGeneration())) {
            return Optional.empty();
        }
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries = stageEntries(nodePublication, previous);
        if (entries == null) {
            return Optional.empty();
        }
        Snapshot candidate = new Snapshot(nodePublication, entries, nodeCanonicalBytes);
        return Optional.of(new Prepared(previous, candidate));
    }

    public synchronized boolean commit(Prepared prepared) {
        Objects.requireNonNull(prepared, "Prepared catalog projection is required");
        if (active.get() != prepared.previous()) {
            return false;
        }
        Snapshot candidate = prepared.candidate();
        if (!validSnapshot(candidate) || !acceptsTransition(prepared.previous(), candidate)) {
            return false;
        }
        active.set(candidate);
        acknowledgedKey.set(candidate.publication().key());
        return true;
    }

    synchronized boolean commitPrepared(Prepared prepared) {
        Objects.requireNonNull(prepared, "Prepared catalog projection is required");
        if (active.get() != prepared.previous()) {
            return false;
        }
        Snapshot candidate = prepared.candidate();
        active.set(candidate);
        acknowledgedKey.set(candidate.publication().key());
        return true;
    }

    synchronized void restorePrepared(Snapshot snapshot, CatalogCacheKey acknowledged) {
        active.set(snapshot);
        acknowledgedKey.set(acknowledged);
    }

    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        CatalogCachePublication publication = snapshot.publication();
        byte[] canonicalBytes = snapshot.canonicalBytes();
        if (!acceptsServer(publication.key()) || !PROJECTION_VERSION.equals(publication.projectionVersion())
            || !canonicalPublication(publication, canonicalBytes)) {
            throw new IllegalArgumentException("Catalog publication snapshot is invalid");
        }
        CatalogCachePublication nodePublication = nodePublication(publication);
        byte[] nodeCanonicalBytes = PUBLICATION_CODEC.encodeBytes(nodePublication);
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries = validatedEntries(nodePublication, snapshot.entries());
        if (entries == null) {
            throw new IllegalArgumentException("Catalog publication snapshot entries are invalid");
        }
        active.set(new Snapshot(nodePublication, entries, nodeCanonicalBytes));
        acknowledgedKey.set(nodePublication.key());
    }

    public boolean apply(byte[] canonicalBytes, CatalogCachePublicationCodec codec) {
        Objects.requireNonNull(codec, "Catalog publication codec is required");
        Objects.requireNonNull(canonicalBytes, "Catalog publication bytes are required");
        CatalogCachePublication publication = codec.decodeBytes(canonicalBytes);
        return apply(publication, canonicalBytes);
    }

    public synchronized boolean hydrateFromCache() {
        if (durableCache == null || expectedServerId == null) {
            return false;
        }
        Optional<ReSyncCatalogPublicationCache.CachedPublication> cached = durableCache.latest(expectedServerId);
        return cached.isPresent() && hydrate(cached.orElseThrow());
    }

    public boolean hydrate(ReSyncCatalogPublicationCache.CachedPublication cached) {
        Optional<PreparedHydration> prepared = prepareHydration(cached);
        return prepared.isPresent() && commitHydration(prepared.orElseThrow());
    }

    Optional<PreparedHydration> prepareHydration(ReSyncCatalogPublicationCache.CachedPublication cached) {
        Objects.requireNonNull(cached, "Cached catalog publication is required");
        Snapshot previous = active.get();
        CatalogCacheKey expected = acknowledgedKey.get();
        if (expectedServerId == null || !expectedServerId.equals(cached.serverId())
            || !acceptsServer(cached.key()) || !PROJECTION_VERSION.equals(cached.key().projectionVersion())
            || !cached.key().equals(cached.publication().key())
            || !cached.key().equals(cached.hydrationProjection().key())
            || cached.hydrationProjection().kind() != CatalogCachePublication.Kind.FULL
            || cached.publication().revision() != cached.hydrationProjection().revision()) {
            return Optional.empty();
        }
        byte[] canonicalBytes = cached.canonicalBytes();
        if (!canonicalPublication(cached.publication(), canonicalBytes)) {
            return Optional.empty();
        }
        CatalogCachePublication nodePublication = nodePublication(cached.publication());
        CatalogCachePublication nodeHydrationProjection = nodePublication(cached.hydrationProjection());
        byte[] nodeCanonicalBytes = PUBLICATION_CODEC.encodeBytes(nodePublication);
        if (!sameHydrationMetadata(nodePublication, nodeHydrationProjection)) {
            return Optional.empty();
        }
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries = validHydrationEntries(
            nodePublication, nodeHydrationProjection);
        if (entries == null) {
            return Optional.empty();
        }
        if (expected != null && !expected.equals(nodePublication.key())
            && (nodePublication.kind() != CatalogCachePublication.Kind.FULL
                || !sameAuthority(expected, nodePublication.key())
                || nodePublication.catalogGeneration() <= expected.catalogGeneration())) {
            return Optional.empty();
        }
        if (previous != null) {
            if (previous.publication().key().equals(nodePublication.key())) {
                if (previous.publication().revision() == nodePublication.revision()) {
                    return sameSnapshot(previous, nodePublication, nodeCanonicalBytes, nodeHydrationProjection, entries)
                        ? Optional.of(new PreparedHydration(previous, expected, previous)) : Optional.empty();
                }
                if (nodePublication.revision() <= previous.publication().revision()) {
                    return Optional.empty();
                }
                if (nodePublication.kind() == CatalogCachePublication.Kind.DELTA
                    && (!deltaAdvances(previous, nodePublication)
                        || !appliesDelta(previous.entries(), nodePublication, entries))) {
                    return Optional.empty();
                }
            } else if (!sameAuthority(previous.publication().key(), nodePublication.key())
                || nodePublication.kind() != CatalogCachePublication.Kind.FULL
                || nodePublication.catalogGeneration() <= previous.publication().catalogGeneration()) {
                return Optional.empty();
            }
        }
        Snapshot candidate = new Snapshot(nodePublication, entries, nodeCanonicalBytes);
        return Optional.of(new PreparedHydration(previous, expected, candidate));
    }

    synchronized boolean commitHydration(PreparedHydration prepared) {
        Objects.requireNonNull(prepared, "Prepared catalog hydration is required");
        CatalogCacheKey currentAcknowledged = acknowledgedKey.get();
        if (active.get() != prepared.previous()
            || (!Objects.equals(currentAcknowledged, prepared.acknowledgedKey())
                && !Objects.equals(currentAcknowledged, prepared.candidate().publication().key()))) {
            return false;
        }
        Snapshot candidate = prepared.candidate();
        active.set(candidate);
        acknowledgedKey.set(candidate.publication().key());
        return true;
    }

    public synchronized boolean hydrateSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            clear();
            return false;
        }
        restore(snapshot);
        return true;
    }

    public synchronized Optional<CatalogCacheKey> acknowledgedKey() {
        return Optional.ofNullable(acknowledgedKey.get());
    }

    public synchronized Optional<ServerId> expectedServerId() {
        return Optional.ofNullable(expectedServerId);
    }

    public synchronized Optional<String> identityDiagnostic() {
        return Optional.ofNullable(identityDiagnostic);
    }

    public synchronized Optional<Snapshot> active() {
        return Optional.ofNullable(active.get());
    }

    public synchronized void clear() {
        active.set(null);
        acknowledgedKey.set(null);
    }

    private Map<ContractRef<NodeId>, CatalogCachePublication.Entry> stageEntries(CatalogCachePublication publication,
                                                                                    Snapshot previous) {
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries;
        if (publication.kind() == CatalogCachePublication.Kind.FULL) {
            entries = new LinkedHashMap<>();
        } else {
            if (previous == null || !previous.publication().key().equals(publication.key())) {
                return null;
            }
            entries = new LinkedHashMap<>(previous.entries());
        }
        for (CatalogCachePublication.Entry entry : publication.entries()) {
            CatalogCachePublication.Entry existing = entries.get(entry.definitionKey());
            if (publication.kind() == CatalogCachePublication.Kind.DELTA
                && existing != null && entry.revision() <= existing.revision()) {
                return null;
            }
            entries.put(entry.definitionKey(), entry);
        }
        return entries;
    }

    private Map<ContractRef<NodeId>, CatalogCachePublication.Entry> validHydrationEntries(
        CatalogCachePublication publication, CatalogCachePublication hydrationProjection) {
        if (publication == null || hydrationProjection == null
            || hydrationProjection.kind() != CatalogCachePublication.Kind.FULL
            || !sameHydrationMetadata(publication, hydrationProjection)) {
            return null;
        }
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : hydrationProjection.entries()) {
            if (entries.put(entry.definitionKey(), entry) != null) {
                return null;
            }
        }
        if (publication.kind() == CatalogCachePublication.Kind.FULL) {
            return publication.entries().equals(hydrationProjection.entries()) ? entries : null;
        }
        return validatedEntries(publication, entries);
    }

    private boolean sameAuthority(CatalogCacheKey first, CatalogCacheKey second) {
        return first.serverId().equals(second.serverId())
            && first.projectionVersion().equals(second.projectionVersion());
    }

    private boolean acceptsServer(CatalogCacheKey key) {
        return expectedServerId != null && key != null && expectedServerId.equals(key.serverId());
    }

    private boolean canonicalPublication(CatalogCachePublication publication, byte[] canonicalBytes) {
        if (publication == null || canonicalBytes == null || canonicalBytes.length == 0
            || !acceptsServer(publication.key()) || !PROJECTION_VERSION.equals(publication.projectionVersion())) {
            return false;
        }
        try {
            CatalogCachePublication decoded = PUBLICATION_CODEC.decodeBytes(canonicalBytes);
            return publication.equals(decoded) && Arrays.equals(canonicalBytes, PUBLICATION_CODEC.encodeBytes(decoded));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private CatalogCachePublication nodePublication(CatalogCachePublication publication) {
        return publication.authoringPublication() == null ? publication : publication.withAuthoringPublication(null);
    }

    private boolean validSnapshot(Snapshot snapshot) {
        return snapshot != null
            && snapshot.publication().authoringPublication() == null
            && snapshot.hydrationPublication().authoringPublication() == null
            && canonicalPublication(snapshot.publication(), snapshot.canonicalBytes())
            && validatedEntries(snapshot.publication(), snapshot.entries()) != null;
    }

    private boolean acceptsTransition(Snapshot previous, Snapshot candidate) {
        if (previous == null) {
            return candidate.publication().kind() == CatalogCachePublication.Kind.FULL;
        }
        CatalogCachePublication previousPublication = previous.publication();
        CatalogCachePublication candidatePublication = candidate.publication();
        if (previousPublication.key().equals(candidatePublication.key())) {
            if (candidatePublication.revision() == previousPublication.revision()) {
                return sameSnapshot(previous, candidatePublication, candidate.canonicalBytes(),
                    candidate.hydrationPublication(), candidate.entries());
            }
            if (candidatePublication.revision() <= previousPublication.revision()) {
                return false;
            }
            return candidatePublication.kind() == CatalogCachePublication.Kind.FULL
                || deltaAdvances(previous, candidatePublication);
        }
        return candidatePublication.kind() == CatalogCachePublication.Kind.FULL
            && sameAuthority(previousPublication.key(), candidatePublication.key())
            && candidatePublication.catalogGeneration() > previousPublication.catalogGeneration();
    }

    private boolean deltaAdvances(Snapshot previous, CatalogCachePublication candidate) {
        for (CatalogCachePublication.Entry entry : candidate.entries()) {
            CatalogCachePublication.Entry existing = previous.entries().get(entry.definitionKey());
            if (existing != null && entry.revision() <= existing.revision()) {
                return false;
            }
        }
        return true;
    }

    private boolean appliesDelta(Map<ContractRef<NodeId>, CatalogCachePublication.Entry> previous,
                                 CatalogCachePublication delta,
                                 Map<ContractRef<NodeId>, CatalogCachePublication.Entry> hydrated) {
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> expected = new LinkedHashMap<>(previous);
        for (CatalogCachePublication.Entry entry : delta.entries()) {
            expected.put(entry.definitionKey(), entry);
        }
        return expected.equals(hydrated);
    }

    private boolean sameSnapshot(Snapshot previous, CatalogCachePublication publication, byte[] canonicalBytes,
                                 CatalogCachePublication hydrationProjection,
                                 Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries) {
        return previous.publication().equals(publication)
            && previous.canonicalBytesEqual(canonicalBytes)
            && previous.entries().equals(entries)
            && previous.hydrationPublication().equals(hydrationProjection);
    }

    private Map<ContractRef<NodeId>, CatalogCachePublication.Entry> validatedEntries(
        CatalogCachePublication publication, Map<ContractRef<NodeId>, CatalogCachePublication.Entry> values) {
        if (publication == null || values == null) {
            return null;
        }
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries = new LinkedHashMap<>();
        try {
            for (Map.Entry<ContractRef<NodeId>, CatalogCachePublication.Entry> value : values.entrySet()) {
                ContractRef<NodeId> key = Objects.requireNonNull(value.getKey(), "Catalog definition key is required");
                CatalogCachePublication.Entry entry = Objects.requireNonNull(value.getValue(),
                    "Catalog publication entry is required");
                if (!key.equals(entry.definitionKey()) || entry.revision() > publication.revision()) {
                    return null;
                }
                entries.put(key, entry);
            }
            Map<ContractRef<NodeId>, CatalogCachePublication.Entry> publicationEntries = new LinkedHashMap<>();
            for (CatalogCachePublication.Entry entry : publication.entries()) {
                publicationEntries.put(entry.definitionKey(), entry);
            }
            if (publication.kind() == CatalogCachePublication.Kind.FULL) {
                return entries.equals(publicationEntries) ? entries : null;
            }
            for (Map.Entry<ContractRef<NodeId>, CatalogCachePublication.Entry> entry : publicationEntries.entrySet()) {
                if (!entry.getValue().equals(entries.get(entry.getKey()))) {
                    return null;
                }
            }
            for (Map.Entry<ContractRef<NodeId>, CatalogCachePublication.Entry> entry : entries.entrySet()) {
                if (!publicationEntries.containsKey(entry.getKey())
                    && entry.getValue().revision() >= publication.revision()) {
                    return null;
                }
            }
            return entries;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean sameHydrationMetadata(CatalogCachePublication publication,
                                          CatalogCachePublication hydrationProjection) {
        return hydrationProjection != null
            && hydrationProjection.kind() == CatalogCachePublication.Kind.FULL
            && publication.key().equals(hydrationProjection.key())
            && publication.revision() == hydrationProjection.revision()
            && Objects.equals(publication.authoringPublication(), hydrationProjection.authoringPublication())
            && publication.unknown().equals(hydrationProjection.unknown());
    }

    public record Snapshot(CatalogCachePublication publication,
                           Map<ContractRef<NodeId>, CatalogCachePublication.Entry> entries,
                           byte[] canonicalBytes) {
        public Snapshot {
            publication = Objects.requireNonNull(publication, "Catalog publication is required");
            Objects.requireNonNull(entries, "Catalog publication entries are required");
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            Objects.requireNonNull(canonicalBytes, "Catalog publication bytes are required");
            if (canonicalBytes.length == 0) {
                throw new IllegalArgumentException("Catalog publication bytes cannot be empty");
            }
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }

        public Optional<CatalogCachePublication.Entry> entry(ContractRef<NodeId> key) {
            return Optional.ofNullable(entries.get(Objects.requireNonNull(key, "Catalog definition key is required")));
        }

        public boolean canonicalBytesEqual(byte[] bytes) {
            return Arrays.equals(canonicalBytes, bytes);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Snapshot other)) {
                return false;
            }
            return publication.equals(other.publication)
                && entries.equals(other.entries)
                && Arrays.equals(canonicalBytes, other.canonicalBytes);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(publication, entries);
            return 31 * result + Arrays.hashCode(canonicalBytes);
        }

        public CatalogCachePublication hydrationPublication() {
            return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, publication.key(),
                publication.catalogBinding(), publication.revision(), List.copyOf(entries.values()),
                publication.authoringPublication(), publication.unknown());
        }
    }

    public record Prepared(Snapshot previous, Snapshot candidate) {
        public Prepared {
            Objects.requireNonNull(candidate, "Prepared catalog candidate is required");
        }
    }

    record PreparedHydration(Snapshot previous, CatalogCacheKey acknowledgedKey, Snapshot candidate) {
        PreparedHydration {
            candidate = Objects.requireNonNull(candidate, "Prepared catalog hydration candidate is required");
        }
    }
}
