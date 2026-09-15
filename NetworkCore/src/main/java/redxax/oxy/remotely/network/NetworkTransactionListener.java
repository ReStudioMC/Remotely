package redxax.oxy.remotely.network;

public interface NetworkTransactionListener {
    NetworkTransactionListener NONE = new NetworkTransactionListener() {
    };

    default void onDocumentApplied(NetworkConfigDocumentKey key) {
    }

    default void onRollbackStarted() {
    }

    default void onDocumentRolledBack(NetworkConfigDocumentKey key) {
    }
}
