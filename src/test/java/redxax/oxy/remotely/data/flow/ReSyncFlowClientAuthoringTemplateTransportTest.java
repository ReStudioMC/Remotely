package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateCodec;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientAuthoringTemplateTransportTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    private static final CatalogProjectionVersion VERSION = CatalogProjectionVersion.current();
    private static final CatalogBinding BINDING = new CatalogBinding(4,
        new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");

    @Test
    void cacheHydrationLeavesLegacySessionAuthoringProjectionEmpty(@TempDir Path tempDirectory) throws Exception {
        Path cachePath = tempDirectory.resolve("catalog-publication-cache.json");
        CatalogCachePublication publication = publication(true);
        CatalogCachePublicationCodec codec = new CatalogCachePublicationCodec();
        CatalogAuthoringPublication authoring = publication.authoringPublication();
        Map<String, Object> legacySnapshot = Map.of(
            "publication", codec.encode(publication),
            "projection", "publication",
            "authoring", new CatalogAuthoringPublicationCodec().encode(authoring)
        );
        Map<String, Object> legacyCache = Map.of(
            "schemaVersion", ReSyncCatalogPublicationCache.SCHEMA_VERSION,
            "servers", Map.of(SERVER.canonicalText(), Map.of(publication.key().canonicalText(), legacySnapshot))
        );
        Files.write(cachePath, JsonValue.fromJava(legacyCache).canonicalBytes(CanonicalLimits.catalog()));

        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), new NoopTransport(), null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(cachePath)));
        try {
            assertEquals(publication.withAuthoringPublication(null), awaitCatalogPublication(client));
            assertTrue(client.activeCatalogAuthoringPublication().isEmpty());
            assertTrue(client.activeCatalogAuthoringChecksum().isEmpty());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void templateRequestUsesExactEnvelopeAndSettlesOnceOnDisconnect(@TempDir Path tempDirectory) throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("catalog-publication-cache.json"))));
        CatalogCachePublication publication = publication(true);
        CatalogCachePublicationCodec publicationCodec = new CatalogCachePublicationCodec();
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.receiveCatalogPublication(publicationCodec.encodeBytes(publication));

            ServerResourceLocator resource = new ServerResourceLocator(SERVER,
                ContractRef.of(OWNER, ResourceTypeId.of("flow")), "template");
            AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, publication.key(),
                awaitAuthoringChecksum(client));
            UUID requestId = UUID.randomUUID();
            AtomicInteger callbackCount = new AtomicInteger();
            AtomicReference<Object> callbackValue = new AtomicReference<>();
            CountDownLatch callbackLatch = new CountDownLatch(1);

            assertTrue(client.requestAuthoringTemplate(requestId, request, response -> {
                callbackCount.incrementAndGet();
                callbackValue.set(response);
                callbackLatch.countDown();
            }));

            ReSyncDecodedFrame frame = transport.sentFrames.stream()
                .filter(value -> value.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE)
                .reduce((first, second) -> second).orElseThrow();
            ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelopeCodec<Map<String, Object>>(
                ResourcePayloadCodecs.json()).decodeBytes(frame.payload());
            assertEquals(ProtocolEnvelope.Kind.REQUEST, envelope.kind());
            assertEquals(requestId, envelope.requestId());
            assertEquals(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, frame.messageType());
            assertEquals(ReSyncProtocolContract.CHANNEL_CONTROL_ID, frame.channel());
            assertEquals(new CatalogVersion(1, 2), envelope.contractVersion());
            assertEquals(ContractRef.of(OWNER, OperationId.of("resource.authoring-template")).canonicalText(),
                envelope.operation().canonicalText());
            assertEquals("authoring-template.request", envelope.payloadType().id().value());
            assertEquals(Set.of("resources", "catalog_authoring"), envelope.capabilities().stream()
                .map(value -> value.id().value()).collect(Collectors.toSet()));
            assertTrue(envelope.body() instanceof ProtocolBody.ControlRequest);
            ProtocolBody.ControlRequest body = (ProtocolBody.ControlRequest) envelope.body();
            assertEquals(request, AuthoringTemplateCodec.INSTANCE.decodeRequest(
                JsonValue.fromJava(body.values().get("request"))));

            transport.close();
            assertTrue(callbackLatch.await(2, TimeUnit.SECONDS));
            assertEquals(1, callbackCount.get());
            assertNull(callbackValue.get());
            assertFalse(client.requestAuthoringTemplate(requestId, request, ignored -> {}));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void templateResponseMustEchoTheCapturedNegotiatedContractVersion(@TempDir Path tempDirectory) throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("template-response-cache.json"))));
        CatalogCachePublication publication = publication(true);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication));
            ContentHash authoringChecksum = awaitAuthoringChecksum(client);
            ServerResourceLocator resource = new ServerResourceLocator(SERVER,
                ContractRef.of(OWNER, ResourceTypeId.of("flow")), "template-response");
            AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, publication.key(), authoringChecksum);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<AuthoringTemplateResponse> accepted = new AtomicReference<>();
            CountDownLatch completion = new CountDownLatch(1);
            UUID requestId = UUID.randomUUID();

            assertTrue(client.requestAuthoringTemplate(requestId, request, response -> {
                callbacks.incrementAndGet();
                accepted.set(response);
                completion.countDown();
            }));
            ProtocolEnvelope<Map<String, Object>> outbound = lastEnvelope(transport);
            assertEquals(new CatalogVersion(1, 2), outbound.contractVersion());
            GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource, 0L, BINDING, Set.of(),
                List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
            AuthoringTemplateResponse response = new AuthoringTemplateResponse(resource, publication.key(), BINDING,
                AuthoringTemplatePayload.flow(document), document.checksum(), authoringChecksum, Set.of(), Set.of());

            transport.receiveEnvelope(templateResponse(outbound, response, new CatalogVersion(1, 3)));
            ReSyncFlowClientTestHarness.drain(client);
            assertEquals(0, callbacks.get());

            transport.receiveEnvelope(templateResponse(outbound, response, outbound.contractVersion()));
            assertTrue(completion.await(2L, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertEquals(AuthoringTemplateCodec.INSTANCE.encodeResponse(response),
                AuthoringTemplateCodec.INSTANCE.encodeResponse(accepted.get()));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void authoringTemplateRevisionConflictRefreshesCatalogAndSettlesPendingRequest(@TempDir Path tempDirectory)
        throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), transport, null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(tempDirectory.resolve("template-conflict-cache.json"))));
        CatalogCachePublication publication = publication(true);
        try {
            client.connect().join();
            transport.receive(handshakeResponse(capabilities(publication.key())));
            transport.receiveCatalogPublication(new CatalogCachePublicationCodec().encodeBytes(publication));
            ServerResourceLocator resource = new ServerResourceLocator(SERVER,
                ContractRef.of(OWNER, ResourceTypeId.of("function")), "catalog-rollover");
            AuthoringTemplateRequest request = new AuthoringTemplateRequest(resource, publication.key(),
                awaitAuthoringChecksum(client));
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<AuthoringTemplateResponse> result = new AtomicReference<>();
            CountDownLatch completion = new CountDownLatch(1);

            assertTrue(client.requestAuthoringTemplate(UUID.randomUUID(), request, response -> {
                callbacks.incrementAndGet();
                result.set(response);
                completion.countDown();
            }));
            ProtocolEnvelope<Map<String, Object>> outbound = lastEnvelope(transport);
            int refreshesBefore = transport.fullCatalogRequestCount();

            ProtocolEnvelope<Map<String, Object>> rejection = templateRejection(outbound,
                ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT, "The acknowledged catalog publication is stale");
            assertTrue(ReSyncFlowClient.authoringTemplateRefreshRequired(rejection));
            transport.receiveEnvelope(rejection);

            assertTrue(completion.await(2L, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertNull(result.get());
            assertTrue(awaitFullCatalogRequestCount(transport, refreshesBefore + 1));
            assertFalse(ReSyncFlowClient.authoringTemplateRefreshRequired(templateRejection(outbound,
                ProtocolRejectionCode.RESOURCE_NOT_FOUND, "The requested resource does not exist")));
        } finally {
            client.shutdown();
        }
    }

    private static CatalogCachePublication publication(boolean withAuthoring) {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, VERSION);
        ContractRef<NodeId> node = ContractRef.of(new OwnerId("restudio.test"), new NodeId("node"));
        CatalogCachePublication.Entry entry = CatalogCachePublication.Entry.present(node, 3,
            CatalogCacheState.UNAVAILABLE, Set.of(), true,
            CatalogCacheOpaque.of("{\"id\":\"node\"}".getBytes(StandardCharsets.UTF_8)));
        return new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, BINDING, 3,
            List.of(entry), withAuthoring ? authoring() : null, Map.of());
    }

    private static CatalogAuthoringPublication authoring() {
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                true, true, CatalogCacheState.ACTIVE, List.of()));
        return new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0), VERSION, sections, Set.of());
    }

    private static byte[] handshakeResponse(String capabilities) {
        byte[] capabilityBytes = capabilities.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 7 + capabilityBytes.length);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(capabilityBytes.length);
        payload.put(capabilityBytes);
        return payload.array();
    }

    private static String capabilities(CatalogCacheKey key) {
        JsonObject root = new JsonObject();
        root.addProperty("serverId", key.serverId().canonicalText());
        root.addProperty("catalogPublicationKey", key.canonicalText());
        root.addProperty("authorityEpoch", 1L);
        JsonObject authoring = new JsonObject();
        authoring.addProperty("capability", "restudio.resync/catalog_authoring");
        authoring.addProperty("available", true);
        authoring.addProperty("projectionVersion", VERSION.canonicalText());
        root.add("catalogAuthoring", authoring);
        JsonObject protocol = new JsonObject();
        protocol.addProperty("supported", true);
        JsonObject resourceContract = new JsonObject();
        resourceContract.addProperty("version", "1.2");
        protocol.add("genericResourceContract", resourceContract);
        root.add("protocolEnvelope", protocol);
        addContract(root);
        return root.toString();
    }

    private static ProtocolEnvelope<Map<String, Object>> lastEnvelope(CapturingTransport transport) {
        ReSyncDecodedFrame frame = transport.sentFrames.stream()
            .filter(value -> value.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE)
            .reduce((first, second) -> second).orElseThrow();
        return new ProtocolEnvelopeCodec<Map<String, Object>>(ResourcePayloadCodecs.json()).decodeBytes(frame.payload());
    }

    private static ProtocolEnvelope<Map<String, Object>> templateResponse(
        ProtocolEnvelope<Map<String, Object>> request, AuthoringTemplateResponse response, CatalogVersion version) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, version, UUID.randomUUID(), request.requestId(),
            request.correlationId(), request.traceId(), request.serverId(), null, 0L, request.authorityEpoch(), null,
            request.operation(), request.capabilities(), ContractRef.of(OWNER,
            ResourceTypeId.of("authoring-template.response")), null, null, false, null, null, null, null, null, 0L,
            ProtocolEnvelope.Status.OK, List.of(), request.unknown(), new ProtocolBody.ControlResponse(
            "resource.authoring-template", Map.of("response", AuthoringTemplateCodec.INSTANCE.encodeResponse(response).toJava())));
    }

    private static ProtocolEnvelope<Map<String, Object>> templateRejection(
        ProtocolEnvelope<Map<String, Object>> request, ProtocolRejectionCode code, String message) {
        Map<String, Object> values = Map.of(
            "rejectionCode", code.wireValue(),
            "rejectionMessage", message,
            "requestId", request.requestId().toString(),
            "correlationId", request.correlationId().toString(),
            "traceId", request.traceId().toString(),
            "operation", request.operation().canonicalText());
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, request.contractVersion(), UUID.randomUUID(),
            request.requestId(), request.correlationId(), request.traceId(), request.serverId(), null, 0L,
            request.authorityEpoch(), null, request.operation(), request.capabilities(), request.payloadType(), null,
            null, false, null, null, null, null, null, 0L, ProtocolEnvelope.Status.REJECTED, List.of(),
            Map.of("rejectionCode", code.wireValue(), "rejectionMessage", message),
            new ProtocolBody.ControlResponse("resource.rejection", values));
    }

    private static boolean awaitFullCatalogRequestCount(CapturingTransport transport, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (transport.fullCatalogRequestCount() >= expected) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return transport.fullCatalogRequestCount() >= expected;
    }

    private static ContentHash awaitAuthoringChecksum(ReSyncFlowClient client) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Optional<ContentHash> checksum = client.activeCatalogAuthoringChecksum();
            if (checksum.isPresent()
                && client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION) {
                return checksum.orElseThrow();
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        ContentHash checksum = client.activeCatalogAuthoringChecksum().orElseThrow();
        assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION, client.catalogAuthority());
        return checksum;
    }

    private static CatalogCachePublication awaitCatalogPublication(ReSyncFlowClient client) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Optional<ReSyncCatalogPublicationProjection.Snapshot> active = client.catalogPublicationProjection().active();
            if (active.isPresent()) {
                return active.orElseThrow().publication();
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Catalog publication cache was not hydrated");
    }

    private static void addContract(JsonObject root) {
        JsonObject contract = new JsonObject();
        contract.addProperty("version", ReSyncProtocolContract.FLOW_CONTRACT.version());
        contract.addProperty("minimumClientVersion", 0);
        JsonArray supported = new JsonArray();
        ReSyncProtocolContract.FLOW_CONTRACT.serverCapabilities().forEach(supported::add);
        contract.add("supported", supported);
        JsonArray negotiated = new JsonArray();
        for (String capability : ReSyncProtocolContract.FLOW_CONTRACT.negotiate(
            ReSyncProtocolContract.FLOW_CONTRACT.clientCapabilities())) {
            negotiated.add(capability);
        }
        contract.add("negotiated", negotiated);
        root.add("flowContract", contract);
    }

    private static final class NoopTransport implements ReSyncFrameTransport {
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
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class CapturingTransport implements ReSyncFrameTransport {
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
            sentFrames.add(new ReSyncFrameCodec().decode(frame, null));
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

        private void receive(byte[] payload) {
            frameHandler.accept(new ReSyncFrameCodec().encode(
                ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE, payload, (short) 0, 1));
        }

        private void receiveCatalogPublication(byte[] publication) {
            ByteBuffer payload = ByteBuffer.allocate(1 + publication.length);
            payload.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
            payload.put(publication);
            frameHandler.accept(new ReSyncFrameCodec().encode(
                ReSyncProtocolContract.MESSAGE_DATA, payload.array(), ReSyncProtocolContract.CHANNEL_FLOW_ID, 2));
        }

        private void receiveEnvelope(ProtocolEnvelope<Map<String, Object>> envelope) {
            byte[] payload = new ProtocolEnvelopeCodec<Map<String, Object>>(ResourcePayloadCodecs.json()).encodeBytes(envelope);
            frameHandler.accept(new ReSyncFrameCodec().encode(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE,
                payload, ReSyncProtocolContract.CHANNEL_CONTROL_ID, 3));
        }

        private int fullCatalogRequestCount() {
            return (int) sentFrames.stream()
                .filter(frame -> frame.messageType() == ReSyncProtocolContract.MESSAGE_DATA)
                .filter(frame -> frame.channel() == ReSyncProtocolContract.CHANNEL_FLOW_ID)
                .map(ReSyncDecodedFrame::payload)
                .filter(payload -> payload.length == 1
                    && payload[0] == ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION_REQUEST)
                .count();
        }
    }
}
