package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;
import restudio.rescreen.util.JsonTreeParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioDocumentCodecTest {
    @Test
    void designerSnapshotsRemainIndependentAndPreserveEditableFields() {
        GuiDefinition gui = new GuiDefinition();
        gui.setId("menu");
        gui.setTitle("Builders");
        gui.setRows(5);
        gui.setEnabled(false);
        gui.setExtendToPlayerInventory(true);
        JsonObject snapshot = ReSyncCollaborationDocuments.from(gui);
        GuiDefinition restored = ReSyncCollaborationDocuments.gui(snapshot);
        restored.setTitle("Changed");
        assertEquals("Builders", snapshot.get("title").getAsString());
        assertEquals(5, restored.getRows());
        assertFalse(restored.isEnabled());
        assertTrue(restored.isExtendToPlayerInventory());

        ScoreboardDefinition scoreboard = new ScoreboardDefinition();
        scoreboard.setLines(List.of("First", "Second"));
        scoreboard.setObjectiveId("objective");
        ScoreboardDefinition scoreCopy = ReSyncCollaborationDocuments.scoreboard(
            ReSyncCollaborationDocuments.from(scoreboard));
        assertEquals(List.of("First", "Second"), scoreCopy.getLines());
        assertEquals("objective", scoreCopy.getObjectiveId());

        TabDefinition tab = new TabDefinition();
        tab.setHeader("Hello\nWorld");
        tab.setFooter("Footer");
        tab.setEntryFormat("{player}");
        TabDefinition tabCopy = ReSyncCollaborationDocuments.tab(
            ReSyncCollaborationDocuments.from(tab));
        assertEquals("Hello\nWorld", tabCopy.getHeader());
        assertEquals("Footer", tabCopy.getFooter());
        assertEquals("{player}", tabCopy.getEntryFormat());
    }

    @Test
    void structuredCommandContextRoundTripsWithoutLosingSubcommands() {
        String payload = "{\"command\":\"build\",\"subcommands\":[\"create\",\"delete\"],\"structured\":true}";
        CommandBindingContext context = CommandBindingContext.fromJson(payload);
        assertEquals("build", context.command);
        assertEquals(List.of("create", "delete"), context.subcommands);
        assertTrue(context.structured);
        assertEquals(JsonTreeParser.parse(payload), JsonTreeParser.parse(context.toJson()));
    }
}
