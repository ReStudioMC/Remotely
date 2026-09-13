package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.network.NetworkAdoptionReport;
import redxax.oxy.remotely.network.NetworkCreationMember;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.config.RemotelyRecentItem;
import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.session.TerminalSession;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import restudio.rescreen.platform.Async;
import restudio.rebase.api.unified.internal.StandardOutputStateParser;
import restudio.rebase.backend.TerminalSessionProvider;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface ServerScreenHost {
    enum ServerManagerMode {
        FULL,
        REACTOR_ONLY
    }

    default ServerManagerMode serverManagerMode() {
        return ServerManagerMode.FULL;
    }

    enum ServerState {
        UNKNOWN,
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        CRASHED,
        INSTALLING;

        public static ServerState parse(String value) {
            if (value == null || value.isBlank()) return UNKNOWN;
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "OFFLINE", "STOPPED" -> STOPPED;
                case "STARTING", "STARTED" -> STARTING;
                case "RUNNING", "ONLINE" -> RUNNING;
                case "STOPPING" -> STOPPING;
                case "CRASHED", "FAILED" -> CRASHED;
                case "INSTALLING", "INSTALL" -> INSTALLING;
                default -> UNKNOWN;
            };
        }
    }

    enum ServerIconAccent {
        DEFAULT,
        NICE,
        CALM,
        DANGER
    }

    record ServerMetrics(long uptimeMs, double cpuPercent, long memoryBytes, long memoryLimitBytes, int players,
                         int maxPlayers) {
    }

    enum PrerequisiteState {
        VERIFIED,
        FAILED,
        NOT_APPLICABLE,
        UNAVAILABLE;

        public boolean allowsStart() {
            return this == VERIFIED || this == NOT_APPLICABLE;
        }
    }

    record ServerHealth(PrerequisiteState eula, PrerequisiteState serverJar, PrerequisiteState startScript, boolean healthy) {
        public ServerHealth {
            eula = eula == null ? PrerequisiteState.UNAVAILABLE : eula;
            serverJar = serverJar == null ? PrerequisiteState.UNAVAILABLE : serverJar;
            startScript = startScript == null ? PrerequisiteState.UNAVAILABLE : startScript;
            healthy = healthy && eula.allowsStart() && serverJar.allowsStart() && startScript.allowsStart();
        }

        public ServerHealth(boolean eulaAccepted, boolean hasServerJar, boolean hasStartScript, boolean healthy) {
            this(eulaAccepted ? PrerequisiteState.VERIFIED : PrerequisiteState.FAILED,
                    hasServerJar ? PrerequisiteState.VERIFIED : PrerequisiteState.FAILED,
                    hasStartScript ? PrerequisiteState.VERIFIED : PrerequisiteState.FAILED, healthy);
        }

        public boolean eulaAccepted() { return eula.allowsStart(); }
        public boolean hasServerJar() { return serverJar.allowsStart(); }
        public boolean hasStartScript() { return startScript.allowsStart(); }
    }

    default Async<String> connectionInfo(Object target) {
        return Async.completed("");
    }

    default String reProxyAddress(Object target) {
        return "";
    }

    default boolean localPortOpen(Object target) {
        return false;
    }

    record LocalStatus(boolean controllerAvailable, boolean knownSession, boolean ready, String state, String desiredState,
                       Integer exitCode, String lastError, long pid, long wrapperPid, long serverPid, List<Long> pids) {
        public LocalStatus(boolean knownSession, boolean ready, String state, String desiredState, Integer exitCode,
                           String lastError, boolean hasActiveProcesses) {
            this(true, knownSession, ready, state, desiredState, exitCode, lastError, 0, 0, 0,
                    hasActiveProcesses ? List.of(1L) : List.of());
        }

        public LocalStatus(boolean knownSession, boolean ready, String state, String desiredState, Integer exitCode,
                           String lastError) {
            this(true, knownSession, ready, state, desiredState, exitCode, lastError, 0, 0, 0, List.of());
        }

        public LocalStatus {
            state = state == null ? "" : state;
            desiredState = desiredState == null ? "" : desiredState;
            lastError = lastError == null ? "" : lastError;
            pids = pids == null ? List.of() : List.copyOf(pids);
        }

        public boolean hasActiveProcesses() {
            return pid > 0 || wrapperPid > 0 || serverPid > 0 || pids.stream().anyMatch(value -> value != null && value > 0);
        }

        public boolean noKnownSession() {
            return controllerAvailable && !knownSession;
        }

        public boolean stoppedWithoutProcesses() {
            String normalized = state.trim().toUpperCase(Locale.ROOT);
            return !hasActiveProcesses() && ("STOPPED".equals(normalized) || "CRASHED".equals(normalized));
        }
    }

    default void applyLocalStatus(Object target, LocalStatus status, TerminalSession session) {
        if (target != null && status != null && !status.state().isBlank()) {
            setState(target, ServerState.parse(status.state()));
        }
    }

    record ServerIdentity(String id, String name, String backendType, String path, boolean server, boolean local,
                          boolean installing) {
        public ServerIdentity {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            backendType = backendType == null ? "" : backendType;
            path = path == null ? "" : path;
        }
    }

    record AccountIdentity(boolean authenticated, String subjectId, String displayName, String avatarAsset) {
        public AccountIdentity {
            subjectId = subjectId == null ? "" : subjectId;
            displayName = displayName == null || displayName.isBlank() ? authenticated ? "Account" : "Sign In" : displayName;
            avatarAsset = avatarAsset == null || avatarAsset.isBlank() ? "steve.png" : avatarAsset;
        }
    }

    record EnvironmentNotice(String label, String description, String icon) {
        public EnvironmentNotice {
            label = label == null ? "" : label;
            description = description == null ? "" : description;
            icon = icon == null || icon.isBlank() ? "info.png" : icon;
        }

        public boolean visible() {
            return !label.isBlank();
        }
    }

    default EnvironmentNotice environmentNotice() {
        return new EnvironmentNotice("", "", "");
    }

    record ImportResult(int imported, int failed, boolean resourcesChanged, boolean worldsChanged) {
    }

    interface TerminalDataStream {
        Async<Void> stream(BiConsumer<Integer, String> onLine);

        void stop();
    }

    record HostView(String id, String name, String type, String address, int port, boolean connected, boolean panel,
                    String user, String authMode, String keyPath, String registryPath,
                    String password, String apiKey, String keyPassphrase) {
        public HostView(String id, String name, String type, String address, int port, boolean connected, boolean panel) {
            this(id, name, type, address, port, connected, panel, "", "PASSWORD", "", "", "", "", "");
        }

        public HostView(String id, String name, String type, String address, int port, boolean connected, boolean panel,
                        String user, String authMode, String keyPath, String registryPath) {
            this(id, name, type, address, port, connected, panel, user, authMode, keyPath, registryPath, "", "", "");
        }

        public HostView {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            type = type == null || type.isBlank() ? "SSH" : type;
            address = address == null ? "" : address;
            user = user == null ? "" : user;
            authMode = authMode == null || authMode.isBlank() ? "PASSWORD" : authMode;
            keyPath = keyPath == null ? "" : keyPath;
            registryPath = registryPath == null ? "" : registryPath;
            password = password == null ? "" : password;
            apiKey = apiKey == null ? "" : apiKey;
            keyPassphrase = keyPassphrase == null ? "" : keyPassphrase;
        }

        public HostView withConnected(boolean value) {
            return new HostView(id, name, type, address, port, value, panel, user, authMode, keyPath, registryPath,
                    password, apiKey, keyPassphrase);
        }
    }

    record RemoteHostDraft(String id, String name, String user, String address, int port, String type, String authMode,
                           String password, String apiKey, String keyPath, String keyPassphrase, String registryPath) {
        public RemoteHostDraft {
            id = id == null ? "" : id;
            name = name == null ? "" : name.trim();
            user = user == null ? "" : user.trim();
            address = address == null ? "" : address.trim();
            type = type == null || type.isBlank() ? "SSH" : type.trim().toUpperCase(Locale.ROOT);
            authMode = authMode == null || authMode.isBlank() ? "PASSWORD" : authMode.trim().toUpperCase(Locale.ROOT);
            password = password == null ? "" : password;
            apiKey = apiKey == null ? "" : apiKey;
            keyPath = keyPath == null ? "" : keyPath.trim();
            keyPassphrase = keyPassphrase == null ? "" : keyPassphrase;
            registryPath = registryPath == null ? "" : registryPath.trim();
        }
    }

    record NetworkView(String id, String name, String status, String description, List<String> members, boolean managed,
                       String proxyId) {
        public NetworkView(String id, String name, String status, String description, List<String> members, boolean managed) {
            this(id, name, status, description, members, managed, "");
        }

        public NetworkView {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            status = status == null || status.isBlank() ? "Unknown" : status;
            description = description == null ? "" : description;
            members = members == null ? List.of() : List.copyOf(members);
            proxyId = proxyId == null ? "" : proxyId;
        }
    }

    record NetworkJobView(boolean canResume, boolean canRollback, String status) {
        public NetworkJobView {
            status = status == null ? "" : status;
        }

        public boolean interruptedOrFailed() {
            return "INTERRUPTED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
        }
    }

    record GroupView(String id, String name, List<String> members) {
        public GroupView {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    record PlanView(String id, String name, int memoryMb, int diskMb, int cpuPercent, long priceCents) {
        public PlanView {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? "Reactor Plan" : name;
        }
    }

    record ConfigurationValues(String name, String version, String loader, String software, String build,
                               boolean linkedModpack, boolean msmpCompatible, boolean msmpEnabled,
                               Map<String, String> properties) {
        public ConfigurationValues {
            name = name == null ? "" : name;
            version = version == null ? "" : version;
            loader = loader == null ? "" : loader;
            software = software == null ? "" : software;
            build = build == null ? "" : build;
            properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    record ConfigurationState(Object original, Object draft, HostView remoteHost, boolean editMode,
                              boolean restudioBackend, boolean restudioCreation, String serverIdentifier,
                              String preselectedPlanName) {
        public ConfigurationState {
            serverIdentifier = serverIdentifier == null ? "" : serverIdentifier;
            preselectedPlanName = preselectedPlanName == null ? "" : preselectedPlanName;
        }
    }

    record ConfigurationUi(Map<String, Supplier<List<Setting>>> settings,
                           Runnable cleanup, String title, Supplier<String> planName,
                           Supplier<String> subdomain, Supplier<ServerModels.CustomPlanRequest> customPlan,
                           Supplier<String> localLocation) {
        public ConfigurationUi {
            settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            cleanup = cleanup == null ? () -> {} : cleanup;
            title = title == null || title.isBlank() ? "Server Configuration" : title;
            planName = planName == null ? () -> "" : planName;
            subdomain = subdomain == null ? () -> "" : subdomain;
            customPlan = customPlan == null ? () -> null : customPlan;
            localLocation = localLocation == null ? () -> "" : localLocation;
        }
    }

    enum Action {
        GLOBAL_TERMINAL,
        FILE_EXPLORER,
        SERVER_CONFIGURATION,
        DEVELOPMENT,
        RESYNC_STUDIO,
        NETWORK_SETTINGS,
        NETWORK_CREATE,
        NETWORK_IMPORT,
        NETWORK_RECOVER,
        NETWORK_LIFECYCLE,
        NETWORK_MEMBERSHIP,
        NETWORK_MIGRATION,
        CREATE_SERVER,
        IMPORT_SERVER,
        MODPACK_SERVER,
        REACTOR_PLANS,
        RESYNC_PROVISION,
        RESYNC_UPDATE,
        REMOTE_HOST,
        HOST_TEST,
        HOST_DELETE,
        HOST_SETTINGS,
        OPEN_PANEL,
        CUSTOMIZE_ICON,
        INBOX,
        REPORTS,
        SIGN_IN,
        SIGN_OUT,
        WORLD,
        DUPLICATE_SERVER,
        DELETE_SERVER
    }

    record ActionAvailability(boolean available, String reason) {
        public ActionAvailability {
            reason = reason == null ? "" : reason;
        }

        public static ActionAvailability enabled() {
            return new ActionAvailability(true, "");
        }

        public static ActionAvailability disabled(String reason) {
            return new ActionAvailability(false, reason);
        }
    }

    ApplicationHost application();

    default ServerConfigurationTarget configurationTarget(Object value) {
        return ServerConfigurationTarget.unavailable(value);
    }

    default ServerConfigurationTarget copyConfigurationTarget(Object value, String name) {
        return configurationTarget(value);
    }

    default ServerConfigurationTarget createConfigurationTarget() {
        return ServerConfigurationTarget.unavailable(null);
    }

    default HostView hostView(Object value) {
        return value instanceof HostView host ? host : null;
    }

    default void configureTargetDefaults(ServerConfigurationTarget target) {
    }

    default void applyTargetPreset(ServerConfigurationTarget target, Object preset) {
        if (target != null && preset != null) {
            target.modLoader(preset);
        }
    }

    default void configureRemoteTarget(ServerConfigurationTarget target, HostView host, ServerConfigurationTarget source) {
    }

    default void openExternal(String url) {
        if (url != null && !url.isBlank()) {
            application().notify("Open Link", url, ReSyncNotificationLevel.INFO);
        }
    }

    default ConfigurationState createConfigurationState(Object original, Object remoteHost, Object preset,
                                                         boolean restudioCreation, String preselectedPlanName) {
        return new ConfigurationState(original, original, remoteHost instanceof HostView host ? host : null,
                original != null, false, restudioCreation, "", preselectedPlanName);
    }

    default ConfigurationValues configurationValues(Object value) {
        return new ConfigurationValues("", "", "", "", "", false, false, false, Map.of());
    }

    default ConfigurationUi createConfigurationUi(Screen owner, ConfigurationState state,
                                                   ServerSettingsDataController settingsController,
                                                   Map<String, String> remoteVariables,
                                                   List<String> extraFiles,
                                                   Runnable reloadDataDrivenSettings,
                                                   BooleanSupplier allowServerSoftwareChange) {
        return new ConfigurationUi(Map.of(), () -> {}, state != null && state.editMode()
                ? "Edit Server" : state != null && state.restudioCreation() ? "Order New Server" : "Create New Server",
                () -> "", () -> "", () -> null, this::defaultInstanceLocation);
    }

    default void setConfigurationName(Object value, String name) {
    }

    default void setConfigurationState(Object value, String state) {
    }

    default void copyConfigurationState(Object source, Object destination) {
    }

    default void removeConfigurationProperty(Object value, String key) {
    }

    default void setConfigurationProperty(Object value, String key, String propertyValue) {
    }

    default String configurationProperty(Object value, String key, String fallback) {
        ConfigurationValues values = configurationValues(value);
        return values.properties().getOrDefault(key, fallback);
    }

    default void recordConfigurationLog(Object value, String message) {
    }

    default ServerUiCapabilityProvider capabilities(ServerModels.ClientServerView server) {
        return ServerUiCapabilityProvider.unavailable();
    }

    default ServerModels.ClientServerView serverView(Object target) {
        return target instanceof ServerModels.ClientServerView server ? server : null;
    }

    default ServerIconAccent serverIconAccent(ServerModels.ClientServerView server, boolean isCreate) {
        if (server == null || isCreate) return ServerIconAccent.DEFAULT;
        if (server.isSuspended) return ServerIconAccent.DANGER;
        if (server.isInstalling) return ServerIconAccent.CALM;
        return switch (state(server)) {
            case RUNNING, STARTING, STOPPING -> ServerIconAccent.NICE;
            case CRASHED -> ServerIconAccent.DANGER;
            default -> ServerIconAccent.DEFAULT;
        };
    }

    default ServerIconProvider iconProvider() {
        return ServerIconProvider.logical();
    }

    default Object iconTarget(ServerModels.ClientServerView server) {
        return server;
    }

    default ServerDetailsTarget detailsTarget(Object target) {
        if (target instanceof ServerDetailsTarget value) return value;
        if (target instanceof ServerModels.ClientServerView server) return new ClientServerDetailsTarget(server);
        return ServerDetailsTarget.unavailable(target);
    }

    default String serverOrderKey(ServerModels.ClientServerView server) {
        return serverId(server);
    }

    default ServerIdentity identity(Object target) {
        ServerDetailsTarget value = detailsTarget(target);
        return new ServerIdentity(value.id(), value.name(), value.backendType(), value.path(), value.server(), value.local(),
                value.state() == ServerState.INSTALLING);
    }

    default ServerState state(Object target) {
        return detailsTarget(target).state();
    }

    default void setState(Object target, ServerState state) {
        detailsTarget(target).setState(state);
    }

    default void setViewContext(String contextId) {
        ScreenManager.getInstance().setViewContext(contextId);
    }

    default void addStateListener(Object target, Consumer<ServerState> listener) {
    }

    default void removeStateListener(Object target, Consumer<ServerState> listener) {
    }

    default void onServerTabSelected(Object target) {
    }

    default void onServerTabClosed(Object target) {
    }

    default String msmpStatus(Object target) {
        return "Unavailable";
    }

    default boolean isServer(Object target) {
        return identity(target).server();
    }

    default boolean isLocal(Object target) {
        return identity(target).local();
    }

    default boolean isPanel(Object target) {
        return "PTERO".equalsIgnoreCase(identity(target).backendType()) || "CALAGOPUS".equalsIgnoreCase(identity(target).backendType());
    }

    default boolean isQuickServer(Object target) {
        return detailsTarget(target).quickServer();
    }

    default boolean isReProxyForwarded(Object target) {
        return false;
    }

    default boolean supportsReProxy(Object target) {
        return isLocal(target);
    }

    default void refreshReProxy(Object target, Runnable onComplete) {
    }

    default void startReProxy(Object target, Runnable onComplete) {
        unavailable(Action.NETWORK_LIFECYCLE);
    }

    default void stopReProxy(Object target, Runnable onComplete) {
        unavailable(Action.NETWORK_LIFECYCLE);
    }

    default Async<Void> startServer(RemotelyServerApi api, Object target) {
        ServerModels.ClientServerView server = serverView(target);
        return server == null ? Async.failed(new UnsupportedOperationException("Server Start Is Unavailable"))
                : setServerPower(api, server, "start");
    }

    default Async<Void> stopServer(RemotelyServerApi api, Object target) {
        ServerModels.ClientServerView server = serverView(target);
        return server == null ? Async.failed(new UnsupportedOperationException("Server Stop Is Unavailable"))
                : setServerPower(api, server, "stop");
    }

    default Async<Void> killServer(RemotelyServerApi api, Object target) {
        ServerModels.ClientServerView server = serverView(target);
        return server == null ? Async.failed(new UnsupportedOperationException("Server Kill Is Unavailable"))
                : setServerPower(api, server, "kill");
    }

    default ServerUiCapabilityProvider.Availability killAvailability(Object target) {
        return ServerUiCapabilityProvider.Availability.supported();
    }

    default Async<ServerMetrics> metrics(RemotelyServerApi api, Object target) {
        ServerModels.ClientServerView server = serverView(target);
        if (server == null) return Async.failed(new UnsupportedOperationException("Server Statistics Are Unavailable"));
        return capabilities(server).stats(server).thenApply(stats -> {
            if (stats == null || stats.resources == null) return new ServerMetrics(0, 0, 0, 0, 0, 0);
            long limitMegabytes = stats.resources.limits == null || stats.resources.limits.memory == null
                    ? 0 : stats.resources.limits.memory.longValue();
            long limit = limitMegabytes <= 0 ? 0 : limitMegabytes * 1024L * 1024L;
            return new ServerMetrics(stats.resources.uptime, stats.resources.cpuAbsolute, stats.resources.memoryBytes,
                    limit, 0, 0);
        });
    }

    default Async<Void> reloadSettings(Object target, boolean remote) {
        return Async.completed(null);
    }

    default Async<Void> attachTerminal(Object target, TerminalWidget terminal) {
        detailsTarget(target).attachTerminal(terminal);
        return Async.completed(null);
    }

    default Async<Void> detachTerminal(Object target, TerminalWidget terminal) {
        detailsTarget(target).detachTerminal();
        return Async.completed(null);
    }

    default void attachOutputParser(Object target, BiConsumer<Integer, String> parser) {
        detailsTarget(target).addLogListener(parser);
    }

    default void detachOutputParser(Object target, BiConsumer<Integer, String> parser) {
        detailsTarget(target).removeLogListener(parser);
    }

    default TerminalDataStream dataStream(Object target, BiConsumer<Integer, String> onLine) {
        return null;
    }

    default StandardOutputStateParser standardParser(Object target) {
        return createStandardOutputParser(target);
    }

    default boolean supportsDevelopment(Object target) {
        return developmentProvider().available();
    }

    default Object createDevelopmentTarget(Object target) {
        return twinLocal(target);
    }

    default ServerDevelopmentProvider developmentProvider() {
        return ServerDevelopmentProvider.unavailableProvider();
    }

    default String developmentConfigPath() {
        return "data";
    }

    default void closeServerScreen(Screen current, Object parent) {
        application().openParentScreen(current, parent);
    }

    default String screenId() {
        return "server-details";
    }

    default String screenTitle() {
        return "Terminal";
    }

    default String screenIcon() {
        return "terminal.png";
    }

    default boolean mergeServerScreen(Screen current, ServerModels.ClientServerView server, boolean openDevelopment) {
        return false;
    }

    default Async<List<ServerModels.ClientServerView>> localServers() {
        return Async.completed(List.of());
    }

    default Async<List<ServerModels.ClientServerView>> restudioServers() {
        return Async.completed(List.of());
    }

    default List<RemotelyRecentItem> recentRestudioItems() {
        return List.of();
    }

    default void recordRecentRestudioItem(RemotelyRecentItem item) {
    }

    default void removeRecentRestudioItem(RemotelyRecentItem item) {
    }

    default void recordRecentRestudioServer(String serverId, String path, String name) {
        recordRecentRestudioItem(new RemotelyRecentItem(RemotelyRecentItem.Kind.SERVER, serverId, path, name));
    }

    default void recordRecentRestudioItem(String serverId, String path, String name) {
        recordRecentRestudioItem(new RemotelyRecentItem(RemotelyRecentItem.Kind.ITEM, serverId, path, name));
    }

    default boolean openRecentRestudioItem(Screen current, RemotelyRecentItem item) {
        return false;
    }

    default boolean openRecentRestudioPath(Screen current, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (RemotelyRecentItem item : recentRestudioItems()) {
            if (item != null && path.equals(item.path()) && openRecentRestudioItem(current, item)) {
                return true;
            }
        }
        return false;
    }

    default void reloadInstances() {
    }

    default AccountIdentity accountIdentity() {
        return new AccountIdentity(authenticated(), "", "", "steve.png");
    }

    default Async<Identifier> accountAvatar() {
        return Async.completed(null);
    }

    default boolean authenticated() {
        return false;
    }

    default Async<List<HostView>> remoteHosts() {
        return Async.completed(List.of());
    }

    default Async<List<ServerModels.ClientServerView>> hostServers(HostView host) {
        return Async.completed(List.of());
    }

    default Async<List<NetworkView>> networks() {
        return Async.completed(List.of());
    }

    default NetworkJobView latestNetworkJob(NetworkView network) {
        return null;
    }

    default NetworkOverviewProvider networkOverviewProvider(RemotelyClient client) {
        return networkOverviewProvider();
    }

    default NetworkOverviewProvider networkOverviewProvider() {
        return NetworkOverviewProvider.from(this);
    }

    default Async<List<GroupView>> groups(String context) {
        return Async.completed(List.of());
    }

    default Async<List<PlanView>> reactorPlans() {
        return Async.completed(List.of());
    }

    default Async<Void> serverAction(ServerModels.ClientServerView server, String action) {
        return Async.failed(new UnsupportedOperationException("Server Action Is Unavailable"));
    }

    default Async<Void> hostAction(HostView host, String action) {
        return Async.failed(new UnsupportedOperationException("Host Action Is Unavailable"));
    }

    default Async<HostView> saveRemoteHost(RemoteHostDraft draft) {
        return Async.failed(new UnsupportedOperationException("Remote Host Configuration Is Unavailable"));
    }

    default Async<HostView> testAndSaveRemoteHost(RemoteHostDraft draft) {
        return saveRemoteHost(draft).thenCompose(host -> hostAction(host, "test").thenApply(ignored -> host));
    }

    default Async<NetworkAdoptionReport> scanNetwork(ServerModels.ClientServerView proxy, boolean migrate) {
        return Async.failed(new UnsupportedOperationException("Network Import Requires A Connected Desktop Host"));
    }

    default void openNetworkImport(Screen parent, ServerModels.ClientServerView proxy, NetworkAdoptionReport report, boolean migrate) {
        throw new UnsupportedOperationException("Network Import Requires A Connected Desktop Host");
    }

    default Async<Void> networkAction(NetworkView network, String action) {
        return Async.failed(new UnsupportedOperationException("Network Action Is Unavailable"));
    }

    default Async<Void> createNetwork(String name, String proxyId, List<String> backendIds, boolean installReSync) {
        return Async.failed(new UnsupportedOperationException("Network Creation Is Unavailable"));
    }

    default Async<Void> createNetwork(String name, String proxyId, int entryPort, List<NetworkCreationMember> backends, boolean installReSync) {
        return createNetwork(name, proxyId, backends.stream().map(NetworkCreationMember::instanceId).toList(), installReSync);
    }

    default Async<String> createNetwork(NetworkCreationPlan plan) {
        Objects.requireNonNull(plan, "Network creation plan is required");
        List<NetworkCreationPlan.Server> proxies = plan.servers().stream().filter(NetworkCreationPlan.Server::proxy).toList();
        List<NetworkCreationPlan.Server> backends = plan.servers().stream().filter(server -> !server.proxy()).toList();
        if (plan.name().isBlank()) return Async.failed(new IllegalArgumentException("Network Name Is Required"));
        if (plan.entryPort() < 1 || plan.entryPort() > 65535) return Async.failed(new IllegalArgumentException("Player Port Must Be Between 1 And 65535"));
        if (proxies.size() != 1) return Async.failed(new IllegalArgumentException("One Proxy Is Required"));
        if (backends.isEmpty()) return Async.failed(new IllegalArgumentException("At Least One Backend Is Required"));
        Set<String> existingIds = new LinkedHashSet<>();
        Set<String> routes = new LinkedHashSet<>();
        for (NetworkCreationPlan.Server server : plan.servers()) {
            if (server.existing() && !existingIds.add(server.existingId())) {
                return Async.failed(new IllegalArgumentException("A Server Is Selected More Than Once"));
            }
            if (!server.existing() && (server.template() == null || server.settings() == null)) {
                return Async.failed(new IllegalArgumentException("Every New Server Needs A Complete Configuration"));
            }
            if (!server.proxy()) {
                String route = server.route().toLowerCase(Locale.ROOT);
                if (route.isBlank()) return Async.failed(new IllegalArgumentException("Every Backend Needs A Route Name"));
                if (!routes.add(route)) return Async.failed(new IllegalArgumentException("Backend Route Names Must Be Unique"));
            }
        }
        Map<NetworkCreationPlan.Server, Object> resolved = new LinkedHashMap<>();
        Async<Void> creation;
        try {
            creation = Objects.requireNonNull(validateNetworkCreationPlan(plan), "Network validation result is required");
        } catch (RuntimeException error) {
            creation = Async.failed(error);
        }
        for (NetworkCreationPlan.Server server : plan.servers()) {
            if (server.existing()) continue;
            creation = creation.thenCompose(ignored -> createNetworkServer(server).thenCompose(value -> {
                if (value == null) return Async.failed(new IllegalStateException("Created Server Is Unavailable"));
                resolved.put(server, value);
                try {
                    return Objects.requireNonNull(saveInstanceConfiguration(value, server.settings()), "Server configuration save result is required");
                } catch (RuntimeException error) {
                    return Async.failed(error);
                }
            }));
        }
        Async<String> transaction = creation.thenCompose(ignored -> {
            String proxyId = networkServerId(proxies.getFirst(), resolved);
            List<NetworkCreationMember> members = backends.stream().map(server -> new NetworkCreationMember(
                networkServerId(server, resolved), server.route(), server.role(), "", 0, server.capacity(), server.reSync())).toList();
            return createNetwork(plan.name(), proxyId, plan.entryPort(), members, proxies.getFirst().reSync()).thenApply(completed -> proxyId);
        });
        return transaction.exceptionallyCompose(failure -> rollbackNetworkServers(resolved, failure)
            .thenCompose(ignored -> Async.failed(failure)));
    }

    default Async<Void> validateNetworkCreationPlan(NetworkCreationPlan plan) {
        return Async.completed(null);
    }

    default Async<Object> createNetworkServer(NetworkCreationPlan.Server server) {
        Async<Object> creation;
        try {
            creation = server.host() == null
                ? createLocalInstance(server.template(), server.location())
                : createRemoteInstance(server.template(), server.host());
            creation = Objects.requireNonNull(creation, "Server creation result is required");
        } catch (RuntimeException error) {
            creation = Async.failed(error);
        }
        return creation.thenApply(value -> Objects.requireNonNull(value, "Created server is required"))
            .exceptionallyCompose(failure -> rollbackPartialNetworkServer(server, failure)
            .thenCompose(ignored -> Async.failed(failure)));
    }

    private String networkServerId(NetworkCreationPlan.Server server, Map<NetworkCreationPlan.Server, Object> resolved) {
        if (server.existing()) return server.existingId();
        ServerModels.ClientServerView view = serverView(resolved.get(server));
        String identifier = view == null ? "" : view.identifier;
        if (identifier == null || identifier.isBlank()) identifier = view == null ? "" : view.uuid;
        if (identifier == null || identifier.isBlank()) throw new IllegalStateException("Created Server Identity Is Unavailable");
        return identifier;
    }

    private Async<Void> rollbackNetworkServers(Map<NetworkCreationPlan.Server, Object> created, Throwable failure) {
        Async<Void> rollback = Async.completed(null);
        List<Map.Entry<NetworkCreationPlan.Server, Object>> entries = new ArrayList<>(created.entrySet());
        for (int index = entries.size() - 1; index >= 0; index--) {
            Map.Entry<NetworkCreationPlan.Server, Object> entry = entries.get(index);
            rollback = rollback.thenCompose(ignored -> rollbackNetworkServer(entry.getKey(), entry.getValue(), failure));
        }
        return rollback;
    }

    private Async<Void> rollbackPartialNetworkServer(NetworkCreationPlan.Server plan, Throwable failure) {
        Async<Void> removal;
        try {
            removal = Objects.requireNonNull(discardPartialNetworkServer(plan), "Partial server rollback result is required");
        } catch (RuntimeException rollbackFailure) {
            if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
            return Async.completed(null);
        }
        return removal.handle((value, rollbackFailure) -> {
            if (rollbackFailure != null && rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
            return null;
        });
    }

    private Async<Void> rollbackNetworkServer(NetworkCreationPlan.Server plan, Object server, Throwable failure) {
        Async<Void> removal;
        try {
            removal = Objects.requireNonNull(discardCreatedServer(plan, server), "Created server rollback result is required");
        } catch (RuntimeException rollbackFailure) {
            if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
            return Async.completed(null);
        }
        return removal.handle((value, rollbackFailure) -> {
            if (rollbackFailure != null && rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
            return null;
        });
    }

    default Async<Void> discardCreatedServer(Object server) {
        return Async.failed(new UnsupportedOperationException("Created Server Rollback Is Unavailable"));
    }

    default Async<Void> discardCreatedServer(NetworkCreationPlan.Server plan, Object server) {
        return discardCreatedServer(server);
    }

    default Async<Void> discardPartialNetworkServer(NetworkCreationPlan.Server plan) {
        return Async.completed(null);
    }

    default Async<Void> networkServerAction(String networkId, String serverId, String action, boolean installReSync) {
        return Async.failed(new UnsupportedOperationException("Network Membership Is Unavailable"));
    }

    default boolean networkReSyncEnabled(NetworkView network) {
        return false;
    }

    default Async<Void> saveServerOrder(String context, List<String> serverIds) {
        return Async.completed(null);
    }

    default Async<Void> saveGroup(String context, GroupView group) {
        return Async.completed(null);
    }

    default Async<Void> saveConfiguredGroups(String context, List<GroupView> groups) {
        Async<Void> result = Async.completed(null);
        for (GroupView group : groups == null ? List.<GroupView>of() : groups) {
            result = result.thenCompose(ignored -> saveGroup(context, group));
        }
        return result;
    }

    default Async<Void> customizeIcon(ServerModels.ClientServerView server, HostView host, Identifier icon, Runnable onComplete) {
        return Async.failed(new UnsupportedOperationException("Icon Customization Is Unavailable"));
    }

    default void openIconCustomizer(Screen current, ServerModels.ClientServerView server, HostView host, Runnable onComplete) {
        unavailable(Action.CUSTOMIZE_ICON);
    }

    default void addInstanceChangeListener(Runnable listener) {
    }

    default void removeInstanceChangeListener(Runnable listener) {
    }

    default void addNetworkChangeListener(Runnable listener) {
    }

    default void removeNetworkChangeListener(Runnable listener) {
    }

    default void addRuntimeChangeListener(Runnable listener) {
    }

    default void removeRuntimeChangeListener(Runnable listener) {
    }

    default void addAuthStateListener(Runnable listener) {
    }

    default void removeAuthStateListener(Runnable listener) {
    }

    default void openPanel(Screen current, HostView host) {
        unavailable(Action.OPEN_PANEL);
    }

    default void openImportExplorer(Screen current, HostView host, ServerModels.ClientServerView server) {
        importServer(current);
    }

    default void openInbox(Screen current) {
        unavailable(Action.INBOX);
    }

    default void openReports(Screen current) {
        unavailable(Action.REPORTS);
    }

    default void signIn(Screen current) {
        unavailable(Action.SIGN_IN);
    }

    default void signOut(Screen current) {
        unavailable(Action.SIGN_OUT);
    }

    default void openSettings(Screen current) {
        unavailable(Action.HOST_SETTINGS);
    }

    default void openModpackBrowser(Screen current) {
        unavailable(Action.MODPACK_SERVER);
    }

    default void openModpackBrowser(Screen current, HostView remoteHost, boolean reStudioContext) {
        openModpackBrowser(current);
    }

    default void openReactorPlans(Screen current) {
        unavailable(Action.REACTOR_PLANS);
    }

    default void openReactorPlan(Screen current, PlanView plan) {
        openReactorPlans(current);
    }

    default Async<Void> setServerPower(RemotelyServerApi api, ServerModels.ClientServerView server, String signal) {
        return api.setServerPower(serverId(server), signal);
    }

    default Async<Void> repairServer(ServerModels.ClientServerView server, String repair) {
        if ("eula".equalsIgnoreCase(repair == null ? "" : repair.trim())) {
            return Async.completed(null);
        }
        return Async.failed(new UnsupportedOperationException("Server Repair Is Unavailable"));
    }

    default ServerUiCapabilityProvider.Availability healthRepairAvailability(ServerModels.ClientServerView server, String repair) {
        return ServerUiCapabilityProvider.Availability.supported();
    }

    default Async<ServerHealth> serverHealth(ServerModels.ClientServerView server) {
        return Async.failed(new UnsupportedOperationException("Server Health Is Unavailable"));
    }

    default Async<LocalStatus> localStatus(Object target) {
        return Async.completed(null);
    }

    default TerminalSessionProvider terminalProvider(RemotelyServerApi api, ServerModels.ClientServerView server) {
        return null;
    }

    default ServerTerminalPlatform terminalPlatform(RemotelyServerApi api, ServerModels.ClientServerView server) {
        return ServerTerminalPlatform.NONE;
    }

    default NewTerminalTargetProvider newTerminalTargetProvider() {
        return NewTerminalTargetProvider.local();
    }

    default ResourceContainerAdapter createResourceContainer(ReScreen host, Object instance, ServerModels.ClientServerView server,
                                                              int x, int y, int width, int height) {
        return null;
    }

    default StandardOutputStateParser createStandardOutputParser(Object instance) {
        return null;
    }

    default TerminalWidget createTerminal(RemotelyServerApi api, ServerModels.ClientServerView server, String id,
                                          int x, int y, int width, int height, TerminalSessionProvider provider) {
        TerminalSessionProvider resolved = provider == null ? terminalProvider(api, server) : provider;
        return ServerTerminal.getOrCreate(id, this, api, server, x, y, width, height, resolved);
    }


    default TerminalWidget createLocalTerminal(String id, int x, int y, int width, int height) {
        return TerminalWidget.getOrCreate(id, x, y, width, height, null);
    }

    default void shutdownTerminal(String id) {
        ServerTerminal.shutdown(id);
    }

    default void prepareTerminalStart(TerminalWidget terminal) {
    }

    default boolean terminalStartsServerProcess(RemotelyServerApi api, ServerModels.ClientServerView server) {
        return false;
    }

    default boolean terminalRestartsOnCrash(RemotelyServerApi api, ServerModels.ClientServerView server) {
        return false;
    }

    default TerminalDataStream terminalDataStream(RemotelyServerApi api, ServerModels.ClientServerView server) {
        return null;
    }

    default Async<String> retainedTerminalOutput(RemotelyServerApi api, ServerModels.ClientServerView server) {
        return Async.completed("");
    }

    default void configureTerminal(RemotelyServerApi api, ServerModels.ClientServerView server, TerminalWidget terminal) {
    }

    default void configureTerminalInput(RemotelyServerApi api, ServerModels.ClientServerView server, TerminalWidget terminal) {
        if (terminal != null) terminal.disableFakeInput();
    }

    default void recordTerminalNotice(RemotelyServerApi api, ServerModels.ClientServerView server, String line) {
    }

    default Async<ServerModels.ServerStatus> serverStatus(RemotelyServerApi api, ServerModels.ClientServerView server) {
        return api.getServerStatus(serverId(server));
    }

    default String requestStart(Object instance) {
        return "";
    }

    default String requestStop(Object instance) {
        return "";
    }

    default String activeOperationId(Object instance) {
        return "";
    }

    default void completeOperation(Object instance, String operationId, Object state) {
    }

    default void restoreRunning(Object instance, String operationId, String reason) {
    }

    default void failOperation(Object instance, String operationId, Object state, String reason) {
    }

    default void completeServerOperation(Object instance, String operationId, ServerState state) {
        completeOperation(instance, operationId, detailsTarget(instance).nativeState(state));
    }

    default void failServerOperation(Object instance, String operationId, ServerState state, String reason) {
        failOperation(instance, operationId, detailsTarget(instance).nativeState(state), reason);
    }

    default void beginStart(Object instance) {
    }

    default void markReady(Object instance, String operationId) {
        setState(instance, ServerState.RUNNING);
    }

    default boolean isStopPending(Object instance) {
        return false;
    }

    default Async<Void> loadInstanceProperties(Object instance, boolean remote) {
        return Async.completed(null);
    }

    default <T> Async<T> configurationLoad(String operation, Async<T> load, Supplier<T> fallback) {
        return load;
    }

    default Async<Void> reloadInstanceSettings(Object instance, boolean remote) {
        return Async.completed(null);
    }

    default Async<List<String>> listInstanceFiles(Object instance) {
        return Async.completed(List.of());
    }

    default Async<Void> loadInstanceModpack(Object instance) {
        return Async.completed(null);
    }

    default String defaultInstanceLocation() {
        return "";
    }

    default Async<Object> createLocalInstance(Object template, String location) {
        return Async.failed(new UnsupportedOperationException("Local Server Creation Is Unavailable"));
    }

    default Async<Object> createRemoteInstance(Object template, HostView remoteHost) {
        return Async.failed(new UnsupportedOperationException("Remote Server Creation Is Unavailable"));
    }

    default Async<Void> saveInstanceConfiguration(Object instance, ServerSettingsDataController settingsController) {
        return Async.failed(new UnsupportedOperationException("Server Configuration Save Is Unavailable"));
    }

    default ServerSettingsDataController createServerSettingsController(Object instance, ServerSettingsSnapshot snapshot) {
        return ServerSettingsDataController.unavailable();
    }

    default Async<Void> applyInstanceEdit(Object original, Object template, String newName,
                                           boolean repairStartScript, boolean reinstallSoftware,
                                           Notification notification, ServerSettingsDataController settingsController) {
        return Async.failed(new UnsupportedOperationException("Server Configuration Edit Is Unavailable"));
    }

    default Async<Void> refreshRemoteInstance(HostView remoteHost) {
        return Async.completed(null);
    }

    default HostView resolveRemoteHost(Object instance, HostView context) {
        return context;
    }

    default Async<ServerModels.CheckoutResponse> createHostedCheckout(String serverName, String planName,
                                                                        Map<String, String> environment,
                                                                        Map<String, String> fileConfigs,
                                                                        String subdomain,
                                                                        ServerModels.CustomPlanRequest customPlan) {
        return Async.failed(new UnsupportedOperationException("Hosted Server Creation Is Unavailable"));
    }

    default Async<Void> writeInstanceFile(Object instance, String path, String content) {
        return Async.failed(new UnsupportedOperationException("Instance File Write Is Unavailable"));
    }

    default Async<ImportResult> importDroppedFiles(Object target, List<?> files) {
        return Async.failed(new UnsupportedOperationException("File Import Is Unavailable"));
    }

    default Object twinLocal(Object remote) {
        return null;
    }

    default Object developmentSource(Object candidate) {
        return candidate;
    }

    default Async<Void> renameInstance(Object instance, String name) {
        return Async.failed(new UnsupportedOperationException("Instance Rename Is Unavailable"));
    }

    default Async<Void> renameRemoteServer(Object instance, String name) {
        return Async.completed(null);
    }

    default Async<Void> sendServerCommand(RemotelyServerApi api, ServerModels.ClientServerView server, String command) {
        return api.sendServerCommand(serverId(server), command);
    }

    default boolean supports(Action action) {
        return action == null || application().supportsDesktopIntegrations();
    }

    default ActionAvailability managerAction(Action action, Object target) {
        if (supports(action)) {
            return ActionAvailability.enabled();
        }
        String label = action == null ? "Action" : action.name().replace('_', ' ');
        return ActionAvailability.disabled(label + " Is Unavailable");
    }

    default void openGlobalTerminal(Screen current) {
        unavailable(Action.GLOBAL_TERMINAL);
    }

    default void openGlobalFileExplorer(Screen current) {
        unavailable(Action.FILE_EXPLORER);
    }

    default void openFileExplorer(Screen current, ServerModels.ClientServerView server) {
        application().openRemoteFiles(current, server);
    }

    default void openFileExplorer(Screen current, Object instance) {
        application().openRemoteFiles(current, instance);
    }

    default void openServerConfiguration(Screen current, ServerModels.ClientServerView server) {
        unavailable(Action.SERVER_CONFIGURATION);
    }

    default void openServerConfiguration(Screen current, Object instance) {
        unavailable(Action.SERVER_CONFIGURATION);
    }

    default void openDevelopment(Screen current, ServerModels.ClientServerView server) {
        unavailable(Action.DEVELOPMENT);
    }

    default void openReSyncStudio(Screen current, ServerModels.ClientServerView server) {
        unavailable(Action.RESYNC_STUDIO);
    }

    default void openNetworkSettings(Screen current, String networkId) {
        unavailable(Action.NETWORK_SETTINGS);
    }

    default void createServer(Screen current) {
        unavailable(Action.CREATE_SERVER);
    }

    default void createServer(Screen current, HostView remoteHost) {
        createServer(current);
    }

    default void createServer(Screen current, HostView remoteHost, Object preset, Consumer<Object> creationCallback) {
        createServer(current, remoteHost);
    }

    default void importServer(Screen current) {
        unavailable(Action.IMPORT_SERVER);
    }

    default void openRemoteHost(Screen current) {
        unavailable(Action.REMOTE_HOST);
    }

    default boolean openServerPath(Screen current, String path) {
        return false;
    }

    default void openWorld(Screen current, ServerModels.ClientServerView server) {
        unavailable(Action.WORLD);
    }

    default void duplicateServer(Screen current, ServerModels.ClientServerView server) {
        unavailable(Action.DUPLICATE_SERVER);
    }

    default void deleteServer(Screen current, ServerModels.ClientServerView server) {
        unavailable(Action.DELETE_SERVER);
    }

    default void unavailable(Action action) {
        String label = action == null ? "Action" : action.name().replace('_', ' ');
        application().notify(label, label + " Is Unavailable", ReSyncNotificationLevel.WARN);
    }

    static ServerScreenHost of(ApplicationHost application) {
        return new ServerScreenHost() {
            @Override
            public ApplicationHost application() {
                return application;
            }
        };
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
}
