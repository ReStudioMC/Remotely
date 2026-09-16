package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.DropDownWidget;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentDesignerRawInputBehaviorTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void frozenWorkspaceKeepsRawInputImmediateAndDefersOneSemanticMutationOnce() throws Exception {
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        FlowGraph graph = graph();
        FrozenContentDesigner designer = new FrozenContentDesigner(graph, releaseSnapshot);
        long version = designer.mutationVersion();

        try {
            for (int index = 0; index < 128; index++) {
                double point = -1000.0 - index;
                designer.mouseClicked(mouse(designer, ReMouseEvent.Action.PRESSED, point, point, 0.0, 0.0));
                designer.mouseDragged(mouse(designer, ReMouseEvent.Action.DRAGGED, point, point, 1.0, 1.0));
                designer.mouseReleased(mouse(designer, ReMouseEvent.Action.RELEASED, point, point, 0.0, 0.0));
                designer.mouseScrolled(new ReScrollEvent(designer, designer, index, ReModifierState.none(), point, point, 0, -1));
                designer.keyPressed(new ReKeyEvent(designer, designer, index, ReModifierState.none(),
                    ReKeyEvent.Action.PRESSED, ReKey.A, 0, 0, ReKeyLocation.STANDARD, false));
                designer.textInput(new ReTextInputEvent(designer, designer, index, ReModifierState.none(), "a", 'a'));
            }

            assertTrue(deferred(designer).isEmpty());
            assertNull(overflow(designer));
            assertFalse(backpressured(designer));
            assertEquals(version, designer.mutationVersion());

            setProperty(designer, "name", "Next Name");

            assertEquals(1, deferred(designer).size());
            assertNull(overflow(designer));
            assertFalse(backpressured(designer));
            assertEquals(version, designer.mutationVersion());
            assertNull(designer.contentProperty("name"));

            releaseSnapshot.countDown();
            drainWorkspaceUpdates(designer);

            assertTrue(deferred(designer).isEmpty());
            assertEquals("Next Name", designer.contentProperty("name"));
            assertEquals(version + 1L, designer.mutationVersion());

            drainWorkspaceUpdates(designer);
            assertEquals(version + 1L, designer.mutationVersion());
        } finally {
            releaseSnapshot.countDown();
            designer.removed();
        }
    }

    @Test
    void embeddedTypeDropdownOpensAndCommitsTheRenderedSelection() throws Exception {
        Screen host = new Screen();
        host.resize(1000, 650);
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("ghasdh", "item", "Ghasdh");
        ContentDesignerScreen designer = new ContentDesignerScreen("dropdown-test", graph, host);
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, designer);
        try {
            view.init();
            DropDownWidget<String> dropdown = awaitDropdown(view, designer, "__type");

            assertTrue(view.mouseClicked(mouse(view, ReMouseEvent.Action.PRESSED,
                dropdown.getX() + 4, dropdown.getY() + 4, 0.0, 0.0)));
            assertTrue(dropdown.isExpanded());
            set(dropdown, "dropdownAnimationProgress", 1.0F);
            int option = dropdown.getItems().indexOf("block");
            int itemX = dropdown.getX() + 6;
            int itemY = dropdown.getY() + dropdown.getHeight() + option * 14 + 7;
            designer.renderOverlayPass(new TestDrawContext(), itemX, itemY, 0.0F);

            assertTrue(view.mouseClicked(mouse(view, ReMouseEvent.Action.PRESSED, itemX, itemY, 0.0, 0.0)));
            assertEquals("block", CustomContentGraphAdapter.contentType(graph));
        } finally {
            designer.removed();
        }
    }

    @SuppressWarnings("unchecked")
    private static DropDownWidget<String> awaitDropdown(ScreenBackedStudioView view, ContentDesignerScreen designer,
                                                         String key) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            view.render(new TestDrawContext(), 0, 0, 0.0F);
            Field field = ContentDesignerScreen.class.getDeclaredField("contentDropdowns");
            field.setAccessible(true);
            DropDownWidget<String> dropdown = ((Map<String, DropDownWidget<String>>) field.get(designer)).get(key);
            Field panelField = ContentDesignerScreen.class.getDeclaredField("contentPanel");
            panelField.setAccessible(true);
            SidePanel panel = (SidePanel) panelField.get(designer);
            if (panel != null) {
                panel.animation(false).show();
                designer.updatePositions();
            }
            if (dropdown != null && dropdown.getWidth() > 0 && dropdown.getHeight() > 0 && panel != null
                && panel.isMouseOver(dropdown.getX() + 4, dropdown.getY() + 4)) {
                return dropdown;
            }
            Thread.sleep(2L);
        }
        throw new AssertionError("Content dropdown did not materialize: " + key);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static FlowGraph graph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("content.item.raw-input");
        graph.setResourceType("flow");
        return graph;
    }

    private static ReMouseEvent mouse(Object source, ReMouseEvent.Action action, double x, double y,
                                      double deltaX, double deltaY) {
        return new ReMouseEvent(source, source, 0L, ReModifierState.none(), action, x, y, deltaX, deltaY,
            ReMouseButton.LEFT, 0, 1);
    }

    private static void setProperty(ContentDesignerScreen designer, String key, Object value) throws Exception {
        Method method = ContentDesignerScreen.class.getDeclaredMethod("setProperty", String.class, Object.class);
        method.setAccessible(true);
        method.invoke(designer, key, value);
    }

    private static void drainWorkspaceUpdates(GraphEditorScreen designer) throws Exception {
        Method method = GraphEditorScreen.class.getDeclaredMethod("drainWorkspaceUpdates");
        method.setAccessible(true);
        method.invoke(designer);
    }

    @SuppressWarnings("unchecked")
    private static Deque<Runnable> deferred(GraphEditorScreen designer) throws Exception {
        Field field = GraphEditorScreen.class.getDeclaredField("deferredWorkspaceMutations");
        field.setAccessible(true);
        return (Deque<Runnable>) field.get(designer);
    }

    private static Runnable overflow(GraphEditorScreen designer) throws Exception {
        Field field = GraphEditorScreen.class.getDeclaredField("deferredWorkspaceOverflowMutation");
        field.setAccessible(true);
        return (Runnable) field.get(designer);
    }

    private static boolean backpressured(GraphEditorScreen designer) throws Exception {
        Field field = GraphEditorScreen.class.getDeclaredField("workspaceMutationBackpressure");
        field.setAccessible(true);
        return field.getBoolean(designer);
    }

    private static final class FrozenContentDesigner extends ContentDesignerScreen {
        private final CountDownLatch releaseSnapshot;

        private FrozenContentDesigner(FlowGraph graph, CountDownLatch releaseSnapshot) {
            super("raw-input", graph, new Screen());
            this.releaseSnapshot = releaseSnapshot;
        }

        @Override
        protected boolean workspaceSnapshotPending() {
            return releaseSnapshot.getCount() > 0L || super.workspaceSnapshotPending();
        }

        private long mutationVersion() {
            return workspaceMutationVersion();
        }

        private Object contentProperty(String key) {
            return CustomContentGraphAdapter.getContentProperty(graph, key, null);
        }
    }
}
