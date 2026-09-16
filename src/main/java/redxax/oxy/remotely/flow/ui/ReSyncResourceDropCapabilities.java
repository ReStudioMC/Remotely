package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.flow.data.FlowResourceReference;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

public final class ReSyncResourceDropCapabilities {
    private static final String SERVER_OWNER = "server";
    private static final String BUILTIN_OWNER = "builtin";
    private static final Map<String, DropCatalog> ACTIVE_CATALOGS = BrowserSafeState.map();
    private static final Map<String, TypedCatalogBundle> ACTIVE_BUNDLES = BrowserSafeState.map();
    private static final Object PUBLICATION_LOCK = new Object();

    private ReSyncResourceDropCapabilities() {
    }

    static DropSpec forResource(ReSyncResourceDragPayload resource) {
        return resolve(resource, null).spec();
    }

    static DropSpec forResource(ReSyncResourceDragPayload resource, DropCatalog catalog) {
        return resolve(resource, catalog).spec();
    }

    static DropSpec forResource(ReSyncResourceDragPayload resource, DropCatalog catalog, String capability) {
        return resolve(resource, catalog, capability).spec();
    }

    static DropSpec forResource(ReSyncResourceDragPayload resource, DropCatalog catalog, Collection<String> capabilities) {
        return resolve(resource, catalog, capabilities).spec();
    }

    static DropResult resolvePublished(ReSyncResourceDragPayload resource, String serverId, long generation, String checksum) {
        return resolvePublished(resource, serverId, generation, checksum, "");
    }

    static DropResult resolvePublished(ReSyncResourceDragPayload resource, String serverId, long generation, String checksum,
                                       String projectionIdentity) {
        TypedCatalogBundle bundle;
        synchronized (PUBLICATION_LOCK) {
            bundle = serverId != null && !serverId.isBlank()
                ? ACTIVE_BUNDLES.get(normalizeServerId(serverId)) : null;
        }
        if (bundle != null && projectionIdentity != null && !projectionIdentity.isBlank()
            && !projectionIdentity.equals(bundle.key().projectionIdentity())) {
            return DropResult.unavailable(resource != null ? resource.type() : "", "catalog_mismatch",
                "the active drop catalog does not match the server catalog projection");
        }
        DropCatalog catalog = bundle != null ? bundle.dropCatalog() : null;
        return resolve(resource, catalog, serverId, generation, checksum);
    }

    public static Object publicationLock() {
        return PUBLICATION_LOCK;
    }

    static void clearPublishedDropState(String serverId) {
        if (serverId != null && !serverId.isBlank()) {
            String key = normalizeServerId(serverId);
            ACTIVE_CATALOGS.remove(key);
            ACTIVE_BUNDLES.remove(key);
        }
    }

    static DropResult resolve(ReSyncResourceDragPayload resource, DropCatalog catalog) {
        if (!validResource(resource)) {
            return DropResult.invalid("invalid_resource", "resource type and ID are required");
        }
        DropResult catalogState = catalogState(catalog, resource.type());
        if (catalogState != null) {
            return catalogState;
        }
        ResourceIdentity identity = resourceIdentity(resource);
        List<DropContribution> candidates = catalog.contributionsFor(identity.type(), identity.owner());
        if (candidates.isEmpty()) {
            return DropResult.unavailable(resource.type(), "missing_drop_target", "no server-published drop target is available");
        }
        return select(resource.type(), candidates);
    }

    static DropResult resolveTyped(ReSyncResourceDragPayload resource, ReSyncTypedInteractionProjection projection) {
        if (projection == null) {
            return resolve(resource, (DropCatalog) null);
        }
        DropCatalog catalog = DropCatalog.fromTypedProjection(projection);
        return resolve(resource, catalog, projection.key().serverId().canonicalText(),
            projection.key().catalogGeneration(), projection.key().snapshotChecksum().canonicalText());
    }

    static DropResult resolve(ReSyncResourceDragPayload resource, DropCatalog catalog, String serverId, long generation, String checksum) {
        if (!validResource(resource)) {
            return DropResult.invalid("invalid_resource", "resource type and ID are required");
        }
        if (catalog == null) {
            return DropResult.unavailable(resource.type(), "missing_drop_catalog", "no active server-scoped drop catalog is available");
        }
        if (!catalog.matches(serverId, generation, checksum)) {
            return DropResult.unavailable(resource.type(), "catalog_mismatch", "the active drop catalog does not match the server registry session");
        }
        return resolve(resource, catalog);
    }

