package redxax.oxy.remotely.ui.tests;

import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.rescreen.layout.FreeLayout;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.rescreen.layout.RestrictedLayout;
import restudio.rescreen.ui.widgets.*;

import java.util.List;
import java.util.Random;

public class ContainerTestingScreen extends ReScreen {
    private final Random random = new Random();

    public ContainerTestingScreen() {
        super();
    }

    private AnimatedWidget randomWidget() {
        int minW = 20, maxW = 150, minH = 18, maxH = 100;
        int w = minW + random.nextInt(maxW - minW + 1);
        int h = minH + random.nextInt(maxH - minH + 1);
        return new AnimatedButton.Builder().size(w, h).label((w + "x" + h)).build();
    }

    @Override
    public void init() {
        super.init();
        AnimatedWidget.setCornerSpeedMultiplier(AnimatedWidget.EntranceCorner.TOP_LEFT, .2f);

        Container restrictedContainer = createContainer(String.valueOf("Container 1".hashCode()), 5, 60, width - 10, height - 5);
                restrictedContainer.columns(3)
                .layout(new RestrictedLayout()).enableSelecting(true)
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Click Me")).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Another Button")).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Add Another Button")).onClick(() -> restrictedContainer.addWidget(
                        new AnimatedButton.Builder().size(100, 20).label(("Button " + (restrictedContainer.getWidgets().size() + 1))).build())).build())
                .addWidget(new ScrollSelectorWidget.Builder().size(100, 20).options(List.of("Option 1", "Option 2", "Option 3", "Option 4", "Option 5")).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Add Toggle Button")).onClick(() -> restrictedContainer.addWidget(new ToggleWidget.Builder().size(100, 20).build())).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Add Slider Button")).onClick(() -> restrictedContainer.addWidget(new DoubleSliderWidget.Builder().build())).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Increase Columns")).onClick(() -> restrictedContainer.columns(restrictedContainer.getColumns() + 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Decrease Columns")).onClick(() -> restrictedContainer.columns(restrictedContainer.getColumns() - 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Increase Padding")).onClick(() -> restrictedContainer.padding(restrictedContainer.getPadding() + 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Decrease Padding")).onClick(() -> restrictedContainer.padding(restrictedContainer.getPadding() - 1)).build());

        Container managedContainer = createContainer(String.valueOf("ManagedContainer".hashCode()), 5, 60, width - 10, height - 5);
        managedContainer.columns(3).layout(new ManagedLayout())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Increase Columns")).onClick(() -> managedContainer.columns(managedContainer.getColumns() + 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Decrease Columns")).onClick(() -> managedContainer.columns(managedContainer.getColumns() - 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Increase Padding")).onClick(() -> managedContainer.padding(managedContainer.getPadding() + 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Decrease Padding")).onClick(() -> managedContainer.padding(managedContainer.getPadding() - 1)).build())
                .addWidget(new AnimatedButton.Builder().size(120, 20).label(("Add Random Widget")).onClick(() -> managedContainer.addWidget(randomWidget())).build());

        Container freeContainer = createContainer(String.valueOf("FreeContainer".hashCode()), 5, 60, width - 10, height - 5);
        freeContainer.layout(new FreeLayout())
                .addWidget(new AnimatedButton.Builder().size(120, 20).label(("Add Random Widget")).onClick(() -> freeContainer.addWidget(randomWidget())).build());

        SidePanel sidePanel = createSidePanel("Global Panel").collapsible("Global Panel").y(60).height(height - 5).width(width - 50).show().collapse();
        sidePanel.addWidget(new AnimatedButton.Builder().size(100, 20).label(("Panel Button")).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Increase Columns")).onClick(() -> sidePanel.container().columns(sidePanel.container().getColumns() + 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Decrease Columns")).onClick(() -> sidePanel.container().columns(sidePanel.container().getColumns() - 1)).build())
                .addWidget(new AnimatedButton.Builder().size(100, 20).label(("Add Another Button")).onClick(() ->
                        sidePanel.addWidget(new AnimatedButton.Builder().size(100, 20).label(("Button " + (sidePanel.container().getWidgets().size() + 1))).build())).build())
                .addWidget(new TextInputWidget.Builder().size(100, 20).placeholder("Type here...").text("Sample Text").build());

        tabs().addTab("Restricted", restrictedContainer);
        tabs().addTab("Managed", managedContainer);
        tabs().addTab("Free", freeContainer);

        tabsManager.builder().allowReorder(true).allowAdd(true).position(5, 36).size(width - 5, 18).onPlusButtonClicked(() -> tabs().addTab("Tab " + tabsManager.getTabs().size(), createContainer(5, 60, width - 10, height - 5)
                        .addWidget(new AnimatedButton(0, 0, 100, 20, ("Berger " + tabsManager.getTabs().size()))))).build();
        tabsManager.setActiveTab(restrictedContainer);

        SearchMode searchMode = new SearchMode(true);
        headerBuilder.addRight("close.png", () -> client.setScreen(null), "Close Screen")
                .addLeft("remotely.png", () -> client.setScreen(new ContainerTestingScreen()), "Reload Screen")
                .setSearchMode(searchMode, true)
                .build();
    }
}
