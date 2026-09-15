package redxax.oxy.remotely.network;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record NetworkDefinition(int schemaVersion, String networkId, String name, long revision, String proxyInstanceId, NetworkDesiredState desiredState, NetworkForwardingPolicy forwarding, List<NetworkEntryPoint> entryPoints, List<NetworkMember> members, List<RoutingGroup> routingGroups, List<SyncRealm> syncRealms, NetworkRuntimePolicy runtime, Map<String, Boolean> features, NetworkSharedDataPolicy sharedDataPolicy, long createdAt, long updatedAt) {
    public static final int CURRENT_SCHEMA_VERSION = 5;
    public static final String FEATURE_RUNTIME = "runtime";
    public static final String FEATURE_PRESENCE = "presence";
    public static final String FEATURE_SHARED_STATE = "sharedState";
    public static final String FEATURE_FLOW_EVENTS = "flowEvents";
    public static final String FEATURE_SHARED_CHAT = "sharedChat";
    public static final String FEATURE_SHARED_RESOURCES = "sharedResources";
    public static final String FEATURE_PATH_SYNC = "pathSync";

    public NetworkDefinition {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        networkId = normalize(networkId);
        name = normalize(name);
        revision = Math.max(1, revision);
        proxyInstanceId = normalize(proxyInstanceId);
        desiredState = desiredState == null ? NetworkDesiredState.STOPPED : desiredState;
        forwarding = forwarding == null ? NetworkForwardingPolicy.secureDefault("") : forwarding;
        entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
        members = members == null ? List.of() : List.copyOf(members);
        routingGroups = routingGroups == null ? List.of() : List.copyOf(routingGroups);
        syncRealms = syncRealms == null ? List.of() : List.copyOf(syncRealms);
        runtime = runtime == null ? NetworkRuntimePolicy.disabled() : runtime;
        features = features == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(features));
        sharedDataPolicy = sharedDataPolicy == null ? NetworkSharedDataPolicy.defaults() : sharedDataPolicy;
        long now = NetworkClock.SYSTEM.millis();
        createdAt = createdAt <= 0 ? now : createdAt;
        updatedAt = updatedAt <= 0 ? createdAt : updatedAt;
    }

    public NetworkDefinition(int schemaVersion, String networkId, String name, long revision, String proxyInstanceId, NetworkDesiredState desiredState, NetworkForwardingPolicy forwarding, List<NetworkEntryPoint> entryPoints, List<NetworkMember> members, List<RoutingGroup> routingGroups, List<SyncRealm> syncRealms, Map<String, Boolean> features, long createdAt, long updatedAt) {
        this(schemaVersion, networkId, name, revision, proxyInstanceId, desiredState, forwarding, entryPoints, members, routingGroups, syncRealms, NetworkRuntimePolicy.disabled(), features, NetworkSharedDataPolicy.defaults(), createdAt, updatedAt);
    }

    public NetworkDefinition(int schemaVersion, String networkId, String name, long revision, String proxyInstanceId, NetworkDesiredState desiredState, NetworkForwardingPolicy forwarding, List<NetworkEntryPoint> entryPoints, List<NetworkMember> members, List<RoutingGroup> routingGroups, List<SyncRealm> syncRealms, NetworkRuntimePolicy runtime, Map<String, Boolean> features, long createdAt, long updatedAt) {
        this(schemaVersion, networkId, name, revision, proxyInstanceId, desiredState, forwarding, entryPoints, members, routingGroups, syncRealms, runtime, features, NetworkSharedDataPolicy.defaults(), createdAt, updatedAt);
    }

    public static NetworkDefinition create(String name, String proxyInstanceId, NetworkForwardingPolicy forwarding, List<NetworkEntryPoint> entryPoints, List<NetworkMember> members) {
        return create(name, proxyInstanceId, forwarding, entryPoints, members, NetworkClock.SYSTEM);
    }

    public static NetworkDefinition create(String name, String proxyInstanceId, NetworkForwardingPolicy forwarding, List<NetworkEntryPoint> entryPoints, List<NetworkMember> members, NetworkClock clock) {
        long now = Objects.requireNonNull(clock, "clock").millis();
        return new NetworkDefinition(CURRENT_SCHEMA_VERSION, UUID.randomUUID().toString(), name, 1, proxyInstanceId, NetworkDesiredState.STOPPED, forwarding, entryPoints, members, List.of(), List.of(), defaultRuntime(proxyInstanceId, entryPoints, members), defaultFeatures(), NetworkSharedDataPolicy.defaults(), now, now);
    }

    public NetworkDefinition migrated() {
        if (schemaVersion >= CURRENT_SCHEMA_VERSION) {
            return this;
        }
        boolean runtimeRequired = members.stream().anyMatch(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled());
        NetworkRuntimePolicy migratedRuntime = runtime.enabled() || !runtimeRequired ? runtime : defaultRuntime(proxyInstanceId, entryPoints, members);
        Map<String, Boolean> migratedFeatures = new LinkedHashMap<>(defaultFeatures());
        migratedFeatures.putAll(features);
        return new NetworkDefinition(CURRENT_SCHEMA_VERSION, networkId, name, revision, proxyInstanceId, desiredState, forwarding, entryPoints, members, routingGroups, syncRealms, migratedRuntime, migratedFeatures, sharedDataPolicy, createdAt, updatedAt);
    }

    public NetworkDefinition nextRevision(List<NetworkMember> updatedMembers, List<RoutingGroup> updatedRoutingGroups, List<SyncRealm> updatedSyncRealms, NetworkDesiredState updatedDesiredState) {
        return nextRevision(updatedMembers, updatedRoutingGroups, updatedSyncRealms, updatedDesiredState, NetworkClock.SYSTEM);
    }

    public NetworkDefinition nextRevision(List<NetworkMember> updatedMembers, List<RoutingGroup> updatedRoutingGroups, List<SyncRealm> updatedSyncRealms, NetworkDesiredState updatedDesiredState, NetworkClock clock) {
        return new NetworkDefinition(schemaVersion, networkId, name, revision + 1, proxyInstanceId, updatedDesiredState, forwarding, entryPoints, updatedMembers, updatedRoutingGroups, updatedSyncRealms, runtime, features, sharedDataPolicy, createdAt, Objects.requireNonNull(clock, "clock").millis());
    }

    public NetworkDefinition renamed(String updatedName) {
        return renamed(updatedName, NetworkClock.SYSTEM);
    }

    public NetworkDefinition renamed(String updatedName, NetworkClock clock) {
        return new NetworkDefinition(schemaVersion, networkId, updatedName, revision + 1, proxyInstanceId, desiredState, forwarding, entryPoints, members, routingGroups, syncRealms, runtime, features, sharedDataPolicy, createdAt, Objects.requireNonNull(clock, "clock").millis());
    }

    public NetworkDefinition withForwarding(NetworkForwardingPolicy updatedForwarding) {
        return withForwarding(updatedForwarding, NetworkClock.SYSTEM);
    }

    public NetworkDefinition withForwarding(NetworkForwardingPolicy updatedForwarding, NetworkClock clock) {
        return new NetworkDefinition(schemaVersion, networkId, name, revision + 1, proxyInstanceId, desiredState, updatedForwarding, entryPoints, members, routingGroups, syncRealms, runtime, features, sharedDataPolicy, createdAt, Objects.requireNonNull(clock, "clock").millis());
    }

    public NetworkDefinition withSharedData(List<SyncRealm> updatedRealms, Map<String, Boolean> updatedFeatures) {
        return withSharedData(updatedRealms, updatedFeatures, sharedDataPolicy);
    }

    public NetworkDefinition withSharedData(List<SyncRealm> updatedRealms, Map<String, Boolean> updatedFeatures, NetworkSharedDataPolicy updatedPolicy) {
        return withSharedData(updatedRealms, updatedFeatures, updatedPolicy, NetworkClock.SYSTEM);
    }

    public NetworkDefinition withSharedData(List<SyncRealm> updatedRealms, Map<String, Boolean> updatedFeatures, NetworkSharedDataPolicy updatedPolicy, NetworkClock clock) {
        return new NetworkDefinition(schemaVersion, networkId, name, revision + 1, proxyInstanceId, desiredState, forwarding, entryPoints, members, routingGroups, updatedRealms, runtime, updatedFeatures, updatedPolicy, createdAt, Objects.requireNonNull(clock, "clock").millis());
    }

    public boolean featureEnabled(String feature) {
        return features.getOrDefault(feature, defaultFeatures().getOrDefault(feature, false));
    }

    public NetworkMember proxyMember() {
        return members.stream().filter(member -> member.instanceId().equals(proxyInstanceId)).findFirst().orElse(null);
    }

    private static Map<String, Boolean> defaultFeatures() {
        return Map.of(
            FEATURE_RUNTIME, true,
            FEATURE_PRESENCE, true,
            FEATURE_SHARED_STATE, false,
            FEATURE_FLOW_EVENTS, true,
            FEATURE_SHARED_CHAT, true,
            FEATURE_SHARED_RESOURCES, true,
            FEATURE_PATH_SYNC, false
        );
    }

    private static NetworkRuntimePolicy defaultRuntime(String proxyInstanceId, List<NetworkEntryPoint> entryPoints, List<NetworkMember> members) {
        List<NetworkMember> safeMembers = members == null ? List.of() : members;
        String normalizedProxyId = normalize(proxyInstanceId);
        NetworkMember proxy = safeMembers.stream().filter(member -> member.instanceId().equals(normalizedProxyId)).findFirst().orElse(null);
        List<NetworkMember> runtimeMembers = safeMembers.stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).toList();
        if (proxy == null || runtimeMembers.isEmpty()) {
            return NetworkRuntimePolicy.disabled();
        }
        Set<Integer> occupied = new LinkedHashSet<>();
        safeMembers.stream().filter(member -> member.hostScope().equals(proxy.hostScope())).map(NetworkMember::port).forEach(occupied::add);
        if (entryPoints != null) {
            entryPoints.stream().map(NetworkEntryPoint::port).forEach(occupied::add);
        }
        int port = 12442;
        while (occupied.contains(port) && port <= 12999) {
            port++;
        }
        if (port > 12999) {
            port = 12000;
            while (occupied.contains(port) && port < 12442) {
                port++;
            }
        }
        if (occupied.contains(port)) {
            return NetworkRuntimePolicy.disabled();
        }
        boolean loopback = runtimeMembers.stream().allMatch(member -> member.hostScope().equals(proxy.hostScope()));
        return new NetworkRuntimePolicy(true, loopback ? "127.0.0.1" : proxy.address(), port, loopback ? NetworkTransportSecurity.LOOPBACK : NetworkTransportSecurity.WSS, loopback);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
