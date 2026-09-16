package redxax.oxy.remotely.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;
import java.util.function.UnaryOperator;
public final class BrowserSafeState {
    private BrowserSafeState() {
    }

    public static <K, V> Map<K, V> map() {
        return Collections.synchronizedMap(new HashMap<>());
    }

    public static <E> Set<E> set() {
        return Collections.synchronizedSet(new HashSet<>());
    }

    public static <E> List<E> list() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    public static <E> Deque<E> deque() {
        return new SynchronizedDeque<>();
    }

    public static <E> Queue<E> queue() {
        return new SynchronizedDeque<>();
    }

    public static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    public static long nanosToMicros(long nanos) {
        return nanos / 1_000L;
    }

    public static final class BooleanValue {
        private boolean value;

        public BooleanValue() {
        }

        public BooleanValue(boolean value) {
            this.value = value;
        }

        public synchronized boolean get() {
            return value;
        }

        public synchronized void set(boolean value) {
            this.value = value;
        }

        public synchronized boolean getAndSet(boolean value) {
            boolean previous = this.value;
            this.value = value;
            return previous;
        }

        public synchronized boolean compareAndSet(boolean expected, boolean value) {
            if (this.value != expected) return false;
            this.value = value;
            return true;
        }
    }

    public static final class IntegerValue {
        private int value;

        public IntegerValue() {
        }

        public IntegerValue(int value) {
            this.value = value;
        }

        public synchronized int get() {
            return value;
        }

        public synchronized void set(int value) {
            this.value = value;
        }

        public synchronized int incrementAndGet() {
            return ++value;
        }

        public synchronized int getAndIncrement() {
            return value++;
        }

        public synchronized int decrementAndGet() {
            return --value;
        }

        public synchronized int getAndSet(int value) {
            int previous = this.value;
            this.value = value;
            return previous;
        }

        public synchronized boolean compareAndSet(int expected, int value) {
            if (this.value != expected) return false;
            this.value = value;
            return true;
        }

        public synchronized int addAndGet(int delta) {
            value += delta;
            return value;
        }

        public synchronized int updateAndGet(IntUnaryOperator updater) {
            value = updater.applyAsInt(value);
            return value;
        }

        public synchronized int accumulateAndGet(int given, IntBinaryOperator accumulator) {
            value = accumulator.applyAsInt(value, given);
            return value;
        }
    }

    public static final class LongValue {
        private long value;

        public LongValue() {
        }

        public LongValue(long value) {
            this.value = value;
        }

        public synchronized long get() {
            return value;
        }

        public synchronized void set(long value) {
            this.value = value;
        }

        public synchronized long incrementAndGet() {
            return ++value;
        }

        public synchronized long getAndIncrement() {
            return value++;
        }

        public synchronized long decrementAndGet() {
            return --value;
        }

        public synchronized long getAndSet(long value) {
            long previous = this.value;
            this.value = value;
            return previous;
        }

        public synchronized boolean compareAndSet(long expected, long value) {
            if (this.value != expected) return false;
            this.value = value;
            return true;
        }

        public synchronized long addAndGet(long delta) {
            value += delta;
            return value;
        }

        public synchronized long updateAndGet(LongUnaryOperator updater) {
            value = updater.applyAsLong(value);
            return value;
        }

        public synchronized long accumulateAndGet(long given, LongBinaryOperator accumulator) {
            value = accumulator.applyAsLong(value, given);
            return value;
        }
    }

    public static final class ReferenceValue<T> {
        private T value;

        public ReferenceValue() {
        }

        public ReferenceValue(T value) {
            this.value = value;
        }

        public synchronized T get() {
            return value;
        }

        public synchronized void set(T value) {
            this.value = value;
        }

        public synchronized T getAndSet(T value) {
            T previous = this.value;
            this.value = value;
            return previous;
        }

        public synchronized boolean compareAndSet(T expected, T value) {
            if (this.value != expected) return false;
            this.value = value;
            return true;
        }

        public synchronized T updateAndGet(UnaryOperator<T> updater) {
            value = updater.apply(value);
            return value;
        }

        public synchronized T accumulateAndGet(T given, BinaryOperator<T> accumulator) {
            value = accumulator.apply(value, given);
            return value;
        }
    }

    public static final class Lock {
        private Object owner;
        private int depth;

        public synchronized void lock() {
            Object current = TaskIdentities.access.current();
            while (owner != null && owner != current) {
                try {
                    wait();
                } catch (InterruptedException error) {
                    TaskIdentities.access.interrupt();
                    throw new IllegalStateException("Lock wait interrupted", error);
                }
            }
            owner = current;
            depth++;
        }

        public synchronized boolean tryLock() {
            Object current = TaskIdentities.access.current();
            if (owner != null && owner != current) {
                return false;
            }
            owner = current;
            depth++;
            return true;
        }

