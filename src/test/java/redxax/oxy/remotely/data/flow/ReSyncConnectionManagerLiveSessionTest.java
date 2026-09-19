package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.Async;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncConnectionManagerLiveSessionTest {

    @Test
    void mismatchedFramePeerIdentityIsRejectedBeforeOwnerPublication() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String configuredServerId = "abcdefab-cdef-4abc-8def-abcdefabcdef";
        ServerId otherServerId = new ServerId(UUID.fromString("12345678-1234-4234-8234-123456789abc"));

        assertNull(manager.activateLiveSession(new ReSyncLiveServerSession(configuredServerId, "Server",
            new TestTransport(otherServerId))));
        assertNull(manager.getFlowClient(configuredServerId));
    }

    @Test
    void ownerActionFailsSelfRetirementWithoutDeadlocking() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "self-close:server";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));

        assertTrue(manager.withCurrentFlowClient(serverId, owner, ignored ->
            assertThrows(IllegalStateException.class,
                () -> manager.closeServerConnectionAtomically(serverId, null, null))));

        assertSame(owner, manager.getFlowClient(serverId));
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void nestedOwnerAdmissionFailsWhenAnotherThreadIsRetiringTheOwner() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "nested-retirement:server";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch tryNestedAdmission = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> action = executor.submit(() -> manager.withCurrentFlowClient(serverId, owner, ignored -> {
                actionEntered.countDown();
                await(tryNestedAdmission);
                assertFalse(manager.withCurrentFlowClient(serverId, owner, nested -> {
                }));
            }));
            assertTrue(actionEntered.await(5, TimeUnit.SECONDS));
            Future<Boolean> close = executor.submit(() -> {
                closeStarted.countDown();
                return manager.closeServerConnectionAtomically(serverId, null, null);
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
            tryNestedAdmission.countDown();

            assertTrue(action.get(5, TimeUnit.SECONDS));
            assertTrue(close.get(5, TimeUnit.SECONDS));
        } finally {
            tryNestedAdmission.countDown();
            executor.shutdownNow();
            manager.closeServerConnection(serverId, () -> {
            });
        }
    }

    @Test
    void closeWaitsForActiveOwnerActionBeforeRetirement() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "leased-close:server";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch retirementEntered = new CountDownLatch(1);
        AtomicInteger cacheClears = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> action = executor.submit(() -> manager.withCurrentFlowClient(serverId, owner, ignored -> {
                actionEntered.countDown();
                await(releaseAction);
            }));
            assertTrue(actionEntered.await(5, TimeUnit.SECONDS));
            Future<Boolean> close = executor.submit(() -> {
                closeStarted.countDown();
                return manager.closeServerConnectionAtomically(serverId, retired -> {
                    assertSame(owner, retired);
                    retirementEntered.countDown();
                }, cacheClears::incrementAndGet);
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertFalse(retirementEntered.await(100, TimeUnit.MILLISECONDS));
            releaseAction.countDown();

            assertTrue(action.get(5, TimeUnit.SECONDS));
            assertTrue(close.get(5, TimeUnit.SECONDS));
            assertEquals(0, retirementEntered.getCount());
            assertEquals(1, cacheClears.get());
            assertEquals(0, ownershipLocks(manager).size());
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
            manager.closeServerConnection(serverId, () -> {
            });
        }
    }

    @Test
    void atomicCloseRunsRetirementAndFinalClearWithoutAnOwner() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        AtomicInteger retirements = new AtomicInteger();
        AtomicInteger cacheClears = new AtomicInteger();

        boolean removed = manager.closeServerConnectionAtomically("absent:server", source -> {
            assertNull(source);
            retirements.incrementAndGet();
        }, cacheClears::incrementAndGet);

        assertFalse(removed);
        assertEquals(1, retirements.get());
        assertEquals(1, cacheClears.get());
    }

    @Test
    void closeRejectsConcurrentAttachDuringRetirement() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "owner-close:server";
        TestTransport transport = new TestTransport();
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", transport));
        CountDownLatch detachEntered = new CountDownLatch(1);
        CountDownLatch releaseDetach = new CountDownLatch(1);
        CountDownLatch attachStarted = new CountDownLatch(1);
        AtomicInteger attaches = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> close = executor.submit(() -> manager.closeCurrentFlowClient(serverId, removed -> {
                assertSame(owner, removed);
                detachEntered.countDown();
                await(releaseDetach);
            }));
            assertTrue(detachEntered.await(5, TimeUnit.SECONDS));
            Future<Boolean> attach = executor.submit(() -> {
                attachStarted.countDown();
                return manager.withCurrentFlowClient(serverId, owner, ignored -> attaches.incrementAndGet());
            });
            assertTrue(attachStarted.await(5, TimeUnit.SECONDS));
            assertFalse(attach.get(5, TimeUnit.SECONDS));
            releaseDetach.countDown();

            assertTrue(close.get(5, TimeUnit.SECONDS));
            assertEquals(0, attaches.get());
            assertEquals(1, transport.closeCalls.get());
            assertEquals(0, ownershipLocks(manager).size());
        } finally {
            releaseDetach.countDown();
            executor.shutdownNow();
            manager.closeServerConnection(serverId, () -> {
            });
        }
    }

    @Test
    void reusableFrameTransportCanHandshakeAgain() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        TestTransport transport = new TestTransport();
        ReSyncLiveServerSession session = new ReSyncLiveServerSession("live:reusable:player", "Server", transport);
        ReSyncFlowClient first = manager.activateLiveSession(session);
        transport.disconnect();

        ReSyncFlowClient reactivated = manager.activateLiveSession(session);
        try {
            assertSame(first, reactivated);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (transport.sentFrames.get() < 2) {
                    Thread.onSpinWait();
                }
            });
            assertEquals(2, transport.sentFrames.get());
        } finally {
            reactivated.shutdown();
        }
    }

    @Test
    void currentOwnerActionSerializesReplacementAndRejectsTheStaleClient() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "owner-action:server";
        ReSyncFlowClient first = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));
        AtomicInteger actions = new AtomicInteger();
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> action = executor.submit(() -> manager.withCurrentFlowClient(serverId, first, ignored -> {
                actions.incrementAndGet();
                actionEntered.countDown();
                await(releaseAction);
            }));
            assertTrue(actionEntered.await(5, TimeUnit.SECONDS));
            Future<ReSyncFlowClient> replacement = executor.submit(() -> {
                replacementStarted.countDown();
                return manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", new TestTransport()));
            });
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS));
            assertNull(replacement.get(2, TimeUnit.SECONDS));
            releaseAction.countDown();

            assertTrue(action.get(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (manager.getFlowClient(serverId) == null || manager.getFlowClient(serverId) == first) {
                    Thread.onSpinWait();
                }
            });
            ReSyncFlowClient current = manager.getFlowClient(serverId);
            assertFalse(manager.withCurrentFlowClient(serverId, first, ignored -> actions.incrementAndGet()));
            assertTrue(manager.withCurrentFlowClient(serverId, current, ignored -> actions.incrementAndGet()));
            assertEquals(2, actions.get());
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
            manager.closeServerConnection(serverId, () -> {
            });
        }
    }

    @Test
    void synchronousHandshakeListenerCanReenterActivation() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "reentrant:server";
        ReentrantHandshakeTransport transport = new ReentrantHandshakeTransport(manager, serverId);
        ReSyncLiveServerSession session = new ReSyncLiveServerSession(serverId, "Server", transport);
        manager.setConnectionListener(ignored -> manager.activateLiveSession(session));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> manager.activateLiveSession(session));

        assertEquals(1, transport.sentFrames.get());
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void ownerActionCompletesWhileConnectedListenerIsBlocked() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "blocked-connected-listener:server";
        ReentrantHandshakeTransport transport = new ReentrantHandshakeTransport(manager, serverId);
        ReSyncLiveServerSession session = new ReSyncLiveServerSession(serverId, "Server", transport);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        manager.setConnectionListener(ignored -> {
            listenerEntered.countDown();
            await(releaseListener);
        });
        try {
            Future<ReSyncFlowClient> activation = executor.submit(() -> manager.activateLiveSession(session));
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
            ReSyncFlowClient owner = manager.getFlowClient(serverId);
            assertTrue(owner != null);

            Future<Boolean> action = executor.submit(() -> manager.withCurrentFlowClient(serverId, owner,
                ignored -> {
                }));
            assertTrue(action.get(2, TimeUnit.SECONDS));

            releaseListener.countDown();
            assertSame(owner, activation.get(2, TimeUnit.SECONDS));
        } finally {
            releaseListener.countDown();
            executor.shutdownNow();
            manager.shutdownAll();
        }
    }

    @Test
    void precommitConnectedCallbackIsReplayedExactlyOnceAfterActivation() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "fenced:server";
        ReentrantHandshakeTransport transport = new ReentrantHandshakeTransport(manager, serverId, false);
        AtomicInteger connections = new AtomicInteger();
        manager.setConnectionListener(ignored -> connections.incrementAndGet());

        manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", transport));

        assertEquals(0, connections.get());
        transport.callbacksEnabled.set(true);
        transport.commitCallbacks();
        assertEquals(1, connections.get());
        transport.commitCallbacks();
        assertEquals(1, connections.get());
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void displacedPrecommitConnectedCallbackIsSuppressed() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "displaced-precommit:server";
        ReentrantHandshakeTransport firstTransport = new ReentrantHandshakeTransport(manager, serverId, false);
        AtomicInteger connections = new AtomicInteger();
        manager.setConnectionListener(ignored -> connections.incrementAndGet());

        manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", firstTransport));
        Object firstOwner = flowClientOwners(manager).get(serverId);
        ReentrantHandshakeTransport secondTransport = new ReentrantHandshakeTransport(manager, serverId, false);
        manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", secondTransport));

        firstTransport.callbacksEnabled.set(true);
        firstTransport.commitCallbacks();
        assertEquals(0, connections.get());
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (flowClientOwners(manager).get(serverId) == firstOwner) {
                Thread.onSpinWait();
            }
        });
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void callbackInvalidatingItsGenerationIsNotReplayed() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "self-invalidating:server";
        ReentrantHandshakeTransport transport = new ReentrantHandshakeTransport(manager, serverId, false);
        AtomicInteger connections = new AtomicInteger();
        AtomicReference<ReSyncFlowClient> owner = new AtomicReference<>();
        manager.setConnectionListener(ignored -> {
            connections.incrementAndGet();
            try {
                flowClientOwners(manager).remove(serverId);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });

        owner.set(manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", transport)));
        transport.callbacksEnabled.set(true);
        transport.commitCallbacks();
        transport.commitCallbacks();
        assertEquals(1, connections.get());
        owner.get().shutdown();
    }

    @Test
    void frameConnectDefersWithoutWaitingForPriorCleanup() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient("deferred:frame", transport, null);
        Async<Void> cleanup = Async.pending();
        Field cleanupField = ReSyncFlowClient.class.getDeclaredField("connectionCleanupCompletion");
        cleanupField.setAccessible(true);
        cleanupField.set(client, cleanup);
        try {
            Async<Void> connect = assertTimeoutPreemptively(Duration.ofSeconds(1), client::connect);

            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTING, client.connectionState());
            assertEquals(0, transport.sentFrames.get());
            assertFalse(connect.isDone());

            cleanup.complete(null);

            assertTrue(transport.frameSent.await(2, TimeUnit.SECONDS));
            connect.join();
            assertEquals(1, transport.sentFrames.get());
        } finally {
            cleanup.complete(null);
            client.shutdown();
        }
    }

    @Test
    void disconnectedOwnerCanDispatchAgainWithoutAnotherErrorCallback() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "terminal:server";
        ensureWithoutConnecting(manager, serverId, null);
        Object owner = flowClientOwners(manager).get(serverId);
        Method claimConnect = ReSyncConnectionManager.class.getDeclaredMethod("claimConnect", owner.getClass());
        claimConnect.setAccessible(true);

        Object firstClaim = claimConnect.invoke(manager, owner);
        Method completion = firstClaim.getClass().getDeclaredMethod("completion");
        Method dispatch = firstClaim.getClass().getDeclaredMethod("dispatch");
        completion.setAccessible(true);
        dispatch.setAccessible(true);
        ((CompletableFuture<?>) completion.invoke(firstClaim)).complete(null);

        Object retryClaim = claimConnect.invoke(manager, owner);
        Object duplicateClaim = claimConnect.invoke(manager, owner);

        assertTrue((boolean) dispatch.invoke(retryClaim));
        assertFalse((boolean) dispatch.invoke(duplicateClaim));
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void explicitProfileReplacesAConnectedDifferentOwner() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "profile:server";
        ReSyncFlowClient first = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));
        Field authenticated = ReSyncFlowClient.class.getDeclaredField("authenticated");
        authenticated.setAccessible(true);
        ((AtomicBoolean) authenticated.get(first)).set(true);

        assertNull(ensureWithoutConnecting(manager, serverId,
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "replacement-key")));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (manager.getFlowClient(serverId) == null) {
                Thread.onSpinWait();
            }
        });
        ReSyncFlowClient replacement = manager.getFlowClient(serverId);

        assertNotSame(first, replacement);
        assertSame(replacement, manager.getFlowClient(serverId));
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void ownershipLockIsReclaimedAfterClose() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "closed:server";
        manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", new TestTransport()));

        manager.closeServerConnection(serverId, () -> {
        });

        assertEquals(0, ownershipLocks(manager).size());
    }

    @Test
    void ownershipLocksAreReclaimedAfterShutdown() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        manager.activateLiveSession(new ReSyncLiveServerSession("shutdown:first", "First", new TestTransport()));
        manager.activateLiveSession(new ReSyncLiveServerSession("shutdown:second", "Second", new TestTransport()));

        manager.shutdownAll();

        assertEquals(0, ownershipLocks(manager).size());
    }

    @Test
    void shutdownAwaitsAnActiveReplacementBeforeReturning() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        BlockingCloseTransport transport = new BlockingCloseTransport();
        String serverId = "shutdown:replacement";
        manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", transport));
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "replacement-key");
        flowProfiles(manager).put(serverId, profile);
        assertNull(ensureWithoutConnecting(manager, serverId,
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8766", "replacement-key-2")));
        assertTrue(transport.closeEntered.await(2, TimeUnit.SECONDS));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> shutdown = executor.submit(manager::shutdownAll);
            assertFalse(shutdown.isDone());
            transport.allowClose.countDown();
            shutdown.get(5, TimeUnit.SECONDS);
            assertNull(manager.getFlowClient(serverId));
            assertEquals(0, ownershipLocks(manager).size());
        } finally {
            transport.allowClose.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void withCurrentFlowClientNowReleasesOwnershipBeforeImmediateAction() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "now:reentrant";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));
        AtomicBoolean actionEntered = new AtomicBoolean();

        assertTrue(manager.withCurrentFlowClientNow(serverId, owner, ignored -> {
            actionEntered.set(true);
            assertThrows(IllegalStateException.class,
                () -> manager.closeServerConnectionAtomically(serverId, null, null));
        }));

        assertTrue(actionEntered.get());
        assertSame(owner, manager.getFlowClient(serverId));
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void withCurrentFlowClientNowReacquiresOwnershipForNestedCallback() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "now:nested";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));
        AtomicInteger nestedActions = new AtomicInteger();

        assertTrue(manager.withCurrentFlowClientNow(serverId, owner, ignored ->
            assertTrue(manager.withCurrentFlowClientNow(serverId, owner,
                nested -> nestedActions.incrementAndGet()))));

        assertEquals(1, nestedActions.get());
        manager.closeServerConnection(serverId, () -> {
        });
    }

    @Test
    void shutdownBoundsNeverReleasingTransportAndRemainsIdempotent() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        NonInterruptibleCloseTransport stuckTransport = new NonInterruptibleCloseTransport();
        TestTransport healthyTransport = new TestTransport();
        String stuckServerId = "shutdown:never-release";
        String healthyServerId = "shutdown:healthy";
        manager.activateLiveSession(new ReSyncLiveServerSession(stuckServerId, "Stuck", stuckTransport));
        manager.activateLiveSession(new ReSyncLiveServerSession(healthyServerId, "Healthy", healthyTransport));
        flowProfiles(manager).put(stuckServerId,
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "stuck-key"));
        assertNull(ensureWithoutConnecting(manager, stuckServerId,
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8766", "replacement-key")));
        assertTrue(stuckTransport.closeEntered.await(2, TimeUnit.SECONDS));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> shutdown = executor.submit(manager::shutdownAll);
            assertThrows(ExecutionException.class, () -> shutdown.get(3, TimeUnit.SECONDS));
            assertEquals(1, healthyTransport.closeCalls.get());
            assertNull(manager.getFlowClient(stuckServerId));
            assertNull(manager.getFlowClient(healthyServerId));
            assertEquals(0, ownershipLocks(manager).size());
            assertTimeoutPreemptively(Duration.ofMillis(250), () -> assertDoesNotThrow(manager::shutdownAll));

            stuckTransport.allowClose.countDown();
            assertTrue(stuckTransport.closeExited.await(2, TimeUnit.SECONDS));
            assertTrue(connectionLifecycleExecutor(manager).awaitTermination(2, TimeUnit.SECONDS));
            assertNull(manager.getFlowClient(stuckServerId));
            assertNull(manager.getFlowClient(healthyServerId));
            assertTrue(flowProfiles(manager).isEmpty());
            assertTrue(pendingRetirements(manager).isEmpty());
            assertEquals(0, ownershipLocks(manager).size());
        } finally {
            stuckTransport.allowClose.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedShutdownRestoresInterruptAfterBoundedCleanup() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        BlockingCloseTransport transport = new BlockingCloseTransport();
        String serverId = "shutdown:interrupted";
        manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", transport));
        try {
            Thread.currentThread().interrupt();
            assertThrows(RuntimeException.class, manager::shutdownAll);
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(manager.getFlowClient(serverId));
            assertEquals(0, ownershipLocks(manager).size());
            assertDoesNotThrow(manager::shutdownAll);
        } finally {
            Thread.interrupted();
            transport.allowClose.countDown();
        }
    }

    @Test
    void shutdownCompletesPartialRetirementAndIsIdempotent() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        String serverId = "shutdown:partial";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Server", new TestTransport()));
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "partial-key");
        flowProfiles(manager).put(serverId, profile);
        AtomicBoolean failRetirement = new AtomicBoolean(true);
        AtomicInteger retirements = new AtomicInteger();
        AtomicInteger cacheClears = new AtomicInteger();
        Consumer<ReSyncFlowClient> onRetired = ignored -> {
            retirements.incrementAndGet();
            if (failRetirement.getAndSet(false)) {
                throw new IllegalStateException("retirement callback failed once");
            }
        };

        assertThrows(RuntimeException.class, () -> manager.closeServerConnectionAtomically(
            serverId, onRetired, cacheClears::incrementAndGet));
        assertSame(owner, manager.getFlowClient(serverId));

        assertDoesNotThrow(manager::shutdownAll);
        assertNull(manager.getFlowClient(serverId));
        assertNull(manager.getProfile(serverId));
        assertEquals(2, retirements.get());
        assertEquals(1, cacheClears.get());
        assertDoesNotThrow(manager::shutdownAll);
        assertEquals(0, ownershipLocks(manager).size());
    }

    @Test
    void failedShutdownDrainsOwnersBeforeReportingFailure() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        RetryingCloseTransport failedTransport = new RetryingCloseTransport();
        TestTransport successfulTransport = new TestTransport();
        ReSyncFlowClient failedOwner = manager.activateLiveSession(new ReSyncLiveServerSession(
            "shutdown:failed", "Failed", failedTransport));
        manager.activateLiveSession(new ReSyncLiveServerSession("shutdown:successful", "Successful", successfulTransport));

        assertThrows(RuntimeException.class, manager::shutdownAll);

        assertNull(manager.getFlowClient("shutdown:failed"));
        assertNull(manager.getFlowClient("shutdown:successful"));
        assertEquals(2, failedTransport.closeCalls.get());
        assertEquals(1, successfulTransport.closeCalls.get());

        assertDoesNotThrow(manager::shutdownAll);
        assertNull(manager.getFlowClient("shutdown:failed"));
        assertEquals(2, failedTransport.closeCalls.get());
    }

    @Test
    void failedCurrentCloseRetainsExactOwnerForRetry() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        RetryingCloseTransport transport = new RetryingCloseTransport();
        String serverId = "close:failed";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Failed", transport));

        assertThrows(RuntimeException.class, () -> manager.closeCurrentFlowClient(serverId, null));

        assertSame(owner, manager.getFlowClient(serverId));
        assertEquals(1, transport.closeCalls.get());

        assertTrue(manager.closeCurrentFlowClient(serverId, null));
        assertNull(manager.getFlowClient(serverId));
        assertEquals(2, transport.closeCalls.get());
    }

    @Test
    void failedAtomicCloseRetainsOwnerAndProfileUntilRetry() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        RetryingCloseTransport transport = new RetryingCloseTransport();
        String serverId = "atomic-close:failed";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Failed", transport));
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "failed-key");
        flowProfiles(manager).put(serverId, profile);
        AtomicInteger retirements = new AtomicInteger();
        AtomicInteger cacheClears = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> manager.closeServerConnectionAtomically(serverId,
            ignored -> retirements.incrementAndGet(), cacheClears::incrementAndGet));

        assertSame(owner, manager.getFlowClient(serverId));
        assertSame(profile, manager.getProfile(serverId));
        assertEquals(0, retirements.get());
        assertEquals(0, cacheClears.get());

        assertTrue(manager.closeServerConnectionAtomically(serverId,
            ignored -> retirements.incrementAndGet(), cacheClears::incrementAndGet));
        assertNull(manager.getFlowClient(serverId));
        assertNull(manager.getProfile(serverId));
        assertEquals(1, retirements.get());
        assertEquals(1, cacheClears.get());
        assertEquals(2, transport.closeCalls.get());
    }

    @Test
    void failedPostShutdownRetirementRetriesOnlyTheFailedCleanupStep() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        TestTransport transport = new TestTransport();
        String serverId = "post-shutdown:failed";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Failed", transport));
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "failed-key");
        flowProfiles(manager).put(serverId, profile);
        AtomicBoolean failRetirement = new AtomicBoolean(true);
        AtomicInteger retirements = new AtomicInteger();
        AtomicInteger cacheClears = new AtomicInteger();
        Consumer<ReSyncFlowClient> onRetired = ignored -> {
            retirements.incrementAndGet();
            if (failRetirement.getAndSet(false)) {
                throw new IllegalStateException("retirement callback failed once");
            }
        };
        Runnable onCacheClear = () -> cacheClears.incrementAndGet();

        assertThrows(RuntimeException.class, () -> manager.closeServerConnectionAtomically(serverId, onRetired, onCacheClear));

        assertSame(owner, manager.getFlowClient(serverId));
        assertSame(profile, manager.getProfile(serverId));
        assertEquals(1, transport.closeCalls.get());
        assertEquals(1, retirements.get());
        assertEquals(1, cacheClears.get());
        assertThrows(IllegalStateException.class, () -> manager.ensureFlowClient(serverId,
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8766", "replacement-key")));

        assertTrue(manager.closeServerConnectionAtomically(serverId, onRetired, onCacheClear));

        assertNull(manager.getFlowClient(serverId));
        assertNull(manager.getProfile(serverId));
        assertEquals(1, transport.closeCalls.get());
        assertEquals(2, retirements.get());
        assertEquals(1, cacheClears.get());
        assertEquals(0, ownershipLocks(manager).size());
    }

    @Test
    void failedAbsentServerCacheCleanupRemainsReachableWithoutDoubleRetirementCallback() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        AtomicBoolean failCache = new AtomicBoolean(true);
        AtomicInteger retirements = new AtomicInteger();
        AtomicInteger cacheClears = new AtomicInteger();
        Consumer<ReSyncFlowClient> onRetired = ignored -> retirements.incrementAndGet();
        Runnable onCacheClear = () -> {
            cacheClears.incrementAndGet();
            if (failCache.getAndSet(false)) {
                throw new IllegalStateException("cache clear failed once");
            }
        };

        assertThrows(RuntimeException.class, () -> manager.closeServerConnectionAtomically(
            "absent:retryable", onRetired, onCacheClear));
        assertEquals(1, retirements.get());
        assertEquals(1, cacheClears.get());

        assertFalse(manager.closeServerConnectionAtomically("absent:retryable", onRetired, onCacheClear));

        assertEquals(1, retirements.get());
        assertEquals(2, cacheClears.get());
        assertEquals(0, ownershipLocks(manager).size());
    }

    @Test
    void failedReplacementRetainsOwnerAndDoesNotPublishCandidate() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        AlwaysFailingCloseTransport transport = new AlwaysFailingCloseTransport();
        String serverId = "replacement:failed";
        ReSyncFlowClient owner = manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Failed", transport));
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "failed-key");
        flowProfiles(manager).put(serverId, profile);
        TestTransport replacementTransport = new TestTransport();

        assertNull(manager.activateLiveSession(new ReSyncLiveServerSession(
            serverId, "Replacement", replacementTransport)));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (transport.closeCalls.get() == 0) {
                Thread.onSpinWait();
            }
        });

        assertNull(manager.getFlowClient(serverId));
        assertSame(profile, manager.getProfile(serverId));
        assertEquals(1, transport.closeCalls.get());
        assertEquals(0, replacementTransport.sentFrames.get());
    }

    @Test
    void directProfileReplacementRetiresOffTheCallingThread() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        BlockingCloseTransport transport = new BlockingCloseTransport();
        String serverId = "replacement:async";
        ReSyncFlowClient original = manager.activateLiveSession(new ReSyncLiveServerSession(serverId, "Server", transport));
        ReSyncConnectionManager.ReSyncConnectionProfile profile =
            new ReSyncConnectionManager.ReSyncConnectionProfile("ws://127.0.0.1:8765", "replacement-key");
        try {
            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                assertNull(ensureWithoutConnecting(manager, serverId, profile)));
            assertTrue(transport.closeEntered.await(2, TimeUnit.SECONDS));
            assertTrue(transport.closeThread.get().startsWith("ReSync-Connection-Lifecycle-"));
            assertNull(manager.getFlowClient(serverId));
            assertFalse(manager.isFlowClientConnected(serverId));

            transport.allowClose.countDown();

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (manager.getFlowClient(serverId) == null) {
                    Thread.onSpinWait();
                }
            });
            assertNotSame(original, manager.getFlowClient(serverId));
            assertSame(profile, manager.getProfile(serverId));
        } finally {
            transport.allowClose.countDown();
            try {
                manager.closeServerConnection(serverId, () -> {
                });
            } finally {
                manager.shutdownAll();
            }
        }
    }

    @Test
    void concurrentActivationPublishesAndConnectsOneClient() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        TestTransport transport = new TestTransport();
        ReSyncLiveServerSession session = new ReSyncLiveServerSession("live:server:player", "Server", transport);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ReSyncFlowClient> first = executor.submit(() -> activateTogether(manager, session, ready, start));
            Future<ReSyncFlowClient> second = executor.submit(() -> activateTogether(manager, session, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            ReSyncFlowClient firstClient = first.get(5, TimeUnit.SECONDS);
            ReSyncFlowClient secondClient = second.get(5, TimeUnit.SECONDS);

            assertSame(firstClient, secondClient);
            assertSame(firstClient, manager.getFlowClient(session.serverId()));
            assertEquals(1, transport.sentFrames.get());
            assertEquals(0, transport.closeCalls.get());
        } finally {
            manager.closeServerConnection(session.serverId(), () -> {
            });
            executor.shutdownNow();
        }
    }

    @Test
    void displacedClientCallbacksCannotReportForTheNewOwner() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        AtomicInteger disconnects = new AtomicInteger();
        CountDownLatch disconnectReported = new CountDownLatch(1);
        manager.setDisconnectListener(serverId -> {
            disconnects.incrementAndGet();
            disconnectReported.countDown();
        });
        TestTransport firstTransport = new TestTransport();
        ReSyncLiveServerSession firstSession = new ReSyncLiveServerSession("live:server:player", "Server", firstTransport);
        manager.activateLiveSession(firstSession);
        TestTransport secondTransport = new TestTransport();

        manager.activateLiveSession(new ReSyncLiveServerSession(firstSession.serverId(), "Server", secondTransport));
        assertEquals(0, disconnects.get());
        assertTrue(secondTransport.frameSent.await(2, TimeUnit.SECONDS));

        secondTransport.disconnect();
        assertTrue(disconnectReported.await(2, TimeUnit.SECONDS));
        assertEquals(1, disconnects.get());
        manager.closeServerConnection(firstSession.serverId(), () -> {
        });
    }

    @Test
    void repeatedActivationKeepsTheConnectingBridgeTransport() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        TestTransport transport = new TestTransport();
        ReSyncLiveServerSession session = new ReSyncLiveServerSession("live:server:player", "Server", transport);

        ReSyncFlowClient first = manager.activateLiveSession(session);
        try {
            ReSyncFlowClient second = manager.activateLiveSession(session);

            assertSame(first, second);
            assertEquals(0, transport.closeCalls.get());
            assertEquals(1, transport.sentFrames.get());
        } finally {
            first.shutdown();
        }
    }

    @Test
    void closedLiveSessionIsReplacedByTheNextActivation() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        TestTransport firstTransport = new TestTransport();
        ReSyncLiveServerSession firstSession = new ReSyncLiveServerSession("live:server:player", "Server", firstTransport);
        ReSyncFlowClient first = manager.activateLiveSession(firstSession);

        manager.closeServerConnection(firstSession.serverId(), () -> {
        });
        TestTransport secondTransport = new TestTransport();
        ReSyncFlowClient second = manager.activateLiveSession(new ReSyncLiveServerSession(
            firstSession.serverId(), "Server", secondTransport));
        try {
            assertNotSame(first, second);
            assertEquals(1, firstTransport.closeCalls.get());
            assertEquals(1, secondTransport.sentFrames.get());
        } finally {
            second.shutdown();
        }
    }

    @Test
    void retiredBridgeTransportRequiresAFreshOuterSession() {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        TestTransport transport = new TestTransport(false);
        ReSyncLiveServerSession session = new ReSyncLiveServerSession("live:server:player", "Server", transport);
        ReSyncFlowClient first = manager.activateLiveSession(session);
        transport.disconnect();

        ReSyncFlowClient reactivated = manager.activateLiveSession(session);
        TestTransport replacementTransport = new TestTransport();
        assertNull(manager.activateLiveSession(new ReSyncLiveServerSession(
            session.serverId(), "Server", replacementTransport)));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (manager.getFlowClient(session.serverId()) == null) {
                Thread.onSpinWait();
            }
        });
        ReSyncFlowClient replacement = manager.getFlowClient(session.serverId());
        try {
            assertSame(first, reactivated);
            assertEquals(1, transport.sentFrames.get());
            assertNotSame(first, replacement);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (replacementTransport.sentFrames.get() < 1) {
                    Thread.onSpinWait();
                }
            });
            assertEquals(1, replacementTransport.sentFrames.get());
        } finally {
            replacement.shutdown();
        }
    }

    @Test
    void duplicateConnectClaimDoesNotWaitForPendingDispatch() throws Exception {
        ReSyncConnectionManager manager = new ReSyncConnectionManager(null, null);
        TestTransport transport = new TestTransport();
        ReSyncLiveServerSession session = new ReSyncLiveServerSession("live:pending:player", "Server", transport);
        ReSyncFlowClient client = manager.activateLiveSession(session);
        Object owner = flowClientOwners(manager).get(session.serverId());
        Field connectInvoking = owner.getClass().getDeclaredField("connectInvoking");
        Field connectClaimed = owner.getClass().getDeclaredField("connectClaimed");
        Field connectDispatch = owner.getClass().getDeclaredField("connectDispatch");
        connectInvoking.setAccessible(true);
        connectClaimed.setAccessible(true);
        connectDispatch.setAccessible(true);
        connectInvoking.setBoolean(owner, true);
        connectClaimed.setBoolean(owner, true);
        connectDispatch.set(owner, new CompletableFuture<>());

        assertTimeoutPreemptively(Duration.ofMillis(250), () -> assertSame(client, manager.activateLiveSession(session)));

        connectInvoking.setBoolean(owner, false);
        manager.closeServerConnection(session.serverId(), () -> {
        });
    }

    private ReSyncFlowClient activateTogether(ReSyncConnectionManager manager, ReSyncLiveServerSession session,
                                               CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return manager.activateLiveSession(session);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private ReSyncFlowClient ensureWithoutConnecting(ReSyncConnectionManager manager, String serverId,
                                                      ReSyncConnectionManager.ReSyncConnectionProfile profile) throws Exception {
        Method ensure = ReSyncConnectionManager.class.getDeclaredMethod("ensureFlowClient", String.class,
            ReSyncConnectionManager.ReSyncConnectionProfile.class, boolean.class, boolean.class);
        ensure.setAccessible(true);
        return (ReSyncFlowClient) ensure.invoke(manager, serverId, profile, false, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flowClientOwners(ReSyncConnectionManager manager) throws ReflectiveOperationException {
        Field owners = ReSyncConnectionManager.class.getDeclaredField("flowClients");
        owners.setAccessible(true);
        return (Map<String, Object>) owners.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ReSyncConnectionManager.ReSyncConnectionProfile> flowProfiles(
        ReSyncConnectionManager manager) throws ReflectiveOperationException {
        Field profiles = ReSyncConnectionManager.class.getDeclaredField("flowProfiles");
        profiles.setAccessible(true);
        return (Map<String, ReSyncConnectionManager.ReSyncConnectionProfile>) profiles.get(manager);
    }

    private static Map<?, ?> pendingRetirements(ReSyncConnectionManager manager) throws ReflectiveOperationException {
        Field retirements = ReSyncConnectionManager.class.getDeclaredField("pendingRetirements");
        retirements.setAccessible(true);
        return (Map<?, ?>) retirements.get(manager);
    }

    private static ExecutorService connectionLifecycleExecutor(ReSyncConnectionManager manager)
        throws ReflectiveOperationException {
        Field executor = ReSyncConnectionManager.class.getDeclaredField("connectionLifecycleExecutor");
        executor.setAccessible(true);
        return (ExecutorService) executor.get(manager);
    }

    private Map<?, ?> ownershipLocks(ReSyncConnectionManager manager) {
        try {
            Field locks = ReSyncConnectionManager.class.getDeclaredField("connectionOwnershipLocks");
            locks.setAccessible(true);
            return (Map<?, ?>) locks.get(manager);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Connection ownership locks could not be inspected", exception);
        }
    }

    private static final class ReentrantHandshakeTransport implements ReSyncFrameTransport {
        private final ReSyncConnectionManager manager;
        private final String serverId;
        private final AtomicInteger sentFrames = new AtomicInteger();
        private final AtomicBoolean callbacksEnabled;
        private final ArrayDeque<Runnable> deferredCallbacks = new ArrayDeque<>();
        private Runnable closeHandler;

        private ReentrantHandshakeTransport(ReSyncConnectionManager manager, String serverId) {
            this(manager, serverId, true);
        }

        private ReentrantHandshakeTransport(ReSyncConnectionManager manager, String serverId,
                                            boolean callbacksEnabled) {
            this.manager = manager;
            this.serverId = serverId;
            this.callbacksEnabled = new AtomicBoolean(callbacksEnabled);
        }

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
            sentFrames.incrementAndGet();
            publishConnected();
        }

        private void publishConnected() {
            try {
                Object owner = flowClientOwners(manager).get(serverId);
                Field clientField = owner.getClass().getDeclaredField("client");
                clientField.setAccessible(true);
                ReSyncFlowClient client = (ReSyncFlowClient) clientField.get(owner);
                Field outboundLock = ReSyncFlowClient.class.getDeclaredField("outboundLock");
                outboundLock.setAccessible(true);
                Field authenticated = ReSyncFlowClient.class.getDeclaredField("authenticated");
                authenticated.setAccessible(true);
                Field activeGeneration = ReSyncFlowClient.class.getDeclaredField("activeTransportGeneration");
                activeGeneration.setAccessible(true);
                Field completedGeneration = ReSyncFlowClient.class.getDeclaredField("completedStartupGeneration");
                completedGeneration.setAccessible(true);
                synchronized (outboundLock.get(client)) {
                    ((AtomicBoolean) authenticated.get(client)).set(true);
                    completedGeneration.setInt(client, activeGeneration.getInt(client));
                }
                Field listener = ReSyncFlowClient.class.getDeclaredField("connectionListener");
                listener.setAccessible(true);
                ((Runnable) listener.get(client)).run();
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public ReSyncFrameTransport.CallbackPublication publishCallback(Runnable callback) {
            if (callback == null) {
                return ReSyncFrameTransport.CallbackPublication.STALE;
            }
            if (!callbacksEnabled.get()) {
                synchronized (deferredCallbacks) {
                    deferredCallbacks.addLast(callback);
                }
                return ReSyncFrameTransport.CallbackPublication.DEFERRED;
            }
            callback.run();
            return ReSyncFrameTransport.CallbackPublication.EXECUTED;
        }

        private void commitCallbacks() {
            List<Runnable> callbacks;
            synchronized (deferredCallbacks) {
                callbacks = new ArrayList<>(deferredCallbacks);
                deferredCallbacks.clear();
            }
            callbacks.forEach(Runnable::run);
        }

        @Override
        public void close() {
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class TestTransport implements ReSyncFrameTransport {
        private final AtomicInteger sentFrames = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch frameSent = new CountDownLatch(1);
        private final boolean reusable;
        private final ServerId peerServerId;
        private volatile Runnable closeHandler;

        private TestTransport() {
            this(true, null);
        }

        private TestTransport(boolean reusable) {
            this(reusable, null);
        }

        private TestTransport(ServerId peerServerId) {
            this(true, peerServerId);
        }

        private TestTransport(boolean reusable, ServerId peerServerId) {
            this.reusable = reusable;
            this.peerServerId = peerServerId;
        }

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
            sentFrames.incrementAndGet();
            frameSent.countDown();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.ofNullable(peerServerId);
        }

        @Override
        public boolean reusableAfterDisconnect() {
            return reusable;
        }

        private void disconnect() {
            if (closeHandler != null) {
                closeHandler.run();
            }
        }
    }

    private static final class BlockingCloseTransport implements ReSyncFrameTransport {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private final AtomicReference<String> closeThread = new AtomicReference<>();
        private volatile Runnable closeHandler;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
            closeThread.set(Thread.currentThread().getName());
            closeEntered.countDown();
            await(allowClose);
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class NonInterruptibleCloseTransport implements ReSyncFrameTransport {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private final CountDownLatch closeExited = new CountDownLatch(1);
        private volatile Runnable closeHandler;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
            closeEntered.countDown();
            boolean interrupted = false;
            while (allowClose.getCount() > 0L) {
                try {
                    allowClose.await();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (closeHandler != null) {
                closeHandler.run();
            }
            closeExited.countDown();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class RetryingCloseTransport implements ReSyncFrameTransport {
        private final AtomicInteger closeCalls = new AtomicInteger();

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
            if (closeCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("close failed once");
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }

    }

    private static final class AlwaysFailingCloseTransport implements ReSyncFrameTransport {
        private final AtomicInteger closeCalls = new AtomicInteger();

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
            closeCalls.incrementAndGet();
            throw new IllegalStateException("close always fails");
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
