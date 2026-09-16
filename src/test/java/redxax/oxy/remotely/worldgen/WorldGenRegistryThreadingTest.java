package redxax.oxy.remotely.worldgen;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.worldgen.registry.WorldGenNodeDefinition;
import redxax.oxy.remotely.worldgen.registry.WorldGenNodeRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorldGenRegistryThreadingTest {
    @Test
    void registryListenersRunOutsideMonitorInPublicationOrder() {
        WorldGenNodeRegistry registry = WorldGenNodeRegistry.getInstance();
        String serverId = "threading-" + UUID.randomUUID();
        WorldGenNodeDefinition first = WorldGenNodeDefinition.builder("first", "First").build();
        WorldGenNodeDefinition second = WorldGenNodeDefinition.builder("second", "Second").build();
        List<Boolean> monitorStates = new ArrayList<>();
        List<String> observedDefinitions = new ArrayList<>();
        WorldGenNodeRegistry.WorldGenNodeRegistryListener listener = ignored -> {
            monitorStates.add(Thread.holdsLock(registry));
            String definitionId = registry.getAllDefinitions(serverId).iterator().next().getId();
            observedDefinitions.add(definitionId);
            if ("first".equals(definitionId)) {
                registry.replaceDefinitions(serverId, List.of(second));
            }
        };
        registry.addListener(listener);
        try {
            registry.replaceDefinitions(serverId, List.of(first));
            assertEquals(List.of("first", "second"), observedDefinitions);
            assertEquals(List.of(false, false), monitorStates);
        } finally {
            registry.removeListener(listener);
            registry.restoreSnapshot(new WorldGenNodeRegistry.Snapshot(serverId, false, Map.of()), false);
        }
    }

    @Test
    void inboundRegistryPreparationDoesNotSynchronizeTheManagerEntryPoint() throws Exception {
        Method completeSnapshot = WorldGenManager.class.getDeclaredMethod("applyRegistrySnapshot", String.class,
            Collection.class, Object.class, boolean.class, long.class, long.class);
        Method inferredCapabilities = WorldGenManager.class.getDeclaredMethod("applyRegistrySnapshot", String.class,
            Collection.class, Object.class, long.class, long.class);
        Method clearCache = WorldGenManager.class.getDeclaredMethod("clearCache", String.class);

        assertFalse(Modifier.isSynchronized(completeSnapshot.getModifiers()));
        assertFalse(Modifier.isSynchronized(inferredCapabilities.getModifiers()));
        assertFalse(Modifier.isSynchronized(clearCache.getModifiers()));
    }
}
