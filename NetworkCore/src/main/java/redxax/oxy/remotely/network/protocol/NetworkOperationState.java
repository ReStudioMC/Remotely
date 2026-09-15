package redxax.oxy.remotely.network.protocol;

public enum NetworkOperationState {
    ADMITTED,
    WAITING_FOR_MEMBERS,
    RUNNING,
    ROLLING_BACK,
    SUCCEEDED,
    ROLLED_BACK,
    FAILED,
    NEEDS_REVIEW
}
