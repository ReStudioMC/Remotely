package redxax.oxy.remotely.worldgen.data;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.rescreen.util.JsonTreeParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenSerializerBrowserContractTest {
    @Test
    void explicitCodecPreservesEveryModelFieldAndExactJsonValues() {
        UUID inputUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        WorldGenGraph terrain = graph("terrain", inputUuid);
        WorldGenProject project = new WorldGenProject();
        project.setId("project");
        project.setVersion(17);
        project.setTerrainGraph(terrain);
        project.setBiomeGraph(graph("biome", inputUuid));
        project.setSurfaceGraph(graph("surface", inputUuid));
        project.setCaveGraph(graph("cave", inputUuid));
        project.setFeatureGraph(graph("feature", inputUuid));
        project.setStructureGraph(graph("structure", inputUuid));
        project.setSpawnGraph(graph("spawn", inputUuid));
        project.setSettings(settings());
        project.setBiomeProfiles(List.of(profile()));

        String serialized = WorldGenSerializer.serializeProjectOwned(project);
        WorldGenProject restored = WorldGenSerializer.deserializeProject(serialized);

        assertNotNull(restored);
        assertEquals(JsonTreeParser.parse(serialized),
            JsonTreeParser.parse(WorldGenSerializer.serializeProjectOwned(restored)));
        Object exactLong = restored.getTerrainGraph().getNodes().get("node").getInputValues().get("exactLong");
        assertInstanceOf(Long.class, exactLong);
        assertEquals(Long.MAX_VALUE, exactLong);
        assertEquals(inputUuid.toString(),
            restored.getTerrainGraph().getNodes().get("node").getInputValues().get("uuid"));
        Map<?, ?> nested = assertInstanceOf(Map.class,
            restored.getTerrainGraph().getNodes().get("node").getInputValues().get("nested"));
        assertEquals(Long.MIN_VALUE, nested.get("exactLong"));
    }

    @Test
    void unknownPayloadFieldsSurviveOwnedFieldUpdatesAtEveryNestedLevel() {
        WorldGenProject source = new WorldGenProject();
        source.setId("unknowns");
        source.setTerrainGraph(graph("terrain", UUID.randomUUID()));
        source.setBiomeProfiles(List.of(profile()));
        JsonObject payload = JsonTreeParser.parse(WorldGenSerializer.serializeProjectOwned(source)).getAsJsonObject();
        payload.addProperty("extensionRoot", "root");
        payload.getAsJsonObject("settings").addProperty("extensionSetting", 42);
        JsonObject terrain = payload.getAsJsonObject("terrainGraph");
        terrain.addProperty("extensionGraph", true);
        terrain.getAsJsonObject("nodes").getAsJsonObject("node").addProperty("extensionNode", "node");
        terrain.getAsJsonArray("connections").get(0).getAsJsonObject().addProperty("extensionConnection", "connection");
        JsonObject profile = payload.getAsJsonArray("biomeProfiles").get(0).getAsJsonObject();
        profile.addProperty("extensionProfile", "profile");
        profile.getAsJsonArray("spawnRules").get(0).getAsJsonObject().addProperty("extensionRule", "rule");

        WorldGenProject restored = WorldGenSerializer.deserializeProject(JsonTreeParser.write(payload));
        restored.getSettings().setSeaLevel(71);
        restored.getTerrainGraph().getNodes().get("node").setX(512.25);
        JsonObject merged = JsonTreeParser.parse(WorldGenSerializer.serializeProject(restored)).getAsJsonObject();

        assertEquals("root", merged.get("extensionRoot").getAsString());
        assertEquals(42, merged.getAsJsonObject("settings").get("extensionSetting").getAsInt());
        JsonObject mergedTerrain = merged.getAsJsonObject("terrainGraph");
        assertTrue(mergedTerrain.get("extensionGraph").getAsBoolean());
        assertEquals("node", mergedTerrain.getAsJsonObject("nodes").getAsJsonObject("node")
            .get("extensionNode").getAsString());
        assertEquals("connection", mergedTerrain.getAsJsonArray("connections").get(0).getAsJsonObject()
            .get("extensionConnection").getAsString());
        JsonObject mergedProfile = merged.getAsJsonArray("biomeProfiles").get(0).getAsJsonObject();
        assertEquals("profile", mergedProfile.get("extensionProfile").getAsString());
        assertEquals("rule", mergedProfile.getAsJsonArray("spawnRules").get(0).getAsJsonObject()
            .get("extensionRule").getAsString());
        assertEquals(71, merged.getAsJsonObject("settings").get("seaLevel").getAsInt());
        assertEquals(512.25, mergedTerrain.getAsJsonObject("nodes").getAsJsonObject("node").get("x").getAsDouble());
    }

    @Test
    void absentFieldsKeepDeclaredModelDefaults() {
        WorldGenProject project = WorldGenSerializer.deserializeProject("{}");

        assertNotNull(project);
        assertEquals(WorldGenProject.CURRENT_VERSION, project.getVersion());
        assertEquals(WorldGenGraph.CURRENT_VERSION, project.getTerrainGraph().getVersion());
        assertEquals(-64, project.getSettings().getMinY());
        assertEquals(320, project.getSettings().getMaxY());
        assertEquals(63, project.getSettings().getSeaLevel());
        assertTrue(project.getSettings().isVanillaBiomesEnabled());
        assertTrue(project.getSettings().isVanillaFeaturesEnabled());
        assertTrue(project.getSettings().isVanillaStructuresEnabled());
        assertTrue(project.getSettings().isVanillaSpawnsEnabled());
        assertTrue(project.getBiomeProfiles().isEmpty());
    }

    private static WorldGenGraph graph(String id, UUID inputUuid) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("exactLong", Long.MAX_VALUE);
        inputs.put("uuid", inputUuid);
        inputs.put("nested", Map.of("exactLong", Long.MIN_VALUE, "enabled", true));
        inputs.put("values", List.of("one", 2L, 3.5, false));
        WorldGenGraph graph = new WorldGenGraph();
        graph.setId(id);
        graph.setVersion(9);
        graph.setNodes(new LinkedHashMap<>(Map.of("node", new WorldGenNode("worldgen:test", 12.5, -8.75, inputs))));
        graph.setConnections(List.of(new WorldGenConnection("node", "output", "node", "input")));
        return graph;
    }

    private static WorldGenProjectSettings settings() {
        WorldGenProjectSettings settings = new WorldGenProjectSettings();
        settings.setSeedPolicy("fixed");
        settings.setMinY(-32);
        settings.setMaxY(512);
        settings.setSeaLevel(80);
        settings.setDefaultBlock("minecraft:deepslate");
        settings.setDefaultFluid("minecraft:lava");
        settings.setDatapackNamespace("browser_contract");
        settings.setGeneratorBackend("plugin");
        settings.setGenerationMode("vanilla");
        settings.setTargetVersion("1.21.4");
        settings.setWorldPreset("amplified");
        settings.setTerrainTemplate("islands");
        settings.setVanillaBiomesEnabled(false);
        settings.setVanillaFeaturesEnabled(false);
        settings.setVanillaStructuresEnabled(false);
        settings.setVanillaSpawnsEnabled(false);
        settings.setVanillaStructureTerrainSafety(false);
        settings.setVanillaStructureSampleRadius(96);
        settings.setVanillaStructureMaxHeightDelta(24);
        settings.setBiomeVanillaFeatureOverrides(new LinkedHashMap<>(Map.of(
            "minecraft:plains", true, "minecraft:desert", false)));
        settings.setPreviewEnvironment("NETHER");
        settings.setActivePreviewPlayer("123e4567-e89b-12d3-a456-426614174001");
        return settings;
    }

    private static WorldGenBiomeProfile profile() {
        WorldGenSpawnRule rule = new WorldGenSpawnRule();
        rule.setEntityType("minecraft:allay");
        rule.setWeight(37);
        rule.setMinGroup(2);
        rule.setMaxGroup(7);
        rule.setCategory("creature");
        rule.setBiomeFilters(List.of("minecraft:plains", "#minecraft:is_forest"));
        rule.setMinY(-20);
        rule.setMaxY(220);
        rule.setBlockBelow("minecraft:moss_block");
        rule.setMinLight(3);
        rule.setMaxLight(12);
        rule.setTime("night");
        rule.setWeather("rain");
        WorldGenBiomeProfile profile = new WorldGenBiomeProfile();
        profile.setId("lush");
        profile.setDisplayName("Lush Test");
        profile.setMode(WorldGenBiomeProfileMode.VANILLA_OVERRIDE);
        profile.setVanillaBaseBiome("minecraft:lush_caves");
        profile.setTemperature(0.75F);
        profile.setHumidity(0.9F);
        profile.setContinentalness(-0.25F);
        profile.setErosion(0.125F);
        profile.setWeirdness(0.625F);
        profile.setSurfaceReference("surface:grass");
        profile.setKeepVanillaFeatures(true);
        profile.setKeepVanillaStructures(true);
        profile.setKeepVanillaSpawns(true);
        profile.setSpawnRules(List.of(rule));
        return profile;
    }
}
