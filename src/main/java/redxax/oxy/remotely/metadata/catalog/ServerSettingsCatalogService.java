package redxax.oxy.remotely.metadata.catalog;

import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.metadata.MetadataRepository;
import redxax.oxy.remotely.settings.server.HostedServerSettingsProvider;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import restudio.rescreen.platform.Async;
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
    private final Map<String, MetadataRepository.Request> requests = new LinkedHashMap<>();
    private final Set<MetadataRepository.Request> admitted = new LinkedHashSet<>();
    private final Map<Context, Integer> references = new LinkedHashMap<>();
    private boolean closed;
    private boolean settingsAdmitted;

    public ServerSettingsCatalogService(MetadataRepository metadata) {
        this(metadata, new ResolvedCatalogRepository(), new HostedServerSettingsProvider(ServerSettingsRegistry.getInstance()));
    }

    ServerSettingsCatalogService(MetadataRepository metadata, ResolvedCatalogRepository resolved,
                                 HostedServerSettingsProvider hostedSettings) {
        this.metadata = Objects.requireNonNull(metadata, "Metadata repository is required");
        this.resolved = Objects.requireNonNull(resolved, "Resolved catalog repository is required");
        this.hostedSettings = Objects.requireNonNull(hostedSettings, "Hosted settings provider is required");
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
        synchronized (this) {
            if (settingsAdmitted) return;
            settingsAdmitted = true;
        }
        metadata.refresh(SETTINGS_REQUEST).whenComplete((snapshot, failure) -> {
            if (failure != null) {
                retrySettings();
                return;
            }
            try {
                hostedSettings.apply(snapshot);
            } catch (RuntimeException invalid) {
                retrySettings();
            }
        });
    }

    private synchronized void retrySettings() {
        settingsAdmitted = false;
    }

    private void admit(String minecraftVersion) {
        MetadataRepository.Request request = request(minecraftVersion);
        synchronized (this) {
            if (request == null || !admitted.add(request)) return;
        }
        metadata.refresh(request).whenComplete((ignored, failure) -> {
            if (failure == null) return;
            synchronized (ServerSettingsCatalogService.this) {
                admitted.remove(request);
            }
        });
    }

    public Async<MetadataRepository.Snapshot> refresh(String minecraftVersion) {
        MetadataRepository.Request request = request(minecraftVersion);
        return request == null ? Async.failed(new IllegalArgumentException("Minecraft version is required"))
            : metadata.refresh(request);
    }

    private ResolvedCatalogRepository.Snapshot resolve(Context context, String sourceId, String currentIdentity,
                                                        Collection<String> currentValues) {
        CatalogId catalogId = catalogId(sourceId);
        if (catalogId == null) return null;
        MetadataRepository.Request request = request(context.minecraftVersion());
        MetadataRepository.Snapshot metadataSnapshot = request == null ? null : metadata.snapshot(request);
        ResolvedCatalogRepository.Baseline baseline = metadataSnapshot == null
            ? ResolvedCatalogRepository.Baseline.missing()
            : ResolvedCatalogRepository.Baseline.from(metadataSnapshot, catalogId);
        String server = context.resolutionIdentity();
        OptionCatalogLoader.Snapshot liveSnapshot = OptionCatalogLoader.snapshot(context.serverId(), sourceId);
        ResolvedCatalogRepository.Live live = "server:none".equals(context.serverId())
            ? ResolvedCatalogRepository.Live.missing()
            : ResolvedCatalogRepository.Live.from(server, Long.toString(liveSnapshot.revision()), liveSnapshot);
        List<String> current = currentValues == null ? List.of() : currentValues.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
        String identity = current.isEmpty() ? "current:none" : text(currentIdentity, "current:configured");
        return resolved.resolve(new ResolvedCatalogRepository.Input(catalogId.canonicalText(), baseline, live,
            new ResolvedCatalogRepository.Current(identity, current)));
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

    private synchronized void release(Context context) {
        Integer count = references.get(context);
        if (count == null) return;
        if (count > 1) {
            references.put(context, count - 1);
            return;
        }
        references.remove(context);
        resolved.invalidateServer(context.resolutionIdentity());
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        references.clear();
        admitted.clear();
        resolved.invalidateAll();
        hostedSettings.close();
    }

    private record Context(String serverId, long connectionGeneration, String minecraftVersion) {
        private String resolutionIdentity() {
            return serverId + "@" + connectionGeneration;
        }
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
