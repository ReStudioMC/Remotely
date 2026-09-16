package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class AdvertisedOptionSourceResolver {
    private AdvertisedOptionSourceResolver() {
    }

    static Optional<Resolved> resolve(CatalogAuthoringPublication publication,
                                      ContractRef<InspectorFieldId> reference) {
        if (publication == null || reference == null || !publication.compatible()) {
            return Optional.empty();
        }
        ContractRef<CapabilityId> entryReference = ContractRef.of(reference.owner(),
            CapabilityId.of(reference.id().canonicalText()));
        Resolved selected = null;
        for (CatalogAuthoringPublication.Entry entry : publication.optionSources()) {
            if (entry == null || !entry.editable() || entry.opaque()) {
                continue;
            }
            if (!entryReference.equals(entry.reference())) {
                continue;
            }
            InspectorOptionSource source = decode(entry);
            ContractRef<InspectorFieldId> sourceReference = ContractRef.of(entry.reference().owner(), source.id());
            if (selected != null) {
                throw new IllegalArgumentException("Catalog advertises a duplicate option source reference");
            }
            selected = new Resolved(sourceReference, source);
        }
        if (selected == null || !selected.reference().equals(reference)) {
            return Optional.empty();
        }
        return Optional.of(selected);
    }

    private static InspectorOptionSource decode(CatalogAuthoringPublication.Entry entry) {
        JsonValue decoded = CanonicalCodec.decode(entry.data().canonicalBytes());
        JsonValue.JsonObject object = object(decoded, "Option source");
        InspectorFieldId id = InspectorFieldId.of(text(object, "id"));
        if (!entry.reference().id().canonicalText().equals(id.canonicalText())) {
            throw new IllegalArgumentException("Option source ID does not match its advertised reference");
        }
        return new InspectorOptionSource(
            id,
            text(object, "title"),
            text(object, "description"),
            decodeType(require(object, "valueType")),
            decodeQuerySchema(require(object, "querySchema")),
            IdentityCodec.decodeReference(require(object, "capability"), CapabilityId::new),
            integer(object, "pageLimit"),
            text(object, "invalidationKey")
        );
    }

    private static OptionQuerySchemaV1 decodeQuerySchema(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Option query schema");
        if (integer(object, "schemaVersion") != OptionQuerySchemaV1.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported option query schema version");
        }
        JsonValue resourceValue = present(object, "resource");
        OptionQuerySchemaV1.ResourceSlot resource = resourceValue instanceof JsonValue.JsonNull
            ? null : decodeResourceSlot(resourceValue);
        return new OptionQuerySchemaV1(resource, decodeFields(require(object, "context"), "context"),
            decodeFields(require(object, "dependencies"), "dependencies"),
            unknown(object, Set.of("schemaVersion", "resource", "context", "dependencies")));
    }

    private static OptionQuerySchemaV1.ResourceSlot decodeResourceSlot(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Option query resource");
        TypeExpr type = decodeType(require(object, "type"));
        if (!(type instanceof TypeExpr.ResourceType resourceType)) {
            throw new IllegalArgumentException("Option query resource type must be a resource");
        }
        return new OptionQuerySchemaV1.ResourceSlot(resourceType, bool(object, "required"),
            unknown(object, Set.of("type", "required")));
    }

    private static Map<String, OptionQuerySchemaV1.Field> decodeFields(JsonValue value, String name) {
        JsonValue.JsonObject object = object(value, "Option query " + name);
        Map<String, OptionQuerySchemaV1.Field> fields = new LinkedHashMap<>();
        object.fields().forEach((fieldName, fieldValue) -> {
            JsonValue.JsonObject field = object(fieldValue, "Option query " + name + " field");
            JsonValue defaultValue = present(field, "defaultValue");
            fields.put(fieldName, new OptionQuerySchemaV1.Field(decodeType(require(field, "type")),
                bool(field, "required"), defaultValue instanceof JsonValue.JsonNull
                    ? null : decodeTypedValue(defaultValue),
                unknown(field, Set.of("type", "required", "defaultValue"))));
        });
        return Map.copyOf(fields);
    }

    private static TypeExpr decodeType(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Type expression");
        String kind = text(object, "kind");
        Map<String, Object> unknown = unknown(object, typeFields(kind));
        return switch (kind) {
            case "named" -> new TypeExpr.Named(decodeTypeReference(require(object, "type")),
                array(object, "arguments").stream().map(AdvertisedOptionSourceResolver::decodeType).toList(), unknown);
            case "optional" -> new TypeExpr.OptionalType(decodeType(require(object, "element")), unknown);
            case "list" -> new TypeExpr.ListType(decodeType(require(object, "element")), unknown);
            case "map" -> new TypeExpr.MapType(decodeType(require(object, "key")),
                decodeType(require(object, "value")), unknown);
            case "tuple" -> new TypeExpr.TupleType(array(object, "elements").stream()
                .map(AdvertisedOptionSourceResolver::decodeType).toList(), unknown);
            case "result" -> new TypeExpr.ResultType(decodeType(require(object, "success")),
                decodeType(require(object, "failure")), unknown);
            case "resource" -> new TypeExpr.ResourceType(decodeTypeReference(require(object, "resourceType")), unknown);
            case "union" -> new TypeExpr.UnionType(array(object, "variants").stream()
                .map(AdvertisedOptionSourceResolver::decodeVariant).toList(), unknown);
            case "opaque" -> {
                if (!bool(object, "raw")) {
                    throw new IllegalArgumentException("Opaque option types require raw=true");
                }
                yield new TypeExpr.OpaqueType(decodeTypeReference(require(object, "type")), unknown);
            }
            default -> throw new IllegalArgumentException("Unknown advertised option type: " + kind);
        };
    }

    private static TypeExpr.UnionVariant decodeVariant(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Union variant");
        return new TypeExpr.UnionVariant(
            text(object, "variantId"),
            decodeType(require(object, "type")),
            optionalText(object, "displayName"),
            optionalText(object, "description"),
            unknown(object, Set.of("variantId", "type", "displayName", "description"))
        );
    }

    private static TypeReference decodeTypeReference(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Type reference");
        return new TypeReference(text(object, "ownerId"), text(object, "localId"),
            unknown(object, Set.of("ownerId", "localId")));
    }

    private static TypedValue decodeTypedValue(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Typed value");
        TypeExpr type = decodeType(require(object, "type"));
        TypedValue.State state = switch (text(object, "state")) {
            case "absent" -> TypedValue.State.ABSENT;
            case "null" -> TypedValue.State.NULL;
            case "value" -> TypedValue.State.VALUE;
            case "locator" -> TypedValue.State.LOCATOR;
            case "opaque" -> TypedValue.State.OPAQUE;
            default -> throw new IllegalArgumentException("Unknown typed value state");
        };
        String variantId = optionalText(object, "variantId");
        JsonValue valueField = object.value("value");
        JsonValue locatorField = object.value("locator");
        boolean hasValue = valueField != null;
        boolean hasLocator = locatorField != null;
        if ((state == TypedValue.State.ABSENT || state == TypedValue.State.NULL)
            && (hasValue || hasLocator || variantId != null)
            || state == TypedValue.State.VALUE && (!hasValue || hasLocator)
            || state == TypedValue.State.LOCATOR && (hasValue || !hasLocator)
            || state == TypedValue.State.OPAQUE && (!hasValue || hasLocator)) {
            throw new IllegalArgumentException("Typed value state does not match its material");
        }
        TypeExpr selected = selectType(type, variantId, state);
        Object material = hasValue ? decodeRawValue(selected, valueField.toJava()) : null;
        return new TypedValue(type, state, variantId, material,
            hasLocator ? IdentityCodec.decodeLocator(locatorField) : null,
            unknown(object, Set.of("state", "type", "variantId", "value", "locator")));
    }

    private static TypeExpr selectType(TypeExpr type, String variantId, TypedValue.State state) {
        if (type instanceof TypeExpr.UnionType union && state != TypedValue.State.ABSENT
            && state != TypedValue.State.NULL) {
            if (variantId == null) {
                throw new IllegalArgumentException("Union typed values require a variant ID");
            }
            return union.variant(variantId).type();
        }
        if (!(type instanceof TypeExpr.UnionType) && variantId != null) {
            throw new IllegalArgumentException("Only union typed values may carry a variant ID");
        }
        return type;
    }

    private static Object decodeRawValue(TypeExpr type, Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case TypeExpr.ResourceType ignored -> IdentityCodec.decodeLocator(JsonValue.fromJava(raw));
            case TypeExpr.OptionalType optional -> decodeRawValue(optional.element(), raw);
            case TypeExpr.ListType list -> decodeListRaw(raw, list.element());
            case TypeExpr.MapType map -> decodeMapRaw(raw, map);
            case TypeExpr.TupleType tuple -> decodeTupleRaw(raw, tuple);
            case TypeExpr.ResultType result -> decodeResultRaw(raw, result);
            case TypeExpr.Named named -> decodeNamedRaw(raw, named);
            case TypeExpr.UnionType ignored -> throw new IllegalArgumentException("Union branch type is unresolved");
            case TypeExpr.OpaqueType ignored -> raw;
        };
    }

    private static Object decodeNamedRaw(Object raw, TypeExpr.Named type) {
        if ("builtin".equals(type.reference().ownerId()) && type.arguments().isEmpty()) {
            return switch (type.reference().localId()) {
                case "uuid" -> raw instanceof String text ? canonicalUuid(text) : fail("Typed UUID must be text");
                case "integer" -> integral(raw);
                case "number" -> decimal(raw);
                default -> raw;
            };
        }
        if (raw instanceof Map<?, ?> map && containsResource(type)
            && map.keySet().containsAll(Set.of("serverId", "type", "id"))) {
            return IdentityCodec.decodeLocator(JsonValue.fromJava(map));
        }
        return raw;
    }

    private static List<Object> decodeListRaw(Object raw, TypeExpr element) {
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("Typed list value must be an array");
        }
        List<Object> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(value == null ? null : decodeRawValue(element, value)));
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> decodeMapRaw(Object raw, TypeExpr.MapType type) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Typed map value must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            Object decodedKey = decodeRawValue(type.key(), key);
            if (!(decodedKey instanceof String name)) {
                throw new IllegalArgumentException("Typed map keys must decode to text");
            }
            result.put(name, value == null ? null : decodeRawValue(type.value(), value));
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<Object> decodeTupleRaw(Object raw, TypeExpr.TupleType type) {
        if (!(raw instanceof List<?> values) || values.size() != type.elements().size()) {
            throw new IllegalArgumentException("Typed tuple value has the wrong shape");
        }
        List<Object> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            result.add(value == null ? null : decodeRawValue(type.elements().get(index), value));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> decodeResultRaw(Object raw, TypeExpr.ResultType type) {
        if (!(raw instanceof Map<?, ?> values) || !(values.get("success") instanceof Boolean success)
            || !values.containsKey("value")) {
            throw new IllegalArgumentException("Typed result value has the wrong shape");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("Typed result keys must be text");
            }
            result.put(name, "value".equals(name) && value != null
                ? decodeRawValue(success ? type.success() : type.failure(), value) : value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static boolean containsResource(TypeExpr expression) {
        return switch (expression) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(AdvertisedOptionSourceResolver::containsResource);
            case TypeExpr.OptionalType optional -> containsResource(optional.element());
            case TypeExpr.ListType list -> containsResource(list.element());
            case TypeExpr.MapType map -> containsResource(map.key()) || containsResource(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(AdvertisedOptionSourceResolver::containsResource);
            case TypeExpr.ResultType result -> containsResource(result.success()) || containsResource(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type)
                .anyMatch(AdvertisedOptionSourceResolver::containsResource);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        throw new IllegalArgumentException("Typed number must be numeric");
    }

    private static BigInteger integral(Object value) {
        try {
            return decimal(value).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Typed integer must be integral", exception);
        }
    }

    private static UUID canonicalUuid(String value) {
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value)) {
            throw new IllegalArgumentException("Typed UUID must be canonical");
        }
        return result;
    }

    private static <T> T fail(String message) {
        throw new IllegalArgumentException(message);
    }

    private static Set<String> typeFields(String kind) {
        return switch (kind) {
            case "named" -> Set.of("kind", "type", "arguments");
            case "optional", "list" -> Set.of("kind", "element");
            case "map" -> Set.of("kind", "key", "value");
            case "tuple" -> Set.of("kind", "elements");
            case "result" -> Set.of("kind", "success", "failure");
            case "resource" -> Set.of("kind", "resourceType");
            case "union" -> Set.of("kind", "variants");
            case "opaque" -> Set.of("kind", "type", "raw");
            default -> Set.of("kind");
        };
    }

    private static JsonValue require(JsonValue.JsonObject object, String field) {
        JsonValue value = present(object, field);
        if (value == null || value instanceof JsonValue.JsonNull) {
            throw new IllegalArgumentException("Option source is missing " + field);
        }
        return value;
    }

    private static JsonValue present(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Option source is missing " + field);
        }
        return value;
    }

    private static String text(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonString text)) {
            throw new IllegalArgumentException("Option source " + field + " must be text");
        }
        return text.value();
    }

    private static String optionalText(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null || value instanceof JsonValue.JsonNull) {
            return null;
        }
        if (!(value instanceof JsonValue.JsonString text)) {
            throw new IllegalArgumentException("Option source " + field + " must be text");
        }
        return text.value();
    }

    private static int integer(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Option source " + field + " must be a number");
        }
        return number.value().intValueExact();
    }

    private static boolean bool(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonBoolean bool)) {
            throw new IllegalArgumentException("Option source " + field + " must be a boolean");
        }
        return bool.value();
    }

    private static List<JsonValue> array(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Option source " + field + " must be an array");
        }
        return List.copyOf(array.values());
    }

    private static JsonValue.JsonObject object(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, Set<String> known) {
        Map<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                values.put(key, value.toJava());
            }
        });
        return Map.copyOf(values);
    }

    record Resolved(ContractRef<InspectorFieldId> reference, InspectorOptionSource source) {
        Resolved {
            Objects.requireNonNull(reference, "Option source reference is required");
            Objects.requireNonNull(source, "Option source is required");
        }
    }
}
