package redxax.oxy.remotely.web;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.RemotelyComposition;
import redxax.oxy.remotely.ui.server.ServerManagerScreen;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.flow.registry.NodeDiscoveryPreferences;
import redxax.oxy.remotely.data.flow.ReSyncCredentialProvider;
import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientContext;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientFactory;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransportFactory;
import redxax.oxy.remotely.data.flow.ReSyncWebSocketFrameTransport;
import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.host.ApplicationHostRegistry;
import redxax.oxy.remotely.metadata.catalog.ServerSettingsCatalogService;
import redxax.oxy.remotely.web.platform.BrowserApplicationHost;
import redxax.oxy.remotely.web.platform.BrowserClock;
import redxax.oxy.remotely.web.platform.BrowserCatalogPublicationDecoder;
import redxax.oxy.remotely.util.TaskIdentities;
import redxax.oxy.remotely.web.platform.BrowserCommunityProvider;
import redxax.oxy.remotely.web.platform.BrowserDiagnosticsClient;
import redxax.oxy.remotely.web.platform.BrowserDeveloperCapabilityAdapter;
import redxax.oxy.remotely.web.platform.BrowserFileExplorerAdapters;
import redxax.oxy.remotely.web.platform.BrowserHttpTransport;
import redxax.oxy.remotely.web.platform.BrowserLaunchSession;
import redxax.oxy.remotely.web.platform.BrowserMarketplaceDetailsProvider;
import redxax.oxy.remotely.web.platform.BrowserMetadataRepository;
import redxax.oxy.remotely.web.platform.BrowserReSyncClock;
import redxax.oxy.remotely.web.platform.BrowserReSyncIdentityProvider;
import redxax.oxy.remotely.web.platform.BrowserReSyncStorage;
import redxax.oxy.remotely.web.platform.BrowserRemotelyServerApi;
import redxax.oxy.remotely.web.platform.BrowserRemotelyConfigStore;
import redxax.oxy.remotely.web.platform.BrowserTaskScheduler;
import restudio.rescreen.platform.browser.BrowserWebSocketTransport;
import restudio.rescreen.platform.browser.BrowserKeyValueStore;
import redxax.oxy.remotely.util.TaskSchedulers;
import redxax.oxy.remotely.settings.server.BrowserSafeYamlServerSettingsMetadataParser;
import redxax.oxy.remotely.settings.server.BundledServerSettingsRegistry;
import redxax.oxy.remotely.settings.server.ServerSettingsRegistry;
import restudio.rescreen.platform.browser.BrowserReScreenClient;
import restudio.rescreen.platform.browser.BrowserRuntimeDiagnostics;
import restudio.rescreen.config.BackgroundSource;
import restudio.rescreen.config.Config;
import restudio.rescreen.config.UiConfigStore;
import restudio.rescreen.platform.ITextRenderer;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.text.FontRegistry;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Sound;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.platform.websocket.WebSocketOptions;
import restudio.rebase.backend.FileExplorerProviders;
import restudio.rebase.backend.FileExplorerRuntime;
import restudio.rebase.backend.DeveloperCapabilityProviders;
import restudio.rebase.restudio.community.ReStudioCommunityProviders;
import restudio.rebase.restudio.community.ReStudioCommunityProvider;
import restudio.rebase.ui.worldmap.BrowserWorldMapProvider;
import restudio.rebase.ui.worldmap.WorldMapProvider;
import restudio.rebase.ui.screens.editor.completion.CodeCompletionRegistry;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rebase.ui.widgets.TerminalModelProvider;

import java.util.List;
import java.util.Objects;

public final class RemotelyBrowserComposition {
    private RemotelyBrowserComposition() {
    }

