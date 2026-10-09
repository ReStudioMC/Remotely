package redxax.oxy.remotely.ui.widgets;
import java.time.Duration;
import java.util.Deque;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskSchedulers;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.network.DesktopNetworkManager;
import redxax.oxy.remotely.network.DesktopNetworkAccess;
import redxax.oxy.remotely.ui.server.ServerIconManager;
import redxax.oxy.remotely.ui.server.DesktopServerIconProvider;
import redxax.oxy.remotely.ui.widgets.management.PlayerManagerController;
import restudio.rebase.Rebase;
import restudio.rebase.account.Account;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.backend.feature.ResourceUsageFeature;
import restudio.rebase.hosting.RemoteHost;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.ExpandableWindowTitleWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.WindowTitleBarRenderer;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.ResourceManager;
import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkPlayerLifecycle;
import restudio.resync.network.NetworkPlayerLifecycleType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;

import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class ServerPulseTitleExtension extends ExpandableWindowTitleWidget {
    private record ServerSnapshot(Instance instance, long uptimeMs, double cpuPercent, long memoryBytes, long memoryLimitBytes, List<UnifiedPlayer> players) {
    }

    private record PlayerEvent(UUID playerId, String name, boolean joined, String scope, boolean authoritative, long createdAt) {
    }

    private record ScopedPlayer(String scope, UUID playerId) {
    }

    private record PendingPlayerLeave(UnifiedPlayer player, long createdAt) {
    }

    private record RecentNetworkLifecycle(boolean joined, long occurredAt) {
    }

    private record PlayerSubscription(PlayerManagerController controller, Consumer<PlayerManagerController.PlayerSnapshot> listener) {
    }

    private static final long REFRESH_INTERVAL_MS = 5_000L;
    private static final long DISCOVERY_INTERVAL_MS = 30_000L;
    private static final long PLAYER_STABILITY_MS = 750L;
    private static final long PLAYER_INITIALIZATION_MS = 5_000L;
    private static final long NETWORK_TRANSFER_GRACE_MS = 5_000L;
    private static final long AUTHORITATIVE_EVENT_WINDOW_MS = 30_000L;
    private static final long EVENT_DURATION_MS = 4_200L;
    private static final float EVENT_EXIT_START = 0.78f;
    private static final int BASE_HEIGHT = 8;
    private static final int EVENT_HEAD_SIZE = 8;
    private static final int EVENT_HEIGHT = 12;
    private static final int EVENT_HORIZONTAL_PADDING = 5;
    private static final long FACE_RETRY_MS = 30_000L;
    private static final Identifier MISSING_FACE = Identifier.icon("steve.png");
    private final BrowserSafeState.BooleanValue refreshing = new BrowserSafeState.BooleanValue();
    private final ServerIconManager iconManager = new ServerIconManager(new DesktopServerIconProvider(DesktopRemotelyPaths.appDir()));
    private final Map<String, Instance> reactorInstances = BrowserSafeState.map();
    private final Map<String, Map<UUID, UnifiedPlayer>> knownPlayers = new HashMap<>();
    private final Map<String, Map<UUID, UnifiedPlayer>> observedPlayers = new HashMap<>();
    private final Map<String, Long> observedPlayersSince = new HashMap<>();
    private final Map<String, Long> playerSubscriptionStartedAt = new HashMap<>();
    private final Map<UUID, Identifier> playerFaces = BrowserSafeState.map();
    private final Map<UUID, Long> playerFaceRetryAt = BrowserSafeState.map();
    private final Set<UUID> requestedPlayerFaces = BrowserSafeState.set();
    private final Set<String> initializedPlayers = new HashSet<>();
    private final Map<String, PlayerSubscription> subscriptions = new HashMap<>();
    private final Map<ScopedPlayer, PendingPlayerLeave> pendingPlayerLeaves = new LinkedHashMap<>();
    private final Map<ScopedPlayer, RecentNetworkLifecycle> recentNetworkLifecycles = new HashMap<>();
    private final Deque<PlayerEvent> playerEvents = BrowserSafeState.deque();
    private final NetworkPlayerNotificationSource networkPlayerNotifications = new NetworkPlayerNotificationSource();
    private final Consumer<NetworkEvent> networkEventListener = this::applyNetworkEvent;
    private final Map<String, MountableButtonWidget> rows = new LinkedHashMap<>();
    private final MountableButtonWidget emptyRow = new MountableButtonWidget.Builder("No Running Servers").description("Statuses update automatically").build();
    private volatile List<ServerSnapshot> snapshots = List.of();
    private volatile long nextRefreshAt;
    private volatile long nextDiscoveryAt;
    private DesktopNetworkManager networkManager;
    private boolean expandedContentVisible;
    private float expandedContentWidth;
    private float expandedContentHeight;
    private float expandedContentOpacity = 1f;

    public ServerPulseTitleExtension() {
        this(null);
    }

    public ServerPulseTitleExtension(DesktopNetworkManager networkManager) {
        super(WindowTitleBarRenderer.BUTTON_WIDTH, WindowTitleBarRenderer.BUTTON_HEIGHT);
        this.networkManager = networkManager;
        if (networkManager != null) networkManager.addRuntimeEventListener(networkEventListener);
        emptyRow.entranceAnimationEnabled = false;
        selectable = true;
        setSelected(true);
    }

    @Override
    protected int getExpandedWidth() {
        int contentWidth = snapshots.stream().limit(8).mapToInt(server -> Math.max(TextRenderer.getWidth(server.instance().getName()),
                TextRenderer.getWidth(serverDescription(server))) + 20).max().orElse(TextRenderer.getWidth("Statuses update automatically") + 20);
        return Math.clamp(contentWidth, 280, 560);
    }

    @Override
    protected int getExpandedHeight() {
        return 10 + Math.clamp(snapshots.size(), 1, 8) * 31;
    }

    @Override
    protected int getRestingWidth(long now) {
        return currentEvents(now).stream().mapToInt(event -> {
            int fullWidth = eventWidth(event);
            return getCollapsedWidth() + Math.round((fullWidth - getCollapsedWidth()) * eventVisibility(event, now));
        }).max().orElse(getCollapsedWidth());
    }

    @Override
    protected int getRestingHeight(long now) {
        List<PlayerEvent> events = currentEvents(now);
        if (events.isEmpty()) return BASE_HEIGHT;
        return Math.max(BASE_HEIGHT, Math.round(events.stream().map(event -> EVENT_HEIGHT * eventVisibility(event, now)).reduce(0f, Float::sum)));
    }

    @Override
    public void tick() {
        bindNetworkEvents();
        PlayerEvent event = currentEvents(System.currentTimeMillis()).stream().findFirst().orElse(null);
        setAccent(ThemeManager.getAccent(event == null ? "default" : event.joined() ? "nice" : "danger"));
        setSelected(true);
        super.tick();
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (isExpanded()) {
            for (MountableButtonWidget row : rows.values()) {
                if (row.isVisible() && row.isMouseOver(event.x(), event.y()) && row.mouseClicked(event.retarget(row, event.x(), event.y()))) {
                    return event.finish(true);
                }
            }
        }
        return super.mouseClicked(event);
    }

    @Override
    protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        settlePlayerSnapshots(now);
        requestRefresh(now);
        if (isExpanded()) {
            expandedContentVisible = true;
            expandedContentWidth = renderWidth();
            expandedContentHeight = renderHeight();
            expandedContentOpacity = 1f;
        } else if (getWidth() == getRestingWidth(now) && getHeight() == getRestingHeight(now)) {
            expandedContentVisible = false;
        }
        if (expandedContentVisible) {
            float distance = Math.max(Math.abs(expandedContentWidth - targetWidth), Math.abs(expandedContentHeight - targetHeight));
            float remaining = Math.max(Math.abs(renderWidth() - targetWidth), Math.abs(renderHeight() - targetHeight));
            float opacity = isExpanded() ? 1f : distance == 0f ? 0f : Math.clamp((remaining / distance - 0.25f) / 0.75f, 0f, 1f);
            expandedContentOpacity = Math.min(expandedContentOpacity, opacity);
            if (expandedContentOpacity > 0f) drawExpandedContent(context, mouseX, mouseY, expandedContentOpacity);
            return;
        }
        List<PlayerEvent> events = currentEvents(now);
        for (int index = 0; index < events.size(); index++) {
            drawPlayerEvent(context, events.get(index), now, index, events.size());
        }
    }

    private void drawPlayerEvent(IDrawContext context, PlayerEvent event, long now, int index, int count) {
        float progress = Math.clamp((now - event.createdAt()) / (float) EVENT_DURATION_MS, 0f, 1f);
        float entrance = Math.clamp(progress / 0.16f, 0f, 1f);
        float exit = Math.clamp((progress - EVENT_EXIT_START) / (1f - EVENT_EXIT_START), 0f, 1f);
        float easedEntrance = 1f - (1f - entrance) * (1f - entrance) * (1f - entrance);
        float easedExit = exit * exit * exit;
        int availableHeight = getHeight() - 2;
        int innerHeight = Math.max(1, availableHeight / count);
        int innerTop = getY() + 1 + innerHeight * index;
        if (index == count - 1) innerHeight = Math.max(1, availableHeight - innerHeight * index);
        int textY = innerTop + Math.round((innerHeight - ITextRenderer.fontHeight) / 2f);
        int headY = innerTop + (innerHeight - EVENT_HEAD_SIZE) / 2;
        int contentWidth = eventContentWidth(event);
        float contentOffsetX = -(1f - easedEntrance) * contentWidth + easedExit * contentWidth;
        context.pushScissorState();
        context.enableScissor(getX() + 1, innerTop, getX() + getWidth() - 1, innerTop + innerHeight);
        context.getMatrices().push();
        context.getMatrices().translate(contentOffsetX, 0, 0);
        int contentX = getX() + getWidth() - EVENT_HORIZONTAL_PADDING - contentWidth;
        Identifier face = playerFace(event);
        context.drawPixelArt(face, contentX, headY, EVENT_HEAD_SIZE, EVENT_HEAD_SIZE);
        contentX += EVENT_HEAD_SIZE + 4;
        String sign = event.joined() ? "+" : "-";
        context.drawText(sign, contentX, textY, ThemeManager.getAccent(event.joined() ? "nice" : "danger").getAccentColor(), false);
        contentX += TextRenderer.getWidth(sign) + 3;
        context.drawText(eventMessage(event), contentX, textY, ThemeManager.getColor(ThemeColor.text), false);
        context.getMatrices().pop();
        context.disableScissor();
        context.popScissorState();
    }

    private void requestRefresh(long now) {
        if (now < nextRefreshAt || !refreshing.compareAndSet(false, true)) return;
        nextRefreshAt = now + REFRESH_INTERVAL_MS;
        discoverInstances(now).thenCompose(this::collectSnapshots).whenComplete((result, throwable) -> {
            if (throwable == null && result != null) ScreenManager.getInstance().execute(() -> applySnapshots(result));
            refreshing.set(false);
        });
    }

    private Async<List<Instance>> discoverInstances(long now) {
        InstanceManager manager = Rebase.get().getInstanceManager();
        List<Async<Void>> discoveries = new ArrayList<>();
        if (now >= nextDiscoveryAt) {
            nextDiscoveryAt = now + DISCOVERY_INTERVAL_MS;
            for (RemoteHost host : manager.getRemoteHosts()) {
                manager.fetchRemoteInstances(host);
            }
            discoveries.add(AsyncTools.withTimeout(refreshReactorInstances(), TaskSchedulers.current(), Duration.ofSeconds(8)).exceptionally(ignored -> null));
        }
        return Async.allOf(discoveries.toArray(Async[]::new)).thenApply(ignored -> {
            Map<String, Instance> instances = new LinkedHashMap<>();
            for (Instance instance : manager.getLocalInstances()) addDiscoveredInstance(instances, instance);
            for (RemoteHost host : manager.getRemoteHosts()) {
                for (Instance instance : manager.getRemoteInstances(host)) addDiscoveredInstance(instances, instance);
            }
            for (Instance instance : reactorInstances.values()) addDiscoveredInstance(instances, instance);
            return instances.values().stream().filter(this::shouldProbe).toList();
        });
    }

    private Async<Void> refreshReactorInstances() {
        if (!ReStudio.getInstance().isAuthenticated()) {
            reactorInstances.clear();
            return Async.completed(null);
        }
        return JvmAsyncBridge.fromFuture(ReStudio.getInstance().getApi().getServers().thenAccept(servers -> {
            Set<String> identifiers = new HashSet<>();
            if (servers != null) {
                for (ServerModels.ClientServerView server : servers) {
                    if (server == null || server.identifier == null || server.identifier.isBlank()) continue;
                    identifiers.add(server.identifier);
                    Instance instance = reactorInstances.computeIfAbsent(server.identifier, ignored -> createReactorInstance(server));
                    if (server.name != null && !server.name.isBlank()) instance.setName(server.name);
                    applyServerType(instance, server.loader);
                    if (server.isInstalling) instance.setState(InstanceState.INSTALLING);
                    if (server.isSuspended) instance.setState(InstanceState.STOPPED);
                }
            }
            reactorInstances.keySet().retainAll(identifiers);
        }));
    }

    private Instance createReactorInstance(ServerModels.ClientServerView server) {
        Map<String, String> credentials = new HashMap<>();
        credentials.put("identifier", server.identifier);
        credentials.put("host", server.sftpIp == null ? "" : server.sftpIp);
        credentials.put("port", String.valueOf(server.sftpPort));
        credentials.put("user", server.sftpUser == null ? "" : server.sftpUser);
        credentials.put("password", "");
        Instance instance = new Instance(server.name == null || server.name.isBlank() ? server.identifier : server.name, "unknown", "");
        instance.setBackendConfig(new BackendConfig("RESTUDIO", credentials));
        instance.setServer(true);
        applyServerType(instance, server.loader);
        instance.setState(server.isInstalling ? InstanceState.INSTALLING : InstanceState.STOPPED);
        return instance;
    }

    private void applyServerType(Instance instance, String loader) {
        if (loader == null || loader.isBlank()) return;
        instance.setServerSoftwareType(loader);
        try {
            instance.setModLoader(ModLoader.valueOf(loader.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void addDiscoveredInstance(Map<String, Instance> instances, Instance instance) {
        if (instance == null) return;
        BackendConfig config = instance.getBackendConfig();
        String identifier = config != null && config.credentials != null ? config.credentials.get("identifier") : null;
        String type = config == null || config.type == null ? "INSTANCE" : config.type.toUpperCase(Locale.ROOT);
        String key = identifier == null || identifier.isBlank() ? instance.getInstanceId() : type + ":" + identifier;
        instances.putIfAbsent(key, instance);
    }

    private boolean shouldProbe(Instance instance) {
        if (isLifecycleActive(instance.getState())) return true;
        BackendConfig config = instance.getBackendConfig();
        return config != null && config.type != null && !"LOCAL".equalsIgnoreCase(config.type);
    }

    private Async<List<ServerSnapshot>> collectSnapshots(List<Instance> instances) {
        List<Async<ServerSnapshot>> futures = instances.stream().map(this::collectSnapshot).toList();
        return Async.allOf(futures.toArray(Async[]::new))
                .thenApply(ignored -> futures.stream().map(Async::join).filter(this::isActiveSnapshot).toList());
    }

    private boolean isActiveSnapshot(ServerSnapshot snapshot) {
        if (isLifecycleActive(snapshot.instance().getState())) return true;
        boolean hasUsage = snapshot.uptimeMs() > 0 || snapshot.cpuPercent() > 0 || snapshot.memoryBytes() > 0;
        if (hasUsage) snapshot.instance().setState(InstanceState.RUNNING);
        return hasUsage;
    }

    private boolean isLifecycleActive(InstanceState state) {
        return state == InstanceState.RUNNING || state == InstanceState.STARTING || state == InstanceState.STOPPING;
    }

    private Async<ServerSnapshot> collectSnapshot(Instance instance) {
        ResourceUsageFeature.ResourceUsage empty = emptyUsage();
        return AsyncTools.withTimeout(resourceUsage(instance), TaskSchedulers.current(), Duration.ofSeconds(4)).exceptionally(ignored -> empty).thenApply(usage -> {
            List<UnifiedPlayer> players = snapshots.stream().filter(snapshot -> snapshot.instance().getInstanceId().equals(instance.getInstanceId()))
                    .findFirst().map(ServerSnapshot::players).orElseGet(List::of);
            return new ServerSnapshot(instance, usage.uptimeMs(), usage.cpuPercent(), usage.memoryBytes(), usage.memoryLimitBytes(), players);
        });
    }

    private Async<ResourceUsageFeature.ResourceUsage> resourceUsage(Instance instance) {
        ServerBackend backend = instance.getBackend();
        if (backend == null) return Async.completed(emptyUsage());
        return backend.getFeature(ResourceUsageFeature.class).map(ResourceUsageFeature::getResourcesAsync).orElseGet(() -> Async.completed(emptyUsage()));
    }

    private ResourceUsageFeature.ResourceUsage emptyUsage() {
        return new ResourceUsageFeature.ResourceUsage(0, 0, 0, 0, 0, 0, 0);
    }

    private void applySnapshots(List<ServerSnapshot> updated) {
        reconcileSubscriptions(updated.stream().map(ServerSnapshot::instance).toList());
        List<ServerSnapshot> merged = new ArrayList<>();
        for (ServerSnapshot snapshot : updated) {
            Map<UUID, UnifiedPlayer> players = knownPlayers.get(snapshot.instance().getInstanceId());
            merged.add(new ServerSnapshot(snapshot.instance(), snapshot.uptimeMs(), snapshot.cpuPercent(), snapshot.memoryBytes(), snapshot.memoryLimitBytes(),
                    players == null ? snapshot.players() : List.copyOf(players.values())));
        }
        snapshots = List.copyOf(merged);
    }

    private void reconcileSubscriptions(List<Instance> running) {
        Set<String> runningIds = running.stream().map(Instance::getInstanceId).collect(Collectors.toSet());
        List<String> removed = subscriptions.keySet().stream().filter(id -> !runningIds.contains(id)).toList();
        for (String id : removed) {
            PlayerSubscription subscription = subscriptions.remove(id);
            subscription.controller().removePlayerListener(subscription.listener());
            knownPlayers.remove(id);
            observedPlayers.remove(id);
            observedPlayersSince.remove(id);
            playerSubscriptionStartedAt.remove(id);
            initializedPlayers.remove(id);
        }
        for (Instance instance : running) {
            PlayerSubscription subscription = subscriptions.computeIfAbsent(instance.getInstanceId(), ignored -> {
                PlayerManagerController controller = PlayerManagerController.getOrCreate(instance);
                Consumer<PlayerManagerController.PlayerSnapshot> listener = snapshot -> applyPlayers(instance, snapshot);
                playerSubscriptionStartedAt.put(instance.getInstanceId(), System.currentTimeMillis());
                controller.addPlayerListener(listener);
                return new PlayerSubscription(controller, listener);
            });
            subscription.controller().refreshPlayerSnapshots();
        }
    }

    private void applyPlayers(Instance instance, PlayerManagerController.PlayerSnapshot snapshot) {
        String id = instance.getInstanceId();
        Map<UUID, UnifiedPlayer> current = new LinkedHashMap<>();
        for (UnifiedPlayer player : snapshot.players()) {
            if (player != null && player.isOnline() && player.getUuid() != null) {
                current.put(player.getUuid(), player);
            }
        }
        if (snapshot.baseline()) {
            observedPlayers.put(id, current);
            observedPlayersSince.put(id, System.currentTimeMillis());
            knownPlayers.put(id, current);
            initializedPlayers.add(id);
            updateSnapshotPlayers(id, current);
            return;
        }
        Map<UUID, UnifiedPlayer> observed = observedPlayers.get(id);
        if (observed == null || !observed.keySet().equals(current.keySet())) observedPlayersSince.put(id, System.currentTimeMillis());
        observedPlayers.put(id, current);
    }

    private void settlePlayerSnapshots(long now) {
        recentNetworkLifecycles.entrySet().removeIf(entry -> now - entry.getValue().occurredAt() > AUTHORITATIVE_EVENT_WINDOW_MS);
        Set<String> settledScopes = new HashSet<>();
        for (Map.Entry<String, Map<UUID, UnifiedPlayer>> entry : new ArrayList<>(observedPlayers.entrySet())) {
            String id = entry.getKey();
            Map<UUID, UnifiedPlayer> current = entry.getValue();
            long observedSince = observedPlayersSince.getOrDefault(id, now);
            if (now - observedSince < PLAYER_STABILITY_MS) continue;
            if (!initializedPlayers.contains(id)) {
                long subscribedAt = playerSubscriptionStartedAt.getOrDefault(id, now);
                if (current.isEmpty() && now - subscribedAt < PLAYER_INITIALIZATION_MS) continue;
                initializedPlayers.add(id);
                knownPlayers.put(id, current);
                updateSnapshotPlayers(id, current);
                continue;
            }
            String scope = playerScope(id);
            if (!settledScopes.add(scope)) continue;
            Set<String> scopeIds = playerScopeIds(scope);
            long scopeObservedSince = scopeIds.stream().mapToLong(scopeId -> observedPlayersSince.getOrDefault(scopeId, now)).max().orElse(now);
            if (now - scopeObservedSince < PLAYER_STABILITY_MS) continue;
            Map<UUID, UnifiedPlayer> previous = scopedPlayers(scopeIds, knownPlayers);
            Map<UUID, UnifiedPlayer> observed = scopedPlayers(scopeIds, observedPlayers);
            reconcilePlayerEvents(scope, previous, observed, now);
            for (String scopeId : scopeIds) {
                Map<UUID, UnifiedPlayer> scopePlayers = observedPlayers.get(scopeId);
                if (scopePlayers == null || !initializedPlayers.contains(scopeId)) continue;
                knownPlayers.put(scopeId, scopePlayers);
                updateSnapshotPlayers(scopeId, scopePlayers);
            }
        }
        flushPendingPlayerLeaves(now);
    }

    private String playerScope(String instanceId) {
        DesktopNetworkManager manager = currentNetworkManager();
        if (manager != null) {
            String networkId = manager.getNetworkForInstance(instanceId).map(network -> network.networkId()).orElse("");
            if (!networkId.isBlank()) return "network:" + networkId;
        }
        String networkId = snapshots.stream().filter(snapshot -> snapshot.instance().getInstanceId().equals(instanceId))
                .map(snapshot -> snapshot.instance().getNetworkId()).filter(id -> !id.isBlank()).findFirst().orElse("");
        return networkId.isBlank() ? "instance:" + instanceId : "network:" + networkId;
    }

    private Set<String> playerScopeIds(String scope) {
        Set<String> ids = new HashSet<>(knownPlayers.keySet());
        ids.addAll(observedPlayers.keySet());
        return ids.stream().filter(id -> playerScope(id).equals(scope)).collect(Collectors.toSet());
    }

    private Map<UUID, UnifiedPlayer> scopedPlayers(Set<String> scopeIds, Map<String, Map<UUID, UnifiedPlayer>> source) {
        Map<UUID, UnifiedPlayer> players = new LinkedHashMap<>();
        for (String scopeId : scopeIds) {
            if (!initializedPlayers.contains(scopeId)) continue;
            players.putAll(source.getOrDefault(scopeId, knownPlayers.getOrDefault(scopeId, Map.of())));
        }
        return players;
    }

    private void reconcilePlayerEvents(String scope, Map<UUID, UnifiedPlayer> previous, Map<UUID, UnifiedPlayer> observed, long now) {
        if (usesNetworkPlayerEvents(scope)) {
            for (UUID playerId : observed.keySet()) pendingPlayerLeaves.remove(new ScopedPlayer(scope, playerId));
            return;
        }
        for (UnifiedPlayer player : observed.values()) {
            if (previous.containsKey(player.getUuid())) continue;
            PendingPlayerLeave pending = pendingPlayerLeaves.remove(new ScopedPlayer(scope, player.getUuid()));
            if (pending == null && !matchesRecentNetworkLifecycle(scope, player.getUuid(), true)) enqueuePlayerEvent(scope, player, true);
        }
        for (UnifiedPlayer player : previous.values()) {
            if (observed.containsKey(player.getUuid())) continue;
            if (matchesRecentNetworkLifecycle(scope, player.getUuid(), false)) continue;
            if (scope.startsWith("network:")) {
                pendingPlayerLeaves.putIfAbsent(new ScopedPlayer(scope, player.getUuid()), new PendingPlayerLeave(player, now));
            } else {
                enqueuePlayerEvent(scope, player, false);
            }
        }
    }

    private void flushPendingPlayerLeaves(long now) {
        List<ScopedPlayer> expired = pendingPlayerLeaves.entrySet().stream()
                .filter(entry -> now - entry.getValue().createdAt() >= NETWORK_TRANSFER_GRACE_MS).map(Map.Entry::getKey).toList();
        for (ScopedPlayer player : expired) {
            if (usesNetworkPlayerEvents(player.scope())) {
                pendingPlayerLeaves.remove(player);
                continue;
            }
            PendingPlayerLeave pending = pendingPlayerLeaves.remove(player);
            if (pending == null) continue;
            Map<UUID, UnifiedPlayer> observed = scopedPlayers(playerScopeIds(player.scope()), observedPlayers);
            if (!observed.containsKey(player.playerId()) && !matchesRecentNetworkLifecycle(player.scope(), player.playerId(), false)) {
                enqueuePlayerEvent(player.scope(), pending.player(), false);
            }
        }
    }

    private boolean matchesRecentNetworkLifecycle(String scope, UUID playerId, boolean joined) {
        RecentNetworkLifecycle recent = recentNetworkLifecycles.get(new ScopedPlayer(scope, playerId));
        return recent != null && recent.joined() == joined;
    }

    private void bindNetworkEvents() {
        DesktopNetworkManager current = currentNetworkManager();
        if (current == networkManager) return;
        if (networkManager != null) networkManager.removeRuntimeEventListener(networkEventListener);
        networkManager = current;
        if (networkManager != null) networkManager.addRuntimeEventListener(networkEventListener);
    }

    private DesktopNetworkManager currentNetworkManager() {
        RemotelyClient client = RemotelyClient.INSTANCE;
        return DesktopNetworkAccess.manager(client);
    }

    private boolean usesNetworkPlayerEvents(String scope) {
        if (!scope.startsWith("network:")) return false;
        DesktopNetworkManager manager = currentNetworkManager();
        return manager != null && manager.getRuntimeSnapshot(scope.substring("network:".length())).connected();
    }

    private void applyNetworkEvent(NetworkEvent event) {
        networkPlayerNotifications.accept(event, System.currentTimeMillis()).ifPresent(lifecycle ->
                ScreenManager.getInstance().execute(() -> applyNetworkLifecycle(event.networkId(), lifecycle)));
    }

    private void applyNetworkLifecycle(String networkId, NetworkPlayerLifecycle lifecycle) {
        boolean joined = lifecycle.type() == NetworkPlayerLifecycleType.JOINED;
        String scope = "network:" + networkId;
        ScopedPlayer player = new ScopedPlayer(scope, lifecycle.playerId());
        recentNetworkLifecycles.entrySet().removeIf(entry -> lifecycle.occurredAt() - entry.getValue().occurredAt() > AUTHORITATIVE_EVENT_WINDOW_MS);
        pendingPlayerLeaves.remove(player);
        recentNetworkLifecycles.put(player, new RecentNetworkLifecycle(joined, lifecycle.occurredAt()));
        playerEvents.removeIf(event -> !event.authoritative() && event.scope().equals(scope) && event.playerId().equals(lifecycle.playerId()));
        enqueuePlayerEvent(scope, lifecycle.playerId(), lifecycle.playerName(), joined, true);
    }

    private void updateSnapshotPlayers(String instanceId, Map<UUID, UnifiedPlayer> players) {
        List<ServerSnapshot> updated = snapshots.stream().map(snapshot -> snapshot.instance().getInstanceId().equals(instanceId)
                ? new ServerSnapshot(snapshot.instance(), snapshot.uptimeMs(), snapshot.cpuPercent(), snapshot.memoryBytes(), snapshot.memoryLimitBytes(), List.copyOf(players.values()))
                : snapshot).toList();
        snapshots = List.copyOf(updated);
    }

    private void enqueuePlayerEvent(String scope, UnifiedPlayer player, boolean joined) {
        enqueuePlayerEvent(scope, player.getUuid(), player.getName(), joined, false);
    }

    private void enqueuePlayerEvent(String scope, UUID playerId, String playerName, boolean joined, boolean authoritative) {
        String name = playerName == null || playerName.isBlank() ? "Unknown" : playerName;
        while (playerEvents.size() >= 2) playerEvents.pollFirst();
        playerEvents.add(new PlayerEvent(playerId, name, joined, scope, authoritative, System.currentTimeMillis()));
    }

    private List<PlayerEvent> currentEvents(long now) {
        PlayerEvent event = playerEvents.peekFirst();
        while (event != null && now - event.createdAt() > EVENT_DURATION_MS) {
            playerEvents.pollFirst();
            event = playerEvents.peekFirst();
        }
        return List.copyOf(playerEvents);
    }

    private String formatStatus(ServerSnapshot server) {
        if (server.instance().getState() == InstanceState.STARTING) return "Starting";
        if (server.instance().getState() == InstanceState.STOPPING) return "Stopping";
        long uptimeMs = server.uptimeMs();
        if (uptimeMs <= 0) return "Running";
        long minutes = uptimeMs / 60_000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0) return days + "d " + hours % 24 + "h";
        if (hours > 0) return hours + "h " + minutes % 60 + "m";
        return Math.max(1, minutes) + "m";
    }

    private String formatMemory(long used, long limit) {
        double usedGb = used / 1_073_741_824.0;
        if (limit <= 0) return String.format(Locale.ROOT, "%.1f GB", usedGb);
        return String.format(Locale.ROOT, "%.1f/%.1f GB", usedGb, limit / 1_073_741_824.0);
    }

    private String formatPlayers(List<UnifiedPlayer> players) {
        if (players.isEmpty()) return "No Players";
        String visible = String.join(", ", players.stream().map(UnifiedPlayer::getName).map(name -> name == null || name.isBlank() ? "Unknown" : name).limit(3).toList());
        return players.size() > 3 ? visible + " +" + (players.size() - 3) : visible;
    }

    private String eventMessage(PlayerEvent event) {
        return event.name() + (event.joined() ? " Joined" : " Left");
    }

    private int eventWidth(PlayerEvent event) {
        return EVENT_HORIZONTAL_PADDING * 2 + eventContentWidth(event);
    }

    private int eventContentWidth(PlayerEvent event) {
        String sign = event.joined() ? "+" : "-";
        return EVENT_HEAD_SIZE + 4 + TextRenderer.getWidth(sign) + 3 + TextRenderer.getWidth(eventMessage(event));
    }

    private float eventVisibility(PlayerEvent event, long now) {
        float progress = Math.clamp((now - event.createdAt()) / (float) EVENT_DURATION_MS, 0f, 1f);
        float exit = Math.clamp((progress - EVENT_EXIT_START) / (1f - EVENT_EXIT_START), 0f, 1f);
        return 1f - exit * exit * exit;
    }

    private Identifier playerFace(PlayerEvent event) {
        Identifier face = playerFaces.get(event.playerId());
        if (face != null) return face;
        requestPlayerFace(event.playerId(), event.name());
        return MISSING_FACE;
    }

    private void requestPlayerFace(UUID playerId, String playerName) {
        if (System.currentTimeMillis() < playerFaceRetryAt.getOrDefault(playerId, 0L)) return;
        if (!requestedPlayerFaces.add(playerId)) return;
        String name = playerName == null || playerName.isBlank() ? "Unknown" : playerName;
        new Account(name, playerId.toString(), null, 0).getFaceIdAsync().whenComplete((identifier, error) -> {
            requestedPlayerFaces.remove(playerId);
            if (error == null && identifier != null && !MISSING_FACE.equals(identifier)) {
                ResourceManager.getInstance().retainImage(identifier);
                playerFaces.put(playerId, identifier);
                playerFaceRetryAt.remove(playerId);
            } else {
                playerFaceRetryAt.put(playerId, System.currentTimeMillis() + FACE_RETRY_MS);
            }
        });
    }

    private String serverDescription(ServerSnapshot server) {
        return formatStatus(server) + " • " + Math.round(server.cpuPercent()) + "% CPU • "
                + formatMemory(server.memoryBytes(), server.memoryLimitBytes()) + " • " + formatPlayers(server.players());
    }

    private void drawExpandedContent(IDrawContext context, int mouseX, int mouseY, float opacity) {
        List<ServerSnapshot> current = snapshots.stream().limit(8).toList();
        synchronizeRows(current);
        context.pushScissorState();
        context.enableScissor(getX() + 2, getY() + 2, getX() + getWidth() - 2, getY() + getHeight() - 2);
        int rowX = getX() + 5;
        int rowWidth = Math.max(1, Math.round(expandedContentWidth) - 10);
        int rowY = getY() + 5;
        if (current.isEmpty()) {
            emptyRow.setActive(false);
            emptyRow.setPosition(rowX, rowY);
            emptyRow.setSize(rowWidth, 28);
            emptyRow.setOpacity(opacity);
            emptyRow.render(context, mouseX, mouseY, 0f);
        } else {
            for (ServerSnapshot server : current) {
                MountableButtonWidget row = rows.get(server.instance().getInstanceId());
                row.setName(server.instance().getName());
                row.setDescription(serverDescription(server));
                row.setAccent(ThemeManager.getDefaultAccent());
                row.setPosition(rowX, rowY);
                row.setSize(rowWidth, 28);
                row.setOpacity(opacity);
                row.render(context, mouseX, mouseY, 0f);
                rowY += 31;
            }
        }
        context.disableScissor();
        context.popScissorState();
    }

    private void synchronizeRows(List<ServerSnapshot> servers) {
        rows.keySet().retainAll(servers.stream().map(server -> server.instance().getInstanceId()).toList());
        for (ServerSnapshot server : servers) {
            rows.computeIfAbsent(server.instance().getInstanceId(), ignored -> {
                Identifier quickIcon = iconManager.getQuickIconId(server.instance());
                MountableButtonWidget row = new MountableButtonWidget.Builder(server.instance().getName())
                        .icon(quickIcon == null ? Identifier.icon("server.png") : quickIcon)
                        .onClick(() -> openServerDetails(server.instance())).build();
                row.entranceAnimationEnabled = false;
                iconManager.loadIconIdAsync(server.instance(), row::setIcon);
                iconManager.loadRemoteIconAsync(server.instance(), () -> iconManager.loadIconIdAsync(server.instance(), row::setIcon));
                return row;
            });
        }
    }

    private void openServerDetails(Instance instance) {
        RemotelyClient client = RemotelyClient.INSTANCE;
        if (client == null || instance == null) return;
        Object parent = ScreenManager.getInstance().getCurrentScreen();
        client.openInstanceInTerminal(parent, instance);
    }
}
