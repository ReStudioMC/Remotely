package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.managed.PlayerSession;
import redxax.oxy.remotely.data.player.IPlayerHistoryCollector;
import redxax.oxy.remotely.data.player.IPlayerHistoryProvider;
import redxax.oxy.remotely.data.player.action.IActionExecutor;
import redxax.oxy.remotely.data.player.management.PlayerOperation;
import redxax.oxy.remotely.data.player.management.PlayerOperationResult;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.data.player.source.IPlayerSource;
import redxax.oxy.remotely.data.playerdata.PlayerDataSource;
import restudio.rebase.backend.DeveloperCapabilityProvider;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.widgets.TerminalWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface ServerUiCapabilityProvider {
    enum Capability {
        FILES("files.list"),
        CONSOLE("console.command"),
        PLAYERS("players.list"),
        HEALTH("health.read"),
        RESOURCES("resources.read"),
        TERMINAL("console.terminal"),
        LOCAL_LIFECYCLE("server.lifecycle"),
        RESYNC("resync.connect");

        private final String action;

        Capability(String action) {
            this.action = action;
        }

        public String action() {
            return action;
        }
    }

    record Availability(boolean available, String reason) {
        public Availability {
            reason = reason == null ? "" : reason.trim();
        }

        public static Availability supported() {
            return new Availability(true, "");
        }

        public static Availability missing(String reason) {
            return new Availability(false, reason);
        }
    }

    default String serverId(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.identifier != null && !server.identifier.isBlank()) return server.identifier;
        return server.uuid == null ? "" : server.uuid;
    }

    default String serverId(Object server) {
        return server instanceof ServerModels.ClientServerView view ? serverId(view) : "";
    }

    default String serverName(Object server) {
        return server instanceof ServerModels.ClientServerView view && view.name != null ? view.name : "";
    }

    default void logOutput(Object server, int lineNumber, String line) {
    }

    default Object playerDataApi(Object server) {
        return null;
    }

    default String serverDataRoot(Object server) {
        return "";
    }

    default boolean serverRunning(Object server) {
        return true;
    }

    default void preparePlayerSources(Object server) {
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

    default DeveloperCapabilityProvider developer(Object server) {
        String id = serverId(server);
        return id.isBlank() ? DeveloperCapabilityProvider.unavailable() : developer(id);
    }

    default DeveloperCapabilityProvider developer(String serverId) {
        return DeveloperCapabilityProvider.unavailable();
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

    default Async<List<RemotelyServerApi.Player>> players(Object server) {
        return server instanceof ServerModels.ClientServerView view ? players(view)
                : Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"));
    }

    default Async<Void> playerAction(Object server, RemotelyServerApi.PlayerAction action) {
        return server instanceof ServerModels.ClientServerView view ? playerAction(view, action)
                : Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"));
    }

    default Async<PlayerDossier> playerDetails(Object server, UUID playerId) {
        return Async.failed(new UnsupportedOperationException("Player Details Are Unavailable"));
    }

    default Async<List<PlayerSession>> playerSessions(Object server, UUID playerId) {
        return Async.failed(new UnsupportedOperationException("Player History Is Unavailable"));
    }

    default Async<PlayerOperationResult> playerOperation(Object server, PlayerOperation operation) {
        return Async.failed(new UnsupportedOperationException("Player Action Is Unavailable"));
    }

    default Availability availability(ServerModels.ClientServerView server, Capability capability) {
        return server == null ? Availability.missing("No Server Is Selected") : Availability.missing("Server Capability Is Unavailable");
    }

    default Availability availability(ServerModels.ClientServerView server, String action) {
        return server == null ? Availability.missing("No Server Is Selected") : Availability.missing("Server Capability Is Unavailable");
    }

    default Async<RemotelyServerApi.ServerCapabilities> refresh(ServerModels.ClientServerView server) {
        return Async.failed(new UnsupportedOperationException("Server Capability Inventory Is Unavailable"));
    }

    default void addCapabilityListener(Runnable listener) {
    }

    default void removeCapabilityListener(Runnable listener) {
    }

    default List<IPlayerSource> supplementalPlayerSources(Object server, TerminalWidget terminal) {
        return List.of();
    }

    default List<IActionExecutor> supplementalPlayerActionExecutors(Object server, TerminalWidget terminal) {
        return List.of();
    }

    default Optional<PlayerHistory> playerHistory(Object server, Object api, TerminalWidget terminal,
                                                  Function<String, UUID> uuidResolver) {
        return Optional.empty();
    }

    default List<IPlayerSource> standardPlayerSources(Object server, Object api, TerminalWidget terminal,
                                                       IPlayerHistoryCollector historyCollector) {
        return List.of();
    }

    default List<PlayerDataSource> playerDataSources(Object server, Object api) {
        return List.of();
    }

    default Async<String> readPlayerActions(Object server) {
        return Async.failed(new UnsupportedOperationException("Player Actions Are Unavailable"));
    }

    default Optional<DataStream> dataStream(Object server) {
        return Optional.empty();
    }

    default boolean supports(ServerModels.ClientServerView server, Capability capability) {
        return availability(server, capability).available();
    }

    default Availability availability(Object server, Capability capability) {
        return server instanceof ServerModels.ClientServerView view ? availability(view, capability)
                : server == null ? Availability.missing("No Server Is Selected") : Availability.missing("Server Capability Is Unavailable");
    }

    default boolean supports(Object server, Capability capability) {
        return availability(server, capability).available();
    }

    default Async<RemotelyServerApi.ServerCapabilities> refresh(Object server) {
        return server instanceof ServerModels.ClientServerView view ? refresh(view)
                : Async.failed(new UnsupportedOperationException("Server Capability Inventory Is Unavailable"));
    }

    record PlayerHistory(IPlayerHistoryProvider provider, IPlayerHistoryCollector collector) {
    }

    interface ContentPlayerSource extends IPlayerSource {
        void updateFromContent(String fileName, String content);
    }

    interface DataStream {
        Async<Void> streamData(String logPath, List<String> preLoadFiles, BiConsumer<Integer, String> onLine);

        void stopStream();
    }

    static ServerUiCapabilityProvider unavailable() {
        return UnavailableServerUiCapabilityProvider.INSTANCE;
    }

    static ServerUiCapabilityProvider api(RemotelyServerApi api) {
        return new ApiServerUiCapabilityProvider(api, null);
    }

    static ServerUiCapabilityProvider api(RemotelyServerApi api, FlowManager flowManager) {
        return new ApiServerUiCapabilityProvider(api, flowManager);
    }

    final class ApiServerUiCapabilityProvider implements ServerUiCapabilityProvider {
        private final RemotelyServerApi api;
        private final FlowManager flowManager;
        private final Map<String, RemotelyServerApi.ServerCapabilities> inventories = new LinkedHashMap<>();
        private final Map<String, String> inventoryFailures = new LinkedHashMap<>();
        private final List<Runnable> capabilityListeners = new ArrayList<>();

        private ApiServerUiCapabilityProvider(RemotelyServerApi api, FlowManager flowManager) {
            this.api = api;
            this.flowManager = flowManager;
        }

        @Override
        public Async<List<ServerModels.PteroFileObjectAttributes>> listFiles(ServerModels.ClientServerView server, String directory) {
            return requiredApi().listFiles(requiredId(server), directory);
        }

        @Override
        public Async<String> readFile(ServerModels.ClientServerView server, String path) {
            return requiredApi().getFileContent(requiredId(server), path);
        }

        @Override
        public Async<Void> writeFile(ServerModels.ClientServerView server, String path, String content) {
            return requiredApi().writeFile(requiredId(server), path, content);
        }

        @Override
        public Async<Void> deleteFiles(ServerModels.ClientServerView server, String root, List<String> files) {
            return requiredApi().deleteFiles(requiredId(server), root, files);
        }

        @Override
        public Async<Void> renameFiles(ServerModels.ClientServerView server, String root, List<ServerModels.PteroFileRenameItem> files) {
            return requiredApi().renameFiles(requiredId(server), root, files);
        }

        @Override
        public Async<Void> createFolder(ServerModels.ClientServerView server, String root, String name) {
            return requiredApi().createFolder(requiredId(server), root, name);
        }

        @Override
        public DeveloperCapabilityProvider developer(String serverId) {
            return api == null ? DeveloperCapabilityProvider.unavailable() : api.developer(serverId);
        }

        @Override
        public Async<Void> sendCommand(ServerModels.ClientServerView server, String command) {
            return requiredApi().sendServerCommand(requiredId(server), command);
        }

        @Override
        public Async<Void> setPower(ServerModels.ClientServerView server, String signal) {
            return requiredApi().setServerPower(requiredId(server), signal);
        }

        @Override
        public Async<ServerModels.ServerStatus> status(ServerModels.ClientServerView server) {
            return requiredApi().getServerStatus(requiredId(server));
        }

        @Override
        public Async<ServerModels.ServerStats> stats(ServerModels.ClientServerView server) {
            return requiredApi().getServerResources(requiredId(server));
        }

        @Override
        public Async<ServerModels.WebsocketData> consoleSession(ServerModels.ClientServerView server) {
            return requiredApi().getServerWebsocket(requiredId(server));
        }

        @Override
        public Async<List<RemotelyServerApi.Player>> players(ServerModels.ClientServerView server) {
            String serverId = requiredId(server);
            return refresh(server).thenCompose(inventory -> inventory.action("players.list").supported()
                    ? requiredApi().getPlayers(serverId)
                    : Async.failed(new UnsupportedOperationException("Player Management Is Unavailable")))
                    .thenCompose(result -> result == null || !result.supported()
                    ? Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"))
                    : Async.completed(result.players()));
        }

        @Override
        public Async<Void> playerAction(ServerModels.ClientServerView server, RemotelyServerApi.PlayerAction action) {
            if (action == null || action.playerId() == null) {
                return Async.failed(new UnsupportedOperationException("Player Management Is Unavailable"));
            }
            return requiredApi().executePlayerAction(requiredId(server), action);
        }

        @Override
        public List<IActionExecutor> supplementalPlayerActionExecutors(Object server, TerminalWidget terminal) {
            String serverId = serverId(server);
            if (serverId.isBlank() || api == null) return List.of();
            return List.of(new BrowserPlayerActionExecutor(api, serverId));
        }

        @Override
        public Async<String> readPlayerActions(Object server) {
            String serverId = serverId(server);
            if (serverId.isBlank()) return Async.failed(new UnsupportedOperationException("Player Actions Are Unavailable"));
            return refresh(server).thenCompose(inventory -> inventory.action("players.actions").supported()
                    ? requiredApi().getFileContent(serverId, "Remotely/player-actions.json").exceptionally(ignored -> "")
                    : Async.completed(""));
        }

        @Override
        public Async<RemotelyServerApi.PlayerDetails> playerDetails(ServerModels.ClientServerView server, UUID playerId) {
            if (flowManager == null || playerId == null) return Async.failed(new UnsupportedOperationException("ReSync Player Details Are Unavailable"));
            String serverId = requiredId(server);
            return flowManager.requestOnlinePlayers(serverId).thenCompose(players -> {
                var dossier = flowManager.getPlayerDossier(serverId, playerId);
                if (dossier == null) return Async.failed(new IllegalStateException("Player Details Are Unavailable"));
                Map<String, Object> data = new LinkedHashMap<>();
                dossier.getFacets().forEach((facet, state) -> data.put(facet, state == null ? Map.of() : state.getData()));
                List<RemotelyServerApi.PlayerSession> sessions = dossier.getSessions().stream()
                        .map(session -> new RemotelyServerApi.PlayerSession(session.getStartedAt(), session.getEndedAt())).toList();
                RemotelyServerApi.Player player = new RemotelyServerApi.Player(playerId, dossier.getPlayerName(), dossier.isOnline(), false, -1, "");
                return Async.completed(new RemotelyServerApi.PlayerDetails(player, dossier.getFirstSeenAt(), dossier.getLastSeenAt(),
                        dossier.getTotalPlayTimeMs(), sessions, data));
            });
        }

        @Override
        public Availability availability(ServerModels.ClientServerView server, Capability capability) {
            return availability(server, capability == null ? "" : capability.action());
        }

        @Override
        public Availability availability(ServerModels.ClientServerView server, String action) {
            if (api == null || serverId(server).isBlank()) return Availability.missing("Server Identifier Is Unavailable");
            String serverId = serverId(server);
            RemotelyServerApi.ServerCapabilities inventory = inventories.get(serverId);
            if (inventory == null) {
                String failure = inventoryFailures.get(serverId);
                return Availability.missing(failure == null || failure.isBlank() ? "Server Capabilities Are Loading" : failure);
            }
            RemotelyServerApi.CapabilityAvailability state = inventory.action(action);
            if (state.supported() && reSyncAction(action) && !"resync.websocket".equals(state.transport())) {
                return Availability.missing("ReSync WebSocket Transport Is Unavailable");
            }
            return state.supported() ? Availability.supported() : Availability.missing(state.reason().isBlank() ? "Capability Is Unavailable" : state.reason());
        }

        @Override
        public Async<RemotelyServerApi.ServerCapabilities> refresh(ServerModels.ClientServerView server) {
            String serverId = requiredId(server);
            return requiredApi().getServerCapabilities(serverId).whenComplete((inventory, failure) -> {
                if (failure == null && inventory != null) {
                    inventories.put(serverId, inventory);
                    inventoryFailures.remove(serverId);
                } else {
                    inventories.remove(serverId);
                    inventoryFailures.put(serverId, failure == null || failure.getMessage() == null ? "Server Capability Inventory Is Unavailable" : failure.getMessage());
                }
                notifyCapabilityListeners();
            });
        }

        @Override
        public synchronized void addCapabilityListener(Runnable listener) {
            if (listener != null && !capabilityListeners.contains(listener)) capabilityListeners.add(listener);
        }

        @Override
        public synchronized void removeCapabilityListener(Runnable listener) {
            capabilityListeners.remove(listener);
        }

        private void notifyCapabilityListeners() {
            List<Runnable> listeners;
            synchronized (this) {
                listeners = List.copyOf(capabilityListeners);
            }
            listeners.forEach(listener -> {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                }
            });
        }

        private RemotelyServerApi requiredApi() {
            if (api == null) throw new IllegalStateException("Server API Is Unavailable");
            return api;
        }

        private String requiredId(ServerModels.ClientServerView server) {
            String id = serverId(server);
            if (id.isBlank()) throw new IllegalArgumentException("A server identifier is required");
            return id;
        }

        private static boolean reSyncAction(String action) {
            return action != null && (action.startsWith("world.") || action.startsWith("resync."));
        }

        private static final class BrowserPlayerActionExecutor implements IActionExecutor {
            private final RemotelyServerApi api;
            private final String serverId;

            private BrowserPlayerActionExecutor(RemotelyServerApi api, String serverId) {
                this.api = api;
                this.serverId = serverId;
            }

            @Override
            public boolean canExecute(String actionType) {
                return "command".equals(actionType);
            }

            @Override
            public Async<Void> execute(UnifiedPlayer player, String actionType, Object... args) {
                if (player == null || player.getUuid() == null) {
                    return Async.failed(new IllegalArgumentException("Player Is Unavailable"));
                }
                String command = args.length > 0 && args[0] instanceof String value ? value : "";
                RemotelyServerApi.PlayerAction action = new RemotelyServerApi.PlayerAction(
                        actionType, player.getUuid(), player.getName(), command, false);
                return api.executePlayerAction(serverId, action);
            }

            @Override
            public int getPriority() {
                return 30;
            }
        }
    }

    final class UnavailableServerUiCapabilityProvider implements ServerUiCapabilityProvider {
        private static final UnavailableServerUiCapabilityProvider INSTANCE = new UnavailableServerUiCapabilityProvider();

        private UnavailableServerUiCapabilityProvider() {
        }
    }
}
