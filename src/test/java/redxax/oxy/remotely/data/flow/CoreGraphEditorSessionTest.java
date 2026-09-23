package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.workspace.CoreFunctionSourcePatch;
import restudio.resync.flow.workspace.CoreWorkspaceDocument;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphPassthrough;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ModeId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreGraphEditorSessionTest {
    private static final ServerId SERVER = ServerId.deterministic("editor-session-test");
    private static final OwnerId OWNER = OwnerId.of("test");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));
    private static final CatalogBinding NEXT_BINDING = new CatalogBinding(2, "2".repeat(64), "3".repeat(64));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final ServerResourceLocator FLOW = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("flow")), "session");
    private static final ServerResourceLocator COMMAND = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("command")), "session");
    private static final ServerResourceLocator FUNCTION = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("function")), "session");

    @Test
    void publicationChecksFollowPermissionReplacementAndRejectChangedAuthority() {
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            CatalogAuthoringPublication.Section.TYPES, CatalogAuthoringPublication.Section.EDITORS,
            CatalogAuthoringPublication.Section.PREVIEWS, CatalogAuthoringPublication.Section.CAPABILITIES)
            .stream().map(section -> new CatalogAuthoringPublication.SectionProjection(section, true, true,
                CatalogCacheState.ACTIVE, List.of())).toList();
        CatalogAuthoringPublication publication = new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), sections, Set.of());
        ContentHash checksum = ContentHash.of("5".repeat(64));
        GraphDocument baseline = graph(1, node("main", 0, 0, OpaqueData.empty()), OpaqueData.empty());
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline, checksum, Set.of(), Set.of());
        ContractRef<CapabilityId> denied = ContractRef.of(OWNER, CapabilityId.of("not-advertised"));

        assertTrue(session.matchesPublication(publication, checksum));
        assertTrue(session.matchesPublication(publication, checksum));
        assertFalse(session.matchesPublication(publication, ContentHash.of("6".repeat(64))));
        session.rebasePublication(baseline, checksum, Set.of(denied), Set.of());
        assertFalse(session.matchesPublication(publication, checksum));
        assertFalse(session.matchesPublication(publication, checksum));
        session.rebasePublication(baseline, checksum, Set.of(), Set.of(denied));
        assertFalse(session.matchesPublication(publication, checksum));
        session.rebasePublication(baseline, checksum, Set.of(), Set.of());
        assertTrue(session.matchesPublication(publication, checksum));
        CatalogAuthoringPublication changed = new CatalogAuthoringPublication(NEXT_BINDING, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), sections, Set.of());
        assertFalse(session.matchesPublication(changed, checksum));
        assertTrue(session.matchesPublication(publication, checksum));
    }

    @Test
    void dirtyStateUsesMaintainedMutationVersionsWithoutEncoding() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/CoreGraphEditorSession.java"));
        int dirtyStart = source.indexOf("public synchronized boolean dirty()");
        int isDirtyStart = source.indexOf("public synchronized boolean isDirty()", dirtyStart);
        String dirty = source.substring(dirtyStart, isDirtyStart);

        assertTrue(dirty.contains("mutationVersion != baselineMutationVersion"));
        assertFalse(dirty.contains("Codec"));
        assertFalse(dirty.contains("canonical"));
    }

    @Test
    void passthroughToggleAndConnectionIdentitySurviveSessionMutations() {
        GraphNode source = node("source", 0, 0, OpaqueData.empty());
        GraphNode target = node("target", 20, 0, OpaqueData.empty());
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(0, source, OpaqueData.empty()));
        session.addNode(target, Set.of());
        PinId input = PinId.of("input");
        ConnectionId connectionId = ConnectionId.deterministic("passthrough-connection");
        GraphConnection connection = new GraphConnection(connectionId,
            new GraphEndpoint(source.instanceId(), PinId.of("output")),
            new GraphEndpoint(target.instanceId(), input));

        session.togglePassthrough(target.instanceId(), input)
            .addConnection(connection, target.instanceId(), input);

        GraphPassthrough passthrough = session.graphDocument().passthroughs().getFirst();
        assertEquals(target.instanceId(), passthrough.nodeId());
        assertEquals(input, passthrough.inputPin());
        assertEquals(List.of(connectionId), passthrough.connectionIds());
        session.removeConnection(connectionId);
        assertTrue(session.graphDocument().passthroughs().getFirst().connectionIds().isEmpty());
        session.togglePassthrough(target.instanceId(), input);
        assertTrue(session.graphDocument().passthroughs().isEmpty());
    }

    @Test
    void revisionZeroTemplateIsAValidLosslessSessionBaseline() {
        GraphDocument baseline = graph(0, node("main", 0, 0, OpaqueData.of(Map.of("nodeFuture", "keep"))),
            OpaqueData.of(Map.of("graphFuture", List.of("keep"))));
        CoreGraphEditorSession session = CoreGraphEditorSession.fromTemplate(new AuthoringTemplatePayload.Flow(baseline,
            Map.of("payloadFuture", true)), ContentHash.of("2".repeat(64)), Set.of());

        assertEquals(0, session.baseRevision());
        assertFalse(session.dirty());
        assertEquals(baseline.canonicalJson(), session.canonicalPayloadJson());
        assertTrue(session.canonicalTemplatePayload().canonicalText().contains("payloadFuture"));

        NodeInstanceId nodeId = NodeInstanceId.deterministic("node:main");
        session.setNodeModeId(nodeId, ModeId.of("compact"))
            .setNodeInspectorField(nodeId, InspectorFieldId.of("label"), TypedValue.value(STRING, "edited"))
            .setNodeValue(nodeId, PinId.of("text"), TypedValue.value(STRING, "edited"))
            .setNodeBranches(nodeId, List.of(branch()))
            .setNodeRepeatables(nodeId, List.of(repeatable()));

        assertTrue(session.dirty());
        assertTrue(session.canonicalPayloadJson().contains("nodeFuture"));
        assertTrue(session.canonicalPayloadJson().contains("graphFuture"));
        assertTrue(session.canUndo());
        assertTrue(session.undo());
        assertTrue(session.canRedo());
        assertTrue(session.redo());
        assertEquals(6, session.historyState().size());
    }

    @Test
    void contractOnlyResourcePayloadCannotOpenTheGraphOrFunctionEditor() {
        AuthoringTemplatePayload.Resource payload = new AuthoringTemplatePayload.Resource(FLOW, BINDING,
            TypeReference.of("test", "flow-document"), TypedValue.value(STRING, "value"), Set.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> CoreGraphEditorSession.fromTemplate(payload));

        assertTrue(failure.getMessage().contains("Resource authoring templates"));
    }

    @Test
    void keepsCatalogAuthoringAndEditorOperationCapabilitiesSeparate() {
        ContractRef<CapabilityId> catalogCapability = ContractRef.of(OWNER, CapabilityId.of("flow.event"));
        ContractRef<CapabilityId> editCapability = ContractRef.of(OWNER, CapabilityId.of("generic-editor"));
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(0, node("main", 0, 0, OpaqueData.empty()),
            OpaqueData.empty()), BINDING, ContentHash.of("2".repeat(64)), Set.of(catalogCapability), Set.of(editCapability));

        assertEquals(Set.of(catalogCapability), session.activeAuthoringCapabilities());
        assertEquals(Set.of(catalogCapability), session.authoringCapabilities());
        assertEquals(Set.of(editCapability), session.editCapabilities());
        assertNotEquals(session.authoringCapabilities(), session.editCapabilities());
        assertThrows(UnsupportedOperationException.class,
            () -> session.authoringCapabilities().add(editCapability));
        assertThrows(UnsupportedOperationException.class,
            () -> session.editCapabilities().add(catalogCapability));
    }

    @Test
    void authoringOnlyConvenienceConstructorLeavesEditorOperationsEmpty() {
        ContractRef<CapabilityId> catalogCapability = ContractRef.of(OWNER, CapabilityId.of("flow.event"));
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph(0,
            node("main", 0, 0, OpaqueData.empty()), OpaqueData.empty()), ContentHash.of("2".repeat(64)),
            Set.of(catalogCapability));

        assertEquals(Set.of(catalogCapability), session.authoringCapabilities());
        assertTrue(session.editCapabilities().isEmpty());
    }

    @Test
    void addingNodesExpandsGraphAndFunctionRequiredCapabilities() {
        ContractRef<CapabilityId> capability = ContractRef.of(OWNER, CapabilityId.of("player.cooldown"));
        GraphNode added = node("cooldown", 20, 40, OpaqueData.empty());
        CoreGraphEditorSession graphSession = new CoreGraphEditorSession(graph(0,
            node("main", 0, 0, OpaqueData.empty()), OpaqueData.empty()));
        CoreGraphEditorSession functionSession = new CoreGraphEditorSession(functionSource(0, List.of(), List.of(),
            OpaqueData.empty()));

        graphSession.addNode(added, Set.of(capability));
        functionSession.addNode(added, Set.of(capability));

        assertEquals(Set.of(capability), graphSession.requiredCapabilities());
        assertEquals(Set.of(capability), functionSession.requiredCapabilities());
        assertEquals(2, graphSession.graphDocument().nodes().size());
        assertEquals(1, functionSession.functionSourceDocument().graph().nodes().size());
        assertTrue(graphSession.dirty());
        assertTrue(functionSession.dirty());
    }

    @Test
    void graphRebaseUsesLatestAuthorityAsUndoBaseAndRestoresMergedDraft() {
        NodeInstanceId nodeId = NodeInstanceId.deterministic("node:main");
        GraphDocument baseline = graph(1, node("main", 0, 0, OpaqueData.of(Map.of("nodeFuture", "base"))),
            OpaqueData.of(Map.of("graphFuture", "base")));
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        session.setNodeModeId(nodeId, ModeId.of("local"));

        GraphDocument latest = graph(2, node("main", 12, 24, OpaqueData.of(Map.of("nodeFuture", "latest"))),
            OpaqueData.of(Map.of("graphFuture", "latest", "remoteFuture", true)));
        session.rebase(latest);
        String mergedJson = session.canonicalPayloadJson();

        assertEquals(2, session.baseRevision());
        assertEquals(BINDING, session.binding());
        assertTrue(session.dirty());
        assertEquals(2, session.historyState().size());
        assertEquals(1, session.historyState().cursor());
        assertEquals(2, session.revision());
        assertEquals(ModeId.of("local"), session.graphDocument().nodes().getFirst().modeId());
        assertEquals(12, session.graphDocument().nodes().getFirst().x());
        assertEquals(24, session.graphDocument().nodes().getFirst().y());
        assertTrue(mergedJson.contains("remoteFuture"));

        assertTrue(session.undo());
        assertFalse(session.dirty());
        assertEquals(latest.canonicalJson(), session.canonicalPayloadJson());
        assertEquals(2, session.revision());
        assertTrue(session.redo());
        assertTrue(session.dirty());
        assertEquals(mergedJson, session.canonicalPayloadJson());
    }

    @Test
    void graphPublicationRebasePreservesDirtyWorkspaceAndReplacesAuthoringAuthority() {
        ContractRef<CapabilityId> oldCapability = ContractRef.of(OWNER, CapabilityId.of("old"));
        ContractRef<CapabilityId> nextCapability = ContractRef.of(OWNER, CapabilityId.of("next"));
        ContractRef<CapabilityId> editCapability = ContractRef.of(OWNER, CapabilityId.of("generic-editor"));
        ContentHash nextChecksum = ContentHash.of("4".repeat(64));
        NodeInstanceId nodeId = NodeInstanceId.deterministic("node:main");
        GraphDocument baseline = graph(1, node("main", 0, 0, OpaqueData.empty()), OpaqueData.empty());
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline, BINDING,
            ContentHash.of("2".repeat(64)), Set.of(oldCapability), Set.of(editCapability));
        session.setNodeModeId(nodeId, ModeId.of("local"));
        GraphDocument latest = graph(2, NEXT_BINDING,
            node("main", 12, 24, OpaqueData.of(Map.of("remoteFuture", true))), OpaqueData.empty());

        session.rebasePublication(latest, nextChecksum, Set.of(nextCapability), Set.of(editCapability));

        assertEquals(NEXT_BINDING, session.catalogBinding());
        assertEquals(nextChecksum, session.authoringChecksum());
        assertEquals(Set.of(nextCapability), session.authoringCapabilities());
        assertEquals(Set.of(editCapability), session.editCapabilities());
        assertEquals(2, session.baselineRevision());
        assertEquals(ModeId.of("local"), session.graphDocument().nodes().getFirst().modeId());
        assertEquals(12, session.graphDocument().nodes().getFirst().x());
        assertTrue(session.canonicalPayloadJson().contains("remoteFuture"));
        assertTrue(session.isDirty());
        assertTrue(session.undo());
        assertFalse(session.isDirty());
        assertEquals(latest.canonicalJson(), session.canonicalPayloadJson());
    }

    @Test
    void graphMutationsRetainTypedResourceIdentityAndCoreUnknownData() {
        GraphNode node = node("main", 0, 0, OpaqueData.empty());
        GraphDocument baseline = graph(0, node, OpaqueData.empty());
        ServerResourceLocator item = new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of("item")), "exact");
        TypedValue locator = TypedValue.locator(TypeExpr.resource(TypeReference.of("test", "item")), item,
            Map.of("typedFuture", true));
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);

        session.setNodeValue(node.instanceId(), PinId.of("item"), locator)
            .addVariable(new GraphVariable(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "item", locator.type(), locator, OpaqueData.of(Map.of("variableFuture", true))));

        assertTrue(session.canonicalPayloadJson().contains(item.id()));
        assertTrue(session.canonicalPayloadJson().contains("typedFuture"));
        assertTrue(session.canonicalPayloadJson().contains("variableFuture"));
        assertEquals(item, session.graphDocument().variables().getFirst().value().locator());
    }

    @Test
    void commandMetadataUsesCanonicalGraphMutationLifecycle() {
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), COMMAND, 4, BINDING, Set.of(),
            List.of(node("main", 12, 24, OpaqueData.of(Map.of("nodeFuture", "keep")))), List.of(), List.of(), List.of(),
            OpaqueData.of(Map.of("commandLabel", "legacy", "structured", false, "commandPaths", List.of("legacy"),
                "graphFuture", Map.of("nested", List.of("keep")))));
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        ContentHash baselineChecksum = session.checksum();
        CommandGraphMetadata metadata = new CommandGraphMetadata("/Admin", true,
            List.of("admin reload", "admin reload", "admin stop"));
        GraphDocument expected = metadata.apply(baseline);

        assertEquals(new CommandGraphMetadata("legacy", false, List.of("legacy")), session.commandMetadata());

        session.setCommandMetadata(metadata);

        assertEquals(metadata, session.commandMetadata());
        assertEquals(expected.canonicalJson(), session.graphDocument().canonicalJson());
        assertTrue(session.dirty());
        assertNotEquals(baselineChecksum, session.checksum());
        assertEquals(2, session.historyState().size());
        assertEquals(1, session.historyState().cursor());

        ContentHash changedChecksum = session.checksum();
        session.setCommandMetadata(metadata);
        assertEquals(changedChecksum, session.checksum());
        assertEquals(2, session.historyState().size());

        assertTrue(session.undo());
        assertFalse(session.dirty());
        assertEquals(baselineChecksum, session.checksum());
        assertEquals(new CommandGraphMetadata("legacy", false, List.of("legacy")), session.commandMetadata());
        assertTrue(session.redo());
        assertEquals(expected.canonicalJson(), session.graphDocument().canonicalJson());
    }

    @Test
    void commandMetadataRejectsNonCommandResources() {
        CommandGraphMetadata metadata = new CommandGraphMetadata("admin", false, List.of());
        CoreGraphEditorSession flow = new CoreGraphEditorSession(graph(0,
            node("main", 0, 0, OpaqueData.empty()), OpaqueData.empty()));
        CoreGraphEditorSession function = new CoreGraphEditorSession(functionSource(0, List.of(), List.of(), OpaqueData.empty()));

        assertThrows(IllegalStateException.class, flow::commandMetadata);
        assertThrows(IllegalStateException.class, () -> flow.setCommandMetadata(metadata));
        assertThrows(IllegalStateException.class, function::commandMetadata);
        assertThrows(IllegalStateException.class, () -> function.setCommandMetadata(metadata));
    }

    @Test
    void functionSignaturePatchUsesStableParameterPathsAndRebases() {
        FunctionSourceDocument baseline = functionSource(0, List.of(parameter("input", true)), List.of(parameter("output", false)),
            OpaqueData.of(Map.of("graphFuture", true)));
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        FunctionParameterContract added = parameter("output-two", false);
        session.setFunctionOutputs(List.of(parameter("output", false), added));

        CoreFunctionSourcePatch.Patch patch = CoreFunctionSourcePatch.diff(baseline, session.functionSourceDocument());
        assertTrue(patch.signaturePatches().stream().anyMatch(value -> value.op().equals(CoreFunctionSourcePatch.ARRAY_ADD)
            && value.path().equals("/signature/outputs")));
        assertTrue(patch.signaturePatches().stream().noneMatch(value -> value.path().contains("/0")));
        assertTrue(session.canonicalPayloadJson().contains("graphFuture"));

        FunctionSourceDocument latest = functionSource(1, List.of(parameter("input", true)), List.of(parameter("output", false)),
            OpaqueData.of(Map.of("graphFuture", true, "remoteFuture", "preserve")));
        session.rebase(latest);
        String mergedJson = session.canonicalPayloadJson();
        assertEquals(1, session.baseRevision());
        assertEquals(BINDING, session.binding());
        assertTrue(session.dirty());
        assertEquals(2, session.historyState().size());
        assertEquals(1, session.historyState().cursor());
        assertEquals(1, session.revision());
        assertEquals(2, session.functionSourceDocument().signature().outputs().size());
        assertTrue(mergedJson.contains("remoteFuture"));
        assertTrue(mergedJson.contains("graphFuture"));

        assertTrue(session.undo());
        assertFalse(session.dirty());
        assertEquals(latest.canonicalJson(), session.canonicalPayloadJson());
        assertEquals(1, session.revision());
        assertTrue(session.redo());
        assertTrue(session.dirty());
        assertEquals(mergedJson, session.canonicalPayloadJson());
    }

    @Test
    void functionPublicationRebasePreservesSignatureEditsAcrossBindingAndRevisionChange() {
        FunctionSourceDocument baseline = functionSource(1, BINDING, List.of(parameter("input", true)),
            List.of(parameter("output", false)), OpaqueData.empty());
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline, BINDING,
            ContentHash.of("2".repeat(64)), Set.of(), Set.of());
        session.setFunctionOutputs(List.of(parameter("output", false), parameter("local-output", false)));
        FunctionSourceDocument latest = functionSource(4, NEXT_BINDING, List.of(parameter("input", true)),
            List.of(parameter("output", false)), OpaqueData.of(Map.of("remoteFuture", true)));
        ContentHash nextChecksum = ContentHash.of("4".repeat(64));

        session.rebasePublication(latest, nextChecksum, Set.of(), Set.of());

        assertEquals(NEXT_BINDING, session.catalogBinding());
        assertEquals(nextChecksum, session.authoringChecksum());
        assertEquals(4, session.baselineRevision());
        assertEquals(2, session.functionSourceDocument().signature().outputs().size());
        assertTrue(session.canonicalPayloadJson().contains("remoteFuture"));
        assertTrue(session.isDirty());
    }

    @Test
    void workspaceFunctionSignatureMergesPeerEditsWithoutChangingTheSavedBaseline() {
        FunctionSourceDocument baseline = functionSource(1, List.of(parameter("input", true)), List.of(), OpaqueData.empty());
        CoreGraphEditorSession local = new CoreGraphEditorSession(baseline);
        CoreGraphEditorSession remote = new CoreGraphEditorSession(baseline);
        local.setFunctionOutputs(List.of(parameter("result", false)));
        remote.setFunctionInputs(List.of(parameter("input", true), parameter("peer", false)));
        CoreWorkspaceDocument base = new CoreWorkspaceDocument(null, baseline);
        CoreWorkspaceDocument peer = new CoreWorkspaceDocument(null, remote.functionSourceDocument());
        CoreWorkspaceDocument roundTrip = base.apply(base.diff(peer));
        assertEquals(peer.encode(), roundTrip.encode());
        CoreGraphEditorSession.WorkspaceEdit edit = CoreGraphEditorSession.prepareWorkspaceEdit(
            new CoreWorkspaceDocument(null, local.functionSourceDocument()), base, roundTrip);

        assertTrue(local.applyWorkspaceEdit(edit));
        assertEquals(2, local.functionSourceDocument().signature().inputs().size());
        assertEquals(1, local.functionSourceDocument().signature().outputs().size());
        assertEquals(baseline, local.baselineFunctionSourceDocument());
        assertEquals(1, local.baselineRevision());
        assertTrue(local.dirty());
        assertFalse(local.applyWorkspaceEdit(edit));
    }

    @Test
    void preparedWorkspaceEditCannotOverwriteAnInterveningLocalEdit() {
        GraphNode node = node("main", 0, 0, OpaqueData.empty());
        GraphDocument baseline = graph(1, node, OpaqueData.empty());
        CoreGraphEditorSession local = new CoreGraphEditorSession(baseline);
        CoreGraphEditorSession remote = new CoreGraphEditorSession(baseline);
        remote.setNodePosition(node.instanceId(), 20, 30);
        CoreWorkspaceDocument base = new CoreWorkspaceDocument(baseline, null);
        CoreGraphEditorSession.WorkspaceEdit edit = CoreGraphEditorSession.prepareWorkspaceEdit(base, base,
            new CoreWorkspaceDocument(remote.graphDocument(), null));
        local.setNodePosition(node.instanceId(), 40, 50);
        String retained = local.canonicalPayloadJson();

        assertFalse(local.applyWorkspaceEdit(edit));
        assertEquals(retained, local.canonicalPayloadJson());
        assertThrows(IllegalStateException.class, () -> CoreGraphEditorSession.prepareWorkspaceEdit(
            new CoreWorkspaceDocument(local.graphDocument(), null), base,
            new CoreWorkspaceDocument(remote.graphDocument(), null)));
    }

    private static GraphDocument graph(long revision, GraphNode node, OpaqueData unknown) {
        return graph(revision, BINDING, node, unknown);
    }

    private static GraphDocument graph(long revision, CatalogBinding binding, GraphNode node, OpaqueData unknown) {
        return new GraphDocument(new CatalogVersion(1, 0), FLOW, revision, binding, Set.of(), List.of(node), List.of(),
            List.of(), List.of(), unknown);
    }

    private static GraphNode node(String name, double x, double y, OpaqueData unknown) {
        return new GraphNode(NodeInstanceId.deterministic("node:" + name),
            ContractRef.of(OWNER, NodeId.of("test-node")), 1, null, Map.of(), Map.of(), List.of(), List.of(),
            InspectorState.empty(), x, y, unknown);
    }

    private static BranchBinding branch() {
        BranchCase branchCase = new BranchCase(CaseId.of("enabled"), Map.of(), InspectorState.empty(),
            OpaqueData.of(Map.of("caseFuture", true)));
        return new BranchBinding(BranchId.of("state"), branchCase.caseId(), List.of(branchCase),
            OpaqueData.of(Map.of("branchFuture", true)));
    }

    private static RepeatableBinding repeatable() {
        RepeatableElement element = new RepeatableElement(RepeatableElementId.deterministic("element"), Map.of(),
            OpaqueData.of(Map.of("elementFuture", true)));
        return new RepeatableBinding(RepeatableGroupId.of("items"), true, List.of(element),
            OpaqueData.of(Map.of("repeatableFuture", true)));
    }

    private static FunctionSourceDocument functionSource(long revision, List<FunctionParameterContract> inputs,
                                                         List<FunctionParameterContract> outputs, OpaqueData unknown) {
        return functionSource(revision, BINDING, inputs, outputs, unknown);
    }

    private static FunctionSourceDocument functionSource(long revision, CatalogBinding binding,
                                                         List<FunctionParameterContract> inputs,
                                                         List<FunctionParameterContract> outputs, OpaqueData unknown) {
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), FUNCTION, revision, binding, Set.of(),
            List.of(), List.of(), List.of(), List.of(), unknown);
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(FUNCTION),
            new FunctionRevision(revision), inputs, outputs);
        return new FunctionSourceDocument(signature, graph);
    }

    private static FunctionParameterContract parameter(String name, boolean required) {
        return new FunctionParameterContract(FunctionParameterId.deterministic(name), STRING, required);
    }
}
