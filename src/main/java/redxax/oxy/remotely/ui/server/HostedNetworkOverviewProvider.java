package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.network.ForwardingMode;
import redxax.oxy.remotely.network.HostedNetworkClient;
import redxax.oxy.remotely.network.HostedNetworkPendingStore;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import restudio.rebase.restudio.api.models.ServerModels;
import redxax.oxy.remotely.network.NetworkConfigDocumentKey;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkDesiredState;
import redxax.oxy.remotely.network.NetworkDiscoveryResult;
import redxax.oxy.remotely.network.NetworkEntryPoint;
import redxax.oxy.remotely.network.NetworkForwardingPolicy;
import redxax.oxy.remotely.network.NetworkIncident;
import redxax.oxy.remotely.network.NetworkIncidentSeverity;
import redxax.oxy.remotely.network.NetworkIncidentSource;
import redxax.oxy.remotely.network.NetworkIncidentStatus;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkJobDocument;
import redxax.oxy.remotely.network.NetworkJobDocumentState;
import redxax.oxy.remotely.network.NetworkJobStatus;
import redxax.oxy.remotely.network.NetworkJobType;
import redxax.oxy.remotely.network.NetworkLifecycleAction;
import redxax.oxy.remotely.network.NetworkLifecycleJob;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkLifecycleStatus;
import redxax.oxy.remotely.network.NetworkLifecycleStep;
import redxax.oxy.remotely.network.NetworkLifecycleStepStatus;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkMemberManagement;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.NetworkObservationState;
import redxax.oxy.remotely.network.NetworkPathSync;
import redxax.oxy.remotely.network.NetworkPreflightReport;
import redxax.oxy.remotely.network.NetworkPreflightCheck;
import redxax.oxy.remotely.network.NetworkPreflightCheckStatus;
import redxax.oxy.remotely.network.NetworkPreflightStatus;
import redxax.oxy.remotely.network.NetworkRuntimeConnectionState;
import redxax.oxy.remotely.network.NetworkRuntimeNodePresence;
import redxax.oxy.remotely.network.NetworkRuntimeNodeStatus;
import redxax.oxy.remotely.network.NetworkRuntimePolicy;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.network.NetworkSharedDataPolicy;
import redxax.oxy.remotely.network.NetworkTransportSecurity;
import redxax.oxy.remotely.network.NetworkValidationIssue;
import redxax.oxy.remotely.network.NetworkMemberObservation;
import redxax.oxy.remotely.network.PortReservation;
import redxax.oxy.remotely.network.RoutingGroup;
import redxax.oxy.remotely.network.RoutingStrategy;
import redxax.oxy.remotely.network.SyncDataFamily;
import redxax.oxy.remotely.network.SyncLocationPolicy;
import redxax.oxy.remotely.network.SyncRealm;
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.util.Identifier;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class HostedNetworkOverviewProvider implements NetworkOverviewProvider {
    public interface Transport {
        Async<JsonObject> request(String method, String path, Object body);
        Async<List<ServerScreenHost.NetworkView>> networks();
        boolean authenticated();
        void openServer(Screen current, String serverId);
    }

    private static final Duration POLL_INITIAL_DELAY = Duration.ofSeconds(1);
    private static final Duration POLL_PERIOD = Duration.ofSeconds(2);
    private static final Map<Enum<?>, Map<String, ? extends Enum<?>>> ENUM_VALUES = Map.ofEntries(
            Map.entry(ForwardingMode.MODERN, enumValues(ForwardingMode.MODERN, ForwardingMode.BUNGEEGUARD, ForwardingMode.LEGACY, ForwardingMode.NONE)),
            Map.entry(NetworkDesiredState.STOPPED, enumValues(NetworkDesiredState.STOPPED, NetworkDesiredState.RUNNING, NetworkDesiredState.MAINTENANCE)),
            Map.entry(NetworkMemberRole.CUSTOM, enumValues(NetworkMemberRole.PROXY, NetworkMemberRole.LOBBY, NetworkMemberRole.FALLBACK,
                    NetworkMemberRole.GAMEPLAY, NetworkMemberRole.RESTRICTED, NetworkMemberRole.MAINTENANCE, NetworkMemberRole.CUSTOM)),
            Map.entry(NetworkMemberManagement.MANAGED, enumValues(NetworkMemberManagement.MANAGED, NetworkMemberManagement.EXTERNAL)),
            Map.entry(RoutingStrategy.ORDERED, enumValues(RoutingStrategy.ORDERED, RoutingStrategy.LEAST_PLAYERS, RoutingStrategy.WEIGHTED)),
            Map.entry(SyncDataFamily.PRESENCE, enumValues(SyncDataFamily.PRESENCE, SyncDataFamily.INVENTORY, SyncDataFamily.ENDER_CHEST,
                    SyncDataFamily.EXPERIENCE, SyncDataFamily.VITALS, SyncDataFamily.EFFECTS, SyncDataFamily.PLAYER_STATE,
                    SyncDataFamily.ADVANCEMENTS, SyncDataFamily.RECIPES, SyncDataFamily.STATISTICS, SyncDataFamily.LOCATION,
                    SyncDataFamily.PERSISTENT_DATA)),
            Map.entry(SyncLocationPolicy.NEVER, enumValues(SyncLocationPolicy.NEVER, SyncLocationPolicy.SAME_SERVER_ONLY,
                    SyncLocationPolicy.REALM_RETURN_POINT, SyncLocationPolicy.EXACT_COMPATIBLE_WORLD)),
            Map.entry(NetworkTransportSecurity.LOOPBACK, enumValues(NetworkTransportSecurity.LOOPBACK, NetworkTransportSecurity.WSS)),
            Map.entry(NetworkSharedDataPolicy.SelectionMode.ALL, enumValues(NetworkSharedDataPolicy.SelectionMode.ALL,
                    NetworkSharedDataPolicy.SelectionMode.ALLOW_LIST, NetworkSharedDataPolicy.SelectionMode.DENY_LIST)),
            Map.entry(NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS, enumValues(NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS,
                    NetworkSharedDataPolicy.ConflictPolicy.LOCAL_WINS)),
            Map.entry(NetworkObservationState.UNKNOWN, enumValues(NetworkObservationState.UNKNOWN, NetworkObservationState.HEALTHY,
                    NetworkObservationState.DEGRADED, NetworkObservationState.INSECURE, NetworkObservationState.UNREACHABLE,
                    NetworkObservationState.DRIFTED)),
            Map.entry(NetworkValidationIssue.Severity.INFO, enumValues(NetworkValidationIssue.Severity.INFO,
                    NetworkValidationIssue.Severity.WARNING, NetworkValidationIssue.Severity.ERROR)),
            Map.entry(NetworkRuntimeNodeStatus.OFFLINE, enumValues(NetworkRuntimeNodeStatus.ONLINE, NetworkRuntimeNodeStatus.DRAINING,
                    NetworkRuntimeNodeStatus.MAINTENANCE, NetworkRuntimeNodeStatus.OFFLINE, NetworkRuntimeNodeStatus.REVOKED)),
            Map.entry(NetworkRuntimeConnectionState.DISABLED, enumValues(NetworkRuntimeConnectionState.DISABLED,
                    NetworkRuntimeConnectionState.CONNECTING, NetworkRuntimeConnectionState.CONNECTED,
                    NetworkRuntimeConnectionState.RECONNECTING, NetworkRuntimeConnectionState.UNAVAILABLE)),
            Map.entry(NetworkIncidentStatus.OPEN, enumValues(NetworkIncidentStatus.OPEN, NetworkIncidentStatus.RESOLVED)),
            Map.entry(NetworkIncidentSource.RUNTIME, enumValues(NetworkIncidentSource.RUNTIME, NetworkIncidentSource.DISCOVERY,
                    NetworkIncidentSource.EVENT)),
            Map.entry(NetworkIncidentSeverity.WARNING, enumValues(NetworkIncidentSeverity.INFO, NetworkIncidentSeverity.WARNING,
                    NetworkIncidentSeverity.CRITICAL)),
            Map.entry(NetworkLifecycleAction.START, enumValues(NetworkLifecycleAction.START, NetworkLifecycleAction.STOP,
                    NetworkLifecycleAction.DRAIN, NetworkLifecycleAction.CAPACITY_GATE, NetworkLifecycleAction.MAINTENANCE,
                    NetworkLifecycleAction.HEALTH_GATE, NetworkLifecycleAction.RESUME)),
            Map.entry(NetworkLifecycleStepStatus.PENDING, enumValues(NetworkLifecycleStepStatus.PENDING, NetworkLifecycleStepStatus.RUNNING,
                    NetworkLifecycleStepStatus.SUCCEEDED, NetworkLifecycleStepStatus.SKIPPED, NetworkLifecycleStepStatus.FAILED)),
            Map.entry(NetworkLifecycleOperation.START, enumValues(NetworkLifecycleOperation.START, NetworkLifecycleOperation.STOP,
                    NetworkLifecycleOperation.RESTART, NetworkLifecycleOperation.ROLLING_RESTART, NetworkLifecycleOperation.DRAIN)),
            Map.entry(NetworkLifecycleStatus.READY, enumValues(NetworkLifecycleStatus.READY, NetworkLifecycleStatus.RUNNING,
                    NetworkLifecycleStatus.INTERRUPTED, NetworkLifecycleStatus.SUCCEEDED, NetworkLifecycleStatus.FAILED)),
            Map.entry(NetworkJobDocumentState.PENDING, enumValues(NetworkJobDocumentState.PENDING, NetworkJobDocumentState.UNCHANGED,
                    NetworkJobDocumentState.APPLIED, NetworkJobDocumentState.ROLLED_BACK)),
            Map.entry(NetworkJobType.RECONCILE, enumValues(NetworkJobType.QUICK_CREATE, NetworkJobType.RECONCILE, NetworkJobType.ATTACH,
                    NetworkJobType.ROUTING, NetworkJobType.REALMS, NetworkJobType.ROTATE_SECRET, NetworkJobType.DETACH,
                    NetworkJobType.DELETE, NetworkJobType.ADOPT, NetworkJobType.LIFECYCLE)),
            Map.entry(NetworkJobStatus.PLANNING, enumValues(NetworkJobStatus.PLANNING, NetworkJobStatus.READY, NetworkJobStatus.RUNNING,
                    NetworkJobStatus.INTERRUPTED, NetworkJobStatus.ROLLING_BACK, NetworkJobStatus.SUCCEEDED,
                    NetworkJobStatus.ROLLED_BACK, NetworkJobStatus.FAILED, NetworkJobStatus.BLOCKED)),
            Map.entry(NetworkPreflightCheckStatus.FAILED, enumValues(NetworkPreflightCheckStatus.PASSED, NetworkPreflightCheckStatus.WARNING,
                    NetworkPreflightCheckStatus.FAILED)),
            Map.entry(NetworkPreflightStatus.RUNNING, enumValues(NetworkPreflightStatus.RUNNING, NetworkPreflightStatus.SUCCEEDED,
                    NetworkPreflightStatus.FAILED)));
    private final Transport adapter;
    private final RemotelyClient client;
    private ServerScreenHost authorityHost;
    private final List<Consumer<OverviewState>> listeners = new ArrayList<>();
    private final List<Consumer<NetworkRuntimeSnapshot>> runtimeListeners = new ArrayList<>();
    private volatile String loadedNetworkId = "";
    private volatile OverviewState loadedState;
    private BooleanSupplier loadedAdmission = () -> false;
    private long loadGeneration;
    private TaskScheduler.ScheduledTask pollingTask;
    private boolean refreshInFlight;

    public HostedNetworkOverviewProvider(Transport adapter) {
        this(adapter, null);
    }

    public HostedNetworkOverviewProvider(Transport adapter, RemotelyClient client) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.client = client;
    }

    @Override
    public NetworkCapability resourcesCapability() {
        return NetworkOverviewProvider.resourcesCapability(client);
    }

    @Override
    public NetworkCapability connectionKeyCapability() {
        return client != null && client.getApiClient() != null && adapter.authenticated()
                ? NetworkCapability.supported("connectionKey", "hosted-network")
                : NetworkCapability.unavailable("connectionKey", "Sign In To Read The Proxy Connection Key", "hosted-network");
    }

    @Override
    public BooleanSupplier connectionKeyAdmission() {
        if (!connectionKeyCapability().supported()) return () -> false;
        RemotelyServerApi api = client.getApiClient();
        ServerScreenHost host = authorityHost();
        ServerScreenHost.AccountIdentity account = host == null ? null : host.accountIdentity();
        return () -> {
            if (client.getApiClient() != api || !adapter.authenticated()) return false;
            if (host == null) return true;
            ServerScreenHost.AccountIdentity currentAccount = host.accountIdentity();
            return account.authenticated() && currentAccount.authenticated()
                    && Objects.equals(account.subjectId(), currentAccount.subjectId());
        };
    }

    private ServerScreenHost authorityHost() {
        if (authorityHost == null && client != null && client.getHost() != null) authorityHost = client.getHost().serverScreenHost(client);
        return authorityHost;
    }

    @Override
    public void addAuthStateListener(Runnable listener) {
        ServerScreenHost host = authorityHost();
        if (host != null) host.addAuthStateListener(listener);
    }

    @Override
    public void removeAuthStateListener(Runnable listener) {
        if (authorityHost != null) authorityHost.removeAuthStateListener(listener);
    }

    @Override
    public Async<String> connectionKey(String networkId) {
        NetworkCapability capability = connectionKeyCapability();
        if (!capability.supported()) return NetworkOverviewProvider.unavailable(capability.reason());
        BooleanSupplier current = connectionKeyAdmission();
        RemotelyServerApi api = client.getApiClient();
        if (!current.getAsBoolean()) return NetworkOverviewProvider.unavailable("Account Changed. Reopen The Network");
        return load(networkId).thenCompose(state -> {
            if (!current.getAsBoolean()) throw new IllegalStateException("Account Changed. Reopen The Network");
            if (state.network() == null || !networkId.equals(state.network().networkId())
                    || state.network().forwarding().mode() != ForwardingMode.MODERN) {
                throw new IllegalStateException("A Modern Forwarding Network Is Required");
            }
            ServerView proxy = state.servers().stream()
                    .filter(server -> server.id().equals(state.network().proxyInstanceId()) && server.proxy() && server.managed())
                    .findFirst().orElseThrow(() -> new IllegalStateException("The Network Proxy Is Unavailable"));
            if (proxy.serverId().isBlank()) throw new IllegalStateException("The Network Proxy Is Awaiting Provisioning");
            return api.getFileContent(proxy.serverId(), "forwarding.secret").thenApply(value -> {
                if (!current.getAsBoolean()) throw new IllegalStateException("Account Changed. Reopen The Network");
                String key = value == null ? "" : value.trim();
                if (key.isBlank()) throw new IllegalStateException("The Proxy Connection Key Is Unavailable. Finish Network Setup First");
                return key;
            });
        });
    }

    @Override
    public Async<Void> openResources(Screen current, String networkId, String nodeId, BooleanSupplier admission) {
        OverviewState state = currentState(networkId);
        Async<OverviewState> source = state != null && state.network() != null && networkId.equals(state.network().networkId()) ? Async.completed(state) : load(networkId);
        return source.thenCompose(value -> NetworkOverviewProvider.openResources(client, value.network(), nodeId, admission));
    }


    @Override
    public Async<Void> openResources(Screen current, String networkId, String nodeId, String serverId, String apiKey, BooleanSupplier admission) {
        OverviewState state = currentState(networkId);
        Async<OverviewState> source = state != null && state.network() != null && networkId.equals(state.network().networkId()) ? Async.completed(state) : load(networkId);
        return source.thenCompose(value -> NetworkOverviewProvider.openResources(client, value.network(), nodeId, serverId, apiKey, admission));
    }

    public static List<ServerScreenHost.NetworkView> views(JsonElement response) {
        if (response == null || !response.isJsonArray()) return List.of();
        List<ServerScreenHost.NetworkView> result = new ArrayList<>();
        for (JsonElement element : response.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            result.add(new ServerScreenHost.NetworkView(HostedNetworkJson.string(value, "id"), HostedNetworkJson.string(value, "name"),
                    HostedNetworkJson.string(value, "status"), HostedNetworkJson.string(value, "description"),
                    HostedNetworkJson.strings(value, "members"), HostedNetworkJson.bool(value, "managed", true),
                    HostedNetworkJson.string(value, "proxyId")));
        }
        return List.copyOf(result);
    }

    @Override
    public Async<OverviewState> load(String networkId) {
        long generation;
        BooleanSupplier current = client == null ? adapter::authenticated : connectionKeyAdmission();
        synchronized (this) {
            if (!networkId.equals(loadedNetworkId)) {
                loadGeneration++;
                loadedNetworkId = networkId;
                loadedState = null;
                loadedAdmission = () -> false;
            }
            generation = loadGeneration;
        }
        return adapter.request("GET", "/networks/" + segment(networkId), null)
                .thenApply(value -> {
                    OverviewState result = state(value);
                    synchronized (this) {
                        if (generation != loadGeneration || !current.getAsBoolean()) {
                            throw new IllegalStateException("Network Changed. Refresh Your Servers");
                        }
                        if (result.network() == null || !networkId.equals(result.network().networkId())) {
                            throw new IllegalStateException("Network Response Does Not Match");
                        }
                        loadedState = result;
                        loadedAdmission = current;
                    }
                    return result;
                });
    }

    @Override
    public String inventoryNetworkId(String networkId) {
        ServerScreenHost host = authorityHost();
        return host == null ? networkId : host.hostedNetworkViewId(networkId);
    }

    @Override
    public BooleanSupplier mutationAdmission() {
        return NetworkOverviewProvider.mutationAdmission(client);
    }

    private synchronized OverviewState currentState(String networkId) {
        return loadedState != null && networkId.equals(loadedNetworkId) && loadedAdmission.getAsBoolean() ? loadedState : null;
    }

    @Override
    public void addListener(Consumer<OverviewState> listener) {
        if (listener == null || listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        ensurePolling();
    }

    @Override
    public void removeListener(Consumer<OverviewState> listener) {
        listeners.remove(listener);
        stopPollingIfUnused();
    }

    @Override
    public void addRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        if (listener == null || runtimeListeners.contains(listener)) {
            return;
        }
        runtimeListeners.add(listener);
        ensurePolling();
    }

    @Override
    public void removeRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        runtimeListeners.remove(listener);
        stopPollingIfUnused();
    }

    private void ensurePolling() {
        if (pollingTask != null && !pollingTask.isCancelled()) {
            return;
        }
        pollingTask = TaskSchedulers.current().scheduleAtFixedRate(this::poll,
                POLL_INITIAL_DELAY, POLL_PERIOD);
    }

    private void stopPollingIfUnused() {
        if (!listeners.isEmpty() || !runtimeListeners.isEmpty()) {
            return;
        }
        TaskScheduler.ScheduledTask task = pollingTask;
        pollingTask = null;
        if (task != null) {
            task.cancel();
        }
        refreshInFlight = false;
    }

    private void poll() {
        if (refreshInFlight || (listeners.isEmpty() && runtimeListeners.isEmpty())) {
            return;
        }
        String networkId = loadedNetworkId;
        if (networkId == null || networkId.isBlank()) {
            return;
        }
        refreshInFlight = true;
        OverviewState previous = loadedState;
        load(networkId).whenComplete((current, failure) -> {
            refreshInFlight = false;
            if (failure != null || current == null || !networkId.equals(loadedNetworkId)) {
                return;
            }
            if (networkChanged(previous, current)) {
                for (Consumer<OverviewState> listener : List.copyOf(listeners)) {
                    listener.accept(current);
                }
            }
            if (runtimeChanged(previous, current) && current.runtime() != null) {
                for (Consumer<NetworkRuntimeSnapshot> listener : List.copyOf(runtimeListeners)) {
                    listener.accept(current.runtime());
                }
            }
        });
    }

    private boolean networkChanged(OverviewState previous, OverviewState current) {
        return previous == null
                || !Objects.equals(previous.network(), current.network())
                || !Objects.equals(previous.servers(), current.servers())
                || !Objects.equals(previous.discovery(), current.discovery())
                || !Objects.equals(previous.incidents(), current.incidents())
                || !Objects.equals(previous.lifecycleJobs(), current.lifecycleJobs())
                || !Objects.equals(previous.jobs(), current.jobs())
                || !Objects.equals(previous.transferFailureHeat(), current.transferFailureHeat())
                || !Objects.equals(previous.reSyncInstalled(), current.reSyncInstalled())
                || !Objects.equals(previous.capabilities(), current.capabilities());
    }

    private boolean runtimeChanged(OverviewState previous, OverviewState current) {
        return previous == null || !Objects.equals(previous.runtime(), current.runtime());
    }

    @Override
    public Async<OverviewState> loadForServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return Async.failed(new IllegalArgumentException("Server Identifier Is Unavailable"));
        }
        return adapter.networks().thenCompose(networks -> networks == null ? Async.failed(new UnsupportedOperationException("Network Is Unavailable"))
                : networks.stream().filter(network -> network != null && network.members().contains(serverId)).findFirst()
                .map(network -> load(network.id()))
                .orElseGet(() -> Async.failed(new UnsupportedOperationException("Network Is Unavailable"))));
    }

    @Override
    public Async<NetworkDefinition> save(String networkId, SaveRequest request) {
        return observeMutation(networkId, () -> load(networkId).thenCompose(state -> requireCapability(state, "save")
                .thenCompose(ignored -> adapter.request("PUT", "/networks/" + segment(networkId),
                        withRevision(HostedNetworkJson.save(request), state.network().revision())))), null)
                .thenCompose(ignored -> load(networkId)).thenApply(OverviewState::network);
    }

    @Override
    public Async<NetworkLifecycleJob> lifecycle(String networkId, NetworkLifecycleOperation operation) {
        return lifecycle(networkId, operation, null);
    }

    @Override
    public Async<NetworkLifecycleJob> lifecycle(String networkId, NetworkLifecycleOperation operation,
                                               Consumer<NetworkOperationStatus> progress) {
        return lifecycleRequest(networkId, "/lifecycle", Map.of("operation", enumName(operation, NetworkLifecycleOperation.START)), progress);
    }

    @Override
    public Async<NetworkLifecycleJob> memberLifecycle(String networkId, String memberId,
                                                       NetworkLifecycleOperation operation) {
        return memberLifecycle(networkId, memberId, operation, null);
    }

    @Override
    public Async<NetworkLifecycleJob> memberLifecycle(String networkId, String memberId,
                                                      NetworkLifecycleOperation operation, Consumer<NetworkOperationStatus> progress) {
        return observeMutation(networkId, () -> load(networkId).thenCompose(state -> requireCapability(state, "memberLifecycle")
                .thenCompose(ignored -> {
                    String instanceId = instanceId(state, memberId);
                    if (instanceId.isBlank()) return Async.failed(new IllegalArgumentException("Network Member Is Unavailable"));
                    return adapter.request("POST", networkPath(networkId, "/members/" + segment(instanceId) + "/lifecycle"),
                            withRevision(Map.of("operation", enumName(operation, NetworkLifecycleOperation.START)), state.network().revision()));
                })), progress).thenApply(HostedNetworkOverviewProvider::completedLifecycle);
    }

    @Override
    public Async<NetworkJob> attach(String networkId, AttachRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverId", request == null ? "" : request.serverId());
        body.put("joinRule", request == null ? "" : request.joinRule());
        body.put("installReSync", request != null && request.installReSync());
        return jobRequest(networkId, "/members", body);
    }

    @Override
    public Async<NetworkJob> attachExternal(String networkId, ExternalAttachRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request != null) {
            body.put("name", request.name());
            body.put("address", request.address());
            body.put("port", request.port());
            body.put("capacity", request.capacity());
            body.put("joinRule", request.joinRule());
        }
        body.putIfAbsent("name", "");
        body.putIfAbsent("address", "");
        body.putIfAbsent("port", 0);
        body.putIfAbsent("capacity", 0);
        body.putIfAbsent("joinRule", "");
        return jobRequest(networkId, "/members/external", body);
    }

    @Override
    public Async<NetworkJob> detach(String networkId, String memberId) {
        return observeMutation(networkId, () -> load(networkId).thenCompose(state -> requireCapability(state, "membership")
                .thenCompose(ignored -> {
                    String instanceId = instanceId(state, memberId);
                    if (instanceId.isBlank()) return Async.failed(new IllegalArgumentException("Network Member Is Unavailable"));
                    return adapter.request("POST", networkPath(networkId, "/members/" + segment(instanceId) + "/detach"),
                            withRevision(Map.of(), state.network().revision()));
                })), null).thenApply(HostedNetworkOverviewProvider::completedJob);
    }

    @Override
    public Async<NetworkJob> dissolve(String networkId) {
        return jobRequest(networkId, "/dissolve", Map.of()).thenApply(job -> {
            if (job.status() != NetworkJobStatus.SUCCEEDED) throw new IllegalStateException(job.message());
            synchronized (this) {
                if (networkId.equals(loadedNetworkId)) {
                    loadGeneration++;
                    loadedNetworkId = "";
                    loadedState = null;
                    loadedAdmission = () -> false;
                }
            }
            return job;
        });
    }

    @Override
    public Async<NetworkPreflightReport> preflight(String networkId) {
        return load(networkId).thenCompose(state -> requireCapability(state, "preflight")
                .thenCompose(ignored -> adapter.request("POST", networkPath(networkId, "/preflight"),
                        withRevision(Map.of(), state.network().revision()))))
                .thenApply(value -> preflight(object(value, "preflight")));
    }

    @Override
    public Async<NetworkJob> rotateSecret(String networkId) {
        return jobRequest(networkId, "/rotate-secret", Map.of());
    }

    @Override
    public Async<NetworkJob> reconcile(String networkId) {
        return jobRequest(networkId, "/reconcile", Map.of());
    }

    @Override
    public Async<NetworkJob> resumeJob(String networkId, String jobId) {
        return jobRequest(networkId, "/jobs/" + segment(jobId) + "/resume", Map.of());
    }

    @Override
    public Async<NetworkJob> rollbackJob(String networkId, String jobId) {
        return jobRequest(networkId, "/jobs/" + segment(jobId) + "/rollback", Map.of());
    }

    @Override
    public Async<NetworkLifecycleJob> resumeLifecycle(String networkId, String jobId) {
        return resumeLifecycle(networkId, jobId, null);
    }

    @Override
    public Async<NetworkLifecycleJob> resumeLifecycle(String networkId, String jobId, Consumer<NetworkOperationStatus> progress) {
        return lifecycleRequest(networkId, "/lifecycle-jobs/" + segment(jobId) + "/resume", Map.of(), progress);
    }

    @Override
    public Async<Void> runtimeNodeMode(String networkId, String nodeId, NetworkRuntimeNodeStatus status) {
        return load(networkId).thenCompose(state -> requireCapability(state, "runtimeControl")
                .thenCompose(ignored -> adapter.request("POST", networkPath(networkId, "/runtime/nodes/" + segment(nodeId)),
                        withRevision(Map.of("status", enumName(status, NetworkRuntimeNodeStatus.OFFLINE)), state.network().revision())))).thenApply(ignored -> null);
    }

    @Override
    public Async<NetworkJob> installReSync(String networkId) {
        return jobRequest(networkId, "/resync", Map.of());
    }

    @Override
    public Async<Void> executeProxyCommand(String networkId, String command) {
        return load(networkId).thenCompose(state -> requireCapability(state, "command")
                .thenCompose(ignored -> adapter.request("POST", networkPath(networkId, "/command"),
                        withRevision(Map.of("command", command == null ? "" : command), state.network().revision()))))
                .thenApply(ignored -> null);
    }

    @Override
    public Async<Void> broadcastMessage(String networkId, String message) {
        return load(networkId).thenCompose(state -> requireCapability(state, "broadcast")
                .thenCompose(ignored -> adapter.request("POST", networkPath(networkId, "/broadcast"),
                        withRevision(Map.of("message", message == null ? "" : message), state.network().revision()))))
                .thenApply(ignored -> null);
    }

    @Override
    public Async<NetworkCreationContext> serverCreationContext(String networkId) {
        return adapter.authenticated() ? Async.completed(new NetworkCreationContext(true, null))
                : NetworkOverviewProvider.unavailable("Sign In To Add Reactor Servers");
    }

    @Override
    public boolean reactorNetwork() {
        return true;
    }

    @Override
    public Async<List<ServerView>> availableServers(String networkId) {
        ServerScreenHost host = authorityHost();
        if (host == null) return NetworkOverviewProvider.unavailable("Reactor Servers Are Unavailable");
        BooleanSupplier current = connectionKeyAdmission();
        return adapter.networks().thenCompose(networks -> host.restudioServers().thenApply(servers -> {
            if (!current.getAsBoolean()) throw new IllegalStateException("Account Changed. Reopen The Network");
            Set<String> attached = networks.stream().flatMap(value -> value.members().stream()).collect(Collectors.toSet());
            return servers.stream().filter(server -> server != null && !server.isInstalling && !server.isSuspended)
                    .filter(server -> !reactorServerId(server).isBlank() && !attached.contains(reactorServerId(server)))
                    .filter(server -> supportedBackend(server.software == null || server.software.isBlank() ? server.loader : server.software))
                    .map(server -> new ServerView(reactorServerId(server), server.name, false, true, "Reactor", server.ip,
                            server.port, "", (Identifier) null, reactorServerId(server))).toList();
        }));
    }

    private static String reactorServerId(ServerModels.ClientServerView server) {
        return server.identifier == null || server.identifier.isBlank() ? server.uuid == null ? "" : server.uuid : server.identifier;
    }

    private static boolean supportedBackend(String software) {
        return switch (software == null ? "" : software.trim().toUpperCase(Locale.ROOT)) {
            case "PAPER", "FOLIA", "PURPUR", "LEAF", "PUFFERFISH", "CANVAS", "ASPAPER", "DIVINEMC" -> true;
            default -> false;
        };
    }

    @Override
    public ServerScreenHost.ActionAvailability serverDraftAvailability(String networkId, ServerConfigurationTarget target, NetworkMemberSource.Draft source) {
        return source != null && supportedBackend(source.metadata().settings().get("SOFTWARE"))
                ? ServerScreenHost.ActionAvailability.enabled()
                : ServerScreenHost.ActionAvailability.disabled("Choose Paper Or A Supported Paper Server For This Reactor Network");
    }

    @Override
    public Async<Void> addServer(String networkId, AttachRequest request, Consumer<NetworkOperationStatus> progress) {
        try {
            ServerScreenHost host = authorityHost();
            if (host == null) return NetworkOverviewProvider.unavailable("Reactor Servers Are Unavailable");
            BooleanSupplier current = connectionKeyAdmission();
            String account = host.hostedNetworkAccount();
            HostedNetworkPendingStore store = host.hostedNetworkPendingStore();
            HostedNetworkPendingStore.PendingAttach saved = store.attachment(account, networkId);
            Async<HostedNetworkPendingStore.PendingAttach> prepared;
            if (saved != null) prepared = Async.completed(saved);
            else if (request == null) return NetworkOverviewProvider.unavailable("Saved Server Request Is Unavailable");
            else prepared = load(networkId).thenCompose(state -> requireCapability(state, "membership").thenApply(ignored -> {
                if (!current.getAsBoolean() || !account.equals(host.hostedNetworkAccount())) throw new IllegalStateException("Account Changed. Reopen The Network");
                if (state.servers().stream().anyMatch(server -> server.managed() && !stoppedServer(server.state()))) {
                    throw new IllegalStateException("Stop Network Servers Before Adding A Server");
                }
                NetworkMemberSource source = request.source() == null ? new NetworkMemberSource.ExistingServer(request.serverId()) : request.source();
                String name = request.name().isBlank() ? "Backend" : request.name();
                String route = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
                if (route.isBlank()) route = "backend";
                Set<String> routes = state.network().members().stream().map(NetworkMember::routeName).collect(Collectors.toSet());
                String selected = route;
                for (int index = 2; routes.contains(selected); index++) selected = route + "-" + index;
                NetworkCommand.Member member = new NetworkCommand.Member(source, selected, NetworkMemberRole.GAMEPLAY, 0, 0, request.installReSync());
                NetworkCommand.Attach command = new NetworkCommand.Attach(NetworkCommand.CURRENT_SCHEMA_VERSION, UUID.randomUUID().toString(),
                        networkId, state.network().revision(), member);
                return store.admitAttachment(account, command);
            }));
            RemotelyServerApi api = client.getApiClient();
            return prepared.thenCompose(pending -> api.hostedNetworks().execute(pending.command(), pending.body(), client.getComposition().scheduler(),
                    () -> current.getAsBoolean() && account.equals(host.hostedNetworkAccount()), progress).whenComplete((status, error) -> {
                if (status != null) store.clearAttachment(account, pending.command().requestId(), networkId);
                else if (error instanceof HostedNetworkClient.OperationFailure terminal) {
                    store.markAttachmentTerminal(account, pending.command().requestId(), networkId, terminal.status().state());
                }
            })).thenApply(status -> null);
        } catch (Throwable failure) {
            return Async.failed(failure);
        }
    }

    private static boolean stoppedServer(String state) {
        return "OFFLINE".equalsIgnoreCase(state) || "STOPPED".equalsIgnoreCase(state);
    }

    @Override
    public boolean available() {
        return adapter.authenticated();
    }

    @Override
    public void openServer(Screen current, String serverId) {
        adapter.openServer(current, serverId);
    }

    private Async<NetworkLifecycleJob> lifecycleRequest(String networkId, String suffix, Object body, Consumer<NetworkOperationStatus> progress) {
        return observeMutation(networkId, () -> load(networkId).thenCompose(state -> requireCapability(state, lifecycleCapability(suffix))
                .thenCompose(ignored -> adapter.request("POST", networkPath(networkId, suffix),
                        withRevision(body, state.network().revision())))), progress)
                .thenApply(HostedNetworkOverviewProvider::completedLifecycle);
    }

    private Async<NetworkJob> jobRequest(String networkId, String suffix, Object body) {
        return observeMutation(networkId, () -> load(networkId).thenCompose(state -> requireCapability(state, jobCapability(suffix))
                .thenCompose(ignored -> adapter.request("POST", networkPath(networkId, suffix),
                        withRevision(body, state.network().revision())))), null)
                .thenApply(HostedNetworkOverviewProvider::completedJob);
    }

    private Async<NetworkOperationStatus> observeMutation(String networkId, Supplier<Async<JsonObject>> admission,
                                                          Consumer<NetworkOperationStatus> progress) {
        try {
            if (client == null || client.getApiClient() == null) return Async.failed(new IllegalStateException("Network Progress Is Unavailable"));
            BooleanSupplier current = connectionKeyAdmission();
            if (!current.getAsBoolean()) return Async.failed(new IllegalStateException("Account Changed. Reopen The Network"));
            HostedNetworkClient observer = client.getApiClient().hostedNetworks();
            return admission.get().thenCompose(value -> observer.observe(networkId, object(value, "operation"),
                    client.getComposition().scheduler(), current, progress));
        } catch (Throwable failure) {
            return Async.failed(failure);
        }
    }

    private static NetworkLifecycleJob completedLifecycle(NetworkOperationStatus status) {
        if (status.lifecycleJob() == null) throw new IllegalStateException("Completed Network Lifecycle Result Is Unavailable");
        return status.lifecycleJob();
    }

    private static NetworkJob completedJob(NetworkOperationStatus status) {
        if (status.job() == null) throw new IllegalStateException("Completed Network Result Is Unavailable");
        return status.job();
    }

    private static Async<Void> requireCapability(OverviewState state, String operation) {
        if (state == null || operation == null || operation.isBlank()) {
            return Async.completed(null);
        }
        NetworkCapability capability = state.capabilities().get(operation);
        if (capability != null && capability.supported()) {
            return Async.completed(null);
        }
        String reason = capability == null || capability.reason().isBlank() ? "Network Operation Is Unavailable" : capability.reason();
        return Async.failed(new UnsupportedOperationException(reason));
    }

    private static String instanceId(OverviewState state, String memberId) {
        if (state == null || state.network() == null || memberId == null || memberId.isBlank()) return "";
        return state.network().members().stream()
                .filter(member -> memberId.equals(member.instanceId()) || memberId.equals(member.nodeId()))
                .map(NetworkMember::instanceId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String lifecycleCapability(String suffix) {
        if (suffix != null && suffix.startsWith("/lifecycle-jobs/")) return "lifecycleRecovery";
        return suffix != null && suffix.startsWith("/members/") ? "memberLifecycle" : "lifecycle";
    }

    private static String jobCapability(String suffix) {
        if (suffix == null) return "job";
        if (suffix.equals("/members")) return "membership";
        if (suffix.equals("/members/external")) return "externalMembership";
        if (suffix.endsWith("/detach")) return "membership";
        if (suffix.equals("/dissolve")) return "dissolve";
        if (suffix.equals("/rotate-secret")) return "secretRotation";
        if (suffix.equals("/reconcile")) return "reconcile";
        if (suffix.endsWith("/resume") || suffix.endsWith("/rollback")) return "jobRecovery";
        if (suffix.equals("/resync")) return "resync";
        return "job";
    }

    private static Object withRevision(Object body, long revision) {
        if (body instanceof JsonObject value) {
            JsonObject result = value.deepCopy();
            result.addProperty("expectedRevision", revision);
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (body instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key instanceof String name) result.put(name, value);
            });
        }
        result.put("expectedRevision", revision);
        return result;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String networkPath(String networkId, String suffix) {
        return "/networks/" + segment(networkId) + suffix;
    }

    private static OverviewState state(JsonObject value) {
        NetworkDefinition network = definition(object(value, "network"));
        List<ServerView> servers = HostedNetworkJson.servers(value);
        NetworkDiscoveryResult discovery = discovery(object(value, "discovery"), network);
        NetworkRuntimeSnapshot runtime = runtime(object(value, "runtime"), network.networkId());
        List<NetworkIncident> incidents = HostedNetworkJson.objects(value, "incidents").stream().map(item -> incident(item, network.networkId())).toList();
        List<NetworkLifecycleJob> lifecycleJobs = HostedNetworkJson.objects(value, "lifecycleJobs").stream().map(HostedNetworkOverviewProvider::lifecycleJob).toList();
        List<NetworkJob> jobs = HostedNetworkJson.objects(value, "jobs").stream().map(HostedNetworkOverviewProvider::networkJob).toList();
        return new OverviewState(network, servers, discovery, runtime, incidents, lifecycleJobs, jobs,
                HostedNetworkJson.integerMap(value, "transferFailureHeat"), HostedNetworkJson.booleanMap(value, "reSyncInstalled"),
                HostedNetworkJson.capabilities(value));
    }

    private static NetworkDefinition definition(JsonObject value) {
        List<NetworkMember> members = HostedNetworkJson.objects(value, "members").stream().map(HostedNetworkOverviewProvider::member).toList();
        return new NetworkDefinition(
                HostedNetworkJson.integer(value, "schemaVersion", 5),
                HostedNetworkJson.string(value, "networkId"),
                HostedNetworkJson.string(value, "name"),
                HostedNetworkJson.longValue(value, "revision", 1),
                HostedNetworkJson.string(value, "proxyInstanceId"),
                enumValue(HostedNetworkJson.string(value, "desiredState"), NetworkDesiredState.STOPPED),
                forwarding(HostedNetworkJson.object(value, "forwarding")),
                HostedNetworkJson.objects(value, "entryPoints").stream().map(HostedNetworkOverviewProvider::entryPoint).toList(),
                members,
                HostedNetworkJson.objects(value, "routingGroups").stream().map(HostedNetworkOverviewProvider::routingGroup).toList(),
                HostedNetworkJson.objects(value, "syncRealms").stream().map(HostedNetworkOverviewProvider::syncRealm).toList(),
                runtimePolicy(HostedNetworkJson.object(value, "runtime")),
                HostedNetworkJson.booleanMap(value, "features"),
                sharedDataPolicy(HostedNetworkJson.object(value, "sharedDataPolicy")),
                HostedNetworkJson.longValue(value, "createdAt", 0),
                HostedNetworkJson.longValue(value, "updatedAt", 0));
    }

    private static NetworkMember member(JsonObject value) {
        return new NetworkMember(HostedNetworkJson.string(value, "instanceId"), HostedNetworkJson.string(value, "nodeId"),
                HostedNetworkJson.string(value, "routeName"), enumValue(HostedNetworkJson.string(value, "role"), NetworkMemberRole.CUSTOM),
                HostedNetworkJson.string(value, "hostScope"), HostedNetworkJson.string(value, "address"),
                HostedNetworkJson.integer(value, "port", 0), HostedNetworkJson.integer(value, "capacity", 0),
                HostedNetworkJson.bool(value, "resyncEnabled", false),
                enumValue(HostedNetworkJson.string(value, "management"), NetworkMemberManagement.MANAGED));
    }

    private static NetworkForwardingPolicy forwarding(JsonObject value) {
        return new NetworkForwardingPolicy(enumValue(HostedNetworkJson.string(value, "mode"), ForwardingMode.MODERN),
                HostedNetworkJson.bool(value, "proxyOnlineMode", true), HostedNetworkJson.string(value, "secretReference"),
                HostedNetworkJson.bool(value, "firewallVerified", false));
    }

    private static NetworkEntryPoint entryPoint(JsonObject value) {
        return new NetworkEntryPoint(HostedNetworkJson.string(value, "id"), HostedNetworkJson.string(value, "bindAddress"),
                HostedNetworkJson.integer(value, "port", 0), new LinkedHashSet<>(HostedNetworkJson.strings(value, "forcedHosts")));
    }

    private static RoutingGroup routingGroup(JsonObject value) {
        return new RoutingGroup(HostedNetworkJson.string(value, "id"), HostedNetworkJson.string(value, "name"),
                enumValue(HostedNetworkJson.string(value, "strategy"), RoutingStrategy.ORDERED), HostedNetworkJson.strings(value, "nodeIds"),
                HostedNetworkJson.integerMap(value, "weights"), HostedNetworkJson.string(value, "fallbackGroupId"),
                new LinkedHashSet<>(HostedNetworkJson.strings(value, "forcedHosts")), HostedNetworkJson.string(value, "permission"));
    }

    private static SyncRealm syncRealm(JsonObject value) {
        return new SyncRealm(HostedNetworkJson.string(value, "id"), HostedNetworkJson.string(value, "name"),
                new LinkedHashSet<>(HostedNetworkJson.strings(value, "nodeIds")),
                HostedNetworkJson.strings(value, "dataFamilies").stream().map(item -> enumValue(item, SyncDataFamily.PRESENCE)).collect(Collectors.toCollection(LinkedHashSet::new)),
                enumValue(HostedNetworkJson.string(value, "locationPolicy"), SyncLocationPolicy.NEVER),
                new LinkedHashSet<>(HostedNetworkJson.strings(value, "persistentDataNamespaces")),
                HostedNetworkJson.integer(value, "retainedSnapshots", 10), HostedNetworkJson.integer(value, "retentionDays", 30));
    }

    private static NetworkRuntimePolicy runtimePolicy(JsonObject value) {
        return new NetworkRuntimePolicy(HostedNetworkJson.bool(value, "enabled", false), HostedNetworkJson.string(value, "hubAddress"),
                HostedNetworkJson.integer(value, "hubPort", 0), enumValue(HostedNetworkJson.string(value, "security"), NetworkTransportSecurity.LOOPBACK),
                HostedNetworkJson.bool(value, "transportReady", false));
    }

    private static NetworkSharedDataPolicy sharedDataPolicy(JsonObject value) {
        List<NetworkPathSync> pathSyncs = HostedNetworkJson.objects(value, "pathSyncs").stream().map(HostedNetworkOverviewProvider::pathSync).toList();
        return new NetworkSharedDataPolicy(enumValue(HostedNetworkJson.string(value, "chatChannelMode"), NetworkSharedDataPolicy.SelectionMode.ALL),
                new LinkedHashSet<>(HostedNetworkJson.strings(value, "chatChannels")), HostedNetworkJson.longValue(value, "chatRetentionMillis", 120_000),
                enumValue(HostedNetworkJson.string(value, "resourceTypeMode"), NetworkSharedDataPolicy.SelectionMode.ALL),
                new LinkedHashSet<>(HostedNetworkJson.strings(value, "resourceTypes")), pathSyncs,
                enumValue(HostedNetworkJson.string(value, "resourceConflictPolicy"), NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS),
                HostedNetworkJson.integer(value, "maximumPayloadBytes", NetworkSharedDataPolicy.DEFAULT_MAXIMUM_PAYLOAD_BYTES));
    }

    private static NetworkPathSync pathSync(JsonObject value) {
        return new NetworkPathSync(HostedNetworkJson.string(value, "id"), HostedNetworkJson.string(value, "name"),
                HostedNetworkJson.bool(value, "enabled", false), new LinkedHashSet<>(HostedNetworkJson.strings(value, "nodeIds")),
                new LinkedHashSet<>(HostedNetworkJson.strings(value, "paths")),
                enumValue(HostedNetworkJson.string(value, "conflictPolicy"), NetworkSharedDataPolicy.ConflictPolicy.NETWORK_WINS),
                HostedNetworkJson.strings(value, "commands"));
    }

    private static NetworkDiscoveryResult discovery(JsonObject value, NetworkDefinition network) {
        List<NetworkMemberObservation> observations = HostedNetworkJson.objects(value, "observations").stream().map(item -> new NetworkMemberObservation(
                HostedNetworkJson.string(item, "nodeId"), HostedNetworkJson.string(item, "instanceId"), HostedNetworkJson.bool(item, "instanceAvailable", false),
                HostedNetworkJson.string(item, "observedHostScope"), HostedNetworkJson.integer(item, "observedPort", 0), HostedNetworkJson.string(item, "software"),
                enumValue(HostedNetworkJson.string(item, "state"), NetworkObservationState.UNKNOWN), HostedNetworkJson.longValue(item, "observedAt", 0))).toList();
        List<PortReservation> reservations = HostedNetworkJson.objects(value, "reservations").stream().map(item -> new PortReservation(
                HostedNetworkJson.string(item, "hostScope"), HostedNetworkJson.integer(item, "port", 0), HostedNetworkJson.string(item, "ownerType"),
                HostedNetworkJson.string(item, "ownerId"), HostedNetworkJson.string(item, "label"))).toList();
        List<NetworkValidationIssue> issues = HostedNetworkJson.objects(value, "issues").stream().map(HostedNetworkOverviewProvider::issue).toList();
        return new NetworkDiscoveryResult(network, Map.of(), observations, reservations, issues);
    }

    private static NetworkValidationIssue issue(JsonObject value) {
        return new NetworkValidationIssue(enumValue(HostedNetworkJson.string(value, "severity"), NetworkValidationIssue.Severity.INFO),
                HostedNetworkJson.string(value, "code"), HostedNetworkJson.string(value, "subject"), HostedNetworkJson.string(value, "message"));
    }

    private static NetworkRuntimeSnapshot runtime(JsonObject value, String networkId) {
        Map<String, NetworkRuntimeNodePresence> nodes = new LinkedHashMap<>();
        JsonObject nodeMap = HostedNetworkJson.object(value, "nodes");
        nodeMap.entrySet().forEach(entry -> {
            if (entry.getValue() != null && entry.getValue().isJsonObject()) {
                JsonObject item = entry.getValue().getAsJsonObject();
                NetworkRuntimeNodePresence presence = new NetworkRuntimeNodePresence(
                        HostedNetworkJson.string(item, "networkId", networkId), HostedNetworkJson.string(item, "nodeId", entry.getKey()),
                        enumValue(HostedNetworkJson.string(item, "status"), NetworkRuntimeNodeStatus.OFFLINE), HostedNetworkJson.integer(item, "players", 0),
                        HostedNetworkJson.integer(item, "capacity", 0), HostedNetworkJson.decimal(item, "tps", -1), HostedNetworkJson.decimal(item, "mspt", -1),
                        HostedNetworkJson.longValue(item, "heapUsed", 0), HostedNetworkJson.longValue(item, "heapMaximum", 0), HostedNetworkJson.longValue(item, "observedAt", 0));
                nodes.put(entry.getKey(), presence);
            }
        });
        return new NetworkRuntimeSnapshot(HostedNetworkJson.string(value, "networkId", networkId),
                enumValue(HostedNetworkJson.string(value, "state"), NetworkRuntimeConnectionState.DISABLED),
                HostedNetworkJson.string(value, "message"), nodes, HostedNetworkJson.longValue(value, "updatedAt", 0));
    }

    private static NetworkIncident incident(JsonObject value, String networkId) {
        String id = HostedNetworkJson.string(value, "incidentId", UUID.randomUUID().toString());
        String resolvedNetworkId = HostedNetworkJson.string(value, "networkId", networkId);
        long openedAt = HostedNetworkJson.longValue(value, "openedAt", 0);
        long updatedAt = Math.max(openedAt, HostedNetworkJson.longValue(value, "updatedAt", openedAt));
        NetworkIncidentStatus status = enumValue(HostedNetworkJson.string(value, "status"), NetworkIncidentStatus.OPEN);
        long resolvedAt = HostedNetworkJson.longValue(value, "resolvedAt", 0);
        if (status == NetworkIncidentStatus.OPEN) resolvedAt = 0;
        if (status == NetworkIncidentStatus.RESOLVED) resolvedAt = Math.max(updatedAt, resolvedAt);
        return new NetworkIncident(id, resolvedNetworkId, HostedNetworkJson.string(value, "key", id), HostedNetworkJson.string(value, "nodeId"),
                HostedNetworkJson.string(value, "type", "network"), enumValue(HostedNetworkJson.string(value, "source"), NetworkIncidentSource.RUNTIME),
                enumValue(HostedNetworkJson.string(value, "severity"), NetworkIncidentSeverity.WARNING), status,
                HostedNetworkJson.string(value, "summary", "Network Incident"), HostedNetworkJson.string(value, "detail"), openedAt, updatedAt,
                resolvedAt, Math.max(1, HostedNetworkJson.integer(value, "occurrences", 1)));
    }

    private static NetworkLifecycleJob lifecycleJob(JsonObject value) {
        List<NetworkLifecycleStep> steps = HostedNetworkJson.objects(value, "steps").stream().map(item -> new NetworkLifecycleStep(
                HostedNetworkJson.string(item, "stepId"), HostedNetworkJson.string(item, "instanceId"), HostedNetworkJson.string(item, "nodeId"),
                HostedNetworkJson.string(item, "routeName"), enumValue(HostedNetworkJson.string(item, "action"), NetworkLifecycleAction.START),
                enumValue(HostedNetworkJson.string(item, "status"), NetworkLifecycleStepStatus.PENDING), HostedNetworkJson.longValue(item, "startedAt", 0),
                HostedNetworkJson.longValue(item, "completedAt", 0), HostedNetworkJson.string(item, "message"))).toList();
        return new NetworkLifecycleJob(HostedNetworkJson.integer(value, "schemaVersion", 1), HostedNetworkJson.string(value, "jobId"),
                HostedNetworkJson.string(value, "networkId"), HostedNetworkJson.longValue(value, "networkRevision", 1),
                enumValue(HostedNetworkJson.string(value, "operation"), NetworkLifecycleOperation.START),
                enumValue(HostedNetworkJson.string(value, "status"), NetworkLifecycleStatus.READY), HostedNetworkJson.string(value, "initiator"),
                HostedNetworkJson.longValue(value, "createdAt", 0), HostedNetworkJson.longValue(value, "updatedAt", 0),
                HostedNetworkJson.integer(value, "attempt", 0), HostedNetworkJson.string(value, "message"), steps);
    }

    private static NetworkJob networkJob(JsonObject value) {
        List<NetworkJobDocument> documents = HostedNetworkJson.objects(value, "documents").stream().map(item -> new NetworkJobDocument(
                new NetworkConfigDocumentKey(HostedNetworkJson.string(HostedNetworkJson.object(item, "key"), "instanceId"),
                        HostedNetworkJson.string(HostedNetworkJson.object(item, "key"), "path")), HostedNetworkJson.integer(item, "applyOrder", 0),
                HostedNetworkJson.bool(item, "originalExists", false), HostedNetworkJson.string(item, "originalHash"), HostedNetworkJson.string(item, "desiredHash"),
                enumValue(HostedNetworkJson.string(item, "state"), NetworkJobDocumentState.PENDING))).toList();
        return new NetworkJob(HostedNetworkJson.integer(value, "schemaVersion", 1), HostedNetworkJson.string(value, "jobId"),
                HostedNetworkJson.string(value, "networkId"), HostedNetworkJson.longValue(value, "networkRevision", 1),
                enumValue(HostedNetworkJson.string(value, "type"), NetworkJobType.RECONCILE), enumValue(HostedNetworkJson.string(value, "status"), NetworkJobStatus.PLANNING),
                HostedNetworkJson.string(value, "initiator"), HostedNetworkJson.longValue(value, "createdAt", 0), HostedNetworkJson.longValue(value, "updatedAt", 0),
                HostedNetworkJson.integer(value, "attempt", 0), HostedNetworkJson.string(value, "message"), HostedNetworkJson.stringMap(value, "context"), documents,
                HostedNetworkJson.objects(value, "issues").stream().map(HostedNetworkOverviewProvider::issue).toList());
    }

    private static NetworkPreflightReport preflight(JsonObject value) {
        List<NetworkPreflightCheck> checks = HostedNetworkJson.objects(value, "checks").stream().map(item -> new NetworkPreflightCheck(
                HostedNetworkJson.string(item, "id"), HostedNetworkJson.string(item, "subject"), HostedNetworkJson.string(item, "label"),
                enumValue(HostedNetworkJson.string(item, "status"), NetworkPreflightCheckStatus.FAILED), HostedNetworkJson.string(item, "detail"),
                HostedNetworkJson.longValue(item, "checkedAt", 0))).toList();
        return new NetworkPreflightReport(HostedNetworkJson.integer(value, "schemaVersion", 1), HostedNetworkJson.string(value, "reportId"),
                HostedNetworkJson.string(value, "networkId"), HostedNetworkJson.longValue(value, "networkRevision", 1),
                enumValue(HostedNetworkJson.string(value, "status"), NetworkPreflightStatus.RUNNING), HostedNetworkJson.longValue(value, "startedAt", 0),
                HostedNetworkJson.longValue(value, "completedAt", 0), HostedNetworkJson.string(value, "summary"), checks);
    }

    private static String enumName(Enum<?> value, Enum<?> fallback) {
        return (value == null ? fallback : value).name();
    }

    private static <T extends Enum<T>> T enumValue(String value, T fallback) {
        if (fallback == null || value == null || value.isBlank()) return fallback;
        Map<String, ? extends Enum<?>> values = ENUM_VALUES.get(fallback);
        if (values == null) return fallback;
        Enum<?> candidate = values.get(value.trim().toUpperCase(Locale.ROOT));
        return candidate == null ? fallback : castEnum(candidate);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> T castEnum(Enum<?> value) {
        return (T) value;
    }

    private static <T extends Enum<T>> Map<String, T> enumValues(T... values) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            result.put(value.name(), value);
        }
        return Map.copyOf(result);
    }

    private static JsonObject object(JsonObject value, String key) {
        return HostedNetworkJson.object(value, key);
    }

}
