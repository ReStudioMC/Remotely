package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerResourceActivationSettlementTest {
    private static final String SERVER_ID = UUID.randomUUID().toString();

    @Test
    void coreCommitSettlesPendingActivationBeforeRollbackCanObserveIt() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        try {
            putPendingActivation(manager, true, false, "activation:core");
            AtomicInteger commits = new AtomicInteger();

            assertTrue(manager.commitCoreResourceActivation(SERVER_ID, ReSyncResourceType.FLOW, "flow-a", "activation:core",
                false, "", false, () -> {
                    commits.incrementAndGet();
                    assertEquals(1, pendingActivations(manager).size());
                }));

            assertEquals(1, commits.get());
            assertTrue(pendingActivations(manager).isEmpty());
            assertFalse(rollbackResourceActivations(manager, SERVER_ID));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void failedCoreCommitLeavesPendingActivationForRollback() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        try {
            putPendingActivation(manager, true, false, "activation:core");

            assertThrows(IllegalStateException.class, () -> manager.commitCoreResourceActivation(SERVER_ID,
                ReSyncResourceType.FLOW, "flow-a", "activation:core", false, "", false,
                () -> { throw new IllegalStateException("commit failed"); }));

            assertEquals(1, pendingActivations(manager).size());
            assertTrue(rollbackResourceActivations(manager, SERVER_ID));
            assertTrue(pendingActivations(manager).isEmpty());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void retainedActivationSurvivesDisconnectAndRebindsToTheCurrentCoreOwner() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        ReSyncFlowClient source = new ReSyncFlowClient(SERVER_ID, null, null, null, null) {
            @Override
            boolean retainsResourceActivation(ReSyncResourceType type, String resourceId, String requestId) {
                return type == ReSyncResourceType.FLOW && "flow-a".equals(resourceId)
                    && "activation:core".equals(requestId);
            }
        };
        try {
            Object previousOwner = coreOwner(source, 1L);
            Object currentOwner = coreOwner(source, 2L);
            putPendingActivation(manager, true, false, "activation:core", previousOwner);

            assertFalse(rollbackResourceActivations(manager, SERVER_ID, source));
            assertSame(previousOwner, pendingActivationOwner(manager));

            rebindRetainedResourceActivations(manager, SERVER_ID, source, currentOwner);
            assertSame(currentOwner, pendingActivationOwner(manager));

            assertTrue(rollbackResourceActivations(manager, SERVER_ID));
            assertTrue(pendingActivations(manager).isEmpty());
        } finally {
            source.shutdown();
            manager.shutdown();
        }
    }

    private static void putPendingActivation(FlowManager manager, boolean previousEnabled, boolean enabled,
                                             String requestId) throws Exception {
        putPendingActivation(manager, previousEnabled, enabled, requestId, null);
    }

    private static void putPendingActivation(FlowManager manager, boolean previousEnabled, boolean enabled,
                                             String requestId, Object ownerToken) throws Exception {
        Field field = FlowManager.class.getDeclaredField("pendingActivations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> activations = (Map<Object, Object>) field.get(manager);
        Class<?> keyType = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$ActivationKey");
        Constructor<?> keyConstructor = keyType.getDeclaredConstructor(String.class, ReSyncResourceType.class, String.class);
        keyConstructor.setAccessible(true);
        Class<?> pendingType = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$PendingActivation");
        Class<?> ownerTokenType = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CoreGraphOwnerToken");
        Constructor<?> pendingConstructor = pendingType.getDeclaredConstructor(boolean.class, boolean.class, String.class, ownerTokenType);
        pendingConstructor.setAccessible(true);
        activations.put(keyConstructor.newInstance(SERVER_ID, ReSyncResourceType.FLOW, "flow-a"),
            pendingConstructor.newInstance(previousEnabled, enabled, requestId, ownerToken));
    }

    private static Map<?, ?> pendingActivations(FlowManager manager) {
        try {
            Field field = FlowManager.class.getDeclaredField("pendingActivations");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(manager);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Pending activations could not be inspected", exception);
        }
    }

    private static boolean rollbackResourceActivations(FlowManager manager, String serverId) throws Exception {
        Method method = FlowManager.class.getDeclaredMethod("rollbackResourceActivationsNow", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(manager, serverId);
    }

    private static boolean rollbackResourceActivations(FlowManager manager, String serverId,
                                                       ReSyncFlowClient retainedOwner) throws Exception {
        Method method = FlowManager.class.getDeclaredMethod("rollbackResourceActivationsNow", String.class,
            ReSyncFlowClient.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(manager, serverId, retainedOwner);
    }

    private static Object coreOwner(ReSyncFlowClient source, long generation) throws Exception {
        Class<?> type = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CoreGraphOwnerToken");
        Constructor<?> constructor = type.getDeclaredConstructor(ReSyncFlowClient.class, long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(source, generation, SERVER_ID);
    }

    private static Object pendingActivationOwner(FlowManager manager) throws Exception {
        Object pending = pendingActivations(manager).values().iterator().next();
        Method method = pending.getClass().getDeclaredMethod("ownerToken");
        method.setAccessible(true);
        return method.invoke(pending);
    }

    private static void rebindRetainedResourceActivations(FlowManager manager, String serverId,
                                                          ReSyncFlowClient source, Object ownerToken) throws Exception {
        Method method = FlowManager.class.getDeclaredMethod("rebindRetainedResourceActivations", String.class,
            ReSyncFlowClient.class, ownerToken.getClass());
        method.setAccessible(true);
        method.invoke(manager, serverId, source, ownerToken);
    }
}
