package redxax.oxy.remotely.ui.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.network.config.PropertiesConfigurationAdapter;
import redxax.oxy.remotely.ui.settings.data.ServerSettingsDataController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ServerCreationFiles {
    private ServerCreationFiles() {
    }

    static Map<String, String> initialFiles(ServerConfigurationTarget target, ServerSettingsDataController settings,
                                            String playerId, String playerName) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(settings, "settings");
        Map<String, String> files = new LinkedHashMap<>(settings.changedFileContents());
        files.put("server.properties", serverProperties(files.get("server.properties"), target.properties()));
        String operators = operatorFile(target, playerId, playerName);
        if (operators != null) files.put("ops.json", operators);
        return files;
    }

    static String serverProperties(String changed, Map<String, String> values) {
        PropertiesConfigurationAdapter adapter = new PropertiesConfigurationAdapter();
        String content = adapter.remove(adapter.remove(changed == null ? "" : changed, "server-port"), "op-me");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "Server Property Name Is Required");
            if ("server-port".equals(key) || "op-me".equals(key)) continue;
            String value = escape(Objects.requireNonNull(entry.getValue(), "Server Property Value Is Required"), false);
            content = adapter.apply(content, adapter.contains(content, key) ? key : escape(key, true), value);
        }
        return content;
    }

    private static String escape(String value, boolean key) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> encoded.append("\\\\");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                case '\f' -> encoded.append("\\f");
                case ' ', '=', ':', '#', '!' -> {
                    if (key || index == 0) encoded.append('\\');
                    encoded.append(character);
                }
                default -> encoded.append(character);
            }
        }
        return encoded.toString();
    }

    static String operatorFile(ServerConfigurationTarget target, String playerId, String playerName) {
        if (!Boolean.parseBoolean(target.properties().getOrDefault("op-me", "false"))) return null;
        if (playerId == null || playerId.isBlank() || playerName == null || playerName.isBlank()) return null;
        JsonObject operator = new JsonObject();
        operator.addProperty("uuid", playerId);
        operator.addProperty("name", playerName);
        operator.addProperty("level", 4);
        operator.addProperty("bypassesPlayerLimit", false);
        JsonArray operators = new JsonArray();
        operators.add(operator);
        return operators.toString();
    }
}
