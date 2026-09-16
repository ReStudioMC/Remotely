package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public final class ReSyncCatalogAuthoringProjection {
    public static final String NON_CANONICAL_SERVER_ID = "CATALOG_AUTHORING_SERVER_ID_NON_CANONICAL";
    public static final String BINDING_MISMATCH = "CATALOG_AUTHORING_BINDING_MISMATCH";
    public static final String NON_CANONICAL = "CATALOG_AUTHORING_NON_CANONICAL";
    public static final String PROJECTION_VERSION_MISMATCH = "CATALOG_AUTHORING_PROJECTION_VERSION_MISMATCH";

    private static final CatalogProjectionVersion PROJECTION_VERSION = CatalogProjectionVersion.current();
    private static final CatalogAuthoringPublicationCodec CODEC = new CatalogAuthoringPublicationCodec();

    private final ServerId expectedServerId;
    private final String identityDiagnostic;
    private final BrowserSafeState.ReferenceValue<Snapshot> active = new BrowserSafeState.ReferenceValue<>();

    public ReSyncCatalogAuthoringProjection(ServerId expectedServerId) {
        this(Objects.requireNonNull(expectedServerId, "Expected server ID is required"), null);
    }

    private ReSyncCatalogAuthoringProjection(ServerId expectedServerId, String identityDiagnostic) {
        this.expectedServerId = expectedServerId;
        this.identityDiagnostic = identityDiagnostic;
    }

    public static ReSyncCatalogAuthoringProjection forConfiguredServerId(String configuredServerId) {
        try {
            if (configuredServerId == null || configuredServerId.isBlank()) {
                throw new IllegalArgumentException("Configured server ID is blank");
            }
            ServerId expectedServerId = ServerId.parseCanonicalText(configuredServerId);
            if (!expectedServerId.canonicalText().equals(configuredServerId)) {
                throw new IllegalArgumentException("Configured server ID is not canonical");
            }
            return new ReSyncCatalogAuthoringProjection(expectedServerId, null);
        } catch (RuntimeException exception) {
            return new ReSyncCatalogAuthoringProjection(null, NON_CANONICAL_SERVER_ID);
        }
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

    public synchronized Optional<CatalogAuthoringPublication> activePublication() {
        return active().map(Snapshot::publication);
    }

    public synchronized Optional<CatalogAuthoringPublication> activeAuthoringPublication() {
        return activePublication();
    }

    public synchronized Optional<ContentHash> activeChecksum() {
        return active().map(Snapshot::checksum);
    }

    public synchronized Optional<ContentHash> activeAuthoringPublicationChecksum() {
        return activeChecksum();
    }

    public synchronized Optional<Prepared> prepare(CatalogCacheKey key, long revision,
                                                    CatalogAuthoringPublication publication,
                                                    byte[] canonicalBytes) {
        Snapshot previous = active.get();
        if (previous != null && key != null && key.equals(previous.key()) && revision == previous.revision()) {
            return previous.canonicalBytesEqual(canonicalBytes) && previous.publication().equals(publication)
                ? Optional.of(new Prepared(previous, previous)) : Optional.empty();
        }
        Snapshot candidate = validate(key, revision, publication, canonicalBytes);
        if (candidate == null) {
            return Optional.empty();
        }
        if (previous != null) {
            if (key.equals(previous.key())) {
                if (revision < previous.revision()) {
                    return Optional.empty();
                }
                if (revision == previous.revision()) {
                    if (sameSnapshot(candidate, previous)) {
                        return Optional.of(new Prepared(previous, previous));
                    }
                    return Optional.empty();
                }
            } else if (!sameAuthority(previous.key(), key)
                || key.catalogGeneration() <= previous.key().catalogGeneration()) {
                return Optional.empty();
            }
        }
        return Optional.of(new Prepared(previous, candidate));
    }

    public synchronized boolean apply(CatalogCacheKey key, long revision,
                                       CatalogAuthoringPublication publication,
                                       byte[] canonicalBytes) {
        Optional<Prepared> prepared = prepare(key, revision, publication, canonicalBytes);
        if (prepared.isEmpty()) {
            return false;
        }
        return commit(prepared.orElseThrow());
    }

    public synchronized boolean commit(Prepared prepared) {
        Objects.requireNonNull(prepared, "Prepared authoring projection is required");
        if (active.get() != prepared.previous()) {
            return false;
        }
        Snapshot candidate = prepared.candidate();
        if (candidate == prepared.previous()) {
            return true;
        }
        if (validate(candidate.key(), candidate.revision(), candidate.publication(), candidate.canonicalBytes()) == null
            || !acceptsTransition(prepared.previous(), candidate)) {
            return false;
        }
        active.set(candidate);
        return true;
    }

    synchronized boolean commitPrepared(Prepared prepared) {
        Objects.requireNonNull(prepared, "Prepared authoring projection is required");
        if (active.get() != prepared.previous()) {
            return false;
        }
        active.set(prepared.candidate());
        return true;
    }

    synchronized void restorePrepared(Snapshot snapshot) {
        active.set(snapshot);
    }

    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null) {
            active.set(null);
            return;
        }
        if (validate(snapshot.key(), snapshot.revision(), snapshot.publication(), snapshot.canonicalBytes()) == null) {
            throw new IllegalArgumentException("Catalog authoring snapshot is invalid");
        }
        active.set(snapshot);
    }

    public synchronized void clear() {
        active.set(null);
    }

    public static ContentHash checksum(byte[] canonicalBytes) {
        Objects.requireNonNull(canonicalBytes, "Catalog authoring bytes are required");
        CatalogAuthoringPublication publication = CODEC.decodeBytes(canonicalBytes);
        if (!Arrays.equals(canonicalBytes, CODEC.encodeBytes(publication))) {
            throw new IllegalArgumentException("Catalog authoring bytes are not canonical");
        }
        return CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
    }

    private Snapshot validate(CatalogCacheKey key, long revision,
                              CatalogAuthoringPublication publication, byte[] canonicalBytes) {
        if (key == null || publication == null || canonicalBytes == null || canonicalBytes.length == 0
            || expectedServerId == null || !expectedServerId.equals(key.serverId())
            || !key.hasCatalogBinding() || revision < 0L
            || !PROJECTION_VERSION.equals(key.projectionVersion())
            || !PROJECTION_VERSION.equals(publication.projectionVersion())
            || !CatalogAuthoringPublication.supportsProjectionVersion(publication.projectionVersion())) {
            return null;
        }
        CatalogBinding binding = publication.binding();
        if (binding == null || !binding.equals(key.catalogBinding())) {
            return null;
        }
        try {
            CatalogAuthoringPublication decoded = CODEC.decodeBytes(canonicalBytes);
            if (!publication.equals(decoded) || !Arrays.equals(canonicalBytes, CODEC.encodeBytes(decoded))) {
                return null;
            }
            return new Snapshot(key, revision, publication, canonicalBytes, checksum(canonicalBytes));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean sameAuthority(CatalogCacheKey first, CatalogCacheKey second) {
        return first.serverId().equals(second.serverId())
            && first.projectionVersion().equals(second.projectionVersion());
    }

    private static boolean sameSnapshot(Snapshot first, Snapshot second) {
        return first.key().equals(second.key())
            && first.revision() == second.revision()
            && first.publication().equals(second.publication())
            && first.checksum().equals(second.checksum())
            && Arrays.equals(first.canonicalBytes(), second.canonicalBytes());
    }

    private static boolean acceptsTransition(Snapshot previous, Snapshot candidate) {
        if (previous == null) {
            return true;
        }
        if (candidate.key().equals(previous.key())) {
            return candidate.revision() > previous.revision()
                || candidate.revision() == previous.revision() && sameSnapshot(candidate, previous);
        }
        return sameAuthority(previous.key(), candidate.key())
            && candidate.key().catalogGeneration() > previous.key().catalogGeneration();
    }

    public record Prepared(Snapshot previous, Snapshot candidate) {
        public Prepared {
            Objects.requireNonNull(candidate, "Prepared authoring candidate is required");
        }
    }

    public record Snapshot(CatalogCacheKey key, long revision, CatalogAuthoringPublication publication,
                           byte[] canonicalBytes, ContentHash checksum) {
        public Snapshot {
            key = Objects.requireNonNull(key, "Catalog authoring cache key is required");
            publication = Objects.requireNonNull(publication, "Catalog authoring publication is required");
            canonicalBytes = Objects.requireNonNull(canonicalBytes, "Catalog authoring bytes are required").clone();
            if (canonicalBytes.length == 0) {
                throw new IllegalArgumentException("Catalog authoring bytes cannot be empty");
            }
            checksum = Objects.requireNonNull(checksum, "Catalog authoring checksum is required");
            if (!checksum.equals(ReSyncCatalogAuthoringProjection.checksum(canonicalBytes))) {
                throw new IllegalArgumentException("Catalog authoring checksum does not match bytes");
            }
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
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
            return key.equals(other.key)
                && revision == other.revision
                && publication.equals(other.publication)
                && Arrays.equals(canonicalBytes, other.canonicalBytes)
                && checksum.equals(other.checksum);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(key, revision, publication, checksum);
            return 31 * result + Arrays.hashCode(canonicalBytes);
        }
    }
}