    static DropResult resolve(ReSyncResourceDragPayload resource, DropCatalog catalog, String capability) {
        if (!validResource(resource)) {
            return DropResult.invalid("invalid_resource", "resource type and ID are required");
        }
        DropResult catalogState = catalogState(catalog, resource.type());
        if (catalogState != null) {
            return catalogState;
        }
        if (capability == null || capability.isBlank()) {
            return DropResult.unavailable(resource.type(), "missing_capability", "drop target capability is required");
        }
        ResourceIdentity identity = resourceIdentity(resource);
        List<DropContribution> candidates = catalog.contributionsFor(identity.type(), identity.owner()).stream()
            .filter(value -> capability.trim().equalsIgnoreCase(value.capability()))
            .toList();
        if (candidates.isEmpty()) {
            return DropResult.unavailable(resource.type(), "missing_capability", "server did not publish the requested drop target capability");
        }
        return select(resource.type(), candidates);
    }

    static DropResult resolve(ReSyncResourceDragPayload resource, DropCatalog catalog, Collection<String> capabilities) {
        if (!validResource(resource)) {
            return DropResult.invalid("invalid_resource", "resource type and ID are required");
        }
        DropResult catalogState = catalogState(catalog, resource.type());
        if (catalogState != null) {
            return catalogState;
        }
        if (capabilities == null || capabilities.isEmpty()) {
            return DropResult.unavailable(resource.type(), "missing_capability", "no drop target capabilities are available");
        }
        Set<String> requested = capabilities.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        ResourceIdentity identity = resourceIdentity(resource);
        List<DropContribution> candidates = catalog.contributionsFor(identity.type(), identity.owner()).stream()
            .filter(value -> requested.contains(value.capability()))
            .toList();
        if (candidates.isEmpty()) {
            return DropResult.unavailable(resource.type(), "missing_capability", "none of the requested drop target capabilities is published");
        }
        return select(resource.type(), candidates);
    }

    static DropPublicationResult publishCatalog(String serverId, long generation, String checksum,
                                                Collection<DropContribution> contributions) {
        DropCatalog catalog = DropCatalog.published(serverId, generation, checksum, contributions);
        if (!catalog.isValid()) {
            return DropPublicationResult.invalid(catalog);
        }
        return DropPublicationResult.rejected(catalog, "typed_bundle_required",
            "drop catalogs must be published with a function boundary catalog bundle");
    }

    public static PublicationOutcome publishTypedCatalog(String serverId, long generation, String checksum,
                                                          Collection<PublishedDropContribution> contributions) {
        List<DropContribution> translated = contributions == null ? null : contributions.stream()
            .map(ReSyncResourceDropCapabilities::translate)
            .toList();
        DropPublicationResult result = publishCatalog(serverId, generation, checksum, translated);
        return new PublicationOutcome(result.isAccepted(), result.code(), result.reason());
    }

    public static BundlePublicationOutcome publishTypedCatalogBundle(
        String serverId, long generation, String checksum, String projectionIdentity,
        Collection<PublishedDropContribution> contributions,
        FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog) {
        BundlePreparation preparation = prepareTypedCatalogBundle(serverId, generation, checksum, projectionIdentity,
            contributions, boundaryCatalog);
        if (!preparation.valid()) {
            return preparation.outcome();
        }
        synchronized (PUBLICATION_LOCK) {
            return evaluateTypedCatalogBundle(preparation, true);
        }
    }

    public static BundlePublicationOutcome validateTypedCatalogBundle(
        String serverId, long generation, String checksum, String projectionIdentity,
        Collection<PublishedDropContribution> contributions,
        FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog) {
        BundlePreparation preparation = prepareTypedCatalogBundle(serverId, generation, checksum, projectionIdentity,
            contributions, boundaryCatalog);
        if (!preparation.valid()) {
            return preparation.outcome();
        }
        synchronized (PUBLICATION_LOCK) {
            return evaluateTypedCatalogBundle(preparation, false);
        }
    }

