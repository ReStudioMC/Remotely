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
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
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
import java.util.HashSet;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ServerScheduleSettingsController {
    private static final String TAB = "Schedules";
    private static final List<String> ZONES = ZoneId.getAvailableZoneIds().stream().sorted().toList();
    private static final int FIELD_WIDTH = 140;

    private final ReScreen owner;
    private final AsyncServerScheduleFeature feature;
    private long operationSequence;
    private long taskRowSequence;
    private final List<ServerScheduleModels.Schedule> schedules = new ArrayList<>();
    private boolean loaded;
    private boolean loading;
    private boolean action;
    private final Set<String> running = new HashSet<>();
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
        private ServerScheduleModels.Schedule rendered;
        private String displayZone;
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
            String zone = ZoneId.systemDefault().getId();
            if (!current.equals(rendered) || !zone.equals(displayZone)) {
                String state = current.enabled() ? "Enabled" : "Disabled";
                String next = current.nextRunAt().isBlank() ? "No Next Run" : "Next " + displayTime(current.nextRunAt());
                widget.setName(current.name());
                widget.setMessage(current.name());
                widget.setDescription(state + " · " + next);
                widget.setHiddenText(current.lastResult().isBlank() ? "Never Run" : current.lastResult());
                rendered = current;
                displayZone = zone;
            }
            run.active = !action && canRun && !current.processing() && !running.contains(current.id());
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
                .size(500, 460).setResizable(true).setMinSize(440, 330)
                .animateElevation(false).enableHoverColors(false).animateColor(false).entranceAnimation(false);
        TextInputWidget name = input(draft.name(), "Schedule Name", 260);
        ToggleWidget enabled = toggle(draft.enabled(), "Enable Schedule");
        ToggleWidget onlyOnline = toggle(draft.onlyOnline(), "Run Tasks Only While The Server Is Running");
        ToggleWidget timing = toggle(capabilities.cron() && (!capabilities.oneTime() || "Recurring".equals(draft.timing())), "Repeat This Schedule");
        timing.setActive(capabilities.cron() && capabilities.oneTime());
        List<String> presets = ScheduleTimingGuide.PRESETS.stream()
                .filter(value -> capabilities.seconds() || !ScheduleTimingGuide.SECONDLY.equals(value)).toList();
        DropDownWidget<String> preset = ScheduleDatePicker.quiet(new DropDownWidget.Builder<>(presets)
                .selectedItem(draft.preset()).size(ScheduleDatePicker.SELECTOR_WIDTH, 20).build());
        MountableButtonWidget timingRow = new MountableButtonWidget.Builder("Recurring")
                .description("Off Runs Once. On Repeats At The Chosen Frequency").addWidget(timing).build();
        TextInputWidget advancedCron = input(draft.cron(), capabilities.seconds() ? "Five Or Six Field Cron" : "Five Field Cron", 260);
        String currentZone = draft.zone().isBlank() ? ZoneId.systemDefault().getId() : draft.zone();
        List<String> zones = new ArrayList<>(ZONES);
        if (!zones.contains(currentZone)) zones.addFirst(currentZone);
        DropDownWidget<String> zone = new DropDownWidget.Builder<>(zones).selectedItem(currentZone).maxVisibleItems(8).size(220, 20).build();
        LocalDateTime selected = draft.dateTime(LocalDate.now());
        ScheduleDatePicker picker = new ScheduleDatePicker(selected, capabilities.seconds());
        picker.addMountedWidget(preset);
        AnimatedButton summary = new AnimatedButton.Builder().active(false).size(260, 22).build();
        popup.addRow("name", "Name", name, enabled);
        popup.addRow("timing", "", timingRow);
        popup.addRow("schedule-picker", "", picker);
        if (capabilities.cron()) popup.addRow("schedule-cron", "Advanced Cron", advancedCron);
        if (capabilities.timeZone()) popup.addRow("schedule-zone", "Time Zone", zone);
        popup.addRow("schedule-summary", "", summary);
        Runnable updateTiming = () -> {
            boolean recurring = timing.getValue();
            String pattern = recurring ? preset.getSelectedItem() : "One Time";
            picker.pattern(pattern);
            timingRow.setDescription(recurring ? "Repeats At The Chosen Frequency" : "Runs Once At The Chosen Date And Time");
            preset.setVisible(capabilities.cron() && recurring);
            picker.controls(!recurring || !ScheduleTimingGuide.ADVANCED.equals(pattern)
                    && !ScheduleTimingGuide.SECONDLY.equals(pattern) && (capabilities.seconds() || !ScheduleTimingGuide.MINUTELY.equals(pattern)));
            popup.getWidget().setRowVisibility("schedule-cron", recurring && ScheduleTimingGuide.ADVANCED.equals(pattern));
            try {
                String text = timingSummary(recurring, pattern, picker, advancedCron.getText(), capabilities.timeZone() ? zone.getSelectedItem() : "Panel Time");
                summary.setMessage(text);
                summary.setHint(text + (ScheduleTimingGuide.MONTHLY.equals(pattern) || ScheduleTimingGuide.YEARLY.equals(pattern)
                        ? "\nMonths Without This Date Are Skipped" : ""));
            } catch (RuntimeException invalid) {
                summary.setMessage(invalid.getMessage());
            }
        };
        timing.setOnChange(updateTiming);
        preset.setOnSelectionChanged(value -> updateTiming.run());
        picker.onChange(updateTiming);
        zone.setOnSelectionChanged(value -> updateTiming.run());
        advancedCron.setOnChange(updateTiming);
        updateTiming.run();
        popup.addRow("online", "", new MountableButtonWidget.Builder("Only While Running")
                .description("Skip A Run When The Server Is Offline").addWidget(onlyOnline).build());

        List<ServerScheduleModels.Task> tasks = draft.tasks().isEmpty() ? List.of(defaultTask()) : draft.tasks();
        List<TaskEditor> editors = new ArrayList<>();
        AnimatedButton addTask = new AnimatedButton.Builder().label("Add Task").onClick(() -> {
            if (editors.size() >= 32) return;
            editors.add(new TaskEditor(popup.getWidget(), editors, defaultTask(), capabilities.backup(), capabilities.retention()));
            updateTaskRows(popup.getWidget(), editors);
        }).build();
        popup.addRow("add-task", "", addTask);
        popup.addTitleAction("Save", () -> save(popup, schedule, name, enabled, timing, preset, picker, advancedCron, zone, onlyOnline, editors),
                PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget widget = ScheduleDatePicker.quiet(popup.build());
        for (ServerScheduleModels.Task task : tasks) editors.add(new TaskEditor(widget, editors, task, capabilities.backup(), capabilities.retention()));
        updateTaskRows(widget, editors);
        widget.centerOnOwner();
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
            editor.number.setName("Task " + (index + 1));
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
        private final DropDownWidget<String> action;
        private final TextInputWidget payload;
        private final TextInputWidget delay;
        private final ToggleWidget continueOnFailure;
        private final MountableButtonWidget number;
        private final TextInputWidget keep;
        private final TextInputWidget age;
        private boolean expanded;
        private final SquareButtonWidget up;
        private final SquareButtonWidget down;
        private final SquareButtonWidget remove;
        private final PopupRow row;

        private TaskEditor(PopupWidget popup, List<TaskEditor> editors, ServerScheduleModels.Task task, boolean backup, boolean retention) {
            id = task.id();
            List<String> actions = actionOptions(backup);
            action = new DropDownWidget.Builder<>(actions).selectedItem(label(task.action())).size(FIELD_WIDTH, 20).build();
            payload = input(task.payload(), task.action() == ServerScheduleModels.Action.COMMAND ? "Command" : "Value", FIELD_WIDTH);
            delay = input(String.valueOf(task.delaySeconds()), "Seconds", FIELD_WIDTH);
            continueOnFailure = toggle(task.continueOnFailure(), "Continue With The Next Task If This Task Fails");
            keep = input(Integer.toString(task.keepBackups()), "0 Keeps All", FIELD_WIDTH);
            age = input(Integer.toString(task.retentionDays()), "0 Has No Age Limit", FIELD_WIDTH);
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
            number = new MountableButtonWidget.Builder("Task").addButton(up).addButton(down).addButton(remove).build();
            MountableButtonWidget actionRow = new MountableButtonWidget.Builder("Task Type").description("Choose What This Task Does").addWidget(action).build();
            MountableButtonWidget valueRow = new MountableButtonWidget.Builder("Value").addWidget(payload).build();
            MountableButtonWidget delayRow = new MountableButtonWidget.Builder("Wait Before Task").description("Pause Before This Task, In Seconds").addWidget(delay).build();
            MountableButtonWidget failureRow = new MountableButtonWidget.Builder("Continue After Failure").description("Run The Next Task If This One Fails").addWidget(continueOnFailure).build();
            MountableButtonWidget keepRow = new MountableButtonWidget.Builder("Keep Backups").description("Newest Successful Copies. 0 Keeps All").addWidget(keep).build();
            MountableButtonWidget ageRow = new MountableButtonWidget.Builder("Maximum Age").description("Days. 0 Has No Age Limit").addWidget(age).build();
            MountableButtonWidget retentionInfo = new MountableButtonWidget.Builder("Only This Task's Backups")
                    .description("Locked Backups And The Newest Successful Copy Are Kept").build();
            List<AnimatedWidget> details = List.of(actionRow, valueRow, delayRow, failureRow, keepRow, ageRow, retentionInfo);
            number.setEmbeddedBody(details, false);
            number.setOnClick(() -> { expanded = !expanded; number.setEmbeddedBody(null, expanded); });
            Runnable updateAction = () -> {
                ServerScheduleModels.Action selected = parseAction(action.getSelectedItem());
                valueRow.setVisible(selected == ServerScheduleModels.Action.COMMAND || selected == ServerScheduleModels.Action.BACKUP);
                valueRow.setName(selected == ServerScheduleModels.Action.BACKUP ? "Backup Name" : "Command");
                valueRow.setDescription(selected == ServerScheduleModels.Action.BACKUP ? "A Name For Each New Backup" : "Send This Command To The Server Console");
                actionRow.setDescription(switch (selected) {
                    case BACKUP -> "Create A Backup Of The Server";
                    case COMMAND -> "Send A Console Command";
                    case START -> "Start The Server";
                    case STOP -> "Stop The Server";
                    case RESTART -> "Restart The Server";
                    default -> label(selected);
                });
                keepRow.setVisible(retention && selected == ServerScheduleModels.Action.BACKUP);
                ageRow.setVisible(retention && selected == ServerScheduleModels.Action.BACKUP);
                retentionInfo.setVisible(retention && selected == ServerScheduleModels.Action.BACKUP);
                number.setDescription(selected == ServerScheduleModels.Action.BACKUP && retention
                        ? "Keep " + (keep.getText().equals("0") ? "All" : keep.getText()) + (age.getText().equals("0") ? "" : " · " + age.getText() + " Days") : label(selected));
                number.setEmbeddedBody(null, expanded);
            };
            action.setOnSelectionChanged(value -> updateAction.run());
            keep.setOnChange(updateAction);
            age.setOnChange(updateAction);
            updateAction.run();
            ScheduleDatePicker.quiet(number);
            row = new PopupRow.Builder("", number).id("task-row:" + (++taskRowSequence)).build();
        }

        private String id() { return id; }
        private DropDownWidget<String> action() { return action; }
        private TextInputWidget payload() { return payload; }
        private TextInputWidget delay() { return delay; }
        private ToggleWidget continueOnFailure() { return continueOnFailure; }
    }

    private String timingSummary(boolean recurring, String pattern, ScheduleDatePicker picker, String advanced, String zone) {
        if (!recurring) return "Runs Once On " + picker.value().format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss")) + " In " + zone;
        String summary = ScheduleTimingGuide.summary(pattern, picker.clock(), picker.weekDay(), picker.monthDay(), advanced, zone);
        if (ScheduleTimingGuide.YEARLY.equals(pattern)) summary = "Runs Every " + picker.value().format(DateTimeFormatter.ofPattern("d MMM"))
                + " At " + picker.clock() + " In " + zone;
        return summary;
    }

    private void save(PopupWidget.Builder popup, ServerScheduleModels.Schedule schedule, TextInputWidget name,
                      ToggleWidget enabled, ToggleWidget timing, DropDownWidget<String> preset, ScheduleDatePicker picker,
                      TextInputWidget advancedCron, DropDownWidget<String> zone, ToggleWidget onlyOnline, List<TaskEditor> editors) {
        if (closed || action) return;
        ServerScheduleModels.Mutation mutation;
        String summary;
        try {
            String scheduleName = name.getText().trim();
            if (scheduleName.isBlank()) throw new IllegalArgumentException("Name Is Required");
            ZoneId selectedZone = ZoneId.of(zone.getSelectedItem().isBlank() ? "UTC" : zone.getSelectedItem().trim());
            boolean recurring = timing.getValue();
            String runAt = recurring ? "" : picker.value().atZone(selectedZone).toInstant().toString();
            if (!recurring && enabled.getValue() && !Instant.parse(runAt).isAfter(Instant.now())) {
                throw new IllegalArgumentException("One Time Schedule Must Be In The Future");
            }
            String cron = recurring ? ScheduleTimingGuide.cron(preset.getSelectedItem(), picker.clock(), picker.weekDay(),
                    picker.monthDay(), picker.month(), advancedCron.getText(), feature.scheduleCapabilities().seconds()) : "";
            if (!feature.scheduleCapabilities().seconds() && cron.split(" ").length == 6) {
                throw new IllegalArgumentException("This Backend Supports Timing To The Minute");
            }
            ServerScheduleModels.Timing selectedTiming = new ServerScheduleModels.Timing(recurring
                    ? ServerScheduleModels.TimingType.CRON : ServerScheduleModels.TimingType.ONE_TIME, runAt, cron, selectedZone.getId());
            mutation = new ServerScheduleModels.Mutation(scheduleName, enabled.getValue(), selectedTiming, onlyOnline.getValue(), capture(editors));
            summary = timingSummary(recurring, preset.getSelectedItem(), picker, advancedCron.getText(), feature.scheduleCapabilities().timeZone() ? selectedZone.getId() : "Panel Time");
        } catch (RuntimeException failure) {
            new Notification("Invalid Schedule", failure.getMessage(), Notification.Type.WARN);
            return;
        }
        action = true;
        String key = operationKey();
        var operation = schedule == null ? feature.createSchedule(mutation, key)
                : feature.updateSchedule(schedule.id(), mutation, schedule.revision(), key);
        operation.whenComplete((updated, failure) -> ScreenManager.getInstance().execute(() -> {
            if (closed) return;
            action = false;
            if (failure == null && updated != null) {
                popup.getWidget().hide();
                upsert(updated);
                new Notification("Schedule Saved", summary + " · Next " + displayTime(updated.nextRunAt()), Notification.Type.SUCCESS);
            } else new Notification("Save Failed", error(failure), Notification.Type.ERROR);
            refresh();
        }));
    }

    private List<ServerScheduleModels.Task> capture(List<TaskEditor> editors) {
        List<ServerScheduleModels.Task> tasks = new ArrayList<>();
        for (int index = 0; index < editors.size(); index++) {
            TaskEditor editor = editors.get(index);
            ServerScheduleModels.Action action = parseAction(editor.action().getSelectedItem());
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
            int keep = action == ServerScheduleModels.Action.BACKUP && feature.scheduleCapabilities().retention()
                    ? retentionNumber(editor.keep.getText(), 1000, "Keep Backups") : 0;
            int age = action == ServerScheduleModels.Action.BACKUP && feature.scheduleCapabilities().retention()
                    ? retentionNumber(editor.age.getText(), 3650, "Maximum Age") : 0;
            tasks.add(new ServerScheduleModels.Task(editor.id(), index + 1, action,
                    action == ServerScheduleModels.Action.COMMAND || action == ServerScheduleModels.Action.BACKUP ? payload : "", delay,
                    editor.continueOnFailure().getValue(), keep, age));
        }
        return tasks;
    }

    private static int retentionNumber(String value, int maximum, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0 || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + " Must Be Between 0 And " + maximum);
        }
    }

    private void run(ServerScheduleModels.Schedule schedule) {
        if (action || schedule.processing() || !running.add(schedule.id())) return;
        feature.runSchedule(schedule.id(), operationKey()).whenComplete((run, failure) -> ScreenManager.getInstance().execute(() -> {
            if (closed) return;
            running.remove(schedule.id());
            boolean failed = failure != null || run != null && "FAILED".equals(run.status());
            String title = failed ? "Run Failed" : run != null && "COMPLETED".equals(run.status()) ? "Schedule Completed" : "Schedule Started";
            String result = failure != null ? error(failure) : failed && run != null ? run.result() : schedule.name();
            new Notification(title, result, failed ? Notification.Type.ERROR : Notification.Type.SUCCESS);
            loadError = "";
            loaded = false;
            refresh();
        }));
    }

    private void confirmDelete(ServerScheduleModels.Schedule schedule) {
        Screen screen = ScreenManager.getInstance().getCurrentScreen();
        if (screen == null || action) return;
        PopupWidget.Builder popup = new PopupWidget.Builder("Delete Schedule").size(360, 150);
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
        PopupWidget widget = ScheduleDatePicker.quiet(popup.build());
        widget.centerOnOwner();
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

    private ToggleWidget toggle(boolean value, String hint) {
        return new ToggleWidget.Builder().toggled(value).size(36, 18).hint(hint).build();
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

    public void cleanup() {
        closed = true;
        generation++;
    }

}
