package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.config.RemotelyViewStateStore;
import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.session.TerminalSession;
import redxax.oxy.remotely.ui.server.containers.PlayersContainer;
import redxax.oxy.remotely.ui.widgets.management.PlayerManagerController;
import restudio.rebase.api.unified.internal.StandardOutputStateParser;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.ui.widgets.LifecycleButtonWidget;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rebase.ui.widgets.ViewSwitcherWidget;
import restudio.rescreen.debug.IDebugInfoProvider;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReDropEvent;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.*;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.UiTasks;

import java.util.*;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static restudio.rescreen.config.Config.desktopMode;

public class ServerDetailsScreen extends ReScreen implements IDebugInfoProvider, DesktopWindowBehaviorProvider, ServerDevelopmentHost {

    private final RemotelyClient remotelyClient;
    private final Object parent;
    private final Object initialInstanceToOpen;
    private final RemotelyViewStateStore.State browserRestoreState;
    private Object instance;
    private final Map<TabsManager.Tab, TabContext> tabContexts = new HashMap<>();
    private ViewSwitcherWidget viewSwitcher;
    private boolean resetAnimForView;
    private final Consumer<ServerScreenHost.ServerState> stateListener = this::onStateChanged;
    private boolean openDevelopmentOnStart;
    private boolean clearBrowserDetailStateOnRemove;
    private LifecycleButtonWidget startIconButton;
    private ToggleWidget developmentModeToggle;
    private boolean applyingDevelopmentMode;
    private PopupWidget networkSummaryPopup;
    private PopupWidget serverHealthPopup;
    private TabContext serverHealthPopupContext;
    private Object sidecarInstance;
    private ServerDevelopmentPanel developmentPanel;

    private final Map<TabContext, TerminalSession> contextInfos = new HashMap<>();
    private final Map<TabContext, DevelopmentTabState> developmentTabs = new IdentityHashMap<>();
    private TaskScheduler.ScheduledTask statusScheduler;
    private SearchMode headerSearchMode;
    private SearchMode resourcesSearchMode;
    private SearchMode playersSearchMode;
    private final Set<String> localControllerFailureNotices = new HashSet<>();
    private final Map<String, Long> serverHealthChecksInFlight = new HashMap<>();
    private final Set<String> serverHealthRepairsInFlight = new HashSet<>();
    private long serverHealthRequestSequence;
    private final Map<String, Consumer<ServerScreenHost.ServerState>> restartListeners = new HashMap<>();
    private Async<Object> newTerminalTargetRequest;
    private Async<NewTerminalTargetProvider.State> terminalRestoreRequest;
    private long newTerminalTargetGeneration;
    private volatile boolean closed;
    private static final long KILL_CONFIRM_MS = 5_000;
    private String killConfirmInstanceId;
    private String killingInstanceId;
    private long killConfirmUntilMs;
    private static final int TERMINAL_SCROLLBAR_WIDTH = 2;
    private final ScrollbarController terminalScrollbarController = new ScrollbarController();

    private enum ServerHealthRepair {
        NONE,
        EULA,
        SERVER_JAR,
        START_SCRIPT
    }

    record MetricsRequest(long requestGeneration, long lifecycleGeneration) {
    }

    static final class MetricsRequestGate {
        private long requestGeneration;
        private long lifecycleGeneration;
        private long lastRequestAtMs;
        private MetricsRequest activeRequest;
        private ServerScreenHost.ServerState lifecycleState = ServerScreenHost.ServerState.UNKNOWN;

        MetricsRequest tryStart(long nowMs) {
            if (activeRequest != null && nowMs - lastRequestAtMs <= 5_000) return null;
            if (nowMs - lastRequestAtMs < 1_000) return null;
            lastRequestAtMs = nowMs;
            activeRequest = new MetricsRequest(++requestGeneration, lifecycleGeneration);
            return activeRequest;
        }

        boolean current(MetricsRequest request) {
            return request != null && request.equals(activeRequest) && request.lifecycleGeneration() == lifecycleGeneration;
        }

        void finish(MetricsRequest request) {
            if (current(request)) activeRequest = null;
        }

        void transition(ServerScreenHost.ServerState state) {
            ServerScreenHost.ServerState resolved = state == null ? ServerScreenHost.ServerState.UNKNOWN : state;
            if (lifecycleState == resolved) return;
            lifecycleState = resolved;
            invalidate();
        }

        void invalidate() {
            lifecycleGeneration++;
            activeRequest = null;
        }
    }

    private static final class DevelopmentTabState {
        private final Object remote;
        private final Object local;
        private final List<ViewEntry> remoteViews;
        private final List<ViewEntry> localViews;
        private final TerminalSession remoteSession;
        private final TerminalSession localSession;
        private int remoteView;
        private int localView;
        private boolean localActive;

        private DevelopmentTabState(Object remote, Object local, List<ViewEntry> remoteViews, List<ViewEntry> localViews,
                                    TerminalSession remoteSession, TerminalSession localSession, int remoteView) {
            this.remote = remote;
            this.local = local;
            this.remoteViews = remoteViews;
            this.localViews = localViews;
            this.remoteSession = remoteSession;
            this.localSession = localSession;
            this.remoteView = remoteView;
        }
    }

    private record DevelopmentRestoreState(Object remote, Object local, boolean localActive,
                                            int remoteView, int localView, boolean panelVisible) {
    }

    public ServerDetailsScreen(Object parent, RemotelyClient client) {
        this(parent, client, null, false);
    }

    public ServerDetailsScreen(Object parent, RemotelyClient client, Object initialInstanceToOpen) {
        this(parent, client, initialInstanceToOpen, false);
    }

    public ServerDetailsScreen(Object parent, RemotelyClient client, Object initialInstanceToOpen, boolean openDevelopmentOnStart) {
        super();
        this.parent = parent;
        this.remotelyClient = client;
        this.initialInstanceToOpen = initialInstanceToOpen;
        this.browserRestoreState = client == null ? RemotelyViewStateStore.State.empty() : client.getBrowserViewState();
        this.openDevelopmentOnStart = openDevelopmentOnStart;
    }

    private ServerScreenHost screenHost() {
        return remotelyClient == null || remotelyClient.getHost() == null
                ? ServerScreenHost.of(new EmptyApplicationHost()) : remotelyClient.getHost().serverScreenHost(remotelyClient);
    }

    private ServerDetailsTarget detailsTarget(Object target) {
        return screenHost().detailsTarget(target);
    }

    private boolean browserRuntime() {
        return remotelyClient != null && remotelyClient.getComposition().environment() == RemotelyComposition.Environment.BROWSER;
    }

    private void restoreBrowserView(TabContext context) {
        if (!browserRuntime() || context == null || browserRestoreState.serverId().isBlank() || context.instance == null) {
            return;
        }
        String targetId = detailsTarget(context.instance).id();
        String tabId = detailsTarget(context.id).id();
        if (!browserRestoreState.serverId().equals(targetId)
                || !browserRestoreState.tabId().isBlank() && !browserRestoreState.tabId().equals(tabId)) {
            return;
        }
        int restoredIndex = viewIndex(context.views, browserRestoreState.viewId());
        if (restoredIndex >= 0) {
            context.selectedViewIndex = restoredIndex;
        }
    }

    private int viewIndex(List<ViewEntry> views, String viewId) {
        if (views == null || viewId == null || viewId.isBlank()) {
            return -1;
        }
        for (int index = 0; index < views.size(); index++) {
            if (viewId.equalsIgnoreCase(viewName(views.get(index)))) {
                return index;
            }
        }
        return -1;
    }

    private void persistBrowserViewContext(TabContext context) {
        if (!browserRuntime() || remotelyClient == null || !screenHost().authenticated()) {
            return;
        }
        if (context == null || context.instance == null) {
            remotelyClient.clearBrowserDetailState();
            return;
        }
        ServerModels.ClientServerView server = screenHost().serverView(context.instance);
        String serverId = detailsTarget(server).id();
        if (server == null || serverId.isBlank()) {
            remotelyClient.clearBrowserDetailState();
            return;
        }
        String tabId = detailsTarget(context.id).id();
        if (tabId.isBlank()) {
            tabId = serverId;
        }
        String viewId = context.selectedViewIndex >= 0 && context.selectedViewIndex < context.views.size()
                ? viewName(context.views.get(context.selectedViewIndex)) : "";
        remotelyClient.saveBrowserDetailState(serverId, tabId, viewId);
    }

    @Override
    public ReScreen screen() {
        return this;
    }

    @Override
    public ServerDevelopmentProvider developmentProvider() {
        return screenHost().developmentProvider();
    }

    @Override
    public String developmentConfigPath() {
        return screenHost().developmentConfigPath();
    }

    @Override
    public String developmentInstanceId(Object instance) {
        return detailsTarget(instance).id();
    }

    @Override
    public String developmentInstanceName(Object instance) {
        return detailsTarget(instance).name();
    }

    @Override
    public void init(){
        resetAnimForView = true;
        cancelNewTerminalTargetRequest();
        TabContext previousContext = getActiveContext();
        Map<String, Integer> previousViewIndices = new HashMap<>();
        for (Map.Entry<TabsManager.Tab, TabContext> entry : tabContexts.entrySet()) {
            TabContext context = entry.getValue();
            DevelopmentTabState development = developmentTabs.get(context);
            Object keyTarget = development == null ? context.instance == null ? context.id : context.instance : development.remote;
            previousViewIndices.put(reinitializationKey(keyTarget), context.selectedViewIndex);
        }
        DevelopmentTabState previousDevelopment = previousContext == null ? null : developmentTabs.get(previousContext);
        DevelopmentRestoreState developmentRestore = previousDevelopment == null ? null : new DevelopmentRestoreState(
                previousDevelopment.remote,
                previousDevelopment.local,
                previousDevelopment.localActive,
                previousDevelopment.remoteView,
                previousDevelopment.localView,
                developmentPanel != null && developmentPanel.isRequestedVisible());
        int previousActiveTabIndex = tabs().getActiveTabIndex();
        closed = false;
        if (statusScheduler != null) {
            statusScheduler.cancel();
            statusScheduler = null;
        }
        if (viewSwitcher != null) {
            viewSwitcher.cleanup();
            viewSwitcher = null;
        }
        destroyAllSessions();
        for (TabContext context : new ArrayList<>(tabContexts.values())) {
            if (context instanceof ServerTabStatusContext statusContext) statusContext.invalidateMetricsRequest();
            if (context.capabilityProvider != null && context.capabilityListener != null) {
                context.capabilityProvider.removeCapabilityListener(context.capabilityListener);
            }
            cleanupTarget(context.instance);
            DevelopmentTabState development = developmentTabs.get(context);
            if (development != null) {
                cleanupTarget(development.remote);
                cleanupTarget(development.local);
            }
        }
        restartListeners.clear();
        serverHealthChecksInFlight.clear();
        serverHealthRepairsInFlight.clear();
        localControllerFailureNotices.clear();
        statusContexts.clear();
        tabContexts.clear();
        contextInfos.clear();
        developmentTabs.clear();
        hideServerHealthPopup();
        super.init();
        tabs().clearTabs();
        if (previousActiveTabIndex >= 0) setSavedTabIndex(previousActiveTabIndex);
        header().reset();
        statusBar().size(14).visible(false).build();
        setupHeader();
        setupTabs();
        setupDevelopmentPanel();
        applyStatusBarForActiveTab();
        startStatusScheduler();
        restoreViewIndices(previousViewIndices);
        TabContext context = getActiveContext();
        if (context != null) {
            if (developmentPanel != null) developmentPanel.activeServerChanged(context.instance);
            if (!context.views.isEmpty()) onViewChanged(context, context.views.get(Math.clamp(context.selectedViewIndex, 0, context.views.size() - 1)));
            if (developmentRestore != null) {
                restoreDevelopment(developmentRestore);
            } else if (openDevelopmentOnStart && context.instance != null) {
                openDevelopment(context.instance);
            }
        }
        openDevelopmentOnStart = false;
        resetAnimForView = false;
    }

    private String reinitializationKey(Object target) {
        if (target instanceof String value) return "terminal:" + value;
        String id = detailsTarget(target).id();
        return id.isBlank() ? "object:" + System.identityHashCode(target) : "server:" + id;
    }

    private void restoreViewIndices(Map<String, Integer> previousViewIndices) {
        if (previousViewIndices.isEmpty()) return;
        for (TabContext context : tabContexts.values()) {
            DevelopmentTabState development = developmentTabs.get(context);
            Object keyTarget = development == null ? context.instance == null ? context.id : context.instance : development.remote;
            Integer index = previousViewIndices.get(reinitializationKey(keyTarget));
            if (index != null) context.selectedViewIndex = Math.max(0, index);
        }
        TabsManager.Tab active = tabs().getActiveTab();
        if (active != null) onTabSelected(active);
    }

    private void restoreDevelopment(DevelopmentRestoreState restore) {
        if (restore.remote() == null || restore.local() == null) return;
        openDevelopmentTab(restore.remote(), restore.local());
        TabsManager.Tab tab = tabs().getActiveTab();
        TabContext context = tab == null ? null : tabContexts.get(tab);
        DevelopmentTabState development = context == null ? null : developmentTabs.get(context);
        if (context == null || development == null) return;
        development.remoteView = Math.max(0, restore.remoteView());
        development.localView = Math.max(0, restore.localView());
        setDevelopmentLocal(false);
        if (restore.localActive()) setDevelopmentLocal(true);
        if (developmentPanel != null) developmentPanel.restoreVisibility(restore.panelVisible());
    }

    public String getDesktopAppId() {
        return "server-details";
    }

    public String getDesktopAppTitle() {
        return "Terminal";
    }

    public String getDesktopAppIconPath() {
        return "terminal.png";
    }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() {
        return DesktopWindowBehavior.MERGE_INTO_EXISTING;
    }

    @Override
    public boolean mergeIntoExistingDesktopWindow(Screen existingScreen) {
        if (!(existingScreen instanceof ServerDetailsScreen existing)) {
            return false;
        }
        if (initialInstanceToOpen != null) {
            existing.addInstanceTab(initialInstanceToOpen);
            if (openDevelopmentOnStart) {
                existing.openDevelopment(initialInstanceToOpen);
            }
        }
        return true;
    }

    @Override
    public void close(){
        clearBrowserDetailStateOnRemove = true;
        if (desktopMode && isDesktopWindow()) {
            var overlay = ScreenManager.getInstance().getDesktopWindowsOverlay();
            if (overlay != null) {
                overlay.requestCloseWindowForScreen(this);
                return;
            }
        }
        screenHost().closeServerScreen(this, parent);
    }

