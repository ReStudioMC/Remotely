package redxax.oxy.remotely.network;

public record NetworkValidationIssue(Severity severity, String code, String subject, String message) {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public boolean blocksPersistence() {
        return severity == Severity.ERROR;
    }
}
