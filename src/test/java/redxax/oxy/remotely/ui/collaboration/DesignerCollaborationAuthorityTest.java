package redxax.oxy.remotely.ui.collaboration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.WidgetComposite;
import restudio.rescreen.ui.collaboration.CollaborativeWidget;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.ColorFieldWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.ReorderableWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignerCollaborationAuthorityTest {
    @Test
    void admittedStateIgnoresCallerMutationAndAcceptsReplacementAtTheSameTimestamp() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        CollaborativeWidget.DropDownState first = new CollaborativeWidget.DropDownState(true, 12f, 0);
        SelectorProbe source = new SelectorProbe(first);
        SelectorProbe target = new SelectorProbe(null);
        sender.addDrawableChild(source);
        receiver.addDrawableChild(target);
        JsonObject publication = DesignerCollaborationAuthority.widgetStates(sender);
        DesignerCollaborationAuthority.RemoteWidgetState admitted =
            new DesignerCollaborationAuthority.RemoteWidgetState(publication, 7L);
        JsonObject entry = publication.entrySet().iterator().next().getValue().getAsJsonObject();
        entry.getAsJsonObject("state").addProperty("selected", 1);

        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(admitted));
        assertEquals(first, target.state);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(admitted));
        assertEquals(first, target.state);

        DesignerCollaborationAuthority.RemoteWidgetState replacement =
            new DesignerCollaborationAuthority.RemoteWidgetState(publication, 7L);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(replacement));
        assertEquals(new CollaborativeWidget.DropDownState(true, 12f, 1), target.state);

        entry.getAsJsonArray("path").remove(0);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(replacement));
        assertEquals(new CollaborativeWidget.DropDownState(true, 12f, 1), target.state);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of());
        assertNull(target.state);
    }

    @Test
    void retainedStateFollowsRebuiltWidgetsAndLocalFocusAndClearsOnLeave() {
        ThemeManager.initBrowserDefaults();
        ExternalContainerScreen sender = new ExternalContainerScreen();
        ExternalContainerScreen receiver = new ExternalContainerScreen();
        Container sourceContainer = new Container("source", 0, 0, 200, 200);
        Container targetContainer = new Container("target", 0, 0, 200, 200);
        CollaborativeWidget.DropDownState state = new CollaborativeWidget.DropDownState(true, 8f, 1);
        SelectorProbe source = new SelectorProbe(state);
        TextInputWidget localInput = input();
        SelectorProbe target = new SelectorProbe(null, localInput);
        sourceContainer.addWidget(source);
        targetContainer.addWidget(target);
        sender.expose("resource", sourceContainer);
        receiver.expose("resource", targetContainer);
        DesignerCollaborationAuthority.RemoteWidgetState admitted = new DesignerCollaborationAuthority.RemoteWidgetState(
            DesignerCollaborationAuthority.widgetStates(sender), 1L);

        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(admitted));
        assertEquals(state, target.state);
        localInput.setFocused(true);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(admitted));
        assertNull(target.state);
        localInput.setFocused(false);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(admitted));
        assertEquals(state, target.state);

        Container rebuiltContainer = new Container("target", 0, 0, 200, 200);
        SelectorProbe rebuilt = new SelectorProbe(null);
        rebuiltContainer.addWidget(rebuilt);
        receiver.expose("resource", rebuiltContainer);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(admitted));
        assertNull(target.state);
        assertEquals(state, rebuilt.state);
        DesignerCollaborationAuthority.clearWidgetStates(receiver);
        assertNull(rebuilt.state);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of());
        assertNull(rebuilt.state);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(admitted));
        assertEquals(state, rebuilt.state);
    }

    @Test
    void selectorStatePreservesRemotePickerContentAndSelection() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        var state = new ItemSelectorWidget.CollaborationState(320, 240, "stone", "Stone", "No Matches", true,
            42.5f, 24, List.of(new ItemSelectorWidget.CollaborationItem("Stone", "minecraft", "block/stone", "ITEM",
                "Build With Stone", "rock stone", 7, "Block", true)));
        SelectorProbe source = new SelectorProbe(state);
        SelectorProbe target = new SelectorProbe(null);
        sender.addDrawableChild(source);
        receiver.addDrawableChild(target);

        JsonObject publication = DesignerCollaborationAuthority.widgetStates(sender);
        assertFalse(publication.isEmpty());
        DesignerCollaborationAuthority.applyWidgetStates(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteWidgetState(publication, 1L)));

        assertEquals(state, target.state);
    }

    @Test
    void selectorPublicationRetainsOldItemsAfterCatalogMutation() {
        ThemeManager.initBrowserDefaults();
        TestScreen screen = new TestScreen();
        ItemSelectorWidget selector = new ItemSelectorWidget(screen);
        ItemSelectorWidget.AsyncItemSnapshot[] catalog = {
            new ItemSelectorWidget.AsyncItemSnapshot(List.of(new ItemSelectorWidget.AsyncItem("Stone", "", "stone", () -> {})), false, "No Items")
        };
        selector.setAsyncItems(null, () -> catalog[0]);
        selector.openEmbedded();
        screen.addDrawableChild(selector);

        JsonObject first = DesignerCollaborationAuthority.widgetStates(screen);
        JsonArray firstItems = selectorItems(first);
        assertEquals(1, firstItems.size());
        selector.setSelectedItem("Stone");
        JsonObject selection = DesignerCollaborationAuthority.widgetStates(screen);
        assertEquals("", selectorState(first).get("selectedItem").getAsString());
        assertEquals("Stone", selectorState(selection).get("selectedItem").getAsString());

        catalog[0] = new ItemSelectorWidget.AsyncItemSnapshot(List.of(
            new ItemSelectorWidget.AsyncItem("Stone", "", "stone", () -> {}),
            new ItemSelectorWidget.AsyncItem("Dirt", "", "dirt", () -> {})
        ), false, "No Items");
        selector.setAsyncItems(null, () -> catalog[0]);
        JsonObject next = DesignerCollaborationAuthority.widgetStates(screen);
        assertEquals(1, firstItems.size());
        assertEquals("Stone", firstItems.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals(2, selectorItems(next).size());
        assertEquals("Dirt", selectorItems(next).get(1).getAsJsonObject().get("label").getAsString());

        catalog[0] = new ItemSelectorWidget.AsyncItemSnapshot(List.of(new ItemSelectorWidget.AsyncItem("Oak", "", "oak", () -> {})), false, "No Items");
        selector.setAsyncItems(null, () -> catalog[0]);
        JsonObject replacement = DesignerCollaborationAuthority.widgetStates(screen);
        assertEquals(2, selectorItems(next).size());
        assertEquals(1, selectorItems(replacement).size());
        assertEquals("Oak", selectorItems(replacement).get(0).getAsJsonObject().get("label").getAsString());
    }

    @Test
    void largeAsyncSelectorKeepsVisibleRowPointerAndPublishesAllItems() {
        ThemeManager.initBrowserDefaults();
        TestScreen screen = new TestScreen();
        screen.resize(800, 600);
        List<ItemSelectorWidget.AsyncItem> catalog = new ArrayList<>();
        for (int index = 0; index < 1024; index++) {
            String label = "Item " + index;
            catalog.add(new ItemSelectorWidget.AsyncItem(label, "", label, () -> {}));
        }
        ItemSelectorWidget selector = new ItemSelectorWidget(screen);
        selector.setPosition(20, 20);
        selector.setWidth(240);
        selector.setHeight(140);
        selector.setAsyncItems(null, () -> new ItemSelectorWidget.AsyncItemSnapshot(catalog, false, "No Items"));
        ScrollSelectorWidget mode = new ScrollSelectorWidget(0, 0, 240, 20, List.of("None", "Run Flow"));
        Container panel = new Container("inspector", 20, 20, 300, 500);
        panel.addWidget(selector);
        panel.addWidget(mode);
        screen.addDrawableChild(panel);
        selector.openEmbedded();
        mode.setFocused(true);

        Widget visible = selector.getOverlayChildren().stream().filter(IconButton.class::isInstance).findFirst().orElseThrow();
        Widget hidden = selector.getChildWidgets().getLast();
        assertFalse(selector.getOverlayChildren().contains(hidden));
        assertSame(visible, DesignerCollaborationAuthority.hit(screen,
            visible.getX() + visible.getWidth() / 2, visible.getY() + visible.getHeight() / 2, null));
        assertSame(mode, DesignerCollaborationAuthority.resolve(screen, DesignerCollaborationAuthority.path(screen, mode)));
        assertEquals(1024, selectorItems(DesignerCollaborationAuthority.widgetStates(screen)).size());
    }

    private static JsonArray selectorItems(JsonObject publication) {
        return selectorState(publication).getAsJsonArray("items");
    }

    private static JsonObject selectorState(JsonObject publication) {
        for (var value : publication.entrySet()) {
            JsonObject state = value.getValue().getAsJsonObject().getAsJsonObject("state");
            if ("selector".equals(state.get("type").getAsString())) {
                return state;
            }
        }
        throw new AssertionError("Selector state missing");
    }

    @Test
    void separatelyPublishedPickerRetainsItsNestedTextSelection() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        TextInputWidget sourceInput = input();
        TextInputWidget remoteInput = input();
        sourceInput.setText("boolean");
        sourceInput.selectAll();
        sourceInput.setFocused(true);
        var state = new ItemSelectorWidget.CollaborationState(320, 240, "boolean", "", "No Matches", false,
            0f, 24, List.of());
        SelectorProbe source = new SelectorProbe(state, sourceInput);
        SelectorProbe remote = new SelectorProbe(null, remoteInput);
        sender.addDrawableChild(source);
        receiver.addDrawableChild(remote);

        JsonObject publication = DesignerCollaborationAuthority.widgetStates(sender, source);
        assertFalse(publication.toString().contains("\"type\":\"selector\""));
        DesignerCollaborationAuthority.applyWidgetStates(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteWidgetState(publication, 1L)));

        assertEquals("boolean", remoteInput.getText());
    }

    @Test
    void colorFieldPublishesItsInnerEditAndRestoresTheCommittedValueWhenThePeerLeaves() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        ColorFieldWidget source = new ColorFieldWidget(10, 20, 100, 20, "#112233");
        ColorFieldWidget target = new ColorFieldWidget(50, 70, 120, 20, "#112233");
        source.setCollaborationKey("field:color");
        target.setCollaborationKey("field:color");
        sender.addDrawableChild(source);
        receiver.addDrawableChild(new AnimatedButton(0, 0, 20, 20, "Other"));
        receiver.addDrawableChild(target);
        TextInputWidget sourceInput = (TextInputWidget) source.getChildWidgets().getFirst();
        TextInputWidget targetInput = (TextInputWidget) target.getChildWidgets().getFirst();
        sourceInput.setFocused(true);
        sourceInput.setText("#445566");

        JsonObject publication = DesignerCollaborationAuthority.widgetStates(sender);
        DesignerCollaborationAuthority.applyWidgetStates(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteWidgetState(publication, 1L)));

        assertEquals("#445566", targetInput.getText());
        assertSame(targetInput, DesignerCollaborationAuthority.resolve(receiver,
            DesignerCollaborationAuthority.path(sender, sourceInput)));
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of());
        assertEquals("#112233", targetInput.getText());
    }

    private static final class SelectorProbe extends AnimatedWidget implements CollaborativeWidget, WidgetComposite {
        private State state;
        private final List<Widget> children;

        private SelectorProbe(State state, Widget... children) {
            super(0, 0, 320, 240, "Picker");
            this.state = state;
            this.children = List.of(children);
        }

        @Override public List<Widget> getChildWidgets() { return children; }
        @Override public boolean hasCollaborationState() { return state != null; }
        @Override public State captureCollaborationState() { return state; }
        @Override public void applyCollaborationState(State value) { state = value; }
        @Override public void clearCollaborationState() { state = null; }
        @Override protected void drawContent(IDrawContext context, int mouseX, int mouseY) { }
    }

    @Test
    void resolvesPanelFieldsByIdentityAcrossDifferentRowOrders() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        StudioPanel senderPanel = new StudioPanel(sender, "resource").show();
        StudioPanel receiverPanel = new StudioPanel(receiver, "resource").show();
        showImmediately(senderPanel);
        showImmediately(receiverPanel);
        AnimatedButton optional = button("Optional");
        AnimatedButton sourceWorld = button("World");
        AnimatedButton remoteWorld = button("World");
        ReSyncStudioPanelState.identify(sourceWorld, "resource-field:world");
        ReSyncStudioPanelState.identify(remoteWorld, "resource-field:world");
        senderPanel.setWidgets(List.of(optional, sourceWorld));
        receiverPanel.setWidgets(List.of(remoteWorld));

        JsonArray path = DesignerCollaborationAuthority.path(sender, sourceWorld);

        assertEquals("panel:resource", path.get(0).getAsString());
        assertEquals("key:resource-field:world", path.get(1).getAsString());
        assertSame(remoteWorld, DesignerCollaborationAuthority.resolve(receiver, path));
    }

    @Test
    void mirrorsTransientDropdownStateThroughTheSharedAuthority() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        StudioPanel senderPanel = new StudioPanel(sender, "resource").show();
        StudioPanel receiverPanel = new StudioPanel(receiver, "resource").show();
        showImmediately(senderPanel);
        showImmediately(receiverPanel);
        DropDownWidget<String> source = dropdown();
        DropDownWidget<String> remote = dropdown();
        ReSyncStudioPanelState.identify(source, "resource-field:type");
        ReSyncStudioPanelState.identify(remote, "resource-field:type");
        senderPanel.setWidgets(List.of(source));
        receiverPanel.setWidgets(List.of(remote));
        source.onClick(source.getX() + 1, source.getY() + 1, 0);

        JsonObject states = DesignerCollaborationAuthority.widgetStates(sender);
        assertEquals(1, states.size());
        DesignerCollaborationAuthority.applyWidgetStates(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteWidgetState(states, 1L)));

        assertFalse(remote.isExpanded());
        assertTrue(remote.isDropdownVisible());
        assertTrue(DesignerCollaborationAuthority.widgetStates(receiver).isEmpty());
    }

    @Test
    void resolvesAndAccentsFocusedFieldChildrenWithoutDesignerKeys() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        StudioPanel senderPanel = new StudioPanel(sender, "resource").show();
        StudioPanel receiverPanel = new StudioPanel(receiver, "resource").show();
        showImmediately(senderPanel);
        showImmediately(receiverPanel);
        TextInputWidget sourceInput = input();
        TextInputWidget remoteInput = input();
        TitledRowWidget sourceWorld = row("World", sourceInput);
        TitledRowWidget remoteWorld = row("World", remoteInput);
        senderPanel.setWidgets(List.of(row("Optional", input()), sourceWorld));
        receiverPanel.setWidgets(List.of(remoteWorld));
        sourceWorld.setFocused(true);
        sourceInput.setFocused(true);

        JsonArray path = DesignerCollaborationAuthority.path(sender, sourceInput);
        DesignerCollaborationAuthority.applyFocusAccents(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteFocus(path, 0xFFFF0000)));

        assertSame(sourceInput, DesignerCollaborationAuthority.focused(sender));
        assertSame(remoteInput, DesignerCollaborationAuthority.resolve(receiver, path));
        assertNotNull(remoteInput.getCollaborationAccent());
    }

    @Test
    void resolvesPointersThroughContainerContentCoordinatesAndCurrentScroll() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        sender.width = 400;
        sender.height = 300;
        receiver.width = 400;
        receiver.height = 300;
        Container source = new Container("container", 10, 20, 200, 220);
        Container remote = new Container("container", 10, 20, 300, 220);
        source.setScrollOffset(120f);
        remote.setScrollOffset(40f);
        sender.addDrawableChild(source);
        receiver.addDrawableChild(remote);
        sender.mouseMoved(mouseMoved(sender, 110, 100));

        JsonObject state = DesignerCollaborationAuthority.pointer(sender, 110, 100, null);
        DesignerCollaborationAuthority.Pointer pointer = DesignerCollaborationAuthority.resolvePointer(receiver, state);

        assertNotNull(pointer);
        assertEquals(160, pointer.x());
        assertEquals(180, pointer.y());
    }

    @Test
    void resolvesTheCursorOverAMirroredDropdownWithoutOpeningItLocally() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        DropDownWidget<String> source = dropdown();
        DropDownWidget<String> remote = dropdown();
        source.setPosition(40, 20);
        remote.setPosition(200, 80);
        remote.setWidth(270);
        sender.addDrawableChild(source);
        receiver.addDrawableChild(remote);
        source.onClick(41, 21, 0);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(
            new DesignerCollaborationAuthority.RemoteWidgetState(DesignerCollaborationAuthority.widgetStates(sender), 1)));

        JsonObject state = DesignerCollaborationAuthority.pointer(sender, 130, 47, null);
        DesignerCollaborationAuthority.Pointer pointer = DesignerCollaborationAuthority.resolvePointer(receiver, state);

        assertTrue(remote.isDropdownVisible());
        assertFalse(remote.isExpanded());
        assertNotNull(pointer);
        assertEquals(335, pointer.x());
        assertEquals(107, pointer.y());
    }

    @Test
    void dropdownOverlayCursorUsesTheReceiverFieldPositionBeyondThePanelViewport() {
        ThemeManager.initBrowserDefaults();
        ExternalContainerScreen sender = new ExternalContainerScreen();
        ExternalContainerScreen receiver = new ExternalContainerScreen();
        Container sourcePanel = new Container("source", 10, 20, 200, 35);
        Container remotePanel = new Container("remote", 250, 60, 300, 35);
        DropDownWidget<String> source = dropdown();
        DropDownWidget<String> remote = dropdown();
        sourcePanel.addWidget(source);
        remotePanel.addWidget(remote);
        sender.expose("resource", sourcePanel);
        receiver.expose("resource", remotePanel);
        source.setPosition(20, 38);
        source.setWidth(180);
        remote.setPosition(270, 78);
        remote.setWidth(240);
        source.onClick(21, 39, 0);
        DesignerCollaborationAuthority.applyWidgetStates(receiver, List.of(
            new DesignerCollaborationAuthority.RemoteWidgetState(DesignerCollaborationAuthority.widgetStates(sender), 1)));

        JsonObject state = DesignerCollaborationAuthority.pointer(sender, 110, 65, null);
        DesignerCollaborationAuthority.Pointer pointer = DesignerCollaborationAuthority.resolvePointer(receiver, state);

        assertTrue(remote.isDropdownVisible());
        assertFalse(remote.isExpanded());
        assertTrue(65 > sourcePanel.getY() + sourcePanel.getHeight());
        assertNotNull(pointer);
        assertEquals(390, pointer.x());
        assertEquals(105, pointer.y());
    }

    @Test
    void resolvesPopupFieldsAcrossDifferentScreenRootOrders() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        TextInputWidget sourceInput = input();
        TextInputWidget remoteInput = input();
        PopupWidget sourcePopup = new PopupWidget.Builder("World Settings").addRow("World", sourceInput).build();
        PopupWidget remotePopup = new PopupWidget.Builder("World Settings").addRow("World", remoteInput).build();
        sender.addDrawableChild(sourcePopup);
        receiver.addDrawableChild(button("Unrelated"));
        receiver.addDrawableChild(remotePopup);

        JsonArray path = DesignerCollaborationAuthority.path(sender, sourceInput);

        assertEquals("PopupWidget:World Settings", path.get(0).getAsJsonObject().get("identity").getAsString());
        assertEquals("key:popup:world-settings/row:world:0/0", path.get(1).getAsString());
        assertSame(remoteInput, DesignerCollaborationAuthority.resolve(receiver, path));
    }

    @Test
    void mirrorsFocusedTextAndSelectionThroughTheWidgetCapability() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        StudioPanel senderPanel = new StudioPanel(sender, "resource").show();
        StudioPanel receiverPanel = new StudioPanel(receiver, "resource").show();
        showImmediately(senderPanel);
        showImmediately(receiverPanel);
        TextInputWidget source = input();
        TextInputWidget remote = input();
        senderPanel.setWidgets(List.of(row("Name", source)));
        receiverPanel.setWidgets(List.of(row("Name", remote)));
        source.setText("Selected Name");
        source.selectAll();
        source.setFocused(true);

        JsonObject states = DesignerCollaborationAuthority.widgetStates(sender);
        DesignerCollaborationAuthority.applyWidgetStates(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteWidgetState(states, 1L)));

        assertEquals("Selected Name", remote.getText());
    }

    @Test
    void resolvesAndAccentsFieldsInsideReorderableRows() {
        ThemeManager.initBrowserDefaults();
        TestScreen sender = new TestScreen();
        TestScreen receiver = new TestScreen();
        StudioPanel senderPanel = new StudioPanel(sender, "resource").show();
        StudioPanel receiverPanel = new StudioPanel(receiver, "resource").show();
        showImmediately(senderPanel);
        showImmediately(receiverPanel);
        TextInputWidget sourceInput = input();
        TextInputWidget remoteInput = input();
        ReorderableWidget<RowWidget> source = reorderable(sourceInput);
        ReorderableWidget<RowWidget> remote = reorderable(remoteInput);
        senderPanel.setWidgets(List.of(new TitledRowWidget.Builder().title("Paths").size(180, 34).addWidget(source).build()));
        receiverPanel.setWidgets(List.of(new TitledRowWidget.Builder().title("Paths").size(180, 34).addWidget(remote).build()));
        sourceInput.setFocused(true);

        JsonArray path = DesignerCollaborationAuthority.path(sender, sourceInput);
        DesignerCollaborationAuthority.applyFocusAccents(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteFocus(path, 0xFFFF0000)));

        assertSame(remoteInput, DesignerCollaborationAuthority.resolve(receiver, path));
        assertNotNull(remoteInput.getCollaborationAccent());
    }

    @Test
    void toggleKeepsItsSemanticAccentDuringRemoteFocus() {
        ThemeManager.initBrowserDefaults();
        ToggleWidget toggle = new ToggleWidget.Builder().toggled(true).size(40, 18).build();

        toggle.setCollaborationAccent(CollaborationVisuals.accent(0xFFFF0000));

        assertFalse(toggle.canBeFocused());
        assertFalse(toggle.hasCollaborationAccent());
    }

    @Test
    void resolvesFieldsFromAHostOwnedCollaborationContainer() {
        ThemeManager.initBrowserDefaults();
        ExternalContainerScreen sender = new ExternalContainerScreen();
        ExternalContainerScreen receiver = new ExternalContainerScreen();
        Container sourceContainer = new Container("source", 0, 0, 200, 200);
        Container remoteContainer = new Container("remote", 0, 0, 200, 200);
        DropDownWidget<String> source = dropdown();
        DropDownWidget<String> remote = dropdown();
        ReSyncStudioPanelState.identify(source, "resource-field:type");
        ReSyncStudioPanelState.identify(remote, "resource-field:type");
        sourceContainer.addWidget(source);
        remoteContainer.addWidget(remote);
        sender.expose("studio_resource", sourceContainer);
        receiver.expose("studio_resource", remoteContainer);
        source.onClick(source.getX() + 1, source.getY() + 1, 0);

        JsonArray path = DesignerCollaborationAuthority.path(sender, source);
        JsonObject states = DesignerCollaborationAuthority.widgetStates(sender);
        DesignerCollaborationAuthority.applyWidgetStates(receiver,
            List.of(new DesignerCollaborationAuthority.RemoteWidgetState(states, 1L)));

        assertEquals("panel:studio_resource", path.get(0).getAsString());
        assertSame(remote, DesignerCollaborationAuthority.resolve(receiver, path));
        assertTrue(remote.isDropdownVisible());
    }

    private static AnimatedButton button(String label) {
        return new AnimatedButton.Builder().label(label).size(180, 18).build();
    }

    private static DropDownWidget<String> dropdown() {
        return new DropDownWidget.Builder<>(List.of("One", "Two")).size(180, 18).build();
    }

    private static TextInputWidget input() {
        return new TextInputWidget.Builder().size(180, 18).build();
    }

    private static TitledRowWidget row(String title, TextInputWidget input) {
        return new TitledRowWidget.Builder().title(title).size(180, 34).addWidget(input).build();
    }

    private static ReorderableWidget<RowWidget> reorderable(TextInputWidget input) {
        RowWidget row = new RowWidget.Builder().size(180, 18).addWidget(input).build();
        return new ReorderableWidget.Builder<RowWidget>().items(row).size(180, 18).build();
    }

    private static ReMouseEvent mouseMoved(ReScreen screen, double x, double y) {
        return new ReMouseEvent(new Object(), screen, System.nanoTime(), ReModifierState.none(), ReMouseEvent.Action.MOVED,
            x, y, 0.0, 0.0, ReMouseButton.UNKNOWN, -1, 0);
    }

    private static void showImmediately(StudioPanel panel) {
        panel.sidePanel().animation(false);
        panel.sidePanel().update();
    }

    private static final class TestScreen extends ReScreen {
    }

    private static final class ExternalContainerScreen extends ReScreen {
        private String id = "";
        private Widget container;

        private void expose(String id, Widget container) {
            this.id = id;
            this.container = container;
        }

        @Override
        public Map<String, Widget> collaborationContainers() {
            return container != null ? Map.of(id, container) : Map.of();
        }
    }
}
