package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StudioCoreActivationStateMachineTest {
    @Test
    void sessionBindPreparesUntilProjectionAndWidgetTopologyAreReady() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java"));
        String drain = method(source, "void drainCoreOpenIntents()");
        String bind = method(source, "protected boolean bindStudioCoreDocument(");

        assertTrue(drain.contains("coreStudioDocumentTerminal(document)"));
        assertTrue(drain.contains("coreStudioDocumentReady(document)"));
        assertTrue(drain.contains("intent.phase() == CoreOpenPhase.REQUESTING"));
        assertTrue(drain.contains("intent.preparing(true)"));
        assertTrue(drain.indexOf("coreStudioDocumentReady(document)")
            < drain.indexOf("pendingCoreOpenIntents.remove(intent.key())"));
        assertTrue(drain.contains("intent.phase() == CoreOpenPhase.PREPARING"));
        assertFalse(bind.contains("pendingCoreOpenIntents.remove(key)"));
    }

    @Test
    void backgroundMembershipCannotPersistActivation() throws IOException {
        String studio = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java"));
        String manager = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java"));
        String bind = method(studio, "protected boolean bindStudioCoreDocument(");
        String persistence = method(manager,
            "public void persistOpenProjectDocument(String serverId, String type, String id, String title, boolean activate)");

        assertTrue(bind.contains("persistOpenStudioDocument(type, id, documentTitle, activate)"));
        assertTrue(persistence.contains("boolean active = activate || existing != null && existing.active()"));
        assertTrue(persistence.contains("if (activate)"));
        assertTrue(persistence.contains("if (activate && !key.equals(edit.selectedResourceKey()))"));
        assertTrue(persistence.contains("edit.selectedResourceKey(key)"));
        assertTrue(persistence.indexOf("if (activate && !key.equals(edit.selectedResourceKey()))")
            < persistence.indexOf("edit.selectedResourceKey(key)"));
    }

    @Test
    void pendingSameKeyKeepsVisibleDocumentWithoutAReplacementLoadingSurface() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java"));
        String restore = method(source, "private void restorePendingCoreStudioSelection(");
        String retain = method(source, "private void retainVisibleStudioDocument()");
        String loading = method(source, "boolean coreStudioLoadingVisible()");
        String save = method(source, "protected final boolean blockPendingCoreStudioSave()");
        String bind = method(source, "protected boolean bindStudioCoreDocument(");

        assertTrue(restore.contains("Objects.equals(previous.key(), pendingCoreStudioDocumentKey)"));
        assertTrue(restore.contains("setStudioTabsManagerActiveTab"));
        assertFalse(restore.contains("clearActiveStudioDocument()"));
        assertTrue(retain.contains("activeStudioDocument.key()"));
        assertTrue(retain.contains("setStudioTabsManagerActiveTab(activeTab)"));
        assertTrue(loading.contains("return false"));
        assertFalse(source.contains("Loading Core Graph"));
        assertTrue(save.contains("new Notification(\"Core Editor\", \"Editor Loading\""));
        assertFalse(save.contains("activeStudioDocument.key(), pendingCoreStudioDocumentKey"));
        assertTrue(bind.contains("if (document.coreSession() == null)"));
        assertTrue(bind.indexOf("if (document.coreSession() == null)")
            < bind.indexOf("studioDocumentClosed(document)"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int body = source.indexOf('{', start);
        int depth = 0;
        for (int index = body; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new IllegalStateException("Method body is incomplete");
    }
}
