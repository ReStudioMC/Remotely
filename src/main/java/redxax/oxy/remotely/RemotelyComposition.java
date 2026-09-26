package redxax.oxy.remotely;

import redxax.oxy.remotely.util.TaskSchedulers;

import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientFactory;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransportFactory;
import redxax.oxy.remotely.data.flow.ReSyncIdentityProvider;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.metadata.catalog.ServerSettingsCatalogService;
import redxax.oxy.remotely.session.TerminalSessionManager;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistryStorage;
import redxax.oxy.remotely.ui.server.PanelServerProvider;
import redxax.oxy.remotely.ui.server.RemoteHostConnectionProvider;
import redxax.oxy.remotely.ui.server.ServerIconManager;
import redxax.oxy.remotely.ui.server.ServerIconProvider;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import restudio.rebase.backend.FileExplorerProviders;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.KeyValueStore;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.ui.core.Screen;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class RemotelyComposition {
    public enum Environment {
        DESKTOP,
        BROWSER
    }

    public enum Capability {
        LOCAL_STORAGE,
        LOCAL_PROCESSES,
        NATIVE_RENDERING,
        DESKTOP_INTEGRATIONS,
        PRIMARY_SCREEN,
        REBASE_BOOTSTRAP,
        RESTUDIO_BOOTSTRAP,
        REVERSE_PROXY_AUTOSTART,
        PACK_CONTENT,
        STORAGE_MIGRATION,
        INSTANCE_RECONCILIATION,
        NODE_REGISTRY,
        REFLECTIVE_API_LOOKUP
    }

    public record Capabilities(Set<Capability> enabled) {
        public Capabilities {
            enabled = enabled == null || enabled.isEmpty() ? Set.of() : Set.copyOf(enabled);
        }

        public static Capabilities browser() {
            return new Capabilities(Set.of());
        }

        public static Capabilities desktop() {
            return new Capabilities(EnumSet.allOf(Capability.class));
        }

        public boolean has(Capability capability) {
            return capability != null && enabled.contains(capability);
        }

        public Capabilities with(Capability... capabilities) {
            EnumSet<Capability> copy = EnumSet.noneOf(Capability.class);
            copy.addAll(enabled);
            if (capabilities != null) {
                copy.addAll(Arrays.asList(capabilities));
            }
            return new Capabilities(copy);
        }
    }

    private final RemotelyApplication application;
    private final Environment environment;
    private final ApplicationHost host;
    private final Capabilities capabilities;
    private final Object applicationDirectory;
    private final String platformName;
    private final RemotelyConfigStore configManager;
    private final Supplier<RemotelyConfigStore> configManagerFactory;
    private final RemotelyServerApi apiClient;
    private final ServerUiCapabilityProvider serverUiCapabilityProvider;
    private final PanelServerProvider panelServerProvider;
    private final RemoteHostConnectionProvider remoteHostConnectionProvider;
    private final ServerIconProvider serverIconProvider;
    private final Supplier<?> networkManagerFactory;
    private final BiFunction<RemotelyClient, RemotelyServerApi, FlowManager> flowManagerFactory;
    private final ReSyncFlowClientFactory reSyncFlowClientFactory;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ReSyncFrameTransportFactory reSyncFrameTransportFactory;
    private final ReSyncIdentityProvider reSyncIdentityProvider;
    private final Supplier<?> instanceManagerFactory;
    private final Supplier<?> terminalSessionManagerFactory;
    private final Supplier<NodeRegistry> nodeRegistryFactory;
    private final Function<RemotelyClient, Screen> rootScreenFactory;
    private final Supplier<RemotelyServerApi> apiClientFactory;
    private final Consumer<RemotelyClient> initializationHook;
    private final ServerSettingsRegistry.StorageSnapshot serverSettingsRegistryStorageSnapshot;
    private final ServerSettingsCatalogService serverSettingsCatalogService;
    private final KeyValueStore storageBreakdownStore;
    private final int storageBreakdownMaxChars;

    private RemotelyComposition(Builder builder) {
        application = builder.application;
        environment = builder.environment;
        host = Objects.requireNonNull(builder.host, "host");
        capabilities = builder.capabilities;
        applicationDirectory = builder.applicationDirectory;
        platformName = builder.platformName;
        configManager = builder.configManager;
        configManagerFactory = builder.configManagerFactory;
        apiClient = builder.apiClient;
        serverUiCapabilityProvider = builder.serverUiCapabilityProvider;
        panelServerProvider = builder.panelServerProvider;
        remoteHostConnectionProvider = builder.remoteHostConnectionProvider;
        serverIconProvider = builder.serverIconProvider;
        networkManagerFactory = builder.networkManagerFactory;
        flowManagerFactory = builder.flowManagerFactory;
        reSyncFlowClientFactory = builder.reSyncFlowClientFactory;
        scheduler = builder.scheduler;
        clock = builder.clock;
        TaskSchedulers.configure(scheduler);
        FileExplorerProviders.installScheduler(this, scheduler);
        if (environment == Environment.DESKTOP) {
            ServerIconManager icons = new ServerIconManager(serverIconProvider);
            FileExplorerProviders.installServerIconResolver(serverIconProvider, instance -> {
                icons.resolveIconPath(instance, true, null);
                return icons.getIconId(instance);
            });
        }
        reSyncFrameTransportFactory = builder.reSyncFrameTransportFactory;
        reSyncIdentityProvider = builder.reSyncIdentityProvider;
        instanceManagerFactory = builder.instanceManagerFactory;
        terminalSessionManagerFactory = builder.terminalSessionManagerFactory;
        nodeRegistryFactory = builder.nodeRegistryFactory;
        rootScreenFactory = builder.rootScreenFactory;
        apiClientFactory = builder.apiClientFactory;
        initializationHook = builder.initializationHook;
        serverSettingsCatalogService = builder.serverSettingsCatalogService;
        storageBreakdownStore = builder.storageBreakdownStore;
        storageBreakdownMaxChars = builder.storageBreakdownMaxChars;
        ServerSettingsRegistry registry = ServerSettingsRegistry.getInstance();
        ServerSettingsRegistryStorage storage = builder.serverSettingsRegistryStorageFactory.apply(registry);
        if (environment == Environment.BROWSER) {
            serverSettingsRegistryStorageSnapshot = registry.installStorageWithSnapshot(storage);
        } else {
            registry.installStorage(storage);
            serverSettingsRegistryStorageSnapshot = null;
        }
    }

    public static Builder browser(ApplicationHost host) {
        return new Builder(host)
                .application(RemotelyApplication.APP)
                .environment(Environment.BROWSER)
                .platformName("web")
                .capabilities(Capabilities.browser())
                .serverSettingsRegistryStorage(ignored -> ServerSettingsRegistryStorage.unavailable())
                .panelServerProvider(PanelServerProvider.unavailable())
                .remoteHostConnectionProvider(RemoteHostConnectionProvider.unavailable());
    }

    public static Builder builder(ApplicationHost host) {
        return new Builder(host);
    }

    public RemotelyApplication application() {
        return application;
    }

    public Environment environment() {
        return environment;
    }

    public ApplicationHost host() {
        return host;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    public <T> T applicationDirectory() {
        return (T) applicationDirectory;
    }

    public String platformName() {
        return platformName;
    }

    public RemotelyConfigStore configManager() {
        return configManagerFactory == null ? configManager : configManagerFactory.get();
    }

    public RemotelyServerApi apiClient() {
        return apiClient;
    }

    public ServerUiCapabilityProvider serverUiCapabilityProvider() {
        return serverUiCapabilityProvider;
    }

    public PanelServerProvider panelServerProvider() {
        return panelServerProvider;
    }

    public RemoteHostConnectionProvider remoteHostConnectionProvider() {
        return remoteHostConnectionProvider;
    }

    public ServerIconProvider serverIconProvider() {
        return serverIconProvider;
    }

    public <T> T createNetworkManager() {
        return networkManagerFactory == null ? null : (T) networkManagerFactory.get();
    }

    public FlowManager createFlowManager(RemotelyClient client, RemotelyServerApi resolvedApiClient) {
        if (flowManagerFactory != null) {
            return flowManagerFactory.apply(client, resolvedApiClient);
        }
        return new FlowManager(client, resolvedApiClient, reSyncFlowClientFactory, scheduler, clock, null);
    }

    public ReSyncFlowClientFactory reSyncFlowClientFactory() {
        return reSyncFlowClientFactory;
    }

    public TaskScheduler scheduler() {
        return scheduler;
    }

    public Clock clock() {
        return clock;
    }

    public ReSyncFrameTransportFactory reSyncFrameTransportFactory() {
        return reSyncFrameTransportFactory;
    }

    public ReSyncIdentityProvider reSyncIdentityProvider() {
        return reSyncIdentityProvider;
    }

    public <T> T createInstanceManager() {
        return instanceManagerFactory == null ? null : (T) instanceManagerFactory.get();
    }

    public TerminalSessionManager createTerminalSessionManager() {
        return terminalSessionManagerFactory == null ? new TerminalSessionManager() : (TerminalSessionManager) terminalSessionManagerFactory.get();
    }

    public RemotelyServerApi createApiClient() {
        return apiClientFactory == null ? null : apiClientFactory.get();
    }

    public void initializePlatform(RemotelyClient client) {
        if (initializationHook != null) {
            initializationHook.accept(client);
        }
    }

    public Screen createRootScreen(RemotelyClient client) {
        return rootScreenFactory == null ? null : rootScreenFactory.apply(client);
    }

    public NodeRegistry createNodeRegistry() {
        return nodeRegistryFactory == null ? null : nodeRegistryFactory.get();
    }

    public ServerSettingsRegistry.StorageSnapshot serverSettingsRegistryStorageSnapshot() {
        return serverSettingsRegistryStorageSnapshot;
    }

    public ServerSettingsCatalogService serverSettingsCatalogService() {
        return serverSettingsCatalogService;
    }

    public KeyValueStore storageBreakdownStore() {
        return storageBreakdownStore;
    }

    public int storageBreakdownMaxChars() {
        return storageBreakdownMaxChars;
    }

    public static final class Builder {
        private final ApplicationHost host;
        private RemotelyApplication application = RemotelyApplication.APP;
        private Environment environment = Environment.BROWSER;
        private Capabilities capabilities = Capabilities.browser();
        private Object applicationDirectory;
        private String platformName;
        private RemotelyConfigStore configManager;
        private Supplier<RemotelyConfigStore> configManagerFactory;
        private RemotelyServerApi apiClient;
        private ServerUiCapabilityProvider serverUiCapabilityProvider = ServerUiCapabilityProvider.unavailable();
        private PanelServerProvider panelServerProvider = PanelServerProvider.unavailable();
        private RemoteHostConnectionProvider remoteHostConnectionProvider = RemoteHostConnectionProvider.unavailable();
        private ServerIconProvider serverIconProvider = ServerIconProvider.logical();
        private Supplier<?> networkManagerFactory;
        private BiFunction<RemotelyClient, RemotelyServerApi, FlowManager> flowManagerFactory;
        private ReSyncFlowClientFactory reSyncFlowClientFactory;
        private TaskScheduler scheduler;
        private Clock clock;
        private ReSyncFrameTransportFactory reSyncFrameTransportFactory;
        private ReSyncIdentityProvider reSyncIdentityProvider;
        private Supplier<?> instanceManagerFactory;
        private Supplier<?> terminalSessionManagerFactory;
        private Supplier<NodeRegistry> nodeRegistryFactory;
        private Function<RemotelyClient, Screen> rootScreenFactory;
        private Supplier<RemotelyServerApi> apiClientFactory;
        private Consumer<RemotelyClient> initializationHook;
        private Function<ServerSettingsRegistry, ServerSettingsRegistryStorage> serverSettingsRegistryStorageFactory;
        private ServerSettingsCatalogService serverSettingsCatalogService;
        private KeyValueStore storageBreakdownStore;
        private int storageBreakdownMaxChars = 1_500_000;

        private Builder(ApplicationHost host) {
            this.host = Objects.requireNonNull(host, "host");
        }

        public Builder application(RemotelyApplication application) {
            this.application = Objects.requireNonNull(application, "application");
            return this;
        }

        public Builder environment(Environment environment) {
            this.environment = Objects.requireNonNull(environment, "environment");
            return this;
        }

        public Builder capabilities(Capabilities capabilities) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            return this;
        }

        public Builder enable(Capability... capabilities) {
            this.capabilities = this.capabilities.with(capabilities);
            return this;
        }

        public Builder applicationDirectory(Object applicationDirectory) {
            this.applicationDirectory = applicationDirectory;
            return this;
        }

        public Builder platformName(String platformName) {
            this.platformName = platformName;
            return this;
        }

        public Builder configManager(RemotelyConfigStore configManager) {
            this.configManager = configManager;
            this.configManagerFactory = null;
            return this;
        }

        public Builder configManagerFactory(Supplier<RemotelyConfigStore> configManagerFactory) {
            this.configManagerFactory = configManagerFactory;
            this.configManager = null;
            return this;
        }

        public Builder apiClient(RemotelyServerApi apiClient) {
            this.apiClient = apiClient;
            return this;
        }

        public Builder serverUiCapabilityProvider(ServerUiCapabilityProvider provider) {
            this.serverUiCapabilityProvider = Objects.requireNonNull(provider, "serverUiCapabilityProvider");
            return this;
        }

        public Builder panelServerProvider(PanelServerProvider provider) {
            this.panelServerProvider = Objects.requireNonNull(provider, "panelServerProvider");
            return this;
        }

        public Builder remoteHostConnectionProvider(RemoteHostConnectionProvider provider) {
            this.remoteHostConnectionProvider = Objects.requireNonNull(provider, "remoteHostConnectionProvider");
            return this;
        }

        public Builder serverSettingsCatalogService(ServerSettingsCatalogService serverSettingsCatalogService) {
            this.serverSettingsCatalogService = serverSettingsCatalogService;
            return this;
        }

        public Builder serverIconProvider(ServerIconProvider provider) {
            this.serverIconProvider = Objects.requireNonNull(provider, "serverIconProvider");
            return this;
        }

        public Builder storageBreakdownStore(KeyValueStore store, int maxChars) {
            storageBreakdownStore = store;
            storageBreakdownMaxChars = Math.max(1, maxChars);
            return this;
        }

        public Builder networkManagerFactory(Supplier<?> networkManagerFactory) {
            this.networkManagerFactory = networkManagerFactory;
            return this;
        }

        public Builder flowManagerFactory(BiFunction<RemotelyClient, RemotelyServerApi, FlowManager> flowManagerFactory) {
            this.flowManagerFactory = flowManagerFactory;
            return this;
        }

        public Builder reSyncFlowClientFactory(ReSyncFlowClientFactory reSyncFlowClientFactory) {
            this.reSyncFlowClientFactory = reSyncFlowClientFactory;
            return this;
        }

        public Builder scheduler(TaskScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder reSyncFrameTransportFactory(ReSyncFrameTransportFactory factory) {
            this.reSyncFrameTransportFactory = factory;
            return this;
        }

        public Builder reSyncIdentityProvider(ReSyncIdentityProvider provider) {
            this.reSyncIdentityProvider = provider;
            return this;
        }

        public Builder instanceManagerFactory(Supplier<?> instanceManagerFactory) {
            this.instanceManagerFactory = instanceManagerFactory;
            return this;
        }

        public Builder terminalSessionManagerFactory(Supplier<?> terminalSessionManagerFactory) {
            this.terminalSessionManagerFactory = terminalSessionManagerFactory;
            return this;
        }

        public Builder nodeRegistryFactory(Supplier<NodeRegistry> nodeRegistryFactory) {
            this.nodeRegistryFactory = nodeRegistryFactory;
            return this;
        }

        public Builder rootScreenFactory(Function<RemotelyClient, Screen> rootScreenFactory) {
            this.rootScreenFactory = rootScreenFactory;
            return this;
        }

        public Builder apiClientFactory(Supplier<RemotelyServerApi> apiClientFactory) {
            this.apiClientFactory = apiClientFactory;
            return this;
        }

        public Builder initializationHook(Consumer<RemotelyClient> initializationHook) {
            this.initializationHook = initializationHook;
            return this;
        }

        public Builder serverSettingsRegistryStorage(Function<ServerSettingsRegistry, ServerSettingsRegistryStorage> factory) {
            serverSettingsRegistryStorageFactory = Objects.requireNonNull(factory, "serverSettingsRegistryStorageFactory");
            return this;
        }

        public RemotelyComposition build() {
            if (serverSettingsRegistryStorageFactory == null) {
                throw new IllegalStateException("Server settings registry storage must be configured");
            }
            if (environment == Environment.BROWSER && apiClient != null && serverUiCapabilityProvider == ServerUiCapabilityProvider.unavailable()) {
                serverUiCapabilityProvider = ServerUiCapabilityProvider.api(apiClient);
            }
            return new RemotelyComposition(this);
        }
    }
}
