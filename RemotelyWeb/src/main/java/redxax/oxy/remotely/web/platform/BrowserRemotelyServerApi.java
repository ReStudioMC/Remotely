package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import redxax.oxy.remotely.RemotelyCapabilityException;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceApi;
import redxax.oxy.remotely.ui.server.NetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rebase.backend.CapabilityDescriptor;
import restudio.rebase.backend.CapabilityIds;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.backend.GitJobProvider;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.TerminalSession;
import restudio.rebase.backend.TerminalSessionProvider;
import restudio.rebase.backend.TerminalSize;
import restudio.rebase.backend.TerminalCapability;
import restudio.rebase.backend.TerminalTransport;
import restudio.rebase.backend.TransferSink;
import restudio.rebase.backend.TransferSource;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.http.HttpRequest;
import restudio.rescreen.platform.http.HttpResponse;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.rescreen.platform.websocket.BinaryWebSocket;
import restudio.rescreen.platform.websocket.BinaryWebSocketListener;
import restudio.rescreen.platform.websocket.WebSocketOptions;
import restudio.rescreen.platform.websocket.WebSocketTransport;
import restudio.rescreen.platform.browser.BrowserHostActionHandler;
import restudio.rescreen.platform.browser.BrowserFile;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.settings.controllers.VersionSettingsCatalog.GameVersion;
import restudio.rebase.settings.controllers.VersionSettingsCatalog.ModLoaderVersion;
import restudio.rebase.settings.controllers.VersionSettingsCatalog;
import restudio.rebase.api.git.data.GitBranch;
import restudio.rebase.api.git.data.GitCommit;
import restudio.rebase.api.git.data.GitFileStatus;
import restudio.rebase.api.git.data.GitStatus;
import restudio.rebase.api.git.data.GitStashEntry;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.schedule.ServerScheduleCapabilityClient;
import restudio.rebase.schedule.ServerScheduleModels;
import restudio.rebase.restudio.api.ReStudioResourceCapabilityClient;
import restudio.rescreen.util.IsoTimes;

