package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCommandGraphInvariantTest {
    @Test
    void coreCommandStartIsOwnedByTheCommandResource() throws IOException {
        String source = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertTrue(source.contains("addableNodeDefinitions(typedPalette.definitions(), commandDocument)"));
        assertTrue(source.contains("addableNodeDefinitions(legacySnapshot.definitions().values().stream()"));
        assertTrue(source.contains("refresh.palette.categories().getOrDefault(category, List.of())"));
        assertTrue(source.contains("definitions = addableNodeDefinitions(definitions)"));
        assertTrue(source.contains("isActiveCoreCommandDocument() && isAnyCommandStartType(type)"));
        assertTrue(source.contains("boolean protectedCoreNode = coreDocument && isProtectedCoreNode(coreSession, nodeId)"));
        assertTrue(source.contains("!isProtectedCoreNode(session, nodeId)"));
        assertTrue(source.contains("canMoveNode(nodeId)"));
        assertTrue(source.contains("canMutateWidgetStructure(widget)"));
        assertTrue(source.contains("CommandGraphContract.isAnyStart"));
        assertTrue(source.contains("CommandGraphContract.isCanonicalStart"));
        int positionMutation = source.indexOf("private void syncNodePosition");
        int deletion = source.indexOf("private void deleteNode");
        assertTrue(positionMutation >= 0 && deletion > positionMutation);
        assertFalse(source.substring(positionMutation, deletion).contains("isCanonicalCommandStartNode"));
        assertTrue(source.indexOf("commitCoreDeleteMutation(\"Node Delete\"", deletion) > deletion);
    }
}
