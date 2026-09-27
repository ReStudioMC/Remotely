package redxax.oxy.remotely.data.player.standard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import redxax.oxy.remotely.data.managed.PlayerSession;
import redxax.oxy.remotely.data.managed.SessionEvent;
import redxax.oxy.remotely.data.managed.SessionEventType;
import redxax.oxy.remotely.data.player.IPlayerHistoryCollector;
import redxax.oxy.remotely.data.player.IPlayerHistoryProvider;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.instance.Instance;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import restudio.rescreen.platform.Async;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StandardPlayerHistoryProvider implements IPlayerHistoryProvider, IPlayerHistoryCollector {
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final RebaseAPI api;
    private final Instance instance;
    private final TerminalWidget terminalWidget;
    private final Path historyDir;
    private final Path metaFile;
    private final Map<UUID, List<PlayerSession>> sessionsCache = new HashMap<>();
    private final Map<UUID, PlayerSession> activeSessions = new HashMap<>();
    private final List<PatternHandler> patternHandlers = new ArrayList<>();
    private final BiConsumer<Integer, String> logListener = this::onLogLine;
    private final TerminalWidget.OutputListener terminalListener = line -> onLogLine(-1, line);
    private final Function<String, UUID> nameResolver;
    private final Map<UUID, Map<String, Long>> lastCommandSeen = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastAccessSeen = new HashMap<>();
    private final String instanceId;
    private int maxProcessedLine = -1;
    private boolean baselineMode;

    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final String BASELINE_START_MARKER = "[REMOTELY_PLAYER_BASELINE_START]";
    private static final String BASELINE_END_MARKER = "[REMOTELY_PLAYER_BASELINE_END]";

    private static final Pattern COMMAND_ISSUED_PATTERN_1 = Pattern.compile("(?:.*\\[INFO]: )?.*?(\\w+) issued server command: (.+)");
    private static final Pattern COMMAND_ISSUED_PATTERN_2 = Pattern.compile("(?:.*\\[INFO]: )?.*?(\\w+) executed command: (.+)");

    private record PatternHandler(Pattern pattern, PatternConsumer consumer, String marker) { }

    public interface PatternConsumer {
        void accept(Matcher matcher, String line, long timestamp, int lineNum);
    }

    private static class HistoryMeta {
        int lastProcessedLine = -1;
    }

    public StandardPlayerHistoryProvider(Instance instance, RebaseAPI api, TerminalWidget terminalWidget, Path instancePath, Function<String, UUID> nameResolver) {
        this.instance = instance;
        this.api = api;
        this.terminalWidget = terminalWidget;
        this.historyDir = instancePath.resolve("Remotely").resolve("player-history");
        this.metaFile = instancePath.resolve("Remotely").resolve("history-meta.json");
        this.nameResolver = nameResolver;
        this.instanceId = instance.getInstanceId();
    }

    public StandardPlayerHistoryProvider(RebaseAPI api, TerminalWidget terminalWidget, Path instancePath, Function<String, UUID> nameResolver) {
        this.instance = null;
        this.api = api;
        this.terminalWidget = terminalWidget;
        this.historyDir = instancePath.resolve("Remotely").resolve("player-history");
        this.metaFile = instancePath.resolve("Remotely").resolve("history-meta.json");
        this.nameResolver = nameResolver;
        this.instanceId = "unknown";
    }

    @Override
    public void initialize() {
        ensureDir();
        loadMeta();
        registerDefaultPatterns();
        if (instance != null) {
            instance.addLogListener(logListener);
        } else if (terminalWidget != null) {
            terminalWidget.addOutputListener(terminalListener);
        }
    }

    @Override
    public void shutdown() {
        if (instance != null) {
            instance.removeLogListener(logListener);
        } else if (terminalWidget != null) {
            terminalWidget.removeOutputListener(terminalListener);
        }

        saveMeta();
    }

    public void onLogLine(int lineNum, String line) {
        if (line == null || line.isEmpty()) return;
        String trimmed = line.trim();
        if (BASELINE_START_MARKER.equals(trimmed)) {
            baselineMode = true;
            return;
        }
        if (BASELINE_END_MARKER.equals(trimmed)) {
            baselineMode = false;
            return;
        }
        if (baselineMode) return;

        if (lineNum != -1) {
            if (lineNum <= maxProcessedLine) return;
            maxProcessedLine = lineNum;
        }

        if (line.indexOf('\u001B') >= 0) line = ANSI_PATTERN.matcher(line).replaceAll("");
        long now = System.currentTimeMillis();
        for (PatternHandler ph : patternHandlers) {
            if (ph.marker() != null && !line.contains(ph.marker())) continue;
            Matcher m = ph.pattern().matcher(line);
            if (m.matches()) ph.consumer().accept(m, line, now, lineNum);
        }
    }

    public void registerPattern(Pattern pattern, PatternConsumer consumer) {
        registerPattern(pattern, consumer, null);
    }

    private void registerPattern(Pattern pattern, PatternConsumer consumer, String marker) {
        patternHandlers.add(new PatternHandler(pattern, consumer, marker));
    }

    @Override
    public void startSession(UUID uuid, String name, String ip, long startTime) {
        PlayerSession session = new PlayerSession(uuid, name, ip, startTime);
        activeSessions.put(uuid, session);
        List<PlayerSession> list = sessionsCache.computeIfAbsent(uuid, u -> new ArrayList<>());
        list.add(session);
        save(uuid);
        ReLog.logger(LogTypes.MINECRAFT).source(LogSource.instance(instanceId, instanceId)).component(StandardPlayerHistoryProvider.class).operation("Player Session").with("player", name).info("Player session started");
    }

    @Override
    public void endSession(UUID uuid, long endTime) {
        PlayerSession s = activeSessions.get(uuid);
        if (s != null) {
            s.endTime = endTime;
            save(uuid);
            ReLog.logger(LogTypes.MINECRAFT).source(LogSource.instance(instanceId, instanceId)).component(StandardPlayerHistoryProvider.class).operation("Player Session").with("player", s.name).info("Player session ended");
        }
    }

    @Override
    public void recordCommand(UUID uuid, String name, String command, long timestamp) {
        recordCommand(uuid, name, command, timestamp, -1);
    }

    public void recordCommand(UUID uuid, String name, String command, long timestamp, int lineNum) {
        if (lineNum != -1 && lineNum <= maxProcessedLine && maxProcessedLine > 0) return;

        Map<String, Long> seen = lastCommandSeen.computeIfAbsent(uuid, u -> new HashMap<>());
        Long lastTs = seen.get(command);
        if (lastTs != null && (timestamp - lastTs) < 500) return;
        seen.put(command, timestamp);
        PlayerSession s = ensureActiveOrEphemeral(uuid, name, timestamp);
        s.events.add(new SessionEvent(timestamp, SessionEventType.COMMAND, command));
        save(uuid);
        ReLog.logger(LogTypes.SECURITY).source(LogSource.instance(instanceId, instanceId)).component(StandardPlayerHistoryProvider.class).operation("Player Command").with("player", name).with("command", command).info("Player command recorded");
    }

    @Override
    public void recordAccessChange(UUID uuid, String name, SessionEventType type, String details, long timestamp) {
        recordAccessChange(uuid, name, type, details, timestamp, -1);
    }

    public void recordAccessChange(UUID uuid, String name, SessionEventType type, String details, long timestamp, int lineNum) {
        if (lineNum != -1 && lineNum <= maxProcessedLine && maxProcessedLine > 0) return;

        if(uuid == null) return;
        String key = (type == SessionEventType.BAN || type == SessionEventType.UNBAN || type == SessionEventType.KICK) ? type.name() : type.name() + "|" + String.valueOf(details);
        Map<String, Long> seen = lastAccessSeen.computeIfAbsent(uuid, u -> new HashMap<>());
        Long lastTs = seen.get(key);
        if (lastTs != null && (timestamp - lastTs) < 1000) return;
        seen.put(key, timestamp);
        PlayerSession s = ensureActiveOrEphemeral(uuid, name, timestamp);
        s.events.add(new SessionEvent(timestamp, type, details));
        save(uuid);
        ReLog.logger(LogTypes.SECURITY).source(LogSource.instance(instanceId, instanceId)).component(StandardPlayerHistoryProvider.class).operation("Player Access Change").with("player", name).with("change", type).with("details", details).info("Player access changed");
    }

    public void recordCommandByName(String name, String command, long timestamp, int lineNum) {
        UUID uuid = resolve(name);
        if (uuid == null) return;
        recordCommand(uuid, name, command, timestamp, lineNum);
    }

    @Override
    public Async<List<PlayerSession>> getSessions(UUID uuid) {
        List<PlayerSession> cached = sessionsCache.get(uuid);
        if (cached != null) return Async.completed(cached);
        return load(uuid).thenApply(list -> {
            sessionsCache.put(uuid, list);
            return list;
        });
    }

    private PlayerSession ensureActiveOrEphemeral(UUID uuid, String name, long timestamp) {
        PlayerSession s = activeSessions.get(uuid);
        if (s == null) {
            s = new PlayerSession(uuid, name, null, timestamp);
            s.endTime = timestamp;
            List<PlayerSession> list = sessionsCache.computeIfAbsent(uuid, u -> new ArrayList<>());
            list.add(s);
        }
        return s;
    }

    private UUID resolve(String name) {
        if (nameResolver == null) return null;
        return nameResolver.apply(name);
    }

    private void registerDefaultPatterns() {
        registerPattern(COMMAND_ISSUED_PATTERN_1, (m, line, ts, ln) -> recordCommandByName(m.group(1), m.group(2), ts, ln), " issued server command: ");
        registerPattern(COMMAND_ISSUED_PATTERN_2, (m, line, ts, ln) -> recordCommandByName(m.group(1), m.group(2), ts, ln), " executed command: ");
    }

    private void ensureDir() {
        api.fileExists(historyDir).thenAccept(exists -> { if (!exists) api.createDirectory(historyDir); });
    }

    private void loadMeta() {
        api.readFile(metaFile).thenAccept(content -> {
            if (content != null && !content.isEmpty()) {
                try {
                    HistoryMeta meta = gson.fromJson(content, HistoryMeta.class);
                    if (meta != null) this.maxProcessedLine = meta.lastProcessedLine;
                } catch (Exception ignored) {}
            }
        });
    }

    private void saveMeta() {
        if (maxProcessedLine > 0) {
            HistoryMeta meta = new HistoryMeta();
            meta.lastProcessedLine = maxProcessedLine;
            api.writeFile(metaFile, gson.toJson(meta));
        }
    }

    private Path fileFor(UUID uuid) {
        return historyDir.resolve(uuid.toString() + ".json");
    }

    private Async<List<PlayerSession>> load(UUID uuid) {
        Path f = fileFor(uuid);
        return JvmAsyncBridge.fromFuture(api.fileExists(f)).thenCompose(exists -> {
            if (!exists) return Async.completed(new ArrayList<>());
            return JvmAsyncBridge.fromFuture(api.readFile(f)).thenApply(content -> {
                if (content == null || content.isEmpty()) return new ArrayList<>();
                Type type = new TypeToken<List<PlayerSession>>(){}.getType();
                List<PlayerSession> result = gson.fromJson(content, type);
                return result != null ? result : new ArrayList<>();
            });
        });
    }

    private void save(UUID uuid) {
        List<PlayerSession> list = sessionsCache.getOrDefault(uuid, new ArrayList<>());
        String json = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(list);
        api.writeFile(fileFor(uuid), json);
        saveMeta();
    }
}
