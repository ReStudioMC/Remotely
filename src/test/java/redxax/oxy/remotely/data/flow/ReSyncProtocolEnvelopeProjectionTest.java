package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
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
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncProtocolEnvelopeProjectionTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-5111-8111-111111111111");
    private static final UUID MESSAGE_UUID = UUID.fromString("22222222-2222-5222-8222-222222222222");
    private static final UUID REQUEST_UUID = UUID.fromString("33333333-3333-5333-8333-333333333333");
    private static final UUID CORRELATION_UUID = UUID.fromString("44444444-4444-5444-8444-444444444444");
    private static final UUID TRACE_UUID = UUID.fromString("55555555-5555-5555-8555-555555555555");

    @Test
    void retainsUnknownEnvelopeBodyAndUnsupportedCapabilities() {
        ContractRef<CapabilityId> known = ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("known"));
        ContractRef<CapabilityId> future = ContractRef.of(new OwnerId("future.extension"), CapabilityId.of("known"),
            Map.of("futureReference", Map.of("version", 9)));
        ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            MESSAGE_UUID,
            REQUEST_UUID,
            CORRELATION_UUID,
            TRACE_UUID,
            new ServerId(SERVER_UUID),
            null,
            0,
            null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("control.test")),
            Set.of(known, future),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("control.request")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of("futureEnvelope", Map.of("enabled", true)),
            new ProtocolBody.ControlRequest("test", Map.of("value", true), Map.of("futureBody", List.of(1, 2)))
        );

        ReSyncProtocolEnvelopeProjection.Projection projection = ReSyncProtocolEnvelopeProjection.project(envelope, Set.of("known"));

        assertSame(envelope, projection.envelope());
        assertEquals(Set.of(known, future), projection.capabilities());
        assertEquals(Set.of(future), projection.unsupportedCapabilities());
        assertEquals(Map.of("futureEnvelope", Map.of("enabled", true)), projection.unknownEnvelopeFields());
        assertEquals(Map.of("futureBody", List.of(1, 2)), projection.unknownBodyFields());
        assertEquals(Map.of("version", 9), future.unknown().get("futureReference"));
        assertTrue(projection.hasUnsupportedCapabilities());
        assertFalse(projection.hasResourceDocument());
        assertFalse(projection.canApplyTypedResource());
    }

    @Test
    void unsupportedCapabilityCannotActivateAResourceDocument() {
        ServerId server = new ServerId(SERVER_UUID);
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")), "flow-a");
        UUID mutationId = UUID.fromString("66666666-6666-5666-8666-666666666666");
        var payload = ResourcePayloadCodecs.json().canonicalize(Map.of("id", "flow-a"));
        ResourceDocument<Map<String, Object>> document = ResourceDocument.live(resource, 1L, mutationId, payload, "server");
        ContractRef<CapabilityId> known = ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("known"));
        ContractRef<CapabilityId> future = ContractRef.of(new OwnerId("future.extension"), CapabilityId.of("future"));
        ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.EVENT,
            new CatalogVersion(1, 0),
            MESSAGE_UUID,
            null,
            CORRELATION_UUID,
            TRACE_UUID,
            server,
            resource,
            1L,
            mutationId,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.changed")),
            Set.of(known, future),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")),
            null,
            payload.checksum(),
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
            new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.LOAD, document, Map.of("futureBody", true))
        );

        ReSyncProtocolEnvelopeProjection.Projection projection = ReSyncProtocolEnvelopeProjection.project(envelope, Set.of("known"));

        assertTrue(projection.hasResourceDocument());
        assertTrue(projection.hasUnsupportedCapabilities());
        assertFalse(projection.canApplyTypedResource());
    }

    @Test
    void genericActivationCapabilityPreservesAuthoritativeInactiveState() {
        ServerId server = new ServerId(SERVER_UUID);
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")), "flow-a");
        UUID mutationId = UUID.fromString("77777777-7777-5777-8777-777777777777");
        var payload = ResourcePayloadCodecs.json().canonicalize(Map.of("id", "flow-a", "enabled", false));
        ResourceDocument<Map<String, Object>> document = ResourceDocument.live(resource, 2L, mutationId, payload,
            ResourceActivationState.INACTIVE, "server");
        Set<ContractRef<CapabilityId>> capabilities = Set.of(
            ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("resources")),
            restudio.resync.protocol.ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY);
        ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.ACK,
            restudio.resync.protocol.ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            MESSAGE_UUID,
            REQUEST_UUID,
            CORRELATION_UUID,
            TRACE_UUID,
            server,
            resource,
            document.revision(),
            mutationId,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.activate")),
            capabilities,
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")),
            null,
            document.payloadHash(),
            false,
            null,
            null,
            null,
            null,
            null,
            0L,
            ProtocolEnvelope.Status.OK,
            List.of(),
            Map.of(),
            new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.ACTIVATE, document)
        );

        ReSyncProtocolEnvelopeProjection.Projection projection = ReSyncProtocolEnvelopeProjection.project(envelope,
            ReSyncProtocolEnvelopeProjection.genericResourceCapabilities(List.of()));

        assertFalse(projection.hasUnsupportedCapabilities());
        assertTrue(projection.canApplyTypedResource());
        assertEquals(ResourceActivationState.INACTIVE, projection.resourceDocument().activationState());
    }

    @Test
    void roundTripsAuthorityEpochWithoutChangingResourceProjection() {
        ServerId server = new ServerId(SERVER_UUID);
        ServerResourceLocator resource = new ServerResourceLocator(server,
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")), "flow-a");
        UUID mutationId = UUID.fromString("88888888-8888-5888-8888-888888888888");
        var payload = ResourcePayloadCodecs.json().canonicalize(Map.of("id", "flow-a"));
        ResourceDocument<Map<String, Object>> document = ResourceDocument.live(resource, 1L, mutationId, payload, "server");
        Set<ContractRef<CapabilityId>> capabilities = Set.of(
            ContractRef.of(new OwnerId("restudio.resync"), CapabilityId.of("resources")));
        ProtocolEnvelope<Map<String, Object>> envelope = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.EVENT,
            restudio.resync.protocol.ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            MESSAGE_UUID,
            null,
            CORRELATION_UUID,
            TRACE_UUID,
            server,
            resource,
            1L,
            7L,
            mutationId,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.load")),
            capabilities,
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")),
            null,
            document.payloadHash(),
            false,
            null,
            null,
            null,
            null,
            null,
            0L,
            ProtocolEnvelope.Status.OK,
            List.of(),
            Map.of(),
            new ProtocolBody.ResourceDocumentResponse(ResourceOperationKind.LOAD, document)
        );

        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
        ProtocolEnvelope<Map<String, Object>> decoded = codec.decodeText(codec.encodeText(envelope));

        assertEquals(7L, decoded.authorityEpoch());
        assertEquals(document.resource(), decoded.body() instanceof ProtocolBody.ResourceDocumentResponse response
            ? response.document().resource() : null);
    }
}
