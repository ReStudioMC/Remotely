package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.RemotelyServerApi;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rebase.platform.jvm.JvmClock;
import restudio.rebase.platform.jvm.JvmTaskScheduler;
import restudio.rebase.platform.jvm.JvmWebSocketTransport;

public final class DesktopReSyncFlowClientFactory {
    private DesktopReSyncFlowClientFactory() {
    }

    public static ReSyncFlowClientFactory create() {
        return (serverId, apiClient, directWsUrl, directApiKey, suppliedTransport, state) -> {
            TaskScheduler scheduler = new JvmTaskScheduler();
            Clock clock = new JvmClock();
            ReSyncIdentityProvider identityProvider = new ReSyncDesktopIdentityProvider();
            ReSyncFlowClientContext context = state instanceof ReSyncFlowClientContext resolved
                ? resolved : ReSyncFlowClientContext.defaults();
            if (suppliedTransport != null) {
                return new ReSyncFlowClient(serverId, suppliedTransport, directApiKey == null || directApiKey.isBlank() ? "bridge" : directApiKey, context, scheduler, clock,
                    identityProvider, ReSyncCredentialProvider.apiKey(), true);
            }
            ReSyncFrameTransportFactory transportFactory = endpoint ->
                new ReSyncWebSocketFrameTransport(endpoint, new JvmWebSocketTransport(ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES));
            return new ReSyncFlowClient(serverId, apiClient, directWsUrl, directApiKey, context, scheduler, clock,
                transportFactory, identityProvider, ReSyncCredentialProvider.apiKey(), true);
        };
    }
}
