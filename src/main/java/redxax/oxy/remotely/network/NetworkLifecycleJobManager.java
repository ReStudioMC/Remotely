package redxax.oxy.remotely.network;

import redxax.oxy.remotely.util.TaskSchedulers;

import redxax.oxy.remotely.util.AsyncTools;

import restudio.rebase.api.unified.InstanceApi;
import restudio.rebase.backend.ExecutionProvider;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.localcontrol.LifecycleManager;
import restudio.rebase.localcontrol.LocalServerControllerClient;
import restudio.resync.network.NetworkNodeStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;



import java.util.function.Supplier;

public class NetworkLifecycleJobManager {
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(2);
    private static final long POLL_DELAY_MILLIS = 500;
    private final NetworkLifecycleJobRepository repository;
    private final NetworkRuntimeMonitor runtimeMonitor;
    private final NetworkLifecyclePlanner planner = new NetworkLifecyclePlanner();
    private final NetworkLifecycleMachine machine = new NetworkLifecycleMachine();
    private final Map<String, NetworkLifecycleJob> jobs = new LinkedHashMap<>();
    private final Object admissionGuard = new Object();
    private final Map<String, String> activeNetworkJobs = new HashMap<>();
    private final Set<String> activeJobIds = new HashSet<>();

    public NetworkLifecycleJobManager(Path applicationDirectory) {
        this(applicationDirectory, null);
    }

    public NetworkLifecycleJobManager(Path applicationDirectory, NetworkRuntimeMonitor runtimeMonitor) {
        repository = new NetworkLifecycleJobRepository(applicationDirectory);
        this.runtimeMonitor = runtimeMonitor;
        reload();
    }

    public synchronized void reload() {
        jobs.clear();
        for (NetworkLifecycleJob loaded : repository.loadAll()) {
            NetworkLifecycleJob recovered = loaded.status() == NetworkLifecycleStatus.RUNNING ? loaded.withStatus(NetworkLifecycleStatus.INTERRUPTED, "Remotely closed during the network operation") : loaded;
            jobs.put(recovered.jobId(), recovered);
            if (recovered != loaded) {
                repository.save(recovered);
            }
        }
    }

    public synchronized List<NetworkLifecycleJob> getJobs() {
        return jobs.values().stream().sorted(Comparator.comparingLong(NetworkLifecycleJob::updatedAt).reversed()).toList();
    }

    public synchronized List<NetworkLifecycleJob> getJobs(String networkId) {
        return getJobs().stream().filter(job -> job.networkId().equals(networkId)).toList();
    }

    public synchronized Optional<NetworkLifecycleJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public Async<NetworkLifecycleJob> execute(NetworkDefinition network, Collection<Instance> instances, NetworkLifecycleOperation operation, String initiator) {
        Objects.requireNonNull(network, "Network is required");
        Map<String, Instance> instancesById = indexInstances(instances);
        List<NetworkLifecycleStep> steps = plan(network, operation);
        for (NetworkLifecycleStep step : steps) {
            if (!instancesById.containsKey(step.instanceId())) {
                return Async.failed(new IllegalStateException("Network server is unavailable: " + step.routeName()));
            }
        }
        NetworkLifecycleJob job = NetworkLifecycleJob.create(network, operation, initiator, steps);
        return runAdmitted(job.networkId(), job.jobId(), () -> {
            persist(job);
            return continueJob(machine.start(job), network, instancesById);
        });
    }

    public Async<NetworkLifecycleJob> executeMember(NetworkDefinition network, NetworkMember member, Instance instance, NetworkLifecycleOperation operation, String initiator) {
        if (network == null || member == null || instance == null) {
            return Async.failed(new IllegalArgumentException("Server is unavailable"));
        }
        if (!member.instanceId().equals(instance.getInstanceId())) {
            return Async.failed(new IllegalArgumentException("Server does not belong to this network"));
        }
        NetworkLifecycleJob job = NetworkLifecycleJob.create(network, operation, initiator,
                planner.planMember(network, member, operation));
        return runAdmitted(job.networkId(), job.jobId(), () -> {
            persist(job);
            return continueJob(machine.start(job), network, Map.of(instance.getInstanceId(), instance));
        });
    }

