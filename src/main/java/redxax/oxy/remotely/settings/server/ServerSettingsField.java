package redxax.oxy.remotely.settings.server;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ServerSettingsField {
    public enum CollectionMode {
        SEQUENCE,
        FIXED_MAP,
        DYNAMIC_MAP;

        public static CollectionMode parse(String value) {
            return valueOf(required(value, "collection mode").replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    public enum ValueType {
        TEXT,
        BOOLEAN,
        INTEGER,
        DECIMAL,
        DURATION,
        SELECT,
        LIST,
        MAP,
        OBJECT,
        RAW;

        public static ValueType parse(String value) {
            return valueOf(required(value, "value type").replace('-', '_').toUpperCase(Locale.ROOT));
        }

        private boolean numeric() {
            return this == INTEGER || this == DECIMAL;
        }

        private boolean structured() {
            return this == LIST || this == MAP;
        }
    }

    public enum SentinelRole {
        INHERIT,
        DISABLED,
        ALL;

        public static SentinelRole parse(String value) {
            return valueOf(required(value, "sentinel role").replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    public record FixedKey(String value, String label, String description) {
        public FixedKey {
            value = required(value, "fixed key value");
            label = required(label, "fixed key label");
            description = description == null ? "" : description.trim();
        }

        public FixedKey(String value, String label) {
            this(value, label, "");
        }
    }

    public record CollectionPresentation(String itemName, String itemsName, String addLabel, String emptyLabel,
                                         String emptyDescription, String identityField, String detailsLabel) {
        public CollectionPresentation {
            itemName = optional(itemName);
            itemsName = optional(itemsName);
            addLabel = optional(addLabel);
            emptyLabel = optional(emptyLabel);
            emptyDescription = optional(emptyDescription);
            identityField = optional(identityField);
            detailsLabel = optional(detailsLabel);
        }
    }

    public record Catalog(String source, String field) {
        public Catalog {
            source = optional(source);
            field = optional(field);
            if ((source == null) == (field == null)) {
                throw new IllegalArgumentException("A catalog must define exactly one source or field");
            }
        }
    }

    public record ValueReference(String document, String field, Map<String, String> fields) {
        public ValueReference {
            document = optional(document);
            field = optional(field);
            fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            if ((field == null) == fields.isEmpty()) {
                throw new IllegalArgumentException("A value reference must define exactly one field or keyed fields");
            }
            fields.forEach((key, value) -> {
                required(key, "value reference key");
                required(value, "value reference field");
            });
        }

        public String fieldFor(Object entryKey) {
            return field != null ? field : fields.get(String.valueOf(entryKey));
        }
    }

    public record Sentinel(Object value, String label, String description, SentinelRole role, ValueReference reference) {
        public Sentinel {
            if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
                throw new IllegalArgumentException("A scalar sentinel value is required");
            }
            label = required(label, "sentinel label");
            description = description == null ? "" : description.trim();
            Objects.requireNonNull(role, "sentinel role");
            if (reference != null && role != SentinelRole.INHERIT) {
                throw new IllegalArgumentException("Only inherit sentinels may reference another value");
            }
        }

        public Sentinel(Object value, String label, SentinelRole role, ValueReference reference) {
            this(value, label, "", role, reference);
        }

        public Sentinel(Object value, String label, SentinelRole role) {
            this(value, label, "", role, null);
        }
    }

    public record ObjectField(String key, String name, String description, ValueSpec value) {
        public ObjectField {
            key = required(key, "object field key");
            name = required(name, "object field name");
            description = description == null ? "" : description.trim();
            Objects.requireNonNull(value, "object field value");
        }
    }

    public record ValueSpec(ValueType type, BigDecimal min, BigDecimal max, List<String> options, Catalog catalog,
                            boolean allowCustom, List<Sentinel> sentinels, List<ObjectField> fields,
                            CollectionSchema collection, String fieldsFrom) {
        public ValueSpec {
            Objects.requireNonNull(type, "value type");
            options = options == null ? List.of() : List.copyOf(options);
            sentinels = sentinels == null ? List.of() : List.copyOf(sentinels);
            fields = fields == null ? List.of() : List.copyOf(fields);
            fieldsFrom = optional(fieldsFrom);
            if (!type.numeric() && (min != null || max != null)) {
                throw new IllegalArgumentException("Only numeric collection values may define ranges");
            }
            if (min != null && max != null && min.compareTo(max) > 0) {
                throw new IllegalArgumentException("Collection value minimum cannot exceed maximum");
            }
            if (type == ValueType.INTEGER) {
                ensureIntegral(min, "minimum");
                ensureIntegral(max, "maximum");
            }
            if (type == ValueType.SELECT && options.isEmpty()) {
                throw new IllegalArgumentException("Select collection values need at least one option");
            }
            if (type != ValueType.SELECT && !options.isEmpty()) {
                throw new IllegalArgumentException("Only select collection values may define options");
            }
            if (options.size() != options.stream().distinct().count()) {
                throw new IllegalArgumentException("Collection value options must be unique");
            }
            for (String option : options) {
                required(option, "collection value option");
            }
            if (allowCustom && catalog == null) {
                throw new IllegalArgumentException("Custom catalog values require a catalog");
            }
            if (catalog != null && type != ValueType.TEXT && type != ValueType.SELECT) {
                throw new IllegalArgumentException("Catalogs require text or select collection values");
            }
            if (!fields.isEmpty() && type != ValueType.OBJECT) {
                throw new IllegalArgumentException("Only object collection values may define fields");
            }
            if (fieldsFrom != null && type != ValueType.OBJECT) {
                throw new IllegalArgumentException("Only object collection values may reference fields");
            }
            if (fieldsFrom != null && !fields.isEmpty()) {
                throw new IllegalArgumentException("Object collection values cannot define fields and fieldsFrom together");
            }
            if (type == ValueType.OBJECT && fields.isEmpty() && fieldsFrom == null) {
                throw new IllegalArgumentException("Object collection values require fields or fieldsFrom");
            }
            if (collection != null && !type.structured()) {
                throw new IllegalArgumentException("Only list or map collection values may define a nested collection");
            }
            if (collection == null && type.structured()) {
                throw new IllegalArgumentException("List and map collection values require a nested collection");
            }
            if (collection != null && type == ValueType.LIST && collection.mode() != CollectionMode.SEQUENCE) {
                throw new IllegalArgumentException("List collection values require sequence mode");
            }
            if (collection != null && type == ValueType.MAP && collection.mode() == CollectionMode.SEQUENCE) {
                throw new IllegalArgumentException("Map collection values require map mode");
            }
            if (type == ValueType.RAW && (min != null || max != null || catalog != null || allowCustom
                    || !sentinels.isEmpty() || !fields.isEmpty() || collection != null || fieldsFrom != null)) {
                throw new IllegalArgumentException("Raw collection values cannot define typed behavior");
            }
            if (sentinels.stream().map(Sentinel::value).distinct().count() != sentinels.size()) {
                throw new IllegalArgumentException("Collection value sentinels must be unique");
            }
            if (fields.stream().map(ObjectField::key).distinct().count() != fields.size()) {
                throw new IllegalArgumentException("Object collection field keys must be unique");
            }
        }
    }

    public record CollectionSchema(CollectionMode mode, boolean ordered, boolean unique, List<FixedKey> keys,
                                   ValueSpec key, ValueSpec value, CollectionPresentation presentation) {
        public CollectionSchema {
            Objects.requireNonNull(mode, "collection mode");
            keys = keys == null ? List.of() : List.copyOf(keys);
            Objects.requireNonNull(value, "collection value");
            if (keys.stream().map(FixedKey::value).distinct().count() != keys.size()) {
                throw new IllegalArgumentException("Fixed collection keys must be unique");
            }
            switch (mode) {
                case SEQUENCE -> {
                    if (key != null || !keys.isEmpty()) {
                        throw new IllegalArgumentException("Sequence collections cannot define map keys");
                    }
                }
                case FIXED_MAP -> {
                    if (key != null || keys.isEmpty()) {
                        throw new IllegalArgumentException("Fixed maps require fixed keys and cannot define a dynamic key");
                    }
                }
                case DYNAMIC_MAP -> {
                    if (key == null || !keys.isEmpty()) {
                        throw new IllegalArgumentException("Dynamic maps require a key schema and cannot define fixed keys");
                    }
                }
            }
        }

        public CollectionSchema(CollectionMode mode, boolean ordered, boolean unique, List<FixedKey> keys,
                                ValueSpec key, ValueSpec value) {
            this(mode, ordered, unique, keys, key, value, null);
        }
    }

    public enum Type {
        BOOLEAN,
        BOOLEAN_OR_DEFAULT,
        BOOLEAN_OR_DISABLED,
        INTEGER,
        INTEGER_OR_DEFAULT,
        INTEGER_OR_DISABLED,
        DECIMAL,
        DECIMAL_OR_DEFAULT,
        DECIMAL_OR_DISABLED,
        TEXT,
        SELECT,
        DURATION,
        DURATION_OR_DISABLED,
        LIST,
        MAP;

        public ServerSettingsFieldType toFieldType() {
            return ServerSettingsFieldType.valueOf(name());
        }
    }

    private final String id;
    private final String key;
    private final ServerSettingsFieldType type;
    private final String tab;
    private final String group;
    private final String name;
    private final String description;
    private final Object defaultValue;
    private final boolean defaultSpecified;
    private final boolean nullable;
    private final BigDecimal min;
    private final BigDecimal max;
    private final List<String> options;
    private final String disabledValue;
    private final CollectionSchema collection;

    public ServerSettingsField(String id, String key, ServerSettingsFieldType type, String tab, String group, String name,
                               String description, Object defaultValue, BigDecimal min, BigDecimal max, List<String> options) {
        this(id, key, type, tab, group, name, description, defaultValue, min, max, options, false, defaultValue != null, null);
    }

    public ServerSettingsField(String id, String key, ServerSettingsFieldType type, String tab, String group, String name,
                               String description, Object defaultValue, BigDecimal min, BigDecimal max, List<String> options,
                               boolean nullable) {
        this(id, key, type, tab, group, name, description, defaultValue, min, max, options, nullable, defaultValue != null, null);
    }

    public ServerSettingsField(String id, String key, ServerSettingsFieldType type, String tab, String group, String name,
                               String description, Object defaultValue, BigDecimal min, BigDecimal max, List<String> options,
                               boolean nullable, boolean defaultSpecified) {
        this(id, key, type, tab, group, name, description, defaultValue, min, max, options, nullable, defaultSpecified, null);
    }

    public ServerSettingsField(String id, String key, ServerSettingsFieldType type, String tab, String group, String name,
                               String description, Object defaultValue, BigDecimal min, BigDecimal max, List<String> options,
                               boolean nullable, boolean defaultSpecified, String disabledValue) {
        this(id, key, type, tab, group, name, description, defaultValue, min, max, options, nullable, defaultSpecified,
                disabledValue, null);
    }

    public ServerSettingsField(String id, String key, ServerSettingsFieldType type, String tab, String group, String name,
                               String description, Object defaultValue, BigDecimal min, BigDecimal max, List<String> options,
                               boolean nullable, boolean defaultSpecified, String disabledValue, CollectionSchema collection) {
        this.id = required(id, "id");
        this.key = required(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.tab = required(tab, "tab");
        this.group = required(group, "group");
        this.name = required(name, "name");
        this.description = required(description, "description");
        this.defaultValue = immutableValue(defaultValue);
        this.defaultSpecified = defaultSpecified;
        this.nullable = nullable;
        this.min = min;
        this.max = max;
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("Field minimum cannot exceed maximum: " + id);
        }
        this.options = options == null ? List.of() : List.copyOf(options);
        this.disabledValue = disabledValue != null && !disabledValue.isBlank() ? disabledValue.trim()
                : collection == null ? inferDisabledValue(type, description) : null;
        this.collection = collection;
        validateValueBounds();
    }

    public ServerSettingsField(String id, String key, Type type, String tab, String group, String name, String description,
                               Object defaultValue, BigDecimal min, BigDecimal max, List<String> options) {
        this(id, key, Objects.requireNonNull(type, "type").toFieldType(), tab, group, name, description, defaultValue, min, max, options);
    }

    public ServerSettingsField(String id, String key, Type type, String tab, String group, String name, String description,
                               Object defaultValue, BigDecimal min, BigDecimal max, List<String> options, boolean nullable) {
        this(id, key, Objects.requireNonNull(type, "type").toFieldType(), tab, group, name, description, defaultValue, min, max, options, nullable);
    }

    public ServerSettingsField(String id, String key, Type type, String tab, String group, String name, String description,
                               Object defaultValue, BigDecimal min, BigDecimal max, List<String> options,
                               boolean nullable, boolean defaultSpecified) {
        this(id, key, Objects.requireNonNull(type, "type").toFieldType(), tab, group, name, description, defaultValue, min, max, options, nullable, defaultSpecified, null);
    }

    public ServerSettingsField(String id, String key, Type type, String tab, String group, String name, String description,
                               Object defaultValue, BigDecimal min, BigDecimal max, List<String> options,
                               boolean nullable, boolean defaultSpecified, String disabledValue) {
        this(id, key, Objects.requireNonNull(type, "type").toFieldType(), tab, group, name, description, defaultValue, min, max, options, nullable, defaultSpecified, disabledValue);
    }

    public String id() {
        return id;
    }

    public String key() {
        return key;
    }

    public ServerSettingsFieldType type() {
        return type;
    }

    public ServerSettingsFieldType fieldType() {
        return type;
    }

    public String tab() {
        return tab;
    }

    public String group() {
        return group;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Object defaultValue() {
        return defaultValue;
    }

    public Object defaultValueOrNull() {
        return defaultValue;
    }

    public boolean defaultSpecified() {
        return defaultSpecified;
    }

    public boolean nullable() {
        return nullable;
    }

    public BigDecimal min() {
        return min;
    }

    public BigDecimal max() {
        return max;
    }

    public BigDecimal minimum() {
        return min;
    }

    public BigDecimal maximum() {
        return max;
    }

    public List<String> options() {
        return options;
    }

    public String disabledValue() {
        return disabledValue;
    }

    public CollectionSchema collection() {
        return collection;
    }

    private static String inferDisabledValue(ServerSettingsFieldType type, String description) {
        if (type == ServerSettingsFieldType.INTEGER_OR_DISABLED || type == ServerSettingsFieldType.DECIMAL_OR_DISABLED
                || type == ServerSettingsFieldType.BOOLEAN_OR_DISABLED || type == ServerSettingsFieldType.DURATION_OR_DISABLED) {
            return "disabled";
        }
        if (description == null || description.isBlank()) {
            return null;
        }
        String lower = description.toLowerCase(Locale.ROOT);
        if (lower.contains("-1 disables") || lower.contains("use -1 to disable") || lower.contains("-1 to disable")
                || lower.contains("set to -1 to disable") || lower.contains("-1 for vanilla timing") || lower.contains("-1 to turn off")) {
            return "-1";
        }
        if (lower.contains("0 disables") || lower.contains("use 0 to disable") || lower.contains("0 to disable")
                || lower.contains("set to 0 to disable") || lower.contains("0 to turn off")) {
            return "0";
        }
        if (lower.contains("use none to disable") || lower.contains("none to disable")) {
            return "none";
        }
        return null;
    }

    private void validateValueBounds() {
        if (!type.numeric() && (min != null || max != null)) {
            throw new IllegalArgumentException("Only numeric fields may define ranges: " + id);
        }
        if (type == ServerSettingsFieldType.SELECT && options.isEmpty()) {
            throw new IllegalArgumentException("Select fields need at least one option: " + id);
        }
        if (type != ServerSettingsFieldType.SELECT && type != ServerSettingsFieldType.LIST && !options.isEmpty()) {
            throw new IllegalArgumentException("Only select and list fields may define options: " + id);
        }
        for (String option : options) {
            required(option, "option");
        }
        if (options.size() != options.stream().distinct().count()) {
            throw new IllegalArgumentException("Field options must be unique: " + id);
        }
        if (defaultSpecified && defaultValue == null && !nullable) {
            throw new IllegalArgumentException("Null defaults require a nullable field: " + id);
        }
        if (defaultValue != null) {
            switch (type) {
                case BOOLEAN -> requireType(defaultValue instanceof Boolean, "boolean");
                case BOOLEAN_OR_DEFAULT, BOOLEAN_OR_DISABLED -> {
                    requireType(defaultValue instanceof Boolean || defaultValue instanceof String, "boolean or sentinel");
                    String sentinel = type == ServerSettingsFieldType.BOOLEAN_OR_DEFAULT ? "default"
                            : disabledValue != null ? disabledValue : "disabled";
                    if (defaultValue instanceof String value && !value.equalsIgnoreCase(sentinel)) {
                        throw new IllegalArgumentException("Invalid boolean union sentinel: " + id);
                    }
                }
                case INTEGER -> {
                    requireType(isIntegral(defaultValue), "integer");
                    validateNumericDefault();
                }
                case DECIMAL -> {
                    requireType(defaultValue instanceof Number, "decimal");
                    validateNumericDefault();
                }
                case INTEGER_OR_DEFAULT, INTEGER_OR_DISABLED -> {
                    requireType(defaultValue instanceof Number || defaultValue instanceof String, "integer or sentinel");
                    if (defaultValue instanceof Number) {
                        validateNumericDefault();
                        if (!isIntegral(defaultValue)) throw new IllegalArgumentException("Integer union default must be integral: " + id);
                    } else if (!defaultValue.equals(type == ServerSettingsFieldType.INTEGER_OR_DEFAULT ? "default"
                            : disabledValue != null ? disabledValue : "disabled")) {
                        throw new IllegalArgumentException("Invalid integer union sentinel: " + id);
                    }
                }
                case DECIMAL_OR_DEFAULT, DECIMAL_OR_DISABLED -> {
                    requireType(defaultValue instanceof Number || defaultValue instanceof String, "decimal or sentinel");
                    if (defaultValue instanceof Number) validateNumericDefault();
                    else if (!defaultValue.equals(type == ServerSettingsFieldType.DECIMAL_OR_DEFAULT ? "default"
                            : disabledValue != null ? disabledValue : "disabled")) throw new IllegalArgumentException("Invalid decimal union sentinel: " + id);
                }
                case TEXT, DURATION, DURATION_OR_DISABLED -> requireType(defaultValue instanceof String, "text");
                case SELECT -> {
                    requireType(defaultValue instanceof String, "select value");
                    if (!options.contains(defaultValue)) {
                        throw new IllegalArgumentException("Select default is not an option: " + id);
                    }
                }
                case LIST -> requireType(defaultValue instanceof List<?>, "list");
                case MAP -> requireType(defaultValue instanceof Map<?, ?>, "map");
            }
        }
        if (collection != null) {
            if (type == ServerSettingsFieldType.LIST && collection.mode() != CollectionMode.SEQUENCE) {
                throw new IllegalArgumentException("List fields require sequence collection mode: " + id);
            }
            if (type == ServerSettingsFieldType.MAP && collection.mode() == CollectionMode.SEQUENCE) {
                throw new IllegalArgumentException("Map fields require map collection mode: " + id);
            }
            if (type != ServerSettingsFieldType.LIST && type != ServerSettingsFieldType.MAP) {
                throw new IllegalArgumentException("Only list and map fields may define a collection schema: " + id);
            }
        }
    }

    private void validateNumericDefault() {
        BigDecimal value = numericValue(defaultValue);
        if (min != null && value.compareTo(min) < 0 || max != null && value.compareTo(max) > 0) {
            throw new IllegalArgumentException("Field default is outside its range: " + id);
        }
        if ((type == ServerSettingsFieldType.INTEGER || type.integerUnion()) && value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Integer field default must be integral: " + id);
        }
    }

    private static boolean isIntegral(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        return numericValue(number).stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal numericValue(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("A numeric value is required");
        }
        return new BigDecimal(number.toString());
    }

    private static void ensureIntegral(BigDecimal value, String name) {
        if (value != null && value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Collection value " + name + " must be integral");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(ServerSettingsField::immutableValue).toList());
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> immutable = new LinkedHashMap<>();
            map.forEach((key, nested) -> immutable.put(immutableValue(key), immutableValue(nested)));
            return Collections.unmodifiableMap(immutable);
        }
        throw new IllegalArgumentException("Unsupported default value type");
    }

    private static void requireType(boolean condition, String expected) {
        if (!condition) {
            throw new IllegalArgumentException("Expected " + expected + " default value");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A nonblank field " + name + " is required");
        }
        return value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ServerSettingsField that)) return false;
        return id.equals(that.id) && key.equals(that.key) && type == that.type && tab.equals(that.tab) && group.equals(that.group)
                && name.equals(that.name) && description.equals(that.description) && Objects.equals(defaultValue, that.defaultValue)
                && defaultSpecified == that.defaultSpecified && nullable == that.nullable
                && Objects.equals(min, that.min) && Objects.equals(max, that.max) && options.equals(that.options)
                && Objects.equals(disabledValue, that.disabledValue) && Objects.equals(collection, that.collection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, key, type, tab, group, name, description, defaultValue, defaultSpecified, nullable, min, max, options,
                disabledValue, collection);
    }
}
