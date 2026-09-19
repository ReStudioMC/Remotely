package redxax.oxy.remotely.data.player.source;

final class PlayerFileSnapshotState {
    static final int MAX_RETRY_ATTEMPT = 2;
    static final long REPORT_INTERVAL_MILLIS = 30_000L;
    private static final int DURABLE_FAILURE_COUNT = 3;
    private static final long DURABLE_FAILURE_MILLIS = 1_000L;

    private long generation;
    private String acceptedContent;
    private int failures;
    private long firstFailureAt = -1L;
    private long lastReportAt = -1L;

    synchronized long observe() {
        return ++generation;
    }

    synchronized boolean current(long candidate) {
        return candidate == generation;
    }

    synchronized Accepted accept(long candidate, String content) {
        if (candidate != generation) {
            return new Accepted(false, false);
        }
        boolean changed = !content.equals(acceptedContent);
        acceptedContent = content;
        failures = 0;
        firstFailureAt = -1L;
        lastReportAt = -1L;
        return new Accepted(true, changed);
    }

    synchronized Rejected reject(long candidate, int attempt, long now) {
        if (candidate != generation) {
            return new Rejected(false, false, false);
        }
        if (failures == 0) {
            firstFailureAt = now;
        }
        failures++;
        boolean durable = failures >= DURABLE_FAILURE_COUNT || now - firstFailureAt >= DURABLE_FAILURE_MILLIS;
        boolean report = durable && (lastReportAt < 0L || now - lastReportAt >= REPORT_INTERVAL_MILLIS);
        if (report) {
            lastReportAt = now;
        }
        return new Rejected(true, attempt < MAX_RETRY_ATTEMPT, report);
    }

    synchronized boolean reportNow(long candidate, long now) {
        if (candidate != generation || lastReportAt >= 0L && now - lastReportAt < REPORT_INTERVAL_MILLIS) {
            return false;
        }
        lastReportAt = now;
        return true;
    }

    synchronized String acceptedContent() {
        return acceptedContent;
    }

    record Accepted(boolean current, boolean changed) {
    }

    record Rejected(boolean current, boolean retry, boolean report) {
    }
}
