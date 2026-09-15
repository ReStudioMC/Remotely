package redxax.oxy.remotely.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record NetworkLifecycleJob(int schemaVersion, String jobId, String networkId, long networkRevision,
                                  NetworkLifecycleOperation operation, NetworkLifecycleStatus status, String initiator,
                                  long createdAt, long updatedAt, int attempt, String message,
                                  List<NetworkLifecycleStep> steps) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public NetworkLifecycleJob {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        jobId = normalize(jobId);
        networkId = normalize(networkId);
        networkRevision = Math.max(1, networkRevision);
        operation = operation == null ? NetworkLifecycleOperation.START : operation;
        status = status == null ? NetworkLifecycleStatus.READY : status;
        initiator = normalize(initiator);
        long now = NetworkClock.SYSTEM.millis();
        createdAt = createdAt <= 0 ? now : createdAt;
        updatedAt = updatedAt <= 0 ? createdAt : updatedAt;
        attempt = Math.max(0, attempt);
        message = normalize(message);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static NetworkLifecycleJob create(NetworkDefinition network, NetworkLifecycleOperation operation,
                                             String initiator, List<NetworkLifecycleStep> steps) {
        return create(network, operation, initiator, steps, NetworkClock.SYSTEM);
    }

    public static NetworkLifecycleJob create(NetworkDefinition network, NetworkLifecycleOperation operation,
                                             String initiator, List<NetworkLifecycleStep> steps, NetworkClock clock) {
        long now = Objects.requireNonNull(clock, "clock").millis();
        return new NetworkLifecycleJob(CURRENT_SCHEMA_VERSION, UUID.randomUUID().toString(), network.networkId(),
                network.revision(), operation, NetworkLifecycleStatus.READY, initiator, now, now, 0,
                "Network operation is ready", steps);
    }

    public NetworkLifecycleJob startingAttempt() {
        return startingAttempt(NetworkClock.SYSTEM);
    }

    public NetworkLifecycleJob startingAttempt(NetworkClock clock) {
        return update(NetworkLifecycleStatus.RUNNING, operationMessage(), steps, attempt + 1, clock);
    }

    public NetworkLifecycleJob withStep(NetworkLifecycleStep updatedStep) {
        return withStep(updatedStep, NetworkClock.SYSTEM);
    }

    public NetworkLifecycleJob withStep(NetworkLifecycleStep updatedStep, NetworkClock clock) {
        List<NetworkLifecycleStep> updated = new ArrayList<>(steps.size());
        boolean found = false;
        for (NetworkLifecycleStep step : steps) {
            if (step.stepId().equals(updatedStep.stepId())) {
                updated.add(updatedStep);
                found = true;
            } else {
                updated.add(step);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Lifecycle step does not exist: " + updatedStep.stepId());
        }
        return update(status, updatedStep.message(), updated, attempt, clock);
    }

    public NetworkLifecycleJob withStatus(NetworkLifecycleStatus updatedStatus, String updatedMessage) {
        return withStatus(updatedStatus, updatedMessage, NetworkClock.SYSTEM);
    }

    public NetworkLifecycleJob withStatus(NetworkLifecycleStatus updatedStatus, String updatedMessage, NetworkClock clock) {
        return update(updatedStatus, updatedMessage, steps, attempt, clock);
    }

    public boolean canResume() {
        return status == NetworkLifecycleStatus.INTERRUPTED || status == NetworkLifecycleStatus.FAILED;
    }

    private NetworkLifecycleJob update(NetworkLifecycleStatus updatedStatus, String updatedMessage,
                                       List<NetworkLifecycleStep> updatedSteps, int updatedAttempt, NetworkClock clock) {
        return new NetworkLifecycleJob(schemaVersion, jobId, networkId, networkRevision, operation, updatedStatus, initiator,
                createdAt, Objects.requireNonNull(clock, "clock").millis(), updatedAttempt, updatedMessage, updatedSteps);
    }

    private String operationMessage() {
        return switch (operation) {
            case START -> "Starting network";
            case STOP -> "Stopping network";
            case RESTART -> "Restarting network";
            case ROLLING_RESTART -> "Restarting Backends";
            case DRAIN -> "Draining network";
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
