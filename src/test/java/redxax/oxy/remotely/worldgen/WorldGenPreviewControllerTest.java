package redxax.oxy.remotely.worldgen;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.TaskScheduler;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenPreviewControllerTest {
    @Test
    void clearServerCancelsTrackedTimerAndCallback() {
        WorldGenPreviewController controller = new WorldGenPreviewController();
        AtomicBoolean callbackCancelled = new AtomicBoolean();
        TestTimer timer = new TestTimer();

        controller.markCreating("server-a", "preview-a");
        controller.update("server-a", "preview-a", "ready");
        controller.trackTimer("server-a", "preview-a", timer);
        controller.addCancellationCallback("server-a", "preview-a", () -> callbackCancelled.set(true));

        controller.clearServer("server-a");

        assertEquals("stopped", controller.state("server-a", "preview-a"));
        assertTrue(timer.cancelled);
        assertTrue(callbackCancelled.get());
    }

    @Test
    void clearAllCancelsEveryPreviewWithoutTouchingNewServerState() {
        WorldGenPreviewController controller = new WorldGenPreviewController();
        controller.markCreating("server-a", "preview-a");
        controller.markCreating("server-b", "preview-b");
        controller.clearAll();

        assertEquals("stopped", controller.state("server-a", "preview-a"));
        assertEquals("stopped", controller.state("server-b", "preview-b"));

        controller.markCreating("server-a", "preview-new");
        assertEquals("creating", controller.state("server-a", "preview-new"));
    }

    private static final class TestTimer implements TaskScheduler.ScheduledTask {
        private boolean cancelled;

        @Override
        public boolean cancel() {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
