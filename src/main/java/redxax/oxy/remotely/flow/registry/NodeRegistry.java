package redxax.oxy.remotely.flow.registry;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import restudio.rescreen.platform.Clock;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.data.FlowTypeRef;
import redxax.oxy.remotely.flow.sync.*;
import restudio.resync.flow.contract.FlowCategoryMetadata;
import restudio.resync.flow.contract.FlowTypeMetadata;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

public class NodeRegistry {
    private static final String DEFAULT_SERVER_KEY = "local";
    private static final Comparator<NodeDefinition> DEFINITION_ORDER = Comparator.nullsFirst(Comparator
        .comparingInt(NodeDefinition::getRegistryPriority)
        .thenComparingInt(NodeRegistry::categoryPriority)
        .thenComparing(NodeRegistry::categoryId, NodeRegistry::compareStableText)
        .thenComparing(NodeRegistry::categoryName, NodeRegistry::compareStableText)
        .thenComparing(NodeDefinition::getDisplayName, NodeRegistry::compareStableText)
        .thenComparing(NodeRegistry::definitionType, NodeRegistry::compareStableText)
        .thenComparing(NodeDefinition::getOwner, NodeRegistry::compareStableText)
        .thenComparing(NodeDefinition::getId, NodeRegistry::compareStableText)
        .thenComparing(NodeRegistry::canonicalDefinitionId, NodeRegistry::compareStableText));
    private final Map<String, NodeDefinition> localDefinitions = BrowserSafeState.map();
    private final Map<String, Map<String, NodeDefinition>> serverDefinitions = BrowserSafeState.map();
    private final Map<String, Map<String, NodePluginPayload>> serverPlugins = BrowserSafeState.map();
    private final Map<String, Map<String, NodePluginPayload>> serverUnresolvedPlugins = BrowserSafeState.map();
    private final Map<String, List<String>> serverNodeIds = BrowserSafeState.map();
    private final Map<String, Map<String, Map<String, List<String>>>> serverPropertyActions = BrowserSafeState.map();
    private final Map<String, Map<String, Map<String, FlowDataType>>> serverPropertyOutputTypes = BrowserSafeState.map();
    private final Map<String, List<FlowPropertyMetadata>> serverPropertyMetadata = BrowserSafeState.map();
    private final Map<String, List<FlowResourceMetadata>> serverResourceMetadata = BrowserSafeState.map();
    private final Map<String, List<FlowTypeMetadata>> serverTypeMetadata = BrowserSafeState.map();
    private final Map<String, Map<String, FlowDataType>> serverDataTypes = BrowserSafeState.map();
    private final Map<String, List<FlowCategoryMetadata>> serverCategoryMetadata = BrowserSafeState.map();
    private final Map<String, List<FlowOptionSourceMetadata>> serverOptionSourceMetadata = BrowserSafeState.map();
    private final Map<String, List<FlowConversionRule>> serverConversionRules = BrowserSafeState.map();
    private final Map<String, RegistrySessionMetadata> serverRegistrySessions = BrowserSafeState.map();
    private final Map<String, SnapshotAuthority> serverSnapshotAuthorities = BrowserSafeState.map();
    private final Map<String, String> serverSnapshotProjectionIdentities = BrowserSafeState.map();
    private final Map<String, SelectorCatalogSnapshot> selectorCatalogSnapshots = BrowserSafeState.map();
    private final Map<String, Long> selectorCatalogRevisions = BrowserSafeState.map();
    private final Map<String, CanonicalCatalogAuthority> serverCanonicalCatalogs = BrowserSafeState.map();
    private final Map<String, Map<String, Object>> serverSnapshotData = BrowserSafeState.map();
    private final Map<String, String> serverInvalidationReasons = BrowserSafeState.map();
    private final List<NodeRegistryListener> listeners = BrowserSafeState.list();

    private static NodeRegistry INSTANCE;

    public NodeRegistry() {
        INSTANCE = this;
    }

    public NodeRegistry(Clock clock) {
        this();
    }

    public static NodeRegistry getInstance() {
        return INSTANCE;
    }

    public interface NodeRegistryListener {
        void onRegistryUpdated(String serverId);
    }

    public synchronized void register(NodeDefinition definition) {
        if (definition == null || definition.getId() == null) {
            return;
        }
        localDefinitions.put(definition.getId(), definition);
    }

    public synchronized void registerServerDefinition(String serverId, NodeDefinition definition) {
        if (definition == null || definition.getId() == null) {
            return;
        }
        NodeReference reference = canonicalReference(definition);
        if (reference == null) {
            return;
        }
        String key = normalizeServerId(serverId);
        CanonicalCatalogAuthority catalogAuthority = serverCanonicalCatalogs.get(key);
        if (catalogAuthority != null && !catalogAuthority.includes(definition)) {
            return;
        }
        serverDefinitions.computeIfAbsent(key, k -> BrowserSafeState.map()).put(reference.canonical(), definition);
        refreshSelectorCatalogSnapshot(key);
    }

    public synchronized void unregisterServerDefinition(String serverId, String definitionId) {
        if (definitionId == null) {
            return;
        }
        String key = normalizeServerId(serverId);
        Map<String, NodeDefinition> defs = serverDefinitions.get(key);
        if (defs == null) {
            return;
        }
        if (defs.remove(definitionId) != null) {
            refreshSelectorCatalogSnapshot(key);
            return;
        }
        List<String> matches = defs.entrySet().stream()
            .filter(entry -> matchesCanonicalReference(entry.getValue(), definitionId)
                || matchesLocalReference(entry.getValue(), definitionId))
            .map(Map.Entry::getKey)
            .toList();
        if (matches.size() == 1) {
            defs.remove(matches.getFirst());
            refreshSelectorCatalogSnapshot(key);
        }
    }

    public synchronized NodeDefinition getDefinition(String serverId, String nodeId) {
        if (nodeId == null) {
            return null;
        }
        String key = normalizeServerId(serverId);
        Map<String, NodeDefinition> definitions = serverDefinitions.get(key);
        if (definitions != null) {
            List<NodeDefinition> canonicalMatches = definitions.values().stream()
                .filter(definition -> matchesCanonicalReference(definition, nodeId))
                .distinct()
                .toList();
            if (canonicalMatches.size() == 1) {
                return canonicalMatches.getFirst();
            }
            if (canonicalMatches.size() > 1) {
                return null;
            }
            List<NodeDefinition> localMatches = definitions.values().stream()
                .filter(definition -> matchesLocalReference(definition, nodeId))
                .distinct()
                .toList();
            if (localMatches.size() == 1) {
                return localMatches.getFirst();
            }
            if (localMatches.size() > 1) {
                return null;
            }
        }
        return serverCanonicalCatalogs.containsKey(key) ? null : getUnresolvedDefinition(serverId, nodeId);
    }

