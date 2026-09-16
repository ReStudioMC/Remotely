package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowSerializer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

final class TypedGraphCache {
    private static final List<ReSyncResourceType> GRAPH_TYPES = List.of(ReSyncResourceType.FUNCTION, ReSyncResourceType.FLOW, ReSyncResourceType.COMMAND);
    private final Map<ReSyncResourceType, SyncedResourceCache<FlowGraph>> stores = new EnumMap<>(ReSyncResourceType.class);

    record GraphKey(ReSyncResourceType type, String id) {
    }

    TypedGraphCache() {
        for (ReSyncResourceType type : GRAPH_TYPES) {
            stores.put(type, new SyncedResourceCache<>(FlowGraph::getId, FlowGraph::getId));
        }
    }

    void clearForServer(String serverId) {
        stores.values().forEach(store -> store.clearForServer(serverId));
    }

    void clearAll() {
        stores.values().forEach(store -> store.clearAll());
    }

    Set<String> serverIds() {
        TreeSet<String> result = new TreeSet<>();
        stores.values().forEach(store -> result.addAll(store.serverIds()));
        return Collections.unmodifiableSet(result);
    }

    void clearAuthoritativeForServer(String serverId) {
        stores.values().forEach(store -> store.clearAuthoritativeForServer(serverId));
    }

    FlowGraph get(String serverId, String resourceId) {
        FlowGraph found = null;
        for (ReSyncResourceType type : GRAPH_TYPES) {
            FlowGraph graph = get(serverId, type, resourceId);
            if (graph == null) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = graph;
        }
        return found;
    }

    FlowGraph get(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.get(serverId, resourceId) : null;
    }

    FlowGraph getOwned(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getOwned(serverId, resourceId) : null;
    }

    SyncedResourceCache.SnapshotLease<FlowGraph> snapshotLease(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.snapshotLease(serverId, resourceId) : null;
    }

    SyncedResourceCache.SnapshotLease<FlowGraph> snapshotAuthoritativeLease(String serverId, ReSyncResourceType type,
                                                                              String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.snapshotAuthoritativeLease(serverId, resourceId) : null;
    }

    SyncedResourceCache.SaveLease<FlowGraph> getDraftLease(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getDraftLease(serverId, resourceId) : null;
    }

