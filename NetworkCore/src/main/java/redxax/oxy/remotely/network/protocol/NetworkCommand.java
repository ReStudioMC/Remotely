package redxax.oxy.remotely.network.protocol;

import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkMemberRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface NetworkCommand permits NetworkCommand.Create, NetworkCommand.Save, NetworkCommand.Attach,
        NetworkCommand.Detach, NetworkCommand.RevisionCommand, NetworkCommand.JobCommand,
        NetworkCommand.Lifecycle, NetworkCommand.MemberLifecycle, NetworkCommand.ResumeLifecycle {
    int CURRENT_SCHEMA_VERSION = 1;

    int schemaVersion();

    String requestId();

    String networkId();

    long expectedRevision();

    Type type();

    enum Type {
        CREATE,
        SAVE,
        ATTACH,
        DETACH,
        DISSOLVE,
        RECONCILE,
        ROTATE_SECRET,
        RESUME,
        ROLLBACK,
        LIFECYCLE,
        MEMBER_LIFECYCLE,
        RESUME_LIFECYCLE
    }

    record Member(NetworkMemberSource source, String routeName, NetworkMemberRole role, int preferredPort,
                  int capacity, boolean resyncEnabled) {
        public Member {
            if (source == null) {
                throw new IllegalArgumentException("Member source is required");
            }
            routeName = text(routeName, 128, "routeName");
            role = role == null ? NetworkMemberRole.GAMEPLAY : role;
            if (preferredPort < 0 || preferredPort > 65535 || capacity < 0) {
                throw new IllegalArgumentException("Member port or capacity is invalid");
            }
        }
    }

    record Create(int schemaVersion, String requestId, String networkId, String name, int entryPort,
                  List<Member> members, Map<String, Object> settings) implements NetworkCommand {
        public Create {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            name = text(name, 80, "name");
            if (entryPort <= 0 || entryPort > 65535) {
                throw new IllegalArgumentException("entryPort is invalid");
            }
            members = members == null ? List.of() : List.copyOf(members);
            if (members.isEmpty() || members.size() > 32 || members.stream().anyMatch(member -> member == null)) {
                throw new IllegalArgumentException("Network members are invalid");
            }
            settings = copyMap(settings);
        }

        @Override
        public long expectedRevision() {
            return 0;
        }

        @Override
        public Type type() {
            return Type.CREATE;
        }
    }

    record Save(int schemaVersion, String requestId, String networkId, long expectedRevision,
                Map<String, Object> changes) implements NetworkCommand {
        public Save {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            changes = copyMap(changes);
        }

        @Override
        public Type type() {
            return Type.SAVE;
        }
    }

    record Attach(int schemaVersion, String requestId, String networkId, long expectedRevision,
                  Member member) implements NetworkCommand {
        public Attach {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            if (member == null) {
                throw new IllegalArgumentException("member is required");
            }
        }

        @Override
        public Type type() {
            return Type.ATTACH;
        }
    }

    record Detach(int schemaVersion, String requestId, String networkId, long expectedRevision,
                  String memberId) implements NetworkCommand {
        public Detach {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            memberId = text(memberId, 255, "memberId");
        }

        @Override
        public Type type() {
            return Type.DETACH;
        }
    }

    record RevisionCommand(int schemaVersion, String requestId, String networkId, long expectedRevision,
                           Type type) implements NetworkCommand {
        public RevisionCommand {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            if (type != Type.DISSOLVE && type != Type.RECONCILE && type != Type.ROTATE_SECRET) {
                throw new IllegalArgumentException("Revision command type is invalid");
            }
        }
    }

    record JobCommand(int schemaVersion, String requestId, String networkId, long expectedRevision,
                      String jobId, Type type) implements NetworkCommand {
        public JobCommand {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            jobId = text(jobId, 255, "jobId");
            if (type != Type.RESUME && type != Type.ROLLBACK) {
                throw new IllegalArgumentException("Job command type is invalid");
            }
        }
    }

    record Lifecycle(int schemaVersion, String requestId, String networkId, long expectedRevision,
                     NetworkLifecycleOperation operation) implements NetworkCommand {
        public Lifecycle {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            operation = operation == null ? NetworkLifecycleOperation.START : operation;
        }

        @Override
        public Type type() {
            return Type.LIFECYCLE;
        }
    }

    record MemberLifecycle(int schemaVersion, String requestId, String networkId, long expectedRevision,
                           String memberId, NetworkLifecycleOperation operation) implements NetworkCommand {
        public MemberLifecycle {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            memberId = text(memberId, 255, "memberId");
            operation = operation == null ? NetworkLifecycleOperation.START : operation;
            if (operation != NetworkLifecycleOperation.START && operation != NetworkLifecycleOperation.STOP) {
                throw new IllegalArgumentException("Member lifecycle supports only START and STOP");
            }
        }

        @Override
        public Type type() {
            return Type.MEMBER_LIFECYCLE;
        }
    }

    record ResumeLifecycle(int schemaVersion, String requestId, String networkId, long expectedRevision,
                           String lifecycleJobId) implements NetworkCommand {
        public ResumeLifecycle {
            schemaVersion = schema(schemaVersion);
            requestId = uuid(requestId, "requestId");
            networkId = text(networkId, 255, "networkId");
            expectedRevision = revision(expectedRevision);
            lifecycleJobId = text(lifecycleJobId, 255, "lifecycleJobId");
        }

        @Override
        public Type type() {
            return Type.RESUME_LIFECYCLE;
        }
    }

    private static int schema(int value) {
        if (value != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported network command schema " + value);
        }
        return value;
    }

    private static long revision(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        return value;
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String text(String value, int maximum, String field) {
        String normalized = required(value, field);
        if (normalized.length() > maximum || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String uuid(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        if (values == null) {
            return Map.of();
        }
        return copyMap(values, 0);
    }

    private static Map<String, Object> copyMap(Map<?, ?> values, int depth) {
        if (depth > 8 || values.size() > 256) {
            throw new IllegalArgumentException("Command values are too large");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("Command value key is required");
            }
            String name = text(String.valueOf(key), 128, "value key");
            copy.put(name, copyValue(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map, depth);
        }
        if (value instanceof List<?> list) {
            if (depth > 8 || list.size() > 256) {
                throw new IllegalArgumentException("Command values are too large");
            }
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item, depth + 1)));
            return List.copyOf(copy);
        }
        if (value instanceof String text) {
            if (text.length() > 65_536 || text.indexOf(0) >= 0) {
                throw new IllegalArgumentException("Command text value is too large");
            }
            return text;
        }
        if (value instanceof Number number) {
            double portable = number.doubleValue();
            if (!Double.isFinite(portable)) {
                throw new IllegalArgumentException("Command number value must be finite");
            }
            if (portable == Math.rint(portable)) {
                if (Math.abs(portable) > 9_007_199_254_740_991D) {
                    throw new IllegalArgumentException("Command integer value exceeds the portable range");
                }
                return (long) portable;
            }
            return portable;
        }
        if (value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Command values must use Map, List, string, number, or boolean values");
    }
}
