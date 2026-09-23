package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.ui.VersionedEditorDraft;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.render.Render;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;

import java.util.List;
import java.util.function.Consumer;

public class GuiStudioPreviewView implements ReSyncStudioView, ReSyncCollaborativeView, StudioCatalogRefreshView {
    private static final Gson GSON = new Gson();
    private final String serverId;
    private final String guiId;
    private final VersionedEditorDraft<JsonObject> guiDraft;
    private GuiDefinition gui;
    private volatile long collaborationLifecycle = 1L;

    public GuiStudioPreviewView(String serverId, String guiId) {
        this.serverId = serverId;
        this.guiId = guiId;
        this.guiDraft = new VersionedEditorDraft<>(new JsonObject(), JsonObject::toString,
            payload -> GSON.fromJson(payload, JsonObject.class), (previous, replacement) -> {}, failure -> {}, () -> {});
        refresh();
    }

    @Override
    public void selected() {
        if (guiDraft.defer(this::refresh)) {
            return;
        }
        refresh();
    }

    @Override
    public void onStudioCatalogRefreshed() {
        if (guiDraft.defer(this::refresh)) {
            return;
        }
        refresh();
    }

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
        return guiDraft.requestProjection("gui", guiId, this::collaborationDocument,
            projection -> completion.accept(collaborationSnapshot(lifecycle, projection)));
    }

    private CollaborationDocumentSnapshot collaborationSnapshot(long lifecycle,
                                                                 VersionedEditorDraft.ProjectionSnapshot projection) {
        RuntimeException failure = projection.failure();
        JsonObject document = null;
        if (failure == null) {
            try {
                document = GSON.fromJson(projection.payload(), JsonObject.class);
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
        return guiDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (gui == null) {
                throw new IllegalStateException("GUI Preview Is Unavailable");
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
        if (gui == null) {
            return;
        }
        ReSyncCollaborationDocuments.copy(gui, ReSyncCollaborationDocuments.gui(document));
    }

    @Override
    public void renderPreview(IDrawContext context, int x, int y, int width, int height) {
        if (gui == null) {
            return;
        }
        int rows = Math.clamp(gui.getRows(), 1, 6);
        int slot = Math.clamp(Math.min((width - 40) / 9, (height - 40) / rows), 12, 26);
        int gridWidth = slot * 9;
        int gridHeight = slot * rows;
        int startX = x + Math.max(0, (width - gridWidth) / 2);
        int startY = y + Math.max(0, (height - gridHeight) / 2);
        int border = ThemeManager.getColor(ThemeColor.innerBorder);
        int background = ThemeManager.getColor(ThemeColor.innerBackground);
        Render.drawLayeredInnerBorder(context, startX - 6, startY - 18, gridWidth + 12, gridHeight + 24, background, border);
        context.drawText(gui.getTitle() == null || gui.getTitle().isBlank() ? gui.getId() : gui.getTitle(), startX, startY - 12, ThemeManager.getColor(ThemeColor.text), false);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int slotX = startX + col * slot;
                int slotY = startY + row * slot;
                Render.drawLayeredInnerBorder(context, slotX, slotY, slot - 1, slot - 1, ThemeManager.getColor(ThemeColor.background), border);
            }
        }
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        guiDraft.drain();
    }

    @Override
    public void closed() {
        collaborationLifecycle++;
        guiDraft.close();
    }

    private void refresh() {
        FlowManager manager = FlowManager.getInstance();
        gui = manager != null ? manager.getGui(serverId, guiId) : null;
    }
}
