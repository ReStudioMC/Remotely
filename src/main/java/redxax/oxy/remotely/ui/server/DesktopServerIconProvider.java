package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.api.RebaseApiFactory;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.hosting.RemoteHost;
import restudio.rebase.instance.Instance;
import restudio.rescreen.config.AppStoragePaths;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.ImportedIconLibrary;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.ResourceManager;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static redxax.oxy.remotely.util.DevUtil.devPrint;

public final class DesktopServerIconProvider implements ServerIconProvider {
    private static final Set<String> SOFTWARE_ICONS = Set.of("vanilla", "fabric", "forge", "neoforge", "paper", "purpur", "quilt", "spigot", "bukkit", "leaf", "velocity", "waterfall");
    private final Path cacheDir;
    private final Path customizationDir;
    private final Set<String> remoteIconsLoaded = BrowserSafeState.set();
    private final Map<String, Identifier> iconIdCache = BrowserSafeState.map();
    private final Map<String, Identifier> defaultIconIds = BrowserSafeState.map();

    public DesktopServerIconProvider(Path applicationDir) {
        this.cacheDir = applicationDir == null ? null : AppStoragePaths.cache(applicationDir).resolve("icons");
        this.customizationDir = applicationDir == null ? null : AppStoragePaths.data(applicationDir).resolve("icon-selections");
        if (this.cacheDir != null) this.cacheDir.toFile().mkdirs();
    }

    @Override
    public void setDefaultIcons(Map<String, Identifier> icons) {
        defaultIconIds.clear();
        if (icons != null) {
            defaultIconIds.putAll(icons);
        }
    }

    @Override
    public Identifier getIconId(Object server) {
        return getIconId(asInstance(server));
    }

    private Identifier getIconId(Instance instance) {
        if (instance == null || cacheDir == null) {
            return getDefaultIconId(instance);
        }
        String instanceKey = getInstanceUniqueId(instance);
        Identifier cached = iconIdCache.get(instanceKey);
        if (cached != null) {
            return cached;
        }
        Identifier cachedIcon = loadFromCache(instance);
        if (cachedIcon != null) {
            return cachedIcon;
        }
        Identifier instanceIcon = loadFromInstance(instance);
        return instanceIcon != null ? instanceIcon : getDefaultIconId(instance);
    }

    @Override
    public Identifier getQuickIconId(Object server) {
        return getQuickIconId(asInstance(server));
    }

    private Identifier getQuickIconId(Instance instance) {
        if (instance == null) {
            return getDefaultIconId(null);
        }
        Identifier cached = iconIdCache.get(getInstanceUniqueId(instance));
        return cached == null ? getDefaultIconId(instance) : cached;
    }

    @Override
    public Customization getCustomization(Object server) {
        Instance instance = asInstance(server);
        StoredCustomization stored = loadCustomization(instance);
        if (stored == null) return ServerIconProvider.super.getCustomization(server);
        if (!stored.libraryId().isBlank()) {
            ImportedIconLibrary.Entry imported = ImportedIconLibrary.find(stored.libraryId());
            if (imported == null) return ServerIconProvider.super.getCustomization(server);
            Identifier image = ScreenManager.getInstance().imageAssets().registerRemoteImage(imported.source());
            return image == null ? ServerIconProvider.super.getCustomization(server)
                    : new Customization(image, stored.tint(), image, imported.source(), imported.id());
        }
        return new Customization(stored.image(), stored.tint(), stored.image(), "", "");
    }

    @Override
    public Identifier getLogicalIconId(String software, String loader) {
        return getDefaultIconId(software, loader);
    }

