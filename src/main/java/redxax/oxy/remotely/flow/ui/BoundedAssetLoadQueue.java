package redxax.oxy.remotely.flow.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class BoundedAssetLoadQueue<K, V> implements AutoCloseable {
    enum Admission {
        ACCEPTED,
        DEDUPLICATED,
        SATURATED
    }

    @FunctionalInterface
    interface CompletionCallback<T> {
        void accept(T value, Runnable finish);
    }

    private static final int MAX_SUBSCRIBERS_PER_JOB = 16;
    private final Object lock = new Object();
    private final ArrayDeque<Job<K, V>> queue = new ArrayDeque<>();
    private final Map<K, Job<K, V>> activeJobs = new HashMap<>();
    private final int maxCount;
    private final long maxBytes;
    private final Thread worker;
    private int activeCount;
    private long activeBytes;
    private boolean closed;

    BoundedAssetLoadQueue(String threadName, int maxCount, long maxBytes) {
        if (maxCount < 1 || maxBytes < 1L) {
            throw new IllegalArgumentException("Asset queue limits must be positive");
        }
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.worker = new Thread(this::runWorker, Objects.requireNonNull(threadName));
        this.worker.setDaemon(true);
        this.worker.start();
    }

    Admission offer(K key, long bytes, Supplier<V> loader, CompletionCallback<V> success,
                    CompletionCallback<RuntimeException> failure) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(loader);
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        long retainedBytes = Math.max(1L, bytes);
        Delivery<K, V> immediate = null;
        Admission admission;
        synchronized (lock) {
            if (closed || retainedBytes > maxBytes) {
                return Admission.SATURATED;
            }
            Job<K, V> existing = activeJobs.get(key);
            if (existing != null) {
                if (existing.subscriberCount >= MAX_SUBSCRIBERS_PER_JOB) {
                    return Admission.SATURATED;
                }
                Subscriber<V> subscriber = new Subscriber<>(success, failure);
                existing.subscriberCount++;
                if (existing.completed && existing.workerComplete) {
                    immediate = prepareDeliveryLocked(existing, subscriber);
                } else {
                    existing.subscribers.add(subscriber);
                }
                admission = Admission.DEDUPLICATED;
            } else {
                if (activeCount >= maxCount || activeBytes > maxBytes - retainedBytes) {
                    return Admission.SATURATED;
                }
                Job<K, V> job = new Job<>(key, retainedBytes, loader);
                job.subscribers.add(new Subscriber<>(success, failure));
                job.subscriberCount = 1;
                activeJobs.put(key, job);
                activeCount++;
                activeBytes += retainedBytes;
                queue.addLast(job);
                lock.notifyAll();
                admission = Admission.ACCEPTED;
            }
        }
        if (immediate != null) {
            invoke(immediate);
        }
        return admission;
    }

    Admission offer(K key, long bytes, Supplier<V> loader, Consumer<V> success,
                    Consumer<RuntimeException> failure) {
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        return offer(key, bytes, loader, (value, finish) -> {
            try {
                success.accept(value);
            } finally {
                finish.run();
            }
        }, (error, finish) -> {
            try {
                failure.accept(error);
            } finally {
                finish.run();
            }
        });
    }

    boolean isActive(K key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            return activeJobs.containsKey(key);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
            for (Job<K, V> job : activeJobs.values()) {
                job.subscribers.clear();
            }
            activeJobs.clear();
            activeCount = 0;
            activeBytes = 0L;
            lock.notifyAll();
        }
        worker.interrupt();
    }

    private void runWorker() {
        while (true) {
            Job<K, V> job;
            synchronized (lock) {
                while (queue.isEmpty() && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException exception) {
                        if (closed) {
                            return;
                        }
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (queue.isEmpty()) {
                    return;
                }
                job = queue.removeFirst();
                if (closed || activeJobs.get(job.key) != job) {
                    continue;
                }
            }
            V value = null;
            RuntimeException failure = null;
            try {
                value = job.loader.get();
            } catch (Throwable throwable) {
                failure = asRuntimeException(throwable);
            }
            synchronized (lock) {
                if (closed || activeJobs.get(job.key) != job) {
                    continue;
                }
                job.value = value;
                job.failure = failure;
                job.completed = true;
            }
            try {
                deliver(job);
            } catch (Throwable ignored) {
                synchronized (lock) {
                    removeLocked(job);
                }
            } finally {
                finish(job);
            }
        }
    }

    private void deliver(Job<K, V> job) {
        while (true) {
            List<Delivery<K, V>> deliveries;
            synchronized (lock) {
                if (closed || activeJobs.get(job.key) != job) {
                    return;
                }
                if (job.subscribers.isEmpty()) {
                    return;
                }
                deliveries = new ArrayList<>(job.subscribers.size());
                for (Subscriber<V> subscriber : job.subscribers) {
                    deliveries.add(prepareDeliveryLocked(job, subscriber));
                }
                job.subscribers.clear();
            }
            for (Delivery<K, V> delivery : deliveries) {
                invoke(delivery);
            }
        }
    }

    private void invoke(Delivery<K, V> delivery) {
        try {
            if (delivery.job.failure == null) {
                delivery.subscriber.success.accept(delivery.job.value, delivery.completion);
            } else {
                delivery.subscriber.failure.accept(delivery.job.failure, delivery.completion);
            }
        } catch (Throwable ignored) {
            delivery.completion.run();
        }
    }

    private Delivery<K, V> prepareDeliveryLocked(Job<K, V> job, Subscriber<V> subscriber) {
        job.pendingDeliveries++;
        return new Delivery<>(job, subscriber, new Completion(job));
    }

    private void finish(Job<K, V> job) {
        boolean deliverPending;
        synchronized (lock) {
            if (activeJobs.get(job.key) != job) {
                return;
            }
            job.workerComplete = true;
            deliverPending = !job.subscribers.isEmpty();
            if (!deliverPending) {
                finishIfReadyLocked(job);
            }
        }
        if (deliverPending) {
            try {
                deliver(job);
            } catch (Throwable ignored) {
                synchronized (lock) {
                    removeLocked(job);
                }
            } finally {
                synchronized (lock) {
                    finishIfReadyLocked(job);
                }
            }
        }
    }

    private void finishIfReadyLocked(Job<K, V> job) {
        if (job.workerComplete && job.pendingDeliveries == 0 && job.subscribers.isEmpty()) {
            removeLocked(job);
        }
    }

    private void removeLocked(Job<K, V> job) {
        if (!activeJobs.remove(job.key, job)) {
            return;
        }
        job.subscribers.clear();
        activeCount--;
        activeBytes -= job.bytes;
        lock.notifyAll();
    }

    private RuntimeException asRuntimeException(Throwable throwable) {
        return throwable instanceof RuntimeException exception ? exception : new RuntimeException("Asset load failed", throwable);
    }

    private final class Completion implements Runnable {
        private final Job<K, V> job;
        private boolean finished;

        private Completion(Job<K, V> job) {
            this.job = job;
        }

        @Override
        public void run() {
            synchronized (lock) {
                if (finished) {
                    return;
                }
                finished = true;
                if (job.pendingDeliveries > 0) {
                    job.pendingDeliveries--;
                }
                finishIfReadyLocked(job);
            }
        }
    }

    private record Subscriber<V>(CompletionCallback<V> success, CompletionCallback<RuntimeException> failure) {
    }

    private static final class Job<K, V> {
        private final K key;
        private final long bytes;
        private final Supplier<V> loader;
        private final List<Subscriber<V>> subscribers = new ArrayList<>();
        private int subscriberCount;
        private int pendingDeliveries;
        private boolean completed;
        private boolean workerComplete;
        private V value;
        private RuntimeException failure;

        private Job(K key, long bytes, Supplier<V> loader) {
            this.key = key;
            this.bytes = bytes;
            this.loader = loader;
        }
    }

    private record Delivery<K, V>(Job<K, V> job, Subscriber<V> subscriber, Runnable completion) {
    }
}
