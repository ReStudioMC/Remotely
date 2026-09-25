package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.discord.DiscordRpcSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerBackupSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerExtraSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerFeatureSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerGameRulesSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerGeneralSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerJvmSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerLiveSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerManagementSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerNetworkSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerPlanSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerScheduleSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerSubuserSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerStartupSettingsController;
import redxax.oxy.remotely.ui.settings.controllers.ServerStartupSettingsProvider;
import redxax.oxy.remotely.ui.settings.controllers.PlayerActionsSettingsController;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;
import restudio.rebase.settings.controllers.ModpackSettingsController;
import restudio.rebase.settings.controllers.VersionSettingsController;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rescreen.config.Config;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class ServerConfigurationUiComposition {
    private ServerConfigurationUiComposition() {
    }

    public static ServerScreenHost.ConfigurationUi create(Screen owner, ServerScreenHost.ConfigurationState state,
                                                           ServerSettingsDataController data, Map<String, String> remoteVariables,
                                                           List<String> extraFiles,
                                                           Runnable reload, BooleanSupplier allow, String defaultLocation,
                                                           ServerConfigurationUiPlatform platform) {
        Object original = platform == null ? null : platform.originalTarget();
        Object instance = platform == null ? null : platform.target();
        if (instance == null || !(owner instanceof ReScreen screen)) {
            return new ServerScreenHost.ConfigurationUi(Map.of(), () -> {}, "Server Configuration", () -> "", () -> "", () -> null, () -> defaultLocation);
        }
        Map<String, Supplier<List<Setting>>> settings = new LinkedHashMap<>();
        List<Runnable> cleanup = new ArrayList<>();
        TextInputWidget instanceLocationField = null;
        if (!state.editMode() && !state.restudioCreation() && state.remoteHost() == null) {
            instanceLocationField = new TextInputWidget.Builder()
                    .text(defaultLocation == null ? "" : defaultLocation)
                    .placeholder("Instances Path")
                    .size(0, 20)
                    .build();
        }
        TextInputWidget locationField = instanceLocationField;
        List<ProfileChoice> profileChoices = state.resourcePoolCreation() ? choices(state.poolOptions()) : List.of();
        ScrollSelectorWidget profile = state.resourcePoolCreation() ? new ScrollSelectorWidget.Builder()
                .options(profileChoices.stream().map(ProfileChoice::label).toList()).selectedIndex(0).size(180, 20).build() : null;
        VersionSettingsController version = new VersionSettingsController(platform.versionTarget(), platform.versionCatalog());
        if (state.restudioBackend() || state.restudioCreation()) {
            version.bindToRemoteVariables(remoteVariables);
        }
        version.allowServerSoftwareChangeWhen(ignored -> allow == null || allow.getAsBoolean());
        version.onServerSoftwareChanged(ignored -> reload.run());
        ServerGeneralSettingsController general = new ServerGeneralSettingsController(platform.generalSettingsProvider(),
                state.editMode() || state.resourcePoolCreation());
        ModpackSettingsController modpack = new ModpackSettingsController(platform.modpackTarget(), platform.managedModpackTarget(), platform.modpackProvider());
        boolean linkedModpack = platform.modpackTarget().linkedModpack();
        cleanup.add(modpack::cleanup);
        ServerPlanSettingsController plan = state.restudioCreation() && !state.resourcePoolCreation()
                ? new ServerPlanSettingsController(platform.planSettingsProvider()) : null;
        if (plan != null) plan.selectPlanByName(state.preselectedPlanName());
        if (!linkedModpack && !state.resourcePoolCreation()) {
            settings.put("Server Software", () -> {
                if (state.restudioBackend() || state.restudioCreation()) version.bindToRemoteVariables(remoteVariables);
                return version.getSettings();
            });
        }
        settings.put("General", () -> {
            List<Setting> result = new ArrayList<>();
            if (plan != null) result.addAll(plan.getSettings());
            result.addAll(general.getSettings());
            if (locationField != null) {
                Setting.Builder storage = new Setting.Builder("Storage");
                storage.addRow("Location", locationField);
                result.add(storage.build());
            }
            if (state.editMode() && state.restudioBackend()) {
                result.addAll(modpack.getSettings());
            }
            return result;
        });
        if (state.resourcePoolCreation()) {
            settings.put("Pool", () -> {
                Setting.Builder identity = new Setting.Builder("Server Profile");
                identity.addRow("Profile", profile);
                return List.of(identity.build());
            });
            Runnable dispose = () -> cleanup.forEach(Runnable::run);
            return new ServerScreenHost.ConfigurationUi(settings, dispose, "Create Server",
                    () -> "", () -> "", () -> null, () -> defaultLocation,
                    () -> {
                        if (profileChoices.isEmpty()) {
                            return new ServerScreenHost.PoolResources("", "", "", "", "", "", "", "");
                        }
                        ProfileChoice selected = profileChoices.get(Math.clamp(profile.getSelectedIndex(), 0, profileChoices.size() - 1));
                        return new ServerScreenHost.PoolResources(selected.gameId(), selected.profileId(), "", "", "", "", "", "");
                    });
        }
        settings.put("Features", new ServerFeatureSettingsController(platform.featureSettingsProvider())::getSettings);
        if (state.editMode() && state.restudioBackend()) {
            settings.put("Software Settings", new ServerStartupSettingsController(ServerStartupSettingsProvider.map(remoteVariables,
                    linkedModpack ? Set.of("AUTOMATIC_UPDATING") : Set.of()))::getSettings);
        }
        if (Config.configManager instanceof RemotelyConfigStore config) {
            settings.put("Discord", new DiscordRpcSettingsController(
                    platform.discordSettings(), config, platform.discordCapability())::getSettings);
        }
        ServerJvmSettingsController java = new ServerJvmSettingsController(platform.jvmSettingsProvider());
        if (state.restudioBackend() || state.restudioCreation()) java.bindToRemoteVariables(remoteVariables);
        settings.put("Java", java::getSettings);
        if (state.editMode()) {
            ServerBackupSettingsController backup = new ServerBackupSettingsController(screen, platform.backupProvider());
            settings.put("Backups", backup::getSettings);
            cleanup.add(backup::cleanup);
            ServerScheduleSettingsController schedules = new ServerScheduleSettingsController(screen, platform.scheduleProvider());
            settings.put("Schedules", schedules::getSettings);
            cleanup.add(schedules::cleanup);
        }
        if (state.editMode() && state.restudioBackend()) {
            settings.put("Network", new ServerNetworkSettingsController(screen, platform.portProvider())::getSettings);
            settings.put("Subusers", new ServerSubuserSettingsController(screen, platform.subuserProvider())::getSettings);
        }
        boolean compatible = platform.managementCompatible();
        if (compatible) settings.put("Management", new ServerManagementSettingsController(platform.managementSettings())::getSettings);
        boolean enabled = platform.managementEnabled();
        if (state.editMode()) {
            settings.put("Player Actions", new PlayerActionsSettingsController(platform.playerActionsFileProvider())::getSettings);
            if (compatible && enabled) {
                var liveProvider = platform.liveSettingsProvider();
                ServerGameRulesSettingsController rules = new ServerGameRulesSettingsController(liveProvider);
                ServerLiveSettingsController live = new ServerLiveSettingsController(liveProvider);
                settings.put("Live Settings", () -> {
                    List<Setting> result = new ArrayList<>(rules.getSettings());
                    result.addAll(live.getSettings());
                    return result;
                });
                cleanup.add(rules::cleanup);
                cleanup.add(live::cleanup);
            }
        }
        if (state.editMode() && !state.restudioCreation()) {
            settings.put("Extra Files", () -> {
                List<String> availableFiles = new ArrayList<>(extraFiles == null ? List.of() : extraFiles);
                availableFiles.addAll(data.availableDocumentPaths());
                return new ServerExtraSettingsController(availableFiles, data.documentPaths(), platform.documentAccess()).getSettings();
            });
        }
        String title = state.editMode() ? "Edit " + platform.name()
                : state.resourcePoolCreation() ? "Create Server"
                : state.restudioCreation() ? "Order New Server" : "Create New Server";
        Runnable dispose = () -> cleanup.forEach(Runnable::run);
        return new ServerScreenHost.ConfigurationUi(settings, dispose, title,
                () -> plan == null ? "" : plan.getSelectedPlanName(),
                () -> plan == null ? "" : plan.getSubdomain(),
                () -> plan == null ? null : plan.getCustomPlanRequest(),
                () -> locationField == null ? defaultLocation : locationField.getText().trim(),
                () -> new ServerScreenHost.PoolResources("", "", "", "", "", "", "", ""));
    }

    private static List<ProfileChoice> choices(ResourcePoolModels.DraftOptions options) {
        List<ProfileChoice> result = new ArrayList<>();
        for (ResourcePoolModels.GameOption game : options.games()) {
            for (ResourcePoolModels.ProfileOption profile : game.profiles()) {
                result.add(new ProfileChoice(game.id(), profile.id(), game.label() + " • " + profile.label()));
            }
        }
        return List.copyOf(result);
    }

    private record ProfileChoice(String gameId, String profileId, String label) {
    }
}
