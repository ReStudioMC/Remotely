package redxax.oxy.remotely.metadata;

import restudio.rescreen.platform.Async;
import restudio.resync.metadata.MetadataArtifactFamily;
import restudio.resync.metadata.MetadataBundleDescriptor;
import restudio.resync.metadata.MetadataBundleId;
import restudio.resync.metadata.MetadataCoordinate;
import restudio.resync.metadata.MetadataManifest;
import restudio.resync.metadata.MetadataManifestCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MetadataRepository {
    public static final long MAX_BUNDLE_BYTES = 32L * 1024L * 1024L;
    private final MetadataTransport transport;
    private final MetadataStorage storage;
    private final Map<MetadataArtifactFamily, MetadataBundleCodec<?>> codecs;
    private final MetadataManifestCodec manifestCodec = new MetadataManifestCodec();
    private final Map<MetadataBundleId, Bundle> admitted = new LinkedHashMap<>();
    private final Map<MetadataBundleId, Async<Bundle>> admissions = new LinkedHashMap<>();
    private final Map<Request, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<Request, Async<Snapshot>> refreshes = new LinkedHashMap<>();
    private long generation;
    private Diagnostic diagnostic = Diagnostic.ready();

    public MetadataRepository(MetadataTransport transport, MetadataStorage storage) {
        this(transport, storage, MetadataCodecs.standard());
    }

    public MetadataRepository(MetadataTransport transport, MetadataStorage storage,
                              Map<MetadataArtifactFamily, MetadataBundleCodec<?>> codecs) {
        this.transport = Objects.requireNonNull(transport, "Metadata transport is required");
        this.storage = storage == null ? MetadataStorage.none() : storage;
        Objects.requireNonNull(codecs, "Metadata codecs are required");
        LinkedHashMap<MetadataArtifactFamily, MetadataBundleCodec<?>> checked = new LinkedHashMap<>();
        codecs.forEach((family, codec) -> {
            if (family == null || codec == null || !family.equals(codec.artifactFamily())) {
                throw new IllegalArgumentException("Metadata codec family is invalid");
            }
            checked.put(family, codec);
        });
        this.codecs = Map.copyOf(checked);
    }

    public synchronized Snapshot snapshot(Request request) {
        return snapshots.get(request);
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized Diagnostic diagnostic() {
        return diagnostic;
    }

    public Async<Snapshot> refresh(Request request) {
        Objects.requireNonNull(request, "Metadata request is required");
        Async<Snapshot> result;
        Snapshot resident;
        synchronized (this) {
            result = refreshes.get(request);
            if (result != null) return result;
            result = Async.pending();
            refreshes.put(request, result);
            resident = snapshots.get(request);
            diagnostic = new Diagnostic(State.REFRESHING, "", generation, resident == null ? "" : resident.stamp());
        }
        Async<Snapshot> owner = result;
        Async<MetadataTransport.ManifestResponse> resolution;
        try {
            resolution = transport.resolve(request, resident == null ? "" : resident.etag());
        } catch (Throwable failure) {
            settleFailure(request, owner, failure);
            return result;
        }
        resolution.whenComplete((response, failure) -> {
            if (failure != null) {
                settleFailure(request, owner, failure);
                return;
            }
            if (response.status() == MetadataTransport.Status.NOT_MODIFIED) {
                if (resident == null) settleFailure(request, owner, new IllegalStateException("Metadata manifest was not available"));
                else settleSuccess(request, owner, resident);
                return;
            }
            MetadataManifest manifest;
            try {
                byte[] bytes = response.bytes();
                manifest = manifestCodec.decodeBytes(bytes);
                if (!Arrays.equals(bytes, manifestCodec.encodeBytes(manifest))) {
                    throw new IllegalArgumentException("Metadata manifest is not canonically encoded");
                }
                validateManifest(request, manifest);
            } catch (Throwable invalid) {
                settleFailure(request, owner, invalid);
                return;
            }
            loadBundles(manifest.bundles(), 0, new LinkedHashMap<>()).whenComplete((bundles, bundleFailure) -> {
                if (bundleFailure != null) {
                    settleFailure(request, owner, bundleFailure);
                    return;
                }
                String stamp = stamp(manifest);
                Snapshot next;
                synchronized (MetadataRepository.this) {
                    Snapshot current = snapshots.get(request);
                    if (current != null && current.stamp().equals(stamp)) {
                        next = new Snapshot(current.generation(), stamp, response.etag(), manifest, bundles);
                    } else {
                        generation++;
                        next = new Snapshot(generation, stamp, response.etag(), manifest, bundles);
                    }
                    snapshots.put(request, next);
                }
                settleSuccess(request, owner, next);
            });
        });
        return result;
    }

    private Async<Map<MetadataArtifactFamily, Bundle>> loadBundles(List<MetadataBundleDescriptor> descriptors, int index,
                                                                    LinkedHashMap<MetadataArtifactFamily, Bundle> result) {
        if (index >= descriptors.size()) return Async.completed(Map.copyOf(result));
        MetadataBundleDescriptor descriptor = descriptors.get(index);
        Async<Map<MetadataArtifactFamily, Bundle>> completion = Async.pending();
        admit(descriptor).whenComplete((bundle, failure) -> {
            if (failure != null) {
                completion.fail(failure);
                return;
            }
            result.put(descriptor.artifactFamily(), bundle);
            loadBundles(descriptors, index + 1, result).whenComplete((bundles, remainingFailure) -> {
                if (remainingFailure != null) completion.fail(remainingFailure);
                else completion.complete(bundles);
            });
        });
        return completion;
    }

    private Async<Bundle> admit(MetadataBundleDescriptor descriptor) {
        synchronized (this) {
            Bundle resident = admitted.get(descriptor.bundleId());
            if (resident != null) return Async.completed(requireDescriptor(resident, descriptor));
            Async<Bundle> existing = admissions.get(descriptor.bundleId());
            if (existing != null) return existing.thenApply(bundle -> requireDescriptor(bundle, descriptor));
            Async<Bundle> admission = Async.pending();
            admissions.put(descriptor.bundleId(), admission);
            Async<byte[]> read;
            try {
                read = storage.read(descriptor.bundleId());
            } catch (Throwable failure) {
                read = Async.failed(failure);
            }
            read.whenComplete((stored, storageFailure) -> {
                if (storageFailure == null && stored != null) {
                    try {
                        settleAdmission(descriptor, admission, verify(descriptor, stored), false);
                        return;
                    } catch (Throwable ignored) {
                        noteWarning("Cached metadata failed verification and was replaced");
                    }
                }
                Async<byte[]> download;
                try {
                    download = transport.bundle(descriptor.bundleId());
                } catch (Throwable failure) {
                    download = Async.failed(failure);
                }
                download.whenComplete((downloaded, downloadFailure) -> {
                    if (downloadFailure != null) {
                        failAdmission(descriptor.bundleId(), admission, downloadFailure);
                        return;
                    }
                    Bundle bundle;
                    try {
                        bundle = verify(descriptor, downloaded);
                    } catch (Throwable invalid) {
                        failAdmission(descriptor.bundleId(), admission, invalid);
                        return;
                    }
                    Async<Void> write;
                    try {
                        write = storage.write(descriptor.bundleId(), bundle.bytes());
                    } catch (Throwable failure) {
                        write = Async.failed(failure);
                    }
                    write.whenComplete((unused, writeFailure) -> settleAdmission(descriptor, admission, bundle, writeFailure != null));
                });
            });
            return admission;
        }
    }

    private Bundle verify(MetadataBundleDescriptor descriptor, byte[] bytes) {
        if (bytes == null || bytes.length != descriptor.byteSize()) {
            throw new IllegalArgumentException("Metadata bundle size does not match its descriptor");
        }
        if (bytes.length > MAX_BUNDLE_BYTES) {
            throw new IllegalArgumentException("Metadata bundle exceeds client capacity");
        }
        if (!descriptor.bundleId().verifiesCanonicalBytes(bytes)) {
            throw new IllegalArgumentException("Metadata bundle digest does not match its descriptor");
        }
        MetadataBundleCodec<?> codec = codecs.get(descriptor.artifactFamily());
        if (codec == null || codec.formatVersion() != descriptor.formatVersion()) {
            throw new IllegalArgumentException("Metadata bundle format is unsupported");
        }
        return decode(descriptor, bytes, codec);
    }

    private static <T> Bundle decode(MetadataBundleDescriptor descriptor, byte[] bytes, MetadataBundleCodec<T> codec) {
        T value = codec.decode(bytes);
        byte[] canonical = codec.encode(value);
        if (!Arrays.equals(bytes, canonical)) {
            throw new IllegalArgumentException("Metadata bundle is not canonically encoded");
        }
        return new Bundle(descriptor, canonical, value);
    }

    private synchronized void settleAdmission(MetadataBundleDescriptor descriptor, Async<Bundle> admission, Bundle bundle,
                                              boolean storageWarning) {
        if (admissions.get(descriptor.bundleId()) != admission) return;
        admissions.remove(descriptor.bundleId());
        Bundle existing = admitted.putIfAbsent(descriptor.bundleId(), bundle);
        if (storageWarning) noteWarning("Verified metadata could not be cached");
        admission.complete(existing == null ? bundle : requireDescriptor(existing, descriptor));
    }

    private synchronized void failAdmission(MetadataBundleId id, Async<Bundle> admission, Throwable failure) {
        if (admissions.get(id) != admission) return;
        admissions.remove(id);
        admission.fail(failure);
    }

    private synchronized void settleSuccess(Request request, Async<Snapshot> owner, Snapshot snapshot) {
        if (refreshes.get(request) != owner) return;
        refreshes.remove(request);
        String message = diagnostic.state() == State.READY ? diagnostic.message() : "";
        diagnostic = new Diagnostic(State.READY, message, generation, snapshot.stamp());
        owner.complete(snapshot);
    }

    private synchronized void noteWarning(String message) {
        diagnostic = new Diagnostic(State.READY, message, generation, diagnostic.stamp());
    }

    private synchronized void settleFailure(Request request, Async<Snapshot> owner, Throwable failure) {
        if (refreshes.get(request) != owner) return;
        refreshes.remove(request);
        Snapshot fallback = snapshots.get(request);
        String message = failure.getMessage() == null || failure.getMessage().isBlank()
            ? "Metadata refresh failed" : failure.getMessage();
        diagnostic = new Diagnostic(fallback == null ? State.UNAVAILABLE : State.STALE, message, generation,
            fallback == null ? "" : fallback.stamp());
        if (fallback == null) owner.fail(failure);
        else owner.complete(fallback);
    }

    private void validateManifest(Request request, MetadataManifest manifest) {
        if (manifest.bundles().size() != request.artifactFamilies().size()) {
            throw new IllegalArgumentException("Metadata manifest does not resolve every requested artifact family");
        }
        Set<MetadataArtifactFamily> resolved = new LinkedHashSet<>();
        for (MetadataBundleDescriptor descriptor : manifest.bundles()) resolved.add(descriptor.artifactFamily());
        if (!resolved.equals(request.artifactFamilies())) {
            throw new IllegalArgumentException("Metadata manifest contains an unexpected artifact family");
        }
        for (MetadataBundleDescriptor descriptor : manifest.bundles()) {
            if (descriptor.byteSize() > MAX_BUNDLE_BYTES || !descriptor.selector().matches(request.coordinate(), request.capabilities())) {
                throw new IllegalArgumentException("Metadata manifest contains an incompatible bundle");
            }
        }
    }

    private static Bundle requireDescriptor(Bundle bundle, MetadataBundleDescriptor descriptor) {
        if (!bundle.descriptor().artifactFamily().equals(descriptor.artifactFamily())
            || bundle.descriptor().formatVersion() != descriptor.formatVersion()
            || bundle.descriptor().byteSize() != descriptor.byteSize()) {
            throw new IllegalArgumentException("Metadata digest was reused with a conflicting descriptor");
        }
        return bundle.descriptor().equals(descriptor) ? bundle : new Bundle(descriptor, bundle.bytes, bundle.value, true);
    }

    private static String stamp(MetadataManifest manifest) {
        List<String> parts = new ArrayList<>();
        parts.add(manifest.resolverRevision());
        manifest.bundles().forEach(bundle -> parts.add(bundle.artifactFamily().canonicalText() + ":" + bundle.bundleId().canonicalText()));
        return String.join("|", parts);
    }

    public record Request(Set<MetadataArtifactFamily> artifactFamilies, MetadataCoordinate coordinate, Set<String> capabilities) {
        public Request {
            Objects.requireNonNull(artifactFamilies, "Metadata artifact families are required");
            if (artifactFamilies.isEmpty()) throw new IllegalArgumentException("At least one metadata artifact family is required");
            artifactFamilies = Set.copyOf(artifactFamilies);
            coordinate = Objects.requireNonNull(coordinate, "Metadata coordinate is required");
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    public static final class Bundle {
        private final MetadataBundleDescriptor descriptor;
        private final byte[] bytes;
        private final Object value;

        private Bundle(MetadataBundleDescriptor descriptor, byte[] bytes, Object value) {
            this(descriptor, bytes, value, false);
        }

        private Bundle(MetadataBundleDescriptor descriptor, byte[] bytes, Object value, boolean trusted) {
            this.descriptor = descriptor;
            this.bytes = trusted ? bytes : bytes.clone();
            this.value = value;
        }

        public MetadataBundleDescriptor descriptor() {
            return descriptor;
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public Object value() {
            return value;
        }

    }

    public record Snapshot(long generation, String stamp, String etag, MetadataManifest manifest,
                           Map<MetadataArtifactFamily, Bundle> bundles) {
        public Snapshot {
            if (generation < 1) throw new IllegalArgumentException("Metadata generation must be positive");
            stamp = Objects.requireNonNull(stamp, "Metadata stamp is required");
            etag = etag == null ? "" : etag;
            manifest = Objects.requireNonNull(manifest, "Metadata manifest is required");
            bundles = Map.copyOf(bundles);
        }

        public Bundle bundle(MetadataArtifactFamily family) {
            return bundles.get(family);
        }
    }

    public record Diagnostic(State state, String message, long generation, String stamp) {
        public Diagnostic {
            state = Objects.requireNonNull(state, "Metadata diagnostic state is required");
            message = message == null ? "" : message;
            stamp = stamp == null ? "" : stamp;
        }

        private static Diagnostic ready() {
            return new Diagnostic(State.READY, "", 0, "");
        }
    }

    public enum State {
        READY,
        REFRESHING,
        STALE,
        UNAVAILABLE
    }
}
