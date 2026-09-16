package redxax.oxy.remotely.worldgen;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.contract.WorldGenGenerationMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenManagerTest {
    private final WorldGenManager manager = WorldGenManager.getInstance();

    @Test
    void createsGameOwnedVanillaProjects() {
        WorldGenProject project = manager.createProjectTemplate("Vanilla", "Survival", "survival");

        assertEquals(WorldGenGenerationMode.VANILLA.id(), project.getSettings().getGenerationMode());
        assertEquals("overworld", project.getSettings().getTerrainTemplate());
        assertTrue(project.getTerrainGraph().getNodes().isEmpty());
        assertTrue(project.getSurfaceGraph().getNodes().isEmpty());
        assertTrue(project.getCaveGraph().getNodes().isEmpty());
        assertTrue(project.getFeatureGraph().getNodes().values().stream().anyMatch(node -> "worldgen:output_features".equals(node.getType())));
    }

    @Test
    void preservesCreativeHybridProjects() {
        WorldGenProject project = manager.createProjectTemplate("Hybrid", "Continental", "creative");

        assertEquals(WorldGenGenerationMode.HYBRID.id(), project.getSettings().getGenerationMode());
        assertEquals("continental", project.getSettings().getTerrainTemplate());
        assertFalse(project.getTerrainGraph().getNodes().isEmpty());
    }
}
