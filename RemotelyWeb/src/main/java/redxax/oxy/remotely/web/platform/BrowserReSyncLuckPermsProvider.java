package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncLuckPermsProvider;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsCodec;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsNetworkEnvironment;
import redxax.oxy.remotely.util.BrowserSafeState;

import java.util.List;
import java.util.Map;

public final class BrowserReSyncLuckPermsProvider implements ReSyncLuckPermsProvider, AutoCloseable {
    private final Map<ReSyncFlowClient, ReSyncLuckPermsClient> clients = BrowserSafeState.map();
    private final ReSyncLuckPermsCodec codec = new BrowserReSyncLuckPermsCodec();
    private final ReSyncLuckPermsNetworkEnvironment networkEnvironment = ReSyncLuckPermsNetworkEnvironment.unavailable();

    @Override
    public ReSyncLuckPermsClient get(ReSyncFlowClient client) {
        if (client == null) return null;
        return clients.computeIfAbsent(client, value -> new ReSyncLuckPermsClient(value, networkEnvironment, codec));
    }

    @Override
    public void close(ReSyncFlowClient client) {
        clients.remove(client);
    }

    @Override
    public void close() {
        for (ReSyncLuckPermsClient client : List.copyOf(clients.values())) client.close();
        clients.clear();
    }
}
