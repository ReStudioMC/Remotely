package redxax.oxy.remotely.worldgen.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import restudio.rescreen.util.JsonTreeParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorldGenSerializer {
    private static final Set<String> OWNED_GRAPH_FIELDS = graphFields("");
    private static final Set<String> OWNED_PROJECT_FIELDS = projectFields();
    private static final Set<String> GRAPH_MAP_PATHS = Set.of(
        "nodes", "nodes[].inputValues", "terrainGraph.nodes", "terrainGraph.nodes[].inputValues",
        "biomeGraph.nodes", "biomeGraph.nodes[].inputValues", "surfaceGraph.nodes", "surfaceGraph.nodes[].inputValues",
        "caveGraph.nodes", "caveGraph.nodes[].inputValues", "featureGraph.nodes", "featureGraph.nodes[].inputValues",
        "structureGraph.nodes", "structureGraph.nodes[].inputValues", "spawnGraph.nodes", "spawnGraph.nodes[].inputValues",
        "settings.biomeVanillaFeatureOverrides");

    private WorldGenSerializer() {
    }

    public static String serialize(WorldGenGraph graph) {
        if (graph == null) {
            return "null";
        }
        JsonObject serialized = ownedGraph(graph);
        return JsonTreeParser.write(mergePayload(graph.opaquePayload(), serialized, OWNED_GRAPH_FIELDS));
    }

    public static WorldGenGraph deserialize(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonElement parsed = JsonTreeParser.parse(json);
        if (!parsed.isJsonObject()) {
            return null;
        }
        WorldGenGraph graph = graph(parsed.getAsJsonObject());
        graph.setOpaquePayload(logicalPayload(parsed.getAsJsonObject()));
        return graph;
    }

    public static String serializeProject(WorldGenProject project) {
        if (project == null) {
            return "null";
        }
        JsonObject serialized = ownedProject(project);
        return JsonTreeParser.write(mergePayload(project.opaquePayload(), serialized, OWNED_PROJECT_FIELDS));
    }

    public static String serializeProjectOwned(WorldGenProject project) {
        return project == null ? "null" : JsonTreeParser.write(ownedProject(project));
    }

    public static WorldGenProject deserializeProject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonElement parsed = JsonTreeParser.parse(json);
        if (!parsed.isJsonObject()) {
            return null;
        }
        WorldGenProject project = project(parsed.getAsJsonObject());
        project.setOpaquePayload(logicalPayload(parsed.getAsJsonObject()));
        return project;
    }

    private static JsonObject ownedGraph(WorldGenGraph graph) {
        return graph(graph);
    }

    private static JsonObject ownedProject(WorldGenProject project) {
        return project(project);
    }

    private static JsonObject project(WorldGenProject project) {
        JsonObject result = new JsonObject();
        put(result, "id", project.getId());
        result.addProperty("version", project.getVersion());
        result.add("terrainGraph", graph(project.getTerrainGraph()));
        result.add("biomeGraph", graph(project.getBiomeGraph()));
        result.add("surfaceGraph", graph(project.getSurfaceGraph()));
        result.add("caveGraph", graph(project.getCaveGraph()));
        result.add("featureGraph", graph(project.getFeatureGraph()));
        result.add("structureGraph", graph(project.getStructureGraph()));
        result.add("spawnGraph", graph(project.getSpawnGraph()));
        result.add("settings", settings(project.getSettings()));
        JsonArray profiles = new JsonArray();
        for (WorldGenBiomeProfile profile : project.getBiomeProfiles()) {
            profiles.add(profile == null ? JsonNull.INSTANCE : profile(profile));
        }
        result.add("biomeProfiles", profiles);
        return result;
    }

    private static WorldGenProject project(JsonObject root) {
        WorldGenProject project = new WorldGenProject();
        project.setId(string(root, "id", project.getId()));
        project.setVersion(integer(root, "version", project.getVersion()));
        project.setTerrainGraph(graph(object(root, "terrainGraph")));
        project.setBiomeGraph(graph(object(root, "biomeGraph")));
        project.setSurfaceGraph(graph(object(root, "surfaceGraph")));
        project.setCaveGraph(graph(object(root, "caveGraph")));
        project.setFeatureGraph(graph(object(root, "featureGraph")));
        project.setStructureGraph(graph(object(root, "structureGraph")));
        project.setSpawnGraph(graph(object(root, "spawnGraph")));
        project.setSettings(settings(object(root, "settings")));
        List<WorldGenBiomeProfile> profiles = new ArrayList<>();
        for (JsonElement encoded : array(root, "biomeProfiles")) {
            profiles.add(encoded != null && encoded.isJsonObject() ? profile(encoded.getAsJsonObject()) : null);
        }
        project.setBiomeProfiles(profiles);
        return project;
    }

    private static JsonObject graph(WorldGenGraph graph) {
        WorldGenGraph source = graph == null ? new WorldGenGraph() : graph;
        JsonObject result = new JsonObject();
        put(result, "id", source.getId());
        result.addProperty("version", source.getVersion());
        JsonObject nodes = new JsonObject();
        for (Map.Entry<String, WorldGenNode> entry : source.getNodes().entrySet()) {
            nodes.add(entry.getKey(), entry.getValue() == null ? JsonNull.INSTANCE : node(entry.getValue()));
        }
        result.add("nodes", nodes);
        JsonArray connections = new JsonArray();
        for (WorldGenConnection connection : source.getConnections()) {
            connections.add(connection == null ? JsonNull.INSTANCE : connection(connection));
        }
        result.add("connections", connections);
        return result;
    }

    private static WorldGenGraph graph(JsonObject root) {
        WorldGenGraph graph = new WorldGenGraph();
        if (root == null) {
            return graph;
        }
        graph.setId(string(root, "id", graph.getId()));
        graph.setVersion(integer(root, "version", graph.getVersion()));
        Map<String, WorldGenNode> nodes = new LinkedHashMap<>();
        JsonObject encodedNodes = object(root, "nodes");
        if (encodedNodes != null) {
            for (Map.Entry<String, JsonElement> entry : encodedNodes.entrySet()) {
                nodes.put(entry.getKey(), entry.getValue() != null && entry.getValue().isJsonObject()
                    ? node(entry.getValue().getAsJsonObject()) : null);
            }
        }
        graph.setNodes(nodes);
        List<WorldGenConnection> connections = new ArrayList<>();
        for (JsonElement encoded : array(root, "connections")) {
            connections.add(encoded != null && encoded.isJsonObject() ? connection(encoded.getAsJsonObject()) : null);
        }
        graph.setConnections(connections);
        return graph;
    }

    private static JsonObject node(WorldGenNode node) {
        JsonObject result = new JsonObject();
        put(result, "type", node.getType());
        result.addProperty("x", node.getX());
        result.addProperty("y", node.getY());
        result.add("inputValues", value(node.getInputValues()));
        return result;
    }

    private static WorldGenNode node(JsonObject root) {
        WorldGenNode node = new WorldGenNode();
        node.setType(string(root, "type", node.getType()));
        node.setX(decimal(root, "x", node.getX()));
        node.setY(decimal(root, "y", node.getY()));
        node.setInputValues(map(root.get("inputValues")));
        return node;
    }

    private static JsonObject connection(WorldGenConnection connection) {
        JsonObject result = new JsonObject();
        put(result, "sourceNodeId", connection.getSourceNodeId());
        put(result, "sourcePin", connection.getSourcePin());
        put(result, "targetNodeId", connection.getTargetNodeId());
        put(result, "targetPin", connection.getTargetPin());
        return result;
    }

    private static WorldGenConnection connection(JsonObject root) {
        WorldGenConnection connection = new WorldGenConnection();
        connection.setSourceNodeId(string(root, "sourceNodeId", connection.getSourceNodeId()));
        connection.setSourcePin(string(root, "sourcePin", connection.getSourcePin()));
        connection.setTargetNodeId(string(root, "targetNodeId", connection.getTargetNodeId()));
        connection.setTargetPin(string(root, "targetPin", connection.getTargetPin()));
        return connection;
    }

    private static JsonObject settings(WorldGenProjectSettings settings) {
        WorldGenProjectSettings source = settings == null ? new WorldGenProjectSettings() : settings;
        JsonObject result = new JsonObject();
        put(result, "seedPolicy", source.getSeedPolicy());
        result.addProperty("minY", source.getMinY());
        result.addProperty("maxY", source.getMaxY());
        result.addProperty("seaLevel", source.getSeaLevel());
        put(result, "defaultBlock", source.getDefaultBlock());
        put(result, "defaultFluid", source.getDefaultFluid());
        put(result, "datapackNamespace", source.getDatapackNamespace());
        put(result, "generatorBackend", source.getGeneratorBackend());
        put(result, "generationMode", source.getGenerationMode());
        put(result, "targetVersion", source.getTargetVersion());
        put(result, "worldPreset", source.getWorldPreset());
        put(result, "terrainTemplate", source.getTerrainTemplate());
        result.addProperty("vanillaBiomesEnabled", source.isVanillaBiomesEnabled());
        result.addProperty("vanillaFeaturesEnabled", source.isVanillaFeaturesEnabled());
        result.addProperty("vanillaStructuresEnabled", source.isVanillaStructuresEnabled());
        result.addProperty("vanillaSpawnsEnabled", source.isVanillaSpawnsEnabled());
        result.addProperty("vanillaStructureTerrainSafety", source.isVanillaStructureTerrainSafety());
        result.addProperty("vanillaStructureSampleRadius", source.getVanillaStructureSampleRadius());
        result.addProperty("vanillaStructureMaxHeightDelta", source.getVanillaStructureMaxHeightDelta());
        result.add("biomeVanillaFeatureOverrides", value(source.getBiomeVanillaFeatureOverrides()));
        put(result, "previewEnvironment", source.getPreviewEnvironment());
        put(result, "activePreviewPlayer", source.getActivePreviewPlayer());
        return result;
    }

    private static WorldGenProjectSettings settings(JsonObject root) {
        WorldGenProjectSettings settings = new WorldGenProjectSettings();
        if (root == null) {
            return settings;
        }
        settings.setSeedPolicy(string(root, "seedPolicy", settings.getSeedPolicy()));
        settings.setMinY(integer(root, "minY", settings.getMinY()));
        settings.setMaxY(integer(root, "maxY", settings.getMaxY()));
        settings.setSeaLevel(integer(root, "seaLevel", settings.getSeaLevel()));
        settings.setDefaultBlock(string(root, "defaultBlock", settings.getDefaultBlock()));
        settings.setDefaultFluid(string(root, "defaultFluid", settings.getDefaultFluid()));
        settings.setDatapackNamespace(string(root, "datapackNamespace", settings.getDatapackNamespace()));
        settings.setGeneratorBackend(string(root, "generatorBackend", settings.getGeneratorBackend()));
        settings.setGenerationMode(string(root, "generationMode", settings.getGenerationMode()));
        setTargetVersion(settings, string(root, "targetVersion", settings.getTargetVersion()));
        settings.setWorldPreset(string(root, "worldPreset", settings.getWorldPreset()));
        settings.setTerrainTemplate(string(root, "terrainTemplate", settings.getTerrainTemplate()));
        settings.setVanillaBiomesEnabled(bool(root, "vanillaBiomesEnabled", settings.isVanillaBiomesEnabled()));
        settings.setVanillaFeaturesEnabled(bool(root, "vanillaFeaturesEnabled", settings.isVanillaFeaturesEnabled()));
        settings.setVanillaStructuresEnabled(bool(root, "vanillaStructuresEnabled", settings.isVanillaStructuresEnabled()));
        settings.setVanillaSpawnsEnabled(bool(root, "vanillaSpawnsEnabled", settings.isVanillaSpawnsEnabled()));
        settings.setVanillaStructureTerrainSafety(bool(root, "vanillaStructureTerrainSafety", settings.isVanillaStructureTerrainSafety()));
        settings.setVanillaStructureSampleRadius(integer(root, "vanillaStructureSampleRadius", settings.getVanillaStructureSampleRadius()));
        settings.setVanillaStructureMaxHeightDelta(integer(root, "vanillaStructureMaxHeightDelta", settings.getVanillaStructureMaxHeightDelta()));
        settings.setBiomeVanillaFeatureOverrides(booleanMap(root.get("biomeVanillaFeatureOverrides")));
        settings.setPreviewEnvironment(string(root, "previewEnvironment", settings.getPreviewEnvironment()));
        settings.setActivePreviewPlayer(string(root, "activePreviewPlayer", settings.getActivePreviewPlayer()));
        return settings;
    }

    private static void setTargetVersion(WorldGenProjectSettings settings, String targetVersion) {
        try {
            settings.setTargetVersion(targetVersion);
        } catch (RuntimeException ignored) {
        }
    }

    private static JsonObject profile(WorldGenBiomeProfile profile) {
        JsonObject result = new JsonObject();
        put(result, "id", profile.getId());
        put(result, "displayName", profile.getDisplayName());
        put(result, "mode", profile.getMode() == null ? null : profile.getMode().name());
        put(result, "vanillaBaseBiome", profile.getVanillaBaseBiome());
        result.addProperty("temperature", profile.getTemperature());
        result.addProperty("humidity", profile.getHumidity());
        result.addProperty("continentalness", profile.getContinentalness());
        result.addProperty("erosion", profile.getErosion());
        result.addProperty("weirdness", profile.getWeirdness());
        put(result, "surfaceReference", profile.getSurfaceReference());
        result.addProperty("keepVanillaFeatures", profile.isKeepVanillaFeatures());
        result.addProperty("keepVanillaStructures", profile.isKeepVanillaStructures());
        result.addProperty("keepVanillaSpawns", profile.isKeepVanillaSpawns());
        JsonArray rules = new JsonArray();
        for (WorldGenSpawnRule rule : profile.getSpawnRules()) {
            rules.add(rule == null ? JsonNull.INSTANCE : spawnRule(rule));
        }
        result.add("spawnRules", rules);
        return result;
    }

    private static WorldGenBiomeProfile profile(JsonObject root) {
        WorldGenBiomeProfile profile = new WorldGenBiomeProfile();
        profile.setId(string(root, "id", profile.getId()));
        profile.setDisplayName(string(root, "displayName", profile.getDisplayName()));
        profile.setMode(profileMode(string(root, "mode", profile.getMode().name()), profile.getMode()));
        profile.setVanillaBaseBiome(string(root, "vanillaBaseBiome", profile.getVanillaBaseBiome()));
        profile.setTemperature((float) decimal(root, "temperature", profile.getTemperature()));
        profile.setHumidity((float) decimal(root, "humidity", profile.getHumidity()));
        profile.setContinentalness((float) decimal(root, "continentalness", profile.getContinentalness()));
        profile.setErosion((float) decimal(root, "erosion", profile.getErosion()));
        profile.setWeirdness((float) decimal(root, "weirdness", profile.getWeirdness()));
        profile.setSurfaceReference(string(root, "surfaceReference", profile.getSurfaceReference()));
        profile.setKeepVanillaFeatures(bool(root, "keepVanillaFeatures", profile.isKeepVanillaFeatures()));
        profile.setKeepVanillaStructures(bool(root, "keepVanillaStructures", profile.isKeepVanillaStructures()));
        profile.setKeepVanillaSpawns(bool(root, "keepVanillaSpawns", profile.isKeepVanillaSpawns()));
        List<WorldGenSpawnRule> rules = new ArrayList<>();
        for (JsonElement encoded : array(root, "spawnRules")) {
            rules.add(encoded != null && encoded.isJsonObject() ? spawnRule(encoded.getAsJsonObject()) : null);
        }
        profile.setSpawnRules(rules);
        return profile;
    }

    private static WorldGenBiomeProfileMode profileMode(String encoded, WorldGenBiomeProfileMode fallback) {
        try {
            return encoded == null ? fallback : WorldGenBiomeProfileMode.valueOf(encoded);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static JsonObject spawnRule(WorldGenSpawnRule rule) {
        JsonObject result = new JsonObject();
        put(result, "entityType", rule.getEntityType());
        result.addProperty("weight", rule.getWeight());
        result.addProperty("minGroup", rule.getMinGroup());
        result.addProperty("maxGroup", rule.getMaxGroup());
        put(result, "category", rule.getCategory());
        JsonArray filters = new JsonArray();
        for (String filter : rule.getBiomeFilters()) {
            filters.add(filter);
        }
        result.add("biomeFilters", filters);
        result.addProperty("minY", rule.getMinY());
        result.addProperty("maxY", rule.getMaxY());
        put(result, "blockBelow", rule.getBlockBelow());
        result.addProperty("minLight", rule.getMinLight());
        result.addProperty("maxLight", rule.getMaxLight());
        put(result, "time", rule.getTime());
        put(result, "weather", rule.getWeather());
        return result;
    }

    private static WorldGenSpawnRule spawnRule(JsonObject root) {
        WorldGenSpawnRule rule = new WorldGenSpawnRule();
        rule.setEntityType(string(root, "entityType", rule.getEntityType()));
        rule.setWeight(integer(root, "weight", rule.getWeight()));
        rule.setMinGroup(integer(root, "minGroup", rule.getMinGroup()));
        rule.setMaxGroup(integer(root, "maxGroup", rule.getMaxGroup()));
        rule.setCategory(string(root, "category", rule.getCategory()));
        rule.setBiomeFilters(strings(root, "biomeFilters"));
        rule.setMinY(integer(root, "minY", rule.getMinY()));
        rule.setMaxY(integer(root, "maxY", rule.getMaxY()));
        rule.setBlockBelow(string(root, "blockBelow", rule.getBlockBelow()));
        rule.setMinLight(integer(root, "minLight", rule.getMinLight()));
        rule.setMaxLight(integer(root, "maxLight", rule.getMaxLight()));
        rule.setTime(string(root, "time", rule.getTime()));
        rule.setWeather(string(root, "weather", rule.getWeather()));
        return rule;
    }

    private static JsonElement value(Object source) {
        if (source == null) return JsonNull.INSTANCE;
        if (source instanceof JsonElement json) return json.deepCopy();
        if (source instanceof String text) return new JsonPrimitive(text);
        if (source instanceof Character character) return new JsonPrimitive(character);
        if (source instanceof Boolean bool) return new JsonPrimitive(bool);
        if (source instanceof Number number) return new JsonPrimitive(number);
        if (source instanceof UUID uuid) return new JsonPrimitive(uuid.toString());
        if (source instanceof Enum<?> enumValue) return new JsonPrimitive(enumValue.name());
        if (source instanceof Map<?, ?> map) {
            JsonObject result = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) result.add(String.valueOf(entry.getKey()), value(entry.getValue()));
            }
            return result;
        }
        if (source instanceof Iterable<?> iterable) {
            JsonArray result = new JsonArray();
            for (Object item : iterable) result.add(value(item));
            return result;
        }
        if (source instanceof Object[] values) return values(values);
        if (source instanceof boolean[] values) return values(values);
        if (source instanceof byte[] values) return values(values);
        if (source instanceof short[] values) return values(values);
        if (source instanceof int[] values) return values(values);
        if (source instanceof long[] values) return values(values);
        if (source instanceof float[] values) return values(values);
        if (source instanceof double[] values) return values(values);
        if (source instanceof char[] values) return values(values);
        throw new IllegalArgumentException("Unsupported World Generation JSON Value");
    }

    private static Object value(JsonElement source) {
        if (source == null || source.isJsonNull()) return null;
        if (source.isJsonObject()) return map(source);
        if (source.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonElement item : source.getAsJsonArray()) result.add(value(item));
            return result;
        }
        JsonPrimitive primitive = source.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (!primitive.isNumber()) return primitive.getAsString();
        String encoded = primitive.getAsString();
        if (!encoded.contains(".") && !encoded.contains("e") && !encoded.contains("E")) {
            try {
                return Long.parseLong(encoded);
            } catch (NumberFormatException ignored) {
            }
        }
        return Double.parseDouble(encoded);
    }

    private static Map<String, Object> map(JsonElement source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null && source.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                result.put(entry.getKey(), value(entry.getValue()));
            }
        }
        return result;
    }

    private static Map<String, Boolean> booleanMap(JsonElement source) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (source != null && source.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                result.put(entry.getKey(), entry.getValue() == null || entry.getValue().isJsonNull()
                    ? null : entry.getValue().getAsBoolean());
            }
        }
        return result;
    }

    private static JsonArray values(Object[] values) { JsonArray result = new JsonArray(); for (Object item : values) result.add(value(item)); return result; }
    private static JsonArray values(boolean[] values) { JsonArray result = new JsonArray(); for (boolean item : values) result.add(item); return result; }
    private static JsonArray values(byte[] values) { JsonArray result = new JsonArray(); for (byte item : values) result.add(item); return result; }
    private static JsonArray values(short[] values) { JsonArray result = new JsonArray(); for (short item : values) result.add(item); return result; }
    private static JsonArray values(int[] values) { JsonArray result = new JsonArray(); for (int item : values) result.add(item); return result; }
    private static JsonArray values(long[] values) { JsonArray result = new JsonArray(); for (long item : values) result.add(item); return result; }
    private static JsonArray values(float[] values) { JsonArray result = new JsonArray(); for (float item : values) result.add(item); return result; }
    private static JsonArray values(double[] values) { JsonArray result = new JsonArray(); for (double item : values) result.add(item); return result; }
    private static JsonArray values(char[] values) { JsonArray result = new JsonArray(); for (char item : values) result.add(item); return result; }

    private static String string(JsonObject root, String key, String fallback) {
        JsonElement value = root == null ? null : root.get(key);
        if (value == null) return fallback;
        if (value.isJsonNull()) return null;
        try { return value.getAsString(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static int integer(JsonObject root, String key, int fallback) {
        JsonElement value = root == null ? null : root.get(key);
        try { return value == null || value.isJsonNull() ? fallback : value.getAsInt(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static double decimal(JsonObject root, String key, double fallback) {
        JsonElement value = root == null ? null : root.get(key);
        try { return value == null || value.isJsonNull() ? fallback : value.getAsDouble(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        JsonElement value = root == null ? null : root.get(key);
        try { return value == null || value.isJsonNull() ? fallback : value.getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root == null ? null : root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject root, String key) {
        JsonElement value = root == null ? null : root.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static List<String> strings(JsonObject root, String key) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : array(root, key)) {
            result.add(value == null || value.isJsonNull() ? null : value.getAsString());
        }
        return result;
    }

    private static void put(JsonObject target, String key, String value) {
        if (value != null) target.addProperty(key, value);
    }

    private static JsonObject logicalPayload(JsonObject source) {
        return source.deepCopy();
    }

    private static JsonObject mergePayload(JsonObject existing, JsonObject serialized, Collection<String> ownedFields) {
        Set<String> owned = ownedFields == null ? Set.of() : Set.copyOf(ownedFields);
        return mergeObject(existing, serialized, "", owned);
    }

    private static JsonObject mergeObject(JsonObject existing, JsonObject serialized, String path, Set<String> ownedFields) {
        JsonObject merged = existing == null ? new JsonObject() : existing.deepCopy();
        for (String field : directOwnedFields(path, ownedFields)) {
            if (!serialized.has(field)) {
                merged.remove(field);
            }
        }
        for (var entry : serialized.entrySet()) {
            String childPath = path.isBlank() ? entry.getKey() : path + "." + entry.getKey();
            JsonElement previous = existing != null ? existing.get(entry.getKey()) : null;
            merged.add(entry.getKey(), mergeElement(previous, entry.getValue(), childPath, ownedFields));
        }
        return merged;
    }

    private static JsonElement mergeElement(JsonElement existing, JsonElement serialized, String path, Set<String> ownedFields) {
        if (serialized == null || serialized.isJsonNull()) {
            return serialized == null ? JsonNull.INSTANCE : serialized.deepCopy();
        }
        if (serialized.isJsonObject()) {
            if (isGraphMapPath(path)) {
                return mergeMap(existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : null,
                    serialized.getAsJsonObject(), path, ownedFields);
            }
            return mergeObject(existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : null,
                serialized.getAsJsonObject(), path, ownedFields);
        }
        if (!serialized.isJsonArray() || existing == null || !existing.isJsonArray()) {
            return serialized.deepCopy();
        }
        JsonArray merged = new JsonArray();
        List<JsonElement> oldValues = existing.getAsJsonArray().asList();
        Set<Integer> matched = new LinkedHashSet<>();
        List<JsonElement> newValues = serialized.getAsJsonArray().asList();
        for (int index = 0; index < newValues.size(); index++) {
            JsonElement current = newValues.get(index);
            int oldIndex = matchingArrayIndex(current, oldValues, matched, index);
            JsonElement previous = oldIndex >= 0 ? oldValues.get(oldIndex) : null;
            if (oldIndex >= 0) {
                matched.add(oldIndex);
            }
            merged.add(mergeElement(previous, current, path + "[]", ownedFields));
        }
        return merged;
    }

    private static JsonObject mergeMap(JsonObject existing, JsonObject serialized, String path, Set<String> ownedFields) {
        JsonObject merged = new JsonObject();
        for (var entry : serialized.entrySet()) {
            JsonElement previous = existing == null ? null : existing.get(entry.getKey());
            String childPath = path + "." + entry.getKey();
            merged.add(entry.getKey(), mergeElement(previous, entry.getValue(), childPath, ownedFields));
        }
        return merged;
    }

    private static boolean isGraphMapPath(String path) {
        List<String> actual = pathParts(path);
        return GRAPH_MAP_PATHS.stream().map(WorldGenSerializer::pathParts)
            .anyMatch(candidate -> candidate.size() == actual.size() && matchesPath(candidate, actual));
    }

    private static int matchingArrayIndex(JsonElement current, List<JsonElement> oldValues, Set<Integer> matched, int fallback) {
        if (current != null && current.isJsonObject()) {
            JsonObject object = current.getAsJsonObject();
            for (String identity : List.of("id", "key", "name", "uuid", "type")) {
                if (!object.has(identity) || object.get(identity).isJsonNull()) {
                    continue;
                }
                String value = object.get(identity).toString();
                for (int index = 0; index < oldValues.size(); index++) {
                    JsonElement previous = oldValues.get(index);
                    if (matched.contains(index) || previous == null || !previous.isJsonObject()) {
                        continue;
                    }
                    JsonObject previousObject = previous.getAsJsonObject();
                    if (previousObject.has(identity) && value.equals(previousObject.get(identity).toString())) {
                        return index;
                    }
                }
            }
        }
        return fallback < oldValues.size() && !matched.contains(fallback) ? fallback : -1;
    }

    private static Set<String> directOwnedFields(String path, Set<String> ownedFields) {
        Set<String> direct = new LinkedHashSet<>();
        List<String> actual = pathParts(path);
        for (String owned : ownedFields) {
            if (owned == null || owned.isBlank()) {
                continue;
            }
            List<String> candidate = pathParts(owned);
            if (candidate.size() != actual.size() + 1 || !matchesPath(candidate, actual)) {
                continue;
            }
            String field = candidate.getLast();
            if (!"*".equals(field)) {
                direct.add(field);
            }
        }
        return direct;
    }

    private static List<String> pathParts(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String raw : path.split("\\.")) {
            if (raw.endsWith("[]")) {
                parts.add(raw.substring(0, raw.length() - 2));
                parts.add("*");
            } else if (raw.chars().allMatch(Character::isDigit)) {
                parts.add("*");
            } else {
                parts.add(raw);
            }
        }
        return parts;
    }

    private static boolean matchesPath(List<String> candidate, List<String> actual) {
        for (int index = 0; index < actual.size(); index++) {
            String expected = candidate.get(index);
            if (!"*".equals(expected) && !expected.equals(actual.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> graphFields(String prefix) {
        String base = prefix == null || prefix.isBlank() ? "" : prefix + ".";
        return Set.of(
            base + "id", base + "version", base + "nodes", base + "connections",
            base + "nodes[].type", base + "nodes[].x", base + "nodes[].y", base + "nodes[].inputValues",
            base + "connections[].sourceNodeId", base + "connections[].sourcePin",
            base + "connections[].targetNodeId", base + "connections[].targetPin");
    }

    private static Set<String> projectFields() {
        LinkedHashSet<String> fields = new LinkedHashSet<>(List.of(
            "id", "version", "terrainGraph", "biomeGraph", "surfaceGraph", "caveGraph", "featureGraph",
            "structureGraph", "spawnGraph", "settings", "biomeProfiles"));
        for (String graph : List.of("terrainGraph", "biomeGraph", "surfaceGraph", "caveGraph", "featureGraph", "structureGraph", "spawnGraph")) {
            fields.addAll(graphFields(graph));
        }
        fields.addAll(List.of(
            "settings.seedPolicy", "settings.minY", "settings.maxY", "settings.seaLevel", "settings.defaultBlock",
            "settings.defaultFluid", "settings.datapackNamespace", "settings.generatorBackend", "settings.generationMode",
            "settings.targetVersion", "settings.worldPreset", "settings.terrainTemplate", "settings.vanillaBiomesEnabled",
            "settings.vanillaFeaturesEnabled", "settings.vanillaStructuresEnabled", "settings.vanillaSpawnsEnabled",
            "settings.vanillaStructureTerrainSafety", "settings.vanillaStructureSampleRadius", "settings.vanillaStructureMaxHeightDelta",
            "settings.biomeVanillaFeatureOverrides", "settings.previewEnvironment", "settings.activePreviewPlayer",
            "biomeProfiles[].id", "biomeProfiles[].displayName", "biomeProfiles[].mode", "biomeProfiles[].vanillaBaseBiome",
            "biomeProfiles[].temperature", "biomeProfiles[].humidity", "biomeProfiles[].continentalness", "biomeProfiles[].erosion",
            "biomeProfiles[].weirdness", "biomeProfiles[].surfaceReference", "biomeProfiles[].keepVanillaFeatures",
            "biomeProfiles[].keepVanillaStructures", "biomeProfiles[].keepVanillaSpawns", "biomeProfiles[].spawnRules",
            "biomeProfiles[].spawnRules[].entityType", "biomeProfiles[].spawnRules[].weight", "biomeProfiles[].spawnRules[].minGroup",
            "biomeProfiles[].spawnRules[].maxGroup", "biomeProfiles[].spawnRules[].category", "biomeProfiles[].spawnRules[].biomeFilters",
            "biomeProfiles[].spawnRules[].minY", "biomeProfiles[].spawnRules[].maxY", "biomeProfiles[].spawnRules[].blockBelow",
            "biomeProfiles[].spawnRules[].minLight", "biomeProfiles[].spawnRules[].maxLight", "biomeProfiles[].spawnRules[].time",
            "biomeProfiles[].spawnRules[].weather"));
        return Set.copyOf(fields);
    }
}
