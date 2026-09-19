package redxax.oxy.remotely.flow.ui.studio;

import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class StudioPanel {
    public enum Placement {
        LEFT,
        RIGHT
    }

    private final ReScreen screen;
    private final SidePanel sidePanel;
    private final ReSyncStudioPanelState state;
    private Supplier<List<AnimatedWidget>> contentSupplier = List::of;

    public StudioPanel(ReScreen screen, String id) {
        this.screen = screen;
        this.sidePanel = screen.createSidePanel(id).collapsible("Studio Panel");
        this.state = new ReSyncStudioPanelState();
        sidePanel.right()
            .minWidth(ReSyncStudioPanelState.MIN_WIDTH)
            .width(ReSyncStudioPanelState.DEFAULT_WIDTH);
        sidePanel.container()
            .layout(new ManagedLayout())
            .columns(1)
            .padding(state.padding())
            .scrolling(true)
            .enableSelecting(false);
    }

    public SidePanel sidePanel() {
        return sidePanel;
    }

    public Container container() {
        return sidePanel.container();
    }

    public ReSyncStudioPanelState state() {
        return state;
    }

    public StudioPanel required() {
        sidePanel.required();
        return this;
    }

    public StudioPanel collapsible(String label) {
        sidePanel.collapsible(label);
        return this;
    }

    public StudioPanel dismissible(String label) {
        sidePanel.dismissible(label);
        return this;
    }

    public StudioPanel placement(Placement placement) {
        if (placement == Placement.LEFT) {
            sidePanel.left();
        } else {
            sidePanel.right();
        }
        return this;
    }

    public StudioPanel left() {
        return placement(Placement.LEFT);
    }

    public StudioPanel right() {
        return placement(Placement.RIGHT);
    }

    public StudioPanel padding(int padding) {
        state.padding(padding);
        container().layout(new ManagedLayout()).columns(1).padding(state.padding()).scrolling(true).enableSelecting(false);
        return this;
    }

    public StudioPanel horizontalOffset(int offset) {
        sidePanel.horizontalOffset(offset);
        return this;
    }

    public StudioPanel layout() {
        int top = top();
        int bottomReserve = bottomReserve();
        int panelHeight = Math.max(120, screen.getHeight() - top - bottomReserve);
        state.width(currentWidth());
        sidePanel.y(top).height(panelHeight).width(state.width());
        return this;
    }

    public StudioPanel show() {
        sidePanel.show();
        return this;
    }

    public StudioPanel hide() {
        sidePanel.hide();
        return this;
    }

    public void renderHintOverlay(IDrawContext context) {
        if (!sidePanel.isVisible()) {
            return;
        }
        container().renderHintOverlay(context);
    }

    public int rowWidth() {
        return state.rowWidth();
    }

    private int currentWidth() {
        int width = sidePanel.getConfiguredWidth();
        if (width <= 0) {
            width = state.width();
        }
        return width;
    }

    private int top() {
        if (screen instanceof StudioInfiniteScreen infiniteScreen) {
            return infiniteScreen.studioPanelTop();
        }
        return screen.header().headerSize + 5;
    }

    private int bottomReserve() {
        if (screen instanceof StudioInfiniteScreen infiniteScreen) {
            return Math.max(0, infiniteScreen.studioPanelBottomReserve());
        }
        return 8;
    }

    public TitledRowWidget row(String title, String description, Widget... widgets) {
        for (Widget widget : widgets) {
            ReSyncStudioPanelState.disableEntrance(widget);
        }
        TitledRowWidget row = new TitledRowWidget.Builder()
            .title(title)
            .description(description)
            .size(rowWidth(), ReSyncStudioPanelState.ROW_HEIGHT)
            .gap(4)
            .addWidget(widgets)
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    public Category category(String title) {
        return new Category(this, title);
    }

    public StudioPanel content(Supplier<List<AnimatedWidget>> contentSupplier) {
        this.contentSupplier = contentSupplier == null ? List::of : contentSupplier;
        return this;
    }

    public StudioPanel refresh() {
        List<AnimatedWidget> widgets = contentSupplier.get();
        setWidgets(widgets == null ? List.of() : widgets);
        return this;
    }

    public StudioPanel loading(String title) {
        setWidgets(List.of(message(title == null || title.isBlank() ? "Loading" : title)));
        return this;
    }

    public StudioPanel empty(String title) {
        setWidgets(List.of(message(title == null || title.isBlank() ? "Empty" : title)));
        return this;
    }

    public void setWidgets(List<? extends AnimatedWidget> widgets) {
        container().replaceWidgets(widgets);
    }

    public void mountWidget(AnimatedWidget widget) {
        if (widget == null || container().getWidgets().contains(widget)) {
            return;
        }
        ReSyncStudioPanelState.disableEntrance(widget);
        container().addWidget(widget);
    }

    public void mountWidget(AnimatedWidget widget, int index) {
        if (widget == null || container().getWidgets().contains(widget)) {
            return;
        }
        ReSyncStudioPanelState.disableEntrance(widget);
        container().addWidget(widget, index);
    }

    public void mountWidgetAfter(AnimatedWidget anchor, AnimatedWidget widget) {
        if (widget == null || container().getWidgets().contains(widget)) {
            return;
        }
        List<AnimatedWidget> widgets = container().getWidgets();
        int index = anchor == null ? widgets.size() : widgets.indexOf(anchor) + 1;
        if (index <= 0) {
            index = widgets.size();
        }
        mountWidget(widget, index);
    }

    public void unmountWidget(AnimatedWidget widget) {
        if (widget == null || !container().getWidgets().contains(widget)) {
            return;
        }
        container().removeWidget(widget);
    }

    private AnimatedButton message(String title) {
        AnimatedButton message = new AnimatedButton.Builder()
            .label(title)
            .size(rowWidth(), 18)
            .centered(false)
            .active(false)
            .flat(true)
            .transparent(true)
            .animateElevation(false)
            .enableHoverColors(false)
            .entranceAnimation(false)
            .accentType(ThemeManager.getDefaultAccent())
            .build();
        ReSyncStudioPanelState.disableEntrance(message);
        return message;
    }

    public static class Category {
        private final StudioPanel panel;
        private final String title;
        private final List<AnimatedWidget> widgets = new ArrayList<>();

        private Category(StudioPanel panel, String title) {
            this.panel = panel;
            this.title = title == null ? "" : title;
        }

        public Category row(String title, String description, Widget... inputs) {
            widgets.add(panel.row(title, description, inputs));
            return this;
        }

        public List<AnimatedWidget> build() {
            List<AnimatedWidget> built = new ArrayList<>();
            if (!title.isBlank()) {
                AnimatedButton header = new AnimatedButton.Builder()
                    .label(title)
                    .size(panel.rowWidth(), 18)
                    .centered(false)
                    .active(false)
                    .flat(true)
                    .transparent(true)
                    .animateElevation(false)
                    .enableHoverColors(false)
                    .entranceAnimation(false)
                    .accentType(ThemeManager.getDefaultAccent())
                    .build();
                built.add(header);
            }
            built.addAll(widgets);
            return built;
        }

        public void addToPanel() {
            for (AnimatedWidget widget : build()) {
                panel.container().addWidget(widget);
            }
            panel.container().updateWidgetPositions();
        }
    }
}
