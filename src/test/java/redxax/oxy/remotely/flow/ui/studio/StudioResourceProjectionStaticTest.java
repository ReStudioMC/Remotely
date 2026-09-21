package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioResourceProjectionStaticTest {
    @Test
    void studioPresentationProjectionUsesDerivedMembershipBeforeHydratedPayloads() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java"));
        String manager = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java"));

        assertTrue(source.contains("private List<ReSyncProjectMetadata.ResourceEntry> studioResourceProjection(FlowManager manager)"));
        assertTrue(source.contains("private ReSyncProjectMetadata studioPresentationMetadata(FlowManager manager)"));
        assertTrue(source.contains("manager.snapshotProjectMetadata(studioServerId())"));
        assertTrue(source.contains("manager.getProjectResources(studioServerId())"));
        assertTrue(source.contains("hydrateStudioTypedResources(manager, type, resources)"));
        assertTrue(source.contains("if (existing.getDisplayName() == null || existing.getDisplayName().isBlank())"));
        assertTrue(source.contains("if (existing.getPath() == null || existing.getPath().isBlank())"));
        assertFalse(source.contains("flowClient.isResourceListAuthoritative(type)"));
        assertFalse(source.contains("resources.entrySet().removeIf(entry -> type.typeId().equalsIgnoreCase(entry.getValue().getType()))"));
        assertFalse(source.contains("studioFallbackResource(type"));
        assertTrue(manager.contains("completeTypes.add(ReSyncResourceDragPayload.WORLDGEN)"));
        assertTrue(manager.contains("completeTypes.add(ReSyncResourceDragPayload.WORLD)"));
        assertTrue(manager.contains("return deriveProjectResources(currentProjectMetadataSnapshot(serverId), snapshotTypedResourceMembership(serverId))"));
    }

    @Test
    void browserRebuildCanRefreshWhenPresentationMetadataStampDoesNotChange() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java"));
        String tick = method(source, "\n    public void tick()");
        String explicitRebuild = method(source, "public void rebuild()");
        String schedule = method(source, "private void scheduleRebuild(long metadataStamp, RemotePath revealPath, int attempt)");
        String preparation = method(source, "private void prepareRebuild(BrowserRebuildRequest request)");
        String decorationRevision = method(source, "static long nextDecorationRevision(");
        String application = method(source, "private void applyPreparedRebuild(PreparedBrowserRebuild prepared)");
        String publication = method(source, "private void publishPreparedRebuild(PreparedBrowserRebuild prepared)");

        assertTrue(explicitRebuild.contains("scheduledProjectMetadataStamp = Long.MIN_VALUE;"));
        assertTrue(explicitRebuild.contains("scheduledDecorationRevision = Long.MIN_VALUE;"));
        assertTrue(explicitRebuild.contains("failedProjectMetadataStamp = Long.MIN_VALUE;"));
        assertTrue(explicitRebuild.contains("failedDecorationRevision = Long.MIN_VALUE;"));
        assertTrue(tick.contains("if (lastProjectMetadataStamp != metadataStamp) scheduleRebuild(metadataStamp)"));
        assertTrue(tick.contains("long collaborationRevision = collaborationRevision()"));
        assertTrue(tick.contains("if (lastCollaborationRevision != collaborationRevision)"));
        assertTrue(tick.contains("collaborationDecorationRevision = nextDecorationRevision("));
        assertTrue(tick.contains("scheduleRebuild(metadataStamp)"));
        assertTrue(decorationRevision.contains("previousActivityRevision == activityRevision"));
        assertTrue(decorationRevision.contains("decorationRevision + 1L"));
        assertTrue(source.contains("private record AssetBrowserSnapshot(List<String> folders, List<String> resources, long collaborationRevision)"));
        assertTrue(schedule.contains("long collaborationRevision = collaborationDecorationRevision;"));
        assertTrue(schedule.contains("scheduledProjectMetadataStamp == metadataStamp && scheduledDecorationRevision == collaborationRevision"));
        assertTrue(schedule.contains("rebuildDrain.submit(new BrowserRebuildRequest(generation, metadataStamp, collaborationRevision, revealPath,"));
        assertTrue(preparation.contains("List<ReSyncProjectMetadata.ResourceEntry> projectedResources = screen.studioAllResources().stream()"));
        assertTrue(preparation.contains(".filter(resource -> !AutomationDefinitionDraft.supports(resource.getType()))"));
        assertTrue(preparation.contains("List<BrowserFolder> folders = browserFolders(screen.studioAllFolders()).stream()"));
        assertTrue(preparation.contains("AssetBrowserSnapshot snapshot = assetBrowserSnapshot(folders, resources, request.decorationRevision());"));
        assertTrue(preparation.contains("ReSyncProjectTreeProvider provider = prepareTreeProvider(projectRoot, folders, resources);"));
        assertTrue(preparation.indexOf("prepareTreeProvider(projectRoot, folders, resources)")
            < preparation.indexOf("ScreenManager.getInstance().execute(() -> {"));
        assertTrue(preparation.contains("applyPreparedRebuild(prepared);"));
        assertTrue(preparation.contains("finishRebuildFailure(request.generation(), request.metadataStamp(), request.decorationRevision()"));
        assertTrue(application.contains("prepared.snapshot().collaborationRevision() != scheduledDecorationRevision"));
        assertTrue(application.contains("prepared.metadataStamp() != scheduledProjectMetadataStamp"));
        assertTrue(application.contains("prepared.expandedTreeRevision() != expandedTreeRevision"));
        assertTrue(application.contains("rebuildPublicationGate.publish(prepared.generation()"));
        assertTrue(publication.contains("resourceIconPaths = prepared.iconPaths();"));
        assertTrue(publication.contains("treeProvider = prepared.provider();"));
        assertTrue(publication.contains("mountPreparedTree(prepared.provider(), prepared.expandAll(), revealPath);"));
        assertFalse(publication.contains("for ("));
        assertFalse(publication.contains("getExpandedDirectories"));
        assertFalse(publication.contains("putAll("));
        assertFalse(source.contains("manager.saveProjectMetadata(studioServerId(),"));
    }

    @Test
    void typedListProjectionDoesNotReloadDocumentsAlreadyAppliedFromThePage() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java"));

        assertTrue(source.contains("private boolean hasHydratedResource(String serverId, ReSyncResourceType type, String id)"));
        assertTrue(source.contains("if (!hasHydratedResource(serverId, type, id))"));
        assertTrue(source.contains("if (!hasHydratedResource(serverId, ReSyncResourceType.GUI, guiId))"));
        assertTrue(source.contains("if (!hasHydratedResource(serverId, ReSyncResourceType.SCOREBOARD, scoreboardId))"));
        assertTrue(source.contains("if (!hasHydratedResource(serverId, ReSyncResourceType.TAB, tabId))"));
        assertTrue(source.contains("if (!hasHydratedResource(serverId, ReSyncResourceType.CUSTOM_CONTENT, contentId))"));
        assertTrue(source.contains("projectMetadataStore.getFromCache(serverId, serverId) != null"));
    }

    @Test
    void resourceIconsStaySemanticAcrossStudioSurfaces() throws IOException {
        String studio = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java"));
        String browser = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java"));
        String subResources = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/AutomationDefinitionDesignerScreen.java"));
        String content = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/ContentDesignerScreen.java"));
        String graph = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));
        String marketplace = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/marketplace/ReSyncMarketplaceScreen.java"));

        assertTrue(studio.contains("case AUTOMATION_DOCUMENT_TYPE -> \"resources.png\""));
        assertTrue(studio.contains("case ReSyncResourceDragPayload.CUSTOM_CONTENT -> \"content.png\""));
        assertTrue(studio.contains("case ReSyncResourceDragPayload.VARIABLE_DEFINITION -> \"snippets.png\""));
        assertTrue(studio.contains("case ReSyncResourceDragPayload.SCHEDULE_DEFINITION -> \"calendar.png\""));
        assertTrue(studio.contains("default -> \"flow.png\""));
        assertTrue(browser.contains(".addItem(\"New Content\", \"content.png\""));
        assertTrue(browser.contains(".addItem(\"New Variable\", \"snippets.png\""));
        assertTrue(browser.contains(".addItem(\"New Schedule\", \"calendar.png\""));
        assertTrue(browser.contains(".addItem(\"New Flow\", \"flow.png\""));
        assertTrue(subResources.contains("case AutomationDefinitionDraft.VARIABLE -> \"snippets.png\""));
        assertTrue(subResources.contains("case AutomationDefinitionDraft.TIMER -> \"history.png\""));
        assertTrue(subResources.contains("case AutomationDefinitionDraft.SCHEDULE -> \"calendar.png\""));
        assertTrue(content.contains("return \"content.png\""));
        assertTrue(content.contains(".iconPath(\"flow.png\")"));
        assertTrue(graph.contains("return studioMode ? \"ReSync.png\" : \"flow.png\""));
        assertTrue(marketplace.contains("return \"content.png\""));
        assertFalse(studio.contains("customContentIconPath"));
        assertFalse(browser.contains("customContentIconPath"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0);
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
