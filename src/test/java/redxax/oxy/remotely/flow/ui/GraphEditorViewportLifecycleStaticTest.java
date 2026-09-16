package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorViewportLifecycleStaticTest {
    @Test
    void onlyDocumentRetirementMakesTheSameResourceEligibleForAnotherInitialFit() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        String request = method(source, "private void requestInitialViewportFit()", "public String getDesktopAppId()");
        String close = method(source, "protected void studioDocumentClosed(StudioDocument document)",
            "protected void studioDocumentRenamed(StudioDocument previous, StudioDocument renamed)");

        assertFalse(request.contains("initialViewportFittedKeys.remove"));
        assertTrue(close.contains("initialViewportFittedKeys.remove(document.key())"));
    }

    @Test
    void topologyPublicationAndTabSelectionCannotRefitAnAlreadyFittedDocument() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        String schedule = method(source, "private void scheduleInitialViewportFit()", "private void cancelInitialViewportFit()");
        String selection = method(source, "protected void afterStudioDocumentSelected(StudioDocument document)",
            "protected boolean isStudioDocumentDirty(StudioDocument document)");

        assertTrue(schedule.contains("if (initialViewportFittedKeys.contains(initialViewportFitKey()))"));
        assertFalse(selection.contains("initialViewportFittedKeys.remove"));
    }

    @Test
    void explicitContentFocusStillCancelsAutomaticFit() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        String focus = method(source, "public void focusContentBranch(String branchPin)",
            "private void applyInitialViewportFitIfReady()");

        assertTrue(focus.contains("cancelInitialViewportFit()"));
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0);
        assertTrue(to > from);
        return source.substring(from, to);
    }
}