    public void closeScreen() {
        close();
    }

    protected void setupHeader(){
        header().addRight("close.png", this::closeScreen, "Close");
        if (screenHost().supports(ServerScreenHost.Action.RESYNC_STUDIO)) header().addRight("ReSync.png", this::openReSyncStudio, "ReSync");
        if (screenHost().supports(ServerScreenHost.Action.FILE_EXPLORER)) header().addRight("explorer.png", this::exploreInstanceFiles, "File Explorer");
        if (screenHost().supports(ServerScreenHost.Action.SERVER_CONFIGURATION)) header().addRight("edit.png", this::openInstanceSettings, "Server Settings");
        if (screenHost().supports(ServerScreenHost.Action.DEVELOPMENT)) header().addRight("merge.png", this::openDevModeScreen, "Development");
        developmentModeToggle = new ToggleWidget.Builder().label("Local").toggled(true).size(66, 18)
                .animateElevation(false).entranceAnimation(false).onChange(() -> {
                    if (!applyingDevelopmentMode) setDevelopmentLocal(developmentModeToggle.getValue());
                }).build();
        developmentModeToggle.setVisible(false);
        developmentModeToggle.setHint("Switch Local And Remote");
        header().addLeft(developmentModeToggle);
        startIconButton = new LifecycleButtonWidget(this::launchOrStopInstance, "Server")
                .shiftAction("Restart Server", "Restart Server", "reload.png", state -> state == InstanceState.RUNNING);
        header().addLeft(startIconButton);
        ServerScreenHost.EnvironmentNotice notice = screenHost().environmentNotice();
        if (notice.visible()) {
            header().addLeft(new IconButton.Builder().imagePath(notice.icon()).label(notice.label()).hint(notice.description())
                    .active(false).size(18, 18).autoWidthOnTextChange(true).build());
        }
        header().addLeft("resources.png", () -> {
            TerminalSession info = getCurrentInfo();
            if (info != null && info.getResourceContainer() != null) info.getResourceContainer().openInstanceResources();
        }, "Resources");
        header().addLeft("reverse.png", () -> {
            TabContext context = getActiveContext();
            if (context != null) screenHost().startReProxy(context.instance, () -> onViewChanged(context, null));
        }, "Start ReProxy");
        header().addLeft("closeReverse.png", () -> {
            TabContext context = getActiveContext();
            if (context != null) screenHost().stopReProxy(context.instance, () -> onViewChanged(context, null));
        }, "Stop ReProxy");
        header().addLeft("download.png", () -> {
            TerminalSession info = getCurrentInfo();
            if (info != null && info.getResourceContainer() != null) info.getResourceContainer().showUpdateAllDialog();
        }, "Update All Resources");
        headerSearchMode = new SearchMode(false);
        headerSearchMode.setPlaceholder("Search Logs...");
        headerSearchMode.setSearchTarget(this::getActiveTerminalWidget);
        resourcesSearchMode = new SearchMode(false);
        resourcesSearchMode.setPlaceholder("Search Resources...");
        resourcesSearchMode.setOnTextChange(query -> {
            TerminalSession info = getCurrentInfo();
            if (info != null && info.getResourceContainer() != null && header().liveUpdate) info.getResourceContainer().search(query);
        });
        resourcesSearchMode.setOnSearchEnter(query -> {
            TerminalSession info = getCurrentInfo();
            if (info != null && info.getResourceContainer() != null) info.getResourceContainer().search(query);
        });
        playersSearchMode = new SearchMode(false);
        playersSearchMode.setPlaceholder("Search Players...");
        playersSearchMode.setOnTextChange(query -> {
            TerminalSession info = getCurrentInfo();
            if (info != null && info.getPlayersContainer() != null && header().liveUpdate) info.getPlayersContainer().search(query);
        });
        playersSearchMode.setOnSearchEnter(query -> {
            TerminalSession info = getCurrentInfo();
            if (info != null && info.getPlayersContainer() != null) info.getPlayersContainer().search(query);
        });
        header().setSearchMode(headerSearchMode, true);
        header().build();
    }

    protected void setupTabs() {
        tabs().builder()
            .position(5, 35).size(width - 10, 18)
            .allowAdd(true).allowClose(true).allowReorder(true).allowRename(true)
            .onPlusButtonClicked(this::addNewTerminalTab)
            .onTabSelected(this::onTabSelected)
            .onTabClosed(this::onTabClosed)
            .onTabsReordered(this::onTabsReordered)
            .onTabRenamed(this::onTabRenamed)
            .build();

        NewTerminalTargetProvider targetProvider = screenHost().newTerminalTargetProvider();
        List<Object> tabStore = getTabStore();
        List<NewTerminalTargetProvider.Tab> canonicalTabs = new ArrayList<>();
        for (Object entry : tabStore) {
            Object canonical = canonicalTab(entry);
            if (canonical == null || !targetProvider.supports(canonical)) continue;
            boolean duplicate = canonicalTabs.stream().anyMatch(existing -> sameTab(existing.target(), canonical));
            if (!duplicate) canonicalTabs.add(new NewTerminalTargetProvider.Tab(canonical, detailsTarget(canonical).name()));
        }
        Object requestedInstance = canonicalTab(initialInstanceToOpen);
        if (requestedInstance != null && !targetProvider.supports(requestedInstance)) requestedInstance = null;
        List<Object> canonicalTargets = canonicalTabs.stream().map(NewTerminalTargetProvider.Tab::target).toList();
        int activeIndex = requestedInstance == null ? getSavedTabIndex() : indexOfInstance(canonicalTargets, requestedInstance);
        if (requestedInstance != null && activeIndex < 0) {
            canonicalTabs.add(new NewTerminalTargetProvider.Tab(requestedInstance, detailsTarget(requestedInstance).name()));
            activeIndex = canonicalTabs.size() - 1;
        } else if (requestedInstance != null) {
            NewTerminalTargetProvider.Tab existing = canonicalTabs.get(activeIndex);
            canonicalTabs.set(activeIndex, new NewTerminalTargetProvider.Tab(requestedInstance, existing.name()));
        } else if (canonicalTabs.isEmpty()) {
            String localTerminalId = UUID.randomUUID().toString();
            if (targetProvider.supports(localTerminalId)) {
                canonicalTabs.add(new NewTerminalTargetProvider.Tab(localTerminalId, "Terminal"));
                activeIndex = 0;
            } else {
                activeIndex = -1;
            }
        }
        if (activeIndex < 0 || activeIndex >= canonicalTabs.size()) {
            activeIndex = 0;
        }
        NewTerminalTargetProvider.State current = new NewTerminalTargetProvider.State(canonicalTabs, activeIndex);
        if (requestedInstance != null) {
            applyTerminalState(targetProvider, current);
            return;
        }
        terminalRestoreRequest = targetProvider.restore(current);
        Async<NewTerminalTargetProvider.State> request = terminalRestoreRequest;
        if (request == null) {
            applyTerminalState(targetProvider, current);
        } else if (request.isDone()) {
            Throwable failure = request.failure();
            applyTerminalState(targetProvider, failure == null ? request.value() : current);
        } else {
            request.whenComplete((state, failure) -> screenHost().application().execute(() -> {
                if (closed || terminalRestoreRequest != request) return;
                terminalRestoreRequest = null;
                if (failure != null) host().notify("Terminal", message(failure), ReSyncNotificationLevel.WARN);
                applyTerminalState(targetProvider, failure == null ? state : current);
            }));
        }
    }

    private void applyTerminalState(NewTerminalTargetProvider provider, NewTerminalTargetProvider.State state) {
        if (closed || !tabs().getTabs().isEmpty()) return;
        terminalRestoreRequest = null;
        List<NewTerminalTargetProvider.Tab> restored = state == null ? List.of() : state.tabs().stream()
                .filter(tab -> provider.supports(tab.target()))
                .filter(tab -> tab.target() instanceof String || screenHost().serverView(tab.target()) != null)
                .toList();
        List<Object> tabStore = getTabStore();
        tabStore.clear();
        tabStore.addAll(restored.stream().map(NewTerminalTargetProvider.Tab::target).toList());
        int activeIndex = restored.isEmpty() ? -1 : Math.clamp(state.activeIndex(), 0, restored.size() - 1);
        for (int i = 0; i < restored.size(); i++) {
            NewTerminalTargetProvider.Tab tab = restored.get(i);
            createAndAddTab(tab.target(), false, i == activeIndex, tab.name());
        }
        if (activeIndex >= 0 && activeIndex < tabs().getTabs().size()) {
            tabs().setActiveTab(activeIndex);
        } else if (!tabs().getTabs().isEmpty()) {
            tabs().setActiveTab(0);
        }
        if (!tabStore.isEmpty()) persistTerminalState();
    }

    private List<Object> getTabStore() {
        return remotelyClient.getMultiTerminalTabs();
    }

    private int getSavedTabIndex() {
        return remotelyClient.getActiveMultiTerminalTabIndex();
    }

    private void setSavedTabIndex(int index) {
        remotelyClient.setActiveMultiTerminalTabIndex(index);
    }

    private void createAndAddTab(Object tabInfo, boolean setActive) {
        createAndAddTab(tabInfo, setActive, true, "");
    }

    private void createAndAddTab(Object tabInfo, boolean setActive, boolean initialize) {
        createAndAddTab(tabInfo, setActive, initialize, "");
    }

    private void createAndAddTab(Object tabInfo, boolean setActive, boolean initialize, String savedName) {
        Object inst = tabInfo instanceof String ? null : tabInfo;
        String localId = (tabInfo instanceof String) ? (String) tabInfo : null;
        String name = savedName == null || savedName.isBlank() ? inst != null ? detailsTarget(inst).name() : "Terminal" : savedName;
        if (inst == null && localId == null) {
            long count = contextInfos.values().stream().filter(TerminalSession::isLocalTerminalMode).count() + 1;
            name = "Terminal " + count;
        }

        int statusPad = inst != null ? 15 : 0;
        Container main = createContainer("root", 5, 60, width - 10, height - 65 - statusPad);
        main.layout(new ManagedLayout()).backgroundDrawing(true).disableScissorRegion(false).verticalSpacing(14).padding(0).setRelativeScissor(-1, -1, -1, -3);

        TabContext ctx = inst != null ? new ServerTabStatusContext(inst, tabInfo) : new TabContext(null, tabInfo);
        ctx.mainContainer = main;

        if (initialize) {
            initializeTabContext(ctx, tabInfo, inst, localId, statusPad);
            restoreBrowserView(ctx);
        }

        contextInfos.put(ctx, initialize ? remotelyClient.getSessionManager().getSession(tabInfo) : null);
        TabsManager.Tab tab = tabs().addTab(name, main);
        tab.setData(tabInfo);
        registerTab(tab, ctx);
        if (ctx instanceof TabStatusContext statusContext) {
            registerStatusContext(tab, statusContext);
        }

        if (setActive) {
            tabs().setActiveTab(tabs().getTabs().size() - 1);
        }
    }

    private TerminalSession initializeTabContext(TabContext ctx) {
        if (ctx == null) return null;
        TerminalSession existing = contextInfos.get(ctx);
        if (existing != null && existing.getTerminalWidget() != null) {
            bindSessionHost(existing);
            configureTerminalInput(ctx.instance, existing.getTerminalWidget());
            return existing;
        }

        Object tabInfo = ctx.id;
        Object inst = ctx.instance;
        String localId = (tabInfo instanceof String) ? (String) tabInfo : null;
        int statusPad = inst != null ? 15 : 0;
        TerminalSession info = initializeTabContext(ctx, tabInfo, inst, localId, statusPad);
        contextInfos.put(ctx, info);
        return info;
    }

    private TerminalSession initializeTabContext(TabContext ctx, Object tabInfo, Object inst, String localId, int statusPad){
        TerminalSession info = remotelyClient.getSessionManager().getSession(tabInfo);
        if (info == null) info = remotelyClient.getSessionManager().createSession(tabInfo, inst, localId);
        if (info == null) return null;
        if (info.getTerminalWidget() != null) {
            info.setInstance(inst);
            bindSessionHost(info);
            configureTerminalInput(inst, info.getTerminalWidget());
            if (ctx.views.isEmpty()) {
                ctx.addView(info.getTerminalWidget(), "terminal.png", "Terminal", null);
                if (info.getResourceContainer() != null) {
                    ResourceContainerAdapter resources = info.getResourceContainer();
                    List<AnimatedWidget> tools = resources.selectorsRow() == null ? List.of() : List.of(resources.selectorsRow());
                    ctx.addView(resources.widget(), "resources.png", "Resources", tools);
                }
                if (info.getPlayersContainer() != null) ctx.addView(info.getPlayersContainer(), "steve.png", "Players", null);
            }
            return info;
        }
        ServerModels.ClientServerView server = screenHost().serverView(inst);
        TerminalWidget terminal = server == null
                ? screenHost().createLocalTerminal(localId, 5, 60, width - 10, height - 66)
                : screenHost().createTerminal(remotelyClient.getApiClient(), server, localId, 5, 60,
                width - 10, height - 66 - statusPad, null);
        if (terminal == null) return info;
        terminal.entranceAnimationEnabled = false;
        info.setTerminalWidget(terminal);
        terminal.start();
        if (server != null) {
            screenHost().attachTerminal(inst, terminal);
            ResourceContainerAdapter resources = screenHost().createResourceContainer(this, inst, server,
                    5, 60, width - 10, height - 66 - statusPad);
            if (resources != null) info.setResourceContainer(resources);
            PlayersContainer players = new PlayersContainer(this, inst, terminal, 5, 60,
                    width - 10, height - 66 - statusPad, screenHost().capabilities(server));
            info.setPlayersContainer(players);
            setupTerminalListeners(inst, info);
        }
        screenHost().configureTerminalInput(remotelyClient.getApiClient(), server, terminal);
        if (ctx.views.isEmpty()) {
            ctx.addView(terminal, "terminal.png", "Terminal", null);
            if (info.getResourceContainer() != null) {
                ResourceContainerAdapter resources = info.getResourceContainer();
                List<AnimatedWidget> tools = resources.selectorsRow() == null ? List.of() : List.of(resources.selectorsRow());
                ctx.addView(resources.widget(), "resources.png", "Resources", tools);
            }
            if (info.getPlayersContainer() != null) ctx.addView(info.getPlayersContainer(), "steve.png", "Players", null);
        }
        return info;
    }

    private void bindSessionHost(TerminalSession info) {
        if (info.getResourceContainer() != null) info.getResourceContainer().setHost(this);
        if (info.getPlayersContainer() != null) info.getPlayersContainer().setHost(this);
    }

