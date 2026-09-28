package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rebase.backend.feature.AsyncServerScheduleFeature;
import restudio.rebase.schedule.ServerScheduleModels;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.ui.widgets.PopupWidget.PopupRow;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;
import java.time.ZoneId;

public final class ServerScheduleSettingsController {
    private static final String TAB = "Schedules";

    private final ReScreen owner;
    private final AsyncServerScheduleFeature feature;
    private long operationSequence;
    private long taskRowSequence;
    private final List<ServerScheduleModels.Schedule> schedules = new ArrayList<>();
    private boolean loaded;
    private boolean loading;
    private boolean action;
    private boolean closed;
    private String loadError = "";
    private long generation;
    private Setting setting;
    private final Map<String, PopupRow> rows = new LinkedHashMap<>();
    private final Map<String, ScheduleRow> scheduleRows = new LinkedHashMap<>();
    private AnimatedButton addButton;
    private AnimatedButton statusButton;
    private AnimatedButton retryButton;
    private AnimatedButton reasonButton;

    public ServerScheduleSettingsController(ReScreen owner, AsyncServerScheduleFeature feature) {
        this.owner = owner;
        this.feature = feature;
    }

    public List<Setting> getSettings() {
        if (closed) return List.of();
        ServerScheduleModels.Capabilities capabilities = feature == null
                ? ServerScheduleModels.Capabilities.unavailable("Scheduling Is Unavailable") : feature.scheduleCapabilities();
        if (setting == null) setting = new Setting.Builder("Server Schedules").build();
        List<PopupRow> next = new ArrayList<>();
        if (!capabilities.available()) {
            statusButton = statusButton == null ? new AnimatedButton.Builder().active(false).build() : statusButton;
            statusButton.setMessage("Scheduling Unavailable");
            statusButton.setHint(capabilities.reason());
            next.add(row("status", statusButton));
            showRows(next);
            return List.of(setting);
        }
        if (!capabilities.reason().isBlank()) {
            if (reasonButton == null) reasonButton = new AnimatedButton.Builder().active(false).build();
            reasonButton.setMessage(capabilities.reason());
            next.add(row("reason", reasonButton));
        }
        if (addButton == null) addButton = new AnimatedButton.Builder().label("Add Schedule")
                .accentType(ThemeManager.getAccent("nice")).onClick(() -> showEditor(null,
                        ScheduleEditorDraft.create(null, List.of(defaultTask())))).build();
        addButton.active = !action;
        next.add(row("add", addButton));
        ensureLoaded();
        if (!loaded) {
            String label = loading ? "Loading Schedules" : loadError.isBlank() ? "Schedules Unavailable" : "Load Failed";
            statusButton = statusButton == null ? new AnimatedButton.Builder().active(false).build() : statusButton;
            statusButton.setMessage(label);
            statusButton.setHint(loadError);
            next.add(row("status", statusButton));
            if (!loading) {
                if (retryButton == null) retryButton = new AnimatedButton.Builder().label("Retry Load").onClick(this::load).build();
                next.add(row("retry", retryButton));
            }
            if (schedules.isEmpty()) {
                showRows(next);
                return List.of(setting);
            }
        }
        if (schedules.isEmpty()) {
            statusButton = statusButton == null ? new AnimatedButton.Builder().active(false).build() : statusButton;
            statusButton.setMessage("No Schedules");
            statusButton.setHint("");
            next.add(row("status", statusButton));
            showRows(next);
            return List.of(setting);
        }
        schedules.stream().sorted(Comparator.comparing(ServerScheduleModels.Schedule::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(schedule -> {
                    ScheduleRow current = scheduleRows.computeIfAbsent(schedule.id(), ignored -> new ScheduleRow(schedule));
                    current.update(schedule, capabilities.runNow());
                    next.add(row("schedule:" + schedule.id(), current.widget));
                });
        scheduleRows.keySet().removeIf(id -> schedules.stream().noneMatch(schedule -> schedule.id().equals(id)));
        showRows(next);
        return List.of(setting);
    }

    private PopupRow row(String id, Widget widget) {
        return rows.computeIfAbsent(id, ignored -> new PopupRow.Builder("", widget).id(id).build());
    }

    private void showRows(List<PopupRow> next) {
        if (!setting.getRows().equals(next)) setting.setRows(next);
        rows.keySet().removeIf(id -> next.stream().noneMatch(row -> id.equals(row.id)));
    }

    private final class ScheduleRow {
        private ServerScheduleModels.Schedule schedule;
        private final SquareButtonWidget run;
        private final SquareButtonWidget edit;
        private final SquareButtonWidget delete;
        private final MountableButtonWidget widget;

        private ScheduleRow(ServerScheduleModels.Schedule initial) {
            schedule = initial;
            run = new SquareButtonWidget.Builder().imagePath("start.png").hint("Run Now")
                    .accentType(ThemeManager.getAccent("nice")).onClick(() -> ServerScheduleSettingsController.this.run(schedule)).size(18, 18).build();
            edit = new SquareButtonWidget.Builder().imagePath("edit.png").hint("Edit Schedule")
                    .onClick(() -> showEditor(schedule, ScheduleEditorDraft.create(schedule, schedule.tasks()))).size(18, 18).build();
            delete = new SquareButtonWidget.Builder().imagePath("delete.png").hint("Delete Schedule")
                    .accentType(ThemeManager.getAccent("danger")).onClick(() -> confirmDelete(schedule)).size(18, 18).build();
            widget = new MountableButtonWidget.Builder(initial.name()).addButton(run).addButton(edit).addButton(delete).build();
        }

        private void update(ServerScheduleModels.Schedule current, boolean canRun) {
            schedule = current;
            String state = current.enabled() ? "Enabled" : "Disabled";
            String next = current.nextRunAt().isBlank() ? "No Next Run" : "Next " + displayTime(current.nextRunAt());
            widget.setName(current.name());
            widget.setMessage(current.name());
            widget.setDescription(state + " · " + next);
            widget.setHiddenText(current.lastResult().isBlank() ? "Never Run" : current.lastResult());
            run.active = !action && canRun;
            edit.active = !action;
            delete.active = !action;
        }
    }

    private void ensureLoaded() {
        if (!loaded && !loading && loadError.isBlank()) load();
    }

    private void load() {
        if (closed || loading || feature == null) return;
        loading = true;
        loadError = "";
        long request = ++generation;
        feature.listSchedules().whenComplete((values, failure) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != generation) return;
            loading = false;
            if (failure == null) {
                schedules.clear();
                if (values != null) schedules.addAll(values);
                loaded = true;
            } else {
                loaded = false;
                loadError = error(failure);
            }
            refresh();
        }));
    }

    private void showEditor(ServerScheduleModels.Schedule schedule, ScheduleEditorDraft draft) {
        Screen screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null || action) return;
        ServerScheduleModels.Capabilities capabilities = feature.scheduleCapabilities();
        PopupWidget.Builder popup = new PopupWidget.Builder(schedule == null ? "Add Schedule" : "Edit Schedule")
                .pos(50, screen.height / 8).size(480, 430).setResizable(true).setMinSize(420, 330);
        TextInputWidget name = input(draft.name(), "Schedule Name", 260);
        ToggleWidget enabled = new ToggleWidget.Builder().toggled(draft.enabled()).build();
        ToggleWidget onlyOnline = new ToggleWidget.Builder().toggled(draft.onlyOnline()).build();
        List<String> timingOptions = new ArrayList<>();
        if (capabilities.oneTime()) timingOptions.add("One Time");
        if (capabilities.cron()) timingOptions.add("Recurring");
        int timingIndex = timingOptions.indexOf(draft.timing());
        ScrollSelectorWidget timing = new ScrollSelectorWidget.Builder().options(timingOptions)
                .selectedIndex(Math.max(0, timingIndex)).size(150, 20).build();
        ScrollSelectorWidget preset = new ScrollSelectorWidget.Builder().options(ScheduleTimingGuide.PRESETS)
                .selectedIndex(ScheduleTimingGuide.PRESETS.indexOf(draft.preset())).size(150, 20).build();
        TextInputWidget recurringTime = input(draft.recurringTime(), "HH:MM", 90);
        ScrollSelectorWidget weekDay = new ScrollSelectorWidget.Builder().options(ScheduleTimingGuide.WEEKDAYS_LIST)
                .selectedIndex(draft.weekDay()).size(120, 20).build();
        TextInputWidget monthDay = input(draft.monthDay(), "1 To 31", 70);
        TextInputWidget advancedCron = input(draft.cron(), "Five Field Cron", 220);
        TextInputWidget when = input(draft.oneTime(), "2026-08-27 18:00", 260);
        TextInputWidget zone = input(draft.zone(), "Time Zone", 180);
        popup.addRow("name", "Name", name);
        popup.addRow("enabled", "Enabled", enabled);
        popup.addRow("timing", "Timing", timing);
        if (capabilities.oneTime()) popup.addRow("schedule-when", "Local Date And Time", when);
        if (capabilities.cron()) {
            popup.addRow("schedule-preset", "Recurring Pattern", preset);
            popup.addRow("schedule-time", "Recurring Time", recurringTime);
            popup.addRow("schedule-weekday", "Week Day", weekDay);
            popup.addRow("schedule-month-day", "Month Day", monthDay);
            popup.addRow("schedule-cron", "Advanced Cron", advancedCron);
            Runnable updateTimingFields = () -> updateTimingRows(popup.getWidget(), capabilities.oneTime(), capabilities.timeZone(),
                    "Recurring".equals(timing.getSelectedOption()), preset.getSelectedOption());
            timing.setOnChange(updateTimingFields);
            preset.setOnChange(updateTimingFields);
            if (capabilities.timeZone()) popup.addRow("schedule-zone", "Recurring Time Zone", zone);
            updateTimingFields.run();
        }
        popup.addRow("online", "Only While Running", onlyOnline);

        List<ServerScheduleModels.Task> tasks = draft.tasks().isEmpty() ? List.of(defaultTask()) : draft.tasks();
        List<TaskEditor> editors = new ArrayList<>();
        AnimatedButton addTask = new AnimatedButton.Builder().label("Add Task").onClick(() -> {
            if (editors.size() >= 32) return;
            editors.add(new TaskEditor(popup.getWidget(), editors, defaultTask(), capabilities.backup()));
            updateTaskRows(popup.getWidget(), editors);
        }).build();
        popup.addRow("add-task", "", addTask);
        popup.addTitleAction("Save", () -> save(popup, schedule, name, enabled, timing, when, preset, recurringTime,
                        weekDay, monthDay, advancedCron, zone, onlyOnline, editors),
                PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget widget = popup.build();
        for (ServerScheduleModels.Task task : tasks) editors.add(new TaskEditor(widget, editors, task, capabilities.backup()));
        updateTaskRows(widget, editors);
        screen.addDrawableChild(widget);
        widget.show();
    }

    private void updateTaskRows(PopupWidget popup, List<TaskEditor> editors) {
        List<PopupRow> next = new ArrayList<>(popup.getRows());
        next.removeIf(row -> row.id.startsWith("task-row:"));
        int addIndex = 0;
        while (addIndex < next.size() && !"add-task".equals(next.get(addIndex).id)) addIndex++;
        for (int index = 0; index < editors.size(); index++) {
            TaskEditor editor = editors.get(index);
            editor.number.setMessage("Task " + (index + 1));
            editor.up.active = index > 0;
            editor.down.active = index + 1 < editors.size();
            editor.remove.active = editors.size() > 1;
            next.add(addIndex + index, editor.row);
        }
        for (PopupRow row : next) {
            if ("add-task".equals(row.id) && row.getWidgets().getFirst() instanceof AnimatedButton add) {
                add.active = editors.size() < 32;
            }
        }
        if (!popup.getRows().equals(next)) popup.setRows(next);
    }

    private final class TaskEditor {
        private final String id;
        private final ScrollSelectorWidget action;
        private final TextInputWidget payload;
        private final TextInputWidget delay;
        private final ToggleWidget continueOnFailure;
        private final AnimatedButton number;
        private final SquareButtonWidget up;
        private final SquareButtonWidget down;
        private final SquareButtonWidget remove;
        private final PopupRow row;

        private TaskEditor(PopupWidget popup, List<TaskEditor> editors, ServerScheduleModels.Task task, boolean backup) {
            id = task.id();
            List<String> actions = actionOptions(backup);
            action = new ScrollSelectorWidget.Builder().options(actions)
                    .selectedIndex(Math.max(0, actions.indexOf(label(task.action())))).size(95, 20).build();
            payload = input(task.payload(), task.action() == ServerScheduleModels.Action.COMMAND ? "Command" : "Value", 135);
            delay = input(String.valueOf(task.delaySeconds()), "Delay", 50);
            continueOnFailure = new ToggleWidget.Builder().toggled(task.continueOnFailure()).size(36, 18).build();
            number = new AnimatedButton.Builder().label("Task").active(false).size(54, 20).build();
            up = new SquareButtonWidget.Builder().imagePath("up.png").hint("Move Up").size(18, 18).onClick(() -> {
                int index = editors.indexOf(this);
                if (index > 0) {
                    Collections.swap(editors, index, index - 1);
                    updateTaskRows(popup, editors);
                }
            }).build();
            down = new SquareButtonWidget.Builder().imagePath("down.png").hint("Move Down").size(18, 18).onClick(() -> {
                int index = editors.indexOf(this);
                if (index >= 0 && index + 1 < editors.size()) {
                    Collections.swap(editors, index, index + 1);
                    updateTaskRows(popup, editors);
                }
            }).build();
            remove = new SquareButtonWidget.Builder().imagePath("delete.png").hint("Remove Task")
                    .accentType(ThemeManager.getAccent("danger")).size(18, 18).onClick(() -> {
                        if (editors.size() <= 1) return;
                        editors.remove(this);
                        updateTaskRows(popup, editors);
                    }).build();
            row = new PopupRow.Builder("", number, action, payload, delay, continueOnFailure, up, down, remove)
                    .id("task-row:" + (++taskRowSequence)).build();
        }

        private String id() { return id; }
        private ScrollSelectorWidget action() { return action; }
        private TextInputWidget payload() { return payload; }
        private TextInputWidget delay() { return delay; }
        private ToggleWidget continueOnFailure() { return continueOnFailure; }
    }

    private void save(PopupWidget.Builder popup, ServerScheduleModels.Schedule schedule, TextInputWidget name,
                      ToggleWidget enabled, ScrollSelectorWidget timing, TextInputWidget when, ScrollSelectorWidget preset,
                      TextInputWidget recurringTime, ScrollSelectorWidget weekDay, TextInputWidget monthDay,
                      TextInputWidget advancedCron, TextInputWidget zone,
                      ToggleWidget onlyOnline, List<TaskEditor> editors) {
        if (closed || action) return;
        String scheduleName = name.getText().trim();
        if (scheduleName.isBlank()) {
            new Notification("Invalid Schedule", "Name Is Required", Notification.Type.WARN);
            return;
        }
        boolean oneTime = "One Time".equals(timing.getSelectedOption());
        String value;
        try {
            value = oneTime ? ScheduleEditorDraft.instant(when.getText(), zone.getText().isBlank() ? ZoneId.systemDefault().getId() : zone.getText().trim())
                    : ScheduleTimingGuide.cron(preset.getSelectedOption(), recurringTime.getText(),
                    Math.max(0, ScheduleTimingGuide.WEEKDAYS_LIST.indexOf(weekDay.getSelectedOption())), monthDay.getText(), advancedCron.getText());
            if (oneTime && !Instant.parse(value).isAfter(Instant.now())) throw new IllegalArgumentException("One Time Schedule Must Be In The Future");
            if (!oneTime) ScheduleTimingGuide.summary(preset.getSelectedOption(), recurringTime.getText(),
                    Math.max(0, ScheduleTimingGuide.WEEKDAYS_LIST.indexOf(weekDay.getSelectedOption())), monthDay.getText(), advancedCron.getText(), zone.getText());
        } catch (RuntimeException failure) {
            new Notification("Invalid Schedule", failure.getMessage(), Notification.Type.WARN);
            return;
        }
        if (value.isBlank()) {
            new Notification("Invalid Schedule", oneTime ? "Date And Time Are Required" : "Cron Is Required", Notification.Type.WARN);
            return;
        }
        List<ServerScheduleModels.Task> tasks;
        try {
            tasks = capture(editors);
        } catch (RuntimeException failure) {
            new Notification("Invalid Schedule", failure.getMessage(), Notification.Type.WARN);
            return;
        }
        String timingSummary;
        try {
            timingSummary = oneTime ? "Runs Once At " + ScheduleEditorDraft.display(value,
                    zone.getText().isBlank() ? ZoneId.systemDefault().getId() : zone.getText().trim()) : ScheduleTimingGuide.summary(preset.getSelectedOption(), recurringTime.getText(),
                    Math.max(0, ScheduleTimingGuide.WEEKDAYS_LIST.indexOf(weekDay.getSelectedOption())), monthDay.getText(), advancedCron.getText(), zone.getText());
            if (!oneTime) ZoneId.of(zone.getText().isBlank() ? "UTC" : zone.getText().trim());
        } catch (RuntimeException failure) {
            new Notification("Invalid Schedule", "Choose A Valid Time Zone Like UTC Or Europe/Berlin", Notification.Type.WARN);
            return;
        }
        ServerScheduleModels.Timing scheduleTiming = new ServerScheduleModels.Timing(oneTime
                ? ServerScheduleModels.TimingType.ONE_TIME : ServerScheduleModels.TimingType.CRON,
                oneTime ? value : "", oneTime ? "" : value, zone.getText().trim());
        ServerScheduleModels.Mutation mutation = new ServerScheduleModels.Mutation(scheduleName, enabled.getValue(), scheduleTiming,
                onlyOnline.getValue(), tasks);
        action = true;
        String key = operationKey();
        var operation = schedule == null ? feature.createSchedule(mutation, key)
                : feature.updateSchedule(schedule.id(), mutation, schedule.revision(), key);
        operation.whenComplete((updated, failure) -> ScreenManager.getInstance().execute(() -> {
            if (closed) return;
            action = false;
            if (failure == null && updated != null) {
                popup.getWidget().setVisible(false);
                upsert(updated);
                new Notification("Schedule Saved", timingSummary + " · Next " + displayTime(updated.nextRunAt()), Notification.Type.SUCCESS);
            } else {
                new Notification("Save Failed", error(failure), Notification.Type.ERROR);
            }
            refresh();
        }));
    }

    private List<ServerScheduleModels.Task> capture(List<TaskEditor> editors) {
        List<ServerScheduleModels.Task> tasks = new ArrayList<>();
        for (int index = 0; index < editors.size(); index++) {
            TaskEditor editor = editors.get(index);
            ServerScheduleModels.Action action = parseAction(editor.action().getSelectedOption());
            String payload = editor.payload().getText();
            if (action == ServerScheduleModels.Action.COMMAND && payload.isBlank()) {
                throw new IllegalArgumentException("Command Is Required For Task " + (index + 1));
            }
            int delay;
            try {
                delay = Integer.parseInt(editor.delay().getText().trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Task Delay Must Be A Number");
            }
            if (delay < 0 || delay > 86400) throw new IllegalArgumentException("Task Delay Must Be Between 0 And 86400");
            tasks.add(new ServerScheduleModels.Task(editor.id(), index + 1, action, payload, delay,
                    editor.continueOnFailure().getValue()));
        }
        return tasks;
    }

    private void run(ServerScheduleModels.Schedule schedule) {
        if (action) return;
        action = true;
        feature.runSchedule(schedule.id(), operationKey()).whenComplete((run, failure) -> ScreenManager.getInstance().execute(() -> {
            if (closed) return;
            action = false;
            new Notification(failure == null ? "Schedule Started" : "Run Failed",
                    failure == null ? schedule.name() : error(failure), failure == null ? Notification.Type.SUCCESS : Notification.Type.ERROR);
            loadError = "";
            loaded = false;
            refresh();
        }));
    }

    private void confirmDelete(ServerScheduleModels.Schedule schedule) {
        Screen screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null || action) return;
        PopupWidget.Builder popup = new PopupWidget.Builder("Delete Schedule").pos(70, screen.height / 4).size(360, 150);
        popup.addRow("confirm", "Delete " + schedule.name() + "?");
        popup.addTitleAction("Delete", () -> {
            popup.getWidget().setVisible(false);
            action = true;
            feature.deleteSchedule(schedule.id(), schedule.revision(), operationKey()).whenComplete((ignored, failure) ->
                    ScreenManager.getInstance().execute(() -> {
                        if (closed) return;
                        action = false;
                        if (failure == null) {
                            schedules.removeIf(value -> value.id().equals(schedule.id()));
                            new Notification("Schedule Deleted", schedule.name(), Notification.Type.INFO);
                        } else {
                            new Notification("Delete Failed", error(failure), Notification.Type.ERROR);
                        }
                        refresh();
                    }));
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        PopupWidget widget = popup.build();
        screen.addDrawableChild(widget);
        widget.show();
    }

    private void upsert(ServerScheduleModels.Schedule schedule) {
        schedules.removeIf(value -> value.id().equals(schedule.id()));
        schedules.add(schedule);
    }

    private void refresh() {
        if (closed) return;
        ScreenManager manager = ScreenManager.getInstance();
        List<SettingsScreen> screens = new ArrayList<>();
        if (manager.getCurrentScreen() instanceof SettingsScreen settings) screens.add(settings);
        if (manager.getDesktopWindowsOverlay() != null) {
            for (ScreenWindowWidget window : manager.getDesktopWindowsOverlay().getWindows()) {
                if (window.getScreen() instanceof SettingsScreen settings && !screens.contains(settings)) screens.add(settings);
            }
        }
        screens.forEach(settings -> settings.refreshTab(TAB));
    }

    private List<String> actionOptions(boolean backup) {
        List<String> values = new ArrayList<>(List.of("Start", "Stop", "Restart", "Command"));
        if (backup) values.add("Backup");
        return values;
    }

    private String label(ServerScheduleModels.Action action) {
        String value = action.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private ServerScheduleModels.Action parseAction(String value) {
        return ServerScheduleModels.Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private ServerScheduleModels.Task defaultTask() {
        return new ServerScheduleModels.Task("", 1, ServerScheduleModels.Action.RESTART, "", 0, false);
    }

    private TextInputWidget input(String value, String placeholder, int width) {
        return new TextInputWidget.Builder().text(value == null ? "" : value).placeholder(placeholder).size(width, 20).build();
    }

    private String operationKey() {
        return System.currentTimeMillis() + "-" + (++operationSequence);
    }

    private String error(Throwable failure) {
        Throwable value = failure;
        while (value != null && value.getCause() != null) value = value.getCause();
        String message = value == null ? "Schedule Operation Failed" : value.getMessage();
        return message == null || message.isBlank() ? "Schedule Operation Failed" : message;
    }

    private static String displayTime(String value) {
        if (value == null || value.isBlank()) return "Not Scheduled";
        try {
            return ScheduleEditorDraft.display(value, ZoneId.systemDefault().getId());
        } catch (RuntimeException failure) {
            return value;
        }
    }

    private static void updateTimingRows(PopupWidget popup, boolean oneTime, boolean timeZone, boolean recurring, String preset) {
        if (oneTime) popup.setRowVisibility("schedule-when", !recurring);
        popup.setRowVisibility("schedule-preset", recurring);
        popup.setRowVisibility("schedule-time", recurring && (ScheduleTimingGuide.DAILY.equals(preset)
                || ScheduleTimingGuide.WEEKDAYS.equals(preset) || ScheduleTimingGuide.WEEKLY.equals(preset)
                || ScheduleTimingGuide.MONTHLY.equals(preset)));
        popup.setRowVisibility("schedule-weekday", recurring && ScheduleTimingGuide.WEEKLY.equals(preset));
        popup.setRowVisibility("schedule-month-day", recurring && ScheduleTimingGuide.MONTHLY.equals(preset));
        popup.setRowVisibility("schedule-cron", recurring && ScheduleTimingGuide.ADVANCED.equals(preset));
        if (timeZone) popup.setRowVisibility("schedule-zone", recurring);
    }

    public void cleanup() {
        closed = true;
        generation++;
    }

}
