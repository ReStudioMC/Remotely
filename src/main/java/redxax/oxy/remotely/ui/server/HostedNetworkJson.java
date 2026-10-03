package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.network.NetworkPathSync;
import redxax.oxy.remotely.network.NetworkSharedDataPolicy;
import redxax.oxy.remotely.network.RoutingGroup;
import redxax.oxy.remotely.network.SyncRealm;
import redxax.oxy.remotely.ui.server.NetworkOverviewProvider.SaveRequest;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HostedNetworkJson {
    private HostedNetworkJson() {
    }

    static JsonObject save(SaveRequest request) {
        JsonObject result = new JsonObject();
        if (request == null) return result;
        put(result, "name", request.name());
        result.add("routingGroups", routingGroups(request.routingGroups()));
        result.add("syncRealms", syncRealms(request.syncRealms()));
        result.add("features", map(request.features()));
        result.add("sharedDataPolicy", sharedDataPolicy(request.sharedDataPolicy()));
        return result;
    }

    static JsonArray routingGroups(List<RoutingGroup> values) {
        JsonArray result = new JsonArray();
        for (RoutingGroup value : values == null ? List.<RoutingGroup>of() : values) {
            JsonObject item = new JsonObject();
            put(item, "id", value.id()); put(item, "name", value.name()); put(item, "strategy", value.strategy().name());
            item.add("nodeIds", strings(value.nodeIds())); item.add("weights", map(value.weights())); put(item, "fallbackGroupId", value.fallbackGroupId());
            item.add("forcedHosts", strings(value.forcedHosts())); put(item, "permission", value.permission()); result.add(item);
        }
        return result;
    }

    static JsonArray syncRealms(List<SyncRealm> values) {
        JsonArray result = new JsonArray();
        for (SyncRealm value : values == null ? List.<SyncRealm>of() : values) {
            JsonObject item = new JsonObject();
            put(item, "id", value.id()); put(item, "name", value.name()); item.add("nodeIds", strings(value.nodeIds()));
            item.add("dataFamilies", enums(value.dataFamilies())); put(item, "locationPolicy", value.locationPolicy().name());
            item.add("persistentDataNamespaces", strings(value.persistentDataNamespaces())); put(item, "retainedSnapshots", value.retainedSnapshots());
            put(item, "retentionDays", value.retentionDays()); result.add(item);
        }
        return result;
    }

    static JsonObject sharedDataPolicy(NetworkSharedDataPolicy value) {
        JsonObject result = new JsonObject();
        if (value == null) return result;
        put(result, "chatChannelMode", value.chatChannelMode().name()); result.add("chatChannels", strings(value.chatChannels()));
        put(result, "chatRetentionMillis", value.chatRetentionMillis()); put(result, "resourceTypeMode", value.resourceTypeMode().name());
        result.add("resourceTypes", strings(value.resourceTypes())); put(result, "resourceConflictPolicy", value.resourceConflictPolicy().name());
        put(result, "maximumPayloadBytes", value.maximumPayloadBytes()); result.add("pathSyncs", pathSyncs(value.pathSyncs())); return result;
    }

    static JsonArray pathSyncs(List<NetworkPathSync> values) {
        JsonArray result = new JsonArray();
        for (NetworkPathSync value : values == null ? List.<NetworkPathSync>of() : values) {
            JsonObject item = new JsonObject(); put(item, "id", value.id()); put(item, "name", value.name()); put(item, "enabled", value.enabled());
            item.add("nodeIds", strings(value.nodeIds())); item.add("paths", strings(value.paths())); put(item, "conflictPolicy", value.conflictPolicy().name());
            item.add("commands", strings(value.commands())); result.add(item);
        }
        return result;
    }

    static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        if (values != null) for (String value : values) result.add(value == null ? "" : value);
        return result;
    }

    static JsonArray enums(Iterable<? extends Enum<?>> values) {
        JsonArray result = new JsonArray();
        if (values != null) for (Enum<?> value : values) result.add(value == null ? "" : value.name());
        return result;
    }

    static JsonObject map(Map<?, ?> values) {
        JsonObject result = new JsonObject();
        if (values != null) values.forEach((key, value) -> put(result, String.valueOf(key), value));
        return result;
    }

    static void put(JsonObject value, String key, Object item) {
        if (item == null) value.add(key, JsonNull.INSTANCE);
        else if (item instanceof Boolean bool) value.addProperty(key, bool);
        else if (item instanceof Number number) value.addProperty(key, number);
        else value.addProperty(key, String.valueOf(item));
    }

    static JsonObject object(JsonObject value, String key) {
        JsonElement element = value == null ? null : value.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static List<JsonObject> objects(JsonObject value, String key) {
        JsonElement element = value == null ? null : value.get(key);
        if (element == null || !element.isJsonArray()) return List.of();
        List<JsonObject> result = new ArrayList<>();
        element.getAsJsonArray().forEach(item -> { if (item != null && item.isJsonObject()) result.add(item.getAsJsonObject()); });
        return result;
    }

    static List<NetworkOverviewProvider.ServerView> servers(JsonObject value) {
        return objects(value, "servers").stream().map(item -> new NetworkOverviewProvider.ServerView(string(item, "id"), string(item, "name"),
                bool(item, "proxy", false), bool(item, "managed", true), string(item, "hostScope"), string(item, "address"),
                integer(item, "port", 0), string(item, "state"), string(item, "icon"))).toList();
    }

    static String string(JsonObject value, String key) { return string(value, key, ""); }
    static String string(JsonObject value, String key, String fallback) {
        JsonElement item = value == null ? null : value.get(key);
        return item == null || item.isJsonNull() ? fallback : item.isJsonPrimitive() ? item.getAsString() : fallback;
    }
    static boolean bool(JsonObject value, String key, boolean fallback) {
        JsonElement item = value == null ? null : value.get(key);
        try { return item == null || item.isJsonNull() ? fallback : item.getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
    }
    static int integer(JsonObject value, String key, int fallback) {
        JsonElement item = value == null ? null : value.get(key);
        try { return item == null || item.isJsonNull() ? fallback : item.getAsInt(); } catch (RuntimeException ignored) { return fallback; }
    }
    static long longValue(JsonObject value, String key, long fallback) {
        JsonElement item = value == null ? null : value.get(key);
        try { return item == null || item.isJsonNull() ? fallback : item.getAsLong(); } catch (RuntimeException ignored) { return fallback; }
    }
    static double decimal(JsonObject value, String key, double fallback) {
        JsonElement item = value == null ? null : value.get(key);
        try { return item == null || item.isJsonNull() ? fallback : item.getAsDouble(); } catch (RuntimeException ignored) { return fallback; }
    }
    static List<String> strings(JsonObject value, String key) {
        JsonElement item = value == null ? null : value.get(key);
        if (item == null || !item.isJsonArray()) return List.of();
        List<String> result = new ArrayList<>(); item.getAsJsonArray().forEach(entry -> { if (entry != null && entry.isJsonPrimitive()) result.add(entry.getAsString()); }); return List.copyOf(result);
    }
    static Map<String, String> stringMap(JsonObject value, String key) {
        Map<String, String> result = new LinkedHashMap<>(); object(value, key).entrySet().forEach(entry -> { if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), entry.getValue().getAsString()); }); return Map.copyOf(result);
    }
    static Map<String, Boolean> booleanMap(JsonObject value, String key) {
        Map<String, Boolean> result = new LinkedHashMap<>(); object(value, key).entrySet().forEach(entry -> { if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), bool(object(value, key), entry.getKey(), false)); }); return Map.copyOf(result);
    }
    static Map<String, Integer> integerMap(JsonObject value, String key) {
        Map<String, Integer> result = new LinkedHashMap<>(); object(value, key).entrySet().forEach(entry -> { if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), integer(object(value, key), entry.getKey(), 0)); }); return Map.copyOf(result);
    }

    static Map<String, NetworkOverviewProvider.NetworkCapability> capabilities(JsonObject value) {
        Map<String, NetworkOverviewProvider.NetworkCapability> result = new LinkedHashMap<>();
        object(value, "capabilities").entrySet().forEach(entry -> {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) return;
            JsonObject capability = entry.getValue().getAsJsonObject();
            String operation = string(capability, "operation", entry.getKey());
            result.put(entry.getKey(), new NetworkOverviewProvider.NetworkCapability(operation,
                    bool(capability, "supported", false), string(capability, "reason"), string(capability, "transport")));
        });
        return Map.copyOf(result);
    }
}
