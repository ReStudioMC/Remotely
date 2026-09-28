package redxax.oxy.remotely;

import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.config.DesktopRemotelyConfigManager;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.DesktopServerSettingsRegistry;
import restudio.rebase.backend.impl.ReStudioBackend;
import restudio.rebase.backend.impl.CalagopusBackend;
import restudio.rebase.update.ApplicationUpdateManager;
import restudio.rebase.IRebaseManager;
import restudio.rebase.account.AccountManager;
import restudio.rebase.backup.BackupManager;
import restudio.rebase.cache.CacheManager;
import restudio.rebase.config.RebaseConfigManager;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.java.JavaManager;
import restudio.rebase.preset.OptionsPresetManager;
import restudio.rebase.preset.ResourceListManager;
import restudio.rebase.resource.InstanceResourceManager;
import restudio.rebase.resource.ResourceMetadataManager;
import restudio.rebase.resource.ResourceStateManager;
import restudio.rebase.resource.UpdateManager;
import restudio.rebase.resource.provider.*;

import restudio.rebase.restudio.ReStudio;
import restudio.rebase.twin.ServerTwinManager;
import restudio.rebase.update.UpdateAvailablePopup;
import restudio.rebase.util.PlaytimeManager;
import redxax.oxy.remotely.DesktopRemotelyPaths;
import restudio.rebase.instance.loaders.FabricHandler;
import restudio.rebase.instance.loaders.ForgeHandler;
import restudio.rebase.instance.loaders.NeoForgeHandler;
import restudio.rebase.instance.loaders.QuiltHandler;
import restudio.rebase.instance.loaders.ModLoader;
import restudio.rebase.instance.loaders.ModLoaderHandler;
import restudio.rebase.instance.loaders.ModLoaderVersion;
import restudio.rebase.minecraft.GameVersion;
import restudio.rebase.backend.BackendFactory;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.impl.LocalBackend;
import restudio.rebase.backend.impl.PteroBackend;
import restudio.rebase.backend.impl.SshBackend;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RemotelyManager implements IRebaseManager {
    private final InstanceManager instanceManager;
    private final RemotelyConfigManager configManager;
    private final BackupManager backupManager;
    private final CacheManager cacheManager;
    private final JavaManager javaManager;
    private final OptionsPresetManager optionsPresetManager;
    private final PlaytimeManager playtimeManager;
    private final ResourceListManager resourceListManager;
    private final ResourceMetadataManager resourceMetadataManager;
    private final ResourceStateManager resourceStateManager;
    private final InstanceResourceManager instanceResourceManager;
    private DesktopServerSettingsRegistry desktopServerSettingsRegistry;

    private final UpdateManager updateManager;
    private final ApplicationUpdateManager applicationUpdateManager;
    private final ServerTwinManager twinManager;
    private final List<IResourceProvider> resourceProviders;
    private final Path versionsDir;
    private final Map<ModLoader, ModLoaderHandler> modLoaderHandlers = new HashMap<>();
    private final Map<String, GameVersion> allGameVersions = BrowserSafeState.map();
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public RemotelyManager() {
        this(DesktopRemotelyPaths.appDir());
    }

    public RemotelyManager(Path applicationDir) {
        this(applicationDir, new DesktopRemotelyConfigManager(applicationDir));
    }

    public RemotelyManager(Path applicationDir, RemotelyConfigManager configManager) {
        applicationDir = Objects.requireNonNull(applicationDir, "applicationDir");
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.configManager.apply();

        this.instanceManager = InstanceManager.getInstance();
        this.backupManager = new BackupManager(applicationDir);
        this.cacheManager = new CacheManager(applicationDir);
        this.javaManager = new JavaManager(applicationDir, DesktopRemotelyPaths.legacyAppDir());
        this.optionsPresetManager = new OptionsPresetManager(applicationDir);
        this.playtimeManager = new PlaytimeManager(applicationDir);
        this.resourceListManager = new ResourceListManager(applicationDir);
        this.resourceMetadataManager = new ResourceMetadataManager(applicationDir);
        this.resourceStateManager = new ResourceStateManager();
        this.applicationUpdateManager = new ApplicationUpdateManager(applicationDir, this, "remotely", "remotely-dev");
        this.twinManager = new ServerTwinManager(applicationDir);


        this.resourceProviders = List.of(
                new ModrinthProvider(),
                new CurseForgeProvider(),
                new HangarProvider(),
                new SpigetProvider()
        );

        this.instanceResourceManager = new InstanceResourceManager(resourceMetadataManager, cacheManager, resourceProviders, resourceStateManager);
        this.updateManager = new UpdateManager(applicationDir);
        this.versionsDir = DesktopRemotelyPaths.minecraftVersionsDir();
        try { Files.createDirectories(this.versionsDir); } catch (IOException ignored) {}
        modLoaderHandlers.put(ModLoader.FABRIC, new FabricHandler(applicationDir));
        modLoaderHandlers.put(ModLoader.QUILT, new QuiltHandler(applicationDir));
        modLoaderHandlers.put(ModLoader.FORGE, new ForgeHandler(applicationDir));
        modLoaderHandlers.put(ModLoader.NEOFORGE, new NeoForgeHandler(applicationDir));
        init();
    }

    private void init() {
        javaManager.refreshRuntimes();
        BackendFactory.register("LOCAL", (cfg, inst) -> new LocalBackend(cfg != null ? cfg : new BackendConfig("LOCAL", new HashMap<>()), inst));
        BackendFactory.register("SSH", SshBackend::new);
        BackendFactory.register("PTERO", PteroBackend::new);
        BackendFactory.register("CALAGOPUS", CalagopusBackend::new);
        BackendFactory.register("RESTUDIO", ReStudioBackend::new);
        desktopServerSettingsRegistry = new DesktopServerSettingsRegistry(
                ServerSettingsRegistry.getInstance(),
                configManager.getApplicationDir().resolve("server-settings")
        );
    }

    private Async<JsonObject> loadRemoteManifest() {
        Path manifestCachePath = cacheManager.getCacheDir().resolve("manifests").resolve("version_manifest_v2.json");
        return JvmAsyncBridge.fromFuture(cacheManager.getOrFetchJson(VERSION_MANIFEST_URL, manifestCachePath, Duration.ofHours(24), JsonObject.class));
    }

    private void parseRemoteManifest(JsonObject manifest) {
        JsonArray versionsArray = manifest.getAsJsonArray("versions");
        for (JsonElement element : versionsArray) {
            JsonObject versionObj = element.getAsJsonObject();
            String id = versionObj.get("id").getAsString();
            String type = versionObj.get("type").getAsString();
            String url = versionObj.get("url").getAsString();
            GameVersion remoteVanillaVersion = new GameVersion(id, type, id, ModLoader.VANILLA, null, versionsDir.resolve(id));
            remoteVanillaVersion.setRemoteUrl(url);
            if (versionObj.has("releaseTime")) {
                remoteVanillaVersion.setReleaseTime(versionObj.get("releaseTime").getAsString());
            }
            allGameVersions.putIfAbsent(id, remoteVanillaVersion);
        }
    }

    private void loadLocalVersions() {
        try (Stream<Path> stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String versionId = dir.getFileName().toString();
                Path jsonFile = dir.resolve(versionId + ".json");
                if (Files.exists(jsonFile)) {
                    try {
                        String content = Files.readString(jsonFile);
                        JsonObject versionData = JsonParser.parseString(content).getAsJsonObject();
                        String id = versionData.get("id").getAsString();
                        String type = versionData.get("type").getAsString();
                        String inheritsFrom = versionData.has("inheritsFrom") ? versionData.get("inheritsFrom").getAsString() : id;

                        ModLoader loader = ModLoader.VANILLA;
                        String loaderVersion = null;
                        if (id.startsWith("fabric-loader-")) {
                            loader = ModLoader.FABRIC;
                            String prefix = "fabric-loader-";
                            String suffix = "-" + inheritsFrom;
                            if (id.startsWith(prefix) && id.endsWith(suffix) && id.length() > prefix.length() + suffix.length()) {
                                loaderVersion = id.substring(prefix.length(), id.length() - suffix.length());
                            } else {
                                String[] parts = id.split("-");
                                if (parts.length >= 4) {
                                    loaderVersion = String.join("-", Arrays.copyOfRange(parts, 2, parts.length - 1));
                                }
                            }
                        } else if (id.startsWith("quilt-loader-")) {
                            loader = ModLoader.QUILT;
                            String prefix = "quilt-loader-";
                            String suffix = "-" + inheritsFrom;
                            if (id.startsWith(prefix) && id.endsWith(suffix) && id.length() > prefix.length() + suffix.length()) {
                                loaderVersion = id.substring(prefix.length(), id.length() - suffix.length());
                            }
                        } else if (id.contains("-forge-")) {
                            loader = ModLoader.FORGE;
                            String[] parts = id.split("-forge-");
                            if (parts.length > 1) {
                                loaderVersion = parts[1];
                            }
                        } else if (id.startsWith("neoforge-") || id.contains("-neoforge-")) {
                            loader = ModLoader.NEOFORGE;
                            if (id.startsWith("neoforge-")) {
                                loaderVersion = id.substring("neoforge-".length());
                            } else {
                                String[] parts = id.split("-neoforge-");
                                if (parts.length > 1) {
                                    loaderVersion = parts[1];
                                }
                            }
                        }

                        GameVersion localVersion = new GameVersion(id, type, inheritsFrom, loader, loaderVersion, dir);
                        localVersion.setRemoteUrl(jsonFile.toUri().toString());

                        if (versionData.has("releaseTime")) {
                            localVersion.setReleaseTime(versionData.get("releaseTime").getAsString());
                        } else {
                            GameVersion parentVersion = allGameVersions.get(inheritsFrom);
                            if (parentVersion != null) {
                                localVersion.setReleaseTime(parentVersion.getReleaseTime());
                            }
                        }

                        GameVersion existingVersion = allGameVersions.get(id);
                        if (localVersion.getReleaseTime() == null && existingVersion != null) {
                            localVersion.setReleaseTime(existingVersion.getReleaseTime());
                        }

                        allGameVersions.put(id, localVersion);
                    } catch (Exception ignored) {}
                }
            });
        } catch (IOException ignored) {}
    }


    @Override
    public InstanceManager getInstanceManager() {
        return instanceManager;
    }

    @Override
    public InstanceResourceManager getResourceManager() {
        return instanceResourceManager;
    }

    @Override
    public ResourceStateManager getResourceStateManager() {
        return resourceStateManager;
    }


    @Override
    public UpdateManager getUpdateManager() {
        return updateManager;
    }

    @Override
    public BackupManager getBackupManager() {
        return backupManager;
    }

    @Override
    public CacheManager getCacheManager() {
        return cacheManager;
    }

    @Override
    public ResourceMetadataManager getResourceMetadataManager() {
        return resourceMetadataManager;
    }

    @Override
    public List<IResourceProvider> getResourceProviders() {
        return resourceProviders;
    }

    @Override
    public IResourceProvider getResourceProvider(String name) {
        return resourceProviders.stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    @Override
    public AccountManager getAccountManager() {
        return null;
    }

    @Override
    public ResourceListManager getResourceListManager() {
        return resourceListManager;
    }

    @Override
    public OptionsPresetManager getOptionsPresetManager() {
        return optionsPresetManager;
    }

    @Override
    public PlaytimeManager getPlaytimeManager() {
        return playtimeManager;
    }

    @Override
    public JavaManager getJavaManager() {
        return javaManager;
    }

    @Override
    public ApplicationUpdateManager getApplicationUpdateManager() {
        return applicationUpdateManager;
    }

    @Override
    public RebaseConfigManager getConfigManager() {
        return configManager;
    }

    @Override
    public ServerTwinManager getTwinManager() {
        return twinManager;
    }

    @Override
    public ReStudio getReStudio() {
        return ReStudio.getInstance();
    }

    @Override
    public Path getLauncherJarPath() throws IOException {
        try {
            return Path.of(RemotelyInit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception error) {
            throw new IOException("Could not determine launcher JAR path", error);
        }
    }

    @Override
    public void log(String message) {
        ReLog.logger(LogTypes.APPLICATION).source(LogSource.application("Remotely")).component(RemotelyManager.class).debug(message);
    }

    @Override
    public String getName() {
        return "Remotely";
    }

    @Override
    public Path getInstancesDir() {
        return instanceManager.getInstancesDir();
    }

    @Override
    public List<GameVersion> getLocalBaseVersions() {
        if (allGameVersions.isEmpty()) {
            JsonObject manifest = loadRemoteManifest().join();
            if (manifest != null) {
                parseRemoteManifest(manifest);
            }
            loadLocalVersions();
        }
        return allGameVersions.values().stream().filter(v -> v.getModLoader() == ModLoader.VANILLA).collect(Collectors.toList());
    }

    @Override
    public List<ModLoaderVersion> getModLoaderVersions(ModLoader loader, String mcVersion) {
        if (loader == null || loader == ModLoader.VANILLA) {
            return new ArrayList<>();
        }
        ModLoaderHandler handler = modLoaderHandlers.get(loader);
        if (handler == null) {
            log("No handler found for mod loader: " + loader);
            return new ArrayList<>();
        }
        return handler.getAvailableVersions(mcVersion).join();
    }
}
