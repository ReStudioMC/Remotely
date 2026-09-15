package redxax.oxy.remotely.network;

import redxax.oxy.remotely.util.TaskSchedulers;

import redxax.oxy.remotely.util.AsyncTools;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.DesktopRemotelyPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapter;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapters;
import redxax.oxy.remotely.network.config.DesktopStructuredDocumentParser;
import restudio.rebase.api.unified.InstanceApi;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rebase.platform.jvm.JvmClock;
import restudio.rebase.platform.jvm.JvmTaskScheduler;
import restudio.rebase.platform.jvm.JvmWebSocketTransport;
import restudio.rescreen.platform.websocket.WebSocketTransport;

import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkSnapshotMetadata;
import restudio.resync.network.NetworkStateReconciliationRequest;
import restudio.resync.network.PlayerTransfer;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;


import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class DesktopNetworkManager implements NetworkManager<Instance, PortReservation> {
    static {
        NetworkHostScope.installResolver(DesktopNetworkHostScope::describe);
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final NetworkRepository repository;
    private final NetworkSecretStore secretStore;
    private final NetworkRuntimeMonitor runtimeMonitor;
    private final TaskScheduler runtimeScheduler;
    private final boolean ownsRuntimeScheduler;
    private final NetworkIncidentManager incidentManager;
    private final NetworkPortAllocator portAllocator;
    private final NetworkProviderAllocationService providerAllocationService;
    private final NetworkDiscoveryService discoveryService;
    private final NetworkDesiredStatePlanner desiredStatePlanner;
    private final NetworkDetachPlanner detachPlanner;
    private final NetworkAdoptionService adoptionService;
    private final NetworkConfigurationTransaction configurationTransaction;
    private final NetworkJobManager jobManager;
    private final NetworkLifecycleJobManager lifecycleJobManager;
    private final NetworkPreflightManager preflightManager;
    private final Map<String, NetworkDefinition> networks = new LinkedHashMap<>();
    private final Set<String> mutationLocks = new LinkedHashSet<>();
    private final Object mutationGuard = new Object();
    private final List<Consumer<List<NetworkDefinition>>> listeners = BrowserSafeState.list();
    private volatile NetworkCatalog networkCatalog = NetworkCatalog.empty();
    private volatile List<Instance> runtimeInstances = List.of();
    private volatile String loadError = "";

    public DesktopNetworkManager(Path applicationDirectory) {
        this(applicationDirectory, new JvmWebSocketTransport(), new JvmTaskScheduler(), new JvmClock(), true);
    }

    public DesktopNetworkManager(Path applicationDirectory, WebSocketTransport webSocketTransport) {
        this(applicationDirectory, webSocketTransport, new JvmTaskScheduler(), new JvmClock(), true);
    }

    public DesktopNetworkManager(Path applicationDirectory, WebSocketTransport webSocketTransport,
                          TaskScheduler scheduler, Clock clock) {
        this(applicationDirectory, webSocketTransport, scheduler, clock, false);
    }

    private DesktopNetworkManager(Path applicationDirectory, WebSocketTransport webSocketTransport,
                           TaskScheduler scheduler, Clock clock, boolean ownsRuntimeScheduler) {
        Path dataDirectory = DesktopRemotelyPaths.dataDir(applicationDirectory);
        this.repository = new NetworkRepository(dataDirectory);
        this.secretStore = new NetworkSecretStore();
        this.runtimeScheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsRuntimeScheduler = ownsRuntimeScheduler;
        this.runtimeMonitor = new NetworkRuntimeMonitor(secretStore, webSocketTransport, scheduler, clock);
        this.incidentManager = new NetworkIncidentManager(dataDirectory);
        this.runtimeMonitor.addListener(this::observeRuntimeIncidents);
        this.runtimeMonitor.addEventListener(this::observeRuntimeEvent);
        this.portAllocator = new NetworkPortAllocator();
        this.providerAllocationService = new NetworkProviderAllocationService();
        this.discoveryService = new NetworkDiscoveryService(portAllocator);
        this.desiredStatePlanner = new NetworkDesiredStatePlanner();
        this.detachPlanner = new NetworkDetachPlanner();
        this.adoptionService = new NetworkAdoptionService();
        this.configurationTransaction = new NetworkConfigurationTransaction();
        this.jobManager = new NetworkJobManager(dataDirectory, configurationTransaction);
        this.lifecycleJobManager = new NetworkLifecycleJobManager(dataDirectory, runtimeMonitor);
        this.preflightManager = new NetworkPreflightManager(dataDirectory, new NetworkPreflightService(discoveryService, desiredStatePlanner, secretStore, configurationTransaction));
        reload();
    }

    public synchronized void reload() {
        try {
            List<NetworkDefinition> loaded = repository.loadAll();
            validateLoadedMembership(loaded);
            networks.clear();
            loaded.forEach(network -> networks.put(network.networkId(), network));
            loadError = "";
            notifyListeners();
        } catch (NetworkPersistenceException | IllegalArgumentException exception) {
            loadError = exception.getMessage() == null ? "Failed to load networks" : exception.getMessage();
            runtimeMonitor.refresh(List.of(), runtimeInstances);
        }
    }

    public List<NetworkDefinition> getNetworks() {
        return networkCatalog.networks();
    }

    public Optional<NetworkDefinition> getNetwork(String networkId) {
        return Optional.ofNullable(networkCatalog.byId().get(networkId));
    }

    public Optional<NetworkDefinition> getNetworkForInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(networkCatalog.byInstanceId().get(instanceId));
    }

    public synchronized Optional<NetworkJob> getRecoverableCreationJob(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        return jobManager.getJobs().stream().filter(job -> job.type() == NetworkJobType.QUICK_CREATE && job.canResume()).filter(job -> {
            try {
                return creationCandidateFromContext(job.context()).members().stream().anyMatch(member -> member.instanceId().equals(instanceId));
            } catch (RuntimeException exception) {
                return false;
            }
        }).findFirst();
    }

    public synchronized NetworkDefinition save(NetworkDefinition network) {
        return saveInternal(network, false);
    }

    private NetworkDefinition saveInternal(NetworkDefinition network, boolean allowLocked) {
        Objects.requireNonNull(network, "Network is required");
        if (!allowLocked && mutationLocks.contains(network.networkId())) {
            throw new IllegalStateException("Network has an active operation");
        }
        NetworkValidator.requireValid(network);
        validateGlobalMembership(List.of(network), network.networkId());
        NetworkDefinition existing = networks.get(network.networkId());
        if (existing != null && network.revision() < existing.revision()) {
            throw new IllegalArgumentException("Network revision cannot move backwards");
        }
        if (existing != null && network.revision() == existing.revision() && !network.equals(existing)) {
            throw new IllegalArgumentException("Changed network content requires a new revision");
        }
        repository.save(network);
        networks.put(network.networkId(), network);
        notifyListeners();
        return network;
    }

    public synchronized Async<NetworkAdoptionReport> scanForAdoption(Instance proxy, Collection<Instance> instances) {
        return adoptionService.scan(proxy, instances, getNetworks());
    }

    public synchronized Async<NetworkAdoptionReport> scanLegacyMigration(Instance proxy, Collection<Instance> instances) {
        return adoptionService.scanLegacyMigration(proxy, instances, getNetworks());
    }

    public synchronized Async<NetworkCreationPreparedPlan> prepareCreation(NetworkCreationRequest request, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        Objects.requireNonNull(request, "Network creation request is required");
        List<Instance> snapshot = instances == null ? List.of() : instances.stream().filter(Objects::nonNull).toList();
        Set<String> selectedIds = new LinkedHashSet<>();
        selectedIds.add(request.proxyInstanceId());
        request.backends().stream().filter(member -> member.management() == NetworkMemberManagement.MANAGED).map(NetworkCreationMember::instanceId).forEach(selectedIds::add);
        return resolveProviderAllocations(instancesForIds(snapshot, selectedIds)).thenCompose(allocations -> prepareCreationResolved(request, snapshot, externalReservations, allocations));
    }

    private synchronized Async<NetworkCreationPreparedPlan> prepareCreationResolved(NetworkCreationRequest request, Collection<Instance> instances, Collection<PortReservation> externalReservations, Map<String, NetworkProviderAllocation> providerAllocations) {
        NetworkSecretStore.Secret secret = secretStore.createForwardingSecret();
        NetworkDefinition candidate;
        try {
            candidate = buildCreationCandidate(request, instances, externalReservations, secret.reference(), providerAllocations);
        } catch (RuntimeException exception) {
            secretStore.deleteForwardingSecret(secret.reference());
            return Async.failed(exception);
        }
        try {
            NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(candidate, instances, externalReservations)), secretStore);
            if (!plan.canApply()) {
                secretStore.deleteForwardingSecret(secret.reference());
                secretStore.deleteEnrollmentTokens(candidate);
                return Async.failed(new IllegalStateException(plan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("Network creation is blocked")));
            }
            return configurationTransaction.prepare(plan, instances).handle((prepared, throwable) -> {
                if (throwable != null) {
                    secretStore.deleteForwardingSecret(secret.reference());
                    secretStore.deleteEnrollmentTokens(candidate);
                    throw new IllegalStateException(throwable);
                }
                return new NetworkCreationPreparedPlan(candidate, prepared);
            });
        } catch (RuntimeException exception) {
            secretStore.deleteForwardingSecret(secret.reference());
            secretStore.deleteEnrollmentTokens(candidate);
            return Async.failed(exception);
        }
    }

    public synchronized void discardPreparedCreation(NetworkCreationPreparedPlan creationPrepared) {
        if (creationPrepared == null || networks.containsKey(creationPrepared.candidate().networkId())) {
            return;
        }
        boolean jobOwnsSecret = jobManager.getJobs(creationPrepared.candidate().networkId()).stream().anyMatch(job -> job.context().getOrDefault("secretReference", "").equals(creationPrepared.candidate().forwarding().secretReference()));
        if (!jobOwnsSecret) {
            secretStore.deleteForwardingSecret(creationPrepared.candidate().forwarding().secretReference());
            secretStore.deleteEnrollmentTokens(creationPrepared.candidate());
        }
    }

    public synchronized Async<NetworkJob> runPreparedCreation(NetworkCreationPreparedPlan creationPrepared, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(creationPrepared, "Prepared network creation is required");
        List<Instance> snapshot = instances == null ? List.of() : instances.stream().filter(Objects::nonNull).toList();
        Set<String> selectedIds = creationPrepared.candidate().members().stream().filter(NetworkMember::isManaged).map(NetworkMember::instanceId).collect(Collectors.toCollection(LinkedHashSet::new));
        return resolveProviderAllocations(instancesForIds(snapshot, selectedIds)).thenCompose(allocations -> runPreparedCreationResolved(creationPrepared, snapshot, initiator, allocations));
    }

    private synchronized Async<NetworkJob> runPreparedCreationResolved(NetworkCreationPreparedPlan creationPrepared, Collection<Instance> instances, String initiator, Map<String, NetworkProviderAllocation> providerAllocations) {
        NetworkDefinition candidate = creationPrepared.candidate();
        if (networks.containsKey(candidate.networkId())) {
            return Async.failed(new IllegalStateException("Network already exists"));
        }
        validateProviderAllocations(candidate, instances, providerAllocations);
        validateGlobalMembership(List.of(candidate), candidate.networkId());
        Map<String, String> context = Map.of(
            "network", GSON.toJson(candidate),
            "secretReference", candidate.forwarding().secretReference(),
            "bindings", GSON.toJson(captureCreationBindings(candidate, instances))
        );
        return withMutationLock(candidate.networkId(), () -> jobManager.executePrepared(candidate, creationPrepared.prepared(), instances, NetworkJobType.QUICK_CREATE, initiator, context).thenCompose(job -> {
            if (job.status() != NetworkJobStatus.SUCCEEDED) {
                return Async.completed(job);
            }
            return finalizeCreation(job, instances).thenApply(unused -> job);
        }));
    }

    public synchronized Async<NetworkSecretRotationPreparedPlan> prepareSecretRotation(NetworkDefinition network, Collection<Instance> instances) {
        Objects.requireNonNull(network, "Network is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before secret rotation review started"));
        }
        if (current.forwarding().mode() != ForwardingMode.MODERN) {
            return Async.failed(new IllegalStateException("Secret Rotation Requires Modern Forwarding"));
        }
        if (current.members().stream().anyMatch(member -> !member.isManaged())) {
            return Async.failed(new IllegalStateException("Detach Or Import External Backends Before Rotating The Secret"));
        }
        try {
            requireManagedServersStopped(current, instances, "Stop Every Managed Network Server Before Rotating The Secret");
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        NetworkSecretStore.Secret secret = secretStore.createForwardingSecret();
        try {
            NetworkForwardingPolicy forwarding = new NetworkForwardingPolicy(current.forwarding().mode(), current.forwarding().proxyOnlineMode(), secret.reference(), current.forwarding().firewallVerified());
            NetworkDefinition candidate = current.withForwarding(forwarding);
            NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(candidate, instances, List.of())), secretStore);
            if (!plan.canApply()) {
                secretStore.deleteForwardingSecret(secret.reference());
                return Async.failed(new IllegalStateException(plan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("Forwarding secret rotation is blocked")));
            }
            return configurationTransaction.prepare(plan, instances).handle((prepared, throwable) -> {
                if (throwable != null) {
                    secretStore.deleteForwardingSecret(secret.reference());
                    throw new IllegalStateException(throwable);
                }
                return new NetworkSecretRotationPreparedPlan(current, candidate, prepared);
            });
        } catch (RuntimeException exception) {
            secretStore.deleteForwardingSecret(secret.reference());
            return Async.failed(exception);
        }
    }

    public synchronized void discardPreparedSecretRotation(NetworkSecretRotationPreparedPlan rotationPrepared) {
        if (rotationPrepared == null) {
            return;
        }
        NetworkDefinition current = networks.get(rotationPrepared.baseNetwork().networkId());
        if (current != null && current.revision() == rotationPrepared.candidate().revision() && current.forwarding().secretReference().equals(rotationPrepared.candidate().forwarding().secretReference())) {
            return;
        }
        boolean jobOwnsSecret = jobManager.getJobs(rotationPrepared.baseNetwork().networkId()).stream().filter(job -> job.type() == NetworkJobType.ROTATE_SECRET).anyMatch(job -> job.context().getOrDefault("newSecretReference", "").equals(rotationPrepared.candidate().forwarding().secretReference()));
        if (!jobOwnsSecret) {
            secretStore.deleteForwardingSecret(rotationPrepared.candidate().forwarding().secretReference());
        }
    }

    public synchronized Async<NetworkJob> runPreparedSecretRotation(NetworkSecretRotationPreparedPlan rotationPrepared, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(rotationPrepared, "Prepared secret rotation is required");
        NetworkDefinition current = networks.get(rotationPrepared.baseNetwork().networkId());
        if (current == null || current.revision() != rotationPrepared.baseNetwork().revision()) {
            return Async.failed(new IllegalStateException("Network changed after secret rotation review"));
        }
        NetworkDefinition candidate = rotationPrepared.candidate();
        if (!candidate.networkId().equals(current.networkId()) || candidate.revision() != current.revision() + 1 || candidate.forwarding().secretReference().equals(current.forwarding().secretReference())) {
            return Async.failed(new IllegalStateException("Prepared secret rotation is invalid"));
        }
        try {
            requireManagedServersStopped(current, instances, "Stop Every Managed Network Server Before Rotating The Secret");
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        Map<String, String> context = Map.of(
            "network", GSON.toJson(candidate),
            "oldSecretReference", current.forwarding().secretReference(),
            "newSecretReference", candidate.forwarding().secretReference()
        );
        return withMutationLock(current.networkId(), () -> jobManager.executePrepared(candidate, rotationPrepared.prepared(), instances, NetworkJobType.ROTATE_SECRET, initiator, context).thenCompose(job -> {
            if (job.status() == NetworkJobStatus.ROLLED_BACK) {
                deleteUnusedSecret(candidate.forwarding().secretReference());
                return Async.completed(job);
            }
            if (job.status() != NetworkJobStatus.SUCCEEDED) {
                return Async.completed(job);
            }
            return finalizeSecretRotation(job, instances).thenApply(unused -> job);
        }));
    }

    public synchronized NetworkAdoptionReport resolveAdoptionRoute(NetworkAdoptionReport report, String routeName, Instance instance, Collection<Instance> instances) {
        return adoptionService.resolveRoute(report, routeName, instance, instances, getNetworks());
    }

    public synchronized NetworkAdoptionReport resolveExternalAdoptionRoute(NetworkAdoptionReport report, String routeName) {
        return adoptionService.resolveExternalRoute(report, routeName);
    }

    public synchronized Async<NetworkDefinition> adoptNetwork(String name, NetworkAdoptionReport report, Collection<Instance> instances) {
        Objects.requireNonNull(report, "Adoption report is required");
        if (!report.canAdopt()) {
            return Async.failed(new IllegalStateException(report.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("Resolve adoption findings first")));
        }
        List<Instance> snapshot = instances == null ? List.of() : instances.stream().filter(Objects::nonNull).toList();
        Map<String, Instance> instancesById = indexInstances(snapshot);
        Instance proxy = instancesById.get(report.proxyInstanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Velocity proxy is unavailable"));
        }
        Set<String> selectedIds = new LinkedHashSet<>();
        selectedIds.add(proxy.getInstanceId());
        report.routes().stream().filter(route -> route.management() == NetworkMemberManagement.MANAGED).map(NetworkAdoptionRoute::instanceId).forEach(selectedIds::add);
        return resolveProviderAllocations(instancesForIds(snapshot, selectedIds)).thenCompose(allocations -> adoptNetworkResolved(name, report, instancesById, allocations));
    }

    private synchronized Async<NetworkDefinition> adoptNetworkResolved(String name, NetworkAdoptionReport report, Map<String, Instance> instancesById, Map<String, NetworkProviderAllocation> providerAllocations) {
        Instance proxy = instancesById.get(report.proxyInstanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Velocity proxy is unavailable"));
        }
        if (getNetworkForInstance(proxy.getInstanceId()).isPresent()) {
            return Async.failed(new IllegalStateException("Velocity proxy already belongs to a network"));
        }
        return adoptionService.readForwardingSecret(proxy, report).thenCompose(secret -> {
            NetworkSecretStore.Secret importedSecret = secret.isBlank() ? null : secretStore.importForwardingSecret(secret);
            try {
                NetworkDefinition network;
                synchronized (this) {
                    if (getNetworkForInstance(proxy.getInstanceId()).isPresent()) {
                        throw new IllegalStateException("Velocity proxy already belongs to a network");
                    }
                    network = buildAdoptedNetwork(name, report, instancesById, importedSecret == null ? "" : importedSecret.reference(), providerAllocations);
                    save(network);
                }
                List<InstanceBinding> previousBindings = network.members().stream().map(NetworkMember::instanceId).map(instancesById::get).filter(Objects::nonNull)
                        .map(instance -> new InstanceBinding(instance, instance.getNetworkId(), instance.getNetworkNodeId(), instance.getNetworkRevision())).toList();
                try {
                    List<Async<Void>> metadataUpdates = new ArrayList<>();
                    for (NetworkMember member : network.members()) {
                        Instance instance = instancesById.get(member.instanceId());
                        if (instance == null) {
                            continue;
                        }
                        instance.bindNetwork(network.networkId(), member.nodeId(), network.revision());
                        metadataUpdates.add(save(instance));
                    }
                    return Async.allOf(metadataUpdates.toArray(Async[]::new)).thenApply(unused -> network)
                            .exceptionallyCompose(throwable -> rollbackAdoption(network, previousBindings, importedSecret, throwable));
                } catch (RuntimeException metadataFailure) {
                    return rollbackAdoption(network, previousBindings, importedSecret, metadataFailure);
                }
            } catch (RuntimeException exception) {
                if (importedSecret != null) {
                    secretStore.deleteForwardingSecret(importedSecret.reference());
                }
                return Async.failed(exception);
            }
        });
    }

    Async<NetworkDefinition> rollbackAdoption(NetworkDefinition network, List<InstanceBinding> bindings, NetworkSecretStore.Secret importedSecret, Throwable failure) {
        Throwable cause = unwrapCompletion(failure);
        List<Async<Void>> metadataRollbacks = new ArrayList<>();
        try {
            synchronized (this) {
                repository.delete(network);
                incidentManager.delete(network.networkId());
                networks.remove(network.networkId());
                notifyListeners();
            }
            for (InstanceBinding binding : bindings) {
                binding.restore();
                metadataRollbacks.add(save(binding.instance()));
            }
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(unwrapCompletion(rollbackFailure));
            return Async.failed(cause);
        }
        return Async.allOf(metadataRollbacks.toArray(Async[]::new)).handle((unused, rollbackFailure) -> {
            if (rollbackFailure != null) {
                cause.addSuppressed(unwrapCompletion(rollbackFailure));
            } else if (importedSecret != null) {
                try {
                    secretStore.deleteForwardingSecret(importedSecret.reference());
                } catch (RuntimeException secretFailure) {
                    cause.addSuppressed(unwrapCompletion(secretFailure));
                }
            }
            throw new IllegalStateException(cause);
        });
    }

    private Throwable unwrapCompletion(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public synchronized Async<NetworkDefinition> attach(NetworkDefinition network, Instance instance, String routeName, NetworkMemberRole role, String hostScope, String address, int port, int capacity, boolean resyncEnabled) {
        return Async.failed(new IllegalStateException("Safe attach requires a reconciliation job"));
    }

    public synchronized Async<NetworkJob> updateRoutingSafely(NetworkDefinition network, List<RoutingGroup> routingGroups, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before routing update started"));
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        Instance proxy = instancesById.get(current.proxyInstanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Proxy is unavailable"));
        }
        List<RoutingGroup> normalizedGroups = routingGroups == null ? List.of() : List.copyOf(routingGroups);
        NetworkDefinition candidate = current.nextRevision(current.members(), normalizedGroups, current.syncRealms(), current.desiredState());
        NetworkValidator.requireValid(candidate);
        Map<String, String> context = Map.of("routingGroups", GSON.toJson(candidate.routingGroups()), "baseRevision", String.valueOf(current.revision()));
        return withMutationLock(current.networkId(), () -> {
            NetworkReconciliationPlan plan = routingPlan(current, candidate, instances, List.of());
            return jobManager.execute(candidate, plan, instances, NetworkJobType.ROUTING, initiator, context).thenCompose(job -> {
                if (job.status() != NetworkJobStatus.SUCCEEDED) {
                    return Async.completed(job);
                }
                return finalizeRouting(job, instances).thenApply(unused -> job);
            });
        });
    }

    public synchronized Async<NetworkRoutingPreparedPlan> prepareRouting(NetworkDefinition network, List<RoutingGroup> routingGroups, Collection<Instance> instances) {
        Objects.requireNonNull(network, "Network is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before routing review started"));
        }
        Instance proxy = indexInstances(instances).get(current.proxyInstanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Proxy is unavailable"));
        }
        List<RoutingGroup> normalizedGroups = routingGroups == null ? List.of() : List.copyOf(routingGroups);
        NetworkDefinition candidate = buildRoutingCandidate(current, normalizedGroups);
        NetworkValidator.requireValid(candidate);
        NetworkReconciliationPlan plan = routingPlan(current, candidate, instances, List.of());
        return configurationTransaction.prepare(plan, instances).thenApply(prepared -> new NetworkRoutingPreparedPlan(current, normalizedGroups, prepared));
    }

    public synchronized Async<NetworkJob> runPreparedRouting(NetworkRoutingPreparedPlan routingPrepared, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(routingPrepared, "Prepared routing changes are required");
        NetworkDefinition current = networks.get(routingPrepared.baseNetwork().networkId());
        if (current == null || current.revision() != routingPrepared.baseNetwork().revision()) {
            return Async.failed(new IllegalStateException("Network changed after routing review"));
        }
        NetworkDefinition candidate = buildRoutingCandidate(current, routingPrepared.routingGroups());
        if (candidate.revision() != routingPrepared.prepared().plan().networkRevision()) {
            return Async.failed(new IllegalStateException("Prepared routing revision is stale"));
        }
        Map<String, String> context = Map.of("routingGroups", GSON.toJson(candidate.routingGroups()), "baseRevision", String.valueOf(current.revision()));
        return withMutationLock(current.networkId(), () -> jobManager.executePrepared(candidate, routingPrepared.prepared(), instances, NetworkJobType.ROUTING, initiator, context).thenCompose(job -> {
            if (job.status() != NetworkJobStatus.SUCCEEDED) {
                return Async.completed(job);
            }
            return finalizeRouting(job, instances).thenApply(unused -> job);
        }));
    }

    public synchronized Async<NetworkRealmPreparedPlan> prepareRealms(NetworkDefinition network, List<SyncRealm> realms, Collection<Instance> instances) {
        return prepareSharedData(network, realms, network.features(), instances);
    }

    public synchronized Async<NetworkRealmPreparedPlan> prepareSharedData(NetworkDefinition network, List<SyncRealm> realms, Map<String, Boolean> features, Collection<Instance> instances) {
        return prepareSharedData(network, realms, features, network.sharedDataPolicy(), instances);
    }

    public synchronized Async<NetworkRealmPreparedPlan> prepareSharedData(NetworkDefinition network, List<SyncRealm> realms, Map<String, Boolean> features, NetworkSharedDataPolicy sharedDataPolicy, Collection<Instance> instances) {
        Objects.requireNonNull(network, "Network is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalStateException("Network changed before shared data review"));
        }
        try {
            NetworkDefinition candidate = buildSharedDataCandidate(current, realms, features, sharedDataPolicy);
            NetworkValidator.requireValid(candidate);
            NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(candidate, instances, List.of())), secretStore);
            return configurationTransaction.prepare(plan, instances).thenApply(prepared -> new NetworkRealmPreparedPlan(current, candidate.syncRealms(), candidate.features(), candidate.sharedDataPolicy(), prepared));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public synchronized Async<NetworkJob> runPreparedRealms(NetworkRealmPreparedPlan realmPrepared, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(realmPrepared, "Prepared realm changes are required");
        NetworkDefinition current = networks.get(realmPrepared.baseNetwork().networkId());
        if (current == null || current.revision() != realmPrepared.baseNetwork().revision()) {
            return Async.failed(new IllegalStateException("Network changed after shared data review"));
        }
        try {
            NetworkDefinition candidate = buildSharedDataCandidate(current, realmPrepared.realms(), realmPrepared.features(), realmPrepared.sharedDataPolicy());
            if (candidate.revision() != realmPrepared.prepared().plan().networkRevision()) {
                return Async.failed(new IllegalStateException("Prepared shared data revision is stale"));
            }
            Map<String, String> context = Map.of(
                "syncRealms", GSON.toJson(candidate.syncRealms()),
                "features", GSON.toJson(candidate.features()),
                "sharedDataPolicy", GSON.toJson(candidate.sharedDataPolicy()),
                "baseRevision", String.valueOf(current.revision())
            );
            return withMutationLock(current.networkId(), () -> reconcileItemStateTransition(current, candidate).thenCompose(unused -> jobManager.executePrepared(candidate, realmPrepared.prepared(), instances, NetworkJobType.REALMS, initiator, context)).thenCompose(job -> {
                if (job.status() != NetworkJobStatus.SUCCEEDED) {
                    return Async.completed(job);
                }
                return finalizeRealms(job, instances).thenApply(unused -> job);
            }));
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
    }

    public Async<NetworkJob> applyRealms(NetworkDefinition network, List<SyncRealm> realms, Collection<Instance> instances, String initiator) {
        return prepareRealms(network, realms, instances).thenCompose(prepared -> runPreparedRealms(prepared, instances, initiator));
    }

    public synchronized Async<NetworkJob> enableReSyncSafely(NetworkDefinition network, Collection<String> instanceIds, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalStateException("Network changed before ReSync setup"));
        }
        Set<String> requested = instanceIds == null ? Set.of() : instanceIds.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) {
            return Async.failed(new IllegalArgumentException("Choose at least one managed server for ReSync"));
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        Instance proxy = instancesById.get(current.proxyInstanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Proxy is unavailable"));
        }
        NetworkDefinition candidate;
        try {
            List<PortReservation> reservations = portAllocator.discover(getNetworks(), instances, List.of());
            candidate = buildReSyncCandidate(current, requested, proxy, reservations);
            NetworkValidator.requireValid(candidate);
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(candidate, instances, List.of())), secretStore);
        Map<String, String> context = Map.of("candidate", GSON.toJson(candidate), "baseRevision", String.valueOf(current.revision()));
        return withMutationLock(current.networkId(), () -> jobManager.execute(candidate, plan, instances, NetworkJobType.ADOPT, initiator, context).thenCompose(job -> {
            if (job.status() != NetworkJobStatus.SUCCEEDED) {
                return Async.completed(job);
            }
            return finalizeReSync(job, instances).thenApply(unused -> job);
        }));
    }

    private Async<Void> reconcileItemStateTransition(NetworkDefinition current, NetworkDefinition candidate) {
        Set<String> families = new LinkedHashSet<>();
        Set<String> nodes = new LinkedHashSet<>();
        collectChangedItemFamily(current, candidate, SyncDataFamily.INVENTORY, "inventory", families, nodes);
        collectChangedItemFamily(current, candidate, SyncDataFamily.ENDER_CHEST, "ender-chest", families, nodes);
        if (families.isEmpty()) {
            return Async.completed(null);
        }
        candidate.members().stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).map(NetworkMember::nodeId).forEach(nodes::add);
        NetworkStateReconciliationRequest request = new NetworkStateReconciliationRequest(UUID.randomUUID().toString(), nodes, families);
        return runtimeMonitor.reconcilePlayerState(current.networkId(), request);
    }

    private void collectChangedItemFamily(NetworkDefinition current, NetworkDefinition candidate, SyncDataFamily family, String wireName, Set<String> families, Set<String> nodes) {
        Set<String> currentNodes = realmNodes(current, family);
        Set<String> candidateNodes = realmNodes(candidate, family);
        if (currentNodes.equals(candidateNodes)) {
            return;
        }
        families.add(wireName);
        nodes.addAll(currentNodes);
        nodes.addAll(candidateNodes);
    }

    private Set<String> realmNodes(NetworkDefinition network, SyncDataFamily family) {
        return network.syncRealms().stream().filter(realm -> realm.dataFamilies().contains(family)).flatMap(realm -> realm.nodeIds().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Async<NetworkJob> attachSafely(NetworkDefinition network, Instance instance, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int preferredPort, int capacity, boolean resyncEnabled, Collection<Instance> instances, Collection<PortReservation> externalReservations, String initiator) {
        List<Instance> instanceSnapshot = instances == null ? List.of() : List.copyOf(instances);
        List<PortReservation> reservationSnapshot = externalReservations == null ? List.of() : List.copyOf(externalReservations);
        return AsyncTools.supply(TaskSchedulers.current(), () -> ensureForwardingSecret(network, instanceSnapshot))
            .thenCompose(future -> future)
            .thenCompose(unused -> providerAllocationService.resolve(instance))
            .thenCompose(allocation -> attachSafelyResolved(network, instance, requestedRouteName, role, routingGroupId, address, preferredPort, capacity, resyncEnabled, instanceSnapshot, reservationSnapshot, initiator, allocation));
    }

    private synchronized Async<NetworkJob> attachSafelyResolved(NetworkDefinition network, Instance instance, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int preferredPort, int capacity, boolean resyncEnabled, Collection<Instance> instances, Collection<PortReservation> externalReservations, String initiator, NetworkProviderAllocation providerAllocation) {
        ResolvedAttach resolved;
        try {
            resolved = resolveAttach(network, instance, requestedRouteName, role, routingGroupId, address, preferredPort, capacity, resyncEnabled, instances, externalReservations, providerAllocation);
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        return withMutationLock(resolved.base().networkId(), () -> {
            NetworkDiscoveryResult discovery = discoverObserved(resolved.candidate(), instances, externalReservations);
            NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discovery), secretStore);
            return configurationTransaction.prepare(plan, instances).thenCompose(prepared -> {
                NetworkMemberRestorePoint restorePoint = captureRestorePoint(resolved.member(), prepared);
                Map<String, String> context = attachContext(resolved.candidate(), resolved.member(), resolved.routingGroupId(), restorePoint);
                return jobManager.executePrepared(resolved.candidate(), prepared, instances, NetworkJobType.ATTACH, initiator, context).thenCompose(job -> finishAttach(job, instances, restorePoint));
            });
        });
    }

    public Async<NetworkAttachPreparedPlan> prepareAttach(NetworkDefinition network, Instance instance, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int preferredPort, int capacity, boolean resyncEnabled, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        List<Instance> instanceSnapshot = instances == null ? List.of() : List.copyOf(instances);
        List<PortReservation> reservationSnapshot = externalReservations == null ? List.of() : List.copyOf(externalReservations);
        return AsyncTools.supply(TaskSchedulers.current(), () -> ensureForwardingSecret(network, instanceSnapshot))
            .thenCompose(future -> future)
            .thenCompose(unused -> providerAllocationService.resolve(instance))
            .thenCompose(allocation -> prepareAttachResolved(network, instance, requestedRouteName, role, routingGroupId, address, preferredPort, capacity, resyncEnabled, instanceSnapshot, reservationSnapshot, allocation));
    }

    private Async<Void> ensureForwardingSecret(NetworkDefinition network, Collection<Instance> instances) {
        Objects.requireNonNull(network, "Network is required");
        if (network.forwarding().mode() == ForwardingMode.NONE || network.forwarding().mode() == ForwardingMode.LEGACY
            || !secretStore.resolveForwardingSecret(network.forwarding().secretReference()).isBlank()) {
            return Async.completed(null);
        }
        Instance proxy = indexInstances(instances).get(network.proxyInstanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Forwarding Key Cannot Be Recovered Because The Proxy Is Unavailable"));
        }
        return adoptionService.scan(proxy, instances, List.of())
            .thenCompose(report -> adoptionService.readForwardingSecret(proxy, report))
            .thenAccept(secret -> secretStore.restoreForwardingSecret(network.forwarding().secretReference(), secret));
    }

    private synchronized Async<NetworkAttachPreparedPlan> prepareAttachResolved(NetworkDefinition network, Instance instance, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int preferredPort, int capacity, boolean resyncEnabled, Collection<Instance> instances, Collection<PortReservation> externalReservations, NetworkProviderAllocation providerAllocation) {
        ResolvedAttach resolved;
        try {
            resolved = resolveAttach(network, instance, requestedRouteName, role, routingGroupId, address, preferredPort, capacity, resyncEnabled, instances, externalReservations, providerAllocation);
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(resolved.candidate(), instances, externalReservations)), secretStore);
        if (!plan.canApply()) {
            return Async.failed(new IllegalStateException(plan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("Server attach is blocked")));
        }
        return configurationTransaction.prepare(plan, instances).thenApply(prepared -> new NetworkAttachPreparedPlan(resolved.base(), resolved.candidate(), resolved.member(), resolved.routingGroupId(), prepared));
    }

    public synchronized Async<NetworkAttachPreparedPlan> prepareExternalAttach(NetworkDefinition network, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int port, int capacity, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        ResolvedAttach resolved;
        try {
            resolved = resolveExternalAttach(network, requestedRouteName, role, routingGroupId, address, port, capacity, instances, externalReservations);
        } catch (RuntimeException exception) {
            return Async.failed(exception);
        }
        NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(resolved.candidate(), instances, externalReservations)), secretStore);
        if (!plan.canApply()) {
            return Async.failed(new IllegalStateException(plan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("External route attach is blocked")));
        }
        return configurationTransaction.prepare(plan, instances).thenApply(prepared -> new NetworkAttachPreparedPlan(resolved.base(), resolved.candidate(), resolved.member(), resolved.routingGroupId(), prepared));
    }

    public synchronized Async<NetworkJob> runPreparedAttach(NetworkAttachPreparedPlan attachPrepared, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(attachPrepared, "Prepared server attach is required");
        Instance attached = indexInstances(instances).get(attachPrepared.member().instanceId());
        return providerAllocationService.resolve(attached).thenCompose(allocation -> runPreparedAttachResolved(attachPrepared, instances, initiator, allocation));
    }

    private synchronized Async<NetworkJob> runPreparedAttachResolved(NetworkAttachPreparedPlan attachPrepared, Collection<Instance> instances, String initiator, NetworkProviderAllocation providerAllocation) {
        NetworkDefinition current = networks.get(attachPrepared.baseNetwork().networkId());
        if (current == null || current.revision() != attachPrepared.baseNetwork().revision()) {
            return Async.failed(new IllegalStateException("Network changed after attach review"));
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        Instance proxy = instancesById.get(current.proxyInstanceId());
        Instance attached = instancesById.get(attachPrepared.member().instanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Proxy Is Unavailable"));
        }
        if (attachPrepared.member().isManaged() && attached == null) {
            return Async.failed(new IllegalStateException("Server Is Unavailable"));
        }
        if (attached != null && getNetworkForInstance(attached.getInstanceId()).isPresent()) {
            return Async.failed(new IllegalStateException("Server already belongs to a network"));
        }
        validateProviderAllocation(attachPrepared.member(), attached, providerAllocation);
        NetworkDefinition candidate = attachPrepared.candidate();
        if (!candidate.networkId().equals(current.networkId()) || candidate.revision() != current.revision() + 1) {
            return Async.failed(new IllegalStateException("Prepared attach candidate is stale"));
        }
        if (candidate.revision() != attachPrepared.prepared().plan().networkRevision()) {
            return Async.failed(new IllegalStateException("Prepared attach revision is stale"));
        }
        NetworkDiscoveryResult discovery = discoverObserved(candidate, instances, List.of());
        List<NetworkValidationIssue> errors = discovery.issues().stream().filter(NetworkValidationIssue::blocksPersistence).toList();
        if (!errors.isEmpty()) {
            return Async.failed(new IllegalStateException(errors.getFirst().message()));
        }
        NetworkMemberRestorePoint restorePoint = attachPrepared.member().isManaged() ? captureRestorePoint(attachPrepared.member(), attachPrepared.prepared()) : null;
        Map<String, String> context = attachContext(candidate, attachPrepared.member(), attachPrepared.routingGroupId(), restorePoint);
        return withMutationLock(current.networkId(), () -> jobManager.executePrepared(candidate, attachPrepared.prepared(), instances, NetworkJobType.ATTACH, initiator, context).thenCompose(job -> finishAttach(job, instances, restorePoint)));
    }

    private ResolvedAttach resolveAttach(NetworkDefinition network, Instance instance, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int preferredPort, int capacity, boolean resyncEnabled, Collection<Instance> instances, Collection<PortReservation> externalReservations, NetworkProviderAllocation providerAllocation) {
        Objects.requireNonNull(network, "Network is required");
        Objects.requireNonNull(instance, "Instance is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            throw new IllegalArgumentException("Network changed before attach started");
        }
        if (current.members().stream().anyMatch(member -> member.instanceId().equals(instance.getInstanceId())) || getNetworkForInstance(instance.getInstanceId()).isPresent()) {
            throw new IllegalArgumentException("Instance already belongs to a network");
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        Instance proxy = instancesById.get(current.proxyInstanceId());
        if (proxy == null) {
            throw new IllegalStateException("Proxy is unavailable");
        }
        boolean providerManaged = providerAllocationService.isProviderManaged(instance);
        if (providerManaged && providerAllocation == null) {
            throw new IllegalStateException("Provider Allocation Is Required For " + instance.getName());
        }
        String hostScope = providerManaged ? providerAllocation.hostScope() : NetworkHostScope.resolve(instance);
        boolean sameHost = !providerManaged && current.proxyMember() != null && current.proxyMember().hostScope().equals(hostScope);
        String resolvedAddress = providerManaged ? providerAllocation.address() : address == null ? "" : address.trim();
        if (sameHost) {
            resolvedAddress = "127.0.0.1";
        } else if (resolvedAddress.isBlank()) {
            throw new IllegalArgumentException("Cross-host attach requires a reachable backend address");
        } else if (!current.forwarding().firewallVerified()) {
            throw new IllegalStateException(providerManaged ? "Verify Provider Firewall Or Private-Network Protection Before Attach" : "Verify private-network or firewall protection before cross-host attach");
        }
        String resolvedRouteName = routeName(requestedRouteName == null || requestedRouteName.isBlank() ? instance.getName() : requestedRouteName);
        String resolvedGroupId = routingGroupId == null ? "" : routingGroupId.trim();
        if (!resolvedGroupId.isBlank() && current.routingGroups().stream().noneMatch(group -> group.id().equals(resolvedGroupId))) {
            throw new IllegalArgumentException("Routing group does not exist: " + resolvedGroupId);
        }
        List<PortReservation> reservations = portAllocator.discover(getNetworks(), instances, externalReservations).stream().filter(reservation -> !reservation.ownerId().equals(instance.getInstanceId())).toList();
        int resolvedPort = providerManaged ? providerAllocation.port() : portAllocator.allocate(hostScope, preferredPort, NetworkPortAllocator.DEFAULT_RANGE_START, NetworkPortAllocator.DEFAULT_RANGE_END, reservations);
        if (providerManaged && reservations.stream().anyMatch(reservation -> reservation.hostScope().equals(hostScope) && reservation.port() == resolvedPort)) {
            throw new IllegalStateException("Provider Allocation Is Already Reserved For " + instance.getName());
        }
        NetworkMember member = new NetworkMember(instance.getInstanceId(), UUID.randomUUID().toString(), resolvedRouteName, role, hostScope, resolvedAddress, resolvedPort, capacity, resyncEnabled);
        NetworkDefinition candidate = ensureAttachRuntime(buildAttachCandidate(current, member, resolvedGroupId), proxy, reservations);
        return new ResolvedAttach(current, candidate, member, resolvedGroupId);
    }

    private ResolvedAttach resolveExternalAttach(NetworkDefinition network, String requestedRouteName, NetworkMemberRole role, String routingGroupId, String address, int port, int capacity, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        Objects.requireNonNull(network, "Network is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            throw new IllegalArgumentException("Network changed before attach started");
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        Instance proxy = instancesById.get(current.proxyInstanceId());
        if (proxy == null) {
            throw new IllegalStateException("Proxy is unavailable");
        }
        String resolvedAddress = address == null ? "" : address.trim();
        if (resolvedAddress.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("External Backend Address And Port Are Required");
        }
        String resolvedRouteName = routeName(requestedRouteName == null || requestedRouteName.isBlank() ? "external" : requestedRouteName);
        String resolvedGroupId = routingGroupId == null ? "" : routingGroupId.trim();
        if (!resolvedGroupId.isBlank() && current.routingGroups().stream().noneMatch(group -> group.id().equals(resolvedGroupId))) {
            throw new IllegalArgumentException("Routing group does not exist: " + resolvedGroupId);
        }
        String hostScope = loopbackAddress(resolvedAddress) ? NetworkHostScope.resolve(proxy) : "external:" + resolvedAddress.toLowerCase(Locale.ROOT);
        List<PortReservation> reservations = portAllocator.discover(getNetworks(), instances, externalReservations);
        boolean occupied = reservations.stream().anyMatch(reservation -> reservation.hostScope().equals(hostScope) && reservation.port() == port);
        if (occupied) {
            throw new IllegalStateException("External Backend Port Is Already Reserved");
        }
        NetworkMember member = new NetworkMember("external:" + UUID.randomUUID(), UUID.randomUUID().toString(), resolvedRouteName, role, hostScope, resolvedAddress, port, capacity, false, NetworkMemberManagement.EXTERNAL);
        NetworkDefinition candidate = buildAttachCandidate(current, member, resolvedGroupId);
        return new ResolvedAttach(current, candidate, member, resolvedGroupId);
    }

    public synchronized Async<NetworkDefinition> detach(NetworkDefinition network, Instance instance) {
        return Async.failed(new IllegalStateException("Safe detach requires the proxy, backend, and full instance collection"));
    }

    public synchronized Async<NetworkJob> dissolveSafely(NetworkDefinition network, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null) {
            return Async.failed(new IllegalArgumentException("Network no longer exists"));
        }
        Map<String, String> context = Map.of(
            "operation", "dissolve",
            "memberIds", current.members().stream().map(NetworkMember::instanceId).collect(Collectors.joining(",")),
            "nodeIds", current.members().stream().filter(member -> !member.isProxy()).map(NetworkMember::nodeId).collect(Collectors.joining(",")),
            "secretReference", current.forwarding().secretReference()
        );
        return withMutationLock(current.networkId(), () -> {
            NetworkDiscoveryResult discovery = discoverObserved(current, instances, List.of());
            NetworkReconciliationPlan plan = detachPlanner.planDissolve(DesktopNetworkPlanInput.from(discovery));
            return jobManager.execute(current, plan, instances, NetworkJobType.DELETE, initiator, context).thenCompose(job -> {
                String cleanupMessage = job.status() == NetworkJobStatus.SUCCEEDED ? "Network dissolved and reachable servers restored" : "Network dissolved; some server files require manual review: " + job.message();
                Async<Void> cleanup = job.status() == NetworkJobStatus.SUCCEEDED ? Async.completed(null) : applyDissolveFallback(current, plan, instances);
                return cleanup.thenCompose(unused -> finalizeDissolve(job, instances)).handle((unused, throwable) -> {
                    if (throwable != null) {
                        return jobManager.failCompletion(job.jobId(), "Network removal failed", throwable);
                    }
                    return jobManager.completeBestEffort(job.jobId(), cleanupMessage);
                });
            });
        });
    }

    private Async<Void> applyDissolveFallback(NetworkDefinition network, NetworkReconciliationPlan plan, Collection<Instance> instances) {
        Map<NetworkConfigDocumentKey, List<NetworkConfigMutation>> documents = plan.mutations().stream().collect(Collectors.groupingBy(
            mutation -> new NetworkConfigDocumentKey(mutation.instanceId(), mutation.path()), LinkedHashMap::new, Collectors.toList()));
        List<Async<Void>> attempts = new ArrayList<>();
        for (List<NetworkConfigMutation> mutations : documents.values()) {
            NetworkReconciliationPlan documentPlan = new NetworkReconciliationPlan("", network.networkId(), network.revision(), 0, mutations, List.of(), NetworkPlanStrategy.DETACH);
            attempts.add(configurationTransaction.prepare(documentPlan, instances)
                .thenCompose(prepared -> configurationTransaction.apply(prepared, network, instances))
                .handle((result, throwable) -> null));
        }
        return Async.allOf(attempts.toArray(Async[]::new));
    }

    public synchronized Async<NetworkJob> detachSafely(NetworkDefinition network, Instance instance, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        Objects.requireNonNull(instance, "Instance is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before detach started"));
        }
        NetworkMember member = current.members().stream().filter(candidate -> candidate.instanceId().equals(instance.getInstanceId())).findFirst().orElse(null);
        if (member == null) {
            return Async.failed(new IllegalArgumentException("Instance is not a network member"));
        }
        return detachMemberSafely(current, member, instance, instances, initiator);
    }

    public synchronized Async<NetworkJob> detachExternalSafely(NetworkDefinition network, NetworkMember member, Collection<Instance> instances, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        Objects.requireNonNull(member, "Network member is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before detach started"));
        }
        NetworkMember currentMember = current.members().stream().filter(candidate -> candidate.nodeId().equals(member.nodeId())).findFirst().orElse(null);
        if (currentMember == null || currentMember.isManaged()) {
            return Async.failed(new IllegalArgumentException("External network member is unavailable"));
        }
        return detachMemberSafely(current, currentMember, null, instances, initiator);
    }

    private Async<NetworkJob> detachMemberSafely(NetworkDefinition current, NetworkMember member, Instance instance, Collection<Instance> instances, String initiator) {
        if (member.isProxy()) {
            return Async.failed(new IllegalArgumentException("The proxy cannot be detached while the network exists"));
        }
        if (current.members().stream().filter(candidate -> !candidate.isProxy()).count() <= 1) {
            return Async.failed(new IllegalArgumentException("The last backend cannot be detached while the network exists"));
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        Instance proxy = instancesById.get(current.proxyInstanceId());
        if (proxy == null) {
            return Async.failed(new IllegalStateException("Proxy is unavailable"));
        }
        if (member.isManaged() && instance == null) {
            return Async.failed(new IllegalStateException("Server Is Unavailable"));
        }
        Async<NetworkMemberRestorePoint> restorePoint = member.isManaged() ? resolveRestorePoint(current, member, instances) : Async.completed(null);
        return restorePoint.thenCompose(original -> withMutationLock(current.networkId(), () -> {
            NetworkDiscoveryResult discovery = discoverObserved(current, instances, List.of());
            NetworkReconciliationPlan plan = detachPlanner.plan(DesktopNetworkPlanInput.from(discovery), member.instanceId(), original,
                    entry -> entry.sensitive() ? secretStore.resolveRestoreValue(entry.value()) : entry.value());
            Map<String, String> context = new LinkedHashMap<>();
            context.put("instanceId", member.instanceId());
            context.put("nodeId", member.nodeId());
            if (original != null) context.put("restorePoint", GSON.toJson(original));
            return jobManager.execute(current, plan, instances, NetworkJobType.DETACH, initiator, context).thenCompose(job -> {
                if (job.status() != NetworkJobStatus.SUCCEEDED) return Async.completed(job);
                return finalizeDetach(job, instances).thenApply(unused -> job);
            });
        }));
    }

    public synchronized void delete(String networkId, Collection<Instance> instances) {
        NetworkDefinition network = networks.get(networkId);
        if (network == null) {
            return;
        }
        throw new IllegalStateException("Safe network deletion requires a dissolve job");
    }

    public String getLoadError() {
        return loadError;
    }

    public Path getDirectory() {
        return repository.getDirectory();
    }

    public NetworkSecretStore getSecretStore() {
        return secretStore;
    }

    public NetworkPortAllocator getPortAllocator() {
        return portAllocator;
    }

    public NetworkJobManager getJobManager() {
        return jobManager;
    }

    @Override
    public String getJobLoadError() {
        return jobManager.getLoadError();
    }

    public NetworkLifecycleJobManager getLifecycleJobManager() {
        return lifecycleJobManager;
    }

    public NetworkPreflightManager getPreflightManager() {
        return preflightManager;
    }

    public synchronized Async<NetworkPreflightReport> runPreflight(NetworkDefinition network, Collection<Instance> instances) {
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before preflight started"));
        }
        return withMutationLock(current.networkId(), () -> preflightManager.run(current, instances, getNetworks()));
    }

    public synchronized Async<NetworkLifecycleJob> runLifecycle(NetworkDefinition network, Collection<Instance> instances, NetworkLifecycleOperation operation, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        Objects.requireNonNull(operation, "Lifecycle operation is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before the lifecycle operation started"));
        }
        return withMutationLock(current.networkId(), () -> {
            Async<NetworkJob> preflight = Async.completed(null);
            if (operation == NetworkLifecycleOperation.START || operation == NetworkLifecycleOperation.RESTART || operation == NetworkLifecycleOperation.ROLLING_RESTART) {
                NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(current, instances, List.of())), secretStore);
                List<NetworkValidationIssue> blocking = plan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).toList();
                if (!blocking.isEmpty()) {
                    return Async.failed(new IllegalStateException(blocking.getFirst().message()));
                }
                Map<String, Instance> instancesById = indexInstances(instances);
                preflight = configurationTransaction.prepare(plan, instances).thenCompose(prepared -> {
                    if (prepared.plan().changes().isEmpty()) {
                        return Async.completed(null);
                    }
                    boolean allStopped = current.members().stream().map(NetworkMember::instanceId).map(instancesById::get).filter(Objects::nonNull).allMatch(this::isStopped);
                    if (!allStopped) {
                        return Async.failed(new IllegalStateException("Stop The Network Before Applying Pending Configuration"));
                    }
                    return jobManager.executePrepared(current, prepared, instances, NetworkJobType.RECONCILE, initiator + " Preflight");
                });
            }
            return preflight.thenCompose(configurationJob -> {
                if (configurationJob != null && configurationJob.status() != NetworkJobStatus.SUCCEEDED) {
                    return Async.failed(new IllegalStateException(configurationJob.message()));
                }
                return lifecycleJobManager.execute(current, instances, operation, initiator);
            }).thenCompose(job -> {
                if (job.status() != NetworkLifecycleStatus.SUCCEEDED) {
                    return Async.completed(job);
                }
                return finalizeLifecycle(job, instances).thenApply(unused -> job);
            });
        });
    }

    public synchronized Async<NetworkLifecycleJob> runMemberLifecycle(NetworkDefinition network, NetworkMember member, Collection<Instance> instances, NetworkLifecycleOperation operation, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        Objects.requireNonNull(member, "Network member is required");
        Objects.requireNonNull(operation, "Lifecycle operation is required");
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before the server action started"));
        }
        NetworkMember currentMember = current.members().stream().filter(candidate -> candidate.nodeId().equals(member.nodeId())).findFirst().orElse(null);
        Instance instance = currentMember == null ? null : indexInstances(instances).get(currentMember.instanceId());
        if (currentMember == null || instance == null) {
            return Async.failed(new IllegalArgumentException("Server is unavailable"));
        }
        return withMutationLock(current.networkId(), () -> lifecycleJobManager.executeMember(current, currentMember, instance, operation, initiator));
    }

    public synchronized Async<NetworkLifecycleJob> resumeLifecycle(String jobId, Collection<Instance> instances) {
        NetworkLifecycleJob job = lifecycleJobManager.getJob(jobId).orElseThrow(() -> new IllegalArgumentException("Network lifecycle job does not exist: " + jobId));
        NetworkDefinition current = networks.get(job.networkId());
        if (current == null) {
            return Async.failed(new IllegalArgumentException("Network no longer exists"));
        }
        return withMutationLock(current.networkId(), () -> lifecycleJobManager.resume(jobId, current, instances).thenCompose(updated -> {
            if (updated.status() != NetworkLifecycleStatus.SUCCEEDED) {
                return Async.completed(updated);
            }
            return finalizeLifecycle(updated, instances).thenApply(unused -> updated);
        }));
    }

    public synchronized NetworkDiscoveryResult discover(NetworkDefinition network, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        return discoverObserved(network, instances, externalReservations);
    }

    private synchronized NetworkDiscoveryResult discoverObserved(NetworkDefinition network, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        NetworkDiscoveryResult discovery = discoveryService.discover(network, instances, getNetworks(), externalReservations);
        if (networks.containsKey(network.networkId())) {
            incidentManager.observeDiscovery(discovery);
        }
        return discovery;
    }

    public synchronized NetworkReconciliationPlan plan(NetworkDefinition network, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        return desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discover(network, instances, externalReservations)), secretStore);
    }

    public synchronized Async<NetworkJob> runJob(NetworkDefinition network, Collection<Instance> instances, Collection<PortReservation> externalReservations, NetworkJobType type, String initiator) {
        NetworkDefinition current = networks.get(network.networkId());
        if (current == null || current.revision() != network.revision()) {
            return Async.failed(new IllegalArgumentException("Network changed before the job started"));
        }
        return withMutationLock(current.networkId(), () -> {
            NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(current, instances, externalReservations)), secretStore);
            return jobManager.execute(current, plan, instances, type, initiator);
        });
    }

    public synchronized Async<NetworkJob> resumeJob(String jobId, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        NetworkJob job = jobManager.getJob(jobId).orElseThrow(() -> new IllegalArgumentException("Network job does not exist: " + jobId));
        NetworkDefinition current = networks.get(job.networkId());
        if (current == null && job.type() == NetworkJobType.QUICK_CREATE) {
            NetworkDefinition candidate = creationCandidateFromContext(job.context());
            List<Instance> snapshot = instances == null ? List.of() : instances.stream().filter(Objects::nonNull).toList();
            Set<String> selectedIds = candidate.members().stream().filter(NetworkMember::isManaged).map(NetworkMember::instanceId).collect(Collectors.toCollection(LinkedHashSet::new));
            return resolveProviderAllocations(instancesForIds(snapshot, selectedIds)).thenCompose(allocations -> resumeCreationJob(jobId, candidate, snapshot, externalReservations, allocations));
        }
        if (current == null) {
            return Async.failed(new IllegalArgumentException("Network no longer exists"));
        }
        if (job.type() == NetworkJobType.ROTATE_SECRET) {
            try {
                requireManagedServersStopped(current, instances, "Stop Every Managed Network Server Before Resuming Secret Rotation");
            } catch (RuntimeException exception) {
                return Async.failed(exception);
            }
        }
        if (job.type() == NetworkJobType.ATTACH) {
            NetworkMember member = memberFromAttachContext(job.context());
            Instance attached = indexInstances(instances).get(member.instanceId());
            return providerAllocationService.resolve(attached).thenCompose(allocation -> resumeExistingJob(jobId, current, instances, externalReservations, allocation));
        }
        return resumeExistingJob(jobId, current, instances, externalReservations, null);
    }

    private synchronized Async<NetworkJob> resumeExistingJob(String jobId, NetworkDefinition reviewedNetwork, Collection<Instance> instances, Collection<PortReservation> externalReservations, NetworkProviderAllocation providerAllocation) {
        NetworkJob job = jobManager.getJob(jobId).orElseThrow(() -> new IllegalArgumentException("Network job does not exist: " + jobId));
        NetworkDefinition current = networks.get(reviewedNetwork.networkId());
        if (current == null || current.revision() != reviewedNetwork.revision()) {
            return Async.failed(new IllegalStateException("Network changed before job recovery"));
        }
        return withMutationLock(current.networkId(), () -> {
            NetworkDefinition plannedNetwork = switch (job.type()) {
                case ATTACH -> attachCandidateFromContext(current, job.context());
                case ROUTING -> buildRoutingCandidate(current, routingGroupsFromContext(job.context()));
                case REALMS -> buildSharedDataCandidate(current, realmsFromContext(job.context()), featuresFromContext(job.context(), current.features()), sharedDataPolicyFromContext(job.context(), current.sharedDataPolicy()));
                case ROTATE_SECRET -> secretRotationCandidateFromContext(job.context());
                case ADOPT -> reSyncCandidateFromContext(job.context());
                default -> current;
            };
            if (job.type() == NetworkJobType.ATTACH) {
                NetworkMember member = memberFromAttachContext(job.context());
                validateProviderAllocation(member, indexInstances(instances).get(member.instanceId()), providerAllocation);
            }
            NetworkDiscoveryResult discovery = discoverObserved(plannedNetwork, instances, externalReservations);
            NetworkReconciliationPlan plan = switch (job.type()) {
                case DETACH -> detachPlanner.plan(DesktopNetworkPlanInput.from(discovery), job.context().getOrDefault("instanceId", ""),
                        restorePointFromContext(job.context()), entry -> entry.sensitive() ? secretStore.resolveRestoreValue(entry.value()) : entry.value());
                case DELETE -> detachPlanner.planDissolve(DesktopNetworkPlanInput.from(discovery));
                case ROUTING -> routingPlan(current, plannedNetwork, instances, externalReservations);
                default -> desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discovery), secretStore);
            };
            return jobManager.resume(jobId, plannedNetwork, plan, instances).thenCompose(updated -> {
                if (updated.type() == NetworkJobType.ROTATE_SECRET && updated.status() == NetworkJobStatus.ROLLED_BACK) {
                    deleteUnusedSecret(updated.context().getOrDefault("newSecretReference", ""));
                    return Async.completed(updated);
                }
                if (updated.status() != NetworkJobStatus.SUCCEEDED) {
                    return Async.completed(updated);
                }
                if (updated.type() == NetworkJobType.DETACH) {
                    return finalizeDetach(updated, instances).thenApply(unused -> updated);
                }
                if (updated.type() == NetworkJobType.ATTACH) {
                    return finalizeAttach(updated, instances).thenApply(unused -> updated);
                }
                if (updated.type() == NetworkJobType.ROUTING) {
                    return finalizeRouting(updated, instances).thenApply(unused -> updated);
                }
                if (updated.type() == NetworkJobType.REALMS) {
                    return finalizeRealms(updated, instances).thenApply(unused -> updated);
                }
                if (updated.type() == NetworkJobType.ADOPT) {
                    return finalizeReSync(updated, instances).thenApply(unused -> updated);
                }
                if (updated.type() == NetworkJobType.ROTATE_SECRET) {
                    return finalizeSecretRotation(updated, instances).thenApply(unused -> updated);
                }
                if (updated.type() == NetworkJobType.DELETE) {
                    return finalizeDissolve(updated, instances).thenApply(unused -> updated);
                }
                return Async.completed(updated);
            });
        });
    }

    private synchronized Async<NetworkJob> resumeCreationJob(String jobId, NetworkDefinition candidate, Collection<Instance> instances, Collection<PortReservation> externalReservations, Map<String, NetworkProviderAllocation> providerAllocations) {
        validateProviderAllocations(candidate, instances, providerAllocations);
        return withMutationLock(candidate.networkId(), () -> {
            NetworkReconciliationPlan plan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(candidate, instances, externalReservations)), secretStore);
            return jobManager.resume(jobId, candidate, plan, instances).thenCompose(updated -> updated.status() == NetworkJobStatus.SUCCEEDED ? finalizeCreation(updated, instances).thenApply(unused -> updated) : Async.completed(updated));
        });
    }

    public synchronized Async<NetworkJob> rollbackJob(String jobId, Collection<Instance> instances) {
        NetworkJob job = jobManager.getJob(jobId).orElseThrow(() -> new IllegalArgumentException("Network job does not exist: " + jobId));
        NetworkDefinition network = networks.get(job.networkId());
        if (network == null && job.type() == NetworkJobType.QUICK_CREATE) {
            NetworkDefinition candidate = creationCandidateFromContext(job.context());
            return withMutationLock(candidate.networkId(), () -> jobManager.rollback(jobId, instances).thenApply(updated -> {
                if (updated.status() == NetworkJobStatus.ROLLED_BACK) {
                    secretStore.deleteForwardingSecret(candidate.forwarding().secretReference());
                    secretStore.deleteEnrollmentTokens(candidate);
                }
                return updated;
            }));
        }
        if (network == null) {
            return Async.failed(new IllegalArgumentException("Network no longer exists"));
        }
        if (job.type() == NetworkJobType.DETACH && network.members().stream().noneMatch(member -> member.instanceId().equals(job.context().getOrDefault("instanceId", "")))) {
            return Async.failed(new IllegalStateException("Completed detach cannot be rolled back into network membership"));
        }
        if (job.type() == NetworkJobType.ATTACH && network.members().stream().anyMatch(member -> member.instanceId().equals(job.context().getOrDefault("instanceId", "")))) {
            return Async.failed(new IllegalStateException("Completed attach cannot be rolled back out of network membership"));
        }
        if (job.type() == NetworkJobType.ROUTING && network.revision() == job.networkRevision()) {
            return Async.failed(new IllegalStateException("Completed routing changes cannot be rolled back without a new routing revision"));
        }
        if (job.type() == NetworkJobType.REALMS && network.revision() == job.networkRevision()) {
            return Async.failed(new IllegalStateException("Completed realm changes cannot be rolled back without a new realm revision"));
        }
        if (job.type() == NetworkJobType.ROTATE_SECRET && network.revision() == job.networkRevision()) {
            return Async.failed(new IllegalStateException("Completed secret rotation cannot be rolled back without a new rotation"));
        }
        if (job.type() == NetworkJobType.ROTATE_SECRET) {
            try {
                requireManagedServersStopped(network, instances, "Stop Every Managed Network Server Before Rolling Back Secret Rotation");
            } catch (RuntimeException exception) {
                return Async.failed(exception);
            }
        }
        return withMutationLock(network.networkId(), () -> jobManager.rollback(jobId, instances).thenCompose(updated -> {
            if (updated.status() == NetworkJobStatus.ROLLED_BACK && updated.type() == NetworkJobType.ROTATE_SECRET) {
                deleteUnusedSecret(updated.context().getOrDefault("newSecretReference", ""));
            }
            if (updated.status() == NetworkJobStatus.ROLLED_BACK && updated.type() == NetworkJobType.REALMS) {
                return reloadReSyncBackends(network, instances).thenApply(unused -> updated);
            }
            return Async.completed(updated);
        }));
    }

    public Async<Void> recoverCompletedJobs(Collection<Instance> instances) {
        Async<Void> recovery = Async.completed(null);
        for (NetworkJob job : jobManager.getJobs()) {
            if (job.status() == NetworkJobStatus.SUCCEEDED) {
                recovery = recovery.thenCompose(unused -> recoverCompletedJob(job, instances));
            }
        }
        for (NetworkLifecycleJob job : lifecycleJobManager.getJobs()) {
            if (job.status() == NetworkLifecycleStatus.SUCCEEDED) {
                recovery = recovery.thenCompose(unused -> recoverCompletedLifecycleJob(job, instances));
            }
        }
        return recovery;
    }

    private Async<Void> recoverCompletedJob(NetworkJob job, Collection<Instance> instances) {
        NetworkDefinition network = getNetwork(job.networkId()).orElse(null);
        if (job.type() == NetworkJobType.DELETE && "dissolve".equals(job.context().get("operation"))) {
            if (network != null) {
                return network.revision() == job.networkRevision() ? finalizeDissolve(job, instances) : Async.completed(null);
            }
            Set<String> memberIds = commaSeparated(job.context().getOrDefault("memberIds", ""));
            boolean incomplete = instances.stream().filter(instance -> memberIds.contains(instance.getInstanceId())).anyMatch(instance -> job.networkId().equals(instance.getNetworkId()));
            return incomplete ? finalizeDissolve(job, instances) : Async.completed(null);
        }
        if (network == null) {
            return Async.completed(null);
        }
        String instanceId = job.context().getOrDefault("instanceId", "");
        return switch (job.type()) {
            case DETACH -> network.revision() == job.networkRevision() && network.members().stream().anyMatch(member -> member.instanceId().equals(instanceId)) ? finalizeDetach(job, instances) : Async.completed(null);
            case ATTACH -> network.revision() + 1 == job.networkRevision() && network.members().stream().noneMatch(member -> member.instanceId().equals(instanceId)) ? finalizeAttach(job, instances) : Async.completed(null);
            case ROUTING -> network.revision() + 1 == job.networkRevision() ? finalizeRouting(job, instances) : Async.completed(null);
            case REALMS -> network.revision() + 1 == job.networkRevision() ? finalizeRealms(job, instances) : Async.completed(null);
            case ADOPT -> network.revision() + 1 == job.networkRevision() ? finalizeReSync(job, instances) : Async.completed(null);
            case ROTATE_SECRET -> network.revision() == job.networkRevision() || network.revision() + 1 == job.networkRevision() ? finalizeSecretRotation(job, instances) : Async.completed(null);
            default -> Async.completed(null);
        };
    }

    private Async<Void> recoverCompletedLifecycleJob(NetworkLifecycleJob job, Collection<Instance> instances) {
        NetworkDefinition network = getNetwork(job.networkId()).orElse(null);
        return shouldRecoverLifecycle(job, network) ? finalizeLifecycle(job, instances) : Async.completed(null);
    }

    public Async<NetworkPreparedPlan> prepare(NetworkReconciliationPlan plan, Collection<Instance> instances) {
        return configurationTransaction.prepare(plan, instances);
    }

    public synchronized Async<NetworkJob> runPreparedJob(NetworkPreparedPlan prepared, Collection<Instance> instances, NetworkJobType type, String initiator) {
        NetworkDefinition current = networks.get(prepared.plan().networkId());
        if (current == null) {
            return Async.failed(new IllegalArgumentException("Network no longer exists"));
        }
        return withMutationLock(current.networkId(), () -> jobManager.executePrepared(current, prepared, instances, type, initiator));
    }

    public synchronized List<NetworkValidationIssue> reconcileInstanceBindings(Collection<Instance> instances) {
        if (instances == null || !loadError.isBlank()) {
            return List.of();
        }
        List<Instance> instanceSnapshot = instances.stream().filter(Objects::nonNull).toList();
        List<NetworkValidationIssue> issues = new ArrayList<>();
        for (Instance instance : instanceSnapshot) {
            Optional<NetworkDefinition> owner = getNetworkForInstance(instance.getInstanceId());
            if (owner.isEmpty()) {
                if (instance.isNetworkMember()) {
                    issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.WARNING, "binding.owner.unavailable", instance.getInstanceId(), "Kept The Saved Membership While Its Network Is Unavailable"));
                }
                continue;
            }
            NetworkDefinition network = owner.get();
            NetworkMember member = network.members().stream().filter(candidate -> candidate.instanceId().equals(instance.getInstanceId())).findFirst().orElseThrow();
            if (!network.networkId().equals(instance.getNetworkId()) || !member.nodeId().equals(instance.getNetworkNodeId()) || network.revision() != instance.getNetworkRevision()) {
                instance.bindNetwork(network.networkId(), member.nodeId(), network.revision());
                save(instance);
                issues.add(new NetworkValidationIssue(NetworkValidationIssue.Severity.INFO, "binding.reconciled", instance.getInstanceId(), "Updated network binding to revision " + network.revision()));
            }
        }
        runtimeInstances = instanceSnapshot;
        runtimeMonitor.refresh(getNetworks(), instanceSnapshot);
        return List.copyOf(issues);
    }

    public NetworkRuntimeSnapshot getRuntimeSnapshot(String networkId) {
        return runtimeMonitor.snapshot(networkId);
    }

    public List<NetworkIncident> getIncidents(String networkId) {
        return incidentManager.incidents(networkId);
    }

    public int getOpenIncidentCount(String networkId) {
        return incidentManager.openCount(networkId);
    }

    public Map<String, Integer> getTransferFailureHeat(String networkId) {
        return incidentManager.transferFailureHeat(networkId);
    }

    public void addRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        runtimeMonitor.addListener(listener);
    }

    public void removeRuntimeListener(Consumer<NetworkRuntimeSnapshot> listener) {
        runtimeMonitor.removeListener(listener);
    }

    public void addRuntimeEventListener(Consumer<NetworkEvent> listener) {
        runtimeMonitor.addEventListener(listener);
    }

    public void removeRuntimeEventListener(Consumer<NetworkEvent> listener) {
        runtimeMonitor.removeEventListener(listener);
    }

    public Async<Void> setRuntimeNodeMode(String networkId, String nodeId, NetworkRuntimeNodeStatus status) {
        return runtimeMonitor.setNodeMode(networkId, nodeId, NetworkNodeStatus.valueOf(status.name()));
    }

    public Async<Void> executeRuntimeProxyCommand(String networkId, String command) {
        return runtimeMonitor.executeProxyCommand(networkId, command);
    }

    public Async<Void> broadcastRuntimeMessage(String networkId, String message) {
        return runtimeMonitor.broadcast(networkId, message);
    }

    public Async<List<NetworkSnapshotMetadata>> listRuntimeSnapshots(String networkId, UUID playerId, int limit) {
        return runtimeMonitor.listSnapshots(networkId, playerId, limit);
    }

    public Async<List<NetworkSnapshotMetadata>> listRuntimeSnapshots(String networkId, UUID playerId, int offset, int limit) {
        return runtimeMonitor.listSnapshots(networkId, playerId, offset, limit);
    }

    public Async<NetworkSnapshotMetadata> readRuntimeSnapshot(String networkId, String snapshotId) {
        return runtimeMonitor.readSnapshot(networkId, snapshotId);
    }

    public Async<NetworkSnapshotMetadata> pinRuntimeSnapshot(String networkId, String snapshotId, boolean pinned) {
        return runtimeMonitor.pinSnapshot(networkId, snapshotId, pinned);
    }

    public Async<PlayerTransfer> restoreRuntimeSnapshot(String networkId, String snapshotId, String targetNodeId) {
        return runtimeMonitor.restoreSnapshot(networkId, snapshotId, targetNodeId);
    }

    private void observeRuntimeIncidents(NetworkRuntimeSnapshot snapshot) {
        NetworkDefinition network;
        synchronized (this) {
            network = networks.get(snapshot.networkId());
        }
        if (network != null) {
            incidentManager.observeRuntime(network, snapshot);
        }
    }

    private void observeRuntimeEvent(NetworkEvent event) {
        NetworkDefinition network;
        synchronized (this) {
            network = networks.get(event.networkId());
        }
        if (network != null) {
            incidentManager.observeEvent(network, event);
        }
    }

    public void close() {
        runtimeMonitor.close();
        if (ownsRuntimeScheduler && runtimeScheduler instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void addListener(Consumer<List<NetworkDefinition>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<List<NetworkDefinition>> listener) {
        listeners.remove(listener);
    }

    private void validateGlobalMembership(List<NetworkDefinition> candidates, String replacingNetworkId) {
        Map<String, String> ownership = new LinkedHashMap<>();
        for (NetworkDefinition existing : networks.values()) {
            if (existing.networkId().equals(replacingNetworkId)) {
                continue;
            }
            existing.members().forEach(member -> ownership.put(member.instanceId(), existing.networkId()));
        }
        for (NetworkDefinition candidate : candidates) {
            for (NetworkMember member : candidate.members()) {
                String owner = ownership.putIfAbsent(member.instanceId(), candidate.networkId());
                if (owner != null && !owner.equals(candidate.networkId())) {
                    throw new IllegalArgumentException("Instance " + member.instanceId() + " belongs to multiple networks");
                }
            }
        }
    }

    private void validateLoadedMembership(List<NetworkDefinition> loaded) {
        Map<String, String> ownership = new LinkedHashMap<>();
        for (NetworkDefinition network : loaded) {
            for (NetworkMember member : network.members()) {
                String owner = ownership.putIfAbsent(member.instanceId(), network.networkId());
                if (owner != null && !owner.equals(network.networkId())) {
                    throw new IllegalArgumentException("Instance " + member.instanceId() + " belongs to multiple networks");
                }
            }
        }
    }

    private void notifyListeners() {
        List<NetworkDefinition> snapshot = networks.values().stream().sorted(Comparator.comparing(NetworkDefinition::name, String.CASE_INSENSITIVE_ORDER)).toList();
        Map<String, NetworkDefinition> byId = snapshot.stream().collect(Collectors.toUnmodifiableMap(NetworkDefinition::networkId, network -> network));
        Map<String, NetworkDefinition> byInstanceId = snapshot.stream().flatMap(network -> network.members().stream().map(member -> Map.entry(member.instanceId(), network))).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        networkCatalog = new NetworkCatalog(snapshot, byId, byInstanceId);
        runtimeMonitor.refresh(snapshot, runtimeInstances);
        listeners.forEach(listener -> listener.accept(snapshot));
    }

    private NetworkDefinition buildAdoptedNetwork(String name, NetworkAdoptionReport report, Map<String, Instance> instancesById, String secretReference, Map<String, NetworkProviderAllocation> providerAllocations) {
        Instance proxy = instancesById.get(report.proxyInstanceId());
        NetworkProviderAllocation proxyAllocation = providerAllocations.get(proxy.getInstanceId());
        boolean providerManagedProxy = providerAllocationService.isProviderManaged(proxy);
        if (providerManagedProxy && proxyAllocation == null) {
            throw new IllegalStateException("Provider Allocation Is Required For " + proxy.getName());
        }
        String proxyScope = providerManagedProxy ? proxyAllocation.hostScope() : NetworkHostScope.resolve(proxy);
        String proxyAddress = providerManagedProxy ? proxyAllocation.address() : report.bindAddress();
        int entryPort = providerManagedProxy ? proxyAllocation.port() : report.entryPort();
        NetworkMember proxyMember = new NetworkMember(proxy.getInstanceId(), UUID.randomUUID().toString(), "proxy", NetworkMemberRole.PROXY, proxyScope, proxyAddress, entryPort, 0, false);
        List<NetworkMember> members = new ArrayList<>();
        members.add(proxyMember);
        Set<String> providerEndpoints = new LinkedHashSet<>();
        if (providerManagedProxy) {
            providerEndpoints.add(proxyScope + ":" + entryPort);
        }
        Set<String> fallbackRoutes = new LinkedHashSet<>(report.fallbackRoutes());
        for (NetworkAdoptionRoute route : report.routes()) {
            NetworkMemberRole role = fallbackRoutes.contains(route.routeName()) && fallbackRoutes.stream().findFirst().orElse("").equals(route.routeName()) ? NetworkMemberRole.LOBBY : NetworkMemberRole.GAMEPLAY;
            if (route.management() == NetworkMemberManagement.EXTERNAL) {
                String hostScope = loopbackAddress(route.address()) ? proxyScope : "external:" + route.address().toLowerCase(Locale.ROOT);
                members.add(new NetworkMember(route.instanceId(), UUID.randomUUID().toString(), route.routeName(), role, hostScope, route.address(), route.port(), 0, false, NetworkMemberManagement.EXTERNAL));
                continue;
            }
            Instance backend = instancesById.get(route.instanceId());
            if (backend == null || getNetworkForInstance(backend.getInstanceId()).isPresent()) {
                throw new IllegalStateException("Adoption backend is unavailable: " + route.routeName());
            }
            if (providerAllocationService.isProviderManaged(backend)) {
                NetworkProviderAllocation allocation = providerAllocations.get(backend.getInstanceId());
                if (allocation == null) {
                    throw new IllegalStateException("Provider Allocation Is Required For " + route.routeName());
                }
                if (!providerEndpoints.add(allocation.hostScope() + ":" + allocation.port())) {
                    throw new IllegalStateException("Provider Allocation Is Used More Than Once: " + allocation.address() + ":" + allocation.port());
                }
                members.add(new NetworkMember(backend.getInstanceId(), UUID.randomUUID().toString(), route.routeName(), role, allocation.hostScope(), allocation.address(), allocation.port(), 0, false));
                continue;
            }
            members.add(new NetworkMember(backend.getInstanceId(), UUID.randomUUID().toString(), route.routeName(), role, NetworkHostScope.resolve(backend), route.address(), route.port(), 0, false));
        }
        NetworkForwardingPolicy forwarding = new NetworkForwardingPolicy(report.forwardingMode(), report.proxyOnlineMode(), secretReference, false);
        NetworkDefinition base = NetworkDefinition.create(name == null || name.isBlank() ? proxy.getName() + " Network" : name, proxy.getInstanceId(), forwarding, List.of(new NetworkEntryPoint("primary", report.bindAddress(), entryPort, report.forcedHosts().keySet())), members);
        Map<String, NetworkMember> membersByRoute = members.stream().filter(member -> !member.isProxy()).collect(Collectors.toMap(NetworkMember::routeName, member -> member));
        List<RoutingGroup> groups = adoptedRoutingGroups(report, membersByRoute);
        NetworkDesiredState desiredState = isStopped(proxy) ? NetworkDesiredState.STOPPED : NetworkDesiredState.RUNNING;
        Map<String, Boolean> features = Map.of(
            NetworkDefinition.FEATURE_RUNTIME, false,
            NetworkDefinition.FEATURE_PRESENCE, false,
            NetworkDefinition.FEATURE_SHARED_STATE, false,
            NetworkDefinition.FEATURE_FLOW_EVENTS, false,
            NetworkDefinition.FEATURE_SHARED_CHAT, false,
            NetworkDefinition.FEATURE_SHARED_RESOURCES, false,
            NetworkDefinition.FEATURE_PATH_SYNC, false
        );
        return new NetworkDefinition(base.schemaVersion(), base.networkId(), base.name(), base.revision(), base.proxyInstanceId(), desiredState, base.forwarding(), base.entryPoints(), base.members(), groups, List.of(), NetworkRuntimePolicy.disabled(), features, base.sharedDataPolicy(), base.createdAt(), base.updatedAt());
    }

    private List<RoutingGroup> adoptedRoutingGroups(NetworkAdoptionReport report, Map<String, NetworkMember> membersByRoute) {
        List<RoutingGroup> groups = new ArrayList<>();
        List<String> fallbackNodes = report.fallbackRoutes().stream().map(membersByRoute::get).filter(Objects::nonNull).map(NetworkMember::nodeId).toList();
        Set<String> fallbackHosts = report.forcedHosts().entrySet().stream().filter(entry -> entry.getValue().equals(report.fallbackRoutes())).map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!fallbackNodes.isEmpty()) {
            groups.add(new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, fallbackNodes, Map.of(), "", fallbackHosts, ""));
        }
        Map<List<String>, Set<String>> forcedRoutes = new LinkedHashMap<>();
        report.forcedHosts().forEach((host, routes) -> {
            if (!routes.equals(report.fallbackRoutes())) {
                forcedRoutes.computeIfAbsent(routes, ignored -> new LinkedHashSet<>()).add(host);
            }
        });
        int index = 1;
        for (Map.Entry<List<String>, Set<String>> entry : forcedRoutes.entrySet()) {
            List<String> nodeIds = entry.getKey().stream().map(membersByRoute::get).filter(Objects::nonNull).map(NetworkMember::nodeId).toList();
            if (!nodeIds.isEmpty()) {
                groups.add(new RoutingGroup("forced-" + index, "Forced Hosts " + index, RoutingStrategy.ORDERED, nodeIds, Map.of(), fallbackNodes.isEmpty() ? "" : "fallback", entry.getValue(), ""));
                index++;
            }
        }
        return List.copyOf(groups);
    }

    private String routeName(String name) {
        String normalized = name == null ? "server" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return normalized.isBlank() ? "server" : normalized;
    }

    private boolean loopbackAddress(String address) {
        return address != null && (address.equalsIgnoreCase("localhost") || address.equals("127.0.0.1") || address.equals("::1") || address.equals("[::1]") || address.equals("0:0:0:0:0:0:0:1") || address.equals("[0:0:0:0:0:0:0:1]"));
    }

    private Map<String, Integer> withoutKey(Map<String, Integer> values, String key) {
        Map<String, Integer> copy = new LinkedHashMap<>(values);
        copy.remove(key);
        return copy;
    }

    private Async<Void> finalizeDissolve(NetworkJob job, Collection<Instance> instances) {
        Set<String> memberIds = new LinkedHashSet<>();
        Set<String> nodeIds = commaSeparated(job.context().getOrDefault("nodeIds", ""));
        nodeIds.add(NetworkRuntimeIdentity.operatorNodeId(job.networkId()));
        String persistedMemberIds = job.context().getOrDefault("memberIds", "");
        if (!persistedMemberIds.isBlank()) {
            for (String memberId : persistedMemberIds.split(",")) {
                if (!memberId.isBlank()) {
                    memberIds.add(memberId.trim());
                }
            }
        }
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current != null) {
                if (current.revision() != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before dissolve was committed"));
                }
                current.members().stream().map(NetworkMember::instanceId).forEach(memberIds::add);
                current.members().stream().filter(member -> !member.isProxy()).map(NetworkMember::nodeId).forEach(nodeIds::add);
                repository.delete(current);
                incidentManager.delete(current.networkId());
                networks.remove(current.networkId());
                notifyListeners();
            }
            String secretReference = job.context().getOrDefault("secretReference", "");
            boolean secretStillUsed = networks.values().stream().anyMatch(candidate -> candidate.forwarding().secretReference().equals(secretReference));
            if (!secretStillUsed) {
                secretStore.deleteForwardingSecret(secretReference);
            }
            nodeIds.forEach(nodeId -> secretStore.deleteEnrollmentToken(job.networkId(), nodeId));
            secretStore.deleteRuntimeCredential(job.networkId(), NetworkRuntimeIdentity.operatorNodeId(job.networkId()));
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        for (String memberId : memberIds) {
            Instance instance = instancesById.get(memberId);
            if (instance == null) {
                continue;
            }
            instance.clearNetworkBinding();
            metadataUpdates.add(save(instance));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new));
    }

    private Async<Void> reloadReSyncBackends(NetworkDefinition network, Collection<Instance> instances) {
        Map<String, Instance> instancesById = indexInstances(instances);
        List<Async<Void>> reloads = network.members().stream().filter(member -> member.isManaged() && !member.isProxy() && member.resyncEnabled()).map(NetworkMember::instanceId).map(instancesById::get).filter(Objects::nonNull).filter(instance -> !isStopped(instance)).map(instance -> JvmAsyncBridge.fromFuture(InstanceApi.of(instance).console().execute("resync network reload"))).toList();
        return Async.allOf(reloads.toArray(Async[]::new));
    }

    private Async<Void> finalizeLifecycle(NetworkLifecycleJob job, Collection<Instance> instances) {
        if (job.operation() == NetworkLifecycleOperation.DRAIN) {
            return Async.completed(null);
        }
        NetworkDesiredState desiredState = lifecycleDesiredState(job.operation());
        NetworkDefinition updated;
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current == null) {
                return Async.failed(new IllegalStateException("Network no longer exists"));
            }
            if (current.revision() > job.networkRevision()) {
                if (current.revision() == job.networkRevision() + 1 && current.desiredState() != desiredState) {
                    return Async.failed(new IllegalStateException("Network lifecycle state conflicts with a newer revision"));
                }
                updated = current;
            } else {
                if (current.revision() != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before lifecycle state was committed"));
                }
                updated = current.nextRevision(current.members(), current.routingGroups(), current.syncRealms(), desiredState);
                saveInternal(updated, true);
            }
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        for (NetworkMember member : updated.members()) {
            Instance instance = instancesById.get(member.instanceId());
            if (instance == null) {
                continue;
            }
            instance.bindNetwork(updated.networkId(), member.nodeId(), updated.revision());
            metadataUpdates.add(save(instance));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new));
    }

    static boolean shouldRecoverLifecycle(NetworkLifecycleJob job, NetworkDefinition network) {
        if (job == null || network == null || job.status() != NetworkLifecycleStatus.SUCCEEDED || job.operation() == NetworkLifecycleOperation.DRAIN) {
            return false;
        }
        NetworkDesiredState desiredState = lifecycleDesiredState(job.operation());
        return network.revision() == job.networkRevision() || network.revision() == job.networkRevision() + 1 && network.desiredState() == desiredState;
    }

    private static NetworkDesiredState lifecycleDesiredState(NetworkLifecycleOperation operation) {
        return operation == NetworkLifecycleOperation.STOP ? NetworkDesiredState.STOPPED : NetworkDesiredState.RUNNING;
    }

    private Async<Void> finalizeDetach(NetworkJob job, Collection<Instance> instances) {
        Map<String, Instance> instancesById = indexInstances(instances);
        String instanceId = job.context().getOrDefault("instanceId", "");
        String contextNodeId = job.context().getOrDefault("nodeId", "");
        NetworkMemberRestorePoint restorePoint = restorePointFromContext(job.context());
        Instance detached = instancesById.get(instanceId);
        NetworkDefinition updated;
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current == null) {
                return Async.failed(new IllegalStateException("Network no longer exists"));
            }
            NetworkMember member = current.members().stream().filter(candidate -> candidate.instanceId().equals(instanceId)).findFirst().orElse(null);
            if (member == null) {
                if (!contextNodeId.isBlank()) {
                    secretStore.deleteEnrollmentToken(job.networkId(), contextNodeId);
                }
                deleteRestoreSecrets(restorePoint);
                if (detached == null) {
                    return Async.completed(null);
                }
                detached.clearNetworkBinding();
                return save(detached);
            }
            if (member.isManaged() && detached == null) {
                return Async.failed(new IllegalStateException("Detached server is unavailable for metadata update"));
            }
            if (current.revision() != job.networkRevision()) {
                return Async.failed(new IllegalStateException("Network changed before detach membership was committed"));
            }
            List<NetworkMember> members = current.members().stream().filter(candidate -> !candidate.nodeId().equals(member.nodeId())).toList();
            List<RoutingGroup> groups = current.routingGroups().stream().map(group -> new RoutingGroup(group.id(), group.name(), group.strategy(), group.nodeIds().stream().filter(nodeId -> !nodeId.equals(member.nodeId())).toList(), withoutKey(group.weights(), member.nodeId()), group.fallbackGroupId(), group.forcedHosts(), group.permission())).toList();
            List<SyncRealm> realms = current.syncRealms().stream().map(realm -> new SyncRealm(realm.id(), realm.name(), realm.nodeIds().stream().filter(nodeId -> !nodeId.equals(member.nodeId())).collect(Collectors.toCollection(LinkedHashSet::new)), realm.dataFamilies(), realm.locationPolicy(), realm.persistentDataNamespaces(), realm.retainedSnapshots(), realm.retentionDays())).toList();
            updated = current.nextRevision(members, groups, realms, current.desiredState());
            saveInternal(updated, true);
            secretStore.deleteEnrollmentToken(current.networkId(), member.nodeId());
        }
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        if (detached != null) {
            detached.clearNetworkBinding();
            metadataUpdates.add(save(detached));
        }
        for (NetworkMember member : updated.members()) {
            Instance remaining = instancesById.get(member.instanceId());
            if (remaining == null) {
                continue;
            }
            remaining.bindNetwork(updated.networkId(), member.nodeId(), updated.revision());
            metadataUpdates.add(save(remaining));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new)).thenRun(() -> deleteRestoreSecrets(restorePoint));
    }

    private Set<String> commaSeparated(String value) {
        Set<String> values = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return values;
        }
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private Async<Void> finalizeAttach(NetworkJob job, Collection<Instance> instances) {
        Map<String, Instance> instancesById = indexInstances(instances);
        NetworkMember member = memberFromAttachContext(job.context());
        Instance attached = instancesById.get(member.instanceId());
        if (member.isManaged() && attached == null) {
            return Async.failed(new IllegalStateException("Attached server is unavailable for metadata update"));
        }
        NetworkDefinition updated;
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current == null) {
                return Async.failed(new IllegalStateException("Network no longer exists"));
            }
            NetworkMember existing = current.members().stream().filter(candidate -> candidate.instanceId().equals(member.instanceId())).findFirst().orElse(null);
            if (existing != null) {
                if (!existing.nodeId().equals(member.nodeId()) || current.revision() != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before attach membership was committed"));
                }
                updated = current;
            } else {
                if (current.revision() + 1 != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before attach membership was committed"));
                }
                updated = attachCandidateFromContext(current, job.context());
                if (updated.revision() != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Attach revision does not match the prepared job"));
                }
                saveInternal(updated, true);
            }
        }
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        for (NetworkMember networkMember : updated.members()) {
            Instance networkInstance = instancesById.get(networkMember.instanceId());
            if (networkInstance == null) {
                continue;
            }
            networkInstance.bindNetwork(updated.networkId(), networkMember.nodeId(), updated.revision());
            metadataUpdates.add(save(networkInstance));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new));
    }

    private Async<NetworkJob> finishAttach(NetworkJob job, Collection<Instance> instances, NetworkMemberRestorePoint restorePoint) {
        if (job.status() != NetworkJobStatus.SUCCEEDED) {
            if (job.status() == NetworkJobStatus.ROLLED_BACK || job.status() == NetworkJobStatus.BLOCKED) deleteRestoreSecrets(restorePoint);
            return Async.completed(job);
        }
        return finalizeAttach(job, instances).thenApply(unused -> job);
    }

    private Async<Void> finalizeRouting(NetworkJob job, Collection<Instance> instances) {
        NetworkDefinition updated;
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current == null) {
                return Async.failed(new IllegalStateException("Network no longer exists"));
            }
            if (current.revision() == job.networkRevision()) {
                updated = current;
            } else {
                long baseRevision;
                try {
                    baseRevision = Long.parseLong(requiredContext(job.context(), "baseRevision"));
                } catch (NumberFormatException exception) {
                    return Async.failed(new IllegalStateException("Routing job context is invalid", exception));
                }
                if (current.revision() != baseRevision || current.revision() + 1 != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before routing was committed"));
                }
                updated = buildRoutingCandidate(current, routingGroupsFromContext(job.context()));
                if (updated.revision() != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Routing revision does not match the prepared job"));
                }
                saveInternal(updated, true);
            }
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        for (NetworkMember member : updated.members()) {
            Instance instance = instancesById.get(member.instanceId());
            if (instance == null) {
                continue;
            }
            instance.bindNetwork(updated.networkId(), member.nodeId(), updated.revision());
            metadataUpdates.add(save(instance));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new));
    }

    private Async<Void> finalizeRealms(NetworkJob job, Collection<Instance> instances) {
        NetworkDefinition updated;
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current == null) {
                return Async.failed(new IllegalStateException("Network no longer exists"));
            }
            if (current.revision() == job.networkRevision()) {
                updated = current;
            } else {
                long baseRevision;
                try {
                    baseRevision = Long.parseLong(requiredContext(job.context(), "baseRevision"));
                } catch (NumberFormatException exception) {
                    return Async.failed(new IllegalStateException("Realm job context is invalid", exception));
                }
                if (current.revision() != baseRevision || current.revision() + 1 != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before realms were committed"));
                }
                updated = buildSharedDataCandidate(current, realmsFromContext(job.context()), featuresFromContext(job.context(), current.features()), sharedDataPolicyFromContext(job.context(), current.sharedDataPolicy()));
                if (updated.revision() != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Realm revision does not match the prepared job"));
                }
                saveInternal(updated, true);
            }
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        for (NetworkMember member : updated.members()) {
            Instance instance = instancesById.get(member.instanceId());
            if (instance == null) {
                continue;
            }
            instance.bindNetwork(updated.networkId(), member.nodeId(), updated.revision());
            metadataUpdates.add(save(instance));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new)).thenCompose(unused -> reloadReSyncBackends(updated, instances));
    }

    private Async<Void> finalizeReSync(NetworkJob job, Collection<Instance> instances) {
        NetworkDefinition updated;
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current == null) {
                return Async.failed(new IllegalStateException("Network no longer exists"));
            }
            if (current.revision() == job.networkRevision()) {
                updated = current;
            } else {
                long baseRevision;
                try {
                    baseRevision = Long.parseLong(requiredContext(job.context(), "baseRevision"));
                } catch (NumberFormatException exception) {
                    return Async.failed(new IllegalStateException("ReSync setup job context is invalid", exception));
                }
                if (current.revision() != baseRevision || current.revision() + 1 != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before ReSync setup was committed"));
                }
                updated = reSyncCandidateFromContext(job.context());
                if (updated.revision() != job.networkRevision() || !updated.networkId().equals(current.networkId())) {
                    return Async.failed(new IllegalStateException("ReSync setup revision does not match the prepared job"));
                }
                saveInternal(updated, true);
            }
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        for (NetworkMember member : updated.members()) {
            Instance instance = instancesById.get(member.instanceId());
            if (instance == null) {
                continue;
            }
            instance.bindNetwork(updated.networkId(), member.nodeId(), updated.revision());
            metadataUpdates.add(save(instance));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new)).thenCompose(unused -> reloadReSyncBackends(updated, instances));
    }

    private Async<Void> finalizeSecretRotation(NetworkJob job, Collection<Instance> instances) {
        NetworkDefinition candidate = secretRotationCandidateFromContext(job.context());
        String oldSecretReference = requiredContext(job.context(), "oldSecretReference");
        NetworkDefinition committed;
        synchronized (this) {
            NetworkDefinition current = networks.get(job.networkId());
            if (current == null) {
                return Async.failed(new IllegalStateException("Network no longer exists"));
            }
            if (current.revision() == candidate.revision()) {
                if (!current.forwarding().secretReference().equals(candidate.forwarding().secretReference())) {
                    return Async.failed(new IllegalStateException("Forwarding secret rotation conflicts with the committed revision"));
                }
                committed = current;
            } else {
                if (current.revision() + 1 != candidate.revision() || !current.forwarding().secretReference().equals(oldSecretReference) || candidate.revision() != job.networkRevision()) {
                    return Async.failed(new IllegalStateException("Network changed before forwarding secret rotation was committed"));
                }
                committed = saveInternal(candidate, true);
            }
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        List<Async<Void>> metadataUpdates = new ArrayList<>();
        for (NetworkMember member : committed.members()) {
            Instance instance = instancesById.get(member.instanceId());
            if (instance == null) {
                if (member.isManaged()) {
                    return Async.failed(new IllegalStateException("Rotated network server is unavailable: " + member.routeName()));
                }
                continue;
            }
            instance.bindNetwork(committed.networkId(), member.nodeId(), committed.revision());
            metadataUpdates.add(save(instance));
        }
        return Async.allOf(metadataUpdates.toArray(Async[]::new)).thenRun(() -> deleteUnusedSecret(oldSecretReference));
    }

    private Async<Void> finalizeCreation(NetworkJob job, Collection<Instance> instances) {
        NetworkDefinition candidate = creationCandidateFromContext(job.context());
        List<InstanceBinding> previousBindings = restoreCreationBindings(candidate, instances, job.context().getOrDefault("bindings", ""));
        try {
            synchronized (this) {
                NetworkDefinition current = networks.get(candidate.networkId());
                if (current != null && (current.revision() != candidate.revision() || !current.equals(candidate))) {
                    throw new IllegalStateException("Created Network Metadata Conflicts With The Completed Job");
                }
                validateGlobalMembership(List.of(candidate), candidate.networkId());
            }
            Map<String, Instance> instancesById = indexInstances(instances);
            List<Async<Void>> metadataUpdates = new ArrayList<>();
            for (NetworkMember member : candidate.members()) {
                Instance instance = instancesById.get(member.instanceId());
                if (instance == null) {
                    if (!member.isManaged()) continue;
                    throw new IllegalStateException("Created Network Server Is Unavailable: " + member.routeName());
                }
                instance.bindNetwork(candidate.networkId(), member.nodeId(), candidate.revision());
                metadataUpdates.add(save(instance));
            }
            return Async.allOf(metadataUpdates.toArray(Async[]::new)).thenRun(() -> commitCreationMetadata(candidate))
                .exceptionallyCompose(failure -> rollbackFinalizedCreation(job, candidate, previousBindings, instances, failure));
        } catch (RuntimeException failure) {
            return rollbackFinalizedCreation(job, candidate, previousBindings, instances, failure);
        }
    }

    private synchronized void commitCreationMetadata(NetworkDefinition candidate) {
        NetworkDefinition current = networks.get(candidate.networkId());
        if (current == null) {
            saveInternal(candidate, true);
            return;
        }
        if (current.revision() != candidate.revision() || !current.equals(candidate)) {
            throw new IllegalStateException("Created Network Metadata Conflicts With The Completed Job");
        }
    }

    private List<CreationBinding> captureCreationBindings(NetworkDefinition network, Collection<Instance> instances) {
        Map<String, Instance> instancesById = indexInstances(instances);
        return network.members().stream().filter(NetworkMember::isManaged).map(NetworkMember::instanceId).map(instancesById::get).filter(Objects::nonNull)
            .map(instance -> new CreationBinding(instance.getInstanceId(), instance.getNetworkId(), instance.getNetworkNodeId(), instance.getNetworkRevision())).toList();
    }

    private List<InstanceBinding> restoreCreationBindings(NetworkDefinition network, Collection<Instance> instances, String encoded) {
        Map<String, Instance> instancesById = indexInstances(instances);
        Map<String, CreationBinding> stored = new LinkedHashMap<>();
        if (encoded != null && !encoded.isBlank()) {
            try {
                CreationBinding[] values = GSON.fromJson(encoded, CreationBinding[].class);
                if (values != null) {
                    for (CreationBinding value : values) {
                        if (value != null && value.instanceId() != null && !value.instanceId().isBlank()) stored.put(value.instanceId(), value);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        List<InstanceBinding> bindings = new ArrayList<>();
        for (NetworkMember member : network.members()) {
            if (!member.isManaged()) continue;
            Instance instance = instancesById.get(member.instanceId());
            if (instance == null) continue;
            CreationBinding value = stored.get(member.instanceId());
            bindings.add(value == null ? new InstanceBinding(instance, "", "", 0)
                : new InstanceBinding(instance, value.networkId(), value.nodeId(), value.revision()));
        }
        return List.copyOf(bindings);
    }

    private Async<Void> rollbackFinalizedCreation(NetworkJob job, NetworkDefinition candidate, List<InstanceBinding> bindings,
                                                  Collection<Instance> instances, Throwable failure) {
        Throwable cause = unwrapCompletion(failure);
        Async<NetworkJob> configurationRollback;
        try {
            configurationRollback = jobManager.rollback(job.jobId(), instances);
        } catch (RuntimeException rollbackFailure) {
            configurationRollback = Async.failed(rollbackFailure);
        }
        return configurationRollback.handle((rolledBack, rollbackFailure) -> {
            if (rollbackFailure != null) cause.addSuppressed(unwrapCompletion(rollbackFailure));
            return null;
        }).thenCompose(ignored -> rollbackCreationMetadata(candidate, bindings, cause));
    }

    private Async<Void> rollbackCreationMetadata(NetworkDefinition candidate, List<InstanceBinding> bindings, Throwable cause) {
        synchronized (this) {
            NetworkDefinition current = networks.get(candidate.networkId());
            if (current != null && current.equals(candidate)) {
                try {
                    repository.delete(current);
                } catch (RuntimeException rollbackFailure) {
                    cause.addSuppressed(unwrapCompletion(rollbackFailure));
                }
                try {
                    incidentManager.delete(current.networkId());
                } catch (RuntimeException rollbackFailure) {
                    cause.addSuppressed(unwrapCompletion(rollbackFailure));
                }
                networks.remove(current.networkId());
                try {
                    notifyListeners();
                } catch (RuntimeException rollbackFailure) {
                    cause.addSuppressed(unwrapCompletion(rollbackFailure));
                }
            } else if (current != null) {
                cause.addSuppressed(new IllegalStateException("Created Network Metadata Changed Before Rollback"));
            }
        }
        List<Async<Void>> metadataRollbacks = new ArrayList<>();
        for (InstanceBinding binding : bindings) {
            binding.restore();
            try {
                metadataRollbacks.add(save(binding.instance()));
            } catch (RuntimeException rollbackFailure) {
                cause.addSuppressed(unwrapCompletion(rollbackFailure));
            }
        }
        return Async.allOf(metadataRollbacks.toArray(Async[]::new)).handle((ignored, rollbackFailure) -> {
            if (rollbackFailure != null) cause.addSuppressed(unwrapCompletion(rollbackFailure));
            try {
                boolean secretStillUsed;
                synchronized (this) {
                    secretStillUsed = networks.values().stream().anyMatch(network -> network.forwarding().secretReference().equals(candidate.forwarding().secretReference()));
                }
                if (!secretStillUsed) secretStore.deleteForwardingSecret(candidate.forwarding().secretReference());
                secretStore.deleteEnrollmentTokens(candidate);
            } catch (RuntimeException rollbackError) {
                cause.addSuppressed(unwrapCompletion(rollbackError));
            }
            return null;
        }).thenCompose(ignored -> Async.failed(cause));
    }

    private NetworkDefinition buildCreationCandidate(NetworkCreationRequest request, Collection<Instance> instances, Collection<PortReservation> externalReservations, String secretReference, Map<String, NetworkProviderAllocation> providerAllocations) {
        if (request.name().isBlank()) {
            throw new IllegalArgumentException("Network Name Is Required");
        }
        if (request.entryPort() < 1) {
            throw new IllegalArgumentException("Entry Port Is Required");
        }
        if (request.backends().isEmpty()) {
            throw new IllegalArgumentException("Select At Least One Backend");
        }
        Map<String, Instance> instancesById = indexInstances(instances);
        Instance proxy = instancesById.get(request.proxyInstanceId());
        if (proxy == null || !isVelocity(proxy)) {
            throw new IllegalArgumentException("Select One Velocity Proxy");
        }
        if (!isStopped(proxy)) {
            throw new IllegalStateException("Stop Every Selected Server Before Creating The Network");
        }
        Set<String> selectedIds = new LinkedHashSet<>();
        selectedIds.add(proxy.getInstanceId());
        for (NetworkCreationMember backend : request.backends()) {
            if (!selectedIds.add(backend.instanceId())) {
                throw new IllegalArgumentException("A Server Is Selected More Than Once");
            }
        }
        if (selectedIds.stream().anyMatch(instanceId -> getNetworkForInstance(instanceId).isPresent())) {
            throw new IllegalStateException("Detach Selected Servers From Their Existing Network First");
        }
        List<PortReservation> reservations = new ArrayList<>(portAllocator.discover(getNetworks(), instances, externalReservations == null ? List.of() : externalReservations).stream().filter(reservation -> !selectedIds.contains(reservation.ownerId())).toList());
        NetworkProviderAllocation proxyAllocation = providerAllocations.get(proxy.getInstanceId());
        boolean providerManagedProxy = providerAllocationService.isProviderManaged(proxy);
        if (providerManagedProxy && proxyAllocation == null) {
            throw new IllegalStateException("Provider Allocation Is Required For " + proxy.getName());
        }
        String proxyScope = providerManagedProxy ? proxyAllocation.hostScope() : NetworkHostScope.resolve(proxy);
        int proxyPort = providerManagedProxy ? proxyAllocation.port() : portAllocator.allocate(proxyScope, request.entryPort(), NetworkPortAllocator.DEFAULT_RANGE_START, NetworkPortAllocator.DEFAULT_RANGE_END, reservations);
        if (providerManagedProxy && reservations.stream().anyMatch(reservation -> reservation.hostScope().equals(proxyScope) && reservation.port() == proxyPort)) {
            throw new IllegalStateException("Provider Proxy Allocation Is Already Reserved");
        }
        reservations.add(new PortReservation(proxyScope, proxyPort, providerManagedProxy ? "provider-allocation" : "network-plan", proxy.getInstanceId(), proxy.getName()));
        boolean resyncEnabled = request.backends().stream().anyMatch(backend -> backend.management() == NetworkMemberManagement.MANAGED && backend.resyncEnabled());
        NetworkMember proxyMember = new NetworkMember(proxy.getInstanceId(), UUID.randomUUID().toString(), "proxy", NetworkMemberRole.PROXY, proxyScope, providerManagedProxy ? proxyAllocation.address() : "127.0.0.1", proxyPort, 0, resyncEnabled);
        List<NetworkMember> members = new ArrayList<>();
        members.add(proxyMember);
        Set<String> routes = new LinkedHashSet<>();
        for (NetworkCreationMember backendRequest : request.backends()) {
            Instance backend = instancesById.get(backendRequest.instanceId());
            String route = routeName(backendRequest.routeName().isBlank() ? backend == null ? "external" : backend.getName() : backendRequest.routeName());
            if (!routes.add(route)) {
                throw new IllegalArgumentException("Backend Route Names Must Be Unique");
            }
            if (backendRequest.management() == NetworkMemberManagement.EXTERNAL) {
                String address = backendRequest.address();
                if (address.isBlank() || backendRequest.preferredPort() < 1) {
                    throw new IllegalArgumentException("External Backend Address And Port Are Required For " + route);
                }
                String scope = loopbackAddress(address) ? proxyScope : "external:" + address.toLowerCase(Locale.ROOT);
                boolean sameHost = scope.equals(proxyScope);
                if (!sameHost && !request.firewallVerified()) {
                    throw new IllegalStateException("Verify Private-Network Or Firewall Protection For External Backends");
                }
                int port = backendRequest.preferredPort();
                boolean occupied = reservations.stream().anyMatch(reservation -> reservation.hostScope().equals(scope) && reservation.port() == port);
                if (occupied) {
                    throw new IllegalStateException("External Backend Port Is Already Reserved For " + route);
                }
                reservations.add(new PortReservation(scope, port, "external-network-node", backendRequest.instanceId(), route));
                members.add(new NetworkMember(backendRequest.instanceId(), UUID.randomUUID().toString(), route, backendRequest.role(), scope, address, port, backendRequest.capacity(), false, NetworkMemberManagement.EXTERNAL));
                continue;
            }
            if (backend == null || backend.isProxyServer()) {
                throw new IllegalArgumentException("Selected Backend Is Unavailable");
            }
            if (!isStopped(backend)) {
                throw new IllegalStateException("Stop Every Selected Server Before Creating The Network");
            }
            if (providerAllocationService.isProviderManaged(backend)) {
                NetworkProviderAllocation allocation = providerAllocations.get(backend.getInstanceId());
                if (allocation == null) {
                    throw new IllegalStateException("Provider Allocation Is Required For " + route);
                }
                if (!request.firewallVerified()) {
                    throw new IllegalStateException("Verify Provider Firewall Or Private-Network Protection For " + route);
                }
                boolean occupied = reservations.stream().anyMatch(reservation -> reservation.hostScope().equals(allocation.hostScope()) && reservation.port() == allocation.port());
                if (occupied) {
                    throw new IllegalStateException("Provider Allocation Is Already Reserved For " + route);
                }
                reservations.add(new PortReservation(allocation.hostScope(), allocation.port(), "provider-allocation", backend.getInstanceId(), backend.getName()));
                members.add(new NetworkMember(backend.getInstanceId(), UUID.randomUUID().toString(), route, backendRequest.role(), allocation.hostScope(), allocation.address(), allocation.port(), backendRequest.capacity(), backendRequest.resyncEnabled()));
                continue;
            }
            String scope = NetworkHostScope.resolve(backend);
            boolean sameHost = scope.equals(proxyScope);
            String address = sameHost ? "127.0.0.1" : backendRequest.address();
            if (!sameHost && address.isBlank()) {
                throw new IllegalArgumentException("Cross-Host Backend Address Is Required For " + route);
            }
            if (!sameHost && !request.firewallVerified()) {
                throw new IllegalStateException("Verify Private-Network Or Firewall Protection For Cross-Host Backends");
            }
            int preferredPort = backendRequest.preferredPort() > 0 ? backendRequest.preferredPort() : observedPort(backend, 25566);
            int port = portAllocator.allocate(scope, preferredPort, NetworkPortAllocator.DEFAULT_RANGE_START, NetworkPortAllocator.DEFAULT_RANGE_END, reservations);
            reservations.add(new PortReservation(scope, port, "network-plan", backend.getInstanceId(), backend.getName()));
            members.add(new NetworkMember(backend.getInstanceId(), UUID.randomUUID().toString(), route, backendRequest.role(), scope, address, port, backendRequest.capacity(), backendRequest.resyncEnabled()));
        }
        Map<String, NetworkMember> membersByRoute = members.stream().filter(member -> !member.isProxy()).collect(Collectors.toMap(NetworkMember::routeName, member -> member, (first, second) -> first, LinkedHashMap::new));
        List<NetworkMember> fallbackMembers = request.fallbackRoutes().stream().map(this::routeName).map(membersByRoute::get).filter(Objects::nonNull).distinct().toList();
        if (!request.fallbackRoutes().isEmpty() && fallbackMembers.size() != request.fallbackRoutes().stream().map(this::routeName).distinct().count()) {
            throw new IllegalArgumentException("Fallback Routing References An Unknown Backend");
        }
        if (fallbackMembers.isEmpty()) {
            fallbackMembers = members.stream().filter(member -> !member.isProxy() && member.role() == NetworkMemberRole.LOBBY).toList();
        }
        if (fallbackMembers.isEmpty()) {
            fallbackMembers = List.of(members.stream().filter(member -> !member.isProxy()).findFirst().orElseThrow());
        }
        List<NetworkMember> resolvedFallbackMembers = fallbackMembers;
        NetworkForwardingPolicy forwarding = new NetworkForwardingPolicy(ForwardingMode.MODERN, true, secretReference, request.firewallVerified());
        NetworkDefinition base = NetworkDefinition.create(request.name(), proxy.getInstanceId(), forwarding, List.of(NetworkEntryPoint.primary(proxyPort)), members);
        List<RoutingGroup> routingGroups = new ArrayList<>();
        Set<String> fallbackHosts = request.forcedHosts().entrySet().stream().filter(entry -> entry.getValue().stream().map(this::routeName).toList().equals(resolvedFallbackMembers.stream().map(NetworkMember::routeName).toList())).map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
        routingGroups.add(new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, resolvedFallbackMembers.stream().map(NetworkMember::nodeId).toList(), Map.of(), "", fallbackHosts, ""));
        Map<List<String>, Set<String>> forcedRoutes = new LinkedHashMap<>();
        request.forcedHosts().forEach((host, routeNames) -> {
            List<String> normalizedRoutes = routeNames.stream().map(this::routeName).toList();
            if (!normalizedRoutes.equals(resolvedFallbackMembers.stream().map(NetworkMember::routeName).toList())) {
                forcedRoutes.computeIfAbsent(normalizedRoutes, ignored -> new LinkedHashSet<>()).add(host);
            }
        });
        int forcedIndex = 1;
        for (Map.Entry<List<String>, Set<String>> entry : forcedRoutes.entrySet()) {
            List<String> nodes = entry.getKey().stream().map(membersByRoute::get).filter(Objects::nonNull).map(NetworkMember::nodeId).toList();
            if (nodes.size() != entry.getKey().size()) {
                throw new IllegalArgumentException("Forced Host Routing References An Unknown Backend");
            }
            routingGroups.add(new RoutingGroup("forced-" + forcedIndex, "Forced Hosts " + forcedIndex, RoutingStrategy.ORDERED, nodes, Map.of(), "fallback", entry.getValue(), ""));
            forcedIndex++;
        }
        Set<String> presenceNodes = members.stream().filter(member -> member.isProxy() || member.resyncEnabled()).map(NetworkMember::nodeId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<SyncRealm> realms = presenceNodes.size() > 1 ? List.of(SyncRealm.presence("presence", "Presence", presenceNodes)) : List.of();
        List<NetworkMember> runtimeMembers = members.stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).toList();
        NetworkRuntimePolicy runtime = NetworkRuntimePolicy.disabled();
        if (!runtimeMembers.isEmpty()) {
            int hubPort = portAllocator.allocate(proxyScope, 12442, 12000, 12999, reservations);
            boolean loopbackRuntime = runtimeMembers.stream().allMatch(member -> member.hostScope().equals(proxyScope));
            String hubAddress = loopbackRuntime ? "127.0.0.1" : providerManagedProxy ? proxyAllocation.address() : reachableHost(proxy);
            runtime = new NetworkRuntimePolicy(true, hubAddress, hubPort, loopbackRuntime ? NetworkTransportSecurity.LOOPBACK : NetworkTransportSecurity.WSS, loopbackRuntime);
        }
        Map<String, Boolean> features = new LinkedHashMap<>(base.features());
        if (runtimeMembers.isEmpty()) {
            features.put(NetworkDefinition.FEATURE_RUNTIME, false);
            features.put(NetworkDefinition.FEATURE_PRESENCE, false);
            features.put(NetworkDefinition.FEATURE_FLOW_EVENTS, false);
            features.put(NetworkDefinition.FEATURE_SHARED_CHAT, false);
            features.put(NetworkDefinition.FEATURE_SHARED_RESOURCES, false);
            features.put(NetworkDefinition.FEATURE_PATH_SYNC, false);
        }
        NetworkDefinition candidate = new NetworkDefinition(base.schemaVersion(), base.networkId(), base.name(), base.revision(), base.proxyInstanceId(), base.desiredState(), base.forwarding(), base.entryPoints(), base.members(), routingGroups, realms, runtime, features, base.sharedDataPolicy(), base.createdAt(), base.updatedAt());
        NetworkValidator.requireValid(candidate);
        return candidate;
    }

    private NetworkDefinition creationCandidateFromContext(Map<String, String> context) {
        try {
            NetworkDefinition candidate = GSON.fromJson(requiredContext(context, "network"), NetworkDefinition.class);
            NetworkValidator.requireValid(candidate);
            return candidate;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Network creation job context is invalid", exception);
        }
    }

    private NetworkDefinition reSyncCandidateFromContext(Map<String, String> context) {
        try {
            NetworkDefinition candidate = GSON.fromJson(requiredContext(context, "candidate"), NetworkDefinition.class);
            NetworkValidator.requireValid(candidate);
            return candidate;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ReSync setup job context is invalid", exception);
        }
    }

    private NetworkDefinition secretRotationCandidateFromContext(Map<String, String> context) {
        try {
            NetworkDefinition candidate = GSON.fromJson(requiredContext(context, "network"), NetworkDefinition.class);
            NetworkValidator.requireValid(candidate);
            if (candidate.forwarding().mode() != ForwardingMode.MODERN || !candidate.forwarding().secretReference().equals(requiredContext(context, "newSecretReference"))) {
                throw new IllegalStateException("Forwarding secret rotation context does not match its candidate network");
            }
            return candidate;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Forwarding secret rotation job context is invalid", exception);
        }
    }

    private NetworkReconciliationPlan routingPlan(NetworkDefinition current, NetworkDefinition candidate, Collection<Instance> instances, Collection<PortReservation> externalReservations) {
        NetworkDiscoveryResult discovery = discoverObserved(candidate, instances, externalReservations == null ? List.of() : externalReservations);
        NetworkReconciliationPlan desired = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discovery), secretStore);
        List<NetworkConfigMutation> mutations = new ArrayList<>(desired.mutations());
        Set<String> desiredHosts = candidate.routingGroups().stream().flatMap(group -> group.forcedHosts().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
        current.routingGroups().stream().flatMap(group -> group.forcedHosts().stream()).filter(host -> !desiredHosts.contains(host)).distinct().forEach(host -> mutations.add(new NetworkConfigMutation(candidate.proxyInstanceId(), "velocity.toml", ConfigurationFormat.TOML, "forced-hosts." + host, "", "", false, true, "Remove " + host, NetworkMutationAction.REMOVE)));
        return new NetworkReconciliationPlan(desired.planId(), desired.networkId(), desired.networkRevision(), desired.createdAt(), mutations, desired.issues(), desired.strategy());
    }

    private NetworkDefinition buildRoutingCandidate(NetworkDefinition network, List<RoutingGroup> routingGroups) {
        return network.nextRevision(network.members(), routingGroups, network.syncRealms(), network.desiredState());
    }

    private NetworkDefinition buildRealmCandidate(NetworkDefinition network, List<SyncRealm> realms) {
        return buildSharedDataCandidate(network, realms, network.features());
    }

    private NetworkDefinition buildSharedDataCandidate(NetworkDefinition network, List<SyncRealm> realms, Map<String, Boolean> features) {
        return buildSharedDataCandidate(network, realms, features, network.sharedDataPolicy());
    }

    private NetworkDefinition buildSharedDataCandidate(NetworkDefinition network, List<SyncRealm> realms, Map<String, Boolean> features, NetworkSharedDataPolicy sharedDataPolicy) {
        List<SyncRealm> normalized = realms == null ? List.of() : List.copyOf(realms);
        Map<String, Boolean> normalizedFeatures = features == null ? network.features() : Map.copyOf(new LinkedHashMap<>(features));
        return network.withSharedData(normalized, normalizedFeatures, sharedDataPolicy);
    }

    private List<RoutingGroup> routingGroupsFromContext(Map<String, String> context) {
        try {
            RoutingGroup[] groups = GSON.fromJson(requiredContext(context, "routingGroups"), RoutingGroup[].class);
            return groups == null ? List.of() : List.of(groups);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Routing job context is invalid", exception);
        }
    }

    private List<SyncRealm> realmsFromContext(Map<String, String> context) {
        try {
            SyncRealm[] realms = GSON.fromJson(requiredContext(context, "syncRealms"), SyncRealm[].class);
            return realms == null ? List.of() : List.of(realms);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Realm job context is invalid", exception);
        }
    }

    private Map<String, Boolean> featuresFromContext(Map<String, String> context, Map<String, Boolean> fallback) {
        String encoded = context.get("features");
        if (encoded == null || encoded.isBlank()) {
            return fallback;
        }
        try {
            Map<String, Boolean> features = GSON.fromJson(encoded, new TypeToken<Map<String, Boolean>>() {
            }.getType());
            return features == null ? fallback : Map.copyOf(new LinkedHashMap<>(features));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Shared data job context is invalid", exception);
        }
    }

    private NetworkSharedDataPolicy sharedDataPolicyFromContext(Map<String, String> context, NetworkSharedDataPolicy fallback) {
        String encoded = context.get("sharedDataPolicy");
        if (encoded == null || encoded.isBlank()) {
            return fallback;
        }
        try {
            NetworkSharedDataPolicy policy = GSON.fromJson(encoded, NetworkSharedDataPolicy.class);
            return policy == null ? fallback : policy;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Shared data policy job context is invalid", exception);
        }
    }

    NetworkDefinition buildReSyncCandidate(NetworkDefinition network, Collection<String> instanceIds, Instance proxy, Collection<PortReservation> reservations) {
        Set<String> enabledIds = network.members().stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).map(NetworkMember::instanceId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (instanceIds != null) {
            instanceIds.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).forEach(enabledIds::add);
        }
        Set<String> managedIds = network.members().stream().filter(member -> !member.isProxy() && member.isManaged()).map(NetworkMember::instanceId).collect(Collectors.toSet());
        if (!managedIds.containsAll(enabledIds)) {
            throw new IllegalArgumentException("ReSync can only be enabled for managed backend servers");
        }
        List<NetworkMember> members = network.members().stream().map(member -> {
            boolean enabled = member.isProxy() ? !enabledIds.isEmpty() : member.isManaged() && enabledIds.contains(member.instanceId());
            return new NetworkMember(member.instanceId(), member.nodeId(), member.routeName(), member.role(), member.hostScope(), member.address(), member.port(), member.capacity(), enabled, member.management());
        }).toList();
        Set<String> presenceNodes = members.stream().filter(member -> member.isProxy() || member.resyncEnabled()).map(NetworkMember::nodeId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<SyncRealm> realms = new ArrayList<>();
        boolean presenceUpdated = false;
        for (SyncRealm realm : network.syncRealms()) {
            if (!realm.dataFamilies().contains(SyncDataFamily.PRESENCE)) {
                realms.add(realm);
                continue;
            }
            Set<String> nodes = new LinkedHashSet<>(realm.nodeIds());
            nodes.addAll(presenceNodes);
            realms.add(new SyncRealm(realm.id(), realm.name(), nodes, realm.dataFamilies(), realm.locationPolicy(), realm.persistentDataNamespaces(), realm.retainedSnapshots(), realm.retentionDays()));
            presenceUpdated = true;
        }
        if (!presenceUpdated && presenceNodes.size() > 1) {
            realms.add(SyncRealm.presence("presence", "Presence", presenceNodes));
        }
        Map<String, Boolean> features = new LinkedHashMap<>(network.features());
        features.put(NetworkDefinition.FEATURE_RUNTIME, true);
        features.put(NetworkDefinition.FEATURE_PRESENCE, true);
        features.put(NetworkDefinition.FEATURE_FLOW_EVENTS, true);
        if (network.members().stream().noneMatch(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled())) {
            features.put(NetworkDefinition.FEATURE_SHARED_CHAT, true);
            features.put(NetworkDefinition.FEATURE_SHARED_RESOURCES, true);
        }
        NetworkDefinition candidate = new NetworkDefinition(network.schemaVersion(), network.networkId(), network.name(), network.revision() + 1, network.proxyInstanceId(), network.desiredState(), network.forwarding(), network.entryPoints(), members, network.routingGroups(), realms, network.runtime(), features, network.sharedDataPolicy(), network.createdAt(), Clock.system().millis());
        return ensureAttachRuntime(candidate, proxy, reservations);
    }

    private NetworkDefinition buildAttachCandidate(NetworkDefinition network, NetworkMember member, String routingGroupId) {
        NetworkMember existing = network.members().stream().filter(candidate -> candidate.instanceId().equals(member.instanceId())).findFirst().orElse(null);
        if (existing != null) {
            if (!existing.nodeId().equals(member.nodeId())) {
                throw new IllegalStateException("Instance already belongs to this network with another node ID");
            }
            return network;
        }
        List<NetworkMember> members = new ArrayList<>(network.members());
        members.add(member);
        List<RoutingGroup> groups = network.routingGroups().stream().map(group -> {
            if (routingGroupId == null || routingGroupId.isBlank() || !group.id().equals(routingGroupId)) {
                return group;
            }
            List<String> nodeIds = new ArrayList<>(group.nodeIds());
            if (!nodeIds.contains(member.nodeId())) {
                nodeIds.add(member.nodeId());
            }
            return new RoutingGroup(group.id(), group.name(), group.strategy(), nodeIds, group.weights(), group.fallbackGroupId(), group.forcedHosts(), group.permission());
        }).toList();
        List<SyncRealm> realms = network.syncRealms().stream().map(realm -> {
            if (!member.resyncEnabled() || !realm.dataFamilies().contains(SyncDataFamily.PRESENCE)) {
                return realm;
            }
            Set<String> nodeIds = new LinkedHashSet<>(realm.nodeIds());
            nodeIds.add(member.nodeId());
            return new SyncRealm(realm.id(), realm.name(), nodeIds, realm.dataFamilies(), realm.locationPolicy(), realm.persistentDataNamespaces(), realm.retainedSnapshots(), realm.retentionDays());
        }).toList();
        return network.nextRevision(members, groups, realms, network.desiredState());
    }

    private NetworkDefinition ensureAttachRuntime(NetworkDefinition candidate, Instance proxy, Collection<PortReservation> reservations) {
        List<NetworkMember> runtimeMembers = candidate.members().stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).toList();
        if (runtimeMembers.isEmpty()) {
            return candidate;
        }
        NetworkMember proxyMember = candidate.proxyMember();
        if (proxyMember == null) {
            return candidate;
        }
        if (!proxyMember.resyncEnabled()) {
            List<NetworkMember> enabledMembers = candidate.members().stream().map(member -> member.isProxy()
                ? new NetworkMember(member.instanceId(), member.nodeId(), member.routeName(), member.role(), member.hostScope(), member.address(), member.port(), member.capacity(), true, member.management())
                : member).toList();
            candidate = new NetworkDefinition(candidate.schemaVersion(), candidate.networkId(), candidate.name(), candidate.revision(), candidate.proxyInstanceId(), candidate.desiredState(), candidate.forwarding(), candidate.entryPoints(), enabledMembers, candidate.routingGroups(), candidate.syncRealms(), candidate.runtime(), candidate.features(), candidate.sharedDataPolicy(), candidate.createdAt(), candidate.updatedAt());
            proxyMember = candidate.proxyMember();
        }
        NetworkMember resolvedProxyMember = proxyMember;
        boolean loopback = runtimeMembers.stream().allMatch(member -> member.hostScope().equals(resolvedProxyMember.hostScope()));
        NetworkRuntimePolicy currentRuntime = candidate.runtime();
        int hubPort = currentRuntime.enabled() ? currentRuntime.hubPort() : allocateRuntimePort(candidate, resolvedProxyMember, reservations);
        NetworkRuntimePolicy runtime;
        if (loopback && (!currentRuntime.enabled() || currentRuntime.security() == NetworkTransportSecurity.LOOPBACK)) {
            runtime = new NetworkRuntimePolicy(true, "127.0.0.1", hubPort, NetworkTransportSecurity.LOOPBACK, true);
        } else if (currentRuntime.enabled() && currentRuntime.security() == NetworkTransportSecurity.WSS) {
            runtime = currentRuntime;
        } else {
            String address = !loopbackAddress(resolvedProxyMember.address()) ? resolvedProxyMember.address() : reachableHost(proxy);
            runtime = new NetworkRuntimePolicy(true, address, hubPort, NetworkTransportSecurity.WSS, false);
        }
        return new NetworkDefinition(candidate.schemaVersion(), candidate.networkId(), candidate.name(), candidate.revision(), candidate.proxyInstanceId(), candidate.desiredState(), candidate.forwarding(), candidate.entryPoints(), candidate.members(), candidate.routingGroups(), candidate.syncRealms(), runtime, candidate.features(), candidate.sharedDataPolicy(), candidate.createdAt(), candidate.updatedAt());
    }

    private int allocateRuntimePort(NetworkDefinition candidate, NetworkMember proxy, Collection<PortReservation> reservations) {
        List<PortReservation> occupied = new ArrayList<>();
        if (reservations != null) {
            occupied.addAll(reservations);
        }
        candidate.members().forEach(member -> occupied.add(new PortReservation(member.hostScope(), member.port(), "network-member", member.instanceId(), member.routeName())));
        candidate.entryPoints().forEach(entry -> occupied.add(new PortReservation(proxy.hostScope(), entry.port(), "network-entry", candidate.networkId(), entry.id())));
        return portAllocator.allocate(proxy.hostScope(), 12442, 12000, 12999, occupied);
    }

    private NetworkMemberRestorePoint captureRestorePoint(NetworkMember member, NetworkPreparedPlan prepared) {
        Map<String, NetworkRestoreEntry> entries = new LinkedHashMap<>();
        List<String> createdSecrets = new ArrayList<>();
        try {
            for (NetworkConfigMutation mutation : prepared.plan().mutations()) {
                if (!mutation.instanceId().equals(member.instanceId())) continue;
                NetworkRestoreEntry entry = restoreEntry(mutation.path(), mutation.format(), mutation.key(), mutation.currentPresent(), mutation.currentValue(), mutation.sensitive(), createdSecrets);
                entries.put(mutation.path() + "\u0000" + mutation.key(), entry);
            }
            return new NetworkMemberRestorePoint(NetworkMemberRestorePoint.CURRENT_SCHEMA_VERSION, member.instanceId(), member.nodeId(), 0, List.copyOf(entries.values()));
        } catch (RuntimeException exception) {
            createdSecrets.forEach(secretStore::deleteRestoreValue);
            throw exception;
        }
    }

    private NetworkMemberRestorePoint captureRestorePoint(NetworkMember member, Collection<NetworkConfigMutation> templates, Map<NetworkConfigDocumentKey, NetworkDocumentSnapshot> originals) {
        NetworkConfigurationAdapters adapters = new NetworkConfigurationAdapters(new DesktopStructuredDocumentParser());
        Map<String, NetworkRestoreEntry> entries = new LinkedHashMap<>();
        List<String> createdSecrets = new ArrayList<>();
        try {
            for (NetworkConfigMutation template : templates) {
                if (!template.instanceId().equals(member.instanceId())) continue;
                NetworkConfigDocumentKey documentKey = new NetworkConfigDocumentKey(member.instanceId(), template.path());
                NetworkDocumentSnapshot original = originals.get(documentKey);
                if (original == null) throw new IllegalStateException("Original configuration backup is missing for " + template.path());
                NetworkConfigurationAdapter adapter = adapters.get(template.format());
                boolean present = original.exists() && adapter.contains(original.content(), template.key());
                String value = present ? adapter.read(original.content(), template.key()) : "";
                NetworkRestoreEntry entry = restoreEntry(template.path(), template.format(), template.key(), present, value, template.sensitive(), createdSecrets);
                entries.put(template.path() + "\u0000" + template.key(), entry);
            }
            return new NetworkMemberRestorePoint(NetworkMemberRestorePoint.CURRENT_SCHEMA_VERSION, member.instanceId(), member.nodeId(), 0, List.copyOf(entries.values()));
        } catch (RuntimeException exception) {
            createdSecrets.forEach(secretStore::deleteRestoreValue);
            throw exception;
        }
    }

    private NetworkRestoreEntry restoreEntry(String path, ConfigurationFormat format, String key, boolean present, String value, boolean sensitive, List<String> createdSecrets) {
        String storedValue = value == null ? "" : value;
        if (sensitive && present) {
            storedValue = secretStore.saveRestoreValue(storedValue);
            createdSecrets.add(storedValue);
        }
        return new NetworkRestoreEntry(path, format, key, present, storedValue, sensitive);
    }

    private Async<NetworkMemberRestorePoint> resolveRestorePoint(NetworkDefinition network, NetworkMember member, Collection<Instance> instances) {
        NetworkJob attachJob = jobManager.getJobs(network.networkId()).stream().filter(job -> job.type() == NetworkJobType.ATTACH).filter(job -> job.context().getOrDefault("instanceId", "").equals(member.instanceId())).filter(job -> job.context().getOrDefault("nodeId", "").equals(member.nodeId())).findFirst().orElse(null);
        if (attachJob == null) return Async.completed(null);
        NetworkMemberRestorePoint stored;
        try {
            stored = restorePointFromContext(attachJob.context());
        } catch (RuntimeException exception) {
            stored = null;
        }
        if (stored != null && stored.entries().stream().filter(NetworkRestoreEntry::present).filter(NetworkRestoreEntry::sensitive).allMatch(entry -> secretStore.canResolveRestoreValue(entry.value()))) return Async.completed(stored);
        List<NetworkJobDocument> documents = attachJob.documents().stream().filter(document -> document.key().instanceId().equals(member.instanceId())).toList();
        if (documents.isEmpty()) return Async.completed(null);
        NetworkReconciliationPlan currentPlan = desiredStatePlanner.plan(DesktopNetworkPlanInput.from(discoverObserved(network, instances, List.of())), secretStore);
        List<NetworkConfigMutation> templates = currentPlan.mutations().stream().filter(mutation -> mutation.instanceId().equals(member.instanceId())).toList();
        return configurationTransaction.readOriginalDocuments(attachJob.jobId(), documents, instances).handle((originals, throwable) -> throwable == null ? captureRestorePoint(member, templates, originals) : null);
    }

    private NetworkMemberRestorePoint restorePointFromContext(Map<String, String> context) {
        String encoded = context == null ? "" : context.getOrDefault("restorePoint", "");
        if (encoded.isBlank()) return null;
        try {
            return GSON.fromJson(encoded, NetworkMemberRestorePoint.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Original server configuration restore point is invalid", exception);
        }
    }

    private void deleteRestoreSecrets(NetworkMemberRestorePoint restorePoint) {
        if (restorePoint == null) return;
        restorePoint.entries().stream().filter(NetworkRestoreEntry::sensitive).filter(NetworkRestoreEntry::present).map(NetworkRestoreEntry::value).forEach(secretStore::deleteRestoreValue);
    }

    private Map<String, String> attachContext(NetworkDefinition candidate, NetworkMember member, String routingGroupId, NetworkMemberRestorePoint restorePoint) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("instanceId", member.instanceId());
        context.put("nodeId", member.nodeId());
        context.put("routeName", member.routeName());
        context.put("role", member.role().name());
        context.put("hostScope", member.hostScope());
        context.put("address", member.address());
        context.put("port", String.valueOf(member.port()));
        context.put("capacity", String.valueOf(member.capacity()));
        context.put("resyncEnabled", String.valueOf(member.resyncEnabled()));
        context.put("management", member.management().name());
        context.put("routingGroupId", routingGroupId == null ? "" : routingGroupId);
        context.put("candidate", GSON.toJson(candidate));
        if (restorePoint != null) context.put("restorePoint", GSON.toJson(restorePoint));
        return Map.copyOf(context);
    }

    private NetworkDefinition attachCandidateFromContext(NetworkDefinition current, Map<String, String> context) {
        String encoded = context.getOrDefault("candidate", "");
        NetworkDefinition candidate = encoded.isBlank() ? buildAttachCandidate(current, memberFromAttachContext(context), context.getOrDefault("routingGroupId", "")) : GSON.fromJson(encoded, NetworkDefinition.class).migrated();
        if (!candidate.networkId().equals(current.networkId()) || candidate.revision() != current.revision() + 1) {
            throw new IllegalStateException("Attach job candidate is stale");
        }
        NetworkValidator.requireValid(candidate);
        return candidate;
    }

    private NetworkMember memberFromAttachContext(Map<String, String> context) {
        try {
            return new NetworkMember(requiredContext(context, "instanceId"), requiredContext(context, "nodeId"), requiredContext(context, "routeName"), NetworkMemberRole.valueOf(requiredContext(context, "role")), requiredContext(context, "hostScope"), requiredContext(context, "address"), Integer.parseInt(requiredContext(context, "port")), Integer.parseInt(context.getOrDefault("capacity", "0")), Boolean.parseBoolean(context.getOrDefault("resyncEnabled", "true")), NetworkMemberManagement.valueOf(context.getOrDefault("management", NetworkMemberManagement.MANAGED.name())));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Attach job context is invalid", exception);
        }
    }

    private Async<Map<String, NetworkProviderAllocation>> resolveProviderAllocations(Collection<Instance> instances) {
        List<Instance> providers = instances == null ? List.of() : instances.stream().filter(Objects::nonNull).filter(providerAllocationService::isProviderManaged).toList();
        if (providers.isEmpty()) {
            return Async.completed(Map.of());
        }
        List<Async<NetworkProviderAllocation>> resolutions = providers.stream().map(providerAllocationService::resolve).toList();
        return Async.allOf(resolutions.toArray(Async[]::new)).thenApply(unused -> {
            Map<String, NetworkProviderAllocation> allocations = new LinkedHashMap<>();
            resolutions.stream().map(Async::join).filter(Objects::nonNull).forEach(allocation -> allocations.put(allocation.instanceId(), allocation));
            return Map.copyOf(allocations);
        });
    }

    private List<Instance> instancesForIds(Collection<Instance> instances, Collection<String> instanceIds) {
        if (instances == null || instanceIds == null || instanceIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>(instanceIds);
        return instances.stream().filter(Objects::nonNull).filter(instance -> ids.contains(instance.getInstanceId())).toList();
    }

    private void validateProviderAllocations(NetworkDefinition network, Collection<Instance> instances, Map<String, NetworkProviderAllocation> allocations) {
        Map<String, Instance> instancesById = indexInstances(instances);
        for (NetworkMember member : network.members()) {
            if (!member.isManaged()) {
                continue;
            }
            Instance instance = instancesById.get(member.instanceId());
            validateProviderAllocation(member, instance, allocations.get(member.instanceId()));
        }
    }

    private void validateProviderAllocation(NetworkMember member, Instance instance, NetworkProviderAllocation allocation) {
        boolean providerEndpoint = member.hostScope().startsWith("ptero:") || member.hostScope().startsWith("calagopus:") || member.hostScope().startsWith("restudio:");
        if (instance == null) {
            if (providerEndpoint) {
                throw new IllegalStateException("Provider Server Is Unavailable For " + member.routeName());
            }
            return;
        }
        if (!providerAllocationService.isProviderManaged(instance)) {
            if (providerEndpoint) {
                throw new IllegalStateException("Provider Backend Changed After Review For " + member.routeName());
            }
            return;
        }
        if (allocation == null) {
            throw new IllegalStateException("Provider Allocation Is Unavailable For " + member.routeName());
        }
        if (!member.address().equalsIgnoreCase(allocation.address()) || member.port() != allocation.port() || !member.hostScope().equals(allocation.hostScope())) {
            throw new IllegalStateException("Provider Allocation Changed After Review For " + member.routeName());
        }
    }

    private String requiredContext(Map<String, String> context, String key) {
        String value = context == null ? "" : context.getOrDefault(key, "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing job context " + key);
        }
        return value;
    }

    private Map<String, Instance> indexInstances(Collection<Instance> instances) {
        Map<String, Instance> indexed = new LinkedHashMap<>();
        if (instances != null) {
            instances.stream().filter(Objects::nonNull).forEach(instance -> indexed.put(instance.getInstanceId(), instance));
        }
        return indexed;
    }

    private void requireManagedServersStopped(NetworkDefinition network, Collection<Instance> instances, String message) {
        Map<String, Instance> instancesById = indexInstances(instances);
        for (NetworkMember member : network.members()) {
            if (!member.isManaged()) {
                continue;
            }
            Instance instance = instancesById.get(member.instanceId());
            if (instance == null) {
                throw new IllegalStateException("Server Is Unavailable: " + member.routeName());
            }
            if (!isStopped(instance)) {
                throw new IllegalStateException(message);
            }
        }
    }

    private synchronized void deleteUnusedSecret(String secretReference) {
        if (secretReference == null || secretReference.isBlank()) {
            return;
        }
        boolean used = networks.values().stream().anyMatch(network -> network.forwarding().secretReference().equals(secretReference));
        if (!used) {
            secretStore.deleteForwardingSecret(secretReference);
        }
    }

    private boolean isStopped(Instance instance) {
        return instance.getState() == InstanceState.STOPPED || instance.getState() == InstanceState.CRASHED;
    }

    private boolean isVelocity(Instance instance) {
        return instance.getModLoader() == ModLoader.VELOCITY || instance.getServerSoftwareCompatibility().stream().anyMatch(value -> value.equalsIgnoreCase("velocity"));
    }

    private int observedPort(Instance instance, int fallback) {
        try {
            int port = Integer.parseInt(instance.getServerProperties().getProperty("server-port", String.valueOf(fallback)).trim());
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String reachableHost(Instance instance) {
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().credentials == null) {
            return "";
        }
        String host = instance.getBackendConfig().credentials.getOrDefault("host", "").trim();
        if (!host.contains("://")) {
            return host;
        }
        try {
            String resolved = URI.create(host).getHost();
            return resolved == null ? "" : resolved;
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private void releaseMutationLock(String networkId) {
        synchronized (mutationGuard) {
            mutationLocks.remove(networkId);
        }
    }

    private <T> Async<T> withMutationLock(String networkId, Supplier<Async<T>> operation) {
        synchronized (mutationGuard) {
            if (!mutationLocks.add(networkId)) {
                return Async.failed(new IllegalStateException("Network has an active operation"));
            }
        }
        try {
            return AsyncTools.supply(TaskSchedulers.current(), operation).thenCompose(future -> future).whenComplete((unused, throwable) -> releaseMutationLock(networkId));
        } catch (RuntimeException exception) {
            releaseMutationLock(networkId);
            return Async.failed(exception);
        }
    }

    private Async<Void> save(Instance instance) {
        return JvmAsyncBridge.fromFuture(instance.save());
    }

    private record NetworkCatalog(List<NetworkDefinition> networks, Map<String, NetworkDefinition> byId, Map<String, NetworkDefinition> byInstanceId) {
        private static NetworkCatalog empty() {
            return new NetworkCatalog(List.of(), Map.of(), Map.of());
        }
    }

    private record ResolvedAttach(NetworkDefinition base, NetworkDefinition candidate, NetworkMember member, String routingGroupId) {
    }

    record InstanceBinding(Instance instance, String networkId, String nodeId, long revision) {
        private void restore() {
            if (networkId == null || networkId.isBlank() || nodeId == null || nodeId.isBlank()) {
                instance.clearNetworkBinding();
                return;
            }
            instance.bindNetwork(networkId, nodeId, revision);
        }
    }

    private record CreationBinding(String instanceId, String networkId, String nodeId, long revision) {
    }

}
