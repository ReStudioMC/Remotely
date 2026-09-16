package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import redxax.oxy.remotely.worldgen.data.WorldGenSerializer;
import redxax.oxy.remotely.worldgen.registry.WorldGenNodeDefinition;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

final class WorldGenProtocolHandler {
    private final String serverId;
    private final Gson gson;
    private final Consumer<JsonObject> jobConsumer;
    private final LongSupplier establishedEpoch;
    private final WorldGenAuthorityTransition authorityTransition;

    WorldGenProtocolHandler(String serverId, Gson gson, Consumer<JsonObject> jobConsumer) {
        this(serverId, gson, jobConsumer, () -> -1L, (epoch, identity, message) -> false);
    }

    WorldGenProtocolHandler(String serverId, Gson gson, Consumer<JsonObject> jobConsumer, LongSupplier establishedEpoch) {
        this(serverId, gson, jobConsumer, establishedEpoch, (epoch, identity, message) -> false);
    }

    WorldGenProtocolHandler(String serverId, Gson gson, Consumer<JsonObject> jobConsumer, LongSupplier establishedEpoch,
                            LongPredicate authorityTransition) {
        this(serverId, gson, jobConsumer, establishedEpoch,
            (epoch, identity, message) -> WorldGenManager.getInstance().acceptWorldGenAuthorityTransition(serverId,
                epoch, identity, message, authorityTransition));
    }

    WorldGenProtocolHandler(String serverId, Gson gson, Consumer<JsonObject> jobConsumer, LongSupplier establishedEpoch,
                            WorldGenAuthorityTransition authorityTransition) {
        this.serverId = serverId;
        this.gson = gson;
        this.jobConsumer = jobConsumer;
        this.establishedEpoch = establishedEpoch != null ? establishedEpoch : () -> -1L;
        this.authorityTransition = authorityTransition != null ? authorityTransition : (epoch, identity, message) -> false;
    }

