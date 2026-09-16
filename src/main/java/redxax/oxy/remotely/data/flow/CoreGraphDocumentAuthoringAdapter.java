package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.authoring.AuthoringTemplatePayload;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.GraphDraft;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.AuthoringTemplateRequest;
import restudio.resync.flow.protocol.AuthoringTemplateResponse;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class CoreGraphDocumentAuthoringAdapter {
    public static final int MAX_PENDING_REQUESTS = 32;
    public static final String TEMPLATE_TRANSPORT_UNAVAILABLE =
        "AUTHORING_TEMPLATE_TRANSPORT_UNAVAILABLE";
    public static final String TEMPLATE_DISPATCH_FAILED = "AUTHORING_TEMPLATE_DISPATCH_FAILED";
    public static final String AUTHORING_CATALOG_UNAVAILABLE = "AUTHORING_CATALOG_UNAVAILABLE";
    public static final String CATALOG_AUTHORITY_UNAVAILABLE = "CATALOG_AUTHORITY_UNAVAILABLE";
    public static final String AUTHORING_TEMPLATE_STALE = "AUTHORING_TEMPLATE_STALE";
    public static final String AUTHORING_TEMPLATE_CHECKSUM_REQUIRED = "AUTHORING_TEMPLATE_CHECKSUM_REQUIRED";
    public static final String AUTHORING_TEMPLATE_CHECKSUM_MISMATCH = "AUTHORING_TEMPLATE_CHECKSUM_MISMATCH";
    public static final String AUTHORING_TEMPLATE_PAYLOAD_UNSUPPORTED = "AUTHORING_TEMPLATE_PAYLOAD_UNSUPPORTED";

    private final TemplateTransport transport;
    private final ActivePublicationProvider activePublicationProvider;
    private final Map<UUID, PendingRequest> pending = new LinkedHashMap<>();
    private final Map<UUID, DraftResult> completed = new LinkedHashMap<>();
    private final Map<UUID, ServerResourceLocator> completedResources = new LinkedHashMap<>();
    private final Map<Identity, GraphDraft> drafts = new LinkedHashMap<>();

    public CoreGraphDocumentAuthoringAdapter() {
        this(null, resource -> Optional.empty());
    }

    public CoreGraphDocumentAuthoringAdapter(TemplateTransport transport) {
        this(transport, resource -> Optional.empty());
    }

    public CoreGraphDocumentAuthoringAdapter(TemplateTransport transport,
                                             ActivePublicationProvider activePublicationProvider) {
        this.transport = transport;
        this.activePublicationProvider = Objects.requireNonNull(activePublicationProvider,
            "Active authoring publication provider is required");
    }

    public RequestResult requestTemplate(ServerResourceLocator resource,
                                         CatalogAuthoringPublication publication) {
        Validation validation = validateRequest(resource, publication);
        if (!validation.accepted()) {
            synchronized (this) {
                if (resource != null) {
                    drafts.remove(Identity.from(resource));
                }
            }
            return RequestResult.rejected(validation.reason());
        }
        TemplateTransport currentTransport = transport;
        if (currentTransport == null) {
            synchronized (this) {
                drafts.remove(Identity.from(resource));
            }
            return RequestResult.unavailable(TEMPLATE_TRANSPORT_UNAVAILABLE);
        }
        if (!activePublication(resource).filter(publication::equals).isPresent()) {
            synchronized (this) {
                drafts.remove(Identity.from(resource));
            }
            return RequestResult.unavailable(AUTHORING_CATALOG_UNAVAILABLE);
        }
        CatalogCacheKey key = new CatalogCacheKey(resource.serverId(), publication.binding(),
            publication.projectionVersion());
        Identity identity = Identity.from(resource);
        UUID requestId;
        AuthoringTemplateRequest request;
        synchronized (this) {
            for (Map.Entry<UUID, PendingRequest> entry : pending.entrySet()) {
                PendingRequest pendingRequest = entry.getValue();
                if (pendingRequest.resource().equals(resource) && pendingRequest.key().equals(key)) {
                    return RequestResult.requested(entry.getKey(), pendingRequest.request());
                }
            }
            pending.entrySet().removeIf(entry -> entry.getValue().resource().equals(resource));
            drafts.remove(identity);
            if (pending.size() >= MAX_PENDING_REQUESTS) {
                return RequestResult.rejected("AUTHORING_TEMPLATE_REQUEST_LIMIT");
            }
            requestId = UUID.randomUUID();
            ContentHash authoringChecksum = CatalogCachePublicationCodec.authoringPublicationChecksum(publication);
            request = new AuthoringTemplateRequest(resource, key, authoringChecksum);
            pending.put(requestId, new PendingRequest(requestId, request, publication, authoringChecksum));
        }
        boolean dispatched;
        try {
            dispatched = currentTransport.request(requestId, request, response -> acceptTemplate(requestId, response));
        } catch (RuntimeException exception) {
            boolean completedRequest;
            synchronized (this) {
                completedRequest = completed.containsKey(requestId);
                if (!completedRequest) {
                    pending.remove(requestId);
                    DraftResult result = DraftResult.rejected(requestId, TEMPLATE_DISPATCH_FAILED);
                    rememberCompleted(result, request.resource());
                }
            }
            if (completedRequest) {
                return RequestResult.requested(requestId, request);
            }
            return RequestResult.rejected(requestId, request, TEMPLATE_DISPATCH_FAILED);
        }
        if (!dispatched) {
            synchronized (this) {
                if (completed.containsKey(requestId)) {
                    return RequestResult.requested(requestId, request);
                }
                pending.remove(requestId);
                DraftResult result = DraftResult.rejected(requestId, TEMPLATE_TRANSPORT_UNAVAILABLE);
                rememberCompleted(result, request.resource());
            }
            return RequestResult.unavailable(requestId, request, TEMPLATE_TRANSPORT_UNAVAILABLE);
        }
        return RequestResult.requested(requestId, request);
    }

    public synchronized DraftResult acceptTemplate(UUID requestId, AuthoringTemplateResponse response) {
        return acceptTemplate(requestId, response, null);
    }

    public synchronized DraftResult acceptTemplate(UUID requestId, AuthoringTemplateResponse response,
                                                   CatalogAuthoringPublication activePublication) {
        PendingRequest request = requestId == null ? null : pending.remove(requestId);
        if (request == null) {
            return DraftResult.rejected(requestId, AUTHORING_TEMPLATE_STALE);
        }
        String checksumDiagnostic = checksumDiagnostic(request, response);
        if (!checksumDiagnostic.isEmpty()) {
            DraftResult result = DraftResult.rejected(request.requestId(), checksumDiagnostic);
            drafts.remove(Identity.from(request.resource()));
            rememberCompleted(result, request.resource());
            return result;
        }
        CatalogAuthoringPublication currentPublication = activePublication != null ? activePublication
            : activePublication(request.resource()).orElse(null);
        ContentHash activeChecksum = currentPublication == null ? null
            : CatalogCachePublicationCodec.authoringPublicationChecksum(currentPublication);
        DraftResult result = currentPublication != null && request.publication().equals(currentPublication)
            && request.authoringChecksum().equals(activeChecksum)
            ? validateAndCreate(request, response)
            : DraftResult.rejected(request.requestId(), AUTHORING_CATALOG_UNAVAILABLE);
        if (result.accepted()) {
            drafts.put(Identity.from(result.draft().resource()), result.draft());
        } else {
            drafts.remove(Identity.from(request.resource()));
        }
        rememberCompleted(result, request.resource());
        return result;
    }

    public synchronized DraftResult rejectTemplate(UUID requestId, String reason) {
        PendingRequest request = requestId == null ? null : pending.remove(requestId);
        if (request == null) {
            return DraftResult.rejected(requestId, reason == null || reason.isBlank()
                ? AUTHORING_TEMPLATE_STALE : reason);
        }
        drafts.remove(Identity.from(request.resource()));
        DraftResult result = DraftResult.rejected(requestId, reason == null || reason.isBlank()
            ? AUTHORING_TEMPLATE_STALE : reason);
        rememberCompleted(result, request.resource());
        return result;
    }

    public synchronized Optional<DraftResult> completed(UUID requestId) {
        return Optional.ofNullable(requestId == null ? null : completed.get(requestId));
    }

    public synchronized Optional<GraphDraft> draft(ServerResourceLocator resource) {
        return Optional.ofNullable(resource == null ? null : drafts.get(Identity.from(resource)));
    }

    public synchronized int pendingRequests() {
        return pending.size();
    }

    public synchronized Map<ServerResourceLocator, GraphDraft> drafts() {
        Map<ServerResourceLocator, GraphDraft> result = new LinkedHashMap<>();
        drafts.forEach((identity, draft) -> result.put(draft.resource(), draft));
        return Collections.unmodifiableMap(result);
    }

    public synchronized void clearServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        pending.entrySet().removeIf(entry -> serverId.equals(entry.getValue().resource().serverId().canonicalText()));
        drafts.entrySet().removeIf(entry -> serverId.equals(entry.getValue().resource().serverId().canonicalText()));
        completedResources.entrySet().removeIf(entry -> serverId.equals(entry.getValue().serverId().canonicalText()));
        completed.keySet().removeIf(requestId -> !completedResources.containsKey(requestId));
    }

    public synchronized Optional<AuthoringTemplateRequest> pending(UUID requestId) {
        PendingRequest request = requestId == null ? null : pending.get(requestId);
        return request == null ? Optional.empty() : Optional.of(request.request());
    }

    public synchronized Optional<ContentHash> pendingAuthoringChecksum(UUID requestId) {
        PendingRequest request = requestId == null ? null : pending.get(requestId);
        return request == null ? Optional.empty() : Optional.of(request.authoringChecksum());
    }

    public synchronized Optional<CoreGraphEditorSession> editorSession(UUID requestId) {
        DraftResult result = requestId == null ? null : completed.get(requestId);
        if (result == null || !result.accepted()) {
            return Optional.empty();
        }
        CatalogAuthoringPublication currentPublication = activePublication(result.draft().resource()).orElse(null);
        if (currentPublication == null || !catalogReady(currentPublication)
            || !result.authoringPublicationChecksum().equals(
                CatalogCachePublicationCodec.authoringPublicationChecksum(currentPublication))) {
            return Optional.empty();
        }
        return Optional.of(result.editorSession());
    }

    public synchronized void discard(ServerResourceLocator resource) {
        if (resource != null) {
            drafts.remove(Identity.from(resource));
        }
    }

    public synchronized void clear() {
        pending.clear();
        completed.clear();
        completedResources.clear();
        drafts.clear();
    }

    private DraftResult validateAndCreate(PendingRequest request, AuthoringTemplateResponse response) {
        String checksumDiagnostic = checksumDiagnostic(request, response);
        if (!checksumDiagnostic.isEmpty()) {
            return DraftResult.rejected(request.requestId(), checksumDiagnostic);
        }
        if (!request.resource().equals(response.resource()) || !request.key().equals(response.publicationKey())
            || !request.key().hasCatalogBinding() || !request.key().catalogBinding().equals(response.catalogBinding())) {
            return DraftResult.rejected(request.requestId(), AUTHORING_TEMPLATE_STALE);
        }
        CatalogAuthoringPublication publication = request.publication();
        if (!catalogReady(publication) || !publication.binding().equals(response.catalogBinding())) {
            return DraftResult.rejected(request.requestId(), AUTHORING_CATALOG_UNAVAILABLE);
        }
        AuthoringTemplatePayload payload = response.payload();
        if (!matchesResourceType(request.resource(), payload) || !payload.catalogBinding().equals(response.catalogBinding())
            || !payload.checksum().equals(response.templateChecksum())) {
            return DraftResult.rejected(request.requestId(), AUTHORING_TEMPLATE_STALE);
        }
        if (payload instanceof AuthoringTemplatePayload.Resource) {
            return DraftResult.rejected(request.requestId(), AUTHORING_TEMPLATE_PAYLOAD_UNSUPPORTED);
        }
        if (!publication.advertisedEditCapabilities().containsAll(response.editCapabilities())
            || !catalogCapabilities(publication).containsAll(response.requiredCapabilities())) {
            return DraftResult.rejected(request.requestId(), AUTHORING_CATALOG_UNAVAILABLE);
        }
        try {
            GraphDraft draft = switch (payload) {
                case AuthoringTemplatePayload.Flow flow -> new GraphDraft(request.resource(), 0L, requireGraph(flow.document()));
                case AuthoringTemplatePayload.Command command -> new GraphDraft(request.resource(), 0L, requireGraph(command.document()));
                case AuthoringTemplatePayload.Function function -> new GraphDraft(request.resource(), 0L,
                    requireFunction(function.document()));
                case AuthoringTemplatePayload.Resource ignored -> throw new IllegalStateException(
                    "Resource authoring payload reached the graph editor");
            };
            if (!draft.resource().equals(response.resource()) || draft.baseRevision() != 0L
                || draft.catalogBinding() == null || !draft.catalogBinding().equals(response.catalogBinding())) {
                return DraftResult.rejected(request.requestId(), AUTHORING_TEMPLATE_STALE);
            }
            return DraftResult.accepted(request.requestId(), draft, request.authoringChecksum(),
                catalogCapabilities(publication), response.editCapabilities());
        } catch (RuntimeException exception) {
            return DraftResult.rejected(request.requestId(), AUTHORING_TEMPLATE_STALE);
        }
    }

    private static String checksumDiagnostic(PendingRequest request, AuthoringTemplateResponse response) {
        if (response == null) {
            return AUTHORING_TEMPLATE_STALE;
        }
        if (response.authoringPublicationChecksum() == null) {
            return AUTHORING_TEMPLATE_CHECKSUM_REQUIRED;
        }
        if (request.request().acknowledgedAuthoringPublicationChecksum() == null
            || !request.authoringChecksum().equals(request.request().acknowledgedAuthoringPublicationChecksum())
            || !request.authoringChecksum().equals(response.authoringPublicationChecksum())) {
            return AUTHORING_TEMPLATE_CHECKSUM_MISMATCH;
        }
        return "";
    }

    private Optional<CatalogAuthoringPublication> activePublication(ServerResourceLocator resource) {
        try {
            Optional<CatalogAuthoringPublication> publication = activePublicationProvider.active(resource);
            return publication == null ? Optional.empty() : publication;
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static GraphDocument requireGraph(GraphDocument document) {
        if (document == null || document.revision() != 0L) {
            throw new IllegalArgumentException("Authoring template graph must be revision zero");
        }
        return document;
    }

    private static FunctionSourceDocument requireFunction(FunctionSourceDocument document) {
        if (document == null || document.graph().revision() != 0L || document.signature().revision().value() != 0L) {
            throw new IllegalArgumentException("Authoring template function must be revision zero");
        }
        return document;
    }

    private static boolean matchesResourceType(ServerResourceLocator resource, AuthoringTemplatePayload payload) {
        String type = resource.resourceType().value();
        return switch (payload.kind()) {
            case FLOW -> "flow".equals(type);
            case COMMAND -> "command".equals(type);
            case FUNCTION -> "function".equals(type);
            case RESOURCE -> payload instanceof AuthoringTemplatePayload.Resource resourcePayload
                && resource.equals(resourcePayload.resource());
        };
    }

    private static Set<ContractRef<CapabilityId>> catalogCapabilities(CatalogAuthoringPublication publication) {
        CatalogAuthoringPublication.SectionProjection section = publication.section(
            CatalogAuthoringPublication.Section.CAPABILITIES);
        if (section == null) {
            return Set.of();
        }
        return section.entries().stream()
            .filter(entry -> entry != null && entry.editable())
            .map(CatalogAuthoringPublication.Entry::reference)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<ContractRef<CapabilityId>> immutableCapabilities(Collection<ContractRef<CapabilityId>> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(values);
    }

    private static Validation validateRequest(ServerResourceLocator resource, CatalogAuthoringPublication publication) {
        if (resource == null || resource.id() == null || resource.id().isBlank() || !isGraphType(resource)) {
            return Validation.rejected("AUTHORING_TEMPLATE_RESOURCE_INVALID");
        }
        if (publication == null || !catalogReady(publication) || publication.binding() == null
            || publication.projectionVersion() == null) {
            return Validation.rejected(AUTHORING_CATALOG_UNAVAILABLE);
        }
        return Validation.valid();
    }

    private static boolean catalogReady(CatalogAuthoringPublication publication) {
        return publication != null && publication.compatible()
            && editableSection(publication, CatalogAuthoringPublication.Section.TYPES)
            && editableSection(publication, CatalogAuthoringPublication.Section.EDITORS)
            && editableSection(publication, CatalogAuthoringPublication.Section.CAPABILITIES);
    }

    private static boolean editableSection(CatalogAuthoringPublication publication,
                                           CatalogAuthoringPublication.Section section) {
        CatalogAuthoringPublication.SectionProjection projection = publication.section(section);
        return projection != null && projection.editable();
    }

    private static boolean isGraphType(ServerResourceLocator resource) {
        return switch (resource.resourceType().value()) {
            case "flow", "function", "command" -> true;
            default -> false;
        };
    }

    private void rememberCompleted(DraftResult result, ServerResourceLocator resource) {
        if (result.requestId() == null) {
            return;
        }
        completed.put(result.requestId(), result);
        if (resource != null) {
            completedResources.put(result.requestId(), resource);
        } else {
            completedResources.remove(result.requestId());
        }
        while (completed.size() > MAX_PENDING_REQUESTS) {
            UUID removed = completed.keySet().iterator().next();
            completed.remove(removed);
            completedResources.remove(removed);
        }
    }

    @FunctionalInterface
    public interface TemplateTransport {
        boolean request(UUID requestId, AuthoringTemplateRequest request,
                        Consumer<AuthoringTemplateResponse> responseConsumer);
    }

    @FunctionalInterface
    public interface ActivePublicationProvider {
        Optional<CatalogAuthoringPublication> active(ServerResourceLocator resource);
    }

    public enum RequestStatus {
        REQUESTED,
        PENDING,
        REJECTED,
        UNAVAILABLE
    }

    public enum DraftStatus {
        ACCEPTED,
        REJECTED
    }

    public record RequestResult(RequestStatus status, UUID requestId, AuthoringTemplateRequest request, String reason) {
        public RequestResult {
            status = Objects.requireNonNull(status, "Authoring template request status is required");
            reason = reason == null ? "" : reason;
            if (status == RequestStatus.REQUESTED && (requestId == null || request == null)) {
                throw new IllegalArgumentException("Requested authoring templates require request correlation");
            }
        }

        private static RequestResult requested(UUID requestId, AuthoringTemplateRequest request) {
            return new RequestResult(RequestStatus.REQUESTED, requestId, request, "");
        }

        public static RequestResult pending(String reason) {
            return new RequestResult(RequestStatus.PENDING, null, null, reason);
        }

        public boolean admitted() {
            return status == RequestStatus.REQUESTED || status == RequestStatus.PENDING;
        }

        private static RequestResult rejected(String reason) {
            return new RequestResult(RequestStatus.REJECTED, null, null, reason);
        }

        private static RequestResult rejected(UUID requestId, AuthoringTemplateRequest request, String reason) {
            return new RequestResult(RequestStatus.REJECTED, requestId, request, reason);
        }

        private static RequestResult unavailable(String reason) {
            return new RequestResult(RequestStatus.UNAVAILABLE, null, null, reason);
        }

        private static RequestResult unavailable(UUID requestId, AuthoringTemplateRequest request, String reason) {
            return new RequestResult(RequestStatus.UNAVAILABLE, requestId, request, reason);
        }
    }

    public record DraftResult(DraftStatus status, UUID requestId, GraphDraft draft,
                              ContentHash authoringPublicationChecksum,
                              Set<ContractRef<CapabilityId>> authoringCapabilities,
                              Set<ContractRef<CapabilityId>> editCapabilities, String reason) {
        public DraftResult(DraftStatus status, UUID requestId, GraphDraft draft,
                           ContentHash authoringPublicationChecksum,
                           Set<ContractRef<CapabilityId>> editCapabilities, String reason) {
            this(status, requestId, draft, authoringPublicationChecksum, Set.of(), editCapabilities, reason);
        }

        public DraftResult {
            status = Objects.requireNonNull(status, "Authoring template draft status is required");
            authoringCapabilities = immutableCapabilities(authoringCapabilities);
            editCapabilities = immutableCapabilities(editCapabilities);
            reason = reason == null ? "" : reason;
            if (status == DraftStatus.ACCEPTED
                && (requestId == null || draft == null || authoringPublicationChecksum == null)) {
                throw new IllegalArgumentException("Accepted authoring templates require validated identity");
            }
            if (status != DraftStatus.ACCEPTED
                && (draft != null || authoringPublicationChecksum != null || !authoringCapabilities.isEmpty()
                    || !editCapabilities.isEmpty())) {
                throw new IllegalArgumentException("Rejected authoring templates cannot expose validated state");
            }
        }

        private static DraftResult accepted(UUID requestId, GraphDraft draft, ContentHash authoringChecksum,
                                            Collection<ContractRef<CapabilityId>> authoringCapabilities,
                                            Collection<ContractRef<CapabilityId>> editCapabilities) {
            return new DraftResult(DraftStatus.ACCEPTED, requestId, draft, authoringChecksum,
                immutableCapabilities(authoringCapabilities), immutableCapabilities(editCapabilities), "");
        }

        private static DraftResult rejected(UUID requestId, String reason) {
            return new DraftResult(DraftStatus.REJECTED, requestId, null, null, Set.of(), Set.of(), reason);
        }

        public boolean accepted() {
            return status == DraftStatus.ACCEPTED;
        }

        public CoreGraphEditorSession editorSession() {
            if (!accepted()) {
                throw new IllegalStateException("Only an accepted authoring draft can open an editor session");
            }
            if (draft.functionSourceDocument() != null) {
                return new CoreGraphEditorSession(draft.functionSourceDocument(), draft.catalogBinding(),
                    authoringPublicationChecksum, authoringCapabilities, editCapabilities);
            }
            if (draft.graphDocument() != null) {
                return new CoreGraphEditorSession(draft.graphDocument(), draft.catalogBinding(),
                    authoringPublicationChecksum, authoringCapabilities, editCapabilities);
            }
            throw new IllegalStateException("Accepted authoring draft does not contain an editable document");
        }
    }

    private record PendingRequest(UUID requestId, AuthoringTemplateRequest request,
                                  CatalogAuthoringPublication publication, ContentHash authoringChecksum) {
        private PendingRequest {
            requestId = Objects.requireNonNull(requestId, "Authoring template request ID is required");
            request = Objects.requireNonNull(request, "Authoring template request is required");
            publication = Objects.requireNonNull(publication, "Authoring publication is required");
            authoringChecksum = Objects.requireNonNull(authoringChecksum, "Authoring publication checksum is required");
        }

        private ServerResourceLocator resource() {
            return request.resource();
        }

        private CatalogCacheKey key() {
            return request.acknowledgedCatalogKey();
        }
    }

    private record Identity(String server, String owner, String type, String id) {
        private Identity {
            server = Objects.requireNonNull(server, "Resource server is required");
            owner = Objects.requireNonNull(owner, "Resource owner is required");
            type = Objects.requireNonNull(type, "Resource type is required");
            id = Objects.requireNonNull(id, "Resource ID is required");
        }

        private static Identity from(ServerResourceLocator resource) {
            return new Identity(resource.serverId().canonicalText(), resource.owner().canonicalText(),
                resource.resourceType().value(), resource.id());
        }
    }

    private record Validation(boolean accepted, String reason) {
        private static Validation valid() {
            return new Validation(true, "");
        }

        private static Validation rejected(String reason) {
            return new Validation(false, reason);
        }
    }
}
