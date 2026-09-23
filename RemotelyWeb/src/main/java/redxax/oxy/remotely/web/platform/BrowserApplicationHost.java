package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.FlowManagerUiAdapter;
import redxax.oxy.remotely.data.flow.ReSyncConnectionManager;
import redxax.oxy.remotely.data.flow.ReSyncConnectionProfileProvider;
import redxax.oxy.remotely.data.flow.ReSyncServerIdentity;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientConfiguration;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientFactory;
import redxax.oxy.remotely.data.flow.ReSyncLuckPermsProvider;
import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import redxax.oxy.remotely.flow.ui.ReSyncProvisioningService;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.browser.BrowserMinecraftGameAssets;
import restudio.rescreen.platform.browser.BrowserClipboardHandler;
import restudio.rescreen.platform.browser.BrowserHostActionHandler;
import restudio.rescreen.platform.browser.BrowserTextRenderer;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.rebase.ui.screens.marketplace.MarketplaceDetailsScreen;
import restudio.rebase.ui.screens.auth.ReStudioLoginScreen;
import restudio.rebase.restudio.marketplace.MarketplaceDetailsProvider;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.http.HttpTransport;
import redxax.oxy.remotely.ui.integrations.luckperms.LuckPermsDashboardScreen;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import redxax.oxy.remotely.ui.server.ServerDetailsScreen;
import restudio.rescreen.platform.Async;

