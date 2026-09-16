package redxax.oxy.remotely.flow.cache;

import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import com.google.gson.Gson;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.sync.NodePluginPayload;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.contract.FlowCategoryMetadata;
import restudio.resync.protocol.ReSyncProtocolContract;
import redxax.oxy.remotely.flow.sync.FlowConversionRule;
import redxax.oxy.remotely.flow.sync.FlowOptionSourceMetadata;
import redxax.oxy.remotely.flow.sync.FlowPropertyMetadata;
import redxax.oxy.remotely.flow.sync.FlowResourceMetadata;
import restudio.resync.flow.contract.FlowTypeMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import redxax.oxy.remotely.data.flow.ReSyncStorage;
import restudio.rescreen.platform.Clock;

public class NodeRegistryCache {
    private static final int CACHE_SCHEMA_VERSION = 12;
    private static NodeRegistryCache INSTANCE;
    private final Gson gson = new Gson();
    private final ReSyncStorage cacheStorage;
    private final ReSyncStorage quarantineStorage;
    private CacheState state = new CacheState();
    private final Map<String, String> quarantinedServers = new HashMap<>();
    private boolean quarantineStateUnreadable;

    private NodeRegistryCache() {
        this(ReSyncStorage.legacy("remotely.node-registry"), ReSyncStorage.legacy("remotely.node-registry-quarantine"));
    }

    NodeRegistryCache(ReSyncStorage cacheStorage) {
        this(cacheStorage, ReSyncStorage.memory("remotely.node-registry-quarantine:" + System.identityHashCode(cacheStorage)));
    }

    public NodeRegistryCache(ReSyncStorage storage, Clock clock) {
        this(storage != null ? storage : ReSyncStorage.memory("remotely.node-registry"),
            ReSyncStorage.legacy("remotely.node-registry-quarantine"));
    }

    NodeRegistryCache(ReSyncStorage cacheStorage, ReSyncStorage quarantineStorage) {
        this.cacheStorage = cacheStorage != null ? cacheStorage : ReSyncStorage.memory("remotely.node-registry");
        this.quarantineStorage = quarantineStorage != null ? quarantineStorage : ReSyncStorage.memory("remotely.node-registry-quarantine");
        load();
    }

