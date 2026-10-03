package redxax.oxy.remotely.servers;

import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.ServerBackend;
import restudio.rebase.backend.feature.ServerScheduleFeature;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.platform.jvm.JvmPersistedServerScheduleFeature;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopServerSchedules implements AutoCloseable {
    private final InstanceManager instances;
    private final Runnable changed = this::refresh;
    private final Object lock = new Object();
    private final Map<String, Owner> active = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Remotely Schedule Startup");
        thread.setDaemon(true);
        return thread;
    });
    private List<Instance> pending = List.of();
    private long revision;
    private boolean queued;
    private boolean started;
    private volatile boolean closed;

    public DesktopServerSchedules(InstanceManager instances) {
        this.instances = instances;
    }

    public void start() {
        synchronized (lock) {
            if (started || closed) return;
            started = true;
            instances.addChangeListener(changed);
        }
        refresh();
    }

    private void refresh() {
        List<Instance> snapshot = instances.getAllInstances().stream().filter(DesktopServerSchedules::supported).toList();
        synchronized (lock) {
            if (closed) return;
            pending = snapshot;
            revision++;
            if (queued) return;
            queued = true;
            worker.execute(this::update);
        }
    }

    private void update() {
        while (!closed) {
            List<Instance> snapshot;
            long stamp;
            synchronized (lock) {
                snapshot = pending;
                stamp = revision;
            }
            active.entrySet().removeIf(entry -> {
                if (snapshot.stream().anyMatch(instance -> instance == entry.getValue().instance())) return false;
                stop(entry.getValue().instance());
                return true;
            });
            for (Instance instance : snapshot) {
                if (closed) break;
                try {
                    Owner previous = active.get(instance.getInstanceId());
                    if (previous == null && !JvmPersistedServerScheduleFeature.hasSavedSchedules(instance)) continue;
                    ServerBackend backend = instance.getBackend();
                    if (previous != null && previous.backend() == backend) continue;
                    if (backend != null && backend.getFeature(ServerScheduleFeature.class).isPresent()) {
                        active.put(instance.getInstanceId(), new Owner(instance, backend));
                    } else {
                        active.remove(instance.getInstanceId());
                    }
                } catch (RuntimeException failure) {
                    ReLog.logger(LogTypes.INSTANCE_LIFECYCLE).source(LogSource.instance(instance.getInstanceId(), instance.getName()))
                            .component(DesktopServerSchedules.class).operation("Restore Schedules").error("Could not restore server schedules", failure);
                }
            }
            synchronized (lock) {
                if (stamp != revision) continue;
                queued = false;
                return;
            }
        }
    }

    private record Owner(Instance instance, ServerBackend backend) {}

    private void stop(Instance instance) {
        try {
            instance.closeBackend();
        } catch (RuntimeException failure) {
            ReLog.logger(LogTypes.INSTANCE_LIFECYCLE).source(LogSource.instance(instance.getInstanceId(), instance.getName()))
                    .component(DesktopServerSchedules.class).operation("Stop Schedules").error("Could not stop server schedules", failure);
        }
    }

    private static boolean supported(Instance instance) {
        if (instance == null || instance.isTemporary() || !instance.isServer()) return false;
        BackendConfig backend = instance.getBackendConfig();
        return backend != null && ("LOCAL".equalsIgnoreCase(backend.type) || "SSH".equalsIgnoreCase(backend.type));
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            instances.removeChangeListener(changed);
            worker.execute(() -> {
                active.values().forEach(owner -> stop(owner.instance()));
                active.clear();
            });
            worker.shutdown();
        }
    }
}
