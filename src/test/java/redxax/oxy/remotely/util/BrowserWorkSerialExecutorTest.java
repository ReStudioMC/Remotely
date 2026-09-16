package redxax.oxy.remotely.util;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserWorkSerialExecutorTest {
    @Test
    void serialExecutorPreservesSubmissionOrderOnAParallelAsyncPool() throws Exception {
        Async.Snapshot platform = Async.snapshot();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        BrowserWork.Executor worker = BrowserWork.serialExecutor();
        List<Integer> completed = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(8);
        AtomicInteger overlapping = new AtomicInteger();
        try {
            Async.installExecutor(pool::execute, ignored -> Thread.currentThread().interrupt());
            worker.execute(() -> {
                started.countDown();
                record(completed, overlapping, 0);
                finished.countDown();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int index = 1; index <= 7; index++) {
                int value = index;
                worker.execute(() -> {
                    record(completed, overlapping, value);
                    finished.countDown();
                });
            }
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), completed);
            assertEquals(0, overlapping.get());
        } finally {
            worker.shutdownNow();
            Async.restore(platform);
            pool.shutdownNow();
        }
    }

    private static void record(List<Integer> completed, AtomicInteger overlapping, int value) {
        if (overlapping.getAndIncrement() != 0) {
            throw new IllegalStateException("Serial executor ran overlapping work");
        }
        try {
            Thread.sleep(5L);
            synchronized (completed) {
                completed.add(value);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            overlapping.decrementAndGet();
        }
    }
}
