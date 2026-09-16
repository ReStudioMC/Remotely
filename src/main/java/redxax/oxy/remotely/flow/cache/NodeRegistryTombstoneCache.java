package redxax.oxy.remotely.flow.cache;

import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import com.google.gson.Gson;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;

import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import redxax.oxy.remotely.data.flow.ReSyncStorage;

public class NodeRegistryTombstoneCache {
    private static final int CACHE_SCHEMA_VERSION = 3;
    private static final int PREVIOUS_CACHE_SCHEMA_VERSION = 2;
    private static final NodeRegistryTombstoneCache INSTANCE = new NodeRegistryTombstoneCache();
    private final Gson gson = new Gson();
    private final ReSyncStorage cacheStorage;
    private final ReSyncStorage quarantineStorage;
    private final Map<String, TombstoneRecord> servers = new HashMap<>();
    private final Map<String, String> invalidationReasons = new HashMap<>();
    private final Map<String, String> quarantinedServers = new HashMap<>();
    private boolean quarantineStateUnreadable;

    private NodeRegistryTombstoneCache() {
        this(ReSyncStorage.legacy("remotely.node-registry-tombstones"),
            ReSyncStorage.legacy("remotely.node-registry-tombstones-quarantine"));
    }

    public NodeRegistryTombstoneCache(ReSyncStorage cacheStorage) {
        this(cacheStorage, ReSyncStorage.memory("remotely.node-registry-tombstones-quarantine:" + System.identityHashCode(cacheStorage)));
    }

    NodeRegistryTombstoneCache(ReSyncStorage cacheStorage, ReSyncStorage quarantineStorage) {
        this.cacheStorage = cacheStorage != null ? cacheStorage : ReSyncStorage.memory("remotely.node-registry-tombstones");
        this.quarantineStorage = quarantineStorage != null ? quarantineStorage : ReSyncStorage.memory("remotely.node-registry-tombstones-quarantine");
        load();
    }

    public static NodeRegistryTombstoneCache getInstance() {
        return INSTANCE;
    }

