package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;

import java.util.List;
public class ServerFeatureSettingsController {
    private final ServerFeatureSettingsProvider provider;
    private final boolean allowLocalOptions;

    public ServerFeatureSettingsController(ServerFeatureSettingsProvider provider) {
        this(provider, true);
    }

    public ServerFeatureSettingsController(ServerFeatureSettingsProvider provider, boolean allowLocalOptions) {
        this.provider = provider;
        this.allowLocalOptions = allowLocalOptions;
    }

    private List<Setting> residentSettings;

    public List<Setting> getSettings() {
        if (residentSettings == null) residentSettings = List.copyOf(createSettings());
        return residentSettings;
    }

    private List<Setting> createSettings() {
        Setting.Builder builder = new Setting.Builder("Features & Integrations");
        if (allowLocalOptions && provider.local()) {
            builder.addOption(ConfigOption.<Boolean>builder("Keep Running")
                    .description("Keep Server Running After Remotely Closes On Next Start.")
                    .bind(provider::lifecyclePersistent, provider::lifecyclePersistent)
                    .defaultValue(true)
                    .build());
            builder.addOption(ConfigOption.<Boolean>builder("Auto Restart")
                    .description("Restart Persistent Servers After A Crash On Next Start.")
                    .bind(provider::restartOnCrash,
                            val -> {
                                provider.restartOnCrash(val);
                                if (val) {
                                    provider.lifecyclePersistent(true);
                                }
                            })
                    .defaultValue(true)
                    .build());
            builder.addOption(ConfigOption.<Integer>builder("Restart Delay")
                    .description("Crash Restart Delay On Next Start.")
                    .range(1, 300)
                    .bind(provider::restartDelaySeconds, provider::restartDelaySeconds)
                    .defaultValue(5)
                    .build());
            builder.addOption(ConfigOption.<Integer>builder("Restart Attempts")
                    .description("Maximum Crash Restarts On Next Start.")
                    .range(1, 50)
                    .bind(provider::restartMaxAttempts, provider::restartMaxAttempts)
                    .defaultValue(3)
                    .build());
            builder.addOption(ConfigOption.<Boolean>builder("Auto Start ReProxy")
                    .description("Start ReProxy When This Server Is Ready.")
                    .bind(() -> Boolean.parseBoolean(provider.property("reproxy.autoStart", "false")),
                            val -> provider.setProperty("reproxy.autoStart", String.valueOf(val)))
                    .defaultValue(false)
                    .build());
        }

        builder.addOption(ConfigOption.<Boolean>builder("Enable MSMP")
                .description("Minecraft Server Management Protocol support.")
                .bind(() -> Boolean.parseBoolean(provider.property("provider.msmp.enabled", "true")),
                      val -> provider.setProperty("provider.msmp.enabled", String.valueOf(val)))
                .defaultValue(true)
                .build());

        builder.addOption(ConfigOption.<Boolean>builder("Enable Backend API")
                .description("Integration with ReBase backend services.")
                .bind(() -> Boolean.parseBoolean(provider.property("provider.backend.enabled", "true")),
                      val -> provider.setProperty("provider.backend.enabled", String.valueOf(val)))
                .defaultValue(true)
                .build());

        builder.addOption(ConfigOption.<Boolean>builder("Enable Standard Provider")
                .description("Standard local file-based management.")
                .bind(() -> Boolean.parseBoolean(provider.property("provider.standard.enabled", "true")),
                      val -> provider.setProperty("provider.standard.enabled", String.valueOf(val)))
                .defaultValue(true)
                .build());

        builder.addOption(ConfigOption.<String>builder("Global Priority")
                .description("Order of providers: msmp, backend, standard.")
                .bind(() -> provider.property("provider.priority", "msmp,backend,standard"),
                      val -> provider.setProperty("provider.priority", val))
                .defaultValue("msmp,backend,standard")
                .build());

        builder.addOption(ConfigOption.<String>builder("Files Priority")
                .description("Provider priority for file operations.")
                .bind(() -> provider.property("provider.priority.files", ""),
                      val -> provider.setProperty("provider.priority.files", val))
                .defaultValue("")
                .build());

        builder.addOption(ConfigOption.<String>builder("Console Priority")
                .description("Provider priority for console access.")
                .bind(() -> provider.property("provider.priority.console", ""),
                      val -> provider.setProperty("provider.priority.console", val))
                .defaultValue("")
                .build());

        builder.addOption(ConfigOption.<String>builder("Players Priority")
                .description("Provider priority for player management.")
                .bind(() -> provider.property("provider.priority.players", ""),
                      val -> provider.setProperty("provider.priority.players", val))
                .defaultValue("")
                .build());

        builder.addOption(ConfigOption.<Integer>builder("Player Data Interval")
                .description("Real Time Update Interval")
                .range(250, 10000)
                .bind(() -> Integer.parseInt(provider.property("playerdata.refresh.intervalMs", "1000")),
                        val -> provider.setProperty("playerdata.refresh.intervalMs", String.valueOf(val)))
                .defaultValue(1000)
                .build());

        return List.of(builder.build());
    }

}