    private boolean matchesCanonicalReference(NodeDefinition definition, String nodeId) {
        if (definition == null || nodeId == null) {
            return false;
        }
        try {
            NodeReference ownerReference = new NodeReference(definition.getOwner(), definition.getId());
            if (ownerReference.canonical().equalsIgnoreCase(nodeId)) {
                return true;
            }
            String canonicalId = definition.getCanonicalId();
            if (canonicalId != null && !canonicalId.isBlank()
                && new NodeReference(definition.getOwner(), canonicalId).canonical().equalsIgnoreCase(nodeId)) {
                return true;
            }
            return qualifiedDefinitionIdentity(definition, definition.getId(), nodeId)
                || qualifiedDefinitionIdentity(definition, canonicalId, nodeId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean matchesLocalReference(NodeDefinition definition, String nodeId) {
        if (definition == null || nodeId == null || nodeId.indexOf(':') >= 0) {
            return false;
        }
        try {
            return new NodeReference(definition.getOwner(), definition.getId()).localId().equals(nodeId)
                || definition.getId().equals(nodeId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean qualifiedDefinitionIdentity(NodeDefinition definition, String definitionId, String nodeId) {
        if (definition == null || definitionId == null || definitionId.isBlank() || nodeId == null
            || !definitionId.equalsIgnoreCase(nodeId)) {
            return false;
        }
        int separator = definitionId.indexOf(':');
        if (separator <= 0 || separator == definitionId.length() - 1
            || definitionId.indexOf(':', separator + 1) >= 0) {
            return false;
        }
        String owner = definition.getOwner();
        String qualifiedOwner = definitionId.substring(0, separator);
        return "builtin".equalsIgnoreCase(owner) || owner.equalsIgnoreCase(qualifiedOwner);
    }

    private NodeReference canonicalReference(NodeDefinition definition) {
        if (definition == null) {
            return null;
        }
        try {
            return new NodeReference(definition.getOwner(), definition.getId());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public synchronized NodeDefinition getDefinition(String serverId, NodeReference reference) {
        return getActiveDefinition(serverId, reference);
    }

    public synchronized NodeDefinition getAuthoritativeDefinition(String serverId, String nodeId) {
        if (!hasCanonicalCatalogAuthority(serverId) || nodeId == null || nodeId.isBlank()) {
            return null;
        }
        String key = normalizeServerId(serverId);
        Map<String, NodeDefinition> definitions = serverDefinitions.get(key);
        CanonicalCatalogAuthority authority = serverCanonicalCatalogs.get(key);
        if (definitions == null || authority == null) {
            return null;
        }
        List<NodeDefinition> canonicalMatches = definitions.values().stream()
            .filter(definition -> authority.includes(definition))
            .filter(definition -> matchesCanonicalReference(definition, nodeId))
            .distinct()
            .toList();
        if (canonicalMatches.size() == 1) {
            return canonicalMatches.getFirst();
        }
        if (canonicalMatches.size() > 1) {
            return null;
        }
        List<NodeDefinition> localMatches = definitions.values().stream()
            .filter(definition -> authority.includes(definition))
            .filter(definition -> matchesLocalReference(definition, nodeId))
            .distinct()
            .toList();
        return localMatches.size() == 1 ? localMatches.getFirst() : null;
    }

    public synchronized NodeDefinition getAuthoritativeDefinition(String serverId, NodeReference reference) {
        return reference != null ? getAuthoritativeDefinition(serverId, reference.canonical()) : null;
    }

    public synchronized Map<String, NodeDefinition> getAuthoritativeDefinitions(String serverId) {
        if (!hasCanonicalCatalogAuthority(serverId)) {
            return Map.of();
        }
        String key = normalizeServerId(serverId);
        Map<String, NodeDefinition> definitions = serverDefinitions.get(key);
        CanonicalCatalogAuthority authority = serverCanonicalCatalogs.get(key);
        if (definitions == null || authority == null) {
            return Map.of();
        }
        LinkedHashMap<String, NodeDefinition> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, NodeDefinition> entry : orderedDefinitionMap(definitions).entrySet()) {
            NodeDefinition definition = entry.getValue();
            NodeReference reference = canonicalReference(definition);
            if (authority.includes(definition) && reference != null) {
                ordered.putIfAbsent(reference.canonical(), definition);
            }
        }
        return ordered;
    }

    public synchronized boolean hasCanonicalCatalogAuthority(String serverId) {
        return serverCanonicalCatalogs.containsKey(normalizeServerId(serverId));
    }

    public synchronized boolean hasAuthoritativeDefinitions(String serverId) {
        return !getAuthoritativeDefinitions(serverId).isEmpty();
    }

    public synchronized NodeDefinition getActiveDefinition(String serverId, NodeReference reference) {
        if (reference == null) {
            return null;
        }
        String key = normalizeServerId(serverId);
        Map<String, NodeDefinition> definitions = serverDefinitions.get(key);
        if (definitions == null) {
            return null;
        }
        CanonicalCatalogAuthority authority = serverCanonicalCatalogs.get(key);
        NodeDefinition definition = definitions.get(reference.canonical());
        if (definition != null && reference.owner().equalsIgnoreCase(definition.getOwner())
            && (authority == null || authority.includes(definition))) {
            return definition;
        }
        List<NodeDefinition> matches = definitions.values().stream()
            .filter(value -> value != null && reference.owner().equalsIgnoreCase(value.getOwner())
                && (authority == null || authority.includes(value))
                && matchesLocalReference(value, reference.localId()))
            .distinct()
            .toList();
        if (matches.size() != 1) {
            return null;
        }
        return matches.getFirst();
    }

    public record NodeReference(String owner, String nodeId) {
        public NodeReference {
            owner = required(owner, "node owner");
            nodeId = required(nodeId, "node ID");
            String prefix = owner + ":";
            if (nodeId.regionMatches(true, 0, prefix, 0, prefix.length())) {
                nodeId = nodeId.substring(prefix.length());
            }
        }

        public String canonical() {
            return owner + ":" + nodeId;
        }

        public String localId() {
            return nodeId;
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
    }

    public synchronized boolean hasDefinitions(String serverId) {
        String key = normalizeServerId(serverId);
        Map<String, NodeDefinition> definitions = serverDefinitions.get(key);
        return definitions != null && !definitions.isEmpty();
    }

    public synchronized Map<String, NodeDefinition> getAllDefinitions(String serverId) {
        String key = normalizeServerId(serverId);
        Map<String, NodeDefinition> definitions = serverDefinitions.get(key);
        if (definitions == null) {
            return new LinkedHashMap<>();
        }
        return orderedDefinitionMap(definitions);
    }

    public synchronized Map<String, NodeDefinition> getLocalDefinitions() {
        return orderedDefinitionMap(localDefinitions);
    }

    public boolean applySnapshot(String serverId, NodeRegistrySnapshot snapshot) {
        return applySnapshot(serverId, snapshot, null);
    }

    public synchronized boolean applySnapshot(String serverId, NodeRegistrySnapshot snapshot, SnapshotAuthority authority) {
        ServerState candidate = stageSnapshot(serverId, snapshot, authority);
        return candidate != null && swapServerState(candidate);
    }

    public synchronized ServerState stageSnapshot(String serverId, NodeRegistrySnapshot snapshot, SnapshotAuthority authority) {
        return stageSnapshot(serverId, snapshot, authority, null);
    }

    public synchronized ServerState stageSnapshot(String serverId, NodeRegistrySnapshot snapshot,
                                                  SnapshotAuthority authority, Map<String, Object> snapshotData) {
        if (serverId == null || snapshot == null) {
            return null;
        }
        if (authority != null && !serverId.equals(authority.serverId())) {
            return null;
        }
        if (snapshot.getContractVersion() < ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion()
            || snapshot.getContractVersion() > ReSyncProtocolContract.FLOW_CONTRACT.version()
            || snapshot.getMinimumClientContractVersion() > ReSyncProtocolContract.FLOW_CONTRACT.version()
            || !ReSyncProtocolContract.FLOW_CONTRACT.hasRequiredCapabilities(snapshot.getCapabilities())
            || !snapshot.getServerIdentity().isBlank() && !serverId.equals(snapshot.getServerIdentity())
            || snapshot.getCompatibleUntil() > 0 && snapshot.getCompatibleUntil() < System.currentTimeMillis()) {
            return null;
        }
        if (!validSnapshotPayload(snapshot)) {
            return null;
        }
        CanonicalCatalogAuthority canonicalCatalog = canonicalCatalogAuthority(snapshot);
        if (snapshot.requiresCanonicalCatalog() && canonicalCatalog == null) {
            return null;
        }
        String key = normalizeServerId(serverId);
        ServerState currentState = captureServerState(serverId);
        if (canonicalCatalog == null && !snapshot.hasCanonicalCatalogPayload()) {
            canonicalCatalog = currentState.catalogAuthority();
        }
        SnapshotAuthority currentAuthority = serverSnapshotAuthorities.get(key);
        SnapshotAuthority snapshotAuthority = snapshotAuthority(snapshot, serverId);
        SnapshotAuthority nextAuthority = authority != null ? authority : snapshotAuthority != null ? snapshotAuthority : currentAuthority;
        if (nextAuthority != null && (!validText(nextAuthority.serverId()) || !validText(nextAuthority.checksum()))) {
            return null;
        }
        String snapshotProjectionIdentity = snapshot.getCatalogProjectionIdentity();
        String currentProjectionIdentity = serverSnapshotProjectionIdentities.getOrDefault(key, "");
        if (snapshotProjectionIdentity.isBlank() && nextAuthority != null && currentAuthority != null
            && nextAuthority.generation() == currentAuthority.generation()
            && nextAuthority.checksum().equals(currentAuthority.checksum())) {
            snapshotProjectionIdentity = currentProjectionIdentity;
        }
        if (nextAuthority != null && currentAuthority != null
            && nextAuthority.generation() == currentAuthority.generation()
            && nextAuthority.checksum().equals(currentAuthority.checksum())
            && !currentProjectionIdentity.isBlank() && !snapshotProjectionIdentity.isBlank()
            && !currentProjectionIdentity.equals(snapshotProjectionIdentity)) {
            return null;
        }
        if (nextAuthority != null && currentAuthority != null) {
            if (nextAuthority.generation() < currentAuthority.generation()) {
                return null;
            }
            if (nextAuthority.generation() == currentAuthority.generation()
                && !nextAuthority.checksum().equals(currentAuthority.checksum())) {
                return null;
            }
        }
        RegistrySessionMetadata currentSession = currentState.session();
        String currentChecksum = currentSession != null ? currentSession.checksum() : "";
        if (!snapshot.canApplyTo(currentChecksum)) {
            return null;
        }

        Map<String, NodePluginPayload> previousPlugins = new HashMap<>(currentState.plugins());
        Map<String, NodePluginPayload> plugins = new HashMap<>(currentState.plugins());
        Map<String, NodePluginPayload> unresolvedPlugins = new HashMap<>(currentState.unresolvedPlugins());
        List<String> nodeIds = new ArrayList<>(currentState.nodeIds());
        Map<String, Map<String, List<String>>> propertyActions = copyPropertyActions(currentState.propertyActions());
        Map<String, Map<String, FlowDataType>> propertyOutputTypes = copyPropertyOutputTypes(currentState.propertyOutputTypes());
        List<FlowPropertyMetadata> propertyMetadata = new ArrayList<>(currentState.propertyMetadata());
        List<FlowResourceMetadata> resourceMetadata = new ArrayList<>(currentState.resourceMetadata());
        List<FlowTypeMetadata> typeMetadata = new ArrayList<>(currentState.typeMetadata());
        Map<String, FlowDataType> dataTypes = new LinkedHashMap<>(currentState.dataTypes());
        List<FlowCategoryMetadata> categoryMetadata = new ArrayList<>(currentState.categoryMetadata());
        List<FlowOptionSourceMetadata> optionSourceMetadata = new ArrayList<>(currentState.optionSourceMetadata());
        List<FlowConversionRule> conversionRules = new ArrayList<>(currentState.conversionRules());

        if (snapshot.isFullSync()) {
            plugins.clear();
            nodeIds.clear();
            Set<String> incomingPluginIds = snapshot.getPlugins().stream().filter(payload -> payload != null && payload.getPluginId() != null)
                .map(NodePluginPayload::getPluginId).collect(Collectors.toSet());
            for (Map.Entry<String, NodePluginPayload> entry : previousPlugins.entrySet()) {
                if (!incomingPluginIds.contains(entry.getKey())) {
                    unresolvedPlugins.put(entry.getKey(), entry.getValue());
                }
            }
        }

        for (String pluginId : snapshot.getRemovedPlugins()) {
            NodePluginPayload removed = plugins.remove(pluginId);
            if (removed != null) {
                unresolvedPlugins.put(pluginId, removed);
            }
        }

        for (NodePluginPayload payload : snapshot.getPlugins()) {
            plugins.put(payload.getPluginId(), payload);
            unresolvedPlugins.remove(payload.getPluginId());
        }

        nodeIds = new ArrayList<>(snapshot.getNodeIds());

        if (snapshot.getPropertyActions() != null) {
            propertyActions = copyPropertyActions(snapshot.getPropertyActions());
        }
        if (snapshot.getPropertyOutputTypes() != null) {
            propertyOutputTypes = copyPropertyOutputTypes(snapshot.getPropertyOutputTypes());
        }
        if (snapshot.getPropertyMetadata() != null) {
            propertyMetadata = new ArrayList<>(snapshot.getPropertyMetadata());
        }
        if (snapshot.getResourceMetadata() != null) {
            resourceMetadata = new ArrayList<>(snapshot.getResourceMetadata());
        }

        if (snapshot.getTypeMetadata() != null) {
            typeMetadata = new ArrayList<>(snapshot.getTypeMetadata());
            dataTypes = buildServerDataTypes(key, snapshot.getTypeMetadata());
        }
        if (snapshot.getCategoryMetadata() != null) {
            categoryMetadata = new ArrayList<>(snapshot.getCategoryMetadata());
        }
        if (snapshot.getOptionSourceMetadata() != null) {
            optionSourceMetadata = new ArrayList<>(snapshot.getOptionSourceMetadata());
        }
        if (snapshot.getConversionRules() != null) {
            conversionRules = new ArrayList<>(snapshot.getConversionRules());
        }

        String registryChecksum = snapshot.getRegistryChecksum();
        if (registryChecksum == null || registryChecksum.isBlank()) {
            registryChecksum = currentSession != null ? currentSession.checksum() : "";
        }
        long generatedAt = snapshot.getGeneratedAt() > 0 ? snapshot.getGeneratedAt()
            : currentSession != null ? currentSession.generatedAt() : 0L;
        Map<String, Object> data = snapshotData != null ? snapshotData
            : !snapshot.getOpaqueData().isEmpty() ? snapshot.getOpaqueData() : currentState.snapshotData();
        Map<String, NodeDefinition> definitions = buildServerDefinitions(key, nodeIds, plugins, canonicalCatalog);
        if (definitions == null) {
            return null;
        }
        RegistrySessionMetadata session = new RegistrySessionMetadata(snapshot.getContractVersion(), snapshot.getMinimumClientContractVersion(),
            serverId, registryChecksum, generatedAt, snapshot.getCompatibleUntil(), snapshot.getCapabilities(),
            snapshot.getRegistryDiagnostics(), snapshot.isFullSync());
        return new ServerState(serverId, definitions, plugins, unresolvedPlugins, nodeIds, propertyActions, propertyOutputTypes,
            propertyMetadata, resourceMetadata, typeMetadata, dataTypes, categoryMetadata, optionSourceMetadata, conversionRules,
            session, nextAuthority, snapshotProjectionIdentity, data, canonicalCatalog);
    }

    private boolean validSnapshotPayload(NodeRegistrySnapshot snapshot) {
        if (!snapshot.hasValidCanonicalCatalog()) {
            return false;
        }
        Set<String> pluginIds = new HashSet<>();
        Set<String> definitionIds = new HashSet<>();
        for (NodePluginPayload payload : snapshot.getPlugins()) {
            if (payload == null || !validText(payload.getPluginId()) || !pluginIds.add(payload.getPluginId())) {
                return false;
            }
            if (payload.getNodes() == null) {
                return false;
            }
            for (NodeDefinition definition : payload.getNodes()) {
                NodeReference reference = canonicalReference(definition);
                if (definition == null || !validText(definition.getId()) || !validText(definition.getOwner())
                    || reference == null || !definitionIds.add(reference.canonical().toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
        }
        for (String pluginId : snapshot.getRemovedPlugins()) {
            if (!validText(pluginId)) {
                return false;
            }
        }
        for (String nodeId : snapshot.getNodeIds()) {
            if (!validText(nodeId)) {
                return false;
            }
        }
        if (snapshot.getCapabilities().stream().anyMatch(value -> !validText(value))
            || snapshot.getPropertyMetadata().stream().anyMatch(value -> value == null)
            || snapshot.getResourceMetadata().stream().anyMatch(value -> value == null)
            || snapshot.getTypeMetadata().stream().anyMatch(value -> value == null)
            || snapshot.getCategoryMetadata().stream().anyMatch(value -> value == null)
            || snapshot.getOptionSourceMetadata().stream().anyMatch(value -> value == null)
            || snapshot.getConversionRules().stream().anyMatch(value -> value == null)) {
            return false;
        }
        return snapshot.getCatalogGeneration() >= -1L
            && ((snapshot.getCatalogGeneration() < 0L
            && snapshot.getCatalogChecksum().isBlank()
            && snapshot.getCatalogProjectionIdentity().isBlank())
            || (snapshot.getCatalogGeneration() >= 0L
            && !snapshot.getCatalogChecksum().isBlank()
            && !snapshot.getCatalogProjectionIdentity().isBlank()));
    }

    private boolean validText(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim())
            && value.chars().noneMatch(Character::isISOControl);
    }

    private SnapshotAuthority snapshotAuthority(NodeRegistrySnapshot snapshot, String serverId) {
        if (snapshot.getCatalogGeneration() < 0L && snapshot.getCatalogChecksum().isBlank()) {
            return null;
        }
        if (snapshot.getCatalogGeneration() < 0L || snapshot.getCatalogChecksum().isBlank()) {
            return null;
        }
        try {
            return new SnapshotAuthority(serverId, snapshot.getCatalogGeneration(), snapshot.getCatalogChecksum());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private CanonicalCatalogAuthority canonicalCatalogAuthority(NodeRegistrySnapshot snapshot) {
        if (snapshot == null || !snapshot.hasCanonicalCatalogPayload()) {
            return null;
        }
        try {
            NodeRegistrySnapshot.CanonicalCatalog catalog = snapshot.canonicalCatalog().orElse(null);
            if (catalog == null) {
                return null;
            }
            Object rawDefinitions = catalog.document().get("definitions");
            if (!(rawDefinitions instanceof Iterable<?> entries)) {
                return null;
            }
            Set<CanonicalNodeIdentity> definitions = new HashSet<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> rawEntry)) {
                    return null;
                }
                Map<?, ?> descriptor = rawEntry.get("descriptor") instanceof Map<?, ?> nested ? nested : rawEntry;
                String owner = canonicalText(rawEntry, "ownerId", "owner");
                String descriptorId = canonicalText(rawEntry, "descriptorId");
                if (descriptorId.isBlank()) {
                    descriptorId = canonicalText(descriptor, "id", "descriptorId");
                }
                if (owner.isBlank() || descriptorId.isBlank()) {
                    return null;
                }
                String authoredNodeId = canonicalText(descriptor, "sourceNodeId");
                if (authoredNodeId.isBlank() && descriptor.get("metadata") instanceof Map<?, ?> metadata) {
                    authoredNodeId = canonicalText(metadata, "sourceNodeId");
                }
                if (authoredNodeId.isBlank()) {
                    authoredNodeId = descriptorId;
                }
                if (!definitions.add(new CanonicalNodeIdentity(owner, descriptorId, authoredNodeId))) {
                    return null;
                }
            }
            return new CanonicalCatalogAuthority(catalog.content(), catalog.generation(), catalog.checksum(),
                catalog.bindingManifestHash(), definitions);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String canonicalText(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    private static int categoryPriority(NodeDefinition definition) {
        return definition != null && definition.getCategory() != null
            ? definition.getCategory().getPriority() : Integer.MAX_VALUE;
    }

    private static String categoryId(NodeDefinition definition) {
        return definition != null && definition.getCategory() != null
            ? definition.getCategory().getId() : null;
    }

    private static String categoryName(NodeDefinition definition) {
        return definition != null && definition.getCategory() != null
            ? definition.getCategory().getDisplayName() : null;
    }

    private static String definitionType(NodeDefinition definition) {
        return definition != null && definition.getKind() != null ? definition.getKind().name() : null;
    }

    private static String canonicalDefinitionId(NodeDefinition definition) {
        if (definition == null) {
            return null;
        }
        String id = definition.getCanonicalId();
        if (id == null || id.isBlank()) {
            id = definition.getId();
        }
        try {
            return new NodeReference(definition.getOwner(), id).canonical();
        } catch (IllegalArgumentException exception) {
            return id;
        }
    }

    private static int compareStableText(String left, String right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        int comparison = String.CASE_INSENSITIVE_ORDER.compare(left, right);
        return comparison != 0 ? comparison : left.compareTo(right);
    }

    private static LinkedHashMap<String, NodeDefinition> orderedDefinitionMap(
        Map<String, NodeDefinition> source) {
        LinkedHashMap<String, NodeDefinition> ordered = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return ordered;
        }
        List<Map.Entry<String, NodeDefinition>> entries = new ArrayList<>(source.entrySet());
        entries.sort((left, right) -> {
            int comparison = DEFINITION_ORDER.compare(left.getValue(), right.getValue());
            return comparison != 0 ? comparison : compareStableText(left.getKey(), right.getKey());
        });
        for (Map.Entry<String, NodeDefinition> entry : entries) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        return ordered;
    }

    private static List<NodeDefinition> orderedDefinitions(List<NodeDefinition> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<NodeDefinition> ordered = new ArrayList<>(source);
        ordered.sort(DEFINITION_ORDER);
        return ordered;
    }

    private static NodePluginPayload orderedPayload(NodePluginPayload source) {
        NodePluginPayload ordered = new NodePluginPayload();
        ordered.setPluginId(source.getPluginId());
        ordered.setVersion(source.getVersion());
        ordered.setDescription(source.getDescription());
        ordered.setChecksum(source.getChecksum());
        ordered.setOpaqueData(source.getOpaqueData());
        ordered.setNodes(orderedDefinitions(source.getNodes()));
        return ordered;
    }

    private static String authoredNodeId(NodeDefinition definition) {
        Map<String, Object> handlerConfig = definition.getHandlerConfig();
        Object sourceNodeId = handlerConfig != null ? handlerConfig.get("sourceNodeId") : null;
        return sourceNodeId instanceof String value && !value.isBlank() ? value.trim() : definition.getId();
    }

    public boolean containsDefinition(ServerState state, NodeReference reference) {
        if (state == null || reference == null) {
            return false;
        }
        return containsDefinition(state.definitions(), reference);
    }

    private boolean containsDefinition(Map<String, NodeDefinition> definitions, NodeReference reference) {
        if (definitions == null || reference == null) {
            return false;
        }
        NodeDefinition exact = definitions.get(reference.canonical());
        if (exact != null && reference.owner().equalsIgnoreCase(exact.getOwner())) {
            return true;
        }
        return definitions.values().stream()
            .filter(definition -> definition != null && reference.owner().equalsIgnoreCase(definition.getOwner()))
            .filter(definition -> matchesLocalReference(definition, reference.localId()))
            .distinct()
            .count() == 1;
    }

    public synchronized boolean swapServerState(ServerState state) {
        return swapServerState(state, true);
    }

    public synchronized boolean swapServerState(ServerState state, boolean notify) {
        if (state == null || state.serverId().isBlank()) {
            return false;
        }
        String key = normalizeServerId(state.serverId());
        serverDefinitions.remove(key);
        serverPlugins.remove(key);
        serverUnresolvedPlugins.remove(key);
        serverNodeIds.remove(key);
        serverPropertyActions.remove(key);
        serverPropertyOutputTypes.remove(key);
        serverPropertyMetadata.remove(key);
        serverResourceMetadata.remove(key);
        serverTypeMetadata.remove(key);
        serverDataTypes.remove(key);
        serverCategoryMetadata.remove(key);
        serverOptionSourceMetadata.remove(key);
        serverConversionRules.remove(key);
        serverRegistrySessions.remove(key);
        serverSnapshotAuthorities.remove(key);
        serverSnapshotProjectionIdentities.remove(key);
        serverCanonicalCatalogs.remove(key);
        serverSnapshotData.remove(key);
        serverInvalidationReasons.remove(key);
        if (!state.definitions().isEmpty()) {
            serverDefinitions.put(key, new HashMap<>(state.definitions()));
        }
        if (!state.plugins().isEmpty()) {
            serverPlugins.put(key, new HashMap<>(state.plugins()));
        }
        if (!state.unresolvedPlugins().isEmpty()) {
            serverUnresolvedPlugins.put(key, new HashMap<>(state.unresolvedPlugins()));
        }
        if (!state.nodeIds().isEmpty()) {
            serverNodeIds.put(key, new ArrayList<>(state.nodeIds()));
        }
        if (!state.propertyActions().isEmpty()) {
            serverPropertyActions.put(key, new HashMap<>(state.propertyActions()));
        }
        if (!state.propertyOutputTypes().isEmpty()) {
            serverPropertyOutputTypes.put(key, new HashMap<>(state.propertyOutputTypes()));
        }
        if (!state.propertyMetadata().isEmpty()) {
            serverPropertyMetadata.put(key, new ArrayList<>(state.propertyMetadata()));
        }
        if (!state.resourceMetadata().isEmpty()) {
            serverResourceMetadata.put(key, new ArrayList<>(state.resourceMetadata()));
        }
        if (!state.typeMetadata().isEmpty()) {
            serverTypeMetadata.put(key, new ArrayList<>(state.typeMetadata()));
        }
        if (!state.dataTypes().isEmpty()) {
            serverDataTypes.put(key, new HashMap<>(state.dataTypes()));
        }
        if (!state.categoryMetadata().isEmpty()) {
            serverCategoryMetadata.put(key, new ArrayList<>(state.categoryMetadata()));
        }
        if (!state.optionSourceMetadata().isEmpty()) {
            serverOptionSourceMetadata.put(key, new ArrayList<>(state.optionSourceMetadata()));
        }
        if (!state.conversionRules().isEmpty()) {
            serverConversionRules.put(key, new ArrayList<>(state.conversionRules()));
        }
        if (state.session() != null) {
            serverRegistrySessions.put(key, state.session());
        }
        if (state.authority() != null) {
            serverSnapshotAuthorities.put(key, state.authority());
        }
        if (!state.projectionIdentity().isBlank()) {
            serverSnapshotProjectionIdentities.put(key, state.projectionIdentity());
        }
        if (state.catalogAuthority() != null) {
            serverCanonicalCatalogs.put(key, state.catalogAuthority());
        }
        if (!state.snapshotData().isEmpty()) {
            serverSnapshotData.put(key, copySnapshotData(state.snapshotData()));
        }
        long revision = selectorCatalogRevisions.merge(key, 1L, Long::sum);
        selectorCatalogSnapshots.put(key, new SelectorCatalogSnapshot(key, revision, state.definitions(),
            state.dataTypes(), state.categoryMetadata(), state.conversionRules(), state.authority()));
        if (notify) {
            notifyListeners(state.serverId());
        }
        return true;
    }

    public synchronized ServerState captureServerState(String serverId) {
        String key = normalizeServerId(serverId);
        return new ServerState(serverId,
            orderedDefinitionMap(serverDefinitions.get(key)),
            copyMap(serverPlugins.get(key)),
            copyMap(serverUnresolvedPlugins.get(key)),
            copyList(serverNodeIds.get(key)),
            copyPropertyActions(serverPropertyActions.get(key)),
            copyPropertyOutputTypes(serverPropertyOutputTypes.get(key)),
            copyList(serverPropertyMetadata.get(key)),
            copyList(serverResourceMetadata.get(key)),
            copyList(serverTypeMetadata.get(key)),
            copyMap(serverDataTypes.get(key)),
            copyList(serverCategoryMetadata.get(key)),
            copyList(serverOptionSourceMetadata.get(key)),
            copyList(serverConversionRules.get(key)),
            serverRegistrySessions.get(key),
            serverSnapshotAuthorities.get(key),
            serverSnapshotProjectionIdentities.getOrDefault(key, ""),
            copySnapshotData(serverSnapshotData.get(key)),
            serverCanonicalCatalogs.get(key));
    }

    public synchronized boolean restoreServerState(ServerState state) {
        return restoreServerState(state, true);
    }

    public synchronized boolean restoreServerState(ServerState state, boolean notify) {
        return swapServerState(state, notify);
    }

    public synchronized boolean publish(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        return notifyListeners(serverId);
    }

    public synchronized void clearServer(String serverId) {
        clearServer(serverId, true);
    }

    public synchronized void clearServer(String serverId, boolean notify) {
        String key = normalizeServerId(serverId);
        serverPlugins.remove(key);
        serverUnresolvedPlugins.remove(key);
        serverNodeIds.remove(key);
        serverDefinitions.remove(key);
        serverPropertyActions.remove(key);
        serverPropertyOutputTypes.remove(key);
        serverPropertyMetadata.remove(key);
        serverResourceMetadata.remove(key);
        serverTypeMetadata.remove(key);
        serverDataTypes.remove(key);
        serverCategoryMetadata.remove(key);
        serverOptionSourceMetadata.remove(key);
        serverConversionRules.remove(key);
        serverRegistrySessions.remove(key);
        serverSnapshotAuthorities.remove(key);
        serverSnapshotProjectionIdentities.remove(key);
        serverCanonicalCatalogs.remove(key);
        serverSnapshotData.remove(key);
        serverInvalidationReasons.remove(key);
        selectorCatalogRevisions.merge(key, 1L, Long::sum);
        selectorCatalogSnapshots.remove(key);
        if (notify) {
            notifyListeners(serverId);
        }
    }

    public synchronized boolean hasServerState(String serverId) {
        String key = normalizeServerId(serverId);
        return serverDefinitions.containsKey(key) || serverPlugins.containsKey(key)
            || serverUnresolvedPlugins.containsKey(key) || serverNodeIds.containsKey(key)
            || serverRegistrySessions.containsKey(key) || serverSnapshotAuthorities.containsKey(key)
            || serverCanonicalCatalogs.containsKey(key)
            || serverSnapshotProjectionIdentities.containsKey(key)
            || serverSnapshotData.containsKey(key);
    }

    public synchronized void invalidateServer(String serverId, String reason) {
        String key = normalizeServerId(serverId);
        clearServer(serverId, false);
        serverInvalidationReasons.put(key, reason != null && !reason.isBlank() ? reason.trim() : "Registry publication invalidated");
        notifyListeners(serverId);
    }

    public synchronized RegistrySessionMetadata getRegistrySessionMetadata(String serverId) {
        return serverRegistrySessions.get(normalizeServerId(serverId));
    }

    public synchronized SnapshotAuthority getSnapshotAuthority(String serverId) {
        return serverSnapshotAuthorities.get(normalizeServerId(serverId));
    }

    public SelectorCatalogSnapshot getSelectorCatalogSnapshot(String serverId) {
        return selectorCatalogSnapshots.get(normalizeServerId(serverId));
    }

    public synchronized String getSnapshotProjectionIdentity(String serverId) {
        return serverSnapshotProjectionIdentities.getOrDefault(normalizeServerId(serverId), "");
    }

    public synchronized Optional<CanonicalCatalogAuthority> getCanonicalCatalogAuthority(String serverId) {
        return Optional.ofNullable(serverCanonicalCatalogs.get(normalizeServerId(serverId)));
    }

    public synchronized String getInvalidationReason(String serverId) {
        return serverInvalidationReasons.getOrDefault(normalizeServerId(serverId), "");
    }

    public synchronized void restoreInvalidationReason(String serverId, String reason) {
        if (serverId == null) {
            return;
        }
        String key = normalizeServerId(serverId);
        if (reason == null || reason.isBlank()) {
            serverInvalidationReasons.remove(key);
        } else {
            serverInvalidationReasons.put(key, reason);
        }
    }

    public synchronized void clearSnapshotAuthority(String serverId) {
        if (serverId != null) {
            String key = normalizeServerId(serverId);
            serverSnapshotAuthorities.remove(key);
            serverSnapshotProjectionIdentities.remove(key);
            serverCanonicalCatalogs.remove(key);
            refreshSelectorCatalogSnapshot(key);
        }
    }

    public synchronized void preserveSnapshotData(String serverId, Map<String, Object> data) {
        if (serverId == null || data == null) {
            return;
        }
        serverSnapshotData.put(normalizeServerId(serverId), copySnapshotData(data));
    }

    public synchronized Map<String, Object> getSnapshotData(String serverId) {
        return serverSnapshotData.getOrDefault(normalizeServerId(serverId), Map.of());
    }

    private Map<String, Object> copySnapshotData(Map<String, Object> data) {
        return data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    private static <K, V> Map<K, V> copyMap(Map<K, V> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    private static <T> List<T> copyList(List<T> source) {
        return source == null ? List.of() : new ArrayList<>(source);
    }

    private static Map<String, Map<String, List<String>>> copyPropertyActions(
        Map<String, Map<String, List<String>>> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Map<String, List<String>>> copy = new LinkedHashMap<>();
        source.forEach((family, properties) -> copy.put(family,
            properties == null ? Map.of() : properties.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                    entry -> entry.getValue() == null ? List.of() : new ArrayList<>(entry.getValue()),
                    (left, right) -> right, LinkedHashMap::new))));
        return copy;
    }

    private static Map<String, Map<String, FlowDataType>> copyPropertyOutputTypes(
        Map<String, Map<String, FlowDataType>> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Map<String, FlowDataType>> copy = new LinkedHashMap<>();
        source.forEach((family, properties) -> copy.put(family,
            properties == null ? Map.of() : new LinkedHashMap<>(properties)));
        return copy;
    }

    public record ServerState(String serverId,
                              Map<String, NodeDefinition> definitions,
                              Map<String, NodePluginPayload> plugins,
                              Map<String, NodePluginPayload> unresolvedPlugins,
                              List<String> nodeIds,
                              Map<String, Map<String, List<String>>> propertyActions,
                              Map<String, Map<String, FlowDataType>> propertyOutputTypes,
                              List<FlowPropertyMetadata> propertyMetadata,
                              List<FlowResourceMetadata> resourceMetadata,
                              List<FlowTypeMetadata> typeMetadata,
                              Map<String, FlowDataType> dataTypes,
                              List<FlowCategoryMetadata> categoryMetadata,
                              List<FlowOptionSourceMetadata> optionSourceMetadata,
                              List<FlowConversionRule> conversionRules,
                              RegistrySessionMetadata session,
                              SnapshotAuthority authority,
                              String projectionIdentity,
                              Map<String, Object> snapshotData,
                              CanonicalCatalogAuthority catalogAuthority) {
        public ServerState {
            serverId = serverId != null ? serverId : "";
            definitions = definitions != null
                ? Collections.unmodifiableMap(orderedDefinitionMap(definitions)) : Map.of();
            plugins = plugins != null ? Map.copyOf(plugins) : Map.of();
            unresolvedPlugins = unresolvedPlugins != null ? Map.copyOf(unresolvedPlugins) : Map.of();
            nodeIds = nodeIds != null ? nodeIds.stream().sorted(NodeRegistry::compareStableText).toList() : List.of();
            propertyActions = propertyActions != null ? Map.copyOf(propertyActions) : Map.of();
            propertyOutputTypes = propertyOutputTypes != null ? Map.copyOf(propertyOutputTypes) : Map.of();
            propertyMetadata = propertyMetadata != null ? List.copyOf(propertyMetadata) : List.of();
            resourceMetadata = resourceMetadata != null ? List.copyOf(resourceMetadata) : List.of();
            typeMetadata = typeMetadata != null ? List.copyOf(typeMetadata) : List.of();
            dataTypes = dataTypes != null ? Map.copyOf(dataTypes) : Map.of();
            categoryMetadata = categoryMetadata != null ? List.copyOf(categoryMetadata) : List.of();
            optionSourceMetadata = optionSourceMetadata != null ? List.copyOf(optionSourceMetadata) : List.of();
            conversionRules = conversionRules != null ? List.copyOf(conversionRules) : List.of();
            projectionIdentity = projectionIdentity != null ? projectionIdentity : "";
            snapshotData = snapshotData != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(snapshotData)) : Map.of();
        }
    }

    public record SelectorCatalogSnapshot(String serverId, long revision,
                                          Map<String, NodeDefinition> definitions,
                                          Map<String, FlowDataType> dataTypes,
                                          List<FlowCategoryMetadata> categoryMetadata,
                                          List<FlowConversionRule> conversionRules,
                                          List<NodeDefinition.NodeCategory> categoryOrder,
                                          SnapshotAuthority authority) {
        public SelectorCatalogSnapshot(String serverId, long revision, Map<String, NodeDefinition> definitions,
                                       Map<String, FlowDataType> dataTypes, List<FlowCategoryMetadata> categoryMetadata,
                                       List<FlowConversionRule> conversionRules, SnapshotAuthority authority) {
            this(serverId, revision, definitions, dataTypes, categoryMetadata, conversionRules,
                selectorCategoryOrder(categoryMetadata), authority);
        }

        public SelectorCatalogSnapshot {
            serverId = serverId != null ? serverId : "";
            if (revision < 0L) {
                throw new IllegalArgumentException("selector catalog revision cannot be negative");
            }
            definitions = definitions != null
                ? Collections.unmodifiableMap(orderedDefinitionMap(definitions)) : Map.of();
            dataTypes = dataTypes != null ? Map.copyOf(dataTypes) : Map.of();
            categoryMetadata = categoryMetadata != null ? List.copyOf(categoryMetadata) : List.of();
            conversionRules = conversionRules != null ? List.copyOf(conversionRules) : List.of();
            categoryOrder = categoryOrder != null ? List.copyOf(categoryOrder) : List.of();
        }

        public SelectorCatalogKey key() {
            return new SelectorCatalogKey(serverId, revision, authority);
        }

        private static List<NodeDefinition.NodeCategory> selectorCategoryOrder(List<FlowCategoryMetadata> metadata) {
            LinkedHashSet<NodeDefinition.NodeCategory> order = new LinkedHashSet<>();
            if (metadata != null) {
                for (FlowCategoryMetadata value : metadata) {
                    if (value != null) {
                        order.add(NodeDefinition.NodeCategory.fromString(value.getId()));
                    }
                }
            }
            if (order.isEmpty()) {
                order.addAll(NodeDefinition.NodeCategory.values());
            }
            return List.copyOf(order);
        }
    }

    public record SelectorCatalogKey(String serverId, long revision, SnapshotAuthority authority) {
    }

    public record CanonicalCatalogAuthority(String content, long generation, String checksum,
                                            String projectionIdentity, Set<CanonicalNodeIdentity> definitions) {
        public CanonicalCatalogAuthority {
            if (content == null || content.isBlank() || generation < 0L || checksum == null || checksum.isBlank()
                || projectionIdentity == null || projectionIdentity.isBlank()) {
                throw new IllegalArgumentException("canonical catalog authority is incomplete");
            }
            content = content.trim();
            checksum = checksum.trim();
            projectionIdentity = projectionIdentity.trim();
            definitions = definitions != null ? Set.copyOf(definitions) : Set.of();
        }

        private boolean includes(NodeDefinition definition) {
            if (definition == null || definition.getOwner() == null || definition.getId() == null) {
                return false;
            }
            String authoredNodeId = authoredNodeId(definition);
            String localNodeId = localNodeId(definition);
            return definitions.stream().anyMatch(value -> value.owner().equalsIgnoreCase(definition.getOwner())
                && (value.descriptorId().equalsIgnoreCase(localNodeId)
                || value.descriptorId().equalsIgnoreCase(definition.getCanonicalId())
                || value.authoredNodeId().equalsIgnoreCase(authoredNodeId)));
        }
    }

    private static String localNodeId(NodeDefinition definition) {
        try {
            return new NodeReference(definition.getOwner(), definition.getId()).localId();
        } catch (IllegalArgumentException exception) {
            return definition.getId();
        }
    }

    public record CanonicalNodeIdentity(String owner, String descriptorId, String authoredNodeId) {
        public CanonicalNodeIdentity {
            if (owner == null || owner.isBlank() || descriptorId == null || descriptorId.isBlank()
                || authoredNodeId == null || authoredNodeId.isBlank()) {
                throw new IllegalArgumentException("canonical node identity is incomplete");
            }
            owner = owner.trim();
            descriptorId = descriptorId.trim();
            authoredNodeId = authoredNodeId.trim();
        }
    }

    public record SnapshotAuthority(String serverId, long generation, String checksum) {
        public SnapshotAuthority {
            if (serverId == null || serverId.isBlank() || !serverId.equals(serverId.trim())) {
                throw new IllegalArgumentException("server identity is required");
            }
            if (generation < 0L) {
                throw new IllegalArgumentException("snapshot generation cannot be negative");
            }
            if (checksum == null || checksum.isBlank() || !checksum.equals(checksum.trim())) {
                throw new IllegalArgumentException("snapshot checksum is required");
            }
            serverId = serverId.trim();
            checksum = checksum.trim();
        }
    }

    public record RegistrySessionMetadata(int contractVersion, int minimumClientContractVersion, String serverIdentity,
                                          String checksum, long generatedAt, long compatibleUntil, List<String> capabilities,
                                          Map<String, Object> diagnostics, boolean fullSync) {
        public RegistrySessionMetadata {
            serverIdentity = serverIdentity != null ? serverIdentity : "";
            checksum = checksum != null ? checksum : "";
            capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
            diagnostics = diagnostics != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics)) : Map.of();
        }
    }

    public synchronized List<FlowCategoryMetadata> getServerCategories(String serverId) {
        String key = normalizeServerId(serverId);
        List<FlowCategoryMetadata> meta = serverCategoryMetadata.get(key);
        if (meta != null && !meta.isEmpty()) {
            return meta;
        }
        return NodeDefinition.NodeCategory.values().stream()
                .map(cat -> fallbackCategoryMetadata(cat))
                .toList();
    }

    private FlowCategoryMetadata fallbackCategoryMetadata(NodeDefinition.NodeCategory category) {
        String id = category.getId();
        if (List.of("logic", "data", "variable", "flow", "function", "utility").contains(id)) {
            return new FlowCategoryMetadata(id, category.getDisplayName(), category.getColor(), category.getPriority(), "flow", "Flow", 0xFF55FFFF, 100);
        }
        if (List.of("event", "action", "player", "entity", "block", "world", "inventory", "item", "visual", "world_gen").contains(id)) {
            return new FlowCategoryMetadata(id, category.getDisplayName(), category.getColor(), category.getPriority(), "minecraft", "Minecraft", 0xFF55AA55, 200);
        }
        if (List.of("command", "network", "chat", "scoreboard", "trade", "npc", "loot", "menu", "tab_list", "dialog", "custom_content", "recipe", "advancement", "text", "permission", "ability").contains(id)) {
            return new FlowCategoryMetadata(id, category.getDisplayName(), category.getColor(), category.getPriority(), "resync", "ReSync", 0xFF5CC8FF, 300);
        }
        return new FlowCategoryMetadata(id, category.getDisplayName(), category.getColor(), category.getPriority(), "integrations", "Integrations", 0xFF7289DA, 400);
    }

    public synchronized FlowOptionSourceMetadata getServerOptionSource(String serverId, String sourceId) {
        if (sourceId == null) {
            return null;
        }
        String key = normalizeServerId(serverId);
        List<FlowOptionSourceMetadata> list = serverOptionSourceMetadata.get(key);
        if (list == null) {
            return null;
        }
        for (FlowOptionSourceMetadata meta : list) {
            if (meta != null && meta.getId() != null && meta.getId().equalsIgnoreCase(sourceId)) {
                return meta;
            }
        }
        return null;
    }

    public synchronized FlowTypeMetadata getTypeMetadata(String serverId, String typeId) {
        if (typeId == null) {
            return null;
        }
        String key = normalizeServerId(serverId);
        List<FlowTypeMetadata> list = serverTypeMetadata.get(key);
        if (list == null) {
            return null;
        }
        for (FlowTypeMetadata meta : list) {
            if (meta != null && meta.getId() != null && meta.getId().equalsIgnoreCase(typeId)) {
                return meta;
            }
        }
        return null;
    }

    public synchronized List<FlowDataType> getServerDataTypes(String serverId) {
        String key = normalizeServerId(serverId);
        List<FlowTypeMetadata> list = serverTypeMetadata.get(key);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<FlowDataType> types = new ArrayList<>();
        for (FlowTypeMetadata meta : list) {
            if (meta == null || meta.getId() == null || meta.getId().isBlank()) {
                continue;
            }
            FlowDataType type = resolveType(key, meta.getId());
            if (type != FlowDataType.EXECUTION) {
                types.add(type);
            }
        }
        return types;
    }

    public synchronized boolean canConvertTypes(String serverId, FlowDataType source, FlowDataType target) {
        if (source == null || target == null) {
            return false;
        }
        FlowDataType resolvedSource = resolveType(serverId, source.getId());
        FlowDataType resolvedTarget = resolveType(serverId, target.getId());
        if (resolvedSource.canConvertTo(resolvedTarget)) {
            return true;
        }
        String key = normalizeServerId(serverId);
        List<FlowConversionRule> rules = serverConversionRules.get(key);
        if (rules == null) {
            return false;
        }
        String sourceId = source.getId();
        String targetId = target.getId();
        for (FlowConversionRule rule : rules) {
            if (rule != null
                    && rule.getSourceTypeId() != null
                    && rule.getTargetTypeId() != null
                    && (rule.getAvailability() == null || rule.getAvailability().isBlank() || "available".equalsIgnoreCase(rule.getAvailability()))
                    && rule.getSourceTypeId().equalsIgnoreCase(sourceId)
                    && rule.getTargetTypeId().equalsIgnoreCase(targetId)) {
                return true;
            }
        }
        return false;
    }

    public synchronized FlowConversionRule findConversionRule(String serverId, FlowDataType source, FlowDataType target) {
        if (source == null || target == null) {
            return null;
        }
        List<FlowConversionRule> rules = serverConversionRules.get(normalizeServerId(serverId));
        if (rules == null) {
            return null;
        }
        return rules.stream()
            .filter(rule -> rule != null
                && rule.getSourceTypeId() != null
                && rule.getTargetTypeId() != null
                && (rule.getAvailability() == null || rule.getAvailability().isBlank() || "available".equalsIgnoreCase(rule.getAvailability()))
                && rule.getSourceTypeId().equalsIgnoreCase(source.getId())
                && rule.getTargetTypeId().equalsIgnoreCase(target.getId()))
            .min(Comparator.comparingInt(FlowConversionRule::getCost))
            .orElse(null);
    }

    public synchronized List<FlowOptionSourceMetadata> getServerOptionSources(String serverId) {
        List<FlowOptionSourceMetadata> metadata = serverOptionSourceMetadata.get(normalizeServerId(serverId));
        return metadata != null ? List.copyOf(metadata) : List.of();
    }

    public synchronized List<FlowConversionRule> getServerConversionRules(String serverId) {
        List<FlowConversionRule> rules = serverConversionRules.get(normalizeServerId(serverId));
        return rules != null ? List.copyOf(rules) : List.of();
    }

    public synchronized List<String> getServerPluginIds(String serverId) {
        Map<String, NodePluginPayload> plugins = serverPlugins.get(normalizeServerId(serverId));
        return plugins != null ? plugins.keySet().stream().sorted(NodeRegistry::compareStableText).toList() : List.of();
    }

    public synchronized List<String> getUnresolvedPluginIds(String serverId) {
        Map<String, NodePluginPayload> plugins = serverUnresolvedPlugins.get(normalizeServerId(serverId));
        return plugins != null ? plugins.keySet().stream().sorted(NodeRegistry::compareStableText).toList() : List.of();
    }

    public synchronized List<NodePluginPayload> getUnresolvedPluginPayloads(String serverId) {
        Map<String, NodePluginPayload> plugins = serverUnresolvedPlugins.get(normalizeServerId(serverId));
        return plugins != null ? plugins.values().stream().filter(payload -> payload != null)
            .sorted((left, right) -> compareStableText(left.getPluginId(), right.getPluginId())).toList() : List.of();
    }

    public synchronized void restoreUnresolvedPlugins(String serverId, List<NodePluginPayload> payloads) {
        if (serverId == null || payloads == null || payloads.isEmpty()) {
            return;
        }
        String key = normalizeServerId(serverId);
        Map<String, NodePluginPayload> active = serverPlugins.getOrDefault(key, Map.of());
        Map<String, NodePluginPayload> unresolved = serverUnresolvedPlugins.computeIfAbsent(key, ignored -> BrowserSafeState.map());
        for (NodePluginPayload payload : payloads) {
            if (payload != null && payload.getPluginId() != null && !active.containsKey(payload.getPluginId())) {
                unresolved.put(payload.getPluginId(), payload);
            }
        }
    }

    public synchronized NodeDefinition getUnresolvedDefinition(String serverId, String nodeId) {
        if (nodeId == null || nodeId.isBlank() || !nodeId.equals(nodeId.trim())) {
            return null;
        }
        Map<String, NodePluginPayload> plugins = serverUnresolvedPlugins.get(normalizeServerId(serverId));
        if (plugins == null) {
            return null;
        }
        List<UnresolvedDefinitionCandidate> candidates = unresolvedDefinitionCandidates(plugins);
        boolean canonicalLookup = nodeId.indexOf(':') >= 0;
        List<NodeDefinition> matches = candidates.stream()
            .filter(candidate -> canonicalLookup
                ? candidate.matchesCanonical(nodeId)
                : candidate.matchesLocal(nodeId))
            .map(UnresolvedDefinitionCandidate::definition)
            .distinct()
            .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        return null;
    }

    private List<UnresolvedDefinitionCandidate> unresolvedDefinitionCandidates(
        Map<String, NodePluginPayload> plugins) {
        return plugins.values().stream()
            .filter(payload -> payload != null && payload.getNodes() != null)
            .sorted(Comparator.comparing(NodePluginPayload::getPluginId,
                Comparator.nullsFirst(NodeRegistry::compareStableText))
                .thenComparing(NodePluginPayload::getChecksum,
                    Comparator.nullsFirst(NodeRegistry::compareStableText)))
            .flatMap(payload -> orderedDefinitions(payload.getNodes()).stream()
                .filter(definition -> definition != null)
                .map(definition -> new UnresolvedDefinitionCandidate(definition, payload.getPluginId())))
            .sorted(Comparator.comparing(UnresolvedDefinitionCandidate::canonicalSortKey,
                NodeRegistry::compareStableText)
                .thenComparing(candidate -> candidate.definition().getId(),
                    Comparator.nullsFirst(NodeRegistry::compareStableText))
                .thenComparing(candidate -> candidate.pluginId(),
                    Comparator.nullsFirst(NodeRegistry::compareStableText)))
            .toList();
    }

    private record UnresolvedDefinitionCandidate(NodeDefinition definition, String pluginId) {
        private boolean matchesCanonical(String nodeId) {
            return canonicalIds().stream().anyMatch(value -> value.equalsIgnoreCase(nodeId));
        }

        private boolean matchesLocal(String nodeId) {
            return localIds().stream().anyMatch(value -> value.equals(nodeId));
        }

        private List<String> canonicalIds() {
            Set<String> identities = new HashSet<>();
            addCanonicalIdentity(identities, definition.getOwner(), definition.getId());
            addCanonicalIdentity(identities, definition.getOwner(), definition.getCanonicalId());
            addQualifiedIdentity(identities, definition.getOwner(), definition.getId());
            addQualifiedIdentity(identities, definition.getOwner(), definition.getCanonicalId());
            return identities.stream().sorted(NodeRegistry::compareStableText).toList();
        }

        private List<String> localIds() {
            Set<String> identities = new HashSet<>();
            addLocalIdentity(identities, definition.getOwner(), definition.getId());
            addLocalIdentity(identities, definition.getOwner(), definition.getCanonicalId());
            return identities.stream().sorted(NodeRegistry::compareStableText).toList();
        }

        private String canonicalSortKey() {
            return canonicalIds().stream().findFirst().orElse("");
        }

        private static void addCanonicalIdentity(Set<String> identities, String owner, String nodeId) {
            if (owner == null || owner.isBlank() || nodeId == null || nodeId.isBlank()) {
                return;
            }
            try {
                identities.add(new NodeReference(owner, nodeId).canonical());
            } catch (IllegalArgumentException ignored) {
            }
        }

        private static void addQualifiedIdentity(Set<String> identities, String owner, String nodeId) {
            if (owner == null || owner.isBlank() || nodeId == null || nodeId.isBlank()) {
                return;
            }
            int separator = nodeId.indexOf(':');
            if (separator <= 0 || separator == nodeId.length() - 1
                || nodeId.indexOf(':', separator + 1) >= 0) {
                return;
            }
            String qualifiedOwner = nodeId.substring(0, separator);
            if ("builtin".equalsIgnoreCase(owner) || owner.equalsIgnoreCase(qualifiedOwner)) {
                identities.add(nodeId);
            }
        }

        private static void addLocalIdentity(Set<String> identities, String owner, String nodeId) {
            if (nodeId == null || nodeId.isBlank()) {
                return;
            }
            try {
                String localId = new NodeReference(owner, nodeId).localId();
                if (localId.indexOf(':') < 0) {
                    identities.add(localId);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public synchronized NodeRegistrySnapshot materializeSnapshot(String serverId, NodeRegistrySnapshot source) {
        if (serverId == null || source == null) {
            return null;
        }
        String key = normalizeServerId(serverId);
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(source.getContractVersion());
        snapshot.setMinimumClientContractVersion(source.getMinimumClientContractVersion());
        snapshot.setServerIdentity(serverId);
        snapshot.setCompatibleUntil(source.getCompatibleUntil());
        snapshot.setCapabilities(source.getCapabilities());
        snapshot.setRegistryDiagnostics(source.getRegistryDiagnostics());
        snapshot.setFullSync(true);
        snapshot.setRegistryChecksum(source.getRegistryChecksum());
        snapshot.setGeneratedAt(source.getGeneratedAt());
        snapshot.setCatalogGeneration(source.getCatalogGeneration());
        snapshot.setCatalogChecksum(source.getCatalogChecksum());
        snapshot.setCatalogProjectionIdentity(source.getCatalogProjectionIdentity());
        snapshot.setDropContributions(new ArrayList<>(source.getDropContributions()));
        snapshot.setFunctionBoundaries(new ArrayList<>(source.getFunctionBoundaries()));
        snapshot.setCatalogMetadata(source.getCatalogMetadata());
        snapshot.setOpaqueData(!source.getOpaqueData().isEmpty() ? source.getOpaqueData() : getSnapshotData(serverId));
        snapshot.setNodeIds(serverNodeIds.getOrDefault(key, List.of()).stream()
            .sorted(NodeRegistry::compareStableText).toList());
        snapshot.setPlugins(serverPlugins.getOrDefault(key, Map.of()).values().stream()
            .filter(payload -> payload != null)
            .sorted((left, right) -> compareStableText(left.getPluginId(), right.getPluginId()))
            .map(NodeRegistry::orderedPayload).toList());
        snapshot.setRemovedPlugins(List.of());
        snapshot.setPropertyActions(serverPropertyActions.getOrDefault(key, Map.of()));
        snapshot.setPropertyOutputTypes(serverPropertyOutputTypes.getOrDefault(key, Map.of()));
        snapshot.setPropertyMetadata(new ArrayList<>(serverPropertyMetadata.getOrDefault(key, List.of())));
        snapshot.setResourceMetadata(new ArrayList<>(serverResourceMetadata.getOrDefault(key, List.of())));
        snapshot.setTypeMetadata(new ArrayList<>(serverTypeMetadata.getOrDefault(key, List.of())));
        snapshot.setCategoryMetadata(new ArrayList<>(serverCategoryMetadata.getOrDefault(key, List.of())));
        snapshot.setOptionSourceMetadata(new ArrayList<>(serverOptionSourceMetadata.getOrDefault(key, List.of())));
        snapshot.setConversionRules(new ArrayList<>(serverConversionRules.getOrDefault(key, List.of())));
        return snapshot;
    }

    public synchronized boolean canAssignTypes(String serverId, FlowTypeRef source, FlowTypeRef target) {
        if (source == null || target == null) {
            return false;
        }
        if (source.isTypeVariable() || target.isTypeVariable()) {
            return true;
        }
        FlowDataType sourceType = resolveType(serverId, source.getTypeId());
        FlowDataType targetType = resolveType(serverId, target.getTypeId());
        if (!targetType.isAssignableFrom(sourceType)) {
            return false;
        }
        if ("resource_reference".equals(target.getTypeId())) {
            return target.getArguments().isEmpty() || source.getArguments().size() == 1
                && target.getArguments().getFirst().getTypeId().equalsIgnoreCase(source.getArguments().getFirst().getTypeId());
        }
        if (target.getArguments().isEmpty()) {
            return true;
        }
        if (target.getArguments().size() != source.getArguments().size()) {
            return false;
        }
        for (int index = 0; index < target.getArguments().size(); index++) {
            if (!canAssignTypes(serverId, source.getArguments().get(index), target.getArguments().get(index))) {
                return false;
            }
        }
        return true;
    }

    public synchronized FlowDataType resolveType(String serverId, String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return FlowDataType.ANY;
        }
        String key = normalizeServerId(serverId);
        Map<String, FlowDataType> types = serverDataTypes.get(key);
        FlowDataType type = types != null ? types.get(typeId.toLowerCase()) : null;
        return type != null ? type : FlowDataType.fromString(typeId);
    }

    public synchronized List<String> getPropertyActions(String serverId, String family, String property) {
        String key = normalizeServerId(serverId);
        Map<String, Map<String, List<String>>> families = serverPropertyActions.get(key);
        if (families == null) {
            return List.of();
        }
        Map<String, List<String>> properties = families.get(family);
        if (properties == null) {
            return List.of();
        }
        List<String> actions = properties.get(property);
        return actions != null ? actions : List.of();
    }

    public synchronized FlowDataType getPropertyOutputType(String serverId, String family, String property) {
        String key = normalizeServerId(serverId);
        Map<String, Map<String, FlowDataType>> families = serverPropertyOutputTypes.get(key);
        if (families == null) {
            return FlowDataType.ANY;
        }
        Map<String, FlowDataType> properties = families.get(family);
        if (properties == null) {
            return FlowDataType.ANY;
        }
        FlowDataType type = properties.get(property);
        return type != null ? resolveType(key, type.getId()) : FlowDataType.ANY;
    }

    public synchronized FlowPropertyMetadata getPropertyMetadata(String serverId, String family, String property) {
        if (family == null || property == null) {
            return null;
        }
        List<FlowPropertyMetadata> metadata = serverPropertyMetadata.get(normalizeServerId(serverId));
        if (metadata == null) {
            return null;
        }
        return metadata.stream()
            .filter(value -> value != null && family.equalsIgnoreCase(value.getFamily()) && property.equalsIgnoreCase(value.getProperty()))
            .findFirst()
            .orElse(null);
    }

    public synchronized List<FlowResourceMetadata> getResourceMetadata(String serverId) {
        List<FlowResourceMetadata> metadata = serverResourceMetadata.get(normalizeServerId(serverId));
        return metadata != null ? List.copyOf(metadata) : List.of();
    }

    public synchronized FlowResourceMetadata getResourceMetadata(String serverId, String typeId) {
        if (typeId == null) {
            return null;
        }
        return getResourceMetadata(serverId).stream()
            .filter(value -> value != null && typeId.equalsIgnoreCase(value.getTypeId()))
            .findFirst()
            .orElse(null);
    }

    public synchronized void addListener(NodeRegistryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(NodeRegistryListener listener) {
        listeners.remove(listener);
    }

    private Map<String, NodeDefinition> buildServerDefinitions(String key, List<String> nodeIds,
                                                                Map<String, NodePluginPayload> plugins,
                                                                CanonicalCatalogAuthority catalogAuthority) {
        Set<String> nodeIdSet = new HashSet<>(nodeIds);
        boolean hasNodeList = !nodeIds.isEmpty();
        Map<String, NodeDefinition> definitions = new LinkedHashMap<>();

        List<NodeDefinition> candidates = new ArrayList<>();
        for (NodePluginPayload payload : plugins.values()) {
            if (payload == null || payload.getNodes() == null) {
                continue;
            }
            for (NodeDefinition def : payload.getNodes()) {
                if (def == null || def.getId() == null) {
                    continue;
                }
                if (catalogAuthority != null && !catalogAuthority.includes(def)) {
                    continue;
                }
                NodeReference reference = canonicalReference(def);
                if (reference != null && (!hasNodeList || nodeIdSet.contains(def.getId())
                    || nodeIdSet.contains(def.getCanonicalId())
                    || nodeIdSet.contains(reference.localId())
                    || nodeIdSet.contains(reference.canonical()))) {
                    candidates.add(def);
                }
            }
        }

        candidates.sort(DEFINITION_ORDER);
        for (NodeDefinition definition : candidates) {
            NodeReference reference = canonicalReference(definition);
            if (reference != null && definitions.putIfAbsent(reference.canonical(), definition) != null) {
                return null;
            }
        }

        if (hasNodeList) {
            for (String nodeId : nodeIds) {
                if (definitions.values().stream().noneMatch(definition -> matchesCanonicalReference(definition, nodeId)
                    || matchesLocalReference(definition, nodeId))) {
                    ReLog.logger(LogTypes.FLOW).source(LogSource.resource(nodeId, nodeId)).component(NodeRegistry.class).debug("Node definition is unavailable");
                }
            }
        }
        return definitions;
    }

    private void rebuildServerDefinitions(String key) {
        List<String> nodeIds = serverNodeIds.getOrDefault(key, List.of());
        Map<String, NodePluginPayload> plugins = serverPlugins.getOrDefault(key, Map.of());
        Map<String, NodeDefinition> definitions = buildServerDefinitions(key, nodeIds, plugins, serverCanonicalCatalogs.get(key));
        if (definitions != null) {
            serverDefinitions.put(key, new HashMap<>(definitions));
            refreshSelectorCatalogSnapshot(key);
        }
    }

    private void refreshSelectorCatalogSnapshot(String key) {
        if (key == null) {
            return;
        }
        Map<String, NodeDefinition> definitions = serverDefinitions.get(key);
        Map<String, FlowDataType> dataTypes = serverDataTypes.get(key);
        List<FlowCategoryMetadata> categoryMetadata = serverCategoryMetadata.get(key);
        List<FlowConversionRule> conversionRules = serverConversionRules.get(key);
        SnapshotAuthority authority = serverSnapshotAuthorities.get(key);
        if (definitions == null && dataTypes == null && categoryMetadata == null && conversionRules == null && authority == null) {
            selectorCatalogSnapshots.remove(key);
            return;
        }
        long revision = selectorCatalogRevisions.merge(key, 1L, Long::sum);
        selectorCatalogSnapshots.put(key, new SelectorCatalogSnapshot(key, revision,
            definitions != null ? definitions : Map.of(),
            dataTypes != null ? new LinkedHashMap<>(dataTypes) : Map.of(),
            categoryMetadata != null ? new ArrayList<>(categoryMetadata) : List.of(),
            conversionRules != null ? new ArrayList<>(conversionRules) : List.of(), authority));
    }

    private boolean notifyListeners(String serverId) {
        boolean successful = true;
        for (NodeRegistryListener listener : listeners) {
            try {
                listener.onRegistryUpdated(serverId);
            } catch (RuntimeException | Error exception) {
                successful = false;
                try {
                    ReLog.logger(LogTypes.FLOW).source(LogSource.server(serverId, serverId)).component(NodeRegistry.class)
                        .with("reason", exception.getMessage()).warn("Node registry listener failed");
                } catch (RuntimeException | Error ignored) {
                }
            }
        }
        return successful;
    }

    private Map<String, FlowDataType> buildServerDataTypes(String serverId, List<FlowTypeMetadata> metadata) {
        Map<String, FlowTypeMetadata> descriptors = new LinkedHashMap<>();
        for (FlowTypeMetadata descriptor : metadata) {
            if (descriptor != null && descriptor.getId() != null && !descriptor.getId().isBlank()) {
                descriptors.put(descriptor.getId().toLowerCase(), descriptor);
            }
        }
        Map<String, FlowDataType> types = new LinkedHashMap<>();
        for (String typeId : descriptors.keySet()) {
            resolveServerType(serverId, typeId, descriptors, types, new HashSet<>());
        }
        return Map.copyOf(types);
    }

    private FlowDataType resolveServerType(String serverId, String typeId, Map<String, FlowTypeMetadata> descriptors,
                                           Map<String, FlowDataType> types, Set<String> resolving) {
        String normalized = typeId.toLowerCase();
        FlowDataType resolved = types.get(normalized);
        if (resolved != null) {
            return resolved;
        }
        FlowDataType builtin = FlowDataType.fromString(normalized);
        if (builtin.isResolved() && "builtin".equals(builtin.getOwner())) {
            types.put(normalized, builtin);
            return builtin;
        }
        FlowTypeMetadata metadata = descriptors.get(normalized);
        if (metadata == null || !resolving.add(normalized)) {
            return builtin;
        }
        if (!metadata.isAvailable()) {
            types.put(normalized, builtin);
            resolving.remove(normalized);
            return builtin;
        }
        FlowDataType parent = null;
        if (metadata.getParentId() != null && !metadata.getParentId().isBlank()) {
            parent = resolveServerType(serverId, metadata.getParentId(), descriptors, types, resolving);
        }
        FlowDataType type = FlowDataType.serverType(metadata.getId(), metadata.getDisplayName(), metadata.getColor(), parent,
            metadata.isCanStringify(), metadata.getOwner() != null && !metadata.getOwner().isBlank() ? metadata.getOwner() : serverId);
        resolving.remove(normalized);
        types.put(normalized, type);
        return type;
    }

    private String normalizeServerId(String serverId) {
        return serverId != null ? serverId : DEFAULT_SERVER_KEY;
    }
}
