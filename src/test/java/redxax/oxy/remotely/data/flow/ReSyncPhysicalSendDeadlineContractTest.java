package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncPhysicalSendDeadlineContractTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("91819181-9181-4181-8181-918191819181"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");

    @Test
    void defaultTransportPublishesSuccessOnlyAfterAcceptance() {
        ImmediateTransport accepted = new ImmediateTransport(true);
        AtomicReference<ReSyncFrameTransport.SendResult> result = new AtomicReference<>();

        assertTrue(accepted.trySend(new byte[] {1, 2, 3}, result::set));
        assertEquals(1, accepted.sendCount.get());
        ReSyncFrameTransport.SendReceipt receipt = assertInstanceOf(ReSyncFrameTransport.SendReceipt.class,
            result.get());
        assertTrue(receipt.delivered());
        assertEquals(3L, receipt.byteCount());

        ImmediateTransport rejected = new ImmediateTransport(false);
        result.set(null);
        assertFalse(rejected.trySend(new byte[] {4}, result::set));
        assertEquals(0, rejected.sendCount.get());
        assertNull(result.get());
    }

    @Test
    void multiFrameDeliveryPublishesExactlyOnceAfterTheFinalFrame() {
        List<ReSyncFrameTransport.SendResult> results = new ArrayList<>();
        ReSyncFrameTransport.SendTracker tracker = new ReSyncFrameTransport.SendTracker(100L, 3, 90L, 7, 700L,
            results::add);
        tracker.encoded(3, 120L);

        assertFalse(tracker.sentFrame());
        assertFalse(tracker.sentFrame());
        assertTrue(results.isEmpty());
        assertTrue(tracker.sentFrame());
        assertEquals(1, results.size());
        ReSyncFrameTransport.SendReceipt receipt = assertInstanceOf(ReSyncFrameTransport.SendReceipt.class,
            results.getFirst());
        assertEquals(3, receipt.frameCount());
        assertEquals(120L, receipt.byteCount());
        assertEquals(7, receipt.queuedFrameCount());
        assertFalse(tracker.sentFrame());
        assertEquals(1, results.size());
    }

    @Test
    void deliveryFailureSettlesCloseAndAdapterFailureExactlyOnce() {
        List<ReSyncFrameTransport.SendResult> closeResults = new ArrayList<>();
        ReSyncFrameTransport.SendTracker close = new ReSyncFrameTransport.SendTracker(100L, 2, 64L, 0, 0L,
            closeResults::add);
        assertTrue(close.fail("transport_closed"));
        assertFalse(close.fail("adapter_send_failed"));
        assertFalse(close.sentFrame());
        ReSyncFrameTransport.SendFailure closeFailure = assertInstanceOf(ReSyncFrameTransport.SendFailure.class,
            closeResults.getFirst());
        assertEquals("transport_closed", closeFailure.reason());
        assertEquals(1, closeResults.size());

        List<ReSyncFrameTransport.SendResult> adapterResults = new ArrayList<>();
        ReSyncFrameTransport.SendTracker adapter = new ReSyncFrameTransport.SendTracker(100L, 1, 32L, 2, 96L,
            adapterResults::add);
        assertTrue(adapter.fail("adapter_send_failed"));
        ReSyncFrameTransport.SendFailure adapterFailure = assertInstanceOf(ReSyncFrameTransport.SendFailure.class,
            adapterResults.getFirst());
        assertEquals("adapter_send_failed", adapterFailure.reason());
        assertFalse(adapterFailure.delivered());
    }

    @Test
    void terminalFailureClaimClosesThePrePublicationRaceWindow() {
        List<ReSyncFrameTransport.SendResult> results = new ArrayList<>();
        ReSyncFrameTransport.SendTracker tracker = new ReSyncFrameTransport.SendTracker(100L, 1, 32L, 0, 0L,
            results::add);

        ReSyncFrameTransport.SendResult failure = tracker.claimFailure("transport_closed");
        assertInstanceOf(ReSyncFrameTransport.SendFailure.class, failure);
        assertTrue(tracker.completed());
        assertTrue(results.isEmpty());

        tracker.encoded(3, 96L);
        assertNull(tracker.claimSentFrame());
        tracker.publish(failure);
        tracker.publish(failure);

        assertEquals(1, results.size());
        assertEquals("transport_closed", results.getFirst().reason());
    }

    @Test
    void physicalSuccessClaimWinsCloseAndExpiryBeforePublication() {
        List<ReSyncFrameTransport.SendResult> results = new ArrayList<>();
        ReSyncFrameTransport.SendTracker tracker = new ReSyncFrameTransport.SendTracker(100L, 1, 32L, 0, 0L,
            results::add);

        ReSyncFrameTransport.SendResult receipt = tracker.claimSentFrame();
        assertInstanceOf(ReSyncFrameTransport.SendReceipt.class, receipt);
        assertNull(tracker.claimFailure("transport_closed"));
        assertNull(tracker.claimFailure("delivery_timeout"));
        assertTrue(results.isEmpty());
        tracker.publish(receipt);

        assertEquals(1, results.size());
        assertTrue(results.getFirst().delivered());
    }

    @Test
    void callbackFailureCannotTurnPhysicalSuccessIntoSendFailure() {
        ReSyncFrameTransport.SendTracker tracker = new ReSyncFrameTransport.SendTracker(100L, 1, 32L, 0, 0L,
            ignored -> {
                throw new IllegalStateException("callback failed");
            });
        assertDoesNotThrow(() -> assertTrue(tracker.sentFrame()));
        assertTrue(tracker.completed());

        ImmediateTransport transport = new ImmediateTransport(true);
        assertDoesNotThrow(() -> assertTrue(transport.trySend(new byte[] {1}, ignored -> {
            throw new IllegalStateException("callback failed");
        })));
        assertEquals(1, transport.sendCount.get());
    }

    @Test
    void queueDeliveryTimeoutIsBoundedWithoutStartingAResponseReceipt() {
        List<ReSyncFrameTransport.SendResult> results = new ArrayList<>();
        long enqueued = 1_000L;
        long timeout = TimeUnit.SECONDS.toNanos(30L);
        ReSyncFrameTransport.SendTracker tracker = new ReSyncFrameTransport.SendTracker(enqueued, 1, 16L, 4, 128L,
            results::add);

        assertFalse(tracker.expired(enqueued + timeout - 1L, timeout));
        assertTrue(results.isEmpty());
        assertTrue(tracker.expired(enqueued + timeout, timeout));
        assertTrue(tracker.fail("delivery_timeout"));
        ReSyncFrameTransport.SendFailure failure = assertInstanceOf(ReSyncFrameTransport.SendFailure.class,
            results.getFirst());
        assertEquals("delivery_timeout", failure.reason());
        assertFalse(failure.delivered());
    }

    @Test
    void coreAndTypedAttemptFencesRejectDuplicateOrStaleReceipts() throws Exception {
        Object core = newCoreRecovery();
        Class<?> coreType = core.getClass();
        Method coreDispatched = method(coreType, "dispatched");
        Method coreSettled = method(coreType, "transportSettled", int.class);
        Method coreReplay = method(coreType, "claimReplay", int.class);
        assertEquals(1, coreDispatched.invoke(core));
        assertTrue((Boolean) coreSettled.invoke(core, 1));
        assertFalse((Boolean) coreSettled.invoke(core, 1));
        assertTrue((Boolean) coreReplay.invoke(core, 1));
        assertEquals(2, coreDispatched.invoke(core));
        assertFalse((Boolean) coreSettled.invoke(core, 1));
        assertTrue((Boolean) coreSettled.invoke(core, 2));

        Object typed = newTypedDelivery();
        Class<?> typedType = typed.getClass();
        Method typedDispatched = method(typedType, "dispatched");
        Method typedSettled = method(typedType, "transportSettled", int.class);
        assertEquals(1, typedDispatched.invoke(typed));
        assertFalse((Boolean) typedSettled.invoke(typed, 2));
        assertTrue((Boolean) typedSettled.invoke(typed, 1));
        assertFalse((Boolean) typedSettled.invoke(typed, 1));
    }

    @Test
    void genericTypedRequestWaitsForDeliveryAndRetiresOnTransportFailure() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        UUID requestId = UUID.fromString("92829282-9282-4282-8282-928292829282");
        try {
            setField(client, "activeTransportGeneration", 1);
            Object pending = newTypedLoad(client, requestId);
            Object delivery = newTypedDelivery();
            assertEquals(1, method(delivery.getClass(), "dispatched").invoke(delivery));
            typedDeliveries(client).put(requestId, delivery);
            ReSyncFrameTransport.SendTracker tracker = new ReSyncFrameTransport.SendTracker(System.nanoTime(), 2,
                64L, 3, 96L, result -> invokeTypedSettlement(client, pending, delivery, 1, result));

            assertTrue(pendingTyped(client).containsKey(requestId));
            assertFalse(tracker.sentFrame());
            assertTrue(pendingTyped(client).containsKey(requestId));
            assertTrue(tracker.fail("adapter_send_failed"));
            assertFalse(pendingTyped(client).containsKey(requestId));
            assertFalse(typedDeliveries(client).containsKey(requestId));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void staleGenerationReceiptCannotArmGenericResponseDeadline() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        UUID requestId = UUID.fromString("93839383-9383-4383-8383-938393839383");
        try {
            setField(client, "activeTransportGeneration", 1);
            Object pending = newTypedLoad(client, requestId);
            Object delivery = newTypedDelivery();
            assertEquals(1, method(delivery.getClass(), "dispatched").invoke(delivery));
            typedDeliveries(client).put(requestId, delivery);
            setField(client, "activeTransportGeneration", 2);

            invokeTypedSettlement(client, pending, delivery, 1,
                ReSyncFrameTransport.SendReceipt.immediate(System.nanoTime(), 32));

            assertFalse(pendingTyped(client).containsKey(requestId));
            assertFalse(typedDeliveries(client).containsKey(requestId));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void staleGenerationDeliveredMutationRetainsExactIdentity() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        UUID requestId = UUID.fromString("97879787-9787-4787-8787-978797879787");
        try {
            setField(client, "activeTransportGeneration", 1);
            ServerResourceLocator resource = resource(ReSyncResourceType.CUSTOM_CONTENT.typeId(), "stale-delete");
            Object pending = newTypedRequest(client, requestId,
                new ResourceDeleteRequest(resource, 1L, UUID.randomUUID()));
            Object delivery = newTypedDelivery();
            assertEquals(1, method(delivery.getClass(), "dispatched").invoke(delivery));
            typedDeliveries(client).put(requestId, delivery);
            setField(client, "activeTransportGeneration", 2);

            invokeTypedSettlement(client, pending, delivery, 1,
                ReSyncFrameTransport.SendReceipt.immediate(System.nanoTime(), 32));

            assertFalse(pendingTyped(client).containsKey(requestId));
            assertEquals(pending, retainedTypedMutations(client).get(requestId));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void genericResponseDeadlineRemainsInactiveUntilPhysicalDelivery() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        UUID requestId = UUID.fromString("94849484-9484-4484-8484-948494849484");
        try {
            setField(client, "activeTransportGeneration", 1);
            Object pending = newTypedLoad(client, requestId);
            Object delivery = newTypedDelivery();
            assertEquals(1, method(delivery.getClass(), "dispatched").invoke(delivery));
            typedDeliveries(client).put(requestId, delivery);

            invokeTypedDeadline(client, pending, delivery, 1);
            assertTrue(pendingTyped(client).containsKey(requestId));

            invokeTypedSettlement(client, pending, delivery, 1,
                ReSyncFrameTransport.SendReceipt.immediate(System.nanoTime(), 32));
            assertTrue(pendingTyped(client).containsKey(requestId));
            invokeTypedDeadline(client, pending, delivery, 1);
            assertFalse(pendingTyped(client).containsKey(requestId));
            assertFalse(typedDeliveries(client).containsKey(requestId));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void transportFailureSettlesEveryGenericTypedOperation() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        try {
            setField(client, "activeTransportGeneration", 1);
            int index = 0;
            for (ResourceOperation operation : genericOperations()) {
                UUID requestId = new UUID(0x9585958595854585L, 0x8585958595850000L + index++);
                Object pending = newTypedRequest(client, requestId, operation);
                Object delivery = newTypedDelivery();
                assertEquals(1, method(delivery.getClass(), "dispatched").invoke(delivery));
                typedDeliveries(client).put(requestId, delivery);

                invokeTypedSettlement(client, pending, delivery, 1,
                    new ReSyncFrameTransport.SendFailure(System.nanoTime(), 1L, 1, 32L, 0, 0L,
                        "adapter_send_failed"));

                assertFalse(pendingTyped(client).containsKey(requestId), operation.kind().name());
                assertFalse(typedDeliveries(client).containsKey(requestId), operation.kind().name());
                boolean retained = List.of(ResourceOperationKind.CREATE, ResourceOperationKind.SAVE,
                    ResourceOperationKind.DELETE, ResourceOperationKind.ACTIVATE, ResourceOperationKind.DUPLICATE)
                    .contains(operation.kind());
                assertEquals(retained, retainedTypedMutations(client).containsKey(requestId), operation.kind().name());
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    void ordinaryCoreSaveTransportFailureRetainsTheExactSubmittedSnapshot() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        UUID requestId = UUID.fromString("99899989-9989-4989-8989-998999899989");
        UUID mutationId = UUID.fromString("90809080-9080-4080-8080-908090809080");
        try {
            setField(client, "activeTransportGeneration", 1);
            ServerResourceLocator resource = resource(ReSyncResourceType.FLOW.typeId(), "ordinary-core-save");
            var payload = ResourcePayloadCodecs.json().canonicalize(Map.of("id", "submitted-snapshot"));
            ResourceSaveRequest<?> operation = new ResourceSaveRequest<>(resource, 3L, payload, mutationId);
            Object pending = newCoreRequest(client, requestId, resource, mutationId, operation.payloadHash());
            Object recovery = newCoreRecovery(operation, operation.payloadHash(), true);
            coreRecoveries(client).put(requestId, recovery);
            int attempt = (Integer) method(recovery.getClass(), "dispatched").invoke(recovery);

            invokeCoreSettlement(client, pending, recovery, attempt,
                new ReSyncFrameTransport.SendFailure(System.nanoTime(), 1L, 1, 32L, 0, 0L,
                    "adapter_send_failed"));

            assertFalse(pendingCore(client).containsKey(requestId));
            assertFalse(coreRecoveries(client).containsKey(requestId));
            Object retained = retainedCoreMutations(client).get(requestId);
            assertTrue(retained != null);
            assertEquals(pending, method(retained.getClass(), "pending").invoke(retained));
            assertEquals(operation, method(retained.getClass(), "operation").invoke(retained));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void connectionRetirementCannotSplitCoreOrTypedMutationRetentionOwnership() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        UUID coreRequestId = UUID.fromString("91809180-9180-4180-8180-918091809180");
        UUID typedRequestId = UUID.fromString("92809280-9280-4280-8280-928092809280");
        try {
            setField(client, "activeTransportGeneration", 1);
            var payload = ResourcePayloadCodecs.json().canonicalize(Map.of("id", "retention-race"));
            ServerResourceLocator coreResource = resource(ReSyncResourceType.FLOW.typeId(), "core-retention-race");
            ResourceSaveRequest<?> coreOperation = new ResourceSaveRequest<>(coreResource, 3L, payload,
                UUID.randomUUID());
            Object corePending = newCoreRequest(client, coreRequestId, coreResource, coreOperation.mutationId(),
                coreOperation.payloadHash());
            Object coreRecovery = newCoreRecovery(coreOperation, coreOperation.payloadHash(), true);
            coreRecoveries(client).put(coreRequestId, coreRecovery);

            ServerResourceLocator typedResource = resource(ReSyncResourceType.CUSTOM_CONTENT.typeId(),
                "typed-retention-race");
            Object typedPending = newTypedRequest(client, typedRequestId,
                new ResourceSaveRequest<>(typedResource, 4L, payload, UUID.randomUUID()));

            Object retentionFence = field(client, "transportMutationRetentionLock");
            CountDownLatch coreStarted = new CountDownLatch(1);
            AtomicReference<Boolean> coreCleanup = new AtomicReference<>();
            Thread coreFailure = new Thread(() -> {
                coreStarted.countDown();
                coreCleanup.set(invokeCoreFailure(client, corePending));
            });
            synchronized (retentionFence) {
                coreFailure.start();
                assertTrue(coreStarted.await(1L, TimeUnit.SECONDS));
                assertTrue(invokeCoreRetention(client, corePending, coreRecovery));
            }
            coreFailure.join(TimeUnit.SECONDS.toMillis(1L));

            CountDownLatch typedStarted = new CountDownLatch(1);
            Thread typedFailure = new Thread(() -> {
                typedStarted.countDown();
                invokeTypedFailure(client, typedPending);
            });
            synchronized (retentionFence) {
                typedFailure.start();
                assertTrue(typedStarted.await(1L, TimeUnit.SECONDS));
                assertTrue(invokeTypedRetention(client, typedPending));
            }
            typedFailure.join(TimeUnit.SECONDS.toMillis(1L));

            assertFalse(coreFailure.isAlive());
            assertFalse(typedFailure.isAlive());
            assertTrue(coreCleanup.get());
            assertTrue(retainedCoreMutations(client).containsKey(coreRequestId));
            assertTrue(retainedTypedMutations(client).containsKey(typedRequestId));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void deliveredMutationResponseLossRetainsIdentityAfterOneExactReplay() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        UUID requestId = UUID.fromString("98889888-9888-4888-8888-988898889888");
        try {
            setField(client, "activeTransportGeneration", 1);
            ServerResourceLocator resource = resource(ReSyncResourceType.CUSTOM_CONTENT.typeId(), "deadline-delete");
            Object pending = newTypedRequest(client, requestId,
                new ResourceDeleteRequest(resource, 1L, UUID.randomUUID()));
            Object delivery = newTypedDelivery();
            Method dispatched = method(delivery.getClass(), "dispatched");
            Method settled = method(delivery.getClass(), "transportSettled", int.class);
            Method replay = method(delivery.getClass(), "claimReplay", int.class);
            assertEquals(1, dispatched.invoke(delivery));
            assertTrue((Boolean) settled.invoke(delivery, 1));
            assertTrue((Boolean) replay.invoke(delivery, 1));
            assertEquals(2, dispatched.invoke(delivery));
            assertTrue((Boolean) settled.invoke(delivery, 2));
            assertFalse((Boolean) replay.invoke(delivery, 2));
            typedDeliveries(client).put(requestId, delivery);

            invokeTypedDeadline(client, pending, delivery, 2);

            assertFalse(pendingTyped(client).containsKey(requestId));
            assertEquals(pending, retainedTypedMutations(client).get(requestId));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void vanillaBridgeRoutesEveryAcceptedTerminalPathThroughTheTracker() throws Exception {
        String source = Files.readString(Path.of("RemotelyMod", "src", "main", "java", "redxax", "oxy",
            "remotely", "resync", "bridge", "ReSyncVanillaBridgeManager.java")).replace("\r\n", "\n");
        String flow = Files.readString(Path.of("src", "main", "java", "redxax", "oxy", "remotely", "data",
            "flow", "ReSyncFlowClient.java")).replace("\r\n", "\n");
        String connections = Files.readString(Path.of("src", "main", "java", "redxax", "oxy", "remotely", "data",
            "flow", "ReSyncConnectionManager.java")).replace("\r\n", "\n");

        assertTrue(source.contains("failOutboundSend(send, \"encoding_failed\")"));
        assertTrue(source.contains("failOutboundSendLocked(packet.send(), \"adapter_send_failed\")"));
        assertTrue(source.contains("failOutboundSendsLocked(this, \"transport_closed\")"));
        assertTrue(source.contains("send.tracker.expired(nowNanos, MAX_OUTBOUND_DELIVERY_NANOS)"));
        assertTrue(source.contains("if (failOutboundSend(send, \"delivery_timeout\"))"));
        assertFalse(source.contains("if (failOutboundSend(send, \"delivery_timeout\")) {\n                requestRestart(send.generation);"));
        assertTrue(source.contains("MAX_OUTBOUND_PACKETS_PER_TICK = 32"));
        assertTrue(source.contains("MAX_OUTBOUND_BYTES_PER_TICK = 768 * 1_024"));
        assertTrue(connections.contains("\"ReSync Connection Failed\".equals(normalized)"));
        assertTrue(connections.contains("live_session_transient_failure_suppressed"));
        assertTrue(source.contains("restartRequiredGeneration.compareAndSet(-1L, generation)"));
        assertTrue(source.contains("LOGGER.warn(\"ReSync bridge restart requested: {}\", reason)"));
        assertTrue(source.contains("!pendingOutboundSends.contains(send)"));
        assertTrue(source.contains("public boolean reusableAfterDisconnect() {\n            return true;"));
        int liveAudit = source.indexOf("public boolean ensureLiveSessionActive()");
        int liveServerId = source.indexOf("public String getLiveServerId()", liveAudit);
        assertTrue(liveAudit >= 0 && liveServerId > liveAudit);
        String liveAuditBody = source.substring(liveAudit, liveServerId);
        assertTrue(liveAuditBody.contains("liveSessionActivated = false;\n            scheduleActivation("));
        assertFalse(liveAuditBody.contains("requestRestart(generation);\n            return false;\n        }\n        scheduleActivation"));
        int reset = source.indexOf("private void beginBridgeAttempt(long nextHello)");
        int closeSend = source.indexOf("private void sendCloseAsync(", reset);
        assertTrue(reset >= 0 && closeSend > reset);
        String resetBody = source.substring(reset, closeSend);
        int admissionFence = resetBody.indexOf("synchronized (sequence)");
        int snapshot = resetBody.indexOf("failAllOutboundSendsLocked(\"bridge_restarted\")");
        int generationReset = resetBody.indexOf("bridgeGeneration.incrementAndGet()");
        int failurePublication = resetBody.indexOf("publishSendResults(failures)");
        assertTrue(admissionFence >= 0 && snapshot > admissionFence);
        assertTrue(generationReset > snapshot);
        assertTrue(failurePublication > generationReset);
        int failureClaim = source.indexOf("private SendPublication failOutboundSendLocked(");
        int publishHelper = source.indexOf("private void publishSendResults(", failureClaim);
        assertTrue(failureClaim >= 0 && publishHelper > failureClaim);
        String failureClaimBody = source.substring(failureClaim, publishHelper);
        assertTrue(failureClaimBody.indexOf("pendingOutboundSends.remove(send)")
            < failureClaimBody.indexOf("send.tracker.claimFailure(reason)"));
        int bridgeSend = source.indexOf("private OutboundDelivery sendOutboundPacket(OutboundPacket packet)");
        int closeDispatch = source.indexOf("private boolean dispatchCloseHandler()", bridgeSend);
        assertTrue(bridgeSend >= 0 && closeDispatch > bridgeSend);
        String bridgeSendBody = source.substring(bridgeSend, closeDispatch);
        assertTrue(bridgeSendBody.indexOf("synchronized (activationFence)")
            < bridgeSendBody.indexOf("synchronized (outboundLock)"));
        assertTrue(bridgeSendBody.indexOf("synchronized (outboundLock)")
            < bridgeSendBody.indexOf("deliverOutboundPacketLocked(packet, outboundCurrent())"));
        int close = source.indexOf("public void close()", source.indexOf("private class BridgeTransport"));
        int open = source.indexOf("public boolean isOpen()", close);
        assertTrue(close >= 0 && open > close);
        String closeBody = source.substring(close, open);
        assertTrue(closeBody.indexOf("synchronized (activationFence)")
            < closeBody.indexOf("synchronized (outboundLock)"));
        assertTrue(closeBody.indexOf("synchronized (outboundLock)")
            < closeBody.indexOf("failOutboundSendsLocked(this, \"transport_closed\")"));
        int dispatch = flow.indexOf("private boolean dispatchTypedResourceRequest(PendingTypedResourceRequest pending,");
        int receipt = flow.indexOf("private void typedRequestTransportSettled(", dispatch);
        assertTrue(dispatch >= 0 && receipt > dispatch);
        String dispatchBody = flow.substring(dispatch, receipt);
        assertTrue(dispatchBody.contains("result -> settleTypedRequestTransportResult(pending, delivery, attempt,"));
        assertFalse(dispatchBody.contains("\"wire_dispatched\""));
        assertFalse(dispatchBody.contains("heartbeatScheduler.schedule"));
        assertTrue(flow.contains("pending.operation() == ResourceOperationKind.SAVE"
            + " && retainPendingCoreGraphSave(pending)"));
        assertTrue(flow.contains("retryRetainedTransportMutations(generation)"));
    }

    private static Object newCoreRecovery() throws Exception {
        return newCoreRecovery(new ResourceLoadRequest(resource("flow", "physical-send")), null, false);
    }

    private static Object newCoreRecovery(ResourceOperation operation, ContentHash payloadHash, boolean mutation)
        throws Exception {
        Class<?> type = Class.forName(ReSyncFlowClient.class.getName() + "$CoreRequestRecovery");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(operation, payloadHash, mutation, null);
    }

    private static Object newCoreRequest(ReSyncFlowClient client, UUID requestId, ServerResourceLocator resource,
                                         UUID mutationId, ContentHash payloadHash) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("coreRequest", UUID.class, ReSyncResourceType.class,
            ResourceOperationKind.class, ServerResourceLocator.class, long.class, UUID.class, String.class,
            String.class, UUID.class, UUID.class, ContentHash.class, ContentHash.class);
        method.setAccessible(true);
        return method.invoke(client, requestId, ReSyncResourceType.FLOW, ResourceOperationKind.SAVE, resource, 3L,
            mutationId, null, null, UUID.randomUUID(), UUID.randomUUID(), payloadHash, payloadHash);
    }

    private static Object newTypedDelivery() throws Exception {
        Class<?> type = Class.forName(ReSyncFlowClient.class.getName() + "$TypedRequestDelivery");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object newTypedLoad(ReSyncFlowClient client, UUID requestId) throws Exception {
        ReSyncResourceType type = ReSyncResourceType.CUSTOM_CONTENT;
        ServerResourceLocator resource = resource(type.typeId(), "typed-delivery");
        return newTypedRequest(client, requestId, new ResourceLoadRequest(resource));
    }

    private static Object newTypedRequest(ReSyncFlowClient client, UUID requestId, ResourceOperation operation)
        throws Exception {
        ReSyncResourceType type = ReSyncResourceType.CUSTOM_CONTENT;
        boolean mutation = List.of(ResourceOperationKind.CREATE, ResourceOperationKind.SAVE, ResourceOperationKind.DELETE,
            ResourceOperationKind.ACTIVATE, ResourceOperationKind.DUPLICATE).contains(operation.kind());
        ServerResourceLocator resource = operationResource(operation);
        UUID mutationId = operationMutation(operation);
        long expectedRevision = operation instanceof ResourceSaveRequest<?> save ? save.expectedRevision()
            : operation instanceof ResourceDeleteRequest delete ? delete.expectedRevision()
            : operation instanceof ResourceActivateRequest activate ? activate.expectedRevision()
            : operation instanceof ResourceDuplicateRequest duplicate ? duplicate.expectedRevision() : 0L;
        ContentHash payloadHash = operation instanceof ResourceCreateRequest<?> create ? create.payloadHash()
            : operation instanceof ResourceSaveRequest<?> save ? save.payloadHash() : null;
        Method method = ReSyncFlowClient.class.getDeclaredMethod("typedResourceRequest", ReSyncResourceType.class,
            ResourceOperation.class, ServerResourceLocator.class, long.class, UUID.class, ContentHash.class,
            boolean.class, boolean.class, String.class, String.class, String.class, boolean.class, UUID.class,
            UUID.class, UUID.class);
        method.setAccessible(true);
        return method.invoke(client, type, operation, resource, expectedRevision, mutationId, payloadHash, mutation,
            false, null, null, null, operation instanceof ResourceActivateRequest activate
                && activate.targetState().wireName().equals("active"), requestId, UUID.randomUUID(), UUID.randomUUID());
    }

    private static List<ResourceOperation> genericOperations() {
        ReSyncResourceType type = ReSyncResourceType.CUSTOM_CONTENT;
        ServerResourceLocator resource = resource(type.typeId(), "typed-delivery");
        UUID mutation = UUID.fromString("96869686-9686-4686-8686-968696869686");
        var payload = ResourcePayloadCodecs.json().canonicalize(Map.of("id", "typed-delivery"));
        return List.of(new ResourceListRequest(resource.type(), null, 100, null), new ResourceLoadRequest(resource),
            new ResourceCreateRequest<>(resource, payload, mutation), new ResourceSaveRequest<>(resource, 1L, payload,
                mutation), new ResourceDeleteRequest(resource, 1L, mutation), new ResourceActivateRequest(resource, 1L,
                mutation), new ResourceDuplicateRequest(resource("custom_content", "typed-source"), resource, 1L, mutation));
    }

    private static ServerResourceLocator operationResource(ResourceOperation operation) {
        if (operation instanceof ResourceListRequest) {
            return null;
        }
        if (operation instanceof ResourceLoadRequest load) {
            return load.resource();
        }
        if (operation instanceof ResourceCreateRequest<?> create) {
            return create.resource();
        }
        if (operation instanceof ResourceSaveRequest<?> save) {
            return save.resource();
        }
        if (operation instanceof ResourceDeleteRequest delete) {
            return delete.resource();
        }
        if (operation instanceof ResourceActivateRequest activate) {
            return activate.resource();
        }
        if (operation instanceof ResourceDuplicateRequest duplicate) {
            return duplicate.target();
        }
        throw new IllegalArgumentException("Unsupported typed operation " + operation.kind());
    }

    private static UUID operationMutation(ResourceOperation operation) {
        if (operation instanceof ResourceCreateRequest<?> create) {
            return create.mutationId();
        }
        if (operation instanceof ResourceSaveRequest<?> save) {
            return save.mutationId();
        }
        if (operation instanceof ResourceDeleteRequest delete) {
            return delete.mutationId();
        }
        if (operation instanceof ResourceActivateRequest activate) {
            return activate.mutationId();
        }
        if (operation instanceof ResourceDuplicateRequest duplicate) {
            return duplicate.mutationId();
        }
        return null;
    }

    private static void invokeTypedSettlement(ReSyncFlowClient client, Object pending, Object delivery, int attempt,
                                              ReSyncFrameTransport.SendResult result) {
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("typedRequestTransportSettled", pending.getClass(),
                delivery.getClass(), int.class, long.class, ReSyncFrameTransport.SendResult.class);
            method.setAccessible(true);
            method.invoke(client, pending, delivery, attempt, 1L, result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void invokeCoreSettlement(ReSyncFlowClient client, Object pending, Object recovery, int attempt,
                                             ReSyncFrameTransport.SendResult result) {
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("coreRequestTransportSettled", pending.getClass(),
                recovery.getClass(), int.class, long.class, ReSyncFrameTransport.SendResult.class);
            method.setAccessible(true);
            method.invoke(client, pending, recovery, attempt, 1L, result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean invokeCoreRetention(ReSyncFlowClient client, Object pending, Object recovery) {
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("retainCoreTransportMutation", pending.getClass(),
                recovery.getClass());
            method.setAccessible(true);
            return (Boolean) method.invoke(client, pending, recovery);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean invokeCoreFailure(ReSyncFlowClient client, Object pending) {
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("failCorePending", pending.getClass(),
                String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(client, pending, "connection retired");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean invokeTypedRetention(ReSyncFlowClient client, Object pending) {
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("retainTypedTransportMutation",
                pending.getClass());
            method.setAccessible(true);
            return (Boolean) method.invoke(client, pending);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void invokeTypedFailure(ReSyncFlowClient client, Object pending) {
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("failTypedResourceRequest", pending.getClass(),
                String.class);
            method.setAccessible(true);
            method.invoke(client, pending, "connection retired");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void invokeTypedDeadline(ReSyncFlowClient client, Object pending, Object delivery, int attempt) {
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("handleTypedResourceResponseDeadline",
                pending.getClass(), delivery.getClass(), int.class);
            method.setAccessible(true);
            method.invoke(client, pending, delivery, attempt);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> pendingTyped(ReSyncFlowClient client) throws Exception {
        return (Map<UUID, Object>) field(client, "pendingTypedResourceRequests");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> typedDeliveries(ReSyncFlowClient client) throws Exception {
        return (Map<UUID, Object>) field(client, "typedRequestDeliveries");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> retainedTypedMutations(ReSyncFlowClient client) throws Exception {
        return (Map<UUID, Object>) field(client, "retainedTypedTransportMutations");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> pendingCore(ReSyncFlowClient client) throws Exception {
        return (Map<UUID, Object>) field(client, "pendingCoreGraphRequests");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> coreRecoveries(ReSyncFlowClient client) throws Exception {
        return (Map<UUID, Object>) field(client, "coreRequestRecoveries");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> retainedCoreMutations(ReSyncFlowClient client) throws Exception {
        return (Map<UUID, Object>) field(client, "retainedCoreTransportMutations");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static final class ImmediateTransport implements ReSyncFrameTransport {
        private final boolean accepted;
        private final AtomicInteger sendCount = new AtomicInteger();

        private ImmediateTransport(boolean accepted) {
            this.accepted = accepted;
        }

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
        }

        @Override
        public void send(byte[] frame) {
            sendCount.incrementAndGet();
        }

        @Override
        public boolean trySend(byte[] frame) {
            if (!accepted) {
                return false;
            }
            send(frame);
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public Optional<ServerId> peerServerId() {
            return Optional.empty();
        }
    }
}
