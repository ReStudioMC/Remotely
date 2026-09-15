package redxax.oxy.remotely.network;

import java.util.LinkedHashSet;
import java.util.Set;

public record SyncRealm(String id, String name, Set<String> nodeIds, Set<SyncDataFamily> dataFamilies, SyncLocationPolicy locationPolicy, Set<String> persistentDataNamespaces, int retainedSnapshots, int retentionDays) {
    public SyncRealm {
        id = normalize(id);
        name = normalize(name);
        nodeIds = nodeIds == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(nodeIds));
        dataFamilies = dataFamilies == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(dataFamilies));
        locationPolicy = locationPolicy == null ? SyncLocationPolicy.NEVER : locationPolicy;
        persistentDataNamespaces = persistentDataNamespaces == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(persistentDataNamespaces));
        retainedSnapshots = Math.clamp(retainedSnapshots, 1, 10_000);
        retentionDays = Math.clamp(retentionDays, 1, 3650);
    }

    public static SyncRealm presence(String id, String name, Set<String> nodeIds) {
        return new SyncRealm(id, name, nodeIds, Set.of(SyncDataFamily.PRESENCE), SyncLocationPolicy.NEVER, Set.of(), 10, 30);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