    private void setupTerminalListeners(Object inst, TerminalSession info){
        if (inst == null || info == null) return;
        if (info.getStandardParser() != null) {
            screenHost().detachOutputParser(inst, info.getStandardParser());
            info.setStandardParser(null);
        }
        if (info.getStreamDataParser() != null) {
            screenHost().detachOutputParser(inst, info.getStreamDataParser());
            info.setStreamDataParser(null);
        }
        if (info.getDataStream() != null) {
            info.getDataStream().stop();
            info.setDataStream(null);
        }

        boolean standardEnabled = Boolean.parseBoolean(detailsTarget(inst).setting(
                "provider.standard.enabled", "true"));
        String priority = detailsTarget(inst).setting("provider.priority.lifecycle", "msmp,standard");
        if (standardEnabled && priority.contains("standard")) {
            StandardOutputStateParser parser = screenHost().standardParser(inst);
            if (parser != null) {
                screenHost().attachOutputParser(inst, parser);
                info.setStandardParser(parser);
            }
        }
        StreamDataParser streamParser = new StreamDataParser(info);
        ServerModels.ClientServerView server = screenHost().serverView(inst);
        ServerScreenHost.TerminalDataStream stream = server == null
                ? screenHost().dataStream(inst, streamParser)
                : screenHost().terminalDataStream(remotelyClient.getApiClient(), server);
        if (stream == null) stream = screenHost().dataStream(inst, streamParser);
        if (stream != null) {
            info.setStreamDataParser(streamParser);
            info.setDataStream(stream);
            stream.stream(streamParser);
        }
    }

    protected void onViewChanged(TabContext context, ViewEntry activeView){
        if (context == null) return;
        TerminalSession info = contextInfos.get(context);
        if (info == null) return;
        boolean server = !info.isLocalTerminalMode() && context.instance != null;
        if (server) {
            screenHost().application().setServerActivity(context.instance, viewName(activeView));
        } else {
            screenHost().application().setLocalTerminalActivity();
        }
        boolean panel = server && screenHost().isPanel(context.instance);
        DevelopmentTabState development = developmentTabs.get(context);
        boolean developmentAvailable = development != null || server && screenHost().supportsDevelopment(context.instance);
        header().setButtonVisible("explorer.png", server);
        header().setButtonVisible("edit.png", server && !panel);
        header().setButtonVisible("merge.png", !panel && developmentAvailable);
        bindCapabilityListener(context, server ? screenHost().serverView(context.instance) : null);
        boolean reProxyAvailable = server && screenHost().supportsReProxy(context.instance);
        header().setButtonVisible("reverse.png", reProxyAvailable && !screenHost().isReProxyForwarded(context.instance));
        header().setButtonVisible("closeReverse.png", reProxyAvailable && screenHost().isReProxyForwarded(context.instance));
        if (reProxyAvailable) {
            screenHost().refreshReProxy(context.instance, () -> screenHost().application().execute(() -> {
                if (closed || !tabContexts.containsValue(context)) return;
                boolean forwarded = screenHost().isReProxyForwarded(context.instance);
                header().setButtonVisible("reverse.png", !forwarded);
                header().setButtonVisible("closeReverse.png", forwarded);
            }));
        }
        if (developmentModeToggle != null) {
            developmentModeToggle.setVisible(developmentTabs.containsKey(context));
            if (development != null) updateDevelopmentModeToggle(development.localActive);
        }
        boolean resourceView = info != null && info.getResourceContainer() != null && activeView != null
                && info.getResourceContainer().widget() == activeView.widget();
        header().setButtonVisible("resources.png", info.getResourceContainer() != null && server);
        boolean resourceUpdates = resourceView && info.getResourceContainer().capability(ResourceContainerAdapter.CAPABILITY_UPDATE_SELECTION).available();
        header().setButtonVisible("download.png", resourceUpdates);
        if (info != null && info.getResourceContainer() != null) {
            info.getResourceContainer().setSelectorsVisible(resourceView);
            if (resourceView) info.getResourceContainer().ensureSelectorsSynced();
            if (!resourceView) info.getResourceContainer().resetLoadingState();
        }
        updateStartButton(context, info);
        if (activeView == null || info == null) return;
        ResourceContainerAdapter resources = info.getResourceContainer();
        if (resources != null && resources.widget() == activeView.widget()) {
            header().setSearchMode(resourcesSearchMode, true);
            if (header().searchBox != null) resources.search(header().searchBox.getText());
            resources.setSelectorsVisible(true);
            resources.loadResources();
        } else if (activeView.widget() instanceof TerminalWidget terminal) {
            String query = header().searchBox == null ? "" : header().searchBox.getText();
            terminal.setFocused(true);
            setFocusedWidget(terminal);
            header().setSearchMode(headerSearchMode, true);
            headerSearchMode.search(query);
        } else if (info.getPlayersContainer() != null && info.getPlayersContainer() == activeView.widget()) {
            header().setSearchMode(playersSearchMode, true);
            if (header().searchBox != null) info.getPlayersContainer().search(header().searchBox.getText());
            info.getPlayersContainer().fullRefresh();
        } else {
            header().setSearchMode(null, false);
        }
    }

    protected void onTabSelected(TabsManager.Tab tab){
        if (tab == null) return;
        for (TerminalSession session : contextInfos.values()) {
            if (session != null && session.getResourceContainer() != null) {
                session.getResourceContainer().setSelectorsVisible(false);
            }
        }
        for (TabContext candidate : tabContexts.values()) {
            if (candidate instanceof ServerTabStatusContext statusContext) statusContext.invalidateMetricsRequest();
            for (ViewEntry view : candidate.views) {
                view.loadedToolbarWidgets().forEach(widget -> widget.setVisible(false));
            }
        }
        TabContext context = tabContexts.get(tab);
        if (context == null) return;

        if (instance != null) screenHost().removeStateListener(instance, stateListener);
        if (viewSwitcher != null) {
            viewSwitcher.cleanup();
            viewSwitcher = null;
        }

        instance = context.instance;
        setActiveContainer(context.mainContainer);
        TerminalSession info = initializeTabContext(context);
        if (context.instance != null) {
            screenHost().addStateListener(context.instance, stateListener);
            screenHost().setViewContext(detailsTarget(context.instance).id());
            onStateChanged(screenHost().state(context.instance));
            screenHost().onServerTabSelected(context.instance);
        } else if (info != null) {
            screenHost().setViewContext(info.getLocalTerminalId());
        }

        if (context.views.size() > 1) {
            viewSwitcher = new ViewSwitcherWidget(this, context.mainContainer);
            for (ViewEntry view : context.views) {
                viewSwitcher.register(view.icon(), view.hint(), () -> view.isLoaded() ? view.widget() : context.mainContainer);
            }
            viewSwitcher.setOnChange(index -> onViewSwitched(context, index));
            viewSwitcher.build();
            viewSwitcher.getWidget().entranceAnimationEnabled = !tabs().previousTabs.contains(tab) || resetAnimForView;
            viewSwitcher.getWidget().recreateButtons();
            int index = Math.clamp(context.selectedViewIndex, 0, context.views.size() - 1);
            if (index == 0) onViewSwitched(context, index);
            else viewSwitcher.setActiveIndex(index);
        } else if (!context.views.isEmpty()) {
            onViewSwitched(context, 0);
        } else {
            onViewChanged(context, null);
            updatePositions();
        }

        if (context.instance != null && info != null && !info.isLocalTerminalMode()) {
            screenHost().reloadInstanceSettings(context.instance, !screenHost().isLocal(context.instance)).whenComplete((ignored, failure) -> {
                screenHost().application().execute(() -> {
                    if (failure == null && isServerContextAvailable(context, info)) {
                        setupTerminalListeners(context.instance, info);
                        if (info.getPlayersContainer() != null) info.getPlayersContainer().fullRefresh();
                    }
                });
            });
        }
        setSavedTabIndex(tabs().getActiveTabIndex());
        applyStatusBarForActiveTab();
        if (developmentPanel != null) {
            DevelopmentTabState development = developmentTabs.get(context);
            if (development == null) developmentPanel.activeServerChanged(context.instance);
            else developmentPanel.developmentModeChanged(development.remote, development.local, development.localActive);
        }
        persistBrowserViewContext(context);
        persistTerminalState();
    }

    private void onTabClosed(TabsManager.Tab tab){
        TabContext context = tabContexts.remove(tab);
        if (context == null) return;
        if (context instanceof ServerTabStatusContext statusContext) statusContext.invalidateMetricsRequest();
        if (serverHealthPopupContext == context) hideServerHealthPopup();
        cleanupTarget(context.instance);
        clearPerServerState(context.instance);
        if (context.capabilityProvider != null && context.capabilityListener != null) {
            context.capabilityProvider.removeCapabilityListener(context.capabilityListener);
        }
        TerminalSession info = contextInfos.remove(context);
        if (info != null && remotelyClient != null) remotelyClient.getSessionManager().destroySession(info.getTabId());
        DevelopmentTabState development = developmentTabs.remove(context);
        if (development != null) {
            cleanupTarget(development.remote);
            cleanupTarget(development.local);
            clearPerServerState(development.remote);
            clearPerServerState(development.local);
            if (remotelyClient != null) {
                if (development.remoteSession != null && development.remoteSession != info) remotelyClient.getSessionManager().destroySession(development.remoteSession.getTabId());
                if (development.localSession != null && development.localSession != info) remotelyClient.getSessionManager().destroySession(development.localSession.getTabId());
            }
        }
        statusContexts.remove(tab);
        syncTabStoreFromTabs();
        if (tabs().getTabs().isEmpty()) {
            screenHost().application().execute(() -> {
                if (!closed && tabs().getTabs().isEmpty()) closeScreen();
            });
        }
        else {
            setSavedTabIndex(tabs().getActiveTabIndex());
            persistBrowserViewContext(getActiveContext());
        }
    }

    private void bindCapabilityListener(TabContext context, ServerModels.ClientServerView server) {
        ServerUiCapabilityProvider provider = server == null ? null : screenHost().capabilities(server);
        if (context.capabilityProvider == provider) return;
        if (context.capabilityProvider != null && context.capabilityListener != null) {
            context.capabilityProvider.removeCapabilityListener(context.capabilityListener);
        }
        context.capabilityProvider = provider;
        context.capabilityListener = null;
        if (provider == null) return;
        Runnable listener = () -> screenHost().application().execute(() -> {
            if (closed || !tabContexts.containsValue(context)) return;
            ViewEntry active = context.views.isEmpty() ? null
                    : context.views.get(Math.clamp(context.selectedViewIndex, 0, context.views.size() - 1));
            onViewChanged(context, active);
        });
        context.capabilityListener = listener;
        provider.addCapabilityListener(listener);
    }

    private void onTabRenamed(TabsManager.Tab tab){
        TabContext context = tab == null ? null : tabContexts.get(tab);
        if (context == null || context.instance == null) return;
        DevelopmentTabState development = developmentTabs.get(context);
        Object target = development == null ? context.instance : development.remote;
        String previousName = detailsTarget(target).name();
        String name = tab.getName();
        screenHost().renameInstance(target, name)
                .exceptionally(failure -> {
                    screenHost().application().execute(() -> {
                        if (tabContexts.get(tab) == context) {
                            tab.setName(previousName);
                            persistTerminalState();
                        }
                        host().notify("Rename Failed", message(failure), ReSyncNotificationLevel.WARN);
                    });
                    return null;
                });
        persistTerminalState();
    }

    private void onTabsReordered(List<TabsManager.Tab> newOrder) {
        syncTabStoreFromTabs(newOrder);
        setSavedTabIndex(tabs().getActiveTabIndex());
        persistBrowserViewContext(getActiveContext());
    }

    private void syncTabStoreFromTabs() {
        syncTabStoreFromTabs(tabs().getTabs());
    }

    private void syncTabStoreFromTabs(List<TabsManager.Tab> tabOrder) {
        List<Object> newTabOrder = new ArrayList<>();
        for (TabsManager.Tab tab : tabOrder) {
            TabContext context = tabContexts.get(tab);
            if (context != null) {
                DevelopmentTabState development = developmentTabs.get(context);
                newTabOrder.add(development != null ? development.remote : context.instance != null ? context.instance : context.id);
            }
        }
        List<Object> tabStore = getTabStore();
        tabStore.clear();
        tabStore.addAll(newTabOrder);
        persistTerminalState();
    }

    private void persistTerminalState() {
        NewTerminalTargetProvider provider = screenHost().newTerminalTargetProvider();
        List<NewTerminalTargetProvider.Tab> persisted = new ArrayList<>();
        for (TabsManager.Tab tab : tabs().getTabs()) {
            TabContext context = tabContexts.get(tab);
            if (context == null) continue;
            DevelopmentTabState development = developmentTabs.get(context);
            Object target = development != null ? development.remote : context.instance != null ? context.instance : context.id;
            if (target != null && provider.supports(target)) persisted.add(new NewTerminalTargetProvider.Tab(target, tab.getName()));
        }
        provider.persist(new NewTerminalTargetProvider.State(persisted, tabs().getActiveTabIndex()));
    }

    private void addNewTerminalTab() {
        if (newTerminalTargetRequest != null) return;
        NewTerminalTargetProvider provider = screenHost().newTerminalTargetProvider();
        long generation = ++newTerminalTargetGeneration;
        Async<Object> request;
        try {
            request = provider.newTarget(List.copyOf(getTabStore()));
        } catch (RuntimeException exception) {
            host().notify("Terminal", message(exception), ReSyncNotificationLevel.WARN);
            return;
        }
        if (request == null) {
            host().notify("Terminal", "Terminal Target Is Unavailable", ReSyncNotificationLevel.WARN);
            return;
        }
        newTerminalTargetRequest = request;
        if (request.isDone()) {
            Throwable failure = request.failure();
            completeNewTerminalTarget(provider, request, generation, failure == null ? request.value() : null, failure);
        } else {
            request.whenComplete((target, failure) -> screenHost().application().execute(() ->
                    completeNewTerminalTarget(provider, request, generation, target, failure)));
        }
    }

    private void completeNewTerminalTarget(NewTerminalTargetProvider provider, Async<Object> request, long generation,
                                           Object target, Throwable failure) {
        if (generation != newTerminalTargetGeneration || newTerminalTargetRequest != request || closed) return;
        newTerminalTargetRequest = null;
        if (failure != null) {
            host().notify("Terminal", message(failure), ReSyncNotificationLevel.WARN);
            return;
        }
        if (target == null || !provider.supports(target)) {
            host().notify("Terminal", "Terminal Target Is Unavailable", ReSyncNotificationLevel.WARN);
            return;
        }
        if (target instanceof String id) {
            getTabStore().add(id);
            createAndAddTab(id, true);
            persistTerminalState();
        } else {
            addInstanceTab(target);
        }
    }

