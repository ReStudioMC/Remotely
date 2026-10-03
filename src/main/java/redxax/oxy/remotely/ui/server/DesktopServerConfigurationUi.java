package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.discord.DesktopDiscordRpcInstanceSettings;
import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.DesktopHostedSettingsProviders;
import redxax.oxy.remotely.ui.settings.controllers.DesktopPlayerActionsFileProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerFeatureSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerExtraSettingsDocumentAccess;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerGeneralSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerPlanSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerJvmSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.DesktopServerLiveSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.PlayerActionsFileProvider;
import redxax.oxy.remotely.ui.settings.controllers.PortManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerExtraSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerFeatureSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerGeneralSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerManagementSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerPlanSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerJvmSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.ServerLiveSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.SubuserSettingsProvider;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rebase.platform.jvm.JvmBackupSettingsProvider;
import restudio.rebase.backend.feature.AsyncServerScheduleFeature;
import restudio.rebase.backend.feature.DesktopServerScheduleFeatureAdapter;
import restudio.rebase.settings.controllers.BackupSettingsProvider;
import restudio.rebase.instance.Instance;
import restudio.rebase.settings.controllers.DesktopModpackSettingsAdapters;
import restudio.rebase.settings.controllers.DesktopVersionSettingsAdapters;
import restudio.rebase.settings.controllers.ModpackSettingsProvider;
import restudio.rebase.settings.controllers.ModpackSettingsTarget;
import restudio.rebase.settings.controllers.VersionSettingsCatalog;
import restudio.rebase.settings.controllers.VersionSettingsTarget;
import restudio.rebase.util.VersionUtil;
import restudio.rescreen.ui.core.Screen;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class DesktopServerConfigurationUi {
    private DesktopServerConfigurationUi() {
    }

    public static ServerScreenHost.ConfigurationUi create(Screen owner, ServerScreenHost.ConfigurationState state,
                                                           ServerSettingsDataController data, Map<String, String> remoteVariables,
                                                           List<String> extraFiles, Runnable reload, BooleanSupplier allow,
                                                           String defaultLocation) {
        Instance original = state.original() instanceof Instance value ? value : null;
        Instance instance = state.draft() instanceof Instance value ? value : null;
        return ServerConfigurationUiComposition.create(owner, state, data, remoteVariables, extraFiles, reload, allow, defaultLocation,
                instance == null ? null : platform(owner, instance, original));
    }

    private static ServerConfigurationUiPlatform platform(Screen owner, Instance instance, Instance original) {
        Instance managed = original == null ? instance : original;
        return new ServerConfigurationUiPlatform() {
            @Override public Object target() { return instance; }
            @Override public Object originalTarget() { return original; }
            @Override public String name() { return instance.getName(); }
            @Override public boolean linkedModpack() { return instance.hasLinkedModpack(); }
            @Override public boolean managementCompatible() { return VersionUtil.isMSMPCompatible(instance.getVersionId()); }
            @Override public boolean managementEnabled() { return Boolean.parseBoolean(instance.getServerProperties().getProperty("management-server-enabled", "false")); }
            @Override public VersionSettingsTarget versionTarget() { return DesktopVersionSettingsAdapters.target(instance); }
            @Override public VersionSettingsCatalog versionCatalog() { return DesktopVersionSettingsAdapters.catalog(); }
            @Override public ModpackSettingsTarget modpackTarget() { return DesktopModpackSettingsAdapters.target(instance); }
            @Override public ModpackSettingsTarget managedModpackTarget() { return DesktopModpackSettingsAdapters.target(managed); }
            @Override public ModpackSettingsProvider modpackProvider() { return DesktopModpackSettingsAdapters.provider(); }
            @Override public DiscordRpcSettingsController.InstanceSettings discordSettings() { return DesktopDiscordRpcInstanceSettings.create(managed); }
            @Override public ServerFeatureSettingsProvider featureSettingsProvider() { return new DesktopServerFeatureSettingsProvider(instance); }
            @Override public ServerGeneralSettingsProvider generalSettingsProvider() { return new DesktopServerGeneralSettingsProvider(instance); }
            @Override public ServerManagementSettingsProvider managementSettings() { return new DesktopServerManagementSettingsProvider(instance); }
            @Override public ServerPlanSettingsProvider planSettingsProvider() { return new DesktopServerPlanSettingsProvider(); }
            @Override public ServerJvmSettingsProvider jvmSettingsProvider() { return new DesktopServerJvmSettingsProvider(instance); }
            @Override public BackupSettingsProvider backupProvider() { return new JvmBackupSettingsProvider(owner, instance, managed); }
            @Override public AsyncServerScheduleFeature scheduleProvider() { return new DesktopServerScheduleFeatureAdapter(managed); }
            @Override public PortManagementSettingsProvider portProvider() { return DesktopHostedSettingsProviders.ports(instance); }
            @Override public SubuserSettingsProvider subuserProvider() { return DesktopHostedSettingsProviders.subusers(instance); }
            @Override public PlayerActionsFileProvider playerActionsFileProvider() { return new DesktopPlayerActionsFileProvider(managed); }
            @Override public ServerLiveSettingsProvider liveSettingsProvider() { return new DesktopServerLiveSettingsProvider(managed); }
            @Override public ServerExtraSettingsController.DocumentAccess documentAccess() { return new DesktopServerExtraSettingsDocumentAccess(instance); }
        };
    }
}
