package redxax.oxy.remotely.network;

public record NetworkConfigDocumentKey(String instanceId, String path) {
    public NetworkConfigDocumentKey {
        instanceId = instanceId == null ? "" : instanceId.trim();
        path = path == null ? "" : path.trim();
    }
}
