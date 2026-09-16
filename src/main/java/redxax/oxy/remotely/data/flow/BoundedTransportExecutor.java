package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.TaskIdentities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class BoundedTransportExecutor {
    enum Priority {
        REPLACEABLE,
        STANDARD,
        CRITICAL,
        LOSSLESS,
        TERMINAL
    }

    private final Object lock = new Object();
    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private final Map<String, Work> replaceable = new HashMap<>();
    private final int maxCount;
    private final long maxBytes;
    private final int terminalReserveCount;
    private final long terminalReserveBytes;
    private final IntSupplier generation;
    private long queuedBytes;
    private boolean shutdown;
    private boolean terminated;
    private boolean draining;

    BoundedTransportExecutor(String threadName, int maxCount, long maxBytes, IntSupplier generation) {
        if (maxCount < 1 || maxBytes < 1L) {
            throw new IllegalArgumentException("Transport queue limits must be positive");
        }
        Objects.requireNonNull(threadName, "Thread name is required");
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.terminalReserveCount = maxCount >= 8 ? Math.max(1, maxCount / 8) : 0;
        this.terminalReserveBytes = maxBytes >= 1024L ? Math.max(1L, maxBytes / 8L) : 0L;
        this.generation = Objects.requireNonNull(generation, "Generation supplier is required");
    }

    boolean offer(int expectedGeneration, long bytes, Priority priority, String coalesceKey, Runnable task) {
        return offer(expectedGeneration, bytes, priority, coalesceKey, () -> task);
    }

    boolean offer(int expectedGeneration, long bytes, Priority priority, String coalesceKey,
                  Supplier<Runnable> taskFactory) {
        Objects.requireNonNull(priority, "Transport work priority is required");
        Objects.requireNonNull(taskFactory, "Transport work factory is required");
        long retainedBytes = Math.max(1L, bytes);
        if (retainedBytes > maxBytes) {
            return false;
        }
        boolean interrupted = false;
        try {
            synchronized (lock) {
                if (shutdown) {
                    return false;
                }
                while (true) {
                    List<Work> removals = plannedRemovals(priority, coalesceKey, retainedBytes);
                    if (removals == null) {
                        if (priority == Priority.LOSSLESS || priority == Priority.TERMINAL) {
                            try {
                                lock.wait();
                            } catch (InterruptedException exception) {
                                interrupted = true;
                            }
                            if (shutdown) {
                                return false;
                            }
                            continue;
                        }
                        return false;
                    }
                    Runnable task = Objects.requireNonNull(taskFactory.get(), "Transport work is required");
                    for (Work removal : removals) {
                        remove(removal);
                    }
                    add(new Work(expectedGeneration, retainedBytes, priority, coalesceKey, task));
                    lock.notifyAll();
                    scheduleDrainLocked();
                    return true;
                }
            }
        } finally {
            if (interrupted) {
                TaskIdentities.access.interrupt();
            }
        }
    }

    int queuedCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    long queuedBytes() {
        synchronized (lock) {
            return queuedBytes;
        }
    }

    public void execute(Runnable command) {
        if (!offer(generation.getAsInt(), 1L, Priority.STANDARD, null, command)) {
            throw new IllegalStateException("Transport queue is full");
        }
    }

    public void shutdown() {
        synchronized (lock) {
            shutdown = true;
            lock.notifyAll();
            scheduleDrainLocked();
        }
    }

    public List<Runnable> shutdownNow() {
        synchronized (lock) {
            shutdown = true;
            List<Runnable> pending = queue.stream().map(Work::task).toList();
            queue.clear();
            replaceable.clear();
            queuedBytes = 0L;
            lock.notifyAll();
            scheduleDrainLocked();
            return new ArrayList<>(pending);
        }
    }

    public boolean isShutdown() {
        synchronized (lock) {
            return shutdown;
        }
    }

    public boolean isTerminated() {
        synchronized (lock) {
            return terminated;
        }
    }

    public boolean awaitTermination(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (lock) {
            while (!terminated) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                lock.wait(remaining);
            }
            return true;
        }
    }

    private void scheduleDrainLocked() {
        if (draining || terminated) {
            return;
        }
        draining = true;
        BrowserWork.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Work work;
            synchronized (lock) {
                if (queue.isEmpty()) {
                    draining = false;
                    if (shutdown) {
                        terminated = true;
                    }
                    lock.notifyAll();
                    return;
                }
                work = queue.removeFirst();
                queuedBytes -= work.bytes();
                if (work.coalesceKey() != null) {
                    replaceable.remove(work.coalesceKey(), work);
                }
                lock.notifyAll();
            }
            if (work.expectedGeneration() >= 0 && work.expectedGeneration() != generation.getAsInt()) {
                continue;
            }
            try {
                work.task().run();
            } catch (RuntimeException | Error ignored) {
            }
        }
    }

    private boolean fits(int count, long bytes, Priority priority) {
        int countLimit = priority == Priority.TERMINAL ? maxCount : maxCount - terminalReserveCount;
        long byteLimit = priority == Priority.TERMINAL ? maxBytes : maxBytes - terminalReserveBytes;
        return count < countLimit && bytes <= byteLimit;
    }

    private List<Work> plannedRemovals(Priority priority, String coalesceKey, long incomingBytes) {
        List<Work> removals = new ArrayList<>();
        Work replaced = priority == Priority.REPLACEABLE && coalesceKey != null ? replaceable.get(coalesceKey) : null;
        int projectedCount = queue.size();
        long projectedBytes = queuedBytes + incomingBytes;
        if (replaced != null) {
            removals.add(replaced);
            projectedCount--;
            projectedBytes -= replaced.bytes();
        }
        if (fits(projectedCount, projectedBytes, priority)) {
            return removals;
        }
        for (Work work : queue) {
            if (work != replaced && work.priority() == Priority.REPLACEABLE) {
                removals.add(work);
                projectedCount--;
                projectedBytes -= work.bytes();
                if (fits(projectedCount, projectedBytes, priority)) {
                    return removals;
                }
            }
        }
        return null;
    }

    private void add(Work work) {
        queue.addLast(work);
        queuedBytes += work.bytes();
        if (work.priority() == Priority.REPLACEABLE && work.coalesceKey() != null) {
            replaceable.put(work.coalesceKey(), work);
        }
    }

    private void remove(Work work) {
        if (!queue.remove(work)) {
            return;
        }
        queuedBytes -= work.bytes();
        if (work.coalesceKey() != null) {
            replaceable.remove(work.coalesceKey(), work);
        }
    }

    private record Work(int expectedGeneration, long bytes, Priority priority, String coalesceKey, Runnable task) {
    }
}
