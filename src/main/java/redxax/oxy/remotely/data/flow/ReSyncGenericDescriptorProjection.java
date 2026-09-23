package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskIdentities;

import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ReSyncGenericDescriptorProjection {
    private ReSyncGenericDescriptorProjection() {
    }

    public static List<Projection> open(Collection<CatalogCachePublication.Entry> entries,
                                        ClientCapabilities capabilities) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
            .map(entry -> open(entry, capabilities))
            .flatMap(Optional::stream)
            .toList();
    }

    public static Optional<Projection> open(CatalogCachePublication.Entry entry,
                                            ClientCapabilities capabilities) {
        long startedAt = System.nanoTime();
        if (entry == null) {
            return Optional.empty();
        }
        ClientCapabilities supported = capabilities != null ? capabilities : ClientCapabilities.none();
        if (entry.tombstone()) {
            return traced(entry, Optional.of(Projection.tombstone(entry)), startedAt, "tombstoned");
        }
        String canonical = entry.data().canonicalText();
        try {
            Object parsed = entry.data().canonicalValue().toJava();
            if (!(parsed instanceof Map<?, ?> raw)) {
                return traced(entry, Optional.of(Projection.invalid(entry, canonical,
                    "The catalog descriptor is not an object.")), startedAt, "descriptor_not_object");
            }
            Map<String, Object> descriptor = freezeMap(raw);
            String descriptorId = text(descriptor.get("id"));
            if (!entry.definitionKey().id().value().equals(descriptorId)) {
                return traced(entry, Optional.of(Projection.invalid(entry, canonical,
                    "The catalog descriptor identity does not match its typed key.")), startedAt,
                    "typed_identity_mismatch");
            }
            List<Field> fields = new ArrayList<>();
            List<Pin> pins = parsePins(descriptor.get("pins"), fields, supported);
            Inspector inspector = parseInspector(descriptor.get("inspector"), fields, supported,
                entry.definitionKey().owner());
            boolean usable = entry.state() == CatalogCacheState.ACTIVE && !entry.opaque();
            boolean editable = usable && fields.stream().allMatch(Field::editable);
            Status status = !usable ? status(entry) : editable ? Status.ACTIVE : Status.READ_ONLY;
            String reason = status == Status.ACTIVE ? "" : reason(status, fields);
            return traced(entry, Optional.of(new Projection(entry.definitionKey(), entry.revision(), status, !editable,
                descriptor, canonical, fields, pins, inspector, entry.requiredCapabilities(), entry.unknown(), reason)),
                startedAt, status.name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return traced(entry, Optional.of(Projection.invalid(entry, canonical,
                "The catalog descriptor cannot be interpreted by this client.")), startedAt,
                "descriptor_interpretation_failed:" + TaskIdentities.failureName(exception));
        }
    }

    private static Optional<Projection> traced(CatalogCachePublication.Entry entry, Optional<Projection> result,
                                               long startedAt, String outcome) {
        if (!ReSyncLifecycleDiagnostics.enabled()) return result;
        Projection projection = result.orElse(null);
        List<Field> fields = projection != null ? projection.fields() : List.of();
        List<String> unsupportedReasons = fields.stream().filter(field -> !field.editable())
            .map(Field::reason).filter(reason -> reason != null && !reason.isBlank()).distinct().toList();
        long nonPrimitiveUnsupported = fields.stream().filter(field -> !field.editable())
            .filter(field -> !primitiveTypeExpression(field.typeExpression())).count();
        ReSyncFlowClient.traceLifecycle("unresolved", "typed_descriptor_conversion",
            "resourceKey", entry.definitionKey().canonicalText(), "operation", "project_descriptor",
            "requestId", "", "correlationId", "", "traceId", "", "mutationId", "", "generation", -1L,
            "authorityEpoch", -1L, "revision", entry.revision(), "status",
            projection != null ? projection.status() : "empty", "readOnly",
            projection != null && projection.readOnly(), "fieldCount", fields.size(), "pinCount",
            projection != null ? projection.pins().size() : 0, "unsupportedFieldCount",
            fields.stream().filter(field -> !field.editable()).count(), "nonPrimitiveUnsupportedCount",
            nonPrimitiveUnsupported, "unsupportedReasons", String.join(";", unsupportedReasons), "reason", outcome,
            "rejectionReason", projection == null ? outcome : projection.reason(),
            "elapsedMs", elapsedMillis(startedAt));
        return result;
    }

    private static boolean primitiveTypeExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String normalized = expression.toLowerCase(Locale.ROOT);
        return Set.of("string", "text", "number", "integer", "float", "double", "boolean", "bool", "json",
            "json_object").contains(normalized);
    }

    private static long elapsedMillis(long startedAt) {
        return BrowserSafeState.nanosToMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static Status status(CatalogCachePublication.Entry entry) {
        if (entry.opaque()) {
            return Status.OPAQUE;
        }
        return entry.state() == CatalogCacheState.UNAVAILABLE ? Status.UNAVAILABLE : Status.READ_ONLY;
    }

    private static String reason(Status status, List<Field> fields) {
        return switch (status) {
            case OPAQUE -> "The descriptor is preserved but unavailable to this client.";
            case UNAVAILABLE -> "Required catalog capabilities are unavailable.";
            case READ_ONLY -> fields.stream().anyMatch(field -> !field.editable())
                ? "One or more fields have no supported generic editor capability."
                : "The catalog entry is read-only.";
            case INVALID -> "The descriptor is invalid or has an identity mismatch.";
            case TOMBSTONED -> "The catalog entry was removed by the server.";
            case ACTIVE -> "";
        };
    }

    private static List<Pin> parsePins(Object value, List<Field> fields, ClientCapabilities capabilities) {
        List<Pin> result = new ArrayList<>();
        if (!(value instanceof Collection<?> pins)) {
            return List.of();
        }
        for (Object item : pins) {
            if (!(item instanceof Map<?, ?> raw)) {
                fields.add(Field.unsupported("pin:unknown", "Unknown Pin", "The pin descriptor is not an object."));
                continue;
            }
            Map<String, Object> pin = freezeMap(raw);
            String id = text(pin.get("id"));
            if (id.isBlank()) {
                fields.add(Field.unsupported("pin:unknown", "Unknown Pin", "The pin has no stable identity."));
                continue;
            }
            Direction direction = direction(pin.get("direction"));
            Object type = pin.get("type");
            if (direction == null || type == null) {
                fields.add(Field.unsupported("pin:" + id, text(pin.get("displayName")),
                    "The pin descriptor does not declare a canonical direction and type."));
                continue;
            }
            ContractRef<InspectorFieldId> optionSource = reference(pin.get("optionSource"));
            result.add(new Pin(id, text(pin.get("displayName")), direction, type, text(pin.get("description")),
                optionSource));
            if (bareResourceType(type)) {
                fields.add(new Field("pin:" + id, text(pin.get("displayName")), text(pin.get("description")),
                    EditorKind.UNSUPPORTED, CanonicalJson.canonicalize(type), capability(pin.get("editor")), false,
                    "Resource pins require an owner-qualified resource type."));
                continue;
            }
            if (direction == Direction.INPUT && literalInput(pin)) {
                Field field = qualifiedResourceType(type) && optionSource != null
                    ? new Field("pin:" + id, text(pin.get("displayName")), text(pin.get("description")),
                        EditorKind.SELECT, CanonicalJson.canonicalize(type), capability(pin.get("editor")), true, "")
                    : field("pin:" + id, text(pin.get("displayName")), text(pin.get("description")),
                        pin.get("type"), pin.get("editor"), capabilities);
                String widget = text(presentation(pin).get("widget"));
                if (!supportsWidget(field.editorKind(), widget)) {
                    field = new Field(field.id(), field.title(), field.description(), EditorKind.UNSUPPORTED,
                        field.typeExpression(), field.capability(), false,
                        "The declared input widget is not supported by this client.");
                }
                fields.add(field);
            }
        }
        return List.copyOf(result);
    }

    private static boolean qualifiedResourceType(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return false;
        }
        Map<String, Object> type = freezeMap(raw);
        if (!"resource".equalsIgnoreCase(text(type.get("kind")))
            || !(type.get("resourceType") instanceof Map<?, ?> reference)) {
            return false;
        }
        return !text(reference.get("ownerId")).isBlank() && !text(reference.get("localId")).isBlank();
    }

    private static boolean bareResourceType(Object value) {
        if (value instanceof String expression) {
            String compact = expression.toLowerCase(Locale.ROOT);
            int offset = 0;
            while ((offset = compact.indexOf("resource_reference", offset)) >= 0) {
                int end = offset + "resource_reference".length();
                while (end < compact.length() && Character.isWhitespace(compact.charAt(end))) {
                    end++;
                }
                if (end == compact.length() || compact.charAt(end) != '<') {
                    return true;
                }
                offset = end;
            }
            return false;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return false;
        }
        Map<String, Object> type = freezeMap(raw);
        String kind = text(type.get("kind")).toLowerCase(Locale.ROOT);
        if ("resource".equals(kind)) {
            if (!(type.get("resourceType") instanceof Map<?, ?> reference)) {
                return true;
            }
            return text(reference.get("ownerId")).isBlank() || text(reference.get("localId")).isBlank();
        }
        if ("named".equals(kind)) {
            if (!(type.get("type") instanceof Map<?, ?> reference)) {
                return false;
            }
            String localId = text(reference.get("localId"));
            if (!"resource_reference".equalsIgnoreCase(localId)) {
                return false;
            }
            return !(type.get("arguments") instanceof Collection<?> arguments) || arguments.size() != 1
                || bareResourceType(arguments.iterator().next());
        }
        return switch (kind) {
            case "optional", "list", "set", "queue", "stack" -> bareResourceType(type.get("element"));
            case "map" -> bareResourceType(type.get("key")) || bareResourceType(type.get("value"));
            case "result" -> bareResourceType(type.get("success")) || bareResourceType(type.get("failure"));
            case "tuple" -> collectionHasBareResource(type.get("elements"));
            case "union" -> unionHasBareResource(type.get("variants"));
            default -> false;
        };
    }

    private static boolean collectionHasBareResource(Object value) {
        return value instanceof Collection<?> values && values.stream().anyMatch(ReSyncGenericDescriptorProjection::bareResourceType);
    }

    private static boolean unionHasBareResource(Object value) {
        if (!(value instanceof Collection<?> variants)) {
            return false;
        }
        return variants.stream().anyMatch(variant -> variant instanceof Map<?, ?> map && bareResourceType(map.get("type")));
    }

    private static boolean literalInput(Map<String, Object> pin) {
        if (flowType(pin.get("type"))) {
            return false;
        }
        Map<String, Object> presentation = presentation(pin);
        String widget = text(presentation.get("widget"));
        String editor = capability(pin.get("editor"));
        return primitiveTypeExpression(typeName(pin.get("type")))
            || "resource_reference".equals(typeName(pin.get("type")))
            || pin.get("default") != null || pin.get("defaultValue") != null
            || pin.get("optionSource") != null || presentation.get("optionSource") != null
            || !widget.isBlank() && !"auto".equalsIgnoreCase(widget)
            || presentation.get("options") instanceof Collection<?> options && !options.isEmpty()
            || !editor.isBlank() && !editor.endsWith("/generic-editor");
    }

    static Map<String, Object> presentation(Map<String, Object> pin) {
        if (!pin.containsKey("presentation")) {
            return pin;
        }
        if (!(pin.get("presentation") instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Pin presentation must be an object");
        }
        return freezeMap(raw);
    }

    private static boolean supportsWidget(EditorKind kind, String widget) {
        if (widget.isBlank() || "auto".equalsIgnoreCase(widget)) {
            return true;
        }
        String normalized = widget.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case TEXT -> Set.of("text", "multiline", "dropdown", "searchable_list", "color").contains(normalized);
            case NUMBER -> Set.of("number", "slider", "dropdown", "searchable_list").contains(normalized);
            case BOOLEAN -> Set.of("toggle", "dropdown", "searchable_list").contains(normalized);
            case SELECT -> Set.of("dropdown", "searchable_list").contains(normalized);
            case JSON -> "multiline".equals(normalized);
            case UNSUPPORTED -> false;
        };
    }

    private static Direction direction(Object value) {
        return switch (text(value).toLowerCase(Locale.ROOT)) {
            case "input" -> Direction.INPUT;
            case "output" -> Direction.OUTPUT;
            default -> null;
        };
    }

    private static Inspector parseInspector(Object value, List<Field> fields, ClientCapabilities capabilities,
                                            OwnerId owner) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Inspector.empty();
        }
        Map<String, Object> inspector = freezeMap(raw);
        Object sections = inspector.get("sections");
        if (!(sections instanceof Collection<?> values)) {
            return new Inspector(text(inspector.get("id")), text(inspector.get("title")),
                text(inspector.get("description")), List.of(), property(inspector, "bindings", "binding"),
                property(inspector, "visibility", "visibleWhen"), declaredReadOnly(inspector));
        }
        List<InspectorSection> projectedSections = new ArrayList<>();
        for (Object sectionValue : values) {
            if (!(sectionValue instanceof Map<?, ?> sectionRaw)) {
                fields.add(Field.unsupported("inspector:unknown", "Unknown Field", "The inspector section is not an object."));
                continue;
            }
            Map<String, Object> section = freezeMap(sectionRaw);
            Object rows = section.get("rows");
            if (!(rows instanceof Collection<?> rowValues)) {
                projectedSections.add(new InspectorSection(text(section.get("id")), text(section.get("title")),
                    text(section.get("description")), List.of(), property(section, "bindings", "binding"),
                    property(section, "visibility", "visibleWhen"), declaredReadOnly(section)));
                continue;
            }
            List<InspectorRow> projectedRows = new ArrayList<>();
            for (Object rowValue : rowValues) {
                if (!(rowValue instanceof Map<?, ?> rowRaw)) {
                    fields.add(Field.unsupported("inspector:unknown", "Unknown Field", "The inspector row is not an object."));
                    continue;
                }
                Map<String, Object> row = freezeMap(rowRaw);
                List<InspectorField> projectedFields = parseFields(row.get("fields"), fields, capabilities, "field:", owner);
                projectedRows.add(new InspectorRow(text(row.get("id")), text(row.get("title")),
                    text(row.get("description")), projectedFields, property(row, "bindings", "binding"),
                    property(row, "visibility", "visibleWhen"), declaredReadOnly(row)));
            }
            projectedSections.add(new InspectorSection(text(section.get("id")), text(section.get("title")),
                text(section.get("description")), projectedRows, property(section, "bindings", "binding"),
                property(section, "visibility", "visibleWhen"), declaredReadOnly(section)));
        }
        return new Inspector(text(inspector.get("id")), text(inspector.get("title")),
            text(inspector.get("description")), projectedSections, property(inspector, "bindings", "binding"),
            property(inspector, "visibility", "visibleWhen"), declaredReadOnly(inspector));
    }

    private static List<InspectorField> parseFields(Object value, List<Field> fields,
                                                    ClientCapabilities capabilities, String prefix, OwnerId owner) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        List<InspectorField> projected = new ArrayList<>();
        for (Object fieldValue : values) {
            if (!(fieldValue instanceof Map<?, ?> raw)) {
                fields.add(Field.unsupported(prefix + "unknown", "Unknown Field", "The inspector field is not an object."));
                continue;
            }
            Map<String, Object> source = freezeMap(raw);
            String id = text(source.get("id"));
            if (id.isBlank()) {
                fields.add(Field.unsupported(prefix + "unknown", "Unknown Field", "The inspector field has no stable identity."));
                continue;
            }
            Object type = source.containsKey("valueType") ? source.get("valueType") : source.get("type");
            Field capabilityField = field(prefix + id, text(source.get("title")), text(source.get("description")),
                type, source.get("editor"), capabilities);
            if (declaredReadOnly(source) && capabilityField.editable()) {
                capabilityField = new Field(capabilityField.id(), capabilityField.title(), capabilityField.description(),
                    capabilityField.editorKind(), capabilityField.typeExpression(), capabilityField.capability(), false,
                    "The inspector field is declared read-only.");
            }
            fields.add(capabilityField);
            List<InspectorField> children = new ArrayList<>();
            children.addAll(parseFields(source.get("children"), fields, capabilities, prefix + id + "/", owner));
            children.addAll(parseFields(source.get("fields"), fields, capabilities, prefix + id + "/", owner));
            if (source.get("element") instanceof Map<?, ?> element) {
                children.addAll(parseFields(List.of(element), fields, capabilities, prefix + id + "/element/", owner));
            }
            Object defaultValue = source.containsKey("default") ? source.get("default") : source.get("defaultValue");
            ContractRef<InspectorFieldId> optionSource = embeddedOptionSource(source.get("optionSource"), owner);
            projected.add(new InspectorField(id, capabilityField.id(), capabilityField.title(),
                capabilityField.description(), text(source.get("kind")), type, defaultValue,
                property(source, "bindings", "binding"), optionSource, source.get("query"),
                property(source, "visibility", "visibleWhen"), !capabilityField.editable(), capabilityField,
                children));
        }
        return List.copyOf(projected);
    }

    private static Object property(Map<String, Object> source, String primary, String fallback) {
        return source.containsKey(primary) ? source.get(primary) : source.get(fallback);
    }

    private static boolean declaredReadOnly(Map<String, Object> source) {
        return Boolean.TRUE.equals(source.get("readOnly"));
    }

    private static Field field(String id, String title, String description, Object type,
                               Object editor, ClientCapabilities capabilities) {
        String capability = capability(editor);
        EditorKind kind = capabilities.editor(capability, type).orElse(EditorKind.UNSUPPORTED);
        boolean editable = kind != EditorKind.UNSUPPORTED;
        String reason = editable ? "" : capability.isBlank()
            ? "The descriptor does not declare a supported editor capability."
            : "The editor capability is not supported by this client.";
        return new Field(id, title.isBlank() ? id : title, description, kind,
            type == null ? "" : CanonicalJson.canonicalize(type), capability, editable, reason);
    }

    private static String capability(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return "";
        }
        Map<String, Object> map = freezeMap(raw);
        String owner = text(map.get("ownerId"));
        String id = text(map.get("localId"));
        return owner.isBlank() || id.isBlank() ? "" : owner + "/" + id;
    }

    private static ContractRef<InspectorFieldId> reference(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Option source reference must be an object");
        }
        Map<String, Object> source = freezeMap(raw);
        String owner = text(source.get("ownerId"));
        String id = text(source.get("localId"));
        if (owner.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("Option source reference is incomplete");
        }
        return ContractRef.of(OwnerId.of(owner), InspectorFieldId.of(id));
    }

    private static ContractRef<InspectorFieldId> embeddedOptionSource(Object value, OwnerId owner) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw) || owner == null) {
            throw new IllegalArgumentException("Inspector option source must be an object");
        }
        Map<String, Object> source = freezeMap(raw);
        if (!source.keySet().containsAll(Set.of("id", "title", "description", "valueType", "querySchema",
            "capability", "pageLimit", "invalidationKey"))) {
            throw new IllegalArgumentException("Inspector option source is incomplete");
        }
        String id = text(source.get("id"));
        if (id.isBlank() || !(source.get("capability") instanceof Map<?, ?>)
            || !(source.get("pageLimit") instanceof Number)) {
            throw new IllegalArgumentException("Inspector option source is invalid");
        }
        return ContractRef.of(owner, InspectorFieldId.of(id));
    }

    private static boolean flowType(Object value) {
        String type = typeName(value);
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "flow", "execution" -> true;
            default -> false;
        };
    }

    private static String typeName(Object value) {
        if (value instanceof String string) {
            return string;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return "";
        }
        Map<String, Object> map = freezeMap(raw);
        String kind = text(map.get("kind"));
        if ("named".equalsIgnoreCase(kind) && map.get("type") instanceof Map<?, ?> reference) {
            Map<String, Object> typed = freezeMap(reference);
            String owner = text(typed.get("ownerId"));
            String localId = text(typed.get("localId"));
            return owner.isBlank() || localId.isBlank() || "builtin".equals(owner)
                ? localId : owner + ":" + localId;
        }
        if ("resource".equalsIgnoreCase(kind)) {
            return "resource_reference";
        }
        return kind;
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Canonical descriptor maps require string keys");
            }
            result.put(key, freeze(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map);
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> result = new ArrayList<>(collection.size());
            collection.forEach(item -> result.add(freeze(item)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    public enum Status {
        ACTIVE,
        READ_ONLY,
        UNAVAILABLE,
        OPAQUE,
        INVALID,
        TOMBSTONED
    }

    public enum EditorKind {
        TEXT,
        NUMBER,
        BOOLEAN,
        SELECT,
        JSON,
        UNSUPPORTED
    }

    public enum Direction {
        INPUT,
        OUTPUT
    }

    public record Pin(String id, String displayName, Direction direction, Object type, String description,
                      ContractRef<InspectorFieldId> optionSource) {
        public Pin {
            id = Objects.requireNonNull(id, "Pin identity is required");
            if (id.isBlank()) {
                throw new IllegalArgumentException("Pin identity is required");
            }
            displayName = displayName == null || displayName.isBlank() ? id : displayName;
            direction = Objects.requireNonNull(direction, "Pin direction is required");
            type = freeze(Objects.requireNonNull(type, "Pin type is required"));
            description = description == null ? "" : description;
        }

        public String typeExpression() {
            return CanonicalJson.canonicalize(type);
        }
    }

    public record ClientCapabilities(Map<String, EditorKind> editors, boolean primitiveEditors) {
        public ClientCapabilities(Map<String, EditorKind> editors) {
            this(editors, false);
        }

        public ClientCapabilities {
            LinkedHashMap<String, EditorKind> normalized = new LinkedHashMap<>();
            if (editors != null) {
                editors.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null && value != EditorKind.UNSUPPORTED) {
                        normalized.put(key, value);
                    }
                });
            }
            editors = Collections.unmodifiableMap(normalized);
        }

        public static ClientCapabilities none() {
            return new ClientCapabilities(Map.of(), false);
        }

        public static ClientCapabilities primitive() {
            return new ClientCapabilities(Map.of(), true);
        }

        public Optional<EditorKind> editor(String capability) {
            return capability == null || capability.isBlank() ? Optional.empty() : Optional.ofNullable(editors.get(capability));
        }

        public Optional<EditorKind> editor(String capability, Object type) {
            Optional<EditorKind> explicit = editor(capability);
            if (explicit.isPresent() || !primitiveEditors || capability == null || capability.isBlank()) {
                return explicit;
            }
            int separator = capability.lastIndexOf('/');
            String localId = separator >= 0 ? capability.substring(separator + 1) : capability;
            String normalizedLocalId = localId.toLowerCase(Locale.ROOT);
            String normalizedType = primitiveType(type);
            if (normalizedType.isBlank()) {
                return Optional.empty();
            }
            if (Set.of("text", "string").contains(normalizedType)
                && Set.of("text", "text-editor", "string", "string-editor", "generic-editor").contains(normalizedLocalId)) {
                return Optional.of(EditorKind.TEXT);
            }
            if (Set.of("number", "integer", "float", "double").contains(normalizedType)
                && Set.of("number", "number-editor", "numeric", "numeric-editor", "generic-editor").contains(normalizedLocalId)) {
                return Optional.of(EditorKind.NUMBER);
            }
            if (Set.of("boolean", "bool").contains(normalizedType)
                && Set.of("boolean", "boolean-editor", "bool", "bool-editor", "toggle", "toggle-editor", "generic-editor").contains(normalizedLocalId)) {
                return Optional.of(EditorKind.BOOLEAN);
            }
            if (Set.of("json", "json_object").contains(normalizedType)
                && Set.of("json", "json-editor", "generic-editor").contains(normalizedLocalId)) {
                return Optional.of(EditorKind.JSON);
            }
            return Optional.empty();
        }

        private static String primitiveType(Object value) {
            String type = typeName(value);
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "text" -> "string";
                case "bool" -> "boolean";
                case "json" -> "json_object";
                default -> type.toLowerCase(Locale.ROOT);
            };
        }
    }

    public record Field(String id, String title, String description, EditorKind editorKind,
                        String typeExpression, String capability, boolean editable, String reason) {
        public Field {
            id = Objects.requireNonNull(id, "Field identity is required");
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            editorKind = Objects.requireNonNull(editorKind, "Editor kind is required");
            typeExpression = typeExpression == null ? "" : typeExpression;
            capability = capability == null ? "" : capability;
            reason = reason == null ? "" : reason;
        }

        private static Field unsupported(String id, String title, String reason) {
            return new Field(id, title, "", EditorKind.UNSUPPORTED, "", "", false, reason);
        }
    }

    public record Inspector(String id, String title, String description, List<InspectorSection> sections,
                            Object bindings, Object visibility, boolean readOnly) {
        public Inspector {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            sections = sections == null ? List.of() : List.copyOf(sections);
            bindings = freeze(bindings);
            visibility = freeze(visibility);
        }

        public static Inspector empty() {
            return new Inspector("", "", "", List.of(), null, null, false);
        }

        public boolean present() {
            return !id.isBlank() || !title.isBlank() || !description.isBlank() || !sections.isEmpty()
                || bindings != null || visibility != null || readOnly;
        }
    }

    public record InspectorSection(String id, String title, String description, List<InspectorRow> rows,
                                   Object bindings, Object visibility, boolean readOnly) {
        public InspectorSection {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            rows = rows == null ? List.of() : List.copyOf(rows);
            bindings = freeze(bindings);
            visibility = freeze(visibility);
        }
    }

    public record InspectorRow(String id, String title, String description, List<InspectorField> fields,
                               Object bindings, Object visibility, boolean readOnly) {
        public InspectorRow {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            fields = fields == null ? List.of() : List.copyOf(fields);
            bindings = freeze(bindings);
            visibility = freeze(visibility);
        }
    }

    public record InspectorField(String id, String path, String title, String description, String kind,
                                 Object type, Object defaultValue, Object bindings,
                                 ContractRef<InspectorFieldId> optionSource, Object query,
                                 Object visibility, boolean readOnly, Field editor, List<InspectorField> children) {
        public InspectorField {
            id = Objects.requireNonNull(id, "Inspector field identity is required");
            path = Objects.requireNonNull(path, "Inspector field path is required");
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            kind = kind == null ? "" : kind;
            type = freeze(type);
            defaultValue = freeze(defaultValue);
            bindings = freeze(bindings);
            query = freeze(query);
            visibility = freeze(visibility);
            editor = Objects.requireNonNull(editor, "Inspector field editor projection is required");
            children = children == null ? List.of() : List.copyOf(children);
        }

        public String typeExpression() {
            return type == null ? "" : CanonicalJson.canonicalize(type);
        }
    }

    public record Projection(ContractRef<NodeId> definitionKey,
                             long revision,
                             Status status,
                             boolean readOnly,
                             Map<String, Object> descriptor,
                             String canonicalData,
                             List<Field> fields,
                             List<Pin> pins,
                             Inspector inspector,
                             Set<ContractRef<CapabilityId>> requiredCapabilities,
                             Map<String, Object> unknown,
                             String reason) {
        public Projection(ContractRef<NodeId> definitionKey,
                          long revision,
                          Status status,
                          boolean readOnly,
                          Map<String, Object> descriptor,
                          String canonicalData,
                          List<Field> fields,
                          Set<ContractRef<CapabilityId>> requiredCapabilities,
                          Map<String, Object> unknown,
                          String reason) {
            this(definitionKey, revision, status, readOnly, descriptor, canonicalData, fields, List.of(), Inspector.empty(),
                requiredCapabilities, unknown, reason);
        }

        public Projection(ContractRef<NodeId> definitionKey,
                          long revision,
                          Status status,
                          boolean readOnly,
                          Map<String, Object> descriptor,
                          String canonicalData,
                          List<Field> fields,
                          List<Pin> pins,
                          Set<ContractRef<CapabilityId>> requiredCapabilities,
                          Map<String, Object> unknown,
                          String reason) {
            this(definitionKey, revision, status, readOnly, descriptor, canonicalData, fields, pins, Inspector.empty(),
                requiredCapabilities, unknown, reason);
        }

        public Projection {
            definitionKey = Objects.requireNonNull(definitionKey, "Definition key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Catalog revision cannot be negative");
            }
            status = Objects.requireNonNull(status, "Projection status is required");
            descriptor = descriptor == null ? Map.of() : freezeMap(descriptor);
            canonicalData = canonicalData == null ? "" : canonicalData;
            fields = fields == null ? List.of() : List.copyOf(fields);
            pins = pins == null ? List.of() : List.copyOf(pins);
            inspector = inspector == null ? Inspector.empty() : inspector;
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
            unknown = unknown == null ? Map.of() : freezeMap(unknown);
            reason = reason == null ? "" : reason;
        }

        private static Projection tombstone(CatalogCachePublication.Entry entry) {
            return new Projection(entry.definitionKey(), entry.revision(), Status.TOMBSTONED, true,
                Map.of(), "", List.of(), List.of(), Inspector.empty(), Set.of(), entry.unknown(),
                "The catalog entry was removed by the server.");
        }

        private static Projection invalid(CatalogCachePublication.Entry entry, String canonical, String reason) {
            return new Projection(entry.definitionKey(), entry.revision(), Status.INVALID, true,
                Map.of(), canonical, List.of(), List.of(), Inspector.empty(), entry.requiredCapabilities(), entry.unknown(), reason);
        }

        public Optional<Pin> pin(String id, boolean input) {
            if (id == null || id.isBlank() || !id.equals(id.strip())) {
                return Optional.empty();
            }
            Direction expected = input ? Direction.INPUT : Direction.OUTPUT;
            return pins.stream().filter(pin -> pin.id().equals(id) && pin.direction() == expected).findFirst();
        }
    }
}
