package redxax.oxy.remotely.data.player.source;

import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.data.managed.SessionEventType;
import redxax.oxy.remotely.data.player.IPlayerHistoryCollector;
import redxax.oxy.remotely.data.player.PlayerService;
import redxax.oxy.remotely.data.player.PlayerUpdateBatch;
import redxax.oxy.remotely.data.player.model.BanInfo;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import restudio.rebase.instance.Instance;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StandardLogSource implements IPlayerSource {
    private final Instance instance;
    private final IPlayerHistoryCollector historyCollector;
    private PlayerService service;
    private boolean enabled = false;
    private final BiConsumer<Integer, String> logListener = this::processLogLine;

    private final Map<String, UUID> nameToUuid = BrowserSafeState.map();

    private static final Pattern PLAYER_JOIN_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?(\\w+)\\[/([0-9.:]+)] logged in with entity id \\d+ at .*");
    private static final Pattern PLAYER_LEAVE_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?(\\w+) left the game");
    private static final Pattern PLAYER_UUID_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?UUID of player (\\w+) is ([0-9a-f\\-]+)");
    private static final Pattern PLAYER_LOGIN_FAST_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?(\\w+) joined the game");
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final String LOG_RESET_MARKER = "[REMOTELY_LOG_RESET]";
    private static final String BASELINE_START_MARKER = "[REMOTELY_PLAYER_BASELINE_START]";
    private static final String BASELINE_END_MARKER = "[REMOTELY_PLAYER_BASELINE_END]";
    private boolean baselineMode;

    private static final Pattern PLAYER_OP_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*Made (\\w+) a server operator.*");
    private static final Pattern PLAYER_DEOP_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*Made (\\w+) no longer a server operator.*");

    private static final Pattern OP_ALREADY_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?Nothing changed\\. The player is already an operator");
    private static final Pattern OP_NOT_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?Nothing changed\\. The player is not an operator");

    private static final Pattern PLAYER_BAN_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?Banned (\\w+): (.*)");
    private static final Pattern PLAYER_UNBAN_PATTERN = Pattern.compile("(?:.*\\[INFO]: )?.*?Unbanned (\\w+)");

    public StandardLogSource(Instance instance, IPlayerHistoryCollector historyCollector) {
        this.instance = instance;
        this.historyCollector = historyCollector;
    }


    @Override
    public void init(PlayerService context) {
        this.service = context;
    }

    @Override
    public void enable() {
        if (!enabled) {
            instance.addLogListener(logListener);
            enabled = true;
        }
    }

    @Override
    public void disable() {
        if (enabled) {
            instance.removeLogListener(logListener);
            enabled = false;
        }
    }

    @Override
    public int getPriority() {
        return 25;
    }

    @Override
    public void refresh() {
    }

    private void processLogLine(int lineNum, String line) {
        if (line == null) return;
        line = (line.indexOf('\u001B') < 0 ? line : ANSI_PATTERN.matcher(line).replaceAll("")).trim();
        if (line.isEmpty()) return;
        if (BASELINE_START_MARKER.equals(line)) {
            baselineMode = true;
            resetLogOnlinePlayers();
            return;
        }
        if (BASELINE_END_MARKER.equals(line)) {
            baselineMode = false;
            return;
        }
        if (LOG_RESET_MARKER.equals(line)) {
            resetLogOnlinePlayers();
            return;
        }

        Matcher uuidMatcher = line.contains("UUID of player ") ? PLAYER_UUID_PATTERN.matcher(line) : null;
        if (uuidMatcher != null && uuidMatcher.matches()) {
            String name = uuidMatcher.group(1);
            UUID uuid = UUID.fromString(uuidMatcher.group(2));
            nameToUuid.put(name, uuid);
            PlayerUpdateBatch batch = new PlayerUpdateBatch("log", getPriority());
            batch.add(new PlayerUpdateBatch.PlayerUpdate(uuid, name));
            service.submitUpdate(batch);
            if (service != null) {
                service.ensurePlayer(uuid, name, "log", getPriority());
            }
            return;
        }

        Matcher joinMatcher = line.contains(" logged in with entity id ") ? PLAYER_JOIN_PATTERN.matcher(line) : null;
        if (joinMatcher != null && joinMatcher.matches()) {
            String name = joinMatcher.group(1);
            String ip = joinMatcher.group(2);
            UUID uuid = resolveUuid(name);
            if (uuid != null) {
                PlayerUpdateBatch batch = new PlayerUpdateBatch("log", getPriority());
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, name);
                update.setOnline(true);
                update.setIp(ip);
                long now = System.currentTimeMillis();
                batch.add(update);
                service.submitUpdate(batch);

                if (!baselineMode) historyCollector.startSession(uuid, name, ip, now);
            } else if (service != null) {
                service.markOnlineByName(name, ip, System.currentTimeMillis(), "log", getPriority());
            }
            return;
        }

        Matcher fastJoin = line.contains(" joined the game") ? PLAYER_LOGIN_FAST_PATTERN.matcher(line) : null;
        if (fastJoin != null && fastJoin.matches()) {
            String name = fastJoin.group(1);
            UUID uuid = resolveUuid(name);
            if (uuid != null) {
                PlayerUpdateBatch batch = new PlayerUpdateBatch("log", getPriority());
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, name);
                update.setOnline(true);
                long now = System.currentTimeMillis();
                batch.add(update);
                service.submitUpdate(batch);
                if (!baselineMode) historyCollector.startSession(uuid, name, null, now);
            } else if (service != null) {
                service.markOnlineByName(name, null, System.currentTimeMillis(), "log", getPriority());
            }
            return;
        }

        Matcher leaveMatcher = line.contains(" left the game") ? PLAYER_LEAVE_PATTERN.matcher(line) : null;
        if (leaveMatcher != null && leaveMatcher.matches()) {
            String name = leaveMatcher.group(1);
            UUID uuid = resolveUuid(name);
            if (uuid != null) {
                PlayerUpdateBatch batch = new PlayerUpdateBatch("log", getPriority());
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, name);
                update.setOnline(false);
                batch.add(update);
                service.submitUpdate(batch);

                if (!baselineMode) historyCollector.endSession(uuid, System.currentTimeMillis());
            } else if (service != null) {
                service.clearPendingOnline(name);
            }
            return;
        }

        Matcher opMatcher = line.contains(" server operator") ? PLAYER_OP_PATTERN.matcher(line) : null;
        if (opMatcher != null && opMatcher.matches()) {
            String name = opMatcher.group(1);
            UUID uuid = resolveUuid(name);
            if (uuid != null) {
                PlayerUpdateBatch batch = new PlayerUpdateBatch("log", getPriority());
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, name);
                update.setOp(true);
                batch.add(update);
                service.submitUpdate(batch);
                if (!baselineMode) historyCollector.recordAccessChange(uuid, name, SessionEventType.OP_CHANGE, "op=true", System.currentTimeMillis());
            }
            return;
        }

        Matcher deopMatcher = line.contains(" no longer a server operator") ? PLAYER_DEOP_PATTERN.matcher(line) : null;
        if (deopMatcher != null && deopMatcher.matches()) {
            String name = deopMatcher.group(1);
            UUID uuid = resolveUuid(name);
            if (uuid != null) {
                PlayerUpdateBatch batch = new PlayerUpdateBatch("log", getPriority());
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, name);
                update.setOp(false);
                batch.add(update);
                service.submitUpdate(batch);
                if (!baselineMode) historyCollector.recordAccessChange(uuid, name, SessionEventType.OP_CHANGE, "op=false", System.currentTimeMillis());
            }
            return;
        }

        if (line.contains("Nothing changed.") && (OP_ALREADY_PATTERN.matcher(line).find() || OP_NOT_PATTERN.matcher(line).find())) {
            if (service != null) service.refreshSources();
            return;
        }

        Matcher banMatcher = line.contains("Banned ") ? PLAYER_BAN_PATTERN.matcher(line) : null;
        if (banMatcher != null && banMatcher.matches()) {
            String name = banMatcher.group(1);
            String reason = banMatcher.group(2);
            UUID uuid = resolveUuid(name);
            if (uuid != null) {
                PlayerUpdateBatch batch = new PlayerUpdateBatch("log", 15);
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, name);
                String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date());
                update.setBan(new BanInfo(uuid.toString(), name, now, "Console", "Forever", reason));
                batch.add(update);
                service.submitUpdate(batch);
                if (!baselineMode) historyCollector.recordAccessChange(uuid, name, SessionEventType.BAN, reason, System.currentTimeMillis());
            }
            return;
        }

        Matcher unbanMatcher = line.contains("Unbanned ") ? PLAYER_UNBAN_PATTERN.matcher(line) : null;
        if (unbanMatcher != null && unbanMatcher.matches()) {
            String name = unbanMatcher.group(1);
            UUID uuid = resolveUuid(name);
            if (uuid != null) {
                PlayerUpdateBatch batch = new PlayerUpdateBatch("log", 15);
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, name);
                update.clearBan();
                batch.add(update);
                service.submitUpdate(batch);
                if (!baselineMode) historyCollector.recordAccessChange(uuid, name, SessionEventType.UNBAN, "", System.currentTimeMillis());
            }
        }
    }

    private void resetLogOnlinePlayers() {
        PlayerUpdateBatch batch = new PlayerUpdateBatch("log", getPriority());
        service.getRegistry().getAll().stream().filter(UnifiedPlayer::isOnline).filter(player -> "log".equalsIgnoreCase(player.getOnline().getSource())).forEach(player -> {
            PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(player.getUuid(), player.getName());
            update.setOnline(false);
            batch.add(update);
        });
        if (!batch.getUpdates().isEmpty()) service.submitUpdate(batch);
    }

    private UUID resolveUuid(String name) {
        if (name == null || name.isBlank()) return null;
        UUID cached = nameToUuid.get(name);
        if (cached != null) return cached;
        if (service != null) {
            UUID resolved = service.resolveUuid(name);
            if (resolved != null) {
                nameToUuid.put(name, resolved);
            }
            return resolved;
        }
        return null;
    }
}
