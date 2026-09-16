package redxax.oxy.remotely.flow.ui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class BoundedAssetCache<K, V> implements AutoCloseable {
    private final Object lock = new Object();
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxEntries;
    private final long maxBytes;
    private final long ttlNanos;
    private final Consumer<V> releaser;
    private long retainedBytes;
    private boolean closed;

    BoundedAssetCache(int maxEntries, long maxBytes, long ttlMillis, Consumer<V> releaser) {
        if (maxEntries < 1 || maxBytes < 1L || ttlMillis < 1L) {
            throw new IllegalArgumentException("Asset cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.ttlNanos = Math.multiplyExact(ttlMillis, 1_000_000L);
        this.releaser = Objects.requireNonNull(releaser);
    }

    V get(K key) {
        if (key == null) {
            return null;
        }
        V expired = null;
        V value;
        synchronized (lock) {
            if (closed) {
                return null;
            }
            Entry<V> entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            long now = System.nanoTime();
            if (entry.expiresAtNanos() <= now) {
                entries.remove(key);
                retainedBytes -= entry.bytes();
                expired = entry.value();
                value = null;
            } else {
                value = entry.value();
            }
        }
        release(expired);
        return value;
    }

    boolean put(K key, V value, long bytes) {
        if (key == null || value == null) {
            return false;
        }
        long retained = Math.max(1L, bytes);
        List<V> releases = new ArrayList<>();
        boolean retainedInCache;
        synchronized (lock) {
            if (closed) {
                retainedInCache = false;
                releases.add(value);
            } else {
                Entry<V> previous = entries.remove(key);
                if (previous != null) {
                    retainedBytes -= previous.bytes();
                    if (previous.value() != value) {
                        releases.add(previous.value());
                    }
                }
                if (retained > maxBytes) {
                    releases.add(value);
                    retainedInCache = false;
                } else {
                    entries.put(key, new Entry<>(value, retained, System.nanoTime() + ttlNanos));
                    retainedBytes += retained;
                    trim(releases);
                    retainedInCache = entries.containsKey(key);
                }
            }
        }
        releaseAll(releases);
        if (retainedInCache) {
            synchronized (lock) {
                Entry<V> retainedEntry = entries.get(key);
                retainedInCache = !closed && retainedEntry != null && retainedEntry.value() == value;
            }
        }
        return retainedInCache;
    }

    void remove(K key) {
        if (key == null) {
            return;
        }
        V removed = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            Entry<V> entry = entries.remove(key);
            if (entry == null) {
                return;
            }
            retainedBytes -= entry.bytes();
            removed = entry.value();
        }
        release(removed);
    }

    @Override
    public void close() {
        List<V> releases;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            releases = entries.values().stream().map(Entry::value).toList();
            entries.clear();
            retainedBytes = 0L;
        }
        releaseAll(releases);
    }

    private void trim(List<V> releases) {
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || retainedBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<K, Entry<V>> eldest = iterator.next();
            iterator.remove();
            retainedBytes -= eldest.getValue().bytes();
            releases.add(eldest.getValue().value());
        }
    }

    private void releaseAll(List<V> values) {
        for (V value : values) {
            release(value);
        }
    }

    private void release(V value) {
        if (value == null) {
            return;
        }
        try {
            releaser.accept(value);
        } catch (Throwable ignored) {
        }
    }

    private record Entry<V>(V value, long bytes, long expiresAtNanos) {
    }
}
