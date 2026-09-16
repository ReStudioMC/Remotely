package redxax.oxy.remotely.data.flow;

import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ReSyncLifecycleDiagnosticPolicy {
    static final String CURRENT_MODE_PROPERTY = "remotely.resync.diagnostics.lifecycle.mode";
    static final String CURRENT_MODE_ENVIRONMENT = "REMOTELY_RESYNC_DIAGNOSTICS_LIFECYCLE";
    static final String LEGACY_DEBUG_PROPERTY = "remotely.resync.lifecycleDebug";
    static final String LEGACY_DEBUG_ENVIRONMENT = "REMOTELY_RESYNC_LIFECYCLE_DEBUG";
    static final DiagnosticSink.Mode DEFAULT_MODE = DiagnosticSink.Mode.RECOVERY;
    static final int MAX_FIELDS = 32;
    static final int MAX_VALUE_CHARS = 240;
    static final int MAX_LINE_BYTES = 4096;
    static final int MAX_COLLECTION_ITEMS = 16;
    static final int MAX_COLLECTION_DEPTH = 3;
    static final int MAX_THROTTLE_KEYS = 256;
    static final int CRITICAL_CAPACITY = 512;
    static final int NORMAL_CAPACITY = 4096;
    static final int MAX_BATCH_SIZE = 256;
    static final long MAX_BATCH_DELAY_MILLIS = 100L;
    static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    static final long RETENTION_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    static final long RETENTION_BYTES = 128L * 1024L * 1024L;
    static final long CLOSE_TIMEOUT_MILLIS = 2_000L;
    static final long PROGRESS_THROTTLE_NANOS = 250_000_000L;
    static final Set<String> PROGRESS_STAGES = Set.of(
        "authoritative_list_page", "catalog_frame_progress", "catalog_publication_progress",
        "render_index_published", "render_index_rejected", "generic_widget_conversion", "typed_descriptor_conversion");
    static final Set<String> SENSITIVE_FIELDS = Set.of(
        "payload", "canonicalpayload", "authorization", "token", "secret", "password", "credential",
        "rawsession", "session", "user", "address", "endpoint", "host", "ip", "path", "sql", "stack",
        "exception", "client");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:payload|canonicalpayload|authorization|token|secret|password|credential|rawsession|session|user|address|endpoint|host|ip|path|sql|stack|exception|client)\\b\\s*[:=]");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
        "(?i)[a-z]:[\\\\/]|/(?:[^\\s/]+/)+");
    private static final Pattern SQL_TEXT = Pattern.compile(
        "(?i)\\b(?:select|insert|update|delete|alter|create|drop|pragma)\\b.+\\b(?:from|into|table|where|set)\\b");
    private static final Pattern STACK_TEXT = Pattern.compile(
        "(?i)(?:^|\\s)(?:at\\s+[A-Za-z0-9_$.-]+\\.[A-Za-z0-9_$.-]+\\(|[A-Za-z0-9_$.-]+Exception(?:\\s|$))");

    private ReSyncLifecycleDiagnosticPolicy() {
    }

    static DiagnosticSink.Mode resolveFromProcess() {
        return resolveMode(readProperty(CURRENT_MODE_PROPERTY), readEnvironment(CURRENT_MODE_ENVIRONMENT),
            readProperty(LEGACY_DEBUG_PROPERTY), readEnvironment(LEGACY_DEBUG_ENVIRONMENT));
    }

    static DiagnosticSink.Mode resolveMode(String modeProperty, String modeEnvironment,
                                           String legacyProperty, String legacyEnvironment) {
        String explicit = firstNonBlank(modeProperty, modeEnvironment);
        if (!explicit.isBlank()) {
            return parseMode(explicit);
        }
        String legacy = firstNonBlank(legacyProperty, legacyEnvironment);
        return legacy.isBlank() ? DEFAULT_MODE
            : truthy(legacy) ? DiagnosticSink.Mode.RECOVERY : DiagnosticSink.Mode.OFF;
    }

    static DiagnosticSink.Mode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return DiagnosticSink.Mode.OFF;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1", "recovery" -> DiagnosticSink.Mode.RECOVERY;
            case "normal", "important" -> DiagnosticSink.Mode.NORMAL;
            case "verbose", "trace", "all" -> DiagnosticSink.Mode.VERBOSE;
            case "off", "false", "no", "0", "disabled" -> DiagnosticSink.Mode.OFF;
            default -> DiagnosticSink.Mode.OFF;
        };
    }

    static DiagnosticSink.Priority priority(String stage, Map<String, ?> values) {
        String normalizedStage = stage == null ? "" : stage.toLowerCase(Locale.ROOT);
        if (normalizedStage.contains("terminal") || normalizedStage.contains("shutdown")) {
            return DiagnosticSink.Priority.TERMINAL;
        }
        String outcome = values == null ? "" : safeText(values.get("outcome"));
        String failure = normalizedStage + " " + outcome.toLowerCase(Locale.ROOT) + " "
            + safeText(values == null ? null : values.get("reason")).toLowerCase(Locale.ROOT) + " "
            + safeText(values == null ? null : values.get("status")).toLowerCase(Locale.ROOT);
        if (values != null && values.get("elapsedMs") instanceof Number elapsed && elapsed.longValue() >= 250L) {
            return DiagnosticSink.Priority.IMPORTANT;
        }
        if (containsAny(failure, "fail", "error", "timeout", "conflict", "reject", "blocked", "unavailable",
            "exception", "exhausted", "empty", "missing", "stalled") || normalizedStage.contains("settlement")) {
            return DiagnosticSink.Priority.IMPORTANT;
        }
        return DiagnosticSink.Priority.NORMAL;
    }

    static boolean healthyProgress(DiagnosticEvent event) {
        if (event == null || event.elapsedMillis() >= 250L || event.priority().atLeast(DiagnosticSink.Priority.IMPORTANT)) {
            return false;
        }
        String stage = event.stage().toLowerCase(Locale.ROOT);
        return PROGRESS_STAGES.contains(stage) || stage.endsWith("_list_page")
            || stage.endsWith("_list_progress") || stage.endsWith("_render_publication");
    }

    static String progressKey(DiagnosticEvent event) {
        StringBuilder key = new StringBuilder(event.stage());
        if (event.stage().endsWith("_conversion")) {
            return key.toString();
        }
        if (event.identity() != null && event.identity().serverId() != null) {
            key.append('|').append(event.identity().serverId().canonicalText());
        }
        if (event.identity() != null && event.identity().requestId() != null) {
            key.append('|').append(event.identity().requestId());
        }
        if (event.identity() != null && event.identity().generation() != null) {
            key.append('|').append(event.identity().generation());
        }
        if (event.identity() != null && event.identity().typedKey() != null) {
            key.append('|').append(event.identity().typedKey().canonicalText());
        }
        if (event.identity() != null && event.identity().operation() != null) {
            key.append('|').append(event.identity().operation().canonicalText());
        }
        return key.toString();
    }

    static boolean sensitiveField(String field) {
        if (field == null || field.isBlank()) {
            return true;
        }
        String normalized = field.toLowerCase(Locale.ROOT);
        if (SENSITIVE_FIELDS.stream().anyMatch(sensitive -> !sensitive.equals("ip") && normalized.contains(sensitive))) {
            return true;
        }
        String tokenized = field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        for (String token : tokenized.split("[_\\-.]")) {
            if (SENSITIVE_FIELDS.contains(token)) return true;
        }
        return false;
    }

    static String safeText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        if (text.isEmpty()) {
            return "";
        }
        if (structured(text) || SENSITIVE_ASSIGNMENT.matcher(text).find() || ABSOLUTE_PATH.matcher(text).find()
            || SQL_TEXT.matcher(text).find() || STACK_TEXT.matcher(text).find()) {
            return "[redacted]";
        }
        return limitedText(text);
    }

    static String safeThread(String value) {
        String safe = safeText(value);
        return safe.isBlank() ? "unknown" : safe;
    }

    static boolean truthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "0", "false", "no", "off", "disabled" -> false;
            default -> true;
        };
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static String readProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String readEnvironment(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static boolean structured(String text) {
        return text.length() > 1 && ((text.charAt(0) == '{' && text.charAt(text.length() - 1) == '}')
            || (text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']'));
    }

    private static String limitedText(String value) {
        return value.length() <= MAX_VALUE_CHARS ? value : value.substring(0, MAX_VALUE_CHARS - 3) + "...";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
