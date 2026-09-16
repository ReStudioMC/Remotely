package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAssetCacheTest {
    @Test
    void evictionAndCloseReleaseEachRetainedValueOnce() {
        AtomicInteger releases = new AtomicInteger();
        BoundedAssetCache<String, String> cache = new BoundedAssetCache<>(1, 2L, 60_000L,
            value -> releases.incrementAndGet());
        assertTrue(cache.put("first", "first", 1L));
        assertTrue(cache.put("second", "second", 1L));
        assertEquals(1, releases.get());
        cache.close();
        assertEquals(2, releases.get());
        assertFalse(cache.put("after-close", "after-close", 1L));
        assertEquals(3, releases.get());
        cache.close();
        assertEquals(3, releases.get());
    }
}
