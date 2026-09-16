package redxax.oxy.remotely.flow.ui.studio;

import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.ui.widgets.AnimatedWidget;

import java.util.List;

public interface ReSyncStudioView extends StudioResourceRenameAware {
    default void init() {}
    default void selected() {}
    default void deselected() {}
    default void clearFocus() {}
    default void closed() {}
    default void tick() {}
    default void resize(int width, int height) {}
    default List<AnimatedWidget> headerButtons() { return List.of(); }
    default StudioPanel.Placement preferredPanelPlacement() { return StudioPanel.Placement.RIGHT; }
    default boolean hasPanel() { return false; }
    default void configurePanel(StudioPanel panel) {}
    default boolean hasUnsavedChanges() { return false; }
    default boolean requestSave() {
        return this instanceof StudioSaveProvider provider && provider.requestStudioSave();
    }
    default void markChangesSaving(long sequence) {}
    default void markChangesSaved() {}
    default void markChangesSaved(long sequence) { markChangesSaved(); }
    default void discardUnsavedChanges() {}
    default void renderPreview(IDrawContext context, int x, int y, int width, int height) {}
    void render(IDrawContext context, int mouseX, int mouseY, float delta);
    default boolean mouseClicked(ReMouseEvent event) {
        return false;
    }

    default boolean mouseReleased(ReMouseEvent event) {
        return false;
    }

    default boolean mouseDragged(ReMouseEvent event) {
        return false;
    }

    default void mouseMoved(ReMouseEvent event) {
    }

    default boolean mouseScrolled(ReScrollEvent event) {
        return false;
    }

    default boolean keyPressed(ReKeyEvent event) {
        return false;
    }

    default boolean textInput(ReTextInputEvent event) {
        return false;
    }
}
