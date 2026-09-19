package redxax.oxy.remotely;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.config.RemotelyViewStateStore;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import redxax.oxy.remotely.network.NetworkManager;
import redxax.oxy.remotely.session.TerminalSessionManager;
import redxax.oxy.remotely.flow.registry.NodeDiscoveryPreferences;
import redxax.oxy.remotely.ui.server.PanelServerProvider;
import redxax.oxy.remotely.ui.server.RemoteHostConnectionProvider;
import redxax.oxy.remotely.ui.server.ServerManagerScreen;
import redxax.oxy.remotely.ui.server.ServerTerminal;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.text.FontRegistry;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncLiveServerSession;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rescreen.util.Notification;

import java.util.*;

public class RemotelyClient {

    public static volatile RemotelyClient INSTANCE;
    private final RemotelyComposition composition;
    private final ApplicationHost host;
    private final ServerUiCapabilityProvider serverUiCapabilityProvider;
    private final PanelServerProvider panelServerProvider;
    private final RemoteHostConnectionProvider remoteHostConnectionProvider;
    private int activeHostIndex = 0;
    public static String os;
    public static ITextRenderer tr;
    private final List<Object> multiTerminalTabs = BrowserSafeState.list();
    private int activeMultiTerminalTabIndex = 0;
    private final TerminalSessionManager sessionManager;
    private FlowManager flowManager;
    private NetworkManager<?, ?> networkManager;
    private RemotelyServerApi apiClient;
    private ServerManagerScreen serverManagerScreen;
    private final Map<String, ClientServerView> restudioServerViews = BrowserSafeState.map();

    public RemotelyClient(RemotelyComposition composition) {
        this.composition = Objects.requireNonNull(composition, "composition");
        this.host = composition.host();
        ApplicationHostRegistry.install(this.host);
        this.serverUiCapabilityProvider = Objects.requireNonNull(composition.serverUiCapabilityProvider(), "serverUiCapabilityProvider");
        this.panelServerProvider = Objects.requireNonNull(composition.panelServerProvider(), "panelServerProvider");
        this.remoteHostConnectionProvider = Objects.requireNonNull(composition.remoteHostConnectionProvider(), "remoteHostConnectionProvider");
        this.sessionManager = composition.createTerminalSessionManager();
        INSTANCE = this;
    }

    public void initialize() {
        Object applicationDirectory = composition.applicationDirectory();
        if (applicationDirectory != null) {
            Config.applicationDir = applicationDirectory;
        }
        RemotelyConfigStore configuredManager = composition.configManager();
        if (configuredManager != null) {
            Config.setConfigManager(configuredManager);
            NodeDiscoveryPreferences.configure(configuredManager);
        }

        if (composition.capabilities().has(RemotelyComposition.Capability.LOCAL_STORAGE) && Config.configManager != null) {
            ThemeManager.init();
        } else {
            ThemeManager.initBrowserDefaults();
        }
        host.ensureTextRenderer();
        networkManager = composition.createNetworkManager();
        if (networkManager != null) {
            if (!networkManager.getLoadError().isBlank()) {
                ReLog.logger(LogTypes.NETWORK).source(LogSource.application("Remotely")).component(RemotelyClient.class).with("reason", networkManager.getLoadError()).error("Could not load networks");
            }
            if (!networkManager.getJobLoadError().isBlank()) {
                ReLog.logger(LogTypes.NETWORK).source(LogSource.application("Remotely")).component(RemotelyClient.class).with("reason", networkManager.getJobLoadError()).error("Could not load network jobs");
            }
        }
        if (composition.capabilities().has(RemotelyComposition.Capability.NODE_REGISTRY)) {
            composition.createNodeRegistry();
        }
        ReLog.logger(LogTypes.APPLICATION).source(LogSource.application("Remotely")).component(RemotelyClient.class).info("Client initialized");
        if (composition.capabilities().has(RemotelyComposition.Capability.LOCAL_STORAGE)) {
            loadSnippets();
        }

        os = composition.platformName();
        if (os == null || os.isBlank()) {
            os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        }

        FontRegistry.MONO_FONT = host.getFontIdentifier("remotely", "mono");

        apiClient = composition.apiClient();
        if (apiClient == null && composition.capabilities().has(RemotelyComposition.Capability.REFLECTIVE_API_LOOKUP)) {
            apiClient = composition.createApiClient();
        }
        if (flowManager == null) {
            flowManager = composition.createFlowManager(this, apiClient);
        }
        Screen rootScreen = composition.createRootScreen(this);
        if (rootScreen instanceof ServerManagerScreen serverManager) {
            serverManagerScreen = serverManager;
        }
        if (host.managesPrimaryScreen() && composition.capabilities().has(RemotelyComposition.Capability.PRIMARY_SCREEN)) {
            ScreenManager.getInstance().setDesktopSuperScreenSupplier(this::getOrCreateServerManagerScreen);
        }
        if (rootScreen != null) {
            host.setScreen(rootScreen);
        }
        composition.initializePlatform(this);
    }