    public static Runtime start(String canvasId, BrowserLaunchSession.Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        CodeCompletionRegistry.installDefaults();
        UiConfigStore previousConfig = Config.configManager;
        ConfigSnapshot previousConfigValues = ConfigSnapshot.capture();
        ApplicationHost previousApplicationHost = ApplicationHostRegistry.current();
        TaskScheduler previousScheduler = TaskSchedulers.current();
        Async.Snapshot previousAsync = Async.snapshot();
        NodeDiscoveryPreferences.Snapshot previousRecentNodes = NodeDiscoveryPreferences.snapshot();
        FileExplorerProviders.Snapshot previousFileExplorerProviders = FileExplorerProviders.snapshot();
        FileExplorerRuntime.Snapshot previousFileExplorerRuntime = FileExplorerRuntime.snapshot();
        ScreenManager.Snapshot previousScreenManager = ScreenManager.getInstance().snapshot();
        Object previousApplicationDirectory = Config.applicationDir;
        ITextRenderer previousTextRenderer = TextRenderer.getTextRendererAdapter();
        ITextRenderer previousRemotelyRenderer = RemotelyClient.tr;
        RemotelyClient previousClient = RemotelyClient.INSTANCE;
        Object previousMonoFont = FontRegistry.MONO_FONT;
        String previousOs = RemotelyClient.os;
        WorldMapProvider previousWorldMapProvider = WorldMapProvider.current();
        ReStudioCommunityProvider previousCommunityProvider = ReStudioCommunityProviders.current();
        DeveloperCapabilityProviders.Snapshot previousDeveloperCapabilities = DeveloperCapabilityProviders.snapshot();
        ThemeManager.Snapshot previousTheme = ThemeManager.snapshot();
        TerminalWidget.Snapshot previousTerminalWidget = TerminalWidget.snapshot();
        BrowserApplicationHost host = null;
        BrowserRemotelyConfigStore config = null;
        WorldMapProvider browserWorldMapProvider = null;
        BrowserAdapters adapters = null;
        BrowserCommunityProvider communityProvider = null;
        BrowserRemotelyServerApi serverApi = null;
        BrowserDeveloperCapabilityAdapter developerAdapter = null;
        RemotelyClient client = null;
        BrowserReScreenClient screenClient = null;
        Screen browserRoot = null;
        RemotelyComposition composition = null;
        ServerSettingsRegistry.StorageSnapshot serverSettingsStorage = null;
        OptionCatalogCache previousOptionCatalogCache = null;
        try {
            boolean desktopMode = !BrowserReScreenClient.compactTouchDevice();
            Config.setDesktopModeOverride(desktopMode);
            boolean demo = metadata.demo();
            TaskIdentities.install(TaskIdentities.Access.BROWSER);
            previousOptionCatalogCache = OptionCatalogCache.install(BrowserReSyncStorage.fromKey("remotely.option-catalogs"),
                new BrowserReSyncClock());
            host = new BrowserApplicationHost(canvasId, metadata);
            config = new BrowserRemotelyConfigStore();
            Config.setConfigManager(config);
            config.apply();
            host.setMinecraftAssetsEnabled(config::enabled);
            ApplicationHostRegistry.install(host);
            browserWorldMapProvider = new BrowserWorldMapProvider();
            WorldMapProvider.install(browserWorldMapProvider);
            host.ensureTextRenderer();
            adapters = new BrowserAdapters(new BrowserClock(), new BrowserTaskScheduler(), new BrowserHttpTransport(), new BrowserWebSocketTransport());
            BrowserAdapters activeAdapters = adapters;
            BrowserDiagnosticsClient diagnostics = new BrowserDiagnosticsClient(activeAdapters.http());
            BrowserTaskScheduler.setDiagnostics(diagnostics);
            host.setHttpTransport(activeAdapters.http());
            if (!demo) {
                communityProvider = new BrowserCommunityProvider(activeAdapters.http(), host, metadata);
                ReStudioCommunityProviders.install(communityProvider);
            }
            TaskSchedulers.configure(activeAdapters.scheduler());
            serverApi = new BrowserRemotelyServerApi(activeAdapters.http(), activeAdapters.clock(), activeAdapters.scheduler(), activeAdapters.webSocket(), metadata, host);
            Async.installExecutor(activeAdapters.scheduler()::execute, ignored -> { });
            if (communityProvider != null) communityProvider.getAccount().exceptionally(ignored -> null);
            if (!demo) developerAdapter = BrowserDeveloperCapabilityAdapter.install(serverApi);
            BrowserFileExplorerAdapters.install(host, activeAdapters.scheduler());
            TerminalWidget.installModelProvider(TerminalModelProvider.DEFAULT);
            if (developerAdapter != null) TerminalWidget.installSessionProvider(developerAdapter::terminal);
            host.setMarketplaceDetailsProvider(new BrowserMarketplaceDetailsProvider(serverApi, serverApi.browserMarketplace(), activeAdapters.http(), metadata));
            BrowserReSyncIdentityProvider identity = new BrowserReSyncIdentityProvider(metadata);
            ReSyncFrameTransportFactory transportFactory = endpoint -> new ReSyncWebSocketFrameTransport(endpoint, activeAdapters.webSocket(),
                () -> WebSocketOptions.subprotocols(List.of("resync.v1", "resync-ticket." + BrowserLaunchSession.ticket())), false);
            ReSyncCredentialProvider credentials = ReSyncCredentialProvider.browserTicket();
            ReSyncFlowClientFactory flowFactory = (serverId, apiClient, directWsUrl, directApiKey, suppliedTransport, state) -> {
                ReSyncFlowClientContext context = state instanceof ReSyncFlowClientContext resolved ? resolved : ReSyncFlowClientContext.defaults();
                ReSyncFrameTransport frameTransport = suppliedTransport;
                if (frameTransport == null) {
                    String endpoint = directWsUrl == null || directWsUrl.isBlank()
                        ? BrowserLaunchSession.reSyncUrl(serverId) : directWsUrl;
                    frameTransport = transportFactory.create(endpoint);
                }
                BrowserCatalogPublicationDecoder catalogDecoder = new BrowserCatalogPublicationDecoder();
                try {
                    return new ReSyncFlowClient(serverId, frameTransport, directApiKey, context,
                        activeAdapters.scheduler(), activeAdapters.clock(), identity, credentials, false, catalogDecoder);
                } catch (RuntimeException | Error exception) {
                    catalogDecoder.close();
                    throw exception;
                }
            };
            composition = RemotelyComposition.browser(host)
                .configManager(config)
                .storageBreakdownStore(BrowserKeyValueStore.local("remotely:storage-breakdown:v1"), 1_500_000)
                .apiClient(serverApi)
                .scheduler(activeAdapters.scheduler())
                .clock(activeAdapters.clock())
                .reSyncFlowClientFactory(flowFactory)
                .reSyncFrameTransportFactory(transportFactory)
                .reSyncIdentityProvider(identity)
                .serverSettingsCatalogService(new ServerSettingsCatalogService(
                    BrowserMetadataRepository.create(activeAdapters.http())))
                .enable(RemotelyComposition.Capability.PRIMARY_SCREEN)
                .rootScreenFactory(value -> new ServerManagerScreen(null, value))
                .build();
            serverSettingsStorage = composition.serverSettingsRegistryStorageSnapshot();
            ServerSettingsRegistry settingsRegistry = ServerSettingsRegistry.getInstance();
            BundledServerSettingsRegistry.loadInto(settingsRegistry, new BrowserSafeYamlServerSettingsMetadataParser());
            client = new RemotelyClient(composition);
            client.initialize();
            if (demo) host.notify("Reactor Demo", "Shared With Other Visitors. Resets When Everyone Is Idle", ReSyncNotificationLevel.INFO);
            browserRoot = host.getCurrentScreen();
            screenClient = new BrowserReScreenClient(canvasId, host::getCurrentScreen, desktopMode).diagnostics(diagnostics);
            config.applyBrowserAppearance();
            if (!(browserRoot instanceof ServerManagerScreen root)) {
                throw new IllegalStateException("Remotely Server Manager Did Not Start");
            }
            return new Runtime(canvasId, host, client, root, metadata, config, adapters, serverApi,
                client.getFlowManager(), screenClient, communityProvider, previousCommunityProvider,
                browserWorldMapProvider, previousWorldMapProvider, developerAdapter, previousConfig, previousApplicationHost,
                previousScheduler, previousAsync, previousRecentNodes, previousFileExplorerProviders, previousFileExplorerRuntime,
                previousScreenManager, previousApplicationDirectory, previousTextRenderer, previousRemotelyRenderer, previousClient,
                previousMonoFont, previousOs, previousConfigValues, previousDeveloperCapabilities, previousTheme, previousTerminalWidget,
                serverSettingsStorage, previousOptionCatalogCache,
                new CloseState());
        } catch (Throwable failure) {
            restoreRuntime(host, client, screenClient, communityProvider, adapters, serverApi, browserWorldMapProvider, developerAdapter,
                config, previousConfig, previousApplicationHost, previousScheduler, previousAsync, previousApplicationDirectory,
                previousTextRenderer, previousRemotelyRenderer, previousClient, previousMonoFont, previousOs,
                previousCommunityProvider, previousWorldMapProvider, previousRecentNodes, previousFileExplorerProviders,
                previousFileExplorerRuntime, previousScreenManager, browserRoot, previousConfigValues, previousDeveloperCapabilities,
                previousTheme, previousTerminalWidget, serverSettingsStorage, previousOptionCatalogCache, false);
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Remotely Browser Startup Failed", failure);
        }
    }

