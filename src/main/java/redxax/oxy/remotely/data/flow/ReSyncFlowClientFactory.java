package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.RemotelyServerApi;

import java.util.Objects;

@FunctionalInterface
public interface ReSyncFlowClientFactory {
    ReSyncFlowClientFactory UNAVAILABLE = new ReSyncFlowClientFactory() {
        @Override
        public ReSyncFlowClient create(String serverId, RemotelyServerApi apiClient, String directWsUrl, String directApiKey,
                                       ReSyncFrameTransport suppliedTransport, Object state) {
            throw new IllegalStateException("ReSync flow client factory is unavailable");
        }

        @Override
        public boolean available() {
            return false;
        }
    };

    class ProviderState {
        private static volatile ReSyncFlowClientFactory value = UNAVAILABLE;

        private ProviderState() {
        }
    }

    ReSyncFlowClient create(String serverId, RemotelyServerApi apiClient, String directWsUrl, String directApiKey,
                            ReSyncFrameTransport suppliedTransport, Object state);

    default boolean available() {
        return true;
    }

    default ReSyncFlowClient createLive(String serverId, ReSyncFrameTransport transport, Object state) {
        return create(serverId, null, null, null, transport, state);
    }

    default ReSyncFlowClient createNetwork(String serverId, String apiKey, ReSyncFrameTransport transport, Object state) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ReSync API Key Is Required");
        }
        return create(serverId, null, null, apiKey, Objects.requireNonNull(transport, "transport"), state);
    }

    static void installDesktop(ReSyncFlowClientFactory factory) {
        ProviderState.value = Objects.requireNonNull(factory, "factory");
    }

    static ReSyncFlowClientFactory desktop() {
        return ProviderState.value;
    }

    static ReSyncFlowClientFactory unavailable() {
        return UNAVAILABLE;
    }

}
