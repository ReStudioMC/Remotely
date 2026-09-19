package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCoreInteractionInvariantTest {
    private static final Path SOURCE = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java");

    @Test
    void coreInteractionPermissionsAreSeparated() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("private boolean canMoveNode(String nodeId)"));
        assertTrue(source.contains("private boolean canMutateNodeStructure(String nodeId)"));
        assertTrue(source.contains("private boolean canDeleteNode(String nodeId)"));
        assertTrue(source.contains("access == CoreMutationAccess.CONTENT && !coreCapabilityAllowed(session, nodeId)"));
        assertTrue(source.contains("access == CoreMutationAccess.DELETE && isProtectedCoreNode(session, type, nodeId)"));
        assertTrue(source.contains("commitCoreMoveMutation(\"Node Positions\""));
        assertTrue(source.contains("commitCoreStructuralMutation(\"Connection Add\""));
        assertTrue(source.contains("commitCoreDeleteMutation(\"Node Delete\""));
    }

    @Test
    void acceptedMutationsQueueAndSavesWaitForSettlement() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("coreMutationQueue.addLast(command)"));
        assertTrue(source.contains("coreMutationQueue.removeLast()"));
        assertTrue(source.contains("startNextCoreMutation()"));
        assertTrue(source.contains("coreDeferredSaves.addLast(save)"));
        assertTrue(source.contains("DesignerSaveNotifications.startResumableExact(serverId"));
        assertTrue(source.contains("dispatchDeferredCoreSaves()"));
        assertTrue(source.contains("input.deferredMutation() && !matchesDeferredWorkspaceMutationTarget()"));
        assertFalse(source.contains("if (coreMutationCommitPending != null) {\n            return false;"));
    }

    @Test
    void projectionRefreshKeepsWidgetsAndViewportStable() throws IOException {
        String source = Files.readString(SOURCE);
        int render = source.indexOf("public void renderHandler(IDrawContext context");
        int updateTransforms = source.indexOf("updateTransforms(delta);", render);
        String renderEntry = source.substring(render, updateTransforms);

        assertTrue(source.contains("canRefreshStableCoreWidgets(projected)"));
        assertTrue(source.contains("refreshStableCoreWidgets(projected)"));
        assertTrue(source.contains("return activeStudioDocument.key();"));
        assertTrue(source.contains("refreshCoreProjectionIfChanged();"));
        assertFalse(renderEntry.contains("tick();"));
        assertFalse(renderEntry.contains("refreshCoreProjectionIfChanged();"));
    }

    @Test
    void readOnlyNodeKeepsOnlyItsAuthorizedCloseControl() throws IOException {
        String flowWidget = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/FlowNodeWidget.java"));
        String nodeWidget = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/NodeWidget.java"));

        assertTrue(flowWidget.contains("if (isEditorReadOnly())"));
        assertTrue(flowWidget.contains("mouseClickedCloseButton(event)"));
        assertTrue(flowWidget.contains("if (isEditorReadOnly()) {\n            return false;"));
        assertTrue(nodeWidget.contains("protected final boolean mouseClickedCloseButton(ReMouseEvent event)"));
    }
}
