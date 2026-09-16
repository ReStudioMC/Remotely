package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.theme.ThemeManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootTableDesignerScreenVaultTest {
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
    void vaultTriggerUsesModeSpecificKeysAndTargetSelector() {
        JsonObject resource = new JsonObject();
        JsonObject trigger = new JsonObject();
        trigger.addProperty("event", "vault_open");
        trigger.addProperty("target", "normal");
        trigger.addProperty("tool", "");
        resource.add("trigger", trigger);
        TestLootDesigner designer = new TestLootDesigner(new StudioScreen(), resource);
        try {
            assertEquals("minecraft:trial_key", designer.triggerTool());
            assertTrue(designer.eventOptions().contains("vault_open"));
            assertEquals(List.of("normal", "ominous", "any"), designer.targetOptions());
            assertTrue(designer.targetIsDropdown());
            assertTrue(designer.fields().contains("trigger.tool"));

            designer.selectTarget("ominous");
            assertEquals("minecraft:ominous_trial_key", designer.triggerTool());
            designer.selectTarget("any");
            assertEquals("", designer.triggerTool());
        } finally {
            designer.closed();
        }
    }

    private static final class TestLootDesigner extends LootTableDesignerScreen {
        private TestLootDesigner(StudioScreen host, JsonObject resource) {
            super(host, "loot-vault", resource, "server", null);
        }

        private String triggerTool() {
            return jsonPathText("trigger.tool");
        }

        private List<String> targetOptions() {
            return customSelectorOptions("trigger.target");
        }

        private List<String> eventOptions() {
            return customSelectorOptions("trigger.event");
        }

        private boolean targetIsDropdown() {
            return customDropdownField("trigger.target");
        }

        private List<String> fields() {
            return lootTableFields();
        }

        private void selectTarget(String value) {
            onDropdownSelectionChanged("trigger.target", value);
        }
    }
}
