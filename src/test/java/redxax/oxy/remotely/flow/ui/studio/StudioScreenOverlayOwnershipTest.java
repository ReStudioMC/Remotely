package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenOverlayWidget;
import restudio.rescreen.ui.widgets.AnimatedWidget;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudioScreenOverlayOwnershipTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void hostPanelRendersItsOverlayWhileAnEmbeddedDesignerIsActive() {
        TestStudioScreen host = new TestStudioScreen();
        host.resize(1280, 720);
        StudioPanel panel = new StudioPanel(host, "resource").required();
        panel.sidePanel().animation(false);
        panel.show();
        ProbeOverlay overlay = new ProbeOverlay();
        panel.mountWidget(overlay);
        panel.sidePanel().update();

        host.activate(panel, new ScreenBackedStudioView(host, new SharedPanelScreen()));
        host.renderOverlayPass(new TestDrawContext(), 0, 0, 0f);

        assertEquals(1, overlay.renderCount);
    }

    private static final class TestStudioScreen extends StudioScreen {
        private void activate(StudioPanel panel, ReSyncStudioView view) {
            studioMode = true;
            studioResourceStudioPanel = panel;
            studioResourcePanel = panel.sidePanel();
            activeStudioDocument = new StudioDocument("test", "designer", "Designer", null, view, new StudioViewportState());
        }
    }

    private static final class SharedPanelScreen extends Screen implements ReSyncStudioView {
        @Override
        public boolean hasPanel() {
            return true;
        }

        @Override
        public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        }
    }

    private static final class ProbeOverlay extends AnimatedWidget implements ScreenOverlayWidget {
        private int renderCount;

        private ProbeOverlay() {
            super(0, 0, 100, 18, "Overlay");
        }

        @Override
        public boolean hasScreenOverlay() {
            return true;
        }

        @Override
        public void renderScreenOverlay(IDrawContext context, int mouseX, int mouseY, float delta) {
            renderCount++;
        }

        @Override
        protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
        }
    }
}
