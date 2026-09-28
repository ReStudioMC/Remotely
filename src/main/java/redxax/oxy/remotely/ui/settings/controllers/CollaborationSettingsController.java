package redxax.oxy.remotely.ui.settings.controllers;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.ui.widgets.ColorFieldWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;

import java.util.List;

public final class CollaborationSettingsController {
    private final RemotelyConfigStore config;
    private final CollaborationSettingsProvider provider;

    public CollaborationSettingsController(RemotelyConfigStore config) {
        this(config, new CollaborationSettingsProvider() {});
    }

    public CollaborationSettingsController(RemotelyConfigStore config, CollaborationSettingsProvider provider) {
        this.config = config;
        this.provider = provider;
    }

    private List<Setting> residentSettings;

    public List<Setting> getSettings() {
        if (residentSettings == null) residentSettings = List.copyOf(createSettings());
        return residentSettings;
    }

    private List<Setting> createSettings() {
        Setting.Builder builder = new Setting.Builder("Collaboration");
        builder.addOption(ConfigOption.<Boolean>builder("Custom Color")
            .description("Use your chosen color for cursors, activity, and messages. Turn this off to use your avatar color.")
            .bind(config::getCollaborationColorOverrideEnabled, config::setCollaborationColorOverrideEnabled)
            .defaultValue(false)
            .dependsOn(() -> provider.available(CollaborationSettingsProvider.CUSTOM_COLOR))
            .build());

        ColorFieldWidget color = new ColorFieldWidget(0, 0, 140, 20, String.format("#%08X", config.getCollaborationColor()));
        color.setActive(provider.available(CollaborationSettingsProvider.COLOR));
        color.onChange = () -> config.setCollaborationColor(parseColor(color.getColor()));
        builder.addRow("", new MountableButtonWidget.Builder("Color")
            .description("Choose the color other collaborators see.")
            .addWidget(color)
            .build());
        return List.of(builder.build());
    }

    private int parseColor(String value) {
        if (value == null) {
            return config.getCollaborationColor();
        }
        String hex = value.trim().replace("#", "");
        try {
            if (hex.length() == 8) {
                hex = hex.substring(2);
            }
            return hex.length() == 6 ? 0xFF000000 | Integer.parseInt(hex, 16) : config.getCollaborationColor();
        } catch (NumberFormatException ignored) {
            return config.getCollaborationColor();
        }
    }
}
