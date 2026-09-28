package redxax.oxy.remotely;

import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.host.ReScreenApplicationHost;
import redxax.oxy.remotely.host.DesktopServerHost;
import redxax.oxy.remotely.ui.server.ServerManagerScreen;
import redxax.oxy.remotely.ui.widgets.ServerPulseTitleExtension;
import redxax.oxy.remotely.network.DesktopNetworkManager;
import redxax.oxy.remotely.network.DesktopNetworkAccess;
import restudio.rebase.Rebase;
import restudio.rebase.minecraft.assets.MinecraftAssetsManager;
import restudio.rebase.update.UpdateAvailablePopup;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.lwjgl.DrawContextLwjgl;
import restudio.rescreen.ReStudioEntry;
import restudio.rescreen.config.Config;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.WindowTitleExtension;
import restudio.rescreen.util.Identifier;

import java.util.List;

public class RemotelyEntry extends ReStudioEntry {
    private List<WindowTitleExtension> windowTitleExtensions = List.of();
    private RemotelySession session;

    @Override
    public void init() {
        if (session == null || !session.isInitialized()) {
            session = RemotelyInit.startSession(DesktopRemotelyComposition.create(new ReScreenApplicationHost()).build());
            if (Rebase.get().getConfigManager().isUpdateCheckOnStartup()) {
                Rebase.get().getApplicationUpdateManager().checkForUpdates().thenAccept(updateOpt -> updateOpt.ifPresent(releaseInfo -> ScreenManager.getInstance().execute(() -> UpdateAvailablePopup.show(releaseInfo, Rebase.get().getApplicationUpdateManager()))));
            }
        }
        RemotelyClient client = session.client();
        windowTitleExtensions = List.of(new ServerPulseTitleExtension(DesktopNetworkAccess.manager(client)));

        client.getHost().ensureTextRenderer();

        setupScreens();
    }

    @Override
    public String getIconResourcePath() {
        return "/assets/restudio/logos/Reemotely.png";
    }

    @Override
    public Identifier getIconIdentifier() {
        return Identifier.of("restudio", "logos/Reemotely.png");
    }

    @Override
    public String getWindowTitle() {
        return "Remotely";
    }

    @Override
    public boolean isWindowTitleStatic() {
        return true;
    }

    @Override
    public List<WindowTitleExtension> getWindowTitleExtensions() {
        return windowTitleExtensions;
    }

    @Override
    public IDrawContext getDrawContext() {
        return new DrawContextLwjgl(MinecraftAssetsManager.get(getRemotelyConfigManager()).getAssetSource());
    }

    @Override
    protected void setupScreens() {
        ScreenManager screenManager = ScreenManager.getInstance();
        RemotelyClient client = session.client();
        ServerManagerScreen superScreen = new ServerManagerScreen(null, client);

        screenManager.setScreen(superScreen);
        requestMinecraftAssetsStartupProvision();
    }

    private void requestMinecraftAssetsStartupProvision() {
        Screen current = ScreenManager.getInstance().getCurrentScreen();
        ReScreen screen = current instanceof ReScreen reScreen ? reScreen : null;
        MinecraftAssetsManager.get(getRemotelyConfigManager()).requestStartupProvision(screen);
    }

    private RemotelyConfigManager getRemotelyConfigManager() {
        if (Config.configManager instanceof RemotelyConfigManager remotelyConfigManager) {
            return remotelyConfigManager;
        }
        throw new IllegalStateException("Remotely config manager not initialized");
    }
}
