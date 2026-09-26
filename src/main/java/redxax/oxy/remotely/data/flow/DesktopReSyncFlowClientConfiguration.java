package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import redxax.oxy.remotely.data.integrations.luckperms.DesktopReSyncLuckPermsNetworkEnvironment;
import redxax.oxy.remotely.data.integrations.luckperms.DesktopReSyncLuckPermsCodec;
import redxax.oxy.remotely.flow.cache.NodeRegistryCache;
import redxax.oxy.remotely.flow.cache.NodeRegistryTombstoneCache;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import restudio.rebase.platform.jvm.JvmClock;

import java.nio.file.Path;

public final class DesktopReSyncFlowClientConfiguration {
    private DesktopReSyncFlowClientConfiguration() {
    }

    public static ReSyncFlowClientConfiguration create(RemotelyClient client, FlowManager manager,
                                                       ReSyncFlowClientFactory requestedFactory) {
        ReSyncFlowClientFactory factory = requestedFactory == null ? DesktopReSyncFlowClientFactory.create() : requestedFactory;
        DesktopReSyncFlowClientState state = new DesktopReSyncFlowClientState(manager, null);
        Path flowDirectory = DesktopRemotelyPaths.appDir().resolve("data").resolve("flow");
        JvmClock clock = new JvmClock();
        ReSyncFlowCaches caches = new ReSyncFlowCaches(
            new NodeRegistryCache(DesktopReSyncStorage.fromKey(flowDirectory.resolve("node_registry_cache.json")), clock),
            new NodeRegistryTombstoneCache(DesktopReSyncStorage.fromKey(flowDirectory.resolve("node_registry_tombstones.json"))),
            OptionCatalogCache.getInstance());
        ReSyncFlowClientContext context = new ReSyncFlowClientContext(state, caches, new NodeRegistry(clock), state)
            .withLuckPermsProvider(flowClient -> new ReSyncLuckPermsClient(flowClient,
                new DesktopReSyncLuckPermsNetworkEnvironment(manager), new DesktopReSyncLuckPermsCodec()));
        return new ReSyncFlowClientConfiguration(factory, new DesktopReSyncConnectionProfileProvider(client),
            new DesktopReSyncConnectionNotificationSink(), context.nodeRegistry(), context);
    }
}
