package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncContentBrowserDeleteHistoryStaticTest {
    private static final Path SOURCE = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java");

    @Test
    void deleteUndoRecreatesCapturedTypedPayloadBeforePresentation() throws IOException {
        String source = Files.readString(SOURCE);
        String restore = method(source, "private CompletableFuture<Boolean> restoreResourceSettled", "private void undoBrowserEdit");
        String apply = method(source, "private CompletableFuture<BrowserStateResult> applyBrowserState", "private CompletableFuture<Boolean> saveProjectMetadataSettled");

        assertTrue(restore.contains("ReSyncResourceType.byTypeId(snapshot.type())"));
        assertTrue(restore.contains("DesignerSaveNotifications.startExact(serverId, type, snapshot.id(), snapshot.id())"));
        assertTrue(restore.contains("manager.saveGraph(serverId, type, FlowSerializer.deserialize(snapshot.payload()), ticket)"));
        assertTrue(restore.contains("manager.saveJsonResource(serverId, type, gson.fromJson(snapshot.payload(), JsonObject.class), ticket)"));
        assertTrue(apply.contains("if (targetEntry != null)"));
        assertTrue(apply.contains("payloadSettlements.put(key, restoreResourceSettled(manager, targetSnapshot))"));
        String recreate = apply.substring(apply.indexOf("} else if (targetEntry != null)"));
        assertFalse(recreate.contains("currentSnapshot == null"));
        assertTrue(apply.indexOf("restoreResourceSettled(manager, targetSnapshot)") < apply.indexOf("saveProjectMetadataSettled(manager, target)"));
    }

    @Test
    void redoUsesSettledDeleteAndNeverDeletesFromPresentationAlone() throws IOException {
        String source = Files.readString(SOURCE);
        String apply = method(source, "private CompletableFuture<BrowserStateResult> applyBrowserState", "private CompletableFuture<Boolean> saveProjectMetadataSettled");

        assertTrue(apply.contains("ResourceSnapshot sourceSnapshot = currentStateSnapshots.get(key)"));
        assertTrue(apply.contains("!sourceSnapshot.type().equals(currentSnapshot.type())"));
        assertTrue(apply.contains("manager.deleteResourceSettled(screen.studioServerId(), type, sourceSnapshot.id())"));
        assertTrue(apply.contains("result != null && result.deleted()"));
        assertTrue(apply.contains("settledDeleteKeys.contains(key)"));
        assertTrue(apply.contains("payloadSettlements.put(key, CompletableFuture.completedFuture(true))"));
        assertFalse(apply.contains("deleteResourceSettled(screen.studioServerId(), type, currentEntry.getId())"));
    }

    @Test
    void historyAndStudioDocumentsAdvanceOnlyAfterTerminalSettlement() throws IOException {
        String source = Files.readString(SOURCE);
        String undo = method(source, "private void undoBrowserEdit", "private boolean worldGenHistoryAffected");
        String finish = method(source, "private void restoreHistoryEntry(BrowserHistoryEntry edit, boolean before, BrowserStateResult result", "private CompletableFuture<BrowserStateResult> applyBrowserState");

        assertTrue(undo.contains("BrowserHistoryEntry edit = undoHistory.peekLast()"));
        assertTrue(undo.contains("BrowserHistoryEntry edit = redoHistory.peekLast()"));
        assertFalse(undo.contains("undoHistory.removeLast()"));
        assertFalse(undo.contains("redoHistory.removeLast()"));
        assertTrue(finish.contains("boolean restored = result.restored() && source.peekLast() == edit"));
        assertTrue(finish.indexOf("if (!restored)") < finish.indexOf("source.removeLast()"));
        assertTrue(finish.contains("settledHistoryDeleteKeys.put(edit, Set.copyOf(settled))"));
        assertTrue(finish.contains("settledHistoryDeleteKeys.remove(edit)"));
        assertTrue(finish.contains("screen.removeStudioDocuments(result.deletedKeys())"));
        assertFalse(finish.contains("!targetEntries.containsKey(key)"));
    }

    private static String method(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}
