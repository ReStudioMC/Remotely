package redxax.oxy.remotely.web.platform;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;
import restudio.rebase.resource.ResourceIndexOrchestrator;
import restudio.rebase.resource.ResourceIndexRequests;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.resource.provider.OnlineResourceVersion;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedResourceContextTest {
    @Test
    void directoryAggregationKeepsSuccessfulFoldersWithFailureMetadata() {
        ResourceIndexOrchestrator.Result result = canonicalIndex(List.of("/mods", "/plugins"), Map.of(
                "/mods", List.of(entry("/mods", "first.jar", null, null))),
                Map.of("/plugins", "plugins unavailable"));

        assertEquals(List.of("first.jar"), result.resources().stream()
                .map(ResourceIndexOrchestrator.ResolvedEntry::fileName).toList());
        assertEquals("plugins unavailable", result.failures().get("/plugins"));
    }

    @Test
    void invalidationMakesCapturedOperationsStale() {
        HostedResourceContext context = new HostedResourceContext(null, null, "server-a", null, null, null);
        Object operation = context.captureOperation();

        assertTrue(context.isOperationCurrent(operation, null));
        HostedResourceContext.CanonicalResourceInventory before = context.canonicalResourceInventory(false).join();
        assertTrue(before.entries().isEmpty());
        assertTrue(before.failures().isEmpty());
        context.invalidate();
        assertFalse(context.isOperationCurrent(operation, null));
        HostedResourceContext.CanonicalResourceInventory after = context.canonicalResourceInventory(false).join();
        assertTrue(after.entries().isEmpty());
        assertTrue(after.failures().isEmpty());
        assertTrue(context.resourceFailureSnapshot().isEmpty());
    }

    @Test
    void failedDirectoryReusesLastGoodListingAlongsideSuccessfulDirectory() {
        CanonicalSource source = new CanonicalSource();
        source.entries = Map.of("/mods", List.of(entry("/mods", "a.jar", "abc123", null)),
                "/plugins", List.of(entry("/plugins", "b.jar", "def456", null)));
        source.index(List.of("/mods", "/plugins"));
        source.entries = Map.of("/plugins", List.of(entry("/plugins", "c.jar", "ghi789", null)));
        source.failures = Map.of("/mods", "mods unavailable");
        ResourceIndexOrchestrator.Result result = source.index(List.of("/mods", "/plugins"));

        assertEquals(List.of("a.jar", "c.jar"), result.resources().stream()
                .map(ResourceIndexOrchestrator.ResolvedEntry::fileName).toList());
        assertEquals("mods unavailable", result.failures().get("/mods"));
    }

    @Test
    void allFailedDirectoriesReuseTheCompleteLastGoodInventory() {
        CanonicalSource source = new CanonicalSource();
        source.entries = Map.of("/mods", List.of(entry("/mods", "a.jar", "abc123", null)),
                "/plugins", List.of(entry("/plugins", "b.jar", "def456", null)));
        source.index(List.of("/mods", "/plugins"));
        source.entries = Map.of();
        source.failures = Map.of("/mods", "mods unavailable", "/plugins", "plugins unavailable");
        ResourceIndexOrchestrator.Result result = source.index(List.of("/mods", "/plugins"));

        assertEquals(List.of("a.jar", "b.jar"), result.resources().stream()
                .map(ResourceIndexOrchestrator.ResolvedEntry::fileName).toList());
        assertEquals(2, result.failures().size());
    }

    @Test
    void inventoryWarningDescribesPartialDirectoryFailure() {
        HostedResourceContext.CanonicalResourceInventory inventory = new HostedResourceContext.CanonicalResourceInventory(
                List.of(), null, List.of(new HostedResourceContext.ResourceFailure("/plugins", "plugins unavailable")));

        assertEquals("/plugins: plugins unavailable", inventory.warning());
    }

    @Test
    void inventoryRetainsBlankHashesAndIndexesEveryPhysicalHashMatch() {
        ResourceIndexOrchestrator.Result inventory = canonicalIndex(List.of("/mods", "/plugins"), Map.of(
                "/mods", List.of(entry("/mods", "local.jar", null, null), entry("/mods", "shared.jar", "ABC123", null)),
                "/plugins", List.of(entry("/plugins", "shared.jar", "sha1:abc123", null))), Map.of());

        assertEquals(3, inventory.resources().size());
        assertTrue(inventory.resources().getFirst().hash() == null);
        assertEquals("ABC123", inventory.resources().get(1).hash());
        assertEquals("sha1:abc123", inventory.resources().get(2).hash());
    }

    @Test
    void hashResolutionRetainsSha1AndCurseForgeFingerprintWhenEitherIsMissing() {
        ResourceIndexOrchestrator.Entry vault = entry("/plugins", "vault.jar", "existing-sha1", null);
        ResourceIndexOrchestrator.Entry placeholder = entry("/plugins", "placeholder.jar", null, 987654321L);
        List<List<ResourceIndexOrchestrator.Entry>> requested = new ArrayList<>();
        ResourceIndexOrchestrator.Result result = new ResourceIndexOrchestrator().index(List.of("/plugins"),
                new ResourceIndexOrchestrator.Source() {
                    @Override
                    public Async<List<ResourceIndexOrchestrator.Entry>> list(String directory) {
                        return Async.completed(List.of(vault, placeholder));
                    }

                    @Override
                    public Async<Map<String, ResourceIndexOrchestrator.HashResolution>> resolveHashes(
                            List<ResourceIndexOrchestrator.Entry> files) {
                        requested.add(files);
                        return Async.completed(Map.of("plugins/vault.jar",
                                        new ResourceIndexOrchestrator.HashResolution("existing-sha1", 123456789L),
                                "plugins/placeholder.jar",
                                new ResourceIndexOrchestrator.HashResolution("placeholder-sha1", 987654321L)));
                    }
                }, null).join();

        ResourceIndexOrchestrator.ResolvedEntry resolved = result.resources().getFirst();
        ResourceIndexOrchestrator.ResolvedEntry second = result.resources().get(1);
        assertEquals(2, requested.getFirst().size());
        assertEquals("existing-sha1", resolved.hash());
        assertEquals(123456789L, resolved.murmur2());
        assertEquals("placeholder-sha1", second.hash());
        assertEquals(987654321L, second.murmur2());
    }

    @Test
    void hashRequestsAreNormalizedAndCappedAtBackendBatchLimit() {
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < 129; index++) paths.add("/plugins\\plugin-" + String.format("%03d", index) + ".jar");
        paths.add("plugins/plugin-000.jar");

        List<List<String>> batches = ResourceIndexRequests.batches(paths, 64);

        assertEquals(3, batches.size());
        assertEquals(64, batches.get(0).size());
        assertEquals(64, batches.get(1).size());
        assertEquals(1, batches.get(2).size());
        assertEquals("plugins/plugin-000.jar", batches.getFirst().getFirst());
        assertEquals("plugins/plugin-128.jar", batches.getLast().getFirst());
        assertTrue(batches.stream().flatMap(List::stream).distinct().count() == 129);
    }

    @Test
    void hashBatchMergeCombinesSha1AndMurmur2Deterministically() {
        Map<String, ResourceIndexOrchestrator.HashResolution> hashes = new LinkedHashMap<>();
        hashes.put("plugins/vault.jar", null);

        ResourceIndexRequests.merge(hashes, List.of(
                new ResourceIndexRequests.HashValue("/plugins/vault.jar", "", 987654321L),
                new ResourceIndexRequests.HashValue("plugins/vault.jar", "ABC123", null)));

        ResourceIndexOrchestrator.HashResolution result = hashes.get("plugins/vault.jar");
        assertEquals("abc123", result.sha1());
        assertEquals(987654321L, result.murmur2());
    }

    @Test
    void preferredProviderWinsOneAuthoritativeIdentityPerHash() {
        OnlineResourceVersion modrinth = version("modrinth-project", "modrinth-version");
        OnlineResourceVersion curseForge = version("curse-project", "curse-version");
        List<String> providers = HostedResourceContext.prioritizeProvider(
                List.of("Modrinth", "CurseForge", "Hangar"), "CurseForge");
        Map<String, HostedResourceContext.ResourceIndexMatch> matches = new LinkedHashMap<>();
        HostedResourceContext.mergeProviderMatches(matches, providers.getFirst(), Map.of("ABC123", curseForge), false);
        HostedResourceContext.mergeProviderMatches(matches, "Modrinth", Map.of("ABC123", modrinth), false);

        assertEquals(List.of("CurseForge", "Modrinth", "Hangar"), providers);
        assertEquals(1, matches.size());
        assertEquals("CurseForge", matches.get("hash:abc123").provider());
        assertSame(curseForge, matches.get("hash:abc123").version());
    }

    @Test
    void modpackProfileBuildsCanonicalParentChildInventoryAndDetachesOwnedResource() {
        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.name = "Adventure Pack";
        server.environment = Map.of("MODPACK_PROVIDER", "modrinth", "MODPACK_PROJECT_ID", "pack-id");
        String profileJson = "{\"name\":\"Adventure Pack\",\"provider\":\"modrinth\",\"projectId\":\"pack-id\"," +
                "\"versionNumber\":\"1.2.0\",\"content\":[{\"path\":\"mods/owned.jar\"},{\"path\":\"mods/preserved.jar\"}]," +
                "\"preservedConflicts\":[\"mods/preserved.jar\"]}";
        HostedResourceContext.ModpackProfile profile = HostedResourceContext.profile(profileJson, List.of(), server);
        ResourceContainerItem owned = new ResourceContainerItem("mods/owned.jar", ResourceType.MOD, "owned.jar", true);
        ResourceContainerItem preserved = new ResourceContainerItem("mods/preserved.jar", ResourceType.MOD, "preserved.jar", true);

        List<ResourceContainerItem> resources = HostedResourceContainerProvider.hierarchy(List.of(owned, preserved), profile);

        assertEquals(2, resources.size());
        assertTrue(resources.getFirst().isModpack());
        assertEquals(List.of(owned), resources.getFirst().getChildren());
        assertSame(preserved, resources.get(1));
        HostedResourceContext.ModpackProfile detached = profile.detach("mods/owned.jar.disabled");
        assertFalse(detached.owns("mods/owned.jar"));
        assertTrue(detached.preservedPaths().contains("mods/owned.jar"));
    }

    @Test
    void resourceGroupsPersistPerServerThroughBrowserConfigStore() {
        MemoryStorage storage = new MemoryStorage();
        BrowserRemotelyConfigStore firstStore = new BrowserRemotelyConfigStore(storage);
        HostedResourceContext first = new HostedResourceContext(null, null, "server-a", null, null, null, firstStore);
        first.saveResourceGroups(Map.of("Performance", List.of("mods/lithium.jar", "mods/ferritecore.jar")));

        HostedResourceContext restored = new HostedResourceContext(null, null, "server-a", null, null, null,
                new BrowserRemotelyConfigStore(storage));
        HostedResourceContext otherServer = new HostedResourceContext(null, null, "server-b", null, null, null,
                new BrowserRemotelyConfigStore(storage));

        assertEquals(Map.of("Performance", List.of("mods/lithium.jar", "mods/ferritecore.jar")), restored.resourceGroups());
        assertTrue(otherServer.resourceGroups().isEmpty());
    }

    @Test
    void rootDiscoveryAddsResourceDirectoriesOutsideTheLoaderDefault() {
        ServerModels.PteroFileObjectAttributes mods = new ServerModels.PteroFileObjectAttributes();
        mods.name = "mods";
        mods.isFile = false;

        List<String> directories = HostedResourceContext.resourceDirectories("PAPER", "world");
        List<String> discovered = new HostedResourceContext(null, null, "server-a", null, "PAPER", null)
                .mergeResourceDirectories(directories, List.of(mods));

        assertTrue(discovered.contains("/plugins"));
        assertTrue(discovered.contains("/mods"));
    }

    @Test
    void canonicalSoftwareRestoresCompatibilityWhenLoaderIsStale() {
        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.identifier = "server-a";
        server.loader = "VANILLA";
        server.software = "PURPUR";
        HostedResourceContext context = new HostedResourceContext(null, null, "server-a", "1.21.8", server.loader,
                null, null, null, server);

        assertTrue(context.supportsPlugins());
        assertFalse(context.supportsMods());
        assertEquals("PURPUR", context.serverSoftwareType());
        assertEquals(List.of("purpur", "paper"), context.providerLoaderTokens(ResourceType.PLUGIN));
    }

    private static ResourceIndexOrchestrator.Result canonicalIndex(List<String> directories,
                                                                    Map<String, List<ResourceIndexOrchestrator.Entry>> entries,
                                                                    Map<String, String> failures) {
        return new ResourceIndexOrchestrator().index(directories, new ResourceIndexOrchestrator.Source() {
            @Override
            public Async<List<ResourceIndexOrchestrator.Entry>> list(String directory) {
                String failure = failures.get(directory);
                return failure == null ? Async.completed(entries.getOrDefault(directory, List.of()))
                        : Async.failed(new IllegalStateException(failure));
            }

            @Override
            public Async<Map<String, ResourceIndexOrchestrator.HashResolution>> resolveHashes(
                    List<ResourceIndexOrchestrator.Entry> files) {
                return Async.completed(Map.of());
            }
        }, null).join();
    }

    private static final class CanonicalSource implements ResourceIndexOrchestrator.Source {
        private Map<String, List<ResourceIndexOrchestrator.Entry>> entries = Map.of();
        private Map<String, String> failures = Map.of();
        private final Map<String, List<ResourceIndexOrchestrator.Entry>> snapshots = new LinkedHashMap<>();

        private ResourceIndexOrchestrator.Result index(List<String> directories) {
            return new ResourceIndexOrchestrator().index(directories, this, null).join();
        }

        @Override
        public Async<List<ResourceIndexOrchestrator.Entry>> list(String directory) {
            String failure = failures.get(directory);
            if (failure != null) return Async.failed(new IllegalStateException(failure));
            List<ResourceIndexOrchestrator.Entry> current = entries.getOrDefault(directory, List.of());
            snapshots.put(directory, current);
            return Async.completed(current);
        }

        @Override
        public List<ResourceIndexOrchestrator.Entry> snapshot(String directory) {
            return snapshots.getOrDefault(directory, List.of());
        }

        @Override
        public Async<Map<String, ResourceIndexOrchestrator.HashResolution>> resolveHashes(
                List<ResourceIndexOrchestrator.Entry> files) {
            return Async.completed(Map.of());
        }
    }

    private static ResourceIndexOrchestrator.Entry entry(String directory, String filename, String hash, Long murmur2) {
        return new ResourceIndexOrchestrator.Entry(directory, filename, 0L, 0L, true, hash, murmur2);
    }

    private static OnlineResourceVersion version(String projectId, String id) {
        OnlineResourceVersion version = new OnlineResourceVersion();
        version.projectId = projectId;
        version.id = id;
        return version;
    }

    private static final class MemoryStorage implements BrowserRemotelyConfigStore.Storage {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String read(String key) {
            return values.get(key);
        }

        @Override
        public boolean write(String key, String value) {
            values.put(key, value);
            return true;
        }

        @Override
        public boolean erase(String key) {
            values.remove(key);
            return true;
        }
    }
}
