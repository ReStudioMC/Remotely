package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.settings.options.ConfigOption;
import restudio.rescreen.util.Notification;

import java.util.List;
import java.util.regex.Pattern;

public class ServerManagementSettingsController {
    private final ServerManagementSettingsProvider provider;
    private static final Pattern SECRET_PATTERN = Pattern.compile("^[a-zA-Z0-9]{40}$");

    public ServerManagementSettingsController(ServerManagementSettingsProvider provider) {
        this.provider = provider;
    }

    private List<Setting> residentSettings;

    public List<Setting> getSettings() {
        if (residentSettings == null) residentSettings = List.copyOf(createSettings());
        return residentSettings;
    }

    private List<Setting> createSettings() {
        ServerManagementSettingsProvider p = provider;
        boolean isRemote = p.remote();

        String host = p.property("management-server-host", "");
        if (host.isEmpty()) {
            host = isRemote ? "0.0.0.0" : "localhost";
            p.setProperty("management-server-host", host);
        }

        String port = p.property("management-server-port", "");
        if (port.isEmpty() || "0".equals(port)) p.setProperty("management-server-port", "25585");

        if (p.property("management-server-tls-enabled") == null && p.property("management.server.tls.enabled") == null) {
            p.setProperty("management-server-tls-enabled", "false");
            p.setProperty("management.server.tls.enabled", "false");
        }

        Setting.Builder management = new Setting.Builder("Server Management Protocol");

        ConfigOption<Boolean> enableManagement = ConfigOption.<Boolean>builder("Enable Management API")
            .description("Allow remote management via MSMP.")
            .bind(() -> Boolean.parseBoolean(p.property("management-server-enabled", "false")),
                val -> {
                    p.setProperty("management-server-enabled", String.valueOf(val));
                    if (val && isRemote && !Boolean.parseBoolean(p.property("management-server-tls-enabled", p.property("management.server.tls.enabled", "false")))) {
                        new Notification.Builder().message("MSMP Without TLS Is Insecure").description("Enable TLS For Remote MSMP").type(Notification.Type.WARN).build();
                    }
                    String currentPort = p.property("management-server-port", "0");
                    if (currentPort.isEmpty() || "0".equals(currentPort)) {
                        p.setProperty("management-server-port", "25585");
                    }
                    String currentHost = p.property("management-server-host", "");
                    if (isRemote && (currentHost.isEmpty() || "localhost".equalsIgnoreCase(currentHost) || "127.0.0.1".equals(currentHost))) {
                        p.setProperty("management-server-host", "0.0.0.0");
                    }
                })
            .defaultValue(false)
            .build();
        management.addOption(enableManagement);

        management.addOption(ConfigOption.<String>builder("Management Host")
            .description("IP address to bind the management server to.")
            .bind(() -> p.property("management-server-host", isRemote ? "0.0.0.0" : "localhost"),
                val -> p.setProperty("management-server-host", val))
            .defaultValue(isRemote ? "0.0.0.0" : "localhost")
            .dependsOn(enableManagement)
            .build());

        management.addOption(ConfigOption.<String>builder("Management Port")
            .description("Port for the management server.")
            .bind(() -> p.property("management-server-port", "25585"),
                val -> p.setProperty("management-server-port", val))
            .defaultValue("25585")
            .dependsOn(enableManagement)
            .build());

        management.addOption(ConfigOption.<String>builder("Secret (40 chars)")
            .description("Security token for authentication.")
            .bind(() -> p.property("management-server-secret", p.property("management-server-token", "")),
                val -> {
                    if (!val.isEmpty() && !SECRET_PATTERN.matcher(val).matches()) {
                        new Notification.Builder().message("Invalid Secret").description("Secret Must Be Exactly 40 Alphanumeric Characters").type(Notification.Type.ERROR).build();
                    }
                    if (val.isEmpty()) {
                        p.remove("management-server-secret");
                    } else {
                        p.setProperty("management-server-secret", val);
                    }
                    p.remove("management-server-token");
                })
            .defaultValue("")
            .dependsOn(enableManagement)
            .build());

        Setting.Builder tls = new Setting.Builder("TLS / SSL");

        ConfigOption<Boolean> enableTls = ConfigOption.<Boolean>builder("Enable TLS (SSL)")
            .description("Encrypt management traffic.")
            .bind(() -> Boolean.parseBoolean(p.property("management-server-tls-enabled", p.property("management.server.tls.enabled", "false"))),
                val -> {
                    p.setProperty("management-server-tls-enabled", String.valueOf(val));
                    p.setProperty("management.server.tls.enabled", String.valueOf(val));
                    if (!val && isRemote && Boolean.parseBoolean(p.property("management-server-enabled", "false"))) {
                        new Notification.Builder().message("MSMP Without TLS Is Insecure").description("Enable TLS For Remote MSMP").type(Notification.Type.WARN).build();
                    }
                })
            .defaultValue(false)
            .dependsOn(enableManagement)
            .build();
        tls.addOption(enableTls);

        tls.addOption(ConfigOption.<String>builder("Keystore Path")
            .description("Path to the JKS keystore file.")
            .bind(() -> p.property("management.server.tls.keystore.path", ""),
                val -> p.setProperty("management.server.tls.keystore.path", val))
            .defaultValue("")
            .dependsOn(enableTls)
            .build());

        tls.addOption(ConfigOption.<String>builder("Keystore Password")
            .description("Password for the keystore.")
            .bind(() -> p.property("management.server.tls.keystore.password", ""),
                val -> p.setProperty("management.server.tls.keystore.password", val))
            .defaultValue("")
            .dependsOn(enableTls)
            .build());

        return List.of(management.build(), tls.build());
    }
}
