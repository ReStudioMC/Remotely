package redxax.oxy.remotely;

public final class ResourceTogglePendingException extends RuntimeException {
    private final String jobId;

    public ResourceTogglePendingException(String jobId) {
        super("Resource Toggle Is Still Running. Refresh To Check Its State");
        this.jobId = jobId;
    }

    public String jobId() {
        return jobId;
    }
}
