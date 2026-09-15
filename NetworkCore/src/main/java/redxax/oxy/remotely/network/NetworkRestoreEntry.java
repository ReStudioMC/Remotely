package redxax.oxy.remotely.network;

public record NetworkRestoreEntry(String path, ConfigurationFormat format, String key, boolean present, String value, boolean sensitive) {
    public NetworkRestoreEntry {
        path = normalize(path);
        format = format == null ? ConfigurationFormat.PROPERTIES : format;
        key = normalize(key);
        value = value == null ? "" : value;
        if (path.isBlank() || key.isBlank()) {
            throw new IllegalArgumentException("Restore entry path and key are required");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