    public record Runtime(String canvasId, BrowserApplicationHost host, RemotelyClient client,
                          ServerManagerScreen root,
                          BrowserLaunchSession.Metadata launch, BrowserRemotelyConfigStore config, BrowserAdapters adapters,
                          BrowserRemotelyServerApi serverApi, FlowManager flowManager,
                          BrowserReScreenClient screenClient, BrowserCommunityProvider communityProvider,
                          ReStudioCommunityProvider previousCommunityProvider, WorldMapProvider browserWorldMapProvider,
                          WorldMapProvider previousWorldMapProvider, BrowserDeveloperCapabilityAdapter developerAdapter,
                          UiConfigStore previousConfig, ApplicationHost previousApplicationHost, TaskScheduler previousScheduler,
                          Async.Snapshot previousAsync, NodeDiscoveryPreferences.Snapshot previousRecentNodes,
                          FileExplorerProviders.Snapshot previousFileExplorerProviders, FileExplorerRuntime.Snapshot previousFileExplorerRuntime,
                          ScreenManager.Snapshot previousScreenManager, Object previousApplicationDirectory,
                          ITextRenderer previousTextRenderer, ITextRenderer previousRemotelyRenderer,
                          RemotelyClient previousClient, Object previousMonoFont, String previousOs,
                          ConfigSnapshot previousConfigValues, DeveloperCapabilityProviders.Snapshot previousDeveloperCapabilities,
                          ThemeManager.Snapshot previousTheme, TerminalWidget.Snapshot previousTerminalWidget,
                          ServerSettingsRegistry.StorageSnapshot serverSettingsStorage,
                          OptionCatalogCache previousOptionCatalogCache,
                          CloseState closeState) implements AutoCloseable {
        @Override
        public void close() {
            if (!closeState.close()) {
                return;
            }
            restoreRuntime(host, client, screenClient, communityProvider, adapters, serverApi, browserWorldMapProvider, developerAdapter,
                config, previousConfig, previousApplicationHost, previousScheduler, previousAsync, previousApplicationDirectory,
                previousTextRenderer, previousRemotelyRenderer, previousClient, previousMonoFont, previousOs,
                previousCommunityProvider, previousWorldMapProvider, previousRecentNodes, previousFileExplorerProviders,
                previousFileExplorerRuntime, previousScreenManager, root, previousConfigValues, previousDeveloperCapabilities,
                previousTheme, previousTerminalWidget, serverSettingsStorage, previousOptionCatalogCache, true);
        }
    }

