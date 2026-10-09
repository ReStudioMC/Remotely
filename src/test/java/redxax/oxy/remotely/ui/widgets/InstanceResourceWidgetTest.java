package redxax.oxy.remotely.ui.widgets;

import org.junit.jupiter.api.Test;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.ui.screens.resources.DesktopResourceContainerProvider;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rescreen.platform.ReScreenRuntime;
import restudio.rescreen.platform.desktop.DesktopReScreenRuntime;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceResourceWidgetTest {
    @Test
    void indexedIconReplacesFallbackWithoutProjectLookup() {
        ThemeManager.initBrowserDefaults();
        ScreenManager screens = ScreenManager.getInstance();
        ReScreenRuntime previous = screens.runtime();
        DesktopReScreenRuntime runtime = new DesktopReScreenRuntime(() -> 0, Runnable::run);
        DesktopResourceContainerProvider provider = new DesktopResourceContainerProvider(null);
        screens.restoreRuntimeReference(runtime);
        ResourceContainerItem resource = new ResourceContainerItem("plugins/example.jar", ResourceType.PLUGIN, "example.jar", true);
        Identifier fallback = Identifier.icon("missing.png");
        resource.setIconId(fallback);
        resource.setIconUrl("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        InstanceResourceWidget widget = new InstanceResourceWidget(null, provider, resource, null);
        try {
            screens.processTasks();
            Identifier loaded = resource.getIconId();
            assertNotEquals(fallback, loaded);
            assertTrue(runtime.imageAssets().hasImage(loaded));
            widget.refresh();
            screens.processTasks();
            assertEquals(loaded, resource.getIconId());
        } finally {
            widget.cleanup();
            provider.stopWatching();
            screens.restoreRuntimeReference(previous);
        }
    }
}