    public Async<NetworkLifecycleJob> resume(String jobId, NetworkDefinition network, Collection<Instance> instances) {
        Objects.requireNonNull(network, "Network is required");
        NetworkLifecycleJob job;
        synchronized (this) {
            job = jobs.get(jobId);
        }
        if (job == null) {
            return Async.failed(new IllegalArgumentException("Network lifecycle job does not exist: " + jobId));
        }
        return runAdmitted(job.networkId(), job.jobId(), () -> {
            if (!job.canResume()) {
                throw new IllegalStateException("Network lifecycle job cannot be resumed from " + job.status());
            }
            if (!job.networkId().equals(network.networkId()) || job.networkRevision() != network.revision()) {
                throw new IllegalStateException("Network changed after this lifecycle job was created");
            }
            Map<String, Instance> instancesById = indexInstances(instances);
            for (NetworkLifecycleStep step : job.steps()) {
                if (!step.complete() && !instancesById.containsKey(step.instanceId())) {
                    throw new IllegalStateException("Network server is unavailable: " + step.routeName());
                }
            }
            return continueJob(machine.start(job), network, instancesById);
        });
    }

    public Path getDirectory() {
        return repository.getDirectory();
    }

    public List<NetworkLifecycleStep> plan(NetworkDefinition network, NetworkLifecycleOperation operation) {
        if (network == null || operation == null) {
            return List.of();
        }
        return planner.plan(network, operation);
    }

    private Async<NetworkLifecycleJob> continueJob(NetworkLifecycleJob job, NetworkDefinition network, Map<String, Instance> instancesById) {
        persist(job);
        List<NetworkLifecycleStep> next = machine.next(job);
        if (next.isEmpty()) {
            NetworkLifecycleJob completed = machine.finish(job);
            persist(completed);
            return Async.completed(completed);
        }
        if (next.size() > 1) {
            return continueParallelStarts(job, network, instancesById, next);
        }
        NetworkLifecycleJob runningJob = machine.begin(job, next);
        NetworkLifecycleStep runningStep = step(runningJob, next.getFirst().stepId());
        persist(runningJob);
        Instance instance = instancesById.get(runningStep.instanceId());
        return executeStep(network, instance, runningStep, runningJob.operation()).handle((outcome, throwable) -> {
            if (throwable != null) {
                String message = rootMessage(throwable);
                NetworkLifecycleJob failed = machine.fail(runningJob, runningStep, message);
                persist(failed);
                return Async.completed(failed);
            }
            NetworkLifecycleJob checkpoint = machine.succeed(runningJob, runningStep, outcome.skipped(), outcome.message());
            persist(checkpoint);
            return continueJob(checkpoint, network, instancesById);
        }).thenCompose(future -> future);
    }

    private Async<NetworkLifecycleJob> continueParallelStarts(NetworkLifecycleJob job, NetworkDefinition network,
                                                               Map<String, Instance> instancesById,
                                                               List<NetworkLifecycleStep> pending) {
        NetworkLifecycleJob runningJob = machine.begin(job, pending);
        List<NetworkLifecycleStep> runningSteps = pending.stream().map(item -> step(runningJob, item.stepId())).toList();
        persist(runningJob);
        NetworkLifecycleJob batchJob = runningJob;
        List<Async<ParallelStepOutcome>> starts = runningSteps.stream().map(step -> executeStep(network, instancesById.get(step.instanceId()), step, batchJob.operation()).handle((outcome, throwable) -> new ParallelStepOutcome(step, outcome, throwable)).thenApply(result -> {
            persistParallelOutcome(batchJob.jobId(), result);
            return result;
        })).toList();
        return Async.allOf(starts.toArray(Async[]::new)).thenCompose(unused -> {
            NetworkLifecycleJob checkpoint;
            synchronized (this) {
                checkpoint = jobs.getOrDefault(batchJob.jobId(), batchJob);
            }
            String failure = "";
            for (Async<ParallelStepOutcome> start : starts) {
                ParallelStepOutcome result = start.join();
                if (result.failure() != null) {
                    String message = rootMessage(result.failure());
                    if (failure.isBlank()) failure = message;
                }
            }
            if (!failure.isBlank()) {
                NetworkLifecycleJob failed = machine.failJob(checkpoint, failure);
                persist(failed);
                return Async.completed(failed);
            }
            persist(checkpoint);
            return continueJob(checkpoint, network, instancesById);
        });
    }

