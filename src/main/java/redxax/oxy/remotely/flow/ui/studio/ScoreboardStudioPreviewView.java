package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.ui.VersionedEditorDraft;
import restudio.rescreen.platform.IDrawContext;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.List;
import java.util.function.Consumer;

public class ScoreboardStudioPreviewView implements ReSyncStudioView, ReSyncCollaborativeView, StudioCatalogRefreshView {
    private static final Gson GSON = new Gson();
    private final String serverId;
    private final String scoreboardId;
    private final VersionedEditorDraft<JsonObject> scoreboardDraft;
    private ScoreboardDefinition scoreboard;
    private volatile long collaborationLifecycle = 1L;

    public ScoreboardStudioPreviewView(String serverId, String scoreboardId) {
        this.serverId = serverId;
        this.scoreboardId = scoreboardId;
        this.scoreboardDraft = new VersionedEditorDraft<>(new JsonObject(), JsonObject::toString,
            payload -> GSON.fromJson(payload, JsonObject.class), (previous, replacement) -> {}, failure -> {}, () -> {});
        refresh();
    }

    @Override
    public void selected() {
        if (scoreboardDraft.defer(this::refresh)) {
            return;
        }
        refresh();
    }

    @Override
    public void onStudioCatalogRefreshed() {
        if (scoreboardDraft.defer(this::refresh)) {
            return;
        }
        refresh();
    }

    @Override
    public JsonObject collaborationDocument() {
        return ReSyncCollaborationDocuments.from(scoreboard);
    }

    @Override
    public long collaborationLifecycle() {
        return collaborationLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return scoreboardDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return scoreboardDraft.requestProjection("scoreboard", scoreboardId, this::collaborationDocument,
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
        return scoreboardDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (scoreboard == null) {
                throw new IllegalStateException("Scoreboard Preview Is Unavailable");
            }
            if (ReSyncCollaborationDocuments.to(document, ScoreboardDefinition.class) == null) {
                throw new IllegalArgumentException("Collaboration Document Is Invalid");
            }
            applyCollaborationDocument(document, patches);
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (scoreboardDraft.defer(() -> applyCollaborationDocument(document, patches))) {
            return;
        }
        scoreboardDraft.markMutation();
        if (scoreboard == null) {
            return;
        }
        ReSyncCollaborationDocuments.copy(scoreboard, ReSyncCollaborationDocuments.to(document, ScoreboardDefinition.class));
    }

    @Override
    public void renderPreview(IDrawContext context, int x, int y, int width, int height) {
        if (scoreboard == null) {
            return;
        }
        List<String> lines = scoreboard.getLines() == null ? List.of() : scoreboard.getLines();
        int maxLines = Math.min(15, lines.size());
        int panelWidth = Math.clamp(width / 3, 120, width - 24);
        int rowHeight = 12;
        int panelHeight = Math.min(height - 24, (maxLines + 1) * rowHeight + 8);
        int startX = x + Math.max(0, (width - panelWidth) / 2);
        int startY = y + Math.max(0, (height - panelHeight) / 2);
        context.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0x7F101010);
        context.drawText(scoreboard.getTitle() == null || scoreboard.getTitle().isBlank() ? scoreboard.getId() : scoreboard.getTitle(), startX + 6, startY + 4, 0xFFFFFFFF, true);
        for (int i = 0; i < maxLines; i++) {
            context.drawText(lines.get(i), startX + 6, startY + 18 + i * rowHeight, 0xFFFFFFFF, true);
        }
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        scoreboardDraft.drain();
    }

    @Override
    public void closed() {
        collaborationLifecycle++;
        scoreboardDraft.close();
    }

    private void refresh() {
        FlowManager manager = FlowManager.getInstance();
        scoreboard = manager != null ? manager.getScoreboard(serverId, scoreboardId) : null;
    }
}
