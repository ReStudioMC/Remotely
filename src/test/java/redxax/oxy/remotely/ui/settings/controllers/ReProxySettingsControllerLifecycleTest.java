package redxax.oxy.remotely.ui.settings.controllers;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.MountableButtonWidget;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReProxySettingsControllerLifecycleTest {
    @Test
    void oldLoadCannotReplaceDataAfterReactivation() {
        ThemeManager.Snapshot theme = ThemeManager.snapshot();
        TaskScheduler previous = TaskSchedulers.current();
        ThemeManager.initBrowserDefaults();
        TaskSchedulers.configure(new NoopScheduler());
        try {
            PendingCapability capability = new PendingCapability();
            ReProxySettingsController controller = new ReProxySettingsController(capability);
            assertEquals(1, controller.getSettings().size());
            controller.cleanup();
            controller.activate();
            assertEquals(2, capability.requests.size());

            capability.requests.get(0).complete(summary("old", "old.example"));
            ScreenManager.getInstance().processTasks();
            assertEquals(1, controller.getSettings().size());

            capability.requests.get(1).complete(summary("new", "new.example"));
            ScreenManager.getInstance().processTasks();
            var settings = controller.getSettings();
            assertEquals(2, settings.size());
            MountableButtonWidget domain = (MountableButtonWidget) settings.get(1).getRows().getFirst().getWidgets().getFirst();
            assertEquals("new.example", domain.name);
            controller.cleanup();
        } finally {
            TaskSchedulers.configure(previous);
            ThemeManager.restore(theme);
        }
    }

    private ServerModels.ReProxySummary summary(String id, String address) {
        ServerModels.ReProxyDomain domain = new ServerModels.ReProxyDomain();
        domain.id = id;
        domain.fullDomain = address;
        domain.status = "ACTIVE";
        ServerModels.ReProxySummary summary = new ServerModels.ReProxySummary();
        summary.domains = List.of(domain);
        return summary;
    }

    private static final class PendingCapability implements ReProxySettingsCapability {
        private final List<Async<ServerModels.ReProxySummary>> requests = new ArrayList<>();

        @Override public boolean authenticated() { return true; }
        @Override public Availability availability() { return Availability.supported(); }
        @Override public Async<ServerModels.ReProxySummary> summary() {
            Async<ServerModels.ReProxySummary> request = Async.pending();
            requests.add(request);
            return request;
        }
        @Override public Async<ServerModels.ReProxyDomain> createDomain(String subdomain) { return Async.completed(null); }
        @Override public Async<Void> deleteDomain(String domainId) { return Async.completed(null); }
        @Override public Async<Void> stopTunnel(String tunnelId) { return Async.completed(null); }
        @Override public void copyAddress(String address) { }
    }

    private static final class NoopScheduler implements TaskScheduler {
        @Override public void execute(Runnable task) { task.run(); }
        @Override public ScheduledTask schedule(Runnable task, Duration delay) { return new NoopTask(); }
        @Override public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) { return new NoopTask(); }
    }

    private static final class NoopTask implements TaskScheduler.ScheduledTask {
        @Override public boolean cancel() { return true; }
        @Override public boolean isCancelled() { return false; }
    }
}
