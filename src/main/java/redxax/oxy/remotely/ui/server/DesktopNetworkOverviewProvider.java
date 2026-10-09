package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.network.DesktopNetworkManager;
import redxax.oxy.remotely.network.ForwardingMode;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkDiscoveryResult;
import redxax.oxy.remotely.network.NetworkDiscoveryService;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkJobStatus;
import redxax.oxy.remotely.network.NetworkJobType;
import redxax.oxy.remotely.network.NetworkLifecycleJob;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkMember;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.NetworkPreflightReport;
import redxax.oxy.remotely.network.NetworkRuntimeNodeStatus;
import redxax.oxy.remotely.network.NetworkRuntimeSnapshot;
import redxax.oxy.remotely.network.NetworkHostScope;
import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import restudio.rebase.Rebase;
import restudio.rebase.api.unified.InstanceApi;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public class DesktopNetworkOverviewProvider implements NetworkOverviewProvider {
    private final RemotelyClient client;
    private final DesktopNetworkManager manager;
    private final ServerIconManager iconManager;
    private final Map<Consumer<OverviewState>, Consumer<List<NetworkDefinition>>> listeners = new IdentityHashMap<>();
    private volatile String loadedNetworkId = "";
    private final List<BiConsumer<String, String>> serverStateListeners = new ArrayList<>();
    private final Map<Instance, Consumer<InstanceState>> observedInstances = new IdentityHashMap<>();
    private final Runnable inventoryChanged = this::observeInstances;
    private InstanceManager observedManager;

    public DesktopNetworkOverviewProvider(RemotelyClient client, DesktopNetworkManager manager) {
        this.client = Objects.requireNonNull(client, "client");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.iconManager = new ServerIconManager(new DesktopServerIconProvider(DesktopRemotelyPaths.appDir()));
    }

    @Override
    public Async<OverviewState> load(String networkId) {
        return Async.supplyAsync(() -> snapshot(networkId));
    }

    @Override
    public BooleanSupplier mutationAdmission() {
        return NetworkOverviewProvider.mutationAdmission(client);
    }

    @Override
    public Async<OverviewState> loadForServer(String serverId) {
        NetworkDefinition network = manager.getNetworkForInstance(serverId).orElse(null);
        return network == null ? NetworkOverviewProvider.unavailable("Network Is Unavailable") : load(network.networkId());
    }

    @Override
    public Async<NetworkDefinition> save(String networkId, SaveRequest request) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(current -> {
            if (!request.routingGroups().equals(current.routingGroups()) || !request.syncRealms().equals(current.syncRealms())
                    || !request.features().equals(current.features()) || !request.sharedDataPolicy().equals(current.sharedDataPolicy())) {
                requireConfiguration(current, instances());
            }
            Async<NetworkDefinition> result = Async.completed(current);
            if (!request.name().equals(current.name())) {
                result = result.thenApply(updated -> {
                    NetworkDefinition renamed = manager.save(updated.renamed(request.name()));
                    manager.reconcileInstanceBindings(instances());
                    return renamed;
                });
            }
            if (!request.routingGroups().equals(current.routingGroups())) {
                result = result.thenCompose(updated -> manager.prepareRouting(updated, request.routingGroups(), instances())
                        .thenCompose(prepared -> manager.runPreparedRouting(prepared, instances(), "Network Overview"))
                        .thenApply(job -> requireSuccess(job, "Routing Changes Did Not Finish"))
                        .thenApply(ignored -> requireNetwork(networkId)));
            }
            if (!request.syncRealms().equals(current.syncRealms())
                    || !request.features().equals(current.features())
                    || !request.sharedDataPolicy().equals(current.sharedDataPolicy())) {
                result = result.thenCompose(updated -> manager.prepareSharedData(updated, request.syncRealms(), request.features(), request.sharedDataPolicy(), instances())
                        .thenCompose(prepared -> manager.runPreparedRealms(prepared, instances(), "Network Overview"))
                        .thenApply(job -> requireSuccess(job, "Shared Network Changes Did Not Finish"))
                        .thenApply(ignored -> requireNetwork(networkId)));
            }
            return result;
        });
    }

    @Override
    public Async<NetworkLifecycleJob> lifecycle(String networkId, NetworkLifecycleOperation operation) {
        return Async.supplyAsync(() -> requireNetwork(networkId))
                .thenCompose(network -> manager.runLifecycle(network, instances(), operation, "Network Overview"));
    }

    @Override
    public Async<NetworkLifecycleJob> memberLifecycle(String networkId, String memberId, NetworkLifecycleOperation operation) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            NetworkMember member = network.members().stream().filter(value -> value.nodeId().equals(memberId)).findFirst().orElse(null);
            return member == null ? NetworkOverviewProvider.unavailable("Server Is Unavailable")
                    : manager.runMemberLifecycle(network, member, instances(), operation, "Network Overview");
        });
    }

    @Override
    public Async<NetworkJob> attach(String networkId, AttachRequest request) {
        BooleanSupplier current = mutationAdmission();
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireAdmission(current);
            List<Instance> snapshot = instances();
            requireConfiguration(network, snapshot);
            Instance instance = findInstance(snapshot, request.serverId());
            if (instance == null) {
                return NetworkOverviewProvider.unavailable("Server Is Unavailable");
            }
            ServerScreenHost host = client.getHost().serverScreenHost(client);
            requireAttachment(network, instance, host);
            String address = defaultAddress(network, instance);
            int port = observedPort(instance, 25566);
            return requireStopped(network, snapshot, instance, host).thenCompose(ignored -> Async.supplyAsync(() -> {
                requireAdmission(current);
                requireRevision(network);
                requireAttachment(network, instance, host);
                if (request.installReSync()) {
                    NetworkReSyncSetup.SetupResult result = NetworkReSyncSetup.installLatest(List.of(instance));
                    if (!result.successful()) throw new IllegalStateException(result.failureMessage());
                }
                return null;
            })).thenCompose(ignored -> {
                requireAdmission(current);
                requireRevision(network);
                return manager.prepareAttach(network, instance, instance.getName(), NetworkMemberRole.CUSTOM, request.joinRule(), address,
                        port, 0, request.installReSync(), instances(), List.of());
            }).thenCompose(prepared -> {
                requireAdmission(current);
                return manager.runPreparedAttach(prepared, instances(), "Network Overview");
            });
        });
    }

    @Override
    public Async<NetworkJob> attachExternal(String networkId, ExternalAttachRequest request) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireConfiguration(network, instances());
            return manager.prepareExternalAttach(network, request.name(), NetworkMemberRole.CUSTOM,
                    request.joinRule(), request.address(), request.port(), request.capacity(), instances(), List.of())
                    .thenCompose(prepared -> manager.runPreparedAttach(prepared, instances(), "Network Overview"));
        });
    }

    @Override
    public Async<NetworkJob> detach(String networkId, String memberId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireConfiguration(network, instances());
            NetworkMember member = network.members().stream().filter(value -> value.nodeId().equals(memberId)).findFirst().orElse(null);
            if (member == null) {
                return NetworkOverviewProvider.unavailable("Server Is Unavailable");
            }
            Instance instance = findInstance(member.instanceId());
            if (member.isManaged() && instance == null) {
                return NetworkOverviewProvider.unavailable("Server Is Unavailable");
            }
            return member.isManaged()
                    ? manager.detachSafely(network, instance, instances(), "Network Overview")
                    : manager.detachExternalSafely(network, member, instances(), "Network Overview");
        });
    }

    @Override
    public Async<NetworkJob> dissolve(String networkId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireConfiguration(network, instances());
            return manager.dissolveSafely(network, instances(), "Network Overview");
        });
    }

    @Override
    public Async<NetworkPreflightReport> preflight(String networkId) {
        return Async.supplyAsync(() -> requireNetwork(networkId))
                .thenCompose(network -> manager.runPreflight(network, instances()));
    }

    @Override
    public Async<NetworkJob> rotateSecret(String networkId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            List<Instance> snapshot = instances();
            requireConfiguration(network, snapshot);
            return applyModernForwarding(network, snapshot);
        });
    }

    @Override
    public Async<NetworkJob> reconcile(String networkId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            List<Instance> snapshot = instances();
            requireConfiguration(network, snapshot);
            manager.reconcileInstanceBindings(snapshot);
            return manager.runJob(network, snapshot, List.of(), NetworkJobType.RECONCILE, "Network Overview");
        });
    }

    private Async<NetworkJob> applyModernForwarding(NetworkDefinition network, List<Instance> instances) {
        return manager.prepareSecretRotation(network, instances)
                .thenCompose(prepared -> manager.runPreparedSecretRotation(prepared, instances, "Network Overview")
                        .whenComplete((job, failure) -> {
                            if (failure != null) manager.discardPreparedSecretRotation(prepared);
                        }));
    }

    @Override
    public NetworkCapability enrollmentCapability() {
        return NetworkCapability.supported("enrollmentRenewal", "desktop");
    }

    @Override
    public Async<NetworkJob> renewServerEnrollment(String networkId, String nodeId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network ->
                manager.renewServerEnrollment(network, nodeId, instances(), "Network Overview"));
    }

    @Override
    public Async<NetworkJob> resumeJob(String networkId, String jobId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireConfiguration(network, instances());
            return manager.resumeJob(jobId, instances(), List.of());
        });
    }

    @Override
    public Async<NetworkJob> rollbackJob(String networkId, String jobId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireConfiguration(network, instances());
            return manager.rollbackJob(jobId, instances());
        });
    }

    @Override
    public Async<NetworkLifecycleJob> resumeLifecycle(String networkId, String jobId) {
        return manager.resumeLifecycle(jobId, instances());
    }

    @Override
    public Async<Void> runtimeNodeMode(String networkId, String nodeId, NetworkRuntimeNodeStatus status) {
        return manager.setRuntimeNodeMode(networkId, nodeId, status);
    }

    @Override
    public Async<NetworkJob> installReSync(String networkId) {
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireConfiguration(network, instances());
            List<Instance> targets = network.members().stream().filter(NetworkMember::isManaged).map(NetworkMember::instanceId)
                    .map(this::findInstance).filter(Objects::nonNull).distinct().toList();
            List<String> backendIds = network.members().stream().filter(member -> member.isManaged() && !member.isProxy())
                    .map(NetworkMember::instanceId).toList();
            return Async.supplyAsync(() -> NetworkReSyncSetup.installLatest(targets))
                    .thenApply(result -> {
                        if (!result.successful()) {
                            throw new IllegalStateException(result.failureMessage());
                        }
                        return network;
                    })
                    .thenCompose(updated -> manager.enableReSyncSafely(updated, backendIds, instances(), "Network Overview"));
        });
    }

    @Override
    public Async<Void> executeProxyCommand(String networkId, String command) {
        return manager.executeRuntimeProxyCommand(networkId, command);
    }

    @Override
    public Async<Void> broadcastMessage(String networkId, String message) {
        return manager.broadcastRuntimeMessage(networkId, message);
    }

    @Override
    public Async<NetworkCreationContext> serverCreationContext(String networkId) {
        NetworkDefinition network = requireNetwork(networkId);
        Instance proxy = findInstance(network.proxyInstanceId());
        if (proxy == null) return NetworkOverviewProvider.unavailable("Proxy Is Unavailable");
        BackendConfig backend = proxy.getBackendConfig();
        if (backend == null || "LOCAL".equalsIgnoreCase(backend.type)) return Async.completed(new NetworkCreationContext(false, null));
        if (!"SSH".equalsIgnoreCase(backend.type)) return NetworkOverviewProvider.unavailable("Create Servers Through This Server's Provider");
        ServerScreenHost host = client.getHost().serverScreenHost(client);
        String hostId = backend.credentials == null ? "" : backend.credentials.getOrDefault("hostId", "");
        String address = backend.credentials == null ? "" : backend.credentials.getOrDefault("host", "");
        String port = backend.credentials == null ? "22" : backend.credentials.getOrDefault("port", "22");
        String user = backend.credentials == null ? "" : backend.credentials.getOrDefault("user", "");
        return host.remoteHosts().thenApply(hosts -> {
            List<ServerScreenHost.HostView> matching = hosts.stream().filter(value -> !value.panel() && "SSH".equalsIgnoreCase(value.type()))
                    .filter(value -> !hostId.isBlank() ? value.id().equals(hostId) : !address.isBlank()
                            && value.address().equalsIgnoreCase(address) && Integer.toString(value.port()).equals(port) && value.user().equals(user)).toList();
            if (matching.size() != 1) throw new IllegalStateException("The Network's SSH Host Is Unavailable");
            return new NetworkCreationContext(false, matching.getFirst());
        });
    }

    @Override
    public Async<List<ServerView>> availableServers(String networkId) {
        return load(networkId).thenApply(state -> {
            NetworkMember proxy = state.network().proxyMember();
            if (proxy == null) return List.of();
            List<String> attached = manager.getNetworks().stream().flatMap(value -> value.members().stream()).map(NetworkMember::instanceId).toList();
            ServerScreenHost host = client.getHost().serverScreenHost(client);
            return instances().stream().filter(instance -> instance != null && !instance.isHidden() && !instance.isProxyServer()
                    && proxy.hostScope().equals(NetworkHostScope.resolve(instance)) && !attached.contains(instance.getInstanceId()))
                    .filter(instance -> host.networkMemberAvailability(host.serverView(instance)).available())
                    .filter(instance -> supportsBackend(state.network(), instance))
                    .map(instance -> serverView(state.network(), instance)).toList();
        });
    }

    @Override
    public ServerScreenHost.ActionAvailability serverDraftAvailability(String networkId, ServerConfigurationTarget target, NetworkMemberSource.Draft source) {
        return target != null && target.raw() instanceof Instance instance && supportsBackend(requireNetwork(networkId), instance)
                ? ServerScreenHost.ActionAvailability.enabled()
                : ServerScreenHost.ActionAvailability.disabled("Choose A Backend With Supported Forwarding For This Network");
    }

    @Override
    public Async<Void> validateServerAddition(String networkId) {
        BooleanSupplier current = mutationAdmission();
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireAdmission(current);
            List<Instance> snapshot = instances();
            requireConfiguration(network, snapshot);
            return requireStopped(network, snapshot, null, client.getHost().serverScreenHost(client)).thenApply(ignored -> {
                requireAdmission(current);
                requireRevision(network);
                return null;
            });
        });
    }

    @Override
    public NetworkCapability resourcesCapability() {
        return NetworkOverviewProvider.resourcesCapability(client);
    }

    @Override
    public Async<Void> openResources(Screen current, String networkId, String nodeId, BooleanSupplier admission) {
        return Async.supplyAsync(() -> requireNetwork(networkId))
                .thenCompose(network -> NetworkOverviewProvider.openResources(client, network, nodeId, admission));
    }

    @Override
    public Async<Void> openResources(Screen current, String networkId, String nodeId, String serverId, String apiKey, BooleanSupplier admission) {
        return Async.supplyAsync(() -> requireNetwork(networkId))
                .thenCompose(network -> NetworkOverviewProvider.openResources(client, network, nodeId, serverId, apiKey, admission));
    }

    @Override
    public void openServer(Screen current, String serverId) {
        Instance instance = findInstance(serverId);
        if (instance == null) {
            throw new UnsupportedOperationException("Server Is Unavailable");
        }
        client.openInstanceInTerminal(current, instance);
    }

    @Override
    public void addListener(Consumer<OverviewState> listener) {
        if (listener == null || listeners.containsKey(listener)) {
            return;
        }
        Consumer<List<NetworkDefinition>> callback = ignored -> loadForListener(listener);
        listeners.put(listener, callback);
        manager.addListener(callback);
    }

    @Override
    public void removeListener(Consumer<OverviewState> listener) {
        Consumer<List<NetworkDefinition>> callback = listeners.remove(listener);
        if (callback != null) {
            manager.removeListener(callback);
        }
    }

    @Override
    public void addRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        manager.addRuntimeListener(listener);
    }

    @Override
    public void removeRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        manager.removeRuntimeListener(listener);
    }

    @Override
    public synchronized void addServerStateListener(BiConsumer<String, String> listener) {
        if (listener == null || serverStateListeners.contains(listener)) return;
        serverStateListeners.add(listener);
        if (observedManager == null) {
            observedManager = Rebase.get().getInstanceManager();
            observedManager.addChangeListener(inventoryChanged);
        }
        observeInstances();
    }

    @Override
    public synchronized void removeServerStateListener(BiConsumer<String, String> listener) {
        serverStateListeners.remove(listener);
        if (!serverStateListeners.isEmpty()) return;
        if (observedManager != null) observedManager.removeChangeListener(inventoryChanged);
        observedManager = null;
        observedInstances.forEach(Instance::removeStateListener);
        observedInstances.clear();
    }

    private synchronized void observeInstances() {
        if (observedManager == null) return;
        NetworkDefinition network = manager.getNetwork(loadedNetworkId).orElse(null);
        List<Instance> current = network == null ? List.of() : observedManager.getAllInstances().stream()
                .filter(instance -> network.members().stream().anyMatch(member -> member.isManaged() && member.instanceId().equals(instance.getInstanceId()))).toList();
        observedInstances.entrySet().removeIf(entry -> {
            if (current.contains(entry.getKey())) return false;
            entry.getKey().removeStateListener(entry.getValue());
            publishServerState(entry.getKey().getInstanceId(), "");
            return true;
        });
        for (Instance instance : current) {
            if (!observedInstances.containsKey(instance)) {
                Consumer<InstanceState> listener = state -> serverStateChanged(instance);
                observedInstances.put(instance, listener);
                instance.addStateListener(listener);
            }
            InstanceState state = instance.getState();
            publishServerState(instance.getInstanceId(), state == null ? "" : state.name());
        }
    }

    private synchronized void serverStateChanged(Instance instance) {
        if (!observedInstances.containsKey(instance)) return;
        InstanceState state = instance.getState();
        publishServerState(instance.getInstanceId(), state == null ? "" : state.name());
    }

    private synchronized void publishServerState(String serverId, String state) {
        for (BiConsumer<String, String> listener : List.copyOf(serverStateListeners)) listener.accept(serverId, state);
    }

    @Override
    public boolean available() {
        return manager.available();
    }

    private void loadForListener(Consumer<OverviewState> listener) {
        String networkId = listenerNetworkId(listener);
        if (networkId.isBlank()) {
            return;
        }
        load(networkId).whenComplete((state, failure) -> {
            if (failure == null && state != null) {
                listener.accept(state);
            }
        });
    }

    private String listenerNetworkId(Consumer<OverviewState> listener) {
        return loadedNetworkId;
    }

    private OverviewState snapshot(String networkId) {
        NetworkDefinition network = requireNetwork(networkId);
        loadedNetworkId = networkId == null ? "" : networkId;
        observeInstances();
        List<Instance> instances = instances();
        List<ServerView> servers = instances.stream().map(instance -> serverView(network, instance)).toList();
        Map<String, Boolean> installed = new LinkedHashMap<>();
        network.members().stream().filter(NetworkMember::isManaged).map(NetworkMember::instanceId).distinct().forEach(instanceId -> {
            Instance instance = findInstance(instances, instanceId);
            if (instance != null) {
                installed.put(instanceId, NetworkReSyncSetup.isInstalled(instance));
            }
        });
        return new OverviewState(network, servers, manager.discover(network, instances, List.of()), manager.getRuntimeSnapshot(networkId),
                manager.getIncidents(networkId), manager.getLifecycleJobManager().getJobs(networkId), manager.getJobManager().getJobs(networkId),
                manager.getTransferFailureHeat(networkId), installed, capabilities(network, instances));
    }

    private Map<String, NetworkCapability> capabilities(NetworkDefinition network, Collection<Instance> instances) {
        String issue = configurationIssue(network, instances);
        Map<String, NetworkCapability> capabilities = new LinkedHashMap<>();
        for (String operation : List.of("save", "membership", "externalMembership", "secretRotation", "reconcile", "jobRecovery", "resync", "dissolve")) {
            capabilities.put(operation, issue.isBlank() ? NetworkCapability.supported(operation, "desktop")
                    : NetworkCapability.unavailable(operation, issue, "desktop"));
        }
        for (String operation : List.of("preflight", "lifecycle", "memberLifecycle", "lifecycleRecovery", "runtimeControl", "command", "broadcast")) {
            capabilities.put(operation, NetworkCapability.supported(operation, "desktop"));
        }
        Instance proxy = findInstance(instances, network.proxyInstanceId());
        NetworkMember proxyMember = network.members().stream().filter(member -> member.isProxy() && member.isManaged()).findFirst().orElse(null);
        String enrollmentIssue = proxy == null || proxyMember == null ? "A Managed Proxy Is Required For Server Enrollment Renewal" : "";
        if (enrollmentIssue.isBlank()) {
            ServerBackend backend = proxy.getBackend();
            if (backend == null || backend.getFileSystem() == null || !backend.getFileSystem().supportsAtomicWrites()) {
                enrollmentIssue = "Safe Configuration Writes Are Unavailable For The Proxy";
            }
        }
        capabilities.put("enrollmentRenewal", enrollmentIssue.isBlank() ? enrollmentCapability()
                : NetworkCapability.unavailable("enrollmentRenewal", enrollmentIssue, "desktop"));
        return Map.copyOf(capabilities);
    }

    public static String configurationIssue(NetworkDefinition network, Collection<Instance> instances) {
        for (NetworkMember member : network.members()) {
            if (!member.isManaged()) continue;
            Instance instance = instances == null ? null : instances.stream().filter(value -> value != null && member.instanceId().equals(value.getInstanceId())).findFirst().orElse(null);
            if (instance == null) return "Managed Server " + member.routeName() + " Is Unavailable For Safe Configuration Changes";
            try {
                ServerBackend backend = instance.getBackend();
                if (backend == null || backend.getFileSystem() == null || !backend.getFileSystem().supportsAtomicWrites()) {
                    return "Safe Configuration Writes Are Unavailable For " + instance.getName();
                }
            } catch (RuntimeException exception) {
                return "Safe Configuration Writes Are Unavailable For " + instance.getName();
            }
        }
        return "";
    }

    private void requireConfiguration(NetworkDefinition network, Collection<Instance> instances) {
        String issue = configurationIssue(network, instances);
        if (!issue.isBlank()) throw new IllegalStateException(issue);
    }

    private static boolean supportsBackend(NetworkDefinition network, Instance instance) {
        return !instance.isProxyServer() && (network.forwarding().mode() != ForwardingMode.MODERN || NetworkDiscoveryService.supportsModernForwarding(instance));
    }

    private void requireAttachment(NetworkDefinition network, Instance instance, ServerScreenHost host) {
        NetworkMember proxy = network.proxyMember();
        if (instance.isHidden() || proxy == null || !proxy.hostScope().equals(NetworkHostScope.resolve(instance))) {
            throw new IllegalStateException("Choose A Server On This Network's Host");
        }
        ServerScreenHost.ActionAvailability availability = host.networkMemberAvailability(host.serverView(instance));
        if (!availability.available()) throw new IllegalStateException(availability.reason());
        if (!supportsBackend(network, instance)) throw new IllegalStateException("Choose A Backend With Supported Forwarding For This Network");
    }

    private Async<Void> requireStopped(NetworkDefinition network, List<Instance> snapshot, Instance selected, ServerScreenHost host) {
        Map<String, Instance> targets = new LinkedHashMap<>();
        for (NetworkMember member : network.members()) {
            if (!member.isManaged()) continue;
            Instance instance = findInstance(snapshot, member.instanceId());
            if (instance == null) return NetworkOverviewProvider.unavailable("Managed Server Is Unavailable");
            targets.put(instance.getInstanceId(), instance);
        }
        if (selected != null) targets.put(selected.getInstanceId(), selected);
        List<Async<Void>> checks = new ArrayList<>();
        for (Instance instance : targets.values()) {
            BackendConfig backend = instance.getBackendConfig();
            boolean local = backend == null || backend.type == null || backend.type.isBlank() || "LOCAL".equalsIgnoreCase(backend.type);
            if (local) {
                checks.add(host.localStatus(instance).thenApply(status -> {
                    if (status == null || !status.controllerAvailable() || host.isStaleLocalStatus(instance, status)) {
                        throw new IllegalStateException("Could Not Check Whether " + instance.getName() + " Has Stopped");
                    }
                    if (status.hasActiveProcesses() || !status.noKnownSession() && !status.stoppedWithoutProcesses()) {
                        throw new IllegalStateException("Stop Network Servers And The Selected Server Before Adding A Server");
                    }
                    return null;
                }));
            } else {
                checks.add(JvmAsyncBridge.fromFuture(InstanceApi.of(instance).console().getStatus()).thenApply(status -> {
                    if (status == null) throw new IllegalStateException("Could Not Check Whether " + instance.getName() + " Has Stopped");
                    if (status.state() != InstanceState.STOPPED && status.state() != InstanceState.CRASHED) {
                        throw new IllegalStateException("Stop Network Servers And The Selected Server Before Adding A Server");
                    }
                    return null;
                }));
            }
        }
        return JvmAsyncBridge.fromFuture(JvmAsyncBridge.toFuture(Async.allOf(checks.toArray(Async[]::new))).orTimeout(10, TimeUnit.SECONDS));
    }

    private void requireRevision(NetworkDefinition network) {
        if (requireNetwork(network.networkId()).revision() != network.revision()) {
            throw new IllegalStateException("The Network Changed. Reopen Server Setup");
        }
    }

    private static void requireAdmission(BooleanSupplier current) {
        if (!current.getAsBoolean()) throw new IllegalStateException("Server Setup Changed. Reopen The Network");
    }

    private NetworkDefinition requireNetwork(String networkId) {
        return manager.getNetwork(networkId).orElseThrow(() -> new IllegalStateException("Network Is Unavailable"));
    }

    private List<Instance> instances() {
        try {
            InstanceManager instanceManager = Rebase.get().getInstanceManager();
            return List.copyOf(instanceManager.getAllInstances());
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private Instance findInstance(String id) {
        return findInstance(instances(), id);
    }

    private Instance findInstance(Collection<Instance> instances, String id) {
        return instances.stream().filter(instance -> instance != null && instance.getInstanceId().equals(id)).findFirst().orElse(null);
    }

    private ServerView serverView(NetworkDefinition network, Instance instance) {
        NetworkMember member = network.members().stream().filter(value -> value.instanceId().equals(instance.getInstanceId())).findFirst().orElse(null);
        String backend = instance.getBackendConfig() == null ? "LOCAL" : instance.getBackendConfig().type;
        InstanceState state = instance.getState();
        Identifier icon = iconManager.getIconId(instance);
        return new ServerView(instance.getInstanceId(), instance.getName(), member != null && member.isProxy(), member == null || member.isManaged(),
                NetworkHostScope.resolve(instance), member == null ? defaultAddress(network, instance) : member.address(),
                member == null ? observedPort(instance, 25566) : member.port(), state == null ? "" : state.name(), icon);
    }

    private String defaultAddress(NetworkDefinition network, Instance instance) {
        NetworkMember proxy = network.proxyMember();
        if (proxy != null && proxy.hostScope().equals(NetworkHostScope.resolve(instance))) {
            return "127.0.0.1";
        }
        if (instance.getBackendConfig() == null || instance.getBackendConfig().credentials == null) {
            return "";
        }
        return instance.getBackendConfig().credentials.getOrDefault("host", "");
    }

    private int observedPort(Instance instance, int fallback) {
        try {
            int port = Integer.parseInt(instance.getServerProperties().getProperty("server-port", String.valueOf(fallback)).trim());
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private NetworkJob requireSuccess(NetworkJob job, String message) {
        if (job == null || job.status() != NetworkJobStatus.SUCCEEDED) {
            throw new IllegalStateException(job == null ? message : job.message());
        }
        return job;
    }
}
