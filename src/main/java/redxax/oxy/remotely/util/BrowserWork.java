package redxax.oxy.remotely.util;

import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class BrowserWork {
    private BrowserWork() {
    }

    public static void execute(Runnable task) {
        if (task == null) {
            return;
        }
        Async.supplyAsync(() -> {
            task.run();
            return null;
        });
    }

    public static TaskScheduler.ScheduledTask schedule(Duration delay, Runnable task) {
        if (task == null) {
            return TaskScheduler.direct().schedule(() -> {
            }, Duration.ZERO);
        }
        try {
            return TaskSchedulers.current().schedule(task, delay);
        } catch (RuntimeException ignored) {
            execute(task);
            return TaskScheduler.direct().schedule(() -> {
            }, Duration.ZERO);
        }
    }

    public static Executor executor() {
        return new Executor(false);
    }

    public static Executor serialExecutor() {
        return new Executor(true);
    }

    public static boolean failed(Async<?> async) {
        return async != null && async.isDone() && async.failure() != null;
    }

    public static final class Executor {
        private final boolean serial;
        private final Deque<Runnable> pending = new ArrayDeque<>();
        private boolean running;
        private boolean shutdown;
        private int active;

        private Executor(boolean serial) {
            this.serial = serial;
        }

        public void execute(Runnable task) {
            if (task == null) {
                return;
            }
            if (!serial) {
                BrowserWork.execute(task);
                return;
            }
            synchronized (this) {
                if (shutdown) {
                    throw new IllegalStateException("Executor is shut down");
                }
                pending.addLast(task);
                if (running) {
                    return;
                }
                running = true;
            }
            BrowserWork.execute(this::drain);
        }

        private void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    if (shutdown) {
                        running = false;
                        active = 0;
                        return;
                    }
                    next = pending.pollFirst();
                    if (next == null) {
                        running = false;
                        active = 0;
                        return;
                    }
                    active = 1;
                }
                try {
                    next.run();
                } catch (RuntimeException ignored) {
                }
            }
        }

        public Async<Void> submit(Runnable task) {
            Async<Void> result = Async.pending();
            execute(() -> {
                try {
                    if (task != null) {
                        task.run();
                    }
                    result.complete(null);
                } catch (Throwable failure) {
                    result.fail(failure);
                }
            });
            return result;
        }

        public void shutdown() {
            if (!serial) {
                return;
            }
            synchronized (this) {
                shutdown = true;
            }
        }

        public List<Runnable> shutdownNow() {
            if (!serial) {
                return List.of();
            }
            List<Runnable> discarded;
            synchronized (this) {
                shutdown = true;
                running = false;
                active = 0;
                discarded = new ArrayList<>(pending);
                pending.clear();
            }
            return discarded;
        }

        public boolean isShutdown() {
            if (!serial) {
                return false;
            }
            synchronized (this) {
                return shutdown;
            }
        }

        public boolean isTerminated() {
            return false;
        }

        public void setRemoveOnCancelPolicy(boolean value) {
        }

        public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        }

        public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        }

        public void purge() {
        }

        public List<Runnable> getQueue() {
            if (!serial) {
                return List.of();
            }
            synchronized (this) {
                return List.copyOf(pending);
            }
        }

        public int getActiveCount() {
            if (!serial) {
                return 0;
            }
            synchronized (this) {
                return active;
            }
        }

        public TaskScheduler.ScheduledTask schedule(Runnable command, Duration delay) {
            return BrowserWork.schedule(delay, command);
        }

        public TaskScheduler.ScheduledTask schedule(Runnable command, long delay, Object ignoredUnit) {
            return schedule(command, Duration.ofSeconds(Math.max(0L, delay)));
        }

        public TaskScheduler.ScheduledTask scheduleAtFixedRate(Runnable command, Duration initial, Duration period) {
            try {
                return TaskSchedulers.current().scheduleAtFixedRate(command, initial, period);
            } catch (RuntimeException ignored) {
                execute(command);
                return TaskScheduler.direct().scheduleAtFixedRate(command, initial, period);
            }
        }

        public TaskScheduler.ScheduledTask scheduleAtFixedRate(Runnable command, long initial, Duration period) {
            return scheduleAtFixedRate(command, Duration.ofSeconds(Math.max(0L, initial)), period);
        }

        public TaskScheduler.ScheduledTask scheduleAtFixedRate(Runnable command, long initial, long period, Object ignoredUnit) {
            return scheduleAtFixedRate(command, Duration.ofSeconds(Math.max(0L, initial)), Duration.ofSeconds(Math.max(1L, period)));
        }

        public boolean awaitTermination(long timeout, Object ignoredUnit) {
            return true;
        }

        public boolean awaitTermination(long timeoutMillis) {
            return true;
        }
    }
}
