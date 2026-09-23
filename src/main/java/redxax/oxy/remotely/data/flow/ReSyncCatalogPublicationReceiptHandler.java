package redxax.oxy.remotely.data.flow;

import restudio.rescreen.platform.Async;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.identity.ServerId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class ReSyncCatalogPublicationReceiptHandler {
    private static final String READ_ONLY_DISPATCH = "CATALOG_PUBLICATION.DISPATCH_NOT_APPLIED";
    private static final String READ_ONLY_RECEIPT = "CATALOG_PUBLICATION.RECEIPT_NOT_APPLIED";
    private static final String READ_ONLY_KEY = "CATALOG_PUBLICATION.RECEIPT_KEY_MISMATCH";
    private static final String READ_ONLY_REVISION = "CATALOG_PUBLICATION.RECEIPT_REVISION_MISMATCH";
    private static final String READ_ONLY_STALE = "CATALOG_PUBLICATION.RECEIPT_STALE";
    private static final String REJECTED_SERVER = "CATALOG_PUBLICATION.RECEIPT_SERVER_MISMATCH";
    private static final String REJECTED_CANONICAL = "CATALOG_PUBLICATION.RECEIPT_NON_CANONICAL";

    private final ServerId expectedServerId;
    private final String expectedSessionKey;
    private final ReSyncCatalogPublicationProjection projection;
    private final ReSyncCatalogAuthoringProjection authoringProjection;
    private final ReSyncCatalogPublicationCache durableCache;
    private final List<String> authoringCapabilities;
    private final CatalogAuthoringPublicationCodec authoringCodec = new CatalogAuthoringPublicationCodec();
    private volatile boolean authoringRequired;
    private long cachePersistenceSequence;
    private volatile CachePersistence cachePersistence = CachePersistence.idle();
    private volatile Async<CachePersistence> cachePersistenceCompletion =
        Async.completed(cachePersistence);

    public ReSyncCatalogPublicationReceiptHandler(ServerId expectedServerId, String expectedSessionKey,
                                                  ReSyncCatalogPublicationProjection projection) {
        this(expectedServerId, expectedSessionKey, projection, null, null);
    }

    public ReSyncCatalogPublicationReceiptHandler(ServerId expectedServerId, String expectedSessionKey,
                                                  ReSyncCatalogPublicationProjection projection,
                                                  ReSyncCatalogAuthoringProjection authoringProjection,
                                                  ReSyncCatalogPublicationCache durableCache) {
        this(expectedServerId, expectedSessionKey, projection, authoringProjection, durableCache, List.of());
    }

    public ReSyncCatalogPublicationReceiptHandler(ServerId expectedServerId, String expectedSessionKey,
                                                  ReSyncCatalogPublicationProjection projection,
                                                  ReSyncCatalogAuthoringProjection authoringProjection,
                                                  ReSyncCatalogPublicationCache durableCache,
                                                  List<String> authoringCapabilities) {
        this.expectedServerId = Objects.requireNonNull(expectedServerId, "Expected server ID is required");
        this.expectedSessionKey = requireSessionKey(expectedSessionKey);
        this.projection = Objects.requireNonNull(projection, "Catalog publication projection is required");
        this.authoringProjection = authoringProjection;
        this.durableCache = durableCache;
        this.authoringCapabilities = authoringCapabilities == null ? List.of() : List.copyOf(authoringCapabilities);
        this.authoringRequired = false;
    }

    public void setAuthoringRequired(boolean required) {
        authoringRequired = required;
    }

    public synchronized Application apply(CatalogPublicationReceipt receipt, CatalogCachePublication publication,
                                          byte[] canonicalBytes) {
        PreparedApplication prepared = prepare(receipt, publication, canonicalBytes);
        return prepared.ready() ? commit(prepared) : Application.readOnly(prepared.diagnostic());
    }

    synchronized PreparedApplication prepare(CatalogPublicationReceipt receipt, CatalogCachePublication publication,
                                             byte[] canonicalBytes) {
        return prepare(receipt, publication, canonicalBytes, null);
    }

    synchronized PreparedApplication prepare(CatalogPublicationReceipt receipt,
                                             CatalogCachePublicationCodec.ValidatedPublication validated) {
        Objects.requireNonNull(validated, "Validated catalog publication is required");
        return prepare(receipt, validated.publication(), validated.canonicalBytes(), validated);
    }

    private synchronized PreparedApplication prepare(CatalogPublicationReceipt receipt,
                                                    CatalogCachePublication publication, byte[] canonicalBytes,
                                                    CatalogCachePublicationCodec.ValidatedPublication validated) {
        Objects.requireNonNull(receipt, "Catalog publication receipt is required");
        Objects.requireNonNull(publication, "Catalog publication is required");
        Objects.requireNonNull(canonicalBytes, "Catalog publication bytes are required");
        Application validation = validateReceipt(receipt, publication, canonicalBytes);
        if (validation != null) {
            return PreparedApplication.rejected(publication.key(), publication.revision(), validation.diagnostic());
        }
        if (authoringProjection != null && (authoringRequired
            || publication.authoringPublication() != null)) {
            return prepareWithAuthoring(publication, canonicalBytes, validated);
        }
        return prepareNodeOnly(publication, canonicalBytes, validated);
    }

    private PreparedApplication prepareNodeOnly(CatalogCachePublication publication, byte[] canonicalBytes,
                                                CatalogCachePublicationCodec.ValidatedPublication validated) {
        Optional<ReSyncCatalogPublicationProjection.Prepared> prepared = validated == null
            ? projection.prepare(publication, canonicalBytes) : projection.prepare(validated);
        if (prepared.isEmpty()) {
            return PreparedApplication.rejected(publication.key(), publication.revision(), READ_ONLY_STALE);
        }
        return PreparedApplication.ready(publication.key(), publication.revision(), prepared.orElseThrow(), null,
            authoringProjection == null ? null : authoringProjection.active().orElse(null),
            projection.acknowledgedKey().orElse(null));
    }

    private PreparedApplication prepareWithAuthoring(CatalogCachePublication publication, byte[] canonicalBytes,
                                                     CatalogCachePublicationCodec.ValidatedPublication validated) {
        CatalogAuthoringPublication authoring = publication.authoringPublication();
        byte[] authoringBytes;
        if (authoring == null) {
            if (!authoringRequired) {
                return prepareNodeOnly(publication, canonicalBytes, validated);
            }
            return PreparedApplication.rejected(publication.key(), publication.revision(),
                "CATALOG_PUBLICATION.AUTHORING_MISSING");
        } else {
            authoringBytes = authoringCodec.encodeBytes(authoring);
        }
        Optional<ReSyncCatalogPublicationProjection.Prepared> nodePrepared = validated == null
            ? projection.prepare(publication, canonicalBytes) : projection.prepare(validated);
        Optional<ReSyncCatalogAuthoringProjection.Prepared> authoringPrepared = authoringProjection.prepare(
            publication.key(), publication.revision(), authoring, authoringBytes);
        if (nodePrepared.isEmpty()) {
            return PreparedApplication.rejected(publication.key(), publication.revision(), READ_ONLY_STALE);
        }
        if (authoringPrepared.isEmpty()) {
            Optional<ReSyncCatalogAuthoringProjection.Snapshot> activeAuthoring = authoringProjection.active();
            if (activeAuthoring.isPresent() && activeAuthoring.orElseThrow().key().equals(publication.key())
                && activeAuthoring.orElseThrow().revision() == publication.revision()) {
                return PreparedApplication.rejected(publication.key(), publication.revision(),
                    "CATALOG_PUBLICATION.AUTHORING_RECONCILIATION_REQUIRED");
            }
            return PreparedApplication.rejected(publication.key(), publication.revision(),
                "CATALOG_PUBLICATION.RECEIPT_APPLICATION_REJECTED");
        }
        return PreparedApplication.ready(publication.key(), publication.revision(), nodePrepared.orElseThrow(),
            authoringPrepared.orElseThrow(), authoringPrepared.orElseThrow().previous(),
            projection.acknowledgedKey().orElse(null));
    }

    synchronized Application commit(PreparedApplication prepared) {
        return commit(prepared, snapshot -> true);
    }

    synchronized Application commit(PreparedApplication prepared,
                                    Predicate<ReSyncCatalogPublicationProjection.Snapshot> publisher) {
        Objects.requireNonNull(prepared, "Prepared catalog application is required");
        Objects.requireNonNull(publisher, "Prepared catalog publisher is required");
        if (!prepared.ready()) {
            return Application.readOnly(prepared.diagnostic());
        }
        ReSyncCatalogPublicationProjection.Prepared node = prepared.node();
        synchronized (projection) {
            if (projection.active().orElse(null) != node.previous()
                || !Objects.equals(projection.acknowledgedKey().orElse(null), prepared.previousAcknowledgedKey())) {
                return Application.readOnly(READ_ONLY_STALE);
            }
            ReSyncCatalogAuthoringProjection.Prepared authoring = prepared.authoring();
            try {
                if (authoring == null) {
                    if (authoringProjection != null
                        && authoringProjection.active().orElse(null) != prepared.previousAuthoring()) {
                        return Application.readOnly(READ_ONLY_STALE);
                    }
                    if (!projection.commitPrepared(node)) {
                        return Application.readOnly(READ_ONLY_STALE);
                    }
                    if (!publisher.test(node.candidate())) {
                        projection.restorePrepared(node.previous(), prepared.previousAcknowledgedKey());
                        return Application.readOnly(READ_ONLY_STALE);
                    }
                } else {
                    synchronized (authoringProjection) {
                        if (authoringProjection.active().orElse(null) != prepared.previousAuthoring()
                            || !projection.commitPrepared(node)) {
                            return Application.readOnly(READ_ONLY_STALE);
                        }
                        if (!authoringProjection.commitPrepared(authoring)) {
                            projection.restorePrepared(node.previous(), prepared.previousAcknowledgedKey());
                            return Application.readOnly(READ_ONLY_STALE);
                        }
                        if (!publisher.test(node.candidate())) {
                            authoringProjection.restorePrepared(prepared.previousAuthoring());
                            projection.restorePrepared(node.previous(), prepared.previousAcknowledgedKey());
                            return Application.readOnly(READ_ONLY_STALE);
                        }
                    }
                }
            } catch (RuntimeException | Error exception) {
                if (authoringProjection != null) {
                    synchronized (authoringProjection) {
                        authoringProjection.restorePrepared(prepared.previousAuthoring());
                    }
                }
                projection.restorePrepared(node.previous(), prepared.previousAcknowledgedKey());
                return Application.readOnly(READ_ONLY_STALE);
            }
        }
        persist(node, prepared.authoring() == null ? null : prepared.authoring().candidate());
        return Application.applied(node.candidate());
    }

    private Application validateReceipt(CatalogPublicationReceipt receipt, CatalogCachePublication publication,
                                        byte[] canonicalBytes) {
        if (!expectedServerId.equals(publication.serverId()) || !expectedServerId.equals(receipt.publicationKey().serverId())) {
            return Application.readOnly(REJECTED_SERVER);
        }
        if (!expectedSessionKey.equals(receipt.sessionKey())) {
            return Application.readOnly("CATALOG_PUBLICATION.RECEIPT_SESSION_MISMATCH");
        }
        if (!receipt.publicationKey().equals(publication.key())) {
            return Application.readOnly(READ_ONLY_KEY);
        }
        if (receipt.revision() != publication.revision()) {
            return Application.readOnly(READ_ONLY_REVISION);
        }
        if (!receipt.serverDispatched()) {
            return Application.readOnly(READ_ONLY_DISPATCH);
        }
        if (!receipt.clientReceived()) {
            return Application.readOnly(READ_ONLY_RECEIPT);
        }
        if (receipt.cacheApplication() == CatalogPublicationReceipt.CacheApplicationState.REJECTED) {
            return Application.readOnly("CATALOG_PUBLICATION.RECEIPT_APPLICATION_REJECTED");
        }
        if (canonicalBytes.length == 0) {
            return Application.readOnly(REJECTED_CANONICAL);
        }
        return null;
    }

    private void persist(ReSyncCatalogPublicationProjection.Prepared prepared,
                         ReSyncCatalogAuthoringProjection.Snapshot authoring) {
        if (durableCache == null) {
            return;
        }
        long sequence = ++cachePersistenceSequence;
        ReSyncCatalogPublicationProjection.Snapshot snapshot = prepared.candidate();
        CatalogCachePublication publication = snapshot.publication();
        cachePersistence = CachePersistence.pending(sequence, publication.key(), publication.revision());
        Async<CachePersistence> reportedCompletion = Async.pending();
        cachePersistenceCompletion = reportedCompletion;
        Async<ReSyncCatalogPublicationCache.Persistence> completion = durableCache.storeAsync(
            expectedServerId, prepared, authoring, authoring == null ? List.of() : authoringCapabilities);
        completion.whenComplete((result, failure) -> reportedCompletion.complete(completePersistence(sequence,
            publication.key(), publication.revision(), result, failure)));
    }

    private synchronized CachePersistence completePersistence(long sequence, CatalogCacheKey key, long revision,
                                                              ReSyncCatalogPublicationCache.Persistence result,
                                                              Throwable failure) {
        CachePersistence completed;
        if (failure != null || result == null) {
            completed = CachePersistence.rejected(sequence, key, revision,
                "CATALOG_PUBLICATION.CACHE_STORE_FAILED");
        } else {
            completed = result.stored()
                ? CachePersistence.stored(sequence, result.key(), result.revision())
                : CachePersistence.rejected(sequence, result.key(), result.revision(), result.diagnostic());
        }
        if (sequence == cachePersistenceSequence) {
            cachePersistence = completed;
        }
        return completed;
    }

    public CachePersistence cachePersistence() {
        return cachePersistence;
    }

    Async<CachePersistence> cachePersistenceCompletion() {
        return cachePersistenceCompletion;
    }

    public Application apply(CatalogPublicationReceipt receipt, byte[] canonicalBytes,
                             CatalogCachePublicationCodec codec) {
        Objects.requireNonNull(codec, "Catalog publication codec is required");
        Objects.requireNonNull(canonicalBytes, "Catalog publication bytes are required");
        try {
            return apply(receipt, codec.decodeBytes(canonicalBytes), canonicalBytes);
        } catch (RuntimeException exception) {
            return Application.readOnly("CATALOG_PUBLICATION.RECEIPT_PAYLOAD_INVALID");
        }
    }

    record PreparedApplication(CatalogCacheKey key, long revision,
                               ReSyncCatalogPublicationProjection.Prepared node,
                               ReSyncCatalogAuthoringProjection.Prepared authoring,
                               ReSyncCatalogAuthoringProjection.Snapshot previousAuthoring,
                               CatalogCacheKey previousAcknowledgedKey, String diagnostic) {
        PreparedApplication {
            key = Objects.requireNonNull(key, "Catalog cache key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Catalog publication revision cannot be negative");
            }
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (node == null && diagnostic.isBlank()) {
                throw new IllegalArgumentException("Rejected catalog application must carry a diagnostic");
            }
            if (node != null && !diagnostic.isEmpty()) {
                throw new IllegalArgumentException("Prepared catalog application cannot carry a diagnostic");
            }
            if (node != null && (!key.equals(node.candidate().publication().key())
                || revision != node.candidate().publication().revision())) {
                throw new IllegalArgumentException("Prepared catalog application identity does not match");
            }
            if (authoring != null && (!key.equals(authoring.candidate().key())
                || revision != authoring.candidate().revision())) {
                throw new IllegalArgumentException("Prepared catalog authoring identity does not match");
            }
            if (authoring != null && authoring.previous() != previousAuthoring) {
                throw new IllegalArgumentException("Prepared catalog authoring predecessor does not match");
            }
        }

        static PreparedApplication ready(CatalogCacheKey key, long revision,
                                         ReSyncCatalogPublicationProjection.Prepared node,
                                         ReSyncCatalogAuthoringProjection.Prepared authoring,
                                         ReSyncCatalogAuthoringProjection.Snapshot previousAuthoring,
                                         CatalogCacheKey previousAcknowledgedKey) {
            return new PreparedApplication(key, revision, Objects.requireNonNull(node,
                "Prepared catalog projection is required"), authoring, previousAuthoring,
                previousAcknowledgedKey, "");
        }

        static PreparedApplication rejected(CatalogCacheKey key, long revision, String diagnostic) {
            return new PreparedApplication(key, revision, null, null, null, null, diagnostic);
        }

        boolean ready() {
            return node != null;
        }
    }

    public record Application(Status status, String diagnostic,
                              Optional<ReSyncCatalogPublicationProjection.Snapshot> snapshot) {
        public Application {
            status = Objects.requireNonNull(status, "Application status is required");
            diagnostic = diagnostic == null ? "" : diagnostic;
            snapshot = snapshot == null ? Optional.empty() : snapshot;
            if (status == Status.APPLIED && snapshot.isEmpty()) {
                throw new IllegalArgumentException("Applied catalog publication must carry a snapshot");
            }
            if (status != Status.APPLIED && diagnostic.isBlank()) {
                throw new IllegalArgumentException("Rejected catalog publication must carry a diagnostic");
            }
        }

        public static Application applied(ReSyncCatalogPublicationProjection.Snapshot snapshot) {
            return new Application(Status.APPLIED, "", Optional.of(Objects.requireNonNull(snapshot, "Snapshot is required")));
        }

        public static Application readOnly(String diagnostic) {
            return new Application(Status.READ_ONLY, diagnostic, Optional.empty());
        }

        public boolean applied() {
            return status == Status.APPLIED;
        }

        public boolean readOnly() {
            return status == Status.READ_ONLY;
        }
    }

    public enum Status {
        APPLIED,
        READ_ONLY
    }

    public record CachePersistence(long sequence, CatalogCacheKey key, long revision, PersistenceStatus status,
                                   String diagnostic) {
        public CachePersistence {
            if (sequence < 0) {
                throw new IllegalArgumentException("Catalog cache persistence sequence cannot be negative");
            }
            status = Objects.requireNonNull(status, "Catalog cache persistence status is required");
            diagnostic = diagnostic == null ? "" : diagnostic;
            if (status == PersistenceStatus.IDLE && (sequence != 0 || key != null || revision != 0
                || !diagnostic.isEmpty())) {
                throw new IllegalArgumentException("Idle catalog cache persistence state is invalid");
            }
            if (status != PersistenceStatus.IDLE && (sequence == 0 || key == null || revision < 0)) {
                throw new IllegalArgumentException("Active catalog cache persistence state is invalid");
            }
            if ((status == PersistenceStatus.IDLE || status == PersistenceStatus.PENDING
                || status == PersistenceStatus.STORED) && !diagnostic.isEmpty()) {
                throw new IllegalArgumentException("Successful catalog cache persistence state cannot carry a diagnostic");
            }
            if (status == PersistenceStatus.REJECTED && diagnostic.isBlank()) {
                throw new IllegalArgumentException("Rejected catalog cache persistence state must carry a diagnostic");
            }
        }

        public static CachePersistence idle() {
            return new CachePersistence(0, null, 0, PersistenceStatus.IDLE, "");
        }

        public static CachePersistence pending(long sequence, CatalogCacheKey key, long revision) {
            return new CachePersistence(sequence, key, revision, PersistenceStatus.PENDING, "");
        }

        public static CachePersistence stored(long sequence, CatalogCacheKey key, long revision) {
            return new CachePersistence(sequence, key, revision, PersistenceStatus.STORED, "");
        }

        public static CachePersistence rejected(long sequence, CatalogCacheKey key, long revision,
                                                String diagnostic) {
            return new CachePersistence(sequence, key, revision, PersistenceStatus.REJECTED, diagnostic);
        }
    }

    public enum PersistenceStatus {
        IDLE,
        PENDING,
        STORED,
        REJECTED
    }

    private static String requireSessionKey(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Expected receipt session key must be non-blank and canonical");
        }
        return value;
    }
}
