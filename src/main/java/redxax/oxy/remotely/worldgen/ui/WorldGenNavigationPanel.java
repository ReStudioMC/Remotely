package redxax.oxy.remotely.worldgen.ui;

import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.worldgen.data.WorldGenStage;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class WorldGenNavigationPanel {
    private static final int WIDTH = 300;

    private final WorldGenEditorScreen screen;
    private final StudioPanel panel;
    private final SidePanel sidePanel;
    private int contentSignature;

    public WorldGenNavigationPanel(WorldGenEditorScreen screen) {
        this.screen = screen;
        panel = new StudioPanel(screen, "worldgen_navigation")
            .right()
            .padding(8)
            .show();
        sidePanel = panel.sidePanel()
            .minWidth(260)
            .maxWidth(380)
            .width(WIDTH);
        panel.layout();
        refresh(true);
    }

    public int layoutWidth() {
        if (sidePanel.isVisible()) {
            return sidePanel.getDesiredWidth() + 8;
        }
        return (int) Math.ceil(sidePanel.getAnimatedWidth());
    }

    public void layout() {
        panel.layout();
    }

    public void render(IDrawContext context, int mouseX, int mouseY, float delta) {
        refresh(false);
        panel.layout();
        sidePanel.update();
        if (!sidePanel.isVisible() && sidePanel.getAnimatedWidth() <= 1f) {
            return;
        }
        sidePanel.container().render(context, mouseX, mouseY, delta);
        sidePanel.renderHeader(context, mouseX, mouseY);
        panel.renderHintOverlay(context);
    }

    public boolean mouseClicked(ReMouseEvent event) {
        return sidePanel.mouseClicked(event.retarget(sidePanel, event.x(), event.y())) || sidePanel.isMouseOver(event.x(), event.y());
    }

    public boolean mouseReleased(ReMouseEvent event) {
        return sidePanel.mouseReleased(event.retarget(sidePanel, event.x(), event.y()));
    }

    public boolean mouseDragged(ReMouseEvent event) {
        return sidePanel.mouseDragged(event.retarget(sidePanel, event.x(), event.y(), event.deltaX(), event.deltaY()));
    }

    public boolean mouseScrolled(ReScrollEvent event) {
        return sidePanel.mouseScrolled(event.retarget(sidePanel, event.x(), event.y()));
    }

    public boolean keyPressed(ReKeyEvent event) {
        return sidePanel.keyPressed(event.retarget(sidePanel));
    }

    public void refresh() {
        refresh(true);
    }

    private void refresh(boolean force) {
        int signature = signature();
        if (!force && signature == contentSignature) {
            return;
        }
        contentSignature = signature;
        List<AnimatedWidget> content = new ArrayList<>();
        content.add(section("Project"));
        content.add(action(screen.generationMode() + " · " + screen.projectId(), "Open Worldgen Projects", screen::showProjectPopup,
            ThemeManager.getDefaultAccent(), "project"));
        content.add(action("World Settings", "Configure The Terrain Preset, Content, And Version", screen::showSettingsPopup,
            ThemeManager.getDefaultAccent(), "settings"));
        content.add(section("Design"));
        for (WorldGenStage stage : WorldGenStage.values()) {
            content.add(stage(stage));
        }
        content.add(section("Preview"));
        content.add(action("Preview World", "Generate A Temporary World From This Project", screen::showPreviewPopup,
            ThemeManager.getDefaultAccent(), "preview"));
        content.add(action("Stop Preview", "Close The Temporary Preview World", screen::stopPreview,
            ThemeManager.getAccent("danger"), "stop-preview"));
        panel.setWidgets(content);
    }

    private AnimatedButton section(String label) {
        return new AnimatedButton.Builder()
            .label(label)
            .size(panel.rowWidth(), 18)
            .centered(false)
            .active(false)
            .flat(true)
            .transparent(true)
            .animateElevation(false)
            .enableHoverColors(false)
            .entranceAnimation(false)
            .build();
    }

    private AnimatedButton action(String label, String hint, Runnable action, Accent accent, String key) {
        AnimatedButton button = new AnimatedButton.Builder()
            .label(label)
            .size(panel.rowWidth(), 18)
            .centered(false)
            .hint(hint)
            .accentType(accent)
            .entranceAnimation(false)
            .onClick(action)
            .build();
        ReSyncStudioPanelState.identify(button, "worldgen-navigation:" + key);
        return button;
    }

    private AnimatedButton stage(WorldGenStage stage) {
        int nodeCount = screen.stageNodeCount(stage);
        AnimatedButton button = new AnimatedButton.Builder()
            .label(stage.displayName() + " · " + nodeCount + (nodeCount == 1 ? " Node" : " Nodes"))
            .size(panel.rowWidth(), 18)
            .centered(false)
            .hint(screen.stageDescription(stage))
            .accentType(stage == screen.activeStage() ? ThemeManager.getAccent("calm") : ThemeManager.getDefaultAccent())
            .setSelectable(true)
            .entranceAnimation(false)
            .onClick(() -> screen.switchStage(stage))
            .build();
        button.setSelected(stage == screen.activeStage());
        ReSyncStudioPanelState.identify(button, "worldgen-stage:" + stage.name().toLowerCase());
        return button;
    }

    private int signature() {
        int signature = Objects.hash(screen.generationMode(), screen.projectId(), screen.activeStage());
        for (WorldGenStage stage : WorldGenStage.values()) {
            signature = 31 * signature + screen.stageNodeCount(stage);
        }
        return signature;
    }
}