    private static void restoreRuntime(BrowserApplicationHost host, RemotelyClient client, BrowserReScreenClient screenClient,
                                       BrowserCommunityProvider communityProvider, BrowserAdapters adapters,
                                       BrowserRemotelyServerApi serverApi,
                                       WorldMapProvider browserWorldMapProvider, BrowserDeveloperCapabilityAdapter developerAdapter,
                                       BrowserRemotelyConfigStore config, UiConfigStore previousConfig,
                                       ApplicationHost previousApplicationHost, TaskScheduler previousScheduler,
                                       Async.Snapshot previousAsync, Object previousApplicationDirectory,
                                       ITextRenderer previousTextRenderer, ITextRenderer previousRemotelyRenderer,
                                       RemotelyClient previousClient, Object previousMonoFont, String previousOs,
                                       ReStudioCommunityProvider previousCommunityProvider, WorldMapProvider previousWorldMapProvider,
                                       NodeDiscoveryPreferences.Snapshot previousRecentNodes,
                                       FileExplorerProviders.Snapshot previousFileExplorerProviders,
                                       FileExplorerRuntime.Snapshot previousFileExplorerRuntime,
                                       ScreenManager.Snapshot previousScreenManager, Screen browserRoot,
                                       ConfigSnapshot previousConfigValues,
                                       DeveloperCapabilityProviders.Snapshot previousDeveloperCapabilities,
                                       ThemeManager.Snapshot previousTheme, TerminalWidget.Snapshot previousTerminalWidget,
                                       ServerSettingsRegistry.StorageSnapshot serverSettingsStorage,
                                       OptionCatalogCache previousOptionCatalogCache,
                                       boolean clearSession) {
        if (serverSettingsStorage != null) {
            serverSettingsStorage.restore();
        }
        if (adapters != null) {
            BrowserTaskScheduler.setDiagnostics(BrowserRuntimeDiagnostics.NONE);
        }
        if (serverApi != null) {
            try {
                serverApi.close();
            } catch (Throwable ignored) {
            }
        }
        if (client != null) {
            try {
                client.shutdownAllTerminals();
            } catch (Throwable ignored) {
            }
        }
        OptionCatalogCache.restore(previousOptionCatalogCache);
        if (host != null) {
            try {
                BrowserFileExplorerAdapters.close(host);
            } catch (Throwable ignored) {
            }
        }
        if (host != null) {
            try {
                host.close();
            } catch (Throwable ignored) {
            }
        }
        if (communityProvider != null) {
            try {
                communityProvider.close();
            } catch (Throwable ignored) {
            }
        }
        if (adapters != null) {
            try {
                adapters.http().cancelAll();
                adapters.webSocket().closeAll();
                adapters.scheduler().cancelAll();
            } catch (Throwable ignored) {
            }
        }
        if (screenClient == null && browserRoot != null && host != null && host.getCurrentScreen() == browserRoot) {
            try {
                browserRoot.close();
            } catch (Throwable ignored) {
            }
        }
        if (screenClient != null) {
            try {
                screenClient.dispose();
            } catch (Throwable ignored) {
            }
        }
        if (clearSession && BrowserLaunchSession.authenticated()) {
            BrowserLaunchSession.clearLocalSession();
        }
        if (ReStudioCommunityProviders.current() == communityProvider) {
            ReStudioCommunityProviders.install(previousCommunityProvider);
        }
        if (WorldMapProvider.current() == browserWorldMapProvider) {
            WorldMapProvider.install(previousWorldMapProvider);
        }
        if (developerAdapter != null) {
            BrowserDeveloperCapabilityAdapter.clearIfCurrent(developerAdapter);
        }
        DeveloperCapabilityProviders.restore(previousDeveloperCapabilities);
        TerminalWidget.restore(previousTerminalWidget);
        FileExplorerProviders.restore(previousFileExplorerProviders);
        FileExplorerRuntime.restore(previousFileExplorerRuntime);
        NodeDiscoveryPreferences.restore(previousRecentNodes);
        ScreenManager.getInstance().restore(previousScreenManager);
        if (ApplicationHostRegistry.current() == host) {
            ApplicationHostRegistry.install(previousApplicationHost);
        }
        if (Config.configManager == config) {
            if (previousConfig == null) {
                Config.configManager = null;
            } else {
                Config.setConfigManager(previousConfig);
            }
        }
        Config.applicationDir = previousApplicationDirectory;
        if (previousConfigValues != null) {
            previousConfigValues.restore();
        }
        ThemeManager.restore(previousTheme);
        if (TaskSchedulers.current() == (adapters == null ? null : adapters.scheduler())) {
            TaskSchedulers.configure(previousScheduler);
        }
        Async.restore(previousAsync);
        TextRenderer.restoreTextRendererAdapter(previousTextRenderer);
        RemotelyClient.tr = previousRemotelyRenderer;
        FontRegistry.MONO_FONT = previousMonoFont;
        RemotelyClient.os = previousOs;
        if (RemotelyClient.INSTANCE == client) {
            RemotelyClient.INSTANCE = previousClient;
        }
    }

