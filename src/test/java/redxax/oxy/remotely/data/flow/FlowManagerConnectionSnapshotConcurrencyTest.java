package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerConnectionSnapshotConcurrencyTest {
    @Test
    void currentConnectionReadsDoNotWaitForTheGenerationMonitor() throws Exception {
        String serverId = ServerId.deterministic("connection-snapshot-concurrency").canonicalText();
        FlowManager manager = new FlowManager(null, null);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, transport, null);
        try {
            FlowManagerTestConnection.install(manager, serverId, client, transport);
            FlowManager.ServerConnectionToken expected = manager.captureServerConnectionToken(serverId, client);
            Field lockField = FlowManager.class.getDeclaredField("serverConnectionGenerationLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(manager);
            Method worldGenCapture = WorldGenManager.class.getDeclaredMethod("captureConnectionToken", String.class);
            worldGenCapture.setAccessible(true);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = Thread.ofPlatform().start(() -> {
                synchronized (lock) {
                    locked.countDown();
                    try {
                        release.await(2L, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(locked.await(1L, TimeUnit.SECONDS));
            try {
                assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                    FlowManager.ServerConnectionToken actual = manager.currentServerConnectionToken(serverId);
                    assertEquals(expected.generation(), actual.generation());
                    assertSame(client, actual.source());
                    assertTrue(manager.isCurrentServerConnection(actual));
                    FlowManager.ServerConnectionToken worldGenToken =
                        (FlowManager.ServerConnectionToken) worldGenCapture.invoke(WorldGenManager.getInstance(), serverId);
                    assertEquals(expected.generation(), worldGenToken.generation());
                    assertSame(client, worldGenToken.source());
                });
            } finally {
                release.countDown();
                holder.join(2_000L);
            }
        } finally {
            client.shutdown();
            CompletableFuture.runAsync(manager::shutdown).get(5L, TimeUnit.SECONDS);
        }
    }
}
