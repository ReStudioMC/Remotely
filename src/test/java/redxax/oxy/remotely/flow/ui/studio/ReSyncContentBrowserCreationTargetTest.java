package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncContentBrowserCreationTargetTest {
    private static final Path BROWSER = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncContentBrowserWidget.java");
    private static final Path CLIENT = Path.of("src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java");

    @Test
    void toolbarAndContextCreationUseTheSelectedResourceParentFolder() throws IOException {
        String source = Files.readString(BROWSER).replace("\r\n", "\n");
        String toolbar = section(source, "private void showCreateMenu()", "private void showCreateMenu(int mouseX");
        String destination = section(source, "private String createDestination", "private String selectedCreateTargetFolder");
        String selected = section(source, "private String selectedCreateTargetFolder", "private void showCreateContentPopup");

        assertTrue(toolbar.contains("selectedCreateTargetFolder()"));
        assertTrue(destination.contains("if (targetFolder != null)"));
        assertTrue(destination.contains("ReSyncResourceType.defaultFolderFor(type)"));
        assertFalse(destination.contains("selectionDestination"));
        assertTrue(selected.contains("selectionDestination(browserSelection())"));
        assertTrue(selected.contains("ReSyncProjectMetadata.normalizePath(currentFolder)"));
    }

    @Test
    void authoritativeCoreCreateRehydratesItsCoupledProjectMetadataBeforeCompletion() throws IOException {
        String source = Files.readString(CLIENT).replace("\r\n", "\n");
        String settle = section(source, "private boolean completeCoreGraphSaveSettlement", "private boolean scheduleCoreGraphSaveSettlementRecovery");
        int create = settle.indexOf("if (pending.operation() == ResourceOperationKind.CREATE)");
        int metadataRefresh = settle.indexOf("requestProjectMetadataList()", create);
        int lifecycleCompletion = settle.indexOf("completeResourceSave(mutationId)", metadataRefresh);
        int notificationCompletion = settle.indexOf("DesignerSaveNotifications.completeMutation", lifecycleCompletion);

        assertTrue(create >= 0 && metadataRefresh > create && lifecycleCompletion > metadataRefresh);
        assertTrue(notificationCompletion > lifecycleCompletion);
    }

    private static String section(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        return source.substring(startIndex, source.indexOf(end, startIndex));
    }
}
