package redxax.oxy.remotely.ui.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.rebase.instance.Instance;
import restudio.rescreen.platform.Async;
import restudio.rescreen.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerIconProviderLifecycleTest {
    @Test
    void rejectedDesktopCustomizationCompletesExceptionally(@TempDir Path cache) {
        try (DesktopServerIconProvider provider = new DesktopServerIconProvider(cache)) {
            Async<Void> result = provider.customizeIcon(null, null, Identifier.icon("ic_1.png"), null);
            assertTrue(result.isDone());
            assertNotNull(result.failure());
        }
    }

    @Test
    void explicitRefreshInvalidatesSharedMissingIcon(@TempDir Path application) throws IOException {
        Path serverDir = Files.createDirectory(application.resolve("server"));
        Instance server = new Instance("Icon Test", "1.21.1", serverDir.toString());
        try (DesktopServerIconProvider first = new DesktopServerIconProvider(application);
             DesktopServerIconProvider second = new DesktopServerIconProvider(application)) {
            Identifier fallback = first.getIconId(server);
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xff00ff00);
            ImageIO.write(image, "png", serverDir.resolve("icon.png").toFile());
            assertEquals(fallback, second.getIconId(server));
            second.clearAllRemoteTracking();
            Identifier imported = first.getIconId(server);
            assertNotEquals(fallback, imported);
            second.clearCache(server);
            assertEquals(fallback, first.getQuickIconId(server));
        }
    }

    @Test
    void logicalCustomizationCompletesAndInvokesCallback() {
        AtomicInteger callbacks = new AtomicInteger();

        Async<Void> result = ServerIconProvider.logical().customizeIcon(
                new ServerIconProvider.LogicalServer("paper", "paper"), null, Identifier.icon("ic_1.png"), callbacks::incrementAndGet);

        assertTrue(result.isDone());
        assertEquals(1, callbacks.get());
    }
}