import java.net.URI;
import java.net.URLEncoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class BrowserRemotelyServerApi implements RemotelyServerApi, BrowserServerFileTransfer.UploadApi {
    @FunctionalInterface
    private interface BrowserJsonDecoder<T> {
        T decode(JsonObject value);
    }

    private static final String ALLOCATION_LIMIT_CODE = "remotely_web_allocation_limit_reached";
    private static final String LEGACY_ALLOCATION_LIMIT_CODE = "allocation_limit_reached";
    private static final long MISSING_RESYNC_CACHE_MILLIS = 30_000L;
    private static final Duration JOB_POLL_DELAY = Duration.ofMillis(200);
    private static final Duration MANAGER_POLL_INITIAL_DELAY = Duration.ofSeconds(1);
    private static final Duration MANAGER_POLL_PERIOD = Duration.ofSeconds(5);
    private static final Duration BROWSER_READ_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(2);
    private static final Duration BROWSER_READ_CACHE = Duration.ofMillis(750);
    private static final int MAX_BROWSER_READ_REQUESTS = 128;
    private static final int MAX_BROWSER_READ_COOLDOWNS = 128;
    private static final int MAX_BROWSER_READ_VALUES = 128;
    private static final int MAX_GLYPH_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final long JOB_TIMEOUT_MILLIS = 30_000;
    private final HttpTransport transport;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final WebSocketTransport webSocket;
    private final BrowserReSyncMarketplaceApi marketplace;
    private final BrowserApplicationHost host;
    private final ReStudioResourceCapabilityClient resourceApi;
    private final ServerScheduleCapabilityClient scheduleClient;
    private final Map<String, Consumer<DeveloperCapabilityProvider.JobProgress>> developerProgress = new LinkedHashMap<>();
    private final Set<BrowserTerminalTransport> activeTerminalTransports = new HashSet<>();
    private final Set<UUID> activeConsoleSessions = new HashSet<>();
    private final Map<String, Long> missingReSyncServers = new LinkedHashMap<>();
    private final Map<String, Long> missingReSyncApiKeys = new LinkedHashMap<>();
    private final Map<String, Long> missingReSyncVersions = new LinkedHashMap<>();
    private final Object browserReadLock = new Object();
    private final Map<String, BrowserReadOwner> browserReadRequests = new LinkedHashMap<>();
    private final Map<String, BrowserReadCooldown> browserReadCooldowns = new LinkedHashMap<>();
    private final Map<String, BrowserReadValue> browserReadValues = new LinkedHashMap<>();
    private final Runnable browserReadAuthListener = this::invalidateBrowserReadCache;
    private final Runnable browserReadTicketListener = this::invalidateBrowserReadCache;
    private final Runnable browserReadExpiryListener = this::invalidateBrowserReadCache;
    private String negativeReSyncCacheTicket = "";
    private final Map<Object, Set<Consumer<ManagerSnapshot>>> managerInstanceListeners = new IdentityHashMap<>();
    private final Map<Object, Set<Consumer<ManagerSnapshot>>> managerNetworkListeners = new IdentityHashMap<>();
    private final Map<Object, Set<Consumer<ManagerSnapshot>>> managerRuntimeListeners = new IdentityHashMap<>();
    private final String baseUrl;
    private TaskScheduler.ScheduledTask managerPollingTask;
    private Async<ManagerSnapshot> managerPollRequest;
    private ManagerSnapshot managerSnapshot;
    private boolean managerPollInFlight;
    private boolean managerPollRequested;
    private long managerPollGeneration;
    private long browserReadEpoch = 1L;
    private boolean closed;

    public BrowserRemotelyServerApi(HttpTransport transport, Clock clock, TaskScheduler scheduler,
                                    WebSocketTransport webSocket, BrowserLaunchSession.Metadata session,
                                    BrowserApplicationHost host) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.webSocket = Objects.requireNonNull(webSocket, "webSocket");
        this.host = host;
        marketplace = new BrowserReSyncMarketplaceApi(transport, session, host);
        baseUrl = BrowserLaunchSession.capabilityBaseUrl();
        scheduleClient = new ServerScheduleCapabilityClient(this::request);
        resourceApi = new ReStudioResourceCapabilityClient(transport);
        resourceApi.setBaseUrl(BrowserLaunchSession.apiBaseUrl());
        resourceApi.useSessionCookies();
        BrowserLaunchSession.addAuthStateListener(browserReadAuthListener);
        BrowserLaunchSession.addTicketListener(browserReadTicketListener);
        BrowserLaunchSession.addSessionExpiryListener(browserReadExpiryListener);
    }

    public void close() {
        BrowserLaunchSession.removeAuthStateListener(browserReadAuthListener);
        BrowserLaunchSession.removeTicketListener(browserReadTicketListener);
        BrowserLaunchSession.removeSessionExpiryListener(browserReadExpiryListener);
        List<BrowserReadOwner> requests;
        synchronized (browserReadLock) {
            if (closed) return;
            closed = true;
            browserReadEpoch++;
            requests = List.copyOf(browserReadRequests.values());
            browserReadRequests.clear();
            browserReadCooldowns.clear();
            browserReadValues.clear();
        }
        requests.forEach(BrowserReadOwner::cancelOwner);
    }

    void invalidateReadCache() {
        invalidateBrowserReadCache();
    }

    private void invalidateBrowserReadCache() {
        List<BrowserReadOwner> requests;
        synchronized (browserReadLock) {
            browserReadEpoch++;
            requests = List.copyOf(browserReadRequests.values());
            browserReadRequests.clear();
            browserReadCooldowns.clear();
            browserReadValues.clear();
        }
        requests.forEach(BrowserReadOwner::cancelOwner);
    }

    private static void cancel(Async<?> request) {
        if (request != null && !request.isDone()) request.cancel();
    }

    private Async<String> view(BrowserReadOwner owner) {
        return owner.view();
    }

    public BrowserLaunchSession.Metadata session() {
        return BrowserLaunchSession.metadata();
    }

    @Override
    public ReSyncMarketplaceApi marketplace() {
        return marketplace;
    }

    public BrowserReSyncMarketplaceApi browserMarketplace() {
        return marketplace;
    }

    HttpTransport transport() {
        return transport;
    }

    @Override
    public NetworkOverviewProvider networkOverviewProvider(RemotelyClient client) {
        return new BrowserNetworkOverviewProvider(this, client);
    }

    Async<JsonObject> networkRequest(String method, String endpoint, Object body) {
        return request(method, endpoint, body == null ? null : json(body)).thenApply(BrowserJson::object);
    }

    Async<JsonElement> networkValueRequest(String method, String endpoint, Object body) {
        return request(method, endpoint, body == null ? null : json(body)).thenApply(BrowserJson::parse);
    }

    static String pathValue(String value) {
        return path(value);
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> getServers() {
        return getList("/servers", BrowserRemotelyServerApi::serverView).thenApply(value -> value == null ? List.of()
                : value.stream().filter(Objects::nonNull).map(ServerView::toModel).toList());
    }

    @Override
    public Async<List<ServerModels.Plan>> getPlans() {
        return apiRequest("GET", "/remotely-web/plans", null).thenApply(response -> {
            List<ServerModels.Plan> result = new ArrayList<>();
            BrowserJson.array(response).forEach(element -> {
                if (element == null || !element.isJsonObject()) return;
                JsonObject value = element.getAsJsonObject();
                ServerModels.Plan plan = new ServerModels.Plan();
                plan.id = BrowserJson.string(value, "id");
                plan.name = BrowserJson.string(value, "name");
                plan.memoryMb = BrowserJson.integer(value, "memoryMb", 0);
                plan.diskMb = BrowserJson.integer(value, "diskMb", 0);
                plan.cpuPercent = BrowserJson.integer(value, "cpuPercent", 0);
                plan.databases = BrowserJson.integer(value, "databases", 0);
                plan.backups = BrowserJson.integer(value, "backups", 0);
                plan.allocations = BrowserJson.integer(value, "allocations", 0);
                plan.priceCents = BrowserJson.longValue(value, "priceCents", 0L);
                result.add(plan);
            });
            return List.copyOf(result);
        });
    }

    public Async<List<GameVersion>> getCatalogGameVersions() {
        return request("GET", "/catalog/game-versions", null).thenApply(response -> {
            List<GameVersion> result = new ArrayList<>();
            BrowserJson.array(response).forEach(element -> {
                if (element == null || !element.isJsonObject()) return;
                JsonObject value = element.getAsJsonObject();
                String id = BrowserJson.string(value, "id");
                result.add(new GameVersion(id, BrowserJson.string(value, "type"), BrowserJson.string(value, "baseVersion"),
                        BrowserJson.string(value, "releaseTime")));
            });
            return List.copyOf(result);
        });
    }

    public Async<List<ModLoaderVersion>> getCatalogModLoaderVersions(ModLoader loader, String gameVersion) {
        return request("GET", "/catalog/mod-loaders/" + path(loader.name()) + "/versions?gameVersion=" + query(gameVersion), null).thenApply(response -> {
            List<ModLoaderVersion> result = new ArrayList<>();
            BrowserJson.array(response).forEach(element -> {
                if (element == null || !element.isJsonObject()) return;
                String version = BrowserJson.string(element.getAsJsonObject(), "version");
                if (version != null && !version.isBlank()) result.add(new ModLoaderVersion(loader, version, version));
            });
            return List.copyOf(result);
        });
    }

    public Async<Map<String, VersionSettingsCatalog.Software>> getCatalogSoftware() {
        return request("GET", "/catalog/software", null).thenApply(BrowserRemotelyServerApi::catalogSoftware);
    }

    public Async<Map<String, VersionSettingsCatalog.Version>> getCatalogVersions(String software) {
        return request("GET", "/catalog/software/" + path(software) + "/versions", null).thenApply(response -> {
            Map<String, VersionSettingsCatalog.Version> result = new LinkedHashMap<>();
            BrowserJson.object(response).entrySet().forEach(entry -> {
                if (!entry.getValue().isJsonObject()) return;
                JsonObject value = entry.getValue().getAsJsonObject();
                result.put(entry.getKey(), new VersionSettingsCatalog.Version(BrowserJson.string(value, "version"), BrowserJson.bool(value, "supported", true), BrowserJson.integer(value, "builds", 0), BrowserJson.string(value, "latestBuild"), BrowserJson.string(value, "latestBuildDisplayName"), BrowserJson.string(value, "created")));
            });
            return Map.copyOf(result);
        });
    }

    public Async<List<VersionSettingsCatalog.Build>> getCatalogBuilds(String software, String version) {
        return request("GET", "/catalog/software/" + path(software) + "/versions/" + path(version) + "/builds", null).thenApply(response -> {
            List<VersionSettingsCatalog.Build> result = new ArrayList<>();
            BrowserJson.array(response).forEach(element -> {
                if (element == null || !element.isJsonObject()) return;
                JsonObject value = element.getAsJsonObject();
                result.add(new VersionSettingsCatalog.Build(BrowserJson.string(value, "buildNumber"), BrowserJson.string(value, "displayName")));
            });
            return List.copyOf(result);
        });
    }

    public Async<HostedCatalog> getHostedCatalog() {
        return request("GET", "/catalog/hosted", null).thenApply(response -> hostedCatalog(BrowserJson.object(response)));
    }

    public Async<HostedServerCatalog> getHostedServerCatalog(String serverId) {
        return request("GET", "/catalog/servers/" + path(serverId) + "/hosted", null).thenApply(response -> hostedServerCatalog(BrowserJson.object(response)));
    }

    public Async<List<ServerScreenHost.NetworkView>> getNetworks() {
        return networkValueRequest("GET", "/networks", null).thenApply(response -> {
            List<ServerScreenHost.NetworkView> result = new ArrayList<>();
            if (response == null || !response.isJsonArray()) return List.of();
            response.getAsJsonArray().forEach(element -> {
                if (element == null || !element.isJsonObject()) return;
                JsonObject value = element.getAsJsonObject();
                result.add(new ServerScreenHost.NetworkView(BrowserJson.string(value, "id"), BrowserJson.string(value, "name"),
                        BrowserJson.string(value, "status"), BrowserJson.string(value, "description"), BrowserJson.strings(value, "members"),
                        BrowserJson.bool(value, "managed", true), BrowserJson.string(value, "proxyId")));
            });
            return List.copyOf(result);
        });
    }

    void addInstanceChangeListener(Object owner, Consumer<ManagerSnapshot> listener) {
        addManagerListener(managerInstanceListeners, owner, listener);
    }

    void removeInstanceChangeListener(Object owner, Consumer<ManagerSnapshot> listener) {
        removeManagerListener(managerInstanceListeners, owner, listener);
    }

    void addNetworkChangeListener(Object owner, Consumer<ManagerSnapshot> listener) {
        addManagerListener(managerNetworkListeners, owner, listener);
    }

    void removeNetworkChangeListener(Object owner, Consumer<ManagerSnapshot> listener) {
        removeManagerListener(managerNetworkListeners, owner, listener);
    }

    void addRuntimeChangeListener(Object owner, Consumer<ManagerSnapshot> listener) {
        addManagerListener(managerRuntimeListeners, owner, listener);
    }

    void removeRuntimeChangeListener(Object owner, Consumer<ManagerSnapshot> listener) {
        removeManagerListener(managerRuntimeListeners, owner, listener);
    }

    void removeManagerPollingOwner(Object owner) {
        if (owner == null) return;
        managerInstanceListeners.remove(owner);
        managerNetworkListeners.remove(owner);
        managerRuntimeListeners.remove(owner);
        stopManagerPollingIfUnused();
    }

    void resetManagerSnapshot(Object owner) {
        if (owner == null || !hasManagerOwner(owner)) return;
        clearMissingReSync();
        managerSnapshot = null;
        managerPollRequested = true;
        managerPollGeneration++;
        Async<ManagerSnapshot> request = managerPollRequest;
        managerPollRequest = null;
        managerPollInFlight = false;
        if (request != null && !request.isDone()) request.cancel();
        if (BrowserLaunchSession.authenticated()) requestManagerPoll();
    }

    private boolean isMissingReSync(Map<String, Long> cache, String serverId) {
        prepareMissingReSyncCache();
        synchronized (missingReSyncServers) {
            Long expiresAt = cache.get(serverId);
            return expiresAt != null && expiresAt > clock.millis();
        }
    }

    private void rememberMissingReSync(Map<String, Long> cache, String serverId) {
        prepareMissingReSyncCache();
        synchronized (missingReSyncServers) {
            cache.put(serverId, clock.millis() + MISSING_RESYNC_CACHE_MILLIS);
        }
    }

    private void clearMissingReSync(String serverId) {
        prepareMissingReSyncCache();
        synchronized (missingReSyncServers) {
            missingReSyncServers.remove(serverId);
            missingReSyncApiKeys.remove(serverId);
            missingReSyncVersions.remove(serverId);
        }
    }

    private void clearMissingReSync() {
        prepareMissingReSyncCache();
        synchronized (missingReSyncServers) {
            missingReSyncServers.clear();
            missingReSyncApiKeys.clear();
            missingReSyncVersions.clear();
        }
    }

    private void prepareMissingReSyncCache() {
        String ticket = BrowserLaunchSession.ticket();
        long now = clock.millis();
        synchronized (missingReSyncServers) {
            if (!Objects.equals(negativeReSyncCacheTicket, ticket)) {
                missingReSyncServers.clear();
                missingReSyncApiKeys.clear();
                missingReSyncVersions.clear();
                negativeReSyncCacheTicket = ticket;
            }
            missingReSyncServers.entrySet().removeIf(entry -> entry.getValue() <= now);
            missingReSyncApiKeys.entrySet().removeIf(entry -> entry.getValue() <= now);
            missingReSyncVersions.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
    }

    void reloadInstances(Object owner) {
        if (owner == null || !hasManagerOwner(owner)) return;
        clearMissingReSync();
        requestManagerPoll();
    }

    private void addManagerListener(Map<Object, Set<Consumer<ManagerSnapshot>>> registry, Object owner,
                                     Consumer<ManagerSnapshot> listener) {
        if (owner == null || listener == null) return;
        registry.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(listener);
        ensureManagerPolling();
    }

    private void removeManagerListener(Map<Object, Set<Consumer<ManagerSnapshot>>> registry, Object owner,
                                       Consumer<ManagerSnapshot> listener) {
        if (owner == null || listener == null) return;
        Set<Consumer<ManagerSnapshot>> listeners = registry.get(owner);
        if (listeners == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty()) registry.remove(owner);
        stopManagerPollingIfUnused();
    }

    private boolean hasManagerListeners() {
        return !managerInstanceListeners.isEmpty() || !managerNetworkListeners.isEmpty() || !managerRuntimeListeners.isEmpty();
    }

    private boolean hasManagerOwner(Object owner) {
        return managerInstanceListeners.containsKey(owner) || managerNetworkListeners.containsKey(owner)
                || managerRuntimeListeners.containsKey(owner);
    }

    private void ensureManagerPolling() {
        if (managerPollingTask != null && !managerPollingTask.isCancelled()) return;
        managerPollingTask = scheduler.scheduleAtFixedRate(this::requestManagerPoll,
                MANAGER_POLL_INITIAL_DELAY, MANAGER_POLL_PERIOD);
    }

    private void stopManagerPollingIfUnused() {
        if (hasManagerListeners()) return;
        TaskScheduler.ScheduledTask task = managerPollingTask;
        managerPollingTask = null;
        if (task != null) task.cancel();
        managerPollRequested = false;
        managerPollGeneration++;
        Async<ManagerSnapshot> request = managerPollRequest;
        managerPollRequest = null;
        managerPollInFlight = false;
        if (request != null && !request.isDone()) request.cancel();
        managerSnapshot = null;
    }

    private void requestManagerPoll() {
        if (!hasManagerListeners() || !BrowserLaunchSession.authenticated()) return;
        if (managerPollInFlight) {
            managerPollRequested = true;
            return;
        }
        managerPollInFlight = true;
        managerPollRequested = false;
        long generation = managerPollGeneration;
        Async<ManagerSnapshot> request = fetchManagerSnapshot(!managerInstanceListeners.isEmpty());
        managerPollRequest = request;
        request.whenComplete((snapshot, failure) -> scheduler.execute(() -> finishManagerPoll(generation, request, snapshot, failure)));
    }

    private Async<ManagerSnapshot> fetchManagerSnapshot(boolean includeStates) {
        Async<List<ServerModels.ClientServerView>> servers = getServers();
        Async<List<ServerScreenHost.NetworkView>> networks = getNetworks();
        return Async.allOf(servers, networks).thenCompose(ignored -> {
            List<ServerModels.ClientServerView> serverValues = servers.getNow(List.of());
            List<ServerScreenHost.NetworkView> networkValues = networks.getNow(List.of());
            if (!includeStates || serverValues.isEmpty()) {
                return Async.completed(new ManagerSnapshot(serverValues, networkValues, Map.of()));
            }
            List<String> ids = new ArrayList<>();
            List<Async<ServerModels.ServerStatus>> requests = new ArrayList<>();
            for (ServerModels.ClientServerView server : serverValues) {
                String id = serverId(server);
                if (id.isBlank()) continue;
                ids.add(id);
                requests.add(getServerStatus(id).exceptionally(ignoredFailure -> null));
            }
            Async<?>[] pending = requests.toArray(new Async<?>[0]);
            return Async.allOf(pending).thenApply(ignoredStatuses -> {
                Map<String, ServerModels.ServerStatus> statuses = new LinkedHashMap<>();
                for (int index = 0; index < requests.size(); index++) {
                    ServerModels.ServerStatus status = requests.get(index).getNow(null);
                    if (status != null) statuses.put(ids.get(index), status);
                }
                return new ManagerSnapshot(serverValues, networkValues, statuses);
            });
        });
    }

    private void finishManagerPoll(long generation, Async<ManagerSnapshot> request, ManagerSnapshot snapshot, Throwable failure) {
        if (generation != managerPollGeneration || request != managerPollRequest) return;
        managerPollRequest = null;
        managerPollInFlight = false;
        if (failure == null && snapshot != null && hasManagerListeners() && BrowserLaunchSession.authenticated()) {
            publishManagerSnapshot(snapshot);
        }
        if (managerPollRequested && hasManagerListeners()) {
            managerPollRequested = false;
            scheduler.schedule(this::requestManagerPoll, Duration.ZERO);
        }
    }

    private void publishManagerSnapshot(ManagerSnapshot current) {
        ManagerSnapshot previous = managerSnapshot;
        managerSnapshot = current;
        boolean instancesChanged = previous == null || !sameServers(previous.instances(), current.instances())
                || !sameStatuses(previous.statuses(), current.statuses());
        boolean networksChanged = previous == null || !Objects.equals(previous.networks(), current.networks());
        if (instancesChanged) notifyManagerListeners(managerInstanceListeners, current);
        if (networksChanged) notifyManagerListeners(managerNetworkListeners, current);
        if (networksChanged) notifyManagerListeners(managerRuntimeListeners, current);
    }

    private static boolean sameServers(List<ServerModels.ClientServerView> first, List<ServerModels.ClientServerView> second) {
        if (first.size() != second.size()) return false;
        Map<String, String> left = new LinkedHashMap<>();
        Map<String, String> right = new LinkedHashMap<>();
        for (ServerModels.ClientServerView server : first) left.put(serverId(server), serverFingerprint(server));
        for (ServerModels.ClientServerView server : second) right.put(serverId(server), serverFingerprint(server));
        return Objects.equals(left, right);
    }

    private static boolean sameStatuses(Map<String, ServerModels.ServerStatus> first, Map<String, ServerModels.ServerStatus> second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (String id : first.keySet()) {
            ServerModels.ServerStatus left = first.get(id);
            ServerModels.ServerStatus right = second.get(id);
            if (left == right) continue;
            if (left == null || right == null || !Objects.equals(left.currentState, right.currentState)
                    || left.suspended != right.suspended || left.installing != right.installing) return false;
        }
        return true;
    }

    private static String serverFingerprint(ServerModels.ClientServerView server) {
        if (server == null) return "";
        return String.valueOf(server.identifier) + '\u0000' + String.valueOf(server.uuid) + '\u0000' + String.valueOf(server.name)
                + '\u0000' + String.valueOf(server.description) + '\u0000' + String.valueOf(server.ip) + '\u0000' + server.port
                + '\u0000' + String.valueOf(server.nodeName) + '\u0000' + String.valueOf(server.invocation) + '\u0000'
                + String.valueOf(server.dockerImage) + '\u0000' + server.isSuspended + '\u0000' + server.isInstalling + '\u0000'
                + String.valueOf(server.loader) + '\u0000' + String.valueOf(server.software) + '\u0000' + String.valueOf(server.version);
    }

    private static void notifyManagerListeners(Map<Object, Set<Consumer<ManagerSnapshot>>> registry, ManagerSnapshot snapshot) {
        List<Consumer<ManagerSnapshot>> callbacks = new ArrayList<>();
        for (Set<Consumer<ManagerSnapshot>> listeners : registry.values()) callbacks.addAll(List.copyOf(listeners));
        for (Consumer<ManagerSnapshot> callback : callbacks) {
            try {
                callback.accept(snapshot);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public Async<Void> createNetwork(String name, String proxyId, List<String> backendIds, boolean installReSync) {
        return networkRequest("POST", "/networks", Map.of("name", name == null ? "" : name,
                "proxyId", proxyId == null ? "" : proxyId, "backendIds", backendIds == null ? List.of() : backendIds,
                "installReSync", installReSync)).thenApply(ignored -> null);
    }

    @Override
    public Async<ServerCapabilities> getServerCapabilities(String serverId) {
        return get("/servers/" + path(serverId) + "/capabilities", BrowserRemotelyServerApi::serverCapabilities).thenApply(value -> value == null
                ? new ServerCapabilities(serverId, Map.of(), null) : value.toModel());
    }

    public Async<ServerModels.ClientServerView> getServer(String serverId) {
        return get("/servers/" + path(serverId), BrowserRemotelyServerApi::serverView).thenApply(ServerView::toModel);
    }

    @Override
    public Async<String> getSftpToken(String serverIdentifier) {
        return post("/servers/" + path(serverIdentifier) + "/files/sftp-token", Map.of(), BrowserRemotelyServerApi::sftpToken)
                .thenApply(value -> value == null || value.token == null ? "" : value.token);
    }

    @Override
    public Async<ServerModels.ServerStats> getServerResources(String serverId) {
        return get("/servers/" + path(serverId) + "/stats", BrowserRemotelyServerApi::serverStats);
    }

    @Override
    public Async<ServerModels.ServerStatus> getServerStatus(String serverId) {
        return getServerHealth(serverId).thenApply(health -> {
            ServerModels.ServerStatus status = new ServerModels.ServerStatus();
            status.currentState = health == null || health.status == null ? "offline" : health.status;
            status.suspended = health != null && health.suspended;
            status.installing = health != null && health.installing;
            return status;
        });
    }

    public Async<ServerHealthView> getServerHealth(String serverId) {
        return get("/servers/" + path(serverId) + "/health", BrowserRemotelyServerApi::serverHealth);
    }

    @Override
    public Async<ServerModels.WebsocketData> getServerWebsocket(String serverId) {
        return terminalTicket(serverId).thenApply(ticket -> {
            ServerModels.WebsocketData data = new ServerModels.WebsocketData();
            data.token = ticket.ticket();
            data.socket = BrowserLaunchSession.terminalUrl(serverId);
            return data;
        });
    }

    @Override
    public Async<Void> setServerPower(String serverId, String signal) {
        return job("/servers/" + path(serverId) + "/power", Map.of("signal", signal == null ? "" : signal));
    }

    @Override
    public Async<Void> sendServerCommand(String serverId, String command) {
        return job("/servers/" + path(serverId) + "/console/command", Map.of("command", command == null ? "" : command));
    }

    @Override
    public Async<PlayerList> getPlayers(String serverId) {
        return get("/servers/" + path(serverId) + "/players", BrowserRemotelyServerApi::playerList).thenApply(value -> {
            List<Player> players = value == null || value.players == null ? List.of() : value.players.stream().filter(Objects::nonNull)
                    .map(player -> new Player(player.uuid, player.name, player.online, player.operator, player.ping, player.address)).toList();
            return new PlayerList(value != null && value.supported, players);
        });
    }

    @Override
    public Async<Void> executePlayerAction(String serverId, PlayerAction action) {
        if (action == null) {
            return Async.failed(new IllegalArgumentException("Player action is required"));
        }
        return job("/servers/" + path(serverId) + "/players/actions", Map.of(
            "action", action.action(), "player", action.playerName(), "argument", action.argument(), "ipBan", action.flag()));
    }

    @Override
    public Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(String serverId, String directory) {
        return getList("/servers/" + path(serverId) + "/files?directory=" + query(directory == null ? "/" : directory),
                BrowserRemotelyServerApi::fileEntry);
    }

    @Override
    public Async<List<ServerModels.PteroFileObjectAttributes>> listResourceFiles(String serverId, String directory) {
        return resourceApi.listFiles(serverId, directory);
    }

    @Override
    public Async<List<ServerModels.ResourceFileHash>> resolveResourceFileHashes(String serverId, List<String> paths) {
        return resourceApi.resolveHashes(serverId, paths);
    }

    @Override
    public Async<String> getFileContent(String serverId, String path) {
        return request("GET", "/servers/" + path(serverId) + "/files/content?path=" + query(path), null);
    }

    Async<String> getFileContentAllowMissing(String serverId, String path) {
        return requestAllowMissingContent("GET", "/servers/" + path(serverId) + "/files/content?path=" + query(path), null);
    }

    @Override
    public Async<String> getFileDownloadUrl(String serverId, String path) {
        return downloadFile(serverId, path);
    }

    @Override
    public Async<Void> writeFile(String serverId, String path, String content) {
        return job("/servers/" + path(serverId) + "/files/content", Map.of("path", path == null ? "" : path, "content", content == null ? "" : content));
    }

    @Override
    public Async<Void> deleteFiles(String serverId, String root, List<String> files) {
        return job("/servers/" + path(serverId) + "/files/delete", Map.of("root", root == null ? "/" : root, "files", files == null ? List.of() : files));
    }

    @Override
    public Async<Void> renameFiles(String serverId, String root, List<ServerModels.PteroFileRenameItem> files) {
        if (files == null || files.isEmpty()) return Async.completed(null);
        Async<Void> result = Async.completed(null);
        for (ServerModels.PteroFileRenameItem file : files) {
            result = result.thenCompose(ignored -> job("/servers/" + path(serverId) + "/files/rename", Map.of(
                    "root", root == null ? "/" : root, "from", file == null || file.from == null ? "" : file.from,
                    "to", file == null || file.to == null ? "" : file.to)));
        }
        return result;
    }

    @Override
    public Async<Void> toggleResource(String serverId, String resourcePath, boolean enabled) {
        if (!BrowserLaunchSession.metadata().demo()) return RemotelyServerApi.super.toggleResource(serverId, resourcePath, enabled);
        String value = resourcePath == null ? "" : resourcePath.strip().replace('\\', '/');
        int separator = value.lastIndexOf('/');
        String resource = separator < 0 ? value : value.substring(separator + 1);
        String body = json(Map.of("resource", resource, "enabled", enabled));
        return demoRequest("POST", "/remotely-web/demo/servers/" + path(serverId) + "/resources/toggle", body).thenApply(ignored -> null);
    }

    @Override
    public Async<Void> copyFile(String serverId, String location) {
        return job("/servers/" + path(serverId) + "/files/copy", Map.of("location", location == null ? "" : location));
    }

    @Override
    public Async<Void> createFolder(String serverId, String root, String name) {
        return job("/servers/" + path(serverId) + "/files/create-folder", Map.of("root", root == null ? "/" : root, "name", name == null ? "" : name));
    }

    @Override
    public Async<Void> pullFile(String serverId, String url, String directory, String filename) {
        return job("/servers/" + path(serverId) + "/files/pull", Map.of("sourceUrl", url == null ? "" : url,
                "directory", directory == null ? "/" : directory, "filename", filename == null ? "" : filename));
    }

    @Override
    public Async<Void> compressFiles(String serverId, String root, List<String> files) {
        return job("/servers/" + path(serverId) + "/files/compress", Map.of("root", root == null ? "/" : root,
                "files", files == null ? List.of() : files));
    }

    @Override
    public Async<Void> decompressFile(String serverId, String root, String file) {
        return job("/servers/" + path(serverId) + "/files/decompress", Map.of("root", root == null ? "/" : root,
                "file", file == null ? "" : file));
    }

    @Override
    public Async<List<TrashEntry>> listTrash(String serverId) {
        return getList("/servers/" + path(serverId) + "/files/trash", BrowserRemotelyServerApi::trashEntry).thenApply(value -> value == null ? List.of() : value);
    }

    @Override
    public Async<FileVersion> getFileVersion(String serverId, String filePath) {
        return get("/servers/" + path(serverId) + "/files/version?path=" + query(filePath), BrowserRemotelyServerApi::fileVersion);
    }

    @Override
    public Async<TrashEntry> trashFile(String serverId, String filePath, String expectedVersion) {
        return post("/servers/" + path(serverId) + "/files/trash", Map.of("path", filePath == null ? "" : filePath,
                "expectedVersion", expectedVersion == null ? "" : expectedVersion), BrowserRemotelyServerApi::trashEntry);
    }

    @Override
    public Async<TrashEntry> restoreTrash(String serverId, String trashId, String expectedVersion) {
        return post("/servers/" + path(serverId) + "/files/trash/" + path(trashId) + "/restore",
                Map.of("expectedVersion", expectedVersion == null ? "" : expectedVersion), BrowserRemotelyServerApi::trashEntry);
    }

    @Override
    public Async<Void> purgeTrash(String serverId, String trashId, String expectedVersion) {
        return request("DELETE", "/servers/" + path(serverId) + "/files/trash/" + path(trashId),
                json(Map.of("expectedVersion", expectedVersion == null ? "" : expectedVersion))).thenApply(ignored -> null);
    }

    @Override
    public Async<List<ServerModels.Backup>> getBackups(String serverId) {
        return getList("/servers/" + path(serverId) + "/backups", BrowserRemotelyServerApi::backup).thenApply(value -> value == null ? List.of() : value);
    }

    @Override
    public Async<ServerModels.Backup> createBackup(String serverId, String name, List<String> ignored, boolean locked) {
        return post("/servers/" + path(serverId) + "/backups", Map.of("name", name == null ? "" : name,
                "ignored", ignored == null ? List.of() : ignored, "locked", locked), BrowserRemotelyServerApi::backup);
    }

    @Override
    public ServerScheduleModels.Capabilities scheduleCapabilities(String serverId) {
        if (BrowserLaunchSession.metadata().demo()) {
            return ServerScheduleModels.Capabilities.unavailable("Scheduling Is Not Available In Reactor Demo");
        }
        return new ServerScheduleModels.Capabilities(true, "", ServerScheduleModels.Durability.BACKEND,
                true, true, true, true, true);
    }

    @Override
    public Async<List<ServerScheduleModels.Schedule>> listSchedules(String serverId) {
        if (BrowserLaunchSession.metadata().demo()) return Async.completed(List.of());
        return scheduleClient.list(serverId);
    }

    @Override
    public Async<ServerScheduleModels.Schedule> createSchedule(String serverId, ServerScheduleModels.Mutation mutation,
                                                                String idempotencyKey) {
        if (BrowserLaunchSession.metadata().demo()) return Async.failed(new IllegalStateException("Scheduling Is Not Available In Reactor Demo"));
        return scheduleClient.create(serverId, mutation, idempotencyKey);
    }

    @Override
    public Async<ServerScheduleModels.Schedule> updateSchedule(String serverId, String scheduleId,
                                                                ServerScheduleModels.Mutation mutation,
                                                                String expectedRevision, String idempotencyKey) {
        if (BrowserLaunchSession.metadata().demo()) return Async.failed(new IllegalStateException("Scheduling Is Not Available In Reactor Demo"));
        return scheduleClient.update(serverId, scheduleId, mutation, expectedRevision, idempotencyKey);
    }

    @Override
    public Async<Void> deleteSchedule(String serverId, String scheduleId, String expectedRevision, String idempotencyKey) {
        if (BrowserLaunchSession.metadata().demo()) return Async.failed(new IllegalStateException("Scheduling Is Not Available In Reactor Demo"));
        return scheduleClient.delete(serverId, scheduleId, expectedRevision, idempotencyKey);
    }

    @Override
    public Async<ServerScheduleModels.Run> runSchedule(String serverId, String scheduleId, String idempotencyKey) {
        if (BrowserLaunchSession.metadata().demo()) return Async.failed(new IllegalStateException("Scheduling Is Not Available In Reactor Demo"));
        return scheduleClient.run(serverId, scheduleId, idempotencyKey);
    }

    @Override
    public Async<ServerModels.Backup> toggleBackupLock(String serverId, String backupUuid) {
        return post("/servers/" + path(serverId) + "/backups/" + path(backupUuid) + "/lock", Map.of(), BrowserRemotelyServerApi::backup);
    }

    @Override
    public Async<Void> deleteBackup(String serverId, String backupUuid) {
        return deleteJob("/servers/" + path(serverId) + "/backups/" + path(backupUuid));
    }

    @Override
    public Async<Void> restoreBackup(String serverId, String backupUuid, boolean truncate) {
        return job("/servers/" + path(serverId) + "/backups/" + path(backupUuid) + "/restore", Map.of("truncate", truncate));
    }

    @Override
    public Async<String> getBackupDownloadUrl(String serverId, String backupUuid) {
        return getStringMap("/servers/" + path(serverId) + "/backups/" + path(backupUuid) + "/download")
                .thenApply(value -> value == null ? "" : value.getOrDefault("url", ""));
    }

    @Override
    public Async<List<ServerModels.Allocation>> getAllocations(String serverId) {
        return getList("/servers/" + path(serverId) + "/resources/allocations", BrowserRemotelyServerApi::allocation)
                .thenApply(value -> value == null ? List.of() : value);
    }

    public Async<ServerModels.Allocation> createAllocation(String serverId) {
        return post("/servers/" + path(serverId) + "/resources/allocations", Map.of(), BrowserRemotelyServerApi::allocation);
    }

    public Async<ServerModels.Allocation> updateAllocation(String serverId, Integer allocationId, String notes, boolean primary) {
        if (allocationId == null) return Async.failed(new IllegalArgumentException("Allocation Identifier Is Required"));
        return put("/servers/" + path(serverId) + "/resources/allocations/" + allocationId,
                Map.of("notes", notes == null ? "" : notes, "primary", primary), BrowserRemotelyServerApi::allocation);
    }

    public Async<Void> deleteAllocation(String serverId, Integer allocationId) {
        if (allocationId == null) return Async.failed(new IllegalArgumentException("Allocation Identifier Is Required"));
        return deleteJob("/servers/" + path(serverId) + "/resources/allocations/" + allocationId);
    }

    @Override
    public Async<List<ServerModels.Subuser>> getSubusers(String serverId) {
        return getList("/servers/" + path(serverId) + "/management/subusers", BrowserRemotelyServerApi::subuser)
                .thenApply(value -> value == null ? List.of() : value);
    }

    public Async<ServerModels.Subuser> createSubuser(String serverId, String email, List<String> permissions) {
        return post("/servers/" + path(serverId) + "/management/subusers", Map.of(
                "email", email == null ? "" : email,
                "permissions", permissions == null ? List.of() : permissions), BrowserRemotelyServerApi::subuser);
    }

    public Async<ServerModels.Subuser> updateSubuser(String serverId, String subuserId, List<String> permissions) {
        if (blank(subuserId)) return Async.failed(new IllegalArgumentException("Subuser Identifier Is Required"));
        return put("/servers/" + path(serverId) + "/management/subusers/" + path(subuserId), Map.of(
                "permissions", permissions == null ? List.of() : permissions), BrowserRemotelyServerApi::subuser);
    }

    public Async<Void> deleteSubuser(String serverId, String subuserId) {
        if (blank(subuserId)) return Async.failed(new IllegalArgumentException("Subuser Identifier Is Required"));
        return deleteJob("/servers/" + path(serverId) + "/management/subusers/" + path(subuserId));
    }

    public Async<ServerModels.SystemPermissions> getSystemPermissions() {
        return get("/management/system-permissions", BrowserRemotelyServerApi::systemPermissions);
    }

    public Async<List<ServerModels.ReStudioUserInfo>> searchUsers(String query) {
        String value = query == null ? "" : query.trim();
        if (value.length() < 2) return Async.completed(List.of());
        return getList("/management/users/search?query=" + query(value), BrowserRemotelyServerApi::userInfo)
                .thenApply(results -> results == null ? List.of() : results);
    }

    public Async<ServerManagement> getServerManagement(String serverId) {
        return get("/servers/" + path(serverId) + "/management", BrowserRemotelyServerApi::management).thenApply(ManagementView::management);
    }

    @Override
    public Async<ServerModels.StartupSettings> getServerStartupConfig(String serverId) {
        return get("/servers/" + path(serverId) + "/startup-settings", BrowserRemotelyServerApi::startupSettings);
    }

    @Override
    public Async<Void> updateServerStartupVariables(String serverId, String revision, Map<String, String> values) {
        if (revision == null || revision.isBlank()) return Async.failed(new IllegalArgumentException("Startup Settings Revision Is Required"));
        Map<String, String> variables = values == null ? Map.of() : new LinkedHashMap<>(values);
        return put("/servers/" + path(serverId) + "/startup-settings", Map.of(
                "revision", revision, "values", variables), BrowserRemotelyServerApi::startupSettings).thenApply(ignored -> null);
    }

    @Override
    public Async<Void> updateServerStartupVariable(String serverId, String key, String value) {
        return RemotelyServerApi.super.updateServerStartupVariable(serverId, key, value);
    }

    @Override
    public Async<Void> updateServerDockerImage(String serverId, String dockerImage) {
        return jobPut("/servers/" + path(serverId) + "/settings", Map.of("dockerImage", dockerImage == null ? "" : dockerImage,
                "reinstall", false, "wipeFiles", false));
    }

    @Override
    public Async<Void> renameServer(String serverId, String newName) {
        return jobPut("/servers/" + path(serverId) + "/settings", Map.of("name", newName == null ? "" : newName,
                "reinstall", false, "wipeFiles", false));
    }

    public Async<ServerModels.CheckoutResponse> createHostedCheckout(String serverName, String planName,
                                                                       Map<String, String> environment,
                                                                       Map<String, String> fileConfigs,
                                                                       String subdomain,
                                                                       ServerModels.CustomPlanRequest customPlan) {
        return createHostedCheckout(new HostedCheckoutRequest(serverName, planName, null, environment, fileConfigs,
                null, null, null, null, null, subdomain, customPlan == null ? null : customPlan.memoryMb));
    }

    public Async<ServerModels.CheckoutResponse> createHostedCheckout(HostedCheckoutRequest request) {
        Map<String, Object> body;
        try {
            body = hostedCheckoutBody(request);
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        return apiRequest("POST", "/remotely-web/billing/checkout", json(body)).thenApply(response -> {
            JsonObject value = BrowserJson.object(response);
            ServerModels.CheckoutResponse result = new ServerModels.CheckoutResponse();
            result.url = BrowserJson.string(value, "url");
            result.intentId = BrowserJson.string(value, "intentId");
            return result;
        });
    }

    static Map<String, Object> hostedCheckoutBody(HostedCheckoutRequest request) {
        if (request == null) throw new IllegalArgumentException("Checkout Request Is Required");
        if (blank(request.serverName())) throw new IllegalArgumentException("Server Name Is Required");
        if (blank(request.plan())) throw new IllegalArgumentException("Server Plan Is Required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverName", request.serverName().strip());
        body.put("plan", request.plan().strip());
        body.put("environment", request.environment() == null ? Map.of() : request.environment());
        body.put("fileConfigs", request.fileConfigs() == null ? Map.of() : request.fileConfigs());
        if (request.eggId() != null) body.put("eggId", request.eggId());
        if (request.pendingModpackInstall() != null && !request.pendingModpackInstall().isEmpty()) body.put("pendingModpackInstall", request.pendingModpackInstall());
        if (!blank(request.javaDockerImage())) body.put("javaDockerImage", request.javaDockerImage().strip());
        if (!blank(request.software())) body.put("software", request.software().strip());
        if (!blank(request.version())) body.put("version", request.version().strip());
        if (!blank(request.build())) body.put("build", request.build().strip());
        if (!blank(request.subdomain())) body.put("subdomain", request.subdomain().strip());
        if (request.customMemoryMb() != null) body.put("customMemoryMb", request.customMemoryMb());
        return Map.copyOf(body);
    }

    public Async<HostedModpackCapabilities> getHostedModpackCapabilities(String serverId, String provider) {
        if (blank(serverId) || blank(provider)) return Async.failed(new IllegalArgumentException("Server And Provider Are Required"));
        return request("GET", "/servers/" + path(serverId) + "/modpacks/capabilities?provider=" + path(provider), null)
                .thenApply(value -> hostedModpackCapabilities(BrowserJson.object(value)));
    }

    public Async<HostedModpackJob> preflightHostedModpack(String serverId, HostedModpackRequest request) {
        return submitHostedModpack(serverId, "preflight", request);
    }

    public Async<HostedModpackJob> installHostedModpack(String serverId, HostedModpackRequest request) {
        return submitHostedModpack(serverId, "install", request);
    }

    public Async<HostedModpackJob> changeHostedModpack(String serverId, HostedModpackRequest request) {
        return submitHostedModpack(serverId, "change", request);
    }

    public Async<HostedModpackJob> unlockHostedModpack(String serverId, String provider, String idempotencyKey) {
        if (blank(serverId) || blank(provider) || blank(idempotencyKey)) return Async.failed(new IllegalArgumentException("Server, Provider, And Idempotency Key Are Required"));
        return request("POST", "/servers/" + path(serverId) + "/modpacks/unlock",
                json(Map.of("provider", provider, "idempotencyKey", idempotencyKey))).thenApply(value -> hostedModpackJob(BrowserJson.object(value)));
    }

    public Async<HostedModpackJob> getHostedModpackJob(String jobId) {
        if (blank(jobId)) return Async.failed(new IllegalArgumentException("Capability Job Identifier Is Required"));
        return request("GET", "/jobs/" + path(jobId), null).thenApply(value -> hostedModpackJob(BrowserJson.object(value)));
    }

    private Async<HostedModpackJob> submitHostedModpack(String serverId, String operation, HostedModpackRequest value) {
        if (blank(serverId) || value == null || blank(value.provider()) || blank(value.projectId()) || blank(value.idempotencyKey())) {
            return Async.failed(new IllegalArgumentException("Complete Modpack Selection Is Required"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", value.provider());
        body.put("projectId", value.projectId());
        if (!blank(value.versionId())) body.put("versionId", value.versionId());
        if (!blank(value.fileId())) body.put("fileId", value.fileId());
        if (!blank(value.versionNumber())) body.put("versionNumber", value.versionNumber());
        if (!blank(value.downloadUrl())) body.put("downloadUrl", value.downloadUrl());
        if (!blank(value.minecraftVersion())) body.put("minecraftVersion", value.minecraftVersion());
        if (!blank(value.software())) body.put("software", value.software());
        if (!blank(value.modpackResolutionId())) body.put("modpackResolutionId", value.modpackResolutionId());
        if (!blank(value.javaDockerImage())) body.put("javaDockerImage", value.javaDockerImage());
        body.put("idempotencyKey", value.idempotencyKey());
        return request("POST", "/servers/" + path(serverId) + "/modpacks/" + operation, json(body))
                .thenApply(response -> hostedModpackJob(BrowserJson.object(response)));
    }

    @Override
    public Async<Void> reinstallServer(String serverId) {
        return jobPut("/servers/" + path(serverId) + "/settings", Map.of("reinstall", true, "wipeFiles", false));
    }

    @Override
    public Async<ServerModels.ReProxySummary> getReProxySummary(String serverId) {
        if (blank(serverId)) return Async.failed(new IllegalArgumentException("Server Identifier Is Required"));
        String base = reProxyPath(serverId);
        return getList(base + "/domains", BrowserRemotelyServerApi::hostedDomain).thenCompose(domains -> get(base + "/tunnel", BrowserRemotelyServerApi::hostedTunnel).thenApply(tunnel -> {
            ServerModels.ReProxySummary summary = new ServerModels.ReProxySummary();
            summary.domains = domains == null ? List.of() : domains.stream().filter(Objects::nonNull)
                    .map(BrowserRemotelyServerApi::domainModel).filter(Objects::nonNull).toList();
            summary.activeTunnel = tunnel == null ? null : tunnel.toModel(summary.domains);
            return summary;
        }));
    }

    @Override
    public Async<ServerModels.ReProxyDomain> createReProxyDomain(String serverId, String subdomain) {
        if (blank(serverId)) return Async.failed(new IllegalArgumentException("Server Identifier Is Required"));
        return post(reProxyPath(serverId) + "/domains", new ReProxyDomainRequest(subdomain),
                BrowserRemotelyServerApi::hostedDomain).thenApply(BrowserRemotelyServerApi::requireDomainModel);
    }

    @Override
    public Async<ServerModels.ReProxyStartTunnelResponse> startReProxyTunnel(String serverId, String domainId, int localPort, String protocol) {
        if (blank(serverId)) return Async.failed(new IllegalArgumentException("Server Identifier Is Required"));
        if (blank(domainId)) return Async.failed(new IllegalArgumentException("ReProxy Domain Is Required"));
        return post(reProxyPath(serverId) + "/tunnel", Map.of("domainId", domainId,
                        "localPort", localPort, "protocol", protocol == null ? "" : protocol),
                BrowserRemotelyServerApi::hostedTunnel).thenApply(BrowserRemotelyServerApi::requireStartResponse);
    }

    @Override
    public Async<Void> stopReProxyTunnel(String serverId, String tunnelId) {
        if (blank(serverId)) return Async.failed(new IllegalArgumentException("Server Identifier Is Required"));
        if (blank(tunnelId)) return Async.failed(new IllegalArgumentException("ReProxy Tunnel Is Required"));
        return request("POST", reProxyPath(serverId) + "/tunnel/" + path(tunnelId) + "/stop", json(Map.of())).thenApply(ignored -> null);
    }

    @Override
    public Async<Void> deleteReProxyDomain(String serverId, String domainId) {
        if (blank(serverId)) return Async.failed(new IllegalArgumentException("Server Identifier Is Required"));
        if (blank(domainId)) return Async.failed(new IllegalArgumentException("ReProxy Domain Is Required"));
        return delete(reProxyPath(serverId) + "/domains/" + path(domainId));
    }

    @Override
    public Async<ServerModels.ClientServerView> duplicateServer(String serverId) {
        return duplicateServer(serverId, "Server Copy", UUID.randomUUID().toString()).thenApply(result -> {
            ServerModels.ClientServerView server = new ServerModels.ClientServerView();
            server.identifier = result == null ? "" : result.targetServerId();
            server.name = "Server Copy";
            return server;
        });
    }

    @Override
    public Async<DuplicateServerResult> duplicateServer(String serverId, String name, String idempotencyKey) {
        return post("/servers/" + path(serverId) + "/duplicate", Map.of("name", name == null ? "" : name,
                "idempotencyKey", idempotencyKey == null ? "" : idempotencyKey), BrowserRemotelyServerApi::duplicateResult);
    }

    @Override
    public Async<DuplicateServerResult> duplicateStatus(String serverId, String intentId) {
        return get("/servers/" + path(serverId) + "/duplicates/" + path(intentId), BrowserRemotelyServerApi::duplicateResult);
    }

    @Override
    public Async<Void> deleteServer(String serverId, String serverName) {
        return job("/servers/" + path(serverId) + "/delete", Map.of("confirmServerId", serverId,
                "confirmName", serverName == null ? "" : serverName));
    }

    @Override
    public DeveloperCapabilityProvider developer(String serverId) {
        return new BrowserDeveloperProvider(requireServerId(serverId));
    }

    public Async<Void> uploadFile(String serverId, String sourceUrl, String directory, String filename) {
        return job("/servers/" + path(serverId) + "/files/upload", Map.of(
            "sourceUrl", sourceUrl == null ? "" : sourceUrl,
            "directory", directory == null ? "/" : directory,
            "filename", filename == null ? "" : filename));
    }

    public Async<Void> uploadFileData(String serverId, String directory, String filename, byte[] content) {
        return uploadFileData(serverId, directory, filename, content, true, requestIdempotencyKey("POST", null));
    }

    @Override
    public Async<HostedUploadView> startHostedUpload(String serverId, UUID uploadId, String directory, String filename, long size) {
        return post(hostedUploadPath(serverId), Map.of("uploadId", uploadId.toString(), "directory", directory, "filename", filename, "size", size),
                BrowserRemotelyServerApi::hostedUpload);
    }

    @Override
    public Async<HostedUploadView> hostedUploadStatus(String serverId, UUID uploadId) {
        return get(hostedUploadPath(serverId) + "/" + path(uploadId.toString()), BrowserRemotelyServerApi::hostedUpload);
    }

    @Override
    public Async<HostedUploadView> writeHostedUpload(String serverId, UUID uploadId, long offset, byte[] bytes,
                                                     BiConsumer<Long, Long> progress) {
        return writeHostedUpload(serverId, uploadId, offset, bytes, progress, true);
    }

    @Override
    public Async<HostedUploadView> completeHostedUpload(String serverId, UUID uploadId) {
        return request("POST", hostedUploadPath(serverId) + "/" + path(uploadId.toString()) + "/complete", "{}")
                .thenApply(body -> decode(BrowserJson.object(body), BrowserRemotelyServerApi::hostedUpload));
    }

    @Override
    public Async<Void> cancelHostedUpload(String serverId, UUID uploadId) {
        return delete(hostedUploadPath(serverId) + "/" + path(uploadId.toString()));
    }

    private String hostedUploadPath(String serverId) {
        return "/servers/" + path(serverId) + "/file-uploads";
    }

    private Async<HostedUploadView> writeHostedUpload(String serverId, UUID uploadId, long offset, byte[] bytes,
                                                      BiConsumer<Long, Long> progress, boolean retry) {
        String endpoint = hostedUploadPath(serverId) + "/" + path(uploadId.toString()) + "?offset=" + offset;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .timeout(Duration.ofMinutes(2))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        Async<HttpResponse<String>> upload = transport instanceof BrowserHttpTransport browser
                ? browser.sendAsync(request, HttpResponse.BodyHandlers.ofString(), progress)
                : transport.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        return upload.thenCompose(response -> {
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> writeHostedUpload(serverId, uploadId, offset, bytes, progress, false));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) return sessionExpired(new IllegalStateException("Browser Session Expired"));
                return Async.failed(capabilityFailure(response.statusCode(), response.body()));
            }
            return Async.completed(decode(BrowserJson.object(response.body()), BrowserRemotelyServerApi::hostedUpload));
        });
    }

    private Async<Void> uploadFileData(String serverId, String directory, String filename, byte[] content, boolean retry, String idempotencyKey) {
        String endpoint = "/servers/" + path(serverId) + "/files/data?directory=" + query(directory) + "&filename=" + query(filename);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .header("Idempotency-Key", idempotencyKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(content == null ? new byte[0] : content))
                .build();
        return transport.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenCompose(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) return Async.completed(null);
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> uploadFileData(serverId, directory, filename, content, false, idempotencyKey));
            }
            if (response.statusCode() == 401) return sessionExpired(new IllegalStateException("Browser Session Expired"));
            return Async.failed(new IllegalStateException("Browser File Upload Failed With Status " + response.statusCode()));
        }).thenApply(value -> {
            invalidateBrowserReadCache();
            return value;
        });
    }

    Async<Void> uploadBrowserFile(String serverId, String directory, String filename, BrowserFile file,
                                  BiConsumer<Long, Long> progress) {
        return uploadBrowserFile(serverId, directory, filename, file, progress, true, requestIdempotencyKey("POST", null));
    }

    private Async<Void> uploadBrowserFile(String serverId, String directory, String filename, BrowserFile file,
                                          BiConsumer<Long, Long> progress, boolean retry, String idempotencyKey) {
        if (!(transport instanceof BrowserHttpTransport browserTransport)) {
            return Async.failed(new IllegalStateException("Browser File Upload Is Unavailable"));
        }
        String endpoint = "/servers/" + path(serverId) + "/files/data?directory=" + query(directory) + "&filename=" + query(filename);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .header("Idempotency-Key", idempotencyKey)
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return browserTransport.sendBrowserFile(request, file, progress).thenCompose(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) return Async.completed(null);
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> uploadBrowserFile(serverId, directory, filename, file, progress, false, idempotencyKey));
            }
            if (response.statusCode() == 401) return sessionExpired(new IllegalStateException("Browser Session Expired"));
            return Async.failed(new IllegalStateException("Browser File Upload Failed With Status " + response.statusCode()));
        }).thenApply(value -> {
            invalidateBrowserReadCache();
            return value;
        });
    }

    public Async<Void> downloadFileData(String serverId, String path, TransferSink destination, BiConsumer<Long, Long> progress,
                                        BooleanSupplier cancelled) {
        if (destination == null) return Async.failed(new IllegalArgumentException("Download Destination Is Required"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/servers/" + BrowserRemotelyServerApi.path(serverId)
                        + "/files/data?path=" + query(path)))
                .header("Accept", "application/octet-stream")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        Async<Void> result = Async.pending();
        HostedDownloadLifecycle lifecycle = new HostedDownloadLifecycle(result, destination, progress, cancelled,
                256L * 1024 * 1024);
        Async<HttpResponse<Void>> stream;
        try {
            stream = transport.sendStreaming(request, lifecycle::accept);
        } catch (Throwable failure) {
            lifecycle.fail(failure);
            return result;
        }
        lifecycle.attach(stream);
        stream.whenComplete(lifecycle::complete);
        return result;
    }

    public Async<String> downloadFile(String serverId, String path) {
        return getStringMap("/servers/" + path(serverId) + "/files/download?path=" + query(path)).thenApply(value -> value.get("url"));
    }

    Async<byte[]> downloadFileData(String serverId, String path) {
        return downloadFileData(serverId, path, true);
    }

    private Async<byte[]> downloadFileData(String serverId, String path, boolean retry) {
        String endpoint = "/servers/" + path(serverId) + "/files/data?path=" + query(path);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .header("Accept", "image/png,image/gif,image/*")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        List<byte[]> chunks = new ArrayList<>();
        int[] size = {0};
        return transport.sendStreaming(request, bytes -> {
            if (bytes == null || bytes.length == 0) return;
            if (bytes.length > MAX_GLYPH_IMAGE_BYTES - size[0]) {
                throw new IllegalStateException("Glyph Preview Image Is Too Large");
            }
            size[0] += bytes.length;
            chunks.add(bytes);
        }).thenCompose(response -> {
            byte[] body = new byte[size[0]];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, body, offset, chunk.length);
                offset += chunk.length;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Async.completed(body);
            }
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> downloadFileData(serverId, path, false));
            }
            String message = new String(body, StandardCharsets.UTF_8);
            if (response.statusCode() == 401) return sessionExpired(new IllegalStateException("Browser Session Expired"));
            return Async.failed(capabilityFailure(response.statusCode(), message));
        });
    }

    public Async<Void> openFileDownload(String serverId, String path) {
        BrowserFileExplorerAdapters.PendingExternalOpen reserved = BrowserFileExplorerAdapters.takePendingExternalOpen(serverId, path);
        if (reserved == null) {
            BrowserHostActionHandler actions = host == null ? null : host.hostActionHandler();
            int pendingWindow = actions == null ? -1 : actions.openPendingBrowser();
            if (pendingWindow < 0) return Async.failed(new UnsupportedOperationException("Browser File Download Is Unavailable"));
            reserved = BrowserFileExplorerAdapters.reservePendingExternalOpen(host, serverId, path, actions, pendingWindow);
            if (reserved == null) {
                actions.closePendingBrowser(pendingWindow);
                return Async.failed(new UnsupportedOperationException("Browser File Download Is Unavailable"));
            }
        }
        BrowserFileExplorerAdapters.PendingExternalOpen pending = reserved;
        Async<Void> result;
        try {
            result = downloadFile(serverId, path).thenCompose(url -> {
                if (url == null || url.isBlank()) return Async.failed(new IllegalStateException("File Download Is Unavailable"));
                if (!BrowserFileExplorerAdapters.navigatePendingExternalOpen(pending, url)) {
                    return Async.failed(new UnsupportedOperationException("Browser File Download Is Unavailable"));
                }
                return Async.completed(null);
            });
        } catch (Throwable failure) {
            BrowserFileExplorerAdapters.failPendingExternalOpen(pending);
            return Async.failed(failure);
        }
        return result.whenComplete((ignored, failure) -> {
            if (failure != null) BrowserFileExplorerAdapters.failPendingExternalOpen(pending);
        });
    }

    Async<Void> downloadFiles(String serverId, List<String> paths) {
        BrowserHostActionHandler actions = host == null ? null : host.hostActionHandler();
        if (actions == null) return Async.failed(new UnsupportedOperationException("Browser File Download Is Unavailable"));
        return downloadFiles(paths, path -> downloadFile(serverId, path), actions::downloadUrl);
    }

    static Async<Void> downloadFiles(List<String> paths, Function<String, Async<String>> signer,
                                     BiFunction<String, String, Boolean> downloader) {
        List<String> selected = paths == null ? List.of() : paths.stream()
                .filter(Objects::nonNull).map(String::strip).filter(path -> !path.isEmpty()).toList();
        if (selected.isEmpty()) return Async.failed(new IllegalArgumentException("Select Files To Download"));
        Async<Void> result = Async.completed(null);
        for (String remotePath : selected) {
            result = result.thenCompose(ignored -> signer.apply(remotePath).thenCompose(url -> {
                if (url == null || url.isBlank()) return Async.failed(new IllegalStateException("File Download Is Unavailable"));
                if (!Boolean.TRUE.equals(downloader.apply(downloadName(remotePath), url))) {
                    return Async.failed(new UnsupportedOperationException("Browser File Download Is Unavailable"));
                }
                return Async.completed(null);
            }));
        }
        return result;
    }

    private static String downloadName(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String name = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        return name.isBlank() ? "download" : name;
    }

    public Async<ConsoleSession> openConsoleSession(String serverId) {
        return post("/servers/" + path(serverId) + "/console/sessions", Map.of(), BrowserRemotelyServerApi::consoleSession)
                .thenApply(ConsoleSessionView::toModel)
                .thenApply(session -> {
                    if (session != null && session.id() != null && !session.closed()) {
                        synchronized (activeConsoleSessions) {
                            activeConsoleSessions.add(session.id());
                        }
                    }
                    return session;
                });
    }

    public Async<ConsoleSession> getConsoleSession(UUID sessionId) {
        return get("/console/sessions/" + path(String.valueOf(sessionId)), BrowserRemotelyServerApi::consoleSession).thenApply(ConsoleSessionView::toModel);
    }

    public Async<Void> sendConsoleSessionCommand(UUID sessionId, String command) {
        return job("/console/sessions/" + path(String.valueOf(sessionId)) + "/command", Map.of("command", command == null ? "" : command));
    }

    public Async<Void> closeConsoleSession(UUID sessionId) {
        if (sessionId == null) {
            return Async.completed(null);
        }
        synchronized (activeConsoleSessions) {
            activeConsoleSessions.remove(sessionId);
        }
        return delete("/console/sessions/" + path(String.valueOf(sessionId)));
    }

    public Async<TerminalTicket> terminalTicket(String serverId) {
        return post("/servers/" + path(serverId) + "/terminal/ticket", Map.of(), BrowserRemotelyServerApi::terminalTicket).thenApply(TerminalTicketView::toModel);
    }

    public Async<BinaryWebSocket> openTerminal(String serverId, BinaryWebSocketListener listener) {
        return openTerminal(serverId, listener, true);
    }

    private Async<BinaryWebSocket> openTerminal(String serverId, BinaryWebSocketListener listener, boolean retry) {
        return terminalTicket(serverId).thenCompose(ticket -> webSocket.connectAsync(BrowserLaunchSession.terminalUrl(serverId),
                WebSocketOptions.subprotocols(List.of("remotely-web-terminal.v1", ticket.ticket())), listener))
                .exceptionallyCompose(failure -> {
                    if (!retry || !BrowserLaunchSession.authenticated()) {
                        return Async.failed(failure);
                    }
                    return renewAndRetry(() -> openTerminal(serverId, listener, false));
                });
    }

    public TerminalSessionProvider terminalSessionProvider(String serverId) {
        String resolvedServerId = requireServerId(serverId);
        return size -> {
            TerminalSize resolvedSize = size == null ? new TerminalSize(120, 32) : size;
            BrowserTerminalTransport[] holder = new BrowserTerminalTransport[1];
            BrowserTerminalTransport terminal = new BrowserTerminalTransport(
                    listener -> openTerminal(resolvedServerId, listener),
                    () -> removeActiveTerminal(holder[0]));
            holder[0] = terminal;
            synchronized (activeTerminalTransports) {
                activeTerminalTransports.add(terminal);
            }
            return new TerminalSession(UUID.randomUUID().toString(), terminal, resolvedSize);
        };
    }

    private void removeActiveTerminal(BrowserTerminalTransport terminal) {
        if (terminal == null) return;
        synchronized (activeTerminalTransports) {
            activeTerminalTransports.remove(terminal);
        }
    }

    public void closeAllTerminals() {
        List<BrowserTerminalTransport> terminals;
        synchronized (activeTerminalTransports) {
            terminals = new ArrayList<>(activeTerminalTransports);
            activeTerminalTransports.clear();
        }
        terminals.forEach(BrowserTerminalTransport::close);
        Set<UUID> consoleSessions;
        synchronized (activeConsoleSessions) {
            consoleSessions = Set.copyOf(activeConsoleSessions);
            activeConsoleSessions.clear();
        }
        consoleSessions.forEach(this::closeConsoleSession);
    }

    public Async<List<DeveloperDevice>> developerDevices() {
        return getList("/developer/devices", BrowserRemotelyServerApi::developerDevice)
                .thenApply(devices -> devices == null ? List.of() : devices.stream().map(DeveloperDeviceView::toModel).toList());
    }

    public Async<String> developerJob(UUID deviceId, String operation, Object payload) {
        if (deviceId == null) return Async.failed(new IllegalArgumentException("Developer Device Is Required"));
        if (operation == null || operation.isBlank()) return Async.failed(new IllegalArgumentException("Developer Operation Is Required"));
        String serialized = payload instanceof String value ? value : json(payload == null ? Map.of() : payload);
        return post("/developer/devices/" + path(deviceId.toString()) + "/jobs", Map.of("operation", operation, "payload", serialized),
                BrowserRemotelyServerApi::developerJobCreated).thenCompose(created -> awaitDeveloperJob(deviceId, created));
    }

    @Override
    public Async<ServerModels.ReSyncConfig> getReSyncConfig(String serverId) {
        if (isMissingReSync(missingReSyncServers, serverId)) return Async.completed(new ServerModels.ReSyncConfig());
        return reStudioRequest("GET", serverId, "config").thenApply(value -> reSyncConfig(BrowserJson.object(value)))
                .exceptionallyCompose(failure -> {
                    if (!isNotFound(failure)) return Async.failed(failure);
                    rememberMissingReSync(missingReSyncServers, serverId);
                    return Async.completed(new ServerModels.ReSyncConfig());
                });
    }

    @Override
    public Async<String> getReSyncApiKey(String serverId) {
        if (isMissingReSync(missingReSyncServers, serverId) || isMissingReSync(missingReSyncApiKeys, serverId)) return Async.completed("");
        return reStudioRequest("GET", serverId, "api-key").thenApply(value -> BrowserJson.string(BrowserJson.object(value), "apiKey"))
                .exceptionallyCompose(failure -> {
                    if (!isNotFound(failure)) return Async.failed(failure);
                    rememberMissingReSync(missingReSyncApiKeys, serverId);
                    return Async.completed("");
                });
    }

    @Override
    public Async<String> getReSyncVersion(String serverId) {
        if (isMissingReSync(missingReSyncServers, serverId) || isMissingReSync(missingReSyncVersions, serverId)) return Async.completed("");
        return reStudioRequest("GET", serverId, "version").thenApply(value -> BrowserJson.string(BrowserJson.object(value), "version"))
                .exceptionallyCompose(failure -> {
                    if (!isNotFound(failure)) return Async.failed(failure);
                    rememberMissingReSync(missingReSyncVersions, serverId);
                    return Async.completed("");
                });
    }

    @Override
    public Async<ServerModels.ReSyncProvisionResult> provisionReSync(String serverId) {
        clearMissingReSync(serverId);
        return reStudioRequest("POST", serverId, "provision").thenApply(value -> reSyncProvision(BrowserJson.object(value)));
    }

    @Override
    public Async<ServerModels.ReSyncProvisionResult> updateReSync(String serverId) {
        clearMissingReSync(serverId);
        return reStudioRequest("POST", serverId, "update").thenApply(value -> reSyncProvision(BrowserJson.object(value)));
    }

    private Async<Void> job(String endpoint, Object body) {
        return post(endpoint, body, BrowserRemotelyServerApi::capabilityJob).thenCompose(this::await);
    }

    private Async<Void> jobPut(String endpoint, Object body) {
        return put(endpoint, body, BrowserRemotelyServerApi::capabilityJob).thenCompose(this::await);
    }

    private Async<Void> deleteJob(String endpoint) {
        return request("DELETE", endpoint, null).thenApply(value -> decode(BrowserJson.object(value), BrowserRemotelyServerApi::capabilityJob)).thenCompose(this::await);
    }

    private String requireServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) throw new IllegalArgumentException("A server identifier is required");
        return serverId;
    }

    private Async<Void> await(CapabilityJob job) {
        Async<Void> result = Async.pending();
        poll(job, clock.millis() + JOB_TIMEOUT_MILLIS, result);
        return result;
    }

    private Async<String> awaitDeveloperJob(UUID deviceId, DeveloperJobCreatedView created) {
        if (created == null || created.commandId == null) return Async.failed(new IllegalStateException("Developer Job Response Is Invalid"));
        Async<String> result = Async.pending();
        pollDeveloperJob(deviceId, created.commandId, clock.millis() + JOB_TIMEOUT_MILLIS, result);
        return result;
    }

    private void pollDeveloperJob(UUID deviceId, UUID jobId, long deadline, Async<String> result) {
        get("/developer/devices/" + path(deviceId.toString()) + "/jobs/" + path(jobId.toString()), BrowserRemotelyServerApi::developerJobStatus)
                .whenComplete((job, failure) -> {
                    if (failure != null) {
                        result.fail(failure);
                        return;
                    }
                    if (job == null || job.status == null) {
                        result.fail(new IllegalStateException("Developer Job Status Is Invalid"));
                        return;
                    }
                    if ("COMPLETE".equals(job.status)) {
                        result.complete(job.result == null ? "" : job.result);
                        return;
                    }
                    if (List.of("FAILED", "EXPIRED", "REVOKED").contains(job.status)) {
                        result.fail(new IllegalStateException(job.error == null || job.error.isBlank() ? "Developer Job Failed" : job.error));
                        return;
                    }
                    if (clock.millis() >= deadline) {
                        result.fail(new IllegalStateException("Developer Job Timed Out"));
                        return;
                    }
                    scheduler.schedule(() -> pollDeveloperJob(deviceId, jobId, deadline, result), JOB_POLL_DELAY);
                });
    }

    private void poll(CapabilityJob job, long deadline, Async<Void> result) {
        if (job == null || job.status == null) {
            result.fail(new IllegalStateException("Capability job response is invalid"));
            return;
        }
        if ("COMPLETED".equals(job.status)) {
            result.complete(null);
            return;
        }
        if ("FAILED".equals(job.status)) {
            result.fail(new IllegalStateException(job.error == null || job.error.isBlank() ? "Capability job failed" : job.error));
            return;
        }
        if (clock.millis() >= deadline) {
            result.fail(new IllegalStateException("Capability job timed out"));
            return;
        }
        scheduler.schedule(() -> get("/jobs/" + path(job.id), BrowserRemotelyServerApi::capabilityJob).whenComplete((next, failure) -> {
            if (failure != null) {
                result.fail(failure);
            } else {
                poll(next, deadline, result);
            }
        }), JOB_POLL_DELAY);
    }

    private final class BrowserDeveloperProvider implements DeveloperCapabilityProvider {
        private final String serverId;
        private final Workspace workspace = new BrowserWorkspace();
        private final RemoteFileSystemProvider files = new BrowserWorkspaceFiles();
        private volatile Workspace.Binding binding;
        private volatile DeveloperDeviceView boundDevice;
        private volatile List<DeveloperDeviceView> deviceInventory = List.of();
        private volatile boolean deviceInventoryLoaded;
        private final Map<String, String> fileVersions = new LinkedHashMap<>();
        private final Map<String, String> trashVersions = new LinkedHashMap<>();

        private BrowserDeveloperProvider(String serverId) {
            this.serverId = serverId;
        }

        private String workspaceId() {
            BrowserLaunchSession.Metadata session = BrowserLaunchSession.metadata();
            String grantId = session.grantId() == null || session.grantId().isBlank() ? "browser" : session.grantId();
            return grantId + ":" + Integer.toUnsignedString(serverId.hashCode(), 36);
        }

        @Override
        public Workspace workspace() {
            return workspace;
        }

        @Override
        public RemoteFileSystemProvider logicalFilesystem() {
            return files;
        }

        @Override
        public GitJobProvider git(RemotePath root) {
            return new BrowserGitJobs();
        }

        @Override
        public Search search() {
            return new Search() {
                @Override
                public Async<List<SearchMatch>> find(SearchRequest request) {
                    return requireBinding().thenCompose(selected -> {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("query", request.query());
                        payload.put("regex", request.regularExpression());
                        payload.put("caseSensitive", request.caseSensitive());
                        payload.put("maxResults", request.maxResults());
                        return runWorkspaceJob(selected, "developer.search", payload).thenApply(result -> {
                            DeveloperSearchResult value = decode(BrowserJson.object(result), BrowserRemotelyServerApi::developerSearch);
                            if (value == null || value.matches == null) return List.of();
                            return value.matches.stream().map(match -> new SearchMatch(RemotePath.of(match.path), match.line, match.column, match.preview)).toList();
                        });
                    });
                }
            };
        }

        @Override
        public Workflow workflow() {
            return new Workflow() {
                @Override
                public Async<WorkflowExecution> start(String workflowId, Map<String, String> inputs) {
                    return requireBinding().thenCompose(selected -> submitWorkspaceJob(selected, "developer.workflow", Map.of("workflow", workflowId)))
                            .thenApply(created -> new WorkflowExecution(created.jobId.toString(), "RUNNING", ""));
                }

                @Override
                public Async<WorkflowExecution> status(String executionId) {
                    return requireBinding().thenCompose(selected -> workspaceJob(selected, UUID.fromString(executionId))).thenApply(job -> {
                        String output = "";
                        if (job.result != null && job.result.isJsonObject()) {
                            DeveloperWorkflowResult result = decode(BrowserJson.object(job.result), BrowserRemotelyServerApi::developerWorkflow);
                            output = result == null ? "" : String.join("\n", nonBlank(result.stdout, result.stderr));
                        }
                        return new WorkflowExecution(executionId, job.status == null ? "UNKNOWN" : job.status, output);
                    });
                }

                @Override
                public Async<Void> cancel(String executionId) {
                    return requireBinding().thenCompose(selected -> post(workspaceJobPath(selected, UUID.fromString(executionId)) + "/cancel", Map.of(), BrowserRemotelyServerApi::identity)).thenApply(ignored -> null);
                }
            };
        }

        @Override
        public Lsp lsp() {
            return new Lsp() {
                @Override
                public Async<String> open(String language, List<RemotePath> roots) {
                    return requireBinding().thenCompose(selected -> post("/developer/workspaces/" + path(selected.id()) + "/lsp/sessions",
                            Map.of("deviceId", selected.device().id(), "rootId", selected.root().id(), "binding", language), BrowserRemotelyServerApi::developerLspSession))
                            .thenApply(session -> session.sessionId);
                }

                @Override
                public Async<String> request(String sessionId, String method, String payload) {
                    JsonElement message = BrowserJson.parse(payload == null || payload.isBlank() ? "{}" : payload);
                    if (message == null || !message.isJsonObject()) message = new JsonObject();
                    if (method != null && !method.isBlank() && !message.getAsJsonObject().has("method")) {
                        message.getAsJsonObject().addProperty("jsonrpc", "2.0");
                        message.getAsJsonObject().addProperty("method", method);
                    }
                    JsonElement finalMessage = message;
                    return requireBinding().thenCompose(selected -> request("POST", lspPath(selected, sessionId) + "/messages" + lspBindingQuery(selected), BrowserJson.write(finalMessage)))
                            .thenApply(ignored -> "");
                }

                @Override
                public Async<Void> send(String sessionId, String message) {
                    return request(sessionId, "", message).thenApply(ignored -> null);
                }

                @Override
                public Async<Messages> messages(String sessionId, long after, int limit) {
                    return requireBinding().thenCompose(selected -> get(lspPath(selected, sessionId) + "/messages" + lspBindingQuery(selected)
                            + "&after=" + Math.max(0, after) + "&limit=" + Math.clamp(limit, 1, 256), BrowserRemotelyServerApi::developerLspMessages)).thenApply(result -> {
                        List<Message> messages = result == null || result.messages == null ? List.of() : result.messages.stream()
                                .map(message -> new Message(message.sequence, BrowserJson.write(message.message))).toList();
                        return new Messages(messages, result != null && result.running);
                    });
                }

                @Override
                public Async<Void> close(String sessionId) {
                    return requireBinding().thenCompose(selected -> delete(lspPath(selected, sessionId) + lspBindingQuery(selected)));
                }
            };
        }

        @Override
        public AutoCloseable observeJobProgress(Consumer<JobProgress> observer) {
            if (observer == null) return () -> {
            };
            String workspaceId = workspaceId();
            developerProgress.put(workspaceId, observer);
            return () -> developerProgress.remove(workspaceId, observer);
        }

        @Override
        public Async<TerminalSession> openTerminal(TerminalSize size, Consumer<String> outputConsumer) {
            return Async.failed(new UnsupportedOperationException("Developer Terminal Is Unavailable In Remotely Web"));
        }

        @Override
        public Map<String, CapabilityDescriptor> capabilities() {
            Map<String, CapabilityDescriptor> result = new LinkedHashMap<>();
            boolean developerAvailable = hasOnlineDeveloperDevice();
            boolean workspaceAvailable = developerAvailable;
            result.put(CapabilityIds.DEVELOPER, available(CapabilityIds.DEVELOPER, developerAvailable));
            result.put(CapabilityIds.WORKSPACE, available(CapabilityIds.WORKSPACE, workspaceAvailable));
            result.put(CapabilityIds.FILES, available(CapabilityIds.FILES, binding != null && boundDevice != null && boundDevice.developer != null
                    && boundDevice.developer.files && binding.root().read()));
            result.put(CapabilityIds.TRASH, available(CapabilityIds.TRASH, binding != null && binding.root().write() && boundDevice != null
                    && boundDevice.developer != null && boundDevice.developer.fileOperations != null
                    && boundDevice.developer.fileOperations.containsAll(List.of("trash", "trash-list", "restore", "purge"))));
            result.put(CapabilityIds.TRANSFER, available(CapabilityIds.TRANSFER, binding != null && boundDevice != null && boundDevice.developer != null
                    && boundDevice.developer.transfers));
            boolean executable = binding != null && binding.root().execute();
            result.put(CapabilityIds.GIT, available(CapabilityIds.GIT, executable && boundDevice != null && boundDevice.developer != null && boundDevice.developer.git));
            result.put(CapabilityIds.TERMINAL, CapabilityDescriptor.unavailable(CapabilityIds.TERMINAL,
                    "Developer Terminal Is Unavailable In Remotely Web"));
            result.put(CapabilityIds.SEARCH, available(CapabilityIds.SEARCH, binding != null && boundDevice != null && boundDevice.developer != null && boundDevice.developer.search));
            result.put(CapabilityIds.WORKFLOW, available(CapabilityIds.WORKFLOW, hasBinding(boundDevice == null || boundDevice.developer == null ? null : boundDevice.developer.workflows)));
            result.put(CapabilityIds.LSP, available(CapabilityIds.LSP, hasBinding(boundDevice == null || boundDevice.developer == null ? null : boundDevice.developer.lsp)));
            return Map.copyOf(result);
        }

        private boolean hasOnlineDeveloperDevice() {
            return deviceInventoryLoaded && deviceInventory.stream().anyMatch(device -> device != null
                    && !device.revoked && device.online && device.developer != null);
        }

        private CapabilityDescriptor available(String id, boolean available) {
            return available ? CapabilityDescriptor.supported(id) : CapabilityDescriptor.unavailable(id, "Capability Is Not Available For This Folder");
        }

        private boolean hasBinding(List<DeveloperBindingView> bindings) {
            return binding != null && bindings != null && bindings.stream().anyMatch(value -> Objects.equals(value.root, binding.root().id()));
        }

        private Async<Workspace.Binding> requireBinding() {
            return binding == null ? workspace.current().thenCompose(value -> value == null
                    ? Async.failed(new IllegalStateException("Developer Workspace Is Not Selected")) : Async.completed(value)) : Async.completed(binding);
        }

        private String lspPath(Workspace.Binding binding, String sessionId) {
            return "/developer/workspaces/" + path(binding.id()) + "/lsp/sessions/" + path(sessionId);
        }

        private String lspBindingQuery(Workspace.Binding binding) {
            return "?deviceId=" + query(binding.device().id()) + "&rootId=" + query(binding.root().id());
        }

        private final class BrowserWorkspace implements Workspace {
            @Override
            public Async<List<Device>> devices() {
                return getList("/developer/devices", BrowserRemotelyServerApi::developerDevice).thenApply(values -> {
                    deviceInventoryLoaded = true;
                    deviceInventory = values == null ? List.of() : values.stream().filter(value -> value != null && value.deviceId != null && !value.revoked).toList();
                    return deviceInventory.stream().map(DeveloperDeviceView::toWorkspaceDevice).toList();
                });
            }

            @Override
            public Async<List<Root>> roots(String deviceId) {
                return getList("/developer/devices/" + path(deviceId) + "/roots", BrowserRemotelyServerApi::developerRoot)
                        .thenApply(values -> values == null ? List.of() : values.stream().map(DeveloperRootView::toModel).toList());
            }

            @Override
            public Async<Binding> current() {
                if (binding != null) return Async.completed(binding);
                return getOptional("/developer/workspaces/" + path(workspaceId()) + "/binding", BrowserRemotelyServerApi::developerBinding)
                        .thenCompose(BrowserWorkspace.this::resolve);
            }

            @Override
            public Async<Binding> bind(String deviceId, String rootId) {
                return put("/developer/workspaces/" + path(workspaceId()) + "/binding", Map.of("deviceId", deviceId, "rootId", rootId, "serverId", serverId),
                        BrowserRemotelyServerApi::developerBinding).thenCompose(BrowserWorkspace.this::resolve);
            }

            @Override
            public Async<Void> clear() {
                return delete("/developer/workspaces/" + path(workspaceId()) + "/binding").thenApply(ignored -> {
                    binding = null;
                    boundDevice = null;
                    return null;
                });
            }

            private Async<Binding> resolve(DeveloperWorkspaceBindingView value) {
                if (value == null || value.deviceId == null || value.rootId == null) return Async.completed(null);
                return getList("/developer/devices", BrowserRemotelyServerApi::developerDevice).thenCompose(devices -> {
                    deviceInventoryLoaded = true;
                    deviceInventory = devices == null ? List.of() : devices.stream().filter(device -> device != null && device.deviceId != null && !device.revoked).toList();
                    boundDevice = deviceInventory.stream().filter(device -> value.deviceId.equals(device.deviceId)).findFirst().orElse(null);
                    if (boundDevice == null || !boundDevice.online) return Async.failed(new IllegalStateException("Bound Developer Device Is Offline"));
                    return roots(value.deviceId.toString()).thenApply(roots -> roots.stream().filter(root -> root.id().equals(value.rootId)).findFirst().map(root -> {
                        binding = new Binding(value.workspaceId == null ? workspaceId() : value.workspaceId, boundDevice.toWorkspaceDevice(), root);
                        return binding;
                    }).orElseThrow(() -> new IllegalStateException("Bound Developer Folder Is Unavailable")));
                });
            }
        }

        private final class BrowserWorkspaceFiles implements RemoteFileSystemProvider {
            @Override
            public Async<List<FileEntry>> ls(RemotePath path) {
                return file("list", path, null, null, null, false, false).thenApply(result -> {
                    if (result == null || result.entries == null) return List.of();
                    return result.entries.stream().map(entry -> {
                        RemotePath logical = logical(entry.path);
                        if (entry.version != null) fileVersions.put(relative(logical), entry.version);
                        return new FileEntry(logical, entry.directory, entry.directory ? "-" : String.valueOf(entry.size),
                                String.valueOf(entry.modifiedAt), entry.name,
                                entry.version == null ? Map.of() : Map.of("version", entry.version));
                    }).toList();
                });
            }

            @Override
            public Async<String> read(RemotePath path) {
                return file("read", path, null, null, null, false, false).thenApply(result -> {
                    if (result.version != null) fileVersions.put(relative(path), result.version);
                    return result.content == null ? "" : result.content;
                });
            }

            @Override
            public Async<Void> write(RemotePath path, String content) {
                return file("write", path, null, content, version(path), false, false).thenApply(result -> {
                    remember(path, result);
                    return null;
                });
            }

            @Override
            public Async<Void> createFile(RemotePath path) {
                return file("create", path, null, "", null, false, false).thenApply(result -> {
                    remember(path, result);
                    return null;
                });
            }

            @Override
            public Async<Void> createDirectory(RemotePath path) {
                return file("create", path, null, null, null, true, false).thenApply(result -> {
                    remember(path, result);
                    return null;
                });
            }

            @Override
            public Async<Void> move(List<RemotePath> sources, RemotePath destination) {
                if (sources == null || sources.isEmpty()) return Async.completed(null);
                Async<?>[] moves = sources.stream().map(source -> {
                    RemotePath target = destination.resolve(source.fileName());
                    return file("move", source, target, null, version(source), false, false).thenAccept(result -> {
                        fileVersions.remove(relative(source));
                        remember(target, result);
                    });
                }).toArray(Async[]::new);
                return Async.allOf(moves);
            }

            @Override
            public Async<Void> copy(List<RemotePath> sources, RemotePath destination) {
                if (sources == null || sources.isEmpty()) return Async.completed(null);
                Async<?>[] copies = sources.stream().map(source -> {
                    RemotePath target = destination.resolve(source.fileName());
                    return file("copy", source, target, null, version(source), false, false).thenAccept(result -> remember(target, result));
                }).toArray(Async[]::new);
                return Async.allOf(copies);
            }

            @Override
            public Async<Void> rename(RemotePath oldPath, RemotePath newPath) {
                return file("move", oldPath, newPath, null, version(oldPath), false, false).thenApply(result -> {
                    fileVersions.remove(relative(oldPath));
                    remember(newPath, result);
                    return null;
                });
            }

            @Override
            public Async<Void> delete(List<RemotePath> paths) {
                if (paths == null || paths.isEmpty()) return Async.completed(null);
                Async<?>[] deletes = paths.stream().map(path -> file("delete", path, null, null, version(path), false, true).thenAccept(ignored ->
                        fileVersions.remove(relative(path)))).toArray(Async[]::new);
                return Async.allOf(deletes);
            }

            @Override
            public Async<Void> deleteToTrash(List<RemotePath> paths) {
                if (paths == null || paths.isEmpty()) return Async.completed(null);
                Async<?>[] trashed = paths.stream().map(path -> file("trash", path, null, null, version(path), false, false).thenAccept(result -> {
                    fileVersions.remove(relative(path));
                    if (result != null && result.trashId != null && result.version != null) trashVersions.put(result.trashId, result.version);
                })).toArray(Async[]::new);
                return Async.allOf(trashed);
            }

            @Override
            public Async<List<TrashEntry>> trash() {
                return file("trash-list", null, null, null, null, false, false).thenApply(result -> {
                    if (result == null || result.trash == null) return List.of();
                    return result.trash.stream().map(entry -> {
                        trashVersions.put(entry.id, entry.version);
                        return new TrashEntry(entry.id, logical(entry.originalPath), false, 0, String.valueOf(entry.trashedAt), entry.version);
                    }).toList();
                });
            }

            @Override
            public Async<Void> restoreTrash(List<String> ids) {
                return trashMutation("restore", ids);
            }

            @Override
            public Async<Void> purgeTrash(List<String> ids) {
                return trashMutation("purge", ids);
            }

            @Override
            public Async<Boolean> exists(RemotePath path) {
                if (path == null || path.equals(root())) return Async.completed(true);
                return ls(path.parent()).thenApply(entries -> entries.stream().anyMatch(entry -> entry.path().equals(path)));
            }

            @Override
            public void invalidateCache(RemotePath path) {
                if (path == null) fileVersions.clear();
                else fileVersions.remove(relative(path));
            }

            @Override
            public String getMetadata(String key) {
                return switch (key) {
                    case "type" -> "BROWSER_WORKSPACE";
                    case "workspaceId", "host", "hostId" -> workspaceId();
                    case "serverId" -> serverId;
                    case "virtualRoot" -> binding == null ? null : binding.device().id() + ":" + binding.root().id();
                    default -> null;
                };
            }

            @Override
            public Async<Void> upload(List<TransferSource> sources, RemotePath destination) {
                return upload(sources, destination, (sent, total) -> {
                });
            }

            @Override
            public Async<Void> upload(List<TransferSource> sources, RemotePath destination, BiConsumer<Long, Long> progressCallback) {
                if (boundDevice == null || boundDevice.developer == null || !boundDevice.developer.transfers || binding == null || !binding.root().write()) {
                    if (sources != null) sources.forEach(BrowserTransferBridge::release);
                    return Async.failed(new UnsupportedOperationException("Workspace Upload Is Unavailable"));
                }
                if (sources == null || sources.isEmpty()) return Async.completed(null);
                Async<Void> result = Async.pending();
                BiConsumer<Long, Long> throttledProgress = BrowserServerFileTransfer.throttleProgress(progressCallback);
                UUID[] activeUpload = new UUID[1];
                result.onCancel(() -> {
                    UUID uploadId = activeUpload[0];
                    if (uploadId != null && binding != null) BrowserRemotelyServerApi.this.delete(uploadPath(binding, uploadId));
                    sources.stream().filter(source -> source instanceof BrowserTransferBridge.BrowserFileSource)
                            .map(source -> (BrowserTransferBridge.BrowserFileSource) source)
                            .forEach(BrowserTransferBridge.BrowserFileSource::release);
                });
                long total = sources.stream().mapToLong(source -> Math.max(0, source.size())).sum();
                if (throttledProgress != null) throttledProgress.accept(0L, total);
                uploadNext(sources, 0, destination, 0, total, throttledProgress, activeUpload, result);
                return result;
            }

            @Override
            public Async<Void> download(List<RemotePath> sources, TransferSink destination) {
                return download(sources, destination, (received, total) -> {
                }, () -> false);
            }

            @Override
            public Async<Void> download(List<RemotePath> sources, TransferSink destination, BiConsumer<Long, Long> progressCallback,
                                        BooleanSupplier isCancelled) {
                if (boundDevice == null || boundDevice.developer == null || !boundDevice.developer.transfers || binding == null || !binding.root().read()) {
                    return Async.failed(new UnsupportedOperationException("Workspace Download Is Unavailable"));
                }
                if (sources == null || sources.size() != 1) return Async.failed(new IllegalArgumentException("Select One File To Download"));
                RemotePath source = sources.getFirst();
                String version = fileVersions.get(relative(source));
                return requireBinding().thenCompose(selected -> post("/developer/workspaces/" + BrowserRemotelyServerApi.path(selected.id()) + "/downloads",
                        Map.of("deviceId", selected.device().id(), "rootId", selected.root().id(), "path", relative(source), "version", version == null ? "" : version),
                        BrowserRemotelyServerApi::developerDownload)).thenCompose(ticket -> streamDownload(ticket, destination, progressCallback, isCancelled, true));
            }

            @Override
            public Map<String, CapabilityDescriptor> capabilities() {
                Map<String, CapabilityDescriptor> capabilities = BrowserDeveloperProvider.this.capabilities();
                return Map.of(
                        CapabilityIds.FILES, capabilities.getOrDefault(CapabilityIds.FILES, CapabilityDescriptor.unavailable(CapabilityIds.FILES, "Workspace Files Are Unavailable")),
                        CapabilityIds.TRASH, capabilities.getOrDefault(CapabilityIds.TRASH, CapabilityDescriptor.unavailable(CapabilityIds.TRASH, "Workspace Trash Is Unavailable")),
                        CapabilityIds.TRANSFER, capabilities.getOrDefault(CapabilityIds.TRANSFER, CapabilityDescriptor.unavailable(CapabilityIds.TRANSFER, "Workspace Transfers Are Unavailable")));
            }

            private <T> Async<T> unavailable() {
                return Async.failed(new UnsupportedOperationException("Workspace File Operation Is Unavailable"));
            }

            private RemotePath root() {
                return binding == null ? RemotePath.root() : binding.root().path();
            }

            private String relative(RemotePath path) {
                RemotePath value = path == null ? root() : path;
                String relative = value.startsWith(root()) ? root().relativize(value).asString() : value.asString();
                return ".".equals(relative) ? "" : relative;
            }

            private RemotePath logical(String path) {
                return root().resolve(path == null || path.isBlank() ? "." : path);
            }

            private String version(RemotePath path) {
                return fileVersions.get(relative(path));
            }

            private void remember(RemotePath path, DeveloperFileResult result) {
                if (result != null && result.version != null) fileVersions.put(relative(path), result.version);
            }

            private Async<DeveloperFileResult> file(String operation, RemotePath path, RemotePath target, String content, String expectedVersion,
                                                    boolean directory, boolean recursive) {
                if (!supportsFile(operation)) return Async.failed(new UnsupportedOperationException("Workspace File Operation Is Unavailable"));
                if (Set.of("write", "copy", "move", "delete", "trash").contains(operation) && (expectedVersion == null || expectedVersion.isBlank())) {
                    return Async.failed(new IllegalStateException("Refresh The Folder Before Changing This File"));
                }
                return requireBinding().thenCompose(selected -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("deviceId", selected.device().id());
                    body.put("rootId", selected.root().id());
                    body.put("operation", operation);
                    String relativePath = path == null ? "" : relative(path);
                    if (!relativePath.isBlank()) body.put("path", relativePath);
                    if (target != null) body.put("targetPath", relative(target));
                    if (content != null) body.put("content", content);
                    if ("create".equals(operation)) body.put("directory", directory);
                    if ("delete".equals(operation)) body.put("recursive", recursive);
                    if (expectedVersion != null) body.put("expectedVersion", expectedVersion);
                    return post("/developer/workspaces/" + BrowserRemotelyServerApi.path(selected.id()) + "/files", body, BrowserRemotelyServerApi::developerFileResult);
                });
            }

            private Async<Void> trashMutation(String operation, List<String> ids) {
                if (ids == null || ids.isEmpty()) return Async.completed(null);
                Async<?>[] mutations = ids.stream().map(id -> {
                    String expected = trashVersions.get(id);
                    if (expected == null || expected.isBlank()) return Async.failed(new IllegalStateException("Refresh Trash Before Changing This Item"));
                    return requireBinding().thenCompose(selected -> post("/developer/workspaces/" + BrowserRemotelyServerApi.path(selected.id()) + "/files",
                            Map.of("deviceId", selected.device().id(), "rootId", selected.root().id(), "operation", operation, "trashId", id,
                                    "expectedVersion", expected), BrowserRemotelyServerApi::developerFileResult)).thenAccept(result -> {
                        trashVersions.remove(id);
                        if ("restore".equals(operation) && result != null && result.path != null && result.version != null) {
                            fileVersions.put(result.path, result.version);
                        }
                    });
                }).toArray(Async[]::new);
                return Async.allOf(mutations);
            }

            private void uploadNext(List<TransferSource> sources, int index, RemotePath destination, long completed, long total,
                                    BiConsumer<Long, Long> progress, UUID[] activeUpload, Async<Void> result) {
                if (result.isDone()) return;
                if (index >= sources.size()) {
                    result.complete(null);
                    return;
                }
                TransferSource source = sources.get(index);
                if (source.size() < 0) {
                    result.fail(new IllegalArgumentException("Upload Size Is Required"));
                    return;
                }
                RemotePath target = destination.resolve(source.name());
                requireBinding().thenCompose(selected -> {
                    String expected = fileVersions.get(relative(target));
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("deviceId", selected.device().id());
                    body.put("rootId", selected.root().id());
                    body.put("path", relative(target));
                    body.put("size", source.size());
                    body.put("overwrite", expected != null);
                    if (expected != null) body.put("expectedVersion", expected);
                    return post("/developer/workspaces/" + BrowserRemotelyServerApi.path(selected.id()) + "/uploads", body, BrowserRemotelyServerApi::developerUpload);
                }).whenComplete((upload, failure) -> {
                    if (failure != null) {
                        result.fail(failure);
                        return;
                    }
                    activeUpload[0] = upload.uploadId;
                    uploadChunk(source, upload, completed, total, progress, result).whenComplete((file, uploadFailure) -> {
                        if (uploadFailure != null) {
                            result.fail(uploadFailure);
                            return;
                        }
                        activeUpload[0] = null;
                        remember(target, file);
                        uploadNext(sources, index + 1, destination, completed + source.size(), total, progress, activeUpload, result);
                    });
                });
            }

            private Async<DeveloperFileResult> uploadChunk(TransferSource source, DeveloperUploadView upload, long completed, long total,
                                                            BiConsumer<Long, Long> progress, Async<Void> owner) {
                if (owner.isDone()) return Async.failed(new Async.Cancellation());
                if (upload.offset >= upload.size) {
                    return post(uploadPath(binding, upload.uploadId) + "/complete", Map.of(), BrowserRemotelyServerApi::developerFileResult);
                }
                return source.next().thenCompose(chunk -> {
                    if (chunk.bytes().length == 0 && upload.offset < upload.size) return Async.failed(new IllegalStateException("Upload Ended Before Its Declared Size"));
                    long offset = upload.offset;
                    BiConsumer<Long, Long> chunkProgress = progress == null ? null
                            : (loaded, ignored) -> progress.accept(completed + offset + Math.min(chunk.bytes().length, loaded), total);
                    return deliverUploadChunk(binding, upload.uploadId, offset, chunk.bytes(), chunkProgress);
                }).thenCompose(next -> {
                    if (progress != null) progress.accept(completed + next.offset, total);
                    return uploadChunk(source, next, completed, total, progress, owner);
                });
            }

            private String uploadPath(Workspace.Binding binding, UUID uploadId) {
                return "/developer/workspaces/" + BrowserRemotelyServerApi.path(binding.id()) + "/uploads/" + BrowserRemotelyServerApi.path(uploadId.toString());
            }

            private Async<DeveloperUploadView> deliverUploadChunk(Workspace.Binding binding, UUID uploadId, long offset, byte[] bytes,
                                                                   BiConsumer<Long, Long> progress) {
                return putUploadChunk(binding, uploadId, offset, bytes, progress)
                        .exceptionallyCompose(failure -> get(uploadPath(binding, uploadId), BrowserRemotelyServerApi::developerUpload))
                        .thenCompose(current -> {
                            long end = offset + bytes.length;
                            if (current.offset == end) return Async.completed(current);
                            if (current.offset == offset) return deliverUploadChunk(binding, uploadId, offset, bytes, progress);
                            return Async.failed(new IllegalStateException("Upload Resume Offset Is Invalid"));
                        });
            }

            private Async<Void> streamDownload(DeveloperDownloadTicket ticket, TransferSink sink,
                                               BiConsumer<Long, Long> progress,
                                               BooleanSupplier cancelled, boolean retry) {
                String url = ticket.url.startsWith("http://") || ticket.url.startsWith("https://")
                        ? ticket.url : URI.create(baseUrl).resolve(ticket.url).toString();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/octet-stream")
                        .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket()).timeout(Duration.ofMinutes(10)).GET().build();
                Async<Void> result = Async.pending();
                AsyncChain writes = new AsyncChain();
                LongValue received = new LongValue();
                Async<HttpResponse<Void>> download = transport.sendStreaming(request, bytes -> {
                    if (result.isDone() || cancelled != null && cancelled.getAsBoolean()) throw new Async.Cancellation();
                    long transferred = received.value += bytes.length;
                    writes.value = writes.value.thenCompose(ignored -> sink.write(new TransferSource.Chunk(bytes, transferred >= ticket.size))
                            .thenRun(() -> {
                                if (progress != null) progress.accept(transferred, ticket.size);
                            }));
                });
                result.onCancel(() -> {
                    download.cancel();
                    sink.close();
                });
                download.whenComplete((response, failure) -> {
                    if (failure != null) {
                        sink.close();
                        result.fail(failure);
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                            renewAndRetry(() -> streamDownload(ticket, sink, progress, cancelled, false))
                                    .whenComplete((ignored, retryFailure) -> {
                                        if (retryFailure == null) result.complete(null);
                                        else result.fail(retryFailure);
                                    });
                            return;
                        }
                        sink.close();
                        result.fail(response.statusCode() == 401 ? expiredFailure(new IllegalStateException("Browser Session Expired")) : capabilityFailure(response.statusCode()));
                        return;
                    }
                    writes.value.thenCompose(ignored -> sink.close()).whenComplete((ignored, writeFailure) -> {
                        if (writeFailure == null) result.complete(null);
                        else result.fail(writeFailure);
                    });
                });
                return result;
            }

            private boolean supportsFile(String operation) {
                if (boundDevice == null || boundDevice.developer == null || !boundDevice.developer.files) return false;
                List<String> operations = boundDevice.developer.fileOperations;
                if (operations != null && !operations.contains(operation)) return false;
                return binding != null && binding.root().read() && (!Set.of("write", "create", "copy", "move", "delete", "trash", "restore", "purge").contains(operation)
                        || binding.root().write());
            }
        }

        private final class BrowserGitJobs implements GitJobProvider {
            @Override
            public Set<Operation> operations() {
                if (boundDevice == null || boundDevice.developer == null || !boundDevice.developer.git) return Set.of();
                Set<Operation> result = new LinkedHashSet<>();
                if (supportsGit("status")) result.add(Operation.STATUS);
                if (supportsGit("diff")) result.add(Operation.DIFF);
                if (supportsGit("log")) result.add(Operation.LOG);
                if (supportsGit("commit-files")) result.add(Operation.COMMIT_FILES);
                if (supportsGit("branch")) result.add(Operation.BRANCH);
                if (supportsGit("stage")) result.add(Operation.STAGE);
                if (supportsGit("commit")) result.add(Operation.COMMIT);
                if (supportsGit("push")) result.add(Operation.PUSH);
                if (supportsGit("pull")) result.add(Operation.PULL);
                if (supportsGit("stash")) {
                    result.add(Operation.STASH_PUSH);
                    result.add(Operation.STASH_LIST);
                    result.add(Operation.STASH_APPLY);
                    result.add(Operation.STASH_POP);
                    result.add(Operation.STASH_DROP);
                }
                if (binding != null && !binding.root().write()) result.removeAll(Set.of(Operation.STAGE, Operation.COMMIT, Operation.PUSH,
                        Operation.PULL, Operation.STASH_PUSH, Operation.STASH_APPLY, Operation.STASH_POP, Operation.STASH_DROP));
                return Set.copyOf(result);
            }

            private boolean supportsGit(String operation) {
                List<String> operations = boundDevice.developer.gitOperations;
                return operations == null ? Set.of("status", "diff", "log", "branch", "commit", "push", "pull").contains(operation) : operations.contains(operation);
            }

            @Override public Async<Void> refreshIndex() { return Async.completed(null); }
            @Override public Async<Boolean> isGitInstalled() { return Async.completed(true); }
            @Override public Async<Boolean> isGitRepo() { return getStatus().thenApply(ignored -> true); }

            @Override
            public Async<GitStatus> getStatus() {
                return git("status", Map.of()).thenApply(result -> {
                    DeveloperGitStatus status = result.status;
                    if (status == null) throw developerGitFailure(result);
                    List<GitFileStatus> files = status.entries == null ? List.of() : status.entries.stream().map(entry -> new GitFileStatus(entry.path,
                            gitStatus(entry.index), gitStatus(entry.worktree), entry.originalPath)).toList();
                    return new GitStatus(status.branch, "", status.ahead, status.behind, files);
                });
            }

            @Override public Async<String> diff(String file) { return git("diff", Map.of("paths", List.of(file))).thenApply(BrowserGitJobs::diff); }
            @Override public Async<String> diff(String hash, String file) { return git("diff", Map.of("base", hash, "paths", List.of(file))).thenApply(BrowserGitJobs::diff); }
            @Override public Async<Void> add(List<String> files) { return gitVoid("stage", Map.of("paths", files)); }
            @Override public Async<Void> reset(List<String> files) { return gitVoid("unstage", Map.of("paths", files)); }

            @Override
            public Async<List<GitCommit>> getLog(int max, String branch) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("limit", max);
                if (branch != null && !branch.isBlank()) payload.put("head", branch);
                return git("log", payload).thenApply(result -> result.commits == null ? List.of() : result.commits.stream().map(commit -> new GitCommit(commit.hash,
                        commit.hash == null ? "" : commit.hash.substring(0, Math.min(7, commit.hash.length())), commit.parents == null ? List.of() : commit.parents,
                        commit.authorName, commit.authorEmail, IsoTimes.parse(commit.authoredAt), commit.subject, "")).toList());
            }

            @Override
            public Async<List<String>> getCommitFiles(String hash) {
                return git("commit-files", Map.of("head", hash == null ? "HEAD" : hash)).thenApply(result -> result.files == null ? List.of()
                        : result.files.stream().map(file -> file.path).filter(Objects::nonNull).toList());
            }

            @Override
            public Async<List<GitBranch>> getBranches() {
                return git("branch", Map.of("branchAction", "list")).thenApply(result -> result.branches == null ? List.of() : result.branches.stream()
                        .map(branch -> new GitBranch(branch.name, branch.current, false, branch.upstream)).toList());
            }

            @Override public Async<Void> checkout(String branch) { return gitVoid("branch", Map.of("branchAction", "switch", "branch", branch)); }
            @Override public Async<Void> createBranch(String name) { return gitVoid("branch", Map.of("branchAction", "create", "branch", name)); }
            @Override public Async<Void> deleteBranch(String name) { return gitVoid("branch", Map.of("branchAction", "delete", "branch", name)); }
            @Override public Async<Void> commit(String message) { return gitVoid("commit", Map.of("message", message)); }
            @Override public Async<Void> commit(String message, List<String> files) { return gitVoid("commit", Map.of("message", message, "paths", files)); }
            @Override public Async<Void> push() { return gitVoid("push", Map.of()); }
            @Override public Async<Void> pull() { return gitVoid("pull", Map.of()); }
            @Override public Async<Void> stash() { return gitVoid("stash", Map.of("stashAction", "push", "includeUntracked", true)); }
            @Override public Async<Void> popStash() { return gitVoid("stash", Map.of("stashAction", "pop")); }
            @Override public Async<Void> applyStash(String id) { return gitVoid("stash", Map.of("stashAction", "apply", "stashId", id)); }
            @Override public Async<Void> dropStash(int index) { return gitVoid("stash", Map.of("stashAction", "drop", "stashIndex", index)); }

            @Override
            public Async<List<GitStashEntry>> listStash() {
                return git("stash", Map.of("stashAction", "list")).thenApply(result -> result.stashes == null ? List.of() : result.stashes.stream()
                        .map(stash -> new GitStashEntry(stash.index, stash.message == null ? "" : stash.message, stash.branch == null ? "" : stash.branch,
                                Instant.EPOCH, stash.ref == null ? "" : stash.ref, stash.id == null ? "" : stash.id)).toList());
            }

            private Async<DeveloperGitResult> git(String operation, Map<String, ?> payload) {
                Map<String, Object> request = new LinkedHashMap<>(payload);
                request.put("operation", operation);
                return requireBinding().thenCompose(selected -> runWorkspaceJob(selected, "developer.git", request))
                        .thenApply(value -> decode(BrowserJson.object(value), BrowserRemotelyServerApi::developerGit));
            }

            private Async<Void> gitVoid(String operation, Map<String, ?> payload) {
                return git(operation, payload).thenApply(result -> {
                    if (result == null || result.exitCode != 0) throw developerGitFailure(result);
                    return null;
                });
            }

            private static String diff(DeveloperGitResult result) {
                if (result == null || result.exitCode != 0) throw developerGitFailure(result);
                return result.diff == null ? "" : result.diff;
            }
        }
    }

    private Async<DeveloperWorkspaceJobCreated> submitWorkspaceJob(DeveloperCapabilityProvider.Workspace.Binding binding, String operation, Object payload) {
        return post("/developer/workspaces/" + path(binding.id()) + "/jobs", Map.of("deviceId", binding.device().id(), "rootId", binding.root().id(),
                "operation", operation, "payload", payload == null ? Map.of() : payload), BrowserRemotelyServerApi::developerWorkspaceJobCreated);
    }

    private Async<JsonElement> runWorkspaceJob(DeveloperCapabilityProvider.Workspace.Binding binding, String operation, Object payload) {
        return submitWorkspaceJob(binding, operation, payload).thenCompose(created -> awaitWorkspaceJob(binding, created));
    }

    private Async<JsonElement> awaitWorkspaceJob(DeveloperCapabilityProvider.Workspace.Binding binding, DeveloperWorkspaceJobCreated created) {
        if (created == null || created.jobId == null) return Async.failed(new IllegalStateException("Developer Job Response Is Invalid"));
        Async<JsonElement> result = Async.pending();
        result.onCancel(() -> post(workspaceJobPath(binding, created.jobId) + "/cancel", Map.of(), BrowserRemotelyServerApi::identity));
        pollWorkspaceJob(binding, created.jobId, clock.millis() + JOB_TIMEOUT_MILLIS, result);
        return result;
    }

    private void pollWorkspaceJob(DeveloperCapabilityProvider.Workspace.Binding binding, UUID jobId, long deadline, Async<JsonElement> result) {
        if (result.isDone()) return;
        workspaceJob(binding, jobId).whenComplete((job, failure) -> {
            if (result.isDone()) return;
            if (failure != null) {
                result.fail(failure);
            } else if (job == null || job.status == null) {
                result.fail(new IllegalStateException("Developer Job Status Is Invalid"));
            } else if ("completed".equalsIgnoreCase(job.status)) {
                result.complete(job.result == null ? new JsonObject() : job.result);
            } else if (List.of("failed", "canceled", "timed_out").contains(job.status.toLowerCase(Locale.ROOT))) {
                result.fail(new IllegalStateException(job.error == null || job.error.isBlank() ? "Developer Job Failed" : job.error));
            } else if (clock.millis() >= deadline) {
                post(workspaceJobPath(binding, jobId) + "/cancel", Map.of(), BrowserRemotelyServerApi::identity);
                result.fail(new IllegalStateException("Developer Job Timed Out"));
            } else {
                Consumer<DeveloperCapabilityProvider.JobProgress> observer = developerProgress.get(binding.id());
                if (observer != null) observer.accept(new DeveloperCapabilityProvider.JobProgress(jobId.toString(), job.status,
                        job.progress == null ? "" : BrowserJson.write(job.progress)));
                scheduler.schedule(() -> pollWorkspaceJob(binding, jobId, deadline, result), JOB_POLL_DELAY);
            }
        });
    }

    private Async<DeveloperWorkspaceJobView> workspaceJob(DeveloperCapabilityProvider.Workspace.Binding binding, UUID jobId) {
        return get(workspaceJobPath(binding, jobId), BrowserRemotelyServerApi::developerWorkspaceJob);
    }

    private String workspaceJobPath(DeveloperCapabilityProvider.Workspace.Binding binding, UUID jobId) {
        return "/developer/workspaces/" + path(binding.id()) + "/jobs/" + path(jobId.toString());
    }

    private static GitFileStatus.Status gitStatus(String value) {
        return value == null || value.isEmpty() ? GitFileStatus.Status.UNMODIFIED : GitFileStatus.Status.fromChar(value.charAt(0));
    }

    private static IllegalStateException developerGitFailure(DeveloperGitResult result) {
        String detail = result == null ? "Git Job Returned No Result" : result.stderr;
        return new IllegalStateException(detail == null || detail.isBlank() ? "Git Operation Failed" : detail);
    }

    private static List<String> nonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(value);
        return result;
    }

    private static String json(Object value) {
        return BrowserJson.write(jsonElement(value));
    }

    private static JsonElement jsonElement(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement element) return element;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (value instanceof UUID uuid) return new JsonPrimitive(uuid.toString());
        if (value instanceof ReProxyDomainRequest request) return jsonElement(Map.of("subdomain", request.subdomain() == null ? "" : request.subdomain()));
        if (value instanceof Map<?, ?> map) {
            JsonObject result = new JsonObject();
            map.forEach((key, item) -> BrowserJson.put(result, String.valueOf(key), jsonElement(item)));
            return result;
        }
        if (value instanceof Iterable<?> values) {
            JsonArray result = new JsonArray();
            values.forEach(item -> result.add(jsonElement(item)));
            return result;
        }
        throw new IllegalArgumentException("Unsupported Browser JSON Value");
    }

    private static Map<String, String> stringMap(JsonObject value) {
        Map<String, String> result = new LinkedHashMap<>();
        value.entrySet().forEach(entry -> {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), entry.getValue().getAsString());
        });
        return Map.copyOf(result);
    }

    private static Map<String, Object> objectMap(JsonObject value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.entrySet().forEach(entry -> result.put(entry.getKey(), javaValue(entry.getValue())));
        return result;
    }

    private static Object javaValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) return objectMap(value.getAsJsonObject());
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            value.getAsJsonArray().forEach(item -> result.add(javaValue(item)));
            return List.copyOf(result);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsDouble();
        return primitive.getAsString();
    }

    private static String first(JsonObject value, String... names) {
        for (String name : names) {
            JsonElement element = BrowserJson.element(value, name);
            if (element != null && element.isJsonPrimitive()) return element.getAsString();
        }
        return null;
    }

    private static String nullableString(JsonObject value, String name) {
        return BrowserJson.string(value, name, null);
    }

    private static boolean firstBoolean(JsonObject value, boolean fallback, String... names) {
        for (String name : names) if (BrowserJson.element(value, name) != null) return BrowserJson.bool(value, name, fallback);
        return fallback;
    }

    private static JsonObject child(JsonObject value, String name) {
        return BrowserJson.object(BrowserJson.element(value, name));
    }

    private static List<String> stringsOrNull(JsonObject value, String name) {
        return BrowserJson.element(value, name) == null ? null : BrowserJson.strings(value, name);
    }

    private static Integer nullableInteger(JsonObject value, String name) {
        return BrowserJson.element(value, name) == null ? null : BrowserJson.integer(value, name, 0);
    }

    private static Boolean nullableBoolean(JsonObject value, String... names) {
        for (String name : names) {
            if (BrowserJson.element(value, name) != null) return BrowserJson.bool(value, name, false);
        }
        return null;
    }

    private static Long nullableLong(JsonObject value, String name) {
        return BrowserJson.element(value, name) == null ? null : BrowserJson.longValue(value, name, 0);
    }

    private static UUID uuid(JsonObject value, String name) {
        String text = BrowserJson.string(value, name);
        return text.isBlank() ? null : UUID.fromString(text);
    }

    private static UUID safeUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static Map<String, VersionSettingsCatalog.Software> catalogSoftware(String response) {
        Map<String, VersionSettingsCatalog.Software> result = new LinkedHashMap<>();
        BrowserJson.object(response).entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonObject()) return;
            JsonObject value = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new VersionSettingsCatalog.Software(BrowserJson.string(value, "type"), BrowserJson.strings(value, "categories"), BrowserJson.strings(value, "compatibility")));
        });
        return Map.copyOf(result);
    }

    static HostedCatalog hostedCatalog(JsonObject value) {
        List<HostedEgg> eggs = new ArrayList<>();
        JsonArray eggValues = value.has("eggs") && value.get("eggs").isJsonArray() ? value.getAsJsonArray("eggs") : new JsonArray();
        eggValues.forEach(element -> { if (element.isJsonObject()) eggs.add(hostedEgg(element.getAsJsonObject())); });
        List<HostedPlan> plans = new ArrayList<>();
        JsonArray planValues = value.has("plans") && value.get("plans").isJsonArray() ? value.getAsJsonArray("plans") : new JsonArray();
        planValues.forEach(element -> { if (element.isJsonObject()) plans.add(hostedPlan(element.getAsJsonObject())); });
        return new HostedCatalog(nullableInteger(value, "defaultEggId"), List.copyOf(eggs), List.copyOf(plans));
    }

    private static HostedServerCatalog hostedServerCatalog(JsonObject value) {
        return new HostedServerCatalog(BrowserJson.string(value, "serverId"), nullableInteger(value, "eggId"), BrowserJson.string(value, "currentRuntimeImage"), stringMap(child(value, "runtimeImages")), startupVariables(value), BrowserJson.bool(value, "reinstallRequired", false), BrowserJson.string(value, "reinstallReason"));
    }

    private static HostedEgg hostedEgg(JsonObject value) {
        return new HostedEgg(nullableInteger(value, "eggId"), BrowserJson.string(value, "name"), BrowserJson.string(value, "description"), stringMap(child(value, "runtimeImages")), startupVariables(value), BrowserJson.bool(value, "reinstallRequired", false));
    }

    private static List<HostedStartupVariable> startupVariables(JsonObject value) {
        List<HostedStartupVariable> result = new ArrayList<>();
        JsonArray variables = value.has("startup") && value.get("startup").isJsonArray() ? value.getAsJsonArray("startup") : new JsonArray();
        variables.forEach(element -> {
            if (!element.isJsonObject()) return;
            JsonObject variable = element.getAsJsonObject();
            result.add(new HostedStartupVariable(BrowserJson.string(variable, "key"), BrowserJson.string(variable, "name"), nullableString(variable, "description"), nullableString(variable, "defaultValue"), BrowserJson.bool(variable, "editable", false), BrowserJson.bool(variable, "sensitive", false), BrowserJson.bool(variable, "reinstallRequired", false), nullableString(variable, "rules")));
        });
        return List.copyOf(result);
    }

    private static HostedPlan hostedPlan(JsonObject value) {
        return new HostedPlan(BrowserJson.string(value, "id"), BrowserJson.string(value, "name"), BrowserJson.integer(value, "memoryMb", 0), BrowserJson.integer(value, "diskMb", 0), BrowserJson.integer(value, "cpuPercent", 0), BrowserJson.integer(value, "databases", 0), BrowserJson.integer(value, "backups", 0), BrowserJson.integer(value, "allocations", 0), BrowserJson.longValue(value, "priceCents", 0), BrowserJson.bool(value, "compatible", false), BrowserJson.strings(value, "incompatibilities"));
    }

    private static JsonObject identity(JsonObject value) {
        return value;
    }

    private static <T> T decode(JsonObject value, BrowserJsonDecoder<T> decoder) {
        return decoder.decode(value);
    }

    private static ServerModels.Limits limits(JsonObject value) {
        if (value == null || value.entrySet().isEmpty()) return null;
        ServerModels.Limits result = new ServerModels.Limits();
        result.memory = nullableInteger(value, "memory");
        result.swap = nullableInteger(value, "swap");
        result.disk = nullableInteger(value, "disk");
        result.io = nullableInteger(value, "io");
        result.cpu = nullableInteger(value, "cpu");
        result.threads = BrowserJson.string(value, "threads");
        return result;
    }

    private static ServerView serverView(JsonObject value) {
        ServerView result = new ServerView();
        result.identifier = BrowserJson.string(value, "identifier");
        result.uuid = BrowserJson.string(value, "uuid");
        if (result.identifier.isBlank()) result.identifier = result.uuid;
        result.name = BrowserJson.string(value, "name");
        result.description = BrowserJson.string(value, "description");
        result.ip = BrowserJson.string(value, "ip");
        result.port = BrowserJson.integer(value, "port", 0);
        result.ipAlias = first(value, "ipAlias", "ip_alias");
        result.subdomain = BrowserJson.string(value, "subdomain");
        result.fullDomain = first(value, "fullDomain", "full_domain");
        result.nodeName = first(value, "nodeName", "node_name");
        result.limits = limits(child(value, "limits"));
        result.invocation = BrowserJson.string(value, "invocation");
        result.dockerImage = first(value, "dockerImage", "docker_image");
        result.suspended = firstBoolean(value, false, "suspended", "isSuspended", "is_suspended");
        result.installing = firstBoolean(value, false, "installing", "isInstalling", "is_installing");
        result.linkedModpack = firstBoolean(value, false, "linkedModpack", "linked_modpack");
        result.loader = BrowserJson.string(value, "loader");
        result.version = BrowserJson.string(value, "version");
        result.software = BrowserJson.string(value, "software");
        result.backendType = first(value, "backendType", "backend_type", "backend");
        return result;
    }

    private static SftpTokenView sftpToken(JsonObject value) {
        SftpTokenView result = new SftpTokenView();
        result.token = BrowserJson.string(value, "token");
        result.server = BrowserJson.string(value, "server");
        result.username = BrowserJson.string(value, "username");
        return result;
    }

    private static ServerModels.ServerStats serverStats(JsonObject value) {
        ServerModels.ServerStats result = new ServerModels.ServerStats();
        result.currentState = first(value, "currentState", "current_state");
        result.isSuspended = firstBoolean(value, false, "isSuspended", "is_suspended", "suspended");
        JsonObject source = child(value, "resources");
        if (!source.entrySet().isEmpty()) {
            ServerModels.ServerResources resources = new ServerModels.ServerResources();
            resources.memoryBytes = BrowserJson.longValue(source, "memory_bytes", BrowserJson.longValue(source, "memoryBytes", 0));
            resources.cpuAbsolute = BrowserJson.decimal(source, "cpu_absolute", BrowserJson.decimal(source, "cpuAbsolute", 0));
            resources.diskBytes = BrowserJson.longValue(source, "disk_bytes", BrowserJson.longValue(source, "diskBytes", 0));
            resources.networkRxBytes = BrowserJson.longValue(source, "network_rx_bytes", BrowserJson.longValue(source, "networkRxBytes", 0));
            resources.networkTxBytes = BrowserJson.longValue(source, "network_tx_bytes", BrowserJson.longValue(source, "networkTxBytes", 0));
            resources.uptime = BrowserJson.longValue(source, "uptime", 0);
            resources.limits = limits(child(source, "limits"));
            result.resources = resources;
        }
        return result;
    }

    private static ServerHealthView serverHealth(JsonObject value) {
        ServerHealthView result = new ServerHealthView();
        result.serverId = BrowserJson.string(value, "serverId");
        result.uuid = BrowserJson.string(value, "uuid");
        result.name = BrowserJson.string(value, "name");
        result.status = BrowserJson.string(value, "status");
        result.reachable = BrowserJson.bool(value, "reachable", false);
        result.ready = BrowserJson.bool(value, "ready", false);
        result.online = BrowserJson.bool(value, "online", false);
        result.suspended = BrowserJson.bool(value, "suspended", false);
        result.installing = BrowserJson.bool(value, "installing", false);
        result.clientAvailable = BrowserJson.bool(value, "clientAvailable", false);
        result.resourcesAvailable = BrowserJson.bool(value, "resourcesAvailable", false);
        result.eulaAccepted = nullableBoolean(value, "eulaAccepted", "eula_accepted");
        result.hasServerJar = nullableBoolean(value, "hasServerJar", "has_server_jar");
        result.hasStartScript = nullableBoolean(value, "hasStartScript", "has_start_script");
        result.eulaState = BrowserJson.string(value, "eulaState");
        result.serverJarState = BrowserJson.string(value, "serverJarState");
        result.startScriptState = BrowserJson.string(value, "startScriptState");
        result.healthy = nullableBoolean(value, "healthy");
        result.reason = BrowserJson.string(value, "reason");
        result.checkedAt = BrowserJson.string(value, "checkedAt");
        return result;
    }

    private static PlayerListView playerList(JsonObject value) {
        PlayerListView result = new PlayerListView();
        result.supported = BrowserJson.bool(value, "supported", false);
        result.players = BrowserJson.element(value, "players") == null ? null : BrowserJson.objects(value, "players").stream().map(player -> {
            PlayerView view = new PlayerView();
            view.name = BrowserJson.string(player, "name");
            view.uuid = safeUuid(BrowserJson.string(player, "uuid"));
            view.online = BrowserJson.bool(player, "online", false);
            view.operator = BrowserJson.bool(player, "operator", false);
            view.ping = BrowserJson.integer(player, "ping", -1);
            view.address = BrowserJson.string(player, "address");
            return view;
        }).toList();
        return result;
    }

    private static ServerModels.PteroFileObjectAttributes fileEntry(JsonObject value) {
        ServerModels.PteroFileObjectAttributes result = new ServerModels.PteroFileObjectAttributes();
        result.name = BrowserJson.string(value, "name");
        result.isFile = firstBoolean(value, false, "isFile", "is_file");
        result.size = BrowserJson.longValue(value, "size", 0);
        result.mimetype = BrowserJson.string(value, "mimetype");
        result.sha1 = BrowserJson.string(value, "sha1");
        result.murmur2 = BrowserJson.string(value, "murmur2");
        result.provider = BrowserJson.string(value, "provider");
        result.projectId = first(value, "projectId", "project_id");
        result.versionId = first(value, "versionId", "version_id");
        result.version = BrowserJson.string(value, "version");
        result.title = BrowserJson.string(value, "title");
        result.iconUrl = first(value, "iconUrl", "icon_url");
        result.pageUrl = first(value, "pageUrl", "page_url");
        result.modifiedAt = first(value, "modifiedAt", "modified_at");
        return result;
    }

    private static TrashEntry trashEntry(JsonObject value) {
        return new TrashEntry(BrowserJson.string(value, "id"), BrowserJson.string(value, "path"), BrowserJson.string(value, "version"),
                first(value, "deletedAt", "deleted_at"), BrowserJson.bool(value, "directory", false), BrowserJson.longValue(value, "size", 0));
    }

    private static FileVersion fileVersion(JsonObject value) {
        return new FileVersion(BrowserJson.string(value, "path"), BrowserJson.string(value, "version"), BrowserJson.bool(value, "directory", false),
                BrowserJson.longValue(value, "size", 0));
    }

    private static ServerModels.Backup backup(JsonObject value) {
        ServerModels.Backup result = new ServerModels.Backup();
        result.uuid = BrowserJson.string(value, "uuid");
        result.name = BrowserJson.string(value, "name");
        String ignoredFiles = BrowserJson.element(value, "ignoredFiles") == null ? "ignored_files" : "ignoredFiles";
        result.ignoredFiles = stringsOrNull(value, ignoredFiles);
        result.checksum = BrowserJson.string(value, "checksum");
        result.bytes = nullableLong(value, "bytes");
        result.isSuccessful = firstBoolean(value, false, "isSuccessful", "is_successful", "successful");
        result.isLocked = firstBoolean(value, false, "isLocked", "is_locked", "locked");
        result.createdAt = first(value, "createdAt", "created_at");
        result.completedAt = first(value, "completedAt", "completed_at");
        return result;
    }

    private static ServerModels.Allocation allocation(JsonObject value) {
        ServerModels.Allocation result = new ServerModels.Allocation();
        result.id = nullableInteger(value, "id");
        result.ip = BrowserJson.string(value, "ip");
        result.ipAlias = first(value, "ipAlias", "ip_alias");
        result.port = nullableInteger(value, "port");
        result.notes = BrowserJson.string(value, "notes");
        result.isDefault = firstBoolean(value, false, "isDefault", "is_default");
        return result;
    }

    private static ServerModels.Subuser subuser(JsonObject value) {
        ServerModels.Subuser result = new ServerModels.Subuser();
        result.uuid = BrowserJson.string(value, "uuid");
        result.username = BrowserJson.string(value, "username");
        result.email = BrowserJson.string(value, "email");
        result.image = BrowserJson.string(value, "image");
        result.twoFactorEnabled = firstBoolean(value, false, "twoFactorEnabled", "2fa_enabled", "two_factor_enabled");
        result.permissions = stringsOrNull(value, "permissions");
        result.createdAt = first(value, "createdAt", "created_at");
        JsonObject user = child(value, "restudioUser");
        if (!user.entrySet().isEmpty()) {
            result.restudioUser = new ServerModels.ReStudioUserInfo();
            result.restudioUser.id = BrowserJson.string(user, "id");
            result.restudioUser.username = BrowserJson.string(user, "username");
            result.restudioUser.displayName = BrowserJson.string(user, "displayName");
            result.restudioUser.avatarUrl = BrowserJson.string(user, "avatarUrl");
        }
        return result;
    }

    static ServerModels.SystemPermissions systemPermissions(JsonObject value) {
        ServerModels.SystemPermissions result = new ServerModels.SystemPermissions();
        result.permissions = new LinkedHashMap<>();
        JsonObject permissions = child(value, "permissions");
        for (Map.Entry<String, JsonElement> entry : permissions.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) continue;
            JsonObject categoryValue = entry.getValue().getAsJsonObject();
            ServerModels.PermissionCategory category = new ServerModels.PermissionCategory();
            category.description = BrowserJson.string(categoryValue, "description");
            category.keys = new LinkedHashMap<>();
            JsonObject keys = child(categoryValue, "keys");
            for (Map.Entry<String, JsonElement> key : keys.entrySet()) {
                if (key.getValue() != null && key.getValue().isJsonPrimitive()) category.keys.put(key.getKey(), key.getValue().getAsString());
            }
            result.permissions.put(entry.getKey(), category);
        }
        return result;
    }

    static ServerModels.ReStudioUserInfo userInfo(JsonObject value) {
        ServerModels.ReStudioUserInfo result = new ServerModels.ReStudioUserInfo();
        result.id = BrowserJson.string(value, "id");
        result.username = BrowserJson.string(value, "username");
        result.displayName = BrowserJson.string(value, "displayName");
        result.avatarUrl = BrowserJson.string(value, "avatarUrl");
        return result;
    }

    private static ManagementView management(JsonObject value) {
        ManagementView result = new ManagementView();
        result.serverId = BrowserJson.string(value, "serverId");
        result.name = BrowserJson.string(value, "name");
        result.dockerImage = BrowserJson.string(value, "dockerImage");
        result.startupRevision = BrowserJson.string(value, "startupRevision");
        result.limits = limits(child(value, "limits"));
        JsonObject resources = child(value, "resources");
        result.resources = BrowserJson.element(value, "resources") == null ? null : objectMap(resources);
        result.variables = BrowserJson.element(value, "variables") == null ? null : BrowserJson.objects(value, "variables").stream().map(variable -> {
            ManagementVariableView view = new ManagementVariableView();
            view.key = BrowserJson.string(variable, "key");
            view.value = BrowserJson.string(variable, "value");
            return view;
        }).toList();
        result.dockerImages = BrowserJson.element(value, "dockerImages") == null ? null : stringMap(child(value, "dockerImages"));
        return result;
    }

    private static ServerModels.StartupSettings startupSettings(JsonObject value) {
        return new ServerModels.StartupSettings(BrowserJson.string(value, "revision"), stringMap(child(value, "values")));
    }

    private static HostedReProxyDomainView hostedDomain(JsonObject value) {
        HostedReProxyDomainView result = new HostedReProxyDomainView();
        result.id = BrowserJson.string(value, "id");
        result.user = BrowserJson.string(value, "user");
        result.subdomain = BrowserJson.string(value, "subdomain");
        result.fullDomain = BrowserJson.string(value, "fullDomain");
        result.status = BrowserJson.string(value, "status");
        result.lastUsedAt = BrowserJson.string(value, "lastUsedAt");
        result.createdAt = BrowserJson.string(value, "createdAt");
        result.updatedAt = BrowserJson.string(value, "updatedAt");
        return result;
    }

    private static ServerModels.ReProxyDomain domainModel(HostedReProxyDomainView value) {
        return value == null || blank(value.id) ? null : value.toModel();
    }

    private static ServerModels.ReProxyDomain requireDomainModel(HostedReProxyDomainView value) {
        ServerModels.ReProxyDomain result = domainModel(value);
        if (result == null || blank(result.id)) throw new IllegalStateException("ReProxy Domain Response Is Invalid");
        return result;
    }

    private static ServerModels.ReProxyStartTunnelResponse requireStartResponse(HostedReProxyTunnelView value) {
        if (value == null || blank(value.tunnelId)) throw new IllegalStateException("ReProxy Start Response Is Invalid");
        return value.toStartResponse();
    }

    private static HostedReProxyTunnelView hostedTunnel(JsonObject value) {
        HostedReProxyTunnelView result = new HostedReProxyTunnelView();
        result.serverId = BrowserJson.string(value, "serverId");
        result.tunnelId = BrowserJson.string(value, "tunnelId");
        result.domainId = BrowserJson.string(value, "domainId");
        result.domain = BrowserJson.string(value, "domain");
        result.status = BrowserJson.string(value, "status");
        result.connected = BrowserJson.bool(value, "connected", false);
        result.expiresAt = BrowserJson.string(value, "expiresAt");
        return result;
    }

    private static DuplicateServerResult duplicateResult(JsonObject value) {
        return new DuplicateServerResult(BrowserJson.string(value, "sourceServerId"), BrowserJson.string(value, "targetServerId"),
                BrowserJson.string(value, "subscriptionId"), BrowserJson.string(value, "intentId"), BrowserJson.string(value, "status"),
                BrowserJson.string(value, "phase"), BrowserJson.string(value, "checkoutUrl"), BrowserJson.string(value, "idempotencyKey"));
    }

    private static ConsoleSessionView consoleSession(JsonObject value) {
        ConsoleSessionView result = new ConsoleSessionView();
        result.id = uuid(value, "id");
        result.serverId = BrowserJson.string(value, "serverId");
        result.expiresAt = BrowserJson.longValue(value, "expiresAt", 0);
        result.output = BrowserJson.string(value, "output");
        result.closed = BrowserJson.bool(value, "closed", false);
        return result;
    }

    private static TerminalTicketView terminalTicket(JsonObject value) {
        TerminalTicketView result = new TerminalTicketView();
        result.ticket = BrowserJson.string(value, "ticket");
        result.serverId = BrowserJson.string(value, "serverId");
        result.scope = BrowserJson.string(value, "scope");
        result.expiresAt = BrowserJson.string(value, "expiresAt");
        return result;
    }

    private static CapabilityJob capabilityJob(JsonObject value) {
        CapabilityJob result = new CapabilityJob();
        result.id = nullableString(value, "id");
        result.status = nullableString(value, "status");
        result.error = nullableString(value, "error");
        return result;
    }

    private static HostedModpackJob hostedModpackJob(JsonObject value) {
        return new HostedModpackJob(nullableString(value, "id"), nullableString(value, "serverId"),
                nullableString(value, "operation"), nullableString(value, "status"),
                BrowserJson.integer(value, "progress", 0), objectMap(child(value, "result")),
                nullableString(value, "error"), BrowserJson.longValue(value, "createdAt", 0),
                BrowserJson.longValue(value, "completedAt", 0));
    }

    private static HostedModpackCapabilities hostedModpackCapabilities(JsonObject value) {
        return new HostedModpackCapabilities(BrowserJson.string(value, "serverId"), BrowserJson.string(value, "provider"),
                BrowserJson.bool(value, "active", false), hostedModpackAvailability(child(value, "preflight")),
                hostedModpackAvailability(child(value, "install")), hostedModpackAvailability(child(value, "change")),
                hostedModpackAvailability(child(value, "unlock")));
    }

    private static HostedModpackAvailability hostedModpackAvailability(JsonObject value) {
        return new HostedModpackAvailability(BrowserJson.bool(value, "supported", false), BrowserJson.string(value, "reason"),
                BrowserJson.string(value, "transport"));
    }

    private static ServerCapabilitiesView serverCapabilities(JsonObject value) {
        ServerCapabilitiesView result = new ServerCapabilitiesView();
        result.serverId = BrowserJson.string(value, "serverId");
        result.actions = new LinkedHashMap<>();
        child(value, "actions").entrySet().forEach(entry -> {
            if (entry.getValue() != null && entry.getValue().isJsonObject()) {
                JsonObject source = entry.getValue().getAsJsonObject();
                CapabilityAvailabilityView availability = new CapabilityAvailabilityView();
                availability.supported = BrowserJson.bool(source, "supported", false);
                availability.reason = BrowserJson.string(source, "reason");
                availability.transport = BrowserJson.string(source, "transport");
                result.actions.put(entry.getKey(), availability);
            }
        });
        JsonObject reSync = child(value, "reSync");
        if (!reSync.entrySet().isEmpty()) {
            result.reSync = new ReSyncAvailabilityView();
            result.reSync.supported = BrowserJson.bool(reSync, "supported", false);
            result.reSync.reason = BrowserJson.string(reSync, "reason");
            result.reSync.reasonCode = BrowserJson.string(reSync, "reasonCode");
            result.reSync.transport = BrowserJson.string(reSync, "transport");
            result.reSync.features = stringsOrNull(reSync, "features");
            result.reSync.relayState = BrowserJson.string(reSync, "relayState");
            result.reSync.handshakeState = BrowserJson.string(reSync, "handshakeState");
            result.reSync.protocolCompatibility = BrowserJson.string(reSync, "protocolCompatibility");
            result.reSync.runtimeCompatibility = BrowserJson.string(reSync, "runtimeCompatibility");
            result.reSync.runtimeVersion = BrowserJson.string(reSync, "runtimeVersion");
            result.reSync.endpoint = endpointReadiness(child(reSync, "endpoint"));
        }
        return result;
    }

    private static ReSyncEndpointReadinessView endpointReadiness(JsonObject value) {
        ReSyncEndpointReadinessView result = new ReSyncEndpointReadinessView();
        result.ready = BrowserJson.bool(value, "ready", false);
        result.reasonCode = BrowserJson.string(value, "reasonCode");
        result.reason = BrowserJson.string(value, "reason");
        result.endpointUri = BrowserJson.string(value, "endpointUri");
        result.transport = BrowserJson.string(value, "transport");
        return result;
    }

    private static DeveloperBindingView developerBindingView(JsonObject value) {
        DeveloperBindingView result = new DeveloperBindingView();
        result.id = BrowserJson.string(value, "id");
        result.root = BrowserJson.string(value, "root");
        return result;
    }

    private static DeveloperCapabilitiesView developerCapabilities(JsonObject value) {
        DeveloperCapabilitiesView result = new DeveloperCapabilitiesView();
        result.git = BrowserJson.bool(value, "git", false);
        result.gitOperations = stringsOrNull(value, "gitOperations");
        result.files = BrowserJson.bool(value, "files", false);
        result.fileOperations = stringsOrNull(value, "fileOperations");
        result.transfers = BrowserJson.bool(value, "transfers", false);
        result.search = BrowserJson.bool(value, "search", false);
        result.workflows = BrowserJson.element(value, "workflows") == null ? null
                : BrowserJson.objects(value, "workflows").stream().map(BrowserRemotelyServerApi::developerBindingView).toList();
        result.lsp = BrowserJson.element(value, "lsp") == null ? null
                : BrowserJson.objects(value, "lsp").stream().map(BrowserRemotelyServerApi::developerBindingView).toList();
        return result;
    }

    private static DeveloperDeviceView developerDevice(JsonObject value) {
        DeveloperDeviceView result = new DeveloperDeviceView();
        result.deviceId = uuid(value, "deviceId");
        result.name = BrowserJson.string(value, "name");
        result.version = BrowserJson.string(value, "version");
        JsonElement approvedRoots = BrowserJson.element(value, "approvedRoots");
        result.approvedRoots = approvedRoots == null ? null : approvedRoots.isJsonPrimitive() ? approvedRoots.getAsString() : BrowserJson.write(approvedRoots);
        JsonElement capabilities = BrowserJson.element(value, "capabilities");
        result.capabilities = capabilities == null ? null : capabilities.isJsonPrimitive() ? capabilities.getAsString() : BrowserJson.write(capabilities);
        result.agentVersion = BrowserJson.string(value, "agentVersion");
        result.online = BrowserJson.bool(value, "online", false);
        result.updateRequired = BrowserJson.bool(value, "updateRequired", false);
        result.revoked = BrowserJson.bool(value, "revoked", false);
        JsonObject developer = child(value, "developer");
        result.developer = developer.entrySet().isEmpty() ? null : developerCapabilities(developer);
        return result;
    }

    private static DeveloperRootView developerRoot(JsonObject value) {
        DeveloperRootView result = new DeveloperRootView();
        result.id = BrowserJson.string(value, "id");
        result.path = BrowserJson.string(value, "path");
        result.read = BrowserJson.bool(value, "read", false);
        result.write = BrowserJson.bool(value, "write", false);
        result.execute = BrowserJson.bool(value, "execute", false);
        return result;
    }

    private static DeveloperWorkspaceBindingView developerBinding(JsonObject value) {
        DeveloperWorkspaceBindingView result = new DeveloperWorkspaceBindingView();
        result.workspaceId = BrowserJson.string(value, "workspaceId");
        result.deviceId = uuid(value, "deviceId");
        result.rootId = BrowserJson.string(value, "rootId");
        result.deviceName = BrowserJson.string(value, "deviceName");
        return result;
    }

    private static DeveloperFileEntry developerFileEntry(JsonObject value) {
        DeveloperFileEntry result = new DeveloperFileEntry();
        result.name = BrowserJson.string(value, "name");
        result.path = BrowserJson.string(value, "path");
        result.directory = BrowserJson.bool(value, "directory", false);
        result.size = BrowserJson.longValue(value, "size", 0);
        result.modifiedAt = BrowserJson.longValue(value, "modifiedAt", 0);
        result.version = BrowserJson.string(value, "version");
        return result;
    }

    private static DeveloperTrashEntry developerTrashEntry(JsonObject value) {
        DeveloperTrashEntry result = new DeveloperTrashEntry();
        result.id = BrowserJson.string(value, "id");
        result.originalPath = BrowserJson.string(value, "originalPath");
        result.trashedAt = BrowserJson.longValue(value, "trashedAt", 0);
        result.version = BrowserJson.string(value, "version");
        return result;
    }

    private static DeveloperFileResult developerFileResult(JsonObject value) {
        DeveloperFileResult result = new DeveloperFileResult();
        result.path = BrowserJson.string(value, "path");
        result.content = BrowserJson.string(value, "content");
        result.size = BrowserJson.longValue(value, "size", 0);
        result.version = BrowserJson.string(value, "version");
        result.entries = BrowserJson.objects(value, "entries").stream().map(BrowserRemotelyServerApi::developerFileEntry).toList();
        result.trashId = BrowserJson.string(value, "trashId");
        result.trash = BrowserJson.objects(value, "trash").stream().map(BrowserRemotelyServerApi::developerTrashEntry).toList();
        return result;
    }

    private static DeveloperUploadView developerUpload(JsonObject value) {
        DeveloperUploadView result = new DeveloperUploadView();
        result.uploadId = uuid(value, "uploadId");
        result.path = BrowserJson.string(value, "path");
        result.size = BrowserJson.longValue(value, "size", 0);
        result.offset = BrowserJson.longValue(value, "offset", 0);
        result.chunkSize = BrowserJson.integer(value, "chunkSize", 0);
        return result;
    }

    private static HostedUploadView hostedUpload(JsonObject value) {
        HostedUploadView result = new HostedUploadView();
        result.uploadId = uuid(value, "uploadId");
        result.size = BrowserJson.longValue(value, "size", 0);
        result.offset = BrowserJson.longValue(value, "offset", 0);
        result.chunkSize = BrowserJson.integer(value, "chunkSize", 0);
        result.delivering = BrowserJson.bool(value, "delivering", false);
        result.delivered = BrowserJson.bool(value, "delivered", false);
        result.failed = BrowserJson.bool(value, "failed", false);
        return result;
    }

    private static DeveloperDownloadTicket developerDownload(JsonObject value) {
        DeveloperDownloadTicket result = new DeveloperDownloadTicket();
        result.url = BrowserJson.string(value, "url");
        result.name = BrowserJson.string(value, "name");
        result.size = BrowserJson.longValue(value, "size", 0);
        result.version = BrowserJson.string(value, "version");
        return result;
    }

    private static DeveloperWorkspaceJobCreated developerWorkspaceJobCreated(JsonObject value) {
        DeveloperWorkspaceJobCreated result = new DeveloperWorkspaceJobCreated();
        result.jobId = uuid(value, "jobId");
        return result;
    }

    private static DeveloperWorkspaceJobView developerWorkspaceJob(JsonObject value) {
        DeveloperWorkspaceJobView result = new DeveloperWorkspaceJobView();
        result.status = nullableString(value, "status");
        result.result = BrowserJson.element(value, "result");
        result.error = nullableString(value, "error");
        result.progress = BrowserJson.element(value, "progress");
        return result;
    }

    private static DeveloperGitEntry developerGitEntry(JsonObject value) {
        DeveloperGitEntry result = new DeveloperGitEntry();
        result.path = BrowserJson.string(value, "path");
        result.originalPath = BrowserJson.string(value, "originalPath");
        result.index = BrowserJson.string(value, "index");
        result.worktree = BrowserJson.string(value, "worktree");
        return result;
    }

    private static DeveloperGitStatus developerGitStatus(JsonObject value) {
        DeveloperGitStatus result = new DeveloperGitStatus();
        result.branch = BrowserJson.string(value, "branch");
        result.ahead = BrowserJson.integer(value, "ahead", 0);
        result.behind = BrowserJson.integer(value, "behind", 0);
        result.entries = BrowserJson.objects(value, "entries").stream().map(BrowserRemotelyServerApi::developerGitEntry).toList();
        return result;
    }

    private static DeveloperGitCommit developerGitCommit(JsonObject value) {
        DeveloperGitCommit result = new DeveloperGitCommit();
        result.hash = BrowserJson.string(value, "hash");
        result.parents = BrowserJson.strings(value, "parents");
        result.authorName = BrowserJson.string(value, "authorName");
        result.authorEmail = BrowserJson.string(value, "authorEmail");
        result.authoredAt = BrowserJson.string(value, "authoredAt");
        result.subject = BrowserJson.string(value, "subject");
        return result;
    }

    private static DeveloperGitBranch developerGitBranch(JsonObject value) {
        DeveloperGitBranch result = new DeveloperGitBranch();
        result.name = BrowserJson.string(value, "name");
        result.current = BrowserJson.bool(value, "current", false);
        result.upstream = BrowserJson.string(value, "upstream");
        return result;
    }

    private static DeveloperGitStash developerGitStash(JsonObject value) {
        DeveloperGitStash result = new DeveloperGitStash();
        result.id = BrowserJson.string(value, "id");
        result.index = BrowserJson.integer(value, "index", 0);
        result.ref = BrowserJson.string(value, "ref");
        result.branch = BrowserJson.string(value, "branch");
        result.message = BrowserJson.string(value, "message");
        return result;
    }

    private static DeveloperGitFile developerGitFile(JsonObject value) {
        DeveloperGitFile result = new DeveloperGitFile();
        result.status = BrowserJson.string(value, "status");
        result.path = BrowserJson.string(value, "path");
        result.originalPath = BrowserJson.string(value, "originalPath");
        return result;
    }

    private static DeveloperGitResult developerGit(JsonObject value) {
        DeveloperGitResult result = new DeveloperGitResult();
        result.exitCode = BrowserJson.integer(value, "exitCode", 0);
        JsonObject status = child(value, "status");
        result.status = status.entrySet().isEmpty() ? null : developerGitStatus(status);
        result.diff = BrowserJson.string(value, "diff");
        result.commits = BrowserJson.objects(value, "commits").stream().map(BrowserRemotelyServerApi::developerGitCommit).toList();
        result.branches = BrowserJson.objects(value, "branches").stream().map(BrowserRemotelyServerApi::developerGitBranch).toList();
        result.stashes = BrowserJson.objects(value, "stashes").stream().map(BrowserRemotelyServerApi::developerGitStash).toList();
        result.files = BrowserJson.objects(value, "files").stream().map(BrowserRemotelyServerApi::developerGitFile).toList();
        result.stdout = BrowserJson.string(value, "stdout");
        result.stderr = BrowserJson.string(value, "stderr");
        return result;
    }

    private static DeveloperSearchResult developerSearch(JsonObject value) {
        DeveloperSearchResult result = new DeveloperSearchResult();
        result.matches = BrowserJson.objects(value, "matches").stream().map(match -> {
            DeveloperSearchMatch item = new DeveloperSearchMatch();
            item.path = BrowserJson.string(match, "path");
            item.line = BrowserJson.integer(match, "line", 0);
            item.column = BrowserJson.integer(match, "column", 0);
            item.preview = BrowserJson.string(match, "preview");
            return item;
        }).toList();
        return result;
    }

    private static DeveloperWorkflowResult developerWorkflow(JsonObject value) {
        DeveloperWorkflowResult result = new DeveloperWorkflowResult();
        result.stdout = BrowserJson.string(value, "stdout");
        result.stderr = BrowserJson.string(value, "stderr");
        return result;
    }

    private static DeveloperLspSession developerLspSession(JsonObject value) {
        DeveloperLspSession result = new DeveloperLspSession();
        result.sessionId = BrowserJson.string(value, "sessionId");
        return result;
    }

    private static DeveloperLspMessages developerLspMessages(JsonObject value) {
        DeveloperLspMessages result = new DeveloperLspMessages();
        result.running = BrowserJson.bool(value, "running", false);
        result.messages = BrowserJson.objects(value, "messages").stream().map(message -> {
            DeveloperLspMessage item = new DeveloperLspMessage();
            item.sequence = BrowserJson.longValue(message, "sequence", 0);
            item.message = BrowserJson.element(message, "message");
            return item;
        }).toList();
        return result;
    }

    private static DeveloperJobCreatedView developerJobCreated(JsonObject value) {
        DeveloperJobCreatedView result = new DeveloperJobCreatedView();
        result.commandId = uuid(value, "commandId");
        return result;
    }

    private static DeveloperJobStatusView developerJobStatus(JsonObject value) {
        DeveloperJobStatusView result = new DeveloperJobStatusView();
        result.status = nullableString(value, "status");
        JsonElement jobResult = BrowserJson.element(value, "result");
        result.result = jobResult == null ? null : jobResult.isJsonPrimitive() ? jobResult.getAsString() : BrowserJson.write(jobResult);
        result.error = nullableString(value, "error");
        return result;
    }

    private static ServerModels.ReSyncConfig reSyncConfig(JsonObject value) {
        ServerModels.ReSyncConfig result = new ServerModels.ReSyncConfig();
        result.port = BrowserJson.integer(value, "port", 0);
        result.createdAt = BrowserJson.string(value, "createdAt");
        result.endpointUri = BrowserJson.string(value, "endpointUri");
        result.tlsSpkiFingerprint = BrowserJson.string(value, "tlsSpkiFingerprint");
        return result;
    }

    private static ServerModels.ReSyncProvisionResult reSyncProvision(JsonObject value) {
        ServerModels.ReSyncProvisionResult result = new ServerModels.ReSyncProvisionResult();
        result.success = BrowserJson.bool(value, "success", false);
        result.message = BrowserJson.string(value, "message");
        JsonElement port = BrowserJson.element(value, "port");
        result.port = port == null ? null : BrowserJson.integer(value, "port", 0);
        result.apiKey = BrowserJson.string(value, "apiKey");
        return result;
    }

    private <T> Async<T> get(String endpoint, BrowserJsonDecoder<T> decoder) {
        return request("GET", endpoint, null).thenApply(response -> decode(BrowserJson.object(response), decoder));
    }

    private <T> Async<List<T>> getList(String endpoint, BrowserJsonDecoder<T> decoder) {
        return request("GET", endpoint, null).thenApply(response -> {
            List<T> result = new ArrayList<>();
            BrowserJson.array(response).forEach(element -> {
                if (element != null && element.isJsonObject()) result.add(decode(element.getAsJsonObject(), decoder));
            });
            return List.copyOf(result);
        });
    }

    private Async<Map<String, String>> getStringMap(String endpoint) {
        return request("GET", endpoint, null).thenApply(response -> stringMap(BrowserJson.object(response)));
    }

    private <T> Async<T> getOptional(String endpoint, BrowserJsonDecoder<T> decoder) {
        return getOptionalOnce(endpoint, decoder, true);
    }

    private <T> Async<T> getOptionalOnce(String endpoint, BrowserJsonDecoder<T> decoder, boolean retry) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .header("Accept", "application/json")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .timeout(Duration.ofSeconds(20))
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();
        return transport.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() == 400 || response.statusCode() == 404) return Async.completed(null);
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> getOptionalOnce(endpoint, decoder, false));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    return sessionExpired(new IllegalStateException("Browser Session Expired"));
                }
                return Async.failed(new IllegalStateException("Browser capability request failed with status " + response.statusCode()));
            }
            return Async.completed(decode(BrowserJson.object(response.body()), decoder));
        });
    }

    private <T> Async<T> post(String endpoint, Object body, BrowserJsonDecoder<T> decoder) {
        return request("POST", endpoint, json(body == null ? Map.of() : body)).thenApply(response -> decode(BrowserJson.object(response), decoder));
    }

    private <T> Async<T> put(String endpoint, Object body, BrowserJsonDecoder<T> decoder) {
        return request("PUT", endpoint, json(body == null ? Map.of() : body)).thenApply(response -> decode(BrowserJson.object(response), decoder));
    }

    private Async<DeveloperUploadView> putUploadChunk(DeveloperCapabilityProvider.Workspace.Binding binding, UUID uploadId, long offset, byte[] bytes,
                                                       BiConsumer<Long, Long> progress) {
        return putUploadChunkOnce(binding, uploadId, offset, bytes, progress, true, requestIdempotencyKey("PUT", null));
    }

    private Async<DeveloperUploadView> putUploadChunkOnce(DeveloperCapabilityProvider.Workspace.Binding binding, UUID uploadId, long offset,
                                                           byte[] bytes, BiConsumer<Long, Long> progress, boolean retry, String idempotencyKey) {
        String endpoint = "/developer/workspaces/" + path(binding.id()) + "/uploads/" + path(uploadId.toString()) + "?offset=" + offset;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .header("Idempotency-Key", idempotencyKey)
                .timeout(Duration.ofSeconds(20))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        Async<HttpResponse<String>> upload = transport instanceof BrowserHttpTransport browser
                ? browser.sendAsync(request, HttpResponse.BodyHandlers.ofString(), progress)
                : transport.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        return upload.thenCompose(response -> {
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> putUploadChunkOnce(binding, uploadId, offset, bytes, progress, false, idempotencyKey));
            }
            if (response.statusCode() != 200 && response.statusCode() != 409) {
                if (response.statusCode() == 401) {
                    return sessionExpired(new IllegalStateException("Browser Session Expired"));
                }
                return Async.failed(capabilityFailure(response.statusCode(), response.body()));
            }
            return Async.completed(decode(BrowserJson.object(response.body()), BrowserRemotelyServerApi::developerUpload));
        });
    }

    private static IllegalStateException capabilityFailure(int status) {
        return capabilityFailure(status, null);
    }

    static IllegalStateException capabilityFailure(int status, String body) {
        JsonObject root = parseCapabilityBody(body);
        JsonObject error = capabilityError(root);
        String code = capabilityCode(root, error);
        if (isAllocationLimit(code, root, error, body)) {
            Integer maximum = firstMaximum(root, error);
            String message = maximum == null ? "Maximum Network Ports Reached" : "Maximum Network Ports Reached (" + maximum + ")";
            return new RemotelyCapabilityException(status, ALLOCATION_LIMIT_CODE, message);
        }
        String message = status == 429 ? "Please Wait A Moment Before Trying Again"
                : status == 409 || status == 412 ? "Workspace File Changed. Refresh And Try Again"
                : status == 428 ? "Refresh The Folder Before Changing This File"
                : "Browser Capability Failed With Status " + status;
        return new RemotelyCapabilityException(status, code, message);
    }

    private static JsonObject parseCapabilityBody(String body) {
        if (body == null || body.isBlank()) return new JsonObject();
        try {
            return BrowserJson.object(body);
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static JsonObject capabilityError(JsonObject root) {
        for (String name : List.of("error", "details", "data", "failure")) {
            JsonElement value = BrowserJson.element(root, name);
            if (value != null && value.isJsonObject()) {
                return value.getAsJsonObject();
            }
        }
        return root;
    }

    private static String capabilityCode(JsonObject root, JsonObject error) {
        String code = BrowserJson.string(root, "code", "");
        return code.isBlank() ? BrowserJson.string(error, "code", "") : code;
    }

    private static Integer firstMaximum(JsonObject root, JsonObject error) {
        Integer maximum = positiveMaximum(root);
        return maximum == null ? positiveMaximum(error) : maximum;
    }

    private static Integer positiveMaximum(JsonObject value) {
        int maximum = BrowserJson.integer(value, "maximum", 0);
        return maximum > 0 ? maximum : null;
    }

    private static boolean isAllocationLimit(String code, JsonObject root, JsonObject error, String body) {
        if (ALLOCATION_LIMIT_CODE.equals(code) || LEGACY_ALLOCATION_LIMIT_CODE.equals(code)) {
            return true;
        }
        String message = BrowserJson.string(root, "message", "");
        if (message.isBlank()) message = BrowserJson.string(error, "message", "");
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("maximum network port") || normalized.contains("allocation limit")) {
            return true;
        }
        if (body == null) return false;
        String normalizedBody = body.toLowerCase(Locale.ROOT);
        return normalizedBody.contains(ALLOCATION_LIMIT_CODE) || normalizedBody.contains(LEGACY_ALLOCATION_LIMIT_CODE);
    }

    private Async<Void> delete(String endpoint) {
        return request("DELETE", endpoint, null).thenApply(ignored -> null);
    }

    private Async<String> request(String method, String endpoint, String body) {
        synchronized (browserReadLock) {
            if (closed) return Async.failed(new IllegalStateException("Browser API Is Closed"));
        }
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        Async<String> result = coalescedRead(method, normalizedEndpoint, body,
                () -> requestOnce(method, normalizedEndpoint, body, true,
                        requestIdempotencyKey(method, normalizedEndpoint, body)));
        if (!fileMutation(method, normalizedEndpoint)) return result;
        return result.thenApply(value -> {
            invalidateBrowserReadCache();
            return value;
        });
    }

    private Async<String> requestWithTimeout(String method, String endpoint, String body, Duration timeout) {
        synchronized (browserReadLock) {
            if (closed) return Async.failed(new IllegalStateException("Browser API Is Closed"));
        }
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        return coalescedRead(method, normalizedEndpoint, body,
                () -> requestOnce(method, normalizedEndpoint, body, true,
                        requestIdempotencyKey(method, normalizedEndpoint, body), timeout));
    }

    private Async<String> requestAllowMissing(String method, String endpoint, String body) {
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        return missingView(coalescedRead(method, normalizedEndpoint, body,
                () -> requestOnce(method, normalizedEndpoint, body, true,
                        requestIdempotencyKey(method, normalizedEndpoint, body))), "[]");
    }

    private Async<String> requestAllowMissingContent(String method, String endpoint, String body) {
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        return missingView(coalescedRead(method, normalizedEndpoint, body,
                () -> requestOnce(method, normalizedEndpoint, body, true,
                        requestIdempotencyKey(method, normalizedEndpoint, body))), "");
    }

    private Async<String> coalescedRead(String method, String endpoint, String body, Supplier<Async<String>> requestSupplier) {
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        synchronized (browserReadLock) {
            if (closed) return Async.failed(new IllegalStateException("Browser API Is Closed"));
            if (!coalescibleRead(normalizedMethod, endpoint)) return requestSupplier.get();
            String key = browserReadKey(normalizedMethod, endpoint, body);
            BrowserReadCooldown cooldown = browserReadCooldowns.get(key);
            if (cooldown != null) {
                if (cooldown.until() > System.currentTimeMillis()) return Async.failed(cooldown.failure());
                browserReadCooldowns.remove(key);
            }
            BrowserReadValue cached = browserReadValues.get(key);
            if (cached != null) {
                if (cached.expiresAt() > System.currentTimeMillis()) return Async.completed(cached.value());
                browserReadValues.remove(key);
            }
            BrowserReadOwner existing = browserReadRequests.get(key);
            if (existing != null) return view(existing);
            if (browserReadRequests.size() >= MAX_BROWSER_READ_REQUESTS) {
                return Async.failed(new RemotelyCapabilityException(429, "browser_read_budget",
                        "Browser Read Request Budget Is Busy"));
            }
            Async<String> request;
            try {
                request = Objects.requireNonNull(requestSupplier.get(), "Browser Read Request");
            } catch (Throwable failure) {
                return Async.failed(failure);
            }
            BrowserReadOwner owner = new BrowserReadOwner(key, browserReadEpoch, request);
            request.whenComplete(owner::cleanup);
            browserReadRequests.put(key, owner);
            if (request.isDone()) request.whenComplete(owner::cleanup);
            return view(owner);
        }
    }

    private void cleanupBrowserRead(BrowserReadOwner owner, String ignored, Throwable failure) {
        synchronized (browserReadLock) {
            if (owner.epoch != browserReadEpoch) {
                if (browserReadRequests.get(owner.key) == owner) browserReadRequests.remove(owner.key);
                return;
            }
            Throwable effectiveFailure = owner.request.isCancelled() ? new Async.Cancellation() : failure;
            if (effectiveFailure == null) {
                browserReadCooldowns.remove(owner.key);
                if (ignored != null) {
                    if (browserReadValues.size() >= MAX_BROWSER_READ_VALUES) {
                        browserReadValues.remove(browserReadValues.keySet().iterator().next());
                    }
                    browserReadValues.put(owner.key, new BrowserReadValue(ignored,
                            System.currentTimeMillis() + BROWSER_READ_CACHE.toMillis()));
                }
            } else {
                browserReadValues.remove(owner.key);
                if (rateLimitedRead(effectiveFailure)) {
                    if (browserReadCooldowns.size() >= MAX_BROWSER_READ_COOLDOWNS) {
                        browserReadCooldowns.remove(browserReadCooldowns.keySet().iterator().next());
                    }
                    browserReadCooldowns.put(owner.key, new BrowserReadCooldown(
                            System.currentTimeMillis() + BROWSER_READ_RATE_LIMIT_COOLDOWN.toMillis(), effectiveFailure));
                }
            }
            if (browserReadRequests.get(owner.key) == owner) browserReadRequests.remove(owner.key);
        }
    }

    private static boolean coalescibleRead(String method, String endpoint) {
        if (endpoint == null) return false;
        if ("POST".equalsIgnoreCase(method)) return endpoint.endsWith("/files/hashes");
        if (!"GET".equalsIgnoreCase(method)) return false;
        return endpoint.contains("/files?") || endpoint.contains("/files/content?")
                || endpoint.endsWith("/stats") || endpoint.contains("/resources")
                || endpoint.endsWith("/health") || endpoint.contains("/health?")
                || endpoint.endsWith("/capabilities") || endpoint.contains("/capabilities?");
    }

    private static boolean fileMutation(String method, String endpoint) {
        if (method == null || "GET".equalsIgnoreCase(method) || endpoint == null) return false;
        return (endpoint.endsWith("/files") || endpoint.contains("/files/")) && !endpoint.endsWith("/files/hashes");
    }

    private String browserReadKey(String method, String endpoint, String body) {
        BrowserLaunchSession.Metadata metadata = BrowserLaunchSession.metadata();
        return keyPart(baseUrl) + "|" + keyPart(browserReadEpoch) + "|" + keyPart(metadata.subjectId()) + "|"
                + keyPart(BrowserLaunchSession.ticket()) + "|" + keyPart(method) + "|"
                + keyPart(endpoint) + "|" + keyPart(body);
    }

    private static String normalizeEndpoint(String endpoint) {
        String value = endpoint == null ? "/" : endpoint.strip();
        if (value.isEmpty()) value = "/";
        int queryStart = value.indexOf('?');
        String path = queryStart < 0 ? value : value.substring(0, queryStart);
        String query = queryStart < 0 ? "" : value.substring(queryStart);
        if (!path.startsWith("/")) path = "/" + path;
        while (path.contains("//")) path = path.replace("//", "/");
        if (query.length() > 1) {
            List<String> parameters = new ArrayList<>(List.of(query.substring(1).split("&", -1)));
            parameters.sort(String::compareTo);
            query = "?" + String.join("&", parameters);
        }
        return path + query;
    }

    private static String keyPart(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.length() + ":" + text;
    }

    private static boolean missingStatus(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RemotelyCapabilityException exception && exception.status() == 404) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private static Async<String> missingView(Async<String> shared, String missingBody) {
        Async<String> result = Async.pending();
        result.onCancel(shared::cancel);
        shared.whenComplete((value, failure) -> {
            if (result.isCancelled()) return;
            if (shared.isCancelled()) {
                result.fail(new Async.Cancellation());
                return;
            }
            if (failure == null) result.complete(value);
            else if (missingStatus(failure)) result.complete(missingBody);
            else result.fail(failure);
        });
        return result;
    }

    private static boolean rateLimitedRead(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RemotelyCapabilityException exception && exception.status() == 429) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private Async<String> requestOnce(String method, String endpoint, String body, boolean retry, String idempotencyKey) {
        return requestOnce(method, endpoint, body, retry, idempotencyKey, Duration.ofSeconds(20));
    }

    private Async<String> requestOnce(String method, String endpoint, String body, boolean retry, String idempotencyKey,
                                      Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .header("Accept", acceptsPlainText(endpoint) ? "text/plain;charset=UTF-8" : "application/json")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .timeout(timeout);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return transport.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Async.completed(response.body() == null ? "" : response.body());
            }
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> requestOnce(method, endpoint, body, false, idempotencyKey, timeout));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    return sessionExpired(new IllegalStateException("Browser Session Expired"));
                }
                return Async.failed(capabilityFailure(response.statusCode(), response.body()));
            }
            return Async.completed(response.body() == null ? "" : response.body());
        });
    }

    private static boolean acceptsPlainText(String endpoint) {
        return endpoint != null && endpoint.contains("/files/content?");
    }

    static String requestIdempotencyKey(String method, String body) {
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) return null;
        if (body != null && !body.isBlank()) {
            String supplied = nullableString(BrowserJson.object(body), "idempotencyKey");
            if (supplied != null && !supplied.isBlank()) return supplied;
        }
        return UUID.randomUUID().toString();
    }

    static String requestIdempotencyKey(String method, String endpoint, String body) {
        if (endpoint != null && endpoint.startsWith("/server-schedules/")) {
            if (body == null || body.isBlank()) return null;
            String supplied = nullableString(BrowserJson.object(body), "idempotencyKey");
            return supplied == null || supplied.isBlank() ? null : supplied;
        }
        return requestIdempotencyKey(method, body);
    }

    private Async<String> apiRequest(String method, String endpoint, String body) {
        return apiRequestOnce(method, endpoint, body, true);
    }

    private Async<String> demoRequest(String method, String endpoint, String body) {
        return demoRequestOnce(method, endpoint, body, true);
    }

    private Async<String> demoRequestOnce(String method, String endpoint, String body, boolean retry) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BrowserLaunchSession.apiBaseUrl() + endpoint))
                .header("Accept", "application/json")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .timeout(Duration.ofSeconds(20));
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return transport.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) return Async.completed(response.body() == null ? "" : response.body());
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> demoRequestOnce(method, endpoint, body, false));
            }
            if (response.statusCode() == 401) return sessionExpired(new IllegalStateException("Browser Session Expired"));
            return Async.failed(capabilityFailure(response.statusCode(), response.body()));
        });
    }

    private Async<String> apiRequestOnce(String method, String endpoint, String body, boolean retry) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BrowserLaunchSession.apiBaseUrl() + endpoint))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return transport.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Async.completed(response.body() == null ? "" : response.body());
            }
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> apiRequestOnce(method, endpoint, body, false));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    return sessionExpired(new IllegalStateException("Browser Session Expired"));
                }
                return Async.failed(capabilityFailure(response.statusCode(), response.body()));
            }
            return Async.completed(response.body() == null ? "" : response.body());
        });
    }

    private Async<String> reStudioRequest(String method, String serverId, String operation) {
        return reStudioRequestOnce(method, serverId, operation, true, requestIdempotencyKey(method, null));
    }

    private Async<String> reStudioRequestOnce(String method, String serverId, String operation, boolean retry, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BrowserLaunchSession.capabilityBaseUrl() + "/servers/" + path(serverId) + "/resync/" + operation))
                .header("Accept", "application/json")
                .header("X-Remotely-Web-Ticket", BrowserLaunchSession.ticket())
                .timeout(Duration.ofSeconds(20));
        if ("POST".equals(method)) builder.header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, "POST".equals(method) ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody());
        return transport.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Async.completed(response.body() == null ? "" : response.body());
            }
            if (response.statusCode() == 401 && retry && BrowserLaunchSession.authenticated()) {
                return renewAndRetry(() -> reStudioRequestOnce(method, serverId, operation, false, idempotencyKey));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    return sessionExpired(new IllegalStateException("Browser Session Expired"));
                }
                return Async.failed(capabilityFailure(response.statusCode(), response.body()));
            }
            return Async.completed(response.body() == null ? "" : response.body());
        });
    }

    private <T> Async<T> renewAndRetry(Supplier<Async<T>> retry) {
        String ticket = BrowserLaunchSession.ticket();
        return BrowserLaunchSession.renewAsync()
                .exceptionallyCompose(failure -> {
                    if (renewalWasSuperseded(failure) || BrowserLaunchSession.authenticated()
                            && !Objects.equals(ticket, BrowserLaunchSession.ticket())) {
                        return Async.failed(failure);
                    }
                    return BrowserLaunchSession.isAuthenticationFailure(failure) ? sessionExpired(failure) : Async.failed(failure);
                })
                .thenCompose(ignored -> retry.get());
    }

    private <T> Async<T> sessionExpired(Throwable failure) {
        return Async.failed(expiredFailure(failure));
    }

    private IllegalStateException expiredFailure(Throwable failure) {
        BrowserLaunchSession.expireSession();
        if (host != null) host.signIn(host.getCurrentScreen());
        return new IllegalStateException("Browser Session Expired", failure);
    }

    private static boolean renewalWasSuperseded(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("superseded") || normalized.contains("cancelled") || normalized.contains("canceled")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isNotFound(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("status 404")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String path(String value) {
        return query(value).replace("+", "%20");
    }

    private static String serverId(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.identifier != null && !server.identifier.isBlank()) return server.identifier;
        return server.uuid == null ? "" : server.uuid;
    }

    private static String reProxyPath(String serverId) {
        return "/servers/" + path(serverId) + "/reproxy";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String query(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    record ManagerSnapshot(List<ServerModels.ClientServerView> instances,
                           List<ServerScreenHost.NetworkView> networks,
                           Map<String, ServerModels.ServerStatus> statuses) {
        ManagerSnapshot {
            instances = instances == null ? List.of() : List.copyOf(instances);
            networks = networks == null ? List.of() : List.copyOf(networks);
            statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
        }
    }

    static final class BrowserTerminalTransport implements TerminalTransport {
        private final Deque<byte[]> chunks = new ArrayDeque<>();
        private final Deque<String> pendingOutput = new ArrayDeque<>();
        private final Function<BinaryWebSocketListener, Async<BinaryWebSocket>> connector;
        private final Runnable removeAction;
        private BinaryWebSocket socket;
        private volatile Consumer<String> outputListener;
        private volatile Consumer<String> disconnectListener;
        private volatile boolean closed;
        private volatile String closeReason = "Disconnected";
        private Async<BinaryWebSocket> pendingConnection;
        private long connectionGeneration;
        private boolean connecting;

        BrowserTerminalTransport(Function<BinaryWebSocketListener, Async<BinaryWebSocket>> connector, Runnable removeAction) {
            this.connector = Objects.requireNonNull(connector, "connector");
            this.removeAction = Objects.requireNonNull(removeAction, "removeAction");
        }

        void connect() {
            long generation;
            synchronized (this) {
                if (closed || connecting) return;
                connecting = true;
                generation = ++connectionGeneration;
            }
            Async<BinaryWebSocket> result;
            try {
                result = connector.apply(listener(generation));
            } catch (RuntimeException exception) {
                connectionFailed(generation, exception);
                return;
            }
            if (result == null) {
                connectionFailed(generation, new IllegalStateException("Terminal Connection Is Unavailable"));
                return;
            }
            boolean cancelResult;
            synchronized (this) {
                cancelResult = closed || generation != connectionGeneration;
                if (!cancelResult) pendingConnection = result;
            }
            if (cancelResult) {
                result.whenComplete((value, failure) -> closeSocket(value));
                result.cancel();
                return;
            }
            result.whenComplete((value, failure) -> {
                synchronized (this) {
                    if (pendingConnection == result) pendingConnection = null;
                }
                if (failure != null) connectionFailed(generation, failure);
                else if (value == null) connectionFailed(generation, new IllegalStateException("Terminal Connection Is Unavailable"));
                else attach(generation, value);
            });
        }

        private BinaryWebSocketListener listener(long generation) {
            return new BinaryWebSocketListener() {
                @Override
                public void onOpen(BinaryWebSocket socket) {
                    attach(generation, socket);
                }

                @Override
                public void onText(String text) {
                    if (!isCurrent(generation) || text == null) return;
                    String output = consoleOutput(text);
                    if (output != null && !output.isEmpty()) appendOutput(output);
                }

                @Override
                public void onBinary(byte[] bytes) {
                    if (bytes != null && bytes.length > 0) onText(new String(bytes, StandardCharsets.UTF_8));
                }

                @Override
                public void onClose(int statusCode, String reason) {
                    connectionLost(generation, reason == null || reason.isBlank() ? "Disconnected" : reason);
                }

                @Override
                public void onError(Throwable error) {
                    connectionLost(generation, error == null ? "Terminal Connection Failed" : failureMessage(error));
                }
            };
        }

        private void connectionFailed(long generation, Throwable failure) {
            connectionLost(generation, failureMessage(failure));
        }

        private void attach(long generation, BinaryWebSocket value) {
            boolean closeValue;
            boolean invalidConnection;
            synchronized (this) {
                closeValue = closed || generation != connectionGeneration || value == null || !value.isOpen();
                invalidConnection = !closed && generation == connectionGeneration && (value == null || !value.isOpen());
                if (!closeValue) {
                    socket = value;
                    connecting = false;
                }
            }
            if (closeValue) {
                if (value != null && value.isOpen()) value.close(1000, "Closed");
                if (invalidConnection) connectionFailed(generation, new IllegalStateException("Terminal Connection Is Unavailable"));
                return;
            }
        }

        @Override
        public synchronized int read(char[] buffer, int offset, int length) throws IOException {
            if (buffer == null || offset < 0 || length < 0 || offset + length > buffer.length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            byte[] bytes = chunks.pollFirst();
            if (bytes == null) return closed ? -1 : 0;
            String text = new String(bytes, StandardCharsets.UTF_8);
            int count = Math.min(length, text.length());
            text.getChars(0, count, buffer, offset);
            if (count < text.length()) chunks.addFirst(text.substring(count).getBytes(StandardCharsets.UTF_8));
            return count;
        }

        @Override
        public synchronized void write(byte[] bytes) throws IOException {
            if (closed) throw new IOException("Terminal Is Closed");
        }

        @Override
        public boolean isConnected() {
            BinaryWebSocket value = socket;
            return !closed && value != null && value.isOpen();
        }

        @Override
        public TerminalCapability capability() {
            return TerminalCapability.outputOnly();
        }

        @Override
        public boolean pushBased() {
            return true;
        }

        @Override
        public synchronized void start(Consumer<String> output, Consumer<String> disconnect) {
            outputListener = output;
            disconnectListener = disconnect;
            connect();
        }

        @Override
        public void resize(TerminalSize size) {
        }

        @Override
        public int waitFor() throws InterruptedException {
            return 0;
        }

        @Override
        public synchronized boolean ready() {
            return !chunks.isEmpty();
        }

        @Override
        public synchronized void onOutput(Consumer<String> listener) {
            outputListener = listener;
            if (listener != null) {
                while (!pendingOutput.isEmpty()) listener.accept(pendingOutput.removeFirst());
            }
        }

        @Override
        public synchronized void onDisconnect(Consumer<String> listener) {
            disconnectListener = listener;
            if (listener != null && closed) listener.accept(closeReason);
        }

        @Override
        public String name() {
            return "Browser Terminal";
        }

        @Override
        public void close() {
            terminate("Closed");
        }

        private void connectionLost(long generation, String reason) {
            BinaryWebSocket previous;
            Async<BinaryWebSocket> pending;
            synchronized (this) {
                if (closed || generation != connectionGeneration) return;
                previous = socket;
                socket = null;
                pending = pendingConnection;
                pendingConnection = null;
                connecting = false;
                connectionGeneration++;
            }
            if (pending != null) pending.cancel();
            if (previous != null && previous.isOpen()) previous.close(1000, "Disconnected");
            terminate(reason);
        }

        private void terminate(String reason) {
            BinaryWebSocket value;
            Async<BinaryWebSocket> pending;
            Consumer<String> listener;
            synchronized (this) {
                if (closed) return;
                closed = true;
                closeReason = reason == null || reason.isBlank() ? "Disconnected" : reason;
                value = socket;
                socket = null;
                pending = pendingConnection;
                pendingConnection = null;
                connectionGeneration++;
                connecting = false;
                chunks.clear();
                pendingOutput.clear();
                listener = disconnectListener;
            }
            if (pending != null) pending.cancel();
            closeSocket(value);
            removeAction.run();
            if (listener != null) listener.accept(closeReason);
        }

        private synchronized boolean isCurrent(long generation) {
            return !closed && connectionGeneration == generation;
        }

        private static void closeSocket(BinaryWebSocket value) {
            if (value != null && value.isOpen()) value.close(1000, "Closed");
        }

        private synchronized void appendOutput(String output) {
            if (output == null || output.isEmpty() || closed) return;
            Consumer<String> listener = outputListener;
            if (listener != null) {
                listener.accept(output);
            } else {
                pendingOutput.addLast(output);
                chunks.addLast(output.getBytes(StandardCharsets.UTF_8));
            }
        }

        private static String failureMessage(Throwable failure) {
            Throwable current = failure;
            while (current != null && current.getCause() != null) current = current.getCause();
            String message = current == null ? null : current.getMessage();
            return message == null || message.isBlank() ? "Terminal Connection Failed" : message;
        }

        private String consoleOutput(String text) {
            try {
                JsonElement element = BrowserJson.parse(text);
                if (element != null && element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    String event = object.has("event") ? object.get("event").getAsString() : "";
                    if (object.has("args") && object.get("args").isJsonArray() && !object.getAsJsonArray("args").isEmpty()) {
                        String value = object.getAsJsonArray("args").get(0).getAsString();
                        if ("console output".equalsIgnoreCase(event)) return normalizeConsoleOutput(value);
                        if ("terminal error".equalsIgnoreCase(event)) return "Terminal Error: " + value + "\n";
                    }
                    return "";
                }
            } catch (RuntimeException ignored) {
            }
            return text;
        }

        static String normalizeConsoleOutput(String output) {
            if (output == null || output.isEmpty()) return "";
            String normalized = output.replace("\r\n", "\n").replace("\n", "\r\n");
            return normalized.endsWith("\n") ? normalized : normalized + "\r\n";
        }
    }

    private static final class AsyncChain {
        private Async<Void> value = Async.completed(null);
    }

    static final class HostedDownloadLifecycle {
        private final Async<Void> result;
        private final TransferSink sink;
        private final BiConsumer<Long, Long> progress;
        private final BooleanSupplier cancelled;
        private final long maxBytes;
        private Async<Void> writes = Async.completed(null);
        private Async<HttpResponse<Void>> request;
        private long received;
        private boolean terminated;
        private boolean aborting;

        HostedDownloadLifecycle(Async<Void> result, TransferSink sink, BiConsumer<Long, Long> progress,
                                BooleanSupplier cancelled, long maxBytes) {
            this.result = Objects.requireNonNull(result, "result");
            this.sink = Objects.requireNonNull(sink, "sink");
            this.progress = progress;
            this.cancelled = cancelled;
            this.maxBytes = maxBytes;
            result.onCancel(this::cancel);
        }

        void attach(Async<HttpResponse<Void>> request) {
            synchronized (this) {
                this.request = request;
                if (!terminated) return;
            }
            if (request != null) request.cancel();
        }

        void accept(byte[] value) throws Exception {
            Async<Void> next;
            synchronized (this) {
                if (terminated) throw new Async.Cancellation();
                if (isCancelled()) {
                    cancel();
                    throw new Async.Cancellation();
                }
                if (writes.isDone()) {
                    Throwable failure = writes.failure();
                    if (failure != null) {
                        fail(failure);
                        throw asException(failure);
                    }
                }
                byte[] bytes = value == null ? new byte[0] : value.clone();
                if (bytes.length > maxBytes - received) {
                    IllegalArgumentException failure = new IllegalArgumentException("File Download Exceeds Limit");
                    fail(failure);
                    throw failure;
                }
                received += bytes.length;
                long transferred = received;
                next = writes.thenCompose(ignored -> {
                    synchronized (this) {
                        if (aborting) return Async.failed(new Async.Cancellation());
                    }
                    return sink.write(new TransferSource.Chunk(bytes, false)).thenRun(() -> {
                        if (progress != null && !result.isDone()) progress.accept(transferred, -1L);
                    });
                });
                writes = next;
            }
            next.whenComplete((ignored, failure) -> {
                if (failure != null) fail(failure);
            });
            if (next.isDone() && next.failure() != null) throw asException(next.failure());
        }

        void complete(HttpResponse<Void> response, Throwable failure) {
            if (failure != null) {
                fail(failure);
                return;
            }
            if (response == null) {
                fail(new IllegalStateException("Browser File Download Returned No Response"));
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                fail(new IllegalStateException("Browser File Download Failed With Status " + response.statusCode()));
                return;
            }
            finishSuccess();
        }

        void fail(Throwable failure) {
            Throwable cause = failure == null ? new IllegalStateException("Browser File Download Failed") : failure;
            Async<HttpResponse<Void>> activeRequest;
            Async<Void> queued;
            synchronized (this) {
                if (terminated) return;
                terminated = true;
                aborting = true;
                activeRequest = request;
                queued = writes;
            }
            if (activeRequest != null) activeRequest.cancel();
            settle(queued, cause, false);
        }

        private void cancel() {
            Async<HttpResponse<Void>> activeRequest;
            Async<Void> queued;
            synchronized (this) {
                if (terminated) return;
                terminated = true;
                aborting = true;
                activeRequest = request;
                queued = writes;
            }
            if (activeRequest != null) activeRequest.cancel();
            settle(queued, new Async.Cancellation(), true);
        }

        private void finishSuccess() {
            Async<Void> queued;
            synchronized (this) {
                if (terminated) return;
                terminated = true;
                queued = writes;
            }
            queued.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    terminateSink(queued, failure, false);
                    return;
                }
                Async<Void> closed;
                try {
                    closed = sink.close();
                } catch (Throwable closeFailure) {
                    if (!result.isDone()) result.fail(closeFailure);
                    return;
                }
                closed.whenComplete((ignoredClose, closeFailure) -> {
                    if (result.isDone()) return;
                    if (closeFailure == null) result.complete(null);
                    else result.fail(closeFailure);
                });
            });
        }

        private void settle(Async<Void> queued, Throwable failure, boolean cancelledResult) {
            queued.whenComplete((ignored, queuedFailure) -> {
                Throwable cause = failure == null ? queuedFailure : failure;
                terminateSink(queued, cause, cancelledResult);
            });
        }

        private void terminateSink(Async<Void> ignored, Throwable failure, boolean cancelledResult) {
            Async<Void> terminatedSink;
            try {
                terminatedSink = BrowserTransferBridge.abort(sink);
            } catch (Throwable abortFailure) {
                terminatedSink = Async.failed(abortFailure);
            }
            terminatedSink.whenComplete((ignoredSink, sinkFailure) -> {
                if (cancelledResult || result.isDone()) return;
                if (failure != null) result.fail(failure);
                else if (sinkFailure != null) result.fail(sinkFailure);
                else result.complete(null);
            });
        }

        private boolean isCancelled() {
            try {
                return cancelled != null && cancelled.getAsBoolean();
            } catch (Throwable failure) {
                fail(failure);
                return true;
            }
        }

        private static Exception asException(Throwable failure) {
            if (failure instanceof Exception exception) return exception;
            if (failure instanceof Error error) throw error;
            return new IllegalStateException(failure);
        }
    }

    private static final class LongValue {
        private long value;
    }

    public record HostedCheckoutRequest(String serverName, String plan, Integer eggId,
                                        Map<String, String> environment, Map<String, String> fileConfigs,
                                        Map<String, Object> pendingModpackInstall, String javaDockerImage,
                                        String software, String version, String build, String subdomain,
                                        Integer customMemoryMb) {
    }

    public record HostedStartupVariable(String key, String name, String description, String defaultValue,
                                        boolean editable, boolean sensitive, boolean reinstallRequired, String rules) {
    }

    public record HostedEgg(Integer eggId, String name, String description, Map<String, String> runtimeImages,
                            List<HostedStartupVariable> startup, boolean reinstallRequired) {
    }

    public record HostedPlan(String id, String name, int memoryMb, int diskMb, int cpuPercent, int databases,
                             int backups, int allocations, long priceCents, boolean compatible,
                             List<String> incompatibilities) {
    }

    public record HostedCatalog(Integer defaultEggId, List<HostedEgg> eggs, List<HostedPlan> plans) {
    }

    public record HostedServerCatalog(String serverId, Integer eggId, String currentRuntimeImage, Map<String, String> runtimeImages,
                                      List<HostedStartupVariable> startup, boolean reinstallRequired,
                                      String reinstallReason) {
    }

    public record HostedModpackRequest(String provider, String projectId, String versionId, String fileId,
                                       String versionNumber, String downloadUrl, String minecraftVersion,
                                       String software, String modpackResolutionId, String javaDockerImage,
                                       String idempotencyKey) {
    }

    public record HostedModpackAvailability(boolean supported, String reason, String transport) {
    }

    public record HostedModpackCapabilities(String serverId, String provider, boolean active,
                                            HostedModpackAvailability preflight, HostedModpackAvailability install,
                                            HostedModpackAvailability change, HostedModpackAvailability unlock) {
    }

    public record HostedModpackJob(String id, String serverId, String operation, String status, int progress,
                                   Map<String, Object> result, String error, long createdAt, long completedAt) {
    }

    public record ConsoleSession(UUID id, String serverId, long expiresAt, String output, boolean closed) {
    }

    public record TerminalTicket(String ticket, String serverId, String scope, String expiresAt) {
    }

    public record DeveloperDevice(UUID id, String name, String approvedRoots, String capabilities, String agentVersion,
                                  boolean online, boolean updateRequired, boolean revoked) {
    }

    private static final class ServerView {
        private String identifier;
        private String uuid;
        private String name;
        private String description;
        private String ip;
        private int port;
        private String ipAlias;
        private String subdomain;
        private String fullDomain;
        private String nodeName;
        private ServerModels.Limits limits;
        private String invocation;
        private String dockerImage;
        private boolean suspended;
        private boolean installing;
        private boolean linkedModpack;
        private String loader;
        private String version;
        private String software;
        private String backendType;

        private ServerModels.ClientServerView toModel() {
            ServerModels.ClientServerView model = new ServerModels.ClientServerView();
            model.identifier = identifier;
            model.uuid = uuid;
            model.name = name;
            model.description = description;
            model.ip = ip;
            model.port = port;
            model.ipAlias = ipAlias;
            model.subdomain = subdomain;
            model.fullDomain = fullDomain;
            model.nodeName = nodeName;
            model.limits = limits;
            model.invocation = invocation;
            model.dockerImage = dockerImage;
            model.isSuspended = suspended;
            model.isInstalling = installing;
            model.linkedModpack = linkedModpack;
            model.loader = loader;
            model.version = version;
            model.software = software;
            model.backendType = backendType;
            return model;
        }
    }

    public record ServerManagement(String serverId, String name, String dockerImage, ServerModels.Limits limits,
                                   Map<String, Object> resources, Map<String, String> variables,
                                   Map<String, String> dockerImages) {
        public ServerManagement {
            serverId = serverId == null ? "" : serverId;
            name = name == null ? "" : name;
            dockerImage = dockerImage == null ? "" : dockerImage;
            resources = resources == null ? Map.of() : Map.copyOf(resources);
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            dockerImages = dockerImages == null ? Map.of() : Map.copyOf(dockerImages);
        }
    }

    private static final class SftpTokenView {
        private String token;
        private String server;
        private String username;
    }

    private static final class ManagementView {
        private String serverId;
        private String name;
        private String dockerImage;
        private String startupRevision;
        private ServerModels.Limits limits;
        private Map<String, Object> resources;
        private List<ManagementVariableView> variables;
        private Map<String, String> dockerImages;

        private ServerModels.StartupSettings startup() {
            Map<String, String> result = new LinkedHashMap<>();
            if (variables != null) {
                for (ManagementVariableView variable : variables) {
                    if (variable != null && variable.key != null && !variable.key.isBlank()) result.put(variable.key, variable.value == null ? "" : variable.value);
                }
            }
            return new ServerModels.StartupSettings(startupRevision, result);
        }

        private ServerManagement management() {
            Map<String, String> values = new LinkedHashMap<>();
            values.putAll(startup().values());
            return new ServerManagement(serverId, name, dockerImage, limits, resources, values, dockerImages);
        }
    }

    private static final class ManagementVariableView {
        private String key;
        private String value;
    }

    private static final class HostedReProxyDomainView {
        private String id;
        private String user;
        private String subdomain;
        private String fullDomain;
        private String status;
        private String lastUsedAt;
        private String createdAt;
        private String updatedAt;

        private ServerModels.ReProxyDomain toModel() {
            ServerModels.ReProxyDomain domain = new ServerModels.ReProxyDomain();
            domain.id = id;
            domain.subdomain = subdomain;
            domain.fullDomain = fullDomain;
            domain.status = status;
            domain.lastUsedAt = lastUsedAt;
            domain.createdAt = createdAt;
            domain.updatedAt = updatedAt;
            return domain;
        }
    }

    private static final class HostedReProxyTunnelView {
        private String serverId;
        private String tunnelId;
        private String domainId;
        private String domain;
        private String status;
        private boolean connected;
        private String expiresAt;

        private ServerModels.ReProxyTunnel toModel(List<ServerModels.ReProxyDomain> domains) {
            if (tunnelId == null || tunnelId.isBlank()) return null;
            ServerModels.ReProxyTunnel tunnel = new ServerModels.ReProxyTunnel();
            tunnel.id = tunnelId;
            tunnel.status = status;
            tunnel.expiresAt = expiresAt;
            tunnel.domain = domains == null ? null : domains.stream().filter(value -> value != null && domainId != null && domainId.equals(value.id)).findFirst().orElse(null);
            if (tunnel.domain == null && domain != null && !domain.isBlank()) {
                tunnel.domain = new ServerModels.ReProxyDomain();
                tunnel.domain.id = domainId;
                tunnel.domain.fullDomain = domain;
                tunnel.domain.status = connected ? "active" : status;
            }
            return tunnel;
        }

        private ServerModels.ReProxyStartTunnelResponse toStartResponse() {
            ServerModels.ReProxyStartTunnelResponse response = new ServerModels.ReProxyStartTunnelResponse();
            response.tunnelId = tunnelId;
            response.domain = domain;
            return response;
        }
    }

    private record BrowserReadCooldown(long until, Throwable failure) {
    }

    private record BrowserReadValue(String value, long expiresAt) {
    }

    private final class BrowserReadOwner {
        private final String key;
        private final long epoch;
        private final Async<String> request;
        private int subscribers;
        private boolean ownerCancelled;

        private BrowserReadOwner(String key, long epoch, Async<String> request) {
            this.key = key;
            this.epoch = epoch;
            this.request = request;
        }

        private Async<String> view() {
            return new BrowserReadSubscriber().view();
        }

        private void cleanup(String value, Throwable failure) {
            cleanupBrowserRead(this, value, failure);
        }

        private void cancelOwner() {
            synchronized (browserReadLock) {
                ownerCancelled = true;
            }
            request.cancel();
        }

        private void release(BrowserReadSubscriber subscriber) {
            boolean cancelRequest = false;
            synchronized (browserReadLock) {
                if (subscriber.released) return;
                subscriber.released = true;
                if (subscriber.counted) {
                    subscriber.counted = false;
                    subscribers--;
                }
                if (!ownerCancelled && subscribers == 0 && !request.isDone()) {
                    ownerCancelled = true;
                    if (browserReadRequests.get(key) == this) browserReadRequests.remove(key);
                    cancelRequest = true;
                }
            }
            if (cancelRequest) request.cancel();
        }

        private final class BrowserReadSubscriber {
            private Async<String> result;
            private boolean counted;
            private boolean released;

            private Async<String> view() {
                result = Async.pending();
                result.onCancel(this::cancel);
                synchronized (browserReadLock) {
                    counted = !ownerCancelled && !request.isDone();
                    if (counted) subscribers++;
                }
                request.whenComplete(this::complete);
                return result;
            }

            private void cancel() {
                release(this);
                result = null;
            }

            private void complete(String value, Throwable failure) {
                Async<String> current;
                synchronized (browserReadLock) {
                    if (released) return;
                    released = true;
                    if (counted) {
                        counted = false;
                        subscribers--;
                    }
                    current = result;
                    result = null;
                }
                if (current == null || current.isCancelled()) return;
                if (request.isCancelled()) {
                    current.fail(new Async.Cancellation());
                } else if (failure == null) {
                    current.complete(value);
                } else {
                    current.fail(failure);
                }
            }
        }
    }

    private record ReProxyDomainRequest(String subdomain) {
    }

    private static final class ServerCapabilitiesView {
        private String serverId;
        private Map<String, CapabilityAvailabilityView> actions;
        private ReSyncAvailabilityView reSync;

        private ServerCapabilities toModel() {
            Map<String, CapabilityAvailability> mapped = new LinkedHashMap<>();
            if (actions != null) {
                actions.forEach((action, availability) -> {
                    if (action != null && availability != null) mapped.put(action, availability.toModel());
                });
            }
            return new ServerCapabilities(serverId, mapped, reSync == null ? null : reSync.toModel());
        }
    }

    private static final class CapabilityAvailabilityView {
        private boolean supported;
        private String reason;
        private String transport;

        private CapabilityAvailability toModel() {
            return new CapabilityAvailability(supported, reason, transport);
        }
    }

    private static final class ReSyncAvailabilityView {
        private boolean supported;
        private String reason;
        private String reasonCode;
        private String transport;
        private List<String> features;
        private String relayState;
        private String handshakeState;
        private String protocolCompatibility;
        private String runtimeCompatibility;
        private String runtimeVersion;
        private ReSyncEndpointReadinessView endpoint;

        private ReSyncAvailability toModel() {
            return new ReSyncAvailability(supported, reason, transport, features,
                decodeRelayState(relayState),
                decodeHandshakeState(handshakeState),
                decodeCompatibility(protocolCompatibility),
                decodeCompatibility(runtimeCompatibility), runtimeVersion,
                decodeReadinessReason(reasonCode),
                endpoint == null ? null : endpoint.toModel());
        }
    }

    private static final class ReSyncEndpointReadinessView {
        private boolean ready;
        private String reasonCode;
        private String reason;
        private String endpointUri;
        private String transport;

        private ReSyncEndpointReadiness toModel() {
            return new ReSyncEndpointReadiness(ready,
                decodeReadinessReason(reasonCode),
                reason, endpointUri, transport);
        }
    }

    private static ReSyncRelayState decodeRelayState(String value) {
        return switch (enumToken(value)) {
            case "UNAVAILABLE" -> ReSyncRelayState.UNAVAILABLE;
            case "READY" -> ReSyncRelayState.READY;
            case "UNKNOWN" -> ReSyncRelayState.UNKNOWN;
            default -> ReSyncRelayState.UNKNOWN;
        };
    }

    private static ReSyncHandshakeState decodeHandshakeState(String value) {
        return switch (enumToken(value)) {
            case "CONNECTING" -> ReSyncHandshakeState.CONNECTING;
            case "CONNECTED" -> ReSyncHandshakeState.CONNECTED;
            case "UNREACHABLE" -> ReSyncHandshakeState.UNREACHABLE;
            case "REJECTED" -> ReSyncHandshakeState.REJECTED;
            case "UNKNOWN" -> ReSyncHandshakeState.UNKNOWN;
            default -> ReSyncHandshakeState.UNKNOWN;
        };
    }

    private static ReSyncCompatibility decodeCompatibility(String value) {
        return switch (enumToken(value)) {
            case "COMPATIBLE" -> ReSyncCompatibility.COMPATIBLE;
            case "MISMATCH" -> ReSyncCompatibility.MISMATCH;
            case "UNKNOWN" -> ReSyncCompatibility.UNKNOWN;
            default -> ReSyncCompatibility.UNKNOWN;
        };
    }

    private static RemotelyServerApi.ReSyncReadinessReason decodeReadinessReason(String value) {
        return switch (enumToken(value)) {
            case "NONE" -> RemotelyServerApi.ReSyncReadinessReason.NONE;
            case "NOT_PROVISIONED" -> RemotelyServerApi.ReSyncReadinessReason.NOT_PROVISIONED;
            case "PROVISIONING_INCOMPLETE" -> RemotelyServerApi.ReSyncReadinessReason.PROVISIONING_INCOMPLETE;
            case "CREDENTIAL_UNAVAILABLE" -> RemotelyServerApi.ReSyncReadinessReason.CREDENTIAL_UNAVAILABLE;
            case "INVALID_ENDPOINT" -> RemotelyServerApi.ReSyncReadinessReason.INVALID_ENDPOINT;
            case "MISSING_TLS_PIN" -> RemotelyServerApi.ReSyncReadinessReason.MISSING_TLS_PIN;
            case "PLAINTEXT_PUBLIC_ENDPOINT" -> RemotelyServerApi.ReSyncReadinessReason.PLAINTEXT_PUBLIC_ENDPOINT;
            case "RUNTIME_METADATA_STALE" -> RemotelyServerApi.ReSyncReadinessReason.RUNTIME_METADATA_STALE;
            case "RUNTIME_VERSION_UNAVAILABLE" -> RemotelyServerApi.ReSyncReadinessReason.RUNTIME_VERSION_UNAVAILABLE;
            case "UPSTREAM_UNREACHABLE" -> RemotelyServerApi.ReSyncReadinessReason.UPSTREAM_UNREACHABLE;
            case "PROTOCOL_INCOMPATIBLE" -> RemotelyServerApi.ReSyncReadinessReason.PROTOCOL_INCOMPATIBLE;
            case "UNKNOWN" -> RemotelyServerApi.ReSyncReadinessReason.UNKNOWN;
            default -> RemotelyServerApi.ReSyncReadinessReason.UNKNOWN;
        };
    }

    private static String enumToken(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static final class CapabilityJob {
        private String id;
        private String status;
        private String error;
    }

    public static final class ServerHealthView {
        private String serverId;
        private String uuid;
        private String name;
        private String status;
        private boolean reachable;
        private boolean ready;
        private boolean online;
        private boolean suspended;
        private boolean installing;
        private boolean clientAvailable;
        private boolean resourcesAvailable;
        private Boolean eulaAccepted;
        private Boolean hasServerJar;
        private Boolean hasStartScript;
        private String eulaState;
        private String serverJarState;
        private String startScriptState;
        private Boolean healthy;
        private String reason;
        private String checkedAt;

        public String serverId() {
            return serverId;
        }

        public String uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public String status() {
            return status;
        }

        public boolean reachable() {
            return reachable;
        }

        public boolean ready() {
            return ready;
        }

        public boolean online() {
            return online;
        }

        public boolean suspended() {
            return suspended;
        }

        public boolean installing() {
            return installing;
        }

        public boolean clientAvailable() {
            return clientAvailable;
        }

        public boolean resourcesAvailable() {
            return resourcesAvailable;
        }

        public Boolean eulaAccepted() { return eulaAccepted; }
        public Boolean hasServerJar() { return hasServerJar; }
        public Boolean hasStartScript() { return hasStartScript; }
        public String eulaState() { return eulaState; }
        public String serverJarState() { return serverJarState; }
        public String startScriptState() { return startScriptState; }
        public Boolean healthy() { return healthy; }

        public String reason() {
            return reason;
        }

        public String checkedAt() {
            return checkedAt;
        }
    }

    private static final class DeveloperDeviceView {
        private UUID deviceId;
        private String name;
        private String version;
        private String approvedRoots;
        private String capabilities;
        private String agentVersion;
        private boolean online;
        private boolean updateRequired;
        private boolean revoked;
        private DeveloperCapabilitiesView developer;

        private DeveloperDevice toModel() {
            String resolvedVersion = agentVersion == null ? version : agentVersion;
            return new DeveloperDevice(deviceId, name == null ? "" : name, approvedRoots == null ? "[]" : approvedRoots,
                    capabilities == null ? "[]" : capabilities, resolvedVersion == null ? "" : resolvedVersion, online, updateRequired, revoked);
        }

        private DeveloperCapabilityProvider.Workspace.Device toWorkspaceDevice() {
            Map<String, CapabilityDescriptor> capabilities = new LinkedHashMap<>();
            capabilities.put(CapabilityIds.GIT, developer != null && developer.git ? CapabilityDescriptor.supported(CapabilityIds.GIT)
                    : CapabilityDescriptor.unavailable(CapabilityIds.GIT, "Git Is Unavailable"));
            capabilities.put(CapabilityIds.FILES, developer != null && developer.files ? CapabilityDescriptor.supported(CapabilityIds.FILES)
                    : CapabilityDescriptor.unavailable(CapabilityIds.FILES, "Workspace Files Are Unavailable"));
            capabilities.put(CapabilityIds.TRASH, developer != null && developer.fileOperations != null
                    && developer.fileOperations.containsAll(List.of("trash", "trash-list", "restore", "purge")) ? CapabilityDescriptor.supported(CapabilityIds.TRASH)
                    : CapabilityDescriptor.unavailable(CapabilityIds.TRASH, "Workspace Trash Is Unavailable"));
            capabilities.put(CapabilityIds.TRANSFER, developer != null && developer.transfers ? CapabilityDescriptor.supported(CapabilityIds.TRANSFER)
                    : CapabilityDescriptor.unavailable(CapabilityIds.TRANSFER, "Workspace Transfers Are Unavailable"));
            capabilities.put(CapabilityIds.SEARCH, developer != null && developer.search ? CapabilityDescriptor.supported(CapabilityIds.SEARCH)
                    : CapabilityDescriptor.unavailable(CapabilityIds.SEARCH, "Search Is Unavailable"));
            capabilities.put(CapabilityIds.WORKFLOW, developer != null && developer.workflows != null && !developer.workflows.isEmpty()
                    ? CapabilityDescriptor.supported(CapabilityIds.WORKFLOW) : CapabilityDescriptor.unavailable(CapabilityIds.WORKFLOW, "Workflows Are Unavailable"));
            capabilities.put(CapabilityIds.LSP, developer != null && developer.lsp != null && !developer.lsp.isEmpty()
                    ? CapabilityDescriptor.supported(CapabilityIds.LSP) : CapabilityDescriptor.unavailable(CapabilityIds.LSP, "Language Services Are Unavailable"));
            return new DeveloperCapabilityProvider.Workspace.Device(deviceId.toString(), name, online, capabilities);
        }
    }

    private static final class DeveloperCapabilitiesView {
        private boolean git;
        private List<String> gitOperations;
        private boolean files;
        private List<String> fileOperations;
        private boolean transfers;
        private boolean search;
        private List<DeveloperBindingView> workflows;
        private List<DeveloperBindingView> lsp;
    }

    private static final class DeveloperBindingView {
        private String id;
        private String root;
    }

    private static final class DeveloperRootView {
        private String id;
        private String path;
        private boolean read;
        private boolean write;
        private boolean execute;

        private DeveloperCapabilityProvider.Workspace.Root toModel() {
            String value = path == null || path.isBlank() ? "." : path;
            return new DeveloperCapabilityProvider.Workspace.Root(id, RemotePath.of(value), value, read, write, execute);
        }
    }

    private static final class DeveloperWorkspaceBindingView {
        private String workspaceId;
        private UUID deviceId;
        private String rootId;
        private String deviceName;
    }

    private static final class DeveloperFileResult {
        private String path;
        private String content;
        private long size;
        private String version;
        private List<DeveloperFileEntry> entries;
        private String trashId;
        private List<DeveloperTrashEntry> trash;
    }

    private static final class DeveloperTrashEntry {
        private String id;
        private String originalPath;
        private long trashedAt;
        private String version;
    }

    private static final class DeveloperFileEntry {
        private String name;
        private String path;
        private boolean directory;
        private long size;
        private long modifiedAt;
        private String version;
    }

    private static final class DeveloperUploadView {
        private UUID uploadId;
        private String path;
        private long size;
        private long offset;
        private int chunkSize;
    }

    static final class HostedUploadView {
        UUID uploadId;
        long size;
        long offset;
        int chunkSize;
        boolean delivering;
        boolean delivered;
        boolean failed;
    }

    private static final class DeveloperDownloadTicket {
        private String url;
        private String name;
        private long size;
        private String version;
    }

    private static final class DeveloperWorkspaceJobCreated {
        private UUID jobId;
    }

    private static final class DeveloperWorkspaceJobView {
        private String status;
        private JsonElement result;
        private String error;
        private JsonElement progress;
    }

    private static final class DeveloperGitResult {
        private int exitCode;
        private DeveloperGitStatus status;
        private String diff;
        private List<DeveloperGitCommit> commits;
        private List<DeveloperGitBranch> branches;
        private List<DeveloperGitStash> stashes;
        private List<DeveloperGitFile> files;
        private String stdout;
        private String stderr;
    }

    private static final class DeveloperGitStatus {
        private String branch;
        private int ahead;
        private int behind;
        private List<DeveloperGitEntry> entries;
    }

    private static final class DeveloperGitEntry {
        private String path;
        private String originalPath;
        private String index;
        private String worktree;
    }

    private static final class DeveloperGitCommit {
        private String hash;
        private List<String> parents;
        private String authorName;
        private String authorEmail;
        private String authoredAt;
        private String subject;
    }

    private static final class DeveloperGitBranch {
        private String name;
        private boolean current;
        private String upstream;
    }

    private static final class DeveloperGitStash {
        private String id;
        private int index;
        private String ref;
        private String branch;
        private String message;
    }

    private static final class DeveloperGitFile {
        private String status;
        private String path;
        private String originalPath;
    }

    private static final class DeveloperSearchResult {
        private List<DeveloperSearchMatch> matches;
    }

    private static final class DeveloperSearchMatch {
        private String path;
        private int line;
        private int column;
        private String preview;
    }

    private static final class DeveloperWorkflowResult {
        private String stdout;
        private String stderr;
    }

    private static final class DeveloperLspSession {
        private String sessionId;
    }

    private static final class DeveloperLspMessages {
        private List<DeveloperLspMessage> messages;
        private boolean running;
    }

    private static final class DeveloperLspMessage {
        private long sequence;
        private JsonElement message;
    }

    private static final class DeveloperJobCreatedView {
        private UUID commandId;
    }

    private static final class DeveloperJobStatusView {
        private String status;
        private String result;
        private String error;
    }

    private static final class PlayerListView {
        private boolean supported;
        private List<PlayerView> players;
    }

    private static final class PlayerView {
        private String name;
        private UUID uuid;
        private boolean online;
        private boolean operator;
        private int ping;
        private String address;
    }

    private static final class ConsoleSessionView {
        private UUID id;
        private String serverId;
        private long expiresAt;
        private String output;
        private boolean closed;

        private ConsoleSession toModel() {
            return new ConsoleSession(id, serverId, expiresAt, output == null ? "" : output, closed);
        }
    }

    private static final class TerminalTicketView {
        private String ticket;
        private String serverId;
        private String scope;
        private String expiresAt;

        private TerminalTicket toModel() {
            return new TerminalTicket(ticket, serverId, scope, expiresAt);
        }
    }
}
