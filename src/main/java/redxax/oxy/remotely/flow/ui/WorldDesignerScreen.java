package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.data.flow.world.WorldDashboardEntry;
import redxax.oxy.remotely.data.flow.world.WorldInventoryGroup;
import redxax.oxy.remotely.data.flow.world.WorldOperationResult;
import redxax.oxy.remotely.data.flow.world.WorldProfileSettings;
import redxax.oxy.remotely.data.flow.world.WorldRegistryEntry;
import redxax.oxy.remotely.data.flow.world.WorldRegistryJson;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.data.FlowWorkspaceDocument;
import redxax.oxy.remotely.flow.ui.studio.ReSyncCollaborativeView;
import redxax.oxy.remotely.flow.ui.studio.ReSyncContentBrowserWidget;
import redxax.oxy.remotely.flow.ui.studio.StudioHeaderProvider;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import redxax.oxy.remotely.flow.ui.studio.StudioSelectorView;
import redxax.oxy.remotely.flow.ui.studio.WorldResourceCreator;
import redxax.oxy.remotely.flow.ui.studio.WorldStudioDocumentView;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ContextMenuWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.JsonTreeParser;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static restudio.rescreen.config.Config.desktopMode;

public class WorldDesignerScreen extends StudioScreen implements DesktopWindowBehaviorProvider, StudioHeaderProvider, StudioSelectorView, WorldStudioDocumentView, ReSyncCollaborativeView {
    private static final List<String> DIFFICULTY_OPTIONS = List.of("PEACEFUL", "EASY", "NORMAL", "HARD");
    private static final List<String> GAME_MODE_OPTIONS = List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
    private static final List<String> EDITABLE_WORLD_FIELDS = List.of(
        "difficulty", "isolatedPlayerState", "timeLockEnabled", "lockedTime", "weatherLockEnabled", "lockedStorm", "lockedThundering");
    private static final List<String> EDITABLE_PROFILE_FIELDS = List.of(
        "alias", "hidden", "accessPermission", "bypassPermission", "respawnWorld", "forceGameMode", "gameMode",
        "customSpawnEnabled", "spawnX", "spawnY", "spawnZ", "spawnYaw", "spawnPitch", "pvpEnabled",
        "keepSpawnLoaded", "autoSaveEnabled", "animalSpawnsEnabled", "monsterSpawnsEnabled", "hungerEnabled",
        "autoHealEnabled", "bedRespawnEnabled", "anchorRespawnEnabled", "nonLivingEntitySpawnsEnabled",
        "arrivalMessage", "denyMessage", "inventoryGroupId", "linkedNetherWorld", "linkedEndWorld", "linkedOverworld",
        "netherScale", "endScale", "autoLinkNetherPortal", "autoLinkEndPortal");

    private final String worldName;
    private final String serverId;
    private final Object parent;
    protected final StudioScreen host;
    private final Container detailPane;
    private final VersionedEditorDraft<JsonObject> worldDraft;
    private final List<AnimatedWidget> detailWidgets = new ArrayList<>();
    private final List<AnimatedWidget> headerActions = new ArrayList<>();
    private IconButton actionsHeaderButton;
    private ItemSelectorWidget activePlayerSelector;
    private WorldDetailForm detailForm;
    private final History<JsonObject> worldHistory = history(this::worldDocumentSnapshot, this::restoreWorldDocument);
    private final HistoryRebaseCoordinator<JsonObject> historyRebase;
    private volatile long collaborationLifecycle = 1L;
    private boolean worldHistoryReady;
    private boolean applyingWorldDocument;
    private boolean initialized;
    private WorldRegistryEntry encodedWorld;
    private long encodedWorldUpdatedAt;
    private JsonObject encodedWorldDocument;
    private int x;
    private int y;
    private int width;
    private int height;

    @Override
    public JsonObject collaborationDocument() {
        FlowManager manager = worldManager();
        WorldRegistryEntry world = manager != null ? manager.getWorld(serverId, worldName) : null;
        if (world == null) {
            return null;
        }
        JsonObject document = encodedWorldDocument(world);
        if (detailForm == null) {
            return document;
        }
        WorldProfileSettings profile = world.getProfileSettings();
        JsonObject profileDocument = document.getAsJsonObject("profileSettings");
        if (profileDocument == null) {
            profileDocument = new JsonObject();
            document.add("profileSettings", profileDocument);
        }
        profileDocument.addProperty("alias", detailForm.alias.getText());
        profileDocument.addProperty("hidden", detailForm.hidden.getValue());
        profileDocument.addProperty("accessPermission", detailForm.accessPermission.getText());
        profileDocument.addProperty("bypassPermission", detailForm.bypassPermission.getText());
        profileDocument.addProperty("respawnWorld", safeText(detailForm.respawnWorld.value()));
        profileDocument.addProperty("forceGameMode", detailForm.forceGameMode.getValue());
        profileDocument.addProperty("gameMode", safeText(detailForm.gameMode.value()));
        profileDocument.addProperty("customSpawnEnabled", detailForm.customSpawn.getValue());
        profileDocument.addProperty("spawnX", valueOr(detailForm.spawnX, profile.getSpawnX()));
        profileDocument.addProperty("spawnY", valueOr(detailForm.spawnY, profile.getSpawnY()));
        profileDocument.addProperty("spawnZ", valueOr(detailForm.spawnZ, profile.getSpawnZ()));
        profileDocument.addProperty("spawnYaw", floatValueOr(detailForm.spawnYaw, profile.getSpawnYaw()));
        profileDocument.addProperty("spawnPitch", floatValueOr(detailForm.spawnPitch, profile.getSpawnPitch()));
        profileDocument.addProperty("pvpEnabled", detailForm.pvp.getValue());
        profileDocument.addProperty("keepSpawnLoaded", detailForm.keepSpawn.getValue());
        profileDocument.addProperty("autoSaveEnabled", detailForm.autoSave.getValue());
        profileDocument.addProperty("animalSpawnsEnabled", detailForm.animals.getValue());
        profileDocument.addProperty("monsterSpawnsEnabled", detailForm.monsters.getValue());
        profileDocument.addProperty("hungerEnabled", detailForm.hunger.getValue());
        profileDocument.addProperty("autoHealEnabled", detailForm.autoHeal.getValue());
        profileDocument.addProperty("bedRespawnEnabled", detailForm.bedRespawn.getValue());
        profileDocument.addProperty("anchorRespawnEnabled", detailForm.anchorRespawn.getValue());
        profileDocument.addProperty("nonLivingEntitySpawnsEnabled", detailForm.miscSpawns.getValue());
        profileDocument.addProperty("arrivalMessage", detailForm.arrivalMessage.getText());
        profileDocument.addProperty("denyMessage", detailForm.denyMessage.getText());
        profileDocument.addProperty("inventoryGroupId", inventoryGroupStoredValue(detailForm.inventoryGroup.value()));
        profileDocument.addProperty("linkedNetherWorld", safeText(detailForm.netherWorld.value()));
        profileDocument.addProperty("linkedEndWorld", safeText(detailForm.endWorld.value()));
        profileDocument.addProperty("linkedOverworld", safeText(detailForm.overworld.value()));
        profileDocument.addProperty("netherScale", valueOr(detailForm.netherScale, profile.getNetherScale()));
        profileDocument.addProperty("endScale", valueOr(detailForm.endScale, profile.getEndScale()));
        profileDocument.addProperty("autoLinkNetherPortal", detailForm.autoNether.getValue());
        profileDocument.addProperty("autoLinkEndPortal", detailForm.autoEnd.getValue());
        document.addProperty("difficulty", safeText(detailForm.difficulty.value()));
        document.addProperty("isolatedPlayerState", detailForm.isolated.getValue());
        document.addProperty("timeLockEnabled", detailForm.timeLock.getValue());
        document.addProperty("lockedTime", longValueOr(detailForm.lockedTime, world.getLockedTime()));
        document.addProperty("weatherLockEnabled", detailForm.weatherLock.getValue());
        document.addProperty("lockedStorm", detailForm.storm.getValue());
        document.addProperty("lockedThundering", detailForm.thundering.getValue());
        return document;
    }

    @Override
    public long collaborationLifecycle() {
        return collaborationLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return worldDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return worldDraft.requestProjection(ReSyncResourceDragPayload.WORLD, worldName, this::collaborationDocument,
            projection -> completion.accept(collaborationSnapshot(lifecycle, projection)));
    }

