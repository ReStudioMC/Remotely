package redxax.oxy.remotely.network;

import java.util.Locale;
import java.util.Objects;

public record NetworkLifecycleStep(String stepId, String instanceId, String nodeId, String routeName,
                                   NetworkLifecycleAction action, NetworkLifecycleStepStatus status,
                                   long startedAt, long completedAt, String message) {
    public NetworkLifecycleStep {
        stepId = normalize(stepId);
        instanceId = normalize(instanceId);
        nodeId = normalize(nodeId);
        routeName = normalize(routeName);
        action = action == null ? NetworkLifecycleAction.START : action;
        status = status == null ? NetworkLifecycleStepStatus.PENDING : status;
        startedAt = Math.max(0, startedAt);
        completedAt = Math.max(0, completedAt);
        message = normalize(message);
    }

    public static NetworkLifecycleStep pending(NetworkMember member, NetworkLifecycleAction action, int index) {
        Objects.requireNonNull(member, "member");
        NetworkLifecycleAction resolved = Objects.requireNonNull(action, "action");
        return new NetworkLifecycleStep(resolved.name().toLowerCase(Locale.ROOT) + "-" + index + "-" + member.nodeId(),
                member.instanceId(), member.nodeId(), member.routeName(), resolved, NetworkLifecycleStepStatus.PENDING,
                0, 0, "Waiting");
    }

    public NetworkLifecycleStep running() {
        return running(NetworkClock.SYSTEM);
    }

    public NetworkLifecycleStep running(NetworkClock clock) {
        return new NetworkLifecycleStep(stepId, instanceId, nodeId, routeName, action, NetworkLifecycleStepStatus.RUNNING,
                Objects.requireNonNull(clock, "clock").millis(), 0, actionMessage());
    }

    public NetworkLifecycleStep succeeded(boolean skipped, String updatedMessage) {
        return succeeded(skipped, updatedMessage, NetworkClock.SYSTEM);
    }

    public NetworkLifecycleStep succeeded(boolean skipped, String updatedMessage, NetworkClock clock) {
        return new NetworkLifecycleStep(stepId, instanceId, nodeId, routeName, action,
                skipped ? NetworkLifecycleStepStatus.SKIPPED : NetworkLifecycleStepStatus.SUCCEEDED, startedAt,
                Objects.requireNonNull(clock, "clock").millis(), updatedMessage);
    }

    public NetworkLifecycleStep failed(String updatedMessage) {
        return failed(updatedMessage, NetworkClock.SYSTEM);
    }

    public NetworkLifecycleStep failed(String updatedMessage, NetworkClock clock) {
        return new NetworkLifecycleStep(stepId, instanceId, nodeId, routeName, action, NetworkLifecycleStepStatus.FAILED,
                startedAt, Objects.requireNonNull(clock, "clock").millis(), updatedMessage);
    }

    public boolean complete() {
        return status == NetworkLifecycleStepStatus.SUCCEEDED || status == NetworkLifecycleStepStatus.SKIPPED;
    }

    private String actionMessage() {
        return switch (action) {
            case START -> "Starting " + routeName;
            case STOP -> "Stopping " + routeName;
            case DRAIN -> "Draining " + routeName;
            case CAPACITY_GATE -> "Checking Capacity For " + routeName;
            case MAINTENANCE -> "Starting Maintenance For " + routeName;
            case HEALTH_GATE -> "Checking Health For " + routeName;
            case RESUME -> "Resuming " + routeName;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
