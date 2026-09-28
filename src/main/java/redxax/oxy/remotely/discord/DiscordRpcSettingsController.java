package redxax.oxy.remotely.discord;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import restudio.rebase.settings.controllers.SettingsActionCapability;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;

import java.util.List;

public class DiscordRpcSettingsController {

    public interface InstanceSettings {
        String get(String key, String fallback);

        void set(String key, String value);

        void remove(String key);
    }

    public enum EnabledMode {
        INHERIT,
        ENABLED,
        DISABLED
    }

    private static final String DEFAULT_MANAGER_DETAILS = "In Remotely";
    private static final String DEFAULT_MANAGER_STATE = "Managing Servers";
    private static final String DEFAULT_SERVER_DETAILS = "Viewing Server";
    private static final String DEFAULT_SERVER_STATE = "{server}";
    private static final String DEFAULT_RUNNING_DETAILS = "Managing Server";
    private static final String DEFAULT_RUNNING_STATE = "{players} Players | {server}";
    private static final String DEFAULT_RESYNC_DETAILS = "Designing ReSync";
    private static final String DEFAULT_RESYNC_STATE = "{server} | {studio}";

    private final InstanceSettings instance;
    private final RemotelyConfigStore configManager;
    private final boolean global;
    private final SettingsActionCapability capability;

    public DiscordRpcSettingsController(InstanceSettings instance, RemotelyConfigStore configManager) {
        this(instance, configManager, SettingsActionCapability.supported("settings.discord-rpc"));
    }

    public DiscordRpcSettingsController(InstanceSettings instance, RemotelyConfigStore configManager, SettingsActionCapability capability) {
        this.instance = instance;
        this.configManager = configManager;
        this.global = instance == null;
        this.capability = capability == null ? SettingsActionCapability.unavailable("settings.discord-rpc", "Discord Activity Is Unavailable") : capability;
    }

    private List<Setting> residentSettings;

    public List<Setting> getSettings() {
        if (residentSettings == null) residentSettings = List.copyOf(createSettings());
        return residentSettings;
    }

    private List<Setting> createSettings() {
        Setting.Builder builder = new Setting.Builder("Discord Rpc");

        if (global) {
            builder.addOption(ConfigOption.<Boolean>builder("Enabled")
                    .description(description("Show activity in Discord."))
                    .bind(() -> configManager.bool("discordRpc.enabled", true),
                            val -> configManager.set("discordRpc.enabled", String.valueOf(val)))
                    .defaultValue(true)
                    .dependsOn(capability::available)
                    .build());

            builder.addOption(ConfigOption.<Integer>builder("Idle Timeout Minutes")
                    .description(description("After inactivity, show Idle. Set 0 to disable."))
                    .bind(() -> configManager.integer("discordRpc.idleTimeoutMinutes", 5),
                            val -> configManager.set("discordRpc.idleTimeoutMinutes", String.valueOf(Math.max(0, val))))
                    .defaultValue(5)
                    .dependsOn(capability::available)
                    .build());

            builder.addOption(textOption("Manager Details", "discordRpc.manager.details", DEFAULT_MANAGER_DETAILS));
            builder.addOption(textOption("Manager State", "discordRpc.manager.state", DEFAULT_MANAGER_STATE));
            builder.addOption(textOption("Server Details", "discordRpc.server.details", DEFAULT_SERVER_DETAILS));
            builder.addOption(textOption("Server State", "discordRpc.server.state", DEFAULT_SERVER_STATE));
            builder.addOption(textOption("Running Details", "discordRpc.running.details", DEFAULT_RUNNING_DETAILS));
            builder.addOption(textOption("Running State", "discordRpc.running.state", DEFAULT_RUNNING_STATE));
            builder.addOption(textOption("ReSync Details", "discordRpc.resync.details", DEFAULT_RESYNC_DETAILS));
            builder.addOption(textOption("ReSync State", "discordRpc.resync.state", DEFAULT_RESYNC_STATE));
        } else {
            builder.addOption(ConfigOption.<EnabledMode>builder("Enabled")
                    .description(description("Global Default uses global settings."))
                    .options(List.of(EnabledMode.INHERIT, EnabledMode.ENABLED, EnabledMode.DISABLED))
                    .display(mode -> switch (mode) {
                        case INHERIT -> "Global Default";
                        case ENABLED -> "Enabled";
                        case DISABLED -> "Disabled";
                    })
                    .bind(this::getInstanceEnabledMode, this::setInstanceEnabledMode)
                    .defaultValue(EnabledMode.INHERIT)
                    .dependsOn(capability::available)
                    .build());

            builder.addOption(ConfigOption.<String>builder("Details Override")
                    .description(description("Blank uses global Running Details."))
                    .bind(() -> instance.get("discordRpc.detailsOverride", ""),
                            val -> setInstanceStringOrRemove("discordRpc.detailsOverride", val))
                    .defaultValue("")
                    .dependsOn(capability::available)
                    .build());

            builder.addOption(ConfigOption.<String>builder("State Override")
                    .description(description("Blank uses global Running State."))
                    .bind(() -> instance.get("discordRpc.stateOverride", ""),
                            val -> setInstanceStringOrRemove("discordRpc.stateOverride", val))
                    .defaultValue("")
                    .dependsOn(capability::available)
                    .build());
        }

        return List.of(builder.build());
    }

    private ConfigOption<String> textOption(String name, String key, String defaultValue) {
        return ConfigOption.<String>builder(name)
                .description(description("Tokens: {server} {version} {loader} {state} {view} {studio} {players} {uptime} {cpu} {ram}"))
                .bind(() -> configManager.get(key, defaultValue),
                        val -> configManager.set(key, val != null ? val : ""))
                .defaultValue(defaultValue)
                .dependsOn(capability::available)
                .build();
    }

    private String description(String value) {
        return capability.available() ? value : value + " " + capability.reason();
    }

    private EnabledMode getInstanceEnabledMode() {
        String raw = instance.get("discordRpc.enabledMode", EnabledMode.INHERIT.name());
        try {
            return EnabledMode.valueOf(raw);
        } catch (Exception ignored) {
            return EnabledMode.INHERIT;
        }
    }

    private void setInstanceEnabledMode(EnabledMode mode) {
        if (mode == null || mode == EnabledMode.INHERIT) {
            instance.remove("discordRpc.enabledMode");
        } else {
            instance.set("discordRpc.enabledMode", mode.name());
        }
    }

    private void setInstanceStringOrRemove(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            instance.remove(key);
        } else {
            instance.set(key, value);
        }
    }

}
