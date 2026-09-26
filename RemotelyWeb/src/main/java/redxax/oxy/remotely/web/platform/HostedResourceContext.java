package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.teavm.jso.JSBody;
import restudio.rebase.backend.CapabilityDescriptor;
import restudio.rebase.backend.RemotePath;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rebase.resource.ResourceIndexOrchestrator;
import restudio.rebase.resource.ResourceIndexEntries;
import restudio.rebase.resource.ResourceIndexRequests;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.resource.WorldArchiveInstaller;
import restudio.rebase.resource.marketplace.ResourceBrowserContext;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProvider;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProviderAdapter;
import restudio.rebase.resource.provider.AsyncResourceProvider;
import restudio.rebase.resource.provider.OnlineResourceVersion;
import restudio.rebase.resource.provider.ResourceCompatibilityTokens;
import restudio.rebase.resource.provider.ResourceProviderException;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.screens.resources.ResourceOverviewScreen;
import restudio.rebase.ui.screens.resources.ResourceContainerCapabilities;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.IsoTimes;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyGroup;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class HostedResourceContext implements ResourceBrowserContext {
    private static final int RESOURCE_SEARCH_SCAN_PAGES = 2;
    private static final int HASH_BATCH_SIZE = 64;
    private final ResourceMarketplaceProviderAdapter marketplace;
    private final BrowserRemotelyServerApi api;
    private final ServerUiCapabilityProvider serverCapabilities;
    private final ServerModels.ClientServerView server;
    private final String serverId;
    private final String version;
    private final String loader;
    private final Object remoteHost;
    private final RemotelyConfigStore configStore;
    private final Consumer<ModpackSelection> modpackSelection;
    private final ResourceIndexOrchestrator resourceIndex = new ResourceIndexOrchestrator();
    private final List<Consumer<ResourceBrowserContext.ResourceChange>> resourceListeners = new ArrayList<>();
    private final List<Consumer<CanonicalResourceInventory>> canonicalInventoryListeners = new ArrayList<>();
    private final Map<String, BrowserRemotelyServerApi.HostedModpackCapabilities> modpackCapabilities = new LinkedHashMap<>();
    private final BrowserTaskScheduler scheduler = new BrowserTaskScheduler();
    private TaskScheduler.ScheduledTask pendingResourceNotification;
    private OperationFence pendingResourceNotificationFence;
    private ResourceCacheKey cacheKey;
    private ResourceDataCache dataCache;
    private String selectedProvider;
    private long lifecycleGeneration = 1L;
    private final Map<Async<?>, SharedRequest> sharedRequests = new IdentityHashMap<>();
    private final Map<Async<?>, SharedRequest> sharedViews = new IdentityHashMap<>();

    HostedResourceContext(ResourceMarketplaceProviderAdapter marketplace, Object remoteHost) {
        this(marketplace, null, null, null, null, remoteHost, null, null, null);
    }

    HostedResourceContext(ResourceMarketplaceProviderAdapter marketplace, BrowserRemotelyServerApi api,
                                  String serverId, String version, String loader, Object remoteHost) {
        this(marketplace, api, serverId, version, loader, remoteHost, null, null, null);
    }

    HostedResourceContext(ResourceMarketplaceProviderAdapter marketplace, BrowserRemotelyServerApi api,
                                  String serverId, String version, String loader, Object remoteHost,
                                  RemotelyConfigStore configStore) {
        this(marketplace, api, serverId, version, loader, remoteHost, configStore, null, null);
    }

    HostedResourceContext(ResourceMarketplaceProviderAdapter marketplace, BrowserRemotelyServerApi api,
                                  String serverId, String version, String loader, Object remoteHost,
                                  RemotelyConfigStore configStore, ServerUiCapabilityProvider serverCapabilities,
                                  ServerModels.ClientServerView server) {
        this(marketplace, api, serverId, version, loader, remoteHost, configStore, serverCapabilities, server, null);
    }

    HostedResourceContext(ResourceMarketplaceProviderAdapter marketplace, BrowserRemotelyServerApi api,
                                  String serverId, String version, String loader, Object remoteHost,
                                  RemotelyConfigStore configStore, ServerUiCapabilityProvider serverCapabilities,
                                  ServerModels.ClientServerView server, Consumer<ModpackSelection> modpackSelection) {
        this.marketplace = marketplace;
        this.api = api;
        this.server = server == null ? serverView(serverId) : server;
        this.serverCapabilities = serverCapabilities == null
                ? api == null ? ServerUiCapabilityProvider.unavailable() : ServerUiCapabilityProvider.api(api)
                : serverCapabilities;
        this.serverId = serverId == null ? "" : serverId;
        this.version = version;
        this.loader = loader;
        this.remoteHost = remoteHost;
        this.configStore = configStore;
        this.modpackSelection = modpackSelection;
        this.cacheKey = resourceCacheKey();
        this.dataCache = new ResourceDataCache(cacheKey);
        if (serverCapabilities == null && api != null && !this.serverId.isBlank()) this.serverCapabilities.refresh(this.server);
        if (remoteHost instanceof BrowserServerScreenHost host) host.registerResourceContext(this);
    }

    static ResourceType defaultResourceType(String loader) {
        return ResourceCompatibilityTokens.compatibleTokens(loader).stream().anyMatch(ResourceCompatibilityTokens::isPlugin)
                ? ResourceType.PLUGIN : ResourceType.MOD;
    }

    static List<String> resourceDirectories(String loader) {
        return resourceDirectories(loader, "world");
    }

    static List<String> resourceDirectories(String loader, String worldName) {
        List<String> tokens = ResourceCompatibilityTokens.compatibleTokens(loader);
        boolean mods = tokens.stream().anyMatch(ResourceCompatibilityTokens::isModded);
        boolean plugins = tokens.stream().anyMatch(ResourceCompatibilityTokens::isPlugin);
        List<String> directories = new ArrayList<>();
        if (mods && plugins) {
            directories.add("/mods");
            directories.add("/plugins");
        } else if (plugins) {
            directories.add("/plugins");
        } else {
            directories.add("/mods");
        }
        String datapackDirectory = "/" + normalizedWorldName(worldName) + "/datapacks";
        if (!directories.contains(datapackDirectory)) directories.add(datapackDirectory);
        return List.copyOf(directories);
    }

    @Override
    public boolean hasInstance() {
        return api != null && !serverId.isBlank();
    }

    @Override
    public boolean isServer() {
        return hasInstance();
    }

    @Override
    public Async<ResourceMarketplaceProvider.Details> details(ResourceMarketplaceProvider.Card resource) {
        return details(resource, captureFence());
    }

    private Async<ResourceMarketplaceProvider.Details> details(ResourceMarketplaceProvider.Card resource, OperationFence fence) {
        if (resource == null) return Async.completed(null);
        if (marketplace == null) return Async.completed(null);
        return loadDetails(resource, fence).thenApply(details -> {
            if (!isCurrent(fence)) return null;
            if (details == null) return null;
            ResourceMarketplaceProvider.Card detailedCard = details.card() == null ? resource : details.card();
            ResourceMarketplaceProvider.Card statefulCard = installedCard(detailedCard);
            return new ResourceMarketplaceProvider.Details(statefulCard, details.body(), details.projectUrl(), details.sourceUrl(),
                    details.issuesUrl(), details.wikiUrl(), details.discordUrl(), details.license(), details.createdAt(),
                    details.updatedAt(), details.gameVersions(), details.loaders(), details.links(), details.gallery(),
                    details.websiteUrl(), details.documentationUrl(), details.licenseUrl(), details.donations(),
                    details.creators(), details.averageRating(), details.ratingCount());
        });
    }

    private Async<ResourceMarketplaceProvider.Details> loadDetails(ResourceMarketplaceProvider.Card resource,
                                                                    OperationFence fence) {
        if (!isCurrent(fence)) return staleOperation();
        return marketplace.details(resource.provider(), resource.id()).thenApply(details -> isCurrent(fence) ? details : null);
    }

    @Override
    public Async<List<ResourceMarketplaceProvider.Version>> versions(ResourceMarketplaceProvider.Card resource) {
        return versions(resource, captureFence());
    }

    @Override
    public Async<State> state(ResourceMarketplaceProvider.Card resource) {
        if (resource == null) return Async.completed(State.standard());
        OperationFence fence = captureFence();
        return ensureInventory(fence).thenApply(ignored -> {
            ResourceIndexOrchestrator.ResolvedEntry installed = canonicalResources(resource).stream().findFirst().orElse(null);
            Mode mode = ResourceType.getTypeFromString(resource.type()) == ResourceType.MODPACK && hasLinkedModpack()
                    ? Mode.LINKED : Mode.STANDARD;
            return new State(mode, installed == null || installed.metadata() == null ? "" : Objects.requireNonNullElse(installed.metadata().versionId(), ""),
                    installed == null ? "" : installed.fileName(), hasInstance(), hasInstance() ? "" : "Server Target Is Unavailable");
        });
    }

    private Async<List<ResourceMarketplaceProvider.Version>> versions(ResourceMarketplaceProvider.Card resource, OperationFence fence) {
        if (resource == null) return Async.completed(List.of());
        if (marketplace == null) return Async.completed(List.of());
        return marketplace.versions(resource.provider(), resource.id()).thenCompose(versions -> {
            if (!isCurrent(fence)) return staleOperation();
            return Async.completed(versions == null ? List.of()
                    : versions.stream().filter(candidate -> matchesServer(candidate, resource)).toList());
        });
    }

    @Override
    public boolean supports(ResourceMarketplaceProvider.Action action, ResourceMarketplaceProvider.Card resource) {
        ensureDataCache();
        return action != null && resource != null && switch (action) {
            case INSTALL -> creationModpack(resource) || installSupported(resource);
            case UPDATE -> capabilitiesAvailable("files.read", "files.list", "files.pull", "files.delete") && hasUpdate(resource);
            case DELETE -> capabilitiesAvailable("files.read", "files.list", "files.delete") && hasInstalled(resource);
            case CHANGE_MODPACK -> api != null && hasInstance() && ResourceType.MODPACK == ResourceType.getTypeFromString(resource.type());
            case OPEN, SELECT -> true;
            default -> false;
        };
    }

    @Override
    public Availability availability(ActionRequest request) {
        ensureDataCache();
        if (request == null || request.action() == null || request.resource() == null) {
            return Availability.unavailable("Resource Action Is Unavailable");
        }
        if (request.action() == ResourceMarketplaceProvider.Action.CHANGE_MODPACK) {
            BrowserRemotelyServerApi.HostedModpackCapabilities capabilities = modpackCapabilities.get(request.resource().provider());
            if (capabilities == null) return Availability.unavailable("Checking Modpack Install Capability");
            BrowserRemotelyServerApi.HostedModpackAvailability value = hasLinkedModpack() ? capabilities.change() : capabilities.install();
            if (value == null || !value.supported()) {
                return Availability.unavailable(value == null || value.reason() == null || value.reason().isBlank()
                        ? "Change Modpack Is Unavailable For This Server" : value.reason());
            }
        }
        return supports(request.action(), request.resource()) ? Availability.supported() : Availability.unavailable(switch (request.action()) {
            case INSTALL -> "Install Is Unavailable For This Server";
            case UPDATE -> "Update Is Unavailable For This Resource";
            case DELETE -> "Remove Is Unavailable For This Resource";
            case CHANGE_MODPACK -> "Change Modpack Requires The Modpack Install Capability";
            case OPEN -> "Open Is Unavailable";
            case SELECT -> "Selection Is Unavailable";
        });
    }

    Async<Void> refreshModpackCapabilities(ResourceMarketplaceProvider.Card resource) {
        ensureDataCache();
        if (creationModpack(resource)) {
            return Async.completed(null);
        }
        if (api == null || resource == null || ResourceType.getTypeFromString(resource.type()) != ResourceType.MODPACK) {
            return Async.completed(null);
        }
        return api.getHostedModpackCapabilities(serverId, resource.provider()).thenApply(value -> {
            modpackCapabilities.put(resource.provider(), value == null
                    ? unavailableModpackCapabilities(resource.provider(), "Modpack Capabilities Are Unavailable") : value);
            return (Void) null;
        }).exceptionally(failure -> {
            modpackCapabilities.put(resource.provider(), unavailableModpackCapabilities(resource.provider(), failureMessage(failure)));
            return null;
        });
    }

    @Override
    public Map<String, CapabilityDescriptor> capabilities() {
        return Map.of(
                ResourceContainerCapabilities.GROUPING, configStore == null
                        ? CapabilityDescriptor.unavailable(ResourceContainerCapabilities.GROUPING, "Resource Group Storage Is Unavailable")
                        : CapabilityDescriptor.supported(ResourceContainerCapabilities.GROUPING),
                ResourceContainerCapabilities.NESTED, CapabilityDescriptor.supported(ResourceContainerCapabilities.NESTED),
                ResourceContainerCapabilities.DETACH, mutationCapability(ResourceContainerCapabilities.DETACH,
                        "Detaching Resources Requires Server File Editing", "files.read", "files.write"),
                ResourceContainerCapabilities.UPDATE_SELECTION, CapabilityDescriptor.supported(
                        ResourceContainerCapabilities.UPDATE_SELECTION),
                ResourceContainerCapabilities.BACKUP, mutationCapability(ResourceContainerCapabilities.BACKUP,
                        "Server Backups Are Unavailable", "backups.create"),
                ResourceContainerCapabilities.UPLOAD, CapabilityDescriptor.supported(
                        ResourceContainerCapabilities.UPLOAD));
    }

    @Override
    public void openExternal(String url) {
        if (url != null && !url.isBlank()) ScreenManager.getInstance().hostActions().openBrowser(url);
    }

    @Override
    public boolean isProxyServer() {
        return compatibilityTokens().stream().anyMatch(ResourceCompatibilityTokens::isProxy);
    }

    @Override
    public boolean supportsPlugins() {
        return compatibilityTokens().stream().anyMatch(ResourceCompatibilityTokens::isPlugin);
    }

    @Override
    public boolean supportsMods() {
        return compatibilityTokens().stream().anyMatch(ResourceCompatibilityTokens::isModded);
    }

    @Override
    public boolean supportsDeferredQuickActions() {
        return true;
    }

    @Override
    public boolean hasLinkedModpack() {
        return server != null && server.environment != null && server.environment.get("MODPACK_PROJECT_ID") != null
                && !server.environment.get("MODPACK_PROJECT_ID").isBlank();
    }

    @Override
    public String modLoader() {
        return loader;
    }

    @Override
    public String serverSoftwareType() {
        return server == null || server.software == null || server.software.isBlank() ? loader : server.software;
    }

    @Override
    public String versionId() {
        return version;
    }

    @Override
    public List<String> providerLoaderTokens(ResourceType type) {
        List<String> tokens = compatibilityTokens();
        return switch (type == null ? ResourceType.MODPACK : type) {
            case MOD -> tokens.stream().filter(ResourceCompatibilityTokens::isModded).toList();
            case PLUGIN -> tokens.stream().filter(ResourceCompatibilityTokens::isPlugin).toList();
            default -> List.of();
        };
    }

    private List<String> compatibilityTokens() {
        LinkedHashSet<String> tokens = new LinkedHashSet<>(ResourceCompatibilityTokens.compatibleTokens(loader));
        if (server != null) tokens.addAll(ResourceCompatibilityTokens.compatibleTokens(server.software));
        return List.copyOf(tokens);
    }

    @Override
    public boolean preserveUnknownResourceMetadata() {
        return true;
    }

    @Override
    public int resourceSearchScanBudget(ResourceType type) {
        return RESOURCE_SEARCH_SCAN_PAGES;
    }

    @Override
    public String normalizeCompatibilityToken(String value) {
        return ResourceCompatibilityTokens.normalize(value);
    }

    @Override
    public boolean isModdedCompatibilityToken(String value) {
        return ResourceCompatibilityTokens.isModded(value);
    }

    @Override
    public boolean isPluginCompatibilityToken(String value) {
        return ResourceCompatibilityTokens.isPlugin(value);
    }

    private boolean matchesServer(ResourceMarketplaceProvider.Version candidate, ResourceMarketplaceProvider.Card resource) {
        ResourceType type = resource == null ? ResourceType.MODPACK : ResourceType.getTypeFromString(resource.type());
        return matchesServer(candidate, type == null ? ResourceType.MODPACK : type);
    }

    private boolean matchesServer(ResourceMarketplaceProvider.Version candidate, ResourceType type) {
        if (candidate == null) return false;
        ResourceType resolvedType = type == null ? ResourceType.MODPACK : type;
        return ResourceCompatibilityTokens.matches(toOnlineVersion(candidate), providerLoaderTokens(resolvedType), versionId(), resolvedType);
    }

    private static OnlineResourceVersion toOnlineVersion(ResourceMarketplaceProvider.Version source) {
        OnlineResourceVersion result = new OnlineResourceVersion();
        result.id = source.id();
        result.name = source.name();
        result.versionNumber = source.number();
        result.versionType = source.channel();
        result.datePublished = source.publishedAt();
        result.gameVersions = source.gameVersions() == null ? List.of() : source.gameVersions().stream()
                .filter(Objects::nonNull).toList();
        result.loaders = source.loaders() == null ? List.of() : source.loaders().stream().filter(Objects::nonNull).toList();
        List<OnlineResourceVersion.VersionFile> files = new ArrayList<>();
        if (source.files() != null) {
            source.files().stream().filter(Objects::nonNull).forEach(file -> {
                OnlineResourceVersion.VersionFile mapped = new OnlineResourceVersion.VersionFile();
                mapped.id = file.id();
                mapped.filename = file.name();
                mapped.size = file.size();
                mapped.primary = file.primary();
                mapped.isServerPack = file.serverPack();
                mapped.url = file.downloadUrl();
                files.add(mapped);
            });
        }
        if (files.stream().noneMatch(file -> file.filename != null && !file.filename.isBlank())
                && source.fileName() != null && !source.fileName().isBlank()) {
            OnlineResourceVersion.VersionFile mapped = new OnlineResourceVersion.VersionFile();
            mapped.filename = source.fileName();
            mapped.size = source.fileSize();
            mapped.primary = true;
            files.add(mapped);
        }
        result.files = List.copyOf(files);
        return result;
    }

    @Override
    public String lastProvider(String key) {
        if (selectedProvider != null && marketplace.providers().contains(selectedProvider)) return selectedProvider;
        if (configStore != null) {
            String remembered = configStore.getResourceBrowserLastProvider(key);
            if (remembered != null && marketplace.providers().contains(remembered)) {
                selectedProvider = remembered;
                return remembered;
            }
        }
        selectedProvider = marketplace.providers().stream().findFirst().orElse(null);
        return selectedProvider;
    }

    @Override
    public void saveLastProvider(String key, String provider) {
        if (provider == null || provider.isBlank() || !marketplace.providers().contains(provider)) return;
        selectedProvider = provider;
        if (configStore == null) return;
        configStore.setResourceBrowserLastProvider(key, provider);
        configStore.save();
    }

    @Override
    public List<String> localBaseVersionIds() {
        return version == null || version.isBlank() ? List.of() : List.of(version);
    }

    @Override
    public Async<List<InstalledResource>> installedResources() {
        if (!hasInstance()) return Async.completed(List.of());
        OperationFence fence = captureFence();
        Async<List<InstalledResource>> request = canonicalResourceIndex(false, fence).thenApply(this::installedProjection);
        return view(request);
    }

    private List<InstalledResource> installedProjection(ResourceIndexOrchestrator.Result result) {
        List<ResourceIndexOrchestrator.ResolvedEntry> resources = dataCache.indexedResources;
        if (resources == null || resources.isEmpty()) resources = result == null ? List.of() : result.resources();
        if (resources == null) return List.of();
        return resources.stream().filter(resource -> resource != null && resource.metadata() != null
                && resource.metadata().projectId() != null && !resource.metadata().projectId().isBlank())
                .map(resource -> new InstalledResource(resource.metadata().provider(), resource.metadata().projectId(),
                        resource.metadata().availableUpdate() != null)).distinct().toList();
    }

    private Async<ResourceIndexOrchestrator.Result> canonicalResourceIndex(boolean force, OperationFence fence) {
        if (!hasInstance()) return Async.completed(new ResourceIndexOrchestrator.Result(List.of(), Map.of()));
        if (!force && dataCache.canonicalResourceRequest != null && isDataCurrent(fence)) {
            if (!dataCache.canonicalResourceRequest.isDone() || dataCache.canonicalResourceResult == null) {
                return view(dataCache.canonicalResourceRequest);
            }
            return Async.completed(dataCache.canonicalResourceResult);
        }
        if (force) {
            dataCache.resourceDirectoryRequests.clear();
            dataCache.canonicalResourceResult = null;
        }
        Async<ResourceIndexOrchestrator.Result> request = withCapabilities(() -> resourceDirectoriesAsync(fence)
                .thenCompose(directories -> {
                    dataCache.resourceDirectorySnapshots.keySet().removeIf(path -> !directories.contains(path));
                    dataCache.resourceFailureMessages.keySet().removeIf(path -> !isDiscoveryFailure(path)
                            && !directories.contains(path));
                    return resourceIndex.indexPhysical(directories, resourceSource(fence));
                }), "files.read", "files.list");
        dataCache.canonicalResourceRequest = request;
        request.whenComplete((result, failure) -> {
            if (failure != null) {
                if (dataCache.canonicalResourceRequest == request) {
                    dataCache.canonicalResourceRequest = null;
                    dataCache.canonicalResourceResult = null;
                }
                return;
            }
            if (dataCache.canonicalResourceRequest != request || !isCurrent(fence)) return;
            List<ResourceIndexOrchestrator.ResolvedEntry> resources = result == null ? List.of()
                    : preserveLastGoodMetadata(result.resources());
            dataCache.indexedResources = resources;
            dataCache.canonicalResourceResult = new ResourceIndexOrchestrator.Result(resources,
                    result == null ? Map.of() : result.failures());
            dataCache.resourceFailureMessages.keySet().removeIf(path -> !isDiscoveryFailure(path)
                    && !dataCache.resourceDirectorySnapshots.containsKey(path));
            if (result != null) mergeResourceFailures(result.failures());
            dataCache.modpackProfile = null;
            notifyResourceListeners(fence);
            scheduleResourceHydration(result, fence);
        });
        return view(request);
    }

    private ResourceIndexOrchestrator.Source resourceSource(OperationFence fence) {
        return new ResourceIndexOrchestrator.Source() {
            @Override
            public Async<List<ResourceIndexOrchestrator.Entry>> list(String directory) {
                return listResourceDirectory(directory).thenApply(entries -> {
                    if (!isCurrent(fence)) throw new Async.Cancellation();
                    List<ResourceIndexOrchestrator.Entry> mapped = ResourceIndexEntries.fromFiles(directory, entries);
                    dataCache.resourceDirectorySnapshots.put(directory, mapped);
                    recordResourceFailure(directory, null);
                    return mapped;
                });
            }

            @Override
            public List<ResourceIndexOrchestrator.Entry> snapshot(String directory) {
                return dataCache.resourceDirectorySnapshots.getOrDefault(directory, List.of());
            }

            @Override
            public Async<Map<String, ResourceIndexOrchestrator.HashResolution>> resolveHashes(
                    List<ResourceIndexOrchestrator.Entry> files) {
                List<String> paths = ResourceIndexRequests.paths(files);
                if (paths.isEmpty()) return Async.completed(Map.of());
                if (!isCurrent(fence)) return staleOperation();
                Map<String, ResourceIndexOrchestrator.HashResolution> hashes = new LinkedHashMap<>();
                paths.forEach(path -> hashes.put(path, null));
                boolean[] batchFailed = {false};
                List<List<String>> hashBatches = ResourceIndexRequests.batches(paths, HASH_BATCH_SIZE);
                int batchCount = hashBatches.size();
                int batchNumber = 0;
                Async<Void> batches = Async.completed(null);
                for (List<String> batch : hashBatches) {
                    int currentBatch = ++batchNumber;
                    batches = batches.thenCompose(ignored -> {
                        if (!isCurrent(fence)) return staleOperation();
                        Async<List<ServerModels.ResourceFileHash>> request;
                        try {
                            request = Objects.requireNonNull(api.resolveResourceFileHashes(serverId, batch), "Resource Hash Request");
                        } catch (Throwable failure) {
                            request = Async.failed(failure);
                        }
                        return request.thenApply(values -> {
                            if (!isCurrent(fence)) throw new Async.Cancellation();
                            ResourceIndexRequests.merge(hashes, values.stream().map(value -> new ResourceIndexRequests.HashValue(
                                    value.path, value.sha1, fingerprintValue(normalizeFingerprint(value.murmur2)))).toList());
                            return null;
                        }).handle((ignoredValue, failure) -> {
                            if (failure == null) return null;
                            if (!isCurrent(fence)) throw new Async.Cancellation();
                            if (isCancellation(failure)) throw rethrow(failure);
                            batchFailed[0] = true;
                            dataCache.resourceFailureMessages.put("files.hash",
                                    "Hash Batch " + currentBatch + "/" + batchCount + " Failed: "
                                            + failureMessage(failure, "Resource Hash Resolution Failed"));
                            return null;
                        });
                    });
                }
                return batches.thenApply(ignored -> {
                    if (!isCurrent(fence)) throw new Async.Cancellation();
                    hashes.values().removeIf(Objects::isNull);
                    if (!batchFailed[0]) dataCache.resourceFailureMessages.remove("files.hash");
                    return hashes;
                });
            }
        };
    }


    private void scheduleResourceHydration(ResourceIndexOrchestrator.Result physical, OperationFence fence) {
        if (physical == null || !isCurrent(fence)) return;
        ResourceDataCache cache = dataCache;
        cancel(cache.resourceHydrationRequest);
        cache.resourceHydrationRequest = null;
        long generation = ++cache.resourceHydrationGeneration;
        scheduler.execute(() -> {
            if (!isCurrent(fence) || cache != dataCache || cache.resourceHydrationGeneration != generation) return;
            Async<ResourceIndexOrchestrator.Result> hydration = resourceIndex.hydrate(physical, resourceSource(fence),
                    this::resolveMetadata);
            cache.resourceHydrationRequest = hydration;
            hydration.whenComplete((result, failure) -> {
                if (cache.resourceHydrationRequest != hydration || cache.resourceHydrationGeneration != generation
                        || !isCurrent(fence) || cache != dataCache) return;
                cache.resourceHydrationRequest = null;
                if (failure != null) {
                    if (isCancellation(failure)) {
                        dataCache.resourceFailureMessages.remove("providers");
                    } else {
                        dataCache.resourceFailureMessages.put("providers", failureMessage(failure, "Resource Provider Resolution Failed"));
                    }
                    notifyResourceListeners(fence);
                    return;
                }
                if (result == null) return;
                List<ResourceIndexOrchestrator.ResolvedEntry> resources = preserveLastGoodMetadata(result.resources());
                dataCache.indexedResources = resources;
                dataCache.canonicalResourceResult = new ResourceIndexOrchestrator.Result(resources, result.failures());
                dataCache.resourceFailureMessages.keySet().removeIf(path -> "providers".equals(path)
                        || path.startsWith("provider:"));
                mergeResourceFailures(result.failures());
                notifyResourceListeners(fence);
            });
        });
    }

    private List<ResourceIndexOrchestrator.ResolvedEntry> preserveLastGoodMetadata(
            List<ResourceIndexOrchestrator.ResolvedEntry> physical) {
        Map<String, ResourceIndexOrchestrator.ResolvedEntry> previous = new LinkedHashMap<>();
        dataCache.indexedResources.stream().filter(Objects::nonNull)
                .forEach(entry -> previous.put(entry.path(), entry));
        if (previous.isEmpty() || physical == null || physical.isEmpty()) return physical == null ? List.of() : physical;
        return physical.stream().filter(Objects::nonNull).map(entry -> {
            ResourceIndexOrchestrator.ResolvedEntry old = previous.get(entry.path());
            if (old == null) return entry;
            String hash = entry.hash() == null || entry.hash().isBlank() ? old.hash() : entry.hash();
            Long murmur2 = entry.murmur2() == null ? old.murmur2() : entry.murmur2();
            ResourceIndexOrchestrator.ResolvedMetadata metadata = entry.metadata() == null ? old.metadata() : entry.metadata();
            return new ResourceIndexOrchestrator.ResolvedEntry(entry.directoryPath(), entry.fileName(), entry.size(),
                    entry.mtime(), entry.enabled(), hash, murmur2, metadata);
        }).toList();
    }

    private Async<ResourceIndexOrchestrator.MetadataResolution> resolveMetadata(List<String> hashes,
                                                                                        List<Long> fingerprints) {
        Map<String, ResourceIndexMatch> matches = new LinkedHashMap<>();
        Map<String, String> lookupFailures = new LinkedHashMap<>();
        Async<Void> lookups = Async.completed(null);
        for (String provider : inventoryProviders()) {
            lookups = lookups.thenCompose(ignored -> resolveProvider(provider, hashes, fingerprints, matches,
                    lookupFailures));
        }
        return lookups.thenCompose(ignored -> resolveMetadata(matches, lookupFailures));
    }

    private Async<Void> resolveProvider(String provider, List<String> hashes, List<Long> fingerprints,
                                               Map<String, ResourceIndexMatch> matches, Map<String, String> failures) {
        AsyncResourceProvider source;
        try {
            source = marketplace.source(provider);
        } catch (Throwable failure) {
            recordProviderLookupFailure(failures, provider, failure);
            return Async.completed(null);
        }
        if (source == null) {
            recordProviderLookupFailure(failures, provider, new IllegalStateException("Provider Is Unavailable"));
            return Async.completed(null);
        }
        Async<Map<String, OnlineResourceVersion>> hashLookup;
        try {
            hashLookup = source.supportsHashLookup() && hashes != null && !hashes.isEmpty()
                    ? Objects.requireNonNull(marketplace.searchByHashes(provider, hashes), "Resource Hash Lookup Request")
                    : Async.completed(Map.of());
        } catch (Throwable failure) {
            hashLookup = Async.failed(failure);
        }
        return hashLookup.handle((found, failure) -> {
            if (failure == null) mergeProviderMatches(matches, provider, found, false);
            else recordProviderLookupFailure(failures, provider, failure);
            return (Void) null;
        }).thenCompose(ignored -> {
            Async<Map<String, OnlineResourceVersion>> fingerprintLookup;
            try {
                fingerprintLookup = source.supportsFingerprintLookup() && fingerprints != null && !fingerprints.isEmpty()
                        ? Objects.requireNonNull(marketplace.searchByFingerprints(provider, fingerprints),
                        "Resource Fingerprint Lookup Request") : Async.completed(Map.of());
            } catch (Throwable failure) {
                fingerprintLookup = Async.failed(failure);
            }
            return fingerprintLookup.handle((found, failure) -> {
                if (failure == null) mergeProviderMatches(matches, provider, found, true);
                else recordProviderLookupFailure(failures, provider, failure);
                return (Void) null;
            });
        });
    }

    private Async<ResourceIndexOrchestrator.MetadataResolution> resolveMetadata(
            Map<String, ResourceIndexMatch> matches, Map<String, String> lookupFailures) {
        Map<String, ResourceIndexMatch> projects = new LinkedHashMap<>();
        Map<String, List<String>> projectKeys = new LinkedHashMap<>();
        if (matches != null) {
            matches.forEach((key, match) -> {
                if (match == null || match.version() == null || match.version().projectId == null
                        || match.version().projectId.isBlank()) return;
                String projectKey = resourceKey(match.provider(), match.version().projectId);
                projects.putIfAbsent(projectKey, match);
                projectKeys.computeIfAbsent(projectKey, ignored -> new ArrayList<>()).add(key);
            });
        }
        Map<String, ResourceIndexOrchestrator.ResolvedMetadata> resolved = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        if (lookupFailures != null) failures.putAll(lookupFailures);
        Async<Void> sequence = Async.completed(null);
        for (Map.Entry<String, ResourceIndexMatch> entry : projects.entrySet()) {
            String projectKey = entry.getKey();
            ResourceIndexMatch match = entry.getValue();
            sequence = sequence.thenCompose(ignored -> resolveProject(match)
                    .thenApply(result -> {
                        ResourceMarketplaceProvider.Details detail = result.details().value();
                        List<ResourceMarketplaceProvider.Version> available = result.versions().value();
                        ResourceMarketplaceProvider.Card card = detail == null ? null : detail.card();
                        String typeValue = card == null || card.type() == null || card.type().isBlank()
                                ? match.version().projectType : card.type();
                        ResourceType type = ResourceType.getTypeFromString(typeValue);
                        ResourceMarketplaceProvider.Version latest = available == null ? null : available.stream()
                                .filter(candidate -> matchesServer(candidate, type)).max(Comparator.comparing(HostedResourceContext::publishedAt))
                                .orElse(null);
                        OnlineResourceVersion update = latest == null || Objects.equals(latest.id(), match.version().id)
                                ? null : latestVersion(latest, match.version());
                        ResourceIndexOrchestrator.ResolvedMetadata metadata = new ResourceIndexOrchestrator.ResolvedMetadata(
                                match.provider(), match.version().projectId, match.version().id, match.version().versionNumber,
                                card == null ? match.version().name : card.title(),
                                card == null ? null : card.description(), card == null ? List.of() : card.authors(),
                                card == null || card.gameVersions() == null ? null : String.join(", ", card.gameVersions()),
                                card == null ? null : card.iconUrl(), update);
                        projectKeys.getOrDefault(projectKey, List.of()).forEach(key -> resolved.put(key, metadata));
                        String failure = metadataFailure(result);
                        if (failure != null) failures.put("provider:" + projectKey, failure);
                        return null;
                    }));
        }
        return sequence.thenApply(ignored -> new ResourceIndexOrchestrator.MetadataResolution(resolved, failures));
    }

    private Async<MetadataResult> resolveProject(ResourceIndexMatch match) {
        Async<ResourceMarketplaceProvider.Details> details;
        try {
            details = Objects.requireNonNull(marketplace.details(match.provider(), match.version().projectId),
                    "Resource Details Request");
        } catch (Throwable failure) {
            details = Async.failed(failure);
        }
        return details.handle((value, failure) -> new OperationResult<>(value, failure)).thenCompose(detailsResult -> {
            if (detailsResult.failure() instanceof Async.Cancellation) return Async.failed(detailsResult.failure());
            Async<List<ResourceMarketplaceProvider.Version>> versions;
            try {
                versions = Objects.requireNonNull(marketplace.versions(match.provider(), match.version().projectId),
                        "Resource Versions Request");
            } catch (Throwable failure) {
                versions = Async.failed(failure);
            }
            return versions.handle((available, failure) -> new OperationResult<>(
                            available == null ? List.<ResourceMarketplaceProvider.Version>of() : available, failure))
                    .thenCompose(versionsResult -> {
                        if (versionsResult.failure() instanceof Async.Cancellation) return Async.failed(versionsResult.failure());
                        return Async.completed(new MetadataResult(detailsResult, versionsResult));
                    });
        });
    }

    private static String metadataFailure(MetadataResult result) {
        String detailsFailure = result.details().failure() == null ? null
                : "Details: " + failureMessage(result.details().failure(), "Unavailable");
        String versionsFailure = result.versions().failure() == null ? null
                : "Versions: " + failureMessage(result.versions().failure(), "Unavailable");
        if (detailsFailure == null) return versionsFailure;
        if (versionsFailure == null) return detailsFailure;
        return detailsFailure + "; " + versionsFailure;
    }

    private void mergeResourceFailures(Map<String, String> failures) {
        if (failures == null) return;
        failures.forEach((key, value) -> {
            if (key.startsWith("provider:")) consoleLog("[resources] " + key + ": " + value);
            else dataCache.resourceFailureMessages.put(key, value);
        });
    }

    @JSBody(params = {"message"}, script = "if(window.console&&console.log)console.log(message);")
    private static native void consoleLog(String message);

    private static void recordProviderLookupFailure(Map<String, String> failures, String provider, Throwable failure) {
        if (failures == null) return;
        String key = "provider:" + Objects.requireNonNullElse(provider, "") + ":lookup";
        failures.putIfAbsent(key, "Lookup: " + failureMessage(failure, "Unavailable"));
    }

    static void mergeProviderMatches(Map<String, ResourceIndexMatch> matches, String provider,
                                     Map<String, OnlineResourceVersion> values, boolean fingerprint) {
        if (values == null) return;
        values.forEach((key, version) -> {
            if (version == null || version.projectId == null || version.projectId.isBlank()) return;
            String normalized = fingerprint ? ResourceIndexOrchestrator.fingerprintKey(fingerprintValue(key))
                    : ResourceIndexOrchestrator.hashKey(normalizeHash(key));
            if (!normalized.isBlank()) matches.putIfAbsent(normalized, new ResourceIndexMatch(provider, version));
        });
    }

    private static OnlineResourceVersion latestVersion(ResourceMarketplaceProvider.Version latest,
                                                       OnlineResourceVersion matched) {
        OnlineResourceVersion result = new OnlineResourceVersion();
        result.id = latest.id();
        result.projectId = matched.projectId;
        result.versionNumber = latest.number();
        return result;
    }

    private static long modifiedAt(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            Long parsed = IsoTimes.millis(value);
            return parsed == null ? 0L : parsed;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    Map<String, List<String>> resourceGroups() {
        if (configStore == null) return Map.of();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (RemotelyGroup group : configStore.getInstanceGroups(resourceGroupContext(serverId))) {
            if (group != null && !group.name().isBlank() && !group.members().isEmpty()) {
                groups.put(group.name(), group.members());
            }
        }
        return groups;
    }

    void saveResourceGroups(Map<String, List<String>> groups) {
        if (configStore == null) return;
        List<RemotelyGroup> values = new ArrayList<>();
        int index = 0;
        if (groups != null) {
            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isEmpty()) continue;
                values.add(new RemotelyGroup("resource-" + index++, entry.getKey(), entry.getValue()));
            }
        }
        configStore.setInstanceGroups(resourceGroupContext(serverId), values);
        configStore.save();
    }

    Async<Void> createResourceBackup(String name) {
        return withCapabilities(() -> api.createBackup(serverId, name, List.of(), false).thenApply(ignored -> null), "backups.create");
    }

    Async<Boolean> detachResource(String path) {
        if (path == null || path.isBlank()) return Async.completed(false);
        return withCapabilities(() -> ensureModpackProfile().thenCompose(profile -> {
            if (profile == null || !profile.owns(path)) return Async.completed(false);
            ModpackProfile detached = profile.detach(path);
            return serverCapabilities.writeFile(server, ".meta/modpack-profile.json", detached.json()).thenApply(ignored -> {
                clearInventoryCache();
                notifyResourceListeners(captureFence());
                return true;
            });
        }), "files.read", "files.write");
    }

    @Override
    public synchronized void addResourceListener(Consumer<ResourceBrowserContext.ResourceChange> listener) {
        if (listener != null && !resourceListeners.contains(listener)) resourceListeners.add(listener);
    }

    @Override
    public synchronized void removeResourceListener(Consumer<ResourceBrowserContext.ResourceChange> listener) {
        resourceListeners.remove(listener);
    }

    synchronized void addCanonicalInventoryListener(Consumer<CanonicalResourceInventory> listener) {
        if (listener != null && !canonicalInventoryListeners.contains(listener)) canonicalInventoryListeners.add(listener);
    }

    synchronized void removeCanonicalInventoryListener(Consumer<CanonicalResourceInventory> listener) {
        canonicalInventoryListeners.remove(listener);
    }

    Async<CanonicalResourceInventory> canonicalResourceInventory(boolean force) {
        OperationFence fence = captureFence();
        Async<ResourceIndexOrchestrator.Result> index = canonicalResourceIndex(force, fence);
        return index.thenApply(result -> {
            List<ResourceIndexOrchestrator.ResolvedEntry> resources = dataCache.indexedResources;
            if (resources.isEmpty() && result != null && !result.resources().isEmpty()) resources = result.resources();
            if (!isCurrent(fence)) throw new Async.Cancellation();
            scheduleModpackProfile(resources, fence);
            return new CanonicalResourceInventory(resources, dataCache.modpackProfile, canonicalFailures(result));
        });
    }

    private void scheduleModpackProfile(List<ResourceIndexOrchestrator.ResolvedEntry> resources, OperationFence fence) {
        if (!hasInstance() || !isCurrent(fence) || dataCache.modpackProfile != null
                || dataCache.modpackProfileRequest != null || dataCache.modpackProfileScheduled) return;
        ResourceDataCache cache = dataCache;
        cache.modpackProfileScheduled = true;
        scheduler.execute(() -> {
            if (!isCurrent(fence) || cache != dataCache) return;
            Async<ModpackProfile> request;
            try {
                request = canonicalModpackProfile(resources, fence);
            } catch (Throwable failure) {
                recordResourceFailure(".meta/modpack-profile.json", failure);
                request = Async.completed(null);
            }
            cache.modpackProfileRequest = request;
            request.whenComplete((profile, failure) -> {
                if (!isCurrent(fence) || cache != dataCache) return;
                if (failure != null) {
                    Throwable cause = ResourceProviderException.unwrap(failure);
                    if (!(cause instanceof Async.Cancellation)) {
                        recordResourceFailure(".meta/modpack-profile.json", cause);
                    }
                }
                notifyResourceListeners(fence);
            });
        });
    }

    private List<ResourceFailure> canonicalFailures(ResourceIndexOrchestrator.Result result) {
        Map<String, String> values = new LinkedHashMap<>();
        if (result != null) values.putAll(result.failures());
        dataCache.resourceFailureMessages.forEach(values::putIfAbsent);
        return values.entrySet().stream().map(entry -> new ResourceFailure(entry.getKey(), entry.getValue())).toList();
    }

    private Async<ModpackProfile> canonicalModpackProfile(List<ResourceIndexOrchestrator.ResolvedEntry> resources, OperationFence fence) {
        if (!hasInstance()) return Async.completed(null);
        if (dataCache.modpackProfile != null) return Async.completed(dataCache.modpackProfile);
        if (dataCache.modpackProfileRequest != null) return dataCache.modpackProfileRequest;
        return api.getFileContentAllowMissing(serverId, ".meta/modpack-profile.json").handle((content, failure) -> {
            if (!isCurrent(fence)) throw new Async.Cancellation();
            if (failure != null) {
                Throwable cause = ResourceProviderException.unwrap(failure);
                if (cause instanceof Async.Cancellation cancellation) throw cancellation;
                dataCache.modpackProfile = null;
                recordResourceFailure(".meta/modpack-profile.json", new IllegalStateException("Modpack Profile Is Unavailable"));
                return null;
            }
            if (content == null || content.isBlank()) {
                dataCache.modpackProfile = null;
                recordResourceFailure(".meta/modpack-profile.json", null);
                return null;
            }
            ModpackProfile profile = profile(content, resources, server);
            dataCache.modpackProfile = profile;
            recordResourceFailure(".meta/modpack-profile.json", null);
            return profile;
        });
    }

    void addCapabilityListener(Runnable listener) {
        serverCapabilities.addCapabilityListener(listener);
    }

    void removeCapabilityListener(Runnable listener) {
        serverCapabilities.removeCapabilityListener(listener);
    }

    @Override
    public Async<Void> action(ResourceMarketplaceProvider.Action action, ResourceMarketplaceProvider.Card resource,
                              ResourceMarketplaceProvider.Version version, Runnable refreshed) {
        if (action == null || resource == null) return Async.failed(new IllegalArgumentException("Resource Action Is Unavailable"));
        OperationFence fence = captureFence();
        return switch (action) {
            case INSTALL -> install(resource, version, null, refreshed, fence);
            case UPDATE -> update(resource, version, null, refreshed, fence);
            case DELETE -> delete(resource, refreshed, fence);
            case CHANGE_MODPACK -> changeModpack(resource, version, refreshed, fence);
            case OPEN -> details(resource, fence).thenApply(loaded -> {
                if (!isCurrent(fence)) return null;
                String url = loaded == null ? "" : loaded.projectUrl();
                if (url == null || url.isBlank()) throw new IllegalStateException("Resource Link Is Unavailable");
                openExternal(url);
                return null;
            });
            case SELECT -> Async.completed(null);
            default -> Async.failed(new UnsupportedOperationException("Managed Resource Actions Are Unavailable In Browser"));
        };
    }

    @Override
    public Async<Void> action(ActionRequest request, Runnable refreshed) {
        if (request == null) return Async.failed(new IllegalArgumentException("Resource Action Is Unavailable"));
        OperationFence fence = captureFence();
        return switch (request.action()) {
            case INSTALL -> install(request.resource(), request.version(), request.file(), refreshed, fence);
            case UPDATE -> update(request.resource(), request.version(), request.file(), refreshed, fence);
            case CHANGE_MODPACK -> changeModpack(request.resource(), request.version(), request.file(), refreshed, fence);
            default -> action(request.action(), request.resource(), request.version(), refreshed);
        };
    }

    private Async<Void> changeModpack(ResourceMarketplaceProvider.Card resource, ResourceMarketplaceProvider.Version selected,
                                      Runnable refreshed, OperationFence fence) {
        return changeModpack(resource, selected, null, refreshed, fence);
    }

    private Async<Void> changeModpack(ResourceMarketplaceProvider.Card resource, ResourceMarketplaceProvider.Version selected,
                                      ResourceMarketplaceProvider.VersionFile file, Runnable refreshed, OperationFence fence) {
        if (api == null || !hasInstance()) return Async.failed(new UnsupportedOperationException("Change Modpack Is Unavailable For This Server"));
        return latestVersion(resource, selected, fence).thenCompose(version -> api.getHostedModpackCapabilities(serverId, resource.provider())
                .thenCompose(capabilities -> {
                    if (capabilities == null) {
                        return Async.failed(new IllegalStateException("Modpack Capabilities Are Unavailable"));
                    }
                    modpackCapabilities.put(resource.provider(), capabilities);
                    boolean linked = hasLinkedModpack();
                    BrowserRemotelyServerApi.HostedModpackAvailability availability = linked ? capabilities.change() : capabilities.install();
                    if (availability == null || !availability.supported()) {
                        String reason = availability == null || availability.reason() == null || availability.reason().isBlank()
                                ? "Change Modpack Is Unavailable For This Server" : availability.reason();
                        return Async.failed(new UnsupportedOperationException(reason));
                    }
                    BrowserRemotelyServerApi.HostedModpackRequest request = new BrowserRemotelyServerApi.HostedModpackRequest(
                            resource.provider(), resource.id(), version.id(), file == null ? null : file.id(), version.number(), null, this.version,
                            loader, null, server == null ? null : server.dockerImage, UUID.randomUUID().toString());
                    Notification progress = progressNotification(linked ? "Changing Modpack" : "Installing Modpack");
                    Async<BrowserRemotelyServerApi.HostedModpackJob> preflight = capabilities.preflight() != null
                            && capabilities.preflight().supported()
                            ? api.preflightHostedModpack(serverId, request).thenCompose(job -> awaitModpackJob(job, progress, fence))
                            : Async.completed(null);
                    return preflight.thenCompose(ignored -> {
                        updateModpackProgress(progress, "Preparing Server", 0);
                        Async<BrowserRemotelyServerApi.HostedModpackJob> operation = linked
                                ? api.changeHostedModpack(serverId, request) : api.installHostedModpack(serverId, request);
                        return operation.thenCompose(job -> awaitModpackJob(job, progress, fence));
                    }).thenCompose(job -> {
                        if (!isCurrent(fence)) return Async.completed(null);
                        if (server != null) {
                            if (server.environment == null) server.environment = new LinkedHashMap<>();
                            server.environment.put("MODPACK_PROVIDER", resource.provider());
                            server.environment.put("MODPACK_PROJECT_ID", resource.id());
                            server.environment.put("MODPACK_VERSION_ID", version.id());
                            server.environment.put("MODPACK_VERSION_NUMBER", version.number());
                        }
                        return refreshCanonicalInventory(fence).thenApply(ignored -> {
                            progress.update().message(linked ? "Modpack Changed" : "Modpack Installed")
                                    .description(resource.title()).type(Notification.Type.SUCCESS).loading(false)
                                    .autoSlideOut(true).progress(100, 100).commit();
                            if (refreshed != null) refreshed.run();
                            notifyResourceListeners(fence);
                            return (Void) null;
                        });
                    }).whenComplete((ignored, failure) -> {
                        if (failure == null) return;
                        progress.update().message(linked ? "Change Failed" : "Installation Failed")
                                .description(failureMessage(failure)).type(Notification.Type.ERROR).loading(false)
                                .autoSlideOut(true).commit();
                    });
                }));
    }

    private Async<BrowserRemotelyServerApi.HostedModpackJob> awaitModpackJob(
            BrowserRemotelyServerApi.HostedModpackJob job, Notification progress, OperationFence fence) {
        Async<BrowserRemotelyServerApi.HostedModpackJob> result = Async.pending();
        pollModpackJob(job, progress, fence, result);
        return result;
    }

    Async<Void> unlockModpack(String provider) {
        if (api == null || !hasInstance() || provider == null || provider.isBlank()) {
            return Async.failed(new UnsupportedOperationException("Unlock Modpack Is Unavailable For This Server"));
        }
        OperationFence fence = captureFence();
        return api.getHostedModpackCapabilities(serverId, provider).thenCompose(capabilities -> {
            BrowserRemotelyServerApi.HostedModpackAvailability availability = capabilities == null ? null : capabilities.unlock();
            if (availability == null || !availability.supported()) {
                String reason = availability == null || availability.reason() == null || availability.reason().isBlank()
                        ? "Unlock Modpack Is Unavailable For This Server" : availability.reason();
                return Async.failed(new UnsupportedOperationException(reason));
            }
            return api.unlockHostedModpack(serverId, provider, UUID.randomUUID().toString())
                    .thenCompose(job -> awaitModpackJob(job, null, fence)).thenCompose(job -> {
                        if (server != null && server.environment != null) {
                            server.environment.remove("MODPACK_PROVIDER");
                            server.environment.remove("MODPACK_PROJECT_ID");
                            server.environment.remove("MODPACK_VERSION_ID");
                            server.environment.remove("MODPACK_VERSION_NUMBER");
                        }
                        return refreshCanonicalInventory(fence).thenApply(ignored -> {
                            notifyResourceListeners(fence);
                            return null;
                        });
                    });
        });
    }

    private void pollModpackJob(BrowserRemotelyServerApi.HostedModpackJob job, Notification progress,
                                OperationFence fence, Async<BrowserRemotelyServerApi.HostedModpackJob> result) {
        if (result.isDone()) return;
        if (!isCurrent(fence)) {
            result.fail(new IllegalStateException("Server Context Changed"));
            return;
        }
        if (job == null || job.id() == null || job.id().isBlank()) {
            result.fail(new IllegalStateException("Modpack Job Is Unavailable"));
            return;
        }
        String status = job.status() == null ? "" : job.status().toLowerCase(Locale.ROOT);
        if (progress != null) updateModpackProgress(progress, modpackJobDescription(job), job.progress());
        if (status.equals("completed") || status.equals("complete") || status.equals("succeeded") || status.equals("success")) {
            result.complete(job);
            return;
        }
        if (status.equals("failed") || status.equals("error") || status.equals("cancelled") || status.equals("canceled")) {
            result.fail(new IllegalStateException(job.error() == null || job.error().isBlank() ? "Modpack Operation Failed" : job.error()));
            return;
        }
        scheduler.schedule(() -> api.getHostedModpackJob(job.id()).whenComplete((next, failure) -> {
            if (failure != null) result.fail(failure);
            else pollModpackJob(next, progress, fence, result);
        }), Duration.ofSeconds(1));
    }

    private static Notification progressNotification(String message) {
        return new Notification.Builder().message(message).description("Checking Server")
                .type(Notification.Type.INFO).loading(true).autoSlideOut(false).progress(0, 100).build();
    }

    private static void updateModpackProgress(Notification notification, String description, int progress) {
        if (progress >= 0) notification.updateProgress(description, Math.min(progress, 100), 100);
        else notification.update().description(description).commit();
    }

    private static String modpackJobDescription(BrowserRemotelyServerApi.HostedModpackJob job) {
        String operation = job.operation() == null ? "Modpack" : switch (job.operation().toLowerCase(Locale.ROOT)) {
            case "preflight" -> "Checking Downloads";
            case "install" -> "Installing Modpack";
            case "change" -> "Changing Modpack";
            default -> "Preparing Server";
        };
        return operation;
    }

    private static String failureMessage(Throwable failure) {
        return failureMessage(failure, "Modpack Operation Failed");
    }

    private static String failureMessage(Throwable failure, String fallback) {
        Throwable current = ResourceProviderException.unwrap(failure);
        String message = current == null ? null : current.getMessage();
        if (message == null || message.isBlank()) message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof Async.Cancellation) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private static RuntimeException rethrow(Throwable failure) {
        return failure instanceof RuntimeException exception ? exception : new IllegalStateException(failure);
    }

    private BrowserRemotelyServerApi.HostedModpackCapabilities unavailableModpackCapabilities(String provider, String reason) {
        BrowserRemotelyServerApi.HostedModpackAvailability unavailable =
                new BrowserRemotelyServerApi.HostedModpackAvailability(false, reason, "");
        return new BrowserRemotelyServerApi.HostedModpackCapabilities(serverId, provider, false,
                unavailable, unavailable, unavailable, unavailable);
    }

    private Async<Void> install(ResourceMarketplaceProvider.Card resource, ResourceMarketplaceProvider.Version selected,
                                ResourceMarketplaceProvider.VersionFile selectedFile, Runnable refreshed, OperationFence fence) {
        if (creationModpack(resource)) {
            return latestVersion(resource, selected, fence).thenCompose(version -> resolveDownload(resource, version, selectedFile).thenApply(download -> {
                if (!isCurrent(fence)) return null;
                String selectedLoader = version.loaders() == null || version.loaders().isEmpty() ? "" : version.loaders().getFirst();
                selectedLoader = selectedLoader == null ? "" : selectedLoader.replace("-", "").replace("_", "").toUpperCase(Locale.ROOT);
                String minecraftVersion = version.gameVersions() == null ? "" : version.gameVersions().stream()
                        .filter(value -> value != null && value.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?"))
                        .findFirst().orElse("");
                if (!Set.of("FABRIC", "FORGE", "NEOFORGE", "QUILT").contains(selectedLoader) || minecraftVersion.isBlank()) {
                    throw new IllegalStateException("This Modpack Version Has No Server Software");
                }
                modpackSelection.accept(new ModpackSelection(resource.title() + " Server", resource.provider(), resource.id(),
                        version.id(), version.number(), selectedLoader, minecraftVersion, download.url()));
                if (refreshed != null) refreshed.run();
                return null;
            }));
        }
        if (!installEligible(resource)) {
            return Async.failed(new UnsupportedOperationException("Resource Install Is Unavailable For This Server"));
        }
        return withCapabilities(() -> resourceDirectoriesAsync(fence).thenCompose(ignored -> installVersion(resource, selected, fence)
                .thenCompose(version -> resolveDownload(resource, version, selectedFile).thenCompose(download ->
                        pullFile(download.url(), resourceDirectory(resource), download.filename()))))
                .thenCompose(ignored -> refreshCanonicalInventory(fence)).thenApply(ignored -> {
                    if (!isCurrent(fence)) return null;
                    if (refreshed != null) refreshed.run();
                    return null;
                }), "files.read", "files.list", "files.pull");
    }

    Async<RemotePath> installWorld(ResourceMarketplaceProvider.Card resource, ResourceMarketplaceProvider.Version selected,
                                   String name, boolean replace) {
        if (!hasInstance() || resource == null || ResourceType.getTypeFromString(resource.type()) != ResourceType.WORLD
                || !marketplace.supportsType(resource.provider(), ResourceType.WORLD)) {
            return Async.failed(new UnsupportedOperationException("World Installation Is Unavailable"));
        }
        OperationFence fence = captureFence();
        return withCapabilities(() -> {
            Async<ServerModels.ServerStatus> status = replace ? api.getServerStatus(serverId)
                    : Async.completed(null);
            return status.thenCompose(value -> {
                if (replace && (value == null || value.currentState == null
                        || !Set.of("offline", "stopped").contains(value.currentState.toLowerCase(Locale.ROOT)))) {
                    return Async.failed(new IllegalStateException("Stop The Server Before Replacing Its Active World"));
                }
                return api.getFileContentAllowMissing(serverId, "server.properties")
                        .thenCompose(properties -> api.getFileContentAllowMissing(serverId, "bukkit.yml")
                                .thenCompose(bukkit -> {
                                    WorldArchiveInstaller.ServerLocation location = WorldArchiveInstaller.serverLocation(RemotePath.root(), properties, bukkit);
                                    return installVersion(resource, selected, fence).thenCompose(version ->
                                            resolveDownload(resource, version, null).thenCompose(download -> {
                                                WorldArchiveInstaller.Files files = worldFiles(download.url(), location, fence);
                                                String worldName = replace ? location.activeWorld() : name;
                                                return WorldArchiveInstaller.install(files, location.container(), download.filename(), worldName, replace)
                                                        .thenApply(path -> {
                                                            invalidateFileCache();
                                                            return path;
                                                        });
                                            }));
                                }));
            });
        }, "files.read", "files.list", "files.pull", "files.create-folder", "files.rename", "files.delete", "files.decompress");
    }

    private WorldArchiveInstaller.Files worldFiles(String url, WorldArchiveInstaller.ServerLocation location, OperationFence fence) {
        return new WorldArchiveInstaller.Files() {
            @Override public Async<Void> createDirectory(RemotePath path) {
                if (path.isRoot()) return Async.completed(null);
                return createDirectory(path.parent()).thenCompose(ignored -> exists(path).thenCompose(found -> found
                        ? Async.completed(null) : api.createFolder(serverId, path.parent().asString(), path.fileName())));
            }
            @Override public Async<Void> download(RemotePath path) {
                return api.pullFile(serverId, url, path.parent().asString(), path.fileName());
            }
            @Override public Async<Void> extract(RemotePath archive) {
                return api.decompressFile(serverId, archive.parent().asString(), archive.fileName());
            }
            @Override public Async<List<WorldArchiveInstaller.Entry>> list(RemotePath path) {
                return api.listResourceFiles(serverId, path.asString()).thenApply(entries -> (entries == null ? List.<ServerModels.PteroFileObjectAttributes>of() : entries).stream()
                        .map(entry -> new WorldArchiveInstaller.Entry(entry.name, !entry.isFile)).toList());
            }
            @Override public Async<Boolean> exists(RemotePath path) {
                if (path.isRoot()) return Async.completed(true);
                return api.listResourceFiles(serverId, path.parent().asString()).thenApply(entries -> entries != null && entries.stream()
                        .anyMatch(entry -> path.fileName().equals(entry.name)));
            }
            @Override public Async<Void> rename(RemotePath source, RemotePath target) {
                ServerModels.PteroFileRenameItem item = new ServerModels.PteroFileRenameItem();
                item.from = source.asString();
                item.to = target.asString();
                return api.renameFiles(serverId, "/", List.of(item));
            }
            @Override public Async<Void> delete(RemotePath path) {
                return api.deleteFiles(serverId, path.parent().asString(), List.of(path.fileName()));
            }
            @Override public Async<Void> beforeReplace() {
                if (!isDataCurrent(fence) || !isHostCurrent(fence)) {
                    return Async.failed(new IllegalStateException("Server Session Changed During Installation"));
                }
                return api.getServerStatus(serverId).thenCompose(status -> {
                    if (status == null || status.currentState == null
                            || !Set.of("offline", "stopped").contains(status.currentState.toLowerCase(Locale.ROOT))) {
                        return Async.failed(new IllegalStateException("Stop The Server Before Replacing Its Active World"));
                    }
                    return api.getFileContentAllowMissing(serverId, "server.properties")
                            .thenCompose(properties -> api.getFileContentAllowMissing(serverId, "bukkit.yml")
                                    .thenCompose(bukkit -> location.equals(WorldArchiveInstaller.serverLocation(RemotePath.root(), properties, bukkit))
                                            ? Async.completed(null) : Async.failed(new IllegalStateException("World Settings Changed During Installation"))));
                });
            }
        };
    }

    private Async<Void> update(ResourceMarketplaceProvider.Card resource, ResourceMarketplaceProvider.Version selected,
                               ResourceMarketplaceProvider.VersionFile selectedFile, Runnable refreshed, OperationFence fence) {
        if (!hasInstance() || !supportsResourceResolution(resource)) {
            return Async.failed(new UnsupportedOperationException("Resource Update Is Unavailable"));
        }
        return withCapabilities(() -> ensureInventory(fence).thenCompose(ignored -> {
            List<ResourceIndexOrchestrator.ResolvedEntry> files = canonicalResources(resource);
            if (files.isEmpty()) return Async.failed(new IllegalStateException("Installed Resource Is Unavailable"));
            return latestVersion(resource, selected, fence).thenCompose(target -> resolveDownload(resource, target, selectedFile)
                    .thenCompose(download -> pullFile(download.url(), directory(files.getFirst()), download.filename())
                            .thenCompose(done -> removeObsoleteFiles(files, download.filename()))));
        }).thenCompose(ignored -> refreshCanonicalInventory(fence)).thenApply(ignored -> {
            if (!isCurrent(fence)) return null;
            if (refreshed != null) refreshed.run();
            return null;
        }), "files.read", "files.list", "files.pull", "files.delete");
    }

    private Async<ResourceMarketplaceProviderAdapter.Download> resolveDownload(ResourceMarketplaceProvider.Card resource,
                                                                                ResourceMarketplaceProvider.Version version,
                                                                                ResourceMarketplaceProvider.VersionFile selectedFile) {
        if (selectedFile == null) return marketplace.resolveDownload(resource, version);
        return marketplace.resourceVersion(resource, version).thenCompose(found -> {
            if (found == null || found.files == null) return Async.failed(new IllegalStateException("Resource Download Is Unavailable"));
            OnlineResourceVersion.VersionFile target = found.files.stream().filter(Objects::nonNull)
                    .filter(candidate -> selectedFile.id() != null && !selectedFile.id().isBlank()
                            && selectedFile.id().equals(candidate.id)
                            || selectedFile.name() != null && selectedFile.name().equals(candidate.filename))
                    .findFirst().orElse(null);
            if (target == null || target.filename == null || target.filename.isBlank()) {
                return Async.failed(new IllegalStateException("Resource Download Is Unavailable"));
            }
            try {
                return marketplace.source(resource.provider()).getDirectFileURLAsync(found, target).thenCompose(url ->
                        url == null || url.isBlank() ? Async.failed(new IllegalStateException("Resource Download Is Unavailable"))
                                : Async.completed(new ResourceMarketplaceProviderAdapter.Download(url, target.filename)));
            } catch (Throwable failure) {
                return Async.failed(failure);
            }
        });
    }

    private Async<Void> delete(ResourceMarketplaceProvider.Card resource, Runnable refreshed, OperationFence fence) {
        if (!hasInstance() || !supportsResourceResolution(resource)) {
            return Async.failed(new UnsupportedOperationException("Resource Removal Is Unavailable"));
        }
        return withCapabilities(() -> ensureInventory(fence).thenCompose(ignored -> {
            List<ResourceIndexOrchestrator.ResolvedEntry> files = canonicalResources(resource);
            if (files.isEmpty()) return Async.completed(null);
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (ResourceIndexOrchestrator.ResolvedEntry file : files) grouped.computeIfAbsent(directory(file), ignoredDirectory -> new ArrayList<>()).add(file.fileName());
            Async<Void> result = Async.completed(null);
            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                result = result.thenCompose(done -> deleteFiles(entry.getKey(), entry.getValue()));
            }
            return result;
        }).thenCompose(ignored -> refreshCanonicalInventory(fence)).thenApply(ignored -> {
            if (!isCurrent(fence)) return null;
            if (refreshed != null) refreshed.run();
            return null;
        }), "files.read", "files.list", "files.delete");
    }

    private Async<ResourceMarketplaceProvider.Version> latestVersion(ResourceMarketplaceProvider.Card resource,
                                                                      ResourceMarketplaceProvider.Version selected,
                                                                      OperationFence fence) {
        if (selected != null) return Async.completed(selected);
        return versions(resource, fence).thenCompose(available -> available == null || available.isEmpty()
                ? Async.failed(new IllegalStateException("No Compatible Resource Version Is Available"))
                : Async.completed(available.stream().max(Comparator.comparing(HostedResourceContext::publishedAt)).orElse(available.getFirst())));
    }

    private Async<Void> removeObsoleteFiles(List<ResourceIndexOrchestrator.ResolvedEntry> files, String filename) {
        List<String> obsolete = files.stream().filter(file -> !Objects.equals(file.fileName(), filename))
                .map(ResourceIndexOrchestrator.ResolvedEntry::fileName).filter(name -> name != null && !name.isBlank()).distinct().toList();
        if (obsolete.isEmpty()) return Async.completed(null);
        return deleteFiles(directory(files.getFirst()), obsolete);
    }

    private Async<Void> ensureInventory(OperationFence fence) {
        if (!isCurrent(fence)) return staleOperation();
        return installedResources().thenApply(ignored -> null);
    }

    private Async<Void> refreshCanonicalInventory(OperationFence fence) {
        if (!isCurrent(fence)) return staleOperation();
        dataCache.canonicalResourceRequest = null;
        dataCache.canonicalResourceResult = null;
        return canonicalResourceIndex(true, fence).thenApply(ignored -> null);
    }

    private List<ResourceIndexOrchestrator.ResolvedEntry> canonicalResources(ResourceMarketplaceProvider.Card resource) {
        if (resource == null) return List.of();
        return dataCache.indexedResources.stream().filter(value -> value != null && value.metadata() != null
                && Objects.equals(value.metadata().provider(), resource.provider())
                && Objects.equals(value.metadata().projectId(), resource.id())).toList();
    }

    private static String directory(ResourceIndexOrchestrator.ResolvedEntry resource) {
        if (resource == null || resource.directoryPath() == null || resource.directoryPath().isBlank()) return "/";
        String path = resource.directoryPath().replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        return "/" + path;
    }

    private Async<ModpackProfile> ensureModpackProfile() {
        if (dataCache.modpackProfile != null) return Async.completed(dataCache.modpackProfile);
        return canonicalModpackProfile(dataCache.indexedResources, captureFence());
    }

    static ModpackProfile profile(String content, List<?> entries, ServerModels.ClientServerView server) {
        JsonObject root;
        try {
            root = BrowserJson.object(content);
        } catch (RuntimeException ignored) {
            return null;
        }
        boolean linked = server != null && server.environment != null
                && server.environment.get("MODPACK_PROJECT_ID") != null && !server.environment.get("MODPACK_PROJECT_ID").isBlank();
        List<JsonObject> declared = BrowserJson.objects(root, "content");
        if (!linked && declared.isEmpty() && BrowserJson.string(root, "projectId").isBlank()) return null;
        Set<String> preserved = new LinkedHashSet<>(BrowserJson.strings(root, "preservedConflicts").stream()
                .map(HostedResourceContext::resourcePath).filter(value -> !value.isBlank()).toList());
        Set<String> owned = new LinkedHashSet<>();
        for (JsonObject item : declared) {
            String path = resourcePath(BrowserJson.string(item, "path"));
            if (!path.isBlank() && !preserved.contains(path)) owned.add(path);
        }
        if (declared.isEmpty() && linked && entries != null) {
            JsonArray values = new JsonArray();
            for (Object value : entries) {
                if (value == null) continue;
                String path;
                String hash;
                String murmur2;
                if (value instanceof ResourceIndexOrchestrator.ResolvedEntry entry) {
                    path = resourcePath(entry.path());
                    hash = entry.hash();
                    murmur2 = entry.murmur2() == null ? null : Long.toString(entry.murmur2());
                } else {
                    continue;
                }
                if (path.isBlank()) continue;
                JsonObject item = new JsonObject();
                BrowserJson.put(item, "path", path);
                BrowserJson.put(item, "sha1", hash);
                BrowserJson.put(item, "murmur2", murmur2);
                values.add(item);
                if (!preserved.contains(path)) owned.add(path);
            }
            root.add("content", values);
        }
        String environmentProvider = environment(server, "MODPACK_PROVIDER");
        String environmentProject = environment(server, "MODPACK_PROJECT_ID");
        String environmentVersionId = environment(server, "MODPACK_VERSION_ID");
        String environmentVersion = environment(server, "MODPACK_VERSION_NUMBER");
        String name = first(BrowserJson.string(root, "name"), server == null ? null : server.name, "Installed Modpack");
        String provider = first(BrowserJson.string(root, "provider"), environmentProvider);
        String projectId = first(BrowserJson.string(root, "projectId"), environmentProject);
        String versionId = first(BrowserJson.string(root, "versionId"), environmentVersionId);
        String version = first(BrowserJson.string(root, "versionNumber"), environmentVersion, "Installed");
        BrowserJson.put(root, "name", name);
        BrowserJson.put(root, "provider", provider);
        BrowserJson.put(root, "projectId", projectId);
        BrowserJson.put(root, "versionId", versionId);
        BrowserJson.put(root, "versionNumber", version);
        JsonArray conflicts = new JsonArray();
        preserved.forEach(conflicts::add);
        root.add("preservedConflicts", conflicts);
        return new ModpackProfile(name, provider, projectId, versionId, version, owned, preserved, root.toString());
    }

    private Async<List<String>> resourceDirectoriesAsync() {
        return resourceDirectoriesAsync(captureFence());
    }

    private Async<List<String>> resourceDirectoriesAsync(OperationFence fence) {
        if (!hasInstance()) return Async.completed(List.of());
        if (dataCache.resourceDirectoriesRequest != null && isDataCurrent(fence)) return view(dataCache.resourceDirectoriesRequest);
        dataCache.resourceFailureMessages.remove("server.properties");
        dataCache.resourceFailureMessages.remove("/");
        Async<List<String>> request = api.getFileContentAllowMissing(serverId, "server.properties")
                .handle((content, failure) -> new OperationResult<>(content, failure))
                .thenCompose(result -> {
                    if (!isDataCurrent(fence)) return staleOperation();
                    if (result.failure() != null) {
                        recordResourceFailure("server.properties", result.failure());
                        dataCache.worldName = "world";
                        return Async.completed(resourceDirectories(loader, dataCache.worldName));
                    }
                    recordResourceFailure("server.properties", null);
                    dataCache.worldName = resolveWorldName(result.value());
                    List<String> directories = resourceDirectories(loader, dataCache.worldName);
                    return api.listResourceFiles(serverId, "/")
                            .handle((root, failure) -> new OperationResult<>(root, failure))
                            .thenCompose(rootResult -> {
                                if (!isDataCurrent(fence)) return staleOperation();
                                if (rootResult.failure() != null) {
                                    recordResourceFailure("/", rootResult.failure());
                                    return Async.completed(directories);
                                }
                                recordResourceFailure("/", null);
                                return Async.completed(mergeResourceDirectories(directories, rootResult.value()));
                            });
                });
        dataCache.resourceDirectoriesRequest = request;
        request.whenComplete((ignored, failure) -> {
            if (failure == null) return;
            if (dataCache.resourceDirectoriesRequest == request) dataCache.resourceDirectoriesRequest = null;
        });
        return view(request);
    }

    List<String> mergeResourceDirectories(List<String> defaults,
                                          List<ServerModels.PteroFileObjectAttributes> rootEntries) {
        LinkedHashSet<String> directories = new LinkedHashSet<>(defaults == null ? List.of() : defaults);
        if (rootEntries == null) return List.copyOf(directories);
        String normalizedWorld = normalizedWorldName(dataCache.worldName);
        for (ServerModels.PteroFileObjectAttributes entry : rootEntries) {
            if (entry == null || entry.isFile || entry.name == null || entry.name.isBlank()) continue;
            String name = entry.name.strip();
            switch (name.toLowerCase(Locale.ROOT)) {
                case "mods", "plugins", "resourcepacks", "shaderpacks" -> directories.add("/" + name);
                default -> {
                    if (name.equals(normalizedWorld)) directories.add("/" + name + "/datapacks");
                }
            }
        }
        return List.copyOf(directories);
    }

    private Async<List<ServerModels.PteroFileObjectAttributes>> listResourceDirectory(String directory) {
        String key = directory == null || directory.isBlank() ? "/" : directory;
        Async<List<ServerModels.PteroFileObjectAttributes>> cached = dataCache.resourceDirectoryRequests.get(key);
        if (cached != null) return view(cached);
        Async<List<ServerModels.PteroFileObjectAttributes>> request = api.listResourceFiles(serverId, key);
        dataCache.resourceDirectoryRequests.put(key, request);
        request.whenComplete((ignored, failure) -> {
            if (failure == null) return;
            if (dataCache.resourceDirectoryRequests.get(key) == request) dataCache.resourceDirectoryRequests.remove(key);
        });
        return view(request);
    }

    private void recordResourceFailure(String path, Throwable failure) {
        if (path == null || path.isBlank()) return;
        if (failure == null) {
            dataCache.resourceFailureMessages.remove(path);
        } else {
            dataCache.resourceFailureMessages.put(path, resourceFailureMessage(failure));
        }
    }

    List<ResourceFailure> resourceFailureSnapshot() {
        List<ResourceFailure> failures = new ArrayList<>();
        dataCache.resourceFailureMessages.forEach((path, message) -> failures.add(new ResourceFailure(path, message)));
        return List.copyOf(failures);
    }

    private static boolean isDiscoveryFailure(String path) {
        return "server.properties".equals(path) || "/".equals(path);
    }

    private static String resourceFailureMessage(Throwable failure) {
        return failureMessage(failure, "Resource Folder Unavailable");
    }

    private void notifyResourceListeners(OperationFence fence) {
        scheduleResourceNotification(fence);
    }

    private void scheduleResourceNotification(OperationFence fence) {
        if (!isCurrent(fence)) return;
        synchronized (this) {
            pendingResourceNotificationFence = fence;
            if (pendingResourceNotification != null) pendingResourceNotification.cancel();
            pendingResourceNotification = scheduler.schedule(this::dispatchResourceListeners, Duration.ofMillis(50));
        }
    }

    private void dispatchResourceListeners() {
        OperationFence fence;
        List<Consumer<ResourceBrowserContext.ResourceChange>> listeners;
        List<Consumer<CanonicalResourceInventory>> canonicalInventoryListeners;
        synchronized (this) {
            pendingResourceNotification = null;
            fence = pendingResourceNotificationFence;
            pendingResourceNotificationFence = null;
            listeners = List.copyOf(resourceListeners);
            canonicalInventoryListeners = List.copyOf(this.canonicalInventoryListeners);
        }
        if (!isCurrent(fence)) return;
        CanonicalResourceInventory canonicalSnapshot = new CanonicalResourceInventory(dataCache.indexedResources,
                dataCache.modpackProfile, resourceFailureSnapshot());
        canonicalInventoryListeners.forEach(listener -> listener.accept(canonicalSnapshot));
        listeners.forEach(listener -> listener.accept(new ResourceBrowserContext.ResourceChange(
                ResourceBrowserContext.ResourceChange.EventType.REFRESHED, List.of())));
    }

    private List<String> inventoryProviders() {
        List<String> providers = marketplace.providers() == null ? List.of() : List.copyOf(marketplace.providers());
        return prioritizeProvider(providers, lastProvider(providerPreferenceKey()));
    }

    static List<String> prioritizeProvider(List<String> providers, String preferred) {
        if (providers == null || providers.isEmpty()) return List.of();
        if (preferred == null || !providers.contains(preferred) || Objects.equals(providers.getFirst(), preferred)) return List.copyOf(providers);
        List<String> ordered = new ArrayList<>();
        ordered.add(preferred);
        providers.stream().filter(provider -> !Objects.equals(provider, preferred)).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private String providerPreferenceKey() {
        if (loader != null && !loader.isBlank()) return loader.toLowerCase(Locale.ROOT);
        return isServer() ? "server" : "default";
    }

    private boolean hasInstalled(ResourceMarketplaceProvider.Card resource) {
        return supportsResourceResolution(resource) && !canonicalResources(resource).isEmpty();
    }

    private boolean hasUpdate(ResourceMarketplaceProvider.Card resource) {
        return hasInstalled(resource) && canonicalResources(resource).stream()
                .anyMatch(value -> value.metadata() != null && value.metadata().availableUpdate() != null);
    }

    private ResourceMarketplaceProvider.Card installedCard(ResourceMarketplaceProvider.Card resource) {
        if (resource == null || !hasInstalled(resource)) return resource;
        boolean update = hasUpdate(resource);
        return new ResourceMarketplaceProvider.Card(resource.provider(), resource.id(), resource.slug(), resource.type(), resource.title(),
                resource.description(), resource.authors(), resource.downloads(), resource.followers(), resource.iconUrl(), resource.bannerUrl(),
                true, update, resource.categories(), resource.gameVersions(), resource.loaders(), resource.clientSide(), resource.serverSide());
    }

    private static String normalizedWorldName(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) continue;
            parts.add(part);
        }
        return parts.isEmpty() ? "world" : String.join("/", parts);
    }

    private static String resolveWorldName(String content) {
        if (content == null || content.isBlank()) return "world";
        for (String line : content.replace("\r", "").split("\n")) {
            String value = line.trim();
            if (value.isBlank() || value.startsWith("#")) continue;
            int separator = value.indexOf('=');
            if (separator <= 0 || !"level-name".equalsIgnoreCase(value.substring(0, separator).trim())) continue;
            return normalizedWorldName(value.substring(separator + 1));
        }
        return "world";
    }

    private static String normalizeHash(String hash) {
        if (hash == null) return "";
        String normalized = hash.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("sha1:") ? normalized.substring(5) : normalized;
    }

    private static String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return "";
        Long value = fingerprintValue(fingerprint);
        return value == null ? fingerprint.strip() : Long.toString(value);
    }

    private static Long fingerprintValue(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return null;
        try {
            long value = Long.parseLong(fingerprint.strip());
            return value >= 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant publishedAt(ResourceMarketplaceProvider.Version version) {
        if (version == null || version.publishedAt() == null || version.publishedAt().isBlank()) return Instant.EPOCH;
        try {
            Instant parsed = IsoTimes.parse(version.publishedAt());
            return parsed == null ? Instant.EPOCH : parsed;
        } catch (RuntimeException ignored) {
            return Instant.EPOCH;
        }
    }

    private static String resourceKey(ResourceMarketplaceProvider.Card resource) {
        return resource == null ? "" : resourceKey(resource.provider(), resource.id());
    }

    private static String resourceKey(String provider, String projectId) {
        return Objects.requireNonNullElse(provider, "") + ":" + Objects.requireNonNullElse(projectId, "");
    }

    private static String fileKey(String directory, String filename) {
        String normalizedDirectory = directory == null ? "" : directory.strip().replace('\\', '/');
        while (normalizedDirectory.startsWith("/")) normalizedDirectory = normalizedDirectory.substring(1);
        while (normalizedDirectory.endsWith("/")) normalizedDirectory = normalizedDirectory.substring(0, normalizedDirectory.length() - 1);
        String normalizedFilename = filename == null ? "" : filename.strip().replace('\\', '/');
        while (normalizedFilename.startsWith("/")) normalizedFilename = normalizedFilename.substring(1);
        return normalizedDirectory.isBlank() ? normalizedFilename : normalizedDirectory + "/" + normalizedFilename;
    }

    private static String hashPath(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        return normalized;
    }

    private void clearInventoryCache() {
        advanceLifecycleGeneration();
        dataCache.resourceHydrationGeneration++;
        List<Async<List<ServerModels.PteroFileObjectAttributes>>> directoryRequests =
                List.copyOf(dataCache.resourceDirectoryRequests.values());
        cancelPendingResourceNotification();
        cancel(dataCache.canonicalResourceRequest);
        cancel(dataCache.resourceHydrationRequest);
        cancel(dataCache.modpackProfileRequest);
        cancel(dataCache.resourceDirectoriesRequest);
        directoryRequests.forEach(HostedResourceContext::cancel);
        dataCache.canonicalResourceRequest = null;
        dataCache.canonicalResourceResult = null;
        dataCache.resourceHydrationRequest = null;
        dataCache.modpackProfileRequest = null;
        dataCache.modpackProfileScheduled = false;
        dataCache.resourceDirectoriesRequest = null;
        dataCache.indexedResources = List.of();
        dataCache.modpackProfile = null;
        dataCache.resourceDirectorySnapshots.clear();
        dataCache.resourceDirectoryRequests.clear();
        dataCache.resourceFailureMessages.keySet().removeIf(path -> !isDiscoveryFailure(path));
    }

    void invalidateFileCache() {
        captureFence();
        clearInventoryCache();
    }

    private static void clearDataCache(ResourceDataCache cache) {
        cache.resourceHydrationGeneration++;
        List<Async<List<ServerModels.PteroFileObjectAttributes>>> directoryRequests =
                List.copyOf(cache.resourceDirectoryRequests.values());
        cancel(cache.canonicalResourceRequest);
        cancel(cache.resourceHydrationRequest);
        cancel(cache.modpackProfileRequest);
        cancel(cache.resourceDirectoriesRequest);
        directoryRequests.forEach(HostedResourceContext::cancel);
        cache.canonicalResourceRequest = null;
        cache.canonicalResourceResult = null;
        cache.resourceHydrationRequest = null;
        cache.modpackProfileRequest = null;
        cache.modpackProfileScheduled = false;
        cache.resourceDirectoriesRequest = null;
        cache.indexedResources = List.of();
        cache.modpackProfile = null;
        cache.resourceDirectorySnapshots.clear();
        cache.resourceDirectoryRequests.clear();
        cache.resourceFailureMessages.clear();
    }

    private static <T> Async<T> staleOperation() {
        return Async.failed(new Async.Cancellation());
    }

    private <T> Async<T> view(Async<T> shared) {
        if (shared == null) return Async.completed(null);
        Async<T> result = Async.pending();
        SharedRequest request;
        synchronized (this) {
            request = sharedViews.get(shared);
            if (request == null) request = sharedRequests.get(shared);
            if (request == null) {
                request = new SharedRequest(shared);
                sharedRequests.put(shared, request);
            }
            request.subscribers++;
            sharedViews.put(result, request);
        }
        SharedRequest subscription = request;
        shared.whenComplete((value, failure) -> {
            if (!result.isCancelled()) {
                if (shared.isCancelled()) result.fail(new Async.Cancellation());
                else if (failure == null) result.complete(value);
                else result.fail(failure);
            }
            releaseSharedView(result, subscription);
        });
        result.onCancel(() -> releaseSharedView(result, subscription));
        return result;
    }

    private void releaseSharedView(Async<?> view, SharedRequest request) {
        boolean cancel = false;
        synchronized (this) {
            if (sharedViews.remove(view) == null || request.subscribers <= 0) return;
            request.subscribers--;
            if (request.subscribers == 0) {
                sharedRequests.remove(request.shared, request);
                cancel = !request.shared.isDone() && !ownsSharedRequest(request.shared);
            }
        }
        if (cancel) request.shared.cancel();
    }

    private boolean ownsSharedRequest(Async<?> shared) {
        if (shared == null) return false;
        ResourceDataCache cache = dataCache;
        if (cache.canonicalResourceRequest == shared || cache.resourceDirectoriesRequest == shared) return true;
        return cache.resourceDirectoryRequests.values().stream().anyMatch(value -> value == shared);
    }

    @Override
    public void invalidate() {
        retireResourceWork();
    }

    void invalidateForResourceSession() {
        retireResourceWork();
    }

    private void retireResourceWork() {
        cancelPendingResourceNotification();
        scheduler.cancelAll();
        clearDataCache(dataCache);
        modpackCapabilities.clear();
        synchronized (this) {
            sharedRequests.clear();
            sharedViews.clear();
        }
        cacheKey = null;
        advanceLifecycleGeneration();
    }

    private static void cancel(Async<?> request) {
        if (request != null && !request.isDone()) request.cancel();
    }

    private void cancelPendingResourceNotification() {
        synchronized (this) {
            if (pendingResourceNotification != null) pendingResourceNotification.cancel();
            pendingResourceNotification = null;
            pendingResourceNotificationFence = null;
        }
    }

    private OperationFence captureFence() {
        ensureDataCache();
        BrowserServerScreenHost host = remoteHost instanceof BrowserServerScreenHost value ? value : null;
        String subjectId = host == null ? launchSubjectId() : host.resourceSubjectId();
        if (subjectId == null || subjectId.isBlank()) subjectId = launchSubjectId();
        return new OperationFence(lifecycleGeneration, host, host == null ? 0L : host.resourceGeneration(),
                host == null ? 0L : host.resourceAuthGeneration(), BrowserLaunchSession.authenticated(), subjectId,
                BrowserLaunchSession.ticket(),
                ScreenManager.getInstance().getCurrentScreen());
    }

    private boolean isCurrent(OperationFence fence) {
        return isDataCurrent(fence) && isHostCurrent(fence) && fence.lifecycleGeneration() == lifecycleGeneration
                && (fence.screen() == null || ScreenManager.getInstance().isScreenActive(fence.screen()));
    }

    private boolean isDataCurrent(OperationFence fence) {
        if (fence == null || dataCache.key != null && (!Objects.equals(dataCache.key.subjectId(), fence.subjectId())
                || !Objects.equals(dataCache.key.serverId(), serverId)
                || dataCache.key.hostGeneration() != fence.hostGeneration()
                || dataCache.key.authGeneration() != fence.authGeneration()
                || !Objects.equals(dataCache.key.ticket(), fence.ticket()))) return false;
        if (fence.authenticated() != BrowserLaunchSession.authenticated()) return false;
        if (!Objects.equals(fence.ticket(), BrowserLaunchSession.ticket())) return false;
        String currentSubjectId = launchSubjectId();
        if (!fence.subjectId().isBlank() && !currentSubjectId.isBlank() && !Objects.equals(fence.subjectId(), currentSubjectId)) {
            return false;
        }
        return true;
    }

    private boolean isHostCurrent(OperationFence fence) {
        if (fence.host() != null && (!fence.host().resourceAlive() || fence.host().resourceGeneration() != fence.hostGeneration()
                || fence.host().resourceAuthGeneration() != fence.authGeneration()
                || !Objects.equals(fence.host().resourceSubjectId(), fence.subjectId())
                || !Objects.equals(fence.host().resourceTicket(), fence.ticket()))) {
            return false;
        }
        return true;
    }

    private String resourceSubjectId() {
        BrowserServerScreenHost host = remoteHost instanceof BrowserServerScreenHost value ? value : null;
        String subjectId = host == null ? launchSubjectId() : host.resourceSubjectId();
        if (subjectId == null || subjectId.isBlank()) subjectId = launchSubjectId();
        return subjectId == null ? "" : subjectId;
    }

    private static String launchSubjectId() {
        try {
            return BrowserLaunchSession.metadata().subjectId();
        } catch (UnsatisfiedLinkError ignored) {
            return "";
        }
    }

    private ResourceCacheKey resourceCacheKey() {
        if (serverId.isBlank()) return null;
        BrowserServerScreenHost host = remoteHost instanceof BrowserServerScreenHost value ? value : null;
        return new ResourceCacheKey(resourceSubjectId(), serverId, Objects.requireNonNullElse(version, ""),
                Objects.requireNonNullElse(loader, ""), host == null ? 0L : host.resourceGeneration(),
                host == null ? 0L : host.resourceAuthGeneration(), BrowserLaunchSession.ticket());
    }

    private void advanceLifecycleGeneration() {
        lifecycleGeneration++;
        if (lifecycleGeneration <= 0) lifecycleGeneration = 1L;
    }

    private void ensureDataCache() {
        ResourceCacheKey currentKey = resourceCacheKey();
        if (Objects.equals(cacheKey, currentKey)) return;
        ResourceDataCache previous = dataCache;
        cacheKey = currentKey;
        dataCache = new ResourceDataCache(currentKey);
        if (previous != null && previous != dataCache) {
            cancelPendingResourceNotification();
            scheduler.cancelAll();
            clearDataCache(previous);
        }
        modpackCapabilities.clear();
        advanceLifecycleGeneration();
    }

    private record ResourceCacheKey(String subjectId, String serverId, String version, String loader, long hostGeneration,
                                    long authGeneration, String ticket) {
    }

    private static final class ResourceDataCache {
        private final ResourceCacheKey key;
        private List<ResourceIndexOrchestrator.ResolvedEntry> indexedResources = List.of();
        private final Map<String, String> resourceFailureMessages = new LinkedHashMap<>();
        private final Map<String, List<ResourceIndexOrchestrator.Entry>> resourceDirectorySnapshots = new LinkedHashMap<>();
        private final Map<String, Async<List<ServerModels.PteroFileObjectAttributes>>> resourceDirectoryRequests = new LinkedHashMap<>();
        private Async<ResourceIndexOrchestrator.Result> canonicalResourceRequest;
        private ResourceIndexOrchestrator.Result canonicalResourceResult;
        private Async<ResourceIndexOrchestrator.Result> resourceHydrationRequest;
        private long resourceHydrationGeneration;
        private Async<ModpackProfile> modpackProfileRequest;
        private boolean modpackProfileScheduled;
        private Async<List<String>> resourceDirectoriesRequest;
        private String worldName = "world";
        private ModpackProfile modpackProfile;

        private ResourceDataCache(ResourceCacheKey key) {
            this.key = key;
        }
    }

    private static final class SharedRequest {
        private final Async<?> shared;
        private int subscribers;

        private SharedRequest(Async<?> shared) {
            this.shared = shared;
        }
    }

    private record OperationResult<T>(T value, Throwable failure) {
    }

    private record MetadataResult(OperationResult<ResourceMarketplaceProvider.Details> details,
                                         OperationResult<List<ResourceMarketplaceProvider.Version>> versions) {
    }

    record ResourceFailure(String path, String message) {
        ResourceFailure {
            path = path == null || path.isBlank() ? "/" : path;
            message = message == null || message.isBlank() ? "Resource Unavailable" : message;
        }

        String detail() {
            return path + ": " + message;
        }
    }

    record CanonicalResourceInventory(List<ResourceIndexOrchestrator.ResolvedEntry> entries, ModpackProfile modpack,
                                      List<ResourceFailure> failures) {
        CanonicalResourceInventory {
            entries = entries == null ? List.of() : List.copyOf(entries);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        String warning() {
            return failures.stream().map(ResourceFailure::detail).reduce((left, right) -> left + "; " + right).orElse("");
        }
    }

    record ResourceIndexMatch(String provider, OnlineResourceVersion version) {
    }

    record ModpackProfile(String name, String provider, String projectId, String versionId, String version,
                                 Set<String> ownedPaths, Set<String> preservedPaths, String json) {
        ModpackProfile {
            ownedPaths = ownedPaths == null ? Set.of() : Set.copyOf(ownedPaths);
            preservedPaths = preservedPaths == null ? Set.of() : Set.copyOf(preservedPaths);
        }

        boolean owns(String path) {
            return ownedPaths.contains(resourcePath(path));
        }

        ModpackProfile detach(String path) {
            String normalized = resourcePath(path);
            if (!ownedPaths.contains(normalized)) return this;
            Set<String> owned = new LinkedHashSet<>(ownedPaths);
            owned.remove(normalized);
            Set<String> preserved = new LinkedHashSet<>(preservedPaths);
            preserved.add(normalized);
            JsonObject root = BrowserJson.object(json);
            JsonArray values = new JsonArray();
            preserved.stream().sorted().forEach(values::add);
            root.add("preservedConflicts", values);
            return new ModpackProfile(name, provider, projectId, versionId, version, owned, preserved, root.toString());
        }
    }

    record ModpackSelection(String name, String provider, String projectId, String versionId, String versionNumber,
                            String loader, String minecraftVersion, String downloadUrl) {
    }

    private Async<ResourceMarketplaceProvider.Version> installVersion(ResourceMarketplaceProvider.Card resource,
                                                                       ResourceMarketplaceProvider.Version selected,
                                                                       OperationFence fence) {
        if (selected != null) return Async.completed(selected);
        return versions(resource, fence).thenCompose(available -> available == null || available.isEmpty()
                ? Async.failed(new IllegalStateException("No Compatible Resource Version Is Available"))
                : Async.completed(available.getFirst()));
    }

    private boolean installSupported(ResourceMarketplaceProvider.Card resource) {
        return capabilitiesAvailable("files.read", "files.list", "files.pull") && installEligible(resource);
    }

    private boolean creationModpack(ResourceMarketplaceProvider.Card resource) {
        return !hasInstance() && modpackSelection != null && resource != null
                && ResourceType.getTypeFromString(resource.type()) == ResourceType.MODPACK;
    }

    private boolean installEligible(ResourceMarketplaceProvider.Card resource) {
        if (resource == null || api == null || serverId.isBlank() || marketplace == null
                || !marketplace.providers().contains(resource.provider())) return false;
        if (!marketplace.source(resource.provider()).supportsDownloads()) return false;
        ResourceType type = ResourceType.getTypeFromString(resource.type());
        if (!marketplace.supportsType(resource.provider(), type)) return false;
        return type == ResourceType.MOD && supportsMods() || type == ResourceType.PLUGIN && supportsPlugins()
                || type == ResourceType.DATA_PACK || type == ResourceType.RESOURCE_PACK || type == ResourceType.SHADER_PACK;
    }

    private boolean supportsResourceResolution(ResourceMarketplaceProvider.Card resource) {
        if (resource == null || marketplace == null || !marketplace.providers().contains(resource.provider())) return false;
        AsyncResourceProvider source = marketplace.source(resource.provider());
        return source.supportsHashLookup() || source.supportsFingerprintLookup();
    }

    private Async<Void> pullFile(String url, String directory, String filename) {
        return withCapabilities(() -> api.pullFile(serverId, url, directory, filename), "files.pull");
    }

    private Async<Void> deleteFiles(String directory, List<String> files) {
        return withCapabilities(() -> api.deleteFiles(serverId, directory, files), "files.delete");
    }

    private boolean capabilitiesAvailable(String... actions) {
        for (String action : actions) {
            if (!serverCapabilities.availability(server, action).available()) return false;
        }
        return true;
    }

    private CapabilityDescriptor mutationCapability(String id, String fallback, String... actions) {
        if (!hasInstance()) return CapabilityDescriptor.unavailable(id, fallback);
        ServerUiCapabilityProvider.Availability availability = unavailableCapability(actions);
        return availability == null ? CapabilityDescriptor.supported(id)
                : CapabilityDescriptor.unavailable(id, reason(availability, fallback));
    }

    private <T> Async<T> withCapabilities(Supplier<Async<T>> operation, String... actions) {
        ServerUiCapabilityProvider.Availability unavailable = unavailableCapability(actions);
        if (unavailable == null) return operation.get();
        if ("Server Capabilities Are Loading".equals(unavailable.reason())) {
            return serverCapabilities.refresh(server).thenCompose(ignored -> {
                ServerUiCapabilityProvider.Availability refreshed = unavailableCapability(actions);
                return refreshed == null ? operation.get() : Async.failed(new UnsupportedOperationException(reason(refreshed, "Server File Capability Is Unavailable")));
            });
        }
        return Async.failed(new UnsupportedOperationException(reason(unavailable, "Server File Capability Is Unavailable")));
    }

    private ServerUiCapabilityProvider.Availability unavailableCapability(String... actions) {
        for (String action : actions) {
            ServerUiCapabilityProvider.Availability availability = serverCapabilities.availability(server, action);
            if (!availability.available()) return availability;
        }
        return null;
    }

    private static String reason(ServerUiCapabilityProvider.Availability availability, String fallback) {
        return availability == null || availability.reason().isBlank() ? fallback : availability.reason();
    }

    static String resourceGroupContext(String serverId) {
        return "resources:" + Objects.requireNonNullElse(serverId, "");
    }

    private static String resourcePath(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.endsWith(".disabled") ? normalized.substring(0, normalized.length() - ".disabled".length()) : normalized;
    }

    private static String environment(ServerModels.ClientServerView server, String key) {
        return server == null || server.environment == null ? null : server.environment.get(key);
    }

    private static String first(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value;
            }
        }
        return "";
    }

    private static ServerModels.ClientServerView serverView(String serverId) {
        ServerModels.ClientServerView server = new ServerModels.ClientServerView();
        server.identifier = serverId == null ? "" : serverId;
        return server;
    }

    private String resourceDirectory(ResourceMarketplaceProvider.Card resource) {
        ResourceType type = ResourceType.getTypeFromString(resource.type());
        return type == ResourceType.PLUGIN ? "/plugins" : type == ResourceType.DATA_PACK
                ? "/" + normalizedWorldName(dataCache.worldName) + "/datapacks" : type == ResourceType.RESOURCE_PACK
                ? "/resourcepacks" : type == ResourceType.SHADER_PACK ? "/shaderpacks" : "/mods";
    }

    @Override
    public void saveCollection(Object collection, List<ResourceMarketplaceProvider.Card> entries, Runnable callback) {
        if (callback != null) callback.run();
    }

    @Override
    public void acceptSelection(Object callback, List<ResourceMarketplaceProvider.Card> entries) {
        if (!(callback instanceof Consumer<?> consumer)) {
            log("Resource Selection Unavailable", new IllegalArgumentException("Resource Selection Callback Is Unavailable"));
            return;
        }
        try {
            @SuppressWarnings("unchecked") Consumer<List<ResourceMarketplaceProvider.Card>> typed =
                    (Consumer<List<ResourceMarketplaceProvider.Card>>) consumer;
            typed.accept(entries == null ? List.of() : List.copyOf(entries));
        } catch (RuntimeException failure) {
            log("Resource Selection Failed", failure);
        }
    }

    @Override
    public void openResource(Screen parent, ResourceMarketplaceProvider source, ResourceMarketplaceProvider.Card resource,
                             ResourceType type, boolean server, Object remoteHost, boolean reStudioContext,
                             boolean replacement, Runnable changeCallback) {
        if (!(parent instanceof ReScreen reScreen)) return;
        OperationFence fence = captureFence();
        if (hasInstance() && (dataCache.canonicalResourceRequest == null || !dataCache.canonicalResourceRequest.isDone())) {
            installedResources().whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
                if (!isCurrent(fence)) return;
                if (failure != null) log("Installed Resource State Unavailable", failure);
                openResourceScreen(reScreen, source, resource, type, server, remoteHost, reStudioContext, replacement, changeCallback);
            }));
            return;
        }
        if (!isCurrent(fence)) return;
        openResourceScreen(reScreen, source, resource, type, server, remoteHost, reStudioContext, replacement, changeCallback);
    }

    private void openResourceScreen(ReScreen parent, ResourceMarketplaceProvider source, ResourceMarketplaceProvider.Card resource,
                                    ResourceType type, boolean server, Object remoteHost, boolean reStudioContext,
                                    boolean replacement, Runnable changeCallback) {
        ResourceOverviewScreen screen = new ResourceOverviewScreen(parent,
                new HostedResourceOverviewProvider(this, source, installedCard(resource), type, replacement, changeCallback));
        if (screen.getDesktopAppId().equals(parent.getDesktopAppId())) {
            ScreenManager.getInstance().navigate(parent, screen);
        } else {
            ScreenManager.getInstance().setScreen(screen);
        }
    }

    @Override
    public void log(String message, Throwable failure) {
        String title = message == null || message.isBlank() ? "Resource Operation Failed" : message;
        String detail = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "The resource operation could not be completed." : failure.getMessage();
        new Notification(title, detail, Notification.Type.ERROR);
    }

    @Override
    public Object captureOperation() {
        return captureFence();
    }

    @Override
    public boolean isOperationCurrent(Object operation, Screen screen) {
        return operation instanceof OperationFence fence && isCurrent(fence)
                && (screen == null || ScreenManager.getInstance().isScreenActive(screen));
    }

    private record OperationFence(long lifecycleGeneration, BrowserServerScreenHost host, long hostGeneration,
                                  long authGeneration, boolean authenticated, String subjectId, String ticket, Screen screen) {
    }
}
