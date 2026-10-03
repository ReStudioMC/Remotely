package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.ui.server.HostedNetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;

import java.util.List;

final class BrowserNetworkOverviewProvider extends HostedNetworkOverviewProvider {
    BrowserNetworkOverviewProvider(BrowserRemotelyServerApi api, RemotelyClient client) {
        super(new Transport() {
            @Override
            public Async<JsonObject> request(String method, String path, Object body) {
                return api.networkRequest(method, path, body);
            }

            @Override
            public Async<List<ServerScreenHost.NetworkView>> networks() {
                return api.getNetworks();
            }

            @Override
            public boolean authenticated() {
                return BrowserLaunchSession.authenticated();
            }

            @Override
            public void openServer(Screen current, String serverId) {
                if (client == null || client.getHost() == null) throw new UnsupportedOperationException("Server Is Unavailable");
                ServerScreenHost host = client.getHost().serverScreenHost(client);
                if (host instanceof BrowserServerScreenHost browserHost) {
                    browserHost.openNetworkServer(current, serverId);
                    return;
                }
                throw new UnsupportedOperationException("Server Details Are Unavailable");
            }
        });
    }
}
