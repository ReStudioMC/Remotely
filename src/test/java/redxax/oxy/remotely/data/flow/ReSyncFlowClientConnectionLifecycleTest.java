package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.DesktopRemotelyServerApi;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.DesktopTaskIdentities;
import restudio.rebase.restudio.api.ReStudioApiClient;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientConnectionLifecycleTest {
    private static final short PLUGIN_CHANNEL_ID = 50;
    private static final ServerId TEST_SERVER = new ServerId(UUID.fromString("123e4567-e89b-42d3-a456-426614174000"));

    private static Async.Snapshot asyncSnapshot;
    private static ExecutorService asyncPool;

    @BeforeAll
    static void setUpAll() {
        DesktopTaskIdentities.install();
        DesktopReSyncLocalInstances.install();
        DesktopReSyncDirectSockets.install();
        asyncSnapshot = Async.snapshot();
        asyncPool = Executors.newCachedThreadPool();
        Async.installExecutor(asyncPool::execute, ignored -> Thread.currentThread().interrupt());
    }

    @AfterAll
    static void tearDownAll() {
        if (asyncSnapshot != null) {
            Async.restore(asyncSnapshot);
        }
        if (asyncPool != null) {
            asyncPool.shutdownNow();
        }
    }

    @Test
    void handshakeAuthenticatesBeforeReentrantCapabilityCallbacksButPublishesAfterStartup() throws Exception {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java"));
        int handshake = source.indexOf("private void handleHandshakeResponse");
        int authenticate = source.indexOf("if (!authenticateGeneration(generation))", handshake);
        int reconciliation = source.indexOf("beginTypedReconciliation", handshake);
        int capabilityCache = source.indexOf("manager.cacheServerCapabilities", handshake);
        int publishedConnection = source.indexOf("private boolean isPublishedConnection");

        assertTrue(handshake >= 0);
        assertTrue(authenticate > handshake);
        assertTrue(reconciliation > authenticate);
        assertTrue(capabilityCache > authenticate);
        assertTrue(publishedConnection > capabilityCache);
        assertTrue(source.indexOf("completedStartupGeneration == generation", publishedConnection) > publishedConnection);
    }

    @Test
    void startupGenerationCommitsOnlyAfterReceiptAndWorkspaceSettlement() throws Exception {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java"));
        int start = source.indexOf("private void completeStartupAfterPendingSends");
        int end = source.indexOf("private void reestablishSession", start);
        assertTrue(start >= 0 && end > start);
        String completion = source.substring(start, end);

        int claim = completion.indexOf("completingStartupGeneration = generation");
        int receipt = completion.indexOf("drainCatalogPublicationReceiptOutbox(generation)");
        int workspace = completion.indexOf("connectWorkspaces(generation)");
        int commit = completion.indexOf("completedStartupGeneration = generation");
        assertTrue(claim >= 0 && receipt > claim);
        assertTrue(workspace > receipt && commit > workspace);
        assertTrue(completion.contains("completedStartupGeneration == generation || completingStartupGeneration == generation"));
        assertTrue(completion.contains("if (completingStartupGeneration == generation)"));
    }

    @Test
    void tabProjectionUsesCurrentFlowManagerApiWithoutLinkageSuppression() throws Exception {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java"));
        int dataStart = source.indexOf("private void handleResourceData(ReSyncResourceType type");
        int dataEnd = source.indexOf("private void scheduleLegacyGraphOpen", dataStart);
        int listStart = source.indexOf("private void handleResourceList(ReSyncResourceType type");
        int listEnd = source.indexOf("private void rejectLegacyResourceList", listStart);
        assertTrue(dataStart >= 0 && dataEnd > dataStart && listStart >= 0 && listEnd > listStart);

        String data = source.substring(dataStart, dataEnd);
        String list = source.substring(listStart, listEnd);
        assertTrue(data.contains("cacheResource(fm, type, item);"));
        assertTrue(data.contains("handleResourceDataReceived(fm, type, item);"));
        assertTrue(list.contains("applyServerResourceList(manager, type, ids);"));
        assertFalse(data.contains("NoSuchMethodError"));
        assertFalse(list.contains("NoSuchMethodError"));
    }

    @Test
    void typedResourcePagesAreFencedToTheirTransportGeneration() throws Exception {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java"));

        assertTrue(source.contains("int transportGeneration)"));
        assertTrue(source.contains("!currentTypedResourcePage(pending, generation)"));
        assertTrue(source.contains("pending.transportGeneration() != generation || !isConnectionReadyGeneration(generation)"));
        assertTrue(source.contains("pendingTypedResourceRequests.get(pending.requestId()) == pending"));
        assertTrue(source.contains("synchronized (outboundLock)"));
        assertTrue(source.contains("if (generation < 0 && !mutation)"));
        assertTrue(source.contains("if (pending == null)"));
    }

    @Test
    void openLoadsRetainLogicalIntentAcrossTransientRetirement() throws Exception {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java"));

        assertTrue(source.contains("pendingOpenIntents"));
        assertTrue(source.contains("MAX_RETAINED_OPEN_INTENTS"));
        assertTrue(source.contains("OPEN_INTENT_RETENTION_MILLIS"));
        assertTrue(source.contains("retryPendingOpenLoads(generation)"));
        assertTrue(source.contains("if (hasPendingOpenLoad(intent.type(), intent.resourceId()))"));
        assertTrue(source.contains("failPendingConnectionRequests(\"ReSync Disconnected\", true)"));
        assertTrue(source.contains("failTypedResourceRequest(pending, message, true, retain)"));
        assertTrue(source.contains("if (!retainOpenIntent)"));
        assertTrue(source.contains("transientOpenIntentGeneration(pending.transportGeneration())"));
        assertTrue(source.contains("clearOpenIntents"));
    }

    @Test
    void resourceListRequestDuringRetiredTransportIsDeferredUntilReconnect() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        CountDownLatch firstConnected = new CountDownLatch(1);
        CountDownLatch secondConnected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        client.setConnectionListener(() -> {
            int connection = connections.incrementAndGet();
            if (connection == 1) {
                firstConnected.countDown();
            } else if (connection == 2) {
                secondConnected.countDown();
            }
        });
        client.setDisconnectListener(disconnected::countDown);

        try {
            client.connect().join();
            transport.receive(handshakeFrame());
            assertTrue(firstConnected.await(2, TimeUnit.SECONDS));
            int firstListRequests = resourceListRequestCount(transport, 0);

            transport.disconnect();
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(client::requestGuiList);

            client.connect().join();
            assertTrue(transport.awaitHandshakeRequests(2, 2, TimeUnit.SECONDS));
            transport.receive(handshakeFrame());
            assertTrue(secondConnected.await(2, TimeUnit.SECONDS));
            assertTrue(resourceListRequestCount(transport, 0) > firstListRequests);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void shutdownClientCannotStartAnotherConnection() {
        AtomicInteger requests = new AtomicInteger();
        ReSyncFlowClient client = new ReSyncFlowClient("live:proxy:test", new DesktopRemotelyServerApi(new FailingApi(requests)), null);

        client.shutdown();
        client.connect().join();

        assertEquals(0, requests.get());
    }

    @Test
    void failedShutdownIsReportedAndCanBeRetried() {
        FailingCloseTransport transport = new FailingCloseTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);

        RuntimeException failure = assertThrows(RuntimeException.class, client::shutdown);
        Async<Void> failedAttempt = client.shutdownCompletion();

        assertTrue(failure.toString().contains("close failed once"));
        assertTrue(failedAttempt.isDone() && failedAttempt.failure() != null);
        assertEquals(1, transport.closeCalls.get());

        assertDoesNotThrow(client::shutdown);
        assertDoesNotThrow(client::awaitShutdown);
        assertEquals(2, transport.closeCalls.get());

        assertDoesNotThrow(client::shutdown);
        assertEquals(2, transport.closeCalls.get());
    }

    @Test
    void shutdownClearsJobRetentionStateAlongsidePublicViews() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), new TestTransport(), null,
            new ReSyncCatalogPublicationCache());
        try {
            Method trackGenericJob = ReSyncFlowClient.class.getDeclaredMethod("trackGenericJob", JsonObject.class);
            trackGenericJob.setAccessible(true);
            trackGenericJob.invoke(client, job("active", "running"));
            trackGenericJob.invoke(client, job("terminal", "cancelled"));

            client.shutdown();

            assertTrue(fieldMap(client, "jobs").isEmpty());
            assertTrue(fieldSet(client, "terminalJobNotifications").isEmpty());
            assertTrue(fieldMap(client, "trackedJobStates").isEmpty());
            assertTrue(fieldMap(client, "terminalJobNotificationStates").isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void repeatedConnectionFailureRemainsTransientWhileReconnectIsPending() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch notified = new CountDownLatch(1);
        ReSyncFlowClient client = new ReSyncFlowClient("server", new DesktopRemotelyServerApi(new FailingApi(requests)), null);
        client.setErrorListener((nodeId, message) -> {
            notifications.incrementAndGet();
            notified.countDown();
        });

        try {
            client.connect().join();
            client.connect().join();

            assertFalse(notified.await(250, TimeUnit.MILLISECONDS));
            assertEquals(2, requests.get());
            assertEquals(0, notifications.get());
            assertTrue(client.isReconnectPending());
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTING, client.connectionState());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void handshakeErrorStopsConnectingAndAllowsRetry() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch notified = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        client.setConnectionListener(connected::countDown);
        client.setErrorListener((nodeId, message) -> {
            notifications.incrementAndGet();
            notified.countDown();
        });

        try {
            client.connect().join();

            byte[] message = "Invalid API key".getBytes(StandardCharsets.UTF_8);
            ByteBuffer payload = ByteBuffer.allocate(Integer.BYTES * 2 + message.length);
            payload.putInt(401);
            payload.putInt(message.length);
            payload.put(message);
            transport.receive(new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_ERROR, payload.array(), (short) 0, 1));

            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertEquals(ReSyncFlowClient.ConnectionState.DISCONNECTED, client.connectionState());
            assertEquals(1, notifications.get());

            client.connect().join();
            transport.receive(handshakeFrame());

            assertTrue(connected.await(2, TimeUnit.SECONDS));
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void successfulHandshakeSignalsConnection() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch connected = new CountDownLatch(1);
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        client.setConnectionListener(() -> {
            connections.incrementAndGet();
            connected.countDown();
        });

        try {
            client.connect().join();

            ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 4);
            payload.put((byte) 1);
            payload.putInt(0);
            payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
            payload.putInt(0);
            payload.putInt(0);
            transport.receive(new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE, payload.array(), (short) 0, 1));

            assertTrue(connected.await(2, TimeUnit.SECONDS));
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());
            assertEquals(1, connections.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void responseOwnedHandshakeCannotBeRetiredByConnectTimeout() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), new TestTransport(), null);

        try {
            client.connect().join();
            setIntegerValue(client, "pendingHandshakeGeneration", -1);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Boolean> authentication = CompletableFuture.supplyAsync(() -> {
                await(start);
                return client.authenticateGeneration(1);
            });
            CompletableFuture<Boolean> timeout = CompletableFuture.supplyAsync(() -> {
                await(start);
                return client.claimConnectTimeout(1);
            });

            start.countDown();

            assertTrue(authentication.join());
            assertFalse(timeout.join());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void pendingClaimedAndAuthenticatedGenerationsOwnTheCurrentConnectionUntilTerminalState() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), new TestTransport(), null);

        try {
            client.connect().join();
            assertTrue(client.currentConnectionOwnsTransport());

            setIntegerValue(client, "pendingHandshakeGeneration", -1);
            assertTrue(client.currentConnectionOwnsTransport());

            assertTrue(client.authenticateGeneration(1));
            assertTrue(client.currentConnectionOwnsTransport());
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTING, client.connectionState());

            setField(client, "completedStartupGeneration", 1);
            assertEquals(ReSyncFlowClient.ConnectionState.CONNECTED, client.connectionState());

            setBooleanValue(client, "authenticated", false);
            assertFalse(client.currentConnectionOwnsTransport());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void playerSnapshotWithoutWatchedPlayersFollowsSubscriptionOnEveryConnection() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch disconnected = new CountDownLatch(1);
        client.setConnectionListener(() -> {
            client.requestPlayerTrackingSnapshot();
            connections.incrementAndGet();
        });
        client.setDisconnectListener(disconnected::countDown);
        try {
            for (int connection = 1; connection <= 2; connection++) {
                int offset = transport.sentFrames().size();
                client.connect().join();
                transport.receive(handshakeFrame());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
                while (connections.get() < connection && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(connection, connections.get());
                assertTrue(fieldSet(client, "watchedPlayers").isEmpty());
                List<ReSyncDecodedFrame> frames = transport.sentFrames();
                int subscription = -1;
                int snapshot = -1;
                for (int index = offset; index < frames.size(); index++) {
                    ReSyncDecodedFrame frame = frames.get(index);
                    if (frame.messageType() == ReSyncProtocolContract.MESSAGE_SUBSCRIBE
                        && "player_tracking".equals(subscriptionChannel(frame.payload()))) {
                        subscription = index;
                    }
                    if (frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                        && frame.channel() == ReSyncProtocolContract.CHANNEL_PLAYER_TRACKING_ID
                        && new String(frame.payload(), StandardCharsets.UTF_8).contains("\"action\":\"snapshot\"")) {
                        snapshot = index;
                    }
                }
                assertTrue(subscription >= offset && snapshot > subscription);
                if (connection == 1) {
                    transport.disconnect();
                    assertTrue(disconnected.await(2, TimeUnit.SECONDS));
                }
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    void startupSubscriptionsPrecedeRetainedDataAfterReconnect() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        CountDownLatch firstConnected = new CountDownLatch(1);
        CountDownLatch secondConnected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        client.setConnectionListener(() -> {
            if (connections.incrementAndGet() == 1) {
                firstConnected.countDown();
            } else {
                secondConnected.countDown();
            }
        });
        client.setDisconnectListener(disconnected::countDown);
        addWatchedPlayer(client);
        client.addPluginChannelListener("plugin:test", new ReSyncFlowClient.PluginChannelListener() {
            @Override
            public void onData(String channelId, byte[] payload) {
            }

            @Override
            public void onAvailable(String channelId) {
                client.sendPluginData(channelId, new byte[] {1});
            }
        });
        assertFalse(client.subscribePluginChannel("plugin:test"));

        try {
            client.sendWorldAction(new HashMap<>(Map.of("action", "list")));
            client.connect().join();
            transport.receive(handshakeFrame());

            assertTrue(firstConnected.await(2, TimeUnit.SECONDS));
            assertStartupSubscriptionsPrecedeData(transport, 0);

            transport.disconnect();
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            int reconnectOffset = transport.sentFrames().size();

            client.sendWorldAction(new HashMap<>(Map.of("action", "list")));
            client.connect().join();
            transport.receive(handshakeFrame());

            assertTrue(secondConnected.await(2, TimeUnit.SECONDS));
            assertStartupSubscriptionsPrecedeData(transport, reconnectOffset);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void reconnectReestablishesResourceListRequests() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        CountDownLatch firstConnected = new CountDownLatch(1);
        CountDownLatch secondConnected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        client.setConnectionListener(() -> {
            if (connections.incrementAndGet() == 1) {
                firstConnected.countDown();
            } else {
                secondConnected.countDown();
            }
        });
        client.setDisconnectListener(disconnected::countDown);

        try {
            client.connect().join();
            transport.receive(handshakeFrame());
            assertTrue(firstConnected.await(2, TimeUnit.SECONDS));
            int firstListRequests = resourceListRequestCount(transport, 0);
            assertTrue(firstListRequests > 0);

            transport.disconnect();
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            client.connect().join();
            transport.receive(handshakeFrame());
            assertTrue(secondConnected.await(2, TimeUnit.SECONDS));
            assertTrue(resourceListRequestCount(transport, 0) > firstListRequests);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void typedPublicationRehydratesAfterSameGenerationReconciliation() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        CountDownLatch connected = new CountDownLatch(1);
        client.setConnectionListener(connected::countDown);

        try {
            client.connect().join();
            transport.receive(handshakeFrame());
            assertTrue(connected.await(2, TimeUnit.SECONDS));
            int generation = intField(client, "activeTransportGeneration");
            setField(client, "completedStartupGeneration", generation);
            setIntegerValue(client, "sessionHydrationGeneration", generation);
            setIntegerValue(client, "sessionPublicationHydrationGeneration", -1);
            setField(client, "legacyCompatibilityProven", true);
            setField(client, "typedCatalogAuthorityAdvertised", false);
            setField(client, "catalogAuthority", ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION);

            invokeReestablishSession(client, generation);

            assertEquals(generation, getIntegerValue(client, "sessionPublicationHydrationGeneration"));
            invokeReestablishSession(client, generation);
            assertEquals(generation, getIntegerValue(client, "sessionPublicationHydrationGeneration"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void queuedWorldActionSnapshotsCallerPayloadWithoutMutatingIt() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        CountDownLatch connected = new CountDownLatch(1);
        client.setConnectionListener(connected::countDown);
        List<String> targets = new ArrayList<>(List.of("before"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", "deleteWorld");
        request.put("targets", targets);

        try {
            client.sendWorldAction(request);
            assertFalse(request.containsKey("requestId"));
            targets.set(0, "after");
            request.put("late", true);
            client.connect().join();
            transport.receive(handshakeFrame());

            assertTrue(connected.await(2, TimeUnit.SECONDS));
            ReSyncDecodedFrame frame = transport.sentFrames().stream()
                .filter(sent -> sent.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                    && sent.channel() == ReSyncProtocolContract.CHANNEL_WORLD_MANAGEMENT_ID)
                .findFirst().orElseThrow();
            JsonObject delivered = JsonParser.parseString(new String(frame.payload(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("before", delivered.getAsJsonArray("targets").get(0).getAsString());
            assertTrue(delivered.has("requestId"));
            assertFalse(delivered.has("late"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void callbackCannotRepopulateClientOrExposedSubsystemsAfterShutdown() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean workspaceJoined = new AtomicBoolean(true);
        AtomicBoolean collaborationPublished = new AtomicBoolean(true);
        AtomicReference<Async<JsonObject>> playerControl = new AtomicReference<>();
        client.setConnectionListener(() -> {
            playerControl.set(client.requestPlayerControl("inspect", null, null));
            client.shutdown();
            client.requestMessageLog(0, 20, "", "");
            workspaceJoined.set(client.workspaces().join("flow", "example", new NoopWorkspaceListener()));
            collaborationPublished.set(client.collaboration().publishPresence("flow", "example", "graph", 0, 0, true, false));
            completed.countDown();
        });

        client.connect().join();
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 4);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        transport.receive(new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE, payload.array(), (short) 0, 1));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(playerControl.get().isDone() && playerControl.get().failure() != null);
        assertFalse(workspaceJoined.get());
        assertFalse(collaborationPublished.get());
        Field pendingSends = ReSyncFlowClient.class.getDeclaredField("pendingSends");
        pendingSends.setAccessible(true);
        assertEquals(0, ((Collection<?>) pendingSends.get(client)).size());
    }

    @Test
    void shutdownFromConnectionCallbackCompletesAfterCallbackReturns() throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(TEST_SERVER.canonicalText(), transport, null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch shutdownReturned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Async<Void>> completion = new AtomicReference<>();
        client.setConnectionListener(() -> {
            entered.countDown();
            client.shutdown();
            completion.set(client.shutdownCompletion());
            shutdownReturned.countDown();
            await(release);
        });

        try {
            client.connect().join();
            ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 4);
            payload.put((byte) 1);
            payload.putInt(0);
            payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
            payload.putInt(0);
            payload.putInt(0);
            transport.receive(new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
                payload.array(), (short) 0, 1));

            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(shutdownReturned.await(2, TimeUnit.SECONDS));
            Async<Void> shutdown = completion.get();
            assertTrue(shutdown != null);
            assertFalse(shutdown.isDone());
            CompletableFuture<Void> awaiter = CompletableFuture.runAsync(client::awaitShutdown);
            assertFalse(awaiter.isDone());

            release.countDown();
            shutdown.join();
            awaiter.get(2, TimeUnit.SECONDS);
            assertTrue(shutdown.isDone());
        } finally {
            release.countDown();
            client.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static JsonObject job(String id, String status) {
        JsonObject job = new JsonObject();
        job.addProperty("jobId", id);
        job.addProperty("status", status);
        return job;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> fieldMap(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Set<?> fieldSet(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Set<?>) field.get(client);
    }

    private static int intField(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(client);
    }

    private static int getIntegerValue(ReSyncFlowClient client, String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        Object target = field.get(client);
        if (target instanceof BrowserSafeState.IntegerValue integerValue) {
            return integerValue.get();
        } else if (target instanceof AtomicInteger atomic) {
            return atomic.get();
        }
        return field.getInt(client);
    }

    private static void setIntegerValue(ReSyncFlowClient client, String name, int value) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        Object target = field.get(client);
        if (target instanceof BrowserSafeState.IntegerValue integerValue) {
            integerValue.set(value);
        } else if (target instanceof AtomicInteger atomic) {
            atomic.set(value);
        }
    }

    private static void setBooleanValue(ReSyncFlowClient client, String name, boolean value) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        Object target = field.get(client);
        if (target instanceof BrowserSafeState.BooleanValue booleanValue) {
            booleanValue.set(value);
        } else if (target instanceof AtomicBoolean atomic) {
            atomic.set(value);
        }
    }

    private static void setField(ReSyncFlowClient client, String name, Object value) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(client, value);
    }

    private static void invokeReestablishSession(ReSyncFlowClient client, int generation) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("reestablishSession", int.class);
        method.setAccessible(true);
        method.invoke(client, generation);
    }

    @SuppressWarnings("unchecked")
    private static void addWatchedPlayer(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("watchedPlayers");
        field.setAccessible(true);
        ((Set<UUID>) field.get(client)).add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
    }

    private static void assertStartupSubscriptionsPrecedeData(TestTransport transport, int offset) {
        List<ReSyncDecodedFrame> frames = transport.sentFrames();
        List<String> subscriptions = new ArrayList<>();
        int firstData = -1;
        int playerSubscription = -1;
        int playerWatch = -1;
        int pluginSubscription = -1;
        int pluginData = -1;
        for (int i = offset; i < frames.size(); i++) {
            ReSyncDecodedFrame frame = frames.get(i);
            if (frame.messageType() == ReSyncProtocolContract.MESSAGE_SUBSCRIBE) {
                String channel = subscriptionChannel(frame.payload());
                subscriptions.add(channel);
                if ("player_tracking".equals(channel)) {
                    playerSubscription = i;
                }
                if ("plugin:test".equals(channel)) {
                    pluginSubscription = i;
                }
            } else if (frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA) {
                if (firstData < 0) {
                    firstData = i;
                }
                if (frame.channel() == ReSyncProtocolContract.CHANNEL_PLAYER_TRACKING_ID
                    && new String(frame.payload(), StandardCharsets.UTF_8).contains("\"action\":\"watch\"")) {
                    playerWatch = i;
                }
                if (frame.channel() == PLUGIN_CHANNEL_ID) {
                    pluginData = i;
                }
            }
        }
        assertTrue(subscriptions.contains("flow"));
        assertTrue(subscriptions.contains("world_management"));
        assertTrue(subscriptions.contains("worldgen"));
        assertTrue(subscriptions.contains("player_tracking"));
        assertTrue(subscriptions.contains("plugin:test"));
        assertTrue(firstData >= 0);
        for (int i = offset; i < frames.size(); i++) {
            if (frames.get(i).messageType() == ReSyncProtocolContract.MESSAGE_SUBSCRIBE) {
                assertTrue(i < firstData);
            }
        }
        assertTrue(playerSubscription >= 0 && playerSubscription < firstData);
        assertTrue(pluginSubscription >= 0 && pluginSubscription < firstData);
        assertTrue(playerWatch >= 0 && playerSubscription < playerWatch);
        assertTrue(pluginData >= 0 && pluginSubscription < pluginData);
    }

    private static String subscriptionChannel(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int length = buffer.getInt();
        byte[] channel = new byte[length];
        buffer.get(channel);
        return new String(channel, StandardCharsets.UTF_8);
    }

    private static int resourceListRequestCount(TestTransport transport, int offset) {
        return (int) transport.sentFrames().stream().skip(offset)
            .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA
                && frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID
                && frame.payload().length == 1
                && List.of(ReSyncResourceType.values()).stream()
                    .anyMatch(type -> type.enabled() && frame.payload()[0] == type.listRequestByte()))
            .count();
    }

    private static byte[] handshakeFrame() {
        List<String> channels = List.of("flow", "world_management", "worldgen", "player_tracking", "plugin:test");
        int payloadLength = 1 + Integer.BYTES * 6;
        for (String channel : channels) {
            payloadLength += Integer.BYTES + channel.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES;
        }
        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(channels.size());
        short[] channelIds = {
            ReSyncProtocolContract.CHANNEL_FLOW_ID,
            ReSyncProtocolContract.CHANNEL_WORLD_MANAGEMENT_ID,
            ReSyncProtocolContract.CHANNEL_WORLDGEN_ID,
            ReSyncProtocolContract.CHANNEL_PLAYER_TRACKING_ID,
            PLUGIN_CHANNEL_ID
        };
        for (int i = 0; i < channels.size(); i++) {
            byte[] channel = channels.get(i).getBytes(StandardCharsets.UTF_8);
            payload.putInt(channel.length);
            payload.put(channel);
            payload.putInt(channelIds[i]);
        }
        return new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
            payload.array(), (short) 0, 1);
    }

    private static final class FailingApi extends ReStudioApiClient {
        private final AtomicInteger requests;

        private FailingApi(AtomicInteger requests) {
            this.requests = requests;
        }

        @Override
        public CompletableFuture<ServerModels.ReSyncConfig> getReSyncConfig(String serverId) {
            requests.incrementAndGet();
            return CompletableFuture.failedFuture(new ApiException(404, "server_not_found", "Server not found: " + serverId));
        }
    }

    private static final class TestTransport implements ReSyncFrameTransport {
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private final List<ReSyncDecodedFrame> sentFrames = new CopyOnWriteArrayList<>();
        private Consumer<byte[]> frameHandler;
        private Runnable closeHandler;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
            frameHandler = handler;
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
            sentFrames.add(codec.decode(frame, null));
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

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.of(TEST_SERVER);
        }

        private void receive(byte[] frame) {
            frameHandler.accept(frame);
        }

        private void disconnect() {
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        private List<ReSyncDecodedFrame> sentFrames() {
            return List.copyOf(sentFrames);
        }

        private boolean awaitHandshakeRequests(int expected, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                long count = sentFrames.stream()
                    .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_HANDSHAKE_REQUEST)
                    .count();
                if (count >= expected) {
                    return true;
                }
                Thread.sleep(1L);
            }
            return false;
        }
    }

    private static final class FailingCloseTransport implements ReSyncFrameTransport {
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

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.of(TEST_SERVER);
        }
    }

    private static final class NoopWorkspaceListener implements ReSyncWorkspaceClient.Listener {
        @Override
        public void onSnapshot(ReSyncWorkspaceClient.Snapshot snapshot) {
        }

        @Override
        public void onOperation(ReSyncWorkspaceClient.Operation operation, boolean own) {
        }

        @Override
        public void onAwareness(ReSyncWorkspaceClient.Awareness awareness) {
        }

        @Override
        public void onResync(String reason) {
        }
    }
}
