package redxax.oxy.remotely.network;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record NetworkMemberRestorePoint(int schemaVersion, String instanceId, String nodeId, long createdAt, List<NetworkRestoreEntry> entries) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public NetworkMemberRestorePoint {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        instanceId = normalize(instanceId);
        nodeId = normalize(nodeId);
        createdAt = createdAt <= 0 ? NetworkClock.SYSTEM.millis() : createdAt;
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported restore point schema " + schemaVersion);
        }
        if (instanceId.isBlank() || nodeId.isBlank()) {
            throw new IllegalArgumentException("Restore point instance and node are required");
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Restore point does not contain configuration entries");
        }
        Set<String> keys = new HashSet<>();
        for (NetworkRestoreEntry entry : entries) {
            if (entry == null || !keys.add(entry.path() + "\u0000" + entry.key())) {
                throw new IllegalArgumentException("Restore point contains invalid or duplicate entries");
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
