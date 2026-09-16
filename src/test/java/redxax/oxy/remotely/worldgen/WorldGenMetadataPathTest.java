package redxax.oxy.remotely.worldgen;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.FlowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldGenMetadataPathTest {
    @Test
    void addReconciliationUsesCanonicalWorldGenFilePath() throws Exception {
        assertEquals("WorldGen/worldgen-folder.json", reconciliationPath(true, "worldgen-folder", "WorldGen"));
        assertEquals("WorldGen/worldgen-full.json", reconciliationPath(true, "worldgen-full",
            "WorldGen/source.json"));
        assertEquals("WorldGen/worldgen-default.json", reconciliationPath(true, "worldgen-default", ""));
        assertEquals("Custom/Nested/worldgen-custom.json", reconciliationPath(true, "worldgen-custom",
            "Custom/Nested"));
    }

    @Test
    void deleteReconciliationPreservesExistingAssetPath() throws Exception {
        assertEquals("WorldGen/worldgen-delete.json", reconciliationPath(false, "worldgen-delete",
            "WorldGen/worldgen-delete.json"));
    }

    private static String reconciliationPath(boolean add, String projectId, String path) throws Exception {
        Class<?> type = Class.forName(
            "redxax.oxy.remotely.worldgen.WorldGenManager$PendingWorldGenMetadataReconciliation");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, String.class, boolean.class,
            WorldGenManager.WorldGenMetadataIntent.class, long.class, String.class, FlowManager.ServerConnectionToken.class,
            long.class, Consumer.class);
        constructor.setAccessible(true);
        Object reconciliation = constructor.newInstance("worldgen-path-" + UUID.randomUUID(), "server", projectId, add,
            new WorldGenManager.WorldGenMetadataIntent(projectId, path, -1), 1L, "hash", null, 1L, null);
        Field field = type.getDeclaredField("path");
        field.setAccessible(true);
        return (String) field.get(reconciliation);
    }
}
