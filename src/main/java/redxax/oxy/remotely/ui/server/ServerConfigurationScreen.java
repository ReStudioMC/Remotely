package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.util.BrowserSafeState;

import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyServerApi;
import redxax.oxy.remotely.discord.DiscordRpcBridge;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import redxax.oxy.remotely.ui.settings.controllers.ServerSubdomainSettingsController;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.resource.ResourcePoolClient;
import restudio.rebase.platform.ExternalOpenResult;
import restudio.rebase.ui.widgets.ExternalLinkActions;
import restudio.rebase.storage.StorageBreakdownController;
import restudio.rebase.resource.marketplace.HostedModpackSelection;
import restudio.rebase.backend.CapabilityIds;
import restudio.rebase.backend.FileSpace;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.screens.DesktopWindowsOverlay;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ScreenWindowWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import restudio.rescreen.platform.Async;
import redxax.oxy.remotely.util.AsyncTools;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static restudio.rescreen.util.SoundUtils.playSound;

@SuppressWarnings("unchecked")
public class ServerConfigurationScreen extends ReScreen {
    static final String STORAGE_TAB = "Storage Breakdown";

    ServerScreenHost screenHost() {
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
    private final ResourcePoolController.Creation poolCreation;
    private final HostedModpackSelection modpackSelection;
    private String initialTab;
    private String storageDiskMiB;
    private StorageBreakdownController storageBreakdownController;
    private ResourcePoolModels.DraftOptions poolOptions = new ResourcePoolModels.DraftOptions(List.of());
    private PoolOwner poolOwner;
    private Setting poolConfigurationSetting;
    private MountableButtonWidget poolConfigurationStatus;
    private IconButton poolConfigurationRetry;
    private boolean poolOptionsReady;
    private boolean poolSettingsReady;
    private boolean poolOptionsLoading;
    private boolean poolSettingsLoading;
    private String poolOptionsFailure = "";
    private String poolSettingsFailure = "";
    private long poolSettingsRevision;

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
    private boolean editInFlight;
    private PopupWidget updatePopup;
    private PopupWidget checkoutPopup;
    private String checkoutAccount = "";
    private boolean checkoutDismissed;
    private Object localCreatedInstance;

    private static final Set<String> REINSTALL_TRIGGERING_VARS = Set.of(
        "VERSION", "SOFTWARE", "BUILD"
    );

    public <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient) {
        this(parent, instance, remoteHostContext, remotelyClient, false);
    }

