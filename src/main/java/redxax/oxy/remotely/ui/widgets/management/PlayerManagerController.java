package redxax.oxy.remotely.ui.widgets.management;

import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import redxax.oxy.remotely.data.managed.PlayerAction;
import redxax.oxy.remotely.data.managed.PlayerSession;
import redxax.oxy.remotely.data.player.IPlayerHistoryProvider;
import redxax.oxy.remotely.data.player.PlayerService;
import redxax.oxy.remotely.data.player.PlayerUpdateBatch;
import redxax.oxy.remotely.data.player.management.PlayerManagementService;
import redxax.oxy.remotely.data.player.management.PlayerOperation;
import redxax.oxy.remotely.data.player.management.PlayerOperationResult;
import redxax.oxy.remotely.data.player.management.PlayerOperationRouter;
import redxax.oxy.remotely.data.playerdata.PlayerDataManager;
import redxax.oxy.remotely.data.playerdata.PlayerDataSource;
import redxax.oxy.remotely.data.playerdata.PlayerItem;
import redxax.oxy.remotely.data.player.action.IActionExecutor;
import redxax.oxy.remotely.data.player.action.StandardActionExecutor;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.data.player.source.IPlayerSource;
import redxax.oxy.remotely.session.StreamDataParser;
import redxax.oxy.remotely.ui.server.ServerUiCapabilityProvider;
import redxax.oxy.remotely.ui.server.containers.PlayersContainer;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;

import java.util.*;
import restudio.rescreen.platform.Async;
import java.util.function.Consumer;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.managed.PlayerActionJson;

public class PlayerManagerController {

    public enum LoadState {
        LOADING,
        LOADED,
        UNAVAILABLE,
        FAILED
    }

    public record PlayerSnapshot(List<UnifiedPlayer> players, boolean baseline) {
    }

    private static final Map<String, PlayerManagerController> registry = new HashMap<>();

    private final Object instance;
    private volatile ServerUiCapabilityProvider capabilities;
    private final Runnable capabilityListener = this::capabilitiesChanged;
    private PlayersContainer container;
    private PlayerService playerService;
    private PlayerDataManager playerDataManager;
    private PlayerManagementService playerManagementService;

    private IPlayerHistoryProvider historyProvider;

    private TerminalWidget terminalWidget;
    private boolean isInitialized = false;
    private boolean listenerRegistered = false;
    private ServerUiCapabilityProvider.ContentPlayerSource standardFileSource;
    private volatile List<PlayerAction> playerActions = List.of();
    private int uiToken = 0;
    private volatile int playerActionsLoadToken = 0;
    private final Map<UUID, Integer> reSyncWatchCounts = new HashMap<>();
    private long reSyncWatchGeneration;
    private final Map<UUID, UnifiedPlayer> operationPlayers = BrowserSafeState.map();
    private final PlayerOperationRouter operationRouter = new PlayerOperationRouter();
    private final List<Consumer<PlayerSnapshot>> playerListeners = BrowserSafeState.list();
    private final BrowserSafeState.BooleanValue backgroundDataStreamActive = new BrowserSafeState.BooleanValue();
    private volatile boolean playerBaselineActive;
    private volatile boolean nextPlayerSnapshotBaseline;
    private volatile LoadState loadState = LoadState.LOADING;
    private volatile String loadFailure = "";
    private volatile ServerUiCapabilityProvider.Availability playerAvailability;
    private UnifiedPlayerSource unifiedPlayerSource;

    private PlayerManagerController(Object instance) {
        this(instance, defaultCapabilities());
    }

