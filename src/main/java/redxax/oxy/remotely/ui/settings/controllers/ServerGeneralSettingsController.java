package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.RemotelyClient;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;

import java.util.List;
public class ServerGeneralSettingsController {
    private final ServerGeneralSettingsProvider provider;
    private final boolean editMode;

    public ServerGeneralSettingsController(ServerGeneralSettingsProvider provider) {
        this(provider, false);
    }

    public ServerGeneralSettingsController(ServerGeneralSettingsProvider provider, boolean editMode) {
        this.provider = provider;
        this.editMode = editMode;
    }

    private List<Setting> residentSettings;

    public List<Setting> getSettings() {
        if (residentSettings == null) residentSettings = List.copyOf(createSettings());
        return residentSettings;
    }

    private List<Setting> createSettings() {
        Setting.Builder general = new Setting.Builder("Server");

        general.addOption(ConfigOption.<String>builder("Server Name")
                .description("The name used for this server in Remotely.")
                .bind(provider::name, provider::name)
                .defaultValue("New Server")
                .resettable(false)
                .build());

        if (!editMode && RemotelyClient.INSTANCE.getHost().getGameUserName() != null) {
            general.addOption(ConfigOption.<Boolean>builder("Op Me")
                .description("Grant " + RemotelyClient.INSTANCE.getHost().getGameUserName() + " Operator.")
                .bind(() -> provider.property("op-me", "true").equalsIgnoreCase("true"),
                    val -> provider.setProperty("op-me", String.valueOf(val)))
                .defaultValue(true)
                .resettable(false)
                .build()
            );
        }

        return List.of(general.build());
    }
}
