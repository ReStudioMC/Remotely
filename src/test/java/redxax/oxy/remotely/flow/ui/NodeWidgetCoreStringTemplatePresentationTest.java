package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.ContractRef;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NodeWidgetCoreStringTemplatePresentationTest {
    private static final PinId MESSAGE = PinId.of("message");
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        ServerId.deterministic("core-string-template-test"), ContractRef.of(OwnerId.of("test"),
            ResourceTypeId.of("flow")), "template");

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void coreStringTemplatePresentationShowsItsRealCamelCasePin() {
        NodeWidget widget = widget("{extraPin}", RESOURCE);

        assertEquals(FlowDataType.STRING, widget.getPinType("extraPin", true));
        widget.refreshInputWidgets();
        assertEquals(FlowDataType.STRING, widget.getPinType("extraPin", true));
    }

    @Test
    void legacyStringTemplatePresentationStillMaterializesACanonicalPin() {
        NodeWidget widget = widget("{player}", null);

        assertEquals(FlowDataType.STRING, widget.getPinType("player", true));
        widget.refreshInputWidgets();
        assertEquals(FlowDataType.STRING, widget.getPinType("player", true));
    }

    @Test
    void coreStringTemplatePinAppearsDuringValuePreviewAndRollsBackOnRejection() throws Exception {
        AtomicReference<NodeWidget.NodeValueMutation> proposed = new AtomicReference<>();
        NodeWidget widget = widget("Player", null, mutation -> {
            proposed.set(mutation);
            return true;
        });
        widget.configureCoreOptions(RESOURCE, Map.of(MESSAGE,
            new PinValue(MESSAGE, TypedValue.value(STRING, "Player"))));
        Field widgets = NodeWidget.class.getDeclaredField("inputWidgets");
        widgets.setAccessible(true);
        TextInputWidget input = (TextInputWidget) ((Map<?, ?>) widgets.get(widget)).get(MESSAGE.canonicalText());
        input.setText("Player {extraPin}");
        Method changed = NodeWidget.class.getDeclaredMethod("handleInputValueChanged", NodeDefinition.PinDefinition.class);
        changed.setAccessible(true);
        changed.invoke(widget, templatePin());

        assertNotNull(proposed.get());
        assertEquals("Player {extraPin}", proposed.get().value());
        Method presented = NodeWidget.class.getDeclaredMethod("presentationInputValues");
        presented.setAccessible(true);
        assertEquals("Player {extraPin}", ((Map<?, ?>) presented.invoke(widget)).get("message"));
        assertEquals(FlowDataType.STRING, widget.getPinType("extraPin", true));
        widget.rejectInputValuePreview(proposed.get());
        assertNull(widget.getPinType("extraPin", true));
    }

    private static NodeWidget widget(String template, ServerResourceLocator resource) {
        return widget(template, resource, null);
    }

    private static NodeWidget widget(String template, ServerResourceLocator resource,
                                     NodeWidget.NodeValueMutationHandler handler) {
        String nodeId = NodeInstanceId.deterministic("string-template:" + template).canonicalText();
        FlowNode node = new FlowNode("test:template", 20, 40,
            new LinkedHashMap<>(Map.of(MESSAGE.canonicalText(), template)));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(nodeId, node)));
        NodeDefinition definition = new NodeDefinition.Builder("template", "Template", NodeDefinition.NodeCategory.TEXT)
            .owner("test")
            .input(templatePin())
            .build();
        Map<PinId, PinValue> values = resource == null ? Map.of() : Map.of(MESSAGE,
            new PinValue(MESSAGE, TypedValue.value(STRING, template)));
        return new NodeWidget(20, 40, node, graph, nodeId, null, null, null, definition, false, false, handler,
            resource, values);
    }

    private static NodeDefinition.PinDefinition templatePin() {
        return new NodeDefinition.PinBuilder(MESSAGE, "Message", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).widget(NodeDefinition.WidgetType.TEXT).build();
    }
}
