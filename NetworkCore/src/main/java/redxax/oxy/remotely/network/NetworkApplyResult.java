package redxax.oxy.remotely.network;

import java.util.List;

public record NetworkApplyResult(String planId, boolean applied, boolean rolledBack, List<NetworkConfigDocumentKey> changedDocuments, String message) {
    public NetworkApplyResult {
        changedDocuments = changedDocuments == null ? List.of() : List.copyOf(changedDocuments);
        message = message == null ? "" : message;
    }
}
