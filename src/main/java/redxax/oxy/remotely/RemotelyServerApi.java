package redxax.oxy.remotely;

import restudio.rescreen.platform.Async;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.backend.feature.BackupOperations;
import redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceApi;
import redxax.oxy.remotely.ui.server.NetworkOverviewProvider;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.resource.ResourcePoolClient;
import restudio.rebase.schedule.ServerScheduleModels;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public interface RemotelyServerApi {
    default Object studioApi() {
        return null;
    }

    Async<List<ServerModels.ClientServerView>> getServers();

    default ResourcePoolClient resourcePools() {
        return new ResourcePoolClient((method, path, body) ->
                Async.failed(new UnsupportedOperationException("Resource Pools Are Unavailable")));
    }

    default Async<ServerCapabilities> getServerCapabilities(String serverId) {
        return Async.failed(new UnsupportedOperationException("Server Capability Inventory Is Unavailable"));
    }

    default Async<List<ServerModels.Plan>> getPlans() {
        return Async.failed(new UnsupportedOperationException("Reactor Plans Are Unavailable"));
    }

    Async<String> getSftpToken(String serverIdentifier);

    Async<ServerModels.ServerStats> getServerResources(String serverId);

    Async<ServerModels.ServerStatus> getServerStatus(String serverId);

    Async<ServerModels.WebsocketData> getServerWebsocket(String serverId);

    Async<Void> setServerPower(String serverId, String signal);

    Async<Void> sendServerCommand(String serverId, String command);

    Async<PlayerList> getPlayers(String serverId);

    Async<Void> executePlayerAction(String serverId, PlayerAction action);

    Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(String serverId, String directory);

    default Async<List<ServerModels.PteroFileObjectAttributes>> listResourceFiles(String serverId, String directory) {
        return listFiles(serverId, directory);
    }

    default Async<List<ServerModels.ResourceFileHash>> resolveResourceFileHashes(String serverId, List<String> paths) {
        return Async.failed(new UnsupportedOperationException("Resource Hashes Are Unavailable"));
    }

    Async<String> getFileContent(String serverId, String path);

    Async<String> getFileDownloadUrl(String serverId, String path);

    Async<Void> writeFile(String serverId, String path, String content);

    default Async<List<ServerModels.Backup>> getBackups(String serverId) {
        return Async.failed(new UnsupportedOperationException("Backups Are Unavailable"));
    }

    default Async<ServerModels.Backup> createBackup(String serverId, String name, List<String> ignored, boolean locked) {
        return Async.failed(new UnsupportedOperationException("Backups Are Unavailable"));
    }

    default Async<BackupOperations.CreateResult> createBackup(BackupOperations.CreateRequest request) {
        return createBackup(request.serverId(), request.name(), request.ignored(), request.locked())
                .thenApply(backup -> BackupOperations.created(request, backup));
    }

    default Async<BackupOperations.CreateResult> observeCreate(BackupOperations.CreateRequest request) {
        return Async.failed(new UnsupportedOperationException("Backup Creation Recovery Is Unavailable"));
    }

    default Async<Void> deleteBackup(String serverId, String backupUuid) {
        return Async.failed(new UnsupportedOperationException("Backups Are Unavailable"));
    }

    default Async<Void> restoreBackup(String serverId, String backupUuid, boolean truncate) {
        return Async.failed(new UnsupportedOperationException("Backups Are Unavailable"));
    }

    default Async<BackupOperations.RestoreResult> restoreBackup(BackupOperations.RestoreRequest request) {
        return restoreBackup(request.serverId(), request.backupId(), request.truncate())
                .thenApply(ignored -> BackupOperations.completed(request));
    }

    default Async<BackupOperations.RestoreResult> observeRestore(BackupOperations.RestoreRequest request) {
        return Async.failed(new UnsupportedOperationException("Restore Recovery Is Unavailable"));
    }

    default Async<ServerModels.BackupRestoreDiscovery> discoverRestore(String serverId) {
        return Async.completed(null);
    }

    default Async<ServerModels.Backup> toggleBackupLock(String serverId, String backupUuid) {
        return Async.failed(new UnsupportedOperationException("Backups Are Unavailable"));
    }

    default Async<String> getBackupDownloadUrl(String serverId, String backupUuid) {
        return Async.failed(new UnsupportedOperationException("Backups Are Unavailable"));
    }

    default ServerScheduleModels.Capabilities scheduleCapabilities(String serverId) {
        return ServerScheduleModels.Capabilities.unavailable("Scheduling Is Unavailable");
    }

    default Async<List<ServerScheduleModels.Schedule>> listSchedules(String serverId) {
        return Async.failed(new UnsupportedOperationException("Scheduling Is Unavailable"));
    }

    default Async<ServerScheduleModels.Schedule> createSchedule(String serverId, ServerScheduleModels.Mutation mutation,
                                                                 String idempotencyKey) {
        return Async.failed(new UnsupportedOperationException("Scheduling Is Unavailable"));
    }

    default Async<ServerScheduleModels.Schedule> updateSchedule(String serverId, String scheduleId,
                                                                 ServerScheduleModels.Mutation mutation,
                                                                 String expectedRevision, String idempotencyKey) {
        return Async.failed(new UnsupportedOperationException("Scheduling Is Unavailable"));
    }

    default Async<Void> deleteSchedule(String serverId, String scheduleId, String expectedRevision, String idempotencyKey) {
        return Async.failed(new UnsupportedOperationException("Scheduling Is Unavailable"));
    }

    default Async<ServerScheduleModels.Run> runSchedule(String serverId, String scheduleId, String idempotencyKey) {
        return Async.failed(new UnsupportedOperationException("Scheduling Is Unavailable"));
    }

    default Async<List<ServerModels.Subuser>> getSubusers(String serverId) {
        return Async.failed(new UnsupportedOperationException("Access Management Is Unavailable"));
    }

    default Async<List<ServerModels.Allocation>> getAllocations(String serverId) {
        return Async.failed(new UnsupportedOperationException("Allocation Management Is Unavailable"));
    }

    default Async<ServerModels.StartupSettings> getServerStartupConfig(String serverId) {
        return Async.failed(new UnsupportedOperationException("Startup Settings Are Unavailable"));
    }

    default Async<Void> updateServerStartupVariables(String serverId, String revision, Map<String, String> values) {
        return Async.failed(new UnsupportedOperationException("Startup Settings Are Unavailable"));
    }

    default Async<Void> updateServerStartupVariable(String serverId, String key, String value) {
        return Async.failed(new UnsupportedOperationException("A Startup Settings Revision Is Required"));
    }

    default Async<Void> updateServerDockerImage(String serverId, String dockerImage) {
        return Async.failed(new UnsupportedOperationException("Docker Settings Are Unavailable"));
    }

    default Async<Void> renameServer(String serverId, String newName) {
        return Async.failed(new UnsupportedOperationException("Server Rename Is Unavailable"));
    }

    default Async<Void> reinstallServer(String serverId) {
        return Async.failed(new UnsupportedOperationException("Server Reinstall Is Unavailable"));
    }

    default Async<Void> pullFile(String serverId, String url, String directory, String filename) {
        return Async.failed(new UnsupportedOperationException("File Transfers Are Unavailable"));
    }

    default Async<Void> decompressFile(String serverId, String root, String file) {
        return Async.failed(new UnsupportedOperationException("File Archives Are Unavailable"));
    }

    default Async<Void> deleteFiles(String serverId, String root, List<String> files) {
        return Async.failed(new UnsupportedOperationException("File Deletion Is Unavailable"));
    }

    default Async<Void> renameFiles(String serverId, String root, List<ServerModels.PteroFileRenameItem> files) {
        return Async.failed(new UnsupportedOperationException("File Rename Is Unavailable"));
    }

    default Async<Void> toggleResource(String serverId, String resourcePath, boolean enabled) {
        String current = resourcePath == null ? "" : resourcePath.strip().replace('\\', '/');
        int separator = current.lastIndexOf('/');
        String root = separator <= 0 ? "/" : current.substring(0, separator);
        String name = separator < 0 ? current : current.substring(separator + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        String target = enabled && lower.endsWith(".disabled") ? name.substring(0, name.length() - ".disabled".length())
                : !enabled && !lower.endsWith(".disabled") ? name + ".disabled" : name;
        if (name.isBlank() || name.equals(target)) return Async.completed(null);
        ServerModels.PteroFileRenameItem rename = new ServerModels.PteroFileRenameItem();
        rename.from = name;
        rename.to = target;
        return renameFiles(serverId, root, List.of(rename));
    }

    default Async<Void> copyFile(String serverId, String location) {
        return Async.failed(new UnsupportedOperationException("File Copy Is Unavailable"));
    }

    default Async<Void> createFolder(String serverId, String root, String name) {
        return Async.failed(new UnsupportedOperationException("Folder Creation Is Unavailable"));
    }

    default Async<Void> chmodFiles(String serverId, String root, List<ServerModels.PteroFileChmodItem> files) {
        return Async.failed(new UnsupportedOperationException("File Permissions Are Unavailable"));
    }

    default Async<Void> compressFiles(String serverId, String root, List<String> files) {
        return Async.failed(new UnsupportedOperationException("File Archives Are Unavailable"));
    }

    default Async<List<TrashEntry>> listTrash(String serverId) {
        return Async.failed(new UnsupportedOperationException("File Trash Is Unavailable"));
    }

    default Async<FileVersion> getFileVersion(String serverId, String path) {
        return Async.failed(new UnsupportedOperationException("File Versioning Is Unavailable"));
    }

    default Async<TrashEntry> trashFile(String serverId, String path, String expectedVersion) {
        return Async.failed(new UnsupportedOperationException("File Trash Is Unavailable"));
    }

    default Async<TrashEntry> restoreTrash(String serverId, String trashId, String expectedVersion) {
        return Async.failed(new UnsupportedOperationException("File Trash Is Unavailable"));
    }

    default Async<Void> purgeTrash(String serverId, String trashId, String expectedVersion) {
        return Async.failed(new UnsupportedOperationException("File Trash Is Unavailable"));
    }

    default Async<ServerModels.ReProxySummary> getReProxySummary() {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default Async<ServerModels.ReProxySummary> getReProxySummary(String serverId) {
        return getReProxySummary();
    }

    default Async<List<ServerModels.ReProxyDomain>> listReProxyDomains() {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default Async<ServerModels.ReProxyDomain> createReProxyDomain(String subdomain) {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default Async<ServerModels.ReProxyDomain> createReProxyDomain(String serverId, String subdomain) {
        return createReProxyDomain(subdomain);
    }

    default Async<ServerModels.ReProxyStartTunnelResponse> startReProxyTunnel(String domainId, int localPort, String protocol) {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default Async<ServerModels.ReProxyStartTunnelResponse> startReProxyTunnel(String serverId, String domainId, int localPort, String protocol) {
        return startReProxyTunnel(domainId, localPort, protocol);
    }

    default Async<Void> stopReProxyTunnel(String tunnelId) {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default Async<Void> stopReProxyTunnel(String serverId, String tunnelId) {
        return stopReProxyTunnel(tunnelId);
    }

    default Async<Void> deleteReProxyDomain(String domainId) {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default Async<Void> deleteReProxyDomain(String serverId, String domainId) {
        return deleteReProxyDomain(domainId);
    }

    default Async<ServerModels.ClientServerView> duplicateServer(String serverId) {
        return Async.failed(new UnsupportedOperationException("Server Duplication Is Unavailable"));
    }

    default Async<DuplicateServerResult> duplicateServer(String serverId, String name, String idempotencyKey) {
        return duplicateServer(serverId).thenApply(server -> new DuplicateServerResult(serverId, server == null ? "" : server.identifier,
                "", "", "COMPLETED", "", "", idempotencyKey));
    }

    default Async<DuplicateServerResult> duplicateStatus(String serverId, String intentId) {
        return Async.failed(new UnsupportedOperationException("Server Duplication Status Is Unavailable"));
    }

    default Async<Void> deleteServer(String serverId, String serverName) {
        return Async.failed(new UnsupportedOperationException("Server Deletion Is Unavailable"));
    }

    default Async<List<ServerModels.ReProxyTunnel>> listReProxyTunnels() {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default Async<ServerModels.ReProxyTunnel> getReProxyTunnel(String tunnelId) {
        return Async.failed(new UnsupportedOperationException("ReProxy Is Unavailable"));
    }

    default DeveloperCapabilityProvider developer(String serverId) {
        return DeveloperCapabilityProvider.unavailable();
    }

    default ReSyncMarketplaceApi marketplace() {
        return ReSyncMarketplaceApi.unavailable();
    }

    default NetworkOverviewProvider networkOverviewProvider(RemotelyClient client) {
        return null;
    }

    Async<ServerModels.ReSyncConfig> getReSyncConfig(String serverId);

    Async<String> getReSyncApiKey(String serverId);

    Async<String> getReSyncVersion(String serverId);

    Async<ServerModels.ReSyncProvisionResult> provisionReSync(String serverId);

    Async<ServerModels.ReSyncProvisionResult> updateReSync(String serverId);

    record PlayerList(boolean supported, List<Player> players) {
        public PlayerList {
            players = players == null ? List.of() : List.copyOf(players);
        }
    }

    record Player(UUID uuid, String name, boolean online, boolean operator, int ping, String address) {
        public Player {
            name = name == null ? "" : name;
            address = address == null ? "" : address;
        }
    }

    record PlayerDetails(Player player, long firstSeenAt, long lastSeenAt, long totalPlayTimeMs,
                         List<PlayerSession> sessions, Map<String, Object> data) {
        public PlayerDetails {
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }

    record PlayerSession(long startedAt, long endedAt) {
    }

    record TrashEntry(String id, String path, String version, String deletedAt, boolean directory, long size) {
        public TrashEntry {
            id = id == null ? "" : id;
            path = path == null ? "" : path;
            version = version == null ? "" : version;
            deletedAt = deletedAt == null ? "" : deletedAt;
        }
    }

    record FileVersion(String path, String version, boolean directory, long size) {
    }

    record DuplicateServerResult(String sourceServerId, String targetServerId, String subscriptionId, String intentId,
                                 String status, String phase, String checkoutUrl, String idempotencyKey) {
    }

    record PlayerAction(String action, UUID playerId, String playerName, String argument, boolean flag) {
        public PlayerAction {
            action = action == null ? "" : action.trim();
            playerName = playerName == null ? "" : playerName.trim();
            argument = argument == null ? "" : argument.trim();
        }
    }

    record ServerCapabilities(String serverId, Map<String, CapabilityAvailability> actions, ReSyncAvailability reSync) {
        public ServerCapabilities {
            serverId = serverId == null ? "" : serverId.trim();
            actions = actions == null ? Map.of() : Map.copyOf(actions);
            reSync = reSync == null ? ReSyncAvailability.unavailable("ReSync Is Unavailable") : reSync;
        }

        public CapabilityAvailability action(String action) {
            CapabilityAvailability availability = actions.get(action == null ? "" : action);
            return availability == null ? new CapabilityAvailability(false, "Capability Is Unavailable", "") : availability;
        }
    }

    record CapabilityAvailability(boolean supported, String reason, String transport) {
        public CapabilityAvailability {
            reason = reason == null ? "" : reason.trim();
            transport = transport == null ? "" : transport.trim();
        }
    }

    enum ReSyncRelayState {
        UNKNOWN,
        UNAVAILABLE,
        READY
    }

    enum ReSyncReadinessReason {
        UNKNOWN,
        NONE,
        NOT_PROVISIONED,
        PROVISIONING_INCOMPLETE,
        CREDENTIAL_UNAVAILABLE,
        INVALID_ENDPOINT,
        MISSING_TLS_PIN,
        PLAINTEXT_PUBLIC_ENDPOINT,
        RUNTIME_METADATA_STALE,
        RUNTIME_VERSION_UNAVAILABLE,
        UPSTREAM_UNREACHABLE,
        PROTOCOL_INCOMPATIBLE
    }

    enum ReSyncHandshakeState {
        UNKNOWN,
        CONNECTING,
        CONNECTED,
        UNREACHABLE,
        REJECTED
    }

    enum ReSyncCompatibility {
        UNKNOWN,
        COMPATIBLE,
        MISMATCH
    }

    record ReSyncEndpointReadiness(boolean ready, ReSyncReadinessReason reasonCode, String reason,
                                   String endpointUri, String transport) {
        public ReSyncEndpointReadiness {
            reasonCode = reasonCode == null ? ReSyncReadinessReason.UNKNOWN : reasonCode;
            reason = reason == null ? "" : reason.trim();
            endpointUri = endpointUri == null ? "" : endpointUri.trim();
            transport = transport == null ? "" : transport.trim();
        }

        public static ReSyncEndpointReadiness unavailable(String reason) {
            return new ReSyncEndpointReadiness(false, ReSyncReadinessReason.UNKNOWN, reason, "", "");
        }
    }

    record ReSyncAvailability(boolean supported, String reason, String transport, List<String> features,
                              ReSyncRelayState relayState, ReSyncHandshakeState handshakeState,
                              ReSyncCompatibility protocolCompatibility, ReSyncCompatibility runtimeCompatibility,
                              String runtimeVersion, ReSyncReadinessReason reasonCode,
                              ReSyncEndpointReadiness endpoint) {
        public ReSyncAvailability(boolean supported, String reason, String transport, List<String> features) {
            this(supported, reason, transport, features, ReSyncRelayState.UNKNOWN, ReSyncHandshakeState.UNKNOWN,
                ReSyncCompatibility.UNKNOWN, ReSyncCompatibility.UNKNOWN, "", ReSyncReadinessReason.UNKNOWN, null);
        }

        public ReSyncAvailability(boolean supported, String reason, String transport, List<String> features,
                                  ReSyncRelayState relayState, ReSyncHandshakeState handshakeState,
                                  ReSyncCompatibility protocolCompatibility, ReSyncCompatibility runtimeCompatibility,
                                  String runtimeVersion) {
            this(supported, reason, transport, features, relayState, handshakeState, protocolCompatibility,
                runtimeCompatibility, runtimeVersion, ReSyncReadinessReason.UNKNOWN, null);
        }

        public static ReSyncAvailability unavailable(String reason) {
            return new ReSyncAvailability(false, reason, "", List.of(), ReSyncRelayState.UNKNOWN,
                ReSyncHandshakeState.UNKNOWN, ReSyncCompatibility.UNKNOWN, ReSyncCompatibility.UNKNOWN, "",
                ReSyncReadinessReason.UNKNOWN, ReSyncEndpointReadiness.unavailable(reason));
        }

        public ReSyncAvailability {
            reason = reason == null ? "" : reason.trim();
            transport = transport == null ? "" : transport.trim();
            features = features == null ? List.of() : List.copyOf(features);
            relayState = relayState == null ? ReSyncRelayState.UNKNOWN : relayState;
            handshakeState = handshakeState == null ? ReSyncHandshakeState.UNKNOWN : handshakeState;
            protocolCompatibility = protocolCompatibility == null ? ReSyncCompatibility.UNKNOWN : protocolCompatibility;
            runtimeCompatibility = runtimeCompatibility == null ? ReSyncCompatibility.UNKNOWN : runtimeCompatibility;
            runtimeVersion = runtimeVersion == null ? "" : runtimeVersion.trim();
            reasonCode = reasonCode == null ? ReSyncReadinessReason.UNKNOWN : reasonCode;
            endpoint = endpoint == null ? new ReSyncEndpointReadiness(supported,
                supported ? ReSyncReadinessReason.NONE : reasonCode, reason, "", "") : endpoint;
        }
    }
}
