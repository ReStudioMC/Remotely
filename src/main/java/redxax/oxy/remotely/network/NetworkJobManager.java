package redxax.oxy.remotely.network;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rebase.instance.Instance;
import restudio.rescreen.platform.Async;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NetworkJobManager {
    private final NetworkJobRepository repository;
    private final NetworkConfigurationTransaction transaction;
    private final Map<String, NetworkJob> jobs = new LinkedHashMap<>();
    private final List<Consumer<List<NetworkJob>>> listeners = BrowserSafeState.list();
    private volatile String loadError = "";

    public NetworkJobManager(Path applicationDirectory, NetworkConfigurationTransaction transaction) {
        this.repository = new NetworkJobRepository(applicationDirectory);
        this.transaction = Objects.requireNonNull(transaction, "Configuration transaction is required");
        reload();
    }

    public synchronized void reload() {
        try {
            List<NetworkJob> loaded = repository.loadAll();
            jobs.clear();
            for (NetworkJob job : loaded) {
                if (jobs.putIfAbsent(job.jobId(), job) != null) {
                    throw new NetworkPersistenceException("Duplicate network job ID " + job.jobId());
                }
            }
            List<NetworkJob> interrupted = jobs.values().stream().filter(job -> job.status().requiresRecovery() && job.status() != NetworkJobStatus.INTERRUPTED).map(job -> job.withStatus(NetworkJobStatus.INTERRUPTED, "Remotely closed before this job finished")).toList();
            interrupted.forEach(this::persist);
            loadError = "";
            notifyListeners();
        } catch (NetworkPersistenceException exception) {
            loadError = exception.getMessage() == null ? "Failed to load network jobs" : exception.getMessage();
        }
    }

    public synchronized List<NetworkJob> getJobs() {
        return jobs.values().stream().sorted(Comparator.comparingLong(NetworkJob::updatedAt).reversed()).toList();
    }

    public synchronized List<NetworkJob> getJobs(String networkId) {
        return getJobs().stream().filter(job -> job.networkId().equals(networkId)).toList();
    }

    public synchronized Optional<NetworkJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public Async<NetworkJob> execute(NetworkDefinition network, NetworkReconciliationPlan plan, Collection<Instance> instances, NetworkJobType type, String initiator) {
        return execute(network, plan, instances, type, initiator, Map.of());
    }

    public Async<NetworkJob> execute(NetworkDefinition network, NetworkReconciliationPlan plan, Collection<Instance> instances, NetworkJobType type, String initiator, Map<String, String> context) {
        Objects.requireNonNull(network, "Network is required");
        Objects.requireNonNull(plan, "Network plan is required");
        NetworkJob job = NetworkJob.create(plan, type, initiator, jobContext(plan, context));
        synchronized (this) {
            if (jobs.containsKey(job.jobId())) {
                return Async.failed(new IllegalArgumentException("Network job already exists: " + job.jobId()));
            }
            persist(job);
        }
        if (!plan.canApply()) {
            return Async.completed(job);
        }
        return finishFailures(job.jobId(), prepareAndApply(job, network, plan, instances, false));
    }

    public Async<NetworkJob> executePrepared(NetworkDefinition network, NetworkPreparedPlan prepared, Collection<Instance> instances, NetworkJobType type, String initiator) {
        return executePrepared(network, prepared, instances, type, initiator, Map.of());
    }

    public Async<NetworkJob> executePrepared(NetworkDefinition network, NetworkPreparedPlan prepared, Collection<Instance> instances, NetworkJobType type, String initiator, Map<String, String> context) {
        Objects.requireNonNull(network, "Network is required");
        Objects.requireNonNull(prepared, "Prepared network plan is required");
        NetworkJob job = NetworkJob.create(prepared.plan(), type, initiator, jobContext(prepared.plan(), context));
        synchronized (this) {
            if (jobs.containsKey(job.jobId())) {
                return Async.failed(new IllegalArgumentException("Network job already exists: " + job.jobId()));
            }
            persist(job);
        }
        if (!prepared.plan().canApply()) {
            return Async.completed(job);
        }
        return finishFailures(job.jobId(), applyPrepared(job, network, prepared, instances, false));
    }

    public Async<NetworkJob> resume(String jobId, NetworkDefinition network, NetworkReconciliationPlan currentPlan, Collection<Instance> instances) {
        NetworkJob job;
        synchronized (this) {
            job = requireJob(jobId);
            if (!job.canResume()) {
                return Async.failed(new IllegalStateException("Network job cannot be resumed from " + job.status()));
            }
            if (!job.networkId().equals(network.networkId()) || job.networkRevision() != network.revision()) {
                return Async.failed(new IllegalStateException("Network changed after this job was created"));
            }
        }
        NetworkReconciliationPlan resumedPlan = new NetworkReconciliationPlan(job.jobId(), currentPlan.networkId(), currentPlan.networkRevision(), job.createdAt(), currentPlan.mutations(), currentPlan.issues(), currentPlan.strategy());
        if (!resumedPlan.canApply()) {
            return finishFailures(jobId, Async.failed(new IllegalStateException(resumedPlan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("Network recovery is blocked"))));
        }
        return finishFailures(jobId, prepareAndApply(job, network, resumedPlan, instances, true));
    }

    public Async<NetworkJob> rollback(String jobId, Collection<Instance> instances) {
        NetworkJob job;
        synchronized (this) {
            job = requireJob(jobId);
            if (!job.canRollback()) {
                return Async.failed(new IllegalStateException("Network job has nothing to roll back"));
            }
            persist(job.withStatus(NetworkJobStatus.ROLLING_BACK, "Restoring configuration backups"));
        }
        NetworkTransactionListener listener = listener(jobId);
        Async<NetworkJob> rollback = transaction.rollback(jobId, job.documents(), instances, listener).thenApply(unused -> {
            synchronized (this) {
                NetworkJob rolledBack = requireJob(jobId).withStatus(NetworkJobStatus.ROLLED_BACK, "Network changes rolled back");
                persist(rolledBack);
                return rolledBack;
            }
        });
        return finishFailures(jobId, rollback);
    }

    public String getLoadError() {
        return loadError;
    }

    public synchronized NetworkJob completeBestEffort(String jobId, String message) {
        NetworkJob completed = requireJob(jobId).withStatus(NetworkJobStatus.SUCCEEDED, message);
        persist(completed);
        return completed;
    }

    public synchronized NetworkJob failCompletion(String jobId, String message, Throwable throwable) {
        String detail = throwable == null ? message : message + ": " + rootMessage(throwable);
        NetworkJob failed = requireJob(jobId).withStatus(NetworkJobStatus.FAILED, detail);
        persist(failed);
        return failed;
    }

    public Path getDirectory() {
        return repository.getDirectory();
    }

    public void addListener(Consumer<List<NetworkJob>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<List<NetworkJob>> listener) {
        listeners.remove(listener);
    }

    private Async<NetworkJob> prepareAndApply(NetworkJob job, NetworkDefinition network, NetworkReconciliationPlan plan, Collection<Instance> instances, boolean recovery) {
        return transaction.prepare(plan, instances).thenCompose(prepared -> applyPrepared(job, network, prepared, instances, recovery));
    }

    private Async<NetworkJob> applyPrepared(NetworkJob job, NetworkDefinition network, NetworkPreparedPlan prepared, Collection<Instance> instances, boolean recovery) {
        List<NetworkJobDocument> described = transaction.describe(prepared, network, instances);
        NetworkJob ready;
        synchronized (this) {
            NetworkJob current = requireJob(job.jobId());
            List<NetworkJobDocument> documents = recovery && !current.documents().isEmpty()
                    ? NetworkExecutionPlan.recover(current.documents(), described) : described;
            ready = current.prepared(documents);
            persist(ready);
            ready = ready.startingAttempt();
            persist(ready);
        }
        NetworkTransactionListener listener = listener(job.jobId());
        return transaction.apply(prepared, network, instances, listener, ready.documents()).thenApply(result -> finishApply(job.jobId(), result));
    }

    private synchronized NetworkJob finishApply(String jobId, NetworkApplyResult result) {
        NetworkJob current = requireJob(jobId);
        NetworkJob completed;
        if (result.applied()) {
            completed = current.withStatus(NetworkJobStatus.SUCCEEDED, current.restartRequired() ? "Restart Affected Servers To Apply Changes" : result.message());
        } else {
            boolean partialChangesRemain = current.documents().stream().anyMatch(document -> document.state() == NetworkJobDocumentState.APPLIED);
            NetworkJobStatus status = result.rolledBack() && !partialChangesRemain ? NetworkJobStatus.ROLLED_BACK : NetworkJobStatus.FAILED;
            String message = partialChangesRemain ? result.message() + "; partial changes remain available for resume or rollback" : result.message();
            completed = current.withStatus(status, message);
        }
        persist(completed);
        return completed;
    }

    private Map<String, String> jobContext(NetworkReconciliationPlan plan, Map<String, String> context) {
        Map<String, String> updated = new LinkedHashMap<>(context == null ? Map.of() : context);
        updated.put("restartRequired", Boolean.toString(plan.restartRequired()));
        String restartInstanceIds = plan.changes().stream().filter(NetworkConfigMutation::restartRequired).map(NetworkConfigMutation::instanceId).distinct().sorted().collect(Collectors.joining(","));
        if (!restartInstanceIds.isBlank()) {
            updated.put("restartInstanceIds", restartInstanceIds);
        }
        return updated;
    }

    private NetworkTransactionListener listener(String jobId) {
        return new NetworkTransactionListener() {
            @Override
            public void onDocumentApplied(NetworkConfigDocumentKey key) {
                updateDocument(jobId, key, NetworkJobDocumentState.APPLIED);
            }

            @Override
            public void onRollbackStarted() {
                updateStatus(jobId, NetworkJobStatus.ROLLING_BACK, "Restoring configuration backups");
            }

            @Override
            public void onDocumentRolledBack(NetworkConfigDocumentKey key) {
                updateDocument(jobId, key, NetworkJobDocumentState.ROLLED_BACK);
            }
        };
    }

    private synchronized void updateDocument(String jobId, NetworkConfigDocumentKey key, NetworkJobDocumentState state) {
        NetworkJob current = requireJob(jobId);
        persist(current.withDocumentState(key, state));
    }

    private synchronized void updateStatus(String jobId, NetworkJobStatus status, String message) {
        NetworkJob current = requireJob(jobId);
        persist(current.withStatus(status, message));
    }

    private Async<NetworkJob> finishFailures(String jobId, Async<NetworkJob> future) {
        return future.handle((job, throwable) -> {
            if (throwable == null) {
                return Async.completed(job);
            }
            synchronized (this) {
                NetworkJob failed = requireJob(jobId).withStatus(NetworkJobStatus.FAILED, rootMessage(throwable));
                persist(failed);
                return Async.completed(failed);
            }
        }).thenCompose(result -> result);
    }

    private synchronized NetworkJob requireJob(String jobId) {
        NetworkJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Network job does not exist: " + jobId);
        }
        return job;
    }

    private synchronized void persist(NetworkJob job) {
        repository.save(job);
        jobs.put(job.jobId(), job);
        notifyListeners();
    }

    private void notifyListeners() {
        List<NetworkJob> snapshot = getJobs();
        listeners.forEach(listener -> listener.accept(snapshot));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
