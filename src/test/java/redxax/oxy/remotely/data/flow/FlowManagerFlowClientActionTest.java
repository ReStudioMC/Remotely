package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyServerApi;
import restudio.rescreen.platform.Async;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerFlowClientActionTest {
    @Test
    void pendingAdmissionDeliversExactlyOnceToCurrentOwner(@TempDir Path stateRoot) {
        AdmissionManager manager = new AdmissionManager(stateRoot);
        ReSyncFlowClient owner = flowClient("current");
        Async<ReSyncFlowClient> admission = Async.pending();
        AtomicInteger deliveries = new AtomicInteger();
        manager.current.set(owner);
        manager.admissions.add(admission);

        Async<FlowManager.FlowClientActionSettlement<String>> settlement = manager.withFlowClient(
            "server", client -> {
                assertSame(owner, client);
                deliveries.incrementAndGet();
                return "delivered";
            });

        assertFalse(settlement.isDone());
        assertEquals(0, deliveries.get());
        admission.complete(owner);
        assertTrue(settlement.join().delivered());
        assertEquals("delivered", settlement.join().value());
        assertEquals(1, deliveries.get());

        owner.shutdown();
        manager.shutdown();
    }

    @Test
    void replacementBeforeCallbackRetriesWithoutSendingToStaleOwner(@TempDir Path stateRoot) {
        AdmissionManager manager = new AdmissionManager(stateRoot);
        ReSyncFlowClient older = flowClient("older");
        ReSyncFlowClient newer = flowClient("newer");
        Async<ReSyncFlowClient> firstAdmission = Async.pending();
        AtomicReference<ReSyncFlowClient> delivered = new AtomicReference<>();
        manager.current.set(older);
        manager.admissions.add(firstAdmission);
        manager.admissions.add(Async.completed(newer));

        Async<FlowManager.FlowClientActionSettlement<Void>> settlement = manager.withFlowClient(
            "server", client -> {
                delivered.set(client);
                return null;
            });

        manager.current.set(newer);
        firstAdmission.complete(older);
        assertFalse(settlement.isDone());
        assertNull(delivered.get());
        assertEquals(1, manager.retries.size());

        manager.runRetry();
        assertTrue(settlement.join().delivered());
        assertSame(newer, delivered.get());
        assertEquals(0, manager.retries.size());

        older.shutdown();
        newer.shutdown();
        manager.shutdown();
    }

    @Test
    void nullAdmissionSettlesUnavailableWithoutRunningRequest(@TempDir Path stateRoot) {
        AdmissionManager manager = new AdmissionManager(stateRoot);
        AtomicInteger deliveries = new AtomicInteger();
        manager.admissions.add(Async.completed(null));

        FlowManager.FlowClientActionSettlement<Void> settlement = manager.<Void>withFlowClient("server", client -> {
            deliveries.incrementAndGet();
            return null;
        }).join();

        assertTrue(settlement.unavailable());
        assertNull(settlement.value());
        assertEquals(0, deliveries.get());
        assertEquals(0, manager.retries.size());

        manager.shutdown();
    }

    private static ReSyncFlowClient flowClient(String serverId) {
        return new ReSyncFlowClient(serverId, (RemotelyServerApi) null, null);
    }

    private static final class AdmissionManager extends FlowManager {
        private final Queue<Async<ReSyncFlowClient>> admissions = new ArrayDeque<>();
        private final Queue<Runnable> retries = new ArrayDeque<>();
        private final AtomicReference<ReSyncFlowClient> current = new AtomicReference<>();

        private AdmissionManager(Path stateRoot) {
            super(null, null, null, stateRoot != null ? DesktopReSyncStorage.fromKey(stateRoot) : null);
        }

        @Override
        public Async<ReSyncFlowClient> ensureFlowClientAsync(String serverId) {
            Async<ReSyncFlowClient> admission = admissions.poll();
            return admission != null ? admission : Async.completed(null);
        }

        @Override
        public boolean withCurrentFlowClientNow(String serverId, ReSyncFlowClient expected,
                                                Consumer<ReSyncFlowClient> action) {
            if (current.get() != expected) {
                return false;
            }
            action.accept(expected);
            return true;
        }

        @Override
        protected void retryFlowClientAction(Runnable action) {
            retries.add(action);
        }

        private void runRetry() {
            Runnable retry = retries.poll();
            if (retry != null) {
                retry.run();
            }
        }
    }
}
