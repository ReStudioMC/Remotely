package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.identity.Revision;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.diagnostics.DiagnosticValue;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.TraceId;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class ReSyncLifecycleDiagnosticAdapter {
    private static final String OWNER = "restudio.resync";

    private ReSyncLifecycleDiagnosticAdapter() {
    }

    static DiagnosticEvent event(String stage, String defaultServerId, Object... fields) {
        LinkedHashMap<String, Object> values = fields(fields);
        if (defaultServerId != null && !defaultServerId.isBlank()) {
            values.putIfAbsent("serverId", ReSyncLifecycleDiagnosticPolicy.safeText(defaultServerId));
        }
        String normalizedStage = stage(stage);
        return DiagnosticEvent.of(normalizedStage, ReSyncLifecycleDiagnosticPolicy.priority(normalizedStage, values),
            identity(defaultServerId, rawIdentity(fields)), values, Math.max(0L, nonNegativeLong(values.get("elapsedMs"), 0L)));
    }

    private static Map<String, Object> rawIdentity(Object[] fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (fields == null) return values;
        for (int index = 0; index + 1 < fields.length && index < ReSyncLifecycleDiagnosticPolicy.MAX_FIELDS * 2; index += 2) {
            if (fields[index] instanceof String name) values.put(name, fields[index + 1]);
        }
        return values;
    }

    private static LinkedHashMap<String, Object> fields(Object[] rawFields) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (rawFields == null) {
            return values;
        }
        int fieldIndex = 0;
        for (int index = 0; index + 1 < rawFields.length && index < ReSyncLifecycleDiagnosticPolicy.MAX_FIELDS * 2;
             index += 2) {
            String name = fieldName(rawFields[index], fieldIndex);
            if (ReSyncLifecycleDiagnosticPolicy.sensitiveField(name)) {
                continue;
            }
            values.put(name, safeValue(rawFields[index + 1], 0));
            fieldIndex++;
        }
        if (rawFields.length / 2 > fieldIndex) {
            values.put("truncated", true);
        }
        return values;
    }

    private static String fieldName(Object value, int index) {
        String source = value == null ? "" : String.valueOf(value).strip();
        if (source.isEmpty()) {
            return "field" + index;
        }
        StringBuilder result = new StringBuilder(Math.min(128, source.length()));
        for (int position = 0; position < source.length() && result.length() < 128; position++) {
            char character = source.charAt(position);
            if (Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.') {
                result.append(character);
            } else {
                result.append('_');
            }
        }
        if (result.isEmpty() || !Character.isLetter(result.charAt(0))) {
            result.insert(0, "field_");
        }
        return result.toString();
    }

    private static Object safeValue(Object value, int depth) {
        if (value == null) {
            return "";
        }
        if (value instanceof DiagnosticValue diagnosticValue) {
            return safeValue(diagnosticValue.toJava(), depth);
        }
        if (value instanceof String || value instanceof Character || value instanceof Enum<?>) {
            return ReSyncLifecycleDiagnosticPolicy.safeText(value);
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof BigInteger integer) {
            return integer.bitLength() < 63 ? integer.longValue() : ReSyncLifecycleDiagnosticPolicy.safeText(integer);
        }
        if (value instanceof BigDecimal decimal) {
            return ReSyncLifecycleDiagnosticPolicy.safeText(decimal);
        }
        if (value instanceof Float decimal) {
            return Float.isFinite(decimal) ? decimal : ReSyncLifecycleDiagnosticPolicy.safeText(decimal);
        }
        if (value instanceof Double decimal) {
            return Double.isFinite(decimal) ? decimal : ReSyncLifecycleDiagnosticPolicy.safeText(decimal);
        }
        if (value instanceof byte[] bytes) {
            return "[bytes:" + bytes.length + "]";
        }
        if (value instanceof char[] characters) {
            return ReSyncLifecycleDiagnosticPolicy.safeText(new String(characters));
        }
        if (depth >= ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_DEPTH) {
            return "[nested]";
        }
        if (value instanceof Map<?, ?> map) {
            return safeMap(map, depth + 1);
        }
        if (value instanceof Iterable<?> iterable) {
            return safeIterable(iterable, depth + 1);
        }
        if (arrayLength(value) >= 0) {
            return safeArray(value, depth + 1);
        }
        if (value instanceof ServerId || value instanceof ServerResourceLocator || value instanceof ResourceKey
            || value instanceof ContractRef<?> || value instanceof LocalId || value instanceof Revision
            || value instanceof UUID || value instanceof CorrelationId || value instanceof TraceId) {
            return ReSyncLifecycleDiagnosticPolicy.safeText(value);
        }
        return ReSyncLifecycleDiagnosticPolicy.safeText(value);
    }

    private static Map<String, Object> safeMap(Map<?, ?> source, int depth) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        int visited = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (visited >= ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS) {
                break;
            }
            visited++;
            String key = fieldName(entry.getKey(), visited - 1);
            if (ReSyncLifecycleDiagnosticPolicy.sensitiveField(key)) {
                continue;
            }
            result.put(key, safeValue(entry.getValue(), depth));
        }
        if (source.size() > visited) {
            result.put("truncated", true);
        }
        return result;
    }

    private static List<Object> safeIterable(Iterable<?> source, int depth) {
        ArrayList<Object> result = new ArrayList<>();
        int visited = 0;
        for (Object item : source) {
            if (visited >= ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS) {
                break;
            }
            result.add(safeValue(item, depth));
            visited++;
        }
        if (source instanceof Collection<?> collection && collection.size() > visited) {
            result.add("[truncated]");
        }
        return result;
    }

    private static List<Object> safeArray(Object source, int depth) {
        int length = arrayLength(source);
        if (length < 0) {
            return List.of("[array]");
        }
        ArrayList<Object> result = new ArrayList<>(Math.min(length, ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS));
        int limit = Math.min(length, ReSyncLifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS);
        for (int index = 0; index < limit; index++) {
            result.add(safeValue(arrayValue(source, index), depth));
        }
        if (length > limit) {
            result.add("[truncated]");
        }
        return result;
    }

    private static int arrayLength(Object source) {
        if (source instanceof boolean[] values) return values.length;
        if (source instanceof short[] values) return values.length;
        if (source instanceof int[] values) return values.length;
        if (source instanceof long[] values) return values.length;
        if (source instanceof float[] values) return values.length;
        if (source instanceof double[] values) return values.length;
        if (source instanceof Object[] values) return values.length;
        return -1;
    }

    private static Object arrayValue(Object source, int index) {
        if (source instanceof boolean[] values) return values[index];
        if (source instanceof short[] values) return values[index];
        if (source instanceof int[] values) return values[index];
        if (source instanceof long[] values) return values[index];
        if (source instanceof float[] values) return values[index];
        if (source instanceof double[] values) return values[index];
        if (source instanceof Object[] values) return values[index];
        return "[array]";
    }

    private static DiagnosticIdentity identity(String defaultServerId, Map<String, ?> values) {
        ServerId serverId = serverId(values.get("serverId"));
        if (serverId == null) {
            serverId = serverId(defaultServerId);
        }
        ServerResourceLocator resource = resource(serverId, values);
        if (serverId == null && resource != null) serverId = resource.serverId();
        return new DiagnosticIdentity(serverId, resource, operation(values.get("operation")),
            uuid(values.get("requestId")), correlation(values.get("correlationId")), trace(values.get("traceId")),
            uuid(values.get("mutationId")), nonNegative(values.get("generation")),
            nonNegative(values.get("authorityEpoch")), revision(values.get("revision")));
    }

    private static ServerId serverId(Object value) {
        if (value instanceof ServerId id) {
            return id;
        }
        if (value instanceof UUID uuid) {
            return safe(() -> new ServerId(uuid));
        }
        if (value instanceof String text && !text.isBlank()) {
            return safe(() -> ServerId.parseCanonicalText(text));
        }
        return null;
    }

    private static ServerResourceLocator resource(ServerId serverId, Map<String, ?> values) {
        Object resourceValue = values.get("typedKey");
        if (resourceValue == null) {
            resourceValue = values.get("resourceKey");
        }
        if (resourceValue instanceof ServerResourceLocator locator) {
            return locator;
        }
        if (resourceValue instanceof String text && !text.isBlank()) {
            ServerResourceLocator parsed = safe(() -> ServerResourceLocator.parseCanonicalText(text));
            if (parsed != null) {
                return parsed;
            }
        }
        if (resourceValue instanceof ResourceKey key && serverId != null) {
            return safe(() -> new ServerResourceLocator(serverId, key));
        }
        if (resourceValue instanceof String text && serverId != null) {
            ResourceKey key = safe(() -> ResourceKey.parseCanonicalText(text));
            if (key != null) return safe(() -> new ServerResourceLocator(serverId, key));
        }
        Object type = values.get("resourceType");
        Object id = values.get("resourceId");
        if (id == null && resourceValue instanceof String text) {
            int separator = text.indexOf(':');
            if (separator > 0 && separator + 1 < text.length()) {
                type = text.substring(0, separator);
                id = text.substring(separator + 1);
            }
        }
        if (serverId == null || type == null || id == null) {
            return null;
        }
        String typeText = String.valueOf(type).strip();
        String idText = String.valueOf(id).strip();
        if (typeText.isBlank() || idText.isBlank()) {
            return null;
        }
        Object resourceTypeValue = type;
        return safe(() -> {
            ContractRef<ResourceTypeId> resourceType;
            if (resourceTypeValue instanceof ContractRef<?> reference
                && reference.id() instanceof ResourceTypeId resourceTypeId) {
                resourceType = new ContractRef<>(reference.owner(), resourceTypeId, reference.unknown());
            } else if (typeText.indexOf('/') > 0) {
                resourceType = ContractRef.parseCanonicalText(typeText, ResourceTypeId::new);
            } else {
                resourceType = new ContractRef<>(new OwnerId(OWNER), new ResourceTypeId(typeText));
            }
            return new ServerResourceLocator(serverId, resourceType, idText);
        });
    }

    private static ContractRef<OperationId> operation(Object value) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.isBlank()) {
            return null;
        }
        return safe(() -> {
            if (text.indexOf('/') > 0) {
                return ContractRef.parseCanonicalText(text, OperationId::new);
            }
            return new ContractRef<>(new OwnerId(OWNER), new OperationId(text.toLowerCase(Locale.ROOT)));
        });
    }

    private static CorrelationId correlation(Object value) {
        if (value instanceof CorrelationId id) {
            return id;
        }
        UUID uuid = uuid(value);
        return uuid == null ? null : safe(() -> CorrelationId.of(uuid));
    }

    private static TraceId trace(Object value) {
        if (value instanceof TraceId id) {
            return id;
        }
        UUID uuid = uuid(value);
        return uuid == null ? null : safe(() -> TraceId.of(uuid));
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof CorrelationId id) {
            return id.value();
        }
        if (value instanceof TraceId id) {
            return id.value();
        }
        if (value instanceof String text && !text.isBlank()) {
            return safe(() -> UUID.fromString(text));
        }
        return null;
    }

    private static Revision revision(Object value) {
        if (value instanceof Revision revision) {
            return revision;
        }
        Long number = nonNegative(value);
        return number == null ? null : safe(() -> Revision.of(number));
    }

    private static Long nonNegative(Object value) {
        if (value instanceof Revision revision) {
            return revision.value();
        }
        if (value instanceof Number number) {
            long result = number.longValue();
            return Double.isFinite(number.doubleValue()) && number.doubleValue() == result && result >= 0L ? result : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            return safe(() -> {
                long result = Long.parseLong(text);
                return result < 0L ? null : result;
            });
        }
        return null;
    }

    private static long nonNegativeLong(Object value, long fallback) {
        Long result = nonNegative(value);
        return result == null ? fallback : result;
    }

    private static String stage(String value) {
        String source = value == null ? "unknown" : value.strip();
        if (source.isBlank()) {
            return "unknown";
        }
        StringBuilder result = new StringBuilder(Math.min(128, source.length()));
        for (int index = 0; index < source.length() && result.length() < 128; index++) {
            char character = source.charAt(index);
            result.append(Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.'
                ? character : '_');
        }
        if (result.isEmpty() || !Character.isLetter(result.charAt(0))) {
            result.insert(0, "stage_");
        }
        return result.toString();
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
