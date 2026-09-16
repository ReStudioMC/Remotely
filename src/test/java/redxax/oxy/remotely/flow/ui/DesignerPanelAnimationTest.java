package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.SidePanel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DesignerPanelAnimationTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void guiLayoutFollowsInspectorAnimationWithoutMovingVertically() throws Exception {
        GuiDesignerScreen designer = new GuiDesignerScreen(new GuiDefinition("test", "Test", 3), "",
            new Screen(), false, true);
        try {
            designer.resize(1000, 700);
            designer.init();
            SidePanel inspector = value(designer, "inspectorPanel", SidePanel.class);
            set(inspector, "animatedWidth", 0.0F);
            updateLayout(designer, true);
            int initialX = value(designer, "guiBackgroundX", Integer.class);
            int initialY = value(designer, "guiBackgroundY", Integer.class);

            set(inspector, "animatedWidth", 240.0F);
            updateLayout(designer, false);
            assertNotEquals(initialX, value(designer, "guiBackgroundX", Integer.class));

            designer.header().offset(0, designer.header().getOffsetY() == 0 ? -designer.header().headerSize : 0);
            updateLayout(designer, false);
            assertEquals(initialY, value(designer, "guiBackgroundY", Integer.class));
        } finally {
            designer.removed();
        }
    }

    @Test
    void dialogLayoutFollowsInspectorAnimationWithoutResize() throws Exception {
        DialogDesignerScreen designer = new DialogDesignerScreen(new JsonObject(), "", new Screen(), false, true);
        try {
            designer.resize(1000, 700);
            designer.init();
            SidePanel inspector = value(designer, "inspector", SidePanel.class);
            set(inspector, "animatedWidth", 0.0F);
            updateLayout(designer, true);
            int initialWidth = value(designer, "previewWidth", Integer.class);
            int initialY = value(designer, "previewY", Integer.class);

            set(inspector, "animatedWidth", 240.0F);
            updateLayout(designer, false);
            assertNotEquals(initialWidth, value(designer, "previewWidth", Integer.class));

            designer.header().offset(0, designer.header().getOffsetY() == 0 ? -designer.header().headerSize : 0);
            updateLayout(designer, false);
            assertEquals(initialY, value(designer, "previewY", Integer.class));
        } finally {
            designer.removed();
        }
    }

    @Test
    void dedicatedDesignerContentIgnoresHeaderAnimation() throws Exception {
        ScoreboardDesignerScreen scoreboard = new ScoreboardDesignerScreen(new ScoreboardDefinition("test", "Test"),
            "", new Screen(), false, true);
        TabDesignerScreen tab = new TabDesignerScreen(new TabDefinition("test"), "", new Screen(), false, true);
        AdvancementDesignerScreen advancement = new AdvancementDesignerScreen(new JsonObject(), "", new Screen(),
            false, true);
        try {
            for (StudioScreen designer : new StudioScreen[]{scoreboard, tab, advancement}) {
                designer.resize(1000, 700);
                designer.init();
            }
            int scoreboardTop = invokeInt(scoreboard, "getPreviewAreaTop");
            int tabTop = invokeInt(tab, "getPreviewAreaTop");
            int advancementY = invokeInt(advancement, "advancementWindowY");

            scoreboard.header().offset(0, 0);
            tab.header().offset(0, 0);
            advancement.header().offset(0, 0);

            assertEquals(scoreboardTop, invokeInt(scoreboard, "getPreviewAreaTop"));
            assertEquals(tabTop, invokeInt(tab, "getPreviewAreaTop"));
            assertEquals(advancementY, invokeInt(advancement, "advancementWindowY"));
        } finally {
            scoreboard.removed();
            tab.removed();
            advancement.removed();
        }
    }

    private static void updateLayout(Object designer, boolean force) throws Exception {
        Method method = designer.getClass().getDeclaredMethod("updateLayout", boolean.class);
        method.setAccessible(true);
        method.invoke(designer, force);
    }

    private static int invokeInt(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (int) method.invoke(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