import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class BrowserApplicationHost implements ApplicationHost {
    private final ScreenManager screenManager = ScreenManager.getInstance();
    private final BrowserMinecraftGameAssets gameAssets = new BrowserMinecraftGameAssets();
    private final BrowserClipboardHandler clipboardHandler;
    private final BrowserHostActionHandler hostActionHandler;
    private final BrowserReSyncLuckPermsProvider luckPermsProvider = new BrowserReSyncLuckPermsProvider();
    private final BrowserLaunchSession.Metadata metadata;
    private final FlowManagerUiAdapter flowManagerUiAdapter = new BrowserFlowManagerUiAdapter();
    private MarketplaceDetailsProvider marketplaceDetailsProvider;
    private RemotelyClient serverScreenClient;
    private BrowserServerScreenHost serverScreenHost;
    private HttpTransport httpTransport;
    private BrowserReSyncProvisioningAdapter provisioningAdapter;
    private String reSyncServerContext = "";
    private ServerModels.ClientServerView reSyncStartupServer;
    private String reSyncLoaderHint = "";
    private String reSyncSessionAuthority = "";
    private boolean reSyncSessionAuthenticated;
    private boolean reSyncPreparationActive;
    private Async<ReSyncProvisioningService.StartupProbeResult> reSyncPreparation;
    private Async<ReSyncProvisioningService.StartupProbeResult> reSyncPreparationSource;
    private long reSyncContextGeneration = 1L;
    private final Map<String, RemotelyServerApi.ReSyncReadinessReason> reSyncRelayReasons = new HashMap<>();
    private final Map<String, Boolean> reSyncProbePending = new HashMap<>();
    private String lastReSyncReadinessMessage = "";
    private final Runnable reSyncAuthStateListener = this::invalidateReSyncAuthentication;
    private final Runnable reSyncTicketListener = this::rotateReSyncTicket;
    private final Runnable reSyncSessionExpiryListener = this::invalidateReSyncSession;

    public BrowserApplicationHost(String canvasId, BrowserLaunchSession.Metadata metadata) {
        clipboardHandler = new BrowserClipboardHandler(canvasId);
        hostActionHandler = new BrowserHostActionHandler(canvasId);
        this.metadata = metadata;
        reSyncSessionAuthority = BrowserLaunchSession.authorityKey();
        reSyncSessionAuthenticated = BrowserLaunchSession.authenticated();
        BrowserLaunchSession.addAuthStateListener(reSyncAuthStateListener);
        BrowserLaunchSession.addTicketListener(reSyncTicketListener);
        BrowserLaunchSession.addSessionExpiryListener(reSyncSessionExpiryListener);
    }

    public void setMinecraftAssetsEnabled(BooleanSupplier enabled) {
        gameAssets.enabled(enabled);
    }

    public void setHttpTransport(HttpTransport httpTransport) {
        if (this.httpTransport == httpTransport) {
            return;
        }
        cancelReSyncPreparation();
        reSyncContextGeneration = nextGeneration(reSyncContextGeneration);
        if (provisioningAdapter != null) {
            provisioningAdapter.invalidateHost();
        }
        this.httpTransport = httpTransport;
        provisioningAdapter = new BrowserReSyncProvisioningAdapter(this,
            httpTransport == null ? new BrowserHttpTransport() : httpTransport);
        if (!reSyncServerContext.isBlank()) {
            provisioningAdapter.invalidateServerContext(reSyncServerContext);
        }
    }

    public void activateReSyncServerContext(String serverId) {
        String value = ReSyncServerIdentity.of(serverId).serverId();
        if (!value.isBlank() && Objects.equals(reSyncServerContext, value)) {
            return;
        }
        cancelReSyncPreparation();
        if (!Objects.equals(reSyncServerContext, value) || value.isBlank()) {
            reSyncStartupServer = null;
            reSyncLoaderHint = "";
            reSyncPreparationActive = false;
        }
        reSyncContextGeneration = nextGeneration(reSyncContextGeneration);
        reSyncServerContext = value;
        if (!value.isBlank()) {
            reSyncProbePending.put(value, true);
        }
        BrowserReSyncProvisioningAdapter adapter = provisioningAdapter;
        if (adapter != null) {
            adapter.invalidateServerContext(value);
        }
    }

    private void invalidateReSyncAuthentication() {
        reSyncSessionAuthority = BrowserLaunchSession.authorityKey();
        reSyncSessionAuthenticated = BrowserLaunchSession.authenticated();
        invalidateReSyncSession();
    }

    private void rotateReSyncTicket() {
        String authority = BrowserLaunchSession.authorityKey();
        boolean authenticated = BrowserLaunchSession.authenticated();
        if (reSyncSessionAuthenticated && authenticated && Objects.equals(reSyncSessionAuthority, authority)) {
            FlowManager manager = FlowManager.getInstance();
            if (manager != null && !reSyncServerContext.isBlank()) {
                var flowClient = manager.existingFlowClient(reSyncServerContext);
                if (flowClient != null) manager.withCurrentFlowClientNow(reSyncServerContext, flowClient,
                    current -> current.renewRelaySession(BrowserLaunchSession.ticket()));
            }
            return;
        }
        reSyncSessionAuthority = authority;
        reSyncSessionAuthenticated = authenticated;
        invalidateReSyncSession();
    }

    private void invalidateReSyncSession() {
        cancelReSyncPreparation();
        reSyncStartupServer = null;
        reSyncLoaderHint = "";
        reSyncPreparationActive = false;
        reSyncContextGeneration = nextGeneration(reSyncContextGeneration);
        BrowserReSyncProvisioningAdapter adapter = provisioningAdapter;
        if (adapter != null) {
            adapter.invalidateSession();
        }
    }

    public void setReSyncRelayReadiness(String serverId, RemotelyServerApi.ReSyncReadinessReason reasonCode) {
        String canonicalServerId = ReSyncServerIdentity.of(serverId).serverId();
        if (canonicalServerId.isBlank()) {
            return;
        }
        RemotelyServerApi.ReSyncReadinessReason reason = reasonCode == null
            ? RemotelyServerApi.ReSyncReadinessReason.UNKNOWN : reasonCode;
        reSyncRelayReasons.put(canonicalServerId, reason);
        reSyncProbePending.remove(canonicalServerId);
        if (reason == RemotelyServerApi.ReSyncReadinessReason.NONE) {
            lastReSyncReadinessMessage = "";
        }
    }

    public RemotelyServerApi.ReSyncReadinessReason reSyncRelayReason(String serverId) {
        String canonicalServerId = ReSyncServerIdentity.of(serverId).serverId();
        if (canonicalServerId.isBlank()) {
            return RemotelyServerApi.ReSyncReadinessReason.UNKNOWN;
        }
        return reSyncRelayReasons.getOrDefault(canonicalServerId, RemotelyServerApi.ReSyncReadinessReason.UNKNOWN);
    }

    public boolean reSyncProbePending(String serverId) {
        String canonicalServerId = ReSyncServerIdentity.of(serverId).serverId();
        return !canonicalServerId.isBlank() && Objects.equals(reSyncServerContext, canonicalServerId)
            && Boolean.TRUE.equals(reSyncProbePending.get(canonicalServerId));
    }

    public void reportReSyncReadiness(String message) {
        String value = message == null || message.isBlank() ? "ReSync Capability Is Unavailable" : message.trim();
        if (value.equals(lastReSyncReadinessMessage)) {
            return;
        }
        lastReSyncReadinessMessage = value;
        notify("ReSync", value, ReSyncNotificationLevel.WARN);
    }

    @Override
    public void setScreen(Screen screen) {
        screenManager.setScreen(screen);
    }

    @Override
    public Screen getCurrentScreen() {
        return screenManager.getCurrentScreen();
    }

    @Override
    public ServerScreenHost serverScreenHost(RemotelyClient client) {
        if (serverScreenHost == null || serverScreenClient != client) {
            if (serverScreenHost != null) {
                serverScreenHost.close();
            }
            activateReSyncServerContext("");
            serverScreenClient = client;
            serverScreenHost = new BrowserServerScreenHost(this, client);
        }
        return serverScreenHost;
    }

    @Override
    public void ensureTextRenderer() {
        TextRenderer.setTextRendererAdapter(new BrowserTextRenderer());
        RemotelyClient.tr = TextRenderer.getTr();
    }

    @Override
    public MinecraftGameAssets getGameAssets() {
        return gameAssets;
    }

    @Override
    public Object getFontIdentifier(String namespace, String path) {
        String resolvedNamespace = namespace == null || namespace.isBlank() ? "minecraft" : namespace;
        String resolvedPath = path == null ? "" : path;
        return resolvedNamespace.toLowerCase() + ":" + resolvedPath.toLowerCase();
    }

    @Override
    public void openParentScreen(Screen currentScreen, Object parent) {
        if (parent instanceof Screen screen) {
            screenManager.setScreen(screen);
        }
    }

    @Override
    public void openRemoteFiles(Object parent, Object target) {
        ServerModels.ClientServerView server = target instanceof ServerModels.ClientServerView view ? view : serverView(target);
        if (server == null) {
            notify("File Explorer", "Remote Files Are Unavailable For This Item", ReSyncNotificationLevel.WARN);
            return;
        }
        RemotelyClient client = RemotelyClient.INSTANCE;
        if (client == null) {
            notify("File Explorer", "Remote Files Are Unavailable", ReSyncNotificationLevel.WARN);
            return;
        }
        Screen current = parent instanceof Screen screen ? screen : getCurrentScreen();
        serverScreenHost(client).openFileExplorer(current, server);
    }

    @Override
    public void openTerminal(Object parent, Object instance, RemotelyClient client) {
        if (client == null) return;
        Screen current = parent instanceof Screen screen ? screen : getCurrentScreen();
        if (instance == null) {
            setScreen(new ServerDetailsScreen(current, client));
            return;
        }
        ServerModels.ClientServerView server = serverView(instance);
        if (server == null || !serverScreenHost(client).mergeServerScreen(current, server, false)) {
            notify("Terminal", "Terminal Is Unavailable For This Server", ReSyncNotificationLevel.WARN);
        }
    }

    @Override
    public void openFileExplorer(Object parent, Object path, RemotelyClient client) {
        openRemoteFiles(parent, path);
    }

    @Override
    public void openInstanceFiles(Object parent, Object instance, RemotelyClient client) {
        openRemoteFiles(parent, instance);
    }

    @Override
    public void openServerTwin(Object parent, Object instance, RemotelyClient client) {
        if (client == null) return;
        ServerModels.ClientServerView server = serverView(instance);
        Screen current = parent instanceof Screen screen ? screen : getCurrentScreen();
        if (server == null || !serverScreenHost(client).mergeServerScreen(current, server, true)) {
            notify("Development", "Development Is Unavailable For This Server", ReSyncNotificationLevel.WARN);
        }
    }

    @Override
    public void openReSyncStudio(Object parent, Object instance, Object serverView, RemotelyClient client) {
        if (client == null || client.getFlowManager() == null) return;
        ServerModels.ClientServerView server = serverView instanceof ServerModels.ClientServerView value ? value : this.serverView(instance);
        if (server == null) {
            notify("ReSync", "ReSync Studio Is Unavailable For This Server", ReSyncNotificationLevel.WARN);
            return;
        }
        ReSyncServerIdentity identity = ReSyncServerIdentity.from(null, server);
        if (!identity.present()) return;
        String id = identity.serverId();
        client.getFlowManager().openReSyncStudio(id, server, server.loader == null ? "" : server.loader,
                server.name == null ? id : server.name);
    }

    private ServerModels.ClientServerView serverView(Object target) {
        return BrowserServerScreenHost.asServerView(target);
    }

    @Override
    public void setClipboard(String text) {
        clipboardHandler.setClipboard(text);
    }

    @Override
    public void notify(String title, String message, ReSyncNotificationLevel level) {
        Notification.Type type = switch (level == null ? ReSyncNotificationLevel.INFO : level) {
            case ERROR -> Notification.Type.ERROR;
            case WARN -> Notification.Type.WARN;
            case SUCCESS -> Notification.Type.SUCCESS;
            default -> Notification.Type.INFO;
        };
        new Notification(title == null ? "Remotely" : title, message == null ? "" : message, type);
    }

    @Override
    public boolean authenticated() {
        return BrowserLaunchSession.authenticated();
    }

    @Override
    public void signIn(Screen current) {
        Screen parent = current == null ? getCurrentScreen() : current;
        if (parent instanceof ReStudioLoginScreen) return;
        setScreen(new ReStudioLoginScreen(parent, () -> setScreen(parent)));
    }

    @Override
    public void signOut(Screen current) {
        if (serverScreenHost != null) {
            serverScreenHost.onAuthenticationInvalidated();
        }
        BrowserLaunchSession.signOut();
    }

    @Override
    public void openMarketplaceListing(String marketplaceSlug, String listingSlug, boolean control) {
        Screen parent = screenManager.getCurrentScreen();
        screenManager.setScreen(new MarketplaceDetailsScreen(parent, marketplaceSlug, listingSlug, control, marketplaceDetailsProvider));
    }

    @Override
    public void openPermissionManager(Screen parent, Object client) {
        if (client instanceof ReSyncLuckPermsClient permissions) {
            screenManager.setScreen(new LuckPermsDashboardScreen(parent, permissions));
        } else {
            notify("Permissions", "Permission Management Unavailable", ReSyncNotificationLevel.WARN);
        }
    }

    public void setMarketplaceDetailsProvider(MarketplaceDetailsProvider marketplaceDetailsProvider) {
        this.marketplaceDetailsProvider = marketplaceDetailsProvider;
        BrowserMarkdownImageUploadSupport.install(this, hostActionHandler, marketplaceDetailsProvider);
    }

    public void close() {
        BrowserMarkdownImageUploadSupport.close(this);
        cancelReSyncPreparation();
        reSyncContextGeneration = nextGeneration(reSyncContextGeneration);
        if (provisioningAdapter != null) {
            provisioningAdapter.invalidateHost();
        }
        BrowserLaunchSession.removeAuthStateListener(reSyncAuthStateListener);
        BrowserLaunchSession.removeTicketListener(reSyncTicketListener);
        BrowserLaunchSession.removeSessionExpiryListener(reSyncSessionExpiryListener);
        BrowserFileExplorerAdapters.close(this);
        if (serverScreenHost != null) {
            serverScreenHost.close();
            serverScreenHost = null;
        }
        try {
            hostActionHandler.close();
        } finally {
            luckPermsProvider.close();
            BrowserFileExplorerAdapters.close(this);
            serverScreenClient = null;
            marketplaceDetailsProvider = null;
            provisioningAdapter = null;
            reSyncServerContext = "";
            reSyncStartupServer = null;
            reSyncLoaderHint = "";
            reSyncSessionAuthority = "";
            reSyncSessionAuthenticated = false;
            reSyncPreparationActive = false;
            reSyncPreparation = null;
            reSyncPreparationSource = null;
            reSyncRelayReasons.clear();
            reSyncProbePending.clear();
            lastReSyncReadinessMessage = "";
        }
    }

    @Override
    public void chooseImage(String title, Consumer<HostImage> callback) {
        if (callback == null) return;
        hostActionHandler.pickFiles(false, files -> {
            if (files == null || files.isEmpty()) return;
            var file = files.getFirst();
            String data = file.dataUrl();
            int separator = data == null ? -1 : data.indexOf(',');
            callback.accept(new HostImage(file.name(), file.type(),
                BrowserHttpTransport.decodeBase64(separator < 0 ? "" : data.substring(separator + 1))));
        });
    }

    @Override
    public ReSyncFlowClientConfiguration configureFlowClient(Object client, FlowManager manager,
                                                               ReSyncFlowClientFactory requestedFactory,
                                                               ReSyncFlowClientConfiguration defaults) {
        ReSyncConnectionProfileProvider profiles = new ReSyncConnectionProfileProvider() {
            @Override
            public ReSyncConnectionManager.ReSyncConnectionProfile resolve(ReSyncServerIdentity identity) {
                if (identity == null || !identity.present() || !BrowserLaunchSession.authenticated()) {
                    return null;
                }
                String endpoint = BrowserLaunchSession.reSyncUrl(identity.serverId());
                String ticket = BrowserLaunchSession.ticket();
                return endpoint == null || endpoint.isBlank() || ticket == null || ticket.isBlank() ? null
                    : new ReSyncConnectionManager.ReSyncConnectionProfile(endpoint, BrowserLaunchSession.authorityKey());
            }

            @Override
            public boolean connectionAllowed(ReSyncServerIdentity identity, ReSyncConnectionManager.ReSyncConnectionProfile profile) {
                return identity != null && identity.present() && BrowserLaunchSession.authenticated()
                    && profile != null && Objects.equals(profile.wsUrl(), BrowserLaunchSession.reSyncUrl(identity.serverId()))
                    && Objects.equals(profile.apiKey(), BrowserLaunchSession.authorityKey());
            }

            @Override
            public boolean connectionPending(ReSyncServerIdentity identity) {
                return identity != null && identity.present() && reSyncProbePending(identity.serverId());
            }
        };
        ReSyncLuckPermsProvider provider = luckPermsProvider;
        return new ReSyncFlowClientConfiguration(requestedFactory == null ? defaults.factory() : requestedFactory,
            profiles, this::notify, defaults.nodeRegistry(), defaults.context().withLuckPermsProvider(provider));
    }

    @Override
    public Object provisioningAdapter() {
        if (provisioningAdapter == null) {
            provisioningAdapter = new BrowserReSyncProvisioningAdapter(this,
                httpTransport == null ? new BrowserHttpTransport() : httpTransport);
            if (!reSyncServerContext.isBlank()) {
                provisioningAdapter.invalidateServerContext(reSyncServerContext);
            }
        }
        return provisioningAdapter;
    }

    @Override
    public void prepareReSyncServerContext(String serverId, ServerModels.ClientServerView server, String loaderHint) {
        prepareReSyncServerContextAsync(serverId, server, loaderHint);
    }

    @Override
    public Async<ReSyncProvisioningService.StartupProbeResult> prepareReSyncServerContextAsync(
            String serverId, ServerModels.ClientServerView server, String loaderHint) {
        String actualServerId = ReSyncServerIdentity.from(serverId, server).serverId();
        if (actualServerId.isBlank()) {
            return Async.completed(new ReSyncProvisioningService.StartupProbeResult(
                ReSyncProvisioningService.StartupStatus.NOT_SUPPORTED, false, false));
        }
        if (Objects.equals(reSyncServerContext, actualServerId) && reSyncPreparation != null && !reSyncPreparation.isDone()) {
            return reSyncPreparation;
        }
        activateReSyncServerContext(actualServerId);
        reSyncStartupServer = server;
        reSyncLoaderHint = loaderHint == null ? "" : loaderHint;
        reSyncPreparationActive = true;
        Async<ReSyncProvisioningService.StartupProbeResult> guarded = Async.pending();
        guarded.onCancel(() -> cancelReSyncPreparation(guarded));
        return startReSyncPreparation(actualServerId, server, reSyncLoaderHint, guarded);
    }

    private Async<ReSyncProvisioningService.StartupProbeResult> startReSyncPreparation(
            String serverId, ServerModels.ClientServerView server, String loaderHint,
            Async<ReSyncProvisioningService.StartupProbeResult> guarded) {
        long generation = reSyncContextGeneration;
        BrowserReSyncProvisioningAdapter adapter = (BrowserReSyncProvisioningAdapter) provisioningAdapter();
        Async<ReSyncProvisioningService.StartupProbeResult> source;
        try {
            source = adapter.computeStartupState(serverId, server, loaderHint);
        } catch (Throwable failure) {
            if (reSyncPreparation == guarded) {
                reSyncPreparation = null;
                reSyncPreparationSource = null;
            }
            guarded.fail(failure);
            return guarded;
        }
        reSyncPreparation = guarded;
        reSyncPreparationSource = source;
        source.whenComplete((result, failure) -> {
            if (reSyncPreparation != guarded || reSyncPreparationSource != source) {
                return;
            }
            reSyncPreparation = null;
            reSyncPreparationSource = null;
            if (!isCurrentReSyncContext(serverId, generation) || source.isCancelled()) {
                guarded.cancel();
                return;
            }
            if (failure != null) {
                guarded.fail(failure);
            } else {
                guarded.complete(result);
            }
        });
        return guarded;
    }

    private void cancelReSyncPreparation(Async<ReSyncProvisioningService.StartupProbeResult> preparation) {
        if (reSyncPreparation != preparation) {
            return;
        }
        Async<ReSyncProvisioningService.StartupProbeResult> source = reSyncPreparationSource;
        reSyncPreparation = null;
        reSyncPreparationSource = null;
        if (source != null && !source.isDone()) {
            source.cancel();
        }
    }

    private void cancelReSyncPreparation() {
        Async<ReSyncProvisioningService.StartupProbeResult> preparation = reSyncPreparation;
        Async<ReSyncProvisioningService.StartupProbeResult> source = reSyncPreparationSource;
        reSyncPreparation = null;
        reSyncPreparationSource = null;
        if (source != null && !source.isDone()) {
            source.cancel();
        }
        if (preparation != null && !preparation.isDone()) {
            preparation.cancel();
        }
    }

    @Override
    public void reportReSyncPreparationFailure(String message) {
        reportReSyncReadiness(message);
    }

    private boolean isCurrentReSyncContext(String serverId, long generation) {
        return generation == reSyncContextGeneration && Objects.equals(reSyncServerContext, serverId)
            && ApplicationHostRegistry.current() == this;
    }

    private long nextGeneration(long value) {
        long next = value + 1L;
        return next <= 0L ? 1L : next;
    }



    @Override
    public FlowManagerUiAdapter flowManagerUiAdapter() {
        return flowManagerUiAdapter;
    }

    @Override
    public Identifier registerRemoteImage(String source) {
        return source == null || source.isBlank() ? null : screenManager.imageAssets().registerRemoteImage(source);
    }

    @Override
    public void releaseRemoteImage(Identifier image) {
        if (image != null) screenManager.imageAssets().releaseImage(image);
    }

    @Override
    public boolean shouldCloseRootScreen() {
        return false;
    }

    @Override
    public boolean openExternal() {
        return false;
    }

    @Override
    public boolean supportsDesktopIntegrations() {
        return false;
    }

    @Override
    public boolean managesPrimaryScreen() {
        return true;
    }

    @Override
    public String getGameVersion() {
        return null;
    }

    @Override
    public String getGameUserName() {
        return null;
    }

    @Override
    public String getGameUUID() {
        return null;
    }

    public BrowserClipboardHandler clipboardHandler() {
        return clipboardHandler;
    }

    public BrowserHostActionHandler hostActionHandler() {
        return hostActionHandler;
    }

}
