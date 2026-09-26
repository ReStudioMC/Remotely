package redxax.oxy.remotely.config;

import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.CollaborationSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.PackContentSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ReProxySettingsCapability;
import redxax.oxy.remotely.ui.settings.controllers.ServerClientSettingsProvider;
import restudio.rebase.settings.controllers.AppearanceSettingsProvider;
import restudio.rebase.settings.controllers.BackupSettingsProvider;
import restudio.rebase.settings.controllers.InstanceStorageSettingsProvider;
import restudio.rebase.settings.controllers.JavaSettingsProvider;
import restudio.rebase.settings.controllers.LogSettingsProvider;
import restudio.rebase.settings.controllers.LspSettingsProvider;
import restudio.rebase.settings.controllers.MinecraftAssetsSettingsProvider;
import restudio.rebase.settings.controllers.PresetSettingsProvider;
import restudio.rebase.settings.controllers.ReStudioAccountSettingsProvider;
import restudio.rebase.settings.controllers.SettingsActionCapability;
import restudio.rebase.settings.controllers.ThemeSettingsProvider;
import restudio.rescreen.ui.settings.Setting;

import java.util.List;
import java.util.function.Supplier;

public record GlobalSettingsProviders(
        AppearanceSettingsProvider appearance,
        ThemeSettingsProvider theme,
        ServerClientSettingsProvider servers,
        InstanceStorageSettingsProvider storage,
        boolean applyStorageBeforeSave,
        ReProxySettingsCapability reProxy,
        DiscordRpcSettingsController.InstanceSettings discordInstance,
        SettingsActionCapability discord,
        PackContentSettingsProvider packContent,
        JavaSettingsProvider java,
        LspSettingsProvider lsp,
        MinecraftAssetsSettingsProvider minecraftAssets,
        String explorerUnavailableReason,
        BackupSettingsProvider backups,
        PresetSettingsProvider presets,
        ReStudioAccountSettingsProvider account,
        CollaborationSettingsProvider collaboration,
        LogSettingsProvider logs,
        Supplier<List<Setting>> connections,
        SettingsScreenRuntime runtime) {
}
