package redxax.oxy.remotely.metadata.catalog;

import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.metadata.MetadataRepository;
import redxax.oxy.remotely.settings.server.HostedServerSettingsProvider;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.resync.metadata.CatalogId;
import restudio.resync.metadata.MetadataCoordinate;
import restudio.resync.metadata.MinecraftRegistryBundle;
import restudio.resync.metadata.ServerSettingsBundle;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ServerSettingsCatalogService {
    private static final long REVALIDATE_MILLIS = 60_000L;
    private static final String SOURCE_PREFIX = "server:minecraft:";
    private static final Map<String, String> CATALOG_ALIASES = Map.of(
        "biome", "worldgen/biome",
        "material", "item",
        "statistic", "custom_stat");
    private static final MetadataRepository.Request SETTINGS_REQUEST = new MetadataRepository.Request(
        Set.of(ServerSettingsBundle.ARTIFACT_FAMILY),
        new MetadataCoordinate("java", null, null, null, null, null, null), Set.of());

    private final MetadataRepository metadata;
    private final ResolvedCatalogRepository resolved;
    private final HostedServerSettingsProvider hostedSettings;
    private final Clock clock;
    private final Map<String, MetadataRepository.Request> requests = new LinkedHashMap<>();
    private final Set<MetadataRepository.Request> refreshing = new LinkedHashSet<>();
    private final Map<MetadataRepository.Request, Long> refreshedAt = new LinkedHashMap<>();
    private final Map<Context, Integer> references = new LinkedHashMap<>();
    private final Map<ResolutionKey, CachedResolution> catalogSnapshots = new LinkedHashMap<>();
    private boolean closed;

    public ServerSettingsCatalogService(MetadataRepository metadata) {
        this(metadata, new ResolvedCatalogRepository(), new HostedServerSettingsProvider(ServerSettingsRegistry.getInstance()),
            Clock.system());
    }

    ServerSettingsCatalogService(MetadataRepository metadata, ResolvedCatalogRepository resolved,
                                 HostedServerSettingsProvider hostedSettings) {
        this(metadata, resolved, hostedSettings, Clock.system());
    }

    ServerSettingsCatalogService(MetadataRepository metadata, ResolvedCatalogRepository resolved,
                                 HostedServerSettingsProvider hostedSettings, Clock clock) {
        this.metadata = Objects.requireNonNull(metadata, "Metadata repository is required");
        this.resolved = Objects.requireNonNull(resolved, "Resolved catalog repository is required");
        this.hostedSettings = Objects.requireNonNull(hostedSettings, "Hosted settings provider is required");
        this.clock = clock == null ? Clock.system() : clock;
    }

    public synchronized View open(String serverId, long connectionGeneration, String minecraftVersion) {
        if (closed) throw new IllegalStateException("Server settings catalogs are closed");
        Context context = new Context(text(serverId, "server:none"), Math.max(0L, connectionGeneration),
            minecraftVersion == null ? "" : minecraftVersion.trim());
        references.merge(context, 1, Integer::sum);
        View view = new View(this, context);
        admitSettings();
        admit(context.minecraftVersion());
        return view;
    }

    private void admitSettings() {
        if (!beginRefresh(SETTINGS_REQUEST)) return;
        metadata.refresh(SETTINGS_REQUEST).whenComplete((snapshot, failure) -> {
            finishRefresh(SETTINGS_REQUEST, failure == null && applySettings(snapshot));
        });
    }

    private boolean applySettings(MetadataRepository.Snapshot snapshot) {
        if (!active()) return false;
        try {
            hostedSettings.apply(snapshot);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private synchronized boolean active() {
        return !closed;
    }

    private void admit(String minecraftVersion) {
        MetadataRepository.Request request = request(minecraftVersion);
        if (request == null || !beginRefresh(request)) return;
        metadata.refresh(request).whenComplete((ignored, failure) -> {
            finishRefresh(request, failure == null);
        });
    }

    private synchronized boolean beginRefresh(MetadataRepository.Request request) {
        if (closed || refreshing.contains(request)) return false;
        long now = clock.millis();
        Long previous = refreshedAt.get(request);
        if (previous != null && now >= previous && now - previous < REVALIDATE_MILLIS) return false;
        refreshing.add(request);
        return true;
    }

    private synchronized void finishRefresh(MetadataRepository.Request request, boolean success) {
        refreshing.remove(request);
        if (success && !closed) refreshedAt.put(request, clock.millis());
    }

    public Async<MetadataRepository.Snapshot> refresh(String minecraftVersion) {
        MetadataRepository.Request request = request(minecraftVersion);
        return request == null ? Async.failed(new IllegalArgumentException("Minecraft version is required"))
            : metadata.refresh(request);
    }

    private synchronized ResolvedCatalogRepository.Snapshot resolve(Context context, String sourceId,
                                                                     String currentIdentity,
                                                                     Collection<String> currentValues) {
        if (closed || !references.containsKey(context)) return null;
        CatalogId catalogId = catalogId(sourceId);
        if (catalogId == null) return null;
        MetadataRepository.Request request = request(context.minecraftVersion());
        MetadataRepository.Snapshot metadataSnapshot = request == null ? null : metadata.snapshot(request);
        String server = context.resolutionIdentity();
        OptionCatalogLoader.Snapshot liveSnapshot = OptionCatalogLoader.snapshot(context.serverId(), sourceId);
        List<String> current = currentValues == null ? List.of() : currentValues.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
        String ownerIdentity = text(currentIdentity, "current:configured");
        String identity = currentIdentity(ownerIdentity, current);
        ResolutionKey key = new ResolutionKey(context, sourceId, ownerIdentity);
        ResolutionStamp stamp = new ResolutionStamp(metadataSnapshot == null ? "missing" : metadataSnapshot.stamp(),
            liveSnapshot.contextKey(), liveSnapshot.revision(), liveSnapshot.loading(), liveSnapshot.status(),
            liveSnapshot.diagnostic(), identity);
        CachedResolution cached = catalogSnapshots.get(key);
        if (cached != null && cached.stamp().equals(stamp)) return cached.snapshot();
        ResolvedCatalogRepository.Baseline baseline = metadataSnapshot == null
            ? ResolvedCatalogRepository.Baseline.missing()
            : ResolvedCatalogRepository.Baseline.from(metadataSnapshot, catalogId);
        ResolvedCatalogRepository.Live live = "server:none".equals(context.serverId())
            ? ResolvedCatalogRepository.Live.missing()
            : ResolvedCatalogRepository.Live.from(server, Long.toString(liveSnapshot.revision()), liveSnapshot);
        ResolvedCatalogRepository.Input input = new ResolvedCatalogRepository.Input(catalogId.canonicalText(), baseline,
            live, new ResolvedCatalogRepository.Current(identity, current));
        ResolvedCatalogRepository.Snapshot snapshot = resolved.resolve(input);
        catalogSnapshots.put(key, new CachedResolution(stamp, snapshot));
        return snapshot;
    }

    public static CatalogId catalogId(String sourceId) {
        if (sourceId == null || !sourceId.startsWith(SOURCE_PREFIX)) return null;
        String path = sourceId.substring(SOURCE_PREFIX.length()).trim();
        if (path.isEmpty() || "world".equals(path)) return null;
        return CatalogId.of("minecraft", CATALOG_ALIASES.getOrDefault(path, path));
    }

    private synchronized MetadataRepository.Request request(String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank() || "latest".equalsIgnoreCase(minecraftVersion)) return null;
        String version = minecraftVersion.trim();
        return requests.computeIfAbsent(version, value -> new MetadataRepository.Request(
            Set.of(MinecraftRegistryBundle.ARTIFACT_FAMILY),
            new MetadataCoordinate("java", value, null, null, null, null, null), Set.of()));
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String currentIdentity(String owner, List<String> values) {
        StringBuilder identity = new StringBuilder(owner.length() + 16);
        identity.append(owner.length()).append(':').append(owner);
        for (String value : values) identity.append('|').append(value.length()).append(':').append(value);
        return identity.toString();
    }

    private synchronized void release(Context context) {
        Integer count = references.get(context);
        if (count == null) return;
        if (count > 1) {
            references.put(context, count - 1);
            return;
        }
        references.remove(context);
        catalogSnapshots.keySet().removeIf(key -> key.context().equals(context));
        resolved.invalidateServer(context.resolutionIdentity());
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        references.clear();
        refreshing.clear();
        refreshedAt.clear();
        catalogSnapshots.clear();
        resolved.invalidateAll();
        hostedSettings.close();
    }

    private record Context(String serverId, long connectionGeneration, String minecraftVersion) {
        private String resolutionIdentity() {
            return serverId.length() + ":" + serverId + '|' + connectionGeneration + '|' + minecraftVersion.length()
                + ':' + minecraftVersion;
        }
    }

    private record ResolutionKey(Context context, String sourceId, String currentOwner) {
    }

    private record ResolutionStamp(String metadataStamp, String liveContext, long liveRevision, boolean liveLoading,
                                   String liveStatus, String liveDiagnostic, String currentIdentity) {
    }

    private record CachedResolution(ResolutionStamp stamp, ResolvedCatalogRepository.Snapshot snapshot) {
    }

    public static final class View implements AutoCloseable {
        private final ServerSettingsCatalogService owner;
        private final Context context;
        private boolean closed;

        private View(ServerSettingsCatalogService owner, Context context) {
            this.owner = owner;
            this.context = context;
        }

        public ResolvedCatalogRepository.Snapshot catalog(String sourceId, String currentIdentity,
                                                           Collection<String> currentValues) {
            if (closed) return null;
            return owner.resolve(context, sourceId, currentIdentity, currentValues);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.release(context);
        }
    }
}
