package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowWorkspaceDocument;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncEditorDiagnosticView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncResourceCreator;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioView;
import redxax.oxy.remotely.flow.ui.studio.RecipeSlotTarget;
import redxax.oxy.remotely.flow.ui.studio.RecipeStationLayout;
import redxax.oxy.remotely.flow.ui.studio.StudioCatalogRefreshView;
import redxax.oxy.remotely.flow.ui.studio.StudioHeaderProvider;
import redxax.oxy.remotely.flow.ui.studio.StudioOverlayView;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioPriorityInputView;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import redxax.oxy.remotely.flow.ui.studio.StudioSaveProvider;
import redxax.oxy.remotely.flow.ui.studio.StudioSelectorView;
import restudio.rescreen.game.MinecraftAssetReference;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.game.MinecraftGameEntities;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.game.MinecraftRenderItem;
import restudio.rescreen.render.Render;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.theme.Accent;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.IconMessage;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.CompactBindingWidget;
import restudio.rescreen.util.FileUtils;
import restudio.rescreen.util.Identifier;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.contract.EditorDiagnostic;
import restudio.resync.flow.contract.EditorError;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.ResourceManager;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static redxax.oxy.remotely.flow.ui.GuiEditOverlayState.snapshot;
import static restudio.rescreen.config.Config.desktopMode;

public abstract class FocusedJsonResourceDesignerScreen extends StudioScreen implements DesktopWindowBehaviorProvider, ReSyncStudioView, ReSyncCollaborativeView, ReSyncEditorDiagnosticView, StudioSelectorView, StudioOverlayView, StudioCatalogRefreshView, StudioPriorityInputView, StudioHeaderProvider, StudioSaveProvider {
    private static final Set<FocusedJsonResourceDesignerScreen> OPEN_SCREENS = BrowserSafeState.set();
    protected final String type;
    protected String id;
    protected JsonObject resource;
    protected final VersionedEditorDraft<JsonObject> resourceDraft;
    protected final String serverId;
    protected final Object parent;
    protected final StudioScreen host;
    private static final String MATERIAL_OPTIONS_SOURCE = "server:minecraft:material";
    private static final String RECIPE_ITEM_OPTIONS_SOURCE = "server:custom_content:recipe_item";
    private final List<AnimatedWidget> resourceHeaderActions = new ArrayList<>();
    private final Map<String, TextInputWidget> resourceFieldInputs = new LinkedHashMap<>();
    private final Map<String, CodeEditorWidget> resourceCodeFieldInputs = new LinkedHashMap<>();
    private final Map<String, ToggleWidget> resourceToggleFieldInputs = new LinkedHashMap<>();
    private final Map<String, DropDownWidget<String>> resourceDropdownFieldInputs = new LinkedHashMap<>();
    private final Map<String, AnimatedButton> resourceSelectorButtons = new LinkedHashMap<>();
    private final List<CompactBindingWidget> resourceBindingWidgets = new ArrayList<>();
    private final Map<AnimatedWidget, Accent> diagnosticAccents = new LinkedHashMap<>();
    private EditorError activeEditorError;
    private boolean resourcePanelMounted;
    protected String resourceLinkDraftMode = "";
    protected boolean resourceEditHistoryBatch;
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean resourceRebindPending;
    private volatile long resourceLifecycle = 1L;
    private boolean resourceDisposed;
    protected final StudioScreen.History<String> resourceEditHistory = history(this::resourceSnapshot, this::restoreResourceSnapshot);
    private final HistoryRebaseCoordinator<String> resourceHistoryRebase;

    protected record ResourcePanelSection(String title, List<String> fields) {
    }

