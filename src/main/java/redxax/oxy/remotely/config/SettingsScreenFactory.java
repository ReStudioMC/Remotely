package redxax.oxy.remotely.config;

import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.CollaborationSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.PackContentSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ReProxySettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerClientSettingsController;
import restudio.rebase.settings.controllers.AppearanceSettingsController;
import restudio.rebase.settings.controllers.BackupSettingsController;
import restudio.rebase.settings.controllers.ExplorerSettingsController;
import restudio.rebase.settings.controllers.InstanceStorageSettingsController;
import restudio.rebase.settings.controllers.JavaManagerController;
import restudio.rebase.settings.controllers.LogSettingsController;
import restudio.rebase.settings.controllers.LspSettingsController;
import restudio.rebase.settings.controllers.MinecraftAssetsSettingsController;
import restudio.rebase.settings.controllers.PresetSettingsController;
import restudio.rebase.settings.controllers.ReStudioAccountSettingsController;
import restudio.rebase.settings.controllers.SoundSettingsController;
import restudio.rebase.settings.controllers.ThemeController;
import restudio.rescreen.ui.desktop.DesktopWindowBehaviorProvider;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.SettingsScreen;
import restudio.rescreen.ui.settings.controllers.DevelopmentSettingsController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class SettingsScreenFactory {
    private SettingsScreenFactory() {
    }

    public static SettingsScreen createGlobalSettingsScreen(ReScreen parent, RemotelyConfigStore config, GlobalSettingsProviders providers) {
        return createGlobalSettingsScreen(parent, config, providers, null);
    }

    public static SettingsScreen createGlobalSettingsScreen(ReScreen parent, RemotelyConfigStore config, GlobalSettingsProviders providers, String initialTab) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(providers, "providers");
        SettingsScreenRuntime runtime = Objects.requireNonNull(providers.runtime(), "providers.runtime");
        AppearanceSettingsController appearance = new AppearanceSettingsController(config, providers.appearance());
        ThemeController theme = new ThemeController(providers.theme());
        SoundSettingsController sounds = new SoundSettingsController(config);
        ServerClientSettingsController servers = new ServerClientSettingsController(config, providers.servers());
        InstanceStorageSettingsController storage = new InstanceStorageSettingsController(providers.storage());
        ReProxySettingsController reProxy = new ReProxySettingsController(providers.reProxy());
        DiscordRpcSettingsController discord = new DiscordRpcSettingsController(providers.discordInstance(), config, providers.discord());
        PackContentSettingsController packContent = new PackContentSettingsController(config, providers.packContent());
        JavaManagerController java = new JavaManagerController(providers.java());
        LspSettingsController lsp = new LspSettingsController(providers.lsp());
        MinecraftAssetsSettingsController minecraftAssets = new MinecraftAssetsSettingsController(providers.minecraftAssets());
        ExplorerSettingsController explorer = providers.explorerUnavailableReason() == null
                ? new ExplorerSettingsController(config)
                : new ExplorerSettingsController(config, providers.explorerUnavailableReason());
        BackupSettingsController backups = new BackupSettingsController(parent, providers.backups());
        PresetSettingsController presets = new PresetSettingsController(parent, providers.presets());
        ReStudioAccountSettingsController account = new ReStudioAccountSettingsController(providers.account());
        CollaborationSettingsController collaboration = new CollaborationSettingsController(config, providers.collaboration());
        LogSettingsController logs = new LogSettingsController(providers.logs());
        DevelopmentSettingsController development = new DevelopmentSettingsController(config);
        Map<String, Supplier<List<Setting>>> settingsByTab = new LinkedHashMap<>();
        settingsByTab.put("Appearance", appearance::getSettings);
        settingsByTab.put("Theme", () -> {
            List<Setting> settings = new ArrayList<>(theme.getThemeSettings());
            settings.addAll(theme.getAccentSettings());
            return settings;
        });
        settingsByTab.put("Sounds", sounds::getSettings);
        settingsByTab.put("Servers", servers::getSettings);
        settingsByTab.put("Storage", storage::getSettings);
        settingsByTab.put("ReProxy", reProxy::getSettings);
        settingsByTab.put("Discord", discord::getSettings);
        settingsByTab.put("Pack Content", packContent::getSettings);
        settingsByTab.put("Java", java::getSettings);
        settingsByTab.put("LSP", lsp::getSettings);
        settingsByTab.put("Minecraft Assets", minecraftAssets::getSettings);
        settingsByTab.put("File Explorer", explorer::getSettings);
        settingsByTab.put("Backups", backups::getSettings);
        settingsByTab.put("Presets", presets::getSettings);
        settingsByTab.put("Account", () -> {
            List<Setting> settings = new ArrayList<>(account.getAccountSettings());
            settings.addAll(collaboration.getSettings());
            return settings;
        });
        settingsByTab.put("About", account::getAboutSettings);
        if (providers.connections() != null) settingsByTab.put("Connections", providers.connections());
        settingsByTab.put("Logs", logs::getSettings);
        settingsByTab.put("Development", () -> {
            List<Setting> settings = new ArrayList<>(development.getSettings());
            settings.addAll(account.getDeveloperSettings());
            return settings;
        });
        boolean[] active = {false};
        Runnable cleanup = () -> {
            if (active[0]) {
                active[0] = false;
                backups.cleanup();
                account.cleanup();
                reProxy.cleanup();
                runtime.cleanup();
            }
        };
        return new SettingsScreen(parent, "Remotely Settings", settingsByTab, () -> {
            if (providers.applyStorageBeforeSave()) storage.apply();
            runtime.beforeSave();
            config.save();
            config.apply();
            runtime.afterApply();
        }, cleanup, "Membership".equals(initialTab) ? "Account" : initialTab) {
            public String getDesktopAppId() { return "global-settings"; }
            public String getDesktopAppTitle() { return "Settings"; }
            public String getDesktopAppIconPath() { return "remotely.png"; }

            @Override
            public void onDisplayed() {
                super.onDisplayed();
                if (!active[0]) {
                    active[0] = true;
                    reProxy.activate();
                    runtime.opened();
                    runtime.displayed(this);
                }
            }

            @Override
            public void removed() {
                cleanup.run();
                super.removed();
            }

            @Override
            public DesktopWindowBehavior getDesktopWindowBehavior() {
                return DesktopWindowBehaviorProvider.DesktopWindowBehavior.SINGLETON;
            }
        };
    }
}