    public <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient,
                                         String initialTab, String storageDiskMiB) {
        this(parent, instance, remoteHostContext, remotelyClient);
        this.initialTab = initialTab;
        this.storageDiskMiB = storageDiskMiB;
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
        this(parent, instance, remoteHostContext, remotelyClient, isReStudioCreation, preselectedPlanName, preset,
                creationInitializer, creationCallback, null);
    }

    public ServerConfigurationScreen(Screen parent, RemotelyClient remotelyClient, ResourcePoolController.Creation poolCreation) {
        this(parent, remotelyClient, poolCreation, (HostedModpackSelection) null);
    }

    public ServerConfigurationScreen(Screen parent, RemotelyClient remotelyClient, ResourcePoolController.Creation poolCreation,
                                     HostedModpackSelection modpackSelection) {
        this(parent, null, null, remotelyClient, true, null, null, null, null,
                Objects.requireNonNull(poolCreation, "poolCreation"), modpackSelection);
    }

    private <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient,
                                          boolean isReStudioCreation, String preselectedPlanName, Object preset,
                                          Consumer<T> creationInitializer, Consumer<T> creationCallback,
                                          ResourcePoolController.Creation poolCreation) {
        this(parent, instance, remoteHostContext, remotelyClient, isReStudioCreation, preselectedPlanName, preset,
                creationInitializer, creationCallback, poolCreation, null);
    }

    private <T> ServerConfigurationScreen(Screen parent, T instance, Object remoteHostContext, RemotelyClient remotelyClient,
                                          boolean isReStudioCreation, String preselectedPlanName, Object preset,
                                          Consumer<T> creationInitializer, Consumer<T> creationCallback,
                                          ResourcePoolController.Creation poolCreation, HostedModpackSelection modpackSelection) {
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
        this.poolCreation = poolCreation;
        this.modpackSelection = modpackSelection;

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
            if (modpackSelection != null) this.tempInstance.name(modpackSelection.name());
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

    String poolServerName(String serverId) {
        return parent instanceof ResourcePoolScreen resources ? resources.poolServerName(serverId) : serverId;
    }

    ResourcePoolScreen poolResources() {
        return parent instanceof ResourcePoolScreen resources ? resources : null;
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
        if (poolCreation == null) {
            ServerSettingsDataController controller = screenHost().createServerSettingsController(tempInstance.raw(),
                    ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw()));
            setupSettingsUI(extraFiles, controller);
            startInitialConfigLoad();
            return;
        }
        ServerScreenHost host = screenHost();
        if (!(parent instanceof ResourcePoolScreen resources) || resources.poolView(poolCreation.poolId()) == null) {
            new Notification("Configuration Unavailable", "Resource Pool Capacity Is Unavailable", Notification.Type.ERROR);
            return;
        }
        ServerSettingsDataController controller = host.createNewServerSettingsController(tempInstance.raw(),
                ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw()));
        startInitialConfigLoad();
        poolOwner = new PoolOwner(host, serverApi(), host.resourcePools(), host.hostedNetworkAccount(),
                host.authenticationSession(), configurationLoadRevision.get());
        poolConfigurationStatus = new MountableButtonWidget.Builder("Server Setup").description("Loading Server Images And Software Settings").build();
        poolConfigurationRetry = new IconButton.Builder().label("Retry Setup").imagePath("reload.png").size(130, 20)
                .onClick(this::retryPoolConfiguration).build();
        Setting.Builder loading = new Setting.Builder("Server Setup");
        loading.addRow("", poolConfigurationStatus);
        loading.addRow(new PopupWidget.PopupRow.Builder("", poolConfigurationRetry).id("retry").build());
        poolConfigurationSetting = loading.build();
        setupSettingsUI(extraFiles, controller);
        loadPoolOptions();
        loadPoolSettings(controller, false);
    }

    ResourcePoolModels.DraftOptions poolOptions() {
        return poolOptions;
    }

    List<Setting> poolLoadingSettings() {
        return poolConfigurationSetting == null ? List.of() : List.of(poolConfigurationSetting);
    }

    private void loadPoolOptions() {
        if (poolOptionsLoading || !currentPoolCreation(poolOwner) || !(parent instanceof ResourcePoolScreen resources)) return;
        PoolOwner captured = poolOwner;
        poolOptionsLoading = true;
        poolOptionsFailure = "";
        updatePoolConfiguration();
        resources.controller().draftOptions(captured.host()).whenComplete((available, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!currentPoolCreation(captured)) return;
            poolOptionsLoading = false;
            if (failure != null || available == null || available.games().stream()
                    .noneMatch(game -> "minecraft:java".equals(game.id()) && !game.profiles().isEmpty())) {
                poolOptionsFailure = failure == null ? "No Minecraft Server Images Are Available" : configurationFailureMessage(failure);
            } else {
                poolOptions = available;
                poolOptionsReady = true;
                configurationUi.startupLoaded().run();
            }
            updatePoolConfiguration();
        }));
    }

    private void loadPoolSettings(ServerSettingsDataController controller, boolean replace) {
        if (poolSettingsLoading || !currentPoolCreation(poolOwner)) {
            if (replace) controller.close();
            return;
        }
        poolSettingsLoading = true;
        PoolOwner captured = poolOwner;
        poolSettingsReady = false;
        poolSettingsFailure = "";
        long request = ++poolSettingsRevision;
        updatePoolConfiguration();
        Async<Void> load;
        try {
            load = AsyncTools.withTimeout(controller.load(), remotelyClient.getComposition().scheduler(), Duration.ofSeconds(30));
        } catch (RuntimeException failure) {
            load = Async.failed(failure);
        }
        load.whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!currentPoolCreation(captured) || request != poolSettingsRevision) {
                if (replace) controller.close();
                return;
            }
            poolSettingsLoading = false;
            if (failure != null) {
                poolSettingsFailure = "Software Settings Could Not Load: " + configurationFailureMessage(failure);
                if (replace) controller.close();
            } else {
                if (replace) applyDataDrivenReload(settingsReloadRevision.incrementAndGet(), controller);
                poolSettingsReady = settingsController == controller;
                if (!poolSettingsReady) poolSettingsFailure = "Reset Changed Software Settings Before Retrying Setup";
                refreshConfigurationCategories(controller.tabNames());
            }
            updatePoolConfiguration();
        }));
    }

    private void retryPoolConfiguration() {
        if (!currentPoolCreation(poolOwner) || poolOptionsLoading || poolSettingsLoading) return;
        if (!poolOptionsReady) loadPoolOptions();
        if (!poolSettingsReady && allowServerSoftwareChange(null)) {
            if (pendingSettingsController != null) {
                ServerSettingsDataController pending = pendingSettingsController;
                applyPendingDataDrivenReload();
                poolSettingsReady = settingsController == pending;
                if (poolSettingsReady) poolSettingsFailure = "";
            } else loadPoolSettings(poolOwner.host().createNewServerSettingsController(tempInstance.raw(),
                    ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw())), true);
        }
        updatePoolConfiguration();
    }

    private void updatePoolConfiguration() {
        if (poolConfigurationSetting == null) return;
        boolean current = currentPoolCreation(poolOwner);
        String failure = !current ? "Account Changed. Reopen Server Creation" : !poolOptionsFailure.isBlank() ? poolOptionsFailure : poolSettingsFailure;
        poolConfigurationStatus.setDescription(!failure.isBlank() ? failure : poolOptionsLoading && poolSettingsLoading
                ? "Loading Server Images And Software Settings" : poolOptionsLoading ? "Loading Server Images" : "Loading Software Settings");
        poolConfigurationSetting.setVisible(!current || !poolOptionsReady || !poolSettingsReady);
        poolConfigurationSetting.setRowVisibility("retry", current && !failure.isBlank());
        poolConfigurationRetry.setActive(current && !poolOptionsLoading && !poolSettingsLoading);
        if (settingsScreen != null) {
            var save = settingsScreen.header().getButtonByImagePath("checkmark.png");
            if (save != null) save.setActive(current && poolOptionsReady && poolSettingsReady && !creationInFlight);
        }
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
            if (modpackSelection != null) {
                remoteVariables.put("SOFTWARE", modpackSelection.software());
                remoteVariables.put("VERSION", modpackSelection.minecraftVersion());
            }
        }
        if (poolCreation != null) return;

        Set<String> propertyCategories = new LinkedHashSet<>(fixedSettingsSuppliers.keySet());
        if (!host.refreshGeneralAfterProperties()) propertyCategories.remove("General");
        observeConfigurationLoad(revision, "Server Properties",
                host.configurationLoad("Server Properties", host.loadInstanceProperties(tempInstance.raw(), isEditMode && remote), () -> null),
                propertyCategories);
        if (!isEditMode) return;

        if (host.usesInstanceMetadataReloads()) {
            observeConfigurationLoad(revision, "Server Settings",
                    host.configurationLoad("Server Settings", host.reloadInstanceSettings(tempInstance.raw(), remote), () -> null),
                    fixedSettingsSuppliers.keySet());
            observeConfigurationLoad(revision, "Modpack Settings",
                    host.configurationLoad("Modpack Settings", host.loadInstanceModpack(tempInstance.raw()), () -> null),
                    Set.of("General"));
        }
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
            host.configurationLoad("Startup Configuration", startup, () -> null)
                    .whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
                        if (!acceptConfigurationLoad(revision, "Startup Configuration", failure)) return;
                        configurationUi.startupLoaded().run();
                        refreshConfigurationCategories(Set.of("Software Settings", "Java"));
                    }));
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
            Supplier<List<Setting>> fixed = fixedSettingsSuppliers.get(category);
            boolean dataDriven = settingsController != null && settingsController.tabNames().contains(category);
            if (fixed != null || dataDriven) {
                boolean accepted = settingsScreen.registerCategory(category, dataDriven
                        ? combinedSupplier(fixed, () -> settingsController.settings(category)) : fixed);
                if (!accepted) pendingPublishedTabs.add(category);
            }
        }
    }

    public Screen settingsOwner() { return settingsScreen == null ? this : settingsScreen; }

    private void setupSettingsUI(List<String> extraFiles, ServerSettingsDataController settingsController) {
        this.settingsController = settingsController;
        ServerScreenHost.ConfigurationState state = new ServerScreenHost.ConfigurationState(
                originalInstance == null ? null : originalInstance.raw(), tempInstance.raw(), remoteHostContext,
                isEditMode, isReStudioBackend, isReStudioCreation, serverIdentifier, preselectedPlanName,
                poolCreation != null, poolOptions, parent instanceof ResourcePoolScreen resources && poolCreation != null
                ? resources.poolView(poolCreation.poolId()) : null);
        configurationUi = screenHost().createConfigurationUi(this, state, settingsController, remoteVariables, extraFiles,
                this::reloadDataDrivenSettings, () -> allowServerSoftwareChange(null));
        Map<String, Supplier<List<Setting>>> settingsByTab = new LinkedHashMap<>(configurationUi.settings());
        if (isEditMode) settingsByTab.put(STORAGE_TAB, this::storageSettings);
        List<Runnable> cleanupActions = new ArrayList<>();
        cleanupActions.add(() -> screenClosed = true);
        cleanupActions.add(() -> { if (storageBreakdownController != null) storageBreakdownController.close(); });
        if (isEditMode && isReStudioBackend && serverIdentifier != null && !serverIdentifier.isBlank()) {
            ServerSubdomainSettingsController subdomain = new ServerSubdomainSettingsController(remotelyClient, serverIdentifier, false);
            subdomain.owner(this::settingsOwner);
            subdomain.onChanged(() -> { if (settingsScreen != null && !screenClosed) settingsScreen.refreshTab("Network"); });
            Supplier<List<Setting>> network = settingsByTab.getOrDefault("Network", List::of);
            settingsByTab.put("Network", () -> {
                List<Setting> settings = new ArrayList<>(network.get());
                settings.addAll(subdomain.settings());
                return settings;
            });
            cleanupActions.add(subdomain::close);
        }

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

        settingsScreen = new SettingsScreen(parent, configurationUi.title(), settingsByTab, this::saveConfiguration,
                combinedCleanup, initialTab) {
            @Override
            public void init() {
                super.init();
                updatePoolConfiguration();
            }

            @Override
            public void tick() {
                super.tick();
                updatePoolConfiguration();
            }

            @Override
            public void removed() {
                settingsCleanup.run();
                super.removed();
            }
        };
        if (poolCreation != null || parent instanceof ResourcePoolScreen) {
            settingsScreen.enableNavigation(parent);
        }
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
        if (poolCreation != null || parent instanceof ResourcePoolScreen) {
            settingsHandoff = true;
            ScreenManager.getInstance().replaceScreen(this, settingsScreen);
            return;
        }
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

    private List<Setting> storageSettings() {
        if (storageBreakdownController == null) {
            ServerScreenHost.StorageFiles files = screenHost().storageFiles(originalInstance.raw());
            RemoteFileSystemProvider provider = files.provider();
            RemotePath root = files.root();
            String unavailable = null;
            String sourceId = null;
            var capability = provider.operationCapability(CapabilityIds.FILES, List.of(), root);
            if (!capability.available()) {
                unavailable = capability.detail() == null || capability.detail().isBlank()
                        ? "Server Files Are Unavailable" : capability.detail();
            } else {
                try {
                    FileSpace space = FileSpace.from(provider);
                    root = provider.canonicalPath(root);
                    if (space == null || root == null) unavailable = "This File Source Has No Stable Identity";
                    else sourceId = space + "|" + root.asString();
                } catch (RuntimeException failure) {
                    unavailable = "This File Source Has No Stable Identity";
                }
            }
            BigInteger capacity = null;
            if (storageDiskMiB != null && !storageDiskMiB.isBlank()) {
                try {
                    capacity = new BigInteger(storageDiskMiB).max(BigInteger.ZERO).multiply(BigInteger.valueOf(1024L * 1024));
                } catch (NumberFormatException ignored) {
                    capacity = null;
                }
            }
            storageBreakdownController = new StorageBreakdownController(remotelyClient.storageBreakdownIndex(),
                    screenHost().accountIdentity().authenticated() ? screenHost().hostedNetworkAccount() : "", sourceId, provider, root, capacity, unavailable,
                    () -> screenHost().accountIdentity().authenticated() ? screenHost().hostedNetworkAccount() : "",
                    (parts, size) -> {
                        ResourceAllocationBarWidget bar = new ResourceAllocationBarWidget("Disk",
                                parts.stream().map(part -> new ResourceAllocationBarWidget.StoragePart(part.name(), part.bytes())).toList(), size);
                        bar.entranceAnimationEnabled = false;
                        return bar;
                    }, files.indexed(), remotelyClient.getComposition().scheduler());
        }
        return storageBreakdownController.settings();
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
        if (poolCreation != null) {
            if (!currentPoolCreation(poolOwner) || poolSettingsLoading) return;
            loadPoolSettings(poolOwner.host().createNewServerSettingsController(tempInstance.raw(),
                    ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw())), true);
            return;
        }
        long revision = settingsReloadRevision.incrementAndGet();
        ServerSettingsDataController next = poolCreation == null ? screenHost().createServerSettingsController(tempInstance.raw(),
                ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw())) : screenHost().createNewServerSettingsController(tempInstance.raw(),
                ServerSettingsRegistry.getInstance().snapshot(tempInstance.raw()));
        next.load().thenRun(() -> ScreenManager.getInstance().execute(() -> applyDataDrivenReload(revision, next))).exceptionally(error -> {
            next.close();
            ReLog.logger(LogTypes.CONFIGURATION).source(LogSource.instance(tempInstance.id(), tempInstance.name())).component(ServerConfigurationScreen.class).operation("Reload Server Settings").error("Could not reload server settings metadata", error);
            return null;
        });
    }

    private boolean allowServerSoftwareChange(String ignored) {
        if (poolCreation != null && poolSettingsLoading) return false;
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
        if (poolCreation != null) {
            createPoolServer();
        } else if (isReStudioCreation) {
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
        if (configurationUi == null || creationInFlight) return;
        if (checkoutPopup != null && checkoutAccount.equals(screenHost().accountIdentity().subjectId())) {
            checkoutDismissed = false;
            checkoutPopup.show();
            return;
        }
        String planName = configurationUi.planName().get();
        if (planName == null) {
            new Notification("Error", "Please select a plan.", Notification.Type.ERROR);
            return;
        }

        Map<String, String> fileConfigs;
        try {
            fileConfigs = initialFiles();
        } catch (IOException e) {
            new Notification("Error", "Failed to prepare server properties: " + e.getMessage(), Notification.Type.ERROR);
            return;
        }

        String subdomain = configurationUi.subdomain().get();

        creationInFlight = true;
        checkoutAccount = screenHost().accountIdentity().subjectId();
        String account = checkoutAccount;
        screenHost().createHostedCheckout(tempInstance.name(), planName, remoteVariables, fileConfigs, subdomain,
                configurationUi.customPlan().get()).whenComplete((checkout, failure) -> ScreenManager.getInstance().execute(() -> {
            creationInFlight = false;
            if (screenClosed || !account.equals(screenHost().accountIdentity().subjectId())) return;
            if (failure != null || checkout == null || checkout.url == null || checkout.url.isBlank()) {
                new Notification("Checkout Needs Attention", failure == null ? "Checkout Link Unavailable" : ResourcePoolController.message(failure), Notification.Type.ERROR);
                return;
            }
            Screen owner = settingsOwner();
            checkoutDismissed = false;
            PopupWidget[] surface = new PopupWidget[1];
            PopupWidget.Builder builder = new PopupWidget.Builder("Secure Checkout").size(Math.min(440, Math.max(1, owner.width - 24)),
                    Math.min(240, Math.max(1, owner.height - 40))).setMinSize(1, 1).setResizable(false)
                    .onClose(() -> checkoutDismissed = true);
            builder.addMarkdown("", "Complete Payment In Your Browser, Then Return To Check Server Activation And Review Status.");
            Runnable open = ExternalLinkActions.add(builder, checkout.url,
                    () -> !screenClosed && !checkoutDismissed && account.equals(screenHost().accountIdentity().subjectId())
                            && surface[0] != null && surface[0].isVisible(), screenHost()::openExternal, "Open Secure Checkout", result -> {
                        if (result != ExternalOpenResult.OPENED) return;
                        settingsCleanup.run();
                        close();
                    });
            checkoutPopup = builder.build();
            surface[0] = checkoutPopup;
            owner.addDrawableChild(checkoutPopup);
            checkoutPopup.centerOnOwner();
            checkoutPopup.show();
            open.run();
        }));
    }

    private void createPoolServer() {
        if (configurationUi == null || creationInFlight || screenClosed) {
            return;
        }
        PoolOwner captured = poolOwner;
        if (!currentPoolCreation(captured) || !poolOptionsReady || !poolSettingsReady) {
            updatePoolConfiguration();
            return;
        }
        PoolAllocationEditor editor = parent instanceof ResourcePoolScreen resources
                ? resources.poolEditor(poolCreation.poolId()) : null;
        if (editor != null && (editor.hasChanges() || editor.hasPending())) {
            new Notification("Existing Server Change Pending", editor.hasChanges()
                    ? "Apply Or Reset The Resource Change Before Creating This Server"
                    : "Wait For Updated Pool Capacity Before Creating This Server", Notification.Type.WARN);
            return;
        }
        creationInFlight = true;
        ServerScreenHost.PoolResources limits = configurationUi.poolResources().get();
        if ("0".equals(limits.runtimeRamMiB()) || "0".equals(limits.runtimeCpuPercent())
                || "0".equals(limits.diskMiB())) {
            creationInFlight = false;
            new Notification("Not Enough Pool Capacity", "Add Free RAM, CPU, And Disk Before Creating A Server", Notification.Type.WARN);
            return;
        }
        ResourcePoolController.PoolView pool = parent instanceof ResourcePoolScreen resources
                ? resources.poolView(poolCreation.poolId()) : null;
        if (pool == null || new BigInteger(limits.installerRamMiB()).compareTo(
                new BigInteger(pool.pool().balance().available().ramMiB())) > 0
                || new BigInteger(limits.installerCpuPercent()).compareTo(
                new BigInteger(pool.pool().balance().available().cpuQuotaPercent())) > 0) {
            creationInFlight = false;
            new Notification("Not Enough Pool Capacity", "Setup Needs More Free RAM Or CPU", Notification.Type.WARN);
            return;
        }
        Map<String, String> files;
        try {
            files = initialFiles();
        } catch (IOException failure) {
            creationInFlight = false;
            new Notification("Server Creation Error", configurationFailureMessage(failure), Notification.Type.ERROR);
            return;
        }
        ResourcePoolController controller = parent instanceof ResourcePoolScreen resourceScreen
                ? resourceScreen.controller() : null;
        if (controller == null) {
            creationInFlight = false;
            new Notification("Server Draft Error", "Resource Pool Session Is Unavailable", Notification.Type.ERROR);
            return;
        }
        Notification notice = new Notification.Builder().message("Creating Server").description(tempInstance.name())
                .type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();
        Async<ResourcePoolModels.Draft> request;
        try {
            ResourcePoolModels.DraftModpack modpack = modpackSelection == null ? null : new ResourcePoolModels.DraftModpack(
                    modpackSelection.name(), modpackSelection.provider(), modpackSelection.projectId(), modpackSelection.versionId(),
                    modpackSelection.versionNumber(), modpackSelection.downloadUrl(), modpackSelection.minecraftVersion(), modpackSelection.software());
            request = controller.createDraft(poolCreation, tempInstance.name(), limits,
                    new LinkedHashMap<>(remoteVariables), files, modpack, configurationUi.subdomain().get());
        } catch (RuntimeException failure) {
            creationInFlight = false;
            notice.update().message("Server Draft Invalid").description(ResourcePoolController.message(failure))
                    .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            return;
        }
        request.whenComplete((draft, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!currentPoolCreation(captured)) {
                creationInFlight = false;
                notice.update().message("Server Creation Paused").description("Open Server Drafts From The Original Account To Review This Request")
                        .type(Notification.Type.WARN).loading(false).autoSlideOut(true).commit();
                return;
            }
            if (failure != null) {
                creationInFlight = false;
                notice.update().message("Server Creation Needs Attention").description(ResourcePoolController.message(failure))
                        .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                return;
            }
            notice.update().message("Allocating Server").description(draft.metadata().name()).commit();
            controller.activate(draft, limits).whenComplete((activated, activationFailure) -> ScreenManager.getInstance().execute(() -> {
                creationInFlight = false;
                if (!currentPoolCreation(captured)) {
                    notice.update().message("Server Creation Submitted").description("Open Server Drafts From The Original Account To Review The Outcome")
                            .type(Notification.Type.INFO).loading(false).autoSlideOut(true).commit();
                    return;
                }
                if (activationFailure != null) {
                    String failureMessage = ResourcePoolController.message(activationFailure);
                    String description = failureMessage.contains("Server Address Is Unavailable")
                            ? "No Free Server Address Is Available. Your Draft Is Saved. Contact Support To Add An Address."
                            : failureMessage + ". Open Server Drafts To Retry Activation.";
                    notice.update().message("Server Draft Needs Attention")
                            .description(description)
                            .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                    return;
                }
                boolean active = activated.state() == ResourcePoolModels.DraftState.ACTIVE;
                boolean review = activated.state() == ResourcePoolModels.DraftState.UNKNOWN;
                notice.update().message(active ? "Server Created" : review ? "Activation Needs Review" : "Activation Submitted")
                        .description(review ? activated.reason() == null ? "Setup Did Not Complete" : activated.reason()
                                : activated.metadata().name())
                        .type(active ? Notification.Type.SUCCESS : review ? Notification.Type.WARN : Notification.Type.INFO)
                        .loading(false).autoSlideOut(true).commit();
                if (!screenClosed) {
                    settingsCleanup.run();
                    close();
                }
            }));
        }));
    }

    private boolean currentPoolCreation(PoolOwner owner) {
        return owner != null && !screenClosed && owner.generation() == configurationLoadRevision.get()
                && owner.host() == screenHost() && owner.api() == serverApi() && owner.pools() == owner.host().resourcePools()
                && owner.host().accountIdentity().authenticated() && Objects.equals(owner.account(), owner.host().hostedNetworkAccount())
                && Objects.equals(owner.session(), owner.host().authenticationSession());
    }

    private record PoolOwner(ServerScreenHost host, RemotelyServerApi api, ResourcePoolClient pools, String account, String session, long generation) {}

    private Map<String, String> initialFiles() throws IOException {
        return ServerCreationFiles.initialFiles(tempInstance, settingsController,
                RemotelyClient.INSTANCE.getHost().getGameUUID(), RemotelyClient.INSTANCE.getHost().getGameUserName());
    }

    private void createNewLocalServer() {
        ServerScreenHost host = screenHost();
        String location = configurationUi == null ? host.defaultInstanceLocation() : configurationUi.localLocation().get();
        ServerDetailsScreen details = new ServerDetailsScreen(parent, remotelyClient);
        retainSettingsController();
        settingsCleanup.run();
        closeCreationWindowForDesktop();
        host.application().setScreen(details);
        details.addInstanceTab(tempInstance.raw());
        Async<Object> creation;
        try {
            creation = localCreatedInstance == null ? Objects.requireNonNull(host.createLocalInstance(tempInstance.raw(), location))
                    : Async.completed(localCreatedInstance);
        } catch (RuntimeException error) {
            creation = Async.failed(error);
        }
        creation.thenCompose(newInstance -> {
            localCreatedInstance = newInstance;
            ServerConfigurationTarget created = host.configurationTarget(newInstance);
            tempInstance.properties().forEach(created::property);
            return host.saveInstanceConfiguration(newInstance, settingsController).thenApply(ignored -> newInstance);
        }).thenAccept(newInstance -> ScreenManager.getInstance().execute(() -> {
            handleOpMe(newInstance);
            host.configurationTarget(newInstance).state("STOPPED");
            creationInFlight = false;
            if (creationCallback != null) {
                creationCallback.accept(newInstance);
            }
        })).exceptionally(ex -> {
            ScreenManager.getInstance().execute(() -> {
                creationInFlight = false;
                String message = configurationFailureMessage(ex);
                tempInstance.log("Creation Failed: " + message);
                if (localCreatedInstance != null) {
                    host.configurationTarget(localCreatedInstance).state("STOPPED");
                    new Notification("Server Setup Needs Attention", message + ". The Server Was Kept. Open Its Settings To Continue.", Notification.Type.WARN);
                } else host.failOperation(tempInstance.raw(), host.activeOperationId(tempInstance.raw()), "CRASHED", message);
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
                .progress(0, 100)
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
                    String message = configurationFailureMessage(ex);
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
        editServer(false);
    }

    private void editServer(boolean stopConfirmed) {
        if (editInFlight) return;
        String newName = tempInstance.name();
        Object oldLoader = originalInstance.modLoader();
        String oldVersion = originalInstance.version();
        String oldServerSoftware = originalInstance.software();
        String oldServerBuild = originalInstance.build();
        boolean versionChanged = !Objects.equals(oldLoader, tempInstance.modLoader()) || !Objects.equals(oldVersion, tempInstance.version())
                || !Objects.equals(oldServerSoftware, tempInstance.software()) || !Objects.equals(oldServerBuild, tempInstance.build());
        if (versionChanged && !isReStudioBackend && !stopConfirmed && screenHost().state(originalInstance.raw()) == ServerScreenHost.ServerState.RUNNING) {
            showUpdatePopup();
            return;
        }
        Set<String> allowedReStudioStartupChanges = isReStudioBackend
                ? resolveAllowedReStudioStartupChanges(tempInstance, oldLoader, oldVersion, oldServerSoftware, oldServerBuild)
                : Set.of();
        Notification updateNotification = versionChanged && !isReStudioBackend
                ? new Notification.Builder().message("Installing Server").description("Preparing Update").type(Notification.Type.INFO)
                        .loading(true).autoSlideOut(false).progress(0, 100).build()
                : null;

        editInFlight = true;
        screenHost().applyInstanceEdit(originalInstance.raw(), tempInstance.raw(), newName, !isReStudioBackend,
                versionChanged && !isReStudioBackend, updateNotification, settingsController)
                .thenCompose(ignored -> isReStudioBackend ? saveRemoteVariables(allowedReStudioStartupChanges) : Async.completed(null))
                .thenRun(() -> ScreenManager.getInstance().execute(() -> {
                    editInFlight = false;
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
                        editInFlight = false;
                        String detail = configurationFailureMessage(ex);
                        if (updateNotification != null) {
                            updateNotification.update().message("Update Failed").description(detail).type(Notification.Type.ERROR).loading(false).image(null);
                            updateNotification.loading = false;
                            updateNotification.autoSlideOut = true;
                            return;
                        }
                        new Notification("Edit Failed", detail, Notification.Type.ERROR);
                    });
                    return null;
                });
    }

    private void showUpdatePopup() {
        if (updatePopup != null) {
            updatePopup.show();
            return;
        }
        PopupWidget.Builder builder = new PopupWidget.Builder("Stop And Update").width(370).setResizable(false).setAntiOutOfBound(true);
        builder.addRow(new PopupWidget.PopupRow.Builder("The Server Is Running")
                .description("We Will Save The World, Stop The Server, And Apply Your Version Changes. Your Server Will Stay Off When The Update Finishes.").build());
        builder.addTitleAction("Stop And Update", () -> {
            updatePopup.setVisible(false);
            editServer(true);
        }, PopupWidget.TitleActionRole.PRIMARY);
        builder.addTitleAction("Cancel", () -> updatePopup.setVisible(false), PopupWidget.TitleActionRole.SECONDARY);
        updatePopup = builder.build();
        settingsScreen.addDrawableChild(updatePopup);
        updatePopup.show();
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
        if (poolCreation != null || parent instanceof ResourcePoolScreen) {
            ScreenManager.getInstance().goBack(settingsScreen == null ? this : settingsScreen, parent);
        } else {
            screenHost().application().openParentScreen(this, parent);
        }
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
        return ServerCreationFiles.operatorFile(instance, RemotelyClient.INSTANCE.getHost().getGameUUID(),
                RemotelyClient.INSTANCE.getHost().getGameUserName());
    }
}
