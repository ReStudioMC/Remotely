package redxax.oxy.remotely.ui.server;

import restudio.rebase.instance.InstanceState;
import restudio.rebase.backend.DeveloperCapabilityProviders;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.backend.FileExplorerProviders;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.developer.search.WorkspaceFileIndex;
import restudio.rebase.developer.search.WorkspaceFileCatalog;
import restudio.rebase.developer.search.LogStartSearch;
import restudio.rebase.developer.search.BackupStartSearch;
import restudio.rebase.resource.marketplace.ResourceStartSearch;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProvider;
import restudio.rebase.resource.marketplace.ResourceBrowserContext;
import restudio.rebase.ui.screens.explorer.FileExplorerScreen;
import restudio.rebase.ui.widgets.LifecycleButtonWidget;
import redxax.oxy.remotely.network.NetworkAdoptionReport;
import redxax.oxy.remotely.network.HostedNetworkClient;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyRecentItem;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import redxax.oxy.remotely.discord.DiscordRpcBridge;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkGroupAttachmentTransaction;
import redxax.oxy.remotely.network.NetworkManager;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkRuntimeNodePresence;
import redxax.oxy.remotely.network.NetworkRuntimeNodeStatus;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.ui.widgets.ReactorPlanWidget;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopBounds;
import restudio.rescreen.ui.desktop.DesktopGroup;
import restudio.rescreen.ui.desktop.DesktopGroupWidget;
import restudio.rebase.ui.widgets.account.ReStudioInboxButton;
import restudio.rebase.ui.screens.collaboration.CollaborationScreen;
import restudio.rescreen.ui.desktop.DesktopIconWidget;
import restudio.rescreen.ui.desktop.DesktopMetrics;
import restudio.rescreen.ui.desktop.DesktopShellScreen;
import restudio.rescreen.ui.desktop.DesktopTaskbarHelper;
import restudio.rescreen.ui.desktop.StartEntry;
import restudio.rescreen.ui.desktop.StartMenuWidget;
import restudio.rescreen.ui.desktop.StartHistory;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.ui.rescreen.layout.DesktopLayout;
import restudio.rescreen.ui.rescreen.layout.FreeLayout;
import restudio.rescreen.ui.widgets.*;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static restudio.rescreen.util.SoundUtils.playSound;

public class ServerManagerScreen extends DesktopShellScreen {
    @Override
    protected HttpTransport desktopWeatherTransport() { return serverHost().weatherTransport(); }
    private final RemotelyClient remotelyClient;
    private ServerScreenHost cachedServerHost;
    private PopupWidget createChoicePopup;
    private PopupWidget addServerPopup;
    private IconButton createServerButton;
    private IconButton createNetworkButton;
    private IconButton emptyServerButton;
    private IconButton modpackServerButton;
    private IconButton importServerButton;
    private PopupWidget remoteHostTypePopup;
    private PopupWidget remoteHostPopup;
    private TextInputWidget remoteHostNameInput;
    private TextInputWidget remoteHostUserInput;
    private TextInputWidget remoteHostIpInput;
    private TextInputWidget remoteHostPortInput;
    private TextInputWidget remoteHostPasswordInput;
    private TextInputWidget remoteHostSftpPasswordInput;
    private TextInputWidget remoteHostKeyPathInput;
    private TextInputWidget remoteHostKeyPassphraseInput;
    private TextInputWidget remoteHostRegistryPathInput;
    private RemoteHostType remoteHostType = RemoteHostType.SSH;
    private TabSwitchWidget remoteHostAuthModeSwitch;
    private TabsManager.Tab remoteHostEditTab;
    private boolean remoteHostEditActions;
    private long remoteHostFormGeneration;
    private final Object parent;
    private IconButton userButton;
    private boolean serverManagerContextMenuPressed;
    private WorkspaceFileCatalog startFileIndex;
    private ResourceStartSearch startResourceSearch;
    private LogStartSearch startLogSearch;
    private BackupStartSearch startBackupSearch;
    private ServerStartActions startServerActions;
    private final Map<String, StartBackupBinding> startBackups = new LinkedHashMap<>();
    private List<StartEntry> startLogEntries = List.of();
    private List<StartEntry> startBackupEntries = List.of();
    private List<StartEntry> startActionEntries = List.of();
    private String startLogStatus = "";
    private String startBackupStatus = "";
    private boolean startResourceLoading;
    private boolean startLogLoading;
    private boolean startBackupLoading;
    private long startSpecialRevision;
    private long startPublishedSpecial = -1;
    private long startBoundSpecial = -1;
    private final Map<String, StartMarketBinding> startMarkets = new LinkedHashMap<>();
    private ResourceStartSearch.Target startGlobalMarket;
    private List<StartEntry> startResourceEntries = List.of();
    private String startResourceStatus = "";
    private StartMenuWidget.Search startSearch = new StartMenuWidget.Search("", "", null, "", false);
    private long startBoundResources = -1;
    private long startResourceRevision;
    private long startPublishedResources = -1;
    private final Map<String, StartFileRows> startFileRows = new LinkedHashMap<>();
    private Map<String, ServerModels.ClientServerView> startServers = Map.of();
    private List<StartEntry> startCatalog = List.of();
    private boolean startCatalogDirty = true;
    private String startAuthority = "";
    private String startScope = "";
    private String startHistoryAccount;
    private RemotelyConfigStore startHistoryConfig;
    private final Map<String, StartFileBinding> startBindings = new LinkedHashMap<>();
    private long startCatalogRevision;
    private long startBoundCatalog = -1;
    private long startPublishedCatalog = -1;
    private long startPublishedIndex = -1;
    private long startNavigationGeneration;
    private Object startApi;
    private boolean startSettingsListening;
    private final Consumer<ServerSettingsSnapshot> startSettingsChanged = ignored -> ScreenManager.getInstance().execute(() -> {
        if (this.reactiveRefreshEnabled) startCatalogDirty = true;
    });

    private record StartFileBinding(String backend, String path, Map<String, String> credentials, Object owner, ServerScreenHost.StorageFiles files, DeveloperCapabilityProvider.FileWatch watcher) {
        private StartFileBinding {
            credentials = Map.copyOf(credentials);
        }
    }

    private record StartBackupBinding(StartFileBinding stamp, BackupStartSearch.Backend backend) {}
    private record StartMarketStamp(StartFileBinding source, String version, String loader) {}
    private record StartMarketBinding(StartMarketStamp stamp, ResourceStartSearch.Target target) {}
    private record StartFileRows(StartFileBinding source, WorkspaceFileCatalog.Result result, String name, Identifier icon, List<StartEntry> entries) {}

    private boolean networkOperationInFlight;
    private final Set<String> deletingServers = new HashSet<>();

    private static final String ROW_REMOTE_HOST_USER = "remoteHostUser";
    private static final String ROW_REMOTE_HOST_IP = "remoteHostIp";
    private static final String ROW_REMOTE_HOST_PORT = "remoteHostPort";
    private static final String ROW_REMOTE_HOST_PASSWORD = "remoteHostPassword";
    private static final String ROW_REMOTE_HOST_SFTP_PASSWORD = "remoteHostSftpPassword";
    private static final String ROW_REMOTE_HOST_AUTH_MODE = "remoteHostAuthMode";
    private static final String ROW_REMOTE_HOST_KEY_PATH = "remoteHostKeyPath";
    private static final String ROW_REMOTE_HOST_PASSPHRASE = "remoteHostPassphrase";
    private static final String ROW_REMOTE_HOST_REGISTRY_PATH = "remoteHostRegistryPath";

    private enum RemoteHostType {
        SSH("SSH", "SSH", "Connect With A Password Or SSH Key", false),
        PTERO("PTERO", "Pterodactyl", "Connect Your Panel With A Client API Key", true),
        CALAGOPUS("CALAGOPUS", "Calagopus", "Connect Your Panel With An API Key", true),
        CRAFTY("CRAFTY", "Crafty Controller", "Connect Your Panel With An API Token", true);

        private final String id;
        private final String label;
        private final String description;
        private final boolean panel;

        RemoteHostType(String id, String label, String description, boolean panel) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.panel = panel;
        }

