package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ReSyncTypedInteractionProjection {
    private final CatalogCacheKey key;
    private final long revision;
    private final Map<ContractRef<NodeId>, ReSyncGenericDescriptorProjection.Projection> descriptors;
    private final Map<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> widgetDefinitions;
    private final Map<String, ReSyncGenericWidgetCapabilities.WidgetDefinition> uniqueWidgetDefinitions;
    private final Map<String, ReSyncGenericWidgetCapabilities.WidgetDefinition> uniqueWorldGenWidgetDefinitions;
    private final Map<String, NodeDefinition> worldGenDefinitions;
    private final Map<ContractRef<NodeId>, FunctionBoundary> functionBoundaries;
    private final Map<ContractRef<NodeId>, List<DropContribution>> dropContributions;
    private final List<DropContribution> allDropContributions;
    private final Map<DropSemanticKey, DropAuthoring> dropAuthoring;
    private final Palette palette;
    private final Palette worldGenPalette;
    private final Map<String, List<String>> conversions;

    private ReSyncTypedInteractionProjection(CatalogCacheKey key,
                                              long revision,
                                              Map<ContractRef<NodeId>, ReSyncGenericDescriptorProjection.Projection> descriptors,
                                              Map<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> widgets,
                                              Map<ContractRef<NodeId>, FunctionBoundary> functionBoundaries,
                                              Map<ContractRef<NodeId>, List<DropContribution>> dropContributions,
                                              Set<ContractRef<NodeId>> activeDefinitions,
                                              CatalogAuthoringPublication authoring) {
        this.key = Objects.requireNonNull(key, "Catalog cache key is required");
        this.revision = revision;
        this.descriptors = immutableMap(descriptors);
        this.widgetDefinitions = immutableMap(widgets);
        this.uniqueWidgetDefinitions = uniqueWidgetDefinitions(this.widgetDefinitions, false);
        this.uniqueWorldGenWidgetDefinitions = uniqueWidgetDefinitions(this.widgetDefinitions, true);
        this.worldGenDefinitions = worldGenDefinitions(this.widgetDefinitions);
        this.functionBoundaries = immutableMap(functionBoundaries);
        LinkedHashMap<ContractRef<NodeId>, List<DropContribution>> drops = new LinkedHashMap<>();
        LinkedHashMap<DropSemanticKey, DropAuthoring> admittedDropAuthoring = new LinkedHashMap<>();
        if (dropContributions != null) {
            dropContributions.forEach((identity, values) -> {
                if (identity != null && values != null && !values.isEmpty()) {
                    for (DropContribution contribution : values) {
                        DropAuthoring admitted = admitDrop(contribution, this.descriptors, this.widgetDefinitions,
                            activeDefinitions);
                        if (admitted != null) {
                            drops.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(contribution);
                            admittedDropAuthoring.put(DropSemanticKey.of(contribution), admitted);
                        }
                    }
                }
            });
        }
        drops.replaceAll((ignored, values) -> List.copyOf(values));
        this.dropContributions = Collections.unmodifiableMap(drops);
        this.allDropContributions = drops.values().stream().flatMap(Collection::stream).toList();
        this.dropAuthoring = Collections.unmodifiableMap(admittedDropAuthoring);
        this.palette = Palette.from(key, revision, this.descriptors, this.widgetDefinitions);
        this.worldGenPalette = this.palette.worldGen();
        this.conversions = conversionIndex(authoring);
    }

    public static Optional<ReSyncTypedInteractionProjection> from(ReSyncFlowClient client) {
        return client == null ? Optional.empty() : client.typedInteractionProjection();
    }

    public static ReSyncTypedInteractionProjection from(ReSyncCatalogPublicationProjection.Snapshot snapshot) {
        return from(snapshot, null);
    }

    public static ReSyncTypedInteractionProjection from(ReSyncCatalogPublicationProjection.Snapshot snapshot,
                                                         CatalogAuthoringPublication authoring) {
        Builder builder = begin(snapshot, authoring);
        boolean ready;
        do {
            ready = builder.advance(16);
        } while (!ready);
        return builder.finish();
    }

    public static Builder begin(ReSyncCatalogPublicationProjection.Snapshot snapshot,
                                CatalogAuthoringPublication authoring) {
        return new Builder(snapshot, authoring);
    }

    public static final class Builder {
        private final ReSyncCatalogPublicationProjection.Snapshot snapshot;
        private final CatalogAuthoringPublication authoring;
        private final CatalogCacheKey catalogKey;
        private final long startedAt;
        private final Iterator<CatalogCachePublication.Entry> entries;
        private final Map<ContractRef<NodeId>, ReSyncGenericDescriptorProjection.Projection> descriptors = new LinkedHashMap<>();
        private final Map<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> widgets = new LinkedHashMap<>();
        private final Map<ContractRef<NodeId>, FunctionBoundary> boundaries = new LinkedHashMap<>();
        private final Map<ContractRef<NodeId>, List<DropContribution>> drops = new LinkedHashMap<>();
        private final Set<ContractRef<NodeId>> activeDefinitions = new LinkedHashSet<>();
        private final Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
        private int descriptorRejectedCount;
        private int descriptorReadOnlyCount;
        private boolean ignoredBoundaryDeclaration;
        private boolean finished;

        private Builder(ReSyncCatalogPublicationProjection.Snapshot snapshot,
                        CatalogAuthoringPublication authoring) {
            this.snapshot = Objects.requireNonNull(snapshot, "Catalog publication snapshot is required");
            this.authoring = authoring;
            this.catalogKey = snapshot.publication().key();
            this.startedAt = System.nanoTime();
            this.entries = snapshot.entries().values().iterator();
            ReSyncFlowClient.traceLifecycle(catalogKey.serverId().canonicalText(), "typed_catalog_conversion_started",
                "resourceKey", catalogKey.canonicalText(), "operation", "convert_typed_catalog", "requestId", "",
                "correlationId", "", "traceId", "", "mutationId", "", "generation", catalogKey.catalogGeneration(),
                "authorityEpoch", -1L, "revision", snapshot.publication().revision(), "inputCount",
                snapshot.entries().size(), "elapsedMs", 0L);
        }

        public boolean advance(int maximumEntries) {
            if (maximumEntries < 1) {
                throw new IllegalArgumentException("Catalog projection batch must contain an entry");
            }
            if (finished) {
                return true;
            }
            int count = 0;
            while (entries.hasNext() && count < maximumEntries) {
                accept(entries.next());
                count++;
            }
            return !entries.hasNext();
        }

        private void accept(CatalogCachePublication.Entry entry) {
            Optional<ReSyncGenericDescriptorProjection.Projection> opened = ReSyncGenericDescriptorProjection.open(
                entry, ReSyncGenericDescriptorProjection.ClientCapabilities.primitive());
            if (opened.isEmpty()) {
                descriptorRejectedCount++;
                rejectionReasons.merge("descriptor_projection_empty", 1, Integer::sum);
                return;
            }
            ReSyncGenericDescriptorProjection.Projection descriptor = opened.orElseThrow();
            descriptors.put(entry.definitionKey(), descriptor);
            ReSyncGenericWidgetCapabilities.from(descriptor)
                .ifPresent(widget -> widgets.put(entry.definitionKey(), widget));
            if (entry.present() && !entry.opaque() && entry.state() == CatalogCacheState.ACTIVE) {
                activeDefinitions.add(entry.definitionKey());
                List<DropContribution> contributions = dropContributions(entry.definitionKey(), descriptor.descriptor());
                if (!contributions.isEmpty()) {
                    drops.put(entry.definitionKey(), contributions);
                }
            }
            if (descriptor.status() != ReSyncGenericDescriptorProjection.Status.ACTIVE || descriptor.readOnly()) {
                descriptorReadOnlyCount++;
                rejectionReasons.merge(descriptor.reason().isBlank() ? descriptor.status().name().toLowerCase(Locale.ROOT)
                    : descriptor.reason(), 1, Integer::sum);
                ignoredBoundaryDeclaration |= declaresBoundary(entry.definitionKey(), descriptor.descriptor());
                return;
            }
            BoundaryProjection boundary = boundary(entry.definitionKey(), descriptor.descriptor());
            if (!boundary.valid()) {
                traceRejected(catalogKey, snapshot.entries().size(), descriptors.size(), boundary.diagnostic(), startedAt);
                throw new ValidationException(boundary.diagnostic());
            }
            if (boundary.boundary() != null) {
                FunctionBoundary previous = boundaries.values().stream()
                    .filter(value -> value.role() == boundary.boundary().role()).findFirst().orElse(null);
                if (previous != null) {
                    String diagnostic = "CATALOG_INTERACTION.BOUNDARY_ROLE_CONFLICT:"
                        + previous.nodeIdentity().canonicalText() + ":" + entry.definitionKey().canonicalText();
                    traceRejected(catalogKey, snapshot.entries().size(), descriptors.size(), diagnostic, startedAt);
                    throw new ValidationException(diagnostic);
                }
                boundaries.put(entry.definitionKey(), boundary.boundary());
            }
        }

        public ReSyncTypedInteractionProjection finish() {
            if (finished || entries.hasNext()) {
                throw new IllegalStateException("Catalog projection is not ready");
            }
            finished = true;
            return complete();
        }

        private ReSyncTypedInteractionProjection complete() {
            if (!boundaries.isEmpty()) {
                boolean inputsMissing = boundaries.values().stream()
                    .noneMatch(value -> value.role() == FunctionBoundaryRole.INPUTS);
                boolean outputsMissing = boundaries.values().stream()
                    .noneMatch(value -> value.role() == FunctionBoundaryRole.OUTPUTS);
                if ((inputsMissing || outputsMissing) && ignoredBoundaryDeclaration) {
                    boundaries.clear();
                } else if (inputsMissing) {
                    traceRejected(catalogKey, snapshot.entries().size(), descriptors.size(),
                        "CATALOG_INTERACTION.BOUNDARY_INPUTS_MISSING", startedAt);
                    throw new ValidationException("CATALOG_INTERACTION.BOUNDARY_INPUTS_MISSING");
                } else if (outputsMissing) {
                    traceRejected(catalogKey, snapshot.entries().size(), descriptors.size(),
                        "CATALOG_INTERACTION.BOUNDARY_OUTPUTS_MISSING", startedAt);
                    throw new ValidationException("CATALOG_INTERACTION.BOUNDARY_OUTPUTS_MISSING");
                }
            }
            ReSyncTypedInteractionProjection projection = new ReSyncTypedInteractionProjection(catalogKey,
                snapshot.publication().revision(), descriptors, widgets, boundaries, drops, activeDefinitions, authoring);
            List<String> readOnlyReasons = descriptors.values().stream()
                .filter(ReSyncGenericDescriptorProjection.Projection::readOnly)
                .map(ReSyncGenericDescriptorProjection.Projection::reason)
                .filter(reason -> reason != null && !reason.isBlank()).distinct().toList();
            ReSyncFlowClient.traceLifecycle(catalogKey.serverId().canonicalText(), "typed_catalog_conversion_completed",
                "resourceKey", catalogKey.canonicalText(), "operation", "convert_typed_catalog", "requestId", "",
                "correlationId", "", "traceId", "", "mutationId", "", "generation", catalogKey.catalogGeneration(),
                "authorityEpoch", -1L, "revision", snapshot.publication().revision(), "inputCount",
                snapshot.entries().size(), "descriptorCount", descriptors.size(), "readOnlyCount",
                descriptorReadOnlyCount, "descriptorRejectedCount", descriptorRejectedCount, "widgetCount",
                projection.widgetDefinitions.size(), "widgetRejectedCount",
                Math.max(0, descriptors.size() - projection.widgetDefinitions.size()), "worldGenCount",
                projection.worldGenDefinitions.size(), "boundaryCount", boundaries.size(), "dropCount",
                projection.allDropContributions.size(), "readOnlyReasons", String.join(";", readOnlyReasons), "reason",
                "converted", "rejectionReasons", rejectionReasons(rejectionReasons), "paletteCount",
                projection.palette(false).definitions().size(), "worldGenPaletteCount",
                projection.palette(true).definitions().size(), "elapsedMs", elapsedMillis(startedAt));
            return projection;
        }
    }

    public boolean canConvert(FlowTypeRef source, FlowTypeRef target) {
        if (source == null || target == null) {
            return false;
        }
        String sourceType = source.normalizedGenerics().toString();
        String targetType = target.normalizedGenerics().toString();
        if (sourceType.equals(targetType)) {
            return true;
        }
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayList<String> pending = new ArrayList<>();
        visited.add(sourceType);
        pending.add(sourceType);
        for (int index = 0; index < pending.size() && index < 256; index++) {
            for (String candidate : conversions.getOrDefault(pending.get(index), List.of())) {
                if (targetType.equals(candidate)) {
                    return true;
                }
                if (visited.add(candidate)) {
                    pending.add(candidate);
                }
            }
        }
        return false;
    }

    private static Map<String, List<String>> conversionIndex(CatalogAuthoringPublication publication) {
        if (publication == null || publication.section(CatalogAuthoringPublication.Section.CONVERSIONS).state()
            == CatalogCacheState.UNAVAILABLE) {
            return Map.of();
        }
        LinkedHashMap<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        for (CatalogAuthoringPublication.Entry entry : publication.conversions()) {
            if (entry == null || entry.opaque() || entry.state() == CatalogCacheState.UNAVAILABLE) {
                continue;
            }
            try {
                JsonValue value = entry.data().canonicalValue();
                if (!(value instanceof JsonValue.JsonObject object)) {
                    continue;
                }
                String source = typeExpression(object.value("source").toJava());
                String target = typeExpression(object.value("target").toJava());
                if (!source.isBlank() && !target.isBlank()) {
                    values.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
                }
            } catch (RuntimeException ignored) {
            }
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((source, targets) -> result.put(source, List.copyOf(targets)));
        return Collections.unmodifiableMap(result);
    }

    private static void traceRejected(CatalogCacheKey key, int inputCount, int descriptorCount, String reason,
                                     long startedAt) {
        ReSyncFlowClient.traceLifecycle(key.serverId().canonicalText(), "typed_catalog_conversion_rejected",
            "resourceKey", key.canonicalText(), "operation", "convert_typed_catalog", "requestId", "",
            "correlationId", "", "traceId", "", "mutationId", "", "generation", key.catalogGeneration(),
            "authorityEpoch", -1L, "revision", -1L, "inputCount", inputCount,
            "descriptorCount", descriptorCount, "widgetCount", 0, "paletteCount", 0, "outcome", "rejected",
            "reason", reason, "elapsedMs", elapsedMillis(startedAt));
    }

    private static String rejectionReasons(Map<String, Integer> reasons) {
        return reasons == null || reasons.isEmpty() ? "" : reasons.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue()).reduce((left, right) -> left + ";" + right)
            .orElse("");
    }

    private static long elapsedMillis(long startedAt) {
        return BrowserSafeState.nanosToMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static boolean primitiveTypeExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String normalized = expression.toLowerCase(Locale.ROOT);
        return Set.of("string", "text", "number", "integer", "float", "double", "boolean", "bool", "json",
            "json_object").contains(normalized);
    }

    public CatalogCacheKey key() {
        return key;
    }

    public long revision() {
        return revision;
    }

    public Map<ContractRef<NodeId>, ReSyncGenericDescriptorProjection.Projection> descriptors() {
        return descriptors;
    }

    public Optional<ReSyncGenericDescriptorProjection.Projection> descriptor(ContractRef<NodeId> identity) {
        return identity == null ? Optional.empty() : Optional.ofNullable(descriptors.get(identity));
    }

    public Optional<ReSyncGenericWidgetCapabilities.WidgetDefinition> widgetDefinition(ContractRef<NodeId> identity) {
        return identity == null ? Optional.empty() : Optional.ofNullable(widgetDefinitions.get(identity));
    }

    public Optional<ReSyncGenericWidgetCapabilities.WidgetDefinition> uniqueWidgetDefinition(String id,
                                                                                               boolean worldGen) {
        if (id == null || id.isBlank() || !id.equals(id.strip()) || id.indexOf(':') >= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable((worldGen ? uniqueWorldGenWidgetDefinitions : uniqueWidgetDefinitions).get(id));
    }

    public Map<String, NodeDefinition> worldGenDefinitions() {
        return worldGenDefinitions;
    }

    public Optional<FlowTypeRef> pinType(ContractRef<NodeId> identity, String pinName, boolean input) {
        if (identity == null || pinName == null || pinName.isBlank() || !pinName.equals(pinName.strip())) {
            return Optional.empty();
        }
        return descriptor(identity)
            .flatMap(descriptor -> descriptor.pin(pinName, input))
            .flatMap(ReSyncTypedInteractionProjection::pinType);
    }

    public Map<ContractRef<NodeId>, FunctionBoundary> functionBoundaries() {
        return functionBoundaries;
    }

    public Optional<FunctionBoundary> functionBoundary(ContractRef<NodeId> identity) {
        return identity == null ? Optional.empty() : Optional.ofNullable(functionBoundaries.get(identity));
    }

    public Map<ContractRef<NodeId>, List<DropContribution>> dropContributions() {
        return dropContributions;
    }

    public List<DropContribution> dropContributions(ContractRef<NodeId> identity) {
        return identity == null ? List.of() : dropContributions.getOrDefault(identity, List.of());
    }

    public List<DropContribution> allDropContributions() {
        return allDropContributions;
    }

    public Optional<DropAuthoring> dropAuthoring(String resourceOwner, String resourceType, String capability,
                                                  ContractRef<NodeId> target, String inputPin,
                                                  String referenceKind, String referenceOwner,
                                                  ServerId currentServer) {
        if (currentServer == null || !key.serverId().equals(currentServer) || target == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(dropAuthoring.get(new DropSemanticKey(
                required(resourceOwner, "Resource owner"), required(resourceType, "Resource type"),
                target.owner().canonicalText(), target.id().canonicalText(),
                required(capability, "Drop capability"), required(inputPin, "Drop input pin"),
                required(referenceKind, "Drop reference kind"), required(referenceOwner, "Drop reference owner"))));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Optional<NodeDefinition> droppedNodeDefinition(ContractRef<NodeId> target, Map<String, Object> inputs,
                                                           ServerId currentServer) {
        return droppedNodeAuthoring(target, inputs, currentServer).map(DropAuthoring::definition);
    }

    public Optional<DropAuthoring> droppedNodeAuthoring(ContractRef<NodeId> target, Map<String, Object> inputs,
                                                         ServerId currentServer) {
        if (target == null || inputs == null || currentServer == null || !key.serverId().equals(currentServer)) {
            return Optional.empty();
        }
        return dropAuthoring.values().stream()
            .filter(authoring -> authoring.contribution().target().equals(target))
            .filter(authoring -> exactLocator(inputs.get(authoring.contribution().inputPin()),
                authoring.contribution(), currentServer))
            .findFirst();
    }

    public Palette palette(boolean worldGen) {
        return worldGen ? worldGenPalette : palette;
    }

    private static BoundaryProjection boundary(ContractRef<NodeId> identity, Map<String, Object> descriptor) {
        if (identity == null || descriptor == null) {
            return BoundaryProjection.absent();
        }
        Map<String, Object> metadata = map(descriptor.get("metadata"));
        boolean builtin = builtinBoundary(identity);
        BoundaryDeclaration declaration = boundaryDeclaration(metadata);
        if (!builtin && !declaration.declared()) {
            return BoundaryProjection.absent();
        }
        if (!declaration.diagnostic().isBlank()) {
            return BoundaryProjection.invalid(declaration.diagnostic() + ":" + identity.canonicalText());
        }
        Map<String, Object> authored = declaration.value();
        String role = boundaryRole(identity, authored);
        if (role.isBlank()) {
            return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_ROLE_INVALID:" + identity.canonicalText());
        }
        String flowPin = strictText(authored.get("flowPin"));
        if (authored.containsKey("flowPin") && flowPin.isBlank()) {
            return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_FLOW_PIN_INVALID:" + identity.canonicalText());
        }
        List<Parameter> parameters = new ArrayList<>();
        Object authoredParameters = authored.get("parameterPins");
        if (authored.containsKey("parameterPins") && !(authoredParameters instanceof Collection<?>)) {
            return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETERS_INVALID:" + identity.canonicalText());
        }
        Set<String> parameterIds = new HashSet<>();
        if (authoredParameters instanceof Collection<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> raw)) {
                    return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETER_INVALID:" + identity.canonicalText());
                }
                Map<String, Object> parameter = map(raw);
                String id = strictText(parameter.get("id"));
                String name = strictText(parameter.get("name"));
                String typeRef = typeExpression(parameter.get("typeRef"));
                if (id.isBlank() || name.isBlank() || !parameterIds.add(id)) {
                    return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETER_INVALID:"
                        + identity.canonicalText() + ":" + id);
                }
                if (typeRef.isBlank()) {
                    return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETER_TYPE_INVALID:"
                        + identity.canonicalText() + ":" + id);
                }
                parameters.add(new Parameter(id, name, typeRef));
            }
        }
        boolean hasAuthoredParameters = !parameters.isEmpty();
        Object pinsValue = descriptor.get("pins");
        boolean flowPinPresent = false;
        int executionPins = 0;
        if (pinsValue instanceof Collection<?> pins) {
            String parameterDirection = "inputs".equals(role) ? "output" : "input";
            for (Object value : pins) {
                if (!(value instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> pin = map(raw);
                if (!parameterDirection.equals(strictText(pin.get("direction")))) {
                    continue;
                }
                String pinId = strictText(pin.get("id"));
                String typeRef = typeExpression(pin.get("type"));
                if (isExecutionType(pin.get("type"))) {
                    if (pinId.isBlank()) {
                        return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_FLOW_PIN_INVALID:"
                            + identity.canonicalText());
                    }
                    if (++executionPins > 1) {
                        return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_FLOW_PIN_CONFLICT:"
                            + identity.canonicalText());
                    }
                    if (flowPin.isBlank()) {
                        flowPin = pinId;
                    }
                    flowPinPresent = true;
                } else if (!hasAuthoredParameters && !pinId.isBlank() && !typeRef.isBlank()) {
                    String name = strictText(pin.get("displayName"));
                    if (!parameterIds.add(pinId)) {
                        return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETER_INVALID:"
                            + identity.canonicalText() + ":" + pinId);
                    }
                    try {
                        FlowTypeRef parsedType = FlowTypeRef.parse(typeRef);
                        if (!parsedType.isResolved()) {
                            return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETER_TYPE_INVALID:"
                                + identity.canonicalText() + ":" + pinId);
                        }
                    } catch (RuntimeException exception) {
                        return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETER_TYPE_INVALID:"
                            + identity.canonicalText() + ":" + pinId);
                    }
                    parameters.add(new Parameter(pinId, name.isBlank() ? pinId : name, typeRef));
                }
            }
        }
        if (flowPin.isBlank() || !flowPinPresent) {
            return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_FLOW_PIN_MISSING:" + identity.canonicalText());
        }
        if (parameterIds.contains(flowPin)) {
            return BoundaryProjection.invalid("CATALOG_INTERACTION.BOUNDARY_PARAMETER_CONFLICT:"
                + identity.canonicalText() + ":" + flowPin);
        }
        return BoundaryProjection.valid(new FunctionBoundary(
            "inputs".equals(role) ? FunctionBoundaryRole.INPUTS : FunctionBoundaryRole.OUTPUTS,
            identity, flowPin, parameters));
    }

    private static boolean declaresBoundary(ContractRef<NodeId> identity, Map<String, Object> descriptor) {
        Map<String, Object> metadata = descriptor == null ? Map.of() : map(descriptor.get("metadata"));
        return builtinBoundary(identity) || boundaryDeclaration(metadata).declared();
    }

    private static String boundaryRole(ContractRef<NodeId> identity, Map<String, Object> boundary) {
        if ("builtin".equals(identity.owner().canonicalText())) {
            String id = identity.id().canonicalText();
            if ("function.start".equals(id)) {
                return "inputs";
            }
            if ("function.end".equals(id)) {
                return "outputs";
            }
        }
        String role = strictText(boundary.get("role")).toLowerCase(Locale.ROOT);
        return switch (role) {
            case "input", "inputs" -> "inputs";
            case "output", "outputs" -> "outputs";
            default -> "";
        };
    }

    private static boolean builtinBoundary(ContractRef<NodeId> identity) {
        if (identity == null || !"builtin".equals(identity.owner().canonicalText())) {
            return false;
        }
        String id = identity.id().canonicalText();
        return "function.start".equals(id) || "function.end".equals(id);
    }

    private static BoundaryDeclaration boundaryDeclaration(Map<String, Object> metadata) {
        Map<String, Object> authoredSource = map(metadata.get("authoredSource"));
        Map<String, Object> handlerConfig = map(authoredSource.get("handlerConfig"));
        List<Map<String, Object>> sources = List.of(metadata, handlerConfig);
        Map<String, Object> value = Map.of();
        boolean declared = false;
        for (Map<String, Object> source : sources) {
            for (String key : List.of("functionBoundary", "functionBoundaryIntent")) {
                if (!source.containsKey(key)) {
                    continue;
                }
                Object raw = source.get(key);
                if (!(raw instanceof Map<?, ?>)) {
                    return new BoundaryDeclaration(true, Map.of(),
                        "CATALOG_INTERACTION.BOUNDARY_DECLARATION_INVALID");
                }
                Map<String, Object> candidate = map(raw);
                if (declared && !value.equals(candidate)) {
                    return new BoundaryDeclaration(true, Map.of(),
                        "CATALOG_INTERACTION.BOUNDARY_DECLARATION_CONFLICT");
                }
                declared = true;
                value = candidate;
            }
        }
        return new BoundaryDeclaration(declared, value, "");
    }

    private static List<DropContribution> dropContributions(ContractRef<NodeId> identity, Map<String, Object> descriptor) {
        List<DropContribution> result = new ArrayList<>();
        Map<String, Object> metadata = map(descriptor.get("metadata"));
        for (String key : List.of("dropContributions", "drop_contributions", "resourceDropContributions")) {
            Object value = metadata.get(key);
            if (value instanceof Collection<?> values) {
                for (Object item : values) {
                    if (item instanceof Map<?, ?> raw) {
                        DropContribution contribution = explicitDrop(identity, map(raw));
                        if (contribution != null) {
                            result.add(contribution);
                        }
                    }
                }
            }
        }
        Object pinsValue = descriptor.get("pins");
        if (pinsValue instanceof Collection<?> pins) {
            for (Object value : pins) {
                if (!(value instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> pin = map(raw);
                if (!"input".equals(strictText(pin.get("direction"))) || !isResourceType(pin.get("type"))) {
                    continue;
                }
                Map<String, Object> type = map(pin.get("type"));
                Map<String, Object> resourceType = map(type.get("resourceType"));
                String resourceOwner = strictText(resourceType.get("ownerId"));
                String resourceId = strictText(resourceType.get("localId"));
                String inputPin = strictText(pin.get("id"));
                String capability = strictText(pin.get("resourceRole"));
                if (capability.isBlank()) {
                    capability = "reference";
                }
                if (!resourceOwner.isBlank() && !resourceId.isBlank() && !inputPin.isBlank()) {
                    result.add(new DropContribution(resourceOwner, resourceId, capability, identity, inputPin,
                        referenceKind(resourceOwner, resourceId), resourceOwner, 0));
                }
            }
        }
        LinkedHashMap<DropSemanticKey, DropContribution> unique = new LinkedHashMap<>();
        for (DropContribution contribution : result) {
            if (contribution != null) {
                unique.merge(DropSemanticKey.of(contribution), contribution,
                    ReSyncTypedInteractionProjection::preferredDropContribution);
            }
        }
        return List.copyOf(unique.values());
    }

    private static DropContribution preferredDropContribution(DropContribution current, DropContribution candidate) {
        return candidate.priority() > current.priority() ? candidate : current;
    }

    private static DropAuthoring admitDrop(
        DropContribution contribution,
        Map<ContractRef<NodeId>, ReSyncGenericDescriptorProjection.Projection> descriptors,
        Map<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> widgets,
        Set<ContractRef<NodeId>> activeDefinitions) {
        if (contribution == null || activeDefinitions == null || !activeDefinitions.contains(contribution.target())) {
            return null;
        }
        ReSyncGenericDescriptorProjection.Projection descriptor = descriptors.get(contribution.target());
        ReSyncGenericWidgetCapabilities.WidgetDefinition widget = widgets.get(contribution.target());
        if (descriptor == null || widget == null || widget.definition() == null) {
            return null;
        }
        ReSyncGenericDescriptorProjection.Pin pin = descriptor.pin(contribution.inputPin(), true).orElse(null);
        List<ReSyncGenericDescriptorProjection.Field> unsupported = descriptor.fields().stream()
            .filter(field -> !field.editable()).toList();
        if (pin == null || unsupported.size() != 1
            || !unsupported.getFirst().id().equals("pin:" + contribution.inputPin())) {
            return null;
        }
        TypeExpr type;
        try {
            type = CoreGraphUiProjection.descriptorType(pin.type());
        } catch (RuntimeException exception) {
            return null;
        }
        if (!(type instanceof TypeExpr.ResourceType resource)
            || !resource.resourceType().ownerId().equals(contribution.resourceOwner())
            || !resource.resourceType().localId().equals(contribution.resourceType())) {
            return null;
        }
        return new DropAuthoring(contribution, widget.definition());
    }

    private static boolean exactLocator(Object value, DropContribution contribution, ServerId currentServer) {
        if (!(value instanceof Map<?, ?>)) {
            return false;
        }
        try {
            ServerResourceLocator locator = IdentityCodec.decodeLocator(JsonValue.fromJava(value));
            ContractRef<ResourceTypeId> expected = ContractRef.of(new OwnerId(contribution.resourceOwner()),
                new ResourceTypeId(contribution.resourceType()));
            return locator.serverId().equals(currentServer) && locator.type().equals(expected)
                && locator.id() != null && !locator.id().isBlank();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static DropContribution explicitDrop(ContractRef<NodeId> fallback, Map<String, Object> value) {
        String resourceOwner = strictText(first(value, "resourceTypeOwner", "resourceOwner"));
        String resourceType = strictText(value.get("resourceType"));
        String capability = strictText(value.get("capability"));
        String inputPin = strictText(value.get("inputPin"));
        ContractRef<NodeId> target = typedTarget(value, fallback);
        if (resourceOwner.isBlank() || resourceType.isBlank() || capability.isBlank() || inputPin.isBlank() || target == null) {
            return null;
        }
        String referenceKind = strictText(value.get("referenceKind"));
        String referenceOwner = strictText(value.get("referenceOwner"));
        int priority = number(value.get("priority"));
        return new DropContribution(resourceOwner, resourceType, capability, target, inputPin,
            referenceKind.isBlank() ? referenceKind(resourceOwner, resourceType) : canonicalReferenceKind(referenceKind,
                resourceOwner, resourceType),
            referenceOwner.isBlank() ? resourceOwner : referenceOwner, priority);
    }

    private static ContractRef<NodeId> typedTarget(Map<String, Object> value, ContractRef<NodeId> fallback) {
        String reference = strictText(value.get("nodeReference"));
        if (!reference.isBlank()) {
            try {
                return ContractRef.parseCanonicalText(reference, NodeId::new);
            } catch (RuntimeException exception) {
                return null;
            }
        }
        String owner = strictText(value.get("owner"));
        String nodeId = strictText(value.get("nodeId"));
        if (owner.isBlank() && nodeId.isBlank()) {
            return fallback;
        }
        if (owner.isBlank() || nodeId.isBlank() || !owner.equals(owner.strip()) || !nodeId.equals(nodeId.strip())) {
            return null;
        }
        try {
            return ContractRef.of(new OwnerId(owner), new NodeId(nodeId));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean isResourceType(Object value) {
        return value instanceof Map<?, ?> raw && "resource".equals(strictText(map(raw).get("kind")));
    }

    private static boolean isExecutionType(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return false;
        }
        Map<String, Object> type = map(raw);
        if (!"named".equals(strictText(type.get("kind")))) {
            return false;
        }
        Map<String, Object> reference = map(type.get("type"));
        return "builtin".equals(strictText(reference.get("ownerId")))
            && "execution".equals(strictText(reference.get("localId")));
    }

    private static String typeExpression(Object value) {
        return ReSyncGenericWidgetCapabilities.canonicalTypeExpression(value);
    }

    private static Optional<FlowTypeRef> pinType(Map<String, Object> descriptor, String pinName, boolean input) {
        Object pinsValue = descriptor.get("pins");
        if (!(pinsValue instanceof Collection<?> pins)) {
            return Optional.empty();
        }
        String direction = input ? "input" : "output";
        for (Object value : pins) {
            if (!(value instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> pin = map(raw);
            if (!pinName.equals(strictText(pin.get("id"))) || !direction.equals(strictText(pin.get("direction")))) {
                continue;
            }
            String expression = typeExpression(pin.get("type"));
            if (expression.isBlank()) {
                return Optional.empty();
            }
            try {
                FlowTypeRef type = FlowTypeRef.parse(expression);
                return type.isResolved() ? Optional.of(type) : Optional.empty();
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<FlowTypeRef> pinType(ReSyncGenericDescriptorProjection.Pin pin) {
        String expression = typeExpression(pin.type());
        if (expression.isBlank()) {
            return Optional.empty();
        }
        expression = switch (expression.toLowerCase(Locale.ROOT)) {
            case "text" -> "string";
            case "bool" -> "boolean";
            case "json" -> "json_object";
            default -> expression;
        };
        try {
            FlowTypeRef type = FlowTypeRef.parse(expression);
            return type.isResolved() ? Optional.of(type) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String referenceKind(String resourceOwner, String resourceType) {
        return "builtin".equals(resourceOwner) ? resourceType : resourceOwner + ":" + resourceType;
    }

    private static String canonicalReferenceKind(String referenceKind, String resourceOwner, String resourceType) {
        if ("builtin".equals(resourceOwner)
            && (referenceKind.equalsIgnoreCase(resourceType)
            || referenceKind.equalsIgnoreCase("builtin:" + resourceType))) {
            return resourceType;
        }
        return referenceKind;
    }

    private record DropSemanticKey(String resourceOwner, String resourceType, String targetOwner, String targetId,
                                   String capability, String inputPin, String referenceKind, String referenceOwner) {
        private static DropSemanticKey of(DropContribution contribution) {
            return new DropSemanticKey(contribution.resourceOwner(), contribution.resourceType(),
                contribution.target().owner().canonicalText(), contribution.target().id().canonicalText(),
                contribution.capability(), contribution.inputPin(), contribution.referenceKind(),
                contribution.referenceOwner());
        }
    }

    private static String first(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = strictText(source.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return Map.of();
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String strictText(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            return "";
        }
        return text;
    }

    private static <T> Map<ContractRef<NodeId>, T> immutableMap(Map<ContractRef<NodeId>, T> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, ReSyncGenericWidgetCapabilities.WidgetDefinition> uniqueWidgetDefinitions(
        Map<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> widgets, boolean worldGen) {
        LinkedHashMap<String, ReSyncGenericWidgetCapabilities.WidgetDefinition> unique = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (ReSyncGenericWidgetCapabilities.WidgetDefinition widget : widgets.values()) {
            NodeDefinition definition = widget != null ? widget.definition() : null;
            String id = definition != null ? definition.getId() : null;
            if (definition == null || id == null || id.isBlank() || ambiguous.contains(id)
                || worldGen && !"worldgen".equals(definition.getOwner())) {
                continue;
            }
            if (unique.putIfAbsent(id, widget) != null) {
                unique.remove(id);
                ambiguous.add(id);
            }
        }
        return Collections.unmodifiableMap(unique);
    }

    private static Map<String, NodeDefinition> worldGenDefinitions(
        Map<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> widgets) {
        if (widgets == null || widgets.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, NodeDefinition> definitions = new LinkedHashMap<>();
        for (ReSyncGenericWidgetCapabilities.WidgetDefinition widget : widgets.values()) {
            NodeDefinition definition = widget != null ? widget.definition() : null;
            String owner = definition != null ? definition.getOwner() : null;
            String id = definition != null ? definition.getId() : null;
            if ("worldgen".equals(owner) && id != null && !id.isBlank() && id.equals(id.strip())
                && id.indexOf(':') < 0) {
                definitions.put(owner + ":" + id, definition);
            }
        }
        return Collections.unmodifiableMap(definitions);
    }

    public static final class Palette {
        private static final Comparator<NodeDefinition> ORDER = Comparator.comparingInt(NodeDefinition::getPriority)
            .thenComparing(NodeDefinition::getDisplayName, String.CASE_INSENSITIVE_ORDER);
        private final CatalogCacheKey key;
        private final long revision;
        private final List<NodeDefinition> definitions;
        private final List<NodeDefinition.NodeCategory> categoryOrder;
        private final Map<NodeDefinition.NodeCategory, List<NodeDefinition>> categories;
        private final Map<String, NodeDefinition> definitionsById;

        private Palette(CatalogCacheKey key, long revision, List<NodeDefinition> definitions, List<NodeDefinition.NodeCategory> categoryOrder,
                        Map<NodeDefinition.NodeCategory, List<NodeDefinition>> categories) {
            this.key = Objects.requireNonNull(key, "Catalog cache key is required");
            this.revision = revision;
            this.definitions = List.copyOf(definitions);
            this.categoryOrder = List.copyOf(categoryOrder);
            LinkedHashMap<NodeDefinition.NodeCategory, List<NodeDefinition>> immutable = new LinkedHashMap<>();
            categories.forEach((category, values) -> immutable.put(category, List.copyOf(values)));
            this.categories = Collections.unmodifiableMap(immutable);
            LinkedHashMap<String, NodeDefinition> unique = new LinkedHashMap<>();
            Set<String> ambiguous = new HashSet<>();
            for (NodeDefinition definition : this.definitions) {
                String id = definition.getId();
                if (id == null || id.isBlank() || ambiguous.contains(id)) {
                    continue;
                }
                if (unique.putIfAbsent(id, definition) != null) {
                    unique.remove(id);
                    ambiguous.add(id);
                }
            }
            this.definitionsById = Collections.unmodifiableMap(unique);
        }

        private static Palette from(CatalogCacheKey key,
                                    long revision,
                                    Map<ContractRef<NodeId>, ReSyncGenericDescriptorProjection.Projection> descriptors,
                                    Map<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> widgets) {
            long startedAt = System.nanoTime();
            LinkedHashSet<NodeDefinition.NodeCategory> order = new LinkedHashSet<>();
            LinkedHashMap<NodeDefinition.NodeCategory, List<NodeDefinition>> categories = new LinkedHashMap<>();
            List<NodeDefinition> definitions = new ArrayList<>();
            int readOnlyCount = 0;
            int hiddenCount = 0;
            int missingDefinitionCount = 0;
            for (Map.Entry<ContractRef<NodeId>, ReSyncGenericWidgetCapabilities.WidgetDefinition> entry : widgets.entrySet()) {
                ReSyncGenericWidgetCapabilities.WidgetDefinition widget = entry.getValue();
                if (widget == null) {
                    missingDefinitionCount++;
                    continue;
                }
                if (widget.readOnly()) {
                    readOnlyCount++;
                }
                NodeDefinition definition = widget.definition();
                if (definition == null) {
                    missingDefinitionCount++;
                    continue;
                }
                if (definition.isHidden()) {
                    hiddenCount++;
                    continue;
                }
                NodeDefinition.NodeCategory category = category(definition);
                order.add(category);
                categories.computeIfAbsent(category, ignored -> new ArrayList<>()).add(definition);
                definitions.add(definition);
            }
            definitions.sort(ORDER);
            categories.values().forEach(values -> values.sort(ORDER));
            long nonPrimitiveReadOnlyCount = descriptors == null ? 0L : descriptors.values().stream()
                .filter(ReSyncGenericDescriptorProjection.Projection::readOnly)
                .filter(projection -> projection.fields().stream().anyMatch(field -> !field.editable()
                    && !primitiveTypeExpression(field.typeExpression())))
                .count();
            ReSyncFlowClient.traceLifecycle(key.serverId().canonicalText(), "typed_palette_projection",
                "resourceKey", key.canonicalText(), "operation", "build_palette", "requestId", "",
                "correlationId", "", "traceId", "", "mutationId", "", "generation", key.catalogGeneration(),
                "authorityEpoch", -1L, "revision", revision, "inputCount", widgets.size(), "readOnlyCount",
                readOnlyCount, "nonPrimitiveReadOnlyCount", nonPrimitiveReadOnlyCount, "hiddenCount", hiddenCount,
                "missingDefinitionCount", missingDefinitionCount, "filteredCount",
                widgets.size() - definitions.size(), "outputCount", definitions.size(), "categoryCount", categories.size(),
                "reason", definitions.isEmpty() ? "palette_empty_after_filters" : "projected", "rejectionReason",
                definitions.isEmpty() ? "all_widgets_filtered_or_unavailable" : "", "elapsedMs",
                elapsedMillis(startedAt));
            return new Palette(key, revision, definitions, List.copyOf(order), categories);
        }

        private Palette worldGen() {
            long startedAt = System.nanoTime();
            LinkedHashSet<NodeDefinition.NodeCategory> order = new LinkedHashSet<>();
            LinkedHashMap<NodeDefinition.NodeCategory, List<NodeDefinition>> filtered = new LinkedHashMap<>();
            List<NodeDefinition> worldGenDefinitions = new ArrayList<>();
            for (NodeDefinition definition : definitions) {
                if (!"worldgen".equals(definition.getOwner())) {
                    continue;
                }
                NodeDefinition.NodeCategory category = category(definition);
                order.add(category);
                filtered.computeIfAbsent(category, ignored -> new ArrayList<>()).add(definition);
                worldGenDefinitions.add(definition);
            }
            ReSyncFlowClient.traceLifecycle(key.serverId().canonicalText(), "typed_worldgen_palette_projection",
                "resourceKey", key.canonicalText(), "operation", "filter_worldgen_palette", "requestId", "",
                "correlationId", "", "traceId", "", "mutationId", "", "generation", key.catalogGeneration(),
                "authorityEpoch", -1L, "revision", revision, "inputCount", definitions.size(), "readOnlyCount", 0,
                "filteredCount", definitions.size() - worldGenDefinitions.size(), "outputCount",
                worldGenDefinitions.size(), "categoryCount", filtered.size(), "reason",
                worldGenDefinitions.isEmpty() ? "worldgen_palette_empty_after_filters" : "projected", "rejectionReason",
                worldGenDefinitions.isEmpty() ? "no_worldgen_widgets" : "", "elapsedMs",
                elapsedMillis(startedAt));
            return new Palette(key, revision, worldGenDefinitions, List.copyOf(order), filtered);
        }

        public CatalogCacheKey key() {
            return key;
        }

        private static NodeDefinition.NodeCategory category(NodeDefinition definition) {
            String id = definition.getId();
            if (id != null && id.startsWith("event:")) {
                return NodeDefinition.NodeCategory.EVENT;
            }
            NodeDefinition.NodeCategory category = definition.getCategory();
            return category != null ? category : NodeDefinition.NodeCategory.UTILITY;
        }

        public List<NodeDefinition> definitions() {
            return definitions;
        }

        public List<NodeDefinition.NodeCategory> categoryOrder() {
            return categoryOrder;
        }

        public Map<NodeDefinition.NodeCategory, List<NodeDefinition>> categories() {
            return categories;
        }

        public Optional<NodeDefinition> definition(String id) {
            return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(definitionsById.get(id));
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        private final String diagnostic;

        public ValidationException(String diagnostic) {
            super(required(diagnostic, "Typed interaction diagnostic"));
            this.diagnostic = diagnostic;
        }

        public String diagnostic() {
            return diagnostic;
        }
    }

    private record BoundaryProjection(boolean valid, FunctionBoundary boundary, String diagnostic) {
        private static BoundaryProjection absent() {
            return new BoundaryProjection(true, null, "");
        }

        private static BoundaryProjection valid(FunctionBoundary boundary) {
            return new BoundaryProjection(true, Objects.requireNonNull(boundary, "Function boundary is required"), "");
        }

        private static BoundaryProjection invalid(String diagnostic) {
            return new BoundaryProjection(false, null, required(diagnostic, "Function boundary diagnostic"));
        }
    }

    private record BoundaryDeclaration(boolean declared, Map<String, Object> value, String diagnostic) {
    }

    public enum FunctionBoundaryRole {
        INPUTS,
        OUTPUTS
    }

    public record FunctionBoundary(FunctionBoundaryRole role, ContractRef<NodeId> nodeIdentity,
                                   String flowPin, List<Parameter> parameterPins) {
        public FunctionBoundary {
            role = Objects.requireNonNull(role, "Function boundary role is required");
            nodeIdentity = Objects.requireNonNull(nodeIdentity, "Function boundary identity is required");
            flowPin = Objects.requireNonNull(flowPin, "Function flow pin is required");
            if (flowPin.isBlank() || !flowPin.equals(flowPin.strip())) {
                throw new IllegalArgumentException("Function flow pin is not canonical");
            }
            parameterPins = parameterPins == null ? List.of() : List.copyOf(parameterPins);
        }
    }

    public record Parameter(String id, String name, String typeRef) {
        public Parameter {
            id = required(id, "Function parameter ID");
            name = required(name, "Function parameter name");
            typeRef = required(typeRef, "Function parameter type");
        }
    }

    public record DropContribution(String resourceOwner, String resourceType, String capability,
                                   ContractRef<NodeId> target, String inputPin, String referenceKind,
                                   String referenceOwner, int priority) {
        public DropContribution {
            resourceOwner = required(resourceOwner, "Resource owner");
            resourceType = required(resourceType, "Resource type");
            capability = required(capability, "Drop capability");
            target = Objects.requireNonNull(target, "Drop target identity");
            inputPin = required(inputPin, "Drop input pin");
            referenceKind = required(referenceKind, "Drop reference kind");
            referenceOwner = required(referenceOwner, "Drop reference owner");
        }
    }

    public record DropAuthoring(DropContribution contribution, NodeDefinition definition) {
        public DropAuthoring {
            contribution = Objects.requireNonNull(contribution, "Drop contribution is required");
            definition = Objects.requireNonNull(definition, "Drop node definition is required");
        }

        public Map<String, Object> inputValues(String resourceId, ServerId currentServer) {
            if (currentServer == null || resourceId == null || resourceId.isBlank()) {
                return Map.of();
            }
            try {
                ContractRef<ResourceTypeId> type = ContractRef.of(new OwnerId(contribution.resourceOwner()),
                    new ResourceTypeId(contribution.resourceType()));
                ServerResourceLocator locator = new ServerResourceLocator(currentServer, type, resourceId);
                return Map.of(contribution.inputPin(), locator.canonicalValue());
            } catch (RuntimeException exception) {
                return Map.of();
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " is not canonical");
        }
        return value;
    }
}
