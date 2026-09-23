package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.*;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborationDocuments;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.ReSyncResourceCreator;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioResourceRenameAware;
import redxax.oxy.remotely.flow.ui.studio.StudioSaveProvider;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rebase.ui.widgets.editor.TextAreaWidget;
import restudio.rescreen.game.MinecraftAssetReference;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.game.MinecraftGameItems;
import restudio.rescreen.game.tooltip.MinecraftTextComponents;
import restudio.rescreen.game.tooltip.MinecraftTooltip;
import restudio.rescreen.game.tooltip.MinecraftTooltipLine;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.game.MinecraftRenderItem;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.rescreen.layout.FreeLayout;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.CompactBindingWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;


import static restudio.rescreen.config.Config.desktopMode;

public class GuiDesignerScreen extends StudioScreen implements DesktopWindowBehaviorProvider, ReSyncCollaborativeView, StudioCloseHandledScreen, StudioResourceRenameAware, CollaborativeSlotView, StudioSaveProvider {
    private static final int GRID_COLUMNS = 9;
    private static final int PANEL_PADDING = 8;
    private static final int MIN_SLOT_SIZE = 16;
    private static final int MAX_SLOT_SIZE = 26;
    private static final int SLOT_BASE_SIZE = 18;
    private static final int GUI_TEXTURE_WIDTH = 176;
    private static final int GUI_SIDE_MARGIN = 7;
    private static final OptionCatalogLoader.Profile CATALOGS = OptionCatalogLoader.profile("server:minecraft:material");
    private static final int GUI_TOP_MARGIN = 17;
    private static final int GUI_TITLE_X = 8;
    private static final int GUI_TITLE_Y = 6;
    private static final int GUI_PLAYER_INV_OFFSET = 14;
    private static final int GUI_HOTBAR_OFFSET = 72;
    private static final int GUI_PLAYER_INV_HEIGHT = 96;
    private static final int GUI_BOTTOM_TEXTURE_Y = 126;
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    private static final int PLAYER_INVENTORY_ROWS = 4;
    private static final int TITLE_COLOR = 0xFF404040;
    private static final String MATERIAL_OPTIONS_SOURCE = "server:minecraft:material";
    private static final Set<GuiDesignerScreen> OPEN_SCREENS = BrowserSafeState.set();
    private static final List<String> ACTION_MODE_OPTIONS = List.of("None", "Run Flow", "Run Function", "Run Command", "Menu");

    private static final List<String> FALLBACK_MATERIAL_OPTIONS = List.of(
        "STONE", "COBBLESTONE", "OAK_PLANKS", "OAK_LOG", "GLASS", "GLASS_PANE",
        "GRAY_STAINED_GLASS_PANE", "WHITE_STAINED_GLASS_PANE", "BLACK_STAINED_GLASS_PANE",
        "RED_STAINED_GLASS_PANE", "GREEN_STAINED_GLASS_PANE", "BLUE_STAINED_GLASS_PANE",
        "BARRIER", "CHEST", "ENDER_CHEST", "ANVIL", "BOOK", "PAPER", "MAP",
        "COMPASS", "CLOCK", "DIAMOND", "EMERALD", "GOLD_INGOT", "IRON_INGOT",
        "NETHERITE_INGOT", "REDSTONE", "AMETHYST_SHARD", "ENDER_PEARL",
        "TOTEM_OF_UNDYING", "PLAYER_HEAD", "NAME_TAG"
    );

    private enum GuiActionMode {
        NONE,
        FLOWS,
        FUNCTIONS,
        MENUS,
        COMMAND;

        private static GuiActionMode fromIndex(int index) {
            GuiActionMode[] modes = values();
            return modes[Math.clamp(index, 0, modes.length - 1)];
        }
    }

    private GuiDefinition gui;
    private final VersionedEditorDraft<GuiDefinition> guiDraft;
    private final String serverId;
    private final Object parent;
    private final ReSyncStudioPanelState panelState = new ReSyncStudioPanelState().padding(6);
    private final boolean forceSuperScreen;
    private final boolean animateTopHeader;

    @Override
    public void resourceRenamed(String type, String oldId, String newId) {
        if (guiDraft.defer(() -> resourceRenamed(type, oldId, newId))) {
            return;
        }
        guiDraft.markMutation();
        if (oldId.equals(gui.getId())) {
            ReSyncResourceType.GUI.applyRename(gui, newId);
        }
    }

    private Container gridContainer;
    private StudioPanel inspectorStudioPanel;
    private SidePanel inspectorPanel;
    private TooltipOverlayWidget tooltipOverlay;
    private ItemSelectorWidget materialSelector;
    private ScrollSelectorWidget actionTypeSelector;
    private ItemSelectorWidget flowSelector;
    private ItemSelectorWidget guiSelector;
    private TextInputWidget commandInput;
    private CompactBindingWidget actionBinding;

    private final Map<Integer, SlotButton> slotButtons = new HashMap<>();
    private final Map<Integer, GuiElement> slotElements = new HashMap<>();
    private final Map<String, MinecraftAssetReference> materialTextureReferences = new HashMap<>();
    private final Set<Integer> dragPreviewSlots = new HashSet<>();

    private ToggleWidget placeToggle;
    private ToggleWidget extendInventoryToggle;
    private final List<AnimatedWidget> inspectorDynamicWidgets = new ArrayList<>();
    private boolean placeMode;
    private boolean draggingPlacement;
    private GuiElement dragResizeElement;
    private SlotInteractionGrid.Stroke placementStroke;
    private int pendingResizeSlot = -1;
    private GuiElement pendingResizeElement;
    private GuiElement selectedElement;
    private GuiElement lastInspectorElement;
    private GuiActionMode inspectorActionMode = GuiActionMode.FLOWS;
    private boolean preserveInspectorActionMode;
    private SlotButton hoveredSlotButton;
    private Visual placementTemplate = new Visual("PAPER", "Item");

    private TextInputWidget guiTitleInput;
    private DropDownWidget<Integer> guiRowsSelect;

    private int gridOriginX;
    private int gridOriginY;
    private int guiBackgroundX;
    private int guiBackgroundY;
    private int guiBackgroundWidth;
    private int guiBackgroundHeight;
    private int playerInventoryOriginY;
    private int hotbarOriginY;
    private int highlightOriginX;
    private int highlightOriginY;
    private final int highlightAnimationScope = SlotInteractionGrid.animationScope();
    private int highlightPreviewNonce;
    private int highlightSelectionNonce;
    private final SlotCollaborationAuthority slotCollaboration = new SlotCollaborationAuthority();
    private float guiScale = 1f;
    private int slotSize;
    private int lastWidth = -1;
    private int lastHeight = -1;
    private int lastInspectorWidth = -1;
    private int lastStudioLeftWidth = -1;
    private boolean closingRequested;
    private boolean closeCompleted;
    private boolean studioCloseNotified;
    private Runnable studioCloseHandler;
    private final History<GuiSnapshot> history = history(this::createSnapshot, this::restoreSnapshot);
    private final HistoryRebaseCoordinator<GuiSnapshot> historyRebase;
    private volatile long collaborationLifecycle = 1L;

    @Override
    public JsonObject collaborationDocument() {
        return ReSyncCollaborationDocuments.from(gui);
    }

    @Override
    public long collaborationLifecycle() {
        return collaborationLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return guiDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return guiDraft.requestProjection(ReSyncResourceType.GUI.typeId(), ReSyncResourceType.GUI.extractId(gui),
            this::collaborationSource, projection -> completion.accept(collaborationSnapshot(lifecycle, projection)));
    }

    private GuiDefinition collaborationSource() {
        GuiDefinition source = new GuiDefinition(gui.getId(), gui.getTitle(), gui.getRows());
        source.setEnabled(gui.isEnabled());
        source.setExtendToPlayerInventory(gui.isExtendToPlayerInventory());
        List<GuiElement> elements = new ArrayList<>();
        if (gui.getElements() != null) {
            for (GuiElement element : gui.getElements()) {
                elements.add(element != null ? element.copy() : null);
            }
        }
        source.setElements(elements);
        return source;
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
        if (failure == null && !collaborationLifecycleEquals(lifecycle)) {
            document = null;
            failure = new IllegalStateException("Collaboration Snapshot Expired");
        }
        return new CollaborationDocumentSnapshot(this, lifecycle, projection.editVersion(), document, failure);
    }

    private boolean collaborationLifecycleEquals(long lifecycle) {
        return collaborationLifecycle == lifecycle;
    }

