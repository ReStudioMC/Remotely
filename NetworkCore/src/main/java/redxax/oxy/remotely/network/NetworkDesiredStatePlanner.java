package redxax.oxy.remotely.network;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NetworkDesiredStatePlanner {
    private static final Set<SyncDataFamily> SUPPORTED_TRANSFER_FAMILIES = Set.copyOf(EnumSet.allOf(SyncDataFamily.class));
    private final NetworkClock clock;

    public NetworkDesiredStatePlanner() {
        this(NetworkClock.SYSTEM);
    }

    public NetworkDesiredStatePlanner(NetworkClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public NetworkReconciliationPlan plan(NetworkPlanInput input, NetworkSecrets secrets) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(secrets, "secrets");
        NetworkDefinition network = Objects.requireNonNull(input.network(), "input.network");
        List<NetworkConfigMutation> mutations = new ArrayList<>();
        List<NetworkValidationIssue> issues = new ArrayList<>(input.issues());
        if (network.forwarding().mode() == ForwardingMode.LEGACY || network.forwarding().mode() == ForwardingMode.BUNGEEGUARD) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "forwarding.mode.apply.unsupported", network.networkId(), "Legacy forwarding requires its dedicated compatibility adapter before Remotely can apply this network"));
        }
        String forwardingSecret = secrets.forwardingSecret(network.forwarding().secretReference());
        if (network.forwarding().mode() != ForwardingMode.NONE && forwardingSecret.isBlank()) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "forwarding.secret.unavailable", network.networkId(), "Forwarding secret is unavailable in the credential store"));
        }
        if (network.runtime().enabled() && network.runtime().security() == NetworkTransportSecurity.WSS && !network.runtime().transportReady()) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "resync.transport.unavailable", network.networkId(), "Cross-host ReSync requires provisioned WSS certificates before configuration can be applied"));
        }
        if (network.runtime().enabled() && network.runtime().hubUrl().isBlank()) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "resync.hub.unavailable", network.networkId(), "ReSync network hub address is incomplete"));
        }
        if (network.syncRealms().stream().filter(realm -> realm.dataFamilies().stream().anyMatch(family -> family != SyncDataFamily.PRESENCE)).count() > 1) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "realm.runtime.multiple.unsupported", network.networkId(), "The installed ReSync runtime currently supports one player state realm per network"));
        }
        if (network.runtime().enabled() && network.runtime().security() == NetworkTransportSecurity.LOOPBACK
                && network.members().stream().anyMatch(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()
                    && !NetworkRuntimePolicy.sharesLoopback(network.proxyMember(), member))) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "resync.loopback.isolated", network.networkId(), "ReSync Servers In Separate Machines Or Containers Require A Reachable Secure Hub Address"));
        }
        Map<String, NetworkEnrollment> enrollments = new LinkedHashMap<>();
        NetworkMember proxyMember = network.proxyMember();
        if (proxyMember != null) {
            NetworkServerDescriptor proxy = input.servers().get(proxyMember.instanceId());
            if (proxy != null) {
                planProxy(network, proxy, proxyMember, forwardingSecret, secrets, enrollments, mutations, issues);
            }
        }
        for (NetworkMember member : network.members()) {
            if (member.isProxy()) {
                continue;
            }
            if (!member.isManaged()) {
                continue;
            }
            NetworkServerDescriptor backend = input.servers().get(member.instanceId());
            if (backend != null) {
                planBackend(network, backend, member, proxyMember, forwardingSecret, secrets, enrollments, mutations, issues);
            }
        }
        return new NetworkReconciliationPlan("", network.networkId(), network.revision(), clock.millis(), mutations, issues);
    }

    private void planProxy(NetworkDefinition network, NetworkServerDescriptor proxy, NetworkMember proxyMember, String forwardingSecret, NetworkSecrets secrets, Map<String, NetworkEnrollment> enrollments, List<NetworkConfigMutation> mutations, List<NetworkValidationIssue> issues) {
        String bind = network.entryPoints().isEmpty() ? "0.0.0.0:" + proxyMember.port() : network.entryPoints().getFirst().bindAddress() + ":" + network.entryPoints().getFirst().port();
        add(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "bind", "", quote(bind), false, true, "Set Proxy Address");
        add(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "online-mode", "", String.valueOf(network.forwarding().proxyOnlineMode()), false, true, "Set Proxy Online Mode");
        add(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "player-info-forwarding-mode", "", quote(forwardingValue(network.forwarding().mode())), false, true, "Set Forwarding Mode");
        remove(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "forwarding-secret", true, true, "Remove Deprecated Forwarding Secret");
        if (network.forwarding().mode() != ForwardingMode.NONE) {
            add(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "forwarding-secret-file", "", quote("forwarding.secret"), false, true, "Set Forwarding Secret File");
            add(mutations, proxy, "forwarding.secret", ConfigurationFormat.SECRET, "content", "", forwardingSecret, true, true, "Write Forwarding Secret");
        }
        Map<String, String> routes = new LinkedHashMap<>();
        for (NetworkMember member : network.members()) {
            if (!member.isProxy()) {
                if (member.routeName().equals("try")) {
                    issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "member.route.reserved", member.nodeId(), "Backend route name try is reserved for Velocity fallback routing"));
                }
                routes.put(member.routeName(), quote(member.address() + ":" + member.port()));
            }
        }
        routes.put("try", tomlArray(fallbackRoutes(network)));
        add(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "servers.*", "", tomlSection(routes), false, true, "Set Proxy Routes");
        Map<String, String> forcedHosts = new LinkedHashMap<>();
        for (RoutingGroup group : network.routingGroups()) {
            for (String forcedHost : group.forcedHosts()) {
                List<String> hostsRoutes = group.nodeIds().stream().map(nodeId -> routeForNode(network, nodeId)).filter(value -> !value.isBlank()).toList();
                forcedHosts.put(forcedHost, tomlArray(hostsRoutes));
            }
        }
        remove(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "forced-hosts", false, true, "Normalize Forced Hosts");
        add(mutations, proxy, "velocity.toml", ConfigurationFormat.TOML, "forced-hosts.*", "", tomlSection(forcedHosts), false, true, "Set Forced Hosts");
        planRuntimeHub(network, proxy, secrets, enrollments, mutations, issues);
    }

    private void planRuntimeHub(NetworkDefinition network, NetworkServerDescriptor proxy, NetworkSecrets secrets, Map<String, NetworkEnrollment> enrollments, List<NetworkConfigMutation> mutations, List<NetworkValidationIssue> issues) {
        String path = "plugins/resyncvelocity/network.properties";
        NetworkRuntimePolicy runtime = network.runtime();
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "network.enabled", "", String.valueOf(runtime.enabled()), false, true, "Set ReSync Network Hub");
        if (!runtime.enabled()) {
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "nodes", "", "", false, true, "Clear ReSync Network Nodes");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "routes", "", "", false, true, "Clear ReSync Runtime Routes");
            return;
        }
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "network.id", "", network.networkId(), false, true, "Set ReSync Hub Network");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "network.node-id", "", network.proxyMember().nodeId(), false, true, "Set ReSync Hub Node");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "network.display-name", "", network.name() + " Proxy", false, true, "Set ReSync Hub Name");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.bind-host", "", runtime.security() == NetworkTransportSecurity.LOOPBACK ? runtime.hubAddress() : runtime.hubAddress().contains(":") ? "::" : "0.0.0.0", false, true, "Set ReSync Hub Bind");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.port", "", String.valueOf(runtime.hubPort()), false, true, "Set ReSync Hub Port");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.database", "", "network/network.db", false, true, "Set ReSync Hub Database");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.maximum-frame-bytes", "", "1048576", false, true, "Set ReSync Frame Limit");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.maximum-payload-bytes", "", "524288", false, true, "Set ReSync Payload Limit");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.heartbeat-timeout-millis", "", "15000", false, true, "Set ReSync Heartbeat Timeout");
        int retentionDays = network.syncRealms().stream().mapToInt(SyncRealm::retentionDays).max().orElse(30);
        int retainedSnapshots = network.syncRealms().stream().mapToInt(SyncRealm::retainedSnapshots).max().orElse(20);
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "snapshot.retention-millis", "", Long.toString(retentionDays * 86400000L), false, true, "Set ReSync Snapshot Retention");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "snapshot.retention-per-player-family", "", Integer.toString(retainedSnapshots), false, true, "Set ReSync Snapshot History Limit");
        boolean tls = runtime.security() == NetworkTransportSecurity.WSS;
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.tls.enabled", "", String.valueOf(tls), false, true, "Set ReSync Hub TLS");
        if (tls) {
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.tls.key-store", "", "network/hub.p12", false, true, "Set ReSync Hub Identity");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.tls.trust-store", "", "network/ca.p12", false, true, "Set ReSync Hub Trust");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.tls.key-store-password-env", "", "RESYNC_HUB_KEYSTORE_PASSWORD", false, true, "Set ReSync Hub Key Password Source");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "hub.tls.trust-store-password-env", "", "RESYNC_HUB_TRUSTSTORE_PASSWORD", false, true, "Set ReSync Hub Trust Password Source");
        }
        List<NetworkMember> nodes = network.members().stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).toList();
        String operatorNodeId = NetworkRuntimeIdentity.operatorNodeId(network.networkId());
        List<String> nodeIds = new ArrayList<>();
        nodeIds.add(operatorNodeId);
        nodes.stream().map(NetworkMember::nodeId).forEach(nodeIds::add);
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "nodes", "", String.join(",", nodeIds), false, true, "Set ReSync Network Nodes");
        NetworkEnrollment operatorEnrollment = enrollment(network, operatorNodeId, secrets, enrollments, issues);
        String operatorPrefix = "node." + operatorNodeId + ".";
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, operatorPrefix + "display-name", "", "Remotely", false, true, "Set ReSync Operator Name");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, operatorPrefix + "role", "", "OPERATOR", false, true, "Set ReSync Operator Role");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, operatorPrefix + "capabilities", "", "observe,routing,operate,command,broadcast,state-admin,events,editors", false, true, "Set ReSync Operator Capabilities");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, operatorPrefix + "enrollment-token-hash", "", operatorEnrollment.hash(), true, true, "Set ReSync Operator Enrollment Hash");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, operatorPrefix + "enrollment-expires-at", "", "0", false, true, "Set ReSync Operator Enrollment Expiry");
        for (NetworkMember member : nodes) {
            NetworkEnrollment enrollment = enrollment(network, member.nodeId(), secrets, enrollments, issues);
            String prefix = "node." + member.nodeId() + ".";
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "display-name", "", member.routeName(), false, true, "Set ReSync Node Name");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "role", "", "BACKEND", false, true, "Set ReSync Node Role");
            SyncRealm transferRealm = stateRealm(network, member);
            List<String> capabilities = new ArrayList<>(List.of("presence", "observe", "variables", "events", "transfer", "operate", "command", "broadcast"));
            if (transferRealm != null) {
                capabilities.add("state:" + transferRealm.id());
            }
            if (network.featureEnabled(NetworkDefinition.FEATURE_SHARED_RESOURCES) || network.featureEnabled(NetworkDefinition.FEATURE_SHARED_CHAT) || pathSyncEnabled(network, member)) {
                capabilities.add("resources");
            }
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "capabilities", "", String.join(",", capabilities), false, true, "Set ReSync Node Capabilities");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "enrollment-token-hash", "", enrollment.hash(), true, true, "Set ReSync Enrollment Hash");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "enrollment-expires-at", "", "0", false, true, "Set ReSync Enrollment Expiry");
        }
        List<NetworkMember> routes = network.members().stream().filter(member -> !member.isProxy()).toList();
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "routes", "", routes.stream().map(NetworkMember::routeName).collect(Collectors.joining(",")), false, true, "Set ReSync Runtime Routes");
        for (NetworkMember member : routes) {
            String prefix = "route." + member.routeName() + ".";
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "node-id", "", member.nodeId(), false, true, "Set ReSync Route Node");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "address", "", member.address(), false, true, "Set ReSync Route Address");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "port", "", String.valueOf(member.port()), false, true, "Set ReSync Route Port");
        }
        String maintenanceRoute = fallbackRoutes(network).stream().findFirst().orElse("");
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "maintenance-route", "", maintenanceRoute, false, true, "Set ReSync Maintenance Route");
        planRoutingGroups(network.routingGroups(), proxy, mutations);
    }

    static void planRoutingGroups(List<RoutingGroup> groups, NetworkServerDescriptor proxy, List<NetworkConfigMutation> mutations) {
        String path = "plugins/resyncvelocity/network.properties";
        add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, "routing.groups", "", groups.stream().map(RoutingGroup::id).collect(Collectors.joining(",")), false, true, "Set Join Rules");
        for (RoutingGroup group : groups) {
            String prefix = "routing.group." + group.id() + ".";
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "name", "", group.name(), false, true, "Name Join Rule");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "strategy", "", group.strategy().name(), false, true, "Set Join Strategy");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "nodes", "", String.join(",", group.nodeIds()), false, true, "Set Join Servers");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "fallback", "", group.fallbackGroupId(), false, true, "Set Join Fallback");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "forced-hosts", "", group.forcedHosts().stream().sorted().collect(Collectors.joining(",")), false, true, "Set Join Addresses");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "permission", "", group.permission(), false, true, "Set Join Permission");
            add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "weights", "", group.weights().keySet().stream().sorted().collect(Collectors.joining(",")), false, true, "Set Join Priorities");
            for (String nodeId : group.weights().keySet()) {
                add(mutations, proxy, path, ConfigurationFormat.PROPERTIES, prefix + "weight." + nodeId, "", String.valueOf(group.weights().getOrDefault(nodeId, 1)), false, true, "Set Join Priority");
            }
        }
    }

    private void planBackend(NetworkDefinition network, NetworkServerDescriptor backend, NetworkMember member, NetworkMember proxyMember, String forwardingSecret, NetworkSecrets secrets, Map<String, NetworkEnrollment> enrollments, List<NetworkConfigMutation> mutations, List<NetworkValidationIssue> issues) {
        boolean runtimeEnabled = network.runtime().enabled() && member.resyncEnabled();
        add(mutations, backend, "server.properties", ConfigurationFormat.PROPERTIES, "server-port", backend.property("server-port", "25565"), String.valueOf(member.port()), false, true, "Set Backend Port");
        add(mutations, backend, "server.properties", ConfigurationFormat.PROPERTIES, "online-mode", backend.property("online-mode", "true"), "false", false, true, "Delegate Authentication To Proxy");
        String bind = backend.property("server-ip", "");
        if (NetworkRuntimePolicy.sharesLoopback(proxyMember, member)) {
            add(mutations, backend, "server.properties", ConfigurationFormat.PROPERTIES, "server-ip", bind, "127.0.0.1", false, true, "Restrict Backend To Loopback");
        } else if (bind.equals("127.0.0.1") || bind.equals("::1") || bind.equalsIgnoreCase("localhost")) {
            add(mutations, backend, "server.properties", ConfigurationFormat.PROPERTIES, "server-ip", bind, "", false, true, "Repair Backend Address For Proxy Access");
        }
        if (network.forwarding().mode() == ForwardingMode.MODERN) {
            planModernForwarding(network, backend, forwardingSecret, mutations, issues);
        }
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.config-version", "", "1", false, true, "Set ReSync Settings Version");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.id", "", network.networkId(), false, true, "Set ReSync Network");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.node-id", "", member.nodeId(), false, true, "Set ReSync Node");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.display-name", "", member.routeName(), false, true, "Set ReSync Node Name");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.enabled", "", String.valueOf(runtimeEnabled), false, true, "Set ReSync Network Runtime");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.chat.enabled", "", String.valueOf(runtimeEnabled && network.featureEnabled(NetworkDefinition.FEATURE_SHARED_CHAT)), false, true, "Set Shared Chat");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.chat.channel-mode", "", network.sharedDataPolicy().chatChannelMode().name(), false, true, "Set Shared Chat Channels");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.chat.channels", "", String.join(",", network.sharedDataPolicy().chatChannels()), false, true, "Set Shared Chat Channel List");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.chat.retention-millis", "", String.valueOf(network.sharedDataPolicy().chatRetentionMillis()), false, true, "Set Shared Chat Retention");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.resources.enabled", "", String.valueOf(runtimeEnabled && network.featureEnabled(NetworkDefinition.FEATURE_SHARED_RESOURCES)), false, true, "Set Shared Resources");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.resources.type-mode", "", network.sharedDataPolicy().resourceTypeMode().name(), false, true, "Set Shared Resource Types");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.resources.types", "", String.join(",", network.sharedDataPolicy().resourceTypes()), false, true, "Set Shared Resource Type List");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.resources.conflict-policy", "", network.sharedDataPolicy().resourceConflictPolicy().name(), false, true, "Set Shared Resource Conflicts");
        remove(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.paths.enabled", false, true, "Remove Legacy Path Sync");
        remove(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.paths.entries", false, true, "Remove Legacy Path Sync Paths");
        remove(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.paths.conflict-policy", false, true, "Remove Legacy Path Sync Conflicts");
        add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.path-sync.ids", "", network.sharedDataPolicy().pathSyncs().stream().map(NetworkPathSync::id).collect(Collectors.joining(",")), false, true, "Set Path Sync Entries");
        for (NetworkPathSync sync : network.sharedDataPolicy().pathSyncs()) {
            String prefix = "network.path-sync." + sync.id() + ".";
            boolean enabled = runtimeEnabled && network.featureEnabled(NetworkDefinition.FEATURE_PATH_SYNC) && sync.enabled() && sync.nodeIds().contains(member.nodeId());
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, prefix + "enabled", "", String.valueOf(enabled), false, true, "Set " + sync.name());
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, prefix + "name", "", sync.name(), false, true, "Name " + sync.name());
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, prefix + "paths", "", String.join(",", sync.paths()), false, true, "Set " + sync.name() + " Files");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, prefix + "conflict-policy", "", sync.conflictPolicy().name(), false, true, "Set " + sync.name() + " Conflicts");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, prefix + "command-count", "", String.valueOf(sync.commands().size()), false, true, "Set " + sync.name() + " Commands");
            for (int index = 0; index < sync.commands().size(); index++) {
                add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, prefix + "command." + index, "", sync.commands().get(index), false, true, "Set " + sync.name() + " Command");
            }
        }
        if (runtimeEnabled) {
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.hub-url", "", network.runtime().hubUrl(), false, true, "Set ReSync Hub");
            NetworkEnrollment enrollment = enrollment(network, member.nodeId(), secrets, enrollments, issues);
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.enrollment-token", "", enrollment.token(), true, true, "Set ReSync Enrollment Token");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.credential-file", "", "network/node.credential", false, true, "Set ReSync Credential File");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.capacity", "", String.valueOf(member.capacity()), false, true, "Set ReSync Capacity");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.maximum-frame-bytes", "", "1048576", false, true, "Set ReSync Frame Limit");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.maximum-payload-bytes", "", String.valueOf(network.sharedDataPolicy().maximumPayloadBytes()), false, true, "Set ReSync Payload Limit");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.heartbeat-interval-ticks", "", "100", false, true, "Set ReSync Heartbeat Interval");
            add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.reconnect-delay-ticks", "", "100", false, true, "Set ReSync Reconnect Delay");
            planTransferRealm(network, backend, member, mutations, issues);
            if (network.runtime().security() == NetworkTransportSecurity.WSS) {
                add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.tls.trust-store", "", "network/ca.p12", false, true, "Set ReSync Network Trust");
                add(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.tls.trust-store-password-env", "", "RESYNC_NETWORK_TRUSTSTORE_PASSWORD", false, true, "Set ReSync Trust Password Source");
            } else {
                remove(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.tls.trust-store", false, true, "Remove Unused Network Trust Store");
                remove(mutations, backend, "plugins/.resync-network.properties", ConfigurationFormat.PROPERTIES, "network.tls.trust-store-password-env", false, true, "Remove Unused Network Trust Password Source");
            }
        }
    }

    private void planTransferRealm(NetworkDefinition network, NetworkServerDescriptor backend, NetworkMember member, List<NetworkConfigMutation> mutations, List<NetworkValidationIssue> issues) {
        SyncRealm realm = stateRealm(network, member);
        String path = "plugins/.resync-network.properties";
        if (realm == null) {
            add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.profile", "", "PRESENCE_ONLY", false, true, "Disable Player State Transfer");
            add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.realm", "", "", false, true, "Clear Player State Realm");
            add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.location-policy", "", "NEVER", false, true, "Disable Player Location Transfer");
            add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.persistent-data-namespaces", "", "", false, true, "Clear Persistent Data Allowlist");
            return;
        }
        Set<SyncDataFamily> unsupported = EnumSet.copyOf(realm.dataFamilies());
        unsupported.removeAll(SUPPORTED_TRANSFER_FAMILIES);
        if (!unsupported.isEmpty()) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "realm.family.runtime.unsupported", realm.id(), "This synchronization realm includes state families that the installed ReSync runtime cannot apply yet"));
        }
        add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.profile", "", "CUSTOM", false, true, "Set Player State Profile");
        add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.realm", "", realm.id(), false, true, "Set Player State Realm");
        addTransferFamily(mutations, backend, path, "inventory", realm.dataFamilies().contains(SyncDataFamily.INVENTORY));
        addTransferFamily(mutations, backend, path, "ender-chest", realm.dataFamilies().contains(SyncDataFamily.ENDER_CHEST));
        addTransferFamily(mutations, backend, path, "experience", realm.dataFamilies().contains(SyncDataFamily.EXPERIENCE));
        addTransferFamily(mutations, backend, path, "vitals", realm.dataFamilies().contains(SyncDataFamily.VITALS));
        addTransferFamily(mutations, backend, path, "effects", realm.dataFamilies().contains(SyncDataFamily.EFFECTS));
        addTransferFamily(mutations, backend, path, "movement", realm.dataFamilies().contains(SyncDataFamily.PLAYER_STATE));
        addTransferFamily(mutations, backend, path, "attributes", realm.dataFamilies().contains(SyncDataFamily.PLAYER_STATE));
        addTransferFamily(mutations, backend, path, "advancements", realm.dataFamilies().contains(SyncDataFamily.ADVANCEMENTS));
        addTransferFamily(mutations, backend, path, "recipes", realm.dataFamilies().contains(SyncDataFamily.RECIPES));
        addTransferFamily(mutations, backend, path, "statistics", realm.dataFamilies().contains(SyncDataFamily.STATISTICS));
        addTransferFamily(mutations, backend, path, "persistent-data", realm.dataFamilies().contains(SyncDataFamily.PERSISTENT_DATA));
        add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.location-policy", "", realm.dataFamilies().contains(SyncDataFamily.LOCATION) ? realm.locationPolicy().name() : SyncLocationPolicy.NEVER.name(), false, true, "Set Player Location Policy");
        add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.persistent-data-namespaces", "", realm.dataFamilies().contains(SyncDataFamily.PERSISTENT_DATA) ? realm.persistentDataNamespaces().stream().sorted().collect(Collectors.joining(",")) : "", false, true, "Set Persistent Data Allowlist");
    }

    private void addTransferFamily(List<NetworkConfigMutation> mutations, NetworkServerDescriptor backend, String path, String family, boolean enabled) {
        String name = switch (family) {
            case "ender-chest" -> "Ender Chest";
            default -> Character.toUpperCase(family.charAt(0)) + family.substring(1);
        };
        add(mutations, backend, path, ConfigurationFormat.PROPERTIES, "network.transfer.family." + family, "", String.valueOf(enabled), false, true, "Set " + name + " Transfer");
    }

    private SyncRealm stateRealm(NetworkDefinition network, NetworkMember member) {
        return network.syncRealms().stream().filter(realm -> realm.nodeIds().contains(member.nodeId()) && realm.dataFamilies().stream().anyMatch(family -> family != SyncDataFamily.PRESENCE)).findFirst().orElse(null);
    }

    private void planModernForwarding(NetworkDefinition network, NetworkServerDescriptor backend, String forwardingSecret, List<NetworkConfigMutation> mutations, List<NetworkValidationIssue> issues) {
        switch (backend.forwardingAdapter()) {
            case PAPER -> {
                add(mutations, backend, "spigot.yml", ConfigurationFormat.YAML, "settings.bungeecord", "", "false", false, true, "Disable Legacy Forwarding");
                if (usesLegacyPaperConfiguration(backend.version())) {
                    add(mutations, backend, "paper.yml", ConfigurationFormat.YAML, "settings.velocity-support.enabled", "", "true", false, true, "Enable Modern Forwarding");
                    add(mutations, backend, "paper.yml", ConfigurationFormat.YAML, "settings.velocity-support.online-mode", "", String.valueOf(network.forwarding().proxyOnlineMode()), false, true, "Match Proxy Online Mode");
                    add(mutations, backend, "paper.yml", ConfigurationFormat.YAML, "settings.velocity-support.secret", "", forwardingSecret, true, true, "Set Forwarding Secret");
                } else {
                    add(mutations, backend, "config/paper-global.yml", ConfigurationFormat.YAML, "proxies.velocity.enabled", "", "true", false, true, "Enable Modern Forwarding");
                    add(mutations, backend, "config/paper-global.yml", ConfigurationFormat.YAML, "proxies.velocity.online-mode", "", String.valueOf(network.forwarding().proxyOnlineMode()), false, true, "Match Proxy Online Mode");
                    add(mutations, backend, "config/paper-global.yml", ConfigurationFormat.YAML, "proxies.velocity.secret", "", forwardingSecret, true, true, "Set Forwarding Secret");
                }
            }
            case FABRIC_PROXY_LITE -> add(mutations, backend, "config/FabricProxy-Lite.toml", ConfigurationFormat.TOML, "secret", "", quote(forwardingSecret), true, true, "Set Fabric Forwarding Secret");
            case PROXY_COMPATIBLE_FORGE -> {
                add(mutations, backend, "config/proxy-compatible-forge.toml", ConfigurationFormat.TOML, "forwarding.enabled", "", "true", false, true, "Enable Forge Forwarding");
                add(mutations, backend, "config/proxy-compatible-forge.toml", ConfigurationFormat.TOML, "forwarding.mode", "", quote("MODERN"), false, true, "Set Forge Forwarding Mode");
                add(mutations, backend, "config/proxy-compatible-forge.toml", ConfigurationFormat.TOML, "forwarding.secret", "", quote(forwardingSecret), true, true, "Set Forge Forwarding Secret");
            }
            case UNSUPPORTED -> issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "backend.forwarding.adapter.unavailable", backend.serverId(), "Backend does not have a supported modern-forwarding configuration adapter"));
        }
    }

    private static void add(List<NetworkConfigMutation> mutations, NetworkServerDescriptor server, String path, ConfigurationFormat format, String key, String currentValue, String desiredValue, boolean sensitive, boolean restartRequired, String description) {
        mutations.add(new NetworkConfigMutation(server.serverId(), path, format, key, currentValue, desiredValue, sensitive, restartRequired, description));
    }

    private void remove(List<NetworkConfigMutation> mutations, NetworkServerDescriptor server, String path, ConfigurationFormat format, String key, boolean sensitive, boolean restartRequired, String description) {
        mutations.add(new NetworkConfigMutation(server.serverId(), path, format, key, "", "", sensitive, restartRequired, description, NetworkMutationAction.REMOVE));
    }

    private boolean pathSyncEnabled(NetworkDefinition network, NetworkMember member) {
        return network.featureEnabled(NetworkDefinition.FEATURE_PATH_SYNC) && network.sharedDataPolicy().pathSyncs().stream().anyMatch(sync -> sync.enabled() && sync.nodeIds().contains(member.nodeId()));
    }

    private NetworkEnrollment enrollment(NetworkDefinition network, String nodeId, NetworkSecrets secrets, Map<String, NetworkEnrollment> enrollments, List<NetworkValidationIssue> issues) {
        NetworkEnrollment enrollment = enrollments.get(nodeId);
        if (enrollment != null) {
            return enrollment;
        }
        enrollment = secrets.enrollment(network.networkId(), nodeId);
        if (enrollment == null || enrollment.token().isBlank() || enrollment.hash().isBlank()) {
            issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.ERROR, "runtime.enrollment.unavailable", nodeId, "ReSync enrollment credentials are unavailable"));
            enrollment = new NetworkEnrollment("", "");
        }
        enrollments.put(nodeId, enrollment);
        return enrollment;
    }

    private static String routeForNode(NetworkDefinition network, String nodeId) {
        return network.members().stream().filter(member -> member.nodeId().equals(nodeId)).map(NetworkMember::routeName).findFirst().orElse("");
    }

    static List<String> fallbackRoutes(NetworkDefinition network) {
        List<String> routes = network.routingGroups().stream().filter(group -> group.id().equals("fallback")).flatMap(group -> group.nodeIds().stream()).map(nodeId -> routeForNode(network, nodeId)).filter(value -> !value.isBlank()).toList();
        if (!routes.isEmpty()) return routes;
        return network.members().stream().filter(member -> !member.isProxy() && member.routeName().equals("lobby")).map(NetworkMember::routeName).findFirst()
                .map(List::of).orElseGet(() -> network.members().stream().filter(member -> !member.isProxy()).map(NetworkMember::routeName).findFirst().map(List::of).orElse(List.of()));
    }

    private String forwardingValue(ForwardingMode mode) {
        return switch (mode) {
            case MODERN -> "modern";
            case BUNGEEGUARD, LEGACY -> "legacy";
            case NONE -> "none";
        };
    }

    private boolean usesLegacyPaperConfiguration(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        Matcher matcher = Pattern.compile("1\\.(\\d+)(?:\\.(\\d+))?").matcher(version);
        if (!matcher.find()) {
            return false;
        }
        int minor = Integer.parseInt(matcher.group(1));
        int patch = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        return minor < 18 || (minor == 18 && patch <= 2);
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String tomlArray(List<String> values) {
        return values.stream().map(this::quote).collect(Collectors.joining(", ", "[", "]"));
    }

    private String tomlSection(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> yamlKey(entry.getKey()) + ": " + entry.getValue()).collect(Collectors.joining(", ", "{", "}"));
    }

    private String yamlKey(String value) {
        return value.matches("[A-Za-z0-9_-]+") ? value : "'" + value.replace("'", "''") + "'";
    }

}
