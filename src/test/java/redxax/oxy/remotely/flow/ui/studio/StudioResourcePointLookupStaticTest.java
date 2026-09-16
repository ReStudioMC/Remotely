package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioResourcePointLookupStaticTest {
    @Test
    void studioOpenAndPanelPathsUseExactResourceReads() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java"));

        assertTrue(source.contains("String requestedServerId = studioServerId();"));
        assertTrue(source.contains("manager.snapshotResource(requestedServerId, type, id)"));
        assertTrue(source.contains("STUDIO_RESOURCE_OPENS.execute(() ->"));
        assertTrue(source.contains("if (!lease.isCurrent())"));
        assertTrue(source.contains("mountStudioResource(prepared)"));
        assertTrue(source.contains("manager.getProjectResource(studioServerId(), type, id)"));
        assertTrue(source.contains("drainStudioPanelSaves(key, manager)"));
        assertTrue(source.contains("manager.saveStudioPanelResource(intent.serverId(), intent.type(), lease, resource, intent.ticket())"));
        assertFalse(source.contains("The resource changed before the save was queued"));
        assertFalse(source.contains("getGuisForServer(studioServerId()).get("));
        assertFalse(source.contains("getScoreboardsForServer(studioServerId()).get("));
        assertFalse(source.contains("getTabsForServer(studioServerId()).get("));
        assertFalse(source.contains("getJsonResourcesForServer(studioServerId(), type).get("));
        assertFalse(source.contains("getCustomContentForServer(studioServerId()).get("));
    }

    @Test
    void previewRenderPathsUseCachedSelectedResources() throws IOException {
        for (String file : List.of("GuiStudioPreviewView.java", "ScoreboardStudioPreviewView.java", "TabStudioPreviewView.java")) {
            String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio", file));
            assertTrue(source.contains("private void refresh()"));
            assertFalse(source.contains("ForServer("));
            assertFalse(source.substring(source.indexOf("void renderPreview"), source.indexOf("void render(")).contains("FlowManager.getInstance()"));
        }
    }

    @Test
    void contentBrowserUsesPrecomputedIconsWithoutEagerFallback() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java"));

        assertTrue(source.contains("manager.getCustomContentType(screen.studioServerId(), resource.getId())"));
        assertTrue(source.contains("String iconPath = resourceIconPaths.get(resource.key())"));
        assertFalse(source.contains("resourceIconPaths.getOrDefault(resource.key(), screen.studioResourceIconPath"));
    }

    @Test
    void contentBrowserHistoryMaterializesSnapshotLeasesOffTheTickPath() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java"));

        assertTrue(source.contains("manager.snapshotProjectMetadata(screen.studioServerId())"));
        assertTrue(source.contains("BROWSER_HISTORY.execute(() -> materializeBrowserEdit"));
        assertTrue(source.contains("manager.projectMetadataStamp(screen.studioServerId())"));
        assertTrue(source.contains("scheduleRebuild(metadataStamp)"));
        assertTrue(source.contains("client.collaboration().activityRevision()"));
        assertFalse(source.contains("collaborationSignature()"));
        assertFalse(source.contains("gson.toJson(manager.getProjectMetadata"));
    }

    @Test
    void renameRollbackAndWorldGenHistoryAreExactlyFenced() throws IOException {
        String manager = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java"));
        String browser = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java"));

        assertTrue(manager.contains("record SaveTicketSettlement(boolean saved, boolean currentAtFinish)"));
        assertTrue(manager.contains("settlement.currentAtFinish() && lease.isCurrent()"));
        assertTrue(manager.contains("settlement.currentAtFinish() && lease != null && lease.isCurrent()"));
        assertTrue(browser.contains("if (worldGenHistoryAffected(edit, currentEntries))"));
        assertTrue(browser.contains("Deque<BrowserHistoryEntry> source = before ? undoHistory : redoHistory;"));
        assertTrue(browser.contains("Deque<BrowserHistoryEntry> destination = before ? redoHistory : undoHistory;"));
        assertTrue(browser.contains("boolean restored = result.restored() && source.peekLast() == edit;"));
        assertTrue(browser.contains("source.removeLast();"));
        assertTrue(browser.contains("destination.addLast(edit);"));
        assertTrue(browser.contains("\"Undo Unavailable\" : \"Redo Unavailable\""));
        assertFalse(browser.contains("submitWorldGenSerializedSave("));
    }
}