    @Override
    public void loadIconIdAsync(Object server, Consumer<Identifier> onLoaded) {
        Instance instance = asInstance(server);
        if (onLoaded == null) {
            return;
        }
        if (instance == null || cacheDir == null) {
            ScreenManager.getInstance().execute(() -> onLoaded.accept(getDefaultIconId(instance)));
            return;
        }
        Identifier cached = iconIdCache.get(getInstanceUniqueId(instance));
        if (cached != null) {
            ScreenManager.getInstance().execute(() -> onLoaded.accept(cached));
            return;
        }
        AsyncTools.run(TaskSchedulers.current(), () -> {
            try {
                Identifier cachedIcon = loadFromCache(instance);
                if (cachedIcon != null) {
                    ScreenManager.getInstance().execute(() -> onLoaded.accept(cachedIcon));
                    return;
                }
                Identifier instanceIcon = loadFromInstance(instance);
                if (instanceIcon != null) {
                    ScreenManager.getInstance().execute(() -> onLoaded.accept(instanceIcon));
                    return;
                }
                ScreenManager.getInstance().execute(() -> onLoaded.accept(getDefaultIconId(instance)));
            } catch (Exception exception) {
                devPrint("Failed to load icon: " + exception.getMessage());
                ScreenManager.getInstance().execute(() -> onLoaded.accept(getDefaultIconId(instance)));
            }
        });
    }

