package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientCoreSaveAckOrderingTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("a8c8a8c8-a8c8-48c8-88c8-a8c8a8c8a8c8"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final UUID MUTATION = UUID.fromString("b8c8b8c8-b8c8-48c8-88c8-b8c8b8c8b8c8");
    private static final UUID MESSAGE = UUID.fromString("c8c8c8c8-c8c8-48c8-88c8-c8c8c8c8c8c8");
    private static final UUID REQUEST = UUID.fromString("d8c8d8c8-d8c8-48c8-88c8-d8c8d8c8d8c8");
    private static final UUID CORRELATION = UUID.fromString("e8c8e8c8-e8c8-48c8-88c8-e8c8e8c8e8c8");
    private static final UUID TRACE = UUID.fromString("f8c8f8c8-f8c8-48c8-88c8-f8c8f8c8f8c8");
    private static final ContractRef<CapabilityId> RESOURCES = ContractRef.of(OWNER, CapabilityId.of("resources"));
    private static final ContractRef<CapabilityId> RESOURCE_REVISIONS =
        ContractRef.of(OWNER, CapabilityId.of("resource_revisions"));
    private static final ContractRef<CapabilityId> UNSUPPORTED =
        ContractRef.of(new OwnerId("runtime.test"), CapabilityId.of("unsupported"));

    @Test
    void boundsCoreRequestRecoveryToOneFastReplay() throws Exception {
        assertEquals(4L, staticLong("CORE_REQUEST_TIMEOUT_SECONDS"));
        assertEquals(4L, staticLong("CORE_REQUEST_REPLAY_TIMEOUT_SECONDS"));
        assertEquals(2L, staticLong("CORE_RESPONSE_SETTLEMENT_TIMEOUT_SECONDS"));

        Class<?> recoveryType = Class.forName(ReSyncFlowClient.class.getName() + "$CoreRequestRecovery");
        Constructor<?> constructor = recoveryType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object recovery = constructor.newInstance(new ResourceLoadRequest(resource("flow", "recovery")), null, false, null);
        Method dispatched = recoveryType.getDeclaredMethod("dispatched");
        Method responseReceived = recoveryType.getDeclaredMethod("responseReceived");
        Method responseReceivedAttempt = recoveryType.getDeclaredMethod("responseReceived", int.class);
        Method current = recoveryType.getDeclaredMethod("current", int.class);
        Method claimReplay = recoveryType.getDeclaredMethod("claimReplay", int.class);
        for (Method method : List.of(dispatched, responseReceived, responseReceivedAttempt, current, claimReplay)) {
            method.setAccessible(true);
        }

        assertEquals(1, dispatched.invoke(recovery));
        assertEquals(1, responseReceived.invoke(recovery));
        assertTrue((Boolean) responseReceivedAttempt.invoke(recovery, 1));
        assertTrue((Boolean) claimReplay.invoke(recovery, 1));
        assertFalse((Boolean) claimReplay.invoke(recovery, 1));
        assertEquals(2, dispatched.invoke(recovery));
        assertFalse((Boolean) current.invoke(recovery, 1));
        assertTrue((Boolean) current.invoke(recovery, 2));
        assertFalse((Boolean) claimReplay.invoke(recovery, 2));
    }

    @Test
    void terminalCoreFailureRemovesRecoveryState() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        try {
            bindCoreSaveRequest(client, "terminal-recovery", graph("terminal-recovery", 1L).checksum());
            Object pending = pendingCoreRequest(client);
            Class<?> recoveryType = Class.forName(ReSyncFlowClient.class.getName() + "$CoreRequestRecovery");
            Constructor<?> constructor = recoveryType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object recovery = constructor.newInstance(new ResourceLoadRequest(resource("flow", "terminal-recovery")),
                null, false, null);
            coreRecoveries(client).put(REQUEST, recovery);

            Method fail = ReSyncFlowClient.class.getDeclaredMethod("failCorePending", pending.getClass(), String.class);
            fail.setAccessible(true);
            assertTrue((Boolean) fail.invoke(client, pending, "terminal"));
            assertFalse(coreRecoveries(client).containsKey(REQUEST));
            assertTrue(client.pendingCoreGraphRequest(REQUEST).isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void retainsSaveSettlementUntilEditorRehydrationSucceeds() throws Exception {
        SaveProbe probe = new SaveProbe();
        probe.manager.settlements.add(FlowManager.CoreGraphSaveSettlement.REHYDRATION_REQUIRED);
        probe.manager.settlements.add(FlowManager.CoreGraphSaveSettlement.SETTLED);
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, probe);
        GraphDocument submitted = graph("session-recovery", 1L);
        GraphDocument authoritative = graph("session-recovery", 2L);
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            establishCurrentConnection(client);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            Method captureFence = ReSyncFlowClient.class.getDeclaredMethod("captureResourceSaveFence");
            captureFence.setAccessible(true);
            Object fence = captureFence.invoke(client);
            assertNotNull(fence);
            assertTrue(enqueueResourceSave(client, submitted, fence));
            ticket = DesignerSaveNotifications.startExact(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), submitted.resource().id());
            assertNotNull(ticket);
            assertTrue(DesignerSaveNotifications.attachMutationId(ticket, MUTATION.toString()));
            AtomicReference<Boolean> notificationSaved = new AtomicReference<>();
            CountDownLatch notificationDone = new CountDownLatch(1);
            ticket.whenFinished((saved, updatesState) -> {
                notificationSaved.set(saved);
                notificationDone.countDown();
            });
            bindCoreSaveRequest(client, submitted.resource().id(), submitted.checksum());

            invokeSettlement(client, documentEnvelope(authoritative, REQUEST), 1);

            assertTrue(DesignerSaveNotifications.isPending(ticket));
            assertTrue(hasResourceSave(client, MUTATION.toString()));
            assertTrue(notificationDone.await(2L, TimeUnit.SECONDS));
            assertFalse(DesignerSaveNotifications.isPending(ticket));
            assertEquals(Boolean.TRUE, notificationSaved.get());
            assertEquals(2, probe.manager.settlementCalls.get());
            assertEquals(1L, probe.manager.saveTokens.getFirst().generation());
            assertFalse(hasResourceSave(client, MUTATION.toString()));
            assertEquals(3, staticInt("MAX_CORE_SAVE_SESSION_SETTLEMENT_ATTEMPTS"));
        } finally {
            if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
                DesignerSaveNotifications.failExact(ticket, "cleanup");
            }
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, SERVER.canonicalText(), previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void refreshesProjectMetadataOnlyAfterCoreCreate() throws Exception {
        Path sourcePath = Path.of("src", "main", "java", "redxax", "oxy", "remotely", "data", "flow",
            "ReSyncFlowClient.java");
        String source = Files.readString(sourcePath);
        int start = source.indexOf("private void settleCoreGraphSaveTracker");
        int end = source.indexOf("private static boolean authoritativeCoreGraphAck", start);
        assertTrue(start >= 0 && end > start);
        String settlement = source.substring(start, end);
        int guard = settlement.indexOf("if (pending.operation() == ResourceOperationKind.CREATE)");
        int refresh = settlement.indexOf("requestProjectMetadataList()");
        assertTrue(guard >= 0 && refresh > guard);
        assertEquals(refresh, settlement.lastIndexOf("requestProjectMetadataList()"));
    }

    @Test
    void terminalSaveCallbacksFollowRetainedWorkCleanup() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "redxax", "oxy", "remotely", "data",
            "flow", "ReSyncFlowClient.java")).replace("\r\n", "\n");

        assertCleanupBeforeCallback(source, "private void handleProtocolEnvelope", "completeResourceSave(requestId)",
            "DesignerSaveNotifications.complete(serverId, type, resource.id(), requestId");
        assertCleanupBeforeCallback(source, "private void failTypedResourceRequest", "completeResourceSave(trackerId)",
            "DesignerSaveNotifications.failRequest(serverId, trackerId");
        assertCleanupBeforeCallback(source, "private boolean completeCoreGraphSaveSettlement",
            "completeResourceSave(mutationId)", "DesignerSaveNotifications.completeMutation");
        assertCleanupBeforeCallback(source, "private void failCoreGraphSaveSettlement",
            "completeResourceSave(mutationId)", "DesignerSaveNotifications.failMutation");
        assertCleanupBeforeCallback(source, "private void trackGenericJob", "completeResourceSave(requestId)",
            "DesignerSaveNotifications.failRequest(serverId, requestId");
        assertCleanupBeforeCallback(source, "private void handleFlowError", "completeResourceSave(requestId)",
            "DesignerSaveNotifications.failRequest(serverId, requestId");
        assertCleanupBeforeCallback(source, "private void handleResourceSaveAck", "completeResourceSave(requestId)",
            "DesignerSaveNotifications.complete(serverId, type, id");
        assertCleanupBeforeCallback(source, "private void failCoreGraphSave", "completeResourceSave(requestId)",
            "DesignerSaveNotifications.failMutation");
        assertCleanupBeforeCallback(source, "private void failCoreGraphMutation", "completeResourceSave(mutationId)",
            "DesignerSaveNotifications.failMutation");
        assertCleanupBeforeCallback(source, "private void prepareResourceSave",
            "completeResourceSave(preparation.requestId())", "DesignerSaveNotifications.failRequest(serverId");
        assertCleanupBeforeCallback(source, "private void failPreparedResourceSave",
            "completeResourceSave(preparation.requestId())", "DesignerSaveNotifications.failRequest(serverId");
        assertCleanupBeforeCallback(source, "private void rejectPendingSend", "completeResourceSave(requestId)",
            "DesignerSaveNotifications.failRequest(serverId, requestId");
        assertTrue(source.contains("retireResourceSavesWithoutDispatch();\n        boolean failed = failPendingTriggerUpdates(message);"));
        assertCleanupBeforeCallback(source, "private PendingConnectionFailures failPendingConnectionRequests",
            "failPendingSaveMutations(message)", "failPendingCoreGraphRequests(message, retainOpenIntents)");
    }

    @Test
    void disconnectRetirementDoesNotDispatchTheNextQueuedSave() throws Exception {
        SaveProbe probe = new SaveProbe();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, probe);
        String first = MUTATION.toString();
        String second = UUID.randomUUID().toString();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstFinished = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        DesignerSaveNotifications.SaveTicket firstTicket = null;
        DesignerSaveNotifications.SaveTicket secondTicket = null;
        try {
            establishCurrentConnection(client);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            Method captureFence = ReSyncFlowClient.class.getDeclaredMethod("captureResourceSaveFence");
            captureFence.setAccessible(true);
            Object fence = captureFence.invoke(client);
            assertNotNull(fence);
            GraphDocument graph = graph("disconnect-retirement", 1L);
            assertTrue(enqueueResourceSave(client, graph, fence, first, firstStarted::countDown));
            assertTrue(firstStarted.await(2L, TimeUnit.SECONDS));
            assertTrue(enqueueResourceSave(client, graph, fence, second, secondStarted::countDown));

            firstTicket = DesignerSaveNotifications.startExact(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                graph.resource().id(), graph.resource().id());
            secondTicket = DesignerSaveNotifications.startExact(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                graph.resource().id(), graph.resource().id());
            assertNotNull(firstTicket);
            assertNotNull(secondTicket);
            assertTrue(DesignerSaveNotifications.attachRequestId(firstTicket, REQUEST.toString()));
            assertTrue(DesignerSaveNotifications.attachMutationId(firstTicket, MUTATION.toString()));
            assertTrue(DesignerSaveNotifications.attachRequestId(secondTicket, second));
            firstTicket.whenFinished((saved, updatesState) -> firstFinished.countDown());
            secondTicket.whenFinished((saved, updatesState) -> secondFinished.countDown());
            bindCoreSaveRequest(client, graph.resource().id(), graph.checksum());

            Method failConnection = ReSyncFlowClient.class.getDeclaredMethod("failConnectionAttempt", String.class,
                int.class);
            failConnection.setAccessible(true);
            failConnection.invoke(client, "ReSync Connection Failed", 1);

            assertTrue(firstFinished.await(2L, TimeUnit.SECONDS));
            assertTrue(secondFinished.await(2L, TimeUnit.SECONDS));
            assertFalse(secondStarted.await(200L, TimeUnit.MILLISECONDS));
            assertFalse(hasResourceSave(client, first));
            assertFalse(hasResourceSave(client, second));
            assertTrue(client.pendingCoreGraphRequest(REQUEST).isEmpty());
        } finally {
            if (firstTicket != null && DesignerSaveNotifications.isPending(firstTicket)) {
                DesignerSaveNotifications.failExact(firstTicket, "cleanup");
            }
            if (secondTicket != null && DesignerSaveNotifications.isPending(secondTicket)) {
                DesignerSaveNotifications.failExact(secondTicket, "cleanup");
            }
            restoreWorldGenEpoch(worldGenEpochs, SERVER.canonicalText(), previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void includesCatalogRuntimeCapabilitiesInCoreResourceSupport() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, null);
        try {
            Method method = ReSyncFlowClient.class.getDeclaredMethod("coreResourceCapabilities",
                CatalogAuthoringPublication.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> supported = (Set<String>) method.invoke(client, publicationWithCatalogCapability());

            assertTrue(supported.contains("restudio.resync/flow.event"));
            assertTrue(supported.contains("flow.event"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void acceptsAuthoritativeAckFromReadOnlyProjection() throws Exception {
        SaveProbe probe = new SaveProbe();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, probe);
        GraphDocument submitted = graph("read-only-settlement", 1L);
        GraphDocument authoritative = graph("read-only-settlement", 2L, Set.of(UNSUPPORTED));
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        AtomicReference<ReSyncFlowClient.CoreGraphResourceTransition> refresh = new AtomicReference<>();
        CountDownLatch refreshed = new CountDownLatch(1);
        client.addCoreGraphResourceListener(transition -> {
            refresh.set(transition);
            refreshed.countDown();
        });
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            establishCurrentConnection(client);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            Method captureFence = ReSyncFlowClient.class.getDeclaredMethod("captureResourceSaveFence");
            captureFence.setAccessible(true);
            Object fence = captureFence.invoke(client);
            assertNotNull(fence);
            assertTrue(enqueueResourceSave(client, submitted, fence));
            ticket = DesignerSaveNotifications.startExact(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), submitted.resource().id());
            assertNotNull(ticket);
            assertTrue(DesignerSaveNotifications.attachMutationId(ticket, MUTATION.toString()));
            AtomicReference<Boolean> notificationSaved = new AtomicReference<>();
            AtomicReference<Boolean> notificationUpdatesState = new AtomicReference<>();
            ticket.whenFinished((saved, updatesState) -> {
                notificationSaved.set(saved);
                notificationUpdatesState.set(updatesState);
            });
            bindCoreSaveRequest(client, submitted.resource().id(), submitted.checksum());
            ProtocolEnvelope<Map<String, Object>> envelope = documentEnvelope(authoritative, REQUEST);
            CoreGraphResourceProjection.Projection projection = CoreGraphResourceProjection.project(envelope,
                ReSyncProtocolEnvelopeProjection.genericResourceCapabilities(
                    Set.of(RESOURCES.canonicalText(), RESOURCE_REVISIONS.canonicalText())));
            assertTrue(projection.readOnly());
            invokeCoreGraphEnvelope(client, envelope, 1);

            assertTrue(client.resourceRevisionReconciler().graphReadOnly(authoritative.resource()).isPresent());
            assertTrue(refreshed.await(2L, TimeUnit.SECONDS));
            assertEquals(1, probe.manager.results.size());
            assertEquals(1L, probe.manager.saveTokens.getFirst().generation());
            assertEquals(submitted.checksum(), probe.manager.submittedHashes.getFirst());
            assertEquals(((GraphDocument) projection.corePayload()).canonicalJson(),
                ((GraphDocument) probe.manager.authoritativePayloads.getFirst()).canonicalJson());
            assertEquals(Boolean.TRUE, notificationSaved.get());
            assertEquals(Boolean.TRUE, notificationUpdatesState.get());
            assertFalse(DesignerSaveNotifications.isPending(ticket));
            assertNull(DesignerSaveNotifications.completeMutation(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), MUTATION.toString()));
            assertTrue(client.resourceRevisionReconciler().graphReadOnly(authoritative.resource()).isPresent());
            assertEquals(authoritative.revision(), client.resourceRevisionReconciler()
                .graphReadOnly(authoritative.resource()).orElseThrow().revision());
            assertNotNull(refresh.get());
            assertEquals(authoritative.revision(), refresh.get().projection().revision());
            assertTrue(refresh.get().projection().readOnly());
            assertTrue(client.pendingCoreGraphRequest(REQUEST).isEmpty());
            assertFalse(hasResourceSave(client, MUTATION.toString()));
        } finally {
            if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
                DesignerSaveNotifications.failExact(ticket, "cleanup");
            }
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, SERVER.canonicalText(), previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void rejectsAckWithoutOriginalSaveFenceBeforeManagerCallback() throws Exception {
        SaveProbe probe = new SaveProbe();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, probe);
        GraphDocument submitted = graph("ack-ordering", 1L);
        GraphDocument authoritative = graph("ack-ordering", 2L);
        ContentHash submittedHash = submitted.checksum();
        try {
            bindCoreSaveRequest(client, submitted.resource().id(), submittedHash);
            DesignerSaveNotifications.start(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), submitted.resource().id());
            DesignerSaveNotifications.attachMutationId(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), MUTATION.toString());
            ProtocolEnvelope<Map<String, Object>> envelope = documentEnvelope(authoritative, REQUEST);
            invokeSettlement(client, envelope, 1);

            assertTrue(probe.manager.results.isEmpty());
        } finally {
            DesignerSaveNotifications.failMutation(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), MUTATION.toString(), "cleanup");
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void ignoresAckFromRetiredGenerationBeforeManagerCallback() throws Exception {
        SaveProbe probe = new SaveProbe();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), null, null, null, probe);
        GraphDocument submitted = graph("retired-ordering", 1L);
        GraphDocument authoritative = graph("retired-ordering", 2L);
        try {
            bindCoreSaveRequest(client, submitted.resource().id(), submitted.checksum());
            DesignerSaveNotifications.start(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), submitted.resource().id());
            DesignerSaveNotifications.attachMutationId(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), MUTATION.toString());
            invokeSettlement(client, documentEnvelope(authoritative, REQUEST), 77);

            assertTrue(probe.manager.results.isEmpty());
            assertTrue(client.pendingCoreGraphRequest(REQUEST).isPresent());
        } finally {
            DesignerSaveNotifications.failMutation(SERVER.canonicalText(), ReSyncResourceType.FLOW,
                submitted.resource().id(), MUTATION.toString(), "cleanup");
            client.shutdown();
            probe.close();
        }
    }

    private static void bindCoreSaveRequest(ReSyncFlowClient client, String id, ContentHash submittedHash) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("coreRequest", UUID.class, ReSyncResourceType.class,
            ResourceOperationKind.class, ServerResourceLocator.class, long.class, UUID.class, String.class,
            String.class, UUID.class, UUID.class, ContentHash.class, ContentHash.class);
        method.setAccessible(true);
        method.invoke(client, REQUEST, ReSyncResourceType.FLOW, ResourceOperationKind.SAVE,
            resource("flow", id), 1L, MUTATION, null, null, CORRELATION, TRACE, null, submittedHash);
    }

    private static long staticLong(String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static int staticInt(String name) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static Object pendingCoreRequest(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingCoreGraphRequests");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> requests = (Map<UUID, Object>) field.get(client);
        return requests.get(REQUEST);
    }

    private static Map<UUID, Object> coreRecoveries(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("coreRequestRecoveries");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> recoveries = (Map<UUID, Object>) field.get(client);
        return recoveries;
    }

    private static void invokeSettlement(ReSyncFlowClient client, ProtocolEnvelope<Map<String, Object>> envelope,
                                         int generation) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingCoreGraphRequests");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> requests = (Map<UUID, Object>) field.get(client);
        Object pending = requests.get(REQUEST);
        Method method = List.of(ReSyncFlowClient.class.getDeclaredMethods()).stream()
            .filter(candidate -> candidate.getName().equals("notifyCoreGraphMutationResult")
                && candidate.getParameterCount() == 4)
            .findFirst().orElseThrow();
        method.setAccessible(true);
        CoreGraphResourceProjection.Projection projection = CoreGraphResourceProjection.project(envelope,
            ReSyncProtocolEnvelopeProjection.genericResourceCapabilities(
                Set.of(RESOURCES.canonicalText(), RESOURCE_REVISIONS.canonicalText())));
        method.invoke(client, envelope, pending, projection, generation);
    }

    private static void invokeCoreGraphEnvelope(ReSyncFlowClient client,
                                                 ProtocolEnvelope<Map<String, Object>> envelope,
                                                 int generation) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("handleCoreGraphEnvelope",
            ProtocolEnvelope.class, int.class);
        method.setAccessible(true);
        method.invoke(client, envelope, generation);
    }

    private static boolean enqueueResourceSave(ReSyncFlowClient client, GraphDocument graph, Object fence)
        throws Exception {
        return enqueueResourceSave(client, graph, fence, MUTATION.toString(), () -> {});
    }

    private static boolean enqueueResourceSave(ReSyncFlowClient client, GraphDocument graph, Object fence,
                                               String trackerId, Runnable preparation) throws Exception {
        Class<?> keyType = Class.forName(ReSyncFlowClient.class.getName() + "$ResourceSaveKey");
        Constructor<?> keyConstructor = keyType.getDeclaredConstructor(ReSyncResourceType.class, String.class);
        keyConstructor.setAccessible(true);
        Object key = keyConstructor.newInstance(ReSyncResourceType.FLOW, graph.resource().id());
        Method method = List.of(ReSyncFlowClient.class.getDeclaredMethods()).stream()
            .filter(candidate -> candidate.getName().equals("enqueueResourceSave")
                && candidate.getParameterCount() == 8)
            .findFirst().orElseThrow();
        method.setAccessible(true);
        Object[] arguments = {key, trackerId, -1L, fence, null,
            (BooleanSupplier) () -> true, preparation, (Runnable) () -> {}};
        return (Boolean) method.invoke(client, arguments);
    }

    private static boolean hasResourceSave(ReSyncFlowClient client, String trackerId) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("resourceSavesByTracker");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> saves = (Map<String, Object>) field.get(client);
        return saves.containsKey(trackerId);
    }

    private static void assertCleanupBeforeCallback(String source, String method, String cleanup, String callback) {
        int start = source.lastIndexOf(method);
        int end = source.indexOf("\n    private ", start + method.length());
        assertTrue(start >= 0 && end > start, method);
        String section = source.substring(start, end);
        int callbackIndex = section.indexOf(callback);
        int cleanupIndex = section.lastIndexOf(cleanup, callbackIndex);
        assertTrue(callbackIndex >= 0 && cleanupIndex >= 0 && cleanupIndex < callbackIndex, method);
    }

    private static void establishCurrentConnection(ReSyncFlowClient client) throws Exception {
        Field connectionGeneration = ReSyncFlowClient.class.getDeclaredField("connectionGeneration");
        connectionGeneration.setAccessible(true);
        ((AtomicInteger) connectionGeneration.get(client)).set(1);
        setField(client, "activeTransportGeneration", 1);
        Class<?> sourceType = Class.forName(ReSyncFlowClient.class.getName() + "$ConnectionSource");
        Constructor<?> sourceConstructor = sourceType.getDeclaredConstructor(int.class);
        sourceConstructor.setAccessible(true);
        setField(client, "connectionSource", sourceConstructor.newInstance(1));
        Field pendingHandshakeGeneration = ReSyncFlowClient.class.getDeclaredField("pendingHandshakeGeneration");
        pendingHandshakeGeneration.setAccessible(true);
        ((AtomicInteger) pendingHandshakeGeneration.get(client)).set(-1);
        Method authenticate = ReSyncFlowClient.class.getDeclaredMethod("authenticateGeneration", int.class);
        authenticate.setAccessible(true);
        assertTrue((Boolean) authenticate.invoke(client, 1));
        setField(client, "authorityEpochAdvertised", true);
        setField(client, "authorityEpochEstablished", true);
        setField(client, "handshakeAuthorityEpoch", 1L);
    }

    private static Map<String, Long> worldGenAuthorityEpochs() throws Exception {
        Field projectStore = WorldGenManager.class.getDeclaredField("projectStore");
        projectStore.setAccessible(true);
        Object store = projectStore.get(WorldGenManager.getInstance());
        Field authorityEpochs = store.getClass().getDeclaredField("authorityEpochs");
        authorityEpochs.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> values = (Map<String, Long>) authorityEpochs.get(store);
        return values;
    }

    private static void restoreWorldGenEpoch(Map<String, Long> epochs, String serverId, Long previous) {
        if (previous == null) {
            epochs.remove(serverId);
        } else {
            epochs.put(serverId, previous);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ProtocolEnvelope<Map<String, Object>> documentEnvelope(GraphDocument graph, UUID requestId) {
        ResourceDocument<Map<String, Object>> document = liveDocument(graph);
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.ACK, new CatalogVersion(1, 0), MESSAGE, requestId,
            CORRELATION, TRACE, SERVER, document.resource(), document.revision(), 1L, document.mutationId(),
            ContractRef.of(OWNER, OperationId.of("resource.save")), Set.of(RESOURCES, RESOURCE_REVISIONS),
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(), false,
            null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.SAVE, document));
    }

    private static ResourceDocument<Map<String, Object>> liveDocument(GraphDocument graph) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, graph.resource().resourceType().value());
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION,
            CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, graph.revision());
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, MUTATION.toString());
        fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, ResourceActivationState.ACTIVE.wireName());
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION,
            CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        GraphDocumentCodec.INSTANCE.encode(graph).fields().forEach((key, value) -> fields.put(key, value.toJava()));
        ContentHash assetHash = new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields));
        fields.put(CoreGraphResourceProjection.ASSET_HASH, assetHash.canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return ResourceDocument.live(graph.resource(), graph.revision(), MUTATION,
            ResourcePayloadCodecs.json().canonicalize(payload), ResourceActivationState.ACTIVE, "server");
    }

    private static GraphDocument graph(String id, long revision) {
        return graph(id, revision, Set.of());
    }

    private static GraphDocument graph(String id, long revision,
                                       Set<ContractRef<CapabilityId>> requiredCapabilities) {
        return new GraphDocument(new CatalogVersion(1, 0), resource("flow", id), revision, BINDING,
            requiredCapabilities, List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static CatalogAuthoringPublication publicationWithCatalogCapability() {
        CatalogVersion version = new CatalogVersion(1, 0);
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0",
                new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED,
                    "classpath:/nodes/flow-event.json", "1.0.0", "test", "flow-event"))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow.event"), 1, false,
                InspectorFallback.GENERIC)))
            .build();
        CatalogSnapshot snapshot = new CatalogCompiler(version).compile(List.of(contribution), 1)
            .snapshot().orElseThrow();
        return CatalogAuthoringPublication.project(snapshot, snapshot.minimumClientCapabilities());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static final class SaveProbe extends RemotelyClient {
        private final SaveManager manager;

        private SaveProbe() {
            super(null);
            manager = new SaveManager(this);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class SaveManager extends FlowManager {
        private final List<ReSyncFlowClient.CoreGraphMutationResult> results = new CopyOnWriteArrayList<>();
        private final List<ContentHash> submittedHashes = new CopyOnWriteArrayList<>();
        private final List<Object> authoritativePayloads = new CopyOnWriteArrayList<>();
        private final List<ServerConnectionToken> saveTokens = new CopyOnWriteArrayList<>();
        private final List<CoreGraphSaveSettlement> settlements = new CopyOnWriteArrayList<>();
        private final AtomicInteger settlementCalls = new AtomicInteger();

        private SaveManager(RemotelyClient client) {
            super(client, null);
        }

        @Override
        public ServerConnectionToken captureServerConnectionToken(String serverId, ReSyncFlowClient source) {
            return new ServerConnectionToken(serverId, source, 1L);
        }

        @Override
        public boolean isCurrentServerConnection(ServerConnectionToken token) {
            return token != null && token.generation() == 1L;
        }

        @Override
        public boolean runIfCurrentServerConnection(ServerConnectionToken token, Runnable action) {
            if (!isCurrentServerConnection(token) || action == null) {
                return false;
            }
            action.run();
            return true;
        }

        @Override
        public CoreGraphSaveSettlement settleCoreGraphEditorSessionSaveAuthoritative(
            ServerConnectionToken saveToken, ReSyncFlowClient.CoreGraphMutationResult result,
            ContentHash submittedPayloadHash, Object authoritativePayload) {
            saveTokens.add(saveToken);
            results.add(result);
            submittedHashes.add(submittedPayloadHash);
            authoritativePayloads.add(authoritativePayload);
            settlementCalls.incrementAndGet();
            return settlements.isEmpty() ? CoreGraphSaveSettlement.SETTLED : settlements.removeFirst();
        }
    }
}