    public static final class CloseState {
        private boolean closed;

        private boolean close() {
            if (closed) {
                return false;
            }
            closed = true;
            return true;
        }
    }

    public record BrowserAdapters(BrowserClock clock, BrowserTaskScheduler scheduler, BrowserHttpTransport http,
                                  BrowserWebSocketTransport webSocket) {
    }

    private static final class ConfigSnapshot {
        private final Object reStudioDir = Config.reStudioDir;
        private final Object applicationDir = Config.applicationDir;
        private final boolean isDev = Config.isDev;
        private final boolean shadow = Config.shadow;
        private final boolean wallpaper = Config.wallpaper;
        private final boolean coverMinecraftPanorama = Config.coverMinecraftPanorama;
        private final BackgroundSource backgroundSource = Config.backgroundSource;
        private final String customBackgroundPath = Config.customBackgroundPath;
        private final String minecraftPanoramaVersion = Config.minecraftPanoramaVersion;
        private final float minecraftPanoramaSpeed = Config.minecraftPanoramaSpeed;
        private final float minecraftPanoramaBlur = Config.minecraftPanoramaBlur;
        private final boolean randomMinecraftPanorama = Config.randomMinecraftPanorama;
        private final boolean desktopMode = Config.desktopMode;
        private final Boolean desktopModeOverride = Config.getDesktopModeOverride();
        private final Identifier windowsBackgroundId = Config.windowsBackgroundId;
        private final int windowsBackgroundWidth = Config.windowsBackgroundWidth;
        private final int windowsBackgroundHeight = Config.windowsBackgroundHeight;
        private final List<Identifier> minecraftPanorama = Config.minecraftPanorama;
        private final Object remotelyDir = Config.remotelyDir;
        private final float globalScaleFactor = Config.globalScaleFactor;
        private final float targetScaleFactor = Config.targetScaleFactor;
        private final boolean lastRounding = Config.lastRounding;
        private final long currentTime = Config.currentTime;
        private final float deltaTime = Config.deltaTime;
        private final float globalExpandSpeed = Config.globalExpandSpeed;
        private final float scaleAnimationSpeed = Config.scaleAnimationSpeed;
        private final float animScaleFactor = Config.animScaleFactor;
        private final float globalScrollSpeed = Config.globalScrollSpeed;
        private final float globalMovementSpeed = Config.globalMovementSpeed;
        private final float consoleScrollSpeed = Config.consoleScrollSpeed;
        private final boolean animationsEnabled = Config.animationsEnabled;
        private final boolean customMouse = Config.customMouse;
        private final float mouseSize = Config.mouseSize;
        private final float tailSize = Config.tailSize;
        private final float tailFollowSpeed = Config.tailFollowSpeed;
        private final long lastFrameTime = Config.lastFrameTime;
        private final boolean obfuscate = Config.obfuscate;
        private final float colorTransitionSpeed = Config.colorTransitionSpeed;
        private final String currentThemeName = Config.currentThemeName;
        private final boolean soundEnableSfx = Sound.enableSFX;
        private final int soundPitchVariation = Sound.pitchVariation;
        private final int soundVolume = Sound.soundVolume;
        private final boolean[] soundFlags = captureSoundFlags();

