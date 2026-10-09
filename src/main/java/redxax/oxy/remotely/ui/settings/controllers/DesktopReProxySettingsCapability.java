package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.servers.ReProxyManager;
import redxax.oxy.remotely.servers.JvmReProxyConnectorCapability;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.reproxy.ReProxyClient;
import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.reproxy.ReProxyModels.Connection;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.util.FileUtils;

import java.util.List;

public final class DesktopReProxySettingsCapability implements ReProxySettingsCapability {
    @Override
    public Async<List<ServerTarget>> serverTargets() {
        return Async.supply(() -> InstanceManager.getInstance().getLocalInstances().stream().filter(Instance::isServer)
                .map(instance -> new ServerTarget(instance.getInstanceId(), instance.getName(),
                        new Binding("", "LOCAL", instance.getInstanceId(), "", "", "", ""))).toList());
    }

    @Override
    public Async<Void> selectConnection(Connection connection) {
        if (connection == null || connection.binding() == null) return Async.failed(new IllegalArgumentException("Choose A Saved Connection"));
        if (!"LOCAL".equals(connection.binding().kind())) {
            ReProxyManager.selectServerConnection(connection.binding().serverId(), connection.id());
            return Async.completed(null);
        }
        Instance instance = InstanceManager.getInstance().getLocalInstances().stream()
                .filter(value -> connection.binding().instanceId().equals(value.getInstanceId())).findFirst().orElse(null);
        if (instance == null) return Async.failed(new IllegalStateException("The Saved Server Is Unavailable"));
        return ReProxyManager.setConnection(JvmReProxyConnectorCapability.server(instance), connection);
    }

    @Override
    public ReProxyClient client() {
        return ReStudio.getInstance().getApi().reProxy();
    }

    @Override
    public String accountId() {
        return ReStudio.getInstance().getUserId();
    }

    @Override
    public Availability connectionAvailability(Connection connection) {
        var available = ReProxyManager.availability(connection);
        return new Availability(available.available(), available.reason());
    }

    @Override
    public Async<Void> attachConnection(Connection connection) {
        return ReProxyManager.attachConnection(connection);
    }

    @Override
    public void connectionChanged(Connection connection) {
        ReProxyManager.connectionChanged(connection);
    }

    @Override
    public void connectionDeleted(String connectionId) {
        ReProxyManager.connectionDeleted(connectionId);
    }

    @Override
    public boolean authenticated() {
        return ReStudio.getInstance().isAuthenticated();
    }

    @Override
    public Availability availability() {
        return Availability.supported();
    }

    @Override
    public Async<ServerModels.ReProxySummary> summary() {
        return ReStudio.getInstance().getApi().async().getReProxySummary();
    }

    @Override
    public Async<ServerModels.ReProxyDomain> createDomain(String subdomain) {
        return ReStudio.getInstance().getApi().async().createReProxyDomain(subdomain);
    }

    @Override
    public Async<Void> deleteDomain(String domainId) {
        return ReStudio.getInstance().getApi().async().deleteReProxyDomain(domainId);
    }

    @Override
    public Async<Void> stopTunnel(String tunnelId) {
        return ReStudio.getInstance().getApi().async().stopReProxyTunnel(tunnelId);
    }

    @Override
    public void copyAddress(String address) {
        FileUtils.setClipboard(address);
    }
}
