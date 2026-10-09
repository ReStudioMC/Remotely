package redxax.oxy.remotely.servers;

import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.reproxy.ReProxyModels.Ticket;
import restudio.rescreen.platform.Async;

import java.util.List;

public interface ReProxyConnectorCapability {
    record Availability(boolean available, String reason) {
    }

    Availability availability(Binding binding);

    Handle open(Ticket ticket, Binding binding, List<Endpoint> approved, Listener listener);

    interface Server {
        String id();
        String name();
        int port();
        boolean local();
        String setting(String key, String fallback);
        void putSetting(String key, String value);
        Async<Void> save();
    }

    default Server server(Object target) {
        throw new UnsupportedOperationException("Local Server Access Is Unavailable");
    }

    interface Handle extends AutoCloseable {
        Async<Void> ready();
        void updateGrants(List<Endpoint> approved);
        @Override
        void close();
    }

    interface Listener {
        default void authenticated() { }
        default void activated(String routeRevision) { }
        default void changed(String routeRevision) { }
        default void failed(Throwable error) { }
        default void closed() { }
    }

    static ReProxyConnectorCapability unavailable() {
        return new ReProxyConnectorCapability() {
            @Override
            public Availability availability(Binding binding) {
                return new Availability(false, "Local Forwarding Requires A Connected Agent With Port Access");
            }

            @Override
            public Handle open(Ticket ticket, Binding binding, List<Endpoint> approved, Listener listener) {
                throw new UnsupportedOperationException(availability(binding).reason());
            }
        };
    }
}
