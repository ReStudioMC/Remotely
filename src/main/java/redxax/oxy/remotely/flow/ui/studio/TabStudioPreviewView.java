package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.flow.data.TabDefinition;
import redxax.oxy.remotely.flow.ui.VersionedEditorDraft;
import restudio.rescreen.platform.IDrawContext;
import restudio.resync.flow.workspace.WorkspacePatch;

import java.util.List;
import java.util.function.Consumer;

public class TabStudioPreviewView implements ReSyncStudioView, ReSyncCollaborativeView, StudioCatalogRefreshView {
    private static final Gson GSON = new Gson();
    private final String serverId;
    private final String tabId;
    private final VersionedEditorDraft<JsonObject> tabDraft;
    private TabDefinition tab;
    private volatile long collaborationLifecycle = 1L;

    public TabStudioPreviewView(String serverId, String tabId) {
        this.serverId = serverId;
        this.tabId = tabId;
        this.tabDraft = new VersionedEditorDraft<>(new JsonObject(), JsonObject::toString,
            payload -> GSON.fromJson(payload, JsonObject.class), (previous, replacement) -> {}, failure -> {}, () -> {});
        refresh();
    }

    @Override
    public void selected() {
        if (tabDraft.defer(this::refresh)) {
            return;
        }
        refresh();
    }

    @Override
    public void onStudioCatalogRefreshed() {
        if (tabDraft.defer(this::refresh)) {
            return;
        }
        refresh();
    }

    @Override
    public JsonObject collaborationDocument() {
        return ReSyncCollaborationDocuments.from(tab);
    }

    @Override
    public long collaborationLifecycle() {
        return collaborationLifecycle;
    }

    @Override
    public long collaborationEditVersion() {
        return tabDraft.editVersion();
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null) {
            return false;
        }
        long lifecycle = collaborationLifecycle;
        return tabDraft.requestProjection("tab", tabId, this::collaborationDocument,
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
        return tabDraft.runMutation(() -> {
            if (document == null) {
                throw new IllegalArgumentException("Collaboration Document Is Required");
            }
            if (tab == null) {
                throw new IllegalStateException("Tab Preview Is Unavailable");
            }
            if (ReSyncCollaborationDocuments.tab(document) == null) {
                throw new IllegalArgumentException("Collaboration Document Is Invalid");
            }
            applyCollaborationDocument(document, patches);
        }, result ->
            completion.accept(new CollaborationDocumentApplyResult(this, lifecycle, result.beforeEditVersion(),
                result.afterEditVersion(), result.successful(), result.failure())));
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (tabDraft.defer(() -> applyCollaborationDocument(document, patches))) {
            return;
        }
        tabDraft.markMutation();
        if (tab == null) {
            return;
        }
        ReSyncCollaborationDocuments.copy(tab, ReSyncCollaborationDocuments.tab(document));
    }

    @Override
    public void renderPreview(IDrawContext context, int x, int y, int width, int height) {
        if (tab == null) {
            return;
        }
        int panelWidth = Math.clamp(width / 3, 160, width - 24);
        int panelHeight = Math.min(120, height - 24);
        int startX = x + Math.max(0, (width - panelWidth) / 2);
        int startY = y + Math.max(0, (height - panelHeight) / 2);
        context.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0x7F101010);
        int text = 0xFFFFFFFF;
        context.drawText(tab.getHeader() == null || tab.getHeader().isBlank() ? tab.getId() : tab.getHeader(), startX + 6, startY + 6, text, true);
        context.drawText(tab.getEntryFormat() == null || tab.getEntryFormat().isBlank() ? "%player%" : tab.getEntryFormat(), startX + 6, startY + 48, text, true);
        context.drawText(tab.getFooter() == null ? "" : tab.getFooter(), startX + 6, startY + panelHeight - 18, text, true);
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        tabDraft.drain();
    }

    @Override
    public void closed() {
        collaborationLifecycle++;
        tabDraft.close();
    }

    private void refresh() {
        FlowManager manager = FlowManager.getInstance();
        tab = manager != null ? manager.getTab(serverId, tabId) : null;
    }
}
