package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphDocumentAuthoringAdapterTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final OwnerId OWNER = new OwnerId("resync.authoring.test");
    private static final CatalogBinding BINDING = new CatalogBinding(4, new ContentHash("a".repeat(64)),
        new ContentHash("b".repeat(64)));
    private static final CatalogBinding OTHER_BINDING = new CatalogBinding(5, new ContentHash("c".repeat(64)),
        new ContentHash("d".repeat(64)));
    private static final ContractRef<CapabilityId> MANAGEMENT = ContractRef.of(OWNER,
        CapabilityId.of("generic-resource-authoring"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));

    @Test
    void acceptsExactNativeTemplatesAndKeepsSameIdResourceTypesSeparate() {
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        Map<UUID, Consumer<AuthoringTemplateResponse>> callbacks = new HashMap<>();
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> {
                callbacks.put(requestId, callback);
                return true;
            }, ignored -> Optional.of(publication));
        List<ServerResourceLocator> resources = List.of(resource("flow", "shared"), resource("function", "shared"),
            resource("command", "shared"));
        Map<String, CoreGraphDocumentAuthoringAdapter.RequestResult> requests = new HashMap<>();

        for (ServerResourceLocator resource : resources) {
            CoreGraphDocumentAuthoringAdapter.RequestResult result = adapter.requestTemplate(resource, publication);
            requests.put(resource.resourceType().value(), result);
            assertEquals(CoreGraphDocumentAuthoringAdapter.RequestStatus.REQUESTED, result.status());
        }

        assertEquals(3, adapter.pendingRequests());
        ContentHash authoringChecksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
        assertEquals(authoringChecksum, requests.get("flow").request().acknowledgedAuthoringPublicationChecksum());
        assertEquals(authoringChecksum,
            adapter.pendingAuthoringChecksum(requests.get("flow").requestId()).orElseThrow());
        assertNotEquals(requests.get("flow").requestId(), requests.get("function").requestId());
        assertNotEquals(requests.get("flow").requestId(), requests.get("command").requestId());

        for (ServerResourceLocator resource : resources) {
            CoreGraphDocumentAuthoringAdapter.RequestResult request = requests.get(resource.resourceType().value());
            GraphDocument graph = graph(resource, BINDING);
            AuthoringTemplateResponse response = AuthoringTemplateResponse.of(resource, request.request().acknowledgedCatalogKey(),
                BINDING, payload(resource, graph), authoringChecksum, Set.of());
            assertEquals(authoringChecksum, response.authoringPublicationChecksum());
            callbacks.remove(request.requestId()).accept(response);
            CoreGraphDocumentAuthoringAdapter.DraftResult completed = adapter.completed(request.requestId()).orElseThrow();
            assertTrue(completed.accepted());
            assertEquals(authoringChecksum, completed.authoringPublicationChecksum());
            assertEquals(Set.of(), completed.editCapabilities());
            CoreGraphEditorSession session = adapter.editorSession(request.requestId()).orElseThrow();
            assertEquals(authoringChecksum, session.activeAuthoringChecksum());
            assertEquals(Set.of(), session.activeAuthoringCapabilities());
            GraphDraft draft = adapter.draft(resource).orElseThrow();
            assertEquals(resource, draft.resource());
            assertEquals(BINDING, draft.catalogBinding());
            if ("function".equals(resource.resourceType().value())) {
                assertEquals(graph.checksum(), draft.functionSourceDocument().graph().checksum());
            } else {
                assertEquals(graph.checksum(), draft.graphDocument().checksum());
            }
        }

        assertEquals(3, adapter.drafts().size());
    }

    @Test
    void rejectsReadOnlyCatalogAndStaleBindingWithoutLeavingDrafts() {
        ServerResourceLocator resource = resource("flow", "stale");
        CatalogAuthoringPublication activePublication = publication(BINDING, CatalogCacheState.ACTIVE);
        Map<UUID, Consumer<AuthoringTemplateResponse>> callbacks = new HashMap<>();
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> {
                callbacks.put(requestId, callback);
                return true;
            }, ignored -> Optional.of(activePublication));
        CoreGraphDocumentAuthoringAdapter.RequestResult readOnly = adapter.requestTemplate(resource,
            publication(BINDING, CatalogCacheState.READ_ONLY));
        assertEquals(CoreGraphDocumentAuthoringAdapter.RequestStatus.REJECTED, readOnly.status());
        assertEquals(CoreGraphDocumentAuthoringAdapter.AUTHORING_CATALOG_UNAVAILABLE, readOnly.reason());

        CoreGraphDocumentAuthoringAdapter.RequestResult request = adapter.requestTemplate(resource,
            publication(BINDING, CatalogCacheState.ACTIVE));
        GraphDocument graph = graph(resource, OTHER_BINDING);
        AuthoringTemplateResponse response = AuthoringTemplateResponse.of(resource,
            new CatalogCacheKey(resource.serverId(), OTHER_BINDING, CatalogProjectionVersion.current()), OTHER_BINDING,
            payload(resource, graph), CatalogCachePublicationCodec.authoringPublicationChecksum(
                publication(OTHER_BINDING, CatalogCacheState.ACTIVE)), Set.of());
        callbacks.remove(request.requestId()).accept(response);

        assertEquals(CoreGraphDocumentAuthoringAdapter.DraftStatus.REJECTED,
            adapter.completed(request.requestId()).orElseThrow().status());
        assertTrue(adapter.draft(resource).isEmpty());
    }

    @Test
    void rejectsTemplateWhenAuthoringPublicationChecksumChanges() {
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        Map<UUID, Consumer<AuthoringTemplateResponse>> callbacks = new HashMap<>();
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> {
                callbacks.put(requestId, callback);
                return true;
            }, ignored -> Optional.of(publication));
        ServerResourceLocator resource = resource("command", "checksum");
        CoreGraphDocumentAuthoringAdapter.RequestResult request = adapter.requestTemplate(resource, publication);
        ContentHash wrongChecksum = new ContentHash("e".repeat(64));
        AuthoringTemplateResponse response = AuthoringTemplateResponse.of(resource,
            request.request().acknowledgedCatalogKey(), BINDING, payload(resource, graph(resource, BINDING)),
            wrongChecksum, Set.of());

        callbacks.remove(request.requestId()).accept(response);

        CoreGraphDocumentAuthoringAdapter.DraftResult result = adapter.completed(request.requestId()).orElseThrow();
        assertEquals(CoreGraphDocumentAuthoringAdapter.DraftStatus.REJECTED, result.status());
        assertEquals(CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_CHECKSUM_MISMATCH, result.reason());
        assertTrue(adapter.draft(resource).isEmpty());
    }

    @Test
    void rejectsTemplateWhenAuthoringPublicationChecksumIsMissing() {
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        Map<UUID, Consumer<AuthoringTemplateResponse>> callbacks = new HashMap<>();
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> {
                callbacks.put(requestId, callback);
                return true;
            }, ignored -> Optional.of(publication));
        ServerResourceLocator resource = resource("flow", "missing-checksum");
        CoreGraphDocumentAuthoringAdapter.RequestResult request = adapter.requestTemplate(resource, publication);
        AuthoringTemplateResponse response = AuthoringTemplateResponse.of(resource,
            request.request().acknowledgedCatalogKey(), BINDING, payload(resource, graph(resource, BINDING)), Set.of());

        callbacks.remove(request.requestId()).accept(response);

        CoreGraphDocumentAuthoringAdapter.DraftResult result = adapter.completed(request.requestId()).orElseThrow();
        assertEquals(CoreGraphDocumentAuthoringAdapter.DraftStatus.REJECTED, result.status());
        assertEquals(CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_CHECKSUM_REQUIRED, result.reason());
        assertTrue(adapter.editorSession(request.requestId()).isEmpty());
        assertTrue(adapter.draft(resource).isEmpty());
    }

    @Test
    void doesNotOpenSessionAfterActiveAuthoringPublicationChanges() {
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        CatalogAuthoringPublication[] active = {publication};
        Map<UUID, Consumer<AuthoringTemplateResponse>> callbacks = new HashMap<>();
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> {
                callbacks.put(requestId, callback);
                return true;
            }, ignored -> Optional.ofNullable(active[0]));
        ServerResourceLocator resource = resource("command", "retired-session");
        CoreGraphDocumentAuthoringAdapter.RequestResult request = adapter.requestTemplate(resource, publication);
        ContentHash authoringChecksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
        AuthoringTemplateResponse response = AuthoringTemplateResponse.of(resource,
            request.request().acknowledgedCatalogKey(), BINDING, payload(resource, graph(resource, BINDING)),
            authoringChecksum, Set.of());
        callbacks.remove(request.requestId()).accept(response);

        assertTrue(adapter.editorSession(request.requestId()).isPresent());
        active[0] = publication(OTHER_BINDING, CatalogCacheState.ACTIVE);
        assertTrue(adapter.editorSession(request.requestId()).isEmpty());
    }

    @Test
    void clearsPendingAndDraftStateWhenServerGenerationIsRetired() {
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> true, ignored -> Optional.of(publication));
        ServerResourceLocator resource = resource("command", "retired");
        CoreGraphDocumentAuthoringAdapter.RequestResult request = adapter.requestTemplate(resource,
            publication);

        assertEquals(CoreGraphDocumentAuthoringAdapter.RequestStatus.REQUESTED, request.status());
        assertTrue(adapter.pending(request.requestId()).isPresent());
        adapter.clearServer(SERVER.canonicalText());

        assertTrue(adapter.pending(request.requestId()).isEmpty());
        assertTrue(adapter.completed(request.requestId()).isEmpty());
        assertTrue(adapter.draft(resource).isEmpty());
    }

    @Test
    void genericResourceCallbackSettlesAsARecoverableUnsupportedDraft() {
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        Map<UUID, Consumer<AuthoringTemplateResponse>> callbacks = new HashMap<>();
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> {
                callbacks.put(requestId, callback);
                return true;
            }, ignored -> Optional.of(publication));
        ServerResourceLocator resource = resource("flow", "generic-resource");
        CoreGraphDocumentAuthoringAdapter.RequestResult request = adapter.requestTemplate(resource, publication);
        ContentHash authoringChecksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
        AuthoringTemplatePayload.Resource payload = new AuthoringTemplatePayload.Resource(resource, BINDING,
            TypeReference.of("resync.authoring.test", "generic-document"), TypedValue.value(TEXT, "value"),
            Set.of(MANAGEMENT));
        AuthoringTemplateResponse response = AuthoringTemplateResponse.ofResource(resource,
            request.request().acknowledgedCatalogKey(), BINDING, payload, authoringChecksum, Set.of(MANAGEMENT),
            new ContentHash("f".repeat(64)), MANAGEMENT);

        assertDoesNotThrow(() -> callbacks.remove(request.requestId()).accept(response));

        CoreGraphDocumentAuthoringAdapter.DraftResult result = adapter.completed(request.requestId()).orElseThrow();
        assertEquals(CoreGraphDocumentAuthoringAdapter.DraftStatus.REJECTED, result.status());
        assertEquals(CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_PAYLOAD_UNSUPPORTED, result.reason());
        assertEquals(0, adapter.pendingRequests());
        assertTrue(adapter.pending(request.requestId()).isEmpty());
        assertTrue(adapter.editorSession(request.requestId()).isEmpty());
        assertTrue(adapter.draft(resource).isEmpty());
    }

    @Test
    void callbackIdentityUsesTheFullResourceLocator() {
        CatalogAuthoringPublication publication = publication(BINDING, CatalogCacheState.ACTIVE);
        Map<UUID, Consumer<AuthoringTemplateResponse>> callbacks = new HashMap<>();
        CoreGraphDocumentAuthoringAdapter adapter = new CoreGraphDocumentAuthoringAdapter(
            (requestId, request, callback) -> {
                callbacks.put(requestId, callback);
                return true;
            }, ignored -> Optional.of(publication));
        ServerResourceLocator requested = resource("flow", "same-name");
        ServerResourceLocator otherOwner = new ServerResourceLocator(SERVER,
            ContractRef.of(OwnerId.of("other.authority"), ResourceTypeId.of("flow")), requested.id());
        CoreGraphDocumentAuthoringAdapter.RequestResult request = adapter.requestTemplate(requested, publication);
        ContentHash authoringChecksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
        AuthoringTemplateResponse response = AuthoringTemplateResponse.of(otherOwner,
            request.request().acknowledgedCatalogKey(), BINDING,
            AuthoringTemplatePayload.flow(graph(otherOwner, BINDING)), authoringChecksum, Set.of());

        assertDoesNotThrow(() -> callbacks.remove(request.requestId()).accept(response));

        CoreGraphDocumentAuthoringAdapter.DraftResult result = adapter.completed(request.requestId()).orElseThrow();
        assertEquals(CoreGraphDocumentAuthoringAdapter.DraftStatus.REJECTED, result.status());
        assertEquals(CoreGraphDocumentAuthoringAdapter.AUTHORING_TEMPLATE_STALE, result.reason());
        assertEquals(0, adapter.pendingRequests());
        assertTrue(adapter.draft(requested).isEmpty());
        assertTrue(adapter.draft(otherOwner).isEmpty());
    }

    private static CatalogAuthoringPublication publication(CatalogBinding binding, CatalogCacheState state) {
        List<CatalogAuthoringPublication.SectionProjection> sections = List.of(
            section(CatalogAuthoringPublication.Section.TYPES, state),
            section(CatalogAuthoringPublication.Section.EDITORS, state),
            section(CatalogAuthoringPublication.Section.PREVIEWS, state),
            section(CatalogAuthoringPublication.Section.CAPABILITIES, state));
        return new CatalogAuthoringPublication(binding, new CatalogVersion(1, 0),
            CatalogProjectionVersion.current(), sections, Set.of());
    }

    private static CatalogAuthoringPublication.SectionProjection section(CatalogAuthoringPublication.Section section,
                                                                         CatalogCacheState state) {
        return new CatalogAuthoringPublication.SectionProjection(section, true, true, state, List.of());
    }

    private static AuthoringTemplatePayload payload(ServerResourceLocator resource, GraphDocument graph) {
        return switch (resource.resourceType().value()) {
            case "flow" -> AuthoringTemplatePayload.flow(graph);
            case "command" -> AuthoringTemplatePayload.command(graph);
            case "function" -> AuthoringTemplatePayload.function(new FunctionSourceDocument(
                new FunctionSignature(new FunctionLocator(resource), FunctionRevision.initial(), List.of(), List.of()), graph));
            default -> throw new IllegalArgumentException("Unsupported test resource type");
        };
    }

    private static GraphDocument graph(ServerResourceLocator resource, CatalogBinding binding) {
        return new GraphDocument(new CatalogVersion(1, 0), resource, 0L, binding, Set.of(), List.of(), List.of(),
            List.of(), List.of(), OpaqueData.empty());
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }
}