    @Override
    public boolean applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                              Consumer<CollaborationDocumentApplyResult> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return guiDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (gui == null) {
                throw new IllegalStateException("GUI Is Unavailable");
            }
            if (ReSyncCollaborationDocuments.gui(document) == null) {
                throw new IllegalArgumentException("Collaboration Document Is Invalid");
            }
            applyCollaborationDocument(document, patches);
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (guiDraft.defer(() -> applyCollaborationDocument(document, patches))) {
            return;
        }
        guiDraft.markMutation();
        GuiDefinition incoming = ReSyncCollaborationDocuments.gui(document);
        if (incoming == null) {
            return;
        }
        int selectedIndex = selectedElement != null ? gui.getElements().indexOf(selectedElement) : -1;
        ReSyncCollaborationDocuments.copy(gui, incoming);
        selectedElement = selectedIndex >= 0 && selectedIndex < gui.getElements().size() ? gui.getElements().get(selectedIndex) : null;
        if (guiTitleInput != null && !guiTitleInput.isFocused()) {
            guiTitleInput.setText(gui.getTitle() != null ? gui.getTitle() : "");
        }
        if (guiRowsSelect != null) {
            guiRowsSelect.setSelectedItem(Math.clamp(gui.getRows(), 1, 6));
        }
        if (extendInventoryToggle != null) {
            extendInventoryToggle.setValue(gui.isExtendToPlayerInventory());
        }
        rebuildGrid();
        buildInspectorPanel();
        updateLayout(true);
    }

    @Override
    public void rebaseCollaborationHistory(List<WorkspacePatch<JsonElement>> patches) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        String guiId = gui.getId();
        boolean guiEnabled = gui.isEnabled();
        historyRebase.request(patches, copiedPatches -> snapshot -> {
            GuiDefinition historic = new GuiDefinition(guiId, snapshot.title, snapshot.rows);
            historic.setEnabled(guiEnabled);
            historic.setRows(snapshot.rows);
            historic.setExtendToPlayerInventory(snapshot.extendToPlayerInventory);
            historic.setElements(snapshot.elements);
            JsonObject document = gson.fromJson(gson.toJson(ReSyncCollaborationDocuments.from(historic)), JsonObject.class);
            if (document == null) {
                document = new JsonObject();
            }
            FlowWorkspaceDocument.apply(document, copiedPatches);
            GuiDefinition rebased = ReSyncCollaborationDocuments.gui(document);
            JsonObject serialized = gson.fromJson(gson.toJson(ReSyncCollaborationDocuments.from(rebased)), JsonObject.class);
            GuiDefinition stable = ReSyncCollaborationDocuments.gui(serialized);
            return new GuiSnapshot(stable.getTitle(), stable.getRows(), stable.isExtendToPlayerInventory(),
                stable.getElements(), snapshot.selectedIndex,
                snapshot.placementTemplate != null ? snapshot.placementTemplate.copy() : null);
        });
    }

    private static class GuiSnapshot {
        private final String title;
        private final int rows;
        private final boolean extendToPlayerInventory;
        private final List<GuiElement> elements;
        private final int selectedIndex;
        private final Visual placementTemplate;

        private GuiSnapshot(String title, int rows, boolean extendToPlayerInventory, List<GuiElement> elements, int selectedIndex, Visual placementTemplate) {
            this.title = title;
            this.rows = rows;
            this.extendToPlayerInventory = extendToPlayerInventory;
            this.elements = elements;
            this.selectedIndex = selectedIndex;
            this.placementTemplate = placementTemplate;
        }
    }

    public GuiDesignerScreen(GuiDefinition gui, String serverId, Object parent) {
        this(gui, serverId, parent, !(parent instanceof Screen));
    }

    public GuiDesignerScreen(GuiDefinition gui, String serverId, Object parent, boolean forceSuperScreen) {
        this(gui, serverId, parent, forceSuperScreen, false);
    }

    public GuiDesignerScreen(GuiDefinition gui, String serverId, Object parent, boolean forceSuperScreen, boolean animateTopHeader) {
        super();
        this.gui = gui;
        this.serverId = serverId;
        this.parent = parent;
        this.forceSuperScreen = forceSuperScreen;
        this.animateTopHeader = animateTopHeader;
        this.autoResizeContainers = false;
        OPEN_SCREENS.add(this);
        ensureGuiDefaults();
        this.guiDraft = new VersionedEditorDraft<>(this.gui, FlowSerializer::serializeGui, FlowSerializer::deserializeGui,
            this::rebindGui, this::failSnapshot, () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        this.historyRebase = new HistoryRebaseCoordinator<>(history,
            () -> serverId + ":" + gui.getId(), () -> collaborationLifecycle,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
    }

    public static void refreshCatalogForServer(String serverId) {
        for (GuiDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                screen.refreshMaterialSelector();
                if (screen.actionBinding != null) {
                    screen.actionBinding.refresh();
                }
            }
        }
    }

    public static void refreshFlowBindingsForServer(String serverId) {
        refreshFlowBindingsForServer(serverId, null);
    }

    public static boolean hasOpenScreenForServer(String serverId) {
        for (GuiDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasFlowBindingForServer(String serverId, String flowId) {
        for (GuiDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId) && screen.hasFlowBinding(flowId)) {
                return true;
            }
        }
        return false;
    }

    public static void refreshFlowBindingsForServer(String serverId, String flowId) {
        for (GuiDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null
                && serverId != null
                && serverId.equals(screen.serverId)
                && screen.actionBinding != null
                && (flowId == null || screen.actionBinding.referencesTarget(flowId))) {
                screen.actionBinding.refresh();
            }
        }
    }

    private boolean hasFlowBinding(String flowId) {
        return actionBinding != null && actionBinding.referencesTarget(flowId);
    }

    public String getDesktopAppId() {
        return "gui-designer";
    }

    public String getDesktopAppTitle() {
        return "GUI Designer";
    }

    public String getDesktopAppIconPath() {
        return "slot.png";
    }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() {
        return DesktopWindowBehavior.SINGLETON;
    }

    @Override
    public boolean shouldForceSuperScreen() {
        return desktopMode && forceSuperScreen;
    }

    @Override
    public void init() {
        super.init();
        closingRequested = false;
        closeCompleted = false;
        studioCloseNotified = false;
        CATALOGS.preload(serverId);
        buildHeader();
        if (animateTopHeader) {
            startTopHeaderOpeningAnimation();
        }
        buildContainers();
        buildInspectorPanel();
        rebuildGrid();
        updateLayout(true);
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


    @Override
    public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
        super.renderHandler(context, mouseX, mouseY, delta);
        if (inspectorStudioPanel != null && inspectorPanel != null && (inspectorPanel.isVisible() || inspectorPanel.getAnimatedWidth() > 1f)) {
            renderStudioPanel(inspectorStudioPanel, context, mouseX, mouseY, delta);
        }
        drawGuiSlotTooltip(context, mouseX, mouseY);
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        historyRebase.drain();
        guiDraft.drain();
        updateTopHeaderAnimation();
        updateLayout(false);
        updateCloseAnimation();
        hoveredSlotButton = null;
        updateHoveredSlot(mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(IDrawContext context, int mouseX, int mouseY, float delta) {
        if (forceSuperScreen) {
            MinecraftScreenDarkening.renderContainer(context, width, height);
        } else {
            super.renderBackground(context, mouseX, mouseY, delta);
        }
        renderGuiPreview(context);
    }

    private void requestClose() {
        if (closeCompleted) {
            return;
        }
        if (!closingRequested) {
            closingRequested = true;
            if (animateTopHeader) {
                startTopHeaderClosingAnimation();
            }
            notifyStudioCloseStarted();
            if (inspectorPanel != null) {
                inspectorPanel.hide();
            }
        }
        updateCloseAnimation();
    }

    private void updateCloseAnimation() {
        if (!closingRequested || closeCompleted) {
            return;
        }
        if ((inspectorPanel == null || inspectorPanel.getAnimatedWidth() <= 1f) && (!animateTopHeader || isTopHeaderAnimationFinished())) {
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
        historyRebase.close();
        guiDraft.close();
        OPEN_SCREENS.remove(this);
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
        guiDraft.close();
        OPEN_SCREENS.remove(this);
        super.removed();
    }

    private void renderGuiPreview(IDrawContext context) {
        if (guiBackgroundWidth <= 0 || guiBackgroundHeight <= 0) {
            return;
        }
        MinecraftGameAssets gameAssets = getGameAssets();
        MinecraftAssetReference textureReference = gameAssets.containerTexture("generic_54.png");
        if (!gameAssets.exists(textureReference)) {
            return;
        }
        int rows = Math.max(1, gui.getRows());
        int topHeight = GUI_TOP_MARGIN + rows * SLOT_BASE_SIZE;
        int topHeightScaled = Math.round(topHeight * guiScale);
        Identifier textureId = gameAssets.getImageId(textureReference);
        drawGuiTexture(context, gameAssets, textureReference, textureId, guiBackgroundX, guiBackgroundY, guiBackgroundWidth, topHeightScaled, 0, 0, GUI_TEXTURE_WIDTH, topHeight);
        int bottomY = guiBackgroundY + topHeightScaled;
        int bottomHeightScaled = Math.round(GUI_PLAYER_INV_HEIGHT * guiScale);
        drawGuiTexture(context, gameAssets, textureReference, textureId, guiBackgroundX, bottomY, guiBackgroundWidth, bottomHeightScaled, 0, GUI_BOTTOM_TEXTURE_Y, GUI_TEXTURE_WIDTH, GUI_PLAYER_INV_HEIGHT);

        String title = gui.getTitle() != null && !gui.getTitle().isBlank() ? gui.getTitle() : gui.getId();
        if (title != null && !title.isBlank()) {
            int titleX = guiBackgroundX + Math.round(GUI_TITLE_X * guiScale);
            int titleY = guiBackgroundY + Math.round(GUI_TITLE_Y * guiScale);
            context.drawText(title, titleX, titleY, TITLE_COLOR, false);
        }
        int invLabelX = guiBackgroundX + Math.round(GUI_TITLE_X * guiScale);
        int invLabelY = guiBackgroundY + Math.round((GUI_TOP_MARGIN + rows * SLOT_BASE_SIZE + 3) * guiScale);
        context.drawText("Inventory", invLabelX, invLabelY, TITLE_COLOR, false);
        renderGuiElements(context);
        renderGuiHighlights(context);
    }

    private void renderGuiElements(IDrawContext context) {
        if (gui.getElements() == null || slotSize <= 0) {
            return;
        }
        for (GuiElement element : gui.getElements()) {
            if (element == null || element.getSlots() == null) {
                continue;
            }
            for (Integer slot : element.getSlots()) {
                if (slot == null) {
                    continue;
                }
                SlotButton button = slotButtons.get(slot);
                if (button != null) {
                    drawGuiElementIcon(context, element, button.getX(), button.getY(), button.getWidth(), button.getHeight());
                }
            }
        }
    }

    private void drawGuiElementIcon(IDrawContext context, GuiElement element, int x, int y, int width, int height) {
        if (element == null) {
            return;
        }
        MinecraftRenderItem renderItem = toRenderItem(element.getVisual());
        int padding = Math.max(1, Math.round(guiScale));
        int iconSize = Math.max(10, Math.min(width, height) - padding * 2);
        int iconX = x + (width - iconSize) / 2;
        int iconY = y + (height - iconSize) / 2;
        if (renderItem != null) {
            context.drawItem(renderItem, iconX, iconY, 0);
            return;
        }
        MinecraftGameAssets gameAssets = getGameAssets();
        MinecraftAssetReference reference = resolveMaterialTexture(element.getVisual());
        int sourceWidth = iconSize;
        int sourceHeight = iconSize;
        if (!MinecraftUiPreviewRenderer.drawAssetRegion(context, gameAssets, reference, iconX, iconY, iconSize, iconSize, 0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight)) {
            MinecraftUiPreviewRenderer.drawImage(context, gameAssets.getImageId(reference), iconX, iconY, iconSize, iconSize);
        }
    }

    private void renderGuiHighlights(IDrawContext context) {
        int previewColor = ThemeManager.getAccent("calm").getAccentColor();
        int selectedColor = ThemeManager.getDefaultAccent().getAccentColor();
        List<SlotInteractionGrid.SlotRect> rects = new ArrayList<>();
        Set<Integer> selectedSlots = new HashSet<>();
        for (SlotButton button : slotButtons.values()) {
            GuiElement element = slotElements.get(button.slot);
            SlotInteractionGrid.SlotRect rect = new SlotInteractionGrid.SlotRect(button.slot, button.getX(), button.getY(), Math.min(button.getWidth(), button.getHeight()));
            rects.add(rect);
            if (!slotCollaboration.isFollowing() && element != null && element == selectedElement) {
                selectedSlots.add(button.slot);
            }
        }
        SlotInteractionGrid.drawCollaborativeHighlights(context, rects, dragPreviewSlots, selectedSlots, previewColor, selectedColor,
            slotCollaboration, highlightAnimationScope, "gui_slot", highlightOriginX, highlightOriginY);
    }

    @Override
    public JsonArray collaborationSlots() {
        JsonArray slots = new JsonArray();
        if (!slotCollaboration.isFollowing() && selectedElement != null && selectedElement.getSlots() != null) {
            selectedElement.getSlots().stream().filter(slot -> slot != null).sorted().forEach(slots::add);
        }
        return slots;
    }

    @Override
    public void applyCollaborationSlots(List<RemoteSlotSelection> selections) {
        slotCollaboration.apply(selections);
        Integer followed = slotCollaboration.followedSlot();
        GuiElement element = followed != null ? slotElements.get(followed) : null;
        if (element != null && element != selectedElement) {
            selectElement(element);
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        updateLayout(true);
    }

    @Override
    protected void onSidePanelWidthChanged() {
        super.onSidePanelWidthChanged();
        updateLayout(false);
    }


    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (dispatchSidePanelMouseClicked(event)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();
        ReMouseButton button = event.button();
        if (!isAnyPopupOpen() && (button == ReMouseButton.LEFT || button == ReMouseButton.RIGHT)) {
            int slot = getSlotAt((int) mouseX, (int) mouseY);
            if (slot >= 0) {
                setHighlightOrigin((int) mouseX, (int) mouseY);
                handleSlotClick(slot, mouseButtonCode(event));
                setFocusedWidget(null);
                return true;
            }
            if (button == ReMouseButton.LEFT && gridContainer != null && gridContainer.isMouseOver(mouseX, mouseY)) {
                selectElement(null);
                setFocusedWidget(null);
                return true;
            }
        }
        return super.mouseClicked(event);
    }


    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (dispatchSidePanelMouseDragged(event)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();
        ReMouseButton button = event.button();
        if (draggingPlacement && button == ReMouseButton.LEFT) {
            if (placementStroke != null) {
                placementStroke.moveTo((int) mouseX, (int) mouseY);
                updateDragPreview();
            }
            return true;
        }
        if (placeMode && pendingResizeSlot >= 0 && button == ReMouseButton.LEFT) {
            startPlacementDrag(pendingResizeSlot, pendingResizeElement);
            if (placementStroke != null) {
                placementStroke.moveTo((int) mouseX, (int) mouseY);
                updateDragPreview();
            }
            return true;
        }
        return super.mouseDragged(event);
    }


    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (dispatchSidePanelMouseReleased(event)) {
            return true;
        }
        double mouseX = event.x();
        double mouseY = event.y();
        ReMouseButton button = event.button();
        if (draggingPlacement && button == ReMouseButton.LEFT) {
            finishPlacementDrag();
            return true;
        }
        if (pendingResizeSlot >= 0 && button == ReMouseButton.LEFT) {
            clearPendingResize();
            return true;
        }
        return super.mouseReleased(event);
    }


    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (handleStudioSaveShortcut(event)) {
            return true;
        }
        if (handleStudioHistoryShortcut(event)) {
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        if (event.key() == ReKey.ESCAPE) {
            requestClose();
            return true;
        }
        if (selectedElement != null && (event.key() == ReKey.DELETE || event.key() == ReKey.BACKSPACE)) {
            if (!isAnyPopupOpen()) {
                if (!isGuiKeyboardInputFocused()) {
                    removeElement(selectedElement);
                    return true;
                }
            }
        }
        return false;
    }

    private int mouseButtonCode(ReMouseEvent event) {
        ReMouseButton button = event.button();
        return switch (button) {
            case LEFT -> ReMouseButton.LEFT.code();
            case RIGHT -> ReMouseButton.RIGHT.code();
            case MIDDLE -> ReMouseButton.MIDDLE.code();
            default -> event.nativeButton();
        };
    }

    private boolean isGuiKeyboardInputFocused() {
        return getFocusedWidget() instanceof TextInputWidget
                || getFocusedWidget() instanceof TextAreaWidget
                || getFocusedWidget() instanceof ItemSelectorWidget;
    }

    private void buildHeader() {
        header().reset();
        if (shouldShowBackButton()) {
            header().addRight("close.png", this::requestClose, "Back");
        }
        header().addRight("save.png", this::saveGui, "Save GUI");
        placeToggle = new ToggleWidget.Builder()
            .label("Place")
            .size(70, 18)
            .toggled(placeMode)
            .hint("Place")
            .onChange(this::setPlaceMode)
            .build();
        header().addLeft(placeToggle);
        header().build();
    }

    private boolean shouldShowBackButton() {
        return !desktopMode || shouldForceSuperScreen();
    }

    private void buildContainers() {
        gridContainer = createContainer("gui_grid", 0, 0, 200, 200);
        gridContainer.layout(new FreeLayout()).columns(1).padding(0).scrolling(false).enableSelecting(false).backgroundDrawing(false);
        addDrawableChild(gridContainer);

        tooltipOverlay = new TooltipOverlayWidget();
        tooltipOverlay.setLayer(1000);
        addDrawableChild(tooltipOverlay);

        inspectorStudioPanel = rightStudioPanel("gui_inspector")
            .show();
        inspectorPanel = inspectorStudioPanel.sidePanel();
        inspectorStudioPanel.padding(panelState.padding());
    }

    private void buildInspectorPanel() {
        if (inspectorPanel == null) {
            return;
        }
        Container container = inspectorPanel.container();
        float scrollOffset = container.getScrollOffset();
        ensureInspectorBase(container);
        rebuildInspectorItemSection(container);
        container.updateWidgetPositions();
        container.setTargetScrollOffset(scrollOffset);
    }

    private void ensureInspectorBase(Container container) {
        if (guiTitleInput != null && guiRowsSelect != null && extendInventoryToggle != null) {
            return;
        }
        inspectorDynamicWidgets.clear();
        int rowWidth = inspectorRowWidth();
        guiTitleInput = new TextInputWidget.Builder()
            .text(gui.getTitle() != null ? gui.getTitle() : "")
            .placeholder("Title")
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(this::updateGuiTitle)
            .build();
        disableEntrance(guiTitleInput);
        container.addWidget(panelState.row("Title", guiTitleInput, rowWidth, guiPanelDescription("Title")));

        List<Integer> rowOptions = List.of(1, 2, 3, 4, 5, 6);
        guiRowsSelect = new DropDownWidget.Builder<>(rowOptions)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .selectedItem(Math.clamp(gui.getRows(), 1, 6))
            .displayFunction(rows -> rows + (rows == 1 ? " row" : " rows"))
            .onSelectionChanged(this::updateRows)
            .entranceAnimation(false)
            .build();
        disableEntrance(guiRowsSelect);
        container.addWidget(panelState.row("Rows", guiRowsSelect, rowWidth, guiPanelDescription("Rows")));

        extendInventoryToggle = new ToggleWidget.Builder()
            .label("Player Inventory")
            .size(rowWidth, 20)
            .toggled(gui.isExtendToPlayerInventory())
            .onChange(this::setExtendInventoryMode)
            .entranceAnimation(false)
            .build();
        disableEntrance(extendInventoryToggle);
        container.addWidget(panelState.row("Inventory", extendInventoryToggle, rowWidth, guiPanelDescription("Inventory")));
    }

    private void rebuildInspectorItemSection(Container container) {
        if (selectedElement == lastInspectorElement && !inspectorDynamicWidgets.isEmpty()) {
            if (materialSelector != null) {
                refreshMaterialSelector();
            }
            if (flowSelector != null) {
                refreshFlowSelector();
            }
            if (actionBinding != null) {
                actionBinding.refresh();
            }
            if (guiSelector != null) {
                refreshGuiSelector();
            }
            return;
        }
        lastInspectorElement = selectedElement;
        int dynamicStartIndex = Math.max(0, container.getWidgets().size() - inspectorDynamicWidgets.size());
        inspectorDynamicWidgets.clear();
        materialSelector = null;
        actionTypeSelector = null;
        flowSelector = null;
        guiSelector = null;
        commandInput = null;
        actionBinding = null;

        int rowWidth = inspectorRowWidth();
        if (selectedElement == null) {
            AnimatedButton hint = panelState.hint("Select Item", rowWidth);
            insertInspectorDynamic(container, hint);
            container.replaceWidgetsFromIndex(dynamicStartIndex, inspectorDynamicWidgets);
            return;
        }

        Visual visual = ensureVisual(selectedElement);
        if (!preserveInspectorActionMode) {
            inspectorActionMode = resolveActionMode(selectedElement);
        }

        TextInputWidget slotsInput = new TextInputWidget.Builder()
            .text(formatSlots(selectedElement))
            .placeholder("Slots")
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .active(false)
            .build();
        disableEntrance(slotsInput);
        AnimatedWidget slotsRow = panelState.row("Slots", slotsInput, rowWidth, guiPanelDescription("Slots"));
        insertInspectorDynamic(container, slotsRow);

        TextInputWidget nameInput = new TextInputWidget.Builder()
            .text(visual.getName() != null ? visual.getName() : "")
            .placeholder("Name")
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(text -> {
                updateVisualName(visual, text);
                applySlotState();
            })
            .build();
        disableEntrance(nameInput);
        AnimatedWidget nameRow = panelState.row("Name", nameInput, rowWidth, guiPanelDescription("Name"));
        insertInspectorDynamic(container, nameRow);

        materialSelector = new ItemSelectorWidget.Builder(this)
            .size(rowWidth, 140)
            .embedded(true)
            .dismissOnSelect(false)
            .emptyMessage("No items")
            .asyncItems(OptionCatalogSelector.refreshAction(serverId, MATERIAL_OPTIONS_SOURCE),
                OptionCatalogSelector.legacySource(serverId, MATERIAL_OPTIONS_SOURCE, this::materialOptions,
                    () -> selectedElement != null && selectedElement.getVisual() != null ? selectedElement.getVisual().getMaterial() : "",
                    this::applyMaterial, "No Items"))
            .build();
        disableEntrance(materialSelector);
        AnimatedWidget materialRow = panelState.row("Material", materialSelector, rowWidth, guiPanelDescription("Material"));
        materialRow.setHeight(156);
        insertInspectorDynamic(container, materialRow);

        buildActionEditor(container, rowWidth);

        TextInputWidget modelInput = new TextInputWidget.Builder()
            .text(visual.getModelData() != null ? String.valueOf(visual.getModelData()) : "")
            .placeholder("Model Data")
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(text -> updateModelData(visual, text))
            .build();
        disableEntrance(modelInput);
        AnimatedWidget modelRow = panelState.row("Model Data", modelInput, rowWidth, guiPanelDescription("Model Data"));
        insertInspectorDynamic(container, modelRow);

        String loreText = visual.getLore() != null ? String.join("\n", visual.getLore()) : "";
        TextAreaWidget loreInput = new TextAreaWidget.Builder()
            .text(loreText)
            .placeholder("Lore")
            .size(rowWidth, 70)
            .onChange(text -> updateLore(visual, text))
            .build();
        disableEntrance(loreInput);
        AnimatedWidget loreRow = panelState.row("Lore", loreInput, rowWidth, guiPanelDescription("Lore"));
        loreRow.setHeight(88);
        insertInspectorDynamic(container, loreRow);

        AnimatedButton removeButton = new AnimatedButton.Builder()
            .label("Remove Item")
            .size(rowWidth, 22)
            .accentType(ThemeManager.getAccent("danger"))
            .entranceAnimation(false)
            .onClick(() -> removeElement(selectedElement))
            .build();
        disableEntrance(removeButton);
        insertInspectorDynamic(container, removeButton);
        container.replaceWidgetsFromIndex(dynamicStartIndex, inspectorDynamicWidgets);
        if (materialSelector != null) {
            materialSelector.openEmbedded();
            refreshMaterialSelector();
        }
        if (flowSelector != null) {
            flowSelector.openEmbedded();
            refreshFlowSelector();
        }
        if (actionBinding != null) {
            actionBinding.refresh();
        }
        if (guiSelector != null) {
            guiSelector.openEmbedded();
            refreshGuiSelector();
        }
    }

    private void buildActionEditor(Container container, int rowWidth) {
        actionBinding = new CompactBindingWidget.Builder(
            this,
            ACTION_MODE_OPTIONS,
            this::actionBindingMode,
            this::applyActionBindingMode,
            this::actionBindingTargetOptions,
            this::actionBindingTarget,
            this::applyActionBindingTarget,
            this::actionBindingInputs,
            this::openSelectedActionTarget
        )
            .createAction("Create New", () -> inspectorActionMode == GuiActionMode.FLOWS || inspectorActionMode == GuiActionMode.FUNCTIONS || inspectorActionMode == GuiActionMode.MENUS, this::createActionBindingTarget)
            .animationKey("gui.action")
            .size(rowWidth, 18)
            .entranceAnimation(false)
            .build();
        disableEntrance(actionBinding);
        AnimatedWidget actionRow = panelState.row("Action", actionBinding, rowWidth, guiPanelDescription("Action"));
        insertInspectorDynamic(container, actionRow);
    }

    private String actionBindingMode() {
        return switch (inspectorActionMode) {
            case FLOWS -> "Run Flow";
            case FUNCTIONS -> "Run Function";
            case MENUS -> "Menu";
            case COMMAND -> "Run Command";
            default -> "None";
        };
    }

    private void applyActionBindingMode(String mode) {
        GuiActionMode next = switch (mode) {
            case "Run Flow", "Flow" -> GuiActionMode.FLOWS;
            case "Run Function", "Function" -> GuiActionMode.FUNCTIONS;
            case "Menu" -> GuiActionMode.MENUS;
            case "Run Command", "Command" -> GuiActionMode.COMMAND;
            default -> GuiActionMode.NONE;
        };
        if (selectedElement == null) {
            inspectorActionMode = next;
            return;
        }
        inspectorActionMode = next;
        clearInactiveActions(next);
        applySlotState();
    }

    private List<String> actionBindingTargetOptions() {
        return switch (inspectorActionMode) {
            case FLOWS -> guiFlowOptions();
            case FUNCTIONS -> functionOptions();
            case MENUS -> guiOptions();
            default -> List.of("none");
        };
    }

    private String actionBindingTarget() {
        if (selectedElement == null) {
            return "";
        }
        return switch (inspectorActionMode) {
            case FLOWS -> selectedElement.getFlowId() != null ? selectedElement.getFlowId() : "";
            case FUNCTIONS -> text(selectedElement.getAction(), "functionId");
            case MENUS -> selectedElement.getOpenGuiId() != null ? selectedElement.getOpenGuiId() : "";
            case COMMAND -> "Command";
            default -> "";
        };
    }

    private void applyActionBindingTarget(String value) {
        if (inspectorActionMode == GuiActionMode.FLOWS) {
            applyFlow(realBindingValue(value));
        } else if (inspectorActionMode == GuiActionMode.FUNCTIONS) {
            applyFunction(realBindingValue(value));
        } else if (inspectorActionMode == GuiActionMode.MENUS) {
            applyOpenGui(realBindingValue(value));
        }
    }

    private List<CompactBindingWidget.BindingInput> actionBindingInputs() {
        if (selectedElement == null) {
            return List.of();
        }
        if (inspectorActionMode == GuiActionMode.COMMAND) {
            return List.of(new CompactBindingWidget.BindingInput(
                "command",
                "Command",
                selectedElement.getCommand() != null ? selectedElement.getCommand() : "",
                FlowDataType.STRING.getColor(),
                null,
                this::applyCommand,
                CompactBindingWidget.InputKind.COMMAND
            ));
        }
        if (inspectorActionMode == GuiActionMode.FUNCTIONS) {
            return functionBindingInputs(selectedElement.getAction(), CompactBindingSupport.guiActionShape());
        }
        return List.of();
    }

    private void openSelectedActionTarget() {
        if (inspectorActionMode == GuiActionMode.FLOWS) {
            openSelectedFlow();
        } else if (inspectorActionMode == GuiActionMode.FUNCTIONS) {
            openSelectedFunction();
        } else if (inspectorActionMode == GuiActionMode.MENUS) {
            openSelectedMenu();
        }
    }

    private void createActionBindingTarget() {
        if (inspectorActionMode == GuiActionMode.FLOWS) {
            showCreateFlowTargetPopup();
        } else if (inspectorActionMode == GuiActionMode.FUNCTIONS) {
            showCreateFunctionTargetPopup();
        } else if (inspectorActionMode == GuiActionMode.MENUS) {
            showCreateGuiTargetPopup();
        }
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
                ItemOptionCatalog.formatOptionLabel(name),
                functionInputValue(call, input),
                input.getType() != null ? input.getType().getColor() : FlowDataType.ANY.getColor(),
                () -> functionInputOptions(input),
                value -> updateFunctionInput(call, input, value),
                input.getType() != null && FlowDataType.BOOLEAN.isAssignableFrom(input.getType()) ? CompactBindingWidget.InputKind.BOOLEAN : CompactBindingWidget.InputKind.TEXT,
                () -> CompactBindingSupport.functionInputChoices(serverId, input, functionInputOptions(input))
            ));
        }
        return inputs;
    }

    private FlowGraph selectedFunction(String id, CompactBindingSupport.FunctionShape shape) {
        return CompactBindingSupport.selectedFunction(serverId, id, shape);
    }

    private void normalizeBindingFunction(String functionId, CompactBindingSupport.FunctionShape shape) {
        CompactBindingSupport.initializeFunction(serverId, functionId, shape);
    }

    private String functionInputValue(JsonObject call, FlowGraph.FunctionParameter input) {
        if (call == null || input == null || input.getName() == null || input.getName().isBlank()) {
            return "";
        }
        JsonObject inputs = optionalObject(call, "inputs");
        String name = input.getName();
        if (inputs != null && inputs.has(name) && !inputs.get(name).isJsonNull()) {
            return inputs.get(name).getAsString();
        }
        if (input.getDefaultValue() != null && !input.getDefaultValue().isBlank()) {
            return input.getDefaultValue();
        }
        return CompactBindingSupport.functionInputContextDefault(input, "gui:click");
    }

    private List<String> functionInputOptions(FlowGraph.FunctionParameter input) {
        List<String> options = new ArrayList<>(CompactBindingSupport.functionInputOptions(input, "gui:click"));
        String optionsSource = input.getOptionsSource();
        if (optionsSource != null && !optionsSource.isBlank()) {
            for (String option : OptionCatalogCache.getInstance().getValues(serverId, optionsSource)) {
                if (option != null && !option.isBlank() && !options.contains(option)) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    private void updateFunctionInput(JsonObject call, FlowGraph.FunctionParameter input, String value) {
        if (call == null || input == null || input.getName() == null || input.getName().isBlank()) {
            return;
        }
        captureSnapshot();
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

    private JsonObject functionCall(GuiElement element) {
        JsonObject call = element.getAction();
        if (call == null) {
            call = new JsonObject();
            element.setAction(call);
        }
        if (!call.has("type")) {
            call.addProperty("type", "functionRef");
        }
        if (!call.has("functionId")) {
            call.addProperty("functionId", "");
        }
        return call;
    }

    private JsonObject object(JsonObject object, String key) {
        if (object == null) {
            return new JsonObject();
        }
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            object.add(key, new JsonObject());
        }
        return object.getAsJsonObject(key);
    }

    private JsonObject optionalObject(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private void removeIfEmpty(JsonObject object, String key) {
        JsonObject value = optionalObject(object, key);
        if (value != null && value.isEmpty()) {
            object.remove(key);
        }
    }

    private String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private String realBindingValue(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value) ? null : value;
    }

    private String guiPanelDescription(String label) {
        return switch (label) {
            case "Title" -> "Inventory title shown in the Minecraft menu header.\nKeep it short enough for vanilla clients.";
            case "Rows" -> "Chest row count.\nValid range: 1 to 6.\nEach row contains 9 slots.";
            case "Inventory" -> "Player inventory visibility.\nOn: show the player's inventory below the menu.\nOff: show only custom GUI slots.";
            case "Slots" -> "Slot indexes used by this element.\nSlot 0 is the top-left custom slot.\nMultiple slots make the same element occupy several positions.";
            case "Name" -> "Displayed item name.\nShown when the player hovers this GUI item.";
            case "Material" -> "Visual item material.\nAccepts a Minecraft material id or loaded custom content id.";
            case "Action" -> "Click action for this element.\nNone: visual only.\nFlow: run a flow.\nMenu: open another GUI.\nCommand: execute a command.";
            case "Model Data" -> "Custom model data value.\nUsed by resource packs to select an alternate item model.\nLeave empty for the default model.";
            case "Lore" -> "Item tooltip lines.\nOne line per lore row.\nTypical content: requirements, costs, or status text.";
            case "Flow" -> "Flow executed on click.\nReceives the click/player context from the GUI runtime.";
            case "Menu" -> "GUI opened on click.\nSupports pages, submenus, and navigation items.";
            case "Command" -> "Command executed on click.\nStore the executable command text only.";
            default -> "";
        };
    }

    private void refreshMaterialSelector() {
        if (materialSelector == null) {
            return;
        }
        if (selectedElement != null && selectedElement.getVisual() != null) {
            String current = selectedElement.getVisual().getMaterial();
            setSelectorSelection(materialSelector, OptionCatalogSelector.label(serverId, MATERIAL_OPTIONS_SOURCE, current));
        }
    }

    private void insertInspectorDynamic(Container container, AnimatedWidget widget) {
        ReSyncStudioPanelState.disableEntrance(widget);
        inspectorDynamicWidgets.add(widget);
    }

    private int inspectorRowWidth() {
        if (inspectorPanel == null) {
            return Math.max(ReSyncStudioPanelState.MIN_ROW_WIDTH, ReSyncStudioPanelState.DEFAULT_WIDTH - panelState.padding() * 2);
        }
        return inspectorStudioPanel != null ? inspectorStudioPanel.rowWidth() : Math.max(ReSyncStudioPanelState.MIN_ROW_WIDTH, inspectorPanel.getDesiredWidth() - panelState.padding() * 2);
    }

    private List<String> materialOptions() {
        List<String> values = OptionCatalogCache.getInstance().getValues(serverId, MATERIAL_OPTIONS_SOURCE);
        if (!values.isEmpty()) {
            return values;
        }
        OptionCatalogLoader.preload(serverId, MATERIAL_OPTIONS_SOURCE);
        return FALLBACK_MATERIAL_OPTIONS;
    }

    private List<String> guiFlowOptions() {
        return CompactBindingSupport.flowOptions(serverId);
    }

    private List<String> functionOptions() {
        return CompactBindingSupport.functionOptions(serverId);
    }

    private List<String> guiOptions() {
        List<String> options = new ArrayList<>();
        options.add("none");
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager != null && serverId != null) {
            List<String> guiIds = new ArrayList<>(flowManager.getGuisForServer(serverId).keySet());
            guiIds.sort(String.CASE_INSENSITIVE_ORDER);
            options.addAll(guiIds);
        }
        return options;
    }

    private void refreshFlowSelector() {
        if (flowSelector == null) {
            return;
        }
        flowSelector.clearItems();
        flowSelector.beginBatch();
        FlowManager flowManager = FlowManager.getInstance();
        try {
            flowSelector.addItem("none", () -> applyFlow(null));
            if (flowManager != null && serverId != null) {
                List<String> flowIds = new ArrayList<>(flowManager.getGraphsForServer(serverId, ReSyncResourceType.FLOW).keySet());
                flowIds.sort(String.CASE_INSENSITIVE_ORDER);
                for (String flowId : flowIds) {
                    String label = flowManager.getFlowName(serverId, flowId);
                    flowSelector.addItem(label, () -> applyFlow(flowId));
                }
                String selectedFlow = selectedElement != null ? selectedElement.getFlowId() : null;
                String selectedLabel = selectedFlow == null || selectedFlow.isBlank()
                    ? "none"
                    : flowManager.getFlowName(serverId, selectedFlow);
                setSelectorSelection(flowSelector, selectedLabel);
            }
        } finally {
            flowSelector.endBatch();
        }
    }

    private void refreshGuiSelector() {
        if (guiSelector == null) {
            return;
        }
        guiSelector.clearItems();
        guiSelector.beginBatch();
        FlowManager flowManager = FlowManager.getInstance();
        try {
            guiSelector.addItem("none", () -> applyOpenGui(null));
            if (flowManager != null && serverId != null) {
                List<String> guiIds = new ArrayList<>(flowManager.getGuisForServer(serverId).keySet());
                guiIds.sort(String.CASE_INSENSITIVE_ORDER);
                for (String guiId : guiIds) {
                    String label = flowManager.getGuiName(serverId, guiId);
                    guiSelector.addItem(label, () -> applyOpenGui(guiId));
                }
                String selectedGui = selectedElement != null ? selectedElement.getOpenGuiId() : null;
                String selectedLabel = selectedGui == null || selectedGui.isBlank()
                    ? "none"
                    : flowManager.getGuiName(serverId, selectedGui);
                setSelectorSelection(guiSelector, selectedLabel);
            }
        } finally {
            guiSelector.endBatch();
        }
    }

    private GuiActionMode resolveActionMode(GuiElement element) {
        if (element == null) {
            return GuiActionMode.NONE;
        }
        if (element.getOpenGuiId() != null && !element.getOpenGuiId().isBlank()) {
            return GuiActionMode.MENUS;
        }
        if (element.getCommand() != null && !element.getCommand().isBlank()) {
            return GuiActionMode.COMMAND;
        }
        if (element.getAction() != null && !text(element.getAction(), "functionId").isBlank()) {
            return GuiActionMode.FUNCTIONS;
        }
        if (element.getFlowId() != null && !element.getFlowId().isBlank()) {
            return GuiActionMode.FLOWS;
        }
        return GuiActionMode.NONE;
    }

    private void setActionMode(GuiActionMode mode) {
        if (mode == null || inspectorActionMode == mode) {
            return;
        }
        inspectorActionMode = mode;
        clearInactiveActions(mode);
        lastInspectorElement = null;
        preserveInspectorActionMode = true;
        buildInspectorPanel();
        preserveInspectorActionMode = false;
        updateLayout(true);
    }

    private void clearInactiveActions(GuiActionMode mode) {
        if (selectedElement == null) {
            return;
        }
        boolean changed = false;
        if (mode != GuiActionMode.FLOWS && selectedElement.getFlowId() != null && !selectedElement.getFlowId().isBlank()) {
            changed = true;
        }
        if (mode != GuiActionMode.MENUS && selectedElement.getOpenGuiId() != null && !selectedElement.getOpenGuiId().isBlank()) {
            changed = true;
        }
        if (mode != GuiActionMode.COMMAND && selectedElement.getCommand() != null && !selectedElement.getCommand().isBlank()) {
            changed = true;
        }
        if (mode != GuiActionMode.FUNCTIONS && selectedElement.getAction() != null && !selectedElement.getAction().isEmpty()) {
            changed = true;
        }
        if (!changed) {
            return;
        }
        captureSnapshot();
        if (mode != GuiActionMode.FLOWS) {
            selectedElement.setFlowId(null);
        }
        if (mode != GuiActionMode.MENUS) {
            selectedElement.setOpenGuiId(null);
        }
        if (mode != GuiActionMode.COMMAND) {
            selectedElement.setCommand(null);
        }
        if (mode != GuiActionMode.FUNCTIONS) {
            selectedElement.setAction(null);
        }
    }

    private void applyMaterial(String material) {
        if (selectedElement == null) {
            placementTemplate.setMaterial(material);
            return;
        }
        Visual visual = ensureVisual(selectedElement);
        if ((material == null && visual.getMaterial() == null)
            || (material != null && material.equals(visual.getMaterial()))) {
            return;
        }
        captureSnapshot();
        visual.setMaterial(material);
        placementTemplate = visual.copy();
        applySlotState();
    }

    private void applyFlow(String flowId) {
        if (selectedElement == null) {
            return;
        }
        if ((flowId == null && (selectedElement.getFlowId() == null || selectedElement.getFlowId().isBlank()))
            || (flowId != null && flowId.equals(selectedElement.getFlowId()))) {
            return;
        }
        captureSnapshot();
        selectedElement.setFlowId(flowId);
        if (flowId != null && !flowId.isBlank()) {
            inspectorActionMode = GuiActionMode.FLOWS;
            selectedElement.setOpenGuiId(null);
            selectedElement.setCommand(null);
            selectedElement.setAction(null);
            setSelectorSelection(guiSelector, "none");
            if (commandInput != null) {
                commandInput.setText("");
            }
        }
        String label = flowId == null || flowId.isBlank() ? "none" : formatFlowLabel(flowId);
        setSelectorSelection(flowSelector, label);
        if (actionBinding != null) {
            actionBinding.refresh();
        }
    }

    private void applyOpenGui(String guiId) {
        if (selectedElement == null) {
            return;
        }
        if ((guiId == null && (selectedElement.getOpenGuiId() == null || selectedElement.getOpenGuiId().isBlank()))
            || (guiId != null && guiId.equals(selectedElement.getOpenGuiId()))) {
            return;
        }
        captureSnapshot();
        selectedElement.setOpenGuiId(guiId);
        if (guiId != null && !guiId.isBlank()) {
            inspectorActionMode = GuiActionMode.MENUS;
            selectedElement.setFlowId(null);
            selectedElement.setCommand(null);
            selectedElement.setAction(null);
            setSelectorSelection(flowSelector, "none");
            if (commandInput != null) {
                commandInput.setText("");
            }
        }
        String label = guiId == null || guiId.isBlank() ? "none" : formatGuiLabel(guiId);
        setSelectorSelection(guiSelector, label);
        if (actionBinding != null) {
            actionBinding.refresh();
        }
    }

    private void applyCommand(String command) {
        if (selectedElement == null) {
            return;
        }
        String next = command != null && !command.isBlank() ? command.trim() : null;
        String current = selectedElement.getCommand();
        if ((current == null && next == null) || (current != null && current.equals(next))) {
            return;
        }
        captureSnapshot();
        selectedElement.setCommand(next);
        if (next != null) {
            inspectorActionMode = GuiActionMode.COMMAND;
            selectedElement.setFlowId(null);
            selectedElement.setOpenGuiId(null);
            selectedElement.setAction(null);
            setSelectorSelection(flowSelector, "none");
            setSelectorSelection(guiSelector, "none");
        }
        if (actionBinding != null) {
            actionBinding.refresh();
        }
    }

    private void applyFunction(String functionId) {
        if (selectedElement == null) {
            return;
        }
        String current = text(selectedElement.getAction(), "functionId");
        if ((functionId == null && current.isBlank()) || (functionId != null && functionId.equals(current))) {
            return;
        }
        captureSnapshot();
        if (functionId == null || functionId.isBlank()) {
            selectedElement.setAction(null);
        } else {
            JsonObject call = functionCall(selectedElement);
            call.addProperty("functionId", functionId);
            inspectorActionMode = GuiActionMode.FUNCTIONS;
            selectedElement.setFlowId(null);
            selectedElement.setOpenGuiId(null);
            selectedElement.setCommand(null);
            setSelectorSelection(flowSelector, "none");
            setSelectorSelection(guiSelector, "none");
        }
        if (actionBinding != null) {
            actionBinding.refresh();
        }
    }

    private void openSelectedFlow() {
        if (selectedElement == null) {
            return;
        }
        String flowId = selectedElement.getFlowId();
        if (flowId == null || flowId.isBlank()) {
            return;
        }
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager != null && serverId != null) {
            flowManager.openFlowEditor(serverId, null, flowId);
        }
    }

    private void openSelectedMenu() {
        if (selectedElement == null) {
            return;
        }
        String guiId = selectedElement.getOpenGuiId();
        if (guiId == null || guiId.isBlank()) {
            return;
        }
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager != null && serverId != null) {
            flowManager.openGuiDesigner(serverId, null, guiId, this);
        }
    }

    private void openSelectedFunction() {
        if (selectedElement == null) {
            return;
        }
        String functionId = text(selectedElement.getAction(), "functionId");
        if (functionId == null || functionId.isBlank()) {
            return;
        }
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager != null && serverId != null) {
            flowManager.openGraphEditor(serverId, null, ReSyncResourceType.FUNCTION, functionId);
        }
    }

    private void showCreateFlowTargetPopup() {
        ReSyncResourceCreator.showCreatePopup(this, serverId, ReSyncResourceDragPayload.FLOW, "", null, result -> {
            FlowManager flowManager = FlowManager.getInstance();
            applyFlow(result.id());
            refreshFlowSelector();
            if (actionBinding != null) {
                actionBinding.refresh();
            }
            if (flowManager != null) {
                flowManager.openFlowEditor(serverId, null, result.id());
            }
        });
    }

    private void showCreateFunctionTargetPopup() {
        ReSyncResourceCreator.showCreatePopup(this, serverId, ReSyncResourceDragPayload.FUNCTION, "", null, result -> {
            FlowManager flowManager = FlowManager.getInstance();
            normalizeBindingFunction(result.id(), CompactBindingSupport.guiActionShape());
            applyFunction(result.id());
            if (actionBinding != null) {
                actionBinding.refresh();
            }
            if (flowManager != null) {
                flowManager.openGraphEditor(serverId, null, ReSyncResourceType.FUNCTION, result.id());
            }
        });
    }

    private void showCreateGuiTargetPopup() {
        ReSyncResourceCreator.showCreatePopup(this, serverId, ReSyncResourceDragPayload.GUI, "", null, result -> {
            FlowManager flowManager = FlowManager.getInstance();
            applyOpenGui(result.id());
            refreshGuiSelector();
            if (actionBinding != null) {
                actionBinding.refresh();
            }
            if (flowManager != null) {
                flowManager.openGuiDesigner(serverId, null, result.id(), this);
            }
        });
    }

    private void rebuildGrid() {
        gridContainer.clearWidgets();
        slotButtons.clear();

        refreshSlotMappings();
        buildSlotButtons();
        applySlotState();
        positionGridWidgets();
    }

    private void refreshSlotMappings() {
        slotElements.clear();
        if (gui.getElements() == null) {
            return;
        }
        int maxSlot = getTotalSlots();
        for (GuiElement element : gui.getElements()) {
            if (element == null || element.getSlots() == null) {
                continue;
            }
            for (Integer slot : element.getSlots()) {
                if (slot == null || slot < 0 || slot >= maxSlot) {
                    continue;
                }
                slotElements.put(slot, element);
            }
        }
    }

    private void buildSlotButtons() {
        int totalSlots = getTotalSlots();
        for (int slot = 0; slot < totalSlots; slot++) {
            SlotButton button = new SlotButton(slot, MIN_SLOT_SIZE, this::handleSlotClick);
            button.setElement(slotElements.get(slot));
            gridContainer.addWidget(button);
            slotButtons.put(slot, button);
        }
    }

    private void applySlotState() {
        for (SlotButton button : slotButtons.values()) {
            GuiElement element = slotElements.get(button.slot);
            button.setElement(element);
        }
    }

    private void updateHoveredSlot(int mouseX, int mouseY) {
        if (draggingPlacement || isAnyPopupOpen()) {
            return;
        }
        for (SlotButton button : slotButtons.values()) {
            if (button.hasElement() && button.isMouseOver(mouseX, mouseY)) {
                hoveredSlotButton = button;
                return;
            }
        }
    }

    private void drawGuiSlotTooltip(IDrawContext context, int mouseX, int mouseY) {
        if (hoveredSlotButton == null || !hoveredSlotButton.hasElement()) {
            return;
        }
        GuiElement element = hoveredSlotButton.getElement();
        Visual visual = element.getVisual();
        if (visual == null) {
            return;
        }
        MinecraftTooltip fallback = buildVisualFallbackTooltip(visual);
        MinecraftRenderItem renderItem = hoveredSlotButton.getRenderItem();
        if (renderItem != null) {
            context.drawMinecraftItemTooltip(renderItem, fallback, mouseX, mouseY, getWidth(), getHeight());
            return;
        }
        context.drawMinecraftTooltip(fallback, mouseX, mouseY, getWidth(), getHeight());
    }

    private MinecraftTooltip buildVisualFallbackTooltip(Visual visual) {
        List<MinecraftTooltipLine> lines = new ArrayList<>();
        String title = visual.getName() != null && !visual.getName().isBlank() ? visual.getName() : formatMaterialLabel(visual.getMaterial());
        if (title == null || title.isBlank()) {
            title = "Item";
        }
        lines.add(new MinecraftTooltipLine(MinecraftTextComponents.fromValue(title)));
        if (visual.getLore() != null) {
            lines.addAll(MinecraftTextComponents.fromPlainLore(visual.getLore()));
        }
        if (shouldRenderAsTexture(visual) && visual.getMaterial() != null && !visual.getMaterial().isBlank()) {
            lines.add(new MinecraftTooltipLine(MinecraftTextComponents.fromValue(visual.getMaterial())));
        }
        return new MinecraftTooltip(lines);
    }

    private void handleSlotClick(int slot, int button) {
        slotCollaboration.markLocalInteraction();
        GuiElement element = slotElements.get(slot);
        if (element != null) {
            if (button == ReMouseButton.RIGHT.code()) {
                removeElementAtSlot(slot);
                return;
            }
            if (button == ReMouseButton.LEFT.code()) {
                if (placeMode) {
                    selectElement(element);
                    pendingResizeSlot = slot;
                    pendingResizeElement = element;
                    return;
                }
                selectElement(element);
            }
            return;
        }
        if (button != ReMouseButton.LEFT.code()) {
            return;
        }
        if (placeMode) {
            startPlacementDrag(slot, null);
            return;
        }
        selectElement(null);
    }

    private void removeElementAtSlot(int slot) {
        if (guiDraft.defer(() -> removeElementAtSlot(slot))) {
            return;
        }
        removeElement(slotElements.get(slot));
    }

    private void selectElement(GuiElement element) {
        clearPendingResize();
        highlightSelectionNonce++;
        selectedElement = element;
        if (selectedElement != null && selectedElement.getVisual() != null) {
            placementTemplate = selectedElement.getVisual().copy();
        }
        dragPreviewSlots.clear();
        applySlotState();
        buildInspectorPanel();
    }

    private void removeElement(GuiElement element) {
        if (element == null || gui.getElements() == null) {
            return;
        }
        captureSnapshot();
        gui.getElements().remove(element);
        if (selectedElement == element) {
            selectedElement = null;
        }
        rebuildGrid();
        buildInspectorPanel();
    }

    private void updateRows(int rows) {
        int clamped = Math.clamp(rows, 1, 6);
        if (guiDraft.defer(() -> updateRows(clamped))) {
            return;
        }
        if (clamped == gui.getRows()) {
            return;
        }
        captureSnapshot();
        gui.setRows(clamped);
        if (selectedElement != null && (gui.getElements() == null || !gui.getElements().contains(selectedElement))) {
            selectedElement = null;
        }
        rebuildGrid();
        buildInspectorPanel();
        updateLayout(true);
    }

    private void setExtendInventoryMode(boolean enabled) {
        if (guiDraft.defer(() -> setExtendInventoryMode(enabled))) {
            return;
        }
        if (gui.isExtendToPlayerInventory() == enabled) {
            if (extendInventoryToggle != null && extendInventoryToggle.getValue() != enabled) {
                extendInventoryToggle.setValue(enabled);
            }
            return;
        }
        captureSnapshot();
        gui.setExtendToPlayerInventory(enabled);
        if (extendInventoryToggle != null && extendInventoryToggle.getValue() != enabled) {
            extendInventoryToggle.setValue(enabled);
        }
        if (selectedElement != null && (gui.getElements() == null || !gui.getElements().contains(selectedElement))) {
            selectedElement = null;
        }
        rebuildGrid();
        buildInspectorPanel();
        updateLayout(true);
    }

    private void pruneElementsForSlots(int maxSlot) {
        if (gui.getElements() == null) {
            return;
        }
        gui.getElements().removeIf(element -> {
            if (element == null || element.getSlots() == null) {
                return true;
            }
            element.getSlots().removeIf(slot -> slot == null || slot < 0 || slot >= maxSlot);
            return element.getSlots().isEmpty();
        });
    }

    private void remapSlotsForRowChange(int oldRows, int newRows) {
        if (gui.getElements() == null) {
            return;
        }
        int oldInventoryStart = oldRows * GRID_COLUMNS;
        int newTopSlots = newRows * GRID_COLUMNS;
        int oldInventoryEnd = oldInventoryStart + PLAYER_INVENTORY_SLOTS;
        gui.getElements().removeIf(element -> {
            if (element == null || element.getSlots() == null) {
                return true;
            }
            List<Integer> remapped = new ArrayList<>();
            for (Integer slot : element.getSlots()) {
                if (slot == null || slot < 0) {
                    continue;
                }
                if (slot < oldInventoryStart) {
                    if (slot < newTopSlots) {
                        remapped.add(slot);
                    }
                    continue;
                }
                if (slot < oldInventoryEnd) {
                    int offset = slot - oldInventoryStart;
                    remapped.add(newTopSlots + offset);
                }
            }
            element.setSlots(remapped);
            return remapped.isEmpty();
        });
    }

    private int getTotalSlots() {
        return gui.getRows() * GRID_COLUMNS + (gui.isExtendToPlayerInventory() ? PLAYER_INVENTORY_SLOTS : 0);
    }

    private int getTotalRows() {
        return gui.getRows() + (gui.isExtendToPlayerInventory() ? PLAYER_INVENTORY_ROWS : 0);
    }

    private void setPlaceMode(boolean enabled) {
        placeMode = enabled;
        if (placeToggle != null && placeToggle.getValue() != enabled) {
            placeToggle.setValue(enabled);
        }
        draggingPlacement = false;
        dragResizeElement = null;
        placementStroke = null;
        clearPendingResize();
        dragPreviewSlots.clear();
        applySlotState();
    }

    private void clearPendingResize() {
        pendingResizeSlot = -1;
        pendingResizeElement = null;
    }

    private void startPlacementDrag(int slot, GuiElement resizeElement) {
        draggingPlacement = true;
        dragResizeElement = resizeElement;
        highlightPreviewNonce++;
        clearPendingResize();
        SlotButton button = slotButtons.get(slot);
        if (button != null) {
            setHighlightOrigin(button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2);
        }
        placementStroke = SlotInteractionGrid.beginStroke(slotRects(), button != null ? button.getX() + button.getWidth() / 2 : mouseX, button != null ? button.getY() + button.getHeight() / 2 : mouseY);
        updateDragPreview();
    }

    private void setHighlightOrigin(int x, int y) {
        highlightOriginX = x;
        highlightOriginY = y;
    }

    private void updateDragPreview() {
        dragPreviewSlots.clear();
        if (placementStroke == null) {
            applySlotState();
            return;
        }
        for (int slot : placementStroke.slots()) {
            GuiElement occupied = slotElements.get(slot);
            if (occupied == null || occupied == dragResizeElement) {
                dragPreviewSlots.add(slot);
            }
        }
        applySlotState();
    }

    private void finishPlacementDrag() {
        draggingPlacement = false;
        List<Integer> slots = placementStroke != null ? placementStroke.slots() : List.of();
        GuiElement resizingElement = dragResizeElement;
        dragResizeElement = null;
        placementStroke = null;
        clearPendingResize();
        dragPreviewSlots.clear();

        if (slots.isEmpty()) {
            applySlotState();
            return;
        }
        for (int slot : slots) {
            GuiElement occupied = slotElements.get(slot);
            if (occupied != null && occupied != resizingElement) {
                selectElement(occupied);
                return;
            }
        }
        if (resizingElement != null) {
            captureSnapshot();
            resizingElement.setSlots(new ArrayList<>(slots));
            rebuildGrid();
            selectElement(resizingElement);
            return;
        }

        captureSnapshot();
        GuiElement element = new GuiElement();
        element.getSlots().addAll(slots);
        Visual template = placementTemplate != null ? placementTemplate.copy() : new Visual("STONE", "Item");
        element.setVisual(template);
        gui.getElements().add(element);
        rebuildGrid();
        selectElement(element);
    }

    private int getSlotAt(int mouseX, int mouseY) {
        if (slotSize <= 0) {
            return -1;
        }
        for (SlotButton button : slotButtons.values()) {
            if (button.isMouseOver(mouseX, mouseY)) {
                return button.slot;
            }
        }
        return -1;
    }

    private List<SlotInteractionGrid.SlotRect> slotRects() {
        List<SlotInteractionGrid.SlotRect> slots = new ArrayList<>();
        for (SlotButton button : slotButtons.values()) {
            slots.add(new SlotInteractionGrid.SlotRect(button.slot, button.getX(), button.getY(), Math.min(button.getWidth(), button.getHeight())));
        }
        return slots;
    }

    private void positionGridWidgets() {
        int topSlots = gui.getRows() * GRID_COLUMNS;
        boolean extendInventory = gui.isExtendToPlayerInventory();
        for (SlotButton button : slotButtons.values()) {
            int col = button.slot % GRID_COLUMNS;
            int x = gridOriginX + col * slotSize;
            int y;
            if (!extendInventory || button.slot < topSlots) {
                int row = button.slot / GRID_COLUMNS;
                y = gridOriginY + row * slotSize;
            } else {
                int index = button.slot - topSlots;
                if (index < 27) {
                    int row = index / GRID_COLUMNS;
                    y = playerInventoryOriginY + row * slotSize;
                } else {
                    y = hotbarOriginY;
                }
            }
            button.setPosition(x, y);
            button.setWidth(slotSize);
            button.setHeight(slotSize);
        }

        if (gridContainer != null) {
            gridContainer.updateWidgetPositions();
        }
    }

    private void updateLayout(boolean force) {
        int inspectorWidth = inspectorPanel != null ? inspectorPanel.layoutWidth(0) : 0;
        int studioLeftWidth = parent instanceof StudioScreen studio
            ? studio.studioContentBrowserWidth() : studioContentBrowserWidth();
        if (!force && width == lastWidth && height == lastHeight
            && inspectorWidth == lastInspectorWidth && studioLeftWidth == lastStudioLeftWidth) {
            return;
        }
        lastWidth = width;
        lastHeight = height;
        lastInspectorWidth = inspectorWidth;
        lastStudioLeftWidth = studioLeftWidth;

        int contentTop = header().headerSize + 5;
        int contentHeight = Math.max(120, height - contentTop - PANEL_PADDING);

        if (inspectorPanel != null) {
            if (inspectorStudioPanel != null) {
                inspectorStudioPanel.layout();
            }
        }

        int rightWidth = inspectorWidth;
        int availableWidth = width - studioLeftWidth - rightWidth - PANEL_PADDING * 2;
        int centerWidth = Math.max(120, availableWidth);
        slotSize = SLOT_BASE_SIZE;
        guiScale = 1f;

        guiBackgroundWidth = GUI_TEXTURE_WIDTH;
        int rows = Math.max(1, gui.getRows());
        int topHeight = GUI_TOP_MARGIN + rows * SLOT_BASE_SIZE;
        guiBackgroundHeight = topHeight + GUI_PLAYER_INV_HEIGHT;

        int gridX = studioLeftWidth + PANEL_PADDING + Math.max(0, (centerWidth - guiBackgroundWidth) / 2);
        int gridY = contentTop + Math.max(0, (contentHeight - guiBackgroundHeight) / 2) - 14;

        guiBackgroundX = gridX;
        guiBackgroundY = gridY;
        gridOriginX = guiBackgroundX + GUI_SIDE_MARGIN;
        gridOriginY = guiBackgroundY + GUI_TOP_MARGIN;
        playerInventoryOriginY = guiBackgroundY + GUI_TOP_MARGIN + rows * SLOT_BASE_SIZE + GUI_PLAYER_INV_OFFSET - 1;
        hotbarOriginY = guiBackgroundY + GUI_TOP_MARGIN + rows * SLOT_BASE_SIZE + GUI_HOTBAR_OFFSET - 1;

        if (gridContainer != null) {
            gridContainer.setPosition(guiBackgroundX, guiBackgroundY);
            gridContainer.padding(0);
            gridContainer.size(guiBackgroundWidth, guiBackgroundHeight);
            positionGridWidgets();
        }
    }

    private void saveGui() {
        FlowManager flowManager = FlowManager.getInstance();
        String id = gui.getId();
        String title = gui.getTitle() != null ? gui.getTitle() : id;
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.GUI, id, title);
        observeSave(ticket);
        if (flowManager != null && serverId != null) {
            if (!guiDraft.capture(ReSyncResourceType.GUI.typeId(), id, ticket,
                snapshot -> flowManager.saveGui(serverId, snapshot.serialize(FlowSerializer::deserializeGui), ticket))) {
                DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
            }
        } else {
            DesignerSaveNotifications.failExact(ticket, "ReSync Offline");
        }
    }

    @Override
    public boolean requestStudioSave() {
        saveGui();
        return true;
    }

    private void rebindGui(GuiDefinition previous, GuiDefinition replacement) {
        GuiDefinition currentGui = gui;
        GuiElement currentSelectedElement = selectedElement;
        GuiElement currentDragResizeElement = dragResizeElement;
        GuiElement currentPendingResizeElement = pendingResizeElement;
        GuiElement currentLastInspectorElement = lastInspectorElement;
        boolean currentDraggingPlacement = draggingPlacement;
        SlotInteractionGrid.Stroke currentPlacementStroke = placementStroke;
        SlotButton currentHoveredSlotButton = hoveredSlotButton;
        GuiActionMode currentInspectorActionMode = inspectorActionMode;
        try {
            int selectedIndex = selectedElement != null && previous != null && previous.getElements() != null
                ? previous.getElements().indexOf(selectedElement) : -1;
            gui = replacement;
            List<GuiElement> replacementElements = gui.getElements();
            selectedElement = selectedIndex >= 0 && replacementElements != null && selectedIndex < replacementElements.size()
                ? replacementElements.get(selectedIndex) : null;
            dragResizeElement = null;
            pendingResizeElement = null;
            lastInspectorElement = null;
            draggingPlacement = false;
            placementStroke = null;
            hoveredSlotButton = null;
            rebuildGrid();
            buildInspectorPanel();
            updateLayout(true);
        } catch (RuntimeException | Error exception) {
            gui = currentGui;
            selectedElement = currentSelectedElement;
            dragResizeElement = currentDragResizeElement;
            pendingResizeElement = currentPendingResizeElement;
            lastInspectorElement = currentLastInspectorElement;
            draggingPlacement = currentDraggingPlacement;
            placementStroke = currentPlacementStroke;
            hoveredSlotButton = currentHoveredSlotButton;
            inspectorActionMode = currentInspectorActionMode;
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
                guiDraft.discard(ticket);
                return;
            }
            FlowManager manager = FlowManager.getInstance();
            FlowManager.ResourceReadLease lease = manager != null
                ? manager.snapshotResource(serverId, ReSyncResourceType.GUI.typeId(), ticket.id()) : null;
            if (lease == null || !guiDraft.acknowledge(ticket, lease::materialize)) {
                guiDraft.discard(ticket);
            }
        }));
    }

    private void ensureGuiDefaults() {
        if (gui.getRows() <= 0) {
            gui.setRows(3);
        }
        if (gui.getElements() == null) {
            gui.setElements(new ArrayList<>());
        }
    }

    private Visual ensureVisual(GuiElement element) {
        Visual visual = element.getVisual();
        if (visual == null) {
            visual = new Visual();
            element.setVisual(visual);
        }
        return visual;
    }

    private void updateGuiTitle(String title) {
        String next = title != null ? title : "";
        if (guiDraft.defer(() -> updateGuiTitle(next))) {
            return;
        }
        String current = gui.getTitle() != null ? gui.getTitle() : "";
        if (current.equals(next)) {
            return;
        }
        captureSnapshot();
        gui.setTitle(title);
    }

    private void updateVisualName(Visual visual, String name) {
        String current = visual.getName() != null ? visual.getName() : "";
        String next = name != null ? name : "";
        if (current.equals(next)) {
            return;
        }
        captureSnapshot();
        visual.setName(name);
    }

    private void updateModelData(Visual visual, String text) {
        Integer current = visual.getModelData();
        Integer next = current;
        String trimmed = text != null ? text.trim() : "";
        if (trimmed.isEmpty()) {
            next = null;
        } else {
            try {
                next = Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                applySlotState();
                return;
            }
        }
        if ((current == null && next == null) || (current != null && current.equals(next))) {
            applySlotState();
            return;
        }
        captureSnapshot();
        visual.setModelData(next);
        applySlotState();
    }

    private void updateLore(Visual visual, String text) {
        List<String> loreLines = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    loreLines.add(trimmed);
                }
            }
        }
        if (visual.getLore() != null && visual.getLore().equals(loreLines)) {
            return;
        }
        captureSnapshot();
        visual.setLore(loreLines);
    }

    private GuiSnapshot createSnapshot() {
        List<GuiElement> copiedElements = new ArrayList<>();
        if (gui.getElements() != null) {
            for (GuiElement element : gui.getElements()) {
                if (element != null) {
                    copiedElements.add(element.copy());
                }
            }
        }
        int selectedIndex = -1;
        if (selectedElement != null && gui.getElements() != null) {
            selectedIndex = gui.getElements().indexOf(selectedElement);
        }
        return new GuiSnapshot(
            gui.getTitle(),
            gui.getRows(),
            gui.isExtendToPlayerInventory(),
            copiedElements,
            selectedIndex,
            placementTemplate != null ? placementTemplate.copy() : null
        );
    }

    private void captureSnapshot() {
        guiDraft.markMutation();
        history.capture();
    }

    private void restoreSnapshot(GuiSnapshot snapshot) {
        gui.setTitle(snapshot.title);
        gui.setRows(snapshot.rows);
        gui.setExtendToPlayerInventory(snapshot.extendToPlayerInventory);
        List<GuiElement> restoredElements = new ArrayList<>();
        for (GuiElement element : snapshot.elements) {
            restoredElements.add(element.copy());
        }
        gui.setElements(restoredElements);
        if (snapshot.selectedIndex >= 0 && snapshot.selectedIndex < restoredElements.size()) {
            selectedElement = restoredElements.get(snapshot.selectedIndex);
        } else {
            selectedElement = null;
        }
        placementTemplate = snapshot.placementTemplate != null ? snapshot.placementTemplate.copy() : new Visual("PAPER", "Item");
        if (guiTitleInput != null) {
            guiTitleInput.setText(gui.getTitle() != null ? gui.getTitle() : "");
        }
        if (guiRowsSelect != null) {
            guiRowsSelect.setSelectedItem(Math.clamp(gui.getRows(), 1, 6));
        }
        if (extendInventoryToggle != null) {
            extendInventoryToggle.setValue(gui.isExtendToPlayerInventory());
        }
        rebuildGrid();
        buildInspectorPanel();
        updateLayout(true);
    }

    private String formatSlots(GuiElement element) {
        if (element == null || element.getSlots() == null || element.getSlots().isEmpty()) {
            return "None";
        }
        List<Integer> slots = new ArrayList<>(element.getSlots());
        slots.sort(Integer::compareTo);
        StringBuilder builder = new StringBuilder();
        int max = Math.min(slots.size(), 6);
        for (int i = 0; i < max; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(slots.get(i) + 1);
        }
        if (slots.size() > max) {
            builder.append(" ... (").append(slots.size()).append(" slots)");
        }
        return builder.toString();
    }

    private String formatFlowLabel(String flowId) {
        if (flowId == null || flowId.isBlank() || serverId == null) {
            return "None";
        }
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager == null) {
            return flowId;
        }
        String name = flowManager.getFlowName(serverId, flowId);
        if (name == null || name.isBlank() || name.equals(flowId)) {
            return flowId;
        }
        return name;
    }

    private String formatGuiLabel(String guiId) {
        if (guiId == null || guiId.isBlank() || serverId == null) {
            return "None";
        }
        FlowManager flowManager = FlowManager.getInstance();
        if (flowManager == null) {
            return guiId;
        }
        String name = flowManager.getGuiName(serverId, guiId);
        if (name == null || name.isBlank() || name.equals(guiId)) {
            return guiId;
        }
        return name;
    }

    private void disableEntrance(AnimatedWidget widget) {
        if (widget != null) {
            widget.entranceAnimationEnabled = false;
        }
    }

    private void setSelectorSelection(ItemSelectorWidget selector, String label) {
        if (selector == null) {
            return;
        }
        selector.setSelectedItem(label);
    }

    private String formatMaterialLabel(String material) {
        if (material == null || material.isBlank()) {
            return "unknown";
        }
        String cleaned = material.trim().toLowerCase(Locale.ROOT).replace("_", " ");
        String[] parts = cleaned.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
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

    private void drawGuiTexture(IDrawContext context, MinecraftGameAssets gameAssets, MinecraftAssetReference reference, Identifier fallbackId, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight) {
        if (MinecraftUiPreviewRenderer.drawAssetRegion(context, gameAssets, reference, x, y, width, height, u, v, regionWidth, regionHeight, 256, 256)) {
            return;
        }
        MinecraftUiPreviewRenderer.drawImage(context, fallbackId, x, y, width, height);
    }

    private MinecraftAssetReference resolveMaterialTexture(Visual visual) {
        if (visual == null) {
            return cachedMaterialTexture("stone", null);
        }
        return cachedMaterialTexture(visual.getMaterial(), visual.getModelData());
    }

    private MinecraftAssetReference cachedMaterialTexture(String material, Integer modelData) {
        String key = (material != null ? material : "") + '\u0000' + (modelData != null ? modelData : "");
        MinecraftAssetReference reference = materialTextureReferences.get(key);
        if (reference == null) {
            reference = getGameAssets().resolveMaterialTexture(material, modelData);
            materialTextureReferences.put(key, reference);
        }
        return reference;
    }

    private MinecraftRenderItem toRenderItem(Visual visual) {
        if (visual == null || shouldRenderAsTexture(visual)) {
            return null;
        }
        return MinecraftGameItems.fromVisual(visual.getMaterial(), 1, visual.getName(), visual.getLore(), visual.getModelData());
    }

    private boolean shouldRenderAsTexture(Visual visual) {
        if (visual == null) {
            return true;
        }
        String material = visual.getMaterial();
        if (material == null || material.isBlank()) {
            return true;
        }
        String path = material.trim().replace('\\', '/');
        if (path.contains(":")) {
            String[] parts = path.split(":", 2);
            path = parts.length > 1 ? parts[1] : "";
        }
        return path.contains("/") || path.endsWith(".png");
    }

    private class SlotButton extends AnimatedButton {
        private final int slot;
        private final SlotClickHandler handler;
        private GuiElement element;

        private SlotButton(int slot, int size, SlotClickHandler handler) {
            super(0, 0, size, size, "");
            this.slot = slot;
            this.handler = handler;
            this.centered = true;
            this.selectable = false;
            this.flat = true;
            this.animateElevation = false;
            this.enableHoverColors = false;
            this.entranceAnimationEnabled = false;
            this.transparent = true;
        }

        private void setElement(GuiElement element) {
            this.element = element;
        }

        private GuiElement getElement() {
            return element;
        }

        private boolean hasElement() {
            return element != null;
        }

        private MinecraftRenderItem getRenderItem() {
            return element != null ? toRenderItem(element.getVisual()) : null;
        }

        @Override
        protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
            if (element != null) {
                drawGuiElementIcon(ctx, element, getX(), getY(), getWidth(), getHeight());
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            if (handler != null) {
                handler.onClick(slot, button);
            }
        }
    }

    private class TooltipOverlayWidget extends AnimatedWidget {
        private TooltipOverlayWidget() {
            super(0, 0, 0, 0, "");
            this.active = false;
            this.transparent = true;
            this.enableHoverColors = false;
            this.animateElevation = false;
            this.entranceAnimationEnabled = false;
        }

        @Override
        protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
        }

        @Override
        public void renderHintOverlay(IDrawContext context) {
        }
    }

    private interface SlotClickHandler {
        void onClick(int slot, int button);
    }

    private boolean isAnyPopupOpen() {
        if (widgets == null) {
            return false;
        }
        for (Object child : widgets) {
            if (child instanceof PopupWidget popup && popup.isVisible()) {
                return true;
            }
        }
        return false;
    }
}
