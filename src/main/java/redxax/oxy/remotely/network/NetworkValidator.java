package redxax.oxy.remotely.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class NetworkValidator {
    private NetworkValidator() {
    }

    public static List<NetworkValidationIssue> validate(NetworkDefinition network) {
        List<NetworkValidationIssue> issues = new ArrayList<>();
        if (network == null) {
            issues.add(error("network.missing", "network", "Network definition is missing"));
            return List.copyOf(issues);
        }
        if (network.schemaVersion() > NetworkDefinition.CURRENT_SCHEMA_VERSION) {
            issues.add(error("schema.unsupported", network.networkId(), "Network schema is newer than this Remotely version"));
        }
        if (!isUuid(network.networkId())) {
            issues.add(error("network.id.invalid", network.networkId(), "Network ID must be a UUID"));
        }
        if (network.name().isBlank()) {
            issues.add(error("network.name.missing", network.networkId(), "Network name is required"));
        }
        if (network.members().isEmpty()) {
            issues.add(error("members.empty", network.networkId(), "Network requires a proxy and at least one backend"));
        }
        Set<String> instanceIds = new HashSet<>();
        Set<String> nodeIds = new HashSet<>();
        Set<String> externalNodeIds = new HashSet<>();
        Set<String> routeNames = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (NetworkMember member : network.members()) {
            validateMember(member, issues, instanceIds, nodeIds, routeNames);
            if (!member.isManaged()) externalNodeIds.add(member.nodeId());
            if (!endpoints.add(member.hostScope() + ":" + member.port())) {
                issues.add(error("member.port.duplicate", member.nodeId(), "Network members cannot share a port on the same host"));
            }
        }
        NetworkMember proxy = network.proxyMember();
        if (proxy == null) {
            issues.add(error("proxy.missing", network.proxyInstanceId(), "Proxy instance is not a network member"));
        } else if (!proxy.isProxy()) {
            issues.add(error("proxy.role.invalid", proxy.nodeId(), "Proxy member must use the proxy role"));
        } else if (!proxy.isManaged()) {
            issues.add(error("proxy.management.invalid", proxy.nodeId(), "The Velocity proxy must be managed by Remotely"));
        }
        long proxyCount = network.members().stream().filter(NetworkMember::isProxy).count();
        if (proxyCount != 1) {
            issues.add(error("proxy.count.invalid", network.networkId(), "A network must have exactly one proxy"));
        }
        if (network.members().stream().noneMatch(member -> !member.isProxy())) {
            issues.add(error("backend.missing", network.networkId(), "Network requires at least one backend"));
        }
        if (network.forwarding().mode() == ForwardingMode.MODERN && network.forwarding().secretReference().isBlank()) {
            issues.add(error("forwarding.secret.missing", network.networkId(), "Modern forwarding requires a secret reference"));
        }
        if (!network.forwarding().firewallVerified()) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.WARNING, "security.firewall.unverified", network.networkId(), "Backend firewall or private-network protection has not been verified"));
        }
        validateRuntime(network, proxy, issues);
        validateEntries(network, issues);
        validateGroups(network, issues, nodeIds);
        validateRealms(network, issues, nodeIds, externalNodeIds);
        validatePathSyncs(network, issues, nodeIds, externalNodeIds);
        return List.copyOf(issues);
    }

    public static void requireValid(NetworkDefinition network) {
        List<NetworkValidationIssue> errors = validate(network).stream().filter(NetworkValidationIssue::blocksPersistence).toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.getFirst().message());
        }
    }

    private static void validateMember(NetworkMember member, List<NetworkValidationIssue> issues, Set<String> instanceIds, Set<String> nodeIds, Set<String> routeNames) {
        if (member.instanceId().isBlank()) issues.add(error("member.instance.missing", member.nodeId(), "Member instance ID is required"));
        if (!isUuid(member.nodeId())) issues.add(error("member.node.invalid", member.nodeId(), "Member node ID must be a UUID"));
        if (member.routeName().isBlank()) issues.add(error("member.route.missing", member.nodeId(), "Member route name is required"));
        if (!member.isProxy() && member.routeName().equals("try")) issues.add(error("member.route.reserved", member.nodeId(), "Backend route name try is reserved for Velocity fallback routing"));
        if (member.hostScope().isBlank()) issues.add(error("member.host.missing", member.nodeId(), "Member host scope is required"));
        if (member.address().isBlank()) issues.add(error("member.address.missing", member.nodeId(), "Member address is required"));
        if (member.port() < 1 || member.port() > 65535) issues.add(error("member.port.invalid", member.nodeId(), "Member port must be between 1 and 65535"));
        if (member.capacity() < 0) issues.add(error("member.capacity.invalid", member.nodeId(), "Member capacity cannot be negative"));
        if (!member.isManaged() && member.resyncEnabled()) issues.add(error("member.external.resync", member.nodeId(), "External members cannot be enrolled in ReSync until an agent is registered"));
        if (!instanceIds.add(member.instanceId())) issues.add(error("member.instance.duplicate", member.instanceId(), "Instance belongs to the network more than once"));
        if (!nodeIds.add(member.nodeId())) issues.add(error("member.node.duplicate", member.nodeId(), "Node ID is duplicated"));
        if (!routeNames.add(member.routeName())) issues.add(error("member.route.duplicate", member.routeName(), "Route name is duplicated"));
    }

    private static void validateEntries(NetworkDefinition network, List<NetworkValidationIssue> issues) {
        if (network.entryPoints().isEmpty()) {
            issues.add(error("entry.empty", network.networkId(), "Network requires an entry point"));
            return;
        }
        Set<String> ids = new HashSet<>();
        for (NetworkEntryPoint entry : network.entryPoints()) {
            if (entry.id().isBlank()) issues.add(error("entry.id.missing", network.networkId(), "Entry point ID is required"));
            if (entry.bindAddress().isBlank()) issues.add(error("entry.bind.missing", entry.id(), "Entry point bind address is required"));
            if (entry.port() < 1 || entry.port() > 65535) issues.add(error("entry.port.invalid", entry.id(), "Entry point port must be between 1 and 65535"));
            if (!ids.add(entry.id())) issues.add(error("entry.id.duplicate", entry.id(), "Entry point ID is duplicated"));
        }
    }

    private static void validateRuntime(NetworkDefinition network, NetworkMember proxy, List<NetworkValidationIssue> issues) {
        List<NetworkMember> runtimeMembers = network.members().stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).toList();
        NetworkRuntimePolicy runtime = network.runtime();
        if (!runtimeMembers.isEmpty() && !runtime.enabled()) {
            issues.add(error("runtime.required", network.networkId(), "ReSync-enabled servers require the network runtime hub"));
            return;
        }
        if (!runtime.enabled()) {
            return;
        }
        if (runtime.hubAddress().isBlank()) {
            issues.add(error("runtime.address.missing", network.networkId(), "Network runtime hub address is required"));
        }
        if (runtime.hubPort() < 1 || runtime.hubPort() > 65535) {
            issues.add(error("runtime.port.invalid", network.networkId(), "Network runtime hub port must be between 1 and 65535"));
        }
        if (proxy == null) {
            return;
        }
        if (network.members().stream().anyMatch(member -> member.hostScope().equals(proxy.hostScope()) && member.port() == runtime.hubPort())) {
            issues.add(error("runtime.port.member-conflict", network.networkId(), "Network runtime hub port conflicts with a server on the proxy host"));
        }
        if (network.entryPoints().stream().anyMatch(entry -> entry.port() == runtime.hubPort())) {
            issues.add(error("runtime.port.entry-conflict", network.networkId(), "Network runtime hub port conflicts with a proxy entry point"));
        }
        if (runtime.security() == NetworkTransportSecurity.LOOPBACK) {
            if (!loopbackAddress(runtime.hubAddress())) {
                issues.add(error("runtime.loopback.address", network.networkId(), "Loopback runtime must use a loopback hub address"));
            }
            if (!runtime.transportReady()) {
                issues.add(error("runtime.loopback.unavailable", network.networkId(), "Loopback runtime transport must be ready"));
            }
            if (runtimeMembers.stream().anyMatch(member -> !member.hostScope().equals(proxy.hostScope()))) {
                issues.add(error("runtime.loopback.cross-host", network.networkId(), "Cross-host ReSync servers require WSS transport"));
            }
        } else if (!runtime.transportReady()) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.WARNING, "runtime.wss.pending", network.networkId(), "Cross-host ReSync transport is waiting for certificate provisioning"));
        }
    }

    private static void validateGroups(NetworkDefinition network, List<NetworkValidationIssue> issues, Set<String> nodeIds) {
        Set<String> groupIds = new HashSet<>();
        Set<String> forcedHosts = new HashSet<>();
        Set<String> routableNodes = network.members().stream().filter(member -> !member.isProxy()).map(NetworkMember::nodeId).collect(Collectors.toSet());
        Map<String, String> fallbacks = new LinkedHashMap<>();
        for (RoutingGroup group : network.routingGroups()) {
            if (group.id().isBlank()) issues.add(error("routing.id.missing", network.networkId(), "Routing group ID is required"));
            if (group.name().isBlank()) issues.add(error("routing.name.missing", group.id(), "Routing group name is required"));
            if (!groupIds.add(group.id())) issues.add(error("routing.id.duplicate", group.id(), "Routing group ID is duplicated"));
            Set<String> groupNodes = new HashSet<>();
            for (String nodeId : group.nodeIds()) {
                if (!nodeIds.contains(nodeId)) issues.add(error("routing.node.unknown", nodeId, "Routing group references an unknown node"));
                else if (!routableNodes.contains(nodeId)) issues.add(error("routing.node.proxy", nodeId, "Routing groups cannot target the proxy"));
                if (!groupNodes.add(nodeId)) issues.add(error("routing.node.duplicate", nodeId, "Routing group contains a server more than once"));
            }
            for (String weightedNode : group.weights().keySet()) {
                if (!groupNodes.contains(weightedNode)) issues.add(error("routing.weight.node.unknown", weightedNode, "Routing weight references a server outside the group"));
                int weight = group.weights().getOrDefault(weightedNode, 0);
                if (weight < 1 || weight > 10_000) issues.add(error("routing.weight.invalid", weightedNode, "Routing weights must be between 1 and 10000"));
            }
            if (group.strategy() == RoutingStrategy.WEIGHTED) {
                group.nodeIds().stream().filter(nodeId -> group.weights().getOrDefault(nodeId, 0) <= 0).forEach(nodeId -> issues.add(error("routing.weight.missing", nodeId, "Weighted routing requires a positive weight for every server")));
            }
            for (String forcedHost : group.forcedHosts()) {
                if (forcedHost.isBlank()) issues.add(error("routing.host.missing", group.id(), "Forced host cannot be empty"));
                if (!forcedHosts.add(forcedHost.toLowerCase(Locale.ROOT))) issues.add(error("routing.host.duplicate", forcedHost, "Forced host belongs to more than one routing group"));
            }
            if (!group.fallbackGroupId().isBlank()) fallbacks.put(group.id(), group.fallbackGroupId());
        }
        for (RoutingGroup group : network.routingGroups()) {
            if (!group.fallbackGroupId().isBlank() && !groupIds.contains(group.fallbackGroupId())) {
                issues.add(error("routing.fallback.unknown", group.fallbackGroupId(), "Fallback routing group does not exist"));
            }
        }
        fallbacks.keySet().stream().filter(groupId -> hasFallbackCycle(groupId, fallbacks)).forEach(groupId -> issues.add(error("routing.fallback.cycle", groupId, "Routing fallback chain contains a cycle")));
    }

    private static boolean hasFallbackCycle(String start, Map<String, String> fallbacks) {
        Set<String> visited = new HashSet<>();
        String current = start;
        while (fallbacks.containsKey(current)) {
            if (!visited.add(current)) {
                return true;
            }
            current = fallbacks.get(current);
        }
        return false;
    }

    private static void validateRealms(NetworkDefinition network, List<NetworkValidationIssue> issues, Set<String> nodeIds, Set<String> externalNodeIds) {
        Set<String> realmIds = new HashSet<>();
        Map<String, Integer> stateRealmMembership = new LinkedHashMap<>();
        for (SyncRealm realm : network.syncRealms()) {
            if (realm.id().isBlank()) issues.add(error("realm.id.missing", network.networkId(), "Synchronization realm ID is required"));
            if (!realm.id().matches("[a-z0-9][a-z0-9._-]{0,63}")) issues.add(error("realm.id.invalid", realm.id(), "Synchronization realm ID must use lowercase letters, numbers, dots, dashes, or underscores"));
            if (!realmIds.add(realm.id())) issues.add(error("realm.id.duplicate", realm.id(), "Synchronization realm ID is duplicated"));
            for (String nodeId : realm.nodeIds()) {
                if (!nodeIds.contains(nodeId)) issues.add(error("realm.node.unknown", nodeId, "Synchronization realm references an unknown node"));
                if (externalNodeIds.contains(nodeId)) issues.add(error("realm.node.external", nodeId, "External nodes cannot join synchronization realms until an agent is registered"));
            }
            if (realm.dataFamilies().contains(SyncDataFamily.PERSISTENT_DATA) && realm.persistentDataNamespaces().isEmpty()) {
                issues.add(error("realm.pdc.allowlist.empty", realm.id(), "Persistent data synchronization requires at least one namespace"));
            }
            realm.persistentDataNamespaces().stream().filter(namespace -> !namespace.matches("[a-z0-9][a-z0-9._-]{0,63}")).forEach(namespace -> issues.add(error("realm.pdc.allowlist.invalid", realm.id(), "Persistent data namespaces must use lowercase letters, numbers, dots, dashes, or underscores")));
            if (realm.dataFamilies().contains(SyncDataFamily.LOCATION) && realm.locationPolicy() == SyncLocationPolicy.NEVER) {
                issues.add(error("realm.location.policy.missing", realm.id(), "Location synchronization requires an explicit location policy"));
            }
            if (!realm.dataFamilies().contains(SyncDataFamily.LOCATION) && realm.locationPolicy() != SyncLocationPolicy.NEVER) {
                issues.add(error("realm.location.family.missing", realm.id(), "Add the Location family before choosing a location policy"));
            }
            boolean stateful = realm.dataFamilies().stream().anyMatch(family -> family != SyncDataFamily.PRESENCE);
            if (stateful && realm.nodeIds().size() < 2) {
                issues.add(error("realm.nodes.insufficient", realm.id(), "Player state synchronization requires at least two enrolled servers"));
            }
            if (stateful) {
                for (String nodeId : realm.nodeIds()) {
                    NetworkMember member = network.members().stream().filter(candidate -> candidate.nodeId().equals(nodeId)).findFirst().orElse(null);
                    if (member != null && (member.isProxy() || !member.resyncEnabled())) {
                        issues.add(error("realm.node.runtime.unavailable", nodeId, "Player state synchronization requires an enrolled backend node"));
                    }
                    stateRealmMembership.merge(nodeId, 1, Integer::sum);
                }
            }
        }
        stateRealmMembership.forEach((nodeId, count) -> {
            if (count > 1) {
                issues.add(error("realm.node.ambiguous", nodeId, "A backend cannot belong to multiple player state realms"));
            }
        });
    }

    private static void validatePathSyncs(NetworkDefinition network, List<NetworkValidationIssue> issues, Set<String> nodeIds, Set<String> externalNodeIds) {
        for (NetworkPathSync sync : network.sharedDataPolicy().pathSyncs()) {
            if (sync.enabled() && sync.nodeIds().size() < 2) {
                issues.add(error("path-sync.nodes.insufficient", sync.id(), "Path Sync requires at least two servers"));
            }
            for (String nodeId : sync.nodeIds()) {
                if (!nodeIds.contains(nodeId)) {
                    issues.add(error("path-sync.node.unknown", nodeId, "Path Sync references an unknown server"));
                    continue;
                }
                NetworkMember member = network.members().stream().filter(candidate -> candidate.nodeId().equals(nodeId)).findFirst().orElse(null);
                if (externalNodeIds.contains(nodeId) || member == null || member.isProxy() || !member.resyncEnabled()) {
                    issues.add(error("path-sync.node.unavailable", nodeId, "Path Sync requires a managed ReSync server"));
                }
            }
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean loopbackAddress(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1") || normalized.equals("[::1]") || normalized.equals("0:0:0:0:0:0:0:1") || normalized.equals("[0:0:0:0:0:0:0:1]");
    }

    private static NetworkValidationIssue error(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, code, subject, message);
    }
}
