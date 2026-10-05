package redxax.oxy.remotely.network;

public record NetworkRuntimeNodePresence(String networkId, String nodeId, NetworkRuntimeNodeStatus status, int players, int capacity, double tps, double mspt, long heapUsed, long heapMaximum, long observedAt) {
    public NetworkRuntimeNodePresence {
        networkId = required(networkId, "Network ID");
        nodeId = required(nodeId, "Node ID");
        status = status == null ? NetworkRuntimeNodeStatus.OFFLINE : status;
        players = Math.max(0, players);
        capacity = Math.max(0, capacity);
        tps = Double.isFinite(tps) ? tps : -1;
        mspt = Double.isFinite(mspt) ? mspt : -1;
        heapUsed = Math.max(0, heapUsed);
        heapMaximum = Math.max(0, heapMaximum);
    }

    public boolean fresh(long now) {
        return observedAt > 0 && observedAt >= now - 20_000 && observedAt <= now + 20_000;
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " Is Required");
        }
        return normalized;
    }
}
