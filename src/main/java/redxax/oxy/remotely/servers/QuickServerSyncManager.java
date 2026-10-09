package redxax.oxy.remotely.servers;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rebase.instance.Instance;
import restudio.rebase.localcontrol.LocalServerControllerClient;
import restudio.rebase.localcontrol.LocalServerControllerModels;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public final class QuickServerSyncManager {
    private static final String QUICK_SERVER_ENABLED_KEY = "quickServer.enabled";
    private static final String SOURCE_WORLD_PATH_KEY = "quickServer.sourceWorldPath";
    private static final String LAST_WORLD_SYNC_KEY = "quickServer.lastWorldSync";
    private static final String SYNC_STATE_KEY = "quickServer.syncState";
    private static final String SYNC_STATE_CLEAN = "clean";
    private static final String SYNC_STATE_SYNCING_BACK = "syncingBack";
    private static final Set<String> WORLD_SYNC_EXCLUDES = Set.of("session.lock", "remotely-quick-server.properties");
    private static final Set<String> activeSyncs = BrowserSafeState.set();
    private static final long SMALL_HASH_LIMIT = 16L * 1024L * 1024L;
    private static final long DISK_SPACE_MARGIN = 128L * 1024L * 1024L;

    private QuickServerSyncManager() {
    }

    public static boolean isQuickServer(Instance instance) {
        return instance != null && "true".equalsIgnoreCase(instance.getSettings().getProperty(QUICK_SERVER_ENABLED_KEY));
    }

    public static void syncBackAfterStop(Instance instance) {
        if (!isQuickServer(instance) || instance.getInstanceId() == null || instance.getInstanceId().isBlank()) {
            return;
        }
        String key = instance.getInstanceId();
        if (!activeSyncs.add(key)) {
            return;
        }
        Thread.ofVirtual().name("Remotely Quick Server Sync Back").start(() -> {
            try {
                if (!waitUntilStopped(instance)) {
                    throw new IOException("Server Did Not Stop");
                }
                syncBack(instance);
            } catch (Exception e) {
                ScreenManager.getInstance().execute(() -> new Notification("Quick Server Sync Failed", cleanMessage(e), Notification.Type.ERROR));
            } finally {
                activeSyncs.remove(key);
            }
        });
    }

    public static void stopAndSyncBack(Instance instance) throws IOException {
        if (!isQuickServer(instance)) {
            return;
        }
        ReProxyManager.stopQuietly(JvmReProxyConnectorCapability.server(instance), null);
        try {
            LocalServerControllerClient.stop(instance);
        } catch (IOException e) {
            LocalServerControllerModels.StatusResponse status = e instanceof LocalServerControllerClient.ControllerRequestException controllerException ? controllerException.getStatus() : null;
            if (status == null || (!"STOPPED".equalsIgnoreCase(status.state) && !"CRASHED".equalsIgnoreCase(status.state))) {
                throw e;
            }
        }
        if (!waitUntilStopped(instance)) {
            throw new IOException("Server Did Not Stop");
        }
        syncBack(instance);
    }

    private static boolean waitUntilStopped(Instance instance) {
        long deadline = System.currentTimeMillis() + 45_000L;
        while (System.currentTimeMillis() < deadline) {
            LocalServerControllerModels.StatusResponse status = LocalServerControllerClient.status(instance);
            if (status == null || !status.knownSession || status.pid <= 0 || isStoppedState(status.state)) {
                return true;
            }
            sleep(500);
        }
        return false;
    }

    private static boolean isStoppedState(String state) {
        String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("STOPPED") || normalized.equals("CRASHED");
    }

    private static void syncBack(Instance instance) throws IOException {
        String sourceWorldPath = instance.getSettings().getProperty(SOURCE_WORLD_PATH_KEY, "");
        if (sourceWorldPath.isBlank()) {
            return;
        }
        Path source = Path.of(instance.getPath()).resolve("world");
        Path target = Path.of(sourceWorldPath);
        if (!Files.isDirectory(source) || !Files.isDirectory(target)) {
            return;
        }
        instance.getSettings().setProperty(SYNC_STATE_KEY, SYNC_STATE_SYNCING_BACK);
        instance.save().join();
        syncWorld(source, target);
        instance.getSettings().setProperty(SYNC_STATE_KEY, SYNC_STATE_CLEAN);
        instance.getSettings().setProperty(LAST_WORLD_SYNC_KEY, Instant.now().toString());
        instance.save().join();
    }

    public static void syncWorld(Path source, Path target) throws IOException {
        syncDirectoryIncremental(source, target, WORLD_SYNC_EXCLUDES);
    }

    private static void syncDirectoryIncremental(Path source, Path target, Set<String> excludes) throws IOException {
        Files.createDirectories(target);
        ensureDiskSpace(source, target, excludes);
        try (var stream = Files.walk(target)) {
            for (Path existing : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                if (existing.equals(target)) {
                    continue;
                }
                Path relative = target.relativize(existing);
                if (isExcluded(relative, excludes)) {
                    continue;
                }
                Path sourcePath = source.resolve(relative);
                if (!Files.exists(sourcePath) || typeMismatch(sourcePath, existing)) {
                    deleteIfEmptyOrFile(existing);
                }
            }
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && isExcluded(relative, excludes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (!isExcluded(relative, excludes) && shouldCopy(file, target.resolve(relative))) {
                    Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void ensureDiskSpace(Path source, Path target, Set<String> excludes) throws IOException {
        long required = requiredCopyBytes(source, target, excludes);
        if (required <= 0) {
            return;
        }
        FileStore store = Files.getFileStore(target);
        long usable = store.getUsableSpace();
        if (usable < required + DISK_SPACE_MARGIN) {
            throw new IOException("Not Enough Disk Space. Need " + formatBytes(required + DISK_SPACE_MARGIN) + ", Available " + formatBytes(usable));
        }
    }

    private static long requiredCopyBytes(Path source, Path target, Set<String> excludes) throws IOException {
        final long[] total = {0L};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && isExcluded(relative, excludes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (!isExcluded(relative, excludes) && shouldCopy(file, target.resolve(relative))) {
                    total[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private static boolean shouldCopy(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return true;
        }
        long sourceSize = Files.size(source);
        long targetSize = Files.size(target);
        if (sourceSize != targetSize) {
            return true;
        }
        long sourceTime = Files.getLastModifiedTime(source).toMillis();
        long targetTime = Files.getLastModifiedTime(target).toMillis();
        if (Math.abs(sourceTime - targetTime) > 1000L) {
            return true;
        }
        return sourceSize <= SMALL_HASH_LIMIT && !hash(source).equals(hash(target));
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static boolean isExcluded(Path relative, Set<String> excludes) {
        for (Path part : relative) {
            if (excludes.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean typeMismatch(Path source, Path target) {
        return Files.isDirectory(source) != Files.isDirectory(target);
    }

    private static void deleteIfEmptyOrFile(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var children = Files.list(path)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024D && index < units.length - 1) {
            value /= 1024D;
            index++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }

    private static String cleanMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown Error";
        }
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
