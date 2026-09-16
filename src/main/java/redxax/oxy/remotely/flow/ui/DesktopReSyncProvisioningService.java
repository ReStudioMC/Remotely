package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.flow.FlowManager;
import restudio.rebase.Rebase;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.backend.feature.NetworkTransferFeature;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.resource.InstanceResource;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rebase.util.VersionUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class DesktopReSyncProvisioningService {
    public static final int RESYNC_PORT = 12441;
    private static final String RESYNC_RELEASE_METADATA_URL = "https://restudiomc.net/api/releases/resync/latest?channel=stable&platform=universal";
    private static final String RESYNC_RELEASE_URL = "https://restudiomc.net/api/releases/resync/latest/download";
    private static final VersionUtil.VersionComparator RESYNC_VERSION_COMPARATOR = new VersionUtil.VersionComparator();
    private static final HttpClient RESYNC_HTTP_CLIENT = HttpClient.newHttpClient();
    private final SecureRandom secureRandom = new SecureRandom();
    private ReSyncRelease latestReSyncRelease;

    enum StartupStatus {
        LOADING,
        NOT_SUPPORTED,
        SETUP,
        SERVER_STOPPED,
        READY
    }

    record StartupProbeResult(StartupStatus status, boolean updateAvailable, boolean updateChecked) {
        StartupProbeResult(StartupStatus status, boolean updateAvailable) {
            this(status, updateAvailable, true);
        }
    }

    public record OperationResult(boolean success, String failureMessage) {
        static OperationResult successful() {
            return new OperationResult(true, "");
        }

        static OperationResult failed() {
            return new OperationResult(false, "");
        }

        static OperationResult failed(String message) {
            return new OperationResult(false, message == null ? "" : message);
        }
    }

    private record ReSyncRelease(String id, String version, String fileName, String checksum, String changelog) {
    }

    StartupProbeResult computeStartupState(String serverId, ClientServerView startupServer, String loaderHint) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return new StartupProbeResult(StartupStatus.NOT_SUPPORTED, false, false);
        }
        if (manager.isFlowClientReady(serverId)) {
            return new StartupProbeResult(StartupStatus.READY, false, false);
        }
        Boolean pluginCompatible = isPluginCompatible(serverId, startupServer, loaderHint);
        if (Boolean.FALSE.equals(pluginCompatible)) {
            return new StartupProbeResult(StartupStatus.NOT_SUPPORTED, false, false);
        }
        if (startupServer != null) {
            try {
                Boolean pluginPresent = manager.isReSyncPluginInstalled(serverId).join();
                if (Boolean.TRUE.equals(pluginPresent)) {
                    Instance instance = (Instance) manager.findInstanceByServerId(serverId, startupServer);
                    if (instance != null && (instance.getState() == InstanceState.STARTING || instance.getState() == InstanceState.INSTALLING)) {
                        return new StartupProbeResult(StartupStatus.LOADING, false, false);
                    }
                    if (instance != null && instance.getState() != InstanceState.RUNNING
                        && instance.getState() != InstanceState.SAVING && instance.getState() != InstanceState.SAVED) {
                        return new StartupProbeResult(StartupStatus.SERVER_STOPPED, false, false);
                    }
                    return new StartupProbeResult(StartupStatus.LOADING, false, false);
                }
            } catch (Exception ignored) {
            }
            return new StartupProbeResult(StartupStatus.SETUP, false);
        }
        Instance instance = (Instance) manager.getInstanceByServerId(serverId);
        boolean isRunning = instance != null && (instance.getState() == InstanceState.RUNNING
            || instance.getState() == InstanceState.SAVING || instance.getState() == InstanceState.SAVED);
        if (instance != null && isReSyncResourcePresent(instance)) {
            if (!isRunning) {
                return new StartupProbeResult(StartupStatus.SERVER_STOPPED, false, false);
            }
            if (manager.getFlowAvailabilityIssue(serverId, null) != null) {
                return new StartupProbeResult(StartupStatus.SETUP, false, false);
            }
            return new StartupProbeResult(StartupStatus.LOADING, false, false);
        }
        if (manager.isFlowClientReady(serverId)) {
            return new StartupProbeResult(StartupStatus.READY, false, false);
        }
        return new StartupProbeResult(StartupStatus.SETUP, false);
    }

    boolean isReSyncUpdateAvailable(String serverId, ClientServerView startupServer) {
        if (isReStudioTarget(serverId, startupServer)) {
            return isReSyncUpdateAvailableForReStudio(serverId);
        }
        FlowManager manager = FlowManager.getInstance();
        Instance instance = manager != null ? (Instance) manager.getInstanceByServerId(serverId) : null;
        return instance != null && isReSyncResourcePresent(instance) && isReSyncUpdateAvailable(instance);
    }

    public OperationResult setup(String serverId, ClientServerView startupServer) {
        try {
            if (isReStudioTarget(serverId, startupServer)) {
                return setupForReStudio(serverId) ? OperationResult.successful() : OperationResult.failed();
            }
            return setupForNonReStudio(serverId);
        } catch (Exception error) {
            return OperationResult.failed(error.getMessage() == null || error.getMessage().isBlank() ? "Setup Failed" : error.getMessage());
        }
    }

    public OperationResult update(String serverId, ClientServerView startupServer) {
        try {
            if (isReStudioTarget(serverId, startupServer)) {
                return updateForReStudio(serverId) ? OperationResult.successful() : OperationResult.failed();
            }
            return updateForNonReStudio(serverId);
        } catch (Exception error) {
            return OperationResult.failed(error.getMessage() == null || error.getMessage().isBlank() ? "Update Failed" : error.getMessage());
        }
    }

    void clearReleaseCache() {
        latestReSyncRelease = null;
    }

    public boolean isInstalled(Instance instance) {
        if (instance == null) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return false;
        }
        if (isReStudioTarget(instance.getInstanceId(), null)) {
            try {
                return Boolean.TRUE.equals(manager.isReSyncPluginInstalled(instance.getInstanceId()).join());
            } catch (Exception ignored) {
                return false;
            }
        }
        return isReSyncResourcePresent(instance);
    }

    public OperationResult installLatest(Instance instance) {
        if (instance == null) {
            return OperationResult.failed("Server Not Found");
        }
        if (!isInstalled(instance)) {
            return setup(instance.getInstanceId(), null);
        }
        return isReSyncUpdateAvailable(instance.getInstanceId(), null) ? update(instance.getInstanceId(), null) : OperationResult.successful();
    }

    private Boolean isPluginCompatible(String serverId, ClientServerView startupServer, String loaderHint) {
        FlowManager manager = FlowManager.getInstance();
        Instance instance = manager == null ? null : (Instance) manager.getInstanceByServerId(serverId);
        if (instance != null) {
            String backendType = resolveBackendType(instance);
            if (!instance.isServer()) {
                if ("SSH".equalsIgnoreCase(backendType) || "RESTUDIO".equalsIgnoreCase(backendType)) {
                    return null;
                }
                return false;
            }
            if (instance.supportsPlugins()) {
                return true;
            }
            if (instance.getModLoader() != null) {
                String loaderName = instance.getModLoader().name();
                if (!"VANILLA".equalsIgnoreCase(loaderName)) {
                    return isPluginCompatibleFromLoader(loaderName);
                }
            }
            if (!safeText(loaderHint).isBlank()) {
                return isPluginCompatibleFromLoader(loaderHint);
            }
            if ("SSH".equalsIgnoreCase(backendType)) {
                return null;
            }
            return null;
        }
        if (startupServer != null) {
            if (startupServer.loader == null || startupServer.loader.isBlank()) {
                if (!safeText(loaderHint).isBlank()) {
                    return isPluginCompatibleFromLoader(loaderHint);
                }
                return null;
            }
            return isPluginCompatibleFromLoader(startupServer.loader);
        }
        if (!safeText(loaderHint).isBlank()) {
            return isPluginCompatibleFromLoader(loaderHint);
        }
        return null;
    }

    private String resolveBackendType(Instance instance) {
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().type == null) {
            return "";
        }
        return instance.getBackendConfig().type.trim();
    }

    private boolean isPluginCompatibleFromLoader(String loader) {
        String normalized = safeText(loader).trim().toUpperCase(Locale.ROOT);
        return normalized.equals("PAPER")
            || normalized.equals("FOLIA")
            || normalized.equals("SPIGOT")
            || normalized.equals("BUKKIT")
            || normalized.equals("PURPUR")
            || normalized.equals("LEAF")
            || normalized.equals("VELOCITY")
            || normalized.equals("WATERFALL")
            || normalized.equals("BUNGEECORD");
    }

    private boolean isReSyncResourcePresent(Instance instance) {
        return findReSyncResource(instance) != null;
    }

    private InstanceResource findReSyncResource(Instance instance) {
        try {
            List<InstanceResource> resources = Rebase.get().getResourceManager().getResources(instance).get(15, TimeUnit.SECONDS);
            for (InstanceResource resource : resources) {
                if (resource == null) {
                    continue;
                }
                String name = safeText(resource.getName()).toLowerCase(Locale.ROOT);
                if ("resync".equals(name)) {
                    return resource;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isReSyncUpdateAvailable(Instance instance) {
        ReSyncRelease latest = fetchLatestReSyncRelease();
        if (latest == null || latest.version().isBlank()) {
            return false;
        }
        InstanceResource resource = findReSyncResource(instance);
        if (resource == null) {
            return false;
        }
        String installedVersion = safeText(resource.getVersion()).trim();
        if (installedVersion.isBlank() || "N/A".equalsIgnoreCase(installedVersion)) {
            return false;
        }
        return RESYNC_VERSION_COMPARATOR.compare(latest.version(), installedVersion) > 0;
    }

    private boolean isReSyncUpdateAvailableForReStudio(String serverId) {
        ReSyncRelease latest = fetchLatestReSyncRelease();
        if (latest == null || latest.version().isBlank()) {
            return false;
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return false;
        }
        try {
            String installedVersion = safeText(manager.getReSyncVersionForReStudioServer(serverId).join()).trim();
            if (installedVersion.isBlank()) {
                return false;
            }
            return RESYNC_VERSION_COMPARATOR.compare(latest.version(), installedVersion) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private ReSyncRelease fetchLatestReSyncRelease() {
        if (latestReSyncRelease != null) {
            return latestReSyncRelease;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RESYNC_RELEASE_METADATA_URL))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
            HttpResponse<String> response = RESYNC_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().isBlank()) {
                return null;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            latestReSyncRelease = new ReSyncRelease(
                jsonString(json, "id"),
                jsonString(json, "version"),
                jsonString(json, "fileName"),
                jsonString(json, "checksum"),
                jsonString(json, "changelog")
            );
            return latestReSyncRelease;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String jsonString(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }

    private boolean setupForReStudio(String serverId) throws Exception {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null || serverId.isBlank()) {
            return false;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        manager.provisionReSyncForReStudioServer(serverId, future::complete);
        Boolean result = future.get(90, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    private boolean updateForReStudio(String serverId) throws Exception {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId == null || serverId.isBlank()) {
            return false;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        manager.updateReSyncForReStudioServer(serverId, future::complete);
        Boolean result = future.get(90, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    private OperationResult updateForNonReStudio(String serverId) throws Exception {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return OperationResult.failed();
        }
        Instance instance = (Instance) manager.getInstanceByServerId(serverId);
        if (instance == null) {
            return OperationResult.failed("Server Not Found");
        }
        ServerBackend backend = instance.getBackend();
        if (backend == null) {
            return OperationResult.failed();
        }
        NetworkTransferFeature transfer = backend.getFeature(NetworkTransferFeature.class).orElse(null);
        if (transfer == null) {
            return OperationResult.failed("Network Transfer Missing");
        }
        FileSystemProvider fileSystem = backend.getFileSystem();
        if (fileSystem == null) {
            return OperationResult.failed();
        }
        ReSyncRelease release = fetchLatestReSyncRelease();
        if (release == null || release.version().isBlank()) {
            return OperationResult.failed("Release Not Found");
        }

        Path serverPath = Path.of(instance.getPath());
        Path pluginsPath = serverPath.resolve(resolvePluginsDirectory(instance));
        Path reSyncJarPath = pluginsPath.resolve("ReSync.jar");
        ensureDirectory(fileSystem, pluginsPath);
        deleteOldReSyncJars(instance, fileSystem, reSyncJarPath);
        transfer.downloadFile(RESYNC_RELEASE_URL, reSyncJarPath, null).get(90, TimeUnit.SECONDS);
        verifyLocalReSyncChecksum(instance, reSyncJarPath, release);
        registerReSyncResource(instance, reSyncJarPath);
        return OperationResult.successful();
    }

    private OperationResult setupForNonReStudio(String serverId) throws Exception {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return OperationResult.failed();
        }
        Instance instance = (Instance) manager.getInstanceByServerId(serverId);
        if (instance == null) {
            return OperationResult.failed("Server Not Found");
        }
        ServerBackend backend = instance.getBackend();
        if (backend == null) {
            return OperationResult.failed();
        }
        NetworkTransferFeature transfer = backend.getFeature(NetworkTransferFeature.class).orElse(null);
        if (transfer == null) {
            return OperationResult.failed("Network Transfer Missing");
        }
        FileSystemProvider fileSystem = backend.getFileSystem();
        if (fileSystem == null) {
            return OperationResult.failed();
        }

        Path serverPath = Path.of(instance.getPath());
        Path pluginsPath = serverPath.resolve(resolvePluginsDirectory(instance));
        Path reSyncJarPath = pluginsPath.resolve("ReSync.jar");
        ensureDirectory(fileSystem, pluginsPath);
        transfer.downloadFile(RESYNC_RELEASE_URL, reSyncJarPath, null).get(90, TimeUnit.SECONDS);
        registerReSyncResource(instance, reSyncJarPath);

        Path configDir = pluginsPath.resolve("ReSync");
        ensureDirectory(fileSystem, configDir);
        String apiKey = generateApiKey();
        boolean localBackend = instance.getBackendConfig() != null && "LOCAL".equalsIgnoreCase(instance.getBackendConfig().type);
        String bindHost = localBackend ? "127.0.0.1" : "0.0.0.0";
        String publicBindEnabled = Boolean.toString(!localBackend);
        String configText = "port=" + RESYNC_PORT + "\n"
            + "api-key=" + apiKey + "\n"
            + "bind-host=" + bindHost + "\n"
            + "public-bind-enabled=" + publicBindEnabled + "\n";
        fileSystem.write(configDir.resolve("config.properties"), configText).get(30, TimeUnit.SECONDS);

        BackendConfig backendConfig = instance.getBackendConfig();
        if (backendConfig != null) {
            if (backendConfig.credentials == null) {
                backendConfig.credentials = new HashMap<>();
            }
            backendConfig.credentials.put("resyncEnabled", "true");
            instance.save();
        }
        return OperationResult.successful();
    }

    private void registerReSyncResource(Instance instance, Path reSyncJarPath) {
        if (instance == null || reSyncJarPath == null) {
            return;
        }
        try {
            boolean remoteBackend = instance.getBackendConfig() != null && !"LOCAL".equalsIgnoreCase(instance.getBackendConfig().type);
            if (remoteBackend) {
                Rebase.get().getResourceManager().invalidateCache(instance);
                Rebase.get().getResourceManager().getResources(instance).get(30, TimeUnit.SECONDS);
                return;
            }
            Rebase.get().getResourceManager().invalidateCache(instance);
            Rebase.get().getResourceManager().loadResource(instance, reSyncJarPath).get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            Rebase.get().getResourceManager().invalidateCache(instance);
        }
    }

    private String generateApiKey() {
        byte[] key = new byte[32];
        secureRandom.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private String resolvePluginsDirectory(Instance instance) {
        if (instance == null) {
            return "plugins";
        }
        if (instance.supportsPlugins() || instance.shouldInstallModsAsPlugins()) {
            return "plugins";
        }
        return "plugins";
    }

    private void deleteOldReSyncJars(Instance instance, FileSystemProvider fileSystem, Path targetPath) throws Exception {
        InstanceResource resource = findReSyncResource(instance);
        if (resource == null || resource.getPath() == null || resource.getPath().equals(targetPath)) {
            return;
        }
        fileSystem.delete(List.of(resource.getPath())).get(30, TimeUnit.SECONDS);
    }

    private void verifyLocalReSyncChecksum(Instance instance, Path reSyncJarPath, ReSyncRelease release) throws Exception {
        if (instance == null || instance.getBackendConfig() == null || !"LOCAL".equalsIgnoreCase(instance.getBackendConfig().type)) {
            return;
        }
        String expected = safeText(release.checksum()).trim().toLowerCase(Locale.ROOT);
        if (expected.isBlank() || expected.length() != 64 || !Files.exists(reSyncJarPath)) {
            return;
        }
        String actual = sha256(reSyncJarPath);
        if (!expected.equals(actual)) {
            throw new IOException("Checksum Verification Failed");
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(path));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private void ensureDirectory(FileSystemProvider fileSystem, Path path) throws Exception {
        Boolean exists = fileSystem.exists(path).get(20, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        fileSystem.createDirectory(path).get(30, TimeUnit.SECONDS);
    }

    private boolean isReStudioTarget(String serverId, ClientServerView startupServer) {
        FlowManager manager = FlowManager.getInstance();
        Instance instance = manager == null ? null : (Instance) manager.findInstanceByServerId(serverId, startupServer);
        if (instance != null) {
            BackendConfig backendConfig = instance.getBackendConfig();
            return backendConfig != null && "RESTUDIO".equalsIgnoreCase(safeText(backendConfig.type));
        }
        return startupServer != null && "RESTUDIO".equalsIgnoreCase(safeText(startupServer.backendType));
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
