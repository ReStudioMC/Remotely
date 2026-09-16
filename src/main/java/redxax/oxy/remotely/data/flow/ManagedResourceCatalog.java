package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import redxax.oxy.remotely.flow.sync.FlowResourceMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ManagedResourceCatalog {
    private static final int CAPABILITY_VERSION = 1;
    private static final int METADATA_SCHEMA_VERSION = 2;

    private ManagedResourceCatalog() {
    }

    public enum Operation {
        DISCOVER("discover"),
        QUERY("query"),
        GET("get"),
        CREATE("create"),
        VALIDATE("validate"),
        SAVE("save"),
        UPDATE("update"),
        RENAME("rename"),
        MOVE("move"),
        DUPLICATE("duplicate"),
        ACTIVATE("activate"),
        DELETE("delete"),
        SUBSCRIBE("subscribe"),
        RELOAD("reload"),
        APPLY("apply");

        private final String id;

        Operation(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Operation> byId(String id) {
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            return Arrays.stream(values()).filter(value -> value.id.equalsIgnoreCase(id)).findFirst();
        }
    }

    public enum State {
        AVAILABLE,
        UNSUPPORTED,
        UNAVAILABLE
    }

    public record Access(Operation operation, State state, String reason) {
        public Access {
            operation = Objects.requireNonNull(operation, "Managed resource operation is required");
            state = Objects.requireNonNull(state, "Managed resource operation state is required");
            reason = reason == null ? "" : reason;
            if (state != State.AVAILABLE && reason.isBlank()) {
                throw new IllegalArgumentException("Unavailable managed resource operations require a reason");
            }
        }

        public boolean available() {
            return state == State.AVAILABLE;
        }
    }

    public record Descriptor(String typeId, String displayName, String owner, String defaultFolder,
                             boolean available, boolean opaque, String unavailableReason,
                             Map<Operation, Access> operations, Set<String> presentations) {
        public Descriptor {
            typeId = required(typeId, "Managed resource type is required");
            displayName = displayName == null || displayName.isBlank() ? typeId : displayName;
            owner = clean(owner);
            defaultFolder = defaultFolder == null ? "" : defaultFolder;
            unavailableReason = unavailableReason == null ? "" : unavailableReason;
            if ((!available || opaque) && unavailableReason.isBlank()) {
                throw new IllegalArgumentException("Unavailable managed resource descriptors require a reason");
            }
            EnumMap<Operation, Access> operationCopy = new EnumMap<>(Operation.class);
            if (operations != null) {
                operations.forEach((operation, access) -> {
                    if (operation != null && access != null && operation == access.operation()) {
                        operationCopy.put(operation, access);
                    }
                });
            }
            for (Operation operation : Operation.values()) {
                operationCopy.putIfAbsent(operation, new Access(operation,
                    available ? State.UNSUPPORTED : State.UNAVAILABLE,
                    available ? "The server did not advertise this operation." : unavailableReason));
            }
            operations = Collections.unmodifiableMap(operationCopy);
            LinkedHashSet<String> presentationCopy = new LinkedHashSet<>();
            if (presentations != null) {
                presentations.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT)).forEach(presentationCopy::add);
            }
            presentations = Collections.unmodifiableSet(presentationCopy);
        }

        public Access operation(Operation operation) {
            return operations.get(Objects.requireNonNull(operation, "Managed resource operation is required"));
        }

        public boolean supports(Operation operation) {
            return operation(operation).available();
        }

        public Optional<String> preferredPresentation(Set<String> supportedPresentations) {
            if (supportedPresentations == null || supportedPresentations.isEmpty()) {
                return Optional.empty();
            }
            return presentations.stream().filter(supportedPresentations::contains).findFirst();
        }
    }

    @FunctionalInterface
    public interface Source {
        Optional<Descriptor> descriptor(String serverId, String typeId);
    }

    public static Descriptor fromMetadata(String requestedType, FlowResourceMetadata metadata) {
        String typeId = required(requestedType, "Managed resource type is required");
        if (metadata == null || metadata.getTypeId() == null || !typeId.equals(metadata.getTypeId())) {
            return opaque(typeId, "The server did not publish a descriptor for this resource type.");
        }
        boolean available = metadata.isAvailable();
        String unavailableReason = clean(metadata.getUnavailableReason());
        if (!available && unavailableReason.isBlank()) {
            unavailableReason = "The server reported this resource type as unavailable.";
        }
        Set<String> advertised = new LinkedHashSet<>();
        if (metadata.getOperations() != null) {
            metadata.getOperations().stream().filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT)).forEach(advertised::add);
        }
        Map<String, String> availability = metadata.getOperationAvailability() != null
            ? metadata.getOperationAvailability() : Map.of();
        EnumMap<Operation, Access> operations = new EnumMap<>(Operation.class);
        for (Operation operation : Operation.values()) {
            String reason = availability.entrySet().stream()
                .filter(entry -> entry.getKey() != null && operation.id().equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse("");
            boolean explicitlyAvailable = "available".equalsIgnoreCase(clean(reason));
            boolean listed = advertised.contains(operation.id());
            if (available && (explicitlyAvailable || listed && reason.isBlank())) {
                operations.put(operation, new Access(operation, State.AVAILABLE, ""));
            } else if (!available) {
                operations.put(operation, new Access(operation, State.UNAVAILABLE, unavailableReason));
            } else {
                operations.put(operation, new Access(operation, State.UNSUPPORTED,
                    reason.isBlank() ? "The server did not advertise this operation." : reason));
            }
        }
        String owner = clean(metadata.getOwner());
        boolean opaque = !available || owner.isBlank();
        if (owner.isBlank() && unavailableReason.isBlank()) {
            unavailableReason = "The server descriptor does not identify the resource owner.";
        }
        return new Descriptor(typeId, metadata.getDisplayName(), owner, metadata.getDefaultFolder(), available,
            opaque, unavailableReason, operations, Set.of());
    }

    public static Descriptor fromCapabilities(String requestedType, JsonObject capabilities) {
        String typeId = required(requestedType, "Managed resource type is required");
        try {
            JsonObject managed = object(member(capabilities, "managedResources"), "managedResources");
            if (integer(member(managed, "version"), "managedResources.version") != CAPABILITY_VERSION) {
                return opaque(typeId, "The server published an unsupported managed resource catalog version.");
            }
            JsonArray values = array(member(managed, "descriptors"), "managedResources.descriptors");
            Map<String, FlowResourceMetadata> descriptors = new LinkedHashMap<>();
            for (JsonElement value : values) {
                FlowResourceMetadata metadata = metadata(object(value, "managedResources.descriptors[]"));
                String key = metadata.getTypeId().toLowerCase(Locale.ROOT);
                if (descriptors.putIfAbsent(key, metadata) != null) {
                    return opaque(typeId, "The server published duplicate managed resource descriptors.");
                }
            }
            FlowResourceMetadata metadata = descriptors.get(typeId.toLowerCase(Locale.ROOT));
            return metadata == null
                ? opaque(typeId, "The server did not publish a descriptor for this resource type.")
                : fromMetadata(typeId, metadata);
        } catch (RuntimeException exception) {
            return opaque(typeId, "The server published malformed managed resource descriptors.");
        }
    }

    private static FlowResourceMetadata metadata(JsonObject value) {
        if (integer(member(value, "schemaVersion"), "descriptor.schemaVersion") != METADATA_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Managed resource metadata version is unsupported");
        }
        FlowResourceMetadata metadata = new FlowResourceMetadata();
        metadata.setSchemaVersion(METADATA_SCHEMA_VERSION);
        metadata.setTypeId(text(member(value, "typeId"), "descriptor.typeId", false));
        metadata.setDisplayName(text(member(value, "displayName"), "descriptor.displayName", false));
        metadata.setOwner(text(member(value, "owner"), "descriptor.owner", false));
        metadata.setDefaultFolder(optionalText(value, "defaultFolder"));
        metadata.setAvailable(bool(member(value, "available"), "descriptor.available"));
        metadata.setUnavailableReason(optionalText(value, "unavailableReason"));

        JsonArray operationValues = array(member(value, "operations"), "descriptor.operations");
        List<String> operations = new ArrayList<>(operationValues.size());
        Set<String> operationIds = new LinkedHashSet<>();
        for (JsonElement operationValue : operationValues) {
            String operation = text(operationValue, "descriptor.operations[]", false).toLowerCase(Locale.ROOT);
            if (!operationIds.add(operation)) {
                throw new IllegalArgumentException("Managed resource operations are duplicated");
            }
            operations.add(operation);
        }
        metadata.setOperations(operations);

        JsonObject availabilityValue = object(member(value, "operationAvailability"),
            "descriptor.operationAvailability");
        Map<String, String> availability = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : availabilityValue.entrySet()) {
            String operation = required(entry.getKey(), "Managed resource operation is required")
                .toLowerCase(Locale.ROOT);
            String reason = text(entry.getValue(), "descriptor.operationAvailability." + operation, false);
            if (availability.putIfAbsent(operation, reason) != null) {
                throw new IllegalArgumentException("Managed resource operation availability is duplicated");
            }
        }
        metadata.setOperationAvailability(availability);
        return metadata;
    }

    private static JsonElement member(JsonObject value, String name) {
        if (value == null || !value.has(name) || value.get(name).isJsonNull()) {
            throw new IllegalArgumentException("Missing managed resource capability field: " + name);
        }
        return value.get(name);
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Managed resource capability field is not an object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String name) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Managed resource capability field is not an array: " + name);
        }
        return value.getAsJsonArray();
    }

    private static int integer(JsonElement value, String name) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException("Managed resource capability field is not an integer: " + name);
        }
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Managed resource capability field is not an integer: " + name,
                exception);
        }
    }

    private static boolean bool(JsonElement value, String name) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new IllegalArgumentException("Managed resource capability field is not a boolean: " + name);
        }
        return primitive.getAsBoolean();
    }

    private static String text(JsonElement value, String name, boolean blank) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException("Managed resource capability field is not text: " + name);
        }
        String result = primitive.getAsString().trim();
        if (!blank && result.isBlank()) {
            throw new IllegalArgumentException("Managed resource capability field is blank: " + name);
        }
        return result;
    }

    private static String optionalText(JsonObject value, String name) {
        if (!value.has(name) || value.get(name).isJsonNull()) {
            return "";
        }
        return text(value.get(name), "descriptor." + name, true);
    }

    public static Descriptor opaque(String typeId, String reason) {
        String unavailableReason = clean(reason);
        if (unavailableReason.isBlank()) {
            unavailableReason = "The resource descriptor is unavailable.";
        }
        return new Descriptor(typeId, typeId, "", "", false, true, unavailableReason, Map.of(), Set.of());
    }

    public static Descriptor descriptor(String typeId, String displayName, String owner, boolean available,
                                        String unavailableReason, Map<Operation, Access> operations,
                                        Set<String> presentations) {
        return new Descriptor(typeId, displayName, owner, "", available, !available, unavailableReason,
            operations, presentations);
    }

    public static List<Operation> editorOperations() {
        return List.of(Operation.DISCOVER, Operation.QUERY, Operation.GET, Operation.CREATE, Operation.VALIDATE,
            Operation.SAVE, Operation.UPDATE, Operation.DUPLICATE, Operation.RELOAD, Operation.DELETE,
            Operation.APPLY);
    }

    private static String required(String value, String message) {
        String normalized = clean(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
