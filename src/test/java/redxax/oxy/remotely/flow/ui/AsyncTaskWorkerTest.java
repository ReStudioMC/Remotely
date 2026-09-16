package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskWorkerTest {
    @Test
    void boundedWorkerSchedulesInOrderAndRejectsOverflow() {
        Async.Snapshot platform = Async.snapshot();
        Deque<Runnable> scheduled = new ArrayDeque<>();
        List<Integer> completed = new ArrayList<>();
        try {
            Async.installExecutor(scheduled::addLast, ignored -> {
            });
            AsyncTaskWorker worker = new AsyncTaskWorker(1, 1, 2);

            worker.execute(() -> completed.add(1));
            worker.execute(() -> completed.add(2));
            worker.execute(() -> completed.add(3));

            assertEquals(1, scheduled.size());
            assertThrows(RejectedExecutionException.class, () -> worker.execute(() -> completed.add(4)));
            while (!scheduled.isEmpty()) scheduled.removeFirst().run();
            assertEquals(List.of(1, 2, 3), completed);
        } finally {
            Async.restore(platform);
        }
    }

    @Test
    void closeCancelsScheduledAndQueuedTasksAndRejectsNewWork() {
        Async.Snapshot platform = Async.snapshot();
        Deque<Runnable> scheduled = new ArrayDeque<>();
        List<Integer> completed = new ArrayList<>();
        try {
            Async.installExecutor(scheduled::addLast, ignored -> {
            });
            AsyncTaskWorker worker = new AsyncTaskWorker(1, 1, 2);
            worker.execute(() -> completed.add(1));
            worker.execute(() -> completed.add(2));

            worker.close();
            while (!scheduled.isEmpty()) scheduled.removeFirst().run();

            assertTrue(worker.isClosed());
            assertTrue(completed.isEmpty());
            assertThrows(RejectedExecutionException.class, () -> worker.execute(() -> completed.add(3)));
        } finally {
            Async.restore(platform);
        }
    }
}
