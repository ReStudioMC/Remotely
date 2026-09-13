package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.network.NetworkAdoptionReport;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyRecentItem;
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
import restudio.rescreen.ui.desktop.DesktopIconWidget;
import restudio.rescreen.ui.desktop.DesktopMetrics;
import restudio.rescreen.ui.desktop.DesktopShellScreen;
import restudio.rescreen.ui.desktop.DesktopTaskbarHelper;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.ui.rescreen.layout.DesktopLayout;
import restudio.rescreen.ui.rescreen.layout.FreeLayout;
import restudio.rescreen.ui.widgets.*;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static restudio.rescreen.util.SoundUtils.playSound;

public class ServerManagerScreen extends DesktopShellScreen {
    private final RemotelyClient remotelyClient;
    private ServerScreenHost cachedServerHost;
    private PopupWidget createChoicePopup;
    private PopupWidget addServerPopup;
    private IconButton createServerButton;
    private IconButton createNetworkButton;
    private IconButton emptyServerButton;
    private IconButton modpackServerButton;
    private IconButton importServerButton;
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
    private TabSwitchWidget remoteHostTypeSwitch;
    private TabSwitchWidget remoteHostAuthModeSwitch;
    private AnimatedButton remoteHostConfirmButton;
    private AnimatedButton remoteHostDeleteButton;
    private final Object parent;
    private IconButton userButton;
    private boolean serverManagerContextMenuPressed;
    private boolean networkOperationInFlight;

    private static final String ROW_REMOTE_HOST_TYPE = "remoteHostType";
    private static final String ROW_REMOTE_HOST_USER = "remoteHostUser";
    private static final String ROW_REMOTE_HOST_IP = "remoteHostIp";
    private static final String ROW_REMOTE_HOST_PORT = "remoteHostPort";
    private static final String ROW_REMOTE_HOST_PASSWORD = "remoteHostPassword";
    private static final String ROW_REMOTE_HOST_SFTP_PASSWORD = "remoteHostSftpPassword";
    private static final String ROW_REMOTE_HOST_AUTH_MODE = "remoteHostAuthMode";
    private static final String ROW_REMOTE_HOST_KEY_PATH = "remoteHostKeyPath";
    private static final String ROW_REMOTE_HOST_PASSPHRASE = "remoteHostPassphrase";
    private static final String ROW_REMOTE_HOST_REGISTRY_PATH = "remoteHostRegistryPath";

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
    private final Runnable instanceChangeListener = this::queueServerRefresh;
    private final Runnable networkChangeListener = this::queueServerRefresh;
    private final Runnable runtimeChangeListener = this::queueServerRefresh;
    private final Runnable authStateListener = this::refreshForAuthStateChange;
    private boolean serverRefreshQueued;
    private long contextMenuRequestGeneration;
    private long restudioFetchGeneration;
    private boolean restudioFetchInFlight;
    private String restudioFetchAccount = "";
    private long callbackGeneration;
    private List<DesktopGroup> instanceGroups = new ArrayList<>();
    private List<ServerScreenHost.NetworkView> networkViews = List.of();
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
        if (serverHost().supports(ServerScreenHost.Action.HOST_SETTINGS)) headerBuilder.addLeft(settingsButton);
        if (serverHost().supports(ServerScreenHost.Action.SIGN_IN) || serverHost().supports(ServerScreenHost.Action.SIGN_OUT)) headerBuilder.addLeft(userButton);
        headerBuilder.build();
        refreshAccountAvatar();

