package redxax.oxy.remotely.network;

import java.util.List;
import java.util.Objects;

public final class NetworkLifecycleMachine {
    private final NetworkClock clock;

    public NetworkLifecycleMachine() {
        this(NetworkClock.SYSTEM);
    }

    public NetworkLifecycleMachine(NetworkClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public NetworkLifecycleJob start(NetworkLifecycleJob job) {
        Objects.requireNonNull(job, "job");
        if (job.status() != NetworkLifecycleStatus.READY && !job.canResume()) {
            throw new IllegalStateException("Network lifecycle job cannot start from " + job.status());
        }
        NetworkLifecycleJob ready = job;
        if (job.canResume() && (job.operation() == NetworkLifecycleOperation.START || job.operation() == NetworkLifecycleOperation.RESTART)) {
            int pending = -1;
            for (int index = 0; index < job.steps().size(); index++) {
                NetworkLifecycleStep step = job.steps().get(index);
                if (step.action() == NetworkLifecycleAction.START && !step.complete()) { pending = index; break; }
            }
            for (int index = 0; index < pending; index++) {
                NetworkLifecycleStep step = job.steps().get(index);
                if (step.action() == NetworkLifecycleAction.HEALTH_GATE && step.complete()) {
                    ready = ready.withStep(new NetworkLifecycleStep(step.stepId(), step.instanceId(), step.nodeId(), step.routeName(), step.action(), NetworkLifecycleStepStatus.PENDING, 0, 0, "Check Current Health"), clock);
                }
            }
        }
        return ready.startingAttempt(clock);
    }

    public List<NetworkLifecycleStep> next(NetworkLifecycleJob job) {
        Objects.requireNonNull(job, "job");
        NetworkLifecycleStep first = job.steps().stream().filter(step -> !step.complete()).findFirst().orElse(null);
        if (first == null) {
            return List.of();
        }
        if ((job.operation() == NetworkLifecycleOperation.START || job.operation() == NetworkLifecycleOperation.RESTART)
                && first.action() == NetworkLifecycleAction.START) {
            return job.steps().stream().dropWhile(NetworkLifecycleStep::complete).takeWhile(step -> step.action() == NetworkLifecycleAction.START).filter(step -> !step.complete()).toList();
        }
        return List.of(first);
    }

    public NetworkLifecycleJob begin(NetworkLifecycleJob job, List<NetworkLifecycleStep> steps) {
        NetworkLifecycleJob updated = Objects.requireNonNull(job, "job");
        for (NetworkLifecycleStep step : List.copyOf(Objects.requireNonNull(steps, "steps"))) {
            if (step.complete()) {
                throw new IllegalArgumentException("Completed lifecycle step cannot begin: " + step.stepId());
            }
            updated = updated.withStep(step.running(clock), clock);
        }
        return updated;
    }

    public NetworkLifecycleJob succeed(NetworkLifecycleJob job, NetworkLifecycleStep step, boolean skipped, String message) {
        return Objects.requireNonNull(job, "job").withStep(current(job, step).succeeded(skipped, message, clock), clock);
    }

    public NetworkLifecycleJob fail(NetworkLifecycleJob job, NetworkLifecycleStep step, String message) {
        return failJob(failStep(job, step, message), message);
    }

    public NetworkLifecycleJob failStep(NetworkLifecycleJob job, NetworkLifecycleStep step, String message) {
        return Objects.requireNonNull(job, "job").withStep(current(job, step).failed(message, clock), clock);
    }

    public NetworkLifecycleJob failJob(NetworkLifecycleJob job, String message) {
        return Objects.requireNonNull(job, "job").withStatus(NetworkLifecycleStatus.FAILED, message, clock);
    }

    public NetworkLifecycleJob finish(NetworkLifecycleJob job) {
        Objects.requireNonNull(job, "job");
        if (job.steps().stream().anyMatch(step -> !step.complete())) {
            throw new IllegalStateException("Network lifecycle job still has incomplete steps");
        }
        return job.withStatus(NetworkLifecycleStatus.SUCCEEDED, successMessage(job.operation()), clock);
    }

    private NetworkLifecycleStep current(NetworkLifecycleJob job, NetworkLifecycleStep step) {
        Objects.requireNonNull(step, "step");
        return job.steps().stream().filter(candidate -> candidate.stepId().equals(step.stepId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Lifecycle step does not exist: " + step.stepId()));
    }

    private String successMessage(NetworkLifecycleOperation operation) {
        return switch (operation) {
            case START -> "Network is ready";
            case STOP -> "Network is stopped";
            case RESTART -> "Network restarted";
            case ROLLING_RESTART -> "Network backends rolled without losing healthy capacity";
            case DRAIN -> "Network has no active backend players";
        };
    }
}