    private PlayerManagerController(Object instance, ServerUiCapabilityProvider capabilities) {
        this.instance = instance;
        this.capabilities = capabilities == null ? ServerUiCapabilityProvider.unavailable() : capabilities;
        this.playerAvailability = this.capabilities.availability(instance, ServerUiCapabilityProvider.Capability.PLAYERS);
        this.capabilities.addCapabilityListener(capabilityListener);
        operationRouter.register("resync", 200, operation -> operation.type() == PlayerOperation.Type.INVENTORY_EDIT && operationPlayers.containsKey(operation.playerId())
                && canEditPlayerInventory(operationPlayers.get(operation.playerId())) && operation.baseRevision() >= 0L, this::executeReSyncOperation);
        operationRouter.register("player-service", 100, operation -> operation.type() != PlayerOperation.Type.INVENTORY_EDIT && isServerRunning()
                && playerService != null && playerService.supportsAction(actionName(operation.type())), this::executePlayerServiceOperation);
    }

    public static PlayerManagerController getOrCreate(Object instance) {
        return getOrCreate(instance, defaultCapabilities());
    }

    public static PlayerManagerController getOrCreate(Object instance, ServerUiCapabilityProvider capabilities) {
        ServerUiCapabilityProvider selected = capabilities == null ? defaultCapabilities() : capabilities;
        synchronized (registry) {
            String key = serverKey(instance, selected);
            PlayerManagerController c = registry.get(key);
            if (c == null) {
                c = new PlayerManagerController(instance, selected);
                registry.put(key, c);
            } else {
                c.adoptCapabilities(selected);
            }
            return c;
        }
    }

    private synchronized void adoptCapabilities(ServerUiCapabilityProvider next) {
        if (next == null || capabilities == next) {
            return;
        }
        capabilities.removeCapabilityListener(capabilityListener);
        capabilities = next;
        playerAvailability = capabilities.availability(instance, ServerUiCapabilityProvider.Capability.PLAYERS);
        capabilities.addCapabilityListener(capabilityListener);
        if (isInitialized) {
            reinitializeService();
        }
    }

    private void capabilitiesChanged() {
        ScreenManager.getInstance().execute(() -> {
            ServerUiCapabilityProvider.Availability next = capabilities.availability(instance, ServerUiCapabilityProvider.Capability.PLAYERS);
            ServerUiCapabilityProvider.Availability previous = playerAvailability;
            playerAvailability = next;
            if (Objects.equals(previous, next)) return;
            if (isInitialized) reinitializeService();
            else applyAvailability(next);
        });
    }

    private static ServerUiCapabilityProvider defaultCapabilities() {
        RemotelyClient client = RemotelyClient.INSTANCE;
        return client == null || client.getServerUiCapabilityProvider() == null
                ? ServerUiCapabilityProvider.unavailable() : client.getServerUiCapabilityProvider();
    }

    private static String serverKey(Object server, ServerUiCapabilityProvider capabilities) {
        if (server == null) return "";
        String id = capabilities == null ? "" : capabilities.serverId(server);
        return id == null || id.isBlank() ? Integer.toHexString(System.identityHashCode(server)) : id;
    }

    public void reloadProviders() {
        uiToken++;
        ensureServiceInitialized();
        ensureListenerRegistered();
        if (container != null && playerService != null) container.syncUi(playerService.getRegistry().getAll());
    }

    private void ensureServiceInitialized() {
        if (this.playerService != null && this.playerDataManager != null) return;
        initializeService(false);
    }

    private void reinitializeService() {
        uiToken++;
        initializeService(true);
        ensureListenerRegistered();
        if (container != null && playerService != null) container.syncUi(playerService.getRegistry().getAll());
    }

    private void ensureListenerRegistered() {
        if (playerService == null || listenerRegistered) return;
        playerService.addListener(snapshot -> {
            int token = uiToken;
            boolean baseline = nextPlayerSnapshotBaseline;
            nextPlayerSnapshotBaseline = false;
            ScreenManager.getInstance().execute(() -> {
                if (container != null && token == uiToken) container.syncUi(snapshot);
                List<UnifiedPlayer> stableSnapshot = snapshot == null ? List.of() : List.copyOf(snapshot);
                PlayerSnapshot playerSnapshot = new PlayerSnapshot(stableSnapshot, baseline);
                for (Consumer<PlayerSnapshot> listener : playerListeners) {
                    try {
                        listener.accept(playerSnapshot);
                    } catch (Exception ignored) {
                    }
                }
            });
        });
        listenerRegistered = true;
    }

