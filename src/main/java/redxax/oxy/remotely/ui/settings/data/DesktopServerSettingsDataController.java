package redxax.oxy.remotely.ui.settings.data;

import redxax.oxy.remotely.network.config.DesktopStructuredDocumentParser;
import redxax.oxy.remotely.metadata.catalog.ServerSettingsCatalogService;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.api.RebaseApiFactory;
import restudio.rebase.instance.Instance;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.ScreenManager;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public class DesktopServerSettingsDataController extends ServerSettingsDocumentDataController {
    private final Instance source;
    private final Path sourcePath;
    private final RebaseAPI sourceApi;

    public DesktopServerSettingsDataController(Instance instance, ServerSettingsSnapshot snapshot) {
        this(instance, snapshot, RebaseApiFactory.get(Objects.requireNonNull(instance, "instance")));
    }

    public DesktopServerSettingsDataController(Instance instance, ServerSettingsSnapshot snapshot, RebaseAPI api) {
        this(instance, snapshot, api, null);
    }

    public DesktopServerSettingsDataController(Instance instance, ServerSettingsSnapshot snapshot, RebaseAPI api,
                                               ServerSettingsCatalogService.View catalogs) {
        this(instance, snapshot, api, catalogs, false);
    }

    public static DesktopServerSettingsDataController forNewServer(Instance instance, ServerSettingsSnapshot snapshot,
                                                                    ServerSettingsCatalogService.View catalogs) {
        return new DesktopServerSettingsDataController(instance, snapshot, null, catalogs, true);
    }

    private DesktopServerSettingsDataController(Instance instance, ServerSettingsSnapshot snapshot, RebaseAPI api,
                                                ServerSettingsCatalogService.View catalogs, boolean newServer) {
        super(target(instance), snapshot, newServer ? emptyStore() : store(instance, api), false,
                new DesktopStructuredDocumentParser(), catalogs, newServer ? ScreenManager.getInstance()::execute : null);
        source = instance;
        sourcePath = normalizedPath(instance.getPath());
        sourceApi = api;
    }

    @Override
    public Async<Void> save(Object target) {
        if (!(target instanceof Instance instance)) return Async.failed(new IllegalArgumentException("A desktop server instance is required"));
        if (sourceApi == null) return Async.failed(new IllegalStateException("New Server Settings Must Be Included In Creation"));
        boolean sameTarget = Objects.equals(sourcePath, normalizedPath(instance.getPath()));
        return ready().thenCompose(ignored -> saveTo(sameTarget ? store(source, sourceApi)
                : store(instance, RebaseApiFactory.get(instance)), sameTarget));
    }

    private static ServerSettingsDocumentTarget target(Instance instance) {
        Objects.requireNonNull(instance, "instance");
        return new ServerSettingsDocumentTarget() {
            @Override
            public String catalogServerId() {
                String instanceId = instance.getInstanceId();
                return instanceId == null ? "" : instanceId.trim();
            }

            @Override
            public String minecraftVersion() {
                String version = instance.getVersionId();
                return version == null ? "" : version.trim();
            }

            @Override
            public Collection<String> softwareTokens() {
                return DesktopServerSettingsPackMatcher.softwareTokens(instance);
            }

            @Override
            public String property(String key) {
                return instance.getServerProperties().getProperty(key);
            }

            @Override
            public void property(String key, String value) {
                instance.getServerProperties().setProperty(key, value);
            }

            @Override
            public void removeProperty(String key) {
                instance.getServerProperties().remove(key);
            }

            @Override
            public void replaceProperties(Map<String, String> values) {
                Properties properties = instance.getServerProperties();
                properties.clear();
                properties.putAll(values);
            }

        };
    }

    private static ServerSettingsDocumentStore store(Instance instance, RebaseAPI api) {
        Objects.requireNonNull(api, "api");
        return new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                Path path = resolve(instance, relativePath);
                if (path == null) {
                    return Async.completed(Document.missing());
                }
                return JvmAsyncBridge.fromFuture(api.fileExists(path)).thenCompose(exists -> Boolean.TRUE.equals(exists)
                        ? JvmAsyncBridge.fromFuture(api.readFile(path)).thenApply(content -> new Document(true, content))
                        : Async.completed(Document.missing()));
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                Path path = resolve(instance, relativePath);
                if (path == null) {
                    return Async.failed(new IllegalArgumentException("Configuration path is required"));
                }
                return JvmAsyncBridge.fromFuture(api.writeFile(path, content));
            }

            @Override
            public Async<List<Entry>> list(String relativePath) {
                Path path = resolve(instance, relativePath == null || relativePath.isBlank() ? "." : relativePath);
                if (path == null) return Async.completed(List.of());
                return JvmAsyncBridge.fromFuture(api.listDirectory(path)).thenApply(entries -> entries == null ? List.of()
                        : entries.stream().filter(Objects::nonNull)
                        .map(entry -> new Entry(entry.displayName != null ? entry.displayName
                                : entry.path != null && entry.path.getFileName() != null ? entry.path.getFileName().toString() : "", entry.isDirectory))
                        .filter(entry -> !entry.name().isBlank()).toList());
            }
        };
    }

    private static ServerSettingsDocumentStore emptyStore() {
        return new ServerSettingsDocumentStore() {
            @Override
            public Async<Document> read(String relativePath) {
                return Async.completed(Document.missing());
            }

            @Override
            public Async<Void> write(String relativePath, String content) {
                return Async.failed(new IllegalStateException("New Server Settings Must Be Included In Creation"));
            }

            @Override
            public Async<List<Entry>> list(String relativePath) {
                return Async.completed(List.of());
            }
        };
    }

    private static Path resolve(Instance instance, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        String root = instance.getPath();
        if (root == null || root.isBlank()) return Path.of(relativePath);
        Path base = Path.of(root).toAbsolutePath().normalize();
        Path target = base.resolve(relativePath).normalize();
        if (!target.startsWith(base)) throw new IllegalArgumentException("Configuration path escapes the instance: " + relativePath);
        return target;
    }

    private static Path normalizedPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }
}
