package redxax.oxy.remotely.network;

public record NetworkDocumentSnapshot(NetworkConfigDocumentKey key, String content, boolean exists) {
    public NetworkDocumentSnapshot {
        content = content == null ? "" : content;
    }
}
