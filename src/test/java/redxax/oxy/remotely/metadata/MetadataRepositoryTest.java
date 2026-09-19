package redxax.oxy.remotely.metadata;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;
import restudio.resync.metadata.MetadataArtifactFamily;
import restudio.resync.metadata.MetadataBundleDescriptor;
import restudio.resync.metadata.MetadataBundleId;
import restudio.resync.metadata.MetadataCoordinate;
import restudio.resync.metadata.MetadataManifest;
import restudio.resync.metadata.MetadataManifestCodec;
import restudio.resync.metadata.MetadataSelector;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MetadataRepositoryTest {
    private static final MetadataArtifactFamily FAMILY = MetadataArtifactFamily.of("test_catalog");
    private static final MetadataRepository.Request REQUEST = new MetadataRepository.Request(Set.of(FAMILY),
        new MetadataCoordinate("java", "26.1", null, null, null, null, null), Set.of());
    private static final MetadataManifestCodec MANIFEST_CODEC = new MetadataManifestCodec();

    @Test
    void admitsEachDigestOnceAndReturnsMutationResistantResidentBytes() {
        byte[] bytes = bytes("one");
        FakeTransport transport = new FakeTransport(manifest("revision-1", descriptor(bytes)), bytes);
        MetadataRepository repository = repository(transport, MetadataStorage.none());

        MetadataRepository.Snapshot first = repository.refresh(REQUEST).join();
        byte[] exposed = first.bundle(FAMILY).bytes();
        exposed[0] = 'x';
        MetadataRepository.Snapshot second = repository.refresh(REQUEST).join();

        assertEquals(1, transport.bundleReads);
        assertEquals(1, repository.generation());
        assertArrayEquals(bytes, second.bundle(FAMILY).bytes());
        assertNotSame(second.bundle(FAMILY).bytes(), second.bundle(FAMILY).bytes());
        assertEquals("one", second.bundle(FAMILY).value());
    }

    @Test
    void rejectsWrongDigestAndRetainsLastVerifiedSnapshot() {
        byte[] firstBytes = bytes("one");
        FakeTransport transport = new FakeTransport(manifest("revision-1", descriptor(firstBytes)), firstBytes);
        MetadataRepository repository = repository(transport, MetadataStorage.none());
        MetadataRepository.Snapshot first = repository.refresh(REQUEST).join();
        byte[] expected = bytes("two");
        transport.manifest = manifest("revision-2", descriptor(expected));
        transport.bundle = bytes("tampered");

        MetadataRepository.Snapshot fallback = repository.refresh(REQUEST).join();

        assertSame(first, fallback);
        assertEquals(1, fallback.generation());
        assertEquals(MetadataRepository.State.STALE, repository.diagnostic().state());
        assertEquals("one", fallback.bundle(FAMILY).value());
    }

    @Test
    void loadsVerifiedDurableBytesBeforeUsingTheNetwork() {
        byte[] bytes = bytes("stored");
        MetadataBundleDescriptor descriptor = descriptor(bytes);
        FakeTransport transport = new FakeTransport(manifest("revision-1", descriptor), bytes("network"));
        MemoryStorage storage = new MemoryStorage();
        storage.values.put(descriptor.bundleId(), bytes.clone());

        MetadataRepository.Snapshot snapshot = repository(transport, storage).refresh(REQUEST).join();

        assertEquals(0, transport.bundleReads);
        assertEquals("stored", snapshot.bundle(FAMILY).value());
    }

    @Test
    void coalescesConcurrentRefreshAndAdvancesGenerationOnlyForANewStamp() {
        byte[] firstBytes = bytes("one");
        FakeTransport transport = new FakeTransport(manifest("revision-1", descriptor(firstBytes)), firstBytes);
        Async<MetadataTransport.ManifestResponse> pending = Async.pending();
        transport.pendingManifest = pending;
        MetadataRepository repository = repository(transport, MetadataStorage.none());

        Async<MetadataRepository.Snapshot> first = repository.refresh(REQUEST);
        Async<MetadataRepository.Snapshot> concurrent = repository.refresh(REQUEST);
        assertSame(first, concurrent);
        pending.complete(new MetadataTransport.ManifestResponse(MetadataTransport.Status.RESOLVED, "etag-1", transport.manifest));
        assertEquals(1, first.join().generation());

        byte[] secondBytes = bytes("two");
        transport.manifest = manifest("revision-2", descriptor(secondBytes));
        transport.bundle = secondBytes;
        MetadataRepository.Snapshot second = repository.refresh(REQUEST).join();

        assertEquals(2, second.generation());
        assertEquals("revision-2|test_catalog:" + descriptor(secondBytes).bundleId(), second.stamp());
    }

    private static MetadataRepository repository(FakeTransport transport, MetadataStorage storage) {
        MetadataBundleCodec<String> codec = new MetadataBundleCodec<>() {
            @Override public MetadataArtifactFamily artifactFamily() { return FAMILY; }
            @Override public int formatVersion() { return 1; }
            @Override public String decode(byte[] bytes) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (!text.startsWith("{\"value\":\"") || !text.endsWith("\"}")) {
                    throw new IllegalArgumentException("Invalid test bundle");
                }
                return text.substring(10, text.length() - 2);
            }
            @Override public byte[] encode(String value) { return bytes(value); }
        };
        return new MetadataRepository(transport, storage, Map.of(FAMILY, codec));
    }

    private static byte[] manifest(String revision, MetadataBundleDescriptor descriptor) {
        return MANIFEST_CODEC.encodeBytes(new MetadataManifest(revision, List.of(descriptor)));
    }

    private static MetadataBundleDescriptor descriptor(byte[] bytes) {
        return new MetadataBundleDescriptor(MetadataBundleId.ofCanonicalBytes(bytes), FAMILY, 1,
            new MetadataSelector(FAMILY, "java", "26.1", null, null, null, null, null, null, null, Set.of()),
            bytes.length, Instant.parse("2026-09-19T00:00:00Z"), Map.of("source", "test"));
    }

    private static byte[] bytes(String value) {
        return ("{\"value\":\"" + value + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeTransport implements MetadataTransport {
        private byte[] manifest;
        private byte[] bundle;
        private Async<ManifestResponse> pendingManifest;
        private int bundleReads;

        private FakeTransport(byte[] manifest, byte[] bundle) {
            this.manifest = manifest.clone();
            this.bundle = bundle.clone();
        }

        @Override
        public Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag) {
            if (pendingManifest != null) {
                Async<ManifestResponse> result = pendingManifest;
                pendingManifest = null;
                return result;
            }
            return Async.completed(new ManifestResponse(Status.RESOLVED, "etag", manifest));
        }

        @Override
        public Async<byte[]> bundle(MetadataBundleId bundleId) {
            bundleReads++;
            return Async.completed(bundle.clone());
        }
    }

    private static final class MemoryStorage implements MetadataStorage {
        private final Map<MetadataBundleId, byte[]> values = new HashMap<>();

        @Override
        public Async<byte[]> read(MetadataBundleId bundleId) {
            byte[] value = values.get(bundleId);
            return Async.completed(value == null ? null : value.clone());
        }

        @Override
        public Async<Void> write(MetadataBundleId bundleId, byte[] bytes) {
            values.put(bundleId, bytes.clone());
            return Async.completed(null);
        }
    }
}
