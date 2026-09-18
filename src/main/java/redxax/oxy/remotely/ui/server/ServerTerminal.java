package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.host.ApplicationHost;
import restudio.rebase.backend.TerminalSessionProvider;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.screens.editor.completion.CompletionItem;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rescreen.config.Config;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.IconMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerTerminal extends TerminalWidget implements ServerTerminalLifecycle {
    private static final Map<String, ServerTerminal> CACHE = new HashMap<>();
    private static final ApplicationHost EMPTY_APPLICATION = new ApplicationHost() {
        @Override
        public void setScreen(Screen screen) {
        }

        @Override
        public Screen getCurrentScreen() {
            return null;
        }

        @Override
        public void ensureTextRenderer() {
        }

        @Override
        public MinecraftGameAssets getGameAssets() {
            return MinecraftGameAssets.EMPTY;
        }

        @Override
        public Object getFontIdentifier(String namespace, String path) {
            return null;
        }

        @Override
        public void openParentScreen(Screen currentScreen, Object parent) {
        }

        @Override
        public void setClipboard(String text) {
        }

        @Override
        public boolean shouldCloseRootScreen() {
            return false;
        }

        @Override
        public String getGameVersion() {
            return "";
        }

        @Override
        public String getGameUserName() {
            return "";
        }

        @Override
        public String getGameUUID() {
            return "";
        }
    };
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern COMPLETION_CONFIRMATION_PATTERN = Pattern.compile("(?i)(?:do you wish to see all|display all)\\s+\\d+\\s+possibilit");
    private static final Pattern COMPLETION_VALUE_PATTERN = Pattern.compile("^([-#?@A-Za-z0-9_~^][#?@A-Za-z0-9_:.+*/~^=,\\-]*)(?:\\s+\\(([^)]*)\\))?$");
    private static final Pattern COMPLETION_DESCRIPTION_PATTERN = Pattern.compile("^\\(([^)]*)\\)$");
    private static final long STATUS_POLL_MS = 2_000;
    private static final long CONNECT_ATTEMPT_COOLDOWN_MS = 3_000;
    private static final long START_GRACE_MS = 90_000;
    private static final long STOP_GRACE_MS = 90_000;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 15;

    private final ServerScreenHost host;
    private final ServerTerminalPlatform platform;
    private final RemotelyServerApi api;
    private final ServerModels.ClientServerView server;
    private final IconMessage stoppedMessage;
    private final IconMessage connectingMessage;
    private final IconMessage operationMessage;
    private final IconMessage reconnectingMessage;
    private final AnimatedButton consoleFallbackButton;
    private final Map<String, RemotelyServerApi.Player> players = new HashMap<>();
    protected String cacheId;
    private boolean disposed;
    private boolean reconnecting;
    private boolean explicitDisconnect;
    private boolean forceStoppedView;
    private String reconnectReason = "";
    private int reconnectCountdown;
    private int reconnectDelaySeconds = 3;
    private long lastTick;
    private long lastStatusPoll;
    private long lastConnectAttempt;
    private long lastStartRequested;
    private long lastStopRequested;
    private boolean statusRequestInFlight;
    private boolean consoleFallbackVisible;
    private int consoleFallbackY;
    private String state = "stopped";
    private DesiredPower desiredPower = DesiredPower.UNKNOWN;
    private boolean platformOperationActive;
    private String platformOperationMessage = "Working...";
    private volatile long retainedOutputGeneration;
    private volatile boolean liveOutputObserved;

    private enum DesiredPower {
        UNKNOWN,
        RUNNING,
        STOPPED
    }

    public ServerTerminal(ServerScreenHost host, RemotelyServerApi api, ServerModels.ClientServerView server,
                           int x, int y, int width, int height, TerminalSessionProvider provider) {
        super(x, y, width, height, provider);
        this.host = host == null ? ServerScreenHost.of(EMPTY_APPLICATION) : host;
        ServerTerminalPlatform configuredPlatform = this.host.terminalPlatform(api, server);
        this.platform = configuredPlatform == null ? ServerTerminalPlatform.NONE : configuredPlatform;
        this.api = api;
        this.server = server;
        setTerminalResponsesEnabled(false);
        setSendExitOnShutdown(false);
        this.stoppedMessage = new IconMessage(0, 0, 64, 64, "Ready When You Are", "zz.png");
        this.connectingMessage = new IconMessage(0, 0, 64, 64, "Connecting...", "reverse.png");
        this.operationMessage = new IconMessage(0, 0, 64, 64, "Working...", "remotely.png");
        this.reconnectingMessage = new IconMessage(0, 0, 64, 64, "Connection Lost\nReconnecting...", "reverse.png");
        this.consoleFallbackButton = new AnimatedButton(0, 0, 150, 18, "Use WebSocket Console");
        this.consoleFallbackButton.setAction(this::useConsoleFallback);
        addOutputListener(this::onTerminalOutput);
        setOnConnectionLost(this::handleConnectionLost);
        platform.configure(this);
        platform.attach(this);
        loadRetainedOutput();
        loadPlayers();
    }

    public ServerTerminal(int x, int y, int width, int height, TerminalSessionProvider provider) {
        this(null, null, null, x, y, width, height, provider);
    }

    public static ServerTerminal getOrCreate(ServerScreenHost host, RemotelyServerApi api,
                                              ServerModels.ClientServerView server, int x, int y,
                                              int width, int height, TerminalSessionProvider provider) {
        return getOrCreateCached(serverId(server), host, api, server, x, y, width, height, provider);
    }

    public static ServerTerminal getOrCreate(String id, ServerScreenHost host, RemotelyServerApi api,
                                              ServerModels.ClientServerView server, int x, int y,
                                              int width, int height, TerminalSessionProvider provider) {
        return getOrCreateCached(id, host, api, server, x, y, width, height, provider);
    }

    private static ServerTerminal getOrCreateCached(String id, ServerScreenHost host, RemotelyServerApi api,
                                                     ServerModels.ClientServerView server, int x, int y,
                                                     int width, int height, TerminalSessionProvider provider) {
        if (id == null || id.isBlank()) return new ServerTerminal(host, api, server, x, y, width, height, provider);
        ServerTerminal stale;
        synchronized (ServerTerminal.class) {
            stale = CACHE.get(id);
            if (stale != null && !stale.disposed) return stale;
            if (stale != null) CACHE.remove(id, stale);
        }
        if (stale != null) {
            TerminalWidget.removeCached(id, stale);
            stale.shutdown();
        }
        ServerTerminal created = new ServerTerminal(host, api, server, x, y, width, height, provider);
        created.cacheId = id;
        ServerTerminal result;
        synchronized (ServerTerminal.class) {
            result = CACHE.get(id);
            if (result == null || result.disposed) {
                CACHE.put(id, created);
                result = created;
            }
        }
        if (result == created) {
            TerminalWidget.putCached(id, created);
            boolean registered;
            synchronized (ServerTerminal.class) {
                registered = CACHE.get(id) == created && !created.disposed;
            }
            if (!registered) TerminalWidget.removeCached(id, created);
        } else {
            created.shutdown();
        }
        return result;
    }

    public static void shutdown(String id) {
        ServerTerminal terminal;
        synchronized (ServerTerminal.class) {
            terminal = CACHE.remove(id);
        }
        if (terminal != null) terminal.shutdown();
    }

    public static void shutdownAll() {
        List<ServerTerminal> terminals;
        synchronized (ServerTerminal.class) {
            terminals = new ArrayList<>(CACHE.values());
            CACHE.clear();
        }
        terminals.forEach(ServerTerminal::shutdown);
    }

    @Override
    public void start() {
        super.start();
        if (!platform.replacesStatusPolling()) refreshStatus();
    }

    @Override
    public void startServerProcess() {
        if (!platform.beforeStartServerProcess(this)) return;
        super.startServerProcess();
        if (!platform.replacesStatusPolling()) refreshStatus();
    }

    @Override
    public void stopProcess() {
        retainedOutputGeneration++;
        super.stopProcess();
    }

    @Override
    public void shutdown() {
        if (disposed) return;
        disposed = true;
        retainedOutputGeneration++;
        String id = cacheId == null || cacheId.isBlank() ? serverId(server) : cacheId;
        if (!id.isBlank()) {
            synchronized (ServerTerminal.class) {
                CACHE.remove(id, this);
            }
            TerminalWidget.removeCached(id, this);
        }
        platform.detach(this);
        super.shutdown();
    }

    @Override
    protected void onLiveOutput() {
        if (liveOutputObserved) return;
        liveOutputObserved = true;
        retainedOutputGeneration++;
    }

    @Override
    public void tick() {
        super.tick();
        consoleFallbackButton.tick();
        if (disposed) return;
        long now = System.currentTimeMillis();
        platform.tick(this, now);
        if (!platform.replacesStatusPolling() && now - lastStatusPoll >= STATUS_POLL_MS) {
            lastStatusPoll = now;
            refreshStatus();
        }
        if (reconnecting && now - lastTick >= 1_000) {
            lastTick = now;
            reconnectCountdown--;
            if (reconnectCountdown <= 0) {
                reconnecting = false;
                stopProcess();
                if (shouldStartServerProcess()) startServerProcess();
                else start();
            } else {
                reconnectingMessage.setMessage("Connection Lost: " + reconnectReason + "\nReconnecting in " + reconnectCountdown + "s");
            }
        }
    }

    @Override
    protected void drawContent(IDrawContext context, int mouseX, int mouseY) {
        consoleFallbackVisible = !disposed && !isTerminalReady() && platform.canUseConsoleFallback(this);
        consoleFallbackY = getY() + getHeight() - consoleFallbackButton.getHeight() - 8;
        drawTerminalContent(context, mouseX, mouseY);
        if (consoleFallbackVisible) renderConsoleFallback(context, mouseX, mouseY);
    }

    private void drawTerminalContent(IDrawContext context, int mouseX, int mouseY) {
        if (shouldShowOperationOverlay()) {
            renderCentered(operationMessage, context, mouseX, mouseY);
            return;
        }
        if (reconnecting) {
            renderCentered(reconnectingMessage, context, mouseX, mouseY);
            return;
        }
        boolean hasContent = getHistoryLinesCount() > 0 || getCursorY() > 4;
        if (!isTerminalReady() && !explicitDisconnect && !forceStoppedView && !hasContent && shouldStartServerProcess()) {
            renderCentered(connectingMessage, context, mouseX, mouseY);
            return;
        }
        boolean crashed = "crashed".equals(state);
        boolean stopping = "stopping".equals(state);
        boolean stopped = !crashed && !stopping && ("stopped".equals(state) || "offline".equals(state)
                || desiredPower == DesiredPower.STOPPED || forceStoppedView || explicitDisconnect);
        if (stopped && (!hasContent || forceStoppedView)) {
            renderCentered(stoppedMessage, context, mouseX, mouseY);
            return;
        }
        if (consoleFallbackVisible) {
            context.pushScissorState();
            try {
                context.enableScissor(getX(), getY(), getX() + getWidth(), consoleFallbackY - 8);
                super.drawContent(context, mouseX, mouseY);
            } finally {
                context.popScissorState();
            }
        } else {
            super.drawContent(context, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (consoleFallbackVisible && platform.canUseConsoleFallback(this) && consoleFallbackButton.mouseClicked(event)) return true;
        return super.mouseClicked(event);
    }

    @Override
    public void notifyStartRequested() {
        retainedOutputGeneration++;
        desiredPower = DesiredPower.RUNNING;
        lastStartRequested = System.currentTimeMillis();
        lastStopRequested = 0;
        explicitDisconnect = false;
        forceStoppedView = false;
        state = "starting";
        clearLog();
        host.recordTerminalNotice(api, server, "Start Requested...");
        platform.startRequested(this);
    }

    @Override
    public void notifyStopRequested() {
        discardPendingInput();
        retainedOutputGeneration++;
        desiredPower = DesiredPower.STOPPED;
        lastStopRequested = System.currentTimeMillis();
        lastStartRequested = 0;
        explicitDisconnect = false;
        forceStoppedView = false;
        reconnecting = false;
        broadcastNotice("Stop Requested...");
        broadcastNotice("Waiting For Shutdown...");
        platform.stopRequested(this);
    }

    @Override
    public boolean isStaleLocalControllerStatus(Object status) {
        return platform.isStaleLocalControllerStatus(status);
    }

    @Override
    public void stopProcessAsync() {
        platform.stopProcessAsync(this);
    }

    private void onTerminalOutput(String message) {
        if (message == null || !message.contains("Server is offline.")) return;
        if (desiredPower == DesiredPower.STOPPED || explicitDisconnect) {
            forceStoppedView = true;
            reconnecting = false;
            stopProcessAsync();
            return;
        }
        handleConnectionLost("Server Is Offline");
    }

    private void refreshStatus() {
        if (api == null || server == null || disposed || statusRequestInFlight) return;
        statusRequestInFlight = true;
        try {
            host.serverStatus(api, server).whenComplete((value, failure) -> host.application().execute(() -> {
                statusRequestInFlight = false;
                if (disposed || failure != null || value == null) return;
                applyStatus(value);
            }));
        } catch (Throwable failure) {
            statusRequestInFlight = false;
        }
    }

    private void applyStatus(ServerModels.ServerStatus value) {
        state = value.currentState == null ? "offline" : value.currentState.trim().toLowerCase(Locale.ROOT);
        if (value.suspended || value.installing) {
            desiredPower = value.suspended ? DesiredPower.STOPPED : DesiredPower.RUNNING;
            forceStoppedView = value.suspended;
            explicitDisconnect = value.suspended;
            setObservedState(value.suspended ? ServerScreenHost.ServerState.STOPPED : ServerScreenHost.ServerState.INSTALLING);
            if (value.suspended || value.installing) stopProcess();
            return;
        }
        if ("running".equals(state) || "starting".equals(state)) {
            boolean withinStopGrace = desiredPower == DesiredPower.STOPPED && lastStopRequested > 0
                    && System.currentTimeMillis() - lastStopRequested < STOP_GRACE_MS;
            if (withinStopGrace) return;
            desiredPower = DesiredPower.RUNNING;
            explicitDisconnect = false;
            forceStoppedView = false;
            setObservedState("running".equals(state) ? ServerScreenHost.ServerState.RUNNING : ServerScreenHost.ServerState.STARTING);
            if ("running".equals(state)) {
                lastStartRequested = 0;
                lastStopRequested = 0;
            }
            if (!isTerminalReady() && !reconnecting && System.currentTimeMillis() - lastConnectAttempt >= CONNECT_ATTEMPT_COOLDOWN_MS) {
                lastConnectAttempt = System.currentTimeMillis();
                if ("running".equals(state) || "starting".equals(state)) {
                    if (shouldStartServerProcess()) startServerProcess();
                    else start();
                }
            }
            return;
        }
        if ("stopping".equals(state)) {
            desiredPower = DesiredPower.STOPPED;
            reconnecting = false;
            setObservedState(ServerScreenHost.ServerState.STOPPING);
            return;
        }
        if ("offline".equals(state) || "stopped".equals(state) || "crashed".equals(state)) {
            boolean withinStartGrace = desiredPower == DesiredPower.RUNNING && lastStartRequested > 0
                    && System.currentTimeMillis() - lastStartRequested < START_GRACE_MS;
            if (withinStartGrace) {
                state = "starting";
                setObservedState(ServerScreenHost.ServerState.STARTING);
                return;
            }
            desiredPower = host.terminalRestartsOnCrash(api, server) && "crashed".equals(state)
                    ? DesiredPower.RUNNING : DesiredPower.STOPPED;
            setObservedState("crashed".equals(state) ? ServerScreenHost.ServerState.CRASHED : ServerScreenHost.ServerState.STOPPED);
            if (desiredPower == DesiredPower.RUNNING) {
                explicitDisconnect = false;
                forceStoppedView = false;
            } else {
                explicitDisconnect = true;
                forceStoppedView = true;
                stopProcess();
            }
        }
    }

    private void handleConnectionLost(String reason) {
        if (disposed) return;
        if (platform.connectionLost(this, reason)) return;
        host.application().execute(() -> {
            if (desiredPower == DesiredPower.STOPPED || explicitDisconnect) {
                reconnecting = false;
                forceStoppedView = true;
                stopProcess();
                return;
            }
            scheduleReconnect(reason == null || reason.isBlank() ? "Disconnected" : reason);
        });
    }

    private void scheduleReconnect(String reason) {
        if (reconnecting || desiredPower == DesiredPower.STOPPED) return;
        reconnecting = true;
        reconnectReason = reason;
        reconnectCountdown = Math.max(1, reconnectDelaySeconds);
        reconnectDelaySeconds = Math.min(MAX_RECONNECT_DELAY_SECONDS, reconnectDelaySeconds * 2);
        lastTick = System.currentTimeMillis();
        reconnectingMessage.setMessage("Connection Lost: " + reconnectReason + "\nReconnecting in " + reconnectCountdown + "s");
    }

    private boolean operationActive() {
        return desiredPower == DesiredPower.RUNNING && ("starting".equals(state) || "installing".equals(state)) && !isTerminalReady();
    }

    private boolean shouldShowOperationOverlay() {
        if (isTerminalReady() || getHistoryLinesCount() > 0 || getCursorY() > 4) return false;
        return platformOperationActive || operationActive();
    }

    private void setObservedState(ServerScreenHost.ServerState observedState) {
        if (observedState != null && observedState != ServerScreenHost.ServerState.UNKNOWN) {
            host.setState(server, observedState);
        }
    }

    void setPlatformOperation(boolean active, String message) {
        platformOperationActive = active;
        platformOperationMessage = message == null || message.isBlank() ? "Working..." : message;
        operationMessage.setMessage(platformOperationMessage);
    }

    void acceptPlatformState(String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        state = normalized;
        if ("running".equals(normalized) || "starting".equals(normalized)) {
            desiredPower = DesiredPower.RUNNING;
            explicitDisconnect = false;
            forceStoppedView = false;
            if ("running".equals(normalized)) lastStartRequested = 0;
            return;
        }
        if ("stopping".equals(normalized)) {
            desiredPower = DesiredPower.STOPPED;
            reconnecting = false;
            return;
        }
        if ("offline".equals(normalized) || "stopped".equals(normalized) || "crashed".equals(normalized)) {
            desiredPower = "crashed".equals(normalized) && host.terminalRestartsOnCrash(api, server)
                    ? DesiredPower.RUNNING : DesiredPower.STOPPED;
            explicitDisconnect = desiredPower == DesiredPower.STOPPED;
            forceStoppedView = explicitDisconnect;
            reconnecting = false;
        }
    }

    boolean platformDesiredRunning() {
        return desiredPower == DesiredPower.RUNNING;
    }

    void platformStopProcess() {
        stopProcess();
    }

    void platformStopAndShowStopped() {
        reconnecting = false;
        forceStoppedView = true;
        explicitDisconnect = true;
        stopProcess();
    }

    void platformSetDesiredRunning(boolean running) {
        desiredPower = running ? DesiredPower.RUNNING : DesiredPower.STOPPED;
        explicitDisconnect = !running;
        forceStoppedView = !running;
    }

    private boolean isRunningState() {
        return "running".equals(state) || "starting".equals(state);
    }

    private boolean shouldStartServerProcess() {
        return host.terminalStartsServerProcess(api, server);
    }

    private void loadRetainedOutput() {
        if (host == null || server == null || liveOutputObserved) return;
        long generation = retainedOutputGeneration;
        host.retainedTerminalOutput(api, server).whenComplete((output, failure) -> {
            if (failure != null || output == null || output.isBlank() || disposed || liveOutputObserved
                    || generation != retainedOutputGeneration) return;
            host.application().execute(() -> tryAppendRetainedOutput(generation, output));
        });
    }

    private synchronized void tryAppendRetainedOutput(long generation, String output) {
        if (disposed || liveOutputObserved || generation != retainedOutputGeneration || getHistoryLinesCount() != 0) return;
        appendOutput(output);
    }

    private void loadPlayers() {
        if (host == null || server == null) return;
        host.capabilities(server).players(server).whenComplete((values, failure) -> host.application().execute(() -> {
            if (failure != null || values == null) return;
            players.clear();
            for (RemotelyServerApi.Player player : values) {
                if (player != null && player.name() != null && !player.name().isBlank()) players.put(player.name().toLowerCase(Locale.ROOT), player);
            }
        }));
        setPtyCompletionProvider(this::buildCompletions);
    }

    private List<CompletionItem> buildCompletions(PtyCompletionRequest request) {
        String raw = request.rawOutput();
        if (request.input().isBlank() || raw.isBlank() || raw.indexOf('\n') < 0 && raw.indexOf('\r') < 0
                || COMPLETION_CONFIRMATION_PATTERN.matcher(raw).find()) return List.of();
        String plain = ANSI_PATTERN.matcher(raw).replaceAll(" ");
        String prefix = request.token().toLowerCase(Locale.ROOT);
        Map<String, PtyCandidate> candidates = parseCandidates(plain, prefix, request.input());
        if (candidates.size() < 2) return List.of();
        List<CompletionItem> items = new ArrayList<>(candidates.size());
        for (PtyCandidate candidate : candidates.values()) {
            RemotelyServerApi.Player player = players.get(candidate.value().toLowerCase(Locale.ROOT));
            String detail = candidate.description().isBlank() ? player == null ? "" : "Player" : candidate.description();
            items.add(new CompletionItem(candidate.value(), candidate.value(), detail,
                    player == null ? CompletionItem.Kind.OTHER : CompletionItem.Kind.PLAYER, player == null ? 0 : 100));
        }
        return items;
    }

    private Map<String, PtyCandidate> parseCandidates(String output, String prefix, String input) {
        Map<String, PtyCandidate> candidates = new LinkedHashMap<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.equals(input)) continue;
            String[] columns = line.split("\\s{2,}");
            if (columns.length == 1 && !line.contains("(")) columns = line.split("\\s+");
            for (int i = 0; i < columns.length && candidates.size() < 200; i++) {
                Matcher valueMatcher = COMPLETION_VALUE_PATTERN.matcher(columns[i].trim());
                if (!valueMatcher.matches()) continue;
                String value = valueMatcher.group(1);
                String normalized = value.toLowerCase(Locale.ROOT);
                if (value.length() > 128 || normalized.equals(prefix) || !prefix.isEmpty() && !normalized.startsWith(prefix)) continue;
                String description = valueMatcher.group(2) == null ? "" : valueMatcher.group(2).trim();
                if (description.isEmpty() && i + 1 < columns.length) {
                    Matcher descriptionMatcher = COMPLETION_DESCRIPTION_PATTERN.matcher(columns[i + 1].trim());
                    if (descriptionMatcher.matches()) description = descriptionMatcher.group(1).trim();
                }
                candidates.putIfAbsent(normalized, new PtyCandidate(value, description));
            }
        }
        return candidates;
    }

    private void renderCentered(IconMessage message, IDrawContext context, int mouseX, int mouseY) {
        int contentHeight = message.getContentHeight();
        int groupHeight = contentHeight + (consoleFallbackVisible ? consoleFallbackButton.getHeight() + 12 : 0);
        int top = getY() + Math.max(0, (getHeight() - groupHeight) / 2);
        message.setPosition(getX() + (getWidth() - message.getWidth()) / 2, top);
        if (consoleFallbackVisible) consoleFallbackY = top + contentHeight + 12;
        message.render(context, mouseX, mouseY, Config.deltaTime);
    }

    private void renderConsoleFallback(IDrawContext context, int mouseX, int mouseY) {
        consoleFallbackButton.setPosition(getX() + (getWidth() - consoleFallbackButton.getWidth()) / 2, consoleFallbackY);
        consoleFallbackButton.render(context, mouseX, mouseY, Config.deltaTime);
    }

    private void useConsoleFallback() {
        if (!platform.useConsoleFallback(this)) return;
        reconnecting = false;
        reconnectReason = "";
        reconnectDelaySeconds = 3;
        stopProcess();
        start();
    }

    private void broadcastNotice(String line) {
        appendOutput(line + System.lineSeparator());
        host.recordTerminalNotice(api, server, line);
    }

    protected static String serverId(ServerModels.ClientServerView server) {
        if (server == null) return "";
        if (server.identifier != null && !server.identifier.isBlank()) return server.identifier;
        return server.uuid == null ? "" : server.uuid;
    }

    private record PtyCandidate(String value, String description) {
    }
}
