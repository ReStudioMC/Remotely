package redxax.oxy.remotely.metadata.catalog;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.metadata.MetadataRepository;
import redxax.oxy.remotely.metadata.MetadataStorage;
import redxax.oxy.remotely.metadata.MetadataTransport;
import restudio.rescreen.platform.Async;
import restudio.resync.metadata.CatalogId;
import restudio.resync.metadata.MetadataBundleDescriptor;
import restudio.resync.metadata.MetadataBundleId;
import restudio.resync.metadata.MetadataCoordinate;
import restudio.resync.metadata.MetadataManifest;
import restudio.resync.metadata.MetadataManifestCodec;
import restudio.resync.metadata.MetadataSelector;
import restudio.resync.metadata.MinecraftRegistryBundle;
import restudio.resync.metadata.MinecraftRegistryBundleCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedCatalogRepositoryTest {
    @Test
    void mergesStableLayersAndRetainsBaselineFallback() {
        ResolvedCatalogRepository repository = new ResolvedCatalogRepository();
        ResolvedCatalogRepository.Item baselineShared = item("minecraft:shared", "Hosted Shared", "Hosted Description",
            "hosted", Map.of("baseline", true), true, "");
        ResolvedCatalogRepository.Item liveShared = item("minecraft:shared", "Live Shared", "", "",
            Map.of("live", true), true, "");
        ResolvedCatalogRepository.Input input = input(
            List.of(baselineShared, available("minecraft:baseline")),
            List.of(liveShared, available("plugin:live")),
            List.of("plugin:current"));

        ResolvedCatalogRepository.Snapshot snapshot = repository.resolve(input);

        assertEquals(List.of("minecraft:shared", "minecraft:baseline", "plugin:live", "plugin:current"),
            snapshot.items().stream().map(ResolvedCatalogRepository.ResolvedItem::value).toList());
        ResolvedCatalogRepository.ResolvedItem shared = snapshot.item("minecraft:shared");
        assertEquals("Live Shared", shared.item().label());
        assertEquals("Hosted Description", shared.item().description());
        assertEquals("hosted", shared.item().group());
        assertEquals(Map.of("baseline", true, "live", true), shared.item().metadata());
        assertSame(baselineShared, shared.baselineFallback());
        assertSame(liveShared, shared.liveItem());
        assertEquals(Set.of(ResolvedCatalogRepository.Provenance.BASELINE, ResolvedCatalogRepository.Provenance.LIVE),
            shared.provenance());
        assertEquals(ResolvedCatalogRepository.ItemState.CURRENT_ONLY, snapshot.item("plugin:current").state());
        assertEquals(Set.of(ResolvedCatalogRepository.Provenance.CURRENT), snapshot.item("plugin:current").provenance());
    }

    @Test
    void keepsFallbackUsableAndPreservesFailedLiveProvenance() {
        ResolvedCatalogRepository repository = new ResolvedCatalogRepository();
        ResolvedCatalogRepository.Baseline baseline = ResolvedCatalogRepository.Baseline.available(
            "bundle:one", "entity_types", List.of(available("minecraft:pig")));
        ResolvedCatalogRepository.Live live = new ResolvedCatalogRepository.Live("session:one", "revision:9", "failed:9",
            List.of(item("minecraft:pig", "Connected Pig", "", "", Map.of(), false, "Server Capture Failed")),
            ResolvedCatalogRepository.SourceState.FAILED, "Connection Closed");

        ResolvedCatalogRepository.Snapshot snapshot = repository.resolve(new ResolvedCatalogRepository.Input(
            "entity_types", baseline, live, ResolvedCatalogRepository.Current.empty()));

        assertEquals(ResolvedCatalogRepository.ResolutionState.STALE, snapshot.state());
        assertEquals(ResolvedCatalogRepository.ItemState.STALE, snapshot.item("minecraft:pig").state());
        assertEquals("Connected Pig", snapshot.item("minecraft:pig").item().label());
        assertEquals("Server Capture Failed", snapshot.item("minecraft:pig").diagnostic());
        assertEquals(ResolvedCatalogRepository.SourceState.FAILED, snapshot.diagnostics().get(0).state());
        assertEquals("Connection Closed", snapshot.diagnostics().get(0).message());
    }

    @Test
    void reusesOneImmutableResultPerStampAndInvalidatesFromRepository() {
        ResolvedCatalogRepository repository = new ResolvedCatalogRepository();
        ResolvedCatalogRepository.Input first = input(List.of(available("minecraft:pig")), List.of(), List.of());

        ResolvedCatalogRepository.Snapshot original = repository.resolve(first);
        assertSame(original, repository.resolve(first));
        assertSame(original, repository.snapshot("entity_types", "session:one"));
        assertEquals(1L, repository.generation());

        ResolvedCatalogRepository.Input changed = new ResolvedCatalogRepository.Input("entity_types", first.baseline(),
            first.live(), new ResolvedCatalogRepository.Current("current:two", List.of("plugin:custom")));
        ResolvedCatalogRepository.Snapshot replacement = repository.resolve(changed);
        assertNotSame(original, replacement);
        assertEquals(2L, repository.generation());
        assertSame(original, repository.resolve(first));
        assertEquals(3L, repository.generation());

        repository.invalidateServer("session:one");
        assertEquals(4L, repository.generation());
        assertThrows(IllegalArgumentException.class, () -> repository.snapshot("", "session:one"));
        ResolvedCatalogRepository.Snapshot rebuilt = repository.resolve(first);
        assertNotSame(original, rebuilt);
    }

    @Test
    void keepsActiveCatalogsSeparateForConcurrentServers() {
        ResolvedCatalogRepository repository = new ResolvedCatalogRepository();
        ResolvedCatalogRepository.Input first = input(List.of(available("minecraft:pig")), List.of(), List.of());
        ResolvedCatalogRepository.Live secondLive = new ResolvedCatalogRepository.Live("session:two", "revision:one",
            "available:one", List.of(available("minecraft:allay")), ResolvedCatalogRepository.SourceState.AVAILABLE, "");
        ResolvedCatalogRepository.Input second = new ResolvedCatalogRepository.Input("entity_types", first.baseline(),
            secondLive, ResolvedCatalogRepository.Current.empty());

        ResolvedCatalogRepository.Snapshot firstSnapshot = repository.resolve(first);
        ResolvedCatalogRepository.Snapshot secondSnapshot = repository.resolve(second);

        assertSame(firstSnapshot, repository.snapshot("entity_types", "session:one"));
        assertSame(secondSnapshot, repository.snapshot("entity_types", "session:two"));
        repository.invalidateServer("session:one");
        assertNull(repository.snapshot("entity_types", "session:one"));
        assertSame(secondSnapshot, repository.snapshot("entity_types", "session:two"));
    }

    @Test
    void adaptsAnAdmittedHostedRegistry() {
        CatalogId catalogId = CatalogId.of("minecraft", "entity_type");
        MinecraftRegistryBundle registry = new MinecraftRegistryBundle("26.3", "created", List.of(
            new MinecraftRegistryBundle.Catalog(catalogId, "Entities", "Entity Types", List.of(
                new MinecraftRegistryBundle.Entry("minecraft:pig", "Pig", "Passive Animal", null, "Animals",
                    Map.of("translation_key", "entity.minecraft.pig"))))));
        byte[] bytes = new MinecraftRegistryBundleCodec().encodeBytes(registry);
        MetadataBundleDescriptor descriptor = new MetadataBundleDescriptor(MetadataBundleId.ofCanonicalBytes(bytes),
            MinecraftRegistryBundle.ARTIFACT_FAMILY, 1, new MetadataSelector(MinecraftRegistryBundle.ARTIFACT_FAMILY,
            "java", "26.3", null, null, null, null, null, null, null, Set.of()), bytes.length,
            Instant.parse("2026-09-19T00:00:00Z"), Map.of());
        byte[] manifest = new MetadataManifestCodec().encodeBytes(new MetadataManifest("revision:one", List.of(descriptor)));
        MetadataTransport transport = new MetadataTransport() {
            @Override
            public Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag) {
                return Async.completed(new ManifestResponse(Status.RESOLVED, "etag", manifest));
            }

            @Override
            public Async<byte[]> bundle(MetadataBundleId bundleId) {
                return Async.completed(bytes);
            }
        };
        MetadataRepository repository = new MetadataRepository(transport, MetadataStorage.none());
        MetadataRepository.Request request = new MetadataRepository.Request(Set.of(MinecraftRegistryBundle.ARTIFACT_FAMILY),
            new MetadataCoordinate("java", "26.3", null, null, null, null, null), Set.of());

        MetadataRepository.Snapshot snapshot = repository.refresh(request).join();
        ResolvedCatalogRepository.Baseline baseline = ResolvedCatalogRepository.Baseline.from(snapshot, catalogId);

        assertEquals(registry, snapshot.bundle(MinecraftRegistryBundle.ARTIFACT_FAMILY).value());
        assertEquals(descriptor.bundleId().canonicalText(), baseline.bundleIdentity());
        assertEquals("minecraft:pig", baseline.items().getFirst().value());
        assertEquals(Map.of("translation_key", "entity.minecraft.pig"), baseline.items().getFirst().metadata());
    }

    @Test
    void adaptsLoaderChoicesAndDiagnosticsWithoutMutableMetadata() {
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        List<String> aliases = new ArrayList<>(List.of("zombie"));
        nested.put("aliases", aliases);
        OptionCatalogItem rich = new OptionCatalogItem();
        rich.setValue("minecraft:zombie");
        rich.setLabel("Zombie");
        rich.setDescription("Hostile Entity");
        rich.setGroup("Monster");
        rich.setMetadata(nested);
        OptionCatalogLoader.Snapshot loader = new OptionCatalogLoader.Snapshot(
            OptionCatalogLoader.request("entity_types"), "context", List.of("minecraft:pig", "minecraft:zombie"),
            List.of(rich), true, "stale", "Cached Catalog Is Awaiting Refresh");

        ResolvedCatalogRepository.Live live = ResolvedCatalogRepository.Live.from("session:one", "revision:3", loader);
        aliases.add("husk");
        nested.put("changed", true);

        assertEquals(ResolvedCatalogRepository.SourceState.STALE, live.state());
        assertEquals("Cached Catalog Is Awaiting Refresh", live.diagnostic());
        assertEquals(List.of("minecraft:pig", "minecraft:zombie"), live.items().stream()
            .map(ResolvedCatalogRepository.Item::value).toList());
        assertEquals(Map.of("aliases", List.of("zombie")), live.items().get(1).metadata());
        assertThrows(UnsupportedOperationException.class,
            () -> ((List<Object>) live.items().get(1).metadata().get("aliases")).add("drowned"));
    }

    private static ResolvedCatalogRepository.Input input(List<ResolvedCatalogRepository.Item> baseline,
                                                          List<ResolvedCatalogRepository.Item> live,
                                                          List<String> current) {
        return new ResolvedCatalogRepository.Input("entity_types",
            ResolvedCatalogRepository.Baseline.available("bundle:one", "entity_types", baseline),
            new ResolvedCatalogRepository.Live("session:one", "revision:one", "available:one", live,
                ResolvedCatalogRepository.SourceState.AVAILABLE, ""),
            new ResolvedCatalogRepository.Current(current.isEmpty() ? "current:none" : "current:one", current));
    }

    private static ResolvedCatalogRepository.Item available(String value) {
        return ResolvedCatalogRepository.Item.available(value);
    }

    private static ResolvedCatalogRepository.Item item(String value, String label, String description, String group,
                                                       Map<String, Object> metadata, boolean available, String reason) {
        return new ResolvedCatalogRepository.Item(value, label, description, "", group, metadata, available, reason);
    }
}
