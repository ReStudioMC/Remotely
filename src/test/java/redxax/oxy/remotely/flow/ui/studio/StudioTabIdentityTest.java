package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.TabsManager;
import restudio.rescreen.ui.widgets.TextInputWidget;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StudioTabIdentityTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void closingInactiveEarlierTabPreservesActiveDocumentIdentityWithoutSelectionCallback() {
        TestStudioScreen screen = new TestStudioScreen();
        TrackingView firstView = new TrackingView();
        TrackingView activeView = new TrackingView();
        TrackingView lastView = new TrackingView();
        StudioDocument first = document("first", firstView);
        StudioDocument active = document("active", activeView);
        StudioDocument last = document("last", lastView);
        screen.mount(first, active, last);
        screen.select(active.key());
        TabsManager.Tab activeTab = screen.tab(active.key());
        int selectedBefore = activeView.selected;
        int deselectedBefore = activeView.deselected;
        screen.clearSelections();

        screen.close(first.key());

        assertSame(active, screen.activeStudioDocument);
        assertSame(activeTab, screen.activeTab());
        assertEquals(List.of(), screen.selections);
        assertEquals(selectedBefore, activeView.selected);
        assertEquals(deselectedBefore, activeView.deselected);
        assertEquals(0, lastView.selected);
        assertEquals(1, firstView.closed);
    }

    @Test
    void closingActiveTabSelectsRightNeighborExactlyOnce() {
        TestStudioScreen screen = new TestStudioScreen();
        StudioDocument first = document("first", new TrackingView());
        TrackingView activeView = new TrackingView();
        TrackingView lastView = new TrackingView();
        StudioDocument active = document("active", activeView);
        StudioDocument last = document("last", lastView);
        screen.mount(first, active, last);
        screen.select(active.key());
        screen.clearSelections();

        screen.close(active.key());

        assertSame(last, screen.activeStudioDocument);
        assertSame(screen.tab(last.key()), screen.activeTab());
        assertEquals(List.of(last.key()), screen.selections);
        assertEquals(1, activeView.deselected);
        assertEquals(1, activeView.closed);
        assertEquals(1, lastView.selected);
    }

    @Test
    void closingLastActiveTabSelectsLeftNeighborExactlyOnce() {
        TestStudioScreen screen = new TestStudioScreen();
        StudioDocument first = document("first", new TrackingView());
        TrackingView middleView = new TrackingView();
        TrackingView lastView = new TrackingView();
        StudioDocument middle = document("middle", middleView);
        StudioDocument last = document("last", lastView);
        screen.mount(first, middle, last);
        screen.select(last.key());
        screen.clearSelections();

        screen.close(last.key());

        assertSame(middle, screen.activeStudioDocument);
        assertSame(screen.tab(middle.key()), screen.activeTab());
        assertEquals(List.of(middle.key()), screen.selections);
        assertEquals(1, lastView.deselected);
        assertEquals(1, lastView.closed);
        assertEquals(1, middleView.selected);
    }

    @Test
    void replacingTheActiveDocumentActivatesTheReplacementExactlyOnce() {
        TestStudioScreen screen = new TestStudioScreen();
        TrackingView original = new TrackingView();
        TrackingView replacement = new TrackingView();
        StudioDocument document = document("active", original);
        screen.mount(document);
        screen.select(document.key());

        screen.replace("active", replacement);

        assertSame(replacement, screen.activeView());
        assertEquals(1, original.closed);
        assertEquals(1, replacement.initialized);
        assertEquals(1, replacement.selected);
    }

    @Test
    void everyFullEditorScreenBackedViewUsesTheHeaderTransition() {
        TestStudioScreen screen = new TestStudioScreen();

        assertTrue(screen.animates(new ScreenBackedStudioView(screen, new Screen(), true)));
    }

    @Test
    void priorityViewFocusEstablishedDuringClickSurvivesHostRouting() {
        TestStudioScreen screen = new TestStudioScreen();
        TextInputWidget stale = new TextInputWidget(0, 0, 40, 16);
        TextInputWidget selected = new TextInputWidget(0, 0, 40, 16);
        PriorityView view = new PriorityView(screen, selected);
        StudioDocument document = new StudioDocument("chat", "chat", "Chat", null, view,
            new StudioViewportState());
        screen.mount(document);
        screen.select(document.key());
        screen.focus(stale);

        assertTrue(screen.click(new ReMouseEvent(screen, screen, 0L, ReModifierState.none(),
            ReMouseEvent.Action.PRESSED, 20, 20, 0, 0, ReMouseButton.LEFT, 0, 1)));
        assertSame(selected, screen.focused());
    }

    private static StudioDocument document(String id, TrackingView view) {
        return new StudioDocument("tab", id, id, null, view, new StudioViewportState());
    }

    private static final class TestStudioScreen extends StudioScreen {
        private final List<String> selections = new ArrayList<>();

        private void mount(StudioDocument... documents) {
            width = 800;
            height = 600;
            studioMode = true;
            studioDocuments.addAll(List.of(documents));
            createStudioWorkspaceChrome(false);
        }

        private void select(String key) {
            selectStudioDocument(key);
        }

        private void close(String key) {
            TabsManager.Tab tab = tab(key);
            studioTabsManager.removeTab(studioTabsManager.getTabs().indexOf(tab));
        }

        private TabsManager.Tab tab(String key) {
            return findStudioTab(key, studioTabsManager.getTabs());
        }

        private TabsManager.Tab activeTab() {
            return studioTabsManager.getActiveTab();
        }

        private void clearSelections() {
            selections.clear();
        }

        private void replace(String id, TrackingView view) {
            openStudioViewDocument("tab", id, id, null, view, true, false);
        }

        private ReSyncStudioView activeView() {
            return activeStudioView();
        }

        private boolean animates(ReSyncStudioView view) {
            return shouldAnimateActiveStudioHeader(view);
        }

        private void focus(TextInputWidget widget) {
            setFocusedWidget(widget);
        }

        private TextInputWidget focused() {
            return (TextInputWidget) getFocusedWidget();
        }

        private boolean click(ReMouseEvent event) {
            return handleStudioWorkspaceMouseClicked(event);
        }

        @Override
        protected void selectStudioDocument(String key) {
            selections.add(key);
            super.selectStudioDocument(key);
        }
    }

    private static final class TrackingView implements ReSyncStudioView {
        private int initialized;
        private int selected;
        private int deselected;
        private int closed;

        @Override
        public void init() {
            initialized++;
        }

        @Override
        public void selected() {
            selected++;
        }

        @Override
        public void deselected() {
            deselected++;
        }

        @Override
        public void closed() {
            closed++;
        }

        @Override
        public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        }
    }

    private static final class PriorityView implements ReSyncStudioView, StudioPriorityInputView {
        private final TestStudioScreen host;
        private final TextInputWidget selected;

        private PriorityView(TestStudioScreen host, TextInputWidget selected) {
            this.host = host;
            this.selected = selected;
        }

        @Override
        public boolean mouseClicked(ReMouseEvent event) {
            host.focus(selected);
            return true;
        }

        @Override
        public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        }
    }
}