    private boolean supportsDesktopIntegrations() {
        return composition.capabilities().has(RemotelyComposition.Capability.DESKTOP_INTEGRATIONS) && host.supportsDesktopIntegrations();
    }

    public void openMultiTerminal(Object parent) {
        if (supportsDesktopIntegrations()) {
            host.setLocalTerminalActivity();
        }
        host.openTerminal(parent, null, this);
    }

    public void openInstanceInTerminal(Object parent, Object instance) {
        if (supportsDesktopIntegrations()) {
            host.setServerActivity(instance, "Terminal");
        }
        if (composition.environment() == RemotelyComposition.Environment.BROWSER) {
            host.openTerminal(parent, instance, this);
            return;
        }
        boolean found = multiTerminalTabs.stream().anyMatch(tab -> sameInstanceTab(tab, instance));
        if (!found) {
            multiTerminalTabs.add(instance);
        }

        for (int i = 0; i < multiTerminalTabs.size(); i++) {
            if (sameInstanceTab(multiTerminalTabs.get(i), instance)) {
                activeMultiTerminalTabIndex = i;
                break;
            }
        }

        host.openTerminal(parent, instance, this);
    }

    private boolean sameInstanceTab(Object tab, Object instance) {
        return Objects.equals(tab, instance);
    }

    public void openServerManager(Object parent) {
        if (supportsDesktopIntegrations()) {
            host.setManagerActivity();
        }
        if (composition.environment() == RemotelyComposition.Environment.DESKTOP && Config.desktopMode) {
            Screen desktopSuper = ScreenManager.getInstance().getDesktopSuperScreen();
            if (desktopSuper instanceof ServerManagerScreen existing) {
                serverManagerScreen = existing;
                host.setScreen(existing);
                return;
            }
            host.setScreen(getOrCreateServerManagerScreen());
            return;
        }
        host.setScreen(new ServerManagerScreen(parent, this));
    }

    private ServerManagerScreen getOrCreateServerManagerScreen() {
        Screen desktopSuper = ScreenManager.getInstance().getDesktopSuperScreen();
        if (desktopSuper instanceof ServerManagerScreen existing) {
            serverManagerScreen = existing;
            return existing;
        }
        if (serverManagerScreen == null) {
            serverManagerScreen = new ServerManagerScreen(null, this);
        }
        return serverManagerScreen;
    }

    public void openFileExplorer(Object parent, Object path) {
        host.openFileExplorer(parent, path, this);
    }

    public void openInstanceFiles(Object parent, Object instance) {
        host.openInstanceFiles(parent, instance, this);
    }

    public void openServerTwin(Object parent, Object instance) {
        host.openServerTwin(parent, instance, this);
    }

    public void shutdownAllTerminals() {
        ServerTerminal.shutdownAll();
        if (composition.capabilities().has(RemotelyComposition.Capability.DESKTOP_INTEGRATIONS)) {
            host.shutdownDesktopIntegrations();
        }
        if (flowManager != null) {
            flowManager.shutdown();
        }
        if (composition.serverSettingsCatalogService() != null) {
            composition.serverSettingsCatalogService().close();
        }
        if (networkManager != null) {
            networkManager.close();
        }
        if (composition.capabilities().has(RemotelyComposition.Capability.PRIMARY_SCREEN)
                || composition.capabilities().has(RemotelyComposition.Capability.LOCAL_PROCESSES)) {
            sessionManager.shutdownAll();
        }
        if (composition.capabilities().has(RemotelyComposition.Capability.LOCAL_PROCESSES)) {
            host.shutdownLocalTerminals();
        }
        if (composition.capabilities().has(RemotelyComposition.Capability.LOCAL_STORAGE)) {
            saveSnippets();
        }
    }

    public void saveTabIndex(int activeTabIndex) {
        activeHostIndex = activeTabIndex;
    }

    public int getSavedTabIndex() {
        return activeHostIndex;
    }

    public RemotelyViewStateStore.State getBrowserViewState() {
        if (composition.environment() != RemotelyComposition.Environment.BROWSER
                || !(composition.configManager() instanceof RemotelyViewStateStore store)) {
            return RemotelyViewStateStore.State.empty();
        }
        RemotelyViewStateStore.State state = store.getViewState();
        return state == null ? RemotelyViewStateStore.State.empty() : state;
    }

