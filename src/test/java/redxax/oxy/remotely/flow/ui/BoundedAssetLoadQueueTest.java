package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAssetLoadQueueTest {
    @Test
    void successfulCompletionReleasesCapacityAndAllowsSameKeyAgain() throws Exception {
        BoundedAssetLoadQueue<String, String> queue = new BoundedAssetLoadQueue<>("asset-queue-test", 1, 8L);
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicInteger loads = new AtomicInteger();
            assertEquals(BoundedAssetLoadQueue.Admission.ACCEPTED, queue.offer("same", 1L, () -> {
                started.countDown();
                await(release);
                return "first";
            }, (value, finish) -> {
                loads.incrementAndGet();
                finish.run();
                finish.run();
                delivered.countDown();
            }, (error, finish) -> finish.run()));
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(delivered.await(2L, TimeUnit.SECONDS));
            CountDownLatch secondDelivered = new CountDownLatch(1);
            assertEquals(BoundedAssetLoadQueue.Admission.ACCEPTED, queue.offer("same", 1L, () -> {
                loads.incrementAndGet();
                return "second";
            }, (value, finish) -> {
                finish.run();
                secondDelivered.countDown();
            }, (error, finish) -> finish.run()));
            assertTrue(secondDelivered.await(2L, TimeUnit.SECONDS));
            assertEquals(2, loads.get());
        } finally {
            queue.close();
        }
    }

    @Test
    void deduplicatedSubscribersReceiveTheSameCompletion() throws Exception {
        BoundedAssetLoadQueue<String, String> queue = new BoundedAssetLoadQueue<>("asset-queue-dedup-test", 1, 8L);
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(2);
            assertEquals(BoundedAssetLoadQueue.Admission.ACCEPTED, queue.offer("same", 1L, () -> {
                started.countDown();
                await(release);
                return "value";
            }, (value, finish) -> {
                finish.run();
                delivered.countDown();
            }, (error, finish) -> finish.run()));
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            assertEquals(BoundedAssetLoadQueue.Admission.DEDUPLICATED, queue.offer("same", 1L, () -> "unused",
                (value, finish) -> {
                    finish.run();
                    delivered.countDown();
                }, (error, finish) -> finish.run()));
            release.countDown();
            assertTrue(delivered.await(2L, TimeUnit.SECONDS));
        } finally {
            queue.close();
        }
    }

    @Test
    void failedLoadReleasesCapacityAndNotifiesFailure() throws Exception {
        BoundedAssetLoadQueue<String, String> queue = new BoundedAssetLoadQueue<>("asset-queue-failure-test", 1, 8L);
        try {
            CountDownLatch failed = new CountDownLatch(1);
            assertEquals(BoundedAssetLoadQueue.Admission.ACCEPTED, queue.offer("same", 1L, () -> {
                throw new IllegalStateException("decode");
            }, (value, finish) -> finish.run(), (error, finish) -> {
                finish.run();
                failed.countDown();
            }));
            assertTrue(failed.await(2L, TimeUnit.SECONDS));
            CountDownLatch retried = new CountDownLatch(1);
            assertEquals(BoundedAssetLoadQueue.Admission.ACCEPTED, queue.offer("same", 1L, () -> "again",
                (value, finish) -> {
                    finish.run();
                    retried.countDown();
                }, (error, finish) -> finish.run()));
            assertTrue(retried.await(2L, TimeUnit.SECONDS));
        } finally {
            queue.close();
        }
    }

    @Test
    void callbackErrorCannotPinAnActiveJob() throws Exception {
        BoundedAssetLoadQueue<String, String> queue = new BoundedAssetLoadQueue<>("asset-queue-error-test", 1, 8L);
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            assertEquals(BoundedAssetLoadQueue.Admission.ACCEPTED, queue.offer("same", 1L, () -> "value",
                (value, finish) -> {
                    delivered.countDown();
                    throw new AssertionError("callback");
                }, (error, finish) -> finish.run()));
            assertTrue(delivered.await(2L, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + Duration.ofSeconds(2L).toNanos();
            BoundedAssetLoadQueue.Admission admission;
            do {
                admission = queue.offer("same", 1L, () -> "again", (value, finish) -> finish.run(),
                    (error, finish) -> finish.run());
                if (admission != BoundedAssetLoadQueue.Admission.ACCEPTED) {
                    Thread.yield();
                }
            } while (admission != BoundedAssetLoadQueue.Admission.ACCEPTED && System.nanoTime() < deadline);
            assertEquals(BoundedAssetLoadQueue.Admission.ACCEPTED, admission);
        } finally {
            queue.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
