package redxax.oxy.remotely.network;

import java.util.LinkedHashSet;
import java.util.Set;

public record NetworkEntryPoint(String id, String bindAddress, int port, Set<String> forcedHosts) {
    public NetworkEntryPoint {
        id = normalize(id);
        bindAddress = normalize(bindAddress);
        forcedHosts = forcedHosts == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(forcedHosts));
    }

    public static NetworkEntryPoint primary(int port) {
        return new NetworkEntryPoint("primary", "0.0.0.0", port, Set.of());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
