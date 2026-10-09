package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.network.protocol.NetworkMemberSource;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.util.Notification;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

final class ServerConfigurationDraft {
    private final ServerScreenHost host;
    private final ServerConfigurationTarget target;
    private ServerSettingsDataController settings;
    private final List<Setting> dataSections = new ArrayList<>();
    private Runnable changed = () -> {};
    private Runnable settingsCleanup = () -> {};
    private long revision;
    private boolean loading = true;
    private String failure = "";
    private final ServerScreenHost.ConfigurationUi ui;
    private final List<Setting> sections;
    private final List<Setting> resourceSettings;
    private final Map<String, String> remoteVariables;
    private final ResourcePoolController.PoolView poolView;
    private final ResourcePoolModels.DraftOptions poolOptions;
    private final PoolCreationPreview sharedPreview;
    private boolean closed;

    private ServerConfigurationDraft(ServerScreenHost host, ServerConfigurationTarget target, ServerSettingsDataController settings,
                                     ServerScreenHost.ConfigurationUi ui, List<Setting> sections, List<Setting> resourceSettings,
                                     Map<String, String> remoteVariables, ResourcePoolController.PoolView poolView,
                                     ResourcePoolModels.DraftOptions poolOptions, PoolCreationPreview sharedPreview) {
        this.host = host;
        this.target = target;
        this.settings = settings;
        this.ui = ui;
        this.sections = List.copyOf(sections);
        this.resourceSettings = List.copyOf(resourceSettings);
        this.remoteVariables = remoteVariables;
        this.poolView = poolView;
        this.poolOptions = poolOptions;
        this.sharedPreview = sharedPreview;
        rebuildDataSections();
    }

    static Async<ServerConfigurationDraft> create(ReScreen owner, ServerScreenHost host, Object preset, String name) {
        return create(owner, host, preset, name, null);
    }

    static Async<ServerConfigurationDraft> create(ReScreen owner, ServerScreenHost host, Object preset, String name,
                                                  ServerScreenHost.HostView remoteHost) {
        return create(owner, host, preset, name, remoteHost, null, null);
    }

    static Async<ServerConfigurationDraft> create(ReScreen owner, ServerScreenHost host, Object preset, String name,
                                                  ServerScreenHost.HostView remoteHost, ResourcePoolController.PoolView poolView,
                                                  ResourcePoolModels.DraftOptions poolOptions) {
        return create(owner, host, preset, name, remoteHost, poolView, poolOptions, 1);
    }

    static Async<ServerConfigurationDraft> create(ReScreen owner, ServerScreenHost host, Object preset, String name,
                                                  ServerScreenHost.HostView remoteHost, ResourcePoolController.PoolView poolView,
                                                  ResourcePoolModels.DraftOptions poolOptions, int poolShares) {
        return create(owner, host, preset, name, remoteHost, poolView, poolOptions, poolShares, null);
    }

