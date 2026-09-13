package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.RemotelyCapabilityException;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.packcontent.GlyphPreviewMode;
import redxax.oxy.remotely.packcontent.GlyphPreviewRenderer;
import redxax.oxy.remotely.packcontent.NexoGlyphPreviewAccess;
import redxax.oxy.remotely.ui.server.ServerConfigurationScreen;
import redxax.oxy.remotely.ui.server.ServerConfigurationUiComposition;
import redxax.oxy.remotely.ui.server.ServerConfigurationUiPlatform;
import redxax.oxy.remotely.ui.server.ServerConfigurationTarget;
import redxax.oxy.remotely.ui.server.NetworkOverviewScreen;
import redxax.oxy.remotely.ui.server.NetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.NewTerminalTargetProvider;
import redxax.oxy.remotely.ui.server.ServerDetailsScreen;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.ui.server.ServerIconManager;
import redxax.oxy.remotely.ui.server.ServerIconProvider;
import redxax.oxy.remotely.ui.server.ResourceContainerAdapter;
import redxax.oxy.remotely.ui.server.CanonicalResourceContainerAdapter;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import redxax.oxy.remotely.ui.server.ServerTerminalPlatform;
import redxax.oxy.remotely.ui.server.ServerDetailsTarget;
import redxax.oxy.remotely.ui.server.ClientServerDetailsTarget;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDocumentDataController;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDocumentStore;
import redxax.oxy.remotely.ui.settings.controllers.PlayerActionsFileProvider;
import redxax.oxy.remotely.ui.settings.controllers.PortManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerExtraSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerFeatureSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerGeneralSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerPlanSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerJvmSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerLiveSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.SubuserSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.UnavailableServerLiveSettingsProvider;
import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.widgets.InstanceResourceWidget;
import redxax.oxy.remotely.settings.server.BundledServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.BrowserSafeYamlServerSettingsMetadataParser;
import redxax.oxy.remotely.settings.server.BrowserSafeYaml;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import restudio.rebase.backend.FileExplorerProviders;
import restudio.rebase.backend.FileExplorerRuntime;
import restudio.rebase.backend.CapabilityDescriptor;
import restudio.rebase.backend.CapabilityIds;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.TerminalSessionProvider;
import restudio.rebase.backend.TransferSink;
import restudio.rebase.backend.TransferSource;
import restudio.rebase.backend.feature.AsyncServerScheduleFeature;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.resource.marketplace.ResourceBrowserContext;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProviderAdapter;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProviderAdapter.VersionContext;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProvider;
import restudio.rebase.resource.provider.AsyncReStudioMarketplaceProvider;
import restudio.rebase.resource.provider.ResourceProviderCatalog;
import restudio.rebase.resource.provider.ResourceProviderTransport;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.restudio.community.ReStudioCommunityProvider;
import restudio.rebase.restudio.community.ReStudioCommunityProviders;
import restudio.rebase.settings.controllers.ModpackSettingsProvider;
import restudio.rebase.settings.controllers.ModpackSettingsTarget;
import restudio.rebase.settings.controllers.BackupSettingsProvider;
import restudio.rebase.settings.controllers.SettingsActionCapability;
import restudio.rebase.settings.controllers.VersionSettingsCatalog;
import restudio.rebase.settings.controllers.VersionSettingsTarget;
import restudio.rebase.util.VersionUtil;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rebase.ui.screens.editor.FileEditorScreen;
import restudio.rebase.ui.screens.editor.EditorDecorationBinding;
import restudio.rebase.ui.screens.explorer.FileExplorerScreen;
import restudio.rebase.ui.widgets.editor.TextLineDecoration;
import restudio.rebase.ui.screens.feedback.FeedbackBrowserScreen;
import restudio.rebase.ui.screens.notification.InboxScreen;
import restudio.rebase.ui.screens.resources.ResourceBrowserScreen;
import restudio.rebase.ui.screens.resources.ResourceContainer;
import restudio.rebase.ui.screens.auth.ReStudioLoginScreen;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.IconCustomizerWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyRecentItem;
import redxax.oxy.remotely.config.RemotelyViewStateStore;
import redxax.oxy.remotely.config.SettingsScreenFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class BrowserServerScreenHost implements ServerScreenHost {
    private static final int MAX_CACHED_SERVER_STATES = 128;

    private final BrowserApplicationHost application;
    private final RemotelyServerApi serverApi;
    private final TaskScheduler scheduler;
    private final FlowManager flowManager;
    private final RemotelyClient remotelyClient;
    private final ResourceMarketplaceProviderAdapter resourceMarketplace;
    private BrowserResourceProviderGateway resourceGateway;
    private final BrowserFileExplorerPersistence fileExplorerPersistence;
    private final ServerIconProvider iconProvider;
    private final FileExplorerProviders.Snapshot previousFileExplorerProviders;
    private final FileExplorerRuntime.Snapshot previousFileExplorerRuntime;
    private final Map<String, ServerUiCapabilityProvider> capabilityProviders = new LinkedHashMap<>();
    private final Map<String, NexoGlyphPreviewAccess> glyphPreviews = new LinkedHashMap<>();
    private final Map<String, DeveloperCapabilityProvider> developerProviders = new LinkedHashMap<>();
    private final Map<String, DeveloperCapabilityProvider.Workspace.Binding> developerBindings = new LinkedHashMap<>();
    private final Set<String> resolvedDeveloperBindings = new HashSet<>();
    private final Map<String, HostContext> developerBindingRequests = new LinkedHashMap<>();
    private final Map<String, List<Consumer<DeveloperCapabilityProvider.Workspace.Binding>>> developerBindingWaiters = new LinkedHashMap<>();
    private final Map<String, ServerState> serverStates = new LinkedHashMap<>();
    private final Map<String, List<Consumer<ServerState>>> stateListeners = new LinkedHashMap<>();
    private final Set<String> stateRequests = new HashSet<>();
    private final Map<String, ServerModels.ReProxySummary> reProxyStates = new LinkedHashMap<>();
    private final Map<String, List<Runnable>> reProxyRefreshListeners = new LinkedHashMap<>();
    private final Set<String> reProxyStateRequests = new HashSet<>();
    private final Map<String, PlayerMetrics> playerMetrics = new LinkedHashMap<>();
    private final Map<String, Long> playerMetricRefreshes = new LinkedHashMap<>();
    private final Set<String> playerMetricRequests = new HashSet<>();
    private final Set<Runnable> instanceChangeListeners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Runnable> networkChangeListeners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Runnable> runtimeChangeListeners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<HostedResourceContext> resourceContexts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Consumer<BrowserRemotelyServerApi.ManagerSnapshot> browserInstanceChangeListener = this::handleBrowserInstanceChange;
    private final Consumer<BrowserRemotelyServerApi.ManagerSnapshot> browserNetworkChangeListener = this::handleBrowserNetworkChange;
    private final Consumer<BrowserRemotelyServerApi.ManagerSnapshot> browserRuntimeChangeListener = this::handleBrowserRuntimeChange;
    private final Runnable authStateListener = this::handleAuthenticationChange;
    private final Runnable ticketListener = this::handleAuthenticationChange;
    private final Runnable sessionExpiryListener = this::handleSessionExpiry;
    private boolean sessionExpiryNotified;
    private boolean authenticationObserved;
    private boolean observedAuthenticated;
    private String observedSubjectId = "";
    private String observedTicket = "";
    private long hostGeneration = 1L;
    private long resourceGeneration = 1L;
    private long resourceAuthGeneration = 1L;
    private boolean authenticationCleanupInProgress;
    private boolean closed;

    public BrowserServerScreenHost(BrowserApplicationHost application, RemotelyServerApi serverApi, TaskScheduler scheduler, FlowManager flowManager) {
        this(application, serverApi, scheduler, flowManager, null);
    }

    public BrowserServerScreenHost(BrowserApplicationHost application, RemotelyClient remotelyClient) {
        this(application, remotelyClient == null ? null : remotelyClient.getApiClient(),
                remotelyClient == null ? null : remotelyClient.getComposition().scheduler(),
                remotelyClient == null ? null : remotelyClient.getFlowManager(), remotelyClient);
    }

    @Override
    public ServerScreenHost.ServerManagerMode serverManagerMode() {
        return ServerScreenHost.ServerManagerMode.REACTOR_ONLY;
    }

    private BrowserServerScreenHost(BrowserApplicationHost application, RemotelyServerApi serverApi, TaskScheduler scheduler,
                                    FlowManager flowManager, RemotelyClient remotelyClient) {
        this.application = application;
        this.serverApi = serverApi;
        this.scheduler = scheduler;
        this.flowManager = flowManager;
        this.remotelyClient = remotelyClient;
        this.fileExplorerPersistence = new BrowserFileExplorerPersistence(configStore() instanceof BrowserRemotelyConfigStore config ? config : null);
        this.iconProvider = new BrowserServerIconProvider(() -> configStore() instanceof BrowserRemotelyConfigStore config ? config : null);
        this.previousFileExplorerProviders = FileExplorerProviders.snapshot();
        this.previousFileExplorerRuntime = FileExplorerRuntime.snapshot();
        this.resourceMarketplace = createResourceMarketplace();
        BrowserLaunchSession.addAuthStateListener(authStateListener);
        BrowserLaunchSession.addTicketListener(ticketListener);
        BrowserLaunchSession.addSessionExpiryListener(sessionExpiryListener);
        observeAuthentication(false);
        installFileExplorerRuntime();
        FileEditorScreen.setEditorDecorationBinder(this::bindEditorDecoration);
        FileExplorerProviders.installDescriptorResolver(this, (type, credentials, serverId) -> {
            if (!authenticated() || serverId == null || serverId.isBlank() || !supportedServerBackend(type)) {
                return RemoteFileSystemProvider.unavailable();
            }
            BrowserRemotelyServerApi browserApi = browserApi();
            if (browserApi == null) return RemoteFileSystemProvider.unavailable();
            ServerModels.ClientServerView server = new ServerModels.ClientServerView();
            server.identifier = serverId;
            server.name = credentials == null ? null : credentials.get("serverName");
            server.backendType = type;
            return new BrowserServerFileSystemProvider(this, browserApi, capabilities(server), server);
        });
    }

    @Override
    public BrowserApplicationHost application() {
        return application;
    }

    RemotelyServerApi serverApi() {
        return serverApi;
    }

    @Override
    public ServerDetailsTarget detailsTarget(Object value) {
        if (value instanceof ServerDetailsTarget target) return target;
        ServerModels.ClientServerView server = serverView(value);
        return server == null ? ServerDetailsTarget.unavailable(value) : new ClientServerDetailsTarget(server);
    }

    @Override
    public ServerScreenHost.ServerIdentity identity(Object value) {
        ServerModels.ClientServerView server = serverView(value);
        if (server == null) return ServerScreenHost.super.identity(value);
        String backend = browserBackendType(server);
        return new ServerScreenHost.ServerIdentity(serverId(server), serverName(server), backend, "", true,
                "LOCAL".equalsIgnoreCase(backend), server.isInstalling);
    }

    public void close() {
        BrowserLaunchSession.removeAuthStateListener(authStateListener);
        BrowserLaunchSession.removeTicketListener(ticketListener);
        BrowserLaunchSession.removeSessionExpiryListener(sessionExpiryListener);
        closed = true;
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi != null) browserApi.removeManagerPollingOwner(this);
        if (resourceGateway != null) resourceGateway.close();
        clearBrowserSessionState();
        sessionExpiryNotified = false;
        authenticationObserved = false;
        observedAuthenticated = false;
        observedSubjectId = "";
        observedTicket = "";
        FileExplorerProviders.restore(previousFileExplorerProviders);
        FileExplorerRuntime.restore(previousFileExplorerRuntime);
        FileEditorScreen.setEditorDecorationBinder(null);
    }

    @Override
    public void openExternal(String url) {
        if (url != null && !url.isBlank()) {
            application.hostActionHandler().openBrowser(url);
        }
    }

    @Override
    public ServerUiCapabilityProvider capabilities(ServerModels.ClientServerView server) {
        String serverId = serverId(server);
        if (serverId.isBlank() || serverApi == null) return ServerUiCapabilityProvider.unavailable();
        ServerUiCapabilityProvider provider = capabilityProviders.get(serverId);
        if (provider != null) return provider;
        provider = ServerUiCapabilityProvider.api(serverApi, flowManager);
        capabilityProviders.put(serverId, provider);
        provider.refresh(server);
        return provider;
    }

    @Override
    public boolean supportsDevelopment(Object target) {
        if (demo()) return false;
        ServerModels.ClientServerView server = serverView(target);
        String serverId = serverId(server);
        if (serverId.isBlank() || browserApi() == null || !authenticated()) return false;
        resolveDevelopmentBinding(server, null);
        String key = developmentKey(serverId);
        DeveloperCapabilityProvider provider = developerProviders.get(key);
        DeveloperCapabilityProvider.Workspace.Binding binding = developerBindings.get(key);
        CapabilityDescriptor capability = provider == null ? null : provider.capability(CapabilityIds.DEVELOPER);
        CapabilityDescriptor files = provider == null ? null : provider.capability(CapabilityIds.FILES);
        return binding != null && capability != null && capability.available() && files != null && files.available();
    }

    @Override
    public Async<String> connectionInfo(Object target) {
        ServerModels.ClientServerView server = serverView(target);
        if (server == null) return Async.completed("");
        if (server.fullDomain != null && !server.fullDomain.isBlank()) return Async.completed(server.fullDomain);
        if (server.ipAlias != null && !server.ipAlias.isBlank()) return Async.completed(withPort(server.ipAlias, server.port));
        if (server.subdomain != null && !server.subdomain.isBlank()) return Async.completed(server.subdomain);
        if (server.ip == null || server.ip.isBlank()) return Async.completed("");
        return Async.completed(withPort(server.ip, server.port));
    }

    @Override
    public boolean supportsReProxy(Object target) {
        if (demo()) return false;
        ServerModels.ClientServerView server = serverView(target);
        if (server == null || !BrowserLaunchSession.authenticated() || !isLocalBackend(server)) return false;
        ServerUiCapabilityProvider provider = capabilities(server);
        ServerUiCapabilityProvider.Availability start = provider.availability(server, "reproxy.start");
        ServerUiCapabilityProvider.Availability stop = provider.availability(server, "reproxy.stop");
        return start.available() || stop.available() || capabilityLoading(start) || capabilityLoading(stop);
    }

    @Override
    public boolean isReProxyForwarded(Object target) {
        ServerModels.ClientServerView server = serverView(target);
        if (!isLocalBackend(server)) return false;
        String id = serverId(server);
        if (id.isBlank()) return false;
        ServerModels.ReProxySummary summary = reProxyStates.get(id);
        ServerModels.ReProxyTunnel tunnel = summary == null ? null : summary.activeTunnel;
        return tunnel != null && activeReProxyStatus(tunnel.status);
    }

    @Override
    public void refreshReProxy(Object target, Runnable onComplete) {
        ServerModels.ClientServerView server = serverView(target);
        BrowserRemotelyServerApi api = browserApi();
        String id = serverId(server);
        HostContext context = captureContext();
        if (!isLocalBackend(server)) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (api == null || id.isBlank() || !isCurrent(context) || reProxyStates.containsKey(id)) return;
        if (onComplete != null) reProxyRefreshListeners.computeIfAbsent(id, ignored -> new ArrayList<>()).add(onComplete);
        if (!reProxyStateRequests.add(id)) return;
        api.getReProxySummary(id).whenComplete((summary, failure) -> execute(() -> {
            reProxyStateRequests.remove(id);
            if (!isCurrent(context)) {
                reProxyRefreshListeners.remove(id);
                return;
            }
            reProxyStates.put(id, summary == null ? emptyReProxySummary() : summary);
            List<Runnable> listeners = reProxyRefreshListeners.remove(id);
            if (listeners != null) listeners.forEach(Runnable::run);
        }));
    }

    @Override
    public void startReProxy(Object target, Runnable onComplete) {
        ServerModels.ClientServerView server = serverView(target);
        if (server == null) {
            unavailable(Action.NETWORK_LIFECYCLE);
            return;
        }
        capabilityOperation(server, "reproxy.start", () -> startReProxyOperation(server))
                .whenComplete((ignored, failure) -> execute(() -> {
                    if (failure != null) {
                        application.notify("ReProxy Start Failed", failureMessage(failure), ReSyncNotificationLevel.ERROR);
                        return;
                    }
                    if (onComplete != null) onComplete.run();
                }));
    }

    @Override
    public void stopReProxy(Object target, Runnable onComplete) {
        ServerModels.ClientServerView server = serverView(target);
        if (server == null) {
            unavailable(Action.NETWORK_LIFECYCLE);
            return;
        }
        capabilityOperation(server, "reproxy.stop", () -> stopReProxyOperation(server))
                .whenComplete((ignored, failure) -> execute(() -> {
                    if (failure != null) {
                        application.notify("ReProxy Stop Failed", failureMessage(failure), ReSyncNotificationLevel.ERROR);
                        return;
                    }
                    if (onComplete != null) onComplete.run();
                }));
    }

    @Override
    public ServerUiCapabilityProvider.Availability healthRepairAvailability(ServerModels.ClientServerView server, String repair) {
        return ServerUiCapabilityProvider.Availability.missing("Server Repair Is Unavailable In The Browser");
    }

    @Override
    public Async<ServerScreenHost.ServerMetrics> metrics(RemotelyServerApi api, Object target) {
        HostContext context = captureContext();
        ServerModels.ClientServerView server = serverView(target);
        BrowserRemotelyServerApi browserApi = browserApi();
        if (!isCurrent(context) || server == null || browserApi == null || serverId(server).isBlank()) {
            return Async.failed(new UnsupportedOperationException("Server Statistics Are Unavailable"));
        }
        return browserApi.getServerResources(serverId(server)).thenApply(stats -> {
            if (!isCurrent(context)) return emptyMetrics(server);
            if (stats == null || stats.resources == null) {
                return clearMetrics(server);
            }
            long limit = stats.resources.limits == null || stats.resources.limits.memory == null
                    ? memoryLimit(server) : stats.resources.limits.memory * 1024L * 1024L;
            refreshPlayerMetrics(server, browserApi, context);
            PlayerMetrics players = playerMetrics.getOrDefault(serverId(server), new PlayerMetrics(0, 0));
            return new ServerScreenHost.ServerMetrics(stats.resources.uptime, stats.resources.cpuAbsolute,
                stats.resources.memoryBytes, limit, players.players(), players.maxPlayers());
        }).exceptionally(failure -> {
            if (!isCurrent(context)) return emptyMetrics(server);
            if (isSessionExpired(failure)) {
                notifySessionExpired();
            }
            return clearMetrics(server);
        });
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> localServers() {
        return Async.failed(new UnsupportedOperationException("Local Server Inventory Requires A Connected Desktop Host"));
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> restudioServers() {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi == null || !authenticated()) {
            return Async.completed(List.of());
        }
        return browserApi.getServers();
    }

    @Override
    public void reloadInstances() {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi == null || closed || !authenticated()) return;
        browserApi.reloadInstances(this);
    }

    @Override
    public void addInstanceChangeListener(Runnable listener) {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi == null || listener == null || !instanceChangeListeners.add(listener)) return;
        browserApi.addInstanceChangeListener(this, browserInstanceChangeListener);
    }

    @Override
    public void removeInstanceChangeListener(Runnable listener) {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (listener == null || !instanceChangeListeners.remove(listener)) return;
        if (browserApi != null && instanceChangeListeners.isEmpty()) {
            browserApi.removeInstanceChangeListener(this, browserInstanceChangeListener);
        }
    }

    @Override
    public void addNetworkChangeListener(Runnable listener) {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi == null || listener == null || !networkChangeListeners.add(listener)) return;
        browserApi.addNetworkChangeListener(this, browserNetworkChangeListener);
    }

    @Override
    public void removeNetworkChangeListener(Runnable listener) {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (listener == null || !networkChangeListeners.remove(listener)) return;
        if (browserApi != null && networkChangeListeners.isEmpty()) {
            browserApi.removeNetworkChangeListener(this, browserNetworkChangeListener);
        }
    }

    @Override
    public void addRuntimeChangeListener(Runnable listener) {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi == null || listener == null || !runtimeChangeListeners.add(listener)) return;
        browserApi.addRuntimeChangeListener(this, browserRuntimeChangeListener);
    }

    @Override
    public void removeRuntimeChangeListener(Runnable listener) {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (listener == null || !runtimeChangeListeners.remove(listener)) return;
        if (browserApi != null && runtimeChangeListeners.isEmpty()) {
            browserApi.removeRuntimeChangeListener(this, browserRuntimeChangeListener);
        }
    }

    @Override
    public List<RemotelyRecentItem> recentRestudioItems() {
        if (remotelyClient == null) {
            return List.of();
        }
        RemotelyConfigStore config = remotelyClient.getComposition().configManager();
        return config == null ? List.of() : config.getRecentRestudioItems();
    }

    @Override
    public void recordRecentRestudioItem(RemotelyRecentItem item) {
        if (remotelyClient == null) {
            return;
        }
        RemotelyConfigStore config = remotelyClient.getComposition().configManager();
        if (config != null) {
            config.recordRecentRestudioItem(item);
        }
    }

    @Override
    public void removeRecentRestudioItem(RemotelyRecentItem item) {
        if (remotelyClient == null || item == null) {
            return;
        }
        RemotelyConfigStore config = remotelyClient.getComposition().configManager();
        if (config != null) {
            config.setRecentRestudioItems(config.getRecentRestudioItems().stream()
                    .filter(existing -> !existing.key().equals(item.key()))
                    .toList());
        }
    }

    @Override
    public boolean openRecentRestudioItem(Screen current, RemotelyRecentItem item) {
        BrowserRemotelyServerApi browserApi = browserApi();
        HostContext context = captureContext();
        if (!isCurrent(context) || browserApi == null || item == null || item.serverId().isBlank()) {
            return false;
        }
        browserApi.getServer(item.serverId()).whenComplete((server, failure) -> execute(() -> {
            if (!isCurrent(context)) return;
            if (failure != null || server == null || serverId(server).isBlank()) {
                recentUnavailable(item);
                return;
            }
            if (item.kind() == RemotelyRecentItem.Kind.SERVER) {
                if (!mergeServerScreen(current, server, false)) recentUnavailable(item);
                return;
            }
            if (flowManager == null) {
                recentUnavailable(item);
                return;
            }
            int separator = item.path().indexOf(':');
            if (separator <= 0 || separator >= item.path().length() - 1) {
                recentUnavailable(item);
                return;
            }
            recordRecentRestudioItem(item);
            flowManager.openReSyncStudio(serverId(server), server, "", server.name);
            String type = item.path().substring(0, separator).toLowerCase(Locale.ROOT);
            String id = item.path().substring(separator + 1);
            flowManager.openStudioDocument(serverId(server), item.path(), screen -> {
                if (isCurrent(context)) screen.openWorkspaceResource(type, id);
            });
        }));
        return true;
    }

    private void recentUnavailable(RemotelyRecentItem item) {
        removeRecentRestudioItem(item);
        application.notify("Recent Item Unavailable", "The Saved ReStudio Item Was Removed", ReSyncNotificationLevel.ERROR);
    }

    @Override
    public boolean openServerPath(Screen current, String path) {
        return openRecentRestudioPath(current, path);
    }

    @Override
    public boolean authenticated() {
        return !closed && BrowserLaunchSession.authenticated();
    }

    private boolean demo() {
        return BrowserLaunchSession.metadata().demo();
    }

    @Override
    public ServerScreenHost.AccountIdentity accountIdentity() {
        if (closed) return new ServerScreenHost.AccountIdentity(false, "", "Sign In", "steve.png");
        observeAuthentication(true);
        boolean authenticated = authenticated();
        if (!authenticated) {
            return new ServerScreenHost.AccountIdentity(false, "", "Sign In", "steve.png");
        }
        BrowserLaunchSession.Metadata session = BrowserLaunchSession.metadata();
        if (session.demo()) return new ServerScreenHost.AccountIdentity(true, session.subjectId(), "Reactor Demo", "Reactor.png");
        ReStudioCommunityProvider provider = ReStudioCommunityProviders.current();
        String subjectId = firstNonBlank(session.subjectId(), provider.userId());
        String displayName = firstNonBlank(session.displayName(), provider.displayName());
        displayName = firstNonBlank(displayName, session.username());
        displayName = firstNonBlank(displayName, provider.username());
        displayName = firstNonBlank(displayName, session.sessionLabel());
        displayName = firstNonBlank(displayName, "Account");
        String avatar = firstNonBlank(session.avatarUrl(), "steve.png");
        return new ServerScreenHost.AccountIdentity(true, subjectId, displayName, avatar);
    }

    @Override
    public Async<Identifier> accountAvatar() {
        HostContext context = captureContext();
        observeAuthentication(true);
        if (!isCurrent(context)) return Async.completed(null);
        BrowserLaunchSession.Metadata session = BrowserLaunchSession.metadata();
        if (session.demo()) return Async.completed(null);
        String ticket = BrowserLaunchSession.ticket();
        boolean subjectUpgradeable = context.subjectId().isBlank();
        ReStudioCommunityProvider provider = ReStudioCommunityProviders.current();
        return provider.getAccount().thenCompose(account -> {
            String responseSubjectId = account == null || account.id == null ? "" : account.id;
            if (!isCurrentAccountResponse(context, ticket, responseSubjectId, subjectUpgradeable)) return Async.completed(null);
            if (account != null) {
                BrowserLaunchSession.updateAccountMetadata(account.id, account.username, account.displayName, account.email, account.avatarUrl, session.sessionLabel());
            }
            if (!isCurrentAccountResponse(context, ticket, responseSubjectId, subjectUpgradeable)) return Async.completed(null);
            if (subjectUpgradeable && !responseSubjectId.isBlank()) adoptAccountSubject(ticket, responseSubjectId);
            BrowserLaunchSession.Metadata updated = BrowserLaunchSession.metadata();
            return provider.loadAvatarId(firstNonBlank(updated.subjectId(), provider.userId()), updated.avatarUrl()).exceptionally(ignored -> {
                if (!isCurrentAccountResponse(context, ticket, responseSubjectId, subjectUpgradeable)) return null;
                String avatar = BrowserLaunchSession.metadata().avatarUrl();
                return avatar.isBlank() ? null : application.registerRemoteImage(avatar);
            });
        }).exceptionally(ignored -> {
            if (!isCurrentAccountResponse(context, ticket, "", false)) return null;
            String avatar = BrowserLaunchSession.metadata().avatarUrl();
            return avatar.isBlank() ? null : application.registerRemoteImage(avatar);
        });
    }

    @Override
    public ServerScreenHost.EnvironmentNotice environmentNotice() {
        if (!BrowserLaunchSession.metadata().demo()) return ServerScreenHost.super.environmentNotice();
        String description = demoExpiryDescription(BrowserLaunchSession.demoLeaseExpiresAt());
        return new ServerScreenHost.EnvironmentNotice("Reactor Demo", description, "Reactor.png");
    }

    @Override
    public void addAuthStateListener(Runnable listener) {
        BrowserLaunchSession.addAuthStateListener(listener);
    }

    @Override
    public void removeAuthStateListener(Runnable listener) {
        BrowserLaunchSession.removeAuthStateListener(listener);
    }

    @Override
    public ServerIconProvider iconProvider() {
        return iconProvider;
    }

    @Override
    public Object iconTarget(ServerModels.ClientServerView server) {
        return server;
    }

    @Override
    public ServerScreenHost.NetworkJobView latestNetworkJob(ServerScreenHost.NetworkView network) {
        return null;
    }

    @Override
    public Async<List<ServerScreenHost.HostView>> remoteHosts() {
        return Async.failed(new UnsupportedOperationException("Remote Host Inventory Requires A Connected Desktop Host"));
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> hostServers(ServerScreenHost.HostView host) {
        return Async.failed(new UnsupportedOperationException("Remote Host Inventory Requires A Connected Desktop Host"));
    }

    @Override
    public Async<List<ServerScreenHost.NetworkView>> networks() {
        if (demo()) return Async.completed(List.of());
        BrowserRemotelyServerApi browserApi = browserApi();
        return browserApi == null || !authenticated() ? Async.completed(List.of()) : browserApi.getNetworks();
    }

    @Override
    public Async<List<ServerScreenHost.GroupView>> groups(String context) {
        if (remotelyClient == null || !(remotelyClient.getComposition().configManager() instanceof BrowserRemotelyConfigStore config)) {
            return Async.completed(List.of());
        }
        return Async.completed(config.getInstanceGroupViews(context));
    }

    @Override
    public Async<List<ServerScreenHost.PlanView>> reactorPlans() {
        if (demo()) return Async.completed(List.of());
        BrowserRemotelyServerApi browserApi = browserApi();
        return browserApi == null ? Async.completed(List.of()) : browserApi.getPlans().thenApply(plans -> plans == null ? List.of()
                : plans.stream().filter(Objects::nonNull).map(plan -> new ServerScreenHost.PlanView(
                        plan.id, plan.name, plan.memoryMb, plan.diskMb, plan.cpuPercent, plan.priceCents)).toList());
    }

    @Override
    public Async<Void> saveServerOrder(String context, List<String> serverIds) {
        if (remotelyClient != null && remotelyClient.getComposition().configManager() instanceof BrowserRemotelyConfigStore config) {
            config.setInstanceOrder(context, serverIds == null ? List.of() : serverIds);
        }
        return Async.completed(null);
    }

    @Override
    public Async<Void> saveGroup(String context, ServerScreenHost.GroupView group) {
        if (remotelyClient != null && remotelyClient.getComposition().configManager() instanceof BrowserRemotelyConfigStore config
                && group != null) {
            List<ServerScreenHost.GroupView> groups = new ArrayList<>(config.getInstanceGroupViews(context));
            groups.removeIf(value -> Objects.equals(value.id(), group.id()));
            if (!group.members().isEmpty()) groups.add(group);
            config.setInstanceGroupViews(context, groups);
        }
        return Async.completed(null);
    }

    @Override
    public Async<Void> saveConfiguredGroups(String context, List<ServerScreenHost.GroupView> requested) {
        if (remotelyClient != null && remotelyClient.getComposition().configManager() instanceof BrowserRemotelyConfigStore config) {
            List<ServerScreenHost.GroupView> groups = requested == null ? List.of() : requested.stream()
                    .filter(Objects::nonNull)
                    .filter(group -> !group.members().isEmpty())
                    .map(group -> new ServerScreenHost.GroupView(group.id().isBlank() ? UUID.randomUUID().toString() : group.id(), group.name(), group.members()))
                    .toList();
            config.setInstanceGroupViews(context, groups);
        }
        return Async.completed(null);
    }

    @Override
    public Async<Void> serverAction(ServerModels.ClientServerView server, String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if ("hide".equals(normalized)) {
            RemotelyConfigStore config = configStore();
            if (config != null) {
                String id = serverId(server);
                if (!id.isBlank()) config.hideRestudioServer(id);
                if (server != null && server.name != null && !server.name.isBlank()) config.hideRestudioServer(server.name);
            }
            return Async.completed(null);
        }
        return switch (normalized) {
            case "duplicate" -> duplicateOperation(server);
            case "trash" -> Async.failed(new UnsupportedOperationException("Hosted Server Trash Is Unavailable"));
            case "delete" -> deleteOperation(server);
            case "install-resync", "provision-resync" -> capabilityOperation(server, "resync.provision",
                    () -> serverApi.provisionReSync(serverId(server)).thenApply(ignored -> null));
            case "update-resync" -> capabilityOperation(server, "resync.update",
                    () -> serverApi.updateReSync(serverId(server)).thenApply(ignored -> null));
            default -> Async.failed(new UnsupportedOperationException("Server Action Is Unavailable"));
        };
    }

    @Override
    public Async<Void> customizeIcon(ServerModels.ClientServerView server, ServerScreenHost.HostView host,
                                     Identifier icon, Runnable onComplete) {
        if (server == null || serverId(server).isBlank() || icon == null) {
            return Async.failed(new IllegalArgumentException("Server Icon Is Unavailable"));
        }
        HostContext context = captureContext();
        Runnable guardedCompletion = onComplete == null ? null : () -> {
            if (isCurrent(context)) onComplete.run();
        };
        return new ServerIconManager(iconProvider).customizeIcon(server, host, icon, guardedCompletion);
    }

    @Override
    public void openIconCustomizer(Screen current, ServerModels.ClientServerView server,
                                   ServerScreenHost.HostView host, Runnable onComplete) {
        if (current == null || server == null || serverId(server).isBlank()) {
            unavailable(Action.CUSTOMIZE_ICON);
            return;
        }
        ServerIconManager iconManager = new ServerIconManager(iconProvider);
        List<Identifier> icons = iconManager.loadIconAssetIds();
        if (icons.isEmpty()) {
            application.notify("Icon Customizer", "Icon Assets Are Unavailable", ReSyncNotificationLevel.WARN);
            return;
        }
        HostContext context = captureContext();
        if (!isCurrent(context)) return;
        List<Integer> tints = iconManager.loadIconTints();
        Runnable guardedCompletion = onComplete == null ? null : () -> {
            if (isCurrent(context)) onComplete.run();
        };
        IconCustomizerWidget popup = IconCustomizerWidget.selecting("Icon Customizer", icons, tints, selection -> {
            if (!isCurrent(context)) return;
            ServerIconProvider.Customization customization = new ServerIconProvider.Customization(selection.image(), selection.tint(), selection.rendered());
            iconManager.customizeIcon(server, host, customization, guardedCompletion).whenComplete((ignored, failure) -> execute(() -> {
                if (!isCurrent(context) || failure == null) return;
                application.notify("Icon Customizer", failureMessage(failure), ReSyncNotificationLevel.WARN);
            }));
        });
        current.addDrawableChild(popup);
        popup.show();
    }

    @Override
    public Async<Void> hostAction(ServerScreenHost.HostView host, String action) {
        return Async.failed(new UnsupportedOperationException("Remote Host Actions Require A Connected Desktop Host"));
    }

    @Override
    public Async<ServerScreenHost.HostView> saveRemoteHost(ServerScreenHost.RemoteHostDraft draft) {
        return Async.failed(new UnsupportedOperationException("Remote Host Configuration Requires A Connected Desktop Host"));
    }

    @Override
    public Async<Void> networkAction(ServerScreenHost.NetworkView network, String action) {
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi == null || network == null || network.id().isBlank()) {
            return Async.failed(new IllegalArgumentException("Network Is Unavailable"));
        }
        NetworkOverviewProvider provider = NetworkOverviewProvider.forClient(remotelyClient);
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "open", "settings", "manage" -> {
                openNetworkSettings(application.getCurrentScreen(), network.id());
                yield Async.completed(null);
            }
            case "start" -> provider.lifecycle(network.id(), NetworkLifecycleOperation.START).thenApply(ignored -> null);
            case "stop" -> provider.lifecycle(network.id(), NetworkLifecycleOperation.STOP).thenApply(ignored -> null);
            case "restart" -> provider.lifecycle(network.id(), NetworkLifecycleOperation.RESTART).thenApply(ignored -> null);
            case "rolling-restart" -> provider.lifecycle(network.id(), NetworkLifecycleOperation.ROLLING_RESTART).thenApply(ignored -> null);
            case "reconcile", "repair" -> provider.reconcile(network.id()).thenApply(ignored -> null);
            case "resume", "recover" -> recoverNetwork(provider, network.id(), false);
            case "rollback" -> recoverNetwork(provider, network.id(), true);
            case "preflight" -> provider.preflight(network.id()).thenApply(ignored -> null);
            case "dissolve", "delete" -> provider.dissolve(network.id()).thenApply(ignored -> null);
            default -> normalized.startsWith("rename:") ? renameNetwork(provider, network.id(), action.substring(action.indexOf(':') + 1))
                    : Async.failed(new UnsupportedOperationException("Network Action Is Unavailable"));
        };
    }

    @Override
    public Async<Void> createNetwork(String name, String proxyId, List<String> backendIds, boolean installReSync) {
        BrowserRemotelyServerApi browserApi = browserApi();
        return browserApi == null ? Async.failed(new UnsupportedOperationException("Network Creation Is Unavailable"))
                : browserApi.createNetwork(name, proxyId, backendIds, installReSync);
    }

    @Override
    public Async<Void> networkServerAction(String networkId, String serverId, String action, boolean installReSync) {
        if (networkId == null || networkId.isBlank() || serverId == null || serverId.isBlank()) {
            return Async.failed(new IllegalArgumentException("Network Member Is Unavailable"));
        }
        NetworkOverviewProvider provider = NetworkOverviewProvider.forClient(remotelyClient);
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "attach", "add", "attach-resync", "install-resync" -> provider.attach(networkId,
                    new NetworkOverviewProvider.AttachRequest(serverId, "", installReSync || normalized.endsWith("-resync") || "install-resync".equals(normalized))).thenApply(ignored -> null);
            case "detach", "remove" -> provider.detach(networkId, serverId).thenApply(ignored -> null);
            default -> Async.failed(new UnsupportedOperationException("Network Membership Action Is Unavailable"));
        };
    }

    @Override
    public boolean mergeServerScreen(Screen current, ServerModels.ClientServerView server, boolean openDevelopment) {
        if (remotelyClient == null || !authenticated() || server == null || serverId(server).isBlank()) return false;
        if (demo()) openDevelopment = false;
        application.activateReSyncServerContext(serverId(server));
        recordRecentRestudioServer(serverId(server), serverId(server), server.name);
        if (current instanceof ServerDetailsScreen details) {
            details.addInstanceTab(server);
            if (openDevelopment) details.openDevelopment(server);
            return true;
        }
        application.setScreen(new ServerDetailsScreen(current, remotelyClient, server, openDevelopment));
        return true;
    }

    void openNetworkServer(Screen current, String serverId) {
        BrowserRemotelyServerApi browserApi = browserApi();
        HostContext context = captureContext();
        if (browserApi == null || remotelyClient == null || serverId == null || serverId.isBlank()) {
            throw new UnsupportedOperationException("Server Is Unavailable");
        }
        browserApi.getServer(serverId).whenComplete((server, failure) -> execute(() -> {
            if (!isCurrent(context)) return;
            if (failure != null || server == null) {
                application.notify("Server", "Server Is Unavailable", ReSyncNotificationLevel.WARN);
                return;
            }
            mergeServerScreen(current, server, false);
        }));
    }

    private Async<Void> renameNetwork(NetworkOverviewProvider provider, String networkId, String name) {
        return provider.load(networkId).thenCompose(state -> provider.save(networkId,
                new NetworkOverviewProvider.SaveRequest(name, state.network().routingGroups(), state.network().syncRealms(),
                        state.network().features(), state.network().sharedDataPolicy()))).thenApply(ignored -> null);
    }

    private Async<Void> recoverNetwork(NetworkOverviewProvider provider, String networkId, boolean rollback) {
        return provider.load(networkId).thenCompose(state -> {
            if (state.jobs().isEmpty()) return Async.failed(new UnsupportedOperationException("Network Job Is Unavailable"));
            String jobId = state.jobs().getFirst().jobId();
            return (rollback ? provider.rollbackJob(networkId, jobId) : provider.resumeJob(networkId, jobId)).thenApply(ignored -> null);
        });
    }

    @Override
    public void openGlobalTerminal(Screen current) {
        if (current == null || browserApi() == null || remotelyClient == null || !authenticated()) {
            unavailable(Action.GLOBAL_TERMINAL);
            return;
        }
        remotelyClient.openMultiTerminal(current);
    }

    @Override
    public NewTerminalTargetProvider newTerminalTargetProvider() {
        return new NewTerminalTargetProvider() {
            @Override
            public Async<Object> newTarget(List<Object> openTargets) {
                return Async.failed(new UnsupportedOperationException("New Terminal Tabs Are Unavailable In Browser"));
            }

            @Override
            public boolean supports(Object target) {
                return target instanceof ServerModels.ClientServerView server && !serverId(server).isBlank();
            }

            @Override
            public Async<Object> resolve(Object target) {
                return target instanceof ServerModels.ClientServerView server && !serverId(server).isBlank()
                        ? Async.completed(server) : Async.completed(null);
            }

            @Override
            public Async<State> restore(State current) {
                RemotelyViewStateStore.State saved = remotelyClient == null
                        ? RemotelyViewStateStore.State.empty() : remotelyClient.getBrowserViewState();
                return restudioServers().thenApply(servers -> restoredTerminalState(servers, current, saved));
            }

            @Override
            public void persist(State state) {
                if (remotelyClient == null) return;
                List<RemotelyViewStateStore.TerminalTab> tabs = state.tabs().stream()
                        .filter(tab -> tab.target() instanceof ServerModels.ClientServerView)
                        .map(tab -> new RemotelyViewStateStore.TerminalTab(terminalStateId(tab.target()), tab.name()))
                        .filter(tab -> !tab.serverId().isBlank())
                        .toList();
                remotelyClient.saveBrowserTerminalState(tabs, state.activeIndex());
            }
        };
    }

    static Async<ServerModels.ClientServerView> terminalServer(ServerUiCapabilityProvider provider,
                                                                ServerModels.ClientServerView server) {
        return provider.refresh(server)
                .handle((ignored, failure) -> failure == null
                        && provider.availability(server, ServerUiCapabilityProvider.Capability.TERMINAL).available() ? server : null);
    }

    static NewTerminalTargetProvider.State restoredTerminalState(List<ServerModels.ClientServerView> servers,
                                                                  NewTerminalTargetProvider.State current,
                                                                  RemotelyViewStateStore.State saved) {
        Map<String, ServerModels.ClientServerView> available = new LinkedHashMap<>();
        if (servers != null) {
            for (ServerModels.ClientServerView server : servers) {
                String id = serverId(server);
                if (!id.isBlank()) available.putIfAbsent(id, server);
            }
        }
        List<NewTerminalTargetProvider.Tab> restored = new ArrayList<>();
        RemotelyViewStateStore.State persisted = saved == null ? RemotelyViewStateStore.State.empty() : saved;
        for (RemotelyViewStateStore.TerminalTab descriptor : persisted.terminalTabs()) {
            ServerModels.ClientServerView server = available.get(descriptor.serverId());
            if (server != null) restored.add(new NewTerminalTargetProvider.Tab(server, descriptor.name()));
        }
        NewTerminalTargetProvider.State open = current == null ? new NewTerminalTargetProvider.State(List.of(), 0) : current;
        for (NewTerminalTargetProvider.Tab tab : open.tabs()) {
            if (!(tab.target() instanceof ServerModels.ClientServerView server)) continue;
            String id = serverId(server);
            ServerModels.ClientServerView availableServer = available.get(id);
            if (availableServer == null || restored.stream().anyMatch(value -> id.equals(terminalTargetKey(value.target())))) continue;
            restored.add(new NewTerminalTargetProvider.Tab(availableServer, tab.name()));
        }
        String selectedId = "";
        if (!open.tabs().isEmpty()) {
            Object target = open.tabs().get(Math.clamp(open.activeIndex(), 0, open.tabs().size() - 1)).target();
            selectedId = terminalTargetKey(target);
        }
        if (selectedId.isBlank() && !persisted.terminalTabs().isEmpty()) {
            selectedId = persisted.terminalTabs().get(persisted.terminalTabIndex()).serverId();
        }
        int selectedIndex = 0;
        for (int index = 0; index < restored.size(); index++) {
            if (selectedId.equals(terminalTargetKey(restored.get(index).target()))) {
                selectedIndex = index;
                break;
            }
        }
        return new NewTerminalTargetProvider.State(restored, selectedIndex);
    }

    private static String terminalStateId(Object target) {
        if (target instanceof ServerModels.ClientServerView server) return serverId(server);
        return "";
    }

    private static String terminalTargetKey(Object target) {
        if (target instanceof ServerModels.ClientServerView server) return serverId(server);
        return "";
    }

    @Override
    public void openGlobalFileExplorer(Screen current) {
        if (current == null || browserApi() == null) {
            unavailable(Action.FILE_EXPLORER);
            return;
        }
        HostContext context = captureContext();
        if (!isCurrent(context)) return;
        restudioServers().whenComplete((servers, failure) -> execute(() -> {
            if (!isCurrent(context)) return;
            if (failure != null) {
                application.notify("File Explorer", failureMessage(failure), ReSyncNotificationLevel.WARN);
                return;
            }
            List<ServerModels.ClientServerView> available = servers == null ? List.of() : servers.stream()
                    .filter(Objects::nonNull)
                    .filter(server -> !serverId(server).isBlank())
                    .toList();
            if (available.isEmpty()) {
                application.notify("File Explorer", "No ReStudio Servers Are Available", ReSyncNotificationLevel.WARN);
                return;
            }
            BrowserRemotelyServerApi browserApi = browserApi();
            if (browserApi == null) {
                unavailable(Action.FILE_EXPLORER);
                return;
            }
            List<BrowserServerFileRootProvider.ServerRoot> roots = available.stream().map(server ->
                    new BrowserServerFileRootProvider.ServerRoot(serverId(server), server.name,
                            new BrowserServerFileSystemProvider(this, browserApi, capabilities(server), server))).toList();
            RemotePath root = RemotePath.root();
            application.setScreen(new FileExplorerScreen(current, null, root, root, false, new BrowserServerFileRootProvider(roots)));
        }));
    }

    @Override
    public TerminalSessionProvider terminalProvider(RemotelyServerApi api, ServerModels.ClientServerView server) {
        if (server == null || !(serverApi instanceof BrowserRemotelyServerApi browserApi)) {
            return null;
        }
        String serverId = server.identifier == null || server.identifier.isBlank() ? server.uuid : server.identifier;
        return serverId == null || serverId.isBlank() ? null : browserApi.terminalSessionProvider(serverId);
    }

    @Override
    public ServerTerminalPlatform terminalPlatform(RemotelyServerApi api, ServerModels.ClientServerView server) {
        NexoGlyphPreviewAccess access = glyphPreview(server);
        return access == null ? ServerTerminalPlatform.NONE : new BrowserServerTerminalPlatform(access, this::glyphPreviewMode);
    }

    @Override
    public void configureTerminalInput(RemotelyServerApi api, ServerModels.ClientServerView server, TerminalWidget terminal) {
        if (terminal == null) return;
        if (api == null || server == null || serverId(server).isBlank()) {
            terminal.disableFakeInput();
            return;
        }
        terminal.enableFakeInput("> ", command -> api.sendServerCommand(serverId(server), command).whenComplete((ignored, failure) -> {
            if (failure == null) return;
            execute(() -> application.notify("Command Failed", failureMessage(failure), ReSyncNotificationLevel.ERROR));
        }));
    }

    @Override
    public ResourceContainerAdapter createResourceContainer(ReScreen host, Object instance, ServerModels.ClientServerView server,
                                                            int x, int y, int width, int height) {
        BrowserRemotelyServerApi browserApi = browserApi();
        String id = serverId(server);
        if (browserApi == null || id.isBlank()) {
            return null;
        }
        ResourceMarketplaceProviderAdapter marketplace = resourceMarketplace();
        HostedResourceContext context = new HostedResourceContext(marketplace, browserApi, id,
                server == null ? null : server.version, server == null ? null : server.loader, this, configStore(), capabilities(server), server);
        HostedResourceContainerProvider provider = new HostedResourceContainerProvider(this, server, marketplace, context);
        ResourceContainer container = new ResourceContainer(host, provider, x, y, width, height, InstanceResourceWidget::new, true);
        return new CanonicalResourceContainerAdapter(container);
    }

    @Override
    public Async<Void> setServerPower(RemotelyServerApi api, ServerModels.ClientServerView server, String signal) {
        HostContext context = captureContext();
        String capability = "kill".equalsIgnoreCase(signal) ? "server.kill" : "server.lifecycle";
        return capabilityOperation(server, capability, () -> serverApi.setServerPower(serverId(server), signal)
                .thenApply(ignored -> {
                    if (isCurrent(context)) updateServerState(server, requestedState(signal));
                    return null;
                }));
    }

    @Override
    public ServerUiCapabilityProvider.Availability killAvailability(Object target) {
        ServerModels.ClientServerView server = serverView(target);
        return server == null ? ServerUiCapabilityProvider.Availability.missing("Server Kill Is Unavailable")
                : capabilities(server).availability(server, "server.kill");
    }

    @Override
    public Async<ServerModels.ServerStatus> serverStatus(RemotelyServerApi api, ServerModels.ClientServerView server) {
        HostContext context = captureContext();
        if (serverApi == null || serverId(server).isBlank() || !isCurrent(context)) {
            return Async.failed(new UnsupportedOperationException("Server Status Is Unavailable"));
        }
        return observeSessionFailure(serverApi.getServerStatus(serverId(server)).thenApply(status -> {
            if (status != null && isCurrent(context)) {
                updateServerState(server, status.installing ? ServerState.INSTALLING : ServerState.parse(status.currentState));
            }
            return status;
        }), context);
    }

    @Override
    public ServerState state(Object target) {
        ServerModels.ClientServerView server = serverView(target);
        if (server == null) return ServerState.UNKNOWN;
        String id = serverId(server);
        if (id.isBlank()) return ServerState.UNKNOWN;
        ServerState cached = serverStates.get(id);
        if (cached != null) return cached;
        if (server.isInstalling) return ServerState.INSTALLING;
        if (server.environment != null) {
            String value = server.environment.get("state");
            if (value == null || value.isBlank()) value = server.environment.get("currentState");
            if (value == null || value.isBlank()) value = server.environment.get("current_state");
            ServerState environmentState = ServerState.parse(value);
            if (environmentState != ServerState.UNKNOWN) return environmentState;
        }
        if (target instanceof BrowserServerConfigurationTarget configuration) {
            return ServerState.parse(configuration.stateValue());
        }
        requestInitialState(server);
        return ServerState.UNKNOWN;
    }

    @Override
    public void setState(Object target, ServerState state) {
        updateServerState(serverView(target), state);
    }

    @Override
    public void addStateListener(Object target, Consumer<ServerState> listener) {
        String id = serverId(serverView(target));
        if (id.isBlank() || listener == null) return;
        List<Consumer<ServerState>> listeners = stateListeners.computeIfAbsent(id, ignored -> new ArrayList<>());
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    @Override
    public void removeStateListener(Object target, Consumer<ServerState> listener) {
        String id = serverId(serverView(target));
        if (id.isBlank() || listener == null) return;
        List<Consumer<ServerState>> listeners = stateListeners.get(id);
        if (listeners == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty()) stateListeners.remove(id);
    }

    @Override
    public Async<ServerScreenHost.ServerHealth> serverHealth(ServerModels.ClientServerView server) {
        BrowserRemotelyServerApi api = browserApi();
        HostContext context = captureContext();
        if (api == null || serverId(server).isBlank() || !isCurrent(context)) {
            return Async.failed(new UnsupportedOperationException("Server Health Is Unavailable"));
        }
        return observeSessionFailure(api.getServerHealth(serverId(server)).thenApply(health -> {
            if (!isCurrent(context)) throw new IllegalStateException("Browser Session Expired");
            if (health == null) throw new UnsupportedOperationException("Server Health Is Unavailable");
            ServerScreenHost.PrerequisiteState eula = hostedPrerequisite(health.eulaState(), health.eulaAccepted());
            ServerScreenHost.PrerequisiteState serverJar = hostedPrerequisite(health.serverJarState(), health.hasServerJar());
            ServerScreenHost.PrerequisiteState startScript = hostedPrerequisite(health.startScriptState(), health.hasStartScript());
            boolean healthy = health.healthy() != null && health.healthy();
            return new ServerScreenHost.ServerHealth(eula, serverJar, startScript, healthy);
        }), context);
    }

    private static ServerScreenHost.PrerequisiteState hostedPrerequisite(String state, Boolean value) {
        if (state != null && !state.isBlank()) {
            try {
                return ServerScreenHost.PrerequisiteState.valueOf(state.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ServerScreenHost.PrerequisiteState.UNAVAILABLE;
            }
        }
        if (value == null) return ServerScreenHost.PrerequisiteState.UNAVAILABLE;
        return value ? ServerScreenHost.PrerequisiteState.VERIFIED : ServerScreenHost.PrerequisiteState.FAILED;
    }

    @Override
    public Async<Void> sendServerCommand(RemotelyServerApi api, ServerModels.ClientServerView server, String command) {
        return capabilityOperation(server, "console.command", () -> serverApi.sendServerCommand(serverId(server), command));
    }

    @Override
    public Async<Void> repairServer(ServerModels.ClientServerView server, String repair) {
        return Async.failed(new UnsupportedOperationException("Server Repair Is Unavailable In The Browser"));
    }

    @Override
    public void openFileExplorer(Screen current, ServerModels.ClientServerView server) {
        if (server == null) {
            unavailable(Action.FILE_EXPLORER);
            return;
        }
        withCapability(server, "files.list", () -> {
            BrowserRemotelyServerApi browserApi = browserApi();
            if (browserApi == null) {
                unavailable(Action.FILE_EXPLORER);
                return;
            }
            RemotePath root = RemotePath.root();
            RemoteFileSystemProvider provider = new BrowserServerFileSystemProvider(this, browserApi, capabilities(server), server);
            application.setScreen(new FileExplorerScreen(current, null, root, root, false,
                    provider));
        });
    }

    @Override
    public void openFileExplorer(Screen current, Object instance) {
        ServerModels.ClientServerView server = serverView(instance);
        if (server == null) {
            unavailable(Action.FILE_EXPLORER);
            return;
        }
        openFileExplorer(current, server);
    }

    @Override
    public void openDevelopment(Screen current, ServerModels.ClientServerView server) {
        if (demo()) {
            unavailable(Action.DEVELOPMENT);
            return;
        }
        resolveDevelopmentBinding(server, binding -> {
            if (binding == null) {
                unavailable(Action.DEVELOPMENT);
                return;
            }
            DeveloperCapabilityProvider provider = developerProviders.get(developmentKey(serverId(server)));
            if (provider == null || !provider.capability(CapabilityIds.DEVELOPER).available()
                    || !provider.capability(CapabilityIds.FILES).available()) {
                unavailable(Action.DEVELOPMENT);
                return;
            }
            application.setScreen(new FileEditorScreen(current, server, provider, binding.root().path(), null, binding.root().path()));
        });
    }

    @Override
    public void openReSyncStudio(Screen current, ServerModels.ClientServerView server) {
        if (demo()) {
            unavailable(Action.RESYNC_STUDIO);
            return;
        }
        if (flowManager == null) {
            unavailable(Action.RESYNC_STUDIO);
            return;
        }
        flowManager.openReSyncStudio(serverId(server), server, "", server.name);
    }

    @Override
    public void openNetworkSettings(Screen current, String networkId) {
        if (demo()) {
            unavailable(Action.NETWORK_SETTINGS);
            return;
        }
        if (!authenticated()) {
            unavailable(Action.NETWORK_SETTINGS);
            return;
        }
        application.setScreen(new NetworkOverviewScreen(current, NetworkOverviewProvider.forClient(remotelyClient), networkId));
    }


    @Override
    public void openServerConfiguration(Screen current, ServerModels.ClientServerView server) {
        if (demo()) {
            unavailable(Action.SERVER_CONFIGURATION);
            return;
        }
        withCapability(server, "settings.read", () -> {
            openServerConfiguration(current, (Object) server);
        });
    }

    @Override
    public void openServerConfiguration(Screen current, Object instance) {
        ServerModels.ClientServerView server = serverView(instance);
        if (server == null) {
            unavailable(Action.SERVER_CONFIGURATION);
            return;
        }
        application.setScreen(new ServerConfigurationScreen(current, server, null, remotelyClient));
    }

    @Override
    public ServerConfigurationTarget configurationTarget(Object value) {
        if (value instanceof BrowserServerConfigurationTarget target) return target;
        if (value instanceof ServerModels.ClientServerView server) return new BrowserServerConfigurationTarget(server);
        return ServerConfigurationTarget.unavailable(value);
    }

    @Override
    public ServerConfigurationTarget copyConfigurationTarget(Object value, String name) {
        ServerConfigurationTarget target = configurationTarget(value);
        return target instanceof BrowserServerConfigurationTarget browser ? browser.copy(name) : target;
    }

    @Override
    public ServerConfigurationTarget createConfigurationTarget() {
        return BrowserServerConfigurationTarget.create();
    }

    @Override
    public void configureTargetDefaults(ServerConfigurationTarget target) {
        if (!(target instanceof BrowserServerConfigurationTarget browser)) return;
        browser.backend("RESTUDIO", Map.of());
        browser.property("server-port", "25565");
    }

    @Override
    public void applyTargetPreset(ServerConfigurationTarget target, Object preset) {
        if (!(target instanceof BrowserServerConfigurationTarget browser)
                || !(preset instanceof HostedResourceContext.ModpackSelection modpack)) {
            ServerScreenHost.super.applyTargetPreset(target, preset);
            return;
        }
        browser.linkModpack(modpack.provider(), modpack.projectId(), modpack.versionId(), modpack.versionNumber());
        if (modpack.loader() != null && !modpack.loader().isBlank()) {
            browser.softwareValue(modpack.loader());
        }
    }

    @Override
    public ServerScreenHost.ConfigurationValues configurationValues(Object value) {
        if (!(value instanceof BrowserServerConfigurationTarget target)) return ServerScreenHost.super.configurationValues(value);
        return new ServerScreenHost.ConfigurationValues(target.name(), target.version(), target.software(), target.software(),
                target.build(), target.linkedModpack(), false, false, target.properties());
    }

    @Override
    public void setConfigurationName(Object value, String name) {
        configurationTarget(value).name(name);
    }

    @Override
    public void setConfigurationState(Object value, String state) {
        ServerConfigurationTarget target = configurationTarget(value);
        target.state(state);
        if (target instanceof BrowserServerConfigurationTarget browser) {
            updateServerState(browser.view(), ServerState.parse(state));
        }
    }

    @Override
    public void copyConfigurationState(Object source, Object destination) {
        ServerConfigurationTarget from = configurationTarget(source);
        ServerConfigurationTarget to = configurationTarget(destination);
        if (from instanceof BrowserServerConfigurationTarget sourceTarget && to instanceof BrowserServerConfigurationTarget target) {
            target.name(sourceTarget.name());
            target.backend(sourceTarget.backendType(), sourceTarget.backendCredentials());
            target.versionValue(sourceTarget.version());
            target.softwareValue(sourceTarget.software());
            target.buildValue(sourceTarget.build());
            sourceTarget.properties().forEach(target::property);
        }
    }

    @Override
    public void removeConfigurationProperty(Object value, String key) {
        configurationTarget(value).removeProperty(key);
    }

    @Override
    public void setConfigurationProperty(Object value, String key, String propertyValue) {
        configurationTarget(value).property(key, propertyValue);
    }

    @Override
    public void recordConfigurationLog(Object value, String message) {
        configurationTarget(value).log(message);
    }

    @Override
    public Async<Void> loadInstanceProperties(Object value, boolean remote) {
        if (!(value instanceof BrowserServerConfigurationTarget target)) return Async.completed(null);
        HostContext context = captureContext();
        BrowserRemotelyServerApi browserApi = browserApi();
        String id = target.id();
        if (browserApi == null || id.isBlank()) return Async.completed(null);
        return capabilityOperation(target.view(), "files.read", () -> browserApi.getFileContentAllowMissing(id, "server.properties")
                .thenAccept(content -> {
                    if (isCurrent(context)) target.replaceProperties(parseProperties(content));
                })).handle((ignored, failure) -> {
                    if (failure != null && isSessionExpired(failure)) {
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException(failure);
                    }
                    if (failure == null || !isCurrent(context)) return null;
                    application.notify("Server Properties Unavailable", "Could Not Load Server Properties. Existing Values Were Kept.",
                            ReSyncNotificationLevel.WARN);
                    return null;
                });
    }

    @Override
    public <T> Async<T> configurationLoad(String operation, Async<T> load, Supplier<T> fallback) {
        HostContext context = captureContext();
        return load.handle((value, failure) -> {
            if (failure != null && isSessionExpired(failure)) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure);
            }
            if (failure == null) return value;
            if (!isRecoverableConfigurationFailure(failure)) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure);
            }
            if (!isCurrent(context)) return fallback.get();
            application.notify("Server Configuration Partially Unavailable",
                    (operation == null || operation.isBlank() ? "Some Configuration" : operation) + " Could Not Be Loaded. Available Settings Remain Open.",
                    ReSyncNotificationLevel.WARN);
            return fallback.get();
        });
    }

    @Override
    public Async<Void> reloadInstanceSettings(Object value, boolean remote) {
        return Async.completed(null);
    }

    @Override
    public ServerSettingsDataController createServerSettingsController(Object value, ServerSettingsSnapshot snapshot) {
        if (!(value instanceof BrowserServerConfigurationTarget target) || browserApi() == null || target.id().isBlank()) {
            return ServerSettingsDataController.unavailable();
        }
        ServerSettingsRegistry registry = ServerSettingsRegistry.getInstance();
        if (registry.snapshot().packs().isEmpty()) BundledServerSettingsRegistry.loadInto(registry, new BrowserSafeYamlServerSettingsMetadataParser());
        BrowserRemotelyServerApi api = browserApi();
        ServerSettingsDocumentStore store = new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                HostContext context = captureContext();
                if (!isCurrent(context)) return Async.failed(new IllegalStateException("Browser Session Expired"));
                return observeSessionFailure(api.getFileContentAllowMissing(target.id(), relativePath)
                        .thenApply(content -> content == null ? Document.missing() : new Document(true, content)), context);
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                HostContext context = captureContext();
                if (!isCurrent(context)) return Async.failed(new IllegalStateException("Browser Session Expired"));
                return observeSessionFailure(api.writeFile(target.id(), relativePath, content), context);
            }
        };
        return new ServerSettingsDocumentDataController(target, registry.snapshot(target), store, BrowserSafeYaml::parse);
    }

    @Override
    public ServerScreenHost.ConfigurationUi createConfigurationUi(Screen owner, ConfigurationState state,
                                                                  ServerSettingsDataController settingsController,
                                                                  Map<String, String> remoteVariables,
                                                                  List<String> extraFiles,
                                                                  Runnable reloadDataDrivenSettings,
                                                                  BooleanSupplier allowServerSoftwareChange) {
        if (state == null || !(state.draft() instanceof BrowserServerConfigurationTarget target)) {
            return ServerScreenHost.super.createConfigurationUi(owner, state, settingsController, remoteVariables, extraFiles,
                    reloadDataDrivenSettings, allowServerSoftwareChange);
        }
        BrowserServerConfigurationTarget original = state.original() instanceof BrowserServerConfigurationTarget value ? value : null;
        BrowserServerConfigurationTarget managed = original == null ? target : original;
        BrowserRemotelyServerApi api = browserApi();
        VersionSettingsCatalog catalog = new VersionSettingsCatalog() {
            @Override public Async<List<GameVersion>> gameVersions() { return api.getCatalogGameVersions(); }
            @Override public Async<List<ModLoaderVersion>> modLoaderVersions(restudio.rebase.instance.loaders.ModLoader loader, String gameVersion) {
                return api.getCatalogModLoaderVersions(loader, gameVersion);
            }
            @Override public Async<Map<String, Software>> software() { return api.getCatalogSoftware(); }
            @Override public Async<Map<String, Version>> versions(String software) { return api.getCatalogVersions(software); }
            @Override public Async<List<Build>> builds(String software, String version) { return api.getCatalogBuilds(software, version); }
        };
        DiscordRpcSettingsController.InstanceSettings discord = new DiscordRpcSettingsController.InstanceSettings() {
            @Override public String get(String key, String fallback) { return target.property(key) == null ? fallback : target.property(key); }
            @Override public void set(String key, String value) { target.property(key, value); }
            @Override public void remove(String key) { target.removeProperty(key); }
        };
        ServerExtraSettingsController.DocumentAccess documents = new ServerExtraSettingsController.DocumentAccess() {
            @Override public Async<String> read(String relativePath) {
                return capabilityOperation(target.view(), "files.read", () -> api.getFileContent(target.id(), relativePath));
            }
            @Override public Async<Void> write(String relativePath, String content) {
                return capabilityOperation(target.view(), "files.write", () -> api.writeFile(target.id(), relativePath, content));
            }
            @Override public Async<Void> write(String relativePath, String expectedContent, String content) {
                return capabilityOperation(target.view(), "files.read", () -> api.getFileContent(target.id(), relativePath)).thenCompose(current -> {
                    if (!Objects.equals(current, expectedContent)) {
                        return Async.failed(new IllegalStateException("Configuration File Changed. Reopen It And Try Again"));
                    }
                    return capabilityOperation(target.view(), "files.write", () -> api.writeFile(target.id(), relativePath, content));
                });
            }
        };
        ServerLiveSettingsProvider liveSettings = browserLiveSettingsProvider(target);
        ServerConfigurationUiPlatform platform = new ServerConfigurationUiPlatform() {
            @Override public Object target() { return target; }
            @Override public Object originalTarget() { return original == null ? target : original; }
            @Override public String name() { return target.name(); }
            @Override public boolean linkedModpack() { return target.linkedModpack(); }
            @Override public boolean managementCompatible() { return VersionUtil.isMSMPCompatible(target.version()); }
            @Override public boolean managementEnabled() { return Boolean.parseBoolean(target.property("management-server-enabled")); }
            @Override public VersionSettingsTarget versionTarget() { return target; }
            @Override public VersionSettingsCatalog versionCatalog() { return catalog; }
            @Override public ModpackSettingsTarget modpackTarget() { return target; }
            @Override public ModpackSettingsTarget managedModpackTarget() { return managed; }
            @Override public ModpackSettingsProvider modpackProvider() { return browserModpackProvider(target); }
            @Override public DiscordRpcSettingsController.InstanceSettings discordSettings() { return discord; }
            @Override public SettingsActionCapability discordCapability() { return SettingsActionCapability.supported("settings.discord-rpc"); }
            @Override public ServerFeatureSettingsProvider featureSettingsProvider() { return new BrowserServerFeatureSettingsProvider(target); }
            @Override public ServerGeneralSettingsProvider generalSettingsProvider() { return new BrowserServerGeneralSettingsProvider(target); }
            @Override public ServerManagementSettingsProvider managementSettings() { return new BrowserServerManagementSettingsProvider(target); }
            @Override public ServerPlanSettingsProvider planSettingsProvider() { return api::getPlans; }
            @Override public ServerJvmSettingsProvider jvmSettingsProvider() { return null; }
            @Override public BackupSettingsProvider backupProvider() {
                return BackupSettingsProvider.managed(owner, BrowserHostedSettingsProviders.backups(api, target.id()));
            }
            @Override public AsyncServerScheduleFeature scheduleProvider() {
                return BrowserHostedSettingsProviders.schedules(api, target.id());
            }
            @Override public PortManagementSettingsProvider portProvider() { return BrowserHostedSettingsProviders.ports(api, target.id()); }
            @Override public SubuserSettingsProvider subuserProvider() { return BrowserHostedSettingsProviders.subusers(api, target.id()); }
            @Override public PlayerActionsFileProvider playerActionsFileProvider() { return new BrowserPlayerActionsFileProvider(api, target.id()); }
            @Override public ServerLiveSettingsProvider liveSettingsProvider() { return liveSettings; }
            @Override public ServerExtraSettingsController.DocumentAccess documentAccess() { return documents; }
        };
        return ServerConfigurationUiComposition.create(owner, state, settingsController, remoteVariables, extraFiles,
                reloadDataDrivenSettings, allowServerSoftwareChange, defaultInstanceLocation(), platform);
    }

    private ServerLiveSettingsProvider browserLiveSettingsProvider(BrowserServerConfigurationTarget target) {
        if (flowManager == null) return new UnavailableServerLiveSettingsProvider("ReSync Live Settings Transport Is Unavailable");
        ServerUiCapabilityProvider provider = capabilities(target.view());
        ServerUiCapabilityProvider.Availability gameRules = provider.availability(target.view(), "settings.game-rules.live");
        if (!gameRules.available()) return new UnavailableServerLiveSettingsProvider(gameRules.reason());
        ServerUiCapabilityProvider.Availability liveSettings = provider.availability(target.view(), "settings.server.live");
        if (!liveSettings.available()) return new UnavailableServerLiveSettingsProvider(liveSettings.reason());
        return new BrowserReSyncServerLiveSettingsProvider(flowManager, target.id());
    }

    private ModpackSettingsProvider browserModpackProvider(BrowserServerConfigurationTarget serverTarget) {
        ResourceMarketplaceProviderAdapter marketplace = resourceMarketplace();
        return new ModpackSettingsProvider() {
            @Override public Async<Resource> resource(String provider, String projectId) {
                if (provider == null || provider.isBlank() || projectId == null || projectId.isBlank()) return Async.completed(null);
                return marketplace.details(provider, projectId).thenApply(details -> details == null || details.card() == null
                        ? null : new Resource(provider, marketplace.online(details.card())));
            }

            @Override public Profile localProfile(ModpackSettingsTarget target) { return null; }

            @Override public void openOverview(Screen parent, ModpackSettingsTarget target, Resource resource, boolean replaceable, Runnable changed) {
                if (resource == null || resource.value() == null) return;
                marketplace.details(resource.provider(), resource.value().id).thenAccept(details -> execute(() -> {
                    if (details == null || details.card() == null) return;
                    HostedResourceContext context = modpackContext(browserModpackTarget(target, serverTarget));
                    context.openResource(parent, marketplace, details.card(), ResourceType.MODPACK, true,
                            BrowserServerScreenHost.this, true, replaceable, changed);
                }));
            }

            @Override public void openBrowser(Screen parent, ModpackSettingsTarget target, Runnable changed) {
                application.setScreen(ResourceBrowserScreen.forModpackReplacement(parent, marketplace,
                        modpackContext(browserModpackTarget(target, serverTarget)), changed));
            }

            @Override public Async<Void> unlock(ModpackSettingsTarget target) {
                String provider = target.modpackProvider();
                if (provider == null || provider.isBlank()) return Async.failed(new IllegalStateException("Linked Modpack Provider Is Unavailable"));
                return modpackContext(browserModpackTarget(target, serverTarget)).unlockModpack(provider);
            }

            @Override public void invalidate(ModpackSettingsTarget target) {
            }
        };
    }

    private BrowserServerConfigurationTarget browserModpackTarget(ModpackSettingsTarget target, BrowserServerConfigurationTarget fallback) {
        return target instanceof BrowserServerConfigurationTarget browser ? browser : fallback;
    }

    private HostedResourceContext modpackContext(BrowserServerConfigurationTarget target) {
        return new HostedResourceContext(resourceMarketplace(), browserApi(), target.id(), target.version(), target.software(), this,
                configStore(), capabilities(target.view()), target.view());
    }

    @Override
    public Async<List<String>> listInstanceFiles(Object value) {
        if (!(value instanceof BrowserServerConfigurationTarget target) || browserApi() == null || target.id().isBlank()) {
            return Async.completed(List.<String>of());
        }
        HostContext context = captureContext();
        return capabilityOperation(target.view(), "files.list", () -> browserApi().listFiles(target.id(), "/")
                .thenApply(files -> files == null ? List.<String>of() : files.stream().filter(Objects::nonNull)
                        .map(file -> file.name == null ? "" : file.name).filter(name -> !name.isBlank()).toList()))
                .handle((files, failure) -> {
                    if (failure != null && isSessionExpired(failure)) {
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        throw new IllegalStateException(failure);
                    }
                    if (failure == null) return files == null ? List.<String>of() : files;
                    if (!isCurrent(context)) return List.<String>of();
                    application.notify("Server File Discovery Unavailable", "Could Not Load The Server File List. Settings Remain Available.",
                            ReSyncNotificationLevel.WARN);
                    return List.<String>of();
                });
    }

    @Override
    public Async<Void> saveInstanceConfiguration(Object value, ServerSettingsDataController settingsController) {
        if (!(value instanceof BrowserServerConfigurationTarget target) || browserApi() == null || target.id().isBlank()) {
            return Async.failed(new UnsupportedOperationException("Hosted Server Configuration Is Unavailable"));
        }
        BrowserRemotelyServerApi api = browserApi();
        return capabilityOperation(target.view(), "settings.write", () -> api.renameServer(target.id(), target.name()).thenApply(ignored -> null));
    }

    @Override
    public Async<Void> applyInstanceEdit(Object original, Object template, String newName,
                                         boolean repairStartScript, boolean reinstallSoftware,
                                         Notification notification,
                                         ServerSettingsDataController settingsController) {
        if (!(template instanceof BrowserServerConfigurationTarget target) || browserApi() == null || target.id().isBlank()) {
            return Async.failed(new UnsupportedOperationException("Hosted Server Configuration Is Unavailable"));
        }
        BrowserRemotelyServerApi api = browserApi();
        Async<Void> result = capabilityOperation(target.view(), "settings.write",
                () -> api.renameServer(target.id(), newName == null ? target.name() : newName));
        if (reinstallSoftware) {
            result = result.thenCompose(ignored -> capabilityOperation(target.view(), "settings.write",
                    () -> api.reinstallServer(target.id())));
        }
        return settingsController == null ? result : result.thenCompose(ignored -> settingsController.save(target));
    }

    @Override
    public Async<Void> writeInstanceFile(Object value, String path, String content) {
        if (!(value instanceof BrowserServerConfigurationTarget target) || browserApi() == null || target.id().isBlank()) {
            return Async.failed(new UnsupportedOperationException("Hosted Server Files Are Unavailable"));
        }
        return capabilityOperation(target.view(), "files.write", () -> browserApi().writeFile(target.id(), path, content));
    }

    @Override
    public Async<ServerModels.CheckoutResponse> createHostedCheckout(String serverName, String planName,
                                                                       Map<String, String> environment,
                                                                       Map<String, String> fileConfigs,
                                                                       String subdomain,
                                                                       ServerModels.CustomPlanRequest customPlan) {
        BrowserRemotelyServerApi api = browserApi();
        return api == null ? Async.failed(new UnsupportedOperationException("Hosted Checkout Is Unavailable"))
                : api.createHostedCheckout(serverName, planName, environment, fileConfigs, subdomain, customPlan);
    }

    @Override
    public Async<Void> renameInstance(Object instance, String name) {
        ServerModels.ClientServerView server = serverView(instance);
        if (server == null || serverId(server).isBlank()) {
            return Async.failed(new IllegalArgumentException("Server Identifier Is Unavailable"));
        }
        String previousName = server.name;
        return capabilityOperation(server, "settings.write", () -> serverApi.renameServer(serverId(server), name))
                .thenApply(ignored -> {
                    server.name = name;
                    return (Void) null;
                }).exceptionally(failure -> {
                    server.name = previousName;
                    throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
                });
    }

    @Override
    public Async<Void> renameRemoteServer(Object instance, String name) {
        return Async.completed(null);
    }

    private void resolveDevelopmentBinding(ServerModels.ClientServerView server,
                                           Consumer<DeveloperCapabilityProvider.Workspace.Binding> completion) {
        BrowserRemotelyServerApi api = browserApi();
        String id = serverId(server);
        HostContext context = captureContext();
        if (api == null || id.isBlank() || !isCurrent(context)) {
            if (completion != null) completion.accept(null);
            return;
        }
        String key = developmentKey(id);
        DeveloperCapabilityProvider provider = developerProviders.computeIfAbsent(key, ignored -> api.developer(id));
        DeveloperCapabilityProvider.Workspace.Binding cached = developerBindings.get(key);
        if (resolvedDeveloperBindings.contains(key)) {
            if (completion != null) completion.accept(cached);
            return;
        }
        if (completion != null) developerBindingWaiters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(completion);
        if (developerBindingRequests.putIfAbsent(key, context) != null) return;
        provider.workspace().current().whenComplete((binding, failure) -> application.execute(() -> {
            if (!sameHostContext(context, developerBindingRequests.get(key)) || !isCurrent(context)) return;
            developerBindingRequests.remove(key);
            if (failure == null) resolvedDeveloperBindings.add(key);
            if (failure == null && binding != null) developerBindings.put(key, binding);
            else developerBindings.remove(key);
            if (failure == null && binding != null && application.getCurrentScreen() instanceof ServerDetailsScreen details) {
                details.refreshDevelopmentAvailability(server);
            }
            List<Consumer<DeveloperCapabilityProvider.Workspace.Binding>> waiters = developerBindingWaiters.remove(key);
            if (waiters != null) waiters.forEach(waiter -> waiter.accept(failure == null ? binding : null));
        }));
    }

    private String developmentKey(String serverId) {
        BrowserLaunchSession.Metadata session = BrowserLaunchSession.metadata();
        return firstNonBlank(session.subjectId(), session.grantId()) + ":" + serverId;
    }

    @Override
    public void openWorld(Screen current, ServerModels.ClientServerView server) {
        if (demo()) {
            unavailable(Action.WORLD);
            return;
        }
        withCapability(server, "world.map", () -> {
            if (flowManager == null) {
                unavailable(Action.WORLD);
                return;
            }
            flowManager.openWorldMap(serverId(server), server, "world");
        });
    }

    @Override
    public void duplicateServer(Screen current, ServerModels.ClientServerView server) {
        HostContext context = captureContext();
        if (!isCurrent(context)) return;
        String name = server == null || server.name == null || server.name.isBlank() ? "Server Copy" : server.name + " Copy";
        withCapability(server, "server.duplicate", () -> serverApi.duplicateServer(serverId(server), name, UUID.randomUUID().toString()).whenComplete((copy, failure) ->
                execute(() -> {
                    if (!isCurrent(context)) return;
                    if (failure != null) {
                        application.notify("Duplicate Failed", failureMessage(failure), ReSyncNotificationLevel.ERROR);
                        return;
                    }
                    handleDuplicate(serverId(server), copy, false, context);
                })));
    }

    private void handleDuplicate(String serverId, RemotelyServerApi.DuplicateServerResult result, boolean polling, HostContext context) {
        if (!isCurrent(context)) return;
        if (result == null) {
            application.notify("Duplicate Failed", "Duplicate Status Unavailable", ReSyncNotificationLevel.ERROR);
            return;
        }
        if (result.checkoutUrl() != null && !result.checkoutUrl().isBlank()) {
            application.setClipboard(result.checkoutUrl());
            application.notify("Checkout Link Copied", "Complete Checkout To Create The Server", ReSyncNotificationLevel.SUCCESS);
            return;
        }
        String status = result.status() == null ? "" : result.status().toUpperCase();
        if ("COMPLETED".equals(status)) {
            application.notify("Server Duplicated", "Server Is Ready", ReSyncNotificationLevel.SUCCESS);
            return;
        }
        if ("FAILED".equals(status) || "EXPIRED".equals(status)) {
            application.notify("Duplicate Failed", result.phase() == null || result.phase().isBlank() ? status : result.phase(), ReSyncNotificationLevel.ERROR);
            return;
        }
        if (!polling) application.notify("Server Duplicate", "Server Duplication Started", ReSyncNotificationLevel.INFO);
        if (result.intentId() == null || result.intentId().isBlank()) return;
        schedule(() -> {
            if (!isCurrent(context)) return;
            serverApi.duplicateStatus(serverId, result.intentId()).whenComplete((next, failure) -> execute(() -> {
                if (!isCurrent(context)) return;
                if (failure != null) {
                    application.notify("Duplicate Status", failureMessage(failure), ReSyncNotificationLevel.WARN);
                    return;
                }
                handleDuplicate(serverId, next, true, context);
            }));
        }, Duration.ofSeconds(2));
    }

    @Override
    public void deleteServer(Screen current, ServerModels.ClientServerView server) {
        HostContext context = captureContext();
        if (!isCurrent(context)) return;
        withCapability(server, "server.delete", () -> serverApi.deleteServer(serverId(server), server == null ? "" : server.name).whenComplete((ignored, failure) ->
                execute(() -> {
                    if (!isCurrent(context)) return;
                    if (failure != null) {
                        application.notify("Delete Failed", failureMessage(failure), ReSyncNotificationLevel.ERROR);
                        return;
                    }
                    capabilityProviders.remove(serverId(server));
                    application.notify("Server Deleted", "Server Deleted", ReSyncNotificationLevel.SUCCESS);
                    if (remotelyClient != null) remotelyClient.openServerManager(null);
                })));
    }

    @Override
    public void createServer(Screen current) {
        if (demo()) {
            unavailable(Action.CREATE_SERVER);
            return;
        }
        if (remotelyClient == null) {
            unavailable(Action.CREATE_SERVER);
            return;
        }
        application.setScreen(new ServerConfigurationScreen(current, null, null, remotelyClient, true, null));
    }

    @Override
    public void importServer(Screen current) {
        if (demo()) {
            unavailable(Action.IMPORT_SERVER);
            return;
        }
        unavailable(Action.IMPORT_SERVER);
    }

    @Override
    public void openRemoteHost(Screen current) {
        unavailable(Action.REMOTE_HOST);
    }

    @Override
    public void openInbox(Screen current) {
        application.setScreen(new InboxScreen(current));
    }

    @Override
    public void openSettings(Screen current) {
        if (demo()) {
            unavailable(Action.HOST_SETTINGS);
            return;
        }
        if (!(current instanceof ReScreen parent)) {
            unavailable(Action.HOST_SETTINGS);
            return;
        }
        Object configured = remotelyClient == null ? null : remotelyClient.getComposition().configManager();
        if (!(configured instanceof BrowserRemotelyConfigStore config)) {
            unavailable(Action.HOST_SETTINGS);
            return;
        }
        application.setScreen(SettingsScreenFactory.createGlobalSettingsScreen(parent, config,
                BrowserGlobalSettingsProviders.create(parent, config)));
    }

    @Override
    public void openReactorPlan(Screen current, ServerScreenHost.PlanView plan) {
        if (plan == null || plan.name().isBlank()) {
            openReactorPlans(current);
            return;
        }
        if (remotelyClient == null) {
            unavailable(Action.REACTOR_PLANS);
            return;
        }
        application.setScreen(new ServerConfigurationScreen(current, null, null, remotelyClient, true, plan.name()));
    }

    @Override
    public void openReactorPlans(Screen current) {
        if (demo()) {
            unavailable(Action.REACTOR_PLANS);
            return;
        }
        application.hostActionHandler().openBrowser("https://restudiomc.net/hosting");
    }

    @Override
    public void openPanel(Screen current, ServerScreenHost.HostView host) {
        if (demo()) {
            unavailable(Action.OPEN_PANEL);
            return;
        }
        if (host == null || host.address().isBlank()) {
            unavailable(Action.OPEN_PANEL);
            return;
        }
        application.hostActionHandler().openBrowser(host.address());
    }

    @Override
    public void openModpackBrowser(Screen current) {
        if (demo()) {
            unavailable(Action.MODPACK_SERVER);
            return;
        }
        application.notify("Modpack Browser Unavailable", "Browser Modpack Installation Requires A Server Target",
                ReSyncNotificationLevel.WARN);
    }

    @Override
    public void openModpackBrowser(Screen current, ServerScreenHost.HostView remoteHost, boolean reStudioContext) {
        BrowserRemotelyServerApi api = browserApi();
        if (!authenticated() || !reStudioContext || api == null || remotelyClient == null) {
            unavailable(Action.MODPACK_SERVER);
            return;
        }
        ResourceMarketplaceProviderAdapter marketplace = resourceMarketplace();
        HostedResourceContext context = new HostedResourceContext(marketplace, api, "", "", "", this,
                configStore(), null, null, selection -> application.setScreen(
                        new ServerConfigurationScreen(current, remotelyClient, true, selection)));
        application.setScreen(new ResourceBrowserScreen(current, marketplace, context, ResourceType.MODPACK, true,
                remoteHost, true, null));
    }

    void openServerResourceBrowser(Screen current, ServerModels.ClientServerView server, ResourceType type) {
        BrowserRemotelyServerApi api = browserApi();
        String id = serverId(server);
        if (api == null || id.isBlank()) {
            unavailable(Action.MODPACK_SERVER);
            return;
        }
        ResourceMarketplaceProviderAdapter marketplace = resourceMarketplace();
        ResourceBrowserContext context = new HostedResourceContext(marketplace, api, id, server.version, server.loader, this,
                configStore(), capabilities(server), server);
        application.setScreen(new ResourceBrowserScreen(current, marketplace, context,
                type == null ? ResourceType.MODPACK : type, true, null, true, null));
    }

    @Override
    public void openReports(Screen current) {
        if (demo()) {
            unavailable(Action.REPORTS);
            return;
        }
        application.setScreen(new FeedbackBrowserScreen(current, "Remotely"));
    }

    @Override
    public void signIn(Screen current) {
        Screen parent = current == null ? application.getCurrentScreen() : current;
        if (parent instanceof ReStudioLoginScreen) return;
        application.setScreen(new ReStudioLoginScreen(parent, () -> {
            if (closed || !BrowserLaunchSession.authenticated()) return;
            sessionExpiryNotified = false;
            application.setScreen(parent);
        }));
    }

    private TaskScheduler resourceScheduler() {
        return scheduler == null ? new BrowserTaskScheduler() : scheduler;
    }

    private ResourceMarketplaceProviderAdapter resourceMarketplace() {
        return resourceMarketplace;
    }

    private RemotelyConfigStore configStore() {
        return remotelyClient == null ? null : remotelyClient.getComposition().configManager();
    }

    private GlyphPreviewMode glyphPreviewMode() {
        RemotelyConfigStore config = configStore();
        return config == null ? GlyphPreviewMode.INLINE_HOVER : config.getGlyphPreviewMode();
    }

    private NexoGlyphPreviewAccess glyphPreview(ServerModels.ClientServerView server) {
        String id = serverId(server);
        BrowserRemotelyServerApi api = browserApi();
        if (id.isBlank() || api == null) return null;
        return glyphPreviews.computeIfAbsent(id, ignored -> {
            RemotePath root = RemotePath.root();
            RemoteFileSystemProvider files = new BrowserServerFileSystemProvider(this, api, capabilities(server), server);
            return new NexoGlyphPreviewAccess(files, root, new BrowserGlyphPreviewRuntime(api, files, server, root));
        });
    }

    private void bindEditorDecoration(EditorDecorationBinding binding) {
        if (binding == null || binding.editor() == null || !(binding.provider() instanceof BrowserServerFileSystemProvider provider)) return;
        NexoGlyphPreviewAccess access = glyphPreview(provider.server);
        if (access == null) return;
        String filePath = binding.filePath() == null ? null : binding.filePath().asString();
        GlyphPreviewRenderer renderer = new GlyphPreviewRenderer(access, filePath, binding.language());
        binding.editor().setLineDecoration(new TextLineDecoration() {
            @Override
            public void draw(TextLineDecorationContext context) {
                renderer.drawEditor(context, glyphPreviewMode());
            }

            @Override
            public void afterDraw(TextLineDecorationOverlayContext context) {
                renderer.drawEditorOverlay(context);
            }

            @Override
            public boolean mouseClicked(TextLineDecorationClickContext context) {
                return renderer.openHoveredAsset(context.mouseX(), context.mouseY(), context.button());
            }
        });
        access.refresh();
    }

    private void installFileExplorerRuntime() {
        FileExplorerRuntime.installSettingsResolver(this, fileExplorerPersistence::settings);
        FileExplorerRuntime.installSortSaver(this, fileExplorerPersistence::saveSort);
        FileExplorerRuntime.installServerNameResolver(this, serverId -> {
            BrowserRemotelyServerApi api = browserApi();
            if (api == null) return Async.completed(null);
            return guardOperation(api::getServers).thenApply(servers -> {
                if (servers == null) return null;
                for (ServerModels.ClientServerView server : servers) {
                    if (server != null && Objects.equals(serverId, BrowserServerScreenHost.serverId(server))) return server.name;
                }
                return null;
            });
        });
        FileExplorerRuntime.installTabsResolver(this, new FileExplorerRuntime.TabsResolver() {
            @Override
            public void save(String key, List<FileExplorerRuntime.TabDescriptor> tabs, int activeIndex) {
                fileExplorerPersistence.saveTabs(key, tabs, activeIndex);
            }

            @Override
            public FileExplorerRuntime.LoadedState load(String key) {
                return fileExplorerPersistence.loadTabs(key);
            }
        });
    }

    private ResourceMarketplaceProviderAdapter createResourceMarketplace() {
        Clock clock = remotelyClient == null ? null : remotelyClient.getComposition().clock();
        if (clock == null) clock = new BrowserClock();
        BrowserRemotelyServerApi browserApi = serverApi instanceof BrowserRemotelyServerApi value ? value : null;
        HttpTransport transport = browserApi == null ? new BrowserHttpTransport() : browserApi.transport();
        resourceGateway = new BrowserResourceProviderGateway(transport);
        ResourceProviderTransport providerTransport = new ResourceProviderTransport(
                transport, resourceScheduler(), resourceGateway);
        Clock providerClock = clock;
        AsyncReStudioMarketplaceProvider.Client restudioClient = browserApi == null ? null
                : new BrowserReStudioMarketplaceClient(browserApi.browserMarketplace());
        boolean includeReStudio = restudioClient != null;
        return new ResourceMarketplaceProviderAdapter(ResourceProviderCatalog.instantiateBrowser(includeReStudio,
                definition -> ResourceProviderCatalog.createAsync(definition, providerTransport, providerClock, restudioClient)),
                VersionContext.empty(), true);
    }

    @Override
    public void signOut(Screen current) {
        onAuthenticationInvalidated();
        BrowserLaunchSession.signOut();
    }

    void onAuthenticationInvalidated() {
        if (closed) return;
        authenticationCleanupInProgress = true;
        authenticationObserved = false;
        observedAuthenticated = false;
        observedSubjectId = "";
        resourceMarketplace.clearCatalogCache();
        try {
            clearBrowserSessionState();
            BrowserLaunchSession.clearLocalSession();
        } finally {
            authenticationCleanupInProgress = false;
            sessionExpiryNotified = false;
        }
    }

    @Override
    public void openImportExplorer(Screen current, ServerScreenHost.HostView host, ServerModels.ClientServerView server) {
        importServer(current);
    }

    @Override
    public boolean supports(Action action) {
        if (action == null) return true;
        if (demo()) return switch (action) {
            case FILE_EXPLORER, GLOBAL_TERMINAL, SIGN_OUT -> true;
            default -> false;
        };
        return switch (action) {
            case FILE_EXPLORER, GLOBAL_TERMINAL, DEVELOPMENT, RESYNC_STUDIO, WORLD,
                    SERVER_CONFIGURATION, DUPLICATE_SERVER, DELETE_SERVER, CREATE_SERVER, INBOX,
                    HOST_SETTINGS, REPORTS, SIGN_IN, SIGN_OUT, REACTOR_PLANS, OPEN_PANEL,
                    RESYNC_PROVISION, RESYNC_UPDATE, CUSTOMIZE_ICON, MODPACK_SERVER -> true;
            case NETWORK_SETTINGS -> BrowserLaunchSession.authenticated();
            default -> false;
        };
    }

    @Override
    public ActionAvailability managerAction(Action action, Object target) {
        if (action == null) return ActionAvailability.enabled();
        if (demo()) return demoManagerAction(action);
        ActionAvailability browserAvailability = browserManagerAction(action, target, authenticated());
        if (browserAvailability != null) return browserAvailability;
        return supports(action) ? ActionAvailability.enabled()
                : ActionAvailability.disabled(action.name().replace('_', ' ') + " Is Unavailable");
    }

    static ActionAvailability browserManagerAction(Action action, Object target, boolean authenticated) {
        if (action == null) return ActionAvailability.enabled();
        boolean restudioTarget = "RESTUDIO_MARKER".equals(target);
        return switch (action) {
            case CREATE_SERVER -> authenticated && restudioTarget
                    ? ActionAvailability.enabled()
                    : ActionAvailability.disabled(authenticated ? "Choose ReStudio To Create A Server" : "Sign In To Create A Server");
            case NETWORK_CREATE -> ActionAvailability.disabled("Network Creation Requires A Connected Desktop Host");
            case NETWORK_IMPORT -> ActionAvailability.disabled("Network Import Requires A Connected Desktop Host");
            case MODPACK_SERVER -> authenticated && restudioTarget
                    ? ActionAvailability.enabled()
                    : ActionAvailability.disabled(authenticated ? "Choose ReStudio To Browse Modpacks" : "Sign In To Browse Modpacks");
            case IMPORT_SERVER -> ActionAvailability.disabled("Server Import Requires A Connected Desktop Host");
            case REMOTE_HOST -> ActionAvailability.disabled("Remote Hosts Require A Connected Desktop Host");
            default -> null;
        };
    }

    static ActionAvailability demoManagerAction(Action action) {
        if (action == null) return ActionAvailability.enabled();
        return switch (action) {
            case FILE_EXPLORER, GLOBAL_TERMINAL, SIGN_OUT -> ActionAvailability.enabled();
            default -> ActionAvailability.disabled("Unavailable In Reactor Demo");
        };
    }

    private void withCapability(ServerModels.ClientServerView server, String action, Runnable operation) {
        HostContext context = captureContext();
        if (!isCurrent(context)) return;
        ServerUiCapabilityProvider provider = capabilities(server);
        ServerUiCapabilityProvider.Availability availability = provider.availability(server, action);
        if (availability.available()) {
            if (!isCurrent(context)) return;
            operation.run();
            return;
        }
        if ("Server Capabilities Are Loading".equals(availability.reason())) {
            provider.refresh(server).whenComplete((ignored, failure) -> execute(() -> {
                if (!isCurrent(context)) return;
                if (failure != null) {
                    application.notify("Unavailable", failureMessage(failure), ReSyncNotificationLevel.WARN);
                    return;
                }
                ServerUiCapabilityProvider.Availability refreshed = provider.availability(server, action);
                if (refreshed.available() && isCurrent(context)) operation.run();
                else application.notify("Unavailable", refreshed.reason(), ReSyncNotificationLevel.WARN);
            }));
            return;
        }
        application.notify("Unavailable", availability.reason(), ReSyncNotificationLevel.WARN);
    }

    private <T> Async<T> capabilityOperation(ServerModels.ClientServerView server, String action, Supplier<Async<T>> operation) {
        HostContext context = captureContext();
        if (!isCurrent(context)) return Async.failed(new IllegalStateException("Browser Session Expired"));
        ServerUiCapabilityProvider provider = capabilities(server);
        ServerUiCapabilityProvider.Availability availability = provider.availability(server, action);
        if (availability.available()) return observeSessionFailure(operation.get(), context);
        if ("Server Capabilities Are Loading".equals(availability.reason())) {
            return observeSessionFailure(provider.refresh(server).thenCompose(ignored -> {
                if (!isCurrent(context)) return Async.failed(new IllegalStateException("Browser Session Expired"));
                return capabilityOperation(server, action, operation);
            }), context);
        }
        return Async.failed(new UnsupportedOperationException(availability.reason()));
    }

    private Async<Void> startReProxyOperation(ServerModels.ClientServerView server) {
        BrowserRemotelyServerApi api = browserApi();
        String id = serverId(server);
        if (api == null || id.isBlank() || !isLocalBackend(server)) return Async.failed(new UnsupportedOperationException("ReProxy Is Available For Local Servers Only"));
        return api.getReProxySummary(id).thenCompose(summary -> {
            if (summary != null && summary.activeTunnel != null && activeReProxyStatus(summary.activeTunnel.status)) {
                reProxyStates.put(id, summary);
                return Async.completed(null);
            }
            ServerModels.ReProxyDomain domain = firstActiveReProxyDomain(summary);
            if (domain == null || domain.id == null || domain.id.isBlank()) {
                return Async.failed(new UnsupportedOperationException("Create A ReProxy Domain In Settings First"));
            }
            int port = server.port > 0 ? server.port : 25565;
            return api.startReProxyTunnel(id, domain.id, port, "MINECRAFT_JAVA_TCP")
                    .thenCompose(ignored -> api.getReProxySummary(id))
                    .thenApply(updated -> {
                        reProxyStates.put(id, updated == null ? emptyReProxySummary() : updated);
                        return null;
                    });
        });
    }

    private Async<Void> stopReProxyOperation(ServerModels.ClientServerView server) {
        BrowserRemotelyServerApi api = browserApi();
        String id = serverId(server);
        if (api == null || id.isBlank() || !isLocalBackend(server)) return Async.failed(new UnsupportedOperationException("ReProxy Is Available For Local Servers Only"));
        return api.getReProxySummary(id).thenCompose(summary -> {
            ServerModels.ReProxyTunnel tunnel = summary == null ? null : summary.activeTunnel;
            if (tunnel == null || tunnel.id == null || tunnel.id.isBlank()) {
                reProxyStates.put(id, summary == null ? emptyReProxySummary() : summary);
                return Async.completed(null);
            }
            return api.stopReProxyTunnel(id, tunnel.id)
                    .thenCompose(ignored -> api.getReProxySummary(id))
                    .thenApply(updated -> {
                        reProxyStates.put(id, updated == null ? emptyReProxySummary() : updated);
                        return null;
                    });
        });
    }

    private static ServerModels.ReProxyDomain firstActiveReProxyDomain(ServerModels.ReProxySummary summary) {
        if (summary == null || summary.domains == null) return null;
        return summary.domains.stream().filter(Objects::nonNull)
                .filter(domain -> "ACTIVE".equalsIgnoreCase(domain.status))
                .findFirst().orElse(null);
    }

    private static ServerModels.ReProxySummary emptyReProxySummary() {
        ServerModels.ReProxySummary summary = new ServerModels.ReProxySummary();
        summary.domains = List.of();
        return summary;
    }

    private static boolean activeReProxyStatus(String status) {
        return "CONNECTING".equalsIgnoreCase(status) || "ONLINE".equalsIgnoreCase(status);
    }

    private static boolean capabilityLoading(ServerUiCapabilityProvider.Availability availability) {
        return availability != null && "Server Capabilities Are Loading".equals(availability.reason());
    }

    private Async<Void> duplicateOperation(ServerModels.ClientServerView server) {
        if (server == null || serverId(server).isBlank() || serverApi == null) {
            return Async.failed(new IllegalArgumentException("Server Identifier Is Unavailable"));
        }
        String name = server.name == null || server.name.isBlank() ? "Server Copy" : server.name + " Copy";
        return capabilityOperation(server, "server.duplicate",
                () -> serverApi.duplicateServer(serverId(server), name, UUID.randomUUID().toString()).thenApply(ignored -> null));
    }

    private Async<Void> deleteOperation(ServerModels.ClientServerView server) {
        if (server == null || serverId(server).isBlank() || serverApi == null) {
            return Async.failed(new IllegalArgumentException("Server Identifier Is Unavailable"));
        }
        return capabilityOperation(server, "server.delete",
                () -> serverApi.deleteServer(serverId(server), server.name).thenApply(ignored -> null));
    }

    private void execute(Runnable task) {
        if (task == null) return;
        if (scheduler == null) task.run();
        else scheduler.execute(task);
    }

    private void schedule(Runnable task, Duration delay) {
        if (task == null) return;
        if (scheduler == null) task.run();
        else scheduler.schedule(task, delay);
    }

    private BrowserRemotelyServerApi browserApi() {
        return serverApi instanceof BrowserRemotelyServerApi value ? value : null;
    }

    private void handleAuthenticationChange() {
        if (authenticationCleanupInProgress || closed) return;
        observeAuthentication(true);
    }

    private void handleSessionExpiry() {
        if (!closed) notifySessionExpired();
    }

    private void observeAuthentication(boolean clearOnChange) {
        boolean currentAuthenticated = BrowserLaunchSession.authenticated();
        ReStudioCommunityProvider provider = ReStudioCommunityProviders.current();
        String currentSubjectId = currentAuthenticated
                ? firstNonBlank(BrowserLaunchSession.metadata().subjectId(), provider == null ? "" : provider.userId()) : "";
        String currentTicket = currentAuthenticated ? BrowserLaunchSession.ticket() : "";
        boolean changed = authenticationEpochChanged(authenticationObserved, observedAuthenticated, observedSubjectId,
                currentAuthenticated, currentSubjectId);
        boolean ticketChanged = authenticationObserved && !Objects.equals(observedTicket, currentTicket);
        authenticationObserved = true;
        observedAuthenticated = currentAuthenticated;
        observedSubjectId = currentSubjectId;
        observedTicket = currentTicket;
        if (ticketChanged) {
            advanceResourceAuthGeneration();
            retireResourceContexts();
        }
        if (clearOnChange && (changed || ticketChanged)) resourceMarketplace.clearCatalogCache();
        if (clearOnChange && changed) {
            clearBrowserSessionState();
            sessionExpiryNotified = false;
        }
    }

    static boolean authenticationEpochChanged(boolean observed, boolean previousAuthenticated, String previousSubjectId,
                                              boolean authenticated, String subjectId) {
        return observed && (previousAuthenticated != authenticated || !Objects.equals(previousSubjectId, subjectId));
    }

    private void clearBrowserSessionState() {
        advanceHostGeneration();
        retireResourceContexts();
        BrowserRemotelyServerApi browserApi = browserApi();
        if (browserApi != null) {
            browserApi.closeAllTerminals();
            browserApi.resetManagerSnapshot(this);
        }
        if (remotelyClient != null && remotelyClient.getComposition().environment() == RemotelyComposition.Environment.BROWSER) {
            remotelyClient.shutdownAllTerminals();
        }
        capabilityProviders.clear();
        glyphPreviews.values().forEach(NexoGlyphPreviewAccess::close);
        glyphPreviews.clear();
        developerProviders.clear();
        developerBindings.clear();
        resolvedDeveloperBindings.clear();
        developerBindingRequests.clear();
        developerBindingWaiters.clear();
        serverStates.clear();
        stateListeners.clear();
        stateRequests.clear();
        reProxyStates.clear();
        reProxyRefreshListeners.clear();
        reProxyStateRequests.clear();
        playerMetrics.clear();
        playerMetricRefreshes.clear();
        playerMetricRequests.clear();
        if (closed) {
            synchronized (resourceContexts) {
                resourceContexts.clear();
            }
        }
    }

    private void advanceHostGeneration() {
        hostGeneration++;
        if (hostGeneration <= 0) hostGeneration = 1L;
        advanceResourceAuthGeneration();
    }

    private void advanceResourceAuthGeneration() {
        resourceGeneration++;
        if (resourceGeneration <= 0) resourceGeneration = 1L;
        resourceAuthGeneration++;
        if (resourceAuthGeneration <= 0) resourceAuthGeneration = 1L;
    }

    void registerResourceContext(HostedResourceContext context) {
        if (context == null || closed) return;
        synchronized (resourceContexts) {
            resourceContexts.add(context);
        }
    }

    private void retireResourceContexts() {
        List<HostedResourceContext> contexts;
        synchronized (resourceContexts) {
            contexts = List.copyOf(resourceContexts);
        }
        contexts.forEach(HostedResourceContext::invalidateForResourceSession);
    }

    private HostContext captureContext() {
        observeAuthentication(true);
        return new HostContext(hostGeneration, currentSubjectId());
    }

    private boolean isCurrent(HostContext context) {
        return context != null && !closed && context.generation() == hostGeneration
                && BrowserLaunchSession.authenticated() && Objects.equals(context.subjectId(), currentSubjectId());
    }

    static boolean sameHostContext(HostContext expected, HostContext actual) {
        return Objects.equals(expected, actual);
    }

    private boolean isCurrentAccountResponse(HostContext context, String ticket, String responseSubjectId, boolean subjectUpgradeable) {
        if (context == null || closed || context.generation() != hostGeneration || !BrowserLaunchSession.authenticated()
                || !Objects.equals(ticket, BrowserLaunchSession.ticket())) return false;
        if (responseSubjectId != null && !responseSubjectId.isBlank() && !context.subjectId().isBlank()
                && !Objects.equals(context.subjectId(), responseSubjectId)) return false;
        String currentSubjectId = currentSubjectId();
        return Objects.equals(context.subjectId(), currentSubjectId) || subjectUpgradeable && responseSubjectId != null
                && !responseSubjectId.isBlank() && Objects.equals(responseSubjectId, currentSubjectId);
    }

    private void adoptAccountSubject(String ticket, String subjectId) {
        authenticationObserved = true;
        observedAuthenticated = true;
        observedSubjectId = subjectId;
        observedTicket = ticket;
    }

    private boolean isCurrentGeneration(long generation) {
        return !closed && generation == hostGeneration;
    }

    long resourceGeneration() {
        return resourceGeneration;
    }

    long resourceAuthGeneration() {
        return resourceAuthGeneration;
    }

    boolean resourceAlive() {
        return !closed;
    }

    String resourceSubjectId() {
        return currentSubjectId();
    }

    String resourceTicket() {
        return BrowserLaunchSession.ticket();
    }

    private String currentSubjectId() {
        if (!BrowserLaunchSession.authenticated()) return "";
        BrowserLaunchSession.Metadata session = BrowserLaunchSession.metadata();
        ReStudioCommunityProvider provider = ReStudioCommunityProviders.current();
        return firstNonBlank(session.subjectId(), provider == null ? "" : provider.userId());
    }

    private <T> Async<T> guardCurrent(Async<T> value) {
        return guardCurrent(value, captureContext());
    }

    private <T> Async<T> guardCurrent(Async<T> value, HostContext context) {
        return value.thenApply(result -> {
            if (!isCurrent(context)) throw new IllegalStateException("Browser Session Expired");
            return result;
        });
    }

    private <T> Async<T> guardOperation(Supplier<Async<T>> operation) {
        HostContext context = captureContext();
        if (!isCurrent(context)) return Async.failed(new IllegalStateException("Browser Session Expired"));
        try {
            return guardCurrent(operation.get(), context);
        } catch (Throwable failure) {
            return Async.failed(failure);
        }
    }

    @Override
    public ServerModels.ClientServerView serverView(Object target) {
        return asServerView(target);
    }

    static ServerModels.ClientServerView asServerView(Object target) {
        if (target instanceof BrowserServerConfigurationTarget configuration) return configuration.view();
        return target instanceof ServerModels.ClientServerView server ? server : null;
    }

    private static String browserBackendType(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.environment != null) {
            String backend = server.environment.get("backend");
            if (backend != null && !backend.isBlank()) return backend;
        }
        if (server.backendType != null && !server.backendType.isBlank()) return server.backendType;
        return "RESTUDIO";
    }

    private static boolean supportedServerBackend(String type) {
        return "RESTUDIO".equalsIgnoreCase(type) || "PTERO".equalsIgnoreCase(type)
                || "PTERODACTYL".equalsIgnoreCase(type) || "CALAGOPUS".equalsIgnoreCase(type);
    }

    private static boolean isLocalBackend(ServerModels.ClientServerView server) {
        return "LOCAL".equalsIgnoreCase(browserBackendType(server));
    }

    private static String serverName(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.name != null && !server.name.isBlank()) return server.name;
        return serverId(server);
    }

    private static String serverId(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.identifier != null && !server.identifier.isBlank()) return server.identifier;
        return server.uuid == null ? "" : server.uuid;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private void handleBrowserInstanceChange(BrowserRemotelyServerApi.ManagerSnapshot snapshot) {
        if (snapshot == null || closed) return;
        long generation = hostGeneration;
        execute(() -> {
            if (!isCurrentGeneration(generation) || !authenticated()) return;
            for (ServerModels.ClientServerView server : snapshot.instances()) {
                ServerModels.ServerStatus status = snapshot.statuses().get(serverId(server));
                if (status == null) continue;
                ServerState state = status.installing ? ServerState.INSTALLING : ServerState.parse(status.currentState);
                if (state != ServerState.UNKNOWN) updateServerState(server, state);
            }
            notifyBrowserListeners(instanceChangeListeners);
        });
    }

    private void handleBrowserNetworkChange(BrowserRemotelyServerApi.ManagerSnapshot ignored) {
        if (closed) return;
        long generation = hostGeneration;
        execute(() -> {
            if (isCurrentGeneration(generation) && authenticated()) notifyBrowserListeners(networkChangeListeners);
        });
    }

    private void handleBrowserRuntimeChange(BrowserRemotelyServerApi.ManagerSnapshot ignored) {
        if (closed) return;
        long generation = hostGeneration;
        execute(() -> {
            if (isCurrentGeneration(generation) && authenticated()) notifyBrowserListeners(runtimeChangeListeners);
        });
    }

    private static void notifyBrowserListeners(Set<Runnable> listeners) {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    private void updateServerState(ServerModels.ClientServerView server, ServerState state) {
        if (server == null || state == null) return;
        String id = serverId(server);
        if (id.isBlank()) return;
        Map<String, String> environment = server.environment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(server.environment);
        environment.put("state", state.name());
        environment.put("currentState", state.name());
        environment.put("current_state", state.name());
        server.environment = environment;
        server.isInstalling = state == ServerState.INSTALLING;
        ServerState previous = serverStates.put(id, state);
        trimStateCache();
        if (state == previous) return;
        List<Consumer<ServerState>> listeners = stateListeners.get(id);
        if (listeners == null || listeners.isEmpty()) return;
        long generation = hostGeneration;
        for (Consumer<ServerState> listener : List.copyOf(listeners)) {
            execute(() -> {
                if (isCurrentGeneration(generation)) listener.accept(state);
            });
        }
    }

    private void requestInitialState(ServerModels.ClientServerView server) {
        String id = serverId(server);
        HostContext context = captureContext();
        if (!isCurrent(context) || id.isBlank() || !stateRequests.add(id)) return;
        try {
            serverStatus(null, server).whenComplete((ignored, failure) -> execute(() -> {
                if (failure != null) stateRequests.remove(id);
                if (!isCurrent(context)) return;
                if (failure != null && isSessionExpired(failure)) notifySessionExpired();
            }));
        } catch (Throwable failure) {
            stateRequests.remove(id);
            if (isCurrent(context) && isSessionExpired(failure)) notifySessionExpired();
        }
    }

    private void refreshPlayerMetrics(ServerModels.ClientServerView server, BrowserRemotelyServerApi browserApi, HostContext context) {
        String id = serverId(server);
        if (!isCurrent(context) || id.isBlank() || !playerMetricRequests.add(id)) return;
        long now = System.currentTimeMillis();
        Long lastRefresh = playerMetricRefreshes.get(id);
        if (lastRefresh != null && now - lastRefresh < 5000L) {
            playerMetricRequests.remove(id);
            return;
        }
        Async<List<RemotelyServerApi.Player>> players = capabilities(server).players(server).exceptionally(failure -> {
            if (isCurrent(context) && isSessionExpired(failure)) notifySessionExpired();
            return List.of();
        });
        Async<String> properties = browserApi.getFileContent(id, "server.properties").exceptionally(failure -> {
            if (isCurrent(context) && isSessionExpired(failure)) notifySessionExpired();
            return "";
        });
        Async.allOf(players, properties).whenComplete((ignored, failure) -> execute(() -> {
            if (!isCurrent(context)) {
                playerMetricRequests.remove(id);
                return;
            }
            int online = players.getNow(List.of()).stream().filter(Objects::nonNull).filter(RemotelyServerApi.Player::online).toList().size();
            int maximum = parseMaximumPlayers(properties.getNow(""));
            playerMetrics.put(id, new PlayerMetrics(online, maximum));
            playerMetricRefreshes.put(id, System.currentTimeMillis());
            playerMetricRequests.remove(id);
        }));
    }

    private void trimStateCache() {
        while (serverStates.size() > MAX_CACHED_SERVER_STATES) {
            String removable = serverStates.keySet().stream().filter(id -> !stateListeners.containsKey(id)).findFirst().orElse(null);
            if (removable == null) return;
            serverStates.remove(removable);
        }
    }

    private static ServerState requestedState(String signal) {
        return switch (signal == null ? "" : signal.trim().toLowerCase(Locale.ROOT)) {
            case "start" -> ServerState.STARTING;
            case "stop" -> ServerState.STOPPING;
            case "kill" -> ServerState.STOPPED;
            default -> ServerState.UNKNOWN;
        };
    }

    private static String withPort(String address, int port) {
        if (address == null || address.isBlank() || port <= 0 || port == 25565) return address == null ? "" : address;
        if (address.endsWith(":" + port)) return address;
        return address + ":" + port;
    }

    private static long memoryLimit(ServerModels.ClientServerView server) {
        if (server == null || server.limits == null || server.limits.memory == null || server.limits.memory <= 0) return 0;
        return server.limits.memory * 1024L * 1024L;
    }

    private static ServerScreenHost.ServerMetrics emptyMetrics(ServerModels.ClientServerView server) {
        return new ServerScreenHost.ServerMetrics(0, 0, 0, memoryLimit(server), 0, 0);
    }

    private ServerScreenHost.ServerMetrics clearMetrics(ServerModels.ClientServerView server) {
        String id = serverId(server);
        playerMetrics.remove(id);
        playerMetricRefreshes.remove(id);
        return emptyMetrics(server);
    }

    private static int parseMaximumPlayers(String content) {
        String value = parseProperties(content).get("max-players");
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private <T> Async<T> observeSessionFailure(Async<T> value) {
        return observeSessionFailure(value, null);
    }

    private <T> Async<T> observeSessionFailure(Async<T> value, HostContext context) {
        return value.handle((result, failure) -> {
            if (failure == null) return result;
            if (isSessionExpired(failure) && (context == null || isCurrent(context))) notifySessionExpired();
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failure);
        });
    }

    private void notifySessionExpired() {
        if (closed || sessionExpiryNotified) return;
        boolean demo = BrowserLaunchSession.metadata().demo();
        onAuthenticationInvalidated();
        sessionExpiryNotified = true;
        application.notify(demo ? "Reactor Demo Ended" : "Sign In Required",
                demo ? "Your Demo Server Is Resetting" : "Your Browser Session Expired", demo ? ReSyncNotificationLevel.INFO : ReSyncNotificationLevel.ERROR);
        if (!demo) signIn(application.getCurrentScreen());
    }

    private void handleSessionFailure(HostContext context, Throwable failure) {
        if (isSessionExpired(failure) && isCurrent(context)) notifySessionExpired();
    }

    static boolean isSessionExpired(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RemotelyCapabilityException capability
                    && "reactor_demo_session_expired".equals(capability.code())) return true;
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("browser session expired")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRecoverableConfigurationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                int marker = message.indexOf("Status ");
                if (marker >= 0) {
                    try {
                        int status = Integer.parseInt(message.substring(marker + 7).trim());
                        if (status >= 500 && status < 600) return true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private record PlayerMetrics(int players, int maxPlayers) {
    }

    record HostContext(long generation, String subjectId) {
    }

    private static Map<String, String> parseProperties(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (content == null || content.isBlank()) return result;
        for (String line : content.replace("\r", "").split("\n")) {
            String value = line.trim();
            if (value.isBlank() || value.startsWith("#") || value.startsWith("!")) continue;
            int separator = value.indexOf('=');
            if (separator < 0) separator = value.indexOf(':');
            if (separator <= 0) continue;
            result.put(value.substring(0, separator).trim(), value.substring(separator + 1).trim());
        }
        return result;
    }

    private static String serializeProperties(Map<String, String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) return;
            result.append(key).append('=').append(value == null ? "" : value).append('\n');
        });
        return result.toString();
    }

    private static String failureMessage(Throwable failure) {
        if (failure == null) return "Operation Failed";
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank() ? "Operation Failed" : current.getMessage();
    }

    private static String demoExpiryDescription(String value) {
        if (value == null || value.isBlank()) return "Changes Reset Automatically";
        try {
            long seconds = Duration.between(Instant.now(), Instant.parse(value)).getSeconds();
            if (seconds <= 0) return "Session Is Resetting";
            long minutes = Math.max(1L, (seconds + 59L) / 60L);
            return "Changes Reset Automatically. Session Ends In " + minutes + (minutes == 1 ? " Minute" : " Minutes");
        } catch (RuntimeException ignored) {
            return "Changes Reset Automatically";
        }
    }

    private static FileExplorerRuntime.ArchiveResolver unavailableArchiveResolver() {
        return new FileExplorerRuntime.ArchiveResolver() {
            @Override
            public Async<Void> compress(String targetServerId, String root, List<String> files) {
                return Async.failed(new UnsupportedOperationException("Archive Operations Are Unavailable"));
            }

            @Override
            public Async<Void> decompress(String targetServerId, String root, String file) {
                return Async.failed(new UnsupportedOperationException("Archive Operations Are Unavailable"));
            }
        };
    }

    private static final class BrowserServerFileSystemProvider implements RemoteFileSystemProvider,
            BrowserFileExplorerAdapters.BrowserDownloadDragSource {
        private final BrowserServerScreenHost owner;
        private final BrowserRemotelyServerApi api;
        private final ServerUiCapabilityProvider capabilities;
        private final ServerModels.ClientServerView server;
        private final String serverId;
        private final BrowserServerFileTransfer transfer;

        private BrowserServerFileSystemProvider(BrowserServerScreenHost owner, BrowserRemotelyServerApi api, ServerUiCapabilityProvider capabilities,
                                                ServerModels.ClientServerView server) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.api = Objects.requireNonNull(api, "api");
            this.capabilities = capabilities == null ? ServerUiCapabilityProvider.unavailable() : capabilities;
            this.server = server == null ? new ServerModels.ClientServerView() : server;
            this.serverId = serverId(this.server);
            this.transfer = new BrowserServerFileTransfer(api, this.serverId);
            FileExplorerRuntime.installReStudioArchiveResolver(owner, new FileExplorerRuntime.ArchiveResolver() {
                @Override
                public Async<Void> compress(String targetServerId, String root, List<String> files) {
                    return owner.capabilityOperation(server(targetServerId), "files.compress", () -> api.compressFiles(targetServerId, root, files));
                }

                @Override
                public Async<Void> decompress(String targetServerId, String root, String file) {
                    return owner.capabilityOperation(server(targetServerId), "files.decompress", () -> api.decompressFile(targetServerId, root, file));
                }
            });
        }

        @Override
        public Map<String, CapabilityDescriptor> capabilities() {
            return Map.of(
                    CapabilityIds.FILES, capability(CapabilityIds.FILES, "files.list"),
                    CapabilityIds.TRASH, capability(CapabilityIds.TRASH, "files.version", "files.trash", "files.trash-list", "files.restore", "files.purge"),
                    CapabilityIds.TRANSFER, capability(CapabilityIds.TRANSFER, "files.upload", "files.download"),
                    CapabilityIds.DOWNLOAD, capability(CapabilityIds.DOWNLOAD, "files.download"),
                    CapabilityIds.EXTERNAL_OPEN, capability(CapabilityIds.EXTERNAL_OPEN, "files.download"));
        }

        @Override
        public CapabilityDescriptor operationCapability(String operation, List<RemotePath> sources, RemotePath destination) {
            String id = operation == null || operation.isBlank() ? CapabilityIds.FILES : operation;
            if (BrowserLaunchSession.metadata().demo() && CapabilityIds.WRITE.equals(id)) {
                RemotePath target = sources != null && !sources.isEmpty() ? sources.getFirst() : destination;
                if (target == null || !BrowserLaunchSession.demoPathEditable(remote(target))) {
                    return CapabilityDescriptor.unavailable(CapabilityIds.WRITE, "File Is Read Only In Reactor Demo");
                }
            }
            CapabilityDescriptor available = CapabilityIds.TRASH.equals(id)
                    ? capability(id, "files.version", "files.trash")
                    : capability(id, CapabilityIds.FILES.equals(id) ? "files.list" : id);
            if (!available.available()) return available;
            if (CapabilityIds.COPY.equals(operation) && sources != null && destination != null) {
                RemotePath targetDirectory = normalized(destination);
                for (RemotePath source : sources) {
                    if (source == null || source.isRoot()) continue;
                    if (!normalized(parent(source)).equals(targetDirectory)) {
                        return CapabilityDescriptor.unavailable(CapabilityIds.COPY,
                                "Remote File Copy To Another Folder Is Unavailable");
                    }
                }
            }
            return available;
        }

        @Override
        public CapabilityDescriptor externalOpenCapability(RemotePath path, boolean directory) {
            if (directory) {
                return CapabilityDescriptor.unavailable(CapabilityIds.EXTERNAL_OPEN,
                        "Browser Folder Opening Is Unavailable");
            }
            if (path == null || path.isRoot()) {
                return CapabilityDescriptor.unavailable(CapabilityIds.EXTERNAL_OPEN, "Select A File To Open");
            }
            return capability(CapabilityIds.EXTERNAL_OPEN, "files.download");
        }

        @Override
        public Async<List<RemoteFileSystemProvider.FileEntry>> ls(RemotePath path) {
            String directory = remoteDirectory(path);
            return operation("files.list", () -> capabilities.listFiles(server, directory).thenApply(values -> {
                if (values == null) return List.of();
                return values.stream().filter(Objects::nonNull).map(value -> {
                    RemotePath entryPath = normalized(path).resolve(value.name == null ? "" : value.name);
                    boolean isDirectory = !value.isFile;
                    String size = isDirectory ? "-" : value.size == null ? "" : String.valueOf(value.size);
                    return new RemoteFileSystemProvider.FileEntry(entryPath, isDirectory, size,
                            value.modifiedAt == null ? "-" : value.modifiedAt, value.name,
                            Map.of("mimetype", value.mimetype == null ? "" : value.mimetype));
                }).toList();
            }));
        }

        @Override
        public Async<Void> copy(List<RemotePath> sources, RemotePath destination) {
            if (sources == null || sources.isEmpty() || destination == null) return Async.completed(null);
            RemotePath targetDirectory = normalized(destination);
            Async<Void> result = Async.completed(null);
            for (RemotePath source : sources) {
                if (source == null || source.isRoot()) continue;
                RemotePath sourceDirectory = parent(source);
                if (!normalized(sourceDirectory).equals(targetDirectory)) {
                    return unsupported("Remote File Copy To Another Folder Is Unavailable");
                }
                String location = remote(source);
                if (location.isBlank()) continue;
                result = result.thenCompose(ignored -> operation("files.copy", () -> api.copyFile(serverId, location)));
            }
            return owner.guardCurrent(result);
        }

        @Override
        public Async<Void> move(List<RemotePath> sources, RemotePath destination) {
            if (sources == null || sources.isEmpty() || destination == null) return Async.completed(null);
            Async<Void> result = Async.completed(null);
            for (RemotePath source : sources) {
                if (source == null) continue;
                RemotePath target = destination.resolve(source.fileName());
                String from = remote(source);
                String to = remote(target);
                if (from.isBlank() || to.isBlank() || from.equals(to)) continue;
                result = result.thenCompose(ignored -> operation("files.move", () -> capabilities.renameFiles(server, "/", List.of(rename(from, to)))));
            }
            return owner.guardCurrent(result);
        }

        @Override
        public Async<Void> delete(List<RemotePath> paths) {
            if (paths == null || paths.isEmpty()) return Async.completed(null);
            Async<Void> result = Async.completed(null);
            for (RemotePath path : paths) {
                result = result.thenCompose(ignored -> operation("files.delete", () -> capabilities.deleteFiles(server, remoteDirectory(parent(path)), List.of(path.fileName()))));
            }
            return owner.guardCurrent(result);
        }

        @Override
        public Async<Void> deleteToTrash(List<RemotePath> paths) {
            if (paths == null || paths.isEmpty()) return Async.completed(null);
            Async<Void> result = Async.completed(null);
            for (RemotePath path : paths) {
                result = result.thenCompose(ignored -> operation("files.version", () -> operation("files.trash", () -> api.getFileVersion(serverId, remote(path))
                        .thenCompose(version -> api.trashFile(serverId, remote(path), version == null ? "" : version.version())))
                        .thenApply(ignoredTrash -> null)));
            }
            return owner.guardCurrent(result);
        }

        @Override
        public Async<List<RemoteFileSystemProvider.TrashEntry>> trash() {
            return operation("files.trash-list", () -> api.listTrash(serverId).thenApply(values -> values == null ? List.of() : values.stream().filter(Objects::nonNull)
                    .map(value -> new RemoteFileSystemProvider.TrashEntry(value.id(), RemotePath.of(value.path()), value.directory(), value.size(), value.deletedAt(), value.version())).toList()));
        }

        @Override
        public Async<Void> restoreTrash(List<String> ids) {
            return operation("files.restore", () -> trashAction(ids, (id, entry) -> operation(
                    "files.restore", () -> api.restoreTrash(serverId, id, entry.version()).thenApply(ignored -> null))));
        }

        @Override
        public Async<Void> purgeTrash(List<String> ids) {
            return operation("files.purge", () -> trashAction(ids, (id, entry) -> operation(
                    "files.purge", () -> api.purgeTrash(serverId, id, entry.version()))));
        }

        @Override
        public Async<String> read(RemotePath path) {
            return operation("files.read", () -> capabilities.readFile(server, remote(path)));
        }

        @Override
        public Async<Void> write(RemotePath path, String content) {
            if (BrowserLaunchSession.metadata().demo() && !BrowserLaunchSession.demoPathEditable(remote(path))) {
                return unsupported("File Is Read Only In Reactor Demo");
            }
            return operation("files.write", () -> capabilities.writeFile(server, remote(path), content == null ? "" : content));
        }

        @Override
        public Async<Void> upload(List<TransferSource> sources, RemotePath destination) {
            return upload(sources, destination, (sent, total) -> {
            });
        }

        @Override
        public Async<Void> upload(List<TransferSource> sources, RemotePath destination, BiConsumer<Long, Long> progressCallback) {
            return operation("files.upload", () -> transfer.upload(sources, destination, progressCallback));
        }

        @Override
        public Async<Void> download(List<RemotePath> sources, TransferSink destination) {
            return download(sources, destination, (received, total) -> {
            }, () -> false);
        }

        @Override
        public Async<Void> download(List<RemotePath> sources, TransferSink destination, BiConsumer<Long, Long> progressCallback,
                                    BooleanSupplier isCancelled) {
            return operation("files.download", () -> transfer.download(sources, destination, progressCallback, isCancelled));
        }

        @Override
        public CapabilityDescriptor deviceDownloadCapability(List<RemotePath> sources) {
            if (sources == null || sources.isEmpty()) {
                return CapabilityDescriptor.unavailable(CapabilityIds.DOWNLOAD, "Select Files To Download");
            }
            if (sources.stream().anyMatch(path -> path == null || path.isRoot())) {
                return CapabilityDescriptor.unavailable(CapabilityIds.DOWNLOAD, "Select Files To Download");
            }
            return capability(CapabilityIds.DOWNLOAD, "files.download");
        }

        @Override
        public Async<Void> downloadToDevice(List<RemotePath> sources, BiConsumer<Long, Long> progressCallback,
                                            BooleanSupplier isCancelled) {
            if (isCancelled != null && isCancelled.getAsBoolean()) return Async.failed(new Async.Cancellation());
            List<String> selected = sources == null ? List.of() : sources.stream()
                    .filter(Objects::nonNull).map(this::remote).toList();
            return operation("files.download", () -> api.downloadFiles(serverId, selected));
        }

        @Override
        public Async<String> downloadUrl(RemotePath path) {
            if (path == null || path.isRoot()) return Async.failed(new IllegalArgumentException("Select A File To Drag"));
            return operation("files.download", () -> api.downloadFile(serverId, remote(path)));
        }

        @Override
        public Async<Void> openExternally(RemotePath path, boolean directory) {
            if (directory) return unsupported("Browser Folder Opening Is Unavailable");
            if (path == null || path.isRoot()) return unsupported("Select A File To Open");
            return operation("files.download", () -> api.openFileDownload(serverId, remote(path)));
        }

        @Override
        public Async<Void> rename(RemotePath oldPath, RemotePath newPath) {
            if (oldPath == null || newPath == null) return unsupported("Remote File Rename Is Unavailable");
            String from = remote(oldPath);
            String to = remote(newPath);
            if (from.isBlank() || to.isBlank() || from.equals(to)) return Async.completed(null);
            return operation("files.rename", () -> capabilities.renameFiles(server, "/", List.of(rename(from, to))));
        }

        @Override
        public Async<Void> createFile(RemotePath path) {
            return operation("files.create-file", () -> capabilities.writeFile(server, remote(path), ""));
        }

        @Override
        public Async<Void> createDirectory(RemotePath path) {
            if (path == null || path.fileName().isBlank()) return unsupported("Remote Folder Creation Is Unavailable");
            return operation("files.create-folder", () -> capabilities.createFolder(server, remoteDirectory(parent(path)), path.fileName()));
        }

        @Override
        public Async<Boolean> exists(RemotePath path) {
            if (path == null || path.isRoot()) return Async.completed(true);
            return owner.guardOperation(() -> ls(parent(path)).thenApply(values -> values.stream().anyMatch(value -> value.path().equals(path))));
        }

        @Override
        public void invalidateCache(RemotePath path) {
        }

        @Override
        public String getMetadata(String key) {
            if ("type".equalsIgnoreCase(key)) return browserBackendType(server);
            if ("host".equalsIgnoreCase(key)) return BrowserLaunchSession.apiBaseUrl();
            if ("serverId".equalsIgnoreCase(key)) return serverId;
            if ("serverName".equalsIgnoreCase(key)) return server == null ? null : server.name;
            if ("homeDir".equalsIgnoreCase(key)) return "/";
            return null;
        }

        @Override
        public RemotePath canonicalPath(RemotePath path) {
            return normalized(path);
        }

        private Async<Void> trashAction(List<String> ids, TrashOperation operation) {
            if (ids == null || ids.isEmpty()) return Async.completed(null);
            return trash().thenCompose(entries -> {
                Async<Void> result = Async.completed(null);
                for (String id : ids) {
                    RemoteFileSystemProvider.TrashEntry entry = entries.stream().filter(value -> Objects.equals(value.id(), id)).findFirst().orElse(null);
                    if (entry != null) result = result.thenCompose(ignored -> operation.apply(id, entry));
                }
                return result;
            });
        }

        private <T> Async<T> operation(String action, Supplier<Async<T>> operation) {
            return owner.capabilityOperation(server, action, operation);
        }

        private CapabilityDescriptor capability(String id, String... actions) {
            for (String action : actions) {
                ServerUiCapabilityProvider.Availability availability = capabilities.availability(server, action);
                if (!availability.available()) {
                    return CapabilityDescriptor.unavailable(id, availability.reason().isBlank()
                            ? "Server File Capability Is Unavailable" : availability.reason());
                }
            }
            return CapabilityDescriptor.supported(id);
        }

        private static ServerModels.ClientServerView server(String serverId) {
            ServerModels.ClientServerView server = new ServerModels.ClientServerView();
            server.identifier = serverId == null ? "" : serverId;
            return server;
        }

        private RemotePath normalized(RemotePath path) {
            return path == null ? RemotePath.root() : path;
        }

        private String remote(RemotePath path) {
            String value = normalized(path).asString();
            if (value.isBlank() || "/".equals(value) || ".".equals(value)) return "";
            return value.startsWith("/") ? value.substring(1) : value;
        }

        private String remoteDirectory(RemotePath path) {
            String value = remote(path);
            return value.isBlank() ? "/" : value;
        }

        private RemotePath parent(RemotePath path) {
            RemotePath parent = path == null ? null : path.getParent();
            return parent == null ? RemotePath.root() : parent;
        }

        private ServerModels.PteroFileRenameItem rename(String from, String to) {
            ServerModels.PteroFileRenameItem value = new ServerModels.PteroFileRenameItem();
            value.from = from == null ? "" : from;
            value.to = to == null ? "" : to;
            return value;
        }

        private <T> Async<T> unsupported(String message) {
            return Async.failed(new UnsupportedOperationException(message));
        }

        @FunctionalInterface
        private interface TrashOperation {
            Async<Void> apply(String id, RemoteFileSystemProvider.TrashEntry entry);
        }
    }
}
