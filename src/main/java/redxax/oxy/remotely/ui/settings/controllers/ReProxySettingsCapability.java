package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rebase.reproxy.ReProxyClient;
import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.reproxy.ReProxyModels.Connection;
import restudio.rebase.reproxy.ReProxyModels.Endpoint;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;

import java.util.List;
import java.util.UUID;

public interface ReProxySettingsCapability {
    record Availability(boolean available, String reason) {
        public Availability {
            reason = reason == null ? "" : reason.trim();
        }

        public static Availability supported() {
            return new Availability(true, "");
        }

        public static Availability unavailable(String reason) {
            return new Availability(false, reason);
        }
    }

    boolean authenticated();

    Availability availability();

    Async<ServerModels.ReProxySummary> summary();

    Async<ServerModels.ReProxyDomain> createDomain(String subdomain);

    Async<Void> deleteDomain(String domainId);

    Async<Void> stopTunnel(String tunnelId);

    void copyAddress(String address);

    record ServerTarget(String id, String name, Binding binding) {
    }

    default Async<List<ServerTarget>> serverTargets() {
        return Async.completed(List.of());
    }

    default Async<Void> selectConnection(Connection connection) {
        return Async.failed(new UnsupportedOperationException("Select This Connection From Its Server"));
    }

    default ReProxyClient client() {
        return null;
    }

    default String accountId() {
        return authenticated() ? "Legacy Account" : "";
    }

    default String operationKey() {
        return UUID.randomUUID().toString();
    }

    default Availability connectionAvailability(Connection connection) {
        return Availability.unavailable("Connect A Local Agent");
    }

    default Async<Void> attachConnection(Connection connection) {
        return Async.failed(new UnsupportedOperationException(connectionAvailability(connection).reason()));
    }

    default void connectionChanged(Connection connection) {
    }

    default void connectionDeleted(String connectionId) {
    }

    default Availability pluginAvailability(Connection connection, Endpoint endpoint) {
        return Availability.unavailable("Copy The Assigned Host And Port Into Plugin Settings");
    }

    default Async<Void> applyPluginSettings(Connection connection, Endpoint endpoint, String key) {
        return Async.failed(new UnsupportedOperationException(pluginAvailability(connection, endpoint).reason()));
    }

    static ReProxySettingsCapability unavailable(boolean authenticated, String reason) {
        String message = reason == null || reason.isBlank() ? "ReProxy Is Unavailable" : reason;
        return new ReProxySettingsCapability() {
            @Override
            public boolean authenticated() {
                return authenticated;
            }

            @Override
            public Availability availability() {
                return Availability.unavailable(message);
            }

            @Override
            public Async<ServerModels.ReProxySummary> summary() {
                return failed(message);
            }

            @Override
            public Async<ServerModels.ReProxyDomain> createDomain(String subdomain) {
                return failed(message);
            }

            @Override
            public Async<Void> deleteDomain(String domainId) {
                return failed(message);
            }

            @Override
            public Async<Void> stopTunnel(String tunnelId) {
                return failed(message);
            }

            @Override
            public void copyAddress(String address) {
            }

            private <T> Async<T> failed(String reason) {
                return Async.failed(new UnsupportedOperationException(reason));
            }
        };
    }
}
