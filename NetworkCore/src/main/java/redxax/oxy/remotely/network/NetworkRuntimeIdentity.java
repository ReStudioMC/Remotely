package redxax.oxy.remotely.network;

import redxax.oxy.remotely.util.NameUuid;

public final class NetworkRuntimeIdentity {
    private NetworkRuntimeIdentity() {
    }

    public static String operatorNodeId(String networkId) {
        String normalized = networkId == null ? "" : networkId.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Network ID Is Required");
        }
        return NameUuid.from("remotely-network-operator:" + normalized).toString();
    }
}
