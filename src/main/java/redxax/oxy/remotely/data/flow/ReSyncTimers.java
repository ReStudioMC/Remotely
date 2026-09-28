package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.BrowserWork;
import restudio.rescreen.platform.TaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class ReSyncTimers {
    private final TaskScheduler scheduler;
    private final boolean owned;
    private final Set<Timer> timers = BrowserSafeState.set();
    private final BrowserWork.Executor worker = BrowserWork.executor();
    private boolean closed;

    ReSyncTimers(TaskScheduler scheduler, boolean owned) {
        this.scheduler = Objects.requireNonNull(scheduler, "Connection scheduler is required");
        this.owned = owned;
    }

    TaskScheduler scheduler() {
        return scheduler;
    }

    void execute(Runnable action) {
        worker.execute(action);
    }

    TaskScheduler.ScheduledTask schedule(Runnable action, Duration delay) {
        return schedule(action, delay, null);
    }

    TaskScheduler.ScheduledTask scheduleAtFixedRate(Runnable action, long initial, Duration period) {
        return schedule(action, Duration.ofSeconds(initial), period);
    }

    private TaskScheduler.ScheduledTask schedule(Runnable action, Duration delay, Duration period) {
        Timer timer = new Timer(action, period != null);
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Connection timers are closed");
            }
            timers.add(timer);
        }
        try {
            TaskScheduler.ScheduledTask task = period == null ? scheduler.schedule(timer::run, delay)
                : scheduler.scheduleAtFixedRate(timer::run, delay, period);
            timer.attach(task);
            return timer;
        } catch (RuntimeException failure) {
            timer.cancel();
            throw failure;
        }
    }

    void shutdownNow() {
        List<Timer> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = List.copyOf(timers);
        }
        pending.forEach(Timer::cancel);
        worker.shutdownNow();
        if (owned && scheduler instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Connection scheduler could not close", failure);
            }
        }
    }

    private final class Timer implements TaskScheduler.ScheduledTask {
        private final Runnable action;
        private final boolean repeating;
        private TaskScheduler.ScheduledTask task;
        private boolean cancelled;
        private boolean finished;

        private Timer(Runnable action, boolean repeating) {
            this.action = action;
            this.repeating = repeating;
        }

        private void attach(TaskScheduler.ScheduledTask task) {
            boolean discard;
            synchronized (ReSyncTimers.this) {
                this.task = task;
                discard = cancelled || finished || closed;
            }
            if (discard) task.cancel();
        }

        private void run() {
            synchronized (ReSyncTimers.this) {
                if (cancelled || finished || closed) return;
                if (!repeating) {
                    finished = true;
                    timers.remove(this);
                }
            }
            action.run();
        }

        @Override
        public boolean cancel() {
            TaskScheduler.ScheduledTask current;
            synchronized (ReSyncTimers.this) {
                if (cancelled || finished) return false;
                cancelled = true;
                timers.remove(this);
                current = task;
            }
            if (current != null) current.cancel();
            return true;
        }

        @Override
        public boolean isCancelled() {
            synchronized (ReSyncTimers.this) {
                return cancelled;
            }
        }
    }
}
