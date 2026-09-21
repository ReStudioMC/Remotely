package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class AutomationDefinitionDraft {
    public static final String VARIABLE = "variable_definition";
    public static final String TIMER = "timer_definition";
    public static final String SCHEDULE = "schedule_definition";
    public static final String COMPONENT_BUILDER = "component_builder";
    public static final String CORE_OWNER = "restudio.resync";

    private static final Set<String> TYPES = Set.of(VARIABLE, TIMER, SCHEDULE, COMPONENT_BUILDER);
    private static final Set<String> TARGET_TYPES = Set.of("flow", "function", "command");
    private static final List<String> COMMON_FIELDS = List.of("name", "description", "scope", "persistent");

    private AutomationDefinitionDraft() {
    }

    public record Target(ContractRef<ResourceTypeId> type, String id) {
        public Target {
            type = Objects.requireNonNull(type, "Schedule target type is required");
            id = requiredText(id, "Schedule target is required");
            if (!CORE_OWNER.equals(type.ownerId())) {
                throw new IllegalArgumentException("Schedule target must belong to the Core graph owner");
            }
            if (!TARGET_TYPES.contains(type.localId())) {
                throw new IllegalArgumentException("Schedule target type must be flow, function, or command");
            }
        }

        public Target(String type, String id) {
            this(ContractRef.of(OwnerId.of(CORE_OWNER), ResourceTypeId.of(normalize(type))), id);
        }

        public ServerResourceLocator locator(ServerId serverId) {
            return new ServerResourceLocator(Objects.requireNonNull(serverId, "Schedule target server is required"), type, id);
        }
    }

    public record Prepared(String type, JsonObject document, Target target) {
        public Prepared {
            document = document.deepCopy();
        }

        @Override
        public JsonObject document() {
            return document.deepCopy();
        }
    }

    public static boolean supports(String type) {
        return TYPES.contains(type);
    }

    public static boolean supports(ReSyncResourceType type) {
        return type != null && supports(type.typeId());
    }

    public static List<String> fields(String type) {
        requireType(type);
        return switch (type) {
            case VARIABLE -> combine(COMMON_FIELDS, List.of("valueType", "defaultValue"));
            case TIMER -> combine(COMMON_FIELDS, List.of("defaultDuration", "defaultUnit", "tickInterval"));
            case SCHEDULE -> combine(COMMON_FIELDS, List.of("targetType", "targetId", "timingMode", "duration", "unit",
                "initialDelay", "dateTime", "timeZone", "cron", "overlapPolicy", "existingTaskPolicy",
                "failurePolicy", "offlinePolicy", "missedRunPolicy"));
            case COMPONENT_BUILDER -> List.of("name", "description", "scopeKind", "scopeValue");
            default -> throw new IllegalArgumentException("Unsupported automation type: " + type);
        };
    }

    public static List<String> options(String type, String field) {
        requireType(type);
        return switch (field) {
            case "scope" -> List.of("flow", "server", "player", "entity", "network");
            case "defaultUnit", "unit" -> List.of("ticks", "seconds", "minutes");
            case "targetType" -> List.of("flow", "function", "command");
            case "timingMode" -> List.of("after_delay", "at_time", "repeating", "cron");
            case "overlapPolicy" -> List.of("skip", "queue", "parallel", "replace");
            case "existingTaskPolicy" -> List.of("replace", "keep", "fail");
            case "failurePolicy" -> List.of("continue", "stop");
            case "offlinePolicy" -> List.of("wait", "skip", "run_without_player", "cancel");
            case "missedRunPolicy" -> List.of("run_once", "skip", "cancel");
            case "scopeKind" -> List.of("dynamic", "category", "tag", "item");
            default -> List.of();
        };
    }

    public static List<String> valueTypeOptions(Collection<FlowDataType> dataTypes) {
        Set<String> ids = catalogTypeIds(dataTypes);
        List<String> values = new ArrayList<>(ids.stream().filter(AutomationDefinitionDraft::variableRootType)
            .filter(id -> !genericConstructor(id)).toList());
        List<String> arguments = ids.stream().filter(id -> !genericConstructor(id) && !"execution".equals(id)).toList();
        for (String constructor : List.of("list", "set", "queue", "stack", "optional", "job_reference")) {
            if (ids.contains(constructor)) {
                arguments.forEach(argument -> values.add(constructor + "<" + argument + ">"));
            }
        }
        if (ids.contains("map") && ids.contains("string")) {
            arguments.forEach(argument -> values.add("map<string," + argument + ">"));
        }
        if (ids.contains("result") && !arguments.isEmpty()) {
            String failure = ids.contains("any") ? "any" : ids.contains("string") ? "string" : arguments.getFirst();
            arguments.forEach(success -> values.add("result<" + success + "," + failure + ">"));
        }
        return values.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public static JsonObject create(String type, String id, String folder, Target target) {
        requireType(type);
        String exactId = requiredText(id, "Automation ID is required");
        JsonObject value = new JsonObject();
        value.addProperty("id", exactId);
        if (folder != null && !folder.isBlank()) {
            value.addProperty("folder", folder.trim());
        }
        value.addProperty("name", exactId);
        value.addProperty("description", "");
        switch (type) {
            case VARIABLE -> {
                value.addProperty("valueType", "boolean");
                value.addProperty("scope", "flow");
                value.addProperty("persistent", false);
                value.addProperty("defaultValue", false);
            }
            case TIMER -> {
                value.addProperty("scope", "server");
                value.addProperty("persistent", false);
                value.addProperty("defaultDuration", 60);
                value.addProperty("defaultUnit", "seconds");
                value.addProperty("tickInterval", 0);
            }
            case SCHEDULE -> {
                Target exactTarget = Objects.requireNonNull(target, "Schedule target is required");
                value.add("target", targetObject(exactTarget));
                JsonObject timing = new JsonObject();
                timing.addProperty("mode", "after_delay");
                timing.addProperty("duration", 0);
                timing.addProperty("unit", "seconds");
                timing.addProperty("initialDelay", 0);
                timing.addProperty("dateTime", "");
                timing.addProperty("timeZone", "UTC");
                timing.addProperty("cron", "");
                value.add("timing", timing);
                value.addProperty("scope", "server");
                value.addProperty("persistent", false);
                value.addProperty("overlapPolicy", "skip");
                value.addProperty("existingTaskPolicy", "replace");
                value.addProperty("failurePolicy", "continue");
                value.addProperty("offlinePolicy", "wait");
                value.addProperty("missedRunPolicy", "run_once");
            }
            case COMPONENT_BUILDER -> {
                value.addProperty("displayName", exactId);
                JsonObject scope = new JsonObject();
                scope.addProperty("kind", "dynamic");
                scope.addProperty("value", "");
                value.add("scope", scope);
                value.add("components", new JsonObject());
            }
            default -> throw new IllegalArgumentException("Unsupported automation type: " + type);
        }
        return prepare(type, exactId, value).document();
    }

    public static Prepared prepare(String type, String expectedId, JsonObject document) {
        return prepare(type, expectedId, document, null);
    }

    public static Prepared prepare(String type, String expectedId, JsonObject document,
                                   Collection<FlowDataType> dataTypes) {
        requireType(type);
        if (document == null) {
            throw new IllegalArgumentException("Automation document is required");
        }
        JsonObject value = document.deepCopy();
        String id = requiredText(text(value, "id", ""), "Automation ID is required");
        if (!id.equals(requiredText(expectedId, "Expected automation ID is required"))) {
            throw new IllegalArgumentException("Automation ID must match its resource identity");
        }
        validate(type, value, dataTypes != null ? catalogTypeIds(dataTypes) : null);
        return new Prepared(type, value, SCHEDULE.equals(type) ? target(value) : null);
    }

    public static Prepared prepare(ReSyncResourceType type, String expectedId, JsonObject document) {
        if (!supports(type)) {
            throw new IllegalArgumentException("Unsupported automation type: " + type);
        }
        return prepare(type.typeId(), expectedId, document);
    }

    public static String text(String type, JsonObject document, String field) {
        requireType(type);
        JsonObject value = Objects.requireNonNull(document, "Automation document is required");
        return switch (field) {
            case "name" -> name(value);
            case "scope" -> text(value, field, VARIABLE.equals(type) ? "flow" : "server");
            case "persistent" -> text(value, field, "false");
            case "valueType" -> text(value, field, text(value, "type", "any"));
            case "defaultDuration", "tickInterval" -> plainNumberText(text(value, field, ""));
            case "defaultUnit" -> text(value, field, "seconds");
            case "targetType" -> partialTargetType(value);
            case "targetId" -> partialTargetId(value);
            case "timingMode" -> timingText(value, "mode", "timingMode", "after_delay");
            case "duration", "initialDelay" -> plainNumberText(timingText(value, field, field, ""));
            case "unit", "dateTime", "timeZone", "cron" ->
                timingText(value, field, field, "timeZone".equals(field) ? "UTC" : "");
            case "defaultValue" -> defaultValueText(value, field);
            case "overlapPolicy" -> text(value, field, "skip");
            case "existingTaskPolicy" -> text(value, field, "replace");
            case "failurePolicy" -> text(value, field, "continue");
            case "offlinePolicy" -> text(value, field, "wait");
            case "missedRunPolicy" -> text(value, field, "run_once");
            case "scopeKind" -> text(object(value, "scope"), "kind", "dynamic");
            case "scopeValue" -> text(object(value, "scope"), "value", "");
            default -> text(value, field, "");
        };
    }

    public static void put(String type, JsonObject document, String field, String newValue) {
        requireType(type);
        Objects.requireNonNull(document, "Automation document is required");
        String value = newValue != null ? newValue.trim() : "";
        switch (field) {
            case "name" -> putName(document, value);
            case "persistent" -> document.addProperty(field, Boolean.parseBoolean(value));
            case "defaultDuration", "tickInterval", "duration", "initialDelay" -> putNumber(document, field, value);
            case "defaultValue" -> putDefaultValue(document, value);
            case "targetType" -> putTargetType(document, value);
            case "targetId" -> putTargetId(document, value);
            case "timingMode" -> putTiming(document, "mode", "timingMode", value);
            case "unit", "dateTime", "timeZone", "cron" -> putTiming(document, field, field, value);
            case "scopeKind" -> putObjectText(document, "scope", "kind", value);
            case "scopeValue" -> putObjectText(document, "scope", "value", value);
            default -> putText(document, field, value);
        }
    }

    public static Target target(JsonObject document) {
        Objects.requireNonNull(document, "Schedule document is required");
        JsonObject nested = object(document, "target");
        String flatType = optionalText(document, "targetType");
        String flatId = optionalText(document, "targetId");
        String nestedType = null;
        String owner = CORE_OWNER;
        String nestedId = optionalText(nested, "id");
        JsonElement typeElement = nested != null ? nested.get("type") : null;
        if (typeElement != null && typeElement.isJsonObject()) {
            JsonObject type = typeElement.getAsJsonObject();
            owner = text(type, "ownerId", CORE_OWNER);
            nestedType = optionalText(type, "localId");
        } else if (typeElement != null && typeElement.isJsonPrimitive()) {
            nestedType = typeElement.getAsString();
        }
        String targetType = chooseNormalized(flatType, nestedType, "Schedule target type fields conflict");
        String targetId = chooseExact(flatId, nestedId, "Schedule target ID fields conflict");
        return new Target(ContractRef.of(OwnerId.of(owner), ResourceTypeId.of(normalize(targetType != null
            ? targetType : "function"))),
            requiredText(targetId, "Schedule target is required"));
    }

    private static void validate(String type, JsonObject value, Set<String> catalogTypes) {
        if (SCHEDULE.equals(type)) {
            validateTimingRepresentations(value);
        }
        for (String field : fields(type)) {
            if (!"valueType".equals(field) && !options(type, field).isEmpty()) {
                String configured = text(type, value, field);
                if (!options(type, field).contains(normalize(configured))) {
                    throw new IllegalArgumentException("Unknown " + field + " value: " + configured);
                }
            }
        }
        number(value, "defaultDuration", 0D);
        number(value, "tickInterval", 0D);
        if (VARIABLE.equals(type)) {
            String valueType = text(type, value, "valueType");
            FlowTypeRef resolved;
            try {
                resolved = FlowTypeRef.parse(valueType);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Variable value type has an invalid type expression", exception);
            }
            validateTypeShape(resolved);
            if (!variableRootType(resolved.getTypeId())) {
                throw new IllegalArgumentException("Variable value type must be a concrete data type");
            }
            if (catalogTypes != null && !catalogType(resolved, catalogTypes)) {
                throw new IllegalArgumentException("Variable value type must be a known data type");
            }
            validateDefaultValue(value, resolved.getTypeId());
        }
        if (COMPONENT_BUILDER.equals(type)) {
            String kind = text(type, value, "scopeKind");
            String scopeValue = text(type, value, "scopeValue");
            if (!"dynamic".equals(kind) && scopeValue.isBlank()) {
                throw new IllegalArgumentException("Choose What This Component Builder Applies To");
            }
            if (!value.has("components") || !value.get("components").isJsonObject()) {
                throw new IllegalArgumentException("Component Builder components must be an object");
            }
        }
        if (!SCHEDULE.equals(type)) {
            return;
        }
        target(value);
        double duration = timingNumber(value, "duration", 0D);
        timingNumber(value, "initialDelay", 0D);
        String mode = normalize(exactTimingText(value, "mode", "timingMode", "after_delay"));
        if ("repeating".equals(mode) && duration <= 0D) {
            throw new IllegalArgumentException("Repeating Schedule interval must be positive");
        }
        if ("at_time".equals(mode) && exactTimingText(value, "dateTime", "dateTime", "").isBlank()) {
            throw new IllegalArgumentException("At Time Schedule requires a date and time");
        }
        if ("cron".equals(mode) && exactTimingText(value, "cron", "cron", "").isBlank()) {
            throw new IllegalArgumentException("Cron Schedule requires a pattern");
        }
    }

    private static Set<String> catalogTypeIds(Collection<FlowDataType> dataTypes) {
        Set<String> ids = new LinkedHashSet<>();
        if (dataTypes == null) {
            return ids;
        }
        for (FlowDataType dataType : dataTypes) {
            if (dataType == null || dataType.getId() == null || dataType.getId().isBlank()) {
                continue;
            }
            ids.add(dataType.getId().trim().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static boolean catalogType(FlowTypeRef type, Set<String> catalogTypes) {
        if (!catalogTypes.contains(type.getTypeId())) {
            return false;
        }
        if ("resource_reference".equals(type.getTypeId())) {
            return true;
        }
        return type.getArguments().stream().allMatch(argument -> catalogType(argument, catalogTypes));
    }

    private static boolean genericConstructor(String id) {
        return List.of("list", "set", "queue", "stack", "optional", "result", "job_reference", "map", "tuple").contains(id);
    }

    private static boolean variableRootType(String id) {
        return id != null && !"any".equals(id) && !"execution".equals(id) && !id.startsWith("type:");
    }

    private static void validateTypeShape(FlowTypeRef type) {
        String id = type.getTypeId();
        int arguments = type.getArguments().size();
        switch (id) {
            case "list", "set", "queue", "stack", "optional", "job_reference" -> requireArity(id, arguments, 1);
            case "map", "result" -> requireArity(id, arguments, 2);
            case "tuple" -> {
                if (arguments == 0) {
                    throw new IllegalArgumentException("Variable value type tuple requires at least one type argument");
                }
            }
            case "resource_reference" -> {
                if (arguments > 1 || arguments == 1 && !type.getArguments().getFirst().getArguments().isEmpty()) {
                    throw new IllegalArgumentException("Variable value type resource_reference accepts one simple resource identity");
                }
                return;
            }
            default -> requireArity(id, arguments, 0);
        }
        type.getArguments().forEach(AutomationDefinitionDraft::validateTypeShape);
    }

    private static void requireArity(String id, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Variable value type " + id + " requires " + expected + " type arguments");
        }
    }

    private static void putName(JsonObject value, String text) {
        boolean legacyOnly = value.has("displayName") && !value.has("name");
        if (!legacyOnly || value.has("name")) {
            putText(value, "name", text);
        }
        if (legacyOnly || value.has("displayName")) {
            putText(value, "displayName", text);
        }
    }

    private static void putTargetType(JsonObject value, String type) {
        String normalized = normalize(type);
        if (!TARGET_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Schedule target type must be flow, function, or command");
        }
        boolean nested = value.has("target");
        boolean flat = value.has("targetType") || value.has("targetId") || !nested;
        if (nested) {
            JsonObject current = object(value, "target");
            JsonObject replacement = current != null ? current.deepCopy() : new JsonObject();
            JsonElement currentType = replacement.get("type");
            if (currentType != null && currentType.isJsonPrimitive()) {
                replacement.addProperty("type", normalized);
            } else {
                JsonObject newType = currentType != null && currentType.isJsonObject()
                    ? currentType.getAsJsonObject().deepCopy() : new JsonObject();
                newType.addProperty("ownerId", CORE_OWNER);
                newType.addProperty("localId", normalized);
                replacement.add("type", newType);
            }
            value.add("target", replacement);
        }
        if (flat) {
            value.addProperty("targetType", normalized);
        }
    }

    private static void putTargetId(JsonObject value, String id) {
        String targetId = id != null ? id.trim() : "";
        boolean nested = value.has("target");
        boolean flat = value.has("targetType") || value.has("targetId") || !nested;
        if (nested) {
            JsonObject current = object(value, "target");
            JsonObject replacement = current != null ? current.deepCopy() : new JsonObject();
            replacement.addProperty("id", targetId);
            value.add("target", replacement);
        }
        if (flat) {
            value.addProperty("targetId", targetId);
        }
    }

    private static void putTiming(JsonObject value, String nestedField, String flatField, String text) {
        boolean nested = value.has("timing");
        boolean flat = value.has(flatField) || !nested;
        if (nested) {
            JsonObject timing = object(value, "timing");
            if (timing == null) {
                timing = new JsonObject();
                value.add("timing", timing);
            }
            putText(timing, nestedField, text);
            if ("cron".equals(nestedField) && timing.has("pattern")) {
                putText(timing, "pattern", text);
            }
        }
        if (flat) {
            putText(value, flatField, text);
        }
    }

    private static void putObjectText(JsonObject value, String objectField, String field, String text) {
        JsonObject object = object(value, objectField);
        if (object == null) {
            object = new JsonObject();
            value.add(objectField, object);
        }
        putText(object, field, text);
    }

    private static void putNumber(JsonObject value, String field, String text) {
        JsonPrimitive number;
        try {
            double finite = Double.parseDouble(text);
            BigDecimal decimal = new BigDecimal(text);
            String plain = decimal.toPlainString();
            number = Double.isFinite(finite) && finite >= 0D && plain.length() <= 4096
                ? JsonParser.parseString(plain).getAsJsonPrimitive() : null;
        } catch (RuntimeException exception) {
            number = null;
        }
        putNumericValue(value, field, text, number);
    }

    private static void putNumericValue(JsonObject value, String field, String text, JsonPrimitive number) {
        if ("duration".equals(field) || "initialDelay".equals(field)) {
            boolean nested = value.has("timing");
            boolean flat = value.has(field) || !nested;
            if (nested) {
                JsonObject timing = object(value, "timing");
                if (timing == null) {
                    timing = new JsonObject();
                    value.add("timing", timing);
                }
                if (number != null) {
                    timing.add(field, number.deepCopy());
                } else {
                    timing.addProperty(field, text);
                }
            }
            if (flat) {
                if (number != null) {
                    value.add(field, number.deepCopy());
                } else {
                    value.addProperty(field, text);
                }
            }
            return;
        }
        if (number != null) {
            value.add(field, number);
        } else {
            value.addProperty(field, text);
        }
    }

    private static void putDefaultValue(JsonObject value, String text) {
        if (text.isBlank()) {
            value.add("defaultValue", JsonNull.INSTANCE);
            return;
        }
        try {
            value.add("defaultValue", JsonParser.parseString(text));
        } catch (RuntimeException exception) {
            value.addProperty("defaultValue", text);
        }
    }

    private static String defaultValueText(JsonObject value, String field) {
        if (!value.has(field)) {
            return "";
        }
        JsonElement element = value.get(field);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
            ? plainNumberText(element.getAsString()) : element.toString();
    }

    private static String plainNumberText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        try {
            String plain = new BigDecimal(text).toPlainString();
            return plain.length() <= 4096 ? plain : text;
        } catch (RuntimeException exception) {
            return text;
        }
    }

    private static String name(JsonObject value) {
        return text(value, "name", text(value, "displayName", text(value, "id", "")));
    }

    private static String partialTargetType(JsonObject value) {
        String flat = optionalText(value, "targetType");
        JsonObject target = object(value, "target");
        JsonElement element = target != null ? target.get("type") : null;
        String nested = element != null && element.isJsonObject()
            ? optionalText(element.getAsJsonObject(), "localId")
            : element != null && element.isJsonPrimitive() ? element.getAsString() : null;
        String selected = flat != null ? flat : nested;
        return selected != null ? normalize(selected) : "function";
    }

    private static String partialTargetId(JsonObject value) {
        String flat = optionalText(value, "targetId");
        JsonObject target = object(value, "target");
        String nested = optionalText(target, "id");
        return flat != null ? flat : nested != null ? nested : "";
    }

    private static String timingText(JsonObject value, String nestedField, String flatField, String fallback) {
        return timingText(value, nestedField, flatField, fallback, false);
    }

    private static String exactTimingText(JsonObject value, String nestedField, String flatField, String fallback) {
        return timingText(value, nestedField, flatField, fallback, true);
    }

    private static String timingText(JsonObject value, String nestedField, String flatField, String fallback,
                                     boolean rejectConflict) {
        JsonObject timing = object(value, "timing");
        String nested = optionalText(timing, nestedField);
        if (nested == null && "cron".equals(nestedField)) {
            nested = optionalText(timing, "pattern");
        }
        String flat = optionalText(value, flatField);
        if (rejectConflict && flat != null && nested != null && !sameTiming(nestedField, flat, nested)) {
            throw new IllegalArgumentException("Schedule timing fields conflict: " + flatField);
        }
        return flat != null ? flat : nested != null ? nested : fallback;
    }

    private static double timingNumber(JsonObject value, String field, double fallback) {
        String text = exactTimingText(value, field, field, Double.toString(fallback));
        try {
            return requireFiniteNonnegative(Double.parseDouble(text), field);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Schedule timing value is invalid: " + field);
        }
    }

    private static double number(JsonObject value, String field, double fallback) {
        if (!value.has(field) || value.get(field).isJsonNull()) {
            return fallback;
        }
        try {
            return requireFiniteNonnegative(value.get(field).getAsDouble(), field);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Automation number is invalid: " + field);
        }
    }

    private static void validateTimingRepresentations(JsonObject value) {
        exactTimingText(value, "mode", "timingMode", "after_delay");
        exactTimingText(value, "duration", "duration", "0");
        exactTimingText(value, "unit", "unit", "seconds");
        exactTimingText(value, "initialDelay", "initialDelay", "0");
        exactTimingText(value, "dateTime", "dateTime", "");
        exactTimingText(value, "timeZone", "timeZone", "UTC");
        exactTimingText(value, "cron", "cron", "");
    }

    private static boolean sameTiming(String field, String first, String second) {
        if ("mode".equals(field) || "unit".equals(field)) {
            return normalize(first).equals(normalize(second));
        }
        if ("duration".equals(field) || "initialDelay".equals(field)) {
            try {
                return Double.compare(Double.parseDouble(first), Double.parseDouble(second)) == 0;
            } catch (RuntimeException exception) {
                return first.equals(second);
            }
        }
        return first.equals(second);
    }

    private static double requireFiniteNonnegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0D) {
            throw new IllegalArgumentException("Automation number must be finite and non-negative: " + field);
        }
        return value;
    }

    private static JsonObject targetObject(Target target) {
        JsonObject value = new JsonObject();
        JsonObject type = new JsonObject();
        type.addProperty("ownerId", target.type().ownerId());
        type.addProperty("localId", target.type().localId());
        value.add("type", type);
        value.addProperty("id", target.id());
        return value;
    }

    private static JsonObject object(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonObject() ? value.getAsJsonObject(field) : null;
    }

    private static String text(JsonObject value, String field, String fallback) {
        JsonElement element = value != null ? value.get(field) : null;
        return element != null && !element.isJsonNull() && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static String optionalText(JsonObject value, String field) {
        String text = text(value, field, null);
        return text != null && !text.isBlank() ? text.trim() : null;
    }

    private static String chooseNormalized(String first, String second, String conflict) {
        if (first != null && second != null && !normalize(first).equals(normalize(second))) {
            throw new IllegalArgumentException(conflict);
        }
        return first != null ? first : second;
    }

    private static String chooseExact(String first, String second, String conflict) {
        if (first != null && second != null && !first.equals(second)) {
            throw new IllegalArgumentException(conflict);
        }
        return first != null ? first : second;
    }

    private static void putText(JsonObject value, String field, String text) {
        value.add(field, new JsonPrimitive(text != null ? text : ""));
    }

    private static List<String> combine(List<String> first, List<String> second) {
        ArrayList<String> values = new ArrayList<>(first);
        values.addAll(second);
        return List.copyOf(values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static void validateDefaultValue(JsonObject value, String valueType) {
        JsonElement element = value.get("defaultValue");
        if (element == null || element.isJsonNull()) {
            return;
        }
        boolean valid = switch (valueType) {
            case "boolean" -> element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
            case "number", "float", "instant", "duration", "seed" -> finiteNumber(element);
            case "integer" -> finiteInteger(element);
            case "json_object", "map" -> element.isJsonObject();
            case "list", "set", "queue", "stack" -> element.isJsonArray();
            case "string", "function", "flow_id", "command_id", "custom_content_id", "gui_id",
                 "scoreboard_id", "tab_id", "chat_id", "motd_profile_id", "message_rule_id", "recipe_id",
                 "text_template_id", "advancement_tree_id", "dialog_id", "trade_profile_id", "npc_id",
                 "loot_table_id", "worldgen_id", "uuid", "gamemode", "difficulty", "entity_type",
                 "enchantment", "sound", "permission", "permission_group", "team", "region" ->
                element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("Variable default value must match value type " + valueType);
        }
    }

    private static boolean finiteNumber(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            return Double.isFinite(element.getAsDouble());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean finiteInteger(JsonElement element) {
        if (!finiteNumber(element)) {
            return false;
        }
        double value = element.getAsDouble();
        return value == Math.rint(value);
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static void requireType(String type) {
        if (!supports(type)) {
            throw new IllegalArgumentException("Unsupported automation type: " + type);
        }
    }
}
