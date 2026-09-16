package redxax.oxy.remotely.worldgen.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WorldGenSerializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
            return GSON.toJson(null);
        }
        JsonObject serialized = ownedGraph(graph);
        return GSON.toJson(mergePayload(graph.opaquePayload(), serialized, OWNED_GRAPH_FIELDS));
    }

    public static WorldGenGraph deserialize(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        WorldGenGraph graph = GSON.fromJson(json, WorldGenGraph.class);
        if (graph != null) {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                graph.setOpaquePayload(logicalPayload(parsed.getAsJsonObject()));
            }
        }
        return graph;
    }

    public static String serializeProject(WorldGenProject project) {
        if (project == null) {
            return GSON.toJson(null);
        }
        JsonObject serialized = ownedProject(project);
        return GSON.toJson(mergePayload(project.opaquePayload(), serialized, OWNED_PROJECT_FIELDS));
    }

    public static String serializeProjectOwned(WorldGenProject project) {
        return GSON.toJson(project);
    }

    public static WorldGenProject deserializeProject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        WorldGenProject project = GSON.fromJson(json, WorldGenProject.class);
        if (project != null) {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                project.setOpaquePayload(logicalPayload(parsed.getAsJsonObject()));
            }
        }
        return project;
    }

    private static JsonObject ownedGraph(WorldGenGraph graph) {
        return GSON.toJsonTree(graph).getAsJsonObject();
    }

    private static JsonObject ownedProject(WorldGenProject project) {
        return GSON.toJsonTree(project).getAsJsonObject();
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
