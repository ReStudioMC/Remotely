package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.widgets.ContextMenuWidget;
import restudio.rescreen.ui.widgets.IconButton;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDesignerInputRoutingBehaviorTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void embeddedActionsMenuCapturesPressDragAndReleaseWithoutTouchingTheDetailRowOrHistory()
        throws ReflectiveOperationException {
        Screen host = host();
        RoutingWorldDesigner designer = new RoutingWorldDesigner(host);
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, designer);
        view.init();
        AtomicInteger detailActions = new AtomicInteger();
        AtomicInteger menuActions = new AtomicInteger();
        designer.installDetailAction(80, 80, detailActions::incrementAndGet);
        ContextMenuWidget menu = designer.openMenu(80, 80, menuActions::incrementAndGet);
        menu.render(new TestDrawContext(), menu.getX() + 4, menu.getY() + 4, 0.0F);
        long editVersion = designer.collaborationEditVersion();

        assertTrue(view.mouseClicked(leftMouse(view, ReMouseEvent.Action.PRESSED,
            menu.getX() + 4, menu.getY() + 4, 0, 0)));
        assertEquals(1, menuActions.get());
        assertEquals(0, detailActions.get());
        assertEquals(editVersion, designer.collaborationEditVersion());
        assertFalse(view.hasUnsavedChanges());

        assertTrue(view.mouseDragged(leftMouse(view, ReMouseEvent.Action.DRAGGED,
            menu.getX() + 8, menu.getY() + 8, 4, 4)));
        assertTrue(view.mouseReleased(leftMouse(view, ReMouseEvent.Action.RELEASED,
            menu.getX() + 8, menu.getY() + 8, 0, 0)));
        assertEquals(1, menuActions.get());
        assertEquals(0, detailActions.get());
        assertEquals(editVersion, designer.collaborationEditVersion());
        assertFalse(view.hasUnsavedChanges());
    }

    @Test
    void slowSnapshotNeverQueuesOrReplaysRawEmbeddedInput() throws ReflectiveOperationException {
        Screen host = host();
        RoutingWorldDesigner designer = new RoutingWorldDesigner(host);
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, designer);
        view.init();
        Object draft = draft(designer);
        Field frozen = VersionedEditorDraft.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        frozen.setBoolean(draft, true);

        for (int index = 0; index < 128; index++) {
            double x = 160 + index % 16;
            double y = 100 + index % 8;
            view.mouseClicked(leftMouse(view, ReMouseEvent.Action.PRESSED, x, y, 0, 0));
            view.mouseDragged(leftMouse(view, ReMouseEvent.Action.DRAGGED, x + 2, y + 2, 2, 2));
            view.mouseReleased(leftMouse(view, ReMouseEvent.Action.RELEASED, x + 2, y + 2, 0, 0));
            view.mouseScrolled(new ReScrollEvent(view, view, 0L, ReModifierState.none(), x, y, 0, -1));
            view.keyPressed(new ReKeyEvent(view, view, 0L, ReModifierState.none(), ReKeyEvent.Action.PRESSED,
                ReKey.A, 0, 0, ReKeyLocation.STANDARD, false));
            view.textInput(new ReTextInputEvent(view, view, 0L, ReModifierState.none(), "a", 'a'));
        }

        Field deferred = VersionedEditorDraft.class.getDeclaredField("deferred");
        deferred.setAccessible(true);
        assertTrue(((Deque<?>) deferred.get(draft)).isEmpty());
        Field backpressured = VersionedEditorDraft.class.getDeclaredField("backpressured");
        backpressured.setAccessible(true);
        assertFalse(backpressured.getBoolean(draft));
        assertEquals(0L, designer.collaborationEditVersion());
        frozen.setBoolean(draft, false);
    }

    private static Screen host() {
        Screen host = new Screen();
        host.width = 640;
        host.height = 360;
        return host;
    }

    private static ReMouseEvent leftMouse(Object source, ReMouseEvent.Action action, double x, double y,
                                          double deltaX, double deltaY) {
        return new ReMouseEvent(source, source, 0L, ReModifierState.none(), action, x, y, deltaX, deltaY,
            ReMouseButton.LEFT, 0, 1);
    }

    private static Object draft(WorldDesignerScreen designer) throws ReflectiveOperationException {
        Field field = WorldDesignerScreen.class.getDeclaredField("worldDraft");
        field.setAccessible(true);
        return field.get(designer);
    }

    private static final class RoutingWorldDesigner extends WorldDesignerScreen {
        private RoutingWorldDesigner(Screen host) {
            super("routing-world", "routing-server", host);
        }

        private ContextMenuWidget openMenu(int x, int y, Runnable action) {
            showStudioContextMenu(x, y, new ContextMenuWidget.Builder(this).addItem("Load", action, "Load World"));
            return Stream.concat(hudWidgets.stream(), getScreenWidgetRoots().stream())
                .filter(ContextMenuWidget.class::isInstance)
                .map(ContextMenuWidget.class::cast)
                .filter(ContextMenuWidget::isOpen)
                .findFirst()
                .orElseThrow();
        }

        private void installDetailAction(int x, int y, Runnable action) throws ReflectiveOperationException {
            Field field = WorldDesignerScreen.class.getDeclaredField("detailPane");
            field.setAccessible(true);
            Container pane = (Container) field.get(this);
            IconButton button = new IconButton.Builder()
                .label("Underlying World")
                .size(180, 22)
                .entranceAnimation(false)
                .onClick(action)
                .build();
            button.setPosition(x, y);
            pane.addWidget(button);
        }
    }
}
