package redxax.oxy.remotely.worldgen.registry;

import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WorldGenNodeRegistry {
    private static final String CANONICAL_OWNER = "worldgen";
    private static final String CANONICAL_OWNER_PREFIX = CANONICAL_OWNER + ":";
    private static final Comparator<WorldGenNodeDefinition> DEFINITION_ORDER = Comparator
        .comparingInt(WorldGenNodeDefinition::getPriority)
        .thenComparing(definition -> canonicalIdentity(definition));
    private static final WorldGenNodeRegistry INSTANCE = new WorldGenNodeRegistry();
    private final Map<String, Map<String, WorldGenNodeDefinition>> definitions = new LinkedHashMap<>();
    private final List<WorldGenNodeRegistryListener> listeners = new ArrayList<>();
    private final ArrayDeque<Publication> publications = new ArrayDeque<>();
    private boolean publishing;

    public static WorldGenNodeRegistry getInstance() {
        return INSTANCE;
    }

    public interface WorldGenNodeRegistryListener {
        void onRegistryUpdated(String serverId);
    }

    public synchronized void register(String serverId, WorldGenNodeDefinition definition) {
        requireServerId(serverId);
        requireDefinition(definition);
        Map<String, WorldGenNodeDefinition> candidate = new LinkedHashMap<>();
        Map<String, WorldGenNodeDefinition> current = definitions.get(serverId);
        if (current != null) {
            candidate.putAll(current);
        }
        String identity = canonicalIdentity(definition);
        if (candidate.values().stream().anyMatch(existing -> identity.equals(canonicalIdentity(existing)))) {
            throw new IllegalArgumentException("Duplicate World Generation node definition: " + identity);
        }
        candidate.put(definition.getId(), definition);
        definitions.put(serverId, immutableDefinitions(candidate));
    }

    public void replaceDefinitions(String serverId, Collection<WorldGenNodeDefinition> newDefinitions) {
        replaceDefinitions(serverId, newDefinitions, true);
    }

    public void replaceDefinitions(String serverId, Collection<WorldGenNodeDefinition> newDefinitions,
                                   boolean notify) {
        Snapshot candidate = prepareSnapshot(serverId, newDefinitions);
        boolean drain;
        synchronized (this) {
            definitions.put(serverId, candidate.definitions());
            drain = notify && enqueuePublication(serverId);
        }
        if (drain) {
            drainPublications();
        }
    }

    public Snapshot prepareSnapshot(String serverId, Collection<WorldGenNodeDefinition> newDefinitions) {
        requireServerId(serverId);
        return new Snapshot(serverId, true, stageDefinitions(newDefinitions));
    }

    public Snapshot captureSnapshot(String serverId) {
        Map<String, WorldGenNodeDefinition> serverDefinitions;
        synchronized (this) {
            serverDefinitions = definitions.get(serverId);
        }
        return new Snapshot(serverId, serverDefinitions != null, serverDefinitions != null
            ? serverDefinitions : Map.of());
    }

    public boolean restoreSnapshot(Snapshot snapshot) {
        return restoreSnapshot(snapshot, true);
    }

    public boolean restoreSnapshot(Snapshot snapshot, boolean notify) {
        if (snapshot == null || snapshot.serverId() == null) {
            return false;
        }
        boolean drain;
        synchronized (this) {
            if (!snapshot.present()) {
                definitions.remove(snapshot.serverId());
            } else {
                definitions.put(snapshot.serverId(), snapshot.definitions());
            }
            drain = notify && enqueuePublication(snapshot.serverId());
        }
        if (drain) {
            drainPublications();
        }
        return true;
    }

    public boolean restoreSnapshotIfCurrent(Snapshot expected, Snapshot replacement, boolean notify) {
        if (expected == null || replacement == null || expected.serverId() == null
            || !expected.serverId().equals(replacement.serverId())) {
            return false;
        }
        boolean drain;
        synchronized (this) {
            Map<String, WorldGenNodeDefinition> current = definitions.get(expected.serverId());
            if (expected.present() != (current != null)
                || current != null && !current.equals(expected.definitions())) {
                return false;
            }
            if (replacement.present()) {
                definitions.put(replacement.serverId(), replacement.definitions());
            } else {
                definitions.remove(replacement.serverId());
            }
            drain = notify && enqueuePublication(replacement.serverId());
        }
        if (drain) {
            drainPublications();
        }
        return true;
    }

    public synchronized void addListener(WorldGenNodeRegistryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(WorldGenNodeRegistryListener listener) {
        listeners.remove(listener);
    }

    public boolean publish(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        boolean drain;
        synchronized (this) {
            drain = enqueuePublication(serverId);
        }
        return !drain || drainPublications();
    }

    public WorldGenNodeDefinition getDefinition(String serverId, String nodeId) {
        Map<String, WorldGenNodeDefinition> serverDefinitions;
        synchronized (this) {
            serverDefinitions = definitions.get(serverId);
        }
        if (serverDefinitions == null || nodeId == null) {
            return null;
        }
        WorldGenNodeDefinition direct = serverDefinitions.get(nodeId);
        if (direct != null) {
            return direct;
        }
        String identity = canonicalIdentity(nodeId);
        if (identity.isBlank()) {
            return null;
        }
        return serverDefinitions.values().stream()
            .filter(definition -> identity.equals(canonicalIdentity(definition)))
            .findFirst()
            .orElse(null);
    }

    public Collection<WorldGenNodeDefinition> getAllDefinitions(String serverId) {
        Map<String, WorldGenNodeDefinition> serverDefinitions;
        synchronized (this) {
            serverDefinitions = definitions.get(serverId);
        }
        if (serverDefinitions == null) {
            return List.of();
        }
        return List.copyOf(serverDefinitions.values());
    }

    public synchronized boolean hasDefinitions(String serverId) {
        Map<String, WorldGenNodeDefinition> serverDefinitions = definitions.get(serverId);
        return serverDefinitions != null && !serverDefinitions.isEmpty();
    }

    public record Snapshot(String serverId, boolean present, Map<String, WorldGenNodeDefinition> definitions) {
        public Snapshot {
            definitions = immutableDefinitions(definitions);
        }
    }

    private static Map<String, WorldGenNodeDefinition> stageDefinitions(Collection<WorldGenNodeDefinition> source) {
        Map<String, WorldGenNodeDefinition> candidate = new LinkedHashMap<>();
        Set<String> identities = new HashSet<>();
        if (source != null) {
            for (WorldGenNodeDefinition definition : source) {
                requireDefinition(definition);
                String identity = canonicalIdentity(definition);
                if (!identities.add(identity)) {
                    throw new IllegalArgumentException("Duplicate World Generation node definition: " + identity);
                }
                candidate.put(definition.getId(), definition);
            }
        }
        return immutableDefinitions(candidate);
    }

    private static Map<String, WorldGenNodeDefinition> immutableDefinitions(
        Map<String, WorldGenNodeDefinition> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, WorldGenNodeDefinition> byId = new LinkedHashMap<>();
        Set<String> identities = new HashSet<>();
        for (WorldGenNodeDefinition definition : source.values()) {
            requireDefinition(definition);
            String identity = canonicalIdentity(definition);
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Duplicate World Generation node definition: " + identity);
            }
            if (byId.put(definition.getId(), definition) != null) {
                throw new IllegalArgumentException("Duplicate World Generation node definition: " + identity);
            }
        }
        LinkedHashMap<String, WorldGenNodeDefinition> ordered = new LinkedHashMap<>();
        byId.values().stream().sorted(DEFINITION_ORDER).forEach(definition ->
            ordered.put(definition.getId(), definition));
        return Collections.unmodifiableMap(ordered);
    }

    private static void requireServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("World Generation server ID is required");
        }
    }

    private static void requireDefinition(WorldGenNodeDefinition definition) {
        if (definition == null || canonicalIdentity(definition).isBlank()) {
            throw new IllegalArgumentException("World Generation node definition is invalid");
        }
    }

    private static String canonicalIdentity(WorldGenNodeDefinition definition) {
        return definition != null ? canonicalIdentity(definition.getId()) : "";
    }

    private static String canonicalIdentity(String nodeId) {
        if (nodeId == null || nodeId.isBlank() || !nodeId.equals(nodeId.strip())
            || nodeId.chars().anyMatch(Character::isISOControl)) {
            return "";
        }
        String localId = nodeId.startsWith(CANONICAL_OWNER_PREFIX)
            ? nodeId.substring(CANONICAL_OWNER_PREFIX.length()) : nodeId;
        if (localId.isBlank() || localId.indexOf(':') >= 0) {
            return "";
        }
        return CANONICAL_OWNER_PREFIX + localId;
    }

    private boolean enqueuePublication(String serverId) {
        publications.addLast(new Publication(serverId, List.copyOf(listeners)));
        if (publishing) {
            return false;
        }
        publishing = true;
        return true;
    }

    private boolean drainPublications() {
        boolean successful = true;
        while (true) {
            Publication publication;
            synchronized (this) {
                publication = publications.pollFirst();
                if (publication == null) {
                    publishing = false;
                    return successful;
                }
            }
            for (WorldGenNodeRegistryListener listener : publication.listeners()) {
                try {
                    listener.onRegistryUpdated(publication.serverId());
                } catch (RuntimeException | Error exception) {
                    successful = false;
                    try {
                        ReLog.logger(LogTypes.FLOW).source(LogSource.server(publication.serverId(), publication.serverId()))
                            .component(WorldGenNodeRegistry.class).with("reason", exception.getMessage())
                            .warn("World Generation node registry listener failed");
                    } catch (RuntimeException | Error ignored) {
                    }
                }
            }
        }
    }

    private record Publication(String serverId, List<WorldGenNodeRegistryListener> listeners) {
    }
}