    protected FocusedJsonResourceDesignerScreen(StudioScreen owner, String type, String id, JsonObject resource, String serverId, Object parent) {
        this.type = type;
        this.id = id;
        this.resource = resource != null ? resource : new JsonObject();
        this.serverId = serverId;
        this.parent = parent;
        this.host = owner != null ? owner : parent instanceof StudioScreen screen ? screen : null;
        this.resourceDraft = new VersionedEditorDraft<>(this.resource, JsonObject::toString,
            payload -> gson.fromJson(payload, JsonObject.class), this::rebindResource, this::failSnapshot,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        this.resourceHistoryRebase = new HistoryRebaseCoordinator<>(resourceEditHistory,
            () -> type + ":" + serverId + ":" + id, this::resourceLifecycle,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        OPEN_SCREENS.add(this);
        resourceHeaderActions.add(headerButton("save.png", "Save", this::save));
    }

    protected boolean hasResourceHistory() {
        return false;
    }

    @Override
    public JsonObject collaborationDocument() {
        return resource.deepCopy();
    }

    @Override
    public long collaborationLifecycle() {
        return resourceLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return resourceDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = resourceLifecycle;
        return resourceDraft.requestProjection(type, id, this::collaborationDocument,
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
        if (failure == null && !isResourceLifecycle(lifecycle)) {
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
        long lifecycle = resourceLifecycle;
        return resourceDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (resource == null) {
                throw new IllegalStateException("Resource Is Unavailable");
            }
            applyCollaborationDocument(document, patches);
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (document == null) {
            return;
        }
        if (resourceDraft.defer(() -> applyCollaborationDocument(document, patches))) {
            return;
        }
        resourceDraft.markMutation();
        boolean previousBatch = resourceEditHistoryBatch;
        resourceEditHistoryBatch = true;
        try {
            resource.keySet().clear();
            for (Map.Entry<String, JsonElement> entry : document.entrySet()) {
                resource.add(entry.getKey(), entry.getValue().deepCopy());
            }
            onResourceSnapshotRestored();
            reloadFields();
            if (!remountPanelOnFieldReload()) {
                refreshBindingWidgets();
            }
        } finally {
            resourceEditHistoryBatch = previousBatch;
        }
    }

    @Override
    public void rebaseCollaborationHistory(List<WorkspacePatch<JsonElement>> patches) {
        if (!hasResourceHistory() || patches == null || patches.isEmpty()) {
            return;
        }
        resourceHistoryRebase.request(patches, copiedPatches -> snapshot -> {
            JsonObject historic = gson.fromJson(gson.toJson(snapshot), JsonObject.class);
            if (historic == null) {
                historic = new JsonObject();
            }
            FlowWorkspaceDocument.apply(historic, copiedPatches);
            return gson.toJson(gson.fromJson(gson.toJson(historic), JsonObject.class));
        });
    }

    public static void refreshCatalogForServer(String serverId) {
        for (FocusedJsonResourceDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                screen.onStudioCatalogRefreshed();
            }
        }
    }

    public static void refreshFlowBindingsForServer(String serverId) {
        refreshFlowBindingsForServer(serverId, null);
    }

    public static boolean hasOpenScreenForServer(String serverId) {
        for (FocusedJsonResourceDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasFlowBindingForServer(String serverId, String flowId) {
        for (FocusedJsonResourceDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId) && screen.hasFlowBinding(flowId)) {
                return true;
            }
        }
        return false;
    }

    public static void refreshFlowBindingsForServer(String serverId, String flowId) {
        for (FocusedJsonResourceDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId)) {
                screen.refreshBindingWidgets(flowId);
            }
        }
    }

    public static void refreshMessageLogForServer(String serverId) {
        for (FocusedJsonResourceDesignerScreen screen : OPEN_SCREENS) {
            if (screen != null && serverId != null && serverId.equals(screen.serverId) && screen.usesMessageLog()) {
                screen.onMessageLogRefreshed();
            }
        }
    }

    @Override
    public void close() {
        disposeResourceDesigner();
        super.close();
    }

    @Override
    public void closed() {
        disposeResourceDesigner();
    }

    @Override
    public void removed() {
        disposeResourceDesigner();
        super.removed();
    }

    private void disposeResourceDesigner() {
        if (resourceDisposed) {
            return;
        }
        resourceDisposed = true;
        resourceLifecycle++;
        OPEN_SCREENS.remove(this);
        clearFocusedResourcePanelState();
        resourceHistoryRebase.close();
        resourceDraft.close();
    }

    @Override
    public void resourceRenamed(String type, String oldId, String newId) {
        if (resourceDraft.defer(() -> resourceRenamed(type, oldId, newId))) {
            return;
        }
        resourceDraft.markMutation();
        if (!this.type.equals(type) || !this.id.equals(oldId)) {
            return;
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        if (resourceType != null) {
            resourceType.applyRename(resource, newId);
        }
        id = newId;
    }

    public StudioScreen.History<String> resourceHistory() {
        return resourceEditHistory;
    }

    protected void onMessageLogRefreshed() {
    }

    protected boolean usesMessageLog() {
        return false;
    }

    private String resourceSnapshot() {
        return gson.toJson(resource);
    }

    protected void captureResourceSnapshot() {
        if (hasResourceHistory() && !resourceEditHistoryBatch && !resourceEditHistory.isRestoring()) {
            resourceEditHistory.capture();
        }
    }

    protected void applyResourceMutation(Runnable mutation) {
        if (mutation == null || deferResourceMutation(() -> applyResourceMutation(mutation))) {
            return;
        }
        mutation.run();
    }

    protected long resourceLifecycle() {
        return resourceLifecycle;
    }

    protected boolean isResourceLifecycle(long lifecycle) {
        return lifecycle == resourceLifecycle;
    }

    protected boolean deferResourceMutation(Runnable mutation) {
        if (resourceDraft.defer(mutation)) {
            return true;
        }
        resourceDraft.markMutation();
        return false;
    }

    private void restoreResourceSnapshot(String snapshot) {
        if (deferResourceMutation(() -> restoreResourceSnapshot(snapshot))) {
            return;
        }
        JsonObject restored = gson.fromJson(snapshot, JsonObject.class);
        List<String> keys = resource.entrySet().stream().map(Map.Entry::getKey).toList();
        for (String key : keys) {
            resource.remove(key);
        }
        if (restored != null) {
            for (Map.Entry<String, JsonElement> entry : restored.entrySet()) {
                resource.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        onResourceSnapshotRestored();
        reloadFields();
    }

    protected void onResourceSnapshotRestored() {
    }

    @Override
    public List<AnimatedWidget> headerButtons() {
        return resourceHeaderActions;
    }

    @Override
    public boolean hasPanel() {
        return true;
    }

    @Override
    public StudioPanel.Placement preferredPanelPlacement() {
        return StudioPanel.Placement.RIGHT;
    }

    @Override
    public void configurePanel(StudioPanel panel) {
        useStudioResourcePanel(panel);
        if (!resourcePanelMounted || !resourcePanelWidgetsMounted()) {
            mountResourcePanel();
            return;
        }
        refreshResourcePanelFields();
    }

    @Override
    protected void clearStudioResourcePanelWidgets() {
        super.clearStudioResourcePanelWidgets();
        clearFocusedResourcePanelState();
    }

    private void clearFocusedResourcePanelState() {
        resourcePanelMounted = false;
        resourceFieldInputs.clear();
        resourceCodeFieldInputs.clear();
        resourceToggleFieldInputs.clear();
        resourceDropdownFieldInputs.clear();
        resourceSelectorButtons.clear();
        resourceBindingWidgets.clear();
        studioResourcePanelWidgets.clear();
        studioResourcePanelKey = "";
        studioResourcePanelInputs.clear();
        studioResourcePanelToggles.clear();
        studioResourceStudioPanel = null;
        studioResourcePanel = null;
    }

    @Override
    public void resize(int width, int height) {
        int leftReserve = previewLeftReserve();
        this.x = 18 + leftReserve;
        this.y = 44;
        this.width = Math.max(120, width - 36 - leftReserve);
        this.height = Math.max(80, height - 62);
    }

    protected int previewLeftReserve() {
        return 0;
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        resourceHistoryRebase.drain();
        resourceDraft.drain();
        if (resourceRebindPending && resourceDraft.isSettled()) {
            resourceRebindPending = false;
            onResourceSnapshotRestored();
            reloadFields();
            if (!remountPanelOnFieldReload()) {
                refreshBindingWidgets();
            }
        }
        int text = ThemeManager.getColor(ThemeColor.text);
        int muted = ThemeManager.getColor(ThemeColor.textDark);
        renderPreviewCanvas(context, mouseX, mouseY, text, muted);
    }

    private void renderPreviewCanvas(IDrawContext context, int mouseX, int mouseY, int text, int muted) {
        int previewX = x + 12;
        int previewY = y + 12;
        int rightReserve = studioResourcePanel != null && studioResourcePanel.isVisible() && !studioResourcePanel.isLeftAnchored() ? studioResourcePanel.getDesiredWidth() + 10 : 0;
        int previewRightReserve = centeredTextPreview() ? 0 : rightReserve;
        int previewWidth = Math.max(160, x + width - previewRightReserve - previewX - 14);
        int previewHeight = Math.max(80, height - 24);
        renderResourcePreview(context, previewX, previewY, previewWidth, previewHeight, mouseX, mouseY, text, muted);
    }

    protected boolean centeredTextPreview() {
        return false;
    }

    protected void renderResourcePreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int mouseX, int mouseY, int text, int muted) {
        renderGenericRealPreview(context, previewX, previewY, previewWidth, previewHeight, text, muted);
    }

    protected MinecraftGameAssets getGameAssets() {
        if (RemotelyClient.INSTANCE != null && RemotelyClient.INSTANCE.getHost() != null) {
            MinecraftGameAssets gameAssets = RemotelyClient.INSTANCE.getHost().getGameAssets();
            if (gameAssets != null) {
                return gameAssets;
            }
        }
        return MinecraftGameAssets.EMPTY;
    }

    protected void drawMinecraftTexture(IDrawContext context, MinecraftGameAssets gameAssets, MinecraftAssetReference reference, Identifier fallbackId, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        if (MinecraftUiPreviewRenderer.drawAssetRegion(context, gameAssets, reference, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight)) {
            return;
        }
        MinecraftUiPreviewRenderer.drawImage(context, fallbackId, x, y, width, height);
    }

    protected boolean drawMinecraftSprite(IDrawContext context, MinecraftGameAssets gameAssets, String sprite, int x, int y, int width, int height) {
        return MinecraftUiPreviewRenderer.drawSprite(context, gameAssets, sprite, x, y, width, height);
    }

    protected void renderGenericRealPreview(IDrawContext context, int previewX, int previewY, int previewWidth, int previewHeight, int text, int muted) {
        int centerY = previewY + previewHeight / 2;
        context.drawText(resourceDisplayName(), previewX + 24, centerY - 10, text, false);
        context.drawText("Ready", previewX + 24, centerY + 8, muted, false);
    }

    protected boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return width > 0 && height > 0 && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    protected void save() {
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        FlowManager manager = FlowManager.getInstance();
        if (resourceType != null) {
            sanitizeLegacyResourceFields();
            String resourceId = resourceType.extractId(resource);
            DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, resourceType,
                resourceId, resourceDisplayName());
            observeSave(ticket);
            if (manager == null || serverId == null) {
                DesignerSaveNotifications.failExact(ticket, "ReSync Offline");
                return;
            }
            if (!resourceDraft.capture(resourceType.typeId(), resourceId, ticket,
                snapshot -> manager.saveJsonResource(serverId, resourceType,
                    snapshot.serialize(payload -> gson.fromJson(payload, JsonObject.class)), ticket))) {
                DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
            }
        }
    }

    @Override
    public boolean requestStudioSave() {
        save();
        return true;
    }

    private void rebindResource(JsonObject previous, JsonObject replacement) {
        JsonObject currentResource = resource;
        boolean currentResourceRebindPending = resourceRebindPending;
        if (previous == replacement) {
            resource = previous;
            return;
        }
        try {
            resource = replacement;
            resourceRebindPending = true;
        } catch (RuntimeException | Error exception) {
            resource = currentResource;
            resourceRebindPending = currentResourceRebindPending;
            throw exception;
        }
    }

    private void failSnapshot(VersionedEditorDraft.Failure failure) {
        if (failure.stage() == VersionedEditorDraft.Stage.REBASE) {
            new Notification("Save Refresh Failed", "Save Paused", Notification.Type.ERROR);
            return;
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(failure.key().type());
        if (resourceType != null && failure.request() instanceof DesignerSaveNotifications.SaveTicket ticket) {
            DesignerSaveNotifications.failExact(ticket, "Save Snapshot Failed");
        }
    }

    private void observeSave(DesignerSaveNotifications.SaveTicket ticket) {
        if (ticket == null) {
            return;
        }
        ticket.whenFinished((saved, current) -> ScreenManager.getInstance().execute(() -> {
            if (!saved || !current) {
                resourceDraft.discard(ticket);
                return;
            }
            FlowManager manager = FlowManager.getInstance();
            FlowManager.ResourceReadLease lease = manager != null
                ? manager.snapshotResource(serverId, type, ticket.id()) : null;
            if (lease == null || !resourceDraft.acknowledge(ticket, lease::materialize)) {
                resourceDraft.discard(ticket);
            }
        }));
    }

    protected void sanitizeLegacyResourceFields() {
    }

    protected void reloadFields() {
        if (!remountPanelOnFieldReload()) {
            refreshResourcePanelFields();
            return;
        }
        mountResourcePanel();
    }

    protected boolean remountPanelOnFieldReload() {
        return false;
    }

    protected void mountResourcePanel() {
        if (studioResourcePanel == null) {
            return;
        }
        diagnosticAccents.clear();
        resourceFieldInputs.clear();
        resourceCodeFieldInputs.clear();
        resourceToggleFieldInputs.clear();
        resourceDropdownFieldInputs.clear();
        resourceSelectorButtons.clear();
        resourceBindingWidgets.clear();
        studioResourcePanelWidgets.clear();
        studioResourcePanelKey = ReSyncProjectMetadata.resourceKey(type, id);
        buildResourcePanel();
        resourcePanelMounted = true;
        applyEditorDiagnostics();
    }

    @Override
    public void applyEditorError(EditorError error) {
        clearDiagnosticAccents();
        activeEditorError = error;
        applyEditorDiagnostics();
    }

    @Override
    public void clearEditorError() {
        clearDiagnosticAccents();
        activeEditorError = null;
    }

    private void applyEditorDiagnostics() {
        if (activeEditorError == null) {
            return;
        }
        AnimatedWidget first = null;
        for (EditorDiagnostic diagnostic : activeEditorError.diagnostics()) {
            AnimatedWidget widget = diagnosticWidget(diagnostic);
            if (widget == null) {
                continue;
            }
            diagnosticAccents.putIfAbsent(widget, widget.accentType);
            widget.setAccent(ThemeManager.getAccent("danger"));
            if (first == null) {
                first = widget;
            }
        }
        if (first == null && getFocusedWidget() instanceof AnimatedWidget focused) {
            diagnosticAccents.putIfAbsent(focused, focused.accentType);
            focused.setAccent(ThemeManager.getAccent("danger"));
            first = focused;
        }
        if (first != null) {
            setFocusedWidget(first);
        }
    }

    private AnimatedWidget diagnosticWidget(EditorDiagnostic diagnostic) {
        if (diagnostic == null) {
            return null;
        }
        String field = !diagnostic.field().isBlank() ? diagnostic.field() : diagnostic.path();
        String normalized = normalizeDiagnosticField(field);
        if (normalized.isBlank()) {
            return null;
        }
        for (Map<? extends String, ? extends AnimatedWidget> widgets : List.of(resourceFieldInputs, resourceCodeFieldInputs,
            resourceToggleFieldInputs, resourceDropdownFieldInputs, resourceSelectorButtons)) {
            for (Map.Entry<? extends String, ? extends AnimatedWidget> entry : widgets.entrySet()) {
                if (normalizeDiagnosticField(entry.getKey()).equals(normalized)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String normalizeDiagnosticField(String value) {
        String field = value != null ? value : "";
        int separator = Math.max(field.lastIndexOf('/'), field.lastIndexOf('.'));
        if (separator >= 0 && separator + 1 < field.length()) {
            field = field.substring(separator + 1);
        }
        return field.replaceAll("\\[[^]]*]", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private void clearDiagnosticAccents() {
        diagnosticAccents.forEach(AnimatedWidget::setAccent);
        diagnosticAccents.clear();
    }

    protected void buildResourcePanel() {
        if (studioResourcePanel == null) {
            return;
        }
        int rowWidth = studioPanelState.rowWidth(studioResourcePanel);
        List<String> fields = editorFields().stream().filter(field -> !"id".equals(field) && !"enabled".equals(field)).toList();
        preloadFieldCatalogs(fields);
        List<AnimatedWidget> widgets = new ArrayList<>();
        MountableButtonWidget summary = new MountableButtonWidget.Builder(resourceDisplayName())
            .description(resourceSummary())
            .iconPath(studioResourceIconPath(type, id))
            .build();
        summary.setSize(rowWidth, 30);
        widgets.add(summary);
        for (ResourcePanelSection section : editorSections(fields)) {
            if (!section.title().isBlank()) {
                widgets.add(studioPanelState.hint(section.title(), rowWidth));
            }
            for (String field : section.fields()) {
                AnimatedWidget row = fieldRow(field, rowWidth);
                ReSyncStudioPanelState.identify(row, "resource-field:" + field);
                widgets.add(row);
            }
        }
        appendResourcePanelWidgets(widgets, rowWidth);
        if (resourcePanelSaveButtonVisible()) {
            widgets.add(panelSaveButton(this::save));
        }
        for (AnimatedWidget widget : widgets) {
            ReSyncStudioPanelState.disableEntrance(widget);
        }
        studioResourcePanel.container().replaceWidgets(widgets);
        studioResourcePanelWidgets.clear();
        studioResourcePanelWidgets.addAll(widgets);
    }

    protected void appendResourcePanelWidgets(List<AnimatedWidget> widgets, int rowWidth) {
    }

    protected boolean resourcePanelSaveButtonVisible() {
        return true;
    }

    protected boolean resourcePanelWidgetsMounted() {
        return studioResourcePanel != null
            && !studioResourcePanelWidgets.isEmpty()
            && studioResourcePanel.container().getWidgets().containsAll(studioResourcePanelWidgets);
    }

    protected List<ResourcePanelSection> editorSections(List<String> fields) {
        return List.of(new ResourcePanelSection("", fields));
    }

    protected List<ResourcePanelSection> appendRemainingSections(List<ResourcePanelSection> sections, List<String> fields) {
        Set<String> used = new LinkedHashSet<>();
        List<ResourcePanelSection> result = new ArrayList<>();
        for (ResourcePanelSection section : sections) {
            if (!section.fields().isEmpty()) {
                result.add(section);
                used.addAll(section.fields());
            }
        }
        List<String> remaining = fields.stream().filter(field -> !used.contains(field)).toList();
        if (!remaining.isEmpty()) {
            result.add(new ResourcePanelSection("Advanced", remaining));
        }
        return result;
    }

    protected void refreshResourcePanelFields() {
        for (Map.Entry<String, TextInputWidget> entry : resourceFieldInputs.entrySet()) {
            TextInputWidget input = entry.getValue();
            String value = jsonPathText(entry.getKey());
            if (input != null && !input.isFocused() && !Objects.equals(input.getText(), value)) {
                input.setText(value);
            }
        }
        for (Map.Entry<String, CodeEditorWidget> entry : resourceCodeFieldInputs.entrySet()) {
            CodeEditorWidget input = entry.getValue();
            String value = jsonPathText(entry.getKey());
            if (input != null && !input.isFocused() && !Objects.equals(input.getText(), value)) {
                input.setText(value);
            }
        }
        for (Map.Entry<String, ToggleWidget> entry : resourceToggleFieldInputs.entrySet()) {
            ToggleWidget toggle = entry.getValue();
            String configured = jsonPathText(entry.getKey());
            boolean value = configured.isBlank() ? "enabled".equals(entry.getKey()) : Boolean.parseBoolean(configured);
            if (toggle != null && toggle.getValue() != value) {
                toggle.setValue(value);
            }
        }
        for (Map.Entry<String, DropDownWidget<String>> entry : resourceDropdownFieldInputs.entrySet()) {
            DropDownWidget<String> dropdown = entry.getValue();
            List<String> options = selectorOptions(entry.getKey());
            String value = resolveSelectedOption(options, jsonPathText(entry.getKey()));
            if (dropdown != null && !Objects.equals(dropdown.getSelectedItem(), value)) {
                dropdown.setSelectedItem(value);
            }
        }
        for (Map.Entry<String, AnimatedButton> entry : resourceSelectorButtons.entrySet()) {
            AnimatedButton button = entry.getValue();
            String selected;
            String label;
            if (isRecipeItemSelectorField(entry.getKey())) {
                selected = jsonPathText(entry.getKey());
                label = recipeItemSelectorLabel(selected);
            } else {
                List<String> options = selectorOptions(entry.getKey());
                selected = resolveSelectedOption(normalizedSelectorOptions(options, jsonPathText(entry.getKey())), jsonPathText(entry.getKey()));
                label = isRealOption(selected) ? selectorLabel(entry.getKey(), selected) : "Select";
            }
            if (button != null && !Objects.equals(button.getMessage(), label)) {
                button.setMessage(label);
            }
        }
    }

    protected AnimatedWidget fieldRow(String field, int rowWidth) {
        String label = fieldLabel(field);
        AnimatedWidget customRow = customFieldRow(field, label, rowWidth);
        if (customRow != null) {
            return customRow;
        }
        if (runtimeFunctionBindingField(field)) {
            return runtimeFunctionBindingRow(field, label, rowWidth);
        }
        if (resourceLinkBindingField(field)) {
            return resourceLinkBindingRow(field, label, rowWidth);
        }
        if (flowBindingField(field)) {
            return flowBindingRow(field, label, rowWidth);
        }
        if ("conditions.world".equals(field)) {
            return worldConditionFieldRow(label, rowWidth);
        }
        if (toggleField(field)) {
            return toggleFieldRow(field, label, rowWidth);
        }
        if (dropdownField(field)) {
            return dropdownFieldRow(field, label, rowWidth);
        }
        if (isRecipeItemSelectorField(field)) {
            return recipeItemFieldRow(field, label, rowWidth);
        }
        List<String> selectorOptions = selectorOptions(field);
        if (!selectorOptions.isEmpty() || selectorCatalogSource(field) != null) {
            return searchableFieldRow(field, label, selectorOptions, rowWidth);
        }
        if (isCodeField(field)) {
            int editorHeight = codeFieldHeight(field);
            CodeEditorWidget input = new CodeEditorWidget(0, 0, rowWidth, editorHeight);
            input.setText(jsonPathText(field));
            input.onChange = value -> putJsonText(field, input.getText());
            ReSyncStudioPanelState.disableEntrance(input);
            resourceCodeFieldInputs.put(field, input);
            return studioPanelState.codeRow(label, input, rowWidth, editorHeight + 18, jsonResourceDescription(field, label));
        }
        TextInputWidget input = new TextInputWidget.Builder()
            .text(jsonPathText(field))
            .placeholder(label)
            .forcePlaceholder(false)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(value -> putJsonText(field, value))
            .build();
        ReSyncStudioPanelState.disableEntrance(input);
        resourceFieldInputs.put(field, input);
        return studioPanelState.row(label, input, rowWidth, jsonResourceDescription(field, label));
    }

    protected AnimatedWidget customFieldRow(String field, String label, int rowWidth) {
        return null;
    }

    protected void registerResourceSelectorButton(String field, AnimatedButton button) {
        resourceSelectorButtons.put(field, button);
    }

    protected void registerResourceBindingWidget(CompactBindingWidget widget) {
        resourceBindingWidgets.add(widget);
    }

    protected AnimatedWidget recipeItemFieldRow(String field, String label, int rowWidth) {
        ensureRecipeItemCatalogLoaded();
        String selected = jsonPathText(field);
        AnimatedButton button = new AnimatedButton.Builder()
            .label(recipeItemSelectorLabel(selected))
            .size(174, 18)
            .entranceAnimation(false)
            .build();
        resourceSelectorButtons.put(field, button);
        button.setAction(() -> showRecipeMaterialSelector(field, button.getX(), button.getY() + button.getHeight()));
        return studioPanelState.row(label, button, rowWidth, jsonResourceDescription(field, label));
    }

    protected boolean toggleField(String field) {
        return "enabled".equals(field) || "allowMiniMessage".equals(field) || "channel.allowMiniMessage".equals(field)
            || "ai".equals(field) || "gravity".equals(field) || "invulnerable".equals(field) || "followPlayer".equals(field);
    }

    protected AnimatedWidget toggleFieldRow(String field, String label, int rowWidth) {
        String configured = jsonPathText(field);
        boolean value = configured.isBlank() ? defaultToggleValue(field) : Boolean.parseBoolean(configured);
        ToggleWidget toggle = new ToggleWidget.Builder()
            .label("")
            .toggled(value)
            .size(46, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onChange(val -> putJsonText(field, String.valueOf(val)))
            .build();
        ReSyncStudioPanelState.disableEntrance(toggle);
        resourceToggleFieldInputs.put(field, toggle);
        return studioPanelState.row(label, toggle, rowWidth, jsonResourceDescription(field, label));
    }

    protected boolean defaultToggleValue(String field) {
        return "enabled".equals(field) || "gravity".equals(field) || "invulnerable".equals(field);
    }

    protected boolean dropdownField(String field) {
        return customDropdownField(field);
    }

    protected boolean customDropdownField(String field) {
        return false;
    }

    protected AnimatedWidget dropdownFieldRow(String field, String label, int rowWidth) {
        List<String> options = selectorOptions(field);
        String selected = resolveSelectedOption(options, jsonPathText(field));
        DropDownWidget<String> dropdown = new DropDownWidget.Builder<>(options)
            .displayFunction(this::formatOptionLabel)
            .selectedItem(selected)
            .size(rowWidth, ReSyncStudioPanelState.FIELD_HEIGHT)
            .onSelectionChanged(value -> {
                putJsonText(field, value);
                onDropdownSelectionChanged(field, value);
                if (rebuildOnSelection(field)) {
                    reloadFields();
                }
            })
            .build();
        ReSyncStudioPanelState.disableEntrance(dropdown);
        resourceDropdownFieldInputs.put(field, dropdown);
        return studioPanelState.row(label, dropdown, rowWidth, jsonResourceDescription(field, label));
    }

    protected void onDropdownSelectionChanged(String field, String value) {
    }

    protected void onSelectorValueChanged(String field, String value) {
    }

    protected boolean flowBindingField(String field) {
        return field != null && ("flowId".equals(field) || field.endsWith(".flowId") || "flowPredicate".equals(field) || field.endsWith("Flow") || field.contains("Flow"));
    }

    protected boolean runtimeFunctionBindingField(String field) {
        return field != null && field.startsWith("hooks.") && field.endsWith("Action");
    }

    protected AnimatedWidget runtimeFunctionBindingRow(String field, String label, int rowWidth) {
        CompactBindingWidget widget = new CompactBindingWidget.Builder(
            hostScreen(),
            List.of("None", "Function"),
            () -> jsonPathHas(field) ? "Function" : "None",
            mode -> {
                if ("Function".equals(mode)) {
                    ensureFunctionCall(field);
                } else {
                    removeJsonPath(field);
                }
                refreshResourcePanelFields();
            },
            () -> jsonPathHas(field) ? functionOptions() : List.of("none"),
            () -> jsonPathTextRaw(field + ".functionId"),
            value -> {
                ensureFunctionCall(field);
                putFunctionIdPathText(field + ".functionId", value);
                refreshResourcePanelFields();
            },
            () -> compactFunctionBindingInputs(field, runtimeFunctionShape(field)),
            () -> openFunctionBinding(field)
        )
            .createAction("Create New", () -> jsonPathHas(field), () -> createFunctionBindingTarget(field, runtimeFunctionShape(field)))
            .animationKey("json." + type + "." + field)
            .size(rowWidth, 18)
            .entranceAnimation(false)
            .build();
        resourceBindingWidgets.add(widget);
        ReSyncStudioPanelState.disableEntrance(widget);
        return studioPanelState.row(label, widget, rowWidth, jsonResourceDescription(field, label));
    }

    protected boolean resourceLinkBindingField(String field) {
        return "links".equals(field);
    }

    protected AnimatedWidget resourceLinkBindingRow(String field, String label, int rowWidth) {
        CompactBindingWidget widget = new CompactBindingWidget.Builder(
            hostScreen(),
            List.of("None", "Dialog", "Trade", "Loot Table"),
            this::resourceLinkMode,
            mode -> {
                if ("None".equals(mode)) {
                    resourceLinkDraftMode = "";
                    removeJsonPath("links");
                    removeJsonPath("dialog");
                    removeJsonPath("tradeProfile");
                    removeJsonPath("lootTable");
                } else {
                    resourceLinkDraftMode = mode;
                    removeJsonPath("links");
                    removeJsonPath("dialog");
                    removeJsonPath("tradeProfile");
                    removeJsonPath("lootTable");
                    ensureJsonPathText(resourceLinkField(mode), "");
                }
                refreshResourcePanelFields();
            },
            () -> {
                String mode = resourceLinkMode();
                return "None".equals(mode) ? List.of("none") : selectorOptions(resourceLinkField(mode));
            },
            () -> {
                String mode = resourceLinkMode();
                return "None".equals(mode) ? "" : resourceLinkText(resourceLinkField(mode), legacyResourceLinkField(mode));
            },
            value -> {
                String mode = resourceLinkMode();
                if (!"None".equals(mode)) {
                    putJsonText(resourceLinkField(mode), value);
                }
                refreshResourcePanelFields();
            },
            () -> List.of(),
            this::openLinkedResource
        )
            .animationKey("json." + type + "." + field)
            .size(rowWidth, 18)
            .entranceAnimation(false)
            .build();
        resourceBindingWidgets.add(widget);
        ReSyncStudioPanelState.disableEntrance(widget);
        return studioPanelState.row(label, widget, rowWidth, jsonResourceDescription(field, label));
    }

    protected AnimatedWidget flowBindingRow(String field, String label, int rowWidth) {
        CompactBindingWidget widget = new CompactBindingWidget.Builder(
            hostScreen(),
            List.of("None", "Flow"),
            () -> hasConfiguredJsonText(field) ? "Flow" : "None",
            mode -> {
                if ("Flow".equals(mode)) {
                    ensureJsonPathText(field, "");
                } else {
                    putJsonText(field, "");
                }
                refreshResourcePanelFields();
            },
            this::flowOptions,
            () -> jsonPathTextRaw(field),
            value -> {
                putJsonText(field, value);
                refreshResourcePanelFields();
            },
            () -> List.of(),
            () -> {
                String flowId = jsonPathTextRaw(field);
                if (!flowId.isBlank()) {
                    if (host != null) {
                        host.openWorkspaceFlowEditor(flowId);
                    }
                }
            }
        )
            .createAction("Create New", () -> hasConfiguredJsonText(field), () -> createFlowBindingTarget(field))
            .animationKey("json." + type + "." + field)
            .size(rowWidth, 18)
            .entranceAnimation(false)
            .build();
        resourceBindingWidgets.add(widget);
        ReSyncStudioPanelState.disableEntrance(widget);
        return studioPanelState.row(label, widget, rowWidth, jsonResourceDescription(field, label));
    }

    protected void refreshBindingWidgets() {
        refreshBindingWidgets(null);
    }

    protected void refreshBindingWidgets(String flowId) {
        for (CompactBindingWidget widget : resourceBindingWidgets) {
            if (widget != null && (flowId == null || widget.referencesTarget(flowId))) {
                widget.refresh();
            }
        }
    }

    protected boolean hasFlowBinding(String flowId) {
        for (CompactBindingWidget widget : resourceBindingWidgets) {
            if (widget != null && widget.referencesTarget(flowId)) {
                return true;
            }
        }
        return false;
    }

    protected boolean hasConfiguredJsonText(String field) {
        String value = jsonPathTextRaw(field);
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value);
    }

    protected boolean hasConfiguredJsonArray(String field) {
        JsonElement element = jsonPathElement(field);
        return element != null && element.isJsonArray() && element.getAsJsonArray().size() > 0;
    }

    protected List<CompactBindingWidget.BindingInput> compactFunctionBindingInputs(String functionBase, CompactBindingSupport.FunctionShape shape) {
        FlowGraph function = selectedFunction(functionBase + ".functionId", shape);
        if (function == null || function.getFunctionInputs() == null || function.getFunctionInputs().isEmpty()) {
            return List.of();
        }
        List<CompactBindingWidget.BindingInput> inputs = new ArrayList<>();
        for (FlowGraph.FunctionParameter input : function.getFunctionInputs()) {
            if (input == null || input.getName() == null || input.getName().isBlank()) {
                continue;
            }
            String field = functionBase + ".inputs." + input.getName();
            inputs.add(new CompactBindingWidget.BindingInput(
                input.getName(),
                functionInputLabel(input.getName()),
                jsonPathText(field),
                input.getType() != null ? input.getType().getColor() : FlowDataType.ANY.getColor(),
                () -> functionInputOptions(field, input),
                value -> putJsonText(field, value),
                input.getType() != null && FlowDataType.BOOLEAN.isAssignableFrom(input.getType()) ? CompactBindingWidget.InputKind.BOOLEAN : CompactBindingWidget.InputKind.TEXT,
                () -> CompactBindingSupport.functionInputChoices(serverId, input, functionInputOptions(field, input))
            ));
        }
        return inputs;
    }

    protected List<String> functionInputOptions(String field, FlowGraph.FunctionParameter input) {
        if (input == null) {
            return List.of();
        }
        List<String> options = new ArrayList<>(CompactBindingSupport.functionInputOptions(input, functionInputContext(field)));
        String source = input.getOptionsSource();
        if (source != null && !source.isBlank()) {
            for (String option : catalogOptions(source)) {
                if (option != null && !option.isBlank() && !options.contains(option)) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    protected void openFunctionBinding(String functionBase) {
        String id = jsonPathTextRaw(functionBase + ".functionId");
        if (!id.isBlank() && host != null) {
            host.openWorkspaceResource(ReSyncResourceDragPayload.FUNCTION, id);
        }
    }

    protected void openLinkedResource(String field) {
        openLinkedResource();
    }

    protected void openLinkedResource() {
        String mode = resourceLinkMode();
        if ("None".equals(mode) || host == null) {
            return;
        }
        String field = resourceLinkField(mode);
        String id = resourceLinkText(field, legacyResourceLinkField(mode));
        if (id.isBlank()) {
            return;
        }
        host.openWorkspaceResource(linkedResourceType(field), id);
    }

    protected void createFlowBindingTarget(String field) {
        createBindingResource(ReSyncResourceDragPayload.FLOW, id -> {
            putJsonText(field, id);
            refreshResourcePanelFields();
        });
    }

    protected void createFunctionBindingTarget(String functionBase, CompactBindingSupport.FunctionShape shape) {
        createBindingResource(ReSyncResourceDragPayload.FUNCTION, id -> {
            normalizeBindingFunction(id, shape);
            putJsonText(functionBase + ".functionId", id);
            refreshResourcePanelFields();
        });
    }

    protected void createBindingResource(String type, Consumer<String> onCreated) {
        ReSyncResourceCreator.showCreatePopup(hostScreen(), serverId, type, bindingCreateFolder(), null, result -> {
            if (onCreated != null) {
                onCreated.accept(result.id());
            }
            if (host != null) {
                host.refreshStudioWorkspace(true);
                host.openWorkspaceResource(result.type(), result.id());
            }
        });
    }

    protected CompactBindingSupport.FunctionShape runtimeFunctionShape(String functionBase) {
        return CompactBindingSupport.npcActionShape();
    }

    protected String linkedResourceType(String field) {
        return switch (field) {
            case "links.dialog", "dialog" -> ReSyncResourceDragPayload.DIALOG;
            case "links.lootTable", "lootTable" -> ReSyncResourceDragPayload.LOOT_TABLE;
            case "links.tradeProfile", "tradeProfile" -> ReSyncResourceDragPayload.TRADE_PROFILE;
            default -> ReSyncResourceDragPayload.FLOW;
        };
    }

    protected String resourceLinkMode() {
        if (!resourceLinkDraftMode.isBlank()) {
            return resourceLinkDraftMode;
        }
        if (hasConfiguredJsonText("links.dialog") || hasConfiguredJsonText("dialog")) {
            return "Dialog";
        }
        if (hasConfiguredJsonText("links.tradeProfile") || hasConfiguredJsonText("tradeProfile")) {
            return "Trade";
        }
        if (hasConfiguredJsonText("links.lootTable") || hasConfiguredJsonText("lootTable")) {
            return "Loot Table";
        }
        return "None";
    }

    protected String resourceLinkField(String mode) {
        return switch (mode) {
            case "Dialog" -> "links.dialog";
            case "Trade" -> "links.tradeProfile";
            case "Loot Table" -> "links.lootTable";
            default -> "";
        };
    }

    protected String legacyResourceLinkField(String mode) {
        return switch (mode) {
            case "Dialog" -> "dialog";
            case "Trade" -> "tradeProfile";
            case "Loot Table" -> "lootTable";
            default -> "";
        };
    }

    protected void normalizeBindingFunction(String functionId, CompactBindingSupport.FunctionShape shape) {
        CompactBindingSupport.initializeFunction(serverId, functionId, shape);
    }

    protected String bindingCreateFolder() {
        FlowManager manager = FlowManager.getInstance();
        ReSyncProjectMetadata.ResourceEntry entry = manager != null ? manager.getProjectResource(serverId, type, id) : null;
        return entry != null ? entry.getPath() : "";
    }

    protected void ensureJsonPathText(String field, String value) {
        if (deferResourceMutation(() -> ensureJsonPathText(field, value))) {
            return;
        }
        if (field == null || field.isBlank()) {
            return;
        }
        if (!field.contains(".")) {
            resource.addProperty(field, value != null ? value : "");
            return;
        }
        putJsonPathText(field, value != null ? value : "");
    }

    protected void ensureFunctionCall(String basePath) {
        if (deferResourceMutation(() -> ensureFunctionCall(basePath))) {
            return;
        }
        JsonObject call = ensureJsonPathObject(basePath);
        call.addProperty("type", "functionRef");
        if (!call.has("functionId")) {
            call.addProperty("functionId", "");
        }
    }

    protected JsonObject ensureJsonPathObject(String field) {
        if (field == null || field.isBlank()) {
            return resource;
        }
        String[] parts = field.split("\\.");
        JsonObject current = resource;
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!current.has(part) || !current.get(part).isJsonObject()) {
                current.add(part, new JsonObject());
            }
            current = current.getAsJsonObject(part);
        }
        return current;
    }

    protected boolean jsonPathHas(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        return jsonPathElement(field) != null;
    }

    protected String functionInputLabel(String name) {
        String cleaned = name == null ? "" : name.replace('_', ' ').trim();
        return cleaned.isBlank() ? "Input" : Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    protected AnimatedWidget worldConditionFieldRow(String label, int rowWidth) {
        List<String> selected = worldConditionValues();
        List<String> options = normalizedWorldOptions(catalogOptions("server:minecraft:world"), selected);
        DropDownWidget<String> dropdown = new DropDownWidget.Builder<>(options)
            .multiSelect(true)
            .size(174, 18)
            .maxVisibleItems(8)
            .onMultiSelectionChanged(widget -> putWorldConditionValues(widget.getSelectedItems()))
            .build();
        dropdown.setSelectedItems(selected, List.of());
        ReSyncStudioPanelState.disableEntrance(dropdown);
        return studioPanelState.row(label, dropdown, rowWidth, jsonResourceDescription("conditions.world", label));
    }

    protected String jsonResourceDescription(String field, String label) {
        String key = field == null ? label : field;
        return switch (key) {
            case "channel.prefix" -> "Channel prefix.\nShown before sender/message text in this chat profile.\nUse it for labels, ranks, or routing hints.";
            case "format.template" -> "Public chat layout.\nPlaceholders: {prefix}, {sender}, {message}.\nKeep the sender and message clearly readable.";
            case "channel.range" -> "Hearing range in blocks.\nNegative values mean global chat.\nPositive values only reach nearby viewers.";
            case "channel.speakPermission" -> "Permission required to send in this chat profile.\nEmpty means every player can speak.";
            case "channel.readPermission" -> "Permission required to receive this chat profile.\nEmpty means every player can read it.";
            case "channel.allowMiniMessage" -> "Allows MiniMessage parsing for player chat.\nKeep off unless players should be able to style messages.";
            case "channel.miniMessagePermission" -> "Permission required for player-authored MiniMessage.\nUsed only when Allow MiniMessage is on.";
            case "rule.contains" -> "Chat match text.\nWhen present, this chat rule runs only if the message contains it.\nLeave empty to apply the rule broadly.";
            case "rule.action" -> "Chat rule action.\nblock stops the message.\nreplace rewrites it.\nflow runs logic.\nchannel redirects it.";
            case "rule.replacement" -> "Replacement chat text.\nUse {message} to keep the original message inside the rewritten output.";
            case "rule.channel" -> "Redirect channel id.\nUsed when Action is channel.\nMust match another chat profile id.";
            case "rule.flowId" -> "Flow run by this chat rule.\nReceives the current sender, message, and channel context.";
            case "privateMessages.sender" -> "Private-message sender format.\nShown to the player sending the message.\nPlaceholders: {sender}, {receiver}, {message}.";
            case "privateMessages.receiver" -> "Private-message receiver format.\nShown to the player receiving the message.\nPlaceholders: {sender}, {receiver}, {message}.";
            case "privateMessages.spy" -> "Private-message spy format.\nShown to enabled spies who can see this sender/receiver pair.";
            case "privateMessages.privateMessageFlow" -> "Flow run after a private message is sent.\nReceives event.message and event.receiver.";
            case "mention.template" -> "Mention style.\nUse {player} where the mentioned player name should appear.";
            case "mention.mentionFlow" -> "Flow run when a player mention is applied.\nReceives mention/player context from chat handling.";
            case "ignorePlayersText" -> "Globally ignored players for this chat profile.\nOne name or UUID per line.\nMatching senders are hidden from receivers.";
            case "sender" -> "Private-message sender format.\nPlaceholders:\n{sender} Sender name.\n{receiver} Receiver name.\n{message} Message text after mention parsing.";
            case "receiver" -> "Private-message receiver format.\nPlaceholders:\n{sender} Sender name.\n{receiver} Receiver name.\n{message} Message text after mention parsing.";
            case "spy" -> "Social-spy message format.\nShown to enabled spies who can see the sender/receiver pair.\nPlaceholders: {sender}, {receiver}, {message}.";
            case "player1", "player2", "player3", "player4", "player5" -> "Ignored player name entry.\nUsed by the ignore-list resource to seed blocked private/chat targets.";
            case "name", "displayName", "title" -> "Human-readable name.\nShown in previews, menus, or generated Minecraft text.";
            case "description" -> "Long description for players or editors.\nExplain what the resource does and when it applies.";
            case "enabled" -> "Export state.\nOn: synchronized and active.\nOff: saved but not used live.";
            case "priority" -> "Match ordering weight.\nHigher values are reserved for more specific rules.";
            case "permission" -> "Required permission node.\nLeave empty when no permission check is needed.";
            case "conditions.permission" -> "Recipe permission condition.\nOnly players with this permission can craft or take the recipe result.\nEmpty means no permission gate.";
            case "conditions.world" -> "Recipe world condition.\nOnly players in this world can craft or take the recipe result.\nEmpty means every world.";
            case "worlds" -> "World filter.\nEmpty means every world.\nSelected worlds scope this resource.";
            case "line1", "line2", "motd", "serverName" -> "Minecraft server-list MOTD text.\nRendered in the multiplayer server list.\nKeep both lines readable at small size.";
            case "motdText" -> "Server-list MOTD editor text.\nFirst line maps to line1.\nSecond line maps to line2.";
            case "icon", "iconHash", "iconData" -> "Server-list icon data.\nBest source image size: 64x64.\nMinecraft displays it beside the MOTD.";
            case "playerCountMode" -> "Server-list player-count mode.\nreal: show actual online/max values.\nhidden: hide player counts on Paper.\nfixed: override online and max values.";
            case "onlinePlayers" -> "Fixed online-player count.\nUsed only when Player Count is fixed.\nPaper applies it to the server-list ping response.";
            case "maxPlayers" -> "Fixed max-player count.\nUsed only when Player Count is fixed.\nBukkit applies it to the server-list ping response.";
            case "type", "recipeType" -> "Recipe type.\nOptions:\nshaped: 3x3 pattern.\nshapeless: ingredients in any order.\nfurnace, blasting, smoking, campfire: one input plus cook settings.\nstonecutting: one input to one output.\nsmithing_transform: template/base/addition to output.\nsmithing_trim: template/base/addition trim recipe.";
            case "group", "category" -> "Organization key.\nUsed by browsers, filters, and grouping views.";
            case "material", "output.material", "template.material", "base.material", "addition.material" -> "Item material id.\nAccepts a Minecraft material id or supported custom content id.";
            case "amount", "output.amount", "template.amount", "base.amount", "addition.amount" -> "Item stack amount.\nMost Minecraft item stacks use 1 to 64.";
            case "ingredients" -> "Recipe ingredient list.\nShapeless: all required inputs.\nCooking/stonecutting: first ingredient is the input.\nSmithing: template, base, and addition are separate fields.";
            case "shape" -> "Shaped recipe pattern.\nUp to 3 rows.\nEach character maps to an entry in recipe keys/ingredients.";
            case "slots" -> "Recipe preview slot bindings.\nUsed by the editor to map materials to visible recipe slots.";
            case "experience" -> "Cooking recipe experience reward.\nPassed to Bukkit cooking recipes as the XP dropped when the result is taken.";
            case "cookingTime", "cookTime" -> "Cooking duration in ticks.\n20 ticks = 1 second.\nMinimum runtime value is 1 tick.";
            case "craftedBinding" -> "Action run after a crafting, stonecutting, or shapeless result is taken.\nUse a flow or function with recipe/player context.";
            case "cookedBinding" -> "Action run after a cooking recipe result is taken.\nUse a flow or function with recipe/player context.";
            case "conditionBinding" -> "Predicate function checked before recipe use.\nUse declared inputs with recipe/player context.";
            case "deniedBinding" -> "Action run when recipe conditions or ingredient checks deny the craft.\nUse a flow or function with recipe/player context.";
            case "craftedFlow" -> "Flow run after a crafting, stonecutting, or shapeless result is taken.\nReceives recipe/player event context.";
            case "cookedFlow" -> "Flow run after a furnace, blast furnace, smoker, or campfire result is taken.\nReceives recipe/player event context.";
            case "deniedFlow" -> "Flow run when recipe conditions or ingredient checks deny the craft.\nReceives recipe/player event context.";
            case "channel", "channelId" -> "Chat channel id.\nKeep it stable because formats, permissions, aliases, and rules can reference it.";
            case "range" -> "Chat hearing range in blocks.\nNegative values mean unlimited range.\nUsed with the sender and each viewer location.";
            case "speakPermission" -> "Permission required to send messages in this channel.\nEmpty means every player can speak.";
            case "readPermission" -> "Permission required to receive this channel.\nEmpty means every player can read it.";
            case "allowMiniMessage" -> "MiniMessage formatting gate.\nOn: channel formatting can parse MiniMessage tags.";
            case "miniMessagePermission" -> "Permission required for player-authored MiniMessage formatting.\nEmpty means no extra MiniMessage permission gate.";
            case "format", "messageFormat", "entryFormat" -> "Chat/message format template.\nCommon placeholders:\n{player} Sender name.\n{displayName} Sender display name.\n{message} Message text.\n{prefix} Channel prefix.\n{channel} Channel id.";
            case "prefix" -> "Text before the message or player name.\nTypical content: channel labels, ranks, or status markers.";
            case "suffix" -> "Text after the message or player name.\nKeep it short so chat and tab rows stay readable.";
            case "color" -> "Primary display color.\nUse enough contrast for Minecraft chat and UI backgrounds.";
            case "hover", "hoverText" -> "Interactive chat hover text.\nShown only on clients that support hover events.";
            case "click", "clickAction", "clickValue" -> "Interactive chat click behavior.\nTypical actions are suggest command, run command, or open URL.";
            case "source" -> "Message-rule source event.\nOptions: join, quit, kick, death, title, actionbar, bossbar, openScreen, packetText, system.";
            case "sources" -> "Message-rule source list.\nArray form of Source when the rule should match several event types.";
            case "contains" -> "Match text.\nThe rule applies when the source message contains this value.\nEmpty values match broadly and should be avoided.";
            case "replacement" -> "Replacement or inserted text.\nAction decides how this is used.\n{message} keeps the original message.";
            case "action" -> "Rule action.\nChat rules: block, replace, flow, channel.\nMessage rules: replace_section, replace, append, prepend, remove, flow.";
            case "players" -> "Player filter list.\nWhen present, the rule applies only to matching player names.";
            case "template" -> "Chat format template.\nRendered by the chat/text service for the resource that owns it.";
            case "text" -> textFieldDescription();
            case "mode" -> modeFieldDescription();
            case "framesText" -> "Animation frames.\nOne frame per line.\nUsed by frames mode.";
            case "frameMillis" -> "Animation frame duration in milliseconds.\nMinimum runtime value is 1 ms.\nDefault is 250 ms.";
            case "width" -> "Visible window width in characters.\nUsed by scroll and bounce modes.";
            case "visibleCharacters" -> "Visible character cap.\n0 means no cap.\nUsed by typing and wipe modes.";
            case "colorsText" -> "Color list.\nOne MiniMessage color or tag per line.\nUsed by wave mode.";
            case "secondaryColor" -> "Secondary MiniMessage color or tag.\nUsed by pulse and sparkle modes.";
            case "command", "commands" -> "Executable command text.\nStore commands only, without explanation around them.";
            case "flowId" -> "Flow run by this rule or action.\nReceives the current player/event context.";
            case "flowPredicate", "predicateFlowId" -> "Predicate flow reference.\nThe resource continues only when this flow passes for the current context.";
            case "privateMessageFlow" -> "Flow run after a private message is sent.\nReceives event.message and event.receiver.";
            case "mentionFlow" -> "Flow run when a mention style is applied.\nReceives mention/player context from chat handling.";
            default -> switch (label) {
                case "Worlds" -> "World filter.\nEmpty means every world.\nSelected worlds scope this resource.";
                case "Icon" -> "Preview icon or uploaded image.\nUsed by browser and editor previews.";
                case "Provider" -> "Value resolver.\nDifferent providers map ids to different assets or runtime behavior.";
                default -> "JSON resource field.\nValue must match the selected resource type and runtime schema.";
            };
        };
    }

    protected String textFieldDescription() {
        return "Reusable text body.\nRendered by the resource that owns this field.";
    }

    protected String modeFieldDescription() {
        return "Resource mode.\nAvailable values depend on the current resource type.";
    }

    protected List<String> normalizedWorldOptions(List<String> choices, List<String> selected) {
        List<String> options = new ArrayList<>();
        if (choices != null) {
            for (String choice : choices) {
                if (choice != null && !choice.isBlank() && !"Loading".equals(choice) && !options.contains(choice)) {
                    options.add(choice);
                }
            }
        }
        if (selected != null) {
            for (String value : selected) {
                if (value != null && !value.isBlank() && !options.contains(value)) {
                    options.addFirst(value);
                }
            }
        }
        if (options.isEmpty()) {
            options.add("Loading");
        }
        return options;
    }

    protected AnimatedWidget searchableFieldRow(String field, String label, List<String> options, int rowWidth) {
        List<String> normalized = normalizedSelectorOptions(options, jsonPathText(field));
        String selected = resolveSelectedOption(normalized, jsonPathText(field));
        AnimatedButton button = new AnimatedButton.Builder()
            .label(isRealOption(selected) ? selectorLabel(field, selected) : "Select")
            .size(174, 18)
            .entranceAnimation(false)
            .build();
        resourceSelectorButtons.put(field, button);
        button.setAction(() -> {
            if (normalized.size() == 1 && "Loading".equals(normalized.getFirst()) && selectorCatalogSource(field) == null) {
                return;
            }
            showResourceSelector(field, normalized, jsonPathText(field), value -> {
                if (!isRealOption(value)) {
                    return;
                }
                button.setMessage(selectorLabel(field, value));
                putJsonText(field, value);
                onSelectorValueChanged(field, value);
                if (rebuildOnSelection(field)) {
                    reloadFields();
                }
            }, button.getX(), button.getY() + button.getHeight());
        });
        return studioPanelState.row(label, button, rowWidth, jsonResourceDescription(field, label));
    }

    protected void showResourceSelector(String field, List<String> options, String selected, Consumer<String> onSelected, int selectorX, int selectorY) {
        String catalogSource = selectorCatalogSource(field);
        if (catalogSource != null) {
            Map<String, Object> context = selectorCatalogContext(field);
            ItemSelectorWidget selector = new ItemSelectorWidget.Builder(this)
                .size(220, 240)
                .dismissOnSelect(true)
                .emptyMessage("No Options")
                .asyncItems(OptionCatalogSelector.refreshAction(serverId, catalogSource, context),
                    () -> OptionCatalogSelector.snapshot(serverId, catalogSource, context, () -> options, () -> jsonPathText(field), onSelected, "No Options"))
                .build();
            showStudioSelector(selector, OptionCatalogSelector.label(serverId, catalogSource, context, selected), selectorX, selectorY);
            return;
        }
        String createType = selectorCreateResourceType(field);
        showStudioSelector(List.of(), selectorLabel(field, selected), selectorX, selectorY, selector -> {
            List<String> realOptions = options.stream().filter(this::isRealOption).distinct().toList();
            if (realOptions.stream().anyMatch(option -> "none".equalsIgnoreCase(option))) {
                selector.addItem(selectorLabel(field, "none"), () -> onSelected.accept("none"));
            }
            if (createType != null) {
                selector.addItem("Create New", () -> createBindingResource(createType, onSelected));
            }
            for (String option : realOptions.stream().filter(option -> !"none".equalsIgnoreCase(option)).sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
                selector.addItem(selectorLabel(field, option), () -> onSelected.accept(option));
            }
        });
    }

    protected String selectorCreateResourceType(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        if (field.endsWith(".functionId")) {
            return ReSyncResourceDragPayload.FUNCTION;
        }
        if ("flowId".equals(field) || field.endsWith(".flowId") || "flowPredicate".equals(field) || field.endsWith("Flow") || field.contains("Flow")) {
            return ReSyncResourceDragPayload.FLOW;
        }
        return null;
    }

    @Override
    protected void onStudioSelectorClosed() {
    }

    protected boolean functionInputBooleanField(String field) {
        FlowGraph.FunctionParameter parameter = functionInputParameter(field);
        return parameter != null && parameter.getType() != null && FlowDataType.BOOLEAN.isAssignableFrom(parameter.getType());
    }

    protected List<String> normalizedSelectorOptions(List<String> choices, String selected) {
        List<String> options = new ArrayList<>();
        if (choices != null) {
            for (String choice : choices) {
                if (choice != null && !choice.isBlank() && !options.contains(choice)) {
                    options.add(choice);
                }
            }
        }
        if (selected != null && !selected.isBlank() && !"Loading".equals(selected) && !options.contains(selected)) {
            options.addFirst(selected);
        }
        if (options.isEmpty()) {
            options.add("No Options");
        }
        return options;
    }

    protected String resolveSelectedOption(List<String> options, String selected) {
        if (selected != null && options.contains(selected)) {
            return selected;
        }
        if (selected != null) {
            String normalized = selected.toUpperCase(Locale.ROOT);
            if (options.contains(normalized)) {
                return normalized;
            }
        }
        return options.isEmpty() ? null : options.getFirst();
    }

    protected List<String> selectorOptions(String field) {
        List<String> customOptions = customSelectorOptions(field);
        if (customOptions != null) {
            return customOptions;
        }
        return switch (field) {
            case "enabled", "allowMiniMessage", "channel.allowMiniMessage" -> List.of("true", "false");
            case "dialog", "links.dialog" -> jsonResourceOptions(ReSyncResourceType.DIALOG);
            case "lootTable", "links.lootTable" -> jsonResourceOptions(ReSyncResourceType.LOOT_TABLE);
            case "tradeProfile", "links.tradeProfile" -> jsonResourceOptions(ReSyncResourceType.TRADE_PROFILE);
            case "flowId", "flowPredicate", "craftedFlow", "deniedFlow", "cookedFlow", "privateMessageFlow", "mentionFlow", "rule.flowId", "privateMessages.privateMessageFlow", "mention.mentionFlow",
                 "hooks.openFlow", "hooks.completeFlow", "hooks.deniedFlow", "hooks.spawnFlow", "hooks.rightClickFlow", "hooks.leftClickFlow", "hooks.interactFlow", "hooks.damageFlow", "hooks.deathFlow", "hooks.despawnFlow",
                 "hooks.beforeRollFlow", "hooks.afterRollFlow", "hooks.deniedRollFlow" -> flowOptions();
            default -> {
                String catalog = functionInputCatalogSource(field);
                if (field.endsWith(".functionId")) {
                    yield functionOptions();
                }
                if (functionInputBooleanField(field)) {
                    yield List.of("true", "false");
                }
                if (catalog != null) {
                    yield catalogOptions(catalog);
                }
                yield customRecipeItemSelectorField(field) ? recipeItemOptions() : List.of();
            }
        };
    }

    protected List<String> customSelectorOptions(String field) {
        return null;
    }

    protected String customSelectorCatalogSource(String field) {
        return null;
    }

    protected Map<String, Object> selectorCatalogContext(String field) {
        return Map.of();
    }

    protected String selectorCatalogSource(String field) {
        if ("conditions.world".equals(field) || "location.world".equals(field)) {
            return "server:minecraft:world";
        }
        String functionCatalog = functionInputCatalogSource(field);
        return functionCatalog != null ? functionCatalog : customSelectorCatalogSource(field);
    }

    private void preloadFieldCatalogs(List<String> fields) {
        List<OptionCatalogLoader.Request> requests = new ArrayList<>();
        for (String field : fields) {
            if (isRecipeItemSelectorField(field)) {
                ItemOptionCatalog.ensureLoaded(serverId);
            }
            String source = selectorCatalogSource(field);
            if (source != null && !source.isBlank()) {
                requests.add(OptionCatalogLoader.request(source, selectorCatalogContext(field)));
            }
        }
        OptionCatalogLoader.preload(serverId, requests);
    }

    protected List<String> modeOptions() {
        return List.of();
    }

    protected List<String> actionOptions(String field) {
        return List.of();
    }

    protected boolean rebuildOnSelection(String field) {
        return customRebuildOnSelection(field) || field.endsWith(".functionId");
    }

    protected boolean customRebuildOnSelection(String field) {
        return false;
    }

    protected String selectorLabel(String field, String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        if (isRecipeItemSelectorField(field)) {
            return recipeItemSelectorLabel(value);
        }
        if (field.endsWith("Flow") || "flowId".equals(field) || field.endsWith(".flowId") || "flowPredicate".equals(field) || field.contains("Flow")) {
            FlowManager manager = FlowManager.getInstance();
            return manager != null && !"none".equals(value) ? manager.getFlowName(serverId, value) : value;
        }
        if (field.endsWith(".functionId")) {
            FlowManager manager = FlowManager.getInstance();
            return manager != null && !"none".equals(value) ? manager.getFlowName(serverId, value) : value;
        }
        return formatOptionLabel(value);
    }

    protected String formatOptionLabel(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String cleaned = value.trim().replace("minecraft:", "").replace('_', ' ').replace('-', ' ');
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.isEmpty() ? value : builder.toString();
    }

    protected List<String> recipeTypeOptions() {
        return List.of("shaped", "shapeless", "furnace", "blasting", "smoking", "campfire", "stonecutting", "smithing_transform", "smithing_trim");
    }

    protected List<String> materialOptions() {
        return catalogOptions(MATERIAL_OPTIONS_SOURCE);
    }

    protected List<String> jsonResourceOptions(ReSyncResourceType type) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null || type == null) {
            return List.of("none");
        }
        List<String> values = new ArrayList<>(manager.getJsonResourcesForServer(serverId, type).keySet());
        values.sort(String.CASE_INSENSITIVE_ORDER);
        values.addFirst("none");
        return values;
    }

    protected List<String> typedResourceOptions(String type) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null || type == null) {
            return List.of("none");
        }
        List<String> values = switch (type) {
            case ReSyncResourceDragPayload.GUI -> new ArrayList<>(manager.getGuisForServer(serverId).keySet());
            case ReSyncResourceDragPayload.SCOREBOARD -> new ArrayList<>(manager.getScoreboardsForServer(serverId).keySet());
            case ReSyncResourceDragPayload.TAB -> new ArrayList<>(manager.getTabsForServer(serverId).keySet());
            case ReSyncResourceDragPayload.CUSTOM_CONTENT -> new ArrayList<>(manager.getCustomContentForServer(serverId).keySet());
            default -> new ArrayList<>();
        };
        values.sort(String.CASE_INSENSITIVE_ORDER);
        values.addFirst("none");
        return values;
    }

    protected void ensureRecipeItemCatalogLoaded() {
        ItemOptionCatalog.ensureLoaded(serverId);
    }

    protected List<String> recipeItemOptions() {
        return mergedRecipeItemValues();
    }

    protected List<String> mergedRecipeItemValues() {
        return ItemOptionCatalog.mergedValues(serverId);
    }

    @Override
    public void onStudioCatalogRefreshed() {
        if (!resourcePanelMounted || !resourcePanelWidgetsMounted()) {
            mountResourcePanel();
        } else {
            refreshResourcePanelFields();
            refreshBindingWidgets();
        }
    }

    protected boolean isRecipeItemSelectorField(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        return customRecipeItemSelectorField(field);
    }

    protected boolean customRecipeItemSelectorField(String field) {
        return false;
    }

    protected String recipeItemSelectorLabel(String value) {
        return ItemIconPreview.project(serverId, value).label();
    }

    protected String encodeRecipeItemValue(JsonObject object) {
        if (object == null) {
            return "";
        }
        String contentId = jsonText(object, "contentId");
        if (contentId.isBlank()) {
            contentId = jsonText(object, "customContentId");
        }
        if (contentId.isBlank()) {
            contentId = jsonText(object, "customContent");
        }
        if (!contentId.isBlank()) {
            return "content:" + contentId;
        }
        String provider = jsonText(object, "provider");
        String externalId = jsonText(object, "externalId");
        if (externalId.isBlank()) {
            externalId = jsonText(object, "nexo");
        }
        if (provider.isBlank() && !externalId.isBlank()) {
            provider = "nexo";
        }
        if (!provider.isBlank() && !externalId.isBlank()) {
            return "provider:" + provider.toLowerCase(Locale.ROOT) + ":" + externalId;
        }
        return jsonText(object, "material");
    }

    protected void applyRecipeItemValue(JsonObject object, String value) {
        if (object == null) {
            return;
        }
        object.remove("contentId");
        object.remove("customContentId");
        object.remove("customContent");
        object.remove("provider");
        object.remove("externalId");
        object.remove("nexo");
        object.remove("material");
        object.remove("item");
        String selection = value == null ? "" : value.trim();
        if (selection.isBlank() || "none".equalsIgnoreCase(selection)) {
            return;
        }
        if (selection.startsWith("content:")) {
            object.addProperty("contentId", selection.substring("content:".length()));
            return;
        }
        if (selection.startsWith("provider:")) {
            String rest = selection.substring("provider:".length());
            int split = rest.indexOf(':');
            if (split > 0 && split < rest.length() - 1) {
                object.addProperty("provider", rest.substring(0, split));
                object.addProperty("externalId", rest.substring(split + 1));
            }
            return;
        }
        object.addProperty("material", selection);
    }

    protected JsonObject recipeItemObject(String value) {
        JsonObject object = new JsonObject();
        applyRecipeItemValue(object, value);
        return object;
    }

    protected boolean isRecipeItemPathField(String field) {
        return field != null && field.endsWith(".material");
    }

    protected String recipeItemPathText(String field) {
        String[] parts = field.split("\\.", 2);
        JsonObject parent = jsonObject(parts[0]);
        return encodeRecipeItemValue(parent);
    }

    protected void putRecipeItemPathText(String field, String value) {
        if (deferResourceMutation(() -> putRecipeItemPathText(field, value))) {
            return;
        }
        String[] parts = field.split("\\.", 2);
        JsonObject parent = jsonObject(parts[0]);
        if (!resource.has(parts[0]) || !resource.get(parts[0]).isJsonObject()) {
            resource.add(parts[0], parent);
        }
        applyRecipeItemValue(parent, value);
        if ("output".equals(parts[0]) && value != null && !value.isBlank() && jsonText(parent, "amount").isBlank()) {
            parent.addProperty("amount", 1);
        }
        resource.add(parts[0], parent);
    }

    protected List<String> flowOptions() {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return List.of("none");
        }
        return CompactBindingSupport.flowOptions(serverId);
    }

    protected List<String> functionOptions() {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return List.of("none");
        }
        return CompactBindingSupport.functionOptions(serverId);
    }

    protected String functionInputCatalogSource(String field) {
        FlowGraph.FunctionParameter parameter = functionInputParameter(field);
        if (parameter == null) {
            return null;
        }
        String source = parameter.getOptionsSource();
        return source != null && !source.isBlank() ? source : null;
    }

    protected FlowGraph.FunctionParameter functionInputParameter(String field) {
        String marker = ".inputs.";
        int index = field.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String basePath = field.substring(0, index);
        String inputName = field.substring(index + marker.length());
        FlowGraph function = selectedFunction(basePath + ".functionId");
        if (function == null || function.getFunctionInputs() == null || inputName.isBlank()) {
            return null;
        }
        for (FlowGraph.FunctionParameter input : function.getFunctionInputs()) {
            if (input != null && inputName.equals(input.getName())) {
                return input;
            }
        }
        return null;
    }

    protected List<String> catalogOptions(String source) {
        return OptionCatalogLoader.snapshot(serverId, source).values();
    }

    protected boolean isRealOption(String value) {
        return value != null && !"Loading".equals(value) && !"No Options".equals(value);
    }

    protected boolean isCodeField(String field) {
        return customCodeField(field);
    }

    protected int codeFieldHeight(String field) {
        int customHeight = customCodeFieldHeight(field);
        return customHeight > 0 ? customHeight : 82;
    }

    protected boolean customCodeField(String field) {
        return false;
    }

    protected int customCodeFieldHeight(String field) {
        return -1;
    }

    protected int dynamicCodeFieldHeight(String field) {
        String value = jsonPathText(field);
        int lines = value == null || value.isBlank() ? 4 : Math.clamp(value.split("\\R", -1).length, 4, 10);
        return Math.clamp(lines * 18 + 28, 100, 208);
    }

    protected String resourceSummary() {
        return id;
    }

    protected String firstFilled(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    protected String compactState(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value) ? "None" : "Set";
    }

    protected String resourceLinkText(String field, String legacyField) {
        String value = jsonPathText(field);
        if (!value.isBlank() && !"none".equalsIgnoreCase(value)) {
            return value;
        }
        return jsonPathText(legacyField);
    }

    protected String firstOfferText(String key) {
        JsonArray offers = resource.has("offers") && resource.get("offers").isJsonArray() ? resource.getAsJsonArray("offers") : new JsonArray();
        if (offers.isEmpty() || !offers.get(0).isJsonObject()) {
            return "";
        }
        return jsonText(offers.get(0).getAsJsonObject(), key);
    }

    protected void showRecipeMaterialSelector(String field, int mouseX, int mouseY) {
        openRecipeItemSelector(field, mouseX, mouseY);
    }

    protected void openRecipeItemSelector(String field, int mouseX, int mouseY) {
        String selected = jsonPathText(field);
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Items")
            .asyncItems(() -> ItemOptionCatalog.refresh(serverId),
                () -> ItemOptionCatalog.selectorSnapshot(serverId, () -> jsonPathText(field), value -> applyRecipeItemSelection(field, value), true))
            .build();
        showStudioSelector(selector, recipeItemSelectorLabel(selected), mouseX, mouseY);
    }

    protected void applyRecipeItemSelection(String field, String value) {
        if (!isRealOption(value)) {
            return;
        }
        String selection = value == null || "none".equalsIgnoreCase(value) ? "" : value;
        putJsonText(field, selection);
        reloadFields();
    }


    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (handleActiveStudioSelectorMouseClicked(event)) {
            return true;
        }
        return handleResourceMouseClicked(event);
    }

    protected boolean handleResourceMouseClicked(ReMouseEvent event) {
        return false;
    }


    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (handleActiveStudioSelectorMouseReleased(event)) {
            return true;
        }
        return handleResourceMouseReleased(event);
    }

    protected boolean handleResourceMouseReleased(ReMouseEvent event) {
        return false;
    }


    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (handleActiveStudioSelectorMouseDragged(event)) {
            return true;
        }
        return handleResourceMouseDragged(event);
    }

    protected boolean handleResourceMouseDragged(ReMouseEvent event) {
        return false;
    }


    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        if (handleActiveStudioSelectorMouseScrolled(event)) {
            return true;
        }
        return handleResourceMouseScrolled(event);
    }

    protected boolean handleResourceMouseScrolled(ReScrollEvent event) {
        return false;
    }


    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (handleStudioSaveShortcut(event)) {
            return true;
        }
        if (handleActiveStudioSelectorKeyPressed(event)) {
            return true;
        }
        return handleResourceKeyPressed(event);
    }

