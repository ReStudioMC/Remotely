package redxax.oxy.remotely.network;

import restudio.rebase.instance.Instance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class NetworkPortAllocator {
    static {
        NetworkHostScope.installResolver(DesktopNetworkHostScope::describe);
    }

    public static final int DEFAULT_RANGE_START = 25565;
    public static final int DEFAULT_RANGE_END = 25999;

    public List<PortReservation> discover(Collection<NetworkDefinition> networks, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        Map<String, PortReservation> reservations = new LinkedHashMap<>();
        if (networks != null) {
            for (NetworkDefinition network : networks) {
                for (NetworkMember member : network.members()) {
                    reserve(reservations, new PortReservation(member.hostScope(), member.port(), "network-member", member.instanceId(), network.name() + " / " + member.routeName()));
                }
                NetworkMember proxy = network.proxyMember();
                if (proxy != null && network.runtime().enabled() && network.runtime().hubPort() > 0) {
                    reserve(reservations, new PortReservation(proxy.hostScope(), network.runtime().hubPort(), "resync-network-hub", network.networkId(), network.name() + " / ReSync Hub"));
                }
            }
        }
        if (instances != null) {
            for (Instance instance : instances) {
                discoverInstance(instance).forEach(reservation -> reserve(reservations, reservation));
            }
        }
        if (externalReservations != null) {
            externalReservations.forEach(reservation -> reserve(reservations, reservation));
        }
        return List.copyOf(reservations.values());
    }

    public int allocate(String hostScope, int preferredPort, int rangeStart, int rangeEnd, Collection<PortReservation> reservations) {
        String resolvedHost = hostScope == null || hostScope.isBlank() ? "local" : hostScope.trim();
        int start = Math.clamp(rangeStart, 1, 65535);
        int end = Math.clamp(rangeEnd, start, 65535);
        Set<Integer> used = new LinkedHashSet<>();
        if (reservations != null) {
            reservations.stream().filter(reservation -> reservation.hostScope().equals(resolvedHost)).map(PortReservation::port).filter(port -> port >= 1 && port <= 65535).forEach(used::add);
        }
        if (preferredPort >= start && preferredPort <= end && !used.contains(preferredPort)) {
            return preferredPort;
        }
        for (int port = start; port <= end; port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        throw new IllegalStateException("No available ports on " + resolvedHost + " in range " + start + "-" + end);
    }

    public int allocate(String hostScope, Collection<PortReservation> reservations) {
        return allocate(hostScope, DEFAULT_RANGE_START, DEFAULT_RANGE_START, DEFAULT_RANGE_END, reservations);
    }

    public List<PortReservation> conflicts(Collection<PortReservation> reservations) {
        Map<String, List<PortReservation>> byEndpoint = new LinkedHashMap<>();
        if (reservations != null) {
            for (PortReservation reservation : reservations) {
                byEndpoint.computeIfAbsent(key(reservation), ignored -> new ArrayList<>()).add(reservation);
            }
        }
        return byEndpoint.values().stream().filter(group -> group.size() > 1).flatMap(List::stream).toList();
    }

    private List<PortReservation> discoverInstance(Instance instance) {
        List<PortReservation> reservations = new ArrayList<>();
        String hostScope = NetworkHostScope.resolve(instance);
        Properties properties = instance.getServerProperties();
        if (!instance.isProxyServer()) {
            addPropertyPort(reservations, hostScope, instance, properties, "server-port", "Minecraft");
            if (Boolean.parseBoolean(properties.getProperty("enable-query", "false"))) {
                addPropertyPort(reservations, hostScope, instance, properties, "query.port", "Query");
            }
            if (Boolean.parseBoolean(properties.getProperty("enable-rcon", "false"))) {
                addPropertyPort(reservations, hostScope, instance, properties, "rcon.port", "RCON");
            }
        }
        addSettingPort(reservations, hostScope, instance, "management-server-port", "Management");
        addPropertyPort(reservations, hostScope, instance, properties, "management-server-port", "Management");
        return reservations;
    }

    private void addPropertyPort(List<PortReservation> reservations, String hostScope, Instance instance, Properties properties, String key, String label) {
        int port = parsePort(properties.getProperty(key));
        if (port > 0) {
            reservations.add(new PortReservation(hostScope, port, "instance", instance.getInstanceId(), instance.getName() + " / " + label));
        }
    }

    private void addSettingPort(List<PortReservation> reservations, String hostScope, Instance instance, String key, String label) {
        int port = parsePort(instance.getSettings().getProperty(key));
        if (port > 0) {
            reservations.add(new PortReservation(hostScope, port, "instance", instance.getInstanceId(), instance.getName() + " / " + label));
        }
    }

    private int parsePort(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void reserve(Map<String, PortReservation> reservations, PortReservation reservation) {
        String key = key(reservation) + "|" + reservation.ownerId();
        reservations.putIfAbsent(key, reservation);
    }

    private String key(PortReservation reservation) {
        return reservation.hostScope() + ":" + reservation.port();
    }
}
