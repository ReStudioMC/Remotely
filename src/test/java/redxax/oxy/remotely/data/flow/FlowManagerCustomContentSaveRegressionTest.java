package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.platform.jvm.JvmTaskScheduler;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.ui.core.Screen;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowManagerCustomContentSaveRegressionTest {
    private static final ServerId SERVER = ServerId.deterministic("custom-content-save-regression");
    private static final String SERVER_ID = SERVER.canonicalText();
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1L, "a".repeat(64), "b".repeat(64));

    @TempDir
    Path temporaryDirectory;

    @Test
    void customContentSaveUsesTypedSaveAckAndRejectsStaleAuthority() throws Exception {
        Probe probe = new Probe(temporaryDirectory.resolve("manager"));
        Harness harness = null;
        try {
            harness = connect(probe);
            CustomContentDefinition authoritative = content("custom-item", "Original");
            JsonObject authoritativeJson = JsonParser.parseString(FlowSerializer.serializeCustomContent(authoritative))
                .getAsJsonObject();
            CanonicalPayload<Map<String, Object>> authoritativePayload = canonical(authoritativeJson);
            UUID authoritativeMutation = UUID.randomUUID();
            CustomContentAuthoringPeer peer = harness.peer;
            peer.seed(authoritativePayload, 1L, authoritativeMutation);
            harness.client.resourceRevisionReconciler().apply(new ReSyncResourceRevisionReconciler.ResourceResult(
                SERVER_ID, ReSyncResourceType.CUSTOM_CONTENT.typeId(), authoritative.getId(), 1L,
                authoritativeMutation.toString(), authoritativePayload.checksum().canonicalText(), false,
                authoritativeJson, 1L));
            probe.manager.cacheCustomContent(SERVER_ID, authoritative);

            FlowManager.CustomContentAuthority authority = probe.manager.customContentAuthority(SERVER_ID,
                authoritative.getId());
            assertNotNull(authority);
            CustomContentDefinition edited = content(authoritative.getId(), "Edited");
            DesignerSaveNotifications.SaveTicket ticket = ticket(authoritative.getId());
            AtomicReference<Boolean> saved = new AtomicReference<>();
            ticket.whenFinished((value, ignored) -> saved.set(value));

            assertTrue(probe.manager.saveCustomContent(SERVER_ID, edited, ticket, authority));
            awaitSave(harness, peer, saved);

            ProtocolEnvelope<Map<String, Object>> request = peer.lastSaveRequest();
            assertNotNull(request);
            assertTrue(request.body() instanceof ProtocolBody.ResourceRequest);
            ProtocolBody.ResourceRequest body = (ProtocolBody.ResourceRequest) request.body();
            assertTrue(body.operation() instanceof ResourceSaveRequest<?>);
            ResourceSaveRequest<?> save = (ResourceSaveRequest<?>) body.operation();
            assertEquals(ReSyncResourceType.CUSTOM_CONTENT.typeId(), save.resource().resourceType().value());
            assertEquals(authoritative.getId(), save.resource().id());
            assertEquals(1L, save.expectedRevision());
            assertEquals(ticket.requestId(), request.requestId().toString());
            assertEquals(ticket.mutationId(), save.mutationId().toString());
            assertEquals(1, peer.ackCount());
            assertEquals(Boolean.TRUE, saved.get(), "Typed ACK did not settle save ticket: "
                + "ticketRequest=" + ticket.requestId() + ",wireRequest=" + request.requestId()
                + ",ticketMutation=" + ticket.mutationId() + ",wireMutation=" + save.mutationId()
                + ",ackRevision=" + peer.currentRevision() + ",reconciler="
                + harness.client.resourceRevisionReconciler().get(SERVER_ID,
                    ReSyncResourceType.CUSTOM_CONTENT.typeId(), authoritative.getId())
                + ",managerGeneration=" + probe.manager.getCustomContentRevision(SERVER_ID, authoritative.getId())
                + ",managerState=" + probe.manager.getCustomContentState(SERVER_ID, authoritative.getId())
                + ",pending=" + DesignerSaveNotifications.isPending(ticket));
            assertFalse(DesignerSaveNotifications.isPending(ticket));
            assertEquals(2L, harness.client.resourceRevisionReconciler().revision(SERVER_ID,
                ReSyncResourceType.CUSTOM_CONTENT.typeId(), authoritative.getId()));

            ReSyncResourceRevisionReconciler.ResourceResult newer = new ReSyncResourceRevisionReconciler.ResourceResult(
                SERVER_ID, ReSyncResourceType.CUSTOM_CONTENT.typeId(), authoritative.getId(), 3L,
                UUID.randomUUID().toString(), authoritativePayload.checksum().canonicalText(), false,
                authoritativeJson, 1L);
            harness.client.resourceRevisionReconciler().apply(newer);
            DesignerSaveNotifications.SaveTicket staleTicket = ticket(authoritative.getId());
            AtomicReference<Boolean> staleSaved = new AtomicReference<>();
            staleTicket.whenFinished((value, ignored) -> staleSaved.set(value));
            assertFalse(probe.manager.saveCustomContent(SERVER_ID, edited, staleTicket, authority));
            ReSyncFlowClientTestHarness.drain(harness.client);
            assertEquals(Boolean.FALSE, staleSaved.get());
            assertEquals(1, peer.ackCount());
        } finally {
            if (harness != null) {
                harness.close();
            }
            probe.close();
        }
    }

    @Test
    void pendingReceiptReplaysTheSameTypedMutationAndSettlesItsOriginalTicket() throws Exception {
        TaskScheduler previousScheduler = TaskSchedulers.current();
        JvmTaskScheduler scheduler = new JvmTaskScheduler();
        Probe probe = new Probe(temporaryDirectory.resolve("pending-receipt"));
        TaskSchedulers.configure(scheduler);
        Harness harness = null;
        try {
            harness = connect(probe);
            CustomContentDefinition authoritative = content("custom-item", "Original");
            JsonObject authoritativeJson = JsonParser.parseString(FlowSerializer.serializeCustomContent(authoritative))
                .getAsJsonObject();
            CanonicalPayload<Map<String, Object>> authoritativePayload = canonical(authoritativeJson);
            UUID authoritativeMutation = UUID.randomUUID();
            harness.peer.seed(authoritativePayload, 1L, authoritativeMutation);
            harness.peer.delayAckOnce = true;
            harness.client.resourceRevisionReconciler().apply(new ReSyncResourceRevisionReconciler.ResourceResult(
                SERVER_ID, ReSyncResourceType.CUSTOM_CONTENT.typeId(), authoritative.getId(), 1L,
                authoritativeMutation.toString(), authoritativePayload.checksum().canonicalText(), false,
                authoritativeJson, 1L));
            probe.manager.cacheCustomContent(SERVER_ID, authoritative);

            DesignerSaveNotifications.SaveTicket ticket = ticket(authoritative.getId());
            AtomicReference<Boolean> saved = new AtomicReference<>();
            ticket.whenFinished((value, ignored) -> saved.set(value));
            assertTrue(probe.manager.saveCustomContent(SERVER_ID, content(authoritative.getId(), "Edited"), ticket,
                probe.manager.customContentAuthority(SERVER_ID, authoritative.getId())));

            long deadline = System.nanoTime() + 6_000_000_000L;
            while (System.nanoTime() < deadline && saved.get() == null) {
                ReSyncFlowClientTestHarness.drain(harness.client);
                harness.peer.pump();
                LockSupport.parkNanos(1_000_000L);
            }
            assertEquals(Boolean.TRUE, saved.get(), "requests=" + harness.peer.saveRequests.size()
                + ",acks=" + harness.peer.ackCount() + ",revision=" + harness.peer.currentRevision()
                + ",pending=" + DesignerSaveNotifications.isPending(ticket));
            assertEquals(2, harness.peer.saveRequests.size());
            assertEquals(ticket.requestId(), harness.peer.saveRequests.getFirst().requestId().toString());
            assertEquals(harness.peer.saveRequests.getFirst().requestId(), harness.peer.saveRequests.getLast().requestId());
            assertEquals(harness.peer.saveRequests.getFirst().mutationId(), harness.peer.saveRequests.getLast().mutationId());
            assertEquals(2L, harness.peer.currentRevision());
            assertEquals(1, harness.peer.ackCount());
        } finally {
            if (harness != null) {
                harness.close();
            }
            probe.close();
            TaskSchedulers.configure(previousScheduler);
            scheduler.close();
        }
    }

    @Test
    void retainedTypedSaveRestoresItsFenceBeforeAnAuthoritativeReplayAck() throws Exception {
        Probe probe = new Probe(temporaryDirectory.resolve("retained-save"));
        Harness harness = null;
        try {
            harness = connect(probe);
            CustomContentDefinition authoritative = content("custom-item", "Original");
            JsonObject authoritativeJson = JsonParser.parseString(FlowSerializer.serializeCustomContent(authoritative))
                .getAsJsonObject();
            CanonicalPayload<Map<String, Object>> authoritativePayload = canonical(authoritativeJson);
            UUID authoritativeMutation = UUID.randomUUID();
            harness.peer.seed(authoritativePayload, 1L, authoritativeMutation);
            harness.client.resourceRevisionReconciler().apply(new ReSyncResourceRevisionReconciler.ResourceResult(
                SERVER_ID, ReSyncResourceType.CUSTOM_CONTENT.typeId(), authoritative.getId(), 1L,
                authoritativeMutation.toString(), authoritativePayload.checksum().canonicalText(), false,
                authoritativeJson, 1L));
            probe.manager.cacheCustomContent(SERVER_ID, authoritative);

            DesignerSaveNotifications.SaveTicket ticket = ticket(authoritative.getId());
            AtomicReference<Boolean> saved = new AtomicReference<>();
            ticket.whenFinished((value, ignored) -> saved.set(value));
            assertTrue(probe.manager.saveCustomContent(SERVER_ID, content(authoritative.getId(), "Edited"), ticket,
                probe.manager.customContentAuthority(SERVER_ID, authoritative.getId())));
            ProtocolEnvelope<Map<String, Object>> first = awaitTypedSaveRequest(harness);
            harness.peer.handled.add(first.messageId());
            harness.peer.saveRequests.add(first);

            Field pendingField = ReSyncFlowClient.class.getDeclaredField("pendingTypedResourceRequests");
            pendingField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> pendingRequests = (Map<UUID, Object>) pendingField.get(harness.client);
            Object pending = pendingRequests.get(first.requestId());
            assertNotNull(pending);
            Method retain = ReSyncFlowClient.class.getDeclaredMethod("retainTypedTransportMutation", pending.getClass());
            retain.setAccessible(true);
            assertTrue((Boolean) retain.invoke(harness.client, pending));
            Method retireSaves = ReSyncFlowClient.class.getDeclaredMethod("retireResourceSavesWithoutDispatch");
            retireSaves.setAccessible(true);
            retireSaves.invoke(harness.client);
            Field generationField = ReSyncFlowClient.class.getDeclaredField("activeTransportGeneration");
            generationField.setAccessible(true);
            int generation = generationField.getInt(harness.client);
            Method replay = ReSyncFlowClient.class.getDeclaredMethod("retryRetainedTransportMutations", int.class);
            replay.setAccessible(true);
            replay.invoke(harness.client, generation);

            long deadline = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < deadline && saved.get() == null) {
                ReSyncFlowClientTestHarness.drain(harness.client);
                harness.peer.pump();
                LockSupport.parkNanos(1_000_000L);
            }
            assertEquals(Boolean.TRUE, saved.get());
            assertEquals(2, harness.peer.saveRequests.size());
            assertEquals(first.requestId(), harness.peer.saveRequests.getLast().requestId());
            assertEquals(first.mutationId(), harness.peer.saveRequests.getLast().mutationId());
            assertEquals(2L, harness.peer.currentRevision());
        } finally {
            if (harness != null) {
                harness.close();
            }
            probe.close();
        }
    }

    private static ProtocolEnvelope<Map<String, Object>> awaitTypedSaveRequest(Harness harness) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            ReSyncFlowClientTestHarness.drain(harness.client);
            for (ReSyncDecodedFrame frame : harness.transport.sentFrames()) {
                if (frame.messageType() != ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                    continue;
                }
                ProtocolEnvelope<Map<String, Object>> request = CustomContentAuthoringPeer.ENVELOPES.decodeBytes(frame.payload());
                if (request.body() instanceof ProtocolBody.ResourceRequest body
                    && body.operation() instanceof ResourceSaveRequest<?>) {
                    return request;
                }
            }
            LockSupport.parkNanos(1_000_000L);
        }
        throw new AssertionError("Typed resource save was not dispatched");
    }

    private static DesignerSaveNotifications.SaveTicket ticket(String id) {
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(SERVER_ID,
            ReSyncResourceType.CUSTOM_CONTENT, id, id);
        assertNotNull(ticket);
        assertTrue(DesignerSaveNotifications.attachRequestId(ticket, UUID.randomUUID().toString()));
        assertTrue(DesignerSaveNotifications.attachMutationId(ticket, UUID.randomUUID().toString()));
        return ticket;
    }

    private static CustomContentDefinition content(String id, String name) {
        CustomContentDefinition content = new CustomContentDefinition();
        content.setId(id);
        content.setType("item");
        content.setDisplayName(name);
        content.setMaterial("STICK");
        return content;
    }

    @SuppressWarnings("unchecked")
    private static CanonicalPayload<Map<String, Object>> canonical(JsonObject payload) {
        Map<String, Object> map = new Gson().fromJson(payload, Map.class);
        return ResourcePayloadCodecs.json().canonicalize(map);
    }

    private static Harness connect(Probe probe) throws Exception {
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncCatalogPublicationCache cache = new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(probe.stateRoot.resolve("catalog.json")));
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, probe, cache);
        FlowManagerTestConnection.installCurrent(probe.manager, SERVER_ID, client, transport);
        client.connect().join();
        CatalogCachePublication publication = publication();
        JsonObject capabilities = protocolCapabilities(publication);
        transport.receiveHandshake(capabilities.toString());
        transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication), 2);
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            ReSyncFlowClientTestHarness.drain(client);
            if (client.connectionState() == ReSyncFlowClient.ConnectionState.CONNECTED
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION
                && client.typedInteractionProjection().isPresent()) {
                return new Harness(client, transport, new CustomContentAuthoringPeer(SERVER, transport));
            }
            LockSupport.parkNanos(1_000_000L);
        }
        throw new AssertionError("Typed custom-content test connection did not settle: "
            + client.connectionState() + ", catalog=" + client.catalogAuthority()
            + ", epoch=" + client.resourceRevisionReconciler().authorityEpoch(SERVER_ID));
    }

    private static CatalogCachePublication publication() {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
        List<CatalogAuthoringPublication.SectionProjection> sections = new ArrayList<>();
        for (CatalogAuthoringPublication.Section section : CatalogAuthoringPublication.Section.values()) {
            sections.add(new CatalogAuthoringPublication.SectionProjection(section, true, true,
                CatalogCacheState.ACTIVE, List.of()));
        }
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING,
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), sections, Set.of());
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, BINDING, 1L, List.of(),
            authoring, Map.of());
    }

    private static JsonObject protocolCapabilities(CatalogCachePublication publication) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", SERVER_ID);
        root.addProperty("authorityEpoch", 1L);
        root.addProperty("catalogPublicationKey", publication.key().canonicalText());
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject authority = new JsonObject();
        authority.addProperty("supported", true);
        authority.addProperty("durable", true);
        protocol.add("mutationAuthority", authority);
        JsonObject operations = new JsonObject();
        JsonArray read = new JsonArray();
        read.add("load");
        read.add("list");
        read.add("query");
        operations.add("read", read);
        JsonArray mutate = new JsonArray();
        mutate.add("create");
        mutate.add("save");
        mutate.add("activate");
        operations.add("mutate", mutate);
        protocol.add("resourceOperations", operations);
        JsonObject contract = new JsonObject();
        JsonObject version = new JsonObject();
        version.addProperty("generation", 1);
        version.addProperty("minor", 2);
        contract.add("version", version);
        JsonArray capabilities = new JsonArray();
        capabilities.add("restudio.resync/resource_activation");
        capabilities.add("restudio.resync/resource_create_presentation");
        contract.add("capabilities", capabilities);
        protocol.add("genericResourceContract", contract);
        root.add("protocolEnvelope", protocol);
        return root;
    }

    private static void awaitSave(Harness harness, CustomContentAuthoringPeer peer, AtomicReference<Boolean> saved) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline && saved.get() == null) {
            ReSyncFlowClientTestHarness.drain(harness.client);
            peer.pump();
            LockSupport.parkNanos(1_000_000L);
        }
        ReSyncFlowClientTestHarness.drain(harness.client);
        assertEquals(1, peer.ackCount());
    }

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;
        private final Path stateRoot;

        private Probe(Path stateRoot) {
            super(RemotelyComposition.browser(new TestHost()).build());
            this.stateRoot = stateRoot;
            manager = new FlowManager(this, null, null, redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(stateRoot));
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
            TestHost host = (TestHost) getHost();
            ApplicationHostRegistry.install(host.previousHost);
            RemotelyClient.INSTANCE = host.previousClient;
        }
    }

    private static final class TestHost implements ApplicationHost {
        private final ApplicationHost previousHost = ApplicationHostRegistry.current();
        private final RemotelyClient previousClient = RemotelyClient.INSTANCE;

        @Override public void setScreen(Screen screen) { }
        @Override public Screen getCurrentScreen() { return null; }
        @Override public void ensureTextRenderer() { }
        @Override public MinecraftGameAssets getGameAssets() { return null; }
        @Override public Object getFontIdentifier(String namespace, String path) { return null; }
        @Override public void openParentScreen(Screen currentScreen, Object parent) { }
        @Override public void setClipboard(String text) { }
        @Override public boolean shouldCloseRootScreen() { return false; }
        @Override public String getGameVersion() { return ""; }
        @Override public String getGameUserName() { return ""; }
        @Override public String getGameUUID() { return ""; }
    }

    private record Harness(ReSyncFlowClient client, ScriptedReSyncTransport transport,
                           CustomContentAuthoringPeer peer) implements AutoCloseable {
        @Override
        public void close() {
            ReSyncFlowClientTestHarness.closeClient(client);
        }
    }

    private static final class CustomContentAuthoringPeer {
        private static final ContractRef<CapabilityId> RESOURCES = ContractRef.of(OWNER, CapabilityId.of("resources"));
        private static final Set<ContractRef<CapabilityId>> CAPABILITIES = Set.of(
            RESOURCES, ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY);
        private static final ProtocolEnvelopeCodec<Map<String, Object>> ENVELOPES =
            new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());

        private final ServerId server;
        private final ScriptedReSyncTransport transport;
        private final Set<UUID> handled = new LinkedHashSet<>();
        private ServerResourceLocator resource;
        private ResourceDocument<Map<String, Object>> current;
        private ProtocolEnvelope<Map<String, Object>> lastSaveRequest;
        private final List<ProtocolEnvelope<Map<String, Object>>> saveRequests = new ArrayList<>();
        private boolean delayAckOnce;
        private int sequence = 20;
        private int acknowledgements;

        private CustomContentAuthoringPeer(ServerId server, ScriptedReSyncTransport transport) {
            this.server = server;
            this.transport = transport;
        }

        private void seed(CanonicalPayload<Map<String, Object>> payload, long revision, UUID mutation) {
            resource = new ServerResourceLocator(server, ContractRef.of(OWNER,
                ResourceTypeId.of(ReSyncResourceType.CUSTOM_CONTENT.typeId())), "custom-item");
            current = ResourceDocument.live(resource, revision, mutation, payload,
                ResourceActivationState.ACTIVE, "peer");
        }

        private int pump() {
            int handledNow = 0;
            for (ReSyncDecodedFrame frame : transport.sentFrames()) {
                if (frame.messageType() != ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                    continue;
                }
                ProtocolEnvelope<Map<String, Object>> request = ENVELOPES.decodeBytes(frame.payload());
                if (!(request.body() instanceof ProtocolBody.ResourceRequest body)
                    || !handled.add(request.messageId())) {
                    continue;
                }
                if (!(body.operation() instanceof ResourceSaveRequest<?> untyped)
                    || resource == null || !resource.equals(untyped.resource())) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                ResourceSaveRequest<Map<String, Object>> save = (ResourceSaveRequest<Map<String, Object>>) untyped;
                lastSaveRequest = request;
                saveRequests.add(request);
                if (save.expectedRevision() != current.revision()
                    && !save.mutationId().equals(current.mutationId())) {
                    continue;
                }
                if (!save.mutationId().equals(current.mutationId())) {
                    current = ResourceDocument.live(resource, current.revision() + 1L, save.mutationId(),
                        save.canonicalPayload(), ResourceActivationState.ACTIVE, "peer");
                }
                if (delayAckOnce) {
                    delayAckOnce = false;
                    transport.receiveEnvelope(pending(request), sequence++);
                    handledNow++;
                    continue;
                }
                transport.receiveEnvelope(response(request, current), sequence++);
                acknowledgements++;
                handledNow++;
            }
            return handledNow;
        }

        private ProtocolEnvelope<Map<String, Object>> lastSaveRequest() {
            return lastSaveRequest;
        }

        private int ackCount() {
            return acknowledgements;
        }

        private long currentRevision() {
            return current != null ? current.revision() : -1L;
        }

        private ProtocolEnvelope<Map<String, Object>> response(
            ProtocolEnvelope<Map<String, Object>> request, ResourceDocument<Map<String, Object>> document) {
            return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.ACK,
                request.contractVersion(), UUID.randomUUID(), request.requestId(),
                request.correlationId(), request.traceId(), server, document.resource(), document.revision(), 1L,
                document.mutationId(), ContractRef.of(OWNER, OperationId.of("resource.save")), CAPABILITIES,
                ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(), false,
                null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
                new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.SAVE, document));
        }

        private ProtocolEnvelope<Map<String, Object>> pending(ProtocolEnvelope<Map<String, Object>> request) {
            Map<String, Object> values = Map.of("rejectionCode", ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.wireValue(),
                "rejectionMessage", "Resource mutation is awaiting durable recovery");
            return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, request.contractVersion(), UUID.randomUUID(),
                request.requestId(), request.correlationId(), request.traceId(), server, request.resource(), 0L, 1L,
                request.mutationId(), request.operation(), request.capabilities(), request.payloadType(), null,
                request.payloadHash(), false, null, null, null, null, null, 0L, ProtocolEnvelope.Status.REJECTED,
                List.of(), values, new ProtocolBody.ControlResponse("resource.rejection", values));
        }
    }
}