    private static BundlePreparation prepareTypedCatalogBundle(
        String serverId, long generation, String checksum, String projectionIdentity,
        Collection<PublishedDropContribution> contributions,
        FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog) {
        CatalogPublicationKey key;
        try {
            key = new CatalogPublicationKey(serverId, generation, checksum, projectionIdentity);
        } catch (IllegalArgumentException exception) {
            return BundlePreparation.rejected("invalid_catalog_binding", exception.getMessage());
        }
        List<DropContribution> translated = contributions == null ? null : contributions.stream()
            .map(ReSyncResourceDropCapabilities::translate)
            .toList();
        DropCatalog dropCatalog = DropCatalog.published(key.serverId(), key.generation(), key.checksum(), translated);
        if (!dropCatalog.isValid()) {
            return BundlePreparation.rejected(dropCatalog.diagnosticCode(), dropCatalog.reason());
        }
        if (boundaryCatalog == null || !boundaryCatalog.isTypedProjectionAvailable()) {
            return BundlePreparation.rejected("unavailable_function_boundary_catalog",
                "the function boundary projection is unavailable or incomplete");
        }
        if (boundaryCatalog.isAuthoritative()
            && (boundaryCatalog.generation() != key.generation()
            || !boundaryCatalog.checksum().equals(key.checksum()))) {
            return BundlePreparation.rejected("catalog_projection_mismatch",
                "the function boundary projection does not match the catalog binding");
        }
        FlowNodeWidget.FunctionBoundaryCatalog authoritativeBoundary = boundaryCatalog.withAuthority(
            key.generation(), key.checksum());
        return BundlePreparation.valid(new TypedCatalogBundle(key, dropCatalog, authoritativeBoundary));
    }

    private static BundlePublicationOutcome evaluateTypedCatalogBundle(BundlePreparation preparation, boolean commit) {
        TypedCatalogBundle candidate = preparation.bundle();
        CatalogPublicationKey key = candidate.key();
        TypedCatalogBundle current = ACTIVE_BUNDLES.get(key.serverId());
        if (current != null) {
            int ordering = key.compareTo(current.key());
            if (ordering < 0) {
                return BundlePublicationOutcome.rejected("stale_catalog_bundle",
                    "catalog bundle generation is older than the active publication");
            }
            if (ordering == 0) {
                return current.key().equals(key)
                    ? BundlePublicationOutcome.accepted(current)
                    : BundlePublicationOutcome.rejected("conflicting_catalog_bundle",
                        "catalog bundle identity conflicts at the active generation");
            }
        }
        if (!commit) {
            return new BundlePublicationOutcome(true, "validated", "", candidate);
        }
        try {
            ACTIVE_CATALOGS.put(key.serverId(), candidate.dropCatalog());
            FlowNodeWidget.FunctionBoundaryCatalog.activate(key.serverId(), candidate.boundaryCatalog());
            ACTIVE_BUNDLES.put(key.serverId(), candidate);
        } catch (RuntimeException exception) {
            restorePublishedBundle(key.serverId(), current);
            throw exception;
        }
        return BundlePublicationOutcome.accepted(candidate);
    }

