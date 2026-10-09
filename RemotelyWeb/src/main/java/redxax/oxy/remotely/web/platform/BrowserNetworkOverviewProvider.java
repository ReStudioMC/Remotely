package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.network.NetworkEditorConnection;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.ui.server.HostedNetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.NetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

final class BrowserNetworkOverviewProvider extends HostedNetworkOverviewProvider {
    private final RemotelyClient client;
    private volatile String networkId = "";

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
        }, client);
        this.client = client;
    }

    private BrowserNetworkManager manager() {
        return client != null && client.getNetworkManager() instanceof BrowserNetworkManager manager ? manager : null;
    }

    @Override
    public NetworkCapability resourcesCapability() {
        BrowserNetworkManager manager = manager();
        if (manager == null || !manager.editorAvailable()) return NetworkCapability.unavailable("resources", "Sign In To Open Resources", "network");
        if (!manager.configured(networkId)) return NetworkCapability.unavailable("resources", "Open Network Connection To Connect This Network", "network");
        return NetworkCapability.supported("resources", "network");
    }

    @Override
    public NetworkCapability operatorCapability() {
        BrowserNetworkManager manager = manager();
        return manager != null && manager.editorConfigurable() ? NetworkCapability.supported("operator", "network")
                : NetworkCapability.unavailable("operator", "Sign In To Connect To This Network", "network");
    }

    @Override
    public Async<Void> configureOperator(String networkId, NetworkEditorConnection connection) {
        BrowserNetworkManager manager = manager();
        return manager == null ? NetworkOverviewProvider.unavailable("Network Connection Is Unavailable") : manager.configureEditor(networkId, connection);
    }

    @Override
    public Async<Void> openResources(Screen current, String networkId, String nodeId, BooleanSupplier admission) {
        BrowserNetworkManager manager = manager();
        if (manager == null || !manager.configured(networkId)) return NetworkOverviewProvider.unavailable("Configure The Network Connection First");
        return manager.getNetwork(networkId).map(network -> network.members().stream().filter(member -> member.nodeId().equals(nodeId) && !member.isProxy()).findFirst()
                .map(member -> Async.supplyAsync(() -> {
                    if (admission == null || !admission.getAsBoolean()) throw new IllegalStateException("Resource Opening Was Cancelled");
                    return manager.editorTransport(networkId, nodeId);
                }).thenCompose(transport -> client.getFlowManager().openNetworkReSyncStudio("", member.routeName(), transport, admission)))
                .orElseGet(() -> NetworkOverviewProvider.unavailable("Server Is No Longer In This Network")))
                .orElseGet(() -> NetworkOverviewProvider.unavailable("Network Is Unavailable"));
    }

    @Override
    public Async<OverviewState> load(String networkId) {
        return super.load(networkId).thenApply(state -> {
            BrowserNetworkManager manager = manager();
            if (manager == null || state.network() == null) return state;
            this.networkId = state.network().networkId();
            manager.admit(state.network());
            if (!manager.configured(networkId)) return state;
            return new OverviewState(state.network(), state.servers(), state.discovery(), manager.getRuntimeSnapshot(networkId), state.incidents(),
                    state.lifecycleJobs(), state.jobs(), state.transferFailureHeat(), state.reSyncInstalled(), state.capabilities());
        });
    }

    @Override
    public void addRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        super.addRuntimeListener(listener);
        BrowserNetworkManager manager = manager();
        if (manager != null) manager.addRuntimeListener(listener);
    }

    @Override
    public void removeRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        super.removeRuntimeListener(listener);
        BrowserNetworkManager manager = manager();
        if (manager != null) manager.removeRuntimeListener(listener);
    }
}