    private synchronized void persistParallelOutcome(String jobId, ParallelStepOutcome result) {
        NetworkLifecycleJob current = jobs.get(jobId);
        if (current == null) return;
        NetworkLifecycleJob completed = result.failure() == null
                ? machine.succeed(current, result.step(), result.outcome().skipped(), result.outcome().message())
                : machine.failStep(current, result.step(), rootMessage(result.failure()));
        persist(completed);
    }

    private NetworkLifecycleStep step(NetworkLifecycleJob job, String stepId) {
        return job.steps().stream().filter(step -> step.stepId().equals(stepId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Lifecycle step does not exist: " + stepId));
    }

    private Async<StepOutcome> executeStep(NetworkDefinition network, Instance instance, NetworkLifecycleStep step, NetworkLifecycleOperation operation) {
        return switch (step.action()) {
            case START -> start(network, instance, step);
            case STOP -> stop(instance);
            case DRAIN -> drain(network, instance, step, operation == NetworkLifecycleOperation.ROLLING_RESTART);
            case CAPACITY_GATE -> capacityGate(network, step);
            case MAINTENANCE -> runtimeMode(network, step, NetworkRuntimeNodeStatus.MAINTENANCE);
            case HEALTH_GATE -> healthGate(network, step);
            case RESUME -> runtimeMode(network, step, NetworkRuntimeNodeStatus.ONLINE);
        };
    }

    private Async<StepOutcome> start(NetworkDefinition network, Instance instance, NetworkLifecycleStep step) {
        NetworkMember member = member(network, step.nodeId());
        Async<Void> eula = member != null && member.isManaged() && !member.isProxy() ? acceptEula(instance) : Async.completed(null);
        return eula.thenCompose(unused -> status(instance)).thenCompose(observed -> {
            if (observed.ready()) {
                return Async.completed(new StepOutcome(true, instance.getName() + " is already ready"));
            }
            Async<?> request;
            String startOperationId = LifecycleManager.requestStart(instance);
            instance.setState(InstanceState.STARTING);
            if (isLocal(instance)) {
                request = AsyncTools.run(TaskSchedulers.current(), () -> {
                    try {
                        LocalServerControllerClient.start(instance);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            } else {
                request = JvmAsyncBridge.fromFuture(InstanceApi.of(instance).console().startServer());
            }
            long deadline = System.currentTimeMillis() + START_TIMEOUT.toMillis();
            return request.thenCompose(unused -> await(instance, true, deadline, startOperationId)).thenApply(status -> new StepOutcome(false, instance.getName() + " is ready")).whenComplete((ignored, error) -> {
                if (error != null) {
                    LifecycleManager.fail(instance, startOperationId, InstanceState.CRASHED, rootMessage(error));
                }
            });
        });
    }

    private Async<Void> acceptEula(Instance instance) {
        InstanceApi.FilesApi files = InstanceApi.of(instance).files();
        Path path = Path.of(instance.getPath()).resolve("eula.txt");
        return JvmAsyncBridge.fromFuture(files.exists(path)).thenCompose(exists -> {
            if (!exists) {
                return JvmAsyncBridge.fromFuture(files.write(path, NetworkStartPreparation.acceptMinecraftEula("")));
            }
            return JvmAsyncBridge.fromFuture(files.read(path)).thenCompose(content -> {
                String accepted = NetworkStartPreparation.acceptMinecraftEula(content);
                return accepted.equals(content) ? Async.completed(null) : JvmAsyncBridge.fromFuture(files.write(path, accepted));
            });
        });
    }

    private Async<StepOutcome> stop(Instance instance) {
        return status(instance).thenCompose(observed -> {
            if (stopped(observed.state())) {
                LifecycleManager.complete(instance, LifecycleManager.activeOperationId(instance), observed.state());
                return Async.completed(new StepOutcome(true, instance.getName() + " is already stopped"));
            }
            String stopOperationId = LifecycleManager.requestStop(instance);
            instance.setState(InstanceState.STOPPING);
            long deadline = System.currentTimeMillis() + STOP_TIMEOUT.toMillis();
            return JvmAsyncBridge.fromFuture(InstanceApi.of(instance).console().stopServer()).thenCompose(unused -> await(instance, false, deadline, stopOperationId)).thenApply(status -> new StepOutcome(false, instance.getName() + " stopped")).whenComplete((ignored, error) -> {
                if (error != null) {
                    LifecycleManager.restoreRunning(instance, stopOperationId, rootMessage(error));
                }
            });
        });
    }

    private Async<StepOutcome> drain(NetworkDefinition network, Instance instance, NetworkLifecycleStep step, boolean awaitRuntime) {
        NetworkMember member = member(network, step.nodeId());
        if (awaitRuntime && member != null && member.resyncEnabled() && network.runtime().enabled()) {
            return awaitRuntimePlayers(network.networkId(), step.nodeId(), System.currentTimeMillis() + DRAIN_TIMEOUT.toMillis()).thenApply(unused -> new StepOutcome(false, instance.getName() + " has no active players"));
        }
        return status(instance).thenCompose(observed -> {
            if (stopped(observed.state())) {
                return Async.completed(new StepOutcome(true, instance.getName() + " is stopped"));
            }
            return JvmAsyncBridge.fromFuture(InstanceApi.of(instance).players().getOnline()).thenCompose(players -> {
                if (!players.isEmpty()) {
                    return Async.failed(new IllegalStateException(instance.getName() + " still has " + players.size() + " players and no safe transfer route is active"));
                }
                return Async.completed(new StepOutcome(false, instance.getName() + " has no active players"));
            });
        });
    }

    private Async<StepOutcome> capacityGate(NetworkDefinition network, NetworkLifecycleStep step) {
        if (runtimeMonitor == null) {
            return Async.failed(new IllegalStateException("ReSync runtime is unavailable"));
        }
        NetworkRuntimeSnapshot snapshot = runtimeMonitor.snapshot(network.networkId());
        if (!snapshot.connected()) {
            return Async.failed(new IllegalStateException("ReSync runtime is required for a rolling restart"));
        }
        NetworkRuntimeNodePresence target = snapshot.node(step.nodeId()).orElse(null);
        if (target == null || target.status() != NetworkRuntimeNodeStatus.ONLINE) {
            return Async.failed(new IllegalStateException(step.routeName() + " is not healthy enough to restart"));
        }
        List<String> backendNodes = network.members().stream().filter(member -> !member.isProxy() && member.isManaged() && member.resyncEnabled()).map(NetworkMember::nodeId).toList();
        long healthyBackends = snapshot.nodes().values().stream().filter(presence -> backendNodes.contains(presence.nodeId()) && !presence.nodeId().equals(step.nodeId()) && presence.status() == NetworkRuntimeNodeStatus.ONLINE).count();
        if (healthyBackends == 0) {
            return Async.failed(new IllegalStateException("No other healthy backend can carry players during the restart"));
        }
        int availableSlots = snapshot.nodes().values().stream().filter(presence -> backendNodes.contains(presence.nodeId()) && !presence.nodeId().equals(step.nodeId()) && presence.status() == NetworkRuntimeNodeStatus.ONLINE).mapToInt(presence -> Math.max(0, presence.capacity() - presence.players())).sum();
        if (availableSlots < target.players()) {
            return Async.failed(new IllegalStateException("Healthy backends have " + availableSlots + " free slots but " + step.routeName() + " has " + target.players() + " players"));
        }
        return Async.completed(new StepOutcome(false, availableSlots + " healthy slots remain available"));
    }

    private Async<StepOutcome> runtimeMode(NetworkDefinition network, NetworkLifecycleStep step, NetworkRuntimeNodeStatus status) {
        NetworkMember member = member(network, step.nodeId());
        if (member == null || !member.resyncEnabled() || !network.runtime().enabled()) {
            return Async.completed(new StepOutcome(true, step.routeName() + " does not use ReSync runtime"));
        }
        if (runtimeMonitor == null) {
            return Async.failed(new IllegalStateException("ReSync runtime is unavailable"));
        }
        return runtimeMonitor.setNodeMode(network.networkId(), step.nodeId(), NetworkNodeStatus.valueOf(status.name())).thenApply(unused -> new StepOutcome(false, step.routeName() + " is " + status.name().toLowerCase(Locale.ROOT)));
    }

    private Async<StepOutcome> healthGate(NetworkDefinition network, NetworkLifecycleStep step) {
        NetworkMember member = member(network, step.nodeId());
        if (member == null || !member.resyncEnabled() || !network.runtime().enabled()) {
            return Async.completed(new StepOutcome(true, step.routeName() + " passed server readiness"));
        }
        if (runtimeMonitor == null) {
            return Async.failed(new IllegalStateException("ReSync runtime is unavailable"));
        }
        return awaitRuntimeHealth(network.networkId(), step.nodeId(), System.currentTimeMillis() + HEALTH_TIMEOUT.toMillis()).thenApply(unused -> new StepOutcome(false, step.routeName() + " is healthy"));
    }

    private Async<Void> awaitRuntimePlayers(String networkId, String nodeId, long deadline) {
        if (runtimeMonitor == null) {
            return Async.failed(new IllegalStateException("ReSync runtime is unavailable"));
        }
        NetworkRuntimeSnapshot snapshot = runtimeMonitor.snapshot(networkId);
        NetworkRuntimeNodePresence presence = snapshot.connected() ? snapshot.node(nodeId).orElse(null) : null;
        if (presence != null && presence.players() == 0) {
            return Async.completed(null);
        }
        if (System.currentTimeMillis() >= deadline) {
            int players = presence == null ? -1 : presence.players();
            return Async.failed(new IllegalStateException(players < 0 ? "ReSync runtime did not report drain completion" : players + " players remain after the drain timeout"));
        }
        return AsyncTools.delay(TaskSchedulers.current(), Duration.ofSeconds(1)).thenCompose(unused -> awaitRuntimePlayers(networkId, nodeId, deadline));
    }

    private Async<Void> awaitRuntimeHealth(String networkId, String nodeId, long deadline) {
        if (runtimeMonitor == null) {
            return Async.failed(new IllegalStateException("ReSync runtime is unavailable"));
        }
        NetworkRuntimeSnapshot snapshot = runtimeMonitor.snapshot(networkId);
        NetworkRuntimeNodePresence presence = snapshot.connected() ? snapshot.node(nodeId).orElse(null) : null;
        if (presence != null && (presence.status() == NetworkRuntimeNodeStatus.ONLINE || presence.status() == NetworkRuntimeNodeStatus.MAINTENANCE) && System.currentTimeMillis() - presence.observedAt() <= 20_000) {
            return Async.completed(null);
        }
        if (System.currentTimeMillis() >= deadline) {
            return Async.failed(new IllegalStateException("Backend did not return to live ReSync health before the timeout"));
        }
        return AsyncTools.delay(TaskSchedulers.current(), Duration.ofSeconds(1)).thenCompose(unused -> awaitRuntimeHealth(networkId, nodeId, deadline));
    }

    private NetworkMember member(NetworkDefinition network, String nodeId) {
        return network.members().stream().filter(candidate -> candidate.nodeId().equals(nodeId)).findFirst().orElse(null);
    }

    private Async<ExecutionProvider.ExecutionStatus> await(Instance instance, boolean ready, long deadline, String operationId) {
        return status(instance).thenCompose(observed -> {
            boolean complete = ready ? observed.ready() : stopped(observed.state());
            boolean failedStart = ready && (observed.state() == InstanceState.CRASHED || observed.state() == InstanceState.STOPPED);
            if (complete) {
                if (ready) {
                    LifecycleManager.markReady(instance, operationId);
                } else {
                    LifecycleManager.complete(instance, operationId, InstanceState.STOPPED);
                }
            } else if (failedStart) {
                String detail = observed.detail().isBlank() || "crashed".equalsIgnoreCase(observed.detail()) ? "" : " • " + observed.detail();
                String outcome = observed.state() == InstanceState.STOPPED || observed.detail().toLowerCase(Locale.ROOT).contains("code 0") ? " stopped while starting" : " crashed while starting";
                LifecycleManager.fail(instance, operationId, InstanceState.CRASHED, instance.getName() + outcome + detail);
            }
            if (complete) {
                instance.setState(observed.state());
                return Async.completed(observed);
            }
            if (failedStart) {
                return Async.failed(new IllegalStateException(instance.getName() + " did not become ready"));
            }
            if (System.currentTimeMillis() >= deadline) {
                String target = ready ? "ready" : "stopped";
                String message = instance.getName() + " did not become " + target + " before the timeout";
                if (ready) {
                    LifecycleManager.fail(instance, operationId, InstanceState.CRASHED, message);
                } else {
                    LifecycleManager.restoreRunning(instance, operationId, message);
                }
                return Async.failed(new IllegalStateException(message));
            }
            instance.setState(observed.state());
            return AsyncTools.delay(TaskSchedulers.current(), Duration.ofMillis(POLL_DELAY_MILLIS)).thenCompose(unused -> await(instance, ready, deadline, operationId));
        });
    }

    private Async<ExecutionProvider.ExecutionStatus> status(Instance instance) {
        return JvmAsyncBridge.fromFuture(InstanceApi.of(instance).console().getStatus());
    }

    private Map<String, Instance> indexInstances(Collection<Instance> instances) {
        Map<String, Instance> indexed = new LinkedHashMap<>();
        if (instances != null) {
            instances.stream().filter(instance -> instance != null && instance.getInstanceId() != null).forEach(instance -> indexed.put(instance.getInstanceId(), instance));
        }
        return indexed;
    }

    private boolean isLocal(Instance instance) {
        return instance.getBackendConfig() == null || "LOCAL".equalsIgnoreCase(instance.getBackendConfig().type);
    }

    private boolean stopped(InstanceState state) {
        return state == InstanceState.STOPPED || state == InstanceState.CRASHED;
    }

    private synchronized void persist(NetworkLifecycleJob job) {
        repository.save(job);
        jobs.put(job.jobId(), job);
    }

    private Async<NetworkLifecycleJob> runAdmitted(String networkId, String jobId, Supplier<Async<NetworkLifecycleJob>> operation) {
        Admission admission;
        synchronized (admissionGuard) {
            if (activeJobIds.contains(jobId)) {
                return Async.failed(new IllegalStateException("Network lifecycle job already has an active execution: " + jobId));
            }
            if (activeNetworkJobs.containsKey(networkId)) {
                return Async.failed(new IllegalStateException("Network has an active lifecycle operation: " + networkId));
            }
            activeNetworkJobs.put(networkId, jobId);
            activeJobIds.add(jobId);
            admission = new Admission(networkId, jobId);
        }
        try {
            Async<NetworkLifecycleJob> future = Objects.requireNonNull(operation.get(), "Lifecycle operation did not return a future");
            return future.whenComplete((ignored, throwable) -> release(admission));
        } catch (RuntimeException exception) {
            release(admission);
            return Async.failed(exception);
        }
    }

    private void release(Admission admission) {
        synchronized (admissionGuard) {
            if (Objects.equals(activeNetworkJobs.get(admission.networkId()), admission.jobId())) {
                activeNetworkJobs.remove(admission.networkId());
            }
            activeJobIds.remove(admission.jobId());
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record StepOutcome(boolean skipped, String message) {
    }

    private record ParallelStepOutcome(NetworkLifecycleStep step, StepOutcome outcome, Throwable failure) {
    }

    private record Admission(String networkId, String jobId) {
    }
}
