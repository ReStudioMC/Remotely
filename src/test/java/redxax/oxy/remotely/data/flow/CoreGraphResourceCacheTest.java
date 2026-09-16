package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.cache.GraphResourceCache;
import restudio.resync.flow.cache.GraphResourceState;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
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
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphResourceCacheTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(7, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final ContractRef<CapabilityId> UNSUPPORTED = ContractRef.of(
        new OwnerId("extension.example"), CapabilityId.of("opaque-editor"));
    private static final UUID MESSAGE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REQUEST = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CORRELATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TRACE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID FIRST_MUTATION = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID SECOND_MUTATION = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Test
    void adaptsCompleteProjectionWithoutLosingCoreStateOrUnknownData() {
        ServerResourceLocator resource = resource("flow", "cache");
        GraphDocument graph = graph(resource, 1, Set.of(), OpaqueData.of(Map.of("futurePayload", Map.of("keep", true))));
        CoreGraphResourceProjection.Projection projection = project(graph, 1, FIRST_MUTATION,
            ResourceActivationState.INACTIVE, Map.of("futureEnvelope", true), Map.of("futureBody", List.of(1, 2)),
            Set.of("resources", "resource_activation"));
        CoreGraphResourceCache cache = new CoreGraphResourceCache();

        GraphResourceCache.Reconciliation reconciliation = cache.reconcile(projection).orElseThrow();

        assertEquals(GraphResourceCache.Status.NEW, reconciliation.status());
        GraphResourceState state = cache.state(resource).orElseThrow();
        assertEquals(projection.payloadHash(), state.protocolHash());
        assertEquals(projection.assetHash(), state.assetHash());
        assertEquals(graph.checksum(), state.innerChecksum());
        assertEquals(ResourceActivationState.INACTIVE, state.activationState());
        assertEquals(BINDING, state.catalogBinding());
        assertEquals(Map.of(
            "envelope", Map.of("futureEnvelope", true),
            "body", Map.of("futureBody", List.of(1, 2))), state.unknown());
    }

    @Test
    void retainsReadOnlyProjectionOutsideAuthoritativeLiveCache() {
        ServerResourceLocator resource = resource("flow", "read-only");
        GraphDocument graph = graph(resource, 2, Set.of(UNSUPPORTED), OpaqueData.empty());
        CoreGraphResourceProjection.Projection readOnly = project(graph, 2, FIRST_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"));
        CoreGraphResourceCache cache = new CoreGraphResourceCache();

        assertTrue(readOnly.readOnly());
        assertTrue(cache.reconcile(readOnly).isEmpty());
        assertTrue(cache.state(resource).isEmpty());
        assertEquals(readOnly, cache.readOnly(resource).orElseThrow());
        assertEquals(Set.of(UNSUPPORTED), readOnly.unsupportedCapabilities());
        CoreGraphResourceCache.ProjectionKey key = CoreGraphResourceCache.ProjectionKey.from(readOnly);
        assertEquals(readOnly, cache.projection(key).orElseThrow());
        assertTrue(cache.projection(resource, key.revision(), key.mutationId(), new ContentHash("f".repeat(64))).isEmpty());

        CoreGraphResourceProjection.Projection supported = project(graph, 2, FIRST_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources", UNSUPPORTED.canonicalText()));
        assertEquals(GraphResourceCache.Status.NEW, cache.reconcile(supported).orElseThrow().status());
        assertTrue(cache.state(resource).isPresent());
        assertTrue(cache.readOnly(resource).isEmpty());
    }

    @Test
    void staleLiveProjectionCannotDisplaceNewerReadOnlyProjection() {
        ServerResourceLocator resource = resource("flow", "read-only-order");
        GraphDocument newerGraph = graph(resource, 2, Set.of(UNSUPPORTED), OpaqueData.empty());
        CoreGraphResourceProjection.Projection newer = project(newerGraph, 2, FIRST_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"));
        CoreGraphResourceProjection.Projection stale = project(
            graph(resource, 1, Set.of(), OpaqueData.empty()), 1, SECOND_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"));
        CoreGraphResourceCache cache = new CoreGraphResourceCache();

        cache.reconcile(newer);
        assertTrue(cache.reconcile(stale).isEmpty());
        assertTrue(cache.state(resource).isEmpty());
        assertEquals(2, cache.readOnly(resource).orElseThrow().revision());
    }

    @Test
    void acknowledgesOnlyTheMatchingCoreDraft() {
        ServerResourceLocator resource = resource("command", "drafts");
        GraphDocument draftGraph = graph(resource, 1, Set.of(), OpaqueData.of(Map.of("draft", "first")));
        GraphResourceState draftState = GraphResourceState.live(resource, 1, SECOND_MUTATION,
            new ContentHash("1".repeat(64)), new ContentHash("2".repeat(64)), draftGraph,
            ResourceActivationState.ACTIVE);
        CoreGraphResourceCache cache = new CoreGraphResourceCache();
        cache.putDraft(GraphDraft.from(draftState).withMutationId(FIRST_MUTATION));

        GraphDocument first = graph(resource, 1, Set.of(), OpaqueData.of(Map.of("server", "one")));
        assertEquals(GraphResourceCache.Status.NEW, cache.reconcile(project(first, 1, SECOND_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"))).orElseThrow().status());
        assertTrue(cache.draft(resource).isPresent());

        GraphDocument acknowledged = graph(resource, 2, Set.of(), OpaqueData.of(Map.of("server", "two")));
        assertEquals(GraphResourceCache.Status.ACKNOWLEDGED, cache.reconcile(project(acknowledged, 2, FIRST_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"))).orElseThrow().status());
        assertTrue(cache.draft(resource).isEmpty());
    }

    @Test
    void equalRevisionDuplicateAndConflictReachAuthoritativeReconciliation() {
        ServerResourceLocator resource = resource("flow", "equal-revision");
        CoreGraphResourceCache cache = new CoreGraphResourceCache();
        CoreGraphResourceProjection.Projection first = project(
            graph(resource, 1, Set.of(), OpaqueData.of(Map.of("value", "first"))), 1, FIRST_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"));
        CoreGraphResourceProjection.Projection conflict = project(
            graph(resource, 1, Set.of(), OpaqueData.of(Map.of("value", "second"))), 1, SECOND_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"));

        assertEquals(GraphResourceCache.Status.NEW, cache.reconcile(first).orElseThrow().status());
        assertEquals(GraphResourceCache.Status.DUPLICATE, cache.reconcile(first).orElseThrow().status());
        assertEquals(GraphResourceCache.Status.CONFLICT, cache.reconcile(conflict).orElseThrow().status());

        CoreGraphResourceCache pageCache = new CoreGraphResourceCache();
        CoreGraphResourceProjection.PageProjection page = new CoreGraphResourceProjection.PageProjection(
            first.envelopeProjection(), new ProtocolBody.ResourcePageResponse(ResourceOperationKind.LIST,
                new ResourcePage<>(List.of(), null, true), Map.of()), List.of(first, conflict));
        assertEquals(List.of(GraphResourceCache.Status.NEW, GraphResourceCache.Status.CONFLICT),
            pageCache.reconcilePage(page).stream().map(GraphResourceCache.Reconciliation::status).toList());
    }

    @Test
    void retainsAuthoritativeTombstone() {
        ServerResourceLocator resource = resource("flow", "deleted");
        CoreGraphResourceCache cache = new CoreGraphResourceCache();
        GraphDocument graph = graph(resource, 1, Set.of(), OpaqueData.empty());
        cache.reconcile(project(graph, 1, FIRST_MUTATION, ResourceActivationState.ACTIVE,
            Map.of(), Map.of(), Set.of("resources")));
        ContentHash priorInnerChecksum = cache.state(resource).orElseThrow().innerChecksum();

        ResourceDocument<Map<String, Object>> tombstone = ResourceDocument.tombstone(resource, 2, SECOND_MUTATION,
            new ContentHash("d".repeat(64)), "server");
        ProtocolEnvelope<Map<String, Object>> envelope = envelope(resource, tombstone, ResourceOperationKind.DELETE,
            Map.of(), Map.of());

        assertEquals(GraphResourceCache.Status.NEWER, cache.reconcile(
            CoreGraphResourceProjection.project(envelope, Set.of("resources"))).orElseThrow().status());
        assertTrue(cache.state(resource).orElseThrow().tombstone());
        assertFalse(cache.state(resource).orElseThrow().live());
        assertEquals(priorInnerChecksum, cache.state(resource).orElseThrow().innerChecksum());
        assertNotEquals(cache.state(resource).orElseThrow().protocolHash(), priorInnerChecksum);
    }

    @Test
    void rejectsARejectedPageBeforeApplyingAnyItem() {
        ServerResourceLocator resource = resource("flow", "rejected-page");
        GraphDocument graph = graph(resource, 1, Set.of(), OpaqueData.empty());
        CoreGraphResourceProjection.Projection valid = project(graph, 1, FIRST_MUTATION,
            ResourceActivationState.ACTIVE, Map.of(), Map.of(), Set.of("resources"));
        CoreGraphResourceProjection.Projection rejected = new CoreGraphResourceProjection.Projection(
            valid.envelopeProjection(), valid.resourceDocument(), CoreGraphResourceProjection.Status.REJECTED,
            null, null, -1L, null, null, null, 0, 0, null, Set.of(), Set.of(), "rejected item");
        CoreGraphResourceProjection.PageProjection page = new CoreGraphResourceProjection.PageProjection(
            valid.envelopeProjection(), new ProtocolBody.ResourcePageResponse(ResourceOperationKind.LIST,
                new ResourcePage<>(List.of(), null, true), Map.of()), List.of(valid, rejected));
        CoreGraphResourceCache cache = new CoreGraphResourceCache();

        assertFalse(page.accepted());
        assertThrows(IllegalArgumentException.class, () -> cache.reconcilePage(page));
        assertTrue(cache.state(resource).isEmpty());
        assertTrue(cache.sidecar().isEmpty());
    }

    @Test
    void clearsTypedStatesDraftsAndProjectionSidecarsForServer() {
        ServerResourceLocator liveResource = resource("flow", "clear-live");
        GraphDocument liveGraph = graph(liveResource, 1, Set.of(), OpaqueData.empty());
        CoreGraphResourceCache cache = new CoreGraphResourceCache();
        cache.reconcile(project(liveGraph, 1, FIRST_MUTATION, ResourceActivationState.ACTIVE,
            Map.of(), Map.of(), Set.of("resources")));
        cache.putDraft(GraphDraft.from(cache.state(liveResource).orElseThrow()));

        ServerResourceLocator readOnlyResource = resource("flow", "clear-read-only");
        GraphDocument readOnlyGraph = graph(readOnlyResource, 2, Set.of(UNSUPPORTED), OpaqueData.empty());
        cache.reconcile(project(readOnlyGraph, 2, SECOND_MUTATION, ResourceActivationState.ACTIVE,
            Map.of(), Map.of(), Set.of("resources")));
        assertEquals(2, cache.sidecar().size());

        cache.clearServer(SERVER.canonicalText());

        assertTrue(cache.states().isEmpty());
        assertTrue(cache.drafts().isEmpty());
        assertTrue(cache.sidecar().isEmpty());
    }

    private static CoreGraphResourceProjection.Projection project(GraphDocument graph, long revision, UUID mutation,
                                                                    ResourceActivationState activationState,
                                                                    Map<String, Object> unknownEnvelope,
                                                                    Map<String, Object> unknownBody,
                                                                    Set<String> supportedCapabilities) {
        Map<String, Object> payload = assetPayload(GraphDocumentCodec.INSTANCE.encode(graph),
            graph.resource().resourceType().value(), activationState, revision, mutation);
        ResourceDocument<Map<String, Object>> document = ResourceDocument.live(graph.resource(), revision, mutation,
            ResourcePayloadCodecs.json().canonicalize(payload), activationState, "server");
        return CoreGraphResourceProjection.project(envelope(graph.resource(), document, ResourceOperationKind.LOAD,
            unknownEnvelope, unknownBody), supportedCapabilities);
    }

    private static Map<String, Object> assetPayload(JsonValue.JsonObject core, String resourceType,
                                                    ResourceActivationState activationState, long revision,
                                                    UUID mutation) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, resourceType);
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION, CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, revision);
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, mutation.toString());
        fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, activationState.wireName());
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION, CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        @SuppressWarnings("unchecked")
        Map<String, Object> coreFields = (Map<String, Object>) core.toJava();
        fields.putAll(coreFields);
        ContentHash hash = new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields));
        fields.put(CoreGraphResourceProjection.ASSET_HASH, hash.canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return new LinkedHashMap<>(result);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource,
                                                                  ResourceDocument<Map<String, Object>> document,
                                                                  ResourceOperationKind operation,
                                                                  Map<String, Object> unknownEnvelope,
                                                                  Map<String, Object> unknownBody) {
        java.util.LinkedHashSet<ContractRef<CapabilityId>> capabilities = new java.util.LinkedHashSet<>();
        capabilities.add(ContractRef.of(OWNER, CapabilityId.of("resources")));
        if (document.activationState() == ResourceActivationState.INACTIVE) {
            capabilities.add(ContractRef.of(OWNER, CapabilityId.of("resource_activation")));
        }
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.EVENT, new CatalogVersion(1, 1), MESSAGE, REQUEST,
            CORRELATION, TRACE, SERVER, resource, document.revision(), document.mutationId(),
            ContractRef.of(OWNER, OperationId.of("resource." + operation.name().toLowerCase())), capabilities,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(),
            document.deleted(), null, null, null, null, null, 1L, ProtocolEnvelope.Status.OK, List.of(),
            unknownEnvelope, new ProtocolBody.ResourceDocumentResponse(operation, document, unknownBody));
    }

    private static GraphDocument graph(ServerResourceLocator resource, long revision,
                                       Set<ContractRef<CapabilityId>> requiredCapabilities, OpaqueData unknown) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, revision, BINDING, requiredCapabilities,
            List.of(), List.of(), List.of(), List.of(), unknown);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }
}
