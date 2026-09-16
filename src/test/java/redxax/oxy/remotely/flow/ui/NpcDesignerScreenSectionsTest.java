package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.theme.ThemeManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NpcDesignerScreenSectionsTest {
    private ThemeManager.Snapshot theme;

    @BeforeEach
    void initializeTheme() {
        theme = ThemeManager.snapshot();
        ThemeManager.initBrowserDefaults();
    }

    @AfterEach
    void restoreTheme() {
        ThemeManager.restore(theme);
    }

    @Test
    void groupsCurrentNpcFieldsWithoutLegacySpawnFields() {
        JsonObject resource = new JsonObject();
        resource.addProperty("entityType", "player");
        TestNpcDesigner designer = new TestNpcDesigner(new StudioScreen(), resource);
        try {
            assertEquals(List.of("NPC", "Skin", "Interaction", "Hooks"), designer.sectionTitles());
            assertEquals(List.of("displayName", "entityType", "ai", "gravity", "invulnerable", "followPlayer", "followRange"), designer.sectionFields("NPC"));
            assertEquals(List.of("skin.username"), designer.sectionFields("Skin"));
            assertEquals(List.of("dialog", "tradeProfile", "lootTable"), designer.sectionFields("Interaction"));
            assertEquals(List.of("hooks.spawnAction", "hooks.interactAction", "hooks.rightClickAction", "hooks.leftClickAction", "hooks.damageAction", "hooks.deathAction", "hooks.despawnAction"), designer.sectionFields("Hooks"));
            assertFalse(designer.fields().stream().anyMatch(field -> field.startsWith("spawnMode") || field.startsWith("location.")));
        } finally {
            designer.closed();
        }
    }

    private static final class TestNpcDesigner extends NpcDesignerScreen {
        private TestNpcDesigner(StudioScreen host, JsonObject resource) {
            super(host, "npc-sections", resource, "server", null);
        }

        private List<String> fields() {
            return editorFields();
        }

        private List<String> sectionTitles() {
            return editorSections(fields()).stream().map(ResourcePanelSection::title).toList();
        }

        private List<String> sectionFields(String title) {
            return editorSections(fields()).stream()
                .filter(section -> title.equals(section.title()))
                .findFirst()
                .map(ResourcePanelSection::fields)
                .orElse(List.of());
        }
    }
}