        private static RemoteHostType fromId(String id) {
            for (RemoteHostType type : values()) {
                if (type.id.equalsIgnoreCase(id)) return type;
            }
            throw new IllegalArgumentException("Unknown Host Type: " + id);
        }
    }

    private static Identifier unknown, serverIcon, paper, vanilla, fabric, forge, neoforge, waterfall, velocity, leaf, quilt, spigot, bukkit, purpur;
    private final List<ServerModels.ClientServerView> restudioInstances = new ArrayList<>();
    private final Map<String, ServerModels.ClientServerView> restudioServerViews = new HashMap<>();
    private String restudioInstancesAccount = "";
    private final Map<String, ServerModels.ClientServerView> browserServers = new LinkedHashMap<>();
    private final ServerIconManager iconManager;
    private boolean initializedOnce;
    private int remoteHostSelectionToken;
    private static final long PTERO_STATE_POLL_MS = 30_000L;
    private long lastPteroStatePollMs;
    private boolean pteroStatePollInFlight;
    private Container noServersOverlay;
    private boolean noServersOverlayVisible;
    private IconMessage localNoServersIcon;
    private IconMessage reactorsNoServersIcon;
    private IconMessage pteroNoServersIcon;
    private IconButton reactorInfo;
    private IconButton pteroInfo;
    private final List<ReactorPlanWidget> reactorPlanCards = new ArrayList<>();
    private final List<ServerModels.Plan> reactorPlans = new ArrayList<>();
    private String selectedReactorPlanName;
    private boolean reactorPlanSelectionVisible;
    private static final int REACTOR_PLAN_CARD_GAP = 14;
    private static final int REACTOR_PLAN_CARD_SIDE_MARGIN = 14;
    private long lastPersistentLocalProcessPollMs;
    private volatile boolean persistentLocalProcessPollInFlight;
    private static final long PERSISTENT_LOCAL_PROCESS_POLL_MS = 2000;
    private final Runnable instanceChangeListener = this::queueStartSourceRefresh;
    private final Runnable networkChangeListener = this::queueServerRefresh;
    private final Runnable runtimeChangeListener = this::queueServerRefresh;
    private final Runnable authStateListener = this::refreshForAuthStateChange;
    private boolean serverRefreshQueued;
    private long contextMenuRequestGeneration;
    private long restudioFetchGeneration;
    private boolean restudioFetchInFlight;
    private String restudioFetchAccount = "";
    private long callbackGeneration;
    private long networkFetchGeneration;
    private boolean networkRefreshRequired;
    private List<DesktopGroup> instanceGroups = new ArrayList<>();
    private List<ServerScreenHost.NetworkView> networkViews = List.of();
    private ServerScreenHost.AccountIdentity networkViewsAccount;
    private List<ServerModels.ClientServerView> currentServerViews = List.of();
    private final Map<TabsManager.Tab, List<ServerModels.ClientServerView>> pendingServerViews = new IdentityHashMap<>();
    private final Set<String> pendingNetworkMembershipInstances = new HashSet<>();
    private boolean instanceChangeListenerRegistered;
    private boolean authStateListenerRegistered;
    private volatile boolean reactiveRefreshEnabled;

    private record AccountAvatarFence(long generation, ServerScreenHost host, ServerScreenHost.AccountIdentity account) {
    }

    public ServerManagerScreen(Object parent, RemotelyClient remotelyClient) {
        super();
        this.parent = parent;
        this.remotelyClient = remotelyClient;
        ServerScreenHost host = remotelyClient.getHost().serverScreenHost(remotelyClient);
        this.cachedServerHost = host;
        this.iconManager = new ServerIconManager(host.iconProvider());
        this.preserveStateOnDisplay = true;
    }

    public String getDesktopAppId() {
        return "server-manager";
    }

    public String getDesktopAppTitle() {
        return "Server Manager";
    }

    public String getDesktopAppIconPath() {
        return "remotely.png";
    }

    @Override
    public boolean shouldForceSuperScreen() {
        return Config.desktopMode;
    }

    private ServerScreenHost serverHost() {
        if (cachedServerHost == null) {
            cachedServerHost = remotelyClient.getHost().serverScreenHost(remotelyClient);
        }
        return cachedServerHost;
    }

    private boolean browserRuntime() {
        return remotelyClient.getComposition().environment() == RemotelyComposition.Environment.BROWSER;
    }

    private String browserHostKey(Object data) {
        if (data instanceof ServerScreenHost.HostView host) {
            return "remote:" + host.id();
        }
        if ("RESTUDIO_MARKER".equals(data)) {
            return "restudio";
        }
        return data == null ? "local" : "";
    }

    private void restoreBrowserHostTab() {
        if (!browserRuntime() || !serverHost().authenticated()) {
            return;
        }
        String savedKey = remotelyClient.getBrowserViewState().hostKey();
        if (savedKey.isBlank()) {
            return;
        }
        for (int index = 0; index < tabs().getTabs().size(); index++) {
            TabsManager.Tab tab = tabs().getTabs().get(index);
            if (savedKey.equals(browserHostKey(tab.getData()))) {
                tabs().setActiveTab(index);
                return;
            }
        }
    }

    private boolean isActiveScreen() {
        return reactiveRefreshEnabled && ScreenManager.getInstance().getCurrentScreen() == this;
    }

    private boolean isCurrentTab(TabsManager.Tab tab) {
        return isActiveScreen() && tab != null && tabs().getTabs().contains(tab);
    }

    private boolean isCurrentCallback(long generation) {
        return isActiveScreen() && generation == callbackGeneration;
    }

    private boolean reactorOnlyServerManager() {
        return serverHost().serverManagerMode() == ServerScreenHost.ServerManagerMode.REACTOR_ONLY;
    }

    @Override
    public void init() {
        super.init();
        reactiveRefreshEnabled = true;
        if (!startSettingsListening) {
            ServerSettingsRegistry.getInstance().addListener(startSettingsChanged);
            startSettingsListening = true;
        }
        if (!authStateListenerRegistered) {
            serverHost().addAuthStateListener(authStateListener);
            authStateListenerRegistered = true;
        }
        DiscordRpcBridge.setManagerActive();
        if (initializedOnce) {
            registerInstanceChangeListener();
            registerNetworkChangeListener();
            registerRuntimeChangeListener();
            initNoServersOverlay();
            if (Config.desktopMode) addDesktopControls();
            if (Config.desktopMode && taskbarHelper != null) {
                taskbarHelper.attach();
            }
            refreshAccountButton();
            if (authenticated() || reactorOnlyServerManager()) {
                maybeAddReStudioTab();
            } else {
                removeReStudioTab();
            }
            updatePositions();
            if (networkAccountChanged()) refreshForAuthStateChange();
            else if (networkRefreshRequired) refreshNetworks();
            return;
        }
        registerInstanceChangeListener();
        registerNetworkChangeListener();
        registerRuntimeChangeListener();
        reloadInstancesSmartly();
        loadIcons();
        createPopups();

        Map<String, Identifier> defaultIcons = new HashMap<>();
        defaultIcons.put("vanilla", vanilla);
        defaultIcons.put("fabric", fabric);
        defaultIcons.put("forge", forge);
        defaultIcons.put("neoforge", neoforge);
        defaultIcons.put("paper", paper);
        defaultIcons.put("purpur", purpur);
        defaultIcons.put("quilt", quilt);
        defaultIcons.put("spigot", spigot);
        defaultIcons.put("bukkit", bukkit);
        defaultIcons.put("leaf", leaf);
        defaultIcons.put("velocity", velocity);
        defaultIcons.put("waterfall", waterfall);
        defaultIcons.put("unknown", unknown);
        iconManager.setDefaultIcons(defaultIcons);

        int taskbarHeight = DesktopMetrics.DEFAULT_TASKBAR_HEIGHT;
        ServerScreenHost.AccountIdentity account = serverHost().accountIdentity();

        userButton = new IconButton.Builder()
            .imagePath(account.avatarAsset())
            .label(account.displayName())
            .onClick(this::onAccountButtonClick)
            .size(18, 18)
            .autoWidthOnTextChange(true)
            .build();

        IconButton terminalButton = new IconButton.Builder()
            .imagePath("terminal.png")
            .hint("Terminal")
            .onClick(() -> serverHost().openGlobalTerminal(this))
            .size(18, 18)
            .autoWidthOnTextChange(true)
            .build();

        IconButton fileExplorerButton = new IconButton.Builder()
            .imagePath("explorer.png")
            .hint("File Explorer")
            .onClick(this::openFileExplorer)
            .size(18, 18)
            .autoWidthOnTextChange(true)
            .build();

        IconButton settingsButton = new IconButton.Builder()
            .imagePath("remotely.png")
            .hint("Settings")
            .onClick(() -> serverHost().openSettings(this))
            .size(18, 18)
            .autoWidthOnTextChange(true)
            .build();

        setupDesktopTaskbar(taskbarHeight);
        var headerBuilder = header();
        ServerScreenHost.EnvironmentNotice notice = serverHost().environmentNotice();
        if (!Config.desktopMode && notice.visible()) {
            headerBuilder.addLeft(new IconButton.Builder().imagePath(notice.icon()).label(notice.label()).hint(notice.description())
                    .active(false).size(18, 18).autoWidthOnTextChange(true).build());
        }
        if (serverHost().supports(ServerScreenHost.Action.GLOBAL_TERMINAL)) headerBuilder.addLeft(terminalButton);
        if (serverHost().supports(ServerScreenHost.Action.FILE_EXPLORER)) headerBuilder.addLeft(fileExplorerButton);
        if (!Config.desktopMode && serverHost().supports(ServerScreenHost.Action.HOST_SETTINGS)) headerBuilder.addLeft(settingsButton);
        if (serverHost().supports(ServerScreenHost.Action.SIGN_IN) || serverHost().supports(ServerScreenHost.Action.SIGN_OUT)) headerBuilder.addLeft(userButton);
        headerBuilder.build();
        if (Config.desktopMode) addDesktopControls();
        refreshAccountAvatar();

        if (Config.desktopMode) {
            taskbarHelper = new DesktopTaskbarHelper(this, header(), header().leftButtons.size(), () -> desktopBounds().taskbarTabs().x());
            if (serverHost().supports(ServerScreenHost.Action.GLOBAL_TERMINAL)) {
                taskbarHelper.pinApp("server-details", terminalButton, () -> serverHost().openGlobalTerminal(this), "Terminal");
            }
            if (serverHost().supports(ServerScreenHost.Action.FILE_EXPLORER)) {
                taskbarHelper.pinApp("file-explorer", fileExplorerButton, this::openFileExplorer, "File Explorer");
            }
            taskbarHelper.attach();
        } else {
            taskbarHelper = null;
        }

        TabsManager.Builder tabsBuilder = tabs().builder()
            .rightToLeft(true)
            .allowAdd(true)
            .allowRename(false).allowReorder(false).allowClose(false)
            .onPlusButtonClicked(() -> runManagerAction(ServerScreenHost.Action.REMOTE_HOST,
                    () -> serverHost().openRemoteHost(this)))
            .onTabSelected(this::onHostTabSelected)
            .onTabContextMenu(this::onHostTabContextMenu);
        layoutDesktopTabs(tabsBuilder);
        tabsBuilder.build();
        updateManagerActionControls();

        DesktopBounds.Bounds contentBounds = desktopBounds().content();
        Container desktopContainer = createContainer("desktop", contentBounds.x(), contentBounds.y(), contentBounds.width(), contentBounds.height());
        DesktopLayout localLayout = new DesktopLayout();
        localLayout.setOnReorder(() -> saveServerOrder(desktopContainer, null));
        configureNetworkDrop(localLayout);
        desktopContainer.layout(localLayout).backgroundDrawing(false).enableSelecting(true).enableDoubleClick(false).disableScissorRegion(true).enableDoubleClick(false);

        setActiveContainer(desktopContainer);
        initNoServersOverlay();
        populateHostTabs();
        applyDesktopContentBounds();
        updatePositions();
        initializedOnce = true;
    }

    protected void setupNoServersOverlay(Container overlayContainer) {
        localNoServersIcon = new IconMessage(width / 2 - 32, height / 4, 64, 64, "Welcome To Remotely!\nI Guess We're Locally Now...\nClick Create To Start!\n\n\n Quick Tips:\nMiddle Click To Close Tabs\nSign In To Report Bugs & Give Feedback\nThere's A Very Powerfull Desktop Mode In The Settings!", "remotely.png");
        reactorsNoServersIcon = new IconMessage(width / 2 - 32, height / 4, 64, 64,
            "Reactor By ReStudio\nHigh-End & Affordable\nHosting For Everyone.\nOrder And Control Your\nServer Right Here & Now!", "Reactor.png");
        pteroNoServersIcon = new IconMessage(width / 2 - 32, height / 4, 64, 64, "Panel Host\nLoading Servers...\nOpen Your Panel If No Servers Appear.", "server.png");
        reactorInfo = new IconButton.Builder().size(300, 18).label(authenticated() ? "Learn More Here" : "Sign In").imagePath("external").autoWidthOnTextChange(true).onClick(this::openReactorAccessAction).build();
        reactorInfo.setX(width / 2 - (reactorInfo.getWidth() / 2));
        reactorInfo.setY(reactorsNoServersIcon.getY() + reactorsNoServersIcon.getContentHeight() + 5);
        pteroInfo = new IconButton.Builder().size(300, 18).label("Open Panel").imagePath("external").autoWidthOnTextChange(true).onClick(this::openActivePteroPanel).build();
        pteroInfo.setX(width / 2 - (pteroInfo.getWidth() / 2));
        pteroInfo.setY(pteroNoServersIcon.getY() + pteroNoServersIcon.getHeight() + (12 * 5));

        noServersOverlay.addWidget(localNoServersIcon);
        noServersOverlay.addWidget(reactorsNoServersIcon);
        noServersOverlay.addWidget(pteroNoServersIcon);
        noServersOverlay.addWidget(reactorInfo);
        noServersOverlay.addWidget(pteroInfo);
    }

    private void ensureReactorPlanSelectionCardsCreated() {
        if (!reactorPlanCards.isEmpty()) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            ReactorPlanWidget card = new ReactorPlanWidget(0, 0, 232, 132, null);
            card.setVisible(false);
            card.setActive(false);
            final int planIndex = i;
            card.setOnAction(() -> {
                if (!reactorPlanSelectionVisible) {
                    return;
                }
                if (planIndex >= reactorPlans.size()) {
                    return;
                }
                onReactorPlanSelected(reactorPlans.get(planIndex));
            });
            reactorPlanCards.add(card);
            addDrawableChild(card);
        }
    }

    private void initNoServersOverlay() {
        if (noServersOverlay != null) {
            return;
        }
        DesktopBounds.Bounds contentBounds = desktopBounds().content();
        noServersOverlay = createContainer("server_manager_no_servers_overlay", contentBounds.x(), contentBounds.y(), contentBounds.width(), contentBounds.height());
        noServersOverlay.layout(new FreeLayout()).columns(1).padding(4).scrolling(false).enableSelecting(false).backgroundDrawing(false).disableScissorRegion(true);
        noServersOverlay.setVisible(false);
        noServersOverlay.setActive(false);
        setupNoServersOverlay(noServersOverlay);
        addDrawableChild(noServersOverlay);
    }

    private void updateNoServersOverlayVisibility(boolean visible) {
        if (noServersOverlay == null) {
            return;
        }

        Object tabData = (tabs().getActiveTab() != null) ? tabs().getActiveTab().getData() : null;
        updateOverlayIcons(tabData);

        if (noServersOverlayVisible == visible) {
            return;
        }
        noServersOverlayVisible = visible;
        noServersOverlay.setVisible(visible);
        noServersOverlay.setActive(visible);
    }

    private void showUserMenu() {
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(this);
        builder.addHeaderButton("person.png", () -> CollaborationScreen.open(this, "", ""), "Friends And Teams");
        if (serverHost().supports(ServerScreenHost.Action.INBOX)) {
            builder.addHeaderButton(new ReStudioInboxButton(() -> serverHost().openInbox(this)));
        }
        if (serverHost().supports(ServerScreenHost.Action.REPORTS)) {
            builder.addHeaderButton("report.png", () -> serverHost().openReports(this), "Reports And Feedback");
        }
        if (serverHost().supports(ServerScreenHost.Action.RESOURCES)) {
            builder.addHeaderButton("pool.png", () -> serverHost().openResources(this), "Resources");
        }
        if (serverHost().supports(ServerScreenHost.Action.SIGN_OUT)) {
            builder.addConfirmHeaderButton("close.png", () -> serverHost().signOut(this), "Sign Out", ThemeManager.getAccent("danger"));
        }

        showContextMenu(userButton.getX(), desktopBounds().taskbar().y(), builder);
    }

    private boolean isMouseOverServerManagerContextMenu(double mouseX, double mouseY) {
        for (int i = widgets.size() - 1; i >= 0; i--) {
            if (widgets.get(i) instanceof ContextMenuWidget menu && menu.isVisible() && menu.isOpen() && menu.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    private void onAccountButtonClick() {
        if (authenticated()) {
            showUserMenu();
            return;
        }
        openReStudioLogin();
    }

    private void updateOverlayIcons(Object tabData) {
        boolean isReactors = "RESTUDIO_MARKER".equals(tabData);
        boolean isPtero = tabData instanceof ServerScreenHost.HostView host && host.panel();
        updateReactorAccessAction();
        if (localNoServersIcon != null) localNoServersIcon.setVisible(!isReactors && !isPtero);
        if (reactorsNoServersIcon != null) reactorsNoServersIcon.setVisible(isReactors);
        if (reactorInfo != null) reactorInfo.setVisible(isReactors);
        if (pteroNoServersIcon != null) pteroNoServersIcon.setVisible(isPtero);
        if (pteroInfo != null) pteroInfo.setVisible(isPtero);
    }

    private void updateReactorAccessAction() {
        if (reactorInfo == null) {
            return;
        }
        boolean authenticated = authenticated();
        reactorInfo.setMessage(authenticated ? "Learn More Here" : "Sign In");
        reactorInfo.setOnClick(authenticated ? () -> serverHost().openReactorPlans(this) : this::openReStudioLogin);
        reactorInfo.setX(width / 2 - (reactorInfo.getWidth() / 2));
    }

    private void openReactorAccessAction() {
        if (authenticated()) {
            serverHost().openReactorPlans(this);
            return;
        }
        openReStudioLogin();
    }

    private void openReStudioLogin() {
        serverHost().signIn(this);
    }

    private void openActivePteroPanel() {
        Object data = tabs().getActiveTab() != null ? tabs().getActiveTab().getData() : null;
        if (data instanceof ServerScreenHost.HostView host && host.panel()) {
            serverHost().openPanel(this, host);
        }
    }

    private void refreshAccountButton() {
        if (userButton == null) {
            return;
        }
        ServerScreenHost.AccountIdentity account = serverHost().accountIdentity();
        userButton.setMessage(account.displayName());
        userButton.setOnClick(this::onAccountButtonClick);
        userButton.setIcon(account.avatarAsset());
        refreshAccountAvatar();
        updateReactorAccessAction();
    }

    private void refreshAccountAvatar() {
        if (userButton == null) {
            return;
        }
        ServerScreenHost host = serverHost();
        ServerScreenHost.AccountIdentity account = host.accountIdentity();
        if (!account.authenticated()) return;
        if (accountKey(account).isBlank()) return;
        AccountAvatarFence fence = new AccountAvatarFence(callbackGeneration, host, account);
        host.accountAvatar().whenComplete((avatar, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentAccountAvatar(fence)) {
                return;
            }
            ServerScreenHost.AccountIdentity current = host.accountIdentity();
            if (failure == null && userButton != null && compatibleAccountHydration(account, current)) {
                userButton.setMessage(current.displayName());
                userButton.setOnClick(this::onAccountButtonClick);
                if (avatar != null) userButton.setGeneratedIcon(avatar);
            }
        }));
    }

    private boolean isCurrentAccountAvatar(AccountAvatarFence fence) {
        return fence != null && fence.host() == serverHost() && isCurrentCallback(fence.generation())
                && compatibleAccountHydration(fence.account(), fence.host().accountIdentity());
    }

    static boolean compatibleAccountHydration(ServerScreenHost.AccountIdentity requested,
                                               ServerScreenHost.AccountIdentity current) {
        if (requested == null || current == null || !requested.authenticated() || !current.authenticated()) {
            return false;
        }
        if (!requested.subjectId().isBlank()) {
            return requested.subjectId().equals(current.subjectId());
        }
        return requested.displayName().equals(current.displayName());
    }

    private void refreshForAuthStateChange() {
        resetStartSearch();
        if (!reactiveRefreshEnabled) {
            return;
        }
        boolean authenticated = authenticated();
        boolean changed = networkAccountChanged();
        if (changed) {
            networkViews = List.of();
            networkViewsAccount = null;
        }
        refreshAccountButton();
        if (!authenticated) {
            restudioFetchGeneration++;
            restudioFetchInFlight = false;
            restudioFetchAccount = "";
            clearRestudioModels();
        }
        if (authenticated || reactorOnlyServerManager()) {
            maybeAddReStudioTab();
            if (authenticated) {
                fetchReStudioServers();
            }
        } else {
            removeReStudioTab();
        }
        updatePositions();
        if (changed) loadServersForAllTabs();
        refreshNetworks();
    }

    private boolean networkAccountChanged() {
        ServerScreenHost.AccountIdentity account = serverHost().accountIdentity();
        return networkViewsAccount != null && (networkViewsAccount.authenticated() != account.authenticated()
                || account.authenticated() && !compatibleAccountHydration(networkViewsAccount, account));
    }


    private void reloadInstancesSmartly() {
        serverHost().reloadInstances();
        iconManager.clearAllRemoteTracking();
        refreshNetworkViews(this::loadServersForAllTabs);
        loadServersForAllTabs();
    }

    private void refreshNetworkViews(Runnable afterRefresh) {
        contextMenuRequestGeneration++;
        long generation = callbackGeneration;
        long requestGeneration = ++networkFetchGeneration;
        ServerScreenHost host = serverHost();
        ServerScreenHost.AccountIdentity account = host.accountIdentity();
        host.networks().whenComplete((networks, failure) -> ScreenManager.getInstance().execute(() -> {
            ServerScreenHost.AccountIdentity current = host.accountIdentity();
            if (!isCurrentCallback(generation) || requestGeneration != networkFetchGeneration || host != serverHost()
                    || account.authenticated() != current.authenticated()
                    || account.authenticated() && !compatibleAccountHydration(account, current)) {
                return;
            }
            serverRefreshQueued = false;
            if (failure == null && networks != null) {
                networkViews = List.copyOf(networks);
                networkViewsAccount = account;
            } else if (failure != null) {
                new Notification("Network Refresh Failed", rootMessage(failure), Notification.Type.ERROR);
            }
            if (afterRefresh != null) {
                afterRefresh.run();
            }
        }));
    }

    void refreshNetworks() {
        networkRefreshRequired = true;
        networkFetchGeneration++;
        if (!isActiveScreen()) return;
        networkRefreshRequired = false;
        refreshNetworkViews(this::loadServersForAllTabs);
    }

    void refreshNetworks(String dissolvedId) {
        invalidateNetwork(dissolvedId);
        if (isActiveScreen()) loadServersForAllTabs();
        refreshNetworks();
    }

    private void invalidateNetwork(String dissolvedId) {
        networkViews = networkViews.stream().filter(network -> !network.id().equals(dissolvedId)).toList();
        networkRefreshRequired = true;
        networkFetchGeneration++;
    }

    private void loadIcons() {
        try {
            serverIcon = Identifier.icon("reverse.png");
            unknown = Identifier.icon("unknown.png");
            paper = Identifier.icon("paper.png");
            vanilla = Identifier.icon("vanilla.png");
            fabric = Identifier.icon("fabric.png");
            forge = Identifier.icon("forge.png");
            neoforge = Identifier.icon("neoforge.png");
            waterfall = Identifier.icon("waterfall.png");
            velocity = Identifier.icon("velocity.png");
            leaf = Identifier.icon("leaf.png");
            quilt = Identifier.icon("quilt.png");
            spigot = Identifier.icon("spigot.png");
            bukkit = Identifier.icon("bukkit.png");
            purpur = Identifier.icon("purpur.png");
        } catch (Exception e) {
            new Notification("Failed to load icons: " + e.getMessage(), Notification.Type.ERROR);
        }
    }

    private void populateHostTabs() {
        if (reactorOnlyServerManager()) {
            maybeAddReStudioTab();
            restoreBrowserHostTab();
            TabsManager.Tab activeTab = tabs().getActiveTab();
            if (activeTab != null) {
                setActiveContainer(activeTab.getContainer());
            }
            loadServersForCurrentTab();
            return;
        }
        tabs().addTab("Local", activeContainer).setData(null);
        long generation = callbackGeneration;
        serverHost().remoteHosts().whenComplete((hosts, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure == null && hosts != null) {
                for (ServerScreenHost.HostView host : hosts) {
                    DesktopBounds.Bounds contentBounds = desktopBounds().content();
                    Container c = createContainer("desktop_remote_" + host.id(), contentBounds.x(), contentBounds.y(), contentBounds.width(), contentBounds.height());
                    DesktopLayout remoteLayout = new DesktopLayout();
                    remoteLayout.setOnReorder(() -> saveServerOrder(c, host));
                    configureNetworkDrop(remoteLayout);
                    c.layout(remoteLayout).backgroundDrawing(false).enableSelecting(true).disableScissorRegion(true);
                    TabsManager.Tab hostTab = tabs().addTab(host.name(), c);
                    hostTab.setData(host);
                    serverHost().refreshRemoteInstance(host).whenComplete((ignored, refreshFailure) ->
                            ScreenManager.getInstance().execute(() -> {
                                if (isCurrentCallback(generation) && tabs().getTabs().contains(hostTab)) {
                                    loadServersForTab(hostTab);
                                }
                            }));
                }
            }
            maybeAddReStudioTab();
            int savedIndex = remotelyClient.getSavedTabIndex();
            tabs().setActiveTab(Math.min(savedIndex, tabs().getTabs().size() - 1));
            loadServersForCurrentTab();
        }));
    }

    private void maybeAddReStudioTab() {
        if (hasReStudioTab()) {
            if (authenticated()) {
                fetchReStudioServers();
            }
            return;
        }
        DesktopBounds.Bounds contentBounds = desktopBounds().content();
        Container container = createContainer("desktop_restudio", contentBounds.x(), contentBounds.y(), contentBounds.width(), contentBounds.height());
        DesktopLayout remoteLayout = new DesktopLayout();
        configureNetworkDrop(remoteLayout);
        container.layout(remoteLayout).backgroundDrawing(false).enableSelecting(true).disableScissorRegion(true);
        tabs().addTab("Reactors", container).setData("RESTUDIO_MARKER");
        if (reactorOnlyServerManager()) {
            setActiveContainer(container);
            restoreBrowserHostTab();
        }
        updatePositions();
        if (!reactorOnlyServerManager()) {
            int savedIndex = remotelyClient.getSavedTabIndex();
            if (savedIndex < tabs().getTabs().size()) {
                tabs().setActiveTab(savedIndex);
            }
        }

        if (authenticated()) {
            fetchReStudioServers();
        }
    }

    private void removeReStudioTab() {
        clearRestudioModels();
        if (tabsManager == null) {
            return;
        }
        for (int i = 0; i < tabs().getTabs().size(); i++) {
            TabsManager.Tab tab = tabs().getTabs().get(i);
            if (!"RESTUDIO_MARKER".equals(tab.getData())) {
                continue;
            }
            boolean wasActive = tabs().getActiveTab() == tab;
            tabs().removeTab(i);
            if (wasActive) {
                loadServersForCurrentTab();
            }
            return;
        }
    }

    private boolean hasReStudioTab() {
        for (TabsManager.Tab tab : tabs().getTabs()) {
            if ("RESTUDIO_MARKER".equals(tab.getData())) {
                return true;
            }
        }
        return false;
    }

    private void loadServersForCurrentTab() {
        ensureCurrentRestudioInventory();
        loadServersForTab(tabs().getActiveTab());
    }

    private void ensureCurrentRestudioInventory() {
        if (reactorOnlyServerManager() && !authenticated()) {
            return;
        }
        if (!authenticated()) {
            clearRestudioModels();
            return;
        }
        if (!hasCurrentRestudioAccount() && !restudioFetchInFlight) {
            fetchReStudioServers();
        }
    }

    private void applyDesktopContentBounds() {
        if (tabsManager == null) {
            return;
        }
        DesktopBounds.Bounds contentBounds = desktopBounds().content();
        for (TabsManager.Tab tab : tabs().getTabs()) {
            Container container = tab.getContainer();
            if (container == null) {
                continue;
            }
            container.setPosition(contentBounds.x(), contentBounds.y());
            container.setSize(contentBounds.width(), contentBounds.height());
            container.updateWidgetPositions();
        }
    }

    private void loadServersForAllTabs() {
        if (tabsManager == null) {
            return;
        }
        applyDesktopContentBounds();
        for (TabsManager.Tab tab : tabs().getTabs()) {
            loadServersForTab(tab);
        }
    }

    private void queueServerRefresh() {
        ScreenManager.getInstance().execute(() -> {
            if (!reactiveRefreshEnabled || serverRefreshQueued) {
                return;
            }
            serverRefreshQueued = true;
            refreshNetworkViews(() -> {
                serverRefreshQueued = false;
                if (reactiveRefreshEnabled) {
                    loadServersForCurrentTab();
                }
            });
        });
    }

    private void queueStartSourceRefresh() {
        ScreenManager.getInstance().execute(() -> {
            if (!reactiveRefreshEnabled) return;
            startCatalogDirty = true;
            queueServerRefresh();
        });
    }

    private void registerInstanceChangeListener() {
        if (instanceChangeListenerRegistered) {
            return;
        }
        serverHost().addInstanceChangeListener(instanceChangeListener);
        serverHost().addNetworkChangeListener(networkChangeListener);
        serverHost().addRuntimeChangeListener(runtimeChangeListener);
        instanceChangeListenerRegistered = true;
    }

    private void registerNetworkChangeListener() {
        if (!instanceChangeListenerRegistered) {
            registerInstanceChangeListener();
        }
    }

    private void registerRuntimeChangeListener() {
        if (!instanceChangeListenerRegistered) {
            registerInstanceChangeListener();
        }
    }

    private boolean refreshVisibleServerWidget(ServerModels.ClientServerView instance) {
        TabsManager.Tab activeTab = tabs().getActiveTab();
        if (activeTab == null || activeTab.getContainer() == null || instance == null) {
            return false;
        }
        String key = getWidgetKey(instance);
        List<AnimatedWidget> icons = activeTab.getContainer().getWidgets().stream()
                .<AnimatedWidget>flatMap(widget -> widget instanceof DesktopGroupWidget<?> group ? group.getMembers().stream() : Stream.of(widget))
                .toList();
        for (AnimatedWidget widget : icons) {
            if (!(widget instanceof DesktopIconWidget<?> rawWidget) || !(rawWidget.getItem() instanceof ServerModels.ClientServerView item)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            DesktopIconWidget<ServerModels.ClientServerView> desktopIcon = (DesktopIconWidget<ServerModels.ClientServerView>) rawWidget;
            if (!key.equals(getWidgetKey(item))) {
                continue;
            }
            contextMenuRequestGeneration++;
            desktopIcon.setItem(instance);
            desktopIcon.accentType = getDesktopIconAccent(instance, false);
            refreshServerIcon(desktopIcon);
            return true;
        }
        return false;
    }

    private boolean isPersistentLocalInstance(ServerModels.ClientServerView instance) {
        return instance != null && instance.environment != null && "LOCAL".equalsIgnoreCase(instance.environment.get("backend"));
    }

    private void pollPersistentLocalServerStates(List<ServerModels.ClientServerView> visibleInstances, boolean force) {
        if (visibleInstances == null || visibleInstances.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (persistentLocalProcessPollInFlight || !force && now - lastPersistentLocalProcessPollMs < PERSISTENT_LOCAL_PROCESS_POLL_MS) return;
        List<ServerModels.ClientServerView> targets = visibleInstances.stream().filter(this::isPersistentLocalInstance).toList();
        if (targets.isEmpty()) return;
        lastPersistentLocalProcessPollMs = now;
        persistentLocalProcessPollInFlight = true;
        long generation = callbackGeneration;
        int[] remaining = {targets.size()};
        for (ServerModels.ClientServerView target : targets) {
            serverHost().localStatus(target).whenComplete((status, failure) -> ScreenManager.getInstance().execute(() -> {
                if (isCurrentCallback(generation) && failure == null && status != null) {
                    serverHost().applyLocalStatus(target, status, null);
                    refreshVisibleServerWidget(target);
                }
                if (--remaining[0] == 0) persistentLocalProcessPollInFlight = false;
            }));
        }
    }

    private void addHostTab(ServerScreenHost.HostView host) {
        DesktopBounds.Bounds contentBounds = desktopBounds().content();
        Container container = createContainer("desktop_remote_" + host.id(), contentBounds.x(), contentBounds.y(), contentBounds.width(), contentBounds.height());
        DesktopLayout layout = new DesktopLayout();
        layout.setOnReorder(() -> saveServerOrder(container, host));
        configureNetworkDrop(layout);
        container.layout(layout).backgroundDrawing(false).enableSelecting(true).disableScissorRegion(true);
        tabs().addTab(host.name(), container).setData(host);
        tabs().setActiveTab(tabs().getTabs().size() - 1);
        loadServersForTab(tabs().getActiveTab());
    }

    private void loadServersForTab(TabsManager.Tab tab) {
        if (!isActiveScreen()) {
            return;
        }
        contextMenuRequestGeneration++;
        if (tab == null) {
            updateNoServersOverlayVisibility(false);
            return;
        }

        Container targetContainer = tab.getContainer();
        if (targetContainer == null) {
            return;
        }

        List<ServerModels.ClientServerView> instances;
        Object tabData = tab.getData();

        if ("RESTUDIO_MARKER".equals(tabData) && !reactorOnlyServerManager() && !hasCurrentRestudioAccount()) {
            if (!authenticated()) {
                clearRestudioModels();
                return;
            }
            if (!restudioFetchInFlight) {
                fetchReStudioServers();
            }
            if (!restudioInstances.isEmpty()) {
                clearRestudioModels();
            }
        }

        List<ServerModels.ClientServerView> fetched = pendingServerViews.remove(tab);
        if ("RESTUDIO_MARKER".equals(tabData)) {
            instances = new ArrayList<>(restudioInstances);
        } else if (fetched != null) {
            instances = new ArrayList<>(fetched);
        } else if (tabData instanceof ServerScreenHost.HostView host) {
            long generation = callbackGeneration;
            serverHost().hostServers(host).whenComplete((servers, failure) -> ScreenManager.getInstance().execute(() -> {
                if (isCurrentCallback(generation) && isCurrentTab(tab) && failure == null) renderServerViews(tab, servers);
            }));
            return;
        } else {
            long generation = callbackGeneration;
            serverHost().localServers().whenComplete((servers, failure) -> ScreenManager.getInstance().execute(() -> {
                if (isCurrentCallback(generation) && isCurrentTab(tab) && failure == null) renderServerViews(tab, servers);
            }));
            return;
        }

        if ("RESTUDIO_MARKER".equals(tabData)) {
            RemotelyConfigStore config = remotelyClient.getComposition().configManager();
            List<String> hiddenRestudio = config == null ? List.of() : config.getHiddenRestudioServers();
            instances.removeIf(inst -> isHiddenRestudioServer(hiddenRestudio, inst));
        }

        if (tab == tabs().getActiveTab()) {
            currentServerViews = List.copyOf(instances);
            startCatalogDirty = true;
        }

        String context = "local";
        if (tabData instanceof ServerScreenHost.HostView host) {
            context = "remote." + host.name();
        } else if ("RESTUDIO_MARKER".equals(tabData)) {
            context = "remote.restudio";
        }

        RemotelyConfigStore config = remotelyClient.getComposition().configManager();
        List<String> order = config == null ? List.of() : config.getInstanceOrder(context);

        Map<String, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            orderMap.put(order.get(i), i);
        }
        Map<String, Integer> naturalOrder = new HashMap<>();
        for (int index = 0; index < instances.size(); index++) {
            naturalOrder.put(getWidgetKey(instances.get(index)), index);
        }
        Map<String, Integer> visualOrder = new HashMap<>();
        for (ServerModels.ClientServerView instance : instances) {
            visualOrder.put(serverId(instance), orderMap.getOrDefault(getWidgetKey(instance), naturalOrder.getOrDefault(getWidgetKey(instance), Integer.MAX_VALUE)));
        }
        Map<String, ServerScreenHost.NetworkView> memberships = new HashMap<>();
        Map<String, Integer> networkAnchors = new HashMap<>();
        List<ServerScreenHost.NetworkView> networks = networkViews;
        for (ServerScreenHost.NetworkView network : networks) {
            for (String member : network.members()) {
                memberships.put(member, network);
                networkAnchors.merge(network.id(), visualOrder.getOrDefault(member, Integer.MAX_VALUE), Math::min);
            }
        }
        instances.sort(Comparator.comparingInt((ServerModels.ClientServerView instance) -> {
                ServerScreenHost.NetworkView network = memberships.get(serverId(instance));
                return network == null ? visualOrder.getOrDefault(serverId(instance), Integer.MAX_VALUE) : networkAnchors.getOrDefault(network.id(), Integer.MAX_VALUE);
            })
            .thenComparingInt(instance -> memberships.containsKey(serverId(instance)) ? 0 : 1)
            .thenComparing(instance -> {
                ServerScreenHost.NetworkView network = memberships.get(serverId(instance));
                return network == null ? getWidgetKey(instance) : network.id();
            }, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(this::networkRoleOrder)
            .thenComparingInt(instance -> visualOrder.getOrDefault(serverId(instance), Integer.MAX_VALUE)));
        if (targetContainer.getLayout() instanceof DesktopLayout desktopLayout) {
            desktopLayout.setTopMargin(8);
        }

        Map<String, DesktopIconWidget<ServerModels.ClientServerView>> existingWidgets = new HashMap<>();
        Map<String, DesktopGroupWidget<ServerModels.ClientServerView>> existingGroups = new HashMap<>();
        DesktopIconWidget<ServerModels.ClientServerView> createButton = null;

        for (AnimatedWidget w : targetContainer.getWidgets()) {
            if (w instanceof DesktopGroupWidget<?> rawGroup) {
                @SuppressWarnings("unchecked")
                DesktopGroupWidget<ServerModels.ClientServerView> group = (DesktopGroupWidget<ServerModels.ClientServerView>) rawGroup;
                existingGroups.put(group.getGroup().id(), group);
                for (DesktopIconWidget<ServerModels.ClientServerView> member : group.getMembers()) {
                    existingWidgets.put(getWidgetKey(member.getItem()), member);
                }
                continue;
            }
            if (w instanceof DesktopIconWidget<?> rawWidget) {
                if (rawWidget.getItem() == null) {
                    @SuppressWarnings("unchecked")
                    DesktopIconWidget<ServerModels.ClientServerView> diw = (DesktopIconWidget<ServerModels.ClientServerView>) rawWidget;
                    createButton = diw;
                } else if (rawWidget.getItem() instanceof ServerModels.ClientServerView instance) {
                    @SuppressWarnings("unchecked")
                    DesktopIconWidget<ServerModels.ClientServerView> diw = (DesktopIconWidget<ServerModels.ClientServerView>) rawWidget;
                    existingWidgets.put(getWidgetKey(instance), diw);
                }
            }
        }

        List<AnimatedWidget> toKeep = new ArrayList<>();
        for (ServerModels.ClientServerView server : instances) {
            DesktopIconWidget<ServerModels.ClientServerView> existing = existingWidgets.get(getWidgetKey(server));
            if (existing != null) {
                existing.setItem(server);
                existing.setMessage(serverDisplayLabel(server));
                existing.setHint(serverDisplayHint(server));
                existing.accentType = getDesktopIconAccent(server, false);
                refreshServerIcon(existing);
                toKeep.add(existing);
                existingWidgets.remove(getWidgetKey(server));
            } else {
                DesktopIconWidget<ServerModels.ClientServerView> widget = createServerWidget(server, false);
                toKeep.add(widget);
            }
        }
        toKeep = collapseServerGroups(toKeep, instances, context, existingGroups);
        toKeep = DesktopLayout.retainOrder(targetContainer.getWidgets(), toKeep, this::desktopWidgetKey);

        boolean isPteroTab = tabData instanceof ServerScreenHost.HostView host && host.panel();
        if (!isPteroTab && createButton == null) {
            createButton = createServerWidget(null, true);
        }
        if (!isPteroTab) {
            toKeep.add(createButton);
        }

        String gridContext = "remotely:" + browserHostKey(tabData);
        if ("RESTUDIO_MARKER".equals(tabData)) gridContext += ":" + accountKey(serverHost().accountIdentity());
        toKeep = desktopWidgets(targetContainer, config, gridContext, this::desktopWidgetKey, toKeep);
        contextMenuRequestGeneration++;
        targetContainer.replaceWidgets(toKeep);
        if (tab == tabs().getActiveTab()) {
            updateNoServersOverlayVisibility(instances.isEmpty());
            if (!(tabData instanceof ServerScreenHost.HostView) && !"RESTUDIO_MARKER".equals(tabData)) {
                pollPersistentLocalServerStates(instances, true);
            }
        }
    }

    private void renderServerViews(TabsManager.Tab tab, List<ServerModels.ClientServerView> servers) {
        if (!isCurrentTab(tab)) return;
        pendingServerViews.put(tab, servers == null ? List.of() : List.copyOf(servers));
        loadServersForTab(tab);
    }

    private List<AnimatedWidget> collapseServerGroups(List<AnimatedWidget> widgets, List<ServerModels.ClientServerView> instances, String context,
                                                     Map<String, DesktopGroupWidget<ServerModels.ClientServerView>> existingGroups) {
        Map<String, DesktopIconWidget<ServerModels.ClientServerView>> icons = new LinkedHashMap<>();
        for (AnimatedWidget widget : widgets) {
            if (widget instanceof DesktopIconWidget<?> rawIcon && rawIcon.getItem() instanceof ServerModels.ClientServerView instance) {
                @SuppressWarnings("unchecked")
                DesktopIconWidget<ServerModels.ClientServerView> icon = (DesktopIconWidget<ServerModels.ClientServerView>) rawIcon;
                icons.put(serverId(instance), icon);
            }
        }
        Map<String, DesktopGroup> groupsByMember = new HashMap<>();
        Map<String, DesktopGroupWidget<ServerModels.ClientServerView>> groupWidgets = new HashMap<>();
        for (ServerScreenHost.NetworkView network : networkViews) {
            List<String> members = network.members().stream().filter(icons::containsKey).toList();
            if (members.size() < 2) continue;
            DesktopGroup group = new DesktopGroup("network:" + network.id(), network.name(), members);
            List<DesktopIconWidget<ServerModels.ClientServerView>> memberIcons = members.stream().map(icons::get).filter(Objects::nonNull).toList();
            DesktopGroupWidget<ServerModels.ClientServerView> groupWidget = DesktopGroupWidget.reconcile(existingGroups.get(group.id()), group, memberIcons, (widget, button) -> showServerGroupMenu(widget, network));
            groupWidget.setRenameAction((widget, name) -> saveNetworkName(network, name));
            members.forEach(member -> groupsByMember.put(member, group));
            groupWidgets.put(group.id(), groupWidget);
        }
        RemotelyConfigStore config = remotelyClient.getComposition().configManager();
        List<ServerScreenHost.GroupView> configured = config == null ? List.of() : config.getInstanceGroups(context).stream()
                .map(group -> new ServerScreenHost.GroupView(group.id(), group.name(), group.members()))
                .toList();
        List<DesktopGroup> validManualGroups = new ArrayList<>();
        for (ServerScreenHost.GroupView group : configured) {
            List<String> members = group.members().stream().filter(icons::containsKey).filter(member -> !groupsByMember.containsKey(member)).distinct().toList();
            if (members.size() < 2) continue;
            DesktopGroup validGroup = new DesktopGroup(group.id(), group.name(), members);
            List<DesktopIconWidget<ServerModels.ClientServerView>> memberIcons = members.stream().map(icons::get).toList();
            DesktopGroupWidget<ServerModels.ClientServerView> groupWidget = DesktopGroupWidget.reconcile(existingGroups.get(validGroup.id()), validGroup, memberIcons, (widget, button) -> showServerGroupMenu(widget, null));
            groupWidget.setRenameAction((widget, name) -> renameServerGroup(widget.getGroup().id(), name));
            validManualGroups.add(validGroup);
            members.forEach(member -> groupsByMember.put(member, validGroup));
            groupWidgets.put(validGroup.id(), groupWidget);
        }
        instanceGroups = validManualGroups;
        List<AnimatedWidget> collapsed = new ArrayList<>();
        Set<String> renderedGroups = new HashSet<>();
        for (AnimatedWidget widget : widgets) {
            if (!(widget instanceof DesktopIconWidget<?> icon) || !(icon.getItem() instanceof ServerModels.ClientServerView instance)) {
                collapsed.add(widget);
                continue;
            }
            DesktopGroup group = groupsByMember.get(serverId(instance));
            if (group == null) {
                icon.restoreStandalone();
                collapsed.add(widget);
            } else if (renderedGroups.add(group.id())) {
                collapsed.add(groupWidgets.get(group.id()));
            }
        }
        return collapsed;
    }

    private void showServerGroupMenu(DesktopGroupWidget<ServerModels.ClientServerView> groupWidget, ServerScreenHost.NetworkView network) {
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(this);
        if (network != null) {
            builder.addIconItem("Open Network", "network.png", () -> openNetworkOverview(network), groupWidget.getMembers().size() + " Servers");
        }
        for (DesktopIconWidget<ServerModels.ClientServerView> member : groupWidget.getMembers()) {
            ServerModels.ClientServerView instance = member.getItem();
            builder.addIconItem(serverDisplayLabel(instance), member.getIconId(), () -> onDesktopIconClick(member, 0), serverDisplayHint(instance));
        }
        if (network == null) {
            builder.addIconItem("Ungroup", "close.png", () -> {
                instanceGroups.removeIf(group -> group.id().equals(groupWidget.getGroup().id()));
                saveConfiguredGroups(activeServerGroupContext(), instanceGroups);
                loadServersForCurrentTab();
            }, "Keep Every Server");
        }
        showDesktopContextMenu(groupWidget.getX(), groupWidget.getY() + groupWidget.getHeight(), builder);
    }

    private String activeServerGroupContext() {
        TabsManager.Tab tab = tabs().getActiveTab();
        if (tab != null && tab.getData() instanceof ServerScreenHost.HostView host) {
            return "remote." + host.name();
        }
        return tab != null && "RESTUDIO_MARKER".equals(tab.getData()) ? "remote.restudio" : "local";
    }

    private void renameServerGroup(String groupId, String name) {
        instanceGroups.replaceAll(group -> group.id().equals(groupId) ? new DesktopGroup(group.id(), name, group.members()) : group);
        saveConfiguredGroups(activeServerGroupContext(), instanceGroups);
    }

    private void createServerGroup(List<ServerModels.ClientServerView> instances) {
        LinkedHashSet<String> members = instances.stream().map(ServerManagerScreen::serverId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (members.size() < 2) {
            return;
        }
        List<DesktopGroup> groups = new ArrayList<>(instanceGroups);
        groups.replaceAll(group -> new DesktopGroup(group.id(), group.name(), group.members().stream().filter(member -> !members.contains(member)).toList()));
        groups.removeIf(group -> group.members().size() < 2);
        long number = groups.stream().filter(group -> group.name().startsWith("Group")).count() + 1;
        groups.add(new DesktopGroup(UUID.randomUUID().toString(), "Group " + number, new ArrayList<>(members)));
        instanceGroups = groups;
        saveConfiguredGroups(activeServerGroupContext(), groups);
        loadServersForCurrentTab();
    }

    private String desktopWidgetKey(AnimatedWidget widget) {
        if (widget instanceof DesktopGroupWidget<?> group) return "group:" + group.getGroup().id();
        if (widget instanceof DesktopIconWidget<?> icon && icon.getItem() instanceof ServerModels.ClientServerView server) return "server:" + getWidgetKey(server);
        return "create";
    }

    private String getWidgetKey(ServerModels.ClientServerView inst) {
        if (inst == null) return "create_button";
        return serverHost().serverOrderKey(inst);
    }

    private void saveServerOrder(Container container, ServerScreenHost.HostView host) {
        List<String> newOrderIds = new ArrayList<>();
        for (AnimatedWidget w : container.getWidgets()) {
            if (w instanceof DesktopIconWidget<?> diw && diw.getItem() instanceof ServerModels.ClientServerView instance) {
                newOrderIds.add(getWidgetKey(instance));
            } else if (w instanceof DesktopGroupWidget<?> groupWidget) {
                groupServers(groupWidget).map(this::getWidgetKey).forEach(newOrderIds::add);
            }
        }

        String context = "local";
        if (host != null) {
            context = "remote." + host.name();
        } else if (tabs().getActiveTab() != null && "RESTUDIO_MARKER".equals(tabs().getActiveTab().getData())) {
            context = "remote.restudio";
        }

        serverHost().saveServerOrder(context, newOrderIds);
    }

    private DesktopIconWidget<ServerModels.ClientServerView> createServerWidget(ServerModels.ClientServerView info, boolean isCreate) {
        String label = isCreate || info == null ? "Create" : serverDisplayLabel(info);
        Object iconValue = isCreate || info == null ? null : iconTarget(info);
        Identifier iconId = iconValue == null ? null : iconManager.getQuickIconId(iconValue);
        DesktopIconWidget<ServerModels.ClientServerView> widget = new DesktopIconWidget.Builder<>(info, (Identifier) null, label)
            .onClick(this::onDesktopIconClick)
            .build();
        Identifier fallback = isCreate || info == null ? Identifier.icon("server.png") : serverIcon;
        widget.setGeneratedIcon(iconId == null ? fallback : iconId);
        widget.accentType = getDesktopIconAccent(info, isCreate);
        if (isCreate || info == null) {
            return widget;
        }
        widget.setDetails(() -> widget.getItem().version, () -> widget.getItem().loader);
        Identifier filesIcon = Identifier.icon("explorer.png");
        Identifier settingsIcon = Identifier.icon("edit.png");
        LifecycleButtonWidget lifecycle = new LifecycleButtonWidget(this,
                () -> setServerPower(widget.getItem(), !isServerActive(widget.getItem())), "Server")
                .lifecycleAction(LifecycleButtonWidget.Operation.START, () -> setServerPower(widget.getItem(), true))
                .lifecycleAction(LifecycleButtonWidget.Operation.STOP, () -> setServerPower(widget.getItem(), false));
        widget.addTileAction(lifecycle.tileStyle(), () -> {
            InstanceState state = switch (serverHost().state(widget.getItem())) {
                case STARTING -> InstanceState.STARTING;
                case RUNNING -> InstanceState.RUNNING;
                case STOPPING -> InstanceState.STOPPING;
                case CRASHED -> InstanceState.CRASHED;
                case INSTALLING -> InstanceState.INSTALLING;
                default -> InstanceState.STOPPED;
            };
            lifecycle.update(state);
            lifecycle.setActive(state != InstanceState.STOPPING && state != InstanceState.INSTALLING);
        });
        widget.addTileAction(() -> "File Explorer", () -> filesIcon,
                () -> serverHost().serverActionAvailability(widget.getItem(), ServerScreenHost.Action.FILE_EXPLORER).available(),
                () -> serverHost().openFileExplorer(this, widget.getItem()));
        widget.addTileAction(() -> "Server Settings", () -> settingsIcon,
                () -> serverHost().serverActionAvailability(widget.getItem(), ServerScreenHost.Action.SERVER_CONFIGURATION).available(),
                () -> serverHost().openServerConfiguration(this, widget.getItem()));
        refreshServerIcon(widget);
        return widget;
    }

    private void refreshServerIcon(DesktopIconWidget<ServerModels.ClientServerView> widget) {
        ServerModels.ClientServerView server = widget.getItem();
        Object target = iconTarget(server);
        String key = getWidgetKey(server);
        long generation = callbackGeneration;
        BooleanSupplier current = () -> generation == callbackGeneration && widget.getIconId() != null
                && widget.getItem() != null && key.equals(getWidgetKey(widget.getItem()));
        Consumer<Identifier> loaded = icon -> {
            if (!current.getAsBoolean()) return;
            Identifier resolved = icon == null ? serverIcon : icon;
            if (!Objects.equals(widget.getIconId(), resolved)) {
                widget.setGeneratedIcon(resolved);
                startCatalogDirty = true;
            }
        };
        iconManager.loadIconIdAsync(target, loaded);
        iconManager.loadRemoteIconAsync(target, () -> {
            if (current.getAsBoolean()) iconManager.loadIconIdAsync(target, loaded);
        });
    }

    private Object iconTarget(ServerModels.ClientServerView server) {
        return serverHost().iconTarget(server);
    }

    private boolean isVelocityInstance(ServerModels.ClientServerView instance) {
        return instance != null && "VELOCITY".equalsIgnoreCase(instance.loader);
    }

    private String serverDisplayLabel(ServerModels.ClientServerView instance) {
        return serverName(instance);
    }

    private String serverDisplayHint(ServerModels.ClientServerView instance) {
        ServerScreenHost.NetworkView network = networkViews.stream().filter(value -> value.members().contains(serverId(instance))).findFirst().orElse(null);
        if (network == null) {
            return serverName(instance);
        }
        String id = serverId(instance);
        String role = network.proxyId().equals(id) ? "Proxy" : "Backend";
        NetworkRuntimeNodePresence presence = runtimePresence(network, id);
        String live = presence == null || presence.status() == NetworkRuntimeNodeStatus.OFFLINE
                || presence.status() == NetworkRuntimeNodeStatus.REVOKED || "Proxy".equals(role) ? ""
                : " • " + presence.players() + (presence.capacity() > 0 ? "/" + presence.capacity() : "") + " Players";
        return serverName(instance) + " • " + network.name() + " • " + role + live;
    }

    private NetworkRuntimeNodePresence runtimePresence(ServerScreenHost.NetworkView network, String instanceId) {
        NetworkManager<?, ?> manager = remotelyClient.getNetworkManager();
        if (network == null || instanceId == null || instanceId.isBlank() || manager == null) {
            return null;
        }
        try {
            NetworkDefinition definition = manager.getNetwork(network.id()).orElse(null);
            NetworkMember member = definition == null ? null : definition.members().stream()
                    .filter(candidate -> instanceId.equals(candidate.instanceId())).findFirst().orElse(null);
            if (member == null) {
                return null;
            }
            NetworkRuntimeSnapshot snapshot = manager.getRuntimeSnapshot(network.id());
            return snapshot != null && snapshot.connected() ? snapshot.node(member.nodeId()).orElse(null) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private int networkRoleOrder(ServerModels.ClientServerView instance) {
        ServerScreenHost.NetworkView network = networkViews.stream().filter(value -> value.members().contains(serverId(instance))).findFirst().orElse(null);
        if (network == null) {
            return 2;
        }
        return network.proxyId().equals(serverId(instance)) ? 0 : 1;
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) return "Unknown Error";
        Throwable current = throwable;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "Unknown Error" : message;
    }

    private void onHostTabSelected(TabsManager.Tab tab) {
        startCatalogDirty = true;
        startNavigationGeneration++;
        currentServerViews = List.of();
        contextMenuRequestGeneration++;
        int selectionToken = ++remoteHostSelectionToken;
        remotelyClient.saveTabIndex(tabs().getActiveTabIndex());
        if (browserRuntime()) {
            remotelyClient.saveBrowserHostKey(browserHostKey(tab.getData()));
        }
        applyDesktopContentBounds();
        setActiveContainer(tab.getContainer());
        updateManagerActionControls();

        Object data = tab.getData();
        if (data instanceof ServerScreenHost.HostView host) {
            handleRemoteHostTabSelected(tab, host, selectionToken);
        } else if ("RESTUDIO_MARKER".equals(data)) {
            fetchReStudioServers();
        }

        loadServersForCurrentTab();
    }

    private void handleRemoteHostTabSelected(TabsManager.Tab tab, ServerScreenHost.HostView host, int selectionToken) {
        if (!isActiveScreen() || selectionToken != remoteHostSelectionToken) {
            return;
        }
        if (tabs().getActiveTab() != tab) {
            return;
        }
        boolean connected = host.connected();
        ServerScreenHost.HostView target = connected ? host : host.withConnected(true);
        if (tab.getWidget() != null) {
            tab.getWidget().setAccent(connected ? ThemeManager.getDefaultAccent() : ThemeManager.getAccent("calm"));
        }
        Async<Void> connection = connected ? Async.completed(null) : serverHost().hostAction(host, "connect");
        connection.thenCompose(ignored -> {
                    if (!connected) tab.setData(target);
                    return serverHost().refreshRemoteInstance(target);
                })
                .whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
                    if (!isActiveScreen() || selectionToken != remoteHostSelectionToken || tabs().getActiveTab() != tab) {
                        return;
                    }
                    if (tab.getWidget() != null) {
                        tab.getWidget().setAccent(failure == null ? ThemeManager.getDefaultAccent() : ThemeManager.getAccent("danger"));
                    }
                    loadServersForTab(tab);
                }));
    }

    private void fetchReStudioServers() {
        if (!authenticated()) {
            restudioFetchGeneration++;
            restudioFetchInFlight = false;
            restudioFetchAccount = "";
            clearRestudioModels();
            return;
        }
        String requestAccount = accountKey(serverHost().accountIdentity());
        if (restudioFetchInFlight && requestAccount.equals(restudioFetchAccount)) {
            return;
        }
        long requestGeneration = ++restudioFetchGeneration;
        restudioFetchInFlight = true;
        restudioFetchAccount = requestAccount;
        if (!requestAccount.equals(restudioInstancesAccount)) {
            clearRestudioModels();
            refreshReStudioTabs();
        }
        TabsManager.Tab restudioTab = tabs().getTabs().stream()
                .filter(tab -> "RESTUDIO_MARKER".equals(tab.getData()))
                .findFirst()
                .orElse(null);
        if (restudioTab != null && restudioTab.getWidget() != null) {
            restudioTab.getWidget().setAccent(ThemeManager.getAccent("calm"));
        }
        long generation = callbackGeneration;
        serverHost().restudioServers().whenComplete((servers, e) -> ScreenManager.getInstance().execute(() -> {
            if (requestGeneration == restudioFetchGeneration) {
                restudioFetchInFlight = false;
                restudioFetchAccount = "";
            }
            boolean currentRequest = isCurrentCallback(generation) && requestGeneration == restudioFetchGeneration
                    && authenticated() && requestAccount.equals(accountKey(serverHost().accountIdentity()));
            currentRequest = currentRequest && restudioTab != null && tabs().getTabs().contains(restudioTab)
                    && "RESTUDIO_MARKER".equals(restudioTab.getData());
            if (!currentRequest) {
                return;
            }
            if (restudioTab != null && restudioTab.getWidget() != null) {
                restudioTab.getWidget().setAccent(ThemeManager.getDefaultAccent());
            }
            if (e != null) {
                new Notification("Error fetching ReStudio servers", e.getMessage(), Notification.Type.ERROR);
                if (restudioTab != null && restudioTab.getWidget() != null) {
                    restudioTab.getWidget().setAccent(ThemeManager.getAccent("danger"));
                }
                return;
            }
            restudioInstances.clear();
            restudioInstances.addAll(servers == null ? List.of() : servers);
            restudioServerViews.clear();
            if (servers != null) servers.forEach(server -> restudioServerViews.put(serverName(server), server));
            restudioInstancesAccount = requestAccount;
            remotelyClient.cacheReStudioServerViews(restudioServerViews);

            for (TabsManager.Tab tab : tabs().getTabs()) {
                if ("RESTUDIO_MARKER".equals(tab.getData())) {
                    loadServersForTab(tab);
                    break;
                }
            }
        }));
    }

    private void onHostTabContextMenu(TabsManager.Tab tab) {
        if (tab != null && tab.getData() instanceof ServerScreenHost.HostView) {
            openRemoteHostPopup(tab);
        }
    }

    public void openRemoteHostEditor() {
        closeRemoteHostPopup();
        resetRemoteHostFields();
        showRemoteHostTypes();
    }

    private Accent getDesktopIconAccent(ServerModels.ClientServerView info, boolean isCreate) {
        return switch (serverHost().serverIconAccent(info, isCreate)) {
            case NICE -> ThemeManager.getAccent("nice");
            case CALM -> ThemeManager.getAccent("calm");
            case DANGER -> ThemeManager.getAccent("danger");
            default -> ThemeManager.getDefaultAccent();
        };
    }

    private void onDesktopIconClick(DesktopIconWidget<ServerModels.ClientServerView> widget, int button) {
        if (button == 0) {
            if (widget.getItem() == null) {
                boolean isPteroTab = tabs().getActiveTab().getData() instanceof ServerScreenHost.HostView host && host.panel();
                if (isPteroTab) {
                    new Notification("Panel Managed", "Create Servers In Your Panel", Notification.Type.WARN);
                    return;
                }
                if ("RESTUDIO_MARKER".equals(tabs().getActiveTab().getData()) && !authenticated()) {
                    new Notification.Builder()
                        .message("Not Authenticated")
                        .description("Click To Log-In")
                        .type(Notification.Type.ERROR)
                        .action(this::openReStudioLogin)
                        .build();
                    return;
                }
                playSound(Sound.CREATE);
                showCreateChoice();
            } else {
                openServerScreen(widget.getItem());
            }
        } else if (button == 1) {
            if (widget.getItem() != null) {
                List<ServerModels.ClientServerView> selectedInstances = selectedServerInstances(widget);
                if (selectedInstances.size() > 1) {
                    showSelectedServersMenu(widget, selectedInstances);
                    return;
                }
                activeContainer.clearSelection();
                activeContainer.addSelectedWidget(widget);

                showServerContextMenu(widget, widget.getItem());
            }
        }
    }

    private void showServerContextMenu(DesktopIconWidget<ServerModels.ClientServerView> widget, ServerModels.ClientServerView server) {
        ServerScreenHost host = serverHost();
        TabsManager.Tab requestedTab = tabs().getActiveTab();
        if (!reactiveRefreshEnabled || requestedTab == null || ScreenManager.getInstance().getCurrentScreen() != this) {
            return;
        }
        ++contextMenuRequestGeneration;
        if (!isCurrentContextWidget(requestedTab, widget, server)) return;
        boolean running = isServerActive(server);
        ServerScreenHost.ServerIdentity identity = host.identity(server);
        boolean isPanel = host.isPanel(server);
        boolean isReStudio = "RESTUDIO".equalsIgnoreCase(identity.backendType()) || host.serverOrderKey(server).startsWith("RESTUDIO_");
        ServerScreenHost.HostView remoteHost = isReStudio ? null : host.resolveRemoteHost(server, activeHostView());
        ServerScreenHost.NetworkView network = networkForServer(server);
        boolean canDuplicate = canServerManagerAction(server, ServerScreenHost.Action.DUPLICATE_SERVER, "server.duplicate");
        boolean canDelete = canServerManagerAction(server, ServerScreenHost.Action.DELETE_SERVER, "server.delete");
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(this)
                .addHeaderButton(running ? "stop.png" : "start.png", () -> setServerPower(server, !running), running ? "Stop Server" : "Start Server",
                        running ? ThemeManager.getAccent("danger") : ThemeManager.getAccent("nice"));
        if (!identity.local() && !isPanel) {
            builder.addHeaderButton("merge.png", () -> host.openDevelopment(this, server), "Development");
        }
        if (host.serverActionAvailability(server, ServerScreenHost.Action.SERVER_CONFIGURATION).available()) {
            builder.addHeaderButton("edit.png", () -> host.openServerConfiguration(this, server), "Edit Server's Settings");
        }
        builder.addHeaderButton("explorer.png", () -> host.openFileExplorer(this, server), "Open Server's Files");
        if (remoteHost == null) {
            builder.addHeaderButton("map.png", () -> host.openWorld(this, server), "View World Map");
        }
        builder.addHeaderButton("ReSync.png", () -> host.openReSyncStudio(this, server), "ReSync");
        if (network != null) {
            builder.addIconItem(network.name(), "network.png", () -> openNetworkOverview(network), network.status());
            addManagedNetworkActions(builder, network);
        } else if (remotelyClient.getNetworkManager() != null && isVelocityProxy(server)) {
            builder.addIconItem("Import Network", "merge.png", () -> scanNetworkForAdoption(server), "Scan Velocity Without Changes");
        } else if (remotelyClient.getNetworkManager() != null && isLegacyProxy(server)) {
            builder.addIconItem("Migrate To Velocity", "merge.png", () -> scanLegacyMigration(server), "Review Existing Routes Before Migration");
        }
        if (canDuplicate) {
            builder.addHeaderButton("copy.png", () -> {
                if (ensureServerManagerAction(server, ServerScreenHost.Action.DUPLICATE_SERVER, "server.duplicate")) {
                    host.duplicateServer(this, server);
                }
            }, "Duplicate Server");
        }
        if (canDelete) {
            builder.addHeaderButton("delete.png", () -> {
                if (ensureServerManagerAction(server, ServerScreenHost.Action.DELETE_SERVER, "server.delete")) {
                    showDeleteServerPopup(server);
                }
            }, "Show Deletion Options", ThemeManager.getAccent("danger"));
        }
        builder.addIconItem("Customize Icon", "shades.png", () -> host.openIconCustomizer(this, server, remoteHost, this::loadServersForCurrentTab), "");
        showDesktopContextMenu(widget.getX() + widget.getWidth() + 4, widget.getY() + 24, builder);
    }

    private ServerScreenHost.NetworkView networkForServer(ServerModels.ClientServerView server) {
        String id = serverId(server);
        return networkViews.stream().filter(network -> network.members().contains(id)).findFirst().orElse(null);
    }

    private boolean isCurrentContextWidget(TabsManager.Tab tab, DesktopIconWidget<ServerModels.ClientServerView> widget,
                                           ServerModels.ClientServerView server) {
        return tab != null && tab == tabs().getActiveTab() && activeContainer == tab.getContainer()
                && tab.getContainer() != null && tab.getContainer().getWidgets().contains(widget)
                && tab.getContainer().getSelectedWidgets().contains(widget) && widget.getItem() == server;
    }

    private ServerModels.ClientServerView knownServerView(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (ServerModels.ClientServerView server : currentServerViews) {
            if (id.equals(serverId(server))) {
                return server;
            }
        }
        for (List<ServerModels.ClientServerView> servers : pendingServerViews.values()) {
            for (ServerModels.ClientServerView server : servers) {
                if (id.equals(serverId(server))) {
                    return server;
                }
            }
        }
        for (ServerModels.ClientServerView server : restudioServerViews.values()) {
            if (id.equals(serverId(server))) {
                return server;
            }
        }
        return null;
    }

    private boolean isRestudioServer(ServerModels.ClientServerView server) {
        ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
        return "RESTUDIO".equalsIgnoreCase(identity.backendType()) || serverHost().serverOrderKey(server).startsWith("RESTUDIO_");
    }

    private boolean canUseAsNetworkBackend(ServerModels.ClientServerView server) {
        if (server == null || isVelocityInstance(server) || isLegacyProxy(server)) {
            return false;
        }
        ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
        return !identity.installing() && serverHost().networkMemberAvailability(server).available()
                && networkForServer(server) == null;
    }

    private boolean canUseAsNetworkProxy(ServerModels.ClientServerView server) {
        if (!isVelocityProxy(server)) {
            return false;
        }
        ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
        return !identity.installing() && serverHost().networkMemberAvailability(server).available()
                && networkForServer(server) == null;
    }

    private ServerModels.ClientServerView networkProxy(ServerScreenHost.NetworkView network) {
        if (network == null || network.proxyId().isBlank()) {
            return null;
        }
        ServerModels.ClientServerView proxy = knownServerView(network.proxyId());
        return isVelocityProxy(proxy) ? proxy : null;
    }

    private boolean canAttachNetworkBackend(ServerScreenHost.NetworkView network, ServerModels.ClientServerView server) {
        if (network == null || server == null || network.members().isEmpty() || networkOperationInFlight
                || pendingNetworkMembershipInstances.contains(serverId(server)) || network.members().contains(serverId(server))) {
            return false;
        }
        ServerModels.ClientServerView proxy = networkProxy(network);
        if (proxy == null || !isVelocityProxy(proxy)) {
            return false;
        }
        ServerScreenHost.ServerIdentity proxyIdentity = serverHost().identity(proxy);
        return !proxyIdentity.installing() && !serverHost().isPanel(proxy) && !isRestudioServer(proxy)
                && canUseAsNetworkBackend(server);
    }

    private boolean canStandaloneMutation(ServerModels.ClientServerView server) {
        ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
        return identity.local() && !identity.installing() && !serverHost().isPanel(server)
                && !"RESTUDIO".equalsIgnoreCase(identity.backendType()) && networkForServer(server) == null;
    }

    private boolean canServerManagerAction(ServerModels.ClientServerView server, ServerScreenHost.Action action, String capability) {
        if (!reactorOnlyServerManager()) {
            return serverHost().serverActionAvailability(server, action).available();
        }
        if (server == null || !serverHost().authenticated() || !serverHost().supports(action)) {
            return false;
        }
        ServerUiCapabilityProvider.Availability availability = serverHost().capabilities(server).availability(server, capability);
        return availability.available() || "Server Capabilities Are Loading".equals(availability.reason());
    }

    private boolean ensureServerManagerAction(ServerModels.ClientServerView server, ServerScreenHost.Action action, String capability) {
        if (canServerManagerAction(server, action, capability)) {
            return true;
        }
        if (!reactorOnlyServerManager()) {
            new Notification("Unavailable", serverHost().serverActionAvailability(server, action).reason(), Notification.Type.WARN);
            return false;
        }
        ServerUiCapabilityProvider.Availability availability = server == null
                ? ServerUiCapabilityProvider.Availability.missing("No Server Is Selected")
                : serverHost().capabilities(server).availability(server, capability);
        String reason = availability.reason();
        if (reason.isBlank() || "Server Capabilities Are Loading".equals(reason)) {
            reason = action == ServerScreenHost.Action.DUPLICATE_SERVER
                    ? "Server Duplication Is Unavailable" : "Server Deletion Is Unavailable";
        }
        new Notification("Unavailable", reason, Notification.Type.WARN);
        return false;
    }

    private void addManagedNetworkActions(ContextMenuWidget.Builder builder, ServerScreenHost.NetworkView network) {
        ServerScreenHost.NetworkJobView latestJob = serverHost().latestNetworkJob(network);
        if (latestJob != null && latestJob.canResume()) {
            builder.addHeaderButton("reload.png", () -> resumeNetworkJob(network), "Resume Network", ThemeManager.getAccent("calm"));
        }
        if (latestJob != null && latestJob.canRollback()
                && latestJob.interruptedOrFailed()) {
            builder.addHeaderButton("history.png", () -> rollbackNetworkJob(network), "Rollback Network", ThemeManager.getAccent("danger"));
        }
    }

    private List<ServerModels.ClientServerView> selectedServerInstances(DesktopIconWidget<ServerModels.ClientServerView> clickedWidget) {
        if (!activeContainer.getSelectedWidgets().contains(clickedWidget)) {
            return List.of(clickedWidget.getItem());
        }
        return serverWidgets(activeContainer.getSelectedWidgets().stream()).toList();
    }

    private List<ServerModels.ClientServerView> currentSelectedServerInstances() {
        if (activeContainer == null) {
            return List.of();
        }
        return serverWidgets(activeContainer.getSelectedWidgets().stream()).toList();
    }

    private void openNetworkCreationFromSelection(List<ServerModels.ClientServerView> selected) {
        Object target = tabs().getActiveTab() == null ? null : tabs().getActiveTab().getData();
        ServerScreenHost.ActionAvailability availability = serverHost().managerAction(ServerScreenHost.Action.NETWORK_CREATE, target);
        if (!availability.available()) {
            new Notification("Network Creation Unavailable", availability.reason(), Notification.Type.WARN);
            return;
        }
        List<ServerModels.ClientServerView> proxies = selected.stream().filter(this::isVelocityInstance).toList();
        if (proxies.size() > 1 || selected.stream().anyMatch(server -> isVelocityInstance(server) ? !canUseAsNetworkProxy(server) : !canUseAsNetworkBackend(server))) {
            new Notification("Invalid Selection", "Choose Standalone Backends And At Most One Velocity Proxy", Notification.Type.ERROR);
            return;
        }
        serverHost().application().setScreen(new NetworkCreationScreen(this, serverHost(), selected, NetworkCreationContext.from(serverHost(), target)));
    }

    private void openNetworkOverview(ServerScreenHost.NetworkView network) {
        if (network == null) {
            new Notification("Network Unavailable", Notification.Type.ERROR);
            return;
        }
        serverHost().openNetworkSettings(this, network.id());
    }

    void showNetworkSettings(String networkId) {
        serverHost().openNetworkSettings(this, networkId);
    }

    private void attachNetworkServerAutomatically(ServerScreenHost.NetworkView network, ServerModels.ClientServerView instance) {
        if (!canAttachNetworkBackend(network, instance)) {
            new Notification("Invalid Network Member", "Only Standalone Local Backends Can Join This Velocity Network", Notification.Type.WARN);
            loadServersForAllTabs();
            return;
        }
        if (networkOperationInFlight) {
            loadServersForAllTabs();
            return;
        }
        boolean reSyncEnabled = serverHost().networkReSyncEnabled(network);
        if (!reSyncEnabled) {
            runAutomaticNetworkAttach(network, instance, false);
            return;
        }
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Install ReSync").width(400);
        builder.addRow(new PopupWidget.PopupRow.Builder("Enable Live Server Features").id("resync").description("Install The Latest ReSync On " + serverName(instance) + " For Player Controls, Shared Features, Events, And Live Status.").build());
        builder.addTitleAction("Continue Without ReSync", () -> {
            popup[0].hide();
            runAutomaticNetworkAttach(network, instance, false);
        }, PopupWidget.TitleActionRole.SECONDARY);
        builder.addTitleAction("Install ReSync", () -> {
            popup[0].hide();
            runAutomaticNetworkAttach(network, instance, true);
        }, PopupWidget.TitleActionRole.PRIMARY);
        popup[0] = builder.build();
        popup[0].setX((width - popup[0].getWidth()) / 2);
        popup[0].setY((height - popup[0].getHeight()) / 2);
        addDrawableChild(popup[0]);
        popup[0].show();
    }

    private void runAutomaticNetworkAttach(ServerScreenHost.NetworkView network, ServerModels.ClientServerView instance, boolean installReSync) {
        if (!canAttachNetworkBackend(network, instance)) {
            new Notification("Invalid Network Member", "Only Standalone Local Backends Can Join This Velocity Network", Notification.Type.WARN);
            loadServersForAllTabs();
            return;
        }
        networkOperationInFlight = true;
        pendingNetworkMembershipInstances.add(serverId(instance));
        Notification notification = new Notification.Builder().message(installReSync ? "Installing ReSync" : "Adding Server").description(installReSync ? "Preparing Live Server Features" : "Allocating Port And Updating Velocity").type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        long generation = callbackGeneration;
        serverHost().networkServerAction(network.id(), serverId(instance), installReSync ? "attach-resync" : "attach", installReSync).whenComplete((ignored, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            networkOperationInFlight = false;
            pendingNetworkMembershipInstances.remove(serverId(instance));
            if (throwable != null) {
                notification.update().message("Add Server Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message("Server Added").description(serverName(instance) + " Is Ready").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            }
            loadServersForAllTabs();
        }));
    }

    private void applyNetworkConfiguration(ServerScreenHost.NetworkView network) {
        if (networkOperationInFlight) {
            return;
        }
        networkOperationInFlight = true;
        Notification notification = new Notification.Builder().message("Syncing Network").description("Applying Ports, Routes, And Forwarding").type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        long generation = callbackGeneration;
        serverHost().networkAction(network, "reconcile").whenComplete((ignored, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            networkOperationInFlight = false;
            if (throwable != null) {
                notification.update().message("Network Sync Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message("Network Synced").description("Ports And Routes Are Current").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
                loadServersForAllTabs();
            }
        }));
    }

    private void dissolveNetwork(ServerScreenHost.NetworkView network) {
        if (networkOperationInFlight) {
            return;
        }
        networkOperationInFlight = true;
        Notification notification = new Notification.Builder().message("Dissolving Network").description("Restoring Standalone Settings").type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        long generation = callbackGeneration;
        ServerScreenHost host = serverHost();
        ServerScreenHost.AccountIdentity account = host.accountIdentity();
        var api = remotelyClient.getApiClient();
        host.networkAction(network, "dissolve").whenComplete((ignored, throwable) -> ScreenManager.getInstance().execute(() -> {
            ServerScreenHost.AccountIdentity current = host.accountIdentity();
            boolean owned = host == serverHost() && remotelyClient.getApiClient() == api && account.authenticated() == current.authenticated()
                    && (!account.authenticated() || compatibleAccountHydration(account, current));
            if (owned && throwable == null) invalidateNetwork(network.id());
            if (generation == callbackGeneration) networkOperationInFlight = false;
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (!owned) {
                notification.update().message("Account Changed").description("Refresh Your Servers To Check The Network").type(Notification.Type.WARN).loading(false).autoSlideOut(true).commit();
                return;
            }
            if (throwable != null) {
                String message = throwable instanceof HostedNetworkClient.OperationFailure ? "Dissolve Failed" : "Dissolve Not Confirmed";
                notification.update().message(message).description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                refreshNetworks();
                return;
            }
            notification.update().message("Network Dissolved").description("Servers Restored As Standalone").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            loadServersForAllTabs();
            refreshNetworks();
        }));
    }

    private void saveNetworkName(ServerScreenHost.NetworkView network, String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank()) {
            new Notification("Name Required", Notification.Type.ERROR);
            return;
        }
        long generation = callbackGeneration;
        serverHost().networkAction(network, "rename:" + name).whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure != null) new Notification("Edit Failed", rootMessage(failure), Notification.Type.ERROR);
            else { new Notification("Network Updated", name, Notification.Type.SUCCESS); loadServersForAllTabs(); }
        }));
    }

    private void runNetworkLifecycle(ServerScreenHost.NetworkView network, String operation) {
        Notification notification = new Notification.Builder().message(operation).description(network.name()).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        long generation = callbackGeneration;
        serverHost().networkAction(network, operation.toLowerCase(Locale.ROOT)).whenComplete((ignored, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (throwable != null) {
                notification.update().message("Network Needs Attention").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message("Network Ready").description("Network Operation Complete").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            }
            loadServersForAllTabs();
        }));
    }

    private void scanNetworkForAdoption(ServerModels.ClientServerView proxy) {
        scanNetwork(proxy, false);
    }

    private void scanLegacyMigration(ServerModels.ClientServerView proxy) {
        scanNetwork(proxy, true);
    }

    private void scanNetwork(ServerModels.ClientServerView proxy, boolean migrate) {
        if (networkOperationInFlight || !ensureServerManagerAction(proxy, ServerScreenHost.Action.NETWORK_IMPORT, "network.import")) return;
        networkOperationInFlight = true;
        Notification notification = new Notification.Builder().message("Scanning Network").description(serverName(proxy))
                .type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        long generation = callbackGeneration;
        Async<NetworkAdoptionReport> request;
        try {
            request = serverHost().scanNetwork(proxy, migrate);
        } catch (RuntimeException error) {
            request = Async.failed(error);
        }
        request.whenComplete((report, failure) -> ScreenManager.getInstance().execute(() -> {
            if (failure != null) {
                notification.update().message("Network Scan Failed").description(rootMessage(failure)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message("Network Scanned").description("Review Routes And Findings Before Importing").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            }
            if (!isCurrentCallback(generation)) return;
            networkOperationInFlight = false;
            if (failure != null) return;
            try {
                serverHost().openNetworkImport(this, proxy, report, migrate);
            } catch (RuntimeException error) {
                new Notification("Network Import Unavailable", rootMessage(error), Notification.Type.ERROR);
            }
        }));
    }

    private boolean isVelocityProxy(ServerModels.ClientServerView instance) {
        return isVelocityInstance(instance);
    }

    private boolean isLegacyProxy(ServerModels.ClientServerView instance) {
        return instance != null && ("WATERFALL".equalsIgnoreCase(instance.loader) || "BUNGEECORD".equalsIgnoreCase(instance.loader));
    }

    private void resumeNetworkJob(ServerScreenHost.NetworkView network) {
        long generation = callbackGeneration;
        serverHost().networkAction(network, "resume").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (isCurrentCallback(generation) && failure == null) loadServersForAllTabs();
        }));
    }

    private void rollbackNetworkJob(ServerScreenHost.NetworkView network) {
        long generation = callbackGeneration;
        serverHost().networkAction(network, "rollback").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (isCurrentCallback(generation) && failure == null) loadServersForAllTabs();
        }));
    }

    private void detachNetworkServer(ServerScreenHost.NetworkView network, ServerModels.ClientServerView instance) {
        if (!canDetachNetworkBackend(network, instance)) {
            loadServersForAllTabs();
            return;
        }
        if (networkOperationInFlight) {
            loadServersForAllTabs();
            return;
        }
        networkOperationInFlight = true;
        pendingNetworkMembershipInstances.add(serverId(instance));
        Notification notification = new Notification.Builder().message("Detaching Server").description(serverName(instance)).type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        long generation = callbackGeneration;
        serverHost().networkServerAction(network.id(), serverId(instance), "detach", false).whenComplete((ignored, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            networkOperationInFlight = false;
            pendingNetworkMembershipInstances.remove(serverId(instance));
            if (throwable != null) {
                notification.update().message("Detach Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            } else {
                notification.update().message("Server Detached").description(serverName(instance) + " Is Standalone").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            }
            loadServersForAllTabs();
        }));
    }

    private boolean canDetachNetworkBackend(ServerScreenHost.NetworkView network, ServerModels.ClientServerView instance) {
        if (network == null || instance == null || network.proxyId().isBlank() || network.proxyId().equals(serverId(instance))) {
            return false;
        }
        if (hasMultipleNetworkBackends(network)) {
            return true;
        }
        new Notification("Backend Required", "Dissolve Network To Remove Its Last Backend", Notification.Type.WARN);
        return false;
    }

    private boolean hasMultipleNetworkBackends(ServerScreenHost.NetworkView network) {
        return network != null && !network.proxyId().isBlank()
                && network.members().stream().filter(member -> !network.proxyId().equals(member)).count() > 1;
    }

    private boolean canDeleteInstance(ServerModels.ClientServerView instance) {
        return instance != null && !instance.isInstalling;
    }

    private void attachNetworkServer(ServerScreenHost.NetworkView network, ServerModels.ClientServerView instance) {
        attachNetworkServerAutomatically(network, instance);
    }

    private void attachNetworkGroup(ServerScreenHost.NetworkView network, List<ServerModels.ClientServerView> members) {
        Set<String> existingMembers = new HashSet<>(network.members());
        List<ServerModels.ClientServerView> candidates = members.stream()
                .filter(instance -> !existingMembers.contains(serverId(instance)))
                .filter(instance -> canAttachNetworkBackend(network, instance))
                .toList();
        if (candidates.isEmpty() || networkOperationInFlight) {
            return;
        }
        if (serverHost().networkReSyncEnabled(network)) {
                PopupWidget[] popup = new PopupWidget[1];
            PopupWidget.Builder builder = new PopupWidget.Builder("Install ReSync").width(400);
            builder.addRow(new PopupWidget.PopupRow.Builder("Enable Live Server Features").id("resync")
                    .description("Install The Latest ReSync On " + candidates.size() + " Servers For Player Controls, Shared Features, Events, And Live Status.").build());
            builder.addTitleAction("Continue Without ReSync", () -> {
                popup[0].hide();
                runNetworkGroupAttach(network, candidates, false);
            }, PopupWidget.TitleActionRole.SECONDARY);
            builder.addTitleAction("Install ReSync", () -> {
                popup[0].hide();
                runNetworkGroupAttach(network, candidates, true);
            }, PopupWidget.TitleActionRole.PRIMARY);
            popup[0] = builder.build();
            popup[0].setX((width - popup[0].getWidth()) / 2);
            popup[0].setY((height - popup[0].getHeight()) / 2);
            addDrawableChild(popup[0]);
            popup[0].show();
            return;
        }
        runNetworkGroupAttach(network, candidates, false);
    }

    private void runNetworkGroupAttach(ServerScreenHost.NetworkView network, List<ServerModels.ClientServerView> members, boolean installReSync) {
        List<ServerModels.ClientServerView> joinedMembers = members.stream().filter(instance -> canAttachNetworkBackend(network, instance)).toList();
        if (joinedMembers.isEmpty() || networkOperationInFlight) {
            return;
        }
        networkOperationInFlight = true;
        String groupContext = activeServerGroupContext();
        List<DesktopGroup> groups = new ArrayList<>(instanceGroups);
        joinedMembers.stream().map(ServerManagerScreen::serverId).forEach(pendingNetworkMembershipInstances::add);
        Notification notification = new Notification.Builder().message(installReSync ? "Installing ReSync" : "Adding Group").description(joinedMembers.size() + " Servers").type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        Async<Void> operations = NetworkGroupAttachmentTransaction.execute(joinedMembers,
                instance -> serverHost().networkServerAction(network.id(), serverId(instance), "attach", installReSync)
                        .thenApply(ignored -> true),
                instance -> serverHost().networkServerAction(network.id(), serverId(instance), "detach", false));
        long generation = callbackGeneration;
        operations.whenComplete((unused, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            networkOperationInFlight = false;
            joinedMembers.stream().map(ServerManagerScreen::serverId).forEach(pendingNetworkMembershipInstances::remove);
            if (throwable != null) {
                notification.update().message("Add Group Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            } else {
                groups.removeIf(group -> group.members().stream().anyMatch(member -> joinedMembers.stream().anyMatch(instance -> serverId(instance).equals(member))));
                saveConfiguredGroups(groupContext, groups);
                if (groupContext.equals(activeServerGroupContext())) {
                    instanceGroups = groups;
                }
                notification.update().message("Group Added").description(joinedMembers.size() + " Servers Joined " + network.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            }
            loadServersForAllTabs();
        }));
    }


    private void configureNetworkDrop(DesktopLayout layout) {
        layout.setTopMargin(8);
        layout.setGroupSpacing(0, null);
        layout.setOnDrop(this::handleServerGroupDrop);
        layout.setDropTargetFilter(this::canGroupServerDrop);
        layout.setOnDropAt(null);
    }

    private boolean canGroupServerDrop(AnimatedWidget dragged, AnimatedWidget target) {
        boolean draggable = dragged instanceof DesktopIconWidget<?> icon && icon.getItem() instanceof ServerModels.ClientServerView
                || dragged instanceof DesktopGroupWidget<?> group && !group.getGroup().id().startsWith("network:");
        boolean targetable = target instanceof DesktopIconWidget<?> icon && icon.getItem() instanceof ServerModels.ClientServerView
                || target instanceof DesktopGroupWidget<?>;
        if (!draggable || !targetable) {
            return false;
        }
        if (!(target instanceof DesktopGroupWidget<?> targetGroup) || !targetGroup.getGroup().id().startsWith("network:")) {
            return true;
        }
        String networkId = targetGroup.getGroup().id().substring("network:".length());
        ServerScreenHost.NetworkView network = networkViews.stream().filter(value -> value.id().equals(networkId)).findFirst().orElse(null);
        if (network == null || networkOperationInFlight) {
            return false;
        }
        if (dragged instanceof DesktopIconWidget<?> icon && icon.getItem() instanceof ServerModels.ClientServerView server) {
            return canAttachNetworkBackend(network, server);
        }
        if (dragged instanceof DesktopGroupWidget<?> group) {
            return groupServers(group).allMatch(server -> canAttachNetworkBackend(network, server));
        }
        return false;
    }

    private void handleServerGroupDrop(AnimatedWidget dragged, AnimatedWidget target) {
        if (dragged instanceof DesktopGroupWidget<?> draggedGroup) {
            List<ServerModels.ClientServerView> draggedMembers = groupServers(draggedGroup).collect(Collectors.toCollection(ArrayList::new));
            if (target instanceof DesktopGroupWidget<?> targetGroup && targetGroup.getGroup().id().startsWith("network:")) {
                networkViews.stream().filter(network -> ("network:" + network.id()).equals(targetGroup.getGroup().id())).findFirst().ifPresent(network -> attachNetworkGroup(network, draggedMembers));
                return;
            }
            if (target instanceof DesktopGroupWidget<?> targetGroup) {
                groupServers(targetGroup).forEach(draggedMembers::add);
                instanceGroups.removeIf(group -> group.id().equals(draggedGroup.getGroup().id()) || group.id().equals(targetGroup.getGroup().id()));
                instanceGroups.add(new DesktopGroup(targetGroup.getGroup().id(), targetGroup.getGroup().name(), draggedMembers.stream().map(ServerManagerScreen::serverId).distinct().toList()));
                saveConfiguredGroups(activeServerGroupContext(), instanceGroups);
                loadServersForCurrentTab();
                return;
            }
            if (target instanceof DesktopIconWidget<?> targetIcon && targetIcon.getItem() instanceof ServerModels.ClientServerView targetInstance) {
                draggedMembers.add(targetInstance);
                replaceServerGroup(draggedGroup.getGroup().id(), draggedMembers);
            }
            return;
        }
        if (!(dragged instanceof DesktopIconWidget<?> draggedIcon) || !(draggedIcon.getItem() instanceof ServerModels.ClientServerView draggedInstance)) {
            return;
        }
        if (target instanceof DesktopGroupWidget<?> groupWidget && groupWidget.getGroup().id().startsWith("network:")) {
            networkViews.stream().filter(network -> ("network:" + network.id()).equals(groupWidget.getGroup().id())).findFirst().ifPresent(network -> attachNetworkServer(network, draggedInstance));
            return;
        }
        if (target instanceof DesktopIconWidget<?> targetIcon && targetIcon.getItem() instanceof ServerModels.ClientServerView targetInstance) {
            createServerGroup(List.of(draggedInstance, targetInstance));
            return;
        }
        if (target instanceof DesktopGroupWidget<?> groupWidget) {
            List<ServerModels.ClientServerView> members = groupServers(groupWidget).collect(Collectors.toCollection(ArrayList::new));
            members.add(draggedInstance);
            replaceServerGroup(groupWidget.getGroup().id(), members);
        }
    }

    private void replaceServerGroup(String groupId, List<ServerModels.ClientServerView> members) {
        instanceGroups.replaceAll(group -> group.id().equals(groupId) ? new DesktopGroup(group.id(), group.name(), members.stream().map(ServerManagerScreen::serverId).distinct().toList()) : group);
        saveConfiguredGroups(activeServerGroupContext(), instanceGroups);
        loadServersForCurrentTab();
    }


    private void showSelectedServersMenu(DesktopIconWidget<ServerModels.ClientServerView> anchor, List<ServerModels.ClientServerView> selected) {
        List<ServerModels.ClientServerView> targets = List.copyOf(selected);
        long generation = callbackGeneration;
        TabsManager.Tab tab = tabs().getActiveTab();
        ServerScreenHost host = serverHost();
        String account = accountKey(host.accountIdentity());
        Consumer<Runnable> run = action -> {
            if (isCurrentCallback(generation) && tab == tabs().getActiveTab() && host == serverHost()
                    && account.equals(accountKey(host.accountIdentity()))) action.run();
        };
        long running = selected.stream().filter(this::isServerActive).count();
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(this)
                .addIconItem(selected.size() + " Servers Selected", "info.png", () -> {}, running + " Running")
                .addIconItem("Start Selected", "start.png", () -> run.accept(() -> targets.stream().filter(instance -> !isServerActive(instance))
                        .forEach(instance -> setServerPower(instance, true))), "Start Servers That Are Stopped", ThemeManager.getAccent("nice"))
                .addIconItem("Stop Selected", "stop.png", () -> run.accept(() -> targets.stream().filter(this::isServerActive)
                        .forEach(instance -> setServerPower(instance, false))), "Stop Servers That Are Running", ThemeManager.getAccent("danger"))
                .addIconItem("Group Selected", "folder.png", () -> run.accept(() -> createServerGroup(targets)), "Keep These Servers Together");
        if (selected.stream().allMatch(this::canDuplicateServer)) {
            builder.addIconItem("Duplicate Selected", "copy.png", () -> run.accept(() -> targets.forEach(this::duplicateInstance)), "Create A Copy Of Each Server");
        }
        if (selected.stream().allMatch(server -> canServerManagerAction(server, ServerScreenHost.Action.DELETE_SERVER, "server.delete"))) {
            builder.addIconItem("Delete Selected", "delete.png", () -> run.accept(() -> showDeleteServersPopup(targets)), "Review Deletion Options For These Servers", ThemeManager.getAccent("danger"));
        }
        builder.addIconItem("Hide Selected", "hide.png", () -> run.accept(() -> showDeleteServersPopup(targets)), "Review Options To Hide These Servers Without Deleting Files");
        showDesktopContextMenu(anchor.getX() + anchor.getWidth() + 4, anchor.getY() + 24, builder);
    }

    private boolean isServerActive(ServerModels.ClientServerView instance) {
        if (instance == null) {
            return false;
        }
        return switch (serverHost().state(instance)) {
            case RUNNING, STARTING, STOPPING -> true;
            default -> false;
        };
    }

    private boolean canDuplicateServer(ServerModels.ClientServerView instance) {
        return canServerManagerAction(instance, ServerScreenHost.Action.DUPLICATE_SERVER, "server.duplicate");
    }

    private String normalizeNetworkRoute(String value) {
        String route = value == null ? "server" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return route.isBlank() ? "server" : route;
    }

    private void setServerPower(ServerModels.ClientServerView instance, boolean start) {
        long generation = callbackGeneration;
        serverHost().setServerPower(remotelyClient.getApiClient(), instance, start ? "start" : "stop").whenComplete((ignored, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (throwable != null) new Notification(start ? "Server Start Failed" : "Server Stop Failed", rootMessage(throwable), Notification.Type.ERROR);
            else new Notification(start ? "Server Started" : "Server Stopped", serverName(instance), Notification.Type.SUCCESS);
            loadServersForCurrentTab();
        }));
    }

    private void duplicateInstance(ServerModels.ClientServerView instance) {
        if (!ensureServerManagerAction(instance, ServerScreenHost.Action.DUPLICATE_SERVER, "server.duplicate")) {
            return;
        }
        long generation = callbackGeneration;
        serverHost().serverAction(instance, "duplicate").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            new Notification(failure == null ? "Server Duplicated" : "Duplication Failed", failure == null ? "Server Is Ready" : rootMessage(failure), failure == null ? Notification.Type.SUCCESS : Notification.Type.ERROR);
            if (failure == null) loadServersForCurrentTab();
        }));
    }

    private void customizeIcon(ServerModels.ClientServerView instance, ServerScreenHost.HostView remoteHost) {
        serverHost().openIconCustomizer(this, instance, remoteHost, this::loadServersForCurrentTab);
    }


    private List<ServerModels.ClientServerView> getCurrentServers() {
        return currentServerViews;
    }

    private void openFileExplorer() {
        serverHost().openGlobalFileExplorer(this);
    }

    @Override
    protected void refreshStartEntries() {
        setStartCategories(EnumSet.of(StartEntry.Category.SETTINGS, StartEntry.Category.FILES, StartEntry.Category.ACTIONS,
                StartEntry.Category.SERVERS, StartEntry.Category.RESOURCES, StartEntry.Category.THEMES, StartEntry.Category.LOGS,
                StartEntry.Category.BACKUPS, StartEntry.Category.UPDATES, StartEntry.Category.CALCULATOR));
        String authority = currentStartAuthority();
        Object api = remotelyClient.getApiClient();
        if (!authority.equals(startAuthority) || api != startApi) {
            resetStartSearch();
            startAuthority = authority;
            startApi = api;
        }
        bindStartHistory();
        if (startCatalogDirty) {
            List<StartEntry> entries = new ArrayList<>(ServerStartEntries.global(this, serverHost()));
            Map<String, ServerModels.ClientServerView> servers = new LinkedHashMap<>();
            String context = browserHostKey(tabs().getActiveTab() == null ? null : tabs().getActiveTab().getData());
            for (ServerModels.ClientServerView server : getCurrentServers()) {
                String key = context + ":" + serverHost().serverOrderKey(server);
                if (servers.putIfAbsent(key, server) != null) continue;
                String name = serverName(server);
                entries.add(new StartEntry("server:" + key, name, "Open Server", StartEntry.Category.SERVERS,
                        startServerIcon(server), () -> openStartServer(key), List.of(name, "terminal", "console")).withScope(key, name));
                entries.add(new StartEntry("server:" + key + ":terminal", "Terminal", name + " Console", StartEntry.Category.ACTIONS,
                        Identifier.icon("terminal.png"), () -> openStartServer(key), List.of("console", "commands")).withScope(key, name).withBadge(startServerIcon(server)));
                entries.addAll(ServerStartEntries.server(this, serverHost(), server, "server:" + key).stream()
                        .map(entry -> entry.withScope(key, name).withBadge(startServerIcon(server))).toList());
                entries.add(new StartEntry("server:" + key + ":files", name + " Files", "Open File Explorer", StartEntry.Category.ACTIONS,
                        Identifier.icon("explorer.png"), () -> {
                            ServerModels.ClientServerView current = startServers.get(key);
                            if (current != null) serverHost().openFileExplorer(this, current);
                        }, List.of(name, "files", "folder", "explorer")).withScope(key, name).withBadge(startServerIcon(server)));
            }
            addStartAction(entries, "files", "File Explorer", "Browse Files", ServerScreenHost.Action.FILE_EXPLORER, this::openFileExplorer);
            addStartAction(entries, "terminal", "Terminal", "Open Terminal", ServerScreenHost.Action.GLOBAL_TERMINAL, () -> serverHost().openGlobalTerminal(this));
            addStartAction(entries, "create", "Create Server", "Create, Install Or Import A Server", ServerScreenHost.Action.CREATE_SERVER, this::showCreateChoice);
            addStartAction(entries, "host", "Connect Host", "Connect A Remote Server Host", ServerScreenHost.Action.REMOTE_HOST, () -> serverHost().openRemoteHost(this));
            entries.add(new StartEntry("action:refresh-files", "Refresh Files", "Refresh File Search", StartEntry.Category.ACTIONS,
                    Identifier.icon("reload.png"), () -> { if (startFileIndex != null) startFileIndex.refresh(); }, List.of("search", "index", "reload")));
            startServers = Map.copyOf(servers);
            boolean changedSource = startBindings.entrySet().stream().anyMatch(entry -> {
                ServerModels.ClientServerView current = servers.get(entry.getKey());
                if (current == null) return true;
                return !currentStartSource(current, entry.getValue());
            });
            if (changedSource) {
                startNavigationGeneration++;
                startBindings.entrySet().removeIf(entry -> !currentStartSource(servers.get(entry.getKey()), entry.getValue()));
            }
            startCatalog = List.copyOf(entries);
            startCatalogRevision++;
            startCatalogDirty = false;
            if (!servers.containsKey(startScope)) startScope = "";
            setStartScopes(servers.entrySet().stream().map(entry -> new StartMenuWidget.Scope(entry.getKey(), serverName(entry.getValue()), startServerIcon(entry.getValue()))).toList(),
                    startScope, this::selectStartScope);
        }
        bindStartFiles();
        bindStartResources();
        bindStartSearches();
        publishStartEntries(startFileIndex == null ? null : startFileIndex.snapshot());
    }

    private void addStartAction(List<StartEntry> entries, String id, String title, String detail, ServerScreenHost.Action action, Runnable run) {
        String icon = switch (id) {
            case "files" -> "explorer.png";
            case "terminal" -> "terminal.png";
            case "create" -> "server.png";
            case "host" -> "reverse.png";
            default -> "change.png";
        };
        if (serverHost().supports(action)) entries.add(new StartEntry("action:" + id, title, detail, StartEntry.Category.ACTIONS,
                Identifier.icon(icon), run, List.of(title, detail)));
    }

    private void openStartServer(String key) {
        ServerModels.ClientServerView server = startServers.get(key);
        if (server != null) openServerScreen(server);
    }

    private Identifier startServerIcon(ServerModels.ClientServerView server) {
        Identifier icon = iconManager.getQuickIconId(iconTarget(server));
        return icon == null || icon.equals(Identifier.icon("server.png")) ? Identifier.icon("reverse.png") : icon;
    }

    private void bindStartHistory() {
        String account = accountKey(serverHost().accountIdentity());
        RemotelyConfigStore config = remotelyClient.getComposition().configManager();
        if (account.equals(startHistoryAccount) && config == startHistoryConfig) return;
        startHistoryAccount = account;
        startHistoryConfig = config;
        String key = "remotely.start.history." + Base64.getUrlEncoder().withoutPadding().encodeToString(account.getBytes(StandardCharsets.UTF_8));
        setStartHistory(startStore(config, account, key));
        setStartHome(startStore(config, account, key.replace(".history.", ".home.")),
                List.of("settings:global", "action:terminal", "action:files", "action:create", "start:resources", "desktop:layout"));
    }

    private StartHistory.Store startStore(RemotelyConfigStore config, String account, String key) {
        return new StartHistory.Store() {
            public String load() { return config == null ? "" : config.get(key, ""); }
            public void changed(String encoded) {
                if (config == null || config != remotelyClient.getComposition().configManager()
                        || !account.equals(accountKey(serverHost().accountIdentity()))) return;
                config.set(key, encoded);
                config.save();
            }
        };
    }

    private void selectStartScope(String scope) {
        if (scope.equals(startScope) || !scope.isBlank() && !startServers.containsKey(scope)) return;
        startScope = scope;
        if (startFileIndex != null && !scope.isBlank()) startFileIndex.prioritize(scope);
        if (startFileIndex != null) updateStartStatus(startFileIndex.snapshot());
    }

    private void bindStartFiles() {
        if (startFileIndex != null && startBoundCatalog == startCatalogRevision) return;
        startBoundCatalog = startCatalogRevision;
        List<WorkspaceFileCatalog.Scope> scopes = new ArrayList<>();
        for (Map.Entry<String, ServerModels.ClientServerView> entry : startServers.entrySet()) {
            String scope = entry.getKey();
            ServerModels.ClientServerView server = entry.getValue();
            StartFileBinding binding = startBindings.get(scope);
            if (binding == null || !currentStartSource(server, binding)) {
                ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
                Object source = serverHost().iconTarget(server);
                ServerScreenHost.StorageFiles files = serverHost().storageFiles(source);
                DeveloperCapabilityProvider developer = DeveloperCapabilityProviders.resolve(source, files.provider());
                binding = new StartFileBinding(identity.backendType(), identity.path(), serverHost().configurationTarget(source).backendCredentials(),
                        serverHost().fileSourceOwner(source), files, developer.fileWatch());
                startBindings.put(scope, binding);
            }
            ServerScreenHost.StorageFiles files = binding.files();
            scopes.add(new WorkspaceFileCatalog.Scope(scope, files.provider(), files.root(), binding.watcher()));
        }
        if (startFileIndex == null) {
            WorkspaceFileCatalog index = new WorkspaceFileCatalog(FileExplorerProviders.scheduler(), action -> ScreenManager.getInstance().execute(action));
            startFileIndex = index;
            index.onChanged(snapshot -> {
                if (startFileIndex == index && reactiveRefreshEnabled) publishStartEntries(snapshot);
            });
        }
        startFileIndex.setScopes(scopes);
        if (!startScope.isBlank()) startFileIndex.prioritize(startScope);
        startFileIndex.start();
    }

    private void bindStartResources() {
        if (startResourceSearch != null && startBoundResources == startCatalogRevision) return;
        startBoundResources = startCatalogRevision;
        if (startResourceSearch == null) {
            ResourceStartSearch search = new ResourceStartSearch(this, remotelyClient.getComposition().scheduler(), action -> ScreenManager.getInstance().execute(action));
            startResourceSearch = search;
            setStartResourceRefresh(search::refresh);
            setStartSearchRefresh(StartEntry.Category.UPDATES, search::refresh);
            search.onLoading(value -> {
                if (startResourceSearch == search && reactiveRefreshEnabled && startAuthority.equals(currentStartAuthority())
                        && startApi == remotelyClient.getApiClient()) {
                    startResourceLoading = value;
                    updateStartLoading();
                }
            });
            search.onEntries(entries -> {
                if (startResourceSearch != search || !reactiveRefreshEnabled || !startAuthority.equals(currentStartAuthority())
                        || startApi != remotelyClient.getApiClient()) return;
                if (startResourceEntries.equals(entries)) return;
                startResourceEntries = List.copyOf(entries);
                startResourceRevision++;
                publishStartEntries(startFileIndex == null ? null : startFileIndex.snapshot());
            });
            search.onStatus(status -> {
                if (startResourceSearch != search || !reactiveRefreshEnabled || !startAuthority.equals(currentStartAuthority())
                        || startApi != remotelyClient.getApiClient()) return;
                startResourceStatus = status;
                if (resourceStartSearch()) setStartStatus(status);
            });
            search.onProviders(this::setStartResourceProviders);
            setStartSearchListener(this::searchStart);
        }
        if (startGlobalMarket == null) {
            ResourceMarketplaceProvider marketplace = serverHost().resourceSearchMarketplace(null);
            ResourceBrowserContext context = marketplace == null ? null : serverHost().resourceSearchContext(null, marketplace);
            if (context != null) startGlobalMarket = new ResourceStartSearch.Target("", "Online Resources", Identifier.icon("resources.png"),
                    startAuthority, marketplace, context);
        }
        startMarkets.entrySet().removeIf(entry -> {
            ServerModels.ClientServerView server = startServers.get(entry.getKey());
            StartMarketStamp stamp = entry.getValue().stamp();
            if (currentStartSource(server, stamp.source()) && startBindings.get(entry.getKey()) == stamp.source()
                    && Objects.equals(server.version, stamp.version()) && Objects.equals(server.loader, stamp.loader())) return false;
            entry.getValue().target().context().invalidate();
            return true;
        });
        List<ResourceStartSearch.Target> targets = new ArrayList<>();
        for (Map.Entry<String, ServerModels.ClientServerView> entry : startServers.entrySet()) {
            String id = entry.getKey();
            ServerModels.ClientServerView server = entry.getValue();
            StartFileBinding source = startBindings.get(id);
            if (!currentStartSource(server, source)) continue;
            StartMarketBinding binding = startMarkets.get(id);
            String name = serverName(server);
            Identifier icon = startServerIcon(server);
            if (binding == null) {
                Object target = iconTarget(server);
                ResourceMarketplaceProvider marketplace = serverHost().resourceSearchMarketplace(target);
                ResourceBrowserContext context = marketplace == null ? null : serverHost().resourceSearchContext(target, marketplace);
                if (context == null) continue;
                StartMarketStamp stamp = new StartMarketStamp(source, server.version, server.loader);
                binding = new StartMarketBinding(stamp, new ResourceStartSearch.Target(id, name, icon, stamp, marketplace, context));
                startMarkets.put(id, binding);
            } else if (!binding.target().label().equals(name) || !Objects.equals(binding.target().icon(), icon)) {
                ResourceStartSearch.Target current = binding.target();
                binding = new StartMarketBinding(binding.stamp(), new ResourceStartSearch.Target(id, name, icon, binding.stamp(), current.marketplace(), current.context()));
                startMarkets.put(id, binding);
            }
            targets.add(binding.target());
        }
        startResourceSearch.setTargets(startGlobalMarket, targets);
    }

    private void bindStartSearches() {
        if (startBoundSpecial == startCatalogRevision) return;
        startBoundSpecial = startCatalogRevision;
        if (startLogSearch == null) {
            LogStartSearch search = new LogStartSearch(this, FileExplorerProviders.scheduler(), action -> ScreenManager.getInstance().execute(action));
            startLogSearch = search;
            setStartSearchRefresh(StartEntry.Category.LOGS, search::refresh);
            search.onEntries(entries -> {
                if (startLogSearch != search || !currentStartSearch() || startLogEntries.equals(entries)) return;
                startLogEntries = List.copyOf(entries);
                startSpecialRevision++;
                publishStartEntries(startFileIndex == null ? null : startFileIndex.snapshot());
            });
            search.onStatus(status -> {
                if (startLogSearch != search || !currentStartSearch()) return;
                startLogStatus = status;
                if (startSearch.active() && startSearch.category() == StartEntry.Category.LOGS) setStartStatus(status);
            });
            search.onLoading(value -> {
                if (startLogSearch != search || !currentStartSearch()) return;
                startLogLoading = value;
                updateStartLoading();
            });
        }
        if (startBackupSearch == null) {
            BackupStartSearch search = new BackupStartSearch(this, remotelyClient.getComposition().scheduler(), action -> ScreenManager.getInstance().execute(action));
            startBackupSearch = search;
            setStartSearchRefresh(StartEntry.Category.BACKUPS, search::refresh);
            search.onEntries(entries -> {
                if (startBackupSearch != search || !currentStartSearch() || startBackupEntries.equals(entries)) return;
                startBackupEntries = List.copyOf(entries);
                startSpecialRevision++;
                publishStartEntries(startFileIndex == null ? null : startFileIndex.snapshot());
            });
            search.onStatus(status -> {
                if (startBackupSearch != search || !currentStartSearch()) return;
                startBackupStatus = status;
                if (startSearch.active() && startSearch.category() == StartEntry.Category.BACKUPS) setStartStatus(status);
            });
            search.onLoading(value -> {
                if (startBackupSearch != search || !currentStartSearch()) return;
                startBackupLoading = value;
                updateStartLoading();
            });
        }
        if (startServerActions == null) {
            ServerStartActions search = new ServerStartActions(this, remotelyClient, action -> ScreenManager.getInstance().execute(action));
            startServerActions = search;
            search.onEntries(entries -> {
                if (startServerActions != search || !currentStartSearch() || startActionEntries.equals(entries)) return;
                startActionEntries = List.copyOf(entries);
                startSpecialRevision++;
                publishStartEntries(startFileIndex == null ? null : startFileIndex.snapshot());
            });
        }
        startBackups.entrySet().removeIf(entry -> startBindings.get(entry.getKey()) != entry.getValue().stamp());
        List<LogStartSearch.Source> logs = new ArrayList<>();
        List<BackupStartSearch.Source> backups = new ArrayList<>();
        List<ServerStartActions.Source> actions = new ArrayList<>();
        for (Map.Entry<String, ServerModels.ClientServerView> entry : startServers.entrySet()) {
            String id = entry.getKey();
            ServerModels.ClientServerView server = entry.getValue();
            StartFileBinding source = startBindings.get(id);
            if (!currentStartSource(server, source)) continue;
            String name = serverName(server);
            Identifier icon = startServerIcon(server);
            ServerScreenHost.StorageFiles files = source.files();
            logs.add(new LogStartSearch.Source(id, name, icon, source, files.provider(), files.root(),
                    (path, line) -> openStartLog(id, source, path, line), source.watcher()));
            StartBackupBinding backup = startBackups.get(id);
            if (backup == null) {
                backup = new StartBackupBinding(source, serverHost().startBackups(this, serverHost().iconTarget(server)));
                startBackups.put(id, backup);
            }
            backups.add(new BackupStartSearch.Source(id, name, icon, source, backup.backend()));
            actions.add(new ServerStartActions.Source(id, name, icon, source, serverHost().iconTarget(server)));
        }
        startLogSearch.setSources(logs);
        startBackupSearch.setSources(backups);
        startServerActions.setSources(actions);
        searchStart(startSearch);
    }

    private void openStartLog(String scope, StartFileBinding binding, RemotePath path, int line) {
        ServerModels.ClientServerView server = startServers.get(scope);
        if (!currentStartSearch() || server == null || startBindings.get(scope) != binding || !currentStartSource(server, binding)) return;
        ServerScreenHost.StorageFiles files = binding.files();
        String authority = startAuthority;
        long generation = ++startNavigationGeneration;
        FileExplorerProviders.canOpenEditor(files.provider(), path).whenComplete((accepted, failure) -> ScreenManager.getInstance().execute(() -> {
            ServerModels.ClientServerView current = startServers.get(scope);
            if (!currentStartSearch() || generation != startNavigationGeneration || startBindings.get(scope) != binding
                    || !authority.equals(startAuthority) || !currentStartSource(current, binding)) return;
            if (failure != null) {
                new Notification("Log Open Failed", rootMessage(failure), Notification.Type.ERROR);
                return;
            }
            Object context = serverHost().iconTarget(current);
            if (Boolean.TRUE.equals(accepted) && FileExplorerProviders.openEditor(this, context, files.provider(), files.root(), path, files.root(), line)) return;
            serverHost().application().setScreen(new FileExplorerScreen(this, context, path.parent(), files.root(), false, files.provider()));
        }));
    }

    private boolean currentStartSearch() {
        return reactiveRefreshEnabled && startAuthority.equals(currentStartAuthority()) && startApi == remotelyClient.getApiClient();
    }

    private boolean resourceStartSearch() {
        return startSearch.active() && (startSearch.category() == StartEntry.Category.RESOURCES || startSearch.category() == StartEntry.Category.UPDATES);
    }

    private void updateStartLoading() {
        boolean loading = resourceStartSearch() && startResourceLoading
                || startSearch.active() && startSearch.category() == StartEntry.Category.LOGS && startLogLoading
                || startSearch.active() && startSearch.category() == StartEntry.Category.BACKUPS && startBackupLoading;
        setStartSearching(loading);
    }

    private void searchStart(StartMenuWidget.Search query) {
        startSearch = query;
        if (startFileIndex != null) startFileIndex.prioritize(query.scopeIds());
        if (startResourceSearch != null) startResourceSearch.search(query);
        if (startLogSearch != null) startLogSearch.search(query);
        if (startBackupSearch != null) startBackupSearch.search(query);
        if (startServerActions != null) startServerActions.search(query);
        updateStartLoading();
        if (startFileIndex != null) updateStartStatus(startFileIndex.snapshot());
    }

    private void publishStartEntries(WorkspaceFileCatalog.Snapshot snapshot) {
        if (!startAuthority.equals(currentStartAuthority()) || startApi != remotelyClient.getApiClient()) return;
        long revision = snapshot == null ? -1 : snapshot.revision();
        long resourcesRevision = startResourceRevision;
        if (startPublishedCatalog == startCatalogRevision && startPublishedIndex == revision && startPublishedResources == resourcesRevision && startPublishedSpecial == startSpecialRevision) return;
        startPublishedCatalog = startCatalogRevision;
        startPublishedIndex = revision;
        startPublishedResources = resourcesRevision;
        startPublishedSpecial = startSpecialRevision;
        List<StartEntry> entries = new ArrayList<>(startCatalog);
        if (snapshot != null) {
            for (WorkspaceFileCatalog.Result result : snapshot.scopes()) {
                String scope = result.scope().id();
                ServerModels.ClientServerView server = startServers.get(scope);
                StartFileBinding binding = startBindings.get(scope);
                if (server == null || !currentStartSource(server, binding) || result.scope().provider() != binding.files().provider()
                        || !result.scope().root().equals(binding.files().root())) continue;
                String name = serverName(server);
                Identifier icon = startServerIcon(server);
                StartFileRows rows = startFileRows.get(scope);
                if (rows == null || rows.source() != binding || rows.result() != result || !rows.name().equals(name) || !Objects.equals(rows.icon(), icon)) {
                    List<StartEntry> files = new ArrayList<>();
                    for (WorkspaceFileIndex.Entry entry : result.files().entries()) {
                        files.add(new StartEntry("file:" + scope + ":" + entry.path(), entry.name(), name + " / " + entry.relativePath(),
                                StartEntry.Category.FILES, Identifier.icon(entry.directory() ? "folder.png" : "file.png"),
                                () -> openStartFile(scope, entry), List.of(entry.relativePath())).withScope(scope, name).withBadge(icon));
                    }
                    rows = new StartFileRows(binding, result, name, icon, List.copyOf(files));
                    startFileRows.put(scope, rows);
                }
                entries.addAll(rows.entries());
            }
        }
        entries.addAll(startResourceEntries);
        entries.addAll(startLogEntries);
        entries.addAll(startBackupEntries);
        entries.addAll(startActionEntries);
        if (snapshot != null) startFileRows.keySet().retainAll(snapshot.scopes().stream().map(result -> result.scope().id()).collect(Collectors.toSet()));
        if (snapshot != null) updateStartStatus(snapshot);
        setStartEntries(entries);
    }

    private void updateStartStatus(WorkspaceFileCatalog.Snapshot snapshot) {
        if (resourceStartSearch()) {
            setStartStatus(startResourceStatus);
            return;
        }
        if (startSearch.active() && startSearch.category() == StartEntry.Category.LOGS) {
            setStartStatus(startLogStatus);
            return;
        }
        if (startSearch.active() && startSearch.category() == StartEntry.Category.BACKUPS) {
            setStartStatus(startBackupStatus);
            return;
        }
        if (startSearch.active() && startSearch.category() == StartEntry.Category.CALCULATOR) {
            setStartStatus("");
            return;
        }
        if (startSearch.scopeIds().size() == 1) {
            WorkspaceFileCatalog.Result selected = snapshot.scopes().stream().filter(result -> result.scope().id().equals(startSearch.scopeId())).findFirst().orElse(null);
            if (selected != null && !selected.files().detail().isBlank()) {
                setStartStatus(selected.files().detail());
                return;
            }
        }
        List<WorkspaceFileCatalog.Result> selected = startSearch.scopeIds().isEmpty() ? snapshot.scopes()
                : snapshot.scopes().stream().filter(result -> startSearch.scopeIds().contains(result.scope().id())).toList();
        int count = selected.stream().mapToInt(result -> result.files().entries().size()).sum();
        String detail = selected.stream().map(result -> result.files().detail()).filter(value -> !value.isBlank()).distinct().collect(Collectors.joining(". "));
        setStartStatus(detail.isBlank() ? count + " Files And Folders Across " + selected.size() + " Servers" : detail);
    }

    private void openStartFile(String scope, WorkspaceFileIndex.Entry entry) {
        ServerModels.ClientServerView server = startServers.get(scope);
        StartFileBinding binding = startBindings.get(scope);
        if (server == null || !currentStartSource(server, binding)
                || !startAuthority.equals(currentStartAuthority()) || startApi != remotelyClient.getApiClient()) return;
        ServerScreenHost.StorageFiles files = binding.files();
        String authority = startAuthority;
        long generation = ++startNavigationGeneration;
        if (entry.directory()) {
            serverHost().application().setScreen(new FileExplorerScreen(this, serverHost().iconTarget(server), entry.path(), files.root(), false, files.provider()));
            return;
        }
        FileExplorerProviders.canOpenEditor(files.provider(), entry.path()).whenComplete((accepted, failure) -> ScreenManager.getInstance().execute(() -> {
            ServerModels.ClientServerView current = startServers.get(scope);
            if (!reactiveRefreshEnabled || generation != startNavigationGeneration || startBindings.get(scope) != binding
                    || !authority.equals(currentStartAuthority()) || remotelyClient.getApiClient() != startApi || current == null
                    || !currentStartSource(current, binding)) return;
            if (failure != null) {
                new Notification("File Open Failed", rootMessage(failure), Notification.Type.ERROR);
                return;
            }
            Object context = serverHost().iconTarget(server);
            if (Boolean.TRUE.equals(accepted) && FileExplorerProviders.openEditor(this, context, files.provider(), files.root(), entry.path(), files.root())) return;
            serverHost().application().setScreen(new FileExplorerScreen(this, context, entry.path().parent(), files.root(), false, files.provider()));
        }));
    }

    private void resetStartSearch() {
        dropStartFiles();
        startCatalog = List.of();
        startServers = Map.of();
        startCatalogDirty = true;
        startAuthority = "";
        startScope = "";
        startApi = null;
        startCatalogRevision++;
        startPublishedCatalog = -1;
        startPublishedIndex = -1;
        setStartEntries(List.of());
        setStartScopes(List.of(), "", null);
        setStartStatus("");
    }

    private String currentStartAuthority() {
        return accountKey(serverHost().accountIdentity()) + ":" + serverHost().authenticationSession() + ":" + callbackGeneration;
    }

    private boolean currentStartSource(ServerModels.ClientServerView server, StartFileBinding binding) {
        if (server == null || binding == null) return false;
        ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
        Object source = serverHost().iconTarget(server);
        return binding.backend().equals(identity.backendType()) && binding.path().equals(identity.path())
                && binding.credentials().equals(serverHost().configurationTarget(source).backendCredentials())
                && binding.owner() == serverHost().fileSourceOwner(source);
    }

    private void dropStartFiles() {
        startNavigationGeneration++;
        if (startResourceSearch != null) startResourceSearch.close();
        if (startLogSearch != null) startLogSearch.close();
        if (startBackupSearch != null) startBackupSearch.close();
        if (startServerActions != null) startServerActions.close();
        startLogSearch = null;
        startBackupSearch = null;
        startServerActions = null;
        startBackups.clear();
        startLogEntries = List.of();
        startBackupEntries = List.of();
        startActionEntries = List.of();
        startLogStatus = "";
        startBackupStatus = "";
        startResourceLoading = false;
        startLogLoading = false;
        startBackupLoading = false;
        startBoundSpecial = -1;
        startPublishedSpecial = -1;
        startSpecialRevision++;
        startResourceSearch = null;
        startMarkets.values().forEach(binding -> binding.target().context().invalidate());
        startMarkets.clear();
        if (startGlobalMarket != null) startGlobalMarket.context().invalidate();
        startGlobalMarket = null;
        startResourceEntries = List.of();
        startResourceStatus = "";
        startSearch = new StartMenuWidget.Search("", "", null, "", false);
        setStartSearchListener(null);
        setStartResourceRefresh(null);
        setStartSearchRefresh(StartEntry.Category.UPDATES, null);
        setStartSearchRefresh(StartEntry.Category.LOGS, null);
        setStartSearchRefresh(StartEntry.Category.BACKUPS, null);
        setStartSearching(false);
        setStartResourceProviders(List.of());
        startResourceRevision++;
        startFileRows.clear();
        startBoundResources = -1;
        startPublishedResources = -1;
        if (startFileIndex != null) startFileIndex.close();
        startFileIndex = null;
        startBindings.clear();
        startBoundCatalog = -1;
        startPublishedIndex = -1;
    }

    public void openWorldScreen(ServerModels.ClientServerView instance) {
        serverHost().openWorld(this, instance);
    }

    private void createPopups() {
        createChoicePopup();
        createAddServerPopup();
        createRemoteHostPopup();
        ensureReactorPlanSelectionCardsCreated();
    }

    private void createChoicePopup() {
        PopupWidget.Builder builder = new PopupWidget.Builder("Create").width(180);
        createServerButton = new IconButton.Builder().size(160, 34).label("Server").hint("Create, Install, Or Import A Server").imagePath("server.png").iconSize(32).iconPadding(2).centered(true).onClick(() -> {
            createChoicePopup.hide();
            showServerCreationOptions();
        }).build();
        createNetworkButton = new IconButton.Builder().size(160, 34).label("Network").hint("Create A Proxy And Managed Servers").imagePath("network.png").iconSize(32).iconPadding(2).centered(true).onClick(() -> {
            createChoicePopup.hide();
            List<ServerModels.ClientServerView> selected = serverWidgets(activeContainer.getSelectedWidgets().stream()).toList();
            openNetworkCreationFromSelection(selected);
        }).build();
        builder.addRow("creationTypes", "", createServerButton, createNetworkButton);
        createChoicePopup = builder.build();
        createChoicePopup.hide();
        addDrawableChild(createChoicePopup);
    }

    private void showCreateChoice() {
        updateManagerActionControls();
        createChoicePopup.setX((width - createChoicePopup.getWidth()) / 2);
        createChoicePopup.setY((height - createChoicePopup.getHeight()) / 2);
        createChoicePopup.show();
    }

    private void showServerCreationOptions() {
        Object data = tabs().getActiveTab() == null ? null : tabs().getActiveTab().getData();
        boolean isPanelTab = data instanceof ServerScreenHost.HostView host && host.panel();
        boolean isReStudioTab = "RESTUDIO_MARKER".equals(data);
        addServerPopup.setRowVisibility("createServerRow", !isPanelTab);
        addServerPopup.setRowVisibility("modpackServerRow", !isPanelTab);
        addServerPopup.setRowVisibility("importServerRow", !isReStudioTab && !isPanelTab);
        addServerPopup.setX((width - addServerPopup.getWidth()) / 2);
        addServerPopup.setY((height - addServerPopup.getHeight()) / 2);
        addServerPopup.show();
    }

    private void createAddServerPopup() {
        PopupWidget.Builder builder = new PopupWidget.Builder("Create Server")
            .width(260);

        emptyServerButton = new IconButton.Builder()
            .label(("Create And Customize An Empty Server"))
            .imagePath("create.png")
            .accentType(ThemeManager.getAccent("nice"))
            .onClick(() -> {
                Object data = (tabs().getActiveTab() != null) ? tabs().getActiveTab().getData() : null;
                if ("RESTUDIO_MARKER".equals(data)) {
                    ResourcePoolScreen.openCreate(this, remotelyClient);
                } else if (data instanceof ServerScreenHost.HostView host && host.panel()) {
                    new Notification("Panel Managed", "Create Servers In Your Panel", Notification.Type.WARN);
                } else {
                    serverHost().createServer(this, data instanceof ServerScreenHost.HostView host ? host : null);
                }
                addServerPopup.hide();
            })
            .build();

        modpackServerButton = new IconButton.Builder()
            .label(("Browse For Online Modpacks"))
            .imagePath("download.png")
            .accentType(ThemeManager.getAccent("calm"))
            .enableGradient(true)
            .onClick(() -> {
                addServerPopup.hide();
                openModpackInstallation();
            })
            .build();

        importServerButton = new IconButton.Builder()
            .label(("Import Existing Server"))
            .imagePath("explorer.png")
            .onClick(() -> {
                addServerPopup.hide();
                openImportFileExplorer();
            })
            .build();

        builder.addRow("createServerRow", "", emptyServerButton);
        builder.addRow("modpackServerRow", "", modpackServerButton);
        builder.addRow("importServerRow", "", importServerButton);

        addServerPopup = builder.build();
        addServerPopup.hide();
        addDrawableChild(addServerPopup);
    }

    private void updateManagerActionControls() {
        Object target = tabs().getActiveTab() == null ? null : tabs().getActiveTab().getData();
        applyManagerAction(createNetworkButton, ServerScreenHost.Action.NETWORK_CREATE, target, "Create A Proxy And Managed Servers");
        applyManagerAction(emptyServerButton, ServerScreenHost.Action.CREATE_SERVER, target, "Create And Customize An Empty Server");
        applyManagerAction(modpackServerButton, ServerScreenHost.Action.MODPACK_SERVER, target, "Browse For Online Modpacks");
        applyManagerAction(importServerButton, ServerScreenHost.Action.IMPORT_SERVER, target, "Import Existing Server");
        if (tabs().getPlusButton() != null) {
            applyManagerAction(tabs().getPlusButton(), ServerScreenHost.Action.REMOTE_HOST, target, "Remote Host");
        }
    }

    private void applyManagerAction(AnimatedWidget control, ServerScreenHost.Action action, Object target, String availableHint) {
        if (control == null) {
            return;
        }
        ServerScreenHost.ActionAvailability availability = serverHost().managerAction(action, target);
        control.setActive(availability.available());
        control.setHint(availability.available() ? availableHint : availability.reason());
    }

    private void runManagerAction(ServerScreenHost.Action action, Runnable operation) {
        Object target = tabs().getActiveTab() == null ? null : tabs().getActiveTab().getData();
        ServerScreenHost.ActionAvailability availability = serverHost().managerAction(action, target);
        if (!availability.available()) {
            new Notification("Unavailable", availability.reason(), Notification.Type.WARN);
            return;
        }
        operation.run();
    }

    private record ServerDeletion(ServerModels.ClientServerView server, String id, String name, String backend, String path, boolean hosted) {}

    private void showDeleteServerPopup(ServerModels.ClientServerView server) {
        if (!ensureServerManagerAction(server, ServerScreenHost.Action.DELETE_SERVER, "server.delete")) return;
        showDeleteServersPopup(List.of(server));
    }

    private void showDeleteServersPopup(List<ServerModels.ClientServerView> servers) {
        List<ServerDeletion> selected = servers.stream().distinct()
                .map(server -> new ServerDeletion(server, serverId(server), serverName(server), serverHost().identity(server).backendType(),
                        serverHost().identity(server).path(), isRestudioServer(server))).toList();
        if (selected.isEmpty()) return;
        ServerScreenHost host = serverHost();
        var api = remotelyClient.getApiClient();
        String account = accountKey(host.accountIdentity());
        long generation = callbackGeneration;
        List<AnimatedWidget> entries = new ArrayList<>();
        Map<ServerDeletion, TextInputWidget> confirmations = new LinkedHashMap<>();
        boolean canDelete = selected.stream().allMatch(target -> canServerManagerAction(target.server(), ServerScreenHost.Action.DELETE_SERVER, "server.delete"));
        for (ServerDeletion target : selected) {
            Object iconTarget = iconTarget(target.server());
            Identifier iconId = iconTarget != null ? iconManager.getQuickIconId(iconTarget) : null;
            IconButton entry = new IconButton.Builder().label(target.name()).identifier(iconId != null ? iconId : serverIcon).iconSize(24).size(0, 30).build();
            entry.setActive(false);
            if (iconTarget != null) iconManager.loadIconIdAsync(iconTarget, entry::setIcon);
            entries.add(entry);
            String path = host.identity(target.server()).path();
            if (path != null && !path.isBlank()) entries.add(DeletionPopup.entry(path, "folder.png"));
            if (canDelete && target.hosted()) {
                TextInputWidget confirmation = new TextInputWidget.Builder().placeholder(target.name()).size(300, 20).build();
                entries.add(DeletionPopup.entry("Enter The Server Name To Confirm", "edit.png"));
                entries.add(confirmation);
                confirmations.put(target, confirmation);
            }
        }
        if (canDelete && !confirmations.isEmpty()) {
            entries.add(DeletionPopup.entry("All Server Files And Worlds Will Be Deleted", "explorer.png"));
        }
        List<DeletionPopup.Action> actions = new ArrayList<>();
        BooleanSupplier current = () -> isCurrentCallback(generation) && host == serverHost()
                && api == remotelyClient.getApiClient() && account.equals(accountKey(host.accountIdentity()));
        Consumer<PopupWidget> permanent = popup -> {
            if (!current.getAsBoolean() || !validateServerTargets(selected, true)) return;
            for (Map.Entry<ServerDeletion, TextInputWidget> confirmation : confirmations.entrySet()) {
                if (!confirmation.getKey().name().equals(confirmation.getValue().getText())) {
                    new Notification("Name Does Not Match", "Enter The Name Of " + confirmation.getKey().name() + " Exactly To Delete It", Notification.Type.WARN);
                    return;
                }
            }
            popup.hide();
            deleteServers(selected, 0, true, current);
        };
        if (canDelete) {
            if (confirmations.isEmpty() && host.serverManagerMode() != ServerScreenHost.ServerManagerMode.REACTOR_ONLY) {
                actions.add(DeletionPopup.Action.trash(popup -> {
                    if (!current.getAsBoolean() || !validateServerTargets(selected, true)) return;
                    popup.hide();
                    deleteServers(selected, 0, false, current);
                }));
            }
            actions.add(DeletionPopup.Action.permanent(permanent));
        }
        actions.add(DeletionPopup.Action.hide(popup -> {
            if (!current.getAsBoolean() || !validateServerTargets(selected, false)) return;
            popup.hide();
            for (ServerDeletion target : selected) hideServer(target.server());
        }));
        DeletionPopup.show(this, entries, actions.toArray(DeletionPopup.Action[]::new));
    }

    private boolean validateServerTargets(List<ServerDeletion> selected, boolean deleting) {
        for (ServerDeletion target : selected) {
            ServerModels.ClientServerView known = knownServerView(target.id());
            ServerScreenHost.ServerIdentity identity = serverHost().identity(target.server());
            ServerScreenHost.ServerIdentity knownIdentity = known == null ? identity : serverHost().identity(known);
            if (!target.id().equals(serverId(target.server())) || !target.name().equals(serverName(target.server()))
                    || !target.backend().equals(identity.backendType()) || !target.path().equals(identity.path())
                    || target.hosted() != isRestudioServer(target.server())
                    || known != null && (!target.name().equals(serverName(known)) || !target.backend().equals(knownIdentity.backendType())
                            || !target.path().equals(knownIdentity.path()))) {
                new Notification("Server Changed", "Reopen Deletion Options Before Continuing", Notification.Type.WARN);
                return false;
            }
            if (deleting && !ensureServerManagerAction(target.server(), ServerScreenHost.Action.DELETE_SERVER, "server.delete")) return false;
        }
        return true;
    }

    private void deleteServers(List<ServerDeletion> selected, int index, boolean permanent, BooleanSupplier current) {
        if (index == selected.size() || !current.getAsBoolean()) return;
        ServerDeletion target = selected.get(index);
        if (!validateServerTargets(List.of(target), true)) return;
        deleteServer(target.server(), permanent).whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (failure == null) deleteServers(selected, index + 1, permanent, current);
            else if (selected.size() > 1) new Notification("Deletion Stopped", "Remaining Servers Were Not Deleted", Notification.Type.WARN);
        }));
    }

    private void hideServer(ServerModels.ClientServerView server) {
        playSound(Sound.CLICK);
        long generation = callbackGeneration;
        ServerScreenHost host = serverHost();
        String account = accountKey(host.accountIdentity());
        Async.supply(() -> host.serverAction(server, "hide")).thenCompose(operation -> operation).whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation) || host != serverHost() || !account.equals(accountKey(host.accountIdentity()))) {
                return;
            }
            new Notification(failure == null ? "Server Hidden" : "Hide Failed", failure == null ? serverName(server) : rootMessage(failure),
                    failure == null ? Notification.Type.SUCCESS : Notification.Type.ERROR);
            loadServersForAllTabs();
        }));
    }

    private String serverVersionAndSoftware(ServerModels.ClientServerView server) {
        String version = server.version == null || server.version.isBlank() ? "Unknown Version" : server.version;
        String software = server.loader == null || server.loader.isBlank() ? "Unknown Software" : server.loader;
        return version + "  •  " + software;
    }

    private String serverContextMetadata(ServerModels.ClientServerView server) {
        String backendType = serverHost().identity(server).backendType();
        String backend = backendType == null || backendType.isBlank() ? "Local" : switch (backendType.toUpperCase(Locale.ROOT)) {
            case "RESTUDIO" -> "Reactor";
            case "PTERO" -> "Pterodactyl";
            case "CALAGOPUS" -> "Calagopus";
            case "CRAFTY" -> "Crafty Controller";
            case "SSH" -> "SSH";
            default -> "Local";
        };
        String stateValue = serverHost().state(server).name();
        String state = stateValue.isBlank() ? "Unknown" : stateValue.charAt(0) + stateValue.substring(1).toLowerCase(Locale.ROOT);
        return backend + "  •  " + state;
    }

    private Async<Void> deleteServer(ServerModels.ClientServerView server, boolean permanent) {
        if (!ensureServerManagerAction(server, ServerScreenHost.Action.DELETE_SERVER, "server.delete")) {
            return Async.failed(new IllegalStateException("Server Deletion Is Unavailable"));
        }
        ServerScreenHost host = serverHost();
        var api = remotelyClient.getApiClient();
        String account = accountKey(host.accountIdentity());
        String deletion = account + ":" + serverId(server);
        if (!deletingServers.add(deletion)) return Async.failed(new IllegalStateException("Server Deletion Is Already In Progress"));
        Async<Void> result = Async.pending();
        playSound(Sound.DELETE);
        Notification notification = new Notification.Builder()
                .message(permanent ? "Deleting Server" : "Moving Server To Trash")
                .description(serverName(server))
                .type(Notification.Type.INFO)
                .loading(true)
                .autoSlideOut(false)
                .build();
        long generation = callbackGeneration;
        Async.supply(() -> host.serverAction(server, permanent ? "delete" : "trash")).thenCompose(operation -> operation).whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            deletingServers.remove(deletion);
            if (host != serverHost() || api != remotelyClient.getApiClient() || !account.equals(accountKey(host.accountIdentity()))) {
                notification.update().loading(false).autoSlideOut(true).commit();
                result.fail(new IllegalStateException("Server Account Changed"));
                return;
            }
            if (failure != null) {
                notification.update().message(permanent ? "Delete Failed" : "Move Failed").description(rootMessage(failure)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                result.fail(failure);
                return;
            }
            notification.update().message(permanent ? "Server Deleted" : "Moved To Trash").description(serverName(server)).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            boolean managed = isRestudioServer(server);
            if (managed) {
                restudioFetchGeneration++;
                restudioFetchInFlight = false;
                restudioFetchAccount = "";
                restudioInstances.removeIf(value -> serverId(server).equals(serverId(value)));
                restudioServerViews.values().removeIf(value -> serverId(server).equals(serverId(value)));
                remotelyClient.cacheReStudioServerViews(restudioServerViews);
                if (isActiveScreen()) fetchReStudioServers();
            }
            if (isCurrentCallback(generation) || managed && isActiveScreen()) loadServersForAllTabs();
            result.complete(null);
        }));
        return result;
    }

    private void openReactorPlanSelection() {
        ensureReactorPlanSelectionCardsCreated();
        hideReactorPlanSelection();
        selectedReactorPlanName = null;
        reactorPlans.clear();

        List<ServerModels.Plan> fallbackPlans = List.of(
                createFallbackPlan("Relay", 4096, 200, 700),
                createFallbackPlan("Refine", 6144, 300, 1100),
                createFallbackPlan("Revenge", 8192, 400, 1400)
        );

        long generation = callbackGeneration;
        serverHost().reactorPlans().whenComplete((plans, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            List<ServerModels.Plan> source = failure != null || plans == null || plans.isEmpty() ? fallbackPlans : plans.stream().map(ServerManagerScreen::toPlan).toList();
            source.stream()
                    .filter(Objects::nonNull)
                    .filter(plan -> !"custom".equalsIgnoreCase(plan.name))
                    .sorted(Comparator.comparingLong(plan -> plan.priceCents))
                    .limit(3)
                    .forEach(reactorPlans::add);

            if (reactorPlans.isEmpty()) {
                reactorPlans.addAll(fallbackPlans);
            }

            rebuildReactorPlanSelectionCards();
            showReactorPlanSelection();
        }));
    }

    private static ServerModels.Plan toPlan(ServerScreenHost.PlanView view) {
        ServerModels.Plan plan = new ServerModels.Plan();
        plan.id = view.id();
        plan.name = view.name();
        plan.memoryMb = view.memoryMb();
        plan.diskMb = view.diskMb();
        plan.cpuPercent = view.cpuPercent();
        plan.priceCents = view.priceCents();
        return plan;
    }

    private void rebuildReactorPlanSelectionCards() {
        ensureReactorPlanSelectionCardsCreated();
        if (reactorPlanCards.isEmpty()) {
            return;
        }

        List<ServerModels.Plan> items = reactorPlans.isEmpty() ? List.of(
                createFallbackPlan("Relay", 4096, 200, 700),
                createFallbackPlan("Refine", 6144, 300, 1100),
                createFallbackPlan("Revenge", 8192, 400, 1400)
        ) : reactorPlans;

        for (int i = 0; i < reactorPlanCards.size(); i++) {
            ReactorPlanWidget card = reactorPlanCards.get(i);
            if (i >= items.size()) {
                card.setVisible(false);
                card.setActive(false);
                continue;
            }
            ServerModels.Plan plan = items.get(i);
            card.setVisible(true);
            card.setActive(true);
            card.setContent(
                    plan.name == null || plan.name.isBlank() ? "Reactor Plan" : plan.name,
                    formatPlanFriendlyDescription(plan),
                    formatPlanSpecs(plan),
                    formatPlanPrice(plan.priceCents)
            );
            if (selectedReactorPlanName != null && selectedReactorPlanName.equalsIgnoreCase(plan.name)) {
                card.accentType = ThemeManager.getAccent("nice");
            } else {
                card.accentType = ThemeManager.getDefaultAccent();
            }
        }
        positionReactorPlanSelectionCards();
    }

    private void onReactorPlanSelected(ServerModels.Plan plan) {
        if (plan == null || plan.name == null || plan.name.isBlank()) {
            return;
        }
        selectedReactorPlanName = plan.name;
        for (int i = 0; i < reactorPlanCards.size(); i++) {
            ReactorPlanWidget card = reactorPlanCards.get(i);
            ServerModels.Plan candidate = i < reactorPlans.size() ? reactorPlans.get(i) : null;
            if (candidate != null && candidate.name != null && candidate.name.equalsIgnoreCase(selectedReactorPlanName)) {
                card.accentType = ThemeManager.getAccent("nice");
                continue;
            }
            card.accentType = ThemeManager.getDefaultAccent();
        }
        openReactorConfigWithPlan();
    }

    private void openReactorConfigWithPlan() {
        if (selectedReactorPlanName == null || selectedReactorPlanName.isBlank()) {
            new Notification("Select Plan", Notification.Type.WARN);
            return;
        }
        hideReactorPlanSelection();
        client.setScreen(new ServerConfigurationScreen(this, null, null, remotelyClient, true, selectedReactorPlanName));
    }

    private void hideReactorPlanSelection() {
        reactorPlanSelectionVisible = false;
        for (ReactorPlanWidget card : reactorPlanCards) {
            card.setVisible(false);
            card.setActive(false);
        }
        loadServersForCurrentTab();
    }

    private void showReactorPlanSelection() {
        if (reactorPlanCards.isEmpty()) {
            return;
        }
        reactorPlanSelectionVisible = true;
        positionReactorPlanSelectionCards();
        for (int i = 0; i < reactorPlanCards.size(); i++) {
            ReactorPlanWidget card = reactorPlanCards.get(i);
            boolean hasPlan = i < reactorPlans.size();
            card.setVisible(hasPlan);
            card.setActive(hasPlan);
        }
        updateNoServersOverlayVisibility(false);
    }

    private void positionReactorPlanSelectionCards() {
        if (!reactorPlanSelectionVisible || reactorPlanCards.isEmpty()) {
            return;
        }
        int targetCardWidth = resolveReactorPlanCardWidth();
        int targetCardHeight = resolveReactorPlanCardHeight(targetCardWidth);
        for (ReactorPlanWidget card : reactorPlanCards) {
            card.setWidth(targetCardWidth);
            card.setHeight(targetCardHeight);
        }

        ReactorPlanWidget center = reactorPlanCards.get(1);
        int centerCardWidth = center.getWidth();
        int centerCardHeight = center.getHeight();
        int centerX = (width - centerCardWidth) / 2;
        int maxBottom = Math.max(80, height - 42);
        int centerY = Math.max(22, (maxBottom - centerCardHeight) / 2);
        center.setPosition(centerX, centerY);

        ReactorPlanWidget left = reactorPlanCards.getFirst();
        left.setPosition(Math.max(REACTOR_PLAN_CARD_SIDE_MARGIN, centerX - left.getWidth() - REACTOR_PLAN_CARD_GAP), centerY);

        ReactorPlanWidget right = reactorPlanCards.get(2);
        right.setPosition(Math.min(width - right.getWidth() - REACTOR_PLAN_CARD_SIDE_MARGIN, centerX + centerCardWidth + REACTOR_PLAN_CARD_GAP), centerY);
    }

    private int resolveReactorPlanCardWidth() {
        int available = width - (REACTOR_PLAN_CARD_SIDE_MARGIN * 2) - (REACTOR_PLAN_CARD_GAP * 2);
        int byLayout = available / 3;
        int lowerBound = Math.max(184, Math.round(width * 0.16f));
        int upperBound = Math.max(212, Math.round(width * 0.24f));
        return Math.clamp(byLayout, lowerBound, upperBound);
    }

    private int resolveReactorPlanCardHeight(int cardWidth) {
        if (!reactorPlanCards.isEmpty()) {
            return reactorPlanCards.getFirst().getPreferredHeight();
        }
        return Math.max(1, Math.round(cardWidth * 0.45f));
    }

    private ServerModels.Plan createFallbackPlan(String name, int memoryMb, int cpuPercent, long priceCents) {
        ServerModels.Plan plan = new ServerModels.Plan();
        plan.name = name;
        plan.memoryMb = memoryMb;
        plan.diskMb = 102400;
        plan.cpuPercent = cpuPercent;
        plan.databases = 5;
        plan.backups = 5;
        plan.allocations = 5;
        plan.priceCents = priceCents;
        return plan;
    }

    private String formatPlanSpecs(ServerModels.Plan plan) {
        return formatStorage(plan.memoryMb) + " RAM • " + formatStorage(plan.diskMb) + " Disk • " + formatCpuThreads(plan.cpuPercent) + " Threads";
    }

    private String formatPlanFriendlyDescription(ServerModels.Plan plan) {
        return switch (plan.name) {
            case "Relay" -> "Perfect For 10-15 Players";
            case "Refine" -> "Realistic For Anything";
            case "Revenge" -> "As Fast As It Can Be";
            default -> "";
        };
    }

    private String formatStorage(int valueMb) {
        if (valueMb <= 0) {
            return "0 MB";
        }
        if (valueMb >= 1024) {
            double gb = valueMb / 1024.0;
            long rounded = Math.round(gb);
            if (Math.abs(gb - rounded) < 0.05) {
                return rounded + " GB";
            }
            return String.format(Locale.US, "%.1f GB", gb);
        }
        return valueMb + " MB";
    }

    private String formatCpuThreads(int cpuPercent) {
        int percent = Math.max(0, cpuPercent);
        int threads = Math.max(1, (int) Math.round(percent / 100.0));
        return String.valueOf(threads);
    }

    private String formatPlanPrice(long priceCents) {
        if (priceCents <= 0) {
            return "$0.00/mo";
        }
        return String.format(Locale.US, "$%.2f/mo", priceCents / 100.0);
    }

    private void createRemoteHostPopup() {
        PopupWidget.Builder types = new PopupWidget.Builder("Choose Host Type").width(300).setResizable(false);
        for (RemoteHostType type : RemoteHostType.values()) {
            MountableButtonWidget choice = new MountableButtonWidget.Builder(type.label)
                    .description(type.description)
                    .onClick(() -> {
                        if (remoteHostType != type) {
                            resetRemoteHostFields();
                            remoteHostType = type;
                        }
                        remoteHostTypePopup.hide();
                        openRemoteHostPopup(null);
                    })
                    .build();
            types.addRow("", choice);
        }
        remoteHostTypePopup = types.build();
        remoteHostTypePopup.hide();
        addDrawableChild(remoteHostTypePopup);

        PopupWidget.Builder builder = new PopupWidget.Builder("Connect Remote Host").onClose(this::closeRemoteHostPopup)
                .width(340).setResizable(false).setAntiOutOfBound(true).setBoundOffset(header().headerSize)
                .addTitleAction("Test & Add", this::onConfirmRemoteHost, PopupWidget.TitleActionRole.PRIMARY)
                .addTitleAction("Back", this::showRemoteHostTypes, PopupWidget.TitleActionRole.SECONDARY);

        remoteHostNameInput = new TextInputWidget.Builder().size(18, 18).build();
        builder.addRow("Host Name", remoteHostNameInput);
        remoteHostUserInput = new TextInputWidget.Builder().size(18, 18).text("root").build();
        builder.addRow(ROW_REMOTE_HOST_USER, "User Name", remoteHostUserInput);
        remoteHostIpInput = new TextInputWidget.Builder().size(18, 18).build();
        builder.addRow(ROW_REMOTE_HOST_IP, "IP Or Domain", remoteHostIpInput);
        remoteHostPortInput = new TextInputWidget.Builder().size(18, 18).text("22").build();
        builder.addRow(ROW_REMOTE_HOST_PORT, "Port", remoteHostPortInput);
        remoteHostAuthModeSwitch = new TabSwitchWidget.Builder().options(List.of("Password", "SSH Key")).currentIndex(0)
                .onChange(this::updateRemoteHostAdvancedVisibility).build();
        builder.addRow(ROW_REMOTE_HOST_AUTH_MODE, "Auth Mode", remoteHostAuthModeSwitch);
        remoteHostPasswordInput = new TextInputWidget.Builder().size(18, 18).password(true).build();
        builder.addRow(ROW_REMOTE_HOST_PASSWORD, "Password", remoteHostPasswordInput);
        remoteHostSftpPasswordInput = new TextInputWidget.Builder().size(18, 18).password(true).build();
        builder.addRow(ROW_REMOTE_HOST_SFTP_PASSWORD, "Panel Password", remoteHostSftpPasswordInput);
        remoteHostKeyPathInput = new TextInputWidget.Builder().size(18, 18).placeholder("Leave Empty For Auto Discovery").build();
        builder.addRow(ROW_REMOTE_HOST_KEY_PATH, "Key Path", remoteHostKeyPathInput);
        remoteHostKeyPassphraseInput = new TextInputWidget.Builder().size(18, 18).password(true).build();
        builder.addRow(ROW_REMOTE_HOST_PASSPHRASE, "Passphrase", remoteHostKeyPassphraseInput);
        remoteHostRegistryPathInput = new TextInputWidget.Builder().size(18, 18).placeholder("Optional File Path For Multiple Users").build();
        builder.addRow(ROW_REMOTE_HOST_REGISTRY_PATH, "Shared Registry File", remoteHostRegistryPathInput);

        remoteHostPopup = builder.build();
        remoteHostPopup.hide();
        addDrawableChild(remoteHostPopup);

        remoteHostNameInput.addOnEnter(w -> remoteHostPopup.setFocusedWidget(isPanelHostSelected() ? remoteHostIpInput : remoteHostUserInput));
        remoteHostUserInput.addOnEnter(w -> remoteHostPopup.setFocusedWidget(remoteHostIpInput));
        remoteHostIpInput.addOnEnter(w -> remoteHostPopup.setFocusedWidget(isPanelHostSelected() ? remoteHostPasswordInput : remoteHostPortInput));
        remoteHostPortInput.addOnEnter(w -> remoteHostPopup.setFocusedWidget(remoteHostAuthModeSwitch.getCurrentIndex() == 1 ? remoteHostKeyPathInput : remoteHostPasswordInput));
        remoteHostPasswordInput.addOnEnter(w -> {
            if (remoteHostType == RemoteHostType.CRAFTY) onConfirmRemoteHost();
            else remoteHostPopup.setFocusedWidget(isPanelHostSelected() ? remoteHostSftpPasswordInput : remoteHostRegistryPathInput);
        });
        remoteHostSftpPasswordInput.addOnEnter(w -> onConfirmRemoteHost());
        remoteHostKeyPathInput.addOnEnter(w -> remoteHostPopup.setFocusedWidget(remoteHostKeyPassphraseInput));
        remoteHostKeyPassphraseInput.addOnEnter(w -> remoteHostPopup.setFocusedWidget(remoteHostRegistryPathInput));
        remoteHostRegistryPathInput.addOnEnter(w -> onConfirmRemoteHost());
    }

    private void showRemoteHostTypes() {
        remoteHostPopup.hide();
        remoteHostTypePopup.centerOnOwner();
        remoteHostTypePopup.show();
    }

    private void resetRemoteHostFields() {
        remoteHostFormGeneration++;
        remoteHostNameInput.setText("");
        remoteHostUserInput.setText("root");
        remoteHostIpInput.setText("");
        remoteHostPortInput.setText("22");
        remoteHostPasswordInput.setText("");
        remoteHostSftpPasswordInput.setText("");
        remoteHostAuthModeSwitch.setCurrentIndex(0);
        remoteHostKeyPathInput.setText("");
        remoteHostKeyPassphraseInput.setText("");
        remoteHostRegistryPathInput.setText("");
    }

    private void connectRemoteHostAsync(ServerScreenHost.HostView hostInfo, Runnable onSuccess, Runnable onFailure) {
        long generation = callbackGeneration;
        serverHost().hostAction(hostInfo, "connect").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure == null) {
                if (onSuccess != null) onSuccess.run();
            } else if (onFailure != null) {
                onFailure.run();
            }
        }));
    }

    private void clearRestudioModels() {
        restudioInstances.clear();
        restudioServerViews.clear();
        restudioInstancesAccount = "";
        remotelyClient.cacheReStudioServerViews(restudioServerViews);
    }

    private void refreshReStudioTabs() {
        if (!isActiveScreen() || tabsManager == null) {
            return;
        }
        for (TabsManager.Tab tab : tabs().getTabs()) {
            if ("RESTUDIO_MARKER".equals(tab.getData())) {
                loadServersForTab(tab);
            }
        }
    }

    private void testRemoteHostAsync(ServerScreenHost.HostView hostInfo, Notification notification, Runnable onSuccess, Runnable onFailure) {
        long generation = callbackGeneration;
        serverHost().hostAction(hostInfo, "test").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure == null) {
                notification.update().description("Connecting... 100%").progress(100, 100).commit();
                notification.update().message("Host Ready").description("Connection ok").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
                if (onSuccess != null) onSuccess.run();
            } else {
                notification.update().message("Error While Testing Host").description(rootMessage(failure)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                if (onFailure != null) onFailure.run();
            }
        }));
    }

    private void openRemoteHostPopup(TabsManager.Tab editTab) {
        ServerScreenHost.HostView host = editTab != null && editTab.getData() instanceof ServerScreenHost.HostView value ? value : null;
        if (host != null) {
            RemoteHostType type;
            try {
                type = RemoteHostType.fromId(host.type());
            } catch (IllegalArgumentException error) {
                new Notification("Host Type Unavailable", error.getMessage(), Notification.Type.ERROR);
                return;
            }
            resetRemoteHostFields();
            remoteHostType = type;
            remoteHostNameInput.setText(host.name());
            remoteHostUserInput.setText(host.user().isBlank() ? "root" : host.user());
            remoteHostIpInput.setText(host.address());
            remoteHostPortInput.setText(String.valueOf(host.port()));
            remoteHostAuthModeSwitch.setCurrentIndex("KEY".equalsIgnoreCase(host.authMode()) ? 1 : 0);
            remoteHostKeyPathInput.setText(host.keyPath());
            remoteHostRegistryPathInput.setText(host.registryPath());
        }
        remoteHostEditTab = editTab;
        remoteHostTypePopup.hide();
        remoteHostPopup.setTitle((host == null ? "Connect " : "Edit ") + remoteHostType.label + " Host");
        boolean editing = host != null;
        if (remoteHostEditActions != editing) {
            remoteHostEditActions = editing;
            remoteHostPopup.clearTitleActions();
            remoteHostPopup.addTitleAction(editing ? "Save" : "Test & Add", this::onConfirmRemoteHost, PopupWidget.TitleActionRole.PRIMARY);
            if (editing) {
                remoteHostPopup.addTitleAction("Delete", this::onDeleteRemoteHost, PopupWidget.TitleActionRole.DESTRUCTIVE);
            } else {
                remoteHostPopup.addTitleAction("Back", this::showRemoteHostTypes, PopupWidget.TitleActionRole.SECONDARY);
            }
        }
        for (PopupWidget.PopupRow row : remoteHostPopup.getRows()) {
            if (ROW_REMOTE_HOST_IP.equals(row.id)) row.setLabel(remoteHostType.panel ? "Panel URL" : "IP Or Domain");
            if (ROW_REMOTE_HOST_PASSWORD.equals(row.id)) row.setLabel(remoteHostType == RemoteHostType.CRAFTY ? "API Token" : remoteHostType.panel ? "API Key" : "Password");
        }
        updateRemoteHostAdvancedVisibility();
        remoteHostPopup.centerOnOwner();
        remoteHostPopup.show();
    }

    private void onConfirmRemoteHost() {
        TabsManager.Tab editTab = remoteHostEditTab;
        boolean isEditing = editTab != null && editTab.getData() instanceof ServerScreenHost.HostView;
        boolean isPanel = isPanelHostSelected();
        int port;
        try {
            port = isPanel ? 443 : Integer.parseInt(remoteHostPortInput.getText());
        } catch (NumberFormatException exception) {
            new Notification("Error", "Port Must Be A Valid Number", Notification.Type.ERROR);
            return;
        }
        String type = remoteHostType.id;
        String authMode = isPanel ? "PASSWORD" : (remoteHostAuthModeSwitch.getCurrentIndex() == 1 ? "KEY" : "PASSWORD");
        String address = remoteHostIpInput.getText();
        String name = remoteHostNameInput.getText();
        if (name == null || name.isBlank() || address == null || address.isBlank()) {
            new Notification("Error", "Host Name And Address Are Required", Notification.Type.ERROR);
            return;
        }
        String id = isEditing && editTab.getData() instanceof ServerScreenHost.HostView host ? host.id() : "";
        ServerScreenHost.RemoteHostDraft draft = new ServerScreenHost.RemoteHostDraft(id, name, isPanel ? "" : remoteHostUserInput.getText(), address, port, type, authMode,
                isPanel ? remoteHostSftpPasswordInput.getText() : remoteHostPasswordInput.getText(), isPanel ? remoteHostPasswordInput.getText() : "",
                remoteHostKeyPathInput.getText(), remoteHostKeyPassphraseInput.getText(), remoteHostRegistryPathInput.getText());
        Notification notification = new Notification.Builder().message(isEditing ? "Saving Host" : "Testing Host").description("Connecting... 0%").type(Notification.Type.INFO).loading(true).progress(0, 100).autoSlideOut(false).build();
        Async<ServerScreenHost.HostView> save = isEditing ? serverHost().saveRemoteHost(draft) : serverHost().testAndSaveRemoteHost(draft);
        long generation = callbackGeneration;
        long formGeneration = remoteHostFormGeneration;
        save.whenComplete((host, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure != null) {
                notification.update().message("Host Failed").description(rootMessage(failure)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            if (!isEditing) {
                notification.update().description("Connecting... 100%").progress(100, 100).commit();
                notification.update().message("Host Ready").description(host.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
                addHostTab(host);
                if (formGeneration == remoteHostFormGeneration) closeRemoteHostPopup();
                return;
            }
            notification.update().message("Host Ready").description(host.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            if (tabs().getTabs().contains(editTab)) {
                editTab.setName(host.name());
                editTab.setData(host);
            }
            if (formGeneration == remoteHostFormGeneration) closeRemoteHostPopup();
        }));
    }

    private void updateRemoteHostAdvancedVisibility() {
        boolean usePanel = isPanelHostSelected();
        boolean useSftp = usePanel && remoteHostType != RemoteHostType.CRAFTY;
        boolean useKey = !usePanel && remoteHostAuthModeSwitch != null && remoteHostAuthModeSwitch.getCurrentIndex() == 1;
        if (remoteHostPopup != null) {
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_USER, !usePanel);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_IP, true);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_PORT, !usePanel);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_PASSWORD, usePanel || !useKey);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_AUTH_MODE, !usePanel);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_SFTP_PASSWORD, useSftp);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_KEY_PATH, useKey);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_PASSPHRASE, useKey);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_REGISTRY_PATH, !usePanel);
        }
        if (remoteHostUserInput != null) {
            remoteHostUserInput.setActive(!usePanel);
            remoteHostUserInput.setVisible(!usePanel);
        }
        if (remoteHostPortInput != null) {
            remoteHostPortInput.setActive(!usePanel);
            remoteHostPortInput.setVisible(!usePanel);
        }
        if (remoteHostPasswordInput != null) {
            remoteHostPasswordInput.setActive(usePanel || !useKey);
            remoteHostPasswordInput.setVisible(usePanel || !useKey);
        }
        if (remoteHostSftpPasswordInput != null) {
            remoteHostSftpPasswordInput.setActive(useSftp);
            remoteHostSftpPasswordInput.setVisible(useSftp);
        }
        if (remoteHostKeyPathInput != null) {
            remoteHostKeyPathInput.setActive(useKey);
            remoteHostKeyPathInput.setVisible(useKey);
        }
        if (remoteHostKeyPassphraseInput != null) {
            remoteHostKeyPassphraseInput.setActive(useKey);
            remoteHostKeyPassphraseInput.setVisible(useKey);
        }
        if (remoteHostRegistryPathInput != null) {
            remoteHostRegistryPathInput.setActive(!usePanel);
            remoteHostRegistryPathInput.setVisible(!usePanel);
        }
        if (remoteHostAuthModeSwitch != null) {
            remoteHostAuthModeSwitch.setActive(!usePanel);
            remoteHostAuthModeSwitch.setVisible(!usePanel);
        }
    }

    private boolean isPanelHostSelected() {
        return remoteHostType.panel;
    }

    private void onDeleteRemoteHost() {
        TabsManager.Tab editTab = remoteHostEditTab;
        if (editTab == null || !(editTab.getData() instanceof ServerScreenHost.HostView host)) return;
        long generation = callbackGeneration;
        long formGeneration = remoteHostFormGeneration;
        serverHost().hostAction(host, "delete").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure != null) {
                new Notification("Host Delete Failed", rootMessage(failure), Notification.Type.ERROR);
                return;
            }
            int tabIndex = tabs().getTabs().indexOf(editTab);
            if (tabIndex >= 0) {
                boolean wasActive = tabs().getActiveTab() == editTab;
                tabs().removeTab(tabIndex);
                if (wasActive) {
                    tabs().setActiveTab(0);
                }
            }
            if (formGeneration == remoteHostFormGeneration) closeRemoteHostPopup();
        }));
    }

    private void openServerScreen(ServerModels.ClientServerView info) {
        if (restudioServerViews.containsValue(info)) {
            serverHost().recordRecentRestudioServer(serverId(info), serverId(info), serverName(info));
        }
        serverHost().mergeServerScreen(this, info, false);
    }

    private void openRecentRestudioItem(RemotelyRecentItem item) {
        if (!serverHost().openRecentRestudioItem(this, item)) {
            serverHost().removeRecentRestudioItem(item);
            new Notification("Recent Item Unavailable", "The Saved ReStudio Item Was Removed", Notification.Type.ERROR);
        }
    }

    public static void openServerScreen(String path) {
        if (path == null || path.isBlank() || RemotelyClient.INSTANCE == null) {
            return;
        }
        ServerScreenHost host = RemotelyClient.INSTANCE.getHost().serverScreenHost(RemotelyClient.INSTANCE);
        if (host.openRecentRestudioPath(ScreenManager.currentScreen, path)) {
            return;
        }
        host.openServerPath(ScreenManager.currentScreen, path);
    }

    private void closeRemoteHostPopup() {
        remoteHostFormGeneration++;
        remoteHostEditTab = null;
        if (remoteHostPopup != null) remoteHostPopup.hide();
        if (remoteHostTypePopup != null) remoteHostTypePopup.hide();
    }

    private void openImportFileExplorer() {
        Object data = (tabs().getActiveTab() != null) ? tabs().getActiveTab().getData() : null;
        ServerModels.ClientServerView server = currentSelectedServerInstances().stream().findFirst().orElse(null);
        ServerScreenHost.HostView context = data instanceof ServerScreenHost.HostView value ? value : null;
        ServerScreenHost.HostView host = serverHost().resolveRemoteHost(server, context);
        serverHost().openImportExplorer(this, host, server);
    }

    private void openModpackInstallation() {
        Object data = (tabs().getActiveTab() != null) ? tabs().getActiveTab().getData() : null;
        ServerScreenHost.HostView host = data instanceof ServerScreenHost.HostView value ? value : null;
        serverHost().openModpackBrowser(this, host, "RESTUDIO_MARKER".equals(data));
    }

    @Override
    public void removed() {
        resetStartSearch();
        if (startSettingsListening) {
            ServerSettingsRegistry.getInstance().removeListener(startSettingsChanged);
            startSettingsListening = false;
        }
        reactiveRefreshEnabled = false;
        serverRefreshQueued = false;
        contextMenuRequestGeneration++;
        restudioFetchGeneration++;
        restudioFetchInFlight = false;
        restudioFetchAccount = "";
        remoteHostSelectionToken++;
        callbackGeneration++;
        networkOperationInFlight = false;
        pteroStatePollInFlight = false;
        pendingNetworkMembershipInstances.clear();
        pendingServerViews.clear();
        clearRestudioModels();
        if (instanceChangeListenerRegistered) {
            serverHost().removeInstanceChangeListener(instanceChangeListener);
            serverHost().removeNetworkChangeListener(networkChangeListener);
            serverHost().removeRuntimeChangeListener(runtimeChangeListener);
            instanceChangeListenerRegistered = false;
        }
        if (authStateListenerRegistered) {
            serverHost().removeAuthStateListener(authStateListener);
            authStateListenerRegistered = false;
        }
        remotelyClient.saveTabIndex(tabs().getActiveTabIndex());
        if (taskbarHelper != null) {
            taskbarHelper.detach();
        }
        super.removed();
    }

    private long lastReloadTime = 0;

    @Override
    public void onSuspended() {
        startNavigationGeneration++;
        super.onSuspended();
    }

    @Override
    public void onDisplayed() {
        super.onDisplayed();
        if (Config.desktopMode) addDesktopControls();
        playSound(Sound.SERVERMANAGER);
        applyDesktopContentBounds();
        if (System.currentTimeMillis() - lastReloadTime > 5000) {
            reloadInstancesSmartly();
            lastReloadTime = System.currentTimeMillis();
        }
        ensureCurrentRestudioInventory();
        loadServersForCurrentTab();
    }

    @Override
    public void updatePositions() {
        super.updatePositions();
        relayoutDesktopTabs();
        applyDesktopContentBounds();
        positionReactorPlanSelectionCards();
        if (noServersOverlay != null) {
            DesktopBounds.Bounds contentBounds = desktopBounds().content();
            noServersOverlay.setPosition(contentBounds.x(), contentBounds.y());
            noServersOverlay.setSize(contentBounds.width(), contentBounds.height());
            noServersOverlay.updateWidgetPositions();
            reactorsNoServersIcon.setX(width / 2 - (reactorsNoServersIcon.getWidth() / 2));
            reactorsNoServersIcon.setY(height / 4);
            reactorInfo.setX(width / 2 - (reactorInfo.getWidth() / 2));
            reactorInfo.setY(reactorsNoServersIcon.getY() + reactorsNoServersIcon.getContentHeight() + 5);
            localNoServersIcon.setX(width / 2 - (localNoServersIcon.getWidth() / 2));
            localNoServersIcon.setY(height / 4);
            pteroNoServersIcon.setX(width / 2 - (pteroNoServersIcon.getWidth() / 2));
            pteroNoServersIcon.setY(height / 4);
            pteroInfo.setX(width / 2 - (pteroInfo.getWidth() / 2));
            pteroInfo.setY(pteroNoServersIcon.getY() + pteroNoServersIcon.getHeight() + (12 * 5));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (isDesktopStartOpen() && (startCatalogDirty || !startAuthority.equals(currentStartAuthority()) || startApi != remotelyClient.getApiClient())) refreshStartEntries();
        if (networkRefreshRequired && isActiveScreen()) refreshNetworks();
        Object data = tabs().getActiveTab() != null ? tabs().getActiveTab().getData() : null;
        if (!(data instanceof ServerScreenHost.HostView) && !"RESTUDIO_MARKER".equals(data)) {
            pollPersistentLocalServerStates(getCurrentServers(), false);
        } else if (data instanceof ServerScreenHost.HostView host && host.panel()) {
            pollPteroServerStates(host, false);
        }
    }

    private void pollPteroServerStates(ServerScreenHost.HostView host, boolean force) {
        if (host == null) return;
        long now = System.currentTimeMillis();
        if (pteroStatePollInFlight || (!force && now - lastPteroStatePollMs < PTERO_STATE_POLL_MS)) {
            return;
        }
        lastPteroStatePollMs = now;
        pteroStatePollInFlight = true;
        TabsManager.Tab activeTab = tabs().getActiveTab();
        long generation = callbackGeneration;
        serverHost().refreshRemoteInstance(host).whenComplete((v, e) -> ScreenManager.getInstance().execute(() -> {
            pteroStatePollInFlight = false;
            if (isCurrentCallback(generation) && tabs().getActiveTab() == activeTab && activeTab != null && activeTab.getData() == host) {
                loadServersForTab(activeTab);
            }
        }));
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (reactorPlanSelectionVisible && event.key() == ReKey.ESCAPE) {
            hideReactorPlanSelection();
            return true;
        }
        if (reactorPlanSelectionVisible) {
            return true;
        }
        if (event.key() == ReKey.R && event.modifiers().control()) {
            reloadInstancesSmartly();
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        if (event.key() == ReKey.ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        ReStudioInboxButton.updateAccountBadge(userButton);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (activeContainer != null) {
            DesktopLayout layout = activeContainer.getLayout() instanceof DesktopLayout desktop ? desktop : null;
            boolean iconGesture = layout != null && layout.startsIconGesture(activeContainer, event);
            if (!iconGesture) {
                groupWidgets(activeContainer.getWidgets().stream()).filter(DesktopGroupWidget::isExpanded)
                        .filter(group -> layout == null ? !group.isMouseOver(event.x(), event.y()) : !layout.isScreenMouseOver(activeContainer, group, event))
                        .forEach(group -> group.setExpanded(false));
            }
        }
        if (isMouseOverServerManagerContextMenu(event.x(), event.y())) {
            serverManagerContextMenuPressed = true;
            super.mouseClicked(event);
            return true;
        }
        if (reactorPlanSelectionVisible) {
            for (int i = reactorPlanCards.size() - 1; i >= 0; i--) {
                ReactorPlanWidget card = reactorPlanCards.get(i);
                if (!card.isVisible() || !card.isActive()) {
                    continue;
                }
                if (card.mouseClicked(event.retarget(card, event.x(), event.y()))) {
                    return true;
                }
            }
            return true;
        }
        if (reactorInfo.isVisible() && reactorInfo.isHovered()) {
            return reactorInfo.mouseClicked(event.retarget(reactorInfo, event.x(), event.y()));
        }
        if (pteroInfo.isVisible() && pteroInfo.isHovered()) {
            return pteroInfo.mouseClicked(event.retarget(pteroInfo, event.x(), event.y()));
        }
        return super.mouseClicked(event);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (serverManagerContextMenuPressed) {
            serverManagerContextMenuPressed = false;
            return true;
        }
        if (reactorPlanSelectionVisible) {
            for (int i = reactorPlanCards.size() - 1; i >= 0; i--) {
                ReactorPlanWidget card = reactorPlanCards.get(i);
                if (!card.isVisible() || !card.isActive()) {
                    continue;
                }
                if (card.mouseReleased(event.retarget(card, event.x(), event.y()))) {
                    return true;
                }
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (reactorPlanSelectionVisible) {
            for (int i = reactorPlanCards.size() - 1; i >= 0; i--) {
                ReactorPlanWidget card = reactorPlanCards.get(i);
                if (!card.isVisible() || !card.isActive()) {
                    continue;
                }
                if (card.mouseDragged(event.retarget(card, event.x(), event.y(), event.deltaX(), event.deltaY()))) {
                    return true;
                }
            }
            return true;
        }
        return super.mouseDragged(event);
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        if (reactorPlanSelectionVisible) {
            for (int i = reactorPlanCards.size() - 1; i >= 0; i--) {
                ReactorPlanWidget card = reactorPlanCards.get(i);
                if (!card.isVisible() || !card.isActive()) {
                    continue;
                }
                if (card.mouseScrolled(event.retarget(card, event.x(), event.y()))) {
                    return true;
                }
            }
            return true;
        }
        return super.mouseScrolled(event);
    }

    @Override
    public void mouseMoved(ReMouseEvent event) {
        if (reactorPlanSelectionVisible) {
            for (ReactorPlanWidget card : reactorPlanCards) {
                if (!card.isVisible() || !card.isActive()) {
                    continue;
                }
                card.mouseMoved(event.retarget(card, event.x(), event.y()));
            }
        }
        super.mouseMoved(event);
    }

    public List<ServerModels.ClientServerView> getRestudioInstances() {
        ServerScreenHost.AccountIdentity account = serverHost().accountIdentity();
        if (account == null || !account.authenticated() || !accountKey(account).equals(restudioInstancesAccount)) {
            return List.of();
        }
        return restudioInstances;
    }

    @Override
    public void close() {
        if (parent == null && !remotelyClient.getHost().shouldCloseRootScreen()) {
            return;
        }
        remotelyClient.getHost().openParentScreen(this, parent);
    }

    private boolean authenticated() {
        return serverHost().authenticated();
    }

    private static String accountKey(ServerScreenHost.AccountIdentity account) {
        if (account == null || !account.authenticated()) {
            return "";
        }
        if (!account.subjectId().isBlank()) {
            return "subject:" + account.subjectId();
        }
        return account.displayName().isBlank() ? "" : "name:" + account.displayName();
    }

    private boolean hasCurrentRestudioAccount() {
        ServerScreenHost.AccountIdentity account = serverHost().accountIdentity();
        return account.authenticated() && accountKey(account).equals(restudioInstancesAccount);
    }

    private ServerScreenHost.HostView activeHostView() {
        Object data = tabs().getActiveTab() == null ? null : tabs().getActiveTab().getData();
        return data instanceof ServerScreenHost.HostView host ? host : null;
    }

    private static Stream<ServerModels.ClientServerView> serverWidgets(Stream<? extends AnimatedWidget> widgets) {
        return widgets.<ServerModels.ClientServerView>mapMulti((widget, sink) -> {
            if (widget instanceof DesktopIconWidget<?> icon
                    && icon.getItem() instanceof ServerModels.ClientServerView server) sink.accept(server);
        });
    }

    private static Stream<ServerModels.ClientServerView> groupServers(DesktopGroupWidget<?> group) {
        if (group == null) return Stream.empty();
        return group.getMembers().stream().<ServerModels.ClientServerView>mapMulti((widget, sink) -> {
            if (widget.getItem() instanceof ServerModels.ClientServerView server) sink.accept(server);
        });
    }

    private static Stream<DesktopGroupWidget<?>> groupWidgets(Stream<? extends AnimatedWidget> widgets) {
        return widgets.<DesktopGroupWidget<?>>mapMulti((widget, sink) -> {
            if (widget instanceof DesktopGroupWidget<?> group) sink.accept(group);
        });
    }

    private static String serverId(ServerModels.ClientServerView server) {
        if (server == null) {
            return "";
        }
        if (server.identifier != null && !server.identifier.isBlank()) {
            return server.identifier;
        }
        return server.uuid == null ? "" : server.uuid;
    }

    private static String serverName(ServerModels.ClientServerView server) {
        if (server == null) {
            return "Server";
        }
        if (server.name != null && !server.name.isBlank()) {
            return server.name;
        }
        String id = serverId(server);
        return id.isBlank() ? "Server" : id;
    }

    private static boolean isHiddenRestudioServer(List<String> hiddenServers, ServerModels.ClientServerView server) {
        if (server == null || hiddenServers == null || hiddenServers.isEmpty()) {
            return false;
        }
        String id = serverId(server);
        if (!id.isBlank() && hiddenServers.contains(id)) {
            return true;
        }
        String name = serverName(server);
        return !name.isBlank() && hiddenServers.contains(name);
    }

    private void saveConfiguredGroups(String context, List<DesktopGroup> groups) {
        List<ServerScreenHost.GroupView> views = groups == null ? List.of() : groups.stream()
                .filter(Objects::nonNull)
                .map(group -> new ServerScreenHost.GroupView(group.id(), group.name(), group.members()))
                .toList();
        serverHost().saveConfiguredGroups(context, views);
    }

}
