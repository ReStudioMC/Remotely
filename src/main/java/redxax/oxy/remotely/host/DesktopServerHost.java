package redxax.oxy.remotely.host;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyGroup;
import redxax.oxy.remotely.config.RemotelyRecentItem;
import redxax.oxy.remotely.config.DesktopSettingsScreenFactory;
import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.session.StreamDataParser;
import redxax.oxy.remotely.session.TerminalSession;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkAdoptionReport;
import redxax.oxy.remotely.network.NetworkCreationMember;
import redxax.oxy.remotely.network.NetworkCreationRequest;
import redxax.oxy.remotely.network.NetworkHostScope;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkJobStatus;
import redxax.oxy.remotely.network.NetworkJobType;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkMemberManagement;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.NetworkRuntimeNodePresence;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.network.DesktopNetworkAccess;
import redxax.oxy.remotely.network.DesktopNetworkManager;
import redxax.oxy.remotely.servers.QuickServerSyncManager;
import redxax.oxy.remotely.servers.ReProxyManager;
import redxax.oxy.remotely.ui.server.NetworkAdoptionScreen;
import redxax.oxy.remotely.ui.server.NetworkMigrationScreen;
import redxax.oxy.remotely.ui.server.NetworkOverviewScreen;
import redxax.oxy.remotely.ui.server.NetworkCreationPlan;
import redxax.oxy.remotely.ui.server.ServerDetailsScreen;
import redxax.oxy.remotely.ui.server.ServerManagerScreen;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import redxax.oxy.remotely.ui.server.NetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.DesktopNetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.DesktopServerIconProvider;
import redxax.oxy.remotely.ui.server.ServerIconManager;
import redxax.oxy.remotely.ui.server.ServerIconProvider;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import redxax.oxy.remotely.ui.server.DesktopServerUiCapabilities;
import redxax.oxy.remotely.ui.server.DesktopServerDevelopmentProvider;
import redxax.oxy.remotely.ui.server.ServerDevelopmentProvider;
import redxax.oxy.remotely.ui.server.ServerDetailsTarget;
import redxax.oxy.remotely.ui.server.ClientServerDetailsTarget;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsController;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import redxax.oxy.remotely.ui.widgets.management.PlayerManagerController;
import redxax.oxy.remotely.ui.server.ServerConfigurationScreen;
import redxax.oxy.remotely.ui.server.ServerConfigurationTarget;
import redxax.oxy.remotely.ui.server.DesktopServerConfigurationUi;
import redxax.oxy.remotely.ui.server.DesktopServerConfigurationTarget;
import redxax.oxy.remotely.ui.server.DesktopTerminalSessionProvider;
import redxax.oxy.remotely.ui.server.DesktopServerTerminalPlatform;
import redxax.oxy.remotely.ui.server.ServerTerminalPlatform;
import redxax.oxy.remotely.ui.server.ServerTerminal;
import redxax.oxy.remotely.ui.server.ServerTerminalLifecycle;
import redxax.oxy.remotely.ui.server.CanonicalResourceContainerAdapter;
import redxax.oxy.remotely.ui.server.ResourceContainerAdapter;
import redxax.oxy.remotely.ui.widgets.InstanceResourceWidget;
import restudio.rebase.Rebase;
import restudio.rebase.api.RebaseApiFactory;
import restudio.rebase.api.unified.InstanceApi;
import restudio.rebase.api.unified.internal.StandardOutputStateParser;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.FileExplorerProviders;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.TerminalSessionProvider;
import restudio.rebase.backend.ExecutionProvider;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.backend.impl.PteroBackend;
import restudio.rebase.backend.impl.ReStudioBackend;
import restudio.rebase.backend.feature.ModpackManagementFeature;
import restudio.rebase.backend.feature.ResourceUsageFeature;
import restudio.rebase.backend.feature.ServerInfoFeature;
import restudio.rebase.localcontrol.LocalServerControllerClient;
import restudio.rebase.localcontrol.LocalServerControllerModels;
import restudio.rebase.localcontrol.LifecycleManager;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceFactory;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.instance.InstanceRepairer;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.hosting.RemoteHost;
import restudio.rebase.resource.InstanceDropImporter;
import restudio.rebase.util.Executors;
import restudio.rebase.util.FileUtils;
import restudio.rebase.util.ssh.SSHManager;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.platform.jvm.JvmStandardOutputStateParser;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.restudio.AuthStateListener;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.restudio.SessionState;
import restudio.rebase.ui.screens.auth.ReStudioLoginScreen;
import restudio.rebase.ui.screens.feedback.FeedbackBrowserScreen;
import restudio.rebase.ui.screens.notification.InboxScreen;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.resource.marketplace.JvmResourceMarketplaceAdapter;
import restudio.rebase.ui.screens.resources.ResourceContainer;
import restudio.rebase.ui.screens.resources.DesktopResourceContainerProvider;
import restudio.rebase.ui.screens.explorer.FileExplorerScreen;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rebase.ui.worldmap.WorldMapScreen;
import restudio.rebase.util.VersionUtil;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.IconCustomizerWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.BrowserUtils;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class DesktopServerHost implements ServerScreenHost {
    private static final String DESKTOP_HOST_ID = "remotely.desktopHostId";
    private final RemotelyClient client;
    private final Map<Runnable, Consumer<List<NetworkDefinition>>> networkListeners = new IdentityHashMap<>();
    private final Map<Runnable, Consumer<NetworkRuntimeSnapshot>> runtimeListeners = new IdentityHashMap<>();
    private final Map<Instance, Map<Consumer<ServerScreenHost.ServerState>, Consumer<InstanceState>>> stateListeners = new IdentityHashMap<>();
    private final Map<Runnable, AuthStateListener> authStateListeners = new IdentityHashMap<>();
    private final Map<Runnable, ReStudio> authStateOwners = new IdentityHashMap<>();
    private final Map<String, Instance> restudioBridgeInstances = new ConcurrentHashMap<>();
    private final Map<String, ServerModels.ClientServerView> restudioBridgeViews = new ConcurrentHashMap<>();
    private final AtomicLong restudioRequestGeneration = new AtomicLong();
    private final ServerIconProvider iconProvider;

    public DesktopServerHost(RemotelyClient client) {
        this.client = Objects.requireNonNull(client, "client");
        this.iconProvider = new DesktopServerIconProvider(DesktopRemotelyPaths.appDir());
    }

    @Override
    public ServerModels.ClientServerView serverView(Object value) {
        if (value instanceof ServerModels.ClientServerView server) return server;
        Instance instance = instance(value);
        if (instance == null) return null;
        String identifier = restudioIdentifier(instance);
        if (!identifier.isBlank()) {
            ServerModels.ClientServerView server = restudioBridgeViews.get(identifier);
            if (server != null) return server;
        }
        return desktopServerView(instance);
    }

    @Override
    public ServerScreenHost.ServerIconAccent serverIconAccent(ServerModels.ClientServerView server, boolean isCreate) {
        if (server == null || isCreate) return ServerScreenHost.ServerIconAccent.DEFAULT;
        Instance instance = resolve(server);
        if (instance == null) return ServerScreenHost.super.serverIconAccent(server, false);
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        NetworkDefinition network = manager == null ? null : manager.getNetworkForInstance(instance.getInstanceId()).orElse(null);
        NetworkMember member = network == null ? null : network.members().stream()
                .filter(candidate -> candidate.instanceId().equals(instance.getInstanceId())).findFirst().orElse(null);
        NetworkRuntimeNodePresence presence = null;
        if (manager != null && network != null && member != null) {
            NetworkRuntimeSnapshot snapshot = manager.getRuntimeSnapshot(network.networkId());
            if (snapshot != null && snapshot.connected()) presence = snapshot.node(member.nodeId()).orElse(null);
        }
        if (presence != null) {
            return switch (presence.status()) {
                case ONLINE -> ServerScreenHost.ServerIconAccent.NICE;
                case DRAINING, MAINTENANCE -> ServerScreenHost.ServerIconAccent.DEFAULT;
                case OFFLINE, REVOKED -> ServerScreenHost.ServerIconAccent.DANGER;
            };
        }
        InstanceState state = instance.getState();
        if (state == null) return ServerScreenHost.ServerIconAccent.DEFAULT;
        return switch (state) {
            case RUNNING, STARTING, STOPPING -> ServerScreenHost.ServerIconAccent.NICE;
            case CRASHED -> ServerScreenHost.ServerIconAccent.DANGER;
            case INSTALLING -> ServerScreenHost.ServerIconAccent.DEFAULT;
            default -> ServerScreenHost.ServerIconAccent.DEFAULT;
        };
    }

    @Override
    public Object iconTarget(ServerModels.ClientServerView server) {
        return resolve(server);
    }

    @Override
    public ServerIconProvider iconProvider() {
        return iconProvider;
    }

    @Override
    public String serverOrderKey(ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance != null) return instanceOrderKey(instance);
        String identifier = restudioIdentifier(server);
        if (!identifier.isBlank() && restudioBridgeViews.containsKey(identifier)) return "RESTUDIO_" + identifier;
        return ServerScreenHost.super.serverOrderKey(server);
    }

    @Override
    public ServerScreenHost.ServerIdentity identity(Object value) {
        Instance instance = instance(value);
        if (instance == null && value instanceof ServerModels.ClientServerView server) {
            instance = resolve(server);
        }
        if (instance == null) return ServerScreenHost.super.identity(value);
        BackendConfig config = instance.getBackendConfig();
        String backend = config == null || config.type == null ? "LOCAL" : config.type;
        return new ServerScreenHost.ServerIdentity(instance.getInstanceId(), instance.getName(), backend,
                instance.getPath(), instance.isServer(), isLocalInstance(instance), instance.getState() == InstanceState.INSTALLING);
    }

    @Override
    public ServerDetailsTarget detailsTarget(Object value) {
        if (value instanceof ServerDetailsTarget target) return target;
        if (value instanceof Instance instance) return new DesktopServerDetailsTarget(instance);
        if (value instanceof ServerModels.ClientServerView server) {
            Instance instance = resolve(server);
            return instance == null ? new ClientServerDetailsTarget(server) : new DesktopServerDetailsTarget(instance);
        }
        return ServerDetailsTarget.unavailable(value);
    }

    @Override
    public ServerScreenHost.ServerState state(Object value) {
        Instance instance = instance(value);
        if (instance == null && value instanceof ServerModels.ClientServerView server) {
            instance = resolve(server);
        }
        if (instance == null) return ServerScreenHost.super.state(value);
        return parseState(instance.getState());
    }

    @Override
    public Async<String> connectionInfo(Object value) {
        Instance instance = instance(value);
        if (instance == null) return Async.completed("");
        String forwarded = ReProxyManager.getForwardedAddress(instance);
        if (forwarded != null && !forwarded.isBlank()) return Async.completed(forwarded);
        if (instance.getBackend() == null) return Async.completed("");
        ServerInfoFeature feature = instance.getBackend().getFeature(ServerInfoFeature.class).orElse(null);
        if (feature == null) return Async.completed("");
        return JvmAsyncBridge.fromFuture(feature.getConnectionInfo()).thenApply(info -> info == null ? "" : info.getDisplayString());
    }

    @Override
    public String reProxyAddress(Object value) {
        Instance instance = instance(value);
        return instance == null ? "" : ReProxyManager.getForwardedAddress(instance);
    }

    @Override
    public boolean localPortOpen(Object value) {
        Instance instance = instance(value);
        if (instance == null || instance.getPort() < 1 || instance.getPort() > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", instance.getPort()), 350);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public Async<ServerScreenHost.LocalStatus> localStatus(Object value) {
        Instance instance = instance(value);
        if (instance == null && value instanceof ServerModels.ClientServerView server) {
            instance = resolve(server);
        }
        if (instance == null || !isLocalInstance(instance)) return Async.completed(null);
        Instance target = instance;
        return JvmAsyncBridge.fromFuture(CompletableFuture.supplyAsync(() -> {
            LocalServerControllerModels.StatusResponse status = LocalServerControllerClient.status(target);
            if (status == null) return null;
            return new ServerScreenHost.LocalStatus(status.ok, status.knownSession, status.ready, status.state,
                    status.desiredState, status.exitCode, status.lastError, status.pid, status.wrapperPid,
                    status.serverPid, status.pids);
        }));
    }

    @Override
    public void applyLocalStatus(Object value, ServerScreenHost.LocalStatus status, TerminalSession session) {
        Instance instance = instance(value);
        if (instance == null && value instanceof ServerModels.ClientServerView server) {
            instance = resolve(server);
        }
        if (instance == null || status == null || !status.controllerAvailable() || !status.knownSession()) return;
        String state = status.state().trim().toUpperCase(Locale.ROOT);
        switch (state) {
            case "STARTING" -> {
                LifecycleManager.requestStart(instance);
                instance.setState(InstanceState.STARTING);
            }
            case "RUNNING" -> {
                instance.setState(InstanceState.RUNNING);
            }
            case "STOPPING" -> {
                if ("RUNNING".equalsIgnoreCase(status.desiredState())) LifecycleManager.requestStart(instance);
                else LifecycleManager.requestStop(instance);
                instance.setState(InstanceState.STOPPING);
            }
            case "STOPPED" -> {
                LifecycleManager.complete(instance, LifecycleManager.activeOperationId(instance), InstanceState.STOPPED);
                QuickServerSyncManager.syncBackAfterStop(instance);
                if (ReProxyManager.isForwarded(instance)) ReProxyManager.stopQuietly(instance.getPort(), null);
                if (session != null && session.getTerminalWidget() instanceof ServerTerminalLifecycle terminal
                        && session.getTerminalWidget() instanceof TerminalWidget widget && widget.isTerminalReady()) {
                    terminal.stopProcessAsync();
                }
            }
            case "CRASHED" -> {
                String message = status.lastError().isBlank() ? "Server Crashed" : status.lastError();
                LifecycleManager.fail(instance, LifecycleManager.activeOperationId(instance), InstanceState.CRASHED, message);
                if (ReProxyManager.isForwarded(instance)) ReProxyManager.stopQuietly(instance.getPort(), null);
                if (session != null && session.getTerminalWidget() instanceof ServerTerminalLifecycle terminal
                        && session.getTerminalWidget() instanceof TerminalWidget widget && widget.isTerminalReady()) {
                    terminal.stopProcessAsync();
                }
            }
            default -> {
            }
        }
    }

    @Override
    public boolean isQuickServer(Object value) {
        Instance instance = instance(value);
        return instance != null && "true".equalsIgnoreCase(instance.getSettings().getProperty("quickServer.enabled"));
    }

    @Override
    public boolean isReProxyForwarded(Object value) {
        Instance instance = instance(value);
        return instance != null && ReProxyManager.isForwarded(instance);
    }

    @Override
    public void startReProxy(Object value, Runnable onComplete) {
        Instance instance = instance(value);
        if (instance == null) {
            unavailable(Action.NETWORK_LIFECYCLE);
            return;
        }
        ReProxyManager.start(instance, onComplete);
    }

    @Override
    public void stopReProxy(Object value, Runnable onComplete) {
        Instance instance = instance(value);
        if (instance == null) {
            unavailable(Action.NETWORK_LIFECYCLE);
            return;
        }
        ReProxyManager.stop(instance.getPort(), onComplete);
    }

    @Override
    public void setState(Object value, ServerScreenHost.ServerState state) {
        Instance instance = instance(value);
        if (instance == null && value instanceof ServerModels.ClientServerView server) {
            instance = resolve(server);
        }
        if (instance != null && state != null) {
            try {
                instance.setState(InstanceState.valueOf(state.name()));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    public void addStateListener(Object value, Consumer<ServerScreenHost.ServerState> listener) {
        Instance instance = instance(value);
        if (instance == null || listener == null) return;
        Consumer<InstanceState> bridge = state -> listener.accept(parseState(state));
        Map<Consumer<ServerScreenHost.ServerState>, Consumer<InstanceState>> listeners = stateListeners.computeIfAbsent(instance,
                ignored -> new IdentityHashMap<>());
        Consumer<InstanceState> previous = listeners.put(listener, bridge);
        if (previous != null) instance.removeStateListener(previous);
        instance.addStateListener(bridge);
    }

    @Override
    public void removeStateListener(Object value, Consumer<ServerScreenHost.ServerState> listener) {
        Instance instance = instance(value);
        if (instance == null || listener == null) return;
        Map<Consumer<ServerScreenHost.ServerState>, Consumer<InstanceState>> listeners = stateListeners.get(instance);
        if (listeners == null) return;
        Consumer<InstanceState> bridge = listeners.remove(listener);
        if (bridge != null) instance.removeStateListener(bridge);
        if (listeners.isEmpty()) stateListeners.remove(instance);
    }

    @Override
    public void onServerTabSelected(Object value) {
        Instance instance = instance(value);
        if (instance == null || !instance.isServer()) return;
        Instance target = instance;
        ServerModels.ClientServerView server = serverView(target);
        PlayerManagerController.getOrCreate(target, capabilities(server)).reloadProviders();
        if (VersionUtil.isMSMPCompatible(target.getVersionId())) {
            target.getMSMPManager().handleInstanceStateChange(target.getState());
        }
        CompletableFuture.runAsync(() -> {
            if (Boolean.parseBoolean(target.getSettings().getProperty("provider.msmp.enabled", "true"))
                    && !target.getMSMPManager().isConnected) {
                target.getMSMPManager().connect();
            }
        });
    }

    @Override
    public void onServerTabClosed(Object value) {
        Instance instance = instance(value);
        if (instance != null && instance.getMSMPManager() != null) {
            instance.getMSMPManager().disconnect();
        }
    }

    @Override
    public String msmpStatus(Object value) {
        Instance instance = instance(value);
        if (instance == null || !VersionUtil.isMSMPCompatible(instance.getVersionId())) return "Unavailable";
        return instance.getMSMPManager().isConnected ? "Connected" : "Disconnected";
    }

    @Override
    public Async<Void> startServer(RemotelyServerApi api, Object value) {
        Instance instance = instance(value);
        return instance == null ? ServerScreenHost.super.startServer(api, value) : DesktopServerUiCapabilities.desktop().setPower(instance, "start");
    }

    @Override
    public Async<Void> stopServer(RemotelyServerApi api, Object value) {
        Instance instance = instance(value);
        return instance == null ? ServerScreenHost.super.stopServer(api, value) : DesktopServerUiCapabilities.desktop().setPower(instance, "stop");
    }

    @Override
    public Async<Void> killServer(RemotelyServerApi api, Object value) {
        Instance instance = instance(value);
        return instance == null ? ServerScreenHost.super.killServer(api, value) : DesktopServerUiCapabilities.desktop().setPower(instance, "kill");
    }

    @Override
    public Async<ServerScreenHost.ServerMetrics> metrics(RemotelyServerApi api, Object value) {
        Instance instance = instance(value);
        if (instance == null) {
            ServerModels.ClientServerView server = serverView(value);
            if (server == null) return ServerScreenHost.super.metrics(api, value);
            Async<List<RemotelyServerApi.Player>> players = capabilities(server).players(server).exceptionally(ignored -> List.of());
            return ServerScreenHost.super.metrics(api, server).thenCompose(metrics -> players.thenApply(values ->
                    new ServerScreenHost.ServerMetrics(metrics.uptimeMs(), metrics.cpuPercent(), metrics.memoryBytes(),
                            normalizeMemoryLimit(server, metrics.memoryLimitBytes()), onlinePlayers(values),
                            configuredMaxPlayers(null, server))));
        }
        ServerModels.ClientServerView server = desktopServerView(instance);
        Async<List<RemotelyServerApi.Player>> players = capabilities(server).players(server).exceptionally(ignored -> List.of());
        return resourceUsage(instance).thenCompose(usage -> players.thenApply(values -> {
            int maxPlayers = configuredMaxPlayers(instance, server);
            int playerCount = onlinePlayers(values);
            if (usage == null) return new ServerScreenHost.ServerMetrics(0, 0, 0, 0, playerCount, maxPlayers);
            return new ServerScreenHost.ServerMetrics(usage.uptimeMs(), usage.cpuPercent(), usage.memoryBytes(),
                    usage.memoryLimitBytes(), playerCount, maxPlayers);
        }));
    }

    private static int onlinePlayers(List<RemotelyServerApi.Player> players) {
        return players == null ? 0 : (int) players.stream().filter(Objects::nonNull).filter(RemotelyServerApi.Player::online).count();
    }

    private static long normalizeMemoryLimit(ServerModels.ClientServerView server, long value) {
        if (server == null || server.limits == null || server.limits.memory == null || value != server.limits.memory.longValue()) return value;
        return server.limits.memory.longValue() * 1024L * 1024L;
    }

    private static Async<ResourceUsageFeature.ResourceUsage> resourceUsage(Instance instance) {
        if (instance == null || instance.getBackend() == null) {
            return Async.failed(new UnsupportedOperationException("Server Statistics Are Unavailable"));
        }
        return instance.getBackend().getFeature(ResourceUsageFeature.class)
                .map(feature -> JvmAsyncBridge.fromFuture(feature.getResources()))
                .orElseGet(() -> Async.failed(new UnsupportedOperationException("Server Statistics Are Unavailable")));
    }

    private static int configuredMaxPlayers(Instance instance, ServerModels.ClientServerView server) {
        String[] keys = {"maxPlayers", "max_players", "server.max-players", "max-player-count"};
        if (server != null && server.environment != null) {
            for (String key : keys) {
                int value = parsePositiveInt(server.environment.get(key));
                if (value > 0) return value;
            }
        }
        if (instance != null && instance.getServerProperties() != null) {
            int value = parsePositiveInt(instance.getServerProperties().getProperty("max-players"));
            if (value > 0) return value;
        }
        return 0;
    }

    private static int parsePositiveInt(String value) {
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(value.trim()));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @Override
    public String requestStart(Object value) {
        return requestStart(instance(value));
    }

    @Override
    public String requestStop(Object value) {
        return requestStop(instance(value));
    }

    @Override
    public String activeOperationId(Object value) {
        return activeOperationId(instance(value));
    }

    @Override
    public void completeOperation(Object value, String operationId, Object state) {
        completeOperation(instance(value), operationId, state);
    }

    @Override
    public void restoreRunning(Object value, String operationId, String reason) {
        restoreRunning(instance(value), operationId, reason);
    }

    @Override
    public void failOperation(Object value, String operationId, Object state, String reason) {
        failOperation(instance(value), operationId, state, reason);
    }

    @Override
    public void beginStart(Object value) {
        beginStart(instance(value));
    }

    @Override
    public void markReady(Object value, String operationId) {
        markReady(instance(value), operationId);
    }

    @Override
    public boolean isStopPending(Object value) {
        return isStopPending(instance(value));
    }

    @Override
    public Async<Void> loadInstanceProperties(Object value, boolean remote) {
        return loadInstanceProperties(instance(value), remote);
    }

    @Override
    public Async<Void> reloadInstanceSettings(Object value, boolean remote) {
        return reloadInstanceSettings(instance(value), remote);
    }

    @Override
    public Async<List<String>> listInstanceFiles(Object value) {
        return listInstanceFiles(instance(value));
    }

    @Override
    public Async<Void> loadInstanceModpack(Object value) {
        return loadInstanceModpack(instance(value));
    }

    @Override
    public Async<Object> createLocalInstance(Object template, String location) {
        Instance value = instance(template);
        return createLocalInstance(value, location).thenApply(result -> (Object) result);
    }

    @Override
    public Async<Object> createRemoteInstance(Object template, ServerScreenHost.HostView host) {
        return createRemoteInstance(instance(template), findHost(host)).thenApply(result -> (Object) result);
    }

    @Override
    public Async<Void> saveInstanceConfiguration(Object value, ServerSettingsDataController settingsController) {
        return saveInstanceConfiguration(instance(value), settingsController);
    }

    @Override
    public Async<Void> applyInstanceEdit(Object original, Object template, String newName,
                                         boolean repairStartScript, boolean reinstallSoftware,
                                         Notification notification, ServerSettingsDataController settingsController) {
        return applyInstanceEdit(instance(original), instance(template), newName, repairStartScript, reinstallSoftware,
                notification, settingsController);
    }

    @Override
    public Async<Void> refreshRemoteInstance(ServerScreenHost.HostView host) {
        return refreshRemoteInstance(findHost(host));
    }

    @Override
    public ServerScreenHost.HostView resolveRemoteHost(Object value, ServerScreenHost.HostView context) {
        RemoteHost resolved = resolveRemoteHost(instance(value), findHost(context));
        return resolved == null ? null : toHostView(resolved);
    }

    @Override
    public Async<Void> writeInstanceFile(Object value, String path, String content) {
        return writeInstanceFile(instance(value), path, content);
    }

    @Override
    public Async<ServerScreenHost.ImportResult> importDroppedFiles(Object target, List<?> files) {
        return importDroppedFiles(instance(target), files);
    }

    @Override
    public Object twinLocal(Object remote) {
        return twinLocal(instance(remote));
    }

    @Override
    public Object developmentSource(Object candidate) {
        return developmentSource(instance(candidate));
    }

    @Override
    public Async<Void> renameInstance(Object value, String name) {
        return renameInstance(instance(value), name);
    }

    @Override
    public Async<Void> renameRemoteServer(Object value, String name) {
        return renameRemoteServer(instance(value), name);
    }

    @Override
    public void openFileExplorer(Screen current, Object value) {
        openFileExplorer(current, instance(value));
    }

    @Override
    public void openServerConfiguration(Screen current, Object value) {
        openServerConfiguration(current, instance(value));
    }

    @Override
    public void openModpackBrowser(Screen current, ServerScreenHost.HostView host, boolean reStudioContext) {
        openModpackBrowser(current, findHost(host), reStudioContext);
    }

    @Override
    public ApplicationHost application() {
        return client.getHost();
    }

    @Override
    public ServerConfigurationTarget configurationTarget(Object value) {
        return value instanceof Instance instance ? new DesktopServerConfigurationTarget(instance) : ServerConfigurationTarget.unavailable(value);
    }

    @Override
    public ServerConfigurationTarget copyConfigurationTarget(Object value, String name) {
        if (!(value instanceof Instance instance)) return ServerConfigurationTarget.unavailable(value);
        Instance copy = new Instance(instance, name == null ? instance.getName() : name);
        BackendConfig backend = instance.getBackendConfig();
        if (backend != null) {
            copy.setBackendConfig(new BackendConfig(backend.type,
                new LinkedHashMap<>(backend.credentials == null ? Map.of() : backend.credentials)));
        }
        return new DesktopServerConfigurationTarget(copy);
    }

    @Override
    public ServerConfigurationTarget createConfigurationTarget() {
        Instance instance = new Instance("New Server", client.getHost().getGameVersion(), "");
        instance.setServer(true);
        return new DesktopServerConfigurationTarget(instance);
    }

    @Override
    public void applyTargetPreset(ServerConfigurationTarget target, Object preset) {
        ServerScreenHost.super.applyTargetPreset(target, preset instanceof String value ? creationPreset(value) : preset);
    }

    @Override
    public ServerScreenHost.HostView hostView(Object value) {
        if (value instanceof ServerScreenHost.HostView host) return host;
        return value instanceof RemoteHost host ? toHostView(host) : null;
    }

    @Override
    public void configureTargetDefaults(ServerConfigurationTarget target) {
        if (!(target instanceof DesktopServerConfigurationTarget desktop)) return;
        Instance instance = (Instance) desktop.raw();
        instance.setLocalLifecyclePersistent(true);
        instance.setLocalRestartOnCrash(true);
    }

    @Override
    public void configureRemoteTarget(ServerConfigurationTarget target, ServerScreenHost.HostView view, ServerConfigurationTarget source) {
        if (!(target instanceof DesktopServerConfigurationTarget desktop)) return;
        RemoteHost host = findHost(view);
        if (host == null) return;
        Instance instance = (Instance) desktop.raw();
        if (host.isPanelHost() && source instanceof DesktopServerConfigurationTarget sourceTarget
                && PteroBackend.isPanelType(sourceTarget.backendType())) {
            BackendConfig sourceBackend = ((Instance) sourceTarget.raw()).getBackendConfig();
            instance.setBackendConfig(sourceBackend == null ? null : new BackendConfig(sourceBackend.type,
                    new LinkedHashMap<>(sourceBackend.credentials == null ? Map.of() : sourceBackend.credentials)));
            return;
        }
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("host", host.getIp());
        credentials.put("hostId", host.hostId);
        if (host.isPanelHost()) {
            credentials.put("apiUrl", PteroBackend.normalizePanelUrl(host.getIp()));
            instance.setBackendConfig(new BackendConfig(host.getType().toUpperCase(Locale.ROOT), credentials));
            return;
        }
        credentials.put("port", String.valueOf(host.getPort()));
        credentials.put("user", host.getUser());
        credentials.put("password", host.getPassword());
        credentials.put("authMode", host.getAuthMode());
        if (host.getKeyPath() != null && !host.getKeyPath().isBlank()) credentials.put("keyPath", host.getKeyPath());
        if (host.getInstanceRegistryPath() != null && !host.getInstanceRegistryPath().isBlank()) credentials.put("registryPath", host.getInstanceRegistryPath());
        if (host.getKeyPassphrase() != null && !host.getKeyPassphrase().isBlank()) credentials.put("keyPassphrase", host.getKeyPassphrase());
        instance.setBackendConfig(new BackendConfig("SSH", credentials));
    }

    @Override
    public void openExternal(String url) {
        if (url != null && !url.isBlank()) BrowserUtils.openBrowser(url);
    }

    @Override
    public ServerScreenHost.ConfigurationUi createConfigurationUi(Screen owner,
                                                                   ServerScreenHost.ConfigurationState state,
                                                                   ServerSettingsDataController settingsController,
                                                                   Map<String, String> remoteVariables,
                                                                   List<String> extraFiles,
                                                                   Runnable reloadDataDrivenSettings,
                                                                   BooleanSupplier allowServerSoftwareChange) {
        return DesktopServerConfigurationUi.create(owner, state, settingsController, remoteVariables, extraFiles,
                reloadDataDrivenSettings, allowServerSoftwareChange, defaultInstanceLocation());
    }

    @Override
    public ServerUiCapabilityProvider capabilities(ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        return instance == null ? DesktopServerUiCapabilities.desktop() : DesktopServerUiCapabilities.desktop(instance);
    }

    @Override
    public ServerDevelopmentProvider developmentProvider() {
        return new DesktopServerDevelopmentProvider();
    }

    @Override
    public boolean supportsDevelopment(Object value) {
        Instance instance = instance(value);
        return instance != null && instance.getBackendConfig() != null && instance.getBackendConfig().type != null
                && !"LOCAL".equalsIgnoreCase(instance.getBackendConfig().type);
    }

    @Override
    public String developmentConfigPath() {
        return DesktopRemotelyPaths.appDir().resolve("data").toString();
    }

    @Override
    public boolean authenticated() {
        ReStudio studio = ReStudio.getInstance();
        return studio != null && studio.isAuthenticated();
    }

    @Override
    public ServerScreenHost.AccountIdentity accountIdentity() {
        ReStudio studio = ReStudio.getInstance();
        if (studio == null) {
            return new ServerScreenHost.AccountIdentity(false, "", "Sign In", "steve.png");
        }
        String subjectId = firstNonBlank(studio.getUserId(), studio.getUsername(), studio.getEmail());
        String displayName = firstNonBlank(studio.getDisplayName(), studio.getUsername(), studio.getEmail());
        if (studio.isAuthenticated() && !displayName.isBlank()) {
            return new ServerScreenHost.AccountIdentity(true, subjectId, displayName, "steve.png");
        }
        String sessionLabel = switch (studio.getSessionState()) {
            case RESTORING -> "Restoring Session";
            case REFRESHING -> "Refreshing Session";
            case CONNECTION_LOST -> "Reconnecting";
            case REAUTH_REQUIRED -> "Sign In Required";
            default -> "";
        };
        if (!sessionLabel.isBlank()) {
            return new ServerScreenHost.AccountIdentity(studio.isAuthenticated(), subjectId, sessionLabel, "steve.png");
        }
        return new ServerScreenHost.AccountIdentity(studio.isAuthenticated(), subjectId,
                studio.isAuthenticated() ? "Account" : "Sign In", "steve.png");
    }

    @Override
    public Async<Identifier> accountAvatar() {
        ReStudio studio = ReStudio.getInstance();
        if (studio == null || !studio.isAuthenticated()) {
            return Async.completed(null);
        }
        return JvmAsyncBridge.fromFuture(studio.loadAvatarId()).exceptionally(ignored -> null);
    }

    @Override
    public void reloadInstances() {
        InstanceManager instances;
        try {
            instances = Rebase.get().getInstanceManager();
        } catch (RuntimeException exception) {
            return;
        }
        Map<String, SSHManager> activeSessions = new LinkedHashMap<>();
        try {
            for (RemoteHost host : instances.getRemoteHosts()) {
                if (host == null || host.isPanelHost()) continue;
                SSHManager manager = host.getExistingSshManager();
                if (manager != null && manager.isConnected()) activeSessions.put(host.hostId, manager);
            }
            instances.loadInstances();
            for (RemoteHost host : instances.getRemoteHosts()) {
                if (host == null || host.isPanelHost()) continue;
                SSHManager manager = activeSessions.get(host.hostId);
                if (manager == null) continue;
                RemoteHost previous = manager.getRemoteHost();
                if (previous != null && Objects.equals(host.getIp(), previous.getIp()) && Objects.equals(host.getUser(), previous.getUser()) && host.getPort() == previous.getPort()) {
                    manager.updateHostReference(host);
                    host.setSshManager(manager);
                    activeSessions.remove(host.hostId);
                } else {
                    try {
                        manager.shutdown();
                    } catch (Exception ignored) {
                    }
                    activeSessions.remove(host.hostId);
                }
            }
            for (SSHManager manager : activeSessions.values()) {
                try {
                    manager.shutdown();
                } catch (Exception ignored) {
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    public String requestStart(Instance instance) {
        return instance == null ? "" : LifecycleManager.requestStart(instance);
    }

    public String requestStop(Instance instance) {
        return instance == null ? "" : LifecycleManager.requestStop(instance);
    }

    public String activeOperationId(Instance instance) {
        if (instance == null) return "";
        String operationId = LifecycleManager.activeOperationId(instance);
        return operationId == null ? "" : operationId;
    }

    public void completeOperation(Instance instance, String operationId, Object state) {
        if (instance != null && state instanceof InstanceState value) LifecycleManager.complete(instance, operationId, value);
    }

    public void restoreRunning(Instance instance, String operationId, String reason) {
        if (instance != null) LifecycleManager.restoreRunning(instance, operationId, reason);
    }

    public void failOperation(Instance instance, String operationId, Object state, String reason) {
        if (instance != null && state instanceof InstanceState value) LifecycleManager.fail(instance, operationId, value, reason);
    }

    public void beginStart(Instance instance) {
        if (instance != null) LifecycleManager.beginStart(instance);
    }

    public void markReady(Instance instance, String operationId) {
        if (instance != null) LifecycleManager.markReady(instance, operationId);
    }

    public boolean isStopPending(Instance instance) {
        return instance != null && LifecycleManager.isStopPending(instance);
    }

    public Async<Void> loadInstanceProperties(Instance instance, boolean remote) {
        if (instance == null) {
            return Async.completed(null);
        }
        if (remote) {
            return JvmAsyncBridge.fromFuture(instance.loadRemoteServerProperties());
        }
        return JvmAsyncBridge.fromFuture(CompletableFuture.runAsync(instance::loadServerProperties));
    }

    public Async<Void> reloadInstanceSettings(Instance instance, boolean remote) {
        if (instance == null || !remote) {
            return Async.completed(null);
        }
        return JvmAsyncBridge.fromFuture(instance.reloadSettingsFromBackend());
    }

    public Async<List<String>> listInstanceFiles(Instance instance) {
        if (instance == null || instance.getPath() == null || instance.getPath().isBlank()) {
            return Async.completed(List.of());
        }
        return JvmAsyncBridge.fromFuture(RebaseApiFactory.get(instance).listDirectory(Path.of(instance.getPath()))
                .thenApply(entries -> {
                    List<String> result = new ArrayList<>();
                    if (entries != null) {
                        entries.forEach(entry -> result.add(entry == null ? "" : entry.toString()));
                    }
                    return result;
                })
                .exceptionally(ignored -> List.of()));
    }

    public Async<Void> loadInstanceModpack(Instance instance) {
        BackendConfig backendConfig = instance == null ? null : instance.getBackendConfig();
        if (backendConfig == null || "LOCAL".equalsIgnoreCase(backendConfig.type)) {
            return Async.completed(null);
        }
        var backend = instance.getBackend();
        if (backend == null) {
            return Async.completed(null);
        }
        return backend.getFeature(ModpackManagementFeature.class)
                .map(feature -> JvmAsyncBridge.fromFuture(feature.getInstalledModpackInfo().thenAccept(info -> info.ifPresent(modpack -> {
                    instance.setModpackProvider(modpack.provider());
                    instance.setModpackProjectId(modpack.projectId());
                    instance.setModpackVersionId(modpack.versionId());
                    instance.setModpackVersionNumber(modpack.versionNumber());
                })).exceptionally(ignored -> null)))
                .orElseGet(() -> Async.completed(null));
    }

    @Override
    public String defaultInstanceLocation() {
        return Rebase.get().getInstancesDir().toString();
    }

    public Async<Instance> createLocalInstance(Instance template, String location) {
        if (template == null) {
            return Async.failed(new IllegalArgumentException("Server Template Is Unavailable"));
        }
        try {
            Path root = location == null || location.isBlank() ? Rebase.get().getInstancesDir() : Path.of(location);
            return JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().createInstanceWithLogger(template, root));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<Instance> createRemoteInstance(Instance template, RemoteHost remoteHost) {
        if (template == null || remoteHost == null) {
            return Async.failed(new IllegalArgumentException("Remote Server Details Are Unavailable"));
        }
        return JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().createRemoteInstanceWithLogger(template, remoteHost));
    }

    @Override
    public ServerSettingsDataController createServerSettingsController(Object instance, ServerSettingsSnapshot snapshot) {
        if (!(instance instanceof Instance value)) {
            return ServerSettingsDataController.unavailable();
        }
        return new ServerSettingsController(value, snapshot);
    }

    public Async<Void> saveInstanceConfiguration(Instance instance, ServerSettingsDataController settingsController) {
        if (instance == null || settingsController == null) {
            return Async.failed(new IllegalArgumentException("Server Configuration Is Unavailable"));
        }
        return JvmAsyncBridge.fromFuture(instance.saveServerProperties())
                .thenCompose(ignored -> settingsController.save(instance))
                .thenCompose(ignored -> JvmAsyncBridge.fromFuture(instance.save()));
    }

    public Async<Void> applyInstanceEdit(Instance original, Instance template, String newName,
                                         boolean repairStartScript, boolean reinstallSoftware,
                                         Notification notification, ServerSettingsDataController settingsController) {
        if (original == null || template == null || settingsController == null) {
            return Async.failed(new IllegalArgumentException("Server Configuration Is Unavailable"));
        }
        return JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().applyInstanceEdit(original, template, newName,
                        repairStartScript, reinstallSoftware, notification))
                .thenCompose(ignored -> settingsController.save(original));
    }

    public Async<Void> refreshRemoteInstance(RemoteHost remoteHost) {
        return remoteHost == null ? Async.completed(null)
                : JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().fetchRemoteInstances(remoteHost));
    }

    public RemoteHost resolveRemoteHost(Instance instance, RemoteHost context) {
        return context == null ? findHost(instance) : context;
    }

    @Override
    public Async<ServerModels.CheckoutResponse> createHostedCheckout(String serverName, String planName,
                                                                       Map<String, String> environment,
                                                                       Map<String, String> fileConfigs,
                                                                       String subdomain,
                                                                       ServerModels.CustomPlanRequest customPlan) {
        return JvmAsyncBridge.fromFuture(ReStudio.getInstance().getApi().createCheckoutSessionDetails(serverName, planName, null, environment,
                fileConfigs, null, subdomain, null, customPlan));
    }

    public Async<Void> writeInstanceFile(Instance instance, String path, String content) {
        if (instance == null || instance.getPath() == null || path == null || path.isBlank()) {
            return Async.failed(new IllegalArgumentException("Instance File Is Unavailable"));
        }
        return JvmAsyncBridge.fromFuture(RebaseApiFactory.get(instance).writeFile(Path.of(instance.getPath(), path), content));
    }

    public Async<ServerScreenHost.ImportResult> importDroppedFiles(Instance target, List<?> files) {
        List<Path> paths = files == null ? List.of() : files.stream()
                .filter(Path.class::isInstance)
                .map(Path.class::cast)
                .toList();
        return JvmAsyncBridge.fromFuture(CompletableFuture.supplyAsync(() -> {
            InstanceDropImporter.Result result = InstanceDropImporter.importFiles(target, paths);
            return new ServerScreenHost.ImportResult(result.imported(), result.failed(), result.resourcesChanged(), result.worldsChanged());
        }, Executors.IO));
    }

    public Instance twinLocal(Instance remote) {
        if (remote == null) {
            return null;
        }
        try {
            var twin = Rebase.get().getTwinManager().getTwinForSource(remote);
            return twin == null ? null : Rebase.get().getTwinManager().getTwinInstance(twin);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public Instance developmentSource(Instance candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            var twin = Rebase.get().getTwinManager().getTwins().stream()
                    .filter(item -> Objects.equals(item.twinInstanceId, candidate.getInstanceId()))
                    .findFirst()
                    .orElse(null);
            if (twin == null || twin.sourceInstanceId == null || twin.sourceInstanceId.isBlank()) {
                return candidate;
            }
            Instance source = Rebase.get().getInstanceManager().getInstanceById(twin.sourceInstanceId);
            return source == null ? candidate : source;
        } catch (RuntimeException exception) {
            return candidate;
        }
    }

    public Async<Void> renameInstance(Instance instance, String name) {
        if (instance == null) {
            return Async.failed(new IllegalArgumentException("Instance Is Unavailable"));
        }
        String previousName = instance.getName();
        return JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().renameInstance(instance, name))
                .thenCompose(ignored -> {
                    if (!(instance.getBackend() instanceof ReStudioBackend backend)) return Async.completed(null);
                    return JvmAsyncBridge.fromFuture(ReStudio.getInstance().getApi().renameServer(backend.getServerId(), name));
                })
                .exceptionallyCompose(failure -> JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().renameInstance(instance, previousName))
                        .handle((ignored, rollbackFailure) -> {
                            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
                            throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
                        }));
    }

    public Async<Void> renameRemoteServer(Instance instance, String name) {
        return Async.completed(null);
    }

    @Override
    public void closeServerScreen(Screen current, Object parent) {
        client.getHost().openParentScreen(current, parent);
    }

    @Override
    public boolean mergeServerScreen(Screen current, ServerModels.ClientServerView server, boolean openDevelopment) {
        Instance instance = resolve(server);
        if (instance == null) {
            return false;
        }
        String identifier = restudioIdentifier(server);
        if (!identifier.isBlank() && restudioBridgeViews.containsKey(identifier)) {
            recordRecentRestudioServer(identifier, identifier, server.name);
        }
        if (current instanceof ServerDetailsScreen details) {
            details.addInstanceTab(instance);
            if (openDevelopment) details.openDevelopment(instance);
            return true;
        }
        application().setScreen(new ServerDetailsScreen(current, client, instance, openDevelopment));
        return true;
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> localServers() {
        try {
            List<Instance> instances = Rebase.get().getInstanceManager().getLocalInstances();
            return Async.completed(instances == null ? List.of() : instances.stream()
                    .filter(Objects::nonNull)
                    .filter(instance -> !instance.isHidden())
                    .map(this::desktopServerView)
                    .toList());
        } catch (RuntimeException exception) {
            return Async.completed(List.of());
        }
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> restudioServers() {
        long requestGeneration = restudioRequestGeneration.incrementAndGet();
        ReStudio studio = ReStudio.getInstance();
        if (studio == null || !studio.isAuthenticated()) {
            clearRestudioBridge();
            return Async.completed(List.of());
        }
        try {
            return JvmAsyncBridge.fromFuture(studio.getApi().getServers().thenApply(servers -> {
                List<ServerModels.ClientServerView> actualServers = servers == null ? List.of() : servers.stream().filter(Objects::nonNull).toList();
                Map<String, Instance> nextInstances = new LinkedHashMap<>();
                Map<String, ServerModels.ClientServerView> nextViews = new LinkedHashMap<>();
                for (ServerModels.ClientServerView server : actualServers) {
                    server.backendType = "RESTUDIO";
                    String identifier = restudioIdentifier(server);
                    if (identifier.isBlank()) continue;
                    Map<String, String> credentials = new LinkedHashMap<>();
                    credentials.put("identifier", identifier);
                    credentials.put("host", server.sftpIp == null ? "" : server.sftpIp);
                    credentials.put("port", String.valueOf(server.sftpPort));
                    credentials.put("user", server.sftpUser == null ? "" : server.sftpUser);
                    credentials.put("password", "");
                    credentials.put("installing", String.valueOf(server.isInstalling));
                    credentials.put("suspended", String.valueOf(server.isSuspended));

                    Instance instance = restudioBridgeInstances.computeIfAbsent(identifier,
                            ignored -> new Instance(server.name, "unknown", ""));
                    instance.setName(server.name);
                    updateRestudioCredentials(instance, credentials);
                    instance.setServer(true);
                    applyRestudioObservedState(instance, "", server.isSuspended, server.isInstalling);
                    if (server.loader != null && !server.loader.isBlank()) {
                        try {
                            instance.setModLoader(ModLoader.valueOf(server.loader.toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException ignored) {
                            instance.setModLoader(ModLoader.VANILLA);
                        }
                    }
                    if (server.version != null) instance.setVersionId(server.version);
                    nextInstances.put(identifier, instance);
                    nextViews.put(identifier, server);
                }

                if (requestGeneration != restudioRequestGeneration.get() || ReStudio.getInstance() != studio || !studio.isAuthenticated()) {
                    return actualServers;
                }
                restudioBridgeInstances.keySet().removeIf(identifier -> !nextInstances.containsKey(identifier));
                restudioBridgeInstances.putAll(nextInstances);
                restudioBridgeViews.clear();
                restudioBridgeViews.putAll(nextViews);

                for (Map.Entry<String, Instance> entry : nextInstances.entrySet()) {
                    String identifier = entry.getKey();
                    Instance instance = entry.getValue();
                    ServerModels.ClientServerView server = nextViews.get(identifier);
                    try {
                        studio.getApi().getSftpToken(identifier)
                                .thenAccept(value -> {
                                    if (requestGeneration == restudioRequestGeneration.get()) {
                                        updateRestudioCredential(instance, "password", value == null ? "" : value);
                                    }
                                })
                                .exceptionally(ignored -> null);
                    } catch (RuntimeException ignored) {
                    }
                    try {
                        studio.getApi().getServerResources(identifier)
                                .thenAccept(stats -> {
                                    if (stats != null && requestGeneration == restudioRequestGeneration.get()) {
                                        applyRestudioObservedState(instance, stats.currentState, server.isSuspended || stats.isSuspended,
                                                server.isInstalling);
                                    }
                                })
                                .exceptionally(ignored -> null);
                    } catch (RuntimeException ignored) {
                    }
                }
                return actualServers;
            }));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    @Override
    public List<RemotelyRecentItem> recentRestudioItems() {
        RemotelyConfigStore config = recentConfig();
        return config == null ? List.of() : config.getRecentRestudioItems();
    }

    @Override
    public void recordRecentRestudioItem(RemotelyRecentItem item) {
        RemotelyConfigStore config = recentConfig();
        if (config != null) {
            config.recordRecentRestudioItem(item);
        }
    }

    @Override
    public void removeRecentRestudioItem(RemotelyRecentItem item) {
        RemotelyConfigStore config = recentConfig();
        if (config == null || item == null) {
            return;
        }
        config.setRecentRestudioItems(config.getRecentRestudioItems().stream()
                .filter(existing -> !existing.key().equals(item.key()))
                .toList());
    }

    @Override
    public boolean openRecentRestudioItem(Screen current, RemotelyRecentItem item) {
        if (item == null || item.serverId().isBlank()) {
            return false;
        }
        ServerModels.ClientServerView server = restudioBridgeViews.get(item.serverId());
        if (server == null && !item.path().isBlank()) {
            server = restudioBridgeViews.get(item.path());
        }
        if (server == null) {
            restudioServers().whenComplete((servers, failure) -> application().execute(() -> {
                if (failure != null || servers == null) {
                    recentUnavailable(item);
                    return;
                }
                ServerModels.ClientServerView resolved = servers.stream()
                        .filter(Objects::nonNull)
                        .filter(candidate -> item.serverId().equals(restudioIdentifier(candidate)))
                        .findFirst()
                        .orElse(null);
                if (resolved != null) {
                    openRecentRestudioItem(current, item);
                } else {
                    recentUnavailable(item);
                }
            }));
            return true;
        }
        if (item.kind() == RemotelyRecentItem.Kind.SERVER) {
            recordRecentRestudioItem(item);
            boolean opened = mergeServerScreen(current, server, false);
            if (!opened) removeRecentRestudioItem(item);
            return opened;
        }
        int separator = item.path().indexOf(':');
        if (separator <= 0 || separator >= item.path().length() - 1) {
            removeRecentRestudioItem(item);
            return false;
        }
        Instance instance = resolve(server);
        if (instance == null || client.getFlowManager() == null) {
            removeRecentRestudioItem(item);
            return false;
        }
        recordRecentRestudioItem(item);
        client.openReSyncStudio(current, instance, server);
        String type = item.path().substring(0, separator).toLowerCase(Locale.ROOT);
        String id = item.path().substring(separator + 1);
        client.getFlowManager().openStudioDocument(restudioIdentifier(server), item.path(), screen -> screen.openWorkspaceResource(type, id));
        return true;
    }

    private void recentUnavailable(RemotelyRecentItem item) {
        removeRecentRestudioItem(item);
        application().notify("Recent Item Unavailable", "The Saved ReStudio Item Was Removed", ReSyncNotificationLevel.ERROR);
    }

    @Override
    public void addInstanceChangeListener(Runnable listener) {
        if (listener != null) {
            Rebase.get().getInstanceManager().addChangeListener(listener);
        }
    }

    @Override
    public void removeInstanceChangeListener(Runnable listener) {
        if (listener != null) {
            Rebase.get().getInstanceManager().removeChangeListener(listener);
        }
    }

    @Override
    public void addNetworkChangeListener(Runnable listener) {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        if (manager == null || listener == null) {
            return;
        }
        Consumer<List<NetworkDefinition>> callback = ignored -> listener.run();
        networkListeners.put(listener, callback);
        manager.addListener(callback);
    }

    @Override
    public void removeNetworkChangeListener(Runnable listener) {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        Consumer<List<NetworkDefinition>> callback = networkListeners.remove(listener);
        if (manager != null && callback != null) {
            manager.removeListener(callback);
        }
    }

    @Override
    public void addRuntimeChangeListener(Runnable listener) {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        if (manager == null || listener == null) {
            return;
        }
        Consumer<NetworkRuntimeSnapshot> callback = ignored -> listener.run();
        runtimeListeners.put(listener, callback);
        manager.addRuntimeListener(callback);
    }

    @Override
    public void removeRuntimeChangeListener(Runnable listener) {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        Consumer<NetworkRuntimeSnapshot> callback = runtimeListeners.remove(listener);
        if (manager != null && callback != null) {
            manager.removeRuntimeListener(callback);
        }
    }

    @Override
    public void addAuthStateListener(Runnable listener) {
        if (listener == null || authStateListeners.containsKey(listener)) {
            return;
        }
        AuthStateListener callback = new AuthStateListener() {
            @Override
            public void onLogin(String email) {
                listener.run();
            }

            @Override
            public void onLogout() {
                clearRestudioBridge();
                listener.run();
            }

            @Override
            public void onSessionExpired() {
                clearRestudioBridge();
                listener.run();
            }

            @Override
            public void onSessionStateChanged(SessionState state) {
                listener.run();
            }
        };
        authStateListeners.put(listener, callback);
        ReStudio studio = ReStudio.getInstance();
        if (studio != null) {
            authStateOwners.put(listener, studio);
            studio.addListener(callback);
        }
    }

    @Override
    public void removeAuthStateListener(Runnable listener) {
        AuthStateListener callback = authStateListeners.remove(listener);
        ReStudio studio = authStateOwners.remove(listener);
        if (callback != null && studio != null) {
            studio.removeListener(callback);
        }
    }

    @Override
    public Async<List<ServerScreenHost.HostView>> remoteHosts() {
        try {
            InstanceManager instances = Rebase.get().getInstanceManager();
            return Async.completed(instances.getRemoteHosts().stream().filter(Objects::nonNull).map(DesktopServerHost::toHostView).toList());
        } catch (RuntimeException exception) {
            return Async.completed(List.of());
        }
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> hostServers(ServerScreenHost.HostView view) {
        RemoteHost remoteHost = findHost(view);
        if (remoteHost == null) {
            return Async.completed(List.of());
        }
        try {
            InstanceManager instances = Rebase.get().getInstanceManager();
            return Async.completed(instances.getRemoteInstances(remoteHost).stream().filter(Objects::nonNull)
                    .filter(instance -> !instance.isHidden())
                    .map(this::desktopServerView).toList());
        } catch (RuntimeException exception) {
            return Async.completed(List.of());
        }
    }

    @Override
    public Async<List<ServerScreenHost.NetworkView>> networks() {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        if (manager == null) {
            return Async.completed(List.of());
        }
        try {
            return Async.completed(manager.getNetworks().stream().filter(Objects::nonNull).map(network ->
                    new ServerScreenHost.NetworkView(network.networkId(), network.name(), network.desiredState().name(),
                            "Managed Network", network.members().stream().map(member -> member.instanceId()).toList(), true,
                            network.proxyInstanceId())).toList());
        } catch (RuntimeException exception) {
            return Async.completed(List.of());
        }
    }

    @Override
    public boolean networkReSyncEnabled(ServerScreenHost.NetworkView view) {
        if (view == null || view.id().isBlank()) return false;
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        NetworkDefinition network = manager == null ? null : manager.getNetwork(view.id()).orElse(null);
        return network != null && (network.runtime().enabled() || network.members().stream().anyMatch(NetworkMember::resyncEnabled));
    }

    @Override
    public ServerScreenHost.NetworkJobView latestNetworkJob(ServerScreenHost.NetworkView network) {
        if (network == null || network.id().isBlank()) {
            return null;
        }
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        NetworkJob job = manager == null ? null : manager.getJobManager().getJobs(network.id()).stream().findFirst().orElse(null);
        return job == null ? null : new ServerScreenHost.NetworkJobView(job.canResume(), job.canRollback(), job.status().name());
    }

    @Override
    public NetworkOverviewProvider networkOverviewProvider() {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(this.client);
        return manager == null ? NetworkOverviewProvider.unavailableProvider() : new DesktopNetworkOverviewProvider(this.client, manager);
    }

    @Override
    public Async<NetworkAdoptionReport> scanNetwork(ServerModels.ClientServerView server, boolean migrate) {
        Instance proxy = resolve(server);
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        if (proxy == null) return Async.failed(new IllegalArgumentException("Proxy Server Is Unavailable"));
        if (manager == null) return Async.failed(new IllegalStateException("Network Manager Is Unavailable"));
        List<Instance> instances = List.copyOf(Rebase.get().getInstanceManager().getAllInstances());
        return migrate ? manager.scanLegacyMigration(proxy, instances) : manager.scanForAdoption(proxy, instances);
    }

    @Override
    public void openNetworkImport(Screen parent, ServerModels.ClientServerView server, NetworkAdoptionReport report, boolean migrate) {
        Instance proxy = resolve(server);
        if (proxy == null || report == null || !proxy.getInstanceId().equals(report.proxyInstanceId())) {
            throw new IllegalArgumentException("Proxy Server Is No Longer Available");
        }
        application().setScreen(migrate
                ? new NetworkMigrationScreen(parent, client, proxy, report)
                : new NetworkAdoptionScreen(parent, client, proxy, report));
    }

    @Override
    public Async<Void> networkAction(ServerScreenHost.NetworkView view, String action) {
        if (view == null || view.id().isBlank() || client.getNetworkManager() == null) {
            return Async.failed(new IllegalArgumentException("Network Is Unavailable"));
        }
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        if (manager == null) {
            return Async.failed(new IllegalArgumentException("Network Is Unavailable"));
        }
        NetworkDefinition network = manager.getNetwork(view.id()).orElse(null);
        if (network == null) {
            return Async.failed(new IllegalArgumentException("Network Is Unavailable"));
        }
        List<Instance> instances = Rebase.get().getInstanceManager().getAllInstances();
        String rawAction = action == null ? "" : action.trim();
        String normalized = rawAction.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "start" -> lifecycle(manager, network, instances, NetworkLifecycleOperation.START);
            case "stop" -> lifecycle(manager, network, instances, NetworkLifecycleOperation.STOP);
            case "restart" -> lifecycle(manager, network, instances, NetworkLifecycleOperation.RESTART);
            case "rolling-restart" -> lifecycle(manager, network, instances, NetworkLifecycleOperation.ROLLING_RESTART);
            case "sync", "reconcile" -> job(manager, network, instances, NetworkJobType.RECONCILE);
            case "resume", "recover" -> resumeJob(manager, network, instances);
            case "rollback" -> rollbackJob(manager, network, instances);
            case "dissolve" -> JvmAsyncBridge.fromFuture(manager.dissolveSafely(network, instances, "Server Manager")).thenApply(ignored -> null);
            case "preflight" -> JvmAsyncBridge.fromFuture(manager.runPreflight(network, instances)).thenApply(ignored -> null);
            case "rename" -> Async.failed(new UnsupportedOperationException("Network Name Is Required"));
            default -> normalized.startsWith("rename:") ? renameNetwork(manager, network, rawAction.substring("rename:".length()), instances) : Async.failed(new UnsupportedOperationException("Network Action Is Unavailable"));
        };
    }

    @Override
    public Async<Void> createNetwork(String name, String proxyId, List<String> backendIds, boolean installReSync) {
        List<Instance> instances = Rebase.get().getInstanceManager().getAllInstances();
        Instance proxy = instances.stream().filter(instance -> instance != null && instance.getInstanceId().equals(proxyId)).findFirst().orElse(null);
        List<Instance> backends = backendIds == null ? List.of() : backendIds.stream()
                .map(id -> instances.stream().filter(instance -> instance != null && instance.getInstanceId().equals(id)).findFirst().orElse(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (proxy == null) return Async.failed(new IllegalArgumentException("Proxy Server Is Unavailable"));
        return createNetwork(name, proxyId, observedPort(proxy, 25565), defaultNetworkMembers(proxy, backends, installReSync), installReSync);
    }

    @Override
    public Async<Void> validateNetworkCreationPlan(NetworkCreationPlan plan) {
        try {
            List<Instance> instances = Rebase.get().getInstanceManager().getAllInstances().stream().filter(Objects::nonNull).toList();
            Map<String, Instance> instancesById = instances.stream().collect(Collectors.toMap(Instance::getInstanceId, instance -> instance,
                (first, ignored) -> first, LinkedHashMap::new));
            Set<String> names = instances.stream()
                .map(Instance::getName)
                .filter(Objects::nonNull)
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Path> paths = new LinkedHashSet<>();
            for (NetworkCreationPlan.Server server : plan.servers()) {
                if (server.existing()) {
                    Instance existing = instancesById.get(server.existingId());
                    if (existing == null) return Async.failed(new IllegalArgumentException("Selected Server Is Unavailable"));
                    if (server.proxy() && (!isVelocityProxy(existing) || !canMutateStandalone(existing))) {
                        return Async.failed(new IllegalArgumentException("A Local Standalone Velocity Proxy Is Required"));
                    }
                    if (!server.proxy() && (existing.isProxyServer() || !canMutateStandalone(existing))) {
                        return Async.failed(new IllegalArgumentException("Only Local Standalone Backend Servers Can Join A Network"));
                    }
                    if (existing.getState() != InstanceState.STOPPED && existing.getState() != InstanceState.CRASHED) {
                        return Async.failed(new IllegalStateException("Stop Every Selected Server Before Creating The Network"));
                    }
                    continue;
                }
                Instance template = instance(server.template());
                if (template == null) return Async.failed(new IllegalArgumentException("Server Configuration Is Unavailable"));
                if (server.proxy() && !isVelocityProxy(template)) return Async.failed(new IllegalArgumentException("The Proxy Must Use Velocity"));
                if (!server.proxy() && template.isProxyServer()) return Async.failed(new IllegalArgumentException("Backends Must Use Minecraft Server Software"));
                String name = template.getName() == null ? "" : template.getName().trim();
                if (name.isBlank()) return Async.failed(new IllegalArgumentException("Every New Server Needs A Name"));
                if (!validServerName(name)) return Async.failed(new IllegalArgumentException("Server Names Cannot Contain File Path Characters"));
                if (!names.add(name.toLowerCase(Locale.ROOT))) return Async.failed(new IllegalArgumentException("Server Names Must Be Unique"));
                if (server.host() != null) continue;
                Path root = networkCreationRoot(server);
                if (!Files.isDirectory(root)) return Async.failed(new IllegalArgumentException("Server Location Is Unavailable: " + root));
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root) || !Objects.equals(target.getParent(), root)) {
                    return Async.failed(new IllegalArgumentException("Server Name Must Describe One Folder"));
                }
                if (!paths.add(target) || Files.exists(target)) {
                    return Async.failed(new IllegalArgumentException("Server Folder Already Exists: " + target.getFileName()));
                }
            }
            return Async.completed(null);
        } catch (RuntimeException error) {
            return Async.failed(error);
        }
    }

    @Override
    public Async<Object> createNetworkServer(NetworkCreationPlan.Server server) {
        if (server == null || server.existing()) return Async.failed(new IllegalArgumentException("New Server Configuration Is Required"));
        if (server.host() != null) return ServerScreenHost.super.createNetworkServer(server);
        Instance template = instance(server.template());
        if (template == null) return Async.failed(new IllegalArgumentException("Server Configuration Is Unavailable"));
        try {
            Path root = networkCreationRoot(server);
            Path target = root.resolve(template.getName()).normalize();
            if (!Files.isDirectory(root) || !target.startsWith(root) || !Objects.equals(target.getParent(), root)) {
                return Async.failed(new IllegalArgumentException("Server Location Is Unavailable"));
            }
            Files.createDirectory(target);
            template.setPath(target.toString());
            template.setManagedExternal(!root.equals(Rebase.get().getInstancesDir().toAbsolutePath().normalize()));
            return ServerScreenHost.super.createNetworkServer(server);
        } catch (IOException error) {
            return Async.failed(new IllegalStateException("Server Folder Could Not Be Reserved: " + template.getName(), error));
        } catch (RuntimeException error) {
            return Async.failed(error);
        }
    }

    @Override
    public Async<Void> createNetwork(String name, String proxyId, int entryPort, List<NetworkCreationMember> members, boolean installReSync) {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        if (manager == null) return Async.failed(new IllegalStateException("Network Manager Is Unavailable"));
        List<Instance> instances = Rebase.get().getInstanceManager().getAllInstances();
        Instance proxy = instances.stream().filter(instance -> instance != null && instance.getInstanceId().equals(proxyId)).findFirst().orElse(null);
        if (proxy == null) return Async.failed(new IllegalArgumentException("Proxy Server Is Unavailable"));
        List<NetworkCreationMember> backends = members == null ? List.of() : List.copyOf(members);
        Map<String, Instance> instancesById = instances.stream().filter(Objects::nonNull).collect(Collectors.toMap(Instance::getInstanceId, instance -> instance, (first, ignored) -> first, LinkedHashMap::new));
        if (!isVelocityProxy(proxy) || !canMutateStandalone(proxy)) {
            return Async.failed(new IllegalArgumentException("A Local Standalone Velocity Proxy Is Required"));
        }
        if (backends.isEmpty()) {
            return Async.failed(new IllegalArgumentException("Backend Server Is Required"));
        }
        if (backends.stream().anyMatch(member -> {
            Instance backend = instancesById.get(member.instanceId());
            return backend == null || backend.getInstanceId().equals(proxy.getInstanceId()) || backend.isProxyServer() || !canMutateStandalone(backend);
        })) {
            return Async.failed(new IllegalArgumentException("Only Local Standalone Backend Servers Can Join A Network"));
        }
        NetworkCreationRequest request = new NetworkCreationRequest(name, proxy.getInstanceId(), entryPort, backends, false);
        List<Instance> reSyncTargets = new ArrayList<>();
        if (installReSync) reSyncTargets.add(proxy);
        backends.stream().filter(NetworkCreationMember::resyncEnabled).map(member -> instancesById.get(member.instanceId())).filter(Objects::nonNull).forEach(reSyncTargets::add);
        Async<Void> setup = reSyncTargets.isEmpty() ? Async.completed(null) : installReSync(reSyncTargets.stream().distinct().toList());
        return setup.thenCompose(ignored -> manager.prepareCreation(request, instances, List.of()))
                .thenCompose(prepared -> manager.runPreparedCreation(prepared, instances, "Server Manager"))
                .thenCompose(job -> completeNetworkCreation(manager, instances, job));
    }

    @Override
    public Async<Void> discardCreatedServer(Object server) {
        Instance instance = instance(server);
        if (instance == null) return Async.failed(new IllegalArgumentException("Created Server Is Unavailable"));
        return JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().removeInstanceAsync(instance, true));
    }

    @Override
    public Async<Void> discardCreatedServer(NetworkCreationPlan.Server plan, Object server) {
        if (plan == null || plan.existing()) return discardCreatedServer(server);
        if (plan.host() != null) return Async.failed(new UnsupportedOperationException("Remote Server Rollback Is Unavailable"));
        Instance expected = instance(plan.template());
        Instance instance = instance(server);
        if (expected == null || instance == null || !expected.getInstanceId().equals(instance.getInstanceId())) {
            return Async.failed(new IllegalArgumentException("Created Server Identity Is Unavailable"));
        }
        Path root;
        Path target;
        Path actual;
        try {
            root = networkCreationRoot(plan);
            target = root.resolve(expected.getName()).toAbsolutePath().normalize();
            actual = Path.of(instance.getPath()).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return Async.failed(error);
        }
        if (!Files.isDirectory(root) || !target.startsWith(root) || !Objects.equals(target.getParent(), root) || !target.equals(actual)) {
            return Async.failed(new IllegalStateException("Created Server Folder Cannot Be Verified"));
        }
        if (Files.isRegularFile(target.resolve("instance.properties"))) {
            return JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().removeInstanceAsync(instance, true));
        }
        return JvmAsyncBridge.fromFuture(CompletableFuture.runAsync(() -> {
            try {
                FileUtils.deletePermanently(target);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
            Rebase.get().getInstanceManager().unregisterInstance(instance);
        }, Executors.IO));
    }

    @Override
    public Async<Void> discardPartialNetworkServer(NetworkCreationPlan.Server plan) {
        if (plan == null || plan.host() != null) return Async.completed(null);
        return discardCreatedServer(plan, plan.template());
    }

    @Override
    public Async<Void> networkServerAction(String networkId, String serverId, String action, boolean installReSync) {
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        if (manager == null) {
            return Async.failed(new IllegalStateException("Network Manager Is Unavailable"));
        }
        NetworkDefinition network = manager.getNetwork(networkId).orElse(null);
        if (network == null) {
            return Async.failed(new IllegalArgumentException("Network Is Unavailable"));
        }
        List<Instance> instances = Rebase.get().getInstanceManager().getAllInstances();
        Instance target = instances.stream().filter(instance -> instance != null && instance.getInstanceId().equals(serverId)).findFirst().orElse(null);
        if (target == null) {
            return Async.failed(new IllegalArgumentException("Server Is Unavailable"));
        }
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if ("detach".equals(normalized)) {
            return manager.detachSafely(network, target, instances, "Server Manager").thenApply(DesktopServerHost::requireSuccessfulJob);
        }
        if (!normalized.startsWith("attach")) {
            return Async.failed(new UnsupportedOperationException("Network Membership Action Is Unavailable"));
        }
        if (manager.getNetworkForInstance(target.getInstanceId()).isPresent()) {
            return Async.failed(new IllegalStateException("Server Already Belongs To A Network"));
        }
        Instance proxy = instances.stream().filter(instance -> instance != null && instance.getInstanceId().equals(network.proxyInstanceId())).findFirst().orElse(null);
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Network Proxy Is Unavailable"));
        }
        if (!isVelocityProxy(proxy) || !canMutateStandalone(proxy)) {
            return Async.failed(new IllegalStateException("Network Proxy Is Not A Local Velocity Server"));
        }
        if (target.getInstanceId().equals(proxy.getInstanceId()) || target.isProxyServer() || !canMutateStandalone(target)) {
            return Async.failed(new IllegalStateException("Only Local Standalone Backend Servers Can Join A Network"));
        }
        boolean resync = installReSync || normalized.endsWith("-resync");
        String route = uniqueRoute(network, target.getName());
        String address = NetworkHostScope.resolve(proxy).equals(NetworkHostScope.resolve(target)) ? "" : backendAddress(target);
        Async<Void> setup = resync ? installReSync(List.of(proxy, target)) : Async.completed(null);
        return setup.thenCompose(ignored -> manager.prepareAttach(network, target, route, NetworkMemberRole.GAMEPLAY, "", address, 0, 0, resync, instances, List.of()))
                .thenCompose(prepared -> manager.runPreparedAttach(prepared, instances, "Server Manager"))
                .thenApply(DesktopServerHost::requireSuccessfulJob);
    }

    private static Void requireSuccessfulJob(NetworkJob job) {
        if (job == null || job.status() != NetworkJobStatus.SUCCEEDED) {
            throw new IllegalStateException(job == null ? "Network Operation Did Not Finish" : job.message());
        }
        return null;
    }

    private Async<Void> completeNetworkCreation(DesktopNetworkManager manager, List<Instance> instances, NetworkJob job) {
        if (job != null && job.status() == NetworkJobStatus.SUCCEEDED) return Async.completed(null);
        Throwable failure = new IllegalStateException(job == null ? "Network Creation Did Not Finish" : job.message());
        if (job == null || !job.canRollback()) return Async.failed(failure);
        Async<NetworkJob> rollback;
        try {
            rollback = Objects.requireNonNull(manager.rollbackJob(job.jobId(), instances), "Network rollback result is required");
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return Async.failed(failure);
        }
        return rollback.handle((ignored, rollbackFailure) -> {
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            return null;
        }).thenCompose(ignored -> Async.failed(failure));
    }

    private Async<Void> installReSync(List<Instance> targets) {
        Async<Void> setup = Async.completed(null);
        for (Instance target : targets == null ? List.<Instance>of() : targets) {
            if (target == null) {
                continue;
            }
            setup = setup.thenCompose(ignored -> DesktopServerUiCapabilities.desktop(target).provisionReSync(target).thenApply(result -> null));
        }
        return setup;
    }

    private Path networkCreationRoot(NetworkCreationPlan.Server server) {
        String location = server == null ? "" : server.location();
        return Path.of(location == null || location.isBlank() ? defaultInstanceLocation() : location).toAbsolutePath().normalize();
    }

    private static boolean validServerName(String name) {
        return !name.equals(".") && !name.equals("..") && name.chars().noneMatch(character -> character < 32 || "<>:\"/\\|?*".indexOf(character) >= 0);
    }

    private static Async<Void> renameNetwork(DesktopNetworkManager manager, NetworkDefinition network, String requestedName, List<Instance> instances) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank()) {
            return Async.failed(new IllegalArgumentException("Network Name Is Required"));
        }
        manager.save(network.renamed(name));
        manager.reconcileInstanceBindings(instances);
        return Async.completed(null);
    }

    private static List<NetworkCreationMember> defaultNetworkMembers(Instance proxy, List<Instance> backends, boolean resyncEnabled) {
        Map<String, Integer> routes = new LinkedHashMap<>();
        int index = 0;
        List<NetworkCreationMember> members = new ArrayList<>();
        for (Instance backend : backends) {
            String baseRoute = normalizeNetworkRoute(backend.getName());
            int occurrence = routes.merge(baseRoute, 1, Integer::sum);
            String route = occurrence == 1 ? baseRoute : baseRoute + "-" + occurrence;
            String address = NetworkHostScope.resolve(proxy).equals(NetworkHostScope.resolve(backend)) ? "" : backendAddress(backend);
            NetworkMemberRole role = index++ == 0 ? NetworkMemberRole.LOBBY : NetworkMemberRole.GAMEPLAY;
            members.add(new NetworkCreationMember(backend.getInstanceId(), route, role, address, 0, 0, resyncEnabled, NetworkMemberManagement.MANAGED));
        }
        return members;
    }

    private static String uniqueRoute(NetworkDefinition network, String name) {
        String base = normalizeNetworkRoute(name);
        String route = base;
        int suffix = 2;
        while (containsRoute(network, route)) {
            route = base + "-" + suffix++;
        }
        return route;
    }

    private static boolean containsRoute(NetworkDefinition network, String route) {
        return network.members().stream().anyMatch(member -> member.routeName().equalsIgnoreCase(route));
    }

    private static String normalizeNetworkRoute(String value) {
        String route = value == null ? "server" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return route.isBlank() ? "server" : route;
    }

    private static String backendAddress(Instance instance) {
        BackendConfig config = instance == null ? null : instance.getBackendConfig();
        return config == null || config.credentials == null ? "" : config.credentials.getOrDefault("host", "");
    }

    private static int observedPort(Instance instance, int fallback) {
        try {
            int port = Integer.parseInt(instance.getServerProperties().getProperty("server-port", String.valueOf(fallback)).trim());
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static Async<Void> lifecycle(DesktopNetworkManager manager, NetworkDefinition network, List<Instance> instances,
                                         NetworkLifecycleOperation operation) {
        return JvmAsyncBridge.fromFuture(manager.runLifecycle(network, instances, operation, "Server Manager")).thenApply(ignored -> null);
    }

    private static Async<Void> job(DesktopNetworkManager manager, NetworkDefinition network, List<Instance> instances, NetworkJobType type) {
        return JvmAsyncBridge.fromFuture(manager.runJob(network, instances, List.of(), type, "Server Manager")).thenApply(ignored -> null);
    }

    private static Async<Void> resumeJob(DesktopNetworkManager manager, NetworkDefinition network, List<Instance> instances) {
        NetworkJob job = manager.getJobManager().getJobs(network.networkId()).stream().filter(NetworkJob::canResume).findFirst().orElse(null);
        return job == null ? Async.failed(new IllegalStateException("No Recoverable Network Job"))
                : JvmAsyncBridge.fromFuture(manager.resumeJob(job.jobId(), instances, List.of())).thenApply(ignored -> null);
    }

    private static Async<Void> rollbackJob(DesktopNetworkManager manager, NetworkDefinition network, List<Instance> instances) {
        NetworkJob job = manager.getJobManager().getJobs(network.networkId()).stream().filter(NetworkJob::canRollback).findFirst().orElse(null);
        return job == null ? Async.failed(new IllegalStateException("No Rollback Network Job"))
                : JvmAsyncBridge.fromFuture(manager.rollbackJob(job.jobId(), instances)).thenApply(ignored -> null);
    }

    @Override
    public Async<List<ServerScreenHost.PlanView>> reactorPlans() {
        return client.getApiClient().getPlans().thenApply(plans -> plans == null ? List.of() : plans.stream().filter(Objects::nonNull).map(plan ->
                new ServerScreenHost.PlanView(plan.id, plan.name, plan.memoryMb, plan.diskMb, plan.cpuPercent, plan.priceCents)).toList());
    }

    @Override
    public Async<List<ServerScreenHost.GroupView>> groups(String context) {
        RemotelyConfigManager config = config();
        if (config == null) {
            return Async.completed(List.of());
        }
        return Async.completed(config.getInstanceGroups(context).stream().map(group ->
                new ServerScreenHost.GroupView(group.id(), group.name(), group.members())).toList());
    }

    @Override
    public Async<Void> saveServerOrder(String context, List<String> serverIds) {
        RemotelyConfigManager config = config();
        if (config != null) {
            config.setInstanceOrder(context, serverIds == null ? List.of() : serverIds);
        }
        return Async.completed(null);
    }

    @Override
    public Async<Void> saveGroup(String context, ServerScreenHost.GroupView requested) {
        RemotelyConfigManager config = config();
        if (config == null || requested == null) {
            return Async.completed(null);
        }
        List<RemotelyGroup> groups = new ArrayList<>(config.getInstanceGroups(context));
        String id = requested.id().isBlank() ? UUID.randomUUID().toString() : requested.id();
        groups.removeIf(group -> group.id().equals(id));
        if (!requested.members().isEmpty()) {
            groups.add(new RemotelyGroup(id, requested.name(), requested.members()));
        }
        config.setInstanceGroups(context, groups);
        return Async.completed(null);
    }

    @Override
    public Async<Void> saveConfiguredGroups(String context, List<ServerScreenHost.GroupView> requested) {
        RemotelyConfigManager config = config();
        if (config == null) {
            return Async.completed(null);
        }
        List<RemotelyGroup> groups = requested == null ? List.of() : requested.stream()
                .filter(Objects::nonNull)
                .filter(group -> !group.members().isEmpty())
                .map(group -> new RemotelyGroup(group.id().isBlank() ? UUID.randomUUID().toString() : group.id(), group.name(), group.members()))
                .toList();
        config.setInstanceGroups(context, groups);
        return Async.completed(null);
    }

    @Override
    public Async<Void> serverAction(ServerModels.ClientServerView server, String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if ("hide".equals(normalized)) {
            RemotelyConfigManager config = config();
            String id = restudioIdentifier(server);
            String name = server == null || server.name == null ? "" : server.name;
            if (config != null) {
                if (!id.isBlank()) config.hideRestudioServer(id);
                if (!name.isBlank()) config.hideRestudioServer(name);
            }
            Instance instance = resolve(server);
            if (instance != null) {
                BackendConfig backend = instance.getBackendConfig();
                if (backend != null && "RESTUDIO".equalsIgnoreCase(backend.type) && config != null) {
                    config.hideRestudioServer(instance.getName());
                } else {
                    instance.setHidden(true);
                    instance.save();
                }
            }
            return Async.completed(null);
        }
        Instance instance = resolve(server);
        if (instance == null) {
            return Async.failed(new IllegalStateException("Server Is Unavailable"));
        }
        InstanceManager instances = Rebase.get().getInstanceManager();
        if (("duplicate".equals(normalized) || "trash".equals(normalized) || "delete".equals(normalized))
                && !canMutateStandalone(instance)) {
            return Async.failed(new UnsupportedOperationException("Server Ownership Does Not Allow This Action"));
        }
        return switch (normalized) {
            case "duplicate" -> {
                String name = instance.getName() == null || instance.getName().isBlank() ? "Server" : instance.getName();
                instances.duplicateInstance(instance, name + " Copy");
                yield Async.completed(null);
            }
            case "trash" -> JvmAsyncBridge.fromFuture(instances.removeInstanceAsync(instance, false));
            case "delete" -> JvmAsyncBridge.fromFuture(instances.removeInstanceAsync(instance, true));
            case "install-resync" -> ((DesktopServerUiCapabilities) capabilities(server)).provisionReSync(instance).thenApply(ignored -> null);
            default -> Async.failed(new UnsupportedOperationException("Server Action Is Unavailable"));
        };
    }

    @Override
    public Async<Void> customizeIcon(ServerModels.ClientServerView server, ServerScreenHost.HostView host, Identifier icon, Runnable onComplete) {
        Instance instance = resolve(server);
        if (instance == null || icon == null) {
            return Async.failed(new IllegalArgumentException("Server Icon Is Unavailable"));
        }
        RemoteHost remoteHost = findHost(host);
        return new ServerIconManager(iconProvider).customizeIcon(instance, remoteHost, icon, onComplete);
    }

    @Override
    public void openIconCustomizer(Screen current, ServerModels.ClientServerView server, ServerScreenHost.HostView host, Runnable onComplete) {
        if (current == null) {
            unavailable(Action.CUSTOMIZE_ICON);
            return;
        }
        ServerIconManager iconManager = new ServerIconManager(iconProvider);
        List<Identifier> images = iconManager.loadIconAssetIds();
        if (images.isEmpty()) {
            application().notify("Icon Customizer", "Icon Assets Are Unavailable", ReSyncNotificationLevel.WARN);
            return;
        }
        List<Integer> tints = iconManager.loadIconTints();
        IconCustomizerWidget popup = new IconCustomizerWidget("Icon Customizer", images, tints, icon ->
                customizeIcon(server, host, icon, onComplete));
        current.addDrawableChild(popup);
        popup.show();
    }

    @Override
    public Async<Void> hostAction(ServerScreenHost.HostView view, String action) {
        RemoteHost remoteHost = findHost(view);
        if (remoteHost == null) {
            return Async.failed(new IllegalStateException("Remote Host Is Unavailable"));
        }
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if ("delete".equals(normalized)) {
            Rebase.get().getInstanceManager().removeRemoteHost(remoteHost);
            return Async.completed(null);
        }
        if ("refresh".equals(normalized)) {
            return JvmAsyncBridge.fromFuture(Rebase.get().getInstanceManager().fetchRemoteInstances(remoteHost));
        }
        if ("connect".equals(normalized) || "test".equals(normalized)) {
            return testRemoteHost(remoteHost);
        }
        return Async.failed(new UnsupportedOperationException("Host Action Is Unavailable"));
    }

    @Override
    public Async<ServerScreenHost.HostView> testAndSaveRemoteHost(ServerScreenHost.RemoteHostDraft draft) {
        if (draft == null || draft.id() != null && !draft.id().isBlank()) {
            return saveRemoteHost(draft);
        }
        if (draft.name().isBlank() || draft.address().isBlank()) {
            return Async.failed(new IllegalArgumentException("Host Name And Address Are Required"));
        }
        try {
            InstanceManager instances = Rebase.get().getInstanceManager();
            RemoteHost remoteHost = new RemoteHost();
            applyRemoteHostDraft(remoteHost, draft, true);
            return testRemoteHost(remoteHost).thenApply(ignored -> {
                remoteHost.commitTemporaryCredentials();
                instances.addRemoteHost(remoteHost);
                return toHostView(remoteHost);
            });
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    @Override
    public Async<ServerScreenHost.HostView> saveRemoteHost(ServerScreenHost.RemoteHostDraft draft) {
        if (draft == null || draft.name().isBlank() || draft.address().isBlank()) {
            return Async.failed(new IllegalArgumentException("Host Name And Address Are Required"));
        }
        try {
            InstanceManager instances = Rebase.get().getInstanceManager();
            RemoteHost remoteHost = draft.id().isBlank() ? new RemoteHost() : findHostById(instances, draft.id());
            if (remoteHost == null) {
                remoteHost = new RemoteHost();
            }
            applyRemoteHostDraft(remoteHost, draft, false);
            if (draft.id().isBlank()) {
                instances.addRemoteHost(remoteHost);
            } else {
                instances.updateRemoteHost(remoteHost);
            }
            return Async.completed(toHostView(remoteHost));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    private static void applyRemoteHostDraft(RemoteHost remoteHost, ServerScreenHost.RemoteHostDraft draft, boolean temporaryCredentials) {
        remoteHost.name = draft.name();
        remoteHost.setIp(draft.address());
        remoteHost.setType(draft.type());
        remoteHost.setUser(draft.user());
        remoteHost.setPort(draft.port() <= 0 ? (remoteHost.isPanelHost() ? 443 : 22) : draft.port());
        remoteHost.setAuthMode(draft.authMode());
        if (remoteHost.isPanelHost()) {
            if (draft.apiKey().isBlank() && (remoteHost.getApiKey() == null || remoteHost.getApiKey().isBlank())) {
                throw new IllegalArgumentException("API Key Cannot Be Empty");
            }
            if (!draft.apiKey().isBlank()) {
                if (temporaryCredentials) remoteHost.setTemporaryApiKey(draft.apiKey());
                else remoteHost.setApiKey(draft.apiKey());
            }
            if (!draft.password().isBlank()) {
                if (temporaryCredentials) remoteHost.setTemporaryPassword(draft.password());
                else remoteHost.setPassword(draft.password());
            }
            remoteHost.setKeyPath("");
            remoteHost.setInstanceRegistryPath(null);
            return;
        }
        if (!draft.password().isBlank()) {
            if (temporaryCredentials) remoteHost.setTemporaryPassword(draft.password());
            else remoteHost.setPassword(draft.password());
        }
        remoteHost.setKeyPath(draft.keyPath());
        if (!draft.keyPassphrase().isBlank()) {
            if (temporaryCredentials) remoteHost.setTemporaryKeyPassphrase(draft.keyPassphrase());
            else remoteHost.setKeyPassphrase(draft.keyPassphrase());
        }
        remoteHost.setInstanceRegistryPath(draft.registryPath());
    }

    private Async<Void> testRemoteHost(RemoteHost remoteHost) {
        if (remoteHost.isPanelHost()) {
            return client.getPanelServerProvider().listServers(remoteHost).thenApply(ignored -> null);
        }
        return JvmAsyncBridge.fromFuture(CompletableFuture.runAsync(() -> {
            try {
                if (!remoteHost.getSshManager().connect()) {
                    throw new IllegalStateException("Remote Host Connection Failed");
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Remote Host Connection Failed", exception);
            }
        }));
    }

    @Override
    public void openImportExplorer(Screen current, ServerScreenHost.HostView view, ServerModels.ClientServerView server) {
        RemoteHost remoteHost = findHost(view);
        if (remoteHost == null) {
            openLocalImportExplorer(current);
            return;
        }
        if (remoteHost.isPanelHost()) {
            Instance selected = resolveRemoteInstance(remoteHost, server);
            if (selected == null) {
                application().notify("Import Server", "Select A Server First", ReSyncNotificationLevel.WARN);
                return;
            }
            openRemoteImportExplorer(current, remoteHost, selected);
            return;
        }
        openRemoteImportExplorer(current, remoteHost, null);
    }

    @Override
    public Async<Void> setServerPower(RemotelyServerApi api, ServerModels.ClientServerView server, String signal) {
        Instance instance = resolve(server);
        if (instance == null) {
            return ServerScreenHost.super.setServerPower(api, server, signal);
        }
        String normalized = signal == null ? "" : signal.trim().toLowerCase(Locale.ROOT);
        ServerUiCapabilityProvider provider = capabilities(server);
        return switch (normalized) {
            case "start" -> provider.setPower(server, "start");
            case "stop" -> provider.setPower(server, "stop");
            case "kill" -> provider.setPower(server, "kill");
            case "restart" -> provider.setPower(server, "stop").thenCompose(ignored -> provider.setPower(server, "start"));
            default -> Async.failed(new IllegalArgumentException("Unknown server power signal: " + signal));
        };
    }

    @Override
    public Async<Void> repairServer(ServerModels.ClientServerView server, String repair) {
        Instance instance = resolve(server);
        if (instance == null) {
            return Async.failed(new IllegalStateException("Server Is Unavailable"));
        }
        String normalized = repair == null ? "" : repair.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "eula" -> {
                String previous = instance.getServerProperties().getProperty("eula");
                instance.getServerProperties().setProperty("eula", "true");
                yield JvmAsyncBridge.fromFuture(instance.saveServerProperties()).exceptionallyCompose(failure -> {
                    if (previous == null) instance.getServerProperties().remove("eula");
                    else instance.getServerProperties().setProperty("eula", previous);
                    return Async.<Void>failed(failure);
                });
            }
            case "server-jar" -> {
                Notification notification = new Notification.Builder().message("Downloading Server Jar...").type(Notification.Type.INFO).loading(true).build();
                yield JvmAsyncBridge.fromFuture(new InstanceFactory().downloadMissingServerJar(instance, notification));
            }
            case "start-script" -> JvmAsyncBridge.fromFuture(InstanceRepairer.createStartScript(instance));
            default -> Async.failed(new IllegalArgumentException("Unknown Server Repair: " + repair));
        };
    }

    @Override
    public Async<ServerScreenHost.ServerHealth> serverHealth(ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance == null) {
            return Async.failed(new IllegalStateException("Server Is Unavailable"));
        }
        return JvmAsyncBridge.fromFuture(InstanceApi.of(instance).health().check()).thenApply(status ->
                new ServerScreenHost.ServerHealth(status.eulaAccepted(), status.hasServerJar(),
                        status.hasStartScript(), status.isHealthy()));
    }

    @Override
    public TerminalSessionProvider terminalProvider(RemotelyServerApi api, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        return instance == null ? null : new DesktopTerminalSessionProvider(instance);
    }

    @Override
    public ResourceContainerAdapter createResourceContainer(ReScreen host, Object value, ServerModels.ClientServerView server,
                                                            int x, int y, int width, int height) {
        if (!(value instanceof Instance instance) || !instance.isServer()) {
            return null;
        }
        ResourceContainer container = new ResourceContainer(host, new DesktopResourceContainerProvider(instance), x, y, width, height,
                InstanceResourceWidget::new, true);
        return new CanonicalResourceContainerAdapter(container);
    }

    @Override
    public StandardOutputStateParser createStandardOutputParser(Object value) {
        return value instanceof Instance instance ? new JvmStandardOutputStateParser(instance) : null;
    }

    @Override
    public TerminalWidget createTerminal(RemotelyServerApi api, ServerModels.ClientServerView server, String id,
        int x, int y, int width, int height, TerminalSessionProvider provider) {
        Instance instance = resolve(server);
        TerminalSessionProvider resolved = provider == null && instance != null ? new DesktopTerminalSessionProvider(instance) : provider;
        String cacheId = instance == null || instance.getInstanceId() == null || instance.getInstanceId().isBlank()
                ? id : instance.getInstanceId();
        return ServerTerminal.getOrCreate(cacheId, this, api, server, x, y, width, height, resolved);
    }

    @Override
    public ServerTerminalPlatform terminalPlatform(RemotelyServerApi api, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        return instance == null ? ServerTerminalPlatform.NONE : new DesktopServerTerminalPlatform(instance);
    }

    @Override
    public TerminalWidget createLocalTerminal(String id, int x, int y, int width, int height) {
        ExecutionProvider execution = new LocalBackend(new BackendConfig("LOCAL", new LinkedHashMap<>()), null).getExecution();
        return TerminalWidget.getOrCreate(execution, id, x, y, width, height, null);
    }

    @Override
    public void shutdownTerminal(String id) {
        ServerTerminal.shutdown(id);
        TerminalWidget.shutdownLocal(id);
    }

    @Override
    public boolean terminalStartsServerProcess(RemotelyServerApi api, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().type == null) {
            return false;
        }
        return !"LOCAL".equalsIgnoreCase(instance.getBackendConfig().type);
    }

    @Override
    public boolean terminalRestartsOnCrash(RemotelyServerApi api, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        String type = instance == null || instance.getBackendConfig() == null ? "LOCAL" : instance.getBackendConfig().type;
        return instance != null && (type == null || type.isBlank() || "LOCAL".equalsIgnoreCase(type)) && instance.isLocalRestartOnCrash();
    }

    @Override
    public ServerScreenHost.TerminalDataStream terminalDataStream(RemotelyServerApi api, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance == null) {
            return null;
        }
        Optional<ServerUiCapabilityProvider.DataStream> stream = DesktopServerUiCapabilities.desktop().dataStream(instance);
        return stream.map(value -> new ServerScreenHost.TerminalDataStream() {
            @Override
            public Async<Void> stream(BiConsumer<Integer, String> onLine) {
                String root = instance.getPath() == null ? "" : instance.getPath().replace('\\', '/');
                String prefix = root.isBlank() || root.endsWith("/") ? root : root + "/";
                List<String> preLoadFiles = List.of("ops.json", "banned-players.json", "banned-ips.json", "whitelist.json", "usercache.json")
                        .stream().map(file -> prefix + file).toList();
                PlayerManagerController controller = PlayerManagerController.getOrCreate(instance, capabilities(server));
                StreamDataParser parser = new StreamDataParser(controller);
                BiConsumer<Integer, String> listener = (lineNumber, line) -> {
                    parser.accept(lineNumber, line);
                    if (onLine != null && !isStreamControlLine(line)) {
                        onLine.accept(lineNumber, line);
                    }
                };
                return JvmAsyncBridge.fromFuture(value.streamData(prefix + "logs/latest.log", preLoadFiles, listener));
            }

            @Override
            public void stop() {
                value.stopStream();
            }
        }).orElse(null);
    }

    @Override
    public Async<String> retainedTerminalOutput(RemotelyServerApi api, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance == null || instance.getPath() == null) {
            return Async.completed("");
        }
        String type = instance.getBackendConfig() == null ? "LOCAL" : instance.getBackendConfig().type;
        if (type != null && !type.isBlank() && !"LOCAL".equalsIgnoreCase(type)) {
            return Async.completed("");
        }
        return JvmAsyncBridge.fromFuture(CompletableFuture.supplyAsync(() -> {
            try {
                LocalServerControllerModels.EventsResponse response = LocalServerControllerClient.events(instance, 0, 0);
                StringBuilder output = new StringBuilder();
                if (response != null && response.events != null) {
                    response.events.forEach(event -> {
                        if (event != null && event.text != null) {
                            output.append(event.text);
                        }
                    });
                }
                if (output.isEmpty() && response != null && response.lastError != null && !response.lastError.isBlank()) {
                    output.append(response.lastError).append(System.lineSeparator());
                }
                return output.toString();
            } catch (Exception ignored) {
                return "";
            }
        }));
    }

    @Override
    public void configureTerminalInput(RemotelyServerApi api, ServerModels.ClientServerView server, TerminalWidget terminal) {
        if (terminal == null) return;
        Instance instance = resolve(server);
        String backendType = terminalBackendType(server, instance);
        switch (terminalInputRoute(backendType)) {
            case DIRECT -> {
                terminal.disableFakeInput();
                return;
            }
            case BACKEND_API -> {
                if (instance == null || instance.getBackend() == null || instance.getBackend().getExecution() == null) {
                    terminal.disableFakeInput();
                    return;
                }
            }
        }
        ExecutionProvider execution = instance.getBackend().getExecution();
        terminal.enableFakeInput("> ", command -> execution.sendCommand(command).whenComplete((ignored, failure) -> {
            if (failure == null) return;
            application().execute(() -> application().notify("Command Failed", failure.getMessage() == null ? "Command Failed" : failure.getMessage(),
                    ReSyncNotificationLevel.ERROR));
        }));
    }

    static String terminalBackendType(ServerModels.ClientServerView server, Instance instance) {
        if (instance != null && instance.getBackendConfig() != null && instance.getBackendConfig().type != null
                && !instance.getBackendConfig().type.isBlank()) {
            return instance.getBackendConfig().type;
        }
        return server == null || server.backendType == null || server.backendType.isBlank() ? "LOCAL" : server.backendType;
    }

    static TerminalInputRoute terminalInputRoute(String backendType) {
        if (isPanelType(backendType)) return TerminalInputRoute.BACKEND_API;
        return TerminalInputRoute.DIRECT;
    }

    enum TerminalInputRoute {
        DIRECT,
        BACKEND_API
    }

    @Override
    public void recordTerminalNotice(RemotelyServerApi api, ServerModels.ClientServerView server, String line) {
        Instance instance = resolve(server);
        if (instance != null && line != null && !line.isBlank()) {
            instance.getLogger().addLog(line);
        }
    }

    @Override
    public Async<ServerModels.ServerStatus> serverStatus(RemotelyServerApi api, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance == null) {
            return ServerScreenHost.super.serverStatus(api, server);
        }
        String backendType = instance.getBackendConfig() == null ? "LOCAL" : instance.getBackendConfig().type;
        if (backendType == null || backendType.isBlank() || "LOCAL".equalsIgnoreCase(backendType)) {
            return JvmAsyncBridge.fromFuture(CompletableFuture.supplyAsync(() -> {
                LocalServerControllerModels.StatusResponse observed = LocalServerControllerClient.status(instance);
                ServerModels.ServerStatus status = new ServerModels.ServerStatus();
                String fallback = instance.getState() == null ? "offline" : instance.getState().name().toLowerCase(Locale.ROOT);
                status.currentState = observed == null ? fallback : !observed.knownSession
                        ? "starting".equalsIgnoreCase(fallback) ? fallback : "offline"
                        : observed.state == null || observed.state.isBlank() ? "offline" : observed.state.toLowerCase(Locale.ROOT);
                return status;
            }));
        }
        if ("RESTUDIO".equalsIgnoreCase(backendType)) {
            String identifier = restudioIdentifier(instance);
            ReStudio studio = ReStudio.getInstance();
            if (identifier.isBlank() || studio == null || !studio.isAuthenticated()) {
                return Async.completed(statusFromInstance(instance));
            }
            return JvmAsyncBridge.fromFuture(studio.getApi().getServerStatus(identifier))
                    .thenApply(observed -> observed == null ? statusFromInstance(instance) : observed)
                    .exceptionally(ignored -> statusFromInstance(instance));
        }
        if (instance.getBackend() != null && instance.getBackend().getExecution() != null) {
            return JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().getStatus())
                    .thenApply(observed -> {
                        ServerModels.ServerStatus status = new ServerModels.ServerStatus();
                        status.currentState = observed == null || observed.state() == null ? "offline" : observed.state().name().toLowerCase(Locale.ROOT);
                        status.installing = "installing".equalsIgnoreCase(status.currentState);
                        return status;
                    })
                    .exceptionally(ignored -> statusFromInstance(instance));
        }
        return Async.completed(statusFromInstance(instance));
    }

    @Override
    public Async<Void> sendServerCommand(RemotelyServerApi api, ServerModels.ClientServerView server, String command) {
        Instance instance = resolve(server);
        if (instance == null || instance.getBackend() == null || instance.getBackend().getExecution() == null) {
            return ServerScreenHost.super.sendServerCommand(api, server, command);
        }
        return capabilities(server).sendCommand(server, command);
    }

    private static ServerModels.ServerStatus statusFromInstance(Instance instance) {
        ServerModels.ServerStatus status = new ServerModels.ServerStatus();
        status.currentState = instance == null || instance.getState() == null ? "offline" : instance.getState().name().toLowerCase(Locale.ROOT);
        status.installing = "installing".equalsIgnoreCase(status.currentState);
        return status;
    }

    private static void updateRestudioCredentials(Instance instance, Map<String, String> credentials) {
        if (instance == null || credentials == null) return;
        BackendConfig config = instance.getBackendConfig();
        if (config == null || !"RESTUDIO".equalsIgnoreCase(config.type)) {
            instance.setBackendConfig(new BackendConfig("RESTUDIO", new LinkedHashMap<>(credentials)));
            return;
        }
        config.type = "RESTUDIO";
        if (config.credentials == null) config.credentials = new LinkedHashMap<>();
        credentials.forEach((key, value) -> {
            if (!"password".equals(key) || value != null && !value.isBlank() || !config.credentials.containsKey(key)) {
                config.credentials.put(key, value);
            }
        });
    }

    private static void updateRestudioCredential(Instance instance, String key, String value) {
        BackendConfig config = instance == null ? null : instance.getBackendConfig();
        if (config == null || config.credentials == null || key == null || key.isBlank()) return;
        config.credentials.put(key, value == null ? "" : value);
    }

    private static void applyRestudioObservedState(Instance instance, String value, boolean suspended, boolean installing) {
        if (instance == null) return;
        if (suspended) {
            instance.setState(InstanceState.STOPPED);
            return;
        }
        if (installing) {
            if (!LifecycleManager.isStopPending(instance)) instance.setState(InstanceState.INSTALLING);
            return;
        }
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        InstanceState observed = switch (normalized) {
            case "running" -> InstanceState.RUNNING;
            case "starting" -> InstanceState.STARTING;
            case "stopping" -> InstanceState.STOPPING;
            case "offline", "stopped" -> InstanceState.STOPPED;
            case "crashed" -> InstanceState.CRASHED;
            default -> null;
        };
        if (observed == null) return;
        if (observed == InstanceState.STOPPED && LifecycleManager.isStartPending(instance)) {
            instance.setState(InstanceState.STARTING);
            return;
        }
        if ((observed == InstanceState.RUNNING || observed == InstanceState.STARTING) && LifecycleManager.isStopPending(instance)) {
            instance.setState(InstanceState.STOPPING);
            return;
        }
        instance.setState(observed);
    }

    private static boolean isPanelType(String type) {
        return "PTERO".equalsIgnoreCase(type) || "CALAGOPUS".equalsIgnoreCase(type);
    }

    private static boolean isStreamControlLine(String line) {
        if (line == null) {
            return true;
        }
        String value = line.trim();
        return value.startsWith("[FILE_START:") || value.startsWith("[FILE_END:")
                || value.equals("[REMOTELY_PLAYER_BASELINE_START]") || value.equals("[REMOTELY_PLAYER_BASELINE_END]");
    }

    @Override
    public void openGlobalTerminal(Screen current) {
        client.openMultiTerminal(current);
    }

    @Override
    public void openGlobalFileExplorer(Screen current) {
        application().setScreen(new FileExplorerScreen(current, null, DesktopRemotelyPaths.appDir(),
                DesktopRemotelyPaths.appDir(), false) {
            @Override
            public String getDesktopAppId() {
                return "file-explorer";
            }

            @Override
            public String getDesktopAppTitle() {
                return "File Explorer";
            }

            @Override
            public String getDesktopAppIconPath() {
                return "explorer.png";
            }
        });
    }

    @Override
    public void openFileExplorer(Screen current, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance == null) {
            application().openRemoteFiles(current, server);
            return;
        }
        client.openInstanceFiles(current, instance);
    }

    public void openFileExplorer(Screen current, Instance instance) {
        if (instance == null) {
            unavailable(Action.FILE_EXPLORER);
            return;
        }
        client.openInstanceFiles(current, instance);
    }

    @Override
    public void openServerConfiguration(Screen current, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance != null) {
            application().setScreen(new ServerConfigurationScreen(current, instance, findHost(instance), client));
            return;
        }
        unavailable(Action.SERVER_CONFIGURATION);
    }

    public void openServerConfiguration(Screen current, Instance instance) {
        if (instance == null) {
            unavailable(Action.SERVER_CONFIGURATION);
            return;
        }
        application().setScreen(new ServerConfigurationScreen(current, instance, findHost(instance), client));
    }

    @Override
    public void openDevelopment(Screen current, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance != null) {
            if (mergeServerScreen(current, server, true)) {
                return;
            }
            unavailable(Action.DEVELOPMENT);
            return;
        }
        unavailable(Action.DEVELOPMENT);
    }

    @Override
    public void openReSyncStudio(Screen current, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance != null || isReStudioTarget(server)) {
            client.openReSyncStudio(current, instance, server);
            return;
        }
        unavailable(Action.RESYNC_STUDIO);
    }

    @Override
    public void openNetworkSettings(Screen current, String networkId) {
        application().setScreen(new NetworkOverviewScreen(current, networkOverviewProvider(client), networkId));
    }

    @Override
    public void openWorld(Screen current, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (instance == null) {
            unavailable(Action.WORLD);
            return;
        }
        application().setScreen(new WorldMapScreen(current, instance));
    }

    @Override
    public void duplicateServer(Screen current, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (!canMutateStandalone(instance)) {
            unavailable(Action.DUPLICATE_SERVER);
            return;
        }
        String name = instance.getName() == null || instance.getName().isBlank() ? "Server" : instance.getName();
        Instance duplicate = Rebase.get().getInstanceManager().duplicateInstance(instance, name + " Copy");
        if (duplicate == null) {
            unavailable(Action.DUPLICATE_SERVER);
        }
    }

    @Override
    public void deleteServer(Screen current, ServerModels.ClientServerView server) {
        Instance instance = resolve(server);
        if (!canMutateStandalone(instance)) {
            unavailable(Action.DELETE_SERVER);
            return;
        }
        Rebase.get().getInstanceManager().removeInstanceAsync(instance).whenComplete((ignored, failure) -> application().execute(() -> {
            if (failure != null) {
                application().notify("Delete Server", failure.getMessage(), ReSyncNotificationLevel.ERROR);
                return;
            }
            application().notify("Delete Server", "Server Deleted", ReSyncNotificationLevel.SUCCESS);
            client.openServerManager(current);
        }));
    }

    private boolean canMutateStandalone(Instance instance) {
        if (instance == null || instance.getState() == InstanceState.INSTALLING || !isLocalInstance(instance)) {
            return false;
        }
        BackendConfig config = instance.getBackendConfig();
        if (config != null && ("RESTUDIO".equalsIgnoreCase(config.type) || PteroBackend.isPanelType(config.type))) {
            return false;
        }
        DesktopNetworkManager manager = DesktopNetworkAccess.manager(client);
        return manager == null ? client.getNetworkManager() == null : manager.getNetworkForInstance(instance.getInstanceId()).isEmpty();
    }

    private static boolean isVelocityProxy(Instance instance) {
        return instance != null && instance.isProxyServer()
                && (instance.getModLoader() == ModLoader.VELOCITY
                || instance.getServerSoftwareCompatibility().stream().anyMatch(value -> "velocity".equalsIgnoreCase(value)));
    }

    @Override
    public void openRemoteHost(Screen current) {
        if (current instanceof ServerManagerScreen manager) {
            manager.openRemoteHostEditor();
            return;
        }
        unavailable(Action.REMOTE_HOST);
    }

    @Override
    public void createServer(Screen current) {
        createServer(current, null);
    }

    @Override
    public void createServer(Screen current, ServerScreenHost.HostView remoteHost) {
        application().setScreen(new ServerConfigurationScreen(current, remoteHost, client, null, null));
    }

    @Override
    public void createServer(Screen current, ServerScreenHost.HostView remoteHost, Object preset, Consumer<Object> creationCallback) {
        Object resolvedPreset = preset instanceof String value ? creationPreset(value) : preset;
        application().setScreen(new ServerConfigurationScreen(current, remoteHost, client, resolvedPreset, creationCallback));
    }

    @Override
    public boolean openServerPath(Screen current, String path) {
        if (path == null || path.isBlank()) return false;
        try {
            InstanceManager instances = Rebase.get().getInstanceManager();
            List<Instance> all = new ArrayList<>(instances.getLocalInstances());
            for (RemoteHost host : instances.getRemoteHosts()) {
                all.addAll(instances.getRemoteInstances(host));
            }
            Instance selected = all.stream().filter(Objects::nonNull).filter(instance -> Objects.equals(instance.getPath(), path)).findFirst().orElse(null);
            if (selected == null) return false;
            client.openInstanceInTerminal(current, selected);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public void importServer(Screen current) {
        application().setScreen(new ServerConfigurationScreen(current, null, client, null, null));
    }

    @Override
    public boolean supports(Action action) {
        return application().supportsDesktopIntegrations() || action == Action.GLOBAL_TERMINAL;
    }

    @Override
    public void openInbox(Screen current) {
        application().setScreen(new InboxScreen(current));
    }

    @Override
    public void openReports(Screen current) {
        application().setScreen(new FeedbackBrowserScreen(current, "Remotely"));
    }

    @Override
    public void signIn(Screen current) {
        application().setScreen(new ReStudioLoginScreen(current, () -> application().setScreen(current)));
    }

    @Override
    public void signOut(Screen current) {
        if (ReStudio.getInstance() != null) {
            ReStudio.getInstance().logoutFromWorkOs();
        }
    }

    @Override
    public void openSettings(Screen current) {
         if (application().getCurrentScreen() instanceof ReScreen parentScreen && config() != null) {
            application().setScreen(DesktopSettingsScreenFactory.createGlobalSettingsScreen(parentScreen, config()));
            return;
        }
        unavailable(Action.HOST_SETTINGS);
    }

    @Override
    public void openModpackBrowser(Screen current) {
        openModpackBrowser(current, (RemoteHost) null, false);
    }

    public void openModpackBrowser(Screen current, RemoteHost remoteHost, boolean reStudioContext) {
        application().setScreen(JvmResourceMarketplaceAdapter.browser(current, null, ResourceType.MODPACK, true, remoteHost, reStudioContext));
    }

    @Override
    public void openReactorPlan(Screen current, ServerScreenHost.PlanView plan) {
        if (plan == null || plan.name().isBlank()) {
            openReactorPlans(current);
            return;
        }
        application().setScreen(new ServerConfigurationScreen(current, null, null, client, true, plan.name()));
    }

    @Override
    public void openReactorPlans(Screen current) {
        BrowserUtils.openBrowser("https://restudiomc.net/hosting");
    }

    @Override
    public void openPanel(Screen current, ServerScreenHost.HostView view) {
        if (view == null || view.address().isBlank()) {
            unavailable(Action.OPEN_PANEL);
            return;
        }
        BrowserUtils.openBrowser(view.address());
    }

    private void openLocalImportExplorer(Screen current) {
        application().setScreen(new FileExplorerScreen(current, null, DesktopRemotelyPaths.appDir(),
                DesktopRemotelyPaths.appDir(), true) {
            @Override
            public String getDesktopAppId() {
                return "file-explorer";
            }

            @Override
            public String getDesktopAppTitle() {
                return "Import Server";
            }

            @Override
            public String getDesktopAppIconPath() {
                return "explorer.png";
            }
        });
    }

    private void openRemoteImportExplorer(Screen current, RemoteHost host, Instance selected) {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("hostId", host.hostId);
        credentials.put("host", host.getIp());
        String type = host.getType().toUpperCase(Locale.ROOT);
        RemotePath start = RemotePath.root();
        if (host.isPanelHost()) {
            credentials.put("apiUrl", host.getIp());
            if (selected != null && selected.getBackendConfig() != null && selected.getBackendConfig().credentials != null) {
                credentials.putAll(selected.getBackendConfig().credentials);
            }
            credentials.put("hostId", host.hostId);
            credentials.put("host", host.getIp());
            start = RemotePath.of(selected == null || selected.getPath() == null ? "/" : selected.getPath());
        } else {
            credentials.put("port", String.valueOf(host.getPort()));
            credentials.put("user", host.getUser());
            credentials.put("authMode", host.getAuthMode());
            if (host.getPassword() != null && !host.getPassword().isBlank()) {
                credentials.put("password", host.getPassword());
            }
            if (host.getKeyPath() != null && !host.getKeyPath().isBlank()) {
                credentials.put("keyPath", host.getKeyPath());
            }
            if (host.getInstanceRegistryPath() != null && !host.getInstanceRegistryPath().isBlank()) {
                credentials.put("registryPath", host.getInstanceRegistryPath());
            }
            type = "SSH";
        }
        RemoteFileSystemProvider provider = FileExplorerProviders.resolve(type, credentials, selected == null ? "" : selected.getInstanceId());
        String home = provider.getMetadata("homeDir");
        if (home != null && !home.isBlank() && selected == null) {
            start = RemotePath.of(home);
        }
        application().setScreen(new FileExplorerScreen(current, null, start, RemotePath.of(DesktopRemotelyPaths.appDir().toString()), true, provider) {
            @Override
            public String getDesktopAppId() {
                return "file-explorer";
            }

            @Override
            public String getDesktopAppTitle() {
                return "Import Server";
            }

            @Override
            public String getDesktopAppIconPath() {
                return "explorer.png";
            }
        });
    }

    private static Instance resolveRemoteInstance(RemoteHost host, ServerModels.ClientServerView server) {
        if (host == null) {
            return null;
        }
        List<Instance> instances = Rebase.get().getInstanceManager().getRemoteInstances(host);
        if (server != null) {
            Instance selected = instances.stream().filter(instance -> Objects.equals(serverId(server), instance.getInstanceId())).findFirst().orElse(null);
            if (selected != null) {
                return selected;
            }
        }
        return instances.isEmpty() ? null : instances.getFirst();
    }

    private static String serverId(ServerModels.ClientServerView server) {
        if (server == null) {
            return "";
        }
        return server.identifier == null || server.identifier.isBlank() ? server.uuid : server.identifier;
    }

    private static Instance instance(Object value) {
        return value instanceof Instance result ? result : null;
    }

    private static ServerScreenHost.ServerState parseState(InstanceState state) {
        return state == null ? ServerScreenHost.ServerState.UNKNOWN : switch (state) {
            case STOPPED, SAVED -> ServerScreenHost.ServerState.STOPPED;
            case STARTING -> ServerScreenHost.ServerState.STARTING;
            case RUNNING -> ServerScreenHost.ServerState.RUNNING;
            case STOPPING, SAVING -> ServerScreenHost.ServerState.STOPPING;
            case CRASHED -> ServerScreenHost.ServerState.CRASHED;
            case INSTALLING -> ServerScreenHost.ServerState.INSTALLING;
        };
    }

    private static boolean isLocalInstance(Instance instance) {
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().type == null) return true;
        String type = instance.getBackendConfig().type;
        return type.isBlank() || "LOCAL".equalsIgnoreCase(type);
    }

    private static ServerScreenHost.HostView toHostView(RemoteHost host) {
        return new ServerScreenHost.HostView(host.hostId, host.name, host.getType(), host.getIp(), host.getPort(),
                host.isPanelHost() || host.getExistingSshManager() != null && host.getExistingSshManager().isConnected(), host.isPanelHost(),
                host.getUser(), host.getAuthMode(), host.getKeyPath(), host.getInstanceRegistryPath(),
                host.getPassword(), host.getApiKey(), host.getKeyPassphrase());
    }

    private static RemoteHost findHost(ServerScreenHost.HostView view) {
        if (view == null) {
            return null;
        }
        try {
            return Rebase.get().getInstanceManager().getRemoteHosts().stream().filter(Objects::nonNull)
                    .filter(host -> Objects.equals(view.id(), host.hostId) || Objects.equals(view.name(), host.name)).findFirst().orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static RemotelyConfigManager config() {
        try {
            return Rebase.get().getConfigManager() instanceof RemotelyConfigManager config ? config : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private RemotelyConfigStore recentConfig() {
        RemotelyConfigStore configured = client.getComposition().configManager();
        return configured == null ? config() : configured;
    }

    private static RemoteHost findHostById(InstanceManager instances, String id) {
        if (instances == null || id == null || id.isBlank()) {
            return null;
        }
        return instances.getRemoteHosts().stream().filter(Objects::nonNull).filter(host -> Objects.equals(id, host.hostId)).findFirst().orElse(null);
    }

    private static RemoteHost findHost(Instance instance) {
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().credentials == null) {
            return null;
        }
        Map<String, String> credentials = instance.getBackendConfig().credentials;
        try {
            InstanceManager instances = Rebase.get().getInstanceManager();
            String hostId = credentials.getOrDefault("hostId", "");
            if (!hostId.isBlank()) {
                RemoteHost byId = findHostById(instances, hostId);
                if (byId != null) return byId;
            }
            String address = credentials.getOrDefault("host", "");
            return instances.getRemoteHosts().stream().filter(Objects::nonNull)
                    .filter(host -> Objects.equals(address, host.getIp())).findFirst().orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Instance resolve(ServerModels.ClientServerView server) {
        if (server == null) {
            return null;
        }
        String identifier = restudioIdentifier(server);
        Instance transientInstance = restudioBridgeInstances.get(identifier);
        if (transientInstance != null) return transientInstance;
        try {
            InstanceManager instances = Rebase.get().getInstanceManager();
            String hostId = desktopHostId(server);
            if (!hostId.isBlank()) {
                RemoteHost host = findHostById(instances, hostId);
                if (host == null) return null;
                return instances.getRemoteInstances(host).stream()
                        .filter(candidate -> matchesDesktopIdentity(server, candidate))
                        .findFirst().orElse(null);
            }
            if (!identifier.isBlank()) {
                Instance value = instances.getInstanceById(identifier);
                if (value != null) {
                    return value;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private ServerModels.ClientServerView desktopServerView(Instance instance) {
        return toServerView(instance);
    }

    static boolean matchesDesktopIdentity(ServerModels.ClientServerView server, Instance instance) {
        if (server == null || instance == null || !Objects.equals(restudioIdentifier(server), instance.getInstanceId())) return false;
        String hostId = desktopHostId(server);
        return hostId.isBlank() || Objects.equals(hostId, desktopHostId(instance));
    }

    static String desktopHostId(ServerModels.ClientServerView server) {
        if (server == null || server.environment == null) return "";
        return Objects.toString(server.environment.get(DESKTOP_HOST_ID), "");
    }

    static String desktopHostId(Instance instance) {
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().credentials == null) return "";
        String hostId = Objects.toString(instance.getBackendConfig().credentials.get("hostId"), "");
        if (!hostId.isBlank()) return hostId;
        RemoteHost host = findHost(instance);
        return host == null ? "" : Objects.toString(host.hostId, "");
    }

    private static String restudioIdentifier(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.identifier != null && !server.identifier.isBlank()) return server.identifier;
        if (server.uuid != null && !server.uuid.isBlank()) return server.uuid;
        return "";
    }

    private static boolean isReStudioTarget(ServerModels.ClientServerView server) {
        return server != null && "RESTUDIO".equalsIgnoreCase(server.backendType);
    }

    private static String restudioIdentifier(Instance instance) {
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().credentials == null
                || !"RESTUDIO".equalsIgnoreCase(instance.getBackendConfig().type)) return "";
        String identifier = instance.getBackendConfig().credentials.get("identifier");
        return identifier == null ? "" : identifier;
    }

    private void clearRestudioBridge() {
        restudioRequestGeneration.incrementAndGet();
        restudioBridgeInstances.clear();
        restudioBridgeViews.clear();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static ModLoader creationPreset(String value) {
        try {
            return ModLoader.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return ModLoader.VANILLA;
        }
    }

    private static String instanceOrderKey(Instance instance) {
        BackendConfig config = instance == null ? null : instance.getBackendConfig();
        if (config != null && "RESTUDIO".equalsIgnoreCase(config.type)) {
            String identifier = config.credentials == null ? "" : config.credentials.get("identifier");
            if (identifier != null && !identifier.isBlank()) return "RESTUDIO_" + identifier;
        }
        if (config != null && PteroBackend.isPanelType(config.type)) {
            String hostId = config.credentials == null ? "" : config.credentials.getOrDefault("hostId", "");
            String identifier = config.credentials == null ? "" : config.credentials.getOrDefault("identifier", "");
            if (!identifier.isBlank()) return config.type.toUpperCase(Locale.ROOT) + "_" + hostId + "_" + identifier;
        }
        return instance == null ? "" : instance.getInstanceId();
    }

    private static ServerModels.ClientServerView toServerView(Instance instance) {
        ServerModels.ClientServerView result = new ServerModels.ClientServerView();
        result.identifier = instance.getInstanceId();
        result.uuid = instance.getInstanceId();
        result.name = instance.getName();
        result.description = "Local Server";
        result.loader = instance.getModLoader() == null ? "" : instance.getModLoader().name();
        result.version = instance.getVersionId();
        result.isInstalling = instance.getState() == InstanceState.INSTALLING;
        result.nodeName = instance.getBackendConfig() == null || instance.getBackendConfig().type == null
                ? "Local" : instance.getBackendConfig().type;
        result.backendType = instance.getBackendConfig() == null || instance.getBackendConfig().type == null
                || instance.getBackendConfig().type.isBlank() ? "LOCAL" : instance.getBackendConfig().type;
        result.environment = new LinkedHashMap<>();
        result.environment.put("backend", result.nodeName);
        result.environment.put("state", instance.getState() == null ? "offline" : instance.getState().name().toLowerCase(Locale.ROOT));
        String hostId = desktopHostId(instance);
        if (!hostId.isBlank()) result.environment.put(DESKTOP_HOST_ID, hostId);
        return result;
    }
}
