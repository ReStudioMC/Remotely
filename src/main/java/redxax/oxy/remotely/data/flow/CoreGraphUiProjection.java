package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.data.FlowVariable;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphPassthrough;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.type.TypeReference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

public final class CoreGraphUiProjection {
    private static final double MAX_RENDER_COORDINATE = 1_073_741_824.0D;
    private static final EditCapabilities EDIT_CAPABILITIES = new EditCapabilities(true, true, true, false, false, false);
    private static final EditCapabilities READ_ONLY_CAPABILITIES = new EditCapabilities(false, false, false, false, false, false);
    private final Gson gson = new Gson();
    private final Map<Key, Authority> authorities = BrowserSafeState.map();
    private final Map<String, Long> authorityEpochs = BrowserSafeState.map();
    private final Map<Key, Long> generations = BrowserSafeState.map();
    private long generation;

    public record Snapshot(String serverId, ReSyncResourceType type, String resourceId, Baseline baseline,
                           Tombstone tombstone, boolean epochPresent, long authorityEpoch) {
        public Snapshot {
            serverId = serverId == null ? "" : serverId.trim();
            resourceId = resourceId == null ? "" : resourceId.trim();
            if (serverId.isBlank() || type == null || !type.isGraph() || resourceId.isBlank()) {
                throw new IllegalArgumentException("Core graph projection snapshot identity is invalid");
            }
            if (baseline != null && tombstone != null) {
                throw new IllegalArgumentException("Core graph projection snapshot cannot contain both states");
            }
            if (epochPresent && authorityEpoch < 1L) {
                throw new IllegalArgumentException("Core graph projection snapshot epoch must be positive");
            }
        }
    }

    public record StateSnapshot(Map<Key, EntrySnapshot> authorities, Map<String, Long> authorityEpochs) {
        public StateSnapshot {
            authorities = authorities == null ? Map.of() : Map.copyOf(authorities);
            authorityEpochs = authorityEpochs == null ? Map.of() : Map.copyOf(authorityEpochs);
        }
    }

    public record EntrySnapshot(Baseline baseline, Tombstone tombstone) {
        public EntrySnapshot {
            if (baseline != null && tombstone != null) {
                throw new IllegalArgumentException("Core graph UI state cannot contain both baseline and tombstone");
            }
            if (baseline == null && tombstone == null) {
                throw new IllegalArgumentException("Core graph UI state must contain a baseline or tombstone");
            }
        }
    }

    public synchronized StateSnapshot snapshotAll() {
        Map<Key, EntrySnapshot> entries = new LinkedHashMap<>();
        authorities.forEach((key, authority) -> entries.put(key,
            new EntrySnapshot(authority.baseline(), authority.tombstone())));
        return new StateSnapshot(entries, new LinkedHashMap<>(authorityEpochs));
    }

    public synchronized void restoreAll(StateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Core graph UI state snapshot is required");
        touchKeys();
        authorities.clear();
        snapshot.authorities().forEach((key, entry) -> authorities.put(key, entry.baseline() != null
            ? Authority.baseline(entry.baseline(), snapshot.authorityEpochs().getOrDefault(key.serverId(), 0L))
            : Authority.tombstone(entry.tombstone(), snapshot.authorityEpochs().getOrDefault(key.serverId(), 0L))));
        authorityEpochs.clear();
        authorityEpochs.putAll(snapshot.authorityEpochs());
        generations.clear();
    }