        if (Config.desktopMode) {
            taskbarHelper = new DesktopTaskbarHelper(this, header(), header().leftButtons.size(), () -> desktopBounds().taskbarTabs().x());
            if (serverHost().supports(ServerScreenHost.Action.GLOBAL_TERMINAL)) {
                taskbarHelper.pinApp("server-details", terminalButton, () -> serverHost().openGlobalTerminal(this), "Terminal");
            }
            if (serverHost().supports(ServerScreenHost.Action.FILE_EXPLORER)) {
                taskbarHelper.pinApp("file-explorer", fileExplorerButton, this::openFileExplorer, "File Explorer");
            }
            if (serverHost().supports(ServerScreenHost.Action.HOST_SETTINGS)) {
                taskbarHelper.pinApp("global-settings", settingsButton, () -> serverHost().openSettings(this), "Settings");
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
            .onTabRenamed(this::onHostTabRenamed);
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
        reactorsNoServersIcon = new IconMessage(width / 2 - 32, height / 4, 64, 64, "Reactor By ReStudio\nHigh-End & Affordable Hosting For Everyone.\nOrder And Control Your Server Right Here & Now!", "Reactor.png");
        pteroNoServersIcon = new IconMessage(width / 2 - 32, height / 4, 64, 64, "Panel Host\nLoading Servers...\nOpen Your Panel If No Servers Appear.", "server.png");
        reactorInfo = new IconButton.Builder().size(300, 18).label(authenticated() ? "Learn More Here" : "Sign In").imagePath("external").autoWidthOnTextChange(true).onClick(this::openReactorAccessAction).build();
        reactorInfo.setX(width / 2 - (reactorInfo.getWidth() / 2));
        reactorInfo.setY(reactorsNoServersIcon.getY() + reactorsNoServersIcon.getHeight() + (12 * 5));
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
        if (serverHost().supports(ServerScreenHost.Action.INBOX)) {
            builder.addHeaderButton(new ReStudioInboxButton(() -> serverHost().openInbox(this)));
        }
        if (serverHost().supports(ServerScreenHost.Action.REPORTS)) {
            builder.addHeaderButton("report.png", () -> serverHost().openReports(this), "Reports And Feedback");
        }
        if (serverHost().supports(ServerScreenHost.Action.SIGN_OUT)) {
            builder.addHeaderButton("close.png", () -> serverHost().signOut(this), "Sign Out", ThemeManager.getAccent("danger"));
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
        if (!reactiveRefreshEnabled) {
            return;
        }
        boolean authenticated = authenticated();
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
        serverHost().networks().whenComplete((networks, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure == null && networks != null) {
                networkViews = List.copyOf(networks);
            }
            if (afterRefresh != null) {
                afterRefresh.run();
            }
        }));
    }