    private void initializeService(boolean shutdownExisting) {
        if (shutdownExisting) {
            clearReSyncWatches();
            if (this.playerService != null) this.playerService.shutdown();
            if (this.historyProvider != null) this.historyProvider.shutdown();
            if (this.playerManagementService != null) this.playerManagementService.shutdown();
        }

        this.playerService = new PlayerService();
        this.playerDataManager = new PlayerDataManager();
        standardFileSource = null;
        unifiedPlayerSource = null;
        listenerRegistered = false;
        TerminalWidget tw = this.terminalWidget;
        Object api = capabilities.playerDataApi(instance);

        ServerUiCapabilityProvider.Availability availability = capabilities.availability(instance, ServerUiCapabilityProvider.Capability.PLAYERS);
        playerAvailability = availability;
        applyAvailability(availability);
        if (availability.available()) {
            unifiedPlayerSource = new UnifiedPlayerSource(capabilities, instance);
            playerService.registerSource(unifiedPlayerSource);
            playerService.registerExecutor(new UnifiedPlayerActionExecutor(capabilities, instance));
        }
        for (IPlayerSource source : capabilities.supplementalPlayerSources(instance, tw)) {
            if (source != null) playerService.registerSource(source);
        }
        for (IActionExecutor executor : capabilities.supplementalPlayerActionExecutors(instance, tw)) {
            if (executor != null) playerService.registerExecutor(executor);
        }

        ServerUiCapabilityProvider.PlayerHistory playerHistory = capabilities.playerHistory(instance, api, tw, name -> playerService.getRegistry().getAll().stream()
            .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(name))
            .map(UnifiedPlayer::getUuid)
            .findFirst().orElse(null)).orElse(null);
        IPlayerHistoryProvider standardHistory = playerHistory == null ? null : playerHistory.provider();
        this.historyProvider = standardHistory;

        List<IPlayerSource> standardSources = capabilities.standardPlayerSources(instance, api, tw, playerHistory == null ? null : playerHistory.collector());
        for (IPlayerSource source : standardSources) {
            if (source == null) continue;
            if (source instanceof ServerUiCapabilityProvider.ContentPlayerSource contentSource) {
                standardFileSource = contentSource;
            }
            playerService.registerSource(source);
        }
        if (!standardSources.isEmpty()) {
            playerService.registerExecutor(new StandardActionExecutor(tw));
        }

        for (PlayerDataSource source : capabilities.playerDataSources(instance, api)) {
            if (source != null) playerDataManager.registerSource(source);
        }

