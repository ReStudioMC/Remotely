package redxax.oxy.remotely;

import redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceApi;
import restudio.rescreen.platform.Async;
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
import restudio.rebase.schedule.ServerScheduleModels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DesktopRemotelyServerApi implements RemotelyServerApi {
    private final ReStudioApiClient delegate;

    public DesktopRemotelyServerApi(ReStudioApiClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public ReStudioApiClient studioApi() {
        return delegate;
    }

    @Override
    public ResourcePoolClient resourcePools() {
        return delegate.resourcePools();
    }

    @Override
    public Async<List<ServerModels.ClientServerView>> getServers() {
        return JvmAsyncBridge.fromFuture(delegate.getServers());
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
                true, true, true, true, true);
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
