package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import redxax.oxy.remotely.network.DesktopNetworkManager;
import redxax.oxy.remotely.network.ForwardingMode;
import redxax.oxy.remotely.network.NetworkDefinition;
import redxax.oxy.remotely.network.NetworkDiscoveryResult;
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
import restudio.rebase.Rebase;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.instance.InstanceState;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.util.Identifier;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class DesktopNetworkOverviewProvider implements NetworkOverviewProvider {
    private final RemotelyClient client;
    private final DesktopNetworkManager manager;
    private final ServerIconManager iconManager;
    private final Map<Consumer<OverviewState>, Consumer<List<NetworkDefinition>>> listeners = new IdentityHashMap<>();
    private volatile String loadedNetworkId = "";

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
        return Async.supplyAsync(() -> requireNetwork(networkId)).thenCompose(network -> {
            requireConfiguration(network, instances());
            Instance instance = findInstance(request.serverId());
            if (instance == null) {
                return NetworkOverviewProvider.unavailable("Server Is Unavailable");
            }
            String address = defaultAddress(network, instance);
            int port = observedPort(instance, 25566);
            Async<Void> setup = request.installReSync()
                    ? Async.supplyAsync(() -> NetworkReSyncSetup.installLatest(List.of(instance)))
                        .thenApply(result -> {
                            if (!result.successful()) {
                                throw new IllegalStateException(result.failureMessage());
                            }
                            return null;
                        })
                    : Async.completed(null);
            return setup.thenCompose(ignored -> manager.prepareAttach(network, instance, instance.getName(), NetworkMemberRole.CUSTOM, request.joinRule(), address,
                    port, 0, request.installReSync(), instances(), List.of()))
                    .thenCompose(prepared -> manager.runPreparedAttach(prepared, instances(), "Network Overview"));
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
    public Async<List<ServerView>> availableServers(String networkId) {
        return load(networkId).thenApply(state -> {
            Map<String, NetworkMember> members = new LinkedHashMap<>();
            state.network().members().forEach(member -> members.put(member.instanceId(), member));
            return state.servers().stream().filter(server -> !server.proxy()).filter(server -> !members.containsKey(server.id())).toList();
        });
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
