package redxax.oxy.remotely.flow.ui;

import redxax.oxy.remotely.util.BrowserSafeState;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.CoreGraphEditorSession;
import redxax.oxy.remotely.data.flow.CoreGraphUiProjection;
import redxax.oxy.remotely.data.flow.CoreGraphAuthoringAdapter;
import redxax.oxy.remotely.data.flow.CustomContentCoreEditor;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationProjection;
import redxax.oxy.remotely.data.flow.ReSyncGenericDescriptorProjection;
import redxax.oxy.remotely.data.flow.ReSyncResourceRevisionReconciler;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncGenericWidgetCapabilities;
import redxax.oxy.remotely.data.flow.SyncedResourceState;
import redxax.oxy.remotely.flow.data.CustomAbilityBinding;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import redxax.oxy.remotely.flow.ui.studio.ReSyncStudioPanelState;
import redxax.oxy.remotely.flow.ui.studio.StudioDocumentLifecycleScreen;
import redxax.oxy.remotely.flow.ui.studio.StudioPanel;
import redxax.oxy.remotely.flow.ui.studio.StudioSelectorView;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;
import restudio.rebase.ui.widgets.editor.CodeEditorWidget;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.platform.input.ReScrollEvent;
import restudio.rescreen.platform.input.ReTextInputEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.SidePanel;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.RowWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;
import restudio.resync.flow.workspace.WorkspacePatch;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class ContentDesignerScreen extends GraphEditorScreen implements StudioDocumentLifecycleScreen, StudioSelectorView {
    private static final Gson COLLABORATION_GSON = new Gson();
    private final String flowId;
    private String contentId;
    private final FlowManager flowManager;
    private CoreGraphEditorSession contentCoreSession;
    private CustomContentDefinition contentCoreAuthority;
    private FlowManager.CustomContentAuthority contentAuthorityToken;
    private CatalogAuthoringPublication contentCorePublication;
    private CatalogAuthoringPublication contentObservedPublication;
    private long contentObservedGeneration = -1L;
    private ReSyncResourceRevisionReconciler.Stamp contentObservedStamp;
    private boolean contentCorePreparationPending;
    private boolean contentCoreClosed;
    private long contentCoreGeneration;
    private String contentCoreIssue = "Editor Loading";
    private ContentSave pendingContentSave;
    private final java.util.Queue<Runnable> contentCoreCompletions = BrowserSafeState.queue();
    private final Screen contentDesignerParent;
    private final boolean quickEditMode;
    private final String quickEditSessionId;
    private final List<CustomAbilityBinding> quickEditOriginalAbilities;
    private final Map<String, Object> quickEditOriginalComponents;
    private final ReSyncStudioPanelState panelState = new ReSyncStudioPanelState();
    private volatile ContentPanelSnapshot contentPanelSnapshot;
    private volatile AttributeProjection contentAttributeProjection;
    private StudioPanel contentStudioPanel;
    private StudioPanel attributeDesignerPanel;
    private SidePanel contentPanel;
    private SidePanel attributePanel;
    private String selectedBranch = "";
    private String selectedAttributeComponent = "";
    private MountableButtonWidget summaryWidget;
    private Map<String, MountableButtonWidget> eventRows = new HashMap<>();
    private Deque<AnimatedWidget> contentPanelWidgets = new ArrayDeque<>();
    private final Deque<Deque<AnimatedWidget>> contentPanelRetirements = new ArrayDeque<>();
    private Map<String, Consumer<Object>> contentCollaborationBindings = new LinkedHashMap<>();
    private final List<AnimatedWidget> attributePanelWidgets = new ArrayList<>();
    private List<DropDownWidget<String>> panelDropdowns = new ArrayList<>();
    private Map<String, DropDownWidget<String>> contentDropdowns = new LinkedHashMap<>();
    private Deque<Runnable> contentPanelDiffs = new ArrayDeque<>();
    private long contentPanelRequestVersion;
    private volatile long contentPanelCatalogVersion;
    private long contentPanelValidationVersion;
    private long contentPanelDiffMutationVersion;
    private long contentPanelDiffRequestVersion;
    private String contentPanelKey = "";
    private boolean contentPanelRefreshPending;
    private boolean contentPanelStructurePending;
    private List<AnimatedWidget> collectingAttributeEditorWidgets;
    private MountableButtonWidget attributeHeaderWidget;
    private AnimatedWidget selectedAttributeRowWidget;
    private TextInputWidget attributeSearchInput;
    private int attributePanelStaticWidgetCount;
    private boolean collectingAttributePanelOrder;
    private boolean attributeDesignerOpen;
    private boolean attributeDesignerHidContentBrowser;
    private boolean attributeRestoreSearchFocus;
    private String activeAttributeQuery = "";
    private Map<String, Object> activeAttributeComponents = new LinkedHashMap<>();
    private final Map<String, Object> attributePreviewValues = new LinkedHashMap<>();
    private final Set<String> editedAttributeComponents = new LinkedHashSet<>();
    private final Map<String, AttributeComponentRowState> attributeComponentRowStates = new LinkedHashMap<>();
    private final Map<String, MountableButtonWidget> attributeSectionRows = new LinkedHashMap<>();
    private final Map<String, MountableButtonWidget> attributeStatusRows = new LinkedHashMap<>();
    private List<Map<String, Object>> attributeValidationErrors = List.of();
    private static final String ATTRIBUTE_SCHEMA_SOURCE = "server:minecraft:item_attribute_schema";
    private static final OptionCatalogLoader.Profile CONTENT_CATALOGS = OptionCatalogLoader.profile(
        "server:minecraft:material", "server:minecraft:world", "server:custom_content:provider",
        "server:minecraft:block");
    private static final Set<String> CONTENT_PANEL_CATALOG_SOURCES = Set.of(
        "server:minecraft:attribute", "server:minecraft:material", "server:minecraft:world",
        "server:custom_content:asset", "server:custom_content:provider");
    private static final Set<String> STUDIO_ROOT_INPUTS = Set.of(
        "content_id",
        "name",
        "material",
        "provider",
        "external_id",
        "custom_model_data",
        "components",
        "lore",
        "tags",
        "enabled",
        "priority",
        "cooldown_scope",
        "cooldown_ticks",
        "permission",
        "cancel_event",
        "consume_event",
        "require_sneaking",
        "require_on_ground",
        "hand_filter",
        "target_filter",
        "allowed_worlds",
        "denied_worlds",
        "chance_percent",
        "max_activations_per_tick",
        CustomContentGraphAdapter.FLOW_BRANCHES_KEY
    );

    private record ContentPanelSnapshot(CustomContentDefinition definition, String type, String key,
                                        long generation, long mutationVersion, long requestVersion, long catalogVersion,
                                        long validationVersion, List<String> branches, Map<String, Integer> actionCounts,
                                        List<String> providers, List<String> materials, List<String> providerAssets,
                                        AttributeProjection attributeProjection) {
    }

    private record ContentSave(GraphDocument document, DesignerSaveNotifications.SaveTicket ticket, boolean acknowledged) {
    }

    private record ContentPreparation(long generation, long cacheGeneration, CoreGraphEditorSession previous,
                                      GraphDocument previousGraph, FlowManager.CustomContentAuthority authority,
                                      ReSyncResourceRevisionReconciler.Stamp stamp,
                                      CustomContentDefinition definition, CatalogAuthoringPublication publication,
                                      CoreGraphEditorSession session, ContentSave submitted, String issue) {
    }

    private record AttributeProjection(String type, String displayName, String material, String schemaSource,
                                       List<String> lore, Map<String, Object> components,
                                       Map<String, OptionCatalogItem> catalog, Map<String, String> componentLabels,
                                       Map<String, Map<String, Object>> componentSchemas,
                                       Map<String, OptionCatalogItem> catalogByKey,
                                       Map<String, String> componentLabelsByKey,
                                       Map<String, Map<String, Object>> componentSchemasByKey,
                                       Map<String, OptionCatalogItem> attributeValueCatalogByKey) {
        private AttributeProjection(String type, String displayName, String material, String schemaSource,
                                     List<String> lore, Map<String, Object> components,
                                     Map<String, OptionCatalogItem> catalog, Map<String, String> componentLabels,
                                     Map<String, Map<String, Object>> componentSchemas) {
            this(type, displayName, material, schemaSource, lore, components, catalog, componentLabels, componentSchemas,
                null, null, null, null);
        }

        private AttributeProjection(String type, String displayName, String material, String schemaSource,
                                     List<String> lore, Map<String, Object> components,
                                     Map<String, OptionCatalogItem> catalog, Map<String, String> componentLabels,
                                     Map<String, Map<String, Object>> componentSchemas,
                                     Map<String, OptionCatalogItem> attributeValueCatalogByKey) {
            this(type, displayName, material, schemaSource, lore, components, catalog, componentLabels, componentSchemas,
                null, null, null, attributeValueCatalogByKey);
        }

        private AttributeProjection {
            type = type != null ? type : "";
            displayName = displayName != null ? displayName : "";
            material = material != null ? material : "";
            schemaSource = schemaSource != null ? schemaSource : ATTRIBUTE_SCHEMA_SOURCE;
            lore = freezeProjectionList(lore);
            components = freezeProjectionMap(components);
            catalog = freezeCatalog(catalog);
            componentLabels = componentLabels == null ? labelsForCatalog(catalog) : freezeProjectionStringMap(componentLabels);
            componentSchemas = componentSchemas == null ? schemasForCatalog(catalog) : freezeProjectionSchemas(componentSchemas);
            catalogByKey = catalogByKey == null ? catalogIndex(catalog) : freezeCatalog(catalogByKey);
            componentLabelsByKey = componentLabelsByKey == null ? normalizedLabels(componentLabels) : freezeProjectionStringMap(componentLabelsByKey);
            componentSchemasByKey = componentSchemasByKey == null ? normalizedSchemas(componentSchemas) : freezeProjectionSchemas(componentSchemasByKey);
            attributeValueCatalogByKey = attributeValueCatalogByKey == null ? Map.of() : freezeCatalog(attributeValueCatalogByKey);
        }

        private AttributeProjection withComponents(Map<String, Object> nextComponents) {
            return new AttributeProjection(type, displayName, material, schemaSource, lore, nextComponents, catalog,
                componentLabels, componentSchemas, attributeValueCatalogByKey);
        }

        private AttributeProjection withMaterial(String nextMaterial, String nextSchemaSource) {
            return new AttributeProjection(type, displayName, nextMaterial, nextSchemaSource, lore, components,
                Map.of(), null, null, Map.of());
        }

        private AttributeProjection withDisplayName(String nextDisplayName) {
            return new AttributeProjection(type, nextDisplayName, material, schemaSource, lore, components, catalog,
                componentLabels, componentSchemas, attributeValueCatalogByKey);
        }

        private AttributeProjection withLore(List<String> nextLore) {
            return new AttributeProjection(type, displayName, material, schemaSource, nextLore, components, catalog,
                componentLabels, componentSchemas, attributeValueCatalogByKey);
        }

        private AttributeProjection withType(String nextType) {
            return new AttributeProjection(nextType, displayName, material, schemaSource, lore, components, catalog,
                componentLabels, componentSchemas, attributeValueCatalogByKey);
        }
    }

    private static List<String> freezeProjectionList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static Map<String, Object> freezeProjectionMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), freezeProjectionValue(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> freezeProjectionStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Map<String, Object>> freezeProjectionSchemas(Map<String, Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), freezeProjectionMap(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, OptionCatalogItem> freezeCatalog(Map<String, OptionCatalogItem> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, OptionCatalogItem> copy = new LinkedHashMap<>();
        for (Map.Entry<String, OptionCatalogItem> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), freezeCatalogItem(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static OptionCatalogItem freezeCatalogItem(OptionCatalogItem item) {
        OptionCatalogItem copy = new OptionCatalogItem();
        copy.setValue(item.getValue());
        copy.setLabel(item.getLabel());
        copy.setDescription(item.getDescription());
        copy.setIcon(item.getIcon());
        copy.setGroup(item.getGroup());
        copy.setMetadata(freezeProjectionMap(item.getMetadata()));
        return copy;
    }

    private static Object freezeProjectionValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(entry.getKey().toString(), freezeProjectionValue(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(freezeProjectionValue(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static Map<String, String> labelsForCatalog(Map<String, OptionCatalogItem> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (OptionCatalogItem item : catalog.values()) {
            if (item != null && item.getValue() != null && !item.getValue().isBlank()) {
                labels.put(item.getValue(), item.getLabel());
            }
        }
        return Collections.unmodifiableMap(labels);
    }

    private static Map<String, OptionCatalogItem> catalogIndex(Map<String, OptionCatalogItem> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return Map.of();
        }
        Map<String, OptionCatalogItem> index = new LinkedHashMap<>();
        for (Map.Entry<String, OptionCatalogItem> entry : catalog.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                index.putIfAbsent(normalizedProjectionKey(entry.getKey()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, String> normalizedLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }
        Map<String, String> index = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                index.putIfAbsent(normalizedProjectionKey(entry.getKey()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, Map<String, Object>> schemasForCatalog(Map<String, OptionCatalogItem> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (OptionCatalogItem item : catalog.values()) {
            if (item == null || item.getValue() == null || item.getValue().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = item.getMetadata();
            schemas.put(item.getValue(), schemaMapForProjection(metadata.get("schema")));
        }
        return Collections.unmodifiableMap(schemas);
    }

    private static Map<String, Map<String, Object>> normalizedSchemas(Map<String, Map<String, Object>> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : schemas.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                index.putIfAbsent(normalizedProjectionKey(entry.getKey()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static String normalizedProjectionKey(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }

    private static Map<String, Object> schemaMapForProjection(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                schema.put(entry.getKey().toString(), freezeProjectionValue(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(schema);
    }

    public ContentDesignerScreen(String serverId, ClientServerView server, String flowId, Screen parent) {
        this(serverId, loadGraph(serverId, flowId), flowId, parent, false, "");
    }

    public ContentDesignerScreen(String serverId, FlowGraph graph, Screen parent) {
        this(serverId, graph, graph != null ? graph.getId() : "", parent, false, "");
    }

    private ContentDesignerScreen(String serverId, FlowGraph graph, String flowId, Screen parent, boolean quickEditMode, String quickEditSessionId) {
        this(serverId, graph, flowId, parent, quickEditMode, quickEditSessionId, List.of(), Map.of());
    }

    private ContentDesignerScreen(String serverId, FlowGraph graph, String flowId, Screen parent, boolean quickEditMode, String quickEditSessionId, List<CustomAbilityBinding> quickEditOriginalAbilities, Map<String, Object> quickEditOriginalComponents) {
        super(graph, serverId, parent);
        this.flowId = flowId;
        this.contentId = initialContentId(graph, flowId);
        this.contentDesignerParent = parent;
        this.quickEditMode = quickEditMode;
        this.quickEditSessionId = quickEditSessionId != null ? quickEditSessionId : "";
        this.quickEditOriginalAbilities = quickEditOriginalAbilities != null ? new ArrayList<>(quickEditOriginalAbilities) : List.of();
        this.quickEditOriginalComponents = quickEditOriginalComponents != null ? new LinkedHashMap<>(quickEditOriginalComponents) : Map.of();
        FlowManager manager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
        this.flowManager = manager != null ? manager : FlowManager.getInstance();
        setCoreGraphSaveHandler(this::saveContentCore);
    }

    @Override
    protected boolean ownsCoreGraphSession(CoreGraphEditorSession session) {
        return !contentCoreClosed && session != null && session == contentCoreSession;
    }

    @Override
    protected boolean ownsCoreNodeFields(GraphNode node, ReSyncGenericDescriptorProjection.Projection descriptor) {
        ReSyncFlowClient client = typedCatalogClient();
        ReSyncCatalogPublicationProjection.Snapshot catalog = client != null ? client.catalogPublicationProjection().active().orElse(null) : null;
        CatalogCachePublication.Entry entry = node != null && catalog != null ? catalog.entries().get(node.definition()) : null;
        return node != null && descriptor != null && ownsCoreGraphSession(contentCoreSession)
            && entry != null && !entry.tombstone() && !entry.opaque() && entry.state() == CatalogCacheState.ACTIVE
            && "restudio.resync".equals(node.definition().owner().canonicalText())
            && CustomContentGraphAdapter.typeFromNode(node.definition().id().canonicalText()) != null
            && descriptor.definitionKey().equals(node.definition())
            && (descriptor.status() == ReSyncGenericDescriptorProjection.Status.ACTIVE
                || descriptor.status() == ReSyncGenericDescriptorProjection.Status.READ_ONLY)
            && descriptor.fields().stream().allMatch(field -> field.editable() || ownedContentField(descriptor, field));
    }

    private static boolean ownedContentField(ReSyncGenericDescriptorProjection.Projection descriptor,
                                              ReSyncGenericDescriptorProjection.Field field) {
        if (!field.id().startsWith("pin:") || field.typeExpression().isBlank()) return false;
        String id = field.id().substring("pin:".length());
        return STUDIO_ROOT_INPUTS.contains(id) && descriptor.pin(id, true).isPresent()
            && Set.of("The descriptor does not declare a supported editor capability.",
                "The editor capability is not supported by this client.",
                "The declared input widget is not supported by this client.").contains(field.reason());
    }

    private void prepareContentCoreSession(boolean force) {
        if (contentCoreClosed || contentCorePreparationPending || flowManager == null || contentId == null || contentId.isBlank()) {
            return;
        }
        ReSyncFlowClient client = typedCatalogClient();
        CatalogAuthoringPublication publication = client != null ? client.activeCatalogAuthoringPublication().orElse(null) : null;
        ReSyncCatalogPublicationProjection.Snapshot catalog = client != null ? client.catalogPublicationProjection().active().orElse(null) : null;
        if (publication == null || catalog == null) {
            if (client != null && (client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION
                || client.catalogAuthority() == ReSyncFlowClient.CatalogAuthority.UNAVAILABLE)) {
                contentCoreIssue = "Catalog Is Still Loading";
            }
            return;
        }
        long cacheGeneration = quickEditMode ? 0L : flowManager.getCustomContentRevision(serverId, contentId);
        ReSyncResourceRevisionReconciler.Stamp stamp = quickEditMode ? null : flowManager.customContentStamp(serverId, contentId);
        if (!force && contentObservedGeneration == cacheGeneration && contentObservedPublication == publication
            && Objects.equals(stamp, contentObservedStamp)) {
            return;
        }
        if (contentCoreSession != null && hasPendingCoreMutation(contentCoreSession)) {
            return;
        }
        long generation = ++contentCoreGeneration;
        CoreGraphEditorSession previous = contentCoreSession;
        GraphDocument previousGraph = previous != null ? previous.graphDocument() : null;
        GraphDocument previousBaseline = previous != null ? previous.baselineGraphDocument() : null;
        CustomContentDefinition quickDefinition = quickEditMode ? contentCoreAuthority : null;
        ContentSave submitted = pendingContentSave;
        contentCorePreparationPending = true;
        if (quickEditMode && quickDefinition == null) {
            boolean accepted = submitFrozenWorkspaceGraph(frozen -> {
                buildContentCorePreparation(generation, cacheGeneration, previous, previousGraph, previousBaseline,
                    publication, catalog, submitted, CustomContentGraphAdapter.toDefinition(frozen));
                return true;
            }, completed -> {
                if (!completed && generation == contentCoreGeneration) contentCorePreparationPending = false;
            });
            if (!accepted) contentCorePreparationPending = false;
            return;
        }
        if (!submitLifecycleTask(() -> buildContentCorePreparation(generation, cacheGeneration, previous, previousGraph,
            previousBaseline, publication, catalog, submitted, quickDefinition))) {
            contentCorePreparationPending = false;
        }
    }

    private void buildContentCorePreparation(long generation, long cacheGeneration, CoreGraphEditorSession previous,
                                             GraphDocument previousGraph, GraphDocument previousBaseline,
                                             CatalogAuthoringPublication publication, ReSyncCatalogPublicationProjection.Snapshot catalog,
                                             ContentSave submitted, CustomContentDefinition quickDefinition) {
        FlowManager.CustomContentAuthority authority = null;
        ReSyncResourceRevisionReconciler.Stamp observedStamp = quickEditMode ? null : flowManager.customContentStamp(serverId, contentId);
        CustomContentDefinition definition = quickDefinition;
        CoreGraphEditorSession prepared = null;
        String issue = "";
        try {
            authority = quickEditMode ? null : flowManager.customContentAuthority(serverId, contentId);
            if (!quickEditMode && authority == null) {
                ReSyncResourceRevisionReconciler.Stamp stamp = flowManager.customContentStamp(serverId, contentId);
                throw new IllegalStateException(stamp != null && stamp.deleted() ? "Content Was Deleted" : "Content Is Still Loading");
            }
            definition = quickEditMode ? quickDefinition : authority.materialize();
            ServerResourceLocator resource = new ServerResourceLocator(catalog.publication().key().serverId(),
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(ReSyncResourceType.CUSTOM_CONTENT.typeId())), contentId);
            prepared = CustomContentCoreEditor.prepare(definition, resource, authority != null ? authority.result().revision() : 0L,
                publication, catalog.entries().values());
            if (previousGraph != null) {
                GraphDocument saved = submitted != null && authority != null
                    && acknowledgedContentSave(submitted, authority)
                    ? submitted.document() : null;
                CustomContentCoreEditor.rebase(prepared, previousBaseline, previousGraph, saved);
            }
        } catch (RuntimeException exception) {
            issue = exception.getMessage() == null ? "Content Editor Unavailable" : exception.getMessage();
        }
        ContentPreparation result = new ContentPreparation(generation, cacheGeneration, previous, previousGraph, authority,
            authority != null ? ReSyncResourceRevisionReconciler.Stamp.from(authority.result()) : observedStamp,
            definition, publication, prepared, submitted, issue);
        contentCoreCompletions.add(() -> applyContentCorePreparation(result));
    }

    private void applyContentCorePreparation(ContentPreparation result) {
        if (contentCoreClosed || result.generation() != contentCoreGeneration) {
            return;
        }
        contentCorePreparationPending = false;
        ReSyncFlowClient client = typedCatalogClient();
        if (client == null || client.activeCatalogAuthoringPublication().orElse(null) != result.publication()
            || result.submitted() != pendingContentSave
            || result.previous() != contentCoreSession
            || result.previous() != null && (result.previousGraph() != result.previous().graphDocument()
                || hasPendingCoreMutation(result.previous()))
            || result.authority() != null && !flowManager.isCurrentCustomContentAuthority(result.authority())) {
            return;
        }
        contentObservedGeneration = result.cacheGeneration();
        contentObservedPublication = result.publication();
        contentObservedStamp = result.stamp();
        if (!result.issue().isBlank()) {
            contentCoreIssue = result.issue();
            return;
        }
        boolean sameAuthority = contentCoreSession != null && contentCorePublication == result.publication()
            && (quickEditMode || contentAuthorityToken != null && result.authority() != null
                && contentAuthorityToken.result().revision() == result.authority().result().revision()
                && Objects.equals(contentAuthorityToken.result().payloadHash(), result.authority().result().payloadHash()));
        contentCoreIssue = "";
        contentCoreAuthority = result.definition();
        contentAuthorityToken = result.authority();
        contentCorePublication = result.publication();
        if (pendingContentSave != null && result.authority() != null
            && acknowledgedContentSave(pendingContentSave, result.authority())) {
            pendingContentSave = null;
        }
        if (!sameAuthority) {
            contentCoreSession = result.session();
            setCoreGraphEditorSession(contentCoreSession);
            contentPanelRefreshPending = true;
        }
    }

    private static boolean acknowledgedContentSave(ContentSave save, FlowManager.CustomContentAuthority authority) {
        return Objects.equals(save.ticket().mutationId(), authority.result().mutationId())
            || save.acknowledged() && authority.result().revision() > save.document().revision();
    }

    private boolean saveContentCore(CoreGraphEditorSession session, DesignerSaveNotifications.SaveTicket ticket) {
        ReSyncFlowClient client = typedCatalogClient();
        CatalogAuthoringPublication publication = client != null ? client.activeCatalogAuthoringPublication().orElse(null) : null;
        ReSyncCatalogPublicationProjection.Snapshot catalog = client != null ? client.catalogPublicationProjection().active().orElse(null) : null;
        if (!ownsCoreGraphSession(session) || quickEditMode || contentAuthorityToken == null || !contentCoreIssue.isBlank()
            || pendingContentSave != null || publication == null || catalog == null || publication != contentCorePublication) {
            return false;
        }
        GraphDocument document = session.graphDocument();
        CustomContentDefinition definition = contentCoreAuthority;
        FlowManager.CustomContentAuthority expected = contentAuthorityToken;
        ContentSave save = new ContentSave(document, ticket, false);
        pendingContentSave = save;
        ticket.whenFinished((saved, current) -> contentCoreCompletions.add(() -> {
            if (!contentCoreClosed) {
                if (pendingContentSave == save) {
                    pendingContentSave = saved ? new ContentSave(document, ticket, true) : null;
                }
                contentObservedGeneration = -1L;
                prepareContentCoreSession(true);
            }
        }));
        boolean accepted = submitLifecycleTask(() -> {
            try {
                CoreGraphEditorSession frozen = new CoreGraphEditorSession(document, session.activeAuthoringChecksum(),
                    session.authoringCapabilities(), session.editCapabilities());
                CustomContentDefinition content = CustomContentCoreEditor.materialize(frozen, definition, publication, catalog.entries().values());
                if (client.activeCatalogAuthoringPublication().orElse(null) != publication
                    || client.catalogPublicationProjection().active().orElse(null) != catalog) {
                    DesignerSaveNotifications.failExact(ticket, "Content Catalog Changed Before Save");
                    return;
                }
                if (!flowManager.saveCustomContent(serverId, content, ticket, expected)) {
                    DesignerSaveNotifications.failExact(ticket, "Content Save Rejected");
                }
            } catch (RuntimeException exception) {
                DesignerSaveNotifications.failExact(ticket, exception.getMessage() == null ? "Content Save Failed" : exception.getMessage());
            }
        });
        if (!accepted && pendingContentSave == save) pendingContentSave = null;
        return accepted;
    }

    private boolean submitContentGraph(Function<FlowGraph, Boolean> action, Consumer<Boolean> completion) {
        return submitContentGraph(action, completion, true);
    }

    private boolean submitContentGraph(Function<FlowGraph, Boolean> action, Consumer<Boolean> completion, boolean requireCurrent) {
        CoreGraphEditorSession session = contentCoreSession;
        if (session == null) {
            return submitFrozenWorkspaceGraph(action, completion);
        }
        if (hasPendingCoreMutation(session)) {
            return false;
        }
        CoreGraphUiProjection.EditorSnapshot snapshot = CoreGraphUiProjection.EditorSnapshot.capture(session);
        long generation = contentCoreGeneration;
        return submitLifecycleTask(() -> {
            boolean applied;
            try {
                CoreGraphUiProjection.ProjectionResult projection = new CoreGraphUiProjection().projectEditorSnapshot(snapshot);
                applied = projection.complete() && Boolean.TRUE.equals(action.apply(projection.graph()));
            } catch (RuntimeException exception) {
                applied = false;
            }
            boolean result = applied;
            contentCoreCompletions.add(() -> {
                if (!contentCoreClosed && (!requireCurrent || generation == contentCoreGeneration)) {
                    completion.accept(result && (!requireCurrent || snapshot.currentFor(contentCoreSession)));
                }
            });
        });
    }

    @Override
    protected void synchronizeCoreWorkspaceMutation() {
        CoreGraphEditorSession session = contentCoreSession;
        FlowNode root = CustomContentGraphAdapter.findStartNode(graph);
        String rootId = contentRootNodeId(graph);
        ReSyncFlowClient client = typedCatalogClient();
        ReSyncCatalogPublicationProjection.Snapshot catalog = client != null ? client.catalogPublicationProjection().active().orElse(null) : null;
        if (session == null || root == null || rootId == null || catalog == null) {
            return;
        }
        String type = root.getType();
        Map<String, Object> inputs = freezeProjectionMap(root.getInputValues());
        Map<String, Object> properties = freezeProjectionMap(graph.getContentProperties());
        submitCoreWorkspaceMutation("Content Settings", candidate -> {
            CoreGraphUiProjection.ProjectionResult projected = new CoreGraphUiProjection().projectEditorSession(candidate);
            if (!projected.complete()) return false;
            FlowGraph current = projected.graph();
            FlowNode currentRoot = current.getNodes().get(rootId);
            if (currentRoot == null) return false;
            currentRoot.setInputValues(new LinkedHashMap<>(inputs));
            current.setContentProperties(new LinkedHashMap<>(properties));
            if (!Objects.equals(CustomContentGraphAdapter.typeFromNode(currentRoot.getType()), CustomContentGraphAdapter.typeFromNode(type))) {
                currentRoot.setType(type.contains(":") ? type : "restudio.resync:" + type);
                CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.CUSTOM_CONTENT,
                    candidate.resource(), candidate.catalogBinding(), candidate.graphDocument().schemaVersion(), catalog.entries().values(),
                    candidate.revision());
                GraphNode replacement = new CoreGraphAuthoringAdapter().createNode(NodeInstanceId.parseCanonicalText(rootId), currentRoot, context);
                List<GraphNode> nodes = candidate.graphDocument().nodes().stream()
                    .map(node -> node.instanceId().canonicalText().equals(rootId) ? replacement : node).toList();
                candidate.setNodes(nodes);
                currentRoot.setVersion(replacement.definitionVersion());
            }
            CoreGraphAuthoringAdapter.Result converted = CustomContentCoreEditor.convert(current, candidate, catalog.entries().values());
            if (!converted.lossless()) return false;
            candidate.replaceGraph(converted.graphDocument());
            return true;
        });
    }

    @Override
    protected void onCoreGraphProjectionPublished(FlowGraph previous, FlowGraph current) {
        contentPanelRefreshPending = true;
    }

    private static String initialContentId(FlowGraph graph, String flowId) {
        String id = "";
        if (graph != null) {
            Object value = CustomContentGraphAdapter.getContentProperty(graph, "content_id", "");
            id = value != null ? String.valueOf(value).trim() : "";
        }
        if (id.isBlank()) {
            id = contentIdFromFlowId(graph != null ? graph.getId() : "");
        }
        if (id.isBlank()) {
            id = contentIdFromFlowId(flowId);
        }
        return id;
    }

    private static String contentIdFromFlowId(String flowId) {
        if (flowId == null || !flowId.startsWith("content.")) {
            return "";
        }
        int separator = flowId.indexOf('.', "content.".length());
        return separator >= 0 && separator + 1 < flowId.length() ? flowId.substring(separator + 1) : "";
    }

    public static ContentDesignerScreen quickEdit(String serverId, String sessionId, CustomContentDefinition definition, Screen parent) {
        return new ContentDesignerScreen(serverId, quickEditGraph(sessionId, definition), quickEditFlowId(sessionId, definition), parent, true, sessionId, definition != null ? definition.getAbilities() : List.of(), definition != null ? definition.getComponents() : Map.of());
    }

    private static FlowGraph loadGraph(String serverId, String flowId) {
        FlowManager manager = RemotelyClient.INSTANCE != null ? RemotelyClient.INSTANCE.getFlowManager() : null;
        if (manager == null) {
            manager = FlowManager.getInstance();
        }
        if (manager == null || serverId == null || flowId == null) {
            return new FlowGraph();
        }
        CustomContentDefinition exact = manager.getCustomContent(serverId, flowId);
        if (exact != null && exact.getGraph() != null) {
            return exact.getGraph();
        }
        CustomContentDefinition content = manager.getCustomContentForServer(serverId).values().stream()
            .filter(candidate -> candidate != null && flowId.equals(candidate.getFlowId()) && candidate.getGraph() != null)
            .filter(candidate -> candidate.getId() == null || !flowId.equalsIgnoreCase(candidate.getId()))
            .findFirst()
            .orElse(null);
        if (content != null) {
            return content.getGraph();
        }
        FlowGraph fallback = new FlowGraph();
        fallback.setId(flowId);
        return fallback;
    }

    private static FlowGraph quickEditGraph(String sessionId, CustomContentDefinition definition) {
        String id = definition != null && definition.getId() != null && !definition.getId().isBlank() ? definition.getId() : "quickedit";
        String name = definition != null && definition.getDisplayName() != null ? definition.getDisplayName() : "";
        String type = normalizedQuickEditType(definition != null ? definition.getType() : "");
        String provider = definition != null && definition.getProvider() != null && !definition.getProvider().isBlank() ? definition.getProvider() : "vanilla";
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph(id, type, name);
        if (definition != null && definition.getGraph() != null && definition.getGraph().getContentProperties() != null) {
            graph.getContentProperties().putAll(definition.getGraph().getContentProperties());
        }
        graph.setId(quickEditFlowId(sessionId, definition));
        CustomContentGraphAdapter.setContentProperty(graph, "name", name);
        CustomContentGraphAdapter.setContentProperty(graph, "provider", provider);
        CustomContentGraphAdapter.setContentProperty(graph, "external_id", definition != null && definition.getExternalId() != null ? definition.getExternalId() : "");
        CustomContentGraphAdapter.setContentProperty(graph, "material", definition != null && definition.getMaterial() != null ? definition.getMaterial() : quickEditDefaultMaterial(type));
        CustomContentGraphAdapter.setContentProperty(graph, "custom_model_data", definition != null && definition.getCustomModelData() != null ? definition.getCustomModelData() : "");
        CustomContentGraphAdapter.setContentProperty(graph, "components", definition != null && definition.getComponents() != null ? new LinkedHashMap<>(definition.getComponents()) : new LinkedHashMap<>());
        CustomContentGraphAdapter.setContentProperty(graph, "lore", definition != null && definition.getLore() != null ? String.join("\n", definition.getLore()) : "");
        CustomContentGraphAdapter.setContentProperty(graph, "tags", definition != null && definition.getTags() != null ? String.join("\n", definition.getTags()) : "");
        if ("armor".equals(type)) {
            CustomContentGraphAdapter.setContentConfiguration(graph, "armor_slot", definition != null && definition.getArmorSlot() != null ? definition.getArmorSlot() : "chest");
        }
        CustomContentGraphAdapter.setContentProperty(graph, CustomContentGraphAdapter.FLOW_BRANCHES_KEY, List.of());
        return graph;
    }

    private static String normalizedQuickEditType(String type) {
        String normalized = type != null ? type.toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "block", "armor", "projectile" -> normalized;
            default -> "item";
        };
    }

    private static String quickEditDefaultMaterial(String type) {
        return switch (type) {
            case "block" -> "STONE";
            case "armor" -> "IRON_CHESTPLATE";
            case "projectile" -> "ARROW";
            default -> "STICK";
        };
    }

    private static String quickEditFlowId(String sessionId, CustomContentDefinition definition) {
        if (definition != null && definition.getFlowId() != null && !definition.getFlowId().isBlank()) {
            return definition.getFlowId();
        }
        return "quickedit." + (sessionId != null && !sessionId.isBlank() ? sessionId : "item");
    }

    private AttributeProjection createAttributeProjection(CustomContentDefinition definition) {
        if (definition == null) {
            return null;
        }
        String material = projectionMaterial(definition.getMaterial());
        String source = attributeSchemaSource(definition.getMaterial());
        Map<String, OptionCatalogItem> attributeValueCatalog = attributeCatalog("server:minecraft:attribute");
        return new AttributeProjection(definition.getType(), definition.getDisplayName(), material, source,
            definition.getLore(), definition.getComponents(), attributeCatalog(source), null, null, catalogIndex(attributeValueCatalog));
    }

    private AttributeProjection currentAttributeProjection() {
        return contentPanelSnapshot != null ? contentAttributeProjection : null;
    }

    private void updateAttributeProjectionProperty(String key, Object value) {
        AttributeProjection current = contentAttributeProjection;
        if (current == null || key == null) {
            return;
        }
        switch (key) {
            case "__type" -> contentAttributeProjection = current.withType(value != null ? value.toString() : "");
            case "name" -> contentAttributeProjection = current.withDisplayName(value != null ? value.toString() : "");
            case "material" -> {
                String material = projectionMaterial(value != null ? value.toString() : "");
                if (!material.equals(current.material())) {
                    String source = attributeSchemaSource(material);
                    contentAttributeProjection = current.withMaterial(material, source);
                    requestCatalog(source);
                    refreshContentPanel();
                    if (attributeDesignerOpen) {
                        refreshAttributeDesignerContent(true);
                    }
                }
            }
            case "components" -> contentAttributeProjection = current.withComponents(projectionComponents(value));
            case "lore" -> contentAttributeProjection = current.withLore(projectionList(value));
            default -> {
            }
        }
    }

    private void updateAttributeProjectionWorkspace(Set<String> inputKeys, Set<String> propertyKeys, FlowNode root) {
        if (root != null && root.getType() != null) {
            AttributeProjection current = contentAttributeProjection;
            if (current != null) {
                String type = CustomContentGraphAdapter.typeFromNode(root.getType());
                if (type != null && !type.equals(current.type())) {
                    contentAttributeProjection = current.withType(type);
                }
            }
        }
        if (root != null && root.getInputValues() != null && inputKeys != null) {
            for (String key : inputKeys) {
                updateAttributeProjectionProperty(key, root.getInputValues().get(key));
            }
        }
        if (graph != null && graph.getContentProperties() != null && propertyKeys != null) {
            for (String key : propertyKeys) {
                updateAttributeProjectionProperty(key, graph.getContentProperties().get(key));
            }
        }
    }

    private Map<String, Object> projectionComponents(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> components = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                components.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return components;
    }

    private List<String> projectionList(Object value) {
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : value.toString().split("[,\\r\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private String projectionMaterial(String value) {
        String material = value != null ? value.trim() : "";
        if (material.startsWith("minecraft:")) {
            material = material.substring("minecraft:".length());
        }
        return material.isBlank() ? "STICK" : material.toUpperCase(Locale.ROOT);
    }

    public FlowGraph getContentGraph() {
        return graph;
    }

    @Override
    public String getDesktopAppId() {
        return "content-designer";
    }

    @Override
    public String getDesktopAppTitle() {
        return quickEditMode ? "Quick Edit" : "Content Designer";
    }

    @Override
    public String getDesktopAppIconPath() {
        return "content.png";
    }

    @Override
    public void init() {
        if (widgetCache.size() != graph.getNodes().size()) {
            refreshNodeRegistry();
        }
        super.init();
        if (!CustomContentGraphAdapter.isContentGraph(graph)) {
            close();
            return;
        }
        selectedBranch = firstBranch();
        preloadContentCatalogs();
        preloadAttributeSchema();
        buildContentPanel();
        refreshContentPanel();
    }

    @Override
    protected FlowNodeWidget createNodeWidget(String nodeId, FlowNode node) {
        if (node != null && CustomContentGraphAdapter.typeFromNode(node.getType()) != null) {
            ReSyncGenericWidgetCapabilities.WidgetDefinition definition = genericWidgetDefinition(node.getType());
            GraphNode coreNode = contentCoreSession != null ? contentCoreSession.graphDocument().nodes().stream()
                .filter(value -> value.instanceId().canonicalText().equals(nodeId)).findFirst().orElse(null) : null;
            ReSyncGenericDescriptorProjection.Projection descriptor = coreNode != null && typedCatalogClient() != null ? typedCatalogClient()
                .typedInteractionProjection().flatMap(value -> value.descriptor(coreNode.definition())).orElse(null) : null;
            if (definition != null && ownsCoreNodeFields(coreNode, descriptor)) {
                definition = new ReSyncGenericWidgetCapabilities.WidgetDefinition(definition.definition(), false,
                    definition.identity(), definition.inspector());
            }
            FlowNodeWidget widget = new StudioRootNodeWidget((int) node.getX(), (int) node.getY(), node, graph,
                nodeId, serverId, () -> {}, this::markWorkspaceMutation, definition);
            widget.setEditorDiagnostics(editorDiagnosticsForNode(nodeId));
            return widget;
        }
        return super.createNodeWidget(nodeId, node);
    }

    @Override
    public void refreshNodeRegistry() {
        super.refreshNodeRegistry();
    }

    @Override
    protected void addCustomHeaderButtons() {
    }

    @Override
    protected void createHeaderButtons() {
        super.createHeaderButtons();
    }

    @Override
    public List<AnimatedWidget> getStudioHeaderButtons() {
        return super.getStudioHeaderButtons();
    }

    @Override
    protected boolean shouldCreatePaletteSidePanel() {
        return true;
    }

    @Override
    protected void onOptionCatalogRefreshed() {
        onOptionCatalogRefreshed(null);
    }

    @Override
    protected String workspaceCatalogFence() {
        return super.workspaceCatalogFence() + "|content=" + contentPanelCatalogVersion;
    }

    @Override
    protected void onOptionCatalogRefreshed(String sourceId) {
        super.onOptionCatalogRefreshed(sourceId);
        if (sourceId != null && !contentPanelCatalogSource(sourceId)) {
            return;
        }
        contentPanelCatalogVersion++;
        refreshContentPanel();
        prepareContentCoreSession(false);
        if (attributeDesignerOpen) {
            refreshAttributeDesignerContent(true);
        }
    }

    private boolean contentPanelCatalogSource(String sourceId) {
        String source = attributeCatalogSource(sourceId);
        AttributeProjection projection = contentAttributeProjection;
        String attributeSource = projection != null ? attributeCatalogSource(projection.schemaSource()) : ATTRIBUTE_SCHEMA_SOURCE;
        return CONTENT_PANEL_CATALOG_SOURCES.contains(source) || attributeSource.equals(source);
    }

    @Override
    public void renderHandler(IDrawContext context, int mouseX, int mouseY, float delta) {
        for (int index = 0; index < 4; index++) {
            Runnable completion = contentCoreCompletions.poll();
            if (completion == null) break;
            completion.run();
        }
        prepareContentCoreSession(false);
        super.renderHandler(context, mouseX, mouseY, delta);
        drainContentPanelDiffs();
        if (contentStudioPanel != null) {
            renderStudioPanel(contentStudioPanel, context, mouseX, mouseY, delta);
        }
        if (attributeDesignerPanel != null) {
            renderStudioPanel(attributeDesignerPanel, context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void updatePositions() {
        super.updatePositions();
        if (contentPanel != null) {
            if (contentStudioPanel != null) {
                contentStudioPanel.layout();
            }
        }
        if (attributePanel != null && attributeDesignerPanel != null) {
            attributeDesignerPanel.layout();
        }
    }


    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (handleActiveStudioSelectorMouseClicked(event)) {
            return true;
        }
        if (event.button() == ReMouseButton.RIGHT && !isDesignerPanelMouseOver(event.x(), event.y())) {
            return super.mouseClicked(event);
        }
        if (isAttributeDesignerInteractive() && attributePanel.mouseClicked(event.retarget(attributePanel, event.x(), event.y()))) {
            return true;
        }
        if (contentPanel != null && contentPanel.mouseClicked(event.retarget(contentPanel, event.x(), event.y()))) {
            return true;
        }
        return super.mouseClicked(event);
    }

    private boolean isDesignerPanelMouseOver(double mouseX, double mouseY) {
        return (contentPanel != null && contentPanel.isMouseOver(mouseX, mouseY))
            || (isAttributeDesignerInteractive() && attributePanel.isMouseOver(mouseX, mouseY));
    }

    @Override
    public boolean mouseReleased(ReMouseEvent event) {
        if (isConnectionDragging()) {
            return super.mouseReleased(event);
        }
        if (handleActiveStudioSelectorMouseReleased(event)) {
            return true;
        }
        if (isAttributeDesignerInteractive() && attributePanel.mouseReleased(event.retarget(attributePanel, event.x(), event.y()))) {
            return true;
        }
        if (contentPanel != null && contentPanel.mouseReleased(event.retarget(contentPanel, event.x(), event.y()))) {
            return true;
        }
        return super.mouseReleased(event);
    }


    @Override
    public boolean mouseDragged(ReMouseEvent event) {
        if (isConnectionDragging()) {
            return super.mouseDragged(event);
        }
        if (handleActiveStudioSelectorMouseDragged(event)) {
            return true;
        }
        if (isAttributeDesignerInteractive() && attributePanel.mouseDragged(event.retarget(attributePanel, event.x(), event.y(), event.deltaX(), event.deltaY()))) {
            return true;
        }
        if (contentPanel != null && contentPanel.mouseDragged(event.retarget(contentPanel, event.x(), event.y(), event.deltaX(), event.deltaY()))) {
            return true;
        }
        return super.mouseDragged(event);
    }


    @Override
    public boolean mouseScrolled(ReScrollEvent event) {
        if (isConnectionDragging()) {
            return super.mouseScrolled(event);
        }
        if (handleActiveStudioSelectorMouseScrolled(event)) {
            return true;
        }
        if (isAttributeDesignerInteractive() && attributePanel.mouseScrolled(event.retarget(attributePanel, event.x(), event.y()))) {
            return true;
        }
        if (contentPanel != null && contentPanel.mouseScrolled(event.retarget(contentPanel, event.x(), event.y()))) {
            return true;
        }
        return super.mouseScrolled(event);
    }

    @Override
    public boolean keyPressed(ReKeyEvent event) {
        if (handleActiveStudioSelectorKeyPressed(event)) {
            return true;
        }
        if (handleFocusedTextInputKeyPressed(event)) {
            return true;
        }
        if (isAttributeDesignerInteractive() && attributePanel.keyPressed(event.retarget(attributePanel))) {
            return true;
        }
        if (contentPanel != null && contentPanel.keyPressed(event.retarget(contentPanel))) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean textInput(ReTextInputEvent event) {
        if (handleActiveStudioSelectorTextInput(event)) {
            return true;
        }
        if (handleFocusedTextInputCharTyped(event)) {
            return true;
        }
        if (isAttributeDesignerInteractive() && attributePanel.textInput(event.retarget(attributePanel))) {
            return true;
        }
        if (contentPanel != null && contentPanel.textInput(event.retarget(contentPanel))) {
            return true;
        }
        return super.textInput(event);
    }

    private boolean handleFocusedTextInputKeyPressed(ReKeyEvent event) {
        Widget focused = getFocusedWidget();
        return focused instanceof TextInputWidget && focused.keyPressed(event.retarget(focused));
    }

    private boolean handleFocusedTextInputCharTyped(ReTextInputEvent event) {
        Widget focused = getFocusedWidget();
        return focused instanceof TextInputWidget && focused.textInput(event.retarget(focused));
    }

    @Override
    protected void onSave() {
        if (contentCoreSession == null || !contentCoreIssue.isBlank()) {
            prepareContentCoreSession(true);
            new Notification(getDesktopAppTitle(), contentCoreIssue.isBlank() ? "Editor Loading" : contentCoreIssue, Notification.Type.ERROR);
            return;
        }
        if (quickEditMode) {
            applyQuickEdit();
            return;
        }
        if (pendingContentSave != null) {
            new Notification("Content Designer", "Save In Progress", Notification.Type.INFO);
            return;
        }
        super.onSave();
        if (!deferFrozenWorkspaceMutation(this::updateSummary)) {
            updateSummary();
        }
    }

    @Override
    protected SaveTicketIdentity saveTicketIdentity(ReSyncResourceType materializationType, FlowGraph target,
                                                    String notificationName) {
        String name = notificationName != null && !notificationName.isBlank() ? notificationName : contentId;
        return new SaveTicketIdentity(ReSyncResourceType.CUSTOM_CONTENT, contentId, name);
    }

    private void applyQuickEdit() {
        if (quickEditSessionId.isBlank()) {
            new Notification("Quick Edit", "Apply Failed", Notification.Type.ERROR);
            return;
        }
        boolean submitted = submitContentGraph(frozenGraph -> {
            CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(frozenGraph);
            if (definition == null) {
                return false;
            }
            if ((definition.getAbilities() == null || definition.getAbilities().isEmpty()) && !quickEditOriginalAbilities.isEmpty()) {
                definition.setAbilities(new ArrayList<>(quickEditOriginalAbilities));
            }
            definition.setComponents(persistedQuickEditComponents(definition.getComponents()));
            definition.setGraph(null);
            flowManager.applyQuickEdit(serverId, quickEditSessionId, definition);
            return true;
        }, applied -> new Notification("Quick Edit", applied ? "Applying" : "Apply Failed",
            applied ? Notification.Type.INFO : Notification.Type.ERROR), false);
        if (!submitted) {
            new Notification("Quick Edit", "Apply Failed", Notification.Type.ERROR);
        }
    }

    private Map<String, Object> persistedQuickEditComponents(Map<String, Object> components) {
        Map<String, Object> normalized = normalizeAttributeComponents(components);
        Map<String, Object> persisted = new LinkedHashMap<>();
        for (String id : editedAttributeComponents) {
            if (normalized.containsKey(id)) {
                persisted.put(id, copyAttributeValue(normalized.get(id)));
            } else if (quickEditOriginalComponents.containsKey(id)) {
                persisted.put(id, null);
            }
        }
        return persisted;
    }

    @Override
    public void close() {
        contentCoreClosed = true;
        contentCoreGeneration++;
        contentCoreCompletions.clear();
        contentPanelRequestVersion++;
        contentPanelDiffs = new ArrayDeque<>();
        contentPanelRefreshPending = false;
        contentPanelStructurePending = false;
        closeStudioSelector();
        hideAttributeDesigner();
        contentPanelSnapshot = null;
        contentAttributeProjection = null;
        super.close();
    }

    @Override
    public void removed() {
        contentCoreClosed = true;
        contentCoreGeneration++;
        contentCoreCompletions.clear();
        contentPanelRequestVersion++;
        contentPanelDiffs = new ArrayDeque<>();
        contentPanelRefreshPending = false;
        contentPanelStructurePending = false;
        closeStudioSelector();
        restoreAttributeDesignerContentBrowser();
        dismissStudioWorkspace();
        contentPanelSnapshot = null;
        contentAttributeProjection = null;
        super.removed();
    }

    @Override
    public void studioDocumentSelected() {
        if (attributeDesignerOpen && contentDesignerParent instanceof StudioScreen studioScreen) {
            studioScreen.setStudioContentBrowserTemporarilyHidden(true);
            attributeDesignerHidContentBrowser = true;
        }
    }

    @Override
    public void studioDocumentDeselected() {
        restoreAttributeDesignerContentBrowser();
    }

    private void buildContentPanel() {
        contentStudioPanel = rightStudioPanel("contentPanel")
            .collapsible("Content Inspector")
            .show();
        contentPanel = contentStudioPanel.sidePanel();
        contentStudioPanel.padding(panelState.padding());
    }

    public static void handleAttributeValidationErrorsForServer(String serverId, List<Map<String, Object>> errors) {
        for (GraphEditorScreen screen : OPEN_SCREENS) {
            if (screen instanceof ContentDesignerScreen contentScreen && serverId != null && serverId.equals(contentScreen.getServerId())) {
                contentScreen.attributeValidationErrors = errors != null ? List.copyOf(errors) : List.of();
                contentScreen.contentPanelValidationVersion++;
                if (contentScreen.attributeDesignerOpen) {
                    contentScreen.refreshAttributeDesignerContent(true);
                } else {
                    contentScreen.refreshContentPanel();
                }
            }
        }
    }

    private void refreshContentPanel() {
        if (contentPanel == null) {
            return;
        }
        long requestVersion = ++contentPanelRequestVersion;
        long generation = workspaceSnapshotGeneration();
        long mutationVersion = workspaceMutationVersion();
        long catalogVersion = contentPanelCatalogVersion;
        long validationVersion = contentPanelValidationVersion;
        BrowserSafeState.ReferenceValue<ContentPanelSnapshot> prepared = new BrowserSafeState.ReferenceValue<>();
        boolean submitted = submitContentGraph(frozenGraph -> {
            CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(frozenGraph);
            String type = CustomContentGraphAdapter.contentType(frozenGraph);
            if (definition == null || type == null) {
                return false;
            }
            List<String> branches = List.copyOf(CustomContentGraphAdapter.getEnabledTriggerBranches(frozenGraph));
            Map<String, Integer> actionCounts = new LinkedHashMap<>();
            for (CustomContentGraphAdapter.TriggerDescriptor trigger : CustomContentGraphAdapter.triggersForType(type)) {
                actionCounts.put(trigger.pin(), branchActionCount(frozenGraph, trigger.pin()));
            }
            Set<String> componentKeys = definition.getComponents() != null ? definition.getComponents().keySet() : Set.of();
            String key = type + "\n" + definition.getProvider() + "\n"
                + String.join("\n", branches) + "\n"
                + String.join("\n", componentKeys) + "\n" + Objects.hashCode(definition.getComponents());
            prepared.set(new ContentPanelSnapshot(definition, type, key, generation, mutationVersion, requestVersion,
                catalogVersion, validationVersion, branches, Map.copyOf(actionCounts), List.copyOf(providerOptions()),
                List.copyOf(materialOptions()), List.copyOf(providerAssetOptions(definition.getProvider(), type)),
                createAttributeProjection(definition)));
            return true;
        }, applied -> {
            ContentPanelSnapshot snapshot = prepared.get();
            if (!applied || !OPEN_SCREENS.contains(this) || snapshot == null
                || snapshot.generation() != workspaceSnapshotGeneration()
                || snapshot.requestVersion() != contentPanelRequestVersion
                || snapshot.catalogVersion() != contentPanelCatalogVersion
                || snapshot.validationVersion() != contentPanelValidationVersion
                || snapshot.mutationVersion() != workspaceMutationVersion()) {
                contentPanelRefreshPending = true;
                return;
            }
            contentPanelRefreshPending = false;
            applyContentPanel(snapshot);
        });
        if (!submitted) {
            contentPanelRefreshPending = true;
        }
    }

    private void applyContentPanel(ContentPanelSnapshot snapshot) {
        contentAttributeProjection = snapshot.attributeProjection();
        contentPanelSnapshot = snapshot;
        if (contentAttributeProjection != null) {
            requestCatalog(contentAttributeProjection.schemaSource());
        }
        if (attributeDesignerOpen) {
            refreshAttributeDesignerContent(true);
        }
        CustomContentDefinition definition = snapshot.definition();
        String type = snapshot.type();
        if (snapshot.key().equals(contentPanelKey) && !contentPanelStructurePending) {
            contentPanelDiffs = new ArrayDeque<>();
            queueContentPanelValues(snapshot);
            return;
        }
        Container container = contentPanel.container();
        Deque<AnimatedWidget> retired = contentPanelWidgets;
        contentPanelWidgets = new ArrayDeque<>();
        if (!retired.isEmpty()) {
            contentPanelRetirements.addLast(retired);
        }
        contentCollaborationBindings = new LinkedHashMap<>();
        eventRows = new HashMap<>();
        panelDropdowns = new ArrayList<>();
        contentDropdowns = new LinkedHashMap<>();
        contentPanelDiffs = new ArrayDeque<>();
        contentPanelKey = snapshot.key();
        contentPanelStructurePending = true;
        contentPanelDiffMutationVersion = snapshot.mutationVersion();
        contentPanelDiffRequestVersion = snapshot.requestVersion();
        int rowWidth = contentRowWidth();
        container.beginBatchAdd();
        queueContentPanelRetirement(container);
        queueContentPanelWidget(container, () -> {
            summaryWidget = new MountableButtonWidget.Builder(definition.getDisplayName())
                .description(contentSummary(definition)).iconPath(iconForType(type))
                .onClick(() -> selectedBranch = firstBranch()).build();
            summaryWidget.setSize(rowWidth, 30);
            return summaryWidget;
        });
        queueContentPanelWidget(container, () -> textRow("Name", definition.getDisplayName(), rowWidth, value -> {
            setProperty("name", value);
            updateSummary();
        }));
        queueContentPanelWidget(container, () -> dropdownRow("Type", List.of("item", "armor", "block", "projectile"), type, rowWidth,
            this::setContentType));
        queueTypeRows(container, snapshot, rowWidth);
        queueAssetRows(container, snapshot, rowWidth);
        queueTextRows(container, definition, rowWidth);
        queueComponentRows(container, definition, rowWidth);
        queueRuleRows(container, type, rowWidth);
        queueEventRows(container, snapshot, rowWidth);
        contentPanelDiffs.addLast(() -> {
            container.endBatchAdd();
            contentPanelStructurePending = false;
        });
    }

    private void queueContentPanelRetirement(Container container) {
        if (contentPanelRetirements.isEmpty()) {
            return;
        }
        contentPanelDiffs.addLast(new Runnable() {
            @Override
            public void run() {
                Deque<AnimatedWidget> retired = contentPanelRetirements.peekLast();
                AnimatedWidget widget = retired != null ? retired.peekLast() : null;
                if (widget != null && container.removeLastWidget(widget)) {
                    retired.pollLast();
                }
                if (retired != null && retired.isEmpty()) {
                    contentPanelRetirements.pollLast();
                }
                if (!contentPanelRetirements.isEmpty()) {
                    contentPanelDiffs.addFirst(this);
                }
            }
        });
    }

    private void queueContentPanelWidget(Container container, Supplier<AnimatedWidget> supplier) {
        contentPanelDiffs.addLast(() -> insertContentPanelWidget(container, supplier.get()));
    }

    private void queueTypeRows(Container container, ContentPanelSnapshot snapshot, int rowWidth) {
        CustomContentDefinition definition = snapshot.definition();
        if ("armor".equals(snapshot.type())) {
            String armorSlot = definition.getArmorSlot() == null || definition.getArmorSlot().isBlank() ? "chest" : definition.getArmorSlot();
            queueContentPanelWidget(container, () -> dropdownRow("Armor Slot", List.of("head", "chest", "legs", "feet"), armorSlot, rowWidth, value -> {
                setContentConfiguration("armor_slot", value);
            }));
        } else if ("projectile".equals(snapshot.type())) {
            queueProjectileRows(container, rowWidth);
        }
    }

    private void queueProjectileRows(Container container, int rowWidth) {
        queueContentPanelWidget(container, () -> contentSectionHeader("Projectile Behavior", projectileConfigurationText("entity_type", "ARROW") + " | "
            + projectileConfigurationText("launch_source", "Automatic"), rowWidth));
        queueContentPanelWidget(container, () -> dropdownRow("Projectile", List.of("ARROW", "SPECTRAL_ARROW", "TRIDENT", "SNOWBALL", "EGG", "ENDER_PEARL", "FIREBALL",
            "SMALL_FIREBALL", "DRAGON_FIREBALL", "WITHER_SKULL", "SHULKER_BULLET", "LLAMA_SPIT", "WIND_CHARGE", "BREEZE_WIND_CHARGE"),
            projectileConfigurationText("entity_type", "ARROW"), rowWidth, value -> setProjectileConfiguration("entity_type", value)));
        queueContentPanelWidget(container, () -> dropdownRow("Launch Source", List.of("Automatic", "Bow Ammo", "Item Use", "Both"),
            projectileConfigurationText("launch_source", "Automatic"), rowWidth, value -> setProjectileConfiguration("launch_source", value)));
        queueContentPanelWidget(container, () -> textRow("Speed", projectileConfigurationText("speed", "2.4"), rowWidth, value -> setProjectileConfiguration("speed", value)));
        queueContentPanelWidget(container, () -> textRow("Damage", projectileConfigurationText("damage", "0"), rowWidth, value -> setProjectileConfiguration("damage", value)));
        queueContentPanelWidget(container, () -> dropdownRow("Gravity", List.of("Enabled", "Disabled"),
            projectileConfigurationFlag("gravity", true) ? "Enabled" : "Disabled", rowWidth, value -> setProjectileConfiguration("gravity", "Enabled".equals(value))));
        queueContentPanelWidget(container, () -> dropdownRow("Glowing", List.of("Disabled", "Enabled"),
            projectileConfigurationFlag("glowing", false) ? "Enabled" : "Disabled", rowWidth, value -> setProjectileConfiguration("glowing", "Enabled".equals(value))));
        queueContentPanelWidget(container, () -> dropdownRow("Consume Item", List.of("Enabled", "Disabled"),
            projectileConfigurationFlag("consume_item", true) ? "Enabled" : "Disabled", rowWidth, value -> setProjectileConfiguration("consume_item", "Enabled".equals(value))));
        queueContentPanelWidget(container, () -> dropdownRow("Pickup", List.of("Allowed", "Disallowed", "Creative Only"),
            projectileConfigurationText("pickup", "Allowed"), rowWidth, value -> setProjectileConfiguration("pickup", value)));
        queueContentPanelWidget(container, () -> textRow("Fire Sound", projectileConfigurationText("fire_sound", ""), rowWidth,
            value -> setProjectileConfiguration("fire_sound", value)));
        queueContentPanelWidget(container, () -> textRow("Hit Sound", projectileConfigurationText("hit_sound", ""), rowWidth,
            value -> setProjectileConfiguration("hit_sound", value)));
        queueContentPanelWidget(container, () -> textRow("Sound Volume", projectileConfigurationText("sound_volume", "1"), rowWidth,
            value -> setProjectileConfiguration("sound_volume", value)));
        queueContentPanelWidget(container, () -> textRow("Sound Pitch", projectileConfigurationText("sound_pitch", "1"), rowWidth,
            value -> setProjectileConfiguration("sound_pitch", value)));
        queueContentPanelWidget(container, () -> dropdownRow("Remove On Hit", List.of("Disabled", "Enabled"),
            projectileConfigurationFlag("remove_on_hit", false) ? "Enabled" : "Disabled", rowWidth,
            value -> setProjectileConfiguration("remove_on_hit", "Enabled".equals(value))));
    }

    private void queueAssetRows(Container container, ContentPanelSnapshot snapshot, int rowWidth) {
        CustomContentDefinition definition = snapshot.definition();
        queueContentPanelWidget(container, () -> dropdownRow("Provider", snapshot.providers(), definition.getProvider(), rowWidth, value -> {
            setProperty("provider", value);
            refreshNodeRegistry();
            refreshContentPanel();
        }));
        if ("vanilla".equalsIgnoreCase(definition.getProvider())) {
            queueContentPanelWidget(container, () -> searchableRow("Material", snapshot.materials(), definition.getMaterial(), rowWidth, value -> {
                setProperty("material", value.toUpperCase(Locale.ROOT));
                requestCatalog(attributeSchemaSource(value));
                updateSummary();
            }));
        } else {
            queueContentPanelWidget(container, () -> searchableRow("External ID", snapshot.providerAssets(), definition.getExternalId(), rowWidth, value -> {
                setProperty("external_id", value);
                updateSummary();
            }));
        }
        queueContentPanelWidget(container, () -> textRow("Model", definition.getCustomModelData() == null ? "" : String.valueOf(definition.getCustomModelData()), rowWidth,
            value -> setProperty("custom_model_data", value)));
    }

    private void queueTextRows(Container container, CustomContentDefinition definition, int rowWidth) {
        queueContentPanelWidget(container, () -> contentSectionHeader("Display Text", textSummary(definition), rowWidth));
        queueContentPanelWidget(container, () -> loreRow(definition, rowWidth));
        queueContentPanelWidget(container, () -> textRow("Tags", String.join(", ", definition.getTags() != null ? definition.getTags() : List.of()), rowWidth,
            value -> setProperty("tags", value)));
    }

    private void queueComponentRows(Container container, CustomContentDefinition definition, int rowWidth) {
        Map<String, Object> components = definition.getComponents() != null ? definition.getComponents() : Map.of();
        Map<String, Object> visibleComponents = visibleAttributeComponents(definition);
        queueContentPanelWidget(container, () -> contentSectionHeader("Item Behavior", componentCountSummary(visibleComponents), rowWidth));
        queueContentPanelWidget(container, () -> componentToggleRow("Consumable", consumableSummary(components),
            components.containsKey("minecraft:consumable") || components.containsKey("minecraft:food"), rowWidth, this::setConsumableEnabled));
        queueContentPanelWidget(container, () -> componentToggleRow("Glint", glintSummary(components),
            Boolean.TRUE.equals(components.get("minecraft:enchantment_glint_override")), rowWidth, this::setGlintEnabled));
        queueContentPanelWidget(container, () -> componentsRow(visibleComponents, rowWidth));
    }

    private void queueRuleRows(Container container, String type, int rowWidth) {
        queueContentPanelWidget(container, () -> contentSectionHeader("Trigger Rules", ruleSummary(), rowWidth));
        queueContentPanelWidget(container, () -> textRow("Permission", textProperty("permission"), rowWidth, value -> setProperty("permission", value)));
        queueContentPanelWidget(container, () -> textRow("Cooldown", textProperty("cooldown_ticks"), rowWidth, value -> setProperty("cooldown_ticks", parseInt(value))));
        queueContentPanelWidget(container, () -> textRow("Chance", textProperty("chance_percent"), rowWidth, value -> setProperty("chance_percent", parseDouble(value))));
        queueContentPanelWidget(container, () -> worldsRow(rowWidth));
        queueContentPanelWidget(container, () -> eventFlagsRow(rowWidth));
        if (showsHandFilter(type, selectedBranch)) {
            queueContentPanelWidget(container, () -> dropdownRow("Hand", List.of("any", "main hand", "offhand"), textProperty("hand_filter"), rowWidth,
                value -> setProperty("hand_filter", value)));
        }
        if (showsTargetFilter(selectedBranch)) {
            queueContentPanelWidget(container, () -> dropdownRow("Target", List.of("any", "player", "living entity", "hostile", "passive"),
                textProperty("target_filter"), rowWidth, value -> setProperty("target_filter", value)));
        }
    }

    private void queueEventRows(Container container, ContentPanelSnapshot snapshot, int rowWidth) {
        queueContentPanelWidget(container, () -> contentSectionHeader("Events", snapshot.branches().size() + " Enabled", rowWidth));
        List<CustomContentGraphAdapter.TriggerDescriptor> triggers = CustomContentGraphAdapter.triggersForType(snapshot.type());
        if (triggers.isEmpty()) {
            return;
        }
        contentPanelDiffs.addLast(new Runnable() {
            private int index;

            @Override
            public void run() {
                insertContentPanelWidget(container, eventRow(triggers.get(index++), rowWidth, snapshot));
                if (index < triggers.size()) {
                    contentPanelDiffs.addFirst(this);
                }
            }
        });
    }

    private void drainContentPanelDiffs() {
        if (contentPanelRefreshPending && contentPanelDiffs.isEmpty()) {
            contentPanelRefreshPending = false;
            refreshContentPanel();
            return;
        }
        if (contentPanelDiffs.isEmpty()) {
            return;
        }
        if (contentPanelDiffRequestVersion != contentPanelRequestVersion
            || contentPanelDiffMutationVersion != workspaceMutationVersion()) {
            contentPanelDiffs = new ArrayDeque<>();
            contentPanelKey = "";
            contentPanelStructurePending = false;
            contentPanelRefreshPending = true;
            return;
        }
        long deadline = System.nanoTime() + 1_500_000L;
        int applied = 0;
        while (applied < 2 && System.nanoTime() < deadline) {
            Runnable diff = contentPanelDiffs.pollFirst();
            if (diff == null) {
                break;
            }
            diff.run();
            applied++;
        }
    }

    private void queueContentPanelValues(ContentPanelSnapshot snapshot) {
        contentPanelDiffMutationVersion = snapshot.mutationVersion();
        contentPanelDiffRequestVersion = snapshot.requestVersion();
        CustomContentDefinition definition = snapshot.definition();
        if (summaryWidget != null) {
            contentPanelDiffs.addLast(() -> {
                summaryWidget.setName(definition.getDisplayName());
                summaryWidget.setDescription(contentSummary(definition));
            });
        }
        DropDownWidget<String> provider = contentDropdowns.get("provider");
        if (provider != null) {
            contentPanelDiffs.addLast(() -> {
                List<String> options = normalizedOptions(snapshot.providers(), definition.getProvider());
                provider.setItems(options, resolveSelectedOption(options, definition.getProvider()));
            });
        }
        Iterator<Map.Entry<String, MountableButtonWidget>> rows = eventRows.entrySet().iterator();
        if (rows.hasNext()) {
            contentPanelDiffs.addLast(new Runnable() {
                @Override
                public void run() {
                    Map.Entry<String, MountableButtonWidget> entry = rows.next();
                    boolean enabled = snapshot.branches().contains(entry.getKey());
                    MountableButtonWidget row = entry.getValue();
                    int count = snapshot.actionCounts().getOrDefault(entry.getKey(), 0);
                    row.setDescription((enabled ? "Enabled" : "Off") + " | " + (count == 1 ? "1 Action" : count + " Actions"));
                    row.setSelected(entry.getKey().equals(selectedBranch));
                    if (rows.hasNext()) {
                        contentPanelDiffs.addFirst(this);
                    }
                }
            });
        }
    }

    private String projectileConfigurationText(String key, String fallback) {
        Object value = CustomContentGraphAdapter.getContentConfiguration(graph, "projectile." + key, fallback);
        return value != null ? value.toString() : fallback;
    }

    private boolean projectileConfigurationFlag(String key, boolean fallback) {
        Object value = CustomContentGraphAdapter.getContentConfiguration(graph, "projectile." + key, fallback);
        return value instanceof Boolean flag ? flag : value != null ? Boolean.parseBoolean(value.toString()) : fallback;
    }

    private void setProjectileConfiguration(String key, Object value) {
        if (deferFrozenWorkspaceMutation(() -> setProjectileConfiguration(key, value))) {
            return;
        }
        CustomContentGraphAdapter.setContentConfiguration(graph, "projectile." + key, value);
        markWorkspaceMutation();
        updateSummary();
    }

    private void setContentType(String value) {
        if (deferFrozenWorkspaceMutation(() -> setContentType(value))) {
            return;
        }
        FlowNode start = CustomContentGraphAdapter.findStartNode(graph);
        if (start == null) {
            return;
        }
        start.setType(CustomContentGraphAdapter.nodeType(value));
        updateAttributeProjectionProperty("__type", value);
        if ("armor".equals(value)) {
            CustomContentGraphAdapter.setContentConfiguration(graph, "armor_slot", "chest");
        } else {
            CustomContentGraphAdapter.removeContentConfiguration(graph, "armor_slot");
        }
        String material = defaultMaterial(value);
        CustomContentGraphAdapter.setContentProperty(graph, "material", material);
        updateAttributeProjectionProperty("material", material);
        synchronizeContentIdentity();
        markWorkspaceMutation();
        selectedBranch = firstBranch();
        refreshNodeRegistry();
        refreshContentPanel();
    }

    private void setContentConfiguration(String key, Object value) {
        if (deferFrozenWorkspaceMutation(() -> setContentConfiguration(key, value))) {
            return;
        }
        CustomContentGraphAdapter.setContentConfiguration(graph, key, value);
        markWorkspaceMutation();
    }

    private void insertContentPanelWidget(Container container, AnimatedWidget widget) {
        ReSyncStudioPanelState.disableEntrance(widget);
        container.addWidget(widget);
        contentPanelWidgets.addLast(widget);
    }

    private void clearAttributePanelWidgets(Container container) {
        for (AnimatedWidget widget : new ArrayList<>(attributePanelWidgets)) {
            container.removeWidget(widget);
        }
        attributePanelWidgets.clear();
    }

    private void insertAttributePanelWidget(Container container, AnimatedWidget widget) {
        ReSyncStudioPanelState.disableEntrance(widget);
        if (collectingAttributeEditorWidgets != null) {
            collectingAttributeEditorWidgets.add(widget);
            return;
        }
        attributePanelWidgets.add(widget);
        if (!collectingAttributePanelOrder) {
            container.addWidget(widget);
        }
    }

    private int contentRowWidth() {
        if (contentPanel == null) {
            return Math.max(ReSyncStudioPanelState.MIN_ROW_WIDTH, ReSyncStudioPanelState.DEFAULT_WIDTH - panelState.padding() * 2);
        }
        return contentStudioPanel != null ? contentStudioPanel.rowWidth() : Math.max(ReSyncStudioPanelState.MIN_ROW_WIDTH, contentPanel.getConfiguredWidth() - panelState.padding() * 2);
    }

    private int attributeRowWidth() {
        if (attributePanel == null) {
            return 400;
        }
        int desired = attributePanel.getConfiguredWidth();
        if (desired <= 0) {
            desired = 420;
        }
        return Math.max(340, desired - panelState.padding() * 2);
    }

    @Override
    protected int viewportFitLeft() {
        int left = super.viewportFitLeft();
        if (attributePanel != null && attributePanel.isLeftAnchored()) {
            left += attributePanel.layoutWidth(8);
        }
        return left;
    }

    @Override
    protected int viewportFitWidth() {
        int fitWidth = super.viewportFitWidth();
        if (attributePanel != null && attributePanel.isLeftAnchored()) {
            fitWidth -= attributePanel.layoutWidth(8);
        }
        if (contentPanel != null) {
            fitWidth -= contentPanel.layoutWidth(8);
        }
        return Math.max(1, fitWidth);
    }

    private void addLogicRows(Container container, String type, int rowWidth) {
        MountableButtonWidget selectedRow = new MountableButtonWidget.Builder(eventTitle(selectedBranch))
            .description(actionSummary(selectedBranch))
            .iconPath("flow.png")
            .addButton(new SquareButtonWidget.Builder().imagePath("search.png").hint("Focus").entranceAnimation(false).onClick(() -> focusContentBranch(selectedBranch)).build())
            .build();
        insertContentPanelWidget(container, selectedRow);
        for (CustomContentGraphAdapter.TriggerDescriptor trigger : CustomContentGraphAdapter.triggersForType(type)) {
            MountableButtonWidget row = new MountableButtonWidget.Builder(eventTitle(trigger.pin()))
                .description((trigger.pin().equals(selectedBranch) ? "Selected" : "Open") + " | " + actionSummary(trigger.pin()))
                .iconPath("flow.png")
                .onClick(() -> {
                    selectedBranch = trigger.pin();
                    focusContentBranch(selectedBranch);
                    refreshContentPanel();
                })
                .build();
            row.setSize(rowWidth, 28);
            row.setSelected(trigger.pin().equals(selectedBranch));
            insertContentPanelWidget(container, row);
        }
    }

    private void addRulesRows(Container container, String type, int rowWidth) {
        insertContentPanelWidget(container, textRow("Permission", textProperty("permission"), rowWidth, value -> setProperty("permission", value)));
        insertContentPanelWidget(container, textRow("Cooldown", textProperty("cooldown_ticks"), rowWidth, value -> setProperty("cooldown_ticks", parseInt(value))));
        insertContentPanelWidget(container, textRow("Chance", textProperty("chance_percent"), rowWidth, value -> setProperty("chance_percent", parseDouble(value))));
        insertContentPanelWidget(container, worldsRow(rowWidth));
        insertContentPanelWidget(container, eventFlagsRow(rowWidth));
        if (showsHandFilter(type, selectedBranch)) {
            insertContentPanelWidget(container, dropdownRow("Hand", List.of("any", "main hand", "offhand"), textProperty("hand_filter"), rowWidth, value -> setProperty("hand_filter", value)));
        }
        if (showsTargetFilter(selectedBranch)) {
            insertContentPanelWidget(container, dropdownRow("Target", List.of("any", "player", "living entity", "hostile", "passive"), textProperty("target_filter"), rowWidth, value -> setProperty("target_filter", value)));
        }
    }

    private TitledRowWidget worldsRow(int rowWidth) {
        TextInputWidget worlds = new TextInputWidget.Builder()
            .text(textProperty("allowed_worlds"))
            .placeholder("Worlds")
            .forcePlaceholder(false)
            .size(174, 18)
            .onChange(value -> setProperty("allowed_worlds", value))
            .build();
        contentCollaborationBindings.put("allowed_worlds", next -> worlds.setText(next == null ? "" : String.valueOf(next)));
        ReSyncStudioPanelState.disableEntrance(worlds);
        TitledRowWidget worldsRow = new TitledRowWidget.Builder().title("Worlds").description(contentPanelDescription("Worlds")).size(rowWidth, 36).gap(4).addWidget(searchableInputRow(worlds, worldOptions(), true, "server:minecraft:world")).build();
        ReSyncStudioPanelState.identify(worldsRow, "content-field:allowed_worlds");
        return worldsRow;
    }

    private RowWidget eventFlagsRow(int rowWidth) {
        ToggleWidget cancel = new ToggleWidget.Builder().label("Cancel").toggled(boolProperty("cancel_event")).size(90, 18).entranceAnimation(false)
            .onChange(value -> setProperty("cancel_event", value)).build();
        ToggleWidget consume = new ToggleWidget.Builder().label("Consume").toggled(boolProperty("consume_event")).size(90, 18).entranceAnimation(false)
            .onChange(value -> setProperty("consume_event", value)).build();
        contentCollaborationBindings.put("cancel_event", next -> cancel.setValue(next instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(next))));
        contentCollaborationBindings.put("consume_event", next -> consume.setValue(next instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(next))));
        RowWidget toggles = new RowWidget.Builder()
            .size(rowWidth, 18)
            .padding(4)
            .addWidget(cancel)
            .addWidget(consume)
            .build();
        ReSyncStudioPanelState.identify(toggles, "content-field:event_flags");
        return toggles;
    }

    private void addTextRows(Container container, CustomContentDefinition definition, int rowWidth) {
        addQuickTextRows(container, definition, rowWidth);
        insertContentPanelWidget(container, textRow("Tags", String.join(", ", definition.getTags() != null ? definition.getTags() : List.of()), rowWidth, value -> setProperty("tags", value)));
    }

    private void addQuickTextRows(Container container, CustomContentDefinition definition, int rowWidth) {
        insertContentPanelWidget(container, loreRow(definition, rowWidth));
    }

    private TitledRowWidget loreRow(CustomContentDefinition definition, int rowWidth) {
        CodeEditorWidget lore = new CodeEditorWidget(0, 0, Math.max(180, rowWidth - 18), 78);
        ReSyncStudioPanelState.disableEntrance(lore);
        lore.setShowLineNumbers(false);
        lore.setShowSearchNavigation(false);
        lore.setDimNonMatchingLines(false);
        lore.setShowCursorLineHighlight(false);
        lore.setShowSearchMatchHighlight(false);
        lore.setWordWrap(true);
        lore.setText(String.join("\n", definition.getLore() != null ? definition.getLore() : List.of()));
        lore.onChange = text -> setProperty("lore", text);
        contentCollaborationBindings.put("lore", next -> lore.setText(next == null ? "" : String.valueOf(next)));
        TitledRowWidget loreRow = new TitledRowWidget.Builder()
            .title("Lore")
            .description(contentPanelDescription("Lore"))
            .size(rowWidth, 94)
            .gap(4)
            .addWidget(lore)
            .build();
        ReSyncStudioPanelState.identify(loreRow, "content-field:lore");
        return loreRow;
    }

    private MountableButtonWidget contentSectionHeader(String title, String description, int width) {
        MountableButtonWidget row = new MountableButtonWidget.Builder(title)
            .description(description)
            .build();
        row.setSize(width, 24);
        row.setAccent(ThemeManager.getAccent("calm"));
        return row;
    }

    private String textSummary(CustomContentDefinition definition) {
        int lore = definition.getLore() != null ? definition.getLore().size() : 0;
        int tags = definition.getTags() != null ? definition.getTags().size() : 0;
        return lore + " Lore | " + tags + " Tags";
    }

    private String ruleSummary() {
        List<String> parts = new ArrayList<>();
        if (!textProperty("permission").isBlank()) {
            parts.add("Permission");
        }
        if (parseInt(textProperty("cooldown_ticks")) > 0) {
            parts.add("Cooldown");
        }
        if (parseDouble(textProperty("chance_percent")) < 100) {
            parts.add("Chance");
        }
        if (!textProperty("allowed_worlds").isBlank()) {
            parts.add("Worlds");
        }
        if (boolProperty("cancel_event")) {
            parts.add("Cancel");
        }
        if (boolProperty("consume_event")) {
            parts.add("Consume");
        }
        return parts.isEmpty() ? "No Restrictions" : String.join(" | ", parts);
    }

    private void addAssetRows(Container container, CustomContentDefinition definition, int rowWidth) {
        insertContentPanelWidget(container, dropdownRow("Provider", providerOptions(), definition.getProvider(), rowWidth, value -> {
            setProperty("provider", value);
            refreshNodeRegistry();
            refreshContentPanel();
        }));
        if ("vanilla".equalsIgnoreCase(definition.getProvider())) {
            insertContentPanelWidget(container, searchableRow("Material", materialOptions(), definition.getMaterial(), rowWidth, value -> {
                setProperty("material", value.toUpperCase(Locale.ROOT));
                requestCatalog(attributeSchemaSource(value));
                updateSummary();
            }));
        } else {
            insertContentPanelWidget(container, searchableRow("External ID", providerAssetOptions(definition.getProvider()), definition.getExternalId(), rowWidth, value -> {
                setProperty("external_id", value);
                updateSummary();
            }));
        }
        insertContentPanelWidget(container, textRow("Model", definition.getCustomModelData() == null ? "" : String.valueOf(definition.getCustomModelData()), rowWidth, value -> setProperty("custom_model_data", value)));
    }

    private void addPanelAssetRows(Container container, ContentPanelSnapshot snapshot, int rowWidth) {
        CustomContentDefinition definition = snapshot.definition();
        insertContentPanelWidget(container, dropdownRow("Provider", snapshot.providers(), definition.getProvider(), rowWidth, value -> {
            setProperty("provider", value);
            refreshNodeRegistry();
            refreshContentPanel();
        }));
        if ("vanilla".equalsIgnoreCase(definition.getProvider())) {
            insertContentPanelWidget(container, searchableRow("Material", snapshot.materials(), definition.getMaterial(), rowWidth, value -> {
                setProperty("material", value.toUpperCase(Locale.ROOT));
                requestCatalog(attributeSchemaSource(value));
                updateSummary();
            }));
        } else {
            insertContentPanelWidget(container, searchableRow("External ID", snapshot.providerAssets(), definition.getExternalId(), rowWidth, value -> {
                setProperty("external_id", value);
                updateSummary();
            }));
        }
        insertContentPanelWidget(container, textRow("Model", definition.getCustomModelData() == null ? "" : String.valueOf(definition.getCustomModelData()), rowWidth,
            value -> setProperty("custom_model_data", value)));
    }

    private void addPreviewRows(Container container, CustomContentDefinition definition, String type, int rowWidth) {
        insertContentPanelWidget(container, new MountableButtonWidget.Builder(definition.getDisplayName())
            .description(contentCardPreview(definition, type))
            .iconPath(iconForType(type))
            .build());
        insertContentPanelWidget(container, new MountableButtonWidget.Builder("Events")
            .description(CustomContentGraphAdapter.getEnabledTriggerBranches(graph).size() + " Enabled | " + branchActionCount(selectedBranch) + " Actions")
            .iconPath("flow.png")
            .build());
        insertContentPanelWidget(container, new MountableButtonWidget.Builder("State")
            .description(saveState(definition))
            .iconPath("save.png")
            .build());
        insertContentPanelWidget(container, new MountableButtonWidget.Builder("Validation")
            .description(contentValidationSummary(definition))
            .iconPath("stop.png")
            .build());
    }

    private void addComponentDashboardRows(Container container, CustomContentDefinition definition, int rowWidth) {
        Map<String, Object> components = definition.getComponents() != null ? definition.getComponents() : Map.of();
        Map<String, Object> visibleComponents = visibleAttributeComponents(definition);
        insertContentPanelWidget(container, componentToggleRow(
            "Consumable",
            consumableSummary(components),
            components.containsKey("minecraft:consumable") || components.containsKey("minecraft:food"),
            rowWidth,
            this::setConsumableEnabled
        ));
        insertContentPanelWidget(container, componentToggleRow(
            "Glint",
            glintSummary(components),
            Boolean.TRUE.equals(components.get("minecraft:enchantment_glint_override")),
            rowWidth,
            this::setGlintEnabled
        ));
        insertContentPanelWidget(container, componentsRow(visibleComponents, rowWidth));
    }

    private MountableButtonWidget componentsRow(Map<String, Object> visibleComponents, int rowWidth) {
        MountableButtonWidget componentsRow = new MountableButtonWidget.Builder("Components")
            .description(componentCountSummary(visibleComponents))
            .iconPath("item.png")
            .addButton(new SquareButtonWidget.Builder().imagePath("edit.png").hint("Edit Components").entranceAnimation(false).onClick(this::openAttributeDesigner).build())
            .build();
        componentsRow.setSize(rowWidth, 30);
        return componentsRow;
    }

    private MountableButtonWidget componentToggleRow(String title, String description, boolean enabled, int rowWidth, Consumer<Boolean> onChange) {
        ToggleWidget toggle = new ToggleWidget.Builder()
            .toggled(enabled)
            .size(32, 18)
            .onChange(onChange)
            .entranceAnimation(false)
            .build();
        MountableButtonWidget row = new MountableButtonWidget.Builder(title)
            .description(description)
            .iconPath("item.png")
            .addWidget(toggle)
            .build();
        row.setSize(rowWidth, 30);
        row.setSelected(enabled);
        return row;
    }

    private String consumableSummary(Map<String, Object> components) {
        boolean consumable = components.containsKey("minecraft:consumable");
        boolean food = components.containsKey("minecraft:food");
        if (!consumable && !food) {
            return "Off";
        }
        String seconds = consumableSeconds(components.get("minecraft:consumable"));
        String nutrition = foodNutrition(components.get("minecraft:food"));
        if (!seconds.isBlank() && !nutrition.isBlank()) {
            return "Eat " + seconds + "s | Food +" + nutrition;
        }
        if (!seconds.isBlank()) {
            return "Eat " + seconds + "s";
        }
        if (!nutrition.isBlank()) {
            return "Food +" + nutrition;
        }
        return "On";
    }

    private String glintSummary(Map<String, Object> components) {
        if (!components.containsKey("minecraft:enchantment_glint_override")) {
            return "Off";
        }
        return Boolean.TRUE.equals(components.get("minecraft:enchantment_glint_override")) ? "Shown" : "Hidden";
    }

    private String componentCountSummary(Map<String, Object> components) {
        int count = components != null ? components.size() : 0;
        if (count == 0) {
            return "No extra components";
        }
        return count == 1 ? "1 Component" : count + " Components";
    }

    private String consumableSeconds(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object seconds = map.get("consume_seconds");
            if (seconds instanceof Number number) {
                return formatDashboardNumber(number.doubleValue());
            }
            if (seconds != null && !seconds.toString().isBlank()) {
                return seconds.toString();
            }
        }
        return "";
    }

    private String foodNutrition(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object nutrition = map.get("nutrition");
            if (nutrition instanceof Number number) {
                return formatDashboardNumber(number.doubleValue());
            }
            if (nutrition != null && !nutrition.toString().isBlank()) {
                return nutrition.toString();
            }
        }
        return "";
    }

    private String formatDashboardNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void setConsumableEnabled(boolean enabled) {
        Map<String, Object> components = currentComponents();
        if (enabled) {
            Object consumable = defaultComponentValue("minecraft:consumable");
            if (consumable == null) {
                notifyComponentsLoading();
                return;
            }
            components.putIfAbsent("minecraft:consumable", consumable);
            Object food = defaultComponentValue("minecraft:food");
            if (food != null) {
                components.putIfAbsent("minecraft:food", food);
            }
        } else {
            components.remove("minecraft:food");
            components.remove("minecraft:consumable");
        }
        setComponentsAndRefresh(components);
    }

    private void setGlintEnabled(boolean enabled) {
        Map<String, Object> components = currentComponents();
        if (enabled) {
            components.put("minecraft:enchantment_glint_override", true);
        } else {
            components.remove("minecraft:enchantment_glint_override");
        }
        setComponentsAndRefresh(components);
    }

    private Map<String, Object> currentComponents() {
        AttributeProjection projection = currentAttributeProjection();
        return projection != null ? copyAttributeComponents(projection.components()) : new LinkedHashMap<>();
    }

    private void setComponentsAndRefresh(Map<String, Object> components) {
        Map<String, Object> normalized = normalizeAttributeComponents(components);
        setProperty("components", copyAttributeComponents(normalized));
        activeAttributeComponents = copyAttributeComponents(normalized);
        updateSummary();
        refreshContentPanelIfAttributeDesignerClosed();
        if (attributeDesignerOpen) {
            syncAttributeDirtyState();
            refreshAttributeComponentList(false);
        }
    }

    private Map<String, Object> initialAttributeComponents(CustomContentDefinition definition) {
        Map<String, Object> stored = definition != null && definition.getComponents() != null ? definition.getComponents() : Map.of();
        return activateNormalizedAttributeComponents(stored);
    }

    private Map<String, Object> initialAttributeComponents(AttributeProjection projection) {
        return activateNormalizedAttributeComponents(projection != null ? projection.components() : Map.of());
    }

    private Map<String, Object> visibleAttributeComponents(CustomContentDefinition definition) {
        Map<String, Object> stored = definition != null && definition.getComponents() != null ? definition.getComponents() : Map.of();
        return normalizeAttributeComponents(stored);
    }

    private List<String> materialBackedAttributeIds(AttributeProjection projection) {
        if (projection == null) {
            return List.of();
        }
        String material = projection.material() != null ? projection.material().toUpperCase(Locale.ROOT) : "";
        String type = projection.type() != null ? projection.type().toLowerCase(Locale.ROOT) : "";
        List<String> ids = new ArrayList<>();
        boolean axe = isStandaloneAxeMaterial(material);
        boolean weapon = materialContains(material, "SWORD", "TRIDENT", "MACE") || axe;
        boolean tool = materialContains(material, "PICKAXE", "SHOVEL", "HOE") || axe;
        if ("armor".equals(type) || materialContains(material, "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "ELYTRA", "WOLF_ARMOR", "HORSE_ARMOR", "SHIELD", "SKULL", "CARVED_PUMPKIN")) {
            ids.add("minecraft:equippable");
        }
        if (materialContains(material, "ELYTRA")) {
            ids.add("minecraft:glider");
        }
        if (weapon) {
            ids.add("minecraft:weapon");
        }
        if (materialContains(material, "SHIELD")) {
            ids.add("minecraft:blocks_attacks");
        }
        if (tool) {
            ids.add("minecraft:tool");
        }
        if (materialContains(material, "TRIDENT")) {
            ids.add("minecraft:piercing_weapon");
        }
        if (materialContains(material, "MACE")) {
            ids.add("minecraft:kinetic_weapon");
        }
        if (materialContains(material, "WRITABLE_BOOK")) {
            ids.add("minecraft:writable_book_content");
        }
        if (materialContains(material, "WRITTEN_BOOK")) {
            ids.add("minecraft:written_book_content");
        }
        if (materialContains(material, "BANNER")) {
            ids.add("minecraft:banner_patterns");
        }
        if (materialContains(material, "DECORATED_POT")) {
            ids.add("minecraft:pot_decorations");
        }
        if (materialContains(material, "FIREWORK_STAR")) {
            ids.add("minecraft:firework_explosion");
        }
        if (materialContains(material, "FIREWORK_ROCKET")) {
            ids.add("minecraft:fireworks");
        }
        if (materialContains(material, "BUNDLE")) {
            ids.add("minecraft:bundle_contents");
        }
        if (materialContains(material, "SHULKER_BOX")) {
            ids.add("minecraft:container");
        }
        if (materialContains(material, "BEE_NEST", "BEEHIVE")) {
            ids.add("minecraft:bees");
        }
        if (materialContains(material, "SUSPICIOUS_STEW")) {
            ids.add("minecraft:suspicious_stew_effects");
        }
        if (materialContains(material, "KNOWLEDGE_BOOK")) {
            ids.add("minecraft:recipes");
        }
        if (materialContains(material, "GOAT_HORN")) {
            ids.add("minecraft:instrument");
        }
        if (materialContains(material, "MUSIC_DISC")) {
            ids.add("minecraft:jukebox_playable");
        }
        if (materialContains(material, "POTION", "TIPPED_ARROW")) {
            ids.add("minecraft:potion_contents");
        }
        if ("block".equals(type)) {
            ids.add("minecraft:block_state");
        }
        if (hasBlockEntityDataMaterial(material)) {
            ids.add("minecraft:block_entity_data");
        }
        return ids;
    }

    private boolean isStandaloneAxeMaterial(String material) {
        return material.endsWith("_AXE") && !material.endsWith("_PICKAXE");
    }

    private boolean hasBlockEntityDataMaterial(String material) {
        return "CHEST".equals(material)
            || material.endsWith("_CHEST")
            || materialContains(material, "BARREL", "SHULKER_BOX", "FURNACE", "HOPPER", "DISPENSER", "DROPPER", "BREWING_STAND", "LECTERN", "SPAWNER");
    }

    private Object defaultComponentValue(String id) {
        AttributeProjection projection = currentAttributeProjection();
        String source = projection != null ? projection.schemaSource() : ATTRIBUTE_SCHEMA_SOURCE;
        OptionCatalogItem item = attributeCatalogItem(source, id);
        if (item != null) {
            Object value = defaultComponentValueFromItem(item);
            if (!isEmptyAttributeObject(value)) {
                return value;
            }
        }
        Object fallback = defaultCommonComponentValue(id, projection);
        if (fallback != null) {
            requestCatalog(source);
            return fallback;
        }
        requestCatalog(source);
        return null;
    }

    private boolean isEmptyAttributeObject(Object value) {
        return value instanceof Map<?, ?> map && map.isEmpty();
    }

    private Object defaultCommonComponentValue(String id, AttributeProjection projection) {
        String material = projection != null && projection.material() != null && !projection.material().isBlank()
            ? projection.material().toLowerCase(Locale.ROOT)
            : "stick";
        if ("minecraft:food".equals(id)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("nutrition", 1);
            value.put("saturation", 0.1);
            return value;
        }
        if ("minecraft:consumable".equals(id)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("consume_seconds", 1.6);
            value.put("animation", "eat");
            value.put("sound", "minecraft:entity.generic.eat");
            value.put("has_consume_particles", true);
            return value;
        }
        if ("minecraft:enchantment_glint_override".equals(id)) {
            return true;
        }
        if ("minecraft:item_model".equals(id)) {
            return "minecraft:" + material;
        }
        if ("minecraft:item_name".equals(id)) {
            return Map.of("text", projection != null && !projection.displayName().isBlank() ? projection.displayName() : "Item Name");
        }
        if ("minecraft:custom_name".equals(id)) {
            return Map.of("text", projection != null && !projection.displayName().isBlank() ? projection.displayName() : "Custom Name");
        }
        if ("minecraft:lore".equals(id)) {
            List<String> lore = projection != null && !projection.lore().isEmpty() ? projection.lore() : List.of("Lore");
            return lore.stream().map(line -> Map.of("text", line)).toList();
        }
        if ("minecraft:tooltip_display".equals(id)) {
            return Map.of("hidden_components", List.of("minecraft:attribute_modifiers"));
        }
        if ("minecraft:tooltip_style".equals(id)) {
            return "minecraft:default";
        }
        if ("minecraft:writable_book_content".equals(id)) {
            return Map.of("pages", List.of("Page"));
        }
        if ("minecraft:written_book_content".equals(id)) {
            return Map.of(
                "title", "Book Title",
                "author", "Author",
                "generation", 0,
                "resolved", false,
                "pages", List.of(Map.of("text", "Page"))
            );
        }
        if ("minecraft:attribute_modifiers".equals(id)) {
            return List.of(Map.of(
                "type", "minecraft:attack_damage",
                "amount", 1.0,
                "operation", "add_value",
                "slot", "mainhand",
                "id", "remotely:attack_damage"
            ));
        }
        if ("minecraft:trim".equals(id)) {
            return Map.of(
                "material", "minecraft:iron",
                "pattern", "minecraft:sentry"
            );
        }
        if ("minecraft:firework_explosion".equals(id)) {
            return defaultFireworkExplosion();
        }
        if ("minecraft:fireworks".equals(id)) {
            return Map.of(
                "flight_duration", 1,
                "explosions", List.of(defaultFireworkExplosion())
            );
        }
        if ("minecraft:banner_patterns".equals(id)) {
            return List.of(Map.of("pattern", "minecraft:stripe_bottom", "color", "white"));
        }
        if ("minecraft:charged_projectiles".equals(id)) {
            return List.of(simpleItemStack("minecraft:arrow", 1));
        }
        if ("minecraft:bundle_contents".equals(id)) {
            return List.of(simpleItemStack("minecraft:apple", 1));
        }
        if ("minecraft:container".equals(id)) {
            return List.of(Map.of("slot", 0, "item", simpleItemStack("minecraft:stone", 1)));
        }
        if ("minecraft:use_remainder".equals(id)) {
            return simpleItemStack("minecraft:bowl", 1);
        }
        if ("minecraft:weapon".equals(id)) {
            return Map.of(
                "item_damage_per_attack", 1,
                "disable_blocking_for_seconds", 0.0
            );
        }
        if ("minecraft:blocks_attacks".equals(id)) {
            return Map.of(
                "block_delay_seconds", 0.25,
                "disable_cooldown_scale", 1.0,
                "damage_reductions", List.of(defaultDamageReductionRule()),
                "item_damage", Map.of(
                    "threshold", 1.0,
                    "base", 1.0,
                    "factor", 1.0
                ),
                "block_sound", "minecraft:item.shield.block",
                "disabled_sound", "minecraft:item.shield.break",
                "bypassed_by", "#minecraft:bypasses_shield"
            );
        }
        if ("minecraft:piercing_weapon".equals(id)) {
            return Map.of(
                "deals_knockback", true,
                "dismounts", false,
                "sound", "minecraft:item.trident.throw",
                "hit_sound", "minecraft:item.trident.hit"
            );
        }
        if ("minecraft:kinetic_weapon".equals(id)) {
            return Map.of(
                "contact_cooldown_ticks", 10,
                "delay_ticks", 0,
                "dismount_conditions", Map.of(
                    "max_duration_ticks", 20,
                    "min_speed", 0.0,
                    "min_relative_speed", 0.0
                ),
                "forward_movement", 0.0,
                "damage_multiplier", 1.0,
                "sound", "minecraft:item.trident.throw",
                "hit_sound", "minecraft:item.trident.hit"
            );
        }
        if ("minecraft:use_effects".equals(id)) {
            return Map.of(
                "can_sprint", true,
                "interact_vibrations", true,
                "speed_multiplier", 1.0
            );
        }
        if ("minecraft:repairable".equals(id)) {
            return Map.of("items", "minecraft:iron_ingot");
        }
        if ("minecraft:container_loot".equals(id)) {
            return Map.of(
                "loot_table", "minecraft:chests/simple_dungeon",
                "seed", 0
            );
        }
        if ("minecraft:pot_decorations".equals(id)) {
            return List.of("minecraft:brick", "minecraft:brick", "minecraft:brick", "minecraft:brick");
        }
        if ("minecraft:tool".equals(id)) {
            return Map.of(
                "rules", List.of(Map.of(
                    "blocks", defaultToolRuleBlocks(material),
                    "speed", 2.0,
                    "correct_for_drops", true
                )),
                "default_mining_speed", 1.0,
                "damage_per_block", 1,
                "can_destroy_blocks_in_creative", true
            );
        }
        if ("minecraft:death_protection".equals(id)) {
            return Map.of("death_effects", List.of(Map.of(
                "type", "minecraft:play_sound",
                "sound", "minecraft:item.totem.use"
            )));
        }
        if ("minecraft:bees".equals(id)) {
            return List.of(defaultBeeEntry());
        }
        if ("minecraft:creative_slot_lock".equals(id)) {
            return Map.of();
        }
        if ("minecraft:lock".equals(id)) {
            return "";
        }
        if ("minecraft:suspicious_stew_effects".equals(id)) {
            return List.of(Map.of("id", "minecraft:night_vision", "duration", 160));
        }
        if ("minecraft:lodestone_tracker".equals(id)) {
            return Map.of(
                "target", Map.of(
                    "dimension", "minecraft:overworld",
                    "pos", List.of(0, 64, 0)
                ),
                "tracked", true
            );
        }
        if ("minecraft:block_state".equals(id)) {
            return Map.of("waterlogged", "false");
        }
        if ("minecraft:block_entity_data".equals(id)) {
            return Map.of("id", "minecraft:chest");
        }
        if ("minecraft:bucket_entity_data".equals(id)) {
            return Map.of("Age", 0, "NoAI", false);
        }
        if ("minecraft:entity_data".equals(id)) {
            return Map.of("id", "minecraft:pig", "Health", 10.0f);
        }
        if ("minecraft:debug_stick_state".equals(id)) {
            return Map.of("minecraft:furnace", "facing");
        }
        if ("minecraft:recipes".equals(id)) {
            return List.of("minecraft:crafting_table");
        }
        if ("minecraft:equippable".equals(id)) {
            return defaultEquippableValue(material);
        }
        if ("minecraft:can_break".equals(id) || "minecraft:can_place_on".equals(id)) {
            return List.of(Map.of("blocks", "minecraft:stone"));
        }
        if ("minecraft:damage_resistant".equals(id)) {
            return Map.of("types", "#minecraft:is_fire");
        }
        if ("minecraft:glider".equals(id) || "minecraft:intangible_projectile".equals(id) || "minecraft:unbreakable".equals(id)) {
            return Map.of();
        }
        if ("minecraft:enchantments".equals(id) || "minecraft:stored_enchantments".equals(id)) {
            return Map.of(
                "minecraft:unbreaking", 1
            );
        }
        if ("minecraft:potion_contents".equals(id)) {
            return Map.of("potion", "minecraft:water");
        }
        if ("minecraft:max_stack_size".equals(id)) {
            return 64;
        }
        if ("minecraft:rarity".equals(id)) {
            return "common";
        }
        if ("minecraft:enchantable".equals(id)) {
            return Map.of("value", 10);
        }
        if ("minecraft:ominous_bottle_amplifier".equals(id)) {
            return 1;
        }
        if ("minecraft:instrument".equals(id)) {
            return "minecraft:ponder_goat_horn";
        }
        if ("minecraft:jukebox_playable".equals(id)) {
            return "minecraft:13";
        }
        if ("minecraft:dyed_color".equals(id)) {
            return 16777215;
        }
        return null;
    }

    private TitledRowWidget textRow(String label, String value, int width, Consumer<String> onChange) {
        TextInputWidget input = new TextInputWidget.Builder()
            .text(value == null ? "" : value)
            .placeholder(label)
            .forcePlaceholder(false)
            .size(174, 18)
            .onChange(onChange)
            .build();
        String collaborationKey = contentCollaborationKey(label);
        if (collaborationKey != null) {
            contentCollaborationBindings.put(collaborationKey, next -> input.setText(next == null ? "" : String.valueOf(next)));
        }
        ReSyncStudioPanelState.disableEntrance(input);
        TitledRowWidget row = new TitledRowWidget.Builder().title(label).description(contentPanelDescription(label)).size(width, 36).gap(4).addWidget(input).build();
        ReSyncStudioPanelState.identify(row, "content-field:" + collaborationKey(label));
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private TitledRowWidget dropdownRow(String label, List<String> choices, String selected, int width, Consumer<String> onChange) {
        List<String> options = normalizedOptions(choices, selected);
        DropDownWidget<String> dropdown = new DropDownWidget.Builder<>(options)
            .selectedItem(resolveSelectedOption(options, selected))
            .onSelectionChanged(value -> {
                if (isRealOption(value)) {
                    onChange.accept(value);
                }
            })
            .size(174, 18)
            .maxVisibleItems(10)
            .entranceAnimation(false)
            .build();
        String collaborationKey = contentCollaborationKey(label);
        if (collaborationKey != null) {
            contentCollaborationBindings.put(collaborationKey, next -> dropdown.setSelectedItem(contentCollaborationDropdownValue(collaborationKey, next)));
            contentDropdowns.put(collaborationKey, dropdown);
        }
        panelDropdowns.add(dropdown);
        TitledRowWidget row = new TitledRowWidget.Builder().title(label).description(contentPanelDescription(label)).size(width, 36).gap(4).addWidget(dropdown).build();
        ReSyncStudioPanelState.identify(row, "content-field:" + collaborationKey(label));
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private TitledRowWidget searchableRow(String label, List<String> choices, String selected, int width, Consumer<String> onChange) {
        List<String> options = normalizedOptions(choices, selected);
        String initialLabel = resolveSelectedOption(options, selected);
        AnimatedButton button = new AnimatedButton.Builder()
            .label(isRealOption(initialLabel) ? initialLabel : "Select")
            .size(174, 18)
            .entranceAnimation(false)
            .build();
        String collaborationKey = contentCollaborationKey(label);
        if (collaborationKey != null) {
            contentCollaborationBindings.put(collaborationKey, next -> button.setMessage(next == null || String.valueOf(next).isBlank() ? "Select" : String.valueOf(next)));
        }
        button.setAction(() -> {
            String provider = textProperty("provider");
            List<String> currentOptions;
            String currentSelected;
            if ("Material".equals(label)) {
                currentOptions = normalizedOptions(materialOptions(), textProperty("material"));
                currentSelected = textProperty("material");
            } else if ("External ID".equals(label)) {
                currentOptions = normalizedOptions(providerAssetOptions(provider), textProperty("external_id"));
                currentSelected = textProperty("external_id");
            } else {
                currentOptions = options;
                currentSelected = selected;
            }
            Consumer<String> selection = value -> {
                if (isRealOption(value)) {
                    button.setMessage(value);
                    onChange.accept(value);
                }
            };
            if ("Material".equals(label)) {
                showCatalogSearchSelector(currentOptions, currentSelected, selection, button.getX(), button.getY() + button.getHeight(),
                    "server:minecraft:material", Map.of());
            } else if ("External ID".equals(label)) {
                showCatalogSearchSelector(currentOptions, currentSelected, selection, button.getX(), button.getY() + button.getHeight(),
                    "server:custom_content:asset", customContentCatalogContext(provider));
            } else {
                showSearchSelector(currentOptions, currentSelected, selection, button.getX(), button.getY() + button.getHeight());
            }
        });
        TitledRowWidget row = new TitledRowWidget.Builder().title(label).description(contentPanelDescription(label)).size(width, 36).gap(4).addWidget(button).build();
        ReSyncStudioPanelState.identify(row, "content-field:" + collaborationKey(label));
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private String contentPanelDescription(String label) {
        return switch (label) {
            case "ID" -> "Stable content id.\nUsed by recipes, flows, GUIs, and generated resource data.\nChanging it can break existing references.";
            case "Model" -> "Custom model data integer.\nResource packs use this value to pick an alternate item model.\nLeave empty for the base material model.";
            case "Lore" -> "Item tooltip lines.\nOne line per entry.\nPlace gameplay requirements or stats near the top.";
            case "Tags" -> "Comma-separated content tags.\nUsed for grouping, filtering, and flow-side lookup.";
            case "Permission" -> "Permission node required by this trigger or interaction.\nLeave empty when no permission check is needed.";
            case "Cooldown" -> "Repeat delay in ticks.\n20 ticks = 1 second.\nApplies before this action can run again.";
            case "Chance" -> "Success chance as a percent.\n100 always runs.\n0 never runs.";
            case "Worlds" -> "World allow-list.\nEmpty means every world.\nSelect worlds to restrict where the rule applies.";
            case "Hand" -> "Required interaction hand.\nMain hand and offhand can both fire events, so set this when duplicate triggers matter.";
            case "Target" -> "Required target category.\nLimits the trigger to players, living entities, hostile mobs, passive mobs, or similar target groups.";
            case "Provider" -> "Content asset provider.\nVanilla resolves Minecraft materials.\nOther providers resolve external or pack-backed ids.";
            case "Material" -> "Base Minecraft material id.\nControls the default item icon and fallback appearance.";
            case "External ID" -> "Provider-specific asset id.\nOnly used when the selected provider resolves non-vanilla content.";
            case "Projectile" -> "Entity created when this content is launched.\nArrow keeps normal bow behavior.\nOther projectile types can be launched by using the item.";
            case "Launch Source" -> "Automatic supports bow ammunition and item use.\nBow Ammo only reacts to bows and crossbows.\nItem Use launches when the item is used.";
            case "Speed" -> "Initial projectile speed.\nHigher values travel faster.\n2.4 is a strong arrow-like launch.";
            case "Damage" -> "Direct hit damage for arrow-style projectiles.\n0 keeps the projectile's normal damage.";
            case "Gravity" -> "Controls whether the projectile falls while moving.";
            case "Glowing" -> "Makes the projectile visible through blocks.";
            case "Consume Item" -> "Removes one item after an item-use launch.\nBow ammunition continues to follow normal bow consumption.";
            case "Pickup" -> "Controls who can collect arrow-style projectiles after they land.";
            case "Fire Sound" -> "Sound played at the projectile when it launches.\nLeave empty to add no extra sound.";
            case "Hit Sound" -> "Sound played where the projectile lands or hits an entity.\nLeave empty to add no extra sound.";
            case "Sound Volume" -> "Volume used by both optional projectile sounds.";
            case "Sound Pitch" -> "Pitch used by both optional projectile sounds.\nValid values are 0.5 through 2.";
            case "Remove On Hit" -> "Removes the projectile immediately after its hit event runs.";
            default -> "";
        };
    }

    private MountableButtonWidget eventRow(CustomContentGraphAdapter.TriggerDescriptor trigger, int width) {
        return eventRow(trigger, width, null);
    }

    private MountableButtonWidget eventRow(CustomContentGraphAdapter.TriggerDescriptor trigger, int width,
                                           ContentPanelSnapshot snapshot) {
        boolean enabled = snapshot != null ? snapshot.branches().contains(trigger.pin())
            : CustomContentGraphAdapter.getEnabledTriggerBranches(graph).contains(trigger.pin());
        boolean selected = trigger.pin().equals(selectedBranch);
        ToggleWidget toggle = new ToggleWidget.Builder()
            .toggled(enabled)
            .size(32, 18)
            .onChange(value -> setBranchEnabled(trigger.pin(), value))
            .entranceAnimation(false)
            .build();
        MountableButtonWidget row = new MountableButtonWidget.Builder(eventTitle(trigger.pin()))
            .description((enabled ? "Enabled" : "Off") + " | " + panelActionSummary(snapshot, trigger.pin()))
            .onClick(() -> {
                selectedBranch = trigger.pin();
                focusContentBranch(selectedBranch);
                updateEventRows();
            })
            .addWidget(toggle)
            .build();
        row.setSize(width, 30);
        row.setSelected(selected);
        eventRows.put(trigger.pin(), row);
        return row;
    }

    private String panelActionSummary(ContentPanelSnapshot snapshot, String branch) {
        if (snapshot == null) {
            return actionSummary(branch);
        }
        int count = snapshot.actionCounts().getOrDefault(branch, 0);
        return count == 1 ? "1 Action" : count + " Actions";
    }

    private void openAttributeDesigner() {
        openAttributeDesigner("");
    }

    private void openAttributeDesigner(String query) {
        activeAttributeQuery = query != null ? query : "";
        AttributeProjection projection = currentAttributeProjection();
        if (projection == null) {
            return;
        }
        closeStudioSelector();
        String source = projection.schemaSource();
        requestCatalog(source);
        activeAttributeComponents = initialAttributeComponents(projection);
        editedAttributeComponents.clear();
        attributeDesignerOpen = true;
        if (contentStudioPanel != null) {
            contentStudioPanel.hide();
        }
        if (contentDesignerParent instanceof StudioScreen studioScreen) {
            studioScreen.setStudioContentBrowserTemporarilyHidden(true);
            attributeDesignerHidContentBrowser = true;
        }
        openItemComponentEditor(new ItemComponentEditorPanel.Model("Item Components", projection.material(), null,
            activeAttributeComponents, false, snapshot -> {
                Map<String, Object> next = snapshot.components();
                Set<String> ids = new LinkedHashSet<>(activeAttributeComponents.keySet());
                ids.addAll(next.keySet());
                for (String id : ids) {
                    if (!Objects.equals(activeAttributeComponents.get(id), next.get(id))
                        || activeAttributeComponents.containsKey(id) != next.containsKey(id)) {
                        editedAttributeComponents.add(id);
                    }
                }
                commitAttributeDesignerDraft(next);
            }, ignored -> {}, this::finishAttributeDesigner));
    }

    private void refreshAttributeDesigner() {
        refreshAttributeDesigner(true);
    }

    private void refreshAttributeDesignerContent(boolean preserveScroll) {
        if (attributePanelStaticWidgetCount > 0) {
            syncAttributeDirtyState();
            refreshAttributeComponentList(preserveScroll);
            return;
        }
        refreshAttributeDesigner(preserveScroll);
    }

    private void refreshAttributeDesigner(boolean preserveScroll) {
        if (!attributeDesignerOpen || attributePanel == null || attributeDesignerPanel == null) {
            return;
        }
        Container container = attributePanel.container();
        float previousScroll = container.getScrollOffset();
        clearAttributePanelWidgets(container);
        selectedAttributeRowWidget = null;
        AttributeProjection projection = currentAttributeProjection();
        if (projection == null) {
            hideAttributeDesigner();
            return;
        }
        String source = projection.schemaSource();
        requestCatalog(source);
        if (activeAttributeComponents == null) {
            activeAttributeComponents = initialAttributeComponents(projection);
        }
        int rowWidth = attributeRowWidth();
        MountableButtonWidget header = new MountableButtonWidget.Builder("Item Components")
            .description(attributeDesignerSummary(projection))
            .iconPath("item.png")
            .addButton(new SquareButtonWidget.Builder().imagePath("close.png").hint("Close").entranceAnimation(false).onClick(this::hideAttributeDesigner).build())
            .build();
        header.setSize(rowWidth, 30);
        attributeHeaderWidget = header;
        insertAttributePanelWidget(container, header);
        TextInputWidget search = new TextInputWidget.Builder()
            .text(activeAttributeQuery)
            .placeholder("Component Name")
            .forcePlaceholder(false)
            .size(Math.max(120, rowWidth - 8), 18)
            .build();
        search.setOnChange(() -> {
            activeAttributeQuery = search.getText();
            refreshAttributeComponentList(true, true);
        });
        attributeSearchInput = search;
        RowWidget searchRow = new RowWidget.Builder()
            .size(rowWidth - 8, 18)
            .padding(4)
            .addWidget(search)
            .build();
        TitledRowWidget searchPanel = new TitledRowWidget.Builder()
            .title("Search")
            .description("Type Name Or Purpose")
            .size(rowWidth, 36)
            .gap(4)
            .addWidget(searchRow)
            .build();
        insertAttributePanelWidget(container, searchPanel);
        if (attributeRestoreSearchFocus) {
            searchPanel.focusFirstFocusableChild();
        }
        attributePanelStaticWidgetCount = attributePanelWidgets.size();
        addAttributeValidationRows(container, rowWidth);
        populateAttributeComponents(container, source, activeAttributeComponents, activeAttributeQuery);
        container.updateWidgetPositions();
        if (preserveScroll) {
            container.setTargetScrollOffset(previousScroll);
        } else if (selectedAttributeRowWidget != null) {
            container.scrollToWidget(selectedAttributeRowWidget);
        } else {
            container.setScrollOffset(0);
        }
        attributeRestoreSearchFocus = false;
    }

    private void refreshAttributeComponentList(boolean preserveScroll) {
        refreshAttributeComponentList(preserveScroll, false);
    }

    private void refreshAttributeComponentList(boolean preserveScroll, boolean restoreSearchFocus) {
        if (!attributeDesignerOpen || attributePanel == null || attributeDesignerPanel == null) {
            return;
        }
        AttributeProjection projection = currentAttributeProjection();
        if (projection == null) {
            hideAttributeDesigner();
            return;
        }
        Container container = attributePanel.container();
        float previousScroll = container.getScrollOffset();
        while (attributePanelWidgets.size() > attributePanelStaticWidgetCount) {
            attributePanelWidgets.removeLast();
        }
        selectedAttributeRowWidget = null;
        String source = projection.schemaSource();
        int rowWidth = attributeRowWidth();
        collectingAttributePanelOrder = true;
        try {
            addAttributeValidationRows(container, rowWidth);
            populateAttributeComponents(container, source, activeAttributeComponents, activeAttributeQuery);
        } finally {
            collectingAttributePanelOrder = false;
        }
        container.replaceWidgetsFromIndex(attributePanelStaticWidgetCount, attributePanelWidgets.subList(attributePanelStaticWidgetCount, attributePanelWidgets.size()), false);
        if (preserveScroll) {
            container.setTargetScrollOffset(previousScroll);
        } else if (selectedAttributeRowWidget != null) {
            container.scrollToWidget(selectedAttributeRowWidget);
        }
        if (attributeSearchInput != null && (restoreSearchFocus || attributeSearchInput.isFocused())) {
            setFocusedWidget(attributeSearchInput);
            attributeSearchInput.setFocused(true);
        }
    }

    private void syncAttributeDirtyState() {
        if (!attributeDesignerOpen) {
            return;
        }
        if (attributeHeaderWidget != null) {
            AttributeProjection projection = currentAttributeProjection();
            if (projection != null) {
                attributeHeaderWidget.setDescription(attributeDesignerSummary(projection));
            }
        }
    }

    private void hideAttributeDesigner() {
        if (isItemComponentEditorOpen()) {
            closeItemComponentEditor();
            return;
        }
        finishAttributeDesigner();
    }

    private void finishAttributeDesigner() {
        closeStudioSelector();
        setFocusedWidget(null);
        if (attributePanel != null) {
            clearAttributePanelWidgets(attributePanel.container());
            attributePanel.hideImmediately();
        } else if (attributeDesignerPanel != null) {
            attributeDesignerPanel.hide();
        }
        restoreAttributeDesignerContentBrowser();
        attributeDesignerOpen = false;
        if (contentStudioPanel != null) {
            contentStudioPanel.show();
        }
        attributeDesignerHidContentBrowser = false;
        activeAttributeComponents = new LinkedHashMap<>();
        attributePreviewValues.clear();
        editedAttributeComponents.clear();
        selectedAttributeComponent = "";
        attributeHeaderWidget = null;
        attributeSearchInput = null;
        attributePanelStaticWidgetCount = 0;
        attributeComponentRowStates.clear();
        attributeSectionRows.clear();
        attributeStatusRows.clear();
        refreshContentPanel();
        updatePositions();
    }

    private void restoreAttributeDesignerContentBrowser() {
        if (attributeDesignerHidContentBrowser && contentDesignerParent instanceof StudioScreen studioScreen) {
            studioScreen.setStudioContentBrowserTemporarilyHidden(false);
        }
        attributeDesignerHidContentBrowser = false;
    }

    private boolean isAttributeDesignerInteractive() {
        return attributeDesignerOpen && attributePanel != null && attributePanel.isVisible();
    }

    private String attributeDesignerSummary(AttributeProjection projection) {
        int count = activeAttributeComponents != null ? activeAttributeComponents.size() : 0;
        String source = projection != null ? projection.schemaSource() : ATTRIBUTE_SCHEMA_SOURCE;
        boolean loaded = hasAttributeCatalog(source);
        String target = projection != null && !projection.material().isBlank() ? projection.material() : "Material";
        return target + " | " + (count == 1 ? "1 Component" : count + " Components") + " | " + (loaded ? "Synced" : "Syncing");
    }

    private DropDownWidget<String> panelDropdown(List<String> choices, String selected, Consumer<String> onChange) {
        List<String> options = normalizedOptions(choices, selected);
        return new DropDownWidget.Builder<>(options)
            .selectedItem(resolveSelectedOption(options, selected))
            .onSelectionChanged(value -> {
                if (isRealOption(value)) {
                    onChange.accept(value);
                }
            })
            .size(240, 18)
            .maxVisibleItems(10)
            .entranceAnimation(false)
            .build();
    }

    private void addAttributeValidationRows(Container container, int rowWidth) {
        if (attributeValidationErrors.isEmpty()) {
            return;
        }
        int count = 0;
        for (Map<String, Object> error : attributeValidationErrors) {
            if (count >= 4) {
                insertAttributePanelWidget(container, attributeStatusRow("Errors", (attributeValidationErrors.size() - count) + " More", rowWidth, true, "validationMore"));
                return;
            }
            String component = String.valueOf(error.getOrDefault("component", ""));
            String message = String.valueOf(error.getOrDefault("message", "Invalid Component"));
            insertAttributePanelWidget(container, attributeStatusRow(component.isBlank() ? "Error" : component, message, rowWidth, true, "validation" + count));
            count++;
        }
    }

    private MountableButtonWidget attributeStatusRow(String title, String description, int width, boolean danger) {
        return attributeStatusRow(title, description, width, danger, title + "\n" + description);
    }

    private MountableButtonWidget attributeStatusRow(String title, String description, int width, boolean danger, String identity) {
        String key = identity + "\n" + danger;
        MountableButtonWidget row = attributeStatusRows.get(key);
        if (row == null) {
            row = new MountableButtonWidget.Builder(title)
                .description(description)
                .build();
            if (danger) {
                row.setAccent(ThemeManager.getAccent("danger"));
            } else {
                row.setActive(false);
            }
            attributeStatusRows.put(key, row);
        }
        row.setName(title);
        row.setDescription(description);
        row.setSize(width, 28);
        return row;
    }

    private void populateAttributeComponents(Container container, String source, Map<String, Object> components, String query) {
        AttributeProjection projection = currentAttributeProjection();
        Map<String, OptionCatalogItem> catalog = projection != null && Objects.equals(source, projection.schemaSource())
            ? projection.catalog() : Map.of();
        Map<String, Object> displayComponents = displayAttributeComponents(components, catalog);
        int rowWidth = attributeRowWidth();
        ensureAttributeComponentRowStates(catalog, rowWidth);
        List<String> active = activeComponents(displayComponents, catalog);
        String material = projection != null ? projection.material() : "";
        List<OptionCatalogItem> available = availableComponents(displayComponents, catalog, query, material);
        ensureSelectedAttribute(displayComponents, active);
        insertAttributePanelWidget(container, attributeSectionHeader("Current Components", active.isEmpty() ? "None" : active.size() + " Total", rowWidth));
        if (active.isEmpty()) {
            insertAttributePanelWidget(container, attributeStatusRow("No Components", "Choose Component", rowWidth, false));
        }
        for (String id : active) {
            addAttributeComponentBlock(container, displayComponents, id, catalog.get(id), rowWidth);
        }
        if (available.isEmpty()) {
            insertAttributePanelWidget(container, attributeStatusRow(componentCatalogStatus(source, query), query != null && !query.isBlank() ? "No Matches" : "Synced", rowWidth, false));
        }
        String currentGroup = "";
        for (OptionCatalogItem item : available) {
            String id = item.getValue();
            String group = attributeBrowseGroup(id, item);
            if (!group.equals(currentGroup)) {
                currentGroup = group;
                insertAttributePanelWidget(container, attributeSectionHeader(group, attributeBrowseGroupDescription(group), rowWidth));
            }
            insertAttributePanelWidget(container, attributeComponentRow(id, components, item, rowWidth));
        }
    }

    private void ensureAttributeComponentRowStates(Map<String, OptionCatalogItem> catalog, int width) {
        for (OptionCatalogItem item : catalog.values()) {
            if (item == null || item.getValue() == null || item.getValue().isBlank()) {
                continue;
            }
            AttributeComponentRowState state = attributeComponentRowStates.computeIfAbsent(item.getValue(), this::createAttributeComponentRowState);
            state.item = item;
            state.row.setName(item.getLabel());
            state.row.setSize(width, 28);
        }
    }

    private void ensureSelectedAttribute(Map<String, Object> components, List<String> active) {
        if (!selectedAttributeComponent.isBlank() && !components.containsKey(selectedAttributeComponent) && !attributeComponentRowStates.containsKey(selectedAttributeComponent)) {
            selectedAttributeComponent = "";
        }
    }

    private void addAttributeComponentBlock(Container container, Map<String, Object> components, String id, OptionCatalogItem item, int rowWidth) {
        if (id == null || id.isBlank()) {
            return;
        }
        MountableButtonWidget row = attributeComponentRow(id, components, item, rowWidth);
        insertAttributePanelWidget(container, row);
    }

    private List<AnimatedWidget> collectAttributeEditorWidgets(Runnable builder) {
        List<AnimatedWidget> previous = collectingAttributeEditorWidgets;
        List<AnimatedWidget> widgets = new ArrayList<>();
        collectingAttributeEditorWidgets = widgets;
        try {
            builder.run();
            return widgets;
        } finally {
            collectingAttributeEditorWidgets = previous;
        }
    }

    private MountableButtonWidget attributeSectionHeader(String title, String description, int width) {
        MountableButtonWidget row = attributeSectionRows.get(title);
        if (row == null) {
            row = new MountableButtonWidget.Builder(title)
                .description(description)
                .build();
            row.setActive(false);
            attributeSectionRows.put(title, row);
        }
        row.setName(title);
        row.setDescription(description);
        row.setSize(width, 24);
        return row;
    }

    private String componentCatalogStatus(String source, String query) {
        if (!hasAttributeCatalog(source)) {
            return "Loading";
        }
        if (query != null && !query.isBlank()) {
            return "No Matches";
        }
        return "No Components";
    }

    private MountableButtonWidget attributeComponentRow(String id, Map<String, Object> components, OptionCatalogItem item, int width) {
        AttributeComponentRowState state = attributeComponentRowStates.computeIfAbsent(id, this::createAttributeComponentRowState);
        state.item = item;
        syncAttributeComponentRow(state, components, width);
        return state.row;
    }

    private AttributeComponentRowState createAttributeComponentRowState(String id) {
        ToggleWidget toggle = new ToggleWidget.Builder()
            .size(32, 18)
            .onChange(enabled -> setAttributeComponentEnabled(id, enabled))
            .entranceAnimation(false)
            .build();
        MountableButtonWidget row = new MountableButtonWidget.Builder(componentLabel(id))
            .onClick(() -> handleAttributeComponentClick(id))
            .addWidget(toggle)
            .build();
        return new AttributeComponentRowState(id, row, toggle);
    }

    private void syncAttributeComponentRow(AttributeComponentRowState state, Map<String, Object> components, int width) {
        String id = state.id;
        boolean active = components.containsKey(id);
        boolean selected = id.equals(selectedAttributeComponent);
        Object rowValue = active ? components.get(id) : attributePreviewValues.get(id);
        state.row.setName(state.item != null ? state.item.getLabel() : componentLabel(id));
        state.row.setDescription(attributeRowDescription(id, state.item, active, selected, rowValue));
        state.toggle.setValue(active);
        state.toggle.setActive(true);
        state.row.setSize(width, 28);
        state.row.setSelected(selected);
        if (selected) {
            if (!state.row.hasVisibleEmbeddedBody() || state.editorDirty) {
                if (state.editorWidgets == null || state.editorDirty) {
                    Map<String, Object> editorComponents = active ? components : previewAttributeComponents(id, state.item);
                    state.editorWidgets = collectAttributeEditorWidgets(() -> addAttributeEditorRows(attributePanel.container(), editorComponents, id, editorComponents.get(id)));
                    state.editorDirty = false;
                }
                state.row.setEmbeddedBody(state.editorWidgets, true);
            }
            selectedAttributeRowWidget = state.row;
        } else if (state.row.hasVisibleEmbeddedBody()) {
            state.row.setEmbeddedBody(null, false);
        }
        if (!active) {
            state.editorDirty = false;
        }
    }

    private void handleAttributeComponentClick(String id) {
        boolean active = displayAttributeComponentsForCurrent().containsKey(id);
        selectedAttributeComponent = id.equals(selectedAttributeComponent) ? "" : id;
        selectedAttributeRowWidget = null;
        if (!selectedAttributeComponent.isBlank() && !active) {
            AttributeComponentRowState state = attributeComponentRowStates.get(id);
            ensureAttributePreviewValue(id, state != null ? state.item : null);
        }
        syncAttributeRowsInPlace();
        if (attributePanel != null) {
            Container container = attributePanel.container();
            container.updateWidgetPositions();
            if (selectedAttributeRowWidget != null) {
                container.scrollToWidget(selectedAttributeRowWidget);
            }
        }
    }

    private void setAttributeComponentEnabled(String id, boolean enabled) {
        AttributeComponentRowState state = attributeComponentRowStates.get(id);
        Map<String, Object> current = attributeDraftComponents(activeAttributeComponents);
        boolean active = current.containsKey(id);
        if (enabled == active) {
            return;
        }
        if (enabled) {
            Object previewValue = attributePreviewValues.get(id);
            if (previewValue != null) {
                current.putIfAbsent(id, copyAttributeValue(previewValue));
                selectedAttributeComponent = id;
                editedAttributeComponents.add(id);
                updateAttributeDesignerDraft(current);
            } else if (addDefaultComponent(current, id, state != null ? state.item : null)) {
                selectedAttributeComponent = id;
                editedAttributeComponents.add(id);
                updateAttributeDesignerDraft(current);
            }
            return;
        }
        current.remove(id);
        attributePreviewValues.remove(id);
        editedAttributeComponents.add(id);
        if (id.equals(selectedAttributeComponent)) {
            selectedAttributeComponent = "";
        }
        updateAttributeDesignerDraft(current);
    }

    private boolean addDefaultComponent(Map<String, Object> components, String id, OptionCatalogItem item) {
        Object value = item != null ? defaultComponentValueFromItem(item) : defaultComponentValue(id);
        if (isEmptyAttributeObject(value)) {
            Object fallback = defaultComponentValue(id);
            if (fallback != null) {
                value = fallback;
            }
        }
        if (value == null) {
            notifyComponentsLoading();
            return false;
        }
        components.putIfAbsent(id, copyAttributeValue(value));
        return true;
    }

    private Map<String, Object> previewAttributeComponents(String id, OptionCatalogItem item) {
        Object value = ensureAttributePreviewValue(id, item);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put(id, copyAttributeValue(value));
        return preview;
    }

    private Object ensureAttributePreviewValue(String id, OptionCatalogItem item) {
        Object existing = attributePreviewValues.get(id);
        if (existing != null) {
            return existing;
        }
        Object value = item != null ? defaultComponentValueFromItem(item) : defaultComponentValue(id);
        if (isEmptyAttributeObject(value)) {
            Object fallback = defaultComponentValue(id);
            if (fallback != null) {
                value = fallback;
            }
        }
        if (value == null) {
            value = Map.of();
        }
        attributePreviewValues.put(id, copyAttributeValue(value));
        return value;
    }

    private void notifyComponentsLoading() {
        new Notification("Components", "Syncing Schema", Notification.Type.INFO);
    }

    private void updateAttributeDesignerDraft(Map<String, Object> components) {
        updateAttributeDesignerDraft(components, true);
    }

    private void updateAttributeDesignerDraft(Map<String, Object> components, boolean refreshList) {
        Map<String, Object> draft = copyAttributeComponents(components);
        commitAttributeDesignerDraft(draft);
        if (refreshList) {
            refreshAttributeComponentList(false);
        }
    }

    private void commitAttributeDesignerDraft(Map<String, Object> components) {
        activeAttributeComponents = normalizeAttributeComponents(components);
        setProperty("components", quickEditMode ? copyAttributeComponents(activeAttributeComponents) : persistedAttributeComponents(activeAttributeComponents));
        attributeValidationErrors = List.of();
        updateSummary();
        refreshContentPanelIfAttributeDesignerClosed();
        syncAttributeDirtyState();
        syncAttributeRowsInPlace();
    }

    private Map<String, Object> persistedAttributeComponents(Map<String, Object> components) {
        Map<String, Object> normalized = normalizeAttributeComponents(components);
        AttributeProjection projection = currentAttributeProjection();
        if (projection == null) {
            return normalized;
        }
        for (String id : materialBackedAttributeIds(projection)) {
            if (!normalized.containsKey(id)) {
                continue;
            }
            if (!editedAttributeComponents.contains(id)) {
                normalized.remove(id);
                continue;
            }
            Object defaultValue = defaultCommonComponentValue(id, projection);
            if (defaultValue != null && copyAttributeValue(defaultValue).equals(copyAttributeValue(normalized.get(id)))) {
                normalized.remove(id);
            }
        }
        return normalized;
    }

    private void refreshContentPanelIfAttributeDesignerClosed() {
        if (!attributeDesignerOpen) {
            refreshContentPanel();
        }
    }

    private void markAttributeEditorDirty(String componentId) {
        AttributeComponentRowState state = attributeComponentRowStates.get(componentId);
        if (state == null) {
            return;
        }
        state.editorDirty = true;
        if (!state.row.hasVisibleEmbeddedBody()) {
            state.editorWidgets = null;
        }
    }

    private void syncAttributeRowsInPlace() {
        if (!attributeDesignerOpen || attributePanel == null || attributeComponentRowStates.isEmpty()) {
            return;
        }
        int rowWidth = attributeRowWidth();
        Map<String, Object> displayComponents = displayAttributeComponentsForCurrent();
        for (AttributeComponentRowState state : attributeComponentRowStates.values()) {
            syncAttributeComponentRow(state, displayComponents, rowWidth);
        }
    }

    private String attributeRowDescription(String id, OptionCatalogItem item, boolean active, boolean selected, Object value) {
        List<String> parts = new ArrayList<>();
        if (active) {
            if (!hasIntentionalAttributeEditor(id)) {
                return "Not Editable Here";
            }
            String summary = componentValueSummary(id, value);
            parts.add(summary.isBlank() ? "Set Up" : summary);
        } else if (selected) {
            parts.add("Preview");
        }
        String description = attributeCatalogDescription(item);
        if (!description.isBlank()) {
            parts.add(description);
        }
        if (parts.isEmpty()) {
            parts.add(active ? "Set Up" : "Can Add");
        }
        return joinAttributeDescriptionParts(parts);
    }

    private String attributeCatalogDescription(OptionCatalogItem item) {
        return item != null ? cleanAttributeDescriptionPart(item.getDescription()) : "";
    }

    private String joinAttributeDescriptionParts(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            String cleaned = cleanAttributeDescriptionPart(part);
            if (cleaned.isBlank() || !seen.add(cleaned.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(cleaned);
        }
        return builder.toString();
    }

    private String cleanAttributeDescriptionPart(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String componentValueSummary(String id, Object value) {
        if ("minecraft:consumable".equals(id)) {
            String seconds = consumableSeconds(value);
            return seconds.isBlank() ? "Use Settings" : "Use Time " + seconds + "s";
        }
        if ("minecraft:food".equals(id)) {
            String nutrition = foodNutrition(value);
            return nutrition.isBlank() ? "Food Settings" : "Food Restored " + nutrition;
        }
        if ("minecraft:use_cooldown".equals(id)) {
            return useCooldownSummary(value);
        }
        if ("minecraft:use_remainder".equals(id)) {
            String stack = itemStackLine(objectValue(value));
            return stack.isBlank() ? "Remainder Item" : "Leaves " + stack;
        }
        if ("minecraft:enchantment_glint_override".equals(id)) {
            return Boolean.TRUE.equals(value) ? "Glint Shown" : "Glint Hidden";
        }
        if ("minecraft:dyed_color".equals(id)) {
            return "Color " + formatColorValue(dyedColorRgb(value));
        }
        if ("minecraft:enchantments".equals(id) || "minecraft:stored_enchantments".equals(id)) {
            int count = enchantmentLevels(value).size();
            return count == 1 ? "1 Enchantment" : count + " Enchantments";
        }
        if ("minecraft:attribute_modifiers".equals(id)) {
            int count = attributeModifiers(value).size();
            return count == 1 ? "1 Modifier" : count + " Modifiers";
        }
        if ("minecraft:trim".equals(id)) {
            return trimSummary(value);
        }
        if ("minecraft:firework_explosion".equals(id) || "minecraft:fireworks".equals(id)) {
            return fireworkSummary(value);
        }
        if ("minecraft:banner_patterns".equals(id)) {
            int count = bannerPatterns(value).size();
            return count == 1 ? "1 Pattern" : count + " Patterns";
        }
        if ("minecraft:charged_projectiles".equals(id)) {
            int count = arrayValue(value).size();
            return count == 1 ? "1 Projectile" : count + " Projectiles";
        }
        if ("minecraft:bundle_contents".equals(id)) {
            int count = arrayValue(value).size();
            return count == 1 ? "1 Item" : count + " Items";
        }
        if ("minecraft:container".equals(id)) {
            int count = arrayValue(value).size();
            return count == 1 ? "1 Slot" : count + " Slots";
        }
        if ("minecraft:potion_contents".equals(id)) {
            return potionContentsSummary(value);
        }
        if ("minecraft:can_break".equals(id) || "minecraft:can_place_on".equals(id)) {
            int count = blockPredicates(value).size();
            return count == 1 ? "1 Block Rule" : count + " Block Rules";
        }
        if ("minecraft:damage_resistant".equals(id)) {
            return damageResistantSummary(value);
        }
        if ("minecraft:weapon".equals(id)) {
            return weaponSummary(value);
        }
        if ("minecraft:blocks_attacks".equals(id)) {
            return blocksAttacksSummary(value);
        }
        if ("minecraft:piercing_weapon".equals(id)) {
            return piercingWeaponSummary(value);
        }
        if ("minecraft:kinetic_weapon".equals(id)) {
            return kineticWeaponSummary(value);
        }
        if ("minecraft:attack_range".equals(id)) {
            return attackRangeSummary(value);
        }
        if ("minecraft:swing_animation".equals(id)) {
            return swingAnimationSummary(value);
        }
        if ("minecraft:use_effects".equals(id)) {
            return useEffectsSummary(value);
        }
        if ("minecraft:repairable".equals(id)) {
            return repairableSummary(value);
        }
        if ("minecraft:container_loot".equals(id)) {
            return containerLootSummary(value);
        }
        if ("minecraft:tooltip_style".equals(id)) {
            return "Style " + formatComponentValue(value);
        }
        if ("minecraft:writable_book_content".equals(id) || "minecraft:written_book_content".equals(id)) {
            int count = arrayValue(objectValue(value).get("pages")).size();
            return count == 1 ? "1 Page" : count + " Pages";
        }
        if ("minecraft:pot_decorations".equals(id)) {
            int count = arrayValue(value).size();
            return count == 1 ? "1 Decoration" : count + " Decorations";
        }
        if ("minecraft:tool".equals(id)) {
            int count = arrayValue(objectValue(value).get("rules")).size();
            return count == 1 ? "1 Tool Rule" : count + " Tool Rules";
        }
        if ("minecraft:death_protection".equals(id)) {
            int count = arrayValue(objectValue(value).get("death_effects")).size();
            return count == 1 ? "1 Death Effect" : count + " Death Effects";
        }
        if ("minecraft:bees".equals(id)) {
            int count = arrayValue(value).size();
            return count == 1 ? "1 Bee" : count + " Bees";
        }
        if ("minecraft:creative_slot_lock".equals(id)) {
            return "Creative Locked";
        }
        if ("minecraft:lock".equals(id)) {
            return value != null && !value.toString().isBlank() ? "Lock " + value : "No Lock Key";
        }
        if ("minecraft:suspicious_stew_effects".equals(id)) {
            int count = arrayValue(value).size();
            return count == 1 ? "1 Effect" : count + " Effects";
        }
        if ("minecraft:lodestone_tracker".equals(id)) {
            Map<String, Object> target = objectValue(objectValue(value).get("target"));
            return "Dimension " + target.getOrDefault("dimension", "minecraft:overworld");
        }
        if ("minecraft:block_state".equals(id)) {
            int count = objectValue(value).size();
            return count == 1 ? "1 Block State" : count + " Block States";
        }
        if ("minecraft:block_entity_data".equals(id) || "minecraft:bucket_entity_data".equals(id) || "minecraft:entity_data".equals(id)) {
            Map<String, Object> map = objectValue(value);
            Object entityId = map.get("id");
            return entityId != null && !entityId.toString().isBlank() ? "ID " + entityId : map.size() + " Fields";
        }
        if ("minecraft:debug_stick_state".equals(id)) {
            int count = objectValue(value).size();
            return count == 1 ? "1 Debug State" : count + " Debug States";
        }
        if ("minecraft:recipes".equals(id)) {
            int count = arrayValue(value).size();
            return count == 1 ? "1 Recipe" : count + " Recipes";
        }
        if ("minecraft:equippable".equals(id)) {
            return equippableSummary(value);
        }
        if ("minecraft:enchantable".equals(id)) {
            return "Enchantability " + enchantableValue(value);
        }
        if ("minecraft:instrument".equals(id)) {
            return "Instrument " + formatComponentValue(value);
        }
        if ("minecraft:jukebox_playable".equals(id)) {
            return jukeboxPlayableSummary(value);
        }
        if ("minecraft:glider".equals(id)) {
            return "Glider";
        }
        if ("minecraft:intangible_projectile".equals(id)) {
            return "Intangible Projectile";
        }
        return "";
    }

    private void addAttributeEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        if (addCommonAttributeEditorRows(container, components, componentId, value)) {
            return;
        }
        if (addSchemaAttributeEditorRows(container, components, componentId, value)) {
            return;
        }
        addUnsupportedAttributeEditorRows(container, components, componentId);
    }

    private boolean addSchemaAttributeEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        Map<String, Object> schema = attributeComponentSchema(componentId);
        if (schema.isEmpty()) {
            return false;
        }
        addSchemaEditorRows(container, components, componentId, componentId, value, schema, componentLabel(componentId));
        return true;
    }

    private void addSchemaEditorRows(Container container, Map<String, Object> components, String componentId, String path, Object value, Map<String, Object> schema, String title) {
        String kind = schemaKind(schema, value);
        switch (kind) {
            case "boolean" -> {
                ToggleWidget toggle = new ToggleWidget.Builder()
                    .label(title)
                    .toggled(Boolean.TRUE.equals(value))
                    .size(180, 18)
                    .onChange(next -> updateNestedAttributeComponent(components, componentId, path, next))
                    .entranceAnimation(false)
                    .build();
                insertAttributePanelWidget(container, attributeEditorRow(title, toggle));
            }
            case "number" -> insertAttributePanelWidget(container, attributeEditorRow(title, attributeNumberEditor(components, componentId, path, value, title)));
            case "array" -> addSchemaArrayEditorRow(container, components, componentId, path, value, title);
            case "object" -> addSchemaObjectEditorRows(container, components, componentId, path, value, schema, title);
            default -> {
                String source = attributeSchemaCatalogSource(componentId, path);
                if (!source.isBlank()) {
                    boolean namespaced = attributeSchemaCatalogUsesNamespacedValues(source);
                    insertAttributePanelWidget(container, attributeEditorRow(title, attributeSearchTextEditor(components, componentId, path, value, catalogOptions(source), false, source, namespaced)));
                    return;
                }
                TextInputWidget input = new TextInputWidget.Builder()
                    .text(formatComponentValue(value))
                    .placeholder(title)
                    .forcePlaceholder(false)
                    .size(Math.max(140, attributeRowWidth() - 80), 18)
                    .build();
                input.setOnChange(() -> updateNestedAttributeComponent(components, componentId, path, input.getText()));
                insertAttributePanelWidget(container, attributeEditorRow(title, input));
            }
        }
    }

    private String attributeSchemaCatalogSource(String componentId, String path) {
        String key = componentId != null ? componentId : "";
        String leaf = path != null && path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
        if ("minecraft:damage_type".equals(key)) {
            return "server:minecraft:damage_type";
        }
        if ("minecraft:dye".equals(key) || "minecraft:base_color".equals(key) || key.endsWith("/collar") || key.endsWith("/color") || key.endsWith("/base_color") || key.endsWith("/pattern_color")) {
            return "server:minecraft:dye_color";
        }
        if ("minecraft:provides_trim_material".equals(key) || "minecraft:trim".equals(key) && "material".equals(leaf)) {
            return "server:minecraft:trim_material";
        }
        if ("minecraft:provides_banner_patterns".equals(key) || "minecraft:banner_patterns".equals(key) && "pattern".equals(leaf)) {
            return "server:minecraft:banner_pattern";
        }
        if ("minecraft:break_sound".equals(key) || "minecraft:note_block_sound".equals(key) || "sound".equals(leaf) || "hit_sound".equals(leaf) || "block_sound".equals(leaf)) {
            return "server:minecraft:sound";
        }
        return switch (key) {
            case "minecraft:axolotl/variant" -> "server:minecraft:axolotl_variant";
            case "minecraft:cat/variant" -> "server:minecraft:cat_variant";
            case "minecraft:cat/sound_variant" -> "server:minecraft:cat_sound_variant";
            case "minecraft:chicken/variant" -> "server:minecraft:chicken_variant";
            case "minecraft:chicken/sound_variant" -> "server:minecraft:chicken_sound_variant";
            case "minecraft:cow/variant" -> "server:minecraft:cow_variant";
            case "minecraft:cow/sound_variant" -> "server:minecraft:cow_sound_variant";
            case "minecraft:fox/variant" -> "server:minecraft:fox_variant";
            case "minecraft:frog/variant" -> "server:minecraft:frog_variant";
            case "minecraft:horse/variant" -> "server:minecraft:horse_variant";
            case "minecraft:llama/variant" -> "server:minecraft:llama_variant";
            case "minecraft:mooshroom/variant" -> "server:minecraft:mooshroom_variant";
            case "minecraft:painting/variant" -> "server:minecraft:painting_variant";
            case "minecraft:parrot/variant" -> "server:minecraft:parrot_variant";
            case "minecraft:pig/variant" -> "server:minecraft:pig_variant";
            case "minecraft:pig/sound_variant" -> "server:minecraft:pig_sound_variant";
            case "minecraft:rabbit/variant" -> "server:minecraft:rabbit_variant";
            case "minecraft:salmon/size" -> "server:minecraft:salmon_size";
            case "minecraft:tropical_fish/pattern" -> "server:minecraft:tropical_fish_pattern";
            case "minecraft:villager/variant" -> "server:minecraft:villager_type";
            case "minecraft:wolf/variant" -> "server:minecraft:wolf_variant";
            case "minecraft:wolf/sound_variant" -> "server:minecraft:wolf_sound_variant";
            case "minecraft:zombie_nautilus/variant" -> "server:minecraft:zombie_nautilus_variant";
            default -> "";
        };
    }

    private boolean attributeSchemaCatalogUsesNamespacedValues(String source) {
        return switch (source) {
            case "server:minecraft:axolotl_variant",
                "server:minecraft:dye_color",
                "server:minecraft:fox_variant",
                "server:minecraft:horse_variant",
                "server:minecraft:llama_variant",
                "server:minecraft:mooshroom_variant",
                "server:minecraft:parrot_variant",
                "server:minecraft:rabbit_variant",
                "server:minecraft:salmon_size",
                "server:minecraft:tropical_fish_pattern" -> false;
            default -> true;
        };
    }

    private void addSchemaObjectEditorRows(Container container, Map<String, Object> components, String componentId, String path, Object value, Map<String, Object> schema, String title) {
        Map<String, Object> fields = schemaFields(schema);
        if (fields.isEmpty()) {
            insertAttributePanelWidget(container, attributeStatusRow(title, "No Editable Fields", attributeRowWidth(), false, "schemaEmpty" + componentId));
            return;
        }
        Map<String, Object> object = objectValue(value);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String field = entry.getKey();
            Map<String, Object> fieldSchema = schemaMap(entry.getValue());
            String childPath = path + "." + field;
            Object fieldValue = object.containsKey(field) ? object.get(field) : defaultValueForSchema(fieldSchema);
            addSchemaEditorRows(container, components, componentId, childPath, fieldValue, fieldSchema, humanizeAttributeToken(field));
        }
    }

    private void addSchemaArrayEditorRow(Container container, Map<String, Object> components, String componentId, String path, Object value, String title) {
        CodeEditorWidget editor = new CodeEditorWidget(0, 0, Math.max(180, attributeRowWidth() - 12), 78);
        ReSyncStudioPanelState.disableEntrance(editor);
        editor.setShowLineNumbers(false);
        editor.setShowSearchNavigation(false);
        editor.setDimNonMatchingLines(false);
        editor.setShowCursorLineHighlight(false);
        editor.setShowSearchMatchHighlight(false);
        editor.setWordWrap(true);
        editor.setText(schemaArrayText(value));
        editor.onChange = text -> updateNestedAttributeComponent(components, componentId, path, schemaArrayValue(text));
        insertAttributePanelWidget(container, attributeLargeEditorRow(title, editor, 94));
    }

    private boolean addCommonAttributeEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        if ("minecraft:consumable".equals(componentId)) {
            addConsumableEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:food".equals(componentId)) {
            addFoodEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:use_cooldown".equals(componentId)) {
            addUseCooldownEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:use_remainder".equals(componentId)) {
            addUseRemainderEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:enchantment_glint_override".equals(componentId)) {
            ToggleWidget toggle = new ToggleWidget.Builder()
                .label("Glint Enabled")
                .toggled(Boolean.TRUE.equals(value))
                .size(180, 18)
                .onChange(next -> updateNestedAttributeComponent(components, componentId, componentId, next))
                .entranceAnimation(false)
                .build();
            insertAttributePanelWidget(container, attributeEditorRow("Glint", toggle));
            return true;
        }
        if ("minecraft:custom_model_data".equals(componentId)) {
            addCustomModelDataEditorRow(container, components, componentId, value);
            return true;
        }
        if ("minecraft:item_model".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Model ID", attributeSearchTextEditor(components, componentId, componentId, value, materialModelOptions(), false, "server:minecraft:material")));
            return true;
        }
        if ("minecraft:item_name".equals(componentId) || "minecraft:custom_name".equals(componentId)) {
            addTextComponentEditorRow(container, components, componentId, value);
            return true;
        }
        if ("minecraft:lore".equals(componentId)) {
            addLoreEditorRow(container, components, componentId, value);
            return true;
        }
        if ("minecraft:tooltip_display".equals(componentId)) {
            addTooltipEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:tooltip_style".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Tooltip Style", attributeSearchTextEditor(components, componentId, componentId, value != null ? value : "minecraft:default", tooltipStyleOptions(), false, "")));
            return true;
        }
        if ("minecraft:writable_book_content".equals(componentId)) {
            addWritableBookContentEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:written_book_content".equals(componentId)) {
            addWrittenBookContentEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:attribute_modifiers".equals(componentId)) {
            addAttributeModifiersEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:trim".equals(componentId)) {
            addTrimEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:firework_explosion".equals(componentId)) {
            addFireworkExplosionEditorRows(container, components, componentId, componentId, value);
            return true;
        }
        if ("minecraft:fireworks".equals(componentId)) {
            addFireworksEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:banner_patterns".equals(componentId)) {
            addBannerPatternsEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:pot_decorations".equals(componentId)) {
            addPotDecorationsEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:charged_projectiles".equals(componentId)) {
            addItemStackListEditorRows(container, components, componentId, value, "Projectiles", false);
            return true;
        }
        if ("minecraft:bundle_contents".equals(componentId)) {
            addItemStackListEditorRows(container, components, componentId, value, "Items", false);
            return true;
        }
        if ("minecraft:container".equals(componentId)) {
            addItemStackListEditorRows(container, components, componentId, value, "Slots", true);
            return true;
        }
        if ("minecraft:can_break".equals(componentId)) {
            addBlockPredicateEditorRows(container, components, componentId, value, "Break Blocks");
            return true;
        }
        if ("minecraft:can_place_on".equals(componentId)) {
            addBlockPredicateEditorRows(container, components, componentId, value, "Place On");
            return true;
        }
        if ("minecraft:max_stack_size".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Stack Size", attributeStackSizeEditor(components, componentId, value)));
            return true;
        }
        if ("minecraft:max_damage".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Max Durability", attributeNumberEditor(components, componentId, componentId, value, "100")));
            return true;
        }
        if ("minecraft:damage".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Damage Used", attributeNumberEditor(components, componentId, componentId, value, "0")));
            return true;
        }
        if ("minecraft:repair_cost".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Repair Cost", attributeNumberEditor(components, componentId, componentId, value, "0")));
            return true;
        }
        if ("minecraft:rarity".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Rarity", attributeChoiceEditor(components, componentId, componentId, List.of("common", "uncommon", "rare", "epic"), String.valueOf(value != null ? value : "common"))));
            return true;
        }
        if ("minecraft:enchantable".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Enchantability", attributeNumberEditor(components, componentId, componentId + ".value", enchantableValue(value), "10")));
            return true;
        }
        if ("minecraft:ominous_bottle_amplifier".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Amplifier", attributeNumberEditor(components, componentId, componentId, value, "1")));
            return true;
        }
        if ("minecraft:instrument".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Instrument", attributeSearchTextEditor(components, componentId, componentId, value, instrumentOptions(), false, "server:minecraft:instrument")));
            return true;
        }
        if ("minecraft:jukebox_playable".equals(componentId)) {
            addJukeboxPlayableEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:dyed_color".equals(componentId)) {
            addDyedColorEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:enchantments".equals(componentId) || "minecraft:stored_enchantments".equals(componentId)) {
            addEnchantmentsEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:potion_contents".equals(componentId)) {
            addPotionContentsEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:damage_resistant".equals(componentId)) {
            addDamageResistantEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:weapon".equals(componentId)) {
            addWeaponEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:blocks_attacks".equals(componentId)) {
            addBlocksAttacksEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:piercing_weapon".equals(componentId)) {
            addPiercingWeaponEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:kinetic_weapon".equals(componentId)) {
            addKineticWeaponEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:attack_range".equals(componentId)) {
            addAttackRangeEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:swing_animation".equals(componentId)) {
            addSwingAnimationEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:use_effects".equals(componentId)) {
            addUseEffectsEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:repairable".equals(componentId)) {
            addRepairableEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:container_loot".equals(componentId)) {
            addContainerLootEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:tool".equals(componentId)) {
            addToolEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:death_protection".equals(componentId)) {
            addDeathProtectionEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:bees".equals(componentId)) {
            addBeesEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:creative_slot_lock".equals(componentId)) {
            addPresenceEditorRow(container, components, componentId, "Slot Lock", "Creative Locked");
            return true;
        }
        if ("minecraft:lock".equals(componentId)) {
            insertAttributePanelWidget(container, attributeEditorRow("Lock Key", attributeSearchTextEditor(components, componentId, componentId, value != null ? value : "", List.of(), false, "", false)));
            return true;
        }
        if ("minecraft:suspicious_stew_effects".equals(componentId)) {
            addSuspiciousStewEffectsEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:lodestone_tracker".equals(componentId)) {
            addLodestoneTrackerEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:block_state".equals(componentId)) {
            addBlockStateEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:block_entity_data".equals(componentId)) {
            addDataMapEditorRows(container, components, componentId, objectValue(value), "Block Entity", blockEntityTypeOptions(), "server:minecraft:block_entity_type");
            return true;
        }
        if ("minecraft:bucket_entity_data".equals(componentId)) {
            addDataMapEditorRows(container, components, componentId, objectValue(value), "Bucket Entity", entityTypeOptions(), "server:minecraft:entity_type");
            return true;
        }
        if ("minecraft:entity_data".equals(componentId)) {
            addDataMapEditorRows(container, components, componentId, objectValue(value), "Entity", entityTypeOptions(), "server:minecraft:entity_type");
            return true;
        }
        if ("minecraft:debug_stick_state".equals(componentId)) {
            addDebugStickStateEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:recipes".equals(componentId)) {
            addRecipeEditorRows(container, components, componentId, value);
            return true;
        }
        if ("minecraft:equippable".equals(componentId)) {
            addEquippableEditorRows(container, components, componentId, objectValue(value));
            return true;
        }
        if ("minecraft:unbreakable".equals(componentId)) {
            addPresenceEditorRow(container, components, componentId, "Durability", "Unbreakable");
            return true;
        }
        if ("minecraft:glider".equals(componentId)) {
            addPresenceEditorRow(container, components, componentId, "Movement", "Glider");
            return true;
        }
        if ("minecraft:intangible_projectile".equals(componentId)) {
            addPresenceEditorRow(container, components, componentId, "Projectile", "Intangible");
            return true;
        }
        return false;
    }

    private void addUnsupportedAttributeEditorRows(Container container, Map<String, Object> components, String componentId) {
        insertAttributePanelWidget(container, attributeStatusRow("Unsupported Attribute", "Remove It Or Use A Supported Attribute", attributeRowWidth(), true));
        AnimatedButton remove = new AnimatedButton.Builder()
            .label("Remove Attribute")
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .accentType(ThemeManager.getAccent("danger"))
            .entranceAnimation(false)
            .onClick(() -> {
                Map<String, Object> current = attributeDraftComponents(components);
                current.remove(componentId);
                selectedAttributeComponent = "";
                updateAttributeDesignerDraft(current);
            })
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Action", remove));
    }

    private void addPresenceEditorRow(Container container, Map<String, Object> components, String componentId, String title, String label) {
        ToggleWidget toggle = new ToggleWidget.Builder()
            .label(label)
            .toggled(components.containsKey(componentId))
            .size(180, 18)
            .onChange(next -> {
                updateNestedAttributeComponent(components, componentId, componentId, next ? Map.of() : null);
            })
            .entranceAnimation(false)
            .build();
        insertAttributePanelWidget(container, attributeEditorRow(title, toggle));
    }

    private void addCustomModelDataEditorRow(Container container, Map<String, Object> components, String componentId, Object value) {
        Object modelValue = customModelDataValue(value);
        TextInputWidget input = new TextInputWidget.Builder()
            .text(formatComponentValue(modelValue))
            .placeholder("Number")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        input.setOnChange(() -> {
            Object parsed = parseComponentEditorValue(modelValue instanceof Number ? modelValue : 0.0, input.getText().replace(',', '.'));
            updateNestedAttributeComponent(components, componentId, componentId, Map.of("floats", List.of(parsed)));
        });
        insertAttributePanelWidget(container, attributeEditorRow("Model Data", input));
    }

    private void addDyedColorEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        TextInputWidget color = new TextInputWidget.Builder()
            .text(formatColorValue(dyedColorRgb(value)))
            .placeholder("#RRGGBB")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        color.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId, dyedColorValue(currentAttributeValue(components, componentId), color.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Color", attributeColorPicker(color, false)));
    }

    private Object dyedColorValue(Object previous, String text) {
        return parseColorValue(dyedColorRgb(previous), text);
    }

    private int dyedColorRgb(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object rgb = map.get("rgb");
            if (rgb instanceof Number number) {
                return Math.clamp(number.intValue(), 0, 0xFFFFFF);
            }
            if (rgb != null) {
                return parseColorValue(0xFFFFFF, rgb.toString());
            }
        }
        if (value instanceof Number number) {
            return Math.clamp(number.intValue(), 0, 0xFFFFFF);
        }
        if (value != null) {
            return parseColorValue(0xFFFFFF, value.toString());
        }
        return 0xFFFFFF;
    }

    private String formatColorValue(int value) {
        return String.format(Locale.ROOT, "#%06X", Math.clamp(value, 0, 0xFFFFFF));
    }

    private int parseColorValue(int fallback, String text) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String value = text.trim();
        try {
            if (value.startsWith("#")) {
                return Math.clamp(Integer.parseInt(value.substring(1), 16), 0, 0xFFFFFF);
            }
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Math.clamp(Integer.parseInt(value.substring(2), 16), 0, 0xFFFFFF);
            }
            return Math.clamp(Integer.parseInt(value), 0, 0xFFFFFF);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String dyeColorHex(String value) {
        return switch (value != null ? value.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "") : "") {
            case "black" -> "#1D1D21";
            case "blue" -> "#3C44AA";
            case "brown" -> "#835432";
            case "cyan" -> "#169C9C";
            case "gray" -> "#474F52";
            case "green" -> "#5E7C16";
            case "light_blue" -> "#3AB3DA";
            case "light_gray" -> "#9D9D97";
            case "lime" -> "#80C71F";
            case "magenta" -> "#C74EBD";
            case "orange" -> "#F9801D";
            case "pink" -> "#F38BAA";
            case "purple" -> "#8932B8";
            case "red" -> "#B02E26";
            case "white" -> "#F9FFFE";
            case "yellow" -> "#FED83D";
            default -> "#FFFFFF";
        };
    }

    private void addEnchantmentsEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        Map<String, Integer> levels = enchantmentLevels(value);
        insertAttributePanelWidget(container, attributeStatusRow("Enchantments", levels.isEmpty() ? "No Enchantments" : levels.size() + " Configured", attributeRowWidth(), false, componentId + "EnchantmentsHeader"));
        int index = 1;
        for (Map.Entry<String, Integer> entry : levels.entrySet()) {
            addEnchantmentEntryRow(container, components, componentId, entry.getKey(), entry.getValue(), index);
            index++;
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Enchantment", attributeAddSearchRow("Enchantment", enchantmentOptions(), "server:minecraft:enchantment", valueId -> {
            Map<String, Integer> next = enchantmentLevels(currentAttributeValue(components, componentId));
            next.put(normalizeMinecraftKey(valueId), 1);
            updateAttributeComponentRoot(components, componentId, new LinkedHashMap<>(next), true);
        })));
    }

    private void addEnchantmentEntryRow(Container container, Map<String, Object> components, String componentId, String key, int level, int index) {
        String[] keyRef = {key};
        TextInputWidget idInput = attributeCompactInput(key, "Enchantment");
        TextInputWidget levelInput = attributeCompactInput(String.valueOf(level), "Level");
        idInput.setOnChange(() -> {
            Map<String, Integer> next = enchantmentLevels(currentAttributeValue(components, componentId));
            String previous = keyRef[0];
            int currentLevel = parsePositiveInt(levelInput.getText(), 1);
            next.remove(previous);
            String normalized = normalizeMinecraftKey(idInput.getText());
            next.put(normalized, currentLevel);
            keyRef[0] = normalized;
            updateAttributeComponentRoot(components, componentId, new LinkedHashMap<>(next), false);
        });
        levelInput.setOnChange(() -> {
            Map<String, Integer> next = enchantmentLevels(currentAttributeValue(components, componentId));
            next.put(keyRef[0], parsePositiveInt(levelInput.getText(), 1));
            updateAttributeComponentRoot(components, componentId, new LinkedHashMap<>(next), false);
        });
        TitledRowWidget row = attributeEntryRow("Enchantment " + index, keyRef[0], idInput, attributeSearchButton(idInput, enchantmentOptions(), "server:minecraft:enchantment", value -> {
            idInput.setText(normalizeMinecraftKey(value));
            idInput.runOnChange();
        }), levelInput, attributeDeleteButton(() -> {
            Map<String, Integer> next = enchantmentLevels(currentAttributeValue(components, componentId));
            next.remove(keyRef[0]);
            updateAttributeComponentRoot(components, componentId, new LinkedHashMap<>(next), true);
        }));
        insertAttributePanelWidget(container, row);
    }

    private String enchantmentText(Object value) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : enchantmentLevels(value).entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue());
        }
        return String.join("\n", lines);
    }

    private Object enchantmentValue(Object previous, String text) {
        Map<String, Integer> levels = parseEnchantmentLevels(text);
        return new LinkedHashMap<>(levels);
    }

    private Map<String, Integer> enchantmentLevels(Object value) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        Object rawLevels = value;
        if (value instanceof Map<?, ?> map) {
            rawLevels = map.containsKey("levels") ? map.get("levels") : map;
        }
        if (rawLevels instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    levels.put(normalizeMinecraftKey(entry.getKey().toString()), parsePositiveInt(entry.getValue(), 1));
                }
            }
        }
        return levels;
    }

    private Map<String, Integer> parseEnchantmentLevels(String text) {
        Map<String, Integer> levels = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return levels;
        }
        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String id = line;
            String amount = "1";
            int equals = line.indexOf('=');
            if (equals >= 0) {
                id = line.substring(0, equals).trim();
                amount = line.substring(equals + 1).trim();
            } else {
                String[] parts = line.split("\\s+");
                if (parts.length > 1) {
                    id = parts[0].trim();
                    amount = parts[1].trim();
                }
            }
            if (!id.isBlank()) {
                levels.put(normalizeMinecraftKey(id), parsePositiveInt(amount, 1));
            }
        }
        return levels;
    }

    private int enchantableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return parsePositiveInt(map.get("value"), 10);
        }
        return parsePositiveInt(value, 10);
    }

    private void addAttributeModifiersEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        List<Map<String, Object>> modifiers = attributeModifiers(value);
        insertAttributePanelWidget(container, attributeStatusRow("Modifiers", modifiers.isEmpty() ? "No Modifiers" : modifiers.size() + " Configured", attributeRowWidth(), false, componentId + "ModifiersHeader"));
        for (int i = 0; i < modifiers.size(); i++) {
            addAttributeModifierEntryRow(container, components, componentId, modifiers.get(i), i);
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Attribute", attributeAddSearchRow("Attribute", attributeTypeOptions(), "server:minecraft:attribute", valueId -> {
            List<Object> next = new ArrayList<>(arrayValue(currentAttributeValue(components, componentId)));
            next.add(parseAttributeModifierLine(normalizeMinecraftKey(valueId) + " 1 add_value mainhand"));
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private void addAttributeModifierEntryRow(Container container, Map<String, Object> components, String componentId, Map<String, Object> modifier, int index) {
        String type = String.valueOf(modifier.getOrDefault("type", "minecraft:attack_damage"));
        TextInputWidget amountInput = attributeCompactInput(formatComponentValue(modifier.getOrDefault("amount", 1.0)), "Amount");
        AttributeModifierDropdownRefs dropdowns = new AttributeModifierDropdownRefs();
        dropdowns.operation = attributeOperationDropdown(String.valueOf(modifier.getOrDefault("operation", "add_value")), value -> updateAttributeModifierEntry(components, componentId, index, type, amountInput.getText(), value, selectedDropdownValue(dropdowns.slot)));
        dropdowns.slot = attributeDropdown(List.of("any", "mainhand", "offhand", "head", "chest", "legs", "feet", "body"), String.valueOf(modifier.getOrDefault("slot", "any")), value -> updateAttributeModifierEntry(components, componentId, index, type, amountInput.getText(), selectedDropdownValue(dropdowns.operation), value));
        amountInput.setOnChange(() -> updateAttributeModifierEntry(components, componentId, index, type, amountInput.getText(), selectedDropdownValue(dropdowns.operation), selectedDropdownValue(dropdowns.slot)));
        insertAttributePanelWidget(container, attributeEntryRow(attributeValueLabel("server:minecraft:attribute", type), attributeOperationLabel(selectedDropdownValue(dropdowns.operation)) + " | " + titleCaseAttributeToken(selectedDropdownValue(dropdowns.slot)), amountInput, dropdowns.operation, dropdowns.slot, attributeDeleteButton(() -> removeListAttributeEntry(components, componentId, index))));
    }

    private void updateAttributeModifierEntry(Map<String, Object> components, String componentId, int index, String type, String amount, String operation, String slot) {
        List<Object> next = new ArrayList<>(arrayValue(currentAttributeValue(components, componentId)));
        if (index < 0 || index >= next.size()) {
            return;
        }
        next.set(index, parseAttributeModifierLine(normalizeMinecraftKey(type) + " " + amount + " " + operation + " " + slot));
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private String attributeModifiersText(Object value) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> modifier : attributeModifiers(value)) {
            String type = String.valueOf(modifier.getOrDefault("type", "minecraft:attack_damage"));
            String amount = formatComponentValue(modifier.getOrDefault("amount", 1.0));
            String operation = String.valueOf(modifier.getOrDefault("operation", "add_value"));
            String slot = String.valueOf(modifier.getOrDefault("slot", "any"));
            lines.add(type + " " + amount + " " + operation + " " + slot);
        }
        return String.join("\n", lines);
    }

    private List<Object> attributeModifiersValue(Object previous, String text) {
        List<Object> modifiers = new ArrayList<>();
        for (String line : splitLines(text)) {
            Map<String, Object> modifier = parseAttributeModifierLine(line);
            if (!modifier.isEmpty()) {
                modifiers.add(modifier);
            }
        }
        return modifiers;
    }

    private Map<String, Object> parseAttributeModifierLine(String line) {
        String[] parts = line != null ? line.trim().split("\\s+") : new String[0];
        if (parts.length == 0 || parts[0].isBlank()) {
            return Map.of();
        }
        String type = normalizeMinecraftKey(parts[0]);
        double amount = parts.length > 1 ? parseDouble(parts[1], 1.0) : 1.0;
        String operation = parts.length > 2 && !parts[2].isBlank() ? parts[2].toLowerCase(Locale.ROOT) : "add_value";
        String slot = parts.length > 3 && !parts[3].isBlank() ? parts[3].toLowerCase(Locale.ROOT) : "any";
        String id = parts.length > 4 && !parts[4].isBlank() ? normalizeMinecraftKey(parts[4]) : modifierId(type, operation, slot);
        Map<String, Object> modifier = new LinkedHashMap<>();
        modifier.put("type", type);
        modifier.put("amount", amount);
        modifier.put("operation", operation);
        if (!"any".equals(slot)) {
            modifier.put("slot", slot);
        }
        modifier.put("id", id);
        return modifier;
    }

    private List<Map<String, Object>> attributeModifiers(Object value) {
        Object raw = value;
        if (value instanceof Map<?, ?> map) {
            raw = map.get("modifiers");
        }
        List<Map<String, Object>> modifiers = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    modifiers.add(objectValue(map));
                }
            }
        }
        return modifiers;
    }

    private String modifierId(String type, String operation, String slot) {
        String cleaned = (type + "_" + operation + "_" + slot)
            .replace("minecraft:", "")
            .replace('.', '_')
            .replace('-', '_')
            .toLowerCase(Locale.ROOT);
        return "remotely:" + cleaned;
    }

    private void addTrimEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Material", attributeSearchTextEditor(components, componentId, componentId + ".material", value.getOrDefault("material", "minecraft:iron"), trimMaterialOptions(), false, "server:minecraft:trim_material")));
        insertAttributePanelWidget(container, attributeEditorRow("Pattern", attributeSearchTextEditor(components, componentId, componentId + ".pattern", value.getOrDefault("pattern", "minecraft:sentry"), trimPatternOptions(), false, "server:minecraft:trim_pattern")));
    }

    private String trimSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Trim";
        }
        Object material = map.get("material");
        Object pattern = map.get("pattern");
        if (material != null && pattern != null) {
            return "Material " + material + " | Pattern " + pattern;
        }
        if (material != null) {
            return "Material " + material;
        }
        return pattern != null ? "Pattern " + pattern : "Trim";
    }

    private void addFireworksEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Flight", attributeNumberEditor(components, componentId, componentId + ".flight_duration", value.getOrDefault("flight_duration", 1), "Ticks")));
        Object firstExplosion = firstFireworkExplosion(value.get("explosions"));
        addFireworkExplosionEditorRows(container, components, componentId, componentId + ".explosions.0", firstExplosion);
    }

    private void addFireworkExplosionEditorRows(Container container, Map<String, Object> components, String componentId, String path, Object value) {
        Map<String, Object> explosion = fireworkExplosionValue(value);
        insertAttributePanelWidget(container, attributeEditorRow("Shape", attributeDropdown(List.of("small_ball", "large_ball", "star", "creeper", "burst"), String.valueOf(explosion.getOrDefault("shape", "small_ball")), next -> updateFireworkExplosionField(components, componentId, path, "shape", next))));
        TextInputWidget colors = new TextInputWidget.Builder()
            .text(colorListText(explosion.get("colors")))
            .placeholder("#RRGGBB, #RRGGBB")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        colors.setOnChange(() -> updateFireworkExplosionField(components, componentId, path, "colors", parseColorList(colors.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Colors", attributeColorPicker(colors, true)));
        TextInputWidget fadeColors = new TextInputWidget.Builder()
            .text(colorListText(explosion.get("fade_colors")))
            .placeholder("#RRGGBB, #RRGGBB")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        fadeColors.setOnChange(() -> updateFireworkExplosionField(components, componentId, path, "fade_colors", parseColorList(fadeColors.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Fade Colors", attributeColorPicker(fadeColors, true)));
        ToggleWidget trail = new ToggleWidget.Builder()
            .label("Trail")
            .toggled(Boolean.TRUE.equals(explosion.get("has_trail")))
            .size(180, 18)
            .onChange(next -> updateFireworkExplosionField(components, componentId, path, "has_trail", next))
            .entranceAnimation(false)
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Trail", trail));
        ToggleWidget twinkle = new ToggleWidget.Builder()
            .label("Twinkle")
            .toggled(Boolean.TRUE.equals(explosion.get("has_twinkle")))
            .size(180, 18)
            .onChange(next -> updateFireworkExplosionField(components, componentId, path, "has_twinkle", next))
            .entranceAnimation(false)
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Twinkle", twinkle));
    }

    private void updateFireworkExplosionField(Map<String, Object> components, String componentId, String path, String field, Object value) {
        if (componentId.equals(path)) {
            updateNestedAttributeComponent(components, componentId, componentId + "." + field, value);
            return;
        }
        Map<String, Object> fireworks = objectValue(currentAttributeValue(components, componentId));
        List<Object> explosions = arrayValue(fireworks.get("explosions"));
        Map<String, Object> explosion = fireworkExplosionValue(explosions.isEmpty() ? defaultFireworkExplosion() : explosions.getFirst());
        explosion.put(field, value);
        if (explosions.isEmpty()) {
            explosions.add(explosion);
        } else {
            explosions.set(0, explosion);
        }
        fireworks.put("explosions", explosions);
        fireworks.putIfAbsent("flight_duration", 1);
        updateNestedAttributeComponent(components, componentId, componentId, fireworks);
    }

    private Map<String, Object> defaultFireworkExplosion() {
        return Map.of(
            "shape", "small_ball",
            "colors", List.of(0xFF0000),
            "fade_colors", List.of(0xFFFF00),
            "has_trail", false,
            "has_twinkle", false
        );
    }

    private Object firstFireworkExplosion(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.getFirst();
        }
        return defaultFireworkExplosion();
    }

    private Map<String, Object> fireworkExplosionValue(Object value) {
        Map<String, Object> explosion = objectValue(value);
        if (explosion.isEmpty()) {
            explosion.putAll(defaultFireworkExplosion());
        }
        return explosion;
    }

    private String fireworkSummary(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object shape = map.get("shape");
            if (shape != null) {
                return shape.toString();
            }
            Object duration = map.get("flight_duration");
            Object explosions = map.get("explosions");
            int count = explosions instanceof List<?> list ? list.size() : 0;
            return "Flight " + (duration != null ? duration : 1) + " | " + (count == 1 ? "1 Explosion" : count + " Explosions");
        }
        return "Firework";
    }

    private String colorListText(Object value) {
        List<String> colors = new ArrayList<>();
        for (Object item : arrayValue(value)) {
            if (item instanceof Number number) {
                colors.add(formatColorValue(number.intValue()));
            } else if (item != null && !item.toString().isBlank()) {
                colors.add(formatColorValue(parseColorValue(0xFFFFFF, item.toString())));
            }
        }
        return String.join(", ", colors);
    }

    private List<Object> parseColorList(String text) {
        List<Object> colors = new ArrayList<>();
        for (String value : splitCsv(text)) {
            colors.add(parseColorValue(0xFFFFFF, value));
        }
        return colors;
    }

    private void addBannerPatternsEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        addBannerPatternsEditorRows(container, components, componentId, value, true);
    }

    private String bannerPatternsText(Object value) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> pattern : bannerPatterns(value)) {
            String id = String.valueOf(pattern.getOrDefault("pattern", "minecraft:stripe_bottom"));
            String color = String.valueOf(pattern.getOrDefault("color", "white"));
            lines.add(id + " " + color);
        }
        return String.join("\n", lines);
    }

    private List<Object> bannerPatternsValue(String text) {
        List<Object> patterns = new ArrayList<>();
        for (String line : splitLines(text)) {
            Map<String, Object> pattern = parseBannerPatternLine(line);
            if (!pattern.isEmpty()) {
                patterns.add(pattern);
            }
        }
        return patterns;
    }

    private Map<String, Object> parseBannerPatternLine(String line) {
        String[] parts = line != null ? line.trim().split("\\s+") : new String[0];
        if (parts.length == 0 || parts[0].isBlank()) {
            return Map.of();
        }
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("pattern", normalizeMinecraftKey(parts[0]));
        pattern.put("color", parts.length > 1 && !parts[1].isBlank() ? parts[1].toLowerCase(Locale.ROOT) : "white");
        return pattern;
    }

    private List<Map<String, Object>> bannerPatterns(Object value) {
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (Object item : arrayValue(value)) {
            if (item instanceof Map<?, ?> map) {
                patterns.add(objectValue(map));
            }
        }
        return patterns;
    }

    private void addWritableBookContentEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        addBookPageRows(container, components, componentId, value, false);
    }

    private void addWrittenBookContentEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        addWrittenBookTitleEditorRow(container, components, componentId, value.getOrDefault("title", "Book Title"));
        insertAttributePanelWidget(container, attributeEditorRow("Author", attributeSearchTextEditor(components, componentId, componentId + ".author", value.getOrDefault("author", "Author"), List.of(), false, "", false)));
        DropDownWidget<String> generation = attributeDropdown(List.of("0", "1", "2", "3"), String.valueOf(value.getOrDefault("generation", 0)), next -> updateNestedAttributeComponent(components, componentId, componentId + ".generation", parseNonNegativeInt(next, 0)), this::bookGenerationLabel);
        insertAttributePanelWidget(container, attributeEditorRow("Generation", generation));
        insertAttributePanelWidget(container, attributeEditorRow("Resolved", attributeBooleanEditor(components, componentId, componentId + ".resolved", value.getOrDefault("resolved", false))));
        addBookPageRows(container, components, componentId, value, true);
    }

    private void addWrittenBookTitleEditorRow(Container container, Map<String, Object> components, String componentId, Object value) {
        TextInputWidget title = new TextInputWidget.Builder()
            .text(bookPageText(value))
            .placeholder("Title")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        title.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId + ".title", bookPageValue(value, title.getText(), false)));
        insertAttributePanelWidget(container, attributeEditorRow("Title", title));
    }

    private String bookGenerationLabel(String value) {
        return switch (value) {
            case "0" -> "Original";
            case "1" -> "Copy";
            case "2" -> "Copy Of Copy";
            case "3" -> "Tattered";
            default -> value;
        };
    }

    private void addBookPageRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value, boolean textComponents) {
        List<Object> pages = arrayValue(value.get("pages"));
        insertAttributePanelWidget(container, attributeStatusRow("Pages", pages.isEmpty() ? "No Pages" : pages.size() + " Pages", attributeRowWidth(), false, componentId + "PagesHeader"));
        for (int i = 0; i < pages.size(); i++) {
            TextInputWidget pageInput = attributeCompactInput(bookPageText(pages.get(i)), "Page Text");
            int index = i;
            pageInput.setOnChange(() -> updateBookPage(components, componentId, index, pageInput.getText(), textComponents));
            insertAttributePanelWidget(container, attributeEntryRow("Page " + (i + 1), pageInput.getText().isBlank() ? "Empty Page" : pageInput.getText(), pageInput, attributeDeleteButton(() -> removeBookPage(components, componentId, index))));
        }
        AnimatedButton add = new AnimatedButton.Builder()
            .label("Add Page")
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .accentType(ThemeManager.getAccent("nice"))
            .entranceAnimation(false)
            .onClick(() -> {
                Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
                List<Object> nextPages = arrayValue(next.get("pages"));
                nextPages.add(textComponents ? Map.of("text", "") : "");
                next.put("pages", nextPages);
                updateAttributeComponentRoot(components, componentId, next, true);
            })
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Add Page", add));
    }

    private String bookPageText(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null) {
                return text.toString();
            }
            Object raw = map.get("raw");
            if (raw != null) {
                return raw.toString();
            }
            Object filtered = map.get("filtered");
            return filtered != null ? filtered.toString() : "";
        }
        return value != null ? value.toString() : "";
    }

    private void updateBookPage(Map<String, Object> components, String componentId, int index, String text, boolean textComponents) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        List<Object> pages = arrayValue(next.get("pages"));
        if (index < 0 || index >= pages.size()) {
            return;
        }
        pages.set(index, bookPageValue(pages.get(index), text, textComponents));
        next.put("pages", pages);
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private Object bookPageValue(Object previous, String text, boolean textComponents) {
        String pageText = text != null ? text : "";
        if (previous instanceof Map<?, ?> map) {
            Map<String, Object> copy = objectValue(map);
            if (copy.containsKey("text")) {
                copy.put("text", pageText);
                return copy;
            }
            if (copy.containsKey("raw")) {
                copy.put("raw", pageText);
                return copy;
            }
            if (copy.containsKey("filtered")) {
                copy.put("filtered", pageText);
                return copy;
            }
            copy.put(textComponents ? "text" : "raw", pageText);
            return copy;
        }
        return textComponents ? Map.of("text", pageText) : pageText;
    }

    private void removeBookPage(Map<String, Object> components, String componentId, int index) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        List<Object> pages = arrayValue(next.get("pages"));
        if (index < 0 || index >= pages.size()) {
            return;
        }
        pages.remove(index);
        next.put("pages", pages);
        updateAttributeComponentRoot(components, componentId, next, true);
    }

    private void addBannerPatternsEditorRows(Container container, Map<String, Object> components, String componentId, Object value, boolean rowStyle) {
        List<Map<String, Object>> patterns = bannerPatterns(value);
        insertAttributePanelWidget(container, attributeStatusRow("Patterns", patterns.isEmpty() ? "No Patterns" : patterns.size() + " Patterns", attributeRowWidth(), false, componentId + "PatternsHeader"));
        for (int i = 0; i < patterns.size(); i++) {
            Map<String, Object> pattern = patterns.get(i);
            TextInputWidget patternInput = attributeCompactInput(String.valueOf(pattern.getOrDefault("pattern", "minecraft:stripe_bottom")), "Pattern");
            int index = i;
            DropDownWidget<String> color = attributeDropdown(dyeColorOptions(), String.valueOf(pattern.getOrDefault("color", "white")), next -> updateBannerPatternEntry(components, componentId, index, patternInput.getText(), next));
            patternInput.setOnChange(() -> updateBannerPatternEntry(components, componentId, index, patternInput.getText(), color.getSelectedItem()));
            insertAttributePanelWidget(container, attributeEntryRow("Pattern " + (i + 1), patternInput.getText() + " | " + color.getSelectedItem(), patternInput, attributeSearchButton(patternInput, bannerPatternOptions(), "server:minecraft:banner_pattern", selected -> {
                patternInput.setText(normalizeMinecraftKey(selected));
                patternInput.runOnChange();
            }), color, attributeDeleteButton(() -> removeListAttributeEntry(components, componentId, index))));
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Pattern", attributeAddSearchRow("Pattern", bannerPatternOptions(), "server:minecraft:banner_pattern", valueId -> {
            List<Object> next = arrayValue(currentAttributeValue(components, componentId));
            next.add(Map.of("pattern", normalizeMinecraftKey(valueId), "color", "white"));
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private void updateBannerPatternEntry(Map<String, Object> components, String componentId, int index, String pattern, String color) {
        List<Object> next = arrayValue(currentAttributeValue(components, componentId));
        if (index < 0 || index >= next.size()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("pattern", normalizeMinecraftKey(pattern));
        entry.put("color", color != null && !color.isBlank() ? color.replace("minecraft:", "").toLowerCase(Locale.ROOT) : "white");
        next.set(index, entry);
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private void addPotDecorationsEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        List<Object> decorations = arrayValue(value);
        while (decorations.size() < 4) {
            decorations.add("minecraft:brick");
        }
        for (int i = 0; i < decorations.size(); i++) {
            TextInputWidget item = attributeCompactInput(formatComponentValue(decorations.get(i)), "Item");
            int index = i;
            item.setOnChange(() -> updatePotDecorationEntry(components, componentId, index, item.getText()));
            insertAttributePanelWidget(container, attributeEntryRow("Decoration " + (i + 1), item.getText(), item, attributeSearchButton(item, materialOptions(), "server:minecraft:material", valueId -> {
                item.setText(normalizeMinecraftKey(valueId));
                item.runOnChange();
            })));
        }
    }

    private void updatePotDecorationEntry(Map<String, Object> components, String componentId, int index, String item) {
        List<Object> next = arrayValue(currentAttributeValue(components, componentId));
        while (next.size() <= index) {
            next.add("minecraft:brick");
        }
        next.set(index, normalizeMinecraftKey(item));
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private void updateRootListEntry(Map<String, Object> components, String componentId, int index, Object value, boolean rebuild) {
        List<Object> next = arrayValue(currentAttributeValue(components, componentId));
        if (index < 0 || index >= next.size()) {
            return;
        }
        next.set(index, value);
        updateAttributeComponentRoot(components, componentId, next, rebuild);
    }

    private void addItemStackListEditorRows(Container container, Map<String, Object> components, String componentId, Object value, String title, boolean slotted) {
        List<Object> items = arrayValue(value);
        insertAttributePanelWidget(container, attributeStatusRow(title, items.isEmpty() ? "No Items" : items.size() + " Configured", attributeRowWidth(), false, componentId + "ItemsHeader"));
        for (int i = 0; i < items.size(); i++) {
            addItemStackEntryRow(container, components, componentId, items.get(i), i, slotted);
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Item", attributeAddSearchRow("Item", materialOptions(), "server:minecraft:material", valueId -> {
            List<Object> next = new ArrayList<>(arrayValue(currentAttributeValue(components, componentId)));
            next.add(slotted ? parseSlottedItemStackLine(next.size() + " " + itemStackPickerLine(valueId, false), next.size()) : parseItemStackLine(itemStackPickerLine(valueId, false)));
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private void addItemStackEntryRow(Container container, Map<String, Object> components, String componentId, Object value, int index, boolean slotted) {
        Map<String, Object> entry = objectValue(value);
        int slot = index;
        Map<String, Object> stack = entry;
        if (slotted && entry.containsKey("item")) {
            slot = parseNonNegativeInt(entry.get("slot"), index);
            stack = objectValue(entry.get("item"));
        }
        TextInputWidget itemInput = attributeCompactInput(String.valueOf(stack.getOrDefault("id", "minecraft:stone")), "Item");
        TextInputWidget countInput = attributeCompactInput(String.valueOf(parsePositiveInt(stack.get("count"), 1)), "Count");
        TextInputWidget slotInput = attributeCompactInput(String.valueOf(slot), "Slot");
        itemInput.setOnChange(() -> updateItemStackEntry(components, componentId, index, slotted, slotInput.getText(), itemInput.getText(), countInput.getText()));
        countInput.setOnChange(() -> updateItemStackEntry(components, componentId, index, slotted, slotInput.getText(), itemInput.getText(), countInput.getText()));
        slotInput.setOnChange(() -> updateItemStackEntry(components, componentId, index, slotted, slotInput.getText(), itemInput.getText(), countInput.getText()));
        if (slotted) {
            insertAttributePanelWidget(container, attributeEntryRow("Item " + (index + 1), "Slot " + slot, slotInput, itemInput, attributeSearchButton(itemInput, materialOptions(), "server:minecraft:material", valueId -> {
                itemInput.setText(normalizeMinecraftKey(valueId));
                itemInput.runOnChange();
            }), countInput, attributeDeleteButton(() -> removeListAttributeEntry(components, componentId, index))));
            return;
        }
        insertAttributePanelWidget(container, attributeEntryRow("Item " + (index + 1), itemInput.getText(), itemInput, attributeSearchButton(itemInput, materialOptions(), "server:minecraft:material", valueId -> {
            itemInput.setText(normalizeMinecraftKey(valueId));
            itemInput.runOnChange();
        }), countInput, attributeDeleteButton(() -> removeListAttributeEntry(components, componentId, index))));
    }

    private void updateItemStackEntry(Map<String, Object> components, String componentId, int index, boolean slotted, String slot, String id, String count) {
        List<Object> next = new ArrayList<>(arrayValue(currentAttributeValue(components, componentId)));
        if (index < 0 || index >= next.size()) {
            return;
        }
        String line = (slotted ? parseNonNegativeInt(slot, index) + " " : "") + normalizeMinecraftKey(id) + " " + parsePositiveInt(count, 1);
        next.set(index, slotted ? parseSlottedItemStackLine(line, index) : parseItemStackLine(line));
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private String itemStackListText(Object value, boolean slotted) {
        List<String> lines = new ArrayList<>();
        for (Object item : arrayValue(value)) {
            Map<String, Object> entry = objectValue(item);
            if (slotted && entry.containsKey("item")) {
                Map<String, Object> stack = objectValue(entry.get("item"));
                lines.add(parseNonNegativeInt(entry.get("slot"), 0) + " " + itemStackLine(stack));
            } else {
                lines.add(itemStackLine(entry));
            }
        }
        return String.join("\n", lines);
    }

    private String itemStackLine(Map<String, Object> stack) {
        String id = String.valueOf(stack.getOrDefault("id", "minecraft:stone"));
        int count = parsePositiveInt(stack.get("count"), 1);
        return id + " " + count;
    }

    private String itemStackPickerLine(String value, boolean slotted) {
        String id = value != null ? value.trim().toLowerCase(Locale.ROOT) : "minecraft:stone";
        if (id.isBlank()) {
            id = "minecraft:stone";
        }
        return (slotted ? id : normalizeMinecraftKey(id)) + " 1";
    }

    private List<Object> itemStackListValue(String text, boolean slotted) {
        List<Object> items = new ArrayList<>();
        int fallbackSlot = 0;
        for (String line : splitLines(text)) {
            Object item = slotted ? parseSlottedItemStackLine(line, fallbackSlot) : parseItemStackLine(line);
            if (item != null) {
                items.add(item);
                fallbackSlot++;
            }
        }
        return items;
    }

    private Map<String, Object> parseItemStackLine(String line) {
        String[] parts = line != null ? line.trim().split("\\s+") : new String[0];
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        String id = normalizeMinecraftKey(parts[0]);
        int count = parts.length > 1 ? parsePositiveInt(parts[1], 1) : 1;
        return simpleItemStack(id, count);
    }

    private Map<String, Object> parseSlottedItemStackLine(String line, int fallbackSlot) {
        String[] parts = line != null ? line.trim().split("\\s+") : new String[0];
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        boolean explicitSlot = isInteger(parts[0]);
        int slot = explicitSlot ? parseNonNegativeInt(parts[0], fallbackSlot) : fallbackSlot;
        int idIndex = explicitSlot ? 1 : 0;
        String id = parts.length > idIndex ? parts[idIndex] : "minecraft:stone";
        int countIndex = idIndex + 1;
        int count = parts.length > countIndex ? parsePositiveInt(parts[countIndex], 1) : 1;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("slot", slot);
        entry.put("item", simpleItemStack(normalizeMinecraftKey(id), count));
        return entry;
    }

    private Map<String, Object> simpleItemStack(String id, int count) {
        Map<String, Object> stack = new LinkedHashMap<>();
        stack.put("id", normalizeMinecraftKey(id));
        stack.put("count", Math.clamp(count, 1, 99));
        return stack;
    }

    private String normalizeMinecraftKey(String value) {
        String key = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        return key.contains(":") ? key : "minecraft:" + key;
    }

    private int parsePositiveInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.toString().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private int parseNonNegativeInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(value.toString().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean isInteger(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private double parseDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private void addPotionContentsEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Potion", attributeSearchTextEditor(components, componentId, componentId + ".potion", value.getOrDefault("potion", "minecraft:water"), potionOptions(), false, "server:minecraft:potion")));
        TextInputWidget color = new TextInputWidget.Builder()
            .text(formatColorValue(potionCustomColor(value)))
            .placeholder("#RRGGBB")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        color.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId + ".custom_color", parseColorValue(potionCustomColor(value), color.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Color", attributeColorPicker(color, false)));
    }

    private void addJukeboxPlayableEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        Map<String, Object> object = objectValue(value);
        Object song = object.containsKey("song") ? object.get("song") : value;
        TextInputWidget songInput = new TextInputWidget.Builder()
            .text(formatComponentValue(song != null ? song : "minecraft:13"))
            .placeholder("Song")
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        songInput.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId, jukeboxPlayableValue(currentAttributeValue(components, componentId), songInput.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Song", searchableInputRow(songInput, jukeboxSongOptions(), false, "server:minecraft:jukebox_song")));
    }

    private String jukeboxPlayableValue(Object previous, String song) {
        return normalizeMinecraftKey(song != null && !song.isBlank() ? song : "13");
    }

    private String potionContentsSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Potion";
        }
        Object potion = map.get("potion");
        if (potion != null && !potion.toString().isBlank()) {
            return potion.toString();
        }
        if (map.containsKey("custom_color")) {
            return "Color " + formatColorValue(potionCustomColor(map));
        }
        return "Potion";
    }

    private String jukeboxPlayableSummary(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object song = map.get("song");
            return song != null && !song.toString().isBlank() ? "Song " + song : "Song";
        }
        return value != null && !value.toString().isBlank() ? "Song " + value : "Song";
    }

    private int potionCustomColor(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object color = map.get("custom_color");
            if (color instanceof Number number) {
                return Math.clamp(number.intValue(), 0, 0xFFFFFF);
            }
            if (color != null) {
                return parseColorValue(0xFFFFFF, color.toString());
            }
        }
        return 0xFFFFFF;
    }

    private List<String> potionOptions() {
        return catalogOptions("server:minecraft:potion");
    }

    private List<String> enchantmentOptions() {
        return catalogOptions("server:minecraft:enchantment");
    }

    private List<String> attributeTypeOptions() {
        return catalogOptions("server:minecraft:attribute");
    }

    private List<String> bannerPatternOptions() {
        return catalogOptions("server:minecraft:banner_pattern");
    }

    private List<String> dyeColorOptions() {
        List<String> colors = catalogOptions("server:minecraft:dye_color");
        if (colors.equals(List.of("Loading"))) {
            return colors;
        }
        return normalizedOptions(colors, "white");
    }

    private List<String> blockOptions() {
        return catalogOptions("server:minecraft:block");
    }

    private List<String> instrumentOptions() {
        return catalogOptions("server:minecraft:instrument");
    }

    private List<String> equipmentSlotOptions() {
        return List.of("head", "chest", "legs", "feet", "body", "mainhand", "offhand", "saddle");
    }

    private List<String> equipmentAssetOptions() {
        return List.of("minecraft:leather", "minecraft:chainmail", "minecraft:iron", "minecraft:gold", "minecraft:diamond", "minecraft:turtle_scute", "minecraft:netherite", "minecraft:saddle");
    }

    private List<String> soundOptions() {
        return catalogOptions("server:minecraft:sound");
    }

    private List<String> damageTypeOptions() {
        List<String> damageTypes = catalogOptions("server:minecraft:damage_type");
        if (damageTypes.equals(List.of("Loading"))) {
            return damageTypes;
        }
        List<String> options = new ArrayList<>(damageTypes);
        options.addFirst("#minecraft:is_fire");
        return normalizedOptions(options, "#minecraft:is_fire");
    }

    private List<String> damageResistanceOptions() {
        return List.of(
            "#minecraft:is_fire",
            "#minecraft:is_explosion",
            "#minecraft:is_projectile",
            "#minecraft:is_fall",
            "#minecraft:is_drowning",
            "#minecraft:is_freezing",
            "#minecraft:is_lightning",
            "#minecraft:bypasses_armor",
            "#minecraft:bypasses_shield",
            "#minecraft:bypasses_invulnerability"
        );
    }

    private List<String> tooltipStyleOptions() {
        return List.of("minecraft:default");
    }

    private List<String> cooldownGroupOptions() {
        return List.of(
            "minecraft:generic",
            "minecraft:ender_pearl",
            "minecraft:chorus_fruit",
            "minecraft:shield",
            "minecraft:goat_horn",
            "minecraft:wind_charge",
            "minecraft:ominous_bottle"
        );
    }

    private List<String> lootTableOptions() {
        return catalogOptions("server:minecraft:loot_table");
    }

    private List<String> entityTypeOptions() {
        return catalogOptions("server:minecraft:entity_type");
    }

    private List<String> blockEntityTypeOptions() {
        return catalogOptions("server:minecraft:block_entity_type");
    }

    private List<String> mobEffectOptions() {
        return catalogOptions("server:minecraft:mob_effect");
    }

    private List<String> dimensionOptions() {
        return catalogOptions("server:minecraft:dimension_type");
    }

    private List<String> recipeOptions() {
        return catalogOptions("server:minecraft:recipe");
    }

    private List<String> deathEffectTypeOptions() {
        return List.of("minecraft:play_sound");
    }

    private List<String> commonBlockStateNames() {
        return List.of("facing", "axis", "waterlogged", "open", "powered", "lit", "half", "shape", "type", "age", "level", "rotation");
    }

    private List<String> blockStateValueOptions(String key) {
        String normalized = key != null ? key.toLowerCase(Locale.ROOT) : "";
        if ("facing".equals(normalized)) {
            return List.of("north", "south", "east", "west", "up", "down");
        }
        if ("axis".equals(normalized)) {
            return List.of("x", "y", "z");
        }
        if ("half".equals(normalized)) {
            return List.of("top", "bottom", "upper", "lower");
        }
        if ("type".equals(normalized)) {
            return List.of("single", "left", "right", "top", "bottom", "double");
        }
        if ("shape".equals(normalized)) {
            return List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right", "north_south", "east_west");
        }
        if ("waterlogged".equals(normalized) || "open".equals(normalized) || "powered".equals(normalized) || "lit".equals(normalized)) {
            return List.of("true", "false");
        }
        return List.of("true", "false");
    }

    private List<String> jukeboxSongOptions() {
        return catalogOptions("server:minecraft:jukebox_song");
    }

    private List<String> trimMaterialOptions() {
        return catalogOptions("server:minecraft:trim_material");
    }

    private List<String> trimPatternOptions() {
        return catalogOptions("server:minecraft:trim_pattern");
    }

    private Object customModelDataValue(Object value) {
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Object floats = map.get("floats");
            if (floats instanceof List<?> list && !list.isEmpty()) {
                return list.getFirst();
            }
        }
        return 1.0;
    }

    private void addTextComponentEditorRow(Container container, Map<String, Object> components, String componentId, Object value) {
        TextInputWidget input = new TextInputWidget.Builder()
            .text(plainTextComponentValue(value))
            .placeholder("Text")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        input.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId, Map.of("text", input.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Text", input));
    }

    private String plainTextComponentValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text != null ? text.toString() : "";
        }
        return value != null ? value.toString() : "";
    }

    private void addLoreEditorRow(Container container, Map<String, Object> components, String componentId, Object value) {
        CodeEditorWidget editor = new CodeEditorWidget(0, 0, Math.max(180, attributeRowWidth() - 12), 78);
        ReSyncStudioPanelState.disableEntrance(editor);
        editor.setShowLineNumbers(false);
        editor.setShowSearchNavigation(false);
        editor.setDimNonMatchingLines(false);
        editor.setShowCursorLineHighlight(false);
        editor.setShowSearchMatchHighlight(false);
        editor.setWordWrap(true);
        editor.setText(loreText(value));
        editor.onChange = text -> updateNestedAttributeComponent(components, componentId, componentId, loreValue(text));
        insertAttributePanelWidget(container, attributeLargeEditorRow("Lore", editor, 94));
    }

    private String loreText(Object value) {
        List<String> lines = new ArrayList<>();
        for (Object item : arrayValue(value)) {
            lines.add(plainTextComponentValue(item));
        }
        return String.join("\n", lines);
    }

    private List<Object> loreValue(String text) {
        List<Object> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        for (String line : text.split("\\R", -1)) {
            if (!line.isBlank()) {
                lines.add(Map.of("text", line));
            }
        }
        return lines;
    }

    private void addTooltipEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        TextInputWidget hidden = new TextInputWidget.Builder()
            .text(String.join(", ", tooltipHiddenComponents(value)))
            .placeholder("Hidden Components")
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        hidden.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId, Map.of("hidden_components", splitCsv(hidden.getText()))));
        insertAttributePanelWidget(container, attributeEditorRow("Hide In Tooltip", searchableInputRow(hidden, hiddenTooltipComponentOptions(), true, "")));
    }

    private void addBlockPredicateEditorRows(Container container, Map<String, Object> components, String componentId, Object value, String title) {
        List<Map<String, Object>> predicates = blockPredicates(value);
        insertAttributePanelWidget(container, attributeStatusRow(title, predicates.isEmpty() ? "No Blocks" : predicates.size() + " Rules", attributeRowWidth(), false, componentId + "BlocksHeader"));
        for (int i = 0; i < predicates.size(); i++) {
            addBlockPredicateEntryRow(container, components, componentId, predicates.get(i), i);
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Block", attributeAddSearchRow("Block Or Tag", blockOptions(), "server:minecraft:block", valueId -> {
            List<Object> next = new ArrayList<>(arrayValue(currentAttributeValue(components, componentId)));
            next.add(Map.of("blocks", normalizeBlockPredicate(valueId)));
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private void addBlockPredicateEntryRow(Container container, Map<String, Object> components, String componentId, Map<String, Object> predicate, int index) {
        String block = firstBlockPredicateValue(predicate);
        TextInputWidget input = attributeCompactInput(block, "Block Or Tag");
        input.setOnChange(() -> updateBlockPredicateEntry(components, componentId, index, input.getText()));
        insertAttributePanelWidget(container, attributeEntryRow("Block Rule " + (index + 1), block, input, attributeSearchButton(input, blockOptions(), "server:minecraft:block", value -> {
            input.setText(normalizeBlockPredicate(value));
            input.runOnChange();
        }), attributeDeleteButton(() -> removeListAttributeEntry(components, componentId, index))));
    }

    private void updateBlockPredicateEntry(Map<String, Object> components, String componentId, int index, String block) {
        List<Object> next = new ArrayList<>(arrayValue(currentAttributeValue(components, componentId)));
        if (index < 0 || index >= next.size()) {
            return;
        }
        next.set(index, Map.of("blocks", normalizeBlockPredicate(block)));
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private String firstBlockPredicateValue(Map<String, Object> predicate) {
        Object blocks = predicate.get("blocks");
        if (blocks instanceof List<?> list && !list.isEmpty() && list.getFirst() != null) {
            return list.getFirst().toString();
        }
        return blocks != null && !blocks.toString().isBlank() ? blocks.toString() : "minecraft:stone";
    }

    private String blockPredicateText(Object value) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> predicate : blockPredicates(value)) {
            Object blocks = predicate.get("blocks");
            if (blocks instanceof List<?> list) {
                for (Object block : list) {
                    if (block != null && !block.toString().isBlank()) {
                        lines.add(block.toString());
                    }
                }
            } else if (blocks != null && !blocks.toString().isBlank()) {
                lines.add(blocks.toString());
            }
        }
        return String.join("\n", lines);
    }

    private List<Object> blockPredicateValue(Object previous, String text) {
        List<Object> predicates = new ArrayList<>();
        for (String block : splitLines(text)) {
            predicates.add(Map.of("blocks", normalizeBlockPredicate(block)));
        }
        return predicates;
    }

    private List<Map<String, Object>> blockPredicates(Object value) {
        Object raw = value;
        if (value instanceof Map<?, ?> map) {
            raw = map.get("predicates");
        }
        List<Map<String, Object>> predicates = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    predicates.add(objectValue(map));
                }
            }
        }
        return predicates;
    }

    private String normalizeBlockPredicate(String value) {
        String trimmed = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        if (trimmed.startsWith("#")) {
            String tag = trimmed.substring(1);
            return "#" + (tag.contains(":") ? tag : "minecraft:" + tag);
        }
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private List<String> tooltipHiddenComponents(Map<String, Object> value) {
        Object hidden = value.get("hidden_components");
        if (!(hidden instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private void addConsumableEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Use Time", attributeNumberEditor(components, componentId, componentId + ".consume_seconds", value.getOrDefault("consume_seconds", 1.6), "Seconds")));
        insertAttributePanelWidget(container, attributeEditorRow("Animation", attributeChoiceEditor(components, componentId, componentId + ".animation", List.of("eat", "drink", "block", "bow", "crossbow", "spear", "spyglass", "toot_horn", "brush"), String.valueOf(value.getOrDefault("animation", "eat")))));
        insertAttributePanelWidget(container, attributeEditorRow("Sound", attributeSearchTextEditor(components, componentId, componentId + ".sound", value.getOrDefault("sound", "minecraft:entity.generic.eat"), soundOptions(), false, "server:minecraft:sound")));
        ToggleWidget particles = new ToggleWidget.Builder()
            .label("Show Particles")
            .toggled(!Boolean.FALSE.equals(value.get("has_consume_particles")))
            .size(180, 18)
            .onChange(next -> updateNestedAttributeComponent(components, componentId, componentId + ".has_consume_particles", next))
            .entranceAnimation(false)
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Particles", particles));
    }

    private void addFoodEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Food Restored", attributeNumberEditor(components, componentId, componentId + ".nutrition", value.getOrDefault("nutrition", 1), "Points")));
        Object saturation = value.containsKey("saturation") ? value.get("saturation") : value.getOrDefault("saturation_modifier", 0.1);
        String saturationPath = value.containsKey("saturation_modifier") && !value.containsKey("saturation") ? componentId + ".saturation_modifier" : componentId + ".saturation";
        insertAttributePanelWidget(container, attributeEditorRow("Saturation", attributeNumberEditor(components, componentId, saturationPath, saturation, "Amount")));
        ToggleWidget alwaysEat = new ToggleWidget.Builder()
            .label("Always Eat")
            .toggled(Boolean.TRUE.equals(value.get("can_always_eat")))
            .size(180, 18)
            .onChange(next -> updateNestedAttributeComponent(components, componentId, componentId + ".can_always_eat", next))
            .entranceAnimation(false)
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Always Eat", alwaysEat));
    }

    private void addUseCooldownEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Cooldown", attributeNumberEditor(components, componentId, componentId + ".seconds", value.getOrDefault("seconds", 1.0), "Seconds")));
        TextInputWidget group = new TextInputWidget.Builder()
            .text(formatComponentValue(value.getOrDefault("cooldown_group", "minecraft:generic")))
            .placeholder("Group")
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        group.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId + ".cooldown_group", normalizeMinecraftKey(group.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Cooldown Group", searchableInputRow(group, cooldownGroupOptions(), false, "")));
    }

    private void addUseRemainderEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        TextInputWidget item = new TextInputWidget.Builder()
            .text(itemStackLine(value.isEmpty() ? simpleItemStack("minecraft:bowl", 1) : value))
            .placeholder("Item Count")
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        item.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId, parseItemStackLine(item.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Remainder", searchableInputRow(item, materialOptions(), false, "server:minecraft:material")));
    }

    private void addDamageResistantEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        TextInputWidget types = new TextInputWidget.Builder()
            .text(formatComponentValue(value.getOrDefault("types", "#minecraft:is_fire")))
            .placeholder("Damage Type Or Tag")
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        types.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId + ".types", normalizeDamageTypeSet(types.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Damage Types", searchableInputRow(types, damageResistanceOptions(), false, "")));
    }

    private void addWeaponEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Attack Damage Cost", attributeNumberEditor(components, componentId, componentId + ".item_damage_per_attack", value.getOrDefault("item_damage_per_attack", 1), "Durability")));
        insertAttributePanelWidget(container, attributeEditorRow("Block Disable Time", attributeNumberEditor(components, componentId, componentId + ".disable_blocking_for_seconds", value.getOrDefault("disable_blocking_for_seconds", 0.0), "Seconds")));
    }

    private void addBlocksAttacksEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Block Delay", attributeNumberEditor(components, componentId, componentId + ".block_delay_seconds", value.getOrDefault("block_delay_seconds", 0.25), "Seconds")));
        insertAttributePanelWidget(container, attributeEditorRow("Cooldown Scale", attributeNumberEditor(components, componentId, componentId + ".disable_cooldown_scale", value.getOrDefault("disable_cooldown_scale", 1.0), "Scale")));
        insertAttributePanelWidget(container, attributeEditorRow("Block Sound", attributeSearchTextEditor(components, componentId, componentId + ".block_sound", value.getOrDefault("block_sound", "minecraft:item.shield.block"), soundOptions(), false, "server:minecraft:sound")));
        insertAttributePanelWidget(container, attributeEditorRow("Disabled Sound", attributeOptionalSearchTextEditor(components, componentId, componentId + ".disabled_sound", value.getOrDefault("disabled_sound", value.getOrDefault("disable_sound", "")), soundOptions(), "server:minecraft:sound")));
        TextInputWidget bypassedBy = new TextInputWidget.Builder()
            .text(damageTypeSetText(value.getOrDefault("bypassed_by", "#minecraft:bypasses_shield")))
            .placeholder("Damage Type Or Tag")
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        bypassedBy.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId + ".bypassed_by", optionalDamageTypeSetValue(value.get("bypassed_by"), bypassedBy.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Bypassed By", searchableInputRow(bypassedBy, damageTypeOptions(), false, "server:minecraft:damage_type")));
        addBlocksAttacksDamageRows(container, components, componentId, objectValue(value.get("item_damage")));
        addBlocksAttacksReductionRows(container, components, componentId, value.get("damage_reductions"));
    }

    private void addBlocksAttacksDamageRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> itemDamage) {
        insertAttributePanelWidget(container, attributeStatusRow("Item Damage", "Durability Loss Formula", attributeRowWidth(), false, componentId + "ItemDamageHeader"));
        insertAttributePanelWidget(container, attributeEditorRow("Damage Threshold", attributeNumberEditor(components, componentId, componentId + ".item_damage.threshold", itemDamage.getOrDefault("threshold", 1.0), "Minimum")));
        insertAttributePanelWidget(container, attributeEditorRow("Damage Base", attributeNumberEditor(components, componentId, componentId + ".item_damage.base", itemDamage.getOrDefault("base", 1.0), "Base")));
        insertAttributePanelWidget(container, attributeEditorRow("Damage Factor", attributeNumberEditor(components, componentId, componentId + ".item_damage.factor", itemDamage.getOrDefault("factor", 1.0), "Factor")));
    }

    private void addBlocksAttacksReductionRows(Container container, Map<String, Object> components, String componentId, Object value) {
        List<Map<String, Object>> reductions = damageReductionRules(value);
        insertAttributePanelWidget(container, attributeStatusRow("Damage Reductions", reductions.isEmpty() ? "No Rules" : reductions.size() + " Rules", attributeRowWidth(), false, componentId + "ReductionHeader"));
        for (int i = 0; i < reductions.size(); i++) {
            addDamageReductionEntryRow(container, components, componentId, reductions.get(i), i);
        }
        AnimatedButton add = new AnimatedButton.Builder()
            .label("Add Reduction")
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .accentType(ThemeManager.getAccent("nice"))
            .entranceAnimation(false)
            .onClick(() -> {
                List<Object> next = new ArrayList<>(arrayValue(objectValue(currentAttributeValue(components, componentId)).get("damage_reductions")));
                next.add(defaultDamageReductionRule());
                updateNestedAttributeComponent(components, componentId, componentId + ".damage_reductions", next);
                markAttributeEditorDirty(componentId);
                syncAttributeRowsInPlace();
            })
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Add Reduction", add));
    }

    private void addDamageReductionEntryRow(Container container, Map<String, Object> components, String componentId, Map<String, Object> reduction, int index) {
        TextInputWidget typeInput = attributeCompactInput(damageTypeSetText(reduction.get("type")), "Damage Type");
        TextInputWidget angleInput = attributeCompactInput(formatComponentValue(reduction.getOrDefault("horizontal_blocking_angle", 90.0)), "Angle");
        TextInputWidget baseInput = attributeCompactInput(formatComponentValue(reduction.getOrDefault("base", 0.0)), "Base");
        TextInputWidget factorInput = attributeCompactInput(formatComponentValue(reduction.getOrDefault("factor", 1.0)), "Factor");
        typeInput.setOnChange(() -> updateDamageReductionEntry(components, componentId, index, typeInput.getText(), angleInput.getText(), baseInput.getText(), factorInput.getText()));
        angleInput.setOnChange(() -> updateDamageReductionEntry(components, componentId, index, typeInput.getText(), angleInput.getText(), baseInput.getText(), factorInput.getText()));
        baseInput.setOnChange(() -> updateDamageReductionEntry(components, componentId, index, typeInput.getText(), angleInput.getText(), baseInput.getText(), factorInput.getText()));
        factorInput.setOnChange(() -> updateDamageReductionEntry(components, componentId, index, typeInput.getText(), angleInput.getText(), baseInput.getText(), factorInput.getText()));
        insertAttributePanelWidget(container, attributeEntryRow("Reduction Type " + (index + 1), typeInput.getText().isBlank() ? "All Damage" : typeInput.getText(), typeInput, attributeSearchButton(typeInput, damageTypeOptions(), "server:minecraft:damage_type", value -> {
            typeInput.setText(normalizeDamageTypeSet(value));
            typeInput.runOnChange();
        })));
        insertAttributePanelWidget(container, attributeEntryRow("Reduction " + (index + 1), "Angle " + angleInput.getText() + " | Base " + baseInput.getText() + " | Factor " + factorInput.getText(), angleInput, baseInput, factorInput, attributeDeleteButton(() -> removeDamageReductionEntry(components, componentId, index))));
    }

    private void updateDamageReductionEntry(Map<String, Object> components, String componentId, int index, String type, String angle, String base, String factor) {
        Map<String, Object> component = objectValue(currentAttributeValue(components, componentId));
        List<Object> next = new ArrayList<>(arrayValue(component.get("damage_reductions")));
        if (index < 0 || index >= next.size()) {
            return;
        }
        Map<String, Object> rule = objectValue(next.get(index));
        Object normalizedType = optionalDamageTypeSetValue(rule.get("type"), type);
        if (normalizedType == null) {
            rule.remove("type");
        } else {
            rule.put("type", normalizedType);
        }
        rule.put("horizontal_blocking_angle", parseDouble(angle, 90.0));
        rule.put("base", parseDouble(base, 0.0));
        rule.put("factor", parseDouble(factor, 1.0));
        next.set(index, rule);
        updateNestedAttributeComponent(components, componentId, componentId + ".damage_reductions", next);
    }

    private void removeDamageReductionEntry(Map<String, Object> components, String componentId, int index) {
        Map<String, Object> component = objectValue(currentAttributeValue(components, componentId));
        List<Object> next = new ArrayList<>(arrayValue(component.get("damage_reductions")));
        if (index < 0 || index >= next.size()) {
            return;
        }
        next.remove(index);
        updateNestedAttributeComponent(components, componentId, componentId + ".damage_reductions", next);
        markAttributeEditorDirty(componentId);
        syncAttributeRowsInPlace();
    }

    private Map<String, Object> damageReductionRule(String angle, String base, String factor) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("horizontal_blocking_angle", parseDouble(angle, 90.0));
        rule.put("base", parseDouble(base, 0.0));
        rule.put("factor", parseDouble(factor, 1.0));
        return rule;
    }

    private Map<String, Object> defaultDamageReductionRule() {
        return damageReductionRule("90.0", "0.0", "1.0");
    }

    private String defaultToolRuleBlocks(String material) {
        String value = material != null ? material.toUpperCase(Locale.ROOT) : "";
        if (isStandaloneAxeMaterial(value)) {
            return "#minecraft:mineable/axe";
        }
        if (value.contains("SHOVEL")) {
            return "#minecraft:mineable/shovel";
        }
        if (value.contains("HOE")) {
            return "#minecraft:mineable/hoe";
        }
        return "#minecraft:mineable/pickaxe";
    }

    private List<Map<String, Object>> damageReductionRules(Object value) {
        List<Map<String, Object>> reductions = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    reductions.add(objectValue(map));
                }
            }
        }
        return reductions;
    }

    private void addPiercingWeaponEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Knockback", attributeBooleanEditor(components, componentId, componentId + ".deals_knockback", value.getOrDefault("deals_knockback", true))));
        insertAttributePanelWidget(container, attributeEditorRow("Dismounts", attributeBooleanEditor(components, componentId, componentId + ".dismounts", value.getOrDefault("dismounts", false))));
        insertAttributePanelWidget(container, attributeEditorRow("Throw Sound", attributeSearchTextEditor(components, componentId, componentId + ".sound", value.getOrDefault("sound", "minecraft:item.trident.throw"), soundOptions(), false, "server:minecraft:sound")));
        insertAttributePanelWidget(container, attributeEditorRow("Hit Sound", attributeSearchTextEditor(components, componentId, componentId + ".hit_sound", value.getOrDefault("hit_sound", "minecraft:item.trident.hit"), soundOptions(), false, "server:minecraft:sound")));
    }

    private void addKineticWeaponEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        Map<String, Object> dismount = objectValue(value.get("dismount_conditions"));
        insertAttributePanelWidget(container, attributeEditorRow("Contact Cooldown", attributeNumberEditor(components, componentId, componentId + ".contact_cooldown_ticks", value.getOrDefault("contact_cooldown_ticks", 10), "Ticks")));
        insertAttributePanelWidget(container, attributeEditorRow("Delay", attributeNumberEditor(components, componentId, componentId + ".delay_ticks", value.getOrDefault("delay_ticks", 0), "Ticks")));
        insertAttributePanelWidget(container, attributeEditorRow("Forward Movement", attributeNumberEditor(components, componentId, componentId + ".forward_movement", value.getOrDefault("forward_movement", 0.0), "Scale")));
        insertAttributePanelWidget(container, attributeEditorRow("Damage Multiplier", attributeNumberEditor(components, componentId, componentId + ".damage_multiplier", value.getOrDefault("damage_multiplier", 1.0), "Scale")));
        insertAttributePanelWidget(container, attributeEditorRow("Charge Sound", attributeSearchTextEditor(components, componentId, componentId + ".sound", value.getOrDefault("sound", "minecraft:item.trident.throw"), soundOptions(), false, "server:minecraft:sound")));
        insertAttributePanelWidget(container, attributeEditorRow("Hit Sound", attributeSearchTextEditor(components, componentId, componentId + ".hit_sound", value.getOrDefault("hit_sound", "minecraft:item.trident.hit"), soundOptions(), false, "server:minecraft:sound")));
        addConditionEditorRow(container, components, componentId, componentId + ".damage_conditions", "Damage Conditions", value.get("damage_conditions"));
        addConditionEditorRow(container, components, componentId, componentId + ".knockback_conditions", "Knockback Conditions", value.get("knockback_conditions"));
        insertAttributePanelWidget(container, attributeEditorRow("Dismount Duration", attributeNumberEditor(components, componentId, componentId + ".dismount_conditions.max_duration_ticks", dismount.getOrDefault("max_duration_ticks", 20), "Ticks")));
        insertAttributePanelWidget(container, attributeEditorRow("Minimum Speed", attributeNumberEditor(components, componentId, componentId + ".dismount_conditions.min_speed", dismount.getOrDefault("min_speed", 0.0), "Speed")));
        insertAttributePanelWidget(container, attributeEditorRow("Relative Speed", attributeNumberEditor(components, componentId, componentId + ".dismount_conditions.min_relative_speed", dismount.getOrDefault("min_relative_speed", 0.0), "Speed")));
    }

    private void addAttackRangeEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Minimum Reach", attributeNumberEditor(components, componentId, componentId + ".min_reach", value.getOrDefault("min_reach", 0.0), "Blocks")));
        insertAttributePanelWidget(container, attributeEditorRow("Maximum Reach", attributeNumberEditor(components, componentId, componentId + ".max_reach", value.getOrDefault("max_reach", 3.0), "Blocks")));
        insertAttributePanelWidget(container, attributeEditorRow("Creative Minimum Reach", attributeNumberEditor(components, componentId, componentId + ".min_creative_reach", value.getOrDefault("min_creative_reach", 0.0), "Blocks")));
        insertAttributePanelWidget(container, attributeEditorRow("Creative Maximum Reach", attributeNumberEditor(components, componentId, componentId + ".max_creative_reach", value.getOrDefault("max_creative_reach", 5.0), "Blocks")));
        insertAttributePanelWidget(container, attributeEditorRow("Hitbox Margin", attributeNumberEditor(components, componentId, componentId + ".hitbox_margin", value.getOrDefault("hitbox_margin", 0.3), "Blocks")));
        insertAttributePanelWidget(container, attributeEditorRow("Mob Factor", attributeNumberEditor(components, componentId, componentId + ".mob_factor", value.getOrDefault("mob_factor", 1.0), "Scale")));
    }

    private void addSwingAnimationEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Swing Type", attributeChoiceEditor(components, componentId, componentId + ".type", List.of("none", "whack", "stab"), String.valueOf(value.getOrDefault("type", "whack")))));
        insertAttributePanelWidget(container, attributeEditorRow("Swing Duration", attributeNumberEditor(components, componentId, componentId + ".duration", value.getOrDefault("duration", 6), "Ticks")));
    }

    private void addUseEffectsEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Can Sprint", attributeBooleanEditor(components, componentId, componentId + ".can_sprint", value.getOrDefault("can_sprint", true))));
        insertAttributePanelWidget(container, attributeEditorRow("Use Vibration", attributeBooleanEditor(components, componentId, componentId + ".interact_vibrations", value.getOrDefault("interact_vibrations", true))));
        insertAttributePanelWidget(container, attributeEditorRow("Speed Multiplier", attributeNumberEditor(components, componentId, componentId + ".speed_multiplier", value.getOrDefault("speed_multiplier", 1.0), "Scale")));
    }

    private void addRepairableEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        Object currentItems = value.getOrDefault("items", "minecraft:iron_ingot");
        TextInputWidget items = new TextInputWidget.Builder()
            .text(repairItemsText(currentItems))
            .placeholder("Item, Tag, Or List")
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        items.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId + ".items", repairItemsValue(currentItems, items.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Repair Items", searchableInputRow(items, materialOptions(), false, "server:minecraft:material")));
    }

    private void addContainerLootEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        TextInputWidget table = new TextInputWidget.Builder()
            .text(formatComponentValue(value.getOrDefault("loot_table", "minecraft:chests/simple_dungeon")))
            .placeholder("Loot Table")
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        table.setOnChange(() -> updateNestedAttributeComponent(components, componentId, componentId + ".loot_table", normalizeMinecraftKey(table.getText())));
        insertAttributePanelWidget(container, attributeEditorRow("Loot Table", searchableInputRow(table, lootTableOptions(), false, "server:minecraft:loot_table")));
        insertAttributePanelWidget(container, attributeEditorRow("Loot Seed", attributeNumberEditor(components, componentId, componentId + ".seed", value.getOrDefault("seed", 0), "Seed")));
    }

    private void addToolEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Mining Speed", attributeNumberEditor(components, componentId, componentId + ".default_mining_speed", value.getOrDefault("default_mining_speed", 1.0), "Speed")));
        insertAttributePanelWidget(container, attributeEditorRow("Block Damage", attributeNumberEditor(components, componentId, componentId + ".damage_per_block", value.getOrDefault("damage_per_block", 1), "Durability")));
        insertAttributePanelWidget(container, attributeEditorRow("Creative Break", attributeBooleanEditor(components, componentId, componentId + ".can_destroy_blocks_in_creative", value.getOrDefault("can_destroy_blocks_in_creative", true))));
        List<Object> rules = arrayValue(value.get("rules"));
        insertAttributePanelWidget(container, attributeStatusRow("Tool Rules", rules.isEmpty() ? "No Rules" : rules.size() + " Rules", attributeRowWidth(), false, componentId + "ToolRulesHeader"));
        for (int i = 0; i < rules.size(); i++) {
            addToolRuleEntryRow(container, components, componentId, objectValue(rules.get(i)), i);
        }
        AnimatedButton add = new AnimatedButton.Builder()
            .label("Add Rule")
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .accentType(ThemeManager.getAccent("nice"))
            .entranceAnimation(false)
            .onClick(() -> {
                Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
                List<Object> nextRules = arrayValue(next.get("rules"));
                nextRules.add(Map.of("blocks", "#minecraft:mineable/pickaxe", "speed", 2.0, "correct_for_drops", true));
                next.put("rules", nextRules);
                updateAttributeComponentRoot(components, componentId, next, true);
            })
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Add Rule", add));
    }

    private void addToolRuleEntryRow(Container container, Map<String, Object> components, String componentId, Map<String, Object> rule, int index) {
        TextInputWidget blocks = attributeCompactInput(blockPredicateText(rule.getOrDefault("blocks", "#minecraft:mineable/pickaxe")), "Blocks Or Tag");
        TextInputWidget speed = attributeCompactInput(formatComponentValue(rule.getOrDefault("speed", 2.0)), "Speed");
        ToggleWidget drops = new ToggleWidget.Builder()
            .toggled(Boolean.TRUE.equals(rule.getOrDefault("correct_for_drops", true)))
            .size(80, 18)
            .onChange(next -> updateToolRuleEntry(components, componentId, index, blocks.getText(), speed.getText(), next))
            .entranceAnimation(false)
            .build();
        blocks.setOnChange(() -> updateToolRuleEntry(components, componentId, index, blocks.getText(), speed.getText(), drops.getValue()));
        speed.setOnChange(() -> updateToolRuleEntry(components, componentId, index, blocks.getText(), speed.getText(), drops.getValue()));
        insertAttributePanelWidget(container, attributeEntryRow("Tool Rule " + (index + 1), blocks.getText() + " | Speed " + speed.getText(), blocks, attributeSearchButton(blocks, blockOptions(), "server:minecraft:block", valueId -> {
            blocks.setText(normalizeBlockPredicate(valueId));
            blocks.runOnChange();
        }), speed, drops, attributeDeleteButton(() -> removeNestedListEntry(components, componentId, "rules", index))));
    }

    private void updateToolRuleEntry(Map<String, Object> components, String componentId, int index, String blocks, String speed, boolean drops) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        List<Object> rules = arrayValue(next.get("rules"));
        if (index < 0 || index >= rules.size()) {
            return;
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("blocks", normalizeBlockPredicate(blocks));
        rule.put("speed", parseDouble(speed, 2.0));
        rule.put("correct_for_drops", drops);
        rules.set(index, rule);
        next.put("rules", rules);
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private void addDeathProtectionEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        List<Object> effects = arrayValue(value.get("death_effects"));
        insertAttributePanelWidget(container, attributeStatusRow("Death Effects", effects.isEmpty() ? "No Effects" : effects.size() + " Effects", attributeRowWidth(), false, componentId + "DeathEffectsHeader"));
        for (int i = 0; i < effects.size(); i++) {
            addDeathEffectEntryRow(container, components, componentId, objectValue(effects.get(i)), i);
        }
        AnimatedButton add = new AnimatedButton.Builder()
            .label("Add Effect")
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .accentType(ThemeManager.getAccent("nice"))
            .entranceAnimation(false)
            .onClick(() -> addNestedListEntry(components, componentId, "death_effects", Map.of("type", "minecraft:play_sound", "sound", "minecraft:item.totem.use")))
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Add Effect", add));
    }

    private void addDeathEffectEntryRow(Container container, Map<String, Object> components, String componentId, Map<String, Object> effect, int index) {
        String selectedType = String.valueOf(effect.getOrDefault("type", "minecraft:play_sound"));
        DropDownWidget<String> type = attributeDropdown(deathEffectTypeOptions(), selectedType, next -> updateDeathEffectEntry(components, componentId, index, next, soundInputText(effect)));
        TextInputWidget sound = attributeCompactInput(soundInputText(effect), "Sound");
        sound.setOnChange(() -> updateDeathEffectEntry(components, componentId, index, type.getSelectedItem(), sound.getText()));
        insertAttributePanelWidget(container, attributeEntryRow("Effect " + (index + 1), selectedType, type, sound, attributeSearchButton(sound, soundOptions(), "server:minecraft:sound", valueId -> {
            sound.setText(normalizeMinecraftKey(valueId));
            sound.runOnChange();
        }), attributeDeleteButton(() -> removeNestedListEntry(components, componentId, "death_effects", index))));
    }

    private String soundInputText(Map<String, Object> effect) {
        return formatComponentValue(effect.getOrDefault("sound", "minecraft:item.totem.use"));
    }

    private void updateDeathEffectEntry(Map<String, Object> components, String componentId, int index, String type, String sound) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        List<Object> effects = arrayValue(next.get("death_effects"));
        if (index < 0 || index >= effects.size()) {
            return;
        }
        String normalizedType = normalizeMinecraftKey(type);
        if (!"minecraft:play_sound".equals(normalizedType)) {
            return;
        }
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("type", normalizedType);
        effect.put("sound", normalizeMinecraftKey(sound));
        effects.set(index, effect);
        next.put("death_effects", effects);
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private void addBeesEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        List<Object> bees = arrayValue(value);
        insertAttributePanelWidget(container, attributeStatusRow("Stored Bees", bees.isEmpty() ? "No Bees" : bees.size() + " Bees", attributeRowWidth(), false, componentId + "BeesHeader"));
        for (int i = 0; i < bees.size(); i++) {
            addBeeEntryRow(container, components, componentId, objectValue(bees.get(i)), i);
        }
        AnimatedButton add = new AnimatedButton.Builder()
            .label("Add Bee")
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .accentType(ThemeManager.getAccent("nice"))
            .entranceAnimation(false)
            .onClick(() -> {
                List<Object> next = arrayValue(currentAttributeValue(components, componentId));
                next.add(defaultBeeEntry());
                updateAttributeComponentRoot(components, componentId, next, true);
            })
            .build();
        insertAttributePanelWidget(container, attributeEditorRow("Add Bee", add));
    }

    private void addBeeEntryRow(Container container, Map<String, Object> components, String componentId, Map<String, Object> bee, int index) {
        Map<String, Object> entityData = objectValue(bee.get("entity_data"));
        TextInputWidget entity = attributeCompactInput(formatComponentValue(entityData.getOrDefault("id", "minecraft:bee")), "Entity");
        TextInputWidget ticks = attributeCompactInput(formatComponentValue(bee.getOrDefault("ticks_in_hive", 0)), "Ticks");
        TextInputWidget minTicks = attributeCompactInput(formatComponentValue(bee.getOrDefault("min_ticks_in_hive", 2400)), "Min Ticks");
        entity.setOnChange(() -> updateBeeEntry(components, componentId, index, entity.getText(), ticks.getText(), minTicks.getText()));
        ticks.setOnChange(() -> updateBeeEntry(components, componentId, index, entity.getText(), ticks.getText(), minTicks.getText()));
        minTicks.setOnChange(() -> updateBeeEntry(components, componentId, index, entity.getText(), ticks.getText(), minTicks.getText()));
        insertAttributePanelWidget(container, attributeEntryRow("Bee " + (index + 1), entity.getText(), entity, attributeSearchButton(entity, entityTypeOptions(), "server:minecraft:entity_type", valueId -> {
            entity.setText(normalizeMinecraftKey(valueId));
            entity.runOnChange();
        }), ticks, minTicks, attributeDeleteButton(() -> removeRootListEntry(components, componentId, index))));
    }

    private Map<String, Object> defaultBeeEntry() {
        return Map.of(
            "entity_data", Map.of("id", "minecraft:bee"),
            "ticks_in_hive", 0,
            "min_ticks_in_hive", 2400
        );
    }

    private void updateBeeEntry(Map<String, Object> components, String componentId, int index, String entity, String ticks, String minTicks) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("entity_data", Map.of("id", normalizeMinecraftKey(entity)));
        entry.put("ticks_in_hive", parseNonNegativeInt(ticks, 0));
        entry.put("min_ticks_in_hive", parseNonNegativeInt(minTicks, 2400));
        updateRootListEntry(components, componentId, index, entry, false);
    }

    private void addSuspiciousStewEffectsEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        List<Object> effects = arrayValue(value);
        insertAttributePanelWidget(container, attributeStatusRow("Stew Effects", effects.isEmpty() ? "No Effects" : effects.size() + " Effects", attributeRowWidth(), false, componentId + "StewEffectsHeader"));
        for (int i = 0; i < effects.size(); i++) {
            addStewEffectEntryRow(container, components, componentId, objectValue(effects.get(i)), i);
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Effect", attributeAddSearchRow("Effect", mobEffectOptions(), "server:minecraft:mob_effect", valueId -> {
            List<Object> next = arrayValue(currentAttributeValue(components, componentId));
            next.add(Map.of("id", normalizeMinecraftKey(valueId), "duration", 160));
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private void addStewEffectEntryRow(Container container, Map<String, Object> components, String componentId, Map<String, Object> effect, int index) {
        TextInputWidget id = attributeCompactInput(formatComponentValue(effect.getOrDefault("id", "minecraft:night_vision")), "Effect");
        TextInputWidget duration = attributeCompactInput(formatComponentValue(effect.getOrDefault("duration", 160)), "Ticks");
        id.setOnChange(() -> updateStewEffectEntry(components, componentId, index, id.getText(), duration.getText()));
        duration.setOnChange(() -> updateStewEffectEntry(components, componentId, index, id.getText(), duration.getText()));
        insertAttributePanelWidget(container, attributeEntryRow("Effect " + (index + 1), id.getText() + " | " + duration.getText() + " Ticks", id, attributeSearchButton(id, mobEffectOptions(), "server:minecraft:mob_effect", valueId -> {
            id.setText(normalizeMinecraftKey(valueId));
            id.runOnChange();
        }), duration, attributeDeleteButton(() -> removeRootListEntry(components, componentId, index))));
    }

    private void updateStewEffectEntry(Map<String, Object> components, String componentId, int index, String id, String duration) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", normalizeMinecraftKey(id));
        entry.put("duration", parseNonNegativeInt(duration, 160));
        updateRootListEntry(components, componentId, index, entry, false);
    }

    private void addLodestoneTrackerEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        Map<String, Object> target = objectValue(value.get("target"));
        Object posValue = target.get("pos");
        List<Integer> pos = positionList(posValue);
        insertAttributePanelWidget(container, attributeEditorRow("Dimension", attributeSearchTextEditor(components, componentId, componentId + ".target.dimension", target.getOrDefault("dimension", "minecraft:overworld"), dimensionOptions(), false, "server:minecraft:dimension_type")));
        insertAttributePanelWidget(container, attributeEditorRow("Tracked", attributeBooleanEditor(components, componentId, componentId + ".tracked", value.getOrDefault("tracked", true))));
        addPositionRow(container, components, componentId, pos);
    }

    private void addPositionRow(Container container, Map<String, Object> components, String componentId, List<Integer> pos) {
        TextInputWidget x = attributeCompactInput(String.valueOf(pos.get(0)), "X");
        TextInputWidget y = attributeCompactInput(String.valueOf(pos.get(1)), "Y");
        TextInputWidget z = attributeCompactInput(String.valueOf(pos.get(2)), "Z");
        x.setOnChange(() -> updateLodestonePosition(components, componentId, x.getText(), y.getText(), z.getText()));
        y.setOnChange(() -> updateLodestonePosition(components, componentId, x.getText(), y.getText(), z.getText()));
        z.setOnChange(() -> updateLodestonePosition(components, componentId, x.getText(), y.getText(), z.getText()));
        insertAttributePanelWidget(container, attributeEntryRow("Position", x.getText() + ", " + y.getText() + ", " + z.getText(), x, y, z));
    }

    private List<Integer> positionList(Object value) {
        if (value instanceof List<?> list && list.size() >= 3) {
            return List.of(parseSignedInt(list.get(0), 0), parseSignedInt(list.get(1), 64), parseSignedInt(list.get(2), 0));
        }
        if (value instanceof Map<?, ?> map) {
            return List.of(parseSignedInt(map.get("x"), 0), parseSignedInt(map.get("y"), 64), parseSignedInt(map.get("z"), 0));
        }
        return List.of(0, 64, 0);
    }

    private void updateLodestonePosition(Map<String, Object> components, String componentId, String x, String y, String z) {
        updateNestedAttributeComponent(components, componentId, componentId + ".target.pos", List.of(parseSignedInt(x, 0), parseSignedInt(y, 64), parseSignedInt(z, 0)));
    }

    private void addBlockStateEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        Map<String, Object> states = new LinkedHashMap<>(value);
        if (states.isEmpty()) {
            states.put("waterlogged", "false");
        }
        for (Map.Entry<String, Object> entry : states.entrySet()) {
            addBlockStateEntryRow(container, components, componentId, entry.getKey(), entry.getValue());
        }
        RowWidget add = keyValueAddRow("State", "Value", (key, nextValue) -> {
            Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
            next.put(key, blockStateValue(key, nextValue));
            updateAttributeComponentRoot(components, componentId, next, true);
        });
        insertAttributePanelWidget(container, attributeEditorRow("Add State", add));
    }

    private void addBlockStateEntryRow(Container container, Map<String, Object> components, String componentId, String key, Object value) {
        String text = formatComponentValue(value);
        if (isBooleanText(text)) {
            ToggleWidget toggle = new ToggleWidget.Builder()
                .toggled(Boolean.parseBoolean(text))
                .size(80, 18)
                .onChange(next -> updateBlockStateEntry(components, componentId, key, String.valueOf(next)))
                .entranceAnimation(false)
                .build();
            insertAttributePanelWidget(container, attributeEntryRow(titleCaseAttributeToken(key), "Boolean Block State", toggle, attributeDeleteButton(() -> removeMapEntry(components, componentId, key))));
            return;
        }
        TextInputWidget input = attributeCompactInput(text, "Value");
        input.setOnChange(() -> updateBlockStateEntry(components, componentId, key, input.getText()));
        insertAttributePanelWidget(container, attributeEntryRow(titleCaseAttributeToken(key), "Block State Value", input, attributeSearchButton(input, blockStateValueOptions(key), "", valueId -> {
            input.setText(valueId);
            input.runOnChange();
        }), attributeDeleteButton(() -> removeMapEntry(components, componentId, key))));
    }

    private void updateBlockStateEntry(Map<String, Object> components, String componentId, String key, String value) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        next.put(key, blockStateValue(key, value));
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private Object blockStateValue(String key, String value) {
        if (isBooleanText(value)) {
            return value.toLowerCase(Locale.ROOT);
        }
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private void addDataMapEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value, String title, List<String> idOptions, String catalog) {
        if (value.containsKey("id") || "Block Entity".equals(title) || "Entity".equals(title)) {
            insertAttributePanelWidget(container, attributeEditorRow("ID", attributeSearchTextEditor(components, componentId, componentId + ".id", value.getOrDefault("id", "Entity".equals(title) ? "minecraft:pig" : "minecraft:chest"), idOptions, false, catalog)));
        }
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            String key = entry.getKey();
            if ("id".equals(key)) {
                continue;
            }
            addDataFieldRow(container, components, componentId, key, entry.getValue());
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Field", keyValueAddRow("Field", "Value", (key, nextValue) -> {
            Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
            next.put(key, parseLooseDataValue(nextValue));
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private void addDataFieldRow(Container container, Map<String, Object> components, String componentId, String key, Object value) {
        if (value instanceof Boolean || isNumericBooleanField(key, value)) {
            ToggleWidget toggle = new ToggleWidget.Builder()
                .toggled(booleanDataValue(value))
                .size(80, 18)
                .onChange(next -> {
                    Map<String, Object> map = objectValue(currentAttributeValue(components, componentId));
                    map.put(key, next);
                    updateAttributeComponentRoot(components, componentId, map, false);
                })
                .entranceAnimation(false)
                .build();
            insertAttributePanelWidget(container, attributeEntryRow(humanizeAttributeToken(key), "Boolean Entity Data", toggle, attributeDeleteButton(() -> removeMapEntry(components, componentId, key))));
            return;
        }
        TextInputWidget input = attributeCompactInput(formatComponentValue(value), humanizeAttributeToken(key));
        input.setOnChange(() -> {
            Map<String, Object> map = objectValue(currentAttributeValue(components, componentId));
            map.put(key, parseLooseDataValue(input.getText()));
            updateAttributeComponentRoot(components, componentId, map, false);
        });
        insertAttributePanelWidget(container, attributeEntryRow(humanizeAttributeToken(key), "Entity Or Block Entity Data", input, attributeDeleteButton(() -> removeMapEntry(components, componentId, key))));
    }

    private void addDebugStickStateEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            String block = entry.getKey();
            TextInputWidget blockInput = attributeCompactInput(block, "Block");
            TextInputWidget property = attributeCompactInput(formatComponentValue(entry.getValue()), "Property");
            blockInput.setOnChange(() -> updateDebugStickState(components, componentId, block, blockInput.getText(), property.getText()));
            property.setOnChange(() -> updateDebugStickState(components, componentId, block, blockInput.getText(), property.getText()));
            insertAttributePanelWidget(container, attributeEntryRow("Block State", blockInput.getText() + " | " + property.getText(), blockInput, attributeSearchButton(blockInput, blockOptions(), "server:minecraft:block", valueId -> {
                blockInput.setText(normalizeMinecraftKey(valueId));
                blockInput.runOnChange();
            }), property, attributeSearchButton(property, commonBlockStateNames(), "", valueId -> {
                property.setText(valueId);
                property.runOnChange();
            }), attributeDeleteButton(() -> removeMapEntry(components, componentId, block))));
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add State", keyValueAddRow("Block", "Property", (key, nextValue) -> {
            Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
            next.put(normalizeMinecraftKey(key), nextValue);
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private void updateDebugStickState(Map<String, Object> components, String componentId, String previousKey, String block, String property) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        next.remove(previousKey);
        next.put(normalizeMinecraftKey(block), property != null ? property.trim() : "");
        updateAttributeComponentRoot(components, componentId, next, false);
    }

    private void addRecipeEditorRows(Container container, Map<String, Object> components, String componentId, Object value) {
        List<Object> recipes = arrayValue(value);
        insertAttributePanelWidget(container, attributeStatusRow("Recipes", recipes.isEmpty() ? "No Recipes" : recipes.size() + " Recipes", attributeRowWidth(), false, componentId + "RecipesHeader"));
        for (int i = 0; i < recipes.size(); i++) {
            TextInputWidget recipe = attributeCompactInput(formatComponentValue(recipes.get(i)), "Recipe");
            int index = i;
            recipe.setOnChange(() -> updateRootListEntry(components, componentId, index, normalizeMinecraftKey(recipe.getText()), false));
            insertAttributePanelWidget(container, attributeEntryRow("Recipe " + (i + 1), recipe.getText(), recipe, attributeSearchButton(recipe, recipeOptions(), "server:minecraft:recipe", valueId -> {
                recipe.setText(normalizeMinecraftKey(valueId));
                recipe.runOnChange();
            }), attributeDeleteButton(() -> removeRootListEntry(components, componentId, index))));
        }
        insertAttributePanelWidget(container, attributeEditorRow("Add Recipe", attributeAddSearchRow("Recipe", recipeOptions(), "server:minecraft:recipe", valueId -> {
            List<Object> next = arrayValue(currentAttributeValue(components, componentId));
            next.add(normalizeMinecraftKey(valueId));
            updateAttributeComponentRoot(components, componentId, next, true);
        })));
    }

    private RowWidget keyValueAddRow(String keyPlaceholder, String valuePlaceholder, BiValueConsumer onAdd) {
        TextInputWidget key = attributeCompactInput("", keyPlaceholder);
        TextInputWidget value = attributeCompactInput("", valuePlaceholder);
        SquareButtonWidget add = new SquareButtonWidget.Builder()
            .imagePath("add.png")
            .size(18, 18)
            .hint("Add")
            .accentType(ThemeManager.getAccent("nice"))
            .entranceAnimation(false)
            .onClick(() -> {
                if (!key.getText().isBlank()) {
                    onAdd.accept(key.getText(), value.getText());
                    key.setText("");
                    value.setText("");
                }
            })
            .build();
        RowWidget row = new RowWidget.Builder()
            .size(260, 18)
            .padding(4)
            .addWidget(key, value, add)
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private void removeRootListEntry(Map<String, Object> components, String componentId, int index) {
        List<Object> next = arrayValue(currentAttributeValue(components, componentId));
        if (index < 0 || index >= next.size()) {
            return;
        }
        next.remove(index);
        updateAttributeComponentRoot(components, componentId, next, true);
    }

    private void addNestedListEntry(Map<String, Object> components, String componentId, String key, Object entry) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        List<Object> list = arrayValue(next.get(key));
        list.add(entry);
        next.put(key, list);
        updateAttributeComponentRoot(components, componentId, next, true);
    }

    private void removeNestedListEntry(Map<String, Object> components, String componentId, String key, int index) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        List<Object> list = arrayValue(next.get(key));
        if (index < 0 || index >= list.size()) {
            return;
        }
        list.remove(index);
        next.put(key, list);
        updateAttributeComponentRoot(components, componentId, next, true);
    }

    private void removeMapEntry(Map<String, Object> components, String componentId, String key) {
        Map<String, Object> next = objectValue(currentAttributeValue(components, componentId));
        next.remove(key);
        updateAttributeComponentRoot(components, componentId, next, true);
    }

    private int parseSignedInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean isBooleanText(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    private boolean isNumericBooleanField(String key, Object value) {
        return "NoAI".equalsIgnoreCase(key) && ("1".equals(String.valueOf(value)) || "0".equals(String.valueOf(value)));
    }

    private boolean booleanDataValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value);
        return "1".equals(text) || Boolean.parseBoolean(text);
    }

    private Object parseLooseDataValue(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (isBooleanText(text)) {
            return Boolean.parseBoolean(text);
        }
        if (isInteger(text)) {
            return Integer.parseInt(text);
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
        return text;
    }

    private RowWidget attributeStackSizeEditor(Map<String, Object> components, String componentId, Object value) {
        int stackSize = Math.clamp(parsePositiveInt(value, 64), 1, 99);
        TextInputWidget input = new TextInputWidget.Builder()
            .text(String.valueOf(stackSize))
            .placeholder("1-99")
            .forcePlaceholder(false)
            .size(56, 18)
            .build();
        DoubleSliderWidget[] sliderRef = new DoubleSliderWidget[1];
        DoubleSliderWidget slider = new DoubleSliderWidget.Builder()
            .label("Stack " + stackSize)
            .value((stackSize - 1) / 98.0)
            .onChange(() -> {
                DoubleSliderWidget sliderWidget = sliderRef[0];
                int next = 1 + (int) Math.round(sliderWidget.getValue() * 98.0);
                sliderWidget.label = "Stack " + next;
                input.setText(String.valueOf(next));
                updateNestedAttributeComponent(components, componentId, componentId, next);
            })
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .entranceAnimation(false)
            .build();
        sliderRef[0] = slider;
        input.setOnChange(() -> {
            int next = Math.clamp(parsePositiveInt(input.getText(), stackSize), 1, 99);
            slider.label = "Stack " + next;
            slider.setValue((next - 1) / 98.0);
            updateNestedAttributeComponent(components, componentId, componentId, next);
        });
        RowWidget row = new RowWidget.Builder()
            .size(260, 18)
            .padding(4)
            .addWidget(slider, input)
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    @FunctionalInterface
    private interface BiValueConsumer {
        void accept(String key, String value);
    }

    private void addConditionEditorRow(Container container, Map<String, Object> components, String componentId, String path, String title, Object value) {
        CodeEditorWidget editor = new CodeEditorWidget(0, 0, Math.max(180, attributeRowWidth() - 12), 58);
        ReSyncStudioPanelState.disableEntrance(editor);
        editor.setShowLineNumbers(false);
        editor.setShowSearchNavigation(false);
        editor.setDimNonMatchingLines(false);
        editor.setShowCursorLineHighlight(false);
        editor.setShowSearchMatchHighlight(false);
        editor.setWordWrap(true);
        editor.setText(conditionText(value));
        editor.onChange = text -> updateNestedAttributeComponent(components, componentId, path, conditionValue(value, text));
        insertAttributePanelWidget(container, attributeLargeEditorRow(title, editor, 74));
    }

    private void addEquippableEditorRows(Container container, Map<String, Object> components, String componentId, Map<String, Object> value) {
        insertAttributePanelWidget(container, attributeEditorRow("Slot", attributeChoiceEditor(components, componentId, componentId + ".slot", equipmentSlotOptions(), String.valueOf(value.getOrDefault("slot", "head")))));
        insertAttributePanelWidget(container, attributeEditorRow("Equip Sound", attributeSearchTextEditor(components, componentId, componentId + ".equip_sound", value.getOrDefault("equip_sound", "minecraft:item.armor.equip_generic"), soundOptions(), false, "server:minecraft:sound")));
        insertAttributePanelWidget(container, attributeEditorRow("Asset ID", attributeOptionalSearchTextEditor(components, componentId, componentId + ".asset_id", value.getOrDefault("asset_id", ""), equipmentAssetOptions(), "")));
        insertAttributePanelWidget(container, attributeEditorRow("Camera Overlay", attributeOptionalSearchTextEditor(components, componentId, componentId + ".camera_overlay", value.getOrDefault("camera_overlay", ""), materialModelOptions(), "server:minecraft:material")));
        insertAttributePanelWidget(container, attributeEditorRow("Dispensable", attributeBooleanEditor(components, componentId, componentId + ".dispensable", value.getOrDefault("dispensable", true))));
        insertAttributePanelWidget(container, attributeEditorRow("Swappable", attributeBooleanEditor(components, componentId, componentId + ".swappable", value.getOrDefault("swappable", true))));
        insertAttributePanelWidget(container, attributeEditorRow("Damage On Hurt", attributeBooleanEditor(components, componentId, componentId + ".damage_on_hurt", value.getOrDefault("damage_on_hurt", true))));
        insertAttributePanelWidget(container, attributeEditorRow("Equip On Interact", attributeBooleanEditor(components, componentId, componentId + ".equip_on_interact", value.getOrDefault("equip_on_interact", false))));
    }

    private String weaponSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Weapon";
        }
        Object damage = map.get("item_damage_per_attack");
        Object disable = map.get("disable_blocking_for_seconds");
        return "Damage Cost " + (damage != null ? damage : 1) + " | Shield Disable " + (disable != null ? disable : 0) + "s";
    }

    private String blocksAttacksSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Blocks Attacks";
        }
        Object delay = map.get("block_delay_seconds");
        int reductions = map.get("damage_reductions") instanceof List<?> list ? list.size() : 0;
        return "Delay " + (delay != null ? delay : 0.25) + "s | " + (reductions == 1 ? "1 Reduction" : reductions + " Reductions");
    }

    private String piercingWeaponSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Piercing Weapon";
        }
        boolean knockback = !Boolean.FALSE.equals(map.get("deals_knockback"));
        boolean dismounts = Boolean.TRUE.equals(map.get("dismounts"));
        return (knockback ? "Knockback" : "No Knockback") + " | " + (dismounts ? "Dismounts" : "No Dismount");
    }

    private String kineticWeaponSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Kinetic Weapon";
        }
        Object movement = map.get("forward_movement");
        Object damage = map.get("damage_multiplier");
        return "Movement " + (movement != null ? movement : 1.0) + " | Damage " + (damage != null ? damage : 1.0);
    }

    private String attackRangeSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Attack Range";
        }
        Object minimum = map.get("min_reach");
        Object maximum = map.get("max_reach");
        return "Reach " + (minimum != null ? minimum : 0.0) + "-" + (maximum != null ? maximum : 3.0);
    }

    private String swingAnimationSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Swing Animation";
        }
        Object type = map.get("type");
        Object duration = map.get("duration");
        return titleCaseAttributeToken(String.valueOf(type != null ? type : "whack")) + " | " + (duration != null ? duration : 6) + " Ticks";
    }

    private String useEffectsSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Use Effects";
        }
        Object speed = map.get("speed_multiplier");
        boolean sprint = !Boolean.FALSE.equals(map.get("can_sprint"));
        return "Speed " + (speed != null ? speed : 1.0) + " | " + (sprint ? "Can Sprint" : "No Sprint");
    }

    private String repairableSummary(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object items = map.get("items");
            if (items != null && !items.toString().isBlank()) {
                return "Repairs With " + items;
            }
        }
        return "Repairable";
    }

    private String containerLootSummary(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object table = map.get("loot_table");
            if (table != null && !table.toString().isBlank()) {
                return "Loot " + table;
            }
        }
        return "Container Loot";
    }

    private String equippableSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Equipment";
        }
        Object slot = map.get("slot");
        Object sound = map.get("equip_sound");
        String slotText = slot != null && !slot.toString().isBlank() ? titleCaseAttributeToken(slot.toString()) : "Slot";
        return sound != null && !sound.toString().isBlank() ? "Slot " + slotText + " | Sound " + sound : "Slot " + slotText;
    }

    private ToggleWidget attributeBooleanEditor(Map<String, Object> components, String componentId, String path, Object value) {
        return new ToggleWidget.Builder()
            .toggled(Boolean.TRUE.equals(value))
            .size(180, 18)
            .onChange(next -> updateNestedAttributeComponent(components, componentId, path, next))
            .entranceAnimation(false)
            .build();
    }

    private Map<String, Object> defaultEquippableValue(String material) {
        String name = material != null ? material.toUpperCase(Locale.ROOT) : "";
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("slot", defaultEquipmentSlot(name));
        value.put("equip_sound", "minecraft:item.armor.equip_generic");
        value.put("dispensable", true);
        value.put("swappable", true);
        value.put("damage_on_hurt", true);
        value.put("equip_on_interact", false);
        return value;
    }

    private String defaultEquipmentSlot(String material) {
        if (material.contains("HELMET") || material.contains("HEAD") || material.contains("SKULL") || material.contains("CARVED_PUMPKIN")) {
            return "head";
        }
        if (material.contains("CHESTPLATE") || material.contains("ELYTRA")) {
            return "chest";
        }
        if (material.contains("LEGGINGS")) {
            return "legs";
        }
        if (material.contains("BOOTS")) {
            return "feet";
        }
        if (material.contains("WOLF_ARMOR") || material.contains("HORSE_ARMOR")) {
            return "body";
        }
        if (material.contains("SHIELD")) {
            return "offhand";
        }
        return "mainhand";
    }

    private String normalizeDamageTypeSet(String value) {
        String text = value != null && !value.isBlank() ? value.trim().toLowerCase(Locale.ROOT) : "#minecraft:is_fire";
        if (text.startsWith("#")) {
            String tag = text.substring(1);
            return "#" + (tag.contains(":") ? tag : "minecraft:" + tag);
        }
        return normalizeMinecraftKey(text);
    }

    private Object normalizeOptionalDamageTypeSet(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeDamageTypeSet(value);
    }

    private String damageTypeSetText(Object value) {
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    values.add(item.toString());
                }
            }
            return String.join(", ", values);
        }
        return formatComponentValue(value);
    }

    private Object optionalDamageTypeSetValue(Object previous, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> values = splitCsv(text);
        if (previous instanceof List<?> || values.size() > 1) {
            List<Object> result = new ArrayList<>();
            for (String value : values) {
                result.add(normalizeDamageTypeSet(value));
            }
            return result;
        }
        return normalizeDamageTypeSet(text);
    }

    private String conditionText(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    lines.add(entry.getKey() + "=" + conditionFieldText(entry.getValue()));
                }
            }
            return String.join("\n", lines);
        }
        return "";
    }

    private String conditionFieldText(Object value) {
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    values.add(item.toString());
                }
            }
            return String.join(", ", values);
        }
        return formatComponentValue(value);
    }

    private Object conditionValue(Object previous, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> existing = objectValue(previous);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String line : splitLines(text)) {
            int split = line.indexOf('=');
            String key = split >= 0 ? line.substring(0, split).trim() : line.trim();
            String value = split >= 0 ? line.substring(split + 1).trim() : "";
            if (!key.isBlank()) {
                result.put(key, conditionFieldValue(existing.get(key), value));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private Object conditionFieldValue(Object previous, String text) {
        if (previous instanceof Boolean) {
            return Boolean.parseBoolean(text);
        }
        if (previous instanceof Number) {
            return parseComponentEditorValue(previous, text);
        }
        if (previous instanceof List<?> || text.contains(",")) {
            return new ArrayList<>(splitCsv(text));
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        if (isInteger(text)) {
            return Integer.parseInt(text.trim());
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
        return text;
    }

    private String repairItemsText(Object value) {
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    items.add(item.toString());
                }
            }
            return String.join(", ", items);
        }
        return formatComponentValue(value);
    }

    private Object repairItemsValue(Object previous, String text) {
        if (text == null || text.isBlank()) {
            return previous instanceof List<?> ? new ArrayList<>() : "minecraft:iron_ingot";
        }
        List<String> items = splitCsv(text);
        if (previous instanceof List<?> || items.size() > 1) {
            List<Object> next = new ArrayList<>();
            for (String item : items) {
                next.add(normalizeBlockPredicate(item));
            }
            return next;
        }
        return normalizeBlockPredicate(text);
    }

    private String damageResistantSummary(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object types = map.get("types");
            if (types != null && !types.toString().isBlank()) {
                return "Resists " + types;
            }
        }
        return "Damage Resistant";
    }

    private String useCooldownSummary(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "Cooldown";
        }
        Object seconds = map.get("seconds");
        if (seconds != null && !seconds.toString().isBlank()) {
            return "Cooldown " + seconds + "s";
        }
        return "Cooldown";
    }

    private Object currentAttributeValue(Map<String, Object> components, String componentId) {
        if (activeAttributeComponents != null && activeAttributeComponents.containsKey(componentId)) {
            return activeAttributeComponents.get(componentId);
        }
        if (attributePreviewValues.containsKey(componentId)) {
            return attributePreviewValues.get(componentId);
        }
        return components.get(componentId);
    }

    private Map<String, Object> attributeDraftComponents(Map<String, Object> fallback) {
        if (activeAttributeComponents != null) {
            return copyAttributeComponents(activeAttributeComponents);
        }
        return copyAttributeComponents(fallback);
    }

    private TitledRowWidget attributeEditorRow(String title, AnimatedWidget widget) {
        int width = Math.max(180, attributeRowWidth() - 4);
        return new TitledRowWidget.Builder()
            .title(title)
            .description(attributeEditorDescription(title))
            .size(width, 36)
            .gap(4)
            .addWidget(widget)
            .build();
    }

    private TitledRowWidget attributeLargeEditorRow(String title, AnimatedWidget widget, int height) {
        int width = Math.max(180, attributeRowWidth() - 4);
        return new TitledRowWidget.Builder()
            .title(title)
            .description(attributeEditorDescription(title))
            .size(width, height)
            .gap(4)
            .addWidget(widget)
            .build();
    }

    private TitledRowWidget attributeEntryRow(String title, String description, AnimatedWidget... widgets) {
        int width = Math.max(180, attributeRowWidth() - 4);
        return new TitledRowWidget.Builder()
            .title(title)
            .description(description)
            .size(width, 36)
            .gap(4)
            .addWidget(widgets)
            .build();
    }

    private TextInputWidget attributeCompactInput(String value, String placeholder) {
        TextInputWidget input = new TextInputWidget.Builder()
            .text(value == null ? "" : value)
            .placeholder(placeholder)
            .forcePlaceholder(false)
            .size(120, 18)
            .build();
        ReSyncStudioPanelState.disableEntrance(input);
        return input;
    }

    private SquareButtonWidget attributeSearchButton(TextInputWidget input, List<String> options, String loadingCatalog, Consumer<String> onSelected) {
        return new SquareButtonWidget.Builder()
            .imagePath("search.png")
            .size(18, 18)
            .hint("Search")
            .entranceAnimation(false)
            .onClick(() -> {
                List<String> choices = normalizedOptions(options, input.getText());
                showSearchSelector(choices, input.getText(), value -> {
                    if (isRealOption(value)) {
                        onSelected.accept(value);
                    }
                }, input.getX(), input.getY() + input.getHeight(), loadingCatalog);
            })
            .build();
    }

    private SquareButtonWidget attributeDeleteButton(Runnable action) {
        return new SquareButtonWidget.Builder()
            .imagePath("delete.png")
            .size(18, 18)
            .hint("Delete")
            .accentType(ThemeManager.getAccent("danger"))
            .entranceAnimation(false)
            .onClick(action)
            .build();
    }

    private RowWidget attributeAddSearchRow(String placeholder, List<String> options, String loadingCatalog, Consumer<String> onAdd) {
        TextInputWidget input = attributeCompactInput("", placeholder);
        SquareButtonWidget search = attributeSearchButton(input, options, loadingCatalog, value -> input.setText(value));
        SquareButtonWidget add = new SquareButtonWidget.Builder()
            .imagePath("add.png")
            .size(18, 18)
            .hint("Add")
            .accentType(ThemeManager.getAccent("nice"))
            .entranceAnimation(false)
            .onClick(() -> {
                String value = input.getText();
                if (value == null || value.isBlank()) {
                    return;
                }
                onAdd.accept(value);
                input.setText("");
            })
            .build();
        RowWidget row = new RowWidget.Builder()
            .size(260, 18)
            .padding(4)
            .addWidget(input, search, add)
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private void updateAttributeComponentRoot(Map<String, Object> components, String componentId, Object value, boolean rebuildEditor) {
        if (rebuildEditor) {
            markAttributeEditorDirty(componentId);
        }
        updateNestedAttributeComponent(components, componentId, componentId, value);
    }

    private void removeListAttributeEntry(Map<String, Object> components, String componentId, int index) {
        List<Object> next = new ArrayList<>(arrayValue(currentAttributeValue(components, componentId)));
        if (index < 0 || index >= next.size()) {
            return;
        }
        next.remove(index);
        updateAttributeComponentRoot(components, componentId, next, true);
    }

    private String attributeEditorDescription(String title) {
        return switch (title) {
            case "Action" -> "Removes This Unsupported Component From The Item";
            case "Glint" -> "Forces The Enchantment Shine On Or Off, Ignoring Normal Enchantment State";
            case "Model ID" -> "Names The Item Model Resource The Client Should Render";
            case "Model Data" -> "Adds A Custom Model Data Number Used By Resource Pack Predicates";
            case "Text" -> "Sets The Visible Display Text Stored As A Minecraft Text Component";
            case "Lore" -> "Writes Tooltip Lines, One Line Per Row, In Display Order";
            case "Hide In Tooltip" -> "Lists Components That Should Not Render Their Extra Tooltip Details";
            case "Tooltip Style" -> "Names The Tooltip Style Resource Used To Render This Item Tooltip";
            case "Title" -> "Written Book Title Shown On The Book Cover And Tooltip";
            case "Author" -> "Written Book Author Name Stored Beside The Title";
            case "Generation" -> "Copy State For A Written Book, From Original To Tattered";
            case "Resolved" -> "Marks Written Book Text Components As Already Resolved By The Server";
            case "Pages" -> "Book Page Rows In Reading Order, Stored As Plain Page Text Or Text Components";
            case "Add Page" -> "Appends A New Blank Page To The Book Content";
            case "Use Time" -> "Controls How Many Seconds The Use Action Must Be Held";
            case "Animation" -> "Chooses The First-Person Use Animation The Client Plays";
            case "Sound" -> "Minecraft Sound ID Played When This Use Action Runs";
            case "Cooldown" -> "Delay In Seconds Before Items In This Cooldown Group Can Be Used Again";
            case "Cooldown Group" -> "Shared Namespaced Key So Multiple Items Can Reuse The Same Cooldown";
            case "Remainder" -> "Item Stack Left Behind After A Successful Use, Such As A Bowl Or Bottle";
            case "Damage Types" -> "Damage Type ID Or Tag This Item Resists, For Example #minecraft:is_fire";
            case "Particles" -> "Toggles The Small Particles Spawned While The Item Is Consumed";
            case "Food Restored" -> "Hunger Points Restored When The Item Is Eaten";
            case "Saturation" -> "Saturation Added Alongside Hunger, Higher Values Keep Hunger Longer";
            case "Always Eat" -> "Allows The Food To Be Used Even When The Player Is Already Full";
            case "Levels" -> "Maps Each Enchantment ID To Its Level";
            case "Add Enchantment" -> "Searches The Server Enchantment Registry And Adds A Level One Entry";
            case "Modifiers" -> "Each Row Changes One Attribute By Amount, Operation, And Equipment Slot";
            case "Add Attribute" -> "Searches The Attribute Registry And Adds A New Modifier Row";
            case "Material" -> "Trim Material Registry ID Used For The Armor Trim Color";
            case "Pattern" -> "Trim Or Banner Pattern Registry ID Used For The Visual Pattern";
            case "Flight" -> "Rocket Flight Duration Value Used By Firework Rockets";
            case "Shape" -> "Firework Explosion Shape Rendered By The Client";
            case "Colors" -> "Primary Explosion Colors As Hex Values Or Picked Dye Colors";
            case "Fade Colors" -> "Optional Fade Colors The Explosion Blends Toward";
            case "Trail" -> "Adds The Firework Trail Particle Effect";
            case "Twinkle" -> "Adds The Firework Twinkle Particle And Sound Effect";
            case "Patterns" -> "Banner Pattern Rows In Draw Order, Each With Pattern ID And Dye Color";
            case "Add Pattern" -> "Searches The Banner Pattern Registry And Appends A White Pattern";
            case "Add Decoration" -> "Adds Another Decorated Pot Side Item";
            case "Projectiles" -> "Item Stacks Preloaded Into A Projectile Weapon";
            case "Items" -> "Item Stacks Stored Inside This Container-Like Component";
            case "Slots" -> "Stored Item Rows With Slot Number, Item ID, And Count";
            case "Add Item" -> "Searches Materials And Adds A One-Count Item Stack";
            case "Break Blocks" -> "Blocks Or Tags This Item Is Allowed To Break In Adventure Mode";
            case "Place On" -> "Blocks Or Tags This Item Is Allowed To Be Placed On In Adventure Mode";
            case "Add Block" -> "Searches The Block Registry And Adds A Block Predicate Row";
            case "Tooltip" -> "Controls Whether This Component Shows Extra Data In The Tooltip";
            case "Tooltip Details" -> "Controls Secondary Tooltip Data Such As Hidden Component Lists";
            case "Stack Size" -> "Maximum Number Of Items Allowed In One Stack, Clamped To The Usable 1-99 Range";
            case "Max Durability" -> "Total Durability Capacity Before The Item Breaks";
            case "Damage Used" -> "Current Durability Damage Already Applied To The Item";
            case "Repair Cost" -> "Extra Anvil Cost Added When The Item Is Repaired Or Renamed";
            case "Attack Damage Cost" -> "Durability Lost Each Time This Weapon Attacks";
            case "Block Disable Time" -> "Seconds This Weapon Disables A Target Shield After Hitting";
            case "Block Delay" -> "Seconds Before Blocking Starts After The Player Begins Holding Use";
            case "Cooldown Scale" -> "Multiplier Applied To The Blocking Cooldown After Blocking Is Disabled";
            case "Block Sound" -> "Sound Played When An Incoming Attack Is Successfully Blocked";
            case "Disabled Sound" -> "Optional Sound Played When This Blocking Component Gets Disabled";
            case "Bypassed By" -> "Damage Type ID, Tag, Or List That Ignores This Blocking Component";
            case "Item Damage" -> "Formula That Converts Blocked Damage Into Durability Loss";
            case "Damage Threshold" -> "Minimum Incoming Damage Required Before Durability Is Reduced";
            case "Damage Base" -> "Flat Durability Loss Added When The Threshold Is Met";
            case "Damage Factor" -> "Multiplier Applied To Incoming Damage For Durability Loss";
            case "Damage Reductions" -> "Rules That Reduce Incoming Damage While Blocking";
            case "Add Reduction" -> "Adds A New Blocking Reduction Rule With Angle, Base, And Factor";
            case "Reduction Type" -> "Optional Damage Type Or Tag Restricting Which Damage This Rule Reduces";
            case "Knockback" -> "Controls Whether This Piercing Weapon Applies Knockback On Hit";
            case "Dismounts" -> "Controls Whether Hits Can Force The Target Off A Vehicle Or Mount";
            case "Throw Sound" -> "Sound Played When The Piercing Weapon Is Thrown Or Used";
            case "Hit Sound" -> "Sound Played When This Weapon Successfully Hits A Target";
            case "Charge Sound" -> "Sound Played While The Kinetic Attack Starts Or Charges";
            case "Contact Cooldown" -> "Ticks Before The Same Contact Attack Can Trigger Again";
            case "Delay" -> "Ticks To Wait Before The Kinetic Effect Starts";
            case "Forward Movement" -> "Forward Movement Applied During The Kinetic Attack";
            case "Damage Multiplier" -> "Multiplier Applied To Kinetic Impact Damage";
            case "Damage Conditions" -> "Key Value Predicate Fields Required Before Kinetic Damage Applies";
            case "Knockback Conditions" -> "Key Value Predicate Fields Required Before Kinetic Knockback Applies";
            case "Dismount Duration" -> "Ticks The Dismount Condition Window Remains Active";
            case "Minimum Speed" -> "Minimum Movement Speed Required To Trigger Dismount Logic";
            case "Relative Speed" -> "Minimum Relative Speed Between Attacker And Target";
            case "Minimum Reach" -> "Shortest Survival-Mode Distance This Item Can Attack From";
            case "Maximum Reach" -> "Longest Survival-Mode Distance This Item Can Attack From";
            case "Creative Minimum Reach" -> "Shortest Creative-Mode Distance This Item Can Attack From";
            case "Creative Maximum Reach" -> "Longest Creative-Mode Distance This Item Can Attack From";
            case "Hitbox Margin" -> "Extra Hitbox Padding Added When Checking Attack Reach";
            case "Mob Factor" -> "Reach Multiplier Applied When A Mob Uses This Item";
            case "Swing Type" -> "Animation Variant Played For Attacks Or Interactions";
            case "Swing Duration" -> "Length Of The Swing Animation In Ticks";
            case "Can Sprint" -> "Allows The Player To Keep Sprinting While Using The Item";
            case "Use Vibration" -> "Emits Use Interaction Vibrations For Sensors And Listeners";
            case "Speed Multiplier" -> "Movement Speed Multiplier While The Item Is Being Used";
            case "Repair Items" -> "Item ID, Tag, Or Comma List Accepted As Repair Ingredients";
            case "Loot Table" -> "Loot Table ID Used To Fill This Container When Opened";
            case "Loot Seed" -> "Optional Seed For Deterministic Container Loot Results";
            case "Mining Speed" -> "Fallback Mining Speed Used When No Tool Rule Matches The Broken Block";
            case "Block Damage" -> "Durability Cost Applied For Each Block Broken With This Tool";
            case "Creative Break" -> "Controls Whether Creative Mode Can Destroy Blocks With This Tool Component";
            case "Tool Rules" -> "Per Block Or Tag Mining Rules With Speed And Correct Drop Handling";
            case "Add Rule" -> "Adds A Tool Rule For A Block ID Or Block Tag";
            case "Death Effects" -> "Effects Triggered When This Item Protects The Holder From Death";
            case "Add Effect" -> "Adds A Sound Or Effect Entry To This Component";
            case "Stored Bees" -> "Bee Entity Entries Stored Inside A Hive Or Nest Item";
            case "Add Bee" -> "Adds A Stored Bee With Hive Timing Data";
            case "Slot Lock" -> "Presence Component Preventing Creative Slot Editing For This Stack";
            case "Lock Key" -> "Container Lock String Required To Open This Item's Container Data";
            case "Stew Effects" -> "Status Effects Granted When Suspicious Stew Is Consumed";
            case "Dimension" -> "Dimension ID Used By The Lodestone Compass Target";
            case "Tracked" -> "Controls Whether The Lodestone Compass Still Tracks Its Target";
            case "Position" -> "Block Position Targeted By The Lodestone Tracker";
            case "Add State" -> "Adds A Block State Property Name And Value";
            case "ID" -> "Registry ID Stored In This Data Component";
            case "Add Field" -> "Adds A Loose Data Field Without Requiring Raw JSON Editing";
            case "Recipes" -> "Recipe IDs Granted Or Stored By This Component";
            case "Add Recipe" -> "Adds A Recipe ID Row";
            case "Rarity" -> "Rarity Tier That Controls Tooltip Styling And Item Color";
            case "Enchantability" -> "Enchanting Power Used By Enchantment Table Rolls";
            case "Amplifier" -> "Ominous Bottle Amplifier Level Stored On The Item";
            case "Instrument" -> "Goat Horn Instrument Registry ID Played By This Item";
            case "Slot" -> "Equipment Slot Where This Item Can Be Worn Or Held";
            case "Equip Sound" -> "Sound Played When The Item Is Equipped";
            case "Asset ID" -> "Equipment Asset ID Used For The Rendered Wearable Model";
            case "Camera Overlay" -> "Optional Overlay Texture Shown While Equipped";
            case "Dispensable" -> "Allows Dispensers To Equip This Item Onto Entities";
            case "Swappable" -> "Allows Hotbar Swapping Into The Equipment Slot";
            case "Damage On Hurt" -> "Consumes Durability When The Wearing Entity Takes Damage";
            case "Equip On Interact" -> "Equips The Item When Used Directly On An Entity";
            case "Potion" -> "Potion Registry ID Used For Base Potion Effects";
            case "Color" -> "Custom RGB Color As Hex, Decimal, Or Picked Dye Color";
            case "Song" -> "Jukebox Song Registry ID This Item Can Play";
            case "Movement" -> "Presence Component Enabling Special Movement Behavior";
            case "Projectile" -> "Presence Component Enabling Special Projectile Behavior";
            case "Damage" -> "Presence Component Enabling Damage Behavior";
            case "Durability" -> "Presence Component Enabling Durability Behavior";
            default -> "Sets The " + title + " Value For This Component";
        };
    }

    private RowWidget attributeSearchTextEditor(Map<String, Object> components, String componentId, String path, Object value, List<String> options, boolean append, String loadingCatalog) {
        return attributeSearchTextEditor(components, componentId, path, value, options, append, loadingCatalog, true);
    }

    private RowWidget attributeSearchTextEditor(Map<String, Object> components, String componentId, String path, Object value, List<String> options, boolean append, String loadingCatalog, boolean namespacedStrings) {
        TextInputWidget input = new TextInputWidget.Builder()
            .text(formatComponentValue(value))
            .placeholder(editorPathLabel(path))
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        input.setOnChange(() -> updateNestedAttributeComponent(components, componentId, path, parseSearchEditorValue(value, input.getText(), namespacedStrings)));
        return searchableInputRow(input, options, append, loadingCatalog);
    }

    private Object parseSearchEditorValue(Object previous, String text) {
        return parseSearchEditorValue(previous, text, true);
    }

    private Object parseSearchEditorValue(Object previous, String text, boolean namespacedStrings) {
        if (previous instanceof String) {
            if (text == null || text.isBlank()) {
                return "";
            }
            return namespacedStrings ? normalizeMinecraftKey(text) : text;
        }
        return parseComponentEditorValue(previous, text);
    }

    private RowWidget attributeOptionalSearchTextEditor(Map<String, Object> components, String componentId, String path, Object value, List<String> options, String loadingCatalog) {
        TextInputWidget input = new TextInputWidget.Builder()
            .text(formatComponentValue(value))
            .placeholder(editorPathLabel(path))
            .forcePlaceholder(false)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        input.setOnChange(() -> updateNestedAttributeComponent(components, componentId, path, input.getText().isBlank() ? null : normalizeMinecraftKey(input.getText())));
        return searchableInputRow(input, options, false, loadingCatalog);
    }

    private RowWidget attributeLinePicker(CodeEditorWidget editor, List<String> options, String placeholder, String loadingCatalog, Function<String, String> formatter) {
        TextInputWidget input = new TextInputWidget.Builder()
            .text("")
            .placeholder(placeholder)
            .forcePlaceholder(true)
            .size(Math.max(100, attributeRowWidth() - 112), 18)
            .build();
        SquareButtonWidget selectorButton = new SquareButtonWidget.Builder()
            .imagePath("search.png")
            .size(18, 18)
            .hint("Search")
            .entranceAnimation(false)
            .onClick(() -> {
                List<String> choices = normalizedOptions(options, input.getText());
                showSearchSelector(choices, input.getText(), value -> {
                    if (!isRealOption(value)) {
                        return;
                    }
                    appendEditorLine(editor, formatter.apply(value));
                    input.setText("");
                }, input.getX(), input.getY() + input.getHeight(), loadingCatalog);
            })
            .build();
        ReSyncStudioPanelState.disableEntrance(input);
        RowWidget row = new RowWidget.Builder()
            .size(260, 18)
            .padding(4)
            .addWidget(input)
            .addWidget(selectorButton)
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private RowWidget attributeColorPicker(TextInputWidget input, boolean append) {
        SquareButtonWidget selectorButton = new SquareButtonWidget.Builder()
            .imagePath("search.png")
            .size(18, 18)
            .hint("Dye Color")
            .entranceAnimation(false)
            .onClick(() -> {
                List<String> choices = normalizedOptions(dyeColorOptions(), "");
                showSearchSelector(choices, "", value -> {
                    if (!isRealOption(value)) {
                        return;
                    }
                    String hex = dyeColorHex(value);
                    if (append) {
                        List<String> existing = splitCsv(input.getText());
                        if (!existing.contains(hex)) {
                            existing.add(hex);
                        }
                        input.setText(String.join(", ", existing));
                    } else {
                        input.setText(hex);
                    }
                    input.runOnChange();
                }, input.getX(), input.getY() + input.getHeight(), "server:minecraft:dye_color");
            })
            .build();
        ReSyncStudioPanelState.disableEntrance(input);
        RowWidget row = new RowWidget.Builder()
            .size(260, 18)
            .padding(4)
            .addWidget(input)
            .addWidget(selectorButton)
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private void appendEditorLine(CodeEditorWidget editor, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String current = editor.getText();
        String next = current == null || current.isBlank() ? line.trim() : current.stripTrailing() + "\n" + line.trim();
        editor.setText(next);
        editor.notifyTextChanged();
    }

    private TextInputWidget attributeNumberEditor(Map<String, Object> components, String componentId, String path, Object value, String placeholder) {
        TextInputWidget input = new TextInputWidget.Builder()
            .text(formatComponentValue(value))
            .placeholder(placeholder)
            .forcePlaceholder(false)
            .size(Math.max(140, attributeRowWidth() - 80), 18)
            .build();
        input.setOnChange(() -> updateNestedAttributeComponent(components, componentId, path, parseComponentEditorValue(value instanceof Number ? value : 0.0, input.getText().replace(',', '.'))));
        return input;
    }

    private DropDownWidget<String> attributeChoiceEditor(Map<String, Object> components, String componentId, String path, List<String> choices, String selected) {
        return attributeDropdown(choices, selected, next -> updateNestedAttributeComponent(components, componentId, path, next));
    }

    private DropDownWidget<String> attributeDropdown(List<String> choices, String selected, Consumer<String> onChange) {
        return attributeDropdown(choices, selected, onChange, Function.identity());
    }

    private DropDownWidget<String> attributeDropdown(List<String> choices, String selected, Consumer<String> onChange, Function<String, String> displayFunction) {
        List<String> options = normalizedOptions(choices, selected);
        return new DropDownWidget.Builder<>(options)
            .selectedItem(resolveSelectedOption(options, selected))
            .displayFunction(displayFunction)
            .onSelectionChanged(value -> {
                if (isRealOption(value)) {
                    onChange.accept(value);
                }
            })
            .size(120, 18)
            .maxVisibleItems(10)
            .entranceAnimation(false)
            .build();
    }

    private DropDownWidget<String> attributeOperationDropdown(String selected, Consumer<String> onChange) {
        return attributeDropdown(List.of("add_value", "add_multiplied_base", "add_multiplied_total"), selected, onChange, this::attributeOperationLabel);
    }

    private String attributeOperationLabel(String operation) {
        return switch (operation != null ? operation : "") {
            case "add_value" -> "Add";
            case "add_multiplied_base" -> "Base %";
            case "add_multiplied_total" -> "Total %";
            default -> titleCaseAttributeToken(operation);
        };
    }

    private List<String> materialModelOptions() {
        List<String> models = new ArrayList<>();
        List<String> materials = materialOptions();
        if (materials.equals(List.of("Loading"))) {
            return materials;
        }
        for (String material : materials) {
            if (isRealOption(material)) {
                models.add("minecraft:" + material.toLowerCase(Locale.ROOT));
            }
        }
        return normalizedOptions(models, "");
    }

    private List<String> hiddenTooltipComponentOptions() {
        List<String> options = new ArrayList<>();
        for (String id : List.of(
            "minecraft:attribute_modifiers",
            "minecraft:banner_patterns",
            "minecraft:bundle_contents",
            "minecraft:can_break",
            "minecraft:can_place_on",
            "minecraft:charged_projectiles",
            "minecraft:container",
            "minecraft:custom_data",
            "minecraft:custom_model_data",
            "minecraft:dyed_color",
            "minecraft:enchantments",
            "minecraft:firework_explosion",
            "minecraft:fireworks",
            "minecraft:instrument",
            "minecraft:jukebox_playable",
            "minecraft:lore",
            "minecraft:potion_contents",
            "minecraft:stored_enchantments",
            "minecraft:trim"
        )) {
            if (!options.contains(id)) {
                options.add(id);
            }
        }
        return options;
    }

    private void updateNestedAttributeComponent(Map<String, Object> current, String componentId, String path, Object value) {
        boolean displayed = displayAttributeComponentsForCurrent().containsKey(componentId);
        if (!activeAttributeComponents.containsKey(componentId) && componentId.equals(selectedAttributeComponent) && !displayed) {
            Object component = attributePreviewValues.containsKey(componentId) ? attributePreviewValues.get(componentId) : current.get(componentId);
            String relativePath = path != null && path.startsWith(componentId + ".") ? path.substring(componentId.length() + 1) : "";
            Object updated = updatePath(component, relativePath, value);
            if (updated == null && (path == null || path.equals(componentId))) {
                attributePreviewValues.remove(componentId);
            } else {
                attributePreviewValues.put(componentId, updated);
            }
            syncAttributeRowsInPlace();
            return;
        }
        Map<String, Object> draft = attributeDraftComponents(activeAttributeComponents != null ? activeAttributeComponents : current);
        boolean wasActive = draft.containsKey(componentId);
        Object component = draft.containsKey(componentId) ? draft.get(componentId) : current.get(componentId);
        String relativePath = path != null && path.startsWith(componentId + ".") ? path.substring(componentId.length() + 1) : "";
        Object updated = updatePath(component, relativePath, value);
        if (updated == null && (path == null || path.equals(componentId))) {
            draft.remove(componentId);
        } else {
            draft.put(componentId, updated);
        }
        editedAttributeComponents.add(componentId);
        commitAttributeDesignerDraft(draft);
        if (wasActive != draft.containsKey(componentId)) {
            refreshAttributeComponentList(false);
        }
    }

    @SuppressWarnings("unchecked")
    private Object updatePath(Object current, String path, Object value) {
        if (path == null || path.isBlank()) {
            return value;
        }
        String head = path;
        String tail = "";
        int split = path.indexOf('.');
        if (split >= 0) {
            head = path.substring(0, split);
            tail = path.substring(split + 1);
        }
        if (current instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(entry.getKey().toString(), entry.getValue());
                }
            }
            if (tail.isBlank() && value == null) {
                copy.remove(head);
            } else {
                copy.put(head, updatePath(copy.get(head), tail, value));
            }
            return copy;
        }
        if (current instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            try {
                int index = Integer.parseInt(head);
                if (index >= 0 && index < copy.size()) {
                    copy.set(index, updatePath(copy.get(index), tail, value));
                }
            } catch (NumberFormatException ignored) {
            }
            return copy;
        }
        if (current == null) {
            if (value == null) {
                return null;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put(head, updatePath(null, tail, value));
            return copy;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put(head, updatePath(null, tail, value));
        return copy;
    }

    private Map<String, OptionCatalogItem> attributeCatalog(String source) {
        Map<String, OptionCatalogItem> catalog = new LinkedHashMap<>();
        for (OptionCatalogItem item : attributeCatalogItems(source)) {
            if (item.getValue() != null && !item.getValue().isBlank()) {
                catalog.put(item.getValue(), item);
            }
        }
        return catalog;
    }

    private Map<String, Object> schemaMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> schemaFields(Map<String, Object> schema) {
        return schemaMap(schema != null ? schema.get("fields") : null);
    }

    private Map<String, Object> attributeComponentSchema(String componentId) {
        AttributeProjection projection = currentAttributeProjection();
        if (projection == null || componentId == null) {
            return Map.of();
        }
        return projection.componentSchemasByKey().getOrDefault(normalizedProjectionKey(componentId), Map.of());
    }

    private String schemaKind(Map<String, Object> schema, Object value) {
        Object kind = schema != null ? schema.get("kind") : null;
        if (kind != null && !kind.toString().isBlank() && !"raw".equals(kind.toString())) {
            return kind.toString();
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return "string";
    }

    private Map<String, Object> objectValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    private List<Object> arrayValue(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    private Object defaultValueForSchema(Map<String, Object> schema) {
        return switch (schemaKind(schema, null)) {
            case "boolean" -> false;
            case "number" -> 0;
            case "array" -> new ArrayList<>();
            case "object" -> defaultObjectForSchema(schema);
            default -> "";
        };
    }

    private Map<String, Object> defaultObjectForSchema(Map<String, Object> schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schemaFields(schema).entrySet()) {
            result.put(entry.getKey(), defaultValueForSchema(schemaMap(entry.getValue())));
        }
        return result;
    }

    private List<String> activeComponents(Map<String, Object> components, Map<String, OptionCatalogItem> catalog) {
        return components.keySet().stream()
            .sorted((left, right) -> {
                OptionCatalogItem leftItem = catalog.get(left);
                OptionCatalogItem rightItem = catalog.get(right);
                int groupRank = Integer.compare(attributeBrowseGroupRank(leftItem, left), attributeBrowseGroupRank(rightItem, right));
                if (groupRank != 0) {
                    return groupRank;
                }
                int rank = Integer.compare(attributeBrowseRank(leftItem, left), attributeBrowseRank(rightItem, right));
                return rank != 0 ? rank : componentLabel(left).compareToIgnoreCase(componentLabel(right));
            })
            .toList();
    }

    private List<OptionCatalogItem> availableComponents(Map<String, Object> components, Map<String, OptionCatalogItem> catalog, String query, String material) {
        return catalog.values().stream()
            .filter(item -> item != null && shouldOfferAttributeComponent(item.getValue(), query, material))
            .filter(item -> !components.containsKey(item.getValue()))
            .filter(item -> matchesAttributeSearch(item.getValue(), query, item))
            .sorted((left, right) -> {
                int groupRank = Integer.compare(attributeBrowseGroupRank(left), attributeBrowseGroupRank(right));
                if (groupRank != 0) {
                    return groupRank;
                }
                int applicable = Boolean.compare(!metadataBoolean(left, "applicable", false), !metadataBoolean(right, "applicable", false));
                if (applicable != 0) {
                    return applicable;
                }
                int recommended = Boolean.compare(!metadataBoolean(left, "recommended", false), !metadataBoolean(right, "recommended", false));
                if (recommended != 0) {
                    return recommended;
                }
                int rank = Integer.compare(attributeBrowseRank(left), attributeBrowseRank(right));
                return rank != 0 ? rank : componentLabel(left.getValue()).compareToIgnoreCase(componentLabel(right.getValue()));
            })
            .toList();
    }

    private boolean shouldOfferAttributeComponent(String id, String query, String material) {
        return id != null && !id.isBlank();
    }

    private Map<String, Object> displayAttributeComponents(Map<String, Object> components, Map<String, OptionCatalogItem> catalog) {
        Map<String, Object> display = copyAttributeComponents(components);
        for (OptionCatalogItem item : catalog.values()) {
            if (item == null || item.getValue() == null || display.containsKey(item.getValue())) {
                continue;
            }
            if (!metadataBoolean(item, "default", false)) {
                continue;
            }
            Object value = metadataValue(item, "defaultValue");
            if (value != null) {
                display.put(item.getValue(), copyAttributeValue(value));
            }
        }
        return display;
    }

    private Map<String, Object> displayAttributeComponentsForCurrent() {
        AttributeProjection projection = currentAttributeProjection();
        Map<String, Object> components = attributeDesignerOpen && activeAttributeComponents != null
            ? activeAttributeComponents : projection != null ? projection.components() : Map.of();
        Map<String, OptionCatalogItem> catalog = projection != null ? projection.catalog() : Map.of();
        return displayAttributeComponents(components, catalog);
    }

    private boolean isPrimaryAttributeForMaterial(String id, String material) {
        String value = material != null ? material.toUpperCase(Locale.ROOT) : "";
        return switch (id != null ? id : "") {
            case "minecraft:consumable",
                 "minecraft:food",
                 "minecraft:enchantment_glint_override",
                 "minecraft:custom_model_data",
                 "minecraft:item_model",
                 "minecraft:item_name",
                 "minecraft:custom_name",
                 "minecraft:lore",
                 "minecraft:tooltip_display",
                 "minecraft:tooltip_style",
                 "minecraft:can_break",
                 "minecraft:can_place_on",
                 "minecraft:max_stack_size",
                 "minecraft:max_damage",
                 "minecraft:damage",
                 "minecraft:repair_cost",
                 "minecraft:rarity",
                 "minecraft:enchantable",
                 "minecraft:dyed_color",
                 "minecraft:enchantments",
                 "minecraft:stored_enchantments",
                 "minecraft:unbreakable",
                 "minecraft:damage_resistant",
                 "minecraft:creative_slot_lock",
                 "minecraft:lock",
                 "minecraft:block_state",
                 "minecraft:block_entity_data",
                 "minecraft:bucket_entity_data",
                 "minecraft:entity_data" -> true;
            case "minecraft:trim" -> materialContains(value, "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");
            case "minecraft:firework_explosion" -> materialContains(value, "FIREWORK_STAR");
            case "minecraft:fireworks" -> materialContains(value, "FIREWORK_ROCKET");
            case "minecraft:banner_patterns" -> materialContains(value, "BANNER", "SHIELD");
            case "minecraft:pot_decorations" -> materialContains(value, "DECORATED_POT");
            case "minecraft:charged_projectiles" -> materialContains(value, "CROSSBOW");
            case "minecraft:bundle_contents" -> materialContains(value, "BUNDLE");
            case "minecraft:container" -> materialContains(value, "SHULKER_BOX");
            case "minecraft:container_loot" -> materialContains(value, "CHEST", "BARREL", "SHULKER_BOX");
            case "minecraft:writable_book_content" -> materialContains(value, "WRITABLE_BOOK");
            case "minecraft:written_book_content" -> materialContains(value, "WRITTEN_BOOK");
            case "minecraft:bees" -> materialContains(value, "BEE_NEST", "BEEHIVE");
            case "minecraft:suspicious_stew_effects" -> materialContains(value, "SUSPICIOUS_STEW");
            case "minecraft:lodestone_tracker" -> materialContains(value, "COMPASS");
            case "minecraft:debug_stick_state" -> materialContains(value, "DEBUG_STICK");
            case "minecraft:recipes" -> materialContains(value, "KNOWLEDGE_BOOK");
            case "minecraft:instrument" -> materialContains(value, "GOAT_HORN");
            case "minecraft:jukebox_playable" -> materialContains(value, "MUSIC_DISC");
            case "minecraft:potion_contents" -> materialContains(value, "POTION", "TIPPED_ARROW");
            case "minecraft:glider" -> materialContains(value, "ELYTRA");
            case "minecraft:intangible_projectile" -> materialContains(value, "ARROW");
            case "minecraft:ominous_bottle_amplifier" -> materialContains(value, "OMINOUS_BOTTLE");
            case "minecraft:weapon" -> materialContains(value, "SWORD", "AXE", "TRIDENT", "MACE");
            case "minecraft:blocks_attacks" -> materialContains(value, "SHIELD", "SWORD", "AXE", "TRIDENT", "MACE");
            case "minecraft:piercing_weapon" -> materialContains(value, "TRIDENT");
            case "minecraft:kinetic_weapon" -> materialContains(value, "MACE");
            case "minecraft:attack_range" -> materialContains(value, "SWORD", "AXE", "TRIDENT", "MACE");
            case "minecraft:swing_animation" -> materialContains(value, "SWORD", "AXE", "TRIDENT", "MACE");
            default -> false;
        };
    }

    private boolean materialContains(String material, String... tokens) {
        for (String token : tokens) {
            if (material.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private int attributeBrowseRank(String id) {
        return switch (id != null ? id : "") {
            case "minecraft:item_name",
                 "minecraft:custom_name",
                 "minecraft:lore",
                 "minecraft:tooltip_display",
                 "minecraft:tooltip_style" -> 0;
            case "minecraft:custom_model_data",
                 "minecraft:item_model",
                 "minecraft:dyed_color",
                 "minecraft:enchantment_glint_override",
                 "minecraft:attribute_modifiers",
                 "minecraft:trim",
                 "minecraft:firework_explosion",
                 "minecraft:fireworks",
                 "minecraft:banner_patterns",
                 "minecraft:pot_decorations",
                 "minecraft:charged_projectiles",
                 "minecraft:bundle_contents",
                 "minecraft:container" -> 1;
            case "minecraft:consumable",
                 "minecraft:food",
                 "minecraft:use_cooldown",
                 "minecraft:use_remainder",
                 "minecraft:use_effects",
                 "minecraft:damage_resistant",
                 "minecraft:potion_contents",
                 "minecraft:suspicious_stew_effects",
                 "minecraft:can_break",
                 "minecraft:can_place_on" -> 2;
            case "minecraft:max_stack_size",
                 "minecraft:max_damage",
                 "minecraft:damage",
                 "minecraft:unbreakable",
                 "minecraft:repairable",
                 "minecraft:repair_cost",
                 "minecraft:rarity" -> 3;
            case "minecraft:enchantable",
                 "minecraft:enchantments",
                 "minecraft:stored_enchantments" -> 4;
            case "minecraft:instrument",
                 "minecraft:jukebox_playable",
                 "minecraft:equippable",
                 "minecraft:glider",
                 "minecraft:intangible_projectile",
                 "minecraft:ominous_bottle_amplifier",
                 "minecraft:container_loot",
                 "minecraft:writable_book_content",
                 "minecraft:written_book_content",
                 "minecraft:bees",
                 "minecraft:creative_slot_lock",
                 "minecraft:lock",
                 "minecraft:lodestone_tracker",
                 "minecraft:block_state",
                 "minecraft:block_entity_data",
                 "minecraft:bucket_entity_data",
                 "minecraft:entity_data",
                 "minecraft:debug_stick_state",
                 "minecraft:recipes",
                 "minecraft:tool",
                 "minecraft:death_protection",
                 "minecraft:blocks_attacks",
                 "minecraft:piercing_weapon",
                 "minecraft:kinetic_weapon",
                 "minecraft:attack_range",
                 "minecraft:swing_animation" -> 5;
            default -> 10;
        };
    }

    private int attributeBrowseRank(OptionCatalogItem item) {
        return attributeBrowseRank(item, item != null ? item.getValue() : "");
    }

    private int attributeBrowseRank(OptionCatalogItem item, String id) {
        int fallback = attributeBrowseRank(id) * 100;
        return metadataInt(item, "priority", fallback);
    }

    private int attributeBrowseGroupRank(OptionCatalogItem item) {
        return attributeBrowseGroupRank(item, item != null ? item.getValue() : "");
    }

    private int attributeBrowseGroupRank(OptionCatalogItem item, String id) {
        return switch (attributeBrowseGroup(id, item)) {
            case "Text" -> 0;
            case "Visuals" -> 1;
            case "Use" -> 2;
            case "Combat" -> 3;
            case "Durability" -> 4;
            case "Enchanting" -> 5;
            case "Inventory" -> 6;
            case "Rules" -> 7;
            case "Effects" -> 8;
            case "World" -> 9;
            case "Entity Variants" -> 10;
            case "Data" -> 11;
            case "Behavior" -> 2;
            case "Special" -> 9;
            default -> 12;
        };
    }

    private String attributeBrowseGroup(String id) {
        return switch (attributeBrowseRank(id)) {
            case 0 -> "Text";
            case 1 -> "Visuals";
            case 2 -> "Behavior";
            case 3 -> "Durability";
            case 4 -> "Enchanting";
            case 5 -> "Special";
            default -> "Other";
        };
    }

    private String attributeBrowseGroup(String id, OptionCatalogItem item) {
        String group = item != null ? item.getGroup() : "";
        if (group != null && !group.isBlank()) {
            return group;
        }
        group = metadataString(item, "category", "");
        if (!group.isBlank()) {
            return group;
        }
        return attributeBrowseGroup(id);
    }

    private String attributeBrowseGroupDescription(String group) {
        return switch (group) {
            case "Text" -> "Names And Tooltip";
            case "Visuals" -> "Model And Appearance";
            case "Use" -> "Eating And Reuse";
            case "Combat" -> "Damage And Attacks";
            case "Behavior" -> "Use And Placement";
            case "Durability" -> "Limits And Damage";
            case "Enchanting" -> "Levels And Modifiers";
            case "Inventory" -> "Stored Items";
            case "Rules" -> "Adventure Rules";
            case "Effects" -> "Potions And Status";
            case "World" -> "World Interactions";
            case "Entity Variants" -> "Entity-Specific Data";
            case "Data" -> "Custom Component Data";
            case "Special" -> "Extra Item Behavior";
            default -> "Search Results";
        };
    }

    private boolean hasIntentionalAttributeEditor(String id) {
        if (hasSpecializedAttributeEditor(id)) {
            return true;
        }
        return !attributeComponentSchema(id).isEmpty();
    }

    private boolean hasSpecializedAttributeEditor(String id) {
        return switch (id != null ? id : "") {
            case "minecraft:consumable",
                 "minecraft:food",
                 "minecraft:use_cooldown",
                 "minecraft:use_remainder",
                 "minecraft:enchantment_glint_override",
                 "minecraft:custom_model_data",
                 "minecraft:item_model",
                 "minecraft:item_name",
                 "minecraft:custom_name",
                 "minecraft:lore",
                 "minecraft:tooltip_display",
                 "minecraft:tooltip_style",
                 "minecraft:writable_book_content",
                 "minecraft:written_book_content",
                 "minecraft:attribute_modifiers",
                 "minecraft:can_break",
                 "minecraft:can_place_on",
                 "minecraft:trim",
                 "minecraft:firework_explosion",
                 "minecraft:fireworks",
                 "minecraft:banner_patterns",
                 "minecraft:pot_decorations",
                 "minecraft:charged_projectiles",
                 "minecraft:bundle_contents",
                 "minecraft:container",
                 "minecraft:max_stack_size",
                 "minecraft:max_damage",
                 "minecraft:damage",
                 "minecraft:repair_cost",
                 "minecraft:rarity",
                 "minecraft:enchantable",
                 "minecraft:ominous_bottle_amplifier",
                 "minecraft:instrument",
                 "minecraft:jukebox_playable",
                 "minecraft:dyed_color",
                 "minecraft:enchantments",
                 "minecraft:stored_enchantments",
                 "minecraft:potion_contents",
                 "minecraft:glider",
                 "minecraft:intangible_projectile",
                 "minecraft:damage_resistant",
                 "minecraft:weapon",
                 "minecraft:blocks_attacks",
                 "minecraft:piercing_weapon",
                 "minecraft:kinetic_weapon",
                 "minecraft:attack_range",
                 "minecraft:swing_animation",
                 "minecraft:use_effects",
                 "minecraft:repairable",
                 "minecraft:container_loot",
                 "minecraft:tool",
                 "minecraft:death_protection",
                 "minecraft:bees",
                 "minecraft:creative_slot_lock",
                 "minecraft:lock",
                 "minecraft:suspicious_stew_effects",
                 "minecraft:lodestone_tracker",
                 "minecraft:block_state",
                 "minecraft:block_entity_data",
                 "minecraft:bucket_entity_data",
                 "minecraft:entity_data",
                 "minecraft:debug_stick_state",
                 "minecraft:recipes",
                 "minecraft:equippable",
                 "minecraft:unbreakable" -> true;
            default -> false;
        };
    }

    private boolean matchesAttributeSearch(String id, String query) {
        return matchesAttributeSearch(id, query, null);
    }

    private boolean matchesAttributeSearch(String id, String query, OptionCatalogItem item) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        String haystack = (id + " "
            + componentLabel(id) + " "
            + (item != null ? item.getLabel() : "") + " "
            + (item != null ? item.getDescription() : "") + " "
            + (item != null ? item.getGroup() : "") + " "
            + metadataSearchText(item != null ? item.getMetadata() : Map.of()) + " "
            + attributeSearchAliases(id)).toLowerCase(Locale.ROOT);
        return haystack.contains(needle);
    }

    private int metadataInt(OptionCatalogItem item, String key, int fallback) {
        Object value = metadataValue(item, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean metadataBoolean(OptionCatalogItem item, String key, boolean fallback) {
        Object value = metadataValue(item, key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return fallback;
    }

    private String metadataString(OptionCatalogItem item, String key, String fallback) {
        Object value = metadataValue(item, key);
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        return value.toString();
    }

    private Object metadataValue(OptionCatalogItem item, String key) {
        if (item == null || key == null || key.isBlank()) {
            return null;
        }
        Map<String, Object> metadata = item.getMetadata() != null ? item.getMetadata() : Map.of();
        if (metadata.containsKey(key)) {
            return metadata.get(key);
        }
        Object ui = metadata.get("ui");
        if (ui instanceof Map<?, ?> uiMap) {
            return uiMap.get(key);
        }
        return null;
    }

    private String metadataSearchText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    builder.append(' ').append(entry.getKey());
                }
                builder.append(' ').append(metadataSearchText(entry.getValue()));
            }
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object item : iterable) {
                builder.append(' ').append(metadataSearchText(item));
            }
            return builder.toString();
        }
        return value.toString();
    }

    private String attributeSearchAliases(String id) {
        String value = id != null ? id.toLowerCase(Locale.ROOT) : "";
        if (value.contains("food") || value.contains("consumable")) {
            return "eat edible eatable eating consume";
        }
        if (value.contains("use_cooldown")) {
            return "cooldown delay reuse timer group";
        }
        if (value.contains("use_effects")) {
            return "sprint vibration movement speed";
        }
        if (value.contains("use_remainder")) {
            return "remainder result container bowl bottle after use";
        }
        if (value.contains("custom_model_data") || value.contains("item_model")) {
            return "model texture resource";
        }
        if (value.contains("tooltip") || value.contains("lore")) {
            return "text description line hidden";
        }
        if (value.contains("book_content")) {
            return "book pages title author written writable resolved generation";
        }
        if (value.contains("dyed_color")) {
            return "color tint leather dye rgb hex";
        }
        if (value.contains("enchant")) {
            return "enchantment enchantments level magic glint";
        }
        if (value.contains("attribute_modifiers")) {
            return "attribute modifier damage speed health armor attack";
        }
        if (value.contains("trim")) {
            return "armor trim pattern material smithing";
        }
        if (value.contains("firework")) {
            return "rocket explosion color fade trail twinkle shape";
        }
        if (value.contains("banner_patterns")) {
            return "banner shield pattern color dye";
        }
        if (value.contains("pot_decorations")) {
            return "decorated pot sherd pottery side decoration";
        }
        if (value.contains("charged_projectiles")) {
            return "crossbow loaded arrow projectile rocket";
        }
        if (value.contains("bundle_contents")) {
            return "bundle item contents stored inventory";
        }
        if (value.contains("container")) {
            return "inventory storage slot contents item";
        }
        if (value.contains("potion")) {
            return "potion effect bottle color";
        }
        if (value.contains("can_break")) {
            return "break mine block tag tool adventure";
        }
        if (value.contains("can_place_on")) {
            return "place block tag adventure";
        }
        if (value.contains("jukebox")) {
            return "music disc song record sound";
        }
        if (value.contains("instrument")) {
            return "goat horn sound";
        }
        if (value.contains("glider")) {
            return "elytra flight movement";
        }
        if (value.contains("equippable")) {
            return "equipment armor slot sound wearable";
        }
        if (value.contains("damage_resistant")) {
            return "fire lava damage resistant immunity type tag";
        }
        if (value.contains("weapon")) {
            return "weapon attack damage durability shield blocking disable";
        }
        if (value.contains("blocks_attacks")) {
            return "blocking shield block attacks parry reduction durability sound";
        }
        if (value.contains("attack_range")) {
            return "reach range hitbox distance combat";
        }
        if (value.contains("swing_animation")) {
            return "swing animation attack duration";
        }
        if (value.contains("repairable")) {
            return "repair ingredient anvil durability";
        }
        if (value.contains("container_loot")) {
            return "loot table seed container generated";
        }
        if (value.contains("bees")) {
            return "bee hive nest stored entity ticks";
        }
        if (value.contains("lock")) {
            return "lock creative slot container key";
        }
        if (value.contains("suspicious_stew")) {
            return "stew effect potion duration";
        }
        if (value.contains("lodestone_tracker")) {
            return "lodestone compass dimension position tracked";
        }
        if (value.contains("block_state")) {
            return "block state waterlogged facing powered open";
        }
        if (value.contains("entity_data") || value.contains("block_entity_data") || value.contains("bucket_entity_data")) {
            return "nbt entity block entity id health age noai";
        }
        if (value.contains("debug_stick_state")) {
            return "debug stick block property state";
        }
        if (value.contains("recipes")) {
            return "recipe unlock knowledge book crafting";
        }
        if (value.contains("death_protection")) {
            return "totem death effect protection sound";
        }
        if (value.contains("enchantable")) {
            return "enchant table enchantment power";
        }
        return "";
    }

    private Map<String, Object> activateNormalizedAttributeComponents(Map<String, Object> components) {
        Map<String, Object> normalized = normalizeAttributeComponents(components);
        if (!normalized.equals(copyAttributeComponents(components))) {
            setProperty("components", copyAttributeComponents(normalized));
            updateSummary();
            refreshContentPanelIfAttributeDesignerClosed();
        }
        return normalized;
    }

    private Map<String, Object> normalizeAttributeComponents(Map<String, Object> components) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (components == null) {
            return normalized;
        }
        boolean hideTooltip = false;
        boolean fireResistant = false;
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if ("minecraft:hide_tooltip".equals(entry.getKey())) {
                hideTooltip = !Boolean.FALSE.equals(entry.getValue());
                continue;
            }
            if ("minecraft:hide_additional_tooltip".equals(entry.getKey())) {
                continue;
            }
            if ("minecraft:fire_resistant".equals(entry.getKey())) {
                fireResistant = !Boolean.FALSE.equals(entry.getValue());
                continue;
            }
            Object value = normalizeAttributeComponentValue(entry.getKey(), entry.getValue());
            if (value != null) {
                normalized.put(entry.getKey(), copyAttributeValue(value));
            }
        }
        if (hideTooltip) {
            Map<String, Object> tooltip = objectValue(normalized.get("minecraft:tooltip_display"));
            tooltip.put("hide_tooltip", true);
            normalized.put("minecraft:tooltip_display", tooltip);
        }
        if (fireResistant && !normalized.containsKey("minecraft:damage_resistant")) {
            normalized.put("minecraft:damage_resistant", Map.of("types", "#minecraft:is_fire"));
        }
        return normalized;
    }

    private Object normalizeAttributeComponentValue(String id, Object value) {
        if (value == null) {
            return null;
        }
        if ("minecraft:attribute_modifiers".equals(id) && value instanceof Map<?, ?> map && map.containsKey("modifiers")) {
            return copyAttributeValue(map.get("modifiers"));
        }
        if (("minecraft:can_break".equals(id) || "minecraft:can_place_on".equals(id)) && value instanceof Map<?, ?> map && map.containsKey("predicates")) {
            return copyAttributeValue(map.get("predicates"));
        }
        if ("minecraft:trim".equals(id) && value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && !"show_in_tooltip".equals(entry.getKey().toString())) {
                    copy.put(entry.getKey().toString(), copyAttributeValue(entry.getValue()));
                }
            }
            return copy;
        }
        if ("minecraft:dyed_color".equals(id) && value instanceof Map<?, ?> map) {
            Object rgb = map.get("rgb");
            if (rgb instanceof Number number) {
                return Math.clamp(number.intValue(), 0, 0xFFFFFF);
            }
            if (rgb != null) {
                return parseColorValue(0xFFFFFF, rgb.toString());
            }
        }
        if ("minecraft:jukebox_playable".equals(id) && value instanceof Map<?, ?> map && map.get("song") != null) {
            return normalizeMinecraftKey(map.get("song").toString());
        }
        if ("minecraft:blocks_attacks".equals(id) && value instanceof Map<?, ?> map) {
            Map<String, Object> copy = objectValue(map);
            if (copy.containsKey("disable_sound") && !copy.containsKey("disabled_sound")) {
                copy.put("disabled_sound", copy.remove("disable_sound"));
            } else {
                copy.remove("disable_sound");
            }
            return copy;
        }
        if (("minecraft:enchantments".equals(id) || "minecraft:stored_enchantments".equals(id)) && value instanceof Map<?, ?> map && map.containsKey("levels")) {
            return copyAttributeValue(map.get("levels"));
        }
        if ("minecraft:enchantable".equals(id) && !(value instanceof Map<?, ?>)) {
            return Map.of("value", parsePositiveInt(value, 10));
        }
        if (("minecraft:unbreakable".equals(id) || "minecraft:glider".equals(id) || "minecraft:intangible_projectile".equals(id)) && value instanceof Boolean bool) {
            return bool ? Map.of() : null;
        }
        return copyAttributeValue(value);
    }

    private Map<String, Object> copyAttributeComponents(Map<String, Object> components) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (components == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), copyAttributeValue(entry.getValue()));
            }
        }
        return copy;
    }

    private Object copyAttributeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(entry.getKey().toString(), copyAttributeValue(entry.getValue()));
                }
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(copyAttributeValue(item));
            }
            return copy;
        }
        return value;
    }

    private String schemaArrayText(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Object item : list) {
            lines.add(formatComponentValue(item));
        }
        return String.join("\n", lines);
    }

    private List<Object> schemaArrayValue(String value) {
        List<Object> result = new ArrayList<>();
        for (String line : splitLines(value)) {
            result.add(parseComponentEditorValue("", line));
        }
        return result;
    }

    private Object defaultComponentValueFromItem(OptionCatalogItem item) {
        if (item == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> metadata = item.getMetadata() != null ? item.getMetadata() : Map.of();
        Object defaultValue = metadata.get("defaultValue");
        if (defaultValue != null) {
            return defaultValue;
        }
        Object exampleValue = metadata.get("exampleValue");
        if (exampleValue != null) {
            return exampleValue;
        }
        return defaultValueForSchema(schemaMap(metadata.get("schema")));
    }

    private String editorPathLabel(String path) {
        if (path == null || path.isBlank()) {
            return "Value";
        }
        int split = path.lastIndexOf('.');
        String token = split >= 0 ? path.substring(split + 1) : path;
        if (token.contains(":")) {
            token = token.substring(token.indexOf(':') + 1);
        }
        if (token.matches("\\d+")) {
            return "Item " + (Integer.parseInt(token) + 1);
        }
        return humanizeAttributeToken(token);
    }

    private String humanizeAttributeToken(String token) {
        return switch (token != null ? token : "") {
            case "show_in_tooltip" -> "Show In Tooltip";
            case "hidden_components" -> "Hidden Components";
            case "custom_color", "rgb", "color" -> "Color";
            case "levels" -> "Levels";
            case "potion" -> "Potion";
            case "custom_effects" -> "Effects";
            case "consume_seconds" -> "Use Time";
            case "use_cooldown" -> "Use Cooldown";
            case "use_remainder" -> "Use Remainder";
            case "damage_resistant" -> "Damage Resistant";
            case "weapon" -> "Weapon";
            case "blocks_attacks" -> "Blocks Attacks";
            case "piercing_weapon" -> "Piercing Weapon";
            case "kinetic_weapon" -> "Kinetic Weapon";
            case "attack_range" -> "Attack Range";
            case "swing_animation" -> "Swing Animation";
            case "block_delay_seconds" -> "Block Delay";
            case "disable_cooldown_scale" -> "Cooldown Scale";
            case "damage_reductions" -> "Damage Reductions";
            case "horizontal_blocking_angle" -> "Blocking Angle";
            case "item_damage" -> "Item Damage";
            case "deals_knockback" -> "Knockback";
            case "dismounts" -> "Dismounts";
            case "hit_sound" -> "Hit Sound";
            case "contact_cooldown_ticks" -> "Contact Cooldown";
            case "delay_ticks" -> "Delay";
            case "dismount_conditions" -> "Dismount Conditions";
            case "max_duration_ticks" -> "Dismount Duration";
            case "min_speed" -> "Minimum Speed";
            case "min_relative_speed" -> "Relative Speed";
            case "forward_movement" -> "Forward Movement";
            case "damage_multiplier" -> "Damage Multiplier";
            case "min_reach" -> "Minimum Reach";
            case "max_reach" -> "Maximum Reach";
            case "min_creative_reach" -> "Creative Minimum Reach";
            case "max_creative_reach" -> "Creative Maximum Reach";
            case "hitbox_margin" -> "Hitbox Margin";
            case "mob_factor" -> "Mob Factor";
            case "can_sprint" -> "Can Sprint";
            case "interact_vibrations" -> "Use Vibration";
            case "speed_multiplier" -> "Speed Multiplier";
            case "loot_table" -> "Loot Table";
            case "has_consume_particles" -> "Show Particles";
            case "can_always_eat" -> "Always Eat";
            case "saturation_modifier" -> "Saturation";
            case "nutrition" -> "Food Restored";
            case "animation" -> "Animation";
            case "sound" -> "Sound";
            case "floats" -> "Model Data";
            case "strings" -> "Text Values";
            case "flags" -> "Flags";
            case "colors" -> "Colors";
            case "id" -> "ID";
            case "type" -> "Type";
            case "amount" -> "Amount";
            case "operation" -> "Operation";
            case "slot" -> "Slot";
            case "min", "minimum" -> "Minimum";
            case "max", "maximum" -> "Maximum";
            case "value" -> "Value";
            default -> titleCaseAttributeToken(token);
        };
    }

    private String titleCaseAttributeToken(String token) {
        if (token == null || token.isBlank()) {
            return "Value";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : token.replace('_', ' ').replace('-', ' ').replace('.', ' ').split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            if ("id".equalsIgnoreCase(part)) {
                builder.append("ID");
            } else if ("rgb".equalsIgnoreCase(part)) {
                builder.append("RGB");
            } else {
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.isEmpty() ? "Value" : builder.toString();
    }

    private OptionCatalogItem attributeCatalogItem(String source, String id) {
        AttributeProjection projection = currentAttributeProjection();
        if (projection == null || id == null) {
            return null;
        }
        if (Objects.equals(source, projection.schemaSource())) {
            return projection.catalogByKey().get(normalizedProjectionKey(id));
        }
        if ("server:minecraft:attribute".equals(source)) {
            return projection.attributeValueCatalogByKey().get(normalizedProjectionKey(id));
        }
        return null;
    }

    private String attributeValueLabel(String source, String value) {
        OptionCatalogItem item = attributeCatalogItem(source, value);
        if (item != null && item.getLabel() != null && !item.getLabel().isBlank()) {
            return item.getLabel();
        }
        String cleaned = value != null && value.contains(":") ? value.substring(value.indexOf(':') + 1) : String.valueOf(value);
        if (cleaned.startsWith("generic.")) {
            cleaned = cleaned.substring("generic.".length());
        }
        return titleCaseAttributeToken(cleaned);
    }

    private String componentLabel(String id) {
        AttributeProjection projection = currentAttributeProjection();
        String label = projection != null && id != null ? projection.componentLabelsByKey().get(normalizedProjectionKey(id)) : null;
        if (label != null && !label.isBlank()) {
            return label;
        }
        String cleaned = id != null && id.contains(":") ? id.substring(id.indexOf(':') + 1) : String.valueOf(id);
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.replace('_', ' ').replace('.', ' ').replace('/', ' ').split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return builder.isEmpty() ? cleaned : builder.toString();
    }

    private String attributeSchemaSource(String material) {
        String normalized = material != null && !material.isBlank() ? material.toLowerCase(Locale.ROOT) : "";
        return normalized.isBlank() ? ATTRIBUTE_SCHEMA_SOURCE : ATTRIBUTE_SCHEMA_SOURCE + ":" + normalized;
    }

    private String attributeCatalogSource(String source) {
        return source != null && source.startsWith(ATTRIBUTE_SCHEMA_SOURCE + ":") ? ATTRIBUTE_SCHEMA_SOURCE : source;
    }

    private Map<String, Object> attributeCatalogContext(String source) {
        if (source == null || !source.startsWith(ATTRIBUTE_SCHEMA_SOURCE + ":")) {
            return Map.of();
        }
        String material = source.substring((ATTRIBUTE_SCHEMA_SOURCE + ":").length());
        return material.isBlank() ? Map.of() : Map.of("material", material.toUpperCase(Locale.ROOT));
    }

    private List<OptionCatalogItem> attributeCatalogItems(String source) {
        String catalogSource = attributeCatalogSource(source);
        Map<String, Object> context = attributeCatalogContext(source);
        ReSyncFlowClient client = flowManager != null ? flowManager.existingFlowClient(serverId) : null;
        String contextKey = client != null ? client.optionCatalogContextKey(context) : "";
        return OptionCatalogCache.getInstance().getItems(serverId, catalogSource, contextKey);
    }

    private boolean hasAttributeCatalog(String source) {
        String catalogSource = attributeCatalogSource(source);
        Map<String, Object> context = attributeCatalogContext(source);
        ReSyncFlowClient client = flowManager != null ? flowManager.existingFlowClient(serverId) : null;
        String contextKey = client != null ? client.optionCatalogContextKey(context) : "";
        return OptionCatalogCache.getInstance().hasCatalog(serverId, catalogSource, contextKey);
    }

    private void preloadAttributeSchema() {
        requestCatalog(ATTRIBUTE_SCHEMA_SOURCE);
        AttributeProjection projection = currentAttributeProjection();
        if (projection != null) {
            requestCatalog(projection.schemaSource());
        }
    }

    private String formatComponentValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private Object parseComponentEditorValue(Object previous, String value) {
        if (previous instanceof Number) {
            try {
                String trimmed = value.trim();
                if (previous instanceof Byte || previous instanceof Short || previous instanceof Integer) {
                    return Integer.parseInt(trimmed);
                }
                if (previous instanceof Long) {
                    return Long.parseLong(trimmed);
                }
                double parsed = Double.parseDouble(trimmed);
                if (!trimmed.contains(".") && !trimmed.contains("e") && !trimmed.contains("E")) {
                    return (long) parsed;
                }
                return parsed;
            } catch (NumberFormatException ignored) {
                return previous;
            }
        }
        return value;
    }

    private RowWidget searchableInputRow(TextInputWidget input, List<String> options, boolean append, String loadingCatalog) {
        SquareButtonWidget selectorButton = new SquareButtonWidget.Builder()
            .imagePath("search.png")
            .size(18, 18)
            .hint("Search")
            .entranceAnimation(false)
            .onClick(() -> {
                List<String> choices = normalizedOptions(options, input.getText());
                showSearchSelector(choices, input.getText(), value -> {
                    if (!isRealOption(value)) {
                        return;
                    }
                    if (append && input.getText() != null && !input.getText().isBlank()) {
                        List<String> existing = splitCsv(input.getText());
                        if (!existing.contains(value)) {
                            existing.add(value);
                        }
                        input.setText(String.join(", ", existing));
                    } else {
                        input.setText(value);
                    }
                    input.runOnChange();
                }, input.getX(), input.getY() + input.getHeight(), loadingCatalog);
            })
            .build();
        ReSyncStudioPanelState.disableEntrance(input);
        RowWidget row = new RowWidget.Builder()
            .size(260, 18)
            .padding(4)
            .addWidget(input)
            .addWidget(selectorButton)
            .build();
        ReSyncStudioPanelState.disableEntrance(row);
        return row;
    }

    private String selectedDropdownValue(DropDownWidget<String> dropdown) {
        if (dropdown == null) {
            return "";
        }
        String value = dropdown.getSelectedItem();
        return isRealOption(value) ? value : "";
    }

    private List<String> normalizedOptions(List<String> choices, String selected) {
        List<String> options = new ArrayList<>();
        if (choices != null) {
            for (String choice : choices) {
                if (choice != null && !choice.isBlank() && !options.contains(choice)) {
                    options.add(choice);
                }
            }
        }
        if (options.isEmpty()) {
            options.add("No Options");
        }
        if (selected != null && !selected.isBlank() && !"Loading".equals(selected) && !options.contains(selected)) {
            options.addFirst(selected);
        }
        return options;
    }

    private String resolveSelectedOption(List<String> options, String selected) {
        if (selected != null && options.contains(selected)) {
            return selected;
        }
        if (selected != null) {
            String normalized = selected.toUpperCase(Locale.ROOT);
            if (options.contains(normalized)) {
                return normalized;
            }
        }
        return options.isEmpty() ? null : options.getFirst();
    }

    private boolean isRealOption(String value) {
        return value != null && !"Loading".equals(value) && !"No Options".equals(value);
    }

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split("[,\\r\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private List<String> splitLines(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private void showSearchSelector(List<String> options, String selected, Consumer<String> onSelected, int x, int y) {
        if (options == null || options.isEmpty()) {
            return;
        }
        closeNodeItemSelector();
        ScreenManager manager = ScreenManager.getInstance();
        int currentMouseX = manager.getMouseX();
        int currentMouseY = manager.getMouseY();
        int selectorX = currentMouseX > 0 ? currentMouseX : x;
        int selectorY = currentMouseY > 0 ? currentMouseY : y;
        showStudioSelector(options, selected, selectorX, selectorY, selector -> {
            for (String option : options.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
                selector.addItem(option, () -> onSelected.accept(option));
            }
        });
    }

    private void showSearchSelector(List<String> options, String selected, Consumer<String> onSelected, int x, int y, String catalog) {
        if (catalog == null || catalog.isBlank()) {
            showSearchSelector(options, selected, onSelected, x, y);
            return;
        }
        String source = attributeCatalogSource(catalog);
        Map<String, Object> context = attributeCatalogContext(catalog);
        showCatalogSearchSelector(options, selected, onSelected, x, y, source, context);
    }

    private void showCatalogSearchSelector(List<String> options, String selected, Consumer<String> onSelected, int x, int y,
        String source, Map<String, Object> context) {
        closeNodeItemSelector();
        ScreenManager manager = ScreenManager.getInstance();
        int currentMouseX = manager.getMouseX();
        int currentMouseY = manager.getMouseY();
        int selectorX = currentMouseX > 0 ? currentMouseX : x;
        int selectorY = currentMouseY > 0 ? currentMouseY : y;
        ItemSelectorWidget selector = new ItemSelectorWidget.Builder(this)
            .size(220, 240)
            .dismissOnSelect(true)
            .emptyMessage("No Options")
            .asyncItems(OptionCatalogSelector.refreshAction(serverId, source, context),
                () -> OptionCatalogSelector.snapshot(serverId, source, context, () -> options, () -> selected, onSelected, "No Options"))
            .build();
        showStudioSelector(selector, OptionCatalogSelector.label(serverId, source, context, selected), selectorX, selectorY);
    }

    private void requestCatalog(String source) {
        requestCatalog(attributeCatalogSource(source), attributeCatalogContext(source));
    }

    private void requestCatalog(String source, Map<String, Object> context) {
        OptionCatalogLoader.preload(serverId, source, context);
    }

    private void preloadContentCatalogs() {
        CONTENT_CATALOGS.preload(serverId);
    }

    private void refreshWorldOptions() {
        OptionCatalogLoader.refresh(serverId, "server:minecraft:world");
    }

    private void setBranchEnabled(String branch, boolean enabled) {
        if (deferFrozenWorkspaceMutation(() -> setBranchEnabled(branch, enabled))) {
            return;
        }
        List<String> branches = enabled
            ? new ArrayList<>(CustomContentGraphAdapter.getEnabledTriggerBranches(graph))
            : new ArrayList<>(storedBranches());
        if (enabled && !branches.contains(branch)) {
            branches.add(branch);
        } else if (!enabled) {
            branches.remove(branch);
            detachBranch(branch);
        }
        if (branches.isEmpty()) {
            selectedBranch = "";
        } else {
            selectedBranch = branches.contains(selectedBranch) ? selectedBranch : branches.getFirst();
        }
        CustomContentGraphAdapter.setEnabledTriggerBranches(graph, branches);
        markWorkspaceMutation();
        refreshNodeRegistry();
        updateSummary();
        updateEventRows();
    }

    private void synchronizeContentIdentity() {
        if (contentCoreSession != null) {
            contentId = contentCoreSession.resource().id();
            graph.setId(contentId);
            CustomContentGraphAdapter.setContentProperty(graph, "content_id", contentId);
            return;
        }
        Object value = CustomContentGraphAdapter.getContentProperty(graph, "content_id", contentId != null ? contentId : "");
        String id = value != null ? String.valueOf(value).trim() : "";
        String type = CustomContentGraphAdapter.contentType(graph);
        contentId = id;
        if (type != null && !id.isBlank()) {
            graph.setId(CustomContentGraphAdapter.contentFlowId(type, id));
        }
    }

    private void setProperty(String key, Object value) {
        if (deferFrozenWorkspaceMutation(() -> setProperty(key, value))) {
            return;
        }
        CustomContentGraphAdapter.setContentProperty(graph, key, value);
        if ("content_id".equals(key)) {
            synchronizeContentIdentity();
        }
        updateAttributeProjectionProperty(key, value);
        markWorkspaceMutation();
    }

    @Override
    protected boolean applyWorkspaceDocumentChanges(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        if (document == null || patches == null || patches.isEmpty()) {
            return false;
        }
        String rootNodeId = contentRootNodeId(graph);
        if (rootNodeId == null || patches.stream().anyMatch(patch -> !isContentWorkspacePatch(patch.path(), rootNodeId))) {
            return false;
        }
        FlowNode currentRoot = CustomContentGraphAdapter.findStartNode(graph);
        JsonObject nodes = object(document, "nodes");
        JsonObject incomingRoot = nodes != null ? object(nodes, rootNodeId) : null;
        if (currentRoot == null || incomingRoot == null) {
            return false;
        }
        String rootPath = "/nodes/" + escapeWorkspacePath(rootNodeId);
        String inputPath = rootPath + "/inputValues/";
        Set<String> changedInputs = new LinkedHashSet<>();
        Set<String> changedProperties = new LinkedHashSet<>();
        for (WorkspacePatch<JsonElement> patch : patches) {
            if (patch.path().startsWith(inputPath)) {
                changedInputs.add(rootWorkspaceKey(patch.path().substring(inputPath.length())));
            } else if (patch.path().startsWith("/contentProperties/")) {
                changedProperties.add(rootWorkspaceKey(patch.path().substring("/contentProperties/".length())));
            }
        }
        boolean typeChanged = patches.stream().anyMatch(patch -> patch.path().equals(rootPath + "/type"));
        boolean inputsChanged = !changedInputs.isEmpty() || typeChanged;
        if (inputsChanged) {
            JsonObject incomingInputs = object(incomingRoot, "inputValues");
            if (currentRoot.getInputValues() == null) {
                currentRoot.setInputValues(new LinkedHashMap<>());
            }
            applyValues(currentRoot.getInputValues(), incomingInputs, changedInputs);
            currentRoot.setType(text(incomingRoot.get("type")));
            if (typeChanged || changedInputs.contains("content_id")) {
                synchronizeContentIdentity();
            }
            if (typeChanged || changedInputs.stream().anyMatch(key -> !STUDIO_ROOT_INPUTS.contains(key))) {
                refreshInputWidgets(rootNodeId);
            }
        }
        if (!changedProperties.isEmpty()) {
            applyValues(graph.getContentProperties(), object(document, "contentProperties"), changedProperties);
        }
        updateAttributeProjectionWorkspace(changedInputs, changedProperties, currentRoot);
        graph.setResourceRevision(number(document.get("resourceRevision")));
        graph.setResourceHash(text(document.get("resourceHash")));
        graph.setResourceMutationId(text(document.get("resourceMutationId")));
        return true;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent != null ? parent.get(key) : null;
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static void applyValues(Map<String, Object> target, JsonObject source, Set<String> keys) {
        if (target == null || keys == null) {
            return;
        }
        for (String key : keys) {
            JsonElement value = source != null ? source.get(key) : null;
            if (value == null || value.isJsonNull()) {
                target.remove(key);
            } else {
                target.put(key, COLLABORATION_GSON.fromJson(value, Object.class));
            }
        }
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static long number(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0L;
    }

    @Override
    protected void onWorkspaceGraphApplied(JsonObject document, List<WorkspacePatch<JsonElement>> patches) {
        String rootNodeId = contentRootNodeId(graph);
        Set<String> changedKeys = contentChangedKeys(patches, rootNodeId);
        if (patches == null || patches.isEmpty()) {
            changedKeys = Set.of("__type");
        }
        boolean structural = changedKeys.stream().anyMatch(ContentDesignerScreen::isStructuralContentKey);
        if (structural) {
            float scroll = contentPanel != null ? contentPanel.container().getScrollOffset() : 0.0f;
            refreshContentPanel();
            if (contentPanel != null) {
                contentPanel.container().setScrollOffset(scroll);
                contentPanel.container().setTargetScrollOffset(scroll);
            }
        } else {
            for (String key : changedKeys) {
                Consumer<Object> binding = contentCollaborationBindings.get(key);
                if (binding != null) {
                    binding.accept(contentCollaborationValue(key));
                }
            }
            updateSummary();
            updateEventRows();
        }
    }

    private static Set<String> contentChangedKeys(List<WorkspacePatch<JsonElement>> patches, String rootNodeId) {
        if (patches == null || rootNodeId == null) {
            return Set.of();
        }
        Set<String> changedKeys = new LinkedHashSet<>();
        String prefix = "/nodes/" + escapeWorkspacePath(rootNodeId) + "/inputValues/";
        for (WorkspacePatch<JsonElement> patch : patches) {
            if (patch.path().startsWith(prefix)) {
                changedKeys.add(rootWorkspaceKey(patch.path().substring(prefix.length())));
            } else if (patch.path().startsWith("/contentProperties/")) {
                changedKeys.add(rootWorkspaceKey(patch.path().substring("/contentProperties/".length())));
            } else if (patch.path().equals("/nodes/" + escapeWorkspacePath(rootNodeId) + "/type")) {
                changedKeys.add("__type");
            }
        }
        return changedKeys;
    }

    private boolean isContentWorkspacePatch(String path, String rootNodeId) {
        String root = "/nodes/" + escapeWorkspacePath(rootNodeId);
        return path.startsWith(root + "/inputValues/") || path.equals(root + "/type") || path.startsWith("/contentProperties/")
            || Set.of("/resourceRevision", "/resourceHash", "/resourceMutationId").contains(path);
    }

    private Object contentCollaborationValue(String key) {
        if ("__type".equals(key)) {
            return CustomContentGraphAdapter.contentType(graph);
        }
        if (key.startsWith("projectile.") || "armor_slot".equals(key)) {
            return CustomContentGraphAdapter.getContentConfiguration(graph, key, "");
        }
        return CustomContentGraphAdapter.getContentProperty(graph, key, "");
    }

    private static boolean isStructuralContentKey(String key) {
        return Set.of("__type", "provider", "components", CustomContentGraphAdapter.FLOW_BRANCHES_KEY).contains(key);
    }

    private static String contentRootNodeId(FlowGraph source) {
        FlowNode root = CustomContentGraphAdapter.findStartNode(source);
        if (root == null) {
            return null;
        }
        return source.getNodes().entrySet().stream().filter(entry -> entry.getValue() == root).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static String escapeWorkspacePath(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String unescapeWorkspacePath(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static String rootWorkspaceKey(String value) {
        int separator = value.indexOf('/');
        return unescapeWorkspacePath(separator >= 0 ? value.substring(0, separator) : value);
    }

    private static String contentCollaborationKey(String label) {
        return switch (label) {
            case "Name" -> "name";
            case "Type" -> "__type";
            case "Armor Slot" -> "armor_slot";
            case "Provider" -> "provider";
            case "Material" -> "material";
            case "External ID" -> "external_id";
            case "Model" -> "custom_model_data";
            case "Permission" -> "permission";
            case "Cooldown" -> "cooldown_ticks";
            case "Chance" -> "chance_percent";
            case "Hand" -> "hand_filter";
            case "Target" -> "target_filter";
            case "Tags" -> "tags";
            case "Projectile" -> "projectile.entity_type";
            case "Launch Source" -> "projectile.launch_source";
            case "Speed" -> "projectile.speed";
            case "Damage" -> "projectile.damage";
            case "Gravity" -> "projectile.gravity";
            case "Glowing" -> "projectile.glowing";
            case "Consume Item" -> "projectile.consume_item";
            case "Pickup" -> "projectile.pickup";
            case "Fire Sound" -> "projectile.fire_sound";
            case "Hit Sound" -> "projectile.hit_sound";
            case "Sound Volume" -> "projectile.sound_volume";
            case "Sound Pitch" -> "projectile.sound_pitch";
            case "Remove On Hit" -> "projectile.remove_on_hit";
            default -> null;
        };
    }

    private static String contentCollaborationDropdownValue(String key, Object value) {
        if (Set.of("projectile.gravity", "projectile.glowing", "projectile.consume_item", "projectile.remove_on_hit").contains(key)) {
            boolean enabled = value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
            return enabled ? "Enabled" : "Disabled";
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static String collaborationKey(String label) {
        String key = contentCollaborationKey(label);
        return key != null ? key : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private String textProperty(String key) {
        Object value = CustomContentGraphAdapter.getContentProperty(graph, key, "");
        return value == null ? "" : String.valueOf(value);
    }

    private boolean boolProperty(String key) {
        Object value = CustomContentGraphAdapter.getContentProperty(graph, key, false);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private String firstBranch() {
        List<String> branches = CustomContentGraphAdapter.getEnabledTriggerBranches(graph);
        return branches.isEmpty() ? "use" : branches.getFirst();
    }

    private String contentSummary(CustomContentDefinition definition) {
        return definition.getType() + " | " + definition.getProvider();
    }

    private String contentCardPreview(CustomContentDefinition definition, String type) {
        String asset = "vanilla".equalsIgnoreCase(definition.getProvider()) ? definition.getMaterial() : definition.getExternalId();
        return type + " | " + definition.getProvider() + " | " + asset;
    }

    private String contentValidationSummary(CustomContentDefinition definition) {
        List<String> issues = new ArrayList<>();
        if (definition.getId() == null || definition.getId().isBlank()) {
            issues.add("Missing ID");
        }
        if (definition.getDisplayName() == null || definition.getDisplayName().isBlank()) {
            issues.add("Missing Name");
        }
        if (!"vanilla".equalsIgnoreCase(definition.getProvider()) && (definition.getExternalId() == null || definition.getExternalId().isBlank())) {
            issues.add("Missing Asset");
        }
        if (!attributeValidationErrors.isEmpty()) {
            issues.add(attributeValidationErrors.size() == 1 ? "Component Error" : attributeValidationErrors.size() + " Component Errors");
        }
        for (String branch : CustomContentGraphAdapter.getEnabledTriggerBranches(graph)) {
            if (branchActionCount(branch) == 0) {
                issues.add(eventTitle(branch) + " Empty");
            }
        }
        return issues.isEmpty() ? "Ready" : String.join(", ", issues);
    }

    private void updateSummary() {
        if (summaryWidget != null) {
            String name = textProperty("name");
            String type = CustomContentGraphAdapter.contentType(graph);
            String provider = textProperty("provider");
            summaryWidget.setName(name);
            summaryWidget.setDescription((type != null ? type : "item") + " | "
                + (!provider.isBlank() ? provider : "vanilla"));
        }
    }

    private void updateEventRows() {
        List<String> branches = CustomContentGraphAdapter.getEnabledTriggerBranches(graph);
        for (Map.Entry<String, MountableButtonWidget> entry : eventRows.entrySet()) {
            boolean enabled = branches.contains(entry.getKey());
            MountableButtonWidget row = entry.getValue();
            row.setDescription((enabled ? "Enabled" : "Off") + " | " + actionSummary(entry.getKey()));
            row.setSelected(entry.getKey().equals(selectedBranch));
        }
    }

    private String actionSummary(String branch) {
        int count = branchActionCount(branch);
        return count == 1 ? "1 Action" : count + " Actions";
    }

    private int branchActionCount(String branch) {
        return branchActionCount(graph, branch);
    }

    private static int branchActionCount(FlowGraph source, String branch) {
        FlowNode start = CustomContentGraphAdapter.findStartNode(source);
        String startId = null;
        if (start != null && source.getNodes() != null) {
            for (Map.Entry<String, FlowNode> entry : source.getNodes().entrySet()) {
                if (entry.getValue() == start) {
                    startId = entry.getKey();
                    break;
                }
            }
        }
        if (startId == null || source.getConnections() == null) {
            return 0;
        }
        int count = 0;
        String nodeId = startId;
        String pin = branch;
        while (true) {
            FlowConnection next = null;
            for (FlowConnection connection : source.getConnections()) {
                if (nodeId.equals(connection.getSourceNodeId()) && pin.equals(connection.getSourcePin())) {
                    next = connection;
                    break;
                }
            }
            if (next == null) {
                return count;
            }
            count++;
            nodeId = next.getTargetNodeId();
            pin = "flow";
        }
    }

    private String findNodeId(FlowNode node) {
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }

    private List<String> storedBranches() {
        Object value = CustomContentGraphAdapter.getContentProperty(graph, CustomContentGraphAdapter.FLOW_BRANCHES_KEY, List.of());
        List<String> branches = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    branches.add(item.toString());
                }
            }
        }
        return branches;
    }

    private void detachBranch(String branch) {
        FlowNode start = CustomContentGraphAdapter.findStartNode(graph);
        String startId = start != null ? findNodeId(start) : null;
        if (startId == null || graph.getConnections() == null) {
            return;
        }
        graph.getConnections().removeIf(connection -> startId.equals(connection.getSourceNodeId()) && branch.equals(connection.getSourcePin()));
    }

    private List<String> materialOptions() {
        return catalogOptions("server:minecraft:material");
    }

    private List<String> worldOptions() {
        return catalogOptions("server:minecraft:world");
    }

    private List<String> catalogOptions(String source) {
        boolean missing = !OptionCatalogCache.getInstance().hasCatalog(serverId, source);
        requestCatalog(source);
        List<String> values = OptionCatalogCache.getInstance().getValues(serverId, source);
        if (!values.isEmpty()) {
            return values;
        }
        return missing ? List.of("Loading") : List.of();
    }

    private List<String> providerOptions() {
        return catalogOptions("server:custom_content:provider").stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> providerAssetOptions(String provider) {
        return catalogOptions("server:custom_content:asset", customContentCatalogContext(provider));
    }

    private List<String> providerAssetOptions(String provider, String contentType) {
        return catalogOptions("server:custom_content:asset", customContentCatalogContext(provider, contentType));
    }

    private Map<String, Object> customContentCatalogContext(String provider) {
        return customContentCatalogContext(provider, CustomContentGraphAdapter.contentType(graph));
    }

    private Map<String, Object> customContentCatalogContext(String provider, String contentType) {
        return Map.of(
            "provider", provider != null ? provider : "",
            "content_type", contentType != null ? contentType : ""
        );
    }

    private List<String> catalogOptions(String source, Map<String, Object> context) {
        ReSyncFlowClient client = flowManager != null ? flowManager.existingFlowClient(serverId) : null;
        String contextKey = client != null ? client.optionCatalogContextKey(context) : "";
        boolean missing = !OptionCatalogCache.getInstance().hasCatalog(serverId, source, contextKey);
        requestCatalog(source, context);
        List<String> values = OptionCatalogCache.getInstance().getValues(serverId, source, contextKey);
        if (!values.isEmpty()) {
            return values;
        }
        return missing ? List.of("Loading") : List.of();
    }

    private String saveState(CustomContentDefinition definition) {
        SyncedResourceState flowState = flowManager != null ? flowManager.getFlowState(serverId, graph.getId()) : SyncedResourceState.CLEAN;
        SyncedResourceState contentState = flowManager != null && definition != null ? flowManager.getCustomContentState(serverId, definition.getId()) : flowState;
        SyncedResourceState state = contentState != SyncedResourceState.CLEAN ? contentState : flowState;
        return switch (state) {
            case DIRTY -> "Unsaved";
            case SAVING -> "Saving";
            case SAVED -> "Saved";
            case FAILED -> "Failed";
            case STALE -> "Stale";
            default -> "Clean";
        };
    }

    private String eventTitle(String pin) {
        String[] parts = pin.replace('_', ' ').split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return builder.toString();
    }

    private boolean showsHandFilter(String type, String branch) {
        return !"armor".equals(type) && List.of("use", "left_click", "right_click", "hit_entity", "damage_entity", "break_block", "place", "break", "interact", "step_on").contains(branch);
    }

    private boolean showsTargetFilter(String branch) {
        return List.of("hit_entity", "damage_entity", "drop", "pickup", "damaged", "nearby_player", "tick").contains(branch);
    }

    private String defaultMaterial(String type) {
        return switch (type) {
            case "block" -> "STONE";
            case "armor" -> "IRON_CHESTPLATE";
            case "projectile" -> "ARROW";
            default -> "STICK";
        };
    }

    private String iconForType(String type) {
        return switch (type) {
            case "block" -> "block.png";
            case "armor" -> "armor.png";
            default -> "item.png";
        };
    }

    private static final class AttributeComponentRowState {
        private final String id;
        private final MountableButtonWidget row;
        private final ToggleWidget toggle;
        private OptionCatalogItem item;
        private List<AnimatedWidget> editorWidgets;
        private boolean editorDirty;

        private AttributeComponentRowState(String id, MountableButtonWidget row, ToggleWidget toggle) {
            this.id = id;
            this.row = row;
            this.toggle = toggle;
        }
    }

    private static final class AttributeModifierDropdownRefs {
        private DropDownWidget<String> operation;
        private DropDownWidget<String> slot;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return 100.0;
        }
    }

    private static final class StudioRootNodeWidget extends FlowNodeWidget {
        StudioRootNodeWidget(int x, int y, FlowNode node, FlowGraph graph, String nodeId, String serverId,
                             Runnable onClose, Runnable onMutation,
                             ReSyncGenericWidgetCapabilities.WidgetDefinition definition) {
            super(x, y, node, graph, nodeId, serverId, onClose, onMutation,
                FlowNodeWidget.boundaryCatalogForServer(serverId), definition != null ? definition.definition() : null,
                definition == null || definition.readOnly(), definition == null);
        }

        @Override
        protected boolean shouldShowLiteralInput(NodeDefinition.PinDefinition input) {
            return input == null || !STUDIO_ROOT_INPUTS.contains(input.getName());
        }

        @Override
        protected boolean shouldShowInputPin(NodeDefinition.PinDefinition input) {
            return input == null || !STUDIO_ROOT_INPUTS.contains(input.getName());
        }
    }
}