        if (this.historyProvider != null) this.historyProvider.initialize();
        this.playerManagementService = new PlayerManagementService(playerDataManager, historyProvider, this::getPlayerDossier, this::requestPlayerDossier, this::isReSyncPlayerManagementAvailable);
        loadActionsAsync();
    }

    private synchronized void clearReSyncWatches() {
        FlowManager flowManager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
        String serverId = getReSyncServerId();
        if (flowManager != null && serverId != null && flowManager.isFlowClientConnected(serverId)) {
            ReSyncFlowClient client = flowManager.ensureFlowClient(serverId);
            if (client != null) {
                for (UUID playerId : reSyncWatchCounts.keySet()) client.unwatchPlayer(playerId);
            }
        }
        reSyncWatchCounts.clear();
        reSyncWatchGeneration++;
    }

    private void loadActionsAsync() {
        int loadToken = ++playerActionsLoadToken;
        capabilities.readPlayerActions(instance).thenAccept(content -> {
            List<PlayerAction> loadedActions = List.of();
            if (content != null && !content.isEmpty()) {
                try {
                    List<PlayerAction> loaded = PlayerActionJson.read(content);
                    loadedActions = loaded != null ? List.copyOf(loaded) : List.of();
                } catch (Exception e) {
                    loadedActions = List.of();
                }
            }

            applyLoadedActions(loadToken, loadedActions);
        }).exceptionally(e -> {
            applyLoadedActions(loadToken, List.of());
            return null;
        });
    }

    private void applyLoadedActions(int loadToken, List<PlayerAction> loadedActions) {
        if (loadToken != playerActionsLoadToken) {
            return;
        }
        this.playerActions = loadedActions;
        ScreenManager.getInstance().execute(() -> {
            if (loadToken != playerActionsLoadToken) {
                return;
            }
            if (container != null && playerService != null) {
                container.syncUi(playerService.getRegistry().getAll());
            }
        });
    }

    public void setUiBindings(PlayersContainer container, TerminalWidget terminalWidget) {
        this.container = container;
        boolean terminalChanged = this.terminalWidget != terminalWidget;
        this.terminalWidget = terminalWidget;

        if (!isInitialized || terminalChanged) {
            if (terminalChanged && playerService != null) {
                reinitializeService();
            } else {
                reloadProviders();
            }
            isInitialized = true;
        } else {
             if (container != null && playerService != null) container.syncUi(playerService.getRegistry().getAll());
        }
    }

    public void handleFileUpdate(String fileName, String content) {
        if (standardFileSource != null) {
            standardFileSource.updateFromContent(fileName, content);
        }
    }

    public void fullRefresh() {
        ensureServiceInitialized();
        capabilities.preparePlayerSources(instance);
        ServerUiCapabilityProvider.Availability availability = capabilities.availability(instance, ServerUiCapabilityProvider.Capability.PLAYERS);
        playerAvailability = availability;
        if (!availability.available()) {
            applyAvailability(availability);
            if (isCapabilityLoading(availability)) capabilities.refresh(instance).exceptionally(error -> null);
        } else if (unifiedPlayerSource != null) {
            setLoadState(LoadState.LOADING, "");
        } else {
            setLoadState(LoadState.LOADED, "");
        }
        playerService.refreshSources();
    }

    public LoadState getLoadState() {
        return loadState;
    }

    public String getLoadFailure() {
        return loadFailure;
    }

    public ReSyncLuckPermsClient getLuckPermsClient() {
        FlowManager flowManager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
        String serverId = getReSyncServerId();
        if (flowManager == null || serverId == null || serverId.isBlank() || !flowManager.isFlowClientConnected(serverId)) {
            return null;
        }
        ReSyncFlowClient client = flowManager.ensureFlowClient(serverId);
        return client == null ? null : client.luckPerms();
    }

    public List<PlayerAction> getPlayerActions() {
        return new ArrayList<>(playerActions);
    }

    public int getOnlinePlayerCount() {
        if (playerService == null) {
            return 0;
        }
        return (int) playerService.getRegistry().getAll().stream().filter(UnifiedPlayer::isOnline).count();
    }

    public List<UnifiedPlayer> getOnlinePlayers() {
        if (playerService == null) {
            return List.of();
        }
        return playerService.getRegistry().getAll().stream().filter(UnifiedPlayer::isOnline).toList();
    }

    public void addPlayerListener(Consumer<PlayerSnapshot> listener) {
        if (listener == null || playerListeners.contains(listener)) return;
        playerListeners.add(listener);
        ensureServiceInitialized();
        ensureListenerRegistered();
        listener.accept(new PlayerSnapshot(List.copyOf(playerService.getRegistry().getAll()), true));
        playerService.refreshSources();
        ensureBackgroundDataStream();
    }

    public void removePlayerListener(Consumer<PlayerSnapshot> listener) {
        playerListeners.remove(listener);
        if (playerListeners.isEmpty()) stopBackgroundDataStream();
    }

    public void refreshPlayerSnapshots() {
        ensureServiceInitialized();
        ensureListenerRegistered();
        playerService.refreshSources();
        ensureBackgroundDataStream();
    }

    public void beginPlayerBaseline() {
        ensureServiceInitialized();
        if (playerBaselineActive) return;
        playerBaselineActive = true;
        playerService.beginNotificationBatch();
    }

    public void endPlayerBaseline() {
        if (!playerBaselineActive || playerService == null) return;
        playerBaselineActive = false;
        nextPlayerSnapshotBaseline = true;
        playerService.endNotificationBatch();
    }

    private void ensureBackgroundDataStream() {
        if (playerListeners.isEmpty() || !isServerRunning() || !backgroundDataStreamActive.compareAndSet(false, true)) return;
        Optional<ServerUiCapabilityProvider.DataStream> feature = capabilities.dataStream(instance);
        if (feature.isEmpty()) {
            backgroundDataStreamActive.set(false);
            return;
        }
        String root = capabilities.serverDataRoot(instance);
        if (root == null) root = "";
        String prefix = root.isBlank() || root.endsWith("/") ? root : root + "/";
        String logPath = prefix + "logs/latest.log";
        List<String> preLoadFiles = List.of("ops.json", "banned-players.json", "banned-ips.json", "whitelist.json", "usercache.json").stream()
                .map(file -> prefix + file).toList();
        feature.get().streamData(logPath, preLoadFiles, new StreamDataParser(this))
                .whenComplete((ignored, throwable) -> {
                    endPlayerBaseline();
                    backgroundDataStreamActive.set(false);
                });
    }

    private void stopBackgroundDataStream() {
        if (!backgroundDataStreamActive.compareAndSet(true, false)) return;
        endPlayerBaseline();
        capabilities.dataStream(instance).ifPresent(ServerUiCapabilityProvider.DataStream::stopStream);
    }

    public void refreshPlayerActions() {
        loadActionsAsync();
    }

    public IPlayerHistoryProvider getHistoryProvider() { return historyProvider; }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public PlayerManagementService getPlayerManagementService() {
        ensureServiceInitialized();
        return playerManagementService;
    }

    public Object getInstance() {
        return instance;
    }

    public String getServerName() {
        return capabilities.serverName(instance);
    }

    public void handleLogOutput(int lineNumber, String line) {
        capabilities.logOutput(instance, lineNumber, line);
    }

    public ServerUiCapabilityProvider.Availability playersAvailability() {
        return capabilities.availability(instance, ServerUiCapabilityProvider.Capability.PLAYERS);
    }

    private void applyAvailability(ServerUiCapabilityProvider.Availability availability) {
        if (availability != null && availability.available() || isCapabilityLoading(availability)) {
            setLoadState(LoadState.LOADING, "");
            return;
        }
        String reason = availability == null || availability.reason().isBlank()
                ? "Player Management Is Unavailable" : availability.reason();
        setLoadState(LoadState.UNAVAILABLE, reason);
    }

    private boolean isCapabilityLoading(ServerUiCapabilityProvider.Availability availability) {
        return availability != null && "Server Capabilities Are Loading".equals(availability.reason());
    }

    private void unifiedPlayersLoaded(UnifiedPlayerSource source) {
        if (source == unifiedPlayerSource) setLoadState(LoadState.LOADED, "");
    }

    private void unifiedPlayersFailed(UnifiedPlayerSource source, Throwable failure) {
        if (source != unifiedPlayerSource) return;
        Throwable cause = failure;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String reason = cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? "Could Not Load Players" : cause.getMessage();
        setLoadState(LoadState.FAILED, reason);
    }

    private void setLoadState(LoadState state, String failure) {
        loadState = state;
        loadFailure = failure == null ? "" : failure;
        ScreenManager.getInstance().execute(() -> {
            if (container != null && playerService != null) container.syncUi(playerService.getRegistry().getAll());
        });
    }

    public String getReSyncServerId() {
        return capabilities.serverId(instance);
    }

    public void requestPlayerDossier(UUID playerId) {
        if (playerId != null) capabilities.playerDetails(instance, playerId).exceptionally(error -> null);
    }

    public boolean isReSyncPlayerManagementAvailable() {
        FlowManager flowManager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
        String serverId = getReSyncServerId();
        return flowManager != null && serverId != null && !serverId.isBlank() && flowManager.isFlowClientConnected(serverId);
    }

    public synchronized AutoCloseable watchPlayer(UUID playerId) {
        if (playerId == null) return () -> {};
        long generation = reSyncWatchGeneration;
        int count = reSyncWatchCounts.getOrDefault(playerId, 0);
        reSyncWatchCounts.put(playerId, count + 1);
        if (isReSyncPlayerManagementAvailable()) RemotelyClient.INSTANCE.getFlowManager().watchPlayer(getReSyncServerId(), playerId);
        return () -> {
            if (unwatchPlayer(playerId, generation)) {
                FlowManager flowManager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
                if (flowManager != null && flowManager.isFlowClientConnected(getReSyncServerId())) {
                    ReSyncFlowClient client = flowManager.ensureFlowClient(getReSyncServerId());
                    if (client != null) client.unwatchPlayer(playerId);
                }
            }
        };
    }

    private synchronized boolean unwatchPlayer(UUID playerId, long generation) {
        if (generation != reSyncWatchGeneration) return false;
        int next = reSyncWatchCounts.getOrDefault(playerId, 1) - 1;
        if (next > 0) {
            reSyncWatchCounts.put(playerId, next);
            return false;
        }
        reSyncWatchCounts.remove(playerId);
        return true;
    }

    public boolean canEditPlayerInventory(UnifiedPlayer player) {
        if (player == null || !player.isOnline() || !isReSyncPlayerManagementAvailable()) return false;
        if (getInventoryRevision(player.getUuid()) < 0L) return false;
        PlayerDossier dossier = getPlayerDossier(player.getUuid());
        if (dossier == null || dossier.getFacets().get("playerData") == null) return false;
        FlowManager flowManager = RemotelyClient.INSTANCE.getFlowManager();
        JsonObject capabilities = flowManager.getPlayerControlCapabilities(getReSyncServerId());
        if (capabilities == null || !capabilities.has("operations") || !capabilities.get("operations").isJsonArray()) return false;
        for (var operation : capabilities.getAsJsonArray("operations")) {
            if ("onlineInventoryEdit".equals(operation.getAsString())) return true;
        }
        return false;
    }

    public Async<Boolean> editPlayerInventory(UnifiedPlayer player, Map<String, PlayerItem> edits, long baseRevision) {
        if (!canEditPlayerInventory(player) || edits == null || edits.isEmpty() || baseRevision < 0L) return Async.completed(false);
        List<PlayerOperation.InventoryEdit> inventoryEdits = edits.entrySet().stream().map(entry -> new PlayerOperation.InventoryEdit(entry.getKey(), entry.getValue())).toList();
        PlayerOperation operation = new PlayerOperation(UUID.randomUUID().toString(), PlayerOperation.Type.INVENTORY_EDIT, player.getUuid(), "", false, baseRevision, inventoryEdits);
        operationPlayers.put(player.getUuid(), player);
        return operationRouter.execute(operation).thenApply(PlayerOperationResult::success).whenComplete((success, error) -> operationPlayers.remove(player.getUuid(), player));
    }

    public long getInventoryRevision(UUID playerId) {
        PlayerDossier dossier = getPlayerDossier(playerId);
        if (dossier == null || dossier.getFacets().get("playerData") == null) return -1L;
        Object revision = dossier.getFacets().get("playerData").getData().get("inventoryRevision");
        return revision instanceof Number number ? number.longValue() : -1L;
    }

    public PlayerDossier getPlayerDossier(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        FlowManager flowManager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
        String serverId = getReSyncServerId();
        if (flowManager == null || serverId == null || serverId.isBlank()) {
            return null;
        }
        return flowManager.getPlayerDossier(serverId, playerId);
    }

    public void kickPlayer(UnifiedPlayer player, String reason) {
        executeOperation(player, PlayerOperation.Type.KICK, reason, false).whenComplete((v, e) -> {
            if (e != null) {
                ScreenManager.getInstance().execute(() -> new Notification("Error", e.getMessage(), Notification.Type.ERROR));
            } else {
                playerService.refreshSources();
            }
        });
    }

    public void banPlayer(UnifiedPlayer player, String reason, boolean ipBan) {
        executeOperation(player, PlayerOperation.Type.BAN, reason, ipBan).whenComplete((v, e) -> {
            if (e != null) {
                ScreenManager.getInstance().execute(() -> new Notification("Error", e.getMessage(), Notification.Type.ERROR));
            } else {
                playerService.refreshSources();
            }
        });
    }

    public void unbanPlayer(UnifiedPlayer player) {
        executeOperation(player, PlayerOperation.Type.UNBAN, "", false).whenComplete((v, e) -> {
            if (e != null) {
                ScreenManager.getInstance().execute(() -> new Notification("Error", e.getMessage(), Notification.Type.ERROR));
            } else {
                playerService.refreshSources();
            }
        });
    }

    public void toggleOp(UnifiedPlayer player) {
        String action = player.isOp() ? "deop" : "op";
        executeOperation(player, player.isOp() ? PlayerOperation.Type.DEOP : PlayerOperation.Type.OP, "", false).whenComplete((v, e) -> {
            if (e != null) {
                ScreenManager.getInstance().execute(() -> new Notification("Error", e.getMessage(), Notification.Type.ERROR));
            } else {
                playerService.refreshSources();
            }
        });
    }

    public void runCustomCommand(UnifiedPlayer player, String commandTemplate) {
        if (commandTemplate == null || commandTemplate.isEmpty()) return;
        String command = commandTemplate
            .replace("$name", player.getName())
            .replace("$uuid", player.getUuid().toString());

        executeOperation(player, PlayerOperation.Type.COMMAND, command, false).whenComplete((v, e) -> {
            if (e != null) {
                ScreenManager.getInstance().execute(() -> new Notification("Error", e.getMessage(), Notification.Type.ERROR));
            } else {
                playerService.refreshSources();
            }
        });
    }

    public boolean isServerRunning() {
        return capabilities.serverRunning(instance);
    }

    public boolean canExecutePlayerAction(String actionType) {
        PlayerOperation.Type type = operationType(actionType);
        return type != null && operationRouter.supports(new PlayerOperation("capability", type, null, "", false, 0L, List.of()));
    }

    private Async<PlayerOperationResult> executeOperation(UnifiedPlayer player, PlayerOperation.Type type, String text, boolean flag) {
        PlayerOperation operation = new PlayerOperation(UUID.randomUUID().toString(), type, player.getUuid(), text, flag, 0L, List.of());
        operationPlayers.put(player.getUuid(), player);
        return operationRouter.execute(operation).thenCompose(result -> result.success() ? Async.completed(result)
                : Async.failed(new IllegalStateException(result.reason()))).whenComplete((result, error) -> operationPlayers.remove(player.getUuid(), player));
    }

    private Async<PlayerOperationResult> executePlayerServiceOperation(PlayerOperation operation) {
        UnifiedPlayer target = operationPlayers.get(operation.playerId());
        if (target == null) return Async.completed(PlayerOperationResult.failed(operation.operationId(), "Player Unavailable"));
        Async<Void> execution = switch (operation.type()) {
            case KICK, COMMAND -> playerService.executeAction(target, actionName(operation.type()), operation.text());
            case BAN -> playerService.executeAction(target, actionName(operation.type()), operation.text(), operation.flag());
            case UNBAN, OP, DEOP -> playerService.executeAction(target, actionName(operation.type()));
            case INVENTORY_EDIT -> Async.failed(new IllegalStateException("Invalid Operation Route"));
        };
        return execution.thenApply(ignored -> new PlayerOperationResult(operation.operationId(), true, "", 0L, null));
    }

    private Async<PlayerOperationResult> executeReSyncOperation(PlayerOperation operation) {
        UnifiedPlayer target = operationPlayers.get(operation.playerId());
        if (target == null) return Async.completed(PlayerOperationResult.failed(operation.operationId(), "Player Unavailable"));
        return capabilities.playerOperation(instance, operation);
    }

    private PlayerOperation.Type operationType(String action) {
        if (action == null) return null;
        try {
            return PlayerOperation.Type.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String actionName(PlayerOperation.Type type) {
        return type == null ? "" : type.name().toLowerCase(Locale.ROOT);
    }

    public Async<List<PlayerSession>> getPlayerSessions(UUID uuid) {
        if (historyProvider != null) return historyProvider.getSessions(uuid);
        return capabilities.playerSessions(instance, uuid).exceptionally(error -> List.of());
    }

    private final class UnifiedPlayerSource implements IPlayerSource {
        private final ServerUiCapabilityProvider capabilities;
        private final Object instance;
        private PlayerService service;
        private boolean enabled;

        private UnifiedPlayerSource(ServerUiCapabilityProvider capabilities, Object instance) {
            this.capabilities = capabilities;
            this.instance = instance;
        }

        @Override
        public void init(PlayerService context) {
            service = context;
        }

        @Override
        public void enable() {
            enabled = true;
            refresh();
        }

        @Override
        public void disable() {
            enabled = false;
        }

        @Override
        public int getPriority() {
            return 30;
        }

        @Override
        public void refresh() {
            if (!enabled || service == null) return;
            capabilities.players(instance).thenAccept(players -> {
                apply(players);
                unifiedPlayersLoaded(this);
            }).exceptionally(error -> {
                unifiedPlayersFailed(this, error);
                return null;
            });
        }

        private void apply(List<RemotelyServerApi.Player> players) {
            if (!enabled || service == null || players == null) return;
            PlayerUpdateBatch batch = new PlayerUpdateBatch("api", getPriority());
            Set<UUID> online = new HashSet<>();
            for (RemotelyServerApi.Player player : players) {
                if (player == null || player.uuid() == null) continue;
                if (player.online()) online.add(player.uuid());
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(player.uuid(), player.name());
                update.setOnline(player.online());
                update.setOp(player.operator());
                if (player.ping() >= 0) update.setPing(player.ping());
                if (player.address() != null && !player.address().isBlank()) update.setIp(player.address());
                batch.add(update);
            }
            service.getRegistry().getAll().stream()
                    .filter(UnifiedPlayer::isOnline)
                    .filter(player -> "api".equalsIgnoreCase(player.getOnline().getSource()))
                    .filter(player -> !online.contains(player.getUuid()))
                    .map(player -> new PlayerUpdateBatch.PlayerUpdate(player.getUuid(), player.getName()))
                    .peek(update -> update.setOnline(false))
                    .forEach(batch::add);
            service.submitUpdate(batch);
        }
    }

    private static final class UnifiedPlayerActionExecutor implements IActionExecutor {
        private final ServerUiCapabilityProvider capabilities;
        private final Object instance;

        private UnifiedPlayerActionExecutor(ServerUiCapabilityProvider capabilities, Object instance) {
            this.capabilities = capabilities;
            this.instance = instance;
        }

        @Override
        public boolean canExecute(String actionType) {
            return switch (actionType) {
                case "kick", "ban", "unban", "op", "deop" -> true;
                default -> false;
            };
        }

        @Override
        public Async<Void> execute(UnifiedPlayer player, String actionType, Object... args) {
            if (player == null || player.getUuid() == null) {
                return Async.failed(new IllegalArgumentException("Player Is Unavailable"));
            }
            String fallbackReason = "ban".equals(actionType) ? "Banned By Operator" : "Kicked By Operator";
            String reason = args.length > 0 && args[0] instanceof String value && !value.isBlank() ? value : fallbackReason;
            boolean flag = args.length > 1 && args[1] instanceof Boolean value && value;
            RemotelyServerApi.PlayerAction action = new RemotelyServerApi.PlayerAction(actionType, player.getUuid(), player.getName(), reason, flag);
            return capabilities.playerAction(instance, action);
        }

        @Override
        public int getPriority() {
            return 30;
        }
    }

}
