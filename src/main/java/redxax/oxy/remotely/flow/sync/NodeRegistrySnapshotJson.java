package redxax.oxy.remotely.flow.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowJson;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.contract.FlowCategoryMetadata;
import restudio.resync.flow.contract.FlowTypeMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NodeRegistrySnapshotJson {
    public static NodeRegistrySnapshot read(String json) {
        return read(FlowJson.parse(json));
    }

    public static NodeRegistrySnapshot read(JsonElement json) {
        return json != null && json.isJsonObject() ? read(json.getAsJsonObject()) : null;
    }

    public static NodeRegistrySnapshot read(JsonObject json) {
        if (json == null) {
            return null;
        }
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(integer(json, "contractVersion", snapshot.getContractVersion()));
        snapshot.setMinimumClientContractVersion(integer(json, "minimumClientContractVersion", snapshot.getMinimumClientContractVersion()));
        snapshot.setServerIdentity(text(json, "serverIdentity", snapshot.getServerIdentity()));
        snapshot.setCompatibleUntil(longValue(json, "compatibleUntil", snapshot.getCompatibleUntil()));
        snapshot.setCapabilities(strings(json.get("capabilities")));
        snapshot.setRegistryDiagnostics(objectValues(json.get("registryDiagnostics")));
        snapshot.setFullSync(bool(json, "fullSync", snapshot.isFullSync()));
        snapshot.setBaseRegistryChecksum(text(json, "baseRegistryChecksum", snapshot.getBaseRegistryChecksum()));
        if (json.has("registryChecksum")) {
            snapshot.setRegistryChecksum(text(json, "registryChecksum", null));
        }
        snapshot.setGeneratedAt(longValue(json, "generatedAt", snapshot.getGeneratedAt()));
        snapshot.setCatalogGeneration(longValue(json, "catalogGeneration", snapshot.getCatalogGeneration()));
        snapshot.setCatalogChecksum(text(json, "catalogChecksum", snapshot.getCatalogChecksum()));
        snapshot.setCatalogProjectionIdentity(text(json, "catalogProjectionIdentity", snapshot.getCatalogProjectionIdentity()));
        snapshot.setDropContributions(objectList(json.get("dropContributions")));
        snapshot.setFunctionBoundaries(objectList(json.get("functionBoundaries")));
        snapshot.setCatalogMetadata(objectValues(json.get("catalogMetadata")));
        snapshot.setOpaqueData(objectValues(json.get("opaqueData")));
        snapshot.setNodeIds(strings(json.get("nodeIds")));
        snapshot.setPlugins(plugins(json.get("plugins")));
        snapshot.setRemovedPlugins(strings(json.get("removedPlugins")));
        snapshot.setPropertyActions(propertyActions(json.get("propertyActions")));
        snapshot.setPropertyOutputTypes(propertyOutputTypes(json.get("propertyOutputTypes")));
        snapshot.setPropertyMetadata(propertyMetadata(json.get("propertyMetadata")));
        snapshot.setResourceMetadata(resourceMetadata(json.get("resourceMetadata")));
        snapshot.setTypeMetadata(typeMetadata(json.get("typeMetadata")));
        snapshot.setCategoryMetadata(categoryMetadata(json.get("categoryMetadata")));
        snapshot.setOptionSourceMetadata(optionSourceMetadata(json.get("optionSourceMetadata")));
        snapshot.setConversionRules(conversionRules(json.get("conversionRules")));
        return snapshot;
    }

    private static List<NodePluginPayload> plugins(JsonElement value) {
        List<NodePluginPayload> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonObject()) {
                result.add(plugin(item.getAsJsonObject()));
            }
        }
        return result;
    }

    private static NodePluginPayload plugin(JsonObject json) {
        NodePluginPayload payload = new NodePluginPayload();
        payload.setPluginId(text(json, "pluginId", null));
        payload.setVersion(text(json, "version", null));
        payload.setDescription(text(json, "description", null));
        payload.setChecksum(text(json, "checksum", null));
        payload.setOpaqueData(objectValues(json.get("opaqueData")));
        payload.setNodes(nodes(json.get("nodes")));
        return payload;
    }

    private static List<NodeDefinition> nodes(JsonElement value) {
        List<NodeDefinition> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonObject()) {
                result.add(node(item.getAsJsonObject()));
            }
        }
        return result;
    }

    private static NodeDefinition node(JsonObject json) {
        NodeDefinition.NodeCategory category = json.has("category") ? category(json.get("category")) : null;
        NodeDefinition.Builder builder = new NodeDefinition.Builder(text(json, "id", null), text(json, "displayName", null), category);
        if (json.has("color")) {
            builder.color(integer(json, "color", 0));
        }
        if (json.has("priority")) {
            builder.priority(integer(json, "priority", 0));
        }
        if (json.has("hidden")) {
            builder.hidden(bool(json, "hidden", false));
        }
        if (json.has("hiddenReason")) {
            builder.hiddenReason(text(json, "hiddenReason", null));
        }
        if (json.has("owner")) {
            builder.owner(text(json, "owner", null));
        }
        if (json.has("description")) {
            builder.description(text(json, "description", null));
        }
        if (json.has("handler")) {
            builder.handler(text(json, "handler", null));
        }
        if (json.has("handlerConfig")) {
            builder.handlerConfig(objectValuesOrNull(json.get("handlerConfig")));
        }
        if (json.has("trigger")) {
            builder.trigger(bool(json, "trigger", false));
        }
        if (json.has("eventType")) {
            builder.eventType(text(json, "eventType", null));
        }
        if (json.has("aliases")) {
            builder.aliases(strings(json.get("aliases")));
        }
        if (json.has("outputMappings")) {
            builder.outputMappings(outputMappings(json.get("outputMappings")));
        }
        if (json.has("schemaVersion")) {
            builder.schemaVersion(integer(json, "schemaVersion", 0));
        }
        if (json.has("kind")) {
            builder.kind(nodeKind(json.get("kind")));
        }
        if (json.has("availability")) {
            builder.availability(availability(json.get("availability")));
        }
        if (json.has("canonicalId")) {
            builder.canonicalId(text(json, "canonicalId", null));
        }
        if (json.has("legacyIds")) {
            builder.legacyIds(strings(json.get("legacyIds")));
        }
        if (json.has("deprecated")) {
            builder.deprecated(bool(json, "deprecated", false));
        }
        if (json.has("tags")) {
            builder.tags(strings(json.get("tags")));
        }
        if (json.has("examples")) {
            builder.examples(strings(json.get("examples")));
        }
        if (json.has("family")) {
            builder.family(text(json, "family", null));
        }
        if (json.has("recommended")) {
            builder.recommended(bool(json, "recommended", false));
        }
        if (json.has("replacementFor")) {
            builder.replacementFor(text(json, "replacementFor", null));
        }
        if (json.has("authorizationPolicy")) {
            builder.authorizationPolicy(text(json, "authorizationPolicy", null));
        }
        if (json.has("sensitive")) {
            builder.sensitive(bool(json, "sensitive", false));
        }
        if (json.has("destructive")) {
            builder.destructive(bool(json, "destructive", false));
        }
        if (json.has("auditPolicy")) {
            builder.auditPolicy(text(json, "auditPolicy", null));
        }
        if (json.has("confirmationPolicy")) {
            builder.confirmationPolicy(text(json, "confirmationPolicy", null));
        }
        if (json.has("clockDomain")) {
            builder.clockDomain(text(json, "clockDomain", null));
        }
        for (JsonElement item : array(json, "inputs")) {
            if (item.isJsonObject()) {
                builder.input(pin(item.getAsJsonObject(), NodeDefinition.PinDirection.INPUT));
            }
        }
        for (JsonElement item : array(json, "outputs")) {
            if (item.isJsonObject()) {
                builder.output(pin(item.getAsJsonObject(), NodeDefinition.PinDirection.OUTPUT));
            }
        }
        return builder.build();
    }

    private static NodeDefinition.PinDefinition pin(JsonObject json, NodeDefinition.PinDirection fallbackDirection) {
        FlowDataType dataType = null;
        if (json.has("dataType") && !json.get("dataType").isJsonNull()) {
            dataType = FlowDataType.fromString(text(json, "dataType", "any"));
        }
        NodeDefinition.PinType type = enumValue(NodeDefinition.PinType.class, json.get("type"), NodeDefinition.PinType.DATA);
        NodeDefinition.PinDirection direction = enumValue(NodeDefinition.PinDirection.class, json.get("direction"), fallbackDirection);
        NodeDefinition.WidgetType widgetType = enumValue(NodeDefinition.WidgetType.class, json.get("widgetType"), null);
        return new NodeDefinition.PinDefinition(text(json, "name", null), type, direction, dataType,
            widgetType, strings(json.get("options")), text(json, "optionsSource", null), text(json, "defaultValue", null),
            constraints(json.get("constraints")), stringMap(json.get("visibleWhen")), text(json, "description", null),
            bool(json, "optional", false), typeRef(json.get("typeRef")), repeatable(json.get("repeatable")));
    }

    private static NodeDefinition.NodeCategory category(JsonElement value) {
        String id = text(value, null);
        return id == null ? null : NodeDefinition.NodeCategory.fromString(id);
    }

    private static NodeDefinition.NodeKind nodeKind(JsonElement value) {
        String name = text(value, null);
        if (name == null) {
            return null;
        }
        try {
            return NodeDefinition.NodeKind.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static NodeDefinition.Availability availability(JsonElement value) {
        JsonObject json = object(value);
        return json == null ? null : new NodeDefinition.Availability(text(json, "plugin", null), text(json, "platform", null), text(json, "minVersion", null));
    }

    private static NodeDefinition.RepeatablePin repeatable(JsonElement value) {
        JsonObject json = object(value);
        return json == null ? null : new NodeDefinition.RepeatablePin(text(json, "groupId", null), integer(json, "minItems", 0),
            integer(json, "maxItems", 0), text(json, "itemLabel", null));
    }

    private static NodeDefinition.PinConstraints constraints(JsonElement value) {
        JsonObject json = object(value);
        return json == null ? null : new NodeDefinition.PinConstraints(decimalOrNull(json, "min"), decimalOrNull(json, "max"), decimalOrNull(json, "step"));
    }

    private static FlowTypeRef typeRef(JsonElement value) {
        JsonObject json = object(value);
        if (json == null) {
            return null;
        }
        List<FlowTypeRef> arguments = new ArrayList<>();
        for (JsonElement item : array(json, "arguments")) {
            FlowTypeRef argument = typeRef(item);
            if (argument != null) {
                arguments.add(argument);
            }
        }
        return new FlowTypeRef(text(json, "typeId", "any"), arguments);
    }

    private static List<NodeDefinition.PinMapping> outputMappings(JsonElement value) {
        List<NodeDefinition.PinMapping> mappings = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return mappings;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject json = object(item);
            if (json != null) {
                mappings.add(new NodeDefinition.PinMapping(text(json, "source", null), text(json, "target", null)));
            }
        }
        return mappings;
    }

    private static Map<String, Map<String, List<String>>> propertyActions(JsonElement value) {
        JsonObject root = object(value);
        if (root == null) {
            return null;
        }
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> family : root.entrySet()) {
            JsonObject properties = object(family.getValue());
            if (properties == null) {
                continue;
            }
            Map<String, List<String>> actions = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
                actions.put(property.getKey(), strings(property.getValue()));
            }
            result.put(family.getKey(), actions);
        }
        return result;
    }

    private static Map<String, Map<String, FlowDataType>> propertyOutputTypes(JsonElement value) {
        JsonObject root = object(value);
        if (root == null) {
            return null;
        }
        Map<String, Map<String, FlowDataType>> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> family : root.entrySet()) {
            JsonObject properties = object(family.getValue());
            if (properties == null) {
                continue;
            }
            Map<String, FlowDataType> types = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
                types.put(property.getKey(), FlowDataType.fromString(text(property.getValue(), "any")));
            }
            result.put(family.getKey(), types);
        }
        return result;
    }

    private static List<FlowPropertyMetadata> propertyMetadata(JsonElement value) {
        List<FlowPropertyMetadata> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject json = object(item);
            if (json != null) {
                result.add(new FlowPropertyMetadata(text(json, "family", null), text(json, "property", null), typeRef(json.get("type")),
                    strings(json.get("actions")), bool(json, "readable", false), bool(json, "writable", false),
                    bool(json, "observable", false), bool(json, "invokable", false), text(json, "owner", null)));
            }
        }
        return result;
    }

    private static List<FlowResourceMetadata> resourceMetadata(JsonElement value) {
        List<FlowResourceMetadata> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject json = object(item);
            if (json == null) {
                continue;
            }
            FlowResourceMetadata metadata = new FlowResourceMetadata();
            if (json.has("schemaVersion")) metadata.setSchemaVersion(integer(json, "schemaVersion", 0));
            if (json.has("typeId")) metadata.setTypeId(text(json, "typeId", null));
            if (json.has("displayName")) metadata.setDisplayName(text(json, "displayName", null));
            if (json.has("referenceType")) metadata.setReferenceType(text(json, "referenceType", null));
            if (json.has("identityRules")) metadata.setIdentityRules(text(json, "identityRules", null));
            if (json.has("lifecycle")) metadata.setLifecycle(text(json, "lifecycle", null));
            if (json.has("catalogSource")) metadata.setCatalogSource(text(json, "catalogSource", null));
            if (json.has("operations")) metadata.setOperations(strings(json.get("operations")));
            if (json.has("operationAvailability")) metadata.setOperationAvailability(stringMap(json.get("operationAvailability")));
            if (json.has("authoritativeService")) metadata.setAuthoritativeService(text(json, "authoritativeService", null));
            if (json.has("authorizationPolicy")) metadata.setAuthorizationPolicy(text(json, "authorizationPolicy", null));
            if (json.has("audited")) metadata.setAudited(bool(json, "audited", false));
            if (json.has("defaultFolder")) metadata.setDefaultFolder(text(json, "defaultFolder", null));
            if (json.has("owner")) metadata.setOwner(text(json, "owner", null));
            if (json.has("durable")) metadata.setDurable(bool(json, "durable", false));
            if (json.has("changeEvents")) metadata.setChangeEvents(bool(json, "changeEvents", false));
            if (json.has("activeRefresh")) metadata.setActiveRefresh(bool(json, "activeRefresh", false));
            if (json.has("available")) metadata.setAvailable(bool(json, "available", false));
            if (json.has("unavailableReason")) metadata.setUnavailableReason(text(json, "unavailableReason", null));
            result.add(metadata);
        }
        return result;
    }

    private static List<FlowTypeMetadata> typeMetadata(JsonElement value) {
        List<FlowTypeMetadata> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject json = object(item);
            if (json == null) {
                continue;
            }
            FlowTypeMetadata metadata = new FlowTypeMetadata();
            if (json.has("schemaVersion")) metadata.setSchemaVersion(integer(json, "schemaVersion", 0));
            if (json.has("id")) metadata.setId(text(json, "id", null));
            if (json.has("canonicalId")) metadata.setCanonicalId(text(json, "canonicalId", null));
            if (json.has("legacyIds")) metadata.setLegacyIds(strings(json.get("legacyIds")));
            if (json.has("displayName")) metadata.setDisplayName(text(json, "displayName", null));
            if (json.has("owner")) metadata.setOwner(text(json, "owner", null));
            if (json.has("color")) metadata.setColor(integer(json, "color", 0));
            if (json.has("parentId")) metadata.setParentId(text(json, "parentId", null));
            if (json.has("runtimeType")) metadata.setRuntimeType(text(json, "runtimeType", null));
            if (json.has("codecId")) metadata.setCodecId(text(json, "codecId", null));
            if (json.has("codecVersion")) metadata.setCodecVersion(integer(json, "codecVersion", 0));
            if (json.has("transportable")) metadata.setTransportable(bool(json, "transportable", false));
            if (json.has("persistable")) metadata.setPersistable(bool(json, "persistable", false));
            if (json.has("canStringify")) metadata.setCanStringify(bool(json, "canStringify", false));
            if (json.has("literalInput")) metadata.setLiteralInput(bool(json, "literalInput", false));
            if (json.has("literalEditor")) metadata.setLiteralEditor(text(json, "literalEditor", null));
            if (json.has("catalogSource")) metadata.setCatalogSource(text(json, "catalogSource", null));
            if (json.has("objectPin")) metadata.setObjectPin(bool(json, "objectPin", false));
            if (json.has("available")) metadata.setAvailable(bool(json, "available", false));
            if (json.has("unavailableReason")) metadata.setUnavailableReason(text(json, "unavailableReason", null));
            result.add(metadata);
        }
        return result;
    }

    private static List<FlowCategoryMetadata> categoryMetadata(JsonElement value) {
        List<FlowCategoryMetadata> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject json = object(item);
            if (json == null) {
                continue;
            }
            FlowCategoryMetadata metadata = new FlowCategoryMetadata();
            if (json.has("id")) metadata.setId(text(json, "id", null));
            if (json.has("displayName")) metadata.setDisplayName(text(json, "displayName", null));
            if (json.has("color")) metadata.setColor(integer(json, "color", 0));
            if (json.has("priority")) metadata.setPriority(integer(json, "priority", 0));
            if (json.has("groupId")) metadata.setGroupId(text(json, "groupId", null));
            if (json.has("groupName")) metadata.setGroupName(text(json, "groupName", null));
            if (json.has("groupColor")) metadata.setGroupColor(integer(json, "groupColor", 0));
            if (json.has("groupPriority")) metadata.setGroupPriority(integer(json, "groupPriority", 0));
            result.add(metadata);
        }
        return result;
    }

    private static List<FlowOptionSourceMetadata> optionSourceMetadata(JsonElement value) {
        List<FlowOptionSourceMetadata> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject json = object(item);
            if (json == null) {
                continue;
            }
            FlowOptionSourceMetadata metadata = new FlowOptionSourceMetadata();
            if (json.has("id")) metadata.setId(text(json, "id", null));
            if (json.has("provider")) metadata.setProvider(text(json, "provider", null));
            if (json.has("widgetType")) metadata.setWidgetType(text(json, "widgetType", null));
            if (json.has("searchable")) metadata.setSearchable(bool(json, "searchable", false));
            if (json.has("displayName")) metadata.setDisplayName(text(json, "displayName", null));
            if (json.has("valueType")) metadata.setValueType(text(json, "valueType", null));
            if (json.has("contextKeys")) metadata.setContextKeys(strings(json.get("contextKeys")));
            result.add(metadata);
        }
        return result;
    }

    private static List<FlowConversionRule> conversionRules(JsonElement value) {
        List<FlowConversionRule> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject json = object(item);
            if (json == null) {
                continue;
            }
            FlowConversionRule rule = new FlowConversionRule();
            if (json.has("sourceTypeId")) rule.setSourceTypeId(text(json, "sourceTypeId", null));
            if (json.has("targetTypeId")) rule.setTargetTypeId(text(json, "targetTypeId", null));
            if (json.has("implementationId")) rule.setImplementationId(text(json, "implementationId", null));
            if (json.has("safe")) rule.setSafe(bool(json, "safe", false));
            if (json.has("lossy")) rule.setLossy(bool(json, "lossy", false));
            if (json.has("cost")) rule.setCost(integer(json, "cost", 0));
            if (json.has("availability")) rule.setAvailability(text(json, "availability", null));
            result.add(rule);
        }
        return result;
    }

    private static Map<String, String> stringMap(JsonElement value) {
        JsonObject json = object(value);
        if (json == null) {
            return new LinkedHashMap<>();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            result.put(entry.getKey(), text(entry.getValue(), null));
        }
        return result;
    }

    private static Map<String, Object> objectValues(JsonElement value) {
        Map<String, Object> result = objectValuesOrNull(value);
        return result != null ? result : new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> objectList(JsonElement value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonObject()) {
                result.add(objectValues(item));
            }
        }
        return result;
    }

    private static Map<String, Object> objectValuesOrNull(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            return null;
        }
        Object decoded = FlowJson.value(value);
        if (!(decoded instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(FlowJson.text(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static List<String> strings(JsonElement value) {
        List<String> result = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return result;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            result.add(text(item, null));
        }
        return result;
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String text(JsonObject json, String key, String fallback) {
        return text(json == null ? null : json.get(key), json != null && json.has(key) ? null : fallback);
    }

    private static String text(JsonElement value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int integer(JsonObject json, String key, int fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || value.isJsonNull()) {
            return json != null && json.has(key) ? 0 : fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static long longValue(JsonObject json, String key, long fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || value.isJsonNull()) {
            return json != null && json.has(key) ? 0L : fallback;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || value.isJsonNull()) {
            return json != null && json.has(key) ? false : fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static Double decimalOrNull(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, JsonElement value, T fallback) {
        String name = text(value, null);
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private NodeRegistrySnapshotJson() {
    }
}