        private static ConfigSnapshot capture() {
            return new ConfigSnapshot();
        }

        private void restore() {
            Config.reStudioDir = reStudioDir;
            Config.applicationDir = applicationDir;
            Config.isDev = isDev;
            Config.shadow = shadow;
            Config.wallpaper = wallpaper;
            Config.coverMinecraftPanorama = coverMinecraftPanorama;
            Config.backgroundSource = backgroundSource;
            Config.customBackgroundPath = customBackgroundPath;
            Config.minecraftPanoramaVersion = minecraftPanoramaVersion;
            Config.minecraftPanoramaSpeed = minecraftPanoramaSpeed;
            Config.minecraftPanoramaBlur = minecraftPanoramaBlur;
            Config.randomMinecraftPanorama = randomMinecraftPanorama;
            Config.setDesktopModeOverride(desktopModeOverride);
            Config.desktopMode = desktopMode;
            Config.windowsBackgroundId = windowsBackgroundId;
            Config.windowsBackgroundWidth = windowsBackgroundWidth;
            Config.windowsBackgroundHeight = windowsBackgroundHeight;
            Config.minecraftPanorama = minecraftPanorama;
            Config.remotelyDir = remotelyDir;
            Config.globalScaleFactor = globalScaleFactor;
            Config.targetScaleFactor = targetScaleFactor;
            Config.lastRounding = lastRounding;
            Config.currentTime = currentTime;
            Config.deltaTime = deltaTime;
            Config.globalExpandSpeed = globalExpandSpeed;
            Config.scaleAnimationSpeed = scaleAnimationSpeed;
            Config.animScaleFactor = animScaleFactor;
            Config.globalScrollSpeed = globalScrollSpeed;
            Config.globalMovementSpeed = globalMovementSpeed;
            Config.consoleScrollSpeed = consoleScrollSpeed;
            Config.animationsEnabled = animationsEnabled;
            Config.customMouse = customMouse;
            Config.mouseSize = mouseSize;
            Config.tailSize = tailSize;
            Config.tailFollowSpeed = tailFollowSpeed;
            Config.lastFrameTime = lastFrameTime;
            Config.obfuscate = obfuscate;
            Config.colorTransitionSpeed = colorTransitionSpeed;
            Config.currentThemeName = currentThemeName;
            Sound.enableSFX = soundEnableSfx;
            Sound.pitchVariation = soundPitchVariation;
            Sound.soundVolume = soundVolume;
            restoreSoundFlags(soundFlags);
        }

