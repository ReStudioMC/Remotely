package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkEditorConnection;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkManager;
import redxax.oxy.remotely.network.NetworkDiscoveryResult;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkJobStatus;
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
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

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
                      int port, String state, Identifier icon, String serverId) {
        public ServerView(String id, String name, boolean proxy, boolean managed, String hostScope, String address,
                          int port, String state, Identifier icon) {
            this(id, name, proxy, managed, hostScope, address, port, state, icon, id);
        }

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
            serverId = serverId == null ? "" : serverId;
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

    record AttachRequest(String serverId, String joinRule, boolean installReSync, NetworkMemberSource source, String name) {
        public AttachRequest(String serverId, String joinRule, boolean installReSync) {
            this(serverId, joinRule, installReSync, null, "");
        }

        public AttachRequest {
            serverId = serverId == null ? "" : serverId;
            joinRule = joinRule == null ? "" : joinRule;
            name = name == null ? "" : name.trim();
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

    default void addServerStateListener(BiConsumer<String, String> listener) { }

    default void removeServerStateListener(BiConsumer<String, String> listener) { }

    Async<OverviewState> load(String networkId);

    default String inventoryNetworkId(String networkId) {
        return networkId;
    }

    default BooleanSupplier mutationAdmission() {
        return () -> true;
    }

    static BooleanSupplier mutationAdmission(RemotelyClient client) {
        if (client == null || client.getHost() == null) return () -> false;
        var application = client.getHost();
        var api = client.getApiClient();
        ServerScreenHost host = application.serverScreenHost(client);
        ServerScreenHost.AccountIdentity account = host.accountIdentity();
        return () -> {
            if (client.getHost() != application || client.getApiClient() != api || application.serverScreenHost(client) != host) return false;
            ServerScreenHost.AccountIdentity current = host.accountIdentity();
            return account.authenticated() == current.authenticated() && (!account.authenticated()
                    || (!account.subjectId().isBlank() ? account.subjectId().equals(current.subjectId()) : account.displayName().equals(current.displayName())));
        };
    }

    default Async<OverviewState> loadForServer(String serverId) {
        return unavailable("Network Inventory Is Unavailable");
    }

    default Async<NetworkDefinition> save(String networkId, SaveRequest request) {
        return unavailable("Network Saving Is Unavailable");
    }

    default Async<NetworkLifecycleJob> lifecycle(String networkId, NetworkLifecycleOperation operation) {
        return unavailable("Network Lifecycle Is Unavailable");
    }

    default Async<NetworkLifecycleJob> lifecycle(String networkId, NetworkLifecycleOperation operation,
                                                Consumer<NetworkOperationStatus> progress) {
        return lifecycle(networkId, operation);
    }

    default Async<NetworkLifecycleJob> memberLifecycle(String networkId, String memberId,
                                                        NetworkLifecycleOperation operation) {
        return unavailable("Server Lifecycle Is Unavailable");
    }

    default Async<NetworkLifecycleJob> memberLifecycle(String networkId, String memberId,
                                                       NetworkLifecycleOperation operation, Consumer<NetworkOperationStatus> progress) {
        return memberLifecycle(networkId, memberId, operation);
    }

    default Async<NetworkJob> attach(String networkId, AttachRequest request) {
        return unavailable("Server Attachment Is Unavailable");
    }

    default Async<Void> addServer(String networkId, AttachRequest request, Consumer<NetworkOperationStatus> progress) {
        return attach(networkId, request).thenApply(job -> {
            if (job == null || job.status() != NetworkJobStatus.SUCCEEDED) {
                throw new IllegalStateException(job == null ? "Server Attachment Did Not Finish" : job.message());
            }
            return null;
        });
    }

    default Async<NetworkCreationContext> serverCreationContext(String networkId) {
        return unavailable("Server Creation Is Unavailable");
    }

    default boolean reactorNetwork() {
        return false;
    }

    default ServerScreenHost.ActionAvailability serverDraftAvailability(String networkId, ServerConfigurationTarget target, NetworkMemberSource.Draft source) {
        return ServerScreenHost.ActionAvailability.disabled("Server Creation Is Unavailable For This Network");
    }

    default Async<Void> validateServerAddition(String networkId) {
        return load(networkId).thenApply(state -> {
            NetworkCapability capability = state.capabilities().get("membership");
            if (capability != null && !capability.supported()) throw new IllegalStateException(capability.reason());
            if (state.servers().stream().anyMatch(server -> server.managed() && state.network().members().stream()
                    .anyMatch(member -> member.instanceId().equals(server.id())) && !"STOPPED".equalsIgnoreCase(server.state())
                    && !"OFFLINE".equalsIgnoreCase(server.state()) && !"CRASHED".equalsIgnoreCase(server.state()))) {
                throw new IllegalStateException("Stop Network Servers Before Adding A Server");
            }
            return null;
        });
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

    default Async<String> connectionKey(String networkId) {
        return unavailable("Open The Proxy's forwarding.secret File To Configure An External Backend");
    }

    default NetworkCapability connectionKeyCapability() {
        return NetworkCapability.unavailable("connectionKey", "Open The Proxy's forwarding.secret File To Configure An External Backend", "");
    }

    default BooleanSupplier connectionKeyAdmission() {
        return () -> false;
    }

    default void addAuthStateListener(Runnable listener) { }

    default void removeAuthStateListener(Runnable listener) { }

    default Async<NetworkJob> reconcile(String networkId) {
        return unavailable("Network Repair Is Unavailable");
    }

    default NetworkCapability enrollmentCapability() {
        return NetworkCapability.unavailable("enrollmentRenewal", "Server Enrollment Renewal Is Unavailable For This Connection", "");
    }

    default Async<NetworkJob> renewServerEnrollment(String networkId, String nodeId) {
        return unavailable(enrollmentCapability().reason());
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

    default Async<NetworkLifecycleJob> resumeLifecycle(String networkId, String jobId, Consumer<NetworkOperationStatus> progress) {
        return resumeLifecycle(networkId, jobId);
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

    default NetworkCapability operatorCapability() {
        return NetworkCapability.unavailable("operator", "This Connection Uses Saved Network Settings", "network");
    }

    default Async<Void> configureOperator(String networkId, NetworkEditorConnection connection) {
        return unavailable(operatorCapability().reason());
    }

    default NetworkCapability resourcesCapability() {
        return NetworkCapability.unavailable("resources", "Resource Connections Are Unavailable", "");
    }

    default Async<Void> openResources(Screen current, String networkId, String nodeId, BooleanSupplier admission) {
        return unavailable(resourcesCapability().reason());
    }

    default Async<Void> openResources(Screen current, String networkId, String nodeId, String serverId, String apiKey, BooleanSupplier admission) {
        return unavailable(resourcesCapability().reason());
    }

    static NetworkCapability resourcesCapability(RemotelyClient client) {
        NetworkManager<?, ?> manager = client == null ? null : client.getNetworkManager();
        return manager != null && manager.editorAvailable()
                ? NetworkCapability.supported("resources", "network")
                : NetworkCapability.unavailable("resources", "An Authenticated Network Connection Is Required", "network");
    }

    static Async<Void> openResources(RemotelyClient client, NetworkDefinition network, String nodeId, BooleanSupplier admission) {
        return openResources(client, network, nodeId, "", "", admission);
    }

    static Async<Void> openResources(RemotelyClient client, NetworkDefinition network, String nodeId, String serverId, String apiKey, BooleanSupplier admission) {
        NetworkCapability capability = resourcesCapability(client);
        if (!capability.supported()) return unavailable(capability.reason());
        if (network == null || client.getFlowManager() == null) return unavailable("Resource Connections Are Unavailable");
        NetworkMember member = network.members().stream().filter(value -> value.nodeId().equals(nodeId) && !value.isProxy()).findFirst().orElse(null);
        if (member == null) return unavailable("Server Is No Longer In This Network");
        if (admission == null || !admission.getAsBoolean()) return unavailable("Resource Opening Was Cancelled");
        String serverHint = member.isManaged() ? member.instanceId() : member.nodeId();
        return Async.supplyAsync(() -> {
            if (!admission.getAsBoolean()) throw new IllegalStateException("Resource Opening Was Cancelled");
            ReSyncFrameTransport transport = client.getNetworkManager().editorTransport(network.networkId(), member.nodeId());
            if (serverId != null && !serverId.isBlank() && client.getFlowManager().resetNetworkReSyncSession(transport)) {
                transport = client.getNetworkManager().editorTransport(network.networkId(), member.nodeId());
            }
            return transport;
        }).thenCompose(transport -> serverId == null || serverId.isBlank()
                ? client.getFlowManager().openNetworkReSyncStudio(serverHint, member.routeName(), transport, admission)
                : client.getFlowManager().openNetworkReSyncStudio(serverId, member.routeName(), apiKey, transport, admission));
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
