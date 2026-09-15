package redxax.oxy.remotely.network;

import java.util.List;
import java.util.Objects;

public record NetworkDocumentChange(NetworkConfigDocumentKey key, int applyOrder, NetworkDocumentSnapshot original, String desired,
                                    List<NetworkConfigMutation> mutations) {
    public NetworkDocumentChange {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(original, "original");
        if (!key.equals(original.key()) || applyOrder < 0) {
            throw new IllegalArgumentException("Network document change is invalid");
        }
        desired = desired == null ? "" : desired;
        mutations = List.copyOf(Objects.requireNonNull(mutations, "mutations"));
        if (mutations.stream().anyMatch(mutation -> mutation == null
                || !key.equals(new NetworkConfigDocumentKey(mutation.instanceId(), mutation.path())))) {
            throw new IllegalArgumentException("Network document change contains a mutation for another document");
        }
    }

    public String originalHash() {
        return NetworkExecutionPlan.fingerprint(original.content());
    }

    public String desiredHash() {
        return NetworkExecutionPlan.fingerprint(desired);
    }

    public boolean changed() {
        return !originalHash().equals(desiredHash());
    }

    public NetworkJobDocument document() {
        return new NetworkJobDocument(key, applyOrder, original.exists(), originalHash(), desiredHash(),
                changed() ? NetworkJobDocumentState.PENDING : NetworkJobDocumentState.UNCHANGED);
    }
}