        private static boolean[] captureSoundFlags() {
            boolean[] flags = new boolean[Sound.values().length];
            for (Sound sound : Sound.values()) {
                flags[sound.ordinal()] = soundFlag(sound);
            }
            return flags;
        }

        private static void restoreSoundFlags(boolean[] flags) {
            if (flags == null) {
                return;
            }
            for (Sound sound : Sound.values()) {
                if (sound.ordinal() < flags.length) {
                    setSoundFlag(sound, flags[sound.ordinal()]);
                }
            }
        }

        private static boolean soundFlag(Sound sound) {
            return switch (sound) {
                case CLICK -> Sound.soundCLICK;
                case HOVER -> Sound.soundHOVER;
                case RIGHTCLICK -> Sound.soundRIGHTCLICK;
                case SELECT -> Sound.soundSELECT;
                case START -> Sound.soundSTART;
                case STOP -> Sound.soundSTOP;
                case INFO -> Sound.soundINFO;
                case WARN -> Sound.soundWARN;
                case ERROR -> Sound.soundERROR;
                case SUCCESS -> Sound.soundSUCCESS;
                case CREATE -> Sound.soundCREATE;
                case DELETE -> Sound.soundDELETE;
                case COPY -> Sound.soundCOPY;
                case PASTE -> Sound.soundPASTE;
                case UNDO -> Sound.soundUNDO;
                case REDO -> Sound.soundREDO;
                case SEND -> Sound.soundSEND;
                case RECEIVE -> Sound.soundRECEIVE;
                case RECEIVEERROR -> Sound.soundRECEIVEERROR;
                case SWITCHTAB -> Sound.soundSWITCHTAB;
                case CLOSETAB -> Sound.soundCLOSETAB;
                case TERMINAL -> Sound.soundTERMINAL;
                case FILEEXPLORER -> Sound.soundFILEEXPLORER;
                case FILEEDITOR -> Sound.soundFILEEDITOR;
                case SERVERMANAGER -> Sound.soundSERVERMANAGER;
                case PANEL -> Sound.soundPANEL;
                case SEARCH -> Sound.soundSEARCH;
                case SAVE -> Sound.soundSAVE;
                case SCREEN -> Sound.soundSCREEN;
            };
        }