    SyncedResourceCache.SaveLease<FlowGraph> getDraftLeaseIfGeneration(
        String serverId, ReSyncResourceType type, String resourceId, long expectedGeneration,
        Predicate<? super FlowGraph> expectedPayload) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getDraftLeaseIfGeneration(serverId, resourceId, expectedGeneration,
            expectedPayload) : null;
    }

    FlowGraph getFromDraft(String serverId, String resourceId) {
        FlowGraph found = null;
        for (ReSyncResourceType type : GRAPH_TYPES) {
            FlowGraph graph = getFromDraft(serverId, type, resourceId);
            if (graph == null) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = graph;
        }
        return found;
    }

    FlowGraph getFromDraft(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getFromDraft(serverId, resourceId) : null;
    }

    SyncedResourceCache.SaveLease<FlowGraph> putInDraft(String serverId, FlowGraph graph) {
        return store(graph).putInDraft(serverId, graph);
    }

    SyncedResourceCache.SaveLease<FlowGraph> putInDraftIfGenerationLease(
        String serverId, ReSyncResourceType type, String resourceId, FlowGraph graph, long expectedGeneration,
        SyncedResourceCache.EntrySnapshot<FlowGraph> expected) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store == null || type == null || !type.isGraph() || serverId == null || serverId.isBlank()
            || graph == null || resourceId == null || !resourceId.equals(graph.getId())
            || expected == null || expected.generation() != expectedGeneration
            || !serverId.equals(expected.serverId()) || !resourceId.equals(expected.resourceId())) {
            return null;
        }
        synchronized (store) {
            SyncedResourceCache.EntrySnapshot<FlowGraph> current = store.snapshot(serverId, resourceId);
            if (!sameSnapshot(current, expected) || current.draftPresent()) {
                return null;
            }
            return store.putInDraftIfGenerationLease(serverId, graph, expectedGeneration);
        }
    }

    SyncedResourceCache.SaveLease<FlowGraph> putInDraftIfAuthoritativeIfGenerationLease(
        String serverId, ReSyncResourceType type, String resourceId, FlowGraph graph, long expectedGeneration,
        Predicate<? super FlowGraph> expectedAuthoritative) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store == null || type == null || !type.isGraph() || serverId == null || serverId.isBlank()
            || graph == null || resourceId == null || !resourceId.equals(graph.getId())) {
            return null;
        }
        return store.putInDraftIfAuthoritativeIfGenerationLease(serverId, graph, expectedGeneration,
            expectedAuthoritative);
    }

    void cache(String serverId, FlowGraph graph) {
        store(graph).cache(serverId, graph);
    }

    Long cacheIfGeneration(String serverId, ReSyncResourceType type, FlowGraph graph, long expectedGeneration) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.cacheIfGeneration(serverId, graph, expectedGeneration) : null;
    }

    Long updateIfGeneration(String serverId, ReSyncResourceType type, String resourceId, long expectedGeneration,
                            UnaryOperator<FlowGraph> update) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.updateIfGeneration(serverId, resourceId, expectedGeneration, update) : null;
    }

    boolean update(String serverId, ReSyncResourceType type, String resourceId, UnaryOperator<FlowGraph> update) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null && store.update(serverId, resourceId, update);
    }

    SyncedResourceCache.EntrySnapshot<FlowGraph> snapshot(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.snapshot(serverId, resourceId) : null;
    }

    long currentGeneration(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.currentGeneration(serverId, resourceId) : 0L;
    }

    boolean matchesDraftFence(String serverId, ReSyncResourceType type, String resourceId,
                              SyncedResourceCache.SaveLease<?> expectedLease, long expectedGeneration) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null && store.matchesDraftFence(serverId, resourceId, expectedLease, expectedGeneration);
    }

    boolean matchesDraftFence(String serverId, ReSyncResourceType type, String resourceId,
                              SyncedResourceCache.SaveLease<?> expectedLease, long expectedGeneration,
                              Predicate<? super FlowGraph> expectedPayload) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null && store.matchesDraftFence(serverId, resourceId, expectedLease, expectedGeneration,
            expectedPayload);
    }

    SyncedResourceCache.SaveSettlement publishAuthoritativeIfCurrent(
        String serverId, ReSyncResourceType type, String resourceId, FlowGraph authoritativeValue,
        SyncedResourceCache.SaveLease<?> expectedLease, long expectedGeneration,
        Predicate<? super FlowGraph> expectedPayload) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.publishAuthoritativeIfCurrent(serverId, resourceId, authoritativeValue,
            expectedLease, expectedGeneration, expectedPayload)
            : new SyncedResourceCache.SaveSettlement(false, false, 0L);
    }

    void restore(String serverId, ReSyncResourceType type, String resourceId,
                 SyncedResourceCache.EntrySnapshot<FlowGraph> snapshot) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store != null && snapshot != null) {
            store.restore(snapshot);
        }
    }

    boolean restoreIfGeneration(String serverId, ReSyncResourceType type, String resourceId,
                                SyncedResourceCache.EntrySnapshot<FlowGraph> snapshot, long generation) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null && snapshot != null && store.restoreIfGeneration(snapshot, generation);
    }

    Long removeIfGeneration(String serverId, ReSyncResourceType type, String resourceId, long expectedGeneration) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.removeIfGeneration(serverId, resourceId, expectedGeneration) : null;
    }

    Long markSavedIfGeneration(String serverId, ReSyncResourceType type, String resourceId, long expectedGeneration) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.markSavedIfGeneration(serverId, resourceId, expectedGeneration) : null;
    }

    Long compareAndMarkSavedIfGeneration(String serverId, ReSyncResourceType type, String resourceId, long version,
                                         long expectedGeneration) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.compareAndMarkSavedIfGeneration(serverId, resourceId, version, expectedGeneration) : null;
    }

    void replaceFromServer(String serverId, FlowGraph graph) {
        store(graph).replaceFromServer(serverId, graph);
    }

    void discardDraft(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store != null) {
            store.discardDraft(serverId, resourceId);
        }
    }

    void markSaving(String serverId, ReSyncResourceType type, String resourceId) {
        store(type).markSaving(serverId, resourceId);
    }

    void markSaving(String serverId, String resourceId) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        if (store != null) {
            store.markSaving(serverId, resourceId);
        }
    }

    void markSaved(String serverId, ReSyncResourceType type, String resourceId) {
        store(type).markSaved(serverId, resourceId);
    }

    void markSaved(String serverId, String resourceId) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        if (store != null) {
            store.markSaved(serverId, resourceId);
        }
    }

    void markFailed(String serverId, ReSyncResourceType type, String resourceId) {
        store(type).markFailed(serverId, resourceId);
    }

    void markFailed(String serverId, String resourceId) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        if (store != null) {
            store.markFailed(serverId, resourceId);
        }
    }

    SyncedResourceState getState(String serverId, String resourceId) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        return store != null ? store.getState(serverId, resourceId) : SyncedResourceState.CLEAN;
    }

    boolean containsServerId(String serverId, String resourceId) {
        return GRAPH_TYPES.stream().anyMatch(type -> store(type).containsServerId(serverId, resourceId));
    }

    boolean containsServerId(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null && store.containsServerId(serverId, resourceId);
    }

    boolean containsKey(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null && store.containsKey(serverId, resourceId);
    }

    void putNameIfAbsent(String serverId, String resourceId, String name) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        if (store != null) {
            store.putNameIfAbsent(serverId, resourceId, name);
        }
    }

    void putNameIfAbsent(String serverId, ReSyncResourceType type, String resourceId, String name) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store != null) {
            store.putNameIfAbsent(serverId, resourceId, name);
        }
    }

    SyncedResourceState getState(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getState(serverId, resourceId) : SyncedResourceState.CLEAN;
    }

    void putName(String serverId, String resourceId, String name) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        if (store != null) {
            store.putName(serverId, resourceId, name);
        }
    }

    void putName(String serverId, ReSyncResourceType type, String resourceId, String name) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store != null) {
            store.putName(serverId, resourceId, name);
        }
    }

    String getName(String serverId, String resourceId) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        return store != null ? store.getName(serverId, resourceId) : resourceId;
    }

    String getName(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getName(serverId, resourceId) : resourceId;
    }

    String resolveDisplayName(String serverId, ReSyncResourceType type, String oldId, String newId) {
        return store(type).resolveDisplayName(serverId, oldId, newId);
    }

    void remove(String serverId, ReSyncResourceType type, String resourceId) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store != null) {
            store.remove(serverId, resourceId);
        }
    }

    void remove(String serverId, String resourceId) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, resourceId);
        if (store != null) {
            store.remove(serverId, resourceId);
        }
    }

    boolean rename(String serverId, ReSyncResourceType type, String oldId, String newId, BiConsumer<FlowGraph, String> rename) {
        return store(type).rename(serverId, oldId, newId, rename);
    }

    boolean rename(String serverId, String oldId, String newId, BiConsumer<FlowGraph, String> rename) {
        SyncedResourceCache<FlowGraph> store = uniqueStore(serverId, oldId);
        return store != null && store.rename(serverId, oldId, newId, rename);
    }

    Map<String, FlowGraph> getForServer(String serverId) {
        Map<String, FlowGraph> graphs = new LinkedHashMap<>();
        for (ReSyncResourceType type : GRAPH_TYPES) {
            store(type).getForServer(serverId).forEach((id, graph) -> {
                if (graphs.putIfAbsent(id, graph) != null) {
                    throw new IllegalStateException("Ambiguous graph identity for " + id);
                }
            });
        }
        return graphs;
    }

    Map<GraphKey, FlowGraph> getTypedForServer(String serverId) {
        Map<GraphKey, FlowGraph> graphs = new LinkedHashMap<>();
        for (ReSyncResourceType type : GRAPH_TYPES) {
            store(type).getForServer(serverId).forEach((id, graph) -> graphs.put(new GraphKey(type, id), graph));
        }
        return graphs;
    }

    Map<String, FlowGraph> getForServer(String serverId, ReSyncResourceType type) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getForServer(serverId) : Map.of();
    }

    List<FlowGraph> valuesForServer(String serverId) {
        return GRAPH_TYPES.stream().flatMap(type -> store(type).getForServer(serverId).values().stream()).toList();
    }

    boolean hasLoadedServerList(String serverId) {
        return GRAPH_TYPES.stream().anyMatch(type -> store(type).hasLoadedServerList(serverId));
    }

    boolean hasLoadedServerList(String serverId, ReSyncResourceType type) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null && store.hasLoadedServerList(serverId);
    }

    List<String> getResourceIds(String serverId, ReSyncResourceType type) {
        SyncedResourceCache<FlowGraph> store = store(type);
        return store != null ? store.getResourceIds(serverId) : List.of();
    }

    void applyServerList(String serverId, ReSyncResourceType type, List<String> ids) {
        SyncedResourceCache<FlowGraph> store = store(type);
        if (store != null) {
            store.applyServerList(serverId, ids);
        }
    }

    private SyncedResourceCache<FlowGraph> uniqueStore(String serverId, String resourceId) {
        SyncedResourceCache<FlowGraph> found = null;
        for (ReSyncResourceType type : GRAPH_TYPES) {
            SyncedResourceCache<FlowGraph> store = store(type);
            if (!store.containsKey(serverId, resourceId)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = store;
        }
        return found;
    }

    private SyncedResourceCache<FlowGraph> store(FlowGraph graph) {
        ReSyncResourceType type = graph != null ? ReSyncResourceType.byTypeId(graph.getResourceType()) : null;
        if (type == null || !type.isGraph()) {
            type = graph != null && graph.isFunction() ? ReSyncResourceType.FUNCTION : ReSyncResourceType.FLOW;
            if (graph != null) {
                graph.setResourceType(type.typeId());
            }
        }
        return store(type);
    }

    private SyncedResourceCache<FlowGraph> store(ReSyncResourceType type) {
        return type != null ? stores.get(type) : null;
    }

    private boolean sameSnapshot(SyncedResourceCache.EntrySnapshot<FlowGraph> first,
                                 SyncedResourceCache.EntrySnapshot<FlowGraph> second) {
        if (first == null || second == null || first.generation() != second.generation()
            || first.cachePresent() != second.cachePresent() || first.draftPresent() != second.draftPresent()
            || first.draftLease() != second.draftLease() || first.namePresent() != second.namePresent()
            || !Objects.equals(first.nameValue(), second.nameValue()) || first.statePresent() != second.statePresent()
            || first.stateValue() != second.stateValue() || first.serverIdPresent() != second.serverIdPresent()
            || first.pendingParentPresent() != second.pendingParentPresent()
            || first.pendingParent() != second.pendingParent()) {
            return false;
        }
        if (first.cachePresent() != (first.cacheValue() != null) || second.cachePresent() != (second.cacheValue() != null)) {
            return false;
        }
        if (first.cacheValue() == null || second.cacheValue() == null) {
            return first.cacheValue() == second.cacheValue();
        }
        try {
            return FlowSerializer.toSnapshotJsonObject(first.cacheValue()).toString()
                .equals(FlowSerializer.toSnapshotJsonObject(second.cacheValue()).toString());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