    private void cancelNewTerminalTargetRequest() {
        newTerminalTargetGeneration++;
        Async<Object> request = newTerminalTargetRequest;
        newTerminalTargetRequest = null;
        if (request != null) request.cancel();
    }

    public void addInstanceTab(Object instanceToAdd) {
        Object canonicalInstance = developmentSource(instanceToAdd);
        if (canonicalInstance == null) return;
        NewTerminalTargetProvider provider = screenHost().newTerminalTargetProvider();
        Async<Object> request = provider.resolve(canonicalInstance);
        if (request == null) return;
        if (request.isDone()) {
            if (request.failure() == null) addResolvedInstanceTab(provider, request.value());
        } else {
            request.whenComplete((target, failure) -> screenHost().application().execute(() -> {
                if (!closed && failure == null) addResolvedInstanceTab(provider, target);
            }));
        }
    }

    private void addResolvedInstanceTab(NewTerminalTargetProvider provider, Object canonicalInstance) {
        if (canonicalInstance == null || !provider.supports(canonicalInstance)) return;
        List<TabsManager.Tab> openTabs = tabs().getTabs();
        for (int i = 0; i < openTabs.size(); i++) {
            TabContext context = tabContexts.get(openTabs.get(i));
            DevelopmentTabState development = context == null ? null : developmentTabs.get(context);
            if (context != null && (sameInstance(context.instance, canonicalInstance)
                    || development != null && (sameInstance(development.remote, canonicalInstance) || sameInstance(development.local, canonicalInstance)))) {
                tabs().setActiveTab(i);
                setSavedTabIndex(i);
                persistTerminalState();
                return;
            }
        }
        List<Object> tabStore = getTabStore();
        if (tabStore.stream().noneMatch(tab -> tab instanceof Object instance && sameInstance(instance, canonicalInstance))) {
            tabStore.add(canonicalInstance);
        }
        createAndAddTab(canonicalInstance, true);
        persistTerminalState();
    }

    @Override
    public void openDevelopmentTab(Object remote, Object local) {
        if (remote == null || local == null) return;
        addInstanceTab(remote);
        TabsManager.Tab tab = tabs().getActiveTab();
        TabContext context = tab == null ? null : tabContexts.get(tab);
        if (context == null) return;
        DevelopmentTabState state = developmentTabs.get(context);
        if (state == null || !sameInstance(state.remote, remote) || !sameInstance(state.local, local)) {
            TerminalSession remoteSession = contextInfos.get(context);
            TabContext localContext = new ServerTabStatusContext(local, local);
            localContext.mainContainer = context.mainContainer;
            TerminalSession localSession = initializeTabContext(localContext, local, local, null, 15);
            state = new DevelopmentTabState(remote, local, new ArrayList<>(context.views), new ArrayList<>(localContext.views), remoteSession, localSession, context.selectedViewIndex);
            developmentTabs.put(context, state);
            tab.setData(remote);
            tab.setName(detailsTarget(remote).name());
        }
        switchDevelopmentMode(context, tab, state, true);
    }

    void setDevelopmentLocal(boolean local) {
        TabsManager.Tab tab = tabs().getActiveTab();
        TabContext context = tab == null ? null : tabContexts.get(tab);
        DevelopmentTabState state = context == null ? null : developmentTabs.get(context);
        if (state != null) switchDevelopmentMode(context, tab, state, local);
    }

    @Override
    public boolean isActiveDevelopment(Object remote) {
        TabContext context = getActiveContext();
        DevelopmentTabState state = context == null ? null : developmentTabs.get(context);
        return state != null && sameInstance(state.remote, remote);
    }

    private void switchDevelopmentMode(TabContext context, TabsManager.Tab tab, DevelopmentTabState state, boolean local) {
        if (state.localActive == local && sameInstance(context.instance, local ? state.local : state.remote)) return;
        if (context instanceof ServerTabStatusContext statusContext) statusContext.invalidateMetricsRequest();
        if (context.instance != null) screenHost().removeStateListener(context.instance, stateListener);
        for (ViewEntry view : context.views) {
            List<AnimatedWidget> tools = view.loadedToolbarWidgets();
            if (tools != null) tools.forEach(tool -> tool.setVisible(false));
        }
        if (state.localActive) state.localView = context.selectedViewIndex;
        else state.remoteView = context.selectedViewIndex;
        state.localActive = local;
        context.instance = local ? state.local : state.remote;
        context.id = state.remote;
        context.views.clear();
        context.views.addAll(local ? state.localViews : state.remoteViews);
        context.selectedViewIndex = Math.clamp(local ? state.localView : state.remoteView, 0, Math.max(0, context.views.size() - 1));
        contextInfos.put(context, local ? state.localSession : state.remoteSession);
        tab.setData(state.remote);
        tab.setName(detailsTarget(state.remote).name());
        updateDevelopmentModeToggle(local);
        onTabSelected(tab);
    }

    private void updateDevelopmentModeToggle(boolean local) {
        if (developmentModeToggle == null) return;
        applyingDevelopmentMode = true;
        developmentModeToggle.setValue(local);
        developmentModeToggle.setMessage(local ? "Local" : "Remote");
        developmentModeToggle.setAccent(ThemeManager.getDefaultAccent());
        developmentModeToggle.setVisible(true);
        applyingDevelopmentMode = false;
    }

    private Object developmentSource(Object candidate){
        return screenHost().developmentSource(candidate);
    }

