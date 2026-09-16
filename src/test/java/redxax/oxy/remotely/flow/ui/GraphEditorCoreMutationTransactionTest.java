package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.CoreGraphUiProjection;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncCatalogAuthoringProjection;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationCache;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationProjection;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationReceiptHandler;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.ui.studio.StudioDocument;
import redxax.oxy.remotely.flow.ui.studio.StudioViewportState;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCoreMutationTransactionTest {
    private static final ServerId VALUE_SERVER = ServerId.deterministic("value-publication");
    private static final OwnerId VALUE_OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding VALUE_BINDING = new CatalogBinding(1, "8".repeat(64), "9".repeat(64));
    private static final ServerResourceLocator VALUE_RESOURCE = new ServerResourceLocator(VALUE_SERVER,
        ContractRef.of(VALUE_OWNER, ResourceTypeId.of("flow")), "value-publication");
    private static final ContractRef<CapabilityId> VALUE_EDITOR = ContractRef.of(VALUE_OWNER,
        CapabilityId.of("generic-editor"));
    private static final NodeInstanceId VALUE_NODE = NodeInstanceId.deterministic("value-node");
    private static final NodeInstanceId UNRELATED_NODE = NodeInstanceId.deterministic("unrelated-node");
    private static final PinId VALUE_PIN = PinId.of("message");
    private static final PinId VALUE_FLOW_IN = PinId.of("previous");
    private static final PinId VALUE_FLOW_OUT = PinId.of("next");

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void projectionFailureLeavesTheLiveSessionAndHistoryUntouched() {
        OwnerId owner = OwnerId.of("test");
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("projection-transaction"),
            ContractRef.of(owner, ResourceTypeId.of("command")), "command");
        CatalogBinding binding = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), resource, 4, binding, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        GraphNode added = new GraphNode(NodeInstanceId.deterministic("added"), ContractRef.of(owner, NodeId.of("node")),
            1, null, Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 20, 40, OpaqueData.empty());
        session.addNode(added);
        assertTrue(session.undo());
        String canonical = session.canonicalPayloadJson();
        CoreGraphEditorSession.HistoryState history = session.historyState();

        GraphEditorScreen.CoreMutationTransaction result = GraphEditorScreen.transactCoreMutation(session, current -> {
            current.addNode(added);
            return true;
        }, ignored -> null);

        assertTrue(result.changed());
        assertFalse(result.committed());
        assertEquals(canonical, session.canonicalPayloadJson());
        assertEquals(history, session.historyState());
        assertFalse(session.dirty());
        assertFalse(session.canUndo());
        assertTrue(session.canRedo());
    }

    @Test
    void incompleteProjectionLeavesTheLiveSessionAndHistoryUntouched() {
        OwnerId owner = OwnerId.of("test");
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("incomplete-transaction"),
            ContractRef.of(owner, ResourceTypeId.of("flow")), "flow");
        CatalogBinding binding = new CatalogBinding(1, "2".repeat(64), "3".repeat(64));
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), resource, 7, binding, Set.of(), List.of(),
            List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        GraphNode added = new GraphNode(NodeInstanceId.deterministic("incomplete-added"),
            ContractRef.of(owner, NodeId.of("node")), 1, null, Map.of(), Map.of(), List.of(), List.of(),
            InspectorState.empty(), 12, 18, OpaqueData.empty());
        String canonical = session.canonicalPayloadJson();
        CoreGraphEditorSession.HistoryState history = session.historyState();

        GraphEditorScreen.CoreMutationTransaction result = GraphEditorScreen.transactCoreMutation(session, current -> {
            current.addNode(added);
            return true;
        }, ignored -> new CoreGraphUiProjection.ProjectionResult(new FlowGraph(), 1, 0,
            List.of(added.instanceId().canonicalText()), 0, 0, List.of(), "4".repeat(64), "5".repeat(64), ""));

        assertTrue(result.changed());
        assertFalse(result.committed());
        assertEquals(canonical, session.canonicalPayloadJson());
        assertEquals(history, session.historyState());
        assertFalse(session.dirty());
        assertFalse(GraphEditorScreen.completeCoreProjection(new CoreGraphUiProjection.ProjectionResult(new FlowGraph(),
            1, 0, List.of(added.instanceId().canonicalText()), 0, 0, List.of(), "4".repeat(64), "5".repeat(64), "")));
    }

    @Test
    void moveCaptureAndPatchAdmissionStayBoundedToExactNodeIdentities() {
        GraphDocument document = valueDocument();
        int[] lookups = {0};
        Map<NodeInstanceId, double[]> positions = GraphEditorScreen.changedCoreNodePositions(document,
            List.of(VALUE_NODE.canonicalText()), nodeId -> {
                lookups[0]++;
                assertEquals(VALUE_NODE.canonicalText(), nodeId);
                return new double[]{245, 275};
            });

        assertEquals(1, lookups[0]);
        assertEquals(Set.of(VALUE_NODE), positions.keySet());
        assertEquals(245, positions.get(VALUE_NODE)[0]);
        assertEquals(275, positions.get(VALUE_NODE)[1]);

        int[] multiLookups = {0};
        Map<NodeInstanceId, double[]> multiPositions = GraphEditorScreen.changedCoreNodePositions(document,
            List.of(VALUE_NODE.canonicalText(), UNRELATED_NODE.canonicalText()), nodeId -> {
                multiLookups[0]++;
                return nodeId.equals(VALUE_NODE.canonicalText())
                    ? new double[]{245, 275} : new double[]{425, 315};
            });
        assertEquals(2, multiLookups[0]);
        assertEquals(Set.of(VALUE_NODE, UNRELATED_NODE), multiPositions.keySet());

        String valuePath = "/nodes/@" + VALUE_NODE.canonicalText() + "/position/";
        String unrelatedPath = "/nodes/@" + UNRELATED_NODE.canonicalText() + "/position/";
        List<WorkspacePatch<JsonValue>> exact = List.of(
            new WorkspacePatch<>("set", valuePath + "x", JsonValue.of(245)),
            new WorkspacePatch<>("set", unrelatedPath + "y", JsonValue.of(275)));
        assertTrue(GraphEditorScreen.exactCoreMovePatches(exact,
            List.of(VALUE_NODE.canonicalText(), UNRELATED_NODE.canonicalText())));
        assertFalse(GraphEditorScreen.exactCoreMovePatches(exact, List.of(VALUE_NODE.canonicalText())));
        assertFalse(GraphEditorScreen.exactCoreMovePatches(List.of(
            new WorkspacePatch<>("set", valuePath + "x", JsonValue.of(245))),
            List.of(VALUE_NODE.canonicalText(), UNRELATED_NODE.canonicalText())));
        assertFalse(GraphEditorScreen.exactCoreMovePatches(List.of(
            new WorkspacePatch<>("set", "/nodes/@" + VALUE_NODE.canonicalText() + "/values/message",
                JsonValue.of("changed"))), List.of(VALUE_NODE.canonicalText())));
        assertFalse(GraphEditorScreen.exactCoreMovePatches(List.of(
            new WorkspacePatch<>("remove", valuePath + "x", JsonValue.nullValue())),
            List.of(VALUE_NODE.canonicalText())));
    }

    @Test
    void batchDeletionCommitsOneHistoryEntryAndUndoRestoresEveryNodeAndEdge() {
        GraphDocument baseline = deletionDocument();
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        NodeInstanceId protectedNode = NodeInstanceId.deterministic("delete-protected");
        NodeInstanceId first = NodeInstanceId.deterministic("delete-first");
        NodeInstanceId second = NodeInstanceId.deterministic("delete-second");
        Map<String, NodeInstanceId> identities = Map.of(
            protectedNode.canonicalText(), protectedNode,
            first.canonicalText(), first,
            second.canonicalText(), second);
        List<NodeInstanceId> deletable = GraphEditorScreen.coreDeletionIdentities(
            List.of(second.canonicalText(), protectedNode.canonicalText(), first.canonicalText()),
            nodeId -> !nodeId.equals(protectedNode.canonicalText()), identities::get);

        GraphEditorScreen.CoreMutationTransaction result = GraphEditorScreen.transactCoreMutation(session,
            current -> GraphEditorScreen.removeCoreNodes(current, deletable),
            current -> new CoreGraphUiProjection().projectEditorSession(current));

        assertTrue(result.changed());
        assertTrue(result.committed());
        assertEquals(Set.of(first, second), Set.copyOf(deletable));
        assertEquals(List.of(protectedNode), session.graphDocument().nodes().stream().map(GraphNode::instanceId).toList());
        assertTrue(session.graphDocument().connections().isEmpty());
        assertEquals(2, session.historyState().size());
        assertEquals(1, session.historyState().cursor());

        Set<String> selected = new HashSet<>(identities.keySet());
        Set<String> selectionBase = new HashSet<>(identities.keySet());
        Map<String, int[]> dragStarts = new HashMap<>();
        identities.keySet().forEach(nodeId -> dragStarts.put(nodeId, new int[]{0, 0}));
        List<String> deletedNodeIds = deletable.stream().map(NodeInstanceId::canonicalText).toList();
        GraphEditorScreen.clearCommittedCoreDeletionSelection(deletedNodeIds, selected, selectionBase, dragStarts);
        GraphEditorScreen.clearCommittedCoreDeletionSelection(deletedNodeIds, selected, selectionBase, dragStarts);
        assertEquals(Set.of(protectedNode.canonicalText()), selected);
        assertEquals(Set.of(protectedNode.canonicalText()), selectionBase);
        assertEquals(Set.of(protectedNode.canonicalText()), dragStarts.keySet());

        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
        assertEquals(baseline.nodes(), session.graphDocument().nodes());
        assertEquals(baseline.connections(), session.graphDocument().connections());
        assertFalse(session.dirty());
    }

    @Test
    void failedBatchDeletionPreservesPayloadHistoryAndSelection() {
        GraphDocument baseline = deletionDocument();
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        NodeInstanceId first = NodeInstanceId.deterministic("delete-first");
        NodeInstanceId second = NodeInstanceId.deterministic("delete-second");
        Set<String> selected = new HashSet<>(List.of(first.canonicalText(), second.canonicalText()));
        Set<String> selectionBefore = Set.copyOf(selected);
        CoreGraphEditorSession.HistoryState history = session.historyState();

        GraphEditorScreen.CoreMutationTransaction result = GraphEditorScreen.transactCoreMutation(session,
            current -> GraphEditorScreen.removeCoreNodes(current, List.of(first, second)), ignored -> null);
        if (result.committed()) {
            GraphEditorScreen.clearCommittedCoreDeletionSelection(List.of(first.canonicalText(), second.canonicalText()),
                selected, new HashSet<>(), new HashMap<>());
        }

        assertTrue(result.changed());
        assertFalse(result.committed());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
        assertEquals(history, session.historyState());
        assertEquals(selectionBefore, selected);
        assertFalse(session.dirty());
    }

    @Test
    void admittedValueMutationCommitsWithoutChangingExistingConnections() {
        GraphDocument baseline = deletionDocument();
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        NodeInstanceId node = NodeInstanceId.deterministic("delete-first");
        PinId pin = PinId.of("mode");
        PinValue value = new PinValue(pin,
            TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), "on"));

        GraphEditorScreen.CoreMutationTransaction result = GraphEditorScreen.transactCoreMutation(session, current -> {
            current.setNodeValue(node, value);
            return true;
        }, current -> new CoreGraphUiProjection().projectEditorSession(current));

        assertTrue(result.changed());
        assertTrue(result.committed());
        assertEquals(baseline.connections(), session.graphDocument().connections());
        assertTrue(baseline.nodes().stream().filter(candidate -> candidate.instanceId().equals(node))
            .findFirst().orElseThrow().values().isEmpty());
        assertEquals(value, session.graphDocument().nodes().stream()
            .filter(candidate -> candidate.instanceId().equals(node)).findFirst().orElseThrow().values().get(pin));
    }

    @Test
    void optionalInputRemovalClearsValueAndIncomingEdgesAtomicallyAcrossRejectionUndoAndRedo() {
        GraphNode target = valueNode(VALUE_NODE, 120);
        GraphNode source = valueNode(UNRELATED_NODE, 320);
        GraphConnection incoming = new GraphConnection(ConnectionId.deterministic("optional-incoming"),
            new GraphEndpoint(UNRELATED_NODE, VALUE_FLOW_OUT), new GraphEndpoint(VALUE_NODE, VALUE_PIN));
        GraphConnection outgoing = new GraphConnection(ConnectionId.deterministic("optional-outgoing"),
            new GraphEndpoint(VALUE_NODE, VALUE_FLOW_OUT), new GraphEndpoint(UNRELATED_NODE, VALUE_FLOW_IN));
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(target, source), List.of(incoming, outgoing), List.of(), List.of(),
            OpaqueData.of(Map.of("future", Map.of("keep", true))));
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        CoreGraphEditorSession.HistoryState initialHistory = session.historyState();

        GraphEditorScreen.CoreMutationTransaction rejected = GraphEditorScreen.transactCoreMutation(session,
            current -> GraphEditorScreen.removeCoreOptionalInput(current, VALUE_NODE, VALUE_PIN), ignored -> null);

        assertTrue(rejected.changed());
        assertFalse(rejected.committed());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
        assertEquals(initialHistory, session.historyState());

        GraphEditorScreen.CoreMutationTransaction accepted = GraphEditorScreen.transactCoreMutation(session,
            current -> GraphEditorScreen.removeCoreOptionalInput(current, VALUE_NODE, VALUE_PIN),
            current -> new CoreGraphUiProjection().projectEditorSession(current));

        assertTrue(accepted.changed());
        assertTrue(accepted.committed());
        assertEquals(2, session.historyState().size());
        assertEquals(1, session.historyState().cursor());
        assertFalse(session.graphDocument().nodes().stream()
            .filter(node -> node.instanceId().equals(VALUE_NODE)).findFirst().orElseThrow().values().containsKey(VALUE_PIN));
        assertEquals(List.of(outgoing), session.graphDocument().connections());
        assertEquals(baseline.unknown(), session.graphDocument().unknown());

        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
        assertTrue(session.redo());
        assertFalse(session.graphDocument().nodes().stream()
            .filter(node -> node.instanceId().equals(VALUE_NODE)).findFirst().orElseThrow().values().containsKey(VALUE_PIN));
        assertEquals(List.of(outgoing), session.graphDocument().connections());
    }

    @Test
    void repeatableAddRemoveAndReorderPreserveStableIdentityConnectionsAndHistory() {
        PinId choice = PinId.of("choice");
        PinId matched = PinId.of("matched");
        RepeatableGroupId groupId = RepeatableGroupId.of("cases");
        RepeatableElementId first = RepeatableElementId.deterministic("mutation-first");
        RepeatableElementId second = RepeatableElementId.deterministic("mutation-second");
        RepeatableElement firstElement = new RepeatableElement(first, Map.of(choice,
            new PinValue(choice, TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), "first"))),
            OpaqueData.of(Map.of("futureElement", true)));
        RepeatableElement secondElement = new RepeatableElement(second, Map.of());
        GraphNode repeated = new GraphNode(VALUE_NODE, ContractRef.of(VALUE_OWNER, NodeId.of("repeatable")), 1,
            null, Map.of(), Map.of(), List.of(), List.of(new RepeatableBinding(groupId, true,
                List.of(firstElement, secondElement), OpaqueData.of(Map.of("futureBinding", true)))),
            InspectorState.empty(), 120, 80, OpaqueData.of(Map.of("futureNode", true)));
        GraphNode target = valueNode(UNRELATED_NODE, 320);
        GraphConnection connected = new GraphConnection(ConnectionId.deterministic("repeatable-connected"),
            new GraphEndpoint(VALUE_NODE, matched, first, null),
            new GraphEndpoint(UNRELATED_NODE, VALUE_FLOW_IN));
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(repeated, target), List.of(connected), List.of(), List.of(),
            OpaqueData.of(Map.of("futureDocument", true)));
        NodeDefinition definition = new NodeDefinition.Builder("repeatable", "Repeatable",
            NodeDefinition.NodeCategory.FLOW).owner(VALUE_OWNER.canonicalText())
            .input(new NodeDefinition.PinBuilder(choice, "Choice", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .repeatable("cases", 1, 4, "Case", true).build())
            .output(new NodeDefinition.PinBuilder(matched, "Matched", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
                .repeatable("cases", 1, 4, "Case", true).build())
            .build();
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        NodeWidget.RepeatableMutation remove = new NodeWidget.RepeatableMutation(VALUE_NODE, groupId, first,
            NodeWidget.RepeatableMutationKind.REMOVE);

        GraphEditorScreen.CoreMutationTransaction rejected = GraphEditorScreen.transactCoreMutation(session,
            current -> GraphEditorScreen.applyCoreRepeatableMutation(current, remove, definition), ignored -> null);
        assertTrue(rejected.changed());
        assertFalse(rejected.committed());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());

        assertTrue(GraphEditorScreen.applyCoreRepeatableMutation(session, remove, definition));
        assertEquals(List.of(second), session.graphDocument().nodes().getFirst().repeatables().getFirst().elements()
            .stream().map(RepeatableElement::elementId).toList());
        assertTrue(session.graphDocument().connections().isEmpty());
        assertEquals(Map.of("futureBinding", true),
            session.graphDocument().nodes().getFirst().repeatables().getFirst().unknown().fields());
        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());

        NodeWidget.RepeatableMutation move = new NodeWidget.RepeatableMutation(VALUE_NODE, groupId, second,
            NodeWidget.RepeatableMutationKind.MOVE_EARLIER);
        assertTrue(GraphEditorScreen.applyCoreRepeatableMutation(session, move, definition));
        assertEquals(List.of(second, first), session.graphDocument().nodes().getFirst().repeatables().getFirst().elements()
            .stream().map(RepeatableElement::elementId).toList());
        assertEquals(connected, session.graphDocument().connections().getFirst());
        assertTrue(session.undo());

        RepeatableElementId added = RepeatableElementId.deterministic("mutation-added");
        NodeWidget.RepeatableMutation add = new NodeWidget.RepeatableMutation(VALUE_NODE, groupId, added,
            NodeWidget.RepeatableMutationKind.ADD);
        assertTrue(GraphEditorScreen.applyCoreRepeatableMutation(session, add, definition));
        assertEquals(List.of(first, second, added), session.graphDocument().nodes().getFirst().repeatables().getFirst()
            .elements().stream().map(RepeatableElement::elementId).toList());
        assertEquals(connected, session.graphDocument().connections().getFirst());
        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
    }

    @Test
    void repeatableAddRepairsAMissingRequiredBindingInOneTransaction() {
        PinId choice = PinId.of("choice");
        RepeatableGroupId groupId = RepeatableGroupId.of("required-choices");
        GraphNode repeated = new GraphNode(VALUE_NODE, ContractRef.of(VALUE_OWNER, NodeId.of("repeatable")), 1,
            null, Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 120, 80, OpaqueData.empty());
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(repeated), List.of(), List.of(), List.of(), OpaqueData.empty());
        NodeDefinition definition = new NodeDefinition.Builder("repeatable", "Repeatable",
            NodeDefinition.NodeCategory.FLOW).owner(VALUE_OWNER.canonicalText())
            .input(new NodeDefinition.PinBuilder(choice, "Choice", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .repeatable("required-choices", 3, 5, "Choice", true).build())
            .build();
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        RepeatableElementId requested = RepeatableElementId.deterministic("required-requested");
        NodeWidget.RepeatableMutation add = new NodeWidget.RepeatableMutation(VALUE_NODE, groupId, requested,
            NodeWidget.RepeatableMutationKind.ADD);

        assertTrue(GraphEditorScreen.applyCoreRepeatableMutation(session, add, definition));

        List<RepeatableElement> elements = session.graphDocument().nodes().getFirst().repeatables().getFirst().elements();
        assertEquals(3, elements.size());
        assertEquals(requested, elements.getFirst().elementId());
        assertEquals(3, elements.stream().map(RepeatableElement::elementId).distinct().count());
        assertEquals(2, session.historyState().size());
        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
    }

    @Test
    void repeatableValueMutationChangesOnlyTheSelectedElementAndPreservesUnknownData() {
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        PinId choice = PinId.of("choice");
        RepeatableGroupId groupId = RepeatableGroupId.of("choices");
        RepeatableElementId first = RepeatableElementId.deterministic("value-first");
        RepeatableElementId second = RepeatableElementId.deterministic("value-second");
        PinValue firstValue = new PinValue(choice, TypedValue.value(string, "first"),
            OpaqueData.of(Map.of("firstValueFuture", true)));
        PinValue secondValue = new PinValue(choice, TypedValue.value(string, "second"),
            OpaqueData.of(Map.of("secondValueFuture", true)));
        RepeatableElement firstElement = new RepeatableElement(first, Map.of(choice, firstValue),
            OpaqueData.of(Map.of("firstElementFuture", true)));
        RepeatableElement secondElement = new RepeatableElement(second, Map.of(choice, secondValue),
            OpaqueData.of(Map.of("secondElementFuture", true)));
        GraphNode repeated = new GraphNode(VALUE_NODE, ContractRef.of(VALUE_OWNER, NodeId.of("repeatable")), 1,
            null, Map.of(), Map.of(), List.of(), List.of(new RepeatableBinding(groupId, true,
                List.of(firstElement, secondElement), OpaqueData.of(Map.of("bindingFuture", true)))),
            InspectorState.empty(), 120, 80, OpaqueData.of(Map.of("nodeFuture", true)));
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(repeated), List.of(), List.of(), List.of(),
            OpaqueData.of(Map.of("documentFuture", true)));
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        TypedValue replacement = TypedValue.value(string, "changed", Map.of("typedFuture", true));
        NodeWidget.NodeValueMutation mutation = new NodeWidget.NodeValueMutation(VALUE_NODE, choice, second,
            replacement);

        assertTrue(GraphEditorScreen.applyCoreRepeatableValue(session, mutation, groupId, replacement));

        List<RepeatableElement> elements = session.graphDocument().nodes().getFirst().repeatables().getFirst().elements();
        assertEquals(firstValue, elements.getFirst().values().get(choice));
        assertEquals(replacement, elements.get(1).values().get(choice).value());
        assertEquals(Map.of("secondValueFuture", true), elements.get(1).values().get(choice).unknown().fields());
        assertEquals(Map.of("secondElementFuture", true), elements.get(1).unknown().fields());
        assertEquals(baseline.unknown(), session.graphDocument().unknown());
        assertEquals(2, session.historyState().size());
        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
    }

    @Test
    void repeatableRemovalKeepsConnectionsForTheSameElementIdentityInAnotherGroup() {
        PinId firstOutput = PinId.of("first-output");
        PinId secondOutput = PinId.of("second-output");
        RepeatableGroupId firstGroup = RepeatableGroupId.of("first-group");
        RepeatableGroupId secondGroup = RepeatableGroupId.of("second-group");
        RepeatableElementId shared = RepeatableElementId.deterministic("shared-across-groups");
        GraphNode repeated = new GraphNode(VALUE_NODE, ContractRef.of(VALUE_OWNER, NodeId.of("repeatable")), 1,
            null, Map.of(), Map.of(), List.of(), List.of(
                new RepeatableBinding(firstGroup, true, List.of(new RepeatableElement(shared, Map.of()))),
                new RepeatableBinding(secondGroup, true, List.of(new RepeatableElement(shared, Map.of())))),
            InspectorState.empty(), 120, 80, OpaqueData.empty());
        GraphNode target = valueNode(UNRELATED_NODE, 320);
        GraphConnection removed = new GraphConnection(ConnectionId.deterministic("first-group-connection"),
            new GraphEndpoint(VALUE_NODE, firstOutput, shared, null),
            new GraphEndpoint(UNRELATED_NODE, PinId.of("first-target")));
        GraphConnection retained = new GraphConnection(ConnectionId.deterministic("second-group-connection"),
            new GraphEndpoint(VALUE_NODE, secondOutput, shared, null),
            new GraphEndpoint(UNRELATED_NODE, PinId.of("second-target")));
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(repeated, target), List.of(removed, retained), List.of(), List.of(),
            OpaqueData.empty());
        NodeDefinition definition = new NodeDefinition.Builder("repeatable", "Repeatable",
            NodeDefinition.NodeCategory.FLOW).owner(VALUE_OWNER.canonicalText())
            .output(new NodeDefinition.PinBuilder(firstOutput, "First", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
                .repeatable("first-group", 0, 2, "First", true).build())
            .output(new NodeDefinition.PinBuilder(secondOutput, "Second", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
                .repeatable("second-group", 0, 2, "Second", true).build())
            .build();
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        NodeWidget.RepeatableMutation mutation = new NodeWidget.RepeatableMutation(VALUE_NODE, firstGroup, shared,
            NodeWidget.RepeatableMutationKind.REMOVE);

        assertTrue(GraphEditorScreen.applyCoreRepeatableMutation(session, mutation, definition));

        assertEquals(List.of(retained), session.graphDocument().connections());
        assertEquals(List.of(secondGroup), session.graphDocument().nodes().getFirst().repeatables().stream()
            .filter(binding -> !binding.elements().isEmpty()).map(RepeatableBinding::groupId).toList());
        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
    }

    @Test
    void inspectorBatchUsesOneTransactionAndSurvivesUndoAndReopen() {
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        InspectorFieldId titleId = InspectorFieldId.of("content-title");
        InspectorFieldId legacyId = InspectorFieldId.of("legacy");
        InspectorFieldId addedId = InspectorFieldId.of("content-added");
        InspectorFieldId existingOptionId = InspectorFieldId.of("content-existing-option");
        InspectorFieldId addedOptionId = InspectorFieldId.of("content-added-option");
        Map<InspectorFieldId, TypedValue> inspector = Map.of(
            titleId, TypedValue.value(string, "Before", Map.of("titleFuture", "kept")),
            legacyId, TypedValue.nullValue(string, Map.of("legacyFuture", true)),
            existingOptionId, TypedValue.value(string, "Before Option", Map.of("oldOptionFuture", true)));
        GraphNode node = new GraphNode(VALUE_NODE, ContractRef.of(VALUE_OWNER, NodeId.of("value")), 1, null,
            Map.of(), inspector, List.of(), List.of(), InspectorState.empty(), 120, 180, OpaqueData.empty());
        GraphDocument baseline = new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(node), List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphEditorSession session = new CoreGraphEditorSession(baseline);
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        CoreGraphUiProjection.InspectorProjection initial = projection.projectInspector(session, VALUE_NODE).orElseThrow();
        CoreGraphUiProjection.InspectorValue title = initial.field(titleId).orElseThrow();
        CoreGraphUiProjection.InspectorValue legacy = initial.field(legacyId).orElseThrow();
        CoreGraphUiProjection.InspectorValue added = new CoreGraphUiProjection.InspectorValue(addedId,
            TypedValue.absent(string));
        CoreGraphUiProjection.InspectorValue existingOption = initial.field(existingOptionId).orElseThrow();
        CoreGraphUiProjection.InspectorValue addedOption = new CoreGraphUiProjection.InspectorValue(addedOptionId,
            TypedValue.absent(string));
        TypedValue selectedExisting = TypedValue.value(string, "Existing Option",
            Map.of("selectedOptionFuture", Map.of("keep", true)));
        TypedValue selectedAdded = TypedValue.value(string, "Added Option", Map.of("newOptionFuture", List.of("keep")));
        NodeWidget.InspectorMutation mutation = new NodeWidget.InspectorMutation(VALUE_NODE, List.of(
            new NodeWidget.InspectorFieldMutation(title, title.withValue("After"), false),
            new NodeWidget.InspectorFieldMutation(legacy, null, true),
            new NodeWidget.InspectorFieldMutation(added, added.withValue("Added"), false),
            new NodeWidget.InspectorFieldMutation(existingOption,
                new CoreGraphUiProjection.InspectorValue(existingOptionId, selectedExisting), false, true),
            new NodeWidget.InspectorFieldMutation(addedOption,
                new CoreGraphUiProjection.InspectorValue(addedOptionId, selectedAdded), false, true)));

        GraphEditorScreen.CoreMutationTransaction result = GraphEditorScreen.transactCoreMutation(session,
            current -> GraphEditorScreen.applyCoreInspectorMutation(current, mutation),
            current -> new CoreGraphUiProjection().projectEditorSession(current));

        assertTrue(result.changed());
        assertTrue(result.committed());
        assertEquals(2, session.historyState().size());
        assertEquals(1, session.historyState().cursor());
        CoreGraphUiProjection.InspectorProjection committed = projection.projectInspector(session, VALUE_NODE).orElseThrow();
        assertEquals("After", committed.field(titleId).orElseThrow().value());
        assertEquals(Map.of("titleFuture", "kept"), committed.field(titleId).orElseThrow().unknown());
        assertFalse(committed.contains(legacyId));
        assertEquals("Added", committed.field(addedId).orElseThrow().value());
        assertEquals(selectedExisting, committed.field(existingOptionId).orElseThrow().typedValue());
        assertEquals(selectedAdded, committed.field(addedOptionId).orElseThrow().typedValue());
        String saved = session.canonicalPayloadJson();
        CoreGraphEditorSession reopened = new CoreGraphEditorSession(GraphDocumentCodec.INSTANCE.decode(
            CanonicalCodec.decodePermissive(saved)));
        assertEquals(saved, reopened.canonicalPayloadJson());
        CoreGraphUiProjection.InspectorProjection reopenedInspector = projection.projectInspector(reopened, VALUE_NODE)
            .orElseThrow();
        assertEquals(committed, reopenedInspector);
        assertEquals(selectedExisting, reopenedInspector.field(existingOptionId).orElseThrow().typedValue());
        assertEquals(selectedAdded, reopenedInspector.field(addedOptionId).orElseThrow().typedValue());
        assertTrue(session.undo());
        assertEquals(baseline.canonicalJson(), session.graphDocument().canonicalJson());
    }

    @Test
    void productionValueAndStructuralQueuesPreserveUnaffectedWidgets(@TempDir Path tempDir)
        throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            ValueEditor editor = new ValueEditor(session, catalog.client());
            try {
                editor.materialize();
                FlowNodeWidget valueWidget = editor.widget(VALUE_NODE);
                FlowNodeWidget unrelatedWidget = editor.widget(UNRELATED_NODE);
                Object renderIndex = editor.renderIndex();
                List<?> connections = editor.projectedConnections();
                long projectionGeneration = editor.longField("coreProjectionGeneration");
                assertEquals(1, connections.size());
                int valueBuilds = editor.widgetBuilds(VALUE_NODE);
                int unrelatedBuilds = editor.widgetBuilds(UNRELATED_NODE);

                TextInputWidget field = editor.input(valueWidget, VALUE_PIN);
                field.setFocused(true);
                assertTrue(valueWidget.textInput(new ReTextInputEvent(valueWidget, field, 0L, ReModifierState.none(),
                    "x", 'x')));
                assertTrue(valueWidget.hasInputValuePreview(VALUE_PIN));
                assertTrue(valueWidget.textInput(new ReTextInputEvent(valueWidget, field, 1L, ReModifierState.none(),
                    "y", 'y')));
                assertEquals(1, editor.queuedMutationCount());
                String committedValue = field.getText();
                editor.awaitValue(committedValue);

                assertFalse(valueWidget.hasInputValuePreview(VALUE_PIN));
                assertEquals(projectionGeneration, editor.longField("coreProjectionGeneration"));
                assertSame(valueWidget, editor.widget(VALUE_NODE));
                assertSame(unrelatedWidget, editor.widget(UNRELATED_NODE));
                assertSame(connections, editor.projectedConnections());
                assertSame(renderIndex, editor.renderIndex());
                assertEquals(valueBuilds, editor.widgetBuilds(VALUE_NODE));
                assertEquals(unrelatedBuilds, editor.widgetBuilds(UNRELATED_NODE));

                manager.use(null);
                TextInputWidget rejected = editor.input(valueWidget, VALUE_PIN);
                rejected.setFocused(true);
                assertTrue(valueWidget.textInput(new ReTextInputEvent(valueWidget, rejected, 0L,
                    ReModifierState.none(), "z", 'z')));
                assertEquals(committedValue, editor.value());
                assertFalse(valueWidget.hasInputValuePreview(VALUE_PIN));
                manager.use(session);
                editor.awaitIdle();

                FlowNodeWidget beforeTopologyWidget = editor.widget(UNRELATED_NODE);
                FlowNode beforeTopologyNode = editor.projectedNode(UNRELATED_NODE);
                FlowGraph beforeTopologyGraph = editor.projectedGraph();
                Object beforeTopologyIndex = editor.renderIndex();
                int beforeTopologyBuilds = editor.widgetBuilds(UNRELATED_NODE);
                long beforeFastPaths = editor.longField("coreStructureFastPathCount");
                GraphNode added = valueNode(NodeInstanceId.deterministic("topology-added"), 460);
                assertTrue(editor.addNode(added));
                editor.awaitNodeCount(3);
                assertNotSame(beforeTopologyIndex, editor.renderIndex());
                assertSame(beforeTopologyGraph, editor.projectedGraph());
                assertSame(beforeTopologyNode, editor.projectedNode(UNRELATED_NODE));
                assertSame(beforeTopologyWidget, editor.widget(UNRELATED_NODE));
                assertEquals(beforeTopologyBuilds, editor.widgetBuilds(UNRELATED_NODE));
                assertEquals(1, editor.widgetBuilds(added.instanceId()));
                assertEquals(beforeFastPaths + 1, editor.longField("coreStructureFastPathCount"));
                assertEquals(1, editor.intField("coreStructureLastCreatedWidgetCount"));
                assertEquals(2, editor.intField("coreStructureLastReusedWidgetCount"));
                assertEquals(0, editor.intField("coreStructureLastRemovedWidgetCount"));
                assertEquals(3, editor.intField("coreStructureLastTopologyNodeCount"));
                assertEquals(1, editor.intField("coreStructureLastTopologyConnectionCount"));
                assertEquals(1, editor.intField("coreStructureLastRenderBuildScheduleCount"));

                FlowNodeWidget addedWidget = editor.widget(added.instanceId());
                Object beforeDeleteIndex = editor.renderIndex();
                long beforeDeleteFastPaths = editor.longField("coreStructureFastPathCount");
                assertTrue(editor.deleteNode(VALUE_NODE));
                editor.awaitNodeCount(2);
                assertNotSame(beforeDeleteIndex, editor.renderIndex());
                assertSame(beforeTopologyGraph, editor.projectedGraph());
                assertSame(beforeTopologyWidget, editor.widget(UNRELATED_NODE));
                assertSame(addedWidget, editor.widget(added.instanceId()));
                assertFalse(editor.graphContains(VALUE_NODE));
                editor.awaitCleanup(valueWidget);
                assertEquals(1, editor.cleanupCount(valueWidget));
                assertEquals(0, editor.cleanupCount(beforeTopologyWidget));
                assertEquals(0, editor.cleanupCount(addedWidget));
                assertEquals(beforeDeleteFastPaths + 1, editor.longField("coreStructureFastPathCount"));
                assertEquals(0, editor.intField("coreStructureLastCreatedWidgetCount"));
                assertEquals(2, editor.intField("coreStructureLastReusedWidgetCount"));
                assertEquals(1, editor.intField("coreStructureLastRemovedWidgetCount"));
                assertEquals(1, editor.intField("coreStructureLastRefreshedTargetCount"));
                assertEquals(2, editor.intField("coreStructureLastTopologyNodeCount"));
                assertEquals(0, editor.intField("coreStructureLastTopologyConnectionCount"));
                assertEquals(1, editor.intField("coreStructureLastRenderBuildScheduleCount"));

                NodeInstanceId pastedFirst = NodeInstanceId.deterministic("topology-pasted-first");
                NodeInstanceId pastedSecond = NodeInstanceId.deterministic("topology-pasted-second");
                GraphNode firstPastedNode = valueNode(pastedFirst, 620);
                GraphNode secondPastedNode = valueNode(pastedSecond, 780);
                GraphConnection pastedConnection = new GraphConnection(ConnectionId.deterministic("topology-pasted-edge"),
                    new GraphEndpoint(pastedFirst, VALUE_FLOW_OUT), new GraphEndpoint(pastedSecond, VALUE_FLOW_IN));
                long beforePasteFastPaths = editor.longField("coreStructureFastPathCount");
                assertTrue(editor.pasteNodes(List.of(firstPastedNode, secondPastedNode), List.of(pastedConnection)));
                editor.awaitNodeCount(4);
                assertSame(beforeTopologyGraph, editor.projectedGraph());
                assertSame(beforeTopologyWidget, editor.widget(UNRELATED_NODE));
                assertSame(addedWidget, editor.widget(added.instanceId()));
                assertEquals(1, editor.widgetBuilds(pastedFirst));
                assertEquals(1, editor.widgetBuilds(pastedSecond));
                assertEquals(beforePasteFastPaths + 1, editor.longField("coreStructureFastPathCount"));
                assertEquals(2, editor.intField("coreStructureLastCreatedWidgetCount"));
                assertEquals(2, editor.intField("coreStructureLastReusedWidgetCount"));
                assertEquals(0, editor.intField("coreStructureLastRemovedWidgetCount"));
                assertEquals(0, editor.intField("coreStructureLastRefreshedTargetCount"));
                assertEquals(4, editor.intField("coreStructureLastTopologyNodeCount"));
                assertEquals(1, editor.intField("coreStructureLastTopologyConnectionCount"));
                assertEquals(1, editor.intField("coreStructureLastRenderBuildScheduleCount"));
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void stablePublicationReprojectsRepeatableRowsAcrossMutationAndHistory(@TempDir Path tempDir) throws Exception {
        RepeatableGroupId groupId = RepeatableGroupId.of("messages");
        RepeatableElementId first = RepeatableElementId.deterministic("stable-repeatable-first");
        RepeatableElementId second = RepeatableElementId.deterministic("stable-repeatable-second");
        ValueCatalog catalog = valueCatalog(tempDir, repeatableValueDescriptor());
        CoreGraphEditorSession session = new CoreGraphEditorSession(repeatableValueDocument(groupId, first, second),
            catalog.authoringChecksum(), Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            ValueEditor editor = new ValueEditor(session, catalog.client());
            try {
                editor.materialize();
                FlowGraph projectedGraph = editor.projectedGraph();
                FlowNodeWidget valueWidget = editor.widget(VALUE_NODE);
                FlowNodeWidget unrelatedWidget = editor.widget(UNRELATED_NODE);
                int valueBuilds = editor.widgetBuilds(VALUE_NODE);
                int unrelatedBuilds = editor.widgetBuilds(UNRELATED_NODE);
                String firstView = valueWidget.coreViewPin(VALUE_PIN, first);
                String secondView = valueWidget.coreViewPin(VALUE_PIN, second);
                assertTrue(editor.repeatableRowsMatch(groupId, List.of(first, second)));

                assertTrue(editor.addRepeatable(VALUE_NODE, groupId));
                editor.awaitRepeatableOrder(groupId, List.of(first, second), 3);
                RepeatableElementId added = editor.repeatableOrder(groupId).get(2);
                String addedView = valueWidget.coreViewPin(VALUE_PIN, added);
                assertSame(projectedGraph, editor.projectedGraph());
                assertSame(valueWidget, editor.widget(VALUE_NODE));
                assertSame(unrelatedWidget, editor.widget(UNRELATED_NODE));
                assertEquals(valueBuilds, editor.widgetBuilds(VALUE_NODE));
                assertEquals(unrelatedBuilds, editor.widgetBuilds(UNRELATED_NODE));
                assertEquals(firstView, valueWidget.coreViewPin(VALUE_PIN, first));
                assertEquals(secondView, valueWidget.coreViewPin(VALUE_PIN, second));

                assertTrue(valueWidget.moveCoreRepeatablePin(secondView, true));
                editor.awaitRepeatableOrder(groupId, List.of(second, first, added), 3);
                assertSame(valueWidget, editor.widget(VALUE_NODE));
                assertEquals(List.of(secondView, firstView, addedView), editor.repeatableRowViews());

                assertTrue(valueWidget.removeCoreRepeatablePin(addedView));
                editor.awaitRepeatableOrder(groupId, List.of(second, first), 2);
                assertSame(valueWidget, editor.widget(VALUE_NODE));
                assertFalse(valueWidget.getVisibleInputPins().contains(addedView));

                assertTrue(editor.undoHistory());
                editor.awaitRepeatableOrder(groupId, List.of(second, first, added), 3);
                assertEquals(addedView, valueWidget.coreViewPin(VALUE_PIN, added));
                assertTrue(editor.redoHistory());
                editor.awaitRepeatableOrder(groupId, List.of(second, first), 2);
                assertFalse(valueWidget.getVisibleInputPins().contains(addedView));
                assertSame(projectedGraph, editor.projectedGraph());
                assertSame(valueWidget, editor.widget(VALUE_NODE));
                assertSame(unrelatedWidget, editor.widget(UNRELATED_NODE));
                assertEquals(valueBuilds, editor.widgetBuilds(VALUE_NODE));
                assertEquals(unrelatedBuilds, editor.widgetBuilds(UNRELATED_NODE));
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void structuralFastPathRequiresAtLeastOneExactOperationIdentityCollection() {
        Set<String> added = Set.of("first", "second");

        assertFalse(GraphEditorScreen.exactCoreStructureNodeIds(List.of(), List.of(), added));
        assertFalse(GraphEditorScreen.exactCoreStructureNodeIds(null, null, added));
        assertTrue(GraphEditorScreen.exactCoreStructureNodeIds(List.of("first", "second"), List.of(), added));
        assertTrue(GraphEditorScreen.exactCoreStructureNodeIds(List.of(), List.of("second", "first"), added));
        assertTrue(GraphEditorScreen.exactCoreStructureNodeIds(List.of("second", "first"),
            List.of("first", "second"), added));
        assertFalse(GraphEditorScreen.exactCoreStructureNodeIds(List.of("first"), List.of(), added));
        assertFalse(GraphEditorScreen.exactCoreStructureNodeIds(List.of("first", "first"), List.of(), added));
        assertFalse(GraphEditorScreen.exactCoreStructureNodeIds(List.of("first", "second"), List.of("first"), added));
    }

    @Test
    void mutationCoalescingIdentityMatchIsOrderIndependentAndRejectsDuplicates() {
        assertTrue(GraphEditorScreen.sameCoreMutationNodeIdentities(List.of("first", "second"),
            List.of("second", "first")));
        assertFalse(GraphEditorScreen.sameCoreMutationNodeIdentities(List.of("first"), List.of("second")));
        assertFalse(GraphEditorScreen.sameCoreMutationNodeIdentities(List.of("first", "first"), List.of("first")));
        assertFalse(GraphEditorScreen.sameCoreMutationNodeIdentities(List.of("first"), List.of("first", "first")));
        assertFalse(GraphEditorScreen.sameCoreMutationNodeIdentities(null, List.of("first")));
    }

    @Test
    void structuralRefreshDivergenceFallsBackWithoutPublishingTheStagedTopology(@TempDir Path tempDir)
        throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            DivergingRefreshEditor divergence = new DivergingRefreshEditor(session, catalog.client());
            ValueEditor editor = divergence;
            try {
                editor.materialize();
                FlowGraph originalGraph = editor.projectedGraph();
                FlowNodeWidget originalValueWidget = editor.widget(VALUE_NODE);
                FlowNodeWidget originalTargetWidget = editor.widget(UNRELATED_NODE);
                long fastPaths = editor.longField("coreStructureFastPathCount");
                long fallbacks = editor.longField("coreStructureFallbackCount");
                divergence.divergeNextTargetRefresh();

                assertTrue(editor.deleteNode(VALUE_NODE));
                editor.awaitNodeCount(1);

                assertNotSame(originalGraph, editor.projectedGraph());
                assertFalse(editor.graphContains(VALUE_NODE));
                assertEquals(0, editor.projectedConnections().size());
                assertNotSame(originalTargetWidget, editor.widget(UNRELATED_NODE));
                assertEquals(fastPaths, editor.longField("coreStructureFastPathCount"));
                assertEquals(fallbacks + 1, editor.longField("coreStructureFallbackCount"));
                assertEquals(0, editor.intField("coreStructureLastCreatedWidgetCount"));
                assertEquals(0, editor.intField("coreStructureLastReusedWidgetCount"));
                assertEquals(0, editor.intField("coreStructureLastRemovedWidgetCount"));
                assertEquals(0, editor.intField("coreStructureLastRefreshedTargetCount"));
                assertEquals(0, editor.intField("coreStructureLastTopologyNodeCount"));
                assertEquals(0, editor.intField("coreStructureLastTopologyConnectionCount"));
                assertEquals(0, editor.intField("coreStructureLastRenderBuildScheduleCount"));
                editor.awaitCleanup(originalValueWidget);
                editor.awaitCleanup(originalTargetWidget);
                assertEquals(1, editor.cleanupCount(originalValueWidget));
                assertEquals(1, editor.cleanupCount(originalTargetWidget));
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void wiresReuseWidgetsWhileGlobalUnknownAndMixedMutationsKeepTheGenericPublicationPath(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            ValueEditor editor = new ValueEditor(session, catalog.client());
            try {
                editor.materialize();
                FlowGraph beforeWireGraph = editor.projectedGraph();
                FlowNodeWidget beforeWireWidget = editor.widget(UNRELATED_NODE);
                long wireFallbacks = editor.longField("coreStructureFallbackCount");

                assertTrue(editor.deleteConnection(ConnectionId.deterministic("value-publication-edge")));
                editor.awaitConnectionCount(0);

                assertSame(beforeWireGraph, editor.projectedGraph());
                assertSame(beforeWireWidget, editor.widget(UNRELATED_NODE));
                assertEquals(wireFallbacks, editor.longField("coreStructureFallbackCount"));

                long unknownFastPaths = editor.longField("coreStructureFastPathCount");
                long unknownFallbacks = editor.longField("coreStructureFallbackCount");

                assertTrue(editor.replaceUnknown("preserved"));
                editor.awaitIdle();

                assertEquals("preserved", session.graphDocument().unknown().get("future"));
                assertEquals(unknownFastPaths, editor.longField("coreStructureFastPathCount"));
                assertEquals(unknownFallbacks, editor.longField("coreStructureFallbackCount"));

                FlowGraph beforeUnidentifiedGraph = editor.projectedGraph();
                FlowNodeWidget beforeUnidentifiedWidget = editor.widget(UNRELATED_NODE);
                long unidentifiedFastPaths = editor.longField("coreStructureFastPathCount");
                long unidentifiedFallbacks = editor.longField("coreStructureFallbackCount");
                GraphNode unidentified = valueNode(NodeInstanceId.deterministic("unidentified-added"), 540);

                assertTrue(editor.addNodeWithoutIdentity(unidentified));
                editor.awaitNodeCount(3);

                assertNotSame(beforeUnidentifiedGraph, editor.projectedGraph());
                assertNotSame(beforeUnidentifiedWidget, editor.widget(UNRELATED_NODE));
                assertEquals(unidentifiedFastPaths, editor.longField("coreStructureFastPathCount"));
                assertEquals(unidentifiedFallbacks + 1, editor.longField("coreStructureFallbackCount"));

                FlowGraph beforeMixedGraph = editor.projectedGraph();
                FlowNodeWidget beforeMixedWidget = editor.widget(UNRELATED_NODE);
                long mixedFallbacks = editor.longField("coreStructureFallbackCount");
                GraphNode added = valueNode(NodeInstanceId.deterministic("mixed-added"), 620);

                assertTrue(editor.addNodeAndMoveSurvivor(added, UNRELATED_NODE, 390, 180));
                editor.awaitNodeCount(4);

                assertNotSame(beforeMixedGraph, editor.projectedGraph());
                assertNotSame(beforeMixedWidget, editor.widget(UNRELATED_NODE));
                assertEquals(390D, editor.projectedNode(UNRELATED_NODE).getX());
                assertEquals(mixedFallbacks + 1, editor.longField("coreStructureFallbackCount"));
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void addedNodeAppearsBeforeProjectionAndKeepsItsWidgetAfterSettlement(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            FlowNodeWidget survivor = editor.widget(UNRELATED_NODE);
            GraphDocument baseline = session.graphDocument();
            CoreGraphEditorSession.HistoryState history = session.historyState();
            GraphNode added = valueNode(NodeInstanceId.deterministic("immediate-add"), 540);
            editor.pauseProjection();

            assertTrue(editor.addNode(added));

            FlowNodeWidget previewWidget = editor.widget(added.instanceId());
            assertTrue(previewWidget != null, String.valueOf(editor.field("coreStructurePreviewRejection")));
            assertTrue(editor.field("coreStructuralPreview") != null);
            assertEquals(3, editor.projectedGraph().getNodes().size());
            assertSame(baseline, session.graphDocument());
            assertEquals(history, session.historyState());
            assertSame(survivor, editor.widget(UNRELATED_NODE));
            assertTrue(editor.previewHit(540, 180).contains(previewWidget));

            editor.releaseProjection();
            editor.awaitNodeCount(3);

            assertSame(previewWidget, editor.widget(added.instanceId()));
            assertSame(survivor, editor.widget(UNRELATED_NODE));
            assertEquals(1, editor.widgetBuilds(added.instanceId()));
            assertEquals(history.size() + 1, session.historyState().size());
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void unrelatedCapabilityAdditionCannotPublishAnOptimisticNode(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            FlowGraph projected = editor.projectedGraph();
            FlowNodeWidget source = editor.widget(VALUE_NODE);
            FlowNodeWidget target = editor.widget(UNRELATED_NODE);
            List<?> connections = editor.projectedConnections();
            GraphDocument baseline = session.graphDocument();
            CoreGraphEditorSession.HistoryState history = session.historyState();
            GraphNode added = valueNode(NodeInstanceId.deterministic("invalid-capability-add"), 540);
            editor.pauseProjection();

            assertTrue(editor.addNodeWithCapability(added,
                ContractRef.of(VALUE_OWNER, CapabilityId.of("unrelated-preview-capability"))));

            assertEquals("capability_scope_changed:none", editor.field("coreStructurePreviewRejection"));
            assertTrue(editor.field("coreStructuralPreview") == null);
            assertSame(projected, editor.projectedGraph());
            assertEquals(2, projected.getNodes().size());
            assertTrue(editor.widget(added.instanceId()) == null);
            assertEquals(0, editor.widgetBuilds(added.instanceId()));
            assertSame(source, editor.widget(VALUE_NODE));
            assertSame(target, editor.widget(UNRELATED_NODE));
            assertSame(connections, editor.projectedConnections());
            assertSame(baseline, session.graphDocument());
            assertEquals(history, session.historyState());
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void functionParameterRemovalDeletesItsPrefixedBoundaryConnection() {
        ServerResourceLocator resource = new ServerResourceLocator(VALUE_SERVER,
            ContractRef.of(VALUE_OWNER, ResourceTypeId.of("function")), "parameter-removal");
        NodeInstanceId start = NodeInstanceId.deterministic("parameter-removal-start");
        NodeInstanceId middle = NodeInstanceId.deterministic("parameter-removal-middle");
        NodeInstanceId end = NodeInstanceId.deterministic("parameter-removal-end");
        FunctionParameterId input = FunctionParameterId.deterministic("parameter-removal-input");
        FunctionParameterId output = FunctionParameterId.deterministic("parameter-removal-output");
        TypeExpr booleanType = TypeExpr.named(TypeReference.of("builtin", "boolean"));
        GraphConnection inputConnection = new GraphConnection(ConnectionId.deterministic("parameter-removal-in"),
            new GraphEndpoint(start, PinId.of("function-output-" + input.canonicalText())),
            new GraphEndpoint(middle, PinId.of("target")));
        GraphConnection outputConnection = new GraphConnection(ConnectionId.deterministic("parameter-removal-out"),
            new GraphEndpoint(middle, PinId.of("result")),
            new GraphEndpoint(end, PinId.of("function-input-" + output.canonicalText())));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1L, VALUE_BINDING, Set.of(),
            List.of(
                new GraphNode(start, ContractRef.of(VALUE_OWNER, NodeId.of("function_start")), 1, Map.of()),
                new GraphNode(middle, ContractRef.of(VALUE_OWNER, NodeId.of("player_properties")), 1, Map.of()),
                new GraphNode(end, ContractRef.of(VALUE_OWNER, NodeId.of("function_end")), 1, Map.of())),
            List.of(inputConnection, outputConnection), List.of(), List.of(), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1L),
            List.of(new FunctionParameterContract(input, booleanType)),
            List.of(new FunctionParameterContract(output, booleanType)));
        CoreGraphEditorSession session = new CoreGraphEditorSession(new FunctionSourceDocument(signature, graph));

        assertTrue(GraphEditorScreen.removeCoreFunctionParameter(session, start, input.canonicalText(), true));
        assertTrue(session.functionSourceDocument().signature().inputs().isEmpty());
        assertEquals(List.of(outputConnection), session.functionSourceDocument().graph().connections());

        assertTrue(GraphEditorScreen.removeCoreFunctionParameter(session, end, output.canonicalText(), false));
        assertTrue(session.functionSourceDocument().signature().outputs().isEmpty());
        assertTrue(session.functionSourceDocument().graph().connections().isEmpty());
    }

    @Test
    void commandStudioAddUndoRedoAndWireHistoryKeepThePublishedGraph(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        GraphDocument source = valueDocument();
        GraphDocument command = new GraphDocument(source.schemaVersion(), new ServerResourceLocator(VALUE_SERVER,
            ContractRef.of(VALUE_OWNER, ResourceTypeId.of("command")), VALUE_RESOURCE.id()), source.revision(),
            source.catalogBinding(), source.requiredCapabilities(), source.nodes(), source.connections(),
            source.variables(), source.functions(), source.unknown());
        CoreGraphEditorSession session = new CoreGraphEditorSession(command, catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            FlowGraph graph = editor.projectedGraph();
            FlowNodeWidget survivor = editor.widget(VALUE_NODE);
            long fallbacks = editor.longField("coreStructureFallbackCount");
            editor.pauseProjection();
            NodeInstanceId added = editor.addCatalogNode();
            assertTrue(added != null);
            FlowNodeWidget first = editor.widget(added);
            assertTrue(first != null, String.valueOf(editor.field("coreStructurePreviewRejection")));
            editor.releaseProjection();
            editor.awaitNodeCount(3);
            assertSame(first, editor.widget(added));

            editor.pauseProjection();
            assertTrue(editor.undoActiveHistory());
            assertTrue(editor.widget(added) == null);
            assertEquals(3, session.graphDocument().nodes().size());
            editor.releaseProjection();
            editor.awaitNodeCount(2);

            editor.pauseProjection();
            assertTrue(editor.redoActiveHistory());
            FlowNodeWidget restored = editor.widget(added);
            assertTrue(restored != null, String.valueOf(editor.field("coreStructurePreviewRejection")));
            editor.releaseProjection();
            editor.awaitNodeCount(3);
            assertSame(restored, editor.widget(added));

            GraphConnection original = session.graphDocument().connections().getFirst();
            assertTrue(editor.deleteConnection(original.connectionId()));
            editor.awaitConnectionCount(0);
            editor.pauseProjection();
            assertTrue(editor.undoActiveHistory());
            assertEquals(1, editor.projectedConnections().size());
            editor.releaseProjection();
            editor.awaitConnectionCount(1);
            editor.pauseProjection();
            assertTrue(editor.redoActiveHistory());
            assertEquals(0, editor.projectedConnections().size());
            editor.releaseProjection();
            editor.awaitConnectionCount(0);

            assertSame(graph, editor.projectedGraph());
            assertSame(survivor, editor.widget(VALUE_NODE));
            assertSame(restored, editor.widget(added));
            assertEquals(1, editor.widgetBuilds(VALUE_NODE));
            assertEquals(1, editor.widgetBuilds(UNRELATED_NODE));
            assertEquals(2, editor.widgetBuilds(added));
            assertEquals(fallbacks, editor.longField("coreStructureFallbackCount"));
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void largeAuthoringPublicationIsValidatedOnceUntilItsIdentityChanges(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir, valueDescriptor(), 512);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            assertEquals(1, editor.longField("coreAuthoringValidationBuildCount"));
            GraphNode added = valueNode(NodeInstanceId.deterministic("authoring-cache-add"), 540);
            assertTrue(editor.addNode(added));
            editor.awaitNodeCount(3);
            assertTrue(editor.undoActiveHistory());
            editor.awaitNodeCount(2);
            assertTrue(editor.redoActiveHistory());
            editor.awaitNodeCount(3);
            for (int index = 0; index < 20; index++) {
                assertEquals("ready", editor.authoringDiagnostic());
            }
            assertEquals(1, editor.longField("coreAuthoringValidationBuildCount"));

            manager.use(new CoreGraphEditorSession(session.graphDocument(), catalog.authoringChecksum(),
                Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR)));
            assertTrue(editor.authoringDiagnostic().startsWith("editorSession=stale"));
            assertEquals(1, editor.longField("coreAuthoringValidationBuildCount"));
            manager.use(session);

            CatalogAuthoringPublication previous = catalog.client().activeCatalogAuthoringPublication().orElseThrow();
            CatalogAuthoringPublication replacement = new CatalogAuthoringPublication(previous.binding(), previous.contractVersion(),
                previous.projectionVersion(), previous.sections(), previous.advertisedEditCapabilities(), previous.unknown());
            replaceAuthoringPublication(catalog.client(), replacement);
            assertNotSame(previous, catalog.client().activeCatalogAuthoringPublication().orElseThrow());
            assertEquals("ready", editor.authoringDiagnostic());
            assertEquals(2, editor.longField("coreAuthoringValidationBuildCount"));
            assertEquals("ready", editor.authoringDiagnostic());
            assertEquals(2, editor.longField("coreAuthoringValidationBuildCount"));
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void completedOldRenderBuildCannotForceAddFallbackOrReplaceTheNewTopology(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            FlowGraph graph = editor.projectedGraph();
            FlowNodeWidget survivor = editor.widget(VALUE_NODE);
            long fallbacks = editor.longField("coreStructureFallbackCount");
            editor.pauseRenderWorker();
            editor.invoke("scheduleGraphRenderIndexBuild");
            editor.invoke("drainGraphRenderBuilds");
            Object oldResult = ((AtomicReference<?>) editor.field("graphRenderBuildResult")).get();
            assertTrue(oldResult != null);
            editor.pauseRenderWorker();
            editor.pauseProjection();
            GraphNode added = valueNode(NodeInstanceId.deterministic("old-render-add"), 540);
            assertTrue(editor.addNode(added));
            FlowNodeWidget preview = editor.widget(added.instanceId());
            assertTrue(preview != null);

            editor.releaseProjection();
            editor.invoke("applyCoreProjectionBuild");
            assertEquals(fallbacks, editor.longField("coreStructureFallbackCount"));
            assertSame(graph, editor.projectedGraph());
            assertSame(preview, editor.widget(added.instanceId()));
            editor.invoke("ensureGraphRenderIndex");
            editor.invoke("drainGraphRenderBuilds");
            editor.awaitNodeCount(3);
            Object currentIndex = editor.renderIndex();
            Method publish = GraphEditorScreen.class.getDeclaredMethod("publishGraphRenderBuildResult", oldResult.getClass());
            publish.setAccessible(true);
            publish.invoke(editor, oldResult);
            editor.invoke("ensureGraphRenderIndex");

            assertSame(currentIndex, editor.renderIndex());
            assertTrue(((AtomicReference<?>) editor.field("graphRenderBuildResult")).get() == null);
            assertTrue(editor.currentRenderIndex());
            assertSame(survivor, editor.widget(VALUE_NODE));
            assertSame(preview, editor.widget(added.instanceId()));
            assertTrue(editor.previewHit(540, 180).contains(preview));
            assertEquals(1, editor.widgetBuilds(added.instanceId()));
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void rejectedDeleteRestoresExactNodesConnectionsAndWidgets(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            FlowNodeWidget removed = editor.widget(VALUE_NODE);
            FlowNodeWidget survivor = editor.widget(UNRELATED_NODE);
            FlowNode removedNode = editor.projectedNode(VALUE_NODE);
            List<?> connections = editor.projectedConnections();
            GraphDocument baseline = session.graphDocument();
            CoreGraphEditorSession.HistoryState history = session.historyState();
            editor.pauseProjection();

            assertTrue(editor.deleteNode(VALUE_NODE));

            assertTrue(editor.widget(VALUE_NODE) == null, String.valueOf(editor.field("coreStructurePreviewRejection")));
            assertEquals(1, editor.projectedGraph().getNodes().size());
            assertEquals(0, editor.projectedConnections().size());
            assertSame(baseline, session.graphDocument());
            editor.invalidatePreparedCandidate();
            editor.releaseProjection();
            editor.pauseProjection();
            editor.invoke("applyCoreProjectionBuild");

            assertSame(baseline, session.graphDocument());
            assertEquals(history, session.historyState());
            assertSame(removedNode, editor.projectedNode(VALUE_NODE));
            assertSame(removed, editor.widget(VALUE_NODE));
            assertSame(survivor, editor.widget(UNRELATED_NODE));
            assertSame(connections, editor.projectedConnections());
            assertEquals(0, editor.cleanupCount(removed));
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void previewNodeCanMoveImmediatelyAndKeepsItsPositionAcrossParentSettlement(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            GraphNode added = valueNode(NodeInstanceId.deterministic("queued-drag"), 540);
            editor.pauseProjection();
            assertTrue(editor.addNode(added));
            FlowNodeWidget widget = editor.widget(added.instanceId());
            assertTrue(editor.canMovePreview(added.instanceId()), String.valueOf(editor.field("coreStructurePreviewRejection")));
            assertTrue(editor.canDeletePreview(added.instanceId()));
            assertTrue(editor.nodeAnimationWidth(widget) > widget.getWidth());

            editor.movePreviewNode(added.instanceId(), 720, 360);

            assertEquals(1, editor.queuedMutationCount());
            assertEquals(2, session.graphDocument().nodes().size());
            assertTrue(editor.previewHit(720, 360).contains(widget));
            assertFalse(editor.previewHit(540, 180).contains(widget));
            editor.releaseProjection();
            editor.pauseProjection();
            editor.invoke("applyCoreProjectionBuild");

            assertEquals(3, session.graphDocument().nodes().size());
            assertSame(widget, editor.widget(added.instanceId()));
            assertEquals(720, widget.getX());
            assertEquals(360, widget.getY());
            assertTrue(editor.previewHit(720, 360).contains(widget));
            editor.releaseProjection();
            editor.awaitPosition(added.instanceId(), 720, 360);
            assertSame(widget, editor.widget(added.instanceId()));
            assertEquals(1, editor.widgetBuilds(added.instanceId()));
            assertTrue(session.undo());
            assertEquals(540D, session.graphDocument().nodes().stream()
                .filter(node -> node.instanceId().equals(added.instanceId())).findFirst().orElseThrow().x());
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void rejectedPreviewNeverPersistsQueuedDragWireOrDelete(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            GraphDocument baseline = session.graphDocument();
            CoreGraphEditorSession.HistoryState history = session.historyState();
            GraphNode added = valueNode(NodeInstanceId.deterministic("rejected-followups"), 540);
            editor.pauseProjection();
            assertTrue(editor.addNode(added));
            editor.movePreviewNode(added.instanceId(), 720, 360);
            assertTrue(editor.connectPreviewNode(added.instanceId(), VALUE_NODE));
            editor.deletePreviewNode(added.instanceId());
            assertEquals(3, editor.queuedMutationCount());

            editor.invalidatePreparedCandidate();
            editor.releaseProjection();
            editor.pauseProjection();
            editor.invoke("applyCoreProjectionBuild");

            assertSame(baseline, session.graphDocument());
            assertEquals(history, session.historyState());
            assertEquals(0, editor.queuedMutationCount());
            assertEquals(2, editor.projectedGraph().getNodes().size());
            assertEquals(1, editor.projectedConnections().size());
            assertTrue(editor.widget(added.instanceId()) == null);
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void connectionEditsAppearBeforeProjectionWithoutRebuildingNodes(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        ValueEditor editor = new ValueEditor(session, catalog.client());
        try {
            editor.materialize();
            FlowNodeWidget source = editor.widget(VALUE_NODE);
            FlowNodeWidget target = editor.widget(UNRELATED_NODE);
            GraphConnection connection = session.graphDocument().connections().getFirst();
            editor.pauseProjection();

            assertTrue(editor.deleteConnection(connection.connectionId()));

            assertEquals(0, editor.projectedConnections().size());
            assertEquals(1, session.graphDocument().connections().size());
            assertTrue(editor.field("coreStructuralPreview") != null);
            editor.releaseProjection();
            editor.awaitConnectionCount(0);
            editor.pauseProjection();

            assertTrue(editor.addConnection(connection));

            assertEquals(1, editor.projectedConnections().size());
            assertEquals(0, session.graphDocument().connections().size());
            assertTrue(editor.field("coreStructuralPreview") != null);
            editor.releaseProjection();
            editor.awaitConnectionCount(1);
            assertSame(source, editor.widget(VALUE_NODE));
            assertSame(target, editor.widget(UNRELATED_NODE));
            assertEquals(1, editor.widgetBuilds(VALUE_NODE));
            assertEquals(1, editor.widgetBuilds(UNRELATED_NODE));
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void tabsReuseCurrentWidgetsAndRejectRetainedWidgetsAfterCatalogReplacement(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession first = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        GraphDocument source = valueDocument();
        GraphDocument other = new GraphDocument(source.schemaVersion(), new ServerResourceLocator(VALUE_SERVER,
            ContractRef.of(VALUE_OWNER, VALUE_RESOURCE.resourceType()), "second-tab"), source.revision(), source.catalogBinding(),
            source.requiredCapabilities(), source.nodes(), source.connections(), source.variables(), source.functions(), source.unknown());
        CoreGraphEditorSession second = new CoreGraphEditorSession(other, catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), first);
        ValueEditor editor = new ValueEditor(first, catalog.client());
        try {
            editor.materialize();
            FlowNodeWidget firstWidget = editor.widget(VALUE_NODE);
            FlowGraph firstGraph = editor.projectedGraph();
            Object firstIndex = editor.renderIndex();
            manager.use(second);
            editor.selectSession(second);

            assertEquals(1, editor.widgetBuilds(VALUE_NODE));
            assertEquals(2, firstGraph.getNodes().size());
            assertEquals(1, firstGraph.getConnections().size());
            editor.awaitIdle();
            FlowNodeWidget secondWidget = editor.widget(VALUE_NODE);
            assertNotSame(firstWidget, secondWidget);
            manager.use(first);
            editor.selectSession(first);

            assertSame(firstGraph, editor.projectedGraph());
            assertSame(firstIndex, editor.renderIndex());
            assertSame(firstWidget, editor.widget(VALUE_NODE));
            assertEquals(2, editor.widgetBuilds(VALUE_NODE));
            assertTrue(editor.field("catalogRefresh") == null);
            assertFalse((boolean) editor.field("nodeCatalogRefreshQueued"));
            manager.use(second);
            editor.selectSession(second);
            assertSame(secondWidget, editor.widget(VALUE_NODE));
            assertEquals(2, editor.widgetBuilds(VALUE_NODE));
            assertEquals(0, editor.cleanupCount(firstWidget));
            ReSyncCatalogPublicationProjection.Snapshot snapshot = catalog.client().catalogPublicationProjection().active().orElseThrow();
            ReSyncTypedInteractionProjection replacement = ReSyncTypedInteractionProjection.from(snapshot);
            Method publish = ReSyncFlowClient.class.getDeclaredMethod("publishTypedInteractionProjection",
                ReSyncCatalogPublicationProjection.Snapshot.class, ReSyncTypedInteractionProjection.class);
            publish.setAccessible(true);
            assertTrue((boolean) publish.invoke(catalog.client(), snapshot, replacement));
            manager.use(first);
            editor.selectSession(first);
            assertTrue(editor.widget(VALUE_NODE) == null);
            editor.awaitIdle();
            assertNotSame(firstWidget, editor.widget(VALUE_NODE));
            assertEquals(3, editor.widgetBuilds(VALUE_NODE));
            assertEquals(1, editor.cleanupCount(firstWidget));
        } finally {
            editor.removed();
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void productionMoveSettlementPreservesPublishedWidgetsAndRenderIndex(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            ValueEditor editor = new ValueEditor(session, catalog.client());
            try {
                editor.materialize();
                FlowGraph projectedGraph = editor.projectedGraph();
                FlowNodeWidget valueWidget = editor.widget(VALUE_NODE);
                FlowNodeWidget unrelatedWidget = editor.widget(UNRELATED_NODE);
                List<?> connections = editor.projectedConnections();
                Object renderIndex = editor.renderIndex();
                long projectionGeneration = editor.longField("coreProjectionGeneration");
                int valueBuilds = editor.widgetBuilds(VALUE_NODE);
                int unrelatedBuilds = editor.widgetBuilds(UNRELATED_NODE);
                CoreGraphEditorSession.HistoryState history = session.historyState();

                assertTrue(editor.move(VALUE_NODE, 245, 275));
                editor.awaitPosition(VALUE_NODE, 245, 275);

                assertSame(projectedGraph, editor.projectedGraph());
                assertSame(valueWidget, editor.widget(VALUE_NODE));
                assertSame(unrelatedWidget, editor.widget(UNRELATED_NODE));
                assertSame(connections, editor.projectedConnections());
                assertSame(renderIndex, editor.renderIndex());
                assertEquals(projectionGeneration, editor.longField("coreProjectionGeneration"));
                assertEquals(session.graphDocument().checksum().canonicalText(),
                    editor.field("coreProjectionChecksum"));
                assertTrue(editor.isCoreEditorReady());
                assertEquals(valueBuilds, editor.widgetBuilds(VALUE_NODE));
                assertEquals(unrelatedBuilds, editor.widgetBuilds(UNRELATED_NODE));
                assertEquals(history.size() + 1, session.historyState().size());
                assertEquals(history.cursor() + 1, session.historyState().cursor());
                assertEquals(245, valueWidget.getX());
                assertEquals(275, valueWidget.getY());
                String saved = session.canonicalPayloadJson();

                assertTrue(session.undo());
                assertEquals(120, session.graphDocument().nodes().stream()
                    .filter(node -> node.instanceId().equals(VALUE_NODE)).findFirst().orElseThrow().x());
                assertTrue(session.redo());
                assertEquals(saved, session.canonicalPayloadJson());
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void queuedMovesCoalesceOnlyTheSameIdentitySetAndDeferredSaveSeesEveryDistinctSelection(@TempDir Path tempDir)
        throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            ValueEditor editor = new ValueEditor(session, catalog.client());
            try {
                editor.materialize();
                CoreGraphEditorSession.HistoryState history = session.historyState();
                AtomicInteger saves = new AtomicInteger();
                AtomicReference<String> savedPayload = new AtomicReference<>();
                editor.setCoreGraphSaveHandler((current, ticket) -> {
                    saves.incrementAndGet();
                    savedPayload.set(current.canonicalPayloadJson());
                    return true;
                });

                assertTrue(editor.moveSelection(Map.of(
                    VALUE_NODE, new double[]{180, 210},
                    UNRELATED_NODE, new double[]{380, 210})));
                assertTrue(editor.moveSelection(Map.of(VALUE_NODE, new double[]{240, 275})));
                assertTrue(editor.moveSelection(Map.of(UNRELATED_NODE, new double[]{440, 310})));
                assertTrue(editor.moveSelection(Map.of(UNRELATED_NODE, new double[]{480, 330})));
                assertEquals(2, editor.queuedMutationCount());
                editor.saveEditor();

                editor.awaitPosition(VALUE_NODE, 240, 275);
                editor.awaitPosition(UNRELATED_NODE, 480, 330);

                assertEquals(history.size() + 3, session.historyState().size());
                assertEquals(history.cursor() + 3, session.historyState().cursor());
                assertEquals(1, saves.get());
                assertEquals(session.canonicalPayloadJson(), savedPayload.get());
                assertTrue(session.undo());
                assertEquals(380D, session.graphDocument().nodes().stream()
                    .filter(node -> node.instanceId().equals(UNRELATED_NODE)).findFirst().orElseThrow().x());
                assertTrue(session.undo());
                assertEquals(180D, session.graphDocument().nodes().stream()
                    .filter(node -> node.instanceId().equals(VALUE_NODE)).findFirst().orElseThrow().x());
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void valueAndMoveOverlapRestartCatalogPreparationWithoutAdoptingPreparedWidgets(@TempDir Path tempDir)
        throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            RefreshOverlapEditor overlap = new RefreshOverlapEditor(session, catalog.client());
            ValueEditor editor = overlap;
            try {
                editor.materialize();
                List<FlowNodeWidget> original = List.copyOf(editor.widgetCache.values());
                overlap.holdCatalogPreparation();
                editor.refreshNodeRegistry();
                assertFalse(editor.canRefreshStable());
                editor.tick();
                List<FlowNodeWidget> prepared = editor.preparingWidgets();
                assertEquals(1, prepared.size());
                assertFalse(editor.canRefreshStable());

                FlowNodeWidget valueWidget = editor.widget(VALUE_NODE);
                TextInputWidget field = editor.input(valueWidget, VALUE_PIN);
                field.setFocused(true);
                assertTrue(valueWidget.textInput(new ReTextInputEvent(valueWidget, field, 0L,
                    ReModifierState.none(), "q", 'q')));
                String expectedValue = field.getText();
                editor.awaitProjectionResult();
                editor.tick();
                assertFalse(editor.canRefreshStable());
                assertEquals(expectedValue, editor.value());
                editor.tick();
                List<FlowNodeWidget> valuePrepared = editor.preparingWidgets();
                assertEquals(1, valuePrepared.size());

                assertTrue(editor.move(VALUE_NODE, 265, 295));
                editor.awaitProjectionResult();
                editor.tick();
                assertFalse(editor.canRefreshStable());
                overlap.releaseCatalogPreparation();

                editor.awaitValue(expectedValue);
                editor.awaitPosition(VALUE_NODE, 265, 295);
                List<FlowNodeWidget> stalePrepared = new ArrayList<>(prepared);
                stalePrepared.addAll(valuePrepared);
                stalePrepared.forEach(widget -> assertFalse(editor.widgetCache.containsValue(widget)));
                for (FlowNodeWidget widget : stalePrepared) {
                    editor.awaitCleanup(widget);
                    assertEquals(1, editor.cleanupCount(widget));
                }
                for (FlowNodeWidget widget : original) {
                    editor.awaitCleanup(widget);
                    assertEquals(1, editor.cleanupCount(widget));
                }
                editor.widgetCache.values().forEach(widget -> assertEquals(0, editor.cleanupCount(widget)));
                assertTrue(editor.retirementPayloadsEmpty());
                assertEquals(0, editor.retiredWidgetCount());
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    @Test
    void structuralStagingFailureRetiresOnlyStagedWidgetsBeforeFullFallback(@TempDir Path tempDir) throws Exception {
        ValueCatalog catalog = valueCatalog(tempDir);
        CoreGraphEditorSession session = new CoreGraphEditorSession(valueDocument(), catalog.authoringChecksum(),
            Set.of(VALUE_EDITOR), Set.of(VALUE_EDITOR));
        ValueManager manager = new ValueManager(catalog.client(), session);
        try {
            StagingFailureEditor failureEditor = new StagingFailureEditor(session, catalog.client());
            ValueEditor editor = failureEditor;
            try {
                editor.materialize();
                FlowGraph originalGraph = editor.projectedGraph();
                FlowNodeWidget originalValueWidget = editor.widget(VALUE_NODE);
                FlowNodeWidget originalUnrelatedWidget = editor.widget(UNRELATED_NODE);
                long fastPaths = editor.longField("coreStructureFastPathCount");
                long fallbacks = editor.longField("coreStructureFallbackCount");
                NodeInstanceId first = NodeInstanceId.deterministic("staging-first");
                NodeInstanceId second = NodeInstanceId.deterministic("staging-second");
                GraphConnection connection = new GraphConnection(ConnectionId.deterministic("staging-edge"),
                    new GraphEndpoint(first, VALUE_FLOW_OUT), new GraphEndpoint(second, VALUE_FLOW_IN));
                failureEditor.failStaging(first, second);

                assertTrue(editor.pasteNodes(List.of(valueNode(first, 520), valueNode(second, 700)),
                    List.of(connection)));
                editor.awaitNodeCount(4);

                assertNotSame(originalGraph, editor.projectedGraph());
                assertNotSame(originalValueWidget, editor.widget(VALUE_NODE));
                assertNotSame(originalUnrelatedWidget, editor.widget(UNRELATED_NODE));
                assertEquals(fastPaths, editor.longField("coreStructureFastPathCount"));
                assertEquals(fallbacks + 1, editor.longField("coreStructureFallbackCount"));
                assertEquals(2, failureEditor.capturedWidgets.size());
                editor.awaitCleanup(failureEditor.capturedWidgets.getFirst());
                assertEquals(1, editor.cleanupCount(failureEditor.capturedWidgets.getFirst()));
                assertSame(failureEditor.capturedWidgets.getLast(),
                    editor.widgetCache.get(failureEditor.capturedNodeId));
                assertEquals(0, editor.cleanupCount(failureEditor.capturedWidgets.getLast()));
            } finally {
                editor.removed();
            }
        } finally {
            manager.shutdown();
            catalog.client().shutdown();
        }
    }

    private static GraphDocument valueDocument() {
        GraphConnection connection = new GraphConnection(ConnectionId.deterministic("value-publication-edge"),
            new GraphEndpoint(VALUE_NODE, VALUE_FLOW_OUT), new GraphEndpoint(UNRELATED_NODE, VALUE_FLOW_IN));
        return new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(valueNode(VALUE_NODE, 120), valueNode(UNRELATED_NODE, 320)),
            List.of(connection), List.of(), List.of(), OpaqueData.empty());
    }

    private static GraphDocument repeatableValueDocument(RepeatableGroupId groupId, RepeatableElementId first,
                                                          RepeatableElementId second) {
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        RepeatableElement firstElement = new RepeatableElement(first, Map.of(VALUE_PIN,
            new PinValue(VALUE_PIN, TypedValue.value(string, "first"))));
        RepeatableElement secondElement = new RepeatableElement(second, Map.of(VALUE_PIN,
            new PinValue(VALUE_PIN, TypedValue.value(string, "second"))));
        GraphNode repeated = new GraphNode(VALUE_NODE, ContractRef.of(VALUE_OWNER, NodeId.of("value")), 1, null,
            Map.of(), Map.of(), List.of(), List.of(new RepeatableBinding(groupId, true,
                List.of(firstElement, secondElement))), InspectorState.empty(), 120, 180, OpaqueData.empty());
        GraphNode unrelated = new GraphNode(UNRELATED_NODE, ContractRef.of(VALUE_OWNER, NodeId.of("value")), 1,
            null, Map.of(), Map.of(), List.of(), List.of(), InspectorState.empty(), 320, 180, OpaqueData.empty());
        GraphConnection connection = new GraphConnection(ConnectionId.deterministic("value-publication-edge"),
            new GraphEndpoint(VALUE_NODE, VALUE_FLOW_OUT), new GraphEndpoint(UNRELATED_NODE, VALUE_FLOW_IN));
        return new GraphDocument(new CatalogVersion(1, 0), VALUE_RESOURCE, 1L, VALUE_BINDING,
            Set.of(VALUE_EDITOR), List.of(repeated, unrelated), List.of(connection), List.of(), List.of(),
            OpaqueData.empty());
    }

    private static GraphNode valueNode(NodeInstanceId identity, double x) {
        PinValue value = new PinValue(VALUE_PIN,
            TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), "old"));
        return new GraphNode(identity, ContractRef.of(VALUE_OWNER, NodeId.of("value")), 1, null,
            Map.of(VALUE_PIN, value), Map.of(), List.of(), List.of(), InspectorState.empty(), x, 180,
            OpaqueData.empty());
    }

    private static ValueCatalog valueCatalog(Path tempDir) throws Exception {
        return valueCatalog(tempDir, valueDescriptor());
    }

    private static ValueCatalog valueCatalog(Path tempDir, Map<String, Object> descriptor) throws Exception {
        return valueCatalog(tempDir, descriptor, 0);
    }

    private static ValueCatalog valueCatalog(Path tempDir, Map<String, Object> descriptor, int extraCapabilities) throws Exception {
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDir.resolve("catalog.json")));
        ReSyncFlowClient client = new ReSyncFlowClient(VALUE_SERVER.canonicalText(), new ValueTransport(), null, cache);
        CatalogAuthoringPublication.Entry capability = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.CAPABILITIES, VALUE_EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(), Set.of(CatalogAuthoringPublication.Section.CAPABILITIES), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", "generic-editor"))));
        CatalogAuthoringPublication.Entry editor = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.EDITORS, VALUE_EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(VALUE_EDITOR), Set.of(CatalogAuthoringPublication.Section.EDITORS,
                CatalogAuthoringPublication.Section.CAPABILITIES), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", "generic-editor"))));
        List<CatalogAuthoringPublication.Entry> capabilities = new ArrayList<>();
        capabilities.add(capability);
        for (int index = 0; index < extraCapabilities; index++) {
            String id = "catalog-capability-" + index;
            capabilities.add(new CatalogAuthoringPublication.Entry(CatalogAuthoringPublication.Section.CAPABILITIES,
                ContractRef.of(VALUE_OWNER, CapabilityId.of(id)).canonicalText(), CatalogCacheState.ACTIVE,
                Set.of(), Set.of(CatalogAuthoringPublication.Section.CAPABILITIES), false,
                CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", id, "description", "Published Capability " + index)))));
        }
        capabilities.sort((first, second) -> first.key().compareTo(second.key()));
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                true, true, CatalogCacheState.ACTIVE, List.of(editor)),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                true, true, CatalogCacheState.ACTIVE, capabilities));
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(VALUE_BINDING,
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), sections, Set.of(VALUE_EDITOR));
        CatalogCachePublication.Entry node = CatalogCachePublication.Entry.present(
            ContractRef.of(VALUE_OWNER, NodeId.of("value")), 1L, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalize(descriptor).getBytes(StandardCharsets.UTF_8)));
        CatalogCacheKey key = new CatalogCacheKey(VALUE_SERVER, VALUE_BINDING, CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            VALUE_BINDING, 1L, List.of(node), authoring, Map.of());
        ReSyncCatalogPublicationProjection projection = client.catalogPublicationProjection();
        Field authoringField = ReSyncFlowClient.class.getDeclaredField("catalogAuthoringProjection");
        authoringField.setAccessible(true);
        ReSyncCatalogAuthoringProjection authoringProjection =
            (ReSyncCatalogAuthoringProjection) authoringField.get(client);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(VALUE_SERVER,
            "value-publication", projection, authoringProjection, null);
        handler.setAuthoringRequired(true);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending("value-publication",
            "value-publication-owner", publication).dispatched().clientReceived(key, 1L).receipt().orElseThrow();
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(handler.apply(receipt, publication, codec.encodeBytes(publication)).applied());
        ReSyncCatalogPublicationProjection.Snapshot snapshot = projection.active().orElseThrow();
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(snapshot);
        Method publish = ReSyncFlowClient.class.getDeclaredMethod("publishTypedInteractionProjection",
            ReSyncCatalogPublicationProjection.Snapshot.class, ReSyncTypedInteractionProjection.class);
        publish.setAccessible(true);
        assertTrue((boolean) publish.invoke(client, snapshot, interaction));
        Field authority = ReSyncFlowClient.class.getDeclaredField("catalogAuthority");
        authority.setAccessible(true);
        authority.set(client, ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION);
        return new ValueCatalog(client, CatalogCachePublicationCodec.authoringPublicationChecksum(authoring));
    }

    @SuppressWarnings("unchecked")
    private static void replaceAuthoringPublication(ReSyncFlowClient client, CatalogAuthoringPublication publication) throws Exception {
        Field projectionField = ReSyncFlowClient.class.getDeclaredField("catalogAuthoringProjection");
        projectionField.setAccessible(true);
        ReSyncCatalogAuthoringProjection projection = (ReSyncCatalogAuthoringProjection) projectionField.get(client);
        Field activeField = ReSyncCatalogAuthoringProjection.class.getDeclaredField("active");
        activeField.setAccessible(true);
        AtomicReference<ReSyncCatalogAuthoringProjection.Snapshot> active =
            (AtomicReference<ReSyncCatalogAuthoringProjection.Snapshot>) activeField.get(projection);
        ReSyncCatalogAuthoringProjection.Snapshot previous = active.get();
        active.set(new ReSyncCatalogAuthoringProjection.Snapshot(previous.key(), previous.revision(), publication,
            previous.canonicalBytes(), previous.checksum()));
    }

    private static Map<String, Object> valueDescriptor() {
        Map<String, Object> type = Map.of("kind", "named",
            "type", Map.of("ownerId", "builtin", "localId", "string"), "arguments", List.of());
        Map<String, Object> execution = Map.of("kind", "named",
            "type", Map.of("ownerId", "builtin", "localId", "execution"), "arguments", List.of());
        Map<String, Object> input = Map.of("kind", "pin", "id", VALUE_PIN.canonicalText(),
            "displayName", "Message", "direction", "input", "requirement", "required", "type", type,
            "description", "Message", "resourceRole", "",
            "editor", Map.of("ownerId", VALUE_OWNER.canonicalText(), "localId", "generic-editor"));
        Map<String, Object> previous = Map.of("kind", "pin", "id", VALUE_FLOW_IN.canonicalText(),
            "displayName", "Previous", "direction", "input", "requirement", "required", "type", execution,
            "description", "Previous Step", "resourceRole", "",
            "editor", Map.of("ownerId", VALUE_OWNER.canonicalText(), "localId", "generic-editor"));
        Map<String, Object> next = Map.of("kind", "pin", "id", VALUE_FLOW_OUT.canonicalText(),
            "displayName", "Next", "direction", "output", "requirement", "optional", "type", execution,
            "description", "Next Step", "resourceRole", "",
            "editor", Map.of("ownerId", VALUE_OWNER.canonicalText(), "localId", "generic-editor"));
        return Map.of("kind", "node", "id", "value", "schemaVersion", 1, "displayName", "Value",
            "description", "Value Node", "domain", "flow", "family", "utility",
            "pins", List.of(previous, input, next), "metadata", Map.of(),
            "inspector", Map.of("intent", "none", "sections", List.of()));
    }

    private static Map<String, Object> repeatableValueDescriptor() {
        Map<String, Object> type = Map.of("kind", "named",
            "type", Map.of("ownerId", "builtin", "localId", "string"), "arguments", List.of());
        Map<String, Object> descriptor = new LinkedHashMap<>(valueDescriptor());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declaredPins = (List<Map<String, Object>>) descriptor.get("pins");
        List<Map<String, Object>> pins = new ArrayList<>();
        declaredPins.forEach(pin -> pins.add(new LinkedHashMap<>(pin)));
        Map<String, Object> intent = Map.of("enabled", true, "minimum", 0, "maximum", 4,
            "ordered", true, "groupId", "messages");
        pins.stream().filter(pin -> VALUE_PIN.canonicalText().equals(pin.get("id"))).findFirst().orElseThrow()
            .put("repeatable", intent);
        Map<String, Object> group = Map.of("kind", "repeatable", "id", "messages", "title", "Message",
            "description", "Messages", "elementType", type, "minimum", 0, "maximum", 4, "ordered", true,
            "members", List.of(Map.of("pinId", VALUE_PIN.canonicalText(), "direction", "input", "type", type)));
        descriptor.put("pins", pins);
        descriptor.put("repeatables", List.of(group));
        return descriptor;
    }

    private record ValueCatalog(ReSyncFlowClient client, ContentHash authoringChecksum) {
    }

    private static final class ValueManager extends FlowManager {
        private final ReSyncFlowClient client;
        private CoreGraphEditorSession session;

        private ValueManager(ReSyncFlowClient client, CoreGraphEditorSession session) {
            super(null, null);
            this.client = client;
            this.session = session;
        }

        private void use(CoreGraphEditorSession session) {
            this.session = session;
        }

        @Override
        public ReSyncFlowClient existingFlowClient(String serverId) {
            return client;
        }

        @Override
        public boolean isFlowClientConnected(String serverId) {
            return true;
        }

        @Override
        public boolean isCurrentCoreGraphEditorSession(String serverId, ReSyncResourceType type, String id,
                                                       CoreGraphEditorSession candidate) {
            return candidate != null && candidate == session;
        }
    }

    private static class ValueEditor extends GraphEditorScreen {
        private final CoreGraphEditorSession session;
        private final ReSyncFlowClient client;
        protected final Map<String, Integer> widgetBuilds = new HashMap<>();
        private final Map<FlowNodeWidget, Integer> cleanupCounts = new IdentityHashMap<>();

        private ValueEditor(CoreGraphEditorSession session, ReSyncFlowClient client) {
            super(emptyValueGraph(), VALUE_SERVER.canonicalText(), new Screen());
            this.session = session;
            this.client = client;
            width = 900;
            height = 600;
            studioMode = true;
            StudioDocument document = new StudioDocument(session.resource().resourceType().value(), session.resource().id(), "Value Publication", null,
                session, null, new StudioViewportState());
            studioDocuments.add(document);
            activeStudioDocument = document;
            setCoreGraphEditorSession(session);
        }

        @Override
        protected ReSyncFlowClient typedCatalogClient() {
            return client;
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            widgetBuilds.merge(nodeId, 1, Integer::sum);
            return super.createNodeWidget(nodeId, node);
        }

        @Override
        protected void cleanupCoreWidget(FlowNodeWidget widget) {
            cleanupCounts.merge(widget, 1, Integer::sum);
            super.cleanupCoreWidget(widget);
        }

        private void materialize() throws Exception {
            await(() -> widgetCache.size() == 2 && field("graphRenderIndex") != null
                && field("coreWidgetTopology") != null && currentRenderIndex() && renderWorkerSettled());
        }

        private void awaitValue(String expected) throws Exception {
            await(() -> expected.equals(value()) && field("coreMutationCommitPending") == null
                && ((ArrayDeque<?>) field("coreMutationQueue")).isEmpty()
                && !widget(VALUE_NODE).hasInputValuePreview(VALUE_PIN) && currentRenderIndex()
                && renderWorkerSettled());
        }

        private void awaitNodeCount(int count) throws Exception {
            await(() -> session.graphDocument().nodes().size() == count && widgetCache.size() == count
                && field("coreMutationCommitPending") == null && currentRenderIndex());
        }

        private void awaitConnectionCount(int count) throws Exception {
            await(() -> session.graphDocument().connections().size() == count
                && graph.getConnections().size() == count && field("coreMutationCommitPending") == null
                && currentRenderIndex() && renderWorkerSettled());
        }

        private void awaitIdle() throws Exception {
            await(() -> field("coreMutationCommitPending") == null
                && ((ArrayDeque<?>) field("coreMutationQueue")).isEmpty()
                && ((AtomicReference<?>) field("coreProjectionPendingBuild")).get() == null
                && ((ConcurrentLinkedQueue<?>) field("coreProjectionBuildResults")).isEmpty()
                && !((AtomicBoolean) field("coreProjectionWorkerQueued")).get()
                && field("catalogRefresh") == null && !(boolean) field("nodeCatalogRefreshQueued")
                && field("pendingCoreWidgetTopology") == null && field("graphRenderContinuityIndex") == null
                && field("graphRenderContinuityTopology") == null && field("coreWidgetPublicationFailure") == null
                && currentRenderIndex() && renderWorkerSettled());
        }

        private void awaitPosition(NodeInstanceId nodeId, double x, double y) throws Exception {
            await(() -> {
                GraphNode node = session.graphDocument().nodes().stream()
                    .filter(candidate -> candidate.instanceId().equals(nodeId)).findFirst().orElse(null);
                FlowNode projected = graph.getNodes().get(nodeId.canonicalText());
                FlowNodeWidget widget = widget(nodeId);
                return node != null && projected != null && widget != null
                    && Double.compare(node.x(), x) == 0 && Double.compare(node.y(), y) == 0
                    && Double.compare(projected.getX(), x) == 0 && Double.compare(projected.getY(), y) == 0
                    && widget.getX() == (int) x && widget.getY() == (int) y
                    && field("coreMutationCommitPending") == null
                    && ((ArrayDeque<?>) field("coreMutationQueue")).isEmpty() && currentRenderIndex()
                    && renderWorkerSettled();
            });
        }

        private void awaitRepeatableOrder(RepeatableGroupId groupId, List<RepeatableElementId> expectedPrefix,
                                          int expectedCount) throws Exception {
            await(() -> {
                List<RepeatableElementId> order = repeatableOrder(groupId);
                return order.size() == expectedCount && order.subList(0, expectedPrefix.size()).equals(expectedPrefix)
                    && repeatableRowsMatch(groupId, order) && field("coreMutationCommitPending") == null
                    && ((ArrayDeque<?>) field("coreMutationQueue")).isEmpty() && currentRenderIndex()
                    && renderWorkerSettled();
            });
        }

        private List<RepeatableElementId> repeatableOrder(RepeatableGroupId groupId) {
            return session.graphDocument().nodes().stream()
                .filter(candidate -> candidate.instanceId().equals(VALUE_NODE)).findFirst().orElseThrow()
                .repeatables().stream().filter(binding -> binding.groupId().equals(groupId)).findFirst().orElseThrow()
                .elements().stream().map(RepeatableElement::elementId).toList();
        }

        private boolean repeatableRowsMatch(RepeatableGroupId groupId, List<RepeatableElementId> expected) {
            if (!repeatableOrder(groupId).equals(expected)) {
                return false;
            }
            FlowNodeWidget widget = widget(VALUE_NODE);
            List<String> expectedViews = expected.stream().map(element -> widget.coreViewPin(VALUE_PIN, element)).toList();
            return expectedViews.stream().noneMatch(value -> value == null)
                && repeatableRowViews().equals(expectedViews)
                && expectedViews.stream().allMatch(view -> widget.getPinBounds(view, true) != null);
        }

        private List<String> repeatableRowViews() {
            FlowNodeWidget widget = widget(VALUE_NODE);
            return widget.getVisibleInputPins().stream().filter(view -> {
                var endpoint = widget.corePinEndpoint(view);
                return endpoint != null && VALUE_PIN.equals(endpoint.pinId()) && endpoint.elementId() != null;
            }).toList();
        }

        private boolean addRepeatable(NodeInstanceId nodeId, RepeatableGroupId groupId) throws Exception {
            Method method = NodeWidget.class.getDeclaredMethod("addCoreRepeatable", RepeatableGroupId.class);
            method.setAccessible(true);
            return (boolean) method.invoke(widget(nodeId), groupId);
        }

        private boolean undoHistory() {
            return undoActiveHistory();
        }

        private boolean redoHistory() {
            return redoActiveHistory();
        }

        private void awaitCleanup(FlowNodeWidget widget) throws Exception {
            await(() -> cleanupCount(widget) > 0 && !wasRetired(widget));
        }

        private void awaitProjectionResult() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (((ConcurrentLinkedQueue<?>) field("coreProjectionBuildResults")).isEmpty()
                && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertFalse(((ConcurrentLinkedQueue<?>) field("coreProjectionBuildResults")).isEmpty());
        }

        private void await(ValueCondition condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            int readyObservations = 0;
            while (System.nanoTime() < deadline) {
                tick();
                renderHandler(new TestDrawContext(), width / 2, height / 2, 0F);
                invoke("ensureGraphRenderIndex");
                if (condition.ready()) {
                    if (++readyObservations == 2) {
                        return;
                    }
                } else {
                    readyObservations = 0;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
            assertTrue(readyObservations >= 2);
        }

        private String value() {
            GraphNode node = session.graphDocument().nodes().stream()
                .filter(candidate -> candidate.instanceId().equals(VALUE_NODE)).findFirst().orElseThrow();
            PinValue value = node.values().get(VALUE_PIN);
            return value != null ? String.valueOf(value.value().value()) : null;
        }

        private FlowNodeWidget widget(NodeInstanceId nodeId) {
            return widgetCache.get(nodeId.canonicalText());
        }

        @SuppressWarnings("unchecked")
        private TextInputWidget input(FlowNodeWidget widget, PinId pin) throws Exception {
            Field field = NodeWidget.class.getDeclaredField("inputWidgets");
            field.setAccessible(true);
            return (TextInputWidget) ((Map<String, Widget>) field.get(widget)).get(pin.canonicalText());
        }

        private int widgetBuilds(NodeInstanceId nodeId) {
            return widgetBuilds.getOrDefault(nodeId.canonicalText(), 0);
        }

        private int queuedMutationCount() throws Exception {
            return ((ArrayDeque<?>) field("coreMutationQueue")).size();
        }

        private int cleanupCount(FlowNodeWidget widget) {
            return cleanupCounts.getOrDefault(widget, 0);
        }

        @SuppressWarnings("unchecked")
        private int retiredWidgetCount() throws Exception {
            return ((Set<FlowNodeWidget>) field("retiredCoreWidgets")).size();
        }

        private boolean canRefreshStable() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("canRefreshStableCoreWidgets", FlowGraph.class);
            method.setAccessible(true);
            return (boolean) method.invoke(this, graph);
        }

        @SuppressWarnings("unchecked")
        private List<FlowNodeWidget> preparingWidgets() throws Exception {
            Object refresh = field("catalogRefresh");
            if (refresh == null) {
                return List.of();
            }
            Field prepared = refresh.getClass().getDeclaredField("preparedWidgetOrder");
            prepared.setAccessible(true);
            return List.copyOf((List<FlowNodeWidget>) prepared.get(refresh));
        }

        @SuppressWarnings("unchecked")
        private boolean retirementPayloadsEmpty() throws Exception {
            Object topology = field("coreWidgetTopology");
            if (topology == null) {
                return false;
            }
            Method adopted = topology.getClass().getDeclaredMethod("retireAfterAdoption");
            Method abandoned = topology.getClass().getDeclaredMethod("retireIfAbandoned");
            adopted.setAccessible(true);
            abandoned.setAccessible(true);
            return ((List<FlowNodeWidget>) adopted.invoke(topology)).isEmpty()
                && ((List<FlowNodeWidget>) abandoned.invoke(topology)).isEmpty();
        }

        private Object renderIndex() throws Exception {
            return field("graphRenderIndex");
        }

        private boolean renderWorkerSettled() throws Exception {
            return ((AtomicReference<?>) field("graphRenderPendingBuild")).get() == null
                && !((AtomicBoolean) field("graphRenderWorkerQueued")).get()
                && ((AtomicReference<?>) field("graphRenderBuildResult")).get() == null;
        }

        private long longField(String name) throws Exception {
            return (long) field(name);
        }

        private boolean currentRenderIndex() throws Exception {
            Object index = renderIndex();
            if (index == null) {
                return false;
            }
            for (Method method : GraphEditorScreen.class.getDeclaredMethods()) {
                if (method.getName().equals("currentGraphRenderIndex")) {
                    method.setAccessible(true);
                    return (boolean) method.invoke(this, index);
                }
            }
            return false;
        }

        private List<?> projectedConnections() {
            return graph.getConnections();
        }

        private FlowGraph projectedGraph() {
            return graph;
        }

        private FlowNode projectedNode(NodeInstanceId nodeId) {
            return graph.getNodes().get(nodeId.canonicalText());
        }

        private boolean graphContains(NodeInstanceId nodeId) {
            return graph.getNodes().containsKey(nodeId.canonicalText());
        }

        @SuppressWarnings("unchecked")
        private boolean wasRetired(FlowNodeWidget widget) throws Exception {
            return ((Set<FlowNodeWidget>) field("retiredCoreWidgets")).contains(widget);
        }

        private boolean move(NodeInstanceId nodeId, double x, double y) throws Exception {
            FlowNodeWidget widget = widget(nodeId);
            widget.setPosition((int) x, (int) y);
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreMoveMutation", String.class,
                String.class, Function.class, String[].class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.setNodePosition(nodeId, x, y);
                return true;
            };
            return (boolean) method.invoke(this, "Node Positions", "position:" + nodeId.canonicalText(), mutation,
                new String[]{nodeId.canonicalText()});
        }

        private boolean moveSelection(Map<NodeInstanceId, double[]> positions) throws Exception {
            positions.forEach((nodeId, position) -> widget(nodeId).setPosition((int) position[0], (int) position[1]));
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreMoveMutation", String.class,
                String.class, Function.class, String[].class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                positions.forEach((nodeId, position) -> current.setNodePosition(nodeId, position[0], position[1]));
                return true;
            };
            String[] nodeIds = positions.keySet().stream().map(NodeInstanceId::canonicalText).toArray(String[]::new);
            return (boolean) method.invoke(this, "Node Positions", "positions", mutation, nodeIds);
        }

        private void saveEditor() {
            saveGraph();
        }

        private boolean canMovePreview(NodeInstanceId id) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("canMoveNode", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(this, id.canonicalText());
        }

        private boolean canDeletePreview(NodeInstanceId id) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("canDeleteNode", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(this, id.canonicalText());
        }

        private float nodeAnimationWidth(FlowNodeWidget widget) throws Exception {
            Class<?> type = widget.getClass();
            while (type != null) {
                try {
                    Field target = type.getDeclaredField("targetWidth");
                    target.setAccessible(true);
                    return ((Number) target.get(widget)).floatValue();
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new IllegalStateException("Node animation target is missing");
        }

        private void movePreviewNode(NodeInstanceId id, int x, int y) throws Exception {
            FlowNodeWidget widget = widget(id);
            widget.setPosition(x, y);
            refreshGraphRenderGeometry(List.of(id.canonicalText()));
            Method method = GraphEditorScreen.class.getDeclaredMethod("syncNodePosition", FlowNodeWidget.class);
            method.setAccessible(true);
            method.invoke(this, widget);
        }

        private boolean connectPreviewNode(NodeInstanceId source, NodeInstanceId target) throws Exception {
            Method start = GraphEditorScreen.class.getDeclaredMethod("startWireDrag", FlowNodeWidget.class, String.class, boolean.class);
            start.setAccessible(true);
            start.invoke(this, widget(source), VALUE_FLOW_OUT.canonicalText(), false);
            Method connect = GraphEditorScreen.class.getDeclaredMethod("connectWireTarget", FlowNodeWidget.class, String.class, boolean.class);
            connect.setAccessible(true);
            return (boolean) connect.invoke(this, widget(target), VALUE_FLOW_IN.canonicalText(), true);
        }

        private void deletePreviewNode(NodeInstanceId id) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("deleteNode", String.class);
            method.setAccessible(true);
            method.invoke(this, id.canonicalText());
        }

        private void pauseProjection() throws Exception {
            ((AtomicBoolean) field("coreProjectionWorkerQueued")).set(true);
        }

        private void pauseRenderWorker() throws Exception {
            ((AtomicBoolean) field("graphRenderWorkerQueued")).set(true);
        }

        private String authoringDiagnostic() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("coreAuthoringDiagnostic");
            method.setAccessible(true);
            return (String) method.invoke(this);
        }

        private NodeInstanceId addCatalogNode() throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("addCoreNode", int.class, int.class,
                String.class, String.class, Map.class);
            method.setAccessible(true);
            String id = (String) method.invoke(this, 540, 180, VALUE_OWNER.canonicalText() + ":value", null, Map.of());
            return id != null ? NodeInstanceId.parseCanonicalText(id) : null;
        }

        private void releaseProjection() throws Exception {
            invoke("buildCoreProjections");
        }

        private void invalidatePreparedCandidate() throws Exception {
            Object pending = field("coreMutationCommitPending");
            Method candidate = pending.getClass().getDeclaredMethod("candidate");
            candidate.setAccessible(true);
            ((CoreGraphEditorSession) candidate.invoke(pending)).setNodePosition(UNRELATED_NODE, 380, 220);
        }

        @SuppressWarnings("unchecked")
        private List<FlowNodeWidget> previewHit(double x, double y) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("graphPointCandidates", double.class, double.class);
            method.setAccessible(true);
            return (List<FlowNodeWidget>) method.invoke(this, x, y);
        }

        private void selectSession(CoreGraphEditorSession selected) {
            beforeStudioDocumentSelection();
            StudioDocument document = studioDocuments.stream().filter(candidate -> candidate.coreSession() == selected)
                .findFirst().orElseGet(() -> {
                    StudioDocument created = new StudioDocument("flow", selected.resource().id(), selected.resource().id(),
                        null, selected, null, new StudioViewportState());
                    studioDocuments.add(created);
                    return created;
                });
            activeStudioDocument = document;
            afterStudioDocumentSelected(document);
        }

        private boolean addConnection(GraphConnection connection) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreStructuralMutation", String.class,
                Function.class, String[].class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.addConnection(connection);
                return true;
            };
            return (boolean) method.invoke(this, "Connection Add", mutation,
                new String[]{VALUE_NODE.canonicalText(), UNRELATED_NODE.canonicalText()});
        }

        private boolean addNode(GraphNode node) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreCreateMutation", String.class,
                Function.class, String.class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.addNode(node);
                return true;
            };
            return (boolean) method.invoke(this, "Add Node", mutation, node.instanceId().canonicalText());
        }

        private boolean addNodeWithCapability(GraphNode node, ContractRef<CapabilityId> capability) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreCreateMutation", String.class,
                Function.class, String.class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.addNode(node, Set.of(capability));
                return true;
            };
            return (boolean) method.invoke(this, "Add Node", mutation, node.instanceId().canonicalText());
        }

        private boolean addNodeWithoutIdentity(GraphNode node) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreStructuralMutation", String.class,
                Function.class, String[].class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.addNode(node);
                return true;
            };
            return (boolean) method.invoke(this, "Unidentified Add", mutation, new String[0]);
        }

        private boolean deleteNode(NodeInstanceId nodeId) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreDeleteMutation", String.class,
                Function.class, String[].class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current ->
                GraphEditorScreen.removeCoreNodes(current, List.of(nodeId));
            return (boolean) method.invoke(this, "Delete Node", mutation,
                new String[]{nodeId.canonicalText()});
        }

        private boolean deleteConnection(ConnectionId connectionId) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreStructuralMutation", String.class,
                Function.class, String[].class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.removeConnection(connectionId);
                return true;
            };
            return (boolean) method.invoke(this, "Connection Delete", mutation,
                new String[]{VALUE_NODE.canonicalText(), UNRELATED_NODE.canonicalText()});
        }

        private boolean replaceUnknown(String value) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreGlobalMutation", String.class,
                Function.class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                GraphDocument document = current.graphDocument();
                current.replaceGraph(new GraphDocument(document.schemaVersion(), document.resource(), document.revision(),
                    document.catalogBinding(), document.requiredCapabilities(), document.nodes(), document.connections(),
                    document.variables(), document.functions(), OpaqueData.of(Map.of("future", value))));
                return true;
            };
            return (boolean) method.invoke(this, "Unknown Data", mutation);
        }

        private boolean addNodeAndMoveSurvivor(GraphNode added, NodeInstanceId survivor, double x, double y)
            throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCoreCreateMutation", String.class,
                Function.class, String.class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                current.addNode(added);
                current.setNodePosition(survivor, x, y);
                return true;
            };
            return (boolean) method.invoke(this, "Mixed Structure", mutation, added.instanceId().canonicalText());
        }

        private boolean pasteNodes(List<GraphNode> addedNodes, List<GraphConnection> addedConnections) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod("commitCorePasteMutation", String.class,
                Function.class, List.class);
            method.setAccessible(true);
            Function<CoreGraphEditorSession, Boolean> mutation = current -> {
                GraphDocument document = current.graphDocument();
                List<GraphNode> nodes = new ArrayList<>(document.nodes());
                nodes.addAll(addedNodes);
                List<GraphConnection> connections = new ArrayList<>(document.connections());
                connections.addAll(addedConnections);
                current.replaceGraph(new GraphDocument(document.schemaVersion(), document.resource(), document.revision(),
                    document.catalogBinding(), document.requiredCapabilities(), nodes, connections, document.variables(),
                    document.functions(), document.unknown()));
                return true;
            };
            List<String> selection = addedNodes.stream().map(node -> node.instanceId().canonicalText()).toList();
            return (boolean) method.invoke(this, "Node Paste", mutation, selection);
        }

        private int intField(String name) throws Exception {
            return (int) field(name);
        }

        private Object field(String name) throws Exception {
            Field field = GraphEditorScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(this);
        }

        private Object invoke(String name) throws Exception {
            Method method = GraphEditorScreen.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(this);
        }

        private static FlowGraph emptyValueGraph() {
            FlowGraph graph = new FlowGraph();
            graph.setId(VALUE_RESOURCE.id());
            graph.setResourceType(ReSyncResourceType.FLOW.typeId());
            return graph;
        }
    }

    private static final class RefreshOverlapEditor extends ValueEditor {
        private boolean holdCatalogPreparation;

        private RefreshOverlapEditor(CoreGraphEditorSession session, ReSyncFlowClient client) {
            super(session, client);
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            if (holdCatalogPreparation && nodeId.equals(UNRELATED_NODE.canonicalText())) {
                throw new IllegalStateException("planned catalog overlap");
            }
            return super.createNodeWidget(nodeId, node);
        }

        private void holdCatalogPreparation() {
            holdCatalogPreparation = true;
        }

        private void releaseCatalogPreparation() {
            holdCatalogPreparation = false;
        }
    }

    private static final class StagingFailureEditor extends ValueEditor {
        private final List<FlowNodeWidget> capturedWidgets = new ArrayList<>();
        private final Set<String> stagedNodeIds = new HashSet<>();
        private String capturedNodeId;
        private String failedNodeId;
        private boolean failCreation;
        private boolean failDiagnostic;

        private StagingFailureEditor(CoreGraphEditorSession session, ReSyncFlowClient client) {
            super(session, client);
        }

        private void failStaging(NodeInstanceId first, NodeInstanceId second) {
            capturedWidgets.clear();
            stagedNodeIds.clear();
            stagedNodeIds.add(first.canonicalText());
            stagedNodeIds.add(second.canonicalText());
            capturedNodeId = null;
            failedNodeId = null;
            failCreation = true;
            failDiagnostic = true;
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            if (failCreation && stagedNodeIds.contains(nodeId) && capturedNodeId == null) {
                FlowNodeWidget widget = super.createNodeWidget(nodeId, node);
                capturedNodeId = nodeId;
                capturedWidgets.add(widget);
                return widget;
            }
            if (failCreation && stagedNodeIds.contains(nodeId)) {
                failCreation = false;
                failedNodeId = nodeId;
                throw new IllegalStateException("planned structural widget failure");
            }
            FlowNodeWidget widget = super.createNodeWidget(nodeId, node);
            if (nodeId.equals(capturedNodeId)) {
                capturedWidgets.add(widget);
            }
            return widget;
        }

        @Override
        protected FlowNodeWidget createDiagnosticNodeWidget(String nodeId, FlowNode node, RuntimeException failure) {
            if (failDiagnostic && nodeId.equals(failedNodeId)) {
                failDiagnostic = false;
                throw new IllegalStateException("planned structural diagnostic failure");
            }
            return super.createDiagnosticNodeWidget(nodeId, node, failure);
        }
    }

    private static final class DivergingRefreshEditor extends ValueEditor {
        private boolean divergeTargetRefresh;

        private DivergingRefreshEditor(CoreGraphEditorSession session, ReSyncFlowClient client) {
            super(session, client);
        }

        private void divergeNextTargetRefresh() {
            divergeTargetRefresh = true;
        }

        @Override
        protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
            if (!nodeId.equals(UNRELATED_NODE.canonicalText())) {
                return super.createNodeWidget(nodeId, node);
            }
            widgetBuilds.merge(nodeId, 1, Integer::sum);
            return new FlowNodeWidget((int) node.getX(), (int) node.getY(), node, graph, nodeId,
                VALUE_SERVER.canonicalText()) {
                @Override
                public void refreshInputWidgets() {
                    super.refreshInputWidgets();
                    if (divergeTargetRefresh) {
                        divergeTargetRefresh = false;
                        FlowNode target = DivergingRefreshEditor.this.graph.getNodes().get(nodeId);
                        target.setX(target.getX() + 1D);
                    }
                }
            };
        }
    }

    @FunctionalInterface
    private interface ValueCondition {
        boolean ready() throws Exception;
    }

    private static final class ValueTransport implements ReSyncFrameTransport {
        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static GraphDocument deletionDocument() {
        OwnerId owner = OwnerId.of("test");
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.deterministic("batch-delete"),
            ContractRef.of(owner, ResourceTypeId.of("flow")), "flow");
        CatalogBinding binding = new CatalogBinding(1, "6".repeat(64), "7".repeat(64));
        NodeInstanceId protectedNode = NodeInstanceId.deterministic("delete-protected");
        NodeInstanceId first = NodeInstanceId.deterministic("delete-first");
        NodeInstanceId second = NodeInstanceId.deterministic("delete-second");
        List<GraphNode> nodes = List.of(
            node(owner, protectedNode, 0, 0),
            node(owner, first, 100, 0),
            node(owner, second, 200, 0));
        List<GraphConnection> connections = List.of(
            connection("delete-edge-one", protectedNode, first),
            connection("delete-edge-two", first, second));
        return new GraphDocument(new CatalogVersion(1, 0), resource, 9, binding, Set.of(), nodes, connections,
            List.of(), List.of(), OpaqueData.empty());
    }

    private static GraphNode node(OwnerId owner, NodeInstanceId identity, double x, double y) {
        return new GraphNode(identity, ContractRef.of(owner, NodeId.of("node")), 1, null, Map.of(), Map.of(),
            List.of(), List.of(), InspectorState.empty(), x, y, OpaqueData.empty());
    }

    private static GraphConnection connection(String id, NodeInstanceId source, NodeInstanceId target) {
        return new GraphConnection(ConnectionId.deterministic(id), new GraphEndpoint(source, PinId.of("flow")),
            new GraphEndpoint(target, PinId.of("flow")));
    }
}
