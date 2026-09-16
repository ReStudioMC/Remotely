package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.theme.ThemeManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecipeDesignerScreenMountTest {
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
    void mountsEmptyRecipeBindingsWithNoDraftMode() {
        TestStudio host = new TestStudio();
        host.resize(900, 420);
        TestRecipeDesigner designer = new TestRecipeDesigner(host);
        try {
            assertEquals("None", designer.bindingMode());
            assertDoesNotThrow(() -> designer.configurePanel(new StudioPanel(host, "recipe-mount-test")));
            assertFalse(designer.mountedWidgets().isEmpty());
        } finally {
            designer.closed();
        }
    }

    private static final class TestRecipeDesigner extends RecipeDesignerScreen {
        private TestRecipeDesigner(StudioScreen host) {
            super(host, "recipe", new JsonObject(), "server", null);
        }

        private String bindingMode() {
            return recipeBindingMode("conditionBinding", "", "conditions.predicate", "");
        }

        private List<?> mountedWidgets() {
            return studioResourcePanelWidgets;
        }
    }

    private static final class TestStudio extends StudioScreen {
    }
}
