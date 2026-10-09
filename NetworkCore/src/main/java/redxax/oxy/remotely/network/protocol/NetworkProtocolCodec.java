package redxax.oxy.remotely.network.protocol;

import redxax.oxy.remotely.network.NetworkConfigDocumentKey;
import redxax.oxy.remotely.network.NetworkJob;
import redxax.oxy.remotely.network.NetworkJobDocument;
import redxax.oxy.remotely.network.NetworkJobDocumentState;
import redxax.oxy.remotely.network.NetworkJobStatus;
import redxax.oxy.remotely.network.NetworkJobType;
import redxax.oxy.remotely.network.NetworkLifecycleAction;
import redxax.oxy.remotely.network.NetworkLifecycleJob;
import redxax.oxy.remotely.network.NetworkLifecycleOperation;
import redxax.oxy.remotely.network.NetworkLifecycleStatus;
import redxax.oxy.remotely.network.NetworkLifecycleStep;
import redxax.oxy.remotely.network.NetworkLifecycleStepStatus;
import redxax.oxy.remotely.network.NetworkMemberRole;
import redxax.oxy.remotely.network.NetworkValidationIssue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NetworkProtocolCodec {
    public Map<String, Object> encode(NetworkCommand command) {
        Map<String, Object> value = envelope(command);
        switch (command) {
            case NetworkCommand.Create create -> {
                value.put("name", create.name());
                value.put("entryPort", create.entryPort());
                value.put("members", create.members().stream().map(this::member).toList());
                value.put("settings", create.settings());
            }
            case NetworkCommand.Save save -> value.put("changes", save.changes());
            case NetworkCommand.Attach attach -> value.put("member", member(attach.member()));
            case NetworkCommand.Detach detach -> value.put("memberId", detach.memberId());
            case NetworkCommand.RevisionCommand ignored -> {
            }
            case NetworkCommand.JobCommand job -> value.put("jobId", job.jobId());
            case NetworkCommand.Lifecycle lifecycle -> value.put("operation", lifecycle.operation().name());
            case NetworkCommand.MemberLifecycle lifecycle -> {
                value.put("memberId", lifecycle.memberId());
                value.put("operation", lifecycle.operation().name());
            }
            case NetworkCommand.ResumeLifecycle resume -> value.put("lifecycleJobId", resume.lifecycleJobId());
        }
        return Map.copyOf(value);
    }

    public NetworkCommand decodeCommand(Map<String, ?> value) {
        int schema = integer(value, "schemaVersion", 0);
        String requestId = string(value, "requestId");
        String networkId = string(value, "networkId");
        long revision = longValue(value, "expectedRevision", 0);
        NetworkCommand.Type type = enumeration(value, "type", NetworkCommand.Type.class, null);
        if (type == null) {
            throw new IllegalArgumentException("Network command type is required");
        }
        return switch (type) {
            case CREATE -> new NetworkCommand.Create(schema, requestId, networkId, string(value, "name"),
                    integer(value, "entryPort", 0), maps(value.get("members")).stream().map(this::decodeMember).toList(),
                    map(value.get("settings")));
            case SAVE -> new NetworkCommand.Save(schema, requestId, networkId, revision, map(value.get("changes")));
            case ATTACH -> new NetworkCommand.Attach(schema, requestId, networkId, revision, decodeMember(map(value.get("member"))));
            case DETACH -> new NetworkCommand.Detach(schema, requestId, networkId, revision, string(value, "memberId"));
            case DISSOLVE, RECONCILE, ROTATE_SECRET -> new NetworkCommand.RevisionCommand(schema, requestId, networkId, revision, type);
            case RESUME, ROLLBACK -> new NetworkCommand.JobCommand(schema, requestId, networkId, revision, string(value, "jobId"), type);
            case LIFECYCLE -> new NetworkCommand.Lifecycle(schema, requestId, networkId, revision,
                    enumeration(value, "operation", NetworkLifecycleOperation.class, NetworkLifecycleOperation.START));
            case MEMBER_LIFECYCLE -> new NetworkCommand.MemberLifecycle(schema, requestId, networkId, revision,
                    string(value, "memberId"), enumeration(value, "operation", NetworkLifecycleOperation.class, NetworkLifecycleOperation.START));
            case RESUME_LIFECYCLE -> new NetworkCommand.ResumeLifecycle(schema, requestId, networkId, revision, string(value, "lifecycleJobId"));
        };
    }

    public Map<String, Object> encode(NetworkOperationStatus status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", status.schemaVersion());
        value.put("requestId", status.requestId());
        value.put("networkId", status.networkId());
        value.put("command", status.command().name());
        value.put("state", status.state().name());
        value.put("stage", status.stage().name());
        value.put("operationId", status.operationId());
        value.put("networkRevision", status.networkRevision());
        value.put("message", status.message());
        value.put("createdAt", status.createdAt());
        value.put("updatedAt", status.updatedAt());
        if (status.job() != null) {
            value.put("job", job(status.job()));
        }
        if (status.lifecycleJob() != null) {
            value.put("lifecycleJob", lifecycleJob(status.lifecycleJob()));
        }
        value.put("members", status.members().stream().map(this::memberStatus).toList());
        return Map.copyOf(value);
    }

    public NetworkOperationStatus decodeStatus(Map<String, ?> value) {
        Map<String, Object> job = map(value.get("job"));
        Map<String, Object> lifecycle = map(value.get("lifecycleJob"));
        return new NetworkOperationStatus(integer(value, "schemaVersion", 0),
                string(value, "requestId"), string(value, "networkId"),
                enumeration(value, "command", NetworkCommand.Type.class, NetworkCommand.Type.RECONCILE),
                enumeration(value, "state", NetworkOperationState.class, NetworkOperationState.ADMITTED),
                enumeration(value, "stage", NetworkOperationStage.class, NetworkOperationStage.VALIDATING),
                string(value, "operationId"), longValue(value, "networkRevision", 0), string(value, "message"),
                longValue(value, "createdAt", 0), longValue(value, "updatedAt", 0),
                job.isEmpty() ? null : decodeJob(job), lifecycle.isEmpty() ? null : decodeLifecycleJob(lifecycle),
                maps(value.get("members")).stream().map(this::decodeMemberStatus).toList());
    }

    private Map<String, Object> envelope(NetworkCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Network command is required");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", command.schemaVersion());
        value.put("requestId", command.requestId());
        value.put("networkId", command.networkId());
        value.put("expectedRevision", command.expectedRevision());
        value.put("type", command.type().name());
        return value;
    }

    private Map<String, Object> member(NetworkCommand.Member member) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", source(member.source()));
        value.put("routeName", member.routeName());
        value.put("role", member.role().name());
        value.put("preferredPort", member.preferredPort());
        value.put("capacity", member.capacity());
        value.put("resyncEnabled", member.resyncEnabled());
        return Map.copyOf(value);
    }

    private NetworkCommand.Member decodeMember(Map<String, ?> value) {
        return new NetworkCommand.Member(decodeSource(map(value.get("source"))), string(value, "routeName"),
                enumeration(value, "role", NetworkMemberRole.class, NetworkMemberRole.GAMEPLAY),
                integer(value, "preferredPort", 0), integer(value, "capacity", 0), bool(value, "resyncEnabled", false));
    }

    private Map<String, Object> source(NetworkMemberSource source) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", source.kind().name());
        switch (source) {
            case NetworkMemberSource.ExistingServer existing -> value.put("serverId", existing.serverId());
            case NetworkMemberSource.Draft draft -> {
                value.put("poolId", draft.poolId());
                value.put("draftId", draft.draftId());
                value.put("createRequestId", draft.createRequestId());
                value.put("activationRequestId", draft.activationRequestId());
                value.put("expectedRevision", draft.expectedRevision());
                value.put("metadata", draftMetadata(draft.metadata()));
                value.put("installer", compute(draft.installer()));
                value.put("runtime", compute(draft.runtime()));
                value.put("retained", storage(draft.retained()));
            }
            case NetworkMemberSource.External external -> {
                value.put("externalId", external.externalId());
                value.put("name", external.name());
                value.put("address", external.address());
            }
        }
        return Map.copyOf(value);
    }

    private NetworkMemberSource decodeSource(Map<String, ?> value) {
        NetworkMemberSource.Kind kind = enumeration(value, "kind", NetworkMemberSource.Kind.class, null);
        if (kind == null) {
            throw new IllegalArgumentException("Network member source kind is required");
        }
        return switch (kind) {
            case EXISTING_SERVER -> new NetworkMemberSource.ExistingServer(string(value, "serverId"));
            case DRAFT -> new NetworkMemberSource.Draft(string(value, "poolId"), string(value, "draftId"),
                    string(value, "createRequestId"), string(value, "activationRequestId"),
                    longValue(value, "expectedRevision", 0), decodeDraftMetadata(map(value.get("metadata"))),
                    decodeCompute(map(value.get("installer"))), decodeCompute(map(value.get("runtime"))),
                    decodeStorage(map(value.get("retained"))));
            case EXTERNAL -> new NetworkMemberSource.External(string(value, "externalId"), string(value, "name"), string(value, "address"));
        };
    }

    private Map<String, Object> draftMetadata(NetworkMemberSource.DraftMetadata metadata) {
        Map<String, Object> value = new LinkedHashMap<>(Map.of("name", metadata.name(), "gameId", metadata.gameId(), "profileId", metadata.profileId(),
                "settings", metadata.settings(), "initialFiles", metadata.initialFiles()));
        if (metadata.subdomain() != null) value.put("subdomain", metadata.subdomain());
        return value;
    }

    private NetworkMemberSource.DraftMetadata decodeDraftMetadata(Map<String, ?> value) {
        return new NetworkMemberSource.DraftMetadata(string(value, "name"), string(value, "gameId"), string(value, "profileId"),
                stringMap(value.get("settings")), stringMap(value.get("initialFiles")), value.containsKey("subdomain") ? string(value, "subdomain") : null);
    }

    private Map<String, Object> compute(NetworkMemberSource.Compute compute) {
        return Map.of("ramMiB", compute.ramMiB(), "cpuQuotaPercent", compute.cpuQuotaPercent());
    }

    private NetworkMemberSource.Compute decodeCompute(Map<String, ?> value) {
        return new NetworkMemberSource.Compute(longValue(value, "ramMiB", 0), longValue(value, "cpuQuotaPercent", 0));
    }

    private Map<String, Object> storage(NetworkMemberSource.Storage storage) {
        return Map.of("diskMiB", storage.diskMiB(), "backupMiB", storage.backupMiB());
    }

    private NetworkMemberSource.Storage decodeStorage(Map<String, ?> value) {
        return new NetworkMemberSource.Storage(longValue(value, "diskMiB", 0), longValue(value, "backupMiB", 0));
    }

    private Map<String, Object> memberStatus(NetworkOperationStatus.MemberStatus member) {
        return Map.of("memberId", member.memberId(), "serverId", member.serverId(), "childJobId", member.childJobId(),
                "state", member.state(), "message", member.message());
    }

    private NetworkOperationStatus.MemberStatus decodeMemberStatus(Map<String, ?> value) {
        return new NetworkOperationStatus.MemberStatus(string(value, "memberId"), string(value, "serverId"),
                string(value, "childJobId"), string(value, "state"), string(value, "message"));
    }

    private Map<String, Object> job(NetworkJob job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", job.schemaVersion());
        value.put("jobId", job.jobId());
        value.put("networkId", job.networkId());
        value.put("networkRevision", job.networkRevision());
        value.put("type", job.type().name());
        value.put("status", job.status().name());
        value.put("initiator", job.initiator());
        value.put("createdAt", job.createdAt());
        value.put("updatedAt", job.updatedAt());
        value.put("attempt", job.attempt());
        value.put("message", job.message());
        value.put("context", job.context());
        value.put("documents", job.documents().stream().map(this::document).toList());
        value.put("issues", job.issues().stream().map(this::issue).toList());
        return Map.copyOf(value);
    }

    private NetworkJob decodeJob(Map<String, ?> value) {
        return new NetworkJob(integer(value, "schemaVersion", NetworkJob.CURRENT_SCHEMA_VERSION), string(value, "jobId"),
                string(value, "networkId"), longValue(value, "networkRevision", 1),
                enumeration(value, "type", NetworkJobType.class, NetworkJobType.RECONCILE),
                enumeration(value, "status", NetworkJobStatus.class, NetworkJobStatus.PLANNING), string(value, "initiator"),
                longValue(value, "createdAt", 0), longValue(value, "updatedAt", 0), integer(value, "attempt", 0),
                string(value, "message"), stringMap(value.get("context")),
                maps(value.get("documents")).stream().map(this::decodeDocument).toList(),
                maps(value.get("issues")).stream().map(this::decodeIssue).toList());
    }

    private Map<String, Object> document(NetworkJobDocument document) {
        return Map.of("instanceId", document.key().instanceId(), "path", document.key().path(),
                "applyOrder", document.applyOrder(), "originalExists", document.originalExists(),
                "originalHash", document.originalHash(), "desiredHash", document.desiredHash(), "state", document.state().name());
    }

    private NetworkJobDocument decodeDocument(Map<String, ?> value) {
        return new NetworkJobDocument(new NetworkConfigDocumentKey(string(value, "instanceId"), string(value, "path")),
                integer(value, "applyOrder", 0), bool(value, "originalExists", false), string(value, "originalHash"),
                string(value, "desiredHash"), enumeration(value, "state", NetworkJobDocumentState.class, NetworkJobDocumentState.PENDING));
    }

    private Map<String, Object> issue(NetworkValidationIssue issue) {
        return Map.of("severity", issue.severity().name(), "code", text(issue.code()), "subject", text(issue.subject()), "message", text(issue.message()));
    }

    private NetworkValidationIssue decodeIssue(Map<String, ?> value) {
        return new NetworkValidationIssue(enumeration(value, "severity", NetworkValidationIssue.Severity.class, NetworkValidationIssue.Severity.ERROR),
                string(value, "code"), string(value, "subject"), string(value, "message"));
    }

    private Map<String, Object> lifecycleJob(NetworkLifecycleJob job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", job.schemaVersion());
        value.put("jobId", job.jobId());
        value.put("networkId", job.networkId());
        value.put("networkRevision", job.networkRevision());
        value.put("operation", job.operation().name());
        value.put("status", job.status().name());
        value.put("initiator", job.initiator());
        value.put("createdAt", job.createdAt());
        value.put("updatedAt", job.updatedAt());
        value.put("attempt", job.attempt());
        value.put("message", job.message());
        value.put("steps", job.steps().stream().map(this::lifecycleStep).toList());
        return Map.copyOf(value);
    }

    private NetworkLifecycleJob decodeLifecycleJob(Map<String, ?> value) {
        return new NetworkLifecycleJob(integer(value, "schemaVersion", NetworkLifecycleJob.CURRENT_SCHEMA_VERSION),
                string(value, "jobId"), string(value, "networkId"), longValue(value, "networkRevision", 1),
                enumeration(value, "operation", NetworkLifecycleOperation.class, NetworkLifecycleOperation.START),
                enumeration(value, "status", NetworkLifecycleStatus.class, NetworkLifecycleStatus.READY), string(value, "initiator"),
                longValue(value, "createdAt", 0), longValue(value, "updatedAt", 0), integer(value, "attempt", 0),
                string(value, "message"), maps(value.get("steps")).stream().map(this::decodeLifecycleStep).toList());
    }

    private Map<String, Object> lifecycleStep(NetworkLifecycleStep step) {
        return Map.ofEntries(Map.entry("stepId", step.stepId()), Map.entry("instanceId", step.instanceId()),
                Map.entry("nodeId", step.nodeId()), Map.entry("routeName", step.routeName()),
                Map.entry("action", step.action().name()), Map.entry("status", step.status().name()),
                Map.entry("startedAt", step.startedAt()), Map.entry("completedAt", step.completedAt()),
                Map.entry("message", step.message()));
    }

    private NetworkLifecycleStep decodeLifecycleStep(Map<String, ?> value) {
        return new NetworkLifecycleStep(string(value, "stepId"), string(value, "instanceId"), string(value, "nodeId"),
                string(value, "routeName"), enumeration(value, "action", NetworkLifecycleAction.class, NetworkLifecycleAction.START),
                enumeration(value, "status", NetworkLifecycleStepStatus.class, NetworkLifecycleStepStatus.PENDING),
                longValue(value, "startedAt", 0), longValue(value, "completedAt", 0), string(value, "message"));
    }

    private static Map<String, Object> map(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("Protocol object value is invalid");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("Protocol object key must be a string");
            }
            result.put(name, item);
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException("Protocol list value is invalid");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> mapped = map(item);
            result.add(mapped);
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        map(value).forEach((key, item) -> {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("Protocol string map value is invalid");
            }
            result.put(key, text);
        });
        return Map.copyOf(result);
    }

    private static String string(Map<String, ?> value, String key) {
        Object item = value.get(key);
        if (item == null) {
            return "";
        }
        if (!(item instanceof String text)) {
            throw new IllegalArgumentException("Protocol " + key + " value must be a string");
        }
        return text;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Map<String, ?> value, String key, int fallback) {
        return Math.toIntExact(exactLong(value.get(key), fallback));
    }

    private static long longValue(Map<String, ?> value, String key, long fallback) {
        return exactLong(value.get(key), fallback);
    }

    private static long exactLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            if (value instanceof BigInteger integer) {
                return integer.longValueExact();
            }
            if (value instanceof BigDecimal decimal) {
                return decimal.longValueExact();
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number)) {
                    throw new ArithmeticException();
                }
                return BigDecimal.valueOf(number).longValueExact();
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Protocol integer value is outside its range", exception);
        }
        throw new IllegalArgumentException("Protocol integer value is invalid");
    }

    private static boolean bool(Map<String, ?> value, String key, boolean fallback) {
        Object item = value.get(key);
        if (item == null) {
            return fallback;
        }
        if (!(item instanceof Boolean bool)) {
            throw new IllegalArgumentException("Protocol " + key + " value must be a boolean");
        }
        return bool;
    }

    private static <T extends Enum<T>> T enumeration(Map<String, ?> value, String key, Class<T> type, T fallback) {
        String name = string(value, key);
        if (name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported " + key + " value " + name, exception);
        }
    }
}
