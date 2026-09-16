package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class SyncedResourceCache<T> {

    private static final Gson COPY_GSON = new Gson();

    public record SaveSettlement(boolean accepted, boolean currentAtFinish, long generation) {
    }

    public record EntrySnapshot<T>(String serverId, String resourceId,
                                   boolean cachePresent, T cacheValue,
                                   boolean draftPresent, SaveLease<T> draftLease,
                                   boolean namePresent, String nameValue,
                                   boolean statePresent, SyncedResourceState stateValue,
                                   boolean serverIdPresent,
                                   boolean pendingParentPresent, Object pendingParent,
                                   long generation) {
        public EntrySnapshot(String serverId, String resourceId,
                             boolean cachePresent, T cacheValue,
                             boolean draftPresent, SaveLease<T> draftLease,
                             boolean namePresent, String nameValue,
                             boolean statePresent, SyncedResourceState stateValue,
                             boolean serverIdPresent,
                             boolean pendingParentPresent, Object pendingParent) {
            this(serverId, resourceId, cachePresent, cacheValue, draftPresent, draftLease,
                namePresent, nameValue, statePresent, stateValue, serverIdPresent,
                pendingParentPresent, pendingParent, 0L);
        }
    }

    public static final class SaveLease<T> {
        private final SyncedResourceCache<T> owner;
        private final String serverId;
        private final String resourceId;
        private final long version;
        private final T value;

        private SaveLease(SyncedResourceCache<T> owner, String serverId, String resourceId, long version, T value) {
            this.owner = owner;
            this.serverId = serverId;
            this.resourceId = resourceId;
            this.version = version;
            this.value = value;
        }

        public String serverId() {
            return serverId;
        }

        public String resourceId() {
            return resourceId;
        }

        public long version() {
            return version;
        }

        public boolean isCurrent() {
            return owner.isCurrent(this);
        }

        public boolean discardIfCurrent() {
            return owner.discardDraftIfCurrent(this);
        }

        public boolean updateName(String name, boolean replace) {
            return owner.updateName(this, name, replace);
        }

        public boolean markSaving() {
            return owner.markSaving(this);
        }

        public boolean markFailed() {
            return owner.markFailed(this);
        }

        public String serialize(Function<? super T, String> serializer) {
            return serializer.apply(value);
        }

    }

    public static final class SnapshotLease<T> {
        private final SyncedResourceCache<T> owner;
        private final String serverId;
        private final String resourceId;
        private final long version;
        private final T value;
        private final boolean authoritative;

        private SnapshotLease(SyncedResourceCache<T> owner, String serverId, String resourceId, long version, T value) {
            this(owner, serverId, resourceId, version, value, false);
        }

        private SnapshotLease(SyncedResourceCache<T> owner, String serverId, String resourceId, long version, T value,
                              boolean authoritative) {
            this.owner = owner;
            this.serverId = serverId;
            this.resourceId = resourceId;
            this.version = version;
            this.value = value;
            this.authoritative = authoritative;
        }

        public String serverId() {
            return serverId;
        }

        public String resourceId() {
            return resourceId;
        }

        public long version() {
            return version;
        }

        public boolean isCurrent() {
            return owner.isCurrent(this);
        }

        public SaveLease<T> compareAndPutInDraft(T nextValue) {
            return owner.compareAndPutInDraft(this, nextValue);
        }

        public String serialize(Function<? super T, String> serializer) {
            return serializer.apply(value);
        }

        <R> R materialize(Function<? super T, R> materializer) {
            return materializer.apply(value);
        }
    }

    private final Map<String, T> cache = BrowserSafeState.map();
    private final Map<String, SaveLease<T>> drafts = BrowserSafeState.map();
    private final Map<String, String> names = BrowserSafeState.map();
    private final Map<String, SyncedResourceState> states = BrowserSafeState.map();
    private final Set<String> serverIds = BrowserSafeState.set();
    private final Set<String> cacheServerIds = BrowserSafeState.set();
    private final Set<String> loadedServerLists = BrowserSafeState.set();
    private final Map<String, Object> pendingParents = BrowserSafeState.map();
    private final Map<String, Long> generations = BrowserSafeState.map();
    private final Function<T, String> idExtractor;
    private final Function<T, String> defaultNameExtractor;
    private final Function<T, T> copyFunction;
    private long draftVersion;
    private long snapshotVersion;
    private long generation;

    public SyncedResourceCache(Function<T, String> idExtractor, Function<T, String> defaultNameExtractor) {
        this(idExtractor, defaultNameExtractor, SyncedResourceCache::copyValue);
    }

    public SyncedResourceCache(Function<T, String> idExtractor, Function<T, String> defaultNameExtractor,
                               Function<T, T> copyFunction) {
        this.idExtractor = idExtractor;
        this.defaultNameExtractor = defaultNameExtractor;
        this.copyFunction = copyFunction;
    }

    public String key(String serverId, String resourceId) {
        return serverId + ":" + resourceId;
    }

    public String stripPrefix(String key, String serverId) {
        String prefix = serverId + ":";
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    public synchronized void clearForServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        rememberServer(serverId);
        String prefix = serverId + ":";
        touchKeys(prefix);
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        drafts.keySet().removeIf(k -> k.startsWith(prefix));
        serverIds.removeIf(k -> k.startsWith(prefix));
        names.keySet().removeIf(k -> k.startsWith(prefix));
        states.keySet().removeIf(k -> k.startsWith(prefix));
        pendingParents.keySet().removeIf(k -> k.startsWith(prefix));
        generations.keySet().removeIf(k -> k.startsWith(prefix));
        loadedServerLists.remove(serverId);
        cacheServerIds.remove(serverId);
    }

    public synchronized void clearAll() {
        touchKeys();
        cache.clear();
        drafts.clear();
        serverIds.clear();
        cacheServerIds.clear();
        loadedServerLists.clear();
        names.clear();
        states.clear();
        pendingParents.clear();
        generations.clear();
    }

    public synchronized Set<String> serverIds() {
        TreeSet<String> result = new TreeSet<>();
        result.addAll(cacheServerIds);
        return Collections.unmodifiableSet(result);
    }

    public synchronized void clearAuthoritativeForServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        rememberServer(serverId);
        String prefix = serverId + ":";
        touchKeys(prefix);
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        serverIds.removeIf(k -> k.startsWith(prefix));
        names.keySet().removeIf(k -> k.startsWith(prefix) && !drafts.containsKey(k));
        states.keySet().removeIf(k -> k.startsWith(prefix) && !drafts.containsKey(k));
        generations.keySet().removeIf(k -> k.startsWith(prefix));
        loadedServerLists.remove(serverId);
        if (!hasServerData(serverId)) {
            cacheServerIds.remove(serverId);
        }
    }

    public synchronized Map<String, T> getForServer(String serverId) {
        Map<String, T> result = new LinkedHashMap<>();
        String prefix = serverId + ":";
        for (var entry : cache.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(stripPrefix(entry.getKey(), serverId), entry.getValue());
            }
        }
        for (var entry : drafts.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(stripPrefix(entry.getKey(), serverId), entry.getValue().value);
            }
        }
        return new LazyCopyMap(result);
    }

    public synchronized T get(String serverId, String resourceId) {
        return copy(currentValue(serverId, resourceId));
    }

    public synchronized T getOwned(String serverId, String resourceId) {
        return get(serverId, resourceId);
    }

    public synchronized T getFromCache(String serverId, String resourceId) {
        return copy(cache.get(key(serverId, resourceId)));
    }

    public synchronized T getFromDraft(String serverId, String resourceId) {
        SaveLease<T> lease = drafts.get(key(serverId, resourceId));
        return lease != null ? copy(lease.value) : null;
    }

    public synchronized SaveLease<T> getDraftLease(String serverId, String resourceId) {
        return drafts.get(key(serverId, resourceId));
    }

    public synchronized SaveLease<T> getDraftLeaseIfGeneration(String serverId, String resourceId,
                                                                long expectedGeneration,
                                                                Predicate<? super T> expectedPayload) {
        if (serverId == null || serverId.isBlank() || resourceId == null || resourceId.isBlank()
            || expectedGeneration < 0L) {
            return null;
        }
        String k = key(serverId, resourceId);
        if (currentGeneration(k) != expectedGeneration) {
            return null;
        }
        SaveLease<T> lease = drafts.get(k);
        if (lease == null || expectedPayload == null) {
            return lease;
        }
        try {
            return expectedPayload.test(copy(lease.value)) ? lease : null;
        } catch (RuntimeException | Error exception) {
            return null;
        }
    }

    public synchronized SnapshotLease<T> snapshotLease(String serverId, String resourceId) {
        T value = currentValue(serverId, resourceId);
        return value != null ? new SnapshotLease<>(this, serverId, resourceId, ++snapshotVersion, value) : null;
    }

    public synchronized SnapshotLease<T> snapshotAuthoritativeLease(String serverId, String resourceId) {
        T value = cache.get(key(serverId, resourceId));
        return value != null ? new SnapshotLease<>(this, serverId, resourceId, ++snapshotVersion, value, true) : null;
    }

    private synchronized SaveLease<T> compareAndPutInDraft(SnapshotLease<T> expected, T value) {
        if (expected == null || expected.owner != this || value == null || !isCurrent(expected)
            || !Objects.equals(expected.resourceId, idExtractor.apply(value))) {
            return null;
        }
        rememberServer(expected.serverId);
        T owned = copy(value);
        SaveLease<T> lease = newLease(expected.serverId, expected.resourceId, owned);
        drafts.put(key(expected.serverId, expected.resourceId), lease);
        states.put(key(expected.serverId, expected.resourceId), SyncedResourceState.DIRTY);
        touch(key(expected.serverId, expected.resourceId));
        return lease;
    }

    public synchronized EntrySnapshot<T> snapshot(String serverId, String resourceId) {
        String k = key(serverId, resourceId);
        return new EntrySnapshot<>(serverId, resourceId,
            cache.containsKey(k), copy(cache.get(k)),
            drafts.containsKey(k), drafts.get(k),
            names.containsKey(k), names.get(k),
            states.containsKey(k), states.get(k),
            serverIds.contains(k), pendingParents.containsKey(k), pendingParents.get(k), currentGeneration(k));
    }

    public synchronized void restore(EntrySnapshot<T> snapshot) {
        if (snapshot == null) {
            return;
        }
        rememberServer(snapshot.serverId());
        String k = key(snapshot.serverId(), snapshot.resourceId());
        touch(k);
        restoreInternal(snapshot, k);
    }

    public synchronized boolean restoreIfGeneration(EntrySnapshot<T> snapshot, long expectedGeneration) {
        if (snapshot == null || snapshot.serverId() == null || snapshot.resourceId() == null) {
            return false;
        }
        String k = key(snapshot.serverId(), snapshot.resourceId());
        if (currentGeneration(k) != expectedGeneration) {
            return false;
        }
        rememberServer(snapshot.serverId());
        touch(k);
        restoreInternal(snapshot, k);
        return true;
    }

    public synchronized boolean restoreIfCurrent(EntrySnapshot<T> snapshot, EntrySnapshot<T> expected) {
        if (snapshot == null || expected == null
            || !Objects.equals(snapshot.serverId(), expected.serverId())
            || !Objects.equals(snapshot.resourceId(), expected.resourceId())) {
            return false;
        }
        return restoreIfGeneration(snapshot, expected.generation());
    }

    public synchronized Long cacheIfGeneration(String serverId, T item, long expectedGeneration) {
        return cacheInternal(serverId, item, -1L, null, expectedGeneration, false);
    }

    public synchronized Long cacheAndRebaseIfGeneration(String serverId, T item, long draftVersion,
                                                         BiFunction<? super T, ? super T, ? extends T> rebase,
                                                         long expectedGeneration) {
        return cacheInternal(serverId, item, draftVersion, rebase, expectedGeneration, true);
    }

    public synchronized boolean matchesDraftFence(String serverId, String resourceId, SaveLease<?> expectedLease,
                                                   long expectedGeneration) {
        if (serverId == null || serverId.isBlank() || resourceId == null || resourceId.isBlank()
            || expectedGeneration < 0L) {
            return false;
        }
        String k = key(serverId, resourceId);
        return currentGeneration(k) == expectedGeneration && drafts.get(k) == expectedLease;
    }

    public synchronized boolean matchesDraftFence(String serverId, String resourceId, SaveLease<?> expectedLease,
                                                   long expectedGeneration, Predicate<? super T> expectedPayload) {
        if (!matchesDraftFence(serverId, resourceId, expectedLease, expectedGeneration)) {
            return false;
        }
        if (expectedLease == null || expectedPayload == null) {
            return true;
        }
        try {
            @SuppressWarnings("unchecked")
            SaveLease<T> current = (SaveLease<T>) expectedLease;
            return expectedPayload.test(copy(current.value));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public synchronized SaveSettlement publishAuthoritativeIfCurrent(
        String serverId, String resourceId, T authoritativeValue, SaveLease<?> expectedLease,
        long expectedGeneration, Predicate<? super T> expectedPayload) {
        if (serverId == null || serverId.isBlank() || resourceId == null || resourceId.isBlank()
            || expectedGeneration < 0L) {
            return new SaveSettlement(false, false, 0L);
        }
        String k = key(serverId, resourceId);
        SaveLease<T> currentDraft = drafts.get(k);
        boolean currentAtFinish = currentGeneration(k) == expectedGeneration
            && (expectedLease == null ? currentDraft == null : currentDraft == expectedLease);
        if (currentAtFinish && expectedLease != null && expectedPayload != null) {
            try {
                currentAtFinish = expectedPayload.test(copy(currentDraft.value));
            } catch (RuntimeException exception) {
                currentAtFinish = false;
            }
        }
        T authoritative = authoritativeValue != null ? copy(authoritativeValue)
            : currentAtFinish && currentDraft != null ? copy(currentDraft.value) : copy(cache.get(k));
        try {
            if (authoritative == null || !Objects.equals(resourceId, idExtractor.apply(authoritative))) {
                return new SaveSettlement(false, currentAtFinish, currentGeneration(k));
            }
        } catch (RuntimeException exception) {
            return new SaveSettlement(false, currentAtFinish, currentGeneration(k));
        }
        rememberServer(serverId);
        cache.put(k, authoritative);
        names.putIfAbsent(k, defaultNameExtractor.apply(authoritative));
        serverIds.add(k);
        if (currentAtFinish && currentDraft != null) {
            drafts.remove(k, currentDraft);
        }
        states.put(k, drafts.containsKey(k) ? SyncedResourceState.DIRTY : SyncedResourceState.SAVED);
        touch(k);
        return new SaveSettlement(true, currentAtFinish, currentGeneration(k));
    }

    private void restoreInternal(EntrySnapshot<T> snapshot, String k) {
        if (snapshot.cachePresent()) {
            cache.put(k, copy(snapshot.cacheValue()));
        } else {
            cache.remove(k);
        }
        if (snapshot.draftPresent()) {
            drafts.put(k, snapshot.draftLease());
        } else {
            drafts.remove(k);
        }
        if (snapshot.namePresent()) {
            names.put(k, snapshot.nameValue());
        } else {
            names.remove(k);
        }
        if (snapshot.statePresent()) {
            states.put(k, snapshot.stateValue());
        } else {
            states.remove(k);
        }
        if (snapshot.serverIdPresent()) {
            serverIds.add(k);
        } else {
            serverIds.remove(k);
        }
        if (snapshot.pendingParentPresent()) {
            pendingParents.put(k, snapshot.pendingParent());
        } else {
            pendingParents.remove(k);
        }
        forgetServerIfEmpty(snapshot.serverId());
    }

    public synchronized void putInCache(String serverId, T item) {
        rememberServer(serverId);
        T copy = copy(item);
        String k = key(serverId, idExtractor.apply(copy));
        cache.put(k, copy);
        touch(k);
    }

    public synchronized SaveLease<T> putInDraft(String serverId, T item) {
        return putInDraft(serverId, item, value -> {});
    }

    public synchronized SaveLease<T> putInDraft(String serverId, T item, Consumer<T> prepare) {
        rememberServer(serverId);
        T copy = copy(item);
        prepare.accept(copy);
        String resourceId = idExtractor.apply(copy);
        String k = key(serverId, resourceId);
        SaveLease<T> lease = newLease(serverId, resourceId, copy);
        drafts.put(k, lease);
        states.put(k, SyncedResourceState.DIRTY);
        touch(k);
        return lease;
    }

    public synchronized Long putInDraftIfGeneration(String serverId, T item, long expectedGeneration) {
        SaveLease<T> lease = putInDraftIfGenerationLease(serverId, item, value -> {}, expectedGeneration);
        return lease != null ? currentGeneration(serverId, lease.resourceId()) : null;
    }

    public synchronized Long putInDraftIfGeneration(String serverId, T item, Consumer<T> prepare,
                                                     long expectedGeneration) {
        SaveLease<T> lease = putInDraftIfGenerationLease(serverId, item, prepare, expectedGeneration);
        return lease != null ? currentGeneration(serverId, lease.resourceId()) : null;
    }

    public synchronized SaveLease<T> putInDraftIfGenerationLease(String serverId, T item,
                                                                  long expectedGeneration) {
        return putInDraftIfGenerationLease(serverId, item, value -> {}, expectedGeneration);
    }

    public synchronized SaveLease<T> putInDraftIfGenerationLease(String serverId, T item, Consumer<T> prepare,
                                                                  long expectedGeneration) {
        if (item == null) {
            return null;
        }
        rememberServer(serverId);
        T copy = copy(item);
        prepare.accept(copy);
        String resourceId = idExtractor.apply(copy);
        String k = key(serverId, resourceId);
        if (resourceId == null || currentGeneration(k) != expectedGeneration) {
            return null;
        }
        SaveLease<T> lease = newLease(serverId, resourceId, copy);
        drafts.put(k, lease);
        states.put(k, SyncedResourceState.DIRTY);
        touch(k);
        return lease;
    }

    public synchronized SaveLease<T> putInDraftIfAuthoritativeIfGenerationLease(
        String serverId, T item, long expectedGeneration, Predicate<? super T> expectedAuthoritative) {
        if (item == null || serverId == null || serverId.isBlank() || expectedGeneration < 0L
            || expectedAuthoritative == null) {
            return null;
        }
        T copy = copy(item);
        String resourceId = idExtractor.apply(copy);
        if (resourceId == null || resourceId.isBlank()) {
            return null;
        }
        String k = key(serverId, resourceId);
        T authoritative = cache.get(k);
        if (currentGeneration(k) != expectedGeneration || drafts.containsKey(k) || authoritative == null) {
            return null;
        }
        try {
            if (!expectedAuthoritative.test(copy(authoritative))) {
                return null;
            }
        } catch (RuntimeException exception) {
            return null;
        }
        rememberServer(serverId);
        SaveLease<T> lease = newLease(serverId, resourceId, copy);
        drafts.put(k, lease);
        states.put(k, SyncedResourceState.DIRTY);
        touch(k);
        return lease;
    }

    public synchronized void replaceFromServer(String serverId, T item) {
        if (item == null) {
            return;
        }
        rememberServer(serverId);
        String id = idExtractor.apply(item);
        if (id == null) {
            return;
        }
        String k = key(serverId, id);
        drafts.remove(k);
        T copy = copy(item);
        cache.put(k, copy);
        names.putIfAbsent(k, defaultNameExtractor.apply(copy));
        serverIds.add(k);
        states.put(k, SyncedResourceState.CLEAN);
        touch(k);
    }

    public synchronized void discardDraft(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        drafts.remove(k);
        if (cache.containsKey(k) || serverIds.contains(k)) {
            states.put(k, SyncedResourceState.CLEAN);
        } else {
            states.remove(k);
        }
        touch(k);
        forgetServerIfEmpty(serverId);
    }

    public synchronized boolean discardDraftIfCurrent(SaveLease<T> lease) {
        if (lease == null || lease.owner != this || !isCurrent(lease)) {
            return false;
        }
        String k = key(lease.serverId, lease.resourceId);
        if (!drafts.remove(k, lease)) {
            return false;
        }
        if (cache.containsKey(k) || serverIds.contains(k)) {
            states.put(k, SyncedResourceState.CLEAN);
        } else {
            states.remove(k);
        }
        touch(k);
        forgetServerIfEmpty(lease.serverId);
        return true;
    }

    public synchronized void markSaving(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        states.put(k, SyncedResourceState.SAVING);
        touch(k);
    }

    public synchronized void markFailed(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        states.put(k, SyncedResourceState.FAILED);
        touch(k);
    }

    public synchronized void markStale(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        states.put(k, SyncedResourceState.STALE);
        touch(k);
    }

    public synchronized SyncedResourceState getState(String serverId, String resourceId) {
        return states.getOrDefault(key(serverId, resourceId), SyncedResourceState.CLEAN);
    }

    public synchronized void putNameIfAbsent(String serverId, String resourceId, String name) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        if (names.putIfAbsent(k, name) == null) {
            touch(k);
        }
    }

    public synchronized void putName(String serverId, String resourceId, String name) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        names.put(k, name);
        touch(k);
    }

    public synchronized String getName(String serverId, String resourceId) {
        return names.getOrDefault(key(serverId, resourceId), resourceId);
    }

    public synchronized String removeName(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        String removed = names.remove(k);
        if (removed != null) {
            touch(k);
        }
        forgetServerIfEmpty(serverId);
        return removed;
    }

    public synchronized boolean containsServerId(String serverId, String resourceId) {
        return serverIds.contains(key(serverId, resourceId));
    }

    public synchronized void addServerId(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        if (serverIds.add(k)) {
            touch(k);
        }
    }

    public synchronized void removeServerId(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        if (serverIds.remove(k)) {
            touch(k);
        }
        forgetServerIfEmpty(serverId);
    }

    public synchronized boolean containsKey(String serverId, String resourceId) {
        String k = key(serverId, resourceId);
        return cache.containsKey(k) || drafts.containsKey(k) || serverIds.contains(k);
    }

    public synchronized boolean hasLoadedServerList(String serverId) {
        return loadedServerLists.contains(serverId);
    }

    public synchronized void remove(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        boolean changed = cache.remove(k) != null;
        changed |= drafts.remove(k) != null;
        changed |= serverIds.remove(k);
        changed |= names.remove(k) != null;
        changed |= states.remove(k) != null;
        changed |= pendingParents.remove(k) != null;
        if (changed) {
            touch(k);
        }
        forgetServerIfEmpty(serverId);
    }

    public synchronized Long removeIfGeneration(String serverId, String resourceId, long expectedGeneration) {
        String k = key(serverId, resourceId);
        if (currentGeneration(k) != expectedGeneration) {
            return null;
        }
        rememberServer(serverId);
        boolean changed = cache.remove(k) != null;
        changed |= drafts.remove(k) != null;
        changed |= serverIds.remove(k);
        changed |= names.remove(k) != null;
        changed |= states.remove(k) != null;
        changed |= pendingParents.remove(k) != null;
        if (changed) {
            touch(k);
        }
        forgetServerIfEmpty(serverId);
        return currentGeneration(k);
    }

    public synchronized Long updateIfGeneration(String serverId, String resourceId, long expectedGeneration,
                                                 UnaryOperator<T> update) {
        if (update == null) {
            return null;
        }
        String k = key(serverId, resourceId);
        if (currentGeneration(k) != expectedGeneration) {
            return null;
        }
        T current = currentValue(serverId, resourceId);
        if (current == null) {
            return null;
        }
        T updated = update.apply(copy(current));
        if (updated == null || !Objects.equals(resourceId, idExtractor.apply(updated))) {
            return null;
        }
        T owned = copy(updated);
        SaveLease<T> draft = drafts.get(k);
        if (draft != null) {
            drafts.put(k, new SaveLease<>(this, serverId, resourceId, draft.version, owned));
        } else if (cache.containsKey(k)) {
            cache.put(k, owned);
        } else {
            return null;
        }
        touch(k);
        return currentGeneration(k);
    }

    public synchronized boolean update(String serverId, String resourceId, UnaryOperator<T> update) {
        return updateIfGeneration(serverId, resourceId, currentGeneration(serverId, resourceId), update) != null;
    }

    public synchronized void cache(String serverId, T item) {
        cacheInternal(serverId, item, -1L, null, null, false);
    }

    private Long cacheInternal(String serverId, T item, long draftVersion,
                               BiFunction<? super T, ? super T, ? extends T> rebase,
                               Long expectedGeneration, boolean rebaseDraft) {
        if (item == null) {
            return null;
        }
        rememberServer(serverId);
        String id = idExtractor.apply(item);
        if (id == null) {
            return null;
        }
        String k = key(serverId, id);
        if (expectedGeneration != null && currentGeneration(k) != expectedGeneration) {
            return null;
        }
        T copy = copy(item);
        SaveLease<T> localLease = rebaseDraft && draftVersion < 0L ? drafts.get(k) : null;
        T rebased = null;
        if (localLease != null) {
            if (rebase == null) {
                return null;
            }
            rebased = rebase.apply(copy(cache.get(k)), copy(localLease.value));
            if (rebased == null || !Objects.equals(id, idExtractor.apply(rebased))) {
                return null;
            }
            rebased = copy(rebased);
        }
        cache.put(k, copy);
        names.putIfAbsent(k, defaultNameExtractor.apply(copy));
        serverIds.add(k);
        if (rebased != null) {
            drafts.put(k, newLease(serverId, id, rebased));
            states.put(k, SyncedResourceState.DIRTY);
        } else if (drafts.containsKey(k)) {
            states.put(k, SyncedResourceState.STALE);
        } else {
            states.put(k, SyncedResourceState.CLEAN);
        }
        touch(k);
        return currentGeneration(k);
    }

    public synchronized boolean cacheAndRebase(String serverId, T item, long draftVersion,
                                                BiFunction<? super T, ? super T, ? extends T> rebase) {
        return cacheInternal(serverId, item, draftVersion, rebase, null, true) != null;
    }

    public synchronized void markSaved(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        serverIds.add(k);
        SaveLease<T> draft = drafts.remove(k);
        if (draft != null) {
            cache.putIfAbsent(k, copy(draft.value));
        }
        states.put(k, SyncedResourceState.SAVED);
        touch(k);
    }

    public synchronized Long markSavedIfGeneration(String serverId, String resourceId, long expectedGeneration) {
        if (currentGeneration(serverId, resourceId) != expectedGeneration) {
            return null;
        }
        markSaved(serverId, resourceId);
        return currentGeneration(serverId, resourceId);
    }

    public synchronized Long markSavingIfGeneration(String serverId, String resourceId, long expectedGeneration) {
        if (currentGeneration(serverId, resourceId) != expectedGeneration) {
            return null;
        }
        markSaving(serverId, resourceId);
        return currentGeneration(serverId, resourceId);
    }

    public synchronized Long markFailedIfGeneration(String serverId, String resourceId, long expectedGeneration) {
        if (currentGeneration(serverId, resourceId) != expectedGeneration) {
            return null;
        }
        markFailed(serverId, resourceId);
        return currentGeneration(serverId, resourceId);
    }

    public synchronized boolean compareAndMarkSaved(String serverId, String resourceId, long version) {
        String k = key(serverId, resourceId);
        SaveLease<T> lease = drafts.get(k);
        if (lease == null || lease.version != version) {
            return false;
        }
        rememberServer(serverId);
        serverIds.add(k);
        drafts.remove(k);
        cache.putIfAbsent(k, copy(lease.value));
        states.put(k, SyncedResourceState.SAVED);
        touch(k);
        return true;
    }

    public synchronized Long compareAndMarkSavedIfGeneration(String serverId, String resourceId, long version,
                                                              long expectedGeneration) {
        if (currentGeneration(serverId, resourceId) != expectedGeneration) {
            return null;
        }
        if (!compareAndMarkSaved(serverId, resourceId, version)) {
            return null;
        }
        return currentGeneration(serverId, resourceId);
    }

    public synchronized void setPendingParent(String serverId, String resourceId, Object parent) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        pendingParents.put(k, parent);
        touch(k);
    }

    public synchronized Object removePendingParent(String serverId, String resourceId) {
        rememberServer(serverId);
        String k = key(serverId, resourceId);
        Object removed = pendingParents.remove(k);
        if (removed != null) {
            touch(k);
        }
        forgetServerIfEmpty(serverId);
        return removed;
    }

    public synchronized boolean rename(String serverId, String oldId, String newId, BiConsumer<T, String> applyRename) {
        if (serverId == null || oldId == null || newId == null) {
            return false;
        }
        rememberServer(serverId);
        String trimmedId = newId.trim();
        if (trimmedId.isEmpty()) {
            return false;
        }
        String oldKey = key(serverId, oldId);
        String newKey = key(serverId, trimmedId);
        if (oldKey.equals(newKey)) {
            return false;
        }
        if (cache.containsKey(newKey) || drafts.containsKey(newKey) || serverIds.contains(newKey)) {
            return false;
        }

        SaveLease<T> sourceLease = drafts.get(oldKey);
        T source = sourceLease != null ? sourceLease.value : null;
        boolean wasDraft = sourceLease != null;
        if (!wasDraft) {
            source = cache.get(oldKey);
        }
        if (source == null) {
            return false;
        }

        T renamed = copy(source);
        applyRename.accept(renamed, trimmedId);

        if (wasDraft) {
            drafts.remove(oldKey);
        }
        drafts.put(newKey, newLease(serverId, trimmedId, renamed));

        states.put(newKey, SyncedResourceState.DIRTY);
        if (cache.containsKey(oldKey) || serverIds.contains(oldKey)) {
            states.putIfAbsent(oldKey, SyncedResourceState.CLEAN);
        } else {
            states.remove(oldKey);
        }
        Object pendingParent = pendingParents.remove(oldKey);
        if (pendingParent != null) {
            pendingParents.put(newKey, pendingParent);
        }

        touch(oldKey);
        touch(newKey);

        return true;
    }

    public synchronized boolean restoreRename(String serverId, String renamedId, String originalId,
                                               BiConsumer<T, String> applyRename) {
        if (serverId == null || renamedId == null || originalId == null || renamedId.isBlank() || originalId.isBlank()) {
            return false;
        }
        rememberServer(serverId);
        String renamedKey = key(serverId, renamedId);
        String originalKey = key(serverId, originalId);
        SaveLease<T> draft = drafts.remove(renamedKey);
        if (draft == null) {
            return false;
        }
        T restored = copy(draft.value);
        applyRename.accept(restored, originalId);
        drafts.put(originalKey, newLease(serverId, originalId, restored));
        states.remove(renamedKey);
        states.put(originalKey, SyncedResourceState.DIRTY);
        Object pendingParent = pendingParents.remove(renamedKey);
        if (pendingParent != null) {
            pendingParents.put(originalKey, pendingParent);
        }
        touch(renamedKey);
        touch(originalKey);
        return true;
    }

    public synchronized String resolveDisplayName(String serverId, String oldId, String newId) {
        rememberServer(serverId);
        String oldKey = key(serverId, oldId);
        String displayName = names.remove(oldKey);
        if (displayName == null || displayName.isBlank() || displayName.equals(oldId)) {
            displayName = newId;
        }
        String newKey = key(serverId, newId);
        names.put(newKey, displayName);
        touch(oldKey);
        touch(newKey);
        return displayName;
    }

    public synchronized void applyServerList(String serverId, List<String> ids) {
        rememberServer(serverId);
        String prefix = serverId + ":";
        touchKeys(prefix);
        Set<String> listedKeys = new HashSet<>();
        Set<String> previouslyServerBacked = new HashSet<>();
        for (String key : serverIds) {
            if (key.startsWith(prefix)) {
                previouslyServerBacked.add(key);
            }
        }
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    listedKeys.add(prefix + id);
                }
            }
        }
        cache.keySet().removeIf(k -> k.startsWith(prefix) && !listedKeys.contains(k));
        names.keySet().removeIf(k -> k.startsWith(prefix) && !listedKeys.contains(k) && !drafts.containsKey(k));
        states.keySet().removeIf(k -> k.startsWith(prefix) && !listedKeys.contains(k) && !drafts.containsKey(k));
        pendingParents.keySet().removeIf(k -> k.startsWith(prefix) && !listedKeys.contains(k) && !drafts.containsKey(k));
        for (String draftKey : drafts.keySet()) {
            if (draftKey.startsWith(prefix) && previouslyServerBacked.contains(draftKey) && !listedKeys.contains(draftKey)) {
                states.put(draftKey, SyncedResourceState.DIRTY);
            }
        }
        serverIds.removeIf(k -> k.startsWith(prefix));
        loadedServerLists.add(serverId);
        for (String k : listedKeys) {
            String id = stripPrefix(k, serverId);
            serverIds.add(k);
            names.putIfAbsent(k, id);
            states.putIfAbsent(k, SyncedResourceState.CLEAN);
            touch(k);
        }
    }

    private void rememberServer(String serverId) {
        if (serverId != null && !serverId.isBlank()) {
            cacheServerIds.add(serverId);
        }
    }

    private void forgetServerIfEmpty(String serverId) {
        if (serverId != null && !serverId.isBlank() && !hasServerData(serverId)) {
            cacheServerIds.remove(serverId);
        }
    }

    private boolean hasServerData(String serverId) {
        String prefix = serverId + ":";
        return loadedServerLists.contains(serverId)
            || cache.keySet().stream().anyMatch(k -> k.startsWith(prefix))
            || drafts.keySet().stream().anyMatch(k -> k.startsWith(prefix))
            || serverIds.stream().anyMatch(k -> k.startsWith(prefix))
            || names.keySet().stream().anyMatch(k -> k.startsWith(prefix))
            || states.keySet().stream().anyMatch(k -> k.startsWith(prefix))
            || pendingParents.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    public synchronized List<String> getResourceIds(String serverId) {
        List<String> result = new ArrayList<>();
        String prefix = serverId + ":";
        for (String k : serverIds) {
            if (k.startsWith(prefix)) {
                result.add(k.substring(prefix.length()));
            }
        }
        return result;
    }

    public synchronized long currentGeneration(String serverId, String resourceId) {
        return currentGeneration(key(serverId, resourceId));
    }

    private T copy(T item) {
        if (item == null) {
            return null;
        }
        T copy = copyFunction.apply(item);
        if (copy == null) {
            throw new IllegalStateException("Resource copy cannot be null");
        }
        return copy;
    }

    synchronized String getString(String serverId, String resourceId, Function<T, String> extractor) {
        T value = currentValue(serverId, resourceId);
        return value != null ? extractor.apply(value) : null;
    }

    synchronized boolean getBoolean(String serverId, String resourceId, boolean fallback, Predicate<T> extractor) {
        T value = currentValue(serverId, resourceId);
        return value != null ? extractor.test(value) : fallback;
    }

    synchronized boolean hasCached(String serverId, String resourceId) {
        return cache.containsKey(key(serverId, resourceId));
    }

    synchronized <R> R getProjection(String serverId, String resourceId, Function<T, R> projection, Function<R, R> copy) {
        T value = currentValue(serverId, resourceId);
        R projected = value != null ? projection.apply(value) : null;
        return projected != null ? copy.apply(projected) : null;
    }

    synchronized int currentIdentity(String serverId, String resourceId) {
        T value = currentValue(serverId, resourceId);
        return value != null ? System.identityHashCode(value) : 0;
    }

    private T currentValue(String serverId, String resourceId) {
        String k = key(serverId, resourceId);
        SaveLease<T> draft = drafts.get(k);
        return draft != null ? draft.value : cache.get(k);
    }

    private T draftValue(String key) {
        SaveLease<T> draft = drafts.get(key);
        return draft != null ? draft.value : null;
    }

    private SaveLease<T> newLease(String serverId, String resourceId, T value) {
        return new SaveLease<>(this, serverId, resourceId, ++draftVersion, value);
    }

    private long currentGeneration(String key) {
        return generations.getOrDefault(key, 0L);
    }

    private void touch(String key) {
        long next = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        generation = next;
        generations.put(key, next);
    }

    private void touchKeys(String prefix) {
        Set<String> keys = new HashSet<>();
        cache.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(keys::add);
        drafts.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(keys::add);
        names.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(keys::add);
        states.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(keys::add);
        serverIds.stream().filter(key -> key.startsWith(prefix)).forEach(keys::add);
        pendingParents.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(keys::add);
        keys.forEach(this::touch);
    }

    private void touchKeys() {
        Set<String> keys = new HashSet<>();
        keys.addAll(cache.keySet());
        keys.addAll(drafts.keySet());
        keys.addAll(names.keySet());
        keys.addAll(states.keySet());
        keys.addAll(serverIds);
        keys.addAll(pendingParents.keySet());
        keys.forEach(this::touch);
    }

    private synchronized boolean isCurrent(SaveLease<T> lease) {
        return lease != null && drafts.get(key(lease.serverId, lease.resourceId)) == lease;
    }

    private synchronized boolean updateName(SaveLease<T> lease, String name, boolean replace) {
        if (!isCurrent(lease) || name == null || name.isBlank()) return false;
        String k = key(lease.serverId, lease.resourceId);
        if (replace) {
            names.put(k, name);
            touch(k);
        } else if (names.putIfAbsent(k, name) == null) {
            touch(k);
        }
        return true;
    }

    private synchronized boolean markSaving(SaveLease<T> lease) {
        if (!isCurrent(lease)) return false;
        String k = key(lease.serverId, lease.resourceId);
        states.put(k, SyncedResourceState.SAVING);
        touch(k);
        return true;
    }

    private synchronized boolean markFailed(SaveLease<T> lease) {
        if (!isCurrent(lease)) return false;
        String k = key(lease.serverId, lease.resourceId);
        states.put(k, SyncedResourceState.FAILED);
        touch(k);
        return true;
    }

    private synchronized boolean isCurrent(SnapshotLease<T> lease) {
        if (lease == null) {
            return false;
        }
        return lease.authoritative ? cache.get(key(lease.serverId, lease.resourceId)) == lease.value
            : currentValue(lease.serverId, lease.resourceId) == lease.value;
    }

    private final class LazyCopyMap extends AbstractMap<String, T> {
        private final Map<String, T> source;
        private final Set<Entry<String, T>> entries = new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, T>> iterator() {
                Iterator<Entry<String, T>> iterator = source.entrySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Entry<String, T> next() {
                        Entry<String, T> entry = iterator.next();
                        return Map.entry(entry.getKey(), copy(entry.getValue()));
                    }
                };
            }

            @Override
            public int size() {
                return source.size();
            }
        };

        private LazyCopyMap(Map<String, T> source) {
            this.source = Map.copyOf(source);
        }

        @Override
        public T get(Object key) {
            return copy(source.get(key));
        }

        @Override
        public boolean containsKey(Object key) {
            return source.containsKey(key);
        }

        @Override
        public int size() {
            return source.size();
        }

        @Override
        public Set<Entry<String, T>> entrySet() {
            return entries;
        }

        @Override
        public Set<String> keySet() {
            return source.keySet();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T copyValue(T item) {
        if (item instanceof JsonObject json) {
            return (T) json.deepCopy();
        }
        if (item instanceof FlowGraph graph) {
            return (T) FlowSerializer.deserialize(FlowSerializer.serialize(graph));
        }
        if (item instanceof GuiDefinition gui) {
            return (T) FlowSerializer.deserializeGui(FlowSerializer.serializeGui(gui));
        }
        if (item instanceof ScoreboardDefinition scoreboard) {
            return (T) FlowSerializer.deserializeScoreboard(FlowSerializer.serializeScoreboard(scoreboard));
        }
        if (item instanceof TabDefinition tab) {
            return (T) FlowSerializer.deserializeTab(FlowSerializer.serializeTab(tab));
        }
        return item;
    }
}