    public void saveBrowserHostKey(String hostKey) {
        if (composition.environment() != RemotelyComposition.Environment.BROWSER
                || !(composition.configManager() instanceof RemotelyViewStateStore store)) {
            return;
        }
        RemotelyViewStateStore.State current = getBrowserViewState();
        store.setViewState(new RemotelyViewStateStore.State(hostKey, current.serverId(), current.tabId(), current.viewId(),
                current.terminalTabs(), current.terminalTabIndex()));
    }

    public void saveBrowserDetailState(String serverId, String tabId, String viewId) {
        if (composition.environment() != RemotelyComposition.Environment.BROWSER
                || !(composition.configManager() instanceof RemotelyViewStateStore store)) {
            return;
        }
        RemotelyViewStateStore.State current = getBrowserViewState();
        store.setViewState(new RemotelyViewStateStore.State(current.hostKey(), serverId, tabId, viewId,
                current.terminalTabs(), current.terminalTabIndex()));
    }

    public void clearBrowserDetailState() {
        if (composition.environment() != RemotelyComposition.Environment.BROWSER
                || !(composition.configManager() instanceof RemotelyViewStateStore store)) {
            return;
        }
        RemotelyViewStateStore.State current = getBrowserViewState();
        store.setViewState(new RemotelyViewStateStore.State(current.hostKey(), "", "", "",
                current.terminalTabs(), current.terminalTabIndex()));
    }

    public void saveBrowserTerminalState(List<RemotelyViewStateStore.TerminalTab> tabs, int activeIndex) {
        if (composition.environment() != RemotelyComposition.Environment.BROWSER
                || !(composition.configManager() instanceof RemotelyViewStateStore store)) {
            return;
        }
        RemotelyViewStateStore.State current = getBrowserViewState();
        store.setViewState(new RemotelyViewStateStore.State(current.hostKey(), current.serverId(), current.tabId(), current.viewId(),
                tabs, activeIndex));
    }

    public void saveSnippets() {}
    public void loadSnippets() {}

    public boolean openExternal() {
        if (!composition.capabilities().has(RemotelyComposition.Capability.LOCAL_PROCESSES)) {
            return host.openExternal();
        }
        return host.openExternal();
    }

    public List<Object> getMultiTerminalTabs() {
        return multiTerminalTabs;
    }

    public int getActiveMultiTerminalTabIndex() {
        return activeMultiTerminalTabIndex;
    }

    public void setActiveMultiTerminalTabIndex(int activeMultiTerminalTabIndex) {
        this.activeMultiTerminalTabIndex = activeMultiTerminalTabIndex;
    }

    public ApplicationHost getHost() {
        return host;
    }

    public RemotelyComposition getComposition() {
        return composition;
    }

    public ServerUiCapabilityProvider getServerUiCapabilityProvider() {
        return serverUiCapabilityProvider;
    }

    public PanelServerProvider getPanelServerProvider() {
        return panelServerProvider;
    }

    public RemoteHostConnectionProvider getRemoteHostConnectionProvider() {
        return remoteHostConnectionProvider;
    }

    public TerminalSessionManager getSessionManager() {
        return sessionManager;
    }

    public FlowManager getFlowManager() {
        return flowManager;
    }

    public RemotelyServerApi getApiClient() {
        return apiClient;
    }

    @SuppressWarnings("unchecked")
    public <T, R> NetworkManager<T, R> getNetworkManager() {
        return (NetworkManager<T, R>) networkManager;
    }

    @SuppressWarnings("unchecked")
    public <T, R> NetworkManager<T, R> getNetworkManager(Class<T> instanceType, Class<R> reservationType) {
        return (NetworkManager<T, R>) networkManager;
    }

    @SuppressWarnings("unchecked")
    public <T> NetworkManager<T, Object> getNetworkManager(Class<T> instanceType) {
        return (NetworkManager<T, Object>) networkManager;
    }

    @SuppressWarnings("unchecked")
    public <T extends NetworkManager<?, ?>> T getNetworkManagerAs(Class<T> managerType) {
        return (T) networkManager;
    }

    public void openReSyncStudio(Object parent, Object instance) {
        openReSyncStudio(parent, instance, null);
    }

    public void openReSyncStudio(Object parent, Object instance, ClientServerView serverView) {
        host.openReSyncStudio(parent, instance, serverView, this);
    }

    public void cacheReStudioServerViews(Map<String, ClientServerView> views) {
        restudioServerViews.clear();
        restudioServerViews.putAll(views);
    }

    public void openLiveReSyncStudio(ReSyncLiveServerSession session) {
        if (flowManager == null) {
            new Notification.Builder().message("ReSync Studio Not Available").type(Notification.Type.WARN).build();
            return;
        }
        if (supportsDesktopIntegrations()) {
            host.setStudioActivity(null, session != null ? session.displayName() : "", "Live Studio");
        }
        flowManager.openLiveReSyncStudio(session);
    }

}
