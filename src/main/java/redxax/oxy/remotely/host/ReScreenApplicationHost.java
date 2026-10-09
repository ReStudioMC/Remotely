package redxax.oxy.remotely.host;

import redxax.oxy.remotely.RemotelyInit;

import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.discord.DiscordRpcBridge;
import redxax.oxy.remotely.data.flow.DesktopFlowManagerUiAdapter;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.FlowManagerUiAdapter;
import redxax.oxy.remotely.data.flow.ReSyncServerIdentity;
import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import redxax.oxy.remotely.ui.integrations.luckperms.LuckPermsDashboardScreen;
import redxax.oxy.remotely.ui.integrations.luckperms.DesktopLuckPermsServerIcons;
import redxax.oxy.remotely.ui.server.ServerDetailsScreen;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientConfiguration;
import redxax.oxy.remotely.data.flow.ReSyncFlowClientFactory;
import redxax.oxy.remotely.data.flow.ReSyncNotificationLevel;
import redxax.oxy.remotely.data.flow.DesktopReSyncFlowClientConfiguration;
import redxax.oxy.remotely.flow.ui.DesktopReSyncProvisioningAdapter;
import restudio.rebase.minecraft.assets.MinecraftAssetsManager;
import restudio.rebase.instance.Instance;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.restudio.marketplace.MarketplaceProviders;
import restudio.rebase.terminal.ExecutorServiceManager;
import restudio.rebase.ui.screens.explorer.FileExplorerScreen;
import restudio.rebase.ui.screens.marketplace.MarketplaceDetailsScreen;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rescreen.config.Config;
import restudio.rescreen.game.MinecraftGameAssets;
import restudio.rescreen.game.SourceMinecraftGameAssets;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.FileUtils;
import restudio.rescreen.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class ReScreenApplicationHost implements ApplicationHost {
    private final ScreenManager sm = ScreenManager.getInstance();
    private volatile MinecraftGameAssets gameAssets;
    private RemotelyClient serverScreenClient;
    private DesktopServerHost serverScreenHost;

    @Override
    public void setScreen(Screen screen) {
        sm.setScreen(screen);
    }

    @Override
    public Screen getCurrentScreen() {
        return sm.getCurrentScreen();
    }

    @Override
    public void ensureTextRenderer() {
        RemotelyClient.tr = TextRenderer.getTr();
    }

    @Override
    public MinecraftGameAssets getGameAssets() {
        MinecraftGameAssets current = gameAssets;
        if (Config.configManager instanceof RemotelyConfigManager remotelyConfigManager) {
            if (!(current instanceof SourceMinecraftGameAssets)) {
                current = new SourceMinecraftGameAssets(MinecraftAssetsManager.get(remotelyConfigManager).getAssetSource());
                gameAssets = current;
            }
            return current;
        }
        if (current == null) {
            current = MinecraftGameAssets.EMPTY;
            gameAssets = current;
        }
        return current;
    }

    @Override
    public Object getFontIdentifier(String namespace, String path) {
        if (namespace == null || namespace.isEmpty()) namespace = "minecraft";
        if (path == null) path = "";
        String ns = namespace.toLowerCase();
        String p = path.startsWith("/") ? path.substring(1) : path;
        p = p.toLowerCase();
        return ns + ":" + p;
    }

    @Override
    public void openParentScreen(Screen currentScreen, Object parent) {
        if (parent instanceof Screen) {
            sm.setScreen((Screen) parent);
        }
    }

    @Override
    public boolean shouldCloseRootScreen() {
        return false;
    }

    @Override
    public void openTerminal(Object parent, Object instance, RemotelyClient client) {
        if (client == null) return;
        if (instance instanceof Instance value) {
            setScreen(new ServerDetailsScreen(parent, client, value));
        } else {
            setScreen(new ServerDetailsScreen(parent, client));
        }
    }

    @Override
    public void openFileExplorer(Object parent, Object path, RemotelyClient client) {
        if (!(path instanceof Path localPath) || client == null) {
            openRemoteFiles(parent, path);
            return;
        }
        Screen screenParent = parent instanceof Screen value ? value : null;
        Object dataDirectory = Config.applicationDir instanceof Path applicationDirectory
                ? applicationDirectory.resolve("data") : null;
        setScreen(new FileExplorerScreen(screenParent, null, localPath, dataDirectory, false) {
            @Override
            public String getDesktopAppId() {
                return "file-explorer";
            }

            @Override
            public String getDesktopAppTitle() {
                return "File Explorer";
            }

            @Override
            public String getDesktopAppIconPath() {
                return "explorer.png";
            }

            @Override
            public void close() {
                if (isDesktopWindow()) super.close();
                else openParentScreen(this, parent);
            }
        });
    }

    @Override
    public void openInstanceFiles(Object parent, Object instance, RemotelyClient client) {
        if (!(instance instanceof Instance value) || value.getPath() == null || client == null) {
            openRemoteFiles(parent, instance);
            return;
        }
        Screen screenParent = parent instanceof Screen current ? current : null;
        Path path = Path.of(value.getPath());
        Object dataDirectory = Config.applicationDir instanceof Path applicationDirectory
                ? applicationDirectory.resolve("data") : null;
        setScreen(new FileExplorerScreen(screenParent, value, path, dataDirectory, false) {
            @Override
            public String getDesktopAppId() {
                return "file-explorer";
            }

            @Override
            public String getDesktopAppTitle() {
                return "File Explorer";
            }

            @Override
            public String getDesktopAppIconPath() {
                return "explorer.png";
            }

            @Override
            public void close() {
                if (isDesktopWindow()) super.close();
                else openParentScreen(this, parent);
            }
        });
    }

    @Override
    public void openServerTwin(Object parent, Object instance, RemotelyClient client) {
        if (client != null && instance instanceof Instance value) {
            setScreen(new ServerDetailsScreen(parent, client, value));
        }
    }

    @Override
    public void openReSyncStudio(Object parent, Object instance, Object serverView, RemotelyClient client) {
        if (client == null || client.getFlowManager() == null) return;
        ServerModels.ClientServerView view = serverView instanceof ServerModels.ClientServerView value ? value : null;
        if (instance instanceof Instance value) {
            String serverId = value.getInstanceId();
            String loader = value.getModLoader() == null ? "" : value.getModLoader().name();
            String title = value.getName();
            if (view != null) {
                if (view.identifier != null && !view.identifier.isBlank()) serverId = view.identifier;
                if (view.loader != null && !view.loader.isBlank()) loader = view.loader;
                if (view.name != null && !view.name.isBlank()) title = view.name;
            }
            setStudioActivity(value, title, "Studio");
            client.getFlowManager().openReSyncStudio(serverId, view, loader, title);
            return;
        }
        if (view == null) return;
        ReSyncServerIdentity identity = ReSyncServerIdentity.from(null, view);
        if (!identity.isReStudioTarget() || !identity.present()) return;
        String loader = view.loader == null ? "" : view.loader;
        String title = view.name == null ? "" : view.name;
        setStudioActivity(null, title, "Studio");
        client.getFlowManager().openReSyncStudio(identity.serverId(), view, loader, title);
    }

    @Override
    public void shutdownLocalTerminals() {
        TerminalWidget.shutdownAll();
        ExecutorServiceManager.shutdownSharedExecutors();
    }

    @Override
    public ServerScreenHost serverScreenHost(RemotelyClient client) {
        if (client == null) return ServerScreenHost.of(this);
        if (serverScreenHost == null || serverScreenClient != client) {
            serverScreenClient = client;
            serverScreenHost = new DesktopServerHost(client);
        }
        return serverScreenHost;
    }

    @Override
    public void execute(Runnable task) {
        sm.execute(task);
    }

    @Override
    public void notify(String title, String message, ReSyncNotificationLevel level) {
        sm.execute(() -> new Notification(title, message, switch (level) {
            case WARN -> Notification.Type.WARN;
            case ERROR -> Notification.Type.ERROR;
            case SUCCESS -> Notification.Type.SUCCESS;
            default -> Notification.Type.INFO;
        }));
    }

    @Override
    public void openMarketplaceListing(String marketplaceSlug, String listingSlug, boolean control) {
        sm.execute(() -> sm.setScreen(new MarketplaceDetailsScreen(sm.getCurrentScreen(), marketplaceSlug, listingSlug,
                control, MarketplaceProviders.current())));
    }

    @Override
    public void openPermissionManager(Screen parent, Object client) {
        if (client instanceof ReSyncLuckPermsClient permissions) sm.setScreen(new LuckPermsDashboardScreen(parent, permissions, new DesktopLuckPermsServerIcons()));
        else notify("Permissions", "Permission Management Unavailable", ReSyncNotificationLevel.WARN);
    }

    @Override
    public void chooseImage(String title, Consumer<HostImage> callback) {
        FileUtils.pickImageFileAsync(title, path -> sm.execute(() -> {
            if (callback == null || path == null) return;
            try {
                String fileName = path.getFileName() == null ? "image.bin" : path.getFileName().toString();
                String contentType = Files.probeContentType(path);
                callback.accept(new HostImage(fileName, contentType, Files.readAllBytes(path)));
            } catch (Exception ignored) {
            }
        }));
    }

    @Override
    public Identifier registerRemoteImage(String source) {
        return source == null || source.isBlank() ? null : sm.imageAssets().registerRemoteImage(source);
    }

    @Override
    public void releaseRemoteImage(Identifier image) {
        if (image != null) sm.imageAssets().releaseImage(image);
    }

    @Override
    public String getGameVersion() {
        return null;
    }

    @Override
    public void setClipboard(String text) {
        sm.getClipboardHandler().setClipboard(text);
    }

    @Override
    public boolean openExternal() {
        return DesktopProcessLauncher.openExternal();
    }

    @Override
    public boolean supportsDesktopIntegrations() {
        return sm.runtime().supportsDesktopIntegrations();
    }

    @Override
    public void setManagerActivity() {
        DiscordRpcBridge.setManagerActive();
    }

    @Override
    public void setLocalTerminalActivity() {
        DiscordRpcBridge.setLocalTerminalActive();
    }

    @Override
    public void setServerActivity(Object instance, String view) {
        if (instance instanceof Instance value) DiscordRpcBridge.setServerActive(value.getInstanceId(), view);
    }

    @Override
    public void setStudioActivity(Object instance, String title, String subtitle) {
        if (instance instanceof Instance value) DiscordRpcBridge.setReSyncStudioActive(value.getInstanceId(), title, subtitle);
        else DiscordRpcBridge.setReSyncStudioActive(null, title, subtitle);
    }

    @Override
    public void updateServerActivityMetrics(Object instance, int onlinePlayers, int maxPlayers, int cpuPercent,
                                             int memoryUsedMb, int memoryMaxMb) {
        updateServerActivityMetrics(instance, onlinePlayers, maxPlayers, 0, cpuPercent, memoryUsedMb, memoryMaxMb);
    }

    @Override
    public void updateServerActivityMetrics(Object instance, int onlinePlayers, int maxPlayers, long uptimeMs,
                                             int cpuPercent, int memoryUsedMb, int memoryMaxMb) {
        if (instance instanceof Instance value) DiscordRpcBridge.updateServerMetrics(value.getInstanceId(), onlinePlayers, uptimeMs,
                cpuPercent, toBytes(memoryUsedMb), toBytes(memoryMaxMb));
    }

    private static long toBytes(int megabytes) {
        return megabytes <= 0 ? 0 : megabytes * 1024L * 1024L;
    }

    @Override
    public void shutdownDesktopIntegrations() {
        if (serverScreenHost != null) serverScreenHost.closePluginForwarding();
        RemotelyInit.closeReProxy();
        DiscordRpcBridge.shutdown();
    }

    @Override
    public FlowManagerUiAdapter flowManagerUiAdapter() {
        return supportsDesktopIntegrations() ? new DesktopFlowManagerUiAdapter() : FlowManagerUiAdapter.generic();
    }

    @Override
    public ReSyncFlowClientConfiguration configureFlowClient(Object client, FlowManager manager,
                                                              ReSyncFlowClientFactory requestedFactory,
                                                              ReSyncFlowClientConfiguration defaults) {
        if (!supportsDesktopIntegrations() || !(client instanceof RemotelyClient remotelyClient)) return defaults;
        return DesktopReSyncFlowClientConfiguration.create(remotelyClient, manager, requestedFactory);
    }

    @Override
    public Object provisioningAdapter() {
        return supportsDesktopIntegrations() ? new DesktopReSyncProvisioningAdapter() : null;
    }

    @Override
    public String getGameUserName() {
        return null;
    }

    @Override
    public String getGameUUID() {
        return null;
    }
}