    public synchronized List<NodePluginPayload> get(String serverId) {
        if (serverId == null || serverQuarantined(serverId)) {
            return List.of();
        }
        TombstoneRecord record = servers.get(serverId);
        return record != null ? record.plugins.values().stream().filter(payload -> payload != null)
            .sorted(Comparator.comparing(NodePluginPayload::getPluginId, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))).toList() : List.of();
    }

    public synchronized TombstoneSnapshot snapshot(String serverId) {
        if (serverId == null || serverQuarantined(serverId)) {
            return TombstoneSnapshot.empty();
        }
        TombstoneRecord record = servers.get(serverId);
        return record != null ? record.snapshot() : TombstoneSnapshot.empty();
    }

    public synchronized boolean isQuarantined(String serverId) {
        return serverId != null && serverQuarantined(serverId);
    }

    public synchronized boolean restore(String serverId, TombstoneSnapshot snapshot) {
        if (serverId == null || serverQuarantined(serverId)) {
            return false;
        }
        String key = serverId;
        TombstoneRecord previous = servers.get(key);
        String previousReason = invalidationReasons.get(key);
        TombstoneRecord restored = null;
        if (snapshot != null && !snapshot.payloads().isEmpty()) {
            boolean noAuthority = snapshot.catalogGeneration() < 0L && snapshot.catalogChecksum().isBlank()
                && snapshot.projectionIdentity().isBlank();
            if ((!noAuthority && (!validText(snapshot.registryChecksum()) || snapshot.catalogGeneration() < 0L
                || !validText(snapshot.catalogChecksum()) || !validText(snapshot.projectionIdentity())))
                || !validPayloads(snapshot.payloads())) {
                return false;
            }
            Map<String, NodePluginPayload> plugins = toPluginMap(snapshot.payloads());
            if (plugins.isEmpty()) {
                return false;
            }
            restored = new TombstoneRecord(snapshot.registryChecksum(), snapshot.catalogGeneration(),
                snapshot.catalogChecksum(), snapshot.projectionIdentity(), plugins);
        }
        if (!prepareQuarantine(key, "Node registry tombstone restoration is pending")) {
            quarantineServer(key, "Node registry tombstone restoration could not be persisted");
            return false;
        }
        if (restored == null) {
            servers.remove(key);
        } else {
            servers.put(key, restored);
        }
        invalidationReasons.remove(key);
        if (!save(key)) {
            if (previous == null) {
                servers.remove(key);
            } else {
                servers.put(key, previous);
            }
            if (previousReason == null) {
                invalidationReasons.remove(key);
            } else {
                invalidationReasons.put(key, previousReason);
            }
            quarantineServer(key, "Node registry tombstone restoration could not be persisted");
            return false;
        }
        return clearQuarantine(key);
    }

    public synchronized boolean invalidateServer(String serverId, String reason) {
        if (serverId == null) {
            return false;
        }
        String key = serverId;
        Map<String, TombstoneRecord> nextServers = new HashMap<>(servers);
        Map<String, String> nextReasons = new HashMap<>(invalidationReasons);
        nextServers.remove(key);
        String invalidationReason = reason != null && !reason.isBlank() ? reason.trim() : "Node registry publication invalidated";
        nextReasons.put(key, invalidationReason);
        if (!prepareQuarantine(key, invalidationReason) || !save(nextServers, nextReasons, key)) {
            quarantineServer(key, invalidationReason);
            return false;
        }
        servers.clear();
        servers.putAll(nextServers);
        invalidationReasons.clear();
        invalidationReasons.putAll(nextReasons);
        return clearQuarantine(serverId);
    }

    public synchronized boolean replace(String serverId, List<NodePluginPayload> payloads) {
        return replace(serverId, "", null, "", payloads);
    }

    public synchronized boolean replace(String serverId, String registryChecksum,
                                        NodeRegistry.SnapshotAuthority authority, String projectionIdentity,
                                        List<NodePluginPayload> payloads) {
        if (serverId == null || serverQuarantined(serverId) && authority == null) {
            return false;
        }
        String key = serverId;
        if (authority != null && (!key.equals(authority.serverId()) || !validText(registryChecksum)
            || !validText(projectionIdentity))) {
            return false;
        }
        if (authority == null && projectionIdentity != null && !projectionIdentity.isBlank()) {
            return false;
        }
        Map<String, NodePluginPayload> plugins = new HashMap<>();
        if (payloads != null) {
            for (NodePluginPayload payload : payloads) {
                if (payload == null || !validText(payload.getPluginId())) {
                    return false;
                }
                plugins.put(payload.getPluginId(), payload);
            }
        }
        if (!validPayloads(plugins.values())) {
            return false;
        }
        TombstoneRecord previous = servers.get(key);
        if (previous != null && authority != null && !acceptsAuthority(previous, authority, projectionIdentity)) {
            return false;
        }
        String previousReason = invalidationReasons.get(key);
        TombstoneRecord next = new TombstoneRecord(registryChecksum, authority, projectionIdentity, plugins);
        if (!prepareQuarantine(key, "Node registry tombstone replacement is pending")) {
            quarantineServer(key, "Node registry tombstone replacement could not be persisted");
            return false;
        }
        if (plugins.isEmpty()) {
            servers.remove(key);
        } else {
            servers.put(key, next);
        }
        invalidationReasons.remove(key);
        if (!save(key)) {
            if (previous == null) {
                servers.remove(key);
            } else {
                servers.put(key, previous);
            }
            if (previousReason != null) {
                invalidationReasons.put(key, previousReason);
            }
            quarantineServer(key, "Node registry tombstone replacement could not be persisted");
            return false;
        }
        return clearQuarantine(key);
    }

    private boolean acceptsAuthority(TombstoneRecord previous, NodeRegistry.SnapshotAuthority authority,
                                     String projectionIdentity) {
        if (previous.catalogGeneration < 0L) {
            return true;
        }
        if (authority.generation() < previous.catalogGeneration) {
            return false;
        }
        return authority.generation() > previous.catalogGeneration
            || (authority.checksum().equals(previous.catalogChecksum)
            && projectionIdentity.equals(previous.projectionIdentity));
    }

    public synchronized boolean clear(String serverId) {
        if (serverId == null) {
            return false;
        }
        String key = serverId;
        boolean quarantined = serverQuarantined(key);
        TombstoneRecord previous = servers.get(key);
        String previousReason = invalidationReasons.get(key);
        if (previous == null && !quarantined && previousReason == null) {
            return true;
        }
        if (!prepareQuarantine(key, "Node registry tombstone clear is pending")) {
            quarantineServer(key, "Node registry tombstone clear could not be persisted");
            return false;
        }
        servers.remove(key);
        invalidationReasons.remove(key);
        if (save(key)) {
            return clearQuarantine(key);
        }
        if (previous != null) {
            servers.put(key, previous);
        }
        if (previousReason != null) {
            invalidationReasons.put(key, previousReason);
        }
        quarantineServer(key, "Node registry tombstone clear could not be persisted");
        return false;
    }

    public static boolean validPayloads(Iterable<NodePluginPayload> payloads) {
        if (payloads == null) {
            return true;
        }
        for (NodePluginPayload payload : payloads) {
            if (payload == null || !validText(payload.getPluginId()) || payload.getNodes() == null) {
                return false;
            }
            if (!payload.hasValidCanonicalCatalogPayload()) {
                return false;
            }
            for (NodeDefinition definition : payload.getNodes()) {
                if (definition == null || !validText(definition.getId()) || !validText(definition.getOwner())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim())
            && value.chars().noneMatch(Character::isISOControl);
    }

    private void load() {
        if (!loadQuarantine()) {
            return;
        }
        try {
            String json = cacheStorage.read("state");
            if (json == null || json.isBlank()) {
                return;
            }
            CacheState state = gson.fromJson(json, CacheState.class);
            if (state == null || state.servers == null
                || state.schemaVersion != CACHE_SCHEMA_VERSION && state.schemaVersion != PREVIOUS_CACHE_SCHEMA_VERSION) {
                return;
            }
            if (state.invalidationReasons != null) {
                invalidationReasons.putAll(state.invalidationReasons);
            }
            for (Map.Entry<String, TombstoneRecord> entry : state.servers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().plugins == null
                    || serverQuarantined(entry.getKey()) || invalidationReasons.containsKey(entry.getKey())
                    || !validRecord(entry.getValue()) || !validPayloads(entry.getValue().plugins.values())) {
                    continue;
                }
                if (!entry.getValue().plugins.isEmpty()) {
                    servers.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (RuntimeException exception) {
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(NodeRegistryTombstoneCache.class).operation("Load Node Tombstones").error("Could not load node registry tombstones", exception);
        }
    }

    private boolean validRecord(TombstoneRecord record) {
        if (record.registryChecksum == null || record.catalogChecksum == null || record.projectionIdentity == null
            || record.catalogGeneration < -1L) {
            return false;
        }
        if (record.catalogGeneration < 0L) {
            return record.catalogChecksum.isBlank() && record.projectionIdentity.isBlank();
        }
        return !record.catalogChecksum.isBlank() && !record.projectionIdentity.isBlank();
    }

    private boolean save(String allowedServerId) {
        return save(servers, invalidationReasons, allowedServerId);
    }

    private boolean save(Map<String, TombstoneRecord> nextServers, Map<String, String> nextReasons,
                         String allowedServerId) {
        Map<String, TombstoneRecord> persistedServers = new HashMap<>(nextServers);
        Map<String, String> persistedReasons = new HashMap<>(nextReasons);
        if (quarantineStateUnreadable) {
            persistedServers.keySet().removeIf(serverId -> allowedServerId == null || !allowedServerId.equals(serverId));
        } else {
            for (String serverId : quarantinedServers.keySet()) {
                if (allowedServerId == null || !allowedServerId.equals(serverId)) {
                    persistedServers.remove(serverId);
                    persistedReasons.put(serverId, quarantinedServers.get(serverId));
                }
            }
        }
        try {
            cacheStorage.write("state", gson.toJson(new CacheState(persistedServers, persistedReasons)));
            return true;
        } catch (RuntimeException exception) {
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(NodeRegistryTombstoneCache.class).operation("Save Node Tombstones").error("Could not save node registry tombstones", exception);
            return false;
        }
    }

    private void quarantineServer(String serverId, String reason) {
        prepareQuarantine(serverId, reason);
    }

    private boolean prepareQuarantine(String serverId, String reason) {
        if (serverId == null) {
            return false;
        }
        String quarantineReason = reason != null && !reason.isBlank()
            ? reason.trim() : "Node registry tombstone cache is quarantined until fresh authority is persisted";
        quarantinedServers.put(serverId, quarantineReason);
        if (persistQuarantineState()) {
            return true;
        }
        quarantineStateUnreadable = true;
        invalidateStorage();
        return false;
    }

    private boolean serverQuarantined(String serverId) {
        return quarantineStateUnreadable || quarantinedServers.containsKey(serverId);
    }

    private boolean loadQuarantine() {
        try {
            String json = quarantineStorage.read("state");
            if (json == null || json.isBlank()) {
                return true;
            }
            QuarantineState loaded = gson.fromJson(json, QuarantineState.class);
            if (loaded == null || loaded.servers == null) {
                quarantineStateUnreadable = true;
                return false;
            }
            for (Map.Entry<String, String> entry : loaded.servers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || !entry.getKey().equals(entry.getKey().trim())
                    || entry.getKey().chars().anyMatch(Character::isISOControl)
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                    quarantineStateUnreadable = true;
                    return false;
                }
            }
            quarantinedServers.putAll(loaded.servers);
            return true;
        } catch (RuntimeException exception) {
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(NodeRegistryTombstoneCache.class)
                .operation("Load Node Tombstone Quarantine").error("Could not load node registry tombstone quarantine", exception);
            quarantineStateUnreadable = true;
            return false;
        }
    }

    private boolean persistQuarantineState() {
        try {
            if (quarantinedServers.isEmpty()) {
                quarantineStorage.remove("state");
                return true;
            }
            quarantineStorage.write("state", gson.toJson(new QuarantineState(quarantinedServers)));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean clearQuarantine(String serverId) {
        if (serverId == null || !serverQuarantined(serverId)) {
            return true;
        }
        String previousReason = quarantinedServers.remove(serverId);
        boolean previousUnreadable = quarantineStateUnreadable;
        if (persistQuarantineState()) {
            quarantineStateUnreadable = false;
            return true;
        }
        if (previousReason != null) {
            quarantinedServers.put(serverId, previousReason);
        }
        quarantineStateUnreadable = previousUnreadable;
        return false;
    }

    private boolean invalidateStorage() {
        try {
            cacheStorage.remove("state");
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static class CacheState {
        private int schemaVersion = CACHE_SCHEMA_VERSION;
        private Map<String, TombstoneRecord> servers = new HashMap<>();
        private Map<String, String> invalidationReasons = new HashMap<>();

        private CacheState() {
        }

        private CacheState(Map<String, TombstoneRecord> servers, Map<String, String> invalidationReasons) {
            this.servers = new HashMap<>(servers);
            this.invalidationReasons = new HashMap<>(invalidationReasons);
        }
    }

    private static class QuarantineState {
        private Map<String, String> servers = new HashMap<>();

        private QuarantineState() {
        }

        private QuarantineState(Map<String, String> servers) {
            this.servers = new HashMap<>(servers);
        }
    }

    private Map<String, NodePluginPayload> toPluginMap(List<NodePluginPayload> payloads) {
        Map<String, NodePluginPayload> plugins = new HashMap<>();
        for (NodePluginPayload payload : payloads) {
            if (payload == null || !validText(payload.getPluginId())) {
                return Map.of();
            }
            plugins.put(payload.getPluginId(), payload);
        }
        return plugins;
    }

    public record TombstoneSnapshot(String registryChecksum, long catalogGeneration, String catalogChecksum,
                                    String projectionIdentity,
                                    List<NodePluginPayload> payloads) {
        public TombstoneSnapshot {
            registryChecksum = registryChecksum != null ? registryChecksum : "";
            catalogChecksum = catalogChecksum != null ? catalogChecksum : "";
            projectionIdentity = projectionIdentity != null ? projectionIdentity : "";
            payloads = payloads != null ? List.copyOf(payloads) : List.of();
        }

        static TombstoneSnapshot empty() {
            return new TombstoneSnapshot("", -1L, "", "", List.of());
        }

        public boolean matches(NodeRegistrySnapshot snapshot) {
            if (snapshot == null || registryChecksum.isBlank() || !registryChecksum.equals(snapshot.getRegistryChecksum())) {
                return false;
            }
            if (catalogGeneration >= 0L) {
                return catalogGeneration == snapshot.getCatalogGeneration()
                    && catalogChecksum.equals(snapshot.getCatalogChecksum())
                    && (projectionIdentity.isBlank() || projectionIdentity.equals(snapshot.getCatalogProjectionIdentity()));
            }
            return snapshot.getCatalogGeneration() < 0L;
        }
    }

    private record TombstoneRecord(String registryChecksum, long catalogGeneration, String catalogChecksum,
                                   String projectionIdentity,
                                   Map<String, NodePluginPayload> plugins) {
        private TombstoneRecord {
            registryChecksum = registryChecksum != null ? registryChecksum : "";
            catalogChecksum = catalogChecksum != null ? catalogChecksum : "";
            projectionIdentity = projectionIdentity != null ? projectionIdentity : "";
            plugins = plugins != null ? new HashMap<>(plugins) : new HashMap<>();
        }

        private TombstoneRecord(String registryChecksum, NodeRegistry.SnapshotAuthority authority,
                                String projectionIdentity, Map<String, NodePluginPayload> plugins) {
            this(registryChecksum, authority != null ? authority.generation() : -1L,
                authority != null ? authority.checksum() : "", projectionIdentity, plugins);
        }

        private TombstoneSnapshot snapshot() {
            return new TombstoneSnapshot(registryChecksum, catalogGeneration, catalogChecksum, projectionIdentity,
                new ArrayList<>(plugins.values()));
        }
    }
}
