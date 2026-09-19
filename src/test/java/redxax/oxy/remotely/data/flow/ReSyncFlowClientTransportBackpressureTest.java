package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientTransportBackpressureTest {
    @Test
    void saturatedQueueCoalescesReplaceableWorkBeforeAdmittingCriticalWork() throws Exception {
        AtomicInteger generation = new AtomicInteger(1);
        BoundedTransportExecutor executor = new BoundedTransportExecutor("ReSync-Backpressure-Saturation", 2, 8L,
            generation::get);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        List<String> order = new ArrayList<>();
        try {
            assertTrue(executor.offer(-1, 1L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                entered.countDown();
                await(release);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(executor.offer(1, 4L, BoundedTransportExecutor.Priority.REPLACEABLE, "awareness", () -> {
                order.add("old");
                delivered.countDown();
            }));
            assertTrue(executor.offer(1, 4L, BoundedTransportExecutor.Priority.REPLACEABLE, "awareness", () -> {
                order.add("latest");
                delivered.countDown();
            }));
            assertTrue(executor.offer(1, 4L, BoundedTransportExecutor.Priority.STANDARD, null, () -> {
                order.add("standard");
                delivered.countDown();
            }));
            assertEquals(2, executor.queuedCount());
            assertEquals(8L, executor.queuedBytes());
            assertTrue(executor.offer(1, 4L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                order.add("critical");
                delivered.countDown();
            }));
            assertEquals(2, executor.queuedCount());
            assertEquals(8L, executor.queuedBytes());
            AtomicInteger materialized = new AtomicInteger();
            assertFalse(executor.offer(1, 1L, BoundedTransportExecutor.Priority.STANDARD, null, () -> {
                materialized.incrementAndGet();
                return () -> {
                };
            }));
            assertEquals(0, materialized.get());

            release.countDown();

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("standard", "critical"), order);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2000L);
        }
    }

    @Test
    void admittedTransportWorkPreservesOrder() throws Exception {
        AtomicInteger generation = new AtomicInteger(4);
        BoundedTransportExecutor executor = new BoundedTransportExecutor("ReSync-Backpressure-Ordering", 8, 1024L,
            generation::get);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(3);
        List<Integer> order = new ArrayList<>();
        try {
            executor.offer(-1, 1L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                entered.countDown();
                await(release);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int value = 1; value <= 3; value++) {
                int retained = value;
                assertTrue(executor.offer(4, 1L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                    order.add(retained);
                    delivered.countDown();
                }));
            }

            release.countDown();

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3), order);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2000L);
        }
    }

    @Test
    void failedReplacementAdmissionLeavesExistingQueueUntouched() throws Exception {
        AtomicInteger generation = new AtomicInteger(3);
        BoundedTransportExecutor executor = new BoundedTransportExecutor("ReSync-Backpressure-Transactional", 2, 8L,
            generation::get);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        List<String> order = new ArrayList<>();
        try {
            assertTrue(executor.offer(-1, 1L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                entered.countDown();
                await(release);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(executor.offer(3, 4L, BoundedTransportExecutor.Priority.REPLACEABLE, "snapshot", () -> {
                order.add("snapshot");
                delivered.countDown();
            }));
            assertTrue(executor.offer(3, 4L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                order.add("critical");
                delivered.countDown();
            }));

            assertThrows(IllegalStateException.class, () -> executor.offer(3, 4L,
                BoundedTransportExecutor.Priority.REPLACEABLE, "snapshot", (Supplier<Runnable>) () -> {
                    throw new IllegalStateException("factory failed");
                }));
            assertFalse(executor.offer(3, 8L, BoundedTransportExecutor.Priority.REPLACEABLE, "snapshot", () -> {
            }));
            assertEquals(2, executor.queuedCount());
            assertEquals(8L, executor.queuedBytes());

            release.countDown();

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("snapshot", "critical"), order);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2000L);
        }
    }

    @Test
    void reconnectGenerationFencesQueuedWork() throws Exception {
        AtomicInteger generation = new AtomicInteger(1);
        BoundedTransportExecutor executor = new BoundedTransportExecutor("ReSync-Backpressure-Generation", 8, 1024L,
            generation::get);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        try {
            executor.offer(-1, 1L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                entered.countDown();
                await(release);
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(executor.offer(1, 1L, BoundedTransportExecutor.Priority.CRITICAL, null, stale::incrementAndGet));
            generation.set(2);
            assertTrue(executor.offer(2, 1L, BoundedTransportExecutor.Priority.CRITICAL, null, delivered::countDown));

            release.countDown();

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(0, stale.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2000L);
        }
    }

    @Test
    void terminalAdmissionSurvivesProducerInterruptionWhileSaturated() throws Exception {
        AtomicInteger generation = new AtomicInteger(1);
        BoundedTransportExecutor executor = new BoundedTransportExecutor("ReSync-Backpressure-Terminal", 1, 4L,
            generation::get);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        AtomicBoolean admitted = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            assertTrue(executor.offer(-1, 4L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                entered.countDown();
                await(release);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(executor.offer(-1, 4L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
            }));
            Thread producer = new Thread(() -> {
                admitted.set(executor.offer(-1, 4L, BoundedTransportExecutor.Priority.TERMINAL, null,
                    settled::countDown));
                interrupted.set(Thread.currentThread().isInterrupted());
            }, "ReSync-Backpressure-Terminal-Producer");
            producer.start();
            producer.interrupt();
            release.countDown();
            producer.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(producer.isAlive());
            assertTrue(admitted.get());
            assertTrue(interrupted.get());
            assertTrue(settled.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2000L);
        }
    }

    @Test
    void losslessAdmissionWaitsForCapacityInsteadOfDroppingTheFrame() throws Exception {
        AtomicInteger generation = new AtomicInteger(1);
        BoundedTransportExecutor executor = new BoundedTransportExecutor("ReSync-Backpressure-Lossless", 1, 4L,
            generation::get);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean admitted = new AtomicBoolean();
        try {
            assertTrue(executor.offer(-1, 4L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
                entered.countDown();
                await(release);
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(executor.offer(-1, 4L, BoundedTransportExecutor.Priority.CRITICAL, null, () -> {
            }));
            Thread producer = new Thread(() -> admitted.set(executor.offer(1, 4L,
                BoundedTransportExecutor.Priority.LOSSLESS, null, delivered::countDown)),
                "ReSync-Backpressure-Lossless-Producer");
            producer.start();
            producer.join(TimeUnit.MILLISECONDS.toMillis(100));
            assertTrue(producer.isAlive());

            release.countDown();
            producer.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(producer.isAlive());
            assertTrue(admitted.get());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2000L);
        }
    }

    @Test
    void rejectedDurableSendSettlesOnlyItsExactRequest() throws Exception {
        String serverId = "transport-backpressure:" + UUID.randomUUID();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, new ClosedTransport(), null,
            new ReSyncCatalogPublicationCache());
        String rejectedRequest = UUID.randomUUID().toString();
        String retainedRequest = UUID.randomUUID().toString();
        try {
            trackPendingDelete(client, "rejected", rejectedRequest);
            trackPendingDelete(client, "retained", retainedRequest);
            Method enqueue = pendingEnqueue();
            for (int index = 0; index < 16; index++) {
                assertTrue((boolean) enqueue.invoke(client, "fill-" + index,
                    (long) ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES, pendingSend()));
            }

            assertFalse((boolean) enqueue.invoke(client, rejectedRequest,
                (long) ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES, pendingSend()));
            assertFalse(pendingDeletes(client).containsKey(rejectedRequest));
            assertTrue(pendingDeletes(client).containsKey(retainedRequest));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void rejectedDurableReplacementPreservesItsExistingOwnerAndPosition() throws Exception {
        String serverId = "transport-transaction:" + UUID.randomUUID();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, new ClosedTransport(), null,
            new ReSyncCatalogPublicationCache());
        String requestId = UUID.randomUUID().toString();
        try {
            trackPendingDelete(client, "preserved", requestId);
            Method enqueue = pendingEnqueue();
            assertTrue((boolean) enqueue.invoke(client, requestId, 1024L * 1024L, pendingSend()));
            Object retained = pendingSendsByRequest(client).get(requestId);
            InvocationTargetException factoryFailure = assertThrows(InvocationTargetException.class,
                () -> pendingFactoryAdmission().invoke(client, requestId, 1024L * 1024L,
                    pendingPriority("DURABLE"), null, -1, (Supplier<Object>) () -> {
                        throw new IllegalStateException("factory failed");
                    }));
            assertTrue(factoryFailure.getCause() instanceof IllegalStateException);
            assertSame(retained, pendingSendsByRequest(client).get(requestId));
            for (int index = 0; index < 15; index++) {
                assertTrue((boolean) enqueue.invoke(client, "fill-transaction-" + index,
                    1024L * 1024L, pendingSend()));
            }

            assertFalse((boolean) enqueue.invoke(client, requestId, 2L * 1024L * 1024L, pendingSend()));
            assertTrue(pendingDeletes(client).containsKey(requestId));
            assertSame(retained, pendingSendsByRequest(client).get(requestId));
            assertEquals(16, pendingSendsByRequest(client).size());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void drainingDurableOwnerRejectsConcurrentReplacement() throws Exception {
        ToggleTransport transport = new ToggleTransport();
        ReSyncFlowClient client = new ReSyncFlowClient("transport-claim:" + UUID.randomUUID(), transport, null,
            new ReSyncCatalogPublicationCache());
        String requestId = UUID.randomUUID().toString();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger oldDeliveries = new AtomicInteger();
        AtomicInteger replacementDeliveries = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Method enqueue = pendingEnqueue();
            assertTrue((boolean) enqueue.invoke(client, requestId, 1024L,
                pendingSend(() -> {
                    entered.countDown();
                    await(release);
                    oldDeliveries.incrementAndGet();
                    return true;
                })));
            Object retained = pendingSendsByRequest(client).get(requestId);
            transport.open.set(true);
            authenticated(client).set(true);
            Thread drain = new Thread(() -> {
                try {
                    pendingFlush().invoke(client);
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            }, "ReSync-Backpressure-Claim-Test");
            drain.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            assertFalse((boolean) enqueue.invoke(client, requestId, 1024L,
                pendingSend(() -> {
                    replacementDeliveries.incrementAndGet();
                    return true;
                })));
            assertSame(retained, pendingSendsByRequest(client).get(requestId));

            release.countDown();
            drain.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(drain.isAlive());
            assertNull(failure.get());
            assertEquals(1, oldDeliveries.get());
            assertEquals(0, replacementDeliveries.get());
            assertTrue(awaitPendingSendRelease(client, requestId));
        } finally {
            release.countDown();
            client.shutdown();
        }
    }

    @Test
    void oversizedTerminalResponseFailsItsExactFutureThroughBoundedFallback() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient("transport-terminal:" + UUID.randomUUID(),
            new ClosedTransport(), null, new ReSyncCatalogPublicationCache());
        String requestId = "terminal-" + UUID.randomUUID();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        try {
            pendingPlayerControls(client).put(requestId, future);
            inboundFrameBytes(client).set(17 * 1024 * 1024);
            Method handler = ReSyncFlowClient.class.getDeclaredMethod("handlePlayerTrackingMessage", byte[].class);
            handler.setAccessible(true);
            handler.invoke(client, (Object) ("{\"type\":\"player_control_response\",\"requestId\":\""
                + requestId + "\"}").getBytes(StandardCharsets.UTF_8));
            inboundFrameBytes(client).remove();

            assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertFalse(pendingPlayerControls(client).containsKey(requestId));
        } finally {
            inboundFrameBytes(client).remove();
            client.shutdown();
        }
    }

    private static void trackPendingDelete(ReSyncFlowClient client, String id, String requestId) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("trackPendingLegacyDelete", ReSyncResourceType.class,
            String.class, String.class);
        method.setAccessible(true);
        method.invoke(client, ReSyncResourceType.GUI, id, requestId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> pendingDeletes(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingResourceDeletes");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> pendingSendsByRequest(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingSendsByRequest");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(client);
    }

    private static Method pendingEnqueue() throws Exception {
        Class<?> pendingSend = Class.forName(ReSyncFlowClient.class.getName() + "$PendingSend");
        Method method = ReSyncFlowClient.class.getDeclaredMethod("enqueuePendingSend", String.class, long.class,
            pendingSend);
        method.setAccessible(true);
        return method;
    }

    private static Method pendingFactoryAdmission() throws Exception {
        Class<?> pendingPriority = Class.forName(ReSyncFlowClient.class.getName() + "$PendingSendPriority");
        Method method = ReSyncFlowClient.class.getDeclaredMethod("admitPendingSendLocked", String.class, long.class,
            pendingPriority, String.class, int.class, Supplier.class);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object pendingPriority(String name) throws Exception {
        return Enum.valueOf((Class) Class.forName(ReSyncFlowClient.class.getName() + "$PendingSendPriority"), name);
    }

    private static Object pendingSend() throws Exception {
        return pendingSend(() -> true);
    }

    private static Object pendingSend(Callable<Boolean> send) throws Exception {
        Class<?> type = Class.forName(ReSyncFlowClient.class.getName() + "$PendingSend");
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "send" -> send.call();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "PendingSend";
                default -> null;
            });
    }

    private static Method pendingFlush() throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("flushPendingSends");
        method.setAccessible(true);
        return method;
    }

    private static AtomicBoolean authenticated(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("authenticated");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(client);
    }

    private static boolean awaitPendingSendRelease(ReSyncFlowClient client, String requestId) throws Exception {
        Field lockField = ReSyncFlowClient.class.getDeclaredField("outboundLock");
        lockField.setAccessible(true);
        Object lock = lockField.get(client);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            synchronized (lock) {
                if (!pendingSendsByRequest(client).containsKey(requestId)) {
                    return true;
                }
            }
            Thread.sleep(1L);
        }
        synchronized (lock) {
            return !pendingSendsByRequest(client).containsKey(requestId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CompletableFuture<JsonObject>> pendingPlayerControls(ReSyncFlowClient client)
        throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingPlayerControlRequests");
        field.setAccessible(true);
        return (Map<String, CompletableFuture<JsonObject>>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Integer> inboundFrameBytes(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("inboundFrameBytes");
        field.setAccessible(true);
        return (ThreadLocal<Integer>) field.get(client);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static class ClosedTransport implements ReSyncFrameTransport {
        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return false;
        }
    }

    private static final class ToggleTransport extends ClosedTransport {
        private final AtomicBoolean open = new AtomicBoolean();

        @Override
        public boolean isOpen() {
            return open.get();
        }
    }
}
