package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionBoundaryCatalogTest {
    @BeforeAll
    static void initializeTheme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void catalogBoundaryResolvesParameterAndFlowPins() {
        FlowGraph graph = new FlowGraph();
        FlowGraph.FunctionParameter input = new FlowGraph.FunctionParameter("permissions", FlowDataType.LIST);
        input.setTypeRef(FlowTypeRef.parse("list<permission>"));
        graph.getFunctionInputs().add(input);
        FlowNode entry = new FlowNode("builtin:catalog.function.entry", 0, 0, Map.of());
        FlowNode exit = new FlowNode("builtin:catalog.function.exit", 0, 0, Map.of());
        FlowNodeWidget.FunctionBoundaryCatalog catalog = catalog();

        assertEquals(FlowTypeRef.parse("list<permission>"),
            FlowNodeWidget.resolveFunctionParameterType(entry, graph, "permissions", false, catalog));
        assertEquals("next", FlowNodeWidget.resolveFunctionFlowPin(entry, catalog, false));
        assertEquals("previous", FlowNodeWidget.resolveFunctionFlowPin(exit, catalog, true));
    }

    @Test
    void normalizationCreatesCatalogBoundaryReferencesAndFlowPins() {
        FlowGraph graph = new FlowGraph();
        graph.setFunction(true);

        CompactBindingSupport.normalizeFunction("server", graph, null, catalog());

        assertTrue(graph.getNodes().values().stream().anyMatch(node -> "builtin:catalog.function.entry".equals(node.getType())));
        assertTrue(graph.getNodes().values().stream().anyMatch(node -> "builtin:catalog.function.exit".equals(node.getType())));
        assertEquals(1, graph.getConnections().size());
        assertEquals("next", graph.getConnections().getFirst().getSourcePin());
        assertEquals("previous", graph.getConnections().getFirst().getTargetPin());
    }

    @Test
    void normalizationAddsBoundaryEdgeAlongsideUnrelatedConnections() {
        FlowGraph graph = new FlowGraph();
        graph.setFunction(true);
        String entryId = "entry";
        String exitId = "exit";
        graph.getNodes().put(entryId, new FlowNode("builtin:catalog.function.entry", 0, 0, Map.of()));
        graph.getNodes().put(exitId, new FlowNode("builtin:catalog.function.exit", 0, 0, Map.of()));
        graph.getNodes().put("other", new FlowNode("body.other", 0, 0, Map.of()));
        graph.getNodes().put("target", new FlowNode("body.target", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("other", "out", "target", "in"));

        CompactBindingSupport.normalizeFunction("server", graph, null, catalog());

        assertEquals(2, graph.getConnections().size());
        assertTrue(graph.getConnections().stream().anyMatch(connection -> entryId.equals(connection.getSourceNodeId())
            && "next".equals(connection.getSourcePin()) && exitId.equals(connection.getTargetNodeId())
            && "previous".equals(connection.getTargetPin())));
    }

    @Test
    void normalizationPreservesBoundaryPathThroughBodyNode() {
        FlowGraph graph = new FlowGraph();
        graph.setFunction(true);
        String entryId = "entry";
        String bodyId = "body";
        String exitId = "exit";
        graph.getNodes().put(entryId, new FlowNode("builtin:catalog.function.entry", 0, 0, Map.of()));
        graph.getNodes().put(bodyId, new FlowNode("body.node", 0, 0, Map.of()));
        graph.getNodes().put(exitId, new FlowNode("builtin:catalog.function.exit", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection(entryId, "next", bodyId, "in"));
        graph.getConnections().add(new FlowConnection(bodyId, "out", exitId, "previous"));

        CompactBindingSupport.normalizeFunction("server", graph, null, catalog());

        assertEquals(2, graph.getConnections().size());
        assertTrue(graph.getConnections().stream().noneMatch(connection -> entryId.equals(connection.getSourceNodeId())
            && exitId.equals(connection.getTargetNodeId())));
    }

    @Test
    void unavailableCatalogLeavesFunctionReadOnly() {
        FlowGraph graph = new FlowGraph();

        CompactBindingSupport.normalizeFunction("server", graph, null, FlowNodeWidget.FunctionBoundaryCatalog.unavailable());

        assertFalse(graph.isFunction());
        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getConnections().isEmpty());
    }

    @Test
    void graphMetadataCannotOverrideCatalogAuthority() {
        FlowGraph graph = new FlowGraph();
        graph.setFunction(true);
        Map<String, Object> properties = new HashMap<>();
        properties.put("functionBoundary", Map.of(
            "inputs", Map.of("nodeReference", "metadata.entry", "flowPin", "metadataNext"),
            "outputs", Map.of("nodeReference", "metadata.exit", "flowPin", "metadataPrevious")
        ));
        graph.setContentProperties(properties);

        CompactBindingSupport.normalizeFunction("server", graph, null, catalog());

        assertTrue(graph.getNodes().values().stream().anyMatch(node -> "builtin:catalog.function.entry".equals(node.getType())));
        assertTrue(graph.getNodes().values().stream().anyMatch(node -> "builtin:catalog.function.exit".equals(node.getType())));
        assertEquals("next", graph.getConnections().getFirst().getSourcePin());
        assertEquals("previous", graph.getConnections().getFirst().getTargetPin());
    }

    @Test
    void standaloneBoundaryPublicationCannotCreatePartialState() {
        String serverId = "catalog-publication-test";
        FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        try {
            assertFalse(FlowNodeWidget.FunctionBoundaryCatalog.publish(serverId, 1L, "checksum-1", catalog()));
            assertTrue(FlowNodeWidget.FunctionBoundaryCatalog.forServer(serverId).isReadOnly());
        } finally {
            FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        }
    }

    @Test
    void ownerQualifiedBoundaryDoesNotMatchAnUnqualifiedOrDifferentOwnerNode() {
        FlowNodeWidget.FunctionBoundaryIntent boundary = new FlowNodeWidget.FunctionBoundaryIntent(
            FlowNodeWidget.FunctionBoundaryRole.INPUTS,
            ContractRef.of(new OwnerId("extension-a"), new NodeId("shared-entry")), "next", List.of());
        FlowNodeWidget.FunctionBoundaryCatalog catalog = FlowNodeWidget.FunctionBoundaryCatalog.of(boundary,
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
                ContractRef.of(new OwnerId("extension-a"), new NodeId("shared-exit")), "previous", List.of()));

        assertTrue(catalog.intent(new FlowNode("extension-a:shared-entry", 0, 0, Map.of())) == boundary);
        assertFalse(catalog.intent(new FlowNode("shared-entry", 0, 0, Map.of())) == boundary);
        assertEquals("extension-a:shared-entry", boundary.nodeReference());
        assertEquals("extension-a/shared-entry", boundary.nodeIdentity().canonicalText());
        assertFalse(catalog.intent(new FlowNode("extension-b:shared-entry", 0, 0, Map.of())) == boundary);
    }

    @Test
    void definitionBoundariesAlwaysUseTheDefinitionOwner() {
        NodeDefinition input = new NodeDefinition.Builder("shared-entry", "Entry", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-a")
            .output("next", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .handlerConfig(Map.of("functionBoundary", Map.of("role", "inputs", "flowPin", "next")))
            .build();
        NodeDefinition output = new NodeDefinition.Builder("shared-exit", "Exit", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-a")
            .input("previous", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .handlerConfig(Map.of("functionBoundary", Map.of("role", "outputs", "flowPin", "previous")))
            .build();

        FlowNodeWidget.FunctionBoundaryCatalog catalog = FlowNodeWidget.FunctionBoundaryCatalog.fromDefinitions(List.of(input, output));

        assertTrue(catalog.isTypedProjectionAvailable());
        assertEquals("extension-a:shared-entry", catalog.nodeReference(FlowNodeWidget.FunctionBoundaryRole.INPUTS));
        assertEquals("extension-a:shared-exit", catalog.nodeReference(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS));
    }

    @Test
    void typedBoundaryIdentityIgnoresStaleLegacyRegistryDefinitions() {
        NodeRegistry registry = new NodeRegistry();
        registry.registerServerDefinition("typed-boundary-stale", new NodeDefinition.Builder(
            "shared-entry", "Stale Entry", NodeDefinition.NodeCategory.FLOW).owner("extension-b").build());
        FlowNodeWidget.FunctionBoundaryIntent boundary = new FlowNodeWidget.FunctionBoundaryIntent(
            FlowNodeWidget.FunctionBoundaryRole.INPUTS,
            ContractRef.of(new OwnerId("extension-a"), new NodeId("shared-entry")), "next", List.of());
        try {
            assertTrue(boundary.matches("extension-a:shared-entry"));
            assertFalse(boundary.matches("extension-b:shared-entry"));
        } finally {
            registry.clearServer("typed-boundary-stale");
        }
    }

    @Test
    void invalidTypedBoundaryIdentitiesFailClosed() {
        assertNull(FlowNodeWidget.typedNodeIdentity("invalid owner", "node"));
        assertNull(FlowNodeWidget.typedNodeIdentity("extension-a", "extension-b:node"));
        assertNull(FlowNodeWidget.typedNodeIdentity("extension-a", "extension-a:node:extra"));
        assertNull(FlowNodeWidget.typedNodeIdentity("extension-a", "Node?"));
    }

    @Test
    void standaloneUnavailableBoundaryPublicationCannotCreateState() {
        String serverId = "boundary-retention";
        FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        try {
            assertFalse(FlowNodeWidget.FunctionBoundaryCatalog.publish(serverId, 5L, "checksum-5",
                FlowNodeWidget.FunctionBoundaryCatalog.unavailable()));
            assertTrue(FlowNodeWidget.FunctionBoundaryCatalog.forServer(serverId).isReadOnly());
        } finally {
            FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
        }
    }

    @Test
    void canonicalBoundaryParameterPersistsStableIdentityAcrossRename() throws Exception {
        FlowGraph graph = new FlowGraph();
        graph.setFunction(true);
        FlowNode boundary = new FlowNode("builtin:function.start", 0, 0, new HashMap<>());
        NodeDefinition inputDefinition = boundaryDefinition("function.start", true, "next");
        NodeDefinition outputDefinition = boundaryDefinition("function.end", false, "previous");
        FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog = FlowNodeWidget.FunctionBoundaryCatalog.fromDefinitions(
            List.of(inputDefinition, outputDefinition));
        FlowNodeWidget widget = new FlowNodeWidget(0, 0, boundary, graph, "inputs", "typed:function-parameter",
            null, null, boundaryCatalog, inputDefinition, false, false);
        Method newParameterId = NodeWidget.class.getDeclaredMethod("newFunctionParameterId");
        newParameterId.setAccessible(true);
        String parameterId = (String) newParameterId.invoke(widget);
        FlowGraph.FunctionParameter parameter = new FlowGraph.FunctionParameter(parameterId, "before", FlowDataType.STRING);
        widget.getFunctionParameterList().add(parameter);
        widget.refreshInputWidgets();

        assertNotNull(parameterId);
        assertEquals(parameterId, FunctionParameterId.parseCanonicalText(parameterId).canonicalText());
        String pinId = "function-output-" + parameterId;
        assertTrue(widget.getVisibleOutputPins().contains(pinId));

        parameter.setName("after");
        widget.refreshInputWidgets();

        assertEquals(parameterId, graph.getFunctionInputs().getFirst().getParameterId());
        assertTrue(widget.getVisibleOutputPins().contains(pinId));
        assertFalse(widget.getVisibleOutputPins().contains("function-output-before"));
    }

    @Test
    void typedBoundaryBoundsAndHitTestingUseCatalogFlowPinId() {
        NodeDefinition inputDefinition = boundaryDefinition("function.start", true, "next");
        NodeDefinition outputDefinition = boundaryDefinition("function.end", false, "previous");
        FlowNodeWidget.FunctionBoundaryCatalog catalog = FlowNodeWidget.FunctionBoundaryCatalog.fromDefinitions(
            List.of(inputDefinition, outputDefinition));
        FlowNode start = new FlowNode("builtin:function.start", 0, 0, Map.of());
        FlowNodeWidget widget = new FlowNodeWidget(20, 30, start, new FlowGraph(), "start", "typed:worldgen", null, null,
            catalog, inputDefinition, false, false);

        assertEquals("next", catalog.flowPin(start, false));
        assertTrue(widget.getVisibleOutputPins().contains("next"));
        assertFalse(widget.getVisibleOutputPins().contains("flow"));
        double[] bounds = widget.getPinBounds("next", false);
        assertNotNull(bounds);
        assertEquals("next", widget.getPinAtPosition((int) bounds[0] + 5, (int) bounds[1] + 5));

        FlowNodeWidget legacyWidget = new FlowNodeWidget(20, 30, start, new FlowGraph(), "legacy-start",
            "legacy-boundary", null, null, catalog, inputDefinition, false, false);
        assertTrue(legacyWidget.getVisibleOutputPins().contains("flow"));
        assertFalse(legacyWidget.getVisibleOutputPins().contains("next"));
    }

    @Test
    void typedBoundaryCreatesParameterButtonAfterCatalogInitialization() throws Exception {
        NodeDefinition inputDefinition = new NodeDefinition.Builder("shared-entry", "Function Start", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-a")
            .output("next", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .handlerConfig(Map.of("functionBoundary", Map.of("role", "inputs", "flowPin", "next")))
            .build();
        NodeDefinition outputDefinition = new NodeDefinition.Builder("shared-exit", "Function End", NodeDefinition.NodeCategory.FLOW)
            .owner("extension-a")
            .input("previous", NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION)
            .handlerConfig(Map.of("functionBoundary", Map.of("role", "outputs", "flowPin", "previous")))
            .build();
        FlowNodeWidget.FunctionBoundaryCatalog catalog = FlowNodeWidget.FunctionBoundaryCatalog.fromDefinitions(
            List.of(inputDefinition, outputDefinition));
        FlowNode start = new FlowNode("extension-a:shared-entry", 0, 0, Map.of());

        FlowNodeWidget widget = new FlowNodeWidget(0, 0, start, new FlowGraph(), "start", "typed:custom-function",
            null, null, catalog, inputDefinition, false, false);

        Field paramButton = NodeWidget.class.getDeclaredField("paramButton");
        paramButton.setAccessible(true);
        assertNotNull(paramButton.get(widget));
    }

    @Test
    void legacyBooleanWidgetUsesToggle() {
        assertEquals(NodeDefinition.WidgetType.TOGGLE, NodeDefinition.WidgetType.fromSerializedName(" BoOlEaN "));
    }

    @Test
    void legacyBoundaryAliasesKeepParameterButtons() throws Exception {
        Field paramButton = NodeWidget.class.getDeclaredField("paramButton");
        paramButton.setAccessible(true);
        FlowGraph graph = new FlowGraph();
        graph.setFunction(true);
        FlowNodeWidget.FunctionBoundaryCatalog unavailable = FlowNodeWidget.FunctionBoundaryCatalog.unavailable();

        FlowNodeWidget start = new FlowNodeWidget(0, 0, new FlowNode("function_start", 0, 0, Map.of()), graph,
            "start", "legacy-boundary-alias", null, null, unavailable, boundaryDefinition("function_start", true, "flow"),
            false, false);
        FlowNodeWidget end = new FlowNodeWidget(0, 0, new FlowNode("function_end", 0, 0, Map.of()), graph,
            "end", "legacy-boundary-alias", null, null, unavailable, boundaryDefinition("function_end", false, "flow"),
            false, false);

        assertNotNull(paramButton.get(start));
        assertNotNull(paramButton.get(end));
    }

    @Test
    void shapedCoreFunctionGetsOneBoundaryConnection() {
        ContractRef<NodeId> inputDefinition = ContractRef.of(new OwnerId("extension-a"), new NodeId("shared-entry"));
        ContractRef<NodeId> outputDefinition = ContractRef.of(new OwnerId("extension-a"), new NodeId("shared-exit"));
        GraphNode input = new GraphNode(NodeInstanceId.deterministic("core-function-input"), inputDefinition, 1, Map.of());
        GraphNode output = new GraphNode(NodeInstanceId.deterministic("core-function-output"), outputDefinition, 1, Map.of());
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("function")), "predicate");
        CatalogBinding binding = new CatalogBinding(1, "0".repeat(64), "1".repeat(64));
        GraphDocument document = new GraphDocument(resource, 0, binding, List.of(input, output), List.of());
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(0),
            List.of(), List.of());
        CoreGraphEditorSession session = new CoreGraphEditorSession(new FunctionSourceDocument(signature, document));
        FlowNodeWidget.FunctionBoundaryCatalog catalog = FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS,
                inputDefinition, "next", List.of()),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
                outputDefinition, "previous", List.of()));

        assertTrue(CompactBindingSupport.ensureCoreBoundaryConnection(session, catalog, "predicate"));
        assertEquals(1, session.functionSourceDocument().graph().connections().size());
        assertTrue(CompactBindingSupport.ensureCoreBoundaryConnection(session, catalog, "predicate"));
        assertEquals(1, session.functionSourceDocument().graph().connections().size());
    }

    private FlowNodeWidget.FunctionBoundaryCatalog catalog() {
        return FlowNodeWidget.FunctionBoundaryCatalog.of(
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.INPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("catalog.function.entry")), "next", List.of()),
            new FlowNodeWidget.FunctionBoundaryIntent(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS,
                ContractRef.of(new OwnerId("builtin"), new NodeId("catalog.function.exit")), "previous", List.of())
        );
    }

    private static NodeDefinition boundaryDefinition(String id, boolean input, String flowPin) {
        NodeDefinition.Builder builder = new NodeDefinition.Builder(id, id, NodeDefinition.NodeCategory.FLOW)
            .owner("builtin")
            .handlerConfig(Map.of("functionBoundary", Map.of(
                "role", input ? "inputs" : "outputs",
                "flowPin", flowPin)));
        if (input) {
            builder.output(flowPin, NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
        } else {
            builder.input(flowPin, NodeDefinition.PinType.FLOW, FlowDataType.EXECUTION);
        }
        return builder.build();
    }
}
