package redxax.oxy.remotely.network.protocol;

import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkLifecycleJob;

import java.util.List;
import java.util.UUID;

public record NetworkOperationStatus(int schemaVersion, String requestId, String networkId, NetworkCommand.Type command,
                                     NetworkOperationState state, NetworkOperationStage stage, String operationId,
                                     long networkRevision, String message, long createdAt, long updatedAt,
                                     NetworkJob job, NetworkLifecycleJob lifecycleJob, List<MemberStatus> members) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public NetworkOperationStatus {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported network operation status schema " + schemaVersion);
        }
        requestId = uuid(requestId, "requestId");
        networkId = text(networkId, 255, "networkId");
        command = command == null ? NetworkCommand.Type.RECONCILE : command;
        state = state == null ? NetworkOperationState.ADMITTED : state;
        stage = stage == null ? NetworkOperationStage.VALIDATING : stage;
        operationId = text(operationId, 255, "operationId");
        if (networkRevision < 0 || createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("Operation status revision or time is invalid");
        }
        message = bounded(message, 4096);
        members = members == null ? List.of() : List.copyOf(members);
        if (members.size() > 32 || members.stream().anyMatch(member -> member == null)) {
            throw new IllegalArgumentException("Operation member status is invalid");
        }
        if (job != null && lifecycleJob != null) {
            throw new IllegalArgumentException("Operation status cannot contain configuration and lifecycle jobs together");
        }
    }

    public record MemberStatus(String memberId, String serverId, String childJobId, String state, String message) {
        public MemberStatus {
            memberId = bounded(memberId, 255);
            serverId = bounded(serverId, 255);
            childJobId = bounded(childJobId, 255);
            state = bounded(state, 64);
            message = bounded(message, 4096);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String text(String value, int maximum, String field) {
        String normalized = bounded(value, maximum);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String bounded(String value, int maximum) {
        String normalized = normalize(value);
        if (normalized.length() > maximum || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Operation status text is invalid");
        }
        return normalized;
    }

    private static String uuid(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 36) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }
}
