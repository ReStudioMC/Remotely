package redxax.oxy.remotely;

import redxax.oxy.remotely.network.HostedNetworkClient;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceApi;
import redxax.oxy.remotely.ui.server.HostedNetworkOverviewProvider;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import restudio.rescreen.platform.Async;
import restudio.rescreen.util.JsonTreeParser;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.Rebase;
import restudio.rebase.backend.feature.BackupOperations;
import restudio.rebase.minecraft.GameVersion;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.restudio.api.ReStudioApiClient;
import restudio.rebase.restudio.api.models.MarketplaceModels;
import restudio.rebase.restudio.api.models.ReleaseModels;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.resource.ResourcePoolClient;
import restudio.rebase.resource.marketplace.HostedModpackSelection;
import restudio.rebase.schedule.ServerScheduleModels;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class DesktopRemotelyServerApi implements RemotelyServerApi {
    private static final Duration MODPACK_JOB_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DELETION_RECOVERY_TIMEOUT = Duration.ofMinutes(2);
    private static final Gson GSON = new Gson();
    private final ReStudioApiClient delegate;
    private final HostedNetworkClient networks;
    private final Map<String, PendingNetworkMutation> pendingNetworkMutations = new ConcurrentHashMap<>();
    private String networkAccount = "";

    private record ServerDeletionStatus(String serverId, String status, String failedStep) {}
    private record PendingNetworkMutation(String body, String key) {}

    public DesktopRemotelyServerApi(ReStudioApiClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.networks = new HostedNetworkClient(delegate.async()::hostedNetworkRequest);
    }

    @Override
    public HostedNetworkClient hostedNetworks() {
        return networks;
    }

    public Async<List<ServerScreenHost.NetworkView>> hostedNetworkViews() {
        return hostedNetworkValueRequest("GET", "/networks", null).thenApply(value -> {
            if (!value.isJsonArray()) throw new IllegalStateException("Hosted Network List Is Invalid");
            return HostedNetworkOverviewProvider.views(value);
        });
    }

    public Async<JsonObject> hostedNetworkViewRequest(String method, String path, Object body) {
        return hostedNetworkValueRequest(method, path, body).thenApply(value -> {
            if (!value.isJsonObject()) throw new IllegalStateException("Hosted Network Response Is Invalid");
            return value.getAsJsonObject();
        });
    }

    private Async<JsonElement> hostedNetworkValueRequest(String method, String path, Object body) {
        if (path == null || !("/networks".equals(path) || path.startsWith("/networks/"))) {
            return Async.failed(new IllegalArgumentException("Hosted Network Path Is Invalid"));
        }
        ReStudio studio = ReStudio.getInstance();
        if (studio == null || !studio.isAuthenticated() || studio.getApi() != delegate
                || studio.getUserId() == null || studio.getUserId().isBlank() || studio.getSessionId() == null) {
            return Async.failed(new IllegalStateException("Hosted Network Account Is Unavailable"));
        }
        UUID sessionId = studio.getSessionId();
        String userId = studio.getUserId();
        String content = body == null ? null : GSON.toJson(body);
        String mutation = method + " " + path;
        String requestKey = "GET".equals(method) ? null : networkRequestKey(userId + ":" + sessionId, mutation, content);
        return delegate.async().hostedNetworkRequest(method, "/hosted-networks/views" + ("/networks".equals(path) ? "" : path.substring("/networks".length())), content, requestKey)
                .thenApply(response -> {
                    if (ReStudio.getInstance() != studio || !studio.isAuthenticated() || studio.getApi() != delegate
                            || !sessionId.equals(studio.getSessionId()) || !userId.equals(studio.getUserId())) {
                        throw new IllegalStateException("Hosted Network Account Changed");
                    }
                    JsonElement result = JsonTreeParser.parse(response);
                    if (requestKey != null) clearNetworkRequestKey(mutation, requestKey);
                    return result;
                });
    }

    private synchronized String networkRequestKey(String account, String mutation, String body) {
        if (!networkAccount.equals(account)) {
            pendingNetworkMutations.clear();
            networkAccount = account;
        }
        PendingNetworkMutation pending = pendingNetworkMutations.compute(mutation, (ignored, current) ->
                current != null && Objects.equals(current.body(), body) ? current : new PendingNetworkMutation(body, UUID.randomUUID().toString()));
        return pending.key();
    }

    private synchronized void clearNetworkRequestKey(String mutation, String key) {
        PendingNetworkMutation pending = pendingNetworkMutations.get(mutation);
        if (pending != null && pending.key().equals(key)) pendingNetworkMutations.remove(mutation);
    }

    public ReStudioApiClient studioApi() {
        return delegate;
    }

    @Override
    public ResourcePoolClient resourcePools() {
        return delegate.resourcePools();
    }

    @Override
    public Async<Void> installHostedModpack(String serverId, HostedModpackSelection selection, String requestKey) {
        if (selection == null || requestKey == null || requestKey.isBlank()) {
            return Async.failed(new IllegalArgumentException("Modpack Selection And Request Key Are Required"));
        }
        ServerModels.ModpackJobRequest request = new ServerModels.ModpackJobRequest("install", selection.provider(),
                selection.projectId(), selection.versionId(), selection.versionNumber(), selection.downloadUrl(),
                selection.minecraftVersion(), selection.software());
        long started = System.nanoTime();
        CompletableFuture<Void> install = retry(() -> delegate.submitHostedModpackJob(serverId, request, requestKey), 2)
                .thenCompose(job -> pollHostedModpack(serverId, job, started));
        return JvmAsyncBridge.fromFuture(install);
    }

    private CompletableFuture<Void> pollHostedModpack(String serverId, ServerModels.HostedModpackJob job, long started) {
        if (job == null || job.id == null || job.id.isBlank() || !serverId.equals(job.serverId)
                || !"modpack.install".equals(job.operation)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Modpack Job Is Unavailable"));
        }
        String status = job.status == null ? "" : job.status.toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(status)) return CompletableFuture.completedFuture(null);
        if ("FAILED".equals(status)) {
            String reason = job.error == null || job.error.isBlank() ? "Modpack Installation Failed" : job.error;
            return CompletableFuture.failedFuture(new IllegalStateException(reason));
        }
        if (!"PENDING".equals(status) && !"RUNNING".equals(status)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Modpack Job Status Is Unknown"));
        }
        if (System.nanoTime() - started >= MODPACK_JOB_TIMEOUT.toNanos()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Modpack Installation Is Still Running: " + job.id));
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(job.id);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(new IllegalStateException("Modpack Job Identifier Is Invalid", failure));
        }
        return delay().thenCompose(ignored -> retry(() -> delegate.getHostedModpackJob(serverId, jobId), 2))
                .thenCompose(next -> pollHostedModpack(serverId, next, started));
    }

    private static <T> CompletableFuture<T> retry(Supplier<CompletableFuture<T>> action, int remaining) {
        return action.get().exceptionallyCompose(failure -> {
            if (remaining == 0 || !retryable(failure)) return CompletableFuture.failedFuture(failure);
            return delay().thenCompose(ignored -> retry(action, remaining - 1));
        });
    }

    private static boolean retryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ReStudioApiClient.ApiException api) return api.getStatus() == 429 || api.getStatus() >= 500;
            if (current instanceof IOException || current instanceof TimeoutException) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private static boolean uncertainDeletion(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ReStudioApiClient.ApiException api) return api.getStatus() >= 500;
            if (current instanceof IOException || current instanceof TimeoutException) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private static CompletableFuture<Void> delay() {
        return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS));
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> getServers() {
        return JvmAsyncBridge.fromFuture(delegate.getServers());
    }

    @Override
    public Async<Void> deleteServer(String serverId, String serverName) {
        if (serverId == null || serverId.isBlank() || serverName == null || serverName.isBlank()) {
            return Async.failed(new IllegalArgumentException("Server Identity And Name Are Required"));
        }
        String path = "/servers/" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        String body = GSON.toJson(Map.of("confirmServerId", serverId, "confirmName", serverName));
        long started = System.nanoTime();
        CompletableFuture<Void> deletion = JvmAsyncBridge.toFuture(delegate.async().communityRequest("POST", path + "/delete", body))
                .thenCompose(response -> settleDeletion(serverId, path, started, response, null))
                .exceptionallyCompose(failure -> uncertainDeletion(failure)
                        ? recoverDeletion(serverId, path, started, failure)
                        : CompletableFuture.failedFuture(failure));
        return JvmAsyncBridge.fromFuture(deletion);
    }

    private CompletableFuture<Void> recoverDeletion(String serverId, String path, long started, Throwable originalFailure) {
        return retry(() -> JvmAsyncBridge.toFuture(delegate.async().communityRequest("GET", path + "/deletion", null)), 2)
                .thenCompose(response -> settleDeletion(serverId, path, started, response, originalFailure))
                .exceptionallyCompose(failure -> {
                    Throwable current = failure;
                    while (current != null) {
                        if (current instanceof ReStudioApiClient.ApiException api && api.getStatus() == 404 && originalFailure != null) {
                            return CompletableFuture.failedFuture(originalFailure);
                        }
                        if (current.getCause() == current) break;
                        current = current.getCause();
                    }
                    return CompletableFuture.failedFuture(failure);
                });
    }

    private CompletableFuture<Void> settleDeletion(String serverId, String path, long started, String response, Throwable originalFailure) {
        ServerDeletionStatus status = GSON.fromJson(response, ServerDeletionStatus.class);
        if (status == null || !serverId.equals(status.serverId()) || status.status() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Server Deletion Status Is Invalid"));
        }
        return switch (status.status()) {
            case "COMPLETED" -> CompletableFuture.completedFuture(null);
            case "NEEDS_REVIEW" -> CompletableFuture.failedFuture(new IllegalStateException("Server Deletion Needs Review"));
            case "SUPERSEDED" -> CompletableFuture.failedFuture(new IllegalStateException("Server Deletion Was Superseded"));
            case "PENDING", "RUNNING", "RETRYING", "FAILED" -> {
                if (System.nanoTime() - started >= DELETION_RECOVERY_TIMEOUT.toNanos()) {
                    yield CompletableFuture.failedFuture(new IllegalStateException("Server Deletion Is Still Pending"));
                }
                yield delay().thenCompose(ignored -> recoverDeletion(serverId, path, started, originalFailure));
            }
            default -> CompletableFuture.failedFuture(new IllegalStateException("Server Deletion Status Is Unknown"));
        };
    }

    @Override
    public Async<List<ServerModels.Plan>> getPlans() {
        return JvmAsyncBridge.fromFuture(delegate.getPlans());
    }

    @Override
    public Async<String> getSftpToken(String serverIdentifier) {
        return JvmAsyncBridge.fromFuture(delegate.getSftpToken(serverIdentifier));
    }

    @Override
    public Async<ServerModels.ServerStats> getServerResources(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getServerResources(serverId));
    }

    @Override
    public Async<ServerModels.ServerStatus> getServerStatus(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getServerStatus(serverId));
    }

    @Override
    public Async<ServerModels.WebsocketData> getServerWebsocket(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getServerWebsocket(serverId));
    }

    @Override
    public Async<Void> setServerPower(String serverId, String signal) {
        return JvmAsyncBridge.fromFuture(delegate.setServerPower(serverId, signal));
    }

    @Override
    public Async<Void> sendServerCommand(String serverId, String command) {
        return JvmAsyncBridge.fromFuture(delegate.sendServerCommand(serverId, command));
    }

    @Override
    public Async<PlayerList> getPlayers(String serverId) {
        return Async.failed(new UnsupportedOperationException("Player Management Requires A Server Instance"));
    }

    @Override
    public Async<Void> executePlayerAction(String serverId, PlayerAction action) {
        return Async.failed(new UnsupportedOperationException("Player Management Requires A Server Instance"));
    }

    @Override
    public Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(String serverId, String directory) {
        return JvmAsyncBridge.fromFuture(delegate.listFiles(serverId, directory));
    }

    @Override
    public Async<List<ServerModels.PteroFileObjectAttributes>> listResourceFiles(String serverId, String directory) {
        return JvmAsyncBridge.fromFuture(delegate.listResourceFiles(serverId, directory));
    }

    @Override
    public Async<List<ServerModels.ResourceFileHash>> resolveResourceFileHashes(String serverId, List<String> paths) {
        return JvmAsyncBridge.fromFuture(delegate.resolveResourceFileHashes(serverId, paths));
    }

    @Override
    public Async<String> getFileContent(String serverId, String path) {
        return JvmAsyncBridge.fromFuture(delegate.getFileContent(serverId, path));
    }

    @Override
    public Async<String> getFileDownloadUrl(String serverId, String path) {
        return Async.failed(new UnsupportedOperationException("File Download URLs Require A Browser Session"));
    }

    @Override
    public Async<Void> writeFile(String serverId, String path, String content) {
        return JvmAsyncBridge.fromFuture(delegate.writeFile(serverId, path, content));
    }

    @Override
    public Async<List<ServerModels.Backup>> getBackups(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getBackups(serverId));
    }

    @Override
    public Async<ServerModels.Backup> createBackup(String serverId, String name, List<String> ignored, boolean locked) {
        return JvmAsyncBridge.fromFuture(delegate.createBackup(serverId, name, ignored, locked));
    }

    @Override
    public Async<BackupOperations.CreateResult> createBackup(BackupOperations.CreateRequest request) {
        return JvmAsyncBridge.fromFuture(delegate.createBackup(request));
    }

    @Override
    public Async<BackupOperations.CreateResult> observeCreate(BackupOperations.CreateRequest request) {
        return JvmAsyncBridge.fromFuture(delegate.observeCreate(request));
    }

    @Override
    public Async<Void> deleteBackup(String serverId, String backupUuid) {
        return JvmAsyncBridge.fromFuture(delegate.deleteBackup(serverId, backupUuid));
    }

    @Override
    public Async<Void> restoreBackup(String serverId, String backupUuid, boolean truncate) {
        return JvmAsyncBridge.fromFuture(delegate.restoreBackup(serverId, backupUuid, truncate));
    }

    @Override
    public Async<BackupOperations.RestoreResult> restoreBackup(BackupOperations.RestoreRequest request) {
        return JvmAsyncBridge.fromFuture(delegate.restoreBackup(request));
    }

    @Override
    public Async<BackupOperations.RestoreResult> observeRestore(BackupOperations.RestoreRequest request) {
        return JvmAsyncBridge.fromFuture(delegate.observeRestore(request));
    }

    @Override
    public Async<ServerModels.BackupRestoreDiscovery> discoverRestore(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.discoverRestore(serverId));
    }

    @Override
    public Async<ServerModels.Backup> toggleBackupLock(String serverId, String backupUuid) {
        return JvmAsyncBridge.fromFuture(delegate.toggleBackupLock(serverId, backupUuid));
    }

    @Override
    public Async<String> getBackupDownloadUrl(String serverId, String backupUuid) {
        return JvmAsyncBridge.fromFuture(delegate.getBackupDownloadUrl(serverId, backupUuid));
    }

    @Override
    public ServerScheduleModels.Capabilities scheduleCapabilities(String serverId) {
        return new ServerScheduleModels.Capabilities(true, "", ServerScheduleModels.Durability.BACKEND,
                true, true, true, true, true, true, true);
    }

    @Override
    public Async<List<ServerScheduleModels.Schedule>> listSchedules(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.listSchedules(serverId));
    }

    @Override
    public Async<ServerScheduleModels.Schedule> createSchedule(String serverId, ServerScheduleModels.Mutation mutation,
                                                                String idempotencyKey) {
        return JvmAsyncBridge.fromFuture(delegate.createSchedule(serverId, mutation, idempotencyKey));
    }

    @Override
    public Async<ServerScheduleModels.Schedule> updateSchedule(String serverId, String scheduleId,
                                                                ServerScheduleModels.Mutation mutation,
                                                                String expectedRevision, String idempotencyKey) {
        return JvmAsyncBridge.fromFuture(delegate.updateSchedule(serverId, scheduleId, mutation, expectedRevision, idempotencyKey));
    }

    @Override
    public Async<Void> deleteSchedule(String serverId, String scheduleId, String expectedRevision, String idempotencyKey) {
        return JvmAsyncBridge.fromFuture(delegate.deleteSchedule(serverId, scheduleId, expectedRevision, idempotencyKey));
    }

    @Override
    public Async<ServerScheduleModels.Run> runSchedule(String serverId, String scheduleId, String idempotencyKey) {
        return JvmAsyncBridge.fromFuture(delegate.runSchedule(serverId, scheduleId, idempotencyKey));
    }

    @Override
    public Async<List<ServerModels.Subuser>> getSubusers(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getSubusers(serverId));
    }

    @Override
    public Async<List<ServerModels.Allocation>> getAllocations(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getAllocations(serverId));
    }

    @Override
    public Async<ServerModels.StartupSettings> getServerStartupConfig(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getServerStartupConfig(serverId));
    }

    @Override
    public Async<Void> updateServerStartupVariables(String serverId, String revision, Map<String, String> values) {
        return JvmAsyncBridge.fromFuture(delegate.updateServerStartupVariables(serverId, revision, values));
    }

    @Override
    public Async<Void> updateServerStartupVariable(String serverId, String key, String value) {
        return RemotelyServerApi.super.updateServerStartupVariable(serverId, key, value);
    }

    @Override
    public Async<Void> updateServerDockerImage(String serverId, String dockerImage) {
        return JvmAsyncBridge.fromFuture(delegate.updateServerDockerImage(serverId, dockerImage));
    }

    @Override
    public Async<Void> renameServer(String serverId, String newName) {
        return JvmAsyncBridge.fromFuture(delegate.renameServer(serverId, newName));
    }

    @Override
    public Async<Void> reinstallServer(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.reinstallServer(serverId));
    }

    @Override
    public Async<Void> pullFile(String serverId, String url, String directory, String filename) {
        return JvmAsyncBridge.fromFuture(delegate.pullFile(serverId, url, directory, filename));
    }

    @Override
    public Async<Void> decompressFile(String serverId, String root, String file) {
        return JvmAsyncBridge.fromFuture(delegate.decompressFile(serverId, root, file));
    }

    @Override
    public Async<Void> deleteFiles(String serverId, String root, List<String> files) {
        return JvmAsyncBridge.fromFuture(delegate.deleteFiles(serverId, root, files));
    }

    @Override
    public Async<Void> renameFiles(String serverId, String root, List<ServerModels.PteroFileRenameItem> files) {
        return JvmAsyncBridge.fromFuture(delegate.renameFiles(serverId, root, files));
    }

    @Override
    public Async<Void> copyFile(String serverId, String location) {
        return JvmAsyncBridge.fromFuture(delegate.copyFile(serverId, location));
    }

    @Override
    public Async<Void> createFolder(String serverId, String root, String name) {
        return JvmAsyncBridge.fromFuture(delegate.createFolder(serverId, root, name));
    }

    @Override
    public Async<Void> chmodFiles(String serverId, String root, List<ServerModels.PteroFileChmodItem> files) {
        return JvmAsyncBridge.fromFuture(delegate.chmodFiles(serverId, root, files));
    }

    @Override
    public Async<Void> compressFiles(String serverId, String root, List<String> files) {
        return JvmAsyncBridge.fromFuture(delegate.compressFiles(serverId, root, files));
    }

    @Override
    public Async<ServerModels.ReProxySummary> getReProxySummary() {
        return JvmAsyncBridge.fromFuture(delegate.getReProxySummary());
    }

    @Override
    public Async<List<ServerModels.ReProxyDomain>> listReProxyDomains() {
        return JvmAsyncBridge.fromFuture(delegate.listReProxyDomains());
    }

    @Override
    public Async<ServerModels.ReProxyDomain> createReProxyDomain(String subdomain) {
        return JvmAsyncBridge.fromFuture(delegate.createReProxyDomain(subdomain));
    }

    @Override
    public Async<ServerModels.ReProxyStartTunnelResponse> startReProxyTunnel(String domainId, int localPort, String protocol) {
        return JvmAsyncBridge.fromFuture(delegate.startReProxyTunnel(domainId, localPort, protocol));
    }

    @Override
    public Async<Void> stopReProxyTunnel(String tunnelId) {
        return JvmAsyncBridge.fromFuture(delegate.stopReProxyTunnel(tunnelId));
    }

    @Override
    public Async<Void> deleteReProxyDomain(String domainId) {
        return JvmAsyncBridge.fromFuture(delegate.deleteReProxyDomain(domainId));
    }

    @Override
    public Async<List<ServerModels.ReProxyTunnel>> listReProxyTunnels() {
        return JvmAsyncBridge.fromFuture(delegate.listReProxyTunnels());
    }

    @Override
    public Async<ServerModels.ReProxyTunnel> getReProxyTunnel(String tunnelId) {
        return JvmAsyncBridge.fromFuture(delegate.getReProxyTunnel(tunnelId));
    }

    @Override
    public Async<ServerModels.ReSyncConfig> getReSyncConfig(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getReSyncConfig(serverId));
    }

    @Override
    public Async<String> getReSyncApiKey(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getReSyncApiKey(serverId));
    }

    @Override
    public Async<String> getReSyncVersion(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.getReSyncVersion(serverId));
    }

    @Override
    public Async<ServerModels.ReSyncProvisionResult> provisionReSync(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.provisionReSync(serverId));
    }

    @Override
    public Async<ServerModels.ReSyncProvisionResult> updateReSync(String serverId) {
        return JvmAsyncBridge.fromFuture(delegate.updateReSync(serverId));
    }

    @Override
    public ReSyncMarketplaceApi marketplace() {
        return new ReSyncMarketplaceApi() {
            @Override
            public boolean authenticated() {
                return ReStudio.getInstance() != null && ReStudio.getInstance().isAuthenticated();
            }

            @Override
            public boolean administrator() {
                return ReStudio.getInstance() != null && ReStudio.getInstance().isAdmin();
            }

            @Override
            public Async<MarketplaceModels.PageResponse<MarketplaceModels.Listing>> browse(String marketplaceSlug, String type,
                                                                                             String query, int page, int size, boolean refresh) {
                return JvmAsyncBridge.fromFuture(delegate.browseMarketplaceListings(marketplaceSlug, type, query, page, size));
            }

            @Override
            public Async<MarketplaceModels.PageResponse<MarketplaceModels.Listing>> browseControl(String marketplaceSlug,
                                                                                                    String status, String type,
                                                                                                    String query, int page, int size) {
                return JvmAsyncBridge.fromFuture(delegate.browseMarketplaceControlListings(marketplaceSlug, status, type, query, page, size));
            }

            @Override
            public Async<MarketplaceModels.Listing> getListing(String marketplaceSlug, String listingSlug, boolean refresh) {
                return JvmAsyncBridge.fromFuture(delegate.getMarketplaceListing(marketplaceSlug, listingSlug));
            }

            @Override
            public Async<List<MarketplaceModels.Version>> getVersions(String marketplaceSlug, String listingSlug) {
                return JvmAsyncBridge.fromFuture(delegate.getMarketplaceVersions(marketplaceSlug, listingSlug));
            }

            @Override
            public Async<MarketplaceModels.MediaAsset> uploadMedia(String projectId, String category, String fileName,
                                                                    String visibility, byte[] content, String contentType) {
                if (content == null) return Async.completed(null);
                try {
                    String requestedName = fileName == null ? "" : fileName;
                    int extensionIndex = requestedName.lastIndexOf('.');
                    String suffix = extensionIndex >= 0 && extensionIndex < requestedName.length() - 1
                        ? requestedName.substring(extensionIndex).replaceAll("[^A-Za-z0-9._-]", "") : ".bin";
                    if (suffix.length() < 2 || suffix.length() > 32) suffix = ".bin";
                    Path temporary = Files.createTempFile("resync-marketplace-", suffix);
                    Files.write(temporary, content);
                    return JvmAsyncBridge.fromFuture(delegate.uploadMedia(projectId, category, fileName, visibility, temporary))
                        .whenComplete((ignored, failure) -> {
                            try {
                                Files.deleteIfExists(temporary);
                            } catch (Exception ignoredDelete) {
                            }
                        });
                } catch (Exception exception) {
                    return Async.failed(exception);
                }
            }

            @Override
            public Async<MarketplaceModels.Listing> createListing(String marketplaceSlug, MarketplaceModels.ListingRequest request) {
                return JvmAsyncBridge.fromFuture(delegate.createMarketplaceListing(marketplaceSlug, request));
            }

            @Override
            public Async<MarketplaceModels.Listing> submitListing(String marketplaceSlug, String listingSlug) {
                return JvmAsyncBridge.fromFuture(delegate.submitMarketplaceListing(marketplaceSlug, listingSlug));
            }

            @Override
            public Async<MarketplaceModels.ListingMedia> addListingMedia(String marketplaceSlug, String listingSlug,
                                                                          String mediaAssetId, String kind, int sortOrder) {
                return JvmAsyncBridge.fromFuture(delegate.addMarketplaceListingMedia(marketplaceSlug, listingSlug, mediaAssetId, kind, sortOrder));
            }

            @Override
            public Async<MarketplaceModels.Version> createVersion(String marketplaceSlug, String listingSlug, String version,
                                                                   String channel, String platform, String releaseId,
                                                                   String changelog, String compatibilityJson, String metadataJson) {
                return JvmAsyncBridge.fromFuture(delegate.createMarketplaceVersion(marketplaceSlug, listingSlug, version, channel, platform,
                    releaseId, changelog, compatibilityJson, metadataJson));
            }

            @Override
            public Async<MarketplaceModels.Version> submitVersion(String marketplaceSlug, String listingSlug, String versionId) {
                return JvmAsyncBridge.fromFuture(delegate.submitMarketplaceVersion(marketplaceSlug, listingSlug, versionId));
            }

            @Override
            public Async<List<ReleaseModels.Release>> reSyncReleases() {
                return JvmAsyncBridge.fromFuture(delegate.getReSyncReleases());
            }

            @Override
            public String mediaUrl(String mediaAssetId) {
                return delegate.getMediaDownloadUrl(mediaAssetId);
            }

            @Override
            public List<String> localMinecraftVersions() {
                if (Rebase.get() == null) return List.of();
                return Rebase.get().getLocalBaseVersions().stream().map(GameVersion::getId).toList();
            }

            @Override
            public void openListing(String marketplaceSlug, String listingSlug, boolean control) {
                RemotelyClient client = RemotelyClient.INSTANCE;
                if (client != null && client.getHost() != null) client.getHost().openMarketplaceListing(marketplaceSlug, listingSlug, control);
            }
        };
    }
}
