package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphEditorCoreClipboardActionTest {
    private static final ServerId SERVER = ServerId.deterministic("core-clipboard-test");
    private static final OwnerId OWNER = OwnerId.of("test");
    private static final CatalogBinding BINDING = new CatalogBinding(4, "a".repeat(64), "b".repeat(64));
    private static final ContentHash AUTHORING = ContentHash.of("c".repeat(64));
    private static final ContractRef<CapabilityId> TARGET_CAPABILITY = ContractRef.of(OWNER,
        CapabilityId.of("target-capability"));
    private static final ContractRef<CapabilityId> COPIED_CAPABILITY = ContractRef.of(OWNER,
        CapabilityId.of("copied-capability"));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));

    @Test
    void copyAndPastePreserveCorePayloadAndInternalEndpointData() {
        NodeInstanceId firstId = NodeInstanceId.deterministic("clipboard:first");
        NodeInstanceId secondId = NodeInstanceId.deterministic("clipboard:second");
        NodeInstanceId outsideId = NodeInstanceId.deterministic("clipboard:outside");
        GraphNode first = fullNode(firstId, 15, 25);
        GraphNode second = simpleNode(secondId, 45, 65);
        GraphNode outside = simpleNode(outsideId, 80, 90);
        RepeatableElementId elementId = RepeatableElementId.deterministic("clipboard:endpoint");
        BranchId branchId = BranchId.of("result");
        GraphConnection internal = new GraphConnection(ConnectionId.deterministic("clipboard:internal"),
            new GraphEndpoint(firstId, PinId.of("result"), elementId, branchId,
                OpaqueData.of(Map.of("sourceFuture", true))),
            new GraphEndpoint(secondId, PinId.of("value"), null, null,
                OpaqueData.of(Map.of("targetFuture", "keep"))),
            OpaqueData.of(Map.of("connectionFuture", 7)));
        GraphConnection external = new GraphConnection(ConnectionId.deterministic("clipboard:external"),
            new GraphEndpoint(secondId, PinId.of("result")), new GraphEndpoint(outsideId, PinId.of("value")));
        GraphDocument source = graph("source", Set.of(), List.of(first, second, outside), List.of(internal, external));
        CoreGraphEditorSession session = new CoreGraphEditorSession(source, BINDING, AUTHORING,
            Set.of(COPIED_CAPABILITY), Set.of());

        GraphEditorScreen.CoreClipboardData copied = GraphEditorScreen.captureCoreClipboard(session,
            SERVER.canonicalText(), List.of(firstId, secondId), Set.of(COPIED_CAPABILITY));

        assertEquals(List.of(first, second), copied.nodes());
        assertEquals(List.of(internal), copied.connections());
        NodeInstanceId anchorId = NodeInstanceId.deterministic("clipboard:anchor");
        GraphDocument target = graph("target", Set.of(TARGET_CAPABILITY), List.of(simpleNode(anchorId, 0, 0)), List.of());
        NodeInstanceId pastedFirstId = NodeInstanceId.deterministic("clipboard:pasted-first");
        NodeInstanceId pastedSecondId = NodeInstanceId.deterministic("clipboard:pasted-second");
        GraphEditorScreen.CorePasteResult result = GraphEditorScreen.pasteCoreClipboard(target, copied, 100, 200,
            List.of(pastedFirstId, pastedSecondId));

        assertEquals(List.of(pastedFirstId, pastedSecondId), result.selectedNodeIds());
        assertEquals(Set.of(TARGET_CAPABILITY, COPIED_CAPABILITY), result.document().requiredCapabilities());
        GraphNode pastedFirst = result.document().nodes().stream()
            .filter(node -> node.instanceId().equals(pastedFirstId)).findFirst().orElseThrow();
        assertNotEquals(first.instanceId(), pastedFirst.instanceId());
        assertEquals(first.definition(), pastedFirst.definition());
        assertEquals(first.definitionVersion(), pastedFirst.definitionVersion());
        assertEquals(first.modeId(), pastedFirst.modeId());
        assertEquals(first.values(), pastedFirst.values());
        assertEquals(first.inspectorFields(), pastedFirst.inspectorFields());
        assertEquals(first.branches(), pastedFirst.branches());
        assertEquals(first.repeatables(), pastedFirst.repeatables());
        assertEquals(first.inspectorState().unknown().fields(), pastedFirst.inspectorState().unknown().fields());
        assertEquals(first.unknown().fields(), pastedFirst.unknown().fields());
        assertEquals(100, pastedFirst.x());
        assertEquals(200, pastedFirst.y());
        GraphConnection pastedConnection = result.document().connections().getFirst();
        assertNotEquals(internal.connectionId(), pastedConnection.connectionId());
        assertEquals(pastedFirstId, pastedConnection.source().nodeId());
        assertEquals(pastedSecondId, pastedConnection.target().nodeId());
        assertEquals(elementId, pastedConnection.source().elementId());
        assertEquals(branchId, pastedConnection.source().branchId());
        assertEquals(internal.source().unknown().fields(), pastedConnection.source().unknown().fields());
        assertEquals(internal.target().unknown().fields(), pastedConnection.target().unknown().fields());
        assertEquals(internal.unknown().fields(), pastedConnection.unknown().fields());
    }

    @Test
    void pasteRejectsReusedOrDuplicateNodeIdentitiesBeforeConstructingTheGraph() {
        NodeInstanceId sourceId = NodeInstanceId.deterministic("clipboard:identity-source");
        GraphDocument source = graph("source-identities", Set.of(), List.of(simpleNode(sourceId, 0, 0)), List.of());
        CoreGraphEditorSession session = new CoreGraphEditorSession(source, BINDING, AUTHORING, Set.of(), Set.of());
        GraphEditorScreen.CoreClipboardData copied = GraphEditorScreen.captureCoreClipboard(session,
            SERVER.canonicalText(), List.of(sourceId), Set.of());
        NodeInstanceId existing = NodeInstanceId.deterministic("clipboard:identity-existing");
        GraphDocument target = graph("target-identities", Set.of(), List.of(simpleNode(existing, 0, 0)), List.of());

        assertThrows(IllegalArgumentException.class,
            () -> GraphEditorScreen.pasteCoreClipboard(target, copied, 0, 0, List.of(existing)));
        GraphEditorScreen.CoreClipboardData duplicated = new GraphEditorScreen.CoreClipboardData(copied.serverId(),
            copied.catalogBinding(), copied.authoringChecksum(), copied.requiredCapabilities(),
            List.of(copied.nodes().getFirst(), simpleNode(NodeInstanceId.deterministic("clipboard:duplicate-source"), 1, 1)),
            List.of());
        NodeInstanceId duplicate = NodeInstanceId.deterministic("clipboard:duplicate-target");
        assertThrows(IllegalArgumentException.class,
            () -> GraphEditorScreen.pasteCoreClipboard(target, duplicated, 0, 0, List.of(duplicate, duplicate)));
    }

    @Test
    void mixedSelectionAndIncompleteCaptureRejectWithoutAClipboardCandidate() {
        NodeInstanceId allowedId = NodeInstanceId.deterministic("clipboard:allowed");
        NodeInstanceId blockedId = NodeInstanceId.deterministic("clipboard:blocked");
        Map<String, NodeInstanceId> identities = Map.of("allowed", allowedId, "blocked", blockedId);

        assertEquals(List.of(), GraphEditorScreen.resolveCoreClipboardSelection(List.of("allowed", "blocked"),
            "allowed"::equals, identities::get));
        assertEquals(List.of(), GraphEditorScreen.resolveCoreClipboardSelection(List.of("allowed", "missing"),
            ignored -> true, identities::get));
        assertEquals(List.of(allowedId, blockedId), GraphEditorScreen.resolveCoreClipboardSelection(
            List.of("allowed", "blocked"), ignored -> true, identities::get));

        GraphDocument source = graph("incomplete", Set.of(), List.of(simpleNode(allowedId, 0, 0)), List.of());
        CoreGraphEditorSession session = new CoreGraphEditorSession(source, BINDING, AUTHORING, Set.of(), Set.of());
        assertNull(GraphEditorScreen.captureCoreClipboard(session, SERVER.canonicalText(),
            List.of(allowedId, blockedId), Set.of()));
    }

    @Test
    void rejectedFreshCaptureKeepsThePriorClipboardAndCannotGateAnAction() {
        NodeInstanceId priorId = NodeInstanceId.deterministic("clipboard:prior");
        GraphDocument source = graph("prior", Set.of(), List.of(simpleNode(priorId, 0, 0)), List.of());
        CoreGraphEditorSession session = new CoreGraphEditorSession(source, BINDING, AUTHORING, Set.of(), Set.of());
        GraphEditorScreen.CoreClipboardData prior = GraphEditorScreen.captureCoreClipboard(session,
            SERVER.canonicalText(), List.of(priorId), Set.of());
        GraphEditorScreen.CoreClipboardData candidate = GraphEditorScreen.captureCoreClipboard(session,
            SERVER.canonicalText(), List.of(priorId), Set.of(COPIED_CAPABILITY));

        GraphEditorScreen.CoreClipboardCapture rejected = GraphEditorScreen.acceptCoreClipboardCapture(prior,
            candidate, ignored -> false);

        assertFalse(rejected.accepted());
        assertSame(prior, rejected.clipboard());
    }

    @Test
    void pastedSelectionPublishesOnlyAfterTheCommittedProjection() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        int settlement = source.indexOf("private void settleCoreMutation");
        int committed = source.indexOf("if (committed && active == mutation.session())", settlement);
        int projection = source.indexOf("applyCoreProjection(active, projection);", committed);
        int selection = source.indexOf("applyCommittedCoreSelection(mutation);", committed);
        int rejected = source.indexOf("} else if (active == mutation.session())", committed);

        assertEquals(true, committed > settlement);
        assertEquals(true, projection > committed);
        assertEquals(true, selection > projection);
        assertEquals(true, selection < rejected);
    }

    private static GraphNode fullNode(NodeInstanceId id, double x, double y) {
        PinId valuePin = PinId.of("message");
        TypedValue value = TypedValue.value(STRING, "hello");
        InspectorState state = new InspectorState(InspectorState.State.DRAFT,
            Map.of(InspectorFieldId.of("draft-label"), value), InspectorState.Fallback.READ_ONLY_FIELD,
            UUID.nameUUIDFromBytes("clipboard:draft".getBytes(StandardCharsets.UTF_8)), 3L,
            OpaqueData.of(Map.of("stateFuture", "keep")));
        BranchCase branchCase = new BranchCase(CaseId.of("yes"), Map.of(), InspectorState.empty(),
            OpaqueData.of(Map.of("caseFuture", true)));
        BranchBinding branch = new BranchBinding(BranchId.of("choice"), branchCase.caseId(), List.of(branchCase),
            OpaqueData.of(Map.of("branchFuture", true)));
        RepeatableElement element = new RepeatableElement(RepeatableElementId.deterministic("clipboard:element"),
            Map.of(valuePin, new PinValue(valuePin, value)), OpaqueData.of(Map.of("elementFuture", true)));
        RepeatableBinding repeatable = new RepeatableBinding(RepeatableGroupId.of("items"), true, List.of(element),
            OpaqueData.of(Map.of("repeatableFuture", true)));
        return new GraphNode(id, ContractRef.of(OWNER, NodeId.of("full-node")), 3, ModeId.of("advanced"),
            Map.of(valuePin, new PinValue(valuePin, value, OpaqueData.of(Map.of("valueFuture", true)))),
            Map.of(InspectorFieldId.of("label"), value), List.of(branch), List.of(repeatable), state, x, y,
            OpaqueData.of(Map.of("nodeFuture", List.of("keep"))));
    }

    private static GraphNode simpleNode(NodeInstanceId id, double x, double y) {
        return new GraphNode(id, ContractRef.of(OWNER, NodeId.of("simple-node")), 1, null, Map.of(), Map.of(),
            List.of(), List.of(), InspectorState.empty(), x, y, OpaqueData.empty());
    }

    private static GraphDocument graph(String id, Set<ContractRef<CapabilityId>> requiredCapabilities,
                                       List<GraphNode> nodes, List<GraphConnection> connections) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of("flow")), id);
        return new GraphDocument(new CatalogVersion(1, 0), resource, 2, BINDING, requiredCapabilities, nodes,
            connections, List.of(), List.of(), OpaqueData.of(Map.of("graphFuture", id)));
    }
}