        private static void setSoundFlag(Sound sound, boolean enabled) {
            switch (sound) {
                case CLICK -> Sound.soundCLICK = enabled;
                case HOVER -> Sound.soundHOVER = enabled;
                case RIGHTCLICK -> Sound.soundRIGHTCLICK = enabled;
                case SELECT -> Sound.soundSELECT = enabled;
                case START -> Sound.soundSTART = enabled;
                case STOP -> Sound.soundSTOP = enabled;
                case INFO -> Sound.soundINFO = enabled;
                case WARN -> Sound.soundWARN = enabled;
                case ERROR -> Sound.soundERROR = enabled;
                case SUCCESS -> Sound.soundSUCCESS = enabled;
                case CREATE -> Sound.soundCREATE = enabled;
                case DELETE -> Sound.soundDELETE = enabled;
                case COPY -> Sound.soundCOPY = enabled;
                case PASTE -> Sound.soundPASTE = enabled;
                case UNDO -> Sound.soundUNDO = enabled;
                case REDO -> Sound.soundREDO = enabled;
                case SEND -> Sound.soundSEND = enabled;
                case RECEIVE -> Sound.soundRECEIVE = enabled;
                case RECEIVEERROR -> Sound.soundRECEIVEERROR = enabled;
                case SWITCHTAB -> Sound.soundSWITCHTAB = enabled;
                case CLOSETAB -> Sound.soundCLOSETAB = enabled;
                case TERMINAL -> Sound.soundTERMINAL = enabled;
                case FILEEXPLORER -> Sound.soundFILEEXPLORER = enabled;
                case FILEEDITOR -> Sound.soundFILEEDITOR = enabled;
                case SERVERMANAGER -> Sound.soundSERVERMANAGER = enabled;
                case PANEL -> Sound.soundPANEL = enabled;
                case SEARCH -> Sound.soundSEARCH = enabled;
                case SAVE -> Sound.soundSAVE = enabled;
                case SCREEN -> Sound.soundSCREEN = enabled;
            }
        }
    }
}
