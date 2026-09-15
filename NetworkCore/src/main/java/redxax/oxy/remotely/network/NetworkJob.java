package redxax.oxy.remotely.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record NetworkJob(int schemaVersion, String jobId, String networkId, long networkRevision, NetworkJobType type, NetworkJobStatus status, String initiator, long createdAt, long updatedAt, int attempt, String message, Map<String, String> context, List<NetworkJobDocument> documents, List<NetworkValidationIssue> issues) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public NetworkJob {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        jobId = normalize(jobId);
        networkId = normalize(networkId);
        networkRevision = Math.max(1, networkRevision);
        type = type == null ? NetworkJobType.RECONCILE : type;
        status = status == null ? NetworkJobStatus.PLANNING : status;
        initiator = normalize(initiator);
        long now = NetworkClock.SYSTEM.millis();
        createdAt = createdAt <= 0 ? now : createdAt;
        updatedAt = updatedAt <= 0 ? createdAt : updatedAt;
        attempt = Math.max(0, attempt);
        message = normalize(message);
        context = context == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(context));
        documents = documents == null ? List.of() : List.copyOf(documents);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static NetworkJob create(NetworkReconciliationPlan plan, NetworkJobType type, String initiator) {
        return create(plan, type, initiator, Map.of());
    }

    public static NetworkJob create(NetworkReconciliationPlan plan, NetworkJobType type, String initiator, NetworkClock clock) {
        return create(plan, type, initiator, Map.of(), clock);
    }

    public static NetworkJob create(NetworkReconciliationPlan plan, NetworkJobType type, String initiator, Map<String, String> context) {
        return create(plan, type, initiator, context, NetworkClock.SYSTEM);
    }

    public static NetworkJob create(NetworkReconciliationPlan plan, NetworkJobType type, String initiator, Map<String, String> context, NetworkClock clock) {
        NetworkJobStatus status = plan.canApply() ? NetworkJobStatus.PLANNING : NetworkJobStatus.BLOCKED;
        String message = plan.canApply() ? "Preparing network changes" : plan.issues().stream().filter(NetworkValidationIssue::blocksPersistence).map(NetworkValidationIssue::message).findFirst().orElse("Network changes are blocked");
        long now = Objects.requireNonNull(clock, "clock").millis();
        return new NetworkJob(CURRENT_SCHEMA_VERSION, plan.planId(), plan.networkId(), plan.networkRevision(), type, status, initiator, now, now, 0, message, context, List.of(), plan.issues());
    }

    public NetworkJob prepared(List<NetworkJobDocument> updatedDocuments) {
        return prepared(updatedDocuments, NetworkClock.SYSTEM);
    }

    public NetworkJob prepared(List<NetworkJobDocument> updatedDocuments, NetworkClock clock) {
        return update(NetworkJobStatus.READY, "Network changes are ready", updatedDocuments, attempt, clock);
    }

    public NetworkJob startingAttempt() {
        return startingAttempt(NetworkClock.SYSTEM);
    }

    public NetworkJob startingAttempt(NetworkClock clock) {
        return update(NetworkJobStatus.RUNNING, "Applying network changes", documents, attempt + 1, clock);
    }

    public NetworkJob withStatus(NetworkJobStatus updatedStatus, String updatedMessage) {
        return withStatus(updatedStatus, updatedMessage, NetworkClock.SYSTEM);
    }

    public NetworkJob withStatus(NetworkJobStatus updatedStatus, String updatedMessage, NetworkClock clock) {
        return update(updatedStatus, updatedMessage, documents, attempt, clock);
    }

    public NetworkJob withDocumentState(NetworkConfigDocumentKey key, NetworkJobDocumentState state) {
        return withDocumentState(key, state, NetworkClock.SYSTEM);
    }

    public NetworkJob withDocumentState(NetworkConfigDocumentKey key, NetworkJobDocumentState state, NetworkClock clock) {
        List<NetworkJobDocument> updated = new ArrayList<>(documents.size());
        boolean found = false;
        for (NetworkJobDocument document : documents) {
            if (document.key().equals(key)) {
                updated.add(document.withState(state));
                found = true;
            } else {
                updated.add(document);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Network job does not contain " + key.path());
        }
        return update(status, message, updated, attempt, clock);
    }

    public boolean canResume() {
        return status == NetworkJobStatus.INTERRUPTED || status == NetworkJobStatus.READY || status == NetworkJobStatus.FAILED;
    }

    public boolean canRollback() {
        return !documents.isEmpty() && status != NetworkJobStatus.ROLLED_BACK && status != NetworkJobStatus.BLOCKED;
    }

    public boolean restartRequired() {
        return Boolean.parseBoolean(context.getOrDefault("restartRequired", "false"));
    }

    private NetworkJob update(NetworkJobStatus updatedStatus, String updatedMessage, List<NetworkJobDocument> updatedDocuments, int updatedAttempt, NetworkClock clock) {
        return new NetworkJob(schemaVersion, jobId, networkId, networkRevision, type, updatedStatus, initiator, createdAt, Objects.requireNonNull(clock, "clock").millis(), updatedAttempt, updatedMessage, context, updatedDocuments, issues);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
