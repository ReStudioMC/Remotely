package redxax.oxy.remotely.metadata.catalog;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.metadata.MetadataRepository;
import redxax.oxy.remotely.metadata.MetadataStorage;
import redxax.oxy.remotely.metadata.MetadataTransport;
import redxax.oxy.remotely.settings.server.HostedServerSettingsProvider;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.resync.metadata.CatalogId;
import restudio.resync.metadata.MetadataBundleDescriptor;
import restudio.resync.metadata.MetadataBundleId;
import restudio.resync.metadata.MetadataManifest;
import restudio.resync.metadata.MetadataManifestCodec;
import restudio.resync.metadata.MetadataSelector;
import restudio.resync.metadata.MinecraftRegistryBundle;
import restudio.resync.metadata.MinecraftRegistryBundleCodec;
import restudio.resync.metadata.ServerSettingsBundle;
import restudio.resync.metadata.ServerSettingsBundleCodec;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ServerSettingsCatalogServiceTest {
    @Test
    void admitsExactVersionOnceAndServesResidentSettingsCatalogs() {
        MinecraftRegistryBundle registry = new MinecraftRegistryBundle("26.3", "created", List.of(
            new MinecraftRegistryBundle.Catalog(CatalogId.of("minecraft", "entity_type"), "Entities", "Entity Types",
                List.of(new MinecraftRegistryBundle.Entry("minecraft:pig", "Pig", "Passive Animal", null, "Animals", Map.of())))));
        byte[] bytes = new MinecraftRegistryBundleCodec().encodeBytes(registry);
        MetadataBundleDescriptor descriptor = new MetadataBundleDescriptor(MetadataBundleId.ofCanonicalBytes(bytes),
            MinecraftRegistryBundle.ARTIFACT_FAMILY, 1, new MetadataSelector(MinecraftRegistryBundle.ARTIFACT_FAMILY,
            "java", "26.3", null, null, null, null, null, null, null, Set.of()), bytes.length,
            Instant.parse("2026-09-19T00:00:00Z"), Map.of());
        byte[] manifest = new MetadataManifestCodec().encodeBytes(new MetadataManifest("revision:one", List.of(descriptor)));
        int[] resolutions = {0};
        MetadataTransport transport = new MetadataTransport() {
            @Override
            public Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag) {
                if (request.artifactFamilies().contains(ServerSettingsBundle.ARTIFACT_FAMILY)) {
                    return Async.failed(new IllegalStateException("Settings are not part of this fixture"));
                }
                resolutions[0]++;
                return Async.completed(new ManifestResponse(Status.RESOLVED, "etag", manifest));
            }

            @Override
            public Async<byte[]> bundle(MetadataBundleId bundleId) {
                return Async.completed(bytes);
            }
        };
        ServerSettingsCatalogService service = new ServerSettingsCatalogService(
            new MetadataRepository(transport, MetadataStorage.none()));

        ServerSettingsCatalogService.View first = service.open("server-one", 4L, "26.3");
        ServerSettingsCatalogService.View second = service.open("server-one", 4L, "26.3");
        assertEquals(0, resolutions[0]);
        ResolvedCatalogRepository.Snapshot snapshot = first.catalog("server:minecraft:entity_type", "current:none", List.of());

        assertEquals(1, resolutions[0]);
        assertEquals(List.of("minecraft:pig"), snapshot.items().stream().map(ResolvedCatalogRepository.ResolvedItem::value).toList());
        assertEquals(ResolvedCatalogRepository.Provenance.BASELINE, snapshot.items().getFirst().provenance().iterator().next());
        assertSame(snapshot, first.catalog("server:minecraft:entity_type", "current:none", List.of()));
        first.close();
        assertSame(snapshot, second.catalog("server:minecraft:entity_type", "current:none", List.of()));
        second.close();
        service.close();
    }

    @Test
    void revalidatesResidentMetadataAfterTheFirstCatalogUse() {
        MinecraftRegistryBundle registry = new MinecraftRegistryBundle("26.3", "created", List.of(
            new MinecraftRegistryBundle.Catalog(CatalogId.of("minecraft", "entity_type"), "Entities", "Entity Types",
                List.of(new MinecraftRegistryBundle.Entry("minecraft:pig", "Pig", "Passive Animal", null,
                    "Animals", Map.of())))));
        byte[] bytes = new MinecraftRegistryBundleCodec().encodeBytes(registry);
        MetadataBundleDescriptor descriptor = new MetadataBundleDescriptor(MetadataBundleId.ofCanonicalBytes(bytes),
            MinecraftRegistryBundle.ARTIFACT_FAMILY, 1, new MetadataSelector(MinecraftRegistryBundle.ARTIFACT_FAMILY,
            "java", "26.3", null, null, null, null, null, null, null, Set.of()), bytes.length,
            Instant.parse("2026-09-19T00:00:00Z"), Map.of());
        byte[] manifest = new MetadataManifestCodec().encodeBytes(new MetadataManifest("revision:one", List.of(descriptor)));
        int[] resolutions = {0};
        long[] now = {10_000L};
        MetadataTransport transport = new MetadataTransport() {
            @Override
            public Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag) {
                if (request.artifactFamilies().contains(ServerSettingsBundle.ARTIFACT_FAMILY)) {
                    return Async.failed(new IllegalStateException("Settings are not part of this fixture"));
                }
                resolutions[0]++;
                return Async.completed(new ManifestResponse(Status.RESOLVED, "etag", manifest));
            }

            @Override
            public Async<byte[]> bundle(MetadataBundleId bundleId) {
                return Async.completed(bytes);
            }
        };
        Clock clock = () -> now[0];
        ServerSettingsCatalogService service = new ServerSettingsCatalogService(
            new MetadataRepository(transport, MetadataStorage.none()), new ResolvedCatalogRepository(),
            new HostedServerSettingsProvider(ServerSettingsRegistry.getInstance()), clock);

        ServerSettingsCatalogService.View first = service.open("server-one", 4L, "26.3");
        assertEquals(0, resolutions[0]);
        first.catalog("server:minecraft:entity_type", "current:none", List.of());
        now[0] += 59_999L;
        ServerSettingsCatalogService.View second = service.open("server-one", 4L, "26.3");
        second.catalog("server:minecraft:entity_type", "current:none", List.of());
        assertEquals(1, resolutions[0]);
        now[0]++;
        ServerSettingsCatalogService.View third = service.open("server-one", 4L, "26.3");
        assertEquals(1, resolutions[0]);
        third.catalog("server:minecraft:entity_type", "current:none", List.of());
        assertEquals(2, resolutions[0]);

        first.close();
        second.close();
        third.close();
        service.close();
    }

    @Test
    void retriesHostedSettingsWhenAdmissionFails() {
        ServerSettingsBundle settings = new ServerSettingsBundle("created", List.of(
            ServerSettingsBundle.Source.yaml("broken", 1, "packs: invalid\n")));
        byte[] bytes = new ServerSettingsBundleCodec().encodeBytes(settings);
        MetadataBundleDescriptor descriptor = new MetadataBundleDescriptor(MetadataBundleId.ofCanonicalBytes(bytes),
            ServerSettingsBundle.ARTIFACT_FAMILY, 1, new MetadataSelector(ServerSettingsBundle.ARTIFACT_FAMILY,
            "java", null, null, null, null, null, null, null, null, Set.of()), bytes.length,
            Instant.parse("2026-09-19T00:00:00Z"), Map.of());
        byte[] manifest = new MetadataManifestCodec().encodeBytes(new MetadataManifest("revision:broken",
            List.of(descriptor)));
        int[] settingsResolutions = {0};
        MetadataTransport transport = new MetadataTransport() {
            @Override
            public Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag) {
                if (!request.artifactFamilies().contains(ServerSettingsBundle.ARTIFACT_FAMILY)) {
                    return Async.failed(new IllegalStateException("Registry metadata is not part of this fixture"));
                }
                settingsResolutions[0]++;
                return Async.completed(new ManifestResponse(Status.RESOLVED, "etag", manifest));
            }

            @Override
            public Async<byte[]> bundle(MetadataBundleId bundleId) {
                return Async.completed(bytes);
            }
        };
        ServerSettingsCatalogService service = new ServerSettingsCatalogService(
            new MetadataRepository(transport, MetadataStorage.none()), new ResolvedCatalogRepository(),
            new HostedServerSettingsProvider(ServerSettingsRegistry.empty()), () -> 10_000L);

        ServerSettingsCatalogService.View first = service.open("server-one", 1L, "");
        ServerSettingsCatalogService.View second = service.open("server-one", 1L, "");

        assertEquals(2, settingsResolutions[0]);
        first.close();
        second.close();
        service.close();
    }

    @Test
    void mapsOnlyDeclaredSettingsCatalogAliases() {
        assertEquals(CatalogId.of("minecraft", "item"),
            ServerSettingsCatalogService.catalogId("server:minecraft:material"));
        assertEquals(CatalogId.of("minecraft", "configured_feature"),
            ServerSettingsCatalogService.catalogId("server:minecraft:configured_feature"));
        assertEquals(CatalogId.of("minecraft", "worldgen/biome"),
            ServerSettingsCatalogService.catalogId("server:minecraft:biome"));
        assertNull(ServerSettingsCatalogService.catalogId("server:minecraft:world"));
        assertNull(ServerSettingsCatalogService.catalogId("plugin:custom"));
    }
}