        public synchronized boolean isHeldByCurrentThread() {
            return owner == TaskIdentities.access.current();
        }

        public synchronized void unlock() {
            if (owner != TaskIdentities.access.current()) {
                throw new IllegalStateException("Unlock by non-owner");
            }
            depth--;
            if (depth == 0) {
                owner = null;
                notifyAll();
            }
        }

        public synchronized void signalAll() {
            notifyAll();
        }
    }

    public static final class Latch {
        private int remaining;

        public Latch(int count) {
            remaining = Math.max(0, count);
        }

        public synchronized void countDown() {
            if (remaining > 0) {
                remaining--;
            }
            if (remaining == 0) {
                notifyAll();
            }
        }

        public synchronized long getCount() {
            return remaining;
        }

        public synchronized boolean await(long nanos) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, nanos);
            while (remaining > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0L) {
                    return false;
                }
                wait(Math.max(1L, left / 1_000_000L));
            }
            return true;
        }

        public synchronized boolean awaitMillis(long millis) throws InterruptedException {
            return await(Math.max(0L, millis) * 1_000_000L);
        }
    }

    private static final class SynchronizedDeque<E> implements Deque<E> {
        private final Deque<E> delegate = new ArrayDeque<>();

        @Override
        public synchronized void addFirst(E value) {
            delegate.addFirst(value);
        }

        @Override
        public synchronized void addLast(E value) {
            delegate.addLast(value);
        }

        @Override
        public synchronized boolean offerFirst(E value) {
            return delegate.offerFirst(value);
        }

        @Override
        public synchronized boolean offerLast(E value) {
            return delegate.offerLast(value);
        }

        @Override
        public synchronized E removeFirst() {
            return delegate.removeFirst();
        }

        @Override
        public synchronized E removeLast() {
            return delegate.removeLast();
        }

        @Override
        public synchronized E pollFirst() {
            return delegate.pollFirst();
        }

        @Override
        public synchronized E pollLast() {
            return delegate.pollLast();
        }

        @Override
        public synchronized E getFirst() {
            return delegate.getFirst();
        }

        @Override
        public synchronized E getLast() {
            return delegate.getLast();
        }

        @Override
        public synchronized E peekFirst() {
            return delegate.peekFirst();
        }

        @Override
        public synchronized E peekLast() {
            return delegate.peekLast();
        }

        @Override
        public synchronized boolean removeFirstOccurrence(Object value) {
            return delegate.removeFirstOccurrence(value);
        }

        @Override
        public synchronized boolean removeLastOccurrence(Object value) {
            return delegate.removeLastOccurrence(value);
        }

        @Override
        public synchronized boolean add(E value) {
            return delegate.add(value);
        }

        @Override
        public synchronized boolean offer(E value) {
            return delegate.offer(value);
        }

        @Override
        public synchronized E remove() {
            return delegate.remove();
        }

        @Override
        public synchronized E poll() {
            return delegate.poll();
        }

        @Override
        public synchronized E element() {
            return delegate.element();
        }

        @Override
        public synchronized E peek() {
            return delegate.peek();
        }

        @Override
        public synchronized void push(E value) {
            delegate.push(value);
        }

        @Override
        public synchronized E pop() {
            return delegate.pop();
        }

        @Override
        public synchronized boolean remove(Object value) {
            return delegate.remove(value);
        }

        @Override
        public synchronized boolean contains(Object value) {
            return delegate.contains(value);
        }

        @Override
        public synchronized int size() {
            return delegate.size();
        }

        @Override
        public synchronized boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public synchronized Iterator<E> iterator() {
            return new ArrayList<>(delegate).iterator();
        }

        @Override
        public synchronized Iterator<E> descendingIterator() {
            return new ArrayList<>(delegate).reversed().iterator();
        }

        @Override
        public synchronized Object[] toArray() {
            return delegate.toArray();
        }

        @Override
        public synchronized <T> T[] toArray(T[] array) {
            return delegate.toArray(array);
        }

        @Override
        public synchronized boolean containsAll(Collection<?> values) {
            return delegate.containsAll(values);
        }

        @Override
        public synchronized boolean addAll(Collection<? extends E> values) {
            return delegate.addAll(values);
        }

        @Override
        public synchronized boolean removeAll(Collection<?> values) {
            return delegate.removeAll(values);
        }

        @Override
        public synchronized boolean retainAll(Collection<?> values) {
            return delegate.retainAll(values);
        }

        @Override
        public synchronized void clear() {
            delegate.clear();
        }

        @Override
        public synchronized boolean equals(Object value) {
            return delegate.equals(value);
        }

        @Override
        public synchronized int hashCode() {
            return delegate.hashCode();
        }
    }
}
