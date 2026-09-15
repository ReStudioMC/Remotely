package redxax.oxy.remotely.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class NetworkDetachPlanner {
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("1\\.(\\d+)(?:\\.(\\d+))?");

    public NetworkReconciliationPlan plan(NetworkPlanInput input, String instanceId) {
        return plan(input, instanceId, null, null);
    }

    public NetworkReconciliationPlan plan(NetworkPlanInput input, String instanceId, NetworkMemberRestorePoint restorePoint,
                                           NetworkRestoreValueResolver restoreValues) {
        Objects.requireNonNull(input, "input");
        NetworkDefinition network = input.network();
        List<NetworkValidationIssue> issues = new ArrayList<>(input.issues());
        NetworkMember member = network.members().stream().filter(candidate -> candidate.instanceId().equals(instanceId)).findFirst().orElse(null);
        if (member == null) {
            issues.add(error("detach.member.missing", instanceId, "Server is not a member of this network"));
            return empty(network, issues);
        }
        if (member.isProxy()) {
            issues.add(error("detach.proxy.unsupported", instanceId, "The proxy cannot be detached while the network exists"));
            return empty(network, issues);
        }
        if (network.members().stream().filter(candidate -> !candidate.isProxy()).count() <= 1) {
            issues.add(error("detach.last-backend", instanceId, "The last backend cannot be detached while the network exists"));
            return empty(network, issues);
        }
        Map<String, NetworkServerDescriptor> servers = input.servers();
        NetworkServerDescriptor proxy = servers.get(network.proxyInstanceId());
        NetworkServerDescriptor backend = servers.get(instanceId);
        if (proxy == null) {
            issues.add(error("detach.proxy.unavailable", network.proxyInstanceId(), "Proxy is unavailable for route removal"));
        }
        if (member.isManaged() && backend == null) {
            issues.add(error("detach.backend.unavailable", instanceId, "Server is unavailable for independent-safe restoration"));
        }
        if (proxy == null || member.isManaged() && backend == null) {
            return empty(network, issues);
        }
        List<NetworkConfigMutation> mutations = new ArrayList<>();
        remove(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "servers." + member.routeName(), false, true, "Remove Backend Route");
        List<String> fallbackRoutes = network.routingGroups().stream().filter(group -> group.id().equals("fallback"))
                .flatMap(group -> group.nodeIds().stream()).filter(nodeId -> !nodeId.equals(member.nodeId()))
                .map(nodeId -> routeForNode(network, nodeId)).filter(route -> !route.isBlank()).toList();
        set(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "servers.try", "", tomlArray(fallbackRoutes), false, true, "Update Fallback Order");
        for (RoutingGroup group : network.routingGroups()) {
            List<String> routes = group.nodeIds().stream().filter(nodeId -> !nodeId.equals(member.nodeId()))
                    .map(nodeId -> routeForNode(network, nodeId)).filter(route -> !route.isBlank()).toList();
            for (String forcedHost : group.forcedHosts()) {
                if (routes.isEmpty()) {
                    remove(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "forced-hosts." + forcedHost, false, true, "Remove Empty Forced Host");
                } else {
                    set(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "forced-hosts." + forcedHost, "", tomlArray(routes), false, true, "Update Forced Host");
                }
            }
        }
        if (network.runtime().enabled()) {
            List<String> runtimeRoutes = network.members().stream().filter(candidate -> !candidate.isProxy() && !candidate.nodeId().equals(member.nodeId())).map(NetworkMember::routeName).toList();
            set(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, "routes", "", String.join(",", runtimeRoutes), false, true, "Remove ReSync Runtime Route");
            String maintenanceRoute = fallbackRoutes.isEmpty() ? runtimeRoutes.getFirst() : fallbackRoutes.getFirst();
            set(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, "maintenance-route", "", maintenanceRoute, false, true, "Update ReSync Maintenance Route");
            removeRuntimeRoute(mutations, proxy, member.routeName());
        }
        if (member.resyncEnabled()) {
            List<String> nodes = new ArrayList<>();
            nodes.add(NetworkRuntimeIdentity.operatorNodeId(network.networkId()));
            network.members().stream().filter(candidate -> !candidate.isProxy() && candidate.isManaged() && candidate.resyncEnabled() && !candidate.nodeId().equals(member.nodeId())).map(NetworkMember::nodeId).forEach(nodes::add);
            set(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, "nodes", "", String.join(",", nodes), false, true, "Remove ReSync Network Node");
            removeRuntimeNode(mutations, proxy, member.nodeId());
        }
        if (member.isManaged()) {
            planRestoreBackend(mutations, member, restorePoint, restoreValues, issues);
        }
        return new NetworkReconciliationPlan("", network.networkId(), network.revision(), 0, mutations, issues, NetworkPlanStrategy.DETACH);
    }

    public NetworkReconciliationPlan planDissolve(NetworkPlanInput input) {
        Objects.requireNonNull(input, "input");
        NetworkDefinition network = input.network();
        List<NetworkValidationIssue> issues = new ArrayList<>();
        Map<String, NetworkServerDescriptor> servers = input.servers();
        NetworkServerDescriptor proxy = servers.get(network.proxyInstanceId());
        if (proxy == null) {
            issues.add(warning("dissolve.proxy.unavailable", network.proxyInstanceId(), "Proxy cleanup was skipped because the server is unavailable"));
        }
        List<NetworkConfigMutation> mutations = new ArrayList<>();
        for (NetworkMember member : network.members()) {
            if (member.isProxy()) {
                continue;
            }
            NetworkServerDescriptor backend = servers.get(member.instanceId());
            if (member.isManaged() && backend == null) {
                issues.add(warning("dissolve.backend.unavailable", member.instanceId(), "Server cleanup was skipped because the server is unavailable"));
                continue;
            }
            if (proxy != null) {
                remove(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "servers." + member.routeName(), false, true, "Remove Backend Route");
                removeRuntimeRoute(mutations, proxy, member.routeName());
            }
            if (member.isManaged()) {
                planIndependentBackend(mutations, backend, member.nodeId(), issues, true);
            }
            if (proxy != null && member.resyncEnabled()) {
                removeRuntimeNode(mutations, proxy, member.nodeId());
            }
        }
        if (proxy != null) {
            removeRuntimeNode(mutations, proxy, NetworkRuntimeIdentity.operatorNodeId(network.networkId()));
            set(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, "network.enabled", "", "false", false, true, "Disable ReSync Network Hub");
            set(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, "nodes", "", "", false, true, "Clear ReSync Network Nodes");
            set(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, "routes", "", "", false, true, "Clear ReSync Runtime Routes");
            set(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, "maintenance-route", "", "", false, true, "Clear ReSync Maintenance Route");
            set(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "servers.try", "", "[]", false, true, "Clear Fallback Order");
            for (RoutingGroup group : network.routingGroups()) {
                for (String forcedHost : group.forcedHosts()) {
                    remove(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "forced-hosts." + forcedHost, false, true, "Remove Forced Host");
                }
            }
            set(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "player-info-forwarding-mode", "", "\"none\"", false, true, "Disable Player Forwarding");
            set(mutations, proxy, "forwarding.secret", ConfigurationFormat.SECRET, "content", "", "", true, true, "Erase Forwarding Secret File");
        }
        return new NetworkReconciliationPlan("", network.networkId(), network.revision(), 0, mutations, issues, NetworkPlanStrategy.DETACH);
    }

    private void planRestoreBackend(List<NetworkConfigMutation> mutations, NetworkMember member, NetworkMemberRestorePoint restorePoint,
                                    NetworkRestoreValueResolver restoreValues, List<NetworkValidationIssue> issues) {
        if (restorePoint == null || restoreValues == null) {
            issues.add(warning("detach.restore-point.missing", member.nodeId(), "Original Server Configuration Is Unavailable; The Server Kept Its Current Settings"));
            return;
        }
        if (!restorePoint.instanceId().equals(member.instanceId()) || !restorePoint.nodeId().equals(member.nodeId())) {
            issues.add(error("detach.restore-point.invalid", member.nodeId(), "Original server configuration belongs to another network member"));
            return;
        }
        for (NetworkRestoreEntry entry : restorePoint.entries()) {
            if (entry.present()) {
                set(mutations, member.instanceId(), entry.path(), entry.format(), entry.key(), restoreValues.resolve(entry), entry.sensitive(), "Restore " + entry.key());
            } else {
                remove(mutations, member.instanceId(), entry.path(), entry.format(), entry.key(), entry.sensitive(), true, "Remove Network-Owned " + entry.key());
            }
        }
    }

    private void planIndependentBackend(List<NetworkConfigMutation> mutations, NetworkServerDescriptor backend, String subject,
                                        List<NetworkValidationIssue> issues, boolean bestEffort) {
        set(mutations, backend, "server.properties", ConfigurationFormat.PROPERTIES, "online-mode", backend.property("online-mode", "false"), "true", false, true, "Restore Direct Authentication");
        set(mutations, backend, "server.properties", ConfigurationFormat.PROPERTIES, "server-ip", backend.property("server-ip", "127.0.0.1"), "", false, true, "Restore Direct Binding");
        switch (backend.forwardingAdapter()) {
            case PAPER -> {
                set(mutations, backend, "spigot.yml", ConfigurationFormat.YAML, "settings.bungeecord", "", "false", false, true, "Disable Proxy Forwarding");
                if (usesLegacyPaperConfiguration(backend.version())) {
                    set(mutations, backend, "paper.yml", ConfigurationFormat.YAML, "settings.velocity-support.enabled", "", "false", false, true, "Disable Modern Forwarding");
                } else {
                    set(mutations, backend, "config/paper-global.yml", ConfigurationFormat.YAML, "proxies.velocity.enabled", "", "false", false, true, "Disable Modern Forwarding");
                }
            }
            case FABRIC_PROXY_LITE -> set(mutations, backend, "config/FabricProxy-Lite.toml", ConfigurationFormat.TOML, "secret", "", "\"\"", true, true, "Clear Fabric Forwarding Secret");
            case PROXY_COMPATIBLE_FORGE -> {
                set(mutations, backend, "config/proxy-compatible-forge.toml", ConfigurationFormat.TOML, "forwarding.enabled", "", "false", false, true, "Disable Forge Forwarding");
                set(mutations, backend, "config/proxy-compatible-forge.toml", ConfigurationFormat.TOML, "forwarding.secret", "", "\"\"", true, true, "Clear Forge Forwarding Secret");
            }
            case UNSUPPORTED -> issues.add(bestEffort
                    ? warning("dissolve.forwarding.adapter.unavailable", subject, "Proxy forwarding cleanup requires manual review for this server type")
                    : error("detach.forwarding.adapter.unavailable", subject, "Backend forwarding cannot be disabled safely because its configuration adapter is unavailable"));
        }
        set(mutations, backend, "plugins/ReSync/resync.properties", ConfigurationFormat.PROPERTIES, "network.enabled", "", "false", false, true, "Disable ReSync Network Runtime");
        remove(mutations, backend, "plugins/ReSync/resync.properties", ConfigurationFormat.PROPERTIES, "network.id", false, true, "Remove ReSync Network");
        remove(mutations, backend, "plugins/ReSync/resync.properties", ConfigurationFormat.PROPERTIES, "network.node-id", false, true, "Remove ReSync Node");
        remove(mutations, backend, "plugins/ReSync/resync.properties", ConfigurationFormat.PROPERTIES, "network.hub-url", false, true, "Remove ReSync Hub");
        remove(mutations, backend, "plugins/ReSync/resync.properties", ConfigurationFormat.PROPERTIES, "network.enrollment-token", true, true, "Remove ReSync Enrollment Token");
        set(mutations, backend, "plugins/ReSync/network/node.credential", ConfigurationFormat.SECRET, "content", "", "", true, true, "Erase ReSync Node Credential");
    }

    private void removeRuntimeNode(List<NetworkConfigMutation> mutations, NetworkServerDescriptor proxy, String nodeId) {
        String prefix = "node." + nodeId + ".";
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "display-name", false, true, "Remove ReSync Node Name");
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "role", false, true, "Remove ReSync Node Role");
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "capabilities", false, true, "Remove ReSync Node Capabilities");
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "enrollment-token-hash", true, true, "Remove ReSync Enrollment Hash");
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "enrollment-expires-at", false, true, "Remove ReSync Enrollment Expiry");
    }

    private void removeRuntimeRoute(List<NetworkConfigMutation> mutations, NetworkServerDescriptor proxy, String routeName) {
        String prefix = "route." + routeName + ".";
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "node-id", false, true, "Remove ReSync Route Node");
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "address", false, true, "Remove ReSync Route Address");
        remove(mutations, proxy, "plugins/resyncvelocity/network.properties", ConfigurationFormat.PROPERTIES, prefix + "port", false, true, "Remove ReSync Route Port");
    }

    private void set(List<NetworkConfigMutation> mutations, NetworkServerDescriptor server, String path, ConfigurationFormat format,
                     String key, String currentValue, String desiredValue, boolean sensitive, boolean restartRequired, String description) {
        mutations.add(new NetworkConfigMutation(server.serverId(), path, format, key, currentValue, desiredValue, sensitive, restartRequired, description));
    }

    private void set(List<NetworkConfigMutation> mutations, String serverId, String path, ConfigurationFormat format,
                     String key, String desiredValue, boolean sensitive, String description) {
        mutations.add(new NetworkConfigMutation(serverId, path, format, key, "", desiredValue, sensitive, true, description));
    }

    private void remove(List<NetworkConfigMutation> mutations, NetworkServerDescriptor server, String path, ConfigurationFormat format,
                        String key, boolean sensitive, boolean restartRequired, String description) {
        remove(mutations, server.serverId(), path, format, key, sensitive, restartRequired, description);
    }

    private void remove(List<NetworkConfigMutation> mutations, String serverId, String path, ConfigurationFormat format,
                        String key, boolean sensitive, boolean restartRequired, String description) {
        mutations.add(new NetworkConfigMutation(serverId, path, format, key, "", "", sensitive, restartRequired, description, NetworkMutationAction.REMOVE));
    }

    private NetworkReconciliationPlan empty(NetworkDefinition network, List<NetworkValidationIssue> issues) {
        return new NetworkReconciliationPlan("", network.networkId(), network.revision(), 0, List.of(), issues, NetworkPlanStrategy.DETACH);
    }

    private String routeForNode(NetworkDefinition network, String nodeId) {
        return network.members().stream().filter(member -> member.nodeId().equals(nodeId)).map(NetworkMember::routeName).findFirst().orElse("");
    }

    private boolean usesLegacyPaperConfiguration(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        Matcher matcher = MINECRAFT_VERSION.matcher(version);
        if (!matcher.find()) {
            return false;
        }
        int minor = Integer.parseInt(matcher.group(1));
        int patch = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return minor < 18 || minor == 18 && patch <= 2;
    }

    private String tomlArray(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private NetworkValidationIssue error(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, code, subject, message);
    }

    private NetworkValidationIssue warning(String code, String subject, String message) {
        return new NetworkValidationIssue(NetworkValidationIssue.Severity.WARNING, code, subject, message);
    }
}
