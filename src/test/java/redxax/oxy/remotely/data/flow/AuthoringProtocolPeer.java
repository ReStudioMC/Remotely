package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
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
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceLoadRequest;
import restudio.resync.flow.protocol.ResourceListRequest;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.flow.protocol.ResourceQueryRequest;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AuthoringProtocolPeer {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ContractRef<CapabilityId> RESOURCES = ContractRef.of(OWNER, CapabilityId.of("resources"));
    private static final Set<ContractRef<CapabilityId>> CAPABILITIES = Set.of(
        RESOURCES, ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY);
    private static final ProtocolEnvelopeCodec<Map<String, Object>> ENVELOPES =
        new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());

    private final ServerId server;
    private final Map<ServerResourceLocator, Stored> resources = new LinkedHashMap<>();
    private final Map<ServerResourceLocator, ResourceDocument<Map<String, Object>>> genericResources = new LinkedHashMap<>();
    private final Map<UUID, ProtocolEnvelope<Map<String, Object>>> committedMutations = new LinkedHashMap<>();
    private final Set<UUID> handledRequests = new LinkedHashSet<>();
    private final Map<OperationTarget, Integer> handledOperations = new LinkedHashMap<>();
    private ScriptedReSyncTransport transport;
    private int sequence = 20;
    private OperationTarget droppedResponse;
    private boolean divergeNextResponse;

    AuthoringProtocolPeer(ServerId server, ScriptedReSyncTransport transport) {
        this.server = server;
        attach(transport);
    }

    void attach(ScriptedReSyncTransport transport) {
        this.transport = transport;
    }

    void seed(ReSyncResourceType type, String id, Object payload, long revision, ResourceActivationState activation) {
        ServerResourceLocator resource = resource(type, id);
        Object revised = withRevision(payload, revision);
        resources.put(resource, new Stored(revised, revision, UUID.randomUUID(), activation));
    }

    ResourceDocument<Map<String, Object>> authoritative(ReSyncResourceType type, String id) {
        ServerResourceLocator resource = resource(type, id);
        Stored stored = resources.get(resource);
        if (stored == null) {
            throw new IllegalStateException("Missing peer resource " + type.typeId() + ":" + id);
        }
        return document(resource, stored);
    }

    Object authoritativePayload(ReSyncResourceType type, String id) {
        return requireStored(resource(type, id)).payload();
    }

    void advance(ReSyncResourceType type, String id, ResourceActivationState activation) {
        ServerResourceLocator resource = resource(type, id);
        Stored current = requireStored(resource);
        long revision = current.revision() + 1L;
        resources.put(resource, new Stored(withRevision(current.payload(), revision), revision, UUID.randomUUID(), activation));
    }

    void dropNextResponse(ResourceOperationKind operation, ReSyncResourceType type, String id) {
        droppedResponse = new OperationTarget(operation, resource(type, id));
    }

    void divergeNextResponse() {
        divergeNextResponse = true;
    }

    int handledCount(ResourceOperationKind operation, ReSyncResourceType type, String id) {
        return handledOperations.getOrDefault(new OperationTarget(operation, resource(type, id)), 0);
    }

    List<String> outboundOperations() {
        List<String> operations = new ArrayList<>();
        if (transport == null) {
            return operations;
        }
        for (ReSyncDecodedFrame frame : transport.sentFrames()) {
            if (frame.messageType() != ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                continue;
            }
            ProtocolEnvelope<Map<String, Object>> envelope = ENVELOPES.decodeBytes(frame.payload());
            if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
                continue;
            }
            String target = envelope.resource() == null ? operationType(request.operation())
                : envelope.resource().resourceType().canonicalText() + ":" + envelope.resource().id();
            operations.add(request.operation().kind() + "@" + target);
        }
        return List.copyOf(operations);
    }

    int pump() {
        if (transport == null) {
            throw new IllegalStateException("Peer transport is not attached");
        }
        int handled = 0;
        for (ReSyncDecodedFrame frame : transport.sentFrames()) {
            if (frame.messageType() != ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                continue;
            }
            ProtocolEnvelope<Map<String, Object>> request = ENVELOPES.decodeBytes(frame.payload());
            if (!(request.body() instanceof ProtocolBody.ResourceRequest body)
                || !handledRequests.add(request.messageId())) {
                continue;
            }
            ResourceOperation operation = body.operation();
            ProtocolEnvelope<Map<String, Object>> response = respond(request, operation);
            OperationTarget target = new OperationTarget(operation.kind(), request.resource());
            handledOperations.merge(target, 1, Integer::sum);
            handled++;
            if (response == null || consumeDrop(target)) {
                continue;
            }
            byte[] encoded = ENVELOPES.encodeBytes(response);
            ProtocolEnvelope<Map<String, Object>> roundTripped = ENVELOPES.decodeBytes(encoded);
            transport.receiveEnvelope(ENVELOPES.encodeBytes(roundTripped), sequence++);
        }
        return handled;
    }

    private ProtocolEnvelope<Map<String, Object>> respond(ProtocolEnvelope<Map<String, Object>> request,
                                                           ResourceOperation operation) {
        return switch (operation) {
            case ResourceLoadRequest load -> graphResource(load.resource()) ? load(request, load)
                : genericLoad(request, load);
            case ResourceSaveRequest<?> save -> graphResource(save.resource()) ? save(request, save)
                : genericSave(request, save);
            case ResourceCreateRequest<?> create -> graphResource(create.resource()) ? create(request, create)
                : genericCreate(request, create);
            case ResourceActivateRequest activate -> activate(request, activate);
            case ResourceListRequest list -> page(request, list.type(), ResourceOperationKind.LIST);
            case ResourceQueryRequest query -> page(request, query.type(), ResourceOperationKind.QUERY);
            default -> throw new IllegalArgumentException("Unsupported peer operation " + operation.kind());
        };
    }

    private String operationType(ResourceOperation operation) {
        return switch (operation) {
            case ResourceListRequest list -> list.type().canonicalText();
            case ResourceQueryRequest query -> query.type().canonicalText();
            default -> "catalog";
        };
    }

    private ProtocolEnvelope<Map<String, Object>> load(ProtocolEnvelope<Map<String, Object>> request,
                                                         ResourceLoadRequest load) {
        Stored current = requireStored(load.resource());
        ProtocolEnvelope<Map<String, Object>> response = response(request, ProtocolEnvelope.Kind.ACK,
            ProtocolEnvelope.Status.OK,
            ResourceOperationKind.LOAD, document(load.resource(), current), false);
        CoreGraphResourceProjection.decode(response, capabilityNames());
        return response;
    }

    private ProtocolEnvelope<Map<String, Object>> genericLoad(ProtocolEnvelope<Map<String, Object>> request,
                                                                ResourceLoadRequest load) {
        requireProjectMetadata(load.resource());
        ResourceDocument<Map<String, Object>> document = genericResources.get(load.resource());
        if (document == null) {
            throw new IllegalStateException("Missing peer resource " + load.resource());
        }
        return response(request, ProtocolEnvelope.Kind.ACK, ProtocolEnvelope.Status.OK,
            ResourceOperationKind.LOAD, document, false);
    }

    private ProtocolEnvelope<Map<String, Object>> save(ProtocolEnvelope<Map<String, Object>> request,
                                                        ResourceSaveRequest<?> untyped) {
        @SuppressWarnings("unchecked")
        ResourceSaveRequest<Map<String, Object>> save = (ResourceSaveRequest<Map<String, Object>>) untyped;
        ProtocolEnvelope<Map<String, Object>> replay = committedMutations.get(save.mutationId());
        if (replay != null) {
            return correlate(replay, request);
        }
        Stored current = requireStored(save.resource());
        if (save.expectedRevision() != current.revision()) {
            return conflict(request, document(save.resource(), current));
        }
        long revision = current.revision() + 1L;
        ResourceActivationState activation = activation(save.payload());
        ResourceDocument<Map<String, Object>> accepted = ResourceDocument.live(save.resource(), revision,
            save.mutationId(), save.canonicalPayload(), activation, "peer");
        ProtocolEnvelope<Map<String, Object>> response;
        if (consumeDivergence()) {
            Map<String, Object> divergent = asset(withRevision(current.payload(), revision + 1L), revision + 1L,
                save.mutationId(), activation);
            CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(divergent);
            ResourceDocument<Map<String, Object>> invalid = ResourceDocument.live(save.resource(), revision,
                save.mutationId(), canonical, activation, "peer");
            response = response(request, ProtocolEnvelope.Kind.ACK, ProtocolEnvelope.Status.OK,
                ResourceOperationKind.SAVE, invalid, false);
        } else {
            ProtocolEnvelope<Map<String, Object>> acceptedEnvelope = response(request, ProtocolEnvelope.Kind.ACK,
                ProtocolEnvelope.Status.OK, ResourceOperationKind.SAVE, accepted, false);
            CoreGraphResourceProjection.Projection projection = CoreGraphResourceProjection.decode(acceptedEnvelope,
                capabilityNames());
            resources.put(save.resource(), new Stored(projection.corePayload(), revision, save.mutationId(), activation));
            response = acceptedEnvelope;
        }
        committedMutations.put(save.mutationId(), response);
        return response;
    }

    private ProtocolEnvelope<Map<String, Object>> genericSave(ProtocolEnvelope<Map<String, Object>> request,
                                                                ResourceSaveRequest<?> untyped) {
        @SuppressWarnings("unchecked")
        ResourceSaveRequest<Map<String, Object>> save = (ResourceSaveRequest<Map<String, Object>>) untyped;
        requireProjectMetadata(save.resource());
        ProtocolEnvelope<Map<String, Object>> replay = committedMutations.get(save.mutationId());
        if (replay != null) {
            return correlate(replay, request);
        }
        ResourceDocument<Map<String, Object>> current = genericResources.get(save.resource());
        if (current == null) {
            throw new IllegalStateException("Missing peer resource " + save.resource());
        }
        if (save.expectedRevision() != current.revision()) {
            return conflict(request, current);
        }
        ResourceDocument<Map<String, Object>> accepted = ResourceDocument.live(save.resource(),
            current.revision() + 1L, save.mutationId(), save.canonicalPayload(), current.activationState(), "peer");
        ProtocolEnvelope<Map<String, Object>> response = response(request, ProtocolEnvelope.Kind.ACK,
            ProtocolEnvelope.Status.OK, ResourceOperationKind.SAVE, accepted, false);
        genericResources.put(save.resource(), accepted);
        committedMutations.put(save.mutationId(), response);
        return response;
    }

    private ProtocolEnvelope<Map<String, Object>> create(ProtocolEnvelope<Map<String, Object>> request,
                                                          ResourceCreateRequest<?> untyped) {
        @SuppressWarnings("unchecked")
        ResourceCreateRequest<Map<String, Object>> create = (ResourceCreateRequest<Map<String, Object>>) untyped;
        if (resources.containsKey(create.resource())) {
            return conflict(request, document(create.resource(), requireStored(create.resource())));
        }
        ResourceActivationState activation = activation(create.payload());
        ResourceDocument<Map<String, Object>> accepted = ResourceDocument.live(create.resource(), 1L,
            create.mutationId(), create.canonicalPayload(), activation, "peer");
        ProtocolEnvelope<Map<String, Object>> response = response(request, ProtocolEnvelope.Kind.ACK,
            ProtocolEnvelope.Status.OK, ResourceOperationKind.CREATE, accepted, false);
        CoreGraphResourceProjection.Projection projection = CoreGraphResourceProjection.decode(response, capabilityNames());
        resources.put(create.resource(), new Stored(projection.corePayload(), 1L, create.mutationId(), activation));
        committedMutations.put(create.mutationId(), response);
        return response;
    }

    private ProtocolEnvelope<Map<String, Object>> genericCreate(ProtocolEnvelope<Map<String, Object>> request,
                                                                  ResourceCreateRequest<?> untyped) {
        @SuppressWarnings("unchecked")
        ResourceCreateRequest<Map<String, Object>> create = (ResourceCreateRequest<Map<String, Object>>) untyped;
        requireProjectMetadata(create.resource());
        ProtocolEnvelope<Map<String, Object>> replay = committedMutations.get(create.mutationId());
        if (replay != null) {
            return correlate(replay, request);
        }
        ResourceDocument<Map<String, Object>> current = genericResources.get(create.resource());
        if (current != null) {
            return conflict(request, current);
        }
        ResourceDocument<Map<String, Object>> accepted = ResourceDocument.live(create.resource(), 1L,
            create.mutationId(), create.canonicalPayload(), ResourceActivationState.ACTIVE, "peer");
        ProtocolEnvelope<Map<String, Object>> response = response(request, ProtocolEnvelope.Kind.ACK,
            ProtocolEnvelope.Status.OK, ResourceOperationKind.CREATE, accepted, false);
        genericResources.put(create.resource(), accepted);
        committedMutations.put(create.mutationId(), response);
        return response;
    }

    private ProtocolEnvelope<Map<String, Object>> activate(ProtocolEnvelope<Map<String, Object>> request,
                                                            ResourceActivateRequest activate) {
        ProtocolEnvelope<Map<String, Object>> replay = committedMutations.get(activate.mutationId());
        if (replay != null) {
            return correlate(replay, request);
        }
        Stored current = requireStored(activate.resource());
        if (activate.expectedRevision() != current.revision()) {
            return conflict(request, document(activate.resource(), current));
        }
        long revision = current.revision() + 1L;
        Stored updated = new Stored(withRevision(current.payload(), revision), revision, activate.mutationId(),
            activate.targetState());
        resources.put(activate.resource(), updated);
        ProtocolEnvelope<Map<String, Object>> response = response(request, ProtocolEnvelope.Kind.ACK,
            ProtocolEnvelope.Status.OK, ResourceOperationKind.ACTIVATE, document(activate.resource(), updated), false);
        committedMutations.put(activate.mutationId(), response);
        return response;
    }

    private ProtocolEnvelope<Map<String, Object>> page(ProtocolEnvelope<Map<String, Object>> request,
                                                        ContractRef<ResourceTypeId> resourceType,
                                                        ResourceOperationKind operation) {
        ReSyncResourceType type = resourceType != null && OWNER.equals(resourceType.owner())
            ? ReSyncResourceType.byTypeId(resourceType.id().value()) : null;
        List<ResourceDocument<Map<String, Object>>> documents = List.of();
        if (type != null && type.isGraph()) {
            documents = resources.entrySet().stream()
                .filter(entry -> entry.getKey().type().equals(resourceType))
                .map(entry -> document(entry.getKey(), entry.getValue()))
                .toList();
        } else if (projectMetadata(resourceType)) {
            documents = genericResources.entrySet().stream()
                .filter(entry -> entry.getKey().type().equals(resourceType))
                .map(Map.Entry::getValue)
                .toList();
        }
        ResourcePage<Map<String, Object>> page = new ResourcePage<>(documents, null, true);
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.RESPONSE, request.contractVersion(), UUID.randomUUID(),
            request.requestId(), request.correlationId(), request.traceId(), server, null, 0L,
            request.authorityEpoch(), null,
            ContractRef.of(OWNER, OperationId.of("resource." + operation.name().toLowerCase())),
            request.capabilities(), ContractRef.of(OWNER, ResourceTypeId.of("resource.page")), null, null, false,
            null, null, null, null, null, request.sequence(), ProtocolEnvelope.Status.OK, List.of(), Map.of(),
            new ProtocolBody.ResourcePageResponse(operation, page));
    }

    private ProtocolEnvelope<Map<String, Object>> conflict(ProtocolEnvelope<Map<String, Object>> request,
                                                            ResourceDocument<Map<String, Object>> current) {
        return response(request, ProtocolEnvelope.Kind.CONFLICT, ProtocolEnvelope.Status.CONFLICT,
            requestOperation(request), current, true);
    }

    private ProtocolEnvelope<Map<String, Object>> response(ProtocolEnvelope<Map<String, Object>> request,
                                                            ProtocolEnvelope.Kind kind,
                                                            ProtocolEnvelope.Status status,
                                                            ResourceOperationKind operation,
                                                            ResourceDocument<Map<String, Object>> document,
                                                            boolean conflict) {
        ProtocolBody body = conflict
            ? new ProtocolBody.ConflictResponse(document.resource(), document)
            : new ProtocolBody.ResourceDocumentResponse(operation, document);
        return new ProtocolEnvelope<>(kind, ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION,
            UUID.randomUUID(), request.requestId(), request.correlationId(), request.traceId(), server,
            document.resource(), document.revision(), 1L, document.mutationId(),
            ContractRef.of(OWNER, OperationId.of("resource." + operation.name().toLowerCase())), CAPABILITIES,
            ContractRef.of(OWNER, ResourceTypeId.of("resource.document")), null, document.payloadHash(), false,
            null, null, null, null, null, 1L, status, List.of(), Map.of(), body);
    }

    private ProtocolEnvelope<Map<String, Object>> correlate(ProtocolEnvelope<Map<String, Object>> original,
                                                             ProtocolEnvelope<Map<String, Object>> request) {
        ResourceDocument<Map<String, Object>> document = typedDocument(
            (ProtocolBody.ResourceDocumentResponse) original.body());
        return response(request, original.kind(), original.status(), requestOperation(request), document, false);
    }

    private ResourceDocument<Map<String, Object>> typedDocument(ProtocolBody.ResourceDocumentResponse response) {
        ResourceDocument<?> candidate = response.document();
        if (!candidate.deleted() && !(candidate.payload() instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Core peer documents must contain object payloads");
        }
        @SuppressWarnings("unchecked")
        ResourceDocument<Map<String, Object>> document =
            (ResourceDocument<Map<String, Object>>) (ResourceDocument<?>) candidate;
        return document;
    }

    private ResourceOperationKind requestOperation(ProtocolEnvelope<Map<String, Object>> request) {
        return ((ProtocolBody.ResourceRequest) request.body()).operation().kind();
    }

    private ResourceDocument<Map<String, Object>> document(ServerResourceLocator resource, Stored stored) {
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            asset(stored.payload(), stored.revision(), stored.mutationId(), stored.activation()));
        return ResourceDocument.live(resource, stored.revision(), stored.mutationId(), canonical,
            stored.activation(), "peer");
    }

    private Map<String, Object> asset(Object payload, long revision, UUID mutationId,
                                      ResourceActivationState activation) {
        JsonValue.JsonObject core;
        String kind;
        String resourceType;
        if (payload instanceof GraphDocument graph) {
            core = GraphDocumentCodec.INSTANCE.encode(withRevision(graph, revision));
            kind = CoreGraphResourceProjection.GRAPH_DOCUMENT_KIND;
            resourceType = graph.resource().resourceType().value();
        } else if (payload instanceof FunctionSourceDocument source) {
            core = FunctionSourceDocumentCodec.INSTANCE.encode(withRevision(source, revision));
            kind = CoreGraphResourceProjection.FUNCTION_SOURCE_KIND;
            resourceType = source.graph().resource().resourceType().value();
        } else {
            throw new IllegalArgumentException("Unsupported Core payload " + payload);
        }
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put(CoreGraphResourceProjection.RESOURCE_TYPE, resourceType);
        fields.put(CoreGraphResourceProjection.ASSET_FORMAT_VERSION,
            CoreGraphResourceProjection.CURRENT_ASSET_FORMAT_VERSION);
        fields.put(CoreGraphResourceProjection.ASSET_REVISION, revision);
        fields.put(CoreGraphResourceProjection.ASSET_MUTATION_ID, mutationId.toString());
        fields.put(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE, activation.wireName());
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_KIND, kind);
        fields.put(CoreGraphResourceProjection.CORE_PAYLOAD_VERSION,
            CoreGraphResourceProjection.CURRENT_CORE_PAYLOAD_VERSION);
        core.fields().forEach((key, value) -> fields.put(key, value.toJava()));
        ContentHash hash = new ContentHash(CanonicalJson.sha256(CoreGraphResourceProjection.ASSET_HASH_DOMAIN, fields));
        fields.put(CoreGraphResourceProjection.ASSET_HASH, hash.canonicalText());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonValue.fromJava(fields).toJava();
        return new LinkedHashMap<>(result);
    }

    private ResourceActivationState activation(Map<String, Object> payload) {
        Object value = payload.get(CoreGraphResourceProjection.ASSET_ACTIVATION_STATE);
        return value instanceof String text ? ResourceActivationState.fromWireName(text) : ResourceActivationState.ACTIVE;
    }

    private Object withRevision(Object payload, long revision) {
        if (payload instanceof GraphDocument graph) {
            return withRevision(graph, revision);
        }
        if (payload instanceof FunctionSourceDocument source) {
            return withRevision(source, revision);
        }
        throw new IllegalArgumentException("Unsupported Core payload " + payload);
    }

    private GraphDocument withRevision(GraphDocument graph, long revision) {
        return new GraphDocument(graph.schemaVersion(), graph.resource(), revision, graph.catalogBinding(),
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.variables(), graph.functions(),
            graph.unknown());
    }

    private FunctionSourceDocument withRevision(FunctionSourceDocument source, long revision) {
        GraphDocument graph = withRevision(source.graph(), revision);
        FunctionSignature signature = new FunctionSignature(source.signature().function(), new FunctionRevision(revision),
            source.signature().inputs(), source.signature().outputs(), source.signature().unknown());
        return new FunctionSourceDocument(signature, graph, source.unknown());
    }

    private Stored requireStored(ServerResourceLocator resource) {
        Stored stored = resources.get(resource);
        if (stored == null) {
            throw new IllegalStateException("Missing peer resource " + resource);
        }
        return stored;
    }

    private boolean graphResource(ServerResourceLocator resource) {
        ReSyncResourceType type = resource != null
            ? ReSyncResourceType.byTypeId(resource.resourceType().value()) : null;
        return type != null && type.isGraph();
    }

    private void requireProjectMetadata(ServerResourceLocator resource) {
        if (resource == null || !OWNER.equals(resource.owner()) || !projectMetadata(resource.resourceType())) {
            throw new IllegalArgumentException("Unsupported generic peer resource " + resource);
        }
    }

    private boolean projectMetadata(ContractRef<ResourceTypeId> resourceType) {
        return resourceType != null && OWNER.equals(resourceType.owner()) && projectMetadata(resourceType.id());
    }

    private boolean projectMetadata(ResourceTypeId resourceType) {
        return resourceType != null && ReSyncResourceType.PROJECT_METADATA.typeId().equals(resourceType.value());
    }

    private ServerResourceLocator resource(ReSyncResourceType type, String id) {
        return new ServerResourceLocator(server, ContractRef.of(OWNER, ResourceTypeId.of(type.typeId())), id);
    }

    private boolean consumeDrop(OperationTarget target) {
        if (!target.equals(droppedResponse)) {
            return false;
        }
        droppedResponse = null;
        return true;
    }

    private boolean consumeDivergence() {
        boolean divergence = divergeNextResponse;
        divergeNextResponse = false;
        return divergence;
    }

    private Set<String> capabilityNames() {
        return Set.of("resources", "resource_activation");
    }

    private record Stored(Object payload, long revision, UUID mutationId, ResourceActivationState activation) {
    }

    private record OperationTarget(ResourceOperationKind operation, ServerResourceLocator resource) {
    }
}
