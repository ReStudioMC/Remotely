package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.test.TestDrawContext;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.ui.AdvancementDesignerScreen;
import redxax.oxy.remotely.flow.ui.ChatDesignerScreen;
import redxax.oxy.remotely.flow.ui.DialogDesignerScreen;
import redxax.oxy.remotely.flow.ui.FocusedJsonResourceDesignerScreen;
import redxax.oxy.remotely.flow.ui.GuiDesignerScreen;
import redxax.oxy.remotely.flow.ui.StudioCloseHandledScreen;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenBackedStudioViewResizeTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void idleFramesDoNotForwardUnchangedDimensions() {
        Screen host = new Screen();
        host.resize(1280, 720);
        CountingScreen child = new CountingScreen();
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, child);

        view.init();
        for (int frame = 0; frame < 240; frame++) {
            view.resize(1280, 720);
        }

        assertEquals(1, child.resizeCalls);

        view.resize(1281, 720);
        view.resize(1281, 720);

        assertEquals(2, child.resizeCalls);
    }

    @Test
    void hostTickForwardsExactlyOnceAndRenderAddsNoLifecycleTick() {
        Screen host = new Screen();
        host.resize(1280, 720);
        CountingScreen child = new CountingScreen();
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, child);

        view.init();
        view.tick();
        assertEquals(1, child.tickCalls);

        view.render(new TestDrawContext(), 0, 0, 0.0F);
        assertEquals(1, child.tickCalls);

        view.tick();
        assertEquals(2, child.tickCalls);

        view.render(new TestDrawContext(), 0, 0, 0.0F);
        assertEquals(2, child.tickCalls);
        assertEquals(2, child.renderCalls);
    }

    @Test
    void embeddedDesignerRemovalUnregistersEveryRefreshTarget() {
        Screen host = new Screen();
        GuiDesignerScreen gui = new GuiDesignerScreen(new GuiDefinition("gui", "GUI", 1), "gui-server", host);
        AdvancementDesignerScreen advancement = new AdvancementDesignerScreen(new JsonObject(), "advancement-server", host);
        DialogDesignerScreen dialog = new DialogDesignerScreen(new JsonObject(), "dialog-server", host);
        ChatDesignerScreen chat = new ChatDesignerScreen(new StudioScreen(), "chat", new JsonObject(), "chat-server", host);

        assertTrue(GuiDesignerScreen.hasOpenScreenForServer("gui-server"));
        assertTrue(AdvancementDesignerScreen.hasOpenScreenForServer("advancement-server"));
        assertTrue(DialogDesignerScreen.hasOpenScreenForServer("dialog-server"));
        assertTrue(FocusedJsonResourceDesignerScreen.hasOpenScreenForServer("chat-server"));

        new ScreenBackedStudioView(host, gui).closed();
        new ScreenBackedStudioView(host, advancement).closed();
        new ScreenBackedStudioView(host, dialog).closed();
        new ScreenBackedStudioView(host, chat).closed();

        assertFalse(GuiDesignerScreen.hasOpenScreenForServer("gui-server"));
        assertFalse(AdvancementDesignerScreen.hasOpenScreenForServer("advancement-server"));
        assertFalse(DialogDesignerScreen.hasOpenScreenForServer("dialog-server"));
        assertFalse(FocusedJsonResourceDesignerScreen.hasOpenScreenForServer("chat-server"));
    }

    @Test
    void fullEditorCloseWaitsForTheChildAnimation() {
        Screen host = new Screen();
        CloseHandledScreen child = new CloseHandledScreen();
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, child, true);

        assertFalse(view.closeAnimationFinished());
        assertTrue(view.requestCloseAnimation());
        assertEquals(1, child.closeCalls);
        assertFalse(view.closeAnimationFinished());

        child.finished = true;

        assertTrue(view.closeAnimationFinished());
    }

    private static final class CountingScreen extends Screen {
        private int resizeCalls;
        private int tickCalls;
        private int renderCalls;

        @Override
        public void resize(int width, int height) {
            resizeCalls++;
            super.resize(width, height);
        }

        @Override
        public void tick() {
            tickCalls++;
        }

        @Override
        public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
            renderCalls++;
        }
    }

    private static final class CloseHandledScreen extends Screen implements StudioCloseHandledScreen {
        private boolean finished;
        private int closeCalls;

        @Override
        public void close() {
            closeCalls++;
        }

        @Override
        public void setStudioCloseHandler(Runnable closeHandler) {
        }

        @Override
        public boolean isStudioCloseAnimationFinished() {
            return finished;
        }
    }
}
