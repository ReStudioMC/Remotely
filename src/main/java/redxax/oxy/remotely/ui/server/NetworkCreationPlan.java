package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.network.NetworkMemberManagement;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record NetworkCreationPlan(String name, int entryPort, List<Server> servers, boolean firewallVerified, UUID requestId) {
    public NetworkCreationPlan(String name, int entryPort, List<Server> servers) {
        this(name, entryPort, servers, false);
    }

    public NetworkCreationPlan(String name, int entryPort, List<Server> servers, boolean firewallVerified) {
        this(name, entryPort, servers, firewallVerified, UUID.randomUUID());
    }

    public NetworkCreationPlan {
        name = name == null ? "" : name.trim();
        servers = servers == null ? List.of() : List.copyOf(servers);
        Objects.requireNonNull(requestId, "Network Request Identity Is Required");
    }

    public boolean hosted() {
        return servers.stream().anyMatch(server -> server.hostedSource() != null);
    }

    public NetworkCommand.Create hostedCommand() {
        if (servers.stream().anyMatch(server -> server.hostedSource() == null)) {
            throw new IllegalArgumentException("Hosted Networks Require Reactor Servers Or Explicit External Members");
        }
        List<NetworkCommand.Member> members = servers.stream().map(server -> new NetworkCommand.Member(server.hostedSource(),
                server.proxy() ? "proxy" : server.route(), server.role(), server.preferredPort(), server.capacity(), server.reSync())).toList();
        return new NetworkCommand.Create(NetworkCommand.CURRENT_SCHEMA_VERSION, requestId.toString(), "network-" + requestId,
                name, entryPort, members, Map.of("firewallVerified", firewallVerified));
    }

    public record Server(String existingId, Object template, ServerScreenHost.HostView host, String location,
                         ServerSettingsDataController settings, boolean proxy, String route, NetworkMemberRole role,
                         int capacity, boolean reSync, String address, int preferredPort, NetworkMemberSource hostedSource,
                         NetworkMemberManagement management) {
        public Server(String existingId, Object template, ServerScreenHost.HostView host, String location,
                      ServerSettingsDataController settings, boolean proxy, String route, NetworkMemberRole role,
                      int capacity, boolean reSync) {
            this(existingId, template, host, location, settings, proxy, route, role, capacity, reSync, "", 0, null,
                    NetworkMemberManagement.MANAGED);
        }

        public Server(String existingId, Object template, ServerScreenHost.HostView host, String location,
                      ServerSettingsDataController settings, boolean proxy, String route, NetworkMemberRole role,
                      int capacity, boolean reSync, String address, int preferredPort) {
            this(existingId, template, host, location, settings, proxy, route, role, capacity, reSync, address, preferredPort, null,
                    NetworkMemberManagement.MANAGED);
        }

        public Server(String existingId, Object template, ServerScreenHost.HostView host, String location,
                      ServerSettingsDataController settings, boolean proxy, String route, NetworkMemberRole role,
                      int capacity, boolean reSync, String address, int preferredPort, NetworkMemberSource hostedSource) {
            this(existingId, template, host, location, settings, proxy, route, role, capacity, reSync, address, preferredPort,
                    hostedSource, NetworkMemberManagement.MANAGED);
        }

        public Server {
            existingId = existingId == null ? "" : existingId.trim();
            location = location == null ? "" : location.trim();
            route = route == null ? "" : route.trim();
            role = proxy ? NetworkMemberRole.PROXY : role == null || role == NetworkMemberRole.PROXY ? NetworkMemberRole.GAMEPLAY : role;
            capacity = Math.max(0, capacity);
            address = address == null ? "" : address.trim();
            if (address.length() > 255 || address.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Backend Address Is Invalid");
            }
            if (preferredPort < 0 || preferredPort > 65535) throw new IllegalArgumentException("Backend Port Is Invalid");
            management = hostedSource instanceof NetworkMemberSource.External ? NetworkMemberManagement.EXTERNAL
                : management == null ? NetworkMemberManagement.MANAGED : management;
            if (management == NetworkMemberManagement.EXTERNAL) {
                if (proxy) throw new IllegalArgumentException("A Proxy Cannot Be An External Network Member");
                if (address.isBlank() || preferredPort == 0) {
                    throw new IllegalArgumentException("External Backends Need A Reachable Address And Port");
                }
                if (hostedSource != null && !(hostedSource instanceof NetworkMemberSource.External)) {
                    throw new IllegalArgumentException("External Management Needs An External Server Source");
                }
                reSync = false;
            }
        }

        public boolean existing() {
            return !existingId.isBlank();
        }
    }
}
