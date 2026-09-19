package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.discord.DiscordRpcBridge;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rescreen.config.Config;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.screens.DesktopWindowsOverlay;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import restudio.rescreen.platform.Async;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static restudio.rescreen.util.SoundUtils.playSound;

@SuppressWarnings("unchecked")
public class ServerConfigurationScreen extends ReScreen {
    private ServerScreenHost screenHost() {
        return remotelyClient == null ? ServerScreenHost.of(null) : remotelyClient.getHost().serverScreenHost(remotelyClient);
    }

    private RemotelyServerApi serverApi() {
        return remotelyClient == null ? null : remotelyClient.getApiClient();
    }

    private final Screen parent;
    private final boolean isEditMode;
    private final ServerConfigurationTarget originalInstance;
    private final ServerConfigurationTarget tempInstance;
    private final ServerScreenHost.HostView remoteHostContext;
    private final RemotelyClient remotelyClient;
    private final boolean isReStudioCreation;
    private final String preselectedPlanName;
    private final Consumer<Object> creationInitializer;
    private final Consumer<Object> creationCallback;

    private final Map<String, String> remoteVariables = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> originalRemoteVariables = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<String> extraFiles = new ArrayList<>();
    private final Set<String> pendingPublishedTabs = new LinkedHashSet<>();
    private String remoteStartupRevision = "";
    private final boolean isReStudioBackend;
    private String serverIdentifier;
    private ServerSettingsDataController settingsController;
    private SettingsScreen settingsScreen;
    private Map<String, Supplier<List<Setting>>> fixedSettingsSuppliers = Map.of();
    private ServerScreenHost.ConfigurationUi configurationUi;
    private Consumer<ServerSettingsSnapshot> settingsRegistryListener;
    private final BrowserSafeState.LongValue settingsReloadRevision = new BrowserSafeState.LongValue();
    private final BrowserSafeState.LongValue configurationLoadRevision = new BrowserSafeState.LongValue();
    private ServerSettingsDataController pendingSettingsController;
    private Runnable settingsCleanup = () -> {};
    private boolean settingsHandoff;
    private boolean settingsControllerRetained;
    private boolean settingsControllerClosePending;
    private volatile boolean screenClosed;
    private volatile boolean creationInFlight;

    private static final Set<String> REINSTALL_TRIGGERING_VARS = Set.of(
        "VERSION", "SOFTWARE", "BUILD"
    );

