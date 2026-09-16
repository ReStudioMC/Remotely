package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentDesignerContractTest {

    @Test
    void armorSlotIsDesignerConfigurationInsteadOfANodePin() throws Exception {
        String designer = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/ContentDesignerScreen.java"));
        String definitions = Files.readString(Path.of("../ReSync/src/main/resources/nodes/migrated/custom_content.json"));

        assertTrue(designer.contains("dropdownRow(\"Armor Slot\", List.of(\"head\", \"chest\", \"legs\", \"feet\")"));
        assertTrue(designer.contains("setContentConfiguration(graph, \"armor_slot\""));
        assertFalse(definitions.contains("{\"name\": \"armor_slot\""));
    }

    @Test
    void customContentOffersEveryVisibleRuntimeNode() throws Exception {
        String editor = Files.readString(Path.of("src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java"));

        assertFalse(editor.contains("isAllowedInCurrentEditor"));
        assertTrue(editor.contains(".filter(definition -> definition != null && !definition.isHidden())"));
        assertFalse(editor.contains("definitions.removeIf(NodeDefinition::isHidden)"));
    }
}