    public synchronized Snapshot snapshot(String serverId, ReSyncResourceType type, String id) {
        Key key = new Key(serverId, type, id);
        Authority authority = authorities.get(key);
        Long epoch = authorityEpochs.get(key.serverId());
        return new Snapshot(key.serverId(), key.type(), key.id(), authority != null ? authority.baseline() : null,
            authority != null ? authority.tombstone() : null, epoch != null, epoch != null ? epoch : 0L);
    }

    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "Core graph projection snapshot is required");
        Key key = new Key(snapshot.serverId(), snapshot.type(), snapshot.resourceId());
        if (snapshot.baseline() == null && snapshot.tombstone() == null) {
            authorities.remove(key);
        } else if (snapshot.baseline() != null) {
            authorities.put(key, Authority.baseline(snapshot.baseline(), snapshot.authorityEpoch()));
        } else {
            authorities.put(key, Authority.tombstone(snapshot.tombstone(), snapshot.authorityEpoch()));
        }
        if (snapshot.epochPresent()) {
            authorityEpochs.put(snapshot.serverId(), snapshot.authorityEpoch());
        } else {
            authorityEpochs.remove(snapshot.serverId());
        }
        touch(key);
    }

    public synchronized long currentGeneration(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return 0L;
        }
        return generations.getOrDefault(new Key(serverId, type, id), 0L);
    }

    public synchronized boolean restoreIfGeneration(StateSnapshot snapshot, String serverId,
                                                     ReSyncResourceType type, String id,
                                                     long expectedGeneration) {
        if (snapshot == null || serverId == null || serverId.isBlank() || type == null || !type.isGraph()
            || id == null || id.isBlank()) {
            return false;
        }
        Key key = new Key(serverId, type, id);
        if (currentGeneration(serverId, type, id) != expectedGeneration
            || authorityEpochs.getOrDefault(serverId, 0L) != snapshot.authorityEpochs().getOrDefault(serverId, 0L)) {
            return false;
        }
        EntrySnapshot entry = snapshot.authorities().get(key);
        if (entry == null) {
            authorities.remove(key);
        } else if (entry.baseline() != null) {
            authorities.put(key, Authority.baseline(entry.baseline(), snapshot.authorityEpochs().getOrDefault(serverId, 0L)));
        } else {
            authorities.put(key, Authority.tombstone(entry.tombstone(), snapshot.authorityEpochs().getOrDefault(serverId, 0L)));
        }
        touch(key);
        return true;
    }

    public Optional<FlowGraph> project(ReSyncResourceType type, CoreGraphResourceProjection.Projection projection) {
        return apply(type, projection);
    }

    public ProjectionResult projectEditorSession(CoreGraphEditorSession session) {
        return projectEditorSnapshot(EditorSnapshot.capture(session));
    }

    public Optional<InspectorProjection> projectInspector(CoreGraphEditorSession session, NodeInstanceId nodeId) {
        return projectInspector(EditorSnapshot.capture(session), nodeId);
    }

    public Optional<InspectorProjection> projectInspector(EditorSnapshot snapshot, NodeInstanceId nodeId) {
        Objects.requireNonNull(snapshot, "Core graph editor snapshot is required");
        Objects.requireNonNull(nodeId, "Node ID is required");
        return snapshot.document().nodes().stream()
            .filter(node -> node.instanceId().equals(nodeId))
            .findFirst()
            .map(node -> InspectorProjection.of(node.instanceId(), node.inspectorFields()));
    }

    public Optional<InspectorValue> projectInspectorField(CoreGraphEditorSession session, NodeInstanceId nodeId,
                                                            InspectorFieldId fieldId) {
        return projectInspectorField(EditorSnapshot.capture(session), nodeId, fieldId);
    }

    public Optional<InspectorValue> projectInspectorField(EditorSnapshot snapshot, NodeInstanceId nodeId,
                                                            InspectorFieldId fieldId) {
        Objects.requireNonNull(fieldId, "Inspector field ID is required");
        return projectInspector(snapshot, nodeId).flatMap(inspector -> inspector.field(fieldId));
    }

    public CoreGraphEditorSession setInspectorValue(CoreGraphEditorSession session, NodeInstanceId nodeId,
                                                     InspectorValue value) {
        return setInspectorValue(session, nodeId, value, false);
    }

    public CoreGraphEditorSession setExactInspectorValue(CoreGraphEditorSession session, NodeInstanceId nodeId,
                                                          InspectorValue value) {
        return setInspectorValue(session, nodeId, value, true);
    }

    private CoreGraphEditorSession setInspectorValue(CoreGraphEditorSession session, NodeInstanceId nodeId,
                                                      InspectorValue value, boolean exact) {
        Objects.requireNonNull(session, "Core graph editor session is required");
        Objects.requireNonNull(nodeId, "Node ID is required");
        Objects.requireNonNull(value, "Inspector value is required");
        synchronized (session) {
            projectInspectorField(session, nodeId, value.fieldId()).ifPresent(current -> {
                if (exact) {
                    value.requireCompatibleExactReplacement(current);
                } else {
                    value.requireCompatibleReplacement(current);
                }
            });
            return session.setNodeInspectorField(nodeId, value.fieldId(), value.typedValue());
        }
    }

    public CoreGraphEditorSession removeInspectorValue(CoreGraphEditorSession session, NodeInstanceId nodeId,
                                                        InspectorFieldId fieldId) {
        Objects.requireNonNull(session, "Core graph editor session is required");
        Objects.requireNonNull(nodeId, "Node ID is required");
        Objects.requireNonNull(fieldId, "Inspector field ID is required");
        return session.removeNodeInspectorField(nodeId, fieldId);
    }

    public static Optional<InspectorValue> absentInspectorValue(ReSyncGenericDescriptorProjection.InspectorField field) {
        if (field == null || field.path().isBlank() || field.type() == null) {
            return Optional.empty();
        }
        try {
            InspectorFieldId fieldId = InspectorFieldId.of(field.id());
            return Optional.of(new InspectorValue(fieldId, TypedValue.absent(descriptorType(field.type()))));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static TypeExpr descriptorType(Object value) {
        if (value instanceof String expression) {
            return descriptorType(FlowTypeRef.parse(expression));
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Inspector field type is unsupported");
        }
        Map<String, Object> source = descriptorMap(raw);
        String kind = descriptorText(source.get("kind")).toLowerCase(Locale.ROOT);
        Map<String, Object> unknown;
        return switch (kind) {
            case "named" -> {
                unknown = descriptorUnknown(source, Set.of("kind", "type", "arguments"));
                TypeReference reference = descriptorReference(source.get("type"));
                Object argumentsValue = source.get("arguments");
                List<TypeExpr> arguments = new ArrayList<>();
                if (argumentsValue instanceof Collection<?> values) {
                    values.forEach(argument -> arguments.add(descriptorType(argument)));
                } else if (argumentsValue != null) {
                    throw new IllegalArgumentException("Named inspector type arguments are invalid");
                }
                yield new TypeExpr.Named(reference, arguments, unknown);
            }
            case "optional" -> new TypeExpr.OptionalType(descriptorType(source.get("element")),
                descriptorUnknown(source, Set.of("kind", "element")));
            case "list", "set", "queue", "stack" -> new TypeExpr.ListType(descriptorType(source.get("element")),
                descriptorUnknown(source, Set.of("kind", "element")));
            case "map" -> new TypeExpr.MapType(descriptorType(source.get("key")), descriptorType(source.get("value")),
                descriptorUnknown(source, Set.of("kind", "key", "value")));
            case "tuple" -> {
                if (!(source.get("elements") instanceof Collection<?> values)) {
                    throw new IllegalArgumentException("Tuple inspector type elements are invalid");
                }
                List<TypeExpr> elements = new ArrayList<>();
                values.forEach(element -> elements.add(descriptorType(element)));
                yield new TypeExpr.TupleType(elements, descriptorUnknown(source, Set.of("kind", "elements")));
            }
            case "result" -> new TypeExpr.ResultType(descriptorType(source.get("success")),
                descriptorType(source.get("failure")), descriptorUnknown(source, Set.of("kind", "success", "failure")));
            case "resource" -> new TypeExpr.ResourceType(descriptorReference(source.get("resourceType")),
                descriptorUnknown(source, Set.of("kind", "resourceType")));
            case "union" -> {
                if (!(source.get("variants") instanceof Collection<?> values)) {
                    throw new IllegalArgumentException("Union inspector type variants are invalid");
                }
                List<TypeExpr.UnionVariant> variants = new ArrayList<>();
                for (Object variantValue : values) {
                    if (!(variantValue instanceof Map<?, ?> variantRaw)) {
                        throw new IllegalArgumentException("Union inspector type variant is invalid");
                    }
                    Map<String, Object> variant = descriptorMap(variantRaw);
                    variants.add(new TypeExpr.UnionVariant(descriptorText(variant.get("variantId")),
                        descriptorType(variant.get("type")), nullableDescriptorText(variant.get("displayName")),
                        nullableDescriptorText(variant.get("description")),
                        descriptorUnknown(variant, Set.of("variantId", "type", "displayName", "description"))));
                }
                yield new TypeExpr.UnionType(variants, descriptorUnknown(source, Set.of("kind", "variants")));
            }
            case "opaque" -> new TypeExpr.OpaqueType(descriptorReference(source.get("type")),
                descriptorUnknown(source, Set.of("kind", "raw", "type")));
            case "text", "string", "number", "integer", "float", "double", "boolean", "bool", "uuid",
                 "execution", "json", "json_object", "any" -> new TypeExpr.Named(TypeReference.of("builtin",
                switch (kind) {
                    case "text" -> "string";
                    case "bool" -> "boolean";
                    case "json" -> "json_object";
                    default -> kind;
                }), List.of(), descriptorUnknown(source, Set.of("kind")));
            default -> throw new IllegalArgumentException("Inspector field type is unsupported");
        };
    }

    public static TypeExpr descriptorType(FlowTypeRef type) {
        if (type == null || !type.isResolved()) {
            throw new IllegalArgumentException("Inspector field type is unresolved");
        }
        List<FlowTypeRef> arguments = type.getArguments();
        return switch (type.getTypeId().toLowerCase(Locale.ROOT)) {
            case "optional" -> TypeExpr.optional(descriptorType(arguments.getFirst()));
            case "list", "set", "queue", "stack" -> TypeExpr.list(descriptorType(arguments.getFirst()));
            case "map" -> TypeExpr.map(descriptorType(arguments.getFirst()), descriptorType(arguments.get(1)));
            case "result" -> TypeExpr.result(descriptorType(arguments.getFirst()), descriptorType(arguments.get(1)));
            case "tuple" -> TypeExpr.tuple(arguments.stream().map(CoreGraphUiProjection::descriptorType).toList());
            case "resource_reference" -> {
                FlowTypeRef reference = arguments.getFirst();
                yield TypeExpr.resource(descriptorReference(reference.getTypeId()));
            }
            default -> TypeExpr.named(descriptorReference(type.getTypeId()),
                arguments.stream().map(CoreGraphUiProjection::descriptorType).toList());
        };
    }

    private static TypeReference descriptorReference(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> reference = descriptorMap(raw);
            return new TypeReference(descriptorText(reference.get("ownerId")), descriptorText(reference.get("localId")),
                descriptorUnknown(reference, Set.of("ownerId", "localId")));
        }
        return descriptorReference(descriptorText(value));
    }

    private static TypeReference descriptorReference(String value) {
        int separator = value.indexOf(':');
        if (separator > 0 && separator < value.length() - 1) {
            return TypeReference.of(value.substring(0, separator), value.substring(separator + 1));
        }
        String local = switch (value.toLowerCase(Locale.ROOT)) {
            case "text" -> "string";
            case "bool" -> "boolean";
            case "json" -> "json_object";
            default -> value;
        };
        return TypeReference.of("builtin", local);
    }

    private static Map<String, Object> descriptorMap(Map<?, ?> raw) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Inspector field type keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> descriptorUnknown(Map<String, Object> source, Set<String> known) {
        LinkedHashMap<String, Object> unknown = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!known.contains(key)) {
                unknown.put(key, value);
            }
        });
        return unknown;
    }

    private static String descriptorText(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String nullableDescriptorText(Object value) {
        String text = descriptorText(value);
        return text.isBlank() ? null : text;
    }

    public ProjectionResult projectEditorSnapshot(EditorSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Core graph editor snapshot is required");
        GraphDocument document = snapshot.document();
        ReSyncResourceType type = document == null ? null
            : ReSyncResourceType.byTypeId(document.resource().resourceType().value());
        String checksum = snapshot.checksum().canonicalText();
        if (document == null || type == null || (!type.isGraph() && type != ReSyncResourceType.CUSTOM_CONTENT)) {
            return ProjectionResult.rejected(checksum, "Core graph resource type is unsupported");
        }
        String invalidNodeId = invalidRenderCoordinateNode(document);
        if (invalidNodeId != null) {
            return ProjectionResult.rejected(checksum, "Core graph node position is outside render bounds: " + invalidNodeId);
        }
        FlowGraph graph = projectGraph(type, document, document.revision(), null, snapshot.checksum(),
            ResourceActivationState.ACTIVE);
        if (snapshot.functionSourceDocument() != null) {
            projectFunctionSignature(graph, json(FunctionSourceDocumentCodec.INSTANCE.encode(snapshot.functionSourceDocument())));
        }
        List<String> sourceIdentities = document.nodes().stream()
            .map(node -> node.instanceId().canonicalText()).toList();
        LinkedHashSet<String> projectedIdentities = new LinkedHashSet<>(graph.getNodes().keySet());
        List<String> droppedIdentities = droppedIdentities(sourceIdentities, projectedIdentities);
        Map<String, List<FlowConnection>> projectedConnections = new LinkedHashMap<>();
        graph.getConnections().forEach(connection -> projectedConnections.computeIfAbsent(
            text(connection.getOpaqueProperties(), "connectionId"), ignored -> new ArrayList<>()).add(connection));
        List<String> droppedConnectionIdentities = new ArrayList<>();
        for (var connection : document.connections()) {
            String identity = connection.connectionId().canonicalText();
            List<FlowConnection> candidates = projectedConnections.getOrDefault(identity, List.of());
            FlowConnection match = candidates.stream().filter(candidate -> connectionMatches(connection, candidate,
                projectedIdentities)).findFirst().orElse(null);
            if (match == null) {
                droppedConnectionIdentities.add(identity);
            } else {
                candidates.remove(match);
            }
        }
        List<Map<String, Object>> topologyConnections = document.connections().stream().map(connection -> Map.<String, Object>of(
            "connectionId", connection.connectionId().canonicalText(),
            "sourceNodeId", connection.source().nodeId().canonicalText(),
            "sourcePinId", connection.source().pinId().canonicalText(),
            "targetNodeId", connection.target().nodeId().canonicalText(),
            "targetPinId", connection.target().pinId().canonicalText())).toList();
        List<Map<String, Object>> topologyPassthroughs = document.passthroughs().stream().map(passthrough -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("nodeId", passthrough.nodeId().canonicalText());
            value.put("inputPin", passthrough.inputPin().canonicalText());
            value.put("connectionIds", passthrough.connectionIds().stream()
                .map(connectionId -> connectionId.canonicalText()).toList());
            return value;
        }).toList();
        Map<String, Object> topology = Map.of("nodes", sourceIdentities, "connections", topologyConnections,
            "passthroughs", topologyPassthroughs);
        String topologyChecksum = CanonicalJson.sha256Canonical("remotely.core.ui.topology.v1",
            JsonValue.fromJava(topology).canonicalBytes());
        return new ProjectionResult(graph, sourceIdentities.size(), projectedIdentities.size(), droppedIdentities,
            document.connections().size(), graph.getConnections().size(), droppedConnectionIdentities,
            checksum, topologyChecksum, "");
    }

    private static List<String> droppedIdentities(List<String> source, Collection<String> projected) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        projected.forEach(identity -> remaining.merge(identity, 1, Integer::sum));
        List<String> dropped = new ArrayList<>();
        for (String identity : source) {
            int count = remaining.getOrDefault(identity, 0);
            if (count < 1) {
                dropped.add(identity);
            } else {
                remaining.put(identity, count - 1);
            }
        }
        return dropped;
    }

    private static boolean connectionMatches(GraphConnection source, FlowConnection projected,
                                             Set<String> projectedNodeIds) {
        return projected != null && projectedNodeIds.contains(source.source().nodeId().canonicalText())
            && projectedNodeIds.contains(source.target().nodeId().canonicalText())
            && Objects.equals(projected.getSourceNodeId(), source.source().nodeId().canonicalText())
            && Objects.equals(projected.getSourcePinId(), source.source().pinId().canonicalText())
            && Objects.equals(projected.getTargetNodeId(), source.target().nodeId().canonicalText())
            && Objects.equals(projected.getTargetPinId(), source.target().pinId().canonicalText());
    }

    public Optional<FlowGraph> project(ReSyncFlowClient.CoreGraphResourceTransition transition) {
        return apply(transition);
    }

    public Optional<FlowGraph> apply(ReSyncResourceType type, CoreGraphResourceProjection.Projection projection) {
        Objects.requireNonNull(type, "Core graph type is required");
        Objects.requireNonNull(projection, "Core graph projection is required");
        if (projection.resource() == null) {
            return Optional.empty();
        }
        if (!matchesType(type, projection.resource())) {
            return Optional.empty();
        }
        if (projection.revision() < 1L) {
            return Optional.empty();
        }
        String serverId = projection.resource().serverId().canonicalText();
        long authorityEpoch = projection.envelope().authorityEpoch();
        if (!observeAuthorityEpoch(serverId, authorityEpoch).accepted()) {
            return Optional.empty();
        }
        return applyProjection(Key.from(type, projection.resource()), type, projection, authorityEpoch);
    }

    public Optional<FlowGraph> apply(ReSyncFlowClient.CoreGraphResourceTransition transition) {
        Objects.requireNonNull(transition, "Core graph transition is required");
        CoreGraphResourceProjection.Projection projection = transition.projection();
        if (!matchesType(transition.type(), projection.resource())) {
            return Optional.empty();
        }
        if (projection.revision() < 1L) {
            return Optional.empty();
        }
        Key key = Key.from(transition.type(), projection.resource());
        long authorityEpoch = projection.envelope().authorityEpoch();
        if (!observeAuthorityEpoch(projection.resource().serverId().canonicalText(), authorityEpoch).accepted()) {
            return Optional.empty();
        }
        return applyProjection(key, transition.type(), projection, authorityEpoch);
    }

    private Optional<FlowGraph> applyProjection(Key key, ReSyncResourceType type,
                                                 CoreGraphResourceProjection.Projection projection,
                                                 long authorityEpoch) {
        if (projection.tombstoned()) {
            return applyAuthority(key, Authority.tombstone(new Tombstone(key, projection.revision(), projection.mutationId(),
                projection.payloadHash()), authorityEpoch));
        }
        if (!projection.accepted() || (!projection.hasGraphDocument() && !projection.hasFunctionSourceDocument())) {
            return Optional.empty();
        }
        Baseline baseline = createBaseline(key, type, projection);
        return applyAuthority(key, Authority.baseline(baseline, authorityEpoch));
    }

    public Optional<FlowGraph> apply(ReSyncResourceType type, GraphResourceState state,
                                     boolean readOnly) {
        Objects.requireNonNull(state, "Core graph state is required");
        return apply(type, state, readOnly, authorityEpoch(state.resource().serverId().canonicalText()));
    }

    public Optional<FlowGraph> apply(ReSyncResourceType type, GraphResourceState state,
                                     boolean readOnly, long authorityEpoch) {
        Objects.requireNonNull(type, "Core graph type is required");
        Objects.requireNonNull(state, "Core graph state is required");
        if (!matchesType(type, state.resource())) {
            return Optional.empty();
        }
        if (state.revision() < 1L) {
            return Optional.empty();
        }
        String serverId = state.resource().serverId().canonicalText();
        if (!observeAuthorityEpoch(serverId, authorityEpoch).accepted()) {
            return Optional.empty();
        }
        Key key = Key.from(type, state.resource());
        if (state.tombstone()) {
            return applyAuthority(key, Authority.tombstone(new Tombstone(key, state.revision(), state.mutationId(),
                state.protocolHash()), authorityEpoch));
        }
        if (!state.live() || (state.graphDocument() == null && state.functionSourceDocument() == null)) {
            return Optional.empty();
        }
        Baseline baseline = createBaseline(key, type, state.graphDocument(), state.functionSourceDocument(),
            readOnly ? Status.READ_ONLY : Status.LIVE, state.revision(), state.mutationId(), state.protocolHash(),
            state.assetHash(), state.activationState(), "");
        return applyAuthority(key, Authority.baseline(baseline, authorityEpoch));
    }

    private synchronized Optional<FlowGraph> applyAuthority(Key key, Authority candidate) {
        long observedEpoch = authorityEpochs.getOrDefault(key.serverId(), 0L);
        if (candidate.authorityEpoch() < observedEpoch) {
            Authority current = authorities.get(key);
            return current == null || current.baseline() == null
                ? Optional.empty() : Optional.of(current.baseline().graph());
        }
        BrowserSafeState.ReferenceValue<Optional<FlowGraph>> result = new BrowserSafeState.ReferenceValue<>(Optional.empty());
        BrowserSafeState.ReferenceValue<Boolean> changed = new BrowserSafeState.ReferenceValue<>(false);
        authorities.compute(key, (ignored, current) -> {
            if (!accepts(current, candidate)) {
                result.set(current == null || current.baseline() == null
                    ? Optional.empty() : Optional.of(current.baseline().graph()));
                return current;
            }
            result.set(candidate.baseline() == null ? Optional.empty() : Optional.of(candidate.baseline().graph()));
            changed.set(true);
            return candidate;
        });
        if (changed.get()) {
            touch(key);
        }
        return result.get();
    }

    private static boolean accepts(Authority current, Authority candidate) {
        if (current == null) {
            return true;
        }
        if (candidate.authorityEpoch() < current.authorityEpoch()) {
            return false;
        }
        if (candidate.authorityEpoch() > current.authorityEpoch()) {
            return true;
        }
        Tombstone currentTombstone = current.tombstone();
        if (currentTombstone != null) {
            return candidate.revision() > currentTombstone.revision()
                || candidate.revision() == currentTombstone.revision() && candidate.tombstone() != null;
        }
        Baseline currentBaseline = current.baseline();
        if (candidate.revision() > currentBaseline.revision()) {
            return true;
        }
        if (candidate.revision() < currentBaseline.revision()) {
            return false;
        }
        return candidate.tombstone() != null || currentBaseline.readOnly() && !candidate.baseline().readOnly();
    }

    public Optional<FlowGraph> graph(String serverId, ReSyncResourceType type, String id) {
        return baseline(serverId, type, id).map(Baseline::graph);
    }

    public Optional<Baseline> baseline(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return Optional.empty();
        }
        Authority authority = authorities.get(new Key(serverId, type, id));
        return authority == null ? Optional.empty() : Optional.ofNullable(authority.baseline());
    }

    public boolean contains(String serverId, ReSyncResourceType type, String id) {
        return baseline(serverId, type, id).isPresent();
    }

    public boolean canSave(String serverId, ReSyncResourceType type, String id) {
        return baseline(serverId, type, id).map(Baseline::canSave).orElse(false);
    }

    public boolean authoritative(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return false;
        }
        Key key = new Key(serverId, type, id);
        return authorities.containsKey(key);
    }

    public boolean tombstoned(String serverId, ReSyncResourceType type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || !type.isGraph() || id == null || id.isBlank()) {
            return false;
        }
        Authority authority = authorities.get(new Key(serverId, type, id));
        return authority != null && authority.tombstone() != null;
    }

    public Map<Key, Baseline> baselines() {
        Map<Key, Baseline> result = new LinkedHashMap<>();
        authorities.forEach((key, authority) -> {
            if (authority.baseline() != null) {
                result.put(key, authority.baseline());
            }
        });
        return Collections.unmodifiableMap(result);
    }

    public Optional<EditCapabilities> editCapabilities(String serverId, ReSyncResourceType type, String id) {
        return baseline(serverId, type, id).map(Baseline::editCapabilities);
    }

    public synchronized void remove(String serverId, ReSyncResourceType type, String id) {
        Key key = new Key(serverId, type, id);
        authorities.computeIfPresent(key, (ignored, current) -> current.baseline() == null ? current : null);
        touch(key);
    }

    public synchronized void clearServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        touchKeys(serverId);
        authorities.keySet().removeIf(key -> serverId.equals(key.serverId()));
        generations.keySet().removeIf(key -> serverId.equals(key.serverId()));
        authorityEpochs.remove(serverId);
    }

    private void touch(Key key) {
        long next = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        generation = next;
        generations.put(key, next);
    }

    private void touchKeys() {
        Set<Key> keys = new LinkedHashSet<>(authorities.keySet());
        keys.forEach(this::touch);
    }

    private void touchKeys(String serverId) {
        authorities.keySet().stream().filter(key -> serverId.equals(key.serverId())).forEach(this::touch);
    }

    public synchronized EpochDecision observeAuthorityEpoch(String serverId, long authorityEpoch) {
        String normalized = serverId == null ? "" : serverId.trim();
        if (normalized.isBlank() || authorityEpoch < 1L) {
            return new EpochDecision(EpochOutcome.INVALID, 0L, authorityEpoch);
        }
        long current = authorityEpochs.getOrDefault(normalized, 0L);
        if (authorityEpoch < current) {
            return new EpochDecision(EpochOutcome.STALE, current, authorityEpoch);
        }
        if (authorityEpoch == current) {
            return new EpochDecision(EpochOutcome.CURRENT, current, authorityEpoch);
        }
        authorities.keySet().removeIf(key -> normalized.equals(key.serverId()));
        authorityEpochs.put(normalized, authorityEpoch);
        return new EpochDecision(EpochOutcome.ADVANCED, current, authorityEpoch);
    }

    public long authorityEpoch(String serverId) {
        String normalized = serverId == null ? "" : serverId.trim();
        return authorityEpochs.getOrDefault(normalized, 0L);
    }

    public ReverseResult reverse(Baseline baseline, FlowGraph graph) {
        Objects.requireNonNull(baseline, "Core graph baseline is required");
        if (graph == null) {
            return ReverseResult.rejected("The Core graph editor returned no graph.");
        }
        if (baseline.readOnly()) {
            return ReverseResult.rejected("The Core graph is read-only because the client does not support its capabilities.");
        }
        if (baseline.tombstoned()) {
            return ReverseResult.rejected("The Core graph was deleted on ReSync.");
        }
        Authority current = authorities.get(baseline.key());
        if (current == null || current.baseline() != baseline) {
            return ReverseResult.rejected("The editor baseline is no longer authoritative on ReSync.");
        }
        if (!baseline.provenance().matches(graph)) {
            return ReverseResult.rejected("The editor graph does not match the active Core revision, hash, and mutation.");
        }
        try {
            JsonObject payload = baseline.corePayload().deepCopy();
            JsonObject graphObject = baseline.functionSourceDocument() != null
                ? payload.getAsJsonObject("graph") : payload;
            if (graphObject == null) {
                return ReverseResult.rejected("The Core graph payload has no graph document.");
            }
            if (!sameText(graph.getId(), baseline.key().id()) || !sameText(graph.getResourceType(), baseline.key().type().typeId())) {
                return ReverseResult.rejected("The editor changed the Core resource identity.");
            }
            if (!graph.isFunction() && baseline.functionSourceDocument() != null) {
                return ReverseResult.rejected("The editor changed the Core Function resource type.");
            }
            if (!graph.getLocalVariables().isEmpty() || !graph.getContentProperties().isEmpty()) {
                return ReverseResult.rejected("The editor changed data that the Core bridge cannot represent losslessly.");
            }
            if (!sameNodeTopology(graphObject, graph)) {
                return ReverseResult.rejected("Adding or removing Core nodes is not supported by this editor capability.");
            }
            if (!sameConnectionTopology(graphObject, graph)) {
                return ReverseResult.rejected("Adding or removing Core connections is not supported by this editor capability.");
            }
            if (!patchNodes(graphObject, graph)) {
                return ReverseResult.rejected("The editor changed an unsupported Core node identity or value shape.");
            }
            if (!patchConnections(graphObject, graph)) {
                return ReverseResult.rejected("The editor changed an unsupported Core connection identity or endpoint shape.");
            }
            if (!patchPassthroughs(graphObject, graph)) {
                return ReverseResult.rejected("The editor changed an unsupported Core passthrough identity or relation.");
            }
            if (baseline.functionSourceDocument() != null && !sameFunctionSignature(baseline.corePayload(), graph)) {
                return ReverseResult.rejected("Editing Core Function signatures is not supported by this editor capability.");
            }
            JsonValue value = JsonValue.parse(JsonValue.parse(payload.toString()).canonicalText());
            if (baseline.functionSourceDocument() != null) {
                FunctionSourceDocument source = FunctionSourceDocumentCodec.INSTANCE.decode(value);
                return ReverseResult.accepted(source);
            }
            GraphDocument document = GraphDocumentCodec.INSTANCE.decode(value);
            return ReverseResult.accepted(document);
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "The Core graph conversion was not lossless." : exception.getMessage();
            return ReverseResult.rejected(reason);
        }
    }

    public ReverseResult reverse(String serverId, ReSyncResourceType type, FlowGraph graph) {
        return baseline(serverId, type, graph != null ? graph.getId() : null)
            .map(baseline -> reverse(baseline, graph))
            .orElseGet(() -> ReverseResult.rejected("The Core graph has not been projected for this editor."));
    }

    public ReverseResult toCore(Baseline baseline, FlowGraph graph) {
        return reverse(baseline, graph);
    }

    public ReverseResult toCore(String serverId, ReSyncResourceType type, FlowGraph graph) {
        return reverse(serverId, type, graph);
    }

    private Baseline createBaseline(Key key, ReSyncResourceType type,
                                    CoreGraphResourceProjection.Projection projection) {
        return createBaseline(key, type, projection.graphDocument(), projection.functionSourceDocument(),
            projection.readOnly() ? Status.READ_ONLY : Status.LIVE, projection.revision(), projection.mutationId(),
            projection.payloadHash(), projection.assetHash(), projection.activationState(), projection.rejectionReason());
    }

    private Baseline createBaseline(Key key, ReSyncResourceType type, GraphDocument graphDocument,
                                    FunctionSourceDocument functionSourceDocument, Status status, long revision,
                                    UUID mutationId, ContentHash protocolHash, ContentHash assetHash,
                                    ResourceActivationState activationState, String reason) {
        if (revision < 1L) {
            throw new IllegalArgumentException("Core baseline revision must be positive");
        }
        JsonObject corePayload;
        FlowGraph graph;
        if (functionSourceDocument != null) {
            corePayload = json(FunctionSourceDocumentCodec.INSTANCE.encode(functionSourceDocument));
            graph = projectGraph(type, functionSourceDocument.graph(), revision, mutationId, protocolHash, activationState);
            projectFunctionSignature(graph, corePayload);
        } else {
            corePayload = json(GraphDocumentCodec.INSTANCE.encode(Objects.requireNonNull(graphDocument, "Core graph document is required")));
            graph = projectGraph(type, graphDocument, revision, mutationId, protocolHash, activationState);
        }
        return new Baseline(key, graph, corePayload, graphDocument, functionSourceDocument, status, revision,
            mutationId, protocolHash, assetHash, activationState, reason == null ? "" : reason);
    }

    private FlowGraph projectGraph(ReSyncResourceType type, GraphDocument document, long revision, UUID mutationId,
                                   ContentHash protocolHash, ResourceActivationState activationState) {
        JsonObject object = json(GraphDocumentCodec.INSTANCE.encode(document));
        FlowGraph graph = new FlowGraph(document.resource().id(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
        graph.setResourceType(type.typeId());
        graph.setFunction(type == ReSyncResourceType.FUNCTION);
        graph.setResourceRevision(revision);
        graph.setResourceHash(protocolHash == null ? "" : protocolHash.canonicalText());
        graph.setResourceMutationId(mutationId == null ? "" : mutationId.toString());
        graph.setEnabled(activationState == null || activationState == ResourceActivationState.ACTIVE);
        if (type == ReSyncResourceType.CUSTOM_CONTENT) {
            document.unknown().fields().forEach((key, value) -> {
                if (!CustomContentCoreEditor.CONTENT_PROPERTIES.equals(key) && !CustomContentCoreEditor.CORE_GRAPH.equals(key)) {
                    graph.getOpaqueProperties().put(key, projectionJson(value));
                }
            });
            Object properties = document.unknown().fields().get(CustomContentCoreEditor.CONTENT_PROPERTIES);
            if (properties instanceof Map<?, ?> values) {
                Map<String, Object> projected = new LinkedHashMap<>();
                values.forEach((key, value) -> projected.put(String.valueOf(key), projectedObject(projectionJson(value))));
                graph.setContentProperties(projected);
            }
        }
        JsonArray nodes = object.getAsJsonArray("nodes");
        if (nodes != null) {
            for (JsonElement value : nodes) {
                if (!value.isJsonObject()) {
                    continue;
                }
                JsonObject nodeObject = value.getAsJsonObject();
                String nodeId = text(nodeObject, "instanceId");
                if (nodeId == null || nodeId.isBlank()) {
                    continue;
                }
                JsonObject definition = object(nodeObject, "definition");
                String typeId = descriptorIdentity(definition);
                JsonObject position = object(nodeObject, "position");
                double x = number(position, "x");
                double y = number(position, "y");
                if (!boundedRenderCoordinate(x) || !boundedRenderCoordinate(y)) {
                    throw new IllegalArgumentException("Core graph node position is outside render bounds: " + nodeId);
                }
                Map<String, Object> values = new LinkedHashMap<>();
                JsonObject pins = object(nodeObject, "values");
                if (pins != null) {
                    for (Map.Entry<String, JsonElement> entry : pins.entrySet()) {
                        JsonObject pin = entry.getValue() != null && entry.getValue().isJsonObject()
                            ? entry.getValue().getAsJsonObject() : null;
                        JsonObject typedValue = object(pin, "value");
                        JsonElement raw = projectedValue(typedValue);
                        values.put(entry.getKey(), projectedObject(raw));
                    }
                }
                FlowNode flowNode = new FlowNode(typeId, x, y, values);
                flowNode.setVersion(intValue(nodeObject, "definitionVersion", 1));
                flowNode.getOpaqueProperties().put("instanceId", new JsonPrimitive(nodeId));
                copyOpaqueNodeFields(nodeObject, flowNode);
                restoreContentInputs(flowNode);
                graph.getNodes().put(nodeId, flowNode);
            }
        }
        JsonArray connections = object.getAsJsonArray("connections");
        Map<String, FlowConnection> projectedConnections = new LinkedHashMap<>();
        if (connections != null) {
            for (JsonElement value : connections) {
                if (!value.isJsonObject()) {
                    continue;
                }
                JsonObject connectionObject = value.getAsJsonObject();
                String connectionId = text(connectionObject, "connectionId");
                JsonObject source = object(connectionObject, "source");
                JsonObject target = object(connectionObject, "target");
                if (connectionId == null || source == null || target == null) {
                    continue;
                }
                FlowConnection connection = FlowConnection.stable(text(source, "nodeId"), text(source, "pinId"),
                    text(target, "nodeId"), text(target, "pinId"));
                connection.getOpaqueProperties().put("connectionId", new JsonPrimitive(connectionId));
                copyOpaqueConnectionFields(connectionObject, source, target, connection);
                graph.getConnections().add(connection);
                projectedConnections.put(connectionId, connection);
            }
        }
        List<FlowGraph.EditorPassthrough> passthroughs = new ArrayList<>();
        for (GraphPassthrough passthrough : document.passthroughs()) {
            FlowGraph.EditorPassthrough projected = FlowGraph.EditorPassthrough.stable(
                passthrough.nodeId().canonicalText(), passthrough.inputPin().canonicalText());
            passthroughs.add(projected);
            String editorPin = "__passthrough:" + passthrough.inputPin().canonicalText();
            for (var connectionId : passthrough.connectionIds()) {
                FlowConnection connection = projectedConnections.get(connectionId.canonicalText());
                if (connection != null) {
                    connection.setEditorSourceNodeId(passthrough.nodeId().canonicalText());
                    connection.setEditorSourcePin(editorPin);
                }
            }
        }
        graph.setEditorPassthroughs(passthroughs);
        return graph;
    }

    public FlowNode projectEditorNode(GraphNode node) {
        Objects.requireNonNull(node, "Core node is required");
        if (!boundedRenderCoordinate(node.x()) || !boundedRenderCoordinate(node.y())) {
            throw new IllegalArgumentException("Core graph node position is outside render bounds: " + node.instanceId());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        node.values().forEach((pin, value) -> {
            TypedValue typed = value.value();
            Object raw = typed.state() == TypedValue.State.LOCATOR ? typed.locator().canonicalValue()
                : typed.hasValue() ? typed.value() : null;
            values.put(pin.canonicalText(), projectedObject(projectionJson(raw)));
        });
        FlowNode projected = new FlowNode(node.definition().owner().canonicalText() + ":" + node.definition().id().canonicalText(),
            node.x(), node.y(), values);
        projected.setVersion(node.definitionVersion());
        projected.getOpaqueProperties().put("instanceId", new JsonPrimitive(node.instanceId().canonicalText()));
        node.unknown().fields().forEach((key, value) -> projected.getOpaqueProperties().put(key, projectionJson(value)));
        restoreContentInputs(projected);
        return projected;
    }

    private static void restoreContentInputs(FlowNode node) {
        JsonElement extra = node.getOpaqueProperties().get(CustomContentCoreEditor.CONTENT_INPUTS);
        if (extra != null && extra.isJsonObject()) {
            extra.getAsJsonObject().entrySet().forEach(entry -> node.getInputValues().put(entry.getKey(), projectedObject(entry.getValue())));
        }
    }

    public FlowConnection projectEditorConnection(GraphConnection connection) {
        Objects.requireNonNull(connection, "Core connection is required");
        GraphEndpoint source = connection.source();
        GraphEndpoint target = connection.target();
        FlowConnection projected = FlowConnection.stable(source.nodeId().canonicalText(), source.pinId().canonicalText(),
            target.nodeId().canonicalText(), target.pinId().canonicalText());
        projected.getOpaqueProperties().put("connectionId", new JsonPrimitive(connection.connectionId().canonicalText()));
        connection.unknown().fields().forEach((key, value) -> projected.getOpaqueProperties().put(key, projectionJson(value)));
        projectEndpoint(source, "source", projected);
        projectEndpoint(target, "target", projected);
        return projected;
    }

    private static void projectEndpoint(GraphEndpoint endpoint, String prefix, FlowConnection connection) {
        if (endpoint.elementId() != null) {
            connection.getOpaqueProperties().put(prefix + "ElementId", new JsonPrimitive(endpoint.elementId().canonicalText()));
        }
        if (endpoint.branchId() != null) {
            connection.getOpaqueProperties().put(prefix + "BranchId", new JsonPrimitive(endpoint.branchId().canonicalText()));
        }
    }

    private static JsonElement projectionJson(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, item) -> object.add(String.valueOf(key), projectionJson(item)));
            return object;
        }
        if (value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            collection.forEach(item -> array.add(projectionJson(item)));
            return array;
        }
        throw new IllegalArgumentException("Unsupported Core projection value: " + TaskIdentities.typeName(value));
    }

    private static String invalidRenderCoordinateNode(GraphDocument document) {
        for (var node : document.nodes()) {
            if (!boundedRenderCoordinate(node.x()) || !boundedRenderCoordinate(node.y())) {
                return node.instanceId().canonicalText();
            }
        }
        return null;
    }

    private static boolean boundedRenderCoordinate(double coordinate) {
        return Double.isFinite(coordinate) && Math.abs(coordinate) <= MAX_RENDER_COORDINATE;
    }

    private void projectFunctionSignature(FlowGraph graph, JsonObject source) {
        JsonObject signature = object(source, "signature");
        if (signature == null) {
            return;
        }
        projectParameters(graph.getFunctionInputs(), signature.getAsJsonArray("inputs"));
        projectParameters(graph.getFunctionOutputs(), signature.getAsJsonArray("outputs"));
    }

    private void projectParameters(List<FlowGraph.FunctionParameter> target, JsonArray source) {
        if (source == null) {
            return;
        }
        for (JsonElement value : source) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject parameter = value.getAsJsonObject();
            String id = text(parameter, "id");
            String type = typeId(object(parameter, "type"));
            String name = text(parameter, "name");
            if (name == null || name.isBlank()) {
                name = text(parameter, "displayName");
            }
            if (name == null || name.isBlank()) {
                name = id;
            }
            String widget = text(parameter, "widget");
            String optionsSource = text(parameter, "optionsSource");
            JsonObject defaultValue = object(parameter, "defaultValue");
            JsonElement projectedDefault = projectedValue(defaultValue);
            FlowGraph.FunctionParameter projected = new FlowGraph.FunctionParameter(id, name,
                FlowDataType.fromString(type), widget != null ? widget : "",
                optionsSource != null ? optionsSource : "",
                projectedDefault != null && !projectedDefault.isJsonNull()
                    ? projectedDefault.isJsonPrimitive() && projectedDefault.getAsJsonPrimitive().isString()
                        ? projectedDefault.getAsString() : projectedDefault.toString() : "");
            target.add(projected);
        }
    }

    private boolean sameFunctionSignature(JsonObject source, FlowGraph graph) {
        JsonObject signature = object(source, "signature");
        return sameParameters(signature == null ? null : signature.getAsJsonArray("inputs"), graph.getFunctionInputs())
            && sameParameters(signature == null ? null : signature.getAsJsonArray("outputs"), graph.getFunctionOutputs());
    }

    private boolean sameParameters(JsonArray source, List<FlowGraph.FunctionParameter> current) {
        if (source == null || current == null || source.size() != current.size()) {
            return source == null && (current == null || current.isEmpty());
        }
        for (int index = 0; index < source.size(); index++) {
            String expected = text(source.get(index).getAsJsonObject(), "id");
            String expectedName = text(source.get(index).getAsJsonObject(), "name");
            if (expectedName == null || expectedName.isBlank()) {
                expectedName = text(source.get(index).getAsJsonObject(), "displayName");
            }
            if (expectedName == null || expectedName.isBlank()) {
                expectedName = expected;
            }
            FlowGraph.FunctionParameter actual = current.get(index);
            String expectedType = typeId(object(source.get(index).getAsJsonObject(), "type"));
            if (actual == null || !sameText(expected, actual.getParameterId())
                || !sameText(expectedName, actual.getDisplayName())
                || !sameText(expectedType, actual.getTypeRef().getTypeId())) {
                return false;
            }
        }
        return true;
    }

    private boolean patchNodes(JsonObject graphObject, FlowGraph graph) {
        JsonArray nodes = graphObject.getAsJsonArray("nodes");
        if (nodes == null || graph.getNodes() == null || nodes.size() != graph.getNodes().size()) {
            return false;
        }
        Map<String, JsonObject> sourceById = new LinkedHashMap<>();
        for (JsonElement value : nodes) {
            if (!value.isJsonObject()) {
                return false;
            }
            JsonObject node = value.getAsJsonObject();
            String id = text(node, "instanceId");
            if (id == null || sourceById.put(id, node) != null) {
                return false;
            }
        }
        if (!sourceById.keySet().equals(graph.getNodes().keySet())) {
            return false;
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            FlowNode current = entry.getValue();
            JsonObject baseline = sourceById.get(entry.getKey());
            JsonObject definition = object(baseline, "definition");
            String expectedType = descriptorIdentity(definition);
            if (current == null || !sameText(expectedType, current.getType())
                || intValue(baseline, "definitionVersion", 1) != current.getVersion()) {
                return false;
            }
            if (!sameNodeOpaque(baseline, current)) {
                return false;
            }
            JsonObject position = object(baseline, "position");
            if (position == null) {
                return false;
            }
            if (Double.compare(current.getX(), number(position, "x")) != 0
                || Double.compare(current.getY(), number(position, "y")) != 0) {
                position.addProperty("x", current.getX());
                position.addProperty("y", current.getY());
            }
            if (!patchValues(baseline, current)) {
                return false;
            }
        }
        return true;
    }

    private boolean patchValues(JsonObject baseline, FlowNode current) {
        JsonObject original = object(baseline, "values");
        Map<String, Object> values = current.getInputValues();
        if (original == null || values == null || original.size() != values.size() || !original.keySet().equals(values.keySet())) {
            return original != null && original.isEmpty() && (values == null || values.isEmpty());
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            JsonElement pinElement = original.get(entry.getKey());
            if (pinElement == null || !pinElement.isJsonObject()) {
                return false;
            }
            JsonElement currentValue = gson.toJsonTree(entry.getValue());
            JsonObject typedValue = object(pinElement.getAsJsonObject(), "value");
            JsonElement baselineValue = projectedValue(typedValue);
            if (baselineValue.equals(currentValue)) {
                continue;
            }
            String state = text(typedValue, "state");
            if ("value".equals(state) || "opaque".equals(state)) {
                typedValue.add("value", currentValue);
            } else if ("locator".equals(state) && currentValue.isJsonObject()) {
                typedValue.add("locator", currentValue);
            } else {
                return false;
            }
        }
        return true;
    }

    private boolean patchConnections(JsonObject graphObject, FlowGraph graph) {
        JsonArray connections = graphObject.getAsJsonArray("connections");
        if (connections == null || graph.getConnections() == null || connections.size() != graph.getConnections().size()) {
            return false;
        }
        Map<String, JsonObject> sourceById = new LinkedHashMap<>();
        for (JsonElement value : connections) {
            if (!value.isJsonObject()) {
                return false;
            }
            JsonObject connection = value.getAsJsonObject();
            String connectionId = text(connection, "connectionId");
            if (connectionId == null || sourceById.put(connectionId, connection) != null) {
                return false;
            }
        }
        Set<String> currentIds = new LinkedHashSet<>();
        for (FlowConnection current : graph.getConnections()) {
            if (current == null) {
                return false;
            }
            String currentConnectionId = text(current.getOpaqueProperties(), "connectionId");
            JsonObject baseline = sourceById.get(currentConnectionId);
            if (baseline == null || !currentIds.add(currentConnectionId)) {
                return false;
            }
            if (!sameConnectionOpaque(baseline, current)) {
                return false;
            }
            JsonObject source = object(baseline, "source");
            JsonObject target = object(baseline, "target");
            if (source == null || target == null) {
                return false;
            }
            source.addProperty("nodeId", current.getSourceNodeId());
            source.addProperty("pinId", current.getSourcePinId());
            target.addProperty("nodeId", current.getTargetNodeId());
            target.addProperty("pinId", current.getTargetPinId());
        }
        return sourceById.keySet().equals(currentIds);
    }

    private boolean patchPassthroughs(JsonObject graphObject, FlowGraph graph) {
        List<FlowGraph.EditorPassthrough> current = graph.getEditorPassthroughs() == null
            ? List.of() : graph.getEditorPassthroughs();
        if (current.isEmpty()) {
            graphObject.remove("passthroughs");
            return true;
        }
        JsonArray baseline = graphObject.getAsJsonArray("passthroughs");
        Map<String, JsonObject> baselineByIdentity = new LinkedHashMap<>();
        if (baseline != null) {
            for (JsonElement value : baseline) {
                if (!value.isJsonObject()) {
                    return false;
                }
                JsonObject passthrough = value.getAsJsonObject();
                String nodeId = text(passthrough, "nodeId");
                String inputPin = text(passthrough, "inputPin");
                if (nodeId == null || inputPin == null
                    || baselineByIdentity.put(nodeId + "\u0000" + inputPin, passthrough) != null) {
                    return false;
                }
            }
        }
        Map<String, List<String>> connectionIds = new LinkedHashMap<>();
        for (FlowConnection connection : graph.getConnections()) {
            String editorNode = connection.getEditorSourceNodeId();
            String editorPin = connection.getEditorSourcePin();
            if (editorNode == null || editorNode.isBlank() || editorPin == null
                || !editorPin.startsWith("__passthrough:")) {
                continue;
            }
            String inputPin = editorPin.substring("__passthrough:".length());
            String connectionId = text(connection.getOpaqueProperties(), "connectionId");
            if (connectionId == null || connectionId.isBlank()) {
                return false;
            }
            connectionIds.computeIfAbsent(editorNode + "\u0000" + inputPin, ignored -> new ArrayList<>()).add(connectionId);
        }
        JsonArray output = new JsonArray();
        Set<String> identities = new LinkedHashSet<>();
        for (FlowGraph.EditorPassthrough value : current) {
            if (value == null || value.getNodeId() == null || value.getNodeId().isBlank()
                || value.getInputPinId() == null || value.getInputPinId().isBlank()) {
                return false;
            }
            String identity = value.getNodeId() + "\u0000" + value.getInputPinId();
            if (!identities.add(identity)) {
                return false;
            }
            JsonObject passthrough = baselineByIdentity.get(identity);
            passthrough = passthrough == null ? new JsonObject() : passthrough.deepCopy();
            passthrough.addProperty("nodeId", value.getNodeId());
            passthrough.addProperty("inputPin", value.getInputPinId());
            List<String> ids = connectionIds.getOrDefault(identity, List.of());
            if (ids.isEmpty()) {
                passthrough.remove("connectionIds");
            } else {
                JsonArray references = new JsonArray();
                ids.stream().sorted().forEach(references::add);
                passthrough.add("connectionIds", references);
            }
            output.add(passthrough);
        }
        graphObject.add("passthroughs", output);
        return true;
    }

    private boolean sameNodeTopology(JsonObject graphObject, FlowGraph graph) {
        JsonArray nodes = graphObject.getAsJsonArray("nodes");
        if (nodes == null || graph.getNodes() == null || nodes.size() != graph.getNodes().size()) {
            return false;
        }
        Set<String> baselineIds = new LinkedHashSet<>();
        for (JsonElement value : nodes) {
            if (!value.isJsonObject()) {
                return false;
            }
            String id = text(value.getAsJsonObject(), "instanceId");
            if (id == null || !baselineIds.add(id)) {
                return false;
            }
        }
        return baselineIds.equals(graph.getNodes().keySet());
    }

    private boolean sameConnectionTopology(JsonObject graphObject, FlowGraph graph) {
        JsonArray connections = graphObject.getAsJsonArray("connections");
        if (connections == null || graph.getConnections() == null || connections.size() != graph.getConnections().size()) {
            return false;
        }
        Set<String> baselineIds = new LinkedHashSet<>();
        for (JsonElement value : connections) {
            if (!value.isJsonObject()) {
                return false;
            }
            String id = text(value.getAsJsonObject(), "connectionId");
            if (id == null || !baselineIds.add(id)) {
                return false;
            }
        }
        Set<String> currentIds = new LinkedHashSet<>();
        for (FlowConnection connection : graph.getConnections()) {
            String id = connection == null ? null : text(connection.getOpaqueProperties(), "connectionId");
            if (id == null || !currentIds.add(id)) {
                return false;
            }
        }
        return baselineIds.equals(currentIds);
    }

    private boolean sameNodeOpaque(JsonObject baseline, FlowNode current) {
        Set<String> internal = Set.of("instanceId");
        Set<String> known = Set.of("instanceId", "definition", "definitionVersion", "modeId", "values", "inspector",
            "branches", "repeatables", "inspectorState", "position");
        if (!sameText(text(baseline, "instanceId"), text(current.getOpaqueProperties(), "instanceId"))) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : current.getOpaqueProperties().entrySet()) {
            if (internal.contains(entry.getKey())) {
                continue;
            }
            JsonElement expected = baseline.get(entry.getKey());
            if (expected == null || !expected.equals(entry.getValue())) {
                return false;
            }
        }
        for (String key : baseline.keySet()) {
            if (!known.contains(key) && !current.getOpaqueProperties().containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameConnectionOpaque(JsonObject baseline, FlowConnection current) {
        Set<String> internal = Set.of("connectionId", "sourceElementId", "sourceBranchId", "targetElementId", "targetBranchId");
        Set<String> known = Set.of("connectionId", "source", "target");
        JsonObject source = object(baseline, "source");
        JsonObject target = object(baseline, "target");
        if (source == null || target == null
            || !sameOptionalJson(source.get("elementId"), current.getOpaqueProperties().get("sourceElementId"))
            || !sameOptionalJson(source.get("branchId"), current.getOpaqueProperties().get("sourceBranchId"))
            || !sameOptionalJson(target.get("elementId"), current.getOpaqueProperties().get("targetElementId"))
            || !sameOptionalJson(target.get("branchId"), current.getOpaqueProperties().get("targetBranchId"))) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : current.getOpaqueProperties().entrySet()) {
            if (internal.contains(entry.getKey())) {
                continue;
            }
            JsonElement expected = baseline.get(entry.getKey());
            if (expected == null || !expected.equals(entry.getValue())) {
                return false;
            }
        }
        for (String key : baseline.keySet()) {
            if (!known.contains(key) && !current.getOpaqueProperties().containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    private void copyOpaqueNodeFields(JsonObject source, FlowNode target) {
        Set<String> known = Set.of("instanceId", "definition", "definitionVersion", "modeId", "values", "inspector",
            "branches", "repeatables", "inspectorState", "position");
        source.entrySet().forEach(entry -> {
            if (!known.contains(entry.getKey())) {
                target.getOpaqueProperties().put(entry.getKey(), entry.getValue().deepCopy());
            }
        });
    }

    private void copyOpaqueConnectionFields(JsonObject source, JsonObject sourceEndpoint, JsonObject targetEndpoint,
                                            FlowConnection target) {
        Set<String> known = Set.of("connectionId", "source", "target");
        source.entrySet().forEach(entry -> {
            if (!known.contains(entry.getKey())) {
                target.getOpaqueProperties().put(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        copyEndpointOpaque(sourceEndpoint, "source", target);
        copyEndpointOpaque(targetEndpoint, "target", target);
    }

    private void copyEndpointOpaque(JsonObject endpoint, String prefix, FlowConnection target) {
        for (String field : List.of("elementId", "branchId")) {
            JsonElement value = endpoint.get(field);
            if (value != null) {
                target.getOpaqueProperties().put(prefix + Character.toUpperCase(field.charAt(0)) + field.substring(1), value.deepCopy());
            }
        }
    }

    private static boolean matchesType(ReSyncResourceType type, ServerResourceLocator resource) {
        return type != null && resource != null && type == ReSyncResourceType.byTypeId(resource.resourceType().value());
    }

    private static JsonObject json(JsonValue value) {
        return JsonParser.parseString(value.canonicalText()).getAsJsonObject();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonElement projectedValue(JsonObject typedValue) {
        String state = text(typedValue, "state");
        if ("value".equals(state) || "opaque".equals(state)) {
            JsonElement value = typedValue.get("value");
            return value == null ? JsonNull.INSTANCE : value;
        }
        if ("locator".equals(state)) {
            JsonElement locator = typedValue.get("locator");
            return locator == null ? JsonNull.INSTANCE : locator;
        }
        return JsonNull.INSTANCE;
    }

    private static Object projectedObject(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), projectedObject(entry.getValue())));
            return result;
        }
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            value.getAsJsonArray().forEach(entry -> result.add(projectedObject(entry)));
            return result;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return new BigDecimal(primitive.getAsString());
        }
        return primitive.getAsString();
    }

    private static String descriptorIdentity(JsonObject definition) {
        String owner = definition == null ? null : text(definition, "ownerId");
        String local = definition == null ? null : text(definition, "localId");
        return owner == null || local == null ? "unknown" : owner + ":" + local;
    }

    private static boolean sameOptionalJson(JsonElement expected, JsonElement actual) {
        boolean expectedMissing = expected == null || expected.isJsonNull();
        boolean actualMissing = actual == null || actual.isJsonNull();
        return expectedMissing && actualMissing || !expectedMissing && !actualMissing && expected.equals(actual);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static String text(Map<String, JsonElement> values, String key) {
        JsonElement value = values == null ? null : values.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        String value = text(object, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double number(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || !value.isJsonPrimitive() ? 0D : value.getAsDouble();
    }

    private static String typeId(JsonObject type) {
        if (type == null) {
            return "any";
        }
        JsonObject reference = object(type, "type");
        String id = reference == null ? null : text(reference, "localId");
        return id == null ? "any" : id;
    }

    private static boolean sameText(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    public enum Status {
        LIVE,
        READ_ONLY
    }

    public enum EpochOutcome {
        CURRENT,
        ADVANCED,
        STALE,
        INVALID
    }

    public record EpochDecision(EpochOutcome outcome, long previousEpoch, long authorityEpoch) {
        public boolean accepted() {
            return outcome == EpochOutcome.CURRENT || outcome == EpochOutcome.ADVANCED;
        }

        public boolean advanced() {
            return outcome == EpochOutcome.ADVANCED;
        }
    }

    public record EditCapabilities(boolean nodeValues, boolean nodePositions, boolean connectionRewire,
                                   boolean nodeTopology, boolean connectionTopology, boolean functionSignature) {
    }

    public record InspectorProjection(NodeInstanceId nodeId, Map<InspectorFieldId, InspectorValue> fields) {
        public InspectorProjection {
            nodeId = Objects.requireNonNull(nodeId, "Node ID is required");
            LinkedHashMap<InspectorFieldId, InspectorValue> copy = new LinkedHashMap<>();
            if (fields != null) {
                fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    InspectorFieldId fieldId = Objects.requireNonNull(entry.getKey(), "Inspector field ID is required");
                    InspectorValue value = Objects.requireNonNull(entry.getValue(), "Inspector value is required");
                    if (!fieldId.equals(value.fieldId())) {
                        throw new IllegalArgumentException("Inspector value identity does not match its field key");
                    }
                    copy.put(fieldId, value);
                });
            }
            fields = Collections.unmodifiableMap(copy);
        }

        public static InspectorProjection of(NodeInstanceId nodeId, Map<InspectorFieldId, TypedValue> fields) {
            LinkedHashMap<InspectorFieldId, InspectorValue> projected = new LinkedHashMap<>();
            if (fields != null) {
                fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> projected.put(
                    entry.getKey(), new InspectorValue(entry.getKey(), entry.getValue())));
            }
            return new InspectorProjection(nodeId, projected);
        }

        public Optional<InspectorValue> field(InspectorFieldId fieldId) {
            return Optional.ofNullable(fields.get(Objects.requireNonNull(fieldId, "Inspector field ID is required")));
        }

        public boolean contains(InspectorFieldId fieldId) {
            return fields.containsKey(Objects.requireNonNull(fieldId, "Inspector field ID is required"));
        }
    }

    public record InspectorValue(InspectorFieldId fieldId, TypedValue typedValue) {
        public InspectorValue {
            fieldId = Objects.requireNonNull(fieldId, "Inspector field ID is required");
            typedValue = Objects.requireNonNull(typedValue, "Inspector typed value is required");
        }

        public String fieldIdText() {
            return fieldId.canonicalText();
        }

        public TypeExpr type() {
            return typedValue.type();
        }

        public TypedValue.State state() {
            return typedValue.state();
        }

        public String variantId() {
            return typedValue.variantId();
        }

        public Object value() {
            return typedValue.value();
        }

        public ServerResourceLocator locator() {
            return typedValue.locator();
        }

        public Map<String, Object> unknown() {
            return typedValue.unknown();
        }

        public String canonicalJson() {
            return typedValue.canonicalJson();
        }

        public boolean absent() {
            return state() == TypedValue.State.ABSENT;
        }

        public boolean nullValue() {
            return state() == TypedValue.State.NULL;
        }

        public boolean locatorValue() {
            return state() == TypedValue.State.LOCATOR;
        }

        public boolean opaque() {
            return state() == TypedValue.State.OPAQUE;
        }

        public InspectorValue withTypedValue(TypedValue replacement) {
            InspectorValue value = new InspectorValue(fieldId, replacement);
            value.requireCompatibleReplacement(this);
            return value;
        }

        public InspectorValue withValue(Object material) {
            return withValue(variantId(), material);
        }

        public InspectorValue withValue(String variantId, Object material) {
            return new InspectorValue(fieldId, new TypedValue(type(), TypedValue.State.VALUE, variantId, material,
                null, unknown()));
        }

        public InspectorValue withLocator(ServerResourceLocator locator) {
            return withLocator(variantId(), locator);
        }

        public InspectorValue withLocator(String variantId, ServerResourceLocator locator) {
            return new InspectorValue(fieldId, new TypedValue(type(), TypedValue.State.LOCATOR, variantId, null,
                locator, unknown()));
        }

        public InspectorValue asAbsent() {
            return new InspectorValue(fieldId, TypedValue.absent(type(), unknown()));
        }

        public InspectorValue asNull() {
            return new InspectorValue(fieldId, TypedValue.nullValue(type(), unknown()));
        }

        public InspectorValue withOpaque(Object material) {
            return withOpaque(variantId(), material);
        }

        public InspectorValue withOpaque(String variantId, Object material) {
            return new InspectorValue(fieldId, new TypedValue(type(), TypedValue.State.OPAQUE, variantId, material,
                null, unknown()));
        }

        private void requireCompatibleReplacement(InspectorValue current) {
            requireCompatibleExactReplacement(current);
            if (!unknown().equals(current.unknown())) {
                throw new IllegalArgumentException("Inspector value unknown data cannot change through the UI projection");
            }
        }

        private void requireCompatibleExactReplacement(InspectorValue current) {
            if (!fieldId.equals(current.fieldId())) {
                throw new IllegalArgumentException("Inspector field identity cannot change");
            }
            if (!type().equals(current.type())) {
                throw new IllegalArgumentException("Inspector value type cannot change");
            }
        }
    }

    public record EditorSnapshot(CoreGraphEditorSession owner, GraphDocument graphDocument,
                                 FunctionSourceDocument functionSourceDocument) {
        public EditorSnapshot {
            owner = Objects.requireNonNull(owner, "Core graph editor session is required");
            if ((graphDocument == null) == (functionSourceDocument == null)) {
                throw new IllegalArgumentException("Core graph editor snapshot must carry one document");
            }
        }

        public static EditorSnapshot capture(CoreGraphEditorSession session) {
            Objects.requireNonNull(session, "Core graph editor session is required");
            synchronized (session) {
                return session.isFunction()
                    ? new EditorSnapshot(session, null, session.functionSourceDocument())
                    : new EditorSnapshot(session, session.graphDocument(), null);
            }
        }

        public GraphDocument document() {
            return graphDocument != null ? graphDocument : functionSourceDocument.graph();
        }

        public ContentHash checksum() {
            return graphDocument != null ? graphDocument.checksum() : functionSourceDocument.checksum();
        }

        public String resourceKey() {
            GraphDocument document = document();
            return document.resource().resourceType().value() + ":" + document.resource().id();
        }

        public String sessionIdentity() {
            return resourceKey() + "@" + Integer.toHexString(System.identityHashCode(owner));
        }

        public String publicationIdentity() {
            return owner.activeAuthoringChecksum() != null ? owner.activeAuthoringChecksum().canonicalText() : "unavailable";
        }

        public String catalogIdentity() {
            return document().catalogBinding().toString();
        }

        public int nodeCount() {
            return document().nodes().size();
        }

        public int connectionCount() {
            return document().connections().size();
        }

        public boolean currentFor(CoreGraphEditorSession session) {
            if (owner != session) {
                return false;
            }
            synchronized (owner) {
                return graphDocument != null ? owner.isGraph() && owner.graphDocument() == graphDocument
                    : owner.isFunction() && owner.functionSourceDocument() == functionSourceDocument;
            }
        }
    }

    public record ProjectionResult(FlowGraph graph, int sourceNodeCount, int projectedNodeCount,
                                   List<String> droppedNodeIdentities, int sourceConnectionCount,
                                   int projectedConnectionCount, List<String> droppedConnectionIdentities,
                                   String documentChecksum, String topologyChecksum, String rejectionReason) {
        public ProjectionResult {
            droppedNodeIdentities = droppedNodeIdentities == null ? List.of() : List.copyOf(droppedNodeIdentities);
            droppedConnectionIdentities = droppedConnectionIdentities == null
                ? List.of() : List.copyOf(droppedConnectionIdentities);
            documentChecksum = documentChecksum == null ? "" : documentChecksum;
            topologyChecksum = topologyChecksum == null ? "" : topologyChecksum;
            rejectionReason = rejectionReason == null ? "" : rejectionReason;
            if (sourceNodeCount < 0 || projectedNodeCount < 0 || projectedNodeCount > sourceNodeCount
                || sourceConnectionCount < 0 || projectedConnectionCount < 0
                || projectedConnectionCount > sourceConnectionCount) {
                throw new IllegalArgumentException("Core graph projection counts are invalid");
            }
        }

        public static ProjectionResult rejected(String checksum, String reason) {
            return new ProjectionResult(null, 0, 0, List.of(), 0, 0, List.of(), checksum, "", reason);
        }

        public boolean complete() {
            return graph != null && rejectionReason.isBlank() && sourceNodeCount == projectedNodeCount
                && droppedNodeIdentities.isEmpty() && sourceConnectionCount == projectedConnectionCount
                && droppedConnectionIdentities.isEmpty();
        }

        public String compactReason() {
            if (complete()) {
                return "complete";
            }
            if (!rejectionReason.isBlank()) {
                return rejectionReason;
            }
            if (graph == null) {
                return "projected_graph_missing";
            }
            return "dropped_nodes=" + droppedNodeIdentities.size() + ",dropped_connections="
                + droppedConnectionIdentities.size();
        }
    }

    public record EditorProvenance(long revision, String protocolHash, String mutationId) {
        public EditorProvenance {
            protocolHash = protocolHash == null ? "" : protocolHash;
            mutationId = mutationId == null ? "" : mutationId;
        }

        public static EditorProvenance from(Baseline baseline) {
            Objects.requireNonNull(baseline, "Core graph baseline is required");
            return new EditorProvenance(baseline.revision(),
                baseline.protocolHash() == null ? "" : baseline.protocolHash().canonicalText(),
                baseline.mutationId() == null ? "" : baseline.mutationId().toString());
        }

        public boolean matches(FlowGraph graph) {
            return graph != null && graph.getResourceRevision() == revision
                && Objects.equals(graph.getResourceHash(), protocolHash)
                && Objects.equals(graph.getResourceMutationId(), mutationId);
        }
    }

    public record Key(String serverId, ReSyncResourceType type, String id) {
        public Key {
            if (serverId == null || serverId.isBlank()) {
                throw new IllegalArgumentException("Core graph server ID is required");
            }
            type = Objects.requireNonNull(type, "Core graph type is required");
            if (!type.isGraph()) {
                throw new IllegalArgumentException("Core graph type is required");
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Core graph ID is required");
            }
        }

        public static Key from(ReSyncResourceType type, ServerResourceLocator resource) {
            Objects.requireNonNull(resource, "Core graph resource is required");
            return new Key(resource.serverId().canonicalText(), type, resource.id());
        }
    }

    public record Baseline(Key key, FlowGraph graph, JsonObject corePayload, GraphDocument graphDocument,
                           FunctionSourceDocument functionSourceDocument, Status status, long revision,
                           UUID mutationId, ContentHash protocolHash, ContentHash assetHash,
                           ResourceActivationState activationState, String rejectionReason) {
        public Baseline {
            key = Objects.requireNonNull(key, "Core graph key is required");
            graph = detachedGraph(Objects.requireNonNull(graph, "Projected Flow graph is required"));
            corePayload = Objects.requireNonNull(corePayload, "Core graph payload is required").deepCopy();
            if ((graphDocument == null) == (functionSourceDocument == null)) {
                throw new IllegalArgumentException("A Core graph baseline must carry one payload kind");
            }
            if (revision < 1L) {
                throw new IllegalArgumentException("Core baseline revision must be positive");
            }
            status = Objects.requireNonNull(status, "Core graph baseline status is required");
            rejectionReason = rejectionReason == null ? "" : rejectionReason;
        }

        @Override
        public FlowGraph graph() {
            return detachedGraph(graph);
        }

        @Override
        public JsonObject corePayload() {
            return corePayload.deepCopy();
        }

        public boolean readOnly() {
            return status == Status.READ_ONLY;
        }

        public boolean isReadOnly() {
            return readOnly();
        }

        public boolean tombstoned() {
            return false;
        }

        public boolean canSave() {
            return !readOnly();
        }

        public boolean canDelete() {
            return canSave();
        }

        public boolean canActivate() {
            return canSave();
        }

        public EditCapabilities editCapabilities() {
            return readOnly() ? READ_ONLY_CAPABILITIES : EDIT_CAPABILITIES;
        }

        public EditorProvenance provenance() {
            return EditorProvenance.from(this);
        }

        public Object payload() {
            return functionSourceDocument != null ? functionSourceDocument : graphDocument;
        }
    }

    private static FlowGraph detachedGraph(FlowGraph graph) {
        if (graph == null) {
            return null;
        }
        JsonObject structure = FlowSerializer.toJsonObject(graph);
        structure.remove("contentProperties");
        JsonElement nodeStructure = structure.get("nodes");
        if (nodeStructure != null && nodeStructure.isJsonObject()) {
            JsonObject nodes = nodeStructure.getAsJsonObject();
            nodes.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonObject()) {
                    entry.getValue().getAsJsonObject().remove("inputValues");
                }
            });
        }
        JsonElement variableStructure = structure.get("localVariables");
        if (variableStructure != null && variableStructure.isJsonArray()) {
            JsonArray variables = variableStructure.getAsJsonArray();
            variables.forEach(value -> {
                if (value.isJsonObject()) {
                    value.getAsJsonObject().remove("initialValue");
                }
            });
        }

        FlowGraph detached = FlowSerializer.deserialize(structure);
        detached.setContentProperties(detachedValues(graph.getContentProperties()));
        if (graph.getNodes() != null && detached.getNodes() != null) {
            graph.getNodes().forEach((id, source) -> {
                FlowNode target = detached.getNodes().get(id);
                if (source != null && target != null) {
                    target.setInputValues(source.getInputValues() == null ? null : detachedValues(source.getInputValues()));
                }
            });
        }
        if (graph.getLocalVariables() != null && detached.getLocalVariables() != null) {
            int count = Math.min(graph.getLocalVariables().size(), detached.getLocalVariables().size());
            for (int index = 0; index < count; index++) {
                FlowVariable source = graph.getLocalVariables().get(index);
                FlowVariable target = detached.getLocalVariables().get(index);
                if (source != null && target != null) {
                    target.setInitialValue(detachedValue(source.getInitialValue()));
                }
            }
        }
        return detached;
    }

    private static Map<String, Object> detachedValues(Map<String, Object> values) {
        Map<String, Object> detached = new LinkedHashMap<>();
        values.forEach((key, value) -> detached.put(key, detachedValue(value)));
        return detached;
    }

    private static Object detachedValue(Object value) {
        if (value instanceof JsonElement element) {
            return element.deepCopy();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> detached = new LinkedHashMap<>();
            map.forEach((key, entry) -> detached.put(key, detachedValue(entry)));
            return detached;
        }
        if (value instanceof List<?> list) {
            List<Object> detached = new ArrayList<>(list.size());
            list.forEach(entry -> detached.add(detachedValue(entry)));
            return detached;
        }
        return value;
    }

    public record Tombstone(Key key, long revision, UUID mutationId, ContentHash protocolHash) {
        public Tombstone {
            key = Objects.requireNonNull(key, "Core graph tombstone key is required");
            if (revision < 1L) {
                throw new IllegalArgumentException("Core tombstone revision must be positive");
            }
            mutationId = Objects.requireNonNull(mutationId, "Core graph tombstone mutation is required");
            protocolHash = Objects.requireNonNull(protocolHash, "Core graph tombstone hash is required");
        }
    }

    private record Authority(Baseline baseline, Tombstone tombstone, long authorityEpoch) {
        private Authority {
            if ((baseline == null) == (tombstone == null)) {
                throw new IllegalArgumentException("Core graph authority must carry one state");
            }
            if (authorityEpoch < 1L) {
                throw new IllegalArgumentException("Core graph authority epoch must be positive");
            }
        }

        private static Authority baseline(Baseline baseline, long authorityEpoch) {
            return new Authority(Objects.requireNonNull(baseline, "Core graph baseline is required"), null, authorityEpoch);
        }

        private static Authority tombstone(Tombstone tombstone, long authorityEpoch) {
            return new Authority(null, Objects.requireNonNull(tombstone, "Core graph tombstone is required"), authorityEpoch);
        }

        private long revision() {
            return baseline != null ? baseline.revision() : tombstone.revision();
        }
    }

    public record ReverseResult(boolean lossless, GraphDocument graphDocument,
                                FunctionSourceDocument functionSourceDocument, String reason) {
        public ReverseResult {
            if ((graphDocument == null) == (functionSourceDocument == null) && lossless) {
                throw new IllegalArgumentException("A lossless Core conversion must carry one payload");
            }
            reason = reason == null ? "" : reason;
        }

        public static ReverseResult accepted(GraphDocument graphDocument) {
            return new ReverseResult(true, graphDocument, null, "");
        }

        public static ReverseResult accepted(FunctionSourceDocument functionSourceDocument) {
            return new ReverseResult(true, null, functionSourceDocument, "");
        }

        public static ReverseResult rejected(String reason) {
            return new ReverseResult(false, null, null, reason);
        }

        public Object payload() {
            return functionSourceDocument != null ? functionSourceDocument : graphDocument;
        }
    }
}