    protected boolean handleResourceKeyPressed(ReKeyEvent event) {
        return false;
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        return handleActiveStudioSelectorTextInput(event);
    }



    protected List<String> editorFields() {
        return List.of("displayName");
    }

    protected List<String> functionInputFields(String basePath) {
        FlowGraph function = selectedFunction(basePath + ".functionId");
        if (function == null || function.getFunctionInputs() == null || function.getFunctionInputs().isEmpty()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (FlowGraph.FunctionParameter input : function.getFunctionInputs()) {
            if (input != null && input.getName() != null && !input.getName().isBlank()) {
                fields.add(basePath + ".inputs." + input.getName());
            }
        }
        return fields;
    }

    protected FlowGraph selectedFunction(String field) {
        return selectedFunction(field, null);
    }

    protected FlowGraph selectedFunction(String field, CompactBindingSupport.FunctionShape shape) {
        String functionId = jsonPathText(field);
        if (functionId.isBlank()) {
            return null;
        }
        return CompactBindingSupport.selectedFunction(serverId, functionId, shape);
    }

    protected FlowGraph selectedFunctionById(String functionId) {
        if (functionId == null || functionId.isBlank() || "none".equalsIgnoreCase(functionId)) {
            return null;
        }
        return CompactBindingSupport.selectedFunction(serverId, functionId, null);
    }

    protected String fieldLabel(String field) {
        if (field.contains(".inputs.")) {
            String name = field.substring(field.lastIndexOf('.') + 1).replace('_', ' ');
            return name.isBlank() ? "Input" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        String knownLabel = switch (field) {
            case "displayName" -> "Name";
            case "channel.prefix" -> "Prefix";
            case "format.template" -> "Format";
            case "channel.range" -> "Range";
            case "channel.speakPermission" -> "Speak";
            case "channel.readPermission" -> "Read";
            case "channel.allowMiniMessage" -> "Allow MiniMessage";
            case "channel.miniMessagePermission" -> "MiniMessage Permission";
            case "rule.contains" -> "Find";
            case "rule.action" -> "Action";
            case "rule.replacement" -> "Replace With";
            case "rule.channel" -> "Channel";
            case "rule.flowId" -> "Rule Flow";
            case "privateMessages.sender" -> "Sender";
            case "privateMessages.receiver" -> "Receiver";
            case "privateMessages.spy" -> "Spy";
            case "privateMessages.privateMessageFlow" -> "Message Flow";
            case "mention.template" -> "Mention";
            case "mention.mentionFlow" -> "Mention Flow";
            case "ignorePlayersText" -> "Ignored Players";
            case "source" -> "Source";
            case "contains" -> "Find";
            case "replacement" -> "Replace With";
            case "action" -> "Action";
            case "motdText" -> "MOTD";
            case "speakPermission" -> "Speak";
            case "readPermission" -> "Read";
            case "allowMiniMessage" -> "Allow MiniMessage";
            case "miniMessagePermission" -> "MiniMessage Permission";
            case "privateMessageFlow" -> "Message Flow";
            case "mentionFlow" -> "Mention Flow";
            case "playerCountMode" -> "Player Count";
            case "onlinePlayers" -> "Online";
            case "maxPlayers" -> "Max Players";
            case "profession" -> "Profession";
            case "villagerType" -> "Type";
            case "level" -> "Level";
            case "maxUses" -> "Max Uses";
            case "restockTicks" -> "Restock";
            case "lootTable" -> "Loot Table";
            case "tradeProfile" -> "Trade";
            case "dialog" -> "Dialog";
            case "entityType" -> "Entity";
            case "skin.username" -> "Skin Username";
            case "location.world" -> "World";
            case "location.x" -> "X";
            case "location.y" -> "Y";
            case "location.z" -> "Z";
            case "location.yaw" -> "Yaw";
            case "location.pitch" -> "Pitch";
            case "invulnerable" -> "Invulnerable";
            case "ai" -> "AI";
            case "gravity" -> "Gravity";
            case "followPlayer" -> "Look At Player";
            case "followRange" -> "Look Range";
            case "equipment.mainHand" -> "Main Hand";
            case "equipment.offHand" -> "Off Hand";
            case "equipment.helmet" -> "Helmet";
            case "equipment.chestplate" -> "Chestplate";
            case "equipment.leggings" -> "Leggings";
            case "equipment.boots" -> "Boots";
            case "offers.0.cost" -> "Cost";
            case "offers.0.costAmount" -> "Cost Amount";
            case "offers.0.cost2" -> "Cost 2";
            case "offers.0.cost2Amount" -> "Cost 2 Amount";
            case "offers.0.result" -> "Result";
            case "offers.0.resultAmount" -> "Result Amount";
            case "offers.0.weight" -> "Weight";
            case "pools.0.rolls" -> "Rolls";
            case "pools.0.entries.0.item" -> "Item";
            case "pools.0.entries.0.minAmount" -> "Min Amount";
            case "pools.0.entries.0.maxAmount" -> "Max Amount";
            case "pools.0.entries.0.weight" -> "Weight";
            case "pools.0.entries.0.chance" -> "Chance";
            case "pools.0.entries.0.conditions" -> "Conditions";
            case "pools.0.entries.0.components" -> "Components";
            case "hooks.openFlow" -> "Open";
            case "hooks.completeFlow" -> "Complete";
            case "hooks.deniedFlow" -> "Denied";
            case "hooks.spawnFlow" -> "Spawn";
            case "hooks.interactFlow" -> "Interact";
            case "hooks.damageFlow" -> "Damage";
            case "hooks.deathFlow" -> "Death";
            case "hooks.despawnFlow" -> "Despawn";
            case "hooks.openAction" -> "Open";
            case "hooks.completeAction" -> "Complete";
            case "hooks.deniedAction" -> "Denied";
            case "hooks.spawnAction" -> "Spawn";
            case "hooks.interactAction" -> "Interact";
            case "hooks.rightClickAction" -> "Right Click";
            case "hooks.leftClickAction" -> "Left Click";
            case "hooks.damageAction" -> "Damage";
            case "hooks.deathAction" -> "Death";
            case "hooks.despawnAction" -> "Despawn";
            case "hooks.beforeRollFlow" -> "Before Roll";
            case "hooks.afterRollFlow" -> "After Roll";
            case "hooks.deniedRollFlow" -> "Denied Roll";
            case "framesText" -> "Frames";
            case "colorsText" -> "Colors";
            case "flowPredicate" -> "Condition";
            case "conditions.predicate.functionId" -> "Condition Function";
            case "conditionBinding" -> "Condition";
            case "flowId" -> "Flow";
            case "output.material" -> "Output";
            case "output.amount" -> "Amount";
            case "conditions.permission" -> "Permission";
            case "conditions.world" -> "World";
            case "craftedBinding" -> "Craft Action";
            case "craftedFlow" -> "Craft Flow";
            case "craftedAction.functionId" -> "Craft Function";
            case "deniedBinding" -> "Deny Action";
            case "deniedFlow" -> "Deny Flow";
            case "deniedAction.functionId" -> "Deny Function";
            case "cookedBinding" -> "Cook Action";
            case "cookedFlow" -> "Cook Flow";
            case "cookedAction.functionId" -> "Cook Function";
            default -> null;
        };
        if (knownLabel != null) {
            return knownLabel;
        }
        if (field.matches("offers\\.\\d+\\.cost")) {
            return "Cost";
        }
        if (field.matches("offers\\.\\d+\\.costAmount")) {
            return "Cost Amount";
        }
        if (field.matches("offers\\.\\d+\\.cost2")) {
            return "Cost 2";
        }
        if (field.matches("offers\\.\\d+\\.cost2Amount")) {
            return "Cost 2 Amount";
        }
        if (field.matches("offers\\.\\d+\\.result")) {
            return "Result";
        }
        if (field.matches("offers\\.\\d+\\.resultAmount")) {
            return "Result Amount";
        }
        if (field.matches("offers\\.\\d+\\.weight")) {
            return "Weight";
        }
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (i == 0) {
                label.append(Character.toUpperCase(c));
            } else if (Character.isDigit(c) && Character.isLetter(field.charAt(i - 1))) {
                label.append(' ').append(c);
            } else if (Character.isUpperCase(c)) {
                label.append(' ').append(c);
            } else {
                label.append(c);
            }
        }
        return label.toString();
    }

    protected void putJsonText(String field, String value) {
        if (deferResourceMutation(() -> putJsonText(field, value))) {
            return;
        }
        if ("id".equals(field)) {
            return;
        }
        captureResourceSnapshot();
        if ("motdText".equals(field)) {
            putMotdText(value);
            return;
        }
        if (handleSpecialJsonTextWrite(field, value)) {
            return;
        }
        if ("framesText".equals(field)) {
            putJsonArrayLines("frames", value);
            return;
        }
        if ("colorsText".equals(field)) {
            putJsonArrayLines("colors", value);
            return;
        }
        if ("ignorePlayersText".equals(field)) {
            putJsonArrayLines("ignore.players", value);
            return;
        }
        if (playerIndex(field) >= 0) {
            putPlayerText(field, value);
            return;
        }
        if (recipeSlotIndex(field) >= 0) {
            putRecipeSlotText(recipeSlotIndex(field), value);
            return;
        }
        if (recipeIngredientIndex(field) >= 0) {
            putRecipeIngredientText(recipeIngredientIndex(field), value);
            return;
        }
        if (supportsRecipeItemPathFields() && isRecipeItemPathField(field)) {
            putRecipeItemPathText(field, value);
            return;
        }
        if (field.endsWith(".functionId")) {
            putFunctionIdPathText(field, value);
            return;
        }
        if (field.contains(".")) {
            putJsonPathText(field, value);
            return;
        }
        if (value == null || value.isBlank()) {
            resource.remove(field);
            return;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            resource.addProperty(field, Boolean.parseBoolean(value));
            return;
        }
        try {
            resource.addProperty(field, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            resource.addProperty(field, value);
        }
    }

    protected boolean handleSpecialJsonTextWrite(String field, String value) {
        return false;
    }

    protected boolean supportsRecipeItemPathFields() {
        return false;
    }

    protected String jsonPathText(String field) {
        if ("motdText".equals(field)) {
            return motdText();
        }
        if ("framesText".equals(field)) {
            return jsonArrayLines("frames");
        }
        if ("colorsText".equals(field)) {
            return jsonArrayLines("colors");
        }
        if ("ignorePlayersText".equals(field)) {
            return jsonArrayLines("ignore.players");
        }
        if (playerIndex(field) >= 0) {
            return playerText(field);
        }
        if (recipeSlotIndex(field) >= 0) {
            return recipeSlotText(recipeSlotIndex(field));
        }
        if (recipeIngredientIndex(field) >= 0) {
            return recipeIngredientText(recipeIngredientIndex(field));
        }
        if (supportsRecipeItemPathFields() && isRecipeItemPathField(field)) {
            return recipeItemPathText(field);
        }
        FlowGraph.FunctionParameter functionInput = functionInputParameter(field);
        if (functionInput != null) {
            String configured = jsonPathTextRaw(field);
            if (!configured.isBlank()) {
                return configured;
            }
            if (functionInput.getDefaultValue() != null && !functionInput.getDefaultValue().isBlank()) {
                return functionInput.getDefaultValue();
            }
            return functionInputContextDefault(field, functionInput);
        }
        if (!field.contains(".")) {
            JsonElement element = resource.get(field);
            return element != null && element.isJsonArray() ? "" : jsonText(field);
        }
        return jsonPathTextRaw(field);
    }

    protected String functionInputContextDefault(String field, FlowGraph.FunctionParameter input) {
        return CompactBindingSupport.functionInputContextDefault(input, functionInputContext(field));
    }

    protected String functionInputContext(String field) {
        if (field == null) {
            return defaultFunctionInputContext();
        }
        if (field.startsWith("cookedAction.inputs.")) {
            return "recipe:cooked";
        }
        if (field.startsWith("craftedAction.inputs.")) {
            return "recipe:crafted";
        }
        if (field.startsWith("deniedAction.inputs.")) {
            return "recipe:denied";
        }
        if (field.startsWith("conditions.predicate.inputs.")) {
            return "recipe:predicate";
        }
        if (field.startsWith("hooks.")) {
            return defaultFunctionInputContext();
        }
        return defaultFunctionInputContext();
    }

    protected String defaultFunctionInputContext() {
        return "recipe";
    }

    protected String jsonPathTextRaw(String field) {
        JsonElement element = jsonPathElement(field);
        return element != null && !element.isJsonNull() && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    protected List<String> worldConditionValues() {
        JsonObject conditions = jsonObject("conditions");
        List<String> worlds = new ArrayList<>();
        JsonArray array = conditions.has("worlds") && conditions.get("worlds").isJsonArray() ? conditions.getAsJsonArray("worlds") : new JsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonNull() && !element.getAsString().isBlank() && !worlds.contains(element.getAsString())) {
                worlds.add(element.getAsString());
            }
        }
        String legacyWorld = jsonText(conditions, "world");
        if (!legacyWorld.isBlank() && !worlds.contains(legacyWorld)) {
            worlds.addFirst(legacyWorld);
        }
        return worlds;
    }

    protected void putWorldConditionValues(List<String> values) {
        List<String> snapshot = values != null ? new ArrayList<>(values) : List.of();
        if (deferResourceMutation(() -> putWorldConditionValues(snapshot))) {
            return;
        }
        JsonObject conditions = jsonObject("conditions");
        conditions.remove("world");
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank() && !"Loading".equals(value) && !"Any".equalsIgnoreCase(value)) {
                    array.add(value);
                }
            }
        }
        if (array.isEmpty()) {
            conditions.remove("worlds");
        } else {
            conditions.add("worlds", array);
        }
        if (conditions.size() == 0) {
            resource.remove("conditions");
        } else {
            resource.add("conditions", conditions);
        }
    }

    protected String motdText() {
        String line1 = jsonText("line1");
        String line2 = jsonText("line2");
        if (line1.isBlank() && line2.isBlank()) {
            return "";
        }
        return firstMotdLine(line1) + "\n" + firstMotdLine(line2);
    }

    protected void putMotdText(String value) {
        if (deferResourceMutation(() -> putMotdText(value))) {
            return;
        }
        String[] lines = (value == null ? "" : value).split("\\R", -1);
        String line1 = lines.length > 0 ? lines[0] : "";
        String line2 = lines.length > 1 ? lines[1] : "";
        if (line1.isBlank()) {
            resource.remove("line1");
        } else {
            resource.addProperty("line1", line1);
        }
        if (line2.isBlank()) {
            resource.remove("line2");
        } else {
            resource.addProperty("line2", line2);
        }
        resource.remove("mode");
        resource.remove("frames");
        resource.remove("frameMillis");
        resource.remove("rotationMillis");
        resource.remove("line1Frames");
        resource.remove("line2Frames");
        resource.remove("match");
        resource.remove("versionText");
        resource.remove("protocolVersion");
        resource.remove("protocolText");
        resource.remove("samplePlayers");
        resource.remove("countMode");
        resource.remove("fakePlayers");
        resource.remove("overrideMaxPlayers");
    }

    protected String firstMotdLine(String value) {
        String[] lines = (value == null ? "" : value).split("\\R", -1);
        return lines.length > 0 ? lines[0] : "";
    }

    protected void putTemplateText(String value) {
        if (deferResourceMutation(() -> putTemplateText(value))) {
            return;
        }
        if (value == null || value.isBlank()) {
            resource.remove("text");
        } else {
            resource.addProperty("text", value);
        }
    }

    protected String jsonArrayLines(String key) {
        JsonElement element = key != null && key.contains(".") ? jsonPathElement(key) : resource.get(key);
        JsonArray array = element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        List<String> values = new ArrayList<>();
        for (JsonElement e : array) {
            if (!e.isJsonNull()) {
                values.add(e.getAsString());
            }
        }
        return String.join("\n", values);
    }

    protected void putJsonArrayLines(String key, String value) {
        if (deferResourceMutation(() -> putJsonArrayLines(key, value))) {
            return;
        }
        JsonArray array = new JsonArray();
        if (value != null) {
            for (String line : value.split("\\R", -1)) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    array.add(trimmed);
                }
            }
        }
        if (array.isEmpty()) {
            if (key != null && key.contains(".")) {
                removeJsonPath(key);
            } else {
                resource.remove(key);
            }
            return;
        }
        if (key != null && key.contains(".")) {
            putJsonPathElement(key, array);
        } else {
            resource.add(key, array);
        }
    }

    protected String playerText(String field) {
        int index = playerIndex(field);
        if (index < 0) {
            return "";
        }
        JsonArray players = resource.has("players") && resource.get("players").isJsonArray() ? resource.getAsJsonArray("players") : new JsonArray();
        return index < players.size() && !players.get(index).isJsonNull() ? players.get(index).getAsString() : "";
    }

    protected void putPlayerText(String field, String value) {
        if (deferResourceMutation(() -> putPlayerText(field, value))) {
            return;
        }
        int index = playerIndex(field);
        if (index < 0) {
            return;
        }
        JsonArray players = resource.has("players") && resource.get("players").isJsonArray() ? resource.getAsJsonArray("players") : new JsonArray();
        resource.add("players", players);
        while (players.size() <= index) {
            players.add("");
        }
        players.set(index, new JsonPrimitive(value == null ? "" : value.trim()));
    }

    protected int playerIndex(String field) {
        if (field == null || !field.startsWith("player") || field.length() <= "player".length()) {
            return -1;
        }
        try {
            return Math.max(0, Integer.parseInt(field.substring("player".length())) - 1);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    protected int recipeSlotIndex(String field) {
        if (field == null || !field.startsWith("slot") || field.length() <= "slot".length()) {
            return -1;
        }
        try {
            int index = Integer.parseInt(field.substring("slot".length())) - 1;
            return index >= 0 && index <= 8 ? index : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    protected int recipeIngredientIndex(String field) {
        if (field == null || !field.startsWith("ingredient") || field.length() <= "ingredient".length()) {
            return -1;
        }
        try {
            int index = Integer.parseInt(field.substring("ingredient".length())) - 1;
            return index >= 0 && index <= 8 ? index : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    protected String recipeIngredientText(int index) {
        if (index < 0) {
            return "";
        }
        JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
        if (index >= ingredients.size()) {
            if (index == 0 && resource.has("ingredient")) {
                return ingredientLabel(resource.get("ingredient"));
            }
            return "";
        }
        return ingredientLabel(ingredients.get(index));
    }

    protected String recipeSlotText(int index) {
        if (index < 0) {
            return "";
        }
        JsonObject keys = jsonObject("keys");
        JsonElement ingredient = keys.get(recipeSlotSymbol(index));
        if (ingredient != null) {
            return ingredientLabel(ingredient);
        }
        if (!"shapeless".equals(normalizedRecipeType())) {
            return "";
        }
        return recipeIngredientText(index);
    }

    protected void putRecipeSlotText(int index, String value) {
        if (deferResourceMutation(() -> putRecipeSlotText(index, value))) {
            return;
        }
        if (index < 0) {
            return;
        }
        JsonArray shape = resource.has("shape") && resource.get("shape").isJsonArray() ? resource.getAsJsonArray("shape") : new JsonArray();
        resource.add("shape", shape);
        while (shape.size() < 3) {
            shape.add("   ");
        }
        JsonObject keys = jsonObject("keys");
        resource.add("keys", keys);
        String symbol = recipeSlotSymbol(index);
        String material = value == null ? "" : value.trim();
        char shapeSymbol = material.isBlank() || "none".equalsIgnoreCase(material) ? ' ' : symbol.charAt(0);
        for (int row = 0; row < 3; row++) {
            String existing = row < shape.size() && !shape.get(row).isJsonNull() ? shape.get(row).getAsString() : "";
            shape.set(row, new JsonPrimitive(paddedShapeRow(existing, row, index, shapeSymbol)));
        }
        if (material.isBlank() || "none".equalsIgnoreCase(material)) {
            keys.remove(symbol);
            removeRecipeIngredientIndex(index);
            return;
        }
        keys.add(symbol, recipeItemObject(material));
    }

    protected void removeRecipeIngredientIndex(int index) {
        if (deferResourceMutation(() -> removeRecipeIngredientIndex(index))) {
            return;
        }
        JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : null;
        if (ingredients == null || index < 0 || index >= ingredients.size()) {
            return;
        }
        ingredients.set(index, new JsonPrimitive(""));
        while (!ingredients.isEmpty()) {
            JsonElement last = ingredients.get(ingredients.size() - 1);
            if (!last.isJsonNull() && !(last.isJsonPrimitive() && last.getAsString().isBlank())) {
                break;
            }
            ingredients.remove(ingredients.size() - 1);
        }
        if (ingredients.isEmpty()) {
            resource.remove("ingredients");
        }
    }

    protected String ingredientLabel(JsonElement ingredient) {
        if (ingredient == null || ingredient.isJsonNull()) {
            return "";
        }
        return ingredient.isJsonPrimitive() ? ingredient.getAsString() : "";
    }

    protected String normalizedRecipeType() {
        return "";
    }

    protected boolean isCookingRecipe(String recipeType) {
        return false;
    }

    protected String paddedShapeRow(String existing, int row, int index, char shapeSymbol) {
        StringBuilder builder = new StringBuilder(existing == null ? "" : existing);
        while (builder.length() < 3) {
            builder.append(' ');
        }
        int column = index % 3;
        if (row == index / 3) {
            builder.setCharAt(column, shapeSymbol);
        }
        return builder.substring(0, 3);
    }

    protected String recipeSlotSymbol(int index) {
        return String.valueOf((char) ('A' + Math.clamp(index, 0, 8)));
    }

    protected void putRecipeIngredientText(int index, String value) {
        if (deferResourceMutation(() -> putRecipeIngredientText(index, value))) {
            return;
        }
        if (index < 0) {
            return;
        }
        JsonArray ingredients = resource.has("ingredients") && resource.get("ingredients").isJsonArray() ? resource.getAsJsonArray("ingredients") : new JsonArray();
        resource.add("ingredients", ingredients);
        while (ingredients.size() <= index) {
            ingredients.add("");
        }
        String material = value == null ? "" : value.trim();
        if (material.isBlank() || "none".equalsIgnoreCase(material)) {
            ingredients.set(index, new JsonPrimitive(""));
            if (index == 0 && (isCookingRecipe(normalizedRecipeType()) || "stonecutting".equals(normalizedRecipeType()))) {
                resource.remove("ingredient");
            }
        } else {
            JsonObject ingredient = recipeItemObject(material);
            ingredients.set(index, ingredient);
            if (index == 0 && (isCookingRecipe(normalizedRecipeType()) || "stonecutting".equals(normalizedRecipeType()))) {
                resource.add("ingredient", ingredient);
            }
        }
    }

    protected void putJsonPathText(String field, String value) {
        if (deferResourceMutation(() -> putJsonPathText(field, value))) {
            return;
        }
        JsonPathParent parent = jsonPathParent(field, true);
        if (parent == null) {
            return;
        }
        if (value == null || value.isBlank()) {
            removeJsonPath(field);
            return;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            jsonPathSet(parent, new JsonPrimitive(Boolean.parseBoolean(trimmed)));
            return;
        }
        try {
            jsonPathSet(parent, new JsonPrimitive(Integer.parseInt(trimmed)));
        } catch (NumberFormatException ignored) {
            jsonPathSet(parent, new JsonPrimitive(trimmed));
        }
    }

    protected void putJsonPathElement(String field, JsonElement value) {
        if (deferResourceMutation(() -> putJsonPathElement(field, value))) {
            return;
        }
        JsonPathParent parent = jsonPathParent(field, true);
        if (parent != null) {
            jsonPathSet(parent, value);
        }
    }

    protected void removeJsonPath(String field) {
        if (deferResourceMutation(() -> removeJsonPath(field))) {
            return;
        }
        JsonPathParent parent = jsonPathParent(field, false);
        if (parent == null) {
            return;
        }
        if (parent.object() != null) {
            parent.object().remove(parent.key());
        } else if (parent.array() != null && isIndex(parent.key())) {
            int index = Integer.parseInt(parent.key());
            if (index >= 0 && index < parent.array().size()) {
                parent.array().remove(index);
            }
        }
        pruneEmptyPath(field.split("\\."));
    }

    protected void putFunctionIdPathText(String field, String value) {
        if (deferResourceMutation(() -> putFunctionIdPathText(field, value))) {
            return;
        }
        String basePath = field.substring(0, field.length() - ".functionId".length());
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value) || "No Function".equals(value)) {
            putJsonPathText(field, "");
            putJsonPathText(basePath + ".inputs", "");
            return;
        }
        putJsonPathText(field, value);
        pruneFunctionInputs(basePath, value);
    }

    protected void pruneFunctionInputs(String basePath, String functionId) {
        JsonObject call = jsonPathObject(basePath);
        JsonObject inputs = call != null && call.has("inputs") && call.get("inputs").isJsonObject() ? call.getAsJsonObject("inputs") : null;
        FlowGraph function = selectedFunctionById(functionId);
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
        if (inputs.isEmpty()) {
            call.remove("inputs");
        }
    }

    protected JsonElement jsonPathElement(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        String[] parts = field.split("\\.");
        JsonElement current = resource;
        for (int i = 0; i < parts.length; i++) {
            if (current == null || current.isJsonNull()) {
                return null;
            }
            JsonElement element;
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                if (!object.has(parts[i]) || object.get(parts[i]).isJsonNull()) {
                    return null;
                }
                element = object.get(parts[i]);
            } else if (current.isJsonArray() && isIndex(parts[i])) {
                JsonArray array = current.getAsJsonArray();
                int index = Integer.parseInt(parts[i]);
                if (index < 0 || index >= array.size() || array.get(index).isJsonNull()) {
                    return null;
                }
                element = array.get(index);
            } else {
                return null;
            }
            if (i == parts.length - 1) {
                return element;
            }
            if (!element.isJsonObject() && !element.isJsonArray()) {
                return null;
            }
            current = element;
        }
        return null;
    }

    protected JsonPathParent jsonPathParent(String field, boolean create) {
        if (field == null || field.isBlank()) {
            return null;
        }
        String[] parts = field.split("\\.");
        JsonElement current = resource;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            String next = parts[i + 1];
            boolean nextArray = isIndex(next);
            if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                if (!object.has(part) || object.get(part).isJsonNull() || (!object.get(part).isJsonObject() && !object.get(part).isJsonArray())) {
                    if (!create) {
                        return null;
                    }
                    object.add(part, nextArray ? new JsonArray() : new JsonObject());
                }
                current = object.get(part);
            } else if (current.isJsonArray() && isIndex(part)) {
                JsonArray array = current.getAsJsonArray();
                int index = Integer.parseInt(part);
                if (index < 0) {
                    return null;
                }
                while (create && array.size() <= index) {
                    array.add(new JsonObject());
                }
                if (index >= array.size()) {
                    return null;
                }
                JsonElement child = array.get(index);
                if (child == null || child.isJsonNull() || (!child.isJsonObject() && !child.isJsonArray())) {
                    if (!create) {
                        return null;
                    }
                    child = nextArray ? new JsonArray() : new JsonObject();
                    array.set(index, child);
                }
                current = child;
            } else {
                return null;
            }
        }
        JsonElement parent = current;
        String key = parts[parts.length - 1];
        return parent.isJsonObject() ? new JsonPathParent(parent.getAsJsonObject(), null, key)
            : parent.isJsonArray() ? new JsonPathParent(null, parent.getAsJsonArray(), key)
            : null;
    }

    protected void jsonPathSet(JsonPathParent parent, JsonElement value) {
        JsonElement safeValue = value != null ? value : new JsonPrimitive("");
        if (parent.object() != null) {
            parent.object().add(parent.key(), safeValue);
        } else if (parent.array() != null && isIndex(parent.key())) {
            int index = Integer.parseInt(parent.key());
            while (parent.array().size() <= index) {
                parent.array().add(new JsonObject());
            }
            parent.array().set(index, safeValue);
        }
    }

    protected boolean isIndex(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    protected record JsonPathParent(JsonObject object, JsonArray array, String key) {
    }

    protected JsonObject jsonPathObject(String field) {
        JsonElement element = jsonPathElement(field);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    protected void pruneEmptyPath(String[] parts) {
        for (int length = parts.length - 1; length > 0; length--) {
            JsonObject parent = resource;
            for (int i = 0; i < length - 1; i++) {
                if (!parent.has(parts[i]) || !parent.get(parts[i]).isJsonObject()) {
                    parent = null;
                    break;
                }
                parent = parent.getAsJsonObject(parts[i]);
            }
            if (parent == null || !parent.has(parts[length - 1]) || !parent.get(parts[length - 1]).isJsonObject()) {
                continue;
            }
            JsonObject child = parent.getAsJsonObject(parts[length - 1]);
            if (!child.isEmpty()) {
                break;
            }
            parent.remove(parts[length - 1]);
        }
    }

    protected String jsonText(String key) {
        return jsonText(resource, key);
    }

    @Override
    protected String jsonText(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    @Override
    public void renderStudioOverlay(IDrawContext context, int mouseX, int mouseY, float delta) {
    }

    protected int jsonObjectSize(String key) {
        return resource.has(key) && resource.get(key).isJsonObject() ? resource.getAsJsonObject(key).size() : 0;
    }

    protected JsonObject jsonObject(String key) {
        return resource.has(key) && resource.get(key).isJsonObject() ? resource.getAsJsonObject(key) : new JsonObject();
    }

    protected String resourceDisplayName() {
        return "Resource";
    }

    protected void drawFormattedLine(IDrawContext context, String value, int startX, int y, int fallbackColor, boolean shadow) {
        context.drawRichText(value, startX, y, fallbackColor, shadow);
    }

    protected int centeredTextX(String value, int centerX) {
        return centerX - textWidth(value) / 2;
    }

    protected String applyMentionPreview(String line) {
        String template = mentionPreviewTemplate();
        return line.replace("@Alex", template.replace("{player}", "Alex"));
    }

    protected String mentionPreviewTemplate() {
        return "<yellow>@{player}</yellow>";
    }

    protected String recipePreviewMaterial(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        if (!encoded.contains(":")) {
            return encoded;
        }
        if (encoded.startsWith("content:")) {
            String contentId = encoded.substring("content:".length());
            FlowManager manager = FlowManager.getInstance();
            if (manager != null && serverId != null) {
                CustomContentDefinition content = manager.getCustomContent(serverId, contentId);
                if (content != null && content.getMaterial() != null && !content.getMaterial().isBlank()) {
                    return content.getMaterial();
                }
            }
            return "BARRIER";
        }
        if (encoded.startsWith("provider:")) {
            return "PAPER";
        }
        return encoded;
    }

    protected void drawRecipeItem(IDrawContext context, String material, int amount, int x, int y, int scale) {
        String previewMaterial = material == null ? "" : material.trim();
        if (previewMaterial.isBlank() || "none".equalsIgnoreCase(previewMaterial)) {
            return;
        }
        int iconSize = Math.max(16, 16 * scale);
        MinecraftRenderItem item = ItemIconPreview.project(serverId, previewMaterial).toRenderItem();
        if (item == null) {
            return;
        }
        if (scale <= 1) {
            context.drawItem(item, x, y, 0);
        } else {
            context.getMatrices().push();
            context.getMatrices().translate(x, y, 0);
            context.getMatrices().scale(scale, scale, 1);
            context.drawItem(item, 0, 0, 0);
            context.getMatrices().pop();
        }
        if (amount > 1) {
            drawRecipeItemAmount(context, Math.clamp(amount, 1, 64), x, y, iconSize);
        }
    }

    protected void drawRecipeItemAmount(IDrawContext context, int amount, int x, int y, int iconSize) {
        String text = String.valueOf(amount);
        int textX = x + iconSize - textWidth(text);
        int textY = y + iconSize - 8;
        context.drawText(text, textX + 1, textY + 1, 0xFF000000, false);
        context.drawText(text, textX, textY, 0xFFFFFFFF, false);
    }

    protected SquareButtonWidget headerButton(String icon, String hint, Runnable action) {
        return new SquareButtonWidget.Builder()
            .size(18, 18)
            .identifier(Identifier.icon(icon))
            .hint(hint)
            .onClick(action)
            .entranceAnimation(false)
            .build();
    }

    protected Screen hostScreen() {
        return host != null ? host : this;
    }

    protected int parseInt(String value, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(value), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    protected String safeText(String value) {
        return value == null ? "" : value;
    }

    protected int textWidth(String value) {
        String clean = safeText(value).replaceAll("(?i)[&�][0-9a-fk-or]", "").replaceAll("<[^>]+>", "");
        if (RemotelyClient.tr != null) {
            return RemotelyClient.tr.getWidth(clean);
        }
        return clean.length() * 6;
    }

    @Override
    public String getDesktopAppId() {
        return type + "-designer";
    }

    @Override
    public String getDesktopAppTitle() {
        return titleForType(type);
    }

    @Override
    public String getDesktopAppIconPath() {
        return iconForType(type);
    }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() {
        return DesktopWindowBehavior.SINGLETON;
    }

    @Override
    public boolean shouldForceSuperScreen() {
        return desktopMode && !(parent instanceof Screen);
    }

    @Override
    public List<AnimatedWidget> getStudioHeaderButtons() {
        return headerButtons();
    }

    protected static String titleForType(String type) {
        return switch (type) {
            case ReSyncResourceDragPayload.RECIPE_DEFINITION -> "Recipe Designer";
            case ReSyncResourceDragPayload.MOTD_PROFILE -> "MOTD Designer";
            case ReSyncResourceDragPayload.MESSAGE_RULE -> "Message Rule Designer";
            case ReSyncResourceDragPayload.TEXT_TEMPLATE -> "Text Designer";
            case ReSyncResourceDragPayload.CHAT -> "Chat Designer";
            case ReSyncResourceDragPayload.TRADE_PROFILE -> "Trade Designer";
            case ReSyncResourceDragPayload.NPC_DEFINITION -> "NPC Designer";
            case ReSyncResourceDragPayload.LOOT_TABLE -> "Loot Table Designer";
            default -> "Resource Designer";
        };
    }

    protected static String iconForType(String type) {
        return switch (type) {
            case ReSyncResourceDragPayload.RECIPE_DEFINITION -> "crafting.png";
            case ReSyncResourceDragPayload.MOTD_PROFILE -> "hi.png";
            case ReSyncResourceDragPayload.MESSAGE_RULE -> "edit.png";
            case ReSyncResourceDragPayload.TEXT_TEMPLATE -> "text.png";
            case ReSyncResourceDragPayload.CHAT -> "chat.png";
            case ReSyncResourceDragPayload.TRADE_PROFILE -> "trade.png";
            case ReSyncResourceDragPayload.NPC_DEFINITION -> "steve.png";
            case ReSyncResourceDragPayload.LOOT_TABLE -> "resources.png";
            default -> "edit.png";
        };
    }

}
