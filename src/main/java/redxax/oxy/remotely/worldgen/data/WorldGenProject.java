package redxax.oxy.remotely.worldgen.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WorldGenProject {
    public static final int CURRENT_VERSION = 2;
    private String id;
    private int version;
    private WorldGenGraph terrainGraph;
    private WorldGenGraph biomeGraph;
    private WorldGenGraph surfaceGraph;
    private WorldGenGraph caveGraph;
    private WorldGenGraph featureGraph;
    private WorldGenGraph structureGraph;
    private WorldGenGraph spawnGraph;
    private WorldGenProjectSettings settings;
    private List<WorldGenBiomeProfile> biomeProfiles;
    private transient JsonObject opaquePayload;

    public WorldGenProject() {
        this.id = UUID.randomUUID().toString();
        this.version = CURRENT_VERSION;
        this.terrainGraph = new WorldGenGraph();
        this.biomeGraph = new WorldGenGraph();
        this.surfaceGraph = new WorldGenGraph();
        this.caveGraph = new WorldGenGraph();
        this.featureGraph = new WorldGenGraph();
        this.structureGraph = new WorldGenGraph();
        this.spawnGraph = new WorldGenGraph();
        this.settings = new WorldGenProjectSettings();
        this.biomeProfiles = new ArrayList<>();
    }

    public WorldGenGraph graph(WorldGenStage stage) {
        return switch (stage) {
            case TERRAIN -> terrainGraph;
            case BIOME -> biomeGraph;
            case SURFACE -> surfaceGraph;
            case CAVE -> caveGraph;
            case FEATURE -> featureGraph;
            case STRUCTURE -> structureGraph;
            case SPAWN -> spawnGraph;
        };
    }

    public void setGraph(WorldGenStage stage, WorldGenGraph graph) {
        switch (stage) {
            case TERRAIN -> terrainGraph = graph != null ? graph : new WorldGenGraph();
            case BIOME -> biomeGraph = graph != null ? graph : new WorldGenGraph();
            case SURFACE -> surfaceGraph = graph != null ? graph : new WorldGenGraph();
            case CAVE -> caveGraph = graph != null ? graph : new WorldGenGraph();
            case FEATURE -> featureGraph = graph != null ? graph : new WorldGenGraph();
            case STRUCTURE -> structureGraph = graph != null ? graph : new WorldGenGraph();
            case SPAWN -> spawnGraph = graph != null ? graph : new WorldGenGraph();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public WorldGenGraph getTerrainGraph() {
        return terrainGraph;
    }

    public void setTerrainGraph(WorldGenGraph terrainGraph) {
        this.terrainGraph = terrainGraph != null ? terrainGraph : new WorldGenGraph();
    }

    public WorldGenGraph getBiomeGraph() {
        return biomeGraph;
    }

    public void setBiomeGraph(WorldGenGraph biomeGraph) {
        this.biomeGraph = biomeGraph != null ? biomeGraph : new WorldGenGraph();
    }

    public WorldGenGraph getSurfaceGraph() {
        return surfaceGraph;
    }

    public void setSurfaceGraph(WorldGenGraph surfaceGraph) {
        this.surfaceGraph = surfaceGraph != null ? surfaceGraph : new WorldGenGraph();
    }

    public WorldGenGraph getCaveGraph() {
        return caveGraph;
    }

    public void setCaveGraph(WorldGenGraph caveGraph) {
        this.caveGraph = caveGraph != null ? caveGraph : new WorldGenGraph();
    }

    public WorldGenGraph getFeatureGraph() {
        return featureGraph;
    }

    public void setFeatureGraph(WorldGenGraph featureGraph) {
        this.featureGraph = featureGraph != null ? featureGraph : new WorldGenGraph();
    }

    public WorldGenGraph getStructureGraph() {
        return structureGraph;
    }

    public void setStructureGraph(WorldGenGraph structureGraph) {
        this.structureGraph = structureGraph != null ? structureGraph : new WorldGenGraph();
    }

    public WorldGenGraph getSpawnGraph() {
        return spawnGraph;
    }

    public void setSpawnGraph(WorldGenGraph spawnGraph) {
        this.spawnGraph = spawnGraph != null ? spawnGraph : new WorldGenGraph();
    }

    public WorldGenProjectSettings getSettings() {
        return settings;
    }

    public void setSettings(WorldGenProjectSettings settings) {
        this.settings = settings != null ? settings : new WorldGenProjectSettings();
    }

    public List<WorldGenBiomeProfile> getBiomeProfiles() {
        return biomeProfiles;
    }

    public void setBiomeProfiles(List<WorldGenBiomeProfile> biomeProfiles) {
        this.biomeProfiles = biomeProfiles != null ? biomeProfiles : new ArrayList<>();
    }

    JsonObject opaquePayload() {
        return opaquePayload == null ? null : opaquePayload.deepCopy();
    }

    void setOpaquePayload(JsonObject opaquePayload) {
        this.opaquePayload = opaquePayload == null ? null : opaquePayload.deepCopy();
    }
}
