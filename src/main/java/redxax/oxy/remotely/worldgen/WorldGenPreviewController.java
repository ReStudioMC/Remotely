package redxax.oxy.remotely.worldgen;

import restudio.rescreen.platform.TaskScheduler;
import redxax.oxy.remotely.util.BrowserSafeState;
import java.util.Map;
import java.util.Set;
import java.util.Map;

final class WorldGenPreviewController {
    private final Map<String, PreviewOperation> operations = BrowserSafeState.map();

    void markCreating(String serverId, String previewId) {
        String key = previewKey(serverId, previewId);
        PreviewOperation previous = operations.put(key, new PreviewOperation("creating"));
        if (previous != null) {
            previous.cancel();
        }
    }

    void update(String serverId, String previewId, String status) {
        PreviewOperation operation = operations.get(previewKey(serverId, previewId));
        if (operation != null) {
            operation.state = status == null || status.isBlank() ? "stopped" : status;
        }
    }

    void fail(String serverId, String previewId) {
        PreviewOperation operation = operations.get(previewKey(serverId, previewId));
        if (operation != null) {
            operation.state = "error";
            operation.cancelResources();
        }
    }

    void stop(String serverId, String previewId) {
        clear(previewKey(serverId, previewId));
    }

    void trackTimer(String serverId, String previewId, TaskScheduler.ScheduledTask timer) {
        if (timer == null) {
            return;
        }
        PreviewOperation operation = operations.get(previewKey(serverId, previewId));
        if (operation == null || !operation.trackTimer(timer)) {
            timer.cancel();
        }
    }

    void addCancellationCallback(String serverId, String previewId, Runnable callback) {
        if (callback == null) {
            return;
        }
        PreviewOperation operation = operations.get(previewKey(serverId, previewId));
        if (operation == null) {
            runCallback(callback);
            return;
        }
        if (!operation.addCallback(callback)) {
            runCallback(callback);
        }
    }

    void clearServer(String serverId) {
        String prefix = WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":";
        for (String key : operations.keySet()) {
            if (key.startsWith(prefix)) {
                clear(key);
            }
        }
    }

    void clearAll() {
        for (String key : operations.keySet()) {
            clear(key);
        }
    }

    void cancelServer(String serverId) {
        clearServer(serverId);
    }

    void cancelAll() {
        clearAll();
    }

    String state(String serverId, String previewId) {
        PreviewOperation operation = operations.get(previewKey(serverId, previewId));
        return operation == null ? "stopped" : operation.state;
    }

    private String previewKey(String serverId, String previewId) {
        return WorldGenCatalogProjection.normalizeBaseServerId(serverId) + ":" + (previewId == null ? "" : previewId);
    }

    private void clear(String key) {
        PreviewOperation operation = operations.remove(key);
        if (operation != null) {
            operation.cancel();
        }
    }

    private static void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException | Error ignored) {
        }
    }

    private static final class PreviewOperation {
        private volatile String state;
        private volatile TaskScheduler.ScheduledTask timer;
        private final Set<Runnable> callbacks = BrowserSafeState.set();

        private PreviewOperation(String state) {
            this.state = state;
        }

        private synchronized boolean trackTimer(TaskScheduler.ScheduledTask nextTimer) {
            if (state == null || "stopped".equalsIgnoreCase(state) || "error".equalsIgnoreCase(state)) {
                return false;
            }
            TaskScheduler.ScheduledTask previousTimer = timer;
            timer = nextTimer;
            if (previousTimer != null) {
                previousTimer.cancel();
            }
            return true;
        }

        private synchronized boolean addCallback(Runnable callback) {
            if (state == null || "stopped".equalsIgnoreCase(state) || "error".equalsIgnoreCase(state)) {
                return false;
            }
            return callbacks.add(callback);
        }

        private void cancel() {
            state = "stopped";
            cancelResources();
        }

        private synchronized void cancelResources() {
            TaskScheduler.ScheduledTask currentTimer = timer;
            timer = null;
            if (currentTimer != null) {
                currentTimer.cancel();
            }
            for (Runnable callback : callbacks) {
                if (callbacks.remove(callback)) {
                    runCallback(callback);
                }
            }
        }
    }
}
