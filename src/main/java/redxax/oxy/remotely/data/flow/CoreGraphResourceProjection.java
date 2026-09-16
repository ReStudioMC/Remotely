package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceDocumentCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePage;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CoreGraphResourceProjection {
    public static final String RESOURCE_TYPE = "resourceType";
    public static final String ASSET_FORMAT_VERSION = "assetFormatVersion";
    public static final String ASSET_REVISION = "assetRevision";
    public static final String ASSET_MUTATION_ID = "assetMutationId";
    public static final String ASSET_ACTIVATION_STATE = "assetActivationState";
    public static final String ASSET_HASH = "assetHash";
    public static final String CORE_PAYLOAD_KIND = "corePayloadKind";
    public static final String CORE_PAYLOAD_VERSION = "corePayloadVersion";
    public static final int LEGACY_ASSET_FORMAT_VERSION = 3;
    public static final int CURRENT_ASSET_FORMAT_VERSION = 4;
    public static final int CURRENT_CORE_PAYLOAD_VERSION = 1;
    public static final String GRAPH_DOCUMENT_KIND = "graph-document";
    public static final String FUNCTION_SOURCE_KIND = "function-source";
    public static final String ASSET_HASH_DOMAIN = "asset-envelope";

    private static final String CORE_OWNER = "restudio.resync";
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        RESOURCE_TYPE, ASSET_FORMAT_VERSION, ASSET_REVISION, ASSET_MUTATION_ID, ASSET_ACTIVATION_STATE,
        ASSET_HASH, CORE_PAYLOAD_KIND, CORE_PAYLOAD_VERSION);
    private static final Set<String> LEGACY_ENVELOPE_FIELDS = Set.of(
        "contentHash", "formatVersion", "hash", "mutationId", "payloadHash", "payloadKind", "payloadVersion",
        "resourceHash", "resourceMutationId", "resourceRevision", "type", "id");
    private static final Set<String> GRAPH_ROOT_FIELDS = Set.of(
        "schemaVersion", "resource", "revision", "catalogBinding", "requiredCapabilities", "nodes", "connections",
        "variables", "functions");
    private static final Set<String> FUNCTION_ROOT_FIELDS = Set.of("signature", "graph");

    private CoreGraphResourceProjection() {
    }

    public static Projection project(ProtocolEnvelope<Map<String, Object>> envelope,
                                     Collection<String> supportedCapabilities) {
        Objects.requireNonNull(envelope, "Envelope is required");
        ReSyncProtocolEnvelopeProjection.Projection envelopeProjection =
            ReSyncProtocolEnvelopeProjection.project(envelope, supportedCapabilities);
        return project(envelopeProjection, supportedCapabilities);
    }

    public static Projection project(ProtocolEnvelope<Map<String, Object>> envelope,
                                     Collection<String> supportedCapabilities,
                                     CatalogBinding expectedBinding) {
        Objects.requireNonNull(envelope, "Envelope is required");
        ReSyncProtocolEnvelopeProjection.Projection envelopeProjection =
            ReSyncProtocolEnvelopeProjection.project(envelope, supportedCapabilities);
        return project(envelopeProjection, supportedCapabilities, expectedBinding, true);
    }

    public static Projection project(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                     Collection<String> supportedCapabilities) {
        Objects.requireNonNull(envelopeProjection, "Envelope projection is required");
        Set<String> supported = normalizeCapabilities(supportedCapabilities);
        ResourceDocument<Map<String, Object>> document = envelopeProjection.resourceDocument();
        return projectDocument(envelopeProjection, document, supported, null, false, true);
    }

    public static Projection project(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                     Collection<String> supportedCapabilities,
                                     CatalogBinding expectedBinding) {
        return project(envelopeProjection, supportedCapabilities, expectedBinding, true);
    }

    private static Projection project(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                      Collection<String> supportedCapabilities,
                                      CatalogBinding expectedBinding,
                                      boolean bindingRequired) {
        Objects.requireNonNull(envelopeProjection, "Envelope projection is required");
        Set<String> supported = normalizeCapabilities(supportedCapabilities);
        ResourceDocument<Map<String, Object>> document = envelopeProjection.resourceDocument();
        return projectDocument(envelopeProjection, document, supported, expectedBinding, bindingRequired, true);
    }

    public static PageProjection projectPage(ProtocolEnvelope<Map<String, Object>> envelope,
                                             Collection<String> supportedCapabilities) {
        Objects.requireNonNull(envelope, "Envelope is required");
        ReSyncProtocolEnvelopeProjection.Projection envelopeProjection =
            ReSyncProtocolEnvelopeProjection.project(envelope, supportedCapabilities);
        return projectPage(envelopeProjection, supportedCapabilities);
    }

    public static PageProjection projectPage(ProtocolEnvelope<Map<String, Object>> envelope,
                                             Collection<String> supportedCapabilities,
                                             CatalogBinding expectedBinding) {
        Objects.requireNonNull(envelope, "Envelope is required");
        ReSyncProtocolEnvelopeProjection.Projection envelopeProjection =
            ReSyncProtocolEnvelopeProjection.project(envelope, supportedCapabilities);
        return projectPage(envelopeProjection, supportedCapabilities, expectedBinding, true);
    }

    public static PageProjection projectResourcePage(ProtocolEnvelope<Map<String, Object>> envelope,
                                                     Collection<String> supportedCapabilities) {
        return projectPage(envelope, supportedCapabilities);
    }

    public static PageProjection projectPage(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                             Collection<String> supportedCapabilities) {
        return projectPage(envelopeProjection, supportedCapabilities, null, false);
    }

    public static PageProjection projectPage(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                             Collection<String> supportedCapabilities,
                                             CatalogBinding expectedBinding) {
        return projectPage(envelopeProjection, supportedCapabilities, expectedBinding, true);
    }

    private static PageProjection projectPage(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                              Collection<String> supportedCapabilities,
                                              CatalogBinding expectedBinding,
                                              boolean bindingRequired) {
        Objects.requireNonNull(envelopeProjection, "Envelope projection is required");
        if (!(envelopeProjection.envelope().body() instanceof ProtocolBody.ResourcePageResponse response)) {
            throw new IllegalArgumentException("The protocol envelope does not contain a resource page response.");
        }
        Set<String> supported = normalizeCapabilities(supportedCapabilities);
        List<ResourceDocument<Map<String, Object>>> documents = typedPageDocuments(response.page());
        List<Projection> projections = documents.stream()
            .map(document -> projectDocument(envelopeProjection, document, supported, expectedBinding,
                bindingRequired, false))
            .toList();
        return new PageProjection(envelopeProjection, response, projections);
    }

    public static PageProjection decodePage(ProtocolEnvelope<Map<String, Object>> envelope,
                                            Collection<String> supportedCapabilities) {
        PageProjection projection = projectPage(envelope, supportedCapabilities);
        if (!projection.accepted()) {
            throw new IllegalArgumentException(projection.rejectionReason());
        }
        return projection;
    }

    public static PageProjection decodeResourcePage(ProtocolEnvelope<Map<String, Object>> envelope,
                                                    Collection<String> supportedCapabilities) {
        return decodePage(envelope, supportedCapabilities);
    }

    public static Optional<PageProjection> openPage(ProtocolEnvelope<Map<String, Object>> envelope,
                                                    Collection<String> supportedCapabilities) {
        PageProjection projection = projectPage(envelope, supportedCapabilities);
        return projection.accepted() ? Optional.of(projection) : Optional.empty();
    }

    private static Projection projectDocument(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                              ResourceDocument<Map<String, Object>> document,
                                              Set<String> supportedCapabilities,
                                              CatalogBinding expectedBinding,
                                              boolean bindingRequired,
                                              boolean singleResource) {
        if (document == null) {
            return rejected(envelopeProjection, null, null, null, Set.of(),
                "The protocol envelope does not contain a resource document.");
        }
        try {
            validateOuterBoundary(envelopeProjection.envelope(), document, singleResource);
            validateResourceType(document.resource());
            if (document.deleted()) {
                return tombstone(envelopeProjection, document);
            }
            ParsedEnvelope parsed = parseEnvelope(document.payload());
            validateAssetBoundary(document, parsed);
            DecodedPayload decoded = decodePayload(parsed);
            validateDecodedIdentity(document.resource(), parsed, decoded);
            validateCanonicalEnvelope(parsed, decoded);
            CatalogBinding decodedBinding = decoded.graphDocument() != null
                ? decoded.graphDocument().catalogBinding() : decoded.functionSourceDocument().graph().catalogBinding();
            if (bindingRequired && (expectedBinding == null || !expectedBinding.equals(decodedBinding))) {
                return readOnly(envelopeProjection, document, parsed, decoded, Set.of(),
                    expectedBinding == null ? "No active authoring publication proves this graph catalog binding."
                        : "The graph catalog binding does not match the active authoring publication.");
            }
            Set<ContractRef<CapabilityId>> unsupported = unsupportedCapabilities(decoded.requiredCapabilities(), supportedCapabilities);
            if (envelopeProjection.hasUnsupportedCapabilities()) {
                LinkedHashSet<ContractRef<CapabilityId>> allUnsupported = new LinkedHashSet<>(unsupported);
                allUnsupported.addAll(envelopeProjection.unsupportedCapabilities());
                unsupported = Collections.unmodifiableSet(allUnsupported);
            }
            if (!unsupported.isEmpty()) {
                return readOnly(envelopeProjection, document, parsed, decoded, unsupported,
                    "The graph is decoded read-only because this client does not support all required capabilities.");
            }
            return live(envelopeProjection, document, parsed, decoded);
        } catch (RuntimeException exception) {
            return rejected(envelopeProjection, document, null, null, Set.of(), reason(exception));
        }
    }

    private static List<ResourceDocument<Map<String, Object>>> typedPageDocuments(ResourcePage<?> page) {
        List<ResourceDocument<Map<String, Object>>> documents = new ArrayList<>(page.items().size());
        for (ResourceDocument<?> candidate : page.items()) {
            if (!candidate.deleted() && !(candidate.payload() instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Core resource page documents must contain object payloads.");
            }
            @SuppressWarnings("unchecked")
            ResourceDocument<Map<String, Object>> document = (ResourceDocument<Map<String, Object>>) (ResourceDocument<?>) candidate;
            documents.add(document);
        }
        return List.copyOf(documents);
    }

    public static Projection projectGraph(ProtocolEnvelope<Map<String, Object>> envelope,
                                          Collection<String> supportedCapabilities) {
        return project(envelope, supportedCapabilities);
    }

    public static Projection decode(ProtocolEnvelope<Map<String, Object>> envelope,
                                    Collection<String> supportedCapabilities) {
        Projection projection = project(envelope, supportedCapabilities);
        if (!projection.accepted()) {
            throw new IllegalArgumentException(projection.rejectionReason());
        }
        return projection;
    }

    public static Optional<Projection> open(ProtocolEnvelope<Map<String, Object>> envelope,
                                            Collection<String> supportedCapabilities) {
        Projection projection = project(envelope, supportedCapabilities);
        return projection.accepted() ? Optional.of(projection) : Optional.empty();
    }

    public static Optional<Projection> open(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                            Collection<String> supportedCapabilities) {
        Projection projection = project(envelopeProjection, supportedCapabilities);
        return projection.accepted() ? Optional.of(projection) : Optional.empty();
    }

    private static Projection live(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                   ResourceDocument<Map<String, Object>> document, ParsedEnvelope parsed,
                                   DecodedPayload decoded) {
        return new Projection(envelopeProjection, document, Status.LIVE, decoded.graphDocument(),
            decoded.functionSourceDocument(), parsed.assetRevision(), parsed.assetMutationId(),
            parsed.assetActivationState(), parsed.assetHash(), parsed.assetFormatVersion(),
            parsed.corePayloadVersion(), parsed.corePayloadKind(), decoded.requiredCapabilities(), Set.of(), "");
    }

    private static Projection readOnly(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                       ResourceDocument<Map<String, Object>> document, ParsedEnvelope parsed,
                                       DecodedPayload decoded, Set<ContractRef<CapabilityId>> unsupported,
                                       String reason) {
        return new Projection(envelopeProjection, document, Status.READ_ONLY, decoded.graphDocument(),
            decoded.functionSourceDocument(), parsed.assetRevision(), parsed.assetMutationId(),
            parsed.assetActivationState(), parsed.assetHash(), parsed.assetFormatVersion(),
            parsed.corePayloadVersion(), parsed.corePayloadKind(), decoded.requiredCapabilities(), unsupported, reason);
    }

    private static Projection tombstone(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                        ResourceDocument<Map<String, Object>> document) {
        return new Projection(envelopeProjection, document, Status.TOMBSTONED, null, null, document.revision(),
            document.mutationId(), null, null, 0, 0, null, Set.of(), Set.of(), "");
    }

    private static Projection rejected(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                       ResourceDocument<Map<String, Object>> document, ParsedEnvelope parsed,
                                       DecodedPayload decoded, Set<ContractRef<CapabilityId>> unsupported,
                                       String reason) {
        return new Projection(envelopeProjection, document, Status.REJECTED,
            decoded != null ? decoded.graphDocument() : null,
            decoded != null ? decoded.functionSourceDocument() : null,
            parsed != null ? parsed.assetRevision() : document != null ? document.revision() : -1L,
            parsed != null ? parsed.assetMutationId() : document != null ? document.mutationId() : null,
            parsed != null ? parsed.assetActivationState() : null,
            parsed != null ? parsed.assetHash() : null,
            parsed != null ? parsed.assetFormatVersion() : 0,
            parsed != null ? parsed.corePayloadVersion() : 0,
            parsed != null ? parsed.corePayloadKind() : null,
            decoded != null ? decoded.requiredCapabilities() : Set.of(), unsupported, reason);
    }

    private static void validateOuterBoundary(ProtocolEnvelope<Map<String, Object>> envelope,
                                              ResourceDocument<Map<String, Object>> document,
                                              boolean singleResource) {
        if (document.revision() < 1L) {
            throw new IllegalArgumentException("Core resource document revision must be positive.");
        }
        if (singleResource) {
            if (envelope.resource() == null || !exactLocator(envelope.resource(), document.resource())) {
                throw new IllegalArgumentException("The protocol resource locator does not match the document locator.");
            }
            if (envelope.revision() != document.revision()) {
                throw new IllegalArgumentException("The protocol revision does not match the resource document.");
            }
            if (envelope.mutationId() == null || !envelope.mutationId().equals(document.mutationId())) {
                throw new IllegalArgumentException("The protocol mutation does not match the resource document.");
            }
            if (envelope.payloadHash() == null || !envelope.payloadHash().equals(document.payloadHash())) {
                throw new IllegalArgumentException("The protocol payload hash does not match the resource document.");
            }
            if (envelope.deleted() != document.deleted()) {
                throw new IllegalArgumentException("The protocol deletion state does not match the resource document.");
            }
        } else if (envelope.resource() != null || envelope.mutationId() != null || envelope.payloadHash() != null
            || envelope.deleted() || !envelope.serverId().equals(document.resource().serverId())) {
            throw new IllegalArgumentException("The resource page envelope does not match its document boundary.");
        }
        if (!envelope.serverId().equals(document.resource().serverId())) {
            throw new IllegalArgumentException("The protocol server does not match the resource document.");
        }
        if (!document.deleted()) {
            ContentHash expectedPayloadHash = ResourcePayloadCodecs.json().canonicalize(document.payload()).checksum();
            if (!expectedPayloadHash.equals(document.payloadHash())) {
                throw new IllegalArgumentException("The resource document payload hash is not canonical.");
            }
        }
    }

    private static void validateResourceType(ServerResourceLocator resource) {
        if (!CORE_OWNER.equals(resource.owner().canonicalText())) {
            throw new IllegalArgumentException("The graph resource owner is not authoritative.");
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(resource.resourceType().value());
        if (resourceType == null || !resourceType.isGraph()) {
            throw new IllegalArgumentException("The resource is not a supported Core graph type.");
        }
    }

    private static ParsedEnvelope parseEnvelope(Map<String, Object> payload) {
        JsonValue value = JsonValue.fromJava(Objects.requireNonNull(payload, "The resource payload is required"));
        JsonValue.JsonObject object = CanonicalCodec.requireObject(value);
        int assetFormatVersion = exactInt(object, ASSET_FORMAT_VERSION);
        if (assetFormatVersion != CURRENT_ASSET_FORMAT_VERSION) {
            if (assetFormatVersion == LEGACY_ASSET_FORMAT_VERSION) {
                throw new IllegalArgumentException("Core live asset format version 3 requires server migration to version 4.");
            }
            throw new IllegalArgumentException("Unsupported Core asset format version: " + assetFormatVersion);
        }
        String resourceType = text(object, RESOURCE_TYPE);
        long assetRevision = exactLong(object, ASSET_REVISION);
        if (assetRevision < 1L) {
            throw new IllegalArgumentException("Core asset revision must be positive.");
        }
        UUID assetMutationId = canonicalUuid(text(object, ASSET_MUTATION_ID), ASSET_MUTATION_ID);
        ResourceActivationState activationState = ResourceActivationState.fromWireName(text(object, ASSET_ACTIVATION_STATE));
        ContentHash assetHash = new ContentHash(text(object, ASSET_HASH));
        String corePayloadKind = text(object, CORE_PAYLOAD_KIND);
        int corePayloadVersion = exactInt(object, CORE_PAYLOAD_VERSION);
        if (corePayloadVersion != CURRENT_CORE_PAYLOAD_VERSION) {
            throw new IllegalArgumentException("Unsupported Core payload version: " + corePayloadVersion);
        }
        Map<String, JsonValue> coreFields = new LinkedHashMap<>(object.fields());
        ENVELOPE_FIELDS.forEach(coreFields::remove);
        JsonValue.JsonObject core = JsonValue.object(coreFields);
        rejectCoreEnvelopeFields(core, corePayloadKind);
        return new ParsedEnvelope(object, core, resourceType, assetFormatVersion, assetRevision, assetMutationId,
            activationState, assetHash, corePayloadKind, corePayloadVersion);
    }

    private static void validateAssetBoundary(ResourceDocument<Map<String, Object>> document, ParsedEnvelope parsed) {
        ServerResourceLocator resource = document.resource();
        if (!resource.resourceType().value().equals(parsed.resourceType())) {
            throw new IllegalArgumentException("The asset resource type does not match the typed locator.");
        }
        if (!GRAPH_DOCUMENT_KIND.equals(parsed.corePayloadKind()) && !FUNCTION_SOURCE_KIND.equals(parsed.corePayloadKind())) {
            throw new IllegalArgumentException("Unsupported Core payload kind: " + parsed.corePayloadKind());
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(parsed.resourceType());
        if (resourceType == null || !resourceType.acceptsCorePayloadKind(parsed.corePayloadKind())) {
            throw new IllegalArgumentException("The Core payload kind does not match the resource type.");
        }
        if (parsed.assetRevision() != document.revision()) {
            throw new IllegalArgumentException("The asset revision does not match the resource document.");
        }
        if (!parsed.assetMutationId().equals(document.mutationId())) {
            throw new IllegalArgumentException("The asset mutation does not match the resource document.");
        }
        if (document.activationState() != parsed.assetActivationState()) {
            throw new IllegalArgumentException("The asset activation state does not match the resource document.");
        }
        ContentHash expectedAssetHash = assetIntegrityHash(parsed.original());
        if (!expectedAssetHash.equals(parsed.assetHash())) {
            throw new IllegalArgumentException("The Core asset integrity hash does not match its canonical envelope.");
        }
    }

    private static DecodedPayload decodePayload(ParsedEnvelope parsed) {
        if (GRAPH_DOCUMENT_KIND.equals(parsed.corePayloadKind())) {
            GraphDocument graph = GraphDocumentCodec.INSTANCE.decode(parsed.core());
            return new DecodedPayload(graph, null, graph.requiredCapabilities());
        }
        FunctionSourceDocument source = FunctionSourceDocumentCodec.INSTANCE.decode(parsed.core());
        return new DecodedPayload(null, source, source.graph().requiredCapabilities());
    }

    private static void validateDecodedIdentity(ServerResourceLocator resource, ParsedEnvelope parsed,
                                                DecodedPayload decoded) {
        ServerResourceLocator payloadResource = decoded.graphDocument() != null
            ? decoded.graphDocument().resource() : decoded.functionSourceDocument().graph().resource();
        if (!exactLocator(resource, payloadResource)) {
            throw new IllegalArgumentException("The Core payload locator does not match the typed resource locator.");
        }
        long payloadRevision = decoded.graphDocument() != null
            ? decoded.graphDocument().revision() : decoded.functionSourceDocument().graph().revision();
        if (payloadRevision != parsed.assetRevision()) {
            throw new IllegalArgumentException("The Core payload revision does not match the asset revision.");
        }
        if (decoded.functionSourceDocument() != null
            && decoded.functionSourceDocument().signature().revision().value() != parsed.assetRevision()) {
            throw new IllegalArgumentException("The Function source revision does not match the asset revision.");
        }
    }

    private static void validateCanonicalEnvelope(ParsedEnvelope parsed, DecodedPayload decoded) {
        JsonValue.JsonObject core = decoded.graphDocument() != null
            ? GraphDocumentCodec.INSTANCE.encode(decoded.graphDocument())
            : FunctionSourceDocumentCodec.INSTANCE.encode(decoded.functionSourceDocument());
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put(RESOURCE_TYPE, JsonValue.of(parsed.resourceType()));
        fields.put(ASSET_FORMAT_VERSION, JsonValue.of(parsed.assetFormatVersion()));
        fields.put(ASSET_REVISION, JsonValue.of(parsed.assetRevision()));
        fields.put(ASSET_MUTATION_ID, JsonValue.of(parsed.assetMutationId().toString()));
        fields.put(ASSET_ACTIVATION_STATE, JsonValue.of(parsed.assetActivationState().wireName()));
        fields.put(CORE_PAYLOAD_KIND, JsonValue.of(parsed.corePayloadKind()));
        fields.put(CORE_PAYLOAD_VERSION, JsonValue.of(parsed.corePayloadVersion()));
        core.fields().forEach((key, value) -> {
            if (fields.put(key, value) != null) {
                throw new IllegalArgumentException("Core payload field collides with an asset envelope field: " + key);
            }
        });
        JsonValue.JsonObject withoutHash = JsonValue.object(fields);
        ContentHash expectedHash = new ContentHash(CanonicalJson.sha256Canonical(ASSET_HASH_DOMAIN,
            withoutHash.canonicalBytes()));
        fields.put(ASSET_HASH, JsonValue.of(expectedHash.canonicalText()));
        JsonValue.JsonObject canonical = JsonValue.object(fields);
        if (!canonical.canonicalText().equals(parsed.original().canonicalText())) {
            throw new IllegalArgumentException("The Core asset envelope is not in the exact canonical shape.");
        }
    }

    private static void rejectCoreEnvelopeFields(JsonValue.JsonObject core, String kind) {
        Set<String> known = GRAPH_DOCUMENT_KIND.equals(kind) ? GRAPH_ROOT_FIELDS : FUNCTION_ROOT_FIELDS;
        for (String field : core.fields().keySet()) {
            if (ENVELOPE_FIELDS.contains(field) || field.startsWith("asset") || field.startsWith("corePayload")) {
                throw new IllegalArgumentException("Core payload collides with an asset envelope field: " + field);
            }
            if (!known.contains(field) && LEGACY_ENVELOPE_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Legacy asset envelope field is not accepted: " + field);
            }
        }
    }

    private static ContentHash assetIntegrityHash(JsonValue.JsonObject object) {
        Map<String, JsonValue> fields = new LinkedHashMap<>(object.fields());
        if (fields.remove(ASSET_HASH) == null) {
            throw new IllegalArgumentException("The Core asset hash field is required.");
        }
        return new ContentHash(CanonicalJson.sha256Canonical(ASSET_HASH_DOMAIN, JsonValue.object(fields).canonicalBytes()));
    }

    private static boolean exactLocator(ServerResourceLocator first, ServerResourceLocator second) {
        return IdentityCodec.encodeLocator(first).canonicalText().equals(IdentityCodec.encodeLocator(second).canonicalText());
    }

    private static Set<ContractRef<CapabilityId>> unsupportedCapabilities(
        Collection<ContractRef<CapabilityId>> required, Set<String> supported) {
        LinkedHashSet<ContractRef<CapabilityId>> unsupported = new LinkedHashSet<>();
        if (required != null) {
            for (ContractRef<CapabilityId> capability : required) {
                if (!capabilitySupported(capability, supported)) {
                    unsupported.add(capability);
                }
            }
        }
        return Collections.unmodifiableSet(unsupported);
    }

    private static boolean capabilitySupported(ContractRef<CapabilityId> capability, Set<String> supported) {
        return supported.contains(capability.canonicalText())
            || CORE_OWNER.equals(capability.owner().canonicalText()) && supported.contains(capability.id().value());
    }

    private static Set<String> normalizeCapabilities(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static int exactInt(JsonValue.JsonObject object, String field) {
        JsonValue value = required(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Core asset field must be an integer: " + field);
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Core asset field must be an integer: " + field, exception);
        }
    }

    private static long exactLong(JsonValue.JsonObject object, String field) {
        JsonValue value = required(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Core asset field must be an integer: " + field);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Core asset field must be an integer: " + field, exception);
        }
    }

    private static String text(JsonValue.JsonObject object, String field) {
        JsonValue value = required(object, field);
        if (!(value instanceof JsonValue.JsonString text) || text.value().isBlank()) {
            throw new IllegalArgumentException("Core asset field must be non-blank text: " + field);
        }
        return text.value();
    }

    private static UUID canonicalUuid(String value, String field) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Core asset field must be a UUID: " + field, exception);
        }
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("Core asset field must be a canonical UUID: " + field);
        }
        return parsed;
    }

    private static JsonValue required(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required Core asset field is missing: " + field);
        }
        return value;
    }

    private static String reason(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "The Core graph resource could not be interpreted." : message;
    }

    private record ParsedEnvelope(JsonValue.JsonObject original, JsonValue.JsonObject core, String resourceType,
                                  int assetFormatVersion, long assetRevision, UUID assetMutationId,
                                  ResourceActivationState assetActivationState, ContentHash assetHash,
                                  String corePayloadKind, int corePayloadVersion) {
    }

    private record DecodedPayload(GraphDocument graphDocument, FunctionSourceDocument functionSourceDocument,
                                  Set<ContractRef<CapabilityId>> requiredCapabilities) {
        private DecodedPayload {
            if ((graphDocument == null) == (functionSourceDocument == null)) {
                throw new IllegalArgumentException("A Core projection must contain exactly one graph payload.");
            }
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }
    }

    public enum Status {
        LIVE,
        READ_ONLY,
        TOMBSTONED,
        REJECTED
    }

    public record Projection(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                             ResourceDocument<Map<String, Object>> resourceDocument,
                             Status status,
                             GraphDocument graphDocument,
                             FunctionSourceDocument functionSourceDocument,
                             long assetRevision,
                             UUID assetMutationId,
                             ResourceActivationState assetActivationState,
                             ContentHash assetHash,
                             int assetFormatVersion,
                             int corePayloadVersion,
                             String corePayloadKind,
                             Set<ContractRef<CapabilityId>> requiredCapabilities,
                             Set<ContractRef<CapabilityId>> unsupportedCapabilities,
                             String rejectionReason) {
        public Projection {
            envelopeProjection = Objects.requireNonNull(envelopeProjection, "Envelope projection is required");
            status = Objects.requireNonNull(status, "Projection status is required");
            requiredCapabilities = immutableReferences(requiredCapabilities);
            unsupportedCapabilities = immutableReferences(unsupportedCapabilities);
            rejectionReason = rejectionReason == null ? "" : rejectionReason;
            if (acceptedStatus(status) && (resourceDocument == null || resourceDocument.revision() < 1L)) {
                throw new IllegalArgumentException("Accepted Core projections require a positive resource revision.");
            }
            if ((status == Status.LIVE || status == Status.READ_ONLY) && (resourceDocument == null || resourceDocument.deleted()
                || (graphDocument == null) == (functionSourceDocument == null))) {
                throw new IllegalArgumentException("Decoded Core projections require exactly one live payload.");
            }
            if (status == Status.TOMBSTONED && (resourceDocument == null || !resourceDocument.deleted()
                || graphDocument != null || functionSourceDocument != null)) {
                throw new IllegalArgumentException("Tombstone projections cannot carry a payload.");
            }
        }

        public ProtocolEnvelope<Map<String, Object>> envelope() {
            return envelopeProjection.envelope();
        }

        public boolean accepted() {
            return status == Status.LIVE || status == Status.READ_ONLY || status == Status.TOMBSTONED;
        }

        public boolean live() {
            return status == Status.LIVE;
        }

        public boolean readOnly() {
            return status == Status.READ_ONLY;
        }

        public boolean tombstoned() {
            return status == Status.TOMBSTONED;
        }

        public boolean rejected() {
            return status == Status.REJECTED;
        }

        public boolean hasUnsupportedCapabilities() {
            return !unsupportedCapabilities.isEmpty();
        }

        public boolean opaque() {
            return !opaqueData().isEmpty();
        }

        public boolean hasGraphDocument() {
            return graphDocument != null;
        }

        public boolean hasFunctionSourceDocument() {
            return functionSourceDocument != null;
        }

        public Object corePayload() {
            return graphDocument != null ? graphDocument : functionSourceDocument;
        }

        public boolean deleted() {
            return resourceDocument != null && resourceDocument.deleted();
        }

        public ResourceActivationState activationState() {
            return assetActivationState;
        }

        public ServerResourceLocator resource() {
            return resourceDocument == null ? null : resourceDocument.resource();
        }

        public long revision() {
            return resourceDocument == null ? -1L : resourceDocument.revision();
        }

        public UUID mutationId() {
            return resourceDocument == null ? null : resourceDocument.mutationId();
        }

        public ContentHash payloadHash() {
            return resourceDocument == null ? null : resourceDocument.payloadHash();
        }

        public OpaqueData opaqueData() {
            if (graphDocument != null) {
                return graphDocument.unknown();
            }
            if (functionSourceDocument != null) {
                return functionSourceDocument.unknown();
            }
            return OpaqueData.empty();
        }

        private static Set<ContractRef<CapabilityId>> immutableReferences(
            Collection<ContractRef<CapabilityId>> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<ContractRef<CapabilityId>> copy = new LinkedHashSet<>();
            for (ContractRef<CapabilityId> value : values) {
                copy.add(Objects.requireNonNull(value, "Capability reference is required"));
            }
            return Collections.unmodifiableSet(copy);
        }

        private static boolean acceptedStatus(Status status) {
            return status == Status.LIVE || status == Status.READ_ONLY || status == Status.TOMBSTONED;
        }
    }

    public record PageProjection(ReSyncProtocolEnvelopeProjection.Projection envelopeProjection,
                                 ProtocolBody.ResourcePageResponse response,
                                 List<Projection> documents) {
        public PageProjection {
            envelopeProjection = Objects.requireNonNull(envelopeProjection, "Envelope projection is required");
            response = Objects.requireNonNull(response, "Resource page response is required");
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        public ProtocolEnvelope<Map<String, Object>> envelope() {
            return envelopeProjection.envelope();
        }

        public ResourcePage<?> page() {
            return response.page();
        }

        public ResourceOperationKind operation() {
            return response.operation();
        }

        public List<Projection> items() {
            return documents;
        }

        public String nextCursor() {
            return response.page().nextCursor();
        }

        public boolean complete() {
            return response.page().complete();
        }

        public boolean accepted() {
            return documents.stream().allMatch(Projection::accepted);
        }

        public boolean readOnly() {
            return documents.stream().anyMatch(Projection::readOnly);
        }

        public boolean hasUnsupportedCapabilities() {
            return documents.stream().anyMatch(Projection::hasUnsupportedCapabilities);
        }

        public Set<ContractRef<CapabilityId>> unsupportedCapabilities() {
            LinkedHashSet<ContractRef<CapabilityId>> unsupported = new LinkedHashSet<>();
            documents.forEach(document -> unsupported.addAll(document.unsupportedCapabilities()));
            return Collections.unmodifiableSet(unsupported);
        }

        public boolean hasRejectedDocuments() {
            return documents.stream().anyMatch(Projection::rejected);
        }

        public String rejectionReason() {
            return documents.stream().filter(Projection::rejected).map(Projection::rejectionReason)
                .findFirst().orElse("");
        }
    }
}
