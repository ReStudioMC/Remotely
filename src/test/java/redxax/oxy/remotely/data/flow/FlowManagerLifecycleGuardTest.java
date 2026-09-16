package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient.CoreGraphListSubscription;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient.CoreGraphResourceSubscription;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerLifecycleGuardTest {
    @Test
    void retiringAnOlderManagerCannotClearThePublishedOwner(@TempDir Path stateRoot) throws Exception {
        FlowManager older = new FlowManager(null, null, null, redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(stateRoot.resolve("older")));
        FlowManager newer = new FlowManager(null, null, null, redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(stateRoot.resolve("newer")));

        assertSame(newer, FlowManager.getInstance());
        assertDoesNotThrow(older::shutdown);
        assertSame(newer, FlowManager.getInstance());
        assertDoesNotThrow(newer::shutdown);
        assertNull(FlowManager.getInstance());

        Field instance = FlowManager.class.getDeclaredField("INSTANCE");
        assertSame(AtomicReference.class, instance.getType());
    }

    @Test
    void shutdownFromActiveUiEffectFailsBeforeLifecycleMutation() {
        assertThrows(IllegalStateException.class, () -> FlowManager.requireExternalShutdown(1));
        assertDoesNotThrow(() -> FlowManager.requireExternalShutdown(0));
    }

    @Test
    void shutdownAttemptsAllSubscriptionsAndRetriesFailedCleanup() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        AtomicInteger failingCloseCount = new AtomicInteger();
        AtomicInteger successfulCloseCount = new AtomicInteger();
        addSubscription(manager, "a", () -> {
            if (failingCloseCount.getAndIncrement() == 0) {
                throw new IllegalStateException("first close failed");
            }
        });
        addSubscription(manager, "b", successfulCloseCount::incrementAndGet);

        RuntimeException failure = assertThrows(RuntimeException.class, manager::shutdown);

        assertNotNull(failure);
        assertTrue(failure.getSuppressed().length >= 1);
        assertEquals(1, failingCloseCount.get());
        assertEquals(1, successfulCloseCount.get());
        assertDoesNotThrow(manager::shutdown);
        assertEquals(2, failingCloseCount.get());
        assertEquals(1, successfulCloseCount.get());
    }

    private static void addSubscription(FlowManager manager, String serverId,
                                        CoreGraphResourceSubscription subscription) throws Exception {
        Field field = FlowManager.class.getDeclaredField("coreGraphListenerSubscriptions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> subscriptions = (ConcurrentHashMap<String, Object>) field.get(manager);
        Class<?> subscriptionType = Class.forName(
            "redxax.oxy.remotely.data.flow.FlowManager$CoreGraphListenerSubscription");
        Constructor<?> constructor = subscriptionType.getDeclaredConstructor(
            ReSyncFlowClient.class, long.class, CoreGraphResourceSubscription.class, CoreGraphListSubscription.class);
        constructor.setAccessible(true);
        CoreGraphListSubscription listSubscription = () -> {};
        subscriptions.put(serverId, constructor.newInstance(null, 1L, subscription, listSubscription));
    }
}
