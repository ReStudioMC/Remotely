package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.screens.PopupOverlay;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioChatShortcutFocusTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void embeddedSelectorSearchOwnsKeyboardInputUntilItCloses() {
        ChatHost host = new ChatHost();
        StudioInfiniteScreen designer = new StudioInfiniteScreen();
        host.activate(designer);
        ItemSelectorWidget selector = new ItemSelectorWidget(designer);

        selector.show(20, 20);

        assertTrue(host.keyboardInputFocused());

        selector.hide();

        assertFalse(host.keyboardInputFocused());
    }

    @Test
    void embeddedDesignerTextFieldOwnsKeyboardInputButCanvasDoesNot() {
        ChatHost host = new ChatHost();
        StudioInfiniteScreen designer = new StudioInfiniteScreen();
        host.activate(designer);
        TextInputWidget text = new TextInputWidget(10, 10, 100, 18);

        designer.setFocusedWidget(text);

        assertTrue(host.keyboardInputFocused());

        designer.setFocusedWidget(null);

        assertFalse(host.keyboardInputFocused());
    }

    @Test
    void createSelectorSearchKeepsTWhileKeyFallsThroughToStudioHost() throws Exception {
        ChatHost host = new ChatHost();
        host.resize(1280, 720);
        host.studioMode = true;
        ReSyncContentBrowserWidget browser = new ReSyncContentBrowserWidget(host, 0, 0, 190, 600);
        PopupOverlay overlay = ScreenManager.getInstance().getPopupOverlay();
        try {
            SquareButtonWidget createButton = (SquareButtonWidget) field("createButton").get(browser);
            createButton.action.run();
            ItemSelectorWidget selector = (ItemSelectorWidget) field("createSelector").get(browser);
            assertSame(selector, overlay.getFocusedWidget());

            ReKeyEvent key = new ReKeyEvent(host, host, 1L, ReModifierState.none(),
                ReKeyEvent.Action.PRESSED, ReKey.T, 84, 0, ReKeyLocation.STANDARD, false);
            assertFalse(Screen.dispatchKeyPressed(overlay, key));
            Screen.dispatchKeyPressed(host, key);
            assertTrue(host.keyboardInputFocused());

            ReTextInputEvent text = new ReTextInputEvent(host, host, 2L, ReModifierState.none(), "t", 't');
            assertTrue(Screen.dispatchTextInput(overlay, text));
            assertEquals("t", selector.getSearchText());
        } finally {
            ItemSelectorWidget selector = (ItemSelectorWidget) field("createSelector").get(browser);
            if (selector != null) {
                selector.hide();
                overlay.remove(selector);
            }
            browser.dispose();
        }
    }

    private static Field field(String name) throws Exception {
        Field field = ReSyncContentBrowserWidget.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class ChatHost extends StudioScreen {
        private void activate(StudioInfiniteScreen designer) {
            studioMode = true;
            activeStudioDocument = new StudioDocument("test", "content", "Content", null,
                new ScreenBackedStudioView(this, designer), new StudioViewportState());
        }

        private boolean keyboardInputFocused() {
            return isStudioKeyboardInputFocused();
        }
    }
}
