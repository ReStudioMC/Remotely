package redxax.oxy.remotely;

import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.data.flow.DesktopReSyncDirectSockets;
import redxax.oxy.remotely.data.flow.DesktopReSyncLocalInstances;
import redxax.oxy.remotely.data.flow.DesktopReSyncFlowClientFactory;
import redxax.oxy.remotely.data.flow.DesktopReSyncClock;
import redxax.oxy.remotely.data.flow.DesktopReSyncStorage;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientFactory;
import redxax.oxy.remotely.data.flow.ReSyncStorage;
import redxax.oxy.remotely.discord.DiscordRpcBridge;
import redxax.oxy.remotely.discord.DiscordRpcService;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.metadata.DesktopMetadataRepository;
import redxax.oxy.remotely.metadata.catalog.ServerSettingsCatalogService;
import redxax.oxy.remotely.network.DesktopNetworkManager;
import redxax.oxy.remotely.network.DesktopNetworkAccess;
import redxax.oxy.remotely.ui.server.DesktopServerUiCapabilities;
import redxax.oxy.remotely.ui.server.DesktopPanelServerProvider;
import redxax.oxy.remotely.ui.server.DesktopRemoteHostConnectionProvider;
import redxax.oxy.remotely.ui.server.DesktopServerIconProvider;
import redxax.oxy.remotely.ui.server.DesktopTerminalSessionLifecycle;
import restudio.rebase.Rebase;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.platform.jvm.JvmClock;
import restudio.rebase.platform.jvm.JvmTaskScheduler;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.restudio.api.ReStudioApiClient;
import restudio.rebase.ui.screens.editor.completion.CodeCompletionRegistry;
import restudio.rescreen.config.Config;
import redxax.oxy.remotely.session.TerminalSessionManager;
import redxax.oxy.remotely.settings.server.DesktopServerSettingsRegistry;
import redxax.oxy.remotely.util.DesktopTaskIdentities;

import java.nio.file.Path;
import java.util.List;

public final class DesktopRemotelyComposition {
    private DesktopRemotelyComposition() {
    }

    public static RemotelyComposition.Builder create(ApplicationHost host) {
        DesktopTaskIdentities.install();
        CodeCompletionRegistry.installDefaults();
        ReSyncFlowClientFactory flowClientFactory = DesktopReSyncFlowClientFactory.create();
        ReSyncFlowClientFactory.installDesktop(flowClientFactory);
        DesktopReSyncDirectSockets.install();
        DesktopReSyncLocalInstances.install();
        ReSyncStorage.installDesktop(DesktopReSyncStorage::fromKey);
        Path applicationDirectory = DesktopRemotelyPaths.appDir();
        Path flowDirectory = applicationDirectory.resolve("data").resolve("flow");
        OptionCatalogCache.install(DesktopReSyncStorage.fromKey(flowDirectory.resolve("option_catalog_cache.json")), new DesktopReSyncClock());
        return RemotelyComposition.builder(host)
                .application(RemotelyApplication.APP)
                .environment(RemotelyComposition.Environment.DESKTOP)
                .capabilities(RemotelyComposition.Capabilities.desktop())
                .applicationDirectory(applicationDirectory)
                .reSyncFlowClientFactory(flowClientFactory)
                .configManagerFactory(DesktopRemotelyComposition::configManager)
                .scheduler(new JvmTaskScheduler())
                .clock(new JvmClock())
                .serverUiCapabilityProvider(DesktopServerUiCapabilities.desktop())
                .panelServerProvider(DesktopPanelServerProvider.instance())
                .remoteHostConnectionProvider(DesktopRemoteHostConnectionProvider.instance())
                .serverIconProvider(new DesktopServerIconProvider(applicationDirectory))
                .serverSettingsCatalogService(new ServerSettingsCatalogService(
                    DesktopMetadataRepository.create(applicationDirectory.resolve("data").resolve("metadata"))))
                .terminalSessionManagerFactory(() -> new TerminalSessionManager(new DesktopTerminalSessionLifecycle()))
                .networkManagerFactory(() -> new DesktopNetworkManager(applicationDirectory))
                .instanceManagerFactory(() -> Rebase.get().getInstanceManager())
                .nodeRegistryFactory(NodeRegistry::new)
                .serverSettingsRegistryStorage(registry -> new DesktopServerSettingsRegistry(registry, true))
                .apiClientFactory(DesktopRemotelyComposition::apiClient)
                .initializationHook(DesktopRemotelyComposition::initialize);
    }

    private static RemotelyConfigStore configManager() {
        try {
            return Rebase.get().getConfigManager() instanceof RemotelyConfigManager config ? config : null;
        } catch (IllegalStateException exception) {
            return Config.configManager instanceof RemotelyConfigManager config ? config : null;
        }
    }

    private static RemotelyServerApi apiClient() {
        try {
            ReStudio studio = ReStudio.getInstance();
            if (studio == null) return null;
            ReStudioApiClient api = studio.getApi();
            return api == null ? null : new DesktopRemotelyServerApi(api);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void initialize(RemotelyClient client) {
        if (client == null) return;
        DesktopNetworkManager networkManager = DesktopNetworkAccess.manager(client);
        if (networkManager != null && client.getComposition().capabilities().has(RemotelyComposition.Capability.INSTANCE_RECONCILIATION)) {
            try {
                InstanceManager instanceManager = client.getComposition().createInstanceManager();
                if (instanceManager != null) {
                    List<Instance> instances = instanceManager.getAllInstances();
                    networkManager.recoverCompletedJobs(instances).whenComplete((unused, throwable) -> {
                        if (throwable == null) {
                            networkManager.reconcileInstanceBindings(instanceManager.getAllInstances());
                        }
                    });
                    instanceManager.addChangeListener(() -> networkManager.reconcileInstanceBindings(instanceManager.getAllInstances()));
                }
            } catch (IllegalStateException ignored) {
            }
        }
        if (!client.getComposition().capabilities().has(RemotelyComposition.Capability.DESKTOP_INTEGRATIONS)
                || !client.getHost().supportsDesktopIntegrations()) return;
        RemotelyConfigManager config = Config.configManager instanceof RemotelyConfigManager value ? value : null;
        InstanceManager instanceManager = client.getComposition().createInstanceManager();
        if (config == null || instanceManager == null) return;
        DiscordRpcBridge.install(new DiscordRpcService(config, instanceManager));
        DiscordRpcBridge.start();
        DiscordRpcBridge.setManagerActive();
        instanceManager.addChangeListener(DiscordRpcBridge::refreshTrackedInstances);
    }
}
