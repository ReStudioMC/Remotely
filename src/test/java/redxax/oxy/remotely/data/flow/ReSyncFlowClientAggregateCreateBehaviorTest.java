package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.TabDefinition;
import redxax.oxy.remotely.worldgen.WorldGenManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceCreateResult;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReSyncFlowClientAggregateCreateBehaviorTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("91b91b91-b91b-491b-891b-91b91b91b91b"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final UUID REQUEST = UUID.fromString("a2ca2ca2-ca2c-4a2c-8a2c-a2ca2ca2ca2c");
    private static final UUID CORRELATION = UUID.fromString("b3db3db3-db3d-4b3d-8b3d-b3db3db3db3d");
    private static final UUID TRACE = UUID.fromString("c4ec4ec4-ec4e-4c4e-8c4e-c4ec4ec4ec4e");
    private static final UUID MUTATION = UUID.fromString("d5fd5fd5-fd5f-4d5f-8d5f-d5fd5fd5fd5f");
    private static final Set<ContractRef<CapabilityId>> GENERIC_CAPABILITIES = Set.of(
        ContractRef.of(OWNER, CapabilityId.of("resources")),
        ContractRef.of(OWNER, CapabilityId.of("resource_revisions")));
    private static final Set<ContractRef<CapabilityId>> CAPABILITIES = Set.of(
        ContractRef.of(OWNER, CapabilityId.of("resources")),
        ContractRef.of(OWNER, CapabilityId.of("resource_revisions")),
        ContractRef.of(OWNER, CapabilityId.of("resource_create_presentation")));
    @TempDir
    Path tempDir;

    @Test
    void genericCreationFromExactTombstoneReachesWireWithLocalDeletedRevisionFence() throws Exception {
        assertGenericCreatePreflight(true, 2L, true);
    }

    @Test
    void genericCreationRejectsLiveResourceWithoutRetryOrWireMutation() throws Exception {
        assertGenericCreatePreflight(false, 2L, false);
    }

    @Test
    void genericCreationRejectsTombstoneBelowNewerWatermarkWithoutRetryOrWireMutation() throws Exception {
        assertGenericCreatePreflight(true, 3L, false);
    }

    private void assertGenericCreatePreflight(boolean deleted, long watermark, boolean allowed) throws Exception {
        Probe probe = new Probe("never", tempDir);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            establishCurrentConnection(client, probe.manager, transport);
            setField(client, "catalogAuthority", ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION);
            setField(client, "genericResourceContractVersion", new CatalogVersion(1, 2));
            probe.manager.cacheServerCapabilities(SERVER.canonicalText(), JsonParser.parseString("""
                {"protocolEnvelope":{"supported":true,"mutationAuthority":{"supported":true,"durable":true},
                "resourceOperations":{"mutate":["create"]},"genericResourceContract":{"version":
                {"generation":1,"minor":2},"capabilities":["restudio.resync/resource_create_presentation"]}}}
                """).getAsJsonObject());
            ReSyncResourceType type = ReSyncResourceType.CUSTOM_CONTENT;
            String id = "prepared-recreation";
            JsonObject payload = JsonParser.parseString("{\"id\":\"prepared-recreation\",\"revision\":19}")
                .getAsJsonObject();
            var canonical = ResourcePayloadCodecs.json().canonicalize(Map.of("id", id, "revision", 19));
            assertTrue(client.resourceRevisionReconciler().apply(new ReSyncResourceRevisionReconciler.ResourceResult(
                SERVER.canonicalText(), type.typeId(), id, 2L, UUID.randomUUID().toString(),
                canonical.checksum().canonicalText(), deleted, deleted ? null : payload, 1L)).accepted());
            Method observe = ReSyncFlowClient.class.getDeclaredMethod("recordResourceRevision",
                ReSyncResourceType.class, String.class, long.class);
            observe.setAccessible(true);
            observe.invoke(client, type, id, watermark);
            UUID requestId = UUID.randomUUID();
            UUID mutationId = UUID.randomUUID();
            ticket = DesignerSaveNotifications.startSilentResumableExact(SERVER.canonicalText(), type, id,
                "Custom Content", requestId, mutationId);
            probe.manager.capturePreflightFailure = true;
            FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(type.typeId(), id,
                "Prepared Recreation", "Content/Items/Replacement/" + id + ".json", "", 3, false);

            assertTrue(client.sendPreparedResourceCreate(type, id, payload.toString(), canonical.checksum(), metadata,
                ticket));

            assertTrue(await(() -> probe.manager.preflightFailures.get() > 0 || !transport.sentFrames().isEmpty()));
            if (allowed) {
                ProtocolEnvelope<Map<String, Object>> envelope = transport.requireRequest(ResourceOperationKind.CREATE, id);
                ResourceCreateRequest<?> create = (ResourceCreateRequest<?>)
                    ((ProtocolBody.ResourceRequest) envelope.body()).operation();
                assertEquals(0L, envelope.revision());
                assertEquals(mutationId, create.mutationId());
                assertEquals(metadata.path(), create.presentation().path());
                Object pending = pendingTypedRequests(client).get(requestId);
                assertNotNull(pending);
                Method revision = pending.getClass().getDeclaredMethod("expectedRevision");
                revision.setAccessible(true);
                assertEquals(2L, revision.invoke(pending));
                assertEquals(0, probe.manager.preflightFailures.get());
            } else {
                assertEquals(1, probe.manager.preflightFailures.get());
                assertEquals(Boolean.FALSE, probe.manager.preflightRetryable.get());
                assertTrue(transport.sentFrames().isEmpty());
                assertTrue(pendingTypedRequests(client).isEmpty());
                assertTrue(pendingCreatePresentations(client).isEmpty());
                assertFalse(DesignerSaveNotifications.isPending(ticket));
            }
        } finally {
            if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
                DesignerSaveNotifications.failExact(ticket, "cleanup");
            }
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void customContentCreateCanonicalizesLegacyFolderPresentation() {
        FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(
            ReSyncResourceType.CUSTOM_CONTENT.typeId(), "legacy-item", "Legacy Item", "Content/Items", "", 4,
            false);

        assertEquals(new ResourcePresentationIntent("Legacy Item", "Content/Items/legacy-item.json", 4),
            ReSyncFlowClient.createPresentation(ReSyncResourceType.CUSTOM_CONTENT, "legacy-item", metadata));
    }

    @Test
    void coreGraphAssetReplacesStaleEnvelopeFieldsWithTheExactMutation() throws Exception {
        Probe probe = new Probe("never", tempDir);
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), new ScriptedReSyncTransport(), probe,
            new ReSyncCatalogPublicationCache());
        try {
            ServerResourceLocator resource = new ServerResourceLocator(SERVER,
                ContractRef.of(OWNER, ResourceTypeId.of("flow")), "exact-envelope");
            CatalogBinding binding = new CatalogBinding(1L, "a".repeat(64), "b".repeat(64));
            GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1L, binding, Set.of(),
                List.of(), List.of(), List.of(), List.of(), OpaqueData.of(Map.of(
                    CoreGraphResourceProjection.RESOURCE_TYPE, "stale",
                    CoreGraphResourceProjection.ASSET_REVISION, 88L,
                    CoreGraphResourceProjection.ASSET_MUTATION_ID, UUID.randomUUID().toString(),
                    CoreGraphResourceProjection.ASSET_HASH, "c".repeat(64))));
            UUID mutation = UUID.randomUUID();
            Method method = ReSyncFlowClient.class.getDeclaredMethod("coreGraphAsset", ReSyncResourceType.class,
                ServerResourceLocator.class, GraphDocument.class, FunctionSourceDocument.class, long.class,
                UUID.class, ResourceActivationState.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, Object> asset = (Map<String, Object>) method.invoke(client, ReSyncResourceType.FLOW, resource,
                graph, null, 3L, mutation, ResourceActivationState.ACTIVE);

            assertEquals(ReSyncResourceType.FLOW.typeId(), asset.get(CoreGraphResourceProjection.RESOURCE_TYPE));
            assertEquals(3L, ((Number) asset.get(CoreGraphResourceProjection.ASSET_REVISION)).longValue());
            assertEquals(mutation.toString(), asset.get(CoreGraphResourceProjection.ASSET_MUTATION_ID));
            assertFalse("c".repeat(64).equals(asset.get(CoreGraphResourceProjection.ASSET_HASH)));
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void completedFunctionCreateAppearsBeforeAnotherAuthoritativeList() throws Exception {
        Probe probe = new Probe("never", tempDir);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        try {
            establishCurrentConnection(client, probe.manager, transport);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            probe.manager.applyServerGraphList(SERVER.canonicalText(), ReSyncResourceType.FUNCTION, List.of());
            FlowManager.TypedResourceMembershipSnapshot empty =
                probe.manager.snapshotTypedResourceMembership(SERVER.canonicalText());
            assertTrue(empty.completeTypes().contains(ReSyncResourceType.FUNCTION.typeId()));
            assertFalse(empty.contains(ReSyncResourceType.FUNCTION.typeId(), "instant-function"));

            FlowGraph function = new FlowGraph("instant-function", new LinkedHashMap<>(), new ArrayList<>(),
                new ArrayList<>(), true, new ArrayList<>(), new ArrayList<>());
            FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(
                ReSyncResourceType.FUNCTION.typeId(), function.getId(), "Instant Function",
                "Blueprints/Functions/instant-function.json", "", 0, false);
            Object transaction = creationTransaction(ReSyncResourceType.FUNCTION, function, null, UUID.randomUUID(),
                UUID.randomUUID(), metadata);
            assertTrue(await(() -> creationJournalLoaded(probe.manager)));
            putCreationTransaction(probe.manager, transaction);

            invokeCompleteCreation(probe.manager, transaction);

            FlowManager.TypedResourceMembershipSnapshot projected =
                probe.manager.snapshotTypedResourceMembership(SERVER.canonicalText());
            assertTrue(projected.completeTypes().contains(ReSyncResourceType.FUNCTION.typeId()));
            assertTrue(projected.contains(ReSyncResourceType.FUNCTION.typeId(), function.getId()));
            assertTrue(probe.manager.getProjectResources(SERVER.canonicalText()).stream().anyMatch(resource ->
                ReSyncResourceType.FUNCTION.typeId().equals(resource.getType())
                    && function.getId().equals(resource.getId())));
            assertTrue(creationTransactions(probe.manager).isEmpty());
        } finally {
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void aggregateCreateRetriesTheExactAckAndPublishesBothDocumentsOnce() throws Exception {
        Probe probe = new Probe("aggregate-tab", tempDir);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            establishCurrentConnection(client, probe.manager, transport);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            TabDefinition tab = new TabDefinition("aggregate-tab");
            Map<String, Object> payload = payload(ReSyncResourceType.TAB, tab);
            ResourcePresentationIntent presentation = new ResourcePresentationIntent("Aggregate Tab",
                "Tabs/aggregate-tab.json", 4);
            ticket = bindAggregateCreate(client, tab, payload, presentation);
            CountDownLatch settled = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            ticket.whenFinished((saved, currentAtFinish) -> {
                if (saved) {
                    callbacks.incrementAndGet();
                    settled.countDown();
                }
            });

            ProtocolEnvelope<Map<String, Object>> envelope = aggregateEnvelope(payload, presentation);
            invokeAggregate(client, envelope, 1);

            assertEquals(0, probe.manager.notifications.size());
            assertTrue(awaitUi(() -> settled.getCount() == 0L));
            assertTrue(awaitUi(() -> probe.manager.notifications.size() == 2));
            assertEquals(2, probe.manager.tabCacheAttempts.get());
            assertEquals(List.of(ReSyncResourceType.PROJECT_METADATA, ReSyncResourceType.TAB),
                probe.manager.notifications);
            assertEquals(1, callbacks.get());
            assertFalse(DesignerSaveNotifications.isPending(ticket));
            assertTrue(pendingTypedRequests(client).isEmpty());
            assertTrue(pendingCreatePresentations(client).isEmpty());

            invokeAggregate(client, envelope, 1);
            assertEquals(2, probe.manager.tabCacheAttempts.get());
            assertEquals(1, callbacks.get());
        } finally {
            if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
                DesignerSaveNotifications.failExact(ticket, "cleanup");
            }
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void ordinarySaveRetriesTheExactResponseLocallyAndSettlesOnce() throws Exception {
        Probe probe = new Probe("saved-tab", tempDir);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        UUID requestId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID mutationId = UUID.randomUUID();
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            establishCurrentConnection(client, probe.manager, transport);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            TabDefinition tab = new TabDefinition("saved-tab");
            Map<String, Object> payload = payload(ReSyncResourceType.TAB, tab);
            var canonical = ResourcePayloadCodecs.json().canonicalize(payload);
            Method captureFence = ReSyncFlowClient.class.getDeclaredMethod("captureResourceSaveFence");
            captureFence.setAccessible(true);
            Object fence = captureFence.invoke(client);
            assertTrue(enqueueResourceSave(client, fence, "saved-tab", requestId));
            ticket = DesignerSaveNotifications.startSilentResumableExact(SERVER.canonicalText(),
                ReSyncResourceType.TAB, "saved-tab", "Tab", requestId, mutationId);
            ResourceSaveRequest<Map<String, Object>> operation = new ResourceSaveRequest<>(
                resource(ReSyncResourceType.TAB, "saved-tab"), 0L, canonical, mutationId);
            invokeTypedRequest(client, ReSyncResourceType.TAB, operation, operation.resource(), 0L, mutationId,
                canonical.checksum(), true, false, null, ReSyncResourceType.TAB.serialize(tab), requestId.toString(),
                false, requestId, correlationId, traceId);
            CountDownLatch settled = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            ticket.whenFinished((saved, currentAtFinish) -> {
                if (saved) {
                    callbacks.incrementAndGet();
                    settled.countDown();
                }
            });
            ResourceDocument<Map<String, Object>> document = ResourceDocument.live(operation.resource(), 1L,
                mutationId, canonical, ResourceActivationState.ACTIVE, "server");
            ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.ACK,
                new CatalogVersion(1, 0), UUID.randomUUID(), requestId, correlationId, traceId, SERVER,
                document.resource(), document.revision(), 1L, mutationId,
                ContractRef.of(OWNER, OperationId.of("resource.save")), GENERIC_CAPABILITIES,
                ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(), false,
                null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
                new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.SAVE, document));

            invokeDocument(client, envelope, 1);

            assertTrue(awaitUi(() -> settled.getCount() == 0L));
            assertTrue(awaitUi(() -> probe.manager.notifications.size() == 1));
            assertEquals(3, probe.manager.tabCacheAttempts.get());
            assertEquals(1, callbacks.get());
            assertTrue(pendingTypedRequests(client).isEmpty());
        } finally {
            if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
                DesignerSaveNotifications.failExact(ticket, "cleanup");
            }
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void ordinaryResumableSaveFailureSettlesWithoutACreationTransaction() throws Exception {
        Probe probe = new Probe("never", tempDir);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        UUID requestId = UUID.randomUUID();
        UUID mutationId = UUID.randomUUID();
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            establishCurrentConnection(client, probe.manager, transport);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            TabDefinition tab = new TabDefinition("failed-tab");
            Map<String, Object> payload = payload(ReSyncResourceType.TAB, tab);
            var canonical = ResourcePayloadCodecs.json().canonicalize(payload);
            Method captureFence = ReSyncFlowClient.class.getDeclaredMethod("captureResourceSaveFence");
            captureFence.setAccessible(true);
            Object fence = captureFence.invoke(client);
            assertTrue(enqueueResourceSave(client, fence, "failed-tab", requestId));
            ticket = DesignerSaveNotifications.startSilentResumableExact(SERVER.canonicalText(),
                ReSyncResourceType.TAB, "failed-tab", "Tab", requestId, mutationId);
            ResourceSaveRequest<Map<String, Object>> operation = new ResourceSaveRequest<>(
                resource(ReSyncResourceType.TAB, "failed-tab"), 0L, canonical, mutationId);
            invokeTypedRequest(client, ReSyncResourceType.TAB, operation, operation.resource(), 0L, mutationId,
                canonical.checksum(), true, false, null, ReSyncResourceType.TAB.serialize(tab), requestId.toString(),
                false, requestId, UUID.randomUUID(), UUID.randomUUID());
            Object pending = pendingTypedRequests(client).get(requestId);
            assertNotNull(pending);
            AtomicReference<Boolean> outcome = new AtomicReference<>();
            ticket.whenFinished((saved, currentAtFinish) -> outcome.set(saved));

            Method fail = ReSyncFlowClient.class.getDeclaredMethod("failTypedResourceRequest", pending.getClass(),
                String.class, boolean.class);
            fail.setAccessible(true);
            fail.invoke(client, pending, "Resource mutation failed", false);

            assertTrue(awaitUi(() -> outcome.get() != null));
            assertEquals(Boolean.FALSE, outcome.get());
            assertFalse(DesignerSaveNotifications.isPending(ticket));
            assertTrue(pendingTypedRequests(client).isEmpty());
        } finally {
            if (ticket != null && DesignerSaveNotifications.isPending(ticket)) {
                DesignerSaveNotifications.failExact(ticket, "cleanup");
            }
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void typedListPageRollsBackBeforeBoundedExactReplayAndPublishesCompleteMembership() throws Exception {
        Probe probe = new Probe("second-tab", tempDir);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        try {
            establishCurrentConnection(client, probe.manager, transport);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            UUID requestId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            UUID traceId = UUID.randomUUID();
            bindList(client, requestId, correlationId, traceId);
            ResourceDocument<Map<String, Object>> first = tabDocument("first-tab", UUID.randomUUID());
            ResourceDocument<Map<String, Object>> second = tabDocument("second-tab", UUID.randomUUID());
            ResourcePage<Map<String, Object>> page = new ResourcePage<>(List.of(first, second), null, true);
            ProtocolEnvelope<Map<String, Object>> envelope = pageEnvelope(requestId, correlationId, traceId, page);

            invokePage(client, envelope, 1);

            assertEquals(0, probe.manager.notifications.size());
            assertTrue(awaitUi(() -> probe.manager.listApplied.getCount() == 0L));
            assertEquals(List.of(ReSyncResourceType.TAB, ReSyncResourceType.TAB), probe.manager.notifications);
            assertEquals(List.of("first-tab", "second-tab"), probe.manager.appliedList);
            assertTrue(pendingTypedRequests(client).isEmpty());
        } finally {
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void scopedPageRollbackPreservesAnUnrelatedInterleavedAuthorityCommit() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), new ScriptedReSyncTransport(), null,
            new ReSyncCatalogPublicationCache());
        try {
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            ResourceDocument<Map<String, Object>> first = tabDocument("page-a", UUID.randomUUID());
            ResourceDocument<Map<String, Object>> unrelated = tabDocument("event-b", UUID.randomUUID());
            ReSyncResourceRevisionReconciler.Admission pageAdmission = client.resourceRevisionReconciler().prepare(
                result(first));
            assertTrue(pageAdmission.accepted());
            assertTrue(client.resourceRevisionReconciler().commit(pageAdmission).accepted());
            assertTrue(client.resourceRevisionReconciler().apply(result(unrelated)).accepted());

            assertTrue(client.resourceRevisionReconciler().rollback(pageAdmission));
            assertNotNull(client.resourceRevisionReconciler().get(SERVER.canonicalText(),
                ReSyncResourceType.TAB.typeId(), "event-b"));
            assertEquals(unrelated.mutationId().toString(), client.resourceRevisionReconciler().get(
                SERVER.canonicalText(), ReSyncResourceType.TAB.typeId(), "event-b").mutationId());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void newerLocalStateStillReceivesOneAuthoritativeCreationCompletion() throws Exception {
        Probe probe = new Probe("never", tempDir);
        TabDefinition stale = new TabDefinition("observer-tab");
        stale.setHeader("Stale");
        TabDefinition current = new TabDefinition("observer-tab");
        current.setHeader("Current");
        cacheAuthoritativeTab(probe.manager, current);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<FlowManager.CreationResult> observed = new AtomicReference<>();
        Consumer<FlowManager.CreationResult> observer = result -> {
            observed.set(result);
            callbacks.incrementAndGet();
            completed.countDown();
        };
        Object transaction = creationTransaction(stale, observer);
        try {
            assertTrue(await(() -> creationJournalLoaded(probe.manager)));
            putCreationTransaction(probe.manager, transaction);
            invokeCompleteCreation(probe.manager, transaction);
            assertTrue(awaitUi(() -> completed.getCount() == 0L), () -> creationState(transaction));
            invokeCompleteCreation(probe.manager, transaction);

            assertEquals(1, callbacks.get());
            assertNotNull(observed.get());
            assertEquals(ReSyncResourceType.TAB.typeId(), observed.get().type());
            assertEquals("observer-tab", observed.get().id());
            assertTrue(observed.get().resource() instanceof TabDefinition);
            TabDefinition authoritative = (TabDefinition) observed.get().resource();
            assertEquals("observer-tab", authoritative.getId());
            assertEquals("Current", authoritative.getHeader());
            assertEquals(ReSyncResourceType.TAB.serialize(current), ReSyncResourceType.TAB.serialize(authoritative));
            assertFalse(ReSyncResourceType.TAB.serialize(stale).equals(ReSyncResourceType.TAB.serialize(authoritative)));
            assertTrue(creationTransactions(probe.manager).isEmpty());
        } finally {
            probe.close();
        }
    }

    @Test
    void aggregateJournalSettlesWithoutTransientSaveCallback() throws Exception {
        Probe probe = new Probe("never", tempDir);
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, probe,
            new ReSyncCatalogPublicationCache());
        ReSyncResourceRevisionReconciler.Snapshot previousReconciler = client.resourceRevisionReconciler().snapshot();
        Map<String, Long> worldGenEpochs = worldGenAuthorityEpochs();
        Long previousWorldGenEpoch = worldGenEpochs.put(SERVER.canonicalText(), 1L);
        TabDefinition created = new TabDefinition("aggregate-tab");
        Map<String, Object> payload = payload(ReSyncResourceType.TAB, created);
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Aggregate Tab",
            "Tabs/aggregate-tab.json", 4);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<FlowManager.CreationResult> observed = new AtomicReference<>();
        FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(
            ReSyncResourceType.TAB.typeId(), created.getId(), presentation.displayName(), presentation.path(), "",
            presentation.sortOrder(), false);
        Object transaction = creationTransaction(created, result -> {
            observed.set(result);
            callbacks.incrementAndGet();
            completed.countDown();
        }, REQUEST, MUTATION, metadata);
        setNestedField(transaction, "payloadCommitted", false);
        setNestedField(transaction, "newerLocalState", false);
        setNestedField(transaction, "settling", true);
        setCreationPhase(transaction, "PAYLOAD");
        try {
            establishCurrentConnection(client, probe.manager, transport);
            client.resourceRevisionReconciler().observeAuthorityEpoch(SERVER.canonicalText(), 1L);
            assertTrue(await(() -> creationJournalLoaded(probe.manager)));
            putCreationTransaction(probe.manager, transaction);
            Method captureFence = ReSyncFlowClient.class.getDeclaredMethod("captureResourceSaveFence");
            captureFence.setAccessible(true);
            Object fence = captureFence.invoke(client);
            assertNotNull(fence);
            assertTrue(enqueueResourceSave(client, fence));
            ResourceCreateRequest<Map<String, Object>> operation = new ResourceCreateRequest<>(
                resource(ReSyncResourceType.TAB, created.getId()), ResourcePayloadCodecs.json().canonicalize(payload),
                MUTATION, presentation);
            setField(client, "genericResourceContractVersion", new CatalogVersion(1, 2));
            invokeTypedRequest(client, ReSyncResourceType.TAB, operation, operation.resource(), 0L, MUTATION,
                ResourcePayloadCodecs.json().canonicalize(payload).checksum(), true, false, null,
                ReSyncResourceType.TAB.serialize(created), REQUEST.toString(), false, REQUEST, CORRELATION, TRACE);
            pendingCreatePresentations(client).put(MUTATION, presentation);

            assertFalse(probe.manager.settleAggregateCreation(SERVER.canonicalText(), ReSyncResourceType.TAB,
                created.getId(), UUID.randomUUID(), MUTATION));
            assertFalse(probe.manager.settleAggregateCreation(SERVER.canonicalText(), ReSyncResourceType.TAB,
                created.getId(), REQUEST, UUID.randomUUID()));
            invokeAggregate(client, aggregateEnvelope(payload, presentation), 1);
            assertTrue(awaitUi(() -> completed.getCount() == 0L), () -> creationState(transaction));

            assertEquals(1, callbacks.get());
            assertNotNull(observed.get());
            assertEquals(ReSyncResourceType.TAB.typeId(), observed.get().type());
            assertEquals(created.getId(), observed.get().id());
            assertTrue(observed.get().resource() instanceof TabDefinition);
            assertTrue(creationTransactions(probe.manager).isEmpty());
            assertTrue(pendingTypedRequests(client).isEmpty());
            assertTrue(pendingCreatePresentations(client).isEmpty());

            invokeAggregate(client, aggregateEnvelope(payload, presentation), 1);
            assertEquals(1, callbacks.get());
        } finally {
            client.resourceRevisionReconciler().restore(previousReconciler);
            restoreWorldGenEpoch(worldGenEpochs, previousWorldGenEpoch);
            client.shutdown();
            probe.close();
        }
    }

    private static DesignerSaveNotifications.SaveTicket bindAggregateCreate(ReSyncFlowClient client,
                                                                              TabDefinition tab,
                                                                              Map<String, Object> payload,
                                                                              ResourcePresentationIntent presentation)
        throws Exception {
        Method captureFence = ReSyncFlowClient.class.getDeclaredMethod("captureResourceSaveFence");
        captureFence.setAccessible(true);
        Object fence = captureFence.invoke(client);
        assertNotNull(fence);
        assertTrue(enqueueResourceSave(client, fence));
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startSilentResumableExact(
            SERVER.canonicalText(), ReSyncResourceType.TAB, tab.getId(), "Tab", REQUEST, MUTATION);
        ResourceCreateRequest<Map<String, Object>> operation = new ResourceCreateRequest<>(
            resource(ReSyncResourceType.TAB, tab.getId()), ResourcePayloadCodecs.json().canonicalize(payload), MUTATION,
            presentation);
        setField(client, "genericResourceContractVersion", new CatalogVersion(1, 2));
        invokeTypedRequest(client, ReSyncResourceType.TAB, operation, operation.resource(), 0L, MUTATION,
            ResourcePayloadCodecs.json().canonicalize(payload).checksum(), true, false, null,
            ReSyncResourceType.TAB.serialize(tab), REQUEST.toString(), false, REQUEST, CORRELATION, TRACE);
        pendingCreatePresentations(client).put(MUTATION, presentation);
        return ticket;
    }

    private static void bindList(ReSyncFlowClient client, UUID requestId, UUID correlationId, UUID traceId)
        throws Exception {
        ContractRef<ResourceTypeId> type = ContractRef.of(OWNER, ResourceTypeId.of(ReSyncResourceType.TAB.typeId()));
        ResourceListRequest operation = new ResourceListRequest(type, null, 100, null);
        invokeTypedRequest(client, ReSyncResourceType.TAB, operation, null, 0L, null, null, false, false,
            null, null, null, false, requestId, correlationId, traceId);
        activeListCorrelations(client).put(newTypedListKey(ReSyncResourceType.TAB), correlationId);
        typedListResults(client).put(correlationId, new ArrayList<>());
    }

    private static Object invokeTypedRequest(ReSyncFlowClient client, Object... arguments) throws Exception {
        Method method = List.of(ReSyncFlowClient.class.getDeclaredMethods()).stream()
            .filter(candidate -> candidate.getName().equals("typedResourceRequest")
                && candidate.getParameterCount() == 15)
            .findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(client, arguments);
    }

    private static ProtocolEnvelope<Map<String, Object>> aggregateEnvelope(Map<String, Object> payload,
                                                                            ResourcePresentationIntent presentation) {
        ResourceDocument<Map<String, Object>> primary = ResourceDocument.live(resource(ReSyncResourceType.TAB,
                "aggregate-tab"), 1L, MUTATION, ResourcePayloadCodecs.json().canonicalize(payload),
            ResourceActivationState.ACTIVE, "server");
        ResourceDocument<Map<String, Object>> metadata = metadataDocument(presentation);
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.ACK, new CatalogVersion(1, 2), UUID.randomUUID(), REQUEST,
            CORRELATION, TRACE, SERVER, primary.resource(), primary.revision(), 1L, MUTATION,
            ContractRef.of(OWNER, OperationId.of("resource.create")), CAPABILITIES,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.create.result")), null, primary.payloadHash(), false,
            null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourceCreateResponse(new ResourceCreateResult(primary, metadata, presentation)));
    }

    private static ResourceDocument<Map<String, Object>> metadataDocument(ResourcePresentationIntent presentation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverId", SERVER.canonicalText());
        payload.put("folders", List.of());
        payload.put("resources", List.of(Map.of("type", ReSyncResourceType.TAB.typeId(), "id", "aggregate-tab",
            "displayName", presentation.displayName(), "path", presentation.path(), "sortOrder",
            presentation.sortOrder())));
        payload.put("installedBundles", List.of());
        payload.put("openDocuments", List.of());
        payload.put("selectedResourceKey", "");
        return ResourceDocument.live(resource(ReSyncResourceType.PROJECT_METADATA, SERVER.canonicalText()), 2L,
            MUTATION, ResourcePayloadCodecs.json().canonicalize(payload), ResourceActivationState.ACTIVE, "server");
    }

    private static ResourceDocument<Map<String, Object>> tabDocument(String id, UUID mutationId) {
        return ResourceDocument.live(resource(ReSyncResourceType.TAB, id), 1L, mutationId,
            ResourcePayloadCodecs.json().canonicalize(payload(ReSyncResourceType.TAB, new TabDefinition(id))),
            ResourceActivationState.ACTIVE, "server");
    }

    private static ReSyncResourceRevisionReconciler.ResourceResult result(
        ResourceDocument<Map<String, Object>> document) {
        return new ReSyncResourceRevisionReconciler.ResourceResult(SERVER.canonicalText(),
            ReSyncResourceType.TAB.typeId(), document.resource().id(), document.revision(),
            document.mutationId().toString(), document.payloadHash().canonicalText(), false,
            new Gson().toJsonTree(document.payload()).getAsJsonObject(), 1L);
    }

    private static ProtocolEnvelope<Map<String, Object>> pageEnvelope(UUID requestId, UUID correlationId,
                                                                       UUID traceId,
                                                                       ResourcePage<Map<String, Object>> page) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, new CatalogVersion(1, 0), UUID.randomUUID(),
            requestId, correlationId, traceId, SERVER, null, 0L, 1L, null,
            ContractRef.of(OWNER, OperationId.of("resource.list")), GENERIC_CAPABILITIES,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.page")), null, null, false, null, null, null, null,
            null, 1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourcePageResponse(ResourceOperationKind.LIST, page));
    }

    private static void invokeAggregate(ReSyncFlowClient client, ProtocolEnvelope<Map<String, Object>> envelope,
                                        int generation) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("handleAggregateResourceCreateEnvelope",
            ProtocolEnvelope.class, ProtocolBody.ResourceCreateResponse.class, int.class);
        method.setAccessible(true);
        method.invoke(client, envelope, envelope.body(), generation);
    }

    private static void invokePage(ReSyncFlowClient client, ProtocolEnvelope<Map<String, Object>> envelope,
                                   int generation) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("handleTypedResourcePage", ProtocolEnvelope.class,
            ProtocolBody.ResourcePageResponse.class, int.class);
        method.setAccessible(true);
        method.invoke(client, envelope, envelope.body(), generation);
    }

    private static void invokeDocument(ReSyncFlowClient client, ProtocolEnvelope<Map<String, Object>> envelope,
                                       int generation) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("handleProtocolEnvelope", byte[].class, int.class);
        method.setAccessible(true);
        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
        method.invoke(client, codec.encodeBytes(envelope), generation);
    }

    private static boolean enqueueResourceSave(ReSyncFlowClient client, Object fence) throws Exception {
        return enqueueResourceSave(client, fence, "aggregate-tab", REQUEST);
    }

    private static boolean enqueueResourceSave(ReSyncFlowClient client, Object fence, String id, UUID requestId)
        throws Exception {
        Class<?> keyType = Class.forName(ReSyncFlowClient.class.getName() + "$ResourceSaveKey");
        Constructor<?> keyConstructor = keyType.getDeclaredConstructor(ReSyncResourceType.class, String.class);
        keyConstructor.setAccessible(true);
        Object key = keyConstructor.newInstance(ReSyncResourceType.TAB, id);
        Method method = List.of(ReSyncFlowClient.class.getDeclaredMethods()).stream()
            .filter(candidate -> candidate.getName().equals("enqueueResourceSave")
                && candidate.getParameterCount() == 8)
            .findFirst().orElseThrow();
        method.setAccessible(true);
        return (Boolean) method.invoke(client, key, requestId.toString(), -1L, fence, null,
            (BooleanSupplier) () -> true, (Runnable) () -> {}, (Runnable) () -> {});
    }

    private static void establishCurrentConnection(ReSyncFlowClient client, FlowManager manager,
                                                   ReSyncFrameTransport transport) throws Exception {
        FlowManagerTestConnection.install(manager, SERVER.canonicalText(), client, transport);
        setField(client, "authorityEpochAdvertised", true);
        setField(client, "authorityEpochEstablished", true);
        setField(client, "handshakeAuthorityEpoch", 1L);
        setField(client, "genericResourceContractVersion", new CatalogVersion(1, 0));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> pendingTypedRequests(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingTypedResourceRequests");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ResourcePresentationIntent> pendingCreatePresentations(ReSyncFlowClient client)
        throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingCreatePresentations");
        field.setAccessible(true);
        return (Map<UUID, ResourcePresentationIntent>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, UUID> activeListCorrelations(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("activeTypedResourceListCorrelations");
        field.setAccessible(true);
        return (Map<Object, UUID>) field.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, List<String>> typedListResults(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("typedResourceListResults");
        field.setAccessible(true);
        return (Map<UUID, List<String>>) field.get(client);
    }

    private static Object newTypedListKey(ReSyncResourceType type) throws Exception {
        Class<?> keyType = Class.forName(ReSyncFlowClient.class.getName() + "$TypedResourceListKey");
        Constructor<?> constructor = keyType.getDeclaredConstructor(ReSyncResourceType.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(type, null);
    }

    private static Object creationTransaction(TabDefinition resource,
                                              Consumer<FlowManager.CreationResult> observer) throws Exception {
        FlowManager.CreationMetadataIntent metadata = new FlowManager.CreationMetadataIntent(
            ReSyncResourceType.TAB.typeId(), resource.getId(), "Observer Tab", "Tabs/observer-tab.json", "", 0,
            false);
        return creationTransaction(resource, observer, UUID.randomUUID(), UUID.randomUUID(), metadata);
    }

    private static Object creationTransaction(TabDefinition resource, Consumer<FlowManager.CreationResult> observer,
                                              UUID requestId, UUID mutationId,
                                              FlowManager.CreationMetadataIntent metadata) throws Exception {
        return creationTransaction(ReSyncResourceType.TAB, resource, observer, requestId, mutationId, metadata);
    }

    private static Object creationTransaction(ReSyncResourceType type, Object resource,
                                              Consumer<FlowManager.CreationResult> observer, UUID requestId,
                                              UUID mutationId, FlowManager.CreationMetadataIntent metadata)
        throws Exception {
        Class<?> keyType = Class.forName(FlowManager.class.getName() + "$CreationKey");
        Constructor<?> keyConstructor = keyType.getDeclaredConstructor(String.class, String.class, String.class);
        keyConstructor.setAccessible(true);
        String id = type.extractId(resource);
        Object key = keyConstructor.newInstance(SERVER.canonicalText(), type.typeId(), id);
        Class<?> transactionType = Class.forName(FlowManager.class.getName() + "$CreationTransaction");
        Constructor<?> constructor = List.of(transactionType.getDeclaredConstructors()).stream()
            .filter(candidate -> candidate.getParameterCount() == 17)
            .findFirst().orElseThrow();
        constructor.setAccessible(true);
        String payloadJson = type.serialize(resource);
        Object transaction = constructor.newInstance(key, type, resource, null, type.displayName(),
            resource(type, id), ResourcePayloadCodecs.json().canonicalize(payload(type, resource)).checksum(), requestId,
            mutationId, metadata,
            null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), observer, 1L, 1L);
        setNestedField(transaction, "payloadJson", payloadJson);
        setNestedField(transaction, "payloadCommitted", true);
        setNestedField(transaction, "newerLocalState", true);
        Class<?> phaseType = Class.forName(FlowManager.class.getName() + "$CreationPhase");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object settlement = Enum.valueOf((Class) phaseType, "SETTLEMENT");
        setNestedField(transaction, "phase", settlement);
        return transaction;
    }

    private static void setNestedField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String creationState(Object transaction) {
        try {
            List<String> fields = new ArrayList<>();
            for (String name : List.of("phase", "payloadCommitted", "payloadSettlement", "settling", "journalSettled",
                "journalWriteQueued", "suspended")) {
                Field field = transaction.getClass().getDeclaredField(name);
                field.setAccessible(true);
                fields.add(name + "=" + field.get(transaction));
            }
            return String.join(",", fields);
        } catch (ReflectiveOperationException exception) {
            return exception.toString();
        }
    }

    private static boolean creationJournalLoaded(FlowManager manager) {
        try {
            Field field = FlowManager.class.getDeclaredField("creationJournalLoaded");
            field.setAccessible(true);
            return field.getBoolean(manager);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static void setCreationPhase(Object transaction, String phase) throws Exception {
        Class<?> phaseType = Class.forName(FlowManager.class.getName() + "$CreationPhase");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object value = Enum.valueOf((Class) phaseType, phase);
        setNestedField(transaction, "phase", value);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> creationTransactions(FlowManager manager) throws Exception {
        Field field = FlowManager.class.getDeclaredField("creationTransactions");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(manager);
    }

    private static void putCreationTransaction(FlowManager manager, Object transaction) throws Exception {
        Field keyField = transaction.getClass().getDeclaredField("key");
        keyField.setAccessible(true);
        creationTransactions(manager).put(keyField.get(transaction), transaction);
    }

    private static void invokeCompleteCreation(FlowManager manager, Object transaction) throws Exception {
        Method method = FlowManager.class.getDeclaredMethod("completeCreation", transaction.getClass());
        method.setAccessible(true);
        method.invoke(manager, transaction);
    }

    private static void cacheAuthoritativeTab(FlowManager manager, TabDefinition tab) throws Exception {
        Field field = FlowManager.class.getDeclaredField("tabStore");
        field.setAccessible(true);
        Object store = field.get(manager);
        Method cache = store.getClass().getDeclaredMethod("cache", String.class, Object.class);
        cache.setAccessible(true);
        cache.invoke(store, SERVER.canonicalText(), tab);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> worldGenAuthorityEpochs() throws Exception {
        Field projectStore = WorldGenManager.class.getDeclaredField("projectStore");
        projectStore.setAccessible(true);
        Object store = projectStore.get(WorldGenManager.getInstance());
        Field authorityEpochs = store.getClass().getDeclaredField("authorityEpochs");
        authorityEpochs.setAccessible(true);
        return (Map<String, Long>) authorityEpochs.get(store);
    }

    private static void restoreWorldGenEpoch(Map<String, Long> epochs, Long previous) {
        if (previous == null) {
            epochs.remove(SERVER.canonicalText());
        } else {
            epochs.put(SERVER.canonicalText(), previous);
        }
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private static boolean awaitUi(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            ScreenManager.getInstance().processTasks();
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        ScreenManager.getInstance().processTasks();
        return condition.getAsBoolean();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(ReSyncResourceType type, Object value) {
        return new Gson().fromJson(type.serialize(value), Map.class);
    }

    private static ServerResourceLocator resource(ReSyncResourceType type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type.typeId())), id);
    }

    private static final class Probe extends RemotelyClient {
        private final RetryManager manager;

        private Probe(String failFirstId, Path stateRoot) {
            super(null);
            manager = new RetryManager(this, failFirstId, stateRoot);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class RetryManager extends FlowManager {
        private final String failFirstId;
        private final AtomicBoolean firstFailure = new AtomicBoolean(true);
        private final AtomicInteger tabCacheAttempts = new AtomicInteger();
        private final List<ReSyncResourceType> notifications = new CopyOnWriteArrayList<>();
        private final CountDownLatch listApplied = new CountDownLatch(1);
        private volatile List<String> appliedList = List.of();
        private boolean capturePreflightFailure;
        private final AtomicInteger preflightFailures = new AtomicInteger();
        private final AtomicReference<Boolean> preflightRetryable = new AtomicReference<>();

        private RetryManager(RemotelyClient client, String failFirstId, Path stateRoot) {
            super(client, null, null, stateRoot);
            this.failFirstId = failFirstId;
        }

        @Override
        boolean handleResumableResourceSaveFailure(String serverId, ReSyncResourceType type, String id,
                                                   String requestId, boolean retryable, String message) {
            if (!capturePreflightFailure) {
                return super.handleResumableResourceSaveFailure(serverId, type, id, requestId, retryable, message);
            }
            preflightRetryable.set(retryable);
            DesignerSaveNotifications.failRequest(serverId, requestId, message);
            preflightFailures.incrementAndGet();
            return true;
        }

        @Override
        public ServerConnectionToken captureServerConnectionToken(String serverId, ReSyncFlowClient source) {
            return new ServerConnectionToken(serverId, source, source != null ? 1L : 0L);
        }

        @Override
        public boolean isCurrentServerConnection(ServerConnectionToken token) {
            return token != null && token.source() != null && token.generation() == 1L;
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
        boolean cacheResourceAuthoritative(String serverId, ReSyncResourceType type, Object item, long draftVersion,
                                            ResourceProjectionLease lease) {
            if (type == ReSyncResourceType.TAB) {
                tabCacheAttempts.incrementAndGet();
                String id = ((TabDefinition) item).getId();
                if (failFirstId.equals(id) && firstFailure.compareAndSet(true, false)) {
                    return false;
                }
            }
            return super.cacheResourceAuthoritative(serverId, type, item, draftVersion, lease);
        }

        @Override
        boolean markResourceSavedAuthoritative(String serverId, ReSyncResourceType type, String id,
                                               long draftVersion, long revision, String hash,
                                               ResourceProjectionLease lease) {
            return true;
        }

        @Override
        void notifyResourceDataReceivedAfterCommit(String serverId, ReSyncResourceType type, Object item) {
            notifications.add(type);
        }

        @Override
        public void applyServerTabList(String serverId, List<String> tabIds) {
            appliedList = List.copyOf(tabIds);
            listApplied.countDown();
        }

        @Override
        void refreshStudioWorkspace(String serverId, boolean rebuildContentBrowser) {
        }
    }
}
