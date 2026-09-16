package redxax.oxy.remotely.flow.ui;

import restudio.rescreen.platform.Async;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AsyncTaskWorker implements AutoCloseable {
    private final int coreParallelism;
    private final int maxParallelism;
    private final int capacity;
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private final Set<Async<Void>> active = new HashSet<>();
    private int running;
    private boolean closed;

    public AsyncTaskWorker(int coreParallelism, int maxParallelism, int capacity) {
        if (coreParallelism < 1) throw new IllegalArgumentException("Core parallelism must be positive");
        if (maxParallelism < coreParallelism) throw new IllegalArgumentException("Maximum parallelism must include the core workers");
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.coreParallelism = coreParallelism;
        this.maxParallelism = maxParallelism;
        this.capacity = capacity;
    }

    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        boolean start;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Worker is closed");
            start = running < coreParallelism;
            if (start) {
                running++;
            } else {
                if (pending.size() < capacity) {
                    pending.addLast(task);
                } else if (running < maxParallelism) {
                    running++;
                    start = true;
                } else {
                    throw new IllegalStateException("Worker queue is full");
                }
            }
        }
        if (start) start(task);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        List<Async<Void>> cancellations;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending.clear();
            cancellations = new ArrayList<>(active);
        }
        cancellations.forEach(Async::cancel);
    }

    private void start(Runnable task) {
        Async<Void> operation;
        try {
            operation = Async.supplyAsync(() -> {
                if (!isClosed()) task.run();
                return null;
            });
        } catch (RuntimeException | Error failure) {
            finish(null);
            throw failure;
        }
        boolean cancel;
        synchronized (this) {
            cancel = closed;
            if (!operation.isDone()) active.add(operation);
        }
        operation.whenComplete((ignored, failure) -> finish(operation));
        if (cancel && !operation.isDone()) operation.cancel();
    }

    private void finish(Async<Void> operation) {
        Runnable next = null;
        synchronized (this) {
            if (operation != null) active.remove(operation);
            if (running > 0) running--;
            if (!closed && !pending.isEmpty()) {
                running++;
                next = pending.removeFirst();
            }
        }
        if (next != null) start(next);
    }
}
