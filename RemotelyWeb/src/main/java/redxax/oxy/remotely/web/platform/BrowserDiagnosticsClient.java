package redxax.oxy.remotely.web.platform;

import com.google.gson.JsonObject;
import org.teavm.jso.JSBody;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.http.HttpRequest;
import restudio.rescreen.platform.http.HttpResponse;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.rescreen.platform.browser.BrowserRuntimeDiagnostics;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class BrowserDiagnosticsClient implements BrowserRuntimeDiagnostics {
    private static final Set<String> SOURCES = Set.of(
            "scheduled-task",
            "uncaught-error",
            "unhandled-rejection",
            "long-frame",
            "queue-backlog",
            "freeze-heartbeat",
            "synchronous-input");
    private static final Set<String> FAILURE_TYPES = Set.of(
            "AggregateError",
            "Error",
            "EvalError",
            "Exception",
            "IllegalArgumentException",
            "IllegalStateException",
            "IndexOutOfBoundsException",
            "NullPointerException",
            "RangeError",
            "ReferenceError",
            "RuntimeException",
            "SecurityException",
            "SyntaxError",
            "TypeError",
            "URIError",
            "UnhandledRejection");
    private static final Set<String> SCREEN_IDS = Set.of(
            "file-explorer",
            "file-editor",
            "global-settings",
            "server-details",
            "server-manager");
    private static final long DEDUPE_MILLIS = 30_000L;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_REPORTS_PER_WINDOW = 24;
    private static final int MAX_DEDUPE_ENTRIES = 128;
    private final HttpTransport transport;
    private final Map<String, Long> lastReports = new HashMap<>();
    private long windowStartedAt;
    private int reportsInWindow;
    private long sequence;

    public BrowserDiagnosticsClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public synchronized void report(Event event) {
        if (event == null || transport == null || !BrowserLaunchSession.authenticated() || BrowserLaunchSession.metadata().demo()) {
            return;
        }
        String source = source(event.source());
        if (source.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - windowStartedAt >= WINDOW_MILLIS || windowStartedAt <= 0L) {
            windowStartedAt = now;
            reportsInWindow = 0;
            lastReports.clear();
        }
        String failureType = failureType(event.failureType());
        String detail = detail(event.detail());
        String stack = stack(event.stack());
        String key = source + '|' + failureType + '|' + detail + '|' + stack;
        Long last = lastReports.get(key);
        if (last != null && now - last < DEDUPE_MILLIS || reportsInWindow >= MAX_REPORTS_PER_WINDOW) {
            return;
        }
        if (lastReports.size() >= MAX_DEDUPE_ENTRIES) {
            lastReports.clear();
        }
        lastReports.put(key, now);
        reportsInWindow++;
        JsonObject body = new JsonObject();
        body.addProperty("buildId", bounded(buildId(), 128));
        body.addProperty("screenId", screenId(event.screenId()));
        body.addProperty("source", source);
        body.addProperty("correlationId", source + '-' + ++sequence);
        body.addProperty("failureType", failureType);
        body.addProperty("detail", detail);
        body.addProperty("stack", stack);
        body.addProperty("queueBacklog", boundMetric(event.queueBacklog(), 100_000));
        body.addProperty("frameDurationMillis", boundMetric(event.frameDurationMillis(), 60_000));
        body.addProperty("frameIntervalMillis", boundMetric(event.frameIntervalMillis(), 60_000));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BrowserLaunchSession.apiBaseUrl() + "/remotely-web/diagnostics"))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(BrowserJson.write(body)))
                    .build();
            Async<HttpResponse<Void>> result = transport.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            result.whenComplete((ignored, failure) -> { });
        } catch (Throwable ignored) {
        }
    }

    private static String source(String value) {
        return value != null && SOURCES.contains(value) ? value : "";
    }

    private static String failureType(String value) {
        return value != null && FAILURE_TYPES.contains(value) ? value : "";
    }

    private static String detail(String value) {
        String candidate = bounded(value, 96);
        return FAILURE_TYPES.contains(candidate) ? candidate : "";
    }

    private static String stack(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        String[] lines = bounded(value, 16_384).replace('\r', '\n').split("\\n");
        for (String line : lines) {
            String frame = stackFrame(line);
            if (frame.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(frame);
            if (result.length() >= 2048) {
                break;
            }
        }
        return bounded(result.toString(), 2048);
    }

    private static String stackFrame(String value) {
        String line = value == null ? "" : value.strip();
        if (line.isBlank()) {
            return "";
        }
        int at = line.indexOf("at ");
        if (at >= 0) {
            line = line.substring(at + 3).strip();
        }
        int atSign = line.indexOf('@');
        if (atSign >= 0) {
            line = line.substring(0, atSign);
        }
        int opening = line.indexOf('(');
        if (opening >= 0) {
            line = line.substring(0, opening).strip();
        }
        int colon = line.indexOf(':');
        if (colon >= 0) {
            line = line.substring(0, colon);
        }
        int slash = Math.max(line.lastIndexOf('/'), line.lastIndexOf('\\'));
        if (slash >= 0) {
            line = line.substring(slash + 1);
        }
        int dot = line.lastIndexOf('.');
        if (dot >= 0) {
            line = line.substring(dot + 1);
        }
        return identifier(line, 96);
    }

    private static String screenId(String value) {
        String candidate = bounded(value, 96);
        if (candidate.isBlank()) {
            Screen current = ScreenManager.currentScreen;
            candidate = current == null ? "" : bounded(current.getDesktopAppId(), 96);
        }
        return SCREEN_IDS.contains(candidate) ? candidate : "other";
    }

    private static String identifier(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && result.length() < maxLength; index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '$') {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static int boundMetric(int value, int maximum) {
        return Math.clamp(value, 0, maximum);
    }

    @JSBody(script = "const meta = document.querySelector('meta[name=\"remotely-build-id\"]'); return meta && meta.content ? String(meta.content).slice(0, 128) : '';" )
    private static native String buildId();
}
