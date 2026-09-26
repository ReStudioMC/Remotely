package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowWorkspaceDocument;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.ReSyncResourceCreator;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioResourceRenameAware;
import redxax.oxy.remotely.flow.ui.studio.StudioSaveProvider;
import redxax.oxy.remotely.flow.ui.studio.ReSyncNaming;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.game.MinecraftAssetReference;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.game.MinecraftRenderItem;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.CompactBindingWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.workspace.WorkspacePatch;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static restudio.rescreen.config.Config.desktopMode;

public class AdvancementDesignerScreen extends StudioScreen implements DesktopWindowBehaviorProvider, StudioCloseHandledScreen, StudioResourceRenameAware, ReSyncCollaborativeView, StudioSaveProvider {
    private static final Set<AdvancementDesignerScreen> OPEN_SCREENS = BrowserSafeState.set();
    private static final String BLOCK_CATALOG = "server:minecraft:block";
    private static final String BIOME_CATALOG = "server:minecraft:biome";
    private static final String ENTITY_CATALOG = "server:minecraft:entity_type";
    private static final String POTION_EFFECT_CATALOG = "server:minecraft:potion_effect";
    private static final String RECIPE_CATALOG = "server:minecraft:recipe";
    private static final String WORLD_CATALOG = "server:minecraft:world";
    private static final OptionCatalogLoader.Profile CATALOGS = OptionCatalogLoader.profile(
        BLOCK_CATALOG, BIOME_CATALOG, ENTITY_CATALOG, POTION_EFFECT_CATALOG, RECIPE_CATALOG, WORLD_CATALOG);
    private static final int WINDOW_WIDTH = 252;
    private static final int WINDOW_HEIGHT = 140;
    private static final int VIEWPORT_X = 9;
    private static final int VIEWPORT_Y = 18;
    private static final int VIEWPORT_WIDTH = 234;
    private static final int VIEWPORT_HEIGHT = 113;
    private static final int ADVANCEMENT_X_SCALE = 28;
    private static final int ADVANCEMENT_Y_SCALE = 27;
    private static final int NODE_WIDTH = 26;
    private static final int NODE_HEIGHT = 26;
    private static final int FRAME_X = 3;
    private static final int ICON_X = 8;
    private static final int ICON_Y = 5;
    private static final int TITLE_BOX_MIN_WIDTH = 109;
    private static final int TITLE_TEXT_MAX_WIDTH = 163;
    private static final int TITLE_TEXT_X = 32;
    private static final int DESCRIPTION_TEXT_X = 5;
    private static final int TEXT_LINE_HEIGHT = 9;
    private static final int TITLE_BOX_EXTRA_HEIGHT = 17;
    private static final int DESCRIPTION_BOX_EXTRA_HEIGHT = 6;
    private static final int DESCRIPTION_TEXT_Y_OFFSET = -1;
    private static final int TOOLTIP_SLICE_WIDTH = 200;
    private static final int TOOLTIP_SLICE_HEIGHT = 26;
    private static final int TOOLTIP_SLICE_BORDER = 10;
    private static final int BACKGROUND_TILE_SIZE = 16;
    private static final int DRAG_AUTO_PAN_EDGE = 18;
    private static final double DRAG_AUTO_PAN_SPEED = 4.0;
    private static final int[] DESCRIPTION_SPLIT_OFFSETS = {0, 10, -10, 25, -25};
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private JsonObject tree;
    private final VersionedEditorDraft<JsonObject> treeDraft;
    private final String serverId;
    private final Object parent;
    private final boolean forceSuperScreen;
    private final boolean animateTopHeader;
    private final ReSyncStudioPanelState panelState = new ReSyncStudioPanelState();
    private final History<String> history = history(() -> gson.toJson(tree), this::restore);
    private final HistoryRebaseCoordinator<String> historyRebase;
    private volatile long collaborationLifecycle = 1L;

    @Override
    public void resourceRenamed(String type, String oldId, String newId) {
        if (treeDraft.defer(() -> resourceRenamed(type, oldId, newId))) {
            return;
        }
        treeDraft.markMutation();
        if (oldId.equals(ReSyncResourceType.ADVANCEMENT_TREE.extractId(tree))) {
            ReSyncResourceType.ADVANCEMENT_TREE.applyRename(tree, newId);
        }
    }

    private final List<DropDownWidget<String>> panelDropdowns = new ArrayList<>();
    private final List<AnimatedWidget> inspectorPanelWidgets = new ArrayList<>();
    private final List<AnimatedWidget> logicPanelWidgets = new ArrayList<>();
    private final List<AnimatedWidget> rootMetaPanelWidgets = new ArrayList<>();
    private final List<AnimatedWidget> dynamicPanelWidgets = new ArrayList<>();
    private final List<DynamicPanelMount> pendingDynamicPanelMounts = new ArrayList<>();
    private StudioPanel inspectorPanel;
    private SidePanel inspector;
    private ItemSelectorWidget activeSearchSelector;
    private Supplier<List<String>> activeSelectorOptions;
    private Supplier<String> activeSelectorSelected;
    private Consumer<String> activeSelectorOnSelected;
    private String activeSelectorCatalogSource;
    private boolean activeSelectorUsesRecipeCatalog;
    private String lastInspectorNode = "";
    private String lastDynamicStructure = "";
    private CompactBindingWidget dynamicCompletionBinding;
    private TitledRowWidget dynamicCompletionRow;
    private CompactBindingWidget dynamicPredicateBinding;
    private TitledRowWidget dynamicPredicateRow;
    private TextInputWidget dynamicXpInput;
    private TextInputWidget dynamicLootInput;
    private TextInputWidget dynamicRecipesInput;
    private CompactBindingWidget dynamicRunBinding;
    private TitledRowWidget dynamicRunRow;
    private AnimatedButton backgroundButton;
    private ToggleWidget enabledToggle;
    private TextInputWidget treeNameInput;
    private TextInputWidget nodeIdInput;
    private TextInputWidget titleInput;
    private TextInputWidget descriptionInput;
    private String inspectorEditNodeId = "root";
    private boolean syncingInspector;
    private IconButton iconButton;
    private String iconButtonValue;
    private DropDownWidget<String> frameDropdown;
    private ToggleWidget showToastToggle;
    private ToggleWidget announceChatToggle;
    private ToggleWidget hiddenToggle;
    private DropDownWidget<String> parentDropdown;
    private DropDownWidget<String> completionSourceDropdown;
    private DropDownWidget<String> rewardTypeDropdown;
    private DropDownWidget<String> onCompleteTypeDropdown;
    private TitledRowWidget backgroundRow;
    private TitledRowWidget treeNameRow;
    private TitledRowWidget nodeIdRow;
    private TitledRowWidget enabledRow;
    private TitledRowWidget titleRow;
    private TitledRowWidget descriptionRow;
    private TitledRowWidget iconRow;
    private TitledRowWidget frameRow;
    private TitledRowWidget showToastRow;
    private TitledRowWidget announceChatRow;
    private TitledRowWidget hiddenRow;
    private TitledRowWidget parentRow;
    private TitledRowWidget completionSourceRow;
    private TitledRowWidget rewardTypeRow;
    private TitledRowWidget onCompleteTypeRow;
    private String selectedNode = "root";
    private String draggedNode;
    private String dragTargetNode;
    private double panX;
    private double panY;
    private double dragOffsetX;
    private double dragOffsetY;
    private double dragViewPanX;
    private double dragViewPanY;
    private boolean nodeDragMoved;
    private boolean customPan;
    private boolean closingRequested;
    private boolean closeCompleted;
    private boolean studioCloseNotified;
    private Runnable studioCloseHandler;
    private boolean collectingDynamicPanelMounts;

    @Override
    public JsonObject collaborationDocument() {
        commitInspectorEdits(inspectorEditNodeId);
        return tree.deepCopy();
    }

    @Override
    public long collaborationLifecycle() {
        return collaborationLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return treeDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return treeDraft.requestProjection(ReSyncResourceType.ADVANCEMENT_TREE.typeId(),
            ReSyncResourceType.ADVANCEMENT_TREE.extractId(tree), this::collaborationDocument,
            projection -> completion.accept(collaborationSnapshot(lifecycle, projection)));
    }

    private CollaborationDocumentSnapshot collaborationSnapshot(long lifecycle,
                                                                 VersionedEditorDraft.ProjectionSnapshot projection) {
        RuntimeException failure = projection.failure();
        JsonObject document = null;
        if (failure == null) {
            try {
                document = gson.fromJson(projection.payload(), JsonObject.class);
                if (document == null) {
                    failure = new IllegalStateException("Collaboration Snapshot Is Empty");
                }
            } catch (RuntimeException | Error exception) {
                failure = exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
            }
        }
        if (failure == null && lifecycle != collaborationLifecycle) {
            document = null;
            failure = new IllegalStateException("Collaboration Snapshot Expired");
        }
        return new CollaborationDocumentSnapshot(this, lifecycle, projection.editVersion(), document, failure);
    }