    public <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient) {
        this(parent, instance, remoteHostContext, remotelyClient, false);
    }

    public <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient, boolean isReStudioCreation) {
        this(parent, instance, remoteHostContext, remotelyClient, isReStudioCreation, null);
    }

    public <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient, boolean isReStudioCreation, String preselectedPlanName) {
        this(parent, instance, remoteHostContext, remotelyClient, isReStudioCreation, preselectedPlanName, null, null, null);
    }

    public ServerConfigurationScreen(Screen parent, RemotelyClient remotelyClient, boolean isReStudioCreation, Object preset) {
        this(parent, null, null, remotelyClient, isReStudioCreation, null, preset, null, null);
    }

    public <T> ServerConfigurationScreen(Screen parent, Object remoteHostContext, RemotelyClient remotelyClient, Object preset, Consumer<T> creationCallback) {
        this(parent, null, remoteHostContext, remotelyClient, false, null, preset, null, creationCallback);
    }

    public <T> ServerConfigurationScreen(Screen parent, Object remoteHostContext, RemotelyClient remotelyClient, Object preset, Consumer<T> creationInitializer, Consumer<T> creationCallback) {
        this(parent, null, remoteHostContext, remotelyClient, false, null, preset, creationInitializer, creationCallback);
    }

    private <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient, boolean isReStudioCreation, String preselectedPlanName, Object preset, Consumer<T> creationInitializer, Consumer<T> creationCallback) {
        super();
        this.parent = parent;
        this.isEditMode = instance != null;
        this.remotelyClient = remotelyClient;
        ServerScreenHost host = remotelyClient == null ? ServerScreenHost.of(null) : remotelyClient.getHost().serverScreenHost(remotelyClient);
        this.originalInstance = instance == null ? null : host.configurationTarget(instance);
        this.remoteHostContext = host.hostView(remoteHostContext);
        this.isReStudioCreation = isReStudioCreation;
        this.preselectedPlanName = preselectedPlanName;
        this.creationInitializer = creationInitializer == null ? null : value -> creationInitializer.accept((T) value);
        this.creationCallback = creationCallback == null ? null : value -> creationCallback.accept((T) value);

        if (isEditMode) {
            this.tempInstance = host.copyConfigurationTarget(instance, originalInstance.name());
            boolean isRemote = tempInstance.remote();
            this.isReStudioBackend = tempInstance.restudio();
            this.serverIdentifier = isReStudioBackend ? tempInstance.backendCredentials().get("identifier") : null;

            if (isRemote || this.remoteHostContext != null) {
                if (this.remoteHostContext != null) {
                    host.configureRemoteTarget(tempInstance, this.remoteHostContext, originalInstance);
                }
            }
        } else {
            this.tempInstance = host.createConfigurationTarget();
            host.configureTargetDefaults(this.tempInstance);
            host.applyTargetPreset(this.tempInstance, preset);
            this.isReStudioBackend = false;
            if (this.remoteHostContext != null) {
                host.configureRemoteTarget(this.tempInstance, this.remoteHostContext, null);
            }
        }
    }

    public String getDesktopAppId() {
        return "server-configuration";
    }

    public String getDesktopAppTitle() {
        return "Server Configuration";
    }

    public String getDesktopAppIconPath() {
        return "change.png";
    }

    @Override
    public void init() {
        super.init();
        header().addRight("close.png", this::close, "Back").build();
        if (isEditMode) {
            DiscordRpcBridge.setServerSettingsActive(originalInstance.id());
        } else {
            DiscordRpcBridge.setServerCreationActive();
        }

        if (!isEditMode && creationInitializer != null) {
            creationInitializer.accept(tempInstance.raw());
        }
        ServerSettingsDataController controller = screenHost().createServerSettingsController(tempInstance.raw(),
                ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw()));
        setupSettingsUI(extraFiles, controller);
        startInitialConfigLoad();
    }

    private static String configurationFailureMessage(Throwable failure) {
        String message = null;
        Throwable current = failure;
        while (current != null) {
            String currentMessage = current.getMessage();
            if (currentMessage != null && !currentMessage.isBlank()) {
                message = currentMessage;
            }
            current = current.getCause();
        }
        return message == null ? "Configuration Is Unavailable" : message;
    }

    private void startInitialConfigLoad() {
        long revision = configurationLoadRevision.incrementAndGet();
        ServerScreenHost host = screenHost();
        boolean remote = tempInstance.remote();
        if (isReStudioCreation) {
            remoteVariables.put("SOFTWARE", "PAPER");
            remoteVariables.put("VERSION", "latest");
            remoteVariables.put("BUILD", "latest");
        }

        observeConfigurationLoad(revision, "Server Properties",
                host.configurationLoad("Server Properties", host.loadInstanceProperties(tempInstance.raw(), isEditMode && remote), () -> null),
                fixedSettingsSuppliers.keySet());
        if (!isEditMode) return;

        observeConfigurationLoad(revision, "Server Settings",
                host.configurationLoad("Server Settings", host.reloadInstanceSettings(tempInstance.raw(), remote), () -> null),
                fixedSettingsSuppliers.keySet());
        observeConfigurationLoad(revision, "Modpack Settings",
                host.configurationLoad("Modpack Settings", host.loadInstanceModpack(tempInstance.raw()), () -> null),
                Set.of("Server Software", "General"));
        host.configurationLoad("Server Files", host.listInstanceFiles(tempInstance.raw()), List::of)
                .whenComplete((files, failure) -> ScreenManager.getInstance().execute(() -> {
                    if (!acceptConfigurationLoad(revision, "Server Files", failure)) return;
                    extraFiles.clear();
                    if (files != null) extraFiles.addAll(files);
                    refreshConfigurationCategories(Set.of("Extra Files"));
                }));

        if (isReStudioBackend) {
            RemotelyServerApi api = serverApi();
            Async<Void> startup = api == null ? Async.completed(null) : api.getServerStartupConfig(serverIdentifier).thenAccept(data -> {
                if (data == null || data.revision().isBlank()) throw new IllegalStateException("Startup Settings Revision Is Unavailable");
                remoteStartupRevision = data.revision();
                originalRemoteVariables.putAll(data.values());
                data.values().forEach(remoteVariables::putIfAbsent);
            });
            observeConfigurationLoad(revision, "Startup Configuration",
                    host.configurationLoad("Startup Configuration", startup, () -> null),
                    Set.of("Server Software", "Software Settings", "Java"));
        }

        settingsController.load().whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!acceptConfigurationLoad(revision, "Server Settings", failure)) return;
            refreshConfigurationCategories(Set.of("Extra Files"));
        }));
    }

    private void observeConfigurationLoad(long revision, String operation, Async<?> load, Collection<String> categories) {
        load.whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!acceptConfigurationLoad(revision, operation, failure)) return;
            refreshConfigurationCategories(categories);
        }));
    }

    private boolean acceptConfigurationLoad(long revision, String operation, Throwable failure) {
        if (screenClosed || revision != configurationLoadRevision.get()) return false;
        if (failure == null) return true;
        ReLog.logger(LogTypes.CONFIGURATION).source(LogSource.instance(tempInstance.id(), tempInstance.name()))
                .component(ServerConfigurationScreen.class).operation("Load " + operation)
                .error("Could not load " + operation.toLowerCase(Locale.ROOT), failure);
        new Notification("Server Configuration Partially Unavailable",
                operation + " Could Not Be Loaded. Available Settings Remain Open.", Notification.Type.WARN);
        return false;
    }

    private void refreshConfigurationCategories(Collection<String> categories) {
        if (settingsScreen == null) return;
        for (String category : categories) {
            if (category == null) continue;
            if (settingsScreen.hasPendingChanges(category)) {
                pendingPublishedTabs.add(category);
                continue;
            }
            Supplier<List<Setting>> fixed = fixedSettingsSuppliers.get(category);
            boolean dataDriven = settingsController != null && settingsController.tabNames().contains(category);
            if (fixed != null || dataDriven) {
                settingsScreen.registerCategory(category, dataDriven
                        ? combinedSupplier(fixed, () -> settingsController.settings(category)) : fixed);
            }
        }
    }

    private void setupSettingsUI(List<String> extraFiles, ServerSettingsDataController settingsController) {
        this.settingsController = settingsController;
        ServerScreenHost.ConfigurationState state = new ServerScreenHost.ConfigurationState(
                originalInstance == null ? null : originalInstance.raw(), tempInstance.raw(), remoteHostContext,
                isEditMode, isReStudioBackend, isReStudioCreation, serverIdentifier, preselectedPlanName);
        configurationUi = screenHost().createConfigurationUi(this, state, settingsController, remoteVariables, extraFiles,
                this::reloadDataDrivenSettings, () -> allowServerSoftwareChange(null));
        Map<String, Supplier<List<Setting>>> settingsByTab = new LinkedHashMap<>(configurationUi.settings());
        List<Runnable> cleanupActions = new ArrayList<>();
        cleanupActions.add(() -> screenClosed = true);

        fixedSettingsSuppliers = new LinkedHashMap<>(settingsByTab);
        mergeDataDrivenTabs(settingsByTab);

        settingsRegistryListener = ignored -> reloadDataDrivenSettings();
        ServerSettingsRegistry.getInstance().addListener(settingsRegistryListener);
        cleanupActions.add(() -> {
            configurationLoadRevision.incrementAndGet();
            settingsReloadRevision.incrementAndGet();
            ServerSettingsRegistry.getInstance().removeListener(settingsRegistryListener);
            if (configurationUi != null) {
                configurationUi.cleanup();
            }
            closeSettingsControllers();
        });

        BrowserSafeState.BooleanValue cleanupRun = new BrowserSafeState.BooleanValue();
        Runnable combinedCleanup = () -> {
            if (cleanupRun.compareAndSet(false, true)) {
                cleanupActions.forEach(Runnable::run);
            }
        };
        settingsCleanup = combinedCleanup;

        settingsScreen = new SettingsScreen(parent, configurationUi.title(), settingsByTab, this::saveConfiguration, combinedCleanup) {
            @Override
            protected int getEntryMaxWidth() {
                return 760;
            }

            @Override
            public void removed() {
                settingsCleanup.run();
                super.removed();
            }
        };
        ServerSettingsDataController publishedController = settingsController;
        Runnable publicationCleanup = publishedController.onTabsPublished(tabs -> ScreenManager.getInstance().execute(() -> {
            if (screenClosed || this.settingsController != publishedController) return;
            for (String tab : tabs) {
                if (settingsScreen.hasPendingChanges(tab)) {
                    pendingPublishedTabs.add(tab);
                } else {
                    refreshConfigurationCategories(Set.of(tab));
                }
            }
        }));
        cleanupActions.add(publicationCleanup);
        if (Config.desktopMode) {
            DesktopWindowsOverlay overlay = ScreenManager.getInstance().getDesktopWindowsOverlay();
            if (overlay != null) {
                for (ScreenWindowWidget window : overlay.getWindows()) {
                    if (window.getScreen() == this) {
                        settingsHandoff = true;
                        window.replaceScreen(settingsScreen);
                        overlay.bringToFront(window);
                        return;
                    }
                }
            }
        }
        settingsHandoff = true;
        screenHost().application().setScreen(settingsScreen);
    }

    private void mergeDataDrivenTabs(Map<String, Supplier<List<Setting>>> settingsByTab) {
        Supplier<List<Setting>> extraFiles = settingsByTab.remove("Extra Files");
        for (String tab : settingsController.plannedTabNames()) {
            Supplier<List<Setting>> fixed = settingsByTab.get(tab);
            settingsByTab.put(tab, combinedSupplier(fixed, () -> settingsController.settings(tab)));
        }
        Supplier<List<Setting>> softwareSettings = settingsByTab.remove("Software Settings");
        if (softwareSettings != null) {
            LinkedHashMap<String, Supplier<List<Setting>>> ordered = new LinkedHashMap<>();
            Iterator<Map.Entry<String, Supplier<List<Setting>>>> entries = settingsByTab.entrySet().iterator();
            if (entries.hasNext()) {
                Map.Entry<String, Supplier<List<Setting>>> first = entries.next();
                ordered.put(first.getKey(), first.getValue());
            }
            ordered.put("Software Settings", softwareSettings);
            entries.forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue()));
            settingsByTab.clear();
            settingsByTab.putAll(ordered);
        }
        if (extraFiles != null) {
            settingsByTab.put("Extra Files", extraFiles);
        }
    }

    private Supplier<List<Setting>> combinedSupplier(Supplier<List<Setting>> first, Supplier<List<Setting>> second) {
        if (first == null) {
            return second;
        }
        return () -> {
            List<Setting> settings = new ArrayList<>(first.get());
            settings.addAll(second.get());
            return settings;
        };
    }

    private void reloadDataDrivenSettings() {
        if (screenClosed) {
            return;
        }
        long revision = settingsReloadRevision.incrementAndGet();
        ServerSettingsDataController next = screenHost().createServerSettingsController(tempInstance.raw(),
                ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw()));
        next.load().thenRun(() -> ScreenManager.getInstance().execute(() -> applyDataDrivenReload(revision, next))).exceptionally(error -> {
            next.close();
            ReLog.logger(LogTypes.CONFIGURATION).source(LogSource.instance(tempInstance.id(), tempInstance.name())).component(ServerConfigurationScreen.class).operation("Reload Server Settings").error("Could not reload server settings metadata", error);
            return null;
        });
    }

    private boolean allowServerSoftwareChange(String ignored) {
        ServerSettingsDataController controller = settingsController;
        if (screenClosed || settingsScreen == null || controller == null
                || controller.tabNames().stream().noneMatch(settingsScreen::hasPendingChanges)) {
            return true;
        }
        new Notification("Unsaved Server Settings", "Save Or Discard Configuration Changes Before Switching Software.", Notification.Type.WARN);
        return false;
    }

    private void applyDataDrivenReload(long revision, ServerSettingsDataController next) {
        if (screenClosed || revision != settingsReloadRevision.get() || settingsScreen == null || settingsController == null) {
            next.close();
            return;
        }
        Set<String> affectedTabs = new LinkedHashSet<>(settingsController.tabNames());
        affectedTabs.addAll(next.tabNames());
        affectedTabs.add("Extra Files");
        if (affectedTabs.stream().anyMatch(settingsScreen::hasPendingChanges)) {
            if (pendingSettingsController != null) {
                pendingSettingsController.close();
            }
            pendingSettingsController = next;
            return;
        }

        ServerSettingsDataController previous = settingsController;
        settingsController = next;
        for (String tab : affectedTabs) {
            Supplier<List<Setting>> fixed = fixedSettingsSuppliers.get(tab);
            boolean dataDriven = next.tabNames().contains(tab);
            if (fixed == null && !dataDriven) {
                settingsScreen.removeCategory(tab);
            } else {
                Supplier<List<Setting>> supplier = dataDriven ? combinedSupplier(fixed, () -> settingsController.settings(tab)) : fixed;
                settingsScreen.registerCategory(tab, supplier);
            }
        }
        previous.close();
    }

    private void applyPendingDataDrivenReload() {
        if (!pendingPublishedTabs.isEmpty()) {
            Set<String> tabs = Set.copyOf(pendingPublishedTabs);
            pendingPublishedTabs.clear();
            refreshConfigurationCategories(tabs);
        }
        ServerSettingsDataController pending = pendingSettingsController;
        if (pending == null) {
            return;
        }
        pendingSettingsController = null;
        applyDataDrivenReload(settingsReloadRevision.get(), pending);
    }

    private void saveConfiguration() {
        if (isReStudioCreation) {
            createReStudioServer();
        } else if (isEditMode) {
            editServer();
        } else {
            if (creationInFlight) {
                return;
            }
            creationInFlight = true;
            if (remoteHostContext != null) {
                createNewRemoteServer();
            } else {
                createNewLocalServer();
            }
        }
        DiscordRpcBridge.refreshTrackedInstances();
        DiscordRpcBridge.reloadSettings();
    }

    private void createReStudioServer() {
        if (configurationUi == null) return;
        String planName = configurationUi.planName().get();
        if (planName == null) {
            new Notification("Error", "Please select a plan.", Notification.Type.ERROR);
            return;
        }

        Map<String, String> fileConfigs = new HashMap<>();
        fileConfigs.putAll(settingsController.changedFileContents());
        try (StringWriter writer = new StringWriter()) {
            Map<String, String> properties = new LinkedHashMap<>(tempInstance.properties());
            properties.remove("server-port");
            properties.forEach((key, value) -> writer.append(key).append("=").append(value).append('\n'));
            fileConfigs.put("server.properties", writer.toString());
            String opsJson = createOpMeFileContent(tempInstance);
            if (opsJson != null) {
                fileConfigs.put("ops.json", opsJson);
            }
        } catch (IOException e) {
            new Notification("Error", "Failed to prepare server properties: " + e.getMessage(), Notification.Type.ERROR);
            return;
        }

        String subdomain = configurationUi.subdomain().get();

        screenHost().createHostedCheckout(tempInstance.name(), planName, remoteVariables, fileConfigs, subdomain,
                configurationUi.customPlan().get()).thenAccept(checkout -> {
            screenHost().openExternal(checkout.url);
            ScreenManager.getInstance().execute(() -> {
                settingsCleanup.run();
                close();
            });
        }).exceptionally(e -> {
            ScreenManager.getInstance().execute(() -> new Notification("Checkout Error", e.getMessage(), Notification.Type.ERROR));
            return null;
        });
    }

    private void createNewLocalServer() {
        String location = configurationUi == null ? screenHost().defaultInstanceLocation() : configurationUi.localLocation().get();
        ServerDetailsScreen details = new ServerDetailsScreen(parent, remotelyClient);
        retainSettingsController();
        settingsCleanup.run();
        closeCreationWindowForDesktop();
        screenHost().application().setScreen(details);
        details.addInstanceTab(tempInstance.raw());
        Async<Object> creation;
        try {
            creation = Objects.requireNonNull(screenHost().createLocalInstance(tempInstance.raw(), location));
        } catch (RuntimeException error) {
            creation = Async.failed(error);
        }
        creation.thenCompose(newInstance -> {
            ServerConfigurationTarget created = screenHost().configurationTarget(newInstance);
            tempInstance.properties().forEach(created::property);
            return screenHost().saveInstanceConfiguration(newInstance, settingsController).thenApply(v -> newInstance);
        }).thenAccept(newInstance -> ScreenManager.getInstance().execute(() -> {
            handleOpMe(newInstance);
            screenHost().configurationTarget(newInstance).state("STOPPED");
            if (creationCallback != null) {
                creationCallback.accept(newInstance);
            }
        })).exceptionally(ex -> {
            ScreenManager.getInstance().execute(() -> {
                creationInFlight = false;
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String message = cause.getMessage() == null || cause.getMessage().isBlank() ? "Instance Creation Failed" : cause.getMessage();
                tempInstance.log("Creation Failed: " + message);
                screenHost().failOperation(tempInstance.raw(), screenHost().activeOperationId(tempInstance.raw()), "CRASHED", message);
            });
            return null;
        }).whenComplete((ignored, failure) -> releaseSettingsController());
    }

    private void createNewRemoteServer() {
        ServerDetailsScreen details = new ServerDetailsScreen(parent, remotelyClient);
        retainSettingsController();
        settingsCleanup.run();
        closeCreationWindowForDesktop();
        screenHost().application().setScreen(details);
        details.addInstanceTab(tempInstance.raw());
        Notification notification = new Notification.Builder()
                .message("Creating Remote Server")
                .description(tempInstance.name())
                .type(Notification.Type.INFO)
                .loading(true)
                .autoSlideOut(false)
                .image(Identifier.animatedIcon("loadingGreen.png"))
                .animateImage(true)
                .accent(ThemeManager.getAccent("calm"))
                .build();
        Async<Object> creation;
        try {
            creation = Objects.requireNonNull(screenHost().createRemoteInstance(tempInstance.raw(), remoteHostContext));
        } catch (RuntimeException error) {
            creation = Async.failed(error);
        }
        creation
            .thenCompose(newInstance -> screenHost().saveInstanceConfiguration(newInstance, settingsController).thenApply(ignored -> newInstance))
            .thenCompose(newInstance -> screenHost().refreshRemoteInstance(remoteHostContext).handle((v, e) -> {
                if (e != null) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    ScreenManager.getInstance().execute(() -> new Notification("Refresh Failed", cause.getMessage(), Notification.Type.WARN));
                }
                return newInstance;
            }))
            .thenAccept(newInstance -> ScreenManager.getInstance().execute(() -> {
                handleOpMe(newInstance);
                screenHost().configurationTarget(newInstance).state("STOPPED");
                if (creationCallback != null) {
                    creationCallback.accept(newInstance);
                }
                notification.update().message("Remote Server Created").description(screenHost().configurationTarget(newInstance).name()).type(Notification.Type.SUCCESS).loading(false).image(null).autoSlideOut(true);
            })).exceptionally(ex -> {
                ScreenManager.getInstance().execute(() -> {
                    creationInFlight = false;
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String message = cause.getMessage() == null || cause.getMessage().isBlank() ? "Remote Instance Creation Failed" : cause.getMessage();
                    tempInstance.log("Remote Creation Failed: " + message);
                    screenHost().failOperation(tempInstance.raw(), screenHost().activeOperationId(tempInstance.raw()), "CRASHED", message);
                    notification.update().message("Creation Failed").description(message).type(Notification.Type.ERROR).loading(false).image(null).autoSlideOut(true);
                });
                return null;
            }).whenComplete((ignored, failure) -> releaseSettingsController());
    }

    private void closeCreationWindowForDesktop() {
        if (!Config.desktopMode) {
            return;
        }
        DesktopWindowsOverlay overlay = ScreenManager.getInstance().getDesktopWindowsOverlay();
        if (overlay == null) {
            return;
        }
        ScreenWindowWidget window = overlay.getActiveWindow();
        if (window != null && window.getScreen() instanceof SettingsScreen) {
            overlay.requestCloseWindowForScreen(window.getScreen());
        }
    }

    private void retainSettingsController() {
        settingsControllerRetained = true;
    }

    private void releaseSettingsController() {
        settingsControllerRetained = false;
        if (settingsControllerClosePending) closeSettingsControllers();
    }

    private void closeSettingsControllers() {
        if (settingsControllerRetained) {
            settingsControllerClosePending = true;
            return;
        }
        settingsControllerClosePending = false;
        if (pendingSettingsController != null) {
            pendingSettingsController.close();
            pendingSettingsController = null;
        }
        if (settingsController != null) {
            settingsController.close();
            settingsController = null;
        }
    }

    private void editServer() {
        String newName = tempInstance.name();
        Object oldLoader = originalInstance.modLoader();
        String oldVersion = originalInstance.version();
        String oldServerSoftware = originalInstance.software();
        String oldServerBuild = originalInstance.build();
        boolean versionChanged = !Objects.equals(oldLoader, tempInstance.modLoader()) || !Objects.equals(oldVersion, tempInstance.version());
        Set<String> allowedReStudioStartupChanges = isReStudioBackend
                ? resolveAllowedReStudioStartupChanges(tempInstance, oldLoader, oldVersion, oldServerSoftware, oldServerBuild)
                : Set.of();
        Notification updateNotification = versionChanged && !isReStudioBackend
                ? new Notification.Builder().message("Applying Version Changes...").autoSlideOut(false).image(Identifier.animatedIcon("loadingGreen.png")).animateImage(true).accent(ThemeManager.getAccent("calm")).build()
                : null;

        screenHost().applyInstanceEdit(originalInstance.raw(), tempInstance.raw(), newName, !isReStudioBackend,
                versionChanged && !isReStudioBackend, updateNotification, settingsController)
                .thenCompose(ignored -> isReStudioBackend ? saveRemoteVariables(allowedReStudioStartupChanges) : Async.completed(null))
                .thenRun(() -> ScreenManager.getInstance().execute(() -> {
                    if (versionChanged) {
                        if (!isReStudioBackend) {
                            ServerScreenHost.HostView host = resolveRemoteHostForOriginalInstance();
                            Async<Void> refreshFuture = host != null ? screenHost().refreshRemoteInstance(host) : Async.completed(null);
                            refreshFuture.whenComplete((refresh, refreshError) -> ScreenManager.getInstance().execute(() -> {
                                updateNotification.update().message("Server Updated Successfully!").description("Version changes applied.").type(Notification.Type.SUCCESS).loading(false).image(null);
                                updateNotification.loading = false;
                                updateNotification.autoSlideOut = true;
                                if (refreshError != null) {
                                    Throwable cause = refreshError.getCause() != null ? refreshError.getCause() : refreshError;
                                    new Notification("Refresh Failed", cause.getMessage(), Notification.Type.WARN);
                                }
                            }));
                        } else {
                            new Notification("Server Configuration Saved", "Settings updated on panel.", Notification.Type.SUCCESS);
                        }
                    } else {
                        new Notification(originalInstance.name() + " Edited Successfully!", Notification.Type.SUCCESS);
                    }
                    applyPendingDataDrivenReload();
                }))
                .exceptionally(ex -> {
                    ScreenManager.getInstance().execute(() -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        if (updateNotification != null) {
                            updateNotification.update().message("Update Failed").description(cause.getMessage()).type(Notification.Type.ERROR).loading(false).image(null);
                            updateNotification.loading = false;
                            updateNotification.autoSlideOut = true;
                            return;
                        }
                        new Notification("Edit Failed", cause.getMessage(), Notification.Type.ERROR);
                    });
                    return null;
                });
    }

    private ServerScreenHost.HostView resolveRemoteHostForOriginalInstance() {
        if (remoteHostContext != null) {
            return remoteHostContext;
        }
        return screenHost().resolveRemoteHost(originalInstance.raw(), remoteHostContext);
    }

    private Set<String> resolveAllowedReStudioStartupChanges(ServerConfigurationTarget proposed, Object oldLoader, String oldVersion,
                                                               String oldServerSoftware, String oldServerBuild) {
        Set<String> allowed = new HashSet<>();
        if (oldVersion == null ? proposed.version() != null : !oldVersion.equals(proposed.version())) {
            allowed.add("VERSION");
        }
        if (!Objects.equals(oldLoader, proposed.modLoader()) || !Objects.equals(normalized(oldServerSoftware), normalized(proposed.software()))) {
            allowed.add("SOFTWARE");
        }
        if (!Objects.equals(normalized(oldServerBuild), normalized(proposed.build()))) {
            allowed.add("BUILD");
        }
        REINSTALL_TRIGGERING_VARS.stream()
                .filter(key -> !Objects.equals(remoteVariables.get(key), originalRemoteVariables.get(key)))
                .forEach(allowed::add);
        return allowed;
    }

    private String normalized(String value) {
        return value == null || value.isBlank() || "latest".equalsIgnoreCase(value) ? null : value.trim();
    }

    private Async<Void> saveRemoteVariables(Set<String> allowedReinstallVariables) {
        if (!isReStudioBackend || remoteVariables.isEmpty()) return Async.completed(null);

        Set<String> reinstallTriggeringChanges = new HashSet<>();
        Map<String, String> changes = new LinkedHashMap<>();

        List<Map.Entry<String, String>> variables = remoteVariables.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        for (Map.Entry<String, String> entry : variables) {
            String key = entry.getKey();
            String newValue = entry.getValue();
            String oldValue = originalRemoteVariables.get(key);

            if (!Objects.equals(newValue, oldValue)) {
                if (REINSTALL_TRIGGERING_VARS.contains(key) && (allowedReinstallVariables == null || !allowedReinstallVariables.contains(key))) {
                    continue;
                }
                changes.put(key, newValue);

                if (REINSTALL_TRIGGERING_VARS.contains(key)) {
                    reinstallTriggeringChanges.add(key);
                }
            }
        }

        if (changes.isEmpty()) return Async.completed(null);
        RemotelyServerApi api = serverApi();
        if (api == null) return Async.failed(new IllegalStateException("Startup Settings Are Unavailable"));

        if (!reinstallTriggeringChanges.isEmpty()) {
            new Notification.Builder()
                .message("Server Reinstall Required")
                .description("Changes to " + String.join(", ", reinstallTriggeringChanges) + " will trigger a server reinstall.")
                .type(Notification.Type.WARN)
                .autoSlideOut(true)
                .build();
        }

        if (remoteStartupRevision.isBlank()) return Async.failed(new IllegalStateException("Startup Settings Revision Is Unavailable"));
        Map<String, String> batch = new LinkedHashMap<>();
        remoteVariables.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> batch.put(entry.getKey(), entry.getValue()));
        return api.updateServerStartupVariables(serverIdentifier, remoteStartupRevision, batch)
                .thenCompose(ignored -> api.getServerStartupConfig(serverIdentifier))
                .thenAccept(updated -> {
                    if (updated == null || updated.revision().isBlank()) throw new IllegalStateException("Startup Settings Revision Is Unavailable");
                    remoteStartupRevision = updated.revision();
                    remoteVariables.clear();
                    remoteVariables.putAll(updated.values());
                    originalRemoteVariables.clear();
                    originalRemoteVariables.putAll(updated.values());
                });
    }

    @Override
    public void onDisplayed() {
        playSound(Sound.SCREEN);
    }
    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (event.key() == ReKey.ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(event);
    }


    public void close() {
        screenClosed = true;
        screenHost().application().openParentScreen(this, parent);
    }

    @Override
    public void removed() {
        if (!settingsHandoff) {
            screenClosed = true;
            settingsCleanup.run();
        }
        super.removed();
    }

    private void handleOpMe(Object instance) {
        ServerConfigurationTarget target = screenHost().configurationTarget(instance);
        String json = createOpMeFileContent(target);
        if (json == null) return;

        screenHost().writeInstanceFile(instance, "ops.json", json).exceptionally(e -> {
            ReLog.logger(LogTypes.CONFIGURATION).source(LogSource.instance(target.id(), target.name())).component(ServerConfigurationScreen.class).operation("Grant Operator Access").error("Could not update operators", e);
            return null;
        });
    }

    private String createOpMeFileContent(ServerConfigurationTarget instance) {
        if (!Boolean.parseBoolean(instance.properties().getOrDefault("op-me", "false"))) return null;
        String uuid = RemotelyClient.INSTANCE.getHost().getGameUUID();
        String name = RemotelyClient.INSTANCE.getHost().getGameUserName();
        if (uuid == null || uuid.isBlank() || name == null || name.isBlank()) return null;

        JsonObject op = new JsonObject();
        op.addProperty("uuid", uuid);
        op.addProperty("name", name);
        op.addProperty("level", 4);
        op.addProperty("bypassesPlayerLimit", false);
        JsonArray operators = new JsonArray();
        operators.add(op);
        return operators.toString();
    }
}
