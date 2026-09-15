package redxax.oxy.remotely.network;

import java.util.Map;

public record NetworkPreparedPlan(NetworkReconciliationPlan plan, Map<NetworkConfigDocumentKey, NetworkDocumentSnapshot> documents) {
    public NetworkPreparedPlan {
        documents = documents == null ? Map.of() : Map.copyOf(documents);
    }
}
