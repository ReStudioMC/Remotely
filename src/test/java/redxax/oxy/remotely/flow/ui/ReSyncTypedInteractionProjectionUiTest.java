package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.ReSyncProductionDescriptorFixture;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationProjection;
import redxax.oxy.remotely.data.flow.ReSyncGenericDescriptorProjection;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.WidgetCleanup;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncTypedInteractionProjectionUiTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final ServerId OTHER_SERVER = new ServerId(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final ContentHash BINDING_HASH = new ContentHash("f".repeat(64));

    @BeforeAll
    static void initializeRendering() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void readOnlyInspectorOpensAndRetirementDetachesItsPopup() throws Exception {
        ReSyncGenericDescriptorProjection.Field editor = new ReSyncGenericDescriptorProjection.Field(
            "field:title", "Title", "Title value.", ReSyncGenericDescriptorProjection.EditorKind.TEXT,
            CanonicalJson.canonicalize(named("string")), "test/generic-editor", true, "");
        ReSyncGenericDescriptorProjection.InspectorField field = new ReSyncGenericDescriptorProjection.InspectorField(
            "title", "field:title", "Title", "Title value.", "scalar", named("string"), null,
            null, null, null, null, false, editor, List.of());
        ReSyncGenericDescriptorProjection.Inspector inspector = new ReSyncGenericDescriptorProjection.Inspector(
            "generic", "Inspector", "Read-only inspector.", List.of(new ReSyncGenericDescriptorProjection.InspectorSection(
            "main", "Main", "Main fields.", List.of(new ReSyncGenericDescriptorProjection.InspectorRow(
            "row", "Row", "Row fields.", List.of(field), null, null, false)), null, null, false)),
            null, null, false);
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").build();
        FlowNode node = new FlowNode("test:value", 20, 20, new LinkedHashMap<>());
        FlowGraph graph = new FlowGraph();
        String nodeId = NodeInstanceId.deterministic("read-only-inspector").canonicalText();
        graph.setNodes(new LinkedHashMap<>(Map.of(nodeId, node)));
        FlowNodeWidget widget = new FlowNodeWidget(20, 20, node, graph, nodeId, null, null, null,
            FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), definition, true, false, null);
        widget.configureInspector(inspector, null, null);
        widget.render(new TestDrawContext(), 0, 0, 0F);
        Field buttonField = NodeWidget.class.getDeclaredField("inspectorButton");
        buttonField.setAccessible(true);
        AnimatedButton button = (AnimatedButton) buttonField.get(widget);
        Screen previous = ScreenManager.currentScreen;
        FlowGraphDesignerScreen screen = new FlowGraphDesignerScreen(new FlowGraph(), null, null);
        ScreenManager.currentScreen = screen;
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        overlay.clearAllPopups();
        try {
            ReMouseEvent click = new ReMouseEvent(widget, widget, 0L, ReModifierState.none(),
                ReMouseEvent.Action.PRESSED, button.getX() + 1, button.getY() + 1, 0, 0,
                ReMouseButton.LEFT, 0, 1);

            assertTrue(widget.mouseClicked(click));
            assertTrue(overlay.hasOpenPopups());
            assertEquals(1, overlay.getScreenWidgetRoots().stream().filter(PopupWidget.class::isInstance).count());

            for (int attempt = 0; attempt < 3; attempt++) {
                widget.configureInspector(inspector, null, null);
                assertFalse(overlay.hasOpenPopups());
                assertFalse(overlay.getScreenWidgetRoots().stream().anyMatch(PopupWidget.class::isInstance));
                widget.showInspectorPopup();
                assertTrue(overlay.hasOpenPopups());
                assertEquals(1, overlay.getScreenWidgetRoots().stream().filter(PopupWidget.class::isInstance).count());
            }

            PopupWidget popup = (PopupWidget) overlay.getScreenWidgetRoots().stream()
                .filter(PopupWidget.class::isInstance).findFirst().orElseThrow();
            assertEquals(screen, popup.getOwnerScreen());
            assertTrue(popup.isManagedByOverlay());
            popup.onClose.run();
            assertFalse(overlay.hasOpenPopups());
            assertFalse(overlay.getScreenWidgetRoots().stream().anyMatch(PopupWidget.class::isInstance));

            widget.showInspectorPopup();
            WidgetCleanup.cleanup(widget);

            assertFalse(overlay.hasOpenPopups());
            assertFalse(overlay.getScreenWidgetRoots().stream().anyMatch(PopupWidget.class::isInstance));
        } finally {
            overlay.clearAllPopups();
            ScreenManager.currentScreen = previous;
        }
    }

    @Test
    void productionPropertySelectorCallbacksRebuildConditionalTypedOutputsWithoutEditingTheOldProjection() throws Exception {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();
        Screen previous = ScreenManager.currentScreen;
        SelectorScreen screen = new SelectorScreen();
        ScreenManager.currentScreen = screen;
        try {
            for (String id : List.of("player.properties", "entity.properties")) {
                boolean entity = id.startsWith("entity.");
                String prefix = entity ? "input_" : "";
                String outputPrefix = entity ? "output_" : "";
                NodeDefinition definition = ReSyncProductionDescriptorFixture.widget(interaction, id).definition();
                String nodeId = NodeInstanceId.deterministic(id).canonicalText();
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                for (NodeDefinition.PinDefinition pin : definition.getInputs()) {
                    if (pin.getDefaultValue() != null) {
                        values.put(pin.getName(), pin.getDefaultValue());
                    }
                }
                FlowNode node = new FlowNode("restudio.resync:" + id, 20, 20, values);
                AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
                NodeWidget widget = propertyWidget(node, nodeId, definition, proposal);

                assertEquals(List.of(outputPrefix + (entity ? "name" : "location")), widget.getVisibleOutputPins());
                assertTrue(widget.getVisibleInputPins().contains(prefix + "target"));
                assertNotNull(widget.getPinBounds(prefix + "target", true));
                assertFalse(inputWidgets(widget).containsKey(prefix + "target"));

                for (String property : List.of("health", entity ? "passengers" : "name")) {
                    Map<String, Object> oldValues = Map.copyOf(node.getInputValues());
                    AnimatedButton selector = (AnimatedButton) inputWidgets(widget).get(prefix + "property");
                    assertNotNull(selector);
                    selector.onClick(selector.getX() + 1, selector.getY() + 1, 0);
                    screen.choose(property);

                    assertEquals(prefix + "property", proposal.get().pinId().canonicalText());
                    assertEquals(property, proposal.get().value());
                    assertEquals(oldValues, node.getInputValues());
                    values = new LinkedHashMap<>(oldValues);
                    values.put(proposal.get().pinId().canonicalText(), proposal.get().value());
                    node = new FlowNode("restudio.resync:" + id, 20, 20, values);
                    widget = propertyWidget(node, nodeId, definition, proposal);
                    assertEquals(List.of(outputPrefix + property), widget.getVisibleOutputPins());
                    assertNotNull(widget.getPinBounds(outputPrefix + property, false));
                }

                @SuppressWarnings("unchecked")
                DropDownWidget<String> action = (DropDownWidget<String>) inputWidgets(widget).get(prefix + "action");
                action.setSelectedItem("has");
                assertEquals("has", proposal.get().value());
                assertEquals("get", node.getInputValues().get(prefix + "action"));
                values = new LinkedHashMap<>(node.getInputValues());
                values.put(prefix + "action", proposal.get().value());
                NodeWidget has = propertyWidget(new FlowNode("restudio.resync:" + id, 20, 20, values), nodeId, definition, proposal);
                assertEquals(List.of(outputPrefix + "has"), has.getVisibleOutputPins());
            }
        } finally {
            ScreenManager.currentScreen = previous;
        }
    }

    @Test
    void commandSchemaThreeRendersAllOutputsAndWiresArgumentCountWithoutLiteralFields() throws Exception {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();
        String commandId = NodeInstanceId.deterministic("command-event").canonicalText();
        String weatherId = NodeInstanceId.deterministic("command-count-target").canonicalText();
        FlowNode command = new FlowNode("restudio.resync:event.command", 0, 0, new LinkedHashMap<>());
        FlowNode weather = new FlowNode("restudio.resync:world_set_weather", 300, 0,
            new LinkedHashMap<>(Map.of("weather_type", "clear")));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(commandId, command, weatherId, weather)));
        graph.getConnections().add(FlowConnection.stable(commandId, "event.args_count", weatherId, "duration_ticks"));
        NodeDefinition source = ReSyncProductionDescriptorFixture.widget(interaction, "event.command").definition();
        NodeDefinition target = ReSyncProductionDescriptorFixture.widget(interaction, "world_set_weather").definition();
        NodeWidget commandWidget = new NodeWidget(0, 0, command, graph, commandId, null, null, null, source, false, true, mutation -> false);
        NodeWidget weatherWidget = new NodeWidget(300, 0, weather, graph, weatherId, null, null, null, target, false, true, mutation -> false);
        commandWidget.render(new TestDrawContext(), 0, 0, 0F);
        weatherWidget.render(new TestDrawContext(), 0, 0, 0F);

        assertEquals(ReSyncProductionDescriptorFixture.commandOutputTypes().keySet(), Set.copyOf(commandWidget.getVisibleOutputPins()));
        assertTrue(commandWidget.getVisibleInputPins().isEmpty());
        assertTrue(inputWidgets(commandWidget).isEmpty());
        for (String output : ReSyncProductionDescriptorFixture.commandOutputTypes().keySet()) {
            assertNotNull(commandWidget.getPinBounds(output, false), output);
        }
        assertNotNull(weatherWidget.getPinBounds("duration_ticks", true));
        assertEquals(interaction.pinType(ReSyncProductionDescriptorFixture.entry("event.command").definitionKey(), "event.args_count", false),
            interaction.pinType(ReSyncProductionDescriptorFixture.entry("world_set_weather").definitionKey(), "duration_ticks", true));
        assertEquals("event.args_count", graph.getConnections().getFirst().getSourcePinId());
        assertEquals("duration_ticks", graph.getConnections().getFirst().getTargetPinId());
        assertTrue(command.getInputValues().isEmpty());
    }

    @Test
    void eventOutputConnectsToRequiredPlayerTargetWithoutALiteralWidget() throws Exception {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();
        String eventId = NodeInstanceId.deterministic("event-source").canonicalText();
        String playerId = NodeInstanceId.deterministic("player-target").canonicalText();
        FlowNode event = new FlowNode("restudio.resync:event.block.break", 0, 0, new LinkedHashMap<>());
        FlowNode player = new FlowNode("restudio.resync:player.properties", 300, 0,
            new LinkedHashMap<>(Map.of("action", "get", "property", "health")));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(eventId, event, playerId, player)));
        graph.getConnections().add(FlowConnection.stable(eventId, "event.player", playerId, "target"));
        NodeDefinition source = ReSyncProductionDescriptorFixture.widget(interaction, "event.block.break").definition();
        NodeDefinition target = ReSyncProductionDescriptorFixture.widget(interaction, "player.properties").definition();
        NodeWidget eventWidget = new NodeWidget(0, 0, event, graph, eventId, null, null, null, source, false, true, mutation -> false);
        NodeWidget playerWidget = new NodeWidget(300, 0, player, graph, playerId, null, null, null, target, false, true, mutation -> false);
        eventWidget.render(new TestDrawContext(), 0, 0, 0F);
        playerWidget.render(new TestDrawContext(), 0, 0, 0F);

        assertNotNull(eventWidget.getPinBounds("event.player", false));
        assertNotNull(playerWidget.getPinBounds("target", true));
        assertEquals(List.of("health"), playerWidget.getVisibleOutputPins());
        assertFalse(inputWidgets(playerWidget).containsKey("target"));
        assertEquals("event.player", graph.getConnections().getFirst().getSourcePinId());
        assertEquals("target", graph.getConnections().getFirst().getTargetPinId());
    }

    @Test
    void nominalResourcesAndGenericWiresRetainGeometryAndDisplayGraphSerialization() throws Exception {
        Map<String, Object> variable = Map.of("kind", "named", "type", Map.of("ownerId", "type", "localId", "t"),
            "arguments", List.of());
        Map<String, Map<String, Object>> types = new LinkedHashMap<>();
        types.put("resource", resource("builtin", "gui"));
        types.put("value", variable);
        types.put("list", Map.of("kind", "list", "element", variable));
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 3, new ContentHash("d".repeat(64)), BINDING_HASH,
            CatalogProjectionVersion.current());
        CatalogCachePublication value = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 3, List.of(
            entry("roles.source", types.entrySet().stream().map(type -> pin(type.getKey(), "output", type.getValue())).toList(), Map.of()),
            entry("roles.target", types.entrySet().stream().map(type -> pin(type.getKey(), "input", type.getValue())).toList(), Map.of())));
        ReSyncCatalogPublicationProjection publication = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(publication.acknowledgeActiveKey(key));
        assertTrue(publication.apply(value, new CatalogCachePublicationCodec().encodeBytes(value)));
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(publication.active().orElseThrow());
        NodeDefinition source = interaction.widgetDefinition(ContractRef.of(OwnerId.of("builtin"), NodeId.of("roles.source")))
            .orElseThrow().definition();
        NodeDefinition target = interaction.widgetDefinition(ContractRef.of(OwnerId.of("builtin"), NodeId.of("roles.target")))
            .orElseThrow().definition();
        String sourceId = NodeInstanceId.deterministic("role-source").canonicalText();
        String targetId = NodeInstanceId.deterministic("role-target").canonicalText();
        FlowNode sourceNode = new FlowNode("builtin:roles.source", 0, 0, new LinkedHashMap<>());
        FlowNode targetNode = new FlowNode("builtin:roles.target", 300, 0, new LinkedHashMap<>());
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(sourceId, sourceNode, targetId, targetNode)));
        for (String pin : types.keySet()) {
            graph.getConnections().add(FlowConnection.stable(sourceId, pin, targetId, pin));
        }
        NodeWidget sourceWidget = new NodeWidget(0, 0, sourceNode, graph, sourceId, null, null, null, source, false, true, mutation -> false);
        NodeWidget targetWidget = new NodeWidget(300, 0, targetNode, graph, targetId, null, null, null, target, false, true, mutation -> false);
        sourceWidget.render(new TestDrawContext(), 0, 0, 0F);
        targetWidget.render(new TestDrawContext(), 0, 0, 0F);
        Map<String, String> expected = Map.of("resource", "resource_reference<builtin:gui>", "value", "type:t", "list", "list<type:t>");
        for (NodeDefinition.PinDefinition pin : target.getInputs()) {
            String id = pin.getName();
            assertNotNull(sourceWidget.getPinBounds(id, false), id);
            assertNotNull(targetWidget.getPinBounds(id, true), id);
            assertEquals(expected.get(id), sourceWidget.getPinTypeRef(id, false).toString());
            assertEquals(pin.getTypeRef(), targetWidget.getPinTypeRef(id, true));
            assertTrue(targetWidget.getPinTypeRef(id, true).isAssignableFrom(sourceWidget.getPinTypeRef(id, false)));
            FlowGraph.FunctionParameter parameter = FlowGraph.FunctionParameter.stable(id, id, pin.getDataType());
            parameter.setTypeRef(pin.getTypeRef());
            graph.getFunctionInputs().add(parameter);
        }
        assertFalse(inputWidgets(targetWidget).containsKey("value"));
        assertFalse(inputWidgets(targetWidget).containsKey("list"));
        assertFalse(targetWidget.getPinTypeRef("resource", true)
            .isAssignableFrom(FlowTypeRef.parse("resource_reference<extension.generic:gui>")));
        FlowGraph reopened = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        for (FlowGraph.FunctionParameter parameter : reopened.getFunctionInputs()) {
            assertEquals(expected.get(parameter.getParameterId()), parameter.getTypeRef().toString());
        }
        assertEquals(List.copyOf(types.keySet()), reopened.getConnections().stream().map(FlowConnection::getSourcePinId).toList());
        assertEquals(List.copyOf(types.keySet()), reopened.getConnections().stream().map(FlowConnection::getTargetPinId).toList());
        assertTrue(reopened.getNodes().get(targetId).getInputValues().isEmpty());
    }

    @Test
    void exactTypedResourceDropRemainsAuthorableAfterReopenAndRejectsEveryNearMatch() {
        String resourceOwner = "extension.resources";
        String resourceType = "flow";
        Map<String, Object> editableContribution = Map.of("resourceOwner", resourceOwner,
            "resourceType", resourceType, "capability", "reference", "owner", "builtin", "nodeId",
            "resource.editable", "inputPin", "value", "referenceKind", resourceOwner + ":" + resourceType,
            "referenceOwner", resourceOwner, "priority", 1);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 4, new ContentHash("e".repeat(64)), BINDING_HASH,
            CatalogProjectionVersion.current());
        CatalogCachePublication value = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 4, List.of(
            entry("resource.consume", List.of(
                pin("resource", "input", resource(resourceOwner, resourceType))), Map.of()),
            entry("resource.ambiguous", List.of(
                pin("resource", "input", resource(resourceOwner, resourceType)),
                pin("unsupported", "input", resource("extension.resources", "other"))), Map.of()),
            entry("resource.editable", List.of(pin("value", "input", named("string"))),
                Map.of("dropContributions", List.of(editableContribution)))));
        ReSyncCatalogPublicationProjection publication = new ReSyncCatalogPublicationProjection(SERVER);
        assertTrue(publication.acknowledgeActiveKey(key));
        assertTrue(publication.apply(value, new CatalogCachePublicationCodec().encodeBytes(value)));
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(
            publication.active().orElseThrow());
        ContractRef<NodeId> target = ContractRef.of(OwnerId.of("builtin"), NodeId.of("resource.consume"));
        ContractRef<NodeId> ambiguous = ContractRef.of(OwnerId.of("builtin"), NodeId.of("resource.ambiguous"));
        ContractRef<NodeId> editable = ContractRef.of(OwnerId.of("builtin"), NodeId.of("resource.editable"));

        assertTrue(interaction.descriptor(target).orElseThrow().readOnly());
        assertTrue(interaction.widgetDefinition(target).orElseThrow().readOnly());
        assertTrue(interaction.dropContributions(ambiguous).isEmpty());
        assertTrue(interaction.dropContributions(editable).isEmpty());
        assertEquals(List.of(target), interaction.allDropContributions().stream()
            .map(ReSyncTypedInteractionProjection.DropContribution::target).toList());

        ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolveTyped(
            new ReSyncResourceDragPayload(resourceOwner + ":" + resourceType, "saved-flow", "Saved Flow", ""),
            interaction);
        assertTrue(result.isAvailable());
        ReSyncResourceDropCapabilities.DropSpec spec = result.spec();
        ReSyncTypedInteractionProjection.DropAuthoring authoring = interaction.dropAuthoring(
            spec.resourceOwner(), spec.resourceType(), spec.capability(), spec.target().typedIdentity(),
            spec.inputPin(), spec.referenceKind(), spec.referenceOwner(), SERVER).orElseThrow();
        Map<String, Object> inputs = authoring.inputValues("saved-flow", SERVER);
        assertFalse(inputs.isEmpty());

        String nodeId = NodeInstanceId.deterministic("typed-resource-drop").canonicalText();
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(nodeId,
            new FlowNode("builtin:resource.consume", 20, 20, new LinkedHashMap<>(inputs)))));
        FlowGraph reopened = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        Map<String, Object> reopenedInputs = reopened.getNodes().get(nodeId).getInputValues();

        assertTrue(interaction.droppedNodeDefinition(target, inputs, SERVER).isPresent());
        assertTrue(interaction.droppedNodeDefinition(target, reopenedInputs, SERVER).isPresent());
        assertTrue(interaction.dropAuthoring(spec.resourceOwner(), spec.resourceType(), spec.capability(),
            spec.target().typedIdentity(), spec.inputPin(), spec.referenceKind(), spec.referenceOwner(), OTHER_SERVER).isEmpty());
        assertTrue(interaction.droppedNodeDefinition(target, Map.of(), SERVER).isEmpty());
        assertTrue(interaction.droppedNodeDefinition(target, Map.of("resource",
            locator(SERVER, "forged.resources", resourceType, "saved-flow")), SERVER).isEmpty());
        assertTrue(interaction.droppedNodeDefinition(target, Map.of("resource",
            locator(OTHER_SERVER, resourceOwner, resourceType, "saved-flow")), SERVER).isEmpty());

        LinkedHashMap<String, Object> ignoredProvenance = new LinkedHashMap<>(locator(
            SERVER, resourceOwner, resourceType, "saved-flow"));
        ignoredProvenance.put("dropTargetNodeId", "forged");
        assertTrue(interaction.droppedNodeDefinition(target, Map.of("resource", ignoredProvenance), SERVER).isPresent());
    }

    private static NodeWidget propertyWidget(FlowNode node, String id, NodeDefinition definition,
                                             AtomicReference<NodeWidget.NodeValueMutation> proposal) {
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(id, node)));
        NodeWidget widget = new NodeWidget(20, 20, node, graph, id, null, null, null, definition, false, true, mutation -> {
            proposal.set(mutation);
            return true;
        });
        widget.render(new TestDrawContext(), 0, 0, 0F);
        return widget;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Widget> inputWidgets(NodeWidget widget) throws ReflectiveOperationException {
        Field field = NodeWidget.class.getDeclaredField("inputWidgets");
        field.setAccessible(true);
        return (Map<String, Widget>) field.get(widget);
    }

    private static final class SelectorScreen extends FlowGraphDesignerScreen {
        private List<String> options;
        private Consumer<String> selection;

        private SelectorScreen() {
            super(new FlowGraph(), null, null);
        }

        @Override
        public void showNodeInputSelectorAtScreen(List<String> options, String selected, Consumer<String> onSelected, int x, int y) {
            this.options = List.copyOf(options);
            this.selection = onSelected;
        }

        private void choose(String value) {
            assertNotNull(selection);
            assertTrue(options.contains(value));
            Consumer<String> callback = selection;
            selection = null;
            callback.accept(value);
        }
    }

    @Test
    void typedBoundaryAndDropResolutionIgnoreTheLegacyBundle() {
        ReSyncCatalogPublicationProjection publication = new ReSyncCatalogPublicationProjection(SERVER);
        CatalogCacheKey key = new CatalogCacheKey(SERVER, 3, new ContentHash("d".repeat(64)), BINDING_HASH,
            CatalogProjectionVersion.current());
        CatalogCachePublication value = publication(key);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        assertTrue(publication.apply(value, codec.encodeBytes(value)));
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(
            publication.active().orElseThrow());

        FlowNodeWidget.FunctionBoundaryCatalog typed = FlowNodeWidget.fromTypedProjection(interaction);
        assertNull(typed.intent(new FlowNode("extension-stale:function.start", 0, 0, Map.of())));
        assertEquals("next", typed.intent(new FlowNode("builtin:function.start", 0, 0, Map.of())).flowPin());
        assertEquals("builtin:function.start", typed.nodeReference(FlowNodeWidget.FunctionBoundaryRole.INPUTS));
        assertEquals("builtin:function.end", typed.nodeReference(FlowNodeWidget.FunctionBoundaryRole.OUTPUTS));

        ReSyncResourceDropCapabilities.DropResult result = ReSyncResourceDropCapabilities.resolveTyped(
            new ReSyncResourceDragPayload("flow", "flow-id", "Flow", ""), interaction);
        assertTrue(result.isAvailable());
        assertEquals("builtin:resource.consume", result.spec().nodeType());
        assertFalse(result.spec().nodeType().contains("extension-stale"));
    }

    @Test
    void whitespacePaddedTypedIdentitiesFailClosed() {
        assertTrue(FlowNodeWidget.isBuiltinFunctionStartType("builtin:function.start"));
        assertTrue(FlowNodeWidget.isBuiltinFunctionEndType("builtin:function.end"));
        assertFalse(FlowNodeWidget.isBuiltinFunctionStartType("function.start"));
        assertFalse(FlowNodeWidget.isBuiltinFunctionEndType("extension:function.end"));
        assertNull(FlowNodeWidget.typedNodeIdentity(" builtin", "function.start"));
        assertNull(FlowNodeWidget.typedNodeIdentity("builtin", " function.start"));
        assertNull(FlowNodeWidget.typedNodeIdentity("builtin", "builtin:function.start "));
        assertNull(newDropTarget("builtin", " builtin:function.start"));
    }

    private static ReSyncResourceDropCapabilities.DropTarget newDropTarget(String owner, String nodeId) {
        try {
            return new ReSyncResourceDropCapabilities.DropTarget(owner, nodeId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static CatalogCachePublication publication(CatalogCacheKey key) {
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 3, List.of(
            entry("function.start", List.of(pin("flow", "output", named("execution"))), Map.of("flowPin", "next", "role", "inputs")),
            entry("function.end", List.of(pin("flow", "input", named("execution"))), Map.of("flowPin", "previous", "role", "outputs")),
            entry("resource.consume", List.of(pin("resource", "input", resource("builtin", "flow"))), Map.of())));
    }

    private static CatalogCachePublication.Entry entry(String id, List<Map<String, Object>> pins,
                                                       Map<String, Object> boundary) {
        Map<String, Object> metadata = boundary.isEmpty() ? Map.of()
            : boundary.containsKey("dropContributions") ? boundary : Map.of("functionBoundary", boundary);
        Map<String, Object> descriptor = Map.of("kind", "node", "id", id, "displayName", id,
            "description", id, "domain", "flow", "family", "function", "pins", pins,
            "metadata", metadata, "inspector", Map.of("intent", "none", "sections", List.of()));
        String canonical = CanonicalJson.canonicalize(descriptor);
        return CatalogCachePublication.Entry.present(ContractRef.of(new OwnerId("builtin"), new NodeId(id)), 3,
            CatalogCacheState.ACTIVE, Set.of(), false, CatalogCacheOpaque.of(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> pin(String id, String direction, Map<String, Object> type) {
        return Map.of("kind", "pin", "id", id, "direction", direction, "type", type,
            "displayName", id, "description", id, "resourceRole", "",
            "editor", Map.of("ownerId", "builtin", "localId", "generic-editor"));
    }

    private static Map<String, Object> named(String id) {
        return Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", id), "arguments", List.of());
    }

    private static Map<String, Object> resource(String owner, String id) {
        return Map.of("kind", "resource", "resourceType", Map.of("ownerId", owner, "localId", id));
    }

    private static Map<String, Object> locator(ServerId server, String owner, String type, String id) {
        return new ServerResourceLocator(server,
            ContractRef.of(OwnerId.of(owner), ResourceTypeId.of(type)), id).canonicalValue();
    }
}
