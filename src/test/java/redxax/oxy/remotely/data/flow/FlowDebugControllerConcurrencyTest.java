package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDebugControllerConcurrencyTest {
    @Test
    void clearAndSnapshotUseTheSameMonitor() throws Exception {
        FlowDebugController controller = new FlowDebugController(null);
        BlockingClearList records = new BlockingClearList();
        records.add(new FlowDebugController.DebugRecord(1, 1, "session", "node", "graph", "node", "type", "paused", "",
            "", "", "", "", "", "", "", 0));
        Field recordsField = FlowDebugController.class.getDeclaredField("recentRecords");
        recordsField.setAccessible(true);
        recordsField.set(controller, records);

        CompletableFuture<Void> clear = CompletableFuture.runAsync(() -> controller.clear(""));
        assertTrue(records.clearEntered.await(1, TimeUnit.SECONDS));
        CompletableFuture<List<FlowDebugController.DebugRecord>> snapshot = CompletableFuture.supplyAsync(controller::getRecentRecords);
        try {
            assertThrows(TimeoutException.class, () -> snapshot.get(100, TimeUnit.MILLISECONDS));
        } finally {
            records.releaseClear.countDown();
        }

        clear.get(1, TimeUnit.SECONDS);
        assertTrue(records.clearHeldMonitor);
        assertTrue(snapshot.get(1, TimeUnit.SECONDS).isEmpty());
    }

    private static final class BlockingClearList extends ArrayList<FlowDebugController.DebugRecord> {
        private final CountDownLatch clearEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClear = new CountDownLatch(1);
        private volatile boolean clearHeldMonitor;

        @Override
        public void clear() {
            clearHeldMonitor = Thread.holdsLock(this);
            clearEntered.countDown();
            try {
                if (!releaseClear.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Clear was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            super.clear();
        }
    }
}
