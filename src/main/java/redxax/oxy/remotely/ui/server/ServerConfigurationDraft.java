package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class ServerConfigurationDraft {
    private final ServerConfigurationTarget target;
    private ServerSettingsDataController settings;
    private final List<Setting> dataSections = new ArrayList<>();
    private Runnable changed = () -> {};
    private long revision;
    private boolean loading;
    private String failure = "";
    private final ServerScreenHost.ConfigurationUi ui;
    private final List<Setting> sections;
    private boolean closed;

    private ServerConfigurationDraft(ServerConfigurationTarget target, ServerSettingsDataController settings,
                                     ServerScreenHost.ConfigurationUi ui, List<Setting> sections) {
        this.target = target;
        this.settings = settings;
        this.ui = ui;
        this.sections = List.copyOf(sections);
        rebuildDataSections();
    }

    static Async<ServerConfigurationDraft> create(ReScreen owner, ServerScreenHost host, Object preset, String name) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(host, "host");
        ServerConfigurationTarget target = host.createConfigurationTarget();
        host.configureTargetDefaults(target);
        host.applyTargetPreset(target, preset);
        target.name(name);
        Async<Void> properties;
        try {
            properties = Objects.requireNonNull(host.loadInstanceProperties(target.raw(), false));
        } catch (RuntimeException error) {
            properties = Async.failed(error);
        }
        return host.configurationLoad("Server Configuration", properties, () -> null).thenCompose(ignored -> {
            ServerSettingsDataController settings = host.createServerSettingsController(target.raw(), ServerSettingsRegistry.getInstance().snapshot(target.raw()));
            Async<Void> loaded;
            try {
                loaded = Objects.requireNonNull(settings.load());
            } catch (RuntimeException error) {
                settings.close();
                return Async.failed(error);
            }
            return host.configurationLoad("Server Settings", loaded, () -> null).thenCompose(value -> {
                Async<ServerConfigurationDraft> result = Async.pending();
                ScreenManager.getInstance().execute(() -> {
                    try {
                        result.complete(build(owner, host, target, settings));
                    } catch (RuntimeException error) {
                        result.completeExceptionally(error);
                    }
                });
                return result;
            })
                .whenComplete((draft, error) -> {
                    if (error != null) settings.close();
                });
        });
    }

    private static ServerConfigurationDraft build(ReScreen owner, ServerScreenHost host, ServerConfigurationTarget target,
                                                  ServerSettingsDataController settings) {
        ServerScreenHost.ConfigurationUi ui = null;
        try {
            ServerScreenHost.ConfigurationState state = new ServerScreenHost.ConfigurationState(null, target.raw(), null, false, false, false, "", "");
            ServerConfigurationDraft[] draft = new ServerConfigurationDraft[1];
            ui = host.createConfigurationUi(owner, state, settings, new LinkedHashMap<>(), List.of(),
                () -> { if (draft[0] != null) draft[0].reload(host); },
                () -> draft[0] == null || draft[0].allowSoftwareChange());
            List<Setting> sections = new ArrayList<>();
            add(ui.settings(), "General", sections);
            add(ui.settings(), "Server Software", sections);
            add(ui.settings(), "Features", sections);
            add(ui.settings(), "Java", sections);
            draft[0] = new ServerConfigurationDraft(target, settings, ui, sections);
            return draft[0];
        } catch (RuntimeException error) {
            if (ui != null) ui.cleanup().run();
            throw error;
        }
    }

    private static void add(Map<String, Supplier<List<Setting>>> settings, String name, List<Setting> destination) {
        Supplier<List<Setting>> supplier = settings.get(name);
        if (supplier == null) return;
        List<Setting> values = supplier.get();
        if (values != null) values.stream().filter(Objects::nonNull).forEach(destination::add);
    }

    ServerConfigurationTarget target() {
        return target;
    }

    List<Setting> sections() {
        List<Setting> result = new ArrayList<>(sections);
        if (!loading) result.addAll(dataSections);
        return result;
    }

    void onChanged(Runnable listener) {
        changed = listener;
    }

    boolean ready() {
        return !closed && !loading && failure.isBlank();
    }

    String failure() {
        return failure;
    }

    private boolean allowSoftwareChange() {
        if (closed || loading) return false;
        if (dataSections.stream().noneMatch(Setting::hasPendingChanges)) return true;
        new Notification("Unsaved Server Settings", "Reset Changed Server Settings Before Switching Software", Notification.Type.WARN);
        return false;
    }

    private void rebuildDataSections() {
        dataSections.clear();
        for (String tab : settings.tabNames()) dataSections.addAll(settings.settings(tab));
    }

    void reload(ServerScreenHost host) {
        if (closed) return;
        long request = ++revision;
        loading = true;
        failure = "";
        changed.run();
        ServerSettingsDataController next;
        try {
            next = host.createServerSettingsController(target.raw(), ServerSettingsRegistry.getInstance().snapshot(target.raw()));
        } catch (RuntimeException error) {
            loading = false;
            failure = loadFailure(error);
            changed.run();
            return;
        }
        Async<Void> loaded;
        try {
            loaded = next.load();
        } catch (RuntimeException error) {
            loaded = Async.failed(error);
        }
        loaded.whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != revision) {
                next.close();
                return;
            }
            loading = false;
            if (error != null) {
                next.close();
                failure = loadFailure(error);
                new Notification("Server Configuration", failure, Notification.Type.ERROR);
            } else {
                ServerSettingsDataController previous = settings;
                settings = next;
                rebuildDataSections();
                previous.close();
            }
            changed.run();
        }));
    }

    private String loadFailure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String detail = cause.getMessage();
        return detail == null || detail.isBlank() ? "Server Settings Could Not Load" : "Server Settings Could Not Load: " + detail;
    }

    String location() {
        return ui.localLocation().get();
    }

    ServerSettingsDataController settings() {
        return settings;
    }

    void apply() {
        if (!ready()) throw new IllegalStateException(failure.isBlank() ? "Wait For Server Settings To Load" : failure);
        sections().forEach(Setting::applyChanges);
    }

    void close() {
        if (closed) return;
        closed = true;
        revision++;
        changed = () -> {};
        ui.cleanup().run();
        settings.close();
    }
}
