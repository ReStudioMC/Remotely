package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioView;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.theme.ThemeManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResourceDesignersTest {
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
    void opensBuiltInDesignersWithTheirControlsWithoutPresentationMetadata() {
        Map<ReSyncResourceType, Class<? extends FocusedJsonResourceDesignerScreen>> screens = Map.of(
            ReSyncResourceType.MOTD_PROFILE, MotdDesignerScreen.class,
            ReSyncResourceType.MESSAGE_RULE, MessageRuleDesignerScreen.class,
            ReSyncResourceType.TEXT_TEMPLATE, TextTemplateDesignerScreen.class,
            ReSyncResourceType.RECIPE_DEFINITION, RecipeDesignerScreen.class,
            ReSyncResourceType.LOOT_TABLE, LootTableDesignerScreen.class,
            ReSyncResourceType.NPC_DEFINITION, NpcDesignerScreen.class,
            ReSyncResourceType.CHAT, ChatDesignerScreen.class,
            ReSyncResourceType.TRADE_PROFILE, TradeDesignerScreen.class);
        TestStudio studio = new TestStudio();
        screens.forEach((type, expected) -> {
            JsonObject resource = new JsonObject();
            resource.addProperty("id", "test");
            FocusedJsonResourceDesignerScreen full = ResourceDesigners.create(null, type.typeId(), "test", resource.deepCopy(), "server", null);
            FocusedJsonResourceDesignerScreen embedded = (FocusedJsonResourceDesignerScreen) studio.designer(type, resource.deepCopy());
            try {
                assertEquals(expected, full.getClass(), type.typeId());
                assertEquals(expected, embedded.getClass(), type.typeId());
                assertFalse(full.editorFields().isEmpty(), type.typeId());
                assertFalse(full.editorFields().contains("managedResourceDocument"), type.typeId());
                assertEquals(full.editorFields(), embedded.editorFields(), type.typeId());
            } finally {
                full.closed();
                embedded.closed();
            }
        });
    }

    private static final class TestStudio extends StudioScreen {
        private ReSyncStudioView designer(ReSyncResourceType type, JsonObject resource) {
            return focusedResourceView(type.typeId(), "test", resource);
        }
    }
}
