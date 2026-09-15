package redxax.oxy.remotely.network;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RoutingGroup(String id, String name, RoutingStrategy strategy, List<String> nodeIds, Map<String, Integer> weights, String fallbackGroupId, Set<String> forcedHosts, String permission) {
    public RoutingGroup {
        id = normalize(id);
        name = normalize(name);
        strategy = strategy == null ? RoutingStrategy.ORDERED : strategy;
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        weights = weights == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(weights));
        fallbackGroupId = normalize(fallbackGroupId);
        forcedHosts = forcedHosts == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(forcedHosts));
        permission = normalize(permission);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
