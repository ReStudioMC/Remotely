package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdvancementDesignerSelectionBehaviorTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void switchingInspectorSelectionDoesNotCreateAnEdit() throws ReflectiveOperationException {
        Screen host = new Screen();
        host.width = 960;
        host.height = 540;
        AdvancementDesignerScreen designer = new AdvancementDesignerScreen(tree(), "selection-server", host);
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, designer);
        view.init();
        long initialVersion = designer.collaborationEditVersion();

        select(designer, "child");
        select(designer, "root");

        assertEquals(initialVersion, designer.collaborationEditVersion());
        assertFalse(view.hasUnsavedChanges());
    }

    private static void select(AdvancementDesignerScreen designer, String nodeId) throws ReflectiveOperationException {
        Method select = AdvancementDesignerScreen.class.getDeclaredMethod("selectNode", String.class);
        select.setAccessible(true);
        select.invoke(designer, nodeId);
    }

    private static JsonObject tree() {
        JsonObject tree = new JsonObject();
        tree.addProperty("id", "selection");
        tree.addProperty("displayName", "Selection");
        JsonObject nodes = new JsonObject();
        nodes.add("root", node("", "Root"));
        nodes.add("child", node("root", "Child"));
        tree.add("nodes", nodes);
        return tree;
    }

    private static JsonObject node(String parent, String title) {
        JsonObject node = new JsonObject();
        if (!parent.isBlank()) {
            node.addProperty("parent", parent);
        }
        JsonObject display = new JsonObject();
        display.addProperty("title", title);
        display.addProperty("description", "Description");
        display.addProperty("icon", "minecraft:stone");
        display.addProperty("frame", "task");
        display.addProperty("showToast", true);
        display.addProperty("announceToChat", false);
        display.addProperty("hidden", false);
        node.add("display", display);
        node.add("criteria", new JsonObject());
        return node;
    }
}