    @Override
    public void loadRemoteIconAsync(Object server, Runnable onComplete) {
        Instance instance = asInstance(server);
        if (instance == null || cacheDir == null) {
            if (onComplete != null) {
                ScreenManager.getInstance().execute(onComplete);
            }
            return;
        }
        AsyncTools.run(TaskSchedulers.current(), () -> {
            String instanceKey = getInstanceUniqueId(instance);
            boolean loaded = false;
            try {
                BackendConfig backendConfig = instance.getBackendConfig();
                if (backendConfig == null || "LOCAL".equalsIgnoreCase(backendConfig.type)) {
                    return;
                }
                if (!remoteIconsLoaded.add(instanceKey)) {
                    return;
                }
                Path tempDir = Files.createTempDirectory("icon_load");
                try {
                    for (String candidate : List.of("icon.png", "server-icon.png")) {
                        Path candidatePath = tempDir.resolve(candidate);
                        try {
                            RebaseApiFactory.get(instance)
                                    .download(List.of(Path.of(instance.getPath(), candidate)), tempDir)
                                    .join();
                            if (!Files.exists(candidatePath)) {
                                continue;
                            }
                            BufferedImage icon = ImageIO.read(candidatePath.toFile());
                            if (icon != null) {
                                saveToCache(instance, icon);
                                loaded = true;
                                if (onComplete != null) {
                                    ScreenManager.getInstance().execute(onComplete);
                                }
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } finally {
                    deleteDirectoryQuietly(tempDir);
                }
            } catch (Exception ignored) {
            } finally {
                if (!loaded) {
                    remoteIconsLoaded.remove(instanceKey);
                }
            }
        });
    }

    @Override
    public Optional<?> resolveIconPath(Object server, boolean loadRemote, Runnable onLoaded) {
        return resolveIconPath(asInstance(server), loadRemote, onLoaded);
    }

    private Optional<Path> resolveIconPath(Instance instance, boolean loadRemote, Runnable onLoaded) {
        if (instance == null || cacheDir == null) {
            return Optional.empty();
        }
        Optional<Path> cached = resolveCachedIconPath(instance);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Path> local = resolveLocalIconPath(instance);
        if (local.isPresent()) {
            return local;
        }
        BackendConfig backendConfig = instance.getBackendConfig();
        if (loadRemote && backendConfig != null && !"LOCAL".equalsIgnoreCase(backendConfig.type)) {
            loadRemoteIconAsync(instance, onLoaded);
        }
        return Optional.empty();
    }

    @Override
    public Async<Void> customizeIcon(Object server, Object remoteHost, Identifier iconId, Runnable onComplete) {
        return customizeIcon(asInstance(server), remoteHost instanceof RemoteHost host ? host : null, iconId, onComplete);
    }

    @Override
    public Async<Void> customizeIcon(Object server, Object remoteHost, Customization customization, Runnable onComplete) {
        if (customization == null || customization.rendered() == null) {
            return Async.failed(new IllegalArgumentException("Server Icon Is Unavailable"));
        }
        Instance instance = asInstance(server);
        RemoteHost host = remoteHost instanceof RemoteHost value ? value : null;
        return customizeIcon(instance, host, customization.rendered(), null).thenApply(ignored -> {
            try {
                saveCustomization(instance, customization);
            } catch (IOException exception) {
                throw new IllegalStateException("Could Not Save Icon Selection", exception);
            }
            if (onComplete != null) onComplete.run();
            return null;
        });
    }

    private Async<Void> customizeIcon(Instance instance, RemoteHost remoteHost, Identifier iconId, Runnable onComplete) {
        if (instance == null || cacheDir == null) {
            showErrorNotification("Icon Unavailable", "Server Icons Are Unavailable");
            return Async.failed(new IllegalStateException("Server Icons Are Unavailable"));
        }
        BufferedImage icon = ResourceManager.getInstance().resolveImage(iconId);
        if (icon == null) {
            showErrorNotification("Error", "Failed to resolve icon.");
            return Async.failed(new IllegalArgumentException("Failed To Resolve Icon"));
        }
        icon = centerCrop(icon);
        try {
            saveToCache(instance, icon);
            remoteIconsLoaded.remove(getInstanceUniqueId(instance));
            BackendConfig backendConfig = instance.getBackendConfig();
            if (backendConfig == null || "LOCAL".equalsIgnoreCase(backendConfig.type)) {
                File iconFile = new File(instance.getPath(), "icon.png");
                iconFile.getParentFile().mkdirs();
                ImageIO.write(icon, "png", iconFile);
                showSuccessNotification("Icon Updated", "Custom icon set.");
                if (onComplete != null) {
                    onComplete.run();
                }
                return Async.completed(null);
            } else {
                Async<Void> result = Async.pending();
                uploadToRemote(instance, icon).whenComplete((success, failure) -> ScreenManager.getInstance().execute(() -> {
                    if (failure != null) {
                        showErrorNotification("Upload Error", failure.getMessage());
                        result.fail(failure);
                    } else if (!Boolean.TRUE.equals(success)) {
                        String message = "Failed to upload icon to remote server.";
                        showErrorNotification("Upload Failed", message);
                        result.fail(new IllegalStateException(message));
                    } else {
                        showSuccessNotification("Icon Updated", "Custom icon set.");
                        result.complete(null);
                    }
                    if (onComplete != null) onComplete.run();
                }));
                return result;
            }
        } catch (IOException exception) {
            showErrorNotification("Error", "Failed to save icon: " + exception.getMessage());
            return Async.failed(exception);
        }
    }

    @Override
    public void clearCache(Object server) {
        clearCache(asInstance(server));
    }

    private void clearCache(Instance instance) {
        if (instance == null) {
            return;
        }
        try {
            File cacheFile = getCachePath(instance);
            if (cacheFile != null && cacheFile.exists()) {
                cacheFile.delete();
            }
            String instanceKey = getInstanceUniqueId(instance);
            releaseImageId(iconIdCache.remove(instanceKey));
            remoteIconsLoaded.remove(instanceKey);
        } catch (Exception exception) {
            devPrint("Failed to clear cache: " + exception.getMessage());
        }
    }

    @Override
    public void clearAllRemoteTracking() {
        remoteIconsLoaded.clear();
    }

    private File getCachePath(Instance instance) {
        return cacheDir == null ? null : new File(cacheDir.toFile(), getInstanceUniqueId(instance) + ".png");
    }

    private static BufferedImage centerCrop(BufferedImage source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth == 64 && sourceHeight == 64) return source;
        int size = Math.min(sourceWidth, sourceHeight);
        BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int left = (sourceWidth - size) / 2;
            int top = (sourceHeight - size) / 2;
            graphics.drawImage(source, 0, 0, 64, 64, left, top, left + size, top + size, null);
        } finally {
            graphics.dispose();
        }
        return icon;
    }

    private Path getCustomizationPath(Instance instance) {
        return customizationDir == null || instance == null
                ? null
                : customizationDir.resolve(getInstanceUniqueId(instance) + ".properties");
    }

    private StoredCustomization loadCustomization(Instance instance) {
        Path path = getCustomizationPath(instance);
        if (path == null || !Files.isRegularFile(path)) return null;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            Identifier image = new Identifier(
                    properties.getProperty("namespace", ""),
                    properties.getProperty("path", ""),
                    Identifier.Type.valueOf(properties.getProperty("type", "ICON")));
            int tint = Integer.parseInt(properties.getProperty("tint", Integer.toString(ORIGINAL_TINT)));
            return new StoredCustomization(image, tint, properties.getProperty("libraryId", ""));
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            return null;
        }
    }

