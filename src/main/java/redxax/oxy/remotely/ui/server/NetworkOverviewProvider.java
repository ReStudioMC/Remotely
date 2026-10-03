package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkDiscoveryResult;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkLifecycleJob;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkPreflightReport;
import redxax.oxy.remotely.network.NetworkRuntimeNodeStatus;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.network.NetworkSharedDataPolicy;
import redxax.oxy.remotely.network.NetworkIncident;
import redxax.oxy.remotely.network.NetworkValidationIssue;
import redxax.oxy.remotely.network.RoutingGroup;
import redxax.oxy.remotely.network.SyncRealm;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface NetworkOverviewProvider {
    record NetworkCapability(String operation, boolean supported, String reason, String transport) {
        public NetworkCapability {
            operation = operation == null ? "" : operation.trim();
            reason = reason == null ? "" : reason.trim();
            transport = transport == null ? "" : transport.trim();
        }

        public static NetworkCapability supported(String operation, String transport) {
            return new NetworkCapability(operation, true, "", transport);
        }

        public static NetworkCapability unavailable(String operation, String reason, String transport) {
            return new NetworkCapability(operation, false, reason, transport);
        }
    }

    record ServerView(String id, String name, boolean proxy, boolean managed, String hostScope, String address,
                      int port, String state, Identifier icon) {
        public ServerView(String id, String name, boolean proxy, boolean managed, String hostScope, String address,
                          int port, String state, String icon) {
            this(id, name, proxy, managed, hostScope, address, port, state,
                    icon == null || icon.isBlank() ? null : icon.contains(":") ? Identifier.of(icon) : Identifier.icon(icon));
        }

        public ServerView {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            hostScope = hostScope == null ? "" : hostScope;
            address = address == null ? "" : address;
            state = state == null ? "" : state;
        }
    }

    record OverviewState(NetworkDefinition network, List<ServerView> servers, NetworkDiscoveryResult discovery,
                         NetworkRuntimeSnapshot runtime, List<NetworkIncident> incidents,
                         List<NetworkLifecycleJob> lifecycleJobs,
                         List<NetworkJob> jobs, Map<String, Integer> transferFailureHeat,
                         Map<String, Boolean> reSyncInstalled, Map<String, NetworkCapability> capabilities) {
        public OverviewState(NetworkDefinition network, List<ServerView> servers, NetworkDiscoveryResult discovery,
                             NetworkRuntimeSnapshot runtime, List<NetworkIncident> incidents,
                             List<NetworkLifecycleJob> lifecycleJobs,
                             List<NetworkJob> jobs, Map<String, Integer> transferFailureHeat,
                             Map<String, Boolean> reSyncInstalled) {
            this(network, servers, discovery, runtime, incidents, lifecycleJobs, jobs, transferFailureHeat,
                    reSyncInstalled, Map.of());
        }

        public OverviewState {
            servers = servers == null ? List.of() : List.copyOf(servers);
            incidents = incidents == null ? List.of() : List.copyOf(incidents);
            lifecycleJobs = lifecycleJobs == null ? List.of() : List.copyOf(lifecycleJobs);
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            transferFailureHeat = transferFailureHeat == null ? Map.of() : Map.copyOf(transferFailureHeat);
            reSyncInstalled = reSyncInstalled == null ? Map.of() : Map.copyOf(reSyncInstalled);
            capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        }
    }

    record SaveRequest(String name, List<RoutingGroup> routingGroups, List<SyncRealm> syncRealms,
                       Map<String, Boolean> features, NetworkSharedDataPolicy sharedDataPolicy) {
        public SaveRequest {
            name = name == null ? "" : name;
            routingGroups = routingGroups == null ? List.of() : List.copyOf(routingGroups);
            syncRealms = syncRealms == null ? List.of() : List.copyOf(syncRealms);
            features = features == null ? Map.of() : Map.copyOf(features);
            sharedDataPolicy = sharedDataPolicy == null ? NetworkSharedDataPolicy.defaults() : sharedDataPolicy;
        }
    }

    record AttachRequest(String serverId, String joinRule, boolean installReSync) {
        public AttachRequest {
            serverId = serverId == null ? "" : serverId;
            joinRule = joinRule == null ? "" : joinRule;
        }
    }

    record ExternalAttachRequest(String name, String address, int port, int capacity, String joinRule) {
        public ExternalAttachRequest {
            name = name == null ? "" : name;
            address = address == null ? "" : address;
            joinRule = joinRule == null ? "" : joinRule;
        }
    }

    record LifecycleJobView(String jobId, NetworkLifecycleOperation operation, String status, String message,
                            boolean resumable) {
        public LifecycleJobView {
            jobId = jobId == null ? "" : jobId;
            operation = operation == null ? NetworkLifecycleOperation.START : operation;
            status = status == null ? "" : status;
            message = message == null ? "" : message;
        }
    }

    record JobView(String jobId, String status, String message, boolean resumable, boolean rollbackable,
                   boolean restartRequired, List<NetworkValidationIssue> issues) {
        public JobView {
            jobId = jobId == null ? "" : jobId;
            status = status == null ? "" : status;
            message = message == null ? "" : message;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    Async<OverviewState> load(String networkId);

    default Async<OverviewState> loadForServer(String serverId) {
        return unavailable("Network Inventory Is Unavailable");
    }

    default Async<NetworkDefinition> save(String networkId, SaveRequest request) {
        return unavailable("Network Saving Is Unavailable");
    }

    default Async<NetworkLifecycleJob> lifecycle(String networkId, NetworkLifecycleOperation operation) {
        return unavailable("Network Lifecycle Is Unavailable");
    }

    default Async<NetworkLifecycleJob> memberLifecycle(String networkId, String memberId,
                                                        NetworkLifecycleOperation operation) {
        return unavailable("Server Lifecycle Is Unavailable");
    }

    default Async<NetworkJob> attach(String networkId, AttachRequest request) {
        return unavailable("Server Attachment Is Unavailable");
    }

    default Async<NetworkJob> attachExternal(String networkId, ExternalAttachRequest request) {
        return unavailable("External Server Attachment Is Unavailable");
    }

    default Async<NetworkJob> detach(String networkId, String memberId) {
        return unavailable("Server Detachment Is Unavailable");
    }

    default Async<NetworkJob> dissolve(String networkId) {
        return unavailable("Network Dissolution Is Unavailable");
    }

    default Async<NetworkPreflightReport> preflight(String networkId) {
        return unavailable("Network Checks Are Unavailable");
    }

    default Async<NetworkJob> rotateSecret(String networkId) {
        return unavailable("Connection Security Is Unavailable");
    }

    default Async<NetworkJob> reconcile(String networkId) {
        return unavailable("Network Repair Is Unavailable");
    }

    default Async<NetworkJob> resumeJob(String networkId, String jobId) {
        return unavailable("Network Recovery Is Unavailable");
    }

    default Async<NetworkJob> rollbackJob(String networkId, String jobId) {
        return unavailable("Network Rollback Is Unavailable");
    }

    default Async<NetworkLifecycleJob> resumeLifecycle(String networkId, String jobId) {
        return unavailable("Network Recovery Is Unavailable");
    }

    default Async<Void> runtimeNodeMode(String networkId, String nodeId, NetworkRuntimeNodeStatus status) {
        return unavailable("Runtime Controls Are Unavailable");
    }

    default Async<NetworkJob> installReSync(String networkId) {
        return unavailable("ReSync Installation Is Unavailable");
    }

    default Async<Void> executeProxyCommand(String networkId, String command) {
        return unavailable("Proxy Commands Are Unavailable");
    }

    default Async<Void> broadcastMessage(String networkId, String message) {
        return unavailable("Network Broadcasts Are Unavailable");
    }

    default Async<List<ServerView>> availableServers(String networkId) {
        return load(networkId).thenApply(state -> state.servers().stream().filter(server -> !server.proxy()).toList());
    }

    default void openServer(Screen current, String serverId) {
        unsupported("Server Opening Is Unavailable");
    }

    default void addListener(Consumer<OverviewState> listener) {
    }

    default void removeListener(Consumer<OverviewState> listener) {
    }

    default void addRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
    }

    default void removeRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
    }

    default boolean available() {
        return true;
    }

    default boolean openNative(Screen parent, RemotelyClient client, String networkId) {
        return false;
    }

    static NetworkOverviewProvider forClient(RemotelyClient client) {
        if (client == null || client.getHost() == null) {
            return unavailableProvider();
        }
        NetworkOverviewProvider apiProvider = client.getApiClient() == null ? null
                : client.getApiClient().networkOverviewProvider(client);
        if (apiProvider != null) {
            return apiProvider;
        }
        return client.getHost().serverScreenHost(client).networkOverviewProvider(client);
    }

    static NetworkOverviewProvider from(ServerScreenHost host) {
        return unavailableProvider();
    }

    static NetworkOverviewProvider unavailableProvider() {
        return UnavailableNetworkOverviewProvider.INSTANCE;
    }

    static <T> Async<T> unavailable(String message) {
        return Async.failed(new UnsupportedOperationException(message));
    }

    static void unsupported(String message) {
        throw new UnsupportedOperationException(message);
    }

    final class UnavailableNetworkOverviewProvider implements NetworkOverviewProvider {
        private static final UnavailableNetworkOverviewProvider INSTANCE = new UnavailableNetworkOverviewProvider();

        private UnavailableNetworkOverviewProvider() {
        }

        @Override
        public Async<OverviewState> load(String networkId) {
            return unavailable("Network Inventory Is Unavailable");
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}
