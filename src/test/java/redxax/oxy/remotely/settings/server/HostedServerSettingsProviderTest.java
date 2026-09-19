package redxax.oxy.remotely.settings.server;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.metadata.MetadataRepository;
import redxax.oxy.remotely.metadata.MetadataStorage;
import redxax.oxy.remotely.metadata.MetadataTransport;
import restudio.rescreen.platform.Async;
import restudio.resync.metadata.MetadataBundleDescriptor;
import restudio.resync.metadata.MetadataBundleId;
import restudio.resync.metadata.MetadataCoordinate;
import restudio.resync.metadata.MetadataManifest;
import restudio.resync.metadata.MetadataManifestCodec;
import restudio.resync.metadata.MetadataSelector;
import restudio.resync.metadata.ServerSettingsBundle;
import restudio.resync.metadata.ServerSettingsBundleCodec;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostedServerSettingsProviderTest {
    private static final MetadataRepository.Request REQUEST = new MetadataRepository.Request(
            Set.of(ServerSettingsBundle.ARTIFACT_FAMILY),
            new MetadataCoordinate("java", "26.1", null, null, null, null, null),
            Set.of()
    );

    @Test
    void preservesSourcePrecedenceIndependentOfProviderPriorities() {
        ServerSettingsRegistry registry = ServerSettingsRegistry.empty();
        registry.registerBuiltin("builtin", metadata("builtin", 1_000, "Bootstrap"));
        registry.register("programmatic", 1_000, metadata("programmatic", 1_000, "Extension").packs());
        FakeTransport transport = new FakeTransport(bundle(source("hosted", "Hosted")), "revision-1");
        MetadataRepository repository = new MetadataRepository(transport, MetadataStorage.none());

        try (HostedServerSettingsProvider provider = new HostedServerSettingsProvider(registry)) {
            provider.apply(repository.refresh(REQUEST).join());
            assertEquals("Hosted", selectedName(registry));

            registry.registerExternal("external", metadata("external", -1_000, "Override"));
            assertEquals("Override", selectedName(registry));

            registry.clearExternal();
            assertEquals("Hosted", selectedName(registry));
        }

        assertEquals("Extension", selectedName(registry));
    }

    @Test
    void parsesOncePerBundleIdentityAndAtomicallyReplacesHostedSources() {
        ServerSettingsRegistry registry = ServerSettingsRegistry.empty();
        CountingParser parser = new CountingParser();
        ServerSettingsBundle original = bundle(
                source("first", "First"),
                source("second", "Second", "second")
        );
        FakeTransport transport = new FakeTransport(original, "revision-1");
        MetadataRepository repository = new MetadataRepository(transport, MetadataStorage.none());

        try (HostedServerSettingsProvider provider = new HostedServerSettingsProvider(registry, parser)) {
            HostedServerSettingsProvider.State first = provider.apply(repository.refresh(REQUEST).join());
            assertEquals(2, parser.reads);
            assertEquals(2, first.packCount());

            transport.install(transport.bundle, "revision-2");
            HostedServerSettingsProvider.State sameBundle = provider.apply(repository.refresh(REQUEST).join());
            assertEquals(2, parser.reads);
            assertEquals("revision-2|server_settings:" + sameBundle.bundleId(), sameBundle.repositoryStamp());

            transport.install(bundle(source("replacement", "Replacement", "replacement")), "revision-3");
            provider.apply(repository.refresh(REQUEST).join());
            assertEquals(3, parser.reads);
            assertEquals(List.of("replacement"), registry.packs().stream().map(ServerSettingsPack::id).toList());

            transport.install(original, "revision-4");
            HostedServerSettingsProvider.State revisited = provider.apply(repository.refresh(REQUEST).join());
            assertEquals(3, parser.reads);
            assertEquals(List.of("second", "shared"), registry.packs().stream().map(ServerSettingsPack::id).toList());

            transport.install(bundle(ServerSettingsBundle.Source.yaml("broken", 1, "packs: invalid\n")), "revision-5");
            MetadataRepository.Snapshot broken = repository.refresh(REQUEST).join();
            RuntimeException failure = assertThrows(RuntimeException.class, () -> provider.apply(broken));
            assertSame(failure, assertThrows(RuntimeException.class, () -> provider.apply(broken)));
            assertEquals(4, parser.reads);
            assertSame(revisited, provider.state());
            assertEquals(List.of("second", "shared"), registry.packs().stream().map(ServerSettingsPack::id).toList());
        }
    }

    private static String selectedName(ServerSettingsRegistry registry) {
        return registry.packs().stream().filter(pack -> pack.id().equals("shared")).findFirst().orElseThrow().name();
    }

    private static ServerSettingsMetadata metadata(String provider, int priority, String name) {
        return new ServerSettingsMetadata(provider, priority, List.of(
                new ServerSettingsPack("shared", name, "Settings metadata.", priority, List.of("paper"), List.of(
                        new ServerSettingsDocument("settings.yml", ServerSettingsFormat.YAML, false, false)
                ))
        ));
    }

    private static ServerSettingsBundle.Source source(String id, String name) {
        return source(id, name, "shared");
    }

    private static ServerSettingsBundle.Source source(String id, String name, String packId) {
        return ServerSettingsBundle.Source.yaml(id, 1, """
                providerId: %s
                priority: 0
                packs:
                  - id: %s
                    name: %s
                    description: Settings metadata.
                    software: [paper]
                    documents:
                      - path: settings.yml
                        format: yaml
                        fields: []
                """.formatted(id, packId, name));
    }

    private static ServerSettingsBundle bundle(ServerSettingsBundle.Source... sources) {
        return new ServerSettingsBundle("2026-09-19T00:00:00Z", List.of(sources));
    }

    private static final class CountingParser implements ServerSettingsMetadataReader {
        private final ServerSettingsMetadataReader delegate = new BrowserSafeYamlServerSettingsMetadataParser();
        private int reads;

        @Override
        public ServerSettingsMetadata parse(InputStream input, String sourceName) throws IOException {
            reads++;
            return delegate.parse(input, sourceName);
        }
    }

    private static final class FakeTransport implements MetadataTransport {
        private final MetadataManifestCodec manifestCodec = new MetadataManifestCodec();
        private final ServerSettingsBundleCodec bundleCodec = new ServerSettingsBundleCodec();
        private byte[] manifest;
        private byte[] bundle;

        private FakeTransport(ServerSettingsBundle bundle, String revision) {
            install(bundle, revision);
        }

        private void install(ServerSettingsBundle value, String revision) {
            install(bundleCodec.encodeBytes(value), revision);
        }

        private void install(byte[] bytes, String revision) {
            bundle = bytes.clone();
            MetadataBundleId id = MetadataBundleId.ofCanonicalBytes(bundle);
            MetadataBundleDescriptor descriptor = new MetadataBundleDescriptor(
                    id,
                    ServerSettingsBundle.ARTIFACT_FAMILY,
                    ServerSettingsBundle.CURRENT_FORMAT_VERSION,
                    new MetadataSelector(ServerSettingsBundle.ARTIFACT_FAMILY, "java", "26.1",
                            null, null, null, null, null, null, null, Set.of()),
                    bundle.length,
                    Instant.parse("2026-09-19T00:00:00Z"),
                    Map.of("source", "test")
            );
            manifest = manifestCodec.encodeBytes(new MetadataManifest(revision, List.of(descriptor)));
        }

        @Override
        public Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag) {
            return Async.completed(new ManifestResponse(Status.RESOLVED, revisionEtag(), manifest));
        }

        @Override
        public Async<byte[]> bundle(MetadataBundleId bundleId) {
            return Async.completed(bundle.clone());
        }

        private String revisionEtag() {
            return MetadataBundleId.ofCanonicalBytes(manifest).canonicalText();
        }
    }
}
