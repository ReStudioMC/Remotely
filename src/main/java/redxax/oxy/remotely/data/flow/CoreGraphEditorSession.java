package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphPassthrough;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ModeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public final class CoreGraphEditorSession {
    private final Kind kind;
    private ContentHash activeAuthoringChecksum;
    private Set<ContractRef<CapabilityId>> activeAuthoringCapabilities;
    private Set<ContractRef<CapabilityId>> editCapabilities;
    private final AuthoringTemplatePayload.Kind templateKind;
    private final Map<String, Object> templateUnknown;
    private GraphDocument graph;
    private FunctionSourceDocument source;
    private GraphDocument baselineGraph;
    private FunctionSourceDocument baselineSource;
    private long baseRevision;
    private long mutationVersion;
    private long baselineMutationVersion;
    private final ArrayList<State> history = new ArrayList<>();
    private int historyCursor;

    public CoreGraphEditorSession(GraphDocument baseline) {
        this(baseline, null, Set.of(), Set.of(), null, Map.of());
    }

    public CoreGraphEditorSession(GraphDocument baseline, ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities) {
        this(baseline, activeAuthoringChecksum, activeAuthoringCapabilities, Set.of(), null, Map.of());
    }

    public CoreGraphEditorSession(GraphDocument baseline, ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                  Collection<ContractRef<CapabilityId>> editCapabilities) {
        this(baseline, activeAuthoringChecksum, activeAuthoringCapabilities, editCapabilities, null, Map.of());
    }

    public CoreGraphEditorSession(GraphDocument baseline, CatalogBinding expectedBinding,
                                  ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities) {
        this(requireBinding(baseline, expectedBinding), activeAuthoringChecksum, activeAuthoringCapabilities);
    }

    public CoreGraphEditorSession(GraphDocument baseline, CatalogBinding expectedBinding,
                                  ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                  Collection<ContractRef<CapabilityId>> editCapabilities) {
        this(requireBinding(baseline, expectedBinding), activeAuthoringChecksum, activeAuthoringCapabilities,
            editCapabilities);
    }

    public CoreGraphEditorSession(FunctionSourceDocument baseline) {
        this(baseline, null, Set.of(), Set.of(), null, Map.of());
    }

    public CoreGraphEditorSession(FunctionSourceDocument baseline, ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities) {
        this(baseline, activeAuthoringChecksum, activeAuthoringCapabilities, Set.of(), null, Map.of());
    }

    public CoreGraphEditorSession(FunctionSourceDocument baseline, ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                  Collection<ContractRef<CapabilityId>> editCapabilities) {
        this(baseline, activeAuthoringChecksum, activeAuthoringCapabilities, editCapabilities, null, Map.of());
    }

    public CoreGraphEditorSession(FunctionSourceDocument baseline, CatalogBinding expectedBinding,
                                  ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities) {
        this(requireBinding(baseline, expectedBinding), activeAuthoringChecksum, activeAuthoringCapabilities);
    }

    public CoreGraphEditorSession(FunctionSourceDocument baseline, CatalogBinding expectedBinding,
                                  ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                  Collection<ContractRef<CapabilityId>> editCapabilities) {
        this(requireBinding(baseline, expectedBinding), activeAuthoringChecksum, activeAuthoringCapabilities,
            editCapabilities);
    }

    public CoreGraphEditorSession(AuthoringTemplatePayload payload) {
        this(payload, null, Set.of(), Set.of());
    }

    public CoreGraphEditorSession(AuthoringTemplatePayload payload, ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities) {
        this(payload, activeAuthoringChecksum, activeAuthoringCapabilities, Set.of());
    }

    public CoreGraphEditorSession(AuthoringTemplatePayload payload, ContentHash activeAuthoringChecksum,
                                  Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                  Collection<ContractRef<CapabilityId>> editCapabilities) {
        this(payload, activeAuthoringChecksum, activeAuthoringCapabilities, editCapabilities, true);
    }

    public CoreGraphEditorSession(AuthoringTemplateResponse response) {
        this(requirePayload(response), responseChecksum(response), responseCapabilities(response),
            responseEditCapabilities(response));
        requireResponseIdentity(response, this);
    }

    private CoreGraphEditorSession(GraphDocument baseline, ContentHash activeAuthoringChecksum,
                                   Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                   Collection<ContractRef<CapabilityId>> editCapabilities,
                                   AuthoringTemplatePayload.Kind templateKind,
                                   Map<String, ?> templateUnknown) {
        graph = requireGraphBaseline(baseline);
        source = null;
        kind = Kind.GRAPH;
        this.activeAuthoringChecksum = activeAuthoringChecksum;
        this.activeAuthoringCapabilities = immutableCapabilities(activeAuthoringCapabilities);
        this.editCapabilities = immutableCapabilities(editCapabilities);
        this.templateKind = templateKind != null ? templateKind : graphTemplateKind(graph);
        this.templateUnknown = immutableUnknown(templateUnknown);
        baselineGraph = graph;
        baselineSource = null;
        baseRevision = graph.revision();
        history.add(new State(graph, null, mutationVersion));
    }

    private CoreGraphEditorSession(FunctionSourceDocument baseline, ContentHash activeAuthoringChecksum,
                                   Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                   Collection<ContractRef<CapabilityId>> editCapabilities,
                                   AuthoringTemplatePayload.Kind templateKind,
                                   Map<String, ?> templateUnknown) {
        source = requireSourceBaseline(baseline);
        graph = null;
        kind = Kind.FUNCTION;
        this.activeAuthoringChecksum = activeAuthoringChecksum;
        this.activeAuthoringCapabilities = immutableCapabilities(activeAuthoringCapabilities);
        this.editCapabilities = immutableCapabilities(editCapabilities);
        this.templateKind = templateKind != null ? templateKind : AuthoringTemplatePayload.Kind.FUNCTION;
        this.templateUnknown = immutableUnknown(templateUnknown);
        baselineSource = source;
        baselineGraph = null;
        baseRevision = source.graph().revision();
        history.add(new State(null, source, mutationVersion));
    }

    private CoreGraphEditorSession(AuthoringTemplatePayload payload, ContentHash activeAuthoringChecksum,
                                   Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                   Collection<ContractRef<CapabilityId>> editCapabilities,
                                   boolean ignored) {
        Objects.requireNonNull(payload, "Authoring template payload is required");
        if (payload instanceof AuthoringTemplatePayload.Resource) {
            throw new IllegalArgumentException("Resource authoring templates cannot open a graph or function editor");
        }
        if (payload.graphDocument() != null) {
            graph = requireGraphBaseline(payload.graphDocument());
            source = null;
            kind = Kind.GRAPH;
            baselineGraph = graph;
            baselineSource = null;
            templateKind = payload.kind();
        } else if (payload.functionSourceDocument() != null) {
            source = requireSourceBaseline(payload.functionSourceDocument());
            graph = null;
            kind = Kind.FUNCTION;
            baselineSource = source;
            baselineGraph = null;
            templateKind = payload.kind();
        } else {
            throw new IllegalArgumentException("Authoring template payload does not contain an editable document");
        }
        this.activeAuthoringChecksum = activeAuthoringChecksum;
        this.activeAuthoringCapabilities = immutableCapabilities(activeAuthoringCapabilities);
        this.editCapabilities = immutableCapabilities(editCapabilities);
        this.templateUnknown = immutableUnknown(payload.unknown());
        baseRevision = currentGraph().revision();
        history.add(currentState());
    }

    public static CoreGraphEditorSession fromTemplate(AuthoringTemplatePayload payload) {
        return new CoreGraphEditorSession(payload);
    }

    public static CoreGraphEditorSession fromTemplate(AuthoringTemplatePayload payload,
                                                      ContentHash activeAuthoringChecksum,
                                                      Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities) {
        return new CoreGraphEditorSession(payload, activeAuthoringChecksum, activeAuthoringCapabilities);
    }

    public static CoreGraphEditorSession fromTemplate(AuthoringTemplatePayload payload,
                                                      ContentHash activeAuthoringChecksum,
                                                      Collection<ContractRef<CapabilityId>> activeAuthoringCapabilities,
                                                      Collection<ContractRef<CapabilityId>> editCapabilities) {
        return new CoreGraphEditorSession(payload, activeAuthoringChecksum, activeAuthoringCapabilities,
            editCapabilities);
    }

    public static CoreGraphEditorSession fromResponse(AuthoringTemplateResponse response) {
        return new CoreGraphEditorSession(response);
    }

    public synchronized Kind kind() {
        return kind;
    }

    public synchronized boolean isGraph() {
        return kind == Kind.GRAPH;
    }

    public synchronized boolean isFunction() {
        return kind == Kind.FUNCTION;
    }

    public synchronized GraphDocument graphDocument() {
        return graph;
    }

    public synchronized GraphDocument graph() {
        return graph;
    }

    public synchronized FunctionSourceDocument functionSourceDocument() {
        return source;
    }

    public synchronized FunctionSourceDocument source() {
        return source;
    }

    public synchronized Object payload() {
        return kind == Kind.GRAPH ? graph : source;
    }

    public synchronized GraphDocument baselineGraphDocument() {
        return baselineGraph;
    }

    public synchronized FunctionSourceDocument baselineFunctionSourceDocument() {
        return baselineSource;
    }

    public synchronized long revision() {
        return currentGraph().revision();
    }

    public synchronized long currentRevision() {
        return revision();
    }

    public synchronized long baseRevision() {
        return baseRevision;
    }

    public synchronized long baselineRevision() {
        return baseRevision;
    }

    public synchronized CatalogBinding catalogBinding() {
        return currentGraph().catalogBinding();
    }

    public synchronized CatalogBinding binding() {
        return catalogBinding();
    }

    public synchronized ServerResourceLocator resource() {
        return currentGraph().resource();
    }

    public synchronized Set<ContractRef<CapabilityId>> requiredCapabilities() {
        return currentGraph().requiredCapabilities();
    }

    public synchronized ContentHash activeAuthoringChecksum() {
        return activeAuthoringChecksum;
    }

    public synchronized ContentHash authoringChecksum() {
        return activeAuthoringChecksum;
    }

    public synchronized ContentHash authoringPublicationChecksum() {
        return activeAuthoringChecksum;
    }

    public synchronized boolean hasActiveAuthoringChecksum() {
        return activeAuthoringChecksum != null;
    }

    public synchronized Set<ContractRef<CapabilityId>> activeAuthoringCapabilities() {
        return activeAuthoringCapabilities;
    }

    public synchronized Set<ContractRef<CapabilityId>> authoringCapabilities() {
        return activeAuthoringCapabilities;
    }

    public synchronized Set<ContractRef<CapabilityId>> editCapabilities() {
        return editCapabilities;
    }

    public synchronized AuthoringTemplatePayload.Kind templateKind() {
        return templateKind;
    }

    public synchronized Map<String, Object> templateUnknown() {
        return templateUnknown;
    }

    public synchronized CommandGraphMetadata commandMetadata() {
        requireCommand();
        return CommandGraphMetadata.from(graph);
    }

    public synchronized CoreGraphEditorSession setCommandMetadata(CommandGraphMetadata metadata) {
        requireCommand();
        Objects.requireNonNull(metadata, "Command metadata is required");
        return mutateGraph(metadata::apply);
    }

    public synchronized JsonValue.JsonObject canonicalPayload() {
        return kind == Kind.GRAPH ? GraphDocumentCodec.INSTANCE.encode(graph) : FunctionSourceDocumentCodec.INSTANCE.encode(source);
    }

    public synchronized String canonicalPayloadJson() {
        return canonicalPayload().canonicalText();
    }

    public synchronized String canonicalJson() {
        return canonicalPayloadJson();
    }

    public synchronized byte[] canonicalPayloadBytes() {
        return canonicalPayload().canonicalBytes();
    }

    public synchronized ContentHash checksum() {
        return kind == Kind.GRAPH ? graph.checksum() : source.checksum();
    }

    public synchronized ContentHash canonicalChecksum() {
        return checksum();
    }

    public synchronized boolean dirty() {
        return mutationVersion != baselineMutationVersion;
    }

    public synchronized boolean isDirty() {
        return dirty();
    }

    public synchronized boolean hasChanges() {
        return dirty();
    }

    public synchronized boolean isRevisionZeroBaseline() {
        return baseRevision == 0;
    }

    public synchronized AuthoringTemplatePayload templatePayload() {
        if (templateKind == null || revision() != 0) {
            throw new IllegalStateException("The current session is not a revision-zero authoring template");
        }
        return switch (templateKind) {
            case FLOW -> new AuthoringTemplatePayload.Flow(graph, templateUnknown);
            case COMMAND -> new AuthoringTemplatePayload.Command(graph, templateUnknown);
            case FUNCTION -> new AuthoringTemplatePayload.Function(source, templateUnknown);
            case RESOURCE -> throw new IllegalStateException(
                "Resource authoring templates cannot be emitted by a graph or function editor");
        };
    }

    public synchronized JsonValue.JsonObject canonicalTemplatePayload() {
        AuthoringTemplatePayload payload = templatePayload();
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>();
        values.put("kind", JsonValue.of(payload.kind().wireName()));
        values.put("document", canonicalPayload());
        payload.unknown().forEach((key, value) -> {
            if (values.putIfAbsent(key, JsonValue.fromJava(value)) != null) {
                throw new IllegalArgumentException("Authoring template unknown data collides with a known field: " + key);
            }
        });
        return JsonValue.object(values);
    }

    public synchronized HistoryState historyState() {
        return new HistoryState(history.size(), historyCursor, historyCursor, history.size() - historyCursor - 1, dirty());
    }

    public synchronized boolean canUndo() {
        return historyCursor > 0;
    }

    public synchronized boolean canRedo() {
        return historyCursor + 1 < history.size();
    }

    public synchronized boolean undo() {
        if (!canUndo()) {
            return false;
        }
        historyCursor--;
        restore(history.get(historyCursor));
        return true;
    }

    public synchronized boolean redo() {
        if (!canRedo()) {
            return false;
        }
        historyCursor++;
        restore(history.get(historyCursor));
        return true;
    }

    public synchronized CoreGraphEditorSession mutateGraph(UnaryOperator<GraphDocument> mutation) {
        Objects.requireNonNull(mutation, "Graph mutation is required");
        return commitGraph(Objects.requireNonNull(mutation.apply(currentGraph()), "Graph mutation cannot return null"));
    }

    public synchronized CoreGraphEditorSession replaceGraph(GraphDocument desired) {
        return commitGraph(desired);
    }

    public synchronized CoreGraphEditorSession setGraph(GraphDocument desired) {
        return replaceGraph(desired);
    }

    public synchronized CoreGraphEditorSession applyGraphPatches(List<WorkspacePatch<JsonValue>> patches) {
        Objects.requireNonNull(patches, "Graph patches are required");
        if (patches.isEmpty()) {
            return this;
        }
        GraphDocument desired = CoreGraphWorkspacePatch.apply(currentGraph(), patches);
        return commitGraph(desired);
    }

    public synchronized CoreGraphEditorSession applyPatch(List<WorkspacePatch<JsonValue>> patches) {
        return applyGraphPatches(patches);
    }

    public synchronized CoreGraphEditorSession applyFunctionPatch(CoreFunctionSourcePatch.Patch patch) {
        requireFunction();
        Objects.requireNonNull(patch, "Function source patch is required");
        if (patch.isEmpty()) {
            return this;
        }
        return commitSource(CoreFunctionSourcePatch.apply(source, patch));
    }

    public synchronized CoreGraphEditorSession applyPatch(CoreFunctionSourcePatch.Patch patch) {
        return applyFunctionPatch(patch);
    }

    public synchronized CoreGraphEditorSession setNodeModeId(NodeInstanceId nodeId, ModeId modeId) {
        return updateNode(nodeId, node -> copyNode(node, modeId, node.values(), node.inspectorFields(), node.branches(),
            node.repeatables(), node.inspectorState(), node.x(), node.y()));
    }

    public synchronized CoreGraphEditorSession setModeId(NodeInstanceId nodeId, ModeId modeId) {
        return setNodeModeId(nodeId, modeId);
    }

    public synchronized CoreGraphEditorSession setNodeValues(NodeInstanceId nodeId, Map<PinId, PinValue> values) {
        return updateNode(nodeId, node -> copyNode(node, node.modeId(), values, node.inspectorFields(), node.branches(),
            node.repeatables(), node.inspectorState(), node.x(), node.y()));
    }

    public synchronized CoreGraphEditorSession setValues(NodeInstanceId nodeId, Map<PinId, PinValue> values) {
        return setNodeValues(nodeId, values);
    }

    public synchronized CoreGraphEditorSession setNodeValue(NodeInstanceId nodeId, PinValue value) {
        Objects.requireNonNull(value, "Pin value is required");
        return updateNode(nodeId, node -> {
            LinkedHashMap<PinId, PinValue> values = new LinkedHashMap<>(node.values());
            PinValue previous = values.get(value.pinId());
            values.put(value.pinId(), previous == null ? value : new PinValue(value.pinId(), value.value(), previous.unknown()));
            return copyNode(node, node.modeId(), values, node.inspectorFields(), node.branches(), node.repeatables(),
                node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession setNodeValue(NodeInstanceId nodeId, PinId pinId, TypedValue value) {
        return setNodeValue(nodeId, new PinValue(Objects.requireNonNull(pinId, "Pin ID is required"),
            Objects.requireNonNull(value, "Typed value is required")));
    }

    public synchronized CoreGraphEditorSession removeNodeValue(NodeInstanceId nodeId, PinId pinId) {
        Objects.requireNonNull(pinId, "Pin ID is required");
        return updateNode(nodeId, node -> {
            if (!node.values().containsKey(pinId)) {
                return node;
            }
            LinkedHashMap<PinId, PinValue> values = new LinkedHashMap<>(node.values());
            values.remove(pinId);
            return copyNode(node, node.modeId(), values, node.inspectorFields(), node.branches(), node.repeatables(),
                node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession setNodeInspector(NodeInstanceId nodeId,
                                                                 Map<InspectorFieldId, TypedValue> fields) {
        return updateNode(nodeId, node -> copyNode(node, node.modeId(), node.values(), fields, node.branches(),
            node.repeatables(), node.inspectorState(), node.x(), node.y()));
    }

    public synchronized CoreGraphEditorSession setInspector(NodeInstanceId nodeId,
                                                             Map<InspectorFieldId, TypedValue> fields) {
        return setNodeInspector(nodeId, fields);
    }

    public synchronized CoreGraphEditorSession setNodeInspectorValues(NodeInstanceId nodeId,
                                                                        Map<InspectorFieldId, TypedValue> fields) {
        return setNodeInspector(nodeId, fields);
    }

    public synchronized CoreGraphEditorSession setNodeInspectorField(NodeInstanceId nodeId, InspectorFieldId fieldId,
                                                                      TypedValue value) {
        Objects.requireNonNull(fieldId, "Inspector field ID is required");
        Objects.requireNonNull(value, "Inspector typed value is required");
        return updateNode(nodeId, node -> {
            LinkedHashMap<InspectorFieldId, TypedValue> fields = new LinkedHashMap<>(node.inspectorFields());
            fields.put(fieldId, value);
            return copyNode(node, node.modeId(), node.values(), fields, node.branches(), node.repeatables(),
                node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession removeNodeInspectorField(NodeInstanceId nodeId, InspectorFieldId fieldId) {
        Objects.requireNonNull(fieldId, "Inspector field ID is required");
        return updateNode(nodeId, node -> {
            if (!node.inspectorFields().containsKey(fieldId)) {
                return node;
            }
            LinkedHashMap<InspectorFieldId, TypedValue> fields = new LinkedHashMap<>(node.inspectorFields());
            fields.remove(fieldId);
            return copyNode(node, node.modeId(), node.values(), fields, node.branches(), node.repeatables(),
                node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession setNodeBranches(NodeInstanceId nodeId, List<BranchBinding> branches) {
        return updateNode(nodeId, node -> copyNode(node, node.modeId(), node.values(), node.inspectorFields(), branches,
            node.repeatables(), node.inspectorState(), node.x(), node.y()));
    }

    public synchronized CoreGraphEditorSession setBranches(NodeInstanceId nodeId, List<BranchBinding> branches) {
        return setNodeBranches(nodeId, branches);
    }

    public synchronized CoreGraphEditorSession setNodeBranchBindings(NodeInstanceId nodeId,
                                                                       List<BranchBinding> branches) {
        return setNodeBranches(nodeId, branches);
    }

    public synchronized CoreGraphEditorSession setNodeBranch(NodeInstanceId nodeId, BranchBinding branch) {
        Objects.requireNonNull(branch, "Branch binding is required");
        return updateNode(nodeId, node -> {
            ArrayList<BranchBinding> branches = new ArrayList<>(node.branches());
            for (int index = 0; index < branches.size(); index++) {
                if (branches.get(index).branchId().equals(branch.branchId())) {
                    branches.set(index, branch);
                    return copyNode(node, node.modeId(), node.values(), node.inspectorFields(), branches,
                        node.repeatables(), node.inspectorState(), node.x(), node.y());
                }
            }
            branches.add(branch);
            return copyNode(node, node.modeId(), node.values(), node.inspectorFields(), branches,
                node.repeatables(), node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession removeNodeBranch(NodeInstanceId nodeId,
                                                                  BranchId branchId) {
        Objects.requireNonNull(branchId, "Branch ID is required");
        return updateNode(nodeId, node -> {
            ArrayList<BranchBinding> branches = new ArrayList<>(node.branches());
            branches.removeIf(branch -> branch.branchId().equals(branchId));
            return copyNode(node, node.modeId(), node.values(), node.inspectorFields(), branches,
                node.repeatables(), node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession setNodeRepeatables(NodeInstanceId nodeId, List<RepeatableBinding> repeatables) {
        return updateNode(nodeId, node -> copyNode(node, node.modeId(), node.values(), node.inspectorFields(), node.branches(),
            repeatables, node.inspectorState(), node.x(), node.y()));
    }

    public synchronized CoreGraphEditorSession setRepeatables(NodeInstanceId nodeId, List<RepeatableBinding> repeatables) {
        return setNodeRepeatables(nodeId, repeatables);
    }

    public synchronized CoreGraphEditorSession setNodeRepeatableBindings(NodeInstanceId nodeId,
                                                                           List<RepeatableBinding> repeatables) {
        return setNodeRepeatables(nodeId, repeatables);
    }

    public synchronized CoreGraphEditorSession setNodeRepeatable(NodeInstanceId nodeId, RepeatableBinding repeatable) {
        Objects.requireNonNull(repeatable, "Repeatable binding is required");
        return updateNode(nodeId, node -> {
            ArrayList<RepeatableBinding> repeatables = new ArrayList<>(node.repeatables());
            for (int index = 0; index < repeatables.size(); index++) {
                if (repeatables.get(index).groupId().equals(repeatable.groupId())) {
                    repeatables.set(index, repeatable);
                    return copyNode(node, node.modeId(), node.values(), node.inspectorFields(), node.branches(),
                        repeatables, node.inspectorState(), node.x(), node.y());
                }
            }
            repeatables.add(repeatable);
            return copyNode(node, node.modeId(), node.values(), node.inspectorFields(), node.branches(),
                repeatables, node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession removeNodeRepeatable(NodeInstanceId nodeId,
                                                                      RepeatableGroupId groupId) {
        Objects.requireNonNull(groupId, "Repeatable group ID is required");
        return updateNode(nodeId, node -> {
            ArrayList<RepeatableBinding> repeatables = new ArrayList<>(node.repeatables());
            repeatables.removeIf(repeatable -> repeatable.groupId().equals(groupId));
            return copyNode(node, node.modeId(), node.values(), node.inspectorFields(), node.branches(),
                repeatables, node.inspectorState(), node.x(), node.y());
        });
    }

    public synchronized CoreGraphEditorSession setNodeInspectorState(NodeInstanceId nodeId, InspectorState state) {
        return updateNode(nodeId, node -> copyNode(node, node.modeId(), node.values(), node.inspectorFields(), node.branches(),
            node.repeatables(), state, node.x(), node.y()));
    }

    public synchronized CoreGraphEditorSession setInspectorState(NodeInstanceId nodeId, InspectorState state) {
        return setNodeInspectorState(nodeId, state);
    }

    public synchronized CoreGraphEditorSession setNodePosition(NodeInstanceId nodeId, double x, double y) {
        return updateNode(nodeId, node -> copyNode(node, node.modeId(), node.values(), node.inspectorFields(), node.branches(),
            node.repeatables(), node.inspectorState(), x, y));
    }

    public synchronized CoreGraphEditorSession replaceNode(GraphNode replacement) {
        Objects.requireNonNull(replacement, "Graph node is required");
        return updateNode(replacement.instanceId(), ignored -> replacement);
    }

    public synchronized CoreGraphEditorSession addNode(GraphNode node) {
        return addNode(node, Set.of());
    }

    public synchronized CoreGraphEditorSession addNode(GraphNode node,
                                                       Collection<ContractRef<CapabilityId>> requiredCapabilities) {
        Objects.requireNonNull(node, "Graph node is required");
        return mutateGraph(current -> {
            ArrayList<GraphNode> nodes = new ArrayList<>(current.nodes());
            nodes.add(node);
            Set<ContractRef<CapabilityId>> capabilities = new HashSet<>(current.requiredCapabilities());
            if (requiredCapabilities != null) {
                requiredCapabilities.forEach(capability -> capabilities.add(
                    Objects.requireNonNull(capability, "Required node capability is required")));
            }
            return new GraphDocument(current.schemaVersion(), current.resource(), current.revision(), current.catalogBinding(),
                capabilities, nodes, current.connections(), current.passthroughs(), current.variables(), current.functions(), current.unknown());
        });
    }

    public synchronized CoreGraphEditorSession removeNode(NodeInstanceId nodeId) {
        Objects.requireNonNull(nodeId, "Node ID is required");
        return mutateGraph(current -> {
            ArrayList<GraphNode> nodes = new ArrayList<>(current.nodes());
            boolean removed = nodes.removeIf(node -> node.instanceId().equals(nodeId));
            if (!removed) {
                return current;
            }
            ArrayList<GraphConnection> connections = new ArrayList<>(current.connections());
            Set<ConnectionId> removedConnections = connections.stream()
                .filter(connection -> connection.source().nodeId().equals(nodeId)
                    || connection.target().nodeId().equals(nodeId))
                .map(GraphConnection::connectionId).collect(Collectors.toSet());
            connections.removeIf(connection -> removedConnections.contains(connection.connectionId()));
            List<GraphPassthrough> passthroughs = current.passthroughs().stream()
                .filter(value -> !value.nodeId().equals(nodeId))
                .map(value -> new GraphPassthrough(value.nodeId(), value.inputPin(),
                    value.connectionIds().stream().filter(connectionId -> !removedConnections.contains(connectionId)).toList(),
                    value.unknown()))
                .toList();
            return copyGraph(current, nodes, connections, passthroughs, current.variables(), current.functions());
        });
    }

    public synchronized CoreGraphEditorSession setNodes(List<GraphNode> nodes) {
        return mutateGraph(current -> copyGraph(current, nodes, current.connections(), current.variables(), current.functions()));
    }

    public synchronized CoreGraphEditorSession setConnections(List<GraphConnection> connections) {
        return mutateGraph(current -> copyGraph(current, current.nodes(), connections, current.variables(), current.functions()));
    }

    public synchronized CoreGraphEditorSession setPassthroughs(List<GraphPassthrough> passthroughs) {
        List<GraphPassthrough> values = passthroughs == null ? List.of() : List.copyOf(passthroughs);
        return mutateGraph(current -> copyGraph(current, current.nodes(), current.connections(), values,
            current.variables(), current.functions()));
    }

    public synchronized CoreGraphEditorSession togglePassthrough(NodeInstanceId nodeId, PinId inputPin) {
        Objects.requireNonNull(nodeId, "Passthrough node ID is required");
        Objects.requireNonNull(inputPin, "Passthrough input pin is required");
        return mutateGraph(current -> {
            ArrayList<GraphPassthrough> passthroughs = new ArrayList<>(current.passthroughs());
            boolean removed = passthroughs.removeIf(value -> value.nodeId().equals(nodeId) && value.inputPin().equals(inputPin));
            if (!removed) {
                passthroughs.add(new GraphPassthrough(nodeId, inputPin));
            }
            return copyGraph(current, current.nodes(), current.connections(), passthroughs,
                current.variables(), current.functions());
        });
    }

    public synchronized CoreGraphEditorSession setTopology(List<GraphNode> nodes, List<GraphConnection> connections) {
        return mutateGraph(current -> copyGraph(current, nodes, connections, current.variables(), current.functions()));
    }

    public synchronized CoreGraphEditorSession addConnection(GraphConnection connection) {
        Objects.requireNonNull(connection, "Graph connection is required");
        return mutateGraph(current -> {
            ArrayList<GraphConnection> connections = new ArrayList<>(current.connections());
            connections.add(connection);
            return copyGraph(current, current.nodes(), connections, current.variables(), current.functions());
        });
    }

    public synchronized CoreGraphEditorSession addConnection(GraphConnection connection, NodeInstanceId passthroughNode,
                                                              PinId passthroughPin) {
        Objects.requireNonNull(connection, "Graph connection is required");
        return mutateGraph(current -> {
            ArrayList<GraphConnection> connections = new ArrayList<>(current.connections());
            connections.add(connection);
            if (passthroughNode == null || passthroughPin == null) {
                return copyGraph(current, current.nodes(), connections, current.variables(), current.functions());
            }
            List<GraphPassthrough> passthroughs = new ArrayList<>();
            boolean matched = false;
            for (GraphPassthrough value : current.passthroughs()) {
                if (value.nodeId().equals(passthroughNode) && value.inputPin().equals(passthroughPin)) {
                    ArrayList<ConnectionId> connectionIds = new ArrayList<>(value.connectionIds());
                    connectionIds.add(connection.connectionId());
                    passthroughs.add(new GraphPassthrough(value.nodeId(), value.inputPin(), connectionIds, value.unknown()));
                    matched = true;
                } else {
                    passthroughs.add(value);
                }
            }
            if (!matched) {
                passthroughs.add(new GraphPassthrough(passthroughNode, passthroughPin, List.of(connection.connectionId()),
                    OpaqueData.empty()));
            }
            return copyGraph(current, current.nodes(), connections, passthroughs, current.variables(), current.functions());
        });
    }

    public synchronized CoreGraphEditorSession removeConnection(UUID connectionId) {
        Objects.requireNonNull(connectionId, "Connection ID is required");
        return mutateGraph(current -> {
            ArrayList<GraphConnection> connections = new ArrayList<>(current.connections());
            connections.removeIf(connection -> connection.connectionId().value().equals(connectionId));
            return copyGraph(current, current.nodes(), connections, current.variables(), current.functions());
        });
    }

    public synchronized CoreGraphEditorSession removeConnection(ConnectionId connectionId) {
        Objects.requireNonNull(connectionId, "Connection ID is required");
        return removeConnection(connectionId.value());
    }

    public synchronized CoreGraphEditorSession setVariables(List<GraphVariable> variables) {
        return mutateGraph(current -> copyGraph(current, current.nodes(), current.connections(), variables, current.functions()));
    }

    public synchronized CoreGraphEditorSession addVariable(GraphVariable variable) {
        Objects.requireNonNull(variable, "Graph variable is required");
        return mutateGraph(current -> {
            ArrayList<GraphVariable> variables = new ArrayList<>(current.variables());
            variables.add(variable);
            return copyGraph(current, current.nodes(), current.connections(), variables, current.functions());
        });
    }

    public synchronized CoreGraphEditorSession replaceVariable(GraphVariable variable) {
        Objects.requireNonNull(variable, "Graph variable is required");
        return mutateGraph(current -> {
            ArrayList<GraphVariable> variables = new ArrayList<>(current.variables());
            boolean replaced = false;
            for (int index = 0; index < variables.size(); index++) {
                if (variables.get(index).variableId().equals(variable.variableId())) {
                    variables.set(index, variable);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                throw new IllegalArgumentException("Graph variable does not exist: " + variable.variableId());
            }
            return copyGraph(current, current.nodes(), current.connections(), variables, current.functions());
        });
    }

    public synchronized CoreGraphEditorSession setVariableValue(UUID variableId, TypedValue value) {
        Objects.requireNonNull(variableId, "Variable ID is required");
        Objects.requireNonNull(value, "Variable typed value is required");
        GraphVariable variable = currentGraph().variables().stream().filter(candidate -> candidate.variableId().equals(variableId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Graph variable does not exist: " + variableId));
        return replaceVariable(new GraphVariable(variable.variableId(), variable.name(), variable.type(), value, variable.unknown()));
    }

    public synchronized CoreGraphEditorSession removeVariable(UUID variableId) {
        Objects.requireNonNull(variableId, "Variable ID is required");
        return mutateGraph(current -> {
            ArrayList<GraphVariable> variables = new ArrayList<>(current.variables());
            variables.removeIf(variable -> variable.variableId().equals(variableId));
            return copyGraph(current, current.nodes(), current.connections(), variables, current.functions());
        });
    }

    public synchronized CoreGraphEditorSession setFunctions(List<FunctionBinding> functions) {
        return mutateGraph(current -> copyGraph(current, current.nodes(), current.connections(), current.variables(), functions));
    }

    public synchronized CoreGraphEditorSession setFunctionBindings(List<FunctionBinding> functions) {
        return setFunctions(functions);
    }

    public synchronized CoreGraphEditorSession setFunctionBinding(FunctionBinding function) {
        return replaceFunction(function);
    }

    public synchronized CoreGraphEditorSession addFunction(FunctionBinding function) {
        Objects.requireNonNull(function, "Function binding is required");
        return mutateGraph(current -> {
            ArrayList<FunctionBinding> functions = new ArrayList<>(current.functions());
            functions.add(function);
            return copyGraph(current, current.nodes(), current.connections(), current.variables(), functions);
        });
    }

    public synchronized CoreGraphEditorSession replaceFunction(FunctionBinding replacement) {
        Objects.requireNonNull(replacement, "Function binding is required");
        return mutateGraph(current -> {
            ArrayList<FunctionBinding> functions = new ArrayList<>(current.functions());
            String identity = locatorIdentity(replacement.function());
            for (int index = 0; index < functions.size(); index++) {
                if (locatorIdentity(functions.get(index).function()).equals(identity)) {
                    functions.set(index, replacement);
                    return copyGraph(current, current.nodes(), current.connections(), current.variables(), functions);
                }
            }
            throw new IllegalArgumentException("Function binding does not exist: " + identity);
        });
    }

    public synchronized CoreGraphEditorSession removeFunction(ServerResourceLocator function) {
        Objects.requireNonNull(function, "Function locator is required");
        String identity = locatorIdentity(function);
        return mutateGraph(current -> {
            ArrayList<FunctionBinding> functions = new ArrayList<>(current.functions());
            functions.removeIf(binding -> locatorIdentity(binding.function()).equals(identity));
            return copyGraph(current, current.nodes(), current.connections(), current.variables(), functions);
        });
    }

    public synchronized CoreGraphEditorSession setFunctionSignature(FunctionSignature signature) {
        requireFunction();
        Objects.requireNonNull(signature, "Function signature is required");
        return commitSource(new FunctionSourceDocument(signature, source.graph(), source.unknown()));
    }

    public synchronized CoreGraphEditorSession setSignature(FunctionSignature signature) {
        return setFunctionSignature(signature);
    }

    public synchronized CoreGraphEditorSession setFunctionInputs(List<FunctionParameterContract> inputs) {
        requireFunction();
        FunctionSignature current = source.signature();
        return setFunctionSignature(new FunctionSignature(current.function(), current.revision(), inputs, current.outputs(), current.unknown()));
    }

    public synchronized CoreGraphEditorSession setFunctionOutputs(List<FunctionParameterContract> outputs) {
        requireFunction();
        FunctionSignature current = source.signature();
        return setFunctionSignature(new FunctionSignature(current.function(), current.revision(), current.inputs(), outputs, current.unknown()));
    }

    public synchronized CoreGraphEditorSession replaceFunctionSource(FunctionSourceDocument desired) {
        requireFunction();
        return commitSource(desired);
    }

    public synchronized CoreGraphEditorSession rebase(GraphDocument latest) {
        requireGraphKind();
        requireGraphIdentity(baselineGraph, latest);
        GraphDocument merged = CoreGraphWorkspacePatch.rebase(baselineGraph, graph, latest);
        baselineGraph = latest;
        baseRevision = latest.revision();
        graph = merged;
        resetHistory(new State(latest, null, 0L), new State(merged, null, 0L));
        return this;
    }

    public synchronized CoreGraphEditorSession rebase(FunctionSourceDocument latest) {
        requireFunction();
        requireSourceIdentity(baselineSource, latest);
        FunctionSourceDocument merged = CoreFunctionSourcePatch.rebase(baselineSource, source, latest);
        baselineSource = latest;
        baseRevision = latest.graph().revision();
        source = merged;
        resetHistory(new State(null, latest, 0L), new State(null, merged, 0L));
        return this;
    }

    public synchronized CoreGraphEditorSession rebasePublication(GraphDocument latest,
                                                                  ContentHash authoringChecksum,
                                                                  Collection<ContractRef<CapabilityId>> authoringCapabilities,
                                                                  Collection<ContractRef<CapabilityId>> editCapabilities) {
        requireGraphKind();
        GraphDocument merged = CoreGraphWorkspacePatch.rebase(baselineGraph, graph, latest);
        baselineGraph = requireGraphBaseline(latest);
        baseRevision = latest.revision();
        graph = requireGraphBaseline(merged);
        acceptPublication(authoringChecksum, authoringCapabilities, editCapabilities);
        resetHistory(new State(latest, null, 0L), new State(merged, null, 0L));
        return this;
    }

    public synchronized CoreGraphEditorSession rebasePublication(FunctionSourceDocument latest,
                                                                  ContentHash authoringChecksum,
                                                                  Collection<ContractRef<CapabilityId>> authoringCapabilities,
                                                                  Collection<ContractRef<CapabilityId>> editCapabilities) {
        requireFunction();
        FunctionSourceDocument merged = CoreFunctionSourcePatch.rebase(baselineSource, source, latest);
        baselineSource = requireSourceBaseline(latest);
        baseRevision = latest.graph().revision();
        source = requireSourceBaseline(merged);
        acceptPublication(authoringChecksum, authoringCapabilities, editCapabilities);
        resetHistory(new State(null, latest, 0L), new State(null, merged, 0L));
        return this;
    }

    private void acceptPublication(ContentHash authoringChecksum,
                                   Collection<ContractRef<CapabilityId>> authoringCapabilities,
                                   Collection<ContractRef<CapabilityId>> editCapabilities) {
        this.activeAuthoringChecksum = Objects.requireNonNull(authoringChecksum,
            "Active authoring checksum is required");
        this.activeAuthoringCapabilities = immutableCapabilities(authoringCapabilities);
        this.editCapabilities = immutableCapabilities(editCapabilities);
    }

    public synchronized CoreGraphEditorSession acceptAuthoritative(GraphDocument authoritative) {
        requireGraphKind();
        requireGraphIdentity(graph, authoritative);
        graph = requireGraphBaseline(authoritative);
        baselineGraph = graph;
        baseRevision = graph.revision();
        resetHistory();
        return this;
    }

    public synchronized CoreGraphEditorSession acceptAuthoritative(FunctionSourceDocument authoritative) {
        requireFunction();
        requireSourceIdentity(source, authoritative);
        source = requireSourceBaseline(authoritative);
        baselineSource = source;
        baseRevision = source.graph().revision();
        resetHistory();
        return this;
    }

    public synchronized CoreGraphEditorSession markSaved() {
        if (kind == Kind.GRAPH) {
            baselineGraph = graph;
            baseRevision = graph.revision();
        } else {
            baselineSource = source;
            baseRevision = source.graph().revision();
        }
        resetHistory();
        return this;
    }

    public synchronized CoreGraphEditorSession markSaved(GraphDocument authoritative) {
        return acceptAuthoritative(authoritative);
    }

    public synchronized CoreGraphEditorSession markSaved(FunctionSourceDocument authoritative) {
        return acceptAuthoritative(authoritative);
    }

    private CoreGraphEditorSession updateNode(NodeInstanceId nodeId, UnaryOperator<GraphNode> mutation) {
        Objects.requireNonNull(nodeId, "Node ID is required");
        Objects.requireNonNull(mutation, "Node mutation is required");
        return mutateGraph(current -> {
            ArrayList<GraphNode> nodes = new ArrayList<>(current.nodes());
            for (int index = 0; index < nodes.size(); index++) {
                GraphNode node = nodes.get(index);
                if (node.instanceId().equals(nodeId)) {
                    nodes.set(index, Objects.requireNonNull(mutation.apply(node), "Node mutation cannot return null"));
                    return copyGraph(current, nodes, current.connections(), current.variables(), current.functions());
                }
            }
            throw new IllegalArgumentException("Graph node does not exist: " + nodeId);
        });
    }

    private CoreGraphEditorSession commitGraph(GraphDocument desired) {
        Objects.requireNonNull(desired, "Desired graph is required");
        requireGraphIdentity(currentGraph(), desired);
        if (kind == Kind.GRAPH) {
            List<WorkspacePatch<JsonValue>> patches = CoreGraphWorkspacePatch.diff(graph, desired);
            if (patches.isEmpty()) {
                return this;
            }
            GraphDocument applied = CoreGraphWorkspacePatch.apply(graph, patches);
            if (!sameGraph(applied, desired)) {
                throw new IllegalArgumentException("Graph mutation was not lossless");
            }
            graph = applied;
            mutationVersion = Math.incrementExact(mutationVersion);
            pushHistory(new State(graph, null, mutationVersion));
            return this;
        }
        return commitSource(new FunctionSourceDocument(source.signature(), desired, source.unknown()));
    }

    private CoreGraphEditorSession commitSource(FunctionSourceDocument desired) {
        requireFunction();
        Objects.requireNonNull(desired, "Desired function source is required");
        requireSourceIdentity(source, desired);
        CoreFunctionSourcePatch.Patch patches = CoreFunctionSourcePatch.diff(source, desired);
        if (patches.isEmpty()) {
            return this;
        }
        FunctionSourceDocument applied = CoreFunctionSourcePatch.apply(source, patches);
        if (!sameSource(applied, desired)) {
            throw new IllegalArgumentException("Function source mutation was not lossless");
        }
        source = applied;
        mutationVersion = Math.incrementExact(mutationVersion);
        pushHistory(new State(null, source, mutationVersion));
        return this;
    }

    private void pushHistory(State next) {
        while (history.size() > historyCursor + 1) {
            history.removeLast();
        }
        history.add(next);
        historyCursor = history.size() - 1;
    }

    private void resetHistory() {
        baselineMutationVersion = mutationVersion;
        history.clear();
        history.add(currentState());
        historyCursor = 0;
    }

    private void resetHistory(State authoritative, State current) {
        long authoritativeVersion = Math.incrementExact(mutationVersion);
        baselineMutationVersion = authoritativeVersion;
        history.clear();
        history.add(new State(authoritative.graph(), authoritative.source(), authoritativeVersion));
        if (!samePayload(authoritative, current)) {
            mutationVersion = Math.incrementExact(authoritativeVersion);
            history.add(new State(current.graph(), current.source(), mutationVersion));
        } else {
            mutationVersion = authoritativeVersion;
        }
        historyCursor = history.size() - 1;
    }

    private void restore(State state) {
        if (kind == Kind.GRAPH) {
            graph = state.graph();
        } else {
            source = state.source();
        }
        mutationVersion = state.mutationVersion();
    }

    private State currentState() {
        return kind == Kind.GRAPH ? new State(graph, null, mutationVersion) : new State(null, source, mutationVersion);
    }

    private static boolean samePayload(State left, State right) {
        if (left.graph() != null || right.graph() != null) {
            if (left.graph() == null || right.graph() == null) {
                return false;
            }
            return GraphDocumentCodec.INSTANCE.encode(left.graph()).canonicalText()
                .equals(GraphDocumentCodec.INSTANCE.encode(right.graph()).canonicalText());
        }
        if (left.source() == null || right.source() == null) {
            return false;
        }
        return FunctionSourceDocumentCodec.INSTANCE.encode(left.source()).canonicalText()
            .equals(FunctionSourceDocumentCodec.INSTANCE.encode(right.source()).canonicalText());
    }

    private static boolean sameGraph(GraphDocument left, GraphDocument right) {
        return GraphDocumentCodec.INSTANCE.encode(left).canonicalText()
            .equals(GraphDocumentCodec.INSTANCE.encode(right).canonicalText());
    }

    private static boolean sameSource(FunctionSourceDocument left, FunctionSourceDocument right) {
        return FunctionSourceDocumentCodec.INSTANCE.encode(left).canonicalText()
            .equals(FunctionSourceDocumentCodec.INSTANCE.encode(right).canonicalText());
    }

    private GraphDocument currentGraph() {
        return kind == Kind.GRAPH ? graph : source.graph();
    }

    private void requireGraphKind() {
        if (kind != Kind.GRAPH) {
            throw new IllegalStateException("The editor session does not hold a graph payload");
        }
    }

    private void requireFunction() {
        if (kind != Kind.FUNCTION) {
            throw new IllegalStateException("The editor session does not hold a function source payload");
        }
    }

    private void requireCommand() {
        requireGraphKind();
        if (!"command".equals(graph.resource().resourceType().value())) {
            throw new IllegalStateException("The editor session does not hold a command resource");
        }
    }

    private static GraphDocument requireGraphBaseline(GraphDocument value) {
        GraphDocument baseline = Objects.requireNonNull(value, "Graph baseline is required");
        GraphDocumentCodec.INSTANCE.encode(baseline);
        return baseline;
    }

    private static FunctionSourceDocument requireSourceBaseline(FunctionSourceDocument value) {
        FunctionSourceDocument baseline = Objects.requireNonNull(value, "Function source baseline is required");
        FunctionSourceDocumentCodec.INSTANCE.encode(baseline);
        String graphResource = locatorIdentity(baseline.graph().resource());
        String functionResource = locatorIdentity(baseline.signature().function().resource());
        if (!graphResource.equals(functionResource)) {
            throw new IllegalArgumentException("Function source resource identity must match exactly");
        }
        return baseline;
    }

    private static GraphDocument requireBinding(GraphDocument value, CatalogBinding expectedBinding) {
        GraphDocument baseline = Objects.requireNonNull(value, "Graph baseline is required");
        if (!baseline.catalogBinding().equals(Objects.requireNonNull(expectedBinding, "Expected catalog binding is required"))) {
            throw new IllegalArgumentException("Graph baseline catalog binding does not match the expected binding");
        }
        return baseline;
    }

    private static FunctionSourceDocument requireBinding(FunctionSourceDocument value, CatalogBinding expectedBinding) {
        FunctionSourceDocument baseline = Objects.requireNonNull(value, "Function source baseline is required");
        if (!baseline.graph().catalogBinding().equals(Objects.requireNonNull(expectedBinding, "Expected catalog binding is required"))) {
            throw new IllegalArgumentException("Function source baseline catalog binding does not match the expected binding");
        }
        return baseline;
    }

    private static AuthoringTemplatePayload requirePayload(AuthoringTemplateResponse response) {
        return Objects.requireNonNull(response, "Authoring template response is required").payload();
    }

    private static ContentHash responseChecksum(AuthoringTemplateResponse response) {
        return Objects.requireNonNull(response, "Authoring template response is required").authoringPublicationChecksum();
    }

    private static Collection<ContractRef<CapabilityId>> responseCapabilities(AuthoringTemplateResponse response) {
        return Objects.requireNonNull(response, "Authoring template response is required").requiredCapabilities();
    }

    private static Collection<ContractRef<CapabilityId>> responseEditCapabilities(AuthoringTemplateResponse response) {
        return Objects.requireNonNull(response, "Authoring template response is required").editCapabilities();
    }

    private static void requireResponseIdentity(AuthoringTemplateResponse response, CoreGraphEditorSession session) {
        if (response == null) {
            throw new IllegalArgumentException("Authoring template response is required");
        }
        if (!locatorIdentity(response.resource()).equals(locatorIdentity(session.resource()))) {
            throw new IllegalArgumentException("Authoring template response resource identity does not match the session");
        }
        if (!response.catalogBinding().equals(session.catalogBinding())) {
            throw new IllegalArgumentException("Authoring template response binding does not match the session");
        }
        if (!response.templateChecksum().equals(session.checksum())) {
            throw new IllegalArgumentException("Authoring template response checksum does not match the session");
        }
    }

    private static void requireGraphIdentity(GraphDocument expected, GraphDocument actual) {
        if (!expected.schemaVersion().equals(actual.schemaVersion())) {
            throw new IllegalArgumentException("Graph schema identity cannot change in an editor session");
        }
        if (!locatorIdentity(expected.resource()).equals(locatorIdentity(actual.resource()))) {
            throw new IllegalArgumentException("Graph resource identity cannot change in an editor session");
        }
        if (!expected.catalogBinding().equals(actual.catalogBinding())) {
            throw new IllegalArgumentException("Graph catalog binding cannot change in an editor session");
        }
    }

    private static void requireSourceIdentity(FunctionSourceDocument expected, FunctionSourceDocument actual) {
        Objects.requireNonNull(actual, "Function source is required");
        requireGraphIdentity(expected.graph(), actual.graph());
        if (!locatorIdentity(expected.signature().function().resource())
            .equals(locatorIdentity(actual.signature().function().resource()))) {
            throw new IllegalArgumentException("Function source function identity cannot change in an editor session");
        }
    }

    private static GraphDocument copyGraph(GraphDocument source, List<GraphNode> nodes, List<GraphConnection> connections,
                                           List<GraphVariable> variables, List<FunctionBinding> functions) {
        return copyGraph(source, nodes, connections, source.passthroughs(), variables, functions);
    }

    private static GraphDocument copyGraph(GraphDocument source, List<GraphNode> nodes, List<GraphConnection> connections,
                                           List<GraphPassthrough> passthroughs, List<GraphVariable> variables,
                                           List<FunctionBinding> functions) {
        Set<NodeInstanceId> nodeIds = nodes.stream().map(GraphNode::instanceId).collect(Collectors.toSet());
        Set<ConnectionId> connectionIds = connections.stream().map(GraphConnection::connectionId).collect(Collectors.toSet());
        List<GraphPassthrough> normalizedPassthroughs = passthroughs.stream()
            .filter(value -> nodeIds.contains(value.nodeId()))
            .map(value -> new GraphPassthrough(value.nodeId(), value.inputPin(),
                value.connectionIds().stream().filter(connectionIds::contains).toList(), value.unknown()))
            .toList();
        return new GraphDocument(source.schemaVersion(), source.resource(), source.revision(), source.catalogBinding(),
            source.requiredCapabilities(), nodes, connections, normalizedPassthroughs, variables, functions, source.unknown());
    }

    private static GraphNode copyNode(GraphNode node, ModeId modeId, Map<PinId, PinValue> values,
                                      Map<InspectorFieldId, TypedValue> inspector, List<BranchBinding> branches,
                                      List<RepeatableBinding> repeatables, InspectorState inspectorState,
                                      double x, double y) {
        if (!node.inspector().isEmpty()) {
            throw new IllegalArgumentException("Legacy pin inspector values cannot be edited in a Core editor session");
        }
        return new GraphNode(node.instanceId(), node.definition(), node.definitionVersion(), modeId, values, inspector,
            branches, repeatables, inspectorState, x, y, node.unknown());
    }

    private static String locatorIdentity(ServerResourceLocator locator) {
        return IdentityCodec.encodeLocator(Objects.requireNonNull(locator, "Resource locator is required")).canonicalText();
    }

    private static AuthoringTemplatePayload.Kind graphTemplateKind(GraphDocument graph) {
        return switch (graph.resource().resourceType().value()) {
            case "flow" -> AuthoringTemplatePayload.Kind.FLOW;
            case "command" -> AuthoringTemplatePayload.Kind.COMMAND;
            default -> null;
        };
    }

    private static Set<ContractRef<CapabilityId>> immutableCapabilities(Collection<ContractRef<CapabilityId>> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(values.stream().map(value -> Objects.requireNonNull(value, "Authoring capability is required")).toList());
    }

    private static Map<String, Object> immutableUnknown(Map<String, ?> values) {
        return OpaqueData.of(values).fields();
    }

    public enum Kind {
        GRAPH,
        FUNCTION
    }

    public record HistoryState(int size, int cursor, int undoDepth, int redoDepth, boolean dirty) {
        public HistoryState {
            if (size < 1 || cursor < 0 || cursor >= size || undoDepth != cursor || redoDepth != size - cursor - 1) {
                throw new IllegalArgumentException("Invalid editor history state");
            }
        }
    }

    private record State(GraphDocument graph, FunctionSourceDocument source, long mutationVersion) {
    }
}
