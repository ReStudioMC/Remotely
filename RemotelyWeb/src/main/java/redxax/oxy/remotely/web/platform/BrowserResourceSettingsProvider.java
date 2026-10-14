package redxax.oxy.remotely.web.platform;

import restudio.rebase.backend.CapabilityDescriptor;
import restudio.rebase.resource.provider.ResourceProviderCatalog;
import restudio.rebase.settings.controllers.ResourceSettingsProvider;
import restudio.rescreen.platform.Async;

import java.util.List;

final class BrowserResourceSettingsProvider implements ResourceSettingsProvider {
    private final BrowserServerScreenHost host;

    BrowserResourceSettingsProvider(BrowserServerScreenHost host) {
        this.host = host;
    }

    @Override
    public List<String> providers() {
        return ResourceProviderCatalog.browser(false).stream().map(ResourceProviderCatalog.Definition::name).toList();
    }

    @Override
    public Async<List<Scope>> instances() {
        return host.restudioServers().thenApply(servers -> servers.stream()
                .map(server -> new Scope(BrowserServerScreenHost.serverId(server), server.name)).toList());
    }

    @Override
    public Async<Void> refresh(String instanceId) {
        return host.maintainResourceIndexes(instanceId, false);
    }

    @Override
    public Async<Void> purge(String instanceId) {
        return host.maintainResourceIndexes(instanceId, true);
    }

    @Override
    public CapabilityDescriptor capability(String action) {
        return CapabilityDescriptor.supported(action);
    }
}
