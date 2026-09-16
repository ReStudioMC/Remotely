package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
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
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.protocol.ResourcePage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphResourceProjectionTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final UUID MUTATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MESSAGE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID TRACE = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Test
    void decodesFlowCommandAndFunctionWithSharedCoreCodecs() {
        GraphDocument flow = graph("flow", "flow", 4, OpaqueData.of(Map.of("futureFlow", Map.of("keep", true))), Set.of());
        GraphDocument command = graph("command", "command", 5, OpaqueData.of(Map.of("futureCommand", List.of(1, true))), Set.of());
        FunctionSourceDocument function = function("function", 6);

        CoreGraphResourceProjection.Projection flowProjection = project(flow, CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND,
            ResourceActivationState.ACTIVE, 4, MUTATION);
        CoreGraphResourceProjection.Projection commandProjection = project(command,
            CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND, ResourceActivationState.ACTIVE, 5, MUTATION);
        CoreGraphResourceProjection.Projection functionProjection = project(function,
            CoreGraphResourceProjection.FUNCTION_SOURCE_KIND, ResourceActivationState.ACTIVE, 6, MUTATION);

        assertTrue(flowProjection.live());
        assertEquals(flow.checksum(), flowProjection.graphDocument().checksum());
        assertEquals(flow.unknown(), flowProjection.opaqueData());
        assertTrue(commandProjection.live());
        assertEquals(command.checksum(), commandProjection.graphDocument().checksum());
        assertEquals(function.checksum(), functionProjection.functionSourceDocument().checksum());
        assertEquals(function.unknown(), functionProjection.opaqueData());
        assertEquals("function-source", functionProjection.corePayloadKind());
    }

    @Test
    void preservesInactiveActivationAndRejectsMetadataMismatch() {
        GraphDocument graph = graph("flow", "inactive", 8, OpaqueData.empty(), Set.of());
        CoreGraphResourceProjection.Projection inactive = project(graph,
            CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND, ResourceActivationState.INACTIVE, 8, MUTATION);

        assertTrue(inactive.live());
        assertEquals(ResourceActivationState.INACTIVE, inactive.assetActivationState());
        assertEquals(ResourceActivationState.INACTIVE, inactive.resourceDocument().activationState());

        CoreGraphResourceProjection.Projection mismatched = project(graph,
            CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND, ResourceActivationState.ACTIVE, 9, MUTATION);
        assertTrue(mismatched.rejected());
        assertTrue(mismatched.rejectionReason().contains("revision"));
    }

    @Test
    void rejectsTamperedAssetHashAndDecodesUnsupportedGraphCapabilityReadOnly() {
        GraphDocument graph = graph("flow", "tampered", 3, OpaqueData.empty(), Set.of());
        ProtocolEnvelope<Map<String, Object>> tampered = envelope(graph.resource(), document(graph.resource(),
            tamperedPayload(graph, CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND, ResourceActivationState.ACTIVE,
                3, MUTATION, "0".repeat(64)), 3, MUTATION, ResourceActivationState.ACTIVE), ResourceOperationKind.LOAD);

        CoreGraphResourceProjection.Projection hashProjection = CoreGraphResourceProjection.project(tampered, Set.of("resources"));
        assertTrue(hashProjection.rejected());
        assertTrue(hashProjection.rejectionReason().contains("integrity hash"));

        ContractRef<CapabilityId> future = ContractRef.of(new OwnerId("future.extension"), CapabilityId.of("graph-edit"));
        GraphDocument requiring = graph("flow", "capability", 3, OpaqueData.empty(), Set.of(future));
        CoreGraphResourceProjection.Projection capabilityProjection = project(requiring,
            CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND, ResourceActivationState.ACTIVE, 3, MUTATION);
        assertTrue(capabilityProjection.readOnly());
        assertTrue(capabilityProjection.hasGraphDocument());
        assertEquals(requiring.checksum(), capabilityProjection.graphDocument().checksum());
        assertEquals(requiring.unknown(), capabilityProjection.opaqueData());
        assertEquals(Set.of(future), capabilityProjection.unsupportedCapabilities());
        assertTrue(capabilityProjection.hasUnsupportedCapabilities());
    }

    @Test
    void rejectsLegacyV3LiveAssetsUntilServerMigrationEmitsV4() {
        GraphDocument graph = graph("flow", "legacy", 13, OpaqueData.empty(), Set.of());
        Map<String, Object> payload = assetPayload(GraphDocumentCodec.INSTANCE.encode(graph),
            graph.resource().resourceType().value(), CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND,
            ResourceActivationState.ACTIVE, 13, MUTATION, CoreGraphResourceProjection.LEGACY_ASSET_FORMAT_VERSION);
        ResourceDocument<Map<String, Object>> document = document(graph.resource(), payload, 13, MUTATION,
            ResourceActivationState.ACTIVE);

        CoreGraphResourceProjection.Projection projection = CoreGraphResourceProjection.project(
            envelope(graph.resource(), document, ResourceOperationKind.LOAD), Set.of("resources"));

        assertTrue(projection.rejected());
        assertTrue(projection.rejectionReason().contains("version 4"));
    }

    @Test
    void projectsEveryResourcePageDocumentAndRetainsReadOnlyItems() {
        GraphDocument supported = graph("flow", "page-supported", 14, OpaqueData.empty(), Set.of());
        ContractRef<CapabilityId> future = ContractRef.of(new OwnerId("future.extension"), CapabilityId.of("page-edit"));
        GraphDocument unsupported = graph("flow", "page-unsupported", 15,
            OpaqueData.of(Map.of("futurePage", List.of(true, "retain"))), Set.of(future));
        ResourceDocument<Map<String, Object>> supportedDocument = document(supported.resource(),
            assetPayload(GraphDocumentCodec.INSTANCE.encode(supported), "flow", CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND,
                ResourceActivationState.ACTIVE, 14, MUTATION), 14, MUTATION, ResourceActivationState.ACTIVE);
        ResourceDocument<Map<String, Object>> unsupportedDocument = document(unsupported.resource(),
            assetPayload(GraphDocumentCodec.INSTANCE.encode(unsupported), "flow", CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND,
                ResourceActivationState.ACTIVE, 15, MUTATION), 15, MUTATION, ResourceActivationState.ACTIVE);
        ResourceDocument<Map<String, Object>> tombstone = ResourceDocument.tombstone(resource("flow", "page-removed"), 16,
            MUTATION, new ContentHash("d".repeat(64)), "server");
        ProtocolEnvelope<Map<String, Object>> envelope = pageEnvelope(new ResourcePage<>(
            List.of(supportedDocument, unsupportedDocument, tombstone), "next-page", false));

        CoreGraphResourceProjection.PageProjection projection = CoreGraphResourceProjection.projectPage(envelope,
            Set.of("resources"));

        assertTrue(projection.accepted());
        assertEquals("next-page", projection.nextCursor());
        assertFalse(projection.complete());
        assertEquals(3, projection.items().size());
        assertTrue(projection.items().get(0).live());
        assertTrue(projection.items().get(1).readOnly());
        assertTrue(projection.items().get(1).hasGraphDocument());
        assertEquals(unsupported.unknown(), projection.items().get(1).opaqueData());
        assertTrue(projection.items().get(2).tombstoned());
    }

    @Test
    void acceptsTypedTombstonesWithoutInventingPayload() {
        ServerResourceLocator resource = resource("command", "deleted");
        ResourceDocument<Map<String, Object>> tombstone = ResourceDocument.tombstone(resource, 12, MUTATION,
            new ContentHash("c".repeat(64)), "server");
        ProtocolEnvelope<Map<String, Object>> envelope = envelope(resource, tombstone, ResourceOperationKind.DELETE);

        CoreGraphResourceProjection.Projection projection = CoreGraphResourceProjection.project(envelope, Set.of("resources"));

        assertTrue(projection.tombstoned());
        assertNull(projection.graphDocument());
        assertNull(projection.functionSourceDocument());
        assertEquals(tombstone.payloadHash(), projection.payloadHash());
        assertEquals(tombstone.mutationId(), projection.mutationId());
    }

    @Test
    void leavesNonGraphJsonResourceSerializationUntouched() {
        String json = "{\"id\":\"chat\",\"future\":{\"enabled\":true}}";
        Object decoded = ReSyncResourceType.CHAT.deserialize(json);

        assertEquals(json, ReSyncResourceType.CHAT.serialize(decoded));
        assertFalse(ReSyncResourceType.CHAT.isGraph());
        assertTrue(ReSyncResourceType.FLOW.acceptsCorePayloadKind(CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND));
        assertTrue(ReSyncResourceType.COMMAND.acceptsCorePayloadKind(CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND));
        assertTrue(ReSyncResourceType.FUNCTION.acceptsCorePayloadKind(CoreGraphResourceProjection.FUNCTION_SOURCE_KIND));
        assertFalse(ReSyncResourceType.FUNCTION.acceptsCorePayloadKind(CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND));
    }

    private static CoreGraphResourceProjection.Projection project(GraphDocument graph, String kind,
                                                                  ResourceActivationState activationState,
                                                                  long assetRevision, UUID mutationId) {
        Map<String, Object> payload = assetPayload(GraphDocumentCodec.INSTANCE.encode(graph), graph.resource().resourceType().value(),
            kind, activationState, assetRevision, mutationId);
        ResourceDocument<Map<String, Object>> document = document(graph.resource(), payload, assetRevision, mutationId,
            activationState);
        return CoreGraphResourceProjection.project(envelope(graph.resource(), document, kind.equals("function-source")
            ? ResourceOperationKind.LOAD : ResourceOperationKind.LOAD), supported(activationState));
    }

    private static CoreGraphResourceProjection.Projection project(FunctionSourceDocument source, String kind,
                                                                  ResourceActivationState activationState,
                                                                  long assetRevision, UUID mutationId) {
        Map<String, Object> payload = assetPayload(FunctionSourceDocumentCodec.INSTANCE.encode(source),
            source.graph().resource().resourceType().value(), kind, activationState, assetRevision, mutationId);
        ResourceDocument<Map<String, Object>> document = document(source.graph().resource(), payload, assetRevision,
            mutationId, activationState);
        return CoreGraphResourceProjection.project(envelope(source.graph().resource(), document, ResourceOperationKind.LOAD),
            supported(activationState));
    }

    private static Map<String, Object> tamperedPayload(GraphDocument graph, String kind,
                                                       ResourceActivationState activationState,
                                                       long assetRevision, UUID mutationId, String hash) {
        Map<String, Object> payload = assetPayload(GraphDocumentCodec.INSTANCE.encode(graph),
            graph.resource().resourceType().value(), kind, activationState, assetRevision, mutationId);
        payload.put(CoreGraphResourceProjection.ASSET_HASH, hash);
        return payload;
    }

    private static Map<String, Object> assetPayload(JsonValue.JsonObject core, String resourceType, String kind,
                                                    ResourceActivationState activationState, long assetRevision,
                                                    UUID mutationId) {
        return assetPayload(core, resourceType, kind, activationState, assetRevision, mutationId,
            CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
    }

    private static Map<String, Object> assetPayload(JsonValue.JsonObject core, String resourceType, String kind,
                                                    ResourceActivationState activationState, long assetRevision,
                                                    UUID mutationId, int assetFormatVersion) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, resourceType);
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION, assetFormatVersion);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, assetRevision);
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, mutationId.toString());
        if (assetFormatVersion >= CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION) {
            fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, activationState.wireName());
        }
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, kind);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION,
            CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        @SuppressWarnings("unchecked")
        Map<String, Object> coreFields = (Map<String, Object>) core.toJava();
        fields.putAll(coreFields);
        ContentHash hash = new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields));
        fields.put(CoreGraphResourceProjection.ASSET_HASH, hash.canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return new LinkedHashMap<>(result);
    }

    private static ResourceDocument<Map<String, Object>> document(ServerResourceLocator resource,
                                                                  Map<String, Object> payload, long revision,
                                                                  UUID mutationId, ResourceActivationState activationState) {
        return ResourceDocument.live(resource, revision, mutationId, ResourcePayloadCodecs.json().canonicalize(payload),
            activationState, "server");
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource,
                                                                  ResourceDocument<Map<String, Object>> document,
                                                                  ResourceOperationKind operation) {
        boolean activationSupported = !document.deleted() && document.activationState() == ResourceActivationState.INACTIVE;
        Set<ContractRef<CapabilityId>> capabilities = activationSupported || operation == ResourceOperationKind.ACTIVATE
            ? Set.of(ContractRef.of(OWNER, CapabilityId.of("resources")),
                restudio.resync.protocol.ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY)
            : Set.of(ContractRef.of(OWNER, CapabilityId.of("resources")));
        CatalogVersion version = activationSupported || operation == ResourceOperationKind.ACTIVATE
            ? new CatalogVersion(1, 1) : new CatalogVersion(1, 0);
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.EVENT, version, MESSAGE, REQUEST, CORRELATION, TRACE, SERVER,
            resource, document.revision(), document.mutationId(), ContractRef.of(OWNER,
            OperationId.of("resource." + operation.name().toLowerCase())), capabilities,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(),
            document.deleted(), null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourceDocumentResponse(operation, document));
    }

    private static ProtocolEnvelope<Map<String, Object>> pageEnvelope(ResourcePage<Map<String, Object>> page) {
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, new CatalogVersion(1, 0), MESSAGE, REQUEST,
            CORRELATION, TRACE, SERVER, null, 0L, null, ContractRef.of(OWNER, OperationId.of("resource.list")),
            Set.of(ContractRef.of(OWNER, CapabilityId.of("resources"))),
            ContractRef.of(OWNER, ResourceTypeId.of("resource.page")), null, null, false, null, null, null, null, null,
            1L, ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourcePageResponse(ResourceOperationKind.LIST, page, Map.of("futurePageBody", true)));
    }

    private static Set<String> supported(ResourceActivationState activationState) {
        return activationState == ResourceActivationState.INACTIVE
            ? ReSyncProtocolEnvelopeProjection.genericResourceCapabilities(Set.of()) : Set.of("resources");
    }

    private static FunctionSourceDocument function(String id, long revision) {
        ServerResourceLocator resource = resource("function", id);
        GraphDocument graph = graph("function", id, revision, OpaqueData.of(Map.of("graphFuture", true)), Set.of());
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(revision),
            List.of(), List.of(), Map.of("signatureFuture", Map.of("keep", true)));
        return new FunctionSourceDocument(signature, graph, OpaqueData.of(Map.of("sourceFuture", List.of(true, "keep"))));
    }

    private static GraphDocument graph(String type, String id, long revision, OpaqueData unknown,
                                       Set<ContractRef<CapabilityId>> requiredCapabilities) {
        return new GraphDocument(new CatalogVersion(1, 0), resource(type, id), revision, BINDING,
            requiredCapabilities, List.of(), List.of(), List.of(), List.of(), unknown);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }
}
