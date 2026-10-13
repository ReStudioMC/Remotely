package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rebase.backend.feature.ServerSettingsFeature;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class ProviderSettingsController implements AutoCloseable {
    private final ServerSettingsFeature provider;
    private final Runnable changed;
    private final Map<String, Control> controls = new LinkedHashMap<>();
    private ServerSettingsFeature.Snapshot snapshot;
    private List<Setting> settings;
    private long generation;
    private boolean closed;
    private Async<Void> loading;

    public ProviderSettingsController(ServerSettingsFeature provider, Runnable changed) {
        this.provider = provider;
        this.changed = changed;
        status(provider == null ? "Panel Settings Are Unavailable In This Session" : "Loading Panel Settings");
    }

    public List<Setting> settings() {
        return settings;
    }

    public Async<Void> load() {
        if (closed) return Async.failed(new Async.Cancellation());
        if (provider == null) return Async.completed(null);
        if (loading != null && !loading.isDone()) return loading;
        long current = ++generation;
        snapshot = null;
        controls.clear();
        status("Loading Panel Settings");
        changed.run();
        Async<Void> settled = Async.pending();
        loading = settled;
        provider.load().whenComplete((result, failure) -> ScreenManager.getInstance().execute(() -> {
            if (closed || current != generation) {
                settled.fail(new Async.Cancellation());
                return;
            }
            if (failure == null) admit(result);
            else {
                status(message(failure));
                changed.run();
            }
            settled.complete(null);
        }));
        return settled;
    }

    public Async<Void> save() {
        if (closed) return Async.failed(new Async.Cancellation());
        if (provider == null || snapshot == null) return Async.failed(new IllegalStateException("Load Panel Settings Before Saving"));
        Map<String, String> changes = changes();
        if (changes.isEmpty()) return Async.completed(null);
        long current = ++generation;
        Async<Void> settled = Async.pending();
        provider.save(snapshot, changes).whenComplete((result, failure) -> ScreenManager.getInstance().execute(() -> {
            if (failure != null) settled.fail(failure);
            else {
                if (!closed && current == generation) admit(result);
                settled.complete(null);
            }
        }));
        return settled;
    }

    private Map<String, String> changes() {
        Map<String, String> changes = new LinkedHashMap<>();
        controls.forEach((key, control) -> {
            ServerSettingsFeature.Field field = control.field();
            String next = control.value().get();
            if (field != null && field.editable() && !Objects.equals(field.value(), next)) changes.put(key, next);
        });
        return changes;
    }

    private void admit(ServerSettingsFeature.Snapshot result) {
        snapshot = result;
        controls.clear();
        Map<String, Setting> groups = new LinkedHashMap<>();
        for (ServerSettingsFeature.Field field : result.fields()) {
            AnimatedWidget input;
            Supplier<String> value;
            Runnable reset;
            if (!field.secret() && field.kind() == ServerSettingsFeature.Kind.BOOLEAN) {
                ToggleWidget toggle = new ToggleWidget.Builder().toggled(Boolean.parseBoolean(field.value())).build();
                input = toggle;
                value = () -> Boolean.toString(toggle.getValue());
                reset = () -> toggle.setValue(Boolean.parseBoolean(field.value()));
            } else if (!field.secret() && field.kind() == ServerSettingsFeature.Kind.CHOICE) {
                List<ServerSettingsFeature.Choice> choices = new ArrayList<>(field.choices());
                ServerSettingsFeature.Choice selected = choices.stream().filter(choice -> choice.value().equals(field.value())).findFirst().orElse(null);
                if (selected == null) {
                    selected = new ServerSettingsFeature.Choice(field.value().isBlank() ? "Not Set" : field.value(), field.value());
                    choices.addFirst(selected);
                }
                DropDownWidget<ServerSettingsFeature.Choice> dropdown = new DropDownWidget.Builder<>(choices)
                        .displayFunction(ServerSettingsFeature.Choice::name).selectedItem(selected).build();
                input = dropdown;
                value = () -> dropdown.getSelectedItem().value();
                ServerSettingsFeature.Choice initial = selected;
                reset = () -> dropdown.setSelectedItem(initial);
            } else {
                TextInputWidget text = new TextInputWidget.Builder().text(field.value()).password(field.secret()).build();
                input = text;
                value = text::getText;
                reset = () -> text.setText(field.value());
            }
            input.setActive(field.editable());
            controls.put(field.key(), new Control(field, value, reset));
            String description = field.description() + (field.editable() ? "" : " • Read Only");
            MountableButtonWidget row = new MountableButtonWidget.Builder(field.name()).description(description).addWidget(input).build();
            Setting group = groups.computeIfAbsent(field.group(), name -> new Setting.Builder(name).build());
            group.addRow(new PopupWidget.PopupRow.Builder("", row).id(field.key()).build());
        }
        boolean first = true;
        for (Map.Entry<String, Setting> group : groups.entrySet()) {
            boolean saves = first;
            first = false;
            group.getValue().setChanges(new Setting.Changes() {
                @Override public boolean hasPending() {
                    return changes().keySet().stream().anyMatch(key -> snapshot.field(key).group().equals(group.getKey()));
                }
                @Override public Async<Void> prepare() { return saves ? save() : Async.completed(null); }
                @Override public void cancel() { controls.values().forEach(control -> control.reset().run()); }
            });
        }
        Setting reload = new Setting.Builder("Panel").build();
        reload.addRow("", new MountableButtonWidget.Builder("Reload Panel Settings")
                .description("Reloads Current Values And Discards Unsaved Panel Changes").onClick(() -> load()).build());
        List<Setting> next = new ArrayList<>(groups.values());
        next.add(reload);
        settings = List.copyOf(next);
        changed.run();
    }

    private void status(String description) {
        Setting status = new Setting.Builder("Panel Settings").build();
        status.addRow("", new MountableButtonWidget.Builder("Panel Settings").description(description).build());
        if (provider != null) status.addRow("", new MountableButtonWidget.Builder("Reload").onClick(() -> load()).build());
        settings = List.of(status);
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? "Panel Settings Could Not Be Loaded" : cause.getMessage();
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        snapshot = null;
        controls.clear();
    }

    private record Control(ServerSettingsFeature.Field field, Supplier<String> value, Runnable reset) {}
}