    void handle(byte[] data) {
        long startedAt = System.nanoTime();
        if (data == null || data.length < 1) {
            ReSyncFlowClient.traceLifecycle(serverId, "worldgen_packet_response", "resourceKey", "worldgen:catalog",
                "operation", "decode", "requestId", "", "correlationId", "", "traceId", "", "mutationId", "",
                "generation", -1L, "authorityEpoch", -1L, "revision", -1L, "packetId", "missing", "outcome",
                "dropped", "reason", data == null ? "packet_missing" : "packet_empty", "count", 0,
                "elapsedMs", elapsedMillis(startedAt));
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte packetId = buffer.get();
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        try {
            Envelope envelope = envelope(json, startedAt);
            if (!acceptEpoch(envelope)) {
                return;
            }
            tracePacket(packetId, envelope, "admitted", "authority_epoch_current", 1);
            switch (packetId) {
                case 0x23 -> handlePreviewStatus(envelope);
                case 0x25 -> handleRegistrySnapshot(envelope);
                case 0x35 -> handleProjectData(envelope);
                case 0x36 -> handleProjectList(envelope);
                case 0x37 -> handleProjectSaveAck(envelope);
                case 0x38 -> handleCompileDiagnostics(envelope);
                case 0x39 -> handleJob(envelope);
                default -> {
                    tracePacket(packetId, envelope, "dropped", "unknown_packet", 0);
                    ReLog.logger(LogTypes.FLOW).source(LogSource.server(serverId, serverId)).component(WorldGenProtocolHandler.class).with("packetId", String.format("0x%02X", packetId)).warn("Unknown world generation packet");
                }
            }
        } catch (Exception e) {
            ReSyncFlowClient.traceLifecycle(serverId, "worldgen_packet_response", "resourceKey", "worldgen:catalog",
                "operation", packetName(packetId), "requestId", "", "correlationId", "", "traceId", "",
                "mutationId", "", "generation", -1L, "authorityEpoch", -1L, "revision", -1L, "packetId",
                String.format("0x%02X", packetId), "outcome", "dropped", "reason",
                "decode_exception:" + TaskIdentities.typeName(e), "count", 0, "elapsedMs", elapsedMillis(startedAt));
            ReLog.logger(LogTypes.FLOW).source(LogSource.server(serverId, serverId)).component(WorldGenProtocolHandler.class).with("packetId", String.format("0x%02X", packetId)).error("Could not process world generation packet", e);
        }
    }

    private void tracePacket(byte packetId, Envelope envelope, String outcome, String reason, int count) {
        long startedAt = envelope == null ? System.nanoTime() : envelope.startedAtNanos();
        String requestId = envelope == null ? "" : textField(envelope, "requestId");
        String correlationId = envelope == null ? "" : textField(envelope, "correlationId");
        String traceId = envelope == null ? "" : textField(envelope, "traceId");
        String mutationId = envelope == null ? "" : textField(envelope, "mutationId");
        String operationId = envelope == null ? "" : textField(envelope, "operationId");
        String projectId = envelope == null ? "" : textField(envelope, "projectId");
        String resourceId = envelope == null ? "" : textField(envelope, "resourceId");
        String resourceKey = projectId != null && !projectId.isBlank() ? "worldgen:project:" + projectId
            : resourceId != null && !resourceId.isBlank() ? "worldgen:project:" + resourceId : "worldgen:catalog";
        ReSyncFlowClient.traceLifecycle(serverId, "worldgen_packet_response", "resourceKey", resourceKey,
            "operation", packetName(packetId), "requestId", requestId == null ? "" : requestId,
            "correlationId", correlationId == null ? "" : correlationId, "traceId", traceId == null ? "" : traceId,
            "mutationId", mutationId == null ? "" : mutationId, "operationId", operationId == null ? "" : operationId,
            "generation", -1L, "authorityEpoch", envelope == null ? -1L : envelope.authorityEpoch(),
            "revision", envelope == null ? -1L : envelope.revision(), "packetId",
            String.format("0x%02X", packetId & 0xFF), "outcome", outcome == null ? "unknown" : outcome,
            "reason", reason == null ? "" : reason, "count", count, "elapsedMs", elapsedMillis(startedAt));
    }

    private String packetName(byte packetId) {
        return switch (packetId & 0xFF) {
            case 0x22 -> "preview_stop";
            case 0x23 -> "preview_status";
            case 0x24 -> "registry_request";
            case 0x25 -> "registry_snapshot";
            case 0x30 -> "project_save";
            case 0x31 -> "preview_apply";
            case 0x32 -> "project_request";
            case 0x33 -> "project_delete";
            case 0x34 -> "project_list_request";
            case 0x35 -> "project_data";
            case 0x36 -> "project_list";
            case 0x37 -> "project_mutation_ack";
            case 0x38 -> "compile_diagnostics";
            case 0x39 -> "job";
            default -> "unknown";
        };
    }

    private static long elapsedMillis(long startedAt) {
        return BrowserSafeState.nanosToMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private void handlePreviewStatus(Envelope envelope) {
        if (!hasPositiveRevision(envelope)) {
            tracePacket((byte) 0x23, envelope, "dropped", "revision_missing_or_invalid", 0);
            return;
        }
        JsonElement payload = envelope.data();
        Map<?, ?> status = gson.fromJson(payload, Map.class);
        String previewId = status != null && status.get("previewId") != null ? String.valueOf(status.get("previewId")) : "";
        String state = status != null && status.get("status") != null ? String.valueOf(status.get("status")) : "error";
        String message = status != null && status.get("message") != null ? String.valueOf(status.get("message")) : state;
        if (payload != null && payload.isJsonObject()) {
            JsonObject object = payload.getAsJsonObject();
            String action = textField(object, "action");
            String resourceId = textField(object, "resourceId");
            String projectId = textField(object, "projectId");
            String requestId = textField(object, "requestId");
            String mutationId = textField(object, "mutationId");
            String operationId = textField(object, "operationId");
            String canonicalAction = canonicalWorldGenAction(action);
            if (WorldGenManager.WORLD_GEN_PROJECT_SAVE_ACTION.equals(canonicalAction)
                || WorldGenManager.WORLD_GEN_PROJECT_DELETE_ACTION.equals(canonicalAction)) {
                if (!hasCanonicalIdentity(canonicalAction, projectId, resourceId, requestId, mutationId, operationId)) {
                    tracePacket((byte) 0x23, envelope, "dropped", "mutation_identity_invalid", 0);
                    return;
                }
                tracePacket((byte) 0x23, envelope, "dispatched", "mutation_status", 1);
                WorldGenManager.getInstance().handleWorldGenMutationStatus(serverId, canonicalAction, resourceId, requestId,
                    mutationId, operationId, state, message, envelope.revision(), envelope.authorityEpoch());
                return;
            }
        }
        tracePacket((byte) 0x23, envelope, "dispatched", "preview_status", 1);
        WorldGenManager.getInstance().handlePreviewStatus(serverId, previewId, state, message);
    }

    private void handleRegistrySnapshot(Envelope envelope) {
        if (!hasPositiveRevision(envelope) || !envelope.epochPresent() || !envelope.epochValid()
            || envelope.authorityEpoch() < 1L) {
            tracePacket((byte) 0x25, envelope, "dropped", "revision_or_epoch_invalid", 0);
            return;
        }
        JsonElement payload = envelope.data();
        if (payload == null || payload.isJsonNull() || !payload.isJsonObject()) {
            tracePacket((byte) 0x25, envelope, "dropped", "registry_payload_not_object", 0);
            return;
        }
        JsonObject snapshot = payload.getAsJsonObject();
        if (!snapshot.has("nodes") || !snapshot.has("capabilities")) {
            tracePacket((byte) 0x25, envelope, "dropped", "registry_fields_missing", 0);
            return;
        }
        JsonElement nodes = snapshot.get("nodes");
        if (nodes == null || nodes.isJsonNull() || !nodes.isJsonArray()) {
            tracePacket((byte) 0x25, envelope, "dropped", "registry_nodes_not_array", 0);
            return;
        }
        List<WorldGenNodeDefinition> definitions = new ArrayList<>();
        for (JsonElement element : nodes.getAsJsonArray()) {
            WorldGenNodeDefinition definition = gson.fromJson(element, WorldGenNodeDefinition.class);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        JsonElement capabilities = snapshot.get("capabilities");
        Object capabilitySnapshot = capabilities == null || capabilities.isJsonNull()
            ? null : gson.fromJson(capabilities, Object.class);
        tracePacket((byte) 0x25, envelope, "dispatched", "registry_snapshot", definitions.size());
        WorldGenManager.getInstance().applyRegistrySnapshot(serverId, definitions,
            capabilitySnapshot, true, envelope.revision(), envelope.authorityEpoch());
    }

    private void handleProjectData(Envelope envelope) {
        if (!hasPositiveRevision(envelope)) {
            tracePacket((byte) 0x35, envelope, "dropped", "revision_missing_or_invalid", 0);
            return;
        }
        JsonElement payload = envelope.data();
        WorldGenProject project = WorldGenSerializer.deserializeProject(payload.toString());
        if (project != null) {
            tracePacket((byte) 0x35, envelope, "dispatched", "project_data", 1);
            WorldGenManager.getInstance().handleProjectData(serverId, project, envelope.revision(), envelope.authorityEpoch());
        } else {
            tracePacket((byte) 0x35, envelope, "dropped", "project_deserialization_empty", 0);
        }
    }

    private void handleProjectList(Envelope envelope) {
        if (!hasPositiveRevision(envelope)) {
            tracePacket((byte) 0x36, envelope, "dropped", "revision_missing_or_invalid", 0);
            return;
        }
        List<String> ids = projectIds(envelope.data());
        if (ids == null) {
            tracePacket((byte) 0x36, envelope, "dropped", "project_list_invalid", 0);
            return;
        }
        tracePacket((byte) 0x36, envelope, "dispatched", "project_list", ids.size());
        WorldGenManager.getInstance().handleProjectList(serverId, ids, envelope.revision(),
            envelope.authorityEpoch());
    }

    private void handleProjectSaveAck(Envelope envelope) {
        if (!hasPositiveRevision(envelope)) {
            tracePacket((byte) 0x37, envelope, "dropped", "revision_missing_or_invalid", 0);
            return;
        }
        JsonElement payload = envelope.data();
        if (!payload.isJsonObject()) {
            tracePacket((byte) 0x37, envelope, "dropped", "ack_payload_not_object", 0);
            return;
        }
        JsonObject object = payload.getAsJsonObject();
        String action = textField(object, "action");
        String projectId = textField(object, "projectId");
        String resourceId = textField(object, "resourceId");
        String requestId = textField(object, "requestId");
        String mutationId = textField(object, "mutationId");
        String operationId = textField(object, "operationId");
        boolean deleted = object.has("deleted") && object.get("deleted").isJsonPrimitive()
            && object.get("deleted").getAsBoolean();
        String canonicalAction = canonicalWorldGenAction(action);
        if (!hasCanonicalIdentity(canonicalAction, projectId, resourceId, requestId, mutationId, operationId)) {
            tracePacket((byte) 0x37, envelope, "dropped", "mutation_identity_invalid", 0);
            return;
        }
        boolean actionMatches = deleted ? WorldGenManager.WORLD_GEN_PROJECT_DELETE_ACTION.equals(canonicalAction)
            : WorldGenManager.WORLD_GEN_PROJECT_SAVE_ACTION.equals(canonicalAction);
        if (!actionMatches) {
            tracePacket((byte) 0x37, envelope, "dropped", "mutation_action_mismatch", 0);
            return;
        }
        tracePacket((byte) 0x37, envelope, "dispatched", deleted ? "delete_ack" : "save_ack", 1);
        WorldGenManager.getInstance().handleProjectMutationAck(serverId, projectId, envelope.revision(), requestId,
                 mutationId, operationId, deleted, envelope.authorityEpoch());
    }

    private void handleCompileDiagnostics(Envelope envelope) {
        JsonElement payload = envelope.data();
        if (!payload.isJsonObject()) {
            tracePacket((byte) 0x38, envelope, "dropped", "diagnostics_payload_not_object", 0);
            return;
        }
        JsonObject object = payload.getAsJsonObject();
        String projectId = textField(object, "projectId");
        String resourceId = textField(object, "resourceId");
        String requestId = textField(object, "requestId");
        String mutationId = textField(object, "mutationId");
        String operationId = textField(object, "operationId");
        String action = canonicalWorldGenAction(textField(object, "action"));
        if (!hasCanonicalIdentity(action, projectId, resourceId, requestId, mutationId, operationId)) {
            tracePacket((byte) 0x38, envelope, "dropped", "mutation_identity_invalid", 0);
            return;
        }
        if (!hasPositiveRevision(envelope)) {
            tracePacket((byte) 0x38, envelope, "dropped", "revision_missing_or_invalid", 0);
            return;
        }
        tracePacket((byte) 0x38, envelope, "dispatched", "compile_diagnostics", 1);
        WorldGenManager.getInstance().handleCompileDiagnostics(serverId, payload.toString(), projectId, requestId,
            mutationId, operationId, envelope.revision(), envelope.authorityEpoch());
    }

    private void handleJob(Envelope envelope) {
        if (jobConsumer != null && hasPositiveRevision(envelope) && envelope.root().isJsonObject()) {
            tracePacket((byte) 0x39, envelope, "dispatched", "job", 1);
            jobConsumer.accept(envelope.root().getAsJsonObject());
        } else {
            tracePacket((byte) 0x39, envelope, "dropped", jobConsumer == null ? "job_consumer_missing"
                : "job_revision_or_root_invalid", 0);
        }
    }

    private boolean hasPositiveRevision(Envelope envelope) {
        return envelope != null && envelope.revisionPresent() && envelope.revisionValid()
            && envelope.revision() >= 1L;
    }

    private List<String> projectIds(JsonElement payload) {
        if (payload == null || payload.isJsonNull()) {
            return null;
        }
        JsonElement list = payload;
        if (payload.isJsonObject()) {
            JsonObject object = payload.getAsJsonObject();
            String selectedField = null;
            int supportedFieldCount = 0;
            for (String field : List.of("ids", "projectIds", "projects")) {
                if (object.has(field)) {
                    selectedField = field;
                    supportedFieldCount++;
                }
            }
            if (supportedFieldCount != 1) {
                return null;
            }
            list = object.get(selectedField);
        }
        if (list == null || !list.isJsonArray()) {
            return null;
        }
        JsonArray array = list.getAsJsonArray();
        List<String> ids = new ArrayList<>(array.size());
        Set<String> seen = new HashSet<>();
        for (JsonElement value : array) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return null;
            }
            String id = value.getAsString();
            if (id == null || id.isBlank() || !seen.add(id)) {
                return null;
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private Envelope envelope(String json, long startedAt) {
        JsonElement root = JsonParser.parseString(json);
        Metadata metadata = metadata(root);
        return new Envelope(root, metadata.authorityEpoch, metadata.epochPresent, metadata.epochValid,
            metadata.revision, metadata.revisionPresent, metadata.revisionValid, startedAt);
    }

    private boolean acceptEpoch(Envelope envelope) {
        long established = establishedEpoch.getAsLong();
        if (established < 1L || !envelope.epochPresent() || !envelope.epochValid()) {
            tracePacket((byte) -1, envelope, "dropped", established < 1L ? "established_epoch_missing"
                : !envelope.epochPresent() ? "response_epoch_missing" : "response_epoch_invalid", 0);
            return false;
        }
        WorldGenManager manager = WorldGenManager.getInstance();
        if (envelope.authorityEpoch() > established) {
            AuthorityTransition transition = authorityTransition(envelope);
            WorldGenManager.WorldGenMutationIdentity identity = new WorldGenManager.WorldGenMutationIdentity(
                transition.action(), transition.projectId(), transition.resourceId(), transition.requestId(),
                transition.mutationId(), transition.operationId());
            if (!transition.transition() || !hasCanonicalIdentity(identity.action(), identity.projectId(), identity.resourceId(),
                identity.requestId(), identity.mutationId(), identity.operationId())) {
                tracePacket((byte) -1, envelope, "dropped", "uncorrelated_authority_transition", 0);
                ReLog.logger(LogTypes.FLOW).source(LogSource.server(serverId, serverId)).component(WorldGenProtocolHandler.class)
                    .with("authorityEpoch", envelope.authorityEpoch()).warn("Rejected uncorrelated world generation authority transition");
                return false;
            }
            boolean accepted = authorityTransition.accept(envelope.authorityEpoch(), identity, transition.message());
            tracePacket((byte) -1, envelope, accepted ? "admitted" : "dropped",
                accepted ? "authority_transition_accepted" : "authority_transition_rejected", accepted ? 1 : 0);
            return accepted;
        }
        if (envelope.authorityEpoch() < established) {
            tracePacket((byte) -1, envelope, "dropped", "stale_authority_epoch", 0);
            return false;
        }
        WorldGenManager.EpochDecision decision = manager.acceptAuthorityEpoch(serverId, envelope.authorityEpoch());
        if (!decision.accepted()) {
            tracePacket((byte) -1, envelope, "dropped", "manager_authority_epoch_rejected", 0);
            ReLog.logger(LogTypes.FLOW).source(LogSource.server(serverId, serverId)).component(WorldGenProtocolHandler.class)
                .with("authorityEpoch", envelope.authorityEpoch()).warn("Rejected world generation packet from a stale authority epoch");
            return false;
        }
        return true;
    }

    private AuthorityTransition authorityTransition(Envelope envelope) {
        String action = canonicalWorldGenAction(textField(envelope, "action"));
        String projectId = textField(envelope, "projectId");
        String resourceId = textField(envelope, "resourceId");
        String requestId = textField(envelope, "requestId");
        String mutationId = textField(envelope, "mutationId");
        String operationId = textField(envelope, "operationId");
        String status = textField(envelope, "status");
        boolean transition = booleanField(envelope, "forceRefresh") || booleanField(envelope, "force_refresh")
            || isAuthorityTransitionStatus(status);
        String message = textField(envelope, "message");
        return new AuthorityTransition(transition, action, projectId, resourceId, requestId, mutationId, operationId,
            message);
    }

    private boolean isAuthorityTransitionStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return "authority_transition".equals(normalized) || "force_refresh".equals(normalized)
            || "committed_old_authority".equals(normalized);
    }

    private boolean booleanField(Envelope envelope, String field) {
        JsonElement element = field(envelope, field);
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String textField(Envelope envelope, String field) {
        JsonElement element = field(envelope, field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private JsonElement field(Envelope envelope, String field) {
        if (envelope.data() != null && envelope.data().isJsonObject()) {
            JsonObject data = envelope.data().getAsJsonObject();
            if (data.has(field)) {
                return data.get(field);
            }
        }
        if (envelope.root() != null && envelope.root().isJsonObject()) {
            JsonObject root = envelope.root().getAsJsonObject();
            if (root.has(field)) {
                return root.get(field);
            }
        }
        return null;
    }

    private String textField(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()
            || !object.get(field).isJsonPrimitive()) {
            return null;
        }
        String value = object.get(field).getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private String canonicalWorldGenAction(String action) {
        return switch (action == null ? "" : action) {
            case "saveWorldGenProject", WorldGenManager.WORLD_GEN_PROJECT_SAVE_ACTION -> WorldGenManager.WORLD_GEN_PROJECT_SAVE_ACTION;
            case "deleteWorldGenProject", WorldGenManager.WORLD_GEN_PROJECT_DELETE_ACTION -> WorldGenManager.WORLD_GEN_PROJECT_DELETE_ACTION;
            default -> action == null ? "" : action;
        };
    }

    private boolean hasCanonicalIdentity(String action, String projectId, String resourceId, String requestId,
                                         String mutationId, String operationId) {
        return (WorldGenManager.WORLD_GEN_PROJECT_SAVE_ACTION.equals(action)
            || WorldGenManager.WORLD_GEN_PROJECT_DELETE_ACTION.equals(action))
            && projectId != null && !projectId.isBlank()
            && resourceId != null && !resourceId.isBlank()
            && projectId.equals(resourceId)
            && requestId != null && !requestId.isBlank()
            && mutationId != null && !mutationId.isBlank()
            && operationId != null && !operationId.isBlank();
    }

    private Metadata metadata(JsonElement root) {
        Metadata metadata = new Metadata();
        if (root == null || !root.isJsonObject()) {
            return metadata;
        }
        JsonObject outer = root.getAsJsonObject();
        metadata.epochPresent = outer.has("authorityEpoch");
        Long epoch = readLong(outer.get("authorityEpoch"));
        metadata.epochValid = epoch != null && epoch >= 1L;
        metadata.authorityEpoch = epoch != null ? epoch : -1L;
        metadata.revisionPresent = outer.has("revision");
        Long revision = readLong(outer.get("revision"));
        metadata.revisionValid = revision != null && revision >= 1L;
        metadata.revision = revision != null ? revision : -1L;
        return metadata;
    }

    private Long readLong(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return Long.parseLong(element.getAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record Envelope(JsonElement root, long authorityEpoch, boolean epochPresent, boolean epochValid,
                            long revision, boolean revisionPresent, boolean revisionValid, long startedAtNanos) {
        private JsonElement data() {
            if (root != null && root.isJsonObject() && root.getAsJsonObject().has("data")) {
                return root.getAsJsonObject().get("data");
            }
            return JsonNull.INSTANCE;
        }
    }

    private record AuthorityTransition(boolean transition, String action, String projectId, String resourceId,
                                       String requestId, String mutationId, String operationId, String message) {
    }

    @FunctionalInterface
    interface WorldGenAuthorityTransition {
        boolean accept(long nextEpoch, WorldGenManager.WorldGenMutationIdentity identity, String message);
    }

    private static final class Metadata {
        private long authorityEpoch = -1L;
        private long revision = -1L;
        private boolean epochPresent;
        private boolean epochValid;
        private boolean revisionPresent;
        private boolean revisionValid;
    }
}
