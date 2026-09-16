package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import restudio.resync.flow.contract.EditorDiagnostic;
import restudio.resync.flow.contract.EditorError;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReSyncEditorDiagnostics {
    private static final Gson GSON = new Gson();

    private ReSyncEditorDiagnostics() {
    }

    public static EditorError parse(String message) {
        if (message == null || !message.startsWith(EditorError.PREFIX)) {
            return null;
        }
        try {
            return GSON.fromJson(message.substring(EditorError.PREFIX.length()), EditorError.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String title(EditorError error) {
        return error != null && !error.title().isBlank() ? error.title() : "Needs Attention";
    }

    public static String summary(EditorError error) {
        if (error == null) {
            return "Review the highlighted issue and try again.";
        }
        List<String> messages = new ArrayList<>();
        for (EditorDiagnostic diagnostic : error.diagnostics()) {
            String message = friendly(diagnostic);
            if (!message.isBlank() && !messages.contains(message)) {
                messages.add(message);
            }
            if (messages.size() == 3) {
                break;
            }
        }
        if (messages.isEmpty()) {
            return sentence(error.message().isBlank() ? "Review the highlighted issue and try again" : error.message());
        }
        int remaining = error.diagnostics().size() - messages.size();
        if (remaining > 0) {
            messages.add(remaining + " more issue" + (remaining == 1 ? "" : "s") + " need attention.");
        }
        return String.join("\n", messages);
    }

    public static String friendly(EditorDiagnostic diagnostic) {
        if (diagnostic == null) {
            return "";
        }
        String field = label(diagnostic.field());
        return switch (diagnostic.code()) {
            case "REQUIRED_INPUT_MISSING" ->
                "Connect or enter a value for " + fallback(field, "the required input") + " in the highlighted node.";
            case "LITERAL_TYPE_INVALID", "INVALID_LITERAL", "INPUT_LITERAL_INVALID" ->
                "Enter a valid value for " + fallback(field, "the highlighted input") + ".";
            case "UNKNOWN_NODE_TYPE" -> "Replace the highlighted node because its type is unavailable.";
            case "CONNECTION_SOURCE_MISSING", "CONNECTION_TARGET_MISSING" -> "Reconnect the highlighted node.";
            case "RESOURCE_REVISION_CONFLICT" -> "The server has a newer version. Reload to merge the latest changes.";
            default -> defaultMessage(diagnostic, field);
        };
    }

    public static String label(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String spaced = value.replace('-', ' ').replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim();
        String[] words = spaced.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static String defaultMessage(EditorDiagnostic diagnostic, String field) {
        String message = sentence(diagnostic.message());
        if (message.isBlank()) {
            message = field.isBlank() ? "Review the highlighted issue." : "Review " + field + ".";
        }
        if (!diagnostic.remediation().isBlank()) {
            String remediation = sentence(diagnostic.remediation());
            if (!message.equalsIgnoreCase(remediation)) {
                message += " " + remediation;
            }
        }
        return message;
    }

    private static String sentence(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1) + (trimmed.endsWith(".") ? "" : ".");
    }

    private static String fallback(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
