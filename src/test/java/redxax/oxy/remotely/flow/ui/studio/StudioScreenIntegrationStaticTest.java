package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioScreenIntegrationStaticTest {
    private static final Path SOURCE = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java");

    @Test
    void activeViewSelectorKeepsPrintableTInsideTheSelector() throws IOException {
        String source = Files.readString(SOURCE);
        String method = section(source, "private boolean openCollaborationChat", "private void sendCollaborationChat");

        assertTrue(method.contains("activeStudioView()"));
        assertTrue(method.contains("view instanceof StudioSelectorView selectorView && selectorView.hasActiveStudioSelector()"));
        assertTrue(method.indexOf("selectorView.hasActiveStudioSelector()") < method.indexOf("FlowManager manager"));
    }

    @Test
    void viewBackedCloseDiscardsTheManagerDraftWithoutRestoringViewHistory() throws IOException {
        String source = Files.readString(SOURCE);
        String method = section(source, "protected boolean discardStudioDocumentForClose", "private boolean isCurrentCoreStudioDocument");
        String managed = method.substring(method.indexOf("FlowManager manager"));

        assertTrue(method.contains("manager.discardResourceDraft(studioServerId(), type, document.id())"));
        assertTrue(method.contains("manager.discardCoreGraphSession(studioServerId(), type, document.id())"));
        assertTrue(method.substring(0, method.indexOf("FlowManager manager"))
            .contains("document.view().discardUnsavedChanges()"));
        assertFalse(managed.contains("document.view().discardUnsavedChanges()"));
        assertFalse(managed.contains("discardStudioDocument(document)"));
    }

    @Test
    void coreTabCloseUsesLocalOwnershipForDestructiveDiscard() throws IOException {
        String source = Files.readString(SOURCE);
        String request = section(source, "private boolean requestStudioTabClose", "protected boolean discardStudioDocumentForClose");
        String discard = section(source, "protected boolean discardStudioDocumentForClose", "private boolean isCurrentCoreStudioDocument");

        assertTrue(request.contains("isStudioDocumentDirty(document)"));
        assertFalse(request.contains("isCurrentCoreStudioDocument(document)"));
        assertTrue(discard.contains("manager.ownsCoreGraphEditorSession("));
        assertTrue(discard.contains("studioServerId(), type, document.id(), document.coreSession())"));
        assertFalse(discard.contains("isCurrentCoreStudioDocument(document)"));
    }

    @Test
    void expiredCoreRebindRestoresThePreviousTabWithoutClosingTheStaleDocument() throws IOException {
        String source = Files.readString(SOURCE);
        String retry = section(source, "private void retryPendingCoreStudioDocument", "protected StudioDocument findStudioDocument");

        assertTrue(source.contains("private String pendingCoreStudioPreviousDocumentKey = \"\";"));
        assertTrue(retry.contains("restorePendingCoreStudioSelection(previousKey)"));
        assertTrue(retry.contains("setStudioTabsManagerActiveTab(findStudioTab(previous.key(),"));
        assertFalse(retry.contains("studioDocuments.remove"));
        assertFalse(retry.contains("removeTab"));
    }

    @Test
    void permissionsPrepareAsynchronouslyWithABoundedUiMarshaledFailure() throws IOException {
        String source = Files.readString(SOURCE);
        String method = section(source, "protected void openReSyncPermissions", "public boolean hasReSyncUpdateAvailable");

        assertTrue(method.contains("LuckPermsDashboardScreen.prepare(this, client.luckPerms())"));
        assertTrue(method.contains(".orTimeout(LUCKPERMS_PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)"));
        assertTrue(method.contains("ScreenManager.getInstance().execute(() ->"));
        assertTrue(method.contains("new Notification(\"Permissions\", \"Permissions Unavailable\", Notification.Type.ERROR)"));
        assertFalse(method.contains("new LuckPermsDashboardScreen"));
    }

    @Test
    void coreCommandSettingsUseCanonicalMetadataBeforeLegacyDependencies() throws IOException {
        String source = Files.readString(SOURCE);
        String build = section(source, "protected void buildCommandResourcePanel()", "protected void buildScoreboardResourcePanel");
        String apply = section(source, "private void applyCommandInteraction", "protected void applyStudioCollaborationInteraction");

        assertTrue(build.contains("CoreGraphEditorSession coreSession = activeStudioDocument.coreSession()"));
        assertTrue(build.contains("commandContext(coreSession.commandMetadata())"));
        assertTrue(build.indexOf("coreSession.commandMetadata()") < build.indexOf("FlowManager manager"));
        assertTrue(apply.contains("activeStudioDocument.coreSession() != null"));
        assertTrue(apply.contains("applyCoreCommandInteraction(draft)"));
        assertTrue(apply.indexOf("applyCoreCommandInteraction(draft)") < apply.indexOf("applyStudioCollaborationInteraction"));
        assertFalse(apply.substring(0, apply.indexOf("activeStudioDocument.coreSession() != null"))
            .contains("activeStudioDocument.graph()"));
    }

    @Test
    void commandMetadataOmitsBlankPanelPlaceholdersAndUsesTheSharedStartContract() throws IOException {
        String source = Files.readString(SOURCE);
        String collect = section(source, "protected List<String> collectCommandPaths(List<String> values)",
            "protected CommandBindingContext currentCommandDraft");
        String metadata = section(source, "protected CommandGraphMetadata commandMetadata", "private boolean isEditingCommandPanel");
        String classifier = section(source, "protected boolean isCommandStartNode", "protected CommandBindingContext parseCommandContext");

        assertTrue(collect.contains("String path = value != null ? value.trim() : \"\""));
        assertTrue(collect.contains("if (!path.isBlank())"));
        assertTrue(metadata.contains("collectCommandPaths(context.subcommands)"));
        assertTrue(classifier.contains("CommandGraphContract.isAnyStart(type)"));
    }

    private static String section(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }
}
