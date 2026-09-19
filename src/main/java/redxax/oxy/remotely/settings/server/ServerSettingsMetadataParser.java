package redxax.oxy.remotely.settings.server;

import redxax.oxy.remotely.network.config.StructuredDocumentParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ServerSettingsMetadataParser {
    private final StructuredDocumentParser yamlParser;

    public ServerSettingsMetadataParser() {
        this(BrowserSafeYaml::parse);
    }

    public ServerSettingsMetadataParser(StructuredDocumentParser yamlParser) {
        this.yamlParser = Objects.requireNonNull(yamlParser, "yamlParser");
    }

    public ServerSettingsMetadata parse(String yaml) {
        return parse(yaml, "metadata");
    }

    public ServerSettingsMetadata parse(String yaml, String sourceName) {
        if (yaml == null) {
            throw new IllegalArgumentException("Metadata content is required");
        }
        String source = sourceName == null || sourceName.isBlank() ? "metadata" : sourceName;
        Object root;
        try {
            root = yamlParser.parse(yaml);
        } catch (RuntimeException exception) {
            throw invalid(source, "Could not read YAML metadata", exception);
        }
        return parseRoot(root, source);
    }

    public ServerSettingsMetadata parse(InputStream input) throws IOException {
        return parse(input, "metadata");
    }

    public ServerSettingsMetadata parse(InputStream input, String sourceName) throws IOException {
        Objects.requireNonNull(input, "input");
        return parse(new InputStreamReader(input, StandardCharsets.UTF_8), sourceName);
    }

    public ServerSettingsMetadata parse(Reader reader) {
        return parse(reader, "metadata");
    }

    public ServerSettingsMetadata parse(Reader reader, String sourceName) {
        Objects.requireNonNull(reader, "reader");
        try {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                content.append(buffer, 0, read);
            }
            return parse(content.toString(), sourceName);
        } catch (IOException exception) {
            throw invalid(sourceName, "Could not read metadata", exception);
        }
    }

    public ServerSettingsMetadata parseObject(Object root, String sourceName) {
        return parseRoot(root, sourceName);
    }

    private ServerSettingsMetadata parseRoot(Object root, String sourceName) {
        String source = sourceName == null || sourceName.isBlank() ? "metadata" : sourceName;
        if (root == null) {
            throw invalid(source, "Metadata is empty", null);
        }
        String providerId = providerIdFromSource(source);
        int priority = 0;
        List<?> packValues;
        if (root instanceof Map<?, ?> map) {
            Map<String, Object> values = stringMap(map, source);
            providerId = firstString(values, providerId, "providerId", "provider", "id");
            priority = integer(values.getOrDefault("priority", 0), source + ".priority", false);
            Object packs = values.get("packs");
            if (packs == null && values.containsKey("documents")) {
                packs = List.of(values);
            }
            if (packs == null) {
                throw invalid(source, "Metadata must define packs", null);
            }
            packValues = list(packs, source + ".packs");
        } else if (root instanceof Collection<?> collection) {
            packValues = List.copyOf(collection);
        } else {
            throw invalid(source, "Metadata root must be a map or list", null);
        }
        if (providerId == null || providerId.isBlank()) {
            throw invalid(source, "Provider ID must be nonblank", null);
        }
        List<ServerSettingsPack> packs = new ArrayList<>();
        for (int index = 0; index < packValues.size(); index++) {
            packs.add(parsePack(packValues.get(index), source + ".packs[" + index + "]"));
        }
        return new ServerSettingsMetadata(providerId, priority, packs);
    }

    private ServerSettingsPack parsePack(Object raw, String location) {
        Map<String, Object> values = stringMap(raw, location);
        String id = requiredString(values, location + ".id", "id");
        String name = requiredString(values, location + ".name", "name");
        String description = requiredString(values, location + ".description", "description");
        int priority = integer(values.getOrDefault("priority", 0), location + ".priority", false);
        Object applicable = first(values, "applicableSoftwareIds", "applicableSoftware", "softwareIds", "software", "applicable");
        List<String> applicableIds = stringList(applicable, location + ".applicableSoftwareIds");
        Object documents = values.get("documents");
        List<?> documentValues = list(documents, location + ".documents");
        List<ServerSettingsDocument> parsedDocuments = new ArrayList<>();
        for (int index = 0; index < documentValues.size(); index++) {
            parsedDocuments.add(parseDocument(documentValues.get(index), location + ".documents[" + index + "]"));
        }
        return new ServerSettingsPack(id, name, description, priority, applicableIds, parsedDocuments);
    }

    private ServerSettingsDocument parseDocument(Object raw, String location) {
        Map<String, Object> values = stringMap(raw, location);
        String path = requiredString(values, location + ".path", "path", "relativePath", "file");
        ServerSettingsFormat format = ServerSettingsFormat.parse(requiredString(values, location + ".format", "format", "type"));
        boolean required = booleanValue(first(values, "required", "mustExist"), false, location + ".required");
        boolean createIfMissing = booleanValue(first(values, "createIfMissing", "create", "createWhenMissing"), false, location + ".createIfMissing");
        Object existence = first(values, "existence", "exists", "presence");
        if (existence != null) {
            if (existence instanceof Boolean present) {
                required = present;
            } else if (existence instanceof String value) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                switch (normalized) {
                    case "required", "must_exist", "must-exist" -> required = true;
                    case "optional", "if_present", "if-present" -> required = false;
                    default -> throw invalid(location + ".existence", "Expected required or optional", null);
                }
            } else {
                throw invalid(location + ".existence", "Expected a boolean or required/optional", null);
            }
        }
        List<?> fieldValues = list(values.getOrDefault("fields", List.of()), location + ".fields");
        List<ServerSettingsField> fields = new ArrayList<>();
        for (int index = 0; index < fieldValues.size(); index++) {
            fields.add(parseField(fieldValues.get(index), location + ".fields[" + index + "]"));
        }
        try {
            return new ServerSettingsDocument(path, format, required, createIfMissing, fields);
        } catch (IllegalArgumentException exception) {
            throw invalid(location, exception.getMessage(), exception);
        }
    }

    private ServerSettingsField parseField(Object raw, String location) {
        Map<String, Object> values = stringMap(raw, location);
        String id = requiredString(values, location + ".id", "id");
        String key = requiredString(values, location + ".key", "key", "path", "property");
        ServerSettingsFieldType type = ServerSettingsFieldType.parse(requiredString(values, location + ".type", "type"));
        if (key.contains("*") && (!key.endsWith(".*") || type != ServerSettingsFieldType.MAP)) {
            throw invalid(location + ".key", "Wildcard keys must be terminal MAP paths such as world-settings.*", null);
        }
        String tab = requiredString(values, location + ".tab", "tab", "section");
        String group = requiredString(values, location + ".group", "group", "category");
        String name = requiredString(values, location + ".name", "name", "label");
        String description = requiredString(values, location + ".description", "description", "help");
        boolean nullable = booleanValue(first(values, "nullable", "allowNull"), false, location + ".nullable");
        boolean defaultSpecified = contains(values, "default", "defaultValue");
        Object defaultValue = normalizeDefault(first(values, "default", "defaultValue"), type, location + ".default");
        BigDecimal min = decimal(first(values, "min", "minimum"), location + ".min");
        BigDecimal max = decimal(first(values, "max", "maximum"), location + ".max");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalid(location, "Field minimum cannot exceed maximum", null);
        }
        List<String> options = parseOptions(first(values, "options", "values"), location + ".options");
        if (type == ServerSettingsFieldType.INTEGER || type.integerUnion()) {
            ensureIntegral(min, location + ".min");
            ensureIntegral(max, location + ".max");
        }
        Object disabledRaw = first(values, "disabledValue", "disabled", "disabledSentinel");
        String disabledValue = disabledRaw != null ? String.valueOf(disabledRaw) : null;
        ServerSettingsField.CollectionSchema collection;
        try {
            collection = parseCollection(values.get("collection"), location + ".collection");
        } catch (IllegalArgumentException exception) {
            throw invalid(location + ".collection", exception.getMessage(), exception);
        }
        try {
            return new ServerSettingsField(id, key, type, tab, group, name, description, defaultValue, min, max, options,
                    nullable, defaultSpecified, disabledValue, collection);
        } catch (IllegalArgumentException exception) {
            throw invalid(location, exception.getMessage(), exception);
        }
    }

    private static ServerSettingsField.CollectionSchema parseCollection(Object raw, String location) {
        if (raw == null) return null;
        Map<String, Object> values = stringMap(raw, location);
        requireOnly(values, location, "mode", "ordered", "unique", "keys", "key", "value", "presentation");
        ServerSettingsField.CollectionMode mode = ServerSettingsField.CollectionMode.parse(
                requiredString(values, location + ".mode", "mode"));
        boolean ordered = booleanValue(values.get("ordered"), false, location + ".ordered");
        boolean unique = booleanValue(values.get("unique"), false, location + ".unique");
        List<ServerSettingsField.FixedKey> keys = parseFixedKeys(values.get("keys"), location + ".keys");
        ServerSettingsField.ValueSpec key = parseValueSpec(values.get("key"), location + ".key");
        ServerSettingsField.ValueSpec value = parseValueSpec(values.get("value"), location + ".value");
        ServerSettingsField.CollectionPresentation presentation = parseCollectionPresentation(
                values.get("presentation"), location + ".presentation");
        return new ServerSettingsField.CollectionSchema(mode, ordered, unique, keys, key, value, presentation);
    }

    private static ServerSettingsField.CollectionPresentation parseCollectionPresentation(Object raw, String location) {
        if (raw == null) return null;
        Map<String, Object> values = stringMap(raw, location);
        requireOnly(values, location, "itemName", "itemsName", "addLabel", "emptyLabel", "emptyDescription",
                "identityField", "detailsLabel");
        return new ServerSettingsField.CollectionPresentation(
                firstString(values, null, "itemName"),
                firstString(values, null, "itemsName"),
                firstString(values, null, "addLabel"),
                firstString(values, null, "emptyLabel"),
                firstString(values, null, "emptyDescription"),
                firstString(values, null, "identityField"),
                firstString(values, null, "detailsLabel"));
    }

    private static List<ServerSettingsField.FixedKey> parseFixedKeys(Object raw, String location) {
        if (raw == null) return List.of();
        List<?> values = list(raw, location);
        List<ServerSettingsField.FixedKey> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String itemLocation = location + "[" + index + "]";
            Map<String, Object> item = stringMap(values.get(index), itemLocation);
            requireOnly(item, itemLocation, "value", "label", "description");
            result.add(new ServerSettingsField.FixedKey(
                    requiredString(item, itemLocation + ".value", "value"),
                    requiredString(item, itemLocation + ".label", "label"),
                    firstString(item, "", "description")));
        }
        return List.copyOf(result);
    }

    private static ServerSettingsField.ValueSpec parseValueSpec(Object raw, String location) {
        return parseValueSpec(raw, location, false);
    }

    private static ServerSettingsField.ValueSpec parseValueSpec(Object raw, String location, boolean objectField) {
        if (raw == null) return null;
        Map<String, Object> values = stringMap(raw, location);
        if (objectField) {
            requireOnly(values, location, "key", "name", "description", "type", "min", "max", "options", "catalog",
                    "allowCustom", "sentinels", "fields", "collection", "fieldsFrom");
        } else {
            requireOnly(values, location, "type", "min", "max", "options", "catalog", "allowCustom", "sentinels",
                    "fields", "collection", "fieldsFrom");
        }
        ServerSettingsField.ValueType type = ServerSettingsField.ValueType.parse(
                requiredString(values, location + ".type", "type"));
        BigDecimal min = decimal(values.get("min"), location + ".min");
        BigDecimal max = decimal(values.get("max"), location + ".max");
        List<String> options = parseOptions(values.get("options"), location + ".options");
        ServerSettingsField.Catalog catalog = parseCatalog(values.get("catalog"), location + ".catalog");
        boolean allowCustom = booleanValue(values.get("allowCustom"), false, location + ".allowCustom");
        List<ServerSettingsField.Sentinel> sentinels = parseSentinels(values.get("sentinels"), location + ".sentinels");
        List<ServerSettingsField.ObjectField> fields = parseObjectFields(values.get("fields"), location + ".fields");
        ServerSettingsField.CollectionSchema collection = parseCollection(values.get("collection"), location + ".collection");
        String fieldsFrom = values.containsKey("fieldsFrom")
                ? stringValue(values.get("fieldsFrom"), location + ".fieldsFrom") : null;
        return new ServerSettingsField.ValueSpec(type, min, max, options, catalog, allowCustom, sentinels, fields,
                collection, fieldsFrom);
    }

    private static ServerSettingsField.Catalog parseCatalog(Object raw, String location) {
        if (raw == null) return null;
        Map<String, Object> values = stringMap(raw, location);
        requireOnly(values, location, "source", "field");
        String source = values.containsKey("source") ? stringValue(values.get("source"), location + ".source") : null;
        String field = values.containsKey("field") ? stringValue(values.get("field"), location + ".field") : null;
        return new ServerSettingsField.Catalog(source, field);
    }

    private static List<ServerSettingsField.Sentinel> parseSentinels(Object raw, String location) {
        if (raw == null) return List.of();
        List<?> values = list(raw, location);
        List<ServerSettingsField.Sentinel> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String itemLocation = location + "[" + index + "]";
            Map<String, Object> item = stringMap(values.get(index), itemLocation);
            requireOnly(item, itemLocation, "value", "label", "description", "role", "reference");
            if (!item.containsKey("value")) throw invalid(itemLocation + ".value", "A sentinel value is required", null);
            result.add(new ServerSettingsField.Sentinel(item.get("value"),
                    requiredString(item, itemLocation + ".label", "label"),
                    firstString(item, "", "description"),
                    ServerSettingsField.SentinelRole.parse(requiredString(item, itemLocation + ".role", "role")),
                    parseValueReference(item.get("reference"), itemLocation + ".reference")));
        }
        return List.copyOf(result);
    }

    private static ServerSettingsField.ValueReference parseValueReference(Object raw, String location) {
        if (raw == null) return null;
        Map<String, Object> values = stringMap(raw, location);
        requireOnly(values, location, "document", "field", "fields");
        String document = values.containsKey("document") ? stringValue(values.get("document"), location + ".document") : null;
        String field = values.containsKey("field") ? stringValue(values.get("field"), location + ".field") : null;
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        if (values.containsKey("fields")) {
            Map<String, Object> rawFields = stringMap(values.get("fields"), location + ".fields");
            rawFields.forEach((key, value) -> fields.put(key, stringValue(value, location + ".fields." + key)));
        }
        return new ServerSettingsField.ValueReference(document, field, fields);
    }

    private static List<ServerSettingsField.ObjectField> parseObjectFields(Object raw, String location) {
        if (raw == null) return List.of();
        List<?> values = list(raw, location);
        List<ServerSettingsField.ObjectField> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String itemLocation = location + "[" + index + "]";
            Map<String, Object> item = stringMap(values.get(index), itemLocation);
            ServerSettingsField.ValueSpec value = parseValueSpec(item, itemLocation, true);
            result.add(new ServerSettingsField.ObjectField(
                    requiredString(item, itemLocation + ".key", "key"),
                    requiredString(item, itemLocation + ".name", "name"),
                    firstString(item, "", "description"), value));
        }
        return List.copyOf(result);
    }

    private static void requireOnly(Map<String, Object> values, String location, String... allowed) {
        LinkedHashSet<String> names = new LinkedHashSet<>(List.of(allowed));
        for (String key : values.keySet()) {
            if (!names.contains(key)) throw invalid(location + "." + key, "Unknown collection schema field", null);
        }
    }

    private static Object normalizeDefault(Object raw, ServerSettingsFieldType type, String location) {
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case BOOLEAN -> {
                if (raw instanceof Boolean value) yield value;
                if (raw instanceof String value && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
                    yield Boolean.parseBoolean(value);
                }
                throw invalid(location, "Expected a boolean default", null);
            }
            case BOOLEAN_OR_DEFAULT, BOOLEAN_OR_DISABLED -> {
                String sentinel = type == ServerSettingsFieldType.BOOLEAN_OR_DEFAULT ? "default" : "disabled";
                if (raw instanceof String value && value.equalsIgnoreCase(sentinel)) yield sentinel;
                if (raw instanceof Boolean value) yield value;
                throw invalid(location, "Expected a boolean or sentinel", null);
            }
            case INTEGER -> {
                BigDecimal value = decimal(raw, location);
                ensureIntegral(value, location);
                yield value.toBigIntegerExact();
            }
            case DECIMAL -> decimal(raw, location);
            case INTEGER_OR_DEFAULT, INTEGER_OR_DISABLED -> {
                if (raw instanceof String value && value.equalsIgnoreCase(type == ServerSettingsFieldType.INTEGER_OR_DEFAULT ? "default" : "disabled")) {
                    yield value.toLowerCase(Locale.ROOT);
                }
                BigDecimal value = decimal(raw, location);
                ensureIntegral(value, location);
                yield value.toBigIntegerExact();
            }
            case DECIMAL_OR_DEFAULT, DECIMAL_OR_DISABLED -> {
                String sentinel = type == ServerSettingsFieldType.DECIMAL_OR_DEFAULT ? "default" : "disabled";
                if (raw instanceof String value && value.equalsIgnoreCase(sentinel)) yield sentinel;
                yield decimal(raw, location);
            }
            case TEXT, DURATION, DURATION_OR_DISABLED -> {
                if (!(raw instanceof String value)) {
                    throw invalid(location, "Expected a text default", null);
                }
                if (type == ServerSettingsFieldType.DURATION_OR_DISABLED && value.equalsIgnoreCase("disabled")) {
                    yield "disabled";
                }
                if (type != ServerSettingsFieldType.TEXT) validateDuration(value, location);
                yield value.trim();
            }
            case SELECT -> {
                if (raw == null || raw.toString().isBlank()) {
                    throw invalid(location, "Expected a nonblank select default", null);
                }
                yield raw.toString().trim();
            }
            case LIST -> {
                if (!(raw instanceof Collection<?> value)) {
                    throw invalid(location, "Expected a list default", null);
                }
                yield new ArrayList<>(value);
            }
            case MAP -> {
                if (!(raw instanceof Map<?, ?> value)) {
                    throw invalid(location, "Expected a map default", null);
                }
                LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
                value.forEach((key, nested) -> result.put(key, nested));
                yield result;
            }
        };
    }

    private static List<String> parseOptions(Object raw, String location) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Map<?, ?> map) {
            List<String> options = new ArrayList<>();
            for (Object key : map.keySet()) {
                options.add(stringValue(key, location));
            }
            return uniqueOptions(options, location);
        }
        List<?> values = list(raw, location);
        List<String> options = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> option = stringMap(map, location + "[" + index + "]");
                Object optionValue = first(option, "value", "id", "key");
                options.add(stringValue(optionValue, location + "[" + index + "].value"));
            } else {
                options.add(stringValue(value, location + "[" + index + "]"));
            }
        }
        return uniqueOptions(options, location);
    }

    private static List<String> uniqueOptions(List<String> options, String location) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String option : options) {
            if (option == null || option.isBlank()) {
                throw invalid(location, "Options must be nonblank", null);
            }
            if (!unique.add(option)) {
                throw invalid(location, "Options must be unique", null);
            }
        }
        return List.copyOf(unique);
    }

    private static BigDecimal decimal(Object raw, String location) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            throw invalid(location, "Expected a number", null);
        }
        try {
            return new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException exception) {
            throw invalid(location, "Expected a number", exception);
        }
    }

    private static void ensureIntegral(BigDecimal value, String location) {
        if (value != null && value.stripTrailingZeros().scale() > 0) {
            throw invalid(location, "Expected an integer", null);
        }
    }

    private static int integer(Object raw, String location, boolean required) {
        if (raw == null) {
            if (required) throw invalid(location, "Expected an integer", null);
            return 0;
        }
        BigDecimal value = decimal(raw, location);
        ensureIntegral(value, location);
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(location, "Integer is outside the supported range", exception);
        }
    }

    private static boolean booleanValue(Object raw, boolean defaultValue, String location) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean value) return value;
        if (raw instanceof String value && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(value);
        }
        throw invalid(location, "Expected a boolean", null);
    }

    private static String requiredString(Map<String, Object> values, String location, String... keys) {
        Object value = first(values, keys);
        if (value == null || value.toString().isBlank()) {
            throw invalid(location, "A nonblank value is required", null);
        }
        return value.toString().trim();
    }

    private static String firstString(Map<String, Object> values, String fallback, String... keys) {
        Object value = first(values, keys);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) return values.get(key);
        }
        return null;
    }

    private static boolean contains(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) return true;
        }
        return false;
    }

    private static void validateDuration(String value, String location) {
        String normalized = value.trim();
        if (normalized.isEmpty() || !normalized.matches("(?i)(?:-?\\d+(?:\\.\\d+)?)(?:ms|s|m|h|d|t)?")) {
            throw invalid(location, "Expected a duration such as 250ms, 10s, 5m, 1h, or 1d", null);
        }
    }

    private static List<String> stringList(Object raw, String location) {
        if (raw instanceof String value) {
            return List.of(value);
        }
        List<?> values = list(raw, location);
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            result.add(stringValue(value, location));
        }
        return result;
    }

    private static String stringValue(Object raw, String location) {
        if (raw == null || raw.toString().isBlank()) {
            throw invalid(location, "A nonblank string is required", null);
        }
        return raw.toString().trim();
    }

    private static List<?> list(Object raw, String location) {
        if (raw instanceof Collection<?> collection) {
            return List.copyOf(collection);
        }
        if (raw == null) {
            throw invalid(location, "A list is required", null);
        }
        throw invalid(location, "Expected a list", null);
    }

    private static Map<String, Object> stringMap(Object raw, String location) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid(location, "Expected a map", null);
        }
        return stringMap(map, location);
    }

    private static Map<String, Object> stringMap(Map<?, ?> map, String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw invalid(location, "Metadata keys must be nonblank strings", null);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String providerIdFromSource(String sourceName) {
        String normalized = sourceName == null ? "metadata" : sourceName.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return stem.isBlank() ? "metadata" : stem;
    }

    private static IllegalArgumentException invalid(String location, String message, Throwable cause) {
        return cause == null ? new IllegalArgumentException(location + ": " + message) : new IllegalArgumentException(location + ": " + message, cause);
    }

}
