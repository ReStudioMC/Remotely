package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;
import redxax.oxy.remotely.flow.ui.studio.ReSyncContentBrowserWidget;
import redxax.oxy.remotely.flow.ui.studio.StudioDocument;
import redxax.oxy.remotely.flow.ui.studio.StudioViewportState;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ContextMenuWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorInputRoutingBehaviorTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void visibleContextMenuCapturesTheCompletePointerSequenceBeforeTheStudioWorkspaceUnderlay() {
        RoutingEditor editor = new RoutingEditor(graph("context-menu"));
        editor.width = 640;
        editor.height = 360;
        editor.enableReadyStudioRouting();
        AtomicInteger menuActions = new AtomicInteger();

        ContextMenuWidget menu = editor.openMenu(80, 80, menuActions::incrementAndGet);
        menu.render(new TestDrawContext(), menu.getX() + 4, menu.getY() + 4, 0.0F);
        ReMouseEvent click = leftMouse(editor, ReMouseEvent.Action.PRESSED, menu.getX() + 4, menu.getY() + 4, 0, 0);

        assertTrue(editor.mouseClicked(click));
        assertEquals(1, menuActions.get());
        assertEquals(0, editor.workspacePresses.get());

        ReMouseEvent drag = leftMouse(editor, ReMouseEvent.Action.DRAGGED, menu.getX() + 8, menu.getY() + 8, 4, 4);
        ReMouseEvent release = leftMouse(editor, ReMouseEvent.Action.RELEASED, menu.getX() + 8, menu.getY() + 8, 0, 0);

        assertTrue(editor.mouseDragged(drag));
        assertTrue(editor.mouseReleased(release));
        assertEquals(0, editor.workspaceDrags.get());
        assertEquals(0, editor.workspaceReleases.get());
    }

    @Test
    void itemComponentPanelConsumesClicksOverAGraphNode() throws Exception {
        String serverId = "item-panel-route";
        String nodeId = NodeInstanceId.deterministic("item-panel-node").canonicalText();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").build();
        NodeRegistry previous = NodeRegistry.getInstance();
        RoutingEditor editor = null;
        try {
            NodeRegistry registry = new NodeRegistry();
            assertTrue(registry.applySnapshot(serverId, canonicalSnapshot(serverId, definition)));
            FlowGraph graph = graph("item-panel-graph");
            editor = new RoutingEditor(graph, serverId);
            editor.width = 900;
            editor.height = 600;
            editor.openItemComponentEditor(new ItemComponentEditorPanel.Model("Item Components", "minecraft:stone",
                null, Map.of(), false, ignored -> {}, ignored -> {}, () -> {}));
            SidePanel panel = editor.getSidePanels().stream()
                .filter(candidate -> "itemComponentEditorPanel".equals(candidate.id()))
                .findFirst().orElseThrow();
            panel.animation(false).show();
            panel.y(54).height(538).width(420);
            panel.update();
            int x = editor.width - panel.getConfiguredWidth() / 2;
            int y = 300;
            assertTrue(panel.isMouseOver(x, y));
            double[] world = editor.worldPoint(x, y);
            int nodeX = (int) world[0] - 20;
            int nodeY = (int) world[1] - 20;
            FlowNode node = new FlowNode("test:value", nodeX, nodeY, new LinkedHashMap<>());
            graph.setNodes(new LinkedHashMap<>(Map.of(nodeId, node)));
            FlowNodeWidget widget = new FlowNodeWidget(nodeX, nodeY, node, graph, nodeId, serverId, null, null,
                FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), definition, false, false);
            widget.render(new TestDrawContext(), (int) world[0], (int) world[1], 0F);
            assertTrue(widget.isMouseOver(world[0], world[1]));
            editor.installNode(nodeId, widget);

            assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, x, y, 0, 0)));
            assertFalse(editor.nodeDragging());
            editor.mouseDragged(leftMouse(editor, ReMouseEvent.Action.DRAGGED, x + 25, y + 15, 25, 15));
            assertEquals(nodeX, widget.getX());
            assertEquals(nodeY, widget.getY());
        } finally {
            if (editor != null) {
                editor.removed();
            }
            restoreNodeRegistry(previous);
        }
    }

    @Test
    void contextMenuHeaderUsesTheSameTopmostScreenRouteWithoutReachingTheBrowserRow() {
        RoutingEditor editor = new RoutingEditor(graph("context-header"));
        editor.width = 640;
        editor.height = 360;
        editor.enableReadyStudioRouting();
        AtomicInteger headerActions = new AtomicInteger();

        ContextMenuWidget menu = editor.openHeaderMenu(80, 80, headerActions::incrementAndGet);
        menu.render(new TestDrawContext(), menu.getX() + 4, menu.getY() + 4, 0.0F);

        assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED,
            menu.getX() + 6, menu.getY() + 6, 0, 0)));
        assertEquals(1, headerActions.get());
        assertEquals(0, editor.workspacePresses.get());
    }

    @Test
    void browserProducedMenuHeaderCapturesTheClickBeforeItsActualBrowserRow() throws ReflectiveOperationException {
        RoutingEditor editor = new RoutingEditor(graph("browser-menu"));
        editor.width = 640;
        editor.height = 360;
        editor.enableReadyStudioRouting();
        BrowserHarness browser = new BrowserHarness(editor, 0, 32, 240, 300);
        editor.attachBrowser(browser);
        ReSyncProjectMetadata.ResourceEntry resource = new ReSyncProjectMetadata.ResourceEntry();
        resource.setType("flow");
        resource.setId("under-menu");
        resource.setDisplayName("Under Menu");
        resource.setPath("Blueprints/Flows/under-menu.json");

        assertTrue(browser.openResourceMenu(resource, 80, 80));
        ContextMenuWidget menu = editor.currentMenu();
        menu.render(new TestDrawContext(), menu.getVisualLeft() + 4, menu.getVisualTop() + 4, 0.0F);

        assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED,
            menu.getX() + 6, menu.getY() + 6, 0, 0)));
        assertTrue(browser.hasClipboard());
        assertEquals(0, browser.rowPresses.get());
        assertEquals(0, editor.workspacePresses.get());
    }

    @Test
    void slowWorkspaceCaptureNeverQueuesOrReplaysRawInputEvents() throws ReflectiveOperationException {
        RoutingEditor editor = new RoutingEditor(graph("input-pressure"));
        editor.width = 640;
        editor.height = 360;
        editor.forceWorkspaceCapturePending = true;

        for (int index = 0; index < 256; index++) {
            double x = 300 + index % 20;
            double y = 180 + index % 10;
            editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, x, y, 0, 0));
            editor.mouseDragged(leftMouse(editor, ReMouseEvent.Action.DRAGGED, x + 2, y + 2, 2, 2));
            editor.mouseReleased(leftMouse(editor, ReMouseEvent.Action.RELEASED, x + 2, y + 2, 0, 0));
            editor.keyPressed(new ReKeyEvent(editor, editor, index, ReModifierState.none(), ReKeyEvent.Action.PRESSED,
                ReKey.A, 0, 0, ReKeyLocation.STANDARD, false));
        }

        Field field = GraphEditorScreen.class.getDeclaredField("deferredWorkspaceMutations");
        field.setAccessible(true);
        assertTrue(((ArrayDeque<?>) field.get(editor)).isEmpty());
        Field overflow = GraphEditorScreen.class.getDeclaredField("deferredWorkspaceOverflowMutation");
        overflow.setAccessible(true);
        assertNull(overflow.get(editor));
        Field backpressure = GraphEditorScreen.class.getDeclaredField("workspaceMutationBackpressure");
        backpressure.setAccessible(true);
        assertFalse(backpressure.getBoolean(editor));
    }

    @Test
    void staleCoreTabSelectionKeepsTheVisibleEditorAndBlocksBothSaveRoutes() {
        PendingSelectionEditor editor = new PendingSelectionEditor(graph("legacy"));
        editor.width = 640;
        editor.height = 360;
        editor.prepareDocuments();

        editor.selectCoreTab();

        assertEquals(ReSyncProjectMetadata.resourceKey("flow", "legacy"), editor.activeDocumentKey());
        assertEquals(ReSyncProjectMetadata.resourceKey("flow", "legacy"), editor.activeTabKey());

        ReKeyEvent shortcut = new ReKeyEvent(editor, editor, 0L,
            new ReModifierState(false, true, false, false, false, false), ReKeyEvent.Action.PRESSED,
            ReKey.S, 0, 0, ReKeyLocation.STANDARD, false);
        assertTrue(editor.keyPressed(shortcut));

        AnimatedWidget save = editor.getStudioHeaderButtons().stream()
            .filter(button -> "Save".equals(button.hint))
            .findFirst()
            .orElseThrow();
        save.visible = true;
        save.setPosition(24, 24);
        assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, 26, 26, 0, 0)));
        assertEquals(0, editor.saveAttempts.get());
    }

    @Test
    void toolbarAndControlSaveEnterTheExactSameSaveRoute() {
        RoutingEditor editor = new RoutingEditor(graph("save-route"));
        editor.width = 640;
        editor.height = 360;
        editor.installHeaderButtons();
        AnimatedWidget save = editor.getStudioHeaderButtons().stream()
            .filter(button -> "Save".equals(button.hint))
            .findFirst()
            .orElseThrow();
        save.visible = true;
        save.setPosition(24, 24);

        ReMouseEvent toolbarClick = leftMouse(editor, ReMouseEvent.Action.PRESSED,
            save.getX() + 2, save.getY() + 2, 0, 0);
        assertTrue(editor.mouseClicked(toolbarClick));

        ReKeyEvent shortcut = new ReKeyEvent(editor, editor, 0L,
            new ReModifierState(false, true, false, false, false, false), ReKeyEvent.Action.PRESSED,
            ReKey.S, 0, 0, ReKeyLocation.STANDARD, false);
        assertTrue(editor.keyPressed(shortcut));

        assertEquals(2, editor.saveRoutes.size());
        assertSame(editor.saveRoutes.getFirst(), editor.saveRoutes.getLast());
        assertEquals(new SaveRoute("save-route", "flow"), editor.saveRoutes.getFirst());
    }

    @Test
    void admittedNodeFieldClickOwnsFocusDragAndReleaseOutsideTheNode() throws Exception {
        String serverId = "input-routing-authority";
        String nodeId = NodeInstanceId.deterministic("input-routing-node").canonicalText();
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder("message", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).widget(NodeDefinition.WidgetType.TEXT).build();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        NodeRegistry previous = NodeRegistry.getInstance();
        try {
            NodeRegistry registry = new NodeRegistry();
            assertTrue(registry.applySnapshot(serverId, canonicalSnapshot(serverId, definition)));
            FlowGraph graph = graph("field-route");
            graph.setResourceType("test");
            FlowNode node = new FlowNode("test:value", 80, 90, new LinkedHashMap<>(Map.of("message", "old")));
            graph.setNodes(new LinkedHashMap<>(Map.of(nodeId, node)));
            RoutingEditor editor = new RoutingEditor(graph, serverId);
            editor.width = 640;
            editor.height = 360;
            FlowNodeWidget widget = new FlowNodeWidget(80, 90, node, graph, nodeId, serverId, null, null,
                FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), definition, false, false, mutation -> true);
            TrackingTextInput field = new TrackingTextInput();
            field.setText("old");
            installInputWidget(widget, "message", field);
            widget.render(new TestDrawContext(), 0, 0, 0F);
            editor.installNode(nodeId, widget);

            double[] click = editor.screenPoint(field.getX() + 2, field.getY() + 2);
            assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, click[0], click[1], 0, 0)));
            assertSame(field, editor.focusedWidget());
            assertTrue(field.isFocused());
            assertTrue(editor.textInput(new ReTextInputEvent(editor, editor, 0L, ReModifierState.none(), "x", 'x')));
            assertEquals(4, field.getText().length());
            assertTrue(field.getText().contains("x"));

            double[] outside = editor.screenPoint(widget.getX() + widget.getWidth() + 240,
                widget.getY() + widget.getHeight() + 180);
            assertTrue(editor.mouseDragged(leftMouse(editor, ReMouseEvent.Action.DRAGGED, outside[0], outside[1], 30, 20)));
            assertTrue(editor.mouseReleased(leftMouse(editor, ReMouseEvent.Action.RELEASED, outside[0], outside[1], 0, 0)));
            assertEquals(1, field.drags.get());
            assertEquals(1, field.releases.get());
            assertEquals(0, editor.workspaceDrags.get());
            assertEquals(0, editor.workspaceReleases.get());

            new NodeRegistry();
            String before = field.getText();
            assertTrue(editor.textInput(new ReTextInputEvent(editor, editor, 0L, ReModifierState.none(), "x", 'x')));
            assertEquals(before, field.getText());
            assertNull(editor.focusedWidget());
            assertFalse(field.isFocused());
            assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED,
                click[0], click[1], 0, 0)));
            assertNull(editor.focusedWidget());
        } finally {
            restoreNodeRegistry(previous);
        }
    }

    @Test
    void productionFieldUsesPanZoomAndRevokesFocusBeforeCaptureAndSaveEarlyRoutes() throws Exception {
        String serverId = "input-routing-production";
        String nodeId = NodeInstanceId.deterministic("input-routing-production-node").canonicalText();
        NodeDefinition.PinDefinition input = new NodeDefinition.PinBuilder("message", NodeDefinition.PinType.DATA,
            NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).widget(NodeDefinition.WidgetType.TEXT).build();
        NodeDefinition definition = new NodeDefinition.Builder("value", "Value", NodeDefinition.NodeCategory.UTILITY)
            .owner("test").input(input).build();
        NodeRegistry previous = NodeRegistry.getInstance();
        try {
            NodeRegistry registry = new NodeRegistry();
            assertTrue(registry.applySnapshot(serverId, canonicalSnapshot(serverId, definition)));
            FlowGraph graph = graph("production-field-route");
            graph.setResourceType("test");
            FlowNode node = new FlowNode("test:value", 120, 110,
                new LinkedHashMap<>(Map.of("message", "old")));
            graph.setNodes(new LinkedHashMap<>(Map.of(nodeId, node)));
            RoutingEditor editor = new RoutingEditor(graph, serverId);
            editor.width = 800;
            editor.height = 500;
            editor.enableReadyStudioRouting();
            editor.setViewport(1.65F, 73F, -41F);
            FlowNodeWidget widget = new FlowNodeWidget(120, 110, node, graph, nodeId, serverId, null, null,
                FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), definition, false, false, mutation -> true);
            widget.render(new TestDrawContext(), 0, 0, 0F);
            TextInputWidget field = (TextInputWidget) inputWidget(widget, "message");
            editor.installNode(nodeId, widget);

            double[] click = editor.screenPoint(field.getX() + 3, field.getY() + 3);
            assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, click[0], click[1], 0, 0)));
            assertSame(field, editor.focusedWidget());

            new NodeRegistry();
            double[] outside = editor.screenPoint(widget.getX() + widget.getWidth() + 300,
                widget.getY() + widget.getHeight() + 220);
            assertTrue(editor.mouseDragged(leftMouse(editor, ReMouseEvent.Action.DRAGGED,
                outside[0], outside[1], 25, 15)));
            assertNull(editor.focusedWidget());
            assertFalse(field.isFocused());
            assertEquals(0, editor.workspaceDrags.get());

            NodeRegistry restoredAuthority = new NodeRegistry();
            assertTrue(restoredAuthority.applySnapshot(serverId, canonicalSnapshot(serverId, definition)));
            assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, click[0], click[1], 0, 0)));
            assertSame(field, editor.focusedWidget());
            new NodeRegistry();
            AtomicInteger contextActions = new AtomicInteger();
            ContextMenuWidget menu = editor.openMenu(30, 30, contextActions::incrementAndGet);
            menu.render(new TestDrawContext(), 34, 34, 0F);
            assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, 34, 34, 0, 0)));
            assertEquals(1, contextActions.get());
            assertNull(editor.focusedWidget());
            assertFalse(field.isFocused());

            restoredAuthority = new NodeRegistry();
            assertTrue(restoredAuthority.applySnapshot(serverId, canonicalSnapshot(serverId, definition)));
            assertTrue(editor.mouseClicked(leftMouse(editor, ReMouseEvent.Action.PRESSED, click[0], click[1], 0, 0)));
            assertSame(field, editor.focusedWidget());
            new NodeRegistry();
            ReKeyEvent save = new ReKeyEvent(editor, editor, 0L,
                new ReModifierState(false, true, false, false, false, false), ReKeyEvent.Action.PRESSED,
                ReKey.S, 0, 0, ReKeyLocation.STANDARD, false);
            assertTrue(editor.keyPressed(save));
            assertNull(editor.focusedWidget());
            assertFalse(field.isFocused());
            assertEquals(1, editor.saveRoutes.size());
        } finally {
            restoreNodeRegistry(previous);
        }
    }

    private static ReMouseEvent leftMouse(Object source, ReMouseEvent.Action action, double x, double y,
                                           double deltaX, double deltaY) {
        return new ReMouseEvent(source, source, 0L, ReModifierState.none(), action, x, y, deltaX, deltaY,
            ReMouseButton.LEFT, 0, 1);
    }

    private static FlowGraph graph(String id) {
        FlowGraph graph = new FlowGraph();
        graph.setId(id);
        graph.setResourceType("flow");
        return graph;
    }

    @SuppressWarnings("unchecked")
    private static NodeRegistrySnapshot canonicalSnapshot(String serverId, NodeDefinition definition) {
        CatalogSnapshot base = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        Map<String, Object> document = new LinkedHashMap<>((Map<String, Object>) CanonicalJson.parse(base.canonicalContent()));
        document.put("definitions", List.of(Map.of("ownerId", definition.getOwner(), "id", definition.getId(),
            "metadata", Map.of("sourceNodeId", definition.getId()))));
        document.remove("contentChecksum");
        document.put("contentChecksum", "");
        String withoutChecksum = CanonicalJson.canonicalize(document);
        document.put("contentChecksum", CatalogCanonicalizer.checksumForCanonicalContent(withoutChecksum).canonicalText());
        String content = CanonicalJson.canonicalize(document);
        NodePluginPayload plugin = new NodePluginPayload();
        plugin.setPluginId(definition.getOwner());
        plugin.setChecksum("input-routing");
        plugin.setNodes(List.of(definition));
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        List<String> capabilities = new ArrayList<>(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
        capabilities.addAll(List.of("catalog_authority", "catalog_canonical"));
        snapshot.setCapabilities(capabilities);
        snapshot.setFullSync(true);
        snapshot.setServerIdentity(serverId);
        snapshot.setNodeIds(List.of(definition.getId()));
        snapshot.setPlugins(List.of(plugin));
        snapshot.setCatalogGeneration(base.generation());
        snapshot.setCatalogChecksum(CatalogCanonicalizer.checksumForCanonicalContent(content).canonicalText());
        snapshot.setCatalogProjectionIdentity(base.bindingManifestHash().canonicalText());
        snapshot.setCatalogMetadata(Map.of("bindingManifestHash", base.bindingManifestHash().canonicalText()));
        snapshot.setOpaqueData(Map.of("catalogMetadata", Map.of(NodeRegistrySnapshot.CANONICAL_CATALOG_CONTENT_KEY,
            content, "bindingManifestHash", base.bindingManifestHash().canonicalText())));
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static void installInputWidget(NodeWidget widget, String pin, Widget input) throws Exception {
        Field inputWidgets = NodeWidget.class.getDeclaredField("inputWidgets");
        inputWidgets.setAccessible(true);
        ((Map<String, Widget>) inputWidgets.get(widget)).put(pin, input);
    }

    @SuppressWarnings("unchecked")
    private static Widget inputWidget(NodeWidget widget, String pin) throws Exception {
        Field inputWidgets = NodeWidget.class.getDeclaredField("inputWidgets");
        inputWidgets.setAccessible(true);
        return ((Map<String, Widget>) inputWidgets.get(widget)).get(pin);
    }

    private static void restoreNodeRegistry(NodeRegistry registry) throws Exception {
        Field instance = NodeRegistry.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, registry);
    }

    private record SaveRoute(String id, String type) {
    }

    private static final class RoutingEditor extends GraphEditorScreen {
        private final AtomicInteger workspacePresses = new AtomicInteger();
        private final AtomicInteger workspaceDrags = new AtomicInteger();
        private final AtomicInteger workspaceReleases = new AtomicInteger();
        private final List<SaveRoute> saveRoutes = new ArrayList<>();
        private final SaveRoute route;
        private boolean forceWorkspaceCapturePending;

        private RoutingEditor(FlowGraph graph) {
            this(graph, "input-routing");
        }

        private RoutingEditor(FlowGraph graph, String serverId) {
            super(graph, serverId, new Screen());
            route = new SaveRoute(graph.getId(), graph.getResourceType());
        }

        private void installNode(String nodeId, FlowNodeWidget widget) throws Exception {
            Method cache = GraphEditorScreen.class.getDeclaredMethod("cacheNodeWidget", String.class,
                FlowNodeWidget.class);
            cache.setAccessible(true);
            cache.invoke(this, nodeId, widget);
            addWorldWidget(widget);
        }

        private double[] screenPoint(double worldX, double worldY) {
            return worldToScreen(worldX, worldY);
        }

        private double[] worldPoint(double screenX, double screenY) {
            return screenToWorld(screenX, screenY);
        }

        private boolean nodeDragging() {
            return draggedWidget != null;
        }

        private void setViewport(float zoom, float horizontalPan, float verticalPan) {
            zoomLevel = zoom;
            targetZoomLevel = zoom;
            panX = horizontalPan;
            targetPanX = horizontalPan;
            panY = verticalPan;
            targetPanY = verticalPan;
        }

        private Widget focusedWidget() {
            return getFocusedWidget();
        }

        private void enableReadyStudioRouting() {
            studioMode = true;
        }

        private void installHeaderButtons() {
            createHeaderButtons();
        }

        private ContextMenuWidget openMenu(int x, int y, Runnable action) {
            showStudioContextMenu(x, y, new ContextMenuWidget.Builder(this).addItem("Open", action, "Open"));
            return getScreenWidgetRoots().stream()
                .filter(ContextMenuWidget.class::isInstance)
                .map(ContextMenuWidget.class::cast)
                .findFirst()
                .orElseThrow();
        }

        private ContextMenuWidget openHeaderMenu(int x, int y, Runnable action) {
            showStudioContextMenu(x, y, new ContextMenuWidget.Builder(this)
                .addHeaderButton("info.png", action, "Inspect"));
            return getScreenWidgetRoots().stream()
                .filter(ContextMenuWidget.class::isInstance)
                .map(ContextMenuWidget.class::cast)
                .findFirst()
                .orElseThrow();
        }

        private ContextMenuWidget currentMenu() {
            return getScreenWidgetRoots().stream()
                .filter(ContextMenuWidget.class::isInstance)
                .map(ContextMenuWidget.class::cast)
                .filter(ContextMenuWidget::isOpen)
                .findFirst()
                .orElseThrow();
        }

        private void attachBrowser(BrowserHarness browser) {
            studioContentBrowser = browser;
        }

        @Override
        protected boolean workspaceSnapshotPending() {
            return forceWorkspaceCapturePending || super.workspaceSnapshotPending();
        }

        @Override
        protected boolean handleStudioWorkspaceMouseClicked(ReMouseEvent event) {
            workspacePresses.incrementAndGet();
            return studioContentBrowser != null && studioContentBrowser.mouseClicked(event);
        }

        @Override
        protected boolean handleStudioWorkspaceMouseDragged(ReMouseEvent event) {
            workspaceDrags.incrementAndGet();
            return true;
        }

        @Override
        protected boolean handleStudioWorkspaceMouseReleased(ReMouseEvent event) {
            workspaceReleases.incrementAndGet();
            return true;
        }

        @Override
        protected void onSave() {
            saveRoutes.add(route);
        }

        @Override
        protected boolean showDebugControls() {
            return false;
        }

        @Override
        protected boolean shouldShowBackButton() {
            return false;
        }
    }

    private static final class TrackingTextInput extends TextInputWidget {
        private final AtomicInteger drags = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();

        private TrackingTextInput() {
            super(0, 0, 90, 16);
        }

        @Override
        public boolean mouseDragged(ReMouseEvent event) {
            drags.incrementAndGet();
            return super.mouseDragged(event);
        }

        @Override
        public boolean mouseReleased(ReMouseEvent event) {
            releases.incrementAndGet();
            return super.mouseReleased(event);
        }
    }

    private static final class BrowserHarness extends ReSyncContentBrowserWidget {
        private final AtomicInteger rowPresses = new AtomicInteger();

        private BrowserHarness(RoutingEditor screen, int x, int y, int width, int height) {
            super(screen, x, y, width, height);
        }

        private boolean openResourceMenu(ReSyncProjectMetadata.ResourceEntry resource, int x, int y)
            throws ReflectiveOperationException {
            Field selected = ReSyncContentBrowserWidget.class.getDeclaredField("selectedResource");
            selected.setAccessible(true);
            selected.set(this, resource);
            return showExplorerMenu(x, y);
        }

        private boolean hasClipboard() throws ReflectiveOperationException {
            Field clipboard = ReSyncContentBrowserWidget.class.getDeclaredField("clipboard");
            clipboard.setAccessible(true);
            return clipboard.get(this) != null;
        }

        @Override
        public boolean mouseClicked(ReMouseEvent event) {
            rowPresses.incrementAndGet();
            return super.mouseClicked(event);
        }
    }

    private static final class PendingSelectionEditor extends GraphEditorScreen {
        private final AtomicInteger saveAttempts = new AtomicInteger();

        private PendingSelectionEditor(FlowGraph graph) {
            super(graph, "pending-selection", new Screen());
        }

        private void prepareDocuments() {
            studioMode = true;
            createStudioWorkspaceChrome(false);
            studioDocuments.add(new StudioDocument("flow", "legacy", "Legacy", graph("legacy"), null, null,
                new StudioViewportState()));
            studioDocuments.add(new StudioDocument("flow", "core", "Core", graph("core"), null, null,
                new StudioViewportState()));
            syncStudioDocumentTabs();
            selectStudioDocument(ReSyncProjectMetadata.resourceKey("flow", "legacy"));
            createHeaderButtons();
        }

        private void selectCoreTab() {
            TabsManager.Tab tab = findStudioTab(ReSyncProjectMetadata.resourceKey("flow", "core"),
                studioTabsManager.getTabs());
            studioTabsManager.setActiveTab(tab.getContainer());
        }

        private String activeDocumentKey() {
            return activeStudioDocument != null ? activeStudioDocument.key() : "";
        }

        private String activeTabKey() {
            Object data = studioTabsManager.getActiveTab() != null ? studioTabsManager.getActiveTab().getData() : null;
            return data instanceof String key ? key : "";
        }

        @Override
        protected StudioDocument rebindCoreStudioDocument(StudioDocument document) {
            return document != null && "core".equals(document.id()) ? null : document;
        }

        @Override
        protected boolean isCoreStudioDocumentPending(StudioDocument document) {
            return document != null && "core".equals(document.id());
        }

        @Override
        protected void saveGraph() {
            saveAttempts.incrementAndGet();
        }

        @Override
        protected boolean showDebugControls() {
            return false;
        }

        @Override
        protected boolean shouldShowBackButton() {
            return false;
        }
    }
}
