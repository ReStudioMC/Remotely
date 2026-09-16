package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncFlowClientResourceActivationTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("98989898-9898-4989-8989-989898989898"));
    private static final String SERVER_ID = SERVER.canonicalText();
    private static final ContentHash CATALOG_HASH = new ContentHash("a".repeat(64));
    private static final ContentHash MANIFEST_HASH = new ContentHash("b".repeat(64));
    private static final CatalogBinding BINDING = new CatalogBinding(1L, CATALOG_HASH, MANIFEST_HASH);
    private static final ProtocolEnvelopeCodec<Map<String, Object>> CODEC =
        new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
    @TempDir
    Path temporaryDirectory;

    @Test
    void sendsGenericActivationWithRevisionAndUuidMutation() throws Exception {
        Probe probe = new Probe();
        try (ReSyncFlowClientTestHarness fixture = fixture(probe)) {
            ReSyncFlowClient client = fixture.client();
            ScriptedReSyncTransport transport = fixture.transport();
            seedGraph(fixture, ReSyncResourceType.FLOW, "flow-a", true, 7L);
            probe.manager.cacheServerCapabilities(SERVER_ID, activationCapabilitySnapshot());

            client.sendResourceActivation(ReSyncResourceType.FLOW, "flow-a", false, "activation:legacy");

            ReSyncDecodedFrame frame = transport.lastProtocolEnvelope();
            ProtocolEnvelope<Map<String, Object>> envelope = CODEC.decodeBytes(frame.payload());
            ProtocolBody.ResourceRequest body = (ProtocolBody.ResourceRequest) envelope.body();
            restudio.resync.flow.protocol.ResourceActivateRequest request =
                (restudio.resync.flow.protocol.ResourceActivateRequest) body.operation();

            assertEquals(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, frame.messageType());
            assertEquals(ReSyncProtocolContract.CHANNEL_CONTROL_ID, frame.channel());
            assertEquals(SERVER, request.resource().serverId());
            assertEquals(7L, request.expectedRevision());
            assertEquals(ResourceActivationState.INACTIVE, request.targetState());
            assertNotEquals(envelope.requestId(), request.mutationId());
            assertEquals(restudio.resync.protocol.ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
                envelope.contractVersion());
        } finally {
            probe.close();
        }
    }

    @Test
    void advertisesActivationCapabilityForGenericDocumentAndPageRequests() throws Exception {
        Probe probe = new Probe();
        try (ReSyncFlowClientTestHarness fixture = fixture(probe)) {
            ReSyncFlowClient client = fixture.client();
            ScriptedReSyncTransport transport = fixture.transport();
            probe.manager.cacheServerCapabilities(SERVER_ID, activationCapabilitySnapshot());

            client.requestResource(ReSyncResourceType.GUI, "gui-a", false);
            assertEquals(activationCapabilities(), CODEC.decodeBytes(transport.lastProtocolEnvelope().payload()).capabilities());

            client.requestResourceList(ReSyncResourceType.GUI);
            assertEquals(activationCapabilities(), CODEC.decodeBytes(transport.lastProtocolEnvelope().payload()).capabilities());

            client.requestResource(ReSyncResourceType.FLOW, "flow-a", false);
            assertEquals(activationCapabilities(), CODEC.decodeBytes(transport.lastProtocolEnvelope().payload()).capabilities());

            client.requestResourceList(ReSyncResourceType.FLOW);
            assertEquals(activationCapabilities(), CODEC.decodeBytes(transport.lastProtocolEnvelope().payload()).capabilities());

            JsonObject versionedLegacy = activationCapabilitySnapshot();
            versionedLegacy.getAsJsonObject("protocolEnvelope").getAsJsonObject("genericResourceContract")
                .getAsJsonObject("version").addProperty("minor", 0);
            probe.manager.cacheServerCapabilities(SERVER_ID, versionedLegacy);
            client.requestResource(ReSyncResourceType.GUI, "gui-legacy", false);
            assertFalse(CODEC.decodeBytes(transport.lastProtocolEnvelope().payload()).capabilities()
                .contains(restudio.resync.protocol.ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY));
        } finally {
            probe.close();
        }
    }

    @Test
    void appliesActivationAckDocumentStateAndRevision() throws Exception {
        Probe probe = new Probe();
        try (ReSyncFlowClientTestHarness fixture = fixture(probe)) {
            ReSyncFlowClient client = fixture.client();
            ScriptedReSyncTransport transport = fixture.transport();
            seedGraph(fixture, ReSyncResourceType.FLOW, "flow-a", true, 7L);
            probe.manager.cacheServerCapabilities(SERVER_ID, activationCapabilitySnapshot());
            client.sendResourceActivation(ReSyncResourceType.FLOW, "flow-a", false, "activation:legacy");
            ProtocolEnvelope<Map<String, Object>> request = CODEC.decodeBytes(transport.lastProtocolEnvelope().payload());
            var resource = request.resource();
            UUID mutationId = request.mutationId();
            ResourceDocument<Map<String, Object>> document = activationDocument(resource, 8L, false, mutationId);
            ProtocolEnvelope<Map<String, Object>> ack = new ProtocolEnvelope<>(
                ProtocolEnvelope.Kind.ACK,
                request.contractVersion(),
                UUID.randomUUID(),
                request.requestId(),
                request.correlationId(),
                request.traceId(),
                SERVER,
                resource,
                document.revision(),
                1L,
                mutationId,
                ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.activate")),
                activationCapabilities(),
                ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")),
                null,
                document.payloadHash(),
                false,
                null,
                null,
                null,
                null,
                null,
                1L,
                ProtocolEnvelope.Status.OK,
                List.of(),
                Map.of(),
                new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.ACTIVATE, document)
            );

            transport.receiveEnvelope(CODEC.encodeBytes(ack), 3);
            fixture.drain();

            assertTrue(!probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(8L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
        } finally {
            probe.close();
        }
    }

    @Test
    void acceptsFlatResourceActivationCapabilityAdvertisement() throws Exception {
        Probe probe = new Probe();
        try (ReSyncFlowClientTestHarness fixture = fixture(probe)) {
            ReSyncFlowClient client = fixture.client();
            JsonObject snapshot = activationCapabilitySnapshot();
            JsonObject protocol = snapshot.getAsJsonObject("protocolEnvelope");
            JsonObject contract = protocol.getAsJsonObject("genericResourceContract");
            protocol.add("genericResourceContractVersion", contract.get("version").deepCopy());
            protocol.add("resourceCapabilities", contract.get("capabilities").deepCopy());
            protocol.remove("genericResourceContract");
            probe.manager.cacheServerCapabilities(SERVER_ID, snapshot);

            assertTrue(client.supportsGenericResourceActivation(ReSyncResourceType.FLOW));
        } finally {
            probe.close();
        }
    }

    @Test
    void conflictAndEventDocumentsReplaceOptimisticActivationState() throws Exception {
        Probe probe = new Probe();
        try (ReSyncFlowClientTestHarness fixture = fixture(probe)) {
            ReSyncFlowClient client = fixture.client();
            ScriptedReSyncTransport transport = fixture.transport();
            seedGraph(fixture, ReSyncResourceType.FLOW, "flow-a", true, 7L);
            probe.manager.cacheServerCapabilities(SERVER_ID, activationCapabilitySnapshot());
            client.sendResourceActivation(ReSyncResourceType.FLOW, "flow-a", false, "activation:legacy");
            ProtocolEnvelope<Map<String, Object>> request = CODEC.decodeBytes(transport.lastProtocolEnvelope().payload());
            var resource = new restudio.resync.flow.identity.ServerResourceLocator(SERVER,
                ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")), "flow-a");
            ResourceDocument<Map<String, Object>> inactive = activationDocument(resource, 8L, false, UUID.randomUUID());
            assertNotEquals(request.mutationId(), inactive.mutationId());
            ProtocolEnvelope<Map<String, Object>> conflict = activationResponse(ProtocolEnvelope.Kind.CONFLICT,
                ProtocolEnvelope.Status.CONFLICT, request.requestId(), request.correlationId(), request.traceId(), resource,
                inactive, true);
            transport.receiveEnvelope(CODEC.encodeBytes(conflict), 3);
            fixture.drain();

            assertTrue(!probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(8L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
            assertEquals(0, pendingActivations(client));

            ResourceDocument<Map<String, Object>> active = activationDocument(resource, 9L, true, UUID.randomUUID());
            ProtocolEnvelope<Map<String, Object>> event = activationResponse(ProtocolEnvelope.Kind.EVENT,
                ProtocolEnvelope.Status.OK, null, null, null, resource, active, false);
            transport.receiveEnvelope(CODEC.encodeBytes(event), 4);
            fixture.drain();

            assertTrue(probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(9L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
        } finally {
            probe.close();
        }
    }

    @Test
    void newerEventSupersedesOnlyMatchingPendingActivationBeforeDisconnect() throws Exception {
        Probe probe = new Probe();
        try (ReSyncFlowClientTestHarness fixture = fixture(probe)) {
            ReSyncFlowClient client = fixture.client();
            ScriptedReSyncTransport transport = fixture.transport();
            seedGraph(fixture, ReSyncResourceType.FLOW, "flow-a", false, 7L);
            seedGraph(fixture, ReSyncResourceType.COMMAND, "command-a", false, 7L);
            probe.manager.cacheServerCapabilities(SERVER_ID, activationCapabilitySnapshot());
            client.sendResourceActivation(ReSyncResourceType.FLOW, "flow-a", true, "activation:flow");
            client.sendResourceActivation(ReSyncResourceType.COMMAND, "command-a", true, "activation:command");

            var resource = resource(ReSyncResourceType.FLOW, "flow-a");
            ProtocolEnvelope<Map<String, Object>> flowRequest = transport.requireRequest(ResourceOperationKind.ACTIVATE,
                "flow-a");
            ResourceDocument<Map<String, Object>> active = activationDocument(resource, 8L, false, UUID.randomUUID());
            ProtocolEnvelope<Map<String, Object>> event = activationResponse(ProtocolEnvelope.Kind.EVENT,
                ProtocolEnvelope.Status.OK, null, flowRequest.correlationId(), flowRequest.traceId(), resource, active,
                false);
            transport.receiveEnvelope(CODEC.encodeBytes(event), 3);
            fixture.drain();

            assertEquals(1, pendingActivations(client));
            assertTrue(!probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(8L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());

            transport.close();
            fixture.drain();

            assertEquals(1, pendingActivations(client));
            assertEquals(1, retainedCoreTransportMutations(client));
            assertTrue(client.retainsResourceActivation(ReSyncResourceType.COMMAND, "command-a", "activation:command"));
            assertTrue(!probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(8L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
        } finally {
            probe.close();
        }
    }

    @Test
    void ignoresActivationResponsesWithMismatchedCorrelationOrMutation() throws Exception {
        Probe probe = new Probe();
        try (ReSyncFlowClientTestHarness fixture = fixture(probe)) {
            ReSyncFlowClient client = fixture.client();
            ScriptedReSyncTransport transport = fixture.transport();
            seedGraph(fixture, ReSyncResourceType.FLOW, "flow-a", true, 7L);
            probe.manager.cacheServerCapabilities(SERVER_ID, activationCapabilitySnapshot());
            var resource = resource(ReSyncResourceType.FLOW, "flow-a");

            client.sendResourceActivation(ReSyncResourceType.FLOW, "flow-a", false, "activation:ack-correlation");
            ProtocolEnvelope<Map<String, Object>> ackRequest = CODEC.decodeBytes(transport.lastProtocolEnvelope().payload());
            ResourceDocument<Map<String, Object>> ackDocument = activationDocument(resource, 8L, false, ackRequest.mutationId());
            ProtocolEnvelope<Map<String, Object>> mismatchedAck = activationResponse(ProtocolEnvelope.Kind.ACK,
                ProtocolEnvelope.Status.OK, ackRequest.requestId(), UUID.randomUUID(), ackRequest.traceId(), resource,
                ackDocument, false);
            transport.receiveEnvelope(CODEC.encodeBytes(mismatchedAck), 3);
            fixture.drain();

            assertTrue(probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(7L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
            assertEquals(1, pendingActivations(client));

            ResourceDocument<Map<String, Object>> wrongMutationDocument = activationDocument(resource, 8L, false, UUID.randomUUID());
            ProtocolEnvelope<Map<String, Object>> mismatchedMutationAck = activationResponse(ProtocolEnvelope.Kind.ACK,
                ProtocolEnvelope.Status.OK, ackRequest.requestId(), ackRequest.correlationId(), ackRequest.traceId(),
                resource, wrongMutationDocument, false);
            transport.receiveEnvelope(CODEC.encodeBytes(mismatchedMutationAck), 4);
            fixture.drain();

            assertTrue(probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(7L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
            assertEquals(1, pendingActivations(client));

            client.sendResourceActivation(ReSyncResourceType.FLOW, "flow-a", false, "activation:conflict-correlation");
            ProtocolEnvelope<Map<String, Object>> conflictRequest = CODEC.decodeBytes(transport.lastProtocolEnvelope().payload());
            ResourceDocument<Map<String, Object>> conflictDocument = activationDocument(resource, 8L, false, UUID.randomUUID());
            ProtocolEnvelope<Map<String, Object>> mismatchedConflict = activationResponse(ProtocolEnvelope.Kind.CONFLICT,
                ProtocolEnvelope.Status.CONFLICT, conflictRequest.requestId(), UUID.randomUUID(), conflictRequest.traceId(),
                resource, conflictDocument, true);
            transport.receiveEnvelope(CODEC.encodeBytes(mismatchedConflict), 5);
            fixture.drain();

            assertTrue(probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(7L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
            assertEquals(2, pendingActivations(client));

            ResourceDocument<Map<String, Object>> conflictCurrentDocument = activationDocument(resource, 8L, false, UUID.randomUUID());
            ProtocolEnvelope<Map<String, Object>> validConflict = activationResponse(ProtocolEnvelope.Kind.CONFLICT,
                ProtocolEnvelope.Status.CONFLICT, conflictRequest.requestId(), conflictRequest.correlationId(),
                conflictRequest.traceId(), resource, conflictCurrentDocument, true);
            transport.receiveEnvelope(mismatchedEnvelopeMutation(validConflict, UUID.randomUUID()), 6);
            fixture.drain();

            assertTrue(probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").isEnabled());
            assertEquals(7L, probe.manager.getGraph(SERVER_ID, ReSyncResourceType.FLOW, "flow-a").getResourceRevision());
            assertEquals(2, pendingActivations(client));
        } finally {
            probe.close();
        }
    }

    @Test
    void ignoresGenericEnvelopeBeforeAuthentication() throws Exception {
        Probe probe = new Probe();
        CapturingTransport transport = new CapturingTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, transport, probe, new ReSyncCatalogPublicationCache());
        try {
            client.connect().join();
            var resource = resource(ReSyncResourceType.FLOW, "flow-a");
            ResourceDocument<Map<String, Object>> active = activationDocument(resource, 8L, false, UUID.randomUUID());
            ProtocolEnvelope<Map<String, Object>> event = activationResponse(ProtocolEnvelope.Kind.EVENT,
                ProtocolEnvelope.Status.OK, null, null, null, resource, active, false);
            transport.receiveEnvelope(CODEC.encodeBytes(event));

            assertTrue(client.coreGraphResourceCache().state(resource).isEmpty());
        } finally {
            client.shutdown();
            probe.close();
        }
    }

    @Test
    void genericNonGraphConflictAndEventRequireExactRevisionOrSupersession() throws Exception {
        ServerResourceLocator resource = resource(ReSyncResourceType.GUI, "gui-a");
        UUID requestId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID mutationId = UUID.randomUUID();
        Object pending = pendingTypedActivation(requestId, correlationId, traceId, mutationId, resource, 7L);

        ResourceDocument<Map<String, Object>> conflictDocument = activationDocument(resource, 8L, false, UUID.randomUUID());
        ProtocolEnvelope<Map<String, Object>> mismatchedConflict = genericActivationResponse(ProtocolEnvelope.Kind.CONFLICT,
            ProtocolEnvelope.Status.CONFLICT, requestId, correlationId, traceId, resource, conflictDocument, 9L, true);
        assertFalse(pendingTypedMatches(pending, mismatchedConflict, conflictDocument, resource, ReSyncResourceType.GUI));

        ProtocolEnvelope<Map<String, Object>> matchingConflict = genericActivationResponse(ProtocolEnvelope.Kind.CONFLICT,
            ProtocolEnvelope.Status.CONFLICT, requestId, correlationId, traceId, resource, conflictDocument, 8L, true);
        assertTrue(pendingTypedMatches(pending, matchingConflict, conflictDocument, resource, ReSyncResourceType.GUI));

        ResourceDocument<Map<String, Object>> unrelatedSameRevision = activationDocument(resource, 7L, false, UUID.randomUUID());
        ProtocolEnvelope<Map<String, Object>> unrelatedEvent = genericActivationResponse(ProtocolEnvelope.Kind.EVENT,
            ProtocolEnvelope.Status.OK, null, correlationId, traceId, resource, unrelatedSameRevision, 7L, false);
        assertFalse(pendingTypedMatchesEvent(pending, unrelatedEvent, unrelatedSameRevision, resource, ReSyncResourceType.GUI));

        ResourceDocument<Map<String, Object>> matchingSameRevision = activationDocument(resource, 7L, false, mutationId);
        ProtocolEnvelope<Map<String, Object>> mismatchedRevisionEvent = genericActivationResponse(ProtocolEnvelope.Kind.EVENT,
            ProtocolEnvelope.Status.OK, null, correlationId, traceId, resource, matchingSameRevision, 8L, false);
        assertFalse(pendingTypedMatchesEvent(pending, mismatchedRevisionEvent, matchingSameRevision, resource,
            ReSyncResourceType.GUI));

        ProtocolEnvelope<Map<String, Object>> matchingEvent = genericActivationResponse(ProtocolEnvelope.Kind.EVENT,
            ProtocolEnvelope.Status.OK, null, correlationId, traceId, resource, matchingSameRevision, 7L, false);
        assertTrue(pendingTypedMatchesEvent(pending, matchingEvent, matchingSameRevision, resource, ReSyncResourceType.GUI));

        ResourceDocument<Map<String, Object>> newerDocument = activationDocument(resource, 8L, false, UUID.randomUUID());
        ProtocolEnvelope<Map<String, Object>> newerEvent = genericActivationResponse(ProtocolEnvelope.Kind.EVENT,
            ProtocolEnvelope.Status.OK, null, correlationId, traceId, resource, newerDocument, 8L, false);
        assertTrue(pendingTypedMatchesEvent(pending, newerEvent, newerDocument, resource, ReSyncResourceType.GUI));
    }

    @Test
    void nonGraphActivationRetainsExactTransportIdentity() throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER_ID, null, null, null, null);
        try {
            UUID requestId = UUID.randomUUID();
            Object pending = pendingTypedActivation(requestId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                resource(ReSyncResourceType.GUI, "gui-a"), 7L);
            pendingTypedResourceRequests(client).put(requestId, pending);

            assertTrue(retainTypedTransportMutation(client, pending));
            assertFalse(pendingTypedResourceRequests(client).containsKey(requestId));
            assertEquals(1, retainedTypedTransportMutations(client));
            assertTrue(client.retainsResourceActivation(ReSyncResourceType.GUI, "gui-a", "activation:gui"));
        } finally {
            client.shutdown();
        }
    }

    private static ResourceDocument<Map<String, Object>> activationDocument(
        restudio.resync.flow.identity.ServerResourceLocator resource, long revision, boolean enabled, UUID mutationId) {
        ReSyncResourceType type = ReSyncResourceType.byTypeId(resource.resourceType().value());
        GraphDocument graph = graph(type, resource.id(), revision);
        Map<String, Object> payload = assetPayload(GraphDocumentCodec.INSTANCE.encode(graph), type.typeId(),
            enabled ? ResourceActivationState.ACTIVE : ResourceActivationState.INACTIVE, revision, mutationId);
        var canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return ResourceDocument.live(resource, revision, mutationId, canonical,
            enabled ? ResourceActivationState.ACTIVE : ResourceActivationState.INACTIVE, "server");
    }

    private static ProtocolEnvelope<Map<String, Object>> activationResponse(ProtocolEnvelope.Kind kind,
                                                                            ProtocolEnvelope.Status status,
                                                                             UUID requestId,
                                                                             UUID correlationId,
                                                                             UUID traceId,
                                                                             restudio.resync.flow.identity.ServerResourceLocator resource,
                                                                            ResourceDocument<Map<String, Object>> document,
                                                                            boolean conflict) {
        ProtocolBody body = conflict
            ? new ProtocolBody.ConflictResponse(resource, document)
            : new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.ACTIVATE, document);
        return new ProtocolEnvelope<>(kind, restudio.resync.protocol.ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            UUID.randomUUID(), requestId, correlationId != null ? correlationId : UUID.randomUUID(),
            traceId != null ? traceId : UUID.randomUUID(), SERVER, resource, document.revision(),
            1L, document.mutationId(), ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.activate")),
            activationCapabilities(), ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")), null,
            document.payloadHash(), false, null, null, null, null, null, 1L, status, List.of(), Map.of(), body);
    }

    private static ProtocolEnvelope<Map<String, Object>> genericActivationResponse(ProtocolEnvelope.Kind kind,
                                                                                    ProtocolEnvelope.Status status,
                                                                                    UUID requestId,
                                                                                    UUID correlationId,
                                                                                    UUID traceId,
                                                                                    ServerResourceLocator resource,
                                                                                    ResourceDocument<Map<String, Object>> document,
                                                                                    long revision,
                                                                                    boolean conflict) {
        ProtocolBody body = conflict
            ? new ProtocolBody.ConflictResponse(resource, document)
            : new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.ACTIVATE, document);
        return new ProtocolEnvelope<>(kind, restudio.resync.protocol.ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            UUID.randomUUID(), requestId, correlationId, traceId, SERVER, resource, revision, 1L, document.mutationId(),
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.activate")), activationCapabilities(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")), null,
            document.payloadHash(), false, null, null, null, null, null, 1L, status, List.of(), Map.of(), body);
    }

    private static Object pendingTypedActivation(UUID requestId, UUID correlationId, UUID traceId, UUID mutationId,
                                                 ServerResourceLocator resource, long expectedRevision) throws Exception {
        Class<?> pendingType = Class.forName(ReSyncFlowClient.class.getName() + "$PendingTypedResourceRequest");
        var constructor = pendingType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(requestId, correlationId, traceId, mutationId, ResourceOperationKind.ACTIVATE,
            resource, expectedRevision, ReSyncResourceType.GUI, null,
            new ResourceActivateRequest(resource, expectedRevision, ResourceActivationState.INACTIVE, mutationId),
            true, false, null, null, "activation:gui", false, 1);
    }

    private static boolean pendingTypedMatches(Object pending, ProtocolEnvelope<Map<String, Object>> envelope,
                                               ResourceDocument<Map<String, Object>> document,
                                               ServerResourceLocator resource, ReSyncResourceType type) throws Exception {
        Method method = pending.getClass().getDeclaredMethod("matches", ProtocolEnvelope.class, ResourceDocument.class,
            ServerResourceLocator.class, ReSyncResourceType.class);
        method.setAccessible(true);
        return (boolean) method.invoke(pending, envelope, document, resource, type);
    }

    private static boolean pendingTypedMatchesEvent(Object pending, ProtocolEnvelope<Map<String, Object>> envelope,
                                                    ResourceDocument<Map<String, Object>> document,
                                                    ServerResourceLocator resource, ReSyncResourceType type) throws Exception {
        Method method = pending.getClass().getDeclaredMethod("matchesEvent", ProtocolEnvelope.class, ResourceDocument.class,
            ServerResourceLocator.class, ReSyncResourceType.class);
        method.setAccessible(true);
        return (boolean) method.invoke(pending, envelope, document, resource, type);
    }

    private static boolean retainTypedTransportMutation(ReSyncFlowClient client, Object pending) throws Exception {
        Method method = ReSyncFlowClient.class.getDeclaredMethod("retainTypedTransportMutation", pending.getClass());
        method.setAccessible(true);
        return (boolean) method.invoke(client, pending);
    }

    private ReSyncFlowClientTestHarness fixture(Probe probe) throws Exception {
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.TYPES,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.EDITORS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.PREVIEWS,
                true, true, CatalogCacheState.ACTIVE, List.of()),
            new CatalogAuthoringPublication.SectionProjection(CatalogAuthoringPublication.Section.CAPABILITIES,
                true, true, CatalogCacheState.ACTIVE, List.of()));
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING,
            new CatalogVersion(1, 0), CatalogProjectionVersion.current(), sections, Set.of());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL,
            key, BINDING, 1L, List.of(), authoring, Map.of());
        return ReSyncFlowClientTestHarness.connect(SERVER, probe, probe.manager,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve(UUID.randomUUID()) + ".json")), publication);
    }

    private static byte[] mismatchedEnvelopeMutation(ProtocolEnvelope<Map<String, Object>> envelope, UUID mutationId) {
        JsonObject encoded = JsonParser.parseString(new String(CODEC.encodeBytes(envelope), StandardCharsets.UTF_8)).getAsJsonObject();
        encoded.addProperty("mutationId", mutationId.toString());
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static restudio.resync.flow.identity.ServerResourceLocator resource(ReSyncResourceType type, String id) {
        return new restudio.resync.flow.identity.ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of(type.typeId())), id);
    }

    private static int pendingActivations(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingResourceActivations");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(client)).size();
    }

    private static int retainedCoreTransportMutations(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("retainedCoreTransportMutations");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(client)).size();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> pendingTypedResourceRequests(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("pendingTypedResourceRequests");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(client);
    }

    private static int retainedTypedTransportMutations(ReSyncFlowClient client) throws Exception {
        Field field = ReSyncFlowClient.class.getDeclaredField("retainedTypedTransportMutations");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(client)).size();
    }

    private static GraphDocument graph(ReSyncResourceType type, String id, long revision) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING, Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static Map<String, Object> assetPayload(JsonValue.JsonObject core, String type,
                                                     ResourceActivationState activationState, long revision,
                                                     UUID mutationId) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, type);
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION,
            CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, revision);
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, mutationId.toString());
        fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, activationState.wireName());
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION,
            CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        core.fields().forEach((key, value) -> fields.put(key, value.toJava()));
        fields.put(CoreGraphResourceProjection.ASSET_HASH,
            new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields)).canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return new LinkedHashMap<>(result);
    }

    private static void seedGraph(ReSyncFlowClientTestHarness fixture, ReSyncResourceType type, String id,
                                  boolean enabled, long revision) throws Exception {
        ServerResourceLocator resource = resource(type, id);
        ResourceDocument<Map<String, Object>> document = activationDocument(resource, revision, enabled,
            UUID.randomUUID());
        ProtocolEnvelope<Map<String, Object>> event = new ProtocolEnvelope<>(ProtocolEnvelope.Kind.EVENT,
            restudio.resync.protocol.ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION, UUID.randomUUID(), null,
            UUID.randomUUID(), UUID.randomUUID(), SERVER, resource, revision, 1L, document.mutationId(),
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.load")), activationCapabilities(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")), null,
            document.payloadHash(), false, null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(),
            Map.of(), new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.LOAD, document));
        Method receive = ReSyncFlowClient.class.getDeclaredMethod("handleProtocolEnvelope", byte[].class);
        receive.setAccessible(true);
        receive.invoke(fixture.client(), (Object) CODEC.encodeBytes(event));
        fixture.drain();
        assertTrue(fixture.client().coreGraphResourceCache().state(resource).isPresent());
    }

    private static JsonObject activationCapabilitySnapshot() {
        JsonObject root = new JsonObject();
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
        mutate.add("activate");
        operations.add("mutate", mutate);
        protocol.add("resourceOperations", operations);
        JsonObject contract = new JsonObject();
        JsonObject version = new JsonObject();
        version.addProperty("generation", 1);
        version.addProperty("minor", 1);
        contract.add("version", version);
        JsonArray capabilities = new JsonArray();
        capabilities.add("restudio.resync/resource_activation");
        contract.add("capabilities", capabilities);
        protocol.add("genericResourceContract", contract);
        root.add("protocolEnvelope", protocol);
        return root;
    }

    private static Set<ContractRef<CapabilityId>> activationCapabilities() {
        return Set.of(
            ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("resources")),
            restudio.resync.protocol.ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY);
    }

    private static final class Probe extends RemotelyClient {
        private final FlowManager manager;

        private Probe() {
            super(null);
            manager = new FlowManager(this, null);
        }

        @Override
        public FlowManager getFlowManager() {
            return manager;
        }

        private void close() {
            manager.shutdown();
        }
    }

    private static final class CapturingTransport implements ReSyncFrameTransport {
        private final ReSyncFrameCodec codec = new ReSyncFrameCodec();
        private final List<ReSyncDecodedFrame> sentFrames = new ArrayList<>();
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

        private void receiveEnvelope(byte[] payload) {
            frameHandler.accept(codec.encode(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, payload,
                ReSyncProtocolContract.CHANNEL_CONTROL_ID, 2));
        }

        private ReSyncDecodedFrame lastProtocolEnvelope() {
            for (int index = sentFrames.size() - 1; index >= 0; index--) {
                ReSyncDecodedFrame frame = sentFrames.get(index);
                if (frame.messageType() == ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                    return frame;
                }
            }
            throw new IllegalStateException("Generic activation envelope was not sent");
        }
    }
}
