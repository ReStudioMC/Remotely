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
            if (delay == null || delay.isZero() || delay.isNegative()) {
                execute(task);
            }
            return TaskScheduler.direct().schedule(() -> {
            }, Duration.ZERO);
        }
    }

    public static Executor executor() {
        return new Executor(false, null);
    }

    public static Executor executor(String namePrefix) {
        return new Executor(false, namePrefix);
    }

    public static Executor serialExecutor() {
        return new Executor(true, null);
    }

    public static Executor serialExecutor(String namePrefix) {
        return new Executor(true, namePrefix);
    }

    public static Executor serialYieldingExecutor() {
        return new Executor(true, null, true);
    }

    public static boolean failed(Async<?> async) {
        return async != null && async.isDone() && async.failure() != null;
    }

    public static final class Executor {
        private final boolean serial;
        private final String namePrefix;
        private final boolean yieldBetweenTasks;
        private final Deque<Runnable> pending = new ArrayDeque<>();
        private int counter;
        private boolean running;
        private boolean shutdown;
        private int active;

        private Executor(boolean serial, String namePrefix) {
            this(serial, namePrefix, false);
        }

        private Executor(boolean serial, String namePrefix, boolean yieldBetweenTasks) {
            this.serial = serial;
            this.namePrefix = namePrefix;
            this.yieldBetweenTasks = yieldBetweenTasks;
        }

        private Runnable wrapTask(Runnable task) {
            if (namePrefix == null) {
                return task;
            }
            return () -> {
                String original = TaskIdentities.access.name();
                TaskIdentities.access.setName(namePrefix + nextCounter());
                try {
                    task.run();
                } finally {
                    TaskIdentities.access.setName(original);
                }
            };
        }

        private synchronized int nextCounter() {
            counter++;
            return counter;
        }

        public void execute(Runnable task) {
            if (task == null) {
                return;
            }
            Runnable wrapped = wrapTask(task);
            if (!serial) {
                synchronized (this) {
                    if (shutdown) {
                        throw new IllegalStateException("Executor is shut down");
                    }
                    active++;
                }
                BrowserWork.execute(() -> {
                    try {
                        wrapped.run();
                    } finally {
                        synchronized (this) {
                            active--;
                            if (shutdown && active == 0) {
                                notifyAll();
                            }
                        }
                    }
                });
                return;
            }
            synchronized (this) {
                if (shutdown) {
                    throw new IllegalStateException("Executor is shut down");
                }
                pending.addLast(wrapped);
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
                        notifyAll();
                        return;
                    }
                    next = pending.pollFirst();
                    if (next == null) {
                        running = false;
                        active = 0;
                        notifyAll();
                        return;
                    }
                    active = 1;
                }
                try {
                    next.run();
                } catch (RuntimeException ignored) {
                }
                if (yieldBetweenTasks) {
                    synchronized (this) {
                        if (shutdown || pending.isEmpty()) {
                            running = false;
                            active = 0;
                            notifyAll();
                            return;
                        }
                        active = 0;
                    }
                    try {
                        BrowserWork.execute(this::drain);
                        return;
                    } catch (RuntimeException ignored) {
                    }
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
            synchronized (this) {
                shutdown = true;
                if (!serial) {
                    if (active == 0) {
                        notifyAll();
                    }
                    return;
                }
                if (!running && pending.isEmpty()) {
                    notifyAll();
                }
            }
        }

        public List<Runnable> shutdownNow() {
            List<Runnable> discarded;
            synchronized (this) {
                shutdown = true;
                if (!serial) {
                    discarded = List.of();
                    if (active == 0) {
                        notifyAll();
                    }
                    return discarded;
                }
                running = false;
                active = 0;
                discarded = new ArrayList<>(pending);
                pending.clear();
                notifyAll();
            }
            return discarded;
        }

        public boolean isShutdown() {
            synchronized (this) {
                return shutdown;
            }
        }

        public boolean isTerminated() {
            synchronized (this) {
                if (!serial) {
                    return shutdown && active == 0;
                }
                return shutdown && !running && pending.isEmpty();
            }
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
                if (initial == null || initial.isZero() || initial.isNegative()) {
                    execute(command);
                }
                return TaskScheduler.direct().schedule(() -> {
                }, Duration.ZERO);
            }
        }

        public TaskScheduler.ScheduledTask scheduleAtFixedRate(Runnable command, long initial, Duration period) {
            return scheduleAtFixedRate(command, Duration.ofSeconds(Math.max(0L, initial)), period);
        }

        public TaskScheduler.ScheduledTask scheduleAtFixedRate(Runnable command, long initial, long period, Object ignoredUnit) {
            return scheduleAtFixedRate(command, Duration.ofSeconds(Math.max(0L, initial)), Duration.ofSeconds(Math.max(1L, period)));
        }

        public boolean awaitTermination(long timeout, Object unit) {
            long millis = timeout;
            if (unit != null) {
                String unitName = unit.toString();
                if ("NANOSECONDS".equals(unitName)) {
                    millis = timeout / 1_000_000L;
                } else if ("MICROSECONDS".equals(unitName)) {
                    millis = timeout / 1_000L;
                } else if ("MILLISECONDS".equals(unitName)) {
                    millis = timeout;
                } else if ("SECONDS".equals(unitName)) {
                    millis = timeout * 1000L;
                } else if ("MINUTES".equals(unitName)) {
                    millis = timeout * 60_000L;
                } else if ("HOURS".equals(unitName)) {
                    millis = timeout * 3_600_000L;
                } else if ("DAYS".equals(unitName)) {
                    millis = timeout * 86_400_000L;
                } else if (timeout > 1_000_000L) {
                    millis = timeout / 1_000_000L;
                }
            } else if (timeout > 1_000_000L) {
                millis = timeout / 1_000_000L;
            }
            return awaitTermination(millis);
        }

        public boolean awaitTermination(long timeoutMillis) {
            long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
            synchronized (this) {
                while (!isTerminated()) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0L) {
                        return isTerminated();
                    }
                    try {
                        wait(Math.min(remaining, 50L));
                    } catch (InterruptedException ignored) {
                        return isTerminated();
                    }
                }
                return true;
            }
        }
    }
}
