package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import restudio.rescreen.ui.settings.Setting;
import redxax.oxy.remotely.data.flow.AutomationDefinitionDraft;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ManagedResourceEditorModel;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.registry.NodeRegistry;
import redxax.oxy.remotely.flow.sync.FlowResourceMetadata;
import redxax.oxy.remotely.flow.sync.NodeRegistrySnapshot;
import redxax.oxy.remotely.flow.ui.studio.ReSyncResourceCreator;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioView;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import redxax.oxy.remotely.test.TestDrawContext;
import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationDefinitionDesignerContractTest {
    private final List<AutomationDefinitionDesignerScreen> screens = new ArrayList<>();
    private final List<FlowManager> managers = new ArrayList<>();
    private ThemeManager.Snapshot theme;
    private FlowManager previousFlowManager;
    private NodeRegistry previousNodeRegistry;
    private List<Runnable> previousScreenTasks;

    @BeforeEach
    void initializeTheme() throws Exception {
        theme = ThemeManager.snapshot();
        previousFlowManager = FlowManager.getInstance();
        previousNodeRegistry = NodeRegistry.getInstance();
        previousScreenTasks = takeScreenTasks();
        ThemeManager.initBrowserDefaults();
    }

    @AfterEach
    void closeDraftsAndWorkers() throws Exception {
        try {
            for (AutomationDefinitionDesignerScreen screen : screens) {
                VersionedEditorDraft<?> draft = value(screen, "draft", VersionedEditorDraft.class);
                if (draft != null) {
                    draft.close();
                }
                AsyncTaskWorker worker = value(screen, "worker", AsyncTaskWorker.class);
                if (worker != null) {
                    worker.close();
                }
            }
            for (FlowManager manager : managers) {
                manager.shutdown();
            }
        } finally {
            try {
                restoreFlowManager(previousFlowManager);
                restoreNodeRegistry(previousNodeRegistry);
                restoreScreenTasks(previousScreenTasks);
            } finally {
                ThemeManager.restore(theme);
            }
        }
    }

    @Test
    void openingEachAutomationDefinitionReachesTheUnifiedWorkspace() throws Exception {
        String serverId = ServerId.deterministic("subresource-navigation").canonicalText();
        FlowManager manager = connectedManager(serverId);
        for (String type : List.of(AutomationDefinitionDraft.VARIABLE, AutomationDefinitionDraft.TIMER,
            AutomationDefinitionDraft.SCHEDULE)) {
            ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
            JsonObject resource = AutomationDefinitionDraft.create(type, "shared", "",
                new AutomationDefinitionDraft.Target("flow", "scheduled-flow"));
            manager.applyServerJsonResourceList(serverId, resourceType, List.of("shared"));
            manager.cacheJsonResource(serverId, resourceType, resource);
            TestStudioScreen host = new TestStudioScreen(serverId);
            host.openDefinition(type, "shared");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (host.activeView() == null && System.nanoTime() < deadline) {
                drainScreenTasks();
                Thread.onSpinWait();
            }
            assertTrue(host.activeView() instanceof ScreenBackedStudioView);
            ScreenBackedStudioView view = (ScreenBackedStudioView) host.activeView();
            assertTrue(view.screen() instanceof AutomationDefinitionDesignerScreen);
            AutomationDefinitionDesignerScreen designer = (AutomationDefinitionDesignerScreen) view.screen();
            screens.add(designer);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (value(designer, "document", JsonObject.class) == null && System.nanoTime() < deadline) {
                designer.tick();
                drainScreenTasks();
                Thread.onSpinWait();
            }
            assertEquals(resource, value(designer, "document", JsonObject.class));
            assertNotSame(resource, value(designer, "document", JsonObject.class));
            AsyncTaskWorker worker = value(designer, "worker", AsyncTaskWorker.class);
            assertTrue(worker != null);
            designer.init();
            assertSame(worker, value(designer, "worker", AsyncTaskWorker.class));
        }
    }

    @Test
    void openingEveryTypeReusesOneAutomationTabAndDesigner() throws Exception {
        TestStudioScreen host = new TestStudioScreen();
        host.openDefinitions(AutomationDefinitionDraft.TIMER);

        assertTrue(host.activeView() instanceof ScreenBackedStudioView);
        ScreenBackedStudioView view = (ScreenBackedStudioView) host.activeView();
        assertTrue(view.screen() instanceof AutomationDefinitionDesignerScreen);
        AutomationDefinitionDesignerScreen designer = (AutomationDefinitionDesignerScreen) view.screen();
        screens.add(designer);
        assertEquals(AutomationDefinitionDraft.TIMER, value(designer, "type", String.class));
        assertNull(value(designer, "document", JsonObject.class));

        host.openDefinitions(AutomationDefinitionDraft.SCHEDULE);

        assertSame(view, host.activeView());
        assertSame(designer, ((ScreenBackedStudioView) host.activeView()).screen());
        assertEquals(1, host.documentCount());
        assertEquals("Sub Resources", host.activeTitle());
        assertEquals(AutomationDefinitionDraft.SCHEDULE, value(designer, "type", String.class));
    }

    @Test
    void navigationOffersExplicitCreationForEveryDefinitionType() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.VARIABLE, null);
        mountWithoutRefresh(screen);
        Map<?, ?> entries = value(screen, "definitionEntries", Map.class);
        List<String> labels = entries.values().stream().map(MountableButtonWidget.class::cast)
            .map(MountableButtonWidget::getMessage).toList();

        assertTrue(labels.contains("New Variable"));
        assertTrue(labels.contains("New Timer"));
        assertTrue(labels.contains("New Schedule"));

        invoke(screen, "requestNew", new Class<?>[]{String.class}, AutomationDefinitionDraft.TIMER);

        assertEquals(AutomationDefinitionDraft.TIMER, value(screen, "type", String.class));
        assertTrue(value(screen, "creating", Boolean.class));
    }

    @Test
    void invalidRawNumberRemainsDirtyAndCannotBecomeAPreparedDefinition() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.TIMER, null);
        openNew(screen, "");

        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "id", "timer");
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "defaultDuration", "-");

        Map<String, String> raw = values(screen, "rawValues");
        assertEquals("-", raw.get("defaultDuration"));
        assertEquals("-", value(screen, "document", JsonObject.class).get("defaultDuration").getAsString());
        assertTrue(invokeBoolean(screen, "dirty"));
        JsonObject candidate = invokeValue(screen, "materializeRawDocument", JsonObject.class);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> AutomationDefinitionDraft.prepare(AutomationDefinitionDraft.TIMER, "timer", candidate));
        assertTrue(failure.getMessage().contains("number"));

        invoke(screen, "discardChanges", new Class<?>[0]);
        assertFalse(invokeBoolean(screen, "dirty"));
        assertFalse("-".equals(values(screen, "rawValues").get("defaultDuration")));
    }

    @Test
    void staleAndFailedSaveCallbacksLeaveTheDirtyDraftIntact() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.TIMER, null);
        openNew(screen, "");
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "description", "Unsaved");
        set(screen, "saveSequence", 5L);
        set(screen, "savePending", true);

        finishSave(screen, 4L, 1L, false, false);
        assertTrue(value(screen, "savePending", Boolean.class));
        assertEquals("Unsaved", values(screen, "rawValues").get("description"));

        finishSave(screen, 5L, 1L, false, false);
        assertFalse(value(screen, "savePending", Boolean.class));
        assertEquals("Unsaved", values(screen, "rawValues").get("description"));
        assertTrue(invokeBoolean(screen, "dirty"));
    }

    @Test
    void dirtyDraftCanCloseWithoutBuildingAChoicePanel() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.VARIABLE, null);
        openNew(screen, "");
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "name", "Pending Name");

        assertTrue(invokeBoolean(screen, "dirty"));
        assertEquals("Pending Name", values(screen, "rawValues").get("name"));
        assertTrue(screen.canCloseStudioDocument());
        screen.discardUnsavedChanges();
        assertFalse(invokeBoolean(screen, "dirty"));
    }

    @Test
    void malformedEnumLabelsRemainExactUntilTheFirstValidChoiceDispatches() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.VARIABLE, null);
        openNew(screen, "");

        for (String invalid : List.of("_future_scope", "future__scope")) {
            invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "scope", invalid);
            @SuppressWarnings("unchecked")
            DropDownWidget<String> dropdown = (DropDownWidget<String>) invoke(screen, "fieldControl",
                new Class<?>[]{String.class}, "scope");

            assertEquals(invalid, dropdown.getSelectedItem());
            assertEquals(invalid, label(dropdown, invalid));
            dropdown.setSelectedItem("flow");
            assertEquals("flow", values(screen, "rawValues").get("scope"));
            assertEquals("flow", value(screen, "document", JsonObject.class).get("scope").getAsString());
        }
    }

    @Test
    void nondurableCreationBlocksCloseThenReturnsToDirtyChoiceOnError() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.VARIABLE, null);
        openNew(screen, "");
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "id", "queued");
        Object pending = installPendingCreation(screen, "queued");

        screen.close();
        assertFalse(value(screen, "disposed", Boolean.class));
        assertSame(pending, value(screen, "pendingCreation", Object.class));
        assertFalse(screen.canCloseStudioDocument());

        finishCreationDurability(screen, pending, null, new IllegalStateException("offline"));
        assertFalse(value(screen, "creationPending", Boolean.class));
        assertFalse(value(screen, "creationQueued", Boolean.class));
        assertNull(value(screen, "pendingCreation", Object.class));

        assertTrue(screen.canCloseStudioDocument());

        AutomationDefinitionDesignerScreen rejected = designer(AutomationDefinitionDraft.VARIABLE, null);
        openNew(rejected, "");
        Object rejectedPending = installPendingCreation(rejected, "rejected");
        finishCreationDurability(rejected, rejectedPending, false, null);
        assertFalse(value(rejected, "creationPending", Boolean.class));
        assertFalse(value(rejected, "creationQueued", Boolean.class));
        assertNull(value(rejected, "pendingCreation", Object.class));
    }

    @Test
    void durableQueuedCreationCanLeaveWhileTheObserverRemainsBackgroundOwned() throws Exception {
        Screen parent = new Screen() {
        };
        AtomicReference<ReSyncResourceCreator.Result> completed = new AtomicReference<>();
        AutomationDefinitionDesignerScreen screen = designer(parent, AutomationDefinitionDraft.VARIABLE,
            completed::set);
        openNew(screen, "");
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "id", "queued");
        Object pending = installPendingCreation(screen, "queued");

        finishCreationDurability(screen, pending, true, null);
        assertTrue(value(screen, "creationPending", Boolean.class));
        assertTrue(value(screen, "creationQueued", Boolean.class));
        assertSame(pending, value(screen, "pendingCreation", Object.class));

        ScreenManager manager = ScreenManager.getInstance();
        Field currentScreen = ScreenManager.class.getDeclaredField("currentScreen");
        currentScreen.setAccessible(true);
        Object previous = currentScreen.get(manager);
        boolean desktopMode = Config.desktopMode;
        try {
            Config.desktopMode = false;
            currentScreen.set(manager, screen);
            screen.close();
            assertSame(parent, currentScreen.get(manager));
            assertTrue(value(screen, "disposed", Boolean.class));
            assertSame(pending, value(screen, "pendingCreation", Object.class));
            ReSyncResourceCreator.Result result = new ReSyncResourceCreator.Result(
                AutomationDefinitionDraft.VARIABLE, "queued", new JsonObject());
            acceptCreated(screen, pending, result);
            assertSame(result, completed.get());
            assertNull(value(screen, "pendingCreatedId", String.class));
        } finally {
            currentScreen.set(manager, previous);
            Config.desktopMode = desktopMode;
        }
    }

    @Test
    void creationCallbackSeparatesExternalCompletionFromOwnedScreenSelection() throws Exception {
        AtomicReference<ReSyncResourceCreator.Result> completed = new AtomicReference<>();
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.VARIABLE, completed::set);
        Object pending = installPendingCreation(screen, "created");
        ReSyncResourceCreator.Result wrongType = new ReSyncResourceCreator.Result(
            AutomationDefinitionDraft.TIMER, "wrong", new JsonObject());
        ReSyncResourceCreator.Result wrongId = new ReSyncResourceCreator.Result(
            AutomationDefinitionDraft.VARIABLE, "wrong", new JsonObject());
        ReSyncResourceCreator.Result exact = new ReSyncResourceCreator.Result(
            AutomationDefinitionDraft.VARIABLE, "created", new JsonObject());

        acceptCreated(screen, pending, wrongType);
        assertNull(completed.get());
        assertNull(value(screen, "pendingCreatedId", String.class));

        acceptCreated(screen, pending, wrongId);
        assertNull(completed.get());
        assertNull(value(screen, "pendingCreatedId", String.class));

        acceptCreated(screen, pending, exact);
        assertSame(exact, completed.get());
        assertEquals("created", value(screen, "pendingCreatedId", String.class));
        assertTrue(value(screen, "creationPending", Boolean.class));
    }

    @Test
    void reinitializationReopensTheWorkerAndDraftWithoutLosingDirtyValues() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.TIMER, null);
        screen.resize(900, 420);
        screen.init();
        openNew(screen, "timers");
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "name", "Unsaved Timer");
        AsyncTaskWorker firstWorker = value(screen, "worker", AsyncTaskWorker.class);
        VersionedEditorDraft<?> firstDraft = value(screen, "draft", VersionedEditorDraft.class);

        screen.removed();
        assertTrue(firstWorker.isClosed());
        screen.init();

        AsyncTaskWorker secondWorker = value(screen, "worker", AsyncTaskWorker.class);
        VersionedEditorDraft<?> secondDraft = value(screen, "draft", VersionedEditorDraft.class);
        assertNotSame(firstWorker, secondWorker);
        assertFalse(secondWorker.isClosed());
        assertNotSame(firstDraft, secondDraft);
        assertEquals("Unsaved Timer", values(screen, "rawValues").get("name"));
        assertTrue(invokeBoolean(screen, "dirty"));

        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "description", "Still Editable");
        assertEquals("Still Editable", value(screen, "document", JsonObject.class).get("description").getAsString());
    }

    @Test
    void resizeAndPanelLayoutPreserveEditorIdentityCaretScrollAndPopup() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.VARIABLE, null);
        screen.resize(900, 280);
        screen.init();
        openNew(screen, "");
        TextInputWidget input = textInput(screen, "Name");
        input.applyCollaborationState(new TextInputWidget.CollaborationState("Variable Name", 8, 2, 8, 3));
        input.setFocused(true);
        TextInputWidget.CollaborationState textState = (TextInputWidget.CollaborationState) input.captureCollaborationState();
        AnimatedButton typeButton = value(screen, "valueTypeBrowse", AnimatedButton.class);
        assertEquals("Boolean", typeButton.getMessage());
        Container workspace = value(screen, "workspace", Container.class);
        workspace.setScrollOffset(40);
        float scroll = workspace.getScrollOffset();
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(value(screen, "parent", Screen.class))
            .size(180, 120).build();
        set(screen, "valueTypeSelector", selector);
        selector.show(30, 30);
        assertTrue(selector.isOpen());

        screen.resize(980, 280);
        screen.tick();

        assertSame(input, textInput(screen, "Name"));
        assertSame(typeButton, value(screen, "valueTypeBrowse", AnimatedButton.class));
        assertSame(workspace, value(screen, "workspace", Container.class));
        assertSame(selector, value(screen, "valueTypeSelector", ItemSelectorWidget.class));
        assertTrue(selector.isOpen());
        assertEquals(invokeValue(screen, "valueTypeSelectorX", Integer.class), selector.getX());
        assertEquals(invokeValue(screen, "valueTypeSelectorY", Integer.class), selector.getY());
        assertEquals(textState, input.captureCollaborationState());
        assertTrue(input.isFocused());
        assertEquals(scroll, workspace.getScrollOffset());
    }

    @Test
    void switchingDefinitionsReusesMountedSettingsAndControls() throws Exception {
        String serverId = ServerId.deterministic("automation-popup-reuse").canonicalText();
        FlowManager manager = connectedManager(serverId);
        try {
            NodeRegistry registry = new NodeRegistry();
            applyAvailableMetadata(registry, serverId, AutomationDefinitionDraft.TIMER);
            manager.cacheServerCapabilities(serverId, availableCapabilities(AutomationDefinitionDraft.TIMER));
            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.TIMER_DEFINITION,
                List.of("first", "second"));
            JsonObject first = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "first", "", null);
            first.addProperty("name", "First Timer");
            JsonObject second = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "second", "", null);
            second.addProperty("name", "Second Timer");
            manager.cacheJsonResource(serverId, ReSyncResourceType.TIMER_DEFINITION, first);
            manager.cacheJsonResource(serverId, ReSyncResourceType.TIMER_DEFINITION, second);
            AutomationDefinitionDesignerScreen screen = designer(serverId, AutomationDefinitionDraft.TIMER, null);
            mountWithoutRefresh(screen);
            prepareAndPublish(screen);

            List<?> items = value(screen, "items", List.class);
            invoke(screen, "openItem", new Class<?>[]{items.getFirst().getClass(), boolean.class},
                items.getFirst(), false);
            Container workspace = value(screen, "workspace", Container.class);
            List<AnimatedWidget> settings = List.copyOf(workspace.getWidgets());
            Setting identity = (Setting) settings.getFirst();
            identity.collapse(true);
            TextInputWidget name = textInput(screen, "Name");

            invoke(screen, "openItem", new Class<?>[]{items.get(1).getClass(), boolean.class}, items.get(1), false);

            assertEquals(settings, workspace.getWidgets());
            assertSame(identity, workspace.getWidgets().getFirst());
            assertTrue(identity.isCollapsed);
            assertSame(name, textInput(screen, "Name"));
            assertEquals("Second Timer", name.getText());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void controlsUseDefaultsAndOnlyMountableContentRowsUseThirtyPixelHeight() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.VARIABLE, null);
        screen.resize(900, 420);
        screen.init();
        openNew(screen, "");

        assertEquals(30, value(screen, "validationRow", MountableButtonWidget.class).getHeight());
        assertTrue(defaultControlHeight(value(screen, "primaryAction", AnimatedWidget.class)));
        assertEquals("Boolean", value(screen, "valueTypeBrowse", AnimatedButton.class).getMessage());
        assertTrue(value(screen, "fieldControls", Map.class).get("valueType") instanceof AnimatedButton);
        for (AnimatedWidget control : widgets(screen, "formControls")) {
            assertTrue(control.getHeight() == 18 || control.getHeight() == 20);
            assertFalse(control.entranceAnimationEnabled);
        }
        for (Object fieldRow : value(screen, "fieldRows", Map.class).values()) {
            assertFalse(value(fieldRow, "widget", AnimatedWidget.class).entranceAnimationEnabled);
        }
        assertTrue(value(screen, "workspace", Container.class).getWidgets().stream().map(AnimatedWidget.class::cast)
            .noneMatch(widget -> widget.entranceAnimationEnabled));
        List<Integer> settledHeights = value(screen, "workspace", Container.class).getWidgets().stream()
            .map(AnimatedWidget::getHeight).toList();
        for (int tick = 0; tick < 12; tick++) {
            screen.tick();
        }
        assertEquals(settledHeights, value(screen, "workspace", Container.class).getWidgets().stream()
            .map(AnimatedWidget::getHeight).toList());

        set(screen, "creating", false);
        invoke(screen, "rebuildForm", new Class<?>[0]);
        assertTrue(defaultControlHeight(value(screen, "deleteAction", AnimatedWidget.class)));
        for (AnimatedWidget control : widgets(screen, "formControls")) {
            assertTrue(control.getHeight() == 18 || control.getHeight() == 20);
        }
    }

    @Test
    void embeddedDropdownCommitsTheRenderedSelectionAndKeepsTheNumericValue() throws Exception {
        TestStudioScreen host = new TestStudioScreen();
        host.resize(900, 420);
        JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer", "", null);
        ScreenBackedStudioView view = (ScreenBackedStudioView) AutomationDefinitionDesignerScreen.designer(host, "",
            AutomationDefinitionDraft.TIMER, "timer", timer);
        AutomationDefinitionDesignerScreen screen = (AutomationDefinitionDesignerScreen) view.screen();
        screens.add(screen);
        view.init();
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "defaultDuration", "2.5000");
        @SuppressWarnings("unchecked")
        DropDownWidget<String> dropdown = (DropDownWidget<String>) value(screen, "fieldControls", Map.class)
            .get("defaultUnit");
        view.render(new TestDrawContext(), 0, 0, 0.0F);

        assertTrue(view.mouseClicked(mouse(view, dropdown.getX() + 4, dropdown.getY() + 4)));
        assertTrue(dropdown.isExpanded());
        set(dropdown, "dropdownAnimationProgress", 1.0F);
        int minutes = dropdown.getItems().indexOf("minutes");
        int itemX = dropdown.getX() + 6;
        int itemY = dropdown.getY() + dropdown.getHeight() + minutes * 14 + 7;
        screen.renderOverlayPass(new TestDrawContext(), itemX, itemY, 0.0F);

        assertTrue(view.mouseClicked(mouse(view, itemX, itemY)));
        assertEquals("minutes", dropdown.getSelectedItem());
        assertEquals("minutes", values(screen, "rawValues").get("defaultUnit"));
        assertEquals("2.5000", values(screen, "rawValues").get("defaultDuration"));
        assertEquals("2.5000", value(screen, "document", JsonObject.class).get("defaultDuration").toString());
    }

    private static boolean defaultControlHeight(AnimatedWidget widget) {
        return widget.getHeight() == 18 || widget.getHeight() == 20;
    }

    @Test
    void designerOwnsLuckPermsStyleNavigationAndAnEditableWorkspace() throws Exception {
        TestStudioScreen owner = new TestStudioScreen();
        owner.resize(1200, 700);
        JsonObject resource = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer", "", null);
        ReSyncStudioView view = AutomationDefinitionDesignerScreen.designer(owner, "",
            AutomationDefinitionDraft.TIMER, "timer", resource);
        assertTrue(view instanceof ScreenBackedStudioView);
        ScreenBackedStudioView screenView = (ScreenBackedStudioView) view;
        AutomationDefinitionDesignerScreen designer = (AutomationDefinitionDesignerScreen) screenView.screen();

        view.init();
        SidePanel navigation = value(designer, "navigation", SidePanel.class);
        Container workspace = value(designer, "workspace", Container.class);
        assertFalse(view.hasPanel());
        assertFalse(workspace.getWidgets().isEmpty());
        assertTrue(workspace.getWidgets().stream().allMatch(Setting.class::isInstance));
        assertTrue(workspace.getWidgets().stream().map(Setting.class::cast).flatMap(setting -> setting.getRows().stream())
            .allMatch(PopupWidget.PopupRow::fillsWidth));
        assertTrue(navigation.getDesiredWidth() >= 210);
        Map<?, ?> definitionGroups = value(designer, "definitionGroups", Map.class);
        assertEquals(Set.of(AutomationDefinitionDraft.VARIABLE, AutomationDefinitionDraft.TIMER,
            AutomationDefinitionDraft.SCHEDULE), definitionGroups.keySet());
        assertTrue(navigation.container().getWidgets().containsAll(definitionGroups.values()));
        assertTrue(definitionGroups.values().stream().map(Setting.class::cast)
            .flatMap(setting -> setting.getRows().stream()).allMatch(PopupWidget.PopupRow::fillsWidth));
        assertEquals(3, view.headerButtons().size());
        AnimatedWidget add = value(designer, "addAction", AnimatedWidget.class);
        AnimatedWidget delete = value(designer, "deleteAction", AnimatedWidget.class);
        AnimatedWidget save = value(designer, "primaryAction", AnimatedWidget.class);
        assertEquals(List.of(save, delete, add), view.headerButtons());
        assertTrue(add.getX() < delete.getX());
        assertTrue(delete.getX() < save.getX());
        TestDrawContext context = new TestDrawContext();
        view.render(context, 0, 0, 0.0F);
        assertTrue(designer.widgets.contains(workspace));
        assertTrue(designer.widgets.contains(navigation.container()));
        view.selected();
        assertTrue(owner.contentBrowserHidden());
        view.deselected();
        assertFalse(owner.contentBrowserHidden());
        view.selected();
        int resizedWidth = navigation.getDesiredWidth() + 40;
        navigation.width(resizedWidth);
        invoke(designer, "layoutDesigner", new Class<?>[0]);
        assertEquals(resizedWidth, navigation.getDesiredWidth());
        assertEquals(resizedWidth + 8, workspace.getX());
        assertEquals(designer.width - workspace.getX() - 5, workspace.getWidth());
        assertFalse(view.hasUnsavedChanges());

        invoke(designer, "updateField", new Class<?>[]{String.class, String.class}, "name", "Changed Timer");
        assertTrue(view.hasUnsavedChanges());
        assertEquals("Changed Timer", values(designer, "rawValues").get("name"));
        view.discardUnsavedChanges();
        assertFalse(view.hasUnsavedChanges());
        view.closed();
        assertFalse(owner.contentBrowserHidden());
        assertTrue(value(designer, "disposed", Boolean.class));
    }

    @Test
    void sharedAutomationNavigationSwitchesInPlaceAndRetainsDrafts() throws Exception {
        TestStudioScreen owner = new TestStudioScreen();
        owner.openDefinitions(AutomationDefinitionDraft.VARIABLE);
        ReSyncStudioView view = owner.activeView();
        ScreenBackedStudioView screenView = (ScreenBackedStudioView) view;
        AutomationDefinitionDesignerScreen designer = (AutomationDefinitionDesignerScreen) screenView.screen();
        screens.add(designer);

        openNew(designer, "");
        Map<?, ?> definitionGroups = value(designer, "definitionGroups", Map.class);
        List<?> categoryPopups = List.copyOf(definitionGroups.values());
        Setting variables = (Setting) definitionGroups.get(AutomationDefinitionDraft.VARIABLE);
        variables.collapse(true);
        invoke(designer, "updateField", new Class<?>[]{String.class, String.class}, "name", "Unsaved Value");
        designer.showType(AutomationDefinitionDraft.SCHEDULE);

        assertSame(view, owner.activeView());
        assertSame(designer, ((ScreenBackedStudioView) owner.activeView()).screen());
        assertEquals(AutomationDefinitionDraft.SCHEDULE, value(designer, "type", String.class));
        assertEquals(categoryPopups, List.copyOf(value(designer, "definitionGroups", Map.class).values()));
        assertTrue(variables.isCollapsed);
        designer.showType(AutomationDefinitionDraft.VARIABLE);
        assertSame(view, owner.activeView());
        assertEquals(AutomationDefinitionDraft.VARIABLE, value(designer, "type", String.class));
        assertEquals(categoryPopups, List.copyOf(value(designer, "definitionGroups", Map.class).values()));
        assertTrue(variables.isCollapsed);
        assertEquals("Unsaved Value", values(designer, "rawValues").get("name"));
        assertTrue(designer.hasUnsavedChanges());
    }

    @Test
    void pendingOperationsRecoverAcrossRemovalWithoutEnablingDuplicates() throws Exception {
        AtomicReference<ReSyncResourceCreator.Result> completed = new AtomicReference<>();
        AutomationDefinitionDesignerScreen creation = designer(AutomationDefinitionDraft.VARIABLE, completed::set);
        creation.resize(900, 420);
        creation.init();
        openNew(creation, "");
        Object pendingCreation = installPendingCreation(creation, "queued");
        creation.removed();
        ReSyncResourceCreator.Result created = new ReSyncResourceCreator.Result(
            AutomationDefinitionDraft.VARIABLE, "queued", new JsonObject());
        acceptCreated(creation, pendingCreation, created);
        assertNull(value(creation, "pendingCreatedId", String.class));
        creation.init();
        assertEquals("queued", value(creation, "pendingCreatedId", String.class));
        assertSame(pendingCreation, value(creation, "pendingCreation", Object.class));
        assertSame(created, completed.get());

        AutomationDefinitionDesignerScreen save = designer(AutomationDefinitionDraft.TIMER, null);
        save.resize(900, 420);
        save.init();
        openNew(save, "");
        VersionedEditorDraft<?> savingDraft = value(save, "draft", VersionedEditorDraft.class);
        set(save, "savePending", true);
        save.removed();
        save.init();
        assertSame(savingDraft, value(save, "draft", VersionedEditorDraft.class));
        assertTrue(value(save, "savePending", Boolean.class));

        AutomationDefinitionDesignerScreen failedSave = designer(AutomationDefinitionDraft.TIMER, null);
        failedSave.resize(900, 420);
        failedSave.init();
        openNew(failedSave, "");
        set(failedSave, "saveSequence", 4L);
        set(failedSave, "savePending", true);
        failedSave.removed();
        finishSave(failedSave, 4L, 0L, false, false);
        failedSave.init();
        assertFalse(value(failedSave, "savePending", Boolean.class));

        AutomationDefinitionDesignerScreen failedCreation = designer(AutomationDefinitionDraft.VARIABLE, null);
        failedCreation.resize(900, 420);
        failedCreation.init();
        openNew(failedCreation, "");
        Object rejectedCreation = installPendingCreation(failedCreation, "rejected");
        failedCreation.removed();
        finishCreationDurability(failedCreation, rejectedCreation, null, new IllegalStateException("offline"));
        failedCreation.init();
        assertFalse(value(failedCreation, "creationPending", Boolean.class));

        AutomationDefinitionDesignerScreen delete = designer(AutomationDefinitionDraft.TIMER, null);
        delete.resize(900, 420);
        delete.init();
        openNew(delete, "");
        set(delete, "creating", false);
        set(delete, "activeId", "removed");
        set(delete, "deleteSequence", 3L);
        set(delete, "deletePending", true);
        delete.removed();
        FlowManager manager = FlowManager.getInstance();
        invoke(delete, "finishDelete", new Class<?>[]{long.class, long.class, FlowManager.class, String.class,
            FlowManager.ResourceDeleteResult.class, Throwable.class}, 3L, 1L, manager, "removed",
            new FlowManager.ResourceDeleteResult(AutomationDefinitionDraft.TIMER, "removed", true, ""), null);
        assertFalse(value(delete, "deletePending", Boolean.class));
        assertNull(value(delete, "document", JsonObject.class));
    }

    @Test
    void prepareAndPublishWaitForCompleteMembershipAndHydratedDefinitions() throws Exception {
        String serverId = "designer-scan";
        FlowManager manager = connectedManager(serverId);
        try {
            new NodeRegistry();
            AutomationDefinitionDesignerScreen screen = designer(serverId, AutomationDefinitionDraft.TIMER, null);

            prepareAndPublish(screen);
            assertFalse(value(screen, "loaded", Boolean.class));
            assertTrue(value(screen, "listDetail", String.class).contains("Complete Timer List"));

            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.TIMER_DEFINITION, List.of("timer"));
            prepareAndPublish(screen);
            assertFalse(value(screen, "loaded", Boolean.class));
            assertTrue(value(screen, "listDetail", String.class).contains("timer"));

            JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer", "", null);
            manager.cacheJsonResource(serverId, ReSyncResourceType.TIMER_DEFINITION, timer);
            prepareAndPublish(screen);
            assertTrue(value(screen, "loaded", Boolean.class));
            assertEquals(1, value(screen, "items", List.class).size());

            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.SCHEDULE_DEFINITION,
                List.of("schedule"));
            JsonObject schedule = AutomationDefinitionDraft.create(AutomationDefinitionDraft.SCHEDULE, "schedule",
                "", new AutomationDefinitionDraft.Target("function", "target"));
            manager.cacheJsonResource(serverId, ReSyncResourceType.SCHEDULE_DEFINITION, schedule);
            AutomationDefinitionDesignerScreen scheduleScreen = designer(serverId,
                AutomationDefinitionDraft.SCHEDULE, null);
            prepareAndPublish(scheduleScreen);
            assertTrue(value(scheduleScreen, "loaded", Boolean.class));
            assertEquals(1, value(scheduleScreen, "items", List.class).size());
            assertNull(value(scheduleScreen, "typeCatalog", Object.class));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void capabilityPublicationRefreshesTheOpenEditorWithoutReplacingDirtyInput() throws Exception {
        String serverId = ServerId.deterministic("automation-capability-publication").canonicalText();
        FlowManager manager = connectedManager(serverId);
        try {
            NodeRegistry registry = new NodeRegistry();
            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.TIMER_DEFINITION, List.of("timer"));
            JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer", "", null);
            manager.cacheJsonResource(serverId, ReSyncResourceType.TIMER_DEFINITION, timer);
            AutomationDefinitionDesignerScreen screen = designer(serverId, AutomationDefinitionDraft.TIMER, null);
            mountWithoutRefresh(screen);
            openNew(screen, "");
            invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "id", "timer");
            invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "name", "Unsaved Timer");
            set(screen, "creating", false);
            set(screen, "activeId", "timer");
            TextInputWidget input = textInput(screen, "Name");
            input.setText("Unsaved Timer");
            input.applyCollaborationState(new TextInputWidget.CollaborationState("Unsaved Timer", 7, 2, 7, 3));
            TextInputWidget.CollaborationState textState =
                (TextInputWidget.CollaborationState) input.captureCollaborationState();

            prepareAndPublish(screen);
            ManagedResourceEditorModel unavailable = value(screen, "editorModel", ManagedResourceEditorModel.class);
            assertTrue(unavailable.readOnly());

            applyAvailableMetadata(registry, serverId, AutomationDefinitionDraft.TIMER);
            manager.cacheServerCapabilities(serverId, availableCapabilities(AutomationDefinitionDraft.TIMER));

            prepareAndPublish(screen);
            ManagedResourceEditorModel available = value(screen, "editorModel", ManagedResourceEditorModel.class);
            assertNotSame(unavailable, available);
            assertFalse(available.readOnly());
            assertSame(input, textInput(screen, "Name"));
            assertEquals(textState, input.captureCollaborationState());
            assertEquals("Unsaved Timer", values(screen, "rawValues").get("name"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void variableListRemainsReadOnlyWithoutTypedCatalogAuthority() throws Exception {
        String serverId = ServerId.deterministic("automation-variable-without-canonical-types").canonicalText();
        FlowManager manager = connectedManager(serverId);
        try {
            NodeRegistry registry = new NodeRegistry();
            applyAvailableMetadata(registry, serverId, AutomationDefinitionDraft.VARIABLE);
            manager.cacheServerCapabilities(serverId, availableCapabilities(AutomationDefinitionDraft.VARIABLE));
            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.VARIABLE_DEFINITION,
                List.of("variable"));
            JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "variable",
                "", null);
            variable.addProperty("valueType", "extension:future");
            manager.cacheJsonResource(serverId, ReSyncResourceType.VARIABLE_DEFINITION, variable);
            AutomationDefinitionDesignerScreen screen = designer(serverId, AutomationDefinitionDraft.VARIABLE,
                null);

            prepareAndPublish(screen);

            assertTrue(value(screen, "loaded", Boolean.class));
            List<?> items = value(screen, "items", List.class);
            assertEquals(1, items.size());
            assertEquals("", value(items.getFirst(), "diagnostic", String.class));
            assertNull(value(screen, "typeCatalog", Object.class));
            assertNull(invoke(screen, "currentTypeCatalog", new Class<?>[0]));
            Object item = items.getFirst();
            invoke(screen, "openItem", new Class<?>[]{item.getClass(), boolean.class}, item, false);
            Object validation = invoke(screen, "validateCurrent", new Class<?>[0]);
            assertEquals("Value Types Are Still Loading", value(validation, "message", String.class));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void pendingScanCannotPublishAfterItsMissingResourceArrives() throws Exception {
        String serverId = "designer-stale-scan";
        FlowManager manager = connectedManager(serverId);
        try {
            new NodeRegistry();
            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.TIMER_DEFINITION, List.of("timer"));
            AutomationDefinitionDesignerScreen screen = designer(serverId, AutomationDefinitionDraft.TIMER, null);
            long generation = prepare(screen);
            JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer", "", null);
            manager.cacheJsonResource(serverId, ReSyncResourceType.TIMER_DEFINITION, timer);

            publishQueued(screen, generation);
            assertFalse(value(screen, "loaded", Boolean.class));
            assertEquals("Reading Server Resources", value(screen, "listDetail", String.class));

            prepareAndPublish(screen);
            assertTrue(value(screen, "loaded", Boolean.class));
            assertEquals(1, value(screen, "items", List.class).size());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void preparedScanCannotPublishAfterTheDisplayLifecycleEnds() throws Exception {
        String serverId = "designer-stale-lifecycle";
        FlowManager manager = connectedManager(serverId);
        try {
            new NodeRegistry();
            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.TIMER_DEFINITION, List.of("timer"));
            JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer", "", null);
            manager.cacheJsonResource(serverId, ReSyncResourceType.TIMER_DEFINITION, timer);
            AutomationDefinitionDesignerScreen screen = designer(serverId, AutomationDefinitionDraft.TIMER, null);
            prepare(screen);

            screen.removed();
            drainScreenTasks();

            assertFalse(value(screen, "loaded", Boolean.class));
            assertTrue(value(screen, "items", List.class).isEmpty());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void saveAcknowledgementSettlesAfterReinitializationWithoutRunningTheStaleContinuation() throws Exception {
        AutomationDefinitionDesignerScreen screen = designer(AutomationDefinitionDraft.TIMER, null);
        screen.resize(900, 420);
        screen.init();
        openNew(screen, "");
        invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "id", "timer");
        set(screen, "creating", false);
        set(screen, "activeId", "timer");
        AtomicBoolean continued = new AtomicBoolean();
        FlowManager.ResourceReadLease lease = readLease("", AutomationDefinitionDraft.TIMER, "timer",
            () -> true, () -> valueUnchecked(screen, "document", JsonObject.class).toString());
        long lifecycle = value(screen, "lifecycle", Long.class);
        Object acknowledgement = saveAcknowledgement(7L, lifecycle, lease,
            new BrowserSafeState.ReferenceValue<>(value(screen, "document", JsonObject.class).toString()),
            Map.copyOf(values(screen, "rawValues")), () -> continued.set(true));
        set(screen, "saveSequence", 7L);
        set(screen, "savePending", true);
        set(screen, "saveAcknowledgement", acknowledgement);

        screen.removed();
        invoke(screen, "finishSaveAcknowledgement", new Class<?>[0]);
        assertSame(acknowledgement, value(screen, "saveAcknowledgement", Object.class));

        screen.init();
        invoke(screen, "finishSaveAcknowledgement", new Class<?>[0]);
        assertNull(value(screen, "saveAcknowledgement", Object.class));
        assertFalse(value(screen, "savePending", Boolean.class));
        assertFalse(continued.get());
    }

    @Test
    void tickSettlesCapturedSaveAcknowledgementAfterReinitialization() throws Exception {
        String serverId = ServerId.deterministic("automation-captured-save-acknowledgement").canonicalText();
        FlowManager manager = connectedManager(serverId);
        DesignerSaveNotifications.SaveTicket ticket = null;
        try {
            new NodeRegistry();
            manager.applyServerJsonResourceList(serverId, ReSyncResourceType.TIMER_DEFINITION, List.of("timer"));
            JsonObject authoritative = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer",
                "", null);
            authoritative.addProperty("name", "Authoritative Timer");
            manager.cacheJsonResource(serverId, ReSyncResourceType.TIMER_DEFINITION, authoritative);
            AutomationDefinitionDesignerScreen screen = designer(serverId, AutomationDefinitionDraft.TIMER, null);
            mountWithoutRefresh(screen);
            openNew(screen, "");
            invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "id", "timer");
            invoke(screen, "updateField", new Class<?>[]{String.class, String.class}, "name", "Submitted Timer");
            set(screen, "creating", false);
            set(screen, "activeId", "timer");

            ticket = DesignerSaveNotifications.startExact(serverId, ReSyncResourceType.TIMER_DEFINITION, "timer",
                "Timer");
            assertTrue(ticket != null);
            VersionedEditorDraft<?> draft = value(screen, "draft", VersionedEditorDraft.class);
            CountDownLatch captured = new CountDownLatch(1);
            assertTrue(draft.capture(AutomationDefinitionDraft.TIMER, "timer", ticket,
                snapshot -> captured.countDown()));
            assertTrue(captured.await(2L, TimeUnit.SECONDS));

            long lifecycle = value(screen, "lifecycle", Long.class);
            AtomicBoolean continued = new AtomicBoolean();
            set(screen, "saveSequence", 1L);
            set(screen, "savePending", true);
            finishSave(screen, 1L, lifecycle, ticket, true, true,
                Map.copyOf(values(screen, "rawValues")), () -> continued.set(true));
            assertFalse(draft.isSettled());
            assertTrue(value(screen, "saveAcknowledgement", Object.class) != null);

            screen.removed();
            screen.init();
            value(screen, "worker", AsyncTaskWorker.class).close();
            value(screen, "refreshGate", AutomationDefinitionDesignerScreen.RefreshGate.class).close();
            drainScreenTasks();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (value(screen, "savePending", Boolean.class) && System.nanoTime() < deadline) {
                screen.tick();
                Thread.onSpinWait();
            }

            assertTrue(draft.isSettled());
            assertNull(value(screen, "saveAcknowledgement", Object.class));
            assertFalse(value(screen, "savePending", Boolean.class));
            assertEquals("Authoritative Timer",
                value(screen, "baselineDocument", JsonObject.class).get("name").getAsString());
            assertEquals("Authoritative Timer", values(screen, "rawValues").get("name"));
            assertTrue(value(screen, "activeLease", FlowManager.ResourceReadLease.class).isCurrent());
            assertFalse(continued.get());
        } finally {
            DesignerSaveNotifications.failExact(ticket, "Test Complete");
            manager.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AnimatedWidget> widgets(Object target, String name) throws Exception {
        return (List<AnimatedWidget>) value(target, name, List.class);
    }

    private static TextInputWidget textInput(AutomationDefinitionDesignerScreen screen, String placeholder)
        throws Exception {
        return widgets(screen, "formControls").stream().filter(TextInputWidget.class::isInstance)
            .map(TextInputWidget.class::cast).filter(input -> placeholder.equals(input.placeholder)).findFirst()
            .orElseThrow();
    }

    private static void mountWithoutRefresh(AutomationDefinitionDesignerScreen screen) throws Exception {
        screen.resize(900, 420);
        screen.init();
        value(screen, "worker", AsyncTaskWorker.class).close();
        value(screen, "refreshGate", AutomationDefinitionDesignerScreen.RefreshGate.class).close();
        drainScreenTasks();
    }

    private AutomationDefinitionDesignerScreen designer(String type, Consumer<ReSyncResourceCreator.Result> completion)
        throws Exception {
        return designer(null, "", type, completion);
    }

    private AutomationDefinitionDesignerScreen designer(String serverId, String type,
                                                         Consumer<ReSyncResourceCreator.Result> completion)
        throws Exception {
        return designer(null, serverId, type, completion);
    }

    private AutomationDefinitionDesignerScreen designer(Screen parent, String type,
                                                         Consumer<ReSyncResourceCreator.Result> completion)
        throws Exception {
        return designer(parent, "", type, completion);
    }

    private AutomationDefinitionDesignerScreen designer(Screen parent, String serverId, String type,
                                                         Consumer<ReSyncResourceCreator.Result> completion)
        throws Exception {
        TestStudioScreen host = new TestStudioScreen();
        host.resize(900, 420);
        Screen owner = parent != null ? parent : host;
        Constructor<AutomationDefinitionDesignerScreen> constructor = AutomationDefinitionDesignerScreen.class
            .getDeclaredConstructor(Screen.class, String.class, String.class, String.class, String.class,
                Consumer.class, boolean.class);
        constructor.setAccessible(true);
        AutomationDefinitionDesignerScreen screen = constructor.newInstance(owner, serverId, type, null, "",
            completion, false);
        screen.configurePanel(new StudioPanel(host, "automation-test-" + screens.size()));
        screens.add(screen);
        return screen;
    }

    private FlowManager connectedManager(String serverId) throws Exception {
        TestTransport transport = new TestTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, transport, null);
        FlowManager manager = new FlowManager(null, null);
        managers.add(manager);
        Class<?> connection = Class.forName("redxax.oxy.remotely.data.flow.FlowManagerTestConnection");
        Method install = connection.getDeclaredMethod("install", FlowManager.class, String.class,
            ReSyncFlowClient.class, ReSyncFrameTransport.class);
        install.setAccessible(true);
        install.invoke(null, manager, serverId, client, transport);
        return manager;
    }

    private static void prepareAndPublish(AutomationDefinitionDesignerScreen screen) throws Exception {
        publishQueued(screen, prepare(screen));
    }

    private static long prepare(AutomationDefinitionDesignerScreen screen) throws Exception {
        AutomationDefinitionDesignerScreen.RefreshGate gate = value(screen, "refreshGate",
            AutomationDefinitionDesignerScreen.RefreshGate.class);
        long generation = gate.request();
        assertTrue(generation > 0L);
        invoke(screen, "prepare", new Class<?>[]{long.class, long.class}, generation,
            value(screen, "lifecycle", Long.class));
        return generation;
    }

    private static void publishQueued(AutomationDefinitionDesignerScreen screen, long generation) throws Exception {
        drainScreenTasks();
        AutomationDefinitionDesignerScreen.RefreshGate gate = value(screen, "refreshGate",
            AutomationDefinitionDesignerScreen.RefreshGate.class);
        assertFalse(gate.continueAfter(generation));
    }

    private static void drainScreenTasks() {
        for (int attempt = 0; attempt < 8; attempt++) {
            ScreenManager.getInstance().processTasks();
        }
    }

    private static FlowResourceMetadata availableMetadata(String type) {
        FlowResourceMetadata metadata = new FlowResourceMetadata();
        metadata.setTypeId(type);
        metadata.setDisplayName(type);
        metadata.setOwner("builtin");
        metadata.setAvailable(true);
        metadata.setOperations(List.of("discover", "get", "save", "update", "delete"));
        return metadata;
    }

    private static JsonObject availableCapabilities(String type) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("schemaVersion", 2);
        descriptor.addProperty("typeId", type);
        descriptor.addProperty("displayName", type);
        descriptor.addProperty("owner", "builtin");
        descriptor.addProperty("available", true);
        JsonArray operations = new JsonArray();
        for (String operation : List.of("discover", "get", "save", "update", "delete")) {
            operations.add(operation);
        }
        descriptor.add("operations", operations);
        descriptor.add("operationAvailability", new JsonObject());
        JsonArray descriptors = new JsonArray();
        descriptors.add(descriptor);
        JsonObject managedResources = new JsonObject();
        managedResources.addProperty("version", 1);
        managedResources.add("descriptors", descriptors);
        JsonObject capabilities = new JsonObject();
        capabilities.add("managedResources", managedResources);
        return capabilities;
    }

    private static void applyAvailableMetadata(NodeRegistry registry, String serverId, String type) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.version());
        snapshot.setMinimumClientContractVersion(ReSyncProtocolContract.FLOW_CONTRACT.minimumClientVersion());
        snapshot.setCapabilities(ReSyncProtocolContract.FLOW_CONTRACT.requiredCapabilities());
        snapshot.setFullSync(true);
        snapshot.setResourceMetadata(List.of(availableMetadata(type)));
        assertTrue(registry.applySnapshot(serverId, snapshot));
    }

    private static FlowManager.ResourceReadLease readLease(String serverId, String type, String id,
                                                           BooleanSupplier current, Supplier<String> materializer)
        throws Exception {
        Constructor<FlowManager.ResourceReadLease> constructor = FlowManager.ResourceReadLease.class
            .getDeclaredConstructor(String.class, String.class, String.class, String.class, BooleanSupplier.class,
                Supplier.class, Function.class);
        constructor.setAccessible(true);
        return constructor.newInstance(serverId, type, id, "", current, materializer, null);
    }

    private static Object saveAcknowledgement(long sequence, long lifecycle, FlowManager.ResourceReadLease lease,
                                              BrowserSafeState.ReferenceValue<String> authoritative,
                                              Map<String, String> submittedValues, Runnable afterSave)
        throws Exception {
        Class<?> type = Class.forName(AutomationDefinitionDesignerScreen.class.getName() + "$SaveAcknowledgement");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, long.class,
            FlowManager.ResourceReadLease.class, BrowserSafeState.ReferenceValue.class, Map.class, Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(sequence, lifecycle, lease, authoritative, submittedValues, afterSave);
    }

    private static void openNew(AutomationDefinitionDesignerScreen screen, String folder) throws Exception {
        invoke(screen, "openNew", new Class<?>[]{String.class, boolean.class}, folder, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Runnable> takeScreenTasks() throws Exception {
        Field field = ScreenManager.class.getDeclaredField("taskQueue");
        field.setAccessible(true);
        Queue<Runnable> tasks = (Queue<Runnable>) field.get(ScreenManager.getInstance());
        synchronized (tasks) {
            List<Runnable> snapshot = List.copyOf(tasks);
            tasks.clear();
            return snapshot;
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreScreenTasks(List<Runnable> snapshot) throws Exception {
        Field field = ScreenManager.class.getDeclaredField("taskQueue");
        field.setAccessible(true);
        Queue<Runnable> tasks = (Queue<Runnable>) field.get(ScreenManager.getInstance());
        synchronized (tasks) {
            tasks.clear();
            if (snapshot != null) {
                tasks.addAll(snapshot);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreFlowManager(FlowManager manager) throws Exception {
        Field field = FlowManager.class.getDeclaredField("INSTANCE");
        field.setAccessible(true);
        ((BrowserSafeState.ReferenceValue<FlowManager>) field.get(null)).set(manager);
    }

    private static void restoreNodeRegistry(NodeRegistry registry) throws Exception {
        Field field = NodeRegistry.class.getDeclaredField("INSTANCE");
        field.setAccessible(true);
        field.set(null, registry);
    }

    private static void finishSave(AutomationDefinitionDesignerScreen screen, long sequence, long lifecycle,
                                   boolean saved, boolean current) throws Exception {
        finishSave(screen, sequence, lifecycle, null, saved, current, Map.of(), null);
    }

    private static void finishSave(AutomationDefinitionDesignerScreen screen, long sequence, long lifecycle,
                                   DesignerSaveNotifications.SaveTicket ticket, boolean saved, boolean current,
                                   Map<String, String> submittedValues, Runnable afterSave) throws Exception {
        invoke(screen, "finishSave", new Class<?>[]{long.class, long.class,
            DesignerSaveNotifications.SaveTicket.class, boolean.class, boolean.class, Map.class, Runnable.class},
            sequence, lifecycle, ticket, saved, current, submittedValues, afterSave);
    }

    private static Object installPendingCreation(AutomationDefinitionDesignerScreen screen, String id)
        throws Exception {
        JsonObject document = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, id, "", null);
        AutomationDefinitionDraft.Prepared prepared = AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, id, document);
        Class<?> type = Class.forName(AutomationDefinitionDesignerScreen.class.getName() + "$PendingCreation");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, String.class,
            AutomationDefinitionDraft.Prepared.class);
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(value(screen, "lifecycle", Long.class), id, prepared);
        set(screen, "pendingCreation", pending);
        set(screen, "creationPending", true);
        set(screen, "creationQueued", false);
        return pending;
    }

    private static void finishCreationDurability(AutomationDefinitionDesignerScreen screen, Object pending,
                                                 Boolean accepted, Throwable failure) throws Exception {
        invoke(screen, "finishCreationDurability",
            new Class<?>[]{pending.getClass(), Boolean.class, Throwable.class}, pending, accepted, failure);
    }

    private static void acceptCreated(AutomationDefinitionDesignerScreen screen, Object pending,
                                      ReSyncResourceCreator.Result result) throws Exception {
        invoke(screen, "acceptCreated", new Class<?>[]{pending.getClass(), ReSyncResourceCreator.Result.class},
            pending, result);
    }

    private static boolean invokeBoolean(Object target, String name) throws Exception {
        return invokeValue(target, name, Boolean.class);
    }

    private static <T> T invokeValue(Object target, String name, Class<T> type) throws Exception {
        return type.cast(invoke(target, name, new Class<?>[0]));
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments)
        throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static ReMouseEvent mouse(Object source, double x, double y) {
        return new ReMouseEvent(source, source, 0L, ReModifierState.none(), ReMouseEvent.Action.PRESSED, x, y,
            0, 0, ReMouseButton.LEFT, 0, 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> values(Object target, String name) throws Exception {
        return (Map<String, String>) value(target, name, Map.class);
    }

    private static <T> T value(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static <T> T valueUnchecked(Object target, String name, Class<T> type) {
        try {
            return value(target, name, type);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static String label(DropDownWidget<String> dropdown, String value) throws Exception {
        Field field = DropDownWidget.class.getDeclaredField("displayFunction");
        field.setAccessible(true);
        return ((Function<String, String>) field.get(dropdown)).apply(value);
    }

    private static final class TestStudioScreen extends StudioScreen {
        private final String serverId;
        private boolean contentBrowserHidden;

        private TestStudioScreen() {
            this("");
        }

        private TestStudioScreen(String serverId) {
            this.serverId = serverId;
        }

        @Override
        protected String studioServerId() {
            return serverId;
        }

        @Override
        public void setStudioContentBrowserTemporarilyHidden(boolean hidden) {
            contentBrowserHidden = hidden;
        }

        private boolean contentBrowserHidden() {
            return contentBrowserHidden;
        }

        private ReSyncStudioView activeView() {
            return activeStudioDocument != null ? activeStudioDocument.view() : null;
        }

        private int documentCount() {
            return studioDocuments.size();
        }

        private String activeTitle() {
            return activeStudioDocument != null ? activeStudioDocument.title() : "";
        }
    }

    private static final class TestTransport implements ReSyncFrameTransport {
        private boolean open = true;
        private Runnable closeHandler;

        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
            closeHandler = handler;
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
            open = false;
            if (closeHandler != null) {
                closeHandler.run();
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