    private void loadIcons() {
        try {
            serverIcon = Identifier.icon("server.png");
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
        for (AnimatedWidget widget : activeTab.getContainer().getWidgets()) {
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
            Object iconValue = iconTarget(instance);
            iconManager.loadIconIdAsync(iconValue, desktopIcon::setIcon);
            iconManager.loadRemoteIconAsync(iconValue, () -> iconManager.loadIconIdAsync(iconValue, desktopIcon::setIcon));
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
        showContextMenu(groupWidget.getX(), groupWidget.getY() + groupWidget.getHeight(), builder);
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
        DesktopIconWidget.Builder<ServerModels.ClientServerView> builder = iconId != null
            ? new DesktopIconWidget.Builder<>(info, iconId, label)
            : new DesktopIconWidget.Builder<>(info, (Identifier) null, label);
        DesktopIconWidget<ServerModels.ClientServerView> widget = builder
            .onClick(this::onDesktopIconClick)
            .build();
        if (iconId == null) {
            widget.setIcon(serverIcon);
        }
        widget.accentType = getDesktopIconAccent(info, isCreate);
        if (isCreate || info == null) {
            return widget;
        }
        iconManager.loadIconIdAsync(iconValue, widget::setIcon);
        iconManager.loadRemoteIconAsync(iconValue, () -> iconManager.loadIconIdAsync(iconValue, widget::setIcon));
        return widget;
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

    private void onHostTabRenamed(TabsManager.Tab tab) {
        if (tab != null && tab.getData() instanceof ServerScreenHost.HostView) {
            openRemoteHostPopup(true);
        }
    }

    public void openRemoteHostEditor() {
        openRemoteHostPopup(false);
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
        if (!isPanel) {
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
        showContextMenu(widget.getX() + widget.getWidth() + 4, widget.getY() + 24, builder);
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
        return identity.local() && !identity.installing() && !serverHost().isPanel(server) && !isRestudioServer(server)
                && networkForServer(server) == null;
    }

    private boolean canUseAsNetworkProxy(ServerModels.ClientServerView server) {
        if (!isVelocityProxy(server)) {
            return false;
        }
        ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
        return identity.local() && !identity.installing() && !serverHost().isPanel(server) && !isRestudioServer(server)
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
        return proxyIdentity.local() && !proxyIdentity.installing() && !serverHost().isPanel(proxy) && !isRestudioServer(proxy)
                && canUseAsNetworkBackend(server);
    }

    private boolean canStandaloneMutation(ServerModels.ClientServerView server) {
        ServerScreenHost.ServerIdentity identity = serverHost().identity(server);
        return identity.local() && !identity.installing() && !serverHost().isPanel(server)
                && !"RESTUDIO".equalsIgnoreCase(identity.backendType()) && networkForServer(server) == null;
    }

    private boolean canServerManagerAction(ServerModels.ClientServerView server, ServerScreenHost.Action action, String capability) {
        if (!reactorOnlyServerManager()) {
            return canStandaloneMutation(server);
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
        serverHost().application().setScreen(new NetworkCreationScreen(this, serverHost(), selected));
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
        serverHost().networkAction(network, "dissolve").whenComplete((ignored, throwable) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            networkOperationInFlight = false;
            if (throwable != null) {
                notification.update().message("Dissolve Failed").description(rootMessage(throwable)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            notification.update().message("Network Dissolved").description("Servers Restored As Standalone").type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            loadServersForAllTabs();
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
        long running = selected.stream().filter(this::isServerActive).count();
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(this)
                .addHeaderButton("start.png", () -> currentSelectedServerInstances().stream().filter(instance -> !isServerActive(instance)).forEach(instance -> setServerPower(instance, true)), "Start Selected", ThemeManager.getAccent("nice"))
                .addHeaderButton("stop.png", () -> currentSelectedServerInstances().stream().filter(this::isServerActive).forEach(instance -> setServerPower(instance, false)), "Stop Selected", ThemeManager.getAccent("danger"))
                .addHeaderButton("folder.png", () -> createServerGroup(currentSelectedServerInstances()), "Group Selected")
                .addIconItem(selected.size() + " Servers Selected", "info.png", () -> {}, running + " Running");
        if (selected.stream().allMatch(this::canDuplicateServer)) {
            builder.addHeaderButton("copy.png", () -> currentSelectedServerInstances().stream().filter(this::canDuplicateServer).forEach(this::duplicateInstance), "Duplicate Selected");
        }
        showContextMenu(anchor.getX() + anchor.getWidth() + 4, anchor.getY() + 24, builder);
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
                    openReactorPlanSelection();
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

    private void showDeleteServerPopup(ServerModels.ClientServerView server) {
        if (!ensureServerManagerAction(server, ServerScreenHost.Action.DELETE_SERVER, "server.delete")) {
            return;
        }
        Object iconTarget = iconTarget(server);
        Identifier iconId = iconTarget != null ? iconManager.getQuickIconId(iconTarget) : null;
        IconButton entry = new IconButton.Builder()
                .label(serverName(server))
                .identifier(iconId != null ? iconId : serverIcon)
                .iconSize(24)
                .size(0, 30)
                .build();
        entry.setActive(false);
        if (iconTarget != null) {
            iconManager.loadIconIdAsync(iconTarget, entry::setIcon);
        }
        List<AnimatedWidget> entries = new ArrayList<>();
        entries.add(entry);
        String path = serverHost().identity(server).path();
        if (path != null && !path.isBlank()) {
            entries.add(DeletionPopup.entry(path, "folder.png"));
        }
        List<DeletionPopup.Action> actions = new ArrayList<>();
        if (serverHost().serverManagerMode() != ServerScreenHost.ServerManagerMode.REACTOR_ONLY) {
            actions.add(DeletionPopup.Action.trash(popup -> deleteServer(server, popup, false)));
        }
        actions.add(DeletionPopup.Action.permanent(popup -> deleteServer(server, popup, true)));
        actions.add(DeletionPopup.Action.hide(popup -> hideServer(server, popup)));
        DeletionPopup.show(this, entries, actions.toArray(DeletionPopup.Action[]::new));
    }

    private void hideServer(ServerModels.ClientServerView server, PopupWidget popup) {
        playSound(Sound.CLICK);
        if (popup != null) {
            popup.hide();
        }
        RemotelyConfigStore config = remotelyClient.getComposition().configManager();
        if (config != null) {
            String identifier = serverId(server);
            if (!identifier.isBlank()) config.hideRestudioServer(identifier);
            String name = serverName(server);
            if (!name.isBlank()) config.hideRestudioServer(name);
        }
        long generation = callbackGeneration;
        serverHost().serverAction(server, "hide").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            new Notification("Server Hidden", serverName(server), Notification.Type.SUCCESS);
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
            case "SSH" -> "SSH";
            default -> "Local";
        };
        String stateValue = serverHost().state(server).name();
        String state = stateValue.isBlank() ? "Unknown" : stateValue.charAt(0) + stateValue.substring(1).toLowerCase(Locale.ROOT);
        return backend + "  •  " + state;
    }

    private void deleteServer(ServerModels.ClientServerView server, PopupWidget popup, boolean permanent) {
        playSound(Sound.DELETE);
        if (popup != null) {
            popup.hide();
        }
        Notification notification = new Notification.Builder()
                .message(permanent ? "Deleting Server" : "Moving Server To Trash")
                .description(serverName(server))
                .type(Notification.Type.INFO)
                .loading(true)
                .autoSlideOut(false)
                .build();
        long generation = callbackGeneration;
        serverHost().serverAction(server, permanent ? "delete" : "trash").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure != null) {
                notification.update().message(permanent ? "Delete Failed" : "Move Failed").description(rootMessage(failure)).type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            notification.update().message(permanent ? "Server Deleted" : "Moved To Trash").description(serverName(server)).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            loadServersForAllTabs();
        }));
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
        PopupWidget.Builder builder = new PopupWidget.Builder("Connect Remote Host").onClose(this::closeRemoteHostPopup)
            .size(360, 350).setResizable(true)
            .setAntiOutOfBound(true).setBoundOffset(header().headerSize)
            .setMinSize(360, 350);

        remoteHostNameInput = new TextInputWidget.Builder().size(18, 18).build();
        builder.addRow("Host Name", remoteHostNameInput);

        remoteHostTypeSwitch = new TabSwitchWidget.Builder().options(List.of("SSH", "Pterodactyl", "Calagopus")).currentIndex(0).build();
        builder.addRow(ROW_REMOTE_HOST_TYPE, "Host Type", remoteHostTypeSwitch);

        remoteHostUserInput = new TextInputWidget.Builder().size(18, 18).text("root").build();
        builder.addRow(ROW_REMOTE_HOST_USER, "User Name", remoteHostUserInput);

        remoteHostIpInput = new TextInputWidget.Builder().size(18, 18).build();
        builder.addRow(ROW_REMOTE_HOST_IP, "IP Or Domain", remoteHostIpInput);

        remoteHostPortInput = new TextInputWidget.Builder().size(18, 18).text("22").build();
        builder.addRow(ROW_REMOTE_HOST_PORT, "Port", remoteHostPortInput);

        remoteHostPasswordInput = new TextInputWidget.Builder().size(18, 18).build();
        builder.addRow(ROW_REMOTE_HOST_PASSWORD, "Password", remoteHostPasswordInput);

        remoteHostSftpPasswordInput = new TextInputWidget.Builder().size(18, 18).build();
        builder.addRow(ROW_REMOTE_HOST_SFTP_PASSWORD, "Panel Password (Optional, Required For SFTP)", remoteHostSftpPasswordInput);

        remoteHostAuthModeSwitch = new TabSwitchWidget.Builder().options(List.of("Password", "SSH Key")).currentIndex(0).build();
        builder.addRow(ROW_REMOTE_HOST_AUTH_MODE, "Auth Mode", remoteHostAuthModeSwitch);

        remoteHostKeyPathInput = new TextInputWidget.Builder().size(18, 18).placeholder("Leave Empty For Auto Discovery").build();
        builder.addRow(ROW_REMOTE_HOST_KEY_PATH, "Key Path", remoteHostKeyPathInput);

        remoteHostKeyPassphraseInput = new TextInputWidget.Builder().size(18, 18).build();
        builder.addRow(ROW_REMOTE_HOST_PASSPHRASE, "Passphrase", remoteHostKeyPassphraseInput);

        remoteHostRegistryPathInput = new TextInputWidget.Builder().size(18, 18).placeholder("Optional File Path For Multiple Users").build();
        builder.addRow(ROW_REMOTE_HOST_REGISTRY_PATH, "Shared Registry File", remoteHostRegistryPathInput);

        remoteHostPopup = builder.build();
        remoteHostPopup.hide();
        addDrawableChild(remoteHostPopup);
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

    private void openRemoteHostPopup(boolean isEditing) {
        int activeTabIndex = tabs().getActiveTabIndex();
        Object data = (tabs().getActiveTab() != null) ? tabs().getActiveTab().getData() : null;

        remoteHostPopup.clearRows();

        String nameText = "";
        String userText = "root";
        String ipText = "";
        String portText = "22";
        String passwordText = "";
        String sftpPasswordText = "";
        String hostType = "SSH";
        String registryPathText = "";

        if (isEditing && activeTabIndex > 0 && data instanceof ServerScreenHost.HostView host) {
            nameText = host.name();
            userText = host.user().isBlank() ? userText : host.user();
            ipText = host.address();
            portText = String.valueOf(host.port());
            hostType = host.type();

            remoteHostConfirmButton = new AnimatedButton.Builder().label(("Save")).size(18, 18).accentType(ThemeManager.getAccent("nice")).onClick(this::onConfirmRemoteHost).build();
            remoteHostDeleteButton = new AnimatedButton.Builder().label(("Delete")).size(18, 18).onClick(this::onDeleteRemoteHost).accentType(ThemeManager.getAccent("danger")).build();
        } else {
            remoteHostConfirmButton = new AnimatedButton.Builder().label(("Test & Add")).size(18, 18).accentType(ThemeManager.getAccent("nice")).onClick(this::onConfirmRemoteHost).build();
            remoteHostDeleteButton = null;
        }

        String authModeText = "PASSWORD";
        String keyPathText = "";
        if (isEditing && activeTabIndex > 0 && data instanceof ServerScreenHost.HostView host) {
            authModeText = host.authMode();
            keyPathText = host.keyPath();
            registryPathText = host.registryPath();
        }

        remoteHostNameInput.setText(nameText);
        remoteHostTypeSwitch.setCurrentIndex(remoteHostTypeIndex(hostType));
        remoteHostUserInput.setText(isPanelType(hostType) ? "" : userText);
        remoteHostIpInput.setText(ipText);
        remoteHostPortInput.setText(portText);
        remoteHostPasswordInput.setText(passwordText);
        remoteHostSftpPasswordInput.setText(sftpPasswordText);
        remoteHostAuthModeSwitch.setCurrentIndex("KEY".equalsIgnoreCase(authModeText) ? 1 : 0);
        remoteHostKeyPathInput.setText(keyPathText);
        remoteHostKeyPassphraseInput.setText("");
        remoteHostRegistryPathInput.setText(registryPathText);

        remoteHostPopup.addRow("Host Name", remoteHostNameInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_TYPE, "Host Type", remoteHostTypeSwitch);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_USER, "User Name", remoteHostUserInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_IP, "IP Or Domain", remoteHostIpInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_PORT, "Port", remoteHostPortInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_AUTH_MODE, "Auth Mode", remoteHostAuthModeSwitch);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_PASSWORD, "Secret", remoteHostPasswordInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_SFTP_PASSWORD, "Panel Password", remoteHostSftpPasswordInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_KEY_PATH, "Key Path", remoteHostKeyPathInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_PASSPHRASE, "Passphrase", remoteHostKeyPassphraseInput);
        remoteHostPopup.addRow(ROW_REMOTE_HOST_REGISTRY_PATH, "Shared Registry File", remoteHostRegistryPathInput);

        remoteHostPopup.clearTitleActions();
        remoteHostPopup.addTitleAction(isEditing ? "Save" : "Test & Add", () -> remoteHostConfirmButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.PRIMARY);
        if (isEditing && remoteHostDeleteButton != null) {
            remoteHostPopup.addTitleAction("Delete", () -> remoteHostDeleteButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.DESTRUCTIVE);
        }

        remoteHostTypeSwitch.setOnChange(this::updateRemoteHostAdvancedVisibility);
        remoteHostAuthModeSwitch.setOnChange(this::updateRemoteHostAdvancedVisibility);
        updateRemoteHostAdvancedVisibility();

        remoteHostNameInput.addOnEnter((w) -> remoteHostPopup.setFocusedWidget(isPanelHostSelected() ? remoteHostIpInput : remoteHostUserInput));
        remoteHostUserInput.addOnEnter((w) -> {
            if (isPanelHostSelected()) {
                remoteHostPopup.setFocusedWidget(remoteHostSftpPasswordInput);
            } else {
                remoteHostPopup.setFocusedWidget(remoteHostIpInput);
            }
        });
        remoteHostIpInput.addOnEnter((w) -> remoteHostPopup.setFocusedWidget(isPanelHostSelected() ? remoteHostPasswordInput : remoteHostPortInput));
        remoteHostPortInput.addOnEnter((w) -> remoteHostPopup.setFocusedWidget(remoteHostPasswordInput));
        remoteHostPasswordInput.addOnEnter((w) -> {
            if (isPanelHostSelected()) {
                remoteHostPopup.setFocusedWidget(remoteHostSftpPasswordInput);
                return;
            }
            if (remoteHostAuthModeSwitch.getCurrentIndex() == 1) {
                remoteHostPopup.setFocusedWidget(remoteHostKeyPathInput);
            } else {
                remoteHostPopup.setFocusedWidget(remoteHostRegistryPathInput);
            }
        });
        remoteHostSftpPasswordInput.addOnEnter((w) -> onConfirmRemoteHost());
        remoteHostKeyPathInput.addOnEnter((w) -> remoteHostPopup.setFocusedWidget(remoteHostKeyPassphraseInput));
        remoteHostKeyPassphraseInput.addOnEnter((w) -> remoteHostPopup.setFocusedWidget(remoteHostRegistryPathInput));
        remoteHostRegistryPathInput.addOnEnter((w) -> onConfirmRemoteHost());

        remoteHostPopup.setX((this.width - remoteHostPopup.getWidth()) / 2);
        remoteHostPopup.setY((this.height - remoteHostPopup.getHeight()) / 2);
        remoteHostPopup.show();
    }

    private void onConfirmRemoteHost() {
        boolean isEditing = tabs().getActiveTabIndex() > 0 && "Save".equals(remoteHostConfirmButton.getMessage());
        boolean isPanel = isPanelHostSelected();
        int port;
        try {
            port = isPanel ? 443 : Integer.parseInt(remoteHostPortInput.getText());
        } catch (NumberFormatException exception) {
            new Notification("Error", "Port Must Be A Valid Number", Notification.Type.ERROR);
            return;
        }
        String type = isPanel ? selectedPanelType() : "SSH";
        String authMode = isPanel ? "PASSWORD" : (remoteHostAuthModeSwitch.getCurrentIndex() == 1 ? "KEY" : "PASSWORD");
        String address = remoteHostIpInput.getText();
        String name = remoteHostNameInput.getText();
        if (name == null || name.isBlank() || address == null || address.isBlank()) {
            new Notification("Error", "Host Name and IP cannot be empty.", Notification.Type.ERROR);
            return;
        }
        String id = isEditing && tabs().getActiveTab().getData() instanceof ServerScreenHost.HostView host ? host.id() : "";
        ServerScreenHost.RemoteHostDraft draft = new ServerScreenHost.RemoteHostDraft(id, name, isPanel ? "" : remoteHostUserInput.getText(), address, port, type, authMode,
                isPanel ? remoteHostSftpPasswordInput.getText() : remoteHostPasswordInput.getText(), isPanel ? remoteHostPasswordInput.getText() : "",
                remoteHostKeyPathInput.getText(), remoteHostKeyPassphraseInput.getText(), remoteHostRegistryPathInput.getText());
        Notification notification = new Notification.Builder().message(isEditing ? "Saving Host" : "Testing Host").description("Connecting... 0%").type(Notification.Type.INFO).loading(true).progress(0, 100).autoSlideOut(false).build();
        Async<ServerScreenHost.HostView> save = isEditing ? serverHost().saveRemoteHost(draft) : serverHost().testAndSaveRemoteHost(draft);
        long generation = callbackGeneration;
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
                closeRemoteHostPopup();
                return;
            }
            notification.update().message("Host Ready").description(host.name()).type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            if (tabs().getActiveTab() != null) {
                tabs().getActiveTab().setName(host.name());
                tabs().getActiveTab().setData(host);
            }
            closeRemoteHostPopup();
        }));
    }

