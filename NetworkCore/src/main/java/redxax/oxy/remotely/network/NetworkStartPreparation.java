package redxax.oxy.remotely.network;

import redxax.oxy.remotely.network.config.PropertiesConfigurationAdapter;

public final class NetworkStartPreparation {
    private static final String ACCEPTED_MINECRAFT_EULA = "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\neula=true\n";
    private static final PropertiesConfigurationAdapter PROPERTIES = new PropertiesConfigurationAdapter();

    private NetworkStartPreparation() {
    }

    public static String acceptMinecraftEula(String content) {
        String source = content == null ? "" : content;
        if (source.isBlank()) {
            return ACCEPTED_MINECRAFT_EULA;
        }
        if (PROPERTIES.contains(source, "eula") && PROPERTIES.read(source, "eula").equalsIgnoreCase("true")) {
            return source;
        }
        String updated = PROPERTIES.apply(source, "eula", "true");
        if (PROPERTIES.read(updated, "eula").equalsIgnoreCase("true")) {
            return updated;
        }
        String separator = updated.contains("\r\n") ? "\r\n" : "\n";
        return updated + (updated.endsWith("\n") || updated.endsWith("\r") ? "" : separator) + "eula=true" + separator;
    }
}
