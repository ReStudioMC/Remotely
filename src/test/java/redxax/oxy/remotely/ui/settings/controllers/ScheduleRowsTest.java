package redxax.oxy.remotely.ui.settings.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import restudio.rebase.backend.feature.AsyncServerScheduleFeature;
import restudio.rebase.schedule.ServerScheduleModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Isolated
class ScheduleRowsTest {
    @Test
    void refreshedSchedulesAndDisplayZonesUpdateTheirVisibleTimingAndResult() {
        TimeZone previous = TimeZone.getDefault();
        ThemeManager.initBrowserDefaults();
        AtomicReference<ServerScheduleModels.Schedule> current = new AtomicReference<>(schedule("Nightly", "2026-10-01T04:00:30Z", "", "1"));
        AsyncServerScheduleFeature feature = (AsyncServerScheduleFeature) Proxy.newProxyInstance(
                AsyncServerScheduleFeature.class.getClassLoader(), new Class<?>[]{AsyncServerScheduleFeature.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "scheduleCapabilities" -> new ServerScheduleModels.Capabilities(true, "", ServerScheduleModels.Durability.BACKEND,
                            true, true, true, true, true, true, true);
                    case "listSchedules" -> Async.completed(List.of(current.get()));
                    case "runSchedule" -> Async.completed(new ServerScheduleModels.Run("run", "schedule", "", "", "", "COMPLETED", "Completed"));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ServerScheduleSettingsController controller = new ServerScheduleSettingsController(null, feature);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            controller.getSettings();
            ScreenManager.getInstance().processTasks();
            MountableButtonWidget row = row(controller);
            assertEquals("Nightly", row.name);
            assertEquals("Enabled · Next 2026-10-01 04:00:30 UTC", row.description);
            assertEquals("Never Run", row.hiddenText);
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
            assertEquals("Enabled · Next 2026-10-01 06:00:30 Europe/Paris", row(controller).description);
            current.set(schedule("Updated", "invalid-date", "Backup Failed", "2"));
            ((SquareButtonWidget) row.mountedWidgets.getFirst()).action.run();
            ScreenManager.getInstance().processTasks();
            controller.getSettings();
            ScreenManager.getInstance().processTasks();
            row = row(controller);
            assertEquals("Updated", row.name);
            assertEquals("Enabled · Next invalid-date", row.description);
            assertEquals("Backup Failed", row.hiddenText);
            current.set(schedule("No Upcoming Run", "", "Completed", "3"));
            ((SquareButtonWidget) row.mountedWidgets.getFirst()).action.run();
            ScreenManager.getInstance().processTasks();
            controller.getSettings();
            ScreenManager.getInstance().processTasks();
            assertEquals("Enabled · No Next Run", row(controller).description);
        } finally {
            controller.cleanup();
            TimeZone.setDefault(previous);
        }
    }

    private MountableButtonWidget row(ServerScheduleSettingsController controller) {
        return (MountableButtonWidget) controller.getSettings().getFirst().getRows().stream()
                .filter(row -> row.id.equals("schedule:schedule")).findFirst().orElseThrow().getWidgets().getFirst();
    }

    private ServerScheduleModels.Schedule schedule(String name, String next, String result, String revision) {
        return new ServerScheduleModels.Schedule("schedule", name, true,
                new ServerScheduleModels.Timing(ServerScheduleModels.TimingType.CRON, "", "30 0 4 * * *", "UTC"), false,
                List.of(new ServerScheduleModels.Task("task", 1, ServerScheduleModels.Action.BACKUP, "Backup", 0, false, 3, 0)),
                next, "", result, revision, false);
    }
}
