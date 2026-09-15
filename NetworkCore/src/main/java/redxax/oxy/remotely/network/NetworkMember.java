package redxax.oxy.remotely.network;

import java.util.Locale;
import java.util.UUID;

public record NetworkMember(String instanceId, String nodeId, String routeName, NetworkMemberRole role, String hostScope, String address, int port, int capacity, boolean resyncEnabled, NetworkMemberManagement management) {
    public NetworkMember {
        instanceId = normalize(instanceId);
        nodeId = normalize(nodeId);
        routeName = normalizeRoute(routeName);
        role = role == null ? NetworkMemberRole.CUSTOM : role;
        hostScope = normalize(hostScope);
        address = normalize(address);
        management = management == null ? NetworkMemberManagement.MANAGED : management;
    }

    public NetworkMember(String instanceId, String nodeId, String routeName, NetworkMemberRole role, String hostScope, String address, int port, int capacity, boolean resyncEnabled) {
        this(instanceId, nodeId, routeName, role, hostScope, address, port, capacity, resyncEnabled, NetworkMemberManagement.MANAGED);
    }

    public static NetworkMember proxy(String instanceId, int port) {
        return new NetworkMember(instanceId, UUID.randomUUID().toString(), "proxy", NetworkMemberRole.PROXY, "local", "127.0.0.1", port, 0, true);
    }

    public static NetworkMember backend(String instanceId, String routeName, NetworkMemberRole role, int port) {
        return new NetworkMember(instanceId, UUID.randomUUID().toString(), routeName, role, "local", "127.0.0.1", port, 0, true);
    }

    public boolean isProxy() {
        return role == NetworkMemberRole.PROXY;
    }

    public boolean isManaged() {
        return management == NetworkMemberManagement.MANAGED;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeRoute(String value) {
        return normalize(value).toLowerCase(Locale.ROOT).replace(' ', '-');
    }
}
