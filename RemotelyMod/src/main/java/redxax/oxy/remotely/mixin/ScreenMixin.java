package redxax.oxy.remotely.mixin;

import net.minecraft.client.Minecraft;
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#endif
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.ChatScreen;
//#if MC >= 1.21.6 || MC >= 26.1
import net.minecraft.client.gui.screens.dialog.DialogScreen;
//#endif
//#if MC >= 1.20.1 && MC < 26.1
//$$ import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//#if MC >= 1.21.1
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
//#else
//$$ import net.minecraft.world.scores.Score;
//$$ import net.minecraft.world.scores.Objective;
//#endif
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
//#if MC >= 26.1
import net.minecraft.client.input.KeyEvent;
//#endif
//#if MC >= 1.21.9 && MC < 26.1
//$$ import net.minecraft.client.input.KeyEvent;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import redxax.oxy.remotely.adapters.ICustomWidgetHolder;
import redxax.oxy.remotely.adapters.MinecraftDrawContextAdapter;
import redxax.oxy.remotely.config.Config;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.mixin.accessor.AbstractContainerScreenAccessor;
import redxax.oxy.remotely.mixin.accessor.AdvancementTabAccessor;
import redxax.oxy.remotely.mixin.accessor.AdvancementsScreenAccessor;
import redxax.oxy.remotely.rematrix.mc.RematrixContext;
import redxax.oxy.remotely.rematrix.mc.RematrixScale;
import redxax.oxy.remotely.rematrix.mc.RematrixScreen;
import redxax.oxy.remotely.resync.bridge.ReSyncVanillaBridgeManager;
import redxax.oxy.remotely.servers.ReProxyManager;
import redxax.oxy.remotely.ui.tests.ContainerTestingScreen;
import redxax.oxy.remotely.ui.tests.WidgetsTestingScreen;
import redxax.oxy.remotely.util.CursorUtils;
import redxax.oxy.remotely.util.ScreenInitHelper;
import restudio.rescreen.Main;
import restudio.rescreen.platform.input.ReInputEventFactory;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.ui.MouseCursor;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.List;

import static redxax.oxy.remotely.config.Config.enableDebugTools;

@Mixin(value = Screen.class)
public abstract class ScreenMixin implements ICustomWidgetHolder {

    @Unique
    private final List<Widget> remotely$customWidgets = new ArrayList<>();

    @Unique
    private boolean remotely$wasMouseDown = false;

    @Unique
    private IconButton remotely$editOverlayButton;

    @Unique
    private IconButton remotely$advancementAddButton;

    @Unique
    private int remotely$editOverlayRevision = -1;

    @Unique
    private String remotely$advancementOverlayTreeId;

    @Unique
    private String remotely$editOverlayTargetKey;

    @Unique
    private String remotely$editOverlayServerId;

    @Unique
    private String remotely$editOverlayResourceType;

    @Unique
    private String remotely$editOverlayResourceId;

    @Unique
    private boolean remotely$overlayEditable;

    @Unique
    private String remotely$overlayServerId;

    @Unique
    private String remotely$overlayResourceType;

    @Unique
    private String remotely$overlayResourceId;

    @Unique
    private int[] remotely$scoreboardOverlayBounds;

    @Unique
    private String remotely$localScoreboardId;

    @Unique
    private String remotely$overlayGuiId;

    @Unique
    private String remotely$overlayFlowId;

    @Unique
    private int remotely$overlayStateRevision = -1;

    @Override
    public List<Widget> remotely$getWidgets() {
        return this.remotely$customWidgets;
    }

    @Override
    public void remotely$addWidget(Widget widget) {
        this.remotely$customWidgets.add(widget);
    }

    @Override
    public void remotely$clearWidgets() {
        this.remotely$customWidgets.clear();
    }

