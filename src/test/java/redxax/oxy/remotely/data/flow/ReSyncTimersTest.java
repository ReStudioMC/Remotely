package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncTimersTest {
    @Test
    void shutdownCancelsTimersWithoutClosingAnUnownedScheduler() {
        Scheduler scheduler = new Scheduler();
        ReSyncTimers timers = new ReSyncTimers(scheduler, false);
        AtomicInteger runs = new AtomicInteger();
        timers.schedule(runs::incrementAndGet, Duration.ofSeconds(1));
        timers.scheduleAtFixedRate(runs::incrementAndGet, 1L, Duration.ofSeconds(1));
        timers.shutdownNow();
        scheduler.tasks.forEach(task -> task.action.run());

        assertEquals(0, runs.get());
        assertTrue(scheduler.tasks.stream().allMatch(Task::isCancelled));
        assertFalse(scheduler.closed);
        assertThrows(IllegalStateException.class, () -> timers.schedule(() -> { }, Duration.ZERO));
    }

    @Test
    void shutdownDuringRegistrationCancelsTheLateTimerAndClosesItsOwnedScheduler() {
        Scheduler scheduler = new Scheduler();
        ReSyncTimers timers = new ReSyncTimers(scheduler, true);
        scheduler.beforeReturn = timers::shutdownNow;
        AtomicInteger runs = new AtomicInteger();
        TaskScheduler.ScheduledTask task = timers.schedule(runs::incrementAndGet, Duration.ofSeconds(1));
        scheduler.tasks.getFirst().action.run();

        assertTrue(task.isCancelled());
        assertTrue(scheduler.tasks.getFirst().isCancelled());
        assertTrue(scheduler.closed);
        assertEquals(0, runs.get());
    }

    private static final class Scheduler implements TaskScheduler, AutoCloseable {
        private final List<Task> tasks = new ArrayList<>();
        private Runnable beforeReturn = () -> { };
        private boolean closed;
        @Override public void execute(Runnable action) { action.run(); }
        @Override public ScheduledTask schedule(Runnable action, Duration delay) {
            Task task = new Task(action);
            tasks.add(task);
            beforeReturn.run();
            return task;
        }
        @Override public ScheduledTask scheduleAtFixedRate(Runnable action, Duration delay, Duration period) {
            return schedule(action, delay);
        }
        @Override public void close() { closed = true; }
    }

    private static final class Task implements TaskScheduler.ScheduledTask {
        private final Runnable action;
        private boolean cancelled;
        private Task(Runnable action) { this.action = action; }
        @Override public boolean cancel() { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
    }
}
