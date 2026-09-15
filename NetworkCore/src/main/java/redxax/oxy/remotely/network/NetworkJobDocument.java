package redxax.oxy.remotely.network;

public record NetworkJobDocument(NetworkConfigDocumentKey key, int applyOrder, boolean originalExists, String originalHash, String desiredHash, NetworkJobDocumentState state) {
    public NetworkJobDocument {
        if (key == null) {
            throw new IllegalArgumentException("Network job document key is required");
        }
        applyOrder = Math.max(0, applyOrder);
        originalHash = normalize(originalHash);
        desiredHash = normalize(desiredHash);
        state = state == null ? NetworkJobDocumentState.PENDING : state;
    }

    public NetworkJobDocument withState(NetworkJobDocumentState updatedState) {
        return new NetworkJobDocument(key, applyOrder, originalExists, originalHash, desiredHash, updatedState);
    }

    public boolean changed() {
        return !originalHash.equals(desiredHash);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
