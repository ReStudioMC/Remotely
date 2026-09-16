package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedResourceEditorContractTest {
    @Test
    void standaloneMutableEditorsCaptureThroughTheSharedBoundary() throws IOException {
        assertEditor("GuiDesignerScreen.java", "guiDraft", "saveGui(serverId, snapshot.serialize");
        assertEditor("ScoreboardDesignerScreen.java", "scoreboardDraft", "saveScoreboard(serverId, snapshot.serialize");
        assertEditor("TabDesignerScreen.java", "tabDraft", "saveTab(serverId, snapshot.serialize");
        assertEditor("FocusedJsonResourceDesignerScreen.java", "resourceDraft", "saveJsonResource(serverId, resourceType,");
        assertEditor("DialogDesignerScreen.java", "dialogDraft", "saveJsonResource(serverId, ReSyncResourceType.DIALOG,");
        assertEditor("AdvancementDesignerScreen.java", "treeDraft", "saveJsonResource(serverId, ReSyncResourceType.ADVANCEMENT_TREE,");
        assertTrue(source("DialogDesignerScreen.java").contains("snapshot.serialize(payload -> ensureDefaults("));
        assertTrue(source("AdvancementDesignerScreen.java").contains("snapshot.serialize(payload -> sanitizeTree("));
    }

    @Test
    void focusedJsonMutatorsAndAsyncIconApplyRespectFrozenOwnership() throws IOException {
        String focused = source("FocusedJsonResourceDesignerScreen.java");
        String motd = source("MotdDesignerScreen.java");
        String npc = source("NpcDesignerScreen.java");

        assertTrue(focused.contains("protected boolean deferResourceMutation(Runnable mutation)"));
        assertTrue(focused.contains("if (deferResourceMutation(() -> putJsonText(field, value)))"));
        assertTrue(focused.contains("if (deferResourceMutation(() -> putJsonPathElement(field, value)))"));
        assertTrue(motd.contains("applyResourceMutation(() ->"));
        assertTrue(motd.contains("VersionedEditorDraft.submitBackground("));
        assertTrue(npc.contains("VersionedEditorDraft.submitBackground("));
        assertFalse(motd.contains("CompletableFuture.runAsync("));
        assertFalse(npc.contains("CompletableFuture.supplyAsync("));
    }

    @Test
    void motdEditorUsesTheRuntimeResourceFields() throws IOException {
        String motd = source("MotdDesignerScreen.java");

        assertTrue(motd.contains("List.of(\"line1\", \"line2\", \"priority\", \"playerCountMode\")"));
        assertTrue(motd.contains("return \"line1\".equals(field) || \"line2\".equals(field);"));
        assertFalse(motd.contains("motdText"));
    }

    @Test
    void customContentQuickEditDefersSemanticsWhileRawInputStaysImmediate() throws IOException {
        String content = source("ContentDesignerScreen.java");
        String quickEdit = method(content, "private void applyQuickEdit()");

        assertTrue(quickEdit.contains("submitFrozenWorkspaceGraph(frozenGraph ->"));
        assertTrue(quickEdit.contains("CustomContentGraphAdapter.toDefinition(frozenGraph)"));
        for (String input : List.of("mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled", "keyPressed", "textInput")) {
            String rawInput = method(content, "public boolean " + input + "(");
            assertFalse(rawInput.contains("deferFrozenWorkspaceMutation("), input + " must stay immediate");
            assertFalse(rawInput.contains("() -> " + input + "(event)"), input + " must never replay");
        }
    }

    @Test
    void saveFailuresUseExactNotificationTickets() throws IOException {
        String notifications = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/DesignerSaveNotifications.java"));
        assertTrue(notifications.contains("public static final class SaveTicket"));
        assertTrue(notifications.contains("pendingKey(resourceKey, ticket.sequence())"));
        assertTrue(notifications.contains("public static SaveTarget failExact(SaveTicket ticket, String message)"));
        for (String editor : List.of("GuiDesignerScreen.java", "ScoreboardDesignerScreen.java", "TabDesignerScreen.java",
            "FocusedJsonResourceDesignerScreen.java", "DialogDesignerScreen.java", "AdvancementDesignerScreen.java")) {
            String source = source(editor);
            assertTrue(source.contains("DesignerSaveNotifications.startExact("), editor);
            assertTrue(source.contains("DesignerSaveNotifications.failExact(ticket"), editor);
            assertTrue(source.contains(".acknowledge(ticket, lease::materialize)"), editor);
            assertTrue(source.contains("new Notification(\"Save Refresh Failed\", \"Save Paused\""), editor);
        }
    }

    @Test
    void snapshotExecutorAndOverflowAdmissionStayOrderedAndBounded() throws IOException {
        String draft = source("VersionedEditorDraft.java");
        assertTrue(draft.contains("new ThreadPoolExecutor(1, 1"));
        assertTrue(draft.contains("deferred.addLast(new DeferredMutation(mutation, null));"));
        assertTrue(draft.contains("enterBackpressure();"));
        assertTrue(draft.contains("job.cancelBeforeDispatch()"));
    }

    @Test
    void customContentPanelDerivationIsCoalescedAndBudgeted() throws IOException {
        String content = source("ContentDesignerScreen.java");
        assertTrue(content.contains("submitFrozenWorkspaceGraph(frozenGraph ->"));
        assertTrue(content.contains("ContentPanelSnapshot"));
        assertTrue(content.contains("contentPanelDiffs"));
        assertTrue(content.contains("workspaceMutationVersion()"));
        assertTrue(content.contains("applied < 2"));
        assertTrue(content.contains("providerAssetOptions(definition.getProvider(), type)"));
        assertTrue(content.contains("queueContentPanelRetirement(container)"));
        assertTrue(content.contains("queueContentPanelWidget(container"));
        assertTrue(content.contains("container.removeLastWidget(widget)"));
        assertFalse(content.contains("clearContentPanelWidgets(container)"));
    }

    private static void assertEditor(String file, String draft, String workerSave) throws IOException {
        String source = source(file);
        assertTrue(source.contains("VersionedEditorDraft"));
        assertTrue(source.contains(draft + ".drain();"));
        assertTrue(source.contains(draft + ".defer("));
        assertTrue(source.contains(draft + ".capture("));
        assertTrue(source.contains(workerSave));
        assertTrue(source.contains("snapshot.serialize"));
        assertFalse(source.contains("flowManager.saveGui(serverId, gui);"));
        assertFalse(source.contains("flowManager.saveScoreboard(serverId, scoreboard);"));
        assertFalse(source.contains("flowManager.saveTab(serverId, tab);"));
        assertFalse(source.contains("manager.saveJsonResource(serverId, resourceType, resource);"));
    }

    private static String source(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui", file));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "Missing method " + signature);
        int body = source.indexOf('{', start);
        assertTrue(body >= 0, "Missing method body " + signature);
        int depth = 0;
        for (int index = body; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new IllegalStateException("Unclosed method " + signature);
    }
}