    public static NodeRegistryCache getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new NodeRegistryCache();
        }
        return INSTANCE;
    }

    public synchronized NodeRegistrySnapshot getSnapshot(String serverId) {
        if (serverId == null) {
            return null;
        }
        if (serverQuarantined(serverId)) {
            return null;
        }
        ServerCache cache = state.servers.get(serverId);
        if (cache == null) {
            return null;
        }
        if (!validContract(cache.contractVersion, cache.minimumClientContractVersion, cache.capabilities)
            || cache.compatibleUntil > 0 && cache.compatibleUntil < System.currentTimeMillis()
            || cache.serverIdentity != null && !cache.serverIdentity.isBlank() && !serverId.equals(cache.serverIdentity)
            || !validCanonicalCatalog(cache)) {
            CacheState candidate = copyState(state);
            candidate.servers.remove(serverId);
            candidate.invalidationReasons.put(serverId, "Cached node registry is incompatible or expired");
            if (prepareQuarantine(serverId, "Cached node registry could not be invalidated")
                && save(candidate, serverId)) {
                state = candidate;
                clearQuarantine(serverId);
            } else {
                quarantineServer(serverId, "Cached node registry could not be invalidated");
            }
            return null;
        }
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(cache.contractVersion);
        snapshot.setMinimumClientContractVersion(cache.minimumClientContractVersion);
        snapshot.setServerIdentity(cache.serverIdentity);
        snapshot.setCompatibleUntil(cache.compatibleUntil);
        snapshot.setCapabilities(new ArrayList<>(cache.capabilities));
        snapshot.setRegistryDiagnostics(cache.registryDiagnostics);
        snapshot.setFullSync(true);
        snapshot.setRegistryChecksum(cache.registryChecksum);
        snapshot.setGeneratedAt(cache.generatedAt);
        snapshot.setCatalogGeneration(cache.catalogGeneration);
        snapshot.setCatalogChecksum(cache.catalogChecksum);
        snapshot.setCatalogProjectionIdentity(cache.catalogProjectionIdentity);
        snapshot.setDropContributions(new ArrayList<>(cache.dropContributions));
        snapshot.setFunctionBoundaries(new ArrayList<>(cache.functionBoundaries));
        snapshot.setCatalogMetadata(cache.catalogMetadata);
        snapshot.setOpaqueData(cache.opaqueData);
        snapshot.setNodeIds(new ArrayList<>(cache.nodeIds));
        snapshot.setPlugins(new ArrayList<>(cache.plugins.values()));
        snapshot.setRemovedPlugins(new ArrayList<>());
        snapshot.setPropertyActions(cache.propertyActions);
        snapshot.setPropertyOutputTypes(cache.propertyOutputTypes);
        snapshot.setPropertyMetadata(new ArrayList<>(cache.propertyMetadata));
        snapshot.setResourceMetadata(new ArrayList<>(cache.resourceMetadata));
        snapshot.setTypeMetadata(new ArrayList<>(cache.typeMetadata));
        snapshot.setCategoryMetadata(new ArrayList<>(cache.categoryMetadata));
        snapshot.setOptionSourceMetadata(new ArrayList<>(cache.optionSourceMetadata));
        snapshot.setConversionRules(new ArrayList<>(cache.conversionRules));
        return snapshot;
    }

    public synchronized CacheCheckpoint capture(String serverId) {
        if (serverId == null) {
            return new CacheCheckpoint(null, "");
        }
        String quarantineReason = quarantinedServers.get(serverId);
        if (quarantineReason == null && quarantineStateUnreadable) {
            quarantineReason = "Node registry cache quarantine state could not be read";
        }
        return new CacheCheckpoint(getSnapshot(serverId), quarantineReason != null ? quarantineReason
            : state.invalidationReasons.getOrDefault(serverId, ""));
    }

    public synchronized boolean isQuarantined(String serverId) {
        return serverId != null && serverQuarantined(serverId);
    }

    public synchronized Optional<NodeRegistrySnapshot.CanonicalCatalog> getCanonicalCatalog(String serverId) {
        NodeRegistrySnapshot snapshot = getSnapshot(serverId);
        return snapshot != null ? snapshot.canonicalCatalog() : Optional.empty();
    }

    public synchronized boolean restore(String serverId, CacheCheckpoint checkpoint) {
        if (serverId == null || serverQuarantined(serverId)) {
            return false;
        }
        CacheState candidate = copyState(state);
        candidate.servers.remove(serverId);
        candidate.invalidationReasons.remove(serverId);
        if (checkpoint != null && checkpoint.snapshot() != null) {
            ServerCache restored = serverCache(serverId, checkpoint.snapshot());
            if (restored == null) {
                return false;
            }
            candidate.servers.put(serverId, restored);
        }
        if (checkpoint != null && checkpoint.invalidationReason() != null && !checkpoint.invalidationReason().isBlank()) {
            candidate.invalidationReasons.put(serverId, checkpoint.invalidationReason());
        }
        if (!prepareQuarantine(serverId, "Node registry cache restoration is pending")
            || !save(candidate, serverId)) {
            quarantineServer(serverId, "Node registry cache restoration could not be persisted");
            return false;
        }
        state = candidate;
        if (!clearQuarantine(serverId)) {
            return false;
        }
        return true;
    }

    public synchronized boolean invalidateServer(String serverId, String reason) {
        if (serverId == null) {
            return false;
        }
        CacheState candidate = copyState(state);
        candidate.servers.remove(serverId);
        candidate.invalidationReasons.put(serverId,
            reason != null && !reason.isBlank() ? reason.trim() : "Node registry publication invalidated");
        if (!prepareQuarantine(serverId, reason != null && !reason.isBlank() ? reason.trim()
            : "Node registry publication invalidation is pending") || !save(candidate, serverId)) {
            quarantineServer(serverId, reason != null && !reason.isBlank() ? reason.trim()
                : "Node registry publication invalidation could not be persisted");
            return false;
        }
        state = candidate;
        return clearQuarantine(serverId);
    }

    public synchronized Map<String, String> getPluginChecksums(String serverId) {
        Map<String, String> checksums = new HashMap<>();
        if (serverId == null || serverQuarantined(serverId)) {
            return checksums;
        }
        ServerCache cache = state.servers.get(serverId);
        if (cache == null) {
            return checksums;
        }
        for (var entry : cache.plugins.entrySet()) {
            String checksum = entry.getValue().getChecksum();
            if (checksum != null) {
                checksums.put(entry.getKey(), checksum);
            }
        }
        return checksums;
    }

    public synchronized boolean applySnapshot(String serverId, NodeRegistrySnapshot snapshot) {
        if (serverId == null || snapshot == null) {
            return false;
        }
        if (serverQuarantined(serverId) && !snapshot.isFullSync()) {
            return false;
        }
        if (!NodeRegistryTombstoneCache.validPayloads(snapshot.getPlugins())) {
            reject(serverId, "Rejected malformed node registry payload");
            return false;
        }
        if (!validContract(snapshot.getContractVersion(), snapshot.getMinimumClientContractVersion(), snapshot.getCapabilities())) {
            reject(serverId, "Rejected incompatible node registry contract " + snapshot.getContractVersion());
            return false;
        }
        if (!snapshot.getServerIdentity().isBlank() && !serverId.equals(snapshot.getServerIdentity())) {
            reject(serverId, "Rejected node registry snapshot for another server");
            return false;
        }
        if (!snapshot.hasValidCanonicalCatalog()) {
            reject(serverId, "Rejected invalid or missing canonical catalog payload");
            return false;
        }
        long catalogGeneration = snapshot.getCatalogGeneration();
        boolean noCatalogAuthority = catalogGeneration < 0L
            && snapshot.getCatalogChecksum().isBlank()
            && snapshot.getCatalogProjectionIdentity().isBlank();
        boolean completeCatalogAuthority = catalogGeneration >= 0L
            && !snapshot.getCatalogChecksum().isBlank()
            && !snapshot.getCatalogProjectionIdentity().isBlank();
        if (catalogGeneration < -1L || (!noCatalogAuthority && !completeCatalogAuthority)) {
            reject(serverId, "Rejected incomplete typed catalog authority");
            return false;
        }
        ServerCache existing = state.servers.get(serverId);
        if (existing != null && !acceptsCatalogAuthority(existing, snapshot)) {
            reject(serverId, "Rejected stale or conflicting typed catalog authority");
            return false;
        }
        CacheState candidate = copyState(state);
        ServerCache cache = candidate.servers.computeIfAbsent(serverId, ignored -> new ServerCache());
        cache.contractVersion = snapshot.getContractVersion();
        cache.minimumClientContractVersion = snapshot.getMinimumClientContractVersion();
        cache.serverIdentity = serverId;
        cache.compatibleUntil = snapshot.getCompatibleUntil();
        cache.capabilities = new ArrayList<>(snapshot.getCapabilities());
        cache.registryDiagnostics = new HashMap<>(snapshot.getRegistryDiagnostics());
        if (snapshot.isFullSync()) {
            cache.plugins.clear();
            cache.nodeIds.clear();
            cache.registryChecksum = "";
            cache.generatedAt = 0L;
            cache.catalogGeneration = -1L;
            cache.catalogChecksum = "";
            cache.catalogProjectionIdentity = "";
            cache.dropContributions.clear();
            cache.functionBoundaries.clear();
            cache.catalogMetadata.clear();
            cache.opaqueData.clear();
        }
        if (snapshot.getRemovedPlugins() != null) {
            for (String pluginId : snapshot.getRemovedPlugins()) {
                cache.plugins.remove(pluginId);
            }
        }
        if (snapshot.getPlugins() != null) {
            for (NodePluginPayload payload : snapshot.getPlugins()) {
                if (payload == null || payload.getPluginId() == null || payload.getPluginId().isBlank()) {
                    return false;
                }
                cache.plugins.put(payload.getPluginId(), payload);
            }
        }
        if (snapshot.getNodeIds() != null && !snapshot.getNodeIds().isEmpty()) {
            cache.nodeIds = new ArrayList<>(snapshot.getNodeIds());
        }
        if (snapshot.getRegistryChecksum() != null && !snapshot.getRegistryChecksum().isBlank()) {
            cache.registryChecksum = snapshot.getRegistryChecksum();
        }
        if (snapshot.getGeneratedAt() > 0) {
            cache.generatedAt = snapshot.getGeneratedAt();
        }
        if (snapshot.getCatalogGeneration() >= 0) {
            cache.catalogGeneration = snapshot.getCatalogGeneration();
        }
        if (!snapshot.getCatalogChecksum().isBlank()) {
            cache.catalogChecksum = snapshot.getCatalogChecksum();
        }
        if (!snapshot.getCatalogProjectionIdentity().isBlank()) {
            cache.catalogProjectionIdentity = snapshot.getCatalogProjectionIdentity();
        }
        if (snapshot.getDropContributions() != null) {
            cache.dropContributions = new ArrayList<>(snapshot.getDropContributions());
        }
        if (snapshot.getFunctionBoundaries() != null) {
            cache.functionBoundaries = new ArrayList<>(snapshot.getFunctionBoundaries());
        }
        if (snapshot.getCatalogMetadata() != null) {
            cache.catalogMetadata = new LinkedHashMap<>(snapshot.getCatalogMetadata());
        }
        if (snapshot.getOpaqueData() != null && !snapshot.getOpaqueData().isEmpty()) {
            cache.opaqueData = new LinkedHashMap<>(snapshot.getOpaqueData());
        }
        if (snapshot.getPropertyActions() != null) {
            cache.propertyActions = snapshot.getPropertyActions();
        }
        if (snapshot.getPropertyOutputTypes() != null) {
            cache.propertyOutputTypes = snapshot.getPropertyOutputTypes();
        }
        if (snapshot.getPropertyMetadata() != null) {
            cache.propertyMetadata = new ArrayList<>(snapshot.getPropertyMetadata());
        }
        if (snapshot.getResourceMetadata() != null) {
            cache.resourceMetadata = new ArrayList<>(snapshot.getResourceMetadata());
        }
        if (snapshot.getTypeMetadata() != null) {
            cache.typeMetadata = new ArrayList<>(snapshot.getTypeMetadata());
        }
        if (snapshot.getCategoryMetadata() != null) {
            cache.categoryMetadata = new ArrayList<>(snapshot.getCategoryMetadata());
        }
        if (snapshot.getOptionSourceMetadata() != null) {
            cache.optionSourceMetadata = new ArrayList<>(snapshot.getOptionSourceMetadata());
        }
        if (snapshot.getConversionRules() != null) {
            cache.conversionRules = new ArrayList<>(snapshot.getConversionRules());
        }
        cache.updatedAt = System.currentTimeMillis();
        candidate.invalidationReasons.remove(serverId);
        if (!prepareQuarantine(serverId, "Node registry snapshot persistence is pending")
            || !save(candidate, serverId)) {
            quarantineServer(serverId, "Node registry snapshot could not be persisted");
            return false;
        }
        state = candidate;
        return clearQuarantine(serverId);
    }

    private boolean acceptsCatalogAuthority(ServerCache existing, NodeRegistrySnapshot snapshot) {
        long incomingGeneration = snapshot.getCatalogGeneration();
        if (existing.catalogGeneration < 0L) {
            return true;
        }
        if (incomingGeneration < 0L || incomingGeneration < existing.catalogGeneration) {
            return false;
        }
        if (incomingGeneration == existing.catalogGeneration
            && (!String.valueOf(existing.catalogChecksum).equals(snapshot.getCatalogChecksum())
            || !String.valueOf(existing.catalogProjectionIdentity).equals(snapshot.getCatalogProjectionIdentity()))) {
            return false;
        }
        return true;
    }

    private void reject(String serverId, String reason) {
        CacheState candidate = copyState(state);
        candidate.invalidationReasons.put(serverId, reason);
        if (save(candidate, serverId)) {
            state = candidate;
        }
    }

    public synchronized boolean clearServer(String serverId) {
        if (serverId == null) {
            return false;
        }
        CacheState candidate = copyState(state);
        boolean changed = candidate.servers.remove(serverId) != null || candidate.invalidationReasons.remove(serverId) != null;
        changed |= serverQuarantined(serverId);
        if (!changed) {
            return true;
        }
        if (!prepareQuarantine(serverId, "Node registry cache clear is pending") || !save(candidate, serverId)) {
            quarantineServer(serverId, "Node registry cache clear could not be persisted");
            return false;
        }
        state = candidate;
        return clearQuarantine(serverId);
    }

    public synchronized CacheDiagnostic getDiagnostic(String serverId) {
        String quarantineReason = serverId != null ? quarantinedServers.get(serverId) : null;
        if (quarantineReason == null && serverId != null && quarantineStateUnreadable) {
            quarantineReason = "Node registry cache quarantine state could not be read";
        }
        ServerCache cache = serverId != null ? state.servers.get(serverId) : null;
        String invalidationReason = serverId != null ? state.invalidationReasons.getOrDefault(serverId, state.invalidationReasons.getOrDefault("*", "")) : "";
        if (quarantineReason != null) {
            return new CacheDiagnostic(false, 0, 0, 0, "", 0, state.schemaVersion, 0, quarantineReason);
        }
        if (cache == null) {
            return new CacheDiagnostic(false, 0, 0, 0, "", 0, 0, 0, invalidationReason);
        }
        return new CacheDiagnostic(true, cache.nodeIds.size(), cache.plugins.size(), cache.updatedAt, cache.registryChecksum, cache.generatedAt,
            state.schemaVersion, cache.contractVersion, invalidationReason);
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
            CacheState loaded = gson.fromJson(json, CacheState.class);
            if (loaded != null && loaded.servers != null) {
                if (loaded.invalidationReasons == null) {
                    loaded.invalidationReasons = new HashMap<>();
                }
                if (loaded.schemaVersion == CACHE_SCHEMA_VERSION && validLoadedState(loaded)) {
                    loaded.servers.entrySet().removeIf(entry -> serverQuarantined(entry.getKey()));
                    this.state = loaded;
                } else {
                    this.state = new CacheState();
                    String reason = loaded.schemaVersion == CACHE_SCHEMA_VERSION
                        ? "Node registry cache contents were invalid"
                        : "Node registry cache schema changed from " + loaded.schemaVersion + " to " + CACHE_SCHEMA_VERSION;
                    this.state.invalidationReasons.put("*", reason);
                    ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(NodeRegistryCache.class).operation("Load Node Registry").with("reason", reason).warn("Node registry cache was rejected");
                    save();
                }
            }
        } catch (RuntimeException e) {
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(NodeRegistryCache.class).operation("Load Node Registry").error("Could not load node registry cache", e);
        }
    }

    private void save() {
        save(state);
    }

    private boolean save(CacheState value) {
        return save(value, null);
    }

    private boolean save(CacheState value, String allowedServerId) {
        CacheState persisted = copyState(value);
        if (quarantineStateUnreadable) {
            persisted.servers.keySet().removeIf(serverId -> allowedServerId == null || !allowedServerId.equals(serverId));
        } else {
            for (String serverId : quarantinedServers.keySet()) {
                if (allowedServerId == null || !allowedServerId.equals(serverId)) {
                    persisted.servers.remove(serverId);
                    persisted.invalidationReasons.put(serverId, quarantinedServers.get(serverId));
                }
            }
        }
        try {
            cacheStorage.write("state", gson.toJson(persisted));
            return true;
        } catch (RuntimeException e) {
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(NodeRegistryCache.class).operation("Save Node Registry").error("Could not save node registry cache", e);
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
            ? reason.trim() : "Node registry cache is quarantined until fresh authority is persisted";
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
            ReLog.logger(LogTypes.FLOW).source(LogSource.application("Remotely")).component(NodeRegistryCache.class)
                .operation("Load Node Registry Quarantine").error("Could not load node registry quarantine", exception);
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

    private boolean validLoadedState(CacheState loaded) {
        if (loaded.invalidationReasons == null) {
            return false;
        }
        for (Map.Entry<String, ServerCache> entry : loaded.servers.entrySet()) {
            ServerCache cache = entry.getValue();
            if (entry.getKey() == null || cache == null
                || cache.capabilities == null || cache.registryDiagnostics == null || cache.nodeIds == null
                || cache.plugins == null || cache.propertyActions == null || cache.propertyOutputTypes == null
                || cache.propertyMetadata == null || cache.resourceMetadata == null || cache.typeMetadata == null
                || cache.categoryMetadata == null || cache.optionSourceMetadata == null || cache.conversionRules == null
                || cache.dropContributions == null || cache.functionBoundaries == null
                || cache.catalogMetadata == null || cache.opaqueData == null
                || cache.registryChecksum == null || !validCatalogAuthority(cache)
                || !validContract(cache.contractVersion, cache.minimumClientContractVersion, cache.capabilities)
                || !validCanonicalCatalog(cache)
                || !NodeRegistryTombstoneCache.validPayloads(cache.plugins.values())) {
                return false;
            }
        }
        return true;
    }

    private boolean validCatalogAuthority(ServerCache cache) {
        if (cache.catalogGeneration < -1L || cache.catalogChecksum == null
            || cache.catalogProjectionIdentity == null) {
            return false;
        }
        if (cache.catalogGeneration < 0L) {
            return cache.catalogChecksum.isBlank() && cache.catalogProjectionIdentity.isBlank();
        }
        return !cache.catalogChecksum.isBlank() && !cache.catalogProjectionIdentity.isBlank();
    }

    private boolean validContract(int contractVersion, int minimumClientContractVersion, List<String> capabilities) {
        return contractVersion >= ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion()
            && contractVersion <= ReSyncProtocolContract.FLOW_CONTRACT.version()
            && minimumClientContractVersion <= ReSyncProtocolContract.FLOW_CONTRACT.version()
            && ReSyncProtocolContract.FLOW_CONTRACT.hasRequiredCapabilities(capabilities);
    }

    private CacheState copyState(CacheState source) {
        CacheState copy = new CacheState();
        copy.schemaVersion = source.schemaVersion;
        copy.invalidationReasons = new HashMap<>(source.invalidationReasons);
        for (Map.Entry<String, ServerCache> entry : source.servers.entrySet()) {
            copy.servers.put(entry.getKey(), copyServer(entry.getValue()));
        }
        return copy;
    }

    private ServerCache copyServer(ServerCache source) {
        ServerCache copy = new ServerCache();
        copy.contractVersion = source.contractVersion;
        copy.minimumClientContractVersion = source.minimumClientContractVersion;
        copy.serverIdentity = source.serverIdentity;
        copy.compatibleUntil = source.compatibleUntil;
        copy.capabilities = new ArrayList<>(source.capabilities);
        copy.registryDiagnostics = new HashMap<>(source.registryDiagnostics);
        copy.nodeIds = new ArrayList<>(source.nodeIds);
        copy.plugins = new HashMap<>(source.plugins);
        copy.propertyActions = source.propertyActions;
        copy.propertyOutputTypes = source.propertyOutputTypes;
        copy.propertyMetadata = new ArrayList<>(source.propertyMetadata);
        copy.resourceMetadata = new ArrayList<>(source.resourceMetadata);
        copy.typeMetadata = new ArrayList<>(source.typeMetadata);
        copy.categoryMetadata = new ArrayList<>(source.categoryMetadata);
        copy.optionSourceMetadata = new ArrayList<>(source.optionSourceMetadata);
        copy.conversionRules = new ArrayList<>(source.conversionRules);
        copy.registryChecksum = source.registryChecksum;
        copy.generatedAt = source.generatedAt;
        copy.catalogGeneration = source.catalogGeneration;
        copy.catalogChecksum = source.catalogChecksum;
        copy.catalogProjectionIdentity = source.catalogProjectionIdentity;
        copy.dropContributions = new ArrayList<>(source.dropContributions);
        copy.functionBoundaries = new ArrayList<>(source.functionBoundaries);
        copy.catalogMetadata = new LinkedHashMap<>(source.catalogMetadata);
        copy.opaqueData = new LinkedHashMap<>(source.opaqueData);
        copy.updatedAt = source.updatedAt;
        return copy;
    }

    private ServerCache serverCache(String serverId, NodeRegistrySnapshot snapshot) {
        if (snapshot == null || !NodeRegistryTombstoneCache.validPayloads(snapshot.getPlugins())
            || !validContract(snapshot.getContractVersion(), snapshot.getMinimumClientContractVersion(), snapshot.getCapabilities())) {
            return null;
        }
        ServerCache cache = new ServerCache();
        cache.contractVersion = snapshot.getContractVersion();
        cache.minimumClientContractVersion = snapshot.getMinimumClientContractVersion();
        cache.serverIdentity = snapshot.getServerIdentity().isBlank() ? serverId : snapshot.getServerIdentity();
        cache.compatibleUntil = snapshot.getCompatibleUntil();
        cache.capabilities = new ArrayList<>(snapshot.getCapabilities());
        cache.registryDiagnostics = new HashMap<>(snapshot.getRegistryDiagnostics());
        cache.nodeIds = new ArrayList<>(snapshot.getNodeIds());
        cache.plugins = new HashMap<>();
        for (NodePluginPayload payload : snapshot.getPlugins()) {
            if (payload == null || payload.getPluginId() == null || payload.getPluginId().isBlank()) {
                return null;
            }
            cache.plugins.put(payload.getPluginId(), payload);
        }
        cache.propertyActions = snapshot.getPropertyActions() != null ? snapshot.getPropertyActions() : new HashMap<>();
        cache.propertyOutputTypes = snapshot.getPropertyOutputTypes() != null ? snapshot.getPropertyOutputTypes() : new HashMap<>();
        cache.propertyMetadata = new ArrayList<>(snapshot.getPropertyMetadata());
        cache.resourceMetadata = new ArrayList<>(snapshot.getResourceMetadata());
        cache.typeMetadata = new ArrayList<>(snapshot.getTypeMetadata());
        cache.categoryMetadata = new ArrayList<>(snapshot.getCategoryMetadata());
        cache.optionSourceMetadata = new ArrayList<>(snapshot.getOptionSourceMetadata());
        cache.conversionRules = new ArrayList<>(snapshot.getConversionRules());
        cache.registryChecksum = snapshot.getRegistryChecksum() != null ? snapshot.getRegistryChecksum() : "";
        cache.generatedAt = snapshot.getGeneratedAt();
        cache.catalogGeneration = snapshot.getCatalogGeneration();
        cache.catalogChecksum = snapshot.getCatalogChecksum();
        cache.catalogProjectionIdentity = snapshot.getCatalogProjectionIdentity();
        cache.dropContributions = new ArrayList<>(snapshot.getDropContributions());
        cache.functionBoundaries = new ArrayList<>(snapshot.getFunctionBoundaries());
        cache.catalogMetadata = new LinkedHashMap<>(snapshot.getCatalogMetadata());
        cache.opaqueData = new LinkedHashMap<>(snapshot.getOpaqueData());
        cache.updatedAt = System.currentTimeMillis();
        return validCatalogAuthority(cache) && validCanonicalCatalog(cache) ? cache : null;
    }

    private boolean validCanonicalCatalog(ServerCache cache) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setCapabilities(cache.capabilities);
        snapshot.setCatalogGeneration(cache.catalogGeneration);
        snapshot.setCatalogChecksum(cache.catalogChecksum);
        snapshot.setCatalogProjectionIdentity(cache.catalogProjectionIdentity);
        snapshot.setCatalogMetadata(cache.catalogMetadata);
        snapshot.setOpaqueData(cache.opaqueData);
        snapshot.setPlugins(new ArrayList<>(cache.plugins.values()));
        return snapshot.hasValidCanonicalCatalog();
    }

    private static class CacheState {
        private int schemaVersion = CACHE_SCHEMA_VERSION;
        private Map<String, ServerCache> servers = new HashMap<>();
        private Map<String, String> invalidationReasons = new HashMap<>();
    }

    private static class QuarantineState {
        private Map<String, String> servers = new HashMap<>();

        private QuarantineState() {
        }

        private QuarantineState(Map<String, String> servers) {
            this.servers = new HashMap<>(servers);
        }
    }

    public record CacheCheckpoint(NodeRegistrySnapshot snapshot, String invalidationReason) {
        public CacheCheckpoint {
            invalidationReason = invalidationReason != null ? invalidationReason : "";
        }
    }

    private static class ServerCache {
        private int contractVersion;
        private int minimumClientContractVersion;
        private String serverIdentity = "";
        private long compatibleUntil;
        private List<String> capabilities = new ArrayList<>();
        private Map<String, Object> registryDiagnostics = new HashMap<>();
        private List<String> nodeIds = new ArrayList<>();
        private Map<String, NodePluginPayload> plugins = new HashMap<>();
        private Map<String, Map<String, List<String>>> propertyActions = new HashMap<>();
        private Map<String, Map<String, FlowDataType>> propertyOutputTypes = new HashMap<>();
        private List<FlowPropertyMetadata> propertyMetadata = new ArrayList<>();
        private List<FlowResourceMetadata> resourceMetadata = new ArrayList<>();
        private List<FlowTypeMetadata> typeMetadata = new ArrayList<>();
        private List<FlowCategoryMetadata> categoryMetadata = new ArrayList<>();
        private List<FlowOptionSourceMetadata> optionSourceMetadata = new ArrayList<>();
        private List<FlowConversionRule> conversionRules = new ArrayList<>();
        private String registryChecksum = "";
        private long generatedAt = 0L;
        private long catalogGeneration = -1L;
        private String catalogChecksum = "";
        private String catalogProjectionIdentity = "";
        private List<Map<String, Object>> dropContributions = new ArrayList<>();
        private List<Map<String, Object>> functionBoundaries = new ArrayList<>();
        private Map<String, Object> catalogMetadata = new LinkedHashMap<>();
        private Map<String, Object> opaqueData = new LinkedHashMap<>();
        private long updatedAt = 0L;
    }

    public record CacheDiagnostic(boolean present, int nodeCount, int pluginCount, long updatedAt, String registryChecksum, long generatedAt,
                                  int schemaVersion, int contractVersion, String invalidationReason) {
    }
}
