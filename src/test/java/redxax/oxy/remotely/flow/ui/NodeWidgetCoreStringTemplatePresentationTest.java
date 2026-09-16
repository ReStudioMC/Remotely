package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeWidgetCoreStringTemplatePresentationTest {
    private static final Path NODE_WIDGET = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/NodeWidget.java");
    private static final Path GRAPH_EDITOR = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java");
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
    void coreStringTemplatePresentationNeverFabricatesAnUppercasePin() {
        NodeWidget widget = assertDoesNotThrow(() -> widget("{Player}", RESOURCE));

        assertNull(widget.getPinType("Player", true));
        assertDoesNotThrow(widget::refreshInputWidgets);
        assertNull(widget.getPinType("Player", true));
    }

    @Test
    void legacyStringTemplatePresentationStillMaterializesACanonicalPin() {
        NodeWidget widget = widget("{player}", null);

        assertEquals(FlowDataType.STRING, widget.getPinType("player", true));
        widget.refreshInputWidgets();
        assertEquals(FlowDataType.STRING, widget.getPinType("player", true));
    }

    @Test
    void coreContextExistsBeforeDefinitionSetupAndSuppressesLegacyTemplatePins() throws IOException {
        String nodeWidget = Files.readString(NODE_WIDGET);
        int terminalConstructor = nodeWidget.indexOf("ServerResourceLocator coreResource, Map<PinId, PinValue> corePinValues)");
        int resourceAssignment = nodeWidget.indexOf("this.coreResource = coreResource;", terminalConstructor);
        int definitionSetup = nodeWidget.indexOf("this.definition =", terminalConstructor);
        int templateUpdate = nodeWidget.indexOf("private boolean updateStringTemplatePins()");
        int coreGuard = nodeWidget.indexOf("if (coreResource != null)", templateUpdate);
        int templateParsing = nodeWidget.indexOf("nodeStringTemplateNames()", templateUpdate);

        assertTrue(terminalConstructor >= 0);
        assertTrue(resourceAssignment > terminalConstructor);
        assertTrue(resourceAssignment < definitionSetup);
        assertTrue(coreGuard > templateUpdate);
        assertTrue(coreGuard < templateParsing);
        assertTrue(nodeWidget.substring(coreGuard, templateParsing).contains("stringTemplateInputNames.clear();"));

        String graphEditor = Files.readString(GRAPH_EDITOR);
        int createWidget = graphEditor.indexOf("protected FlowNodeWidget createNodeWidget");
        int createWidgetEnd = graphEditor.indexOf("private ReSyncGenericWidgetCapabilities.WidgetDefinition", createWidget);
        String construction = graphEditor.substring(createWidget, createWidgetEnd);
        assertTrue(construction.contains("coreDocument && coreSession != null ? coreSession.resource() : null"));
        assertTrue(construction.contains("coreNode != null ? coreNode.values() : Map.of()"));
        assertFalse(construction.contains("widget.configureCoreOptions"));
    }

    private static NodeWidget widget(String template, ServerResourceLocator resource) {
        String nodeId = NodeInstanceId.deterministic("string-template:" + template).canonicalText();
        FlowNode node = new FlowNode("test:template", 20, 40,
            new LinkedHashMap<>(Map.of(MESSAGE.canonicalText(), template)));
        FlowGraph graph = new FlowGraph();
        graph.setNodes(new LinkedHashMap<>(Map.of(nodeId, node)));
        NodeDefinition definition = new NodeDefinition.Builder("template", "Template", NodeDefinition.NodeCategory.TEXT)
            .owner("test")
            .input(new NodeDefinition.PinDefinition(MESSAGE, "Message", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING))
            .build();
        Map<PinId, PinValue> values = resource == null ? Map.of() : Map.of(MESSAGE,
            new PinValue(MESSAGE, TypedValue.value(STRING, template)));
        return new NodeWidget(20, 40, node, graph, nodeId, null, null, null, definition, false, false, null,
            resource, values);
    }
}
