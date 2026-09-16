package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.identity.CatalogBinding;
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
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphUiProjectionTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final UUID MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void reverseKeepsCanonicalCoreBaselineAndUnknownFields() {
        ServerResourceLocator resource = resource("flow", "baseline");
        GraphDocument graph = graph(resource, OpaqueData.of(java.util.Map.of("futureField", java.util.Map.of("keep", true))));
        CoreGraphUiProjection projection = new CoreGraphUiProjection();

        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 4, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 1);

        FlowGraph ui = projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "baseline").orElseThrow();
        CoreGraphUiProjection.ReverseResult result = projection.reverse(SERVER.canonicalText(), ReSyncResourceType.FLOW, ui);

        assertTrue(result.lossless());
        assertEquals(graph.checksum(), result.graphDocument().checksum());
        assertEquals(graph.unknown(), result.graphDocument().unknown());
        assertEquals("baseline", ui.getId());
        assertFalse(ui.getNodes() == null);
    }

    @Test
    void editorProjectionPublishesOneCompleteCountedTopology() {
        ServerResourceLocator resource = resource("flow", "editor-result");
        CoreGraphEditorSession session = new CoreGraphEditorSession(populatedGraph(resource));

        CoreGraphUiProjection.ProjectionResult result = new CoreGraphUiProjection().projectEditorSession(session);

        assertTrue(result.complete());
        assertEquals(1, result.sourceNodeCount());
        assertEquals(1, result.projectedNodeCount());
        assertEquals(1, result.sourceConnectionCount());
        assertEquals(1, result.projectedConnectionCount());
        assertTrue(result.droppedNodeIdentities().isEmpty());
        assertTrue(result.droppedConnectionIdentities().isEmpty());
        assertEquals(session.checksum().canonicalText(), result.documentChecksum());
        assertFalse(result.topologyChecksum().isBlank());
        assertEquals(result.graph().getNodes().keySet(), Set.of(
            populatedGraph(resource).nodes().getFirst().instanceId().canonicalText()));
        assertFalse(session.dirty());
    }

    @Test
    void editorProjectionRejectsConnectionsWithoutPublishedEndpoints() {
        ServerResourceLocator resource = resource("flow", "missing-endpoint");
        GraphNode sourceNode = node(Map.of());
        GraphEndpoint source = new GraphEndpoint(sourceNode.instanceId(), PinId.of("out"));
        GraphEndpoint target = new GraphEndpoint(NodeInstanceId.deterministic("missing-node"), PinId.of("in"));
        GraphConnection connection = new GraphConnection(ConnectionId.deterministic("missing-endpoint"), source, target);
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(),
            List.of(sourceNode), List.of(connection), List.of(), List.of(), OpaqueData.empty());

        CoreGraphUiProjection.ProjectionResult result = new CoreGraphUiProjection().projectEditorSession(
            new CoreGraphEditorSession(document));

        assertFalse(result.complete());
        assertEquals(1, result.sourceConnectionCount());
        assertEquals(1, result.projectedConnectionCount());
        assertEquals(List.of(connection.connectionId().canonicalText()), result.droppedConnectionIdentities());
    }

    @Test
    void largeEditorProjectionPublishesEveryNodeAndConnectionAsOneCompleteTopology() {
        ServerResourceLocator resource = resource("flow", "large-editor");
        int nodeCount = 2_048;
        List<GraphNode> nodes = new ArrayList<>(nodeCount);
        List<GraphConnection> connections = new ArrayList<>(nodeCount - 1);
        for (int index = 0; index < nodeCount; index++) {
            NodeInstanceId identity = NodeInstanceId.deterministic("large-node-" + index);
            nodes.add(new GraphNode(identity, ContractRef.of(OWNER, NodeId.of("test_node")), 2, null, Map.of(),
                Map.of(), List.of(), List.of(), InspectorState.empty(), index * 24D, index % 16 * 32D,
                OpaqueData.empty()));
            if (index > 0) {
                GraphEndpoint source = new GraphEndpoint(nodes.get(index - 1).instanceId(), PinId.of("out"));
                GraphEndpoint target = new GraphEndpoint(identity, PinId.of("in"));
                connections.add(new GraphConnection(ConnectionId.deterministic("large-connection-" + index),
                    source, target));
            }
        }
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), nodes,
            connections, List.of(), List.of(), OpaqueData.empty());

        CoreGraphUiProjection.ProjectionResult result = new CoreGraphUiProjection().projectEditorSnapshot(
            CoreGraphUiProjection.EditorSnapshot.capture(new CoreGraphEditorSession(document)));

        assertTrue(result.complete());
        assertEquals(nodeCount, result.projectedNodeCount());
        assertEquals(nodeCount - 1, result.projectedConnectionCount());
        assertEquals(nodeCount, result.graph().getNodes().size());
        assertEquals(nodeCount - 1, result.graph().getConnections().size());
        assertEquals(64, result.topologyChecksum().length());
    }

    @Test
    void reversePatchesOnlyEditableCoreFieldsAndKeepsStableOpaqueIdentity() {
        ServerResourceLocator resource = resource("flow", "editable");
        GraphDocument graph = populatedGraph(resource);
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 8, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 1);

        FlowGraph ui = projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "editable").orElseThrow();
        GraphNode originalNode = graph.nodes().getFirst();
        String nodeId = originalNode.instanceId().canonicalText();
        assertEquals("before", ui.getNodes().get(nodeId).getInputValues().get("message"));
        assertEquals("restudio.resync:test_node", ui.getNodes().get(nodeId).getType());
        ui.getNodes().get(nodeId).getInputValues().put("message", "after");
        ui.getNodes().get(nodeId).setX(91.5D);
        ui.getConnections().getFirst().setSourcePinId("rewired");
        CoreGraphUiProjection.ReverseResult result = projection.reverse(SERVER.canonicalText(), ReSyncResourceType.FLOW, ui);

        assertTrue(result.lossless(), result.reason());
        GraphDocument reversed = result.graphDocument();
        GraphNode reversedNode = reversed.nodes().getFirst();
        PinValue reversedPin = reversedNode.values().get(PinId.of("message"));
        assertEquals("after", reversedPin.value().value());
        assertEquals(originalNode.values().get(PinId.of("message")).value().unknown(), reversedPin.value().unknown());
        assertEquals(originalNode.values().get(PinId.of("message")).unknown(), reversedPin.unknown());
        assertEquals(originalNode.unknown(), reversedNode.unknown());
        assertEquals(91.5D, reversedNode.x());
        assertEquals(PinId.of("rewired"), reversed.connections().getFirst().source().pinId());
        assertEquals(graph.connections().getFirst().connectionId(), reversed.connections().getFirst().connectionId());
        assertEquals(graph.connections().getFirst().unknown(), reversed.connections().getFirst().unknown());
        assertEquals(graph.connections().getFirst().source().unknown(), reversed.connections().getFirst().source().unknown());
    }

    @Test
    void reverseRejectsStableIdentityChanges() {
        ServerResourceLocator resource = resource("flow", "identity");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 3, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), populatedGraph(resource),
            ResourceActivationState.ACTIVE), false, 1);

        FlowGraph ui = projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "identity").orElseThrow();
        ui.getNodes().values().iterator().next().getOpaqueProperties().remove("instanceId");
        CoreGraphUiProjection.ReverseResult result = projection.reverse(SERVER.canonicalText(), ReSyncResourceType.FLOW, ui);

        assertFalse(result.lossless());
        assertTrue(result.reason().contains("identity"));
    }

    @Test
    void readOnlyProjectionCannotBeSaved() {
        ServerResourceLocator resource = resource("flow", "read-only");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 2, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)),
            graph(resource, OpaqueData.empty()), ResourceActivationState.ACTIVE), true, 1);

        FlowGraph ui = projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "read-only").orElseThrow();
        CoreGraphUiProjection.ReverseResult result = projection.reverse(SERVER.canonicalText(), ReSyncResourceType.FLOW, ui);

        assertFalse(result.lossless());
        assertTrue(result.reason().contains("read-only"));
    }

    @Test
    void tombstoneRemovesOnlyTheUiProjection() {
        ServerResourceLocator resource = resource("flow", "deleted");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        GraphResourceState live = GraphResourceState.live(resource, 6, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)),
            graph(resource, OpaqueData.empty()), ResourceActivationState.ACTIVE);
        projection.apply(ReSyncResourceType.FLOW, live, false, 1);
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.tombstone(resource, 7, MUTATION,
            new ContentHash("e".repeat(64))), false, 1);
        projection.apply(ReSyncResourceType.FLOW, live, false, 1);

        assertTrue(projection.authoritative(SERVER.canonicalText(), ReSyncResourceType.FLOW, "deleted"));
        assertTrue(projection.tombstoned(SERVER.canonicalText(), ReSyncResourceType.FLOW, "deleted"));
        assertTrue(projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "deleted").isEmpty());
    }

    @Test
    void absentTypedValueCannotBeCoercedByTheUi() {
        ServerResourceLocator resource = resource("flow", "absent");
        PinId pinId = PinId.of("message");
        GraphNode node = node(Map.of(pinId, new PinValue(pinId,
            TypedValue.absent(TypeExpr.named(TypeReference.of("builtin", "string"))))));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(node),
            List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 1, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 1);

        FlowGraph ui = projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "absent").orElseThrow();
        assertNull(ui.getNodes().values().iterator().next().getInputValues().get("message"));
        ui.getNodes().values().iterator().next().getInputValues().put("message", "coerced");

        CoreGraphUiProjection.ReverseResult result = projection.reverse(SERVER.canonicalText(), ReSyncResourceType.FLOW, ui);
        assertFalse(result.lossless(), result.graphDocument() == null ? result.reason()
            : result.graphDocument().nodes().getFirst().values().get(pinId).value().state().name());
    }

    @Test
    void projectsJsonNumbersWithoutDoubleRounding() {
        ServerResourceLocator resource = resource("flow", "precise-number");
        PinId pinId = PinId.of("precise");
        BigDecimal precise = new BigDecimal("9007199254740993123456789.125");
        GraphNode node = node(Map.of(pinId, new PinValue(pinId,
            TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "number")), precise))));
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(node),
            List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 1, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 1);

        FlowGraph ui = projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "precise-number").orElseThrow();
        Object projected = ui.getNodes().values().iterator().next().getInputValues().get("precise");

        assertEquals(BigDecimal.class, projected.getClass());
        assertEquals(precise, projected);
        assertEquals(graph.checksum(), projection.reverse(SERVER.canonicalText(), ReSyncResourceType.FLOW, ui)
            .graphDocument().checksum());
    }

    @Test
    void staleEditorProvenanceCannotOverwriteANewerBaseline() {
        ServerResourceLocator resource = resource("flow", "stale-editor");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        GraphDocument graph = populatedGraph(resource);
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 8, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 1);
        FlowGraph stale = projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "stale-editor").orElseThrow();

        UUID nextMutation = UUID.fromString("33333333-3333-4333-8333-333333333333");
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 9, nextMutation,
            new ContentHash("e".repeat(64)), new ContentHash("f".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 1);
        CoreGraphUiProjection.ReverseResult result = projection.reverse(SERVER.canonicalText(), ReSyncResourceType.FLOW, stale);

        assertFalse(result.lossless());
        assertTrue(result.reason().contains("revision, hash, and mutation"));
    }

    @Test
    void higherAuthorityEpochResetsBaselinesAndRejectsLateLowerEpochProjections() {
        ServerResourceLocator resource = resource("flow", "epoch-reset");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        GraphDocument oldGraph = populatedGraph(resource);
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 8, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), oldGraph,
            ResourceActivationState.ACTIVE), false, 4);

        GraphDocument replacement = graph(resource, OpaqueData.of(Map.of("epoch", 5)));
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 1, MUTATION,
            new ContentHash("e".repeat(64)), new ContentHash("f".repeat(64)), replacement,
            ResourceActivationState.ACTIVE), false, 5);

        assertEquals(5L, projection.authorityEpoch(SERVER.canonicalText()));
        assertEquals(1L, projection.baseline(SERVER.canonicalText(), ReSyncResourceType.FLOW, "epoch-reset")
            .orElseThrow().revision());
        assertTrue(projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 9, MUTATION,
            new ContentHash("1".repeat(64)), new ContentHash("2".repeat(64)), oldGraph,
            ResourceActivationState.ACTIVE), false, 4).isEmpty());
        assertEquals(1L, projection.baseline(SERVER.canonicalText(), ReSyncResourceType.FLOW, "epoch-reset")
            .orElseThrow().revision());
    }

    @Test
    void sameAuthorityEpochKeepsRevisionOrderingAcrossReconnects() {
        ServerResourceLocator resource = resource("flow", "same-epoch");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        GraphDocument graph = graph(resource, OpaqueData.empty());
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 4, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 7);

        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 3, MUTATION,
            new ContentHash("e".repeat(64)), new ContentHash("f".repeat(64)), graph,
            ResourceActivationState.ACTIVE), false, 7);

        assertEquals(7L, projection.authorityEpoch(SERVER.canonicalText()));
        assertEquals(4L, projection.baseline(SERVER.canonicalText(), ReSyncResourceType.FLOW, "same-epoch")
            .orElseThrow().revision());
    }

    @Test
    void exposesOnlyProvenStableIdEditCapabilities() {
        ServerResourceLocator resource = resource("flow", "capabilities");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.live(resource, 1, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), populatedGraph(resource),
            ResourceActivationState.ACTIVE), false, 1);

        CoreGraphUiProjection.EditCapabilities capabilities = projection.editCapabilities(SERVER.canonicalText(),
            ReSyncResourceType.FLOW, "capabilities").orElseThrow();

        assertTrue(capabilities.nodeValues());
        assertTrue(capabilities.nodePositions());
        assertTrue(capabilities.connectionRewire());
        assertFalse(capabilities.nodeTopology());
        assertFalse(capabilities.connectionTopology());
        assertFalse(capabilities.functionSignature());
    }

    @Test
    void functionProjectionPreservesParameterNamesWidgetsAndCatalogs() {
        ServerResourceLocator resource = resource("function", "predicate");
        GraphDocument document = graph(resource, OpaqueData.empty());
        TypeExpr player = TypeExpr.named(TypeReference.of("builtin", "player"));
        FunctionParameterContract input = new FunctionParameterContract(FunctionParameterId.deterministic("player"),
            player, true, null, Map.of("name", "player", "widget", "player", "optionsSource", "online_players"));
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), FunctionRevision.of(4),
            List.of(input), List.of());

        CoreGraphUiProjection.ProjectionResult result = new CoreGraphUiProjection().projectEditorSession(
            new CoreGraphEditorSession(new FunctionSourceDocument(signature, document)));

        FlowGraph.FunctionParameter projected = result.graph().getFunctionInputs().getFirst();
        assertEquals(input.id().canonicalText(), projected.getParameterId());
        assertEquals("player", projected.getName());
        assertEquals("player", projected.getWidget());
        assertEquals("online_players", projected.getOptionsSource());
    }

    @Test
    void sameRevisionTombstoneWinsTheSingleAuthorityState() {
        ServerResourceLocator resource = resource("flow", "ordered");
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        GraphResourceState live = GraphResourceState.live(resource, 7, MUTATION,
            new ContentHash("c".repeat(64)), new ContentHash("d".repeat(64)), graph(resource, OpaqueData.empty()),
            ResourceActivationState.ACTIVE);

        projection.apply(ReSyncResourceType.FLOW, live, false, 1);
        projection.apply(ReSyncResourceType.FLOW, GraphResourceState.tombstone(resource, 7, MUTATION,
            new ContentHash("e".repeat(64))), false, 1);
        projection.apply(ReSyncResourceType.FLOW, live, false, 1);

        assertTrue(projection.authoritative(SERVER.canonicalText(), ReSyncResourceType.FLOW, "ordered"));
        assertTrue(projection.tombstoned(SERVER.canonicalText(), ReSyncResourceType.FLOW, "ordered"));
        assertTrue(projection.graph(SERVER.canonicalText(), ReSyncResourceType.FLOW, "ordered").isEmpty());
    }

    @Test
    void inspectorProjectionKeepsExactTypedStatesLocatorsAndOpaqueData() {
        ServerResourceLocator resource = resource("flow", "inspector-projection");
        ServerResourceLocator selected = resource("gui", "selected-menu");
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        TypeExpr resourceType = TypeExpr.resource(TypeReference.of("restudio.resync", "gui"));
        TypeExpr.OpaqueType opaqueType = TypeExpr.opaque(TypeReference.of("future", "structured-field"));
        InspectorFieldId absentId = InspectorFieldId.of("absent");
        InspectorFieldId nullId = InspectorFieldId.of("null-value");
        InspectorFieldId valueId = InspectorFieldId.of("title");
        InspectorFieldId locatorId = InspectorFieldId.of("menu");
        InspectorFieldId opaqueId = InspectorFieldId.of("future");
        Map<InspectorFieldId, TypedValue> fields = Map.of(
            absentId, TypedValue.absent(string, Map.of("absenceFuture", List.of("keep"))),
            nullId, TypedValue.nullValue(string, Map.of("nullFuture", true)),
            valueId, TypedValue.value(string, "Title", Map.of("valueFuture", Map.of("nested", "keep"))),
            locatorId, TypedValue.locator(resourceType, selected, Map.of("locatorFuture", "keep")),
            opaqueId, TypedValue.opaque(opaqueType,
                Map.of("raw", List.of("unknown", Map.of("deep", true))), Map.of("typedFuture", 7)));
        GraphNode node = node(Map.of(), fields);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(node),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("graphFuture", "keep")));
        CoreGraphUiProjection projection = new CoreGraphUiProjection();

        CoreGraphUiProjection.InspectorProjection inspector = projection.projectInspector(
            new CoreGraphEditorSession(graph), node.instanceId()).orElseThrow();

        assertEquals(node.instanceId(), inspector.nodeId());
        assertEquals(fields.keySet(), inspector.fields().keySet());
        fields.forEach((fieldId, typedValue) -> assertEquals(typedValue,
            inspector.field(fieldId).orElseThrow().typedValue()));
        assertTrue(inspector.field(absentId).orElseThrow().absent());
        assertTrue(inspector.field(nullId).orElseThrow().nullValue());
        assertEquals("Title", inspector.field(valueId).orElseThrow().value());
        CoreGraphUiProjection.InspectorValue locator = inspector.field(locatorId).orElseThrow();
        assertTrue(locator.locatorValue());
        assertEquals(selected, locator.locator());
        assertEquals(SERVER, locator.locator().serverId());
        assertEquals("gui", locator.locator().resourceType().value());
        assertEquals("selected-menu", locator.locator().id());
        assertTrue(inspector.field(opaqueId).orElseThrow().opaque());
        assertEquals(fields.get(opaqueId).canonicalJson(), inspector.field(opaqueId).orElseThrow().canonicalJson());
        assertFalse(inspector.contains(InspectorFieldId.of("default-only")));
        assertThrows(UnsupportedOperationException.class,
            () -> inspector.fields().put(valueId, inspector.field(valueId).orElseThrow()));
    }

    @Test
    void descriptorTypeProjectionPreservesNestedAndReferenceUnknownData() {
        Map<String, Object> descriptor = Map.of(
            "kind", "optional",
            "wrapperFuture", Map.of("keep", true),
            "element", Map.of(
                "kind", "resource",
                "resourceFuture", List.of("keep"),
                "resourceType", Map.of(
                    "ownerId", "extension.generic",
                    "localId", "scene",
                    "referenceFuture", Map.of("deep", true))));

        TypeExpr projected = CoreGraphUiProjection.descriptorType(descriptor);

        assertEquals(descriptor, projected.canonicalValue());
    }

    @Test
    void inspectorEditsUseSessionMutationOwnerAndPreserveUneditedCanonicalState() {
        ServerResourceLocator resource = resource("flow", "inspector-edit");
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        TypeExpr resourceType = TypeExpr.resource(TypeReference.of("restudio.resync", "gui"));
        TypeExpr.OpaqueType opaqueType = TypeExpr.opaque(TypeReference.of("future", "structured-field"));
        InspectorFieldId titleId = InspectorFieldId.of("title");
        InspectorFieldId locatorId = InspectorFieldId.of("menu");
        InspectorFieldId absentId = InspectorFieldId.of("optional-label");
        InspectorFieldId opaqueId = InspectorFieldId.of("future");
        ServerResourceLocator originalLocator = resource("gui", "first-menu");
        ServerResourceLocator replacementLocator = resource("gui", "second-menu");
        TypedValue locator = TypedValue.locator(resourceType, originalLocator,
            Map.of("locatorFuture", Map.of("preserve", true)));
        TypedValue absent = TypedValue.absent(string, Map.of("absenceFuture", true));
        TypedValue opaque = TypedValue.opaque(opaqueType,
            Map.of("raw", List.of(1, 2, 3)), Map.of("typedFuture", "keep"));
        Map<InspectorFieldId, TypedValue> fields = Map.of(
            titleId, TypedValue.value(string, "Before", Map.of("valueFuture", "keep")),
            locatorId, locator,
            absentId, absent,
            opaqueId, opaque);
        GraphNode node = node(Map.of(), fields);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(node),
            List.of(), List.of(), List.of(), OpaqueData.of(Map.of("graphFuture", List.of("keep"))));
        CoreGraphEditorSession session = new CoreGraphEditorSession(graph);
        CoreGraphUiProjection projection = new CoreGraphUiProjection();
        CoreGraphUiProjection.InspectorProjection initial = projection.projectInspector(session, node.instanceId())
            .orElseThrow();

        projection.setInspectorValue(session, node.instanceId(), initial.field(titleId).orElseThrow().withValue("After"));
        projection.setInspectorValue(session, node.instanceId(), initial.field(locatorId).orElseThrow()
            .withLocator(replacementLocator));
        projection.setInspectorValue(session, node.instanceId(), initial.field(opaqueId).orElseThrow()
            .withOpaque(Map.of("raw", List.of(4, 5, 6))));
        projection.removeInspectorValue(session, node.instanceId(), absentId);

        Map<InspectorFieldId, TypedValue> edited = session.graphDocument().nodes().getFirst().inspectorFields();
        assertEquals("After", edited.get(titleId).value());
        assertEquals(fields.get(titleId).type(), edited.get(titleId).type());
        assertEquals(fields.get(titleId).unknown(), edited.get(titleId).unknown());
        assertEquals(replacementLocator, edited.get(locatorId).locator());
        assertEquals(locator.type(), edited.get(locatorId).type());
        assertEquals(locator.unknown(), edited.get(locatorId).unknown());
        assertEquals(CanonicalJson.canonicalize(Map.of("raw", List.of(4, 5, 6))),
            CanonicalJson.canonicalize(edited.get(opaqueId).value()));
        assertEquals(opaque.type(), edited.get(opaqueId).type());
        assertEquals(opaque.unknown(), edited.get(opaqueId).unknown());
        assertFalse(edited.containsKey(absentId));
        assertEquals(graph.unknown(), session.graphDocument().unknown());
        assertTrue(session.dirty());

        CoreGraphUiProjection.InspectorValue wrongType = new CoreGraphUiProjection.InspectorValue(titleId,
            TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "integer")), 4,
                fields.get(titleId).unknown()));
        assertThrows(IllegalArgumentException.class,
            () -> projection.setInspectorValue(session, node.instanceId(), wrongType));
        CoreGraphUiProjection.InspectorValue droppedUnknown = new CoreGraphUiProjection.InspectorValue(titleId,
            TypedValue.value(string, "Dropped Unknown"));
        assertThrows(IllegalArgumentException.class,
            () -> projection.setInspectorValue(session, node.instanceId(), droppedUnknown));
        assertEquals("After", session.graphDocument().nodes().getFirst().inspectorFields().get(titleId).value());
        assertEquals(locator.canonicalJson(), projection.projectInspectorField(new CoreGraphEditorSession(graph),
            node.instanceId(), locatorId).orElseThrow().canonicalJson());
    }

    private static GraphDocument graph(ServerResourceLocator resource, OpaqueData unknown) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, 4, BINDING, Set.of(), List.of(), List.of(),
            List.of(), List.of(), unknown);
    }

    private static GraphDocument populatedGraph(ServerResourceLocator resource) {
        PinId pinId = PinId.of("message");
        TypedValue value = TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), "before",
            Map.of("typedFuture", "kept"));
        GraphNode node = node(Map.of(pinId, new PinValue(pinId, value, OpaqueData.of(Map.of("pinFuture", "kept")))));
        GraphEndpoint source = new GraphEndpoint(node.instanceId(), PinId.of("out"), null, null,
            OpaqueData.of(Map.of("endpointFuture", "source")));
        GraphEndpoint target = new GraphEndpoint(node.instanceId(), PinId.of("in"));
        GraphConnection connection = new GraphConnection(ConnectionId.deterministic("editable-connection"), source, target,
            OpaqueData.of(Map.of("connectionFuture", true)));
        return new GraphDocument(new CatalogVersion(1, 0), resource, 8, BINDING, Set.of(), List.of(node),
            List.of(connection), List.of(), List.of(), OpaqueData.of(Map.of("graphFuture", "kept")));
    }

    private static GraphNode node(Map<PinId, PinValue> values) {
        return node(values, Map.of());
    }

    private static GraphNode node(Map<PinId, PinValue> values, Map<InspectorFieldId, TypedValue> inspector) {
        return new GraphNode(NodeInstanceId.deterministic("editable-node"), ContractRef.of(OWNER, NodeId.of("test_node")),
            2, null, values, inspector, List.of(), List.of(), InspectorState.empty(), 12.5D, 24.5D,
            OpaqueData.of(Map.of("nodeFuture", true)));
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }
}
