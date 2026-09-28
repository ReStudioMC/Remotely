package redxax.oxy.remotely.settings.server;

import redxax.oxy.remotely.util.BrowserSafeState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class ServerSettingsRegistry implements AutoCloseable {
    private static final ServerSettingsRegistry INSTANCE = new ServerSettingsRegistry(ServerSettingsRegistryStorage.unavailable());

    private final Map<String, ProviderRegistration> providers = new LinkedHashMap<>();
    private final List<Consumer<ServerSettingsSnapshot>> listeners = BrowserSafeState.list();
    private volatile ServerSettingsSnapshot currentSnapshot = new ServerSettingsSnapshot(List.of());
    private ServerSettingsRegistryStorage storage;
    private Object hostedOwner;

    public ServerSettingsRegistry() {
        this(ServerSettingsRegistryStorage.unavailable());
    }

    public ServerSettingsRegistry(ServerSettingsRegistryStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public static ServerSettingsRegistry getInstance() {
        return INSTANCE;
    }

    public static ServerSettingsRegistry empty() {
        return new ServerSettingsRegistry(ServerSettingsRegistryStorage.unavailable());
    }

    public StorageSnapshot installStorageWithSnapshot(ServerSettingsRegistryStorage storage) {
        Objects.requireNonNull(storage, "storage");
        synchronized (this) {
            ServerSettingsRegistryStorage previous = this.storage;
            this.storage = storage;
            return new StorageSnapshot(this, previous, storage);
        }
    }

    public void restoreStorage(StorageSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ServerSettingsRegistryStorage installed;
        synchronized (this) {
            if (snapshot.registry != this || snapshot.restored) {
                return;
            }
            if (storage != snapshot.installed) {
                snapshot.restored = true;
                return;
            }
            installed = storage;
            storage = snapshot.previous;
            snapshot.restored = true;
        }
        if (installed != snapshot.previous) {
            installed.close();
        }
    }

    public void installStorage(ServerSettingsRegistryStorage storage) {
        Objects.requireNonNull(storage, "storage");
        ServerSettingsRegistryStorage previous;
        synchronized (this) {
            previous = this.storage;
            this.storage = storage;
        }
        if (previous != storage) {
            previous.close();
        }
    }

    public void register(String providerId, int priority, Collection<ServerSettingsPack> packs) {
        String normalizedProvider = requiredProvider(providerId);
        replace(new ProviderRegistration(
                normalizedProvider,
                priority,
                validatePacks(packs),
                ProviderSource.PROGRAMMATIC,
                "programmatic:" + normalizedProvider
        ));
    }

    public void register(String providerId, int priority, ServerSettingsPack... packs) {
        register(providerId, priority, packs == null ? List.of() : List.of(packs));
    }

    public void register(ServerSettingsMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        register(metadata.providerId(), metadata.priority(), metadata.packs());
    }

    public void registerBuiltin(String sourceKey, ServerSettingsMetadata metadata) {
        registerLoaded(sourceKey, metadata, ProviderSource.BUILTIN);
    }

    public void registerExternal(String sourceKey, ServerSettingsMetadata metadata) {
        registerLoaded(sourceKey, metadata, ProviderSource.EXTERNAL);
    }

    void replaceHosted(Object owner, Map<String, ServerSettingsMetadata> sources) {
        Objects.requireNonNull(owner, "Hosted settings owner is required");
        Objects.requireNonNull(sources, "Hosted settings sources are required");
        List<ProviderRegistration> registrations = new ArrayList<>(sources.size());
        sources.forEach((sourceId, metadata) -> {
            Objects.requireNonNull(metadata, "Hosted settings metadata is required");
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("A hosted settings source ID is required");
            }
            registrations.add(new ProviderRegistration(
                    requiredProvider(metadata.providerId()),
                    metadata.priority(),
                    validatePacks(metadata.packs()),
                    ProviderSource.HOSTED,
                    "hosted:" + sourceId.trim()
            ));
        });
        if (registrations.isEmpty()) {
            throw new IllegalArgumentException("Hosted settings need at least one source");
        }
        boolean changed;
        synchronized (this) {
            claimHostedOwner(owner);
            ServerSettingsSnapshot previous = currentSnapshot;
            providers.entrySet().removeIf(entry -> entry.getValue().source() == ProviderSource.HOSTED);
            for (ProviderRegistration registration : registrations) {
                providers.put(registration.sourceKey(), registration);
            }
            rebuildLocked();
            changed = !previous.packs().equals(currentSnapshot.packs());
        }
        if (changed) notifyListeners();
    }

    void clearHosted(Object owner) {
        Objects.requireNonNull(owner, "Hosted settings owner is required");
        boolean changed;
        synchronized (this) {
            if (hostedOwner != owner) {
                return;
            }
            hostedOwner = null;
            changed = providers.entrySet().removeIf(entry -> entry.getValue().source() == ProviderSource.HOSTED);
            if (changed) {
                rebuildLocked();
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void clearExternal() {
        boolean changed;
        synchronized (this) {
            changed = providers.entrySet().removeIf(entry -> entry.getValue().source() == ProviderSource.EXTERNAL);
            if (changed) {
                rebuildLocked();
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void loadExternalFile(Object file) {
        storage().loadExternalFile(requiredPath(file));
    }

    public void watchExternalDirectory(Object directory) {
        storage().watchExternalDirectory(requiredPath(directory));
    }

    public void watchExternalDirectory(Object directory, long debounceMillis) {
        storage().watchExternalDirectory(requiredPath(directory), debounceMillis);
    }

    public void reloadExternalDirectory() {
        storage().reloadExternalDirectory();
    }

    public void unregister(String providerId) {
        String normalizedProvider = requiredProvider(providerId);
        boolean changed;
        synchronized (this) {
            changed = providers.remove("programmatic:" + normalizedProvider) != null;
            if (changed) {
                rebuildLocked();
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void registerProvider(String providerId, int priority, Collection<ServerSettingsPack> packs) {
        register(providerId, priority, packs);
    }

    public void unregisterProvider(String providerId) {
        unregister(providerId);
    }

    public ServerSettingsSnapshot snapshot() {
        return currentSnapshot;
    }

    public ServerSettingsSnapshot getSnapshot() {
        return snapshot();
    }

    public ServerSettingsSnapshot snapshot(Object ignored) {
        return snapshot();
    }

    public ServerSettingsSnapshot snapshotFor(Object target) {
        return snapshot(target);
    }

    public ServerSettingsSnapshot getSnapshot(Object target) {
        return snapshot(target);
    }

    public List<ServerSettingsPack> packs() {
        return currentSnapshot.packs();
    }

    public List<ServerSettingsPack> packs(Object target) {
        return snapshot(target).packs();
    }

    public void addListener(Consumer<ServerSettingsSnapshot> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<ServerSettingsSnapshot> listener) {
        listeners.remove(listener);
    }

    public void onChange(Consumer<ServerSettingsSnapshot> listener) {
        addListener(listener);
    }

    @Override
    public void close() {
        ServerSettingsRegistryStorage previous;
        synchronized (this) {
            previous = storage;
            storage = ServerSettingsRegistryStorage.unavailable();
            providers.clear();
            hostedOwner = null;
            currentSnapshot = new ServerSettingsSnapshot(List.of());
            listeners.clear();
        }
        previous.close();
    }

    private void registerLoaded(String sourceKey, ServerSettingsMetadata metadata, ProviderSource source) {
        Objects.requireNonNull(metadata, "metadata");
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("A settings source key is required");
        }
        replace(new ProviderRegistration(
                requiredProvider(metadata.providerId()),
                metadata.priority(),
                validatePacks(metadata.packs()),
                source,
                sourceKey
        ));
    }

    private synchronized ServerSettingsRegistryStorage storage() {
        return storage;
    }

    private static String requiredPath(Object value) {
        Objects.requireNonNull(value, "path");
        String path = value.toString();
        if (path.isBlank()) {
            throw new IllegalArgumentException("A settings path is required");
        }
        return path;
    }

    private void replace(ProviderRegistration registration) {
        synchronized (this) {
            providers.put(registration.sourceKey(), registration);
            rebuildLocked();
        }
        notifyListeners();
    }

    private void rebuildLocked() {
        Map<String, List<PackCandidate>> candidates = new LinkedHashMap<>();
        for (ProviderRegistration provider : providers.values()) {
            for (ServerSettingsPack pack : provider.packs()) {
                candidates.computeIfAbsent(pack.id().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(new PackCandidate(provider, pack));
            }
        }
        List<ServerSettingsPack> selected = new ArrayList<>();
        for (List<PackCandidate> registrations : candidates.values()) {
            registrations.sort(packComparator());
            selected.add(registrations.getFirst().pack());
        }
        selected.sort(Comparator.comparingInt(ServerSettingsPack::priority)
                .thenComparing(ServerSettingsPack::id, String.CASE_INSENSITIVE_ORDER));
        currentSnapshot = new ServerSettingsSnapshot(selected);
    }

    private static Comparator<PackCandidate> packComparator() {
        return (left, right) -> {
            int comparison = Integer.compare(right.provider().source().rank(), left.provider().source().rank());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(right.provider().priority(), left.provider().priority());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(right.pack().priority(), left.pack().priority());
            if (comparison != 0) {
                return comparison;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(left.provider().sourceKey(), right.provider().sourceKey());
        };
    }

    private void claimHostedOwner(Object owner) {
        if (hostedOwner != null && hostedOwner != owner) {
            throw new IllegalStateException("Hosted server settings already have an owner");
        }
        hostedOwner = owner;
    }

    private static List<ServerSettingsPack> validatePacks(Collection<ServerSettingsPack> packs) {
        if (packs == null || packs.isEmpty()) {
            throw new IllegalArgumentException("A settings provider needs at least one pack");
        }
        List<ServerSettingsPack> immutable = List.copyOf(packs);
        Set<String> ids = new HashSet<>();
        for (ServerSettingsPack pack : immutable) {
            if (pack == null || !ids.add(pack.id().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Settings pack IDs must be unique within a provider");
            }
        }
        return immutable;
    }

    private static String requiredProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("A provider ID is required");
        }
        return providerId.trim();
    }

    private void notifyListeners() {
        List<Consumer<ServerSettingsSnapshot>> listenersSnapshot;
        synchronized (this) {
            listenersSnapshot = List.copyOf(listeners);
        }
        ServerSettingsSnapshot snapshot = currentSnapshot;
        for (Consumer<ServerSettingsSnapshot> listener : listenersSnapshot) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private record ProviderRegistration(String providerId, int priority, List<ServerSettingsPack> packs,
                                        ProviderSource source, String sourceKey) {
        private ProviderRegistration {
            packs = List.copyOf(packs);
        }
    }

    private record PackCandidate(ProviderRegistration provider, ServerSettingsPack pack) {
    }

    public static final class StorageSnapshot implements AutoCloseable {
        private final ServerSettingsRegistry registry;
        private final ServerSettingsRegistryStorage previous;
        private final ServerSettingsRegistryStorage installed;
        private boolean restored;

        private StorageSnapshot(ServerSettingsRegistry registry, ServerSettingsRegistryStorage previous,
                                ServerSettingsRegistryStorage installed) {
            this.registry = registry;
            this.previous = previous;
            this.installed = installed;
        }

        public void restore() {
            registry.restoreStorage(this);
        }

        @Override
        public void close() {
            restore();
        }
    }

    private enum ProviderSource {
        BUILTIN(0),
        PROGRAMMATIC(1),
        HOSTED(2),
        EXTERNAL(3);

        private final int rank;

        ProviderSource(int rank) {
            this.rank = rank;
        }

        private int rank() {
            return rank;
        }
    }
}
