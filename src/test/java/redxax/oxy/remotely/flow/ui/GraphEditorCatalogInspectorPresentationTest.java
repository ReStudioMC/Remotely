package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorCatalogInspectorPresentationTest {
    private static final Path SOURCE = Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java");

    @Test
    void catalogInspectorVirtualizesDetailRowsAndResolvesWarningsToCopper() throws IOException {
        String source = Files.readString(SOURCE);
        String inspector = source.substring(source.indexOf("private void showTypedCatalogInspector()"),
            source.indexOf("private void showUnavailableRegistryInspector()"));
        String rowBoundary = source.substring(source.indexOf("private void addRegistryInspectorRow("),
            source.indexOf("private Set<String> unresolvedRegistryTypes("));

        assertTrue(inspector.contains(".virtualizeRows(true)"));
        assertTrue(rowBoundary.contains("ThemeManager.getAccent(\"warning\".equals(accent) ? \"copper\" : accent)"));
        assertFalse(rowBoundary.contains("ThemeManager.getAccent(accent)"));
    }
}