    public static boolean restoreTypedCatalogBundle(String serverId, TypedCatalogBundle bundle) {
        String key = serverId != null && !serverId.isBlank() ? normalizeServerId(serverId) : "";
        if (key.isBlank()) {
            return false;
        }
        synchronized (PUBLICATION_LOCK) {
            if (bundle == null) {
                clearPublishedDropState(key);
                FlowNodeWidget.FunctionBoundaryCatalog.deactivate(key);
                return true;
            }
            if (!key.equals(bundle.key().serverId())) {
                return false;
            }
            try {
                restorePublishedBundle(key, bundle);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    private static void restorePublishedBundle(String serverId, TypedCatalogBundle bundle) {
        if (bundle == null) {
            clearPublishedDropState(serverId);
            FlowNodeWidget.FunctionBoundaryCatalog.deactivate(serverId);
            return;
        }
        ACTIVE_CATALOGS.put(serverId, bundle.dropCatalog());
        FlowNodeWidget.FunctionBoundaryCatalog.activate(serverId, bundle.boundaryCatalog());
        ACTIVE_BUNDLES.put(serverId, bundle);
    }

    private record BundlePreparation(TypedCatalogBundle bundle, BundlePublicationOutcome outcome) {
        private static BundlePreparation valid(TypedCatalogBundle bundle) {
            return new BundlePreparation(bundle, null);
        }

        private static BundlePreparation rejected(String code, String reason) {
            return new BundlePreparation(null, BundlePublicationOutcome.rejected(code, reason));
        }

        private boolean valid() {
            return bundle != null;
        }
    }

    public static Optional<TypedCatalogBundle> activeTypedCatalogBundle(String serverId,
                                                                          long generation,
                                                                          String checksum,
                                                                          String projectionIdentity) {
        try {
            CatalogPublicationKey key = new CatalogPublicationKey(serverId, generation, checksum, projectionIdentity);
            synchronized (PUBLICATION_LOCK) {
                TypedCatalogBundle bundle = ACTIVE_BUNDLES.get(key.serverId());
                return bundle != null && bundle.key().equals(key) ? Optional.of(bundle) : Optional.empty();
            }
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Optional<TypedCatalogBundle> currentTypedCatalogBundle(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return Optional.empty();
        }
        synchronized (PUBLICATION_LOCK) {
            return Optional.ofNullable(ACTIVE_BUNDLES.get(normalizeServerId(serverId)));
        }
    }

    private static DropContribution translate(PublishedDropContribution contribution) {
        if (contribution == null) {
            return null;
        }
        if (!validPublishedText(contribution.resourceType())
            || !validPublishedText(contribution.resourceOwner())
            || !validPublishedText(contribution.capability())
            || !validPublishedText(contribution.owner())
            || !validPublishedText(contribution.nodeId())
            || !validPublishedText(contribution.referenceKind())
            || !validPublishedText(contribution.referenceOwner())
            || !validPublishedOptionalText(contribution.inputPin())) {
            return null;
        }
        try {
            return new DropContribution(contribution.resourceOwner(), contribution.resourceType(), contribution.capability(), contribution.owner(),
                contribution.nodeId(), contribution.inputPin(), contribution.referenceKind(), contribution.referenceOwner(),
                contribution.priority());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean validPublishedText(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim())
            && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validPublishedOptionalText(String value) {
        return value == null || (value.isBlank() && value.equals(value.trim())) || validPublishedText(value);
    }

    static DropPublicationResult publish(String serverId, long generation, String checksum,
                                         Collection<DropContribution> contributions) {
        return publishCatalog(serverId, generation, checksum, contributions);
    }

    static Optional<DropCatalog> activeCatalog(String serverId, long generation, String checksum) {
        if (serverId == null || serverId.isBlank() || generation < 0 || checksum == null || checksum.isBlank()) {
            return Optional.empty();
        }
        synchronized (PUBLICATION_LOCK) {
            DropCatalog catalog = ACTIVE_CATALOGS.get(normalizeServerId(serverId));
            return catalog != null && catalog.matches(serverId, generation, checksum) ? Optional.of(catalog) : Optional.empty();
        }
    }

    public static void clearCatalog(String serverId) {
        if (serverId != null && !serverId.isBlank()) {
            synchronized (PUBLICATION_LOCK) {
                clearPublishedDropState(serverId);
                FlowNodeWidget.FunctionBoundaryCatalog.remove(serverId);
            }
        }
    }

    public record CatalogPublicationKey(String serverId, long generation, String checksum, String projectionIdentity)
        implements Comparable<CatalogPublicationKey> {
        public CatalogPublicationKey {
            serverId = required(serverId, "server identity");
            if (generation < 0L) {
                throw new IllegalArgumentException("catalog generation cannot be negative");
            }
            checksum = required(checksum, "catalog checksum");
            projectionIdentity = required(projectionIdentity, "catalog projection identity");
        }

        @Override
        public int compareTo(CatalogPublicationKey other) {
            return Long.compare(generation, other.generation);
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank() || !value.equals(value.trim())) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
    }

    public record TypedCatalogBundle(CatalogPublicationKey key, DropCatalog dropCatalog,
                                     FlowNodeWidget.FunctionBoundaryCatalog boundaryCatalog) {
        public TypedCatalogBundle {
            key = Objects.requireNonNull(key, "catalog publication key");
            dropCatalog = Objects.requireNonNull(dropCatalog, "drop catalog");
            boundaryCatalog = Objects.requireNonNull(boundaryCatalog, "boundary catalog");
        }
    }

    public record BundlePublicationOutcome(boolean accepted, String code, String reason,
                                           TypedCatalogBundle bundle) {
        public BundlePublicationOutcome {
            code = code != null ? code.trim() : "";
            reason = reason != null ? reason.trim() : "";
        }

        static BundlePublicationOutcome accepted(TypedCatalogBundle bundle) {
            return new BundlePublicationOutcome(true, "published", "", bundle);
        }

        static BundlePublicationOutcome rejected(String code, String reason) {
            return new BundlePublicationOutcome(false, code, reason, null);
        }
    }

    private static DropResult catalogState(DropCatalog catalog, String resourceType) {
        if (catalog == null) {
            return DropResult.unavailable(resourceType, "missing_drop_catalog", "no active server-scoped drop catalog is available");
        }
        if (!catalog.isValid()) {
            return DropResult.invalid(catalog.diagnosticCode(), catalog.reason());
        }
        return null;
    }

    private static DropResult select(String resourceType, List<DropContribution> candidates) {
        DropContribution selected = candidates.getFirst();
        int priority = selected.priority();
        if (candidates.stream().skip(1).anyMatch(value -> value.priority() == priority)) {
            return DropResult.unavailable(resourceType, "ambiguous_drop_target",
                "multiple published drop targets have equal priority for this resource type");
        }
        return DropResult.available(selected, selected.resourceType());
    }

    private static boolean validResource(ReSyncResourceDragPayload resource) {
        return resource != null && resource.type() != null && !resource.type().isBlank()
            && validResourceId(resource.id());
    }

    private static boolean validResourceId(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim())
            && value.chars().noneMatch(Character::isISOControl);
    }

    private static String normalizeServerId(String value) {
        return value.trim();
    }

    enum Availability {
        AVAILABLE,
        UNAVAILABLE,
        INVALID
    }

    enum PublicationStatus {
        ACTIVE,
        REJECTED,
        INVALID
    }

    public record PublicationOutcome(boolean accepted, String code, String reason) {
        public PublicationOutcome {
            code = code != null ? code.trim() : "";
            reason = reason != null ? reason.trim() : "";
        }
    }

    public record PublishedDropContribution(String resourceType, String resourceOwner, String capability, String owner,
                                            String nodeId, String inputPin, String referenceKind, String referenceOwner, int priority) {
        public PublishedDropContribution(String resourceType, String capability, String owner, String nodeId,
                                         String inputPin, String referenceKind, String referenceOwner, int priority) {
            this(resourceType, BUILTIN_OWNER, capability, owner, nodeId, inputPin, referenceKind, referenceOwner, priority);
        }
    }

    record DropPublicationResult(PublicationStatus status, DropCatalog catalog, String code, String reason) {
        DropPublicationResult {
            status = Objects.requireNonNull(status, "publication status");
            catalog = Objects.requireNonNull(catalog, "publication catalog");
            code = code != null ? code.trim() : "";
            reason = reason != null ? reason.trim() : "";
            if (status == PublicationStatus.INVALID && reason.isBlank()) {
                throw new IllegalArgumentException("invalid publications require a reason");
            }
        }

        static DropPublicationResult accepted(DropCatalog catalog) {
            return new DropPublicationResult(PublicationStatus.ACTIVE, catalog, "published", "");
        }

        static DropPublicationResult invalid(DropCatalog catalog) {
            return new DropPublicationResult(PublicationStatus.INVALID, catalog, catalog.diagnosticCode(), catalog.reason());
        }

        static DropPublicationResult rejected(DropCatalog catalog, String code, String reason) {
            return new DropPublicationResult(PublicationStatus.REJECTED, catalog, code, reason);
        }

        boolean isAccepted() {
            return status == PublicationStatus.ACTIVE;
        }

        boolean isInvalid() {
            return status == PublicationStatus.INVALID;
        }

        boolean isRejected() {
            return status == PublicationStatus.REJECTED;
        }
    }

    record DropResult(DropSpec spec, String code) {
        DropResult {
            spec = Objects.requireNonNull(spec, "drop result spec");
            code = code != null ? code.trim() : "";
            if (!spec.isAvailable() && code.isBlank()) {
                throw new IllegalArgumentException("unavailable results require a diagnostic code");
            }
        }

        static DropResult available(DropContribution contribution, String resourceType) {
            return new DropResult(DropSpec.available(contribution, resourceType), "available");
        }

        static DropResult unavailable(String resourceType, String code, String reason) {
            return new DropResult(DropSpec.unavailable(resourceType, reason), code);
        }

        static DropResult invalid(String code, String reason) {
            return new DropResult(DropSpec.invalid(reason), code);
        }

        Availability status() {
            return spec.availability();
        }

        boolean isAvailable() {
            return status() == Availability.AVAILABLE;
        }

        boolean isUnavailable() {
            return status() == Availability.UNAVAILABLE;
        }

        boolean isInvalid() {
            return status() == Availability.INVALID;
        }

        String reason() {
            return spec.reason();
        }
    }

    record DropKey(String resourceOwner, String resourceType, String targetOwner, String capability) {
        DropKey {
            resourceOwner = required(resourceOwner, "resource owner");
            resourceType = required(resourceType, "resource type");
            targetOwner = required(targetOwner, "target owner");
            capability = required(capability, "drop target capability");
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }

    record ResourceIdentity(String owner, String type) {
        ResourceIdentity {
            owner = required(owner, "resource owner");
            type = required(type, "resource type");
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }

    record DropTarget(String owner, String nodeId) {
        DropTarget {
            ContractRef<NodeId> identity = typedIdentity(owner, nodeId);
            if (identity == null) {
                throw new IllegalArgumentException("drop target identity is invalid");
            }
            owner = identity.owner().canonicalText();
            nodeId = identity.id().canonicalText();
        }

        public String canonical() {
            return owner + ":" + nodeId;
        }

        ContractRef<NodeId> typedIdentity() {
            return ContractRef.of(new OwnerId(owner), new NodeId(nodeId));
        }

        private static ContractRef<NodeId> typedIdentity(String owner, String nodeId) {
            if (owner == null || nodeId == null || owner.isBlank() || nodeId.isBlank()
                || !owner.equals(owner.trim()) || !nodeId.equals(nodeId.trim())) {
                return null;
            }
            int separator = nodeId.indexOf(':');
            if (separator >= 0) {
                if (separator == 0 || separator == nodeId.length() - 1
                    || nodeId.indexOf(':', separator + 1) >= 0
                    || !owner.equals(nodeId.substring(0, separator))) {
                    return null;
                }
                nodeId = nodeId.substring(separator + 1);
            }
            try {
                return ContractRef.of(new OwnerId(owner), new NodeId(nodeId));
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }

    record DropContribution(DropKey key, DropTarget target, String inputPin, String referenceKind, String referenceOwner, int priority) {
        DropContribution {
            key = Objects.requireNonNull(key, "drop key");
            target = Objects.requireNonNull(target, "drop target");
            if (!key.targetOwner().equalsIgnoreCase(target.owner())) {
                throw new IllegalArgumentException("drop key target owner does not match the target");
            }
            inputPin = normalizeOptional(inputPin);
            referenceKind = referenceKind == null || referenceKind.isBlank() ? key.resourceType() : referenceKind.trim();
            referenceOwner = referenceOwner == null || referenceOwner.isBlank() ? SERVER_OWNER : referenceOwner.trim();
        }

        DropContribution(String resourceType, String capability, String owner, String nodeId, String inputPin) {
            this(BUILTIN_OWNER, resourceType, capability, owner, nodeId, inputPin, resourceType, SERVER_OWNER, 0);
        }

        DropContribution(String resourceType, String capability, String owner, String nodeId, String inputPin, String referenceKind) {
            this(BUILTIN_OWNER, resourceType, capability, owner, nodeId, inputPin, referenceKind, SERVER_OWNER, 0);
        }

        DropContribution(String resourceType, String capability, String owner, String nodeId, String inputPin,
                         String referenceKind, String referenceOwner, int priority) {
            this(BUILTIN_OWNER, resourceType, capability, owner, nodeId, inputPin, referenceKind, referenceOwner, priority);
        }

        DropContribution(String resourceOwner, String resourceType, String capability, String owner, String nodeId,
                         String inputPin, String referenceKind, String referenceOwner, int priority) {
            this(new DropKey(resourceOwner, resourceType, owner, capability), new DropTarget(owner, nodeId), inputPin,
                referenceKind, referenceOwner, priority);
        }

        DropContribution(String resourceType, String capability, DropTarget target, String inputPin) {
            this(BUILTIN_OWNER, resourceType, capability, target.owner(), target.nodeId(), inputPin, resourceType, SERVER_OWNER, 0);
        }

        String resourceType() {
            return key.resourceType();
        }

        String capability() {
            return key.capability();
        }

        String resourceOwner() {
            return key.resourceOwner();
        }

        private static String normalizeOptional(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public static final class DropCatalog {
        private static final DropCatalog EMPTY = new DropCatalog("", -1, "", List.of());
        private final String serverId;
        private final long generation;
        private final String checksum;
        private final PublicationStatus status;
        private final String diagnosticCode;
        private final String reason;
        private final Map<DropKey, DropContribution> byKey;
        private final Map<ResourceIdentity, List<DropContribution>> byResource;

        DropCatalog(Collection<DropContribution> contributions) {
            this("", -1, "", contributions);
        }

        DropCatalog(DropContribution... contributions) {
            this("", -1, "", contributions != null ? List.of(contributions) : null);
        }

        private DropCatalog(String serverId, long generation, String checksum, Collection<DropContribution> contributions) {
            this.serverId = serverId != null && !serverId.isBlank() ? normalizeServerId(serverId) : "";
            this.generation = generation;
            this.checksum = checksum != null ? checksum.trim() : "";
            Map<DropKey, DropContribution> indexed = new LinkedHashMap<>();
            String issueCode = "";
            String issueReason = "";
            if (this.serverId.isBlank() && (generation >= 0 || !this.checksum.isBlank())) {
                issueCode = "invalid_catalog_server";
                issueReason = "server identity is required for a published drop catalog";
            } else if (!this.serverId.isBlank() && generation < 0) {
                issueCode = "invalid_catalog_generation";
                issueReason = "drop catalog generation cannot be less than zero";
            } else if (!this.serverId.isBlank() && this.checksum.isBlank()) {
                issueCode = "invalid_catalog_checksum";
                issueReason = "checksum is required for a published drop catalog";
            } else if (contributions == null) {
                issueCode = "missing_catalog_contributions";
                issueReason = "drop contribution list is required";
            } else {
                for (DropContribution contribution : contributions) {
                    if (contribution == null) {
                        issueCode = "invalid_drop_contribution";
                        issueReason = "drop catalog contains a null contribution";
                        continue;
                    }
                    if (indexed.putIfAbsent(contribution.key(), contribution) != null && issueReason.isBlank()) {
                        issueCode = "duplicate_drop_contribution";
                         issueReason = "drop catalog contains duplicate resource owner, type, target owner, and capability contributions";
                    }
                }
            }
            this.status = issueReason.isBlank() ? PublicationStatus.ACTIVE : PublicationStatus.INVALID;
            this.diagnosticCode = issueCode.isBlank() ? "published" : issueCode;
            this.reason = issueReason;
            this.byKey = Map.copyOf(indexed);
            Map<ResourceIdentity, List<DropContribution>> grouped = new LinkedHashMap<>();
            for (DropContribution contribution : indexed.values()) {
                ResourceIdentity identity = new ResourceIdentity(contribution.resourceOwner(), contribution.resourceType());
                grouped.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(contribution);
            }
            grouped.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparingInt(DropContribution::priority).reversed().thenComparing(DropContribution::capability))
                .toList());
            this.byResource = Map.copyOf(grouped);
        }

        static DropCatalog empty() {
            return EMPTY;
        }

        static DropCatalog published(String serverId, long generation, String checksum, Collection<DropContribution> contributions) {
            return new DropCatalog(serverId, generation, checksum, contributions);
        }

        static DropCatalog of(Collection<DropContribution> contributions) {
            return new DropCatalog(contributions);
        }

        static DropCatalog of(DropContribution... contributions) {
            return new DropCatalog(contributions);
        }

        static DropCatalog fromTypedProjection(ReSyncTypedInteractionProjection projection) {
            if (projection == null) {
                return empty();
            }
            List<DropContribution> contributions = projection.allDropContributions().stream()
                .map(value -> {
                    ContractRef<NodeId> target = value.target();
                    try {
                        return new DropContribution(value.resourceOwner(), value.resourceType(), value.capability(),
                            target.owner().canonicalText(), target.id().canonicalText(), value.inputPin(),
                            value.referenceKind(), value.referenceOwner(), value.priority());
                    } catch (RuntimeException exception) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
            return published(projection.key().serverId().canonicalText(), projection.key().catalogGeneration(),
                projection.key().snapshotChecksum().canonicalText(), contributions);
        }

        String serverId() {
            return serverId;
        }

        long generation() {
            return generation;
        }

        String checksum() {
            return checksum;
        }

        PublicationStatus status() {
            return status;
        }

        String diagnosticCode() {
            return diagnosticCode;
        }

        String reason() {
            return reason;
        }

        boolean isValid() {
            return status == PublicationStatus.ACTIVE;
        }

        boolean matches(String expectedServerId, long expectedGeneration, String expectedChecksum) {
            return isValid() && !serverId.isBlank() && generation >= 0 && !checksum.isBlank()
                && expectedServerId != null && !expectedServerId.isBlank()
                && serverId.equals(normalizeServerId(expectedServerId))
                && generation == expectedGeneration
                && checksum.equals(expectedChecksum != null ? expectedChecksum.trim() : "");
        }

        Optional<DropContribution> find(String resourceType, String capability) {
            if (resourceType == null || resourceType.isBlank() || capability == null || capability.isBlank()) {
                return Optional.empty();
            }
            List<DropContribution> matches = contributionsFor(resourceType, BUILTIN_OWNER).stream()
                .filter(value -> capability.equalsIgnoreCase(value.capability()))
                .toList();
            return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
        }

        List<DropContribution> contributionsFor(String resourceType) {
            return contributionsFor(resourceType, BUILTIN_OWNER);
        }

        List<DropContribution> contributionsFor(String resourceType, String resourceOwner) {
            if (resourceType == null || resourceType.isBlank() || resourceOwner == null || resourceOwner.isBlank()) {
                return List.of();
            }
            return byResource.getOrDefault(new ResourceIdentity(resourceOwner, resourceType), List.of());
        }

        List<DropContribution> contributions() {
            return List.copyOf(byKey.values());
        }
    }

    private static ResourceIdentity resourceIdentity(ReSyncResourceDragPayload resource) {
        String value = resource != null ? resource.type() : "";
        int separator = value != null ? value.indexOf(':') : -1;
        if (separator > 0 && separator < value.length() - 1) {
            return new ResourceIdentity(value.substring(0, separator), value.substring(separator + 1));
        }
        return new ResourceIdentity(BUILTIN_OWNER, value);
    }

    record DropSpec(Availability availability, String resourceType, String resourceOwner, DropTarget target,
                    String capability, String inputPin, String referenceKind, String referenceOwner, String reason) {
        DropSpec {
            availability = Objects.requireNonNull(availability, "drop availability");
            resourceType = resourceType != null ? resourceType.trim() : "";
            resourceOwner = resourceOwner != null ? resourceOwner.trim() : "";
            capability = capability != null ? capability.trim() : "";
            inputPin = inputPin != null ? inputPin.trim() : null;
            referenceKind = referenceKind != null ? referenceKind.trim() : "";
            referenceOwner = referenceOwner != null ? referenceOwner.trim() : "";
            reason = reason != null ? reason.trim() : "";
            if (availability == Availability.AVAILABLE) {
                Objects.requireNonNull(target, "available drop target");
                if (resourceType.isBlank() || resourceOwner.isBlank() || capability.isBlank()) {
                    throw new IllegalArgumentException("available drops require resource owner, resource type, and capability");
                }
            } else if (target != null) {
                throw new IllegalArgumentException("unavailable drops cannot expose a target");
            } else if (reason.isBlank()) {
                throw new IllegalArgumentException("unavailable drops require a reason");
            }
        }

        static DropSpec available(DropContribution contribution, String resourceType) {
            return new DropSpec(Availability.AVAILABLE, resourceType, contribution.resourceOwner(), contribution.target(), contribution.capability(),
                contribution.inputPin(), contribution.referenceKind(), contribution.referenceOwner(), "");
        }

        static DropSpec unavailable(String resourceType, String reason) {
            return new DropSpec(Availability.UNAVAILABLE, resourceType, "", null, "", null, "", "", reason);
        }

        static DropSpec invalid(String reason) {
            return new DropSpec(Availability.INVALID, "", "", null, "", null, "", "", reason);
        }

        String nodeType() {
            return target != null ? target.canonical() : null;
        }

        boolean isAvailable() {
            return availability == Availability.AVAILABLE;
        }

        boolean isUnavailable() {
            return availability == Availability.UNAVAILABLE;
        }

        Map<String, Object> inputValues(String resourceId) {
            if (!isAvailable() || !validResourceId(resourceId) || inputPin == null || inputPin.isBlank()) {
                return Map.of();
            }
            FlowResourceReference value = new FlowResourceReference(
                referenceKind.isBlank() ? resourceType : referenceKind,
                resourceId,
                resourceOwner.isBlank() ? SERVER_OWNER : resourceOwner);
            value.setMetadata(Map.of(
                "dropResourceOwner", resourceOwner,
                "dropTargetOwner", target.owner(),
                "dropTargetNodeId", target.nodeId(),
                "dropCapability", capability));
            return Map.of(inputPin, value);
        }
    }
}