    @Override
    public boolean applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                              Consumer<CollaborationDocumentApplyResult> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return treeDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (tree == null) {
                throw new IllegalStateException("Advancement Tree Is Unavailable");
            }
            applyCollaborationDocument(document, patches);
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (treeDraft.defer(() -> applyCollaborationDocument(document, patches))) {
            return;
        }
        treeDraft.markMutation();
        restore(gson.toJson(document));
    }

    @Override
    public void rebaseCollaborationHistory(List<WorkspacePatch<JsonElement>> patches) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        historyRebase.request(patches, copiedPatches -> snapshot -> {
            JsonObject historic = gson.fromJson(gson.toJson(snapshot), JsonObject.class);
            if (historic == null) {
                historic = new JsonObject();
            }
            FlowWorkspaceDocument.apply(historic, copiedPatches);
            return gson.toJson(gson.fromJson(gson.toJson(historic), JsonObject.class));
        });
    }

    private record TooltipLayout(String id, JsonObject node, int nodeX, int nodeY, int boxX, int titleY, int boxWidth, int titleHeight, int descriptionY, int descriptionTextY, int descriptionHeight, boolean flippedLeft, List<String> titleLines, List<String> descriptionLines) {
    }

    private record DynamicPanelMount(AnimatedWidget anchor, AnimatedWidget widget) {
    }

    private record LayoutNode(String id, JsonObject node, String parentId, List<LayoutNode> children, int depth, int x, float y, int childIndex, LayoutNode parent, LayoutNode previousSibling) {
    }

    private record AdvancementLayout(Map<String, LayoutNode> nodes, int minX, int minY, int maxX, int maxY) {
        LayoutNode node(String id) {
            return id != null ? nodes.get(id) : null;
        }
    }

    private static final class LayoutWorkNode {
        private final String id;
        private final JsonObject node;
        private final LayoutWorkNode parent;
        private final LayoutWorkNode previousSibling;
        private final int childIndex;
        private final List<LayoutWorkNode> children = new ArrayList<>();
        private LayoutWorkNode ancestor;
        private LayoutWorkNode thread;
        private int x;
        private float y = -1.0f;
        private float mod;
        private float change;
        private float shift;

        private LayoutWorkNode(String id, JsonObject node, LayoutWorkNode parent, LayoutWorkNode previousSibling, int childIndex, int x) {
            this.id = id;
            this.node = node;
            this.parent = parent;
            this.previousSibling = previousSibling;
            this.childIndex = childIndex;
            this.x = x;
            this.ancestor = this;
        }
    }

    public AdvancementDesignerScreen(JsonObject tree, String serverId, Object parent) {
        this(tree, serverId, parent, !(parent instanceof Screen));
    }

    public AdvancementDesignerScreen(JsonObject tree, String serverId, Object parent, boolean forceSuperScreen) {
        this(tree, serverId, parent, forceSuperScreen, false);
    }

    public AdvancementDesignerScreen(JsonObject tree, String serverId, Object parent, boolean forceSuperScreen, boolean animateTopHeader) {
        this.tree = tree != null ? tree : new JsonObject();
        this.serverId = serverId;
        this.parent = parent;
        this.forceSuperScreen = forceSuperScreen;
        this.animateTopHeader = animateTopHeader;
        autoResizeContainers = false;
        preserveStateOnDisplay = true;
        OPEN_SCREENS.add(this);
        treeDraft = new VersionedEditorDraft<>(this.tree, JsonObject::toString,
            payload -> JsonParser.parseString(payload).getAsJsonObject(), this::rebindTree, this::failSnapshot,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        historyRebase = new HistoryRebaseCoordinator<>(history,
            () -> serverId + ":" + ReSyncResourceType.ADVANCEMENT_TREE.extractId(tree), () -> collaborationLifecycle,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
    }

    public String getDesktopAppId() {
        return "advancement-designer";
    }

    public String getDesktopAppTitle() {
        return "Advancement Designer";
    }

    public String getDesktopAppIconPath() {
        return "advancement.png";
    }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() {
        return DesktopWindowBehavior.SINGLETON;
    }

    @Override
    public boolean shouldForceSuperScreen() {
        return desktopMode && forceSuperScreen;
    }

    public static void refreshCatalogForServer(String serverId) {
        for (AdvancementDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                screen.onOptionCatalogRefreshed();
            }
        }
    }

    public static void refreshFlowBindingsForServer(String serverId) {
        refreshFlowBindingsForServer(serverId, null);
    }

    public static boolean hasOpenScreenForServer(String serverId) {
        for (AdvancementDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasFlowBindingForServer(String serverId, String flowId) {
        for (AdvancementDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId) && screen.hasFlowBinding(flowId)) {
                return true;
            }
        }
        return false;
    }

    public static void refreshFlowBindingsForServer(String serverId, String flowId) {
        for (AdvancementDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                screen.refreshDynamicBindings(flowId);
            }
        }
    }

    private boolean hasFlowBinding(String flowId) {
        return dynamicCompletionBinding != null && dynamicCompletionBinding.referencesTarget(flowId)
            || dynamicPredicateBinding != null && dynamicPredicateBinding.referencesTarget(flowId)
            || dynamicRunBinding != null && dynamicRunBinding.referencesTarget(flowId);
    }

    @Override
    public void init() {
        super.init();
        closingRequested = false;
        closeCompleted = false;
        studioCloseNotified = false;
        header().reset();
        if (shouldShowBackButton()) {
            header().addRight("close.png", this::requestClose, "Back");
        }
        header().addRight("save.png", this::save, "Save");
        header().addRight("delete.png", this::deleteSelected, "Delete Node");
        header().addRight("add.png", this::addNode, "Add Node");
        header().build();
        if (animateTopHeader) {
            startTopHeaderOpeningAnimation();
        }
        preloadCatalogs();
        ensureInspectorPanel();
        applyInspectorSelection();
    }

    private boolean shouldShowBackButton() {
        return !desktopMode || shouldForceSuperScreen();
    }

    private void onOptionCatalogRefreshed() {
        if (inspector != null) {
            applyInspectorSelection();
        }
        if (activeSearchSelector != null && activeSearchSelector.visible && !activeSelectorUsesRecipeCatalog && activeSelectorCatalogSource == null
            && activeSelectorSelected != null && activeSelectorOnSelected != null) {
            int selectorX = activeSearchSelector.getX();
            int selectorY = activeSearchSelector.getY();
            Supplier<List<String>> options = activeSelectorOptions;
            Supplier<String> selected = activeSelectorSelected;
            Consumer<String> onSelected = activeSelectorOnSelected;
            String catalogSource = activeSelectorCatalogSource;
            closeActiveSearchSelector();
            if (catalogSource != null && !catalogSource.isBlank()) {
                openCatalogSearchSelector(options, catalogSource, selected, onSelected, selectorX, selectorY);
            } else if (options != null) {
                openSearchSelector(options, selected, onSelected, selectorX, selectorY);
            }
        }
    }

    private void refreshDynamicBindings() {
        refreshDynamicBindings(null);
    }

    private void refreshDynamicBindings(String flowId) {
        if (dynamicCompletionBinding != null) {
            if (flowId == null || dynamicCompletionBinding.referencesTarget(flowId)) {
                dynamicCompletionBinding.refresh();
            }
        }
        if (dynamicPredicateBinding != null) {
            if (flowId == null || dynamicPredicateBinding.referencesTarget(flowId)) {
                dynamicPredicateBinding.refresh();
            }
        }
        if (dynamicRunBinding != null) {
            if (flowId == null || dynamicRunBinding.referencesTarget(flowId)) {
                dynamicRunBinding.refresh();
            }
        }
    }

    private void preloadCatalogs() {
        ItemOptionCatalog.ensureLoaded(serverId);
        CATALOGS.preload(serverId);
    }

    private void requestCatalog(String source) {
        requestCatalog(source, false);
    }

    private void requestCatalog(String source, boolean forceRefresh) {
        if (forceRefresh) {
            OptionCatalogLoader.refresh(serverId, source);
        } else {
            OptionCatalogLoader.preload(serverId, source);
        }
    }

    @Override
    public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
        historyRebase.drain();
        treeDraft.drain();
        super.renderHandler(context, mouseX, mouseY, delta);
        if (inspectorPanel != null && inspector != null && (inspector.isVisible() || inspector.getAnimatedWidth() > 1f)) {
            renderStudioPanel(inspectorPanel, context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        updateTopHeaderAnimation();
        updateInspectorLayout();
        updateCloseAnimation();
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void updatePositions() {
        super.updatePositions();
        updateInspectorLayout();
    }

    @Override
    public void renderBackground(IDrawContext context, int mouseX, int mouseY, float delta) {
        if (forceSuperScreen) {
            MinecraftScreenDarkening.renderLight(context, width, height);
        } else {
            super.renderBackground(context, mouseX, mouseY, delta);
        }
        int windowX = advancementWindowX();
        int windowY = advancementWindowY();
        int contentX = windowX + VIEWPORT_X + 1;
        int contentY = windowY + VIEWPORT_Y - 1;
        MinecraftGameAssets gameAssets = getGameAssets();
        JsonObject nodes = nodes();
        AdvancementLayout layout = layout(nodes);
        double viewPanX = viewPanX(layout);
        double viewPanY = viewPanY(layout);
        drawAdvancementBackground(context, gameAssets, windowX, windowY);
        context.pushScissorState();
        context.enableScissor(contentX, contentY, contentX + VIEWPORT_WIDTH, contentY + VIEWPORT_HEIGHT);
        drawTiledBackground(context, gameAssets, contentX, contentY, viewPanX, viewPanY);
        drawViewportDepth(context, contentX, contentY);
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            JsonObject node = entry.getValue().getAsJsonObject();
            drawConnection(context, layout, entry.getKey(), node, contentX, contentY, viewPanX, viewPanY, true);
        }
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            JsonObject node = entry.getValue().getAsJsonObject();
            drawConnection(context, layout, entry.getKey(), node, contentX, contentY, viewPanX, viewPanY, false);
        }
        if (isDraggingNode() && dragTargetNode != null) {
            drawDragTargetConnection(context, layout, contentX, contentY, viewPanX, viewPanY);
        }
        TooltipLayout tooltipLayout = null;
        boolean mouseInViewport = mouseX > contentX && mouseX < contentX + VIEWPORT_WIDTH && mouseY > contentY && mouseY < contentY + VIEWPORT_HEIGHT;
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            JsonObject node = entry.getValue().getAsJsonObject();
            boolean hidden = hidden(node);
            int x = nodeX(layout, entry.getKey(), contentX, viewPanX);
            int y = nodeY(layout, entry.getKey(), contentY, viewPanY);
            int frameX = x + FRAME_X;
            if (mouseInViewport && mouseX >= frameX && mouseX <= frameX + NODE_WIDTH && mouseY >= y && mouseY <= y + NODE_HEIGHT) {
                tooltipLayout = tooltipLayout(layout, entry.getKey(), node, contentX, contentY, viewPanX, viewPanY);
            }
            drawSprite(context, gameAssets, frameSprite(node, entry.getKey().equals(selectedNode)), frameX, y, NODE_WIDTH, NODE_HEIGHT);
            if (entry.getKey().equals(dragTargetNode)) {
                context.fill(frameX - 2, y - 2, frameX + NODE_WIDTH + 2, y + NODE_HEIGHT + 2, 0x66FFFFFF);
            }
            MinecraftRenderItem icon = icon(node);
            if (icon != null) {
                context.drawItem(icon, x + ICON_X, y + ICON_Y, 0);
            }
            if (hidden) {
                drawHiddenNodeMarker(context, x + FRAME_X, y);
            }
        }
        if (tooltipLayout != null) {
            context.pushScissorState();
            context.clearScissor();
            drawTooltip(context, gameAssets, tooltipLayout);
            context.popScissorState();
        }
        context.popScissorState();
    }


    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (dispatchSidePanelMouseClicked(event)) {
            return true;
        }
        if (event.button() == ReMouseButton.LEFT && mouseClickedViewport(event.x(), event.y())) {
            setFocusedWidget(null);
            return true;
        }
        return super.mouseClicked(event);
    }

    private boolean mouseClickedViewport(double mouseX, double mouseY) {
        int contentX = advancementWindowX() + VIEWPORT_X;
        int contentY = advancementWindowY() + VIEWPORT_Y;
        if (mouseX < contentX || mouseX > contentX + VIEWPORT_WIDTH || mouseY < contentY || mouseY > contentY + VIEWPORT_HEIGHT) {
            return false;
        }
        JsonObject nodes = nodes();
        AdvancementLayout layout = layout(nodes);
        double viewPanX = viewPanX(layout);
        double viewPanY = viewPanY(layout);
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            JsonObject node = entry.getValue().getAsJsonObject();
            int x = nodeX(layout, entry.getKey(), contentX, viewPanX);
            int y = nodeY(layout, entry.getKey(), contentY, viewPanY);
            int frameX = x + FRAME_X;
            if (mouseX >= frameX && mouseX <= frameX + NODE_WIDTH && mouseY >= y && mouseY <= y + NODE_HEIGHT) {
                selectNode(entry.getKey());
                draggedNode = entry.getKey();
                dragOffsetX = mouseX - x;
                dragOffsetY = mouseY - y;
                dragViewPanX = viewPanX;
                dragViewPanY = viewPanY;
                dragTargetNode = null;
                nodeDragMoved = false;
                return true;
            }
        }
        draggedNode = "";
        return true;
    }


    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (dispatchSidePanelMouseDragged(event)) {
            return true;
        }
        if (event.button() != ReMouseButton.LEFT || draggedNode == null) {
            return super.mouseDragged(event);
        }
        if (draggedNode.isBlank()) {
            JsonObject nodes = nodes();
            AdvancementLayout layout = layout(nodes);
            if (!canPanViewport(layout)) {
                return true;
            }
            beginCustomPan(layout);
            panX += event.deltaX();
            panY += event.deltaY();
            clampPan(layout);
            return true;
        }
        JsonObject nodes = nodes();
        AdvancementLayout layout = layout(nodes);
        nodeDragMoved = true;
        updateDragBranchTarget(layout, event.x(), event.y());
        autoPanDraggedNode(layout, event.x(), event.y());
        refreshJson();
        return true;
    }


    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (dispatchSidePanelMouseReleased(event)) {
            return true;
        }
        if (event.button() == ReMouseButton.LEFT && isDraggingNode()) {
            commitDragBranchChange();
            draggedNode = null;
            dragTargetNode = null;
            nodeDragMoved = false;
            return true;
        }
        draggedNode = null;
        dragTargetNode = null;
        nodeDragMoved = false;
        return super.mouseReleased(event);
    }


    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        if (activeSearchSelector != null && activeSearchSelector.visible) {
            if (activeSearchSelector.mouseScrolled(event.retarget(activeSearchSelector, event.x(), event.y()))
                || activeSearchSelector.isMouseOver(event.x(), event.y())) {
                return true;
            }
        }
        for (DropDownWidget<String> dropdown : panelDropdowns) {
            if (dropdown.isExpanded() && dropdown.isMouseOver(event.x(), event.y())) {
                dropdown.mouseScrolled(event.retarget(dropdown, event.x(), event.y()));
                return true;
            }
        }
        if (inspector != null && inspector.isVisible()) {
            if (inspector.mouseScrolled(event.retarget(inspector, event.x(), event.y()))) {
                return true;
            }
            if (inspector.isMouseOver(event.x(), event.y())) {
                return true;
            }
        }
        if (super.mouseScrolled(event)) {
            return true;
        }
        int contentX = advancementWindowX() + VIEWPORT_X;
        int contentY = advancementWindowY() + VIEWPORT_Y;
        if (event.x() < contentX || event.x() > contentX + VIEWPORT_WIDTH || event.y() < contentY || event.y() > contentY + VIEWPORT_HEIGHT) {
            return false;
        }
        JsonObject nodes = nodes();
        AdvancementLayout layout = layout(nodes);
        if (!canPanViewport(layout)) {
            return false;
        }
        beginCustomPan(layout);
        panX += event.horizontalAmount() * 16;
        panY += event.verticalAmount() * 16;
        clampPan(layout);
        return true;
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (handleStudioSaveShortcut(event)) {
            return true;
        }
        if (activeSearchSelector != null && activeSearchSelector.visible && activeSearchSelector.keyPressed(event.retarget(activeSearchSelector))) {
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        if (event.key() == ReKey.ESCAPE) {
            requestClose();
            return true;
        }
        if ((event.key() == ReKey.DELETE || event.key() == ReKey.BACKSPACE) && !isStudioKeyboardInputFocused()) {
            deleteSelected();
            return true;
        }
        return false;
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        if (activeSearchSelector != null && activeSearchSelector.visible && activeSearchSelector.textInput(event.retarget(activeSearchSelector))) {
            return true;
        }
        return super.textInput(event);
    }

    @Override
    public void close() {
        requestClose();
    }

    @Override
    public void setStudioCloseHandler(Runnable closeHandler) {
        this.studioCloseHandler = closeHandler;
    }

    @Override
    public boolean isStudioCloseAnimationFinished() {
        return closeCompleted;
    }

    private void requestClose() {
        if (closeCompleted) {
            return;
        }
        if (desktopMode && isDesktopWindow()) {
            var overlay = ScreenManager.getInstance().getDesktopWindowsOverlay();
            if (overlay != null) {
                overlay.requestCloseWindowForScreen(this);
                return;
            }
        }
        if (!closingRequested) {
            closingRequested = true;
            if (animateTopHeader) {
                startTopHeaderClosingAnimation();
            }
            notifyStudioCloseStarted();
            closeActiveSearchSelector();
            if (inspector != null) {
                inspector.hide();
            }
        }
        updateCloseAnimation();
    }

    private void updateCloseAnimation() {
        if (!closingRequested || closeCompleted) {
            return;
        }
        if ((inspector == null || inspector.getAnimatedWidth() <= 1f) && (!animateTopHeader || isTopHeaderAnimationFinished())) {
            finishClose();
        }
    }

    private void notifyStudioCloseStarted() {
        if (studioCloseHandler != null && !studioCloseNotified) {
            studioCloseNotified = true;
            studioCloseHandler.run();
        }
    }

    private void finishClose() {
        if (closeCompleted) {
            return;
        }
        closeCompleted = true;
        collaborationLifecycle++;
        commitInspectorEdits(inspectorEditNodeId);
        historyRebase.close();
        treeDraft.close();
        OPEN_SCREENS.remove(this);
        closeActiveSearchSelector();
        super.close();
        if (studioCloseHandler != null) {
            if (!studioCloseNotified) {
                studioCloseNotified = true;
                studioCloseHandler.run();
            }
            return;
        }
        if (parent != null) {
            if (RemotelyClient.INSTANCE != null && RemotelyClient.INSTANCE.getHost() != null) {
                RemotelyClient.INSTANCE.getHost().openParentScreen(this, parent);
            } else if (parent instanceof Screen screen) {
                ScreenManager.getInstance().setScreen(screen);
            }
        }
    }

    @Override
    public void removed() {
        collaborationLifecycle++;
        historyRebase.close();
        treeDraft.close();
        OPEN_SCREENS.remove(this);
        closeActiveSearchSelector();
        super.removed();
    }

    private void updateInspectorLayout() {
        if (inspector == null) {
            return;
        }
        if (inspectorPanel != null) {
            inspectorPanel.layout();
        }
    }

    private void ensureInspectorPanel() {
        if (inspector == null) {
            inspectorPanel = rightStudioPanel("advancement_inspector")
                .show();
            inspector = inspectorPanel.sidePanel();
            inspectorPanel.padding(panelState.padding());
        }
        if (titleInput == null) {
            buildInspectorWidgets(inspector.container(), inspectorPanel.rowWidth());
        }
    }

    private void selectNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank() || nodeId.equals(selectedNode)) {
            return;
        }
        selectedNode = nodeId;
        applyInspectorSelection();
    }

    private void applyInspectorSelection() {
        if (inspector == null) {
            return;
        }
        String previousNode = lastInspectorNode;
        if (previousNode != null && !previousNode.isBlank() && !previousNode.equals(selectedNode)) {
            commitInspectorEdits(previousNode);
        }
        inspectorEditNodeId = selectedNode;
        Container container = inspector.container();
        float scrollOffset = container.getScrollOffset();
        int rowWidth = inspectorPanel != null ? inspectorPanel.rowWidth() : panelState.rowWidth(inspector);
        boolean nodeChanged = !selectedNode.equals(previousNode);
        syncingInspector = true;
        try {
            syncInspectorValues(rowWidth, nodeChanged);
            syncInspectorRowStructure(container);
            String structure = dynamicStructureKey();
            boolean dynamicMissing = !"root".equals(selectedNode) && dynamicCompletionBinding == null;
            if (dynamicMissing || !structure.equals(lastDynamicStructure)) {
                rebuildDynamicSection(container, rowWidth);
                lastDynamicStructure = structure;
            } else if (nodeChanged) {
                syncDynamicFieldValues();
            }
        } finally {
            syncingInspector = false;
        }
        lastInspectorNode = selectedNode;
        container.setTargetScrollOffset(scrollOffset);
    }

    private void commitInspectorEdits(String nodeId) {
        if (nodeId == null || nodeId.isBlank() || !nodes().has(nodeId)) {
            return;
        }
        String committedNodeId = nodeId;
        if (nodeIdInput != null && !"root".equals(nodeId)) {
            committedNodeId = renameNodeId(nodeId, nodeIdInput.getText());
        }
        JsonObject node = nodes().getAsJsonObject(committedNodeId);
        JsonObject display = object(node, "display");
        if (titleInput != null) {
            update(display, "title", titleInput.getText());
        }
        if (descriptionInput != null) {
            update(display, "description", descriptionInput.getText());
        }
        if ("root".equals(committedNodeId) && treeNameInput != null) {
            tree.addProperty("displayName", treeNameInput.getText());
        }
    }

    private void buildInspectorWidgets(Container container, int rowWidth) {
        JsonObject root = nodes().has("root") ? nodes().getAsJsonObject("root") : null;
        treeNameInput = panelState.input("Asset Name", text(tree, "displayName"), value -> {
            if (syncingInspector) {
                return;
            }
            snapshot();
            tree.addProperty("displayName", value != null ? value : "");
        });
        treeNameInput.setWidth(rowWidth - 8);
        treeNameRow = panelState.row("Asset Name", treeNameInput, rowWidth, advancementPanelDescription("Asset Name"));
        addInspectorWidget(container, treeNameRow);
        rootMetaPanelWidgets.add(treeNameRow);

        if (root != null) {
            JsonObject rootDisplay = object(root, "display");
            backgroundButton = searchableButton(this::backgroundOptions, () -> backgroundMaterial(object(root, "display")), BLOCK_CATALOG, rowWidth - 8, value -> {
                JsonObject rootNode = nodes().has("root") ? nodes().getAsJsonObject("root") : null;
                if (rootNode != null && isRealOption(value)) {
                    update(object(rootNode, "display"), "background", materialToBackgroundTexture(value));
                }
            });
            backgroundRow = panelState.row("Background", backgroundButton, rowWidth, advancementPanelDescription("Background"));
            addInspectorWidget(container, backgroundRow);
            rootMetaPanelWidgets.add(backgroundRow);
        }

        enabledToggle = toggleWidget(true, value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject node = nodeById(inspectorEditNodeId);
            if (node != null) {
                snapshot();
                update(node, "enabled", value);
            }
        });
        enabledRow = panelState.row("Enabled", enabledToggle, rowWidth, advancementPanelDescription("Enabled"));
        addInspectorWidget(container, enabledRow);

        nodeIdInput = panelState.input("Node ID", selectedNode, value -> {
            if (!syncingInspector) {
                snapshot();
            }
        });
        nodeIdInput.setWidth(rowWidth - 8);
        nodeIdRow = panelState.row("Node ID", nodeIdInput, rowWidth, advancementPanelDescription("Node ID"));
        addInspectorWidget(container, nodeIdRow);
        logicPanelWidgets.add(nodeIdRow);

        parentDropdown = structuralDropdown(parentChoices(), "root", value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject node = nodeById(inspectorEditNodeId);
            if (node != null) {
                snapshot();
                update(node, "parent", value);
            }
        }, rowWidth - 8, false);
        parentRow = panelState.row("Parent", parentDropdown, rowWidth, advancementPanelDescription("Parent"));
        addInspectorWidget(container, parentRow);
        logicPanelWidgets.add(parentRow);

        titleInput = panelState.input("Title", "", value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject display = displayForNode(inspectorEditNodeId);
            if (display != null) {
                snapshot();
                update(display, "title", value);
            }
        });
        titleInput.setWidth(rowWidth - 8);
        titleRow = panelState.row("Title", titleInput, rowWidth, advancementPanelDescription("Title"));
        addInspectorWidget(container, titleRow);

        descriptionInput = panelState.input("Description", "", value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject display = displayForNode(inspectorEditNodeId);
            if (display != null) {
                snapshot();
                update(display, "description", value);
            }
        });
        descriptionInput.setWidth(rowWidth - 8);
        descriptionRow = panelState.row("Description", descriptionInput, rowWidth, advancementPanelDescription("Description"));
        addInspectorWidget(container, descriptionRow);

        iconButton = searchableRecipeItemButton(() -> {
            JsonObject display = displayForNode(inspectorEditNodeId);
            return display != null ? text(display, "icon") : "minecraft:stone";
        }, rowWidth - 8, value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject display = displayForNode(inspectorEditNodeId);
            if (display != null && isRealOption(value)) {
                snapshot();
                update(display, "icon", value);
            }
        });
        iconRow = panelState.row("Icon", iconButton, rowWidth, advancementPanelDescription("Icon"));
        addInspectorWidget(container, iconRow);

        frameDropdown = structuralDropdown(List.of("task", "goal", "challenge"), "task", value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject display = displayForNode(inspectorEditNodeId);
            if (display != null) {
                snapshot();
                update(display, "frame", value);
            }
        }, rowWidth - 8, false);
        frameRow = panelState.row("Frame", frameDropdown, rowWidth, advancementPanelDescription("Frame"));
        addInspectorWidget(container, frameRow);

        showToastToggle = toggleWidget(true, value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject display = displayForNode(inspectorEditNodeId);
            if (display != null) {
                snapshot();
                update(display, "showToast", value);
            }
        });
        showToastRow = panelState.row("Show Toast", showToastToggle, rowWidth, advancementPanelDescription("Show Toast"));
        addInspectorWidget(container, showToastRow);

        announceChatToggle = toggleWidget(false, value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject display = displayForNode(inspectorEditNodeId);
            if (display != null) {
                snapshot();
                update(display, "announceToChat", value);
            }
        });
        announceChatRow = panelState.row("Announce Chat", announceChatToggle, rowWidth, advancementPanelDescription("Announce Chat"));
        addInspectorWidget(container, announceChatRow);

        hiddenToggle = toggleWidget(false, value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject display = displayForNode(inspectorEditNodeId);
            if (display != null) {
                snapshot();
                update(display, "hidden", value);
            }
        });
        hiddenRow = panelState.row("Hidden", hiddenToggle, rowWidth, advancementPanelDescription("Hidden"));
        addInspectorWidget(container, hiddenRow);

        completionSourceDropdown = structuralDropdown(List.of("Manual", "Flow", "Function", "Command", "Event"), "Manual", value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject node = nodeById(inspectorEditNodeId);
            if (node != null) {
                snapshot();
                updateCompletionSource(node, value);
            }
        }, rowWidth - 8, true);
        completionSourceRow = panelState.row("Completion Source", completionSourceDropdown, rowWidth, advancementPanelDescription("Completion Source"));

        rewardTypeDropdown = structuralDropdown(List.of("None", "Experience", "Loot", "Recipe"), "None", value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject node = nodeById(inspectorEditNodeId);
            if (node != null) {
                snapshot();
                updateRewardType(node, value);
            }
        }, rowWidth - 8, true);
        rewardTypeRow = panelState.row("Reward", rewardTypeDropdown, rowWidth, advancementPanelDescription("Reward"));
        addInspectorWidget(container, rewardTypeRow);
        logicPanelWidgets.add(rewardTypeRow);

        onCompleteTypeDropdown = structuralDropdown(List.of("None", "Run Flow", "Run Function", "Run Command"), "None", value -> {
            if (syncingInspector) {
                return;
            }
            JsonObject node = nodeById(inspectorEditNodeId);
            if (node != null) {
                snapshot();
                updateOnCompleteType(node, value);
            }
        }, rowWidth - 8, true);
        onCompleteTypeRow = panelState.row("On Complete", onCompleteTypeDropdown, rowWidth, advancementPanelDescription("On Complete"));
    }

    private void syncInspectorValues(int rowWidth, boolean nodeChanged) {
        JsonObject node = nodeById(inspectorEditNodeId);
        if (node == null) {
            return;
        }
        JsonObject display = object(node, "display");
        if (enabledToggle != null) {
            enabledToggle.setValue(bool(node, "enabled", true));
        }
        if (treeNameInput != null && (nodeChanged || !treeNameInput.isFocused())) {
            treeNameInput.setText(text(tree, "displayName"));
        }
        if (nodeIdInput != null && (nodeChanged || !nodeIdInput.isFocused())) {
            nodeIdInput.setText(inspectorEditNodeId);
        }
        if (titleInput != null && (nodeChanged || !titleInput.isFocused())) {
            titleInput.setText(text(display, "title"));
        }
        if (descriptionInput != null && (nodeChanged || !descriptionInput.isFocused())) {
            descriptionInput.setText(text(display, "description"));
        }
        if (iconButton != null) {
            String value = text(display, "icon");
            if (!Objects.equals(iconButtonValue, value)) {
                iconButton.setMessage(recipeItemButtonLabel(value));
                iconButton.setMinecraftItem(ItemIconPreview.project(serverId, value).toRenderItem());
                iconButtonValue = value;
            }
        }
        if (frameDropdown != null) {
            frameDropdown.setSelectedItem(frameValue(display));
        }
        if (showToastToggle != null) {
            showToastToggle.setValue(bool(display, "showToast", true));
        }
        if (announceChatToggle != null) {
            announceChatToggle.setValue(bool(display, "announceToChat", false));
        }
        if (hiddenToggle != null) {
            hiddenToggle.setValue(bool(display, "hidden", false));
        }
        JsonObject root = nodes().has("root") ? nodes().getAsJsonObject("root") : null;
        if (backgroundButton != null && root != null) {
            JsonObject rootDisplay = object(root, "display");
            backgroundButton.setMessage(resolveSelectedOption(normalizedOptions(backgroundOptions(), backgroundMaterial(rootDisplay)), backgroundMaterial(rootDisplay)));
        }
        if (!"root".equals(selectedNode)) {
            refreshStructuralDropdown(parentDropdown, parentChoices(), parentValue(node));
            refreshStructuralDropdown(completionSourceDropdown, List.of("Manual", "Flow", "Function", "Command", "Event"), completionSource(node));
            refreshStructuralDropdown(rewardTypeDropdown, List.of("None", "Experience", "Loot", "Recipe"), rewardType(node));
            refreshStructuralDropdown(onCompleteTypeDropdown, List.of("None", "Run Flow", "Run Function", "Run Command"), onCompleteType(node));
        }
        int fieldWidth = rowWidth - 8;
        if (titleInput != null) {
            titleInput.setWidth(fieldWidth);
        }
        if (descriptionInput != null) {
            descriptionInput.setWidth(fieldWidth);
        }
        if (treeNameInput != null) {
            treeNameInput.setWidth(fieldWidth);
        }
        if (nodeIdInput != null) {
            nodeIdInput.setWidth(fieldWidth);
        }
        if (backgroundButton != null) {
            backgroundButton.setSize(fieldWidth, ReSyncStudioPanelState.FIELD_HEIGHT);
        }
        if (iconButton != null) {
            iconButton.setSize(fieldWidth, ReSyncStudioPanelState.FIELD_HEIGHT);
        }
    }

    private void updateLogicSectionVisibility() {
        if (inspector != null) {
            syncInspectorRowStructure(inspector.container());
        }
    }

    private void updateRootMetaVisibility() {
        if (inspector != null) {
            syncInspectorRowStructure(inspector.container());
        }
    }

    private void syncInspectorRowStructure(Container container) {
        boolean showLogic = nodeById(inspectorEditNodeId) != null && !"root".equals(inspectorEditNodeId);
        boolean showRootMeta = "root".equals(inspectorEditNodeId);
        for (AnimatedWidget widget : inspectorPanelWidgets) {
            boolean shouldShow = shouldShowInspectorRow(widget, showLogic, showRootMeta);
            boolean mounted = container.getWidgets().contains(widget);
            widget.setVisible(shouldShow);
            if (shouldShow && !mounted) {
                mountInspectorWidget(container, widget);
            } else if (!shouldShow && mounted) {
                container.removeWidget(widget);
            }
        }
    }

    private boolean shouldShowInspectorRow(AnimatedWidget widget, boolean showLogic, boolean showRootMeta) {
        if (widget == parentRow || widget == completionSourceRow || widget == onCompleteTypeRow) {
            return false;
        }
        if (rootMetaPanelWidgets.contains(widget)) {
            return showRootMeta;
        }
        if (logicPanelWidgets.contains(widget)) {
            return showLogic;
        }
        return true;
    }

    private void mountInspectorWidget(Container container, AnimatedWidget widget) {
        List<AnimatedWidget> mounted = container.getWidgets();
        int desiredIndex = inspectorPanelWidgets.indexOf(widget);
        int insertIndex = mounted.size();
        for (int i = desiredIndex + 1; i < inspectorPanelWidgets.size(); i++) {
            int nextIndex = mounted.indexOf(inspectorPanelWidgets.get(i));
            if (nextIndex >= 0) {
                insertIndex = nextIndex;
                break;
            }
        }
        if (insertIndex == mounted.size()) {
            for (int i = desiredIndex - 1; i >= 0; i--) {
                int previousIndex = mounted.indexOf(inspectorPanelWidgets.get(i));
                if (previousIndex >= 0) {
                    insertIndex = previousIndex + 1;
                    break;
                }
            }
        }
        ReSyncStudioPanelState.disableEntrance(widget);
        if (inspectorPanel != null) {
            inspectorPanel.mountWidget(widget, insertIndex);
        } else {
            container.addWidget(widget, insertIndex);
        }
    }

    private String advancementPanelDescription(String label) {
        return switch (label) {
            case "Asset Name" -> "Remotely asset name.\nUsed by project views.\nDoes not appear in Minecraft advancements.";
            case "Background" -> "Background of the advancement screen.\nJust a cool cosmetic.";
            case "Enabled" -> "Export state for this node.\nOn: included in generated advancement data.\nOff: kept in the designer only.";
            case "Node ID" -> "Minecraft advancement ID used in grant and revoke commands.\nUse lowercase letters, numbers, dots, dashes, or underscores.";
            case "Parent" -> "Parent advancement link.\nControls tree placement and when the child becomes visible in Minecraft.";
            case "Title" -> "Advancement display title.\nShown in the advancement screen, tooltip, and completion toast.";
            case "Description" -> "Advancement display description.\nShown below the title in the tooltip.\nDescribe the exact player objective.";
            case "Icon" -> "Item icon for this advancement.";
            case "Frame" -> "Visual frame style.\nTask: normal advancement.\nGoal: milestone frame.\nChallenge: challenge frame with stronger completion styling.";
            case "Show Toast" -> "Completion toast flag.\nOn: show the top-right Minecraft toast.\nOff: complete silently unless another action sends feedback.";
            case "Announce Chat" -> "Chat announcement flag.\nOn: announce completion to players.\nOff: keep completion out of chat.";
            case "Hidden" -> "Hidden advancement flag.\nHidden nodes stay invisible until completed.\nThey can still be granted normally.";
            case "Completion Source" -> "Completion driver.\nManual: external grant only.\nFlow: selected flow grants or validates it.\nCommand: command hook grants it.\nEvent: event criterion plus optional predicate.";
            case "Reward" -> "Vanilla reward type.";
            case "On Complete" -> "Extra ReSync action after completion.\nSeparate from vanilla advancement rewards.";
            case "Completion Flow" -> "Flow used for completion.\nSelect a flow that runs in the expected player/event context.";
            case "Completion Function" -> "Function used for completion.\nReturns true when the advancement should complete.";
            case "Completion Command" -> "Command hook for completion.";
            case "Event" -> "Criterion trigger event.\nThe advancement listens for this event before optional predicate checks.";
            case "Target" -> "Optional event target.\nUse it for practical goals like an item, block, entity, recipe, biome, world, effect, or permission.";
            case "Predicate Flow" -> "Optional event filter flow.\nReturn success only when the event data matches the requirement.";
            case "Predicate Function" -> "Optional event filter function.\nUse declared inputs and a boolean output.";
            case "XP" -> "Vanilla experience reward.\nNumber of XP points granted on completion.";
            case "Loot Tables" -> "Vanilla loot table rewards.\nComma-separated ids.\nFormat: namespace:path/to/table.";
            case "Recipes" -> "Vanilla recipe unlock rewards.\nComma-separated recipe ids.\nFormat: namespace:path/to/recipe.";
            case "Run Flow" -> "Flow executed after completion.\nTypical targets: custom rewards, messages, or follow-up logic.";
            case "Run Function" -> "Function executed after completion.\nUse it for reusable rewards and messages.";
            case "Run Command" -> "Commands executed after completion.\nMultiple commands: one command per line.";
            default -> "";
        };
    }

    private String dynamicStructureKey() {
        JsonObject node = currentNode();
        if (node == null || "root".equals(selectedNode)) {
            return "root";
        }
        return rewardType(node);
    }

    private void applyDynamicStructure() {
        if (inspector == null) {
            return;
        }
        Container container = inspector.container();
        float scrollOffset = container.getScrollOffset();
        int rowWidth = inspectorPanel != null ? inspectorPanel.rowWidth() : panelState.rowWidth(inspector);
        rebuildDynamicSection(container, rowWidth);
        lastDynamicStructure = dynamicStructureKey();
        syncDynamicFieldValues();
        container.setTargetScrollOffset(scrollOffset);
    }

    private void refreshDynamicSection() {
        if (inspector == null) {
            return;
        }
        Container container = inspector.container();
        float scrollOffset = container.getScrollOffset();
        syncDynamicFieldValues();
        container.setTargetScrollOffset(scrollOffset);
    }

    private void rebuildDynamicSection(Container container, int rowWidth) {
        List<AnimatedWidget> previousDynamicWidgets = prepareDynamicPanelWidgets();
        clearDynamicFieldRefs();
        JsonObject node = currentNode();
        if (node == null || "root".equals(selectedNode)) {
            mountDynamicPanelWidgets(container, previousDynamicWidgets);
            return;
        }
        collectingDynamicPanelMounts = true;
        try {
            String source = completionSource(node);
            dynamicCompletionBinding = insertCompletionBinding(container, nodeIdRow, rowWidth);
            if ("Event".equals(source)) {
                dynamicPredicateBinding = insertPredicateBinding(container, dynamicCompletionRow, rowWidth);
            }
            String reward = rewardType(node);
            switch (reward) {
                case "Experience" ->
                        dynamicXpInput = insertTextRow(container, rewardTypeRow, "XP", rewardValue(node, "experience"), rowWidth, value -> {
                            JsonObject current = currentNode();
                            if (current != null) {
                                updateRewardNumber(current, "experience", value);
                            }
                        });
                case "Loot" ->
                        dynamicLootInput = insertTextRow(container, rewardTypeRow, "Loot Tables", rewardArray(node, "loot"), rowWidth, value -> {
                            JsonObject current = currentNode();
                            if (current != null) {
                                updateRewardArray(current, "loot", value);
                            }
                        });
                case "Recipe" ->
                        dynamicRecipesInput = insertTextRow(container, rewardTypeRow, "Recipes", rewardArray(node, "recipes"), rowWidth, value -> {
                            JsonObject current = currentNode();
                            if (current != null) {
                                updateRewardArray(current, "recipes", value);
                            }
                        });
            }
            dynamicRunBinding = insertRunBinding(container, rewardTypeRow, rowWidth);
        } finally {
            collectingDynamicPanelMounts = false;
        }
        mountDynamicPanelWidgets(container, previousDynamicWidgets);
    }

    private void syncDynamicFieldValues() {
        JsonObject node = currentNode();
        if (node == null || "root".equals(selectedNode)) {
            return;
        }
        if (dynamicCompletionBinding != null) {
            dynamicCompletionBinding.refresh();
        }
        if (dynamicPredicateBinding != null) {
            dynamicPredicateBinding.refresh();
        }
        if (dynamicXpInput != null && !dynamicXpInput.isFocused()) {
            dynamicXpInput.setText(rewardValue(node, "experience"));
        }
        if (dynamicLootInput != null && !dynamicLootInput.isFocused()) {
            dynamicLootInput.setText(rewardArray(node, "loot"));
        }
        if (dynamicRecipesInput != null && !dynamicRecipesInput.isFocused()) {
            dynamicRecipesInput.setText(rewardArray(node, "recipes"));
        }
        if (dynamicRunBinding != null) {
            dynamicRunBinding.refresh();
        }
    }

    private void clearDynamicFieldRefs() {
        dynamicXpInput = null;
        dynamicLootInput = null;
        dynamicRecipesInput = null;
        dynamicCompletionBinding = null;
        dynamicCompletionRow = null;
        dynamicPredicateBinding = null;
        dynamicPredicateRow = null;
        dynamicRunBinding = null;
        dynamicRunRow = null;
    }

    private CompactBindingWidget insertCompletionBinding(Container container, AnimatedWidget anchor, int width) {
        CompactBindingWidget widget = new CompactBindingWidget.Builder(
            this,
            List.of("Manual", "Flow", "Function", "Command", "Event"),
            () -> completionSource(currentNode()),
            mode -> {
                JsonObject current = currentNode();
                if (current != null) {
                    snapshot();
                    updateCompletionSource(current, mode);
                    syncCompletionDependentRows();
                    refreshDynamicSection();
                }
            },
            () -> {
                JsonObject current = currentNode();
                String mode = current != null ? completionSource(current) : "Manual";
                return switch (mode) {
                    case "Function" -> functionOptions();
                    case "Flow" -> flowOptions();
                    case "Event" -> eventTriggerOptions();
                    default -> List.of("none");
                };
            },
            () -> {
                JsonObject current = currentNode();
                String mode = current != null ? completionSource(current) : "Manual";
                if ("Function".equals(mode)) {
                    return questCompletionPredicateFunction(current);
                }
                if ("Flow".equals(mode)) {
                    return questCompletionValue(current, "flowId");
                }
                if ("Command".equals(mode)) {
                    return "Command";
                }
                if ("Event".equals(mode)) {
                    return firstCriterionTrigger(current);
                }
                return "";
            },
            value -> {
                JsonObject current = currentNode();
                if (current == null) {
                    return;
                }
                String mode = completionSource(current);
                snapshot();
                if ("Function".equals(mode)) {
                    updateQuestCompletionPredicateFunction(current, value);
                    refreshDynamicSection();
                } else if ("Flow".equals(mode)) {
                    updateQuestCompletionString(current, "flowId", value);
                    refreshDynamicSection();
                } else if ("Event".equals(mode)) {
                    updateFirstCriterionTrigger(current, value);
                    refreshDynamicSection();
                }
            },
            this::completionBindingInputs,
            () -> openCompletionBinding()
        )
            .createAction("Create New", () -> {
                JsonObject current = currentNode();
                String mode = current != null ? completionSource(current) : "Manual";
                return "Function".equals(mode) || "Flow".equals(mode);
            }, this::createCompletionBindingTarget)
            .animationKey("advancement.completion")
            .size(width, 18)
            .entranceAnimation(false)
            .build();
        dynamicCompletionRow = panelState.row("Completion", widget, width, advancementPanelDescription("Completion Source"));
        insertDynamicAfter(container, anchor, dynamicCompletionRow);
        return widget;
    }

    private void syncCompletionDependentRows() {
        if (inspectorPanel == null || inspector == null) {
            return;
        }
        Container container = inspector.container();
        boolean needsPredicate = "Event".equals(completionSource(currentNode()));
        if (needsPredicate && dynamicPredicateBinding == null) {
            dynamicPredicateBinding = insertPredicateBinding(container, dynamicCompletionRow, inspectorPanel.rowWidth());
        } else if (!needsPredicate && dynamicPredicateRow != null) {
            inspectorPanel.unmountWidget(dynamicPredicateRow);
            dynamicPanelWidgets.remove(dynamicPredicateRow);
            dynamicPredicateBinding = null;
            dynamicPredicateRow = null;
        }
        lastDynamicStructure = dynamicStructureKey();
    }

    private CompactBindingWidget insertPredicateBinding(Container container, AnimatedWidget anchor, int width) {
        CompactBindingWidget widget = new CompactBindingWidget.Builder(
            this,
            CompactBindingSupport.PREDICATE_MODES,
            () -> predicateMode(currentNode()),
            mode -> {
                JsonObject current = currentNode();
                if (current == null) {
                    return;
                }
                snapshot();
                if ("None".equals(mode)) {
                    updateFirstCriterionString(current, "predicateFlowId", "");
                    updateFirstCriterionPredicateFunction(current, "");
                } else if ("Function".equals(mode)) {
                    updateFirstCriterionString(current, "predicateFlowId", "");
                    JsonObject predicate = new JsonObject();
                    predicate.addProperty("type", "functionRef");
                    predicate.addProperty("functionId", "");
                    firstCriterion(current).add("predicate", predicate);
                }
                refreshDynamicSection();
            },
            () -> "Function".equals(predicateMode(currentNode())) ? functionOptions() : List.of("none"),
            () -> {
                JsonObject current = currentNode();
                if (current == null) {
                    return "";
                }
                return "Function".equals(predicateMode(current)) ? firstCriterionPredicateFunction(current) : "";
            },
            value -> {
                JsonObject current = currentNode();
                if (current == null) {
                    return;
                }
                snapshot();
                if ("Function".equals(predicateMode(current))) {
                    updateFirstCriterionPredicateFunction(current, value);
                    refreshDynamicSection();
                }
            },
            () -> functionBindingInputs(currentCriterionPredicateCall(), CompactBindingSupport.playerPredicateShape()),
            () -> openPredicateBinding()
        )
            .createAction("Create New", () -> "Function".equals(predicateMode(currentNode())), this::createPredicateBindingTarget)
            .animationKey("advancement.predicate")
            .size(width, 18)
            .entranceAnimation(false)
            .build();
        dynamicPredicateRow = panelState.row("Predicate", widget, width, advancementPanelDescription("Predicate Function"));
        insertDynamicAfter(container, anchor, dynamicPredicateRow);
        return widget;
    }

    private CompactBindingWidget insertRunBinding(Container container, AnimatedWidget anchor, int width) {
        CompactBindingWidget widget = new CompactBindingWidget.Builder(
            this,
            List.of("None", "Run Flow", "Run Function", "Run Command"),
            () -> onCompleteType(currentNode()),
            mode -> {
                JsonObject current = currentNode();
                if (current != null) {
                    snapshot();
                    updateOnCompleteType(current, mode);
                    refreshDynamicSection();
                }
            },
            () -> {
                String mode = onCompleteType(currentNode());
                return "Run Function".equals(mode) ? functionOptions() : "Run Flow".equals(mode) ? flowOptions() : List.of("none");
            },
            () -> {
                JsonObject current = currentNode();
                String mode = current != null ? onCompleteType(current) : "None";
                return switch (mode) {
                    case "Run Function" -> completionFunction(current);
                    case "Run Flow" -> completionValue(current, "flowId");
                    case "Run Command" -> "Command";
                    default -> "";
                };
            },
            value -> {
                JsonObject current = currentNode();
                if (current == null) {
                    return;
                }
                String mode = onCompleteType(current);
                snapshot();
                if ("Run Function".equals(mode)) {
                    updateCompletionFunction(current, value);
                    refreshDynamicSection();
                } else if ("Run Flow".equals(mode)) {
                    updateCompletionString(current, "flowId", value);
                    refreshDynamicSection();
                }
            },
            this::runBindingInputs, this::openRunBinding
        )
            .createAction("Create New", () -> "Run Function".equals(onCompleteType(currentNode())) || "Run Flow".equals(onCompleteType(currentNode())), this::createRunBindingTarget)
            .animationKey("advancement.run")
            .size(width, 18)
            .entranceAnimation(false)
            .build();
        dynamicRunRow = panelState.row("On Complete", widget, width, advancementPanelDescription("On Complete"));
        insertDynamicAfter(container, anchor, dynamicRunRow);
        return widget;
    }

    private String predicateMode(JsonObject node) {
        if (node == null) {
            return "None";
        }
        JsonObject criterion = firstCriterion(node);
        if (optionalObject(criterion, "predicate") != null) {
            return "Function";
        }
        return "None";
    }

    private List<CompactBindingWidget.BindingInput> completionBindingInputs() {
        JsonObject node = currentNode();
        if (node == null) {
            return List.of();
        }
        if ("Command".equals(completionSource(node))) {
            return List.of(new CompactBindingWidget.BindingInput(
                "command",
                "Command",
                questCompletionValue(node, "command"),
                FlowDataType.STRING.getColor(),
                null,
                value -> {
                    JsonObject current = currentNode();
                    if (current != null) {
                        updateQuestCompletionString(current, "command", value);
                    }
                },
                CompactBindingWidget.InputKind.COMMAND
            ));
        }
        if ("Event".equals(completionSource(node))) {
            return eventBindingInputs(node);
        }
        return functionBindingInputs(optionalObject(optionalObject(node, "questCompletion"), "predicate"), CompactBindingSupport.playerPredicateShape());
    }

    private List<CompactBindingWidget.BindingInput> eventBindingInputs(JsonObject node) {
        String trigger = firstCriterionTrigger(node);
        String key = conditionKey(trigger);
        if (key.isBlank()) {
            clearFirstCondition(node);
            return List.of();
        }
        return List.of(new CompactBindingWidget.BindingInput(
            "target",
            eventTargetLabel(key),
            firstConditionValue(node),
            eventTargetColor(key),
            () -> eventTargetOptions(currentTrigger()),
            value -> {
                JsonObject current = currentNode();
                if (current != null) {
                    updateFirstCondition(current, value);
                }
            },
            CompactBindingWidget.InputKind.TEXT
        ));
    }

    private String eventTargetLabel(String key) {
        return switch (key) {
            case "item" -> "Item";
            case "block" -> "Block";
            case "entity" -> "Entity";
            case "recipe" -> "Recipe";
            case "biome" -> "Biome";
            case "dimension" -> "World";
            case "changedEffect" -> "Effect";
            case "permission" -> "Permission";
            default -> "Target";
        };
    }

    private int eventTargetColor(String key) {
        return switch (key) {
            case "item" -> FlowDataType.ITEM.getColor();
            case "block", "dimension" -> FlowDataType.MATERIAL.getColor();
            case "entity" -> FlowDataType.ENTITY_TYPE.getColor();
            case "permission", "recipe", "biome", "changedEffect" -> FlowDataType.STRING.getColor();
            default -> FlowDataType.ANY.getColor();
        };
    }

    private List<String> eventTargetOptions(String trigger) {
        if ("item".equals(conditionKey(trigger))) {
            return recipeItemOptions();
        }
        return conditionOptions(trigger);
    }

    private List<CompactBindingWidget.BindingInput> runBindingInputs() {
        JsonObject node = currentNode();
        if (node == null) {
            return List.of();
        }
        if ("Run Command".equals(onCompleteType(node))) {
            return List.of(new CompactBindingWidget.BindingInput(
                "command",
                "Command",
                completionArray(node, "commands"),
                FlowDataType.STRING.getColor(),
                null,
                value -> {
                    JsonObject current = currentNode();
                    if (current != null) {
                        updateCompletionArray(current, "commands", value);
                    }
                },
                CompactBindingWidget.InputKind.COMMAND
            ));
        }
        return functionBindingInputs(optionalObject(optionalObject(node, "onComplete"), "action"), CompactBindingSupport.playerActionShape());
    }

    private List<CompactBindingWidget.BindingInput> functionBindingInputs(JsonObject call, CompactBindingSupport.FunctionShape shape) {
        FlowGraph function = selectedFunction(text(call, "functionId"), shape);
        if (function == null || function.getFunctionInputs() == null || function.getFunctionInputs().isEmpty()) {
            return List.of();
        }
        List<CompactBindingWidget.BindingInput> inputs = new ArrayList<>();
        for (FlowGraph.FunctionParameter input : function.getFunctionInputs()) {
            if (input == null || input.getName() == null || input.getName().isBlank()) {
                continue;
            }
            String name = input.getName();
            inputs.add(new CompactBindingWidget.BindingInput(
                name,
                functionInputLabel(name),
                functionInputValue(call, input),
                input.getType() != null ? input.getType().getColor() : FlowDataType.ANY.getColor(),
                () -> functionInputOptions(input),
                value -> {
                    updateFunctionInput(call, input, value);
                },
                input.getType() != null && FlowDataType.BOOLEAN.isAssignableFrom(input.getType()) ? CompactBindingWidget.InputKind.BOOLEAN : CompactBindingWidget.InputKind.TEXT,
                () -> CompactBindingSupport.functionInputChoices(serverId, input, functionInputOptions(input))
            ));
        }
        return inputs;
    }

    private void openCompletionBinding() {
        JsonObject node = currentNode();
        if (node == null) {
            return;
        }
        String mode = completionSource(node);
        if ("Function".equals(mode)) {
            snapshot();
            String functionId = ensureOwnedFunction(questCompletionPredicateFunction(node), "completion");
            if (functionId.isBlank()) {
                return;
            }
            updateQuestCompletionPredicateFunction(node, functionId);
            openGraphResource(functionId, ReSyncResourceType.FUNCTION);
        } else if ("Flow".equals(mode)) {
            openGraphResource(questCompletionValue(node, "flowId"), ReSyncResourceType.FLOW);
        }
    }

    private void openPredicateBinding() {
        JsonObject node = currentNode();
        if (node == null) {
            return;
        }
        String mode = predicateMode(node);
        if ("Function".equals(mode)) {
            snapshot();
            String functionId = ensureOwnedFunction(firstCriterionPredicateFunction(node), "predicate");
            if (functionId.isBlank()) {
                return;
            }
            updateFirstCriterionPredicateFunction(node, functionId);
            openGraphResource(functionId, ReSyncResourceType.FUNCTION);
        }
    }

    private void openRunBinding() {
        JsonObject node = currentNode();
        if (node == null) {
            return;
        }
        String mode = onCompleteType(node);
        if ("Run Function".equals(mode)) {
            snapshot();
            String functionId = ensureOwnedFunction(completionFunction(node), "complete");
            if (functionId.isBlank()) {
                return;
            }
            updateCompletionFunction(node, functionId);
            openGraphResource(functionId, ReSyncResourceType.FUNCTION);
        } else if ("Run Flow".equals(mode)) {
            openGraphResource(completionValue(node, "flowId"), ReSyncResourceType.FLOW);
        }
    }

    private void createCompletionBindingTarget() {
        JsonObject node = currentNode();
        if (node == null) {
            return;
        }
        String mode = completionSource(node);
        if ("Function".equals(mode)) {
            showCreateBindingFlow(true, id -> {
                JsonObject current = currentNode();
                if (current != null) {
                    snapshot();
                    normalizeBindingFunction(id, CompactBindingSupport.playerPredicateShape());
                    updateQuestCompletionPredicateFunction(current, id);
                    refreshDynamicSection();
                    openGraphResource(id, ReSyncResourceType.FUNCTION);
                }
            });
        } else if ("Flow".equals(mode)) {
            showCreateBindingFlow(false, id -> {
                JsonObject current = currentNode();
                if (current != null) {
                    snapshot();
                    updateQuestCompletionString(current, "flowId", id);
                    refreshDynamicSection();
                    openGraphResource(id, ReSyncResourceType.FLOW);
                }
            });
        }
    }

    private void createPredicateBindingTarget() {
        JsonObject node = currentNode();
        if (node == null) {
            return;
        }
        String mode = predicateMode(node);
        if ("Function".equals(mode)) {
            showCreateBindingFlow(true, id -> {
                JsonObject current = currentNode();
                if (current != null) {
                    snapshot();
                    normalizeBindingFunction(id, CompactBindingSupport.playerPredicateShape());
                    updateFirstCriterionPredicateFunction(current, id);
                    refreshDynamicSection();
                    openGraphResource(id, ReSyncResourceType.FUNCTION);
                }
            });
        }
    }

    private void createRunBindingTarget() {
        JsonObject node = currentNode();
        if (node == null) {
            return;
        }
        String mode = onCompleteType(node);
        if ("Run Function".equals(mode)) {
            showCreateBindingFlow(true, id -> {
                JsonObject current = currentNode();
                if (current != null) {
                    snapshot();
                    normalizeBindingFunction(id, CompactBindingSupport.playerActionShape());
                    updateCompletionFunction(current, id);
                    refreshDynamicSection();
                    openGraphResource(id, ReSyncResourceType.FUNCTION);
                }
            });
        } else if ("Run Flow".equals(mode)) {
            showCreateBindingFlow(false, id -> {
                JsonObject current = currentNode();
                if (current != null) {
                    snapshot();
                    updateCompletionString(current, "flowId", id);
                    refreshDynamicSection();
                    openGraphResource(id, ReSyncResourceType.FLOW);
                }
            });
        }
    }

    private void showCreateBindingFlow(boolean function, Consumer<String> onCreated) {
        ReSyncResourceCreator.showCreatePopup(this, serverId, function ? ReSyncResourceDragPayload.FUNCTION : ReSyncResourceDragPayload.FLOW, "", null, result -> {
            if (onCreated != null) {
                onCreated.accept(result.id());
            }
        });
    }

    private String ensureOwnedFunction(String currentId, String purpose) {
        if (currentId != null && !currentId.isBlank() && !"none".equalsIgnoreCase(currentId) && !"No Function".equals(currentId)) {
            return currentId;
        }
        return "";
    }

    private void normalizeBindingFunction(String functionId, CompactBindingSupport.FunctionShape shape) {
        CompactBindingSupport.initializeFunction(serverId, functionId, shape);
    }

    private String ownedFunctionId(String purpose) {
        String treeId = text(tree, "id");
        String raw = ReSyncNaming.camelCase("advancement " + treeId + " " + selectedNode + " " + purpose);
        return raw.isBlank() ? "advancementFunction" : raw;
    }

    private void openGraphResource(String flowId, ReSyncResourceType type) {
        if (flowId == null || flowId.isBlank() || "none".equalsIgnoreCase(flowId) || "No Flow".equals(flowId) || "No Function".equals(flowId)) {
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager != null && serverId != null) {
            manager.openGraphEditor(serverId, null, type, flowId);
        }
    }

    private TitledRowWidget insertSearchableTitledRow(Container container, AnimatedWidget anchor, String label, Supplier<List<String>> choicesSupplier, String catalogSource, Supplier<String> selectedSupplier, int width, Consumer<AnimatedButton> buttonConsumer, Consumer<String> onChange) {
        AnimatedButton button = searchableButton(choicesSupplier, selectedSupplier, catalogSource, width - 8, onChange);
        TitledRowWidget row = panelState.row(label, button, width, advancementPanelDescription(label));
        insertDynamicAfter(container, anchor, row);
        if (buttonConsumer != null) {
            buttonConsumer.accept(button);
        }
        return row;
    }

    private AnimatedWidget insertFunctionInputRows(Container container, AnimatedWidget anchor, Supplier<JsonObject> callSupplier, int width) {
        JsonObject call = callSupplier != null ? callSupplier.get() : null;
        FlowGraph function = selectedFunction(text(call, "functionId"));
        if (function == null || function.getFunctionInputs() == null || function.getFunctionInputs().isEmpty()) {
            return anchor;
        }
        AnimatedWidget currentAnchor = anchor;
        for (FlowGraph.FunctionParameter input : function.getFunctionInputs()) {
            if (input == null || input.getName() == null || input.getName().isBlank()) {
                continue;
            }
            currentAnchor = insertFunctionInputRow(container, currentAnchor, callSupplier, input, width);
        }
        return currentAnchor;
    }

    private AnimatedWidget insertFunctionInputRow(Container container, AnimatedWidget anchor, Supplier<JsonObject> callSupplier, FlowGraph.FunctionParameter input, int width) {
        String label = functionInputLabel(input.getName());
        String optionsSource = input.getOptionsSource();
        List<String> options = functionInputOptions(input);
        if (optionsSource != null && !optionsSource.isBlank() || !options.isEmpty()) {
            TitledRowWidget row = insertSearchableTitledRow(container, anchor, label, () -> functionInputOptions(input), optionsSource, () -> functionInputValue(callSupplier.get(), input), width, null, value -> updateFunctionInput(callSupplier.get(), input, value));
            return row;
        }
        TextInputWidget inputWidget = panelState.input(label, functionInputValue(callSupplier.get(), input), value -> {
            snapshot();
            updateFunctionInput(callSupplier.get(), input, value);
        });
        inputWidget.setWidth(width - 8);
        TitledRowWidget row = panelState.row(label, inputWidget, width, advancementPanelDescription("Function Input"));
        insertDynamicAfter(container, anchor, row);
        return row;
    }

    private List<String> functionInputOptions(FlowGraph.FunctionParameter input) {
        List<String> options = new ArrayList<>(CompactBindingSupport.functionInputOptions(input, "advancement"));
        String optionsSource = input.getOptionsSource();
        if (optionsSource != null && !optionsSource.isBlank()) {
            for (String option : catalogOptions(optionsSource, List.of())) {
                if (option != null && !option.isBlank() && !options.contains(option)) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    private String functionInputValue(JsonObject call, FlowGraph.FunctionParameter input) {
        if (call == null || input == null || input.getName() == null || input.getName().isBlank()) {
            return "";
        }
        JsonObject inputs = optionalObject(call, "inputs");
        if (inputs != null && inputs.has(input.getName()) && !inputs.get(input.getName()).isJsonNull()) {
            return inputs.get(input.getName()).getAsString();
        }
        if (input.getDefaultValue() != null && !input.getDefaultValue().isBlank()) {
            return input.getDefaultValue();
        }
        return functionInputContextDefault(input);
    }

    private String functionInputContextDefault(FlowGraph.FunctionParameter input) {
        return CompactBindingSupport.functionInputContextDefault(input, "advancement");
    }

    private void updateFunctionInput(JsonObject call, FlowGraph.FunctionParameter input, String value) {
        if (call == null || input == null || input.getName() == null || input.getName().isBlank()) {
            return;
        }
        JsonObject inputs = object(call, "inputs");
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value) || "No Options".equals(value) || "Loading".equals(value)) {
            inputs.remove(input.getName());
            removeIfEmpty(call, "inputs");
            return;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            inputs.addProperty(input.getName(), Boolean.parseBoolean(trimmed));
            return;
        }
        try {
            inputs.addProperty(input.getName(), Integer.parseInt(trimmed));
        } catch (NumberFormatException ignored) {
            inputs.addProperty(input.getName(), trimmed);
        }
    }

    private String functionInputLabel(String name) {
        String cleaned = name == null ? "" : name.replace('_', ' ').trim();
        return cleaned.isBlank() ? "Input" : Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    private TextInputWidget insertTextRow(Container container, AnimatedWidget anchor, String label, String value, int width, Consumer<String> onChange) {
        TextInputWidget input = panelState.input(label, value, next -> {
            snapshot();
            onChange.accept(next);
        });
        input.setWidth(width - 8);
        TitledRowWidget row = panelState.row(label, input, width, advancementPanelDescription(label));
        insertDynamicAfter(container, anchor, row);
        return input;
    }

    private void insertDynamicAfter(Container container, AnimatedWidget anchor, AnimatedWidget widget) {
        if (anchor == null) {
            addDynamicInspectorWidget(container, widget);
            return;
        }
        ReSyncStudioPanelState.disableEntrance(widget);
        dynamicPanelWidgets.add(widget);
        if (collectingDynamicPanelMounts) {
            pendingDynamicPanelMounts.add(new DynamicPanelMount(anchor, widget));
            return;
        }
        mountDynamicWidgetAfter(container, anchor, widget);
    }

    private void mountDynamicWidgetAfter(Container container, AnimatedWidget anchor, AnimatedWidget widget) {
        List<AnimatedWidget> mounted = container.getWidgets();
        int index = mounted.indexOf(anchor);
        if (index < 0) {
            addDynamicInspectorWidget(container, widget);
            return;
        }
        int insertIndex = index + 1;
        while (insertIndex < mounted.size() && dynamicPanelWidgets.contains(mounted.get(insertIndex))) {
            insertIndex++;
        }
        container.addWidget(widget, insertIndex);
    }

    private List<AnimatedWidget> prepareDynamicPanelWidgets() {
        List<AnimatedWidget> previousWidgets = new ArrayList<>(dynamicPanelWidgets);
        dynamicPanelWidgets.clear();
        pendingDynamicPanelMounts.clear();
        panelDropdowns.removeIf(dropdown -> dropdown != frameDropdown
            && dropdown != parentDropdown
            && dropdown != completionSourceDropdown
            && dropdown != rewardTypeDropdown
            && dropdown != onCompleteTypeDropdown);
        return previousWidgets;
    }

    private void mountDynamicPanelWidgets(Container container, List<AnimatedWidget> previousDynamicWidgets) {
        List<AnimatedWidget> desired = container.getWidgets();
        desired.removeAll(previousDynamicWidgets);
        for (DynamicPanelMount mount : pendingDynamicPanelMounts) {
            int insertIndex = desired.indexOf(mount.anchor());
            if (insertIndex < 0) {
                desired.add(mount.widget());
                continue;
            }
            insertIndex++;
            while (insertIndex < desired.size() && dynamicPanelWidgets.contains(desired.get(insertIndex))) {
                insertIndex++;
            }
            desired.add(insertIndex, mount.widget());
        }
        container.replaceWidgets(desired);
        pendingDynamicPanelMounts.clear();
    }

    private void addInspectorWidget(Container container, AnimatedWidget widget) {
        ReSyncStudioPanelState.disableEntrance(widget);
        if (!inspectorPanelWidgets.contains(widget)) {
            inspectorPanelWidgets.add(widget);
        }
        container.addWidget(widget);
    }

    private void addDynamicInspectorWidget(Container container, AnimatedWidget widget) {
        ReSyncStudioPanelState.disableEntrance(widget);
        dynamicPanelWidgets.add(widget);
        if (collectingDynamicPanelMounts) {
            pendingDynamicPanelMounts.add(new DynamicPanelMount(null, widget));
            return;
        }
        if (inspectorPanel != null) {
            inspectorPanel.mountWidget(widget);
        } else {
            container.addWidget(widget);
        }
    }

    private JsonObject nodeById(String nodeId) {
        return nodeId != null && nodes().has(nodeId) ? nodes().getAsJsonObject(nodeId) : null;
    }

    private String renameNodeId(String currentId, String requestedId) {
        String nextId = sanitizeNodeId(requestedId);
        if (nextId.isBlank() || "root".equals(currentId) || currentId.equals(nextId) || nodes().has(nextId)) {
            if (nodeIdInput != null && !currentId.equals(nodeIdInput.getText())) {
                nodeIdInput.setText(currentId);
            }
            return currentId;
        }
        JsonObject source = nodes();
        JsonObject reordered = new JsonObject();
        JsonElement renamedNode = source.get(currentId);
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (entry.getKey().equals(currentId)) {
                reordered.add(nextId, renamedNode);
            } else {
                reordered.add(entry.getKey(), entry.getValue());
            }
        }
        source.keySet().clear();
        for (Map.Entry<String, JsonElement> entry : reordered.entrySet()) {
            source.add(entry.getKey(), entry.getValue());
        }
        rewriteNodeReferences(currentId, nextId);
        if (selectedNode.equals(currentId)) {
            selectedNode = nextId;
        }
        if (inspectorEditNodeId.equals(currentId)) {
            inspectorEditNodeId = nextId;
        }
        if (lastInspectorNode.equals(currentId)) {
            lastInspectorNode = nextId;
        }
        if (nodeIdInput != null && !nextId.equals(nodeIdInput.getText())) {
            nodeIdInput.setText(nextId);
        }
        refreshJson();
        return nextId;
    }

    private void rewriteNodeReferences(String oldId, String newId) {
        String treeId = text(tree, "id");
        String oldFull = treeId.isBlank() ? oldId : treeId + "/" + oldId;
        String newFull = treeId.isBlank() ? newId : treeId + "/" + newId;
        for (Map.Entry<String, JsonElement> entry : nodes().entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject node = entry.getValue().getAsJsonObject();
            String parent = text(node, "parent");
            if (oldId.equals(parent)) {
                node.addProperty("parent", newId);
            } else if (oldFull.equals(parent)) {
                node.addProperty("parent", newFull);
            }
        }
    }

    private String uniqueNodeId(String value, String currentId) {
        String base = sanitizeNodeId(value);
        if (base.isBlank()) {
            base = "new_advancement";
        }
        String candidate = base;
        int suffix = 2;
        while (nodes().has(candidate) && !candidate.equals(currentId)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String sanitizeNodeId(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        StringBuilder result = new StringBuilder();
        boolean previousUnderscore = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean allowed = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_';
            if (allowed) {
                result.append(c);
                previousUnderscore = c == '_';
            } else if (!previousUnderscore) {
                result.append('_');
                previousUnderscore = true;
            }
        }
        while (!result.isEmpty() && result.charAt(0) == '_') {
            result.deleteCharAt(0);
        }
        while (!result.isEmpty() && result.charAt(result.length() - 1) == '_') {
            result.deleteCharAt(result.length() - 1);
        }
        return result.toString();
    }

    private JsonObject displayForNode(String nodeId) {
        JsonObject node = nodeById(nodeId);
        return node != null ? object(node, "display") : null;
    }

    private JsonObject currentNode() {
        return nodeById(selectedNode);
    }

    private JsonObject currentDisplay() {
        return displayForNode(selectedNode);
    }

    private IconButton searchableRecipeItemButton(Supplier<String> selectedSupplier, int width, Consumer<String> onChange) {
        String selected = selectedSupplier.get();
        iconButtonValue = selected;
        IconButton button = new IconButton.Builder()
            .label(recipeItemButtonLabel(selected))
            .minecraftItem(ItemIconPreview.project(serverId, selected).toRenderItem())
            .richText(true)
            .size(width, ReSyncStudioPanelState.FIELD_HEIGHT)
            .entranceAnimation(false)
            .build();
        ReSyncStudioPanelState.disableEntrance(button);
        button.setOnClick(() -> {
            String current = selectedSupplier.get();
            button.setMessage(recipeItemButtonLabel(current));
            openRecipeItemSearchSelector(selectedSupplier, value -> {
                if (isRealOption(value)) {
                    button.setMessage(recipeItemButtonLabel(value));
                    button.setMinecraftItem(ItemIconPreview.project(serverId, value).toRenderItem());
                    iconButtonValue = value;
                    snapshot();
                    onChange.accept(value);
                }
            }, button.getX(), button.getY() + button.getHeight());
        });
        return button;
    }

    private String recipeItemButtonLabel(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return ItemOptionCatalog.label(serverId, value);
    }

    private List<String> recipeItemOptions() {
        return ItemOptionCatalog.mergedValues(serverId);
    }

    private AnimatedButton searchableButton(Supplier<List<String>> choicesSupplier, Supplier<String> selectedSupplier, String catalogSource, int width, Consumer<String> onChange) {
        AnimatedButton button = new AnimatedButton.Builder()
            .label("")
            .size(width, ReSyncStudioPanelState.FIELD_HEIGHT)
            .entranceAnimation(false)
            .build();
        ReSyncStudioPanelState.disableEntrance(button);
        button.setAction(() -> {
            String selected = selectedSupplier.get();
            List<String> options = normalizedOptions(choicesSupplier.get(), selected);
            button.setMessage(searchButtonLabel(options, selected, catalogSource));
            if ((catalogSource == null || catalogSource.isBlank()) && options.size() == 1 && "Loading".equals(options.getFirst())) {
                return;
            }
            Consumer<String> selection = value -> {
                if (isRealOption(value)) {
                    button.setMessage(catalogLabel(catalogSource, value));
                    snapshot();
                    onChange.accept(value);
                }
            };
            if (catalogSource != null && !catalogSource.isBlank()) {
                openCatalogSearchSelector(choicesSupplier, catalogSource, selectedSupplier, selection, button.getX(), button.getY() + button.getHeight());
            } else {
                openSearchSelector(choicesSupplier, selectedSupplier, selection, button.getX(), button.getY() + button.getHeight());
            }
        });
        refreshSearchButtonLabel(button, choicesSupplier, selectedSupplier, catalogSource);
        return button;
    }

    private void refreshSearchButtonLabel(AnimatedButton button, Supplier<List<String>> choicesSupplier, Supplier<String> selectedSupplier, String catalogSource) {
        if (button == null || choicesSupplier == null || selectedSupplier == null) {
            return;
        }
        String selected = selectedSupplier.get();
        List<String> options = normalizedOptions(choicesSupplier.get(), selected);
        button.setMessage(searchButtonLabel(options, selected, catalogSource));
    }

    private String searchButtonLabel(List<String> options, String selected, String catalogSource) {
        String resolved = resolveSelectedOption(options, selected);
        if (catalogSource != null && !catalogSource.isBlank() && !isRealOption(resolved)) {
            return isRealOption(selected) ? catalogLabel(catalogSource, selected) : "Select";
        }
        return catalogLabel(catalogSource, resolved);
    }

    private String catalogLabel(String source, String value) {
        if (source == null || source.isBlank() || value == null || value.isBlank()) {
            return value != null ? value : "";
        }
        return OptionCatalogCache.getInstance().getItems(serverId, source).stream()
            .filter(item -> item != null && value.equals(item.getValue()))
            .map(OptionCatalogItem::getLabel)
            .filter(label -> label != null && !label.isBlank())
            .findFirst()
            .orElse(value);
    }

    private DropDownWidget<String> createDynamicDropdown(List<String> options, String selected, int width, Consumer<String> onChange) {
        List<String> safeOptions = options == null || options.isEmpty() ? List.of("") : options;
        String selectedValue = selected != null && safeOptions.contains(selected) ? selected : safeOptions.getFirst();
        DropDownWidget<String> dropdown = new DropDownWidget.Builder<>(safeOptions)
            .selectedItem(selectedValue)
            .size(width - 8, ReSyncStudioPanelState.FIELD_HEIGHT)
            .maxVisibleItems(8)
            .entranceAnimation(false)
            .onSelectionChanged(value -> {
                if (syncingInspector) {
                    return;
                }
                snapshot();
                onChange.accept(value);
            })
            .build();
        panelDropdowns.add(dropdown);
        return dropdown;
    }

    private DropDownWidget<String> structuralDropdown(List<String> options, String selected, Consumer<String> onChange, int width, boolean rebuildDynamic) {
        List<String> safeOptions = options == null || options.isEmpty() ? List.of("") : options;
        String selectedValue = selected != null && safeOptions.contains(selected) ? selected : safeOptions.getFirst();
        DropDownWidget<String> dropdown = new DropDownWidget.Builder<>(safeOptions)
            .selectedItem(selectedValue)
            .size(width, ReSyncStudioPanelState.FIELD_HEIGHT)
            .maxVisibleItems(8)
            .entranceAnimation(false)
            .onSelectionChanged(value -> {
                if (syncingInspector) {
                    return;
                }
                onChange.accept(value);
                if (rebuildDynamic) {
                    applyDynamicStructure();
                }
            })
            .build();
        panelDropdowns.add(dropdown);
        return dropdown;
    }

    private void refreshStructuralDropdown(DropDownWidget<String> dropdown, List<String> options, String selected) {
        if (dropdown == null) {
            return;
        }
        List<String> safeOptions = options == null || options.isEmpty() ? List.of("") : options;
        String selectedValue = selected != null && safeOptions.contains(selected) ? selected : safeOptions.getFirst();
        dropdown.setItems(safeOptions, selectedValue);
    }

    private ToggleWidget toggleWidget(boolean value, Consumer<Boolean> onChange) {
        ToggleWidget toggle = new ToggleWidget.Builder()
            .toggled(value)
            .size(42, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(next -> {
                if (syncingInspector) {
                    return;
                }
                onChange.accept(next);
            })
            .entranceAnimation(false)
            .build();
        ReSyncStudioPanelState.disableEntrance(toggle);
        return toggle;
    }

    private List<String> normalizedOptions(List<String> choices, String selected) {
        List<String> options = new ArrayList<>();
        if (choices != null) {
            for (String choice : choices) {
                if (choice != null && !choice.isBlank() && !options.contains(choice)) {
                    options.add(choice);
                }
            }
        }
        if (options.isEmpty()) {
            options.add("No Options");
        }
        if (selected != null && !selected.isBlank() && !"Loading".equals(selected) && !options.contains(selected)) {
            options.addFirst(selected);
        }
        return options;
    }

    private String resolveSelectedOption(List<String> options, String selected) {
        if (selected != null && options.contains(selected)) {
            return selected;
        }
        return options.isEmpty() ? "" : options.getFirst();
    }

    private void openRecipeItemSearchSelector(Supplier<String> selectedSupplier, Consumer<String> onSelected, int x, int y) {
        if (selectedSupplier == null || onSelected == null || serverId == null) {
            return;
        }
        String selected = selectedSupplier.get();
        closeActiveSearchSelector();
        activeSelectorUsesRecipeCatalog = true;
        activeSelectorOptions = null;
        activeSelectorSelected = selectedSupplier;
        activeSelectorOnSelected = onSelected;
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Items")
            .asyncItems(() -> ItemOptionCatalog.refresh(serverId),
                ItemOptionCatalog.selectorSource(serverId, selectedSupplier, onSelected, false))
            .onClose(() -> closeActiveSearchSelector(selectorRef[0]));
        ItemSelectorWidget selector = builder.build();
        selectorRef[0] = selector;
        selector.setSelectedItem(ItemOptionCatalog.label(serverId, selected));
        activeSearchSelector = selector;
        int selectorX = Math.clamp(x, 8, Math.max(8, width - selector.getWidth() - 8));
        int selectorY = Math.clamp(y, 32, Math.max(32, height - selector.getHeight() - 20));
        activeSearchSelector.show(selectorX, selectorY);
    }

    private void openSearchSelector(Supplier<List<String>> optionsSupplier, Supplier<String> selectedSupplier, Consumer<String> onSelected, int x, int y) {
        if (optionsSupplier == null || selectedSupplier == null || onSelected == null) {
            return;
        }
        List<String> options = normalizedOptions(optionsSupplier.get(), selectedSupplier.get());
        if (options.isEmpty()) {
            return;
        }
        closeActiveSearchSelector();
        activeSelectorUsesRecipeCatalog = false;
        activeSelectorCatalogSource = null;
        activeSelectorOptions = optionsSupplier;
        activeSelectorSelected = selectedSupplier;
        activeSelectorOnSelected = onSelected;
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .onClose(() -> closeActiveSearchSelector(selectorRef[0]))
            .build();
        selectorRef[0] = selector;
        for (String option : options.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            if (!isRealOption(option)) {
                continue;
            }
            selector.addItem(option, () -> onSelected.accept(option));
        }
        selector.setSelectedItem(selectedSupplier.get());
        activeSearchSelector = selector;
        int selectorX = Math.clamp(x, 8, Math.max(8, width - selector.getWidth() - 8));
        int selectorY = Math.clamp(y, 32, Math.max(32, height - selector.getHeight() - 20));
        activeSearchSelector.show(selectorX, selectorY);
    }

    private void openCatalogSearchSelector(Supplier<List<String>> optionsSupplier, String catalogSource, Supplier<String> selectedSupplier,
                                           Consumer<String> onSelected, int x, int y) {
        if (optionsSupplier == null || catalogSource == null || catalogSource.isBlank() || selectedSupplier == null || onSelected == null) {
            return;
        }
        closeActiveSearchSelector();
        activeSelectorUsesRecipeCatalog = false;
        activeSelectorCatalogSource = catalogSource;
        activeSelectorOptions = optionsSupplier;
        activeSelectorSelected = selectedSupplier;
        activeSelectorOnSelected = onSelected;
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Options")
            .asyncItems(() -> requestCatalog(catalogSource, true),
                () -> catalogSelectorSnapshot(optionsSupplier, catalogSource, selectedSupplier, onSelected))
            .onClose(() -> closeActiveSearchSelector(selectorRef[0]));
        String selected = selectedSupplier.get();
        ItemSelectorWidget selector = builder.build();
        selectorRef[0] = selector;
        selector.setSelectedItem(catalogLabel(catalogSource, selected));
        activeSearchSelector = selector;
        int selectorX = Math.clamp(x, 8, Math.max(8, width - selector.getWidth() - 8));
        int selectorY = Math.clamp(y, 32, Math.max(32, height - selector.getHeight() - 20));
        activeSearchSelector.show(selectorX, selectorY);
    }

    private ItemSelectorWidget.AsyncItemSnapshot catalogSelectorSnapshot(Supplier<List<String>> optionsSupplier, String catalogSource,
        Supplier<String> selectedSupplier, Consumer<String> onSelected) {
        return OptionCatalogSelector.snapshot(serverId, catalogSource, Map.of(),
            () -> normalizedOptions(optionsSupplier.get(), selectedSupplier.get()), selectedSupplier, onSelected, "No Options");
    }

    private void closeActiveSearchSelector() {
        closeActiveSearchSelector(activeSearchSelector);
    }

    private void closeActiveSearchSelector(ItemSelectorWidget selector) {
        if (selector != null) {
            selector.onClose = null;
            selector.hide();
            remove(selector);
        }
        if (selector == activeSearchSelector) {
            activeSearchSelector = null;
            activeSelectorOptions = null;
            activeSelectorSelected = null;
            activeSelectorOnSelected = null;
            activeSelectorCatalogSource = null;
            activeSelectorUsesRecipeCatalog = false;
        }
        setFocusedWidget(null);
    }

    private void addNode() {
        String parentId = selectedNode != null && nodes().has(selectedNode) ? selectedNode : "root";
        if (treeDraft.defer(() -> addNode(parentId))) {
            return;
        }
        addNode(parentId);
    }

    private void addNode(String parentId) {
        snapshot();
        String id = nextNodeId();
        JsonObject node = new JsonObject();
        node.addProperty("enabled", true);
        node.addProperty("parent", parentId);
        JsonObject display = new JsonObject();
        display.addProperty("title", id);
        display.addProperty("description", "Server Progress");
        display.addProperty("icon", "minecraft:stone");
        display.addProperty("frame", "task");
        display.addProperty("showToast", true);
        display.addProperty("announceToChat", false);
        display.addProperty("hidden", false);
        node.add("display", display);
        JsonObject criterion = new JsonObject();
        criterion.addProperty("trigger", "impossible");
        JsonObject criteria = new JsonObject();
        criteria.add("requirement", criterion);
        node.add("criteria", criteria);
        ensureRequirements(node, "requirement");
        nodes().add(id, node);
        lastInspectorNode = "";
        lastDynamicStructure = "";
        refreshJson();
        selectNode(id);
    }

    private void deleteSelected() {
        String removedId = selectedNode;
        if (removedId == null || "root".equals(removedId)) {
            return;
        }
        if (treeDraft.defer(() -> deleteNode(removedId))) {
            return;
        }
        deleteNode(removedId);
    }

    private void deleteNode(String removedId) {
        if (!nodes().has(removedId) || "root".equals(removedId)) {
            return;
        }
        snapshot();
        nodes().remove(removedId);
        for (Map.Entry<String, JsonElement> entry : nodes().entrySet()) {
            JsonObject node = entry.getValue().getAsJsonObject();
            if (removedId.equals(text(node, "parent"))) {
                node.addProperty("parent", "root");
            }
        }
        lastInspectorNode = "";
        lastDynamicStructure = "";
        refreshJson();
        selectNode("root");
    }

    private void save() {
        commitInspectorEdits(inspectorEditNodeId);
        ensureTreeIdentity();
        FlowManager manager = FlowManager.getInstance();
        String id = text(tree, "id");
        String name = text(tree, "displayName");
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId,
            ReSyncResourceType.ADVANCEMENT_TREE, id, name.isBlank() ? id : name);
        observeSave(ticket);
        if (manager != null && serverId != null) {
            if (!treeDraft.capture(ReSyncResourceType.ADVANCEMENT_TREE.typeId(), id, ticket,
                snapshot -> manager.saveJsonResource(serverId, ReSyncResourceType.ADVANCEMENT_TREE,
                    snapshot.serialize(payload -> sanitizeTree(JsonParser.parseString(payload).getAsJsonObject())), ticket))) {
                DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
            }
        } else {
            DesignerSaveNotifications.failExact(ticket, "ReSync Offline");
        }
    }

    @Override
    public boolean requestStudioSave() {
        save();
        return true;
    }

    private void rebindTree(JsonObject previous, JsonObject replacement) {
        JsonObject currentTree = tree;
        String currentLastInspectorNode = lastInspectorNode;
        String currentLastDynamicStructure = lastDynamicStructure;
        try {
            tree = replacement;
            lastInspectorNode = "";
            lastDynamicStructure = "";
            applyInspectorSelection();
        } catch (RuntimeException | Error exception) {
            tree = currentTree;
            lastInspectorNode = currentLastInspectorNode;
            lastDynamicStructure = currentLastDynamicStructure;
            throw exception;
        }
    }

    private void failSnapshot(VersionedEditorDraft.Failure failure) {
        if (failure.stage() == VersionedEditorDraft.Stage.REBASE) {
            new Notification("Save Refresh Failed", "Save Paused", Notification.Type.ERROR);
            return;
        }
        if (failure.request() instanceof DesignerSaveNotifications.SaveTicket ticket) {
            DesignerSaveNotifications.failExact(ticket, "Save Snapshot Failed");
        }
    }

    private void observeSave(DesignerSaveNotifications.SaveTicket ticket) {
        if (ticket == null) {
            return;
        }
        ticket.whenFinished((saved, current) -> ScreenManager.getInstance().execute(() -> {
            if (!saved || !current) {
                treeDraft.discard(ticket);
                return;
            }
            FlowManager manager = FlowManager.getInstance();
            FlowManager.ResourceReadLease lease = manager != null
                ? manager.snapshotResource(serverId, ReSyncResourceType.ADVANCEMENT_TREE.typeId(), ticket.id()) : null;
            if (lease == null || !treeDraft.acknowledge(ticket, lease::materialize)) {
                treeDraft.discard(ticket);
            }
        }));
    }

    private JsonObject sanitizeTree(JsonObject detached) {
        ensureTreeIdentity(detached);
        for (Map.Entry<String, JsonElement> entry : nodes(detached).entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject node = entry.getValue().getAsJsonObject();
            JsonObject display = object(node, "display");
            if (text(display, "icon").isBlank()) {
                display.addProperty("icon", "minecraft:stone");
            }
            if (text(display, "frame").isBlank()) {
                display.addProperty("frame", "task");
            }
            if (display.has("background")) {
                display.addProperty("background", normalizeBackgroundValue(text(display, "background")));
            }
            JsonObject criteria = object(node, "criteria");
            if ("root".equals(entry.getKey()) && criteria.entrySet().size() == 1 && criteria.has("requirement") && autoImpossibleCriterion(criteria.get("requirement"))) {
                criteria.remove("requirement");
                node.add("requirements", new JsonArray());
            }
            if (criteria.isEmpty()) {
                if ("root".equals(entry.getKey())) {
                    node.add("requirements", new JsonArray());
                } else {
                    JsonObject criterion = new JsonObject();
                    criterion.addProperty("trigger", "impossible");
                    criteria.add("requirement", criterion);
                }
            }
            if (!criteria.isEmpty()) {
                String criterion = criteria.keySet().stream().findFirst().orElse("requirement");
                ensureRequirements(node, criterion);
            }
            removeEmptyArrays(node, "rewards", "loot", "recipes");
            removeEmptyArrays(node, "onComplete", "commands");
            removeIfEmpty(node, "rewards");
            removeIfEmpty(node, "onComplete");
            removeIfEmpty(node, "questCompletion");
        }
        reorderTreeNodesParentFirst(detached);
        return detached;
    }

    private void reorderTreeNodesParentFirst(JsonObject detached) {
        JsonObject source = nodes(detached);
        List<String> ordered = new ArrayList<>();
        for (String nodeId : new ArrayList<>(source.keySet())) {
            appendNodeWithParents(source, nodeId, text(detached, "id"), ordered, new HashSet<>());
        }
        JsonObject reordered = new JsonObject();
        for (String nodeId : ordered) {
            if (source.has(nodeId)) {
                reordered.add(nodeId, source.get(nodeId));
            }
        }
        source.keySet().clear();
        for (Map.Entry<String, JsonElement> entry : reordered.entrySet()) {
            source.add(entry.getKey(), entry.getValue());
        }
    }

    private void appendNodeWithParents(JsonObject nodes, String nodeId, String treeId, List<String> ordered, Set<String> visiting) {
        if (nodeId == null || nodeId.isBlank() || ordered.contains(nodeId) || !nodes.has(nodeId) || !nodes.get(nodeId).isJsonObject() || !visiting.add(nodeId)) {
            return;
        }
        String parent = localParentId(text(nodes.getAsJsonObject(nodeId), "parent"), treeId);
        if (!parent.isBlank()) {
            appendNodeWithParents(nodes, parent, treeId, ordered, visiting);
        }
        ordered.add(nodeId);
        visiting.remove(nodeId);
    }

    private boolean autoImpossibleCriterion(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return false;
        }
        JsonObject criterion = value.getAsJsonObject();
        return "impossible".equals(text(criterion, "trigger")) && !criterion.has("conditions") && !criterion.has("predicateFlowId");
    }

    private void removeEmptyArrays(JsonObject node, String objectKey, String... arrayKeys) {
        JsonObject object = optionalObject(node, objectKey);
        if (object == null) {
            return;
        }
        for (String arrayKey : arrayKeys) {
            if (object.has(arrayKey) && object.get(arrayKey).isJsonArray() && object.getAsJsonArray(arrayKey).isEmpty()) {
                object.remove(arrayKey);
            }
        }
    }

    private void snapshot() {
        treeDraft.markMutation();
        history.capture();
    }

    private void restore(String json) {
        commitInspectorEdits(inspectorEditNodeId);
        JsonObject restored = JsonParser.parseString(json).getAsJsonObject();
        tree.keySet().clear();
        for (Map.Entry<String, JsonElement> entry : restored.entrySet()) {
            tree.add(entry.getKey(), entry.getValue());
        }
        if (!nodes().has(selectedNode)) {
            selectedNode = nodes().has("root") ? "root" : nodes().keySet().iterator().next();
        }
        lastInspectorNode = "";
        lastDynamicStructure = "";
        refreshJson();
        applyInspectorSelection();
    }

    private void refreshJson() {
    }

    private void ensureTreeIdentity() {
        ensureTreeIdentity(tree);
    }

    private void ensureTreeIdentity(JsonObject target) {
        String id = text(target, "id");
        if (id.isBlank()) {
            id = "advancement";
            target.addProperty("id", id);
        }
        if (text(target, "displayName").isBlank()) {
            target.addProperty("displayName", id);
        }
    }

    private JsonObject nodes() {
        return nodes(tree);
    }

    private JsonObject nodes(JsonObject target) {
        if (!target.has("nodes") || !target.get("nodes").isJsonObject()) {
            target.add("nodes", new JsonObject());
        }
        return target.getAsJsonObject("nodes");
    }

    private List<String> parentOptions() {
        List<String> options = new ArrayList<>();
        FlowManager manager = FlowManager.getInstance();
        if (manager != null && serverId != null) {
            for (Map.Entry<String, JsonObject> entry : manager.getJsonResourcesForServer(serverId, ReSyncResourceType.ADVANCEMENT_TREE).entrySet()) {
                JsonObject resourceNodes = entry.getValue().has("nodes") && entry.getValue().get("nodes").isJsonObject() ? entry.getValue().getAsJsonObject("nodes") : new JsonObject();
                for (String nodeId : resourceNodes.keySet()) {
                    options.add(entry.getKey().equals(text(tree, "id")) ? nodeId : entry.getKey() + "/" + nodeId);
                }
            }
        }
        if (options.isEmpty()) {
            options.addAll(nodes().keySet());
        }
        options.remove(selectedNode);
        return options.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> parentChoices() {
        List<String> options = new ArrayList<>();
        options.add("root");
        options.addAll(parentOptions());
        return options.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private String parentValue(JsonObject node) {
        String parent = text(node, "parent");
        return parent.isBlank() ? "root" : parent;
    }

    private List<String> backgroundOptions() {
        return catalogOptions(BLOCK_CATALOG, List.of("stone", "dirt", "sand", "netherrack", "end_stone", "deepslate"));
    }

    private List<String> catalogOptions(String source, List<String> fallback) {
        if (serverId == null || source == null) {
            return fallback.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        List<String> values = new ArrayList<>(OptionCatalogCache.getInstance().getValues(serverId, source));
        if (!values.isEmpty()) {
            return values.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (!OptionCatalogCache.getInstance().hasCatalog(serverId, source)) {
            requestCatalog(source);
            return List.of("Loading");
        }
        return fallback.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> eventTriggerOptions() {
        return List.of(
            "bee_nest_destroyed",
            "break_block",
            "break_furniture",
            "bred_animals",
            "changed_dimension",
            "consume_item",
            "craft_recipe",
            "effects_changed",
            "enchanted_item",
            "entity_hurt_player",
            "entity_killed_player",
            "fall_from_height",
            "filled_bucket",
            "fishing_rod_hooked",
            "held_item",
            "impossible",
            "in_biome",
            "interact_furniture",
            "item_durability_changed",
            "item_used_on_block",
            "kill_entity_with_item",
            "killed_by_arrow",
            "obtain_item",
            "permission",
            "place_block",
            "place_furniture",
            "player_hurt_entity",
            "player_interacted_with_entity",
            "player_killed_entity",
            "player_sheared_equipment",
            "recipe_crafted",
            "recipe_unlocked",
            "shoot_bow",
            "shot_crossbow",
            "slept_in_bed",
            "started_riding",
            "tame_animal",
            "used_ender_eye",
            "used_totem",
            "using_item",
            "villager_trade"
        );
    }

    private List<String> flowOptions() {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null) {
            return List.of("none");
        }
        return CompactBindingSupport.flowOptions(serverId);
    }

    private List<String> functionOptions() {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null) {
            return List.of("none");
        }
        return CompactBindingSupport.functionOptions(serverId);
    }

    private FlowGraph selectedFunction(String functionId) {
        return selectedFunction(functionId, null);
    }

    private FlowGraph selectedFunction(String functionId, CompactBindingSupport.FunctionShape shape) {
        if (functionId == null || functionId.isBlank() || "none".equalsIgnoreCase(functionId)) {
            return null;
        }
        return CompactBindingSupport.selectedFunction(serverId, functionId, shape);
    }

    private void update(JsonObject object, String key, String value) {
        object.addProperty(key, value != null ? value : "");
    }

    private void update(JsonObject object, String key, boolean value) {
        object.addProperty(key, value);
    }

    private boolean isRealOption(String value) {
        return value != null && !"Loading".equals(value) && !"No Options".equals(value) && !"No Function".equals(value);
    }

    private String frameValue(JsonObject display) {
        String frame = text(display, "frame").toLowerCase(Locale.ROOT);
        return List.of("task", "goal", "challenge").contains(frame) ? frame : "task";
    }

    private boolean bool(JsonObject value, String key, boolean fallback) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return value.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String firstCriterionKey(JsonObject node) {
        JsonObject criteria = object(node, "criteria");
        return criteria.keySet().stream().findFirst().orElse("requirement");
    }

    private String firstCriterionTrigger(JsonObject node) {
        String trigger = firstCriterionValue(node, "trigger");
        return trigger.isBlank() ? "impossible" : trigger;
    }

    private String firstCriterionValue(JsonObject node, String key) {
        return text(firstCriterion(node), key);
    }

    private JsonObject firstCriterion(JsonObject node) {
        JsonObject criteria = object(node, "criteria");
        String key = firstCriterionKey(node);
        if (!criteria.has(key) || !criteria.get(key).isJsonObject()) {
            JsonObject criterion = new JsonObject();
            criterion.addProperty("trigger", "impossible");
            criteria.add(key, criterion);
        }
        ensureRequirements(node, key);
        return criteria.getAsJsonObject(key);
    }

    private void renameFirstCriterion(JsonObject node, String value) {
        String next = value == null || value.isBlank() ? "requirement" : value.trim();
        JsonObject criteria = object(node, "criteria");
        String current = firstCriterionKey(node);
        if (next.equals(current)) {
            ensureRequirements(node, next);
            return;
        }
        JsonElement existing = criteria.has(current) ? criteria.remove(current) : null;
        if (existing == null || !existing.isJsonObject()) {
            JsonObject criterion = new JsonObject();
            criterion.addProperty("trigger", "impossible");
            existing = criterion;
        }
        criteria.add(next, existing);
        ensureRequirements(node, next);
    }

    private void updateFirstCriterionTrigger(JsonObject node, String value) {
        String key = firstCriterionKey(node);
        JsonObject criterion = firstCriterion(node);
        JsonObject conditions = optionalObject(criterion, "conditions");
        if (conditions != null) {
            conditions.keySet().clear();
            criterion.remove("conditions");
        }
        criterion.addProperty("trigger", value == null || value.isBlank() ? "impossible" : value.trim());
        ensureRequirements(node, key);
    }

    private void updateFirstCriterionString(JsonObject node, String key, String value) {
        JsonObject criterion = firstCriterion(node);
        if (value == null || value.isBlank() || "No Flow".equals(value)) {
            criterion.remove(key);
            return;
        }
        criterion.addProperty(key, value.trim());
    }

    private String firstCriterionPredicateFunction(JsonObject node) {
        return text(optionalObject(firstCriterion(node), "predicate"), "functionId");
    }

    private void updateFirstCriterionPredicateFunction(JsonObject node, String value) {
        updateFunctionCall(firstCriterion(node), "predicate", value);
    }

    private String currentTrigger() {
        JsonObject node = currentNode();
        return node != null ? firstCriterionTrigger(node) : "impossible";
    }

    private JsonObject currentCriterionPredicateCall() {
        JsonObject node = currentNode();
        return node != null ? optionalObject(firstCriterion(node), "predicate") : null;
    }

    private String firstConditionValue(JsonObject node) {
        JsonObject criterion = firstCriterion(node);
        String conditionKey = conditionKey(text(criterion, "trigger"));
        JsonObject conditions = optionalObject(criterion, "conditions");
        if (conditions == null || conditionKey.isBlank() || !conditions.has(conditionKey) || conditions.get(conditionKey).isJsonNull()) {
            return "";
        }
        JsonElement value = conditions.get(conditionKey);
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            return value.getAsJsonArray().get(0).getAsString();
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : List.of("reference", "item", "block", "entity", "value")) {
                if (object.has(key) && !object.get(key).isJsonNull()) {
                    return object.get(key).getAsString();
                }
            }
        }
        return "";
    }

    private void updateFirstCondition(JsonObject node, String value) {
        JsonObject criterion = firstCriterion(node);
        String conditionKey = conditionKey(text(criterion, "trigger"));
        if (conditionKey.isBlank() || value == null || value.isBlank() || !isRealOption(value)) {
            clearFirstCondition(node);
            return;
        }
        JsonObject conditions = object(criterion, "conditions");
        conditions.keySet().clear();
        if ("permission".equals(conditionKey)) {
            JsonArray permissions = new JsonArray();
            for (String part : value.split("[,\\r\\n]+")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) {
                    permissions.add(trimmed);
                }
            }
            if (permissions.isEmpty()) {
                clearFirstCondition(node);
            } else {
                conditions.add(conditionKey, permissions);
            }
            return;
        }
        conditions.addProperty(conditionKey, value.trim());
    }

    private void clearFirstCondition(JsonObject node) {
        JsonObject criterion = firstCriterion(node);
        String conditionKey = conditionKey(text(criterion, "trigger"));
        JsonObject conditions = optionalObject(criterion, "conditions");
        if (conditions == null) {
            return;
        }
        if (conditionKey.isBlank()) {
            conditions.keySet().clear();
        } else {
            conditions.remove(conditionKey);
        }
        if (conditions.isEmpty()) {
            criterion.remove("conditions");
        }
    }

    private String conditionKey(String trigger) {
        return switch (trigger) {
            case "obtain_item", "consume_item", "using_item", "craft_recipe", "item_used_on_block", "kill_entity_with_item", "shoot_bow", "shot_crossbow", "filled_bucket", "item_durability_changed" -> "item";
            case "place_block", "break_block", "place_furniture", "break_furniture", "interact_furniture", "bee_nest_destroyed" -> "block";
            case "player_hurt_entity", "entity_hurt_player", "player_killed_entity", "entity_killed_player", "killed_by_arrow", "tame_animal", "bred_animals", "villager_trade", "player_interacted_with_entity", "player_sheared_equipment", "started_riding" -> "entity";
            case "recipe_crafted", "recipe_unlocked" -> "recipe";
            case "permission" -> "permission";
            case "in_biome" -> "biome";
            case "changed_dimension" -> "dimension";
            case "effects_changed" -> "changedEffect";
            default -> "";
        };
    }

    private String conditionCatalog(String trigger) {
        return switch (conditionKey(trigger)) {
            case "block" -> BLOCK_CATALOG;
            case "entity" -> ENTITY_CATALOG;
            case "recipe" -> RECIPE_CATALOG;
            case "biome" -> BIOME_CATALOG;
            case "dimension" -> WORLD_CATALOG;
            case "changedEffect" -> POTION_EFFECT_CATALOG;
            default -> "";
        };
    }

    private List<String> conditionOptions(String trigger) {
        String catalog = conditionCatalog(trigger);
        if (catalog.isBlank()) {
            return List.of("No Options");
        }
        return catalogOptions(catalog, conditionFallback(conditionKey(trigger)));
    }

    private List<String> conditionFallback(String conditionKey) {
        return switch (conditionKey) {
            case "block" -> List.of("stone", "dirt", "oak_log", "diamond_ore");
            case "entity" -> List.of("zombie", "skeleton", "creeper", "villager");
            case "recipe" -> List.of("minecraft:stone", "minecraft:stick");
            case "biome" -> List.of("minecraft:plains", "minecraft:forest", "minecraft:desert");
            case "dimension" -> List.of("world", "world_nether", "world_the_end");
            case "changedEffect" -> List.of("minecraft:speed", "minecraft:strength", "minecraft:regeneration");
            default -> List.of();
        };
    }

    private String completionSource(JsonObject node) {
        JsonObject source = optionalObject(node, "questCompletion");
        String type = text(source, "type");
        if (List.of("Manual", "Flow", "Function", "Command", "Event").contains(type)) {
            return type;
        }
        return "Manual";
    }

    private void updateCompletionSource(JsonObject node, String value) {
        String type = value == null || value.isBlank() ? "Manual" : value;
        JsonObject source = object(node, "questCompletion");
        source.addProperty("type", type);
        if (!"Flow".equals(type)) {
            source.remove("flowId");
        }
        if (!"Command".equals(type)) {
            source.remove("command");
        }
        if (!"Function".equals(type)) {
            source.remove("predicate");
        }
        if (!"Event".equals(type)) {
            updateFirstCriterionTrigger(node, "impossible");
            updateFirstCriterionString(node, "predicateFlowId", "");
            updateFirstCriterionPredicateFunction(node, "");
        } else if ("impossible".equals(firstCriterionTrigger(node))) {
            updateFirstCriterionTrigger(node, "obtain_item");
        }
        removeIfEmpty(node, "questCompletion");
    }

    private String questCompletionValue(JsonObject node, String key) {
        return text(optionalObject(node, "questCompletion"), key);
    }

    private String questCompletionPredicateFunction(JsonObject node) {
        return text(optionalObject(optionalObject(node, "questCompletion"), "predicate"), "functionId");
    }

    private void updateQuestCompletionPredicateFunction(JsonObject node, String value) {
        updateFunctionCall(object(node, "questCompletion"), "predicate", value);
    }

    private void updateQuestCompletionString(JsonObject node, String key, String value) {
        JsonObject source = object(node, "questCompletion");
        if (value == null || value.isBlank() || "No Flow".equals(value)) {
            source.remove(key);
            removeIfEmpty(node, "questCompletion");
            return;
        }
        source.addProperty(key, value.trim());
    }

    private void updateFunctionCall(JsonObject owner, String key, String functionId) {
        if (functionId == null || functionId.isBlank() || "No Function".equals(functionId) || "none".equals(functionId)) {
            owner.remove(key);
            return;
        }
        JsonObject call = object(owner, key);
        call.addProperty("type", "functionRef");
        call.addProperty("functionId", functionId.trim());
        pruneFunctionInputs(call, functionId.trim());
    }

    private void pruneFunctionInputs(JsonObject call, String functionId) {
        JsonObject inputs = optionalObject(call, "inputs");
        FlowGraph function = selectedFunction(functionId);
        if (inputs == null || function == null || function.getFunctionInputs() == null) {
            return;
        }
        List<String> allowed = new ArrayList<>();
        for (FlowGraph.FunctionParameter input : function.getFunctionInputs()) {
            if (input != null && input.getName() != null && !input.getName().isBlank()) {
                allowed.add(input.getName());
            }
        }
        for (String key : new ArrayList<>(inputs.keySet())) {
            if (!allowed.contains(key)) {
                inputs.remove(key);
            }
        }
        removeIfEmpty(call, "inputs");
    }

    private void ensureRequirements(JsonObject node, String key) {
        JsonArray group = new JsonArray();
        group.add(key);
        JsonArray requirements = new JsonArray();
        requirements.add(group);
        node.add("requirements", requirements);
    }

    private String rewardValue(JsonObject node, String key) {
        JsonObject rewards = optionalObject(node, "rewards");
        return text(rewards, key);
    }

    private String rewardArray(JsonObject node, String key) {
        return joinedArray(optionalObject(node, "rewards"), key);
    }

    private void updateRewardArray(JsonObject node, String key, String value) {
        updateStringArray(object(node, "rewards"), key, value);
        removeIfEmpty(node, "rewards");
    }

    private void updateRewardString(JsonObject node, String key, String value) {
        JsonObject rewards = object(node, "rewards");
        if (value == null || value.isBlank()) {
            rewards.remove(key);
            removeIfEmpty(node, "rewards");
            return;
        }
        rewards.addProperty(key, value.trim());
    }

    private void updateRewardNumber(JsonObject node, String key, String value) {
        JsonObject rewards = object(node, "rewards");
        if (value == null || value.isBlank()) {
            rewards.remove(key);
            removeIfEmpty(node, "rewards");
            return;
        }
        try {
            rewards.addProperty(key, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
        }
    }

    private String rewardType(JsonObject node) {
        JsonObject rewards = optionalObject(node, "rewards");
        if (!text(rewards, "experience").isBlank()) {
            return "Experience";
        }
        if (rewards != null && rewards.has("loot")) {
            return "Loot";
        }
        if (rewards != null && rewards.has("recipes")) {
            return "Recipe";
        }
        return "None";
    }

    private void updateRewardType(JsonObject node, String value) {
        if ("None".equals(value)) {
            node.remove("rewards");
            return;
        }
        JsonObject rewards = object(node, "rewards");
        rewards.remove("experience");
        rewards.remove("loot");
        rewards.remove("recipes");
        rewards.remove("function");
        switch (value) {
            case "Experience" -> rewards.addProperty("experience", 0);
            case "Loot" -> rewards.add("loot", new JsonArray());
            case "Recipe" -> rewards.add("recipes", new JsonArray());
            default -> removeIfEmpty(node, "rewards");
        }
    }

    private String completionValue(JsonObject node, String key) {
        return text(optionalObject(node, "onComplete"), key);
    }

    private String completionFunction(JsonObject node) {
        return text(optionalObject(optionalObject(node, "onComplete"), "action"), "functionId");
    }

    private void updateCompletionFunction(JsonObject node, String value) {
        updateFunctionCall(object(node, "onComplete"), "action", value);
    }

    private String completionArray(JsonObject node, String key) {
        return joinedArray(optionalObject(node, "onComplete"), key);
    }

    private void updateCompletionString(JsonObject node, String key, String value) {
        JsonObject onComplete = object(node, "onComplete");
        if (value == null || value.isBlank() || "No Flow".equals(value)) {
            onComplete.remove(key);
            removeIfEmpty(node, "onComplete");
            return;
        }
        onComplete.addProperty(key, value.trim());
    }

    private void updateCompletionArray(JsonObject node, String key, String value) {
        JsonObject onComplete = object(node, "onComplete");
        updateStringArray(onComplete, key, value);
        if (!onComplete.has(key)) {
            onComplete.add(key, new JsonArray());
        }
    }

    private String onCompleteType(JsonObject node) {
        JsonObject onComplete = optionalObject(node, "onComplete");
        if (onComplete != null && onComplete.has("flowId")) {
            return "Run Flow";
        }
        if (optionalObject(onComplete, "action") != null || optionalObject(onComplete, "function") != null) {
            return "Run Function";
        }
        if (onComplete != null && onComplete.has("commands")) {
            return "Run Command";
        }
        return "None";
    }

    private void updateOnCompleteType(JsonObject node, String value) {
        if ("None".equals(value)) {
            node.remove("onComplete");
            return;
        }
        JsonObject onComplete = object(node, "onComplete");
        onComplete.remove("flowId");
        onComplete.remove("commands");
        onComplete.remove("action");
        onComplete.remove("function");
        if ("Run Flow".equals(value)) {
            onComplete.addProperty("flowId", "");
        } else if ("Run Function".equals(value)) {
            JsonObject action = new JsonObject();
            action.addProperty("type", "functionRef");
            action.addProperty("functionId", "");
            onComplete.add("action", action);
        } else if ("Run Command".equals(value)) {
            onComplete.add("commands", new JsonArray());
        }
    }

    private String joinedArray(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            if (element != null && !element.isJsonNull()) {
                values.add(element.getAsString());
            }
        }
        return String.join(", ", values);
    }

    private void updateStringArray(JsonObject object, String key, String value) {
        if (value == null || value.isBlank()) {
            object.remove(key);
            return;
        }
        JsonArray array = new JsonArray();
        for (String part : value.split("[,\\r\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                array.add(trimmed);
            }
        }
        if (array.isEmpty()) {
            object.remove(key);
            return;
        }
        object.add(key, array);
    }

    private MinecraftRenderItem icon(JsonObject node) {
        String iconRef = text(object(node, "display"), "icon");
        ItemIconPreview.Preview preview = ItemIconPreview.resolve(serverId, iconRef);
        return preview.toRenderItem(title("", node));
    }

    private String nextNodeId() {
        return uniqueNodeId("new_advancement", "");
    }

    private int advancementWindowX() {
        int rightWidth = inspector != null ? inspector.layoutWidth(0) : 0;
        return Math.max(6, (width - rightWidth - WINDOW_WIDTH) / 2);
    }

    private int advancementWindowY() {
        return Math.max(6, (height - WINDOW_HEIGHT) / 2);
    }

    private int nodeX(AdvancementLayout layout, String nodeId, int contentX, double viewPanX) {
        LayoutNode node = layout.node(nodeId);
        return (int) Math.floor(contentX + viewPanX + (node != null ? node.x() * ADVANCEMENT_X_SCALE : 0));
    }

    private int nodeY(AdvancementLayout layout, String nodeId, int contentY, double viewPanY) {
        LayoutNode node = layout.node(nodeId);
        return (int) Math.floor(contentY + viewPanY + (node != null ? node.y() * ADVANCEMENT_Y_SCALE : 0));
    }

    private double viewPanX(AdvancementLayout layout) {
        if (isDraggingNode()) {
            return dragViewPanX;
        }
        int[] bounds = contentBounds(layout);
        if (bounds[2] - bounds[0] <= VIEWPORT_WIDTH) {
            if (customPan) {
                return panX;
            }
            return VIEWPORT_WIDTH / 2.0 - (bounds[0] + bounds[2]) / 2.0;
        }
        return Math.clamp(panX, VIEWPORT_WIDTH - bounds[2], -bounds[0]);
    }

    private double viewPanY(AdvancementLayout layout) {
        if (isDraggingNode()) {
            return dragViewPanY;
        }
        int[] bounds = contentBounds(layout);
        if (bounds[3] - bounds[1] <= VIEWPORT_HEIGHT) {
            if (customPan) {
                return panY;
            }
            return VIEWPORT_HEIGHT / 2.0 - (bounds[1] + bounds[3]) / 2.0;
        }
        return Math.clamp(panY, VIEWPORT_HEIGHT - bounds[3], -bounds[1]);
    }

    private void beginCustomPan(AdvancementLayout layout) {
        if (customPan) {
            return;
        }
        panX = viewPanX(layout);
        panY = viewPanY(layout);
        customPan = true;
    }

    private boolean canScrollX(AdvancementLayout layout) {
        int[] bounds = contentBounds(layout);
        return bounds[2] - bounds[0] > VIEWPORT_WIDTH;
    }

    private boolean canScrollY(AdvancementLayout layout) {
        int[] bounds = contentBounds(layout);
        return bounds[3] - bounds[1] > VIEWPORT_HEIGHT;
    }

    private boolean canPanViewport(AdvancementLayout layout) {
        return customPan || canScrollX(layout) || canScrollY(layout);
    }

    private void autoPanDraggedNode(AdvancementLayout layout, double mouseX, double mouseY) {
        int contentX = advancementWindowX() + VIEWPORT_X;
        int contentY = advancementWindowY() + VIEWPORT_Y;
        double nextPanX = dragViewPanX;
        double nextPanY = dragViewPanY;
        if (mouseX < contentX + DRAG_AUTO_PAN_EDGE) {
            nextPanX += DRAG_AUTO_PAN_SPEED;
        } else if (mouseX > contentX + VIEWPORT_WIDTH - DRAG_AUTO_PAN_EDGE) {
            nextPanX -= DRAG_AUTO_PAN_SPEED;
        }
        if (mouseY < contentY + DRAG_AUTO_PAN_EDGE) {
            nextPanY += DRAG_AUTO_PAN_SPEED;
        } else if (mouseY > contentY + VIEWPORT_HEIGHT - DRAG_AUTO_PAN_EDGE) {
            nextPanY -= DRAG_AUTO_PAN_SPEED;
        }
        if (nextPanX == dragViewPanX && nextPanY == dragViewPanY) {
            return;
        }
        dragViewPanX = nextPanX;
        dragViewPanY = nextPanY;
        panX = dragViewPanX;
        panY = dragViewPanY;
        customPan = true;
        clampPan(layout);
        dragViewPanX = panX;
        dragViewPanY = panY;
    }

    private void clampPan(AdvancementLayout layout) {
        int[] bounds = contentBounds(layout);
        if (bounds[2] - bounds[0] > VIEWPORT_WIDTH) {
            panX = Math.clamp(panX, VIEWPORT_WIDTH - bounds[2], -bounds[0]);
        } else {
            panX = clampVisiblePan(panX, bounds[0], bounds[2], VIEWPORT_WIDTH);
        }
        if (bounds[3] - bounds[1] > VIEWPORT_HEIGHT) {
            panY = Math.clamp(panY, VIEWPORT_HEIGHT - bounds[3], -bounds[1]);
        } else {
            panY = clampVisiblePan(panY, bounds[1], bounds[3], VIEWPORT_HEIGHT);
        }
    }

    private double clampVisiblePan(double value, int min, int max, int viewportSize) {
        int visibleMargin = 8;
        double lower = -max + visibleMargin;
        double upper = viewportSize - min - visibleMargin;
        return Math.clamp(value, lower, upper);
    }

    private boolean isDraggingNode() {
        return draggedNode != null && !draggedNode.isBlank();
    }

    private void updateDragBranchTarget(AdvancementLayout layout, double mouseX, double mouseY) {
        dragTargetNode = null;
        if (draggedNode == null || "root".equals(draggedNode)) {
            return;
        }
        String hovered = hoveredNode(layout, mouseX, mouseY, draggedNode);
        if (hovered == null || hovered.isBlank()) {
            return;
        }
        if (canDropOnNode(draggedNode, hovered)) {
            dragTargetNode = hovered;
        }
    }

    private String hoveredNode(AdvancementLayout layout, double mouseX, double mouseY, String excludedNode) {
        int contentX = advancementWindowX() + VIEWPORT_X;
        int contentY = advancementWindowY() + VIEWPORT_Y;
        if (mouseX < contentX || mouseX > contentX + VIEWPORT_WIDTH || mouseY < contentY || mouseY > contentY + VIEWPORT_HEIGHT) {
            return "";
        }
        List<LayoutNode> ordered = new ArrayList<>(layout.nodes().values());
        ordered.sort(Comparator.comparingInt(LayoutNode::depth).reversed());
        for (LayoutNode layoutNode : ordered) {
            if (layoutNode.id().equals(excludedNode)) {
                continue;
            }
            int x = nodeX(layout, layoutNode.id(), contentX, dragViewPanX);
            int y = nodeY(layout, layoutNode.id(), contentY, dragViewPanY);
            int frameX = x + FRAME_X;
            if (mouseX >= frameX && mouseX <= frameX + NODE_WIDTH && mouseY >= y && mouseY <= y + NODE_HEIGHT) {
                return layoutNode.id();
            }
        }
        return "";
    }

    private void commitDragBranchChange() {
        String movedNode = draggedNode;
        String targetNode = dragTargetNode;
        if (!nodeDragMoved || movedNode == null || movedNode.isBlank() || "root".equals(movedNode)) {
            return;
        }
        commitDragBranchChange(movedNode, targetNode);
    }

    private void commitDragBranchChange(String movedNode, String targetNode) {
        if (treeDraft.defer(() -> commitDragBranchChange(movedNode, targetNode))) {
            return;
        }
        JsonObject node = nodeById(movedNode);
        if (node == null) {
            return;
        }
        String nextParent = targetNode != null && !targetNode.isBlank() ? targetNode : parentValue(node);
        if (isDescendant(nextParent, movedNode)) {
            commitDescendantSwap(movedNode, nextParent);
            return;
        }
        if (!canReparent(movedNode, nextParent)) {
            return;
        }
        String previousParent = parentValue(node);
        boolean parentChanged = !nextParent.equals(previousParent);
        if (!parentChanged) {
            return;
        }
        snapshot();
        node.addProperty("parent", nextParent);
        reorderNodeEntries(movedNode, nextParent);
        lastInspectorNode = "";
        applyInspectorSelection();
        refreshJson();
    }

    private void commitDescendantSwap(String movedNode, String targetNode) {
        if ("root".equals(movedNode) || movedNode.equals(targetNode) || !nodes().has(movedNode) || !nodes().has(targetNode)) {
            return;
        }
        JsonObject moved = nodes().getAsJsonObject(movedNode);
        JsonObject target = nodes().getAsJsonObject(targetNode);
        String movedParent = parentValue(moved);
        if (movedParent.equals(targetNode)) {
            return;
        }
        snapshot();
        target.addProperty("parent", movedParent);
        moved.addProperty("parent", targetNode);
        reorderNodeEntries(targetNode, movedParent);
        reorderNodeEntries(movedNode, targetNode);
        lastInspectorNode = "";
        applyInspectorSelection();
        refreshJson();
    }

    private boolean canDropOnNode(String childId, String parentId) {
        return childId != null && parentId != null && !childId.isBlank() && !parentId.isBlank() && !childId.equals(parentId) && nodes().has(childId) && nodes().has(parentId);
    }

    private boolean canReparent(String childId, String parentId) {
        if (childId == null || parentId == null || childId.isBlank() || parentId.isBlank() || childId.equals(parentId) || !nodes().has(childId) || !nodes().has(parentId)) {
            return false;
        }
        return !isDescendant(parentId, childId);
    }

    private boolean isDescendant(String possibleDescendant, String ancestor) {
        String treeId = text(tree, "id");
        String current = possibleDescendant;
        Set<String> visited = new HashSet<>();
        while (current != null && !current.isBlank() && nodes().has(current) && visited.add(current)) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = localParentId(text(nodes().getAsJsonObject(current), "parent"), treeId);
        }
        return false;
    }

    private void reorderNodeEntries(String movedNode, String parentId) {
        JsonObject source = nodes();
        List<String> order = new ArrayList<>(source.keySet());
        order.remove(movedNode);
        int insertIndex = insertionIndexForNodeOrder(source, order, parentId);
        order.add(insertIndex, movedNode);
        JsonObject reordered = new JsonObject();
        for (String id : order) {
            if (source.has(id)) {
                reordered.add(id, source.get(id));
            }
        }
        source.keySet().clear();
        for (Map.Entry<String, JsonElement> entry : reordered.entrySet()) {
            source.add(entry.getKey(), entry.getValue());
        }
    }

    private int insertionIndexForNodeOrder(JsonObject source, List<String> order, String parentId) {
        int insertIndex = order.indexOf(parentId);
        if (insertIndex < 0) {
            insertIndex = 0;
        }
        String treeId = text(tree, "id");
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            if (source.has(id) && source.get(id).isJsonObject() && parentId.equals(localParentId(text(source.getAsJsonObject(id), "parent"), treeId))) {
                insertIndex = i + 1;
            }
        }
        return Math.clamp(insertIndex, 0, order.size());
    }

    private int[] contentBounds(AdvancementLayout layout) {
        return new int[] {layout.minX(), layout.minY(), layout.maxX(), layout.maxY()};
    }

    private AdvancementLayout layout(JsonObject nodes) {
        Map<String, LayoutWorkNode> workNodes = new HashMap<>();
        LayoutWorkNode root = buildLayoutWorkNode(nodes, "root", null, null, 1, 0, workNodes, new HashSet<>());
        if (root == null) {
            return fallbackLayout(nodes);
        }
        firstWalk(root);
        float minimumY = secondWalk(root, 0.0f, 0, root.y);
        if (minimumY < 0.0f) {
            thirdWalk(root, -minimumY);
        }
        Map<String, LayoutNode> result = new HashMap<>();
        copyLayout(root, null, result);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (LayoutNode node : result.values()) {
            int x = node.x() * ADVANCEMENT_X_SCALE;
            int y = (int) Math.floor(node.y() * ADVANCEMENT_Y_SCALE);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + 30);
            maxY = Math.max(maxY, y + NODE_HEIGHT);
        }
        if (minX == Integer.MAX_VALUE) {
            return fallbackLayout(nodes);
        }
        return new AdvancementLayout(result, minX, minY, maxX, maxY);
    }

    private AdvancementLayout fallbackLayout(JsonObject nodes) {
        Map<String, LayoutNode> result = new HashMap<>();
        int index = 0;
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject node = entry.getValue().getAsJsonObject();
            LayoutNode layoutNode = new LayoutNode(entry.getKey(), node, text(node, "parent"), List.of(), index, index, 0, index + 1, null, null);
            result.put(entry.getKey(), layoutNode);
            index++;
        }
        int maxX = Math.max(VIEWPORT_WIDTH, index * ADVANCEMENT_X_SCALE + 30);
        return new AdvancementLayout(result, 0, 0, maxX, VIEWPORT_HEIGHT);
    }

    private LayoutWorkNode buildLayoutWorkNode(JsonObject nodes, String id, LayoutWorkNode parent, LayoutWorkNode previousSibling, int childIndex, int depth, Map<String, LayoutWorkNode> workNodes, Set<String> visiting) {
        if (id == null || id.isBlank() || workNodes.containsKey(id) || visiting.contains(id) || !nodes.has(id) || !nodes.get(id).isJsonObject()) {
            return null;
        }
        visiting.add(id);
        JsonObject node = nodes.getAsJsonObject(id);
        LayoutWorkNode workNode = new LayoutWorkNode(id, node, parent, previousSibling, childIndex, depth);
        workNodes.put(id, workNode);
        LayoutWorkNode previous = null;
        int index = 1;
        for (String childId : childIds(nodes, id)) {
            LayoutWorkNode child = buildLayoutWorkNode(nodes, childId, workNode, previous, index, depth + 1, workNodes, visiting);
            if (child != null) {
                workNode.children.add(child);
                previous = child;
                index++;
            }
        }
        visiting.remove(id);
        return workNode;
    }

    private List<String> childIds(JsonObject nodes, String parentId) {
        List<String> children = new ArrayList<>();
        String treeId = text(tree, "id");
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            String nodeParent = text(entry.getValue().getAsJsonObject(), "parent");
            if (parentId.equals(localParentId(nodeParent, treeId))) {
                children.add(entry.getKey());
            }
        }
        return children;
    }

    private String localParentId(String parentId, String treeId) {
        if (parentId == null || parentId.isBlank() || parentId.contains(":")) {
            return "";
        }
        if (!parentId.contains("/")) {
            return parentId;
        }
        String[] parts = parentId.split("/", 2);
        return parts.length == 2 && parts[0].equals(treeId) ? parts[1] : "";
    }

    private void firstWalk(LayoutWorkNode node) {
        if (node.children.isEmpty()) {
            node.y = node.previousSibling != null ? node.previousSibling.y + 1.0f : 0.0f;
            return;
        }
        LayoutWorkNode defaultAncestor = null;
        for (LayoutWorkNode child : node.children) {
            firstWalk(child);
            defaultAncestor = apportion(child, defaultAncestor == null ? child : defaultAncestor);
        }
        executeShifts(node);
        float midpoint = (node.children.getFirst().y + node.children.getLast().y) / 2.0f;
        if (node.previousSibling != null) {
            node.y = node.previousSibling.y + 1.0f;
            node.mod = node.y - midpoint;
        } else {
            node.y = midpoint;
        }
    }

    private float secondWalk(LayoutWorkNode node, float modifier, int depth, float minimumY) {
        node.y += modifier;
        node.x = depth;
        if (node.y < minimumY) {
            minimumY = node.y;
        }
        for (LayoutWorkNode child : node.children) {
            minimumY = secondWalk(child, modifier + node.mod, depth + 1, minimumY);
        }
        return minimumY;
    }

    private void thirdWalk(LayoutWorkNode node, float shift) {
        node.y += shift;
        for (LayoutWorkNode child : node.children) {
            thirdWalk(child, shift);
        }
    }

    private void executeShifts(LayoutWorkNode node) {
        float shift = 0.0f;
        float change = 0.0f;
        for (int i = node.children.size() - 1; i >= 0; i--) {
            LayoutWorkNode child = node.children.get(i);
            child.y += shift;
            child.mod += shift;
            change += child.change;
            shift += child.shift + change;
        }
    }

    private LayoutWorkNode apportion(LayoutWorkNode node, LayoutWorkNode defaultAncestor) {
        if (node.previousSibling == null) {
            return defaultAncestor;
        }
        LayoutWorkNode innerRight = node;
        LayoutWorkNode outerRight = node;
        LayoutWorkNode innerLeft = node.previousSibling;
        LayoutWorkNode outerLeft = node.parent.children.getFirst();
        float innerRightMod = node.mod;
        float outerRightMod = node.mod;
        float innerLeftMod = innerLeft.mod;
        float outerLeftMod = outerLeft.mod;
        while (nextOrThread(innerLeft) != null && previousOrThread(innerRight) != null) {
            innerLeft = nextOrThread(innerLeft);
            innerRight = previousOrThread(innerRight);
            outerLeft = previousOrThread(outerLeft);
            outerRight = nextOrThread(outerRight);
            outerRight.ancestor = node;
            float move = innerLeft.y + innerLeftMod - (innerRight.y + innerRightMod) + 1.0f;
            if (move > 0.0f) {
                moveSubtree(getAncestor(innerLeft, node, defaultAncestor), node, move);
                innerRightMod += move;
                outerRightMod += move;
            }
            innerLeftMod += innerLeft.mod;
            innerRightMod += innerRight.mod;
            outerLeftMod += outerLeft.mod;
            outerRightMod += outerRight.mod;
        }
        if (nextOrThread(innerLeft) != null && nextOrThread(outerRight) == null) {
            outerRight.thread = nextOrThread(innerLeft);
            outerRight.mod += innerLeftMod - outerRightMod;
        } else {
            if (previousOrThread(innerRight) != null && previousOrThread(outerLeft) == null) {
                outerLeft.thread = previousOrThread(innerRight);
                outerLeft.mod += innerRightMod - outerLeftMod;
            }
            defaultAncestor = node;
        }
        return defaultAncestor;
    }

    private LayoutWorkNode previousOrThread(LayoutWorkNode node) {
        return node.thread != null ? node.thread : (!node.children.isEmpty() ? node.children.getFirst() : null);
    }

    private LayoutWorkNode nextOrThread(LayoutWorkNode node) {
        return node.thread != null ? node.thread : (!node.children.isEmpty() ? node.children.getLast() : null);
    }

    private void moveSubtree(LayoutWorkNode left, LayoutWorkNode right, float move) {
        float subtreeCount = right.childIndex - left.childIndex;
        if (subtreeCount != 0.0f) {
            right.change -= move / subtreeCount;
            left.change += move / subtreeCount;
        }
        right.shift += move;
        right.y += move;
        right.mod += move;
    }

    private LayoutWorkNode getAncestor(LayoutWorkNode node, LayoutWorkNode sibling, LayoutWorkNode defaultAncestor) {
        return node.ancestor != null && sibling.parent.children.contains(node.ancestor) ? node.ancestor : defaultAncestor;
    }

    private void copyLayout(LayoutWorkNode workNode, LayoutNode parent, Map<String, LayoutNode> result) {
        List<LayoutNode> children = new ArrayList<>();
        LayoutNode layoutNode = new LayoutNode(workNode.id, workNode.node, parent != null ? parent.id() : "", children, workNode.x, workNode.x, workNode.y, workNode.childIndex, parent, workNode.previousSibling != null ? result.get(workNode.previousSibling.id) : null);
        result.put(workNode.id, layoutNode);
        for (LayoutWorkNode child : workNode.children) {
            copyLayout(child, layoutNode, result);
            LayoutNode copied = result.get(child.id);
            children.add(copied);
        }
    }

    private void drawAdvancementBackground(IDrawContext context, MinecraftGameAssets gameAssets, int x, int y) {
        MinecraftAssetReference window = gameAssets.asset("minecraft", "textures/gui/advancements/window.png");
        if (!drawAssetRegion(context, gameAssets, window, x, y, WINDOW_WIDTH, WINDOW_HEIGHT, 0, 0, WINDOW_WIDTH, WINDOW_HEIGHT, 256, 256)) {
            context.fill(x, y, x + WINDOW_WIDTH, y + WINDOW_HEIGHT, 0xFFC6C6C6);
            context.fill(x + VIEWPORT_X, y + VIEWPORT_Y, x + VIEWPORT_X + VIEWPORT_WIDTH, y + VIEWPORT_Y + VIEWPORT_HEIGHT, 0xFF000000);
        }
        context.drawText(windowTitle(), x + 8, y + 6, 0xFF404040, false);
    }

    private String tabId(JsonObject value) {
        String id = text(value, "id");
        return id.isBlank() ? "advancement" : id;
    }

    private JsonObject rootNode(JsonObject value) {
        if (value == null || !value.has("nodes") || !value.get("nodes").isJsonObject()) {
            return null;
        }
        JsonObject resourceNodes = value.getAsJsonObject("nodes");
        return resourceNodes.has("root") && resourceNodes.get("root").isJsonObject() ? resourceNodes.getAsJsonObject("root") : null;
    }

    private void drawTiledBackground(IDrawContext context, MinecraftGameAssets gameAssets, int x, int y, double viewPanX, double viewPanY) {
        String background = rootBackground();
        MinecraftAssetReference reference = assetReference(background, "textures/gui/advancements/backgrounds/stone.png");
        if (!gameAssets.exists(reference)) {
            context.fill(x, y, x + VIEWPORT_WIDTH, y + VIEWPORT_HEIGHT, 0xFF202020);
            return;
        }
        int offsetX = (int) Math.floor(viewPanX) % BACKGROUND_TILE_SIZE;
        int offsetY = (int) Math.floor(viewPanY) % BACKGROUND_TILE_SIZE;
        for (int tileX = -1; tileX <= 15; tileX++) {
            for (int tileY = -1; tileY <= 8; tileY++) {
                drawAssetRegion(context, gameAssets, reference, x + offsetX + BACKGROUND_TILE_SIZE * tileX, y + offsetY + BACKGROUND_TILE_SIZE * tileY, BACKGROUND_TILE_SIZE, BACKGROUND_TILE_SIZE, 0, 0, BACKGROUND_TILE_SIZE, BACKGROUND_TILE_SIZE, BACKGROUND_TILE_SIZE, BACKGROUND_TILE_SIZE);
            }
        }
    }

    private void drawViewportDepth(IDrawContext context, int x, int y) {
        int[] colors = {0x88000000, 0x66000000, 0x44000000, 0x22000000, 0x11000000};
        for (int i = 0; i < colors.length; i++) {
            context.fill(x, y + i, x + VIEWPORT_WIDTH, y + i + 1, colors[i]);
            context.fill(x + i, y, x + i + 1, y + VIEWPORT_HEIGHT, colors[i]);
            context.fill(x, y + VIEWPORT_HEIGHT - i - 1, x + VIEWPORT_WIDTH, y + VIEWPORT_HEIGHT - i, colors[i]);
            context.fill(x + VIEWPORT_WIDTH - i - 1, y, x + VIEWPORT_WIDTH - i, y + VIEWPORT_HEIGHT, colors[i]);
        }
    }

    private void drawConnection(IDrawContext context, AdvancementLayout layout, String nodeId, JsonObject node, int contentX, int contentY, double viewPanX, double viewPanY, boolean shadow) {
        LayoutNode layoutNode = layout.node(nodeId);
        LayoutNode parentNode = layoutNode != null ? layoutNode.parent() : null;
        if (layoutNode == null || parentNode == null) {
            return;
        }
        int parentX = nodeX(layout, parentNode.id(), contentX, viewPanX);
        int parentY = nodeY(layout, parentNode.id(), contentY, viewPanY);
        int childX = nodeX(layout, nodeId, contentX, viewPanX);
        int childY = nodeY(layout, nodeId, contentY, viewPanY);
        drawConnectionLines(context, parentX, parentY, childX, childY, shadow ? 0xFF000000 : 0xFFFFFFFF, shadow);
    }

    private void drawDragTargetConnection(IDrawContext context, AdvancementLayout layout, int contentX, int contentY, double viewPanX, double viewPanY) {
        LayoutNode parentNode = layout.node(dragTargetNode);
        LayoutNode childNode = layout.node(draggedNode);
        if (parentNode == null || childNode == null) {
            return;
        }
        int parentX = nodeX(layout, parentNode.id(), contentX, viewPanX);
        int parentY = nodeY(layout, parentNode.id(), contentY, viewPanY);
        int childX = nodeX(layout, childNode.id(), contentX, viewPanX);
        int childY = nodeY(layout, childNode.id(), contentY, viewPanY);
        drawConnectionLines(context, parentX, parentY, childX, childY, 0xAAFFFFFF, false);
    }

    private void drawConnectionLines(IDrawContext context, int parentX, int parentY, int childX, int childY, int color, boolean shadow) {
        int parentCenterX = parentX + 13;
        int parentExitX = parentX + 30;
        int parentCenterY = parentY + 13;
        int childCenterX = childX + 13;
        int childCenterY = childY + 13;
        if (shadow) {
            hLine(context, parentExitX, parentCenterX, parentCenterY - 1, color);
            hLine(context, parentExitX + 1, parentCenterX, parentCenterY, color);
            hLine(context, parentExitX, parentCenterX, parentCenterY + 1, color);
            hLine(context, childCenterX, parentExitX - 1, childCenterY - 1, color);
            hLine(context, childCenterX, parentExitX - 1, childCenterY, color);
            hLine(context, childCenterX, parentExitX - 1, childCenterY + 1, color);
            vLine(context, parentExitX - 1, childCenterY, parentCenterY, color);
            vLine(context, parentExitX + 1, childCenterY, parentCenterY, color);
            return;
        }
        hLine(context, parentExitX, parentCenterX, parentCenterY, color);
        hLine(context, childCenterX, parentExitX, childCenterY, color);
        vLine(context, parentExitX, childCenterY, parentCenterY, color);
    }

    private void hLine(IDrawContext context, int x1, int x2, int y, int color) {
        context.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 1, color);
    }

    private void vLine(IDrawContext context, int x, int y1, int y2, int color) {
        context.fill(x, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, color);
    }

    private void drawHiddenNodeMarker(IDrawContext context, int x, int y) {
        for (int offset = 0; offset < 16; offset++) {
            context.fill(x + 6 + offset, y + 6 + offset, x + 7 + offset, y + 7 + offset, 0xCC000000);
            context.fill(x + 6 + offset, y + 7 + offset, x + 7 + offset, y + 8 + offset, 0x66000000);
        }
    }

    private TooltipLayout tooltipLayout(AdvancementLayout layout, String id, JsonObject node, int contentX, int contentY, double viewPanX, double viewPanY) {
        int nodeX = nodeX(layout, id, contentX, viewPanX);
        int nodeY = nodeY(layout, id, contentY, viewPanY);
        JsonObject display = object(node, "display");
        String title = title(id, node);
        String description = text(display, "description");
        List<String> titleLines = wrapText(title, TITLE_TEXT_MAX_WIDTH, Integer.MAX_VALUE);
        int titleTextWidth = Math.max(widestLine(titleLines), 80);
        int targetDescriptionWidth = 29 + titleTextWidth;
        List<String> descriptionLines = findOptimalLines(description, targetDescriptionWidth);
        int boxWidth = Math.max(targetDescriptionWidth, widestLine(descriptionLines)) + 8;
        int titleHeight = Math.max(26, titleLines.size() * TEXT_LINE_HEIGHT + TITLE_BOX_EXTRA_HEIGHT);
        int descriptionTextHeight = descriptionLines.size() * TEXT_LINE_HEIGHT;
        int descriptionHeight = descriptionLines.isEmpty() ? 0 : descriptionTextHeight + DESCRIPTION_BOX_EXTRA_HEIGHT;
        int titleY = nodeY + (NODE_HEIGHT - titleHeight) / 2;
        int titleBottom = titleY + titleHeight;
        boolean flippedLeft = nodeX + boxWidth + 26 >= width;
        boolean flippedDescription = titleBottom + descriptionHeight >= contentY + VIEWPORT_HEIGHT;
        int boxX = flippedLeft ? nodeX - boxWidth + 26 + 6 : nodeX;
        int descriptionY = flippedDescription ? titleBottom - titleHeight - descriptionHeight : titleY;
        int descriptionTextY = (flippedDescription ? titleY - descriptionTextHeight + 1 : titleBottom) + DESCRIPTION_TEXT_Y_OFFSET;
        return new TooltipLayout(id, node, nodeX, nodeY, boxX, titleY, boxWidth, titleHeight, descriptionY, descriptionTextY, descriptionHeight, flippedLeft, titleLines, descriptionLines);
    }

    private void drawTooltip(IDrawContext context, MinecraftGameAssets gameAssets, TooltipLayout layout) {
        drawTooltipBoxTextures(context, gameAssets, layout);
        drawSprite(context, gameAssets, frameSprite(layout.node(), layout.id().equals(selectedNode)), layout.nodeX() + FRAME_X, layout.nodeY(), NODE_WIDTH, NODE_HEIGHT);
        drawTooltipBoxText(context, layout);
        MinecraftRenderItem icon = icon(layout.node());
        if (icon != null) {
            context.drawItem(icon, layout.nodeX() + ICON_X, layout.nodeY() + ICON_Y, 0);
        }
    }

    private void drawTooltipBoxTextures(IDrawContext context, MinecraftGameAssets gameAssets, TooltipLayout layout) {
        if (layout.descriptionHeight() > 0) {
            drawSpriteSlice(context, gameAssets, "advancements/title_box", layout.boxX(), layout.descriptionY(), layout.boxWidth(), layout.titleHeight() + layout.descriptionHeight());
        }
        drawSpriteSlice(context, gameAssets, layout.id().equals(selectedNode) ? "advancements/box_obtained" : "advancements/box_unobtained", layout.boxX(), layout.titleY(), layout.boxWidth(), layout.titleHeight());
    }

    private void drawTooltipBoxText(IDrawContext context, TooltipLayout layout) {
        int titleTextX = layout.flippedLeft() ? layout.boxX() + 5 : layout.nodeX() + TITLE_TEXT_X;
        int titleTextY = layout.titleY() + 9;
        for (String line : layout.titleLines()) {
            context.drawText(line, titleTextX, titleTextY, 0xFFFFFFFF, true);
            titleTextY += TEXT_LINE_HEIGHT;
        }
        int descriptionTextY = layout.descriptionTextY();
        for (String line : layout.descriptionLines()) {
            context.drawText(line, layout.boxX() + DESCRIPTION_TEXT_X, descriptionTextY, descriptionColor(text(object(layout.node(), "display"), "frame")), true);
            descriptionTextY += TEXT_LINE_HEIGHT;
        }
    }

    private void drawSprite(IDrawContext context, MinecraftGameAssets gameAssets, String sprite, int x, int y, int width, int height) {
        MinecraftAssetReference reference = gameAssets.asset("minecraft", "textures/gui/sprites/" + sprite + ".png");
        if (drawAssetRegion(context, gameAssets, reference, x, y, width, height, 0, 0, width, height, width, height)) {
            return;
        }
        context.fill(x, y, x + width, y + height, 0xFF1F1F1F);
        context.fill(x, y, x + width, y + 1, 0xFFFFFFFF);
        context.fill(x, y + height - 1, x + width, y + height, 0xFF555555);
        context.fill(x, y, x + 1, y + height, 0xFFFFFFFF);
        context.fill(x + width - 1, y, x + width, y + height, 0xFF555555);
    }

    private void drawSpriteSlice(IDrawContext context, MinecraftGameAssets gameAssets, String sprite, int x, int y, int width, int height) {
        MinecraftAssetReference reference = gameAssets.asset("minecraft", "textures/gui/sprites/" + sprite + ".png");
        if (drawNineSlice(context, gameAssets, reference, x, y, width, height)) {
            return;
        }
        context.fill(x, y, x + width, y + height, 0xFF606060);
    }

    private boolean drawNineSlice(IDrawContext context, MinecraftGameAssets gameAssets, MinecraftAssetReference reference, int x, int y, int width, int height) {
        Object nativeIdentifier = gameAssets.getNativeIdentifier(reference);
        if (!gameAssets.exists(reference) && nativeIdentifier == null) {
            return false;
        }
        int border = Math.min(TOOLTIP_SLICE_BORDER, Math.min(width, height) / 2);
        int centerWidth = Math.max(0, width - border * 2);
        int centerHeight = Math.max(0, height - border * 2);
        int sourceCenterWidth = TOOLTIP_SLICE_WIDTH - TOOLTIP_SLICE_BORDER * 2;
        int sourceCenterHeight = TOOLTIP_SLICE_HEIGHT - TOOLTIP_SLICE_BORDER * 2;
        drawAssetRegion(context, gameAssets, reference, x, y, border, border, 0, 0, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x + border, y, centerWidth, border, TOOLTIP_SLICE_BORDER, 0, sourceCenterWidth, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x + border + centerWidth, y, border, border, TOOLTIP_SLICE_WIDTH - TOOLTIP_SLICE_BORDER, 0, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x, y + border, border, centerHeight, 0, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, sourceCenterHeight, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x + border, y + border, centerWidth, centerHeight, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, sourceCenterWidth, sourceCenterHeight, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x + border + centerWidth, y + border, border, centerHeight, TOOLTIP_SLICE_WIDTH - TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, sourceCenterHeight, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x, y + border + centerHeight, border, border, 0, TOOLTIP_SLICE_HEIGHT - TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x + border, y + border + centerHeight, centerWidth, border, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_HEIGHT - TOOLTIP_SLICE_BORDER, sourceCenterWidth, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        drawAssetRegion(context, gameAssets, reference, x + border + centerWidth, y + border + centerHeight, border, border, TOOLTIP_SLICE_WIDTH - TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_HEIGHT - TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_BORDER, TOOLTIP_SLICE_WIDTH, TOOLTIP_SLICE_HEIGHT);
        return true;
    }

    private boolean drawAssetRegion(IDrawContext context, MinecraftGameAssets gameAssets, MinecraftAssetReference reference, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        return MinecraftUiPreviewRenderer.drawAssetRegion(context, gameAssets, reference, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    private String frameSprite(JsonObject node, boolean selected) {
        JsonObject display = object(node, "display");
        String frame = text(display, "frame").toLowerCase(Locale.ROOT);
        if (!List.of("task", "challenge", "goal").contains(frame)) {
            frame = "task";
        }
        return "advancements/" + frame + "_frame_" + (selected ? "obtained" : "unobtained");
    }

    private int descriptionColor(String frame) {
        return "challenge".equalsIgnoreCase(frame) ? 0xFFAA00AA : 0xFF55FF55;
    }

    private int textWidth(String value) {
        return value == null || value.isBlank() ? 0 : TextRenderer.getWidth(value);
    }

    private int widestLine(List<String> lines) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, textWidth(line));
        }
        return width;
    }

    private List<String> findOptimalLines(String value, int targetWidth) {
        List<String> closest = List.of();
        int closestDistance = Integer.MAX_VALUE;
        for (int offset : DESCRIPTION_SPLIT_OFFSETS) {
            List<String> lines = wrapText(value, Math.max(1, targetWidth - offset), Integer.MAX_VALUE);
            int distance = Math.abs(widestLine(lines) - targetWidth);
            if (distance <= 10) {
                return lines;
            }
            if (distance < closestDistance) {
                closest = lines;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private List<String> wrapText(String value, int maxWidth, int maxLines) {
        if (value == null || value.isBlank() || maxLines <= 0) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.replace('\r', '\n').split("\\n")) {
            wrapParagraph(paragraph.trim(), maxWidth, maxLines, lines);
            if (lines.size() >= maxLines) {
                break;
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private void wrapParagraph(String paragraph, int maxWidth, int maxLines, List<String> lines) {
        if (paragraph.isBlank()) {
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String word : paragraph.split("\\s+")) {
            String next = current.length() == 0 ? word : current + " " + word;
            if (textWidth(next) <= maxWidth) {
                current.setLength(0);
                current.append(next);
                continue;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
                if (lines.size() >= maxLines) {
                    return;
                }
            }
            if (textWidth(word) <= maxWidth) {
                current.append(word);
                continue;
            }
            for (String part : splitLongWord(word, maxWidth)) {
                if (lines.size() >= maxLines) {
                    return;
                }
                if (textWidth(part) <= maxWidth && current.length() == 0) {
                    current.append(part);
                } else {
                    lines.add(part);
                }
            }
        }
        if (current.length() > 0 && lines.size() < maxLines) {
            lines.add(current.toString());
        }
    }

    private List<String> splitLongWord(String word, int maxWidth) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            String next = current.toString() + word.charAt(i);
            if (textWidth(next) <= maxWidth || current.length() == 0) {
                current.append(word.charAt(i));
                continue;
            }
            parts.add(current.toString());
            current.setLength(0);
            current.append(word.charAt(i));
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private String windowTitle() {
        JsonObject root = nodes().has("root") ? nodes().getAsJsonObject("root") : null;
        String title = root != null ? text(object(root, "display"), "title") : "";
        if (title.isBlank()) {
            title = text(tree, "id");
        }
        return title.isBlank() ? "Advancements" : title;
    }

    private String rootBackground() {
        JsonObject root = nodes().has("root") ? nodes().getAsJsonObject("root") : null;
        String background = root != null ? backgroundPath(object(root, "display")) : "";
        if (background.isBlank()) {
            background = text(object(tree, "display"), "background");
        }
        if (background.isBlank()) {
            background = text(tree, "background");
        }
        return background.isBlank() ? "minecraft:textures/gui/advancements/backgrounds/stone.png" : background;
    }

    private String backgroundPath(JsonObject display) {
        return normalizeBackgroundValue(text(display, "background"));
    }

    private String backgroundMaterial(JsonObject display) {
        String background = backgroundPath(display);
        String path = background.contains(":") ? background.split(":", 2)[1] : background;
        if (path.startsWith("textures/block/") && path.endsWith(".png")) {
            return path.substring("textures/block/".length(), path.length() - ".png".length());
        }
        return "stone";
    }

    private String materialToBackgroundTexture(String value) {
        String material = value == null ? "" : value.trim();
        if (material.isBlank() || "Loading".equals(material)) {
            material = "STONE";
        }
        if (material.contains(":")) {
            material = material.split(":", 2)[1];
        }
        String path = material.toLowerCase(Locale.ROOT).replace(' ', '_');
        if (path.startsWith("textures/")) {
            return "minecraft:" + normalizeAssetPath(path, "textures/block/stone.png");
        }
        if (path.endsWith(".png")) {
            return "minecraft:" + normalizeAssetPath(path, "textures/block/stone.png");
        }
        return "minecraft:textures/block/" + path + ".png";
    }

    private String normalizeBackgroundValue(String value) {
        String background = value == null ? "" : value.trim();
        if (background.isBlank()) {
            return "minecraft:textures/gui/advancements/backgrounds/stone.png";
        }
        if (!background.contains(":") && !background.contains("/") && !background.endsWith(".png")) {
            return materialToBackgroundTexture(background);
        }
        if (background.startsWith("minecraft:") && !background.substring("minecraft:".length()).contains("/") && !background.endsWith(".png")) {
            return materialToBackgroundTexture(background);
        }
        if (background.contains(":")) {
            String[] parts = background.split(":", 2);
            return parts[0] + ":" + normalizeAssetPath(parts[1], "textures/gui/advancements/backgrounds/stone.png");
        }
        return "minecraft:" + normalizeAssetPath(background, "textures/gui/advancements/backgrounds/stone.png");
    }

    private MinecraftGameAssets getGameAssets() {
        if (RemotelyClient.INSTANCE != null && RemotelyClient.INSTANCE.getHost() != null) {
            MinecraftGameAssets gameAssets = RemotelyClient.INSTANCE.getHost().getGameAssets();
            if (gameAssets != null) {
                return gameAssets;
            }
        }
        return MinecraftGameAssets.EMPTY;
    }

    private MinecraftAssetReference assetReference(String value, String fallbackPath) {
        String asset = value == null ? "" : value.trim();
        if (asset.isBlank()) {
            return MinecraftAssetReference.of("minecraft", fallbackPath);
        }
        if (asset.contains(":")) {
            String[] parts = asset.split(":", 2);
            return MinecraftAssetReference.of(parts[0], normalizeAssetPath(parts[1], fallbackPath));
        }
        return MinecraftAssetReference.of("minecraft", normalizeAssetPath(asset, fallbackPath));
    }

    private String normalizeAssetPath(String path, String fallbackPath) {
        if (path == null || path.isBlank()) {
            return fallbackPath;
        }
        String clean = path.replace('\\', '/');
        if (clean.startsWith("gui/") || clean.startsWith("block/")) {
            clean = "textures/" + clean;
        } else if (!clean.startsWith("textures/")) {
            clean = "textures/gui/advancements/backgrounds/" + clean;
        }
        if (!clean.endsWith(".png")) {
            clean += ".png";
        }
        return clean;
    }

    private boolean hidden(JsonObject node) {
        JsonObject display = object(node, "display");
        return display.has("hidden") && display.get("hidden").getAsBoolean();
    }

    private String title(String id, JsonObject node) {
        JsonObject display = object(node, "display");
        String title = text(display, "title");
        return title.isBlank() ? id : title;
    }

    private JsonObject object(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) {
            value.add(key, new JsonObject());
        }
        return value.getAsJsonObject(key);
    }

    private JsonObject optionalObject(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : null;
    }

    private void removeIfEmpty(JsonObject value, String key) {
        JsonObject object = optionalObject(value, key);
        if (object != null && object.isEmpty()) {
            value.remove(key);
        }
    }

    private String text(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

}
