package redxax.oxy.remotely.data.player.model;

import java.util.UUID;

public final class PlayerAvatarUrl {
    private PlayerAvatarUrl() {
    }

    public static String identity(String playerName, UUID playerId) {
        return identity(playerName, playerId == null ? "" : playerId.toString());
    }

    public static String identity(String playerName, String playerId) {
        String name = safeSubject(playerName);
        return name.isBlank() ? safeSubject(playerId) : name;
    }

    public static String resolve(String playerName, UUID playerId, int size) {
        return resolve(playerName, playerId == null ? "" : playerId.toString(), size);
    }

    public static String resolve(String playerName, String playerId, int size) {
        String identity = identity(playerName, playerId);
        if (identity.isBlank()) return "";
        int boundedSize = Math.max(1, Math.min(512, size));
        return "https://mc-heads.net/avatar/" + identity + "/" + boundedSize + ".png";
    }

    private static String safeSubject(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        StringBuilder safe = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            safe.append(isSafe(character) ? character : '_');
        }
        return safe.toString();
    }

    private static boolean isSafe(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '.'
                || character == '_'
                || character == '-';
    }
}