    @Inject(
    //#if MC >= 26.1
        method = "extractRenderStateWithTooltipAndSubtitles",
    //#elseif MC >= 1.21.10
    //$$     method = "renderWithTooltipAndSubtitles",
    //#else
    //$$     method = "renderWithTooltip",
    //#endif
        at = @At("TAIL")
    )
    //#if MC >= 26.1
    private void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float f, CallbackInfo ci) {
    //#elseif MC >= 1.20.1
    //$$ private void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float f, CallbackInfo ci) {
    //#else
    //$$ private void render(PoseStack guiGraphics, int mouseX, int mouseY, float f, CallbackInfo ci) {
    //#endif
        restudio.rescreen.config.Config.tickTime();
        CursorUtils.tick();
        Config.globalCursorAnimatedColor = CursorUtils.blendColor();

        ScreenInitHelper.ensureTitleScreenButtons((Screen) (Object) this);
        remotely$updateEditOverlay();

        boolean hasWidgets = !remotely$customWidgets.isEmpty();
        boolean renderCursor = restudio.rescreen.config.Config.customMouse && !(((Object) this) instanceof RematrixScreen);

        boolean renderPinned = !(((Object) this) instanceof RematrixScreen);

        if (hasWidgets || renderCursor || renderPinned) {
            MouseCursor.beginFrame();

            //#if NEOFORGE && MC < 1.21.10
            //$$ Main.setWindow(Minecraft.getInstance().getWindow().getWindow());
            //#elseif MC >= 1.21.6 || MC >= 26.1
            Main.setWindow(Minecraft.getInstance().getWindow().handle());
            //#else
            //$$ Main.setWindow(Minecraft.getInstance().getWindow().getWindow());
            //#endif

            if (hasWidgets) {
                remotely$handleInput(mouseX, mouseY);
            }

            //#if MC >= 26.1
            var pose = guiGraphics.pose();
            pose.pushMatrix();
            pose.identity();
            //#endif
            //#if MC >= 1.21.6 && MC < 26.1
            //$$ var pose = guiGraphics.pose();
            //$$ pose.pushMatrix();
            //$$ pose.identity();
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ guiGraphics.pose().pushPose();
            //$$ guiGraphics.pose().last().pose().identity();
            //#endif

            //#if MC >= 26.1
            RematrixContext ctx = new RematrixContext(guiGraphics);
            //#endif
            //#if MC >= 1.20.1 && MC < 26.1
            //$$ RematrixContext ctx = new RematrixContext(guiGraphics);
            //#endif
            //#if MC < 1.20.1
            //$$ RematrixMcContext ctx = new RematrixMcContext(guiGraphics);
            //#endif
            MinecraftDrawContextAdapter adapter = new MinecraftDrawContextAdapter(ctx);
            adapter.advanceRenderLayer();
            if (hasWidgets) {
                remotely$renderWidgets(adapter, mouseX, mouseY, f);
            }
            if (renderPinned) {
                ScreenManager sm = ScreenManager.getInstance();
                Minecraft minecraft = Minecraft.getInstance();
                RematrixScale.ensureConfigured(minecraft);
                int windowWidth = minecraft.getWindow().getWidth();
                int windowHeight = minecraft.getWindow().getHeight();
                sm.updateDimensions(windowWidth, windowHeight);
                float mcScale = (float) minecraft.getWindow().getGuiScale();
                float reScale = sm.getGuiScale();
                if (mcScale != 0 && reScale != 0) {
                    float renderScale = reScale / mcScale;
                    float mouseScale = mcScale / reScale;
                    //#if MC >= 26.1
                    pose.pushMatrix();
                    pose.scale(renderScale, renderScale);
                    //#endif
                    //#if MC >= 1.21.6 && MC < 26.1
                    //$$ pose.pushMatrix();
                    //$$ pose.scale(renderScale, renderScale);
                    //#endif
                    //#if MC < 1.21.6 && MC < 26.1
                    //$$ guiGraphics.pose().pushPose();
                    //$$ guiGraphics.pose().scale(renderScale, renderScale, 1f);
                    //#endif
                    //#if MC >= 26.1
                    RematrixContext pinnedCtx = new RematrixContext(guiGraphics, renderScale);
                    //#endif
                    //#if MC >= 1.20.1 && MC < 26.1
                    //$$ RematrixContext pinnedCtx = new RematrixContext(guiGraphics, renderScale);
                    //#endif
                    //#if MC < 1.20.1
                    //$$ RematrixMcContext pinnedCtx = new RematrixMcContext(guiGraphics, renderScale);
                    //#endif
                    MinecraftDrawContextAdapter pinnedAdapter = new MinecraftDrawContextAdapter(pinnedCtx);
                    sm.renderPinnedInGameWindows(pinnedAdapter, (int) (mouseX * mouseScale), (int) (mouseY * mouseScale), f);
                    sm.processTasks();
                    //#if MC >= 26.1
                    pose.popMatrix();
                    //#endif
                    //#if MC >= 1.21.6 && MC < 26.1
                    //$$ pose.popMatrix();
                    //#endif
                    //#if MC < 1.21.6 && MC < 26.1
                    //$$ guiGraphics.pose().popPose();
                    //#endif
                }
            }
            if (renderCursor) {
                MouseCursor.updateAndRender(adapter, mouseX, mouseY);
            }

            //#if MC >= 26.1
            pose.popMatrix();
            //#endif
            //#if MC >= 1.21.6 && MC < 26.1
            //$$ pose.popMatrix();
            //#endif
            //#if MC < 1.21.6 && MC < 26.1
            //$$ guiGraphics.pose().popPose();
            //#endif
        }
    }

    @Unique
    private void remotely$handleInput(int mouseX, int mouseY) {
        boolean mouseDown = ScreenManager.getInstance().cursorHandler().isButtonDown(ReMouseButton.LEFT);

        if (mouseDown && !remotely$wasMouseDown) {
            for (int i = remotely$customWidgets.size() - 1; i >= 0; i--) {
                Widget widget = remotely$customWidgets.get(i);
                if (widget.isVisible() && widget.isActive() && widget.isMouseOver(mouseX, mouseY)) {
                    if (Widget.dispatchMouseClicked(widget, ReInputEventFactory.mouseEvent(this, widget, ReMouseEvent.Action.PRESSED, mouseX, mouseY, 0, 0, 0, 0))) {
                        break;
                    }
                }
            }
        }
        remotely$wasMouseDown = mouseDown;
    }

    @Unique
    private void remotely$updateEditOverlay() {
        boolean hasState = remotely$refreshOverlayState();
        FlowManager manager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
        boolean guiTarget = "gui".equals(remotely$overlayResourceType) || (remotely$overlayResourceType == null && remotely$overlayGuiId != null);
        boolean scoreboardTarget = "scoreboard".equals(remotely$overlayResourceType);
        boolean dialogTarget = ReSyncResourceDragPayload.DIALOG.equals(remotely$overlayResourceType);
        remotely$localScoreboardId = remotely$isChatScreen() ? remotely$getKnownLiveScoreboardId() : null;
        boolean localScoreboardTarget = remotely$localScoreboardId != null && !remotely$localScoreboardId.isBlank();
        remotely$scoreboardOverlayBounds = (scoreboardTarget || localScoreboardTarget) ? remotely$getScoreboardBounds() : null;
        boolean showGui = guiTarget && remotely$overlayGuiId != null && !remotely$overlayGuiId.isBlank() && ((Object) this) instanceof AbstractContainerScreen;
        boolean showScoreboard = remotely$isChatScreen() && ((scoreboardTarget && remotely$overlayResourceId != null && !remotely$overlayResourceId.isBlank()) || localScoreboardTarget);
        boolean showDialog = remotely$isDialogScreen() && dialogTarget && remotely$overlayResourceId != null && !remotely$overlayResourceId.isBlank();
        boolean advancementScreen = ((Object) this) instanceof AdvancementsScreen;
        String advancementServerId = advancementScreen ? ReSyncVanillaBridgeManager.getInstance().getLiveServerId() : null;
        boolean showAdvancementAdd = advancementScreen && manager != null && advancementServerId != null && !advancementServerId.isBlank();
        String advancementTreeId = showAdvancementAdd ? remotely$getSelectedAdvancementTreeId(manager, advancementServerId) : null;
        boolean showAdvancementEdit = advancementTreeId != null && !advancementTreeId.isBlank();
        boolean show = (hasState && remotely$overlayEditable && (showGui || showScoreboard || showDialog)) || localScoreboardTarget || showAdvancementAdd;
        if (!show && remotely$isDialogScreen() && ReSyncResourceDragPayload.DIALOG.equals(remotely$editOverlayResourceType) && remotely$editOverlayResourceId != null && !remotely$editOverlayResourceId.isBlank()) {
            showDialog = true;
            show = true;
        }

        if (!show) {
            if (remotely$editOverlayButton != null || remotely$advancementAddButton != null) {
                remotely$clearWidgets();
                remotely$editOverlayButton = null;
                remotely$advancementAddButton = null;
                remotely$editOverlayRevision = -1;
                remotely$advancementOverlayTreeId = null;
                remotely$editOverlayTargetKey = null;
                remotely$editOverlayServerId = null;
                remotely$editOverlayResourceType = null;
                remotely$editOverlayResourceId = null;
            }
            return;
        }

        boolean editButtonTarget = showGui || showScoreboard || showDialog || localScoreboardTarget || showAdvancementEdit;
        String editOverlayServerId = remotely$getEditOverlayServerId(showGui, showScoreboard, showDialog, localScoreboardTarget, showAdvancementEdit);
        String editOverlayResourceType = remotely$getEditOverlayResourceType(showGui, showScoreboard, showDialog, localScoreboardTarget, showAdvancementEdit);
        String editOverlayResourceId = remotely$getEditOverlayResourceId(showGui, showScoreboard, showDialog, localScoreboardTarget, showAdvancementEdit, advancementTreeId);
        String editOverlayTargetKey = remotely$getEditOverlayTargetKey(editOverlayServerId, editOverlayResourceType, editOverlayResourceId);
        boolean rebuildOverlay = remotely$editOverlayRevision != remotely$overlayStateRevision
            || !remotely$same(remotely$advancementOverlayTreeId, advancementTreeId)
            || !remotely$same(remotely$editOverlayTargetKey, editOverlayTargetKey)
            || (showAdvancementAdd && remotely$advancementAddButton == null)
            || (!showAdvancementAdd && remotely$advancementAddButton != null)
            || (editButtonTarget && remotely$editOverlayButton == null)
            || (!editButtonTarget && remotely$editOverlayButton != null);
        if (rebuildOverlay) {
            remotely$clearWidgets();
            remotely$editOverlayButton = null;
            remotely$advancementAddButton = null;
            if (showAdvancementAdd) {
                remotely$advancementAddButton = new IconButton.Builder()
                    .imagePath("add.png")
                    .size(18, 18)
                    .iconSize(16)
                    .onClick(() -> remotely$createAdvancementTreeFromOverlay())
                    .build();
                remotely$advancementAddButton.entranceAnimationEnabled = false;
                remotely$addWidget(remotely$advancementAddButton);
            }
            if (editButtonTarget) {
                remotely$editOverlayButton = new IconButton.Builder()
                    .imagePath("edit.png")
                    .size(18, 18)
                    .iconSize(16)
                    .onClick(() -> remotely$openEditOverlayTarget())
                    .build();
                remotely$editOverlayButton.entranceAnimationEnabled = false;
                remotely$addWidget(remotely$editOverlayButton);
            }
            remotely$editOverlayRevision = remotely$overlayStateRevision;
            remotely$advancementOverlayTreeId = advancementTreeId;
            remotely$editOverlayTargetKey = editOverlayTargetKey;
        }
        remotely$editOverlayServerId = editOverlayServerId;
        remotely$editOverlayResourceType = editOverlayResourceType;
        remotely$editOverlayResourceId = editOverlayResourceId;

        int[] containerBounds = remotely$getContainerBounds();
        int x;
        int y;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        if (showAdvancementAdd) {
            int[] bounds = remotely$getAdvancementsWindowBounds();
            x = bounds[0] + bounds[2] + 6;
            y = Math.max(6, bounds[1] + 6);
            if (x + 18 > screenWidth - 2) {
                x = Math.max(6, screenWidth - 24);
            }
        } else if (showDialog) {
            int[] bounds = remotely$getDialogButtonBounds();
            x = bounds[0];
            y = bounds[1];
        } else if (showScoreboard || localScoreboardTarget) {
            int[] bounds = remotely$scoreboardOverlayBounds != null ? remotely$scoreboardOverlayBounds : remotely$getFallbackScoreboardButtonBounds();
            x = Math.max(6, bounds[0] - 24);
            y = Math.max(6, bounds[1]);
        } else if (containerBounds != null) {
            x = containerBounds[0] + containerBounds[2] + 6;
            y = containerBounds[1];
            if (x + 18 > screenWidth - 2) {
                x = Math.max(6, screenWidth - 24);
            }
            y = Math.max(6, y);
        } else {
            x = Math.max(6, screenWidth - 24);
            y = 6;
        }
        if (remotely$advancementAddButton != null) {
            remotely$advancementAddButton.setPosition(x, y);
            remotely$advancementAddButton.setWidth(18);
            remotely$advancementAddButton.setHeight(18);
            y += 22;
        }
        if (remotely$editOverlayButton != null) {
            remotely$editOverlayButton.setPosition(x, y);
            remotely$editOverlayButton.setWidth(18);
            remotely$editOverlayButton.setHeight(18);
        }
    }

    @Unique
    private String remotely$getEditOverlayTargetKey(String serverId, String type, String id) {
        if (serverId == null || serverId.isBlank() || type == null || type.isBlank() || id == null || id.isBlank()) {
            return null;
        }
        return serverId + ":" + type + ":" + id;
    }

    @Unique
    private String remotely$getEditOverlayServerId(boolean showGui, boolean showScoreboard, boolean showDialog, boolean localScoreboardTarget, boolean showAdvancementEdit) {
        if (showAdvancementEdit || localScoreboardTarget) {
            return ReSyncVanillaBridgeManager.getInstance().getLiveServerId();
        }
        if (showDialog && (remotely$overlayServerId == null || remotely$overlayServerId.isBlank()) && remotely$editOverlayServerId != null && !remotely$editOverlayServerId.isBlank()) {
            return remotely$editOverlayServerId;
        }
        if (showGui || showScoreboard || showDialog) {
            String liveServerId = ReSyncVanillaBridgeManager.getInstance().getLiveServerId();
            return remotely$overlayServerId != null && !remotely$overlayServerId.isBlank() ? remotely$overlayServerId : liveServerId;
        }
        return null;
    }

    @Unique
    private String remotely$getEditOverlayResourceType(boolean showGui, boolean showScoreboard, boolean showDialog, boolean localScoreboardTarget, boolean showAdvancementEdit) {
        if (showAdvancementEdit) {
            return ReSyncResourceDragPayload.ADVANCEMENT_TREE;
        }
        if (showScoreboard || localScoreboardTarget) {
            return ReSyncResourceDragPayload.SCOREBOARD;
        }
        if (showDialog) {
            return ReSyncResourceDragPayload.DIALOG;
        }
        if (showGui) {
            return ReSyncResourceDragPayload.GUI;
        }
        return null;
    }

    @Unique
    private String remotely$getEditOverlayResourceId(boolean showGui, boolean showScoreboard, boolean showDialog, boolean localScoreboardTarget, boolean showAdvancementEdit, String advancementTreeId) {
        if (showAdvancementEdit) {
            return advancementTreeId;
        }
        if (showScoreboard && remotely$overlayResourceId != null && !remotely$overlayResourceId.isBlank()) {
            return remotely$overlayResourceId;
        }
        if (showDialog && remotely$overlayResourceId != null && !remotely$overlayResourceId.isBlank()) {
            return remotely$overlayResourceId;
        }
        if (showDialog && remotely$editOverlayResourceId != null && !remotely$editOverlayResourceId.isBlank()) {
            return remotely$editOverlayResourceId;
        }
        if (localScoreboardTarget) {
            return remotely$localScoreboardId;
        }
        if (showGui) {
            return remotely$overlayGuiId;
        }
        return null;
    }

    @Unique
    private void remotely$createAdvancementTreeFromOverlay() {
        FlowManager manager = remotely$getFlowManager();
        if (manager == null) {
            return;
        }
        ReSyncVanillaBridgeManager.getInstance().createStudioAdvancementTree(ReSyncVanillaBridgeManager.getInstance().getLiveServerId(), true, this);
    }

    @Unique
    private void remotely$openEditOverlayTarget() {
        FlowManager manager = remotely$getFlowManager();
        if (manager == null) {
            return;
        }
        String serverId = remotely$editOverlayServerId;
        String type = remotely$editOverlayResourceType;
        String id = remotely$editOverlayResourceId;
        if (serverId == null || serverId.isBlank() || type == null || type.isBlank() || id == null || id.isBlank()) {
            return;
        }
        if (ReSyncResourceDragPayload.SCOREBOARD.equals(type)) {
            id = manager.resolveScoreboardId(serverId, id);
        }
        ReSyncVanillaBridgeManager.getInstance().openStudioEditTarget(serverId, type, id, true, this);
    }

    @Unique
    private FlowManager remotely$getFlowManager() {
        return RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
    }

    @Unique
    private boolean remotely$isAdvancementsScreen() {
        return ((Object) this) instanceof AdvancementsScreen;
    }

    @Unique
    private boolean remotely$same(String first, String second) {
        if (first == null) {
            return second == null;
        }
        return first.equals(second);
    }

    @Unique
    private String remotely$getSelectedAdvancementTreeId(FlowManager manager, String serverId) {
        if (!remotely$isAdvancementsScreen() || manager == null || serverId == null || serverId.isBlank()) {
            return null;
        }
        AdvancementTab selected = ((AdvancementsScreenAccessor) this).remotely$getSelectedTab();
        String advancementId = remotely$getAdvancementTabId(selected);
        return remotely$getTreeIdForAdvancementId(advancementId);
    }

    @Unique
    private String remotely$getTreeIdForAdvancementId(String advancementId) {
        if (advancementId == null || advancementId.isBlank()) {
            return null;
        }
        int namespaceEnd = advancementId.indexOf(':');
        String namespace = namespaceEnd >= 0 ? advancementId.substring(0, namespaceEnd) : "minecraft";
        if (!"resync".equals(namespace)) {
            return null;
        }
        String path = namespaceEnd >= 0 ? advancementId.substring(namespaceEnd + 1) : advancementId;
        int treeEnd = path.indexOf('/');
        if (treeEnd <= 0) {
            return null;
        }
        return path.substring(0, treeEnd);
    }

    @Unique
    private String remotely$getAdvancementTabId(AdvancementTab tab) {
        if (tab == null) {
            return null;
        }
        try {
            //#if MC >= 1.21.1
            var root = ((AdvancementTabAccessor) tab).remotely$getRootNode();
            if (root == null || root.holder() == null || root.holder().id() == null) {
                return null;
            }
            return root.holder().id().toString();
            //#else
            //$$ var advancement = ((AdvancementTabAccessor) tab).remotely$getAdvancement();
            //$$ if (advancement == null || advancement.getId() == null) {
            //$$     return null;
            //$$ }
            //$$ return advancement.getId().toString();
            //#endif
        } catch (Exception ignored) {
            return null;
        }
    }

    @Unique
    private int[] remotely$getAdvancementsWindowBounds() {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        return new int[] { (screenWidth - 252) / 2, (screenHeight - 140) / 2, 252, 140 };
    }

    @Unique
    private boolean remotely$refreshOverlayState() {
        if (RemotelyClient.INSTANCE == null || RemotelyClient.INSTANCE.getFlowManager() == null) {
            return false;
        }
        FlowManager manager = RemotelyClient.INSTANCE.getFlowManager();
        if (((Object) this) instanceof AbstractContainerScreen && manager.isGuiOverlayEditable()) {
            remotely$overlayEditable = true;
            remotely$overlayServerId = manager.getGuiOverlayServerId();
            remotely$overlayResourceType = "gui";
            remotely$overlayResourceId = manager.getGuiOverlayGuiId();
            remotely$overlayGuiId = manager.getGuiOverlayGuiId();
            remotely$overlayFlowId = manager.getGuiOverlayFlowId();
        } else if (remotely$isEditTargetOverlayScreen() && manager.isEditTargetOverlayEditable()) {
            remotely$overlayEditable = true;
            remotely$overlayServerId = manager.getEditTargetOverlayServerId();
            remotely$overlayResourceType = manager.getEditTargetOverlayResourceType();
            remotely$overlayResourceId = manager.getEditTargetOverlayResourceId();
            remotely$overlayGuiId = null;
            remotely$overlayFlowId = manager.getEditTargetOverlayFlowId();
        } else {
            remotely$overlayEditable = false;
            remotely$overlayServerId = null;
            remotely$overlayResourceType = null;
            remotely$overlayResourceId = null;
            remotely$overlayGuiId = null;
            remotely$overlayFlowId = null;
        }
        remotely$overlayStateRevision = manager.getOverlayRevision();
        return true;
    }

    @Unique
    private boolean remotely$isChatScreen() {
        return ((Object) this) instanceof ChatScreen;
    }

    @Unique
    private boolean remotely$isDialogScreen() {
        //#if MC >= 1.21.6 || MC >= 26.1
        if (((Object) this) instanceof DialogScreen) {
            return true;
        }
        //#endif
        return ((Object) this).getClass().getName().startsWith("net.minecraft.client.gui.screens.dialog.");
    }

    @Unique
    private boolean remotely$isEditTargetOverlayScreen() {
        return remotely$isChatScreen() || remotely$isDialogScreen();
    }

    @Unique
    private int[] remotely$getDialogButtonBounds() {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        return new int[] { Math.max(6, screenWidth - 24), 6 };
    }

    @Unique
    private int[] remotely$getScoreboardBounds() {
        int[] liveBounds = remotely$getLiveScoreboardBounds();
        if (liveBounds != null) {
            return liveBounds;
        }
        ScoreboardDefinition scoreboard = remotely$getOverlayScoreboard();
        if (scoreboard == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int lineCount = scoreboard.getLines() != null ? Math.min(15, scoreboard.getLines().size()) : 0;
        if (lineCount <= 0) {
            return null;
        }
        int maxWidth = remotely$textWidth(scoreboard.getTitle());
        for (String line : scoreboard.getLines()) {
            maxWidth = Math.max(maxWidth, remotely$textWidth(line));
        }
        int width = Math.max(32, maxWidth + 7);
        int contentHeight = lineCount * 9;
        int bottom = screenHeight / 2 + contentHeight / 3;
        int top = bottom - lineCount * 9 - 10;
        int left = screenWidth - maxWidth - 5;
        return new int[] { left, Math.max(6, top), width, ((lineCount + 1) * 9) + 1 };
    }

    @Unique
    private int[] remotely$getLiveScoreboardBounds() {
        Objective objective = remotely$getSidebarObjective();
        if (objective == null) {
            return null;
        }
        Scoreboard scoreboard = objective.getScoreboard();
        //#if MC >= 1.21.1
        NumberFormat numberFormat = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        //#endif
        int maxWidth = Minecraft.getInstance().font.width(objective.getDisplayName());
        int lineCount = 0;
        //#if MC >= 1.21.1
        for (PlayerScoreEntry score : scoreboard.listPlayerScores(objective)) {
            if (score == null || score.isHidden()) {
                continue;
            }
            PlayerTeam team = scoreboard.getPlayersTeam(score.owner());
            Component name = PlayerTeam.formatNameForTeam(team, score.ownerName());
            Component value = score.formatValue(numberFormat);
            int valueWidth = Minecraft.getInstance().font.width(value);
            int lineWidth = Minecraft.getInstance().font.width(name);
            if (valueWidth > 0) {
                lineWidth += Minecraft.getInstance().font.width(": ") + valueWidth;
            }
            maxWidth = Math.max(maxWidth, lineWidth);
            lineCount++;
            if (lineCount >= 15) {
                break;
            }
        }
        //#else
        //$$ for (Score score : scoreboard.getPlayerScores(objective)) {
        //$$     if (score == null || score.getOwner() == null || score.getOwner().startsWith("#")) {
        //$$         continue;
        //$$     }
        //$$     PlayerTeam team = scoreboard.getPlayersTeam(score.getOwner());
        //$$     Component name = PlayerTeam.formatNameForTeam(team, Component.literal(score.getOwner()));
        //$$     String value = Integer.toString(score.getScore());
        //$$     int valueWidth = Minecraft.getInstance().font.width(value);
        //$$     int lineWidth = Minecraft.getInstance().font.width(name);
        //$$     if (valueWidth > 0) {
        //$$         lineWidth += Minecraft.getInstance().font.width(": ") + valueWidth;
        //$$     }
        //$$     maxWidth = Math.max(maxWidth, lineWidth);
        //$$     lineCount++;
        //$$     if (lineCount >= 15) {
        //$$         break;
        //$$     }
        //$$ }
        //#endif
        if (lineCount <= 0) {
            return null;
        }
        return remotely$scoreboardBoundsFromMetrics(maxWidth, lineCount);
    }

    @Unique
    private String remotely$getKnownLiveScoreboardId() {
        Objective objective = remotely$getSidebarObjective();
        if (objective == null || RemotelyClient.INSTANCE == null || RemotelyClient.INSTANCE.getFlowManager() == null) {
            return null;
        }
        String serverId = ReSyncVanillaBridgeManager.getInstance().getLiveServerId();
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        String scoreboardId = RemotelyClient.INSTANCE.getFlowManager().resolveScoreboardId(serverId, objective.getName());
        return RemotelyClient.INSTANCE.getFlowManager().getScoreboard(serverId, scoreboardId) != null ? scoreboardId : null;
    }

    @Unique
    private Objective remotely$getSidebarObjective() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        //#if MC >= 1.21.1
        return minecraft.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        //#else
        //$$ return minecraft.level.getScoreboard().getDisplayObjective(Scoreboard.DISPLAY_SLOT_SIDEBAR);
        //#endif
    }

    @Unique
    private int[] remotely$scoreboardBoundsFromMetrics(int maxWidth, int lineCount) {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int width = Math.max(32, maxWidth + 7);
        int contentHeight = lineCount * 9;
        int bottom = screenHeight / 2 + contentHeight / 3;
        int top = bottom - lineCount * 9 - 10;
        int left = screenWidth - maxWidth - 5;
        return new int[] { left, Math.max(6, top), width, ((lineCount + 1) * 9) + 1 };
    }

    @Unique
    private int[] remotely$getFallbackScoreboardButtonBounds() {
        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        return new int[] { screenWidth - 64, Math.max(6, screenHeight / 2 - 72), 58, 18 };
    }

    @Unique
    private ScoreboardDefinition remotely$getOverlayScoreboard() {
        if (RemotelyClient.INSTANCE == null || RemotelyClient.INSTANCE.getFlowManager() == null || remotely$overlayServerId == null || remotely$overlayResourceId == null) {
            return null;
        }
        return RemotelyClient.INSTANCE.getFlowManager().getScoreboard(remotely$overlayServerId, remotely$overlayResourceId);
    }

    @Unique
    private int remotely$textWidth(String text) {
        String normalized = text != null ? text.replaceAll("<[^>]*>", "").replaceAll("§.", "") : "";
        return Minecraft.getInstance().font.width(normalized);
    }

    @Unique
    private int[] remotely$getContainerBounds() {
        if (!((Object) this instanceof AbstractContainerScreen)) {
            return null;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) this;
        return new int[] { accessor.remotely$getLeftPos(), accessor.remotely$getTopPos(), accessor.remotely$getImageWidth() };
    }

    @Unique
    private void remotely$handleDebugKeys(int key, int modifiers) {
        ReKeyEvent event = ReInputEventFactory.keyPressed(this, null, key, 0, modifiers, false);
        boolean all = event.modifiers().alt() && event.modifiers().shift() && event.modifiers().control();

        if (event.key() == ReKey.D && all) {
            enableDebugTools = !enableDebugTools;
            new Notification("Toggled Debug Tools To " + enableDebugTools, Notification.Type.INFO);
        }
        if (!enableDebugTools) return;
        if (event.key() == ReKey.T && all) {
            ScreenManager.getInstance().setScreen(new WidgetsTestingScreen());
        }
        if (event.key() == ReKey.C && all) {
            ScreenManager.getInstance().setScreen(new ContainerTestingScreen());
        }
        if (event.key() == ReKey.P && all) {
            new Notification("ReProxy Tunnels", String.join(", ", ReProxyManager.listActiveTunnels()), Notification.Type.INFO);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"))
    //#if MC >= 1.21.9 || MC >= 26.1
     private void keyPressed(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
          remotely$handleDebugKeys(keyEvent.key(), keyEvent.modifiers());
      }
    //#else
    //$$ private void keyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
    //$$     remotely$handleDebugKeys(keyCode, modifiers);
    //$$ }
    //#endif

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    //#if MC >= 1.21.9 || MC >= 26.1
    private void keyPressedPinnedInGame(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        ScreenManager manager = ScreenManager.getInstance();
        //#if MC >= 26.3
        if (manager.keyPressedPinnedInGame(ReInputEventFactory.keyPressed(this, manager.getDesktopWindowsOverlay(), keyEvent.key(), keyEvent.keycode(), keyEvent.modifiers(), false))) {
        //#else
        //$$ if (manager.keyPressedPinnedInGame(ReInputEventFactory.keyPressed(this, manager.getDesktopWindowsOverlay(), keyEvent.key(), keyEvent.scancode(), keyEvent.modifiers(), false))) {
        //#endif
            cir.setReturnValue(true);
        }
    }
    //#else
    //$$ private void keyPressedPinnedInGame(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
    //$$     ScreenManager manager = ScreenManager.getInstance();
    //$$     if (manager.keyPressedPinnedInGame(ReInputEventFactory.keyPressed(this, manager.getDesktopWindowsOverlay(), keyCode, scanCode, modifiers, false))) {
    //$$         cir.setReturnValue(true);
    //$$     }
    //$$ }
    //#endif
}
