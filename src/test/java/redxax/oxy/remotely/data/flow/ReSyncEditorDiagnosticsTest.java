package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.contract.EditorDiagnostic;
import restudio.resync.flow.contract.EditorError;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReSyncEditorDiagnosticsTest {
    @Test
    void pointsRequiredInputGuidanceToHighlightedNode() {
        EditorError error = new EditorError("SAVE_FAILED", "command", "home", "Command Needs Attention",
            "Fix the highlighted issues before saving.", List.of(
                new EditorDiagnostic(EditorDiagnostic.Severity.ERROR, "REQUIRED_INPUT_MISSING", "send_message", "message", "",
                    "Required input has no connection or literal value", "Connect message or provide a value")));

        String payload = EditorError.PREFIX + new Gson().toJson(error);
        EditorError parsed = ReSyncEditorDiagnostics.parse(payload);

        assertNotNull(parsed);
        assertEquals("Command Needs Attention", ReSyncEditorDiagnostics.title(parsed));
        assertEquals("Connect or enter a value for Message in the highlighted node.", ReSyncEditorDiagnostics.summary(parsed));
    }

    @Test
    void rejectsLegacyMessagesAsStructuredDiagnostics() {
        assertNull(ReSyncEditorDiagnostics.parse("SAVE_FAILED - Required input missing"));
    }
}
