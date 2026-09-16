package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;

import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ReSyncGenericWidgetCapabilities {
    private ReSyncGenericWidgetCapabilities() {
    }

    public static Optional<WidgetDefinition> from(ReSyncGenericDescriptorProjection.Projection projection) {
        return convert(projection).widget();
    }

    public static Conversion convert(ReSyncGenericDescriptorProjection.Projection projection) {
        long startedAt = System.nanoTime();
        if (projection == null || projection.status() == ReSyncGenericDescriptorProjection.Status.OPAQUE
            || projection.status() == ReSyncGenericDescriptorProjection.Status.UNAVAILABLE
            || projection.status() == ReSyncGenericDescriptorProjection.Status.INVALID
            || projection.status() == ReSyncGenericDescriptorProjection.Status.TOMBSTONED) {
            return empty(projection, startedAt, projection == null ? "projection_missing"
                : "projection_status_" + projection.status().name().toLowerCase(Locale.ROOT));
        }
        try {
            String id = text(projection.descriptor().get("id"));
            String owner = projection.definitionKey().owner().canonicalText();
            if (id.isBlank() || !projection.definitionKey().id().value().equals(id) || owner.isBlank()) {
                return empty(projection, startedAt, "typed_identity_invalid");
            }
            NodeDefinition.NodeCategory category = NodeDefinition.NodeCategory.fromString(
                firstText(projection.descriptor(), "category", "domain", "family"));
            String displayName = firstText(projection.descriptor(), "displayName", "title");
            if (displayName.isBlank()) {
                displayName = id;
            }
            NodeDefinition.Builder builder = new NodeDefinition.Builder(id, displayName, category)
                .owner(owner)
                .description(firstText(projection.descriptor(), "description"));
            Object priority = nodeProperty(projection.descriptor(), "priority");
            if (priority instanceof Number number) {
                builder.priority(number.intValue());
            }
            Object trigger = nodeProperty(projection.descriptor(), "trigger");
            if (trigger instanceof Boolean value) {
                builder.trigger(value);
            }
            if (nodeProperty(projection.descriptor(), "hidden") instanceof Boolean hidden) {
                builder.hidden(hidden);
            }
            List<ReSyncGenericDescriptorProjection.Field> fields = projection.fields();
            Object pinsValue = projection.descriptor().get("pins");
            if (!(pinsValue instanceof Collection<?> pins) || projection.pins().size() != pins.size()) {
                return empty(projection, startedAt, "pin_projection_count_mismatch");
            }
            List<?> pinEntries = pins.stream().toList();
            RepeatableCatalog repeatables = repeatables(projection.descriptor(), pinEntries, projection.pins());
            if (repeatables == null) {
                return empty(projection, startedAt, "repeatable_contract_invalid");
            }
            int pinCount = 0;
            Set<String> pinIds = new HashSet<>();
            for (int index = 0; index < pinEntries.size(); index++) {
                Object value = pinEntries.get(index);
                if (!(value instanceof Map<?, ?> raw)) {
                    return empty(projection, startedAt, "pin_descriptor_not_object:index=" + index);
                }
                Map<String, Object> pin = stringMap(raw);
                ReSyncGenericDescriptorProjection.Pin projected = projection.pins().get(index);
                String pinId = projected.id();
                if (!pinId.equals(text(pin.get("id"))) || !pinIds.add(pinId.toLowerCase(Locale.ROOT))) {
                    return empty(projection, startedAt, "pin_identity_invalid:" + pinId);
                }
                PinConversion converted = pin(projected, pin, fields, repeatables.pins().get(pinId));
                if (converted.definition() == null) {
                    return empty(projection, startedAt, converted.reason() + ":" + pinId);
                }
                NodeDefinition.PinDefinition definition = converted.definition();
                if (definition.getDirection() == NodeDefinition.PinDirection.INPUT) {
                    builder.input(definition);
                } else {
                    builder.output(definition);
                }
                pinCount++;
            }
            WidgetDefinition widget = new WidgetDefinition(builder.build(), projection.readOnly(),
                projection.definitionKey().canonicalText(), projection.inspector());
            ReSyncFlowClient.traceLifecycle("unresolved", "generic_widget_conversion",
                "resourceKey", projection.definitionKey().canonicalText(), "operation", "convert_widget",
                "requestId", "", "correlationId", "", "traceId", "", "mutationId", "", "generation", -1L,
                "authorityEpoch", -1L, "revision", projection.revision(), "status", projection.status(),
                "readOnly", projection.readOnly(), "fieldCount", projection.fields().size(), "pinCount", pinCount,
                "inputCount", widget.definition().getInputs().size(), "outputCount", widget.definition().getOutputs().size(),
                "outcome", "converted", "reason", "", "elapsedMs", elapsedMillis(startedAt));
            return new Conversion(Optional.of(widget), "");
        } catch (RuntimeException exception) {
            return empty(projection, startedAt, "conversion_exception:" + TaskIdentities.failureName(exception));
        }
    }

    private static Conversion empty(ReSyncGenericDescriptorProjection.Projection projection,
                                    long startedAt, String reason) {
        String identity = projection != null ? projection.definitionKey().canonicalText() : "unknown";
        ReSyncFlowClient.traceLifecycle("unresolved", "generic_widget_conversion",
            "resourceKey", identity, "operation", "convert_widget", "requestId", "", "correlationId", "",
            "traceId", "", "mutationId", "", "generation", -1L, "authorityEpoch", -1L, "revision",
            projection != null ? projection.revision() : -1L, "status",
            projection != null ? projection.status() : "missing", "readOnly",
            projection != null && projection.readOnly(), "fieldCount",
            projection != null ? projection.fields().size() : 0, "pinCount",
            projection != null ? projection.pins().size() : 0, "outcome", "empty", "reason", reason,
            "rejectionReason", reason,
            "elapsedMs", elapsedMillis(startedAt));
        return new Conversion(Optional.empty(), reason);
    }

    private static long elapsedMillis(long startedAt) {
        return BrowserSafeState.nanosToMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static Object nodeProperty(Map<String, Object> descriptor, String key) {
        if (descriptor.containsKey(key)) {
            return descriptor.get(key);
        }
        if (descriptor.get("metadata") instanceof Map<?, ?> metadata) {
            if (metadata.containsKey(key)) {
                return metadata.get(key);
            }
            if (metadata.get("authoredSource") instanceof Map<?, ?> authored) {
                return authored.get(key);
            }
        }
        return null;
    }

    private static PinConversion pin(ReSyncGenericDescriptorProjection.Pin projected,
                                     Map<String, Object> source,
                                     List<ReSyncGenericDescriptorProjection.Field> fields,
                                     RepeatableMetadata repeatable) {
        String id = projected.id();
        String displayName = projected.displayName();
        String direction = projected.direction() == ReSyncGenericDescriptorProjection.Direction.INPUT ? "input" : "output";
        TypeExpr typedType;
        try {
            typedType = CoreGraphUiProjection.descriptorType(projected.type());
        } catch (RuntimeException exception) {
            return PinConversion.empty("pin_type_unresolved");
        }
        FlowTypeRef typeRef = typeRef(projected.type(), typedType);
        if (typeRef == null) {
            return PinConversion.empty("pin_type_unresolved");
        }
        if (typeRef.getTypeId().equals("flow")) {
            return PinConversion.empty("legacy_flow_type_unsupported");
        }
        boolean flow = typeRef.getTypeId().equals("execution");
        FlowDataType dataType = flow ? FlowDataType.EXECUTION : FlowDataType.fromString(typeRef.getTypeId());
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(PinId.of(id), displayName,
            flow ? NodeDefinition.PinType.FLOW : NodeDefinition.PinType.DATA,
            direction.equals("input") ? NodeDefinition.PinDirection.INPUT : NodeDefinition.PinDirection.OUTPUT,
            dataType).typeRef(flow ? FlowTypeRef.simple("execution") : typeRef)
            .typedType(typedType)
            .description(text(source.get("description")));
        if (repeatable != null) {
            builder.repeatable(repeatable.groupId(), repeatable.minimum(), repeatable.maximum(),
                repeatable.itemLabel(), repeatable.ordered());
        }
        ReSyncGenericDescriptorProjection.Field field = fields.stream()
            .filter(value -> value.id().equals("pin:" + id))
            .findFirst().orElse(null);
        Map<String, Object> presentation = ReSyncGenericDescriptorProjection.presentation(source);
        if (field != null) {
            String declaredWidget = text(presentation.get("widget"));
            builder.widget(field.editable() && !declaredWidget.isBlank() && !"auto".equalsIgnoreCase(declaredWidget)
                ? NodeDefinition.WidgetType.fromSerializedName(declaredWidget) : widget(field.editorKind()));
        }
        Object options = presentation.get("options");
        if (options instanceof Collection<?> values) {
            List<String> clean = new ArrayList<>();
            for (Object value : values) {
                String option = literalText(value, projected.type());
                if (option == null || option.isBlank()) {
                    return PinConversion.empty("pin_option_invalid");
                }
                clean.add(option);
            }
            builder.options(clean);
        }
        if (projected.optionSource() != null) {
            builder.optionSourceRef(projected.optionSource());
        }
        Object defaultMaterial = source.containsKey("default") ? source.get("default") : source.get("defaultValue");
        DefaultProjection defaultValue = defaultValue(defaultMaterial, projected.type(), typeRef, typedType);
        if (defaultMaterial != null && defaultValue == null) {
            return PinConversion.empty("pin_default_invalid");
        }
        if (defaultValue != null) {
            builder.defaultValue(defaultValue.compatibilityValue());
            if (defaultValue.typedDefault() != null) {
                builder.typedDefault(defaultValue.typedDefault());
            }
        }
        Object optional = source.get("optional");
        if (optional instanceof Boolean value) {
            builder.optional(value);
        } else if ("optional".equals(text(source.get("requirement")))) {
            builder.optional(true);
        }
        if (presentation.containsKey("constraints")) {
            ConstraintValues constraints = constraints(presentation.get("constraints"));
            if (constraints == null) {
                return PinConversion.empty("pin_constraints_invalid");
            }
            if (!constraints.empty()) {
                builder.constraints(constraints.min(), constraints.max(), constraints.step());
            }
        }
        if (presentation.containsKey("visibleWhen")) {
            if (!(presentation.get("visibleWhen") instanceof Map<?, ?> conditions)) {
                return PinConversion.empty("pin_visibility_invalid");
            }
            for (Map.Entry<?, ?> condition : conditions.entrySet()) {
                if (!(condition.getKey() instanceof String input) || input.isBlank()
                    || !(condition.getValue() instanceof String expected) || expected.isBlank()) {
                    return PinConversion.empty("pin_visibility_invalid");
                }
                builder.visibleWhen(input, expected);
            }
        }
        return PinConversion.converted(builder.build());
    }

    private static RepeatableCatalog repeatables(Map<String, Object> descriptor, List<?> pinEntries,
                                                  List<ReSyncGenericDescriptorProjection.Pin> projectedPins) {
        Object rawGroups = descriptor.get("repeatables");
        if (pinEntries.size() != projectedPins.size()) {
            return null;
        }
        Map<String, Map<String, Object>> pins = new LinkedHashMap<>();
        for (Object entry : pinEntries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                return null;
            }
            Map<String, Object> pin = stringMap(raw);
            String id = text(pin.get("id"));
            if (id.isBlank() || pins.putIfAbsent(id, pin) != null) {
                return null;
            }
        }
        if (rawGroups == null) {
            return pins.values().stream().noneMatch(pin -> pin.containsKey("repeatable"))
                ? new RepeatableCatalog(Map.of()) : null;
        }
        if (!(rawGroups instanceof Collection<?> groups)) {
            return null;
        }
        Map<String, RepeatableMetadata> metadataByPin = new LinkedHashMap<>();
        Set<String> groupsSeen = new HashSet<>();
        for (Object value : groups) {
            if (!(value instanceof Map<?, ?> raw)) {
                return null;
            }
            Map<String, Object> group = stringMap(raw);
            String groupId = text(group.get("id"));
            String title = text(group.get("title"));
            Integer minimum = exactInteger(group.get("minimum"));
            Integer maximum = exactInteger(group.get("maximum"));
            Boolean ordered = group.get("ordered") instanceof Boolean flag ? flag : null;
            if (!"repeatable".equals(text(group.get("kind"))) || groupId.isBlank() || title.isBlank()
                || minimum == null || maximum == null || minimum < 0 || maximum < minimum || ordered == null
                || !groupsSeen.add(groupId) || !(group.get("members") instanceof Collection<?> members)
                || members.isEmpty()) {
                return null;
            }
            RepeatableMetadata metadata = new RepeatableMetadata(groupId, minimum, maximum, title, ordered);
            Set<String> memberIds = new HashSet<>();
            for (Object memberValue : members) {
                if (!(memberValue instanceof Map<?, ?> member) || !exactKeys(member, "pinId", "direction", "type")) {
                    return null;
                }
                String pinId = text(member.get("pinId"));
                Map<String, Object> pin = pins.get(pinId);
                if (pin == null || !memberIds.add(pinId)
                    || !Objects.equals(text(member.get("direction")), text(pin.get("direction")))
                    || !Objects.equals(CanonicalJson.canonicalize(member.get("type")),
                        CanonicalJson.canonicalize(pin.get("type")))
                    || metadataByPin.putIfAbsent(pinId, metadata) != null
                    || !repeatableIntentMatches(pin.get("repeatable"), metadata)) {
                    return null;
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : pins.entrySet()) {
            Object intentValue = entry.getValue().get("repeatable");
            if (intentValue == null && !metadataByPin.containsKey(entry.getKey())) {
                continue;
            }
            if (!(intentValue instanceof Map<?, ?> intent)
                || !validRepeatableIntentKeys(intent)) {
                return null;
            }
            boolean enabled = Boolean.TRUE.equals(intent.get("enabled"));
            if (enabled != metadataByPin.containsKey(entry.getKey())) {
                return null;
            }
            if (!enabled && (!Boolean.FALSE.equals(intent.get("ordered"))
                || exactInteger(intent.get("minimum")) == null || exactInteger(intent.get("minimum")) != 0
                || exactInteger(intent.get("maximum")) == null || exactInteger(intent.get("maximum")) != 0
                || intent.get("groupId") != null)) {
                return null;
            }
        }
        return new RepeatableCatalog(metadataByPin);
    }

    private static boolean repeatableIntentMatches(Object value, RepeatableMetadata metadata) {
        if (!(value instanceof Map<?, ?> intent)
            || !validRepeatableIntentKeys(intent) || !intent.containsKey("groupId")) {
            return false;
        }
        return Boolean.TRUE.equals(intent.get("enabled"))
            && metadata.groupId().equals(text(intent.get("groupId")))
            && Objects.equals(metadata.minimum(), exactInteger(intent.get("minimum")))
            && Objects.equals(metadata.maximum(), exactInteger(intent.get("maximum")))
            && Objects.equals(metadata.ordered(), intent.get("ordered"));
    }

    private static boolean validRepeatableIntentKeys(Map<?, ?> intent) {
        Set<String> allowed = Set.of("enabled", "minimum", "maximum", "ordered", "groupId");
        return intent.keySet().stream().allMatch(key -> key instanceof String text && allowed.contains(text))
            && List.of("enabled", "minimum", "maximum", "ordered").stream().allMatch(intent::containsKey);
    }

    private static Integer exactInteger(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double numeric = number.doubleValue();
        int integer = number.intValue();
        return Double.isFinite(numeric) && numeric == integer ? integer : null;
    }

    private static DefaultProjection defaultValue(Object value, Object type, FlowTypeRef expectedType,
                                                  TypeExpr expectedTypedType) {
        if (!(value instanceof Map<?, ?> typed)) {
            String compatibility = literalText(value, type);
            return value == null || compatibility != null ? new DefaultProjection(compatibility, null) : null;
        }
        if (!Objects.equals(type, typed.get("type"))) {
            return null;
        }
        FlowTypeRef defaultType = typeRef(typed.get("type"), expectedTypedType);
        if (defaultType == null || !defaultType.equals(expectedType)) {
            return null;
        }
        String state = text(typed.get("state"));
        String variantId = typed.containsKey("variantId") ? text(typed.get("variantId")) : null;
        if (typed.containsKey("variantId") && variantId.isBlank()
            || !validDefaultVariant(expectedTypedType, state, variantId)) {
            return null;
        }
        Object material;
        String compatibility;
        if ("absent".equals(state) || "null".equals(state)) {
            if (typed.containsKey("value") || typed.containsKey("locator") || typed.containsKey("variantId")) {
                return null;
            }
            material = null;
            compatibility = "null".equals(state) ? "null" : null;
        } else if ("value".equals(state)) {
            if (!typed.containsKey("value") || typed.containsKey("locator") || typed.get("value") == null) {
                return null;
            }
            material = typed.get("value");
            compatibility = literalText(material, type);
            if (compatibility == null) {
                return null;
            }
        } else if ("locator".equals(state)) {
            if (!resourceDefaultType(expectedTypedType, variantId)
                || !(typed.get("locator") instanceof Map<?, ?> locator) || typed.containsKey("value")) {
                return null;
            }
            material = locator;
            compatibility = text(locator.get("id"));
            if (compatibility.isBlank()) {
                compatibility = CanonicalJson.canonicalize(locator);
            }
        } else if ("opaque".equals(state)) {
            if (!typed.containsKey("value") || typed.containsKey("locator")
                || !opaqueDefaultType(expectedTypedType, variantId)) {
                return null;
            }
            material = typed.get("value");
            compatibility = null;
        } else {
            return null;
        }
        Map<String, Object> unknown = typedDefaultUnknown(typed);
        return unknown == null ? null : new DefaultProjection(compatibility,
            new NodeDefinition.TypedDefault(state, defaultType, material, variantId, unknown));
    }

    private static boolean validDefaultVariant(TypeExpr type, String state, String variantId) {
        boolean tagged = "value".equals(state) || "locator".equals(state) || "opaque".equals(state);
        if (type instanceof TypeExpr.UnionType union) {
            if (!tagged || variantId == null) {
                return !tagged && variantId == null;
            }
            try {
                union.variant(variantId);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return variantId == null;
    }

    private static boolean opaqueDefaultType(TypeExpr type, String variantId) {
        TypeExpr selected = selectedDefaultType(type, variantId);
        return selected instanceof TypeExpr.OpaqueType;
    }

    private static boolean resourceDefaultType(TypeExpr type, String variantId) {
        TypeExpr selected = selectedDefaultType(type, variantId);
        return selected instanceof TypeExpr.ResourceType
            || selected instanceof TypeExpr.OptionalType optional && resourceDefaultType(optional.element(), null);
    }

    private static TypeExpr selectedDefaultType(TypeExpr type, String variantId) {
        if (type instanceof TypeExpr.UnionType union) {
            try {
                return union.variant(variantId).type();
            } catch (RuntimeException exception) {
                return null;
            }
        }
        return type;
    }

    private static Map<String, Object> typedDefaultUnknown(Map<?, ?> typed) {
        Set<String> known = Set.of("state", "type", "variantId", "value", "locator");
        LinkedHashMap<String, Object> unknown = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : typed.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return null;
            }
            if (!known.contains(key)) {
                unknown.put(key, entry.getValue());
            }
        }
        return unknown;
    }

    private static String literalText(Object value, Object type) {
        if (value instanceof Map<?, ?> typed) {
            if (!type.equals(typed.get("type"))) {
                return null;
            }
            if ("null".equals(typed.get("state"))) {
                return "null";
            }
            if (!"value".equals(typed.get("state")) || !typed.containsKey("value")) {
                return null;
            }
            value = typed.get("value");
        }
        if (value instanceof String text) {
            return text;
        }
        return value == null ? null : CanonicalJson.canonicalize(value);
    }

    private static ConstraintValues constraints(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Double min = null;
        Double max = null;
        Double step = null;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                || !List.of("min", "max", "step").contains(key)) {
                return null;
            }
            if (!(entry.getValue() instanceof Number number)) {
                return null;
            }
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                return null;
            }
            switch (key) {
                case "min" -> min = numeric;
                case "max" -> max = numeric;
                case "step" -> step = numeric;
                default -> {
                    return null;
                }
            }
        }
        if (min != null && max != null && min > max || step != null && step <= 0.0) {
            return null;
        }
        return new ConstraintValues(min, max, step);
    }

    private static FlowTypeRef typeRef(Object value, TypeExpr typedType) {
        String type = canonicalTypeExpression(value);
        type = switch (type.toLowerCase()) {
            case "text", "string" -> "string";
            case "bool" -> "boolean";
            case "json" -> "json_object";
            default -> type;
        };
        if (type.isBlank()) {
            return null;
        }
        try {
            FlowTypeRef parsed = FlowTypeRef.parse(type);
            return fullyQualifiedResourceRefs(parsed) && supportedTypedType(typedType) ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean supportedTypedType(TypeExpr type) {
        return switch (type) {
            case TypeExpr.Named named -> {
                if ("type".equals(named.reference().ownerId())) {
                    yield named.arguments().isEmpty();
                }
                String identity = "builtin".equals(named.reference().ownerId())
                    ? named.reference().localId() : named.reference().ownerId() + ":" + named.reference().localId();
                yield FlowDataType.fromString(identity).isResolved()
                    && named.arguments().stream().allMatch(ReSyncGenericWidgetCapabilities::supportedTypedType);
            }
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.OptionalType optional -> supportedTypedType(optional.element());
            case TypeExpr.ListType list -> supportedTypedType(list.element());
            case TypeExpr.MapType map -> supportedTypedType(map.key()) && supportedTypedType(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().allMatch(ReSyncGenericWidgetCapabilities::supportedTypedType);
            case TypeExpr.ResultType result -> supportedTypedType(result.success()) && supportedTypedType(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type)
                .allMatch(ReSyncGenericWidgetCapabilities::supportedTypedType);
            case TypeExpr.OpaqueType ignored -> true;
        };
    }

    private static boolean fullyQualifiedResourceRefs(FlowTypeRef type) {
        if ("resource_reference".equals(type.getTypeId())) {
            return type.getArguments().size() == 1 && qualifiedResourceIdentity(type.getArguments().getFirst());
        }
        return type.getArguments().stream().allMatch(ReSyncGenericWidgetCapabilities::fullyQualifiedResourceRefs);
    }

    private static boolean qualifiedResourceIdentity(FlowTypeRef type) {
        if (!type.getArguments().isEmpty()) {
            return false;
        }
        String identity = type.getTypeId();
        int separator = identity.indexOf(':');
        if (separator <= 0 || separator == identity.length() - 1) {
            return false;
        }
        try {
            TypeReference.of(identity.substring(0, separator), identity.substring(separator + 1));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static String canonicalTypeExpression(Object value) {
        if (value instanceof String text) {
            String expression = text(text);
            if (expression.isBlank()) {
                return "";
            }
            try {
                FlowTypeRef parsed = FlowTypeRef.parse(expression);
                return parsed.isResolved() ? parsed.toString() : "";
            } catch (RuntimeException exception) {
                return "";
            }
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return "";
        }
        String kind = text(raw.get("kind")).toLowerCase(Locale.ROOT);
        if (kind.isBlank()) {
            return "";
        }
        if (kind.equals("named")) {
            if (!exactKeys(raw, "kind", "type", "arguments")) {
                return "";
            }
            Map<?, ?> reference = raw.get("type") instanceof Map<?, ?> referenceValue ? referenceValue : Map.of();
            String owner = text(reference.get("ownerId"));
            String id = text(reference.get("localId"));
            if (owner.isBlank() || id.isBlank()) {
                return "";
            }
            String qualified = qualifiedType(owner, id);
            if (qualified.isBlank()) {
                return "";
            }
            List<String> arguments = new ArrayList<>();
            Object argumentValue = raw.get("arguments");
            if (argumentValue != null && !(argumentValue instanceof Collection<?>)) {
                return "";
            }
            if (argumentValue instanceof Collection<?> values) {
                for (Object argument : values) {
                    String expression = canonicalTypeExpression(argument);
                    if (expression.isBlank()) {
                        return "";
                    }
                    arguments.add(expression);
                }
            }
            return arguments.isEmpty() ? qualified : qualified + "<" + String.join(",", arguments) + ">";
        }
        if (kind.equals("resource")) {
            if (!exactKeys(raw, "kind", "resourceType")) {
                return "";
            }
            Map<?, ?> reference = raw.get("resourceType") instanceof Map<?, ?> referenceValue ? referenceValue : Map.of();
            String owner = text(reference.get("ownerId"));
            String id = text(reference.get("localId"));
            String qualified = qualifiedResourceType(owner, id);
            return owner.isBlank() || id.isBlank() || qualified.isBlank() ? "" : "resource_reference<" + qualified + ">";
        }
        if (kind.equals("optional") || kind.equals("list") || kind.equals("set")
            || kind.equals("queue") || kind.equals("stack")) {
            if (!exactKeys(raw, "kind", "element")) {
                return "";
            }
            String element = canonicalTypeExpression(raw.get("element"));
            return element.isBlank() ? "" : kind + "<" + element + ">";
        }
        if (kind.equals("map")) {
            if (!exactKeys(raw, "kind", "key", "value")) {
                return "";
            }
            String key = canonicalTypeExpression(raw.get("key"));
            String mapped = canonicalTypeExpression(raw.get("value"));
            return key.isBlank() || mapped.isBlank() ? "" : "map<" + key + "," + mapped + ">";
        }
        if (kind.equals("result")) {
            if (!exactKeys(raw, "kind", "success", "failure")) {
                return "";
            }
            String success = canonicalTypeExpression(raw.get("success"));
            String failure = canonicalTypeExpression(raw.get("failure"));
            return success.isBlank() || failure.isBlank() ? "" : "result<" + success + "," + failure + ">";
        }
        if (kind.equals("tuple")) {
            if (!exactKeys(raw, "kind", "elements")) {
                return "";
            }
            return collectionType("tuple", raw.get("elements"), 1, 16);
        }
        if (kind.equals("union")) {
            if (!exactKeys(raw, "kind", "variants")) {
                return "";
            }
            if (!(raw.get("variants") instanceof Collection<?> variants) || variants.size() < 2 || variants.size() > 16) {
                return "";
            }
            List<String> expressions = new ArrayList<>(variants.size());
            Set<String> ids = new HashSet<>();
            for (Object variantValue : variants) {
                if (!(variantValue instanceof Map<?, ?> variant)) {
                    return "";
                }
                if (!exactKeys(variant, "variantId", "type")) {
                    return "";
                }
                String id = text(variant.get("variantId"));
                String expression = canonicalTypeExpression(variant.get("type"));
                if (id.isBlank() || expression.isBlank() || !ids.add(id)) {
                    return "";
                }
                expressions.add("variant<" + id + "," + expression + ">");
            }
            expressions.sort(String::compareTo);
            return "union<" + String.join(",", expressions) + ">";
        }
        if (kind.equals("opaque")) {
            if (!exactKeys(raw, "kind", "raw", "type") || !Boolean.TRUE.equals(raw.get("raw"))) {
                return "";
            }
            if (!(raw.get("type") instanceof Map<?, ?> reference)) {
                return "";
            }
            String owner = text(reference.get("ownerId"));
            String id = text(reference.get("localId"));
            String qualified = qualifiedType(owner, id);
            return owner.isBlank() || id.isBlank() || qualified.isBlank() ? "" : "opaque<" + qualified + ">";
        }
        if (!exactKeys(raw, "kind")) {
            return "";
        }
        String primitive = switch (kind) {
            case "text" -> "string";
            case "bool" -> "boolean";
            case "json" -> "json_object";
            default -> kind;
        };
        return FlowDataType.fromString(primitive).isResolved() ? primitive : "";
    }

    private static String collectionType(String kind, Object value, int minimum, int maximum) {
        if (!(value instanceof Collection<?> values) || values.size() < minimum || values.size() > maximum) {
            return "";
        }
        List<String> expressions = new ArrayList<>(values.size());
        for (Object item : values) {
            String expression = canonicalTypeExpression(item);
            if (expression.isBlank()) {
                return "";
            }
            expressions.add(expression);
        }
        return kind + "<" + String.join(",", expressions) + ">";
    }

    private static String qualifiedType(String owner, String id) {
        try {
            TypeReference reference = TypeReference.of(owner, id);
            return "builtin".equals(reference.ownerId()) && FlowDataType.fromString(reference.localId()).isResolved()
                ? reference.localId() : reference.ownerId() + ":" + reference.localId();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String qualifiedResourceType(String owner, String id) {
        try {
            TypeReference reference = TypeReference.of(owner, id);
            return reference.ownerId() + ":" + reference.localId();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static boolean exactKeys(Map<?, ?> value, String... keys) {
        Set<String> expected = Set.of(keys);
        return value.keySet().stream().allMatch(key -> key instanceof String text && expected.contains(text))
            && expected.stream().allMatch(value::containsKey);
    }

    private static NodeDefinition.WidgetType widget(ReSyncGenericDescriptorProjection.EditorKind kind) {
        return switch (kind) {
            case TEXT -> NodeDefinition.WidgetType.TEXT;
            case NUMBER -> NodeDefinition.WidgetType.NUMBER;
            case BOOLEAN -> NodeDefinition.WidgetType.TOGGLE;
            case SELECT -> NodeDefinition.WidgetType.DROPDOWN;
            case JSON -> NodeDefinition.WidgetType.MULTILINE;
            case UNSUPPORTED -> NodeDefinition.WidgetType.AUTO;
        };
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Descriptor map keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private record ConstraintValues(Double min, Double max, Double step) {
        private boolean empty() {
            return min == null && max == null && step == null;
        }
    }

    private record DefaultProjection(String compatibilityValue, NodeDefinition.TypedDefault typedDefault) {
    }

    private record RepeatableMetadata(String groupId, int minimum, int maximum, String itemLabel,
                                      boolean ordered) {
    }

    private record RepeatableCatalog(Map<String, RepeatableMetadata> pins) {
        private RepeatableCatalog {
            pins = Map.copyOf(pins);
        }
    }

    private record PinConversion(NodeDefinition.PinDefinition definition, String reason) {
        private static PinConversion converted(NodeDefinition.PinDefinition definition) {
            return new PinConversion(definition, "");
        }

        private static PinConversion empty(String reason) {
            return new PinConversion(null, reason);
        }
    }

    public record WidgetDefinition(NodeDefinition definition, boolean readOnly, String identity,
                                   ReSyncGenericDescriptorProjection.Inspector inspector) {
        public WidgetDefinition(NodeDefinition definition, boolean readOnly, String identity) {
            this(definition, readOnly, identity, ReSyncGenericDescriptorProjection.Inspector.empty());
        }

        public WidgetDefinition {
            if (definition == null || identity == null || identity.isBlank()) {
                throw new IllegalArgumentException("Generic widget identity and definition are required");
            }
            inspector = inspector == null ? ReSyncGenericDescriptorProjection.Inspector.empty() : inspector;
        }
    }

    public record Conversion(Optional<WidgetDefinition> widget, String reason) {
        public Conversion {
            if (widget == null || reason == null || widget.isPresent() != reason.isEmpty()) {
                throw new IllegalArgumentException("Widget conversion requires a widget or rejection reason");
            }
        }
    }
}