    private void saveCustomization(Instance instance, Customization customization) throws IOException {
        Path path = getCustomizationPath(instance);
        if (path == null) throw new IOException("Server icon selection is unavailable");
        Properties properties = new Properties();
        properties.setProperty("namespace", customization.image().namespace());
        properties.setProperty("path", customization.image().path());
        properties.setProperty("type", customization.image().type().name());
        properties.setProperty("tint", Integer.toString(customization.tint()));
        properties.setProperty("libraryId", customization.libraryId());
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".server-icon-selection-", ".properties");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, null);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Optional<Path> resolveCachedIconPath(Instance instance) {
        try {
            File cacheFile = getCachePath(instance);
            if (cacheFile != null && cacheFile.exists() && cacheFile.isFile()) {
                return Optional.of(cacheFile.toPath());
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private Optional<Path> resolveLocalIconPath(Instance instance) {
        try {
            BackendConfig backendConfig = instance.getBackendConfig();
            if (backendConfig != null && !"LOCAL".equalsIgnoreCase(backendConfig.type)) {
                return Optional.empty();
            }
            for (String candidate : List.of("icon.png", "server-icon.png")) {
                Path path = Path.of(instance.getPath(), candidate);
                if (Files.isRegularFile(path) && Files.size(path) > 0) {
                    return Optional.of(path);
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private String getInstanceUniqueId(Instance instance) {
        BackendConfig config = instance == null ? null : instance.getBackendConfig();
        String backendType = config == null || config.type == null ? "local" : config.type.toLowerCase(Locale.ROOT);
        Map<String, String> credentials = config != null && config.credentials != null ? config.credentials : Collections.emptyMap();
        String identity = switch (backendType) {
            case "restudio" -> credentials.getOrDefault("identifier", "unknown");
            case "ptero", "calagopus" -> credentials.getOrDefault("hostId", credentials.getOrDefault("host", "unknown")) + "|" + credentials.getOrDefault("identifier", "unknown");
            case "local" -> "local";
            default -> credentials.getOrDefault("host", credentials.getOrDefault("hostId", "unknown"));
        };
        String path = instance == null || instance.getPath() == null ? "" : instance.getPath();
        return backendType + "_" + stableHash(identity + "|" + path);
    }

    private String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < Math.min(12, bytes.length); i++) {
                result.append(String.format("%02x", bytes[i]));
            }
            return result.toString();
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private Identifier loadFromCache(Instance instance) {
        try {
            File cacheFile = getCachePath(instance);
            if (cacheFile != null && cacheFile.exists()) {
                BufferedImage icon = ImageIO.read(cacheFile);
                if (icon != null) {
                    return cacheIcon(instance, icon);
                }
            }
        } catch (IOException exception) {
            devPrint("Failed to load from cache: " + exception.getMessage());
        }
        return null;
    }

    private Identifier loadFromInstance(Instance instance) {
        try {
            BackendConfig backendConfig = instance.getBackendConfig();
            if (backendConfig == null || "LOCAL".equalsIgnoreCase(backendConfig.type)) {
                File iconFile = new File(instance.getPath(), "icon.png");
                if (iconFile.exists() && iconFile.isFile()) {
                    BufferedImage icon = ImageIO.read(iconFile);
                    if (icon != null) {
                        return cacheIcon(instance, icon);
                    }
                }
            }
        } catch (Exception exception) {
            devPrint("Failed to load from instance: " + exception.getMessage());
        }
        return null;
    }

    private void saveToCache(Instance instance, BufferedImage icon) throws IOException {
        File cacheFile = getCachePath(instance);
        if (cacheFile == null) {
            cacheIcon(instance, icon);
            return;
        }
        cacheFile.getParentFile().mkdirs();
        ImageIO.write(icon, "png", cacheFile);
        cacheIcon(instance, icon);
    }

    private Identifier getDefaultIconId(Instance instance) {
        if (instance == null) {
            return getDefaultIconId("", "");
        }
        String software = instance.getServerSoftwareType();
        String loader = instance.getModLoader() == null ? "unknown" : instance.getModLoader().name();
        return getDefaultIconId(software, loader);
    }

    private Identifier getDefaultIconId(String software, String loader) {
        String normalizedSoftware = software == null ? "" : software.trim().toLowerCase(Locale.ROOT);
        String normalizedLoader = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        String key = normalizedSoftware.isBlank() ? normalizedLoader : normalizedSoftware;
        Identifier configured = defaultIconIds.get(key);
        if (configured != null) {
            return configured;
        }
        if (SOFTWARE_ICONS.contains(key)) {
            return Identifier.icon(key + ".png");
        }
        return defaultIconIds.getOrDefault("unknown", Identifier.icon("unknown.png"));
    }

    private Identifier cacheIcon(Instance instance, BufferedImage icon) {
        String instanceKey = getInstanceUniqueId(instance);
        releaseImageId(iconIdCache.remove(instanceKey));
        Identifier id = ResourceManager.getInstance().registerImage(Identifier.generatedImage("remotely", "server-icons/instance/" + safeIconKey(instanceKey)), icon);
        iconIdCache.put(instanceKey, id);
        return id;
    }

    private String safeIconKey(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void releaseImageId(Identifier id) {
        if (id != null) {
            ResourceManager.getInstance().releaseImage(id);
        }
    }

    private Async<Boolean> uploadToRemote(Instance instance, BufferedImage icon) {
        return AsyncTools.supply(TaskSchedulers.current(), () -> {
            try {
                Path tempDir = Files.createTempDirectory("icon_upload");
                Path tempFile = tempDir.resolve("icon.png");
                Path serverIconFile = tempDir.resolve("server-icon.png");
                try {
                    ImageIO.write(icon, "png", tempFile.toFile());
                    Files.copy(tempFile, serverIconFile, StandardCopyOption.REPLACE_EXISTING);
                    RebaseApiFactory.get(instance).upload(List.of(tempFile, serverIconFile), Path.of(instance.getPath())).join();
                    return true;
                } finally {
                    deleteDirectoryQuietly(tempDir);
                }
            } catch (Exception exception) {
                devPrint("Failed to upload icon: " + exception.getMessage());
                return false;
            }
        });
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void showSuccessNotification(String title, String message) {
        ScreenManager.getInstance().execute(() -> new Notification(title, message, Notification.Type.SUCCESS));
    }

    private void showErrorNotification(String title, String message) {
        ScreenManager.getInstance().execute(() -> new Notification(title, message, Notification.Type.ERROR));
    }

    private static Instance asInstance(Object server) {
        return server instanceof Instance instance ? instance : null;
    }

    private record StoredCustomization(Identifier image, int tint, String libraryId) {
    }
}
