package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.CoreGraphUiProjection;
import redxax.oxy.remotely.data.flow.ReSyncGenericDescriptorProjection;
import redxax.oxy.remotely.data.flow.ReSyncGenericWidgetCapabilities;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowResourceReference;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeWidgetCoreValueMutationTest {
    private static final NodeInstanceId NODE = NodeInstanceId.deterministic("core-widget-value");
    private static final PinId PIN = PinId.of("message");
    private static final PinId RESOURCE_PIN = PinId.of("resource");
    private static final String RESOURCE_OWNER = "test";
    private static final String RESOURCE_TYPE = "variable_definition";
    private static final String RESOURCE_ID = "score";
    private static final ServerId RESOURCE_SERVER = ServerId.deterministic("core-resource-selector");

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void typedDefaultsDoNotRetrofitExistingNodesDuringWidgetConstruction() {
        FlowNode node = new FlowNode("test:value", 20, 40, new LinkedHashMap<>());
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder(PIN, "Message",
            NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
            .typeRef(FlowTypeRef.simple("string"))
            .typedDefault(new NodeDefinition.TypedDefault("value", FlowTypeRef.simple("string"), "default"))
            .defaultValue("default")
            .build();
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();

        new NodeWidget(20, 40, node, graph, NODE.canonicalText(), null, null, null, definition, false);

        assertTrue(node.getInputValues().isEmpty());
    }

    @Test
    void inspectorStateTransitionsKeepEmptyNullAndAbsentDistinct() {
        InspectorFieldId field = InspectorFieldId.of("title");
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        var absent = new CoreGraphUiProjection.InspectorValue(
            field, TypedValue.absent(string, Map.of("future", true)));
        var value = new CoreGraphUiProjection.InspectorValue(
            field, TypedValue.value(string, "before", Map.of("future", true)));
        var nullValue = new CoreGraphUiProjection.InspectorValue(
            field, TypedValue.nullValue(string, Map.of("future", true)));

        NodeWidget.InspectorFieldMutation absentToEmpty = NodeWidget.inspectorReplacement(absent, null, null, "");
        NodeWidget.InspectorFieldMutation valueToEmpty = NodeWidget.inspectorReplacement(value, null, null, "");
        NodeWidget.InspectorFieldMutation valueToNull = NodeWidget.inspectorReplacement(
            value, TypedValue.State.NULL, null, null);
        NodeWidget.InspectorFieldMutation nullToAbsent = NodeWidget.inspectorReplacement(
            nullValue, TypedValue.State.ABSENT, null, null);

        assertEquals(TypedValue.State.VALUE, absentToEmpty.replacement().typedValue().state());
        assertEquals("", absentToEmpty.replacement().typedValue().value());
        assertEquals(TypedValue.State.VALUE, valueToEmpty.replacement().typedValue().state());
        assertEquals("", valueToEmpty.replacement().typedValue().value());
        assertEquals(TypedValue.State.NULL, valueToNull.replacement().typedValue().state());
        assertTrue(nullToAbsent.remove());
        assertEquals(Map.of("future", true), valueToNull.replacement().unknown());
        assertFalse(valueToNull.exactOption());
    }

    @Test
    void inspectorMutationRejectsReplacementTypeOrUnknownChanges() {
        InspectorFieldId field = InspectorFieldId.of("title");
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        TypeExpr number = TypeExpr.named(TypeReference.of("builtin", "number"));
        var original = new CoreGraphUiProjection.InspectorValue(
            field, TypedValue.absent(string, Map.of("future", true)));

        assertThrows(IllegalArgumentException.class, () -> new NodeWidget.InspectorFieldMutation(original,
            new CoreGraphUiProjection.InspectorValue(field,
                TypedValue.value(number, 1)), false));
        assertThrows(IllegalArgumentException.class, () -> new NodeWidget.InspectorFieldMutation(original,
            new CoreGraphUiProjection.InspectorValue(field,
                TypedValue.value(number, 1)), false, true));
        assertThrows(IllegalArgumentException.class, () -> new NodeWidget.InspectorFieldMutation(original,
            new CoreGraphUiProjection.InspectorValue(field,
                TypedValue.value(string, "changed", Map.of())), false));
    }

    @Test
    void exactInspectorOptionRetainsSelectedUnknownDataWhileManualReplacementCannotChangeIt() {
        InspectorFieldId field = InspectorFieldId.of("resource");
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        CoreGraphUiProjection.InspectorValue original = new CoreGraphUiProjection.InspectorValue(field,
            TypedValue.absent(string));
        TypedValue selected = TypedValue.value(string, "chosen", Map.of("optionFuture", Map.of("keep", true)));

        NodeWidget.InspectorFieldMutation mutation = NodeWidget.inspectorReplacement(original, null, selected, null);

        assertTrue(mutation.exactOption());
        assertSame(selected, mutation.replacement().typedValue());
        assertEquals(Map.of("optionFuture", Map.of("keep", true)), mutation.replacement().unknown());
        assertThrows(IllegalArgumentException.class, () -> new NodeWidget.InspectorFieldMutation(original,
            mutation.replacement(), false));
    }

    @Test
    void exactOptionMutationRetainsTheSelectedTypedValueObject() {
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        TypedValue selected = TypedValue.value(string, "chosen", Map.of("future", Map.of("keep", true)));

        NodeWidget.NodeValueMutation mutation = new NodeWidget.NodeValueMutation(NODE, PIN, selected);

        assertSame(selected, mutation.exactValue());
        assertEquals("chosen", mutation.value());
        assertFalse(mutation.remove());
    }

    @Test
    void realTextAndBackspaceKeepCanonicalStateUnchangedWhilePreviewingTheAdmittedValue() throws Exception {
        FlowNode node = stringNode();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = stringWidget(node, mutation -> {
            proposal.set(mutation);
            return true;
        });
        focusInput(widget);

        assertTrue(widget.textInput(text(widget, 'x')));
        assertEquals("oldx", proposal.get().value());
        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));
        assertEquals("oldx", stringInput(widget).getText());
        assertTrue(widget.hasInputValuePreview(PIN));
        widget.render(new TestDrawContext(), 0, 0, 0F);

        assertTrue(widget.keyPressed(key(widget, ReKey.BACKSPACE)));
        assertEquals("old", proposal.get().value());
        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));
        assertEquals("old", stringInput(widget).getText());
        assertTrue(widget.hasInputValuePreview(PIN));
    }

    @Test
    void realRejectedKeystrokeRestoresTheTypedProjection() throws Exception {
        FlowNode node = stringNode();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = stringWidget(node, mutation -> {
            proposal.set(mutation);
            return false;
        });
        focusInput(widget);

        assertTrue(widget.textInput(text(widget, 'x')));

        assertEquals("oldx", proposal.get().value());
        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));
        assertEquals("old", stringInput(widget).getText());
    }

    @Test
    void exactSuccessfulSettlementPreservesFocusedEligibleInputForTheNextProposal() throws Exception {
        FlowNode node = stringNode();
        List<NodeWidget.NodeValueMutation> proposals = new ArrayList<>();
        NodeWidget widget = stringWidget(node, mutation -> {
            proposals.add(mutation);
            return true;
        });
        focusInput(widget);
        TextInputWidget focused = stringInput(widget);

        assertTrue(widget.textInput(text(widget, 'x')));
        NodeWidget.NodeValueMutation committed = proposals.getLast();
        node.setInputValues(new LinkedHashMap<>(Map.of(PIN.canonicalText(), committed.value())));
        widget.refreshInputWidgets();
        widget.commitInputValuePreview(committed);

        assertFalse(widget.hasInputValuePreview(PIN));
        assertSame(focused, stringInput(widget));
        assertTrue(focused.isFocused());
        assertTrue(widget.textInput(text(widget, 'y')));
        assertEquals("oldxy", proposals.getLast().value());
        assertEquals("oldx", node.getInputValues().get(PIN.canonicalText()));
        assertTrue(widget.hasInputValuePreview(PIN));
    }

    @Test
    void realLegacyTextAndBackspaceStillUpdateTheProjectedNode() throws Exception {
        FlowNode node = stringNode();
        NodeWidget widget = stringWidget(node, null);
        focusInput(widget);

        assertTrue(widget.textInput(text(widget, 'x')));
        assertEquals("oldx", node.getInputValues().get(PIN.canonicalText()));
        assertTrue(widget.keyPressed(key(widget, ReKey.BACKSPACE)));
        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));
    }

    @Test
    void projectedWireOnlyAnyRetainsItsEndpointWithoutFabricatingATextEditor() throws Exception {
        Map<String, String> editor = Map.of("ownerId", "restudio.resync", "localId", "generic-editor");
        List<Map<String, Object>> pins = List.of(
            Map.of("id", "value", "displayName", "Value", "direction", "input", "editor", editor,
                "type", namedType("any")),
            Map.of("id", PIN.canonicalText(), "displayName", "Message", "direction", "input", "editor", editor,
                "type", namedType("string")));
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(
            ContractRef.of(OwnerId.of("test"), NodeId.of("value")), 1L, CatalogCacheState.ACTIVE, Set.of(), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", "value", "pins", pins))));
        ReSyncGenericDescriptorProjection.Projection projection = ReSyncGenericDescriptorProjection.open(entry,
            ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();
        assertFalse(projection.readOnly());
        assertEquals(List.of("pin:message"), projection.fields().stream().map(ReSyncGenericDescriptorProjection.Field::id).toList());
        NodeDefinition definition = ReSyncGenericWidgetCapabilities.from(projection).orElseThrow().definition();
        FlowNode node = stringNode();
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = new NodeWidget(20, 40, node, graph, NODE.canonicalText(), null, null, null,
            definition, false, false, mutation -> {
                proposal.set(mutation);
                return true;
            });
        widget.render(new TestDrawContext(), 0, 0, 0F);

        assertTrue(widget.getVisibleInputPins().contains("value"));
        assertNotNull(widget.getPinBounds("value", true));
        assertEquals(FlowDataType.ANY, widget.getPinType("value", true));
        assertFalse(inputWidgets(widget).containsKey("value"));
        assertNotNull(stringInput(widget));
        focusInput(widget);
        assertTrue(widget.textInput(text(widget, 'x')));
        assertEquals("oldx", proposal.get().value());
        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));

        NodeWidget legacy = new NodeWidget(20, 40, node, graph, NODE.canonicalText(), null, null, null,
            definition, false, false);
        legacy.render(new TestDrawContext(), 0, 0, 0F);
        assertTrue(inputWidgets(legacy).get("value") instanceof TextInputWidget);
    }

    @Test
    void distinctItemAndItemStackRetainObjectPinBehaviorInTypedAndLegacyWidgets() throws Exception {
        assertEquals("item", FlowDataType.ITEM.getId());
        assertEquals("itemstack", FlowDataType.ITEMSTACK.getId());
        Method objectPin = NodeWidget.class.getDeclaredMethod("isObjectPin", FlowDataType.class);
        objectPin.setAccessible(true);
        Method typedAuthority = NodeWidget.class.getDeclaredMethod("typedCatalogAuthorityActive");
        typedAuthority.setAccessible(true);
        for (boolean typed : List.of(false, true)) {
            NodeDefinition.Builder definition = new NodeDefinition.Builder("value", "Objects", NodeDefinition.NodeCategory.UTILITY)
                .owner("test");
            for (FlowDataType type : List.of(FlowDataType.ITEM, FlowDataType.ITEMSTACK)) {
                definition.input(new NodeDefinition.PinBuilder(PinId.of(type.getId()), type.getId(),
                    NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, type).build());
            }
            FlowNode node = new FlowNode("test:value", 20, 40, new LinkedHashMap<>());
            FlowGraph graph = new FlowGraph();
            graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
            NodeWidget widget = new NodeWidget(20, 40, node, graph, NODE.canonicalText(),
                typed ? "object-pins:worldgen" : "object-pins", null, null, definition.build(), false, false,
                typed ? mutation -> true : null);
            assertEquals(typed, typedAuthority.invoke(widget));
            widget.render(new TestDrawContext(), 0, 0, 0F);
            for (FlowDataType type : List.of(FlowDataType.ITEM, FlowDataType.ITEMSTACK)) {
                assertEquals(true, objectPin.invoke(widget, type));
                assertTrue(widget.getVisibleInputPins().contains(type.getId()));
                assertNotNull(widget.getPinBounds(type.getId(), true));
                assertEquals(type, widget.getPinType(type.getId(), true));
                assertFalse(inputWidgets(widget).containsKey(type.getId()));
            }
        }
    }

    @Test
    void coreValueEditProposesTypedMutationWithoutTouchingProjectedFlowNode() throws Exception {
        FlowNode node = new FlowNode("test:value", 20, 40, new LinkedHashMap<>(Map.of(PIN.canonicalText(), "old")));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        NodeDefinition.PinDefinition input = stringPin();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = new NodeWidget(20, 40, node, graph, NODE.canonicalText(), "server", null, null,
            definition, false, false, mutation -> {
                proposal.set(mutation);
                return true;
            });

        Field widgetsField = NodeWidget.class.getDeclaredField("inputWidgets");
        widgetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Widget> widgets = (Map<String, Widget>) widgetsField.get(widget);
        TextInputWidget inputWidget = (TextInputWidget) widgets.get(PIN.canonicalText());
        assertNotNull(inputWidget);
        inputWidget.setText("new");
        Method changed = NodeWidget.class.getDeclaredMethod("handleInputValueChanged",
            NodeDefinition.PinDefinition.class);
        changed.setAccessible(true);
        changed.invoke(widget, input);

        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));
        assertEquals(NODE, proposal.get().nodeId());
        assertEquals(PIN, proposal.get().pinId());
        assertEquals("new", proposal.get().value());
        assertEquals(false, proposal.get().remove());
    }

    @Test
    void legacyValueEditStillMutatesItsFlowNodeDirectly() throws Exception {
        FlowNode node = new FlowNode("test:value", 20, 40, new LinkedHashMap<>(Map.of(PIN.canonicalText(), "old")));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        NodeDefinition.PinDefinition input = stringPin();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        NodeWidget widget = new NodeWidget(20, 40, node, graph, NODE.canonicalText(), null, null, null,
            definition, false, false);

        Field widgetsField = NodeWidget.class.getDeclaredField("inputWidgets");
        widgetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Widget> widgets = (Map<String, Widget>) widgetsField.get(widget);
        TextInputWidget inputWidget = (TextInputWidget) widgets.get(PIN.canonicalText());
        inputWidget.setText("new");
        Method changed = NodeWidget.class.getDeclaredMethod("handleInputValueChanged",
            NodeDefinition.PinDefinition.class);
        changed.setAccessible(true);
        changed.invoke(widget, input);

        assertEquals("new", node.getInputValues().get(PIN.canonicalText()));
    }

    @Test
    void rejectedCoreValueProposalRestoresTheProjectedWidgetValue() throws Exception {
        FlowNode node = new FlowNode("test:value", 20, 40, new LinkedHashMap<>(Map.of(PIN.canonicalText(), "old")));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        NodeDefinition.PinDefinition input = stringPin();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        NodeWidget widget = new NodeWidget(20, 40, node, graph, NODE.canonicalText(), "server", null, null,
            definition, false, false, mutation -> false);

        Field widgetsField = NodeWidget.class.getDeclaredField("inputWidgets");
        widgetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Widget> widgets = (Map<String, Widget>) widgetsField.get(widget);
        TextInputWidget inputWidget = (TextInputWidget) widgets.get(PIN.canonicalText());
        inputWidget.setText("new");
        Method changed = NodeWidget.class.getDeclaredMethod("handleInputValueChanged",
            NodeDefinition.PinDefinition.class);
        changed.setAccessible(true);
        changed.invoke(widget, input);

        @SuppressWarnings("unchecked")
        Map<String, Widget> restored = (Map<String, Widget>) widgetsField.get(widget);
        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));
        assertEquals("old", ((TextInputWidget) restored.get(PIN.canonicalText())).getText());
    }

    @Test
    void admittedValuePreviewUpdatesConditionalPinsAndBoundsWithoutTouchingCanonicalTopology() throws Exception {
        FlowNode node = conditionalNode();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        AtomicInteger geometryInvalidations = new AtomicInteger();
        ConditionalWidget fixture = conditionalWidget(node, mutation -> {
            proposal.set(mutation);
            return true;
        }, geometryInvalidations::incrementAndGet);
        int initialHeight = fixture.widget().getHeight();

        proposeValue(fixture.widget(), fixture.mode(), "on");

        assertEquals("off", node.getInputValues().get("mode"));
        assertEquals("on", proposal.get().value());
        assertTrue(fixture.widget().hasInputValuePreview(PinId.of("mode")));
        assertTrue(fixture.widget().getVisibleInputPins().contains("detail"));
        assertTrue(fixture.widget().getVisibleOutputPins().contains("conditional-output"));
        assertNotNull(fixture.widget().getPinBounds("detail", true));
        assertNotNull(fixture.widget().getPinBounds("conditional-output", false));
        assertTrue(fixture.widget().getTargetHeight() > initialHeight);
        fixture.widget().tick();
        assertTrue(fixture.widget().getHeight() > initialHeight);
        assertEquals(1, fixture.graph().getConnections().size());
        assertEquals(1, geometryInvalidations.get());
    }

    @Test
    void rejectedValuePreviewRestoresAuthoritativeVisibilityBoundsAndConnections() throws Exception {
        FlowNode node = conditionalNode();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        ConditionalWidget fixture = conditionalWidget(node, mutation -> {
            proposal.set(mutation);
            return true;
        });
        int authoritativeHeight = fixture.widget().getHeight();
        proposeValue(fixture.widget(), fixture.mode(), "on");

        fixture.widget().rejectInputValuePreview(proposal.get());

        assertFalse(fixture.widget().hasInputValuePreview(PinId.of("mode")));
        assertFalse(fixture.widget().getVisibleInputPins().contains("detail"));
        assertFalse(fixture.widget().getVisibleOutputPins().contains("conditional-output"));
        assertNull(fixture.widget().getPinBounds("detail", true));
        assertNull(fixture.widget().getPinBounds("conditional-output", false));
        assertEquals(authoritativeHeight, fixture.widget().getHeight());
        assertEquals(1, fixture.graph().getConnections().size());
    }

    @Test
    void successfulProjectionSettlementConvergesAndClearsTheExactPreview() throws Exception {
        FlowNode node = conditionalNode();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        ConditionalWidget fixture = conditionalWidget(node, mutation -> {
            proposal.set(mutation);
            return true;
        });
        proposeValue(fixture.widget(), fixture.mode(), "on");

        node.setInputValues(new LinkedHashMap<>(Map.of("mode", "on")));
        fixture.widget().refreshInputWidgets();
        assertTrue(fixture.widget().hasInputValuePreview(PinId.of("mode")));
        fixture.widget().commitInputValuePreview(proposal.get());

        assertFalse(fixture.widget().hasInputValuePreview(PinId.of("mode")));
        assertEquals("on", node.getInputValues().get("mode"));
        assertTrue(fixture.widget().getVisibleInputPins().contains("detail"));
        assertTrue(fixture.widget().getVisibleOutputPins().contains("conditional-output"));
        assertEquals(1, fixture.graph().getConnections().size());
    }

    @Test
    void synchronousSuccessfulSettlementCannotRecreateItsPreview() throws Exception {
        FlowNode node = conditionalNode();
        AtomicReference<NodeWidget> owner = new AtomicReference<>();
        ConditionalWidget fixture = conditionalWidget(node, mutation -> {
            node.setInputValues(new LinkedHashMap<>(Map.of("mode", mutation.value())));
            owner.get().commitInputValuePreview(mutation);
            return true;
        });
        owner.set(fixture.widget());

        proposeValue(fixture.widget(), fixture.mode(), "on");

        assertFalse(fixture.widget().hasInputValuePreview(PinId.of("mode")));
        assertEquals("on", node.getInputValues().get("mode"));
        assertTrue(fixture.widget().getVisibleOutputPins().contains("conditional-output"));
    }

    @Test
    void abaSettlementUsesMutationIdentityAndFinalRejectionRestoresAuthoritativeB() throws Exception {
        FlowNode node = conditionalNode();
        List<NodeWidget.NodeValueMutation> proposals = new ArrayList<>();
        ConditionalWidget fixture = conditionalWidget(node, mutation -> {
            proposals.add(mutation);
            return true;
        });
        proposeValue(fixture.widget(), fixture.mode(), "a");
        proposeValue(fixture.widget(), fixture.mode(), "b");
        proposeValue(fixture.widget(), fixture.mode(), "a");
        assertNotEquals(proposals.getFirst().mutationId(), proposals.getLast().mutationId());

        node.setInputValues(new LinkedHashMap<>(Map.of("mode", "a")));
        fixture.widget().refreshInputWidgets();
        fixture.widget().rejectInputValuePreview(proposals.getFirst());
        fixture.widget().commitInputValuePreview(proposals.getFirst());

        assertTrue(fixture.widget().hasInputValuePreview(PinId.of("mode")));
        assertEquals("a", ((TextInputWidget) inputWidgets(fixture.widget()).get("mode")).getText());
        assertFalse(fixture.widget().getVisibleOutputPins().contains("conditional-output"));
        assertEquals("a", node.getInputValues().get("mode"));

        node.setInputValues(new LinkedHashMap<>(Map.of("mode", "b")));
        fixture.widget().refreshInputWidgets();
        fixture.widget().rejectInputValuePreview(proposals.getLast());
        assertFalse(fixture.widget().hasInputValuePreview(PinId.of("mode")));
        assertEquals("b", ((TextInputWidget) inputWidgets(fixture.widget()).get("mode")).getText());
    }

    @Test
    void coreClearSelectionProposesTypedRemovalWithoutTouchingProjectedFlowNode() throws Exception {
        FlowNode node = resourceNode(RESOURCE_ID);
        NodeDefinition.PinDefinition input = resourceInput();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = resourceWidget(node, input, mutation -> {
            proposal.set(mutation);
            return true;
        });

        assertTrue(clearReference(widget, input));

        assertEquals(RESOURCE_ID, ((FlowResourceReference) node.getInputValues().get(RESOURCE_PIN.canonicalText())).getId());
        assertEquals(NODE, proposal.get().nodeId());
        assertEquals(RESOURCE_PIN, proposal.get().pinId());
        assertNull(proposal.get().value());
        assertTrue(proposal.get().remove());
    }

    @Test
    void coreOptionalRemovalStaysVisibleAndNeverWritesLegacyStructuralState() {
        FlowNode node = new FlowNode("test:value", 20, 40,
            new LinkedHashMap<>(Map.of(PIN.canonicalText(), "old")));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder(PIN, "Message",
            NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
            .widget(NodeDefinition.WidgetType.TEXT).optional(true).build();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = new NodeWidget(20, 40, node, graph, NODE.canonicalText(), null, null, null,
            definition, false, false, mutation -> {
                proposal.set(mutation);
                return true;
            });

        assertTrue(widget.removeOptionalInputPin(PIN.canonicalText()));

        assertEquals("old", node.getInputValues().get(PIN.canonicalText()));
        assertFalse(node.getInputValues().containsKey("__removed_optional_inputs"));
        assertTrue(widget.getVisibleInputPins().contains(PIN.canonicalText()));
        assertEquals(NODE, proposal.get().nodeId());
        assertEquals(PIN, proposal.get().pinId());
        assertTrue(proposal.get().remove());
    }

    @Test
    void legacyClearSelectionStillMutatesItsFlowNodeDirectly() throws Exception {
        FlowNode node = resourceNode(RESOURCE_ID);
        NodeDefinition.PinDefinition input = resourceInput();
        NodeWidget widget = resourceWidget(node, input, null);

        assertTrue(clearReference(widget, input));

        assertFalse(node.getInputValues().containsKey(RESOURCE_PIN.canonicalText()));
    }

    @Test
    void rejectedCoreClearSelectionRetainsProjectedReference() throws Exception {
        FlowNode node = resourceNode(RESOURCE_ID);
        NodeDefinition.PinDefinition input = resourceInput();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = resourceWidget(node, input, mutation -> {
            proposal.set(mutation);
            return false;
        });

        assertFalse(clearReference(widget, input));

        assertEquals(RESOURCE_ID, ((FlowResourceReference) node.getInputValues().get(RESOURCE_PIN.canonicalText())).getId());
        assertNotNull(proposal.get());
        assertTrue(proposal.get().remove());
    }

    @Test
    void exactResourceSelectionProposesTheCompleteCanonicalLocator() throws Exception {
        FlowNode node = new FlowNode("test:value", 20, 40, new LinkedHashMap<>());
        NodeDefinition.PinDefinition input = resourceInput(RESOURCE_OWNER, RESOURCE_TYPE, List.of(RESOURCE_ID));
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = resourceWidget(node, input, mutation -> {
            proposal.set(mutation);
            return true;
        });

        selectResource(widget, input, RESOURCE_ID);

        assertEquals(Map.of(
            "serverId", RESOURCE_SERVER.canonicalText(),
            "type", Map.of("ownerId", RESOURCE_OWNER, "localId", RESOURCE_TYPE),
            "id", RESOURCE_ID), proposal.get().value());
        assertFalse(proposal.get().remove());
        assertFalse(node.getInputValues().containsKey(RESOURCE_PIN.canonicalText()));
    }

    @Test
    void sameResourceIdRemainsIsolatedByServerAndType() throws Exception {
        NodeDefinition.PinDefinition input = resourceInput("first.owner", "gui", List.of(RESOURCE_ID));
        NodeDefinition.PinDefinition otherTypeInput = resourceInput("second.owner", "gui", List.of(RESOURCE_ID));
        ServerId otherServer = ServerId.deterministic("other-resource-selector");
        AtomicReference<NodeWidget.NodeValueMutation> first = new AtomicReference<>();
        AtomicReference<NodeWidget.NodeValueMutation> otherServerProposal = new AtomicReference<>();
        AtomicReference<NodeWidget.NodeValueMutation> otherTypeProposal = new AtomicReference<>();
        NodeWidget firstWidget = resourceWidget(new FlowNode("test:value", 20, 40, new LinkedHashMap<>()),
            input, RESOURCE_SERVER, mutation -> {
                first.set(mutation);
                return true;
            });
        NodeWidget otherServerWidget = resourceWidget(new FlowNode("test:value", 20, 40, new LinkedHashMap<>()),
            input, otherServer, mutation -> {
                otherServerProposal.set(mutation);
                return true;
            });
        NodeWidget otherTypeWidget = resourceWidget(new FlowNode("test:value", 20, 40, new LinkedHashMap<>()),
            otherTypeInput, RESOURCE_SERVER, mutation -> {
                otherTypeProposal.set(mutation);
                return true;
            });

        selectResource(firstWidget, input, RESOURCE_ID);
        selectResource(otherServerWidget, input, RESOURCE_ID);
        selectResource(otherTypeWidget, otherTypeInput, RESOURCE_ID);

        assertNotEquals(first.get().value(), otherServerProposal.get().value());
        assertNotEquals(first.get().value(), otherTypeProposal.get().value());
        assertEquals(RESOURCE_SERVER.canonicalText(), ((Map<?, ?>) first.get().value()).get("serverId"));
        assertEquals(otherServer.canonicalText(), ((Map<?, ?>) otherServerProposal.get().value()).get("serverId"));
        assertEquals(RESOURCE_SERVER.canonicalText(), ((Map<?, ?>) otherTypeProposal.get().value()).get("serverId"));
        assertEquals(Map.of("ownerId", "first.owner", "localId", "gui"),
            ((Map<?, ?>) first.get().value()).get("type"));
        assertEquals(Map.of("ownerId", "first.owner", "localId", "gui"),
            ((Map<?, ?>) otherServerProposal.get().value()).get("type"));
        assertEquals(Map.of("ownerId", "second.owner", "localId", "gui"),
            ((Map<?, ?>) otherTypeProposal.get().value()).get("type"));
    }

    @Test
    void replacingAResourceValuePreservesLocatorStateAndUnknownFields() {
        TypeExpr.ResourceType type = resourceType("first.owner", "gui");
        ServerResourceLocator previousLocator = new ServerResourceLocator(RESOURCE_SERVER,
            ContractRef.of(OwnerId.of("first.owner"), ResourceTypeId.of("gui")), "old",
            Map.of("locatorExtension", "kept"));
        TypedValue previous = TypedValue.locator(type, previousLocator, Map.of("typedExtension", "kept"));

        TypedValue replacement = GraphEditorScreen.coreTypedValue(previous,
            locatorValue(RESOURCE_SERVER, "first.owner", "gui", RESOURCE_ID), RESOURCE_SERVER);

        assertNotNull(replacement);
        assertEquals(TypedValue.State.LOCATOR, replacement.state());
        assertEquals(RESOURCE_ID, replacement.locator().id());
        assertEquals(Map.of("locatorExtension", "kept"), replacement.locator().unknown());
        assertEquals(Map.of("typedExtension", "kept"), replacement.unknown());
    }

    @Test
    void selectingAResourceForAnAbsentValueCreatesLocatorState() {
        TypeExpr.ResourceType type = resourceType("first.owner", "gui");

        TypedValue replacement = GraphEditorScreen.coreTypedValue(TypedValue.absent(type),
            locatorValue(RESOURCE_SERVER, "first.owner", "gui", RESOURCE_ID), RESOURCE_SERVER);

        assertNotNull(replacement);
        assertEquals(TypedValue.State.LOCATOR, replacement.state());
        assertEquals(RESOURCE_SERVER, replacement.locator().serverId());
        assertEquals(RESOURCE_ID, replacement.locator().id());
    }

    @Test
    void resourceMutationRejectsMismatchedServerAndType() {
        TypeExpr.ResourceType type = resourceType("first.owner", "gui");
        TypedValue previous = TypedValue.absent(type);

        assertNull(GraphEditorScreen.coreTypedValue(previous,
            locatorValue(ServerId.deterministic("wrong-resource-server"), "first.owner", "gui", RESOURCE_ID),
            RESOURCE_SERVER));
        assertNull(GraphEditorScreen.coreTypedValue(previous,
            locatorValue(RESOURCE_SERVER, "second.owner", "gui", RESOURCE_ID), RESOURCE_SERVER));
    }

    @Test
    void emptyResourceOptionsStaySearchableWithoutRawTextFallback() throws Exception {
        FlowNode node = resourceNode(RESOURCE_ID);
        NodeDefinition.PinDefinition input = resourceInput(RESOURCE_OWNER, RESOURCE_TYPE, List.of());
        NodeWidget widget = resourceWidget(node, input, mutation -> true);

        Widget selector = (Widget) inputWidgets(widget).get(RESOURCE_PIN.canonicalText());

        assertTrue(selector instanceof AnimatedButton);
        assertFalse(selector instanceof TextInputWidget);
        assertEquals(RESOURCE_ID, ((AnimatedButton) selector).getMessage());
    }

    @Test
    void bareResourceReferencesAreVisibleAndReadOnly() throws Exception {
        for (FlowTypeRef type : List.of(FlowTypeRef.simple("resource_reference"),
            FlowTypeRef.parse("resource_reference<variable_definition>"))) {
            NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder(RESOURCE_PIN, "Resource",
                NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.RESOURCE_REFERENCE)
                .typeRef(type).widget(NodeDefinition.WidgetType.SEARCHABLE_LIST).options(List.of(RESOURCE_ID)).build();
            AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
            NodeWidget widget = resourceWidget(resourceNode(RESOURCE_ID), input, mutation -> {
                proposal.set(mutation);
                return true;
            });

            Widget selector = (Widget) inputWidgets(widget).get(RESOURCE_PIN.canonicalText());

            assertTrue(selector instanceof AnimatedButton);
            assertFalse(selector.isActive());
            assertEquals("Resource Type Required", ((AnimatedButton) selector).getMessage());
            assertNull(proposal.get());
        }
    }

    @Test
    void failedDeleteSettlementRetainsCoreReferenceWithoutAProposal() throws Exception {
        FlowNode node = resourceNode(RESOURCE_ID);
        NodeDefinition.PinDefinition input = resourceInput();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = resourceWidget(node, input, mutation -> {
            proposal.set(mutation);
            return true;
        });

        FlowManager.ResourceDeleteResult result = new FlowManager.ResourceDeleteResult(RESOURCE_TYPE, RESOURCE_ID,
            false, "Resource Delete Rejected");

        assertFalse(settleDelete(widget, input, RESOURCE_TYPE, RESOURCE_ID, result, null));
        assertEquals(RESOURCE_ID, ((FlowResourceReference) node.getInputValues().get(RESOURCE_PIN.canonicalText())).getId());
        assertNull(proposal.get());
    }

    @Test
    void exactSuccessfulDeleteSettlementProposesCoreRemoval() throws Exception {
        FlowNode node = resourceNode(RESOURCE_ID);
        NodeDefinition.PinDefinition input = resourceInput();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = resourceWidget(node, input, mutation -> {
            proposal.set(mutation);
            return true;
        });

        FlowManager.ResourceDeleteResult result = new FlowManager.ResourceDeleteResult(RESOURCE_TYPE, RESOURCE_ID,
            true, "");

        assertTrue(settleDelete(widget, input, RESOURCE_TYPE, RESOURCE_ID, result, null));
        assertEquals(RESOURCE_ID, ((FlowResourceReference) node.getInputValues().get(RESOURCE_PIN.canonicalText())).getId());
        assertNotNull(proposal.get());
        assertTrue(proposal.get().remove());
    }

    @Test
    void mismatchedSuccessfulDeleteSettlementRetainsCoreReference() throws Exception {
        FlowNode node = resourceNode(RESOURCE_ID);
        NodeDefinition.PinDefinition input = resourceInput();
        AtomicReference<NodeWidget.NodeValueMutation> proposal = new AtomicReference<>();
        NodeWidget widget = resourceWidget(node, input, mutation -> {
            proposal.set(mutation);
            return true;
        });

        FlowManager.ResourceDeleteResult result = new FlowManager.ResourceDeleteResult(RESOURCE_TYPE, "other", true, "");

        assertFalse(settleDelete(widget, input, RESOURCE_TYPE, RESOURCE_ID, result, null));
        assertEquals(RESOURCE_ID, ((FlowResourceReference) node.getInputValues().get(RESOURCE_PIN.canonicalText())).getId());
        assertNull(proposal.get());
    }

    private static FlowNode stringNode() {
        return new FlowNode("test:value", 20, 40, new LinkedHashMap<>(Map.of(PIN.canonicalText(), "old")));
    }

    private static NodeWidget stringWidget(FlowNode node, NodeWidget.NodeValueMutationHandler handler) {
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        NodeDefinition.PinDefinition input = stringPin();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        return new NodeWidget(20, 40, node, graph, NODE.canonicalText(), null, null, null,
            definition, false, false, handler);
    }

    private static void focusInput(NodeWidget widget) throws Exception {
        widget.render(new TestDrawContext(), 0, 0, 0F);
        stringInput(widget).setFocused(true);
        assertTrue(widget.keyPressed(key(widget, ReKey.END)));
    }

    private static TextInputWidget stringInput(NodeWidget widget) throws Exception {
        return (TextInputWidget) inputWidgets(widget).get(PIN.canonicalText());
    }

    private static Map<?, ?> inputWidgets(NodeWidget widget) throws Exception {
        Field field = NodeWidget.class.getDeclaredField("inputWidgets");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(widget);
    }

    private static Map<String, Object> namedType(String id) {
        return Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", id), "arguments", List.of());
    }

    private static NodeDefinition.PinDefinition stringPin() {
        return new NodeDefinition.PinBuilder(PIN, "Message", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).widget(NodeDefinition.WidgetType.TEXT).build();
    }

    private static FlowNode conditionalNode() {
        return new FlowNode("test:conditional", 20, 40, new LinkedHashMap<>(Map.of("mode", "off")));
    }

    private static ConditionalWidget conditionalWidget(FlowNode node, NodeWidget.NodeValueMutationHandler handler) {
        return conditionalWidget(node, handler, null);
    }

    private static ConditionalWidget conditionalWidget(FlowNode node, NodeWidget.NodeValueMutationHandler handler,
                                                       Runnable onMutation) {
        NodeDefinition.PinDefinition mode = new NodeDefinition.PinBuilder("mode", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).widget(NodeDefinition.WidgetType.TEXT).build();
        NodeDefinition.PinDefinition detail = new NodeDefinition.PinBuilder("detail", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).widget(NodeDefinition.WidgetType.TEXT)
            .visibleWhen("mode", "on").build();
        NodeDefinition.PinDefinition output = new NodeDefinition.PinBuilder("conditional-output",
            NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.OUTPUT, FlowDataType.STRING)
            .visibleWhen("mode", "on").build();
        NodeDefinition definition = new NodeDefinition.Builder("conditional", "Conditional",
            NodeDefinition.NodeCategory.UTILITY).owner("test").input(mode).input(detail).output(output).build();
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        graph.setConnections(new ArrayList<>(List.of(new FlowConnection(NODE.canonicalText(),
            "conditional-output", NodeInstanceId.deterministic("conditional-target").canonicalText(), "value"))));
        NodeWidget widget = new NodeWidget(20, 40, node, graph, NODE.canonicalText(), "server", null, onMutation,
            definition, false, false, handler);
        return new ConditionalWidget(widget, graph, mode);
    }

    private static void proposeValue(NodeWidget widget, NodeDefinition.PinDefinition input, String value) throws Exception {
        ((TextInputWidget) inputWidgets(widget).get(input.getId().canonicalText())).setText(value);
        Method changed = NodeWidget.class.getDeclaredMethod("handleInputValueChanged",
            NodeDefinition.PinDefinition.class);
        changed.setAccessible(true);
        changed.invoke(widget, input);
    }

    private record ConditionalWidget(NodeWidget widget, FlowGraph graph, NodeDefinition.PinDefinition mode) {
    }

    private static ReTextInputEvent text(Object source, char character) {
        return new ReTextInputEvent(source, source, 0L, ReModifierState.none(), String.valueOf(character), character);
    }

    private static ReKeyEvent key(Object source, ReKey key) {
        return new ReKeyEvent(source, source, 0L, ReModifierState.none(), ReKeyEvent.Action.PRESSED,
            key, 0, 0, ReKeyLocation.STANDARD, false);
    }

    private static FlowNode resourceNode(String id) {
        return new FlowNode("test:value", 20, 40, new LinkedHashMap<>(Map.of(RESOURCE_PIN.canonicalText(),
            new FlowResourceReference(RESOURCE_TYPE, id, "server"))));
    }

    private static NodeDefinition.PinDefinition resourceInput() {
        return resourceInput(RESOURCE_OWNER, RESOURCE_TYPE, List.of(RESOURCE_ID));
    }

    private static NodeDefinition.PinDefinition resourceInput(String owner, String type, List<String> options) {
        return new NodeDefinition.PinBuilder(RESOURCE_PIN, "Resource", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.RESOURCE_REFERENCE)
            .typeRef(FlowTypeRef.parse("resource_reference<" + owner + ":" + type + ">"))
            .widget(NodeDefinition.WidgetType.SEARCHABLE_LIST).options(options).build();
    }

    private static NodeWidget resourceWidget(FlowNode node, NodeDefinition.PinDefinition input,
                                             NodeWidget.NodeValueMutationHandler handler) {
        return resourceWidget(node, input, RESOURCE_SERVER, handler);
    }

    private static NodeWidget resourceWidget(FlowNode node, NodeDefinition.PinDefinition input, ServerId server,
                                             NodeWidget.NodeValueMutationHandler handler) {
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(NODE.canonicalText(), node)));
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        return new NodeWidget(20, 40, node, graph, NODE.canonicalText(), server.canonicalText(), null, null,
            definition, false, false, handler);
    }

    private static TypeExpr.ResourceType resourceType(String owner, String type) {
        return new TypeExpr.ResourceType(TypeReference.of(owner, type));
    }

    private static Map<String, Object> locatorValue(ServerId server, String owner, String type, String id) {
        return Map.of("serverId", server.canonicalText(), "type", Map.of("ownerId", owner, "localId", type), "id", id);
    }

    private static void selectResource(NodeWidget widget, NodeDefinition.PinDefinition input, String id) throws Exception {
        Field selected = NodeWidget.class.getDeclaredField("searchableSelectorValues");
        selected.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> values = (Map<String, String>) selected.get(widget);
        values.put(input.getId().canonicalText(), id);
        Method changed = NodeWidget.class.getDeclaredMethod("handleInputValueChanged", NodeDefinition.PinDefinition.class);
        changed.setAccessible(true);
        changed.invoke(widget, input);
    }

    private static boolean clearReference(NodeWidget widget, NodeDefinition.PinDefinition input) throws Exception {
        Method clear = NodeWidget.class.getDeclaredMethod("clearManagedReference", NodeDefinition.PinDefinition.class);
        clear.setAccessible(true);
        return (boolean) clear.invoke(widget, input);
    }

    private static boolean settleDelete(NodeWidget widget, NodeDefinition.PinDefinition input, String resourceType,
                                        String id, FlowManager.ResourceDeleteResult result, Throwable failure) throws Exception {
        Method settle = NodeWidget.class.getDeclaredMethod("settleManagedResourceDelete", NodeDefinition.PinDefinition.class,
            String.class, String.class, FlowManager.ResourceDeleteResult.class, Throwable.class);
        settle.setAccessible(true);
        return (boolean) settle.invoke(widget, input, resourceType, id, result, failure);
    }
}