    private void updateRemoteHostAdvancedVisibility() {
        boolean usePtero = isPanelHostSelected();
        boolean useKey = !usePtero && remoteHostAuthModeSwitch != null && remoteHostAuthModeSwitch.getCurrentIndex() == 1;
        if (remoteHostPopup != null) {
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_TYPE, true);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_USER, !usePtero);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_IP, true);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_PORT, !usePtero);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_PASSWORD, usePtero || !useKey);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_AUTH_MODE, !usePtero);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_SFTP_PASSWORD, usePtero);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_KEY_PATH, useKey);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_PASSPHRASE, useKey);
            remoteHostPopup.setRowVisibility(ROW_REMOTE_HOST_REGISTRY_PATH, !usePtero);
        }
        if (remoteHostUserInput != null) {
            remoteHostUserInput.setActive(!usePtero);
            remoteHostUserInput.setVisible(!usePtero);
        }
        if (remoteHostPortInput != null) {
            remoteHostPortInput.setActive(!usePtero);
            remoteHostPortInput.setVisible(!usePtero);
        }
        if (remoteHostPasswordInput != null) {
            remoteHostPasswordInput.setActive(usePtero || !useKey);
            remoteHostPasswordInput.setVisible(usePtero || !useKey);
        }
        if (remoteHostSftpPasswordInput != null) {
            remoteHostSftpPasswordInput.setActive(usePtero);
            remoteHostSftpPasswordInput.setVisible(usePtero);
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
            remoteHostRegistryPathInput.setActive(!usePtero);
            remoteHostRegistryPathInput.setVisible(!usePtero);
        }
        if (remoteHostAuthModeSwitch != null) {
            remoteHostAuthModeSwitch.setActive(!usePtero);
            remoteHostAuthModeSwitch.setVisible(!usePtero);
        }
        if (remoteHostTypeSwitch != null) {
            remoteHostTypeSwitch.setActive(true);
            remoteHostTypeSwitch.setVisible(true);
        }
    }

    private boolean isPanelHostSelected() {
        return remoteHostTypeSwitch != null && remoteHostTypeSwitch.getCurrentIndex() > 0;
    }

    private String selectedPanelType() {
        return remoteHostTypeSwitch != null && remoteHostTypeSwitch.getCurrentIndex() == 2 ? "CALAGOPUS" : "PTERO";
    }

    private int remoteHostTypeIndex(String type) {
        if ("CALAGOPUS".equalsIgnoreCase(type)) return 2;
        return "PTERO".equalsIgnoreCase(type) ? 1 : 0;
    }

    private boolean isPanelType(String type) {
        return "PTERO".equalsIgnoreCase(type) || "CALAGOPUS".equalsIgnoreCase(type);
    }

    private void onDeleteRemoteHost() {
        if (tabs().getActiveTabIndex() <= 0 || !(tabs().getActiveTab().getData() instanceof ServerScreenHost.HostView host)) return;
        long generation = callbackGeneration;
        serverHost().hostAction(host, "delete").whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!isCurrentCallback(generation)) {
                return;
            }
            if (failure != null) {
                new Notification("Host Delete Failed", rootMessage(failure), Notification.Type.ERROR);
                return;
            }
            tabs().removeTab(tabs().getActiveTabIndex());
            tabs().setActiveTab(0);
            closeRemoteHostPopup();
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
        if(remoteHostPopup != null) {
            remoteHostPopup.hide();
        }
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
    public void onDisplayed() {
        super.onDisplayed();
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
            reactorInfo.setY(reactorsNoServersIcon.getY() + reactorsNoServersIcon.getHeight() + (12 * 5));
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
            groupWidgets(activeContainer.getWidgets().stream()).filter(DesktopGroupWidget::isExpanded)
                    .filter(group -> !group.isMouseOver(event.x(), event.y())).forEach(group -> group.setExpanded(false));
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
