package redxax.oxy.remotely.network;

public enum NetworkJobStatus {
    PLANNING,
    READY,
    RUNNING,
    INTERRUPTED,
    ROLLING_BACK,
    SUCCEEDED,
    ROLLED_BACK,
    FAILED,
    BLOCKED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == ROLLED_BACK || this == FAILED || this == BLOCKED;
    }

    public boolean requiresRecovery() {
        return this == PLANNING || this == READY || this == RUNNING || this == INTERRUPTED || this == ROLLING_BACK;
    }
}
