package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.player.IPlayerHistoryCollector;
import redxax.oxy.remotely.data.player.PlayerService;
import redxax.oxy.remotely.data.player.source.IPlayerSource;
import redxax.oxy.remotely.data.player.source.MsmpPlayerSource;
import redxax.oxy.remotely.data.player.source.StandardFileSource;
import redxax.oxy.remotely.data.player.source.StandardLogSource;
import redxax.oxy.remotely.data.player.standard.StandardPlayerHistoryProvider;
import redxax.oxy.remotely.data.playerdata.PlayerDataSource;
import redxax.oxy.remotely.data.playerdata.sources.RconPlayerDataSource;
import redxax.oxy.remotely.data.playerdata.sources.WorldPlayerDataSource;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.data.managed.PlayerSession;
import redxax.oxy.remotely.data.player.management.PlayerOperation;
import redxax.oxy.remotely.data.player.management.PlayerOperationResult;
import redxax.oxy.remotely.data.player.management.ReSyncPlayerDataAdapter;
import redxax.oxy.remotely.data.player.action.IActionExecutor;
import redxax.oxy.remotely.data.playerdata.PlayerData;
import redxax.oxy.remotely.packcontent.GlyphPreviewAccess;
import redxax.oxy.remotely.packcontent.DesktopGlyphPreviewAccess;
import restudio.rebase.api.RebaseApiFactory;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.backend.feature.DataStreamFeature;
import restudio.rebase.backend.feature.PlayerManagementFeature;
import restudio.rebase.backend.feature.ResourceUsageFeature;
import restudio.rebase.backend.feature.ServerHealthFeature;
import restudio.rebase.health.ServerHealth;
import restudio.rebase.backend.feature.TerminalFeature;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.platform.jvm.JvmDeveloperCapabilityProvider;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.widgets.TerminalWidget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface DesktopServerUiCapabilities extends ServerUiCapabilityProvider {
    default String serverId(Instance instance) {
        if (instance == null) return "";
        if (instance.getBackendConfig() != null && instance.getBackendConfig().credentials != null) {
            String identifier = instance.getBackendConfig().credentials.get("identifier");
            if (identifier != null && !identifier.isBlank()) return identifier;
        }
        return instance.getInstanceId() == null ? "" : instance.getInstanceId();
    }

    default String serverId(ServerModels.ClientServerView server) {
        if (server == null) {
            return "";
        }
        if (server.identifier != null && !server.identifier.isBlank()) {
            return server.identifier;
        }
        return server.uuid == null ? "" : server.uuid;
    }

    default Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(ServerModels.ClientServerView server, String directory) {
        return Async.failed(new UnsupportedOperationException("Server Files Are Unavailable"));
    }

    default Async<String> readFile(ServerModels.ClientServerView server, String path) {
        return Async.failed(new UnsupportedOperationException("Server Files Are Unavailable"));
    }

    default Async<Void> writeFile(ServerModels.ClientServerView server, String path, String content) {
        return Async.failed(new UnsupportedOperationException("Server Files Are Unavailable"));
    }

    default Async<Void> deleteFiles(ServerModels.ClientServerView server, String root, List<String> files) {
        return Async.failed(new UnsupportedOperationException("File Deletion Is Unavailable"));
    }

    default Async<Void> renameFiles(ServerModels.ClientServerView server, String root, List<ServerModels.PteroFileRenameItem> files) {
        return Async.failed(new UnsupportedOperationException("File Rename Is Unavailable"));
    }

    default Async<Void> createFolder(ServerModels.ClientServerView server, String root, String name) {
        return Async.failed(new UnsupportedOperationException("Folder Creation Is Unavailable"));
    }

    default DeveloperCapabilityProvider developer(ServerModels.ClientServerView server) {
        return developer(serverId(server));
    }

    default Async<Void> sendCommand(ServerModels.ClientServerView server, String command) {
        return Async.failed(new UnsupportedOperationException("Server Console Is Unavailable"));
    }

    default Async<Void> setPower(ServerModels.ClientServerView server, String signal) {
        return Async.failed(new UnsupportedOperationException("Server Lifecycle Is Unavailable"));
    }

    default Async<ServerModels.ServerStatus> status(ServerModels.ClientServerView server) {
        return Async.failed(new UnsupportedOperationException("Server Status Is Unavailable"));
    }

    default Async<ServerModels.ServerStats> stats(ServerModels.ClientServerView server) {
        return Async.failed(new UnsupportedOperationException("Server Statistics Are Unavailable"));
    }

    default Async<ServerModels.WebsocketData> consoleSession(ServerModels.ClientServerView server) {
        return Async.failed(new UnsupportedOperationException("Server Console Is Unavailable"));
    }

    default Async<List<RemotelyServerApi.Player>> players(ServerModels.ClientServerView server) {
        return Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"));
    }

    default Async<Void> playerAction(ServerModels.ClientServerView server, RemotelyServerApi.PlayerAction action) {
        return Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"));
    }

    default Async<RemotelyServerApi.PlayerDetails> playerDetails(ServerModels.ClientServerView server, UUID playerId) {
        return Async.failed(new UnsupportedOperationException("Player Details Are Unavailable"));
    }

    default Availability availability(ServerModels.ClientServerView server, Capability capability) {
        if (server == null) {
            return Availability.missing("No Server Is Selected");
        }
        return Availability.missing("Server Capability Is Unavailable");
    }

    default Availability availability(ServerModels.ClientServerView server, String action) {
        Capability capability = capability(action);
        return capability == null ? server == null ? Availability.missing("No Server Is Selected") : Availability.supported() : availability(server, capability);
    }

    default Async<RemotelyServerApi.ServerCapabilities> refresh(ServerModels.ClientServerView server) {
        return Async.failed(new UnsupportedOperationException("Server Capability Inventory Is Unavailable"));
    }

    default boolean supports(ServerModels.ClientServerView server, Capability capability) {
        return availability(server, capability).available();
    }

    default String unavailableReason(ServerModels.ClientServerView server, Capability capability) {
        Availability state = availability(server, capability);
        return state.available() ? "" : state.reason();
    }

    private static Capability capability(String action) {
        if (action == null || action.isBlank()) return null;
        for (Capability capability : Capability.values()) {
            if (capability.action().equals(action)) return capability;
        }
        return null;
    }

    private static int memoryLimitMegabytes(long bytes) {
        if (bytes <= 0) return 0;
        long megabytes = Math.round(bytes / (1024d * 1024d));
        return megabytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1, megabytes);
    }

    default Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(Instance instance, String directory) {
        return Async.failed(new UnsupportedOperationException("Server Files Are Unavailable"));
    }

    default Async<String> readFile(Instance instance, String path) {
        return Async.failed(new UnsupportedOperationException("Server Files Are Unavailable"));
    }

    default Async<Void> writeFile(Instance instance, String path, String content) {
        return Async.failed(new UnsupportedOperationException("Server Files Are Unavailable"));
    }

    default DeveloperCapabilityProvider developer(Instance instance) {
        return DeveloperCapabilityProvider.unavailable();
    }

    default DeveloperCapabilityProvider developer(String serverId) {
        return DeveloperCapabilityProvider.unavailable();
    }

    default Async<Void> sendCommand(Instance instance, String command) {
        return Async.failed(new UnsupportedOperationException("Server Console Is Unavailable"));
    }

    default Async<String> startServer(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Server Start Is Unavailable"));
    }

    default Async<Void> stopServer(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Server Stop Is Unavailable"));
    }

    default Async<Void> killServer(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Server Kill Is Unavailable"));
    }

    default Async<Void> setPower(Instance instance, String signal) {
        return Async.failed(new UnsupportedOperationException("Server Lifecycle Is Unavailable"));
    }

    default Async<ServerModels.ServerStatus> status(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Server Status Is Unavailable"));
    }

    default Async<ServerHealth> health(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Server Health Is Unavailable"));
    }

    default Async<ServerModels.WebsocketData> consoleSession(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Server Console Is Unavailable"));
    }

    default Async<ServerModels.ServerStats> stats(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Server Statistics Are Unavailable"));
    }

    default Async<ServerModels.ReSyncConfig> reSyncConfig(Instance instance) {
        return Async.failed(new UnsupportedOperationException("ReSync Is Unavailable"));
    }

    default Async<String> reSyncApiKey(Instance instance) {
        return Async.failed(new UnsupportedOperationException("ReSync Is Unavailable"));
    }

    default Async<String> reSyncVersion(Instance instance) {
        return Async.failed(new UnsupportedOperationException("ReSync Is Unavailable"));
    }

    default Async<ServerModels.ReSyncProvisionResult> provisionReSync(Instance instance) {
        return Async.failed(new UnsupportedOperationException("ReSync Provisioning Is Unavailable"));
    }

    default Async<ServerModels.ReSyncProvisionResult> updateReSync(Instance instance) {
        return Async.failed(new UnsupportedOperationException("ReSync Provisioning Is Unavailable"));
    }

    default String backendType(Instance instance) {
        if (instance == null || instance.getBackendConfig() == null || instance.getBackendConfig().type == null || instance.getBackendConfig().type.isBlank()) {
            return "LOCAL";
        }
        return instance.getBackendConfig().type;
    }

    default Async<List<RemotelyServerApi.Player>> players(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"));
    }

    default Async<Void> playerAction(Instance instance, RemotelyServerApi.PlayerAction action) {
        return Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"));
    }

    default Async<PlayerDossier> playerDetails(Instance instance, UUID playerId) {
        FlowManager flowManager = flowManager();
        String serverId = serverId(instance);
        if (flowManager == null || serverId.isBlank() || playerId == null || !flowManager.isFlowClientConnected(serverId)) {
            return Async.failed(new UnsupportedOperationException("Player Details Are Unavailable"));
        }
        flowManager.requestPlayerDossier(serverId, playerId);
        PlayerDossier dossier = flowManager.getPlayerDossier(serverId, playerId);
        return dossier == null ? Async.failed(new IllegalStateException("Player Details Are Loading")) : Async.completed(dossier);
    }

    default Async<List<PlayerSession>> playerSessions(Instance instance, UUID playerId) {
        return playerDetails(instance, playerId).thenApply(dossier -> dossier.getSessions().stream().map(session -> {
            PlayerSession result = new PlayerSession(playerId, dossier.getPlayerName(), "", session.getStartedAt());
            result.endTime = session.getEndedAt();
            return result;
        }).toList());
    }

    default Async<PlayerData> playerData(Instance instance, UUID playerId) {
        return playerDetails(instance, playerId).thenApply(dossier -> {
            var contribution = ReSyncPlayerDataAdapter.adapt(dossier, 0L);
            return contribution == null ? PlayerData.empty() : contribution.data();
        });
    }

    default Async<PlayerOperationResult> playerOperation(Instance instance, PlayerOperation operation) {
        FlowManager flowManager = flowManager();
        String serverId = serverId(instance);
        if (flowManager == null || serverId.isBlank() || operation == null || !flowManager.isFlowClientConnected(serverId)) {
            return Async.failed(new UnsupportedOperationException("Player Action Is Unavailable"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        String action = operation.type().name().toLowerCase(Locale.ROOT);
        if (operation.type() == PlayerOperation.Type.INVENTORY_EDIT) {
            action = "inventoryEditBatch";
            payload.put("baseInventoryRevision", operation.baseRevision());
            payload.put("edits", operation.inventoryEdits().stream().map(edit -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("slot", edit.slot());
                value.put("itemId", edit.item() == null ? "minecraft:air" : edit.item().id());
                value.put("count", edit.item() == null ? 0 : edit.item().count());
                value.put("item", edit.item() == null ? Map.of() : edit.item().tag());
                return value;
            }).toList());
        } else {
            payload.put("text", operation.text());
            payload.put("flag", operation.flag());
        }
        return flowManager.requestPlayerControl(serverId, action, operation.playerId(), payload)
            .thenApply(response -> new PlayerOperationResult(operation.operationId(), response.has("success") && response.get("success").getAsBoolean(),
                response.has("reason") ? response.get("reason").getAsString() : "", response.has("revision") ? response.get("revision").getAsLong() : 0L, null));
    }

    default List<IPlayerSource> supplementalPlayerSources(Instance instance, TerminalWidget terminal) {
        return List.of();
    }

    default List<IActionExecutor> supplementalPlayerActionExecutors(Instance instance, TerminalWidget terminal) {
        return List.of();
    }

    default Optional<ServerUiCapabilityProvider.PlayerHistory> playerHistory(Instance instance, RebaseAPI api,
                                                                               TerminalWidget terminal, Function<String, UUID> uuidResolver) {
        return Optional.empty();
    }

    default List<IPlayerSource> standardPlayerSources(Instance instance, RebaseAPI api, TerminalWidget terminal,
                                                      IPlayerHistoryCollector historyCollector) {
        return List.of();
    }

    default List<PlayerDataSource> playerDataSources(Instance instance, RebaseAPI api) {
        return List.of();
    }

    default Async<String> readPlayerActions(Instance instance) {
        return Async.failed(new UnsupportedOperationException("Player Actions Are Unavailable"));
    }

    default Optional<ServerUiCapabilityProvider.DataStream> dataStream(Instance instance) {
        return Optional.empty();
    }

    private static FlowManager flowManager() {
        return RemotelyClient.INSTANCE == null ? null : RemotelyClient.INSTANCE.getFlowManager();
    }

    default Optional<GlyphPreviewAccess> glyphPreview(Instance instance) {
        return Optional.empty();
    }

    Availability availability(Instance instance, Capability capability);

    default CompletableFuture<ResourceUsage> resourceUsage(Instance instance) {
        if (instance == null || instance.getBackend() == null) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Resource Usage Is Unavailable"));
        }
        return instance.getBackend().getFeature(ResourceUsageFeature.class)
                .map(feature -> feature.getResources().thenApply(value -> value == null ? null : new ResourceUsage(
                        value.memoryBytes(), value.cpuPercent(), value.memoryLimitBytes(), value.uptimeMs())))
                .orElseGet(() -> CompletableFuture.failedFuture(new UnsupportedOperationException("Resource Usage Is Unavailable")));
    }

    record ResourceUsage(long memoryBytes, double cpuPercent, long memoryLimitBytes, long uptimeMs) {
    }

    default boolean isPanelManaged(Instance instance) {
        String type = backendType(instance);
        return "PTERO".equalsIgnoreCase(type) || "CALAGOPUS".equalsIgnoreCase(type);
    }

    default boolean supports(Instance instance, Capability capability) {
        return availability(instance, capability).available();
    }

    default String unavailableReason(Instance instance, Capability capability) {
        Availability state = availability(instance, capability);
        return state.available() ? "" : state.reason();
    }

    default String serverId(Object server) {
        if (server instanceof Instance value) return serverId(value);
        if (server instanceof ServerModels.ClientServerView value) return serverId(value);
        return "";
    }

    default String serverName(Object server) {
        if (server instanceof ServerModels.ClientServerView value) {
            return value.name == null ? "" : value.name;
        }
        Instance value = instance(server);
        return value == null || value.getName() == null ? "" : value.getName();
    }

    default void logOutput(Object server, int lineNumber, String line) {
        if (server instanceof ServerModels.ClientServerView) {
            ServerUiCapabilityProvider.super.logOutput(server, lineNumber, line);
            return;
        }
        Instance value = instance(server);
        if (value != null) value.onLogOutput(lineNumber, line);
    }

    default Object playerDataApi(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.playerDataApi(server);
        }
        Instance value = instance(server);
        if (value == null) return null;
        try {
            return RebaseApiFactory.get(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    default String serverDataRoot(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.serverDataRoot(server);
        }
        Instance value = instance(server);
        return value == null || value.getPath() == null ? "" : value.getPath().replace('\\', '/');
    }

    default boolean serverRunning(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.serverRunning(server);
        }
        Instance value = instance(server);
        return value != null && (value.getState() == InstanceState.RUNNING
                || value.getMSMPManager() != null && value.getMSMPManager().isConnected);
    }

    @Override
    default void preparePlayerSources(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            ServerUiCapabilityProvider.super.preparePlayerSources(server);
            return;
        }
        Instance value = instance(server);
        if (value != null && value.getMSMPManager() != null
                && Boolean.parseBoolean(value.getSettings().getProperty("provider.msmp.enabled", "true"))
                && !value.getMSMPManager().isConnected) {
            value.getMSMPManager().connect();
        }
    }

    default Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(Object server, String directory) {
        if (server instanceof ServerModels.ClientServerView value) return listFiles(value, directory);
        return listFiles(instance(server), directory);
    }

    default Async<String> readFile(Object server, String path) {
        if (server instanceof ServerModels.ClientServerView value) return readFile(value, path);
        return readFile(instance(server), path);
    }

    default Async<Void> writeFile(Object server, String path, String content) {
        if (server instanceof ServerModels.ClientServerView value) return writeFile(value, path, content);
        return writeFile(instance(server), path, content);
    }

    @Override
    default DeveloperCapabilityProvider developer(Object server) {
        if (server instanceof ServerModels.ClientServerView value) return developer(value);
        return developer(instance(server));
    }

    default Async<Void> sendCommand(Object server, String command) {
        if (server instanceof ServerModels.ClientServerView value) return sendCommand(value, command);
        return sendCommand(instance(server), command);
    }

    default Async<String> startServer(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("Server Start Is Unavailable"));
        }
        return startServer(instance(server));
    }

    default Async<Void> stopServer(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("Server Stop Is Unavailable"));
        }
        return stopServer(instance(server));
    }

    default Async<Void> killServer(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("Server Kill Is Unavailable"));
        }
        return killServer(instance(server));
    }

    default Async<Void> setPower(Object server, String signal) {
        if (server instanceof ServerModels.ClientServerView value) return setPower(value, signal);
        return setPower(instance(server), signal);
    }

    default Async<ServerModels.ServerStatus> status(Object server) {
        if (server instanceof ServerModels.ClientServerView value) return status(value);
        return status(instance(server));
    }

    default Async<ServerHealth> health(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("Server Health Is Unavailable"));
        }
        return health(instance(server));
    }

    default Async<ServerModels.WebsocketData> consoleSession(Object server) {
        if (server instanceof ServerModels.ClientServerView value) return consoleSession(value);
        return consoleSession(instance(server));
    }

    default Async<ServerModels.ServerStats> stats(Object server) {
        if (server instanceof ServerModels.ClientServerView value) return stats(value);
        return stats(instance(server));
    }

    default Async<ServerModels.ReSyncConfig> reSyncConfig(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("ReSync Is Unavailable"));
        }
        return reSyncConfig(instance(server));
    }

    default Async<String> reSyncApiKey(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("ReSync Is Unavailable"));
        }
        return reSyncApiKey(instance(server));
    }

    default Async<String> reSyncVersion(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("ReSync Is Unavailable"));
        }
        return reSyncVersion(instance(server));
    }

    default Async<ServerModels.ReSyncProvisionResult> provisionReSync(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("ReSync Provisioning Is Unavailable"));
        }
        return provisionReSync(instance(server));
    }

    default Async<ServerModels.ReSyncProvisionResult> updateReSync(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("ReSync Provisioning Is Unavailable"));
        }
        return updateReSync(instance(server));
    }

    default String backendType(Object server) {
        if (server instanceof ServerModels.ClientServerView value) {
            return value.backendType == null || value.backendType.isBlank() ? "LOCAL" : value.backendType;
        }
        return backendType(instance(server));
    }

    @Override
    default Async<List<RemotelyServerApi.Player>> players(Object server) {
        if (server instanceof ServerModels.ClientServerView value) return players(value);
        return players(instance(server));
    }

    @Override
    default Async<Void> playerAction(Object server, RemotelyServerApi.PlayerAction action) {
        if (server instanceof ServerModels.ClientServerView value) return playerAction(value, action);
        return playerAction(instance(server), action);
    }

    @Override
    default Async<PlayerDossier> playerDetails(Object server, UUID playerId) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.playerDetails(server, playerId);
        }
        return playerDetails(instance(server), playerId);
    }

    @Override
    default Async<List<PlayerSession>> playerSessions(Object server, UUID playerId) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.playerSessions(server, playerId);
        }
        return playerSessions(instance(server), playerId);
    }

    default Async<PlayerData> playerData(Object server, UUID playerId) {
        if (server instanceof ServerModels.ClientServerView) {
            return Async.failed(new UnsupportedOperationException("Player Details Are Unavailable"));
        }
        return playerData(instance(server), playerId);
    }

    @Override
    default Async<PlayerOperationResult> playerOperation(Object server, PlayerOperation operation) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.playerOperation(server, operation);
        }
        return playerOperation(instance(server), operation);
    }

    @Override
    default List<IPlayerSource> supplementalPlayerSources(Object server, TerminalWidget terminal) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.supplementalPlayerSources(server, terminal);
        }
        return supplementalPlayerSources(instance(server), terminal);
    }

    @Override
    default List<IActionExecutor> supplementalPlayerActionExecutors(Object server, TerminalWidget terminal) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.supplementalPlayerActionExecutors(server, terminal);
        }
        return supplementalPlayerActionExecutors(instance(server), terminal);
    }

    @Override
    default Optional<ServerUiCapabilityProvider.PlayerHistory> playerHistory(Object server, Object api, TerminalWidget terminal,
                                                                               Function<String, UUID> uuidResolver) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.playerHistory(server, api, terminal, uuidResolver);
        }
        return playerHistory(instance(server), api instanceof RebaseAPI value ? value : null, terminal, uuidResolver);
    }

    @Override
    default List<IPlayerSource> standardPlayerSources(Object server, Object api, TerminalWidget terminal,
                                                       IPlayerHistoryCollector historyCollector) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.standardPlayerSources(server, api, terminal, historyCollector);
        }
        return standardPlayerSources(instance(server), api instanceof RebaseAPI value ? value : null, terminal, historyCollector);
    }

    @Override
    default List<PlayerDataSource> playerDataSources(Object server, Object api) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.playerDataSources(server, api);
        }
        return playerDataSources(instance(server), api instanceof RebaseAPI value ? value : null);
    }

    @Override
    default Async<String> readPlayerActions(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.readPlayerActions(server);
        }
        return readPlayerActions(instance(server));
    }

    @Override
    default Optional<ServerUiCapabilityProvider.DataStream> dataStream(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return ServerUiCapabilityProvider.super.dataStream(server);
        }
        return dataStream(instance(server));
    }

    @Override
    default Availability availability(Object server, Capability capability) {
        if (server instanceof ServerModels.ClientServerView value) return availability(value, capability);
        return availability(instance(server), capability);
    }

    default CompletableFuture<ResourceUsage> resourceUsage(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Resource Usage Is Unavailable"));
        }
        return resourceUsage(instance(server));
    }

    default Optional<GlyphPreviewAccess> glyphPreview(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return Optional.empty();
        }
        return glyphPreview(instance(server));
    }

    default boolean isPanelManaged(Object server) {
        if (server instanceof ServerModels.ClientServerView) {
            return false;
        }
        return isPanelManaged(instance(server));
    }

    default String unavailableReason(Object server, Capability capability) {
        if (server instanceof ServerModels.ClientServerView value) return unavailableReason(value, capability);
        return unavailableReason(instance(server), capability);
    }

    private static Instance instance(Object server) {
        return server instanceof Instance value ? value : null;
    }

    static DesktopServerUiCapabilities desktop() {
        return DefaultDesktopServerUiCapabilities.INSTANCE;
    }

    static DesktopServerUiCapabilities desktop(Instance instance) {
        return new BoundDesktopServerUiCapabilities(instance);
    }

    static DesktopServerUiCapabilities unavailableDesktop() {
        return UnavailableDesktopServerUiCapabilities.INSTANCE;
    }

    final class UnavailableDesktopServerUiCapabilities implements DesktopServerUiCapabilities {
        private static final UnavailableDesktopServerUiCapabilities INSTANCE = new UnavailableDesktopServerUiCapabilities();

        private UnavailableDesktopServerUiCapabilities() {
        }

        @Override
        public Availability availability(Instance instance, Capability capability) {
            if (instance == null) {
                return Availability.missing("No Server Is Selected");
            }
            return Availability.missing("Server Capability Is Unavailable");
        }
    }

    final class DefaultDesktopServerUiCapabilities implements DesktopServerUiCapabilities {
        private static final DefaultDesktopServerUiCapabilities INSTANCE = new DefaultDesktopServerUiCapabilities();

        private DefaultDesktopServerUiCapabilities() {
        }

        private FileSystemProvider fileSystem(Instance instance) {
            if (instance == null || instance.getBackend() == null || instance.getBackend().getFileSystem() == null) {
                throw new UnsupportedOperationException("Server Files Are Unavailable");
            }
            return instance.getBackend().getFileSystem();
        }

        @Override
        public DeveloperCapabilityProvider developer(Instance instance) {
            return new JvmDeveloperCapabilityProvider(instance);
        }

        @Override
        public Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(Instance instance, String directory) {
            Path path = localPath(instance, directory);
            return JvmAsyncBridge.fromFuture(fileSystem(instance).ls(path).thenApply(entries -> entries.stream().map(entry -> {
                ServerModels.PteroFileObjectAttributes result = new ServerModels.PteroFileObjectAttributes();
                result.name = entry.displayName;
                result.isFile = !entry.isDirectory;
                if (result.isFile) {
                    String exact = entry.metadata == null ? null : entry.metadata.get("sizeBytes");
                    if (exact == null || exact.isBlank()) exact = entry.size;
                    try {
                        result.size = exact == null ? null : Long.parseLong(exact);
                    } catch (NumberFormatException ignored) {
                        result.size = null;
                    }
                }
                return result;
            }).toList()));
        }

        @Override
        public Async<String> readFile(Instance instance, String path) {
            return JvmAsyncBridge.fromFuture(fileSystem(instance).read(localPath(instance, path)));
        }

        @Override
        public Async<ServerModels.ServerStatus> status(Instance instance) {
            if (instance == null) {
                return Async.failed(new UnsupportedOperationException("Server Status Is Unavailable"));
            }
            ServerModels.ServerStatus status = new ServerModels.ServerStatus();
            status.currentState = instance.getState() == null ? "UNKNOWN" : instance.getState().name();
            status.installing = instance.getState() == InstanceState.INSTALLING;
            return Async.completed(status);
        }

        @Override
        public Async<ServerModels.ServerStats> stats(Instance instance) {
            return JvmAsyncBridge.fromFuture(resourceUsage(instance).thenApply(usage -> {
                ServerModels.ServerStats stats = new ServerModels.ServerStats();
                stats.currentState = instance == null || instance.getState() == null ? "UNKNOWN" : instance.getState().name();
                if (usage == null) return stats;
                stats.resources = new ServerModels.ServerResources();
                stats.resources.memoryBytes = usage.memoryBytes();
                stats.resources.cpuAbsolute = usage.cpuPercent();
                stats.resources.uptime = usage.uptimeMs();
                stats.resources.limits = new ServerModels.Limits();
                stats.resources.limits.memory = memoryLimitMegabytes(usage.memoryLimitBytes());
                return stats;
            }));
        }

        @Override
        public Async<ServerModels.ReSyncConfig> reSyncConfig(Instance instance) {
            return reSyncApi(instance, "ReSync Configuration Is Unavailable", RemotelyServerApi::getReSyncConfig);
        }

        @Override
        public Async<String> reSyncApiKey(Instance instance) {
            return reSyncApi(instance, "ReSync Api Key Is Unavailable", RemotelyServerApi::getReSyncApiKey);
        }

        @Override
        public Async<String> reSyncVersion(Instance instance) {
            return reSyncApi(instance, "ReSync Version Is Unavailable", RemotelyServerApi::getReSyncVersion);
        }

        @Override
        public Async<ServerModels.ReSyncProvisionResult> provisionReSync(Instance instance) {
            return reSyncApi(instance, "ReSync Provisioning Is Unavailable", RemotelyServerApi::provisionReSync);
        }

        @Override
        public Async<ServerModels.ReSyncProvisionResult> updateReSync(Instance instance) {
            return reSyncApi(instance, "ReSync Update Is Unavailable", RemotelyServerApi::updateReSync);
        }

        @Override
        public Async<Void> writeFile(Instance instance, String path, String content) {
            return JvmAsyncBridge.fromFuture(fileSystem(instance).write(localPath(instance, path), content));
        }

        @Override
        public Async<Void> sendCommand(Instance instance, String command) {
            return JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().sendCommand(command));
        }

        @Override
        public Async<String> startServer(Instance instance) {
            return JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().startServer());
        }

        @Override
        public Async<Void> stopServer(Instance instance) {
            return JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().stopServer());
        }

        @Override
        public Async<Void> killServer(Instance instance) {
            return JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().killServer());
        }

        @Override
        public Async<List<RemotelyServerApi.Player>> players(Instance instance) {
            PlayerManagementFeature players = instance.getBackend().getFeature(PlayerManagementFeature.class)
                    .orElseThrow(() -> new UnsupportedOperationException("Player Management Is Unavailable"));
            return JvmAsyncBridge.fromFuture(players.getOnlinePlayers().thenApply(values -> values.stream()
                    .map(player -> new RemotelyServerApi.Player(player.uuid(), player.name(), true, false, -1, ""))
                    .toList()));
        }

        @Override
        public Async<Void> playerAction(Instance instance, RemotelyServerApi.PlayerAction action) {
            if (action == null || action.playerId() == null) return Async.failed(new IllegalArgumentException("Player Is Unavailable"));
            PlayerManagementFeature players = instance.getBackend().getFeature(PlayerManagementFeature.class)
                    .orElseThrow(() -> new UnsupportedOperationException("Player Management Is Unavailable"));
            return switch (action.action().toLowerCase(Locale.ROOT)) {
                case "kick" -> JvmAsyncBridge.fromFuture(players.kick(action.playerId(), action.argument()));
                case "ban" -> JvmAsyncBridge.fromFuture(players.ban(action.playerId(), action.argument(), action.flag()));
                case "unban" -> JvmAsyncBridge.fromFuture(players.unban(action.playerId()));
                case "op" -> JvmAsyncBridge.fromFuture(players.setOp(action.playerId(), true));
                case "deop" -> JvmAsyncBridge.fromFuture(players.setOp(action.playerId(), false));
                default -> Async.failed(new UnsupportedOperationException("Unknown Player Action: " + action.action()));
            };
        }

        @Override
        public Async<ServerHealth> health(Instance instance) {
            return JvmAsyncBridge.fromFuture(instance.getBackend().getFeature(ServerHealthFeature.class)
                    .orElseThrow(() -> new UnsupportedOperationException("Server Health Is Unavailable")).checkHealth());
        }

        @Override
        public Optional<GlyphPreviewAccess> glyphPreview(Instance instance) {
            if (instance == null || instance.getPath() == null) return Optional.empty();
            return Optional.of(new DesktopGlyphPreviewAccess(instance, fileSystem(instance), Path.of(instance.getPath())));
        }

        @Override
        public Async<Void> setPower(Instance instance, String signal) {
            String normalized = signal == null ? "" : signal.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "start" -> startServer(instance).thenApply(ignored -> null);
                case "stop" -> stopServer(instance);
                case "kill" -> killServer(instance);
                default -> Async.failed(new IllegalArgumentException("Unknown server power signal: " + signal));
            };
        }

        @Override
        public String backendType(Instance instance) {
            if (instance == null) return "LOCAL";
            String metadata = fileSystem(instance).getMetadata("type");
            return metadata == null || metadata.isBlank() ? DesktopServerUiCapabilities.super.backendType(instance) : metadata;
        }

        @Override
        public List<IPlayerSource> supplementalPlayerSources(Instance instance, TerminalWidget terminal) {
            if (instance == null || instance.getMSMPManager() == null || !Boolean.parseBoolean(instance.getSettings().getProperty("provider.msmp.enabled", "true"))) {
                return List.of();
            }
            return List.of(new MsmpPlayerSource(instance.getMSMPManager()));
        }

        @Override
        public Optional<ServerUiCapabilityProvider.PlayerHistory> playerHistory(Instance instance, RebaseAPI api, TerminalWidget terminal,
                                                                                 Function<String, UUID> uuidResolver) {
            if (instance == null || instance.getPath() == null) return Optional.empty();
            StandardPlayerHistoryProvider history = new StandardPlayerHistoryProvider(instance, api, terminal, Path.of(instance.getPath()), uuidResolver);
            return Optional.of(new ServerUiCapabilityProvider.PlayerHistory(history, history));
        }

        @Override
        public List<IPlayerSource> standardPlayerSources(Instance instance, RebaseAPI api, TerminalWidget terminal,
                                                          IPlayerHistoryCollector historyCollector) {
            if (instance == null || !Boolean.parseBoolean(instance.getSettings().getProperty("provider.standard.enabled", "true"))) {
                return List.of();
            }
            List<IPlayerSource> sources = new ArrayList<>();
            sources.add(new ContentPlayerSourceAdapter(new StandardFileSource(instance, api)));
            if (historyCollector != null) sources.add(new StandardLogSource(instance, historyCollector));
            return List.copyOf(sources);
        }

        @Override
        public List<PlayerDataSource> playerDataSources(Instance instance, RebaseAPI api) {
            if (instance == null) return List.of();
            List<PlayerDataSource> sources = new ArrayList<>();
            sources.add(new WorldPlayerDataSource(instance, api));
            if (Boolean.parseBoolean(instance.getServerProperties().getProperty("enable-rcon", "false"))) {
                sources.add(new RconPlayerDataSource(instance, false));
            }
            return List.copyOf(sources);
        }

        @Override
        public Optional<ServerUiCapabilityProvider.DataStream> dataStream(Instance instance) {
            if (instance == null || instance.getBackend() == null) {
                return Optional.empty();
            }
            return instance.getBackend().getFeature(DataStreamFeature.class).map(feature -> new ServerUiCapabilityProvider.DataStream() {
                @Override
                public Async<Void> streamData(String logPath, List<String> preLoadFiles, BiConsumer<Integer, String> onLine) {
                    return JvmAsyncBridge.fromFuture(feature.streamData(logPath, preLoadFiles, onLine));
                }

                @Override
                public void stopStream() {
                    feature.stopStream();
                }
            });
        }

        @Override
        public Async<String> readPlayerActions(Instance instance) {
            if (instance == null || instance.getPath() == null) return Async.completed(null);
            return JvmAsyncBridge.fromFuture(RebaseApiFactory.get(instance).readFile(Path.of(instance.getPath(), "Remotely", "player-actions.json")));
        }

        @Override
        public Availability availability(Instance instance, Capability capability) {
            if (instance == null) {
                return Availability.missing("No Server Is Selected");
            }
            if (capability == null) {
                return Availability.missing("Capability Is Unknown");
            }
            if (capability == Capability.LOCAL_LIFECYCLE) {
                return isLocal(instance)
                        ? Availability.supported()
                        : Availability.missing("Local Lifecycle Is Unavailable For This Server");
            }
            if (instance.getBackend() == null) {
                return Availability.missing("Server Backend Is Unavailable");
            }
            return switch (capability) {
                case FILES, CONSOLE -> Availability.supported();
                case PLAYERS -> (instance.getMSMPManager() != null && instance.getMSMPManager().isConnected) || instance.getBackend().getFeature(PlayerManagementFeature.class).isPresent()
                        ? Availability.supported()
                        : Availability.missing("Player Management Is Unavailable");
                case HEALTH -> instance.getBackend().getFeature(ServerHealthFeature.class).isPresent()
                        ? Availability.supported()
                        : Availability.missing("Server Health Is Unavailable");
                case RESOURCES -> instance.getBackend().getFeature(ResourceUsageFeature.class).isPresent()
                        ? Availability.supported()
                        : Availability.missing("Resource Usage Is Unavailable");
                case TERMINAL -> instance.getBackend().getFeature(TerminalFeature.class).isPresent()
                        ? Availability.supported()
                        : Availability.missing("Terminal Attach Is Unavailable");
                case LOCAL_LIFECYCLE -> Availability.supported();
                case RESYNC -> Availability.supported();
            };
        }

        private boolean isLocal(Instance instance) {
            String type = backendType(instance);
            return type == null || "LOCAL".equalsIgnoreCase(type.trim().toUpperCase(Locale.ROOT));
        }

        private static Path localPath(Instance instance, String path) {
            if (instance == null || instance.getPath() == null || instance.getPath().isBlank()) {
                throw new UnsupportedOperationException("Server Path Is Unavailable");
            }
            Path root = Path.of(instance.getPath()).toAbsolutePath().normalize();
            String value = path == null || path.isBlank() || "/".equals(path) ? "" : path;
            Path requested = Path.of(value);
            Path resolved = requested.isAbsolute() && requested.normalize().startsWith(root)
                    ? requested.normalize()
                    : root.resolve(value.replaceFirst("^[\\\\/]+", "")).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException("Path Is Outside Server Directory");
            }
            return resolved;
        }

        private static <T> Async<T> reSyncApi(Instance instance, String unavailableMessage,
                                               BiFunction<RemotelyServerApi, String, Async<T>> request) {
            RemotelyClient client = RemotelyClient.INSTANCE;
            String serverId = instance == null ? "" : DesktopServerUiCapabilities.desktop().serverId(instance);
            RemotelyServerApi api = client == null ? null : client.getApiClient();
            if (api == null || serverId.isBlank()) {
                return Async.failed(new UnsupportedOperationException(unavailableMessage));
            }
            return request.apply(api, serverId);
        }

        private static final class ContentPlayerSourceAdapter implements ServerUiCapabilityProvider.ContentPlayerSource {
            private final StandardFileSource delegate;

            private ContentPlayerSourceAdapter(StandardFileSource delegate) {
                this.delegate = delegate;
            }

            @Override
            public void init(PlayerService context) {
                delegate.init(context);
            }

            @Override
            public void enable() {
                delegate.enable();
            }

            @Override
            public void disable() {
                delegate.disable();
            }

            @Override
            public int getPriority() {
                return delegate.getPriority();
            }

            @Override
            public void refresh() {
                delegate.refresh();
            }

            @Override
            public void updateFromContent(String fileName, String content) {
                delegate.updateFromContent(fileName, content);
            }
        }
    }

    final class BoundDesktopServerUiCapabilities implements DesktopServerUiCapabilities {
        private final Instance instance;

        private BoundDesktopServerUiCapabilities(Instance instance) {
            this.instance = instance;
        }

        @Override
        public String serverId(ServerModels.ClientServerView server) {
            return instance == null ? DesktopServerUiCapabilities.super.serverId(server) : DesktopServerUiCapabilities.desktop().serverId(instance);
        }

        @Override
        public Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(ServerModels.ClientServerView server, String directory) {
            return DesktopServerUiCapabilities.desktop().listFiles(instance, directory);
        }

        @Override
        public Async<String> readFile(ServerModels.ClientServerView server, String path) {
            return DesktopServerUiCapabilities.desktop().readFile(instance, path);
        }

        @Override
        public Async<Void> writeFile(ServerModels.ClientServerView server, String path, String content) {
            return DesktopServerUiCapabilities.desktop().writeFile(instance, path, content);
        }

        @Override
        public Async<Void> deleteFiles(ServerModels.ClientServerView server, String root, List<String> files) {
            if (instance == null || instance.getBackend() == null || instance.getBackend().getFileSystem() == null) {
                return Async.failed(new UnsupportedOperationException("File Deletion Is Unavailable"));
            }
            List<Path> paths = files == null ? List.of() : files.stream().map(file -> DefaultDesktopServerUiCapabilities.localPath(instance, file)).toList();
            return JvmAsyncBridge.fromFuture(instance.getBackend().getFileSystem().deleteToTrash(paths));
        }

        @Override
        public Async<Void> renameFiles(ServerModels.ClientServerView server, String root, List<ServerModels.PteroFileRenameItem> files) {
            if (instance == null || instance.getBackend() == null || instance.getBackend().getFileSystem() == null || files == null || files.isEmpty()) {
                return Async.failed(new UnsupportedOperationException("File Rename Is Unavailable"));
            }
            List<Async<Void>> operations = files.stream().map(file -> instance.getBackend().getFileSystem().rename(DefaultDesktopServerUiCapabilities.localPath(instance, file.from), DefaultDesktopServerUiCapabilities.localPath(instance, file.to)))
                    .map(JvmAsyncBridge::fromFuture).toList();
            return Async.allOf(operations.toArray(Async[]::new));
        }

        @Override
        public Async<Void> createFolder(ServerModels.ClientServerView server, String root, String name) {
            if (instance == null || instance.getBackend() == null || instance.getBackend().getFileSystem() == null) {
                return Async.failed(new UnsupportedOperationException("Folder Creation Is Unavailable"));
            }
            return JvmAsyncBridge.fromFuture(instance.getBackend().getFileSystem().createDirectory(DefaultDesktopServerUiCapabilities.localPath(instance, (root == null ? "" : root) + "/" + name)));
        }

        @Override
        public DeveloperCapabilityProvider developer(ServerModels.ClientServerView server) {
            return DesktopServerUiCapabilities.desktop().developer(instance);
        }

        @Override
        public Async<Void> sendCommand(ServerModels.ClientServerView server, String command) {
            return DesktopServerUiCapabilities.desktop().sendCommand(instance, command);
        }

        @Override
        public Async<Void> setPower(ServerModels.ClientServerView server, String signal) {
            return DesktopServerUiCapabilities.desktop().setPower(instance, signal);
        }

        @Override
        public Async<ServerModels.ServerStatus> status(ServerModels.ClientServerView server) {
            ServerModels.ServerStatus result = new ServerModels.ServerStatus();
            result.currentState = instance == null || instance.getState() == null ? "Unknown" : instance.getState().name();
            return Async.completed(result);
        }

        @Override
        public Async<ServerModels.ServerStats> stats(ServerModels.ClientServerView server) {
            return JvmAsyncBridge.fromFuture(DesktopServerUiCapabilities.desktop().resourceUsage(instance).thenApply(usage -> {
                if (usage == null) {
                    return null;
                }
                ServerModels.ServerStats result = new ServerModels.ServerStats();
                result.currentState = instance == null || instance.getState() == null ? "Unknown" : instance.getState().name();
                result.resources = new ServerModels.ServerResources();
                result.resources.memoryBytes = usage.memoryBytes();
                result.resources.cpuAbsolute = usage.cpuPercent();
                result.resources.uptime = usage.uptimeMs();
                result.resources.limits = new ServerModels.Limits();
                result.resources.limits.memory = memoryLimitMegabytes(usage.memoryLimitBytes());
                return result;
            }));
        }

        @Override
        public Async<ServerModels.WebsocketData> consoleSession(ServerModels.ClientServerView server) {
            return Async.failed(new UnsupportedOperationException("Local Terminal Uses The Embedded Console"));
        }

        @Override
        public Async<List<RemotelyServerApi.Player>> players(ServerModels.ClientServerView server) {
            return DesktopServerUiCapabilities.desktop().players(instance);
        }

        @Override
        public Async<Void> playerAction(ServerModels.ClientServerView server, RemotelyServerApi.PlayerAction action) {
            return DesktopServerUiCapabilities.desktop().playerAction(instance, action);
        }

        @Override
        public List<IPlayerSource> supplementalPlayerSources(Instance ignored, TerminalWidget terminal) {
            return DesktopServerUiCapabilities.desktop().supplementalPlayerSources(instance, terminal);
        }

        @Override
        public List<IActionExecutor> supplementalPlayerActionExecutors(Instance ignored, TerminalWidget terminal) {
            return DesktopServerUiCapabilities.desktop().supplementalPlayerActionExecutors(instance, terminal);
        }

        @Override
        public Optional<ServerUiCapabilityProvider.PlayerHistory> playerHistory(Instance ignored, RebaseAPI api,
                                                                                 TerminalWidget terminal, Function<String, UUID> uuidResolver) {
            return DesktopServerUiCapabilities.desktop().playerHistory(instance, api, terminal, uuidResolver);
        }

        @Override
        public List<IPlayerSource> standardPlayerSources(Instance ignored, RebaseAPI api, TerminalWidget terminal,
                                                          IPlayerHistoryCollector historyCollector) {
            return DesktopServerUiCapabilities.desktop().standardPlayerSources(instance, api, terminal, historyCollector);
        }

        @Override
        public List<PlayerDataSource> playerDataSources(Instance ignored, RebaseAPI api) {
            return DesktopServerUiCapabilities.desktop().playerDataSources(instance, api);
        }

        @Override
        public Async<String> readPlayerActions(Instance ignored) {
            return DesktopServerUiCapabilities.desktop().readPlayerActions(instance);
        }

        @Override
        public Optional<ServerUiCapabilityProvider.DataStream> dataStream(Instance ignored) {
            return DesktopServerUiCapabilities.desktop().dataStream(instance);
        }

        @Override
        public Availability availability(ServerModels.ClientServerView server, Capability capability) {
            return DesktopServerUiCapabilities.desktop().availability(instance, capability);
        }

        @Override
        public Availability availability(Instance ignored, Capability capability) {
            return DesktopServerUiCapabilities.desktop().availability(instance, capability);
        }

    }

}