    static Async<ServerConfigurationDraft> create(ReScreen owner, ServerScreenHost host, Object preset, String name,
                                                  ServerScreenHost.HostView remoteHost, ResourcePoolController.PoolView poolView,
                                                  ResourcePoolModels.DraftOptions poolOptions, int poolShares, PoolCreationPreview preview) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(host, "host");
        if (poolView != null && remoteHost != null) return Async.failed(new IllegalArgumentException("Choose One Server Source"));
        if (preview != null && (poolView == null || !preview.view().pool().id().equals(poolView.pool().id()))) {
            return Async.failed(new IllegalArgumentException("Resource Pool Configuration Changed"));
        }
        if (poolView != null && (poolOptions == null || poolOptions.games().stream()
                .noneMatch(game -> "minecraft:java".equals(game.id()) && !game.profiles().isEmpty()))) {
            return Async.failed(new IllegalStateException("No Minecraft Server Images Are Available"));
        }
        ServerConfigurationTarget target = host.createConfigurationTarget();
        host.configureTargetDefaults(target);
        host.applyTargetPreset(target, preset);
        target.name(name);
        if (remoteHost != null) host.configureRemoteTarget(target, remoteHost, null);
        Async<Void> properties;
        try {
            properties = poolView == null ? Objects.requireNonNull(host.loadInstanceProperties(target.raw(), false)) : Async.completed(null);
        } catch (RuntimeException error) {
            properties = Async.failed(error);
        }
        return host.configurationLoad("Server Configuration", properties, () -> null).thenCompose(ignored -> {
            ServerSettingsDataController settings = poolView == null
                    ? host.createServerSettingsController(target.raw(), ServerSettingsRegistry.getInstance().snapshot(target.raw()))
                    : host.createNewServerSettingsController(target.raw(), ServerSettingsRegistry.getInstance().snapshot(target.raw()));
            Async<Void> loaded;
            try {
                loaded = Objects.requireNonNull(host.configurationLoad("Server Settings", Objects.requireNonNull(settings.load()), () -> null));
            } catch (RuntimeException error) {
                settings.close();
                return Async.failed(error);
            }
            Async<ServerConfigurationDraft> result = Async.pending();
            result.whenComplete((draft, error) -> {
                if (error != null) settings.close();
            });
            ScreenManager.getInstance().execute(() -> {
                if (result.isDone()) {
                    settings.close();
                    return;
                }
                ServerConfigurationDraft draft = null;
                try {
                    draft = build(owner, host, target, settings, remoteHost, poolView, poolOptions, poolShares, preview);
                    draft.observeSettings(loaded);
                    if (!result.complete(draft)) draft.close();
                } catch (RuntimeException error) {
                    if (draft == null) settings.close();
                    else draft.close();
                    result.completeExceptionally(error);
                }
            });
            return result;
        });
    }

    private static ServerConfigurationDraft build(ReScreen owner, ServerScreenHost host, ServerConfigurationTarget target,
                                                  ServerSettingsDataController settings, ServerScreenHost.HostView remoteHost,
                                                  ResourcePoolController.PoolView poolView, ResourcePoolModels.DraftOptions poolOptions,
                                                  int poolShares, PoolCreationPreview preview) {
        ServerScreenHost.ConfigurationUi ui = null;
        try {
            boolean hosted = poolView != null;
            Map<String, String> remoteVariables = new LinkedHashMap<>();
            if (hosted) {
                remoteVariables.put("SOFTWARE", String.valueOf(target.modLoader()).toUpperCase(Locale.ROOT));
                remoteVariables.put("VERSION", "latest");
                remoteVariables.put("BUILD", "latest");
            }
            ServerScreenHost.ConfigurationState state = new ServerScreenHost.ConfigurationState(null, target.raw(), remoteHost,
                    false, false, hosted, "", "", hosted, poolOptions, poolView, poolShares, preview);
            ServerConfigurationDraft[] draft = new ServerConfigurationDraft[1];
            ui = host.createConfigurationUi(owner, state, settings, remoteVariables, List.of(),
                () -> { if (draft[0] != null) draft[0].reload(host); },
                () -> draft[0] == null || draft[0].allowSoftwareChange());
            List<Setting> sections = new ArrayList<>();
            add(ui.settings(), "General", sections);
            add(ui.settings(), "Features", sections);
            add(ui.settings(), "Java", sections);
            if (hosted) add(ui.settings(), "Software Settings", sections);
            List<Setting> resources = new ArrayList<>();
            add(ui.settings(), "Resources", resources);
            draft[0] = new ServerConfigurationDraft(host, target, settings, ui, sections, resources, remoteVariables, poolView, poolOptions, preview);
            ui.startupLoaded().run();
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
        result.addAll(dataSections);
        return result;
    }

    List<Setting> resourceSettings() {
        return resourceSettings;
    }

    void onChanged(Runnable listener) {
        changed = listener;
    }

    boolean ready() {
        return !closed && !loading && failure.isBlank();
    }

    boolean hasPendingChanges() {
        return sections.stream().anyMatch(Setting::hasPendingChanges)
            || resourceSettings.stream().anyMatch(Setting::hasPendingChanges)
            || dataSections.stream().anyMatch(Setting::hasPendingChanges);
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

    private void observeSettings(Async<Void> loaded) {
        long request = ++revision;
        ServerSettingsDataController controller = settings;
        loading = true;
        failure = "";
        settingsCleanup = controller.onTabsPublished(tabs -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != revision || settings != controller) return;
            rebuildDataSections();
            changed.run();
        }));
        loaded.whenComplete((ignored, error) -> ScreenManager.getInstance().execute(() -> {
            if (closed || request != revision || settings != controller) return;
            loading = false;
            if (error != null) {
                failure = loadFailure(error);
                new Notification("Server Configuration", failure, Notification.Type.ERROR);
            }
            rebuildDataSections();
            changed.run();
        }));
    }

    void reload(ServerScreenHost host) {
        if (!allowSoftwareChange()) return;
        ServerSettingsDataController next;
        try {
            next = hosted() ? host.createNewServerSettingsController(target.raw(), ServerSettingsRegistry.getInstance().snapshot(target.raw()))
                    : host.createServerSettingsController(target.raw(), ServerSettingsRegistry.getInstance().snapshot(target.raw()));
        } catch (RuntimeException error) {
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
        ServerSettingsDataController previous = settings;
        settingsCleanup.run();
        settings = next;
        rebuildDataSections();
        observeSettings(loaded);
        previous.close();
        changed.run();
    }

    private String loadFailure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String detail = cause.getMessage();
        return detail == null || detail.isBlank() ? "Server Settings Could Not Load" : "Server Settings Could Not Load: " + detail;
    }

    String location() {
        return hosted() ? "" : ui.localLocation().get();
    }

    ServerSettingsDataController settings() {
        return settings;
    }

    boolean hosted() {
        return poolView != null;
    }

    NetworkMemberSource.Draft hostedSource(ResourcePoolController.Creation creation) {
        if (!hosted() || !ready() || creation == null || !poolView.pool().id().equals(creation.poolId())) {
            throw new IllegalStateException("Resource Pool Configuration Is Unavailable");
        }
        ServerScreenHost.PoolResources resources = ui.poolResources().get();
        long installerRam = number(resources.installerRamMiB());
        long installerCpu = number(resources.installerCpuPercent());
        long runtimeRam = number(resources.runtimeRamMiB());
        long runtimeCpu = number(resources.runtimeCpuPercent());
        long disk = number(resources.diskMiB());
        if (installerRam < Math.max(1, poolOptions.minimumInstallerRamMiB())
                || installerCpu < Math.max(1, poolOptions.minimumInstallerCpuPercent())
                || runtimeRam <= 0 || runtimeCpu <= 0 || disk <= 0) {
            throw new IllegalArgumentException("Choose RAM, CPU, And Disk With Enough Capacity To Set Up The Server");
        }
        ResourcePoolModels.Resources available = (sharedPreview == null ? poolView : sharedPreview.view()).pool().balance().available();
        if (BigInteger.valueOf(installerRam).compareTo(new BigInteger(available.ramMiB())) > 0
                || BigInteger.valueOf(installerCpu).compareTo(new BigInteger(available.cpuQuotaPercent())) > 0
                || BigInteger.valueOf(runtimeRam).compareTo(new BigInteger(available.ramMiB())) > 0
                || BigInteger.valueOf(runtimeCpu).compareTo(new BigInteger(available.cpuQuotaPercent())) > 0
                || BigInteger.valueOf(disk).compareTo(new BigInteger(available.diskMiB())) > 0) {
            throw new IllegalArgumentException("This Resource Pool Does Not Have Enough Available Capacity");
        }
        Map<String, String> initialFiles = ServerCreationFiles.initialFiles(target, settings,
                host.application().getGameUUID(), host.application().getGameUserName());
        NetworkMemberSource.DraftMetadata metadata = new NetworkMemberSource.DraftMetadata(target.name(), resources.gameId(),
                resources.profileId(), new LinkedHashMap<>(remoteVariables), initialFiles);
        return new NetworkMemberSource.Draft(creation.poolId().toString(), creation.draftId().toString(),
                creation.createRequestId().toString(), creation.activationRequestId().toString(), 1, metadata,
                new NetworkMemberSource.Compute(installerRam, installerCpu), new NetworkMemberSource.Compute(runtimeRam, runtimeCpu),
                new NetworkMemberSource.Storage(disk, 0));
    }

    private long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Set Valid Resource Amounts Before Creating The Network", failure);
        }
    }

    void apply() {
        if (!ready()) throw new IllegalStateException(failure.isBlank() ? "Wait For Server Settings To Load" : failure);
        List<Setting> values = sections();
        values.addAll(resourceSettings);
        for (Setting setting : values) {
            String error = setting.validateChanges();
            if (error != null && !error.isBlank()) throw new IllegalArgumentException(error);
        }
        values.forEach(Setting::applyChanges);
    }

    void close() {
        if (closed) return;
        closed = true;
        revision++;
        changed = () -> {};
        settingsCleanup.run();
        ui.cleanup().run();
        settings.close();
    }
}
