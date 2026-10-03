package redxax.oxy.remotely.host;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.DesktopRemotelyServerApi;
import redxax.oxy.remotely.ui.server.HostedNetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rebase.restudio.ReStudio;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

final class DesktopHostedNetworkOverviewProvider extends HostedNetworkOverviewProvider {
    DesktopHostedNetworkOverviewProvider(DesktopRemotelyServerApi api, BiConsumer<Screen, String> openServer) {
        super(new Transport() {
            @Override
            public Async<JsonObject> request(String method, String path, Object body) {
                return api.hostedNetworkViewRequest(method, path, body);
            }

            @Override
            public Async<List<ServerScreenHost.NetworkView>> networks() {
                return api.hostedNetworkViews();
            }

            @Override
            public boolean authenticated() {
                ReStudio studio = ReStudio.getInstance();
                return studio != null && studio.isAuthenticated() && studio.getApi() == api.studioApi();
            }

            @Override
            public void openServer(Screen current, String serverId) {
                openServer.accept(current, serverId);
            }
        });
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(openServer, "openServer");
    }
}
