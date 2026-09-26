package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.world.WorldOperationResult;
import redxax.oxy.remotely.flow.ui.GraphEditorScreen;
import redxax.oxy.remotely.flow.ui.StudioCloseHandledScreen;
import restudio.resync.flow.contract.EditorError;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ScreenBackedStudioView implements ReSyncStudioView, ReSyncCollaborativeView, StudioSelectorView, WorldStudioDocumentView {
    private final Screen host;
    private final Screen screen;
    private final boolean fullEditor;
    private boolean initialized;
    private boolean exposingHeaderButtons;
    private int appliedWidth = Integer.MIN_VALUE;
    private int appliedHeight = Integer.MIN_VALUE;

    public ScreenBackedStudioView(Screen host, Screen screen) {
        this(host, screen, false);
    }

    public ScreenBackedStudioView(Screen host, Screen screen, boolean fullEditor) {
        this.host = host;
        this.screen = screen;
        this.fullEditor = fullEditor;
    }

    public Screen screen() {
        return screen;
    }

    public boolean fullEditor() {
        return fullEditor;
    }

    public boolean closeAnimationFinished() {
        return !(screen instanceof StudioCloseHandledScreen closeHandledScreen)
            || closeHandledScreen.isStudioCloseAnimationFinished();
    }

    public boolean requestCloseAnimation() {
        if (!(screen instanceof StudioCloseHandledScreen)) {
            return false;
        }
        screen.close();
        return true;
    }

    public boolean initialized() {
        return initialized;
    }

    public boolean ownsSidePanels() {
        init();
        return screen instanceof ReScreen reScreen && !reScreen.getSidePanels().isEmpty();
    }

    @Override
    public boolean hasKeyboardInputFocus() {
        return screen instanceof StudioInfiniteScreen studio
            ? studio.isStudioKeyboardInputFocused()
            : StudioInfiniteScreen.keyboardInput(screen.getFocusedDescendant());
    }

    public void applyEditorError(EditorError error) {
        clearEditorError();
        if (screen instanceof ReSyncEditorDiagnosticView diagnosticView) {
            diagnosticView.applyEditorError(error);
        }
    }

    public void clearEditorError() {
        if (screen instanceof ReSyncEditorDiagnosticView diagnosticView) {
            diagnosticView.clearEditorError();
        }
    }

    @Override
    public boolean supportsCollaboration() {
        return screen instanceof ReSyncCollaborativeView collaborative && collaborative.supportsCollaboration();
    }

    @Override
    public long collaborationLifecycle() {
        return screen instanceof ReSyncCollaborativeView collaborative ? collaborative.collaborationLifecycle() : 0L;
    }

    @Override
    public long collaborationEditVersion() {
        return screen instanceof ReSyncCollaborativeView collaborative ? collaborative.collaborationEditVersion() : -1L;
    }

    @Override
    public boolean requestCollaborationDocument(Consumer<CollaborationDocumentSnapshot> completion) {
        if (completion == null || !(screen instanceof ReSyncCollaborativeView collaborative)) {
            return ReSyncCollaborativeView.super.requestCollaborationDocument(completion);
        }
        return collaborative.requestCollaborationDocument(snapshot -> completion.accept(snapshot.transferredTo(this)));
    }

    @Override
    public boolean applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches,
                                              Consumer<CollaborationDocumentApplyResult> completion) {
        if (completion == null) {
            return false;
        }
        if (screen instanceof ReSyncCollaborativeView collaborative) {
            return collaborative.applyCollaborationDocument(document, patches,
                result -> completion.accept(result.ownedBy(this)));
        }
        ReSyncCollaborativeView.completeApply(completion, new CollaborationDocumentApplyResult(this,
            collaborationLifecycle(), -1L, -1L, false,
            new IllegalStateException("Collaboration Apply Unsupported")));
        return false;
    }

    @Override
    public JsonObject collaborationDocument() {
        return screen instanceof ReSyncCollaborativeView collaborative ? collaborative.collaborationDocument() : null;
    }

    @Override
    public void applyCollaborationDocument(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (screen instanceof ReSyncCollaborativeView collaborative) {
            collaborative.applyCollaborationDocument(document, patches);
        }
    }

    @Override
    public void rebaseCollaborationHistory(List<WorkspacePatch<JsonElement>> patches) {
        if (screen instanceof ReSyncCollaborativeView collaborative) {
            collaborative.rebaseCollaborationHistory(patches);
        }
    }

    @Override
    public List<AnimatedWidget> headerButtons() {
        init();
        exposingHeaderButtons = true;
        if (screen instanceof StudioHeaderProvider provider) {
            return provider.getStudioHeaderButtons();
        }
        if (screen instanceof ReScreen reScreen) {
            reScreen.header().build();
            List<AnimatedWidget> buttons = new ArrayList<>();
            buttons.addAll(reScreen.header().leftButtons);
            buttons.addAll(reScreen.header().rightButtons);
            return buttons;
        }
        return ReSyncStudioView.super.headerButtons();
    }

    @Override
    public StudioPanel.Placement preferredPanelPlacement() {
        init();
        return screen instanceof ReSyncStudioView view ? view.preferredPanelPlacement() : ReSyncStudioView.super.preferredPanelPlacement();
    }

    @Override
    public boolean hasPanel() {
        init();
        return !ownsSidePanels() && screen instanceof ReSyncStudioView view && view.hasPanel();
    }

    @Override
    public void configurePanel(StudioPanel panel) {
        init();
        if (!ownsSidePanels() && screen instanceof ReSyncStudioView view) {
            view.configurePanel(panel);
        }
    }

    @Override
    public void init() {
        if (initialized) {
            return;
        }
        resizeScreen(host.width, host.height);
        screen.init();
        if (screen instanceof ReScreen reScreen) {
            reScreen.header().visible(false);
        }
        initialized = true;
    }

    @Override
    public void selected() {
        init();
        resizeScreen(host.width, host.height);
        if (screen instanceof StudioDocumentLifecycleScreen lifecycleScreen) {
            lifecycleScreen.studioDocumentSelected();
        }
        if (screen instanceof ReSyncStudioView view) {
            view.selected();
        }
    }

    @Override
    public void deselected() {
        clearFocus();
        if (screen instanceof StudioDocumentLifecycleScreen lifecycleScreen) {
            lifecycleScreen.studioDocumentDeselected();
        }
        if (screen instanceof ReSyncStudioView view) {
            view.deselected();
        }
    }

    @Override
    public void clearFocus() {
        screen.setFocusedWidget(null);
    }

    @Override
    public void closed() {
        clearFocus();
        if (screen instanceof StudioDocumentLifecycleScreen lifecycleScreen) {
            lifecycleScreen.studioDocumentClosed();
        }
        screen.removed();
    }

    @Override
    public void tick() {
        if (initialized && !(screen instanceof GraphEditorScreen)) {
            screen.tick();
        }
    }

    @Override
    public boolean deferResourceRename(String type, String oldId, String newId, Runnable mutation) {
        if (screen instanceof StudioResourceRenameAware view) {
            return view.deferResourceRename(type, oldId, newId, mutation);
        }
        return false;
    }

    @Override
    public void resourceRenamed(String type, String oldId, String newId) {
        if (screen instanceof StudioResourceRenameAware view) {
            view.resourceRenamed(type, oldId, newId);
        }
    }

    @Override
    public boolean hasUnsavedChanges() {
        if (screen instanceof ReSyncStudioView view) {
            return view.hasUnsavedChanges();
        }
        return screen instanceof StudioInfiniteScreen studioScreen && studioScreen.hasUnsavedChanges();
    }

    @Override
    public void markChangesSaved() {
        if (screen instanceof ReSyncStudioView view) {
            view.markChangesSaved();
        } else if (screen instanceof StudioInfiniteScreen studioScreen) {
            studioScreen.markChangesSaved();
        }
    }

    @Override
    public void markChangesSaving(long sequence) {
        if (screen instanceof ReSyncStudioView view) {
            view.markChangesSaving(sequence);
        } else if (screen instanceof StudioInfiniteScreen studioScreen) {
            studioScreen.markChangesSaving(sequence);
        }
    }

    @Override
    public void markChangesSaved(long sequence) {
        if (screen instanceof ReSyncStudioView view) {
            view.markChangesSaved(sequence);
        } else if (screen instanceof StudioInfiniteScreen studioScreen) {
            studioScreen.markChangesSaved(sequence);
        }
    }

    @Override
    public void discardUnsavedChanges() {
        if (screen instanceof ReSyncStudioView view) {
            view.discardUnsavedChanges();
        } else if (screen instanceof StudioInfiniteScreen studioScreen) {
            studioScreen.discardUnsavedChanges();
        }
    }

    @Override
    public void resize(int width, int height) {
        if (initialized) {
            resizeScreen(width, height);
        }
    }

    private void resizeScreen(int width, int height) {
        if (appliedWidth == width && appliedHeight == height) {
            return;
        }
        screen.resize(width, height);
        appliedWidth = width;
        appliedHeight = height;
    }

    @Override
    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        init();
        List<AnimatedWidget> exposedHeaders = exposingHeaderButtons ? headerButtons() : List.of();
        List<Boolean> visibility = new ArrayList<>();
        for (AnimatedWidget widget : exposedHeaders) {
            visibility.add(widget.visible);
            widget.visible = false;
        }
        boolean deferred = screen.isOverlayPassDeferred();
        screen.setOverlayPassDeferred(true);
        try {
            screen.renderHandler(context, mouseX, mouseY, delta);
        } finally {
            screen.setOverlayPassDeferred(deferred);
            for (int i = 0; i < exposedHeaders.size(); i++) {
                exposedHeaders.get(i).visible = visibility.get(i);
            }
        }
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        init();
        if (exposingHeaderButtons) {
            for (AnimatedWidget headerButton : headerButtons()) {
                if (headerButton != null && headerButton.visible && headerButton.isMouseOver(event.x(), event.y())) {
                    return false;
                }
            }
        }
        return Screen.dispatchMouseClicked(screen, event.retarget(screen, event.x(), event.y()));
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        return initialized && Screen.dispatchMouseReleased(screen, event.retarget(screen, event.x(), event.y()));
    }

    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        return initialized && Screen.dispatchMouseDragged(screen, event.retarget(screen, event.x(), event.y(), event.deltaX(), event.deltaY()));
    }

    @Override
    public void mouseMoved(ReMouseEvent event) {
        if (initialized) {
            Screen.dispatchMouseMoved(screen, event.retarget(screen, event.x(), event.y()));
        }
    }

    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        return initialized && Screen.dispatchMouseScrolled(screen, event.retarget(screen, event.x(), event.y()));
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        return initialized && Screen.dispatchKeyPressed(screen, event.retarget(screen));
    }

    @Override
    public boolean requestSave() {
        init();
        if (screen instanceof StudioSaveProvider provider) {
            return provider.requestStudioSave();
        }
        return screen instanceof ReSyncStudioView view && view.requestSave();
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        return initialized && Screen.dispatchTextInput(screen, event.retarget(screen));
    }

    @Override
    public boolean hasActiveStudioSelector() {
        return screen instanceof StudioSelectorView view && view.hasActiveStudioSelector();
    }

    @Override
    public ItemSelectorWidget activeStudioSelector() {
        return screen instanceof StudioSelectorView view ? view.activeStudioSelector() : null;
    }

    @Override
    public void refreshWorlds() {
        if (screen instanceof WorldStudioDocumentView view) {
            view.refreshWorlds();
        }
    }

    @Override
    public void handleWorldOperationResult(WorldOperationResult result) {
        if (screen instanceof WorldStudioDocumentView view) {
            view.handleWorldOperationResult(result);
        }
    }
}
