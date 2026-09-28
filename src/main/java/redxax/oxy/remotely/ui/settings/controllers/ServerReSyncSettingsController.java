package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ServerReSyncSettingsController {
    private static final String RESYNC_ENABLED_KEY = "resyncEnabled";
    private static final String RESYNC_API_KEY_KEY = "resyncApiKey";
    private static final String RESYNC_PORT_KEY = "resyncPort";
    private static final String DEFAULT_RESYNC_PORT = "12441";
    private final ServerReSyncSettingsProvider provider;
    private Setting setting;

    public ServerReSyncSettingsController(ServerReSyncSettingsProvider provider) {
        this.provider = provider;
    }

    public List<Setting> getSettings() {
        if (setting != null) return List.of(setting);
        if (provider.credentials() == null) return List.of();
        Setting.Builder builder = new Setting.Builder("ReSync Settings");

        ConfigOption<Boolean> enabled = ConfigOption.<Boolean>builder("Enable ReSync")
            .description("Enable ReSync for this server")
            .bind(() -> Boolean.parseBoolean(safeText(provider.credentials().get(RESYNC_ENABLED_KEY))), value -> {
                provider.credentials().put(RESYNC_ENABLED_KEY, String.valueOf(value));
                if (!value) {
                    provider.credentials().remove(RESYNC_PORT_KEY);
                    provider.credentials().remove(RESYNC_API_KEY_KEY);
                    return;
                }
                if (safeText(provider.credentials().get(RESYNC_PORT_KEY)).isBlank()) {
                    provider.credentials().put(RESYNC_PORT_KEY, DEFAULT_RESYNC_PORT);
                }
            })
            .defaultValue(false)
            .build();
        builder.addOption(enabled);

        builder.addOption(ConfigOption.<String>builder("ReSync Port")
            .description("Server ReSync WebSocket port. Default is 12441")
            .bind(() -> safeText(provider.credentials().get(RESYNC_PORT_KEY)), value -> {
                String normalized = safeText(value).trim();
                if (normalized.isBlank()) {
                    provider.credentials().remove(RESYNC_PORT_KEY);
                    return;
                }
                provider.credentials().put(RESYNC_PORT_KEY, normalized);
            })
            .defaultValue("")
            .dependsOn(enabled)
            .build());

        builder.addOption(ConfigOption.<String>builder("ReSync ApiKey")
            .description("Server ReSync api key")
            .bind(() -> safeText(provider.credentials().get(RESYNC_API_KEY_KEY)), value -> {
                String normalized = safeText(value).trim();
                if (normalized.isBlank()) {
                    provider.credentials().remove(RESYNC_API_KEY_KEY);
                    return;
                }
                provider.credentials().put(RESYNC_API_KEY_KEY, normalized);
            })
            .defaultValue("")
            .dependsOn(enabled)
            .build());

        setting = builder.build();
        return List.of(setting);
    }

    public boolean shouldProvisionReStudio() {
        if (!provider.reStudioBackend() || provider.serverIdentifier() == null || provider.serverIdentifier().isBlank()) {
            return false;
        }
        Map<String, String> credentials = provider.credentials();
        if (credentials == null) {
            return false;
        }
        return Boolean.parseBoolean(safeText(credentials.get(RESYNC_ENABLED_KEY)));
    }

    public void provisionReStudio(Consumer<Boolean> callback) {
        provider.provision(callback);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
