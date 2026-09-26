package redxax.oxy.remotely.config;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.discord.DiscordRpcBridge;
import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.server.ServerScreenHost;
import redxax.oxy.remotely.ui.settings.controllers.CollaborationSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopPackContentSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopReProxySettingsCapability;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerClientSettingsProvider;
import restudio.rebase.Rebase;
import restudio.rebase.platform.jvm.JvmAppearanceSettingsProvider;
import restudio.rebase.platform.jvm.JvmBackupSettingsProvider;
import restudio.rebase.platform.jvm.JvmInstanceStorageSettingsProvider;
import restudio.rebase.platform.jvm.JvmJavaSettingsProvider;
import restudio.rebase.platform.jvm.JvmLogSettingsProvider;
import restudio.rebase.platform.jvm.JvmLspSettingsProvider;
import restudio.rebase.platform.jvm.JvmMinecraftAssetsSettingsProvider;
import restudio.rebase.platform.jvm.JvmPresetSettingsProvider;
import restudio.rebase.platform.jvm.JvmReStudioAccountSettingsProvider;
import restudio.rebase.platform.jvm.JvmThemeSettingsProvider;
import restudio.rebase.settings.controllers.SettingsActionCapability;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.SettingsScreen;

import java.util.concurrent.atomic.AtomicBoolean;

public final class DesktopSettingsScreenFactory {
    private DesktopSettingsScreenFactory() {
    }

    public static SettingsScreen createGlobalSettingsScreen(ReScreen parent, RemotelyConfigManager config) {
        JvmInstanceStorageSettingsProvider storage = new JvmInstanceStorageSettingsProvider(config);
        RemotelyClient client = RemotelyClient.INSTANCE;
        ServerScreenHost serverHost = client == null || client.getHost() == null
                ? null : client.getHost().serverScreenHost(client);
        SettingsScreen[] settingsScreen = new SettingsScreen[1];
        AtomicBoolean listenerRegistered = new AtomicBoolean();
        Runnable authStateListener = () -> ScreenManager.getInstance().execute(() -> {
            if (settingsScreen[0] != null) settingsScreen[0].refreshTab("Servers");
        });
        SettingsScreenRuntime runtime = new SettingsScreenRuntime() {
            public void opened() {
                DiscordRpcBridge.setGlobalSettingsActive();
            }

            public void displayed(SettingsScreen screen) {
                if (listenerRegistered.compareAndSet(false, true)) {
                    settingsScreen[0] = screen;
                    if (serverHost != null) serverHost.addAuthStateListener(authStateListener);
                    Rebase.get().getJavaManager().ensureRuntimes().thenRun(() -> ScreenManager.getInstance().execute(() -> {
                        if (settingsScreen[0] == screen) screen.refreshTab("Java");
                    }));
                }
            }

            public void afterApply() {
                DiscordRpcBridge.reloadSettings();
            }

            public void cleanup() {
                if (listenerRegistered.compareAndSet(true, false)) {
                    settingsScreen[0] = null;
                    if (serverHost != null) serverHost.removeAuthStateListener(authStateListener);
                }
            }
        };
        GlobalSettingsProviders providers = new GlobalSettingsProviders(
                new JvmAppearanceSettingsProvider(config),
                new JvmThemeSettingsProvider(),
                new DesktopServerClientSettingsProvider(config),
                storage,
                true,
                new DesktopReProxySettingsCapability(),
                null,
                SettingsActionCapability.supported("settings.discord-rpc"),
                new DesktopPackContentSettingsProvider(),
                new JvmJavaSettingsProvider(),
                new JvmLspSettingsProvider(config),
                new JvmMinecraftAssetsSettingsProvider(config),
                null,
                new JvmBackupSettingsProvider(parent, null),
                new JvmPresetSettingsProvider(),
                new JvmReStudioAccountSettingsProvider(),
                new CollaborationSettingsProvider() {},
                new JvmLogSettingsProvider(),
                null,
                runtime);
        return SettingsScreenFactory.createGlobalSettingsScreen(parent, config, providers);
    }
}
