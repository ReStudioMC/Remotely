package redxax.oxy.remotely.data.player.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import redxax.oxy.remotely.data.managed.BanEntry;
import redxax.oxy.remotely.data.managed.IpBanEntry;
import redxax.oxy.remotely.data.managed.OpEntry;
import redxax.oxy.remotely.data.managed.UserCacheEntry;
import redxax.oxy.remotely.data.managed.WhitelistEntry;
import redxax.oxy.remotely.data.player.PlayerService;
import redxax.oxy.remotely.data.player.PlayerUpdateBatch;
import redxax.oxy.remotely.data.player.model.BanInfo;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.util.AsyncTools;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.instance.Instance;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;

import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class StandardFileSource implements IPlayerSource {
    private static final Duration RETRY_DELAY = Duration.ofMillis(250L);

    private final RebaseAPI api;
    private final Gson gson = new GsonBuilder().setLenient().create();
    private final WatchedFile<OpEntry> opsFile;
    private final WatchedFile<BanEntry> bannedPlayersFile;
    private final WatchedFile<IpBanEntry> bannedIpsFile;
    private final WatchedFile<WhitelistEntry> whitelistFile;
    private final WatchedFile<UserCacheEntry> usercacheFile;
    private final List<WatchedFile<?>> watchedFiles;
    private PlayerService service;
    private volatile boolean enabled;

    public StandardFileSource(Instance instance, RebaseAPI api) {
        this.api = api;
        Path instancePath = Path.of(instance.getPath());
        opsFile = new WatchedFile<>("ops.json", instancePath.resolve("ops.json"), new TypeToken<List<OpEntry>>() { }.getType(), this::processOps);
        bannedPlayersFile = new WatchedFile<>("banned-players.json", instancePath.resolve("banned-players.json"),
                new TypeToken<List<BanEntry>>() { }.getType(), this::processBans);
        bannedIpsFile = new WatchedFile<>("banned-ips.json", instancePath.resolve("banned-ips.json"),
                new TypeToken<List<IpBanEntry>>() { }.getType(), this::processIpBans);
        whitelistFile = new WatchedFile<>("whitelist.json", instancePath.resolve("whitelist.json"),
                new TypeToken<List<WhitelistEntry>>() { }.getType(), this::processWhitelist);
        usercacheFile = new WatchedFile<>("usercache.json", instancePath.resolve("usercache.json"),
                new TypeToken<List<UserCacheEntry>>() { }.getType(), this::processUsercache);
        watchedFiles = List.of(opsFile, bannedPlayersFile, bannedIpsFile, whitelistFile, usercacheFile);
    }

    @Override
    public void init(PlayerService context) {
        this.service = context;
    }

    @Override
    public void enable() {
        if (!enabled) {
            enabled = true;
            refreshAll();
        }
    }

    @Override
    public void disable() {
        enabled = false;
        watchedFiles.forEach(WatchedFile::stop);
    }

    @Override
    public void refresh() {
        refreshAll();
    }

    @Override
    public int getPriority() {
        return 20;
    }

    public void refreshAll() {
        if (!enabled) return;
        watchedFiles.forEach(WatchedFile::refresh);
    }

    public void updateFromContent(String fileName, String content) {
        if (!enabled || fileName == null || content == null) return;
        watchedFiles.stream().filter(file -> fileName.endsWith(file.name)).findFirst().ifPresent(file -> file.observe(content));
    }

    private void processOps(List<OpEntry> ops) {
        if (ops == null || service == null) return;
        PlayerUpdateBatch batch = new PlayerUpdateBatch("files", getPriority());
        for (OpEntry op : ops) {
            UUID uuid = safeUuid(op.uuid);
            if (uuid == null) continue;
            PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, op.name);
            update.setOp(true);
            batch.add(update);
            service.ensurePlayer(uuid, op.name, "files", getPriority());
        }

        Set<String> opUuids = ops.stream()
            .map(o -> o.uuid)
            .filter(u -> u != null && !u.isBlank())
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        service.getRegistry().getAll().stream()
            .filter(UnifiedPlayer::isOp)
            .filter(p -> !opUuids.contains(p.getUuid().toString().toLowerCase()))
            .forEach(p -> {
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(p.getUuid(), p.getName());
                update.setOp(false);
                batch.add(update);
            });

        service.submitUpdate(batch);
    }

    private void processBans(List<BanEntry> bans) {
        if (bans == null || service == null) return;
        PlayerUpdateBatch batch = new PlayerUpdateBatch("files", getPriority());
        for (BanEntry ban : bans) {
            UUID uuid = safeUuid(ban.uuid);
            if (uuid == null) continue;
            PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, ban.name);
            BanInfo banInfo = new BanInfo(ban.uuid, ban.name, ban.created, ban.source, ban.expires, ban.reason);
            update.setBan(banInfo);
            batch.add(update);
            service.ensurePlayer(uuid, ban.name, "files", getPriority());
        }

        Set<String> bannedUuids = bans.stream()
            .map(b -> b.uuid)
            .filter(u -> u != null && !u.isBlank())
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        service.getRegistry().getAll().stream()
            .filter(p -> p.getBan().getValue() != null)
            .filter(p -> !bannedUuids.contains(p.getUuid().toString().toLowerCase()))
            .forEach(p -> {
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(p.getUuid(), p.getName());
                update.clearBan();
                batch.add(update);
            });

        service.submitUpdate(batch);
    }

    private void processIpBans(List<IpBanEntry> ipBans) {
        if (ipBans == null || service == null) return;
        PlayerUpdateBatch batch = new PlayerUpdateBatch("files", getPriority());

        Set<String> bannedIps = ipBans.stream()
            .map(b -> b.ip)
            .filter(ip -> ip != null && !ip.isBlank())
            .collect(Collectors.toSet());

        service.getRegistry().getAll().stream()
            .filter(p -> p.getIp().getValue() != null)
            .forEach(p -> {
                String ip = p.getIp().getValue();
                if (ip == null || ip.isBlank()) return;
                boolean isBanned = bannedIps.contains(ip);
                if (isBanned) {
                    PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(p.getUuid(), p.getName());
                    BanInfo banInfo = new BanInfo(p.getUuid().toString(), p.getName(), "", "IP Ban", "", "IP banned");
                    update.setBan(banInfo);
                    batch.add(update);
                }
            });

        if (!batch.getUpdates().isEmpty()) {
            service.submitUpdate(batch);
        }
    }

    private void processWhitelist(List<WhitelistEntry> whitelist) {
        if (whitelist == null || service == null) return;
        for (WhitelistEntry entry : whitelist) {
            UUID uuid = safeUuid(entry.uuid);
            if (uuid != null) {
                service.ensurePlayer(uuid, entry.name, "files", getPriority());
            }
        }
    }

    private void processUsercache(List<UserCacheEntry> cache) {
        if (cache == null || service == null) return;
        PlayerUpdateBatch batch = new PlayerUpdateBatch("files", getPriority());
        Set<UUID> seen = new HashSet<>();
        for (UserCacheEntry entry : cache) {
            UUID uuid = safeUuid(entry.uuid);
            if (uuid == null) continue;
            if (!seen.add(uuid)) continue;
            PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(uuid, entry.name);
            batch.add(update);
        }
        if (!batch.getUpdates().isEmpty()) {
            service.submitUpdate(batch);
        }
    }

    private UUID safeUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        try {
            return UUID.fromString(uuid);
        } catch (Exception ignored) {
            return null;
        }
    }

    private final class WatchedFile<T> {
        private final String name;
        private final Path path;
        private final Type type;
        private final Consumer<List<T>> consumer;
        private final PlayerFileSnapshotState state = new PlayerFileSnapshotState();
        private TaskScheduler.ScheduledTask pendingRetry;

        private WatchedFile(String name, Path path, Type type, Consumer<List<T>> consumer) {
            this.name = name;
            this.path = path;
            this.type = type;
            this.consumer = consumer;
        }

        private void refresh() {
            long generation = state.observe();
            cancelRetry();
            read(generation, 0);
        }

        private void observe(String content) {
            long generation = state.observe();
            cancelRetry();
            parse(content, generation, 0);
        }

        private void read(long generation, int attempt) {
            if (!enabled || !state.current(generation)) return;
            readContent().whenComplete((content, failure) -> {
                if (!enabled || !state.current(generation)) return;
                if (failure != null) {
                    reject(generation, attempt, failure);
                } else if (content != null) {
                    parse(content, generation, attempt);
                }
            });
        }

        private Async<String> readContent() {
            return JvmAsyncBridge.fromFuture(api.fileExists(path)).thenCompose(exists -> {
                if (!exists) return Async.completed(null);
                return JvmAsyncBridge.fromFuture(api.readFile(path));
            });
        }

        private void parse(String content, long generation, int attempt) {
            if (!enabled || !state.current(generation)) return;
            List<T> values;
            try {
                JsonReader reader = new JsonReader(new StringReader(content));
                reader.setLenient(true);
                values = gson.fromJson(reader, type);
                if (values == null || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new JsonSyntaxException("Expected one complete player data array");
                }
            } catch (Exception failure) {
                reject(generation, attempt, failure);
                return;
            }
            PlayerFileSnapshotState.Accepted accepted = state.accept(generation, content);
            if (accepted.current() && accepted.changed()) {
                consumer.accept(values);
            }
        }

        private void reject(long generation, int attempt, Throwable failure) {
            PlayerFileSnapshotState.Rejected rejected = state.reject(generation, attempt, System.currentTimeMillis());
            if (!rejected.current()) return;
            if (rejected.report()) {
                report(failure);
            }
            if (rejected.retry()) {
                scheduleRetry(generation, attempt + 1, failure);
            }
        }

        private void scheduleRetry(long generation, int attempt, Throwable parseFailure) {
            cancelRetry();
            if (!enabled || !state.current(generation)) return;
            try {
                Duration delay = RETRY_DELAY.multipliedBy(attempt);
                TaskScheduler.ScheduledTask scheduled = AsyncTools.schedule(TaskSchedulers.current(), delay, () -> {
                    synchronized (this) {
                        pendingRetry = null;
                    }
                    read(generation, attempt);
                });
                synchronized (this) {
                    if (enabled && state.current(generation)) {
                        pendingRetry = scheduled;
                    } else {
                        scheduled.cancel();
                    }
                }
            } catch (Throwable schedulingFailure) {
                if (state.reportNow(generation, System.currentTimeMillis())) {
                    report(parseFailure);
                }
            }
        }

        private void report(Throwable failure) {
            ReLog.logger(LogTypes.FILESYSTEM).source(LogSource.resource(path.toString(), name)).component(StandardFileSource.class)
                    .operation("Read Player Data").error("Could not parse player data after retries", failure);
        }

        private synchronized void cancelRetry() {
            if (pendingRetry == null) return;
            pendingRetry.cancel();
            pendingRetry = null;
        }

        private void stop() {
            state.observe();
            cancelRetry();
        }
    }
}