    private CollaborationDocumentSnapshot collaborationSnapshot(long lifecycle,
                                                                 VersionedEditorDraft.ProjectionSnapshot projection) {
        RuntimeException failure = projection.failure();
        JsonObject document = null;
        if (failure == null) {
            try {
                document = parseWorldDocument(projection.payload());
                if (document == null) {
                    failure = new IllegalStateException("Collaboration Snapshot Is Empty");
                }
            } catch (RuntimeException | Error exception) {
                failure = exception instanceof RuntimeException runtime ? runtime : new IllegalStateException(exception);
            }
        }
        if (failure == null && collaborationLifecycle != lifecycle) {
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
        JsonObject semanticDocument = document != null ? document.deepCopy() : null;
        return worldDraft.runMutation(() -> {
            if (semanticDocument == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (worldManager() == null) {
                throw new IllegalStateException("World Manager Is Required");
            }
            applyCollaborationDocument(semanticDocument, List.of());
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        JsonObject semanticDocument = document != null ? document.deepCopy() : null;
        if (worldDraft.defer(() -> applyCollaborationDocument(semanticDocument, List.of()))) {
            return;
        }
        FlowManager manager = worldManager();
        if (manager == null || semanticDocument == null) {
            return;
        }
        worldDraft.markMutation();
        applyingWorldDocument = true;
        try {
            manager.applyCollaborativeWorld(serverId, decodeWorldDocument(semanticDocument));
            invalidateEncodedWorld();
            refreshDetails();
        } finally {
            applyingWorldDocument = false;
        }
    }

    @Override
    public void rebaseCollaborationHistory(List<WorkspacePatch<JsonElement>> patches) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        historyRebase.request(patches, copiedPatches -> snapshot -> {
            JsonObject rebased = snapshot == null ? new JsonObject() : snapshot.deepCopy();
            FlowWorkspaceDocument.apply(rebased, copiedPatches);
            return rebased.deepCopy();
        });
    }

    @Override
    public boolean hasUnsavedChanges() {
        return worldHistoryReady && worldHistory.isDirty((current, saved) ->
            editableWorldDocument(current).equals(editableWorldDocument(saved)));
    }

    private JsonObject worldDocumentSnapshot() {
        JsonObject document = collaborationDocument();
        return document != null ? document.deepCopy() : new JsonObject();
    }

    static JsonObject encodeWorldDocument(WorldRegistryEntry world) {
        return WorldRegistryJson.write(world);
    }

    static JsonObject parseWorldDocument(String payload) {
        JsonElement parsed = JsonTreeParser.parse(payload);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("World Document Must Be An Object");
        }
        return parsed.getAsJsonObject();
    }

    static WorldRegistryEntry decodeWorldDocument(JsonObject document) {
        return WorldRegistryJson.read(document);
    }

    static void preserveUneditedProfileSettings(WorldProfileSettings source, WorldProfileSettings target) {
        if (source == null || target == null) {
            return;
        }
        target.setEntryFeeEnabled(source.isEntryFeeEnabled());
        target.setEntryFee(source.getEntryFee());
    }

    private JsonObject encodedWorldDocument(WorldRegistryEntry world) {
        if (world != encodedWorld || encodedWorldDocument == null || world.getUpdatedAt() != encodedWorldUpdatedAt) {
            encodedWorld = world;
            encodedWorldUpdatedAt = world.getUpdatedAt();
            encodedWorldDocument = encodeWorldDocument(world);
        }
        return encodedWorldDocument.deepCopy();
    }

    private void invalidateEncodedWorld() {
        encodedWorld = null;
        encodedWorldDocument = null;
    }

    private JsonObject editableWorldDocument(JsonObject document) {
        JsonObject editable = new JsonObject();
        copyFields(document, editable, EDITABLE_WORLD_FIELDS);
        JsonObject profile = document != null && document.has("profileSettings") && document.get("profileSettings").isJsonObject()
            ? document.getAsJsonObject("profileSettings") : null;
        JsonObject editableProfile = new JsonObject();
        copyFields(profile, editableProfile, EDITABLE_PROFILE_FIELDS);
        editable.add("profileSettings", editableProfile);
        return editable;
    }

    private void copyFields(JsonObject source, JsonObject target, List<String> fields) {
        if (source == null) {
            return;
        }
        for (String field : fields) {
            JsonElement value = source.get(field);
            if (value != null) {
                target.add(field, value.deepCopy());
            }
        }
    }

    private void restoreWorldDocument(JsonObject snapshot) {
        JsonObject semanticSnapshot = snapshot != null ? snapshot.deepCopy() : null;
        if (worldDraft.defer(() -> restoreWorldDocument(semanticSnapshot))) {
            return;
        }
        FlowManager manager = worldManager();
        if (manager == null || semanticSnapshot == null || semanticSnapshot.isEmpty()) {
            return;
        }
        worldDraft.markMutation();
        JsonObject current = collaborationDocument();
        JsonObject restored = current != null ? current.deepCopy() : semanticSnapshot.deepCopy();
        FlowWorkspaceDocument.apply(restored, FlowWorkspaceDocument.diff(
            editableWorldDocument(current), editableWorldDocument(semanticSnapshot)));
        applyingWorldDocument = true;
        try {
            manager.applyCollaborativeWorld(serverId, decodeWorldDocument(restored));
            invalidateEncodedWorld();
            detailForm = null;
            refreshDetails();
        } finally {
            applyingWorldDocument = false;
        }
    }

    private void captureWorldHistory() {
        if (worldHistoryReady && !applyingWorldDocument) {
            worldHistory.capture();
        }
    }

    private double valueOr(TextInputWidget input, double fallback) {
        Double value = parseNullableDouble(input.getText());
        return value != null ? value : fallback;
    }

    private float floatValueOr(TextInputWidget input, float fallback) {
        Float value = parseNullableFloat(input.getText());
        return value != null ? value : fallback;
    }

    private long longValueOr(TextInputWidget input, long fallback) {
        Long value = parseNullableLong(input.getText());
        return value != null ? value : fallback;
    }

    public WorldDesignerScreen(String worldName, String serverId, Object parent) {
        this.worldName = safeText(worldName);
        this.serverId = safeText(serverId);
        this.parent = parent;
        this.host = parent instanceof StudioScreen screen ? screen : null;
        this.detailPane = new Container("world-detail", 0, 0, 300, 100);
        this.detailPane.layout(new ManagedLayout()).columns(1).padding(5).scrolling(true).backgroundDrawing(true);
        this.worldDraft = new VersionedEditorDraft<>(new JsonObject(), JsonTreeParser::write,
            WorldDesignerScreen::parseWorldDocument, (previous, replacement) -> {}, failure -> {},
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        this.historyRebase = new HistoryRebaseCoordinator<>(worldHistory,
            () -> serverId + ":" + worldName, () -> collaborationLifecycle,
            () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
        createHeaderActions();
    }

    @Override
    public void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        refreshDetails();
    }

    @Override
    public List<AnimatedWidget> getStudioHeaderButtons() {
        return headerActions;
    }

    @Override
    public void resize(int width, int height) {
        int panelWidth = host != null ? host.studioContentBrowserPanelWidth() : 0;
        int gap = panelWidth > 0 ? ReSyncContentBrowserWidget.STUDIO_CONTENT_BROWSER_GAP : 18;
        int rightPad = panelWidth > 0 ? ReSyncContentBrowserWidget.STUDIO_CONTENT_BROWSER_GAP : 18;
        this.x = panelWidth > 0 ? panelWidth + gap : 18;
        this.y = ReSyncContentBrowserWidget.STUDIO_CONTENT_BROWSER_TOP;
        this.height = Math.max(80, height - this.y - ReSyncContentBrowserWidget.STUDIO_CONTENT_BROWSER_BOTTOM);
        this.width = Math.max(120, width - this.x - rightPad);
        updateLayout();
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        worldDraft.drain();
        historyRebase.drain();
        init();
        updateLayout();
        detailPane.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
        collaborationLifecycle++;
        historyRebase.close();
        worldDraft.close();
        super.removed();
    }

    @Override
    public void close() {
        collaborationLifecycle++;
        historyRebase.close();
        worldDraft.close();
        super.close();
    }


    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (routeStudioContextMenuMouseClicked(event)) {
            return true;
        }
        init();
        if (activePlayerSelector != null && activePlayerSelector.visible && Widget.dispatchMouseClicked(activePlayerSelector, event)) {
            return true;
        }
        captureWorldHistory();
        return dispatchWorldInput(() -> Widget.dispatchMouseClicked(detailPane, event));
    }


    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (routeStudioContextMenuMouseReleased(event)) {
            return true;
        }
        if (activePlayerSelector != null && activePlayerSelector.visible && Widget.dispatchMouseReleased(activePlayerSelector, event)) {
            return true;
        }
        return dispatchWorldInput(() -> Widget.dispatchMouseReleased(detailPane, event));
    }


    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (routeStudioContextMenuMouseDragged(event)) {
            return true;
        }
        if (activePlayerSelector != null && activePlayerSelector.visible && Widget.dispatchMouseDragged(activePlayerSelector, event)) {
            return true;
        }
        return dispatchWorldInput(() -> Widget.dispatchMouseDragged(detailPane, event));
    }


    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        if (activePlayerSelector != null && activePlayerSelector.visible && Widget.dispatchMouseScrolled(activePlayerSelector, event)) {
            return true;
        }
        return dispatchWorldInput(() -> Widget.dispatchMouseScrolled(detailPane, event));
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (handleStudioHistoryShortcut(event)) {
            return true;
        }
        if (activePlayerSelector != null && activePlayerSelector.visible && Widget.dispatchKeyPressed(activePlayerSelector, event)) {
            return true;
        }
        captureWorldHistory();
        return dispatchWorldInput(() -> Widget.dispatchKeyPressed(detailPane, event));
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        if (activePlayerSelector != null && activePlayerSelector.visible && Widget.dispatchTextInput(activePlayerSelector, event)) {
            return true;
        }
        captureWorldHistory();
        return dispatchWorldInput(() -> Widget.dispatchTextInput(detailPane, event));
    }

    private boolean dispatchWorldInput(BooleanSupplier dispatch) {
        JsonObject before = editableWorldDocument(worldDocumentSnapshot());
        boolean handled = dispatch.getAsBoolean();
        if (!before.equals(editableWorldDocument(worldDocumentSnapshot()))) {
            worldDraft.markMutation();
        }
        return handled;
    }

    @Override
    public boolean hasActiveStudioSelector() {
        return activePlayerSelector != null && activePlayerSelector.visible;
    }

    private void createHeaderActions() {
        headerActions.add(studioHeaderButton("Save", "save.png", this::saveCurrentWorld, ThemeManager.getAccent("nice")));
        headerActions.add(studioHeaderButton("Map", "map.png", () -> worldManager().openWorldMap(serverId, null, worldName), null));
        actionsHeaderButton = studioHeaderButton("Actions", "ContextMenu.png", this::showCurrentWorldActionsMenu, null);
        headerActions.add(actionsHeaderButton);
        headerActions.add(studioHeaderButton("Groups", "resources.png", this::showInventoryGroupsPopup, null));
        headerActions.add(studioHeaderButton("History", "history.png", () -> worldManager().requestWorldAuditSnapshot(serverId), null));
    }

    private void saveCurrentWorld() {
        if (detailForm == null) {
            return;
        }
        FlowManager manager = worldManager();
        WorldRegistryEntry world = manager != null ? manager.getWorld(serverId, worldName) : null;
        if (world == null) {
            return;
        }
        saveWorld(world, detailForm);
    }

    private void showCurrentWorldActionsMenu() {
        FlowManager manager = worldManager();
        if (manager == null) {
            return;
        }
        WorldRegistryEntry world = manager.getWorld(serverId, worldName);
        boolean loaded = world != null && world.isLoaded();
        ContextMenuWidget.Builder builder = new ContextMenuWidget.Builder(this)
            .addHeaderButton("steve.png", () -> showWorldTeleportPopup(worldName), "Teleport Player")
            .addHeaderButton("search.png", () -> manager.whoWorld(serverId, worldName), "View Players")
            .addIconItem(loaded ? "Unload World" : "Load World", loaded ? "hide.png" : "add.png", () -> {
                if (loaded) {
                    showWorldUnloadPopup(worldName);
                } else {
                    manager.loadWorld(serverId, worldName);
                }
            }, "")
            .addIconItem("Clone World", "copy.png", () -> showCloneWorldPopup(worldName), "")
            .addIconItem("Purge Entities", "delete.png", () -> showWorldPurgePopup(worldName), "", ThemeManager.getAccent("calm"))
            .addIconItem("Delete World", "delete.png", () -> WorldResourceCreator.showDeletePopup(this, serverId, worldName, this::refreshDetails), "", ThemeManager.getAccent("danger"));
        if (actionsHeaderButton != null) {
            showContextMenu(actionsHeaderButton.getX(), actionsHeaderButton.getY() + actionsHeaderButton.getHeight() + 2, builder);
            return;
        }
        showContextMenu(x + 20, y + 20, builder);
    }

    private IconButton studioHeaderButton(String label, String icon, Runnable action, Accent accent) {
        IconButton.Builder builder = new IconButton.Builder()
            .label(label)
            .imagePath(icon)
            .size(0, 18)
            .autoWidthOnTextChange(true)
            .hint(label)
            .entranceAnimation(false)
            .onClick(action);
        if (accent != null) {
            builder.accentType(accent);
        }
        return builder.build();
    }

    private FlowManager worldManager() {
        return FlowManager.getInstance();
    }

    private void updateLayout() {
        detailPane.setPosition(x, y);
        detailPane.setSize(Math.max(120, width), height);
    }

    @Override
    public void refreshWorlds() {
        refreshDetails();
    }

    private void refreshDetails() {
        FlowManager manager = worldManager();
        WorldRegistryEntry world = manager != null ? manager.getWorld(serverId, worldName) : null;
        if (world == null) {
            clearDetailWidgets();
            addDetailWidget(emptyDetailButton());
            detailPane.updateWidgetPositions();
            detailForm = null;
            return;
        }
        if (detailForm != null && worldName.equalsIgnoreCase(detailForm.worldName)) {
            boolean replaceDraft = applyingWorldDocument || !hasUnsavedChanges();
            boolean authoritativeChange = replaceDraft && !editableWorldDocument(worldDocumentSnapshot())
                .equals(editableWorldDocument(encodedWorldDocument(world)));
            detailForm.update(world, replaceDraft);
            if (authoritativeChange && !applyingWorldDocument && worldHistoryReady) {
                worldHistory.clear();
            }
            return;
        }
        clearDetailWidgets();
        WorldProfileSettings profile = world.getProfileSettings();
        int rowWidth = Math.max(220, detailPane.getWidth() - 18);
        int halfWidth = rowWidth / 2 - 5;
        int thirdWidth = rowWidth / 3 - 5;
        TextInputWidget alias = detailInput("Alias", profile.getAlias(), halfWidth);
        DropdownField difficulty = dropdownField(DIFFICULTY_OPTIONS,
            safeText(world.getDifficulty()).isBlank() ? "NORMAL" : safeText(world.getDifficulty()).toUpperCase(Locale.ROOT), halfWidth);
        ToggleWidget hidden = detailToggle("Hidden", profile.isHidden(), 92);
        ToggleWidget forceGameMode = detailToggle("Force Game Mode", profile.isForceGameMode(), 132);
        DropdownField gameMode = dropdownField(GAME_MODE_OPTIONS,
            safeText(profile.getGameMode()).isBlank() ? "SURVIVAL" : safeText(profile.getGameMode()).toUpperCase(Locale.ROOT), halfWidth);
        ToggleWidget pvp = detailToggle("PVP", profile.isPvpEnabled(), 70);
        ToggleWidget autoSave = detailToggle("Auto Save", profile.isAutoSaveEnabled(), 105);
        ToggleWidget keepSpawn = detailToggle("Keep Spawn", profile.isKeepSpawnLoaded(), 112);
        ToggleWidget animals = detailToggle("Animals", profile.isAnimalSpawnsEnabled(), 92);
        ToggleWidget monsters = detailToggle("Monsters", profile.isMonsterSpawnsEnabled(), 100);
        ToggleWidget hunger = detailToggle("Hunger", profile.isHungerEnabled(), 90);
        ToggleWidget autoHeal = detailToggle("Auto Heal", profile.isAutoHealEnabled(), 100);
        ToggleWidget bedRespawn = detailToggle("Bed Respawn", profile.isBedRespawnEnabled(), 118);
        ToggleWidget anchorRespawn = detailToggle("Anchor Respawn", profile.isAnchorRespawnEnabled(), 132);
        ToggleWidget miscSpawns = detailToggle("Misc Spawns", profile.isNonLivingEntitySpawnsEnabled(), 115);
        TextInputWidget accessPermission = detailInput("Access Permission", profile.getAccessPermission(), halfWidth);
        TextInputWidget bypassPermission = detailInput("Bypass Permission", profile.getBypassPermission(), halfWidth);
        TextInputWidget arrivalMessage = detailInput("Arrival Message", profile.getArrivalMessage(), halfWidth);
        TextInputWidget denyMessage = detailInput("Deny Message", profile.getDenyMessage(), halfWidth);
        DropdownField respawnWorld = dropdownField(worldNameOptions(), profile.getRespawnWorld(), halfWidth);
        DropdownField inventoryGroup = dropdownField(inventoryGroupOptions(), inventoryGroupDropdownValue(profile.getInventoryGroupId()), halfWidth, this::inventoryGroupLabel);
        ToggleWidget customSpawn = detailToggle("Custom Spawn", profile.isCustomSpawnEnabled(), 120);
        TextInputWidget spawnX = detailInput("X", formatDecimal(profile.getSpawnX()), 82);
        TextInputWidget spawnY = detailInput("Y", formatDecimal(profile.getSpawnY()), 82);
        TextInputWidget spawnZ = detailInput("Z", formatDecimal(profile.getSpawnZ()), 82);
        TextInputWidget spawnYaw = detailInput("Yaw", formatDecimal(profile.getSpawnYaw()), 82);
        TextInputWidget spawnPitch = detailInput("Pitch", formatDecimal(profile.getSpawnPitch()), 82);
        DropdownField netherWorld = dropdownField(linkedWorldOptions(), profile.getLinkedNetherWorld(), thirdWidth);
        DropdownField endWorld = dropdownField(linkedWorldOptions(), profile.getLinkedEndWorld(), thirdWidth);
        DropdownField overworld = dropdownField(linkedWorldOptions(), profile.getLinkedOverworld(), thirdWidth);
        TextInputWidget netherScale = detailInput("Nether Scale", formatDecimal(profile.getNetherScale()), thirdWidth);
        TextInputWidget endScale = detailInput("End Scale", formatDecimal(profile.getEndScale()), thirdWidth);
        ToggleWidget autoNether = detailToggle("Auto Nether", profile.isAutoLinkNetherPortal(), 112);
        ToggleWidget autoEnd = detailToggle("Auto End", profile.isAutoLinkEndPortal(), 92);
        ToggleWidget isolated = detailToggle("Isolated State", world.isIsolatedPlayerState(), 125);
        ToggleWidget timeLock = detailToggle("Time Lock", world.isTimeLockEnabled(), 100);
        TextInputWidget lockedTime = detailInput("Locked Time", String.valueOf(world.getLockedTime()), 120);
        ToggleWidget weatherLock = detailToggle("Weather Lock", world.isWeatherLockEnabled(), 118);
        ToggleWidget storm = detailToggle("Storm", world.isLockedStorm(), 78);
        ToggleWidget thundering = detailToggle("Thunder", world.isLockedThundering(), 92);
        IconButton statusMetric = metricButton("", "hide.png", 120);
        IconButton playersMetric = metricButton("", "steve.png", 120);
        IconButton environmentMetric = metricButton("", "earth.png", 145);
        IconButton generatorMetric = metricButton("", "resources.png", 160);

        addDetailWidget(metricRow(rowWidth, statusMetric, playersMetric, environmentMetric, generatorMetric));
        addDetailWidget(row("Identity", rowWidth, alias, difficulty.widget()));
        addDetailWidget(row("Access", rowWidth, hidden, forceGameMode, gameMode.widget()));
        addDetailWidget(row("Permissions", rowWidth, accessPermission, bypassPermission));
        addDetailWidget(row("Messages", rowWidth, arrivalMessage, denyMessage));
        addDetailWidget(row("Rules", rowWidth, pvp, autoSave, keepSpawn, animals, monsters));
        addDetailWidget(row("Player State", rowWidth, hunger, autoHeal, bedRespawn, anchorRespawn, miscSpawns));
        addDetailWidget(row("Travel", rowWidth, respawnWorld.widget(), inventoryGroup.widget()));
        addDetailWidget(row("Spawn", rowWidth, customSpawn, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch));
        addDetailWidget(row("Links", rowWidth, netherWorld.widget(), endWorld.widget(), overworld.widget()));
        addDetailWidget(row("Portal Scale", rowWidth, netherScale, endScale, autoNether, autoEnd));
        addDetailWidget(row("Runtime", rowWidth, isolated, timeLock, lockedTime, weatherLock, storm, thundering));
        detailForm = new WorldDetailForm(world.getWorldName(), alias, difficulty, hidden, forceGameMode, gameMode, pvp, autoSave,
            keepSpawn, animals, monsters, hunger, autoHeal, bedRespawn, anchorRespawn, miscSpawns, accessPermission,
            bypassPermission, arrivalMessage, denyMessage, respawnWorld, inventoryGroup, customSpawn, spawnX, spawnY, spawnZ,
            spawnYaw, spawnPitch, netherWorld, endWorld, overworld, netherScale, endScale, autoNether, autoEnd, isolated,
            timeLock, lockedTime, weatherLock, storm, thundering, statusMetric, playersMetric, environmentMetric, generatorMetric);
        detailForm.update(world, true);
        detailPane.updateWidgetPositions();
        if (!worldHistoryReady) {
            worldHistoryReady = true;
            worldHistory.clear();
        }
    }

    private void clearDetailWidgets() {
        for (AnimatedWidget widget : new ArrayList<>(detailWidgets)) {
            detailPane.removeWidget(widget);
        }
        detailWidgets.clear();
    }

    private class WorldDetailForm {
        private final String worldName;
        private final TextInputWidget alias;
        private final DropdownField difficulty;
        private final ToggleWidget hidden;
        private final ToggleWidget forceGameMode;
        private final DropdownField gameMode;
        private final ToggleWidget pvp;
        private final ToggleWidget autoSave;
        private final ToggleWidget keepSpawn;
        private final ToggleWidget animals;
        private final ToggleWidget monsters;
        private final ToggleWidget hunger;
        private final ToggleWidget autoHeal;
        private final ToggleWidget bedRespawn;
        private final ToggleWidget anchorRespawn;
        private final ToggleWidget miscSpawns;
        private final TextInputWidget accessPermission;
        private final TextInputWidget bypassPermission;
        private final TextInputWidget arrivalMessage;
        private final TextInputWidget denyMessage;
        private final DropdownField respawnWorld;
        private final DropdownField inventoryGroup;
        private final ToggleWidget customSpawn;
        private final TextInputWidget spawnX;
        private final TextInputWidget spawnY;
        private final TextInputWidget spawnZ;
        private final TextInputWidget spawnYaw;
        private final TextInputWidget spawnPitch;
        private final DropdownField netherWorld;
        private final DropdownField endWorld;
        private final DropdownField overworld;
        private final TextInputWidget netherScale;
        private final TextInputWidget endScale;
        private final ToggleWidget autoNether;
        private final ToggleWidget autoEnd;
        private final ToggleWidget isolated;
        private final ToggleWidget timeLock;
        private final TextInputWidget lockedTime;
        private final ToggleWidget weatherLock;
        private final ToggleWidget storm;
        private final ToggleWidget thundering;
        private final IconButton statusMetric;
        private final IconButton playersMetric;
        private final IconButton environmentMetric;
        private final IconButton generatorMetric;

        private WorldDetailForm(String worldName, TextInputWidget alias, DropdownField difficulty, ToggleWidget hidden,
                                ToggleWidget forceGameMode, DropdownField gameMode, ToggleWidget pvp, ToggleWidget autoSave,
                                ToggleWidget keepSpawn, ToggleWidget animals, ToggleWidget monsters, ToggleWidget hunger,
                                ToggleWidget autoHeal, ToggleWidget bedRespawn, ToggleWidget anchorRespawn, ToggleWidget miscSpawns,
                                TextInputWidget accessPermission, TextInputWidget bypassPermission, TextInputWidget arrivalMessage,
                                TextInputWidget denyMessage, DropdownField respawnWorld, DropdownField inventoryGroup,
                                ToggleWidget customSpawn, TextInputWidget spawnX, TextInputWidget spawnY, TextInputWidget spawnZ,
                                TextInputWidget spawnYaw, TextInputWidget spawnPitch, DropdownField netherWorld,
                                DropdownField endWorld, DropdownField overworld, TextInputWidget netherScale,
                                TextInputWidget endScale, ToggleWidget autoNether, ToggleWidget autoEnd, ToggleWidget isolated,
                                ToggleWidget timeLock, TextInputWidget lockedTime, ToggleWidget weatherLock, ToggleWidget storm,
                                ToggleWidget thundering, IconButton statusMetric, IconButton playersMetric,
                                IconButton environmentMetric, IconButton generatorMetric) {
            this.worldName = safeText(worldName);
            this.alias = alias;
            this.difficulty = difficulty;
            this.hidden = hidden;
            this.forceGameMode = forceGameMode;
            this.gameMode = gameMode;
            this.pvp = pvp;
            this.autoSave = autoSave;
            this.keepSpawn = keepSpawn;
            this.animals = animals;
            this.monsters = monsters;
            this.hunger = hunger;
            this.autoHeal = autoHeal;
            this.bedRespawn = bedRespawn;
            this.anchorRespawn = anchorRespawn;
            this.miscSpawns = miscSpawns;
            this.accessPermission = accessPermission;
            this.bypassPermission = bypassPermission;
            this.arrivalMessage = arrivalMessage;
            this.denyMessage = denyMessage;
            this.respawnWorld = respawnWorld;
            this.inventoryGroup = inventoryGroup;
            this.customSpawn = customSpawn;
            this.spawnX = spawnX;
            this.spawnY = spawnY;
            this.spawnZ = spawnZ;
            this.spawnYaw = spawnYaw;
            this.spawnPitch = spawnPitch;
            this.netherWorld = netherWorld;
            this.endWorld = endWorld;
            this.overworld = overworld;
            this.netherScale = netherScale;
            this.endScale = endScale;
            this.autoNether = autoNether;
            this.autoEnd = autoEnd;
            this.isolated = isolated;
            this.timeLock = timeLock;
            this.lockedTime = lockedTime;
            this.weatherLock = weatherLock;
            this.storm = storm;
            this.thundering = thundering;
            this.statusMetric = statusMetric;
            this.playersMetric = playersMetric;
            this.environmentMetric = environmentMetric;
            this.generatorMetric = generatorMetric;
        }

        private void update(WorldRegistryEntry world, boolean replaceDraft) {
            WorldProfileSettings profile = world.getProfileSettings();
            if (!replaceDraft) {
                difficulty.refreshOptions(DIFFICULTY_OPTIONS, difficulty.value());
                gameMode.refreshOptions(GAME_MODE_OPTIONS, gameMode.value());
                respawnWorld.refreshOptions(worldNameOptions(), respawnWorld.value());
                inventoryGroup.refreshOptions(inventoryGroupOptions(), inventoryGroup.value());
                netherWorld.refreshOptions(linkedWorldOptions(), netherWorld.value());
                endWorld.refreshOptions(linkedWorldOptions(), endWorld.value());
                overworld.refreshOptions(linkedWorldOptions(), overworld.value());
                updateMetrics(world);
                return;
            }
            updateInput(alias, profile.getAlias());
            difficulty.refreshOptions(DIFFICULTY_OPTIONS, safeText(world.getDifficulty()).isBlank() ? "NORMAL" : safeText(world.getDifficulty()).toUpperCase(Locale.ROOT));
            hidden.setValue(profile.isHidden());
            forceGameMode.setValue(profile.isForceGameMode());
            gameMode.refreshOptions(GAME_MODE_OPTIONS, safeText(profile.getGameMode()).isBlank() ? "SURVIVAL" : safeText(profile.getGameMode()).toUpperCase(Locale.ROOT));
            pvp.setValue(profile.isPvpEnabled());
            autoSave.setValue(profile.isAutoSaveEnabled());
            keepSpawn.setValue(profile.isKeepSpawnLoaded());
            animals.setValue(profile.isAnimalSpawnsEnabled());
            monsters.setValue(profile.isMonsterSpawnsEnabled());
            hunger.setValue(profile.isHungerEnabled());
            autoHeal.setValue(profile.isAutoHealEnabled());
            bedRespawn.setValue(profile.isBedRespawnEnabled());
            anchorRespawn.setValue(profile.isAnchorRespawnEnabled());
            miscSpawns.setValue(profile.isNonLivingEntitySpawnsEnabled());
            updateInput(accessPermission, profile.getAccessPermission());
            updateInput(bypassPermission, profile.getBypassPermission());
            updateInput(arrivalMessage, profile.getArrivalMessage());
            updateInput(denyMessage, profile.getDenyMessage());
            respawnWorld.refreshOptions(worldNameOptions(), profile.getRespawnWorld());
            inventoryGroup.refreshOptions(inventoryGroupOptions(), inventoryGroupDropdownValue(profile.getInventoryGroupId()));
            customSpawn.setValue(profile.isCustomSpawnEnabled());
            updateInput(spawnX, formatDecimal(profile.getSpawnX()));
            updateInput(spawnY, formatDecimal(profile.getSpawnY()));
            updateInput(spawnZ, formatDecimal(profile.getSpawnZ()));
            updateInput(spawnYaw, formatDecimal(profile.getSpawnYaw()));
            updateInput(spawnPitch, formatDecimal(profile.getSpawnPitch()));
            netherWorld.refreshOptions(linkedWorldOptions(), profile.getLinkedNetherWorld());
            endWorld.refreshOptions(linkedWorldOptions(), profile.getLinkedEndWorld());
            overworld.refreshOptions(linkedWorldOptions(), profile.getLinkedOverworld());
            updateInput(netherScale, formatDecimal(profile.getNetherScale()));
            updateInput(endScale, formatDecimal(profile.getEndScale()));
            autoNether.setValue(profile.isAutoLinkNetherPortal());
            autoEnd.setValue(profile.isAutoLinkEndPortal());
            isolated.setValue(world.isIsolatedPlayerState());
            timeLock.setValue(world.isTimeLockEnabled());
            updateInput(lockedTime, String.valueOf(world.getLockedTime()));
            weatherLock.setValue(world.isWeatherLockEnabled());
            storm.setValue(world.isLockedStorm());
            thundering.setValue(world.isLockedThundering());
            updateMetrics(world);
        }

        private void updateMetrics(WorldRegistryEntry world) {
            WorldDashboardEntry dashboard = findWorldDashboard(world.getWorldName());
            String status = dashboard != null ? safeText(dashboard.getStatus()) : world.isLoaded() ? "Loaded" : "Unloaded";
            int players = dashboard != null ? dashboard.getPlayerCount() : 0;
            statusMetric.setMessage(status);
            statusMetric.setIcon(world.isLoaded() ? "play.png" : "hide.png");
            playersMetric.setMessage(players + " Players");
            environmentMetric.setMessage(safeText(world.getEnvironment()).isBlank() ? "Unknown" : world.getEnvironment());
            generatorMetric.setMessage(safeText(world.getGenerator()).isBlank() ? "Default" : world.getGenerator());
        }

        private void updateInput(TextInputWidget input, String value) {
            if (input != null && !input.isFocused() && !Objects.equals(input.getText(), safeText(value))) {
                input.setText(safeText(value));
            }
        }

    }

    private AnimatedWidget metricRow(int rowWidth, IconButton statusButton, IconButton playersButton, IconButton environmentButton, IconButton generatorButton) {
        return new RowWidget.Builder().size(rowWidth, 20).addWidget(statusButton, playersButton, environmentButton, generatorButton).build();
    }

    private IconButton metricButton(String label, String icon, int width) {
        IconButton button = new IconButton.Builder()
            .label(label)
            .imagePath(icon)
            .size(width, 18)
            .entranceAnimation(false)
            .build();
        button.active = false;
        return button;
    }

    private TitledRowWidget row(String title, int rowWidth, AnimatedWidget... widgets) {
        TitledRowWidget.Builder builder = new TitledRowWidget.Builder()
            .title(title)
            .description(worldPanelDescription(title))
            .size(rowWidth, 30)
            .gap(4);
        for (AnimatedWidget widget : widgets) {
            builder.addWidget(widget);
        }
        return builder.build();
    }

    private String worldPanelDescription(String title) {
        return switch (title) {
            case "Identity" -> "World identity fields.\nIncludes display alias, environment, seed, generator, and difficulty.";
            case "Access" -> "Join and play-state controls.\nCovers visibility, forced game mode, and access behavior.";
            case "Permissions" -> "Permission nodes for entering, bypassing, or managing this world.\nLeave empty when no permission check applies.";
            case "Messages" -> "Player-facing messages.\nShown on join, denial, fallback, or world-specific transitions.";
            case "Rules" -> "Core gameplay toggles.\nControls PvP, saving, spawn loading, and entity spawning.";
            case "Player State" -> "World-specific player state rules.\nControls hunger, healing, respawn behavior, and related state resets.";
            case "Travel" -> "World routing settings.\nControls fallback world and inventory-group selection during transfers.";
            case "Spawn" -> "Custom spawn override.\nIncludes world, X, Y, Z, yaw, and pitch.";
            case "Links" -> "Dimension-style links.\nConnects overworld, nether, and end destinations for travel logic.";
            case "Portal Scale" -> "Portal coordinate scaling.\nUsed when linked dimensions convert travel positions.";
            case "Runtime" -> "Live world locks and isolation.\nControls time, weather, storms, thunder, and runtime isolation.";
            case "Groups" -> "Inventory group editor.\nGroups define which player state is shared across selected worlds.";
            case "Saved Groups" -> "Saved inventory groups for this server.\nSelect one to edit its worlds and preserved state.";
            case "Inventory" -> "Inventory state preserved by this group.\nIncludes main inventory, armor, offhand, and ender chest.";
            case "Location" -> "Location state preserved by this group.\nIncludes effects, last location, and bed spawn.";
            case "Actions" -> "Editor actions for the current world or group.\nSave applies changes; cancel discards the draft.";
            case "Worlds" -> "Worlds included by this selector or inventory group.\nEmpty selections depend on the surrounding field behavior.";
            default -> "";
        };
    }

    private void addDetailWidget(AnimatedWidget widget) {
        detailPane.addWidget(widget);
        detailWidgets.add(widget);
    }

    private TextInputWidget detailInput(String placeholder, String value, int width) {
        TextInputWidget input = new TextInputWidget.Builder()
            .placeholder(placeholder)
            .forcePlaceholder(false)
            .text(safeText(value))
            .size(Math.max(60, width), 20)
            .build();
        input.entranceAnimationEnabled = false;
        return input;
    }

    private DropdownField dropdownField(List<String> options, String selected, int width) {
        return dropdownField(options, selected, width, this::worldOptionLabel);
    }

    private DropdownField dropdownField(List<String> options, String selected, int width, Function<String, String> labeler) {
        return new DropdownField(options, selected, width, labeler);
    }

    private final class DropdownField {
        private final DropDownWidget<String> dropdown;
        private final Function<String, String> labeler;
        private List<String> options;

        private DropdownField(List<String> options, String selected, int width, Function<String, String> labeler) {
            this.labeler = labeler == null ? WorldDesignerScreen.this::worldOptionLabel : labeler;
            this.options = normalizeOptions(options);
            String resolved = resolveOptionValue(this.options, selected);
            dropdown = new DropDownWidget.Builder<>(new ArrayList<>(this.options))
                .displayFunction(value -> this.labeler.apply(safeText(value)))
                .selectedItem(resolved)
                .size(Math.max(80, width), 20)
                .maxVisibleItems(10)
                .entranceAnimation(false)
                .build();
        }

        private DropDownWidget<String> widget() {
            return dropdown;
        }

        private String value() {
            String selected = dropdown.getSelectedItem();
            return selected == null ? "" : selected;
        }

        private void refreshOptions(List<String> nextOptions, String preferredValue) {
            String keep = dropdown.isFocused() || dropdown.isHovered() ? value() : preferredValue;
            options = normalizeOptions(nextOptions);
            dropdown.setItems(new ArrayList<>(options), resolveOptionValue(options, keep));
        }

        private List<String> normalizeOptions(List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of("");
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null) {
                    normalized.add(value);
                }
            }
            return new ArrayList<>(normalized);
        }
    }

    private List<String> linkedWorldOptions() {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(worldNameOptions());
        return options;
    }

    private String inventoryGroupDropdownValue(String groupId) {
        String text = safeText(groupId);
        return text.isBlank() ? "No Group" : text;
    }

    private String inventoryGroupLabel(String value) {
        return inventoryGroupDropdownValue(value);
    }

    private String inventoryGroupStoredValue(String value) {
        return "No Group".equals(value) ? "" : safeText(value);
    }

    private String resolveOptionValue(List<String> options, String value) {
        if (options == null || options.isEmpty()) {
            return safeText(value);
        }
        String text = safeText(value);
        for (String option : options) {
            if (safeText(option).equalsIgnoreCase(text)) {
                return option;
            }
        }
        return options.getFirst();
    }

    private String worldOptionLabel(String value) {
        if (value == null || value.isBlank()) {
            return "None";
        }
        String cleaned = value.trim().replace('_', ' ').replace('-', ' ');
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

    private ToggleWidget detailToggle(String label, boolean value, int width) {
        return new ToggleWidget.Builder()
            .label(label)
            .toggled(value)
            .size(width, 20)
            .entranceAnimation(false)
            .build();
    }

    private void saveWorld(WorldRegistryEntry world, WorldDetailForm form) {
        FlowManager manager = worldManager();
        if (manager == null || world == null || form == null) {
            return;
        }
        TextInputWidget alias = form.alias;
        DropdownField difficulty = form.difficulty;
        ToggleWidget hidden = form.hidden;
        ToggleWidget forceGameMode = form.forceGameMode;
        DropdownField gameMode = form.gameMode;
        ToggleWidget pvp = form.pvp;
        ToggleWidget autoSave = form.autoSave;
        ToggleWidget keepSpawn = form.keepSpawn;
        ToggleWidget animals = form.animals;
        ToggleWidget monsters = form.monsters;
        ToggleWidget hunger = form.hunger;
        ToggleWidget autoHeal = form.autoHeal;
        ToggleWidget bedRespawn = form.bedRespawn;
        ToggleWidget anchorRespawn = form.anchorRespawn;
        ToggleWidget miscSpawns = form.miscSpawns;
        TextInputWidget accessPermission = form.accessPermission;
        TextInputWidget bypassPermission = form.bypassPermission;
        TextInputWidget arrivalMessage = form.arrivalMessage;
        TextInputWidget denyMessage = form.denyMessage;
        DropdownField respawnWorld = form.respawnWorld;
        DropdownField inventoryGroup = form.inventoryGroup;
        ToggleWidget customSpawn = form.customSpawn;
        TextInputWidget spawnX = form.spawnX;
        TextInputWidget spawnY = form.spawnY;
        TextInputWidget spawnZ = form.spawnZ;
        TextInputWidget spawnYaw = form.spawnYaw;
        TextInputWidget spawnPitch = form.spawnPitch;
        DropdownField netherWorld = form.netherWorld;
        DropdownField endWorld = form.endWorld;
        DropdownField overworld = form.overworld;
        TextInputWidget netherScale = form.netherScale;
        TextInputWidget endScale = form.endScale;
        ToggleWidget autoNether = form.autoNether;
        ToggleWidget autoEnd = form.autoEnd;
        ToggleWidget isolated = form.isolated;
        ToggleWidget timeLock = form.timeLock;
        TextInputWidget lockedTime = form.lockedTime;
        ToggleWidget weatherLock = form.weatherLock;
        ToggleWidget storm = form.storm;
        ToggleWidget thundering = form.thundering;
        Double parsedSpawnX = parseNullableDouble(spawnX.getText());
        Double parsedSpawnY = parseNullableDouble(spawnY.getText());
        Double parsedSpawnZ = parseNullableDouble(spawnZ.getText());
        Float parsedSpawnYaw = parseNullableFloat(spawnYaw.getText());
        Float parsedSpawnPitch = parseNullableFloat(spawnPitch.getText());
        Double parsedNetherScale = parseNullableDouble(netherScale.getText());
        Double parsedEndScale = parseNullableDouble(endScale.getText());
        Long parsedLockedTime = parseNullableLong(lockedTime.getText());
        if (parsedSpawnX == null || parsedSpawnY == null || parsedSpawnZ == null || parsedSpawnYaw == null || parsedSpawnPitch == null) {
            new Notification("World", "Invalid Spawn", Notification.Type.ERROR);
            return;
        }
        if (parsedNetherScale == null || parsedNetherScale <= 0.0 || parsedEndScale == null || parsedEndScale <= 0.0) {
            new Notification("World", "Invalid Portal Scale", Notification.Type.ERROR);
            return;
        }
        if (parsedLockedTime == null || parsedLockedTime < 0L) {
            new Notification("World", "Invalid Time", Notification.Type.ERROR);
            return;
        }
        WorldProfileSettings profile = new WorldProfileSettings();
        preserveUneditedProfileSettings(world.getProfileSettings(), profile);
        profile.setAlias(alias.getText());
        profile.setHidden(hidden.getValue());
        profile.setAccessPermission(accessPermission.getText());
        profile.setBypassPermission(bypassPermission.getText());
        profile.setRespawnWorld(safeText(respawnWorld.value()));
        profile.setForceGameMode(forceGameMode.getValue());
        profile.setGameMode(safeText(gameMode.value()));
        profile.setCustomSpawnEnabled(customSpawn.getValue());
        profile.setSpawnX(parsedSpawnX);
        profile.setSpawnY(parsedSpawnY);
        profile.setSpawnZ(parsedSpawnZ);
        profile.setSpawnYaw(parsedSpawnYaw);
        profile.setSpawnPitch(parsedSpawnPitch);
        profile.setPvpEnabled(pvp.getValue());
        profile.setKeepSpawnLoaded(keepSpawn.getValue());
        profile.setAutoSaveEnabled(autoSave.getValue());
        profile.setAnimalSpawnsEnabled(animals.getValue());
        profile.setMonsterSpawnsEnabled(monsters.getValue());
        profile.setHungerEnabled(hunger.getValue());
        profile.setAutoHealEnabled(autoHeal.getValue());
        profile.setBedRespawnEnabled(bedRespawn.getValue());
        profile.setAnchorRespawnEnabled(anchorRespawn.getValue());
        profile.setNonLivingEntitySpawnsEnabled(miscSpawns.getValue());
        profile.setArrivalMessage(arrivalMessage.getText());
        profile.setDenyMessage(denyMessage.getText());
        profile.setInventoryGroupId(inventoryGroupStoredValue(inventoryGroup.value()));
        profile.setLinkedNetherWorld(safeText(netherWorld.value()));
        profile.setLinkedEndWorld(safeText(endWorld.value()));
        profile.setLinkedOverworld(safeText(overworld.value()));
        profile.setNetherScale(parsedNetherScale);
        profile.setEndScale(parsedEndScale);
        profile.setAutoLinkNetherPortal(autoNether.getValue());
        profile.setAutoLinkEndPortal(autoEnd.getValue());
        String worldName = world.getWorldName();
        boolean difficultyChanged = !safeText(difficulty.value()).equalsIgnoreCase(safeText(world.getDifficulty()));
        boolean isolatedChanged = isolated.getValue() != world.isIsolatedPlayerState();
        boolean timeLockChanged = timeLock.getValue() != world.isTimeLockEnabled() || parsedLockedTime != world.getLockedTime();
        boolean weatherLockChanged = weatherLock.getValue() != world.isWeatherLockEnabled() || storm.getValue() != world.isLockedStorm() || thundering.getValue() != world.isLockedThundering();
        int operationCount = 1 + (difficultyChanged ? 1 : 0) + (isolatedChanged ? 1 : 0) + (timeLockChanged ? 1 : 0) + (weatherLockChanged ? 1 : 0);
        long saveSequence = manager.beginWorldSaveNotification(serverId, worldName, operationCount);
        markChangesSaving(saveSequence);
        if (difficultyChanged) {
            manager.suppressNextWorldSuccessNotification(serverId, "setDifficulty");
            manager.setWorldDifficulty(serverId, worldName, safeText(difficulty.value()));
        }
        manager.suppressNextWorldSuccessNotification(serverId, "setWorldProfile");
        manager.setWorldProfile(serverId, worldName, profile);
        if (isolatedChanged) {
            manager.suppressNextWorldSuccessNotification(serverId, "setIsolatedPlayerState");
            manager.setWorldIsolatedState(serverId, worldName, isolated.getValue());
        }
        if (timeLockChanged) {
            manager.suppressNextWorldSuccessNotification(serverId, "setTimeLock");
            manager.setWorldTimeLock(serverId, worldName, timeLock.getValue(), parsedLockedTime);
        }
        if (weatherLockChanged) {
            manager.suppressNextWorldSuccessNotification(serverId, "setWeatherLock");
            manager.setWorldWeatherLock(serverId, worldName, weatherLock.getValue(), storm.getValue(), thundering.getValue());
        }
    }

    private IconButton emptyDetailButton() {
        IconButton button = new IconButton.Builder()
            .label("World Unavailable")
            .imagePath("earth.png")
            .size(Math.max(160, detailPane.getWidth() - 18), 22)
            .entranceAnimation(false)
            .build();
        button.active = false;
        return button;
    }

    private void showCloneWorldPopup(String sourceWorld) {
        PopupWidget.Builder builder = new PopupWidget.Builder("Clone World")
            .setResizable(false)
            .setAntiOutOfBound(true)
            .setBoundOffset(desktopMode ? 35 : 0)
            .width(400);
        TextInputWidget worldInput = new TextInputWidget.Builder().placeholder("Target World").size(220, 18).build();
        ToggleWidget loadAfter = detailToggle("Load After", true, 100);
        builder.addRow("Target", worldInput);
        builder.addRow("", loadAfter);
        PopupWidget[] popupRef = new PopupWidget[1];
        IconButton cloneButton = new IconButton.Builder()
            .label("Clone")
            .imagePath("copy.png")
            .accentType(ThemeManager.getAccent("nice"))
            .size(110, 20)
            .onClick(() -> {
                String targetWorld = safeText(worldInput.getText()).trim();
                if (!WorldUiSupport.isValidSimpleId(targetWorld)) {
                    new Notification("World", "Invalid World Name", Notification.Type.ERROR);
                    return;
                }
                if (WorldUiSupport.containsIgnoreCase(worldNameOptions(), targetWorld)) {
                    new Notification("World", "World Exists", Notification.Type.ERROR);
                    return;
                }
                worldManager().cloneWorld(serverId, sourceWorld, targetWorld, loadAfter.getValue());
                if (host != null) {
                    host.openWorkspaceResource(ReSyncResourceDragPayload.WORLD, targetWorld);
                }
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        builder.addTitleAction("Clone", () -> cloneButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private void showWorldUnloadPopup(String worldName) {
        showFallbackPopup(worldName, fallbackWorld -> worldManager().unloadWorld(serverId, worldName, fallbackWorld));
    }

    private void showFallbackPopup(String worldName, Consumer<String> action) {
        List<String> fallbackOptions = fallbackWorldOptions(worldName);
        PopupWidget.Builder builder = fallbackBuilder("Unload World", worldName, 130);
        TextInputWidget fallbackInput = new TextInputWidget.Builder()
            .text(selectDefaultFallbackWorld(worldName, fallbackOptions))
            .placeholder("Fallback World")
            .size(220, 18)
            .build();
        builder.addRow("Fallback", fallbackInput);
        PopupWidget[] popupRef = new PopupWidget[1];
        IconButton actionButton = new IconButton.Builder()
            .label("Unload")
            .imagePath("hide.png")
            .accentType(ThemeManager.getAccent("danger"))
            .size(110, 20)
            .onClick(() -> {
                String fallbackWorld = safeText(fallbackInput.getText()).trim();
                if (!WorldUiSupport.containsIgnoreCase(fallbackOptions, fallbackWorld)) {
                    new Notification("World", "Unknown Fallback World", Notification.Type.ERROR);
                    return;
                }
                action.accept(fallbackWorld);
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        builder.addTitleAction("Unload", () -> actionButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.DESTRUCTIVE);
        popupRef[0] = builder.build();
        addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private PopupWidget.Builder fallbackBuilder(String title, String worldName, int height) {
        return new PopupWidget.Builder(title + " | " + worldName)
            .setResizable(false)
            .setAntiOutOfBound(true)
            .setBoundOffset(desktopMode ? 35 : 0)
            .width(430);
    }

    private void showWorldPurgePopup(String worldName) {
        PopupWidget.Builder builder = new PopupWidget.Builder("Purge Entities")
            .setResizable(false)
            .setAntiOutOfBound(true)
            .setBoundOffset(desktopMode ? 35 : 0)
            .width(420);
        ToggleWidget monsters = detailToggle("Monsters", true, 96);
        ToggleWidget animals = detailToggle("Animals", false, 90);
        ToggleWidget ambient = detailToggle("Ambient", false, 90);
        ToggleWidget misc = detailToggle("Misc", false, 80);
        ToggleWidget vehicles = detailToggle("Vehicles", false, 92);
        ToggleWidget items = detailToggle("Items", false, 80);
        builder.addRow("Types", monsters, animals, ambient);
        builder.addRow("More", misc, vehicles, items);
        PopupWidget[] popupRef = new PopupWidget[1];
        IconButton purgeButton = new IconButton.Builder()
            .label("Purge")
            .imagePath("delete.png")
            .accentType(ThemeManager.getAccent("danger"))
            .size(110, 20)
            .onClick(() -> {
                if (!monsters.getValue() && !animals.getValue() && !ambient.getValue() && !misc.getValue() && !vehicles.getValue() && !items.getValue()) {
                    new Notification("World", "Select Purge Types", Notification.Type.ERROR);
                    return;
                }
                worldManager().purgeWorld(serverId, worldName, monsters.getValue(), animals.getValue(), ambient.getValue(), misc.getValue(), vehicles.getValue(), items.getValue());
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        builder.addTitleAction("Purge", () -> purgeButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.DESTRUCTIVE);
        popupRef[0] = builder.build();
        addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private void showWorldTeleportPopup(String worldName) {
        PopupWidget.Builder builder = new PopupWidget.Builder("Teleport Player")
            .onClose(this::closePlayerSelector)
            .setResizable(false)
            .setAntiOutOfBound(true)
            .setBoundOffset(desktopMode ? 35 : 0)
            .width(440);
        String[] selectedPlayer = {""};
        AnimatedButton playerButton = new AnimatedButton.Builder()
            .label("Select Player")
            .size(220, 18)
            .entranceAnimation(false)
            .build();
        playerButton.setAction(() -> showPlayerSelector(playerButton, selectedPlayer[0], player -> {
            selectedPlayer[0] = player;
            playerButton.setMessage(player.isBlank() ? "Select Player" : player);
        }));
        TextInputWidget xInput = new TextInputWidget.Builder().placeholder("X").size(82, 18).build();
        TextInputWidget yInput = new TextInputWidget.Builder().placeholder("Y").size(82, 18).build();
        TextInputWidget zInput = new TextInputWidget.Builder().placeholder("Z").size(82, 18).build();
        TextInputWidget yawInput = new TextInputWidget.Builder().placeholder("Yaw").size(82, 18).build();
        TextInputWidget pitchInput = new TextInputWidget.Builder().placeholder("Pitch").size(82, 18).build();
        builder.addRow("Player", playerButton);
        builder.addRow("Position", xInput, yInput, zInput);
        builder.addRow("Rotation", yawInput, pitchInput);
        PopupWidget[] popupRef = new PopupWidget[1];
        IconButton spawnButton = new IconButton.Builder()
            .label("Spawn")
            .imagePath("earth.png")
            .size(105, 20)
            .onClick(() -> {
                String playerName = safeText(selectedPlayer[0]).trim();
                if (playerName.isBlank()) {
                    new Notification("World", "Player Required", Notification.Type.ERROR);
                    return;
                }
                worldManager().teleportPlayerToWorldSpawn(serverId, playerName, worldName);
                closePlayerSelector();
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        IconButton teleportButton = new IconButton.Builder()
            .label("Teleport")
            .imagePath("steve.png")
            .accentType(ThemeManager.getAccent("nice"))
            .size(120, 20)
            .onClick(() -> {
                String playerName = safeText(selectedPlayer[0]).trim();
                if (playerName.isBlank()) {
                    new Notification("World", "Player Required", Notification.Type.ERROR);
                    return;
                }
                if (!WorldUiSupport.isCompleteOrBlank(xInput.getText(), yInput.getText(), zInput.getText())
                    || !WorldUiSupport.isCompleteOrBlank(yawInput.getText(), pitchInput.getText())) {
                    new Notification("World", "Complete Coordinates", Notification.Type.ERROR);
                    return;
                }
                worldManager().teleportPlayerToWorld(serverId, playerName, worldName, parseNullableDouble(xInput.getText()),
                    parseNullableDouble(yInput.getText()), parseNullableDouble(zInput.getText()), parseNullableFloat(yawInput.getText()),
                    parseNullableFloat(pitchInput.getText()));
                closePlayerSelector();
                if (popupRef[0] != null) {
                    popupRef[0].hide();
                }
            })
            .build();
        builder.addTitleAction("Spawn", () -> spawnButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.SECONDARY);
        builder.addTitleAction("Teleport", () -> teleportButton.onClick(0, 0, 0), PopupWidget.TitleActionRole.PRIMARY);
        popupRef[0] = builder.build();
        addDrawableChild(popupRef[0]);
        popupRef[0].show();
    }

    private void showPlayerSelector(AnimatedWidget anchor, String selected, Consumer<String> onSelected) {
        FlowManager manager = worldManager();
        if (manager == null || anchor == null || onSelected == null) {
            return;
        }
        closePlayerSelector();
        manager.requestPlayerTrackingSnapshot(serverId);
        List<PlayerDossier> players = manager.getOnlinePlayersForServer(serverId);
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        ItemSelectorWidget[] selectorRef = new ItemSelectorWidget[1];
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(overlay)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Players")
            .onClose(() -> closePlayerSelector(selectorRef[0]))
            .build();
        selector.setLayer(900);
        selector.setPriority(30);
        selectorRef[0] = selector;
        activePlayerSelector = selector;
        for (PlayerDossier player : players) {
            if (player == null || safeText(player.getPlayerName()).isBlank()) {
                continue;
            }
            String name = player.getPlayerName();
            String uuid = safeText(player.getPlayerId());
            selector.addItem(name, uuid, name + " " + uuid, () -> onSelected.accept(name));
        }
        selector.setSelectedItem(selected);
        overlay.addDrawableChild(selector);
        selector.show(anchor.getX(), anchor.getY() + anchor.getHeight());
    }

    private void closePlayerSelector() {
        closePlayerSelector(activePlayerSelector);
    }

    private void closePlayerSelector(ItemSelectorWidget selector) {
        if (selector == null) {
            return;
        }
        selector.onClose = null;
        selector.hide();
        ScreenManager.getInstance().getPopupOverlay().remove(selector);
        if (selector == activePlayerSelector) {
            activePlayerSelector = null;
        }
    }

    private void showInventoryGroupsPopup() {
        FlowManager manager = worldManager();
        if (manager == null) {
            return;
        }
        clearDetailWidgets();
        detailForm = null;
        List<WorldInventoryGroup> groups = new ArrayList<>(manager.getWorldInventoryGroupsForServer(serverId));
        groups.sort(Comparator.comparing(group -> safeText(group == null ? "" : group.getGroupId()), String.CASE_INSENSITIVE_ORDER));
        int rowWidth = Math.max(220, detailPane.getWidth() - 18);
        IconButton createButton = new IconButton.Builder()
            .label("Create")
            .imagePath("create.png")
            .accentType(ThemeManager.getAccent("nice"))
            .size(96, 18)
            .entranceAnimation(false)
            .onClick(() -> showInventoryGroupPopup(null))
            .build();
        IconButton closeButton = new IconButton.Builder()
            .label("Close")
            .imagePath("close.png")
            .size(86, 18)
            .entranceAnimation(false)
            .onClick(this::refreshDetails)
            .build();
        addDetailWidget(row("Groups", rowWidth, createButton, closeButton));
        if (groups.isEmpty()) {
            addDetailWidget(row("Saved Groups", rowWidth, readOnlyButton("No Groups")));
        } else {
            for (WorldInventoryGroup group : groups) {
                if (group == null || safeText(group.getGroupId()).isBlank()) {
                    continue;
                }
                IconButton label = readOnlyButton(inventoryGroupSummary(group));
                SquareButtonWidget edit = new SquareButtonWidget.Builder()
                    .imagePath("edit.png")
                    .hint("Edit Group")
                    .onClick(() -> showInventoryGroupPopup(group))
                    .build();
                SquareButtonWidget delete = new SquareButtonWidget.Builder()
                    .imagePath("delete.png")
                    .hint("Delete Group")
                    .accentType(ThemeManager.getAccent("danger"))
                    .onClick(() -> {
                        manager.deleteInventoryGroup(serverId, group.getGroupId());
                        showInventoryGroupsPopup();
                    })
                    .build();
                addDetailWidget(row(group.getGroupId(), rowWidth, label, edit, delete));
            }
        }
        detailPane.updateWidgetPositions();
    }

    private String inventoryGroupSummary(WorldInventoryGroup group) {
        String display = safeText(group.getDisplayName()).isBlank() ? group.getGroupId() : group.getDisplayName();
        List<String> worlds = group.getWorlds() == null ? List.of() : group.getWorlds();
        return display + " | " + worlds.size() + " Worlds | " + summarizeInventoryGroupShares(group);
    }

    private String summarizeInventoryGroupShares(WorldInventoryGroup group) {
        List<String> parts = new ArrayList<>();
        if (group.isShareInventory()) parts.add("Inventory");
        if (group.isShareArmor()) parts.add("Armor");
        if (group.isShareOffhand()) parts.add("Offhand");
        if (group.isShareEnderChest()) parts.add("Ender Chest");
        if (group.isShareHealth()) parts.add("Health");
        if (group.isShareHunger()) parts.add("Hunger");
        if (group.isShareExperience()) parts.add("Experience");
        if (group.isShareGameMode()) parts.add("Game Mode");
        if (group.isSharePotionEffects()) parts.add("Potions");
        if (group.isShareLastLocation()) parts.add("Last Location");
        if (group.isShareBedSpawn()) parts.add("Bed Spawn");
        return parts.isEmpty() ? "No Shared State" : String.join(", ", parts);
    }

    private void showInventoryGroupPopup(WorldInventoryGroup existingGroup) {
        FlowManager manager = worldManager();
        if (manager == null) {
            return;
        }
        boolean editing = existingGroup != null;
        clearDetailWidgets();
        detailForm = null;
        int rowWidth = Math.max(220, detailPane.getWidth() - 18);
        TextInputWidget displayName = new TextInputWidget.Builder()
            .text(editing ? safeText(existingGroup.getDisplayName()) : "")
            .placeholder("Group Name")
            .forcePlaceholder(false)
            .size(Math.max(220, rowWidth - 12), 20)
            .build();
        displayName.entranceAnimationEnabled = false;
        Set<String> selectedWorlds = new LinkedHashSet<>(editing ? existingGroup.getWorlds() : defaultGroupWorldSelection());
        ToggleWidget inventory = detailToggle("Inventory", !editing || existingGroup.isShareInventory(), 92);
        ToggleWidget armor = detailToggle("Armor", !editing || existingGroup.isShareArmor(), 80);
        ToggleWidget offhand = detailToggle("Offhand", !editing || existingGroup.isShareOffhand(), 88);
        ToggleWidget enderChest = detailToggle("Ender Chest", !editing || existingGroup.isShareEnderChest(), 110);
        ToggleWidget health = detailToggle("Health", !editing || existingGroup.isShareHealth(), 82);
        ToggleWidget hunger = detailToggle("Hunger", !editing || existingGroup.isShareHunger(), 86);
        ToggleWidget experience = detailToggle("Experience", !editing || existingGroup.isShareExperience(), 105);
        ToggleWidget gameMode = detailToggle("Game Mode", !editing || existingGroup.isShareGameMode(), 105);
        ToggleWidget potions = detailToggle("Potions", !editing || existingGroup.isSharePotionEffects(), 90);
        ToggleWidget lastLocation = detailToggle("Last Location", !editing || existingGroup.isShareLastLocation(), 115);
        ToggleWidget bedSpawn = detailToggle("Bed Spawn", !editing || existingGroup.isShareBedSpawn(), 100);
        addDetailWidget(row(editing ? "Edit Group" : "Create Group", rowWidth, displayName));
        addInventoryGroupWorldRow(rowWidth, selectedWorlds);
        addDetailWidget(row("Inventory", rowWidth, inventory, armor, offhand, enderChest));
        addDetailWidget(row("Player State", rowWidth, health, hunger, experience, gameMode));
        addDetailWidget(row("Location", rowWidth, potions, lastLocation, bedSpawn));
        IconButton save = new IconButton.Builder()
            .label(editing ? "Save" : "Create")
            .imagePath("save.png")
            .accentType(ThemeManager.getAccent("nice"))
            .size(90, 18)
            .entranceAnimation(false)
            .onClick(() -> {
                String name = safeText(displayName.getText()).trim();
                String id = editing ? safeText(existingGroup.getGroupId()).trim() : inventoryGroupIdFromName(name);
                if (!editing && name.isBlank()) {
                    new Notification("World", "Group Name Required", Notification.Type.ERROR);
                    return;
                }
                if (!WorldUiSupport.isValidSimpleId(id)) {
                    new Notification("World", "Invalid Group Name", Notification.Type.ERROR);
                    return;
                }
                if (!editing && manager.getWorldInventoryGroup(serverId, id) != null) {
                    new Notification("World", "Group Exists", Notification.Type.ERROR);
                    return;
                }
                WorldInventoryGroup group = new WorldInventoryGroup();
                group.setGroupId(id);
                group.setDisplayName(name);
                group.setWorlds(new ArrayList<>(selectedWorlds));
                group.setShareInventory(inventory.getValue());
                group.setShareArmor(armor.getValue());
                group.setShareOffhand(offhand.getValue());
                group.setShareEnderChest(enderChest.getValue());
                group.setShareHealth(health.getValue());
                group.setShareHunger(hunger.getValue());
                group.setShareExperience(experience.getValue());
                group.setShareGameMode(gameMode.getValue());
                group.setSharePotionEffects(potions.getValue());
                group.setShareLastLocation(lastLocation.getValue());
                group.setShareBedSpawn(bedSpawn.getValue());
                String action = editing ? "updateInventoryGroup" : "createInventoryGroup";
                manager.beginWorldOperationNotification(serverId, name.isBlank() ? id : name, 1, editing ? "Saving Group" : "Creating Group", editing ? "Group Saved" : "Group Created", editing ? "Group Save Failed" : "Group Create Failed");
                manager.suppressNextWorldSuccessNotification(serverId, action);
                if (editing) {
                    manager.updateInventoryGroup(serverId, group);
                } else {
                    manager.createInventoryGroup(serverId, group);
                }
                showInventoryGroupsPopup();
            })
            .build();
        IconButton cancel = new IconButton.Builder()
            .label("Back")
            .imagePath("close.png")
            .size(84, 18)
            .entranceAnimation(false)
            .onClick(this::showInventoryGroupsPopup)
            .build();
        addDetailWidget(row("Actions", rowWidth, save, cancel));
        detailPane.updateWidgetPositions();
    }

    private void addInventoryGroupWorldRow(int rowWidth, Set<String> selectedWorlds) {
        List<String> worlds = worldNameOptions();
        if (worlds.isEmpty()) {
            addDetailWidget(row("Worlds", rowWidth, readOnlyButton("No Worlds")));
            return;
        }
        int buttonWidth = Math.max(110, (rowWidth - 24) / 3);
        List<ToggleWidget> rowToggles = new ArrayList<>();
        for (String world : worlds) {
            ToggleWidget toggle = new ToggleWidget.Builder()
                .label(world)
                .toggled(containsIgnoreCase(selectedWorlds, world))
                .size(buttonWidth, 20)
                .entranceAnimation(false)
                .onChange(value -> {
                    if (value) {
                        selectedWorlds.add(world);
                    } else {
                        selectedWorlds.removeIf(selected -> selected.equalsIgnoreCase(world));
                    }
                })
                .build();
            rowToggles.add(toggle);
            if (rowToggles.size() == 3) {
                addDetailWidget(row("Worlds", rowWidth, rowToggles.toArray(new AnimatedWidget[0])));
                rowToggles.clear();
            }
        }
        if (!rowToggles.isEmpty()) {
            addDetailWidget(row("Worlds", rowWidth, rowToggles.toArray(new AnimatedWidget[0])));
        }
    }

    private String inventoryGroupIdFromName(String name) {
        String id = safeText(name).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        return id.isBlank() ? "group" : id;
    }

    private List<String> defaultGroupWorldSelection() {
        return safeText(worldName).isBlank() ? List.of() : List.of(worldName);
    }

    @Override
    public void handleWorldOperationResult(WorldOperationResult result) {
        refreshDetails();
    }

    private boolean containsIgnoreCase(Set<String> values, String value) {
        if (values == null || value == null) {
            return false;
        }
        for (String entry : values) {
            if (entry != null && entry.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> onlinePlayerNames() {
        FlowManager manager = worldManager();
        return manager == null ? List.of() : WorldUiSupport.normalizeUniqueEntries(manager.getOnlinePlayerNamesForServer(serverId));
    }

    private List<String> worldNameOptions() {
        FlowManager manager = worldManager();
        if (manager == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(manager.getWorldsForServer(serverId).keySet());
        names = WorldUiSupport.normalizeUniqueEntries(names);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private List<String> inventoryGroupOptions() {
        FlowManager manager = worldManager();
        if (manager == null) {
            return List.of("No Group");
        }
        List<String> groups = new ArrayList<>();
        groups.add("No Group");
        for (WorldInventoryGroup group : manager.getWorldInventoryGroupsForServer(serverId)) {
            if (group != null && !safeText(group.getGroupId()).isBlank()) {
                groups.add(group.getGroupId());
            }
        }
        return WorldUiSupport.normalizeUniqueEntries(groups);
    }

    private List<String> fallbackWorldOptions(String worldName) {
        List<String> options = new ArrayList<>(worldNameOptions());
        options.removeIf(option -> option != null && option.equalsIgnoreCase(worldName));
        return options;
    }

    private String selectDefaultFallbackWorld(String worldName, List<String> fallbackOptions) {
        if (fallbackOptions == null || fallbackOptions.isEmpty()) {
            return "";
        }
        WorldRegistryEntry world = worldManager() != null ? worldManager().getWorld(serverId, worldName) : null;
        WorldProfileSettings profile = world == null ? null : world.getProfileSettings();
        List<String> preferred = new ArrayList<>();
        if (profile != null) {
            preferred.add(profile.getRespawnWorld());
            preferred.add(profile.getLinkedOverworld());
        }
        preferred.add("world");
        preferred.add("overworld");
        for (String candidate : preferred) {
            for (String option : fallbackOptions) {
                if (option != null && option.equalsIgnoreCase(safeText(candidate))) {
                    return option;
                }
            }
        }
        return fallbackOptions.getFirst();
    }
    @Override
    public String getDesktopAppId() {
        return "world-designer";
    }

    @Override
    public String getDesktopAppTitle() {
        return "World Designer";
    }

    @Override
    public String getDesktopAppIconPath() {
        return "earth.png";
    }

    @Override
    public DesktopWindowBehavior getDesktopWindowBehavior() {
        return DesktopWindowBehavior.SINGLETON;
    }

    @Override
    public boolean shouldForceSuperScreen() {
        return desktopMode && !(parent instanceof Screen);
    }

    public String worldName() {
        return worldName;
    }

    public String serverId() {
        return serverId;
    }

    @Override
    public <T extends Widget> T addDrawableChild(T widget) {
        if (widget instanceof ContextMenuWidget) {
            return super.addDrawableChild(widget);
        }
        if (parent instanceof Screen screen && screen != this) {
            screen.addDrawableChild(widget);
            return widget;
        }
        return super.addDrawableChild(widget);
    }

    @Override
    public void remove(Widget widget) {
        if (widget instanceof ContextMenuWidget) {
            super.remove(widget);
            return;
        }
        if (parent instanceof Screen screen && screen != this) {
            screen.remove(widget);
            return;
        }
        super.remove(widget);
    }

    private WorldDashboardEntry findWorldDashboard(String worldName) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || worldName == null) {
            return null;
        }
        for (WorldDashboardEntry entry : manager.getWorldDashboardForServer(serverId)) {
            if (entry != null && entry.getWorldName() != null && entry.getWorldName().equalsIgnoreCase(worldName)) {
                return entry;
            }
        }
        return null;
    }

    private IconButton readOnlyButton(String text) {
        IconButton button = new IconButton.Builder()
            .label(text)
            .autoWidthOnTextChange(true)
            .entranceAnimation(false)
            .build();
        button.active = false;
        return button;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private Long parseNullableLong(String value) {
        String text = safeText(value).trim();
        if (text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseNullableDouble(String value) {
        String text = safeText(value).trim();
        if (text.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Float parseNullableFloat(String value) {
        String text = safeText(value).trim();
        if (text.isBlank()) {
            return 0.0F;
        }
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatDecimal(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
