package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorWorkspaceCaptureRecoveryTest {
    @Test
    void saveAdmissionAndCancellationHaveOneAtomicWinner() {
        GraphEditorScreen.WorkspaceSaveAdmission cancelled = new GraphEditorScreen.WorkspaceSaveAdmission();

        assertTrue(cancelled.cancel());
        assertFalse(cancelled.begin());
        assertFalse(cancelled.cancel());
        assertFalse(cancelled.started());

        GraphEditorScreen.WorkspaceSaveAdmission admitted = new GraphEditorScreen.WorkspaceSaveAdmission();

        assertTrue(admitted.begin());
        assertTrue(admitted.started());
        assertFalse(admitted.cancel());
    }

    @Test
    void collaborativeCaptureReturnsThroughAMailboxBeforeRenderOwnedValidation() throws IOException {
        String source = source();
        String callback = between(source, "private void completeCollaborativeWorkspaceCapture(",
            "private void applyCollaborativeWorkspaceCaptureResult()");
        String apply = between(source, "private void applyCollaborativeWorkspaceCaptureResult()",
            "private void submitWorkspaceCapture(WorkspaceCaptureJob job)");

        assertTrue(callback.contains("collaborativeWorkspaceCaptureResult.accumulateAndGet"));
        assertFalse(callback.contains("workspaceCaptureJob"));
        assertFalse(callback.contains("collaborationLifecycle()"));
        assertFalse(callback.contains("collaborationEditVersion()"));
        assertFalse(callback.contains("workspaceFenceActive"));
        assertTrue(apply.contains("workspaceCaptureJob != job || lifecycleClosed"));
        assertTrue(apply.contains("snapshot.lifecycle() != collaborative.collaborationLifecycle()"));
        assertTrue(apply.contains("snapshot.editVersion() != collaborative.collaborationEditVersion()"));
        assertTrue(apply.contains("submitWorkspaceCapture(prepared);"));
    }

    @Test
    void abandonedCaptureExpiresWithoutAdmittingItsLateResult() throws IOException {
        String source = source();
        String apply = between(source, "private void applyWorkspaceSnapshotResult()",
            "private boolean isCollaborativeSnapshotCurrent(");
        String expire = between(source, "private void expireWorkspaceCapture()",
            "private boolean isCollaborativeSnapshotCurrent(");

        assertTrue(source.contains("private static final long WORKSPACE_CAPTURE_TIMEOUT_MILLIS = 5_000L;"));
        assertTrue(source.contains("expireWorkspaceCapture();"));
        assertTrue(source.contains("captureJob.attempt() != result.captureAttempt()"));
        assertTrue(source.contains("workspaceCaptureJob = null;\n        workspaceFrozenLeaseCancellation++;\n        workspaceSnapshotFrozen = false;"));
        assertTrue(source.contains("failWorkspaceCapture(job, \"Save Preparation Timed Out\");"));
        assertTrue(source.contains("collaborativeWorkspaceCaptureResult.set(null);"));
        assertTrue(source.contains("private static final int WORKSPACE_CAPTURE_RETRY_LIMIT = 3;"));
        assertTrue(source.contains("workspaceCaptureFailureCount > WORKSPACE_CAPTURE_RETRY_LIMIT"));
        assertTrue(source.contains("workspaceCaptureTerminal(generation, mutationVersion, documentKey)"));
        assertTrue(source.contains("private boolean workspaceCaptureTerminal(long generation, long mutationVersion, String documentKey)"));
        assertTrue(source.contains("private void settleTerminalWorkspaceCapture(long generation, long mutationVersion, String documentKey)"));
        assertTrue(source.contains("notifyWorkspaceGraphSaveIssue(saveRequest, \"Snapshot Failed, Reopen Editor\")"));
        assertTrue(source.contains("frozenRequest.completion().accept(false);"));
        assertTrue(source.contains("if (!result.admitted() && issue.isBlank() && !isWorkspaceFenceCurrent(result.request().fence()))"));
        assertTrue(source.contains("if (!result.admitted() && !issue.isBlank())"));
        assertTrue(source.contains("&& job.saveAdmission().cancel())"));
        assertTrue(expire.indexOf("failWorkspaceCapture(job, \"Save Preparation Timed Out\");")
            < expire.indexOf("job.saveAdmission().started()"));
        assertTrue(source.contains("Snapshot Failed, Reopen Editor"));
        assertTrue(source.contains("clearWorkspaceCaptureFailures();"));
        assertTrue(apply.contains("workspaceSnapshotFrozen = false;"));
        assertFalse(source.contains("workspaceSnapshotFrozen = true;\n        workspaceSnapshotRetryGeneration = generation;"));
        assertFalse(source.contains("workspaceSnapshotRetryGeneration >= 0L || workspaceGraphApplyFrozen"));
        assertFalse(source.contains("if (deferWorkspaceMutation(() -> mouseScrolled(event)))"));
        assertFalse(source.contains("if (deferWorkspaceMutation(() -> mouseClicked(event)))"));
        assertFalse(source.contains("if (deferWorkspaceMutation(() -> mouseDragged(event)))"));
        assertFalse(source.contains("if (deferWorkspaceMutation(() -> mouseReleased(event)))"));
        assertFalse(source.contains("if (deferWorkspaceMutation(() -> keyPressed(event)))"));
        assertFalse(source.contains("if (deferWorkspaceMutation(() -> textInput(event)))"));
        assertTrue(source.contains("captureJob.cancellation() != workspaceFrozenLeaseCancellation"));
        assertTrue(source.contains("captureJob.cancellation() == workspaceFrozenLeaseCancellation"));
        assertTrue(source.contains("studioMode && handleStudioWorkspaceMouseClicked(event)"));
        assertTrue(source.indexOf("routeStudioContextMenuMouseClicked(event)")
            < source.indexOf("studioMode && handleStudioWorkspaceMouseClicked(event)"));
        assertTrue(source.contains("protected boolean discardStudioDocumentForClose(StudioDocument document)"));
        assertTrue(source.contains("retireWorkspaceCaptureForDiscard();"));
    }

    @Test
    void captureWorkerPublishesInFinallyWithoutReadingRenderOwnedFences() throws IOException {
        String source = source();
        String prepare = between(source, "private void prepareWorkspaceCapture(WorkspaceCaptureJob job)",
            "private void offerWorkspaceSnapshotResult(WorkspaceSnapshotResult offered)");
        String apply = between(source, "private void applyCollaborativeWorkspaceCaptureResult()",
            "private void submitWorkspaceCapture(WorkspaceCaptureJob job)");

        assertTrue(apply.contains("job.cancellation() != workspaceFrozenLeaseCancellation"));
        assertTrue(apply.contains("!workspaceFenceActive(job.fence())"));
        assertTrue(prepare.contains("} finally {"));
        assertTrue(prepare.contains("offerWorkspaceSnapshotResult(result);"));
        assertTrue(prepare.indexOf("saveGraph = snapshot.materializeGraph();")
            < prepare.indexOf("job.saveAdmission().begin()"));
        assertTrue(prepare.indexOf("job.saveAdmission().begin()")
            < prepare.indexOf("request.manager().saveGraph("));
        assertTrue(prepare.indexOf("dispatchAuthorityFencedSave(expected")
            < prepare.indexOf("request.manager().saveGraph("));
        assertTrue(prepare.contains("saveIssue.isBlank() && job.cancellation() != workspaceFrozenLeaseCancellation"));
        assertFalse(prepare.contains("workspaceFenceActive("));
        assertFalse(prepare.contains("typedGraphCanSave("));
    }

    @Test
    void captureFenceTracksSemanticDocumentOwnershipInsteadOfDerivedRenderPublication() throws IOException {
        String source = source();
        String captureFence = between(source, "private boolean workspaceCaptureFenceCurrent(WorkspaceFence fence)",
            "private void scheduleWorkspaceSnapshotRetry(WorkspaceFence fence)");
        String apply = between(source, "private void applyWorkspaceSnapshotResult()",
            "private void expireWorkspaceCapture()");
        String capture = between(source, "protected void captureStableWorkspaceDocument(long mutationVersion)",
            "private void completeCollaborativeWorkspaceCapture(");

        assertTrue(captureFence.contains("!isCoreOwnedStudioDocument()"));
        assertTrue(captureFence.contains("fence.generation() == workspacePublicationGeneration"));
        assertTrue(captureFence.contains("fence.mutationVersion() == workspaceMutationVersion"));
        assertTrue(captureFence.contains("fence.documentKey().equals(workspaceDocumentKey())"));
        assertFalse(captureFence.contains("topologyRevision"));
        assertFalse(captureFence.contains("workspaceCatalogFence"));
        assertTrue(captureFence.contains(
            "protected final void scheduleWorkspaceSnapshotRetry(long generation, long mutationVersion)"));
        assertTrue(captureFence.contains("scheduleWorkspaceSnapshotRetry(workspaceFence(generation, mutationVersion));"));
        assertTrue(apply.contains("if (captureJob.cancellation() != workspaceFrozenLeaseCancellation"));
        assertTrue(apply.contains("|| !workspaceCaptureFenceCurrent(result.fence())"));
        assertTrue(apply.contains("captureJob.collaborative() == null && captureJob.graph() != graph"));
        assertTrue(apply.contains("scheduleWorkspaceSnapshotRetry(result.fence())"));
        assertTrue(capture.contains("if (isCoreOwnedStudioDocument()) {\n            return;"));
        assertTrue(source.contains("private String workspaceSnapshotRetryDocumentKey = \"\";"));
        assertTrue(source.contains("!documentKey.equals(workspaceDocumentKey())"));
        assertTrue(source.contains("private String workspaceCaptureFailureDocumentKey = \"\";"));
        assertTrue(source.contains("workspaceCaptureFailureDocumentKey.equals(documentKey)"));
        assertTrue(source.contains("private boolean supportsWorkspace(String type) {\n        if (isCoreOwnedStudioDocument())"));
    }

    private String source() throws IOException {
        return Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}
