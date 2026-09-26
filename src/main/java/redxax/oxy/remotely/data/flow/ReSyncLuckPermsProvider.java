package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;

public interface ReSyncLuckPermsProvider {
    ReSyncLuckPermsClient get(ReSyncFlowClient client);

    default void close(ReSyncFlowClient client) {
    }

    static ReSyncLuckPermsProvider unavailable() {
        return new ReSyncLuckPermsProvider() {
            @Override
            public ReSyncLuckPermsClient get(ReSyncFlowClient client) {
                return new ReSyncLuckPermsClient(client);
            }
        };
    }
}
