package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.flow.AutomationDefinitionDraft;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ManagedResourceCatalog;
import redxax.oxy.remotely.data.flow.ManagedResourceEditorModel;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.ReSyncValueTypeCatalog;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.ui.studio.ReSyncResourceCreator;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioView;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.flow.ui.studio.StudioSaveProvider;
import redxax.oxy.remotely.flow.ui.studio.StudioSelectorView;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.identity.ServerId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class AutomationDefinitionDesignerScreen extends ReScreen implements ReSyncStudioView, StudioSaveProvider, StudioSelectorView {
    private static final Gson GSON = new Gson();
    private static final long REFRESH_INTERVAL = 2000L;
    private static final Set<String> TARGET_TYPES = Set.of("flow", "function", "command");
    private static final List<String> SUBRESOURCE_TYPES = List.of(AutomationDefinitionDraft.VARIABLE,
        AutomationDefinitionDraft.TIMER, AutomationDefinitionDraft.SCHEDULE, AutomationDefinitionDraft.COMPONENT_BUILDER);
    private static final int HEADER_HEIGHT = 36;
    private static final int NAVIGATION_WIDTH = 258;
    private static final int CONTROL_WIDTH = 150;

    private final Screen parent;
    private final String serverId;
    private String type;
    private String requestedFolder;
    private Consumer<ReSyncResourceCreator.Result> creationCompletion;
    private final boolean createOnOpen;
    private final JsonObject initialResource;
    private AsyncTaskWorker worker;
    private final RefreshGate refreshGate = new RefreshGate();
    private final Map<String, String> rawValues = new LinkedHashMap<>();
    private final Map<String, String> baselineValues = new LinkedHashMap<>();
    private final Map<String, FieldRow> fieldRows = new LinkedHashMap<>();
    private final Map<String, AnimatedWidget> fieldControls = new LinkedHashMap<>();
    private final List<AnimatedWidget> formControls = new ArrayList<>();
    private final Map<ContextKey, EditorState> retainedEditors = new LinkedHashMap<>();
    private final Map<String, ContextKey> lastEditorByType = new LinkedHashMap<>();
    private List<Item> items = List.of();
    private Map<String, List<Item>> itemsByType = Map.of();
    private FlowManager.TypedResourceMembershipSnapshot membership;
    private ReSyncValueTypeCatalog.Snapshot typeCatalog;
    private ManagedResourceCatalog.Descriptor descriptor;
    private ManagedResourceEditorModel editorModel;
    private FlowManager.ResourceReadLease activeLease;
    private VersionedEditorDraft<JsonObject> draft;
    private JsonObject document;
    private JsonObject baselineDocument;
    private Container workspace;
    private SidePanel navigation;
    private TextInputWidget navigationSearch;
    private final Map<String, Setting> definitionGroups = new LinkedHashMap<>();
    private final Map<ContextKey, NavigationEntryWidget> definitionEntries = new LinkedHashMap<>();
    private String navigationFilter = "";
    private boolean contentBrowserHidden;
    private MountableButtonWidget validationRow;
    private DropDownWidget<String> targetIdInput;
    private AnimatedButton valueTypeBrowse;
    private ItemSelectorWidget valueTypeSelector;
    private AnimatedWidget primaryAction;
    private AnimatedWidget addAction;
    private AnimatedWidget deleteAction;
    private Setting validationSetting;
    private PendingCreation pendingCreation;
    private ReSyncResourceCreator.Result completedCreation;
    private SaveAcknowledgement saveAcknowledgement;
    private String activeId = "";
    private String initialSelectedId;
    private String pendingCreatedId;
    private String folder = "";
    private String listMessage = "Loading Definitions";
    private String listDetail = "Reading Server Resources";
    private FlowManager demandedManager;
    private long demandedConnectionGeneration = Long.MIN_VALUE;
    private FlowManager demandedCatalogManager;
    private long demandedCatalogGeneration = Long.MIN_VALUE;
    private long lifecycle = 1L;
    private long lastRefreshAt;
    private long saveSequence;
    private long deleteSequence;
    private boolean creating;
    private boolean creationPending;
    private boolean creationQueued;
    private boolean savePending;
    private boolean deletePending;
    private boolean loaded;
    private boolean disposed;
    private String mountedFormType;
    private boolean mountedFormCreating;
    private boolean mountedFormHasDocument;
    private ItemComponentEditorPanel componentEditor;

    private AutomationDefinitionDesignerScreen(Screen parent, String serverId, String type, String selectedId,
                                               String folder, Consumer<ReSyncResourceCreator.Result> completion,
                                               boolean createOnOpen) {
        this(parent, serverId, type, selectedId, folder, completion, createOnOpen, null);
    }

    private AutomationDefinitionDesignerScreen(Screen parent, String serverId, String type, String selectedId,
                                               String folder, Consumer<ReSyncResourceCreator.Result> completion,
                                               boolean createOnOpen, JsonObject resource) {
        this.parent = parent;
        this.serverId = serverId == null ? "" : serverId;
        this.type = type == null ? "" : type;
        this.initialSelectedId = selectedId == null || selectedId.isBlank() ? null : selectedId;
        this.requestedFolder = folder == null ? "" : folder;
        this.creationCompletion = completion;
        this.createOnOpen = createOnOpen;
        this.initialResource = resource != null ? resource.deepCopy() : null;
    }

    public static ReSyncStudioView designer(StudioScreen owner, String serverId, String type, String id,
                                            JsonObject resource) {
        AutomationDefinitionDesignerScreen screen = new AutomationDefinitionDesignerScreen(owner, serverId, type, id,
            "", null, false, resource);
        return new ScreenBackedStudioView(owner, screen, false);
    }

    public static ReSyncStudioView creator(StudioScreen owner, String serverId, String type, String folder,
                                           Consumer<ReSyncResourceCreator.Result> completion) {
        AutomationDefinitionDesignerScreen screen = new AutomationDefinitionDesignerScreen(owner, serverId, type,
            null, folder, completion, true, null);
        return new ScreenBackedStudioView(owner, screen, false);
    }

    public static ReSyncStudioView typeScreen(StudioScreen owner, String serverId, String type) {
        AutomationDefinitionDesignerScreen screen = new AutomationDefinitionDesignerScreen(owner, serverId, type,
            null, "", null, false, null);
        return new ScreenBackedStudioView(owner, screen, false);
    }

    public void showType(String requestedType) {
        show(requestedType, null, "", null, false);
    }

    public void showDefinition(String requestedType, String id) {
        if (id != null && !id.isBlank()) {
            show(requestedType, id, "", null, false);
        }
    }

    public void showCreate(String requestedType, String targetFolder,
                           Consumer<ReSyncResourceCreator.Result> completion) {
        show(requestedType, null, targetFolder, completion, true);
    }

    public boolean canCloseStudioDocument() {
        return !savePending && !deletePending && (!creationPending || creationQueued);
    }

    @Override
    public String getDesktopAppId() {
        return "automation-" + type;
    }

    @Override
    public String getDesktopAppTitle() {
        return pluralName(type);
    }

    @Override
    public String getDesktopAppIconPath() {
        return switch (type) {
            case AutomationDefinitionDraft.VARIABLE -> "snippets.png";
            case AutomationDefinitionDraft.TIMER -> "history.png";
            case AutomationDefinitionDraft.SCHEDULE -> "calendar.png";
            case AutomationDefinitionDraft.COMPONENT_BUILDER -> "item.png";
            default -> "resources.png";
        };
    }

    @Override
    public void init() {
        if (!disposed && worker != null) {
            return;
        }
        navigation = null;
        workspace = null;
        super.init();
        initEmbedded();
    }

    private void initEmbedded() {
        disposed = false;
        if (worker != null) {
            worker.close();
        }
        worker = new AsyncTaskWorker(1, 1, 4);
        resetViewReferences();
        buildDesignerChrome();
        if (document == null && initialResource != null) {
            document = initialResource.deepCopy();
            activeId = initialSelectedId != null ? initialSelectedId : text(document, "id");
            baselineDocument = document.deepCopy();
            baselineValues.clear();
            baselineValues.putAll(rawValues(document, false));
            rawValues.clear();
            rawValues.putAll(baselineValues);
            activeLease = snapshotResource(activeId);
            initialSelectedId = null;
            creating = false;
            replaceDraft(document);
        } else if (document != null && !savePending) {
            replaceDraft(document);
        }
        if (createOnOpen && document == null) {
            openNew(requestedFolder, false);
        }
        rebuildNavigation();
        rebuildForm();
        resumeCompletedCreation();
        refresh();
    }

    private void resetViewReferences() {
        fieldRows.clear();
        fieldControls.clear();
        formControls.clear();
        targetIdInput = null;
        valueTypeBrowse = null;
        primaryAction = null;
        addAction = null;
        deleteAction = null;
        validationRow = null;
        validationSetting = null;
        definitionGroups.clear();
        definitionEntries.clear();
        mountedFormType = null;
    }

    private void buildDesignerChrome() {
        buildHeader();
        navigation = createSidePanel("automation-definition-navigation").collapsible("Sub Resources").left().animation(false)
            .y(HEADER_HEIGHT).height(Math.max(100, height - HEADER_HEIGHT - 5)).minWidth(210).maxWidth(380)
            .maxWidthRatio(45).width(NAVIGATION_WIDTH).padding(3).gap(2).scrolling(true).show();
        navigationSearch = new TextInputWidget.Builder().placeholder("Search Sub Resources")
            .size(NAVIGATION_WIDTH - 10, 20).onChange(value -> {
                navigationFilter = normalize(value);
                filterNavigation();
                navigation.container().resetScroll();
            }).build();
        navigation.addWidget(navigationSearch);
        for (String resourceType : SUBRESOURCE_TYPES) {
            Setting group = new Setting.Builder(pluralName(resourceType)).build();
            definitionGroups.put(resourceType, group);
            navigation.addWidget(group);
        }
        int left = navigationWidth();
        workspace = createContainer("automation-definition-workspace", left, HEADER_HEIGHT,
            Math.max(220, width - left - 5), Math.max(100, height - HEADER_HEIGHT - 5));
        workspace.layout(new ManagedLayout()).padding(5).columns(1).verticalSpacing(4).scrolling(true)
            .backgroundDrawing(true);
        setActiveContainer(workspace);
    }

    private void rebuildNavigation() {
        if (definitionGroups.size() != SUBRESOURCE_TYPES.size()) {
            return;
        }
        definitionEntries.clear();
        for (String resourceType : SUBRESOURCE_TYPES) {
            Setting group = definitionGroups.get(resourceType);
            group.clearRows();
            ContextKey creationKey = ContextKey.creation(resourceType);
            NavigationEntryWidget create = new NavigationEntryWidget("New " + singularName(resourceType),
                typeDescription(resourceType), "Create", () -> requestNew(resourceType));
            definitionEntries.put(creationKey, create);
            addRow(group, "create", 30, create);
            List<Item> categoryItems = itemsByType.getOrDefault(resourceType, List.of());
            if (!categoryItems.isEmpty()) {
                for (Item item : categoryItems) {
                    ContextKey key = ContextKey.definition(resourceType, item.id());
                    String detail = definitionDescription(resourceType, item);
                    NavigationEntryWidget entry = new NavigationEntryWidget(item.name(), detail,
                        item.diagnostic().isBlank() ? "" : "Needs Attention",
                        () -> requestOpenItem(resourceType, item));
                    definitionEntries.put(key, entry);
                    addRow(group, "definition:" + item.id(), 30, entry);
                }
            } else {
                boolean complete = completeType(membership, resourceType);
                boolean ready = itemsByType.containsKey(resourceType) && complete;
                String title = ready ? "No " + pluralName(resourceType) : resourceType.equals(type)
                    ? listMessage : "Loading " + pluralName(resourceType);
                String detail = ready ? typeDescription(resourceType) : resourceType.equals(type)
                    ? listDetail : "Reading Server Resources";
                NavigationEntryWidget state = new NavigationEntryWidget(title, detail, "", () -> {});
                state.setActive(false);
                addRow(group, "state", 30, state);
            }
        }
        updateNavigationSelection();
        filterNavigation();
        navigation.container().updateWidgetPositions();
    }

    private void requestOpenItem(String requestedType, Item item) {
        if (item == null || savePending || creationPending || deletePending
            || requestedType.equals(type) && item.id().equals(activeId) && !creating) {
            return;
        }
        showDefinition(requestedType, item.id());
    }

    private void requestNew(String requestedType) {
        if (!AutomationDefinitionDraft.supports(requestedType) || savePending || creationPending || deletePending
            || creating && requestedType.equals(type)) {
            return;
        }
        String targetFolder = requestedType.equals(type) && descriptor != null && !descriptor.defaultFolder().isBlank()
            ? descriptor.defaultFolder() : ReSyncResourceType.defaultFolderFor(requestedType);
        show(requestedType, null, targetFolder, null, true);
    }

    private void show(String requestedType, String selectedId, String targetFolder,
                      Consumer<ReSyncResourceCreator.Result> completion, boolean create) {
        if (!AutomationDefinitionDraft.supports(requestedType) || savePending || creationPending || deletePending) {
            return;
        }
        if (componentEditor != null && componentEditor.isOpen()) {
            componentEditor.close();
        }
        String exactId = selectedId == null ? "" : selectedId.trim();
        String exactFolder = targetFolder == null ? "" : targetFolder;
        if (requestedType.equals(type)) {
            if (create) {
                creationCompletion = completion;
                requestedFolder = exactFolder;
                if (!creating) {
                    openNew(exactFolder, true);
                }
                return;
            }
            if (exactId.isBlank()) {
                return;
            }
            if (!creating && exactId.equals(activeId)) {
                return;
            }
            Item selected = item(exactId);
            if (selected != null) {
                openItem(selected, true);
                return;
            }
        }
        retainCurrentEditor();
        changeType(requestedType);
        requestedFolder = exactFolder;
        creationCompletion = completion;
        ContextKey requested = create ? ContextKey.creation(requestedType)
            : !exactId.isBlank() ? ContextKey.definition(requestedType, exactId)
            : lastEditorByType.get(requestedType);
        EditorState retained = requested != null ? retainedEditors.remove(requested) : null;
        if (retained != null) {
            restoreEditor(retained);
        } else if (create) {
            openNew(exactFolder, false);
        } else if (!exactId.isBlank() && item(exactId) != null) {
            openItem(item(exactId), false);
        } else {
            initialSelectedId = exactId.isBlank() ? null : exactId;
        }
        if (addAction != null) {
            addAction.setHint("New " + singularName(type));
        }
        updateNavigationSelection();
        filterNavigation();
        bindForm();
        refresh();
    }

    private void changeType(String requestedType) {
        lifecycle++;
        refreshGate.close();
        closeValueTypeSelector();
        type = requestedType;
        items = itemsByType.getOrDefault(requestedType, List.of());
        descriptor = null;
        typeCatalog = null;
        editorModel = null;
        activeLease = null;
        document = null;
        baselineDocument = null;
        rawValues.clear();
        baselineValues.clear();
        activeId = "";
        initialSelectedId = null;
        pendingCreatedId = null;
        folder = "";
        creating = false;
        loaded = itemsByType.containsKey(requestedType) && completeType(membership, requestedType);
        listMessage = loaded ? "No " + pluralName(requestedType) : "Loading Definitions";
        listDetail = loaded ? "Create One To Get Started" : "Reading Server Resources";
    }

    private void retainCurrentEditor() {
        if (document == null) {
            return;
        }
        ContextKey key = creating ? ContextKey.creation(type) : ContextKey.definition(type, activeId);
        retainedEditors.put(key, new EditorState(type, activeId, folder, creating, document, baselineDocument,
            rawValues, baselineValues, activeLease, creationCompletion));
        lastEditorByType.put(type, key);
        if (draft != null) {
            draft.close();
            draft = null;
        }
    }

    private void restoreEditor(EditorState state) {
        type = state.type();
        activeId = state.id();
        folder = state.folder();
        creating = state.creating();
        document = state.document();
        baselineDocument = state.baselineDocument();
        rawValues.clear();
        rawValues.putAll(state.rawValues());
        baselineValues.clear();
        baselineValues.putAll(state.baselineValues());
        activeLease = state.lease();
        creationCompletion = state.completion();
        replaceDraft(document);
        refreshEditorModel();
    }

    private void filterNavigation() {
        if (definitionGroups.isEmpty()) {
            return;
        }
        for (String resourceType : SUBRESOURCE_TYPES) {
            Setting group = definitionGroups.get(resourceType);
            List<Item> categoryItems = itemsByType.getOrDefault(resourceType, List.of());
            if (categoryItems.isEmpty()) {
                group.filter(navigationFilter);
                continue;
            }
            group.setRowVisibility("create", navigationFilter.isBlank()
                || normalize("new create " + singularName(resourceType) + " " + pluralName(resourceType))
                    .contains(navigationFilter));
            for (Item item : categoryItems) {
                group.setRowVisibility("definition:" + item.id(), navigationFilter.isBlank()
                    || normalize(pluralName(resourceType) + " " + item.searchText()).contains(navigationFilter));
            }
        }
        if (navigation != null) {
            navigation.container().updateWidgetPositions();
        }
    }

    private void updateNavigationSelection() {
        boolean interactive = !savePending && !creationPending && !deletePending;
        definitionEntries.forEach((key, entry) -> {
            boolean selected = key.creating()
                ? creating && key.type().equals(type)
                : !creating && key.type().equals(type) && key.id().equals(activeId);
            entry.setSelected(selected);
            entry.setActive(interactive && !selected);
        });
        if (addAction != null) {
            addAction.setActive(interactive && !creating);
        }
    }

    private void buildHeader() {
        header().reset();
        addAction = headerButton("add.png", "New " + singularName(type), () -> requestNew(type));
        primaryAction = headerButton("save.png", "Save Definition", this::requestStudioSave);
        deleteAction = headerButton("delete.png", "Delete Definition", this::showDelete);
        header().addRight(primaryAction);
        header().addRight(deleteAction);
        header().addRight(addAction);
        header().build();
    }

    private SquareButtonWidget headerButton(String icon, String hint, Runnable action) {
        return new SquareButtonWidget.Builder()
            .size(18, 18)
            .identifier(Identifier.icon(icon))
            .hint(hint)
            .onClick(action)
            .entranceAnimation(false)
            .build();
    }

    private void bindForm() {
        if (workspace == null) {
            return;
        }
        boolean hasDocument = document != null;
        if (!Objects.equals(mountedFormType, type) || mountedFormCreating != creating
            || mountedFormHasDocument != hasDocument) {
            rebuildForm();
            return;
        }
        closeValueTypeSelector();
        refreshFormControls();
    }

    private void rebuildForm() {
        if (workspace == null) {
            return;
        }
        closeValueTypeSelector();
        workspace.clearWidgets(true);
        fieldRows.clear();
        fieldControls.clear();
        formControls.clear();
        targetIdInput = null;
        validationRow = null;
        validationSetting = null;
        mountedFormType = type;
        mountedFormCreating = creating;
        mountedFormHasDocument = document != null;
        if (document == null) {
            MountableButtonWidget message = new MountableButtonWidget.Builder(initialSelectedId != null
                ? "Loading " + singularName(type) : "Choose A " + singularName(type))
                .description(initialSelectedId != null ? "Reading The Selected Definition"
                    : "Select A Definition Or Create One").build();
            message.setActive(false);
            message.setHeight(30);
            ReSyncStudioPanelState.disableEntrance(message);
            workspace.addWidget(message);
            workspace.updateWidgetPositions();
            return;
        }
        addSection("Identity", creating ? List.of("id", "name", "description")
            : List.of("name", "description"));
        switch (type) {
            case AutomationDefinitionDraft.VARIABLE -> {
                addSection("Value", List.of("valueType", "defaultValue"));
                addSection("Who This Belongs To", List.of("scope", "persistent"));
            }
            case AutomationDefinitionDraft.TIMER -> {
                addSection("Timer", List.of("defaultDuration", "defaultUnit", "tickInterval"));
                addSection("Who This Belongs To", List.of("scope", "persistent"));
            }
            case AutomationDefinitionDraft.SCHEDULE -> {
                addSection("When It Fires", List.of("targetId"));
                addSection("Timing", List.of("timingMode", "duration", "unit", "initialDelay", "dateTime",
                    "timeZone", "cron"));
                addSection("If A Run Is Already Active", List.of("overlapPolicy", "existingTaskPolicy"));
                addSection("If A Run Fails", List.of("failurePolicy"));
                addSection("If The Player Is Offline", List.of("offlinePolicy"));
                addSection("If The Server Missed A Run", List.of("missedRunPolicy"));
                addSection("Who This Belongs To", List.of("scope", "persistent"));
            }
            case AutomationDefinitionDraft.COMPONENT_BUILDER -> {
                addSection("Applies To", List.of("scopeKind", "scopeValue"));
                addComponentBuilderSection();
            }
            default -> {
            }
        }
        buildValidation();
        updateConditionalRows();
        updateActionState();
        workspace.updateWidgetPositions();
    }

    private void buildValidation() {
        validationRow = new MountableButtonWidget.Builder("Ready").description("").build();
        validationRow.setActive(false);
        validationRow.setHeight(30);
        ReSyncStudioPanelState.disableEntrance(validationRow);
        validationSetting = new Setting.Builder("Status").build();
        ReSyncStudioPanelState.disableEntrance(validationSetting);
        addRow(validationSetting, "validation", 30, validationRow);
        validationSetting.fitContentHeight();
        workspace.addWidget(validationSetting);
    }

    private void addSection(String title, List<String> fields) {
        Setting section = new Setting.Builder(title).build();
        ReSyncStudioPanelState.disableEntrance(section);
        for (String field : fields) {
            AnimatedWidget control = fieldControl(field);
            ReSyncStudioPanelState.disableEntrance(control);
            fieldControls.put(field, control);
            MountableButtonWidget row = new MountableButtonWidget.Builder(fieldLabel(field))
                .description(fieldDescription(field)).addWidget(control).build();
            row.setHeight(30);
            ReSyncStudioPanelState.disableEntrance(row);
            addRow(section, field, 30, row);
            fieldRows.put(field, new FieldRow(section, field, row));
        }
        section.fitContentHeight();
        workspace.addWidget(section);
    }

    private void addComponentBuilderSection() {
        Setting section = new Setting.Builder("Components").build();
        ReSyncStudioPanelState.disableEntrance(section);
        AnimatedButton edit = new AnimatedButton.Builder().label("Edit Components").size(CONTROL_WIDTH, 18)
            .onClick(this::openComponentBuilderEditor).build();
        formControls.add(edit);
        MountableButtonWidget row = new MountableButtonWidget.Builder("Item Components")
            .description(componentBuilderComponentSummary()).iconPath("item.png").addWidget(edit).build();
        row.setHeight(30);
        ReSyncStudioPanelState.disableEntrance(row);
        addRow(section, "components", 30, row);
        section.fitContentHeight();
        workspace.addWidget(section);
    }

    private void openComponentBuilderEditor() {
        if (document == null || !AutomationDefinitionDraft.COMPONENT_BUILDER.equals(type)) {
            return;
        }
        if (componentEditor == null) {
            componentEditor = new ItemComponentEditorPanel(this, serverId, "componentBuilderEditorPanel");
        }
        JsonObject scope = document.has("scope") && document.get("scope").isJsonObject()
            ? document.getAsJsonObject("scope") : new JsonObject();
        Map<String, Object> components = document.has("components") && document.get("components").isJsonObject()
            ? GSON.fromJson(document.getAsJsonObject("components"), Map.class) : Map.of();
        String material = "item".equals(AutomationDefinitionDraft.text(type, document, "scopeKind"))
            ? AutomationDefinitionDraft.text(type, document, "scopeValue") : "";
        componentEditor.open(new ItemComponentEditorPanel.Model(name(document, currentId()), material, scope,
            components, false, snapshot -> {
                document.add("scope", snapshot.scope());
                document.add("components", GSON.toJsonTree(snapshot.components()).getAsJsonObject());
                rawValues.put("scopeKind", AutomationDefinitionDraft.text(type, document, "scopeKind"));
                rawValues.put("scopeValue", AutomationDefinitionDraft.text(type, document, "scopeValue"));
                draft.markMutation();
                updateActionState();
            }, ignored -> {}, this::rebuildForm));
    }

    private String componentBuilderComponentSummary() {
        if (document == null || !document.has("components") || !document.get("components").isJsonObject()) {
            return "No Components";
        }
        int count = document.getAsJsonObject("components").size();
        return count == 1 ? "1 Component" : count + " Components";
    }

    private AnimatedWidget fieldControl(String field) {
        if ("persistent".equals(field)) {
            ToggleWidget toggle = new ToggleWidget.Builder().label("")
                .toggled(Boolean.parseBoolean(raw(field)))
                .onChange(value -> updateField(field, Boolean.toString(value))).build();
            formControls.add(toggle);
            return toggle;
        }
        if ("valueType".equals(field)) {
            valueTypeBrowse = new AnimatedButton.Builder().label(valueTypeLabel(raw(field))).build();
            valueTypeBrowse.setAction(() -> showValueTypeSelector(valueTypeBrowse));
            valueTypeBrowse.setWidth(CONTROL_WIDTH);
            formControls.add(valueTypeBrowse);
            return valueTypeBrowse;
        }
        if ("targetId".equals(field)) {
            List<String> values = runTargets(membership, currentMembership(), raw("targetType"), raw("targetId"));
            String selected = runTargetKey(raw("targetType"), raw("targetId"));
            targetIdInput = new DropDownWidget.Builder<>(values).displayFunction(this::runTargetLabel)
                .selectedItem(values.contains(selected) ? selected : values.getFirst())
                .maxVisibleItems(7)
                .onSelectionChanged(this::updateRunTarget).build();
            targetIdInput.setWidth(CONTROL_WIDTH);
            formControls.add(targetIdInput);
            return targetIdInput;
        }
        List<String> options = "id".equals(field) ? List.of() : AutomationDefinitionDraft.options(type, field);
        if (!options.isEmpty()) {
            String current = raw(field);
            List<String> values = new ArrayList<>(options);
            if (!current.isBlank() && !values.contains(current)) {
                values.addFirst(current);
            }
            String selected = values.contains(current) ? current : values.getFirst();
            DropDownWidget<String> dropdown = new DropDownWidget.Builder<>(values)
                .displayFunction(value -> optionLabel(field, value)).selectedItem(selected)
                .maxVisibleItems(7)
                .onSelectionChanged(value -> updateField(field, value)).build();
            dropdown.setWidth(CONTROL_WIDTH);
            formControls.add(dropdown);
            return dropdown;
        }
        return textInput(field);
    }

    private void refreshFormControls() {
        fieldControls.forEach((field, control) -> {
            String current = raw(field);
            if (control instanceof TextInputWidget input) {
                input.setText(current);
            } else if (control instanceof ToggleWidget toggle) {
                toggle.setValue(Boolean.parseBoolean(current));
            } else if (control instanceof DropDownWidget<?> rawDropdown) {
                @SuppressWarnings("unchecked")
                DropDownWidget<String> dropdown = (DropDownWidget<String>) rawDropdown;
                List<String> values;
                if ("targetId".equals(field)) {
                    values = runTargets(membership, currentMembership(), raw("targetType"), current);
                    current = runTargetKey(raw("targetType"), current);
                } else {
                    values = new ArrayList<>(AutomationDefinitionDraft.options(type, field));
                    if (!current.isBlank() && !values.contains(current)) {
                        values.addFirst(current);
                    }
                }
                if (!values.isEmpty()) {
                    dropdown.setItems(values, values.contains(current) ? current : values.getFirst());
                }
            } else if ("valueType".equals(field) && control instanceof AnimatedButton button) {
                button.setMessage(valueTypeLabel(current));
            }
        });
        updateConditionalRows();
        updateActionState();
    }

    private TextInputWidget textInput(String field) {
        TextInputWidget input = new TextInputWidget.Builder().placeholder(fieldLabel(field)).text(raw(field))
            .onChange(value -> updateField(field, value)).build();
        input.setWidth(CONTROL_WIDTH);
        formControls.add(input);
        return input;
    }

    private void updateField(String field, String value) {
        if (document == null || savePending || creationPending || deletePending) {
            return;
        }
        String exact = value == null ? "" : value;
        rawValues.put(field, exact);
        Runnable mutation = () -> {
            try {
                if ("id".equals(field)) {
                    document.addProperty("id", exact.trim());
                } else {
                    AutomationDefinitionDraft.put(type, document, field, exact);
                }
            } catch (RuntimeException ignored) {
            }
            draft.markMutation();
        };
        if (!draft.defer(mutation)) {
            mutation.run();
        }
        if ("targetType".equals(field)) {
            syncTargetChoices();
        }
        if ("timingMode".equals(field) || "scopeKind".equals(field)) {
            updateConditionalRows();
        }
        updateActionState();
    }

    private void updateRunTarget(String value) {
        if (document == null || savePending || creationPending || deletePending) {
            return;
        }
        String type = "function";
        String id = "";
        if (value != null && !value.isBlank() && !"select_target".equals(value)) {
            int split = value.indexOf(':');
            if (split > 0) {
                type = value.substring(0, split);
                id = value.substring(split + 1);
            }
        }
        rawValues.put("targetType", type);
        rawValues.put("targetId", id);
        String selectedType = type;
        String selectedId = id;
        Runnable mutation = () -> {
            try {
                AutomationDefinitionDraft.put(this.type, document, "targetType", selectedType);
                AutomationDefinitionDraft.put(this.type, document, "targetId", selectedId);
            } catch (RuntimeException ignored) {
            }
            draft.markMutation();
        };
        if (!draft.defer(mutation)) {
            mutation.run();
        }
        syncTargetChoices();
        updateActionState();
    }

    private void updateConditionalRows() {
        if (AutomationDefinitionDraft.COMPONENT_BUILDER.equals(type)) {
            setFieldVisible("scopeValue", !"dynamic".equals(raw("scopeKind")));
            fieldRows.values().stream().map(FieldRow::setting).distinct().forEach(Setting::fitContentHeight);
            workspace.updateWidgetPositions();
            return;
        }
        if (!AutomationDefinitionDraft.SCHEDULE.equals(type)) {
            return;
        }
        String mode = raw("timingMode");
        setFieldVisible("duration", "after_delay".equals(mode) || "repeating".equals(mode));
        setFieldVisible("unit", "after_delay".equals(mode) || "repeating".equals(mode));
        setFieldVisible("initialDelay", "repeating".equals(mode));
        setFieldVisible("dateTime", "at_time".equals(mode));
        setFieldVisible("timeZone", "at_time".equals(mode) || "cron".equals(mode));
        setFieldVisible("cron", "cron".equals(mode));
        fieldRows.values().stream().map(FieldRow::setting).distinct().forEach(Setting::fitContentHeight);
        workspace.updateWidgetPositions();
    }

    private void setFieldVisible(String field, boolean visible) {
        FieldRow row = fieldRows.get(field);
        if (row != null) {
            row.setting().setRowVisibility(row.rowId(), visible);
        }
    }

    private void syncTargetChoices() {
        if (targetIdInput == null) {
            return;
        }
        List<String> values = runTargets(membership, currentMembership(), raw("targetType"), raw("targetId"));
        String selected = runTargetKey(raw("targetType"), raw("targetId"));
        targetIdInput.setItems(values, values.contains(selected) ? selected : values.getFirst());
    }

    private void updateActionState() {
        Validation validation = validateCurrent();
        boolean busy = savePending || creationPending || deletePending;
        formControls.forEach(control -> control.setActive(!busy));
        if (addAction != null) {
            addAction.setActive(!busy && !creating);
        }
        if (primaryAction != null) {
            primaryAction.setActive(!busy && dirty() && validation.valid());
        }
        if (deleteAction != null) {
            deleteAction.setActive(!busy && !creating && activeLease != null && activeLease.isCurrent()
                && currentMembership() && membership.contains(type, activeId));
            deleteAction.visible = !creating;
        }
        updateNavigationSelection();
        if (validationRow == null) {
            return;
        }
        if (busy) {
            setValidationVisible(true);
            validationRow.setName(deletePending ? "Deleting " + singularName(type) : creationQueued
                ? "Queued For Creation" : creationPending ? "Creating " + singularName(type)
                : "Saving " + singularName(type));
            validationRow.setDescription(creationQueued ? "The Server Will Finish This Creation In The Background"
                : creationPending ? "Waiting For Durable Queue Admission" : "Waiting For Server Confirmation");
            validationRow.setHiddenText(creationQueued ? "Queued" : "Pending");
        } else if (!validation.valid()) {
            setValidationVisible(true);
            validationRow.setName("Needs Attention");
            validationRow.setDescription(validation.message());
            validationRow.setHiddenText("Save Blocked");
        } else {
            setValidationVisible(false);
            validationRow.setName("Ready");
            validationRow.setDescription("");
            validationRow.setHiddenText("");
        }
        workspace.updateWidgetPositions();
    }

    private void setValidationVisible(boolean visible) {
        if (validationSetting != null) {
            validationSetting.setVisible(visible);
        }
    }

    private Validation validateCurrent() {
        if (document == null) {
            return Validation.invalid("Choose A Definition");
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || serverId.isBlank()) {
            return Validation.invalid("ReSync Is Not Connected");
        }
        if (descriptor == null) {
            return Validation.invalid("Loading Type Capabilities");
        }
        ManagedResourceCatalog.Operation operation = creating ? ManagedResourceCatalog.Operation.CREATE
            : descriptor.supports(ManagedResourceCatalog.Operation.UPDATE) ? ManagedResourceCatalog.Operation.UPDATE
            : ManagedResourceCatalog.Operation.SAVE;
        if (!descriptor.available() || descriptor.opaque() || !descriptor.supports(operation)) {
            String reason = descriptor.unavailableReason().isBlank() ? descriptor.operation(operation).reason()
                : descriptor.unavailableReason();
            return Validation.invalid(reason.isBlank() ? singularName(type) + " Editing Is Unavailable" : reason);
        }
        if (!creating && (editorModel == null || editorModel.readOnly())) {
            return Validation.invalid("This Definition Is Read Only");
        }
        if (!creating && (activeLease == null || !activeLease.isCurrent())) {
            return Validation.invalid("This Definition Changed. Reopen It Before Saving");
        }
        String id = currentId();
        if (creating && !id.matches("^[a-zA-Z0-9_]+$")) {
            return Validation.invalid("ID Must Use Letters, Numbers, And Underscores");
        }
        if (creating && ReSyncResourceCreator.exists(manager, serverId, type, id, folder)) {
            return Validation.invalid(singularName(type) + " ID Already Exists");
        }
        ReSyncValueTypeCatalog.Snapshot catalog = AutomationDefinitionDraft.VARIABLE.equals(type)
            ? currentTypeCatalog() : null;
        if (AutomationDefinitionDraft.VARIABLE.equals(type) && catalog == null) {
            return Validation.invalid("Value Types Are Still Loading");
        }
        try {
            JsonObject candidate = materializeRawDocument();
            AutomationDefinitionDraft.Prepared prepared = catalog != null
                ? AutomationDefinitionDraft.prepare(type, id, candidate, catalog.dataTypes())
                : AutomationDefinitionDraft.prepare(type, id, candidate);
            if (catalog != null && !currentTypeCatalog(catalog)) {
                return Validation.invalid("Value Types Changed. Review The Selection");
            }
            if (prepared.target() != null && (!currentMembership() || !manager.isAvailableScheduleTarget(serverId,
                prepared.target().locator(ServerId.parseCanonicalText(serverId))))) {
                return Validation.invalid("Choose An Available Flow, Function, Or Command");
            }
            return Validation.valid(prepared);
        } catch (RuntimeException exception) {
            return Validation.invalid(message(exception));
        }
    }

    private JsonObject materializeRawDocument() {
        JsonObject candidate = document.deepCopy();
        if (creating) {
            candidate.addProperty("id", currentId());
        }
        for (String field : AutomationDefinitionDraft.fields(type)) {
            if ("defaultValue".equals(field) && !raw(field).isBlank()) {
                JsonParser.parseString(raw(field));
            }
            AutomationDefinitionDraft.put(type, candidate, field, raw(field));
        }
        return candidate;
    }

    private void saveExisting(Runnable afterSave) {
        if (creating || savePending || creationPending) {
            return;
        }
        Validation validation = validateCurrent();
        if (!validation.valid()) {
            updateActionState();
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        FlowManager.ResourceReadLease expected = activeLease;
        if (manager == null || resourceType == null || expected == null) {
            return;
        }
        applyPrepared(validation.prepared().document());
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, resourceType,
            activeId, singularName(type));
        if (ticket == null) {
            return;
        }
        long sequence = ++saveSequence;
        long expectedLifecycle = lifecycle;
        Map<String, String> submittedValues = Map.copyOf(rawValues);
        savePending = true;
        updateActionState();
        ticket.whenFinished((saved, current) -> ScreenManager.getInstance().execute(() -> finishSave(sequence,
            expectedLifecycle, ticket, saved, current, submittedValues, afterSave)));
        if (!draft.capture(type, activeId, ticket, snapshot -> {
            JsonObject saving = snapshot.serialize(payload -> JsonParser.parseString(payload).getAsJsonObject());
            if (!manager.saveStudioPanelResource(serverId, resourceType, expected, saving, ticket)) {
                DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
            }
        })) {
            DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
        }
    }

    private void finishSave(long sequence, long expectedLifecycle, DesignerSaveNotifications.SaveTicket ticket,
                            boolean saved, boolean current, Map<String, String> submittedValues, Runnable afterSave) {
        if (sequence != saveSequence || draft == null) {
            return;
        }
        if (!saved || !current) {
            draft.discard(ticket);
            savePending = false;
            if (disposed) {
                draft.close();
                draft = null;
            } else {
                updateActionState();
            }
            return;
        }
        FlowManager manager = FlowManager.getInstance();
        FlowManager.ResourceReadLease lease = manager != null ? manager.snapshotResource(serverId, type, activeId) : null;
        BrowserSafeState.ReferenceValue<String> authoritative = new BrowserSafeState.ReferenceValue<>();
        if (lease == null || !draft.acknowledge(ticket, () -> {
            String payload = lease.materialize();
            authoritative.set(payload);
            return payload;
        })) {
            draft.discard(ticket);
            savePending = false;
            new Notification("Save Refresh Failed", "Reopen The Definition", Notification.Type.ERROR);
            if (disposed) {
                draft.close();
                draft = null;
            } else {
                updateActionState();
            }
            return;
        }
        saveAcknowledgement = new SaveAcknowledgement(sequence, expectedLifecycle, lease, authoritative,
            submittedValues, afterSave);
    }

    private void finishSaveAcknowledgement() {
        SaveAcknowledgement acknowledgement = saveAcknowledgement;
        if (acknowledgement == null || draft == null || !draft.isSettled()) {
            return;
        }
        if (disposed) {
            return;
        }
        saveAcknowledgement = null;
        if (saveSequence != acknowledgement.sequence()) {
            return;
        }
        String payload = acknowledgement.authoritative().get();
        if (payload == null || payload.isBlank() || !acknowledgement.lease().isCurrent()) {
            savePending = false;
            new Notification("Save Refresh Failed", "Reopen The Definition", Notification.Type.ERROR);
            updateActionState();
            return;
        }
        try {
            boolean sameLifecycle = lifecycle == acknowledgement.lifecycle();
            baselineDocument = JsonParser.parseString(payload).getAsJsonObject();
            baselineValues.clear();
            baselineValues.putAll(rawValues(baselineDocument, false));
            if (rawValues.equals(acknowledgement.submittedValues())) {
                rawValues.clear();
                rawValues.putAll(rawValues(document, false));
            }
            activeLease = acknowledgement.lease();
            refreshEditorModel();
            savePending = false;
            bindForm();
            if (sameLifecycle && acknowledgement.afterSave() != null && !dirty()) {
                acknowledgement.afterSave().run();
            }
        } catch (RuntimeException exception) {
            savePending = false;
            new Notification("Save Refresh Failed", "Reopen The Definition", Notification.Type.ERROR);
            updateActionState();
        }
    }

    private void createDefinition() {
        if (!creating || savePending || creationPending) {
            return;
        }
        Validation validation = validateCurrent();
        if (!validation.valid()) {
            updateActionState();
            return;
        }
        applyPrepared(validation.prepared().document());
        long expectedLifecycle = lifecycle;
        String id = currentId();
        PendingCreation pending = new PendingCreation(expectedLifecycle, id, validation.prepared());
        pendingCreation = pending;
        creationPending = true;
        creationQueued = false;
        updateActionState();
        ReSyncResourceCreator.Submission submission = ReSyncResourceCreator.createPreparedAutomation(serverId, type,
            id, folder, pending.prepared(), AutomationDefinitionDraft.VARIABLE.equals(type) ? currentTypeCatalog()
                : null, result -> acceptCreated(pending, result));
        if (!submission.admitted()) {
            clearPendingCreation(pending);
            updateActionState();
            return;
        }
        submission.durable().whenComplete((accepted, error) -> ScreenManager.getInstance().execute(() ->
            finishCreationDurability(pending, accepted, error)));
    }

    private void finishCreationDurability(PendingCreation expected, Boolean accepted, Throwable error) {
        if (pendingCreation != expected) {
            return;
        }
        if (error != null || !Boolean.TRUE.equals(accepted)) {
            clearPendingCreation(expected);
        } else {
            creationQueued = true;
        }
        if (!disposed) {
            updateActionState();
        }
    }

    private void clearPendingCreation(PendingCreation expected) {
        if (pendingCreation != expected) {
            return;
        }
        pendingCreation = null;
        pendingCreatedId = null;
        completedCreation = null;
        creationPending = false;
        creationQueued = false;
    }

    private void acceptCreated(PendingCreation expected, ReSyncResourceCreator.Result result) {
        if (result == null || !type.equals(result.type()) || !expected.id().equals(result.id())
            || pendingCreation != expected) {
            return;
        }
        if (creationCompletion != null) {
            creationCompletion.accept(result);
        }
        completedCreation = result;
        if (disposed) {
            return;
        }
        adoptCompletedCreation(expected, result);
    }

    private void resumeCompletedCreation() {
        PendingCreation pending = pendingCreation;
        ReSyncResourceCreator.Result result = completedCreation;
        if (pending != null && result != null && type.equals(result.type()) && pending.id().equals(result.id())) {
            adoptCompletedCreation(pending, result);
        }
    }

    private void adoptCompletedCreation(PendingCreation expected, ReSyncResourceCreator.Result result) {
        if (pendingCreation != expected || result == null) {
            return;
        }
        pendingCreatedId = result.id();
        creationPending = true;
        listMessage = "Loading Created " + singularName(type);
        listDetail = "Waiting For The Created Definition";
        refresh();
        updateActionState();
    }

    private void showDelete() {
        if (creating || savePending || creationPending || deletePending || activeLease == null
            || !activeLease.isCurrent() || !currentMembership() || !membership.contains(type, activeId)) {
            refresh();
            return;
        }
        PopupWidget[] popup = new PopupWidget[1];
        int popupWidth = Math.clamp(width * 38 / 100, 340, 480);
        int popupHeight = Math.clamp(height * 32 / 100, 150, 220);
        PopupWidget.Builder builder = new PopupWidget.Builder("Delete " + name(document, activeId))
            .size(popupWidth, popupHeight).setMinSize(320, 140).setResizable(true)
            .setAntiOutOfBound(true).setBoundOffset(header().headerSize + 5);
        MountableButtonWidget effect = new MountableButtonWidget.Builder("Remove This Definition")
            .description("Automation Nodes Using It Will Need Another Selection").build();
        effect.setActive(false);
        effect.setHeight(30);
        builder.addRow("", effect);
        builder.addTitleAction("Delete", () -> {
            if (popup[0] != null) {
                popup[0].hide();
            }
            deleteDefinition();
        }, PopupWidget.TitleActionRole.DESTRUCTIVE);
        popup[0] = builder.build();
        addDrawableChild(popup[0]);
        popup[0].show();
    }

    private void deleteDefinition() {
        FlowManager manager = FlowManager.getInstance();
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        FlowManager.TypedResourceMembershipSnapshot expectedMembership = membership;
        FlowManager.ResourceReadLease expectedLease = activeLease;
        ManagedResourceCatalog.Access access = descriptor != null
            ? descriptor.operation(ManagedResourceCatalog.Operation.DELETE) : null;
        if (creating || savePending || creationPending || deletePending || manager == null || resourceType == null
            || access == null || !access.available() || expectedLease == null || !expectedLease.isCurrent()
            || !manager.isCurrentTypedResourceMembership(expectedMembership)
            || !expectedMembership.contains(type, activeId)) {
            String reason = access != null && !access.reason().isBlank() ? access.reason() : "Delete Is Unavailable";
            new Notification("Delete", reason, Notification.Type.ERROR);
            refresh();
            return;
        }
        long sequence = ++deleteSequence;
        long expectedLifecycle = lifecycle;
        String expectedId = activeId;
        deletePending = true;
        updateActionState();
        manager.deleteResourceSettled(serverId, resourceType, expectedId).whenComplete((result, failure) ->
            ScreenManager.getInstance().execute(() -> finishDelete(sequence, expectedLifecycle, manager,
                expectedId, result, failure)));
    }

    private void finishDelete(long sequence, long expectedLifecycle, FlowManager expectedManager, String expectedId,
                              FlowManager.ResourceDeleteResult result, Throwable failure) {
        if (sequence != deleteSequence || !expectedId.equals(activeId)) {
            return;
        }
        if (FlowManager.getInstance() != expectedManager) {
            deletePending = false;
            if (!disposed) {
                updateActionState();
                refresh();
            }
            return;
        }
        deletePending = false;
        if (failure != null || result == null || !result.deleted() || !type.equals(result.type())
            || !expectedId.equals(result.id())) {
            String reason = result != null && result.message() != null && !result.message().isBlank()
                ? result.message() : failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()
                ? failure.getMessage() : "Delete Failed";
            new Notification("Delete", reason, Notification.Type.ERROR);
            if (!disposed) {
                updateActionState();
            }
            return;
        }
        if (draft != null) {
            draft.close();
            draft = null;
        }
        items = items.stream().filter(item -> !expectedId.equals(item.id())).toList();
        Map<String, List<Item>> updatedItems = new LinkedHashMap<>(itemsByType);
        updatedItems.put(type, items);
        itemsByType = Map.copyOf(updatedItems);
        loaded = true;
        listMessage = "No " + pluralName(type);
        listDetail = "Create One To Get Started";
        document = null;
        baselineDocument = null;
        rawValues.clear();
        baselineValues.clear();
        activeId = "";
        activeLease = null;
        editorModel = null;
        retainedEditors.remove(ContextKey.definition(type, expectedId));
        ContextKey last = lastEditorByType.get(type);
        if (last != null && !last.creating() && expectedId.equals(last.id())) {
            lastEditorByType.remove(type);
        }
        new Notification("Deleted", expectedId, Notification.Type.SUCCESS);
        if (!disposed) {
            rebuildNavigation();
            bindForm();
            refresh();
        }
    }

    private void applyPrepared(JsonObject prepared) {
        document.keySet().clear();
        prepared.entrySet().forEach(entry -> document.add(entry.getKey(), entry.getValue().deepCopy()));
    }

    private void openItem(Item item, boolean retainCurrent) {
        if (item == null || !item.lease().isCurrent() || !currentMembership()
            || !membership.contains(type, item.id())) {
            refresh();
            new Notification(pluralName(type), "Definition Changed. Try Again", Notification.Type.WARN);
            return;
        }
        if (retainCurrent) {
            retainCurrentEditor();
        }
        ContextKey key = ContextKey.definition(type, item.id());
        EditorState retained = retainedEditors.remove(key);
        if (retained != null && retained.dirty()) {
            restoreEditor(retained);
            updateNavigationSelection();
            bindForm();
            return;
        }
        replaceDraft(item.document());
        creationCompletion = null;
        creating = false;
        activeId = item.id();
        folder = item.folder();
        activeLease = item.lease();
        baselineDocument = item.document();
        baselineValues.clear();
        baselineValues.putAll(rawValues(document, false));
        rawValues.clear();
        rawValues.putAll(baselineValues);
        initialSelectedId = null;
        lastEditorByType.put(type, key);
        refreshEditorModel();
        updateNavigationSelection();
        bindForm();
    }

    private void openNew(String targetFolder, boolean retainCurrent) {
        if (retainCurrent) {
            retainCurrentEditor();
        }
        ContextKey key = ContextKey.creation(type);
        EditorState retained = retainedEditors.remove(key);
        if (retained != null && retained.dirty()) {
            restoreEditor(retained);
            updateNavigationSelection();
            bindForm();
            return;
        }
        AutomationDefinitionDraft.Target target = AutomationDefinitionDraft.SCHEDULE.equals(type)
            ? new AutomationDefinitionDraft.Target("function", "select_target") : null;
        JsonObject seed = AutomationDefinitionDraft.create(type, "new_automation", targetFolder, target);
        seed.addProperty("id", "");
        seed.addProperty("name", "");
        replaceDraft(seed);
        creating = true;
        activeId = "";
        folder = targetFolder == null ? "" : targetFolder;
        activeLease = null;
        editorModel = null;
        baselineDocument = seed.deepCopy();
        baselineValues.clear();
        baselineValues.putAll(rawValues(seed, true));
        rawValues.clear();
        rawValues.putAll(baselineValues);
        lastEditorByType.put(type, key);
        updateNavigationSelection();
        bindForm();
    }

    private void replaceDraft(JsonObject replacement) {
        if (draft != null) {
            draft.close();
        }
        document = replacement.deepCopy();
        draft = new VersionedEditorDraft<>(document, JsonObject::toString,
            payload -> JsonParser.parseString(payload).getAsJsonObject(), this::rebindDocument,
            this::failDraft, () -> new Notification("Editor Busy", "Try Again", Notification.Type.WARN));
    }

    private void rebindDocument(JsonObject previous, JsonObject replacement) {
        document = replacement;
    }

    private void failDraft(VersionedEditorDraft.Failure failure) {
        if (failure.request() instanceof DesignerSaveNotifications.SaveTicket ticket) {
            DesignerSaveNotifications.failExact(ticket, failure.stage() == VersionedEditorDraft.Stage.REBASE
                ? "Save Refresh Failed" : "Save Snapshot Failed");
        }
    }

    private void refreshEditorModel() {
        editorModel = descriptor == null ? null : ManagedResourceEditorModel.open(serverId, descriptor, activeId,
            document, true).orElse(null);
    }

    private FlowManager.ResourceReadLease snapshotResource(String id) {
        FlowManager manager = FlowManager.getInstance();
        return manager != null ? manager.snapshotResource(serverId, type, id) : null;
    }

    private void discardChanges() {
        if (savePending || creationPending || deletePending || baselineDocument == null) {
            return;
        }
        replaceDraft(baselineDocument);
        rawValues.clear();
        rawValues.putAll(baselineValues);
        refreshEditorModel();
        bindForm();
    }

    @Override
    public void close() {
        if (componentEditor != null && componentEditor.isOpen()) {
            componentEditor.close();
            return;
        }
        if (savePending || deletePending || creationPending && !creationQueued) {
            return;
        }
        if (creationQueued) {
            finishClose();
            return;
        }
        finishClose();
    }

    private void finishClose() {
        if (disposed) {
            return;
        }
        closeValueTypeSelector();
        if (parent != null) {
            ScreenManager.getInstance().setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public void removed() {
        if (componentEditor != null && componentEditor.isOpen()) {
            componentEditor.close();
        }
        restoreContentBrowser();
        if (!disposed) {
            disposed = true;
            lifecycle++;
            refreshGate.close();
            if (worker != null) {
                worker.close();
            }
            closeValueTypeSelector();
            if (draft != null && !savePending) {
                draft.close();
            }
        }
        super.removed();
    }

    @Override
    public boolean hasUnsavedChanges() {
        return creating || dirty() || savePending || creationPending || deletePending
            || retainedEditors.values().stream().anyMatch(state -> state.creating() || state.dirty());
    }

    @Override
    public boolean requestSave() {
        if (savePending || creationPending || deletePending) {
            return false;
        }
        if (creating) {
            createDefinition();
            return creationPending;
        }
        saveExisting(null);
        return savePending;
    }

    @Override
    public boolean requestStudioSave() {
        return requestSave();
    }

    @Override
    public void discardUnsavedChanges() {
        if (savePending || creationPending || deletePending) {
            return;
        }
        if (creating) {
            retainedEditors.remove(ContextKey.creation(type));
            openNew(requestedFolder, false);
        } else {
            discardChanges();
        }
    }

    @Override
    public void tick() {
        super.tick();
        drainEmbedded();
    }

    @Override
    public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
        super.renderHandler(context, mouseX, mouseY, delta);
        if (componentEditor != null && componentEditor.isOpen()) {
            componentEditor.render(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        layoutDesigner();
    }

    @Override
    protected void onSidePanelWidthChanged() {
        super.onSidePanelWidthChanged();
        layoutDesigner();
    }

    private void layoutDesigner() {
        if (navigation != null) {
            navigation.y(HEADER_HEIGHT).height(Math.max(100, height - HEADER_HEIGHT - 5));
            navigation.updateContainerBounds();
            if (navigationSearch != null) {
                navigationSearch.setWidth(Math.max(80, navigation.getConfiguredWidth() - 10));
            }
        }
        if (workspace != null) {
            int left = navigationWidth();
            workspace.setPosition(left, HEADER_HEIGHT);
            workspace.setSize(Math.max(220, width - left - 5), Math.max(100, height - HEADER_HEIGHT - 5));
            workspace.updateWidgetPositions();
        }
        if (navigation != null) {
            navigation.container().updateWidgetPositions();
        }
    }

    private int navigationWidth() {
        return navigation == null ? NAVIGATION_WIDTH + 8 : navigation.layoutWidth(8);
    }

    @Override
    public boolean hasActiveStudioSelector() {
        return valueTypeSelector != null && valueTypeSelector.isOpen();
    }

    @Override
    public ItemSelectorWidget activeStudioSelector() {
        return hasActiveStudioSelector() ? valueTypeSelector : null;
    }

    @Override
    public void selected() {
        if (!contentBrowserHidden && parent instanceof StudioScreen studioScreen) {
            studioScreen.setStudioContentBrowserTemporarilyHidden(true);
            contentBrowserHidden = true;
        }
        if (!disposed) {
            refresh();
        }
    }

    @Override
    public void deselected() {
        closeValueTypeSelector();
        restoreContentBrowser();
    }

    private void restoreContentBrowser() {
        if (contentBrowserHidden && parent instanceof StudioScreen studioScreen) {
            studioScreen.setStudioContentBrowserTemporarilyHidden(false);
        }
        contentBrowserHidden = false;
    }

    @Override
    public void resourceRenamed(String type, String oldId, String newId) {
        if (!this.type.equals(type) || !activeId.equals(oldId) || newId == null || newId.isBlank()) {
            return;
        }
        Runnable rename = () -> {
            activeId = newId;
            if (document != null) {
                document.addProperty("id", newId);
            }
            if (baselineDocument != null) {
                baselineDocument.addProperty("id", newId);
            }
            activeLease = snapshotResource(newId);
        };
        if (draft != null && draft.defer(rename)) {
            return;
        }
        rename.run();
    }

    private void drainEmbedded() {
        if (disposed) {
            return;
        }
        if (draft != null) {
            draft.drain();
        }
        finishSaveAcknowledgement();
        if (valueTypeSelector != null && valueTypeSelector.isOpen() && valueTypeBrowse != null) {
            int selectorX = valueTypeSelectorX();
            int selectorY = valueTypeSelectorY();
            if (valueTypeSelector.getX() != selectorX || valueTypeSelector.getY() != selectorY) {
                valueTypeSelector.setPosition(selectorX, selectorY);
            }
        }
        if (System.currentTimeMillis() - lastRefreshAt >= REFRESH_INTERVAL) {
            refresh();
        }
    }

    private void refresh() {
        if (disposed || serverId.isBlank()) {
            return;
        }
        lastRefreshAt = System.currentTimeMillis();
        requestDefinitionData();
        long generation = refreshGate.request();
        if (generation == 0L) {
            return;
        }
        try {
            AsyncTaskWorker currentWorker = worker;
            if (currentWorker == null) {
                throw new IllegalStateException("Definition refresh worker is unavailable");
            }
            currentWorker.execute(() -> drainRefreshes(generation));
        } catch (RuntimeException exception) {
            refreshGate.cancel(generation);
            showLoadFailure(generation, lifecycle, "Could Not Start Definition Refresh");
        }
    }

    private void requestDefinitionData() {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return;
        }
        try {
            FlowManager.TypedResourceMembershipSnapshot snapshot = manager.snapshotTypedResourceMembership(serverId);
            long generation = snapshot.connectionGeneration();
            ReSyncFlowClient flowClient = manager.existingFlowClient(serverId);
            if ((demandedCatalogManager != manager || demandedCatalogGeneration != generation) && flowClient != null
                && flowClient.catalogAuthoringAdvertised() && ReSyncValueTypeCatalog.active(flowClient) == null) {
                if (flowClient.requestCatalogPublication(true)) {
                    demandedCatalogManager = manager;
                    demandedCatalogGeneration = generation;
                }
            }
            if (demandedManager == manager && demandedConnectionGeneration == generation) {
                return;
            }
            demandedManager = manager;
            demandedConnectionGeneration = generation;
            manager.requestInitialFlowData(serverId);
        } catch (RuntimeException exception) {
            demandedManager = null;
            demandedConnectionGeneration = Long.MIN_VALUE;
            listMessage = "Could Not Request Definitions";
            listDetail = message(exception);
            loaded = false;
        }
    }

    private void drainRefreshes(long generation) {
        while (!disposed) {
            long expectedLifecycle = lifecycle;
            try {
                prepare(generation, expectedLifecycle);
            } catch (RuntimeException exception) {
                showLoadFailure(generation, expectedLifecycle, message(exception));
            }
            if (disposed || !refreshGate.continueAfter(generation)) {
                return;
            }
        }
    }

    private void prepare(long generation, long expectedLifecycle) {
        FlowManager manager = FlowManager.getInstance();
        if (manager == null || disposed || lifecycle != expectedLifecycle) {
            showLoadFailure(generation, expectedLifecycle, "ReSync Is Not Connected");
            return;
        }
        FlowManager.TypedResourceMembershipSnapshot preparedMembership =
            manager.snapshotTypedResourceMembership(serverId);
        if (!manager.isCurrentTypedResourceMembership(preparedMembership)) {
            showLoadFailure(generation, expectedLifecycle, "Waiting For A Current Server Connection");
            return;
        }
        ReSyncValueTypeCatalog.Snapshot preparedCatalog = ReSyncValueTypeCatalog.active(
            manager.existingFlowClient(serverId));
        ManagedResourceCatalog.Descriptor preparedDescriptor = manager.managedResourceDescriptor(serverId, type);
        Map<String, List<Item>> preparedItemsByType = new LinkedHashMap<>();
        categoryLoop:
        for (String resourceType : SUBRESOURCE_TYPES) {
            if (!completeType(preparedMembership, resourceType)) {
                if (resourceType.equals(type)) {
                    publishPrepared(generation, expectedLifecycle, new PreparedScan(manager, preparedMembership,
                        preparedCatalog, preparedDescriptor, Map.copyOf(preparedItemsByType), ScanState.PENDING,
                        "Waiting For The Complete " + singularName(resourceType) + " List", resourceType, "", null));
                    return;
                }
                continue;
            }
            List<Item> preparedItems = new ArrayList<>();
            for (FlowManager.ProjectResource resource : preparedMembership.resources()) {
                if (!resourceType.equals(resource.type())) {
                    continue;
                }
                FlowManager.ResourceReadLease lease = manager.snapshotResource(serverId, resourceType, resource.id());
                if (lease == null) {
                    if (resourceType.equals(type)) {
                        publishPrepared(generation, expectedLifecycle, new PreparedScan(manager, preparedMembership,
                            preparedCatalog, preparedDescriptor, Map.copyOf(preparedItemsByType), ScanState.PENDING,
                            "Waiting For " + resource.id(), resourceType, resource.id(), null));
                        return;
                    }
                    continue categoryLoop;
                }
                try {
                    String payload = lease.materialize();
                    if (payload == null || !lease.isCurrent()) {
                        if (resourceType.equals(type)) {
                            publishPrepared(generation, expectedLifecycle, new PreparedScan(manager, preparedMembership,
                                preparedCatalog, preparedDescriptor, Map.copyOf(preparedItemsByType), ScanState.PENDING,
                                "Refreshing " + resource.id(), resourceType, resource.id(), lease));
                            return;
                        }
                        continue categoryLoop;
                    }
                    JsonObject exact = JsonParser.parseString(payload).getAsJsonObject();
                    Collection<FlowDataType> dataTypes = AutomationDefinitionDraft.VARIABLE.equals(resourceType)
                        && preparedCatalog != null ? preparedCatalog.dataTypes() : null;
                    DefinitionValidation validation = validateDefinition(resourceType, resource.id(), exact,
                        dataTypes);
                    exact = validation.document();
                    preparedItems.add(new Item(resource.id(), name(exact, resource.id()),
                        text(exact, "description"), resource.path(), validation.diagnostic(), exact, lease));
                } catch (RuntimeException exception) {
                    if (resourceType.equals(type)) {
                        publishPrepared(generation, expectedLifecycle, new PreparedScan(manager, preparedMembership,
                            preparedCatalog, preparedDescriptor, Map.copyOf(preparedItemsByType), ScanState.ERROR,
                            "Could Not Read " + resource.id() + ": " + message(exception), resourceType,
                            resource.id(), lease));
                        return;
                    }
                    continue categoryLoop;
                }
            }
            preparedItems.sort(Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Item::id));
            preparedItemsByType.put(resourceType, List.copyOf(preparedItems));
        }
        publishPrepared(generation, expectedLifecycle, new PreparedScan(manager, preparedMembership, preparedCatalog,
            preparedDescriptor, Map.copyOf(preparedItemsByType), ScanState.READY, "", "", "", null));
    }

    private void publishPrepared(long generation, long expectedLifecycle, PreparedScan scan) {
        ScreenManager.getInstance().execute(() -> publish(generation, expectedLifecycle, scan));
    }

    private void publish(long generation, long expectedLifecycle, PreparedScan prepared) {
        if (disposed || lifecycle != expectedLifecycle || !refreshGate.current(generation)
            || FlowManager.getInstance() != prepared.manager()
            || !prepared.manager().isCurrentTypedResourceMembership(prepared.membership())
            || !Objects.equals(prepared.manager().snapshotTypedResourceMembership(serverId), prepared.membership())
            || !currentTypeCatalog(prepared.typeCatalog())
            || !currentDescriptor(prepared.manager(), prepared.descriptor())
            || !currentBlockedState(prepared)
            || prepared.itemsByType().values().stream().flatMap(List::stream)
                .anyMatch(item -> !item.lease().isCurrent())) {
            return;
        }
        ManagedResourceCatalog.Descriptor previousDescriptor = descriptor;
        membership = prepared.membership();
        typeCatalog = prepared.typeCatalog();
        descriptor = prepared.descriptor();
        if (document != null && !creating && !Objects.equals(previousDescriptor, descriptor)) {
            refreshEditorModel();
        }
        if (prepared.state() != ScanState.READY) {
            String nextMessage = prepared.state() == ScanState.ERROR ? "Could Not Load Definitions"
                : "Loading Definitions";
            String nextDetail = prepared.message().isBlank() ? "Retrying Automatically"
                : prepared.message() + " · Retrying Automatically";
            boolean navigationChanged = loaded || !sameNavigation(itemsByType, prepared.itemsByType())
                || !nextMessage.equals(listMessage) || !nextDetail.equals(listDetail);
            itemsByType = prepared.itemsByType();
            items = itemsByType.getOrDefault(type, List.of());
            loaded = false;
            listMessage = nextMessage;
            listDetail = nextDetail;
            if (navigationChanged) {
                rebuildNavigation();
            }
            syncTargetChoices();
            updateActionState();
            return;
        }
        boolean navigationChanged = !loaded || !sameNavigation(itemsByType, prepared.itemsByType());
        itemsByType = prepared.itemsByType();
        items = itemsByType.getOrDefault(type, List.of());
        loaded = itemsByType.containsKey(type);
        listMessage = "No " + pluralName(type);
        listDetail = "Create One To Get Started";
        if (navigationChanged) {
            rebuildNavigation();
        }
        syncTargetChoices();
        boolean createdSelection = pendingCreatedId != null;
        String requestedId = createdSelection ? pendingCreatedId : initialSelectedId;
        if (requestedId == null) {
            updateActionState();
            return;
        }
        Item selected = item(requestedId);
        if (selected == null) {
            if (!createdSelection) {
                initialSelectedId = null;
                bindForm();
                new Notification(pluralName(type), singularName(type) + " Was Not Found", Notification.Type.WARN);
            }
            updateActionState();
            return;
        }
        if (createdSelection) {
            retainedEditors.remove(ContextKey.creation(type));
        }
        pendingCreatedId = null;
        initialSelectedId = null;
        creationPending = false;
        creationQueued = false;
        pendingCreation = null;
        completedCreation = null;
        openItem(selected, false);
    }

    private void showLoadFailure(long generation, long expectedLifecycle, String detail) {
        ScreenManager.getInstance().execute(() -> {
            if (disposed || lifecycle != expectedLifecycle || !refreshGate.current(generation)) {
                return;
            }
            loaded = false;
            listMessage = "Could Not Load Definitions";
            listDetail = detail == null || detail.isBlank() ? "Retrying Automatically"
                : detail + " · Retrying Automatically";
            rebuildNavigation();
            updateActionState();
        });
    }

    private static boolean sameNavigation(Map<String, List<Item>> current, Map<String, List<Item>> next) {
        for (String resourceType : SUBRESOURCE_TYPES) {
            List<Item> currentItems = current.getOrDefault(resourceType, List.of());
            List<Item> nextItems = next.getOrDefault(resourceType, List.of());
            if (currentItems.size() != nextItems.size()) {
                return false;
            }
            for (int index = 0; index < currentItems.size(); index++) {
                Item left = currentItems.get(index);
                Item right = nextItems.get(index);
                if (!left.id().equals(right.id()) || !left.name().equals(right.name())
                    || !definitionDescription(resourceType, left).equals(definitionDescription(resourceType, right))
                    || !left.diagnostic().equals(right.diagnostic())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean currentBlockedState(PreparedScan prepared) {
        if (prepared.blockedId().isBlank()) {
            return prepared.blockedLease() == null;
        }
        try {
            return prepared.blockedLease() != null ? prepared.blockedLease().isCurrent()
                : prepared.manager().snapshotResource(serverId, prepared.blockedType(), prepared.blockedId()) == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Item item(String id) {
        return items.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
    }

    private boolean currentDescriptor(FlowManager manager, ManagedResourceCatalog.Descriptor expected) {
        try {
            ManagedResourceCatalog.Descriptor current = manager != null
                ? manager.managedResourceDescriptor(serverId, type) : null;
            return Objects.equals(current, expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean currentMembership() {
        FlowManager manager = FlowManager.getInstance();
        return manager != null && completeType(membership, type)
            && manager.isCurrentTypedResourceMembership(membership);
    }

    private ReSyncValueTypeCatalog.Snapshot currentTypeCatalog() {
        ReSyncValueTypeCatalog.Snapshot catalog = typeCatalog;
        if (catalog != null && currentTypeCatalog(catalog)) {
            return catalog;
        }
        FlowManager manager = FlowManager.getInstance();
        ReSyncValueTypeCatalog.Snapshot active = manager != null
            ? ReSyncValueTypeCatalog.active(manager.existingFlowClient(serverId)) : null;
        if (active != null) {
            typeCatalog = active;
        }
        return active;
    }

    private boolean currentTypeCatalog(ReSyncValueTypeCatalog.Snapshot catalog) {
        if (!requiresValueCatalog(type)) {
            FlowManager manager = FlowManager.getInstance();
            return catalog == null || manager != null
                && ReSyncValueTypeCatalog.current(manager.existingFlowClient(serverId), catalog);
        }
        FlowManager manager = FlowManager.getInstance();
        if (manager == null) {
            return false;
        }
        if (catalog == null) {
            return ReSyncValueTypeCatalog.active(manager.existingFlowClient(serverId)) == null;
        }
        return ReSyncValueTypeCatalog.current(manager.existingFlowClient(serverId), catalog);
    }

    private void showValueTypeSelector(AnimatedButton anchor) {
        ReSyncValueTypeCatalog.Snapshot catalog = currentTypeCatalog();
        if (catalog == null) {
            new Notification("Value Types", "Value Types Are Still Loading", Notification.Type.WARN);
            return;
        }
        List<String> values = AutomationDefinitionDraft.valueTypeOptions(catalog.dataTypes());
        if (values.isEmpty()) {
            new Notification("Value Types", "No Value Types Are Available", Notification.Type.WARN);
            return;
        }
        closeValueTypeSelector();
        ItemSelectorWidget[] selector = new ItemSelectorWidget[1];
        int selectorWidth = Math.clamp(width * 36 / 100, 340, 480);
        int selectorHeight = Math.clamp(height - header().headerSize - 100, 280, 520);
        ItemSelectorWidget.Builder builder = new ItemSelectorWidget.Builder(this)
            .size(selectorWidth, selectorHeight)
            .dismissOnSelect(true).emptyMessage("No Matching Value Types")
            .onClose(() -> {
                if (valueTypeSelector == selector[0]) {
                    valueTypeSelector = null;
                }
            });
        for (String value : values) {
            builder.addItem(value, "Use " + value, value, () -> selectValueType(value));
        }
        selector[0] = builder.build();
        selector[0].setSelectedItem(raw("valueType"));
        valueTypeSelector = selector[0];
        valueTypeSelector.show(valueTypeSelectorX(), valueTypeSelectorY());
    }

    private int valueTypeSelectorX() {
        if (valueTypeSelector == null || valueTypeBrowse == null) {
            return 8;
        }
        return Math.clamp(valueTypeBrowse.getX(), 8, Math.max(8, width - valueTypeSelector.getWidth() - 8));
    }

    private int valueTypeSelectorY() {
        if (valueTypeSelector == null || valueTypeBrowse == null) {
            return header().headerSize + 5;
        }
        int preferred = valueTypeBrowse.getY() + valueTypeBrowse.getHeight() + 2;
        return Math.clamp(preferred, header().headerSize + 5,
            Math.max(header().headerSize + 5, height - valueTypeSelector.getHeight() - 8));
    }

    private void selectValueType(String value) {
        updateField("valueType", value);
        if (valueTypeBrowse != null) {
            valueTypeBrowse.setMessage(valueTypeLabel(value));
        }
    }

    private void closeValueTypeSelector() {
        ItemSelectorWidget selector = valueTypeSelector;
        valueTypeSelector = null;
        if (selector != null) {
            selector.hide();
        }
    }

    static DefinitionValidation validateDefinition(String type, String id, JsonObject document,
                                                   Collection<FlowDataType> dataTypes) {
        try {
            JsonObject exact = AutomationDefinitionDraft.prepare(type, id, document, dataTypes).document();
            return new DefinitionValidation(exact, "");
        } catch (RuntimeException exception) {
            return new DefinitionValidation(document, message(exception));
        }
    }

    static List<String> targetIds(FlowManager.TypedResourceMembershipSnapshot membership, boolean current,
                                  String targetType, String selected) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("select_target");
        if (current && TARGET_TYPES.contains(targetType) && completeType(membership, targetType)) {
            membership.resources().stream().filter(resource -> targetType.equals(resource.type()))
                .map(FlowManager.ProjectResource::id).sorted(String.CASE_INSENSITIVE_ORDER).forEach(values::add);
        }
        if (selected != null && !selected.isBlank()) {
            values.add(selected);
        }
        return List.copyOf(values);
    }

    static List<String> runTargets(FlowManager.TypedResourceMembershipSnapshot membership, boolean current,
                                   String targetType, String selectedId) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("select_target");
        if (current && membership != null) {
            List<String> keys = new ArrayList<>();
            for (FlowManager.ProjectResource resource : membership.resources()) {
                if (TARGET_TYPES.contains(resource.type()) && completeType(membership, resource.type())) {
                    keys.add(runTargetKey(resource.type(), resource.id()));
                }
            }
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            values.addAll(keys);
        }
        String selected = runTargetKey(targetType, selectedId);
        if (!selected.isBlank() && !"select_target".equals(selected)) {
            values.add(selected);
        }
        return List.copyOf(values);
    }

    static String runTargetKey(String targetType, String selectedId) {
        if (selectedId == null || selectedId.isBlank() || "select_target".equals(selectedId)) {
            return "select_target";
        }
        String type = targetType == null || targetType.isBlank() ? "function" : targetType;
        return type + ":" + selectedId;
    }

    static boolean completeType(FlowManager.TypedResourceMembershipSnapshot membership, String type) {
        return membership != null && type != null && membership.completeTypes().contains(type);
    }

    static boolean requiresValueCatalog(String type) {
        return AutomationDefinitionDraft.VARIABLE.equals(type);
    }

    private String runTargetLabel(String value) {
        if (value == null || value.isBlank() || "select_target".equals(value)) {
            return "Select What To Run";
        }
        int split = value.indexOf(':');
        if (split <= 0 || split == value.length() - 1) {
            return value;
        }
        return optionLabel("targetType", value.substring(0, split)) + " · " + value.substring(split + 1);
    }

    private Map<String, String> rawValues(JsonObject source, boolean includeId) {
        Map<String, String> values = new LinkedHashMap<>();
        if (includeId) {
            values.put("id", text(source, "id"));
        }
        for (String field : AutomationDefinitionDraft.fields(type)) {
            values.put(field, AutomationDefinitionDraft.text(type, source, field));
        }
        return values;
    }

    private boolean dirty() {
        return document != null && (baselineDocument == null || !document.equals(baselineDocument));
    }

    private String currentId() {
        return creating ? raw("id").trim() : activeId;
    }

    private String raw(String field) {
        if (rawValues.containsKey(field)) {
            return rawValues.get(field);
        }
        if (document == null) {
            return "";
        }
        return "id".equals(field) ? text(document, field) : AutomationDefinitionDraft.text(type, document, field);
    }

    private static void addRow(Setting setting, String id, AnimatedWidget widget) {
        addRow(setting, id, Math.max(1, widget.getHeight()), widget);
    }

    private static void addRow(Setting setting, String id, int height, AnimatedWidget widget) {
        setting.addRow(row(id, height, widget));
    }

    private static PopupWidget.PopupRow row(String id, int height, AnimatedWidget widget) {
        return new PopupWidget.PopupRow.Builder("", widget).id(id).minHeight(height).build();
    }

    private static String text(JsonObject value, String field) {
        return value != null && value.has(field) && !value.get(field).isJsonNull()
            && value.get(field).isJsonPrimitive() ? value.get(field).getAsString() : "";
    }

    private static String name(JsonObject value, String fallback) {
        String name = text(value, "name");
        if (name.isBlank()) {
            name = text(value, "displayName");
        }
        return name.isBlank() ? fallback : name;
    }

    private static String singularName(String type) {
        return switch (type) {
            case AutomationDefinitionDraft.VARIABLE -> "Variable";
            case AutomationDefinitionDraft.TIMER -> "Timer";
            case AutomationDefinitionDraft.SCHEDULE -> "Schedule";
            case AutomationDefinitionDraft.COMPONENT_BUILDER -> "Component Builder";
            default -> "Automation";
        };
    }

    private static String pluralName(String type) {
        return singularName(type) + "s";
    }

    private static String typeDescription(String type) {
        return switch (type) {
            case AutomationDefinitionDraft.VARIABLE -> "Reusable values that flows can get and set.";
            case AutomationDefinitionDraft.TIMER -> "Named countdowns you start, pause, and check. Use these for minigames, cooldowns, and timed states.";
            case AutomationDefinitionDraft.SCHEDULE -> "Reusable timed jobs that run a Function, Flow, or Command after a delay, at a time, on a repeat, or on a cron pattern.";
            case AutomationDefinitionDraft.COMPONENT_BUILDER -> "Reusable item component templates for dynamic, category, tag, or item targets.";
            default -> "Sub Resources";
        };
    }

    private static String definitionDescription(String type, Item item) {
        if (item == null) {
            return typeDescription(type);
        }
        JsonObject document = item.document();
        String description = text(document, "description");
        if (!description.isBlank()) {
            return description;
        }
        return switch (type) {
            case AutomationDefinitionDraft.VARIABLE -> {
                String valueType = AutomationDefinitionDraft.text(type, document, "valueType");
                String defaultValue = AutomationDefinitionDraft.text(type, document, "defaultValue");
                yield valueType.isBlank() ? typeDescription(type) : optionLabel(valueType)
                    + (defaultValue.isBlank() ? "" : " · Default " + defaultValue);
            }
            case AutomationDefinitionDraft.TIMER -> {
                String duration = AutomationDefinitionDraft.text(type, document, "defaultDuration");
                String unit = optionLabel(AutomationDefinitionDraft.text(type, document, "defaultUnit"));
                String tick = AutomationDefinitionDraft.text(type, document, "tickInterval");
                String base = duration.isBlank() ? typeDescription(type) : duration + (unit.isBlank() ? "" : " " + unit);
                yield tick.isBlank() || "0".equals(tick) ? base : base + " · Tick Every " + tick;
            }
            case AutomationDefinitionDraft.SCHEDULE -> {
                String targetType = optionLabel(AutomationDefinitionDraft.text(type, document, "targetType"));
                String targetId = AutomationDefinitionDraft.text(type, document, "targetId");
                String timing = optionLabel(AutomationDefinitionDraft.text(type, document, "timingMode"));
                String target = targetId.isBlank() ? targetType : targetType + " " + targetId;
                yield target.isBlank() ? timing.isBlank() ? typeDescription(type) : timing
                    : timing.isBlank() ? target : target + " · " + timing;
            }
            case AutomationDefinitionDraft.COMPONENT_BUILDER -> {
                String kind = optionLabel(AutomationDefinitionDraft.text(type, document, "scopeKind"));
                String value = AutomationDefinitionDraft.text(type, document, "scopeValue");
                int count = document.has("components") && document.get("components").isJsonObject()
                    ? document.getAsJsonObject("components").size() : 0;
                yield (value.isBlank() ? kind : kind + " " + value) + " · " + count
                    + (count == 1 ? " Component" : " Components");
            }
            default -> typeDescription(type);
        };
    }

    static String optionLabel(String value) {
        return optionLabel("", value);
    }

    static String optionLabel(String field, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String key = (field == null ? "" : field) + ":" + value;
        return switch (key) {
            case "overlapPolicy:skip" -> "Skip This Run";
            case "overlapPolicy:queue" -> "Wait In Line";
            case "overlapPolicy:parallel" -> "Run At The Same Time";
            case "overlapPolicy:replace" -> "Stop The Current Run";
            case "existingTaskPolicy:replace" -> "Restart The Active Task";
            case "existingTaskPolicy:keep" -> "Keep The Active Task";
            case "existingTaskPolicy:fail" -> "Refuse A Duplicate Start";
            case "failurePolicy:continue" -> "Keep Repeating";
            case "failurePolicy:stop" -> "Stop After Failure";
            case "offlinePolicy:wait" -> "Wait For The Player";
            case "offlinePolicy:skip" -> "Skip While Offline";
            case "offlinePolicy:run_without_player" -> "Run Without The Player";
            case "offlinePolicy:cancel" -> "Cancel The Task";
            case "missedRunPolicy:run_once" -> "Catch Up Once";
            case "missedRunPolicy:skip" -> "Skip Missed Runs";
            case "missedRunPolicy:cancel" -> "Cancel After A Miss";
            case "scope:flow" -> "This Flow";
            case "scope:server" -> "Whole Server";
            case "scope:player" -> "Each Player";
            case "scope:entity" -> "Each Entity";
            case "scope:network" -> "Whole Network";
            case "targetType:flow" -> "Flow";
            case "targetType:function" -> "Function";
            case "targetType:command" -> "Command";
            case "timingMode:after_delay" -> "After A Delay";
            case "timingMode:at_time" -> "At A Date And Time";
            case "timingMode:repeating" -> "Repeating";
            case "timingMode:cron" -> "Cron Pattern";
            default -> titleCase(value);
        };
    }

    private static String titleCase(String value) {
        String[] words = value.split("_", -1);
        for (String word : words) {
            if (word.isEmpty()) {
                return value;
            }
        }
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private static String valueTypeLabel(String value) {
        String label = FlowDataType.fromString(value).getDisplayName();
        return label == null || label.isBlank() ? "Select Type" : label;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String fieldLabel(String field) {
        return switch (field) {
            case "id" -> "ID";
            case "valueType" -> "Value Type";
            case "defaultValue" -> "Default Value";
            case "defaultDuration" -> "Default Duration";
            case "defaultUnit" -> "Default Unit";
            case "tickInterval" -> "Tick Interval";
            case "targetId" -> "Run This";
            case "timingMode" -> "Timing Mode";
            case "initialDelay" -> "Initial Delay";
            case "dateTime" -> "Date And Time";
            case "timeZone" -> "Time Zone";
            case "overlapPolicy" -> "This Run";
            case "existingTaskPolicy" -> "This Task";
            case "failurePolicy" -> "After Failure";
            case "offlinePolicy" -> "While Offline";
            case "missedRunPolicy" -> "Missed Run";
            case "scope" -> "Scope";
            case "scopeKind" -> "Applies To";
            case "scopeValue" -> "Target";
            default -> optionLabel(field);
        };
    }

    private static String fieldDescription(String field) {
        return switch (field) {
            case "id" -> "The name other nodes use to start or check this definition.";
            case "name" -> "The name shown in Studio.";
            case "description" -> "A short note about what this is for.";
            case "valueType" -> "The type of value this variable stores.";
            case "defaultValue" -> "Returned before anything has set this variable.";
            case "defaultDuration" -> "How long the timer runs when Start does not pass a duration.";
            case "defaultUnit" -> "Unit for the default duration and tick interval.";
            case "tickInterval" -> "Optional heartbeat. Zero means no tick events.";
            case "targetId" -> "Choose the Function, Flow, or Command this schedule runs. Create that resource once and reuse it from any schedule.";
            case "timingMode" -> "Choose when this schedule should fire.";
            case "duration" -> "How long to wait, or how often a repeating schedule runs.";
            case "unit" -> "Unit for the wait or repeat interval.";
            case "initialDelay" -> "Optional wait before the first repeating run.";
            case "dateTime" -> "The date and time of a one-time run.";
            case "timeZone" -> "Time zone used for calendar times and cron patterns.";
            case "cron" -> "Cron pattern that chooses run times.";
            case "scope" -> "Who owns each live instance. Server is one shared timer. Player is one per player.";
            case "scopeKind" -> "Choose whether this template applies dynamically or to a category, tag, or item.";
            case "scopeValue" -> "The category, item tag, or exact item this template supports.";
            case "persistent" -> "Keep the live state after a server restart.";
            case "overlapPolicy" -> "What to do if this schedule wants to fire while a previous run is still going.";
            case "existingTaskPolicy" -> "What to do if this schedule is started again while it is already active.";
            case "failurePolicy" -> "What to do after a repeating run fails.";
            case "offlinePolicy" -> "What to do when the owning player is offline.";
            case "missedRunPolicy" -> "What to do if the server was down or paused through a planned run.";
            default -> "";
        };
    }

    private static String message(RuntimeException exception) {
        return exception != null && exception.getMessage() != null && !exception.getMessage().isBlank()
            ? exception.getMessage() : "Definition Is Invalid";
    }

    record DefinitionValidation(JsonObject document, String diagnostic) {
        DefinitionValidation {
            document = document != null ? document.deepCopy() : new JsonObject();
            diagnostic = diagnostic != null ? diagnostic : "";
        }

        @Override
        public JsonObject document() {
            return document.deepCopy();
        }
    }

    static final class RefreshGate {
        private long generation;
        private boolean running;
        private boolean pending;

        synchronized long request() {
            if (running) {
                pending = true;
                return 0L;
            }
            running = true;
            return ++generation;
        }

        synchronized boolean continueAfter(long expectedGeneration) {
            if (!running || generation != expectedGeneration) {
                return false;
            }
            if (pending) {
                pending = false;
                return true;
            }
            running = false;
            return false;
        }

        synchronized boolean current(long expectedGeneration) {
            return generation == expectedGeneration;
        }

        synchronized void cancel(long expectedGeneration) {
            if (generation == expectedGeneration) {
                running = false;
                pending = false;
            }
        }

        synchronized void close() {
            generation++;
            running = false;
            pending = false;
        }
    }

    private record Item(String id, String name, String description, String folder, String diagnostic,
                        JsonObject document, FlowManager.ResourceReadLease lease) {
        private Item {
            document = document.deepCopy();
            description = description != null ? description : "";
            folder = folder != null ? folder : "";
            diagnostic = diagnostic != null ? diagnostic : "";
        }

        @Override
        public JsonObject document() {
            return document.deepCopy();
        }

        private String searchText() {
            return (id + " " + name + " " + description + " " + folder).toLowerCase(Locale.ROOT);
        }
    }

    private enum ScanState {
        READY,
        PENDING,
        ERROR
    }

    private record PreparedScan(FlowManager manager, FlowManager.TypedResourceMembershipSnapshot membership,
                                ReSyncValueTypeCatalog.Snapshot typeCatalog,
                                ManagedResourceCatalog.Descriptor descriptor, Map<String, List<Item>> itemsByType,
                                ScanState state, String message, String blockedType, String blockedId,
                                FlowManager.ResourceReadLease blockedLease) {
        private PreparedScan {
            if (itemsByType == null || itemsByType.isEmpty()) {
                itemsByType = Map.of();
            } else {
                Map<String, List<Item>> copy = new LinkedHashMap<>();
                itemsByType.forEach((resourceType, items) -> copy.put(resourceType,
                    items == null ? List.of() : List.copyOf(items)));
                itemsByType = Map.copyOf(copy);
            }
            state = Objects.requireNonNull(state);
            message = message != null ? message : "";
            blockedType = blockedType != null ? blockedType : "";
            blockedId = blockedId != null ? blockedId : "";
        }
    }

    private record FieldRow(Setting setting, String rowId, MountableButtonWidget widget) {
    }

    private static final class NavigationEntryWidget extends MountableButtonWidget {
        private NavigationEntryWidget(String title, String detail, String badge, Runnable action) {
            super(title, "", detail, BrowserSafeState.list(), null);
            setSize(NAVIGATION_WIDTH - 12, 30);
            setHiddenText(badge);
            setOnClick(action);
            animateElevation = false;
            elevateOnFocused = false;
            roundedCorners = false;
            selectable = true;
            enableHoverColors = true;
        }
    }

    private record ContextKey(String type, String id, boolean creating) {
        private static ContextKey definition(String type, String id) {
            return new ContextKey(type, id, false);
        }

        private static ContextKey creation(String type) {
            return new ContextKey(type, "", true);
        }
    }

    private record EditorState(String type, String id, String folder, boolean creating, JsonObject document,
                               JsonObject baselineDocument, Map<String, String> rawValues,
                               Map<String, String> baselineValues, FlowManager.ResourceReadLease lease,
                               Consumer<ReSyncResourceCreator.Result> completion) {
        private EditorState {
            document = document.deepCopy();
            baselineDocument = baselineDocument != null ? baselineDocument.deepCopy() : null;
            rawValues = Map.copyOf(rawValues);
            baselineValues = Map.copyOf(baselineValues);
        }

        @Override
        public JsonObject document() {
            return document.deepCopy();
        }

        @Override
        public JsonObject baselineDocument() {
            return baselineDocument != null ? baselineDocument.deepCopy() : null;
        }

        private boolean dirty() {
            return !rawValues.equals(baselineValues);
        }
    }

    private record PendingCreation(long lifecycle, String id, AutomationDefinitionDraft.Prepared prepared) {
        private PendingCreation {
            id = id != null ? id : "";
            prepared = Objects.requireNonNull(prepared);
        }
    }

    private record SaveAcknowledgement(long sequence, long lifecycle, FlowManager.ResourceReadLease lease,
                                       BrowserSafeState.ReferenceValue<String> authoritative, Map<String, String> submittedValues,
                                       Runnable afterSave) {
    }

    private record Validation(AutomationDefinitionDraft.Prepared prepared, String message) {
        private static Validation valid(AutomationDefinitionDraft.Prepared prepared) {
            return new Validation(Objects.requireNonNull(prepared), "");
        }

        private static Validation invalid(String message) {
            return new Validation(null, message != null && !message.isBlank() ? message : "Definition Is Invalid");
        }

        private boolean valid() {
            return prepared != null;
        }
    }
}