    private int indexOfInstance(List<Object> entries, Object candidate) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof Object instance && sameInstance(instance, candidate)) return i;
        }
        return -1;
    }

    @Override
    public void closeInstanceTab(Object instanceToClose) {
        if (instanceToClose == null) return;
        List<TabsManager.Tab> openTabs = tabs().getTabs();
        for (int i = 0; i < openTabs.size(); i++) {
            TabContext context = tabContexts.get(openTabs.get(i));
            DevelopmentTabState development = context == null ? null : developmentTabs.get(context);
            if (context != null && (sameInstance(context.instance, instanceToClose)
                    || development != null && (sameInstance(development.remote, instanceToClose) || sameInstance(development.local, instanceToClose)))) {
                tabs().removeTab(i);
                return;
            }
        }
    }

    private boolean sameInstance(Object a, Object b){
        if (a == b) return true;
        if (a instanceof String || b instanceof String) return Objects.equals(a, b);
        String aId = detailsTarget(a).id();
        String bId = detailsTarget(b).id();
        return !aId.isBlank() && aId.equals(bId);
    }

    private boolean sameTab(Object a, Object b) {
        return a instanceof String || b instanceof String ? Objects.equals(a, b) : sameInstance(a, b);
    }

    private Object canonicalTab(Object candidate) {
        return candidate instanceof String ? candidate : developmentSource(candidate);
    }

    private void launchOrStopInstance() {
        launchOrStopInstance(true);
    }

    private void launchOrStopInstance(boolean allowRestart){
        TabContext context = getActiveContext();
        TerminalSession info = context == null ? null : contextInfos.get(context);
        if (context == null || context.instance == null || info == null || info.isLocalTerminalMode()) return;
        ServerScreenHost.ServerState state = stateOf(context.instance);
        if (allowRestart && hasShiftDown() && state == ServerScreenHost.ServerState.RUNNING) {
            restartInstance(context, contextInfos.get(context));
            return;
        }
        if (state == ServerScreenHost.ServerState.STOPPING) {
            if (isKilling(context.instance)) return;
            ServerUiCapabilityProvider.Availability kill = screenHost().killAvailability(context.instance);
            if (!kill.available()) {
                screenHost().application().notify("Kill Server", kill.reason().isBlank() ? "Server Kill Is Unavailable" : kill.reason(), ReSyncNotificationLevel.WARN);
                return;
            }
            if (!isKillConfirmationActive(context.instance)) {
                armKillConfirmation(context.instance);
                updateStartButton(context, info);
                return;
            }
            killStoppingServer(context, info);
            return;
        }
        if (state == ServerScreenHost.ServerState.RUNNING || state == ServerScreenHost.ServerState.STARTING) {
            stopReProxyIfForwarded(context.instance);
            boolean localTerminal = screenHost().isLocal(context.instance) && info != null
                    && info.getTerminalWidget() instanceof ServerTerminalLifecycle;
            String operationId = screenHost().activeOperationId(context.instance);
            if (info != null && info.getTerminalWidget() instanceof ServerTerminalLifecycle lifecycle) {
                lifecycle.notifyStopRequested();
                operationId = screenHost().activeOperationId(context.instance);
            }
            if (operationId.isBlank()) operationId = screenHost().requestStop(context.instance);
            screenHost().setState(context.instance, ServerScreenHost.ServerState.STOPPING);
            if (localTerminal) {
                updateStartButton(context, info);
                return;
            }
            Async<Void> operation = screenHost().stopServer(remotelyClient.getApiClient(), context.instance);
            String requestedOperationId = operationId;
            ServerScreenHost.ServerState priorState = state;
            operation.whenComplete((ignored, failure) -> screenHost().application().execute(() -> {
                if (failure != null) {
                    screenHost().restoreRunning(context.instance, requestedOperationId, message(failure));
                    screenHost().setState(context.instance, priorState);
                    screenHost().application().notify("Stop Server", message(failure), ReSyncNotificationLevel.ERROR);
                }
                updateStartButton(context, contextInfos.get(context));
            }));
            return;
        }
        startInstance(context, info);
    }

    private void restartInstance(TabContext context, TerminalSession info){
        if (context == null || context.instance == null) return;
        ServerScreenHost.ServerState priorState = stateOf(context.instance);
        String key = killKey(context.instance);
        if (restartListeners.containsKey(key)) return;
        Consumer<ServerScreenHost.ServerState> listener = new Consumer<>() {
            private boolean stopping;

            @Override
            public void accept(ServerScreenHost.ServerState nextState) {
                if (nextState == ServerScreenHost.ServerState.STOPPING) {
                    stopping = true;
                    return;
                }
                if (!stopping || nextState != ServerScreenHost.ServerState.STOPPED && nextState != ServerScreenHost.ServerState.CRASHED) return;
                screenHost().removeStateListener(context.instance, this);
                restartListeners.remove(key);
                screenHost().application().execute(() -> startInstance(context, info));
            }
        };
        restartListeners.put(key, listener);
        screenHost().addStateListener(context.instance, listener);
        stopReProxyIfForwarded(context.instance);
        boolean localTerminal = screenHost().isLocal(context.instance) && info != null
                && info.getTerminalWidget() instanceof ServerTerminalLifecycle;
        String operationId = screenHost().activeOperationId(context.instance);
        if (info != null && info.getTerminalWidget() instanceof ServerTerminalLifecycle lifecycle) {
            lifecycle.notifyStopRequested();
            operationId = screenHost().activeOperationId(context.instance);
        }
        if (operationId.isBlank()) operationId = screenHost().requestStop(context.instance);
        screenHost().setState(context.instance, ServerScreenHost.ServerState.STOPPING);
        if (localTerminal) {
            updateStartButton(context, info);
            return;
        }
        String requestedOperationId = operationId;
        screenHost().stopServer(remotelyClient.getApiClient(), context.instance).whenComplete((ignored, failure) -> {
            screenHost().application().execute(() -> {
                if (failure == null) {
                    updateStartButton(context, info);
                    return;
                }
                screenHost().removeStateListener(context.instance, listener);
                restartListeners.remove(key);
                screenHost().restoreRunning(context.instance, requestedOperationId, message(failure));
                screenHost().setState(context.instance, priorState);
                screenHost().application().notify("Restart Server", message(failure), ReSyncNotificationLevel.ERROR);
                updateStartButton(context, info);
            });
        });
    }

    private void startInstance(TabContext context, TerminalSession info){
        if (!canContinueServerStart(context, info)) return;
        ServerModels.ClientServerView server = screenHost().serverView(context.instance);
        if (server == null) return;
        String healthKey = killKey(context.instance);
        long requestId = ++serverHealthRequestSequence;
        if (serverHealthChecksInFlight.putIfAbsent(healthKey, requestId) != null) return;
        screenHost().serverHealth(server).whenComplete((health, failure) -> screenHost().application().execute(() -> {
            if (!Objects.equals(serverHealthChecksInFlight.get(healthKey), requestId)) return;
            serverHealthChecksInFlight.remove(healthKey);
            if (!canContinueServerStart(context, info)) return;
            if (failure != null) {
                screenHost().application().notify("Health Check Failed", message(failure), ReSyncNotificationLevel.ERROR);
                return;
            }
            if (health == null) {
                screenHost().application().notify("Health Check Failed", "Server Health Unavailable", ReSyncNotificationLevel.ERROR);
                return;
            }
            if (!health.healthy()) {
                showServerHealthPopup(context, info, health);
                return;
            }
            proceedWithServerStart(context, info);
        }));
    }

    private boolean canContinueServerStart(TabContext context, TerminalSession info){
        return !closed && context != null && context.instance != null && info != null && tabContexts.containsValue(context)
                && contextInfos.get(context) == info
                && (stateOf(context.instance) == ServerScreenHost.ServerState.STOPPED
                || stateOf(context.instance) == ServerScreenHost.ServerState.CRASHED
                || stateOf(context.instance) == ServerScreenHost.ServerState.UNKNOWN);
    }

    private boolean isServerContextAvailable(TabContext context, TerminalSession info){
        return !closed && context != null && context.instance != null && info != null && tabContexts.containsValue(context)
                && contextInfos.get(context) == info;
    }

    private void hideServerHealthPopup() {
        if (serverHealthPopup != null) {
            serverHealthPopup.hide();
        }
        serverHealthPopup = null;
        serverHealthPopupContext = null;
    }

    private void hideServerHealthPopup(PopupWidget popup) {
        if (popup != null) {
            popup.hide();
        }
        if (serverHealthPopup == popup) {
            serverHealthPopup = null;
            serverHealthPopupContext = null;
        }
    }

    private void updateStartButton(TabContext context, TerminalSession info){
        if (startIconButton == null) return;
        boolean visible = info != null && !info.isLocalTerminalMode() && context != null && context.instance != null;
        startIconButton.setVisible(visible);
        if (!visible) return;
        ServerScreenHost.ServerState state = stateOf(context.instance);
        startIconButton.setActive(state != ServerScreenHost.ServerState.STOPPING || screenHost().killAvailability(context.instance).available());
        if (state == ServerScreenHost.ServerState.UNKNOWN || state == ServerScreenHost.ServerState.STOPPED
                || state == ServerScreenHost.ServerState.CRASHED) {
            clearKillConfirmation(context.instance);
            startIconButton.updateState(state.name());
        } else {
            if (state != ServerScreenHost.ServerState.STOPPING) clearKillConfirmation(context.instance);
            startIconButton.updateState(state.name(), state == ServerScreenHost.ServerState.STOPPING
                    && isKillConfirmationActive(context.instance), state == ServerScreenHost.ServerState.STOPPING
                    && isKilling(context.instance));
        }
        header().requestLayoutUpdate();
    }

    private void armKillConfirmation(Object instance) {
        if (instance == null) {
            return;
        }
        killConfirmInstanceId = killKey(instance);
        killConfirmUntilMs = System.currentTimeMillis() + KILL_CONFIRM_MS;
    }

    private boolean isKillConfirmationActive(Object instance) {
        if (instance == null || killConfirmInstanceId == null || System.currentTimeMillis() > killConfirmUntilMs) {
            return false;
        }
        return Objects.equals(killConfirmInstanceId, killKey(instance));
    }

    private boolean isKilling(Object instance) {
        return instance != null && killingInstanceId != null && Objects.equals(killingInstanceId, killKey(instance));
    }

    private void clearKillConfirmation(Object instance) {
        if (instance == null || Objects.equals(killConfirmInstanceId, killKey(instance))) {
            killConfirmInstanceId = null;
            killConfirmUntilMs = 0;
        }
        if (instance == null || Objects.equals(killingInstanceId, killKey(instance))) {
            killingInstanceId = null;
        }
    }

    private String killKey(Object instance) {
        if (instance == null) {
            return "";
        }
        String id = detailsTarget(instance).id();
        if (!id.isBlank()) return id;
        String path = detailsTarget(instance).path();
        return path.isBlank() ? String.valueOf(System.identityHashCode(instance)) : path;
    }

    private ServerScreenHost.ServerState stateOf(Object target) {
        ServerScreenHost.ServerState state = screenHost().state(target);
        return state == ServerScreenHost.ServerState.UNKNOWN ? detailsTarget(target).state() : state;
    }

    private void killStoppingServer(TabContext context, TerminalSession info){
        if (context == null || context.instance == null) return;
        killingInstanceId = killKey(context.instance);
        killConfirmInstanceId = null;
        killConfirmUntilMs = 0;
        stopReProxyIfForwarded(context.instance);
        updateStartButton(context, info);
        String operationId = screenHost().activeOperationId(context.instance);
        if (operationId.isBlank()) operationId = screenHost().requestStop(context.instance);
        screenHost().setState(context.instance, ServerScreenHost.ServerState.STOPPING);
        String requestedOperationId = operationId;
        screenHost().killServer(remotelyClient.getApiClient(), context.instance).whenComplete((ignored, failure) ->
                screenHost().application().execute(() -> {
                    if (failure != null) {
                        clearKillConfirmation(context.instance);
                        screenHost().restoreRunning(context.instance, requestedOperationId, message(failure));
                        screenHost().setState(context.instance, ServerScreenHost.ServerState.RUNNING);
                        screenHost().application().notify("Kill Server", message(failure), ReSyncNotificationLevel.ERROR);
                    }
                    updateStartButton(context, info);
                }));
    }

    private void stopReProxyIfForwarded(Object instance){
        if (instance != null && screenHost().isReProxyForwarded(instance)) screenHost().stopReProxy(instance, null);
    }

    private boolean isQuickServer(Object instance){
        return screenHost().isQuickServer(instance);
    }

    private boolean isQuickServerRuntimeOpen(Object instance){
        return isQuickServer(instance) && screenHost().isReProxyForwarded(instance)
                && screenHost().localPortOpen(instance);
    }

    private boolean isLocalPortOpen(Object target){
        return screenHost().localPortOpen(target);
    }

    private void showServerHealthPopup(TabContext context, TerminalSession info, ServerScreenHost.ServerHealth status){
        hideServerHealthPopup();
        if (context == null || status == null) return;
        ServerHealthPopupState state = new ServerHealthPopupState(context, info, status);
        serverHealthPopup = state.popup;
        serverHealthPopupContext = context;
        state.show();
    }

    private final class ServerHealthPopupState {
        private final TabContext context;
        private final TerminalSession info;
        private final String healthKey;
        private final PopupWidget popup;
        private final IconButton eulaButton;
        private final IconButton serverJarButton;
        private final IconButton startScriptButton;
        private final SquareButtonWidget eulaFixButton;
        private final SquareButtonWidget serverJarFixButton;
        private final SquareButtonWidget startScriptFixButton;
        private final IconButton launchButton;
        private final IconButton launchAnywayButton;
        private final ServerUiCapabilityProvider.Availability eulaRepairAvailability;
        private final ServerUiCapabilityProvider.Availability serverJarRepairAvailability;
        private final ServerUiCapabilityProvider.Availability startScriptRepairAvailability;
        private ServerScreenHost.PrerequisiteState eulaState;
        private ServerScreenHost.PrerequisiteState serverJarState;
        private ServerScreenHost.PrerequisiteState startScriptState;
        private ServerHealthRepair repair = ServerHealthRepair.NONE;

        private ServerHealthPopupState(TabContext context, TerminalSession info, ServerScreenHost.ServerHealth status) {
            this.context = context;
            this.info = info;
            healthKey = killKey(context.instance);
            eulaState = status.eula();
            serverJarState = status.serverJar();
            startScriptState = status.startScript();
            ServerModels.ClientServerView server = screenHost().serverView(context.instance);
            eulaRepairAvailability = screenHost().healthRepairAvailability(server, "eula");
            serverJarRepairAvailability = screenHost().healthRepairAvailability(server, "server-jar");
            startScriptRepairAvailability = screenHost().healthRepairAvailability(server, "start-script");

            PopupWidget.Builder builder = new PopupWidget.Builder("Server Health • " + detailsTarget(context.instance).name())
                    .width(350).setResizable(false).setAntiOutOfBound(true);
            popup = builder.getWidget();
            eulaButton = new IconButton.Builder().size(0, 18).active(false).inClickableWhenInactive(true)
                    .hint("Open Minecraft EULA").onClick(() -> screenHost().openExternal("https://www.minecraft.net/en-us/eula")).build();
            serverJarButton = new IconButton.Builder().size(0, 18).active(false).build();
            startScriptButton = new IconButton.Builder().size(0, 18).active(false).build();
            eulaFixButton = healthFixButton("Fix EULA", this::acceptEula);
            serverJarFixButton = healthFixButton("Fix Server Jar", this::downloadServerJar);
            startScriptFixButton = healthFixButton("Fix Start Script", this::createStartScript);
            launchButton = new IconButton.Builder().size(0, 18).label("Launch Server").imagePath("start.png")
                    .accentType(ThemeManager.getAccent("nice")).onClick(() -> launch(false)).build();
            launchAnywayButton = new IconButton.Builder().size(0, 18).label("Launch Anyway").imagePath("report.png")
                    .accentType(ThemeManager.getAccent("danger")).onClick(() -> launch(true)).build();
            builder.addRow(new PopupWidget.PopupRow.Builder("", eulaButton, eulaFixButton).gap(1).build());
            builder.addRow(new PopupWidget.PopupRow.Builder("", serverJarButton, serverJarFixButton).gap(1).build());
            builder.addRow(new PopupWidget.PopupRow.Builder("", startScriptButton, startScriptFixButton).gap(1).build());
            builder.addRow(new PopupWidget.PopupRow.Builder("", launchButton, launchAnywayButton).gap(1).build());
            builder.build();
            popup.onClose = () -> {
                if (serverHealthPopup == popup) {
                    serverHealthPopup = null;
                    serverHealthPopupContext = null;
                }
            };
            refresh();
        }

        private void show() {
            addDrawableChild(popup);
            popup.show();
        }

        private void refresh() {
            updateHealthButton(eulaButton, eulaFixButton, eulaState, ServerHealthRepair.EULA,
                    "EULA", "Accepted", "Needs Agreement", "Saving Agreement...", "info.png", eulaRepairAvailability);
            updateHealthButton(serverJarButton, serverJarFixButton, serverJarState, ServerHealthRepair.SERVER_JAR,
                    "Server Jar", "Ready", "Missing", "Downloading...", "java.png", serverJarRepairAvailability);
            updateHealthButton(startScriptButton, startScriptFixButton, startScriptState, ServerHealthRepair.START_SCRIPT,
                    "Start Script", "Ready", "Missing", "Creating...", "script.png", startScriptRepairAvailability);
            launchButton.setActive(repair == ServerHealthRepair.NONE && isHealthy());
            launchAnywayButton.setActive(repair == ServerHealthRepair.NONE);
        }

        private SquareButtonWidget healthFixButton(String hint, Runnable action) {
            return new SquareButtonWidget.Builder().size(18, 18).identifier(Identifier.icon("checkmark.png"))
                    .hint(hint).accentType(ThemeManager.getAccent("nice")).onClick(action).build();
        }

        private void updateHealthButton(IconButton button, SquareButtonWidget fixButton, ServerScreenHost.PrerequisiteState state,
                                        ServerHealthRepair target, String name, String readyLabel, String missingLabel,
                                        String progressLabel, String icon,
                                        ServerUiCapabilityProvider.Availability availability) {
            boolean repairing = repair == target;
            boolean available = availability == null || availability.available();
            String label = repairing ? progressLabel : switch (state) {
                case VERIFIED -> readyLabel;
                case FAILED -> missingLabel;
                case NOT_APPLICABLE -> "Not Applicable";
                case UNAVAILABLE -> "Unavailable";
            };
            button.setMessage(name + " • " + label);
            button.setIcon(icon);
            button.setAccent(state == ServerScreenHost.PrerequisiteState.VERIFIED ? ThemeManager.getAccent("nice")
                    : ThemeManager.getDefaultAccent());
            fixButton.setHint(available ? "Fix " + name : availability.reason());
            fixButton.setActive(state == ServerScreenHost.PrerequisiteState.FAILED
                    && repair == ServerHealthRepair.NONE && available);
        }

        private void acceptEula() {
            if (!beginRepair(ServerHealthRepair.EULA, eulaState)) return;
            screenHost().repairServer(screenHost().serverView(context.instance), "eula")
                    .whenComplete((ignored, error) -> completeRepair(ServerHealthRepair.EULA, error,
                            () -> eulaState = ServerScreenHost.PrerequisiteState.VERIFIED, "EULA Agreement Failed"));
        }

        private void downloadServerJar() {
            if (!beginRepair(ServerHealthRepair.SERVER_JAR, serverJarState)) return;
            screenHost().repairServer(screenHost().serverView(context.instance), "server-jar")
                    .whenComplete((ignored, error) -> completeRepair(ServerHealthRepair.SERVER_JAR, error,
                            () -> serverJarState = ServerScreenHost.PrerequisiteState.VERIFIED, "Server Jar Download Failed"));
        }

        private void createStartScript() {
            if (!beginRepair(ServerHealthRepair.START_SCRIPT, startScriptState)) return;
            screenHost().repairServer(screenHost().serverView(context.instance), "start-script")
                    .whenComplete((ignored, error) -> completeRepair(ServerHealthRepair.START_SCRIPT, error,
                            () -> startScriptState = ServerScreenHost.PrerequisiteState.VERIFIED, "Start Script Creation Failed"));
        }

        private boolean beginRepair(ServerHealthRepair target, ServerScreenHost.PrerequisiteState state) {
            if (!isServerContextAvailable(context, info)) {
                hideServerHealthPopup(popup);
                return false;
            }
            ServerUiCapabilityProvider.Availability availability = repairAvailability(target);
            if (state != ServerScreenHost.PrerequisiteState.FAILED || repair != ServerHealthRepair.NONE || !availability.available()) {
                if (state == ServerScreenHost.PrerequisiteState.FAILED && !availability.available()) {
                    screenHost().application().notify("Repair Unavailable", availability.reason(), ReSyncNotificationLevel.WARN);
                }
                return false;
            }
            if (!serverHealthRepairsInFlight.add(healthKey)) return false;
            repair = target;
            refresh();
            return true;
        }

        private ServerUiCapabilityProvider.Availability repairAvailability(ServerHealthRepair target) {
            return switch (target) {
                case EULA -> eulaRepairAvailability;
                case SERVER_JAR -> serverJarRepairAvailability;
                case START_SCRIPT -> startScriptRepairAvailability;
                default -> ServerUiCapabilityProvider.Availability.missing("Server Repair Is Unavailable");
            };
        }

        private void completeRepair(ServerHealthRepair target, Throwable error, Runnable success, String title) {
            screenHost().application().execute(() -> {
                if (error == null) success.run();
                else screenHost().application().notify(title, message(error), ReSyncNotificationLevel.ERROR);
                finishRepair(target);
            });
        }

        private void finishRepair(ServerHealthRepair target) {
            if (repair != target) return;
            serverHealthRepairsInFlight.remove(healthKey);
            repair = ServerHealthRepair.NONE;
            refresh();
        }

        private boolean isHealthy() {
            return eulaState.allowsStart() && serverJarState.allowsStart() && startScriptState.allowsStart();
        }

        private void launch(boolean anyway) {
            if (!canContinueServerStart(context, info)) {
                hideServerHealthPopup(popup);
                return;
            }
            if (repair != ServerHealthRepair.NONE || !anyway && !isHealthy()) return;
            hideServerHealthPopup(popup);
            proceedWithServerStart(context, info);
        }
    }

    private void proceedWithServerStart(TabContext context, TerminalSession info){
        if (context == null || context.instance == null) return;
        if (!canContinueServerStart(context, info)) return;
        ServerModels.ClientServerView server = screenHost().serverView(context.instance);
        TerminalWidget terminal = info == null ? null : info.getTerminalWidget();
        if (screenHost().isLocal(context.instance) && info != null && server != null) {
            terminal = recreateLocalTerminal(context, info, server);
        }
        ServerTerminalLifecycle lifecycle = terminal instanceof ServerTerminalLifecycle value ? value : null;
        if (!screenHost().isLocal(context.instance)) screenHost().prepareTerminalStart(terminal);
        String operationId = screenHost().activeOperationId(context.instance);
        if (lifecycle != null) {
            lifecycle.notifyStartRequested();
            String activeOperationId = screenHost().activeOperationId(context.instance);
            if (activeOperationId != null && !activeOperationId.isBlank()) operationId = activeOperationId;
        }
        if (operationId.isBlank()) operationId = screenHost().requestStart(context.instance);
        screenHost().setState(context.instance, ServerScreenHost.ServerState.STARTING);
        String requestedOperationId = operationId;
        if (screenHost().isLocal(context.instance) && terminal != null) {
            updateStartButton(context, info);
            return;
        }
        screenHost().startServer(remotelyClient.getApiClient(), context.instance).whenComplete((ignored, failure) ->
                screenHost().application().execute(() -> {
                    if (failure != null) {
                        String reason = message(failure);
                        screenHost().failServerOperation(context.instance, requestedOperationId, ServerScreenHost.ServerState.CRASHED, reason);
                        screenHost().setState(context.instance, ServerScreenHost.ServerState.CRASHED);
                        if (lifecycle != null) lifecycle.notifyStopRequested();
                        screenHost().application().notify("Start Server", reason, ReSyncNotificationLevel.ERROR);
                    }
                    updateStartButton(context, info);
                }));
    }

    private TerminalWidget recreateLocalTerminal(TabContext context, TerminalSession info, ServerModels.ClientServerView server) {
        TerminalWidget previous = info.getTerminalWidget();
        if (previous != null) previous.shutdown();
        screenHost().shutdownTerminal(detailsTarget(context.instance).id());
        TerminalWidget terminal = screenHost().createTerminal(remotelyClient.getApiClient(), server, null,
                context.mainContainer.getX(), context.mainContainer.getContentTop(),
                context.mainContainer.getEffectiveWidth(), context.mainContainer.getContentHeight(), null);
        if (terminal == null) return previous;
        terminal.entranceAnimationEnabled = false;
        info.setTerminalWidget(terminal);
        if (info.getPlayersContainer() != null) info.getPlayersContainer().setTerminalWidget(terminal);
        for (ViewEntry view : context.views) {
            if ("Terminal".equals(view.hint())) {
                view.widget = terminal;
                break;
            }
        }
        screenHost().attachTerminal(context.instance, terminal);
        setupTerminalListeners(context.instance, info);
        if (context.selectedViewIndex >= 0 && context.selectedViewIndex < context.views.size()
                && "Terminal".equals(context.views.get(context.selectedViewIndex).hint())) {
            onViewSwitched(context, context.selectedViewIndex);
        }
        return terminal;
    }

    private Object ensureSidecar(){
        TabContext context = getActiveContext();
        return context == null ? null : context.instance;
    }

    private void exploreInstanceFiles(){
        Object target = ensureSidecar();
        if (target != null) screenHost().openFileExplorer(this, screenHost().serverView(target));
    }

    private void openReSyncStudio(){
        TabContext context = getActiveContext();
        if (context != null) screenHost().openReSyncStudio(this, screenHost().serverView(context.instance));
    }

    private boolean isDevModeEligible(Object instance){
        return instance != null && screenHost().supportsDevelopment(instance);
    }

    private void openDevModeScreen() {
        TabContext context = getActiveContext();
        if (context == null || context.instance == null) {
            return;
        }
        DevelopmentTabState development = developmentTabs.get(context);
        if (development != null) {
            developmentPanel.toggle(development.remote);
            return;
        }
        if (!isDevModeEligible(context.instance)) {
            new Notification.Builder().message("Development Unavailable").description("Remote Servers Only").type(Notification.Type.ERROR).build();
            return;
        }
        if (!screenHost().developmentProvider().available()) {
            screenHost().openDevelopment(this, screenHost().serverView(context.instance));
            return;
        }
        if (developmentPanel != null) {
            developmentPanel.toggle(context.instance);
        }
    }

    private void setupDevelopmentPanel() {
        if (developmentPanel != null) {
            developmentPanel.dispose();
        }
        ServerModels.ClientServerView server = screenHost().serverView(getActiveContext() == null ? null : getActiveContext().instance);
        developmentPanel = new ServerDevelopmentPanel(this, screenHost().capabilities(server));
    }

    @Override
    protected void onSidePanelWidthChanged() {
        super.onSidePanelWidthChanged();
        if (developmentPanel != null) {
            developmentPanel.layout();
        }
    }

    public void openDevelopment(Object source) {
        if (source == null) return;
        if (!screenHost().developmentProvider().available()) {
            screenHost().openDevelopment(this, screenHost().serverView(source));
            return;
        }
        if (developmentPanel != null) developmentPanel.open(source);
    }

    public void refreshDevelopmentAvailability(Object source) {
        TabContext context = getActiveContext();
        if (context == null || context.instance == null || !sameTab(context.instance, source)) return;
        ViewEntry active = context.views.isEmpty() ? null
                : context.views.get(Math.clamp(context.selectedViewIndex, 0, context.views.size() - 1));
        onViewChanged(context, active);
    }

    public void openInstanceSettings(){
        Object target = ensureSidecar();
        if (target != null) screenHost().openServerConfiguration(this, screenHost().serverView(target));
    }

    private boolean isPteroInstance(Object instance){
        return screenHost().isPanel(instance);
    }

    private void configureTerminalInput(Object instance, TerminalWidget terminal){
        screenHost().configureTerminalInput(remotelyClient.getApiClient(), screenHost().serverView(instance), terminal);
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        updateKillConfirmationExpiry();
        super.render(context, mouseX, mouseY, delta);
        renderTerminalScrollbar(context, mouseX, mouseY);
    }

    private void updateKillConfirmationExpiry() {
        if (killConfirmInstanceId == null || System.currentTimeMillis() <= killConfirmUntilMs) {
            return;
        }
        killConfirmInstanceId = null;
        killConfirmUntilMs = 0;
        TabContext context = getActiveContext();
        if (context == null) {
            return;
        }
        TerminalSession info = contextInfos.get(context);
        updateStartButton(context, info);
    }

    private void renderTerminalScrollbar(IDrawContext context, int mouseX, int mouseY) {
        TerminalWidget terminal = getActiveTerminalWidget();
        if (!shouldRenderTerminalScrollbar(terminal)) return;
        terminalScrollbarController.render(
            context,
            mouseX,
            mouseY,
            getTerminalScrollbarTotalHeight(terminal),
            getTerminalScrollbarOffset(terminal),
            getTerminalScrollbarX(terminal),
            getTerminalScrollbarY(terminal),
            TERMINAL_SCROLLBAR_WIDTH,
            getTerminalScrollbarHeight(terminal)
        );
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        TabContext context = getActiveContext();
        if (event.button() == ReMouseButton.LEFT && context != null && context.selectedViewIndex >= 0 && context.selectedViewIndex < context.views.size()) {
            ViewEntry activeView = context.views.get(context.selectedViewIndex);
            ResourceContainerAdapter resources = contextInfos.get(context) == null ? null : contextInfos.get(context).getResourceContainer();
            if (resources != null && activeView.widget() == resources.widget()) resources.clearSelectionOutsideResource(event.x(), event.y());
        }
        if (handleTerminalScrollbarPressed(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event);
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        TerminalWidget terminal = getActiveTerminalWidget();
        if (shouldRenderTerminalScrollbar(terminal) && terminalScrollbarController.handleMouseDragged((int) event.y(), getTerminalScrollbarTotalHeight(terminal), getTerminalScrollbarHeight(terminal))) {
            terminal.setScrollOffset(getTerminalScrollOffsetFromScrollbar(terminal, terminalScrollbarController.getPendingOffset()));
            return true;
        }
        return super.mouseDragged(event);
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        boolean handled = terminalScrollbarController.isDragging();
        if (handled) {
            terminalScrollbarController.handleMouseReleased();
            return true;
        }
        return super.mouseReleased(event);
    }

    private boolean handleTerminalScrollbarPressed(double mouseX, double mouseY) {
        TerminalWidget terminal = getActiveTerminalWidget();
        if (!shouldRenderTerminalScrollbar(terminal)) return false;
        boolean handled = terminalScrollbarController.handleMousePressed(
            (int) mouseX,
            (int) mouseY,
            getTerminalScrollbarTotalHeight(terminal),
            getTerminalScrollbarOffset(terminal),
            getTerminalScrollbarX(terminal),
            getTerminalScrollbarY(terminal),
            TERMINAL_SCROLLBAR_WIDTH,
            getTerminalScrollbarHeight(terminal)
        );
        if (handled) {
            terminal.setScrollOffset(getTerminalScrollOffsetFromScrollbar(terminal, terminalScrollbarController.getPendingOffset()));
        }
        return handled;
    }

    private TerminalWidget getActiveTerminalWidget() {
        TabContext ctx = getActiveContext();
        if (ctx == null || ctx.selectedViewIndex < 0 || ctx.selectedViewIndex >= ctx.views.size()) return null;
        return ctx.views.get(ctx.selectedViewIndex).widget() instanceof TerminalWidget terminal ? terminal : null;
    }

    private String currentViewName(TabContext ctx) {
        if (ctx == null || ctx.selectedViewIndex < 0 || ctx.selectedViewIndex >= ctx.views.size()) {
            return "Terminal";
        }
        return viewName(ctx.views.get(ctx.selectedViewIndex));
    }

    private String viewName(ViewEntry activeView) {
        return activeView != null && activeView.hint() != null && !activeView.hint().isBlank() ? activeView.hint() : "Terminal";
    }

    private boolean shouldRenderTerminalScrollbar(TerminalWidget terminal) {
        return terminal != null && getTerminalScrollbarHeight(terminal) > 0 && terminal.getContentHeight() > 0;
    }

    private int getTerminalScrollbarTotalHeight(TerminalWidget terminal) {
        return terminal.getContentHeight() + getTerminalScrollbarHeight(terminal);
    }

    private float getTerminalScrollbarOffset(TerminalWidget terminal) {
        return Math.max(0f, terminal.getContentHeight() - terminal.getScrollOffset());
    }

    private float getTerminalScrollOffsetFromScrollbar(TerminalWidget terminal, float scrollbarOffset) {
        return Math.max(0f, terminal.getContentHeight() - scrollbarOffset);
    }

    private int getTerminalScrollbarX(TerminalWidget terminal) {
        return terminal.getX() + terminal.getWidth() + 2;
    }

    private int getTerminalScrollbarY(TerminalWidget terminal) {
        return terminal.getY();
    }

    private int getTerminalScrollbarHeight(TerminalWidget terminal) {
        return terminal.getHeight();
    }

    @Override
    public void updatePositions() {
        super.updatePositions();

        if (developmentPanel != null) {
            developmentPanel.layout();
        }

        for (TabContext c : tabContexts.values()) {
            if (c == null || c.mainContainer == null) continue;
            if (!getGroupManager().isManaged(c.mainContainer)) {
                int pad = c.instance != null ? 15 : 0;
                c.mainContainer.setWidth(width - 10);
                c.mainContainer.setHeight(height - 65 - pad);
                c.mainContainer.updateWidgetPositions();
            }
        }

        TabContext ctx = getActiveContext();
        if (ctx == null) return;

        int topY = 36;
        int rightX = width - 5;
        if (viewSwitcher != null && viewSwitcher.getWidget() != null) {
            rightX -= viewSwitcher.getWidget().getWidth();
            viewSwitcher.setPosition(rightX, topY);
            rightX--;
        }

        if (ctx.selectedViewIndex >= 0 && ctx.selectedViewIndex < ctx.views.size()) {
            for (AnimatedWidget toolbarWidget : ctx.views.get(ctx.selectedViewIndex).loadedToolbarWidgets()) {
                if (toolbarWidget == null || !toolbarWidget.isVisible()) continue;
                rightX -= toolbarWidget.getWidth();
                toolbarWidget.setPosition(rightX, topY);
                rightX--;
            }
            if (!getGroupManager().isManaged(ctx.mainContainer)) {
                tabs().setWidth(Math.max(100, rightX - 10));
                tabs().updateLayout();
            }
        }

        int pad = ctx.instance != null ? 15 : 0;
        if (ctx.selectedViewIndex < ctx.views.size()) {
            ViewEntry view = ctx.views.get(ctx.selectedViewIndex);
            boolean grouped = getGroupManager().isManaged(ctx.mainContainer);
            int newW = ctx.mainContainer.getEffectiveWidth();
            int newH = grouped ? ctx.mainContainer.getContentHeight() : height - 65 - pad;
            if (view.widget() instanceof Container c) {
                c.setWidth(newW);
                c.setHeight(newH);
                c.updateWidgetPositions();
            } else if (view.widget() instanceof AnimatedWidget w) {
                w.setWidth(newW);
                w.setHeight(newH);
            }
        }
    }

    @Override
    protected void onContainerGroupLayoutUpdated() {
        for (TabContext context : tabContexts.values()) {
            if (context.mainContainer == null || context.selectedViewIndex < 0 || context.selectedViewIndex >= context.views.size()) continue;
            AnimatedWidget widget = context.views.get(context.selectedViewIndex).widget();
            if (widget == null) continue;
            Container container = context.mainContainer;
            int x = container.getX();
            int y = container.getContentTop();
            int contentWidth = container.getEffectiveWidth();
            int contentHeight = container.getContentHeight();
            if (widget.getX() != x || widget.getY() != y || widget.getWidth() != contentWidth || widget.getHeight() != contentHeight) {
                widget.setPosition(x, y);
                widget.setWidth(contentWidth);
                widget.setHeight(contentHeight);
                if (widget instanceof Container nested) nested.updateWidgetPositions();
            }
        }
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (event.key() == ReKey.ESCAPE) {
            close();
            return true;
        }
        if (event.key() == ReKey.R && event.modifiers().control()) {
            TerminalSession info = getCurrentInfo();
            if (info != null && info.getResourceContainer() != null) info.getResourceContainer().loadResources(true);
            return true;
        }
        if (event.key() == ReKey.GRAVE_ACCENT && event.modifiers().control()) {
            if (viewSwitcher != null) {
                TabContext ctx = getActiveContext();
                int i = ctx.selectedViewIndex + 1;
                if(i >= ctx.views.size()) i = 0;
                viewSwitcher.setActiveIndex(i);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        super.tick();
        if (developmentPanel != null) {
            developmentPanel.tick();
        }
    }

    @Override
    public void filesDropped(ReDropEvent event){
        TabContext context = getActiveContext();
        if (context == null || context.instance == null || event == null) return;
        screenHost().importDroppedFiles(context.instance, event.files()).whenComplete((result, failure) -> screenHost().application().execute(() -> {
            if (failure != null) {
                screenHost().application().notify("Import Failed", message(failure), ReSyncNotificationLevel.WARN);
                return;
            }
            if (result == null) return;
            if (result.imported() > 0) {
                screenHost().application().notify("Import Complete", "Added " + result.imported() + " "
                        + itemLabel(result.imported()) + ".", ReSyncNotificationLevel.SUCCESS);
                TerminalSession info = contextInfos.get(context);
                if (result.resourcesChanged() && info != null && info.getResourceContainer() != null) {
                    info.getResourceContainer().loadResources(true);
                }
            }
            if (result.failed() > 0) {
                screenHost().application().notify("Import Incomplete", "Could Not Add " + result.failed() + " "
                        + itemLabel(result.failed()) + ".", ReSyncNotificationLevel.WARN);
            }
        }));
    }

    private static String itemLabel(int count) {
        return count == 1 ? "Item" : "Items";
    }

    protected void onStateChanged(ServerScreenHost.ServerState newState){
        screenHost().application().execute(() -> {
            TabContext context = getActiveContext();
            if (context instanceof ServerTabStatusContext statusContext) statusContext.lifecycleChanged(newState);
            if (context != null && !context.views.isEmpty()) {
                int index = Math.clamp(context.selectedViewIndex, 0, context.views.size() - 1);
                context.selectedViewIndex = index;
                onViewChanged(context, context.views.get(index));
            }
            TerminalSession info = context == null ? null : contextInfos.get(context);
            if (context instanceof ServerTabStatusContext statusContext
                    && (newState == ServerScreenHost.ServerState.STOPPED
                    || newState == ServerScreenHost.ServerState.STOPPING
                    || newState == ServerScreenHost.ServerState.CRASHED)) {
                clearStatusMetrics(context, statusContext);
            }
            if (info != null && info.getPlayersContainer() != null) info.getPlayersContainer().fullRefresh();
        });
    }

    private TerminalSession getCurrentInfo() {
        TabContext ctx = getActiveContext();
        return ctx == null ? null : contextInfos.get(ctx);
    }

    private void startStatusScheduler(){
        if (statusScheduler != null) return;
        TaskScheduler scheduler = remotelyClient == null || remotelyClient.getComposition() == null
                ? TaskScheduler.direct() : remotelyClient.getComposition().scheduler();
        if (scheduler == null) scheduler = TaskScheduler.direct();
        statusScheduler = scheduler.scheduleAtFixedRate(() -> screenHost().application().execute(this::refreshActiveStatusBarResources),
                Duration.ZERO, Duration.ofSeconds(1));
    }

    private void applyStatusBarForActiveTab() {
        TabContext ctx = getActiveContext();
        boolean show = ctx instanceof ServerTabStatusContext;

        if (statusBarBuilder != null) {
            statusBarBuilder.visible(show);
            if (!show) {
                statusBarBuilder.clear();
                statusBarBuilder.build();
            }
        }

        if (show) {
            updateActiveStatusBar();
        }
    }

    private void refreshActiveStatusBarResources(){
        if (!shouldRefreshStatusResources()) return;
        TabContext ctx = getActiveContext();
        if (!(ctx instanceof ServerTabStatusContext statusCtx) || ctx.instance == null) return;
        long nowMs = System.currentTimeMillis();
        statusCtx.refreshConnectionInfo(nowMs);
        MetricsRequest request = statusCtx.tryStartRequest(nowMs);
        if (request == null) return;
        ServerModels.ClientServerView server = screenHost().serverView(ctx.instance);
        if (server == null) {
            clearStatusMetrics(ctx, statusCtx);
            statusCtx.finishRequest(request);
            return;
        }
        screenHost().metrics(remotelyClient.getApiClient(), ctx.instance).whenComplete((metrics, failure) ->
                screenHost().application().execute(() -> {
                    if (!isCurrentStatusContext(ctx) || !statusCtx.isCurrentRequest(request)) {
                        statusCtx.finishRequest(request);
                        return;
                    }
                    if (failure == null && metrics != null && !isInactiveMetricsState(ctx.instance)) {
                        ctx.metrics = metrics;
                        statusCtx.update(metrics);
                        updateActivityMetrics(ctx, metrics);
                    } else {
                        clearStatusMetrics(ctx, statusCtx);
                    }
                    if (isLocalInstance(ctx.instance)) {
                        screenHost().localStatus(ctx.instance).whenComplete((status, localFailure) -> screenHost().application().execute(() -> {
                            if (!isCurrentStatusContext(ctx)) return;
                            if (localFailure != null) {
                                String key = detailsTarget(ctx.instance).id() + "|" + message(localFailure);
                                if (localControllerFailureNotices.add(key)) {
                                    screenHost().application().notify("Local Controller", message(localFailure), ReSyncNotificationLevel.WARN);
                                }
                            } else if (status != null) {
                                if (status.lastError().isBlank()) clearLocalControllerFailureNotice(ctx.instance);
                                else notifyLocalControllerFailure(ctx, status);
                                applyLocalControllerState(ctx, contextInfos.get(ctx), status);
                            }
                        }));
                    }
                    statusCtx.finishRequest(request);
                    updateStartButton(ctx, contextInfos.get(ctx));
                }));
    }

    private boolean isInactiveMetricsState(Object target) {
        return switch (stateOf(target)) {
            case STOPPED, STOPPING, CRASHED -> true;
            default -> false;
        };
    }

    private void clearStatusMetrics(TabContext context, ServerTabStatusContext statusContext) {
        if (context == null) return;
        context.metrics = null;
        statusContext.clearMetrics();
        if (context.instance != null) {
            screenHost().application().updateServerActivityMetrics(context.instance, 0, 0, 0, 0, 0, 0);
        }
    }

    private void updateActivityMetrics(TabContext context, ServerScreenHost.ServerMetrics metrics) {
        if (context == null || context.instance == null || metrics == null) return;
        int cpu = (int) Math.clamp(Math.round(metrics.cpuPercent()), 0L, (long) Integer.MAX_VALUE);
        screenHost().application().updateServerActivityMetrics(context.instance, metrics.players(), metrics.maxPlayers(),
                metrics.uptimeMs(), cpu, toMegabytes(metrics.memoryBytes()), toMegabytes(metrics.memoryLimitBytes()));
    }

    private static int toMegabytes(long bytes) {
        if (bytes <= 0) return 0;
        long megabytes = Math.round(bytes / (1024d * 1024d));
        return megabytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, megabytes);
    }

    private boolean shouldRefreshStatusResources(){
        if (closed) return false;
        if (screenHost().application().getCurrentScreen() == this) return true;
        if (!isDesktopWindow()) return false;
        var overlay = client.getDesktopWindowsOverlay();
        var activeWindow = overlay == null ? null : overlay.getActiveWindow();
        return activeWindow != null && activeWindow.getScreen() == this && activeWindow.isVisible() && !activeWindow.isMinimized();
    }

    private boolean isCurrentStatusContext(TabContext context) {
        return shouldRefreshStatusResources() && getActiveContext() == context && tabContexts.containsValue(context);
    }

    private ServerScreenHost.LocalStatus localControllerStatus(TabContext ctx){
        if (ctx == null || ctx.instance == null) return null;
        return null;
    }

    private boolean isLocalInstance(Object instance){
        return detailsTarget(instance).local();
    }

    private void applyLocalControllerState(TabContext ctx, TerminalSession info, ServerScreenHost.LocalStatus status){
        if (ctx == null || status == null || status.state().isBlank()) return;
        screenHost().applyLocalStatus(ctx.instance, status, info);
        updateStartButton(ctx, info);
    }

    private void notifyLocalControllerFailure(TabContext ctx, ServerScreenHost.LocalStatus status){
        if (ctx == null || status == null || status.lastError().isBlank()) return;
        String key = detailsTarget(ctx.instance).id() + "|" + status.lastError();
        if (localControllerFailureNotices.add(key)) {
            screenHost().application().notify("Server", status.lastError(), ReSyncNotificationLevel.ERROR);
        }
    }

    private void clearLocalControllerFailureNotice(Object instance){
        if (instance == null) return;
        localControllerFailureNotices.removeIf(value -> value.startsWith(detailsTarget(instance).id() + "|"));
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "-";
        double b = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int idx = 0;
        while (b >= 1024 && idx < units.length - 1) {
            b /= 1024;
            idx++;
        }
        if (idx <= 1) return String.format(Locale.ROOT, "%.0f%s", b, units[idx]);
        return String.format(Locale.ROOT, "%.1f%s", b, units[idx]);
    }

    private static String formatUptime(long uptimeMs) {
        if (uptimeMs <= 0) return "-";
        long totalSeconds = uptimeMs / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private void showNetworkSummary(Object instance){
        if (instance == null) return;
        NetworkOverviewProvider provider = screenHost().networkOverviewProvider(remotelyClient);
        String id = detailsTarget(instance).id();
        if (!provider.available() || id.isBlank()) {
            screenHost().unavailable(ServerScreenHost.Action.NETWORK_SETTINGS);
            return;
        }
        provider.loadForServer(id).whenComplete((state, failure) -> screenHost().application().execute(() -> {
            if (failure != null || state == null || state.network() == null) {
                screenHost().application().notify("Network", "Network Details Unavailable", ReSyncNotificationLevel.WARN);
                return;
            }
            if (networkSummaryPopup != null) networkSummaryPopup.hide();
            NetworkDefinitionView network = new NetworkDefinitionView(state.network(), state.runtime(), id);
            PopupWidget.Builder builder = new PopupWidget.Builder(network.name()).width(390).setResizable(false).setAntiOutOfBound(true);
            builder.addRow("identity", "Server", infoButton(detailsTarget(instance).name(), "Server Identity"));
            builder.addRow("role", "Role", infoButton(network.role(), "Network Role"));
            builder.addRow("route", "Route", infoButton(network.route(), "Network Route"));
            builder.addRow("presence", "Presence", infoButton(network.presence(), "ReSync Presence"));
            builder.addRow("runtime", "Runtime", infoButton(network.runtime(), "Network Runtime"));
            PopupWidget popup = builder.build();
            popup.onClose = () -> {
                if (networkSummaryPopup == popup) networkSummaryPopup = null;
            };
            addDrawableChild(popup);
            popup.show();
            networkSummaryPopup = popup;
        }));
    }

    private AnimatedButton infoButton(String label, String hint) {
        return new AnimatedButton.Builder().label(label == null || label.isBlank() ? "Unavailable" : label)
                .hint(hint).active(false).accentType(ThemeManager.getAccent("calm")).build();
    }

    private record NetworkDefinitionView(String name, String role, String route, String presence, String runtime) {
        private NetworkDefinitionView(NetworkDefinition network, NetworkRuntimeSnapshot snapshot, String instanceId) {
            this(network.name(), role(network, instanceId), route(network, instanceId), presence(snapshot), runtime(snapshot));
        }

        private static String role(NetworkDefinition network, String instanceId) {
            var member = network.members().stream().filter(candidate -> instanceId.equals(candidate.instanceId())).findFirst().orElse(null);
            return member == null ? "Member" : member.isProxy() ? "Proxy" : formatNetworkRole(member.role().name());
        }

        private static String route(NetworkDefinition network, String instanceId) {
            var member = network.members().stream().filter(candidate -> instanceId.equals(candidate.instanceId())).findFirst().orElse(null);
            return member == null ? "Unavailable" : member.routeName() + " • " + member.address() + ":" + member.port();
        }

        private static String presence(NetworkRuntimeSnapshot snapshot) {
            return snapshot != null && snapshot.connected() ? snapshot.players() + " Shared Players" : "Unavailable";
        }

        private static String runtime(NetworkRuntimeSnapshot snapshot) {
            return snapshot == null ? "Unavailable" : formatNetworkRole(snapshot.state().name());
        }
    }

    private static String formatNetworkRole(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (char character : normalized.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = character == ' ';
        }
        return result.toString();
    }

    private TabContext getActiveContext() {
        return tabs().getActiveTab() == null ? null : tabContexts.get(tabs().getActiveTab());
    }

    private void registerTab(TabsManager.Tab tab, TabContext context) {
        if (tab == null || context == null) return;
        tabContexts.put(tab, context);
        tab.setContainer(context.mainContainer);
    }

    private void unregisterTarget(Object target) {
        if (target == null) return;
        screenHost().removeStateListener(target, stateListener);
    }

    private void removeRestartListener(Object target) {
        if (target == null) return;
        String key = killKey(target);
        Consumer<ServerScreenHost.ServerState> listener = restartListeners.remove(key);
        if (listener != null) screenHost().removeStateListener(target, listener);
    }

    private void cleanupTarget(Object target) {
        if (target == null) return;
        removeRestartListener(target);
        screenHost().onServerTabClosed(target);
        unregisterTarget(target);
    }

    private void clearPerServerState(Object target) {
        if (target == null) return;
        String key = killKey(target);
        serverHealthChecksInFlight.remove(key);
        serverHealthRepairsInFlight.remove(key);
        clearLocalControllerFailureNotice(target);
    }

    private void onViewSwitched(TabContext context, int index) {
        if (context == null || context.views.isEmpty()) return;
        context.selectedViewIndex = Math.clamp(index, 0, context.views.size() - 1);
        ViewEntry active = context.views.get(context.selectedViewIndex);
        active.ensureLoaded();
        context.mainContainer.detachWidgets();
        if (active.widget() != null) context.mainContainer.addWidget(active.widget());
        for (ViewEntry view : context.views) {
            boolean visible = view == active;
            for (AnimatedWidget toolbarWidget : view.loadedToolbarWidgets()) {
                if (toolbarWidget == null) continue;
                toolbarWidget.setVisible(visible);
                if (visible && !widgets.contains(toolbarWidget)) addDrawableChild(toolbarWidget);
            }
        }
        onViewChanged(context, active);
        updatePositions();
        persistBrowserViewContext(context);
    }

    private class TabContext {
        private Object instance;
        private Object id;
        private Container mainContainer;
        private final List<ViewEntry> views = new ArrayList<>();
        private int selectedViewIndex;
        private ServerScreenHost.ServerMetrics metrics;
        private ServerUiCapabilityProvider capabilityProvider;
        private Runnable capabilityListener;

        private TabContext(Object instance, Object id) {
            this.instance = instance;
            this.id = id;
        }

        private void addView(AnimatedWidget widget, String icon, String hint, List<AnimatedWidget> toolbarWidgets) {
            views.add(new ViewEntry(widget, icon, hint, toolbarWidgets));
        }
    }

    private static final class ViewEntry {
        private AnimatedWidget widget;
        private final String icon;
        private final String hint;
        private final List<AnimatedWidget> toolbarWidgets;

        private ViewEntry(AnimatedWidget widget, String icon, String hint, List<AnimatedWidget> toolbarWidgets) {
            this.widget = widget;
            this.icon = icon;
            this.hint = hint;
            this.toolbarWidgets = toolbarWidgets == null ? List.of() : toolbarWidgets;
        }

        private AnimatedWidget widget() {
            return widget;
        }

        private String icon() {
            return icon;
        }

        private String hint() {
            return hint;
        }

        private List<AnimatedWidget> loadedToolbarWidgets() {
            return toolbarWidgets;
        }

        private boolean isLoaded() {
            return widget != null;
        }

        private void ensureLoaded() {
        }
    }

    private final class ServerTabStatusContext extends TabContext implements TabStatusContext {
        private static final long CONNECTION_REFRESH_MS = 30_000;
        private static final long COPIED_DISPLAY_MS = 2_000;
        private static final long NETWORK_REFRESH_MS = 30_000;
        private IconButton connectionWidget;
        private IconButton uptimeWidget;
        private IconButton cpuWidget;
        private IconButton ramWidget;
        private IconButton networkWidget;
        private String connectionInfo = "Loading...";
        private long copiedUntilMs;
        private long copyActionToken;
        private boolean connectionRequestInFlight;
        private long lastConnectionRequestAtMs;
        private boolean networkRequestInFlight;
        private long lastNetworkRequestAtMs;
        private NetworkOverviewProvider.OverviewState networkState;
        private final MetricsRequestGate metricsRequests = new MetricsRequestGate();

        private ServerTabStatusContext(Object instance, Object id) {
            super(instance, id);
        }

        @Override
        public String getContextId() {
            return detailsTarget(instance).id();
        }

        @Override
        public void setupStatusBar(StatusBarBuilder builder) {
            if (connectionWidget == null) {
                connectionWidget = new IconButton.Builder().imagePath("clipboard.png").label(connectionInfo)
                        .hint("Click To Copy").autoWidthOnTextChange(true).size(0, 14).iconSize(12).iconPadding(2)
                        .transparent(true).animateElevation(false).entranceAnimation(false).elevateOnFocused(false).build();
            }
            if (uptimeWidget == null) uptimeWidget = statusButton("Uptime: -");
            if (cpuWidget == null) cpuWidget = statusButton("Cpu: -");
            if (ramWidget == null) ramWidget = statusButton("Ram: -");
            if (networkWidget == null) {
                networkWidget = new IconButton.Builder().imagePath("velocity.png").label("")
                        .hint("Network").autoWidthOnTextChange(true).size(0, 14).iconSize(12).iconPadding(2)
                        .transparent(true).animateElevation(false).entranceAnimation(false).elevateOnFocused(false).build();
            }
            updateConnectionWidget();
            updateNetworkWidget();
            builder.addLeft(connectionWidget);
            builder.addLeft(networkWidget);
            builder.addRight(uptimeWidget);
            builder.addRight(cpuWidget);
            builder.addRight(ramWidget);
        }

        private IconButton statusButton(String label) {
            return new IconButton.Builder().label(label).autoWidthOnTextChange(true).transparent(true)
                    .animateElevation(false).entranceAnimation(false).build();
        }

        private void refreshConnectionInfo(long nowMs) {
            if (connectionRequestInFlight && nowMs - lastConnectionRequestAtMs < 5_000) return;
            if (nowMs - lastConnectionRequestAtMs < CONNECTION_REFRESH_MS) return;
            lastConnectionRequestAtMs = nowMs;
            connectionRequestInFlight = true;
            screenHost().connectionInfo(instance).whenComplete((value, failure) -> screenHost().application().execute(() -> {
                connectionRequestInFlight = false;
                updateConnectionInfo(failure == null ? value : "Unknown");
            }));
            refreshNetworkInfo(nowMs);
        }

        private void updateConnectionInfo(String value) {
            connectionInfo = value == null || value.isBlank() ? "Unknown" : value.endsWith(":25565")
                    ? value.substring(0, value.length() - 6) : value;
            updateConnectionWidget();
        }

        private void updateConnectionWidget() {
            if (connectionWidget == null) return;
            boolean copied = System.currentTimeMillis() < copiedUntilMs;
            connectionWidget.setMessage(copied ? "Copied" : connectionInfo);
            connectionWidget.setIcon(copied ? "checkmark.png" : "clipboard.png");
            connectionWidget.setOnClick("Unknown".equals(connectionInfo) || "Loading...".equals(connectionInfo) ? null : () -> {
                try {
                    screenHost().application().setClipboard(connectionInfo);
                    copiedUntilMs = System.currentTimeMillis() + COPIED_DISPLAY_MS;
                    long token = ++copyActionToken;
                    updateConnectionWidget();
                    UiTasks.runLater(COPIED_DISPLAY_MS, () -> screenHost().application().execute(() -> {
                        if (copyActionToken == token) {
                            copiedUntilMs = 0;
                            updateConnectionWidget();
                        }
                    }));
                    screenHost().application().notify("IP Copied", "Server Address Copied", ReSyncNotificationLevel.SUCCESS);
                } catch (RuntimeException ignored) {
                }
            });
        }

        private void refreshNetworkInfo(long nowMs) {
            if (networkRequestInFlight || nowMs - lastNetworkRequestAtMs < NETWORK_REFRESH_MS) return;
            String serverId = detailsTarget(instance).id();
            NetworkOverviewProvider provider = screenHost().networkOverviewProvider(remotelyClient);
            if (serverId.isBlank() || !provider.available()) {
                networkState = null;
                updateNetworkWidget();
                return;
            }
            networkRequestInFlight = true;
            lastNetworkRequestAtMs = nowMs;
            provider.loadForServer(serverId).whenComplete((state, failure) -> screenHost().application().execute(() -> {
                networkRequestInFlight = false;
                networkState = failure == null ? state : null;
                updateNetworkWidget();
            }));
        }

        private void updateNetworkWidget() {
            if (networkWidget == null) return;
            NetworkOverviewProvider.OverviewState state = networkState;
            if (state == null || state.network() == null) {
                networkWidget.setVisible(false);
                networkWidget.setOnClick(null);
                return;
            }
            var network = state.network();
            var member = network.members().stream().filter(value -> detailsTarget(instance).id().equals(value.instanceId())).findFirst().orElse(null);
            var runtime = state.runtime();
            String role = member == null ? "Member" : member.isProxy() ? "Proxy" : formatNetworkRole(member.role().name());
            String players = runtime != null && runtime.connected() ? " • " + runtime.players() + " Players" : "";
            networkWidget.setMessage(network.name() + " • " + role + players);
            networkWidget.setHint(runtime != null && runtime.connected() ? "Network-Wide ReSync Presence"
                    : runtime == null ? "Network Runtime" : "ReSync " + formatNetworkRole(runtime.state().name()));
            networkWidget.setOnClick(() -> showNetworkSummary(instance));
            networkWidget.setVisible(true);
        }

        private MetricsRequest tryStartRequest(long nowMs) {
            return metricsRequests.tryStart(nowMs);
        }

        private boolean isCurrentRequest(MetricsRequest request) {
            return metricsRequests.current(request);
        }

        private void finishRequest(MetricsRequest request) {
            metricsRequests.finish(request);
        }

        private void lifecycleChanged(ServerScreenHost.ServerState state) {
            metricsRequests.transition(state);
        }

        private void invalidateMetricsRequest() {
            metricsRequests.invalidate();
        }

        private void update(ServerScreenHost.ServerMetrics usage) {
            if (usage == null) return;
            if (uptimeWidget != null) uptimeWidget.setMessage("Uptime: " + formatUptime(usage.uptimeMs()));
            if (cpuWidget != null) cpuWidget.setMessage("CPU: " + String.format(Locale.ROOT, "%.0f%%", usage.cpuPercent()));
            if (ramWidget != null) ramWidget.setMessage("RAM: " + formatBytes(usage.memoryBytes()) + (usage.memoryLimitBytes() > 0 ? "/" + formatBytes(usage.memoryLimitBytes()) : ""));
        }

        private void clearMetrics() {
            if (uptimeWidget != null) uptimeWidget.setMessage("Uptime: -");
            if (cpuWidget != null) cpuWidget.setMessage("CPU: -");
            if (ramWidget != null) ramWidget.setMessage("RAM: -");
        }
    }

    @Override
    public List<String> getLeftLines(){
        TabContext context = getActiveContext();
        if (context == null) return List.of("No Active Context");
        TerminalSession info = contextInfos.get(context);
        if (info != null && info.isLocalTerminalMode()) {
            return List.of("Mode: Local Terminal", "Term ID: " + info.getLocalTerminalId());
        }
        ServerScreenHost.ServerIdentity identity = screenHost().identity(context.instance);
        ServerScreenHost.ServerState state = stateOf(context.instance);
        ServerUiCapabilityProvider capabilities = screenHost().capabilities(screenHost().serverView(context.instance));
        String backend = identity.backendType().isBlank() ? "Unavailable" : identity.backendType();
        String console = capabilities.availability(screenHost().serverView(context.instance), ServerUiCapabilityProvider.Capability.CONSOLE).available()
                ? "Available" : "Unavailable";
        List<String> lines = new ArrayList<>(List.of("Mode: Server", "Server: " + detailsTarget(context.instance).name(),
                "State: " + state, "MSMP: " + screenHost().msmpStatus(context.instance), "Backend: " + backend,
                "Console: " + console));
        return lines;
    }

    @Override
    public List<String> getRightLines(){
        TabContext context = getActiveContext();
        if (context == null) return List.of();
        List<String> info = new ArrayList<>();
        if (context.metrics != null) {
            info.add("Uptime: " + formatUptime(context.metrics.uptimeMs()));
            info.add("CPU: " + Math.round(context.metrics.cpuPercent()) + "%");
            info.add("Players: " + context.metrics.players() + (context.metrics.maxPlayers() > 0 ? "/" + context.metrics.maxPlayers() : ""));
        }
        ServerModels.ClientServerView server = screenHost().serverView(context.instance);
        if (server != null) {
            ReSyncLuckPermsClient luckPerms = PlayerManagerController.getOrCreate(context.instance,
                    screenHost().capabilities(server)).getLuckPermsClient();
            if (luckPerms != null) info.add("LuckPerms: " + (luckPerms.isAvailable() ? "Available" : "Unavailable"));
        }
        info.add("View: " + currentViewName(context));
        return info;
    }

    @Override
    public void removed(){
        closed = true;
        cancelNewTerminalTargetRequest();
        Async<NewTerminalTargetProvider.State> restoreRequest = terminalRestoreRequest;
        terminalRestoreRequest = null;
        if (restoreRequest != null) restoreRequest.cancel();
        if (viewSwitcher != null) {
            viewSwitcher.cleanup();
            viewSwitcher = null;
        }
        hideServerHealthPopup();
        if (networkSummaryPopup != null) {
            networkSummaryPopup.hide();
            networkSummaryPopup = null;
        }
        serverHealthChecksInFlight.clear();
        serverHealthRepairsInFlight.clear();
        if (statusScheduler != null) {
            statusScheduler.cancel();
            statusScheduler = null;
        }
        if (developmentPanel != null) {
            developmentPanel.dispose();
            developmentPanel = null;
        }
        if (shouldClearBrowserDetailState(clearBrowserDetailStateOnRemove, browserRuntime(), remotelyClient != null)) remotelyClient.clearBrowserDetailState();
        else persistBrowserViewContext(getActiveContext());
        syncTabStoreFromTabs();
        destroyAllSessions();
        for (TabContext context : new ArrayList<>(tabContexts.values())) {
            if (context instanceof ServerTabStatusContext statusContext) statusContext.invalidateMetricsRequest();
            if (context.capabilityProvider != null && context.capabilityListener != null) {
                context.capabilityProvider.removeCapabilityListener(context.capabilityListener);
            }
            cleanupTarget(context.instance);
            clearPerServerState(context.instance);
            DevelopmentTabState development = developmentTabs.get(context);
            if (development != null) {
                cleanupTarget(development.remote);
                cleanupTarget(development.local);
                clearPerServerState(development.remote);
                clearPerServerState(development.local);
            }
        }
        restartListeners.clear();
        localControllerFailureNotices.clear();
        statusContexts.clear();
        tabContexts.clear();
        super.removed();
    }

    static boolean shouldClearBrowserDetailState(boolean explicitClose, boolean browserRuntime, boolean hasClient) {
        return explicitClose && browserRuntime && hasClient;
    }

    private void destroyAllSessions() {
        if (remotelyClient == null) return;
        Set<TerminalSession> sessions = Collections.newSetFromMap(new IdentityHashMap<>());
        sessions.addAll(contextInfos.values());
        developmentTabs.values().forEach(development -> {
            if (development != null) {
                sessions.add(development.remoteSession);
                sessions.add(development.localSession);
            }
        });
        sessions.remove(null);
        sessions.forEach(session -> remotelyClient.getSessionManager().destroySession(session.getTabId()));
    }

    private static final class StreamDataParser implements BiConsumer<Integer, String> {
        private final TerminalSession session;
        private String currentFile;
        private final StringBuilder buffer = new StringBuilder();

        private StreamDataParser(TerminalSession session) {
            this.session = session;
        }

        @Override
        public void accept(Integer ignored, String line) {
            if (line == null) return;
            String value = line.trim();
            if (value.startsWith("[FILE_START:")) {
                currentFile = value.substring(12, Math.max(12, value.length() - 1));
                buffer.setLength(0);
                return;
            }
            if (value.startsWith("[FILE_END:") && currentFile != null) {
                currentFile = null;
                buffer.setLength(0);
                return;
            }
            if (currentFile != null) buffer.append(line).append('\n');
        }
    }

    private static final class EmptyApplicationHost implements ApplicationHost {
        @Override public void setScreen(Screen screen) {
        }

        @Override public Screen getCurrentScreen() {
            return null;
        }

        @Override public void ensureTextRenderer() {
        }

        @Override public MinecraftGameAssets getGameAssets() {
            return MinecraftGameAssets.EMPTY;
        }

        @Override public Object getFontIdentifier(String namespace, String path) {
            return null;
        }

        @Override public void openParentScreen(Screen currentScreen, Object parent) {
        }

        @Override public void setClipboard(String text) {
        }

        @Override public boolean shouldCloseRootScreen() {
            return false;
        }

        @Override public String getGameVersion() {
            return "";
        }

        @Override public String getGameUserName() {
            return "";
        }

        @Override public String getGameUUID() {
            return "";
        }
    }

    private ApplicationHost host() {
        return screenHost().application();
    }

    private static String message(Throwable failure) {
        if (failure == null) return "Unknown Error";
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? "Unknown Error" : message;
    }
}
