package redxax.oxy.remotely.network;

import restudio.rescreen.platform.Clock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record NetworkRuntimeSnapshot(String networkId, NetworkRuntimeConnectionState state, String message, Map<String, NetworkRuntimeNodePresence> nodes, long updatedAt) {
    public NetworkRuntimeSnapshot {
        networkId = networkId == null ? "" : networkId.trim();
        state = state == null ? NetworkRuntimeConnectionState.DISABLED : state;
        message = message == null ? "" : message.trim();
        nodes = nodes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(nodes));
    }

    public static NetworkRuntimeSnapshot disabled(String networkId) {
        return new NetworkRuntimeSnapshot(networkId, NetworkRuntimeConnectionState.DISABLED, "ReSync Runtime Disabled", Map.of(), Clock.system().millis());
    }

    public boolean connected() {
        return state == NetworkRuntimeConnectionState.CONNECTED;
    }

    public Optional<NetworkRuntimeNodePresence> node(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public Optional<NetworkRuntimeNodePresence> liveNode(String nodeId, long now) {
        return connected() ? node(nodeId).filter(presence -> presence.fresh(now) || presence.status() == NetworkRuntimeNodeStatus.OFFLINE || presence.status() == NetworkRuntimeNodeStatus.REVOKED) : Optional.empty();
    }

    public int players() {
        return nodes.values().stream().filter(presence -> presence.status() != NetworkRuntimeNodeStatus.OFFLINE && presence.status() != NetworkRuntimeNodeStatus.REVOKED).mapToInt(NetworkRuntimeNodePresence::players).sum();
    }

    public NetworkRuntimeSnapshot connection(NetworkRuntimeConnectionState nextState, String nextMessage, boolean clearNodes) {
        return new NetworkRuntimeSnapshot(networkId, nextState, nextMessage, clearNodes ? Map.of() : nodes, Clock.system().millis());
    }

    public NetworkRuntimeSnapshot presence(NetworkRuntimeNodePresence presence) {
        if (presence == null || !networkId.equals(presence.networkId())) {
            return this;
        }
        Map<String, NetworkRuntimeNodePresence> nextNodes = new LinkedHashMap<>(nodes);
        nextNodes.put(presence.nodeId(), presence);
        return new NetworkRuntimeSnapshot(networkId, state, message, nextNodes, Clock.system().millis());
    }
}
